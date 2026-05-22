(ns predict-rlm-comparison.runner
  "Comparison runner for predict-rlm benchmark ports.

   Mirrors development/bench/runner.clj's public API (start!, run!, stop!,
   generate-summary!) but adds:

     - :iterations preserved in the result EDN (Phase 1 researcher iterations)
     - :by-node preserved (Phase 2 per-leaf-node token usage breakdown)
     - :node-trace queried from the event store after each run
     - per-run mulog trace file (.trace.edn alongside the result .edn)
     - single-task-lock that refuses concurrent runs

   See docs/issues/predict-rlm/PR05-comparison-runner-with-capture.md."
  (:require [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]
            [litellm.router :as litellm-router]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def config
  {:model "google/gemini-3-flash-preview"
   ;; 20 minutes default. predict-rlm's larger benchmarks (document_analysis,
   ;; contract_comparison) take 4-6 minutes on their infrastructure; OpenRouter
   ;; latency + our smaller per-page parallelism need more headroom.
   :timeout-ms 1200000
   :documents-dir "development/bench/documents"
   :results-dir "development/bench/predict_rlm_comparison/results"})

;; =============================================================================
;; Single-task-lock
;; =============================================================================

(defonce ^:private task-lock (atom nil))

(defn try-acquire-task-lock!
  "Atomically attempt to acquire the runner's single-task lock.
   Returns true if acquired; false if a task is already in flight."
  [task-name]
  (compare-and-set! task-lock nil {:task-name task-name
                                   :acquired-at (System/currentTimeMillis)}))

(defn release-task-lock!
  "Release the task lock unconditionally. Safe to call from non-owners."
  []
  (reset! task-lock nil))

(defn task-lock-state
  "Snapshot of the lock state; nil when free, map when held."
  []
  @task-lock)

;; =============================================================================
;; System State
;; =============================================================================

(defonce ^:private system-state (atom nil))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/orc-bench-" (random-uuid))
        ;; Default LMDB map size is 10MB. Image-heavy benchmarks
        ;; (e.g. document_redaction with 6 page-renders × ~150KB each
        ;; plus per-tick state projection) blow past that. Bump to 512MB
        ;; for headroom on multi-page visual tasks.
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir
                                               :db-name "bench"
                                               :map-size (* 512 1024 1024)}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :openrouter
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :event-pubsub ps :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ processor] (:processors ctx)] (tp/stop processor))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [cache (:cache ctx)] (kv/stop cache))
  (when-let [event-store (:event-store ctx)] (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [child (.listFiles f)] (.delete child))
        (.delete f)))))

;; =============================================================================
;; Document Loading
;; =============================================================================

(defn- load-document [doc-key]
  (let [path (str (:documents-dir config) "/" (name doc-key) ".txt")]
    (when (.exists (io/file path))
      (slurp path))))

(defn- load-task-documents [task]
  (reduce (fn [acc k]
            (if-let [content (load-document k)]
              (assoc acc k content)
              acc))
          {}
          (:documents task)))

;; =============================================================================
;; Task Execution
;; =============================================================================

(defn- setup-task-sheet! [ctx task documents]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command
                                            :name (str "Task: " (:name task))))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)
        ;; Per-input schemas allow tasks to declare image-typed keys
        ;; (e.g. [:string {:field-type :image}]) so dscloj routes them as
        ;; multimodal content blocks to the LLM call.
        input-schemas (or (:input-schemas task) {})
        ;; Per-write schemas let tasks declare non-string output keys
        ;; (numbers, vectors, maps). Falls back to :string for any write
        ;; key not listed — backward compatible with single-string-output
        ;; tasks like image_analysis.
        output-schemas (or (:output-schemas task) {})]
    (doseq [[doc-key _] documents]
      (h/run-and-apply! ctx (h/make-declare-key-command
                              sheet-id doc-key
                              (or (get input-schemas doc-key) :string))))
    (doseq [write-key (:writes task)]
      (h/run-and-apply! ctx (h/make-declare-key-command
                              sheet-id write-key
                              (or (get output-schemas write-key) :string))))
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx (h/make-create-node-command
                                             sheet-id :repl-researcher :parent-id seq-id))
          node-id (-> node-result :command-result/events first :node-id)
          ;; Resolve effective main + sub model: per-task override wins,
          ;; else fall back to config default. :sub-model is optional —
          ;; when nil, all Phase-2 :llm calls inherit the main :model.
          effective-model (or (:model task) (:model config))
          effective-sub-model (or (:sub-model task) (:sub-model config))
          rlm-config (cond-> {:debug? true}
                       (:available-code-nodes task)
                       (assoc :available-code-nodes (:available-code-nodes task))
                       effective-sub-model
                       (assoc :sub-model effective-sub-model)
                       ;; Task-level :recursive? override — when set, the
                       ;; researcher emits a tree, gets the output back, and
                       ;; can emit follow-up trees / call (final! ...) to
                       ;; terminate. Default (nil/false) is terminal mode.
                       (:recursive? task)
                       (assoc :recursive? true))]
      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                              sheet-id node-id
                              (:instruction task)
                              (vec (keys documents))
                              (:writes task)
                              []
                              :model effective-model
                              :max-iterations 5
                              :rlm rlm-config))
      {:sheet-id sheet-id :node-id node-id})))

(defn- execute-task! [ctx sheet-id documents timeout-ms]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)
        start-time (System/currentTimeMillis)
        _ (cp/process-command
            (assoc ctx :command
                   {:command/id (random-uuid)
                    :command/timestamp (time/now)
                    :command/name :sheet/tick-tree
                    :sheet-id sheet-id
                    :tick-id tick-id
                    :inputs documents
                    :options {:timeout-ms timeout-ms}}))
        result (deref p timeout-ms {:status :timeout :error "Execution timed out"})
        duration-ms (- (System/currentTimeMillis) start-time)]
    (assoc result :duration-ms duration-ms)))

;; =============================================================================
;; Result Management
;; =============================================================================

(defn- ensure-results-dir! []
  (let [dir (io/file (:results-dir config))]
    (when-not (.exists dir)
      (.mkdirs dir))
    (.getPath dir)))

(defn- timestamp-now []
  (.format (java.time.ZonedDateTime/now)
           (java.time.format.DateTimeFormatter/ISO_INSTANT)))

(defn- task-slug [task]
  (or (:slug task)
      (-> (:name task)
          str/lower-case
          (str/replace #"[^a-z0-9]+" "-")
          (str/replace #"^-+|-+$" ""))))

(defn- filename-stem [task]
  (str (task-slug task) "_"
       (.format (java.time.LocalDateTime/now)
                (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HHmmss"))))

(defn- save-result! [record stem]
  (let [dir (ensure-results-dir!)
        path (str dir "/" stem ".edn")]
    (spit path (pr-str record))
    (println "  Saved EDN:" path)
    path))

(defn- read-iterations
  "Query event-store for the :rlm/researcher-iterations event(s) emitted by
   the researcher and extract the :iterations vector (Phase 1 REPL history).

   The todo_processor emits :rlm/researcher-iterations whenever the
   researcher executed at least one iteration — independent of whether a
   tree was ultimately emitted. This is the uniform capture surface for
   iteration history, working for both tree-emitting and direct-execution
   runs."
  [ctx start-instant]
  (let [events (into [] (es/read (:event-store ctx)
                                 {:tenant-id (:tenant-id ctx)
                                  :types #{:rlm/researcher-iterations}}))]
    (->> events
         (filter (fn [ev]
                   (let [ts (:event/timestamp ev)]
                     (or (nil? start-instant)
                         (.isAfter ^java.time.OffsetDateTime ts start-instant)
                         (.isEqual ^java.time.OffsetDateTime ts start-instant)))))
         (sort-by :event/timestamp)
         (mapcat :iterations)
         vec)))

(defn- read-node-trace
  "Query event-store for :sheet/node-execution-completed events emitted on or
   after `start-instant`. Returns a vector of selected fields per event, sorted
   by event timestamp.

   Captures BOTH the parent sheet's node completions AND any ephemeral child
   sheet's completions (Phase 2 RLM tree execution). The :types filter is used
   without a :tags filter because child sheets have their own sheet-id that the
   runner doesn't know upfront.

   Materializes the reified `es/read` result via `into []` — the in-memory
   store returns a seqable wrapper, not a vector. Filtering by start-instant
   prevents contamination from earlier runs in the same start!/stop! lifecycle."
  [ctx start-instant]
  (let [events (into [] (es/read (:event-store ctx)
                                 {:tenant-id (:tenant-id ctx)
                                  :types #{:sheet/node-execution-completed}}))]
    (->> events
         (filter (fn [ev]
                   ;; Compare java.time.OffsetDateTime instances
                   (let [ts (:event/timestamp ev)]
                     (or (nil? start-instant)
                         (.isAfter ^java.time.OffsetDateTime ts start-instant)
                         (.isEqual ^java.time.OffsetDateTime ts start-instant)))))
         (sort-by :event/timestamp)
         (mapv (fn [ev]
                 (cond-> {:node-id (:node-id ev)
                          :sheet-id (:sheet-id ev)
                          :tick-id (:tick-id ev)
                          :status (:status ev)
                          :timestamp (:event/timestamp ev)}
                   (:inputs ev) (assoc :inputs (:inputs ev))
                   (:writes ev) (assoc :writes (:writes ev))
                   (:usage ev) (assoc :usage (:usage ev))
                   (:duration-ms ev) (assoc :duration-ms (:duration-ms ev))))))))

;; =============================================================================
;; Trace publisher
;; =============================================================================

(defn- start-trace-publisher!
  "Attach a mulog simple-file publisher writing to <results-dir>/<stem>.trace.edn.
   Returns the stop function (call it to detach and flush)."
  [stem]
  (let [dir (ensure-results-dir!)
        path (str dir "/" stem ".trace.edn")]
    (println "  Attaching trace publisher:" path)
    (u/start-publisher! {:type :simple-file
                         :filename path})))

;; =============================================================================
;; Public API
;; =============================================================================

(defn start!
  "Initialize the comparison benchmark system."
  []
  (when @system-state
    (stop-context @system-state))
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set" {})))
        base-config {:provider :openrouter
                     :model (:model config)
                     :config {:api-base "https://openrouter.ai/api/v1"
                              :api-key api-key}}]
    (litellm-router/register! :openrouter base-config)
    (litellm-router/register! (keyword (str "openrouter/" (:model config)))
                              (assoc base-config :model (:model config)))
    ;; Pre-register the default sub-model (if configured) so the Phase-2
    ;; leaf executor's get-provider-with-model can resolve it without
    ;; on-demand registration overhead. Per-task overrides not in config
    ;; get registered on-demand at first use.
    (when-let [sm (:sub-model config)]
      (when (string? sm)
        (litellm-router/register! (keyword (str "openrouter/" sm))
                                  (assoc base-config :model sm)))))
  (reset! system-state (create-context))
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "  predict-rlm Comparison Runner started")
  (println "\n" (apply str (repeat 60 "=")) "\n")
  :started)

(defn stop!
  "Stop the comparison benchmark system."
  []
  (when @system-state
    (stop-context @system-state)
    (reset! system-state nil))
  (release-task-lock!)
  (println "predict-rlm comparison runner stopped.")
  :stopped)

(defn run!
  "Run a single task against the comparison runner.

   The task map must contain the same fields as the existing runner accepts
   (see development/bench/runner.clj), plus:
     :available-code-nodes (optional string) — passed through to the
       :rlm config on the researcher node so the model sees the catalog
       of pre-built code-node functions for the task.

   Returns a result record map and writes:
     <results-dir>/<slug>_<timestamp>.edn         — main result
     <results-dir>/<slug>_<timestamp>.trace.edn   — per-run mulog trace

   Refuses to start if another task is already in flight (single-task-lock)."
  [task]
  (when-not (try-acquire-task-lock! (:name task))
    (throw (ex-info "Task in flight — refusing concurrent run"
                    {:status :failure
                     :error "task in flight"
                     :current-holder (task-lock-state)})))
  (let [ctx @system-state
        _ (when-not ctx
            (release-task-lock!)
            (throw (ex-info "System not started — call (runner/start!) first" {})))
        slug (task-slug task)
        stem (filename-stem task)
        stop-publisher (start-trace-publisher! stem)]
    (try
      (println "\n" (apply str (repeat 70 "=")) "\n")
      (println "  TASK:" (:name task))
      (println "  Pattern:" (:pattern task))
      (println "\n" (apply str (repeat 70 "=")) "\n")
      (println "Loading documents...")
      (let [;; A task can ship its own :input-loader (0-arg fn returning
            ;; {key value}) to provide non-text inputs (image data URIs,
            ;; pre-rendered PDF pages, etc.). Otherwise default to text
            ;; files under documents-dir per the existing 5-benchmark
            ;; runner's convention.
            documents (if-let [f (:input-loader task)]
                        (f)
                        (load-task-documents task))
            _ (doseq [[k v] documents]
                (let [shown (if (string? v)
                              (str (count v) " chars")
                              (str (type v)))]
                  (println (str "  " (name k) ": " shown))))
            _ (println "\nSetting up task sheet...")
            run-start-instant (time/now)
            {:keys [sheet-id]} (setup-task-sheet! ctx task documents)
            _ (println "Executing ORC RLM (comparison mode)...\n")
            start-time (System/currentTimeMillis)
            result (execute-task! ctx sheet-id documents (:timeout-ms config))
            duration-ms (- (System/currentTimeMillis) start-time)
            usage (or (:usage result) {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})
            node-trace (read-node-trace ctx run-start-instant)
            iterations (read-iterations ctx run-start-instant)
            record (cond-> {:task slug
                            :task-name (:name task)
                            :pattern (:pattern task)
                            :timestamp (timestamp-now)
                            :documents (into {} (map (fn [[k v]] [k {:chars (count v)}]) documents))
                            :total-chars (reduce + (map (fn [[_ v]] (count v)) documents))
                            :duration-ms duration-ms
                            :status (:status result)
                            :usage usage
                            :outputs (:outputs result)
                            :evaluation-criteria (:evaluation-criteria task)
                            :iterations iterations
                            :node-trace node-trace}
                     (:error result) (assoc :error (:error result))
                     (:phase2-error result) (assoc :phase2-error (:phase2-error result))
                     (:generated-tree-raw result) (assoc :generated-tree-raw (:generated-tree-raw result)))]
        (println "\n" (apply str (repeat 70 "-")))
        (println "RESULT SUMMARY:")
        (println "  Status:" (:status result))
        (println "  Duration:" (format "%.1f" (/ duration-ms 1000.0)) "seconds")
        (println "  Tokens:" (:total-tokens usage))
        (println "  Node-trace events captured:" (count node-trace))
        (println "  Phase-1 iterations captured:" (count (:iterations result)))
        (println)
        (save-result! record stem)
        (println (apply str (repeat 70 "-")))
        record)
      (finally
        (when stop-publisher
          (try (stop-publisher) (catch Exception _ nil)))
        (release-task-lock!)))))

(defn run-all!
  "Run a list of task maps sequentially."
  [tasks]
  (mapv (fn [task]
          (try (run! task)
               (catch Exception e
                 (println "ERROR running" (:name task) ":" (.getMessage e))
                 {:task-name (:name task)
                  :status :error
                  :error (.getMessage e)})))
        tasks))

(defn generate-summary!
  "Generate a markdown summary table from saved EDN results in the given directory.

   Reads `.edn` files only (not the larger `.trace.edn` mulog event dumps),
   parses with a permissive tagged-literal reader so unknown tags
   (mulog/flake, etc.) don't break the read."
  [results-dir]
  (let [;; Permissive reader: accept any unknown tag and stash it as a
        ;; tagged-literal so reading doesn't blow up on mulog/flake etc.
        reader-opts {:default (fn [tag value] (tagged-literal tag value))}
        dir (io/file results-dir)
        files (when (.exists dir)
                (->> (.listFiles dir)
                     (filter #(.endsWith (.getName %) ".edn"))
                     ;; Skip the .trace.edn files — they're mulog dumps,
                     ;; not result records, and would dominate the summary.
                     (remove #(.endsWith (.getName %) ".trace.edn"))))
        results (map #(edn/read-string reader-opts (slurp %)) files)
        by-task (group-by :task results)
        latest (into {} (map (fn [[k vs]] [k (last (sort-by :timestamp vs))]) by-task))]
    (println "\n# predict-rlm Comparison Results\n")
    (println (str "**Generated:** " (timestamp-now) "\n"))
    (println "## Summary Table\n")
    (println "| Task | Pattern | Doc Size | Duration | Tokens | Status |")
    (println "|------|---------|----------|----------|--------|--------|")
    (doseq [[_ result] (sort-by first latest)]
      (println (format "| %s | %s | %,d chars | %.1fs | %,d | %s |"
                      (:task-name result)
                      (:pattern result)
                      (or (:total-chars result) 0)
                      (/ (:duration-ms result) 1000.0)
                      (get-in result [:usage :total-tokens] 0)
                      (name (:status result)))))
    (let [md-path (str results-dir "/SUMMARY.md")]
      (println (str "\nSummary saved to: " md-path))
      md-path)))
