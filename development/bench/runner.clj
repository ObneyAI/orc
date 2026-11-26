(ns runner
  "Shared infrastructure for running ORC RLM generalization benchmarks.

   This is a *generic* runner — it takes a task map directly and executes it.
   Per-task definitions live in their own files (e.g. risk-analysis.clj).

   Public API:
     (runner/start!)            — bring up the grain system
     (runner/run! task-map)     — execute one task, save result EDN, return result record
     (runner/run-all! task-maps) — sequentially run a list of task maps
     (runner/generate-summary! results-dir) — produce a markdown summary table
     (runner/stop!)             — tear down the system

   Run from REPL:
     (require '[runner])
     (require '[risk-analysis :as t])
     (runner/start!)
     (runner/run! t/task)
     (runner/stop!)
  "
  (:require [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors :as ont-tp]
            [model-registry :as mr]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.orc.colbert.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.event-store-postgres-v3.interface]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [litellm.router :as litellm-router]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [ai.obney.orc.orc-service.core.logic :as service-logic]
            [ai.obney.orc.ontology.core.logic :as ontology-logic]
            [ai.obney.orc.ontology.core.queries :as ontology-queries]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def config
  {:model "google/gemini-3-flash-preview"
   :reranker-model nil
   :timeout-ms 600000
   :documents-dir "development/bench/documents"
   :results-dir "development/bench/generalization-results"})


(def declared-models
  "Every OpenRouter model this harness has decided it talks to. Each gets its
   OWN litellm registration; nothing rides the generic entry by accident.

   - the runner's RLM model (`(:model config)`), used by repl-researcher nodes
   - the reranker's RR-2 default (`reranker/default-model`) — the classify →
     rerank path resolves this when the caller supplies no override, which is
     every bench caller. CH-1: it was NEVER registered here, so no reader of
     this file could tell which models the harness would use."
  [(:model config)
   (or (:reranker-model config) ontology-logic/default-model)
   ontology-logic/verifier-model])

(defn required-models
  "The models this harness WILL use, read from the engines' own defaults at
   call time — not from `declared-models`. Re-reads the vars so an engine-side
   default change is picked up rather than cached."
  []
  (distinct [(:model config)
             (ontology-logic/resolve-model {:ontology-reranker-model
                                      (:reranker-model config)}
                                     nil)
             ontology-logic/verifier-model]))

(defn register-models!
  "Register every declared model with litellm, then ASSERT that every model
   the harness will actually use is explicitly registered.

   Call before building any context. Throws (naming the offending model and
   every registration that exists) rather than letting an undeclared model
   ride the generic `:openrouter` entry — see `model-registry`.

   `api-key` defaults to $OPENROUTER_API_KEY; tests pass a dummy, since the
   precondition itself makes no network call."
  ([] (register-models! (or (System/getenv "OPENROUTER_API_KEY")
                            (throw (ex-info "OPENROUTER_API_KEY not set" {})))))
  ([api-key]
   (let [base {:provider :openrouter
               :config {:api-base "https://openrouter.ai/api/v1"
                        :api-key api-key}}]
     (litellm-router/register! :openrouter (assoc base :model (:model config)))
     (doseq [m (distinct declared-models)]
       (litellm-router/register! (keyword (str "openrouter/" m))
                                 (assoc base :model m))))
   (mr/assert-models-registered! "development/bench/runner" (required-models))))

;; =============================================================================
;; System State
;; =============================================================================

(defonce ^:private system-state (atom nil))

(defn current-context
  "Return the running benchmark context, or nil before startup."
  []
  @system-state)

(def bench-cache-map-size
  "The bench runner's LMDB cache ceiling: 16 GB of address space."
  (* 16 1024 1024 1024))

(defn create-context
  "The convergence probe uses this entry point, and the startup-ordering test redefines it.

   RS7-PS: accepts an optional options map so a caller can point the harness
   at a durable store instead of the default in-memory one:
     :event-store-conn - the `:conn` map passed to `es/start` (default
                          `{:type :in-memory}`; for Postgres pass
                          `{:type :postgres :server-name ... :port-number ...
                          :username ... :password ... :database-name ...}` —
                          see `ai.obney.grain.event-store-postgres-v3.interface.datasource/make-datasource`
                          for the exact keys it reads)
     :tenant-id         - the tenant to use (default a fresh random UUID)
     :cache-dir         - the LMDB cache directory (default a fresh directory
                          under `development/bench/.runner-cache/`, NOT /tmp —
                          macOS's cleaner reaps unaccessed /tmp files after a
                          few days, which gutted a worktree before; see
                          `feedback_never_keep_worktrees_in_tmp`)
   Every existing zero-arg caller keeps working unchanged."
  ([] (create-context {}))
  ([{:keys [event-store-conn tenant-id cache-dir]
     :or {event-store-conn {:type :in-memory}}}]
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn event-store-conn :event-pubsub ps :logger nil})
        cache-dir (or cache-dir (str "development/bench/.runner-cache/" (random-uuid)))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "bench"
                                               :map-size bench-cache-map-size}))
        tenant-id (or tenant-id (random-uuid))
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :llm-provider :openrouter
                  :ontology-reranker-model (:reranker-model config)
                  :injection-capture-rendered-block? true
                  ::cache-dir cache-dir}
        skip-procs #{:ontology/on-description-updated-maybe-reindex}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (if (contains? skip-procs proc-name)
                        acc
                        (assoc acc proc-name
                               (tp/start {:event-pubsub ps
                                          :topics topics
                                          :handler-fn handler-fn
                                          :context base-ctx}))))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :event-pubsub ps :processors processors))))

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
  "Create a sheet for the task with appropriate inputs.

   Sheet name is suffixed with a fresh UUID so back-to-back runs of the
   same task in one process don't collide on the :sheet/create-sheet
   uniqueness constraint (which silently turns a re-run into a nil
   sheet-id and a 600s tick-tree deref-timeout)."
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command
                                            :name (str "Task: " (:name task)
                                                       " — " (random-uuid))))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]

    (doseq [[doc-key _] documents]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id doc-key :string)))

    (doseq [write-key (:writes task)]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id write-key :string)))

    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx (h/make-create-node-command
                                             sheet-id :repl-researcher :parent-id seq-id))
          node-id (-> node-result :command-result/events first :node-id)]

      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                              sheet-id node-id
                              (:instruction task)
                              (vec (keys documents))
                              (:writes task)
                              []
                              :model (:model config)
                              :max-iterations 5
                              :rlm (merge {:debug? true} (:rlm task))))
      {:sheet-id sheet-id :node-id node-id})))

(defn- execute-task! [ctx sheet-id documents timeout-ms]
  (let [tick-id (random-uuid)
        p (service-logic/register-completion! tick-id)
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
;; Node-trace aggregation helper
;; =============================================================================

(defn- summarize-trace-tokens
  "Sum :usage :total-tokens across a node-trace vector. Returns a map with
   the same shape as :usage so callers can compare apples to apples against
   the executor's aggregated :usage field."
  [node-trace]
  (reduce
    (fn [acc ev]
      (let [u (:usage ev)]
        (if (and u (pos? (:total-tokens u 0)))
          (-> acc
              (update :prompt-tokens (fnil + 0) (or (:prompt-tokens u) 0))
              (update :completion-tokens (fnil + 0) (or (:completion-tokens u) 0))
              (update :total-tokens (fnil + 0) (or (:total-tokens u) 0)))
          acc)))
    {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}
    node-trace))

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

(defn- save-result! [task result]
  (let [dir (ensure-results-dir!)
        slug (task-slug task)
        filename (str slug "_"
                     (.format (java.time.LocalDateTime/now)
                              (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HHmmss"))
                     ".edn")
        path (str dir "/" filename)]
    (spit path (pr-str result))
    (println "  Saved:" path)
    path))

;; =============================================================================
;; Public API
;; =============================================================================

(defn drive-projectors!
  "PUBLIC (RS-7): synchronously drive BOTH the C-2d-1 tree-class projector
   AND the R05a behavioral-subtree projector over every
   :ontology/tree-description-updated event in the store. Mirrors the
   c2e-behavioral-live-verify orchestrator pattern. `rs7_traffic_sweep.clj`'s
   `restore!` calls this after replaying a snapshot into a fresh context, so
   the concept graph reflects the replayed events synchronously rather than
   racing the live pubsub-subscribed processors."
  [ctx]
  (doseq [event (into [] (es/read (:event-store ctx)
                                  {:tenant-id (:tenant-id ctx)
                                   :types #{:ontology/tree-description-updated}}))]
    (let [context (assoc ctx :event event)]
      (ontology-logic/on-tree-description-updated-project-concept context)
      (ontology-logic/on-behavioral-subtree-description-updated-project-concept context))))

(defn- emit-synthetic-padding!
  "Emit N synthetic placeholder node-instance descriptions so the
   ColBERT FAISS clustering has enough documents to build. R05e learned
   this — without padding the index build hangs or fails."
  [ctx n]
  (dotimes [i n]
    (let [target-id [(random-uuid) (random-uuid)]]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-node-instance-description
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-id target-id
                :body {:capabilities ["generic placeholder"]
                       :strengths [{:trait "synthetic padding entry"
                                    :good-when "never used in production retrieval"
                                    :recommended-pattern "[:noop {}]"
                                    :confidence 0.1
                                    :evidence-count 1
                                    :first-observed-at "2026-05-28T00:00:00Z"
                                    :last-reinforced-at "2026-05-28T00:00:00Z"}]
                       :weaknesses []
                       :representative-uses ["padding"]
                       :avoid-when ["always"]
                       :summary (str "Synthetic padding entry #" i
                                     " — irrelevant filler to satisfy FAISS clustering floor.")
                       :version 1
                       :consolidated-from-event-count 0}})))))

(defn- seed-corpus-and-build-index!
  "Seed the description corpus (R05a's 22 + 11 + 12 = 45 seeds + 80
   synthetic padding entries for FAISS), drive both concept-graph
   projectors, and rebuild the ColBERT index so the wedge's
   classify-task + classify-behaviors find candidates above threshold."
  [ctx]
  (println "Emitting synthetic padding (80 entries for FAISS clustering floor)...")
  (emit-synthetic-padding! ctx 80)
  (println "Seeding description corpus (45 hand-authored seeds)...")
  (ontology/seed-baseline-corpus! ctx)
  (Thread/sleep 1000)
  (println "Driving concept-graph projectors...")
  (drive-projectors! ctx)
  (println "Building ColBERT description index (one-time, expect 2-4 min)...")
  (ont-tp/maybe-rebuild! ctx)
  (Thread/sleep 1000)
  (println "Index state:" (pr-str (ontology/get-reindex-state ctx))))

(defn- tenant-has-events?
  "RS7-PS: decide 'already seeded' from the STORE'S OWN events for this
   tenant — never a flag file. `:limit 1` keeps this cheap (a single-row
   read) even against a Postgres store with a large history."
  [ctx]
  (boolean (seq (into [] (es/read (:event-store ctx)
                                  {:tenant-id (:tenant-id ctx) :limit 1})))))

(defn start!
  "Initialize the benchmark system. Also seeds the description corpus and
   builds the ColBERT index so tasks with `:rlm {:auto-classify? true}`
   can exercise the R05 classifier path (R-Inject).

   RS7-PS: accepts an optional options map, forwarded verbatim to
   `create-context` (`:event-store-conn` / `:tenant-id` / `:cache-dir`).
   When the connected store ALREADY holds events for the given tenant (the
   normal case on resuming a killed run against a persistent Postgres
   store), the corpus and padding documents are NOT re-seeded: instead the
   existing events are projected (`drive-projectors!`, the same path
   `rs7-traffic-sweep/restore!` uses) and the ColBERT index is rebuilt once
   (`ont-tp/force-rebuild!`) so retrieval reflects the restored state. A
   fresh/empty store (the default in-memory case, or a brand-new Postgres
   database) still seeds normally. Every existing zero-arg caller keeps
   working unchanged.

   Launcher note (RS-7): for a persistent Postgres store, use ONE database
   per ARM (e.g. `rs7_pre_fix`), created before the run with
   `docker exec orc-rs7-postgres createdb -U orc <name>`. Pass 2 continues
   pass 1's database and tenant (the same results-dir), because it measures
   stability against the tree pass 1 grew; `rs7-traffic-sweep/run-pass!`
   refuses a pass 2 on any other tenant."
  ([] (start! {}))
  ([opts]
   (when @system-state
     (stop-context @system-state))
   (register-models!)
   (let [ctx (create-context opts)]
     (reset! system-state ctx)
     (if (tenant-has-events? ctx)
       (do
         (println "RS7-PS: existing events found for tenant" (:tenant-id ctx)
                   "— resuming without reseeding.")
         (drive-projectors! ctx)
         (println "Rebuilding ColBERT description index from restored state...")
         (ont-tp/force-rebuild! ctx)
         (println "Index state:" (pr-str (ontology/get-reindex-state ctx))))
       (seed-corpus-and-build-index! ctx)))
   (println "\n" (apply str (repeat 60 "=")) "\n")
   (println "  ORC RLM Benchmark Runner started (corpus seeded, index built)")
   (println "\n" (apply str (repeat 60 "=")) "\n")
   :started))

(defn start-reindex-processor!
  "RS-7: start the `:ontology/on-description-updated-maybe-reindex`
   processor on the ALREADY-RUNNING system (`create-context` skips it by
   default — see its `skip-procs` comment) for the `:processor` reindex
   policy arm, which wants the faithful async-reindex path rather than the
   harness's synchronous `{:every-k K}` trigger. Call once, after
   `start!`. Idempotent-ish: calling twice just re-subscribes a second
   processor instance under the same key (the first is orphaned, not
   stopped) — callers should call it exactly once per `start!`."
  []
  (let [ctx @system-state
        _ (when-not ctx (throw (ex-info "System not started — call (runner/start!) first" {})))
        {:keys [handler-fn topics]} (get @tp/processor-registry*
                                         :ontology/on-description-updated-maybe-reindex)
        proc (tp/start {:event-pubsub (:event-pubsub ctx)
                        :topics topics
                        :handler-fn handler-fn
                        :context ctx})]
    (swap! system-state update :processors assoc
           :ontology/on-description-updated-maybe-reindex proc)
    :started))

(defn active-index-id
  "RS-7: the :index-id of the most-recently-created 'ontology-descriptions'
   ColBERT index, or nil if none has been built yet. Mirrors
   ontology.interface's PRIVATE `latest-ontology-descriptions-index`
   (duplicated here because that fn is private and this is a dev-only
   reporting need — which index a classify call resolved against — not a
   production retrieval path)."
  [ctx]
  (->> (colbert/list-indexes ctx)
       (filter #(= "ontology-descriptions" (:index-name %)))
       (sort-by :created-at)
       last
       :index-id))

(defn stop!
  "Stop the benchmark system."
  []
  (when @system-state
    (stop-context @system-state)
    (reset! system-state nil))
  (println "Benchmark system stopped.")
  :stopped)

(defn run!
  "Run a single task given a task map.

   The task map must contain:
     :name                 — human-readable name
     :pattern              — short description of the pattern
     :documents            — list of document keys (each maps to a file in documents/)
     :instruction          — instruction string for the RLM
     :writes               — list of output keys the task produces
     :evaluation-criteria  — list of manual evaluation criteria strings (optional)
     :slug                 — explicit filename slug (optional; derived from :name if absent)

   Returns a result record map and saves an EDN to generalization-results/."
  [task]
  (let [ctx @system-state
        _ (when-not ctx (throw (ex-info "System not started — call (runner/start!) first" {})))
        slug (task-slug task)]

    (println "\n" (apply str (repeat 70 "=")) "\n")
    (println "  TASK:" (:name task))
    (println "  Pattern:" (:pattern task))
    (println "\n" (apply str (repeat 70 "=")) "\n")

    (println "Loading documents...")
    (let [documents (load-task-documents task)
          _ (doseq [[k v] documents]
              (println (str "  " (name k) ": " (count v) " chars")))

          _ (println "\nSetting up task sheet...")
          {:keys [sheet-id]} (setup-task-sheet! ctx task documents)

          _ (println "Executing ORC RLM...")
          _ (println "(Watch stdout for [DEBUG RLM] messages)\n")
          start-time (System/currentTimeMillis)
          result (execute-task! ctx sheet-id documents (:timeout-ms config))
          duration-ms (- (System/currentTimeMillis) start-time)

          usage (or (:usage result) {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})

          node-trace (or (:node-trace result) [])
          node-trace-usage-total (summarize-trace-tokens node-trace)

          r-inject-trace (let [records (->> (es/read (:event-store ctx)
                                                    {:types #{:intervention/injection-recorded}
                                                     :tags #{[:sheet sheet-id]}
                                                     :tenant-id (:tenant-id ctx)})
                                            (into [])
                                            (sort-by :recorded-at))]
                           (when-let [r (last records)]
                             {:rendered-at (:recorded-at r)
                              :prepend (:rendered-block r)
                              :prepend-chars (:rendered-chars r)
                              :prompt-content-hash (:prompt-content-hash r)
                              :arm (:arm r)
                              :candidates (:candidates r)
                              :injection-records records}))

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
                          :evaluation-criteria (:evaluation-criteria task)}
                   (:error result) (assoc :error (:error result))
                   (:phase2-error result) (assoc :phase2-error (:phase2-error result))
                   (:generated-tree-raw result) (assoc :generated-tree-raw (:generated-tree-raw result))
                   (or (contains? result :iteration-reasonings)
                       (contains? (:outputs result) :iteration-reasonings))
                   (assoc :iteration-reasonings (or (:iteration-reasonings result)
                                                    (:iteration-reasonings (:outputs result))))
                   r-inject-trace (assoc :r-inject-trace r-inject-trace)
                   (seq node-trace) (assoc :node-trace node-trace
                                           :node-trace-usage-total node-trace-usage-total)
                   (or (contains? result :iterations)
                       (contains? (:outputs result) :iterations))
                   (assoc :iterations (or (:iterations result)
                                          (:iterations (:outputs result)))))]

      (println "\n" (apply str (repeat 70 "-")))
      (println "RESULT SUMMARY:")
      (println "  Status:" (:status result))
      (println "  Duration:" (format "%.1f" (/ duration-ms 1000.0)) "seconds")
      (println "  Tokens:" (:total-tokens usage)
               (str "(prompt: " (:prompt-tokens usage)
                    ", completion: " (:completion-tokens usage) ")"))
      (when (:error result)
        (println "  ERROR:" (:error result)))
      (when (:phase2-error result)
        (println "  PHASE 2 ERROR:" (:phase2-error result)))

      (when-let [tree-raw (get-in result [:outputs :generated-tree-raw])]
        (println "\nGENERATED TREE (raw S-expr):")
        (pprint/pprint tree-raw))

      (println "\nOUTPUTS PREVIEW:")
      (doseq [[k v] (:outputs result)]
        (when (and (string? v) (not= k :generated-tree-raw))
          (println (str "  " (name k) ": "
                       (subs v 0 (min 150 (count v)))
                       (when (> (count v) 150) "...")))))

      (println "\nEVALUATION CRITERIA (manual check):")
      (doseq [criterion (:evaluation-criteria task)]
        (println (str "  [ ] " criterion)))

      (println)
      (save-result! task record)
      (println (apply str (repeat 70 "-")))

      record)))

(defn run-all!
  "Run a list of task maps sequentially."
  [tasks]
  (println "\n" (apply str (repeat 70 "=")) "\n")
  (println "  RUNNING" (count tasks) "TASKS")
  (println "\n" (apply str (repeat 70 "=")) "\n")

  (let [results (doall
                 (for [task tasks]
                   (try
                     (run! task)
                     (catch Exception e
                       (println "ERROR running" (:name task) ":" (.getMessage e))
                       {:task-name (:name task) :status :error :error (.getMessage e)}))))]

    (println "\n\n" (apply str (repeat 70 "=")) "\n")
    (println "  ALL TASKS COMPLETE")
    (println "\n" (apply str (repeat 70 "=")) "\n")

    results))

(defn generate-summary!
  "Generate a markdown summary table from saved EDN results in the given directory."
  [results-dir]
  (let [dir (io/file results-dir)
        files (when (.exists dir)
                (filter #(.endsWith (.getName %) ".edn") (.listFiles dir)))
        results (map #(edn/read-string (slurp %)) files)
        by-task (group-by :task results)
        latest (into {} (map (fn [[k vs]]
                              [k (last (sort-by :timestamp vs))])
                            by-task))]

    (println "\n# ORC RLM Benchmark Results\n")
    (println (str "**Generated:** " (timestamp-now) "\n"))

    (println "## Summary Table\n")
    (println "| Task | Pattern | Doc Size | Duration | Tokens | Status |")
    (println "|------|---------|----------|----------|--------|--------|")

    (doseq [[_ result] (sort-by first latest)]
      (println (format "| %s | %s | %,d chars | %.1fs | %,d | %s |"
                      (:task-name result)
                      (:pattern result)
                      (:total-chars result)
                      (/ (:duration-ms result) 1000.0)
                      (get-in result [:usage :total-tokens] 0)
                      (name (:status result)))))

    (let [md-path (str results-dir "/SUMMARY.md")]
      (println (str "\nSummary saved to: " md-path))
      md-path)))
