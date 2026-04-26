(ns run-orc
  "Benchmark runner for orc examples. Mirrors predict-rlm/bench/run_predict.py.

   Runs one task / one style N times against a chosen model pair, captures a
   `run.json` per run that's byte-compatible with the predict-rlm wrapper's
   schema, plus a per-run trace of node-execution events.

   Usage:

     cd /Users/justinobney/dev/orc
     set -a && source .env && set +a   ;; OPENROUTER_API_KEY
     clj -X:dev:bench run-orc/run \\
       :task :image_analysis :style :b :runs 1

   Args (all keyword/value):
     :task   one of :image_analysis :document_analysis :document_redaction
                    :invoice_processing :contract_comparison
     :style  :a (pipeline) or :b (agentic / repl-researcher)
     :runs   integer, default 1
     :model       main LM, default \"openrouter/openai/gpt-5\"
     :sub-lm-model sub LM, default \"openrouter/openai/gpt-5-mini\""
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [dev]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.workflow-driver.interface :as driver]
            ;; Force schema registration BEFORE dev/start! runs commands —
            ;; otherwise driver/run-driver-loop! gets "Failed to append
            ;; :driver/session-started" because the event store rejects
            ;; the unknown event type.
            [ai.obney.orc.workflow-driver.interface.schemas]
            [ai.obney.orc.doc-skills.core.pdf :as pdf]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time])
  (:import (java.security MessageDigest)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

;; ---------------------------------------------------------------------------
;; Per-task wiring
;; ---------------------------------------------------------------------------

(def repo-root
  (.getAbsolutePath (io/file (System/getProperty "user.dir"))))

(def predict-rlm-prompts
  "Verbatim DEFAULT_QUERY / CRITERIA from predict-rlm's run.py files. Used
   when :prompt-source is :predict-rlm (the default) so both stacks face
   the same question. Loaded eagerly — file lives next to this script."
  (delay (edn/read-string (slurp (io/file repo-root "bench" "predict_rlm_prompts.edn")))))

(defn- prompt-from-orc-defaults [run-ns var-name]
  (deref (requiring-resolve (symbol (str run-ns) var-name))))

(defn- task-extra-prompt
  "Resolve the extra-prompt argument (query / criteria) for a task based on
   the chosen :prompt-source. Returns nil if the task has no extra prompt."
  [task arg-key prompt-source run-ns orc-default-var]
  (case prompt-source
    :predict-rlm (get-in @predict-rlm-prompts [task arg-key])
    :orc         (some-> orc-default-var (->> (prompt-from-orc-defaults run-ns)))))

(def tasks
  "Each entry mirrors one TASKS row in predict-rlm/bench/run_predict.py."
  {:image_analysis
   {:dir          "image_analysis"
    :pipeline-ns  'ai.obney.orc.examples.image-analysis.pipeline
    :agentic-ns   'ai.obney.orc.examples.image-analysis.agentic
    :run-ns       'ai.obney.orc.examples.image-analysis.run
    :input-exts   #{".png" ".jpg" ".jpeg" ".webp"}
    :inputs-fn    (fn [paths run-ns prompt-source]
                    (cond-> {:image-paths paths}
                      :always (assoc :query
                                     (task-extra-prompt :image_analysis :query
                                                        prompt-source run-ns
                                                        "default-query"))))}

   :document_analysis
   {:dir          "document_analysis"
    :pipeline-ns  'ai.obney.orc.examples.document-analysis.pipeline
    :agentic-ns   'ai.obney.orc.examples.document-analysis.agentic
    :run-ns       'ai.obney.orc.examples.document-analysis.run
    :input-exts   #{".pdf"}
    :inputs-fn    (fn [paths run-ns prompt-source]
                    {:documents paths
                     :criteria (task-extra-prompt :document_analysis :criteria
                                                  prompt-source run-ns
                                                  "default-criteria")})}

   :document_redaction
   {:dir          "document_redaction"
    :pipeline-ns  'ai.obney.orc.examples.document-redaction.pipeline
    :agentic-ns   'ai.obney.orc.examples.document-redaction.agentic
    :run-ns       'ai.obney.orc.examples.document-redaction.run
    :input-exts   #{".pdf"}
    :inputs-fn    (fn [paths run-ns prompt-source]
                    {:documents paths
                     :criteria (task-extra-prompt :document_redaction :criteria
                                                  prompt-source run-ns
                                                  "default-criteria")})}

   :invoice_processing
   {:dir          "invoice_processing"
    :pipeline-ns  'ai.obney.orc.examples.invoice-processing.pipeline
    :agentic-ns   'ai.obney.orc.examples.invoice-processing.agentic
    :run-ns       'ai.obney.orc.examples.invoice-processing.run
    :input-exts   #{".pdf"}
    :inputs-fn    (fn [paths _ _]
                    {:invoices paths})}

   :contract_comparison
   {:dir          "contract_comparison"
    :pipeline-ns  'ai.obney.orc.examples.contract-comparison.pipeline
    :agentic-ns   'ai.obney.orc.examples.contract-comparison.agentic
    :run-ns       'ai.obney.orc.examples.contract-comparison.run
    :input-exts   #{".pdf"}
    :inputs-fn    (fn [paths _ _]
                    {:contracts paths})}})

(defn- sample-input-paths [task]
  (let [{:keys [dir input-exts]} (tasks task)
        sample-dir (io/file repo-root "examples" dir "sample" "input")]
    (when-not (.isDirectory sample-dir)
      (throw (ex-info (str "No sample/input dir for task " task) {:dir (.getPath sample-dir)})))
    (->> (file-seq sample-dir)
         (filter #(.isFile ^java.io.File %))
         (filter (fn [f]
                   (let [n (str/lower-case (.getName ^java.io.File f))]
                     (some #(.endsWith n %) input-exts))))
         (sort-by #(.getName ^java.io.File %))
         (mapv #(.getAbsolutePath ^java.io.File %)))))

(defn- task-ns
  "Resolve the namespace symbol holding the workflow for a given style."
  [task style]
  (let [{:keys [pipeline-ns agentic-ns dir]} (tasks task)]
    (case style
      :a       pipeline-ns
      :b       agentic-ns
      :legacy  (symbol (str "ai.obney.orc.examples." (str/replace dir "_" "-") ".legacy")))))

(defn- load-workflow [task style]
  (let [ns-sym (task-ns task style)
        _ (require ns-sym)
        workflow-var (ns-resolve ns-sym 'workflow)]
    (when-not workflow-var
      (throw (ex-info (str "No `workflow` var in " ns-sym
                           " (does this task have a " (name style) " implementation?)")
                      {:task task :style style :ns ns-sym})))
    @workflow-var))

;; ---------------------------------------------------------------------------
;; Model override
;; ---------------------------------------------------------------------------

(defn- assert-allowed-model!
  "Hard guard: refuse to run a workflow whose any node references gpt-4 or
   any other forbidden model. Single source of truth for 'never accidentally
   route to GPT-4' — catches both stale workflow data and any future default
   that might leak through."
  [workflow]
  (let [forbidden #{"gpt-4" "openai/gpt-4" "gpt-4o" "openai/gpt-4o"
                    "gpt-4o-mini" "openai/gpt-4o-mini" "gpt-4-turbo"
                    "openai/gpt-4-turbo" "gpt-3.5-turbo" "openai/gpt-3.5-turbo"}
        seen-models (atom #{})]
    (walk/postwalk
      (fn [x]
        (when (and (map? x) (string? (:model x)))
          (swap! seen-models conj (:model x)))
        (when (and (map? x) (string? (get-in x [:rlm :predict-model])))
          (swap! seen-models conj (get-in x [:rlm :predict-model])))
        x)
      workflow)
    (let [bad (set (filter #(some (fn [f] (clojure.string/includes? % f)) forbidden) @seen-models))]
      (when (seq bad)
        (throw (ex-info (str "Refusing to execute: workflow references forbidden model(s) "
                             (pr-str bad) ". Bench is configured to NEVER use GPT-4 / 4o / 3.5.")
                        {:type :bench/forbidden-model :models bad})))
      (when (empty? @seen-models)
        (throw (ex-info "Workflow has no model bindings — would route to provider default (potentially GPT-4o-mini via litellm-router)."
                        {:type :bench/no-model}))))))

(defn override-models
  "Walk the workflow data and replace :model on every node so the bench
   compares against the same model pair as predict-rlm. Decision (per plan):
     - repl-researcher nodes get :model = main, :rlm.predict-model = sub
     - all `(dsl/llm …)` nodes (`:node-type :leaf, :executor :ai`) get
       :model = sub (treat them as 'perception')

   Asserts that the resulting workflow has no nil-model nodes and no
   forbidden-model references — refuses to return otherwise."
  [workflow main-model sub-model]
  (let [overridden
        (walk/postwalk
          (fn [x]
            (cond
              (and (map? x) (= :repl-researcher (:node-type x)))
              (cond-> (assoc x :model main-model)
                (:rlm x) (assoc-in [:rlm :predict-model] sub-model))

              (and (map? x) (= :leaf (:node-type x)) (= :ai (:executor x)))
              (assoc x :model sub-model)

              :else x))
          workflow)]
    (assert-allowed-model! overridden)
    overridden))

;; ---------------------------------------------------------------------------
;; Cost calculation
;; ---------------------------------------------------------------------------

(def price-table
  (delay (edn/read-string (slurp (io/file repo-root "bench" "price_table.edn")))))

(defn- price-of [model]
  (or (get @price-table model)
      (do (println (format "WARN: no price for model %s — cost will be 0" model))
          {:input 0.0 :output 0.0})))

(defn- cost-of [model in-tokens out-tokens]
  (let [{:keys [input output]} (price-of model)]
    (+ (* (/ in-tokens 1e6) input)
       (* (/ out-tokens 1e6) output))))

;; ---------------------------------------------------------------------------
;; Event capture & aggregation
;; ---------------------------------------------------------------------------

(defn- read-tick-events [ctx sheet-id tick-id]
  (vec (es/read (:event-store ctx)
                {:tenant-id (:tenant-id ctx)
                 :tags #{[:sheet sheet-id] [:tick tick-id]}})))

(defn- aggregate-usage
  "Sum token usage from per-node events and the result-level :rlm map.
   Returns {:root {:input ... :output ... :calls ...}
            :sub  {:input ... :output ... :calls ...}}"
  [events result style]
  (let [node-events (filter #(= :sheet/node-execution-completed (:event/type %)) events)
        ;; For Style A: every node-event with :usage is an LLM call → sub bucket.
        ;; For Style B: the repl-researcher node has :rlm with :root-usage and
        ;; :subcall-usage; the per-node :usage may double-count the root.
        sum-tokens (fn [usages]
                     {:input  (reduce + 0 (map #(or (:prompt-tokens %) 0) usages))
                      :output (reduce + 0 (map #(or (:completion-tokens %) 0) usages))})]
    (case style
      :a
      (let [usages (keep :usage node-events)]
        {:root {:input 0 :output 0 :calls 0}
         :sub  (assoc (sum-tokens usages) :calls (count usages))})

      :b
      (let [rlm-evt (->> node-events
                         (filter #(get-in % [:rlm :enabled?]))
                         first)
            ru (some-> rlm-evt :rlm :root-usage)
            su (some-> rlm-evt :rlm :subcall-usage)
            pcc (or (get-in rlm-evt [:rlm :predict-call-count]) 0)]
        {:root (assoc (sum-tokens (when ru [ru])) :calls (if ru 1 0))
         :sub  (assoc (sum-tokens (when su [su])) :calls pcc)})

      :legacy
      ;; Legacy mode: single repl-researcher node, no :rlm telemetry.
      ;; All tokens are root (one model thinking iteratively); sub is
      ;; structurally always 0 (no predict / predict-all available).
      (let [usages (keep :usage node-events)]
        {:root (assoc (sum-tokens usages) :calls (count usages))
         :sub  {:input 0 :output 0 :calls 0}}))))

;; ---------------------------------------------------------------------------
;; Output artifact capture
;; ---------------------------------------------------------------------------

(defn- sha256 [^java.io.File f]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream f)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn- looks-like-path? [s]
  (and (string? s)
       (or (str/starts-with? s "/")
           (str/starts-with? s "./"))
       (.exists (io/file s))
       (.isFile (io/file s))))

(def ^:private max-string-chars 4000)

(defn- elide-huge [^String s]
  (if (> (count s) max-string-chars)
    (str (subs s 0 200)
         (format " …<elided %d chars>… " (- (count s) 400))
         (subs s (- (count s) 200)))
    s))

(defn- capture-artifacts+view
  "Single walk over result :outputs that simultaneously:
     - Copies any string that looks like a file path (and isn't an input
       echo) into outputs-dir, recording artifact metadata.
     - Builds a JSON-able structured view: captured paths become ::file
       markers, oversized strings (e.g. base64 data URIs) are elided.

   Returns {:artifacts [...] :structured ...}. Replaces an earlier
   two-pass implementation."
  [outputs-map outputs-dir input-paths]
  (.mkdirs outputs-dir)
  (let [excluded (set input-paths)
        acc (atom [])
        seen (atom #{})
        structured
        (walk/postwalk
          (fn [x]
            (cond
              (and (looks-like-path? x)
                   (not (@seen x))
                   (not (excluded x)))
              (let [src (io/file x)
                    target (io/file outputs-dir (.getName src))]
                (io/copy src target)
                (swap! seen conj x)
                (swap! acc conj
                       {:path (str (.getName outputs-dir) "/" (.getName target))
                        :original-path x
                        :size_bytes (.length target)
                        :sha256 (sha256 target)})
                ::file)

              (and (string? x) (@seen x)) ::file
              (string? x) (elide-huge x)
              :else x))
          outputs-map)]
    {:artifacts @acc :structured structured}))

;; ---------------------------------------------------------------------------
;; Inputs metadata
;; ---------------------------------------------------------------------------

(defn- input-meta [path]
  (let [f (io/file path)
        nm (.getName f)
        pdf? (str/ends-with? (str/lower-case nm) ".pdf")]
    {:path (.getAbsolutePath f)
     :name nm
     :size_bytes (.length f)
     :sha256 (sha256 f)
     :page_count (when pdf? (try (pdf/page-count (.getAbsolutePath f))
                                 (catch Throwable _ nil)))}))

;; ---------------------------------------------------------------------------
;; Dispatch (give us the tick-id back)
;; ---------------------------------------------------------------------------

(defn- dispatch-and-wait!
  "Mirror runtime/execute, but expose the tick-id so the bench can scope
   event-store reads to a single run."
  [ctx sheet-id inputs timeout-ms]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)
        cmd-result (cp/process-command
                     (assoc ctx :command
                            {:command/id (random-uuid)
                             :command/timestamp (time/now)
                             :command/name :sheet/tick-tree
                             :sheet-id sheet-id
                             :tick-id tick-id
                             :inputs (or inputs {})
                             :options {:timeout-ms timeout-ms
                                       :store-trace? true}}))]
    (if (:cognitect.anomalies/category cmd-result)
      {:tick-id tick-id
       :result {:status :failure
                :error (:cognitect.anomalies/message cmd-result)}}
      (let [r (deref p timeout-ms ::timeout)]
        {:tick-id tick-id
         :result (if (= r ::timeout)
                   {:status :timeout :error "Execution timed out"}
                   r)}))))

;; ---------------------------------------------------------------------------
;; Per-run orchestration
;; ---------------------------------------------------------------------------

(defn- git-sha []
  (try
    (let [{:keys [out exit]} (sh/sh "git" "-C" repo-root "rev-parse" "--short" "HEAD")]
      (when (zero? exit) (str/trim out)))
    (catch Throwable _ nil)))

(defn- timestamp-id []
  ;; ms-resolution to avoid collisions when multiple runs start in the
  ;; same second (parallel mode).
  (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss-SSS")))

(defn- run-once
  [{:keys [ctx task style main-model sub-model max-iterations sweep-dir run-idx
           timeout-ms prompt-source]}]
  (let [run-dir (io/file sweep-dir (format "%s__%02d" (timestamp-id) run-idx))
        _ (.mkdirs run-dir)
        cfg (tasks task)
        input-paths (sample-input-paths task)
        inputs ((:inputs-fn cfg) input-paths (:run-ns cfg) prompt-source)
        workflow (override-models (load-workflow task style) main-model sub-model)
        sheet-id (dsl/build-workflow! ctx workflow)
        inputs-meta (mapv input-meta input-paths)
        total-pages (let [ps (keep :page_count inputs-meta)]
                      (when (seq ps) (reduce + 0 ps)))
        _ (println (format "[%s style=%s run=%d] starting — %d input(s), %s page(s)"
                           (name task) (name style) run-idx (count input-paths)
                           (or total-pages "?")))
        start-ms (System/currentTimeMillis)
        {:keys [tick-id result]} (dispatch-and-wait! ctx sheet-id inputs timeout-ms)
        duration-ms (- (System/currentTimeMillis) start-ms)
        events (read-tick-events ctx sheet-id tick-id)
        usage (aggregate-usage events result style)
        root-cost (cost-of main-model (:input (:root usage)) (:output (:root usage)))
        sub-cost  (cost-of sub-model  (:input (:sub usage))  (:output (:sub usage)))
        {:keys [artifacts structured]}
        (capture-artifacts+view (or (:outputs result) {})
                                (io/file run-dir "outputs")
                                input-paths)
        trace-file (io/file run-dir "trace.edn")
        _ (spit trace-file (with-out-str (clojure.pprint/pprint events)))
        ;; Pull error text from result OR from any failed node-execution event
        node-errors (->> events
                         (filter #(= :sheet/node-execution-completed (:event/type %)))
                         (filter #(= :failure (:status %)))
                         (keep :error))
        run-error (cond
                    (= :timeout (:status result)) "Execution timed out"
                    (:error result) (:error result)
                    (and (= :failure (:status result)) (seq node-errors))
                    (str/join "\n---\n" node-errors)
                    (= :failure (:status result)) "Run reported :failure with no error message"
                    :else nil)
        record {:stack (str "orc-style-" (name style))
                :task (name task)
                :run_idx run-idx
                :run_id (.getName run-dir)
                :git_sha (git-sha)
                :prompt_source (name prompt-source)
                :models {:root main-model :sub sub-model}
                :max_iterations max-iterations
                :inputs inputs-meta
                :total_pages total-pages
                :duration_seconds (/ duration-ms 1000.0)
                :cost {:root_lm root-cost
                       :sub_lm sub-cost
                       :total (+ root-cost sub-cost)
                       :per_page (when total-pages (/ (+ root-cost sub-cost) total-pages))}
                :calls {:root (:calls (:root usage))
                        :sub  (:calls (:sub usage))}
                :tokens {:root {:input (:input (:root usage))
                                :output (:output (:root usage))}
                         :sub  {:input (:input (:sub usage))
                                :output (:output (:sub usage))}}
                :outputs (or artifacts [])
                :structured structured
                :trace_path "trace.edn"
                :error run-error
                :orc {:tick_id (str tick-id)
                      :sheet_id (str sheet-id)
                      :status (:status result)
                      :event_count (count events)}}]
    (spit (io/file run-dir "run.json") (json/generate-string record {:pretty true}))
    (let [secs (/ duration-ms 1000.0)
          status (if (:error record) "ERR" "ok")]
      (println (format "[%s style=%s run=%d] %s — %.1fs, $%.4f, root=%d sub=%d → %s"
                       (name task) (name style) run-idx status secs
                       (get-in record [:cost :total])
                       (get-in record [:calls :root])
                       (get-in record [:calls :sub])
                       (.getPath run-dir))))
    record))

;; ---------------------------------------------------------------------------
;; Driver-recovery (Cameron's workflow-driver agent)
;; ---------------------------------------------------------------------------

(def ^:private degradations-cache
  (delay (edn/read-string (slurp (io/file repo-root "bench" "degradations.edn")))))

(defn- apply-degradation
  "Walk the workflow data, find the named node, replace its :instruction
   and (optionally) drop keys from its :reads."
  [workflow {:keys [node-name weak-instruction drop-reads]}]
  (walk/postwalk
    (fn [x]
      (if (and (map? x) (= node-name (:name x)))
        (cond-> (assoc x :instruction weak-instruction)
          (seq drop-reads) (update :reads (fn [r] (vec (remove (set drop-reads) r)))))
        x))
    workflow))

(defn- run-driver-once
  "Driver-recovery bench cell:
     1. Build pristine Style A pipeline.
     2. Apply per-task degradation to a chosen node.
     3. Run driver/run-driver-loop! with eval-set + judges + objective.
     4. Capture turns / final-eval / version-number / total cost into run.json.

   Stack tag: orc-driver-recovery."
  [{:keys [ctx task main-model sub-model max-iterations sweep-dir run-idx
           timeout-ms]}]
  ;; Belt-and-suspenders: make sure :driver/* event schemas are loaded
  ;; before any append. clj -X's entry-point resolution sometimes fires
  ;; before transitive requires complete, leading to "Failed to append
  ;; :driver/session-started" on the first try.
  (require 'ai.obney.orc.workflow-driver.interface.schemas)
  (let [run-dir (io/file sweep-dir (format "%s__%02d" (timestamp-id) run-idx))
        _ (.mkdirs run-dir)
        deg (get @degradations-cache task)
        _ (when-not deg
            (throw (ex-info (str "No degradation defined for " task
                                 " in bench/degradations.edn")
                            {:task task})))
        ;; Style A pipeline, model-overridden
        pristine (override-models (load-workflow task :a) main-model sub-model)
        degraded (apply-degradation pristine deg)
        sheet-id (dsl/build-workflow! ctx degraded)
        _ (println (format "[%s style=driver run=%d] starting — degraded node \"%s\""
                           (name task) run-idx (:node-name deg)))
        start-ms (System/currentTimeMillis)
        result (try
                 (driver/run-driver-loop! ctx
                   {:sheet-id sheet-id
                    :objective (:objective deg)
                    :eval-set (:eval-set deg)
                    :judges (:judges deg)
                    :max-turns 3
                    :min-pass-rate 1.0
                    :min-judge-score 0.7
                    :model main-model
                    :tick-timeout-ms timeout-ms
                    :description (str "bench/driver-recovery " (name task) " run " run-idx)})
                 (catch Throwable t
                   (println "DRIVER FAULT:" (.getMessage t))
                   (clojure.pprint/pprint (ex-data t))
                   {:status :error
                    :error (str (.getMessage t)
                                (when-let [d (ex-data t)] (str " | " (pr-str d))))}))
        duration-ms (- (System/currentTimeMillis) start-ms)
        usage (or (:usage result) {})
        ;; Driver usage is single-bucket (no root/sub split — every turn's
        ;; LLM call goes to the same model). Treat as :root for schema parity.
        root-in (or (:prompt-tokens usage) 0)
        root-out (or (:completion-tokens usage) 0)
        root-cost (cost-of main-model root-in root-out)
        turns (vec (:turns result))
        n-turns (count turns)
        ;; Trace: read all events tagged by the driver session id
        session-id (:session-id result)
        events (when session-id
                 (vec (es/read (:event-store ctx)
                               {:tenant-id (:tenant-id ctx)
                                :tags #{[:driver/session session-id]}})))
        trace-file (io/file run-dir "trace.edn")
        _ (spit trace-file (with-out-str (clojure.pprint/pprint events)))
        ;; Summarize the final eval for the structured field
        structured {:driver-status (:status result)
                    :version-number (:version-number result)
                    :reason (:reason result)
                    :n-turns n-turns
                    :final-eval (select-keys (:final-eval result)
                                             [:pass-rate :avg-judge-score
                                              :pass-count :fail-count :total])
                    :turn-decisions (mapv :decision turns)}
        record {:stack "orc-driver-recovery"
                :task (name task)
                :run_idx run-idx
                :run_id (.getName run-dir)
                :git_sha (git-sha)
                :models {:root main-model :sub sub-model}
                :max_iterations max-iterations
                :degradation {:node (:node-name deg)
                              :weak-instruction (:weak-instruction deg)
                              :drop-reads (:drop-reads deg)}
                :duration_seconds (/ duration-ms 1000.0)
                :cost {:root_lm root-cost :sub_lm 0.0 :total root-cost
                       :per_turn (when (pos? n-turns) (/ root-cost n-turns))}
                :calls {:root n-turns :sub 0}
                :tokens {:root {:input root-in :output root-out}
                         :sub  {:input 0 :output 0}}
                :outputs []
                :structured structured
                :trace_path "trace.edn"
                :error (when (= :error (:status result)) (:error result))
                :orc {:session_id (some-> session-id str)
                      :sheet_id (str sheet-id)
                      :driver_status (:status result)
                      :event_count (count events)}}]
    (spit (io/file run-dir "run.json") (json/generate-string record {:pretty true}))
    (let [secs (/ duration-ms 1000.0)
          status (case (:status result)
                   :published "PUBLISHED"
                   :surrendered "SURRENDERED"
                   :error "ERR"
                   (str (:status result)))]
      (println (format "[%s style=driver run=%d] %s — %.1fs, $%.4f, turns=%d → %s"
                       (name task) run-idx status secs root-cost n-turns
                       (.getPath run-dir))))
    record))

;; ---------------------------------------------------------------------------
;; Entry point (clj -X)
;; ---------------------------------------------------------------------------

(defn run
  "Entry point for `clj -X:dev:bench run-orc/run :task :image_analysis ...`.

   :prompt-source — :predict-rlm (default) feeds the verbatim DEFAULT_QUERY
                    / CRITERIA from predict-rlm/examples/<task>/run.py so
                    both stacks face the same question. :orc reverts to
                    orc's own (often abbreviated) defaults from the example
                    run.clj — useful only when measuring orc-on-orc."
  [{:keys [task style runs model sub-lm-model max-iterations timeout-ms prompt-source]
    :or   {style :b
           runs 1
           model "openai/gpt-5"
           sub-lm-model "openai/gpt-5-mini"
           max-iterations 30
           timeout-ms 300000
           prompt-source :predict-rlm}}]
  (when-not (contains? tasks task)
    (throw (ex-info (str "Unknown task " task " — must be one of " (keys tasks))
                    {:task task})))
  (when-not (#{:a :b :legacy :driver} style)
    (throw (ex-info (str "Style must be :a, :b, :legacy, or :driver, got " style) {:style style})))
  (when-not (#{:predict-rlm :orc} prompt-source)
    (throw (ex-info (str ":prompt-source must be :predict-rlm or :orc, got " prompt-source)
                    {:prompt-source prompt-source})))
  (println (format "Bench: task=%s style=%s runs=%d model=%s sub=%s prompt-source=%s"
                   (name task) (name style) runs model sub-lm-model (name prompt-source)))
  (dev/start!)
  (try
    (let [sweep-dir (io/file repo-root "bench" "runs" (name task))
          _ (.mkdirs sweep-dir)
          ctx (dev/ctx)
          run-fn (if (= :driver style) run-driver-once run-once)
          records (vec (for [i (range 1 (inc runs))]
                         (run-fn {:ctx ctx :task task :style style
                                  :main-model model :sub-model sub-lm-model
                                  :max-iterations max-iterations
                                  :sweep-dir sweep-dir
                                  :run-idx i
                                  :timeout-ms timeout-ms
                                  :prompt-source prompt-source})))
          successful (remove :error records)]
      (when (seq successful)
        (let [costs (sort (map #(get-in % [:cost :total]) successful))
              durs (sort (map :duration_seconds successful))
              med (fn [xs] (nth xs (quot (count xs) 2)))]
          (println)
          (println "SWEEP SUMMARY")
          (println (json/generate-string
                     {:task (name task)
                      :style (name style)
                      :models {:root model :sub sub-lm-model}
                      :n_runs runs
                      :n_successful (count successful)
                      :cost_median (med costs)
                      :cost_min (first costs)
                      :cost_max (last costs)
                      :duration_median_s (med durs)}
                     {:pretty true})))))
    (finally
      (dev/stop!)
      (shutdown-agents))))


;; ---------------------------------------------------------------------------
;; Parallel entry point
;; ---------------------------------------------------------------------------

(defn- summarize-records [stack-name records]
  (let [successful (remove :error records)
        costs (sort (map #(get-in % [:cost :total]) successful))
        durs  (sort (map :duration_seconds successful))
        med   (fn [xs] (when (seq xs) (nth xs (quot (count xs) 2))))]
    {:stack stack-name
     :n_runs (count records)
     :n_successful (count successful)
     :n_failed (- (count records) (count successful))
     :cost_total (reduce + 0.0 costs)
     :cost_median (med costs)
     :cost_min (first costs)
     :cost_max (last costs)
     :duration_median_s (med durs)}))

(defn- jobs-list
  "Cartesian product of (task, style, run-idx) for the requested sweep."
  [{:keys [tasks-vec styles runs]}]
  (vec (for [task tasks-vec, style styles, i (range 1 (inc runs))]
         {:task task :style style :run-idx i})))

(defn run-parallel
  "Single long-lived orc JVM, all (task × style × N) runs concurrent up to
   :max-parallel. Pre-builds sheets serially (LMDB-safe), then spawns
   futures bounded by a semaphore.

   Args (clj -X kwargs):
     :tasks         vector of task keywords; defaults to all 5
     :styles        vector of style keywords; default [:a :b]
     :runs          int, default 3
     :model         default \"openai/gpt-5\"
     :sub-lm-model  default \"openai/gpt-5-mini\"
     :max-iterations default 15
     :timeout-ms    per-run default 600000 (10 min)
     :max-parallel  default 4
     :prompt-source :predict-rlm (default) or :orc"
  [{:keys [tasks-vec styles runs model sub-lm-model max-iterations
           timeout-ms max-parallel prompt-source]
    :or   {tasks-vec [:image_analysis :invoice_processing :document_redaction
                       :contract_comparison :document_analysis]
           styles [:a :b]
           runs 3
           model "openai/gpt-5"
           sub-lm-model "openai/gpt-5-mini"
           max-iterations 15
           timeout-ms 600000
           max-parallel 4
           prompt-source :predict-rlm}}]
  (let [jobs (jobs-list {:tasks-vec tasks-vec :styles styles :runs runs})
        n-jobs (count jobs)]
    (println (format "Parallel bench: %d jobs (%d tasks × %d styles × %d runs), max-parallel=%d"
                     n-jobs (count tasks-vec) (count styles) runs max-parallel))
    (println (format "  models: root=%s sub=%s  prompt-source=%s  max-iter=%d  timeout=%ds"
                     model sub-lm-model (name prompt-source) max-iterations
                     (int (/ timeout-ms 1000))))
    (dev/start!)
    (try
      (let [ctx (dev/ctx)
            ;; Pre-build sheets serially — dsl/build-workflow! writes to LMDB
            ;; and we'd rather not race that. Sheet ids are deterministic
            ;; (uuidv5 from workflow content) so this is also memoization.
            sheet-ids (into {}
                            (for [task tasks-vec, style styles
                                  :let [w (override-models (load-workflow task style)
                                                           model sub-lm-model)]]
                              [[task style] (dsl/build-workflow! ctx w)]))
            _ (println (format "Pre-built %d unique sheets." (count sheet-ids)))
            ;; Per-task sweep dirs
            sweep-dirs (into {} (for [task tasks-vec
                                      :let [d (io/file repo-root "bench" "runs"
                                                       (name task))]]
                                  (do (.mkdirs d) [task d])))
            sem (java.util.concurrent.Semaphore. (int max-parallel))
            sweep-start (System/currentTimeMillis)
            futs (mapv
                  (fn [{:keys [task style run-idx]}]
                    (future
                      (.acquire sem)
                      (try
                        (run-once {:ctx ctx :task task :style style
                                   :main-model model :sub-model sub-lm-model
                                   :max-iterations max-iterations
                                   :sweep-dir (sweep-dirs task)
                                   :run-idx run-idx
                                   :timeout-ms timeout-ms
                                   :prompt-source prompt-source})
                        (catch Throwable t
                          (println (format "[%s style=%s run=%d] EXCEPTION: %s"
                                           (name task) (name style) run-idx (.getMessage t)))
                          {:task (name task) :stack (str "orc-style-" (name style))
                           :error (str t)
                           :cost {:total 0.0} :calls {:root 0 :sub 0}
                           :duration_seconds 0})
                        (finally (.release sem)))))
                  jobs)
            records (mapv deref futs)
            sweep-duration-s (/ (- (System/currentTimeMillis) sweep-start) 1000.0)]
        (println)
        (println "===========================================")
        (println (format "PARALLEL SWEEP COMPLETE — %.1fs wall-clock" sweep-duration-s))
        (println "===========================================")
        ;; Per-stack rollup
        (doseq [stack (sort (set (map :stack records)))]
          (let [rs (filter #(= stack (:stack %)) records)]
            (println (json/generate-string (summarize-records stack rs) {:pretty true}))))
        ;; Overall
        (println (json/generate-string
                   {:total_jobs n-jobs
                    :wall_clock_s sweep-duration-s
                    :max_parallel max-parallel
                    :total_cost (reduce + 0.0 (map #(get-in % [:cost :total] 0.0) records))}
                   {:pretty true})))
      (finally
        (dev/stop!)
        (shutdown-agents)))))
