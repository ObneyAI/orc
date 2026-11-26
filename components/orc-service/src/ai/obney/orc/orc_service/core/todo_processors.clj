(ns ai.obney.orc.orc-service.core.todo-processors
  "Behavior Tree Sheet todo processors (policies).

   These are event-driven side effects that respond to domain events:
   - Execute leaf nodes when tree tick starts
   - Handle sequence/fallback/parallel composite node logic
   - Handle map-each iteration
   - Update blackboard with node outputs"
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.orc.integrations.interface :as integrations]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.commands :as commands]
            [ai.obney.orc.orc-service.core.logic :as logic]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [clojure.core.async :as async]
            [clojure.pprint :as pprint]
            [clojure.set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [com.brunobonacci.mulog :as u]
            [malli.core :as m]
            [sci.core :as sci]))

(defn read-facts [ctx owner campaign] (:facts (commands/fact-state ctx owner campaign)))

(defn ensure!
  "Wait only for actual parent recovery facts, under the original absolute deadline.
   No model outcome is recorded while a legitimate recovery binding is pending."
  [ctx owner deadline-ms]
  (when-let [campaign (rm/researcher-campaign ctx (:tick-id owner))]
    (let [now-ms (or (:orc/model-authority-now-ms ctx) #(System/currentTimeMillis))
          sleep! (or (:orc/model-authority-sleep! ctx) #(Thread/sleep %))
          deadline (or deadline-ms (+ (now-ms) 30000))]
      (loop []
        (when (>= (now-ms) deadline)
          (throw (ex-info "Emitted model recovery authority deadline exhausted"
                          {:failure-kind :model-plan-incompatible :reason :emitted-authority-deadline})))
        (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException. "Emitted model recovery interrupted")))
        (let [{:keys [status authority reason]} (logic/assess owner campaign (read-facts ctx owner campaign))]
          (case status
            (:original :authorized) authority
            :ready (let [r (cp/process-command (assoc ctx :command
                             (merge owner {:command/name :sheet/authorize-emitted-model-work
                                           :command/id (random-uuid) :command/timestamp (time/now)})))]
                     (when-let [category (:cognitect.anomalies/category r)]
                       (when-not (= :cognitect.anomalies/conflict category)
                         (throw (ex-info "Emitted model recovery authority could not be recorded"
                                         {:failure-kind :model-plan-incompatible :reason :emitted-authority-write-failed})))
                       (sleep! 5))
                     (recur))
            :pending (if (< (now-ms) deadline)
                       (do (sleep! (min 25 (max 1 (- deadline (now-ms))))) (recur))
                       (throw (ex-info "Emitted model recovery authority was not established before the execution deadline"
                                       {:failure-kind :model-plan-incompatible :reason :emitted-authority-deadline})))
            (throw (ex-info "Emitted model recovery authority is no longer current"
                            {:failure-kind :model-plan-incompatible :reason reason}))))))))

(defn begin!
  "Mark one execution live in this process and start renewing its lease.
   Returns a handle for `end!`. Call on the dispatching thread, before the
   work is handed to its future, so liveness is visible from the moment the
   worker accepts the start."
  [context {:keys [tick-id node-id exec-context start-event-id] :as ids}]
  (let [k (logic/work-key tick-id node-id exec-context)
        stop (promise)
        interval-ms (max 1 (quot (logic/lease-ms context) 3))]
    (logic/mark-live! k)
    (logic/untrack-queued! k)
    (let [renewer
          (try
            (doto (Thread.
                   ^Runnable
                   (fn []
                     (loop []
                       (when (= :ai.obney.orc.orc-service.core.execution-lease/wait (deref stop interval-ms :ai.obney.orc.orc-service.core.execution-lease/wait))
                         (commands/renew! context ids)
                         (recur))))
                   "orc-lease-renewer")
              (.setDaemon true)
              (.start))
            (catch Throwable t
              (logic/unmark-live! k)
              (throw t)))]
      {:key k :stop stop :renewer renewer :ended? (atom false)
       :start-event-id start-event-id})))

;; --- Rlm tree executor ---

(defn- persisted-tick-events [context tick-id]
  (when (and tick-id (:event-store context))
    (into [] (es/read (:event-store context)
                      {:tenant-id (:tenant-id context)
                       :tags #{[:tick tick-id]}}))))

(defn- compute-by-node-from-tick-events
  "Read the event store for a tick's :sheet/node-execution-completed events
   and aggregate per-node usage."
  [event-store tenant-id tick-id]
  (let [tick-events (into [] (es/read event-store
                              (cond-> {:tags #{[:tick tick-id]}}
                                tenant-id (assoc :tenant-id tenant-id))))
        completions (filter #(= :sheet/node-execution-completed (:event/type %))
                            tick-events)]
    (logic/aggregate-by-node completions)))

(defn- clear-terminal-functions!
  "Release only this terminal tick's exact callable bindings. Another live
   tick of the same sheet may still need them; uncertain ownership retains them.
   Returns observed recoverable keys, or nil when cleanup observation failed."
  [context sheet-id tick-id]
  (let [observation
        (try
          (let [events (es/read (:event-store context)
                                {:tenant-id (:tenant-id context) :tags #{[:sheet sheet-id]}
                                 :types #{:sheet/tree-tick-started :sheet/tree-tick-completed :sheet/tick-cancelled}})]
            (when (and events (not (:cognitect.anomalies/category events)))
              (let [live (reduce (fn [active event]
                                   (case (:event/type event)
                                     :sheet/tree-tick-started (conj active (:tick-id event))
                                     :sheet/tree-tick-completed
                                     (if (logic/terminal-root-status->result-status (:root-status event))
                                       (disj active (:tick-id event)) active)
                                     :sheet/tick-cancelled (disj active (:tick-id event))
                                     active)) #{} events)
                    snapshot (rm/get-tick-execution-context context tick-id)
                    nodes (:nodes-by-id snapshot)]
                (when (and (map? snapshot) (not (:cognitect.anomalies/category snapshot))
                           (map? nodes))
                  {:live live
                   :bindings (into []
                                   (keep #(when (:recoverable-fn %)
                                            (select-keys % [:fn :recoverable-fn])))
                                   (vals nodes))}))))
          (catch Exception _ nil))]
    (when observation
      (when (empty? (:live observation))
        (doseq [{:keys [fn recoverable-fn]} (:bindings observation)]
          (swap! logic/ephemeral-fn-registry
                 (clojure.core/fn [registry]
                   (if (= recoverable-fn (:orc/recoverable-fn (meta (get registry fn))))
                     (dissoc registry fn)
                     registry)))))
      (into #{} (map :fn) (:bindings observation)))))

(defn- reconstruct-completed-tick
  "Return a durable child result when the stable Phase-2 tick already reached a
   terminal event. This closes the crash window between child completion and
   recording the result in the parent researcher action log."
  [context tick-id]
  (let [events (persisted-tick-events context tick-id)]
  (when-let [completion (last (filter #(and (= :sheet/tree-tick-completed
                                                (:event/type %))
                                             (some? (logic/terminal-root-status->result-status
                                                     (:root-status %))))
                                      events))]
    (let [root-status (:root-status completion)
          started (first (filter #(= :sheet/tree-tick-started (:event/type %))
                                 events))
          duration-ms (logic/elapsed-ms (:event/timestamp started)
                                             (:event/timestamp completion))
          usage (reduce (fn [acc event]
                          (if (and (= :sheet/node-execution-completed (:event/type event))
                                   (:usage event))
                            (merge-with + acc
                                        (select-keys (:usage event)
                                                     [:prompt-tokens
                                                      :completion-tokens
                                                      :total-tokens]))
                            acc))
                        {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}
                        events)
          by-node (compute-by-node-from-tick-events
                   (:event-store context) (:tenant-id context) tick-id)
          _ (clear-terminal-functions! context (:sheet-id completion) tick-id)]
      (cond-> {:status (logic/terminal-root-status->result-status root-status)
               :outputs (rm/final-values context (:tenant-id context) tick-id)
               :sheet-id (:sheet-id completion)
               :trace-id tick-id
               :replayed? true}
        (some? duration-ms) (assoc :duration-ms duration-ms)
        (pos? (:total-tokens usage 0))
        (assoc :usage (cond-> usage (seq by-node) (assoc :by-node by-node)))
        (:error completion) (assoc :error (:error completion))
        (= :blocked root-status)
        (assoc :block-payload (:block-payload completion)))))))

(defn- await-existing-tick
  [context tick-id timeout-ms]
  (when-let [started (first (filter #(= :sheet/tree-tick-started (:event/type %))
                                    (persisted-tick-events context tick-id)))]
    (or (reconstruct-completed-tick context tick-id)
        (let [p (logic/register-completion! tick-id)]
          (or (reconstruct-completed-tick context tick-id)
              (let [result (deref p timeout-ms :ai.obney.orc.orc-service.core.rlm-tree-executor/deref-expired)]
                (if (= :ai.obney.orc.orc-service.core.rlm-tree-executor/deref-expired result)
                  (do
                    (logic/deregister-completion! tick-id)
                    {:status :timeout
                     :error "Tree execution timed out"
                     :sheet-id (:sheet-id started)
                     :trace-id tick-id})
                  (or (reconstruct-completed-tick context tick-id)
                      (assoc result :replayed? true)))))))))

(defn- run-command!
  "Run a child setup command, stopping compilation when the command is rejected."
  [ctx command-data]
  (let [result (cp/process-command (assoc ctx :command command-data))]
    (when-let [category (:cognitect.anomalies/category result)]
      (let [diagnostic (logic/from-refusal (:command/name command-data) result)]
        (throw (ex-info
                (if diagnostic (logic/render diagnostic)
                    "Generated child setup command rejected")
                (cond-> {:reason :generated-child-setup-rejected
                         :preflight-rejected? true
                         :command-name (:command/name command-data)
                         :cognitect.anomalies/category category}
                  diagnostic (assoc :setup-diagnostic diagnostic))))))
    result))

(defn- compile-tree-node
  "Compile a single tree node into ORC sheet nodes.
   Returns {:node-id N :ephemeral-fn-keys [...]} where ephemeral-fn-keys
   are the keys of any inline functions registered during compilation.

   Supported node types:
   - sheet/sequence - Creates a sequence node
   - sheet/llm - Creates a leaf node with instruction
   - sheet/code - Creates a leaf node with code executor
   - sheet/map-each - Creates a map-each iteration node
   - final! - Creates a leaf node that just writes outputs"
  [context sheet-id tree parent-id & {:keys [index]}]
  (cond
    (and (seq? tree) (= 'sheet/sequence (first tree)))
    (let [seq-result (run-command! context
                       (logic/rlm-tree-executor-make-create-node-command sheet-id :sequence :parent-id parent-id :index index))
          seq-id (-> seq-result :command-result/events first :node-id)
          children (rest tree)
          child-results (vec (map-indexed
                               (fn [idx child-tree]
                                 (compile-tree-node context sheet-id child-tree seq-id :index idx))
                               children))
          all-fn-keys (vec (mapcat :ephemeral-fn-keys child-results))]
      {:node-id seq-id
       :ephemeral-fn-keys all-fn-keys})

    (and (seq? tree) (= 'sheet/parallel (first tree)))
    (let [par-result (run-command! context
                       (logic/rlm-tree-executor-make-create-node-command sheet-id :parallel :parent-id parent-id :index index))
          par-id (-> par-result :command-result/events first :node-id)
          children (rest tree)
          child-results (vec (map-indexed
                               (fn [idx child-tree]
                                 (compile-tree-node context sheet-id child-tree par-id :index idx))
                               children))
          all-fn-keys (vec (mapcat :ephemeral-fn-keys child-results))]
      {:node-id par-id
       :ephemeral-fn-keys all-fn-keys})

    (and (seq? tree) (= 'sheet/llm (first tree)))
    (let [opts (logic/parse-keyword-args (rest tree))
          leaf-result (run-command! context
                        (logic/rlm-tree-executor-make-create-node-command sheet-id :leaf :parent-id parent-id :index index))
          leaf-id (-> leaf-result :command-result/events first :node-id)
          reads (vec (:reads opts))
          writes (vec (:writes opts))]
      (run-command! context
        {:command/name :sheet/set-node-instruction
         :command/id (random-uuid)
         :command/timestamp (time/now)
         :sheet-id sheet-id
         :node-id leaf-id
         :instruction (:instruction opts)})
      (run-command! context
        {:command/name :sheet/set-node-io
         :command/id (random-uuid)
         :command/timestamp (time/now)
         :sheet-id sheet-id
         :node-id leaf-id
         :reads reads
         :writes writes})
      (when (:retry opts)
        (run-command! context
          {:command/name :sheet/set-node-retry
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id leaf-id
           :retry (:retry opts)}))
      (run-command! context
        (cond-> {:command/name :sheet/set-node-executor
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :sheet-id sheet-id
                 :node-id leaf-id
                 :executor :ai}
          (:model opts) (assoc :model (:model opts))
          (contains? opts :options) (assoc :options (:options opts))))
      (when (some #(contains? opts %) [:provider :fallback-models :fallback-policy])
        (run-command! context
          (merge {:command/name :sheet/set-node-model-policy
                  :command/id (random-uuid) :command/timestamp (time/now)
                  :sheet-id sheet-id :node-id leaf-id}
                 (select-keys opts [:provider :fallback-models :fallback-policy]))))
      {:node-id leaf-id
       :ephemeral-fn-keys []})

    (and (seq? tree) (= 'sheet/map-each (first tree)))
    (let [
          args (rest tree)
          child-node (last args)
          keyword-args (butlast args)
          opts (logic/parse-keyword-args keyword-args)
          map-each-result (run-command! context
                            (logic/rlm-tree-executor-make-create-node-command sheet-id :map-each :parent-id parent-id :index index))
          map-each-id (-> map-each-result :command-result/events first :node-id)]
      (run-command! context
        (cond-> {:command/name :sheet/set-map-each-config
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :sheet-id sheet-id
                 :node-id map-each-id
                 :source-key (:from opts)
                 :item-key (:as opts)
                 :output-key (:into opts)}
          (:max-concurrency opts) (assoc :max-concurrency (:max-concurrency opts))
          (some? (:preserve-failures? opts)) (assoc :preserve-failures? (:preserve-failures? opts))))
      (let [child-result (compile-tree-node context sheet-id child-node map-each-id :index 0)]
        {:node-id map-each-id
         :ephemeral-fn-keys (:ephemeral-fn-keys child-result)}))

    (and (seq? tree) (= 'sheet/code (first tree)))
    (let [opts (logic/parse-keyword-args (rest tree))
          f (:fn opts)
          string-fn? (string? f)
          recoverable-fn (:orc/recoverable-fn (meta f))
          fn-value (if string-fn? f (logic/register-ephemeral-fn! f))
          ephemeral-keys (if string-fn? [] [fn-value])
          leaf-result (run-command! context
                        (logic/rlm-tree-executor-make-create-node-command sheet-id :leaf :parent-id parent-id :index index))
          leaf-id (-> leaf-result :command-result/events first :node-id)
          reads (vec (:reads opts))
          writes (vec (:writes opts))
          tool-caller-fn (:tool-caller-fn opts)
          tool-contracts (:tool-contracts opts)]
      (run-command! context
        {:command/name :sheet/set-node-io
         :command/id (random-uuid)
         :command/timestamp (time/now)
         :sheet-id sheet-id
         :node-id leaf-id
         :reads reads
         :writes writes})
      (run-command! context
        (cond-> {:command/name :sheet/set-node-executor
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :sheet-id sheet-id
                 :node-id leaf-id
                 :executor :code
                 :fn fn-value}
          recoverable-fn (assoc :recoverable-fn recoverable-fn)
          tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
          (seq tool-contracts) (assoc :tool-contracts tool-contracts)))
      {:node-id leaf-id
       :ephemeral-fn-keys ephemeral-keys})

    (and (seq? tree) (= 'final! (first tree)))
    {:node-id nil
     :ephemeral-fn-keys []}

    :else
    (throw (ex-info (str "Unknown tree node type: " (first tree))
                    {:tree tree}))))

;; =============================================================================
;; Tree Executor
;; =============================================================================

(defn execute-tree
  "Execute a canonical ORC DSL tree via child tick.

   Arguments:
   - tree: Canonical ORC DSL (output of rlm-dsl->orc-dsl)
   - context: Execution context with event-store, cache, etc.
   - options: Map with:
     - :sandbox-vars - Variables from Phase 1 to write to blackboard
     - :blackboard - Additional blackboard inputs
     - :timeout-ms - Execution timeout (default 60000)
     - :result-grace-ms - Silence tolerance for classifying an expired wait
                          as WEDGED vs over budget (default
                          runtime/default-result-grace-ms; see
                          runtime/run-liveness-verdict)

   Returns:
   {:status :success/:failure/:timeout
    :outputs {...}
    :usage {...}
    :duration-ms N}

   U6 options:
     :blackboard-schemas - Optional {key schema} map preserving parent
                           schemas (e.g. [:string {:field-type :image}])
                           so vision/audio inputs route correctly through
                           the child sheet's leaf nodes. When absent for a
                           given key, falls back to inferring schema from
                           value type."
  [tree context {:keys [sandbox-vars blackboard blackboard-schemas timeout-ms
                        result-grace-ms generated-tree-raw
                        generated-tree-source stable-tick-id]
                 :or {timeout-ms 60000
                      result-grace-ms logic/default-result-grace-ms
                      blackboard-schemas {}}}]
  (if-let [existing (and stable-tick-id
                         (await-existing-tick context stable-tick-id timeout-ms))]
    existing
    (do
  (println "[DEBUG Tree] execute-tree starting")
  (println "[DEBUG Tree] sandbox-vars keys:" (keys sandbox-vars))
  (println "[DEBUG Tree] blackboard keys:" (keys blackboard))
  (try
    (let [start-time (System/currentTimeMillis)
          tree-keys (set (logic/extract-all-keys tree))
          sandbox-vars (select-keys (or sandbox-vars {}) tree-keys)
          output-keys (logic/extract-output-keys tree)
          tree-schemas (logic/extract-key-schemas tree)
          _ (logic/merge-contracts :inherited blackboard-schemas tree-schemas)
          input-schemas (merge
                         (select-keys blackboard-schemas tree-keys)
                         (into {} (map (fn [[key value]]
                                         [key (or (get blackboard-schemas key)
                                                  (get tree-schemas key)
                                                  (logic/infer-specific-schema value [key]))]))
                               sandbox-vars)
                         (into {} (map (fn [[key value]]
                                         [key (or (get blackboard-schemas key)
                                                  (get tree-schemas key)
                                                  (logic/infer-specific-schema value [key]))]))
                               blackboard))
          undeclared-keys (remove #(or (contains? input-schemas %)
                                       (contains? tree-schemas %))
                                  tree-keys)
          _ (when (seq undeclared-keys)
              (throw (ex-info
                      (str "Generated tree keys " (vec undeclared-keys)
                           " have no specific schema; declare :output-schemas using the most specific schema possible for each value's intent")
                      {:preflight-rejected? true :blackboard-keys (vec undeclared-keys)})))
          _ (println "[DEBUG Tree] Creating ephemeral sheet...")
          sheet-result (run-command! context
                         (logic/rlm-tree-executor-make-create-sheet-command))
          _ (println "[DEBUG Tree] sheet-result:" (pr-str (select-keys sheet-result [:command-result/events :cognitect.anomalies/category])))
          sheet-id (-> sheet-result :command-result/events first :sheet-id)
          _ (println "[DEBUG Tree] Created sheet:" sheet-id)

          _ (println "[DEBUG Tree] Tree keys:" tree-keys)
          _ (println "[DEBUG Tree] Output keys:" output-keys)

          _ (doseq [[k schema] (merge tree-schemas input-schemas)]
              (run-command! context
                (logic/rlm-tree-executor-make-declare-key-command sheet-id k schema)))

          _ (println "[DEBUG Tree] Compiling tree into ORC nodes...")
          compile-result (try
                           (compile-tree-node context sheet-id tree nil)
                           (catch Exception e
                             (println "[DEBUG Tree] COMPILE ERROR:" (.getMessage e))
                             (.printStackTrace e)
                             (throw e)))
          ephemeral-fn-keys (:ephemeral-fn-keys compile-result)
          _ (println "[DEBUG Tree] Compiled. Root node:" (:node-id compile-result))
          _ (println "[DEBUG Tree] Ephemeral fn keys:" ephemeral-fn-keys)

          tick-id (or stable-tick-id (random-uuid))
          _ (println "[DEBUG Tree] Starting tick:" tick-id)
          parent-tick-id (:tick-id context)
          _ (when parent-tick-id
              (logic/link-child! parent-tick-id tick-id)
              (logic/emit! parent-tick-id
                               (cond-> {:orc.stream/type :rlm-phase2-started
                                        :child-tick-id tick-id}
                                 (:sheet-id context) (assoc :sheet-id (:sheet-id context))
                                 (:node-id context) (assoc :node-id (:node-id context)))))
          p (logic/register-completion! tick-id)
          tool-context (:tool-context context)
          correlation-id (:orc/correlation-id context)
          llm-call-budget (get-in context [:tick-options :llm-call-budget])
          durable-budget? (and (integer? llm-call-budget)
                               (pos? llm-call-budget))
          llm-budget-root-tick-id
          (when durable-budget?
            (or (get-in context [:tick-options :llm-budget-root-tick-id])
                parent-tick-id))
          llm-budget-root-sheet-id
          (when durable-budget?
            (or (get-in context [:tick-options :llm-budget-root-sheet-id])
                (:sheet-id context)))
          assessment-origin (get-in context [:tick-options :assessment-origin])
          campaign-sheet-id
          (or (get-in context [:tick-options :researcher-campaign-sheet-id])
              (:sheet-id context))
          campaign-tick-id
          (or (get-in context [:tick-options :researcher-campaign-tick-id])
              (:tick-id context))
          campaign-node-id
          (or (get-in context [:tick-options :researcher-campaign-node-id])
              (:node-id context))
          campaign-iteration-index
          (or (get-in context [:tick-options :researcher-iteration-index])
              (:researcher-iteration context))
          campaign-ownership-epoch
          (or (get-in context [:tick-options :researcher-ownership-epoch])
              (:researcher-ownership-epoch context))
          tick-cmd-result (cp/process-command
                            (assoc context :command
                                   (cond-> {:command/id (random-uuid)
                                            :command/timestamp (time/now)
                                            :command/name :sheet/tick-tree
                                            :sheet-id sheet-id
                                            :tick-id tick-id
                                            :inputs (merge blackboard sandbox-vars)
                                            :options (cond-> {:timeout-ms timeout-ms}
                                                       assessment-origin
                                                       (assoc :assessment-origin assessment-origin)
                                                       durable-budget?
                                                       (assoc :llm-call-budget llm-call-budget)
                                                       campaign-ownership-epoch
                                                       (assoc :researcher-campaign-sheet-id campaign-sheet-id
                                                              :researcher-campaign-tick-id campaign-tick-id
                                                              :researcher-campaign-node-id campaign-node-id
                                                              :researcher-campaign-exec-context (or (:exec-context context) (logic/exec-context (get-in context [:event :inputs])) {})
                                                              :researcher-iteration-index campaign-iteration-index
                                                              :researcher-ownership-epoch campaign-ownership-epoch)
                                                       (and durable-budget?
                                                            (pos-int? campaign-ownership-epoch)
                                                            (nat-int? campaign-iteration-index)
                                                            (every? uuid? [campaign-sheet-id campaign-tick-id campaign-node-id]))
                                                       (assoc :checkpointed-campaign? true)
                                                       durable-budget?
                                                       (assoc :llm-budget-root-tick-id
                                                              llm-budget-root-tick-id
                                                              :llm-budget-root-sheet-id
                                                              llm-budget-root-sheet-id))}
                                     parent-tick-id (assoc :parent-tick-id parent-tick-id)
                                     correlation-id (assoc :correlation-id correlation-id)
                                     tool-context (assoc :tool-context tool-context))))
          tick-anomaly (:cognitect.anomalies/category tick-cmd-result)

          _ (when-not tick-anomaly
              (println "[DEBUG Tree] Waiting for tick completion (timeout:" timeout-ms "ms)..."))
          result (if tick-anomaly
                   (do
                     (logic/deregister-completion! tick-id)
                     (println "[DEBUG Tree] tick-tree command rejected:"
                              (:cognitect.anomalies/message tick-cmd-result))
                     {:status :failure
                      :admission-rejected? true
                      :error (str ":sheet/tick-tree command rejected: "
                                  (:cognitect.anomalies/message tick-cmd-result)
                                  (when-let [explain (:error/explain tick-cmd-result)]
                                    (str " — explain: " (pr-str explain))))
                      :sheet-id sheet-id
                      :trace-id tick-id})
                   (let [r (deref p timeout-ms :ai.obney.orc.orc-service.core.rlm-tree-executor/deref-expired)]
                     (if-not (= :ai.obney.orc.orc-service.core.rlm-tree-executor/deref-expired r)
                       r
                       (let [waited-ms (- (System/currentTimeMillis) start-time)
                             {:keys [wedged?] :as verdict}
                             (commands/run-liveness-verdict context tick-id
                                                           result-grace-ms
                                                           waited-ms)]
                         (if-not wedged?
                           {:status :timeout
                            :error "Tree execution timed out"
                            :sheet-id sheet-id
                            :trace-id tick-id}
                           {:status :failure
                            :wedged? true
                            :error (str "Run wedged at "
                                        :orc.rlm-tree-executor/execute-tree
                                        ": engine silent past the child tick's "
                                        timeout-ms " ms budget plus " result-grace-ms
                                        " ms grace on tick " tick-id
                                        "; a wedged run is terminal and must not"
                                        " be retried")
                            :sheet-id sheet-id
                            :trace-id tick-id
                            :liveness (assoc verdict
                                             :seam :orc.rlm-tree-executor/execute-tree
                                             :tick-id tick-id
                                             :sheet-id sheet-id
                                             :waited-ms waited-ms)})))))
          duration-ms (- (System/currentTimeMillis) start-time)
          _ (when parent-tick-id
              (logic/emit! parent-tick-id
                               (cond-> {:orc.stream/type :rlm-phase2-completed
                                        :child-tick-id tick-id
                                        :status (:status result)
                                        :duration-ms duration-ms}
                                 (:sheet-id context) (assoc :sheet-id (:sheet-id context))
                                 (:node-id context) (assoc :node-id (:node-id context))
                                 (:error result) (assoc :error (:error result)))))
          _ (println "[DEBUG Tree] Tick completed. Status:" (:status result))
          _ (when (:error result) (println "[DEBUG Tree] Error:" (:error result)))
          _ (println "[DEBUG Tree] Output keys:" (keys (:outputs result)))]

      (when-let [recoverable-keys (clear-terminal-functions! context sheet-id tick-id)]
        (doseq [fn-key ephemeral-fn-keys
                :when (not (contains? recoverable-keys fn-key))]
          (logic/clear-ephemeral-fn! fn-key)))

      (let [event-store (:event-store context)
            tenant-id (:tenant-id context)
            by-node (when event-store
                      (compute-by-node-from-tick-events event-store tenant-id tick-id))
            tick-events (when event-store
                          (into [] (es/read event-store
                                     (cond-> {:tags #{[:tick tick-id]}}
                                       tenant-id (assoc :tenant-id tenant-id)))))
            trajectory (vec
                         (for [e (sort-by :event/timestamp tick-events)]
                           (let [body (cond-> {:event-type (:event/type e)
                                               :timestamp (:event/timestamp e)}
                                        (:node-id e) (assoc :node-id (:node-id e))
                                        (:status e) (assoc :status (:status e)))]
                             body)))
            _ (try
                (cp/process-command
                  (assoc context :command
                         (cond-> {:command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :command/name :sheet/record-rlm-tree-execution-completion
                                  :sheet-id sheet-id
                                  :tick-id tick-id
                                  :trajectory trajectory
                                  :total-usage (or (:usage result) {})
                                  :duration-ms duration-ms}
                           (some? (:status result))
                           (assoc :status (:status result))
                           (some? generated-tree-raw)
                           (assoc :generated-tree generated-tree-raw)
                           (some? generated-tree-source)
                           (assoc :generated-tree-source generated-tree-source)
                           (some? generated-tree-raw)
                           (assoc :tree-fingerprint
                                  (try (logic/fingerprint generated-tree-raw)
                                       (catch Exception _ nil)))
                           (some? (:sheet-id context))
                           (assoc :source-sheet-id (:sheet-id context))
                           (some? (:tick-id context))
                           (assoc :source-tick-id (:tick-id context)))))
                (catch Exception e
                  (println "[DEBUG Tree] Bookend emission failed:" (.getMessage e))))
            result-with-duration (assoc result :duration-ms duration-ms)]
        (if (seq by-node)
          (update result-with-duration :usage
                  (fn [u] (assoc (or u {}) :by-node by-node)))
          result-with-duration)))
    (catch Exception e
      (println "[DEBUG Tree] EXECUTION ERROR:" (.getMessage e))
      (.printStackTrace e)
      (let [diagnostic (logic/from-exception e)]
        (cond-> {:status :failure
                 :error (str "Tree execution failed: " (.getMessage e))
                 :duration-ms 0}
          diagnostic
          (assoc :setup-diagnostic diagnostic
                 :error (logic/render diagnostic))
          (:preflight-rejected? (ex-data e))
          (assoc :preflight-rejected? true)
          (:blackboard-keys (ex-data e))
          (assoc :preflight-rejected? true
                 :missing-schema-keys (:blackboard-keys (ex-data e))))))))))

;; --- Provider observations ---

(defn- invoke-command! [ctx command-name data]
  (let [call #(cp/process-command
               (assoc ctx :command (merge data {:command/name command-name :command/id (random-uuid)
                                                :command/timestamp (time/now)})))
        result (call)]
    (if (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)) (call) result)))

(defn start-provider-attempt! [ctx attempt] (invoke-command! ctx :sheet/start-provider-attempt attempt))

(defn complete-provider-attempt! [ctx attempt] (invoke-command! ctx :sheet/complete-provider-attempt attempt))

;; =============================================================================
;; Public API
;; =============================================================================

(defn- latest-researcher-frontier-epoch
  [{:keys [event-store tenant-id]} sheet-id tick-id node-id]
  (transduce
   (map :ownership-epoch)
   max
   0
   (es/read event-store
            {:tenant-id tenant-id
             :types #{:rlm/researcher-frontier-claimed}
             :tags #{(logic/campaign-tag sheet-id tick-id node-id)}})))

(defn resume-frontiers!
  "Resume abandoned map coordinators plus leaf, delegate, and researcher
   frontiers from durable state.

   Intended to be called after todo processors have been rebuilt against the
   same event store. Completed nodes are never re-enqueued. Each recovery start
   durably references the abandoned start event, making repeated calls
   idempotent. Returns one result map per active executable frontier inspected."
  [context recover-map-coordinator!]
  (vec
   (mapcat
    (fn [{:keys [tick-id sheet-id nodes-in-progress]}]
      (let [tick-ctx (rm/get-tick-execution-context context tick-id)
            nodes-by-id (:nodes-by-id tick-ctx)
            tick-events (into [] (es/read (:event-store context)
                                          {:tenant-id (:tenant-id context)
                                           :tags #{[:tick tick-id]}}))
            {:keys [open-starts renewals-by-start]} (logic/tick-open-starts tick-events)
            _ (doseq [node-id nodes-in-progress
                      :when (= :map-each (:type (get nodes-by-id node-id)))
                      :let [start (first
                                   (filter #(and (= :sheet/node-execution-started
                                                    (:event/type %))
                                                 (= node-id (:node-id %))
                                                 (nil? (:resumed-from-event-id %)))
                                           tick-events))]
                      :when start]
                (recover-map-coordinator! context sheet-id tick-id node-id)
                (cp/process-command
                 (assoc context
                        :command {:command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :command/name :sheet/resume-node-execution
                                  :sheet-id sheet-id
                                  :tick-id tick-id
                                  :node-id node-id
                                  :original-start-event-id (:event/id start)
                                  :inputs (:inputs start)})))]
        (keep
         (fn [start]
           (let [node-id (:node-id start)
                 node (get nodes-by-id node-id)]
             (when (logic/recoverable-start? context nodes-by-id renewals-by-start start)
               (let [researcher-ownership-epoch
                     (when (logic/checkpointed? node)
                       (inc (latest-researcher-frontier-epoch
                             context sheet-id tick-id node-id)))
                     command
                     (cond-> {:command/id (random-uuid)
                              :command/timestamp (time/now)
                              :command/name :sheet/resume-node-execution
                              :sheet-id sheet-id
                              :tick-id tick-id
                              :node-id node-id
                              :original-start-event-id (:event/id start)
                              :inputs (:inputs start)}
                       researcher-ownership-epoch
                       (assoc :researcher-ownership-epoch
                              researcher-ownership-epoch))
                     result (cp/process-command
                             (assoc context :command command))]
                 (cond-> {:tick-id tick-id
                          :sheet-id sheet-id
                          :node-id node-id
                          :original-start-event-id (:event/id start)
                          :resumed? (boolean
                                     (seq (:command-result/events result)))
                          :command-result result}
                   researcher-ownership-epoch
                   (assoc :researcher-ownership-epoch
                          researcher-ownership-epoch))))))
         open-starts)))
    (rm/get-all-active-executions context))))

;; --- Streaming ---

(defn- node-info
  "Best-effort node name/type lookup from the tick execution snapshot,
   memoized per tick on the subscription. Returns {} when unresolvable."
  [sub tick-id node-id]
  (try
    (let [cache (:node-cache sub)
          nodes (or (get @cache tick-id)
                    (let [n (some-> (rm/get-tick-execution-context (:context sub) tick-id)
                                    :nodes-by-id)]
                      (swap! cache assoc tick-id (or n {}))
                      n))]
      (if-let [node (get nodes node-id)]
        (cond-> {}
          (:name node) (assoc :node-name (:name node))
          (:type node) (assoc :node-type (if (= :leaf (:type node))
                                           (or (:executor node) :ai)
                                           (:type node))))
        {}))
    (catch Exception _ {})))

(defn- tick-values
  "Best-effort resolution of `ks` to values for a tick, for envelope fields the
   durable event references by key only.

   The tick blackboard read model holds metadata, not values, so these resolve
   from the canonical write log. NOT memoized — unlike nodes-by-id this changes
   as the tick runs. Narrow by construction: callers pass the exact key set the
   event names (:write-keys, :output-keys), never the whole blackboard."
  [sub tick-id ks]
  (try
    (let [{:keys [context]} sub]
      (rm/resolve-values context (:tenant-id context) tick-id
                                (rm/get-tick-blackboard context tick-id)
                                ks))
    (catch Exception _ nil)))

(defn- normalize-durable
  "Durable Grain event -> envelope (sans :seq/:ts/:root-tick-id), or nil to
   skip. include-values? gates :writes payloads."
  [sub event include-values?]
  (let [t (:event/type event)
        tick-id (:tick-id event)
        base {:tick-id tick-id
              :sheet-id (:sheet-id event)}]
    (case t
      :sheet/tree-tick-started
      (merge base
             {:orc.stream/type :tick-started}
             (when-let [i (:iteration event)] {:iteration i})
             (when-let [p (:parent-tick-id event)] {:parent-tick-id p})
             (when-let [ks (seq (keys (:seed-sources event)))]
               {:input-keys (vec ks)}))

      :sheet/node-execution-started
      (merge base
             {:orc.stream/type :node-started
              :node-id (:node-id event)}
             (node-info sub tick-id (:node-id event))
             (when-let [me (logic/map-each-context (:inputs event))] {:map-each me}))

      :sheet/node-execution-completed
      (merge base
             {:orc.stream/type :node-completed
              :node-id (:node-id event)
              :status (:status event)}
             (node-info sub tick-id (:node-id event))
             (when include-values?
               (when-let [ks (seq (:write-keys event))]
                 (when-let [vs (not-empty (tick-values sub tick-id ks))]
                   {:writes (logic/truncate-values vs)})))
             (when-let [u (:usage event)] {:usage u})
             (when-let [d (:duration-ms event)] {:duration-ms d})
             (when-let [e (:error event)] {:error e})
             (when-let [ck (:completion-kind event)] {:completion-kind ck})
             (when-let [me (logic/map-each-context (:inputs event))] {:map-each me}))

      :sheet/sequence-progress-updated
      (merge base {:orc.stream/type :progress
                   :node-id (:node-id event)
                   :kind :sequence
                   :index (:child-index event)
                   :total (:total-children event)})

      :sheet/map-each-progress-updated
      (merge base {:orc.stream/type :progress
                   :node-id (:node-id event)
                   :kind :map-each
                   :index (:item-index event)
                   :total (:total-items event)})

      :sheet/tree-tick-completed
      (when (not= :running (:root-status event))
        (merge base
               {:orc.stream/type :tick-completed
                :status (:root-status event)}
               (when include-values?
                 (when-let [ks (seq (:output-keys event))]
                   (when-let [vs (not-empty (tick-values sub tick-id ks))]
                     {:outputs (logic/truncate-values vs)})))
               (when-let [e (:error event)] {:error e})))

      :sheet/tick-cancelled
      (merge base {:orc.stream/type :tick-cancelled})

      :rlm/researcher-iteration-recorded
      (merge base
             {:orc.stream/type :rlm-iteration-recorded
              :node-id (:node-id event)
              :iteration-index (:iteration-index event)
              :attempt-ordinal (:attempt-ordinal event)
              :iteration-record (:iteration-record event)})

      :rlm/tree-generated
      {:orc.stream/type :rlm-tree-generated
       :tick-id (:execution-id event)
       :sheet-id (:sheet-id event)
       :node-id (:node-id event)
       :raw-dsl (logic/truncate-value (:raw-dsl event))}

      nil)))

(defn- start-router!
  "One router per subscription: single consumer of src-chan, single :seq
   assigner. Ordering holds because every producer (tap loops, emit!,
   link-child!) targets the same FIFO.

   Runs on a dedicated thread (async/thread), NOT a go block: envelope
   normalization does read-model lookups (node-info) and pr-str of
   potentially large values (truncate-values) — blocking work that would
   starve the fixed core.async dispatch pool and could stall the shared
   engine pubsub's distribution loops (P1)."
  [sub-id]
  (async/thread
    (loop []
      (let [sub (get @logic/subscriptions sub-id)
            m (when sub (async/<!! (:src-chan sub)))]
        (cond
          (or (nil? sub) (nil? m))
          nil

          (:ai.obney.orc.orc-service.core.streaming/close m)
          (let [n (swap! (:seq-counter sub) inc)]
            (async/offer! (:events-ch sub)
                          {:orc.stream/type :stream-closed
                           :seq n
                           :ts (time/now)
                           :tick-id (:root-tick-id sub)
                           :root-tick-id (:root-tick-id sub)
                           :reason (:ai.obney.orc.orc-service.core.streaming/close m)})
            (logic/finalize-subscription! sub-id))

          :else
          (let [include-values? (get-in sub [:opts :include-values?] true)
                envelope (try
                           (if (:orc.stream/type m)
                             (logic/ephemeral-for-sub sub m)
                             (normalize-durable sub m include-values?))
                           (catch Exception _ nil))
                envelope (when-not (and (= :tick-cancelled (:orc.stream/type envelope))
                                        (not= (:tick-id envelope)
                                              (:root-tick-id sub)))
                           envelope)]
            (when envelope
              (let [n (swap! (:seq-counter sub) inc)
                    terminal? (and (contains? #{:tick-completed :tick-cancelled}
                                              (:orc.stream/type envelope))
                                   (= (:tick-id envelope) (:root-tick-id sub)))]
                (async/offer! (:events-ch sub)
                              (assoc envelope
                                     :seq n
                                     :ts (time/now)
                                     :root-tick-id (:root-tick-id sub)))
                (when terminal?
                  (let [close-n (swap! (:seq-counter sub) inc)
                        reason (if (= :tick-cancelled (:orc.stream/type envelope))
                                 :cancelled
                                 :completed)]
                    (async/offer! (:events-ch sub)
                                  {:orc.stream/type :stream-closed
                                   :seq close-n
                                   :ts (time/now)
                                   :tick-id (:root-tick-id sub)
                                   :root-tick-id (:root-tick-id sub)
                                   :reason reason})
                    (logic/finalize-subscription! sub-id)))))
            (when (get @logic/subscriptions sub-id)
              (recur))))))))

(defn subscribe-execution
  "Subscribe to the live event stream for tick-id and every child tick
   spawned under it (RLM Phase 2 trees, delegate nodes). Call BEFORE
   dispatching :sheet/tick-tree — tick-id is caller-suppliable on
   execute/tick-tree — so no events are missed.

   Options:
     :include-values? - include :writes/:outputs payloads (default true,
                        values capped at ~16KB with a :orc.stream/truncated
                        marker; raw values are in the event store)
     :llm-deltas?     - opt into live LLM output (:llm-fields progressive
                        field snapshots) from :ai leaves (default false;
                        requires a ORC LLM with predict-stream-v2, otherwise
                        leaves run blocking as always)
     :raw-deltas?     - additionally receive :llm-raw-delta raw text chunks
                        (debug-grade; default false)
     :buffer          - consumer channel sliding-buffer size (default 4096).
                        A slow consumer loses OLDEST events; detect via :seq
                        gaps and reconcile from the event store.
     :ttl-ms          - backstop close for ticks that never complete
                        (default 1h)

   Returns {:events-ch <chan of envelopes, closes after :stream-closed>
            :tick-id tick-id
            :close! (fn [] ...)}   ;; idempotent
   or an anomaly map when no pubsub is reachable from the context."
  [context tick-id & {:keys [include-values? llm-deltas? raw-deltas? buffer ttl-ms]
                      :or {include-values? true
                           buffer logic/default-buffer
                           ttl-ms logic/default-ttl-ms}}]
  (let [ps (logic/resolve-pubsub context)]
    (if-not ps
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message
       "No event pubsub reachable: expected :event-pubsub in context or [:config :event-pubsub] on :event-store"}
      (do
        (logic/ensure-tap! ps)
        (let [sub-id (random-uuid)
              sub {:root-tick-id tick-id
                   :tick-ids #{tick-id}
                   :tenant-id (:tenant-id context)
                   :src-chan (async/chan (async/sliding-buffer logic/default-buffer))
                   :events-ch (async/chan (async/sliding-buffer buffer))
                   :seq-counter (atom 0)
                   :node-cache (atom {})
                   :context context
                   :opts {:include-values? include-values?
                          :llm-deltas? (boolean llm-deltas?)
                          :raw-deltas? (boolean raw-deltas?)}}]
          (swap! logic/subscriptions assoc sub-id sub)
          (start-router! sub-id)
          (async/go
            (async/<! (async/timeout ttl-ms))
            (logic/close-subscription! sub-id :ttl))
          {:events-ch (:events-ch sub)
           :tick-id tick-id
           :sub-id sub-id
           :close! (fn [] (logic/close-subscription! sub-id :closed-by-consumer))})))))

(defn execute-stream
  "Non-blocking streamed execution. Subscribes, then dispatches the tick the
   same way runtime/execute does, and returns immediately.

   Accepts every runtime/execute option (:timeout-ms :result-grace-ms
   :use-version :force-draft :trace? :langfuse-client :store-trace?
   :max-ticks :llm-call-budget :tick-id :correlation-id) plus subscription
   opts (:include-values? :buffer :ttl-ms). A run declared WEDGED (see
   runtime/expire-run!) closes the stream with reason :wedged and delivers
   the distinct non-retryable liveness failure to :result.

   Returns {:tick-id uuid
            :events-ch <chan of envelopes>
            :close! (fn [])
            :result <promise of the exact runtime/execute return map>}
   or an anomaly map when subscription fails."
  [context sheet-id inputs & {:keys [timeout-ms result-grace-ms use-version force-draft
                                     trace? langfuse-client store-trace?
                                     max-ticks llm-call-budget tick-id
                                     correlation-id
                                     include-values? llm-deltas? raw-deltas? buffer ttl-ms]
                              :or {timeout-ms 300000
                                   result-grace-ms logic/default-result-grace-ms
                                   store-trace? true
                                   include-values? true
                                   buffer logic/default-buffer}}]
  (let [correlation-id (or correlation-id (:orc/correlation-id context))
        tick-id (or tick-id (random-uuid))
        subscription (subscribe-execution context tick-id
                                          :include-values? include-values?
                                          :llm-deltas? llm-deltas?
                                          :raw-deltas? raw-deltas?
                                          :buffer buffer
                                          :ttl-ms (or ttl-ms (+ timeout-ms logic/default-result-grace-ms)))]
    (if (:cognitect.anomalies/category subscription)
      subscription
      (let [result-promise (promise)
            p (logic/register-completion! tick-id)
            start-time (System/currentTimeMillis)
            cmd-result (cp/process-command
                        (assoc context :command
                               (cond-> {:command/id (random-uuid)
                                        :command/timestamp (time/now)
                                        :command/name :sheet/tick-tree
                                        :sheet-id sheet-id
                                        :tick-id tick-id
                                        :inputs (or inputs {})
                                        :options (cond-> {:timeout-ms timeout-ms
                                                          :execution-deadline-ms (+ start-time timeout-ms)
                                                          :store-trace? store-trace?}
                                                   trace? (assoc :trace? true)
                                                   langfuse-client (assoc :langfuse-client langfuse-client)
                                                   max-ticks (assoc :max-ticks max-ticks)
                                                   llm-call-budget (assoc :llm-call-budget llm-call-budget))}
                                 use-version (assoc :use-version use-version)
                                 force-draft (assoc :force-draft force-draft)
                                 correlation-id (assoc :correlation-id correlation-id)
                                 (:tool-context context)
                                 (assoc :tool-context (:tool-context context)))))]
        (if (:cognitect.anomalies/category cmd-result)
          (do (logic/deregister-completion! tick-id)
              (logic/emit! tick-id {:orc.stream/type :error
                              :error (:cognitect.anomalies/message cmd-result)
                              :source :command-dispatch})
              ((:close! subscription))
              (deliver result-promise
                       {:status :failure
                        :error (:cognitect.anomalies/message cmd-result)
                        :duration-ms (- (System/currentTimeMillis) start-time)}))
          (future
            (let [result (deref p timeout-ms :ai.obney.orc.orc-service.core.streaming/timeout)
                  duration-ms (- (System/currentTimeMillis) start-time)]
              (logic/deregister-completion! tick-id)
              (if (= result :ai.obney.orc.orc-service.core.streaming/timeout)
                (let [expired (commands/expire-run!
                               context sheet-id tick-id inputs duration-ms
                               {:grace-ms result-grace-ms
                                :seam :orc.streaming/execute-stream})]
                  (logic/close-subscription! (:sub-id subscription)
                                       (if (:wedged? expired) :wedged :timeout))
                  (deliver result-promise expired))
                (deliver result-promise (assoc result :duration-ms duration-ms))))))
        {:tick-id tick-id
         :events-ch (:events-ch subscription)
         :close! (:close! subscription)
         :result result-promise}))))

(defn cancel!
  "Cancel a running tick (and any known child ticks spawned under it).

   Best-effort semantics: the engine stops progressing (no new nodes start;
   the re-tick loop halts) and interrupts registered in-flight leaf work.
   Blocking callers unblock with {:status :failure :cancelled? true}; live
   streams receive :tick-cancelled then :stream-closed {:reason :cancelled}.

   Returns {:cancelled [tick-ids...]} or an anomaly map when the tick is
   unknown or already terminal."
  [context tick-id]
  (let [tick (rm/get-tick context tick-id)]
    (cond
      (nil? tick)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Unknown tick: " tick-id)}

      (contains? #{:completed :cancelled} (:status tick))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Tick already " (name (:status tick)))}

      :else
      (let [targets (cons tick-id (logic/descendants-of tick-id))
            cancelled (vec
                       (for [t targets
                             :let [trec (if (= t tick-id) tick (rm/get-tick context t))
                                   sheet-id (:sheet-id trec)]
                             :when (and sheet-id
                                        (not (contains? #{:completed :cancelled}
                                                        (:status trec))))]
                         (do (cp/process-command
                              (assoc context :command
                                     {:command/id (random-uuid)
                                      :command/timestamp (time/now)
                                      :command/name :sheet/cancel-tick
                                      :sheet-id sheet-id
                                      :tick-id t
                                      :reason "cancelled by operator"}))
                             t)))]
        {:cancelled cancelled}))))

;; --- Model call journal ---

(defn- command! [ctx name data]
  (loop [attempt 0]
    (let [result (cp/process-command (assoc ctx :command
                                          (merge data {:command/name name :command/id (random-uuid)
                                                       :command/timestamp (time/now)})))]
      (if (and (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)) (< attempt 3))
        (recur (inc attempt)) result))))

(defn callbacks
  "Bind durable capabilities for one logical model call. Owner is current actual
   node ownership plus :role and :logical-action-id. No raw context is persisted."
  [ctx owner candidates policy]
  (let [owner (assoc owner :configuration-id (or (:configuration-id owner) (logic/configuration-id candidates policy)))
        xs (commands/call-events ctx owner)
        records (filter #(= :sheet/model-candidate-recorded (:event/type %)) xs)
        terminal (last records)
        selected (commands/events ctx #{(logic/scope-tag (commands/scope ctx owner))} #{:sheet/model-candidate-selected})
        floor (reduce max 0 (map :candidate-index selected))
        claims (filter #(= :sheet/model-candidate-claimed (:event/type %)) xs)
        cursor (cond (and terminal (get-in terminal [:entry :advanced?]))
                     (inc (get-in terminal [:entry :candidate-index]))
                     terminal (get-in terminal [:entry :candidate-index])
                     (seq claims) (:candidate-index (first claims))
                     :else floor)]
    {:start-index cursor
     :fresh-authority! (when (and (= :subcall (:role owner))
                                  (rm/researcher-campaign ctx (:tick-id owner)))
                         (fn [deadline] (ensure! ctx owner deadline)))
     :initial-usage (when (get-in terminal [:entry :advanced?]) (get-in terminal [:entry :outcome :usage]))
     :prior-attempts (if (get-in terminal [:entry :advanced?])
                       (get-in terminal [:entry :outcome :model-fallback :attempts]) [])
     :request-receipts (mapv :request (filter #(= :sheet/model-request-recorded (:event/type %)) xs))
     :record-request! (fn [request] (command! ctx :sheet/record-model-request (assoc owner :request request)))
     :output-exposed? (boolean (some #(= :sheet/model-candidate-output-exposed (:event/type %)) xs))
     :mark-output-exposed! (fn [index] (command! ctx :sheet/expose-model-candidate-output (assoc owner :candidate-index index)))
     :completed-outcome (when (and terminal (not (get-in terminal [:entry :advanced?])))
                          (get-in terminal [:entry :outcome]))
     :claim! (fn [index _] (command! ctx :sheet/claim-model-candidate (assoc owner :candidate-index index)))
     :record! (fn [entry] (command! ctx :sheet/record-model-candidate (assoc owner :entry entry)))
     :select! (fn [index _] (command! ctx :sheet/select-model-candidate (assoc owner :candidate-index index)))}))

(defn prepare-ordinary-call
  "Retain one effective ordinary model plan under current node ownership.
   resolve-plan! is lazy: exact historical terminal replay never calls it.
   Researcher prime/effect identities do not use this preparation yet."
  [ctx owner authored-config resolve-plan!]
  (let [scope-id (logic/plan-scope-id owner authored-config)
        history (commands/events ctx #{[:tick (:tick-id owner)]} logic/model-call-journal-event-types)
        operation (filterv #(logic/same-operation? owner %) history)
        plans (filterv #(= :sheet/model-plan-retained (:event/type %)) operation)
        legacy (filterv #(not= :sheet/model-plan-retained (:event/type %)) operation)
        retained (first plans)]
    (when (or (> (count plans) 1)
              (and retained
                   (or (not= scope-id (:plan-scope-id retained))
                       (some #(not= (:configuration-id retained) (:configuration-id %)) legacy))))
      (logic/model-call-journal-incompatible! :historical-operation-ambiguous))
    (if (and (nil? retained) (seq legacy))
      (let [configs (set (map :configuration-id legacy))
            terminal (last (filter #(= :sheet/model-candidate-recorded (:event/type %)) legacy))
            entry (:entry terminal)]
        (when-not (= 1 (count configs)) (logic/model-call-journal-incompatible! :historical-operation-ambiguous))
        (when-not (and terminal (false? (:advanced? entry)) (map? (:outcome entry)))
          (logic/model-call-journal-incompatible! :legacy-effective-profile-unretained))
        (let [candidates (logic/legacy-terminal-plan legacy)
              policy (or (get-in entry [:outcome :model-fallback :policy]) :temporary-transport)
              bound (callbacks ctx (assoc owner :configuration-id (first configs)) candidates policy)]
          (when-not (:completed-outcome bound) (logic/model-call-journal-incompatible! :historical-operation-ambiguous))
          {:candidates candidates :policy policy :callbacks bound :historical-terminal? true}))
      (let [retained
            (or retained
                (let [plan (logic/checked-plan (resolve-plan!))
                      config-id (logic/retained-configuration-id plan)
                      result (command! ctx :sheet/retain-model-plan
                                       (assoc owner :configuration-id config-id
                                              :plan-scope-id scope-id :plan plan))]
                  (when (:cognitect.anomalies/category result) (logic/model-call-journal-incompatible! :model-plan-ownership-lost))
                  (or (first (filter #(and (= :sheet/model-plan-retained (:event/type %))
                                          (logic/same-operation? owner %))
                                     (commands/events ctx #{[:tick (:tick-id owner)]} logic/model-call-journal-event-types)))
                      (logic/model-call-journal-incompatible! :model-plan-not-retained))))
            _ (when-not (= scope-id (:plan-scope-id retained))
                (logic/model-call-journal-incompatible! :historical-operation-ambiguous))
            plan (:plan retained)
            _ (when-not (m/validate logic/model-call-journal-retained-plan-schema plan)
                (logic/model-call-journal-incompatible! :retained-profile-version-unsupported))
            _ (when-not (= (:configuration-id retained) (logic/retained-configuration-id plan))
                (logic/model-call-journal-incompatible! :retained-plan-identity-mismatch))
            bound (callbacks ctx (assoc owner :configuration-id (:configuration-id retained))
                             (:candidates plan) (:policy plan))
            replay-only?
            (when-not (:completed-outcome bound)
              (try
                (logic/checked-plan plan)
                false
                (catch clojure.lang.ExceptionInfo error
                  (if (and (= :retained-profile-version-unsupported (:reason (ex-data error)))
                           (some #(and (= (:start-index bound) (:candidate-index %))
                                       (= :completed (:phase %)) (map? (:outcome %)))
                                 (:request-receipts bound)))
                    true
                    (throw error)))))]
        {:candidates (:candidates plan) :policy (:policy plan) :callbacks bound
         :plan plan :configuration-id (:configuration-id retained)
         :replay-only? (boolean replay-only?)}))))

(defn prepare-prime-call
  "Find immutable role data before registry resolution, then bind one actual
   iteration's original logical request. Legacy IDs are never recalculated."
  [ctx owner authored-config iteration effect-claims resolve-plan! request-id!]
  (let [scope-id (logic/prime-plan-scope-id owner authored-config)
        history (filterv #(logic/same-prime-role? owner %)
                         (commands/events ctx #{[:tick (:tick-id owner)]} logic/model-call-journal-event-types))
        plans (filterv #(and (= :sheet/model-plan-retained (:event/type %))
                             (= scope-id (:plan-scope-id %))) history)
        _ (when (> (count plans) 1) (logic/model-call-journal-incompatible! :historical-operation-ambiguous))
        retained (first plans)
        legacy (filterv #(not= :sheet/model-plan-retained (:event/type %)) history)
        iteration-ids (set (map :logical-action-identity
                               (filter #(and (= iteration (:iteration-index %))
                                             (= :provider (:kind %))) effect-claims)))
        associated (filterv #(contains? iteration-ids (:logical-action-id %)) legacy)
        historical-ids (set (map :logical-action-id associated))]
    (when (> (count historical-ids) 1) (logic/model-call-journal-incompatible! :historical-operation-ambiguous))
    (cond
      (and (= 1 (count historical-ids))
           (some #(and (= (first historical-ids) (:logical-action-identity %))
                       (= :completed (:status %))) effect-claims))
      {:logical-action-id (first historical-ids) :historical-effect-only? true}

      (and (:legacy-completed-action-id owner) (nil? retained)
           (empty? historical-ids)
           (every? #{(:legacy-completed-action-id owner)} iteration-ids))
      {:logical-action-id (:legacy-completed-action-id owner) :historical-effect-only? true}

      (and (nil? retained) (or (seq legacy) (seq iteration-ids)))
      (let [logical-id (first historical-ids)
            configs (set (map :configuration-id associated))
            _ (when-not (and logical-id (= 1 (count configs)))
                (logic/model-call-journal-incompatible! :legacy-effective-profile-unretained))
            candidates (logic/legacy-terminal-plan associated)
            policy (or (last (keep #(get-in % [:entry :outcome :model-fallback :policy]) associated))
                       (:fallback-policy authored-config) :temporary-transport)
            bound (callbacks ctx (assoc owner :logical-action-id logical-id
                                       :configuration-id (first configs))
                             candidates policy)
            completed-receipt? (some #(and (= :completed (:phase %))
                                           (= (:start-index bound) (:candidate-index %))
                                           (map? (:outcome %))) (:request-receipts bound))]
        (when-not (or (:completed-outcome bound) completed-receipt?)
          (logic/model-call-journal-incompatible! :legacy-effective-profile-unretained))
        {:logical-action-id logical-id :configuration-id (first configs)
         :candidates candidates :policy policy :callbacks bound
         :replay-only? true :historical-terminal? true})
      :else
      (let [retained
            (or retained
                (let [plan (logic/checked-plan (resolve-plan!))
                      config-id (logic/retained-configuration-id plan)
                      logical-id (request-id! plan config-id)
                      result (command! ctx :sheet/retain-model-plan
                                       (assoc owner :logical-action-id logical-id
                                              :configuration-id config-id :plan-scope-id scope-id :plan plan))]
                  (when (:cognitect.anomalies/category result) (logic/model-call-journal-incompatible! :model-plan-ownership-lost))
                  (or (first (filter #(and (= :sheet/model-plan-retained (:event/type %))
                                          (logic/same-prime-role? owner %) (= scope-id (:plan-scope-id %)))
                                     (commands/events ctx #{[:tick (:tick-id owner)]} logic/model-call-journal-event-types)))
                      (logic/model-call-journal-incompatible! :model-plan-not-retained))))
            plan (:plan retained)
            _ (when-not (m/validate logic/model-call-journal-retained-plan-schema plan)
                (logic/model-call-journal-incompatible! :retained-profile-version-unsupported))
            config-id (:configuration-id retained)
            _ (when-not (= config-id (logic/retained-configuration-id plan))
                (logic/model-call-journal-incompatible! :retained-plan-identity-mismatch))
            computed-id (request-id! plan config-id)
            historical-id (first historical-ids)
            historical-configs (set (map :configuration-id associated))
            _ (when (or (> (count historical-configs) 1)
                        (and (:legacy-completed-action-id owner) (empty? historical-ids)))
                (logic/model-call-journal-incompatible! :historical-operation-ambiguous))
            original-config (or (first historical-configs) config-id)
            logical-id (or historical-id computed-id)
            mismatched? (or (not= logical-id computed-id) (not= original-config config-id))
            candidates (if (= original-config config-id) (:candidates plan) (logic/legacy-terminal-plan associated))
            bound (callbacks ctx (assoc owner :logical-action-id logical-id :configuration-id original-config)
                             candidates (:policy plan))
            completed-receipt? (some #(and (= (:start-index bound) (:candidate-index %))
                                          (= :completed (:phase %)) (map? (:outcome %))) (:request-receipts bound))
            _ (when (and mismatched? (not (:completed-outcome bound)) (not completed-receipt?))
                (logic/model-call-journal-incompatible! :legacy-effective-profile-unretained))
            replay-only?
            (if mismatched? true
            (when-not (:completed-outcome bound)
              (try
                (logic/checked-plan plan)
                false
                (catch clojure.lang.ExceptionInfo error
                  (if (and (= :retained-profile-version-unsupported (:reason (ex-data error)))
                           (some #(and (= (:start-index bound) (:candidate-index %))
                                       (= :completed (:phase %)) (map? (:outcome %)))
                                 (:request-receipts bound)))
                    true
                    (throw error))))))]
        {:logical-action-id logical-id :configuration-id original-config :plan plan
         :candidates candidates :policy (:policy plan) :callbacks bound
         :replay-only? (boolean replay-only?)}))))

(defn- prepare-specialist-call
  "Bind the original inline effect request before provider resolution. The
   parent researcher owns this role; emitted child nodes do not use this seam."
  [ctx owner authored-config resolve-plan!]
  (let [{:keys [topology facts]} (commands/specialist-state ctx owner logic/specialist-event-types)
        scope-id (logic/inline-plan-scope-id (:role-owner topology) authored-config)
        history (filterv #(logic/specialist-role-event? topology %) facts)
        plans (filterv #(and (= :sheet/model-plan-retained (:event/type %))
                             (= scope-id (:plan-scope-id %))) history)
        _ (when (> (count plans) 1) (logic/model-call-journal-incompatible! :historical-operation-ambiguous))
        retained (first plans)
        operation (filterv #(logic/same-operation? owner %) history)
        legacy (filterv #(not= :sheet/model-plan-retained (:event/type %)) operation)
        configurations (set (map :configuration-id legacy))
        _ (when (> (count configurations) 1) (logic/model-call-journal-incompatible! :historical-operation-ambiguous))
        prior-effect? (some #(= (:logical-action-id owner) (:logical-action-identity %))
                            (commands/events ctx #{[:tick (:tick-id owner)]}
                                    #{logic/claim-event-type logic/completion-event-type
                                      logic/indeterminate-event-type}))
        retained-configurations (set (map :configuration-id
                                          (filter #(= :sheet/model-plan-retained (:event/type %)) history)))
        unretained-history? (some #(and (not= :sheet/model-plan-retained (:event/type %))
                                        (not (contains? retained-configurations (:configuration-id %)))) history)
        legacy-config (first configurations)
        historical? (and legacy-config
                         (or (nil? retained) (not= legacy-config (:configuration-id retained))))]
    (if historical?
      (let [candidates (logic/legacy-terminal-plan legacy)
            policy (or (last (keep #(get-in % [:entry :outcome :model-fallback :policy]) legacy))
                       (:fallback-policy authored-config) :temporary-transport)
            bound (callbacks ctx (assoc owner :configuration-id legacy-config) candidates policy)
            physical? (some #(and (= (:start-index bound) (:candidate-index %))
                                  (= :completed (:phase %)) (map? (:outcome %)))
                            (:request-receipts bound))]
        (when-not (or (:completed-outcome bound) physical?)
          (logic/model-call-journal-incompatible! :legacy-effective-profile-unretained))
        {:logical-action-id (:logical-action-id owner) :configuration-id legacy-config
         :candidates candidates :policy policy :callbacks bound
         :replay-only? true :historical-terminal? true})
      (let [_ (when (and (nil? retained)
                         (or prior-effect? unretained-history?))
                (logic/model-call-journal-incompatible! :legacy-effective-profile-unretained))
            retained
            (or retained
                (let [plan (logic/checked-plan (resolve-plan!))
                      result (command! ctx :sheet/retain-model-plan
                                       (assoc owner :configuration-id (logic/retained-configuration-id plan)
                                              :plan-scope-id scope-id :plan plan))]
                  (when (:cognitect.anomalies/category result) (logic/model-call-journal-incompatible! :model-plan-ownership-lost))
                  (or (first (filter #(and (= :sheet/model-plan-retained (:event/type %))
                                          (= scope-id (:plan-scope-id %))
                                          (logic/specialist-role-event? topology %))
                                     (:facts (commands/specialist-state ctx owner logic/specialist-event-types))))
                      (logic/model-call-journal-incompatible! :model-plan-not-retained))))
            plan (:plan retained)
            _ (when-not (m/validate logic/model-call-journal-retained-plan-schema plan)
                (logic/model-call-journal-incompatible! :retained-profile-version-unsupported))
            _ (when-not (= (:configuration-id retained) (logic/retained-configuration-id plan))
                (logic/model-call-journal-incompatible! :retained-plan-identity-mismatch))
            bound (callbacks ctx (assoc owner :configuration-id (:configuration-id retained))
                             (:candidates plan) (:policy plan))
            replay-only?
            (when-not (:completed-outcome bound)
              (try (logic/checked-plan plan) false
                   (catch clojure.lang.ExceptionInfo error
                     (if (and (= :retained-profile-version-unsupported (:reason (ex-data error)))
                              (some #(and (= (:start-index bound) (:candidate-index %))
                                          (= :completed (:phase %)) (map? (:outcome %)))
                                    (:request-receipts bound)))
                       true (throw error)))))]
        {:logical-action-id (:logical-action-id owner) :configuration-id (:configuration-id retained)
         :plan plan :candidates (:candidates plan) :policy (:policy plan) :callbacks bound
         :replay-only? (boolean replay-only?)}))))

(defn prepare-inline-call
  "Bind a checkpointed inline request to its original shared specialist role plan."
  [ctx owner authored-config resolve-plan!]
  (prepare-specialist-call ctx owner authored-config resolve-plan!))

(defn prepare-emitted-call
  "Bind an actual generated child leaf. Known results precede fresh lineage admission."
  [ctx owner authored-config resolve-plan!]
  (let [{:keys [campaign emitted? role-owner]} (commands/specialist-topology ctx owner)
        original (first (filter #(= (:tick-id owner) (:tick-id %))
                                (commands/events ctx #{[:tick (:tick-id owner)]} #{:sheet/tree-tick-started})))
        lineage? (loop [parent (:parent-tick-id original) seen #{}]
                   (cond
                     (= parent (:tick-id role-owner)) true
                     (or (nil? parent) (contains? seen parent)) false
                     :else (let [start (first (filter #(= parent (:tick-id %))
                                                      (commands/events ctx #{[:tick parent]} #{:sheet/tree-tick-started})))]
                             (recur (:parent-tick-id start) (conj seen parent)))))
        admit-fresh! (fn []
                       (when-not (and emitted? lineage? (pos-int? (:ownership-epoch campaign))
                                      (nat-int? (:iteration-index campaign)))
                         (logic/model-call-journal-incompatible! :emitted-campaign-lineage-unavailable)))
        prepared (prepare-specialist-call ctx owner authored-config
                                          #(do (admit-fresh!) (resolve-plan!)))
        callbacks (:callbacks prepared)
        known? (or (:completed-outcome callbacks)
                   (some #(and (= (:start-index callbacks) (:candidate-index %))
                                (= :completed (:phase %)) (map? (:outcome %)))
                         (:request-receipts callbacks)))]
    (when-not known? (admit-fresh!))
    prepared))

;; --- Researcher models ---

(defn prepare-prime
  "Prepare only checkpointed configured prime calls. Other researcher surfaces
   retain their existing request identities and preparation."
  [node provider module inputs options context]
  (let [ctx (or (get-in context [:model-role-context :context]) context)
        call-owner (assoc (logic/owner context :prime "unprepared")
                          :ownership-epoch (:researcher-ownership-epoch context)
                          :legacy-completed-action-id (:legacy-completed-prime-action-id context))
        authored (select-keys node [:provider :model :options :fallback-models :fallback-policy])]
    (prepare-prime-call
     ctx call-owner authored (:researcher-iteration context) (:researcher-effect-claims context)
     #(logic/resolve-plan node (or (:provider node) provider) module
                                  (dissoc options :orc/idempotency-key))
     (fn [plan configuration-id]
       (logic/provider-logical-action-identity
        {:tick-id (:tick-id context) :node-id (:node-id context)
         :iteration-index (:researcher-iteration context)
         :provider (:provider (first (:candidates plan))) :model (:model node)
         :module module :inputs inputs
         :options {:request-version "prime-request-v1" :role :prime :name (:name node)
                   :role-plan-id (logic/prime-plan-scope-id call-owner authored)
                   :configuration-id configuration-id}})))))

(defn prepare-inline
  "Retain checkpointed inline specialist policy under its unchanged effect ID."
  [node provider module options context logical-action-id]
  (prepare-inline-call
   (or (get-in context [:model-role-context :context]) context)
   (assoc (logic/owner context :subcall logical-action-id)
          :ownership-epoch (:researcher-ownership-epoch context))
   (select-keys node [:provider :model :options :fallback-models :fallback-policy])
   #(logic/resolve-plan node (or (:provider node) provider) module
                                (dissoc options :orc/idempotency-key))))

(defn predict
  "Retry a failed model request without replaying code/tools. Candidate receipts
   and successful role selection survive checkpoint/restart."
  [node provider module inputs options context role logical-action-id]
  (let [provider (or (:provider node) provider)
        configured? (contains? node :fallback-models)
        prepared (case role
                   :prime (:prepared-prime-model-call context)
                   :subcall (:prepared-inline-model-call context)
                   nil)
        policy (or (:policy prepared) (:fallback-policy node) :temporary-transport)
        candidates (cond
                     prepared (:candidates prepared)
                     configured? (:candidates (logic/resolve-plan
                                               node provider module (dissoc options :orc/idempotency-key)))
                     :else (logic/resolved-plan node provider module options))
        config-candidates (when-not prepared
                            (logic/resolved-plan node provider module (or (:model-configuration-options context) options)))
        callbacks (or (:callbacks prepared) (when configured?
                    (ai.obney.orc.orc-service.core.todo-processors/callbacks (or (get-in context [:model-role-context :context]) context)
                                       (logic/owner context role logical-action-id) config-candidates policy)))
        invoke! (fn [index candidate]
                  (let [max-retries (or (get-in candidate [:options :max-retries]) 0)
                        receipts (logic/distinct-completed-receipts
                                   prepared (filterv #(and (= index (:candidate-index %)) (= :completed (:phase %)))
                                                     (:request-receipts callbacks)))
                        completed (into {} (map (juxt :attempt-ordinal :outcome) receipts))]
                    (if-let [known-success (logic/exact-replay-success prepared receipts)]
                      known-success
                    (loop [attempt 0 usage nil]
                      (let [known (get completed attempt)
                            _ (when (and prepared (nil? known))
                                (when (:replay-only? prepared)
                                  (throw (ex-info "Historical researcher profile cannot authorize a fresh request"
                                                  {:failure-kind :model-plan-incompatible
                                                   :reason :legacy-effective-profile-unretained})))
                                (let [resolved (llm/preflight-provider!
                                                (:provider candidate) module
                                                (cond-> (:options candidate)
                                                  (:model candidate) (assoc :model (:model candidate))))]
                                  (when-not (and (= (:protocol candidate) (:protocol resolved))
                                                 (= (get-in candidate [:profile :output-mode]) (:output-mode resolved)))
                                    (throw (ex-info "Retained researcher profile cannot be preserved"
                                                    {:failure-kind :model-plan-incompatible
                                                     :reason :effective-profile-cannot-be-preserved})))))
                            now ((or (:campaign-now-ms-fn context) #(System/currentTimeMillis)))
                            remaining (some-> (:execution-deadline-ms context) (- now))
                            reservation (when-not (or known (and remaining (not (pos? remaining))))
                                          (if-let [reserve! (:reserve-provider-call! context)]
                                            (reserve! {:candidate-index index :node-attempt 1
                                                       :iteration-index (:researcher-iteration context)
                                                       :logical-action-identity (str logical-action-id "/candidate/" index)
                                                       :provider-attempt-ordinal (+ (* (or (:effect-attempt-ordinal context) 0)
                                                                                      (inc max-retries)) attempt)})
                                            (when-let [reserve! (:reserve-llm-call! context)] (reserve!))))
                            outcome
                            (cond
                              known known
                              (and remaining (not (pos? remaining)))
                              {:status :timeout :error "Execution deadline exhausted before provider attempt"}
                              reservation
                              (cond-> {:status :timeout
                                       :error (or (:cognitect.anomalies/message reservation)
                                                  "LLM call budget exhausted before provider attempt")}
                                (:failure-kind reservation) (assoc :failure-kind (:failure-kind reservation)))
                              :else
                              (let [receipt {:request-id (random-uuid) :candidate-index index
                                             :attempt-ordinal attempt :provider (:provider candidate)
                                             :requested-model (:model candidate)}
                                    _ (logic/persist! (:record-request! callbacks) (assoc receipt :phase :started))
                                    result (try
                                             (let [configured-timeout (get-in candidate [:options :timeout-ms])
                                                   timeout (cond (and remaining configured-timeout) (min remaining configured-timeout)
                                                                 remaining remaining :else configured-timeout)
                                                   prediction (llm/predict (:provider candidate) module inputs
                                                                (cond-> (merge (:options candidate)
                                                                                (select-keys options [:with-metadata? :validate? :with-provider-evidence?])
                                                                                {:max-retries 0})
                                                                  (:model candidate) (assoc :model (:model candidate))
                                                                  timeout (assoc :timeout-ms (max 1 timeout))))]
                                               {:status :success :result prediction :outputs (:outputs prediction)
                                                :usage (:usage prediction) :model (:model prediction)})
                                             (catch Exception e
                                               (let [timeout? (llm/transport-timeout? e)]
                                                 (cond-> (merge {:status :failure
                                                                 :error (if timeout?
                                                                          "Provider call deadline exceeded"
                                                                          (.getMessage e))
                                                                 :usage (get-in (ex-data e) [:provider-evidence :usage])}
                                                                (select-keys (ex-data e) [:failure-kind :provider-evidence]))
                                                   timeout? (assoc :timeout-kind :provider)))))]
                                (logic/persist! (:record-request! callbacks)
                                          (assoc receipt :phase :completed
                                                 :outcome (logic/durable-outcome result)))
                                result))
                            usage (when (or usage (:usage outcome)) (merge-with + (or usage {}) (or (:usage outcome) {})))
                            replay-next? (and (:replay-only? prepared)
                                              (contains? completed (inc attempt)))
                            _ (when (and (:replay-only? prepared)
                                         (= :transport-failure (:failure-kind outcome))
                                         (some #(> % attempt) (keys completed))
                                         (not replay-next?))
                                (throw (ex-info "Historical model request receipts have an ambiguous attempt gap"
                                                {:failure-kind :model-plan-incompatible
                                                 :reason :historical-operation-ambiguous})))]
                        (if (and (or (< attempt max-retries) replay-next?)
                                 (= :transport-failure (:failure-kind outcome)))
                          (do
                            (when (pos? (long (or (get-in candidate [:options :retry-delay-ms]) 0)))
                              (Thread/sleep (long (min (max 0 (or remaining Long/MAX_VALUE))
                                                       (get-in candidate [:options :retry-delay-ms])))))
                            (recur (inc attempt) usage))
                          (assoc outcome :usage usage)))))))
        outcome (if configured?
                  (logic/run-candidates (merge callbacks {:candidates candidates :policy policy :invoke! invoke!}))
                  (invoke! 0 (first candidates)))]
    (if (= :success (:status outcome))
      (cond-> (assoc (:result outcome) :usage (:usage outcome))
        (:model-fallback outcome) (assoc :model-fallback (:model-fallback outcome)))
      (throw (ex-info (or (:error outcome) "Model request is already in progress")
                      (select-keys outcome [:status :usage :failure-kind :provider-evidence :model-fallback :model-call-in-flight? :timeout-kind]))))))

(defn execute-llm-primitive
  "Execute an LLM primitive from the RLM sandbox.

   Args:
     name - Node name for tracing
     opts - {:instruction \"...\" :writes [:key] :model \"...\" :reads [...]}
     context - {:provider :openrouter :parent-trace-id uuid :blackboard {...} :usage-tracker atom}

   Returns {:outputs {...} :usage {:prompt_tokens N :completion_tokens N :total_tokens N}}

   Sub-LLM calls receive FULL values from :reads. The generated code is responsible
   for managing chunk sizes appropriately. Previews are only used for the code-generating
   LLM to understand variable shapes - actual data processing uses full values.

   U5: For inputs whose blackboard schema carries :field-type :image (or any
   other field-type), the llm module's input field is given :type so that
   llm's build-message-content routes the value as a multimodal content
   block rather than as inline text. This is the Phase-1 mirror of
   executor.clj's build-field behavior for Phase-2 leaf nodes."
  [name opts context]
  (let [opts (logic/inherit (:model-defaults context) opts)
        {:keys [instruction writes model reads]} opts
        {:keys [provider blackboard sandbox-vars usage-tracker reserve-llm-call!
                reserve-provider-call!
                durable-source-required? tick-id node-id researcher-iteration
                generated-code-hash researcher-ownership-epoch
                effect-attempt-ordinal claim-researcher-effect!
                complete-researcher-effect!
                completed-researcher-effects
                encode-researcher-effect-value]} context
        encode-effect-value (or encode-researcher-effect-value identity)
        inputs (reduce (fn [acc k]
                         (cond
                           (contains? sandbox-vars k) (assoc acc k (get sandbox-vars k))
                           (contains? (get blackboard k) :value)
                           (assoc acc k (get-in blackboard [k :value]))
                           :else acc))
                       {}
                       (or reads []))
        module {:inputs (mapv (fn [[k value]]
                                (let [schema (logic/inline-contract name k (get-in blackboard [k :schema]) value :input)
                                      ft (logic/rlm-sandbox-schema-field-type schema)]
                                  (cond-> {:name k
                                           :spec schema}
                                    ft (assoc :type ft))))
                              inputs)
                :outputs (mapv (fn [k]
                                 (let [schema (logic/inline-contract name k (get-in blackboard [k :schema]) nil :output)]
                                   {:name k
                                    :spec schema
                                    :description (:description (m/properties schema))}))
                               writes)
                :instructions instruction}
        llm-options (cond-> (merge {:validate? false :with-metadata? true} (:options opts))
                      model (assoc :model model))
        logical-action-identity
        (when durable-source-required?
          (logic/logical-action-identity
           {:tick-id tick-id
            :node-id node-id
            :iteration-index researcher-iteration
            :generated-code-hash generated-code-hash
            :kind :provider
            :target (encode-effect-value
                     {:provider (or (:provider opts) provider) :model model :name name
                      :fallback-models (:fallback-models opts) :fallback-policy (:fallback-policy opts)})
            :arguments (encode-effect-value
                        {:module module
                         :inputs inputs
                         :options llm-options})}))
        attempt-identity
        (when logical-action-identity
          (logic/attempt-identity
           logical-action-identity
           researcher-ownership-epoch
           effect-attempt-ordinal))
        completed-effect
        (when (and logical-action-identity completed-researcher-effects)
          (get @completed-researcher-effects logical-action-identity))]

    (u/trace :ai.obney.orc.orc-service.core.rlm-sandbox/rlm-llm-primitive
      {:name name :writes writes :model model}
      (try
        (let [call-context (assoc (or (:model-call-context context) context)
                                 :researcher-ownership-epoch researcher-ownership-epoch)
              prepared-inline-model-call
              (when (and logical-action-identity (nil? completed-effect)
                         (contains? opts :fallback-models))
                (prepare-inline opts provider module llm-options
                                                 call-context logical-action-identity))]
        (when (and logical-action-identity (nil? completed-effect))
          (let [claim-result
                (claim-researcher-effect!
                 {:iteration-index researcher-iteration
                  :logical-action-identity logical-action-identity
                  :attempt-identity attempt-identity
                  :attempt-ordinal effect-attempt-ordinal
                  :kind :provider})]
            (when (:cognitect.anomalies/category claim-result)
              (throw (ex-info "Researcher inline-provider claim conflicted"
                              {:claim-result claim-result
                               :logical-action-identity logical-action-identity
                               :attempt-identity attempt-identity})))))
        (let [result (if completed-effect
                       (:result completed-effect)
                       (let [call-context (assoc call-context :prepared-inline-model-call prepared-inline-model-call)
                             logical-id (or logical-action-identity
                                            (logic/logical-id call-context :subcall name module inputs opts llm-options))]
                         (predict opts provider module inputs llm-options
                                                    call-context :subcall logical-id)))
              outputs (or (:outputs result) result)
              usage (:usage result)
              _ (when (and (nil? completed-effect) usage-tracker usage)
                  (swap! usage-tracker
                         (fn [u]
                           {:prompt-tokens (+ (:prompt-tokens u 0)
                                              (or (:prompt-tokens usage) (:prompt_tokens usage) 0))
                            :completion-tokens (+ (:completion-tokens u 0)
                                                  (or (:completion-tokens usage) (:completion_tokens usage) 0))
                            :total-tokens (+ (:total-tokens u 0)
                                             (or (:total-tokens usage) (:total_tokens usage) 0))})))]
          (when (and logical-action-identity (nil? completed-effect))
            (let [completion-result
                  (complete-researcher-effect!
                   {:logical-action-identity logical-action-identity
                    :attempt-identity attempt-identity
                    :result (encode-effect-value result)})]
              (when (:cognitect.anomalies/category completion-result)
                (throw (ex-info "Researcher inline-provider outcome lost ownership"
                                {:completion-result completion-result
                                 :logical-action-identity logical-action-identity
                                 :attempt-identity attempt-identity})))))
          (when (and logical-action-identity
                     (nil? completed-effect)
                     completed-researcher-effects)
            (swap! completed-researcher-effects assoc logical-action-identity
                   {:status :completed :result result}))
          (reduce (fn [acc k]
                    (assoc acc k (get outputs k)))
                  {}
                  writes)))
        (catch Exception e
          (throw (ex-info (str "LLM primitive '" name "' failed: " (.getMessage e))
                          (merge {:name name :writes writes}
                                 (select-keys (ex-data e) [:failure-kind :reason :provider-evidence :model-fallback :status]))
                          e)))))))

;; =============================================================================
;; RLM Sandbox Context
;; =============================================================================

(defn build-rlm-context
  "Build a SCI execution context for RLM mode.

   Extends the base sandbox with BT primitives:
   - llm - Execute an LLM node immediately
   - final! - Capture result with validation
   - get-input - Load input value into variable space
   - store! - Store computed value in sandbox-vars
   - get-var - Retrieve value from sandbox-vars
   - inputs - Map of input previews (metadata only)

   Options:
   - :provider - ORC LLM provider keyword (e.g., :openrouter)
   - :blackboard - Map of key -> {:key, :schema, :value, :version}
   - :declared-writes - Vector of declared write keys for validation
   - :optional-writes - Set of declared top-level write keys that may be omitted
   - :parent-trace-id - UUID for event tracing
   - :call-tool-fn - Optional MCP tool function
   - :mcp-tools - Optional vector of MCP tool names
   - :browser-tools - Optional vector of browser tool names
   - :sandbox-vars - Optional existing sandbox-vars atom (for persistence across iterations)
   - :usage-tracker - Optional atom for aggregating sub-LLM token usage
   - :recursive? - When true, enables R-2 drill-down primitives (tree-detail, etc.)
   - :event-store - Required when :recursive? true. Used by drill-down primitives.
   - :tenant-id - Optional; passed through to the event-store read"
  [{:keys [provider blackboard declared-writes optional-writes parent-trace-id
           call-tool-fn mcp-tools browser-tools sandbox-vars usage-tracker
           recursive? event-store tenant-id cache
           sheet-id tick-id command-registry reserve-llm-call!
           reserve-provider-call!
           durable-source-required? node-id researcher-iteration
           generated-code-hash researcher-ownership-epoch
           effect-attempt-ordinal claim-researcher-effect!
           complete-researcher-effect!
           completed-researcher-effects
           encode-researcher-effect-value] :as context}]
  (let [
        final-output (atom nil)

        sci-ctx* (atom nil)

        sandbox-vars (or sandbox-vars (atom {}))

        usage-tracker (or usage-tracker (atom {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}))

        exec-context {:provider provider
                      :model-defaults (:model-defaults context)
                      :model-call-context (:model-call-context context)
                      :blackboard blackboard
                      :parent-trace-id parent-trace-id
                      :usage-tracker usage-tracker
                      :reserve-llm-call! reserve-llm-call!
                      :reserve-provider-call! reserve-provider-call!
                      :durable-source-required? durable-source-required?
                      :tick-id tick-id
                      :node-id node-id
                      :researcher-iteration researcher-iteration
                      :generated-code-hash generated-code-hash
                      :researcher-ownership-epoch researcher-ownership-epoch
                      :effect-attempt-ordinal effect-attempt-ordinal
                      :encode-researcher-effect-value
                      encode-researcher-effect-value
                      :claim-researcher-effect! claim-researcher-effect!
                      :complete-researcher-effect! complete-researcher-effect!
                      :completed-researcher-effects
                      completed-researcher-effects}

        inputs-preview (logic/build-inputs-preview blackboard)

        llm-fn (fn [name & args]
                 (let [opts (if (= 1 (count args))
                              (first args)
                              (apply hash-map args))
                       ctx (assoc exec-context :sandbox-vars @sandbox-vars)
                       result (execute-llm-primitive name opts ctx)]
                   (swap! sandbox-vars merge result)
                   result))

        final!-fn (fn [output]
                    (reset! final-output nil)
                    (let [candidate (logic/validate-final! output declared-writes optional-writes)
                          result (logic/validate-outputs
                                   blackboard {:status :success :outputs candidate} false)]
                      (when (= :failure (:status result))
                        (throw (ex-info (:error result)
                                        {:failure-kind :final-output-schema-invalid
                                         :rejected-write-keys (vec (keys (:rejected-writes result)))})))
                      (reset! final-output (:outputs result))
                      (str "FINAL_ANSWER: " (pr-str (:outputs result)))))

        get-input-fn (fn [k]
                       (let [v (get-in blackboard [k :value])]
                         (swap! sandbox-vars assoc k v)
                         v))

        safe-clojure-core '[+ - * / mod quot rem
                           = not= < > <= >= compare
                           str pr-str prn-str println print
                           inc dec min max abs
                           first rest next last butlast
                           cons conj concat into
                           map filter remove reduce
                           take drop take-while drop-while
                           partition partition-all partition-by
                           sort sort-by reverse shuffle
                           get get-in assoc assoc-in dissoc update update-in
                           select-keys merge merge-with
                           keys vals contains? find
                           count empty? not-empty seq vec set list
                           apply comp partial juxt
                           identity constantly
                           some every? not-any? not-every?
                           group-by frequencies
                           zipmap interleave interpose
                           repeat range iterate
                           true? false? nil? some? boolean
                           keyword keyword? symbol symbol? string? number? integer? float? map? vector? set? list? coll? seq? fn?
                           name namespace
                           re-find re-matches re-seq
                           subs format
                           type class]
        core-publics (ns-publics 'clojure.core)
        safe-core (select-keys core-publics safe-clojure-core)

        sequence-fn (fn [name & children]
                      (let [results (reduce (fn [acc child]
                                              (if (map? child)
                                                (merge acc child)
                                                acc))
                                            {}
                                            children)]
                        (swap! sandbox-vars merge results)
                        results))

        parallel-fn (fn [name & children]
                      (let [results (reduce (fn [acc child]
                                              (if (map? child)
                                                (merge acc child)
                                                acc))
                                            {}
                                            children)]
                        (swap! sandbox-vars merge results)
                        results))

        map-each-fn (fn [name & args]
                      (cond
                        (and (keyword? (first args))
                             (= :as (second args))
                             (keyword? (nth args 2))
                             (fn? (nth args 3)))
                        (let [coll-key (first args)
                              item-name (nth args 2)
                              body-fn (nth args 3)
                              coll (get @sandbox-vars coll-key)]
                          (vec
                           (for [item coll]
                             (do
                               (swap! sandbox-vars assoc item-name item)
                               (body-fn)))))

                        (and (coll? (first args))
                             (fn? (second args)))
                        (let [coll (first args)
                              f (second args)]
                          (vec (map f coll)))

                        :else
                        (throw (ex-info (str "Invalid map-each syntax. Use: "
                                             "(map-each \"name\" :key :as :item-name (fn [] ...)) or "
                                             "(map-each \"name\" coll f)")
                                        {:args args}))))

        fallback-fn (fn [name & children]
                      (loop [remaining children]
                        (if (empty? remaining)
                          nil
                          (let [child (first remaining)]
                            (if (and (some? child) (not (instance? Exception child)))
                              (do
                                (when (map? child)
                                  (swap! sandbox-vars merge child))
                                child)
                              (recur (rest remaining)))))))

        condition-fn (fn [name pred then-branch else-branch]
                       (let [result (if pred then-branch else-branch)]
                         (when (map? result)
                           (swap! sandbox-vars merge result))
                         result))

        code-fn (fn [name & args]
                  (let [opts (apply hash-map args)
                        writes (:writes opts)
                        body-result (:body opts)
                        result (if (and (= 1 (count writes)) (not (map? body-result)))
                                 {(first writes) body-result}
                                 (if (map? body-result)
                                   body-result
                                   {(first writes) body-result}))]
                    (swap! sandbox-vars merge result)
                    result))

        store!-fn (fn [k v]
                    (swap! sandbox-vars assoc k v)
                    v)

        get-var-fn (fn [k]
                     (get @sandbox-vars k))

        list-vars-fn (fn []
                       (reduce-kv
                        (fn [acc k v]
                          (assoc acc k (logic/preview-value v)))
                        {}
                        @sandbox-vars))

        emit-tree!-fn (fn [tree]
                        (let [live-inline-closure?
                              (boolean
                               (some (fn [node]
                                       (and (map-entry? node)
                                            (= :fn (key node))
                                            (fn? (val node))))
                                     (tree-seq coll? seq tree)))]
                        (when (and durable-source-required? live-inline-closure?)
                          (throw
                           (ex-info
                            logic/quoted-inline-function-source-message
                            {:requirement :quoted-inline-function-source})))
                        (logic/validate-emitted-tree! tree)
                        (let [executable-tree
                              (walk/postwalk
                               (fn [node]
                                 (if (and (map-entry? node)
                                          (= :fn (key node))
                                          (seq? (val node))
                                          (#{'fn 'fn*} (first (val node))))
                                   [:fn (if durable-source-required?
                                          (logic/prepare-function (val node) @sci-ctx*)
                                          (sci/eval-form @sci-ctx* (val node)))]
                                   node))
                               tree)
                              canonical (logic/rlm-dsl->orc-dsl executable-tree)]
                          (swap! sandbox-vars
                                 (fn [vars]
                                   (cond-> (assoc vars :generated-tree canonical)
                                     (not live-inline-closure?)
                                     (assoc :generated-tree-raw tree
                                            :generated-tree-source (pr-str tree))

                                     live-inline-closure?
                                     (dissoc :generated-tree-raw
                                             :generated-tree-source))))
                          canonical)))

        command-context-ready?
        (and (some? event-store)
             (some? command-registry)
             (some? sheet-id)
             (some? tick-id))
        mint-behavior-available?
        (or (nil? command-registry)
            (contains? command-registry :ontology/mint-behavioral-subtree))
        mint-behavior!-fn
        (fn [name body & kw-args]
          (when-not command-context-ready?
            (throw (ex-info
                     (str "mint-behavior! requires a command context "
                          "(needs :event-store + :command-registry + :sheet-id + :tick-id "
                          "in build-rlm-context opts). The sandbox was built without one — "
                          "running in dry-run/test mode.")
                     {:missing (cond-> []
                                 (nil? event-store) (conj :event-store)
                                 (nil? command-registry) (conj :command-registry)
                                 (nil? sheet-id) (conj :sheet-id)
                                 (nil? tick-id) (conj :tick-id))})))
          (let [opts-map (apply hash-map kw-args)
                parent (:parent opts-map)
                parent-as-uuid (when parent
                                 (cond
                                   (uuid? parent) parent
                                   (string? parent) (try
                                                      (java.util.UUID/fromString parent)
                                                      (catch Exception _ parent))
                                   :else parent))
                logical-action-identity
                (when durable-source-required?
                  (logic/logical-action-identity
                   {:tick-id tick-id
                    :node-id node-id
                    :iteration-index researcher-iteration
                    :generated-code-hash generated-code-hash
                    :kind :behavior-mint
                    :target name
                    :arguments ((or encode-researcher-effect-value identity)
                                {:name name
                                 :body body
                                 :parent parent-as-uuid})}))
                attempt-identity
                (when logical-action-identity
                  (logic/attempt-identity
                   logical-action-identity
                   researcher-ownership-epoch
                   effect-attempt-ordinal))
                completed-effect
                (when (and logical-action-identity completed-researcher-effects)
                  (get @completed-researcher-effects logical-action-identity))
                claim-result
                (when (and logical-action-identity (nil? completed-effect))
                  (claim-researcher-effect!
                   {:iteration-index researcher-iteration
                    :logical-action-identity logical-action-identity
                    :attempt-identity attempt-identity
                    :attempt-ordinal effect-attempt-ordinal
                    :kind :behavior-mint}))
                _ (when (:cognitect.anomalies/category claim-result)
                    (throw (ex-info "Researcher behavior-mint claim conflicted"
                                    {:claim-result claim-result
                                     :logical-action-identity logical-action-identity
                                     :attempt-identity attempt-identity})))
                cmd-result (when-not completed-effect
                             (cp/process-command
                             (assoc
                               (cond-> {:event-store event-store
                                        :command-registry command-registry
                                        :sheet-id sheet-id
                                        :tick-id tick-id}
                                 tenant-id (assoc :tenant-id tenant-id))
                               :command
                               (cond-> {:command/name :ontology/mint-behavioral-subtree
                                        :command/id (random-uuid)
                                        :command/timestamp (time/now)
                                        :name name
                                        :body body
                                        :provenance :agent-minted
                                        :minted-by-sheet-id sheet-id
                                        :minted-by-tick-id tick-id}
                                 logical-action-identity
                                 (assoc :logical-action-identity logical-action-identity
                                        :attempt-identity attempt-identity
                                        :researcher-iteration researcher-iteration)
                                 (some? effect-attempt-ordinal)
                                 (assoc :attempt-ordinal effect-attempt-ordinal)
                                 (some? researcher-ownership-epoch)
                                 (assoc :ownership-epoch researcher-ownership-epoch)
                                 parent-as-uuid (assoc :parent-behavior parent-as-uuid)))))
                mint-conflict?
                (= :cognitect.anomalies/conflict
                   (:cognitect.anomalies/category cmd-result))
                recovered-minted-event
                (when (and logical-action-identity mint-conflict?)
                  ((integrations/capability :ontology :get-behavior-mint-by-logical-action)
                   {:event-store event-store
                    :tenant-id tenant-id}
                   logical-action-identity))
                minted-event
                (when-not completed-effect
                  (or (->> (:command-result/events cmd-result)
                           (filter #(= :ontology/behavioral-subtree-minted
                                       (:event/type %)))
                           first)
                      recovered-minted-event))
                _ (when (and (nil? completed-effect)
                             (nil? minted-event))
                    (throw (ex-info
                             (str "mint-behavior! command rejected: "
                                  (or (:cognitect.anomalies/message cmd-result)
                                      "no events returned by command processor")
                                  (when-let [explain (:error/explain cmd-result)]
                                    (str " — explain: " (pr-str explain))))
                             {:cmd-result cmd-result
                              :mint-name name
                              :mint-body body})))
                target-id (if completed-effect
                            (:result completed-effect)
                            (:target-id minted-event))
                completion-result
                (when (and logical-action-identity (nil? completed-effect))
                  (complete-researcher-effect!
                   {:logical-action-identity logical-action-identity
                    :attempt-identity attempt-identity
                    :result (str target-id)}))
                _ (when (:cognitect.anomalies/category completion-result)
                    (throw (ex-info "Researcher behavior-mint outcome lost ownership"
                                    {:completion-result completion-result
                                     :logical-action-identity logical-action-identity
                                     :attempt-identity attempt-identity})))
                _ (when (and logical-action-identity
                             (nil? completed-effect)
                             completed-researcher-effects)
                    (swap! completed-researcher-effects
                           assoc logical-action-identity
                           {:status :completed :result (str target-id)}))]
            (str target-id)))

        get-description-fn
        (let [coerce-uuid (fn [v]
                            (cond
                              (uuid? v) v
                              (string? v) (try (java.util.UUID/fromString v)
                                               (catch Exception _ v))
                              :else v))
              ctx (cond-> {:event-store event-store}
                    tenant-id (assoc :tenant-id tenant-id)
                    cache     (assoc :cache cache))]
          (fn
            ([target-id]
             (when event-store
               ((integrations/capability :ontology :get-description)
                ctx :tree-fingerprint (coerce-uuid target-id))))
            ([granularity target-id]
             (when event-store
               ((integrations/capability :ontology :get-description)
                ctx granularity (coerce-uuid target-id))))))

        find-tree-result (fn [tick-id]
                           (let [entries (or (:tree-results @sandbox-vars) [])]
                             (if tick-id
                               (first (filter #(= tick-id (:tick-id %)) entries))
                               (last entries))))
        read-tick-events (fn [tick-id]
                           (when tick-id
                             (into []
                                   (map (fn [event]
                                          (if (= :sheet/execution-value-referenced
                                                 (:event/type event))
                                            (assoc event
                                                   :event/type :sheet/execution-value-written
                                                   :value (rm/resolve-source
                                                           context tenant-id
                                                           (:source event)))
                                            event)))
                                   (rm/read-tick-events context tenant-id tick-id))))
        tree-detail-fn (fn
                         ([] (when-let [entry (find-tree-result nil)]
                               (logic/tree-detail-from-events
                                (read-tick-events (:tick-id entry))
                                (:tree-raw entry))))
                         ([tick-id]
                          (when-let [entry (find-tree-result tick-id)]
                            (logic/tree-detail-from-events
                             (read-tick-events (:tick-id entry))
                             (:tree-raw entry)))))
        tree-trajectory-fn (fn
                             ([] (when-let [entry (find-tree-result nil)]
                                   (logic/tree-trajectory-from-events
                                    (read-tick-events (:tick-id entry)))))
                             ([tick-id]
                              (when-let [entry (find-tree-result tick-id)]
                                (logic/tree-trajectory-from-events
                                 (read-tick-events (:tick-id entry))))))
        tree-failures-fn (fn []
                           (when-let [entry (find-tree-result nil)]
                             (logic/tree-failures-from-events
                              (read-tick-events (:tick-id entry)))))
        node-output-fn (fn [node-id]
                         (when-let [entry (find-tree-result nil)]
                           (logic/node-output-from-events
                            (read-tick-events (:tick-id entry))
                            node-id)))
        node-input-profile-fn (fn [node-id]
                                (when-let [entry (find-tree-result nil)]
                                  (logic/node-input-profile-from-events
                                   (read-tick-events (:tick-id entry))
                                   node-id)))
        drill-bindings (when recursive?
                         {'tree-detail tree-detail-fn
                          'tree-trajectory tree-trajectory-fn
                          'tree-failures tree-failures-fn
                          'node-output node-output-fn
                          'node-input-profile node-input-profile-fn})

        rlm-bindings (merge (cond-> {'llm llm-fn
                                     'final! final!-fn
                                     'get-input get-input-fn
                                     'store! store!-fn
                                     'get-var get-var-fn
                                     'list-vars list-vars-fn
                                     'inputs inputs-preview
                                     'sequence sequence-fn
                                     'parallel parallel-fn
                                     'map-each map-each-fn
                                     'fallback fallback-fn
                                     'condition condition-fn
                                     'code code-fn
                                     'emit-tree! emit-tree!-fn
                                     'get-description get-description-fn}
                              mint-behavior-available?
                              (assoc 'mint-behavior! mint-behavior!-fn))
                            drill-bindings)

        {tool-flat :flat tool-namespaces :namespaces}
        (logic/build-tool-bindings call-tool-fn mcp-tools)

        all-bindings (merge rlm-bindings tool-flat)

        sci-ctx (sci/init
                 {:namespaces (merge {'clojure.core safe-core
                                      'user (merge rlm-bindings tool-flat)}
                                     tool-namespaces)
                  :bindings all-bindings})
        _ (reset! sci-ctx* sci-ctx)]

    {:sci-ctx sci-ctx
     :final-output final-output
     :sandbox-vars sandbox-vars
     :usage-tracker usage-tracker}))

;; --- Executor ---

(defn- completed-child-values [context tick-id nodes events]
  (reduce (fn [result completion]
            (let [writes (rm/resolve-writes context (:tenant-id context) tick-id completion)
                  owner (assoc (select-keys completion [:event/id :node-id :tick-id :status])
                               :exec-context (second (logic/execution-key completion)))
                  protected (select-keys writes logic/researcher-control-keys)
                  writes (apply dissoc writes logic/researcher-control-keys)]
              (cond->
                (if (logic/scoped-child-node? nodes (:node-id completion))
                  (update result :scoped-outputs into
                          (map (fn [[k v]] {:key k :owner owner :preview (logic/compute-output-preview v)}) writes))
                  (-> result
                      (update :values merge writes)
                      (update :owners merge (zipmap (keys writes) (repeat owner)))))
                (seq protected) (update :protected-writes into (keys protected)))))
          {:values {} :owners {} :scoped-outputs [] :protected-writes []}
          (filter #(and (= :sheet/node-execution-completed (:event/type %))
                        (contains? #{:success :partial} (:status %))) events)))

(defn- execute-ai-candidate
  "Execute a leaf node using ORC LLM AI.

   Args:
     node - The leaf node map
     blackboard - Map of key -> {:key, :type, :value, :version}
     provider - ORC LLM provider keyword (e.g., :openrouter, :anthropic)
     options - Optional ORC LLM options map (can include :model, :max-retries, :retry-delay-ms)

   Returns:
     {:status :success/:failure
      :outputs {string-key value} - outputs to write to blackboard
      :error string?             - error message if failed
      :duration-ms int           - execution time
      :usage {:prompt-tokens N :completion-tokens N :total-tokens N} - token usage (when available)
      :model string?}            - model used (when available)

   OUTPUT FLATTENING:
   This function flattens nested :map schemas into separate output fields
   (matching Python DSPy's approach), then reassembles them back into
   nested structure for the blackboard."
  [node blackboard provider & {:keys [options stream] :or {options {}}}]
  (let [start-time (System/currentTimeMillis)
        optional-writes (set (:optional-writes options))
        options (dissoc options :optional-writes)
        module (logic/build-module node blackboard)
        inputs (logic/gather-inputs node blackboard)
        output-mapping (:output-mapping module)
        llm-module (dissoc module :output-mapping)
        internal-option-keys #{:execution-deadline-ms :reserve-llm-call! :fresh-model-authority!
                               :reserve-provider-attempt! :provider-reservation-context
                               :tick-id :node-attempt :max-node-attempts
                               :exec-context :start-provider-attempt! :complete-provider-attempt!
                               :max-retries :retry-delay-ms :validate? :unparseable-is-schema-error?
                               :model-fallback-callbacks :model-role-context :model-candidate-index :mark-output-exposed!
                               :model-request-callbacks :orc/retained-model-profile :orc/model-replay-only?}
        llm-options (merge {:validate? false
                               :with-metadata? true
                               :with-provider-evidence? true
                               :use-function-calling? false}
                              (apply dissoc options internal-option-keys)
                              (when (:model node) {:model (:model node)}))
        max-retries (get options :max-retries 1)
        retry-delay-ms (get options :retry-delay-ms 500)
        unparseable-is-schema-error? (boolean (:unparseable-is-schema-error? options))

        stream-profile (when (and stream
                                  (or (:fields? stream) (:raw? stream))
                                  (not (:use-function-calling? llm-options)))
                         (if-let [retained (:orc/retained-model-profile options)]
                           {:output-mode (get-in retained [:profile :output-mode])}
                           (try
                             (llm/preflight-provider! provider llm-module llm-options)
                             (catch Exception error {:error error}))))
        predict-stream-v2 (when (and stream-profile
                                     (not (:error stream-profile))
                                     (not (contains? #{:json-schema :function-calling}
                                                     (:output-mode stream-profile))))
                            llm/predict-stream-v2)
        output-exposed? (atom false)
        mark-output-exposed! (:mark-output-exposed! options)
        expose! (fn []
                  (when (compare-and-set! output-exposed? false true)
                    (when mark-output-exposed!
                      (let [r (mark-output-exposed!)]
                        (when (:cognitect.anomalies/category r)
                          (throw (ex-info "Stream exposure could not be persisted"
                                          {:failure-kind :observation-persistence-failed})))))))
        emit-delta! (when predict-stream-v2
                      (let [base (cond-> (select-keys stream [:sheet-id :node-id])
                                   (:map-each stream) (assoc :map-each (:map-each stream)))]
                        (fn [attempt m]
                          (expose!)
                          (logic/emit! (:tick-id stream)
                                           (merge base {:attempt attempt} m)))))

        try-once-streaming
        (fn [attempt attempt-options]
          (let [ch (predict-stream-v2 provider llm-module inputs attempt-options)]
            (loop [terminal nil]
              (if-let [ev (async/<!! ch)]
                (case (:orc/event ev)
                  :delta (do (when (:raw? stream)
                               (emit-delta! attempt
                                            {:orc.stream/type :llm-raw-delta
                                             :text (:text ev)}))
                             (recur terminal))
                  :fields (do (when (:fields? stream)
                                (emit-delta! attempt
                                             {:orc.stream/type :llm-fields
                                              :fields (logic/reassemble-flattened-outputs
                                                       (:fields ev) output-mapping)}))
                              (recur terminal))
                  :error (recur (let [{:keys [failure-kind provider-evidence]} (:error ev)]
                                  (cond-> {:error (str "LLM stream error: " (pr-str (:error ev)))}
                                    failure-kind (assoc :failure-kind failure-kind)
                                    provider-evidence
                                    (assoc :provider-evidence
                                           (update provider-evidence :usage logic/normalize-usage)))))
                  :final (let [outputs (logic/reassemble-flattened-outputs (:outputs ev) output-mapping true)]
                           (when (:fields? stream)
                             (emit-delta! attempt
                                          {:orc.stream/type :llm-fields
                                           :fields outputs
                                           :final? true}))
                           (recur {:outputs outputs
                                   :usage (logic/normalize-usage (:usage ev))
                                   :model (or (:model ev) (:model node))
                                   :resolved-model (:model ev)
                                   :raw-response (:raw-response ev)}))
                  (recur terminal))
                (assoc (or terminal {:error "LLM stream ended without a final result"})
                       :output-exposed? @output-exposed?)))))

        try-once (fn [attempt attempt-options]
                   (when-let [error (:error stream-profile)] (throw error))
                   (if predict-stream-v2
                     (try-once-streaming attempt attempt-options)
                     (let [result (llm/predict provider llm-module inputs attempt-options)
                           raw-outputs (if (logic/prediction-envelope? result)
                                         (:outputs result)
                                         result)
                           outputs (logic/reassemble-flattened-outputs raw-outputs output-mapping true)]
                       {:outputs outputs
                        :usage (logic/normalize-usage (:usage result))
                        :model (or (:model result) (:model node))
                        :resolved-model (when (logic/prediction-envelope? result) (:model result))
                        :provider-evidence
                        (some-> (:provider-evidence result)
                                (update :usage logic/normalize-usage))
                        :raw-response (:raw-response result)
                        :decisions (:decisions result)
                        :provider-observations (:provider-observations result)})))

        backoff-for (fn [attempt]
                      (if (sequential? retry-delay-ms)
                        (nth retry-delay-ms (min attempt (dec (count retry-delay-ms))))
                        retry-delay-ms))
        candidate-index (:model-candidate-index options)
        request-callbacks (:model-request-callbacks options)
        retained-profile (:orc/retained-model-profile options)
        observation-context (get-in options [:model-role-context :context])
        observation-owner (select-keys (get-in options [:model-role-context :owner])
                                       [:sheet-id :tick-id :node-id :node-start-id :exec-context])
        receipts (filter #(= candidate-index (:candidate-index %)) (:request-receipts request-callbacks))
        completed-receipts (into {} (for [receipt receipts :when (= :completed (:phase receipt))]
                                      [(:attempt-ordinal receipt) receipt]))
        completed-requests (into {} (for [receipt receipts :when (= :completed (:phase receipt))]
                                      [(:attempt-ordinal receipt) (:outcome receipt)]))
        replay (when (seq receipts)
                 (logic/select-response receipts
                   #(logic/validate-ai-prediction node blackboard optional-writes unparseable-is-schema-error? %)))
        record-request! (fn [receipt]
                          (when-let [record! (:record-request! request-callbacks)]
                            (when (:cognitect.anomalies/category (record! receipt))
                              :request-receipt-not-retained)))
        start-provider-attempt! (:start-provider-attempt! options)
        complete-provider-attempt! (:complete-provider-attempt! options)
        deadline-ms (:execution-deadline-ms options)
        reserve-call! (:reserve-llm-call! options)
        tick-id (:tick-id options)
        node-attempt (or (:node-attempt options) 1)
        max-node-attempts (or (:max-node-attempts options) 1)
        reserve-provider-attempt! (:reserve-provider-attempt! options)
        provider-reservation-context (:provider-reservation-context options)
        provider-action-id
        (when reserve-provider-attempt!
          (logic/provider-logical-action-identity
           {:tick-id (:tick-id provider-reservation-context)
            :node-id (:node-id provider-reservation-context)
            :iteration-index (:iteration-index provider-reservation-context)
            :provider provider
            :model (:model node)
            :module llm-module
            :inputs inputs
            :options llm-options}))
        prepare-attempt
        (fn [attempt]
          (let [initial-remaining (logic/remaining-ms deadline-ms)
                physical-ordinal (+ (* (dec node-attempt) (inc max-retries)) attempt)
                authority (when (or (nil? initial-remaining) (pos? initial-remaining))
                            (when-let [ensure! (or (:fresh-authority! request-callbacks) (:fresh-model-authority! options))]
                              (ensure! deadline-ms)))
                remaining (logic/remaining-ms deadline-ms)
                admission (when (and reserve-provider-attempt!
                                     (or (nil? remaining) (pos? remaining)))
                            (reserve-provider-attempt!
                              (cond-> {:logical-action-identity provider-action-id
                                       :provider-attempt-ordinal physical-ordinal
                                       :candidate-index (or (:model-candidate-index options) 0)
                                       :node-attempt node-attempt}
                                authority (assoc :emitted-model-authority authority))))]
            (cond
              (and remaining (not (pos? remaining)))
              {:timeout-error "Execution deadline exhausted before provider attempt"}

              admission
              {:timeout-error (or (:cognitect.anomalies/message admission)
                                  "Durable provider-call reservation rejected before provider attempt")
               :admission-failure-kind (:failure-kind admission)}

              (and (nil? reserve-provider-attempt!)
                   reserve-call! (reserve-call!))
              {:timeout-error "LLM call budget exhausted before provider attempt"}

              :else
              (let [configured-timeout (:timeout-ms llm-options)
                    bounded-timeout (cond
                                      (and configured-timeout remaining)
                                      (max 1 (min (long configured-timeout) remaining))
                                      remaining (max 1 remaining)
                                      :else configured-timeout)
                    attempt-options (cond-> llm-options
                                      bounded-timeout (assoc :timeout-ms bounded-timeout))
                    state {:provider-attempt (inc attempt)
                           :max-provider-attempts (inc max-retries)
                           :node-attempt node-attempt
                           :max-node-attempts max-node-attempts
                           :execution-deadline-ms deadline-ms
                           :execution-budget-remaining-ms remaining
                           :provider-timeout-ms bounded-timeout
                           :exec-context (or (:exec-context options) {})}]
                (logic/record-attempt! tick-id (:id node) state)
                {:options attempt-options}))))]

    (cond
      (:failure replay) (:failure replay)

      (:receipt replay)
      (let [receipt (:receipt replay)
            prediction (:outcome receipt)
            result (:schema-result (logic/validate-ai-prediction node blackboard optional-writes
                                     unparseable-is-schema-error? prediction))
            observation-error
            (some (fn [completed]
                    (when (and observation-context (:observation-owner completed) (:provider-outcome completed))
                      (logic/persist-provider-attempt
                        #(ai.obney.orc.orc-service.core.todo-processors/complete-provider-attempt! observation-context %)
                        (assoc (:observation-owner completed) :outcome (:provider-outcome completed)))))
                  (:completed replay))]
        (if observation-error
          {:status :failure :retryable? false :failure-kind :model-plan-incompatible
           :error "Provider attempt outcome could not be persisted; dispatch will not be repeated"
           :usage (:usage replay)}
          (merge (select-keys prediction [:model :resolved-model :raw-response :decisions :provider-evidence])
                 result
                 {:duration-ms (- (System/currentTimeMillis) start-time) :usage (:usage replay)
                  :model-fallback {:recovery-accounting (:accounting replay)}})))

      :else
    (loop [attempt 0
           accumulated-usage nil]
      (let [known (get completed-requests attempt)
            known-receipt (get completed-receipts attempt)
            profile-error
            (when (and retained-profile (not known))
              (try
                (when (:orc/model-replay-only? options)
                  (throw (ex-info "Model plan cannot be resumed [retained-profile-version-unsupported]"
                                  {:failure-kind :model-plan-incompatible
                                   :reason :retained-profile-version-unsupported})))
                (let [resolved (llm/preflight-provider! provider llm-module
                                                       (assoc llm-options :orc/explicit-provider? true))]
                  (when-not (and (= (:provider retained-profile) (:provider resolved))
                                 (= (:model retained-profile) (:model resolved))
                                 (= (:protocol retained-profile) (:protocol resolved))
                                 (or (= :decision (:protocol resolved))
                                     (= (get-in retained-profile [:profile :output-mode]) (:output-mode resolved))))
                    (throw (ex-info "Model plan cannot be resumed [retained-profile-cannot-be-preserved]"
                                    {:failure-kind :model-plan-incompatible
                                     :reason :retained-profile-cannot-be-preserved}))))
                nil
                (catch Exception error
                  (if (:failure-kind (ex-data error)) error (throw error)))))
            replayed-observation-error
            (when (and known observation-context (:observation-owner known-receipt)
                       (:provider-outcome known-receipt))
              (logic/persist-provider-attempt
               #(ai.obney.orc.orc-service.core.todo-processors/complete-provider-attempt! observation-context %)
               (assoc (:observation-owner known-receipt) :outcome (:provider-outcome known-receipt))))
            {:keys [options timeout-error admission-failure-kind]}
            (when-not (or known profile-error) (prepare-attempt attempt))
            invocation (when-not (or known profile-error timeout-error)
                         (cond-> {:invocation-id (random-uuid)
                                   :node-attempt node-attempt :provider-attempt (inc attempt)}
                           (some? candidate-index)
                           (assoc :candidate-index candidate-index
                                  :requested-provider provider :requested-model (:model node))))
            request (when invocation
                      (cond-> {:request-id (random-uuid) :candidate-index (or candidate-index 0)
                               :attempt-ordinal attempt :provider provider :requested-model (:model node)}
                        (seq observation-owner) (assoc :observation-owner (merge observation-owner invocation))))
            request-start-error (when request (record-request! (assoc request :phase :started)))
            start-error (when (and invocation (not request-start-error))
                          (logic/persist-provider-attempt start-provider-attempt! invocation))
            prediction
            (cond
              known (if (and (:provider-outcome known-receipt)
                             (not= :success (get-in known-receipt [:provider-outcome :status])))
                      (merge known {:error (or (:error known) "Recorded provider response was rejected")}
                             (select-keys (:provider-outcome known-receipt) [:failure-kind :provider-evidence]))
                      known)
              profile-error (merge {:error (.getMessage ^Exception profile-error)}
                                   (select-keys (ex-data profile-error) [:failure-kind :provider-evidence]))
              timeout-error {:error timeout-error :budget-timeout? true}
              request-start-error {:error "Model request start could not be retained"
                                   :failure-kind :model-plan-incompatible}
              start-error {:error "Provider attempt start could not be persisted"}
              :else
              (try
                (try-once attempt options)
                (catch Exception e
                  (let [{:keys [failure-kind provider-evidence provider-observations]} (ex-data e)
                        provider-evidence (some-> provider-evidence (update :usage logic/normalize-usage))]
                    (cond-> {:error (.getMessage e)}
                      provider-observations (assoc :provider-observations provider-observations)
                      failure-kind (assoc :failure-kind failure-kind)
                      provider-evidence
                      (assoc :provider-evidence provider-evidence
                             :usage (logic/normalize-usage (:usage provider-evidence))
                             :model (or (:model provider-evidence) (:model node))
                             :resolved-model (:model provider-evidence)))))))
            {:keys [outputs usage model resolved-model error raw-response failure-kind provider-evidence
                    decisions provider-observations]}
            prediction
            budget-timeout? (boolean timeout-error)
            {:keys [outputs missing-output-keys unparseable-keys schema-result schema-error]}
            (logic/validate-ai-prediction node blackboard optional-writes unparseable-is-schema-error? prediction)
            total-usage (logic/merge-usage accumulated-usage usage)
            provider-outcome
            (cond-> {:status (if (or error (seq unparseable-keys) schema-error) :failure :success)}
              failure-kind (assoc :failure-kind failure-kind)
              provider-evidence (assoc :provider-evidence provider-evidence)
              (and (nil? failure-kind) (or (seq unparseable-keys) schema-error))
              (assoc :failure-kind :schema-validation-failed)
              provider-observations (assoc :provider-observations provider-observations))
            request-complete-error
            (when (and request (not request-start-error) (not start-error))
              (record-request! (assoc request :phase :completed :provider-outcome provider-outcome
                                      :outcome (logic/durable-outcome
                                                (assoc prediction :status (if (:error prediction) :failure :success))))))
            completion-error
            (or request-complete-error replayed-observation-error
            (when (and invocation (not start-error) (not request-start-error))
              (logic/persist-provider-attempt
               complete-provider-attempt!
               (assoc invocation :outcome provider-outcome))))]
        (cond
          (or request-start-error start-error completion-error)
          (cond-> {:status :failure :retryable? false
                   :error (if (or request-start-error start-error)
                            "Provider attempt start could not be persisted; provider was not dispatched"
                            "Provider attempt outcome could not be persisted; dispatch will not be repeated")
                   :duration-ms (- (System/currentTimeMillis) start-time)
                   :usage total-usage}
            (or request-start-error request-complete-error replayed-observation-error)
            (assoc :failure-kind :model-plan-incompatible))

          budget-timeout?
          (cond-> {:status :timeout :error error
                   :duration-ms (- (System/currentTimeMillis) start-time) :usage total-usage}
            admission-failure-kind (assoc :failure-kind admission-failure-kind))

          (and error (not @output-exposed?) (not (contains? logic/non-retryable-failure-kinds failure-kind)) (< attempt max-retries))
          (let [backoff (backoff-for attempt)
                remaining (logic/remaining-ms deadline-ms)]
            (if (and remaining (<= remaining backoff))
              {:status :timeout
               :error "Execution deadline exhausted before provider retry backoff"
               :duration-ms (- (System/currentTimeMillis) start-time)
               :usage total-usage}
              (do (logic/log-retry!
                {:node-id (:id node) :node-name (:name node)
                 :attempt (inc attempt) :max-attempts (inc max-retries)
                 :reason error :trace-id nil})
                  (Thread/sleep backoff)
                  (recur (inc attempt) total-usage))))

          error
          (let [result (cond-> {:status :failure :error error
                                :output-exposed? @output-exposed?
                                :duration-ms (- (System/currentTimeMillis) start-time)}
                         total-usage (assoc :usage total-usage)
                         model (assoc :model model)
                         resolved-model (assoc :resolved-model resolved-model)
                         failure-kind (assoc :failure-kind failure-kind)
                         provider-evidence (assoc :provider-evidence provider-evidence))]
            (logic/log-ai-execution!
              {:node-id (:id node) :node-name (:name node) :model model
               :executor :ai :duration-ms (:duration-ms result)
               :status :failure :usage total-usage :trace-id nil :error error})
            result)

          (and (seq unparseable-keys) (not unparseable-is-schema-error?))
          (let [nil-keys unparseable-keys
                raw-len (count (str raw-response))
                preview (when raw-response
                          (subs raw-response 0 (min 1000 (count raw-response))))
                error-msg (str "LLM output unparseable for keys " nil-keys
                               " — no value could be extracted for these declared writes."
                               (if raw-response
                                 (str " Raw response captured (" raw-len " chars; full text"
                                      " retrievable via (node-output <node-id>) using this"
                                      " node's id from :failed-leaves)."
                                      "\n--- raw response preview (first "
                                      (min 1000 raw-len) " of " raw-len " chars) ---\n"
                                      preview)
                                 " No raw response text was returned by the provider."))
                result (cond-> {:status :failure :error error-msg
                        :raw-response raw-response
                        :outputs outputs
                        :duration-ms (- (System/currentTimeMillis) start-time)
                        :usage total-usage :model model
                        :resolved-model resolved-model
                        :provider-evidence provider-evidence}
                         (seq missing-output-keys)
                         (assoc :missing-write-keys missing-output-keys))]
            (logic/log-unparseable-output!
              {:node-id (:id node) :node-name (:name node) :model model
               :nil-keys nil-keys :raw-length raw-len
               :raw-response raw-response :trace-id nil})
            (logic/log-ai-execution!
              {:node-id (:id node) :node-name (:name node) :model model
               :executor :ai :duration-ms (:duration-ms result)
               :status :failure :usage total-usage :trace-id nil :error error-msg})
            result)

          (and schema-error (not @output-exposed?) (< attempt max-retries))
          (let [backoff (backoff-for attempt)
                remaining (logic/remaining-ms deadline-ms)]
            (if (and remaining (<= remaining backoff))
              {:status :timeout
               :error "Execution deadline exhausted before provider retry backoff"
               :duration-ms (- (System/currentTimeMillis) start-time)
               :usage total-usage}
              (do (logic/log-retry!
                {:node-id (:id node) :node-name (:name node)
                 :attempt (inc attempt) :max-attempts (inc max-retries)
                 :reason schema-error :trace-id nil})
                  (Thread/sleep backoff)
                  (recur (inc attempt) total-usage))))

          schema-error
          (let [result (assoc schema-result
                              :duration-ms (- (System/currentTimeMillis) start-time)
                              :usage total-usage
                              :model model
                              :resolved-model resolved-model
                              :failure-kind :schema-validation-failed
                              :provider-evidence provider-evidence)]
            (logic/log-ai-execution!
              {:node-id (:id node) :node-name (:name node) :model model
               :executor :ai :duration-ms (:duration-ms result)
               :status :failure :usage total-usage :trace-id nil :error schema-error})
            result)

          :else
          (let [result (cond-> {:status :success :outputs (:outputs schema-result)
                                :duration-ms (- (System/currentTimeMillis) start-time)
                                :usage total-usage :model model
                                :resolved-model resolved-model}
                         raw-response (assoc :raw-response raw-response)
                         (seq decisions) (assoc :decisions decisions))]
            (logic/log-ai-execution!
              {:node-id (:id node) :node-name (:name node) :model model
               :executor :ai :duration-ms (:duration-ms result)
               :status :success :usage total-usage :trace-id nil})
            result)))))))

(defn execute-ai
  "Execute a model task under its ordinary or generated-specialist owner,
   retaining effective configured candidates and exact physical responses."
  [node blackboard provider & {:keys [options stream] :or {options {}}}]
  (if-not (seq (:fallback-models node))
    (do
      (when (:provider node)
        (llm/preflight-provider! (:provider node)
          (dissoc (logic/build-module node blackboard) :output-mapping)
          (assoc (dissoc options :fresh-model-authority!)
                 :model (:model node) :orc/explicit-provider? true)))
      (execute-ai-candidate node blackboard (or (:provider node) provider)
                            :options options :stream stream))
    (try
      (let [_ (logic/validate-config! node)
            host-keys #{:execution-deadline-ms :reserve-llm-call! :fresh-model-authority! :reserve-provider-attempt!
                        :provider-reservation-context :tick-id :node-attempt :max-node-attempts
                        :exec-context :start-provider-attempt! :complete-provider-attempt!
                        :model-fallback-callbacks :model-role-context :model-request-callbacks}
            task-options (apply dissoc options host-keys)
            module (dissoc (logic/build-module node blackboard) :output-mapping)
            policy (or (:fallback-policy node) :temporary-transport)
            role-context (:model-role-context options)
            ordinary? (= :node (get-in role-context [:owner :role]))
            emitted? (= :subcall (get-in role-context [:owner :role]))
            resolve-plan! #(logic/resolve-plan node provider module task-options)
            prepared (cond
                       ordinary?
                       (prepare-ordinary-call
                        (:context role-context) (:owner role-context)
                        (select-keys node [:provider :model :options :fallback-models :fallback-policy])
                        resolve-plan!)
                       emitted?
                       (prepare-emitted-call
                        (:context role-context) (:owner role-context)
                        (select-keys node [:provider :model :options :fallback-models :fallback-policy])
                        resolve-plan!))
            legacy-candidates
            (when (and role-context (not ordinary?) (not emitted?))
              (mapv (fn [index candidate]
                      (let [explicit? (or (contains? node :provider)
                                          (and (pos? index)
                                               (contains? (nth (:fallback-models node) (dec index)) :provider)))
                            resolved (llm/preflight-provider!
                                      (:provider candidate) module
                                      (cond-> (:options candidate)
                                        explicit? (assoc :orc/explicit-provider? true)
                                        (:model candidate) (assoc :model (:model candidate))))]
                        (assoc candidate :provider (:provider resolved) :model (:model resolved))))
                    (range) (logic/candidate-plan node provider task-options)))
            candidates (or (:candidates prepared) (:candidates (resolve-plan!)))
            policy (or (:policy prepared) policy)
            callbacks (or (:callbacks prepared) (:model-fallback-callbacks options)
                          (when role-context
                            (ai.obney.orc.orc-service.core.todo-processors/callbacks
                             (:context role-context) (:owner role-context)
                             (or legacy-candidates candidates) policy)))]
        (when-not (fn? (:record! callbacks))
          (throw (ex-info "Durable model fallback callbacks are required"
                          {:failure-kind :configuration-error})))
        (logic/run-candidates
         (merge callbacks
                {:candidates candidates :policy policy
                 :invoke!
                 (fn [index candidate]
                   (execute-ai-candidate
                    (assoc node :model (:model candidate)) blackboard (:provider candidate)
                    :options (-> (merge (:options candidate) (select-keys options host-keys))
                                 (dissoc :model-fallback-callbacks)
                                 (assoc :model-candidate-index index
                                        :model-request-callbacks (when prepared callbacks)
                                        :orc/retained-model-profile (when prepared
                                                                      (select-keys candidate [:provider :model :protocol :profile]))
                                        :orc/model-replay-only? (boolean (:replay-only? prepared))
                                        :mark-output-exposed!
                                        (when-let [mark! (:mark-output-exposed! callbacks)]
                                          #(mark! index))))
                    :stream stream))})))
      (catch clojure.lang.ExceptionInfo error
        (if (= :model-plan-incompatible (:failure-kind (ex-data error)))
          {:status :failure :retryable? false :failure-kind :model-plan-incompatible
           :error (.getMessage error)}
          (throw error))))))

(defn execute-llm-condition
  "Execute an LLM condition node - a provider invocation that answers a yes/no
   question, under the SAME execution policy as a model leaf.

   The condition is expressed as a leaf with one declared boolean write
   (`:result`) and delegated to `execute-ai`, so provider retry, backoff,
   deadline, LLM-call budget reservation, per-node model choice and
   structured failure capture are shared rather than re-implemented.

   Args:
     node - The llm-condition node map with :instruction, :reads, :model
     blackboard - Map of key -> {:key, :schema, :value, :version}
     provider - ORC LLM provider keyword (e.g., :openrouter)
     options - Optional ORC LLM options map (same policy options as execute-ai)

   Returns:
     {:status :success/:failure/:timeout
      :result boolean?          - the model's answer; present ONLY when a
                                  valid boolean answer was obtained
      :error string?            - error message if no valid answer
      :failure-kind keyword?    - structured failure kind (never set for a
                                  valid semantic negative)
      :provider-evidence map?
      :duration-ms int
      :usage map?  :model string?}

   A valid `true` is :status :success; a valid `false` is :status :failure with
   :result false and no :failure-kind. A missing, nil or non-boolean answer is
   a :failure with :failure-kind :schema-validation-failed and no :result."
  [node blackboard provider & {:keys [options] :or {options {}}}]
  (let [answer-key :result
        condition-bb (reduce (fn [bb k]
                               (if (get-in bb [k :schema])
                                 bb
                                 (assoc-in bb [k :schema] :string)))
                             (assoc blackboard answer-key
                                    {:key answer-key
                                     :schema [:boolean
                                              {:description "True if the condition is met, false otherwise"}]})
                             (:reads node))
        leaf (-> node
                 (select-keys [:id :name :instruction :reads :model :options :provider :fallback-models :fallback-policy])
                 (assoc :writes [answer-key]))
        result (execute-ai leaf condition-bb provider
                           :options (merge {:use-function-calling? true}
                                           (:options node) options))
        answer (get-in result [:outputs answer-key])
        valid? (and (= :success (:status result)) (boolean? answer))
        base (dissoc result :outputs :rejected-writes)]
    (cond
      valid?
      (if answer
        (assoc base :status :success :result true)
        (assoc base :status :failure :result false
               :error "Condition evaluated to false"))

      (= :success (:status result))
      (assoc base :status :failure
             :failure-kind :schema-validation-failed
             :error "Model-backed condition returned no valid boolean answer")

      :else
      (cond-> base
        (and (= :failure (:status result)) (nil? (:failure-kind result)) (contains? result :outputs))
        (assoc :failure-kind :schema-validation-failed)))))

(defn execute-decision
  "Execute a model decision: a leaf whose single declared write is a finite
   answer (boolean, or one of an offered set of identified options), under the
   SAME execution policy as a model leaf.

   The decision is expressed as a synthetic leaf whose one write is the answer
   key with a finite schema built from the options actually offered, then
   delegated to `execute-ai`, so provider retry, backoff, deadline, LLM-call
   budget reservation, per-node model choice and failure capture are shared.

   Offered options: the answer key's static schema (`:boolean`, or an enum with
   `:descriptions`), or, with :options-from, the run-time value of that read key
   (`[{:id :description}]`). Every option id and description is rendered into
   the provider-facing instruction.

   Banded (:bands-from): the run-time rubric on that read key is validated
   (an invalid rubric fails the node, writes nothing), rendered into the
   instruction, and offered as an ORDERED string enum of band numbers; the
   chosen band is written as an integer. A native decision model reports its
   distribution, kept in :decision :band-distribution; an exact tie for most
   probable band fails with :failure-kind :undecided.

   Returns the `execute-ai` result shape plus :decision
   `{:offered [...] :answer v :abstained? bool [:set-aside v]}`.
   A valid answer is :status :success with :outputs {answer-key value}. A
   missing, nil or out-of-set answer is :status :failure with
   :failure-kind :schema-validation-failed and NO :outputs. No confidence is
   synthesised: the floor is judged against a provider-reported :confidence
   only (none is reported in this runtime yet), so a configured floor abstains."
  [node blackboard provider & {:keys [options] :or {options {}}}]
  (let [answer-key (first (:writes node))
        fail (fn [error offered]
               {:status :failure
                :failure-kind :schema-validation-failed
                :error error
                :duration-ms 0
                :decision {:offered (vec offered) :abstained? false}})
        banded? (some? (:bands-from node))
        banded (when banded?
                 (logic/rubric (get-in blackboard [(:bands-from node) :value])))
        band-levels (mapv (comp str first) (get-in banded [:rubric :bands]))
        runtime? (some? (:options-from node))
        runtime-opts (when runtime?
                       (logic/runtime-options
                        (get-in blackboard [(:options-from node) :value])))
        static (when-not (or runtime? banded?)
                 (logic/schema-options (get-in blackboard [answer-key :schema])))
        kind (cond banded? :band runtime? :enum :else (:kind static))
        option-list (cond
                      banded? (mapv (fn [[level description]] {:id (str level) :description description})
                                    (get-in banded [:rubric :bands]))
                      runtime? runtime-opts
                      (= :enum kind) (mapv (fn [id] {:id id
                                                     :description (get (:descriptions static) id)})
                                           (:ids static))
                      :else nil)
        offered (if (= :boolean kind) [true false] (mapv :id option-list))]
    (cond
      (and banded? (:error banded))
      (fail (str "Decision '" (:name node) "' read key " (pr-str (:bands-from node))
                 " holds an invalid rubric: " (:error banded))
            [])

      (nil? kind)
      (fail (str "Decision '" (:name node) "' offers no valid options: answer key "
                 (pr-str answer-key) " is not boolean or a finite enum"
                 (when runtime? (str " and read key " (pr-str (:options-from node))
                                     " holds no identified options")))
            [])

      (and runtime? (nil? runtime-opts))
      (fail (str "Decision '" (:name node) "' read key " (pr-str (:options-from node))
                 " holds no non-empty vector of {:id :description} options")
            [])

      (and (some? (:abstain node)) (not (some #(= (:abstain node) %) offered)))
      (fail (str "Decision '" (:name node) "' abstention option " (pr-str (:abstain node))
                 " is not one of the offered options " (pr-str offered))
            offered)

      :else
      (let [offer-text (if banded?
                         (logic/render-bands (:rubric banded))
                         (logic/render-options kind option-list))
            answer-schema (cond
                            (= :boolean kind)
                            [:boolean {:description (str "The decision's answer. " offer-text)}]

                            banded?
                            (into [:enum {:ordered-bands true
                                          :description (str "The chosen band number. " offer-text)
                                          :descriptions (into {}
                                                              (map (fn [{:keys [id description]}]
                                                                     [id description]))
                                                              option-list)}]
                                  offered)

                            :else
                            (into [:enum {:description (str "The chosen option id. " offer-text)
                                          :descriptions (into {}
                                                              (keep (fn [{:keys [id description]}]
                                                                      (when description [id description])))
                                                              option-list)}]
                                  offered))
            decision-bb (reduce (fn [bb k]
                                  (if (get-in bb [k :schema])
                                    bb
                                    (assoc-in bb [k :schema] :string)))
                                (assoc blackboard answer-key
                                       {:key answer-key :schema answer-schema})
                                (:reads node))
            leaf (-> node
                     (select-keys [:id :name :reads :model :options :provider :fallback-models :fallback-policy])
                     (update :reads #(if banded? (vec (remove #{(:bands-from node)} %)) %))
                     (assoc :instruction (str (:instruction node) "\n\n" offer-text)
                            :writes [answer-key]))
            result (execute-ai leaf decision-bb provider
                               :options (merge {:use-function-calling? true} options))
            answer (get-in result [:outputs answer-key])
            reported (some-> (:decisions result) vals first)
            reported-record (if banded?
                              {}
                              (select-keys reported [:probabilities :confidence :probability]))
            band-record (fn [{:keys [probabilities expected-position confidence]}]
                          (when (and probabilities (some? expected-position))
                            {:band-distribution
                             (cond-> {:probabilities probabilities
                                      :expected-position expected-position}
                               (some? confidence) (assoc :confidence confidence))}))
            ->level (fn [x] (if (and banded? (string? x)) (Long/parseLong x) x))
            base (dissoc result :outputs :rejected-writes :decisions)]
        (if (= :success (:status result))
          (let [{:keys [valid? value record]}
                (logic/judge {:offered offered
                                 :abstain (:abstain node)
                                 :min-confidence (:min-confidence node)}
                                answer
                                (:confidence reported))
                record (merge record reported-record
                              (when banded? (band-record reported)))
                record (if banded?
                         (cond-> (update record :offered #(mapv ->level %))
                           (contains? record :answer) (update :answer ->level))
                         record)]
            (if valid?
              (assoc base :outputs {answer-key (->level value)} :decision record)
              (assoc base :status :failure
                     :failure-kind :schema-validation-failed
                     :error (str "Model decision '" (:name node)
                                 "' returned no answer among the offered options "
                                 (pr-str offered))
                     :decision record)))
          (cond-> (assoc base :decision
                         (merge {:offered (mapv ->level offered) :abstained? false}
                                (when banded?
                                  (band-record (get-in result [:provider-evidence :band-distribution])))))
            (and (= :failure (:status result)) (nil? (:failure-kind result))
                 (contains? result :outputs))
            (assoc :failure-kind :schema-validation-failed)))))))

(defn execute-repl-researcher-rlm
  "Execute a repl-researcher node in RLM mode.

   On resume, the researcher uses its journal key to recognise an already-dispatched call (ADR 0004).

   RLM mode provides BT primitives in the sandbox:
   - (llm ...) executes sub-LLM calls
   - (final! ...) validates and captures output
   - (get-input ...) loads input values

   This separates variable space (sandbox memory) from token space (LLM context).

  Options:
   - :debug? - Enable verbose debug output for troubleshooting (default: false)"
  [node blackboard provider context & {:keys [options] :or {options {}}}]
  (let [campaign-now-ms-fn (or (:campaign-now-ms-fn context)
                               #(System/currentTimeMillis))
        start-time (long (campaign-now-ms-fn))
        max-iterations (or (:max-iterations node) 10)
        mcp-tools (or (:mcp-tools node) [])
        browser-tools (or (:browser-tools node) [])
        declared-writes (:writes node)
        optional-writes (set (get-in node [:options :optional-writes]))
        rlm-config (let [rlm (:rlm node)] (if (map? rlm) rlm {}))
        debug? (or (get rlm-config :debug? false) (get options :debug? false))

        dbg (fn [& args] (when debug? (apply println "[DEBUG RLM]" args)))

        inputs-preview (logic/build-inputs-preview blackboard)

        checkpoint-codecs (vec (or (:researcher-checkpoint-codecs context) []))
        encode-effect-value
        (fn [value]
          (logic/encode-checkpoint-value checkpoint-codecs value))
        legacy-checkpoint (some->> (:researcher-checkpoint context)
                                   (logic/decode-checkpoint-value checkpoint-codecs))
        resume-state (some->> (:researcher-resume-state context)
                              (logic/decode-checkpoint-value checkpoint-codecs))
        iteration-records (some->> (:researcher-iteration-records context)
                                   (logic/decode-checkpoint-value checkpoint-codecs))
        checkpoint (if resume-state
                     (assoc resume-state :history (vec iteration-records))
                     legacy-checkpoint)
        researcher-now-ms-fn (or (:researcher-now-ms-fn context)
                                 #(System/currentTimeMillis))
        checkpointed? (boolean (logic/checkpointed? node))
        ownership-epoch (or (:researcher-ownership-epoch context)
                            (:ownership-epoch checkpoint)
                            0)
        missing-effect-claim-capabilities?
        (and checkpointed?
             (or (not (ifn? (:claim-researcher-effect! context)))
                 (not (ifn? (:complete-researcher-effect! context)))))
        missing-ownership-epoch?
        (and checkpointed?
             (not (and (integer? ownership-epoch) (pos? ownership-epoch))))
        quantum-max-iterations (max 1 (or (get-in rlm-config [:quantum :max-iterations]) 1))
        initial-iteration (or (:next-iteration checkpoint) 0)
        checkpoint-revision (atom (or (:revision checkpoint) 0))
        iteration-attempts (or (:iteration-attempts checkpoint) {})
        max-iteration-attempts (max 1 (or (get-in rlm-config [:iteration-retry :max-attempts]) 3))
        researcher-monotonic-ms-fn
        (when checkpointed?
          (or (:researcher-monotonic-ms-fn context)
              #(quot (System/nanoTime) 1000000)))
        researcher-quantum-started-monotonic-ms
        (when checkpointed?
          (long (or (:researcher-quantum-started-monotonic-ms context)
                    (researcher-monotonic-ms-fn))))
        max-observed-quantum-duration-ms
        (long (or (:max-observed-quantum-duration-ms checkpoint) 0))
        with-completed-quantum-observation
        (fn [checkpoint]
          (let [observed-ms
                (max 0 (- (long (researcher-monotonic-ms-fn))
                          researcher-quantum-started-monotonic-ms))]
            (assoc checkpoint
                   :observed-quantum-duration-ms observed-ms
                   :max-observed-quantum-duration-ms
                   (max max-observed-quantum-duration-ms observed-ms))))
        current-iteration (atom initial-iteration)
        action-ordinal (atom 0)
        current-iteration-deadline-ms (atom nil)
        current-generated-code-hash (atom nil)
        legacy-completed-actions
        (reduce-kv (fn [acc action-id action]
                     (assoc acc action-id
                            (update action :result
                                    #(logic/decode-checkpoint-value checkpoint-codecs %))))
                   {}
                   (or (:researcher-actions context) {}))
        effect-claim-completions
        (reduce (fn [acc claim]
                  (if (= :completed (:status claim))
                    (assoc acc (:logical-action-identity claim)
                           (update claim :result
                                   #(logic/decode-checkpoint-value checkpoint-codecs %)))
                    acc))
                {}
                (or (:researcher-effect-claims context) []))
        completed-actions (atom (merge legacy-completed-actions
                                       effect-claim-completions))
        persist-action! (:persist-researcher-action! context)
        checkpoint-tool-violation (atom nil)
        checkpoint-tool-timeout (atom nil)
        unsafe-checkpoint-tool
        (when checkpointed?
          (some (fn [tool-name]
                  (when-not (true? (get-in node [:tool-contracts tool-name
                                                 :checkpoint-safe?]))
                    tool-name))
                mcp-tools))
        raw-call-tool-fn (logic/node-call-tool-fn node blackboard context)
        checkpoint-call-tool-fn
        (when checkpointed? (logic/tool-caller-target raw-call-tool-fn))
        checkpoint-tool-caller-arity
        (when checkpointed? (logic/tool-caller-arity checkpoint-call-tool-fn))
        incompatible-checkpoint-tool-caller?
        (and checkpointed?
             (seq mcp-tools)
             (not= 3 checkpoint-tool-caller-arity))
        call-tool-fn
        (if checkpointed?
          (fn [tool-name args]
            (let [contract (get (:tool-contracts node) tool-name)]
              (when-not (true? (:checkpoint-safe? contract))
                (reset! checkpoint-tool-violation tool-name)
                (throw (ex-info
                        (str "Tool " tool-name
                             " is not declared :checkpoint-safe? for resumable execution")
                        {:tool tool-name :checkpoint-safe? false})))
              (logic/validate-arguments! (:tool-contracts node) tool-name args)
              (let [ordinal (swap! action-ordinal inc)
                    attempt-ordinal (get iteration-attempts @current-iteration 0)
                    action-id
                    (logic/logical-action-identity
                     {:tick-id (:tick-id context)
                      :node-id (:node-id context)
                      :iteration-index @current-iteration
                      :generated-code-hash @current-generated-code-hash
                      :kind :tool
                      :target tool-name
                      :arguments (encode-effect-value args)})
                    legacy-action-id
                    (str (:tick-id context) "/" (:node-id context) "/"
                         @current-iteration "/tool/" ordinal)
                    attempt-id (logic/attempt-identity
                                action-id ownership-epoch attempt-ordinal)
                    tool-timeout-ms
                    (when-let [deadline-ms @current-iteration-deadline-ms]
                      (max 0 (- (long deadline-ms)
                                (long (campaign-now-ms-fn)))))
                    tool-context (assoc (or (:tool-context context) {})
                                        :orc/idempotency-key action-id
                                        :orc/researcher-iteration @current-iteration
                                        :orc/action-ordinal ordinal
                                        :orc/timeout-ms tool-timeout-ms)]
                (if-let [completed (or (get @completed-actions action-id)
                                       (get @completed-actions legacy-action-id))]
                  (:result completed)
                  (let [_ (when-not (and (integer? tool-timeout-ms)
                                         (pos? tool-timeout-ms))
                            (reset! checkpoint-tool-timeout
                                    {:tool tool-name
                                     :timeout-ms tool-timeout-ms})
                            (throw
                             (ex-info
                              "Researcher tool deadline exceeded before dispatch"
                              {:timeout-kind :iteration
                               :tool tool-name
                               :timeout-ms tool-timeout-ms})))
                        {:keys [result durable-result]}
                        (logic/dispatch-claimed-tool-effect!
                         {:claim! (:claim-researcher-effect! context)
                          :complete! (:complete-researcher-effect! context)
                          :codecs checkpoint-codecs
                          :contracts (:tool-contracts node)
                          :tool-name tool-name
                          :args args
                          :action-id action-id
                          :attempt-id attempt-id
                          :attempt-ordinal attempt-ordinal
                          :iteration-index @current-iteration
                          :caller checkpoint-call-tool-fn
                          :caller-arity checkpoint-tool-caller-arity
                          :tool-context tool-context
                          :wrap-errors? true})]
                    (swap! completed-actions assoc action-id
                           {:status :completed :result result})
                    (when persist-action!
                      (persist-action! {:action-id action-id
                                        :action-kind :tool
                                        :iteration @current-iteration
                                        :result durable-result}))
                    result)))))
          (-> (logic/phase1-call-tool-fn (assoc context :call-tool-fn raw-call-tool-fn))
              (logic/guarded-call-tool-fn (:tool-contracts node))))
        timeout-config (or (:timeouts rlm-config) {})
        pre-classification-timing (:researcher-campaign-timing context)
        campaign-timing
        (logic/resolve-researcher-campaign-timing
         {:node node
          :checkpoint (merge pre-classification-timing checkpoint)
          :started-at-ms (or (:campaign-started-at-ms
                              pre-classification-timing)
                             start-time)
          :parent-timeout-ms (:parent-timeout-ms context)})
        campaign-started-at-ms (:campaign-started-at-ms campaign-timing)
        campaign-timeout-ms (:campaign-timeout-ms campaign-timing)
        campaign-deadline-ms (:campaign-deadline-ms campaign-timing)
        classification-context
        (or (:classification-context checkpoint)
            (:researcher-classification-context context))
        workflow-deadline-ms (:workflow-deadline-ms context)
        phase-budget-deadline-ms
        (+ campaign-started-at-ms
           (:total-budget-ms
            (logic/resolve-phase2-budget
             {:node node
              :parent-timeout-ms (:parent-timeout-ms context)
              :phase1-elapsed-ms 0})))
        campaign-deadline-source
        (cond
          (some? (:campaign-ms timeout-config)) :campaign
          (= campaign-deadline-ms phase-budget-deadline-ms) :phase-budget
          :else :campaign)

        total-usage (atom (or (:usage checkpoint)
                              {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}))

        descendant-usage (atom (or (:descendant-usage checkpoint)
                                   {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}))
        add-descendant-usage!
        (fn [u]
          (swap! descendant-usage
                 (fn [acc]
                   (-> acc
                       (update :prompt-tokens (fnil + 0) (or (:prompt-tokens u) 0))
                       (update :completion-tokens (fnil + 0) (or (:completion-tokens u) 0))
                       (update :total-tokens (fnil + 0) (or (:total-tokens u) 0))
                       (logic/add-cost u)))))

        sandbox-vars (atom (or (:sandbox-vars checkpoint) {}))

        var-creation-times (atom (or (:var-creation-times checkpoint) {}))

        cumulative-tree-ms (atom (or (:cumulative-tree-ms checkpoint) 0))
        recursive-mode? (not= false (get-in node [:rlm :recursive?]))
        iteration-record
        (fn [iteration status history-entry attempt-start-ms terminal-result]
          (let [history-entry (or history-entry {})
                raw-attempt-completed-ms (long (researcher-now-ms-fn))
                attempt-completed-ms (max attempt-start-ms
                                          raw-attempt-completed-ms)
                emitted-tree-source (:generated-tree-source history-entry)
                emitted-tree (or (:generated-tree-raw history-entry)
                                 (:generated-tree history-entry))
                tree-fingerprint (when emitted-tree
                                   (logic/fingerprint emitted-tree))
                error-excerpt (logic/bound-iteration-evidence-text
                               (:error history-entry)
                               logic/iteration-error-excerpt-max-chars)
                bounded-reasoning (logic/bound-iteration-evidence-text
                                   (:reasoning history-entry)
                                   logic/iteration-reasoning-max-chars)
                error-class (or (:error-class history-entry)
                                (some-> (:timeout-kind history-entry)
                                        name
                                        (str "-timeout"))
                                (when error-excerpt "researcher-error"))
                created-keys (vec (sort-by str (or (:vars-created history-entry) [])))
                updated-keys (vec (sort-by str (or (:vars-updated history-entry) [])))
                removed-keys (vec (sort-by str (or (:vars-removed history-entry) [])))
                result-profile (or (:result-profile history-entry)
                                   (when (some? (:result history-entry))
                                     (logic/profile-value
                                      (:result history-entry))))
                stdout-profile (or (:stdout-profile history-entry)
                                   (when (some? (:stdout history-entry))
                                     (logic/profile-value
                                      (:stdout history-entry))))]
            (cond-> {:iteration-index iteration
                     :attempt-ordinal (get iteration-attempts iteration 0)
                     :status status
                     :started-at (str (java.time.Instant/ofEpochMilli
                                       attempt-start-ms))
                     :completed-at (str (java.time.Instant/ofEpochMilli
                                         attempt-completed-ms))
                     :duration-ms (max 0 (- attempt-completed-ms
                                            attempt-start-ms))
                     :generated-code-recorded?
                     (boolean (seq (:code history-entry)))
                     :emitted-tree-recorded?
                     (boolean (and emitted-tree tree-fingerprint))
                     :variable-delta {:created-keys created-keys
                                      :updated-keys updated-keys
                                      :removed-keys removed-keys}}
              (= :blocked status)
              (assoc :block-reason (:block-payload terminal-result))

              (contains? history-entry :code)
              (assoc :code (:code history-entry))

              emitted-tree
              (assoc :emitted-tree emitted-tree
                     :tree-fingerprint tree-fingerprint)

              emitted-tree-source
              (assoc :emitted-tree-source emitted-tree-source)

              (some? bounded-reasoning)
              (assoc :reasoning bounded-reasoning)

              error-excerpt
              (assoc :error-class error-class
                     :error-excerpt error-excerpt)

              (some? result-profile)
              (assoc :result-profile result-profile)

              (some? stdout-profile)
              (assoc :stdout-profile stdout-profile))))
        checkpoint-result
        (fn [next-iteration history attempt-start-ms]
          (when checkpointed?
            (let [completed-iteration (dec next-iteration)
                  history-entry (last history)
                  record-status (if (:error history-entry) :failure :success)
                  quantum-complete?
                  (>= (- next-iteration initial-iteration)
                      quantum-max-iterations)
                  checkpoint (cond-> {:version 1
                              :revision (swap! checkpoint-revision inc)
                              :ownership-epoch ownership-epoch
                              :next-iteration next-iteration
                              :history history
                              :sandbox-vars @sandbox-vars
                              :var-creation-times @var-creation-times
                              :usage @total-usage
                              :descendant-usage @descendant-usage
                              :cumulative-tree-ms @cumulative-tree-ms
                              :iteration-attempts (dissoc iteration-attempts (dec next-iteration))
                              :campaign-started-at-ms campaign-started-at-ms
                              :campaign-deadline-ms campaign-deadline-ms
                              :iteration-record
                              (iteration-record completed-iteration
                                                record-status
                                                history-entry
                                                attempt-start-ms
                                                nil)}
                               classification-context
                               (assoc :classification-context
                                      classification-context)
                               quantum-complete?
                               with-completed-quantum-observation)
                  encoded (try
                            {:checkpoint (logic/encode-checkpoint-value checkpoint-codecs checkpoint)}
                            (catch Exception e
                              {:encode-error (ex-message e)}))]
              (if-let [encode-error (:encode-error encoded)]
                {:status :failure
                 :iterations history
                 :duration-ms (- (long (campaign-now-ms-fn)) campaign-started-at-ms)
                 :usage @total-usage
                 :error (str "Researcher checkpoint contains a non-durable sandbox value; "
                             "use EDN data or a registered durable codec (" encode-error ")")}
              (let [durable-checkpoint (:checkpoint encoded)]
              (if (logic/checkpoint-durable-value? durable-checkpoint)
                (if quantum-complete?
                  {:status :running
                   :iterations history
                   :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
                   :usage @total-usage
                   :checkpoint durable-checkpoint}
                  (do
                    (when-let [persist! (:persist-researcher-checkpoint! context)]
                      (persist! durable-checkpoint))
                    nil))
                {:status :failure
                 :iterations history
                 :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
                 :usage @total-usage
                 :error "Researcher checkpoint contains a non-durable sandbox value; use EDN data or a registered durable codec"}))))))
        persist-terminal-result
        (fn [iteration history terminal-result attempt-start-ms]
          (if-not checkpointed?
            terminal-result
            (let [checkpoint (cond-> {:version 1
                              :revision (swap! checkpoint-revision inc)
                              :ownership-epoch ownership-epoch
                              :next-iteration (inc iteration)
                              :history history
                              :sandbox-vars (dissoc @sandbox-vars :generated-tree)
                              :var-creation-times @var-creation-times
                              :usage @total-usage
                              :descendant-usage @descendant-usage
                              :cumulative-tree-ms @cumulative-tree-ms
                              :iteration-attempts (dissoc iteration-attempts iteration)
                              :campaign-started-at-ms campaign-started-at-ms
                              :campaign-deadline-ms campaign-deadline-ms
                              :terminal-result terminal-result
                              :iteration-record
                              (iteration-record iteration
                                                (:status terminal-result)
                                                (last history)
                                                attempt-start-ms
                                                terminal-result)}
                               classification-context
                               (assoc :classification-context
                                      classification-context))
                  durable-checkpoint (logic/encode-checkpoint-value checkpoint-codecs checkpoint)]
              (when-let [persist! (:persist-researcher-checkpoint! context)]
                (persist! durable-checkpoint))
              (assoc terminal-result :checkpoint durable-checkpoint))))
        retry-result
        (fn [iteration history timeout-kind error attempt-start-ms]
          (let [attempt (inc (get iteration-attempts iteration 0))
                retrying? (and checkpointed?
                               (< attempt max-iteration-attempts))
                durable-checkpoint
                (when checkpointed?
                  (logic/encode-checkpoint-value
                   checkpoint-codecs
                   (cond-> {:version 1
                    :revision (swap! checkpoint-revision inc)
                    :ownership-epoch ownership-epoch
                    :next-iteration iteration
                    :history history
                    :sandbox-vars @sandbox-vars
                    :var-creation-times @var-creation-times
                    :usage @total-usage
                    :cumulative-tree-ms @cumulative-tree-ms
                    :iteration-attempts (assoc iteration-attempts iteration attempt)
                    :campaign-started-at-ms campaign-started-at-ms
                    :campaign-deadline-ms campaign-deadline-ms
                    :iteration-record
                    (iteration-record iteration :timeout
                                      {:error error
                                       :timeout-kind timeout-kind}
                                      attempt-start-ms
                                      nil)}
                     classification-context
                     (assoc :classification-context
                            classification-context)
                     retrying? with-completed-quantum-observation)))]
            (if retrying?
              {:status :running
               :iterations history
               :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
               :usage @total-usage
               :checkpoint durable-checkpoint
               :retry {:iteration (inc iteration)
                       :attempt attempt
                       :max-attempts max-iteration-attempts
                       :timeout-kind timeout-kind}}
              (do
                (when (and checkpointed? durable-checkpoint)
                  (when-let [persist! (:persist-researcher-checkpoint! context)]
                    (persist! durable-checkpoint)))
                (cond-> {:status :timeout
                         :timeout-kind timeout-kind
                         :error error
                         :iterations history
                         :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
                         :usage @total-usage
                         :iteration-attempt attempt
                         :max-iteration-attempts max-iteration-attempts}
                  durable-checkpoint (assoc :checkpoint durable-checkpoint))))))]

    (as-> (try
      (loop [iteration initial-iteration
             history (or (:history checkpoint) [])]
        (cond
          (:terminal-result checkpoint)
          (:terminal-result checkpoint)

          missing-effect-claim-capabilities?
          {:status :failure
           :error (str "Checkpointed researcher requires effect-claim and "
                       "effect-complete capabilities before execution")
           :iterations history
           :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
           :usage @total-usage}

          missing-ownership-epoch?
          {:status :failure
           :error "Checkpointed researcher requires a winning ownership epoch before execution"
           :iterations history
           :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
           :usage @total-usage}

          unsafe-checkpoint-tool
          {:status :failure
           :error (str "Tool " unsafe-checkpoint-tool
                       " is not declared :checkpoint-safe? for resumable execution")
           :iterations history
           :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
           :usage @total-usage}

          incompatible-checkpoint-tool-caller?
          {:status :failure
           :error logic/tool-caller-arity-error
           :iterations history
           :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
           :usage @total-usage}

          (>= iteration max-iterations)
          (let [total-elapsed (- (long (campaign-now-ms-fn)) campaign-started-at-ms)
                final-sandbox @sandbox-vars
                surviving-vars (dissoc final-sandbox
                                       :generated-tree :generated-tree-raw
                                       :generated-tree-source
                                       :iteration-reasonings :tree-results)
                generated-tree-raw (:generated-tree-raw final-sandbox)
                generated-tree-source (:generated-tree-source final-sandbox)
                iteration-reasonings (:iteration-reasonings final-sandbox)]
            (cond-> {:status :failure
                     :error "Max iterations reached without final!"
                     :outputs surviving-vars
                     :iterations history
                     :duration-ms total-elapsed
                     :usage @total-usage
                     :cumulative-tree-ms @cumulative-tree-ms
                     :cumulative-thinking-ms (max 0 (- total-elapsed @cumulative-tree-ms))}
              generated-tree-raw (assoc :generated-tree-raw generated-tree-raw)
              generated-tree-source
              (assoc :generated-tree-source generated-tree-source)
              (seq iteration-reasonings) (assoc :iteration-reasonings
                                                (vec iteration-reasonings))))

          (and (>= (long (campaign-now-ms-fn)) campaign-deadline-ms)
               (not= :phase-budget campaign-deadline-source))
          {:status :timeout
           :timeout-kind :campaign
           :error "Researcher campaign deadline exceeded"
           :iterations history
           :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
           :usage @total-usage}

          (let [phase1-elapsed (- (long (campaign-now-ms-fn)) campaign-started-at-ms)
                budget (logic/resolve-phase2-budget
                        {:node node
                         :parent-timeout-ms (:parent-timeout-ms context)
                         :phase1-elapsed-ms phase1-elapsed})]
            (:exhausted? budget))
          (let [phase1-elapsed (- (long (campaign-now-ms-fn)) campaign-started-at-ms)
                budget (logic/resolve-phase2-budget
                        {:node node
                         :parent-timeout-ms (:parent-timeout-ms context)
                         :phase1-elapsed-ms phase1-elapsed})]
            {:status :failure
             :error (str "Budget exhausted in Phase 1 ("
                         phase1-elapsed "ms elapsed of "
                         (:total-budget-ms budget) "ms "
                         "[source=" (name (:source budget)) "]; "
                         "no time left for next iteration)")
             :iterations history
             :duration-ms phase1-elapsed
             :phase1-elapsed-ms phase1-elapsed
             :usage @total-usage
             :budget budget
             :cumulative-tree-ms @cumulative-tree-ms
             :cumulative-thinking-ms (max 0 (- phase1-elapsed @cumulative-tree-ms))})

          :else
          (let [_ (reset! current-iteration iteration)
                _ (reset! action-ordinal 0)
                iteration-start-ms (long (campaign-now-ms-fn))
                attempt-start-ms (when checkpointed?
                                   (long (researcher-now-ms-fn)))
                sandbox-before-iteration @sandbox-vars
                creation-times-before-iteration @var-creation-times
                iteration-deadline-ms (apply min
                                             (remove nil?
                                                     [campaign-deadline-ms
                                                      workflow-deadline-ms
                                                      (+ iteration-start-ms
                                                         (or (:iteration-ms timeout-config)
                                                             campaign-timeout-ms))]))
                _ (reset! current-iteration-deadline-ms iteration-deadline-ms)
                mint-behavior-contract
                (logic/registered-mint-behavior-contract (:command-registry context))
                module (logic/build-rlm-code-generation-module node inputs-preview history
                                                          blackboard @sandbox-vars @var-creation-times
                                                          mcp-tools
                                                          {:mint-behavior-contract mint-behavior-contract})
                available-code-nodes (let [rlm (:rlm node)]
                                       (when (map? rlm) (:available-code-nodes rlm)))
                latest-tree-summary (peek (:tree-results @sandbox-vars))
                setup-feedback (when latest-tree-summary
                                 (logic/render-tree-outcome latest-tree-summary))
                inputs (cond-> {:task (:instruction node)
                                :inputs-info (str "Original blackboard input previews (not current local bindings): " (pr-str inputs-preview))
                                :history (str (or (logic/build-iteration-history history) "None")
                                              (when setup-feedback
                                                (str "\n\nLatest completed child outcome (previews):\n"
                                                     setup-feedback)))}
                         available-code-nodes (assoc :available-code-nodes available-code-nodes)
                         mint-behavior-contract
                         (assoc :mint-behavior-contract mint-behavior-contract)
                         (seq mcp-tools) (assoc :tools (str/join ", " mcp-tools)
                                                :tool-contracts (logic/bound-tool-contracts node mcp-tools)))
                llm-options (merge {:validate? false
                                       :use-function-calling? false
                                       :with-metadata? true}
                                      options
                                      (when (:model node) {:model (:model node)}))

                _ (logic/emit! (:tick-id context)
                                   (cond-> {:orc.stream/type :rlm-iteration-started
                                            :iteration (inc iteration)
                                            :max-iterations max-iterations}
                                     (:sheet-id context) (assoc :sheet-id (:sheet-id context))
                                     (:node-id context) (assoc :node-id (:node-id context))))

                _ (dbg "\n========== ITERATION" (inc (count history)) "==========")
                _ (dbg "node :model =" (:model node))
                _ (dbg "provider =" provider)
                _ (dbg "llm-options =" llm-options)
                _ (dbg "module :outputs =" (:outputs module))
                _ (dbg "module :instructions length =" (count (:instructions module)))
                _ (dbg "inputs :task =" (:task inputs))
                _ (dbg "inputs :inputs-info =" (:inputs-info inputs))
                _ (dbg "calling llm/predict...")
                provider-timeout-ms (max 0
                                         (min (or (:provider-ms timeout-config)
                                              campaign-timeout-ms)
                                              (- iteration-deadline-ms
                                                 (long (campaign-now-ms-fn)))))
                provider-start-ms (long (campaign-now-ms-fn))
                provider-request-options
                (assoc llm-options :timeout-ms provider-timeout-ms)
                legacy-provider-action-id
                (str (:tick-id context) "/" (:node-id context) "/"
                     iteration "/provider/1")
                prepared-prime-model-call
                (when (and checkpointed? (contains? node :fallback-models))
                  (prepare-prime
                   node provider module inputs llm-options
                   (assoc context :researcher-iteration iteration
                                  :legacy-completed-prime-action-id
                                  (when (get @completed-actions legacy-provider-action-id)
                                    legacy-provider-action-id))))
                provider-action-id
                (or (:logical-action-id prepared-prime-model-call)
                    (when (get @completed-actions legacy-provider-action-id) legacy-provider-action-id)
                (if (contains? node :fallback-models)
                  (logic/logical-id (assoc context :researcher-iteration iteration
                                                             :llm-provider provider)
                                               :prime (:name node) module inputs node llm-options)
                  (logic/provider-logical-action-identity
                 {:tick-id (:tick-id context)
                  :node-id (:node-id context)
                  :iteration-index iteration
                  :provider (encode-effect-value provider)
                  :model (encode-effect-value (:model node))
                  :module (encode-effect-value module)
                  :inputs (encode-effect-value inputs)
                  :options (encode-effect-value provider-request-options)})))
                provider-attempt-ordinal (get iteration-attempts iteration 0)
                provider-attempt-id
                (logic/attempt-identity
                 provider-action-id ownership-epoch provider-attempt-ordinal)
                completed-provider-action
                (get @completed-actions provider-action-id)
                completed-provider-envelope (:result completed-provider-action)
                completed-provider-result
                (if (and (map? completed-provider-envelope)
                         (contains? completed-provider-envelope :provider-result))
                  (:provider-result completed-provider-envelope)
                  completed-provider-envelope)
                invoke-provider
                #(predict
                   node provider module inputs
                   (cond-> provider-request-options checkpointed?
                     (assoc :orc/idempotency-key provider-action-id))
                   (assoc context :researcher-iteration iteration
                                  :effect-attempt-ordinal provider-attempt-ordinal
                                  :model-configuration-options llm-options
                                  :prepared-prime-model-call prepared-prime-model-call
                                  :execution-deadline-ms iteration-deadline-ms)
                   :prime provider-action-id)
                provider-claim-result
                (when (and checkpointed?
                           (nil? completed-provider-action)
                           (pos? provider-timeout-ms)
                           (:claim-researcher-effect! context))
                  ((:claim-researcher-effect! context)
                   {:iteration-index iteration
                    :logical-action-identity provider-action-id
                    :attempt-identity provider-attempt-id
                    :attempt-ordinal provider-attempt-ordinal
                    :kind :provider}))
                provider-call (if completed-provider-action
                                {:value completed-provider-result
                                 :replayed? true}
                                (cond
                                  (not (pos? provider-timeout-ms))
                                  {:timeout? true}

                                  (:cognitect.anomalies/category
                                   provider-claim-result)
                                  {:claim-conflict? true
                                   :claim-result provider-claim-result}

                                  (or checkpointed? (seq timeout-config))
                                  (logic/bounded-call
                                   (logic/provider-classification-deadline-ms
                                    provider-timeout-ms)
                                   invoke-provider)

                                  :else
                                  (try {:value (invoke-provider)}
                                       (catch Throwable t {:throwable t}))))
                provider-latency-ms
                (or (when (and (map? completed-provider-envelope)
                               (contains? completed-provider-envelope
                                          :provider-latency-ms))
                      (:provider-latency-ms completed-provider-envelope))
                    (- (long (campaign-now-ms-fn)) provider-start-ms))
                llm-result (cond
                             (:claim-conflict? provider-call)
                             {:code nil
                              :error "Researcher provider claim conflicted"
                              :ownership-conflict? true}

                             (:timeout? provider-call)
                             {:code nil
                              :error "Provider call deadline exceeded"
                              :timeout-kind :provider}

                             (:throwable provider-call)
                             (let [e (:throwable provider-call)]
                               (dbg "llm/predict EXCEPTION:" (.getMessage e))
                               (cond-> (merge {:code nil :error (.getMessage e)}
                                              (select-keys (ex-data e) [:usage :failure-kind :provider-evidence :model-fallback
                                                                        :model-call-in-flight?]))
                                 (and (not (:model-fallback (ex-data e))) (logic/transport-timeout? e))
                                 (assoc :timeout-kind :provider)))

                             :else (:value provider-call))
                durable-provider-envelope
                (logic/encode-checkpoint-value
                 checkpoint-codecs
                 {:provider-result llm-result
                  :provider-latency-ms provider-latency-ms})
                provider-completion-result
                (when (and checkpointed?
                           (not (:replayed? provider-call))
                           (not (:claim-conflict? provider-call))
                           (not (:timeout? provider-call))
                           (not (:throwable provider-call))
                           (:complete-researcher-effect! context))
                  ((:complete-researcher-effect! context)
                   {:logical-action-identity provider-action-id
                    :attempt-identity provider-attempt-id
                    :result durable-provider-envelope}))
                _ (when (:cognitect.anomalies/category provider-completion-result)
                    (throw (ex-info "Researcher provider outcome lost ownership"
                                    {:completion-result provider-completion-result
                                     :logical-action-identity provider-action-id
                                     :attempt-identity provider-attempt-id})))
                _ (when (and checkpointed?
                             (not (:replayed? provider-call))
                             (not (:claim-conflict? provider-call))
                             (not (:timeout? provider-call))
                             (not (:throwable provider-call)))
                    (swap! completed-actions assoc provider-action-id
                           {:status :completed
                            :result {:provider-result llm-result
                                     :provider-latency-ms provider-latency-ms}}))
                _ (when (and checkpointed?
                             (not (:replayed? provider-call))
                             (not (:claim-conflict? provider-call))
                             (not (:timeout? provider-call))
                             (not (:throwable provider-call))
                             persist-action!)
                    (persist-action! {:action-id provider-action-id
                                      :action-kind :provider
                                      :iteration iteration
                                      :result durable-provider-envelope}))
                _ (dbg "llm/predict returned")
                _ (dbg "llm-result keys:" (keys llm-result))
                _ (when (and debug? (:usage llm-result))
                    (dbg "usage:" (:usage llm-result)))
                _ (dbg ":code type:" (type (:code llm-result)))
                _ (dbg ":code nil?:" (nil? (:code llm-result)))
                _ (dbg "[:outputs :code] nil?:" (nil? (get-in llm-result [:outputs :code])))
                _ (dbg "[:outputs] keys:" (keys (:outputs llm-result)))
                _ (when debug?
                    (dbg "FULL llm-result:" (pr-str llm-result)))
                _ (when (and debug? (get-in llm-result [:outputs :code]))
                    (dbg "GENERATED CODE (full):" (get-in llm-result [:outputs :code])))
                _ (dbg ":code blank?:" (if (string? (:code llm-result))
                                         (str/blank? (:code llm-result))
                                         "N/A"))
                _ (when (and debug? (string? (:code llm-result)))
                    (dbg ":code length:" (count (:code llm-result)))
                    (dbg ":code (full):" (:code llm-result)))
                _ (dbg "========================================\n")
                code (let [raw (or (:code llm-result) (get-in llm-result [:outputs :code]))]
                       (cond
                         (string? raw)
                         (-> raw
                             (str/replace #"^```(?:clojure|clj|edn)?\s*\n?" "")
                             (str/replace #"\n?```\s*$" "")
                             str/trim)

                         (some? raw)
                         (pr-str raw)

                         :else nil))
                _ (reset! current-generated-code-hash
                          (when (and (string? code) (not (str/blank? code)))
                            (logic/generated-code-hash code)))

                reasoning (or (:reasoning llm-result)
                              (get-in llm-result [:outputs :reasoning]))
                _ (when (string? reasoning)
                    (swap! sandbox-vars update :iteration-reasonings
                           (fnil conj []) reasoning))

                _ (when (and (string? code) (not (str/blank? code)))
                    (logic/emit! (:tick-id context)
                                     (cond-> {:orc.stream/type :rlm-code-generated
                                              :iteration (inc iteration)
                                              :code (logic/truncate-value code)}
                                       (string? reasoning) (assoc :reasoning (logic/truncate-value reasoning))
                                       (:sheet-id context) (assoc :sheet-id (:sheet-id context))
                                       (:node-id context) (assoc :node-id (:node-id context)))))

                _ (when-let [u (logic/normalize-usage (:usage llm-result))]
                    (swap! total-usage
                           (fn [acc]
                             (-> {:prompt-tokens (+ (:prompt-tokens acc 0) (:prompt-tokens u))
                                  :completion-tokens (+ (:completion-tokens acc 0) (:completion-tokens u))
                                  :total-tokens (+ (:total-tokens acc 0) (:total-tokens u))}
                                 (cond-> (:cost acc) (assoc :cost (:cost acc)))
                                 (logic/add-cost (:usage llm-result))))))]

            (cond
                (:model-call-in-flight? llm-result)
                {:status :running :researcher-frontier-conflict? true}

                (str/blank? code)
                (if-let [terminal-error (or (:error llm-result)
                                            (when checkpointed?
                                              (some-> (:throwable provider-call)
                                                      str))
                                            (when-not recursive-mode?
                                              "LLM did not generate code"))]
                  (if-let [timeout-kind (:timeout-kind llm-result)]
                    (retry-result iteration history timeout-kind terminal-error
                                  attempt-start-ms)
                    (if checkpointed?
                      (let [failure-history
                            (conj history
                                  (cond-> {:error terminal-error
                                           :error-class "provider-failure"}
                                    (string? reasoning)
                                    (assoc :reasoning reasoning)))
                            terminal-result
                            (merge {:status :failure
                                    :error terminal-error
                                    :iterations history
                                    :duration-ms (- (long (campaign-now-ms-fn)) campaign-started-at-ms)
                                    :usage @total-usage}
                                   (select-keys llm-result [:failure-kind :provider-evidence :model-fallback]))]
                        (persist-terminal-result iteration failure-history
                                                 terminal-result
                                                 attempt-start-ms))
                      (merge {:status :failure
                              :error terminal-error
                              :iterations history
                              :duration-ms (- (long (campaign-now-ms-fn)) campaign-started-at-ms)
                              :usage @total-usage}
                             (select-keys llm-result [:failure-kind :provider-evidence :model-fallback]))))
                  (let [next-history (conj history
                                           {:code code
                                            :reasoning reasoning
                                            :provider-latency-ms provider-latency-ms
                                            :provider-usage (logic/normalize-usage (:usage llm-result))
                                            :result nil
                                            :stdout nil
                                            :error "LLM did not generate code"
                                            :vars-created []})]
                    (or (checkpoint-result (inc iteration) next-history
                                           attempt-start-ms)
                        (recur (inc iteration) next-history))))

                :else
                (let [vars-before (set (keys @sandbox-vars))
                    bounded-iteration? (or checkpointed? (contains? timeout-config :iteration-ms))
                    iteration-sandbox-vars (if bounded-iteration?
                                             (atom @sandbox-vars)
                                             sandbox-vars)
                    rlm-ctx (build-rlm-context
                            {:provider provider
                             :model-call-context (assoc context :researcher-iteration iteration
                                                              :effect-attempt-ordinal (get iteration-attempts iteration 0)
                                                              :execution-deadline-ms iteration-deadline-ms)
                             :model-defaults (logic/subcall-defaults node)
                             :blackboard blackboard
                             :declared-writes declared-writes
                             :optional-writes optional-writes
                             :call-tool-fn call-tool-fn
                             :mcp-tools mcp-tools
                             :browser-tools browser-tools
                             :sandbox-vars iteration-sandbox-vars
                             :recursive? recursive-mode?
                             :event-store (:event-store context)
                             :tenant-id (:tenant-id context)
                             :command-registry (:command-registry context)
                             :cache (:cache context)
                             :sheet-id (:sheet-id context)
                             :tick-id (:tick-id context)
                             :node-id (:node-id context)
                             :researcher-iteration @current-iteration
                             :generated-code-hash @current-generated-code-hash
                             :researcher-ownership-epoch ownership-epoch
                             :effect-attempt-ordinal
                             (get iteration-attempts @current-iteration 0)
                             :encode-researcher-effect-value
                             encode-effect-value
                             :claim-researcher-effect!
                             (:claim-researcher-effect! context)
                             :complete-researcher-effect!
                             (:complete-researcher-effect! context)
                             :completed-researcher-effects completed-actions
                             :reserve-llm-call! (:reserve-llm-call! context)
                             :reserve-provider-call!
                             (:reserve-provider-call! context)
                             :durable-source-required? checkpointed?})
                    sandbox-call (if bounded-iteration?
                                   (logic/bounded-call
                                    (max 0 (- iteration-deadline-ms
                                              (long (campaign-now-ms-fn))))
                                    #(logic/execute-rlm-code rlm-ctx code))
                                   {:value (logic/execute-rlm-code rlm-ctx code)})
                    exec-result (cond
                                  @checkpoint-tool-timeout
                                  {:error "Researcher tool deadline exceeded before dispatch"
                                   :timeout-kind :iteration}
                                  @checkpoint-tool-violation
                                  {:error (str "Tool " @checkpoint-tool-violation
                                               " is not declared :checkpoint-safe? for resumable execution")}
                                  (:timeout? sandbox-call)
                                  {:error "Researcher iteration deadline exceeded"
                                   :timeout-kind :iteration}
                                  (:throwable sandbox-call)
                                  {:error (.getMessage ^Throwable (:throwable sandbox-call))}
                                  :else (:value sandbox-call))
                    _ (when (and bounded-iteration? (not (:timeout? sandbox-call)))
                        (reset! sandbox-vars @iteration-sandbox-vars))
                    vars-after (set (keys @sandbox-vars))
                    new-vars (clojure.set/difference vars-after vars-before)
                    updated-vars (into []
                                       (filter #(not= (get sandbox-before-iteration %)
                                                      (get @sandbox-vars %)))
                                       (clojure.set/intersection
                                        (set (keys sandbox-before-iteration))
                                        vars-after))
                    _ (doseq [k new-vars]
                        (swap! var-creation-times assoc k (inc iteration)))
                    final-output (:final-output exec-result)
                    profile-result (if (some? final-output)
                                     final-output
                                     (:raw-result exec-result))
                    history-entry
                    (cond-> {:code code
                             :reasoning reasoning
                             :provider-latency-ms provider-latency-ms
                             :provider-usage (logic/normalize-usage (:usage llm-result))
                             :result (:result exec-result)
                             :stdout (:stdout exec-result)
                             :error-class (:error-class exec-result)
                             :error (:error exec-result)
                             :vars-created (vec new-vars)
                             :vars-updated (vec updated-vars)}
                      (some? profile-result)
                      (assoc :result-profile (logic/profile-value profile-result))

                      (some? (:stdout exec-result))
                      (assoc :stdout-profile
                             (logic/profile-value (:stdout exec-result))))
                    new-history (conj history history-entry)
                    _ (logic/emit! (:tick-id context)
                                       (cond-> {:orc.stream/type :rlm-sandbox-completed
                                                :iteration (inc iteration)
                                                :final? (some? final-output)}
                                         (some? (:result exec-result))
                                         (assoc :result (logic/truncate-value (:result exec-result)))
                                         (:stdout exec-result)
                                         (assoc :stdout (logic/truncate-value (:stdout exec-result)))
                                         (:error exec-result)
                                         (assoc :error (str (:error exec-result)))
                                         (seq new-vars)
                                         (assoc :vars-created (vec new-vars))
                                         (:sheet-id context) (assoc :sheet-id (:sheet-id context))
                                         (:node-id context) (assoc :node-id (:node-id context))))
                    sub-llm-usage (:sub-llm-usage exec-result)
                    _ (when (and sub-llm-usage (pos? (:total-tokens sub-llm-usage 0)))
                        (swap! total-usage
                               (fn [acc]
                                 {:prompt-tokens (+ (:prompt-tokens acc 0) (:prompt-tokens sub-llm-usage 0))
                                  :completion-tokens (+ (:completion-tokens acc 0) (:completion-tokens sub-llm-usage 0))
                                  :total-tokens (+ (:total-tokens acc 0) (:total-tokens sub-llm-usage 0))})))
                    _ (when debug?
                        (dbg "exec-result :error:" (:error exec-result))
                        (dbg "exec-result :final-output:" final-output)
                        (when (pos? (:total-tokens sub-llm-usage 0))
                          (dbg "sub-llm-usage:" sub-llm-usage)))]

                (cond
                  (= :iteration (:timeout-kind exec-result))
                  (retry-result iteration history :iteration
                                "Researcher iteration deadline exceeded"
                                attempt-start-ms)

                  (and bounded-iteration?
                       (>= (long (campaign-now-ms-fn)) iteration-deadline-ms))
                  (retry-result iteration history :iteration
                                "Researcher iteration deadline exceeded"
                                attempt-start-ms)

                  (and (:error exec-result)
                       (or (str/includes? (str (:error exec-result)) "Security")
                           (str/includes? (str (:error exec-result))
                                         "not declared :checkpoint-safe?")))
                  {:status :failure
                   :error (:error exec-result)
                   :iterations new-history
                   :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
                   :usage @total-usage}

                  (and (:error exec-result)
                       (= :model-plan-incompatible (get-in exec-result [:error-data :failure-kind])))
                  {:status :failure
                   :failure-kind :model-plan-incompatible
                   :reason (get-in exec-result [:error-data :reason])
                   :error (:error exec-result)
                   :iterations new-history
                   :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
                   :usage @total-usage}

                  (:error exec-result)
                  (let [
                        _ (when (and recursive-mode? checkpointed?)
                            (swap! sandbox-vars
                                   (fn [vars]
                                     (merge (dissoc vars :generated-tree
                                                    :generated-tree-raw
                                                    :generated-tree-source)
                                            (select-keys sandbox-before-iteration
                                                         [:generated-tree
                                                          :generated-tree-raw
                                                          :generated-tree-source])))))
                        error-msg (:error exec-result)
                        available-vars (concat (keys blackboard) (keys @sandbox-vars))
                        enhanced-error (logic/format-error-with-suggestions error-msg available-vars)
                        error-history (conj history
                                           {:code code
                                            :reasoning reasoning
                                            :provider-latency-ms provider-latency-ms
                                            :provider-usage (logic/normalize-usage (:usage llm-result))
                                            :result nil
                                            :stdout (:stdout exec-result)
                                            :error-class (:error-class exec-result)
                                            :error enhanced-error
                                            :error-data (:error-data exec-result)
                                            :vars-created []})]
                    (or (checkpoint-result (inc iteration) error-history
                                           attempt-start-ms)
                        (recur (inc iteration) error-history)))

                  (and recursive-mode?
                       final-output
                       (contains? @sandbox-vars :generated-tree))
                  (let [guard-error (str "You called (final! ...) in the same iteration as "
                                         "(emit-tree! ...). The tree executes AFTER your code "
                                         "returns, so its outputs were not available to your "
                                         "final!. Neither was applied: the tree was not executed "
                                         "and the final! was discarded. Either call "
                                         "(emit-tree! ...) alone this iteration and inspect "
                                         ":tree-results next iteration, or call (final! ...) alone.")
                        _ (swap! sandbox-vars dissoc
                                 :generated-tree
                                 :generated-tree-raw
                                 :generated-tree-source)
                        guard-history (conj history
                                            {:code code
                                             :reasoning reasoning
                                             :provider-latency-ms provider-latency-ms
                                             :provider-usage (logic/normalize-usage (:usage llm-result))
                                             :result nil
                                             :stdout (:stdout exec-result)
                                             :error guard-error
                                             :vars-created (vec (remove #{:generated-tree
                                                                          :generated-tree-raw
                                                                          :generated-tree-source}
                                                                        new-vars))})]
                    (or (checkpoint-result (inc iteration) guard-history
                                           attempt-start-ms)
                        (recur (inc iteration) guard-history)))

                  final-output
                  (let [total-elapsed (- (long (campaign-now-ms-fn)) campaign-started-at-ms)
                        generated-tree-raw (:generated-tree-raw @sandbox-vars)
                        generated-tree-source (:generated-tree-source @sandbox-vars)
                        iteration-reasonings (:iteration-reasonings @sandbox-vars)
                        _ (when debug?
                            (dbg "RETURN PATH: final! branch; sandbox-vars keys:" (keys @sandbox-vars))
                            (dbg "RETURN PATH: :iteration-reasonings count:"
                                 (count (or iteration-reasonings []))))]
                    (persist-terminal-result
                     iteration
                     new-history
                     (cond-> {:status :success
                              :outputs final-output
                              :final-answer final-output
                              :iterations new-history
                              :duration-ms total-elapsed
                              :usage @total-usage
                              :iteration-reasonings (vec (or iteration-reasonings []))
                              :cumulative-tree-ms @cumulative-tree-ms
                              :cumulative-thinking-ms (max 0 (- total-elapsed @cumulative-tree-ms))}
                       generated-tree-raw
                       (assoc :generated-tree-raw generated-tree-raw)

                       generated-tree-source
                       (assoc :generated-tree-source generated-tree-source))
                     attempt-start-ms))

                  (contains? @sandbox-vars :generated-tree)
                  (let [
                        sub-model (or (get rlm-config :sub-model)
                                      (:sub-model node))
                        generated-tree (-> (:generated-tree @sandbox-vars)
                                           (logic/inject-sub-model sub-model)
                                           (logic/inject-subcall-config (logic/subcall-defaults node))
                                           (logic/inject-tool-caller-fn (:tool-caller-fn node)
                                                                 (:tool-contracts node)))
                        generated-tree-raw (:generated-tree-raw @sandbox-vars)
                        generated-tree-source (:generated-tree-source @sandbox-vars)
                        _ (when debug?
                            (when sub-model
                              (println "\n[DEBUG RLM] Sub-model injected into :llm nodes lacking :model:" sub-model))
                            (println "\n[DEBUG RLM] Phase 2 triggered - emit-tree! was called")
                            (println "[DEBUG RLM] Generated tree (raw S-expr):")
                            (pprint/pprint generated-tree-raw)
                            (println "\n[DEBUG RLM] Generated tree (canonical ORC DSL):")
                            (pprint/pprint generated-tree))
                        phase1-elapsed-ms (- (long (campaign-now-ms-fn)) campaign-started-at-ms)
                        base-budget (logic/resolve-phase2-budget
                                     {:node node
                                      :parent-timeout-ms (:parent-timeout-ms context)
                                      :phase1-elapsed-ms phase1-elapsed-ms})
                        phase2-remaining-ms
                        (max 0 (min (:remaining-ms base-budget)
                                    (or (:phase2-ms timeout-config) Long/MAX_VALUE)
                                    (- iteration-deadline-ms (long (campaign-now-ms-fn)))
                                    (- campaign-deadline-ms (long (campaign-now-ms-fn)))))
                        budget (assoc base-budget
                                      :remaining-ms phase2-remaining-ms
                                      :exhausted? (zero? phase2-remaining-ms)
                                      :deadline-sources
                                      {:phase2-ms (:phase2-ms timeout-config)
                                       :iteration-deadline-ms iteration-deadline-ms
                                       :campaign-deadline-ms campaign-deadline-ms})
                        _ (when debug?
                            (println "\n[DEBUG RLM] Phase 2 budget:" budget))]
                    (if (:exhausted? budget)
                      {:status :failure
                       :error (str "Budget exhausted in Phase 1 ("
                                   phase1-elapsed-ms "ms elapsed of "
                                   (:total-budget-ms budget) "ms "
                                   "[source=" (name (:source budget)) "]; "
                                   "no time left for Phase 2)")
                       :iterations new-history
                       :duration-ms phase1-elapsed-ms
                       :phase1-elapsed-ms phase1-elapsed-ms
                       :usage @total-usage
                       :budget budget}
                      (let [
                            phase2-vars (dissoc @sandbox-vars
                                                :generated-tree
                                                :generated-tree-raw
                                                :generated-tree-source
                                                :iteration-reasonings
                                                :tree-results)
                            _ (when debug?
                                (println "\n[DEBUG RLM] Phase 2 sandbox vars:" (keys phase2-vars)))
                            phase2-action-id
                            (logic/logical-action-identity
                             {:tick-id (:tick-id context)
                              :node-id (:node-id context)
                              :iteration-index iteration
                              :generated-code-hash @current-generated-code-hash
                              :kind :generated-child
                              :target :generated-tree
                              :arguments
                              (encode-effect-value
                               {:generated-tree-source generated-tree-source
                                :inputs phase2-vars})})
                            legacy-phase2-action-id
                            (str (:tick-id context) "/" (:node-id context) "/"
                                 iteration "/phase2/1")
                            phase2-attempt-ordinal (get iteration-attempts iteration 0)
                            phase2-attempt-id
                            (logic/attempt-identity
                             phase2-action-id ownership-epoch phase2-attempt-ordinal)
                            completed-phase2-action
                            (or (get @completed-actions phase2-action-id)
                                (get @completed-actions legacy-phase2-action-id))
                            phase2-claim-result
                            (when (and checkpointed?
                                       (nil? completed-phase2-action)
                                       (:claim-researcher-effect! context))
                              ((:claim-researcher-effect! context)
                               {:iteration-index iteration
                                :logical-action-identity phase2-action-id
                                :attempt-identity phase2-attempt-id
                                :attempt-ordinal phase2-attempt-ordinal
                                :kind :generated-child}))
                            _ (when (:cognitect.anomalies/category phase2-claim-result)
                                (throw (ex-info "Researcher generated-child claim conflicted"
                                                {:claim-result phase2-claim-result
                                                 :logical-action-identity phase2-action-id
                                                 :attempt-identity phase2-attempt-id})))
                            phase2-result (if completed-phase2-action
                                            (let [result (:result completed-phase2-action)]
                                              (when-let [parent-tick-id (:tick-id context)]
                                                (when-let [child-tick-id (:trace-id result)]
                                                  (logic/link-child! parent-tick-id
                                                                         child-tick-id)))
                                              result)
                                            (try
                                            (when-let [required-keys
                                                       (seq (:preflight-required-schema-keys
                                                             @sandbox-vars))]
                                              (let [declared (set (keys
                                                                   (logic/extract-key-schemas
                                                                    generated-tree)))
                                                    used (set (logic/extract-all-keys
                                                               generated-tree))
                                                    corrected (clojure.set/intersection declared used)
                                                    still-missing (vec (remove corrected required-keys))]
                                                (when (seq still-missing)
                                                  (throw (ex-info
                                                          (str "The prior generated tree was rejected before execution. "
                                                               "Re-emit its intended work with explicit schemas for "
                                                               still-missing)
                                                          {:blackboard-keys still-missing})))))
                                            (execute-tree
                                              generated-tree
                                              (assoc context
                                                     :researcher-iteration iteration)
                                              {:sandbox-vars phase2-vars
                                               :generated-tree-raw generated-tree-raw
                                               :generated-tree-source generated-tree-source
                                               :blackboard (reduce-kv
                                                             (fn [acc k entry]
                                                               (if (contains? entry :value)
                                                                 (assoc acc k (:value entry))
                                                                 acc))
                                                             {}
                                                             blackboard)
                                               :blackboard-schemas (reduce-kv
                                                                     (fn [acc k entry]
                                                                       (if-let [s (:schema entry)]
                                                                         (assoc acc k s)
                                                                         acc))
                                                                     {}
                                                                     blackboard)
                                               :stable-tick-id
                                               (when checkpointed?
                                                 (java.util.UUID/nameUUIDFromBytes
                                                  (.getBytes
                                                   phase2-action-id
                                                   "UTF-8")))
                                               :timeout-ms (:remaining-ms budget)})
                                            (catch Exception e
                                              (println "[DEBUG RLM] Phase 2 execution ERROR:" (.getMessage e))
                                              (.printStackTrace e)
                                              (let [diagnostic (logic/from-exception e)]
                                                (cond-> {:status :failure
                                                         :preflight-rejected? true
                                                         :missing-schema-keys (:blackboard-keys (ex-data e))
                                                         :error (str "Phase 2 execution failed: " (.getMessage e))}
                                                  diagnostic
                                                  (assoc :setup-diagnostic diagnostic
                                                         :error (logic/render diagnostic)))))))
                            durable-phase2-result
                            (select-keys phase2-result
                                         [:status :outputs :usage :duration-ms :error :trace-id
                                          :sheet-id :breakdown :phase1-elapsed-ms
                                          :phase2-elapsed-ms :block-payload :preflight-rejected?
                                          :missing-schema-keys :setup-diagnostic])
                            phase2-completion-result
                            (when (and checkpointed?
                                       (nil? completed-phase2-action)
                                       (contains? #{:success :partial :failure :blocked}
                                                  (:status phase2-result))
                                       (:complete-researcher-effect! context))
                              ((:complete-researcher-effect! context)
                               {:logical-action-identity phase2-action-id
                                :attempt-identity phase2-attempt-id
                                :result (logic/encode-checkpoint-value
                                         checkpoint-codecs
                                         durable-phase2-result)}))
                            _ (when (:cognitect.anomalies/category
                                     phase2-completion-result)
                                (throw (ex-info "Researcher generated-child outcome lost ownership"
                                                {:completion-result
                                                 phase2-completion-result
                                                 :logical-action-identity phase2-action-id
                                                 :attempt-identity phase2-attempt-id})))
                            _ (when (and checkpointed?
                                         (nil? completed-phase2-action)
                                         (contains? #{:success :partial :failure :blocked}
                                                    (:status phase2-result)))
                                (swap! completed-actions assoc phase2-action-id
                                       {:status :completed
                                        :result durable-phase2-result}))
                            _ (when (and checkpointed?
                                         (nil? completed-phase2-action)
                                         (contains? #{:success :partial :failure :blocked}
                                                    (:status phase2-result))
                                         persist-action!)
                                (persist-action! {:action-id phase2-action-id
                                                  :action-kind :phase2
                                                  :iteration iteration
                                                  :result (logic/encode-checkpoint-value
                                                           checkpoint-codecs
                                                           durable-phase2-result)}))
                            _ (if (:preflight-rejected? phase2-result)
                                (swap! sandbox-vars update
                                       :preflight-required-schema-keys
                                       (fnil into #{})
                                       (:missing-schema-keys phase2-result))
                                (swap! sandbox-vars dissoc
                                       :preflight-required-schema-keys))
                            _ (when (or (= :timeout (:status phase2-result))
                                        (:wedged? phase2-result))
                                (let [child-tick-id (:trace-id phase2-result)
                                      child-sheet-id (:sheet-id phase2-result)]
                                  (when (and child-tick-id child-sheet-id)
                                    (cp/process-command
                                      (assoc context :command
                                             {:command/id (random-uuid)
                                              :command/timestamp (time/now)
                                              :command/name :sheet/cancel-tick
                                              :sheet-id child-sheet-id
                                              :tick-id child-tick-id}))
                                    (Thread/sleep 500))))
                            phase1-duration phase1-elapsed-ms
                        _ (when debug?
                            (println "\n[DEBUG RLM] Phase 2 result:")
                            (println "  status:" (:status phase2-result))
                            (println "  error:" (:error phase2-result))
                            (println "  outputs keys:" (keys (:outputs phase2-result)))
                            (println "  duration-ms:" (:duration-ms phase2-result)))]
                    (cond
                      (and (= :timeout (:status phase2-result))
                           (or checkpointed? (not recursive-mode?)))
                      (if checkpointed?
                        (do
                          (reset! sandbox-vars sandbox-before-iteration)
                          (reset! var-creation-times creation-times-before-iteration)
                          (retry-result iteration history :phase2
                                        (or (:error phase2-result)
                                            "Phase-2 child deadline exceeded")
                                        attempt-start-ms))
                        (assoc phase2-result
                               :generated-tree-raw generated-tree-raw
                               :generated-tree-source generated-tree-source
                               :iterations new-history
                               :usage @total-usage
                               :duration-ms (+ phase1-elapsed-ms
                                               (or (:duration-ms phase2-result) 0))
                               :phase1-elapsed-ms phase1-elapsed-ms
                               :phase2-elapsed-ms (or (:duration-ms phase2-result)
                                                      (:remaining-ms budget))
                               :phase2-tick-id (:trace-id phase2-result)))

                      (= :blocked (:status phase2-result))
                      (persist-terminal-result
                       iteration
                       new-history
                       {:status :blocked
                        :block-payload (:block-payload phase2-result)
                        :outputs (:outputs phase2-result)
                        :generated-tree-raw generated-tree-raw
                        :generated-tree-source generated-tree-source
                        :iterations new-history
                        :duration-ms (+ phase1-elapsed-ms
                                        (or (:duration-ms phase2-result) 0))
                        :usage @total-usage
                        :phase2-tick-id (:trace-id phase2-result)}
                       attempt-start-ms)
                      recursive-mode?
                      (let [child-tick-id (:trace-id phase2-result)
                            tenant-id (:tenant-id context)
                            event-store (:event-store context)
                            tick-events (when (and event-store child-tick-id)
                                          (into [] (es/read event-store
                                                     (cond-> {:tags #{[:tick child-tick-id]}}
                                                       tenant-id (assoc :tenant-id tenant-id)))))
                            phase2-result+budget
                            (cond-> phase2-result
                              (= :timeout (:status phase2-result))
                              (assoc :phase2-elapsed-ms (or (:duration-ms phase2-result)
                                                            (:remaining-ms budget))
                                     :budget-remaining-ms 0))
                            child-nodes (when child-tick-id
                                          (rm/get-tick-nodes-by-id
                                           context child-tick-id))
                            child-writes (vec (remove logic/researcher-control-keys (logic/child-declared-writes child-nodes)))
                            produced (completed-child-values context child-tick-id child-nodes tick-events)
                            produced-values (:values produced)
                            feedback-result (assoc phase2-result+budget :outputs produced-values)
                            base-summary (assoc (logic/compute-tree-result-summary
                                                  {:phase2-result feedback-result
                                                   :tick-events tick-events
                                                   :tree-raw generated-tree-raw
                                                   :writes (vec (distinct (concat child-writes (keys produced-values))))})
                                                :source-iteration (inc iteration)
                                                :action-id phase2-action-id
                                                :output-owners (:owners produced)
                                                :scoped-outputs (:scoped-outputs produced)
                                                :protected-writes (vec (distinct (:protected-writes produced))))
                            surviving (apply dissoc produced-values child-writes)
                            summary (cond-> base-summary
                                      (seq surviving)
                                      (assoc :surviving-vars (vec (sort-by pr-str (keys surviving)))))
                            _ (swap! sandbox-vars
                                     (fn [vars]
                                       (-> vars
                                           (dissoc :generated-tree)
                                           (merge produced-values)
                                           (update :tree-results (fnil conj []) summary))))
                            _ (swap! var-creation-times
                                     (fn [times]
                                       (reduce (fn [m k] (if (contains? m k) m (assoc m k (inc iteration))))
                                               times (keys produced-values))))
                            _ (swap! cumulative-tree-ms + (or (:duration-ms phase2-result) 0))
                            p2-usage (:usage phase2-result)
                            _ (when (and p2-usage (pos? (:total-tokens p2-usage 0)))
                                (add-descendant-usage! p2-usage)
                                (swap! total-usage
                                       (fn [acc]
                                         (-> acc
                                             (update :prompt-tokens
                                                     (fnil + 0 0)
                                                     (or (:prompt-tokens p2-usage) 0))
                                             (update :completion-tokens
                                                     (fnil + 0 0)
                                                     (or (:completion-tokens p2-usage) 0))
                                             (update :total-tokens
                                                     (fnil + 0 0)
                                                     (or (:total-tokens p2-usage) 0))
                                             (logic/add-cost p2-usage)))))
                            _ (dbg "\n[DEBUG RLM] Recursive recur — :tree-results entries:"
                                   (count (:tree-results @sandbox-vars))
                                   "summary status:" (:status summary))
                            tree-output-keys (vec (:outputs-keys summary))
                            prior-sandbox-keys (set (keys sandbox-before-iteration))
                            tree-created-keys (remove prior-sandbox-keys tree-output-keys)
                            tree-updated-keys (filter prior-sandbox-keys tree-output-keys)
                            outcome (logic/render-tree-outcome summary)
                            new-history (update new-history
                                                (dec (count new-history))
                                                (fn [entry]
                                                  (let [prior-vars (or (:vars-created entry) [])
                                                        prior-updated (or (:vars-updated entry) [])
                                                        marker-syms #{:generated-tree
                                                                      :generated-tree-raw
                                                                      :generated-tree-source}
                                                        kept (remove marker-syms prior-vars)]
                                                    (-> entry
                                                        (assoc :vars-created
                                                               (vec (distinct (concat kept tree-created-keys))))
                                                        (assoc :vars-updated
                                                               (vec (distinct
                                                                     (concat prior-updated
                                                                             tree-updated-keys))))
                                                        (cond-> (:preflight-rejected? phase2-result)
                                                          (assoc :error (:error phase2-result)))
                                                        (assoc :generated-tree
                                                               generated-tree-raw
                                                               :generated-tree-source
                                                               generated-tree-source
                                                               :child-trace-id child-tick-id
                                                               :child-outcome (select-keys summary
                                                                                           [:status :elapsed-ms
                                                                                            :nodes-succeeded
                                                                                            :nodes-failed
                                                                                            :nodes-timed-out
                                                                                            :error
                                                                                            :setup-diagnostic])
                                                               :tree-outcome outcome)))))]
                        (or (checkpoint-result (inc iteration) new-history
                                               attempt-start-ms)
                            (recur (inc iteration) new-history)))
                      :else
                      (let [p1-usage @total-usage
                            p2-usage (:usage phase2-result)
                            p2-by-node (:by-node p2-usage)
                            _ (when p2-usage (add-descendant-usage! p2-usage))
                            combined-usage (cond-> {:prompt-tokens (+ (:prompt-tokens p1-usage 0)
                                                                     (:prompt-tokens p2-usage 0))
                                                    :completion-tokens (+ (:completion-tokens p1-usage 0)
                                                                          (:completion-tokens p2-usage 0))
                                                    :total-tokens (+ (:total-tokens p1-usage 0)
                                                                     (:total-tokens p2-usage 0))}
                                             (or (logic/usage-cost p1-usage) (logic/usage-cost p2-usage))
                                             (assoc :cost (+ (or (logic/usage-cost p1-usage) 0)
                                                             (or (logic/usage-cost p2-usage) 0)))
                                             (seq p2-by-node) (assoc :by-node p2-by-node))]
                        {:status (:status phase2-result)
                         :outputs (:outputs phase2-result)
                         :generated-tree-raw generated-tree-raw
                         :generated-tree-source generated-tree-source
                         :iteration-reasonings (:iteration-reasonings @sandbox-vars)
                         :iterations new-history
                         :duration-ms (+ phase1-duration (or (:duration-ms phase2-result) 0))
                         :usage combined-usage
                         :breakdown {:phase1 p1-usage :phase2 p2-usage}
                         :phase2-duration-ms (:duration-ms phase2-result)
                         :phase2-error (:error phase2-result)
                         :phase1-elapsed-ms phase1-duration
                         :phase2-elapsed-ms (cond
                                              (number? (:duration-ms phase2-result))
                                              (:duration-ms phase2-result)
                                              (= :timeout (:status phase2-result))
                                              (:remaining-ms budget)
                                              :else nil)
                         :phase2-tick-id (:trace-id phase2-result)
                         :budget budget})))))

                  (or (logic/contains-final-answer? (:result exec-result))
                      (logic/contains-final-answer? (:stdout exec-result)))
                  (let [final-answer (or (logic/extract-final-answer (:result exec-result))
                                         (logic/extract-final-answer (:stdout exec-result)))
                        outputs (if (map? final-answer)
                                  final-answer
                                  {(first declared-writes) final-answer})]
                    {:status :success
                     :outputs outputs
                     :final-answer final-answer
                     :iterations new-history
                     :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
                     :usage @total-usage})

                  :else
                  (or (checkpoint-result (inc iteration) new-history
                                         attempt-start-ms)
                      (recur (inc iteration) new-history))))))))

      (catch Exception e
        (merge {:status :failure
                :error (.getMessage e)
                :duration-ms (- (System/currentTimeMillis) campaign-started-at-ms)
                :usage @total-usage}
               (select-keys (ex-data e) [:failure-kind :provider-evidence :model-fallback])))) result
      (if (:usage result)
        (assoc result :own-usage
               (let [d @descendant-usage
                     tokens (into {}
                                  (map (fn [k]
                                         [k (max 0 (- (or (get (:usage result) k) 0)
                                                      (or (get d k) 0)))]))
                                  [:prompt-tokens :completion-tokens :total-tokens])]
                 (if-let [total-cost (logic/usage-cost (:usage result))]
                   (assoc tokens :cost (max 0 (- total-cost (or (logic/usage-cost d) 0))))
                   tokens)))
        result))))

;; =============================================================================
;; Retry Logic
;; =============================================================================

(defn execute-repl-researcher
  "Execute a repl-researcher node using iterative LLM+SCI code execution.

   This implements the RLM (Recursive Language Model) pattern where:
   1. LLM generates Clojure code to call MCP tools
   2. Code executes in a safe SCI sandbox
   3. Results feed back to LLM for next iteration
   4. Converges when FINAL_ANSWER is detected

   When :rlm is true on the node, uses RLM mode with BT primitives:
   - (llm ...) for sub-LLM calls
   - (final! ...) for validated output capture
   - (get-input ...) for loading input values

   Args:
     node - The repl-researcher node map with :instruction, :reads, :writes, :mcp-tools
     blackboard - Map of key -> {:key, :schema, :value, :version}
     provider - ORC LLM provider keyword
     context - Context map with :call-tool-fn (fn [tool-name args-map] -> result) for MCP tool calls
     options - Optional ORC LLM options map

   Returns:
     {:status :success/:failure
      :outputs {string-key value}
      :iterations [{:code ... :result ... :stdout ...}]
      :final-answer string?
      :error string?
      :duration-ms int
      :usage {:prompt-tokens N :completion-tokens N :total-tokens N}}"
  [node blackboard provider context & {:keys [options] :or {options {}}}]
  (let [execution-options (merge options (:options node))]
    (if (:rlm node)
      (execute-repl-researcher-rlm node blackboard provider context :options execution-options)
    (let [start-time (System/currentTimeMillis)
          max-iterations (or (:max-iterations node) 10)
        mcp-tools (or (:mcp-tools node) [])
        browser-tools (or (:browser-tools node) [])
        call-tool-fn (-> (logic/phase1-call-tool-fn
                          (assoc context :call-tool-fn
                                 (logic/node-call-tool-fn node blackboard context)))
                         (logic/guarded-call-tool-fn (:tool-contracts node)))

        sci-ctx (logic/build-sci-context
                 {:call-tool-fn call-tool-fn
                  :mcp-tools mcp-tools
                  :browser-tools browser-tools})

        bb-metadata (logic/build-blackboard-metadata node blackboard)

        total-usage (atom {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})]

    (try
      (loop [iteration 0
             history []]
        (if (>= iteration max-iterations)
          {:status :failure
           :error "Max iterations reached without FINAL_ANSWER"
           :iterations history
           :duration-ms (- (System/currentTimeMillis) start-time)
           :usage @total-usage}

          (let [
                bb-values (reduce (fn [acc k]
                                    (if-let [entry (get blackboard k)]
                                      (assoc acc (name k) (logic/serialize-for-llm (:value entry)))
                                      acc))
                                  {}
                                  (:reads node))
                processed-instruction (reduce (fn [instr [k v]]
                                                (str/replace instr
                                                             (str "{" k "}")
                                                             (if (string? v) v (pr-str v))))
                                              (:instruction node)
                                              bb-values)
                module (logic/build-code-generation-module
                         (assoc node :instruction processed-instruction)
                         bb-metadata history mcp-tools browser-tools)
                all-tools (concat mcp-tools browser-tools)
                inputs {:task (logic/serialize-for-llm processed-instruction)
                        :context bb-metadata
                        :history (or (logic/build-iteration-history history) "None")
                        :tools (str/join ", " all-tools)}
                llm-options (cond-> (assoc execution-options :validate? false :with-metadata? true)
                                 (:model node) (assoc :model (:model node)))

                llm-result (predict node provider module inputs llm-options
                             (assoc context :researcher-iteration iteration
                                            :execution-deadline-ms (:workflow-deadline-ms context))
                             :prime (logic/logical-id (assoc context :researcher-iteration iteration)
                                                                 :prime (:name node) module inputs node llm-options))
                code (let [raw (or (:code llm-result) (get-in llm-result [:outputs :code]))]
                       (cond
                         (string? raw)
                         (-> raw
                             (str/replace #"^```(?:clojure|clj|edn)?\s*\n?" "")
                             (str/replace #"\n?```\s*$" "")
                             str/trim)

                         (some? raw)
                         (pr-str raw)

                         :else nil))

                _ (when-let [u (logic/normalize-usage (:usage llm-result))]
                    (swap! total-usage
                           (fn [acc]
                             {:prompt-tokens (+ (:prompt-tokens acc 0) (:prompt-tokens u))
                              :completion-tokens (+ (:completion-tokens acc 0) (:completion-tokens u))
                              :total-tokens (+ (:total-tokens acc 0) (:total-tokens u))})))]

            (cond
              (str/blank? code)
              {:status :failure
               :error "LLM did not generate code"
               :iterations history
               :duration-ms (- (System/currentTimeMillis) start-time)
               :usage @total-usage}

              (logic/contains-final-answer? code)
              (let [final-answer (logic/extract-final-answer code)
                    write-keys (:writes node)
                    outputs (if (map? final-answer)
                              (let [extracted
                                    (reduce (fn [acc k]
                                              (let [kw (if (keyword? k) k (keyword k))
                                                    v (or (get final-answer kw)
                                                          (get final-answer (name kw)))]
                                                (cond-> acc (some? v) (assoc kw v))))
                                            {}
                                            write-keys)]
                                (if (seq extracted)
                                  extracted
                                  {(first write-keys) final-answer}))
                              {(first write-keys) final-answer})]
                {:status :success
                 :outputs outputs
                 :final-answer final-answer
                 :iterations (conj history {:code code
                                            :result code
                                            :stdout ""
                                            :error nil})
                 :duration-ms (- (System/currentTimeMillis) start-time)
                 :usage @total-usage})

              :else
              (let [exec-result (logic/sci-sandbox-execute-code sci-ctx code)
                    new-history (conj history
                                      {:code code
                                       :result (:result exec-result)
                                       :stdout (:stdout exec-result)
                                       :error (:error exec-result)})
                    raw-result (when (string? (:raw-result exec-result))
                                 (:raw-result exec-result))
                    result-for-extraction (or raw-result (:result exec-result))]
                (cond
                  (or (logic/contains-final-answer? result-for-extraction)
                      (logic/contains-final-answer? (:stdout exec-result)))
                  (let [final-answer (or (logic/extract-final-answer result-for-extraction)
                                         (logic/extract-final-answer (:stdout exec-result)))
                        write-keys (:writes node)
                        outputs (if (map? final-answer)
                                  (let [extracted (reduce (fn [acc k]
                                                            (let [kw (if (keyword? k) k (keyword k))
                                                                  str-k (name kw)
                                                                  v (or (get final-answer kw)
                                                                        (get final-answer str-k))]
                                                              (if (some? v)
                                                                (assoc acc kw v)
                                                                acc)))
                                                          {}
                                                          write-keys)]
                                    (if (seq extracted)
                                      extracted
                                      {(first write-keys) final-answer}))
                                  {(first write-keys) final-answer})]
                    {:status :success
                     :outputs outputs
                     :final-answer final-answer
                     :iterations new-history
                     :duration-ms (- (System/currentTimeMillis) start-time)
                     :usage @total-usage})

                  (logic/repeated-output? history exec-result)
                  {:status :failure
                   :error "Output repeated - possible infinite loop"
                   :iterations new-history
                   :duration-ms (- (System/currentTimeMillis) start-time)
                   :usage @total-usage}

                  :else
                  (recur (inc iteration) new-history)))))))

      (catch Exception e
        (if (:model-call-in-flight? (ex-data e))
          {:status :running :researcher-frontier-conflict? true}
          (merge {:status :failure
                  :error (.getMessage e)
                  :duration-ms (- (System/currentTimeMillis) start-time)
                  :usage (merge-with + @total-usage (or (logic/normalize-usage (:usage (ex-data e))) {}))}
                 (select-keys (ex-data e) [:failure-kind :provider-evidence :model-fallback])))))))))

;; =============================================================================
;; Main Execution Entry Point
;; =============================================================================

(defn execute-leaf
  "Execute a leaf node based on its executor type.

   Executor types:
   - :ai (default) - ORC LLM AI execution
   - :code - Clojure function execution
   - :tool - Authored-tool invocation (one named tool, reads as arguments)

   Args:
     node - The leaf node map
     blackboard - Map of key -> {:key, :type, :value, :version}
     provider - ORC LLM provider keyword (for :ai executor)
     context - Additional context map (event-store, etc.)

   Returns:
     {:status :success/:failure
      :outputs {string-key value}
      :error string?
      :duration-ms int}"
  [node blackboard provider & {:keys [context options stream] :or {context {} options {}}}]
  (let [executor-type (or (:executor node) :ai)
        retry-config (:retry node)
        execution-options (merge options (:options node)
                                 (select-keys options [:start-provider-attempt!
                                                       :complete-provider-attempt! :model-role-context :model-fallback-callbacks]))
        max-node-attempts (or (get-in retry-config [:max-attempts]) 1)
        node-attempt (atom 0)
        execute-fn (fn []
                     (let [current-node-attempt (swap! node-attempt inc)]
                     (case executor-type
                       :ai (execute-ai node blackboard provider
                                       :options (assoc execution-options
                                                       :node-attempt current-node-attempt
                                                       :max-node-attempts max-node-attempts)
                                       :stream stream)
                       :decision (execute-decision node blackboard provider
                                                   :options (assoc execution-options
                                                                   :node-attempt current-node-attempt
                                                                   :max-node-attempts max-node-attempts))
                       :code (logic/executor-execute-code node blackboard context)
                       :tool (logic/execute-tool node blackboard context)
                       (execute-ai node blackboard provider
                                   :options (assoc execution-options
                                                   :node-attempt current-node-attempt
                                                   :max-node-attempts max-node-attempts)
                                   :stream stream))))]
    (try
      (if retry-config
        (logic/execute-with-retry execute-fn retry-config node)
        (execute-fn))
      (finally
        (logic/clear-node! (:tick-id execution-options) (:id node))))))

(defn- cancel-llm-budget!
  "Commit and publish active/root native cancellation off the model work that
   their delivery interrupts. The owned task is synchronously joined."
  [context sheet-id tick-id exceeded]
  (let [reason (str "LLM call budget exceeded: " (:current exceeded) "/" (:budget exceeded))
        owners (cond-> [{:sheet-id sheet-id :tick-id tick-id}]
                 (and (:root-sheet-id exceeded) (:root-tick-id exceeded)
                      (not= tick-id (:root-tick-id exceeded)))
                 (conj {:sheet-id (:root-sheet-id exceeded) :tick-id (:root-tick-id exceeded)}))
        claimed (filterv #(logic/claim-budget-cancellation! (:tick-id %)) owners)]
    (if (seq claimed)
      (or (logic/run-owned-cancellation!
            (fn []
              (let [anomalies
                    (into [] (keep (fn [{:keys [sheet-id tick-id]}]
                             (if (rm/is-tick-or-ancestor-cancelled? context tick-id)
                               (do (swap! logic/budget-cancellation-claims dissoc tick-id) nil)
                               (let [result (cp/process-command
                                              (assoc context :command
                                                     {:command/id (random-uuid) :command/timestamp (time/now)
                                                      :command/name :sheet/cancel-tick :sheet-id sheet-id
                                                      :tick-id tick-id :reason reason}))]
                                 (when (:cognitect.anomalies/category result)
                                   (u/log ::budget-cancellation-failed :level :error
                                          :tick-id tick-id :anomaly result)
                                   (assoc result :cancellation-tick-id tick-id)))))) claimed)]
                (when (seq anomalies) (assoc (first anomalies) :cancellation-anomalies anomalies)))))
          exceeded)
      exceeded)))

(defn- reserve-llm-call-or-cancel!
  "Reserve one call and durably cancel both the active and root ticks when the
   shared root budget is exhausted. Returns the exceeded descriptor, or nil."
  [context tick-ctx sheet-id tick-id]
  (when-let [exceeded (logic/reserve-llm-call! tick-ctx tick-id)]
    (cancel-llm-budget! context sheet-id tick-id exceeded)))

(defn- reserve-durable-provider-call!
  "Durable CAS is the sole bounded admission authority. Nil permits dispatch."
  [context reservation]
  (let [result (cp/process-command
                (assoc context :command
                       (merge {:command/id (random-uuid) :command/timestamp (time/now)
                               :command/name :sheet/reserve-provider-call
                               :invocation-identity (logic/reservation-identity reservation)
                               :reserved-at (str (java.time.Instant/now))}
                              (dissoc reservation :budget))))]
    (when (:cognitect.anomalies/category result) result)))

(defn- reserve-durable-provider-call-or-cancel!
  "Classify append conflicts from the same authoritative dedicated fact stream.
   Only proven exhaustion cancels; incomplete history, stale owners, duplicates
   and read/append failures all refuse dispatch without an exhaustion claim."
  [context sheet-id tick-id reservation]
  (when-let [anomaly (reserve-durable-provider-call! context reservation)]
    (if (= :cognitect.anomalies/conflict (:cognitect.anomalies/category anomaly))
      (try
        (let [read-fn (or (:provider-call-reservation-read-fn context)
                          (fn [query] (es/read (:event-store context) query)))
              events (read-fn {:tenant-id (:tenant-id context)
                               :tags #{[:llm-budget-root (:budget-tick-id reservation)]}
                               :types logic/provider-call-reservations-fact-types})
              decision (logic/reservation-decision
                         (assoc reservation :invocation-identity
                                (logic/reservation-identity reservation)) events)]
          (case (:decision decision)
            :exhausted (let [cancellation (cancel-llm-budget! context sheet-id tick-id decision)]
                         (if (:cognitect.anomalies/category cancellation) cancellation anomaly))
            :incomplete-history (assoc anomaly
                                       :failure-kind :budget-history-unavailable
                                       :cognitect.anomalies/message "Durable LLM budget history is incomplete; legacy bounded execution cannot resume")
            anomaly))
        (catch Throwable error
          (assoc anomaly :cognitect.anomalies/message (.getMessage error))))
      anomaly)))

(defn- durable-budget-root
  "A bounded option never falls back to process-local/no-cap admission when
   canonical ownership cannot be established. CAS still validates that owner."
  [context _sheet-id tick-id tick-ctx]
  (let [owner (rm/root context tick-id)
        budget (get-in tick-ctx [:options :llm-call-budget])]
    (when (and (integer? budget) (pos? budget) (nil? owner))
      (throw (ex-info "Durable LLM budget history is incomplete; bounded execution cannot resume"
                      {:failure-kind :budget-history-unavailable :tick-id tick-id})))
    owner))

(defn- tick-ctx-of
  "The tick's execution context for a handler running inside a delivery.

   The todo processors resolve the context ONCE per delivery (see
   `with-resolved-tick-ctx`) and every projection the handler would repeat in
   its synchronous prologue reuses it: each projection decodes the tick's whole
   cached context, and one delivery used to take up to seven. Falls back to a
   projection when the delivery carried none (direct callers, recovery) or when
   it was resolved for another tick. Never call this from work that runs after
   the handler has appended, or from a future: those must read fresh."
  [context tick-id]
  (let [r (::resolved-tick-ctx context)]
    (if (and r (= tick-id (:tick-id r)))
      (:ctx r)
      (rm/get-tick-execution-context context tick-id))))

(defn- with-resolved-tick-ctx
  "Attach the delivery's tick context (one projection) to `context`."
  [{:keys [event] :as context}]
  (let [tick-id (:tick-id event)]
    (assoc context ::resolved-tick-ctx
           {:tick-id tick-id :ctx (rm/get-tick-execution-context context tick-id)})))

(defn- node-execution-tags
  "Index all bounded node starts on the dedicated budget stream.
   Historical checkpoint-specific tick tags remain separate and unchanged."
  [context sheet-id tick-id node-id]
  (let [tick-ctx (tick-ctx-of context tick-id)
        options (:options tick-ctx)
        node (get (:nodes-by-id tick-ctx) node-id)
        checkpointed? (or (true? (:checkpointed-campaign? options))
                          (logic/checkpointed? node))
        budget-root (durable-budget-root context sheet-id tick-id tick-ctx)
        root-tick-id (when checkpointed? (:tick-id budget-root))]
    (cond-> #{[:sheet sheet-id] [:node node-id] [:tick tick-id]}
      budget-root (conj [:llm-budget-root (:tick-id budget-root)])
      (and root-tick-id (not= root-tick-id tick-id))
      (conj [:tick root-tick-id]))))

(defn- resolve-nodes-by-id
  "Get nodes-by-id for a tick from tick-scoped execution context."
  [ctx _sheet-id tick-id]
  (:nodes-by-id (tick-ctx-of ctx tick-id)))

(defn- resolve-blackboard
  "Get blackboard METADATA for a tick from tick-scoped execution context.
   Entries carry key, schema, version, size profile and (for written keys) a
   :source — but NO :value. Use hydrate-values when values are
   needed."
  [ctx _sheet-id tick-id]
  (:blackboard (tick-ctx-of ctx tick-id)))

(defn- hydrate-values
  "Fill in :value on a metadata blackboard for `ks` (nil = every key),
   resolving each from the canonical write log.

   Ask for the keys you actually need. A node's declared :reads is almost
   always the right set — the exceptions are repl-researcher (whose sandbox
   can read any key by name at runtime) and result delivery (which owes the
   caller the whole blackboard).

   nil ks means EVERY key, so narrowing call sites must pass (or (:reads node)
   []) rather than (:reads node) — a node that declares no reads would
   otherwise silently hydrate the whole blackboard."
  [{:keys [tenant-id] :as context} tick-id metadata-bb ks]
  (rm/hydrate-blackboard context tenant-id tick-id metadata-bb ks))

(defn- resolve-blackboard-values
  "Project a tick's blackboard metadata and hydrate `ks` in one step, for
   handlers that do not already hold the projected context."
  [ctx sheet-id tick-id ks]
  (hydrate-values ctx tick-id (resolve-blackboard ctx sheet-id tick-id) ks))

(defn- resolve-instruction-overrides
  "Get GEPA instruction overrides for a tick, if any."
  [ctx tick-id]
  (:instruction-overrides (rm/get-tick-execution-context ctx tick-id)))

(defn- researcher-classification-committed?
  [context tick-id node-id ownership-epoch]
  (some (fn [event]
          (and (contains? #{:ontology/task-classified
                            :ontology/task-classification-deferred}
                          (:event/type event))
               (= node-id (:source-node-id event))
               (= ownership-epoch (:researcher-ownership-epoch event))))
        (into []
              (es/read (:event-store context)
                       {:tenant-id (:tenant-id context)
                        :types #{:ontology/task-classified
                                 :ontology/task-classification-deferred}
                        :tags #{[:tick tick-id]}}))))

(defn- durable-classification-context
  "Return the exact classifier payload committed with this campaign outcome.

   The outcome and payload share one atomic commit.  This is the recovery seam
   for the interval before the first researcher checkpoint exists."
  [context sheet-id tick-id node-id]
  (some (fn [event]
          (when (and (= sheet-id (:source-sheet-id event))
                     (= node-id (:source-node-id event))
                     (map? (:classification-context event)))
            (:classification-context event)))
        (into []
              (es/read (:event-store context)
                       {:tenant-id (:tenant-id context)
                        :types #{:ontology/task-classified
                                 :ontology/task-classification-deferred}
                        :tags #{[:tick tick-id]}}))))

(defn- capture-classification-signature!
  "CV-1's convergence capture, as a claim operation (CC-6).

   One `:add` delta of kind `:representative-use` carrying the task signature —
   what this class was minted FOR, which is exactly what a representative use
   is, and the kind whose contents CC-3's `assemble-summary` renders into
   `:summary`, the field ColBERT indexes. So the corpus gains the signature by
   the same route as every other insight rather than by a competing write.

   `:evidence-basis :classification-signature` is a DECLARATION, not a claim of
   quality: the content is the deterministic input the classifier keyed on,
   recorded before anything could have judged the answer. `:episodes` is
   deliberately EMPTY — this turn is real but unjudged, and CC-7 must not be
   able to mistake an unjudged occurrence for post-guard evidence.

   Version 0 is not assumed: a fresh mint has no claim events, but the version
   is READ so that a re-used identifier can never silently lose its capture to
   a stale-version refusal. ontology is resolved lazily, the established
   pattern for this wedge's optional dependency.

   NO try/catch, deliberately. The body write this replaced had none, and
   wrapping the replacement would introduce a path where convergence silently
   stops working while every classification still looks fine — the failure
   shape hardest to notice and the one this whole arc is about."
  [context class-id signature]
  (let [claim-set-version (integrations/capability :ontology :get-claim-set-version)
        command {:command/name :ontology/record-claim-deltas
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :granularity :tree-class
                 :target-identifier class-id
                 :deltas [{:operation :add
                           :kind :representative-use
                           :content signature
                           :context-guard nil
                           :recommendation nil
                           :episodes []
                           :from-legacy-corpus false
                           :evidence-basis :classification-signature}]
                 :evidence-event-count 0
                 :claim-set-version
                 (claim-set-version context :tree-class class-id)}]
    (logic/run-or-defer-classification-effect!
     context :convergence-capture command
     #(cp/process-command (assoc context :command command)))))

(defn- birth-description-claims!
  "CV-A item 5 (`DomainFamilyDescribesItselfFromBirth`): on a family birth
   (the mint carries a `:merge-verdict`), record the birth claims in ONE
   dispatch through the claim path (CC-6), never a body write:
     :capability          — the purpose (subject matter / material / output
                            kind), drawn from the verdict's domain-reasoning.
     :representative-use  — the birth signature.
   Inspection correction: no :guard claim. A guard assembles into the body's
   :avoid-when, which the domain penalty scores tasks against; a birth-shape
   note there is not an avoid-when condition. The birth shape lives in the
   concept's own description instead."
  [context class-id domain-reasoning signature]
  (let [claim-set-version (integrations/capability :ontology :get-claim-set-version)
        capability-content
        (str "Domain family born for a task whose subject matter, material "
             "and output kind are: "
             (or domain-reasoning "(no domain reasoning recorded at birth)"))
        command {:command/name :ontology/record-claim-deltas
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :granularity :tree-class
                 :target-identifier class-id
                 :deltas [{:operation :add
                           :kind :capability
                           :content capability-content
                           :context-guard nil
                           :recommendation nil
                           :episodes []
                           :from-legacy-corpus false
                           :evidence-basis :classification-signature}
                          {:operation :add
                           :kind :representative-use
                           :content signature
                           :context-guard nil
                           :recommendation nil
                           :episodes []
                           :from-legacy-corpus false
                           :evidence-basis :classification-signature}]
                 :evidence-event-count 0
                 :claim-set-version
                 (claim-set-version context :tree-class class-id)}]
    (logic/run-or-defer-classification-effect!
     context :convergence-capture command
     #(cp/process-command (assoc context :command command)))))

(defn maybe-auto-classify-and-set-context
  "C-2c-2: when the node is an :rlm repl-researcher with
   :auto-classify? true and no :context already set, run the classifier
   and inject the resulting :tree-id into the node's :context.

   Behavior:
     - :context already set on node      → no-op, return node
     - :auto-classify? false or absent   → no-op, return node
     - else                              → call classify-task, dispatch
                                            :ontology/assign-task-class
                                            command, set :context

   Context map must carry :sheet-id and :tick-id so the emitted event
   carries the provenance triple needed for downstream auditing.

   Returns the (possibly :context-modified) node map. Side effect: when
   the classifier runs, an :ontology/task-classified event lands in the
   store via the dispatched command."
  [node context]
  (let [rlm-config (:rlm node)
        rlm-map? (map? rlm-config)
        auto? (and rlm-map? (true? (:auto-classify? rlm-config)))
        already-set? (some? (:context node))]
    (if (or already-set? (not auto?))
      node
      (let [threshold (or (:auto-classify-threshold rlm-config) 0.7)
            behavioral-threshold (or (:auto-classify-behavioral-threshold rlm-config)
                                     0.6)
            classify-task (integrations/capability :ontology :classify-task)
            classify-behaviors (integrations/capability :ontology :classify-behaviors)
            build-sig (integrations/capability :ontology :build-task-signature)
            signature (build-sig node)
            classification-active? (or (:classification-active? context)
                                       (constantly true))
            ensure-classification-active!
            (fn []
              (when-not (classification-active?)
                (throw
                 (ex-info "Researcher classification deadline expired"
                          {:classification-expired? true
                           :sheet-id (:sheet-id context)
                           :tick-id (:tick-id context)
                           :node-id (:id node)}))))
            parent-summary (logic/maybe-lookup-parent-summary context (:sheet-id context))
            result (classify-task context
                     (cond-> {:task-signature signature
                              :threshold threshold}
                       parent-summary (assoc :parent-summary parent-summary)))
            _ (ensure-classification-active!)
            behavioral-result (classify-behaviors context
                                {:task-signature signature
                                 :threshold behavioral-threshold
                                 :structural-context (:assigned-tree-id result)
                                 :top-n 5})
            _ (ensure-classification-active!)
            behaviors (:behaviors behavioral-result)
            uncertain? (or (= :uncertain (:outcome result))
                           (= :uncertain (:outcome behavioral-result)))]
        (if uncertain?
          (let [fallback-sources @(integrations/capability :ontology :rerank-fallback-sources)
                fallback-of (fn [entries]
                              (some (comp fallback-sources :rerank-source) entries))
                fallback-source (or (when (= :uncertain (:outcome result))
                                      (fallback-of (:ranked-candidates result)))
                                    (when (= :uncertain (:outcome behavioral-result))
                                      (fallback-of (:behaviors behavioral-result)))
                                    :colbert-fallback)
                deferral-command
                (cond->
                 {:command/name :ontology/record-task-classification-deferral
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :source-sheet-id (:sheet-id context)
                  :source-tick-id (:tick-id context)
                  :source-node-id (:id node)
                  :fallback-source fallback-source
                  :ranked-candidates (vec (:ranked-candidates result))
                  :reasoning (if (= :uncertain (:outcome result))
                               (or (:reasoning result)
                                   "Structural classification deferred: reranker fell back (uncertain).")
                               "Behavioral classification deferred: reranker fell back (uncertain); structural assignment withheld.")}
                  (some? (:researcher-ownership-epoch context))
                  (assoc :researcher-ownership-epoch
                         (:researcher-ownership-epoch context)))]
            (ensure-classification-active!)
            (logic/run-or-defer-classification-effect!
             context :classification-outcome deferral-command
             #(cp/process-command
               (assoc context :command deferral-command)))
            (println (format "[DEBUG RLM] node '%s' auto-classify DEFERRED (outcome :uncertain — struct=%s behav=%s, fallback=%s) — deferral event recorded, NO assign-task-class dispatched"
                             (or (:name node) (str (:id node)))
                             (:outcome result)
                             (:outcome behavioral-result)
                             fallback-source)))
          (let [assignment-command
                (cond-> {:command/name :ontology/assign-task-class
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :source-sheet-id (:sheet-id context)
                         :source-tick-id (:tick-id context)
                         :source-node-id (:id node)
                         :assigned-tree-id (:assigned-tree-id result)
                         :confidence (:confidence result)
                         :top-candidates (:top-candidates result)
                         :reasoning (or (:reasoning result) "")
                         :was-fresh-mint? (:was-fresh-mint? result)}
                  (some? (:researcher-ownership-epoch context))
                  (assoc :researcher-ownership-epoch
                         (:researcher-ownership-epoch context))
                  (some? (:parent-tree-id result))
                  (assoc :parent-tree-id (:parent-tree-id result))
                  (true? (:rerank-fallback? result))
                  (assoc :rerank-failed? true)
                  (seq behaviors)
                  (assoc :behavioral-subtrees behaviors)
                  (some? (:ranked-candidates result))
                  (assoc :ranked-candidates (:ranked-candidates result))
                  (some? (:assigned-via result))
                  (assoc :assigned-via (:assigned-via result))
                  (some? (:outcome result))
                  (assoc :outcome (:outcome result))
                  (some? (:domain-verdict result))
                  (assoc :domain-verdict (:domain-verdict result))
                  (some? (:domain-label result))
                  (assoc :domain-label (:domain-label result))
                  (some? (:domain-children-considered result))
                  (assoc :domain-children-considered (:domain-children-considered result))
                  (some? (:domain-deferral result))
                  (assoc :domain-deferral (:domain-deferral result))
                  (some? (:domain-selection result))
                  (assoc :domain-selection (:domain-selection result))
                  (some? (:merge-verdict result))
                  (assoc :merge-verdict (:merge-verdict result)))
                domain-mint? (contains? #{:mint-domain-child :mint-sibling-domain-child}
                                        (:assigned-via result))
                birth-family-mint? (and domain-mint? (some? (:merge-verdict result)))]
            (ensure-classification-active!)
            (when domain-mint?
              (let [mint-command (cond-> {:command/name :ontology/mint-domain-child
                                          :command/id (random-uuid)
                                          :command/timestamp (time/now)
                                          :parent-tree-id (:parent-tree-id result)
                                          :child-tree-id (:assigned-tree-id result)
                                          :domain-label (:domain-label result)
                                          :source-sheet-id (:sheet-id context)
                                          :source-tick-id (:tick-id context)
                                          :source-node-id (:id node)}
                                    birth-family-mint?
                                    (assoc :birth-description
                                           (logic/domain-family-birth-description
                                            (:domain-label result)
                                            (:summary (logic/fetch-tree-body context (:parent-tree-id result)))
                                            (get-in result [:domain-verdict :domain-reasoning])
                                            signature)))]
                (logic/run-or-defer-classification-effect!
                 context :domain-child-mint mint-command
                 #(cp/process-command (assoc context :command mint-command)))
                (ensure-classification-active!)
                (println (format "[DEBUG RLM] node '%s' RS-3 domain-child MINTED under parent %s label %s"
                                 (or (:name node) (str (:id node)))
                                 (:parent-tree-id result)
                                 (:domain-label result)))))
            (when (and domain-mint? (:was-fresh-mint? result))
              (ensure-classification-active!)
              (if (:merge-verdict result)
                (do
                  (birth-description-claims! context (:assigned-tree-id result)
                                             (get-in result [:domain-verdict :domain-reasoning])
                                             signature)
                  (println (format "[DEBUG RLM] node '%s' CV-A BIRTH-DESCRIPTION recorded (capability+representative-use) for :tree-class %s"
                                   (or (:name node) (str (:id node)))
                                   (:assigned-tree-id result))))
                (capture-classification-signature! context (:assigned-tree-id result) signature))
              (ensure-classification-active!)
              (println (format "[DEBUG RLM] node '%s' CONVERGENCE-CAPTURE recorded signature claim for :tree-class %s"
                               (or (:name node) (str (:id node)))
                               (:assigned-tree-id result))))
            (logic/run-or-defer-classification-effect!
             context :classification-outcome assignment-command
             #(cp/process-command (assoc context :command assignment-command)))
            (println (format "[DEBUG RLM] node '%s' auto-classified → %s (confidence %.2f, was-fresh-mint? %s, behavioral-count %d)"
                             (or (:name node) (str (:id node)))
                             (:assigned-tree-id result)
                             (double (or (:confidence result) 0.0))
                             (:was-fresh-mint? result)
                             (count (:behaviors behavioral-result))))
            (when-let [deferral (:domain-deferral result)]
              (ensure-classification-active!)
              (let [domain-deferral-command
                    (cond-> {:command/name :ontology/record-task-classification-deferral
                             :command/id (random-uuid)
                             :command/timestamp (time/now)
                             :source-sheet-id (:sheet-id context)
                             :source-tick-id (:tick-id context)
                             :source-node-id (:id node)
                             :fallback-source :domain-coverage
                             :ranked-candidates (vec (:ranked-candidates result))
                             :reasoning (format "Domain classification deferred: axis=%s reason=%s"
                                                (name (:axis deferral))
                                                (name (:reason deferral)))}
                      (some? (:researcher-ownership-epoch context))
                      (assoc :researcher-ownership-epoch
                             (:researcher-ownership-epoch context)))]
                (logic/run-or-defer-classification-effect!
                 context :domain-classification-deferral domain-deferral-command
                 #(cp/process-command (assoc context :command domain-deferral-command)))
                (ensure-classification-active!)
                (println (format "[DEBUG RLM] node '%s' RS-3 domain-axis DEFERRED (reason=%s) — deferral event recorded alongside the structural assignment"
                                 (or (:name node) (str (:id node)))
                                 (:reason deferral)))))
            (when (and (not domain-mint?) (:was-fresh-mint? result))
              (ensure-classification-active!)
              (capture-classification-signature! context (:assigned-tree-id result) signature)
              (ensure-classification-active!)
              (println (format "[DEBUG RLM] node '%s' CONVERGENCE-CAPTURE recorded signature claim for :tree-class %s"
                               (or (:name node) (str (:id node)))
                               (:assigned-tree-id result))))))
        (assoc node :context
               {:tree-id (:assigned-tree-id result)
                :r05-classifier
                {:structural
                 (cond-> {:assigned-tree-id (:assigned-tree-id result)
                          :confidence (:confidence result)
                          :was-fresh-mint? (:was-fresh-mint? result)
                          :reasoning (:reasoning result)
                          :top-candidates (vec (:top-candidates result))
                          :rerank-fallback? (boolean (:rerank-fallback? result))}
                   (contains? #{:mint-domain-child :land-on-domain-child
                                :mint-sibling-domain-child}
                              (:assigned-via result))
                   (assoc :domain
                          {:assigned-via (:assigned-via result)
                           :parent-tree-id (:parent-tree-id result)
                           :child-tree-id (:assigned-tree-id result)
                           :domain-label (:domain-label result)}))
                 :behavioral {:behaviors (vec (:behaviors behavioral-result))
                              :rerank-fallback? (boolean
                                                  (:rerank-fallback? behavioral-result))}}})))))

(defn- tick-parent-id
  "The tick that spawned `tick-id`, or nil at the root.

   Reads the tick's own :sheet/tree-tick-started event. Deliberately does NOT
   require an :execution-snapshot the way trace-lineage's originating-tick-
   started does: lineage is carried by :parent-tick-id, and the snapshot-less
   UI tick path stamps it just the same."
  [{:keys [event-store tenant-id]} tick-id]
  (some :parent-tick-id
        (into [] (es/read event-store {:types #{:sheet/tree-tick-started}
                                       :tags #{[:tick tick-id]}
                                       :tenant-id tenant-id}))))

(defn- resolve-root-trace-id
  "The ROOT tick of this tick's lineage — the ledger's `:root-trace-id`, the
   key it joins to outcomes by.

   An RLM Phase-2 tree and a delegate node run in CHILD ticks, so a record
   that knew only its own tick would strand that injection from the run it
   belongs to. Cycle- and depth-guarded, and falls back to the tick itself:
   a wrong join is worse than a self-join.

   Mirrors the root walk in trace-lineage; kept separate because that helper
   is defined further down the file and also computes parent/child ids this
   does not need."
  [ctx tick-id]
  (try
    (loop [current tick-id, seen #{}, depth 0]
      (if (or (contains? seen current) (>= depth 100))
        tick-id
        (if-let [parent (tick-parent-id ctx current)]
          (recur parent (conj seen current) (inc depth))
          current)))
    (catch Exception _ tick-id)))

(defn- record-injection!
  "Dispatch the injection record for one render occurrence.

   Best-effort and deliberately silent: this is measurement, and measurement
   must never be able to fail a turn. It also no-ops when there is no command
   context — apply-r05-classifier-context is called directly (with `{}`) by
   pure render tests and by bench probes, and those must keep working."
  [ctx row]
  (when (and (:event-store ctx) (:sheet-id ctx) (:tick-id ctx) (:node-id row))
    (let [command
          (merge {:command/name :sheet/record-injection
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :intervention/type :pattern-injection
                  :sheet-id (:sheet-id ctx)
                  :tick-id (:tick-id ctx)
                  :root-trace-id (resolve-root-trace-id ctx (:tick-id ctx))
                  :correlation-id (:correlation-id ctx)}
                 (when-let [turn-id (:turn-id ctx)]
                   {:turn-id turn-id})
                 row)]
      (logic/run-or-defer-classification-effect!
       ctx :injection-record command
       #(try
          (cp/process-command (assoc ctx :command command))
          (catch Exception _ nil))))))

(defn apply-r05-classifier-context
  "R-Inject: prepend R05's classifier output to the node's :instruction
   when the wedge has stashed a :r05-classifier payload on :context.

   When :r05-classifier is absent (node not auto-classified or :context
   set manually), returns the node unchanged. Otherwise prepends a
   principle-shaped block — structural pattern + behavioral top-3 with
   reasoning + seed :summary guidance — so the model designing the tree
   in Phase 1 sees the corpus's actual examples.

   Three arms, both controls default-off (see *injection-holdout* and
   *claim-holdout*): :treatment renders everything, :claim-holdout renders the
   block without claim-derived content, :holdout renders nothing at all.

   Pure transformation; the only effect is reading description bodies
   for behavioral seeds via ontology/get-description (3 reads max)."
  [node ctx]
  (let [payload (get-in node [:context :r05-classifier])]
    (if-not payload
      node
      (let [structural (:structural payload)
            behavioral (:behavioral payload)
            holdout (logic/holdout-config ctx)
            claim-holdout (logic/claim-holdout-config ctx)
            arm (logic/assign-arm ctx holdout claim-holdout (:id node))
            suppress-claims? (= :claim-holdout arm)
            rendering? (not= :holdout arm)
            node-type-guidance (when rendering?
                                 (logic/current-node-type-guidance ctx node))
            block (when rendering?
                    (str "## Suggested patterns from corpus\n\n"
                       "These are concrete EXAMPLES retrieved from the seed corpus based on "
                       "classification of your task. Each example includes:\n"
                       "  - WHY the candidate fits (reranker reasoning)\n"
                       "  - The pattern's prose summary (seed `:summary`)\n"
                       "  - Capabilities it provides\n"
                       "  - Proven STRENGTHS — traits observed to work, each with a worked-example DSL snippet you can adapt\n"
                       "  - Observed WEAKNESSES — failure modes others hit, with the recommended fix\n"
                       "  - Representative uses where this pattern has shipped\n\n"
                       "You always have FOUR moves, and the references below are EVIDENCE for choosing — not a mandate:\n"
                       "  - ADOPT — use a reference as-is when it is an EXACT fit.\n"
                       "  - ADAPT — keep a reference's pattern, override the specifics, when it mostly fits.\n"
                       (logic/format-specialize-bullet structural)
                       "  - MINT — mint a fresh behavior (`:parent nil`) when the task is genuinely novel and no reference is a true parent.\n"
                       "A match clearing threshold does NOT mean adopt/adapt is your only option — weigh each reference's strengths AND its `:avoid-when` against THIS task, then pick the right move. Your job is the RIGHT tree for THIS task; the corpus is evidence, not gospel.\n\n"
                       (logic/format-structural-section ctx suppress-claims? structural)
                       "\n"
                       (logic/format-behavioral-section ctx suppress-claims? behavioral)
                       "\n"
                       (logic/format-node-type-guidance node-type-guidance)
                       "\n---\n"))
            result (if block
                     (update node :instruction (fn [inst] (str block inst)))
                     node)
            candidates (logic/injected-candidates ctx suppress-claims? payload)]
        (record-injection! ctx
          (cond-> {:node-id (:id node)
                   :arm arm
                   :baseline-policy-id (if (:enabled? claim-holdout)
                                         (or (:baseline-policy-id claim-holdout)
                                             logic/default-claim-baseline-policy-id)
                                         (or (:baseline-policy-id holdout)
                                             logic/default-baseline-policy-id))
                   :selection-propensity (* (if (:enabled? holdout)
                                              (- 1.0 (double (or (:fraction holdout) 0.0)))
                                              1.0)
                                            (if (:enabled? claim-holdout)
                                              (- 1.0 (double (or (:fraction claim-holdout) 0.0)))
                                              1.0))
                   :candidates candidates
                   :candidate-id (:candidate-id (first (filter #(= :structural (:axis %))
                                                               candidates)))
                   :task-class (logic/->uuid (get-in payload [:structural :assigned-tree-id]))
                   :model (:model node)
                   :rendered-chars (count block)}
            block (assoc :prompt-content-hash (logic/sha256-hex block))
            (and block (or (:injection-capture-rendered-block? ctx)
                           logic/*capture-rendered-block?*))
            (assoc :rendered-block block)))
        (println (format "[DEBUG R-Inject] arm %s — prepended %d chars (instruction now %d chars)"
                         (name arm)
                         (count block)
                         (count (:instruction result))))
        result))))

(defn- provider-policy-options
  "The execution-policy options handed to a provider invocation (model leaf or
   model-backed condition): the execution deadline, the shared LLM-call budget
   reservation, the tick, the correlation context and, for a checkpointed
   campaign with a durable budget, the durable provider-attempt reservation."
  [context tick-ctx sheet-id tick-id node-id exec-context]
  (let [tick-options (:options tick-ctx)
        checkpointed-campaign? (true? (:checkpointed-campaign? tick-options))
        budget-root (durable-budget-root context sheet-id tick-id tick-ctx)
        reservation-context
        (merge {:budget-sheet-id (:sheet-id budget-root)
                :budget-tick-id (:tick-id budget-root) :budget (:budget budget-root)
                :sheet-id sheet-id :tick-id tick-id :node-id node-id
                :node-start-id (get-in context [:event :event/id]) :exec-context exec-context}
               (if checkpointed-campaign?
                 {:admission-kind :campaign
                  :campaign-sheet-id (:researcher-campaign-sheet-id tick-options)
                  :campaign-tick-id (:researcher-campaign-tick-id tick-options)
                  :campaign-node-id (:researcher-campaign-node-id tick-options)
                  :iteration-index (:researcher-iteration-index tick-options)
                  :ownership-epoch (:researcher-ownership-epoch tick-options)}
                 {:admission-kind :ordinary :node-start-id (get-in context [:event :event/id])
                  :exec-context exec-context}))
        reserve-provider-attempt!
        (when budget-root
          (fn [{:keys [logical-action-identity provider-attempt-ordinal candidate-index node-attempt emitted-model-authority]}]
            (reserve-durable-provider-call-or-cancel!
              context sheet-id tick-id
              (cond-> (assoc reservation-context :logical-action-identity logical-action-identity
                             :provider-attempt-ordinal provider-attempt-ordinal)
                emitted-model-authority
                (assoc :ownership-epoch (:ownership-epoch emitted-model-authority)
                       :model-logical-action-id (:logical-action-id emitted-model-authority))
                (contains? emitted-model-authority :configuration-id)
                (assoc :model-configuration-id (:configuration-id emitted-model-authority))
                (not checkpointed-campaign?)
                (assoc :candidate-index (or candidate-index 0) :node-attempt (or node-attempt 1))))))]
    (cond->
     {:model-role-context
      {:context context
       :owner {:sheet-id sheet-id :tick-id tick-id :node-id node-id
               :node-start-id (get-in context [:event :event/id])
               :exec-context exec-context
               :role (if (:researcher-campaign-node-id tick-options) :subcall :node)
               :selection-scope [tick-id node-id exec-context]
               :logical-action-id (pr-str [tick-id node-id exec-context])}}
      :execution-deadline-ms (get-in tick-options [:execution-deadline-ms])
      :reserve-llm-call! #(reserve-llm-call-or-cancel! context tick-ctx sheet-id tick-id)
      :tick-id tick-id
      :exec-context exec-context
      :start-provider-attempt!
      (fn [attempt]
        (start-provider-attempt!
         context (merge attempt {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                                 :node-start-id (get-in context [:event :event/id])
                                 :exec-context exec-context})))
      :complete-provider-attempt!
      (fn [attempt]
        (complete-provider-attempt!
         context (merge attempt {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                                 :node-start-id (get-in context [:event :event/id])
                                 :exec-context exec-context})))}
      (:researcher-campaign-node-id tick-options)
      (assoc :fresh-model-authority!
             (fn [deadline]
               (ensure!
                 context {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                          :node-start-id (get-in context [:event :event/id]) :exec-context exec-context
                          :logical-action-id (pr-str [tick-id node-id exec-context])}
                 deadline)))
      reserve-provider-attempt!
      (assoc :reserve-provider-attempt! reserve-provider-attempt!
             :provider-reservation-context reservation-context))))

(defn- campaign-effect-capabilities
  "The effect-claim capabilities and durable receipts a generated child leaf
   needs to checkpoint its tool calls as effects of the checkpointed campaign
   its tick belongs to (GeneratedChildToolCallsAreCheckpointed). The claims are
   the campaign's own, under the campaign's identity and ownership epoch; the
   claim records are read when the leaf starts, so a re-run sees every receipt
   completed before it. Empty when the tick belongs to no checkpointed
   campaign."
  [context tick-options]
  (let [epoch (:researcher-ownership-epoch tick-options)
        campaign-sheet-id (:researcher-campaign-sheet-id tick-options)
        campaign-tick-id (:researcher-campaign-tick-id tick-options)
        campaign-node-id (:researcher-campaign-node-id tick-options)]
    (when (and (integer? epoch) (pos? epoch)
               campaign-sheet-id campaign-tick-id campaign-node-id)
      (let [budget-root-tick-id (or (:llm-budget-root-tick-id tick-options)
                                    campaign-tick-id)
            command (fn [name fields]
                      (cp/process-command
                       (assoc context :command
                              (merge {:command/id (random-uuid)
                                      :command/timestamp (time/now)
                                      :command/name name
                                      :sheet-id campaign-sheet-id
                                      :tick-id campaign-tick-id
                                      :node-id campaign-node-id
                                      :ownership-epoch epoch}
                                     fields))))]
        {:researcher-effect-claims
         (rm/get-researcher-effect-claims context campaign-sheet-id
                                          campaign-tick-id campaign-node-id)
         :claim-researcher-effect!
         (fn [{:keys [iteration-index logical-action-identity attempt-identity
                      attempt-ordinal kind]}]
           (command :sheet/claim-researcher-effect
                    {:budget-root-tick-id budget-root-tick-id
                     :iteration-index iteration-index
                     :logical-action-identity logical-action-identity
                     :attempt-identity attempt-identity
                     :attempt-ordinal attempt-ordinal
                     :kind kind
                     :claimed-at (str (java.time.Instant/now))}))
         :complete-researcher-effect!
         (fn [{:keys [logical-action-identity attempt-identity result]}]
           (command :sheet/complete-researcher-effect
                    {:logical-action-identity logical-action-identity
                     :attempt-identity attempt-identity
                     :result result
                     :resolved-at (str (java.time.Instant/now))}))}))))

(defn- process-lifecycle-command!
  "Run a command whose rejection would strand a node execution. A rejection is
   logged loudly with the command name and the result is returned, so the
   caller can turn it into a visible failure rather than a silent stall."
  [context command]
  (let [result (cp/process-command (assoc context :command command))]
    (when-let [anomaly (logic/command-rejection result)]
      (u/log ::lifecycle-command-rejected
             :level :error
             :command-name (:command/name command)
             :sheet-id (:sheet-id command)
             :tick-id (:tick-id command)
             :node-id (:node-id command)
             :anomaly-category (:cognitect.anomalies/category anomaly)
             :anomaly-message (:cognitect.anomalies/message anomaly)))
    result))

(defn- complete-node-or-fail!
  "Issue a node-completion command. When the command is REJECTED the node
   would never reach a terminal fact and the run would hang until its
   deadline, so fail the node visibly through :sheet/fail-node-execution. The
   error names the rejected completion and keeps the original status,
   failure kind and error text as detail. A :conflict means a competing writer
   already holds a terminal fact for the node, so it is logged but not failed
   again."
  [context completion-command]
  (let [completion-command (cond-> completion-command
                             (contains? completion-command :rejected-writes)
                             (update :rejected-writes logic/rejection-evidence))
        result (process-lifecycle-command! context completion-command)
        anomaly (logic/command-rejection result)]
    (if (and anomaly
             (not= :cognitect.anomalies/conflict
                   (:cognitect.anomalies/category anomaly)))
      (process-lifecycle-command!
       context
       {:command/id (random-uuid)
        :command/timestamp (time/now)
        :command/name :sheet/fail-node-execution
        :sheet-id (:sheet-id completion-command)
        :tick-id (:tick-id completion-command)
        :node-id (:node-id completion-command)
        :error (str "Completion rejected: " (:command/name completion-command)
                    " was rejected ("
                    (or (:cognitect.anomalies/message anomaly)
                        (:cognitect.anomalies/category anomaly))
                    "); attempted status " (pr-str (:status completion-command))
                    (when-let [k (:failure-kind completion-command)]
                      (str ", failure-kind " (pr-str k)))
                    (when-let [e (:error completion-command)]
                      (str ", original error: " e)))})
      result)))

(defn execute-leaf-node
  "Execute a leaf node when node-execution-started is emitted.
   Supports multiple executor types:
   - :ai - ORC LLM AI execution (default)
   - :code - Clojure function execution
   - :tool - Direct tool invocation

   Runs execution in a future to avoid blocking the pubsub thread.
   Uses cp/process-command to emit completion events."
  [{:keys [event event-store llm-provider] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        tick-ctx (tick-ctx-of context tick-id)
        nodes-by-id (:nodes-by-id tick-ctx)
        overrides (:instruction-overrides tick-ctx)
        node (-> (get nodes-by-id node-id)
                 (logic/apply-instruction-override overrides)
                 (apply-r05-classifier-context context))]
    (when (= :leaf (:type node))
      (let [lease (begin!
                   context {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                            :exec-context (logic/extract-execution-context event-inputs)
                            :start-event-id (:event/id event)})]
      (try
      (let [raw-blackboard (hydrate-values context tick-id (:blackboard tick-ctx)
                                           (or (:reads node) []))
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k) (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            tool-context (:tool-context tick-ctx)
            leaf-context (cond-> (assoc (merge context
                                                (logic/ephemeral-context-for tick-id))
                                        :tick-options (:options tick-ctx)
                                        :tick-id tick-id
                                        :node-id node-id
                                        :execution-deadline-ms
                                        (get-in tick-ctx [:options :execution-deadline-ms]))
                           tool-context (assoc :tool-context tool-context)
                           (#{:code :tool} (:executor node))
                           (merge (campaign-effect-capabilities
                                   context (:options tick-ctx))))
            provider (or (:provider node) llm-provider logic/*default-llm-provider*)
            executor-type (or (:executor node) :ai)
            exec-context (logic/extract-execution-context event-inputs)
            is-llm-call? (and (#{:ai :decision :repl-researcher} executor-type) provider)
            stream-cfg (when is-llm-call?
                         (when-let [cfg (logic/delta-config tick-id)]
                           (cond-> (assoc cfg
                                          :tick-id tick-id
                                          :sheet-id sheet-id
                                          :node-id node-id)
                             (and (::map-each-index exec-context)
                                  (::map-each-parent exec-context))
                             (assoc :map-each {:parent (::map-each-parent exec-context)
                                               :index (::map-each-index exec-context)}))))]
        (do
            (when is-llm-call?
              (let [map-idx (::map-each-index exec-context)]
                (u/log ::leaf-llm-event-received
                       :map-idx map-idx
                       :thread (.getName (Thread/currentThread)))))
            (logic/register-work! tick-id node-id (future
            (try
              (try
              (if (rm/is-tick-or-ancestor-cancelled? context tick-id)
                (process-lifecycle-command! context
                  {:command/id (random-uuid)
                          :command/timestamp (time/now)
                          :command/name :sheet/fail-node-execution
                          :sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :error "tick cancelled"})
                (do
              (let [start-ms (System/currentTimeMillis)
                    _ (when is-llm-call?
                        (let [instr (or (:instruction node) "")
                              instr-preview (if (> (count instr) 80) (str (subs instr 0 80) "...") instr)
                              map-idx (::map-each-index exec-context)]
                          (u/log ::leaf-llm-subcall-started
                                 :node-id node-id
                                 :map-idx map-idx
                                 :thread (.getName (Thread/currentThread))
                                 :instruction-preview instr-preview)))
                    raw-result (cond
                             (#{:code :tool} executor-type)
                             (execute-leaf node blackboard nil
                                                    :context leaf-context)
                             provider
                             (execute-leaf
                              node blackboard provider
                              :context leaf-context
                              :options (provider-policy-options
                                        context tick-ctx sheet-id tick-id node-id
                                        exec-context)
                              :stream stream-cfg)
                             (= :decision executor-type)
                             {:status :failure
                              :error "No llm-provider configured for model decision"}
                             :else
                             (logic/execute-leaf-mock node blackboard))
                    result (logic/validate-leaf-outputs blackboard raw-result is-llm-call?)
                  {:keys [status outputs rejected-writes missing-write-keys error duration-ms usage raw-response
                          failure-kind provider-evidence block-payload decision]
                   result-model :model
                   resolved-model :resolved-model} result
                  _ (when is-llm-call?
                      (u/log ::leaf-llm-subcall-completed
                             :node-id node-id
                             :status status
                             :duration-ms (or duration-ms (- (System/currentTimeMillis) start-ms))
                             :total-tokens (:total-tokens usage)))]
              (when usage (logic/add-usage! tick-id usage))
              (when-not (:model-call-in-flight? result)
              (complete-node-or-fail!
                context
                       (cond-> {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/complete-node-execution
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :node-id node-id
                                :node-type (:type node)
                                :status status
                                :writes (logic/normalize-output-keys (or outputs {}))}
                         (seq missing-write-keys) (assoc :missing-write-keys missing-write-keys)
                         (seq rejected-writes)
                         (assoc :rejected-writes (logic/normalize-output-keys rejected-writes))
                         duration-ms (assoc :duration-ms duration-ms)
                         error (assoc :error error)
                         (= :blocked status) (assoc :block-payload block-payload)
                         (and (= :failure status) raw-response)
                         (assoc :raw-response raw-response)
                         (and (= :failure status) failure-kind)
                         (assoc :failure-kind failure-kind)
                         (and (= :failure status) provider-evidence)
                         (assoc :provider-evidence provider-evidence)
                         :always
                         (as-> cmd
                               (let [reads (logic/extract-read-inputs (:reads node) blackboard)]
                                 (cond-> cmd
                                   (or (seq exec-context) (seq reads))
                                   (assoc :inputs (merge exec-context reads))
                                   (seq reads)
                                   (assoc :read-sources (logic/read-sources (:reads node) blackboard exec-context)))))
                         (seq usage) (assoc :usage usage)
                         decision (assoc :decision decision)
                         (:model-fallback result) (assoc :model-fallback (:model-fallback result))
                         (and is-llm-call? (or result-model (:model node)))
                         (assoc :model (or result-model (:model node)))
                         (and is-llm-call? (:model node))
                         (assoc :requested-model (:model node))
                         (and is-llm-call? resolved-model)
                         (assoc :resolved-model resolved-model))))
              (when (and is-llm-call? (seq usage))
                (let [node-path (cond-> [{:type :leaf :node-id node-id}]
                                  (::map-each-parent exec-context)
                                  (#(into [{:type :map-each
                                            :parent (::map-each-parent exec-context)
                                            :index (::map-each-index exec-context)}]
                                          %)))
                      input-profile (logic/todo-processors-compute-input-profile (:reads node) blackboard)]
                  (cp/process-command
                    (assoc context :command
                           (cond-> {:command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :command/name :sheet/record-rlm-tree-node-completion
                                    :sheet-id sheet-id
                                    :tick-id tick-id
                                    :node-id node-id
                                    :node-path node-path
                                    :usage usage}
                             (seq input-profile)
                             (assoc :input-profile input-profile)))))))))
            (catch Throwable t
              (cond
                (logic/blocking-condition? t)
                (complete-node-or-fail!
                  context
                  {:command/id (random-uuid)
                   :command/timestamp (time/now)
                   :command/name :sheet/complete-node-execution
                   :sheet-id sheet-id
                   :tick-id tick-id
                   :node-id node-id
                   :node-type (:type node)
                   :status :blocked
                   :writes {}
                   :block-payload (logic/block-payload t)})

                (instance? Exception t)
                (process-lifecycle-command!
                  context
                  {:command/id (random-uuid)
                   :command/timestamp (time/now)
                   :command/name :sheet/fail-node-execution
                   :sheet-id sheet-id
                   :tick-id tick-id
                   :node-id node-id
                   :error (or (.getMessage t) (str (class t)))})

                :else (throw t))))
              (finally (logic/end! lease))))))
        nil)
      (catch Throwable t
        (logic/end! lease)
        (throw t)))))))

(defn execute-repl-researcher-node
  "Execute a repl-researcher node when node-execution-started is emitted.
   Runs in a future like leaf/llm-condition nodes to avoid blocking pubsub.
   The :rlm/tree-generated event records the last emitted tree once per campaign."
  [{:keys [event event-store llm-provider] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        tick-ctx (tick-ctx-of context tick-id)
        nodes-by-id (:nodes-by-id tick-ctx)
        overrides (:instruction-overrides tick-ctx)
        base-node (-> (get nodes-by-id node-id)
                      (logic/apply-instruction-override overrides))]
    (when (= :repl-researcher (:type base-node))
      (let [raw-blackboard (hydrate-values context tick-id (:blackboard tick-ctx) nil)
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k) (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            provider (or llm-provider logic/*default-llm-provider*)
            exec-context (logic/extract-execution-context event-inputs)
            read-inputs (logic/extract-read-inputs (:reads base-node) blackboard)
            checkpointed? (boolean (logic/checkpointed? base-node))
            budget-root (durable-budget-root context sheet-id tick-id tick-ctx)
            durable-tick-options
            (cond-> (:options tick-ctx)
              budget-root
              (assoc :checkpointed-campaign? checkpointed?
                     :llm-call-budget (:budget budget-root)
                     :llm-budget-root-sheet-id (:sheet-id budget-root)
                     :llm-budget-root-tick-id (:tick-id budget-root)))]
        (let [lease (begin!
                     context {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                              :exec-context exec-context
                              :start-event-id (:event/id event)})]
        (try
        (logic/registered-future
         checkpointed? tick-id node-id
         (let [researcher-monotonic-ms-fn
               (when checkpointed?
                 (or (:researcher-monotonic-ms-fn context)
                     #(quot (System/nanoTime) 1000000)))
               researcher-quantum-started-monotonic-ms-ref (atom nil)
               researcher-ownership-epoch-ref (atom nil)
               researcher-frontier-claim-attempted? (atom false)
               researcher-frontier-claimed? (atom false)
               researcher-lease-lost? (atom false)
               researcher-lease-owned?
               (when (and checkpointed? (fn? (:lease-owned? context)))
                 (:lease-owned? context))
               researcher-registered-work
               (when researcher-lease-owned?
                 (or (logic/current-work)
                     (throw
                      (ex-info
                       "Checkpointed researcher started outside its registered work slot"
                       {:tick-id tick-id :node-id node-id}))))
               researcher-lease-monitor-ref (atom nil)]
           (try
            (let [researcher-lease-monitor
                  (when researcher-registered-work
                    (logic/cancel-work-when!
                     researcher-registered-work
                     researcher-lease-owned?
                     (or (:researcher-lease-monitor-wait-fn context)
                         #(Thread/sleep 100))
                     #(reset! researcher-lease-lost? true)))]
              (reset! researcher-lease-monitor-ref researcher-lease-monitor)
              (when (or (nil? researcher-lease-monitor)
                        (= :owned (:initial-state researcher-lease-monitor)))
            (let [
                  researcher-quantum-started-monotonic-ms
                  (when checkpointed?
                    (long (researcher-monotonic-ms-fn)))
                  _ (reset! researcher-quantum-started-monotonic-ms-ref
                            researcher-quantum-started-monotonic-ms)
                  wedge-ctx (cond-> (assoc context :sheet-id sheet-id :tick-id tick-id
                                           :correlation-id (:correlation-id tick-ctx))
                              (:turn-id (:tool-context tick-ctx))
                              (assoc :turn-id (:turn-id (:tool-context tick-ctx))))
                  durable-resume-projection
                  (rm/get-researcher-resume-state context sheet-id tick-id node-id)
                  durable-resume-state (:resume-state durable-resume-projection)
                  durable-campaign
                  (rm/get-researcher-campaign context tick-id node-id)
                  campaign-now-ms-fn (or (:campaign-now-ms-fn context)
                                         #(System/currentTimeMillis))
                  campaign-timing
                  (when checkpointed?
                    (logic/resolve-researcher-campaign-timing
                     {:node base-node
                      :checkpoint
                      (merge (select-keys durable-campaign
                                          [:campaign-started-at-ms
                                           :campaign-deadline-ms])
                             durable-resume-state)
                      :started-at-ms (long (campaign-now-ms-fn))}))
                  ownership-epoch
                  (when checkpointed?
                    (or (:researcher-ownership-epoch event)
                        (inc (or (:ownership-epoch durable-resume-state) 0))))
                  _ (reset! researcher-ownership-epoch-ref ownership-epoch)
                  frontier-result
                  (when checkpointed?
                    (reset! researcher-frontier-claim-attempted? true)
                    (cp/process-command
                     (assoc context :command
                            {:command/id (random-uuid)
                             :command/timestamp (time/now)
                             :command/name :sheet/claim-researcher-frontier
                             :sheet-id sheet-id
                             :tick-id tick-id
                             :node-id node-id
                             :budget-root-tick-id
                             (or (:tick-id budget-root) tick-id)
                             :ownership-epoch ownership-epoch
                             :campaign-started-at-ms
                             (:campaign-started-at-ms campaign-timing)
                             :campaign-deadline-ms
                             (:campaign-deadline-ms campaign-timing)
                             :claimed-at (str (java.time.Instant/now))})))
                  frontier-anomaly-category
                  (:cognitect.anomalies/category frontier-result)
                  frontier-conflict?
                  (= :cognitect.anomalies/conflict frontier-anomaly-category)
                  _ (when (and checkpointed?
                               (nil? frontier-anomaly-category))
                      (reset! researcher-frontier-claimed? true))
                  _ (when (and frontier-anomaly-category
                               (not frontier-conflict?))
                      (throw
                       (ex-info
                        (or (:cognitect.anomalies/message frontier-result)
                            "Researcher frontier claim failed")
                        {:frontier-result frontier-result})))
                  unresolved-prior-effect-claims
                  (when (and checkpointed? (not frontier-conflict?))
                    (->> (rm/get-researcher-effect-claims
                          context sheet-id tick-id node-id)
                         (filterv
                          #(and (= :claimed (:status %))
                                (integer? (:ownership-epoch %))
                                (< (:ownership-epoch %) ownership-epoch)))))
                  effect-resolution-result
                  (when (seq unresolved-prior-effect-claims)
                    (loop [[claim & remaining]
                           unresolved-prior-effect-claims]
                      (when claim
                        (let [result
                              (cp/process-command
                               (assoc context :command
                                      {:command/id (random-uuid)
                                       :command/timestamp (time/now)
                                       :command/name
                                       :sheet/mark-researcher-effect-indeterminate
                                       :sheet-id sheet-id
                                       :tick-id tick-id
                                       :node-id node-id
                                       :logical-action-identity
                                       (:logical-action-identity claim)
                                       :attempt-identity
                                       (:attempt-identity claim)
                                       :ownership-epoch ownership-epoch
                                       :resolved-at
                                       (str (java.time.Instant/now))}))]
                          (if (:cognitect.anomalies/category result)
                            (assoc result :researcher-effect-claim claim)
                            (recur remaining))))))
                  effect-resolution-anomaly-category
                  (:cognitect.anomalies/category effect-resolution-result)
                  effect-resolution-conflict?
                  (= :cognitect.anomalies/conflict
                     effect-resolution-anomaly-category)
                  _ (when (and effect-resolution-anomaly-category
                               (not effect-resolution-conflict?))
                      (throw
                       (ex-info
                        (or (:cognitect.anomalies/message
                             effect-resolution-result)
                            "Researcher indeterminate effect resolution failed")
                        {:effect-resolution-result effect-resolution-result})))
                  ownership-conflict?
                  (or frontier-conflict? effect-resolution-conflict?)
                  carried-classification-context
                  (when (and (map? (:rlm base-node))
                             (true? (get-in base-node [:rlm :auto-classify?])))
                    (or (:classification-context durable-resume-state)
                        (durable-classification-context
                         context sheet-id tick-id node-id)))
                  auto-classification-required?
                  (and (map? (:rlm base-node))
                       (true? (get-in base-node [:rlm :auto-classify?]))
                       (nil? carried-classification-context)
                       (nil? (:context base-node)))
                  classification-timeout-ms
                  (when (and checkpointed? auto-classification-required?)
                    (long
                     (or (get-in base-node
                                 [:rlm :timeouts :classification-ms])
                         @(integrations/capability :ontology :default-rerank-timeout-ms))))
                  classification-started-at-ms
                  (when classification-timeout-ms
                    (long (campaign-now-ms-fn)))
                  workflow-deadline-ms
                  (get-in tick-ctx [:options :execution-deadline-ms])
                  classification-deadline-ms
                  (when classification-timeout-ms
                    (apply min
                           (remove nil?
                                   [(+ classification-started-at-ms
                                       classification-timeout-ms)
                                    (:campaign-deadline-ms campaign-timing)
                                    workflow-deadline-ms])))
                  classification-remaining-ms
                  (when classification-deadline-ms
                    (- (long classification-deadline-ms)
                       (long (campaign-now-ms-fn))))
                  classification-active
                  (when (and checkpointed? auto-classification-required?)
                    (atom true))
                  classification-effects
                  (when classification-active (atom []))
                  classification-prepared-node
                  (when classification-active (atom nil))
                  classification-ctx
                  (cond-> wedge-ctx
                    campaign-timing
                    (merge campaign-timing)
                    classification-deadline-ms
                    (assoc :classification-deadline-ms
                           classification-deadline-ms
                           :rerank-timeout-ms
                           (max 0 classification-remaining-ms))
                    classification-active
                    (assoc :classification-active?
                           #(true? @classification-active)
                           :classification-effects
                           classification-effects
                           :researcher-ownership-epoch
                           ownership-epoch))
                  classification-result
                  (when-not ownership-conflict?
                    (cond
                      carried-classification-context
                      {:value
                       (-> base-node
                           (assoc :context carried-classification-context)
                           (apply-r05-classifier-context wedge-ctx))}

                      (and checkpointed? auto-classification-required?)
                      (logic/bounded-call
                       classification-remaining-ms
                       #(let [prepared-node
                              (-> base-node
                                  (maybe-auto-classify-and-set-context
                                   classification-ctx)
                                  (apply-r05-classifier-context
                                   classification-ctx))
                              ensure-active!
                              (fn []
                                (when-not @classification-active
                                  (throw
                                   (ex-info
                                    "Researcher classification deadline expired"
                                    {:classification-expired? true
                                     :sheet-id sheet-id
                                     :tick-id tick-id
                                     :node-id node-id}))))
                              effect-priority
                              {
                               :domain-child-mint 5
                               :convergence-capture 10
                               :injection-record 20
                               :classification-outcome 100
                               :domain-classification-deferral 110}]
                          (reset! classification-prepared-node prepared-node)
                          (ensure-active!)
                          (let [ordered-effects
                                (->> @classification-effects
                                     (sort-by (fn [effect]
                                                (get effect-priority
                                                     (:kind effect)
                                                     50)))
                                     (mapv (fn [{:keys [kind command]}]
                                             (cond-> command
                                               (= :classification-outcome kind)
                                               (assoc :classification-context
                                                      (:context prepared-node))))))
                                commit-result
                                (cp/process-command
                                 (assoc context :command
                                        {:command/name
                                         :sheet/commit-researcher-classification
                                         :command/id (random-uuid)
                                         :command/timestamp (time/now)
                                         :sheet-id sheet-id
                                         :tick-id tick-id
                                         :node-id node-id
                                         :ownership-epoch ownership-epoch
                                         :effects ordered-effects}))]
                            (when-let [category
                                       (:cognitect.anomalies/category
                                        commit-result)]
                              (throw
                               (ex-info
                                (or (:cognitect.anomalies/message commit-result)
                                    "Researcher classification commit lost its durable fence")
                                {:category category
                                 :classification-commit-result commit-result}))))
                          (ensure-active!)
                          prepared-node)
                       #(reset! classification-active false))
                      :else
                      {:value
                       (-> base-node
                           (maybe-auto-classify-and-set-context wedge-ctx)
                           (apply-r05-classifier-context wedge-ctx))}))
                  _ (when-let [throwable (:throwable classification-result)]
                      (throw throwable))
                  classification-timeout-result?
                  (:timeout? classification-result)
                  expiration-result
                  (when classification-timeout-result?
                    (cp/process-command
                     (assoc context :command
                            {:command/name
                             :sheet/expire-researcher-classification
                             :command/id (random-uuid)
                             :command/timestamp (time/now)
                             :sheet-id sheet-id
                             :tick-id tick-id
                             :node-id node-id
                             :ownership-epoch ownership-epoch
                             :expired-at (str (java.time.Instant/now))})))
                  expiration-error
                  (let [category (:cognitect.anomalies/category
                                  expiration-result)]
                    (when (and category
                               (not= :cognitect.anomalies/conflict category))
                      (throw
                       (ex-info
                        (or (:cognitect.anomalies/message expiration-result)
                            "Researcher classification expiration failed")
                        {:classification-expiration-result expiration-result}))))
                  classification-commit-won-timeout-race?
                  (and classification-timeout-result?
                       (= :cognitect.anomalies/conflict
                          (:cognitect.anomalies/category expiration-result))
                       @classification-prepared-node
                       (researcher-classification-committed?
                        context tick-id node-id ownership-epoch))
                  classification-timeout?
                  (and classification-timeout-result?
                       (not classification-commit-won-timeout-race?))
                  node
                  (when (and (not ownership-conflict?)
                             (not classification-timeout?))
                    (or (:value classification-result)
                        @classification-prepared-node))
                  tool-context (:tool-context tick-ctx)
                  correlation-id (:correlation-id tick-ctx)
                  durable-iteration-records
                  (rm/get-researcher-iteration-records context sheet-id tick-id node-id)
                  legacy-checkpoint (some-> (rm/get-researcher-checkpoint
                                              context sheet-id tick-id node-id)
                                             :checkpoint)
                  durable-actions (rm/get-researcher-actions context sheet-id tick-id node-id)
                  durable-effect-claims
                  (rm/get-researcher-effect-claims context sheet-id tick-id node-id)
                  persist-v2-checkpoint!
                  (fn [checkpoint resume?]
                    (let [{:keys [resume-state iteration-record]}
                          (logic/checkpoint->v2-researcher-facts checkpoint)]
                      (cp/process-command
                       (assoc context :command
                              {:command/id (random-uuid)
                               :command/timestamp (time/now)
                               :command/name :sheet/checkpoint-researcher-iteration
                               :sheet-id sheet-id
                               :tick-id tick-id
                               :node-id node-id
                               :resume-state resume-state
                               :iteration-record iteration-record
                               :sandbox-snapshot-interval
                               (or (get-in node
                                           [:rlm :sandbox-snapshot-interval])
                                   1)
                               :resume? resume?
                               :inputs event-inputs}))))
                  enriched-context (cond-> (assoc context
                                                  :sheet-id sheet-id
                                                  :tick-id tick-id
                                                  :tick-options durable-tick-options
                                                  :workflow-deadline-ms
                                                  (get-in tick-ctx
                                                          [:options
                                                           :execution-deadline-ms])
                                                  :researcher-campaign-timing
                                                  campaign-timing
                                                  :campaign-now-ms-fn
                                                  campaign-now-ms-fn
                                                  :researcher-monotonic-ms-fn
                                                  researcher-monotonic-ms-fn
                                                  :researcher-quantum-started-monotonic-ms
                                                  researcher-quantum-started-monotonic-ms
                                                  :reserve-llm-call!
                                                  #(reserve-llm-call-or-cancel!
                                                    context tick-ctx sheet-id tick-id)
                                                  :reserve-provider-call!
                                                  (when budget-root
                                                    (fn [{:keys [iteration-index logical-action-identity
                                                                 provider-attempt-ordinal candidate-index node-attempt]}]
                                                      (reserve-durable-provider-call-or-cancel!
                                                        context sheet-id tick-id
                                                        (merge {:budget-sheet-id (:sheet-id budget-root)
                                                                :budget-tick-id (:tick-id budget-root) :budget (:budget budget-root)
                                                                :sheet-id sheet-id :tick-id tick-id :node-id node-id
                                                                :node-start-id (:event/id event) :exec-context exec-context
                                                                :logical-action-identity logical-action-identity
                                                                :provider-attempt-ordinal provider-attempt-ordinal}
                                                               (if checkpointed?
                                                                 {:admission-kind :campaign
                                                                  :campaign-sheet-id sheet-id :campaign-tick-id tick-id
                                                                  :campaign-node-id node-id :iteration-index iteration-index
                                                                  :ownership-epoch ownership-epoch}
                                                                 {:admission-kind :ordinary :node-start-id (:event/id event)
                                                                  :exec-context exec-context :candidate-index (or candidate-index 0)
                                                                  :node-attempt (or node-attempt 1)})))))
                                                  :persist-researcher-checkpoint!
                                                  (fn [checkpoint]
                                                    (persist-v2-checkpoint! checkpoint false))
                                                  :researcher-ownership-epoch
                                                  ownership-epoch
                                                  :researcher-classification-context
                                                  (when (and checkpointed?
                                                             (map? (:rlm base-node))
                                                             (true? (get-in base-node
                                                                            [:rlm :auto-classify?])))
                                                    (:context node))
                                                  :researcher-effect-claims
                                                  durable-effect-claims
                                                  :claim-researcher-effect!
                                                  (fn [{:keys [iteration-index
                                                               logical-action-identity
                                                               attempt-identity
                                                               attempt-ordinal kind]}]
                                                    (cp/process-command
                                                     (assoc context :command
                                                            {:command/id (random-uuid)
                                                             :command/timestamp (time/now)
                                                             :command/name :sheet/claim-researcher-effect
                                                             :sheet-id sheet-id
                                                             :tick-id tick-id
                                                             :node-id node-id
                                                             :budget-root-tick-id
                                                             (or (:tick-id budget-root) tick-id)
                                                             :iteration-index iteration-index
                                                             :logical-action-identity logical-action-identity
                                                             :attempt-identity attempt-identity
                                                             :attempt-ordinal attempt-ordinal
                                                             :ownership-epoch ownership-epoch
                                                             :kind kind
                                                             :claimed-at (str (java.time.Instant/now))})))
                                                  :complete-researcher-effect!
                                                  (fn [{:keys [logical-action-identity
                                                               attempt-identity result]}]
                                                    (cp/process-command
                                                     (assoc context :command
                                                            {:command/id (random-uuid)
                                                             :command/timestamp (time/now)
                                                             :command/name :sheet/complete-researcher-effect
                                                             :sheet-id sheet-id
                                                             :tick-id tick-id
                                                             :node-id node-id
                                                             :logical-action-identity logical-action-identity
                                                             :attempt-identity attempt-identity
                                                             :ownership-epoch ownership-epoch
                                                             :result result
                                                             :resolved-at (str (java.time.Instant/now))})))
                                                  :persist-researcher-action!
                                                  (fn [{:keys [action-id action-kind iteration result]}]
                                                    (cp/process-command
                                                     (assoc context :command
                                                            {:command/id (random-uuid)
                                                             :command/timestamp (time/now)
                                                             :command/name :sheet/record-researcher-action
                                                             :sheet-id sheet-id
                                                             :tick-id tick-id
                                                             :node-id node-id
                                                             :action-id action-id
                                                             :action-kind action-kind
                                                             :iteration iteration
                                                             :result result
                                                             :completed-at (str (java.time.Instant/now))})))
                                                  :node-id node-id)
                                     tool-context (assoc :tool-context tool-context)
                                     correlation-id (assoc :orc/correlation-id correlation-id)
                                     legacy-checkpoint
                                     (assoc :researcher-checkpoint legacy-checkpoint)
                                     durable-resume-state
                                     (assoc :researcher-resume-state durable-resume-state)
                                     (seq durable-iteration-records)
                                     (assoc :researcher-iteration-records
                                            durable-iteration-records)
                                     (seq durable-actions)
                                     (assoc :researcher-actions durable-actions))
                  raw-result
                  (cond
                    ownership-conflict?
                    {:status :running :researcher-frontier-conflict? true}

                    classification-timeout?
                    {:status :timeout
                     :timeout-kind :classification
                     :error "Researcher classification deadline exceeded"
                     :duration-ms
                     (max 0 (- (long (campaign-now-ms-fn))
                               (:campaign-started-at-ms campaign-timing)))}

                    provider
                    (execute-repl-researcher
                     node blackboard provider enriched-context)

                    :else
                    {:status :failure :error "No ORC LLM provider configured"})
                  optional-writes (set (get-in node [:options :optional-writes]))
                  missing-or-nil-required-writes
                  (when (= :success (:status raw-result))
                    (let [raw-outputs (or (:outputs raw-result) {})]
                      (into []
                            (filter #(and (not (contains? optional-writes %))
                                          (or (not (contains? raw-outputs %))
                                              (nil? (get raw-outputs %)))))
                            (:writes node))))
                  contract-result
                  (if (seq missing-or-nil-required-writes)
                    (assoc raw-result
                           :status :failure
                           :error (str "Required researcher outputs are missing or nil: "
                                       (pr-str missing-or-nil-required-writes))
                           :outputs {}
                           :rejected-writes
                           (merge (select-keys (:outputs raw-result) (:writes node))
                                  (zipmap missing-or-nil-required-writes (repeat nil))))
                    raw-result)
                  declared-outputs (when (= :success (:status contract-result))
                                     (logic/strip-nil-researcher-optional-writes
                                      (select-keys (:outputs contract-result) (:writes node))
                                      optional-writes))
                  validated-result (logic/validate-leaf-outputs
                                     blackboard
                                     (if (= :success (:status contract-result))
                                       (assoc contract-result :outputs declared-outputs)
                                       contract-result)
                                     false)
                  result (if (= :success (:status validated-result))
                           (assoc validated-result :outputs
                                  (merge (apply dissoc (:outputs raw-result) (:writes node))
                                         (:outputs validated-result)))
                           validated-result)
                  {:keys [status outputs rejected-writes missing-write-keys error duration-ms
                          failure-kind provider-evidence model-fallback
                          generated-tree-raw generated-tree-source
                          iteration-reasonings usage own-usage iterations block-payload]} result
                  effective-status (if (= :tree-generated status) :tree-generated status)
                  _ (when (and usage (not= :running effective-status))
                      (logic/add-usage! tick-id usage))
                  terminal-quantum-observation
                  (when (and checkpointed?
                             (not ownership-conflict?)
                             (not= :running effective-status))
                    (let [observed-ms
                          (max 0
                               (- (long (researcher-monotonic-ms-fn))
                                  researcher-quantum-started-monotonic-ms))
                          prior-max
                          (max (long (or (:max-observed-quantum-duration-ms
                                         durable-campaign)
                                        0))
                               (long (or (:max-observed-quantum-duration-ms
                                         durable-resume-state)
                                        0)))]
                      {:observed-quantum-duration-ms observed-ms
                       :max-observed-quantum-duration-ms
                       (max prior-max observed-ms)}))
                  effective-outputs (cond-> (or outputs {})
                                      generated-tree-raw (assoc :generated-tree-raw generated-tree-raw)
                                      generated-tree-source
                                      (assoc :generated-tree-source generated-tree-source)
                                      (seq iteration-reasonings) (assoc :iteration-reasonings (vec iteration-reasonings))
                                      (seq iterations) (assoc :iterations (vec iterations))
                                      tool-context (assoc :tool-context tool-context))]
              (cond
                (and checkpointed? @researcher-lease-lost?)
                nil

                ownership-conflict?
                nil

                (= :running effective-status)
                (persist-v2-checkpoint! (:checkpoint result) true)

                :else
                (do
              (when (some? generated-tree-raw)
                (es/append event-store
                           {:tenant-id (:tenant-id context)
                            :events [(->event
                                      {:type :rlm/tree-generated
                                       :tags #{[:sheet sheet-id]
                                               [:tick tick-id]
                                               [:node node-id]}
                                       :body (cond->
                                              {:tree-id (random-uuid)
                                               :execution-id tick-id
                                               :raw-dsl generated-tree-raw
                                               :generated-at (str (java.time.Instant/now))
                                               :sheet-id sheet-id
                                               :node-id node-id}
                                               generated-tree-source
                                               (assoc :source-edn
                                                      generated-tree-source))})]}))
              (when (and (not checkpointed?) (seq iterations))
                (es/append event-store
                           {:tenant-id (:tenant-id context)
                            :events [(->event
                                      {:type :rlm/researcher-iterations
                                       :tags #{[:sheet sheet-id]
                                               [:tick tick-id]}
                                       :body {:execution-id tick-id
                                              :iterations (vec iterations)
                                              :iteration-count (count iterations)
                                              :emitted-at (str (java.time.Instant/now))}})]}))
              (complete-node-or-fail! context
                (cond-> {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/complete-node-execution
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :node-id node-id
                                :node-type :repl-researcher
                                :status effective-status
                                :writes (logic/normalize-output-keys
                                          (if (= :blocked effective-status)
                                            (into {} (remove (comp nil? val))
                                                  (or effective-outputs {}))
                                            (or effective-outputs {})))}
                         ownership-epoch
                         (assoc :researcher-ownership-epoch ownership-epoch)
                         duration-ms (assoc :duration-ms duration-ms)
                         terminal-quantum-observation
                         (merge terminal-quantum-observation)
                         error (assoc :error error)
                         failure-kind (assoc :failure-kind failure-kind)
                         provider-evidence (assoc :provider-evidence provider-evidence)
                         model-fallback (assoc :model-fallback model-fallback)
                         (seq missing-write-keys) (assoc :missing-write-keys missing-write-keys)
                         (seq rejected-writes)
                         (assoc :rejected-writes (logic/normalize-output-keys rejected-writes))
                         (or (seq read-inputs) (seq exec-context))
                         (assoc :inputs (merge read-inputs exec-context))
                         (seq read-inputs)
                         (assoc :read-sources (logic/read-sources (:reads base-node) blackboard exec-context))
                         (= :blocked effective-status) (assoc :block-payload block-payload)
                         (seq usage) (assoc :usage usage)
                         (and (seq usage) (seq own-usage)) (assoc :own-usage own-usage)
                         (:model node) (assoc :model (:model node)))))))))
            (catch Exception e
              (let [researcher-terminal?
                    (and checkpointed?
                         @researcher-frontier-claimed?
                         (some? @researcher-ownership-epoch-ref))
                    researcher-frontier-command-failed?
                    (and checkpointed?
                         @researcher-frontier-claim-attempted?
                         (not @researcher-frontier-claimed?)
                         (some? @researcher-ownership-epoch-ref))
                    terminal-observation
                    (when (and researcher-terminal?
                               (some? @researcher-quantum-started-monotonic-ms-ref))
                      (try
                        (let [observed-ms
                              (max 0
                                   (- (long (researcher-monotonic-ms-fn))
                                      (long @researcher-quantum-started-monotonic-ms-ref)))
                              latest-resume-state
                              (:resume-state
                               (rm/get-researcher-resume-state
                                context sheet-id tick-id node-id))
                              latest-campaign
                              (rm/get-researcher-campaign context tick-id node-id)
                              prior-max
                              (max (long (or (:max-observed-quantum-duration-ms
                                             latest-resume-state)
                                            0))
                                   (long (or (:max-observed-quantum-duration-ms
                                             latest-campaign)
                                            0)))]
                          {:observed-quantum-duration-ms observed-ms
                           :max-observed-quantum-duration-ms
                           (max prior-max observed-ms)})
                        (catch Exception _
                          nil)))
                    command
                    (cond
                      (and checkpointed? @researcher-lease-lost?)
                      nil

                      researcher-terminal?
                      (cond-> {:command/id (random-uuid)
                               :command/timestamp (time/now)
                               :command/name :sheet/complete-node-execution
                               :sheet-id sheet-id
                               :tick-id tick-id
                               :node-id node-id
                               :node-type :repl-researcher
                               :researcher-ownership-epoch
                               @researcher-ownership-epoch-ref
                               :status :failure
                               :writes {}
                               :error (.getMessage e)}
                        terminal-observation (merge terminal-observation))

                      researcher-frontier-command-failed?
                      {:command/id (random-uuid)
                       :command/timestamp (time/now)
                       :command/name :sheet/fail-node-execution
                       :sheet-id sheet-id
                       :tick-id tick-id
                       :node-id node-id
                       :researcher-expected-frontier-epoch
                       (dec (long @researcher-ownership-epoch-ref))
                       :error (.getMessage e)}

                      checkpointed?
                      nil

                      :else
                      {:command/id (random-uuid)
                       :command/timestamp (time/now)
                       :command/name :sheet/fail-node-execution
                       :sheet-id sheet-id
                       :tick-id tick-id
                       :node-id node-id
                       :error (.getMessage e)})]
                (when command
                  (cp/process-command (assoc context :command command)))))
            (finally
              (try
                (when-let [researcher-lease-monitor
                           @researcher-lease-monitor-ref]
                  (logic/stop-ownership-monitor!
                   researcher-lease-monitor))
                (finally
                  (logic/end! lease)
                  (when-let [worker-finished!
                             (:researcher-worker-finished-fn context)]
                    (try
                      (worker-finished!)
                      (catch Throwable _
                        nil)))))))))
        (catch Throwable t
          (logic/end! lease)
          (throw t)))))
        nil)))

(defn complete-delegate-parent!
  "Consume a terminal child tick into its durably linked parent delegate.
   The child tick id plus parent execution context is the idempotency key, so
   duplicate wakes are harmless without suppressing a distinct invocation in
   a later parent iteration."
  ([context child-tick-id result]
   (complete-delegate-parent! context child-tick-id result nil))
  ([context child-tick-id result current-parent-exec-context]
   (let [child-ctx (rm/get-tick-execution-context context child-tick-id)
         options (:options child-ctx)
         parent-sheet-id (:delegate-parent-sheet-id options)
         parent-tick-id (:parent-tick-id (rm/get-tick context child-tick-id))
         parent-node-id (:delegate-parent-node-id options)
         exec-context (or current-parent-exec-context
                          (:delegate-parent-exec-context options)
                          {})
         read-inputs (or (:delegate-parent-read-inputs options) {})
         read-sources (:delegate-parent-read-sources options)]
    (when (and parent-sheet-id parent-tick-id parent-node-id)
      (let [parent-ctx (rm/get-tick-execution-context context parent-tick-id)
            node (get (:nodes-by-id parent-ctx) parent-node-id)
            child-outputs (:outputs result)
            outputs (reduce (fn [acc k]
                              (let [kw-key (if (keyword? k) k (keyword k))
                                    str-key (name k)]
                                (cond
                                  (contains? child-outputs kw-key) (assoc acc k (get child-outputs kw-key))
                                  (contains? child-outputs str-key) (assoc acc k (get child-outputs str-key))
                                  :else acc)))
                            {}
                            (:writes node))
            child-sources (:output-sources result)
            output-sources (reduce (fn [acc k]
                                     (let [source (or (get child-sources k)
                                                      (get child-sources (name k)))]
                                       (if source (assoc acc k source) acc)))
                                   {}
                                   (:writes node))
            copied-outputs (apply dissoc outputs (keys output-sources))]
        (complete-node-or-fail! context
          (cond-> {:command/id (random-uuid)
                         :command/timestamp (time/now)
                         :command/name :sheet/complete-node-execution
                         :sheet-id parent-sheet-id
                         :tick-id parent-tick-id
                         :node-id parent-node-id
                         :completion-id child-tick-id
                         :node-type :delegate
                         :status (:status result)
                         :writes copied-outputs
                         :write-sources (logic/normalize-output-keys output-sources)
                         :write-references? true
                         :inputs (merge read-inputs exec-context)}
                  (seq read-sources) (assoc :read-sources read-sources)
                  (:error result) (assoc :error (:error result)))))))))

(defn deliver-delegate-child-completion
  "Wake a parent delegate from the child's durable terminal event."
  [{:keys [event] :as context}]
  (when (not= :running (:root-status event))
    (when-let [result (commands/durable-terminal-result context (:tick-id event))]
      (complete-delegate-parent! context (:tick-id event) result))))

(defn execute-delegate-node
  "Execute delegate node by dispatching to target sheet.
   Maps parent blackboard inputs to target, executes, maps outputs back.

   Delegate nodes execute another sheet (workflow) with isolated blackboard:
   - :reads keys are passed from parent blackboard to target inputs
   - :writes keys are received from target outputs back to parent blackboard
   - Execution is async with timeout enforcement

   Follows ORC async pattern: future + cp/process-command for completion."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (= :delegate (:type node))
      (let [raw-blackboard (resolve-blackboard-values context sheet-id tick-id
                                                      (or (:reads node) []))
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k)
                                          (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            exec-context (logic/extract-execution-context event-inputs)
            correlation-id (:correlation-id
                            (tick-ctx-of context tick-id))

            target-sheet-id (:target-sheet-id node)
            read-keys (:reads node)
            write-keys (:writes node)
            parent-tick-ctx (tick-ctx-of context tick-id)
            inherited-budget-root
            (durable-budget-root context sheet-id tick-id parent-tick-ctx)
            child-exec-context
            (cond-> (merge context (logic/ephemeral-context-for tick-id))
              (:tool-context parent-tick-ctx)
              (assoc :tool-context (:tool-context parent-tick-ctx)))
            parent-deadline-ms (get-in parent-tick-ctx [:options :execution-deadline-ms])
            local-timeout-ms (or (:delegate-timeout-ms node) 300000)
            timeout-ms (if parent-deadline-ms
                         (max 1 (min local-timeout-ms
                                     (- parent-deadline-ms (System/currentTimeMillis))))
                         local-timeout-ms)
            parent-max-ticks (or (get-in parent-tick-ctx [:options :max-ticks])
                                 logic/*max-tick-iterations*)
            parent-iteration (or (:iteration (rm/get-tick context tick-id)) 1)
            parent-remaining-ticks (max 1 (- parent-max-ticks (dec parent-iteration)))
            max-ticks (min (or (:delegate-max-ticks node) logic/*max-tick-iterations*)
                           parent-remaining-ticks)

            target-inputs (reduce
                           (fn [acc k]
                             (if-let [entry (get blackboard k)]
                               (assoc acc (name k) (:value entry))
                               acc))
                           {}
                           read-keys)
            target-input-sources (logic/read-sources read-keys blackboard exec-context)
            read-inputs (logic/extract-read-inputs read-keys blackboard)]

        (let [child-tick-id (logic/delegate-child-tick-id tick-id node-id exec-context)
              child-started? (fn []
                               (seq (into [] (es/read event-store
                                                     {:tenant-id (:tenant-id context)
                                                      :types #{:sheet/tree-tick-started}
                                                      :tags #{[:tick child-tick-id]}}))))
              child-started-before-claim? (child-started?)
              recovering? (some? (:resumed-from-event-id event))]
          (when-let [terminal-result (and child-started-before-claim?
                                          (commands/durable-terminal-result context child-tick-id))]
            (complete-delegate-parent! context child-tick-id terminal-result exec-context))
          (when (and (or (not child-started-before-claim?) recovering?)
                     (not (commands/durable-terminal-result context child-tick-id))
                     (logic/claim-delegate! child-tick-id))
            (let [child-started-after-claim? (child-started?)
                  terminal-after-claim (when child-started-after-claim?
                                         (commands/durable-terminal-result
                                          context child-tick-id))]
              (cond
                terminal-after-claim
                (do
                  (complete-delegate-parent! context child-tick-id
                                             terminal-after-claim exec-context)
                  (logic/release-delegate! child-tick-id))

                (and child-started-after-claim? (not recovering?))
                (logic/release-delegate! child-tick-id)

                :else
                (let [lease (begin!
                             context {:sheet-id sheet-id :tick-id tick-id
                                      :node-id node-id :exec-context exec-context
                                      :start-event-id (:event/id event)})]
                (try
                (future
          (try
            (let [start-time (System/currentTimeMillis)
                  _ (logic/link-child! tick-id child-tick-id)
                  child-result (commands/execute child-exec-context target-sheet-id
                                           target-inputs
                                           :timeout-ms timeout-ms
                                           :execution-deadline-ms parent-deadline-ms
                                           :max-ticks max-ticks
                                           :tick-id child-tick-id
                                           :parent-tick-id tick-id
                                           :delegate-parent-sheet-id sheet-id
                                           :delegate-parent-node-id node-id
                                           :delegate-parent-exec-context exec-context
                                           :delegate-parent-read-inputs read-inputs
                                           :delegate-parent-read-sources
                                           (logic/normalize-output-keys target-input-sources)
                                           :correlation-id correlation-id
                                           :input-sources target-input-sources
                                           :llm-call-budget
                                           (:budget inherited-budget-root)
                                           :llm-budget-root-sheet-id
                                           (:sheet-id inherited-budget-root)
                                           :llm-budget-root-tick-id
                                           (:tick-id inherited-budget-root)
                                           :assessment-origin
                                           (get-in parent-tick-ctx
                                                   [:options :assessment-origin])
                                           :return-references? true)
                  child-started-after? (seq (into [] (es/read event-store
                                                             {:tenant-id (:tenant-id context)
                                                              :types #{:sheet/tree-tick-started}
                                                              :tags #{[:tick child-tick-id]}})))
                  _duration-ms (- (System/currentTimeMillis) start-time)]
              (when (and (= :failure (:status child-result))
                         (not child-started-after?))
                (process-lifecycle-command! context
                  {:command/id (random-uuid)
                         :command/timestamp (time/now)
                         :command/name :sheet/fail-node-execution
                         :sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :error (:error child-result)}))
              nil)

            (catch Exception e
              (process-lifecycle-command! context
                {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :sheet/fail-node-execution
                        :sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :error (or (.getMessage e) (str (class e)))}))
            (finally
              (logic/release-delegate! child-tick-id)
              (logic/end! lease)))
            nil)
                (catch Throwable t
                  (logic/end! lease)
                  (throw t))))))))))))

(defn execute-condition-node
  "Execute a condition node when node-execution-started is emitted.
   Evaluates the check against blackboard and returns success/failure/running.
   Also handles llm-condition nodes via async LLM execution."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        exec-context (logic/extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)
        node-type (:type node)]
    (cond
      (= :condition node-type)
      (let [check (:check node)
            blackboard (resolve-blackboard-values context sheet-id tick-id
                                                  (if-let [k (:key check)] [k] []))
            on-fail (get check :on-fail :failure)
            passed? (if check
                      (logic/evaluate-condition-check check blackboard)
                      false)
            status (if passed?
                     :success
                     on-fail)]
        {:result/events
         [(->event
           {:type :sheet/node-execution-completed
            :tags #{[:sheet sheet-id]
                    [:node node-id]
                    [:tick tick-id]}
            :body (cond-> {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id node-id
                           :status status}
                    (seq exec-context) (assoc :inputs exec-context))})]})

      (= :llm-condition node-type)
      (let [tick-ctx (tick-ctx-of context tick-id)
            node (logic/apply-instruction-override node (:instruction-overrides tick-ctx))
            blackboard (resolve-blackboard-values context sheet-id tick-id
                                                  (or (:reads node) []))
            provider (:llm-provider context)
            fail-command
            (fn [message]
              {:command/id (random-uuid)
               :command/timestamp (time/now)
               :command/name :sheet/fail-node-execution
               :sheet-id sheet-id
               :tick-id tick-id
               :node-id node-id
               :error message})]
        (logic/register-work!
         tick-id node-id
         (future
           (try
             (if (rm/is-tick-or-ancestor-cancelled? context tick-id)
               (process-lifecycle-command! context
                 (fail-command "tick cancelled"))
               (let [result (if provider
                              (execute-llm-condition
                               node blackboard provider
                               :options (provider-policy-options
                                         context tick-ctx sheet-id tick-id node-id
                                         exec-context))
                              {:status :failure
                               :error "No llm-provider configured for LLM condition"})
                     {:keys [status error duration-ms usage model failure-kind
                             provider-evidence resolved-model]} result
                     answer (:result result)
                     final-status (if (= :success status) :success status)
                     final-status (if (#{:success :failure :timeout} final-status)
                                    final-status
                                    :failure)
                     reads (logic/extract-read-inputs (:reads node) blackboard)]
                 (when usage (logic/add-usage! tick-id usage))
                 (complete-node-or-fail! context
                   (cond-> {:command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :command/name :sheet/complete-node-execution
                                  :sheet-id sheet-id
                                  :tick-id tick-id
                                  :node-id node-id
                                  :node-type (:type node)
                                  :status final-status
                                  :writes {}}
                           duration-ms (assoc :duration-ms duration-ms)
                           error (assoc :error error)
                           (boolean? answer) (assoc :condition-answer answer)
                           (and (not= :success final-status) failure-kind)
                           (assoc :failure-kind failure-kind)
                           (and (not= :success final-status) provider-evidence)
                           (assoc :provider-evidence provider-evidence)
                           (or (seq exec-context) (seq reads))
                           (assoc :inputs (merge exec-context reads))
                           (seq reads)
                           (assoc :read-sources (logic/read-sources (:reads node) blackboard exec-context))
                           (seq usage) (assoc :usage usage)
                           model (assoc :model model)
                           (:model node) (assoc :requested-model (:model node))
                           resolved-model (assoc :resolved-model resolved-model)))))
             (catch Throwable t
               (if (instance? Exception t)
                 (process-lifecycle-command! context
                   (fail-command (or (.getMessage t) (str (class t)))))
                 (throw t))))))
        nil)

      :else nil)))

(defn- ephemeral-routing-active? [context tick-id nodes-by-id]
  (and (not= :legacy (get-in (tick-ctx-of context tick-id)
                             [:options :durability-mode]))
       (logic/ephemeral-routing-tree? nodes-by-id)))

;; =============================================================================
;; Composite Node Execution Processor
;; =============================================================================

(defn execute-composite-node
  "Handle execution of sequence/fallback nodes.
   When started, begin executing the first child.
   When a child completes, decide whether to continue or finish."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        exec-context (logic/extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (#{:sequence :fallback} (:type node))
      (let [children-ids (:children-ids node)
            invalid? (contains? node :model-fallback-policy)]
        (if invalid?
          {:result/events
           [(->event {:type :sheet/node-execution-completed
                      :tags #{[:sheet sheet-id] [:node node-id] [:tick tick-id]}
                      :body (cond-> {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                                     :status :failure :error "Composite model fallback is obsolete; configure fallback-models on model nodes"}
                              (seq exec-context) (assoc :inputs exec-context))})]}
          (if (empty? children-ids)
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body (cond-> {:sheet-id sheet-id
                             :tick-id tick-id
                             :node-id node-id
                             :status (if (= :sequence (:type node)) :success :failure)}
                      (seq exec-context) (assoc :inputs exec-context))})]}
          (let [first-child-id (first children-ids)
                inputs (merge exec-context event-inputs)]
            {:result/events
             [(->event
               {:type :sheet/node-execution-started
                :tags (node-execution-tags context sheet-id tick-id first-child-id)
                :body (commands/stamp
                       context (get nodes-by-id first-child-id)
                       {:sheet-id sheet-id
                        :tick-id tick-id
                        :node-id first-child-id
                        :inputs inputs})})]})))))))

;; =============================================================================
;; Parallel Node Execution Processor
;; =============================================================================

(defn execute-parallel-node
  "Handle execution of parallel nodes.
   When started, execute ALL children concurrently.
   Completion is handled by handle-parallel-child-completion."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        exec-context (logic/extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (= :parallel (:type node))
      (let [children-ids (:children-ids node)]
        (if (empty? children-ids)
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body (cond-> {:sheet-id sheet-id
                             :tick-id tick-id
                             :node-id node-id
                             :status :success}
                      (seq exec-context) (assoc :inputs exec-context))})]}
          {:result/events
           (vec
            (for [child-id children-ids]
              (->event
               {:type :sheet/node-execution-started
                :tags (node-execution-tags context sheet-id tick-id child-id)
                :body (commands/stamp
                       context (get nodes-by-id child-id)
                       {:sheet-id sheet-id
                        :tick-id tick-id
                        :node-id child-id
                        :inputs exec-context})})))})))))

(defn- count-child-statuses
  "Count how many children of a node have completed with each status.
   Filters by execution context to distinguish between map-each iterations.
   Returns {:success n :failure n :total-children n :completed n}"
  [{:keys [event-store tenant-id] :as ctx} sheet-id tick-id parent-node exec-context]
  (let [children-ids (:children-ids parent-node)
        total (count children-ids)
        events (vec (es/read event-store
                                      {:types #{:sheet/node-execution-completed}
                                       :tags #{[:tick tick-id]}
                                       :tenant-id tenant-id}))
        child-set (set children-ids)
        child-completions (filter (fn [e]
                                    (and (child-set (:node-id e))
                                         (logic/matches-execution-context? e exec-context)))
                                  events)
        success-count (count (filter #(= :success (:status %)) child-completions))
        failure-count (count (filter #(= :failure (:status %)) child-completions))]
    {:success success-count
     :failure failure-count
     :total-children total
     :completed (count child-completions)}))

(defn- map-each-item-result
  "Reconstruct one direct map child's result from its durable completion and
   canonical value writes. This is shared by live delivery and recovery so a
   survivor has exactly the same observable value as an uninterrupted item."
  [context tick-events state completion]
  (let [{:keys [items item-key output-key source-key]} state
        item-index (get-in completion [:inputs ::map-each-index])
        map-each-parent-id (get-in completion [:inputs ::map-each-parent])
        item (nth items item-index)
        writes (rm/resolve-writes context (:tenant-id context)
                                         (:tick-id completion) completion)
        special? #{source-key output-key}
        effective-writes
        (or
         (not-empty writes)
         (not-empty
          (reduce-kv (fn [acc k v] (if (special? k) acc (assoc acc k v)))
                     {}
                     (get (logic/writes-by-iteration tick-events)
                          {::map-each-index item-index
                           ::map-each-parent map-each-parent-id}
                          {})))
         (let [latest (merge (rm/tick-seeds
                              context (:tenant-id context) (:tick-id completion))
                             (logic/latest-values tick-events))]
           (reduce-kv
            (fn [acc k v]
              (if (and (keyword? k)
                       (not (special? k))
                       (not= (namespace k) (namespace ::_)))
                (assoc acc k v)
                acc))
            {}
            latest))
         {})]
    (if (= :success (:status completion))
      (let [updated-item (get effective-writes item-key item)
            other-writes (reduce-kv
                          (fn [acc k v]
                            (if (and (not= k item-key)
                                     (not= k source-key)
                                     (not= k output-key))
                              (assoc acc (keyword k) v)
                              acc))
                          {}
                          effective-writes)]
        (if (map? updated-item)
          (merge updated-item other-writes)
          (if (seq other-writes) other-writes updated-item)))
      (assoc (if (map? item) item {:__original item})
             :__status :failure
             :__error (:error completion)))))

(defn- map-each-result-events
  [context sheet-id tick-id parent-id output-key into-value status summary]
  (let [validated (logic/validate-leaf-outputs
                    (resolve-blackboard context sheet-id tick-id)
                    {:status :success :outputs {output-key into-value}})
        rejected? (= :failure (:status validated))
        completion-body (cond-> {:sheet-id sheet-id
                                 :tick-id tick-id
                                 :node-id parent-id
                                 :node-type :map-each
                                 :status (if rejected? :failure status)
                                 :write-keys (if rejected? [] [output-key])
                                 :write-profile (logic/todo-processors-profile-values (:outputs validated))}
                          summary (assoc :partial-summary summary)
                          rejected? (assoc :error (:error validated)
                                           :rejected-write-keys [output-key]
                                           :rejected-write-profile (logic/todo-processors-profile-values (:rejected-writes validated))))]
    [
     (if rejected?
       (->event
        {:type :sheet/execution-value-rejected
         :tags #{[:sheet sheet-id] [:tick tick-id]}
         :body (logic/prepare-write
                 context {:sheet-id sheet-id :tick-id tick-id :node-id parent-id
                          :key output-key :value into-value})})
       (logic/make-bb-write-event context sheet-id tick-id output-key
                            (get-in validated [:outputs output-key])
                            {:node-id parent-id}))
     (->event
      {:type :sheet/node-execution-completed
       :tags #{[:sheet sheet-id] [:node parent-id] [:tick tick-id]}
       :body completion-body})]))

(defn- map-each-completion-events
  [context sheet-id tick-id parent-id state results completed-count]
  (let [total-items (count (:items state))
        [status summary output]
        (logic/classify-map-each-outcome {:results results :item-count total-items})
        parent-node (get (resolve-nodes-by-id context sheet-id tick-id) parent-id)
        into-value (if (:preserve-failures? parent-node) results output)]
    (into [(->event
            {:type :sheet/map-each-progress-updated
             :tags #{[:sheet sheet-id] [:node parent-id] [:tick tick-id]}
             :body {:sheet-id sheet-id :tick-id tick-id :node-id parent-id
                    :item-index completed-count :total-items total-items}})]
          (map-each-result-events context sheet-id tick-id parent-id
                                  (:output-key state) into-value status summary))))

(defn- map-each-start-events
  [context sheet-id tick-id parent-id state indices]
  (mapcat
   (fn [idx]
     (let [item (nth (:items state) idx)
           child-id (:child-id state)
           child-node (get (resolve-nodes-by-id context sheet-id tick-id) child-id)]
       [(logic/make-bb-write-event context sheet-id tick-id (:item-key state) item
                             {:node-id child-id
                              :input-seed? true
                              :exec-context {::map-each-index idx
                                             ::map-each-parent parent-id}})
        (->event
         {:type :sheet/node-execution-started
          :tags (node-execution-tags context sheet-id tick-id child-id)
          :body (commands/stamp
                 context child-node
                 {:sheet-id sheet-id :tick-id tick-id :node-id child-id
                  :inputs {(:item-key state) item
                           ::map-each-index idx
                           ::map-each-parent parent-id}})})]))
   indices))

(defn- declared-map-each-source
  "The immutable source a map parent consumed when its original start ran.

   Some callers inline the declared source in the parent start. A sequence
   normally does not: its child start carries only execution context and the
   map resolves the preceding child's canonical write. In that case replay
   the value log only THROUGH the original parent start. Later item writes may
   overwrite the same source key and must not redefine map membership."
  [context tick-id parent-id source-key tick-events]
  (let [original-start
        (first (filter #(and (= :sheet/node-execution-started (:event/type %))
                             (= parent-id (:node-id %))
                             (nil? (:resumed-from-event-id %)))
                       tick-events))
        start-inputs (:inputs original-start)]
    (if (contains? start-inputs source-key)
      (get start-inputs source-key)
      (let [events-before-start
            (if original-start
              (take-while #(not= (:event/id original-start) (:event/id %))
                          tick-events)
              [])]
        (get
         (reduce
          (fn [values event]
            (case (:event/type event)
              :sheet/execution-value-written
              (assoc values (:key event) (:value event))

              :sheet/execution-value-referenced
              (assoc values (:key event)
                     (rm/resolve-source context (:tenant-id context)
                                               (:source event)))

              values))
          (rm/tick-seeds context (:tenant-id context) tick-id)
          events-before-start)
         source-key)))))

(defn recover-map-each-coordinator!
  "Synchronously rebuild one map coordinator cache from durable item contexts.

   Runtime calls this before it emits any recovered child start, closing the
   race between asynchronous parent recovery and a fast child completion. The
   resumed parent processor calls it again to derive dispatch/finalization;
   reconstruction is idempotent because the event log is authoritative."
  [context sheet-id tick-id parent-id]
  (let [node (get (resolve-nodes-by-id context sheet-id tick-id) parent-id)
        source-key (:source-key node)
        tick-events (rm/read-tick-events
                     context (:tenant-id context) tick-id)
        declared-source (declared-map-each-source context tick-id parent-id
                                                  source-key tick-events)
        items (when (sequential? declared-source) (vec declared-source))
        child-id (first (:children-ids node))]
    (when (and (= :map-each (:type node))
               (sequential? items)
               child-id)
      (let [total-items (count items)
            base-state {:items items
                        :current-index 0
                        :results (vec (repeat total-items nil))
                        :completed-indices #{}
                        :in-flight #{}
                        :max-concurrency (or (:max-concurrency node) 1)
                        :child-id child-id
                        :item-key (:item-key node)
                        :output-key (:output-key node)
                        :source-key source-key}
            valid-index? #(and (integer? %) (<= 0 %) (< % total-items))
            belongs? (fn [event]
                       (let [event-inputs (:inputs event)
                             idx (get event-inputs ::map-each-index)]
                         (and (= child-id (:node-id event))
                              (= parent-id (get event-inputs ::map-each-parent))
                              (valid-index? idx))))
            completions-by-index
            (reduce (fn [acc completion]
                      (if (and (= :sheet/node-execution-completed
                                  (:event/type completion))
                               (belongs? completion))
                        (assoc acc
                               (get-in completion [:inputs ::map-each-index])
                               completion)
                        acc))
                    {}
                    tick-events)
            completed-indices (set (keys completions-by-index))
            started-indices
            (into #{}
                  (keep (fn [start]
                          (when (and (= :sheet/node-execution-started
                                        (:event/type start))
                                     (belongs? start))
                            (get-in start [:inputs ::map-each-index]))))
                  tick-events)
            in-flight (reduce disj started-indices completed-indices)
            results (reduce-kv
                     (fn [acc idx completion]
                       (assoc acc idx
                              (map-each-item-result context tick-events
                                                    base-state completion)))
                     (:results base-state)
                     completions-by-index)
            recovered-state (assoc base-state
                                   :results results
                                   :completed-indices completed-indices
                                   :in-flight in-flight)
            installed (atom nil)
            state-key (logic/map-each-key tick-id parent-id)]
        (swap! logic/map-each-state
               (fn [all-state]
                 (let [current (get all-state state-key)
                       current-completed (or (:completed-indices current) #{})
                       merged-completed (into completed-indices current-completed)
                       merged-results
                       (reduce (fn [acc idx]
                                 (assoc acc idx (get (:results current) idx)))
                               results
                               current-completed)
                       merged-in-flight
                       (reduce disj
                               (into in-flight (or (:in-flight current) #{}))
                               merged-completed)
                       available
                       (filter #(and (not (contains? merged-completed %))
                                     (not (contains? merged-in-flight %)))
                               (range total-items))
                       capacity (max 0 (- (:max-concurrency base-state)
                                          (count merged-in-flight)))
                       dispatch-indices (vec (take capacity available))
                       next-state (assoc recovered-state
                                         :results merged-results
                                         :completed-indices merged-completed
                                         :in-flight (into merged-in-flight
                                                          dispatch-indices)
                                         :recovery-dispatch-indices
                                         dispatch-indices)]
                   (reset! installed next-state)
                   (assoc all-state state-key next-state))))
        (let [state @installed]
          {:state state
           :dispatch-indices (:recovery-dispatch-indices state)
           :completed? (= total-items (count (:completed-indices state)))})))))

(defn resume-in-progress!
  "Recover durable frontiers using the same coordinator as the map-each processor."
  [context]
  (resume-frontiers! context recover-map-each-coordinator!))

(defn execute-map-each-node
  "Handle execution of map-each nodes.
   Iterates over a list in the blackboard, executing the child subtree for each item.
   Supports optional concurrency via :max-concurrency."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (= :map-each (:type node))
      (let [source-key (:source-key node)
            item-key (:item-key node)
            output-key (:output-key node)
            max-concurrency (or (:max-concurrency node) 1)
            children-ids (:children-ids node)
            child-id (first children-ids)
            blackboard (resolve-blackboard-values context sheet-id tick-id
                                                  [source-key])
            source-list (if (:resumed-from-event-id event)
                          (declared-map-each-source
                           context tick-id node-id source-key
                           (rm/read-tick-events
                            context (:tenant-id context) tick-id))
                          (or (get event-inputs source-key)
                              (get-in blackboard [source-key :value])))]
        (cond
          (not (sequential? source-list))
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :node-type :map-each
                     :status :failure
                     :error (str "Source key '" source-key "' is not a list")}})]}

          (empty? source-list)
          {:result/events
           (map-each-result-events context sheet-id tick-id node-id
                                   output-key [] :success nil)}

          (not child-id)
          {:result/events
           (map-each-result-events context sheet-id tick-id node-id
                                   output-key (vec source-list) :success nil)}

          :else
          (let [state-key (logic/map-each-key tick-id node-id)
                items (vec source-list)
                total-items (count items)
                batch-size (min max-concurrency total-items)
                batch-indices (vec (range batch-size))
                base-state {:items items
                            :current-index 0
                            :results (vec (repeat total-items nil))
                            :completed-indices #{}
                            :in-flight #{}
                            :max-concurrency max-concurrency
                            :child-id child-id
                            :item-key item-key
                            :output-key output-key
                            :source-key source-key}]
            (if-not (:resumed-from-event-id event)
              (let [initial-state (assoc base-state
                                         :in-flight (set batch-indices))]
                (swap! logic/map-each-state assoc state-key initial-state)
                {:result/events
                 (into
                  [(->event
                    {:type :sheet/map-each-progress-updated
                     :tags #{[:sheet sheet-id] [:node node-id] [:tick tick-id]}
                     :body {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                            :item-index 0 :total-items total-items}})]
                  (map-each-start-events context sheet-id tick-id node-id
                                         initial-state batch-indices))})
              (let [installed-state (get @logic/map-each-state state-key)
                    recovery (if (and installed-state
                                      (contains? installed-state
                                                 :recovery-dispatch-indices))
                               {:state installed-state
                                :dispatch-indices
                                (:recovery-dispatch-indices installed-state)
                                :completed?
                                (= total-items
                                   (count (:completed-indices installed-state)))}
                               (recover-map-each-coordinator!
                                context sheet-id tick-id node-id))
                    {:keys [state dispatch-indices completed?]} recovery
                    completed-count (count (:completed-indices state))]
                (swap! logic/map-each-state update state-key
                       dissoc :recovery-dispatch-indices)
                (if completed?
                  (do
                    (swap! logic/map-each-state dissoc state-key)
                    {:result/events
                     (map-each-completion-events context sheet-id tick-id node-id
                                                 state (:results state) completed-count)})
                  {:result/events
                   (into
                    [(->event
                      {:type :sheet/map-each-progress-updated
                       :tags #{[:sheet sheet-id] [:node node-id] [:tick tick-id]}
                       :body {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                              :item-index completed-count
                              :total-items total-items}})]
                    (map-each-start-events context sheet-id tick-id node-id
                                           state dispatch-indices))})))))))))

(defn handle-map-each-child-completion
  "Handle completion of a map-each child iteration.
   Collects results and starts next items or completes the map-each node.
   Only processes completions from the DIRECT child of the map-each node."
  [{:keys [event] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        completing-node-id (:node-id event)
        inputs (:inputs event)
        tick-events (rm/read-tick-events context (:tenant-id context) tick-id)
        map-each-parent-id (get inputs ::map-each-parent)
        item-index (get inputs ::map-each-index)]
    (when (and map-each-parent-id item-index)
      (let [state-key (logic/map-each-key tick-id map-each-parent-id)
            state (get @logic/map-each-state state-key)]
        (when (and state (= completing-node-id (:child-id state)))
          (let [{:keys [items child-id item-key output-key]} state
                computed-result (map-each-item-result context tick-events state event)
                action (atom nil)
                _ (swap! logic/map-each-state
                         (fn [all-state]
                           (let [s (get all-state state-key)]
                             (if-not s
                               (do (reset! action :noop) all-state)
                               (if (contains? (:completed-indices s) item-index)
                                 (do (reset! action {:type :noop
                                                     :completed-count
                                                     (count (:completed-indices s))})
                                     all-state)
                                 (let [new-results (assoc (:results s) item-index computed-result)
                                     completed-indices (conj (:completed-indices s) item-index)
                                     completed-count (count completed-indices)
                                     total (count (:items s))
                                     in-flight-after-removal (disj (:in-flight s) item-index)]
                                 (if (= completed-count total)
                                   (do (reset! action {:type :complete
                                                       :results new-results
                                                       :completed-count completed-count})
                                       (dissoc all-state state-key))
                                   (let [next-to-start (first
                                                        (filter #(and (not (contains? completed-indices %))
                                                                      (not (contains? in-flight-after-removal %)))
                                                                (range total)))
                                         in-flight-after-start (if next-to-start
                                                                 (conj in-flight-after-removal next-to-start)
                                                                 in-flight-after-removal)]
                                     (reset! action (if (and next-to-start (< next-to-start total))
                                                      {:type :start-next
                                                       :next-index next-to-start
                                                       :completed-count completed-count}
                                                      {:type :wait
                                                       :completed-count completed-count}))
                                     (assoc all-state state-key
                                            (assoc s
                                                   :results new-results
                                                   :completed-indices completed-indices
                                                   :in-flight in-flight-after-start))))))))))
                act @action
                total-items (count items)
                nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)]
            (case (:type act)
              :complete
              {:result/events
               (map-each-completion-events context sheet-id tick-id
                                           map-each-parent-id state
                                           (:results act)
                                           (:completed-count act))}

              :start-next
              (let [next-item (nth items (:next-index act))
                    child (get nodes-by-id child-id)]
                {:result/events
                 [(->event
                   {:type :sheet/map-each-progress-updated
                    :tags #{[:sheet sheet-id] [:node map-each-parent-id] [:tick tick-id]}
                    :body {:sheet-id sheet-id :tick-id tick-id :node-id map-each-parent-id
                           :item-index (:completed-count act) :total-items total-items}})
                  (logic/make-bb-write-event context sheet-id tick-id item-key next-item
                                       {:node-id child-id
                                        :input-seed? true
                                        :exec-context {::map-each-index (:next-index act)
                                                       ::map-each-parent map-each-parent-id}})
                  (->event
                   {:type :sheet/node-execution-started
                    :tags (node-execution-tags context sheet-id tick-id child-id)
                    :body {:sheet-id sheet-id :tick-id tick-id :node-id child-id
                           :inputs {item-key next-item
                                    ::map-each-index (:next-index act)
                                    ::map-each-parent map-each-parent-id}}})]})

              {:result/events
               [(->event
                 {:type :sheet/map-each-progress-updated
                  :tags #{[:sheet sheet-id] [:node map-each-parent-id] [:tick tick-id]}
                  :body {:sheet-id sheet-id :tick-id tick-id :node-id map-each-parent-id
                         :item-index (or (:completed-count act) 0) :total-items total-items}})]})))))))

;; =============================================================================
;; Tree Tick Completion
;; =============================================================================

(defn- tick-written-keys
  "The set of blackboard keys this tick actually wrote, read from its
   canonical value and reference events.

   Returns nil when the set cannot be determined (no event store, or the
   read threw). Callers MUST treat nil as \"include every key\" so that a
   read failure degrades to the previous whole-blackboard behavior rather
   than silently dropping a caller's outputs."
  [event-store tenant-id tick-id]
  (try
    (when event-store
      (into #{}
            (map :key)
            (es/read event-store
                     (cond-> {:types #{:sheet/execution-value-written
                                       :sheet/execution-value-referenced}
                              :tags #{[:tick tick-id]}}
                       tenant-id (assoc :tenant-id tenant-id)))))
    (catch Exception _ nil)))

(defn complete-tree-tick
  "When the root node completes, complete the tree tick.
   If status is :running, automatically re-tick (up to max iterations).
   If tick has been cancelled, stop immediately.
   For tick-scoped executions, includes outputs in the completion event."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        status (:status event)
        error (:error event)
        block-payload (:block-payload event)
        tick-ctx (rm/get-tick-execution-context context tick-id)
        correlation-id (:correlation-id tick-ctx)
        root-node-id (:root-node-id tick-ctx)
        max-ticks (or (get-in tick-ctx [:options :max-ticks]) logic/*max-tick-iterations*)
        tick (rm/get-tick context tick-id)
        current-iteration (or (:iteration tick) 1)
        cancelled? (or (contains? @logic/budget-cancellation-claims tick-id)
                       (= :cancelled (:status tick))
                       (and (:parent-tick-id tick)
                            (rm/is-tick-or-ancestor-cancelled? context (:parent-tick-id tick))))]
    (when (= node-id root-node-id)
      (cond
        cancelled?
        {:result/events []}

        (and (= status :running)
             (< current-iteration max-ticks))
        {:result/events
         [(rm/event context
           {:type :sheet/tree-tick-completed
            :tags (cond-> #{[:sheet sheet-id] [:tick tick-id]}
                    correlation-id (conj [:correlation correlation-id]))
            :body (cond-> {:sheet-id sheet-id
                           :tick-id tick-id
                           :iteration current-iteration
                           :root-status :running}
                    correlation-id (assoc :correlation-id correlation-id))})
          (rm/event context
           {:type :sheet/tree-tick-started
            :tags (cond-> #{[:sheet sheet-id] [:tick tick-id]}
                    correlation-id (conj [:correlation correlation-id]))
            :body (cond-> {:sheet-id sheet-id
                           :tick-id tick-id
                           :iteration (inc current-iteration)}
                    correlation-id (assoc :correlation-id correlation-id))})]}

        :else
        (let [max-ticks-exhausted? (and (= status :running)
                                        (>= current-iteration max-ticks))
              final-status (if max-ticks-exhausted?
                             :failure
                             status)
              terminal-reason (if max-ticks-exhausted?
                                :max-ticks-exhausted
                                final-status)
              final-error (or error
                              (when max-ticks-exhausted?
                                (str "Maximum tick budget exhausted (" max-ticks ")")))
              output-keys (when tick-ctx
                            (let [bb (:blackboard tick-ctx)
                                  written (tick-written-keys event-store
                                                             (:tenant-id context)
                                                             tick-id)]
                              (vec (for [[k _] bb
                                         :when (or (nil? written) (contains? written k))]
                                     k))))]
          (process-lifecycle-command! context
            (cond-> {:command/id (random-uuid)
                           :command/timestamp (time/now)
                           :command/name :sheet/emit-tick-completed
                           :sheet-id sheet-id
                           :tick-id tick-id
                           :iteration current-iteration
                           :configured-max-ticks max-ticks
                           :consumed-ticks current-iteration
                           :terminal-reason terminal-reason
                           :root-status final-status}
                    output-keys (assoc :output-keys output-keys)
                    correlation-id (assoc :correlation-id correlation-id)
                    (= :blocked final-status) (assoc :block-payload block-payload)
                    final-error (assoc :error final-error))))))))

;; =============================================================================
;; Execution Completion Delivery
;; =============================================================================

(defn advance-ephemeral-frontier
  "Evaluate deterministic ordered control flow to its next durable leaf or
   terminal outcome. Repeated delivery cannot start the same frontier twice."
  [{:keys [event event-store tenant-id] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        tick-ctx (tick-ctx-of context tick-id)
        nodes-by-id (:nodes-by-id tick-ctx)
        root-id (:root-node-id tick-ctx)
        iteration (or (:iteration event) (get-in event [:inputs ::tick-iteration]) 1)
        iteration-context (when (> iteration 1) {::tick-iteration iteration})
        events (into [] (es/read event-store {:tenant-id tenant-id
                                             :tags #{[:tick tick-id]}}))
        terminal? (some #(and (= :sheet/tree-tick-completed (:event/type %))
                              (not= :running (:root-status %))) events)
        current-iteration? (fn [lifecycle-event]
                             (= iteration-context
                                (not-empty (select-keys (:inputs lifecycle-event)
                                                        [::tick-iteration]))))
        started (into #{} (comp (filter #(and (= :sheet/node-execution-started
                                                    (:event/type %))
                                                 (current-iteration? %)))
                                (map :node-id)) events)
        completed (logic/completed-statuses (filter current-iteration? events))
        decisions (reduce (fn [result summary]
                            (if (and (= :sheet/ephemeral-evaluations-recorded
                                        (:event/type summary))
                                     (= iteration (:iteration summary)))
                              (reduce (fn [m step]
                                        (if (= :condition (:node-type step))
                                          (assoc m (:node-id step) (:status step))
                                          m))
                                      result (:steps summary))
                              result))
                          {} events)
        recorded-step-signatures
        (into #{}
              (comp
               (filter #(and (= :sheet/ephemeral-evaluations-recorded
                                  (:event/type %))
                              (= iteration (:iteration %))))
               (mapcat :steps)
               (map (juxt :node-id :node-type :status)))
              events)
        blackboard (resolve-blackboard-values context sheet-id tick-id nil)]
    (when (and (not terminal?) (ephemeral-routing-active? context tick-id nodes-by-id))
      (let [{:keys [boundary status steps error block-payload]}
            (logic/evaluate-ephemeral-node root-id nodes-by-id blackboard completed decisions)
            new-steps (remove #(contains? recorded-step-signatures
                                          [(:node-id %) (:node-type %) (:status %)])
                              steps)
            summary (logic/ephemeral-summary-event sheet-id tick-id iteration new-steps)]
        (cond
          (and boundary (not (contains? started boundary)))
          {:result/events
           (cond-> []
             summary (conj summary)
             true (conj (->event {:type :sheet/node-execution-started
                                  :tags (node-execution-tags context sheet-id tick-id boundary)
                                  :body (commands/stamp
                                         context (get nodes-by-id boundary)
                                         {:sheet-id sheet-id :tick-id tick-id
                                          :node-id boundary
                                          :inputs (or iteration-context {})})})))}

          boundary nil

          :else
          (let [completion (complete-tree-tick
                            (assoc context :event {:sheet-id sheet-id
                                                   :tick-id tick-id
                                                   :node-id root-id
                                                   :status status
                                                   :error error
                                                   :block-payload block-payload}))]
            (update completion :result/events
                    #(cond-> (vec %)
                       summary (into [summary])))))))))

(defn execute-tree-tick
  "When a tree tick starts, begin executing from the root node.
   Reads from tick-scoped execution context (snapshot-based execution)."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        tick-ctx (rm/get-tick-execution-context context tick-id)
        root-id (:root-node-id tick-ctx)
        nodes-by-id (:nodes-by-id tick-ctx)
        root-node (when root-id (get nodes-by-id root-id))]
    (when root-node
      (if (and (not= :legacy (get-in tick-ctx [:options :durability-mode]))
               (logic/ephemeral-routing-tree? nodes-by-id))
        (advance-ephemeral-frontier context)
      {:result/events
       [(->event
         {:type :sheet/node-execution-started
          :tags (node-execution-tags context sheet-id tick-id root-id)
          :body (commands/stamp
                 context root-node
                 {:sheet-id sheet-id
                 :tick-id tick-id
                 :node-id root-id
                 :inputs (cond-> {}
                           (> (or (:iteration event) 1) 1)
                           (assoc ::tick-iteration (:iteration event)))})})]}))))

(defn handle-child-completion
  "When a child node completes, handle the parent's logic.
   For sequences: continue on success, fail on failure.
   For fallbacks: succeed on success, continue on failure.
   For parallel: check policies to determine completion.
   Propagates execution context from child to parent/siblings."
  [{:keys [event event-store tenant-id] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        child-id (:node-id event)
        child-status (:status event)
        child-writes (rm/resolve-writes context (:tenant-id context) tick-id event)
        event-inputs (:inputs event)
        exec-context (logic/extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        child (get nodes-by-id child-id)
        parent-id (:parent-id child)]
    (if (ephemeral-routing-active? context tick-id nodes-by-id)
      (advance-ephemeral-frontier context)
      (when (and parent-id
               (not (some #(and (= parent-id (:node-id %))
                                (logic/matches-execution-context? % exec-context))
                          (into [] (es/read event-store
                                           {:types #{:sheet/node-execution-completed}
                                            :tags #{[:tick tick-id]}
                                            :tenant-id tenant-id})))))
      (let [parent (get nodes-by-id parent-id)
            siblings (:children-ids parent)
            child-index (.indexOf (vec siblings) child-id)
            next-child-id (logic/next-child-strategy
                           {:strategy-id (:strategy-id parent)
                            :siblings siblings
                            :child-index child-index
                            :event event
                            :parent parent})]
        (case (:type parent)
          :sequence
          (cond
            (#{:success :tree-generated :partial} child-status)
            (if next-child-id
              (let [next-index (inc child-index)
                    total-children (count siblings)
                    already-continued?
                    (some #(and (= :sheet/sequence-progress-updated
                                    (:event/type %))
                                (= parent-id (:node-id %))
                                (= next-index (:child-index %))
                                (= exec-context (or (:exec-context %) {})))
                          (into [] (es/read event-store
                                           {:types #{:sheet/sequence-progress-updated}
                                            :tags #{[:tick tick-id]}
                                            :tenant-id tenant-id})))
                    inputs (if (seq exec-context)
                             (merge exec-context child-writes)
                             exec-context)]
                (when-not already-continued?
                  {:result/events
                   [
                    (->event
                     {:type :sheet/sequence-progress-updated
                      :tags #{[:sheet sheet-id]
                              [:node parent-id]
                              [:tick tick-id]}
                      :body (cond-> {:sheet-id sheet-id
                                     :tick-id tick-id
                                     :node-id parent-id
                                     :child-index next-index
                                     :total-children total-children}
                              (seq exec-context)
                              (assoc :exec-context exec-context))})
                    (->event
                     {:type :sheet/node-execution-started
                      :tags (node-execution-tags context sheet-id tick-id next-child-id)
                      :body (commands/stamp
                             context (get nodes-by-id next-child-id)
                             {:sheet-id sheet-id
                              :tick-id tick-id
                              :node-id next-child-id
                              :inputs inputs})})]}))
              (let [sibling-set (set siblings)
                    child-completions (->> (es/read event-store
                                             {:types #{:sheet/node-execution-completed}
                                              :tags #{[:tick tick-id]}
                                              :tenant-id tenant-id})
                                           (into [])
                                           (filter (fn [e]
                                                     (and (sibling-set (:node-id e))
                                                          (logic/matches-execution-context? e exec-context)))))
                    any-partial? (some #(= :partial (:status %)) child-completions)
                    final-status (if any-partial? :partial child-status)]
                (complete-node-or-fail! context
                  (cond-> {:command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :command/name :sheet/complete-node-execution
                                  :sheet-id sheet-id
                                  :tick-id tick-id
                                  :node-id parent-id
                                  :node-type (:type parent)
                                  :status final-status
                                  :write-sources (rm/resolve-write-sources
                                                  event-store (:tenant-id context)
                                                  tick-id event)}
                           (seq exec-context) (assoc :inputs exec-context)))
                nil))

            (= child-status :failure)
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :failure}
                        (:error event) (assoc :error (:error event))
                        (seq exec-context) (assoc :inputs exec-context))})]}

            (= child-status :timeout)
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :timeout}
                        (:error event) (assoc :error (:error event))
                        (seq exec-context) (assoc :inputs exec-context))})]}

            (= child-status :blocked)
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :blocked}
                        (contains? event :block-payload)
                        (assoc :block-payload (:block-payload event))
                        (seq exec-context) (assoc :inputs exec-context))})]}

            (= child-status :running)
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :running}
                        (seq exec-context) (assoc :inputs exec-context))})]}

            :else nil)

          :fallback
          (case child-status
            (:success :partial)
            (let [write-sources (rm/resolve-write-sources
                                 event-store (:tenant-id context) tick-id event)]
              {:result/events
               [(->event
                 {:type :sheet/node-execution-completed
                  :tags #{[:sheet sheet-id]
                          [:node parent-id]
                          [:tick tick-id]}
                  :body (cond-> {:sheet-id sheet-id
                                 :tick-id tick-id
                                 :node-id parent-id
                                 :status child-status}
                          (seq write-sources)
                          (assoc :write-keys (vec (keys write-sources))
                                 :write-sources write-sources)
                          (seq exec-context) (assoc :inputs exec-context))})]})
            (:failure :timeout)
            (if next-child-id
              (let [inputs exec-context]
                {:result/events
                 [(->event
                   {:type :sheet/node-execution-started
                    :tags (node-execution-tags context sheet-id tick-id next-child-id)
                    :body (commands/stamp
                           context (get nodes-by-id next-child-id)
                           {:sheet-id sheet-id
                            :tick-id tick-id
                            :node-id next-child-id
                            :inputs inputs})})]})
              {:result/events
               [(->event
                 {:type :sheet/node-execution-completed
                  :tags #{[:sheet sheet-id]
                          [:node parent-id]
                          [:tick tick-id]}
                  :body (cond-> {:sheet-id sheet-id
                                 :tick-id tick-id
                                 :node-id parent-id
                                 :status :failure}
                          (seq exec-context) (assoc :inputs exec-context))})]})
            :running
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :running}
                        (seq exec-context) (assoc :inputs exec-context))})]}
            :blocked
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :blocked}
                        (contains? event :block-payload)
                        (assoc :block-payload (:block-payload event))
                        (seq exec-context) (assoc :inputs exec-context))})]}
            nil)

          :parallel
          (let [child-counts (count-child-statuses context sheet-id tick-id parent exec-context)
                completion (logic/parallel-completion-strategy
                            {:strategy-id (:strategy-id parent)
                             :child-counts child-counts
                             :success-policy (:success-policy parent)
                             :failure-policy (:failure-policy parent)
                             :event event
                             :parent parent})]
            (when completion
              (let [event (->event
                            {:type :sheet/node-execution-completed
                             :tags #{[:sheet sheet-id]
                                     [:node parent-id]
                                     [:tick tick-id]}
                             :body (cond-> {:sheet-id sheet-id
                                            :tick-id tick-id
                                            :node-id parent-id
                                            :status (:status completion)}
                                     (seq exec-context) (assoc :inputs exec-context))})]
                (es/append event-store
                  {:events [event]
                   :tenant-id tenant-id
                   :cas {:types #{:sheet/node-execution-completed}
                         :tags #{[:tick tick-id] [:node parent-id]}
                         :predicate-fn (fn [existing]
                                         (empty? (into [] existing)))}})
                {})))

          :map-each
          nil

          nil))))))

(def ^:private tick-family-ids rm/tick-family-ids)

(defn- build-node-trace
  "Build a per-node execution trace for a completed tick and its descendants.

   The trace is lineage-scoped through :parent-tick tags. Completion and write
   events are read only for that tick family, and writes are indexed once for
   constant-time lookup per completion. Runtime is therefore linear in this
   execution's event count, independent of accumulated tenant history.

   Returns a vector of selected per-event fields, sorted by timestamp.
   The shape mirrors what bench harnesses (predict_rlm_comparison, R-Inject)
   used to query themselves — it's now a first-class part of the tick
   completion result so consumers don't have to compose it.

   Each entry:
     {:node-id <uuid> :sheet-id <uuid> :tick-id <uuid> :status <kw>
      :timestamp <OffsetDateTime>
      :inputs {…optional…} :writes {…optional…}
      :usage {…optional…} :duration-ms <ms optional>}

   When events of that type haven't been emitted (or event-store is nil),
   returns an empty vector. Never throws."
  [{:keys [event-store] :as context} tenant-id tick-id]
  (try
    (if-not event-store
      []
      (let [family-ids (tick-family-ids event-store tenant-id tick-id)
            family-events
            (logic/hydrate-events
             context
             (into []
                   (mapcat (fn [family-tick-id]
                             (into []
                                   (es/read event-store
                                            (cond-> {:types #{:sheet/node-execution-completed
                                                             :sheet/execution-value-written}
                                                     :tags #{[:tick family-tick-id]}}
                                              tenant-id (assoc :tenant-id tenant-id))))))
                   family-ids))
            writes-by-execution (logic/writes-by-execution family-events)]
        (->> family-events
             (filter #(= :sheet/node-execution-completed (:event/type %)))
             (sort-by :event/timestamp)
             (mapv (fn [ev]
                     (let [writes (or (not-empty
                                       (get writes-by-execution
                                            (logic/execution-key ev)))
                                      (:writes ev)
                                      {})]
                       (cond-> {:node-id (:node-id ev)
                                :sheet-id (:sheet-id ev)
                                :tick-id (:tick-id ev)
                                :status (:status ev)
                                :timestamp (:event/timestamp ev)}
                         (:inputs ev) (assoc :inputs (:inputs ev))
                         (seq writes) (assoc :writes writes)
                         (:usage ev) (assoc :usage (:usage ev))
                         (:duration-ms ev) (assoc :duration-ms (:duration-ms ev)))))))))
    (catch Exception _ [])))

(defn- aggregate-tick-by-node
  "Read all :sheet/node-execution-completed events for the given tick-id
   from the event store, and aggregate per-node usage with a structured
   node-path key.

   When a node's :usage already contains a :by-node breakdown (i.e. it
   was a parent node like repl-researcher that aggregated child-tick
   per-node usage), we PASS THROUGH those structured entries instead of
   reducing to a single entry — this preserves the full path detail from
   Phase 2 child ticks back into the parent tick's saved result."
  [event-store tenant-id tick-id]
  (when event-store
    (let [tick-events (into [] (es/read event-store
                                (cond-> {:tags #{[:tick tick-id]}}
                                  tenant-id (assoc :tenant-id tenant-id))))
          completions (filter #(= :sheet/node-execution-completed (:event/type %))
                              tick-events)]
      (reduce
        (fn [acc event]
          (let [node-id (:node-id event)
                inputs (:inputs event)
                usage (:usage event)
                child-by-node (:by-node usage)]
            (cond
              (and node-id (seq child-by-node))
              (merge-with (fn [a b]
                            (merge-with + a b))
                          acc child-by-node)

              (and node-id usage)
              (let [map-each-parent (some (fn [k]
                                            (when (and (keyword? k)
                                                       (= (name k) "map-each-parent"))
                                              (get inputs k)))
                                          (keys (or inputs {})))
                    map-each-index (some (fn [k]
                                           (when (and (keyword? k)
                                                      (= (name k) "map-each-index"))
                                             (get inputs k)))
                                         (keys (or inputs {})))
                    path (if (and (some? map-each-parent) (some? map-each-index))
                           [{:type :map-each :parent map-each-parent :index map-each-index}
                            {:type :leaf :node-id node-id}]
                           [{:type :leaf :node-id node-id}])]
                (update acc path
                        (fn [existing]
                          (merge-with +
                                      (or existing {:prompt-tokens 0
                                                    :completion-tokens 0
                                                    :total-tokens 0})
                                      (select-keys usage
                                                   [:prompt-tokens
                                                    :completion-tokens
                                                    :total-tokens])))))

              :else acc)))
        {}
        completions))))

(defn deliver-execution-result
  "When a tick completes, deliver the result to any waiting promise.
   This bridges the async todo processor pipeline back to sync callers
   who are blocking on runtime/execute.

   Skips delivery for intermediate :running completions — those are
   re-tick signals, not final results. The final delivery happens when
   the tree completes with :success/:failure, or when max iterations
   is hit (complete-tree-tick converts :running to :failure)."
  [{:keys [event event-store tenant-id] :as context}]
  (let [tick-id (:tick-id event)
        root-status (:root-status event)
        tick-ctx (rm/get-tick-execution-context context tick-id)
        executed-version (:version-number tick-ctx)
        optional-write-keys (into #{}
                                  (mapcat (fn [{:keys [writes options executor]}]
                                            (if (= :decision executor)
                                              writes
                                              (filter (set writes)
                                                      (:optional-writes options)))))
                                  (vals (:nodes-by-id tick-ctx)))
        outputs (or (when-let [bb (rm/get-tick-blackboard context tick-id)]
                      (merge (zipmap (remove optional-write-keys (keys bb))
                                     (repeat nil))
                             (rm/final-values context tenant-id tick-id)))
                    (:outputs event))
        error (:error event)
        block-payload (:block-payload event)]
    (when (not= root-status :running)
      (let [usage (logic/get-tick-usage tick-id)
            by-node (aggregate-tick-by-node event-store tenant-id tick-id)
            usage-with-breakdown (cond-> usage
                                   (seq by-node) (assoc :by-node by-node))
            result-status (logic/terminal-root-status->result-status
                           root-status)
            trace-start-ms (System/currentTimeMillis)
            node-trace (build-node-trace context tenant-id tick-id)
            trace-duration-ms (- (System/currentTimeMillis) trace-start-ms)
            _ (u/log ::execution-result-trace-built
                     :tick-id tick-id
                     :node-count (count node-trace)
                     :duration-ms trace-duration-ms)]
        (logic/clear-llm-count! tick-id)
        (logic/clear-tick-usage! tick-id)
        (logic/clear-tick! tick-id)
        (logic/forget-tick! tick-id)
        (logic/deliver-completion! tick-id
          (cond-> {:status result-status
                   :trace-id tick-id
                   :error error
                   :configured-max-ticks (:configured-max-ticks event)
                   :consumed-ticks (:consumed-ticks event)
                   :terminal-reason (:terminal-reason event)}
            (not= :timeout result-status)
            (assoc :outputs (or outputs {})
                   :output-sources (rm/final-sources context tenant-id tick-id)
                   :generated-tree-raw (get outputs :generated-tree-raw))
            executed-version (assoc :executed-version executed-version)
            (pos? (:total-tokens usage 0)) (assoc :usage usage-with-breakdown)
            (= :blocked root-status) (assoc :block-payload block-payload)
            (seq node-trace) (assoc :node-trace node-trace)))
        (logic/forget-ephemeral-context! tick-id)))
    nil))

(defn- originating-tick-started
  [event-store tenant-id tick-id]
  (some #(when (:execution-snapshot %) %)
        (into [] (es/read event-store
                          {:types #{:sheet/tree-tick-started}
                           :tags #{[:tick tick-id]}
                           :tenant-id tenant-id}))))

(defn- trace-lineage
  "Return durable parent/root/direct-child tick IDs for a trace."
  [event-store tenant-id tick-id]
  (let [started (originating-tick-started event-store tenant-id tick-id)
        parent-id (:parent-tick-id started)
        root-id (loop [current tick-id, seen #{}, depth 0]
                  (if (or (contains? seen current) (>= depth 100))
                    tick-id
                    (if-let [parent (:parent-tick-id
                                     (originating-tick-started event-store tenant-id current))]
                      (recur parent (conj seen current) (inc depth))
                      current)))
        children (->> (es/read event-store
                               {:types #{:sheet/tree-tick-started}
                                :tags #{[:parent-tick tick-id]}
                                :tenant-id tenant-id})
                      (into [])
                      (map :tick-id)
                      distinct
                      (sort-by str)
                      vec)]
    {:parent-trace-id parent-id
     :root-trace-id root-id
     :child-trace-ids children}))

(defn- await-event-visible!
  "Fence pubsub delivery against event-store query visibility. A processor may
   receive an event from a multi-event append before a concurrent read exposes
   the complete append batch; trace assembly must not snapshot that prefix."
  [event-store tenant-id event]
  (when-let [event-id (:event/id event)]
    (let [deadline (+ (System/currentTimeMillis) 5000)]
      (loop []
        (let [visible? (some #(= event-id (:event/id %))
                             (into []
                                   (es/read event-store
                                            {:tenant-id tenant-id
                                             :tags #{[:tick (:tick-id event)]}})))]
          (when-not (or visible? (>= (System/currentTimeMillis) deadline))
            (Thread/sleep 5)
            (recur)))))))

(defn- assemble-execution-trace-owned
  [{:keys [event event-store] :as context}]
  (await-event-visible! event-store (:tenant-id context) event)
  (let [tick-id (:tick-id event)
        sheet-id (:sheet-id event)
        root-status (:root-status event)
        tick-ctx (rm/get-tick-execution-context context tick-id)]
    (when tick-ctx
      (let [nodes-by-id (:nodes-by-id tick-ctx)
            version-number (:version-number tick-ctx)
            correlation-id (:correlation-id tick-ctx)
            tick-events (into [] (es/read event-store {:tags #{[:tick tick-id]} :tenant-id (:tenant-id context)}))
            source-event-count (count (remove #(contains? #{:sheet/execution-traced
                                                            :sheet/execution-trace-refreshed}
                                                          (:event/type %))
                                              tick-events))
            started-event (first (filter #(= :sheet/tree-tick-started (:event/type %)) tick-events))
            started-at (when started-event (:event/timestamp started-event))
            completed-at (:event/timestamp event)
            written-keys (apply disj
                                (or (tick-written-keys event-store (:tenant-id context) tick-id)
                                    #{})
                                (keys (:seed-sources started-event)))
            final-bb (or (:blackboard tick-ctx) {})
            snapshot-profile (fn [key-pred]
                               (reduce-kv (fn [acc k entry]
                                            (if (and (key-pred k) (some? (:profile entry)))
                                              (assoc acc k (:profile entry))
                                              acc))
                                          {} final-bb))
            input-snapshot (snapshot-profile #(not (contains? written-keys %)))
            output-snapshot (snapshot-profile #(contains? written-keys %))
            started-events (filter #(= :sheet/node-execution-started (:event/type %)) tick-events)
            completed-events (filter #(= :sheet/node-execution-completed (:event/type %)) tick-events)
            ephemeral-events (filter #(= :sheet/ephemeral-evaluations-recorded
                                           (:event/type %)) tick-events)
            researcher-checkpoint-events
            (filter #(= :rlm/researcher-checkpointed (:event/type %)) tick-events)
            researcher-resume-state-events
            (filter #(= :rlm/researcher-resume-state-saved (:event/type %)) tick-events)
            researcher-iteration-record-events
            (filter #(= :rlm/researcher-iteration-recorded (:event/type %)) tick-events)
            researcher-resume-events
            (filter #(and (= :sheet/node-execution-started (:event/type %))
                          (:researcher-resume? %))
                    tick-events)
            researcher-action-events
            (filter #(= :rlm/researcher-action-completed (:event/type %)) tick-events)
            researcher-effect-claim-events
            (filter #(= :rlm/researcher-effect-claimed (:event/type %)) tick-events)
            researcher-effect-completion-events
            (filter #(= :rlm/researcher-effect-completed (:event/type %)) tick-events)
            researcher-effect-indeterminate-events
            (filter #(= :rlm/researcher-effect-indeterminate (:event/type %)) tick-events)
            researcher-effect-claims-by-attempt
            (into {} (map (juxt :attempt-identity identity))
                  researcher-effect-claim-events)
            terminal-researcher-event
            (last (filter #(= :rlm/researcher-iterations (:event/type %)) tick-events))
            checkpoint-iterations
            (into {}
                  (keep (fn [checkpoint-event]
                          (let [checkpoint (:checkpoint checkpoint-event)
                                next-iteration (:next-iteration checkpoint)
                                entry (when (pos? next-iteration)
                                        (nth (:history checkpoint) (dec next-iteration) nil))]
                            (when entry
                              [next-iteration
                               (assoc entry
                                      :iteration next-iteration
                                      :checkpointed-at
                                      (or (:checkpointed-at checkpoint-event)
                                          (str (:event/timestamp checkpoint-event))))])))
                        researcher-checkpoint-events))
            terminal-iterations
            (into {}
                  (map-indexed (fn [index entry]
                                 [(inc index) (assoc entry :iteration (inc index))]))
                  (:iterations terminal-researcher-event))
            researcher-iterations
            (if (seq researcher-iteration-record-events)
              (->> researcher-iteration-record-events
                   (map :iteration-record)
                   (sort-by (juxt :iteration-index :attempt-ordinal))
                   (mapv #(assoc % :iteration (inc (:iteration-index %)))))
              (->> (merge terminal-iterations checkpoint-iterations)
                   (sort-by key)
                   (mapv val)))
            researcher-events
            (->>
             (concat
              (map (fn [claim-event]
                     {:type :effect-claimed
                      ::durable-order (str (:event/id claim-event))
                      :kind (:kind claim-event)
                      :iteration (inc (:iteration-index claim-event))
                      :logical-action-identity (:logical-action-identity claim-event)
                      :attempt-identity (:attempt-identity claim-event)
                      :attempt-ordinal (:attempt-ordinal claim-event)
                      :ownership-epoch (:ownership-epoch claim-event)
                      :status (:status claim-event)
                      :at (or (:claimed-at claim-event)
                              (str (:event/timestamp claim-event)))})
                   researcher-effect-claim-events)
              (map (fn [completion-event]
                     (let [claim (get researcher-effect-claims-by-attempt
                                      (:attempt-identity completion-event))]
                       (cond->
                        {:type :effect-completed
                         ::durable-order (str (:event/id completion-event))
                         :logical-action-identity
                         (:logical-action-identity completion-event)
                         :attempt-identity (:attempt-identity completion-event)
                         :ownership-epoch (:ownership-epoch completion-event)
                         :status (:status completion-event)
                         :at (or (:resolved-at completion-event)
                                 (str (:event/timestamp completion-event)))}
                         claim
                         (assoc :kind (:kind claim)
                                :iteration (inc (:iteration-index claim))
                                :attempt-ordinal (:attempt-ordinal claim)))))
                   researcher-effect-completion-events)
              (map (fn [indeterminate-event]
                     (let [claim (get researcher-effect-claims-by-attempt
                                      (:attempt-identity indeterminate-event))]
                       (cond->
                        {:type :effect-indeterminate
                         ::durable-order (str (:event/id indeterminate-event))
                         :logical-action-identity
                         (:logical-action-identity indeterminate-event)
                         :attempt-identity (:attempt-identity indeterminate-event)
                         :resolved-by-ownership-epoch
                         (:resolved-by-ownership-epoch indeterminate-event)
                         :status (:status indeterminate-event)
                         :at (or (:resolved-at indeterminate-event)
                                 (str (:event/timestamp indeterminate-event)))}
                         claim
                         (assoc :kind (:kind claim)
                                :iteration (inc (:iteration-index claim))
                                :attempt-ordinal (:attempt-ordinal claim)
                                :ownership-epoch (:ownership-epoch claim)))))
                   researcher-effect-indeterminate-events)
              (map (fn [action-event]
                     {:type :action-completed
                      ::durable-order (str (:event/id action-event))
                      :action-id (:action-id action-event)
                      :action-kind (:action-kind action-event)
                      :iteration (inc (:iteration action-event))
                      :at (or (:completed-at action-event)
                              (str (:event/timestamp action-event)))})
                   researcher-action-events)
              (mapcat (fn [checkpoint-event]
                        (cond-> [{:type :checkpoint
                                  ::durable-order (str (:event/id checkpoint-event))
                                  :iteration (get-in checkpoint-event
                                                     [:checkpoint :next-iteration])
                                  :at (or (:checkpointed-at checkpoint-event)
                                          (str (:event/timestamp checkpoint-event)))}]
                          (not= false (:yielded? checkpoint-event))
                          (conj {:type :yield
                                 ::durable-order (str (:event/id checkpoint-event) "-yield")
                                 :iteration (get-in checkpoint-event
                                                    [:checkpoint :next-iteration])
                                 :at (str (:event/timestamp checkpoint-event))})))
                      researcher-checkpoint-events)
              (mapcat (fn [state-event]
                        (cond-> [{:type :checkpoint
                                  ::durable-order (str (:event/id state-event))
                                  :iteration (:next-iteration state-event)
                                  :at (or (:saved-at state-event)
                                          (str (:event/timestamp state-event)))}]
                          (not= false (:yielded? state-event))
                          (conj {:type :yield
                                 ::durable-order (str (:event/id state-event) "-yield")
                                 :iteration (:next-iteration state-event)
                                 :at (str (:event/timestamp state-event))})))
                      researcher-resume-state-events)
              (map (fn [resume-event]
                     {:type :resume
                      ::durable-order (str (:event/id resume-event))
                      :iteration (:checkpoint-next-iteration resume-event)
                      :at (str (:event/timestamp resume-event))})
                   researcher-resume-events))
             logic/order-researcher-events)
            started-by-execution (into {} (map (juxt logic/trace-execution-key identity) started-events))
            completed-by-execution (reduce (fn [acc e]
                                             (assoc acc (logic/trace-execution-key e) e))
                                           {}
                                           completed-events)
            durable-node-traces
            (vec
             (for [started started-events
                                :let [node-id (:node-id started)
                                      node (get nodes-by-id node-id)
                                      completed (get completed-by-execution
                                                     (logic/trace-execution-key started))
                                      exec-context (logic/trace-execution-context (:inputs started))
                                      parent-started (when-let [parent-id (:parent-id node)]
                                                       (or (get started-by-execution [parent-id exec-context])
                                                           (get started-by-execution [parent-id {}])))
                                      node-duration (or (:duration-ms completed)
                                                        (logic/todo-processors-elapsed-ms started completed))]
                                :when node]
                            (cond-> {:node-id node-id
                                     :trace-instance-id (:event/id started)
                                     :node-name (:name node)
                                     :node-type (:type node)
                                     :path (logic/trace-node-path nodes-by-id node-id)
                                     :exec-context exec-context
                                     :status (or (:status completed) :unknown)
                                     :started-at (str (:event/timestamp started))
                                     :completed-at (when completed (str (:event/timestamp completed)))}
                              (:executor node) (assoc :executor (:executor node))
                              (:parent-id node) (assoc :parent-id (:parent-id node))
                              parent-started (assoc :parent-trace-instance-id (:event/id parent-started))
                              (some? node-duration) (assoc :duration-ms node-duration)
                              (:error completed) (assoc :error (:error completed))
                              (:completion-id completed)
                              (assoc :delegate-child-tick-id (:completion-id completed)
                                     :delegate-child-status (:status completed)
                                     :completion-delivered? true)
                              (:model-fallback completed)
                              (assoc :model-fallback (:model-fallback completed))
                              (:failure-kind completed)
                              (assoc :failure-kind (:failure-kind completed))
                              (:provider-evidence completed)
                              (assoc :provider-evidence (:provider-evidence completed))
                              (seq (:write-keys completed))
                              (assoc :write-keys (:write-keys completed)
                                     :output-profile (:write-profile completed))

                              (seq (:missing-write-keys completed))
                              (assoc :missing-write-keys (:missing-write-keys completed))
                              (seq (:rejected-write-keys completed))
                              (assoc :rejected-write-keys (:rejected-write-keys completed)
                                     :rejected-output-profile
                                     (:rejected-write-profile completed))

                              (seq (:read-keys completed))
                              (assoc :read-keys (:read-keys completed)
                                     :input-profile (:input-profile completed)))))
            ephemeral-node-traces
            (vec
             (mapcat (fn [summary]
                       (for [step (:steps summary)
                             :let [node-id (:node-id step)
                                   node (get nodes-by-id node-id)]
                             :when node]
                         (cond-> {:node-id node-id
                                  :trace-instance-id (:trace-instance-id step)
                                  :node-name (:name node)
                                  :node-type (:type node)
                                  :path (logic/trace-node-path nodes-by-id node-id)
                                  :status (:status step)
                                  :started-at (str (:event/timestamp summary))
                                  :completed-at (str (:event/timestamp summary))
                                  :ephemeral? true}
                           (:parent-id node) (assoc :parent-id (:parent-id node)))))
                     ephemeral-events))
            all-node-traces (concat durable-node-traces ephemeral-node-traces)
            trace-instance-by-node (into {} (map (juxt :node-id :trace-instance-id))
                                         all-node-traces)
            node-traces (->> all-node-traces
                             (map (fn [node-trace]
                                    (if-let [parent-id (:parent-id node-trace)]
                                      (cond-> node-trace
                                        (trace-instance-by-node parent-id)
                                        (assoc :parent-trace-instance-id
                                               (trace-instance-by-node parent-id)))
                                      node-trace)))
                             (sort-by :started-at)
                             vec)
            duration-ms (if (and started-at completed-at)
                          (max 0 (- (logic/timestamp-ms completed-at)
                                    (logic/timestamp-ms started-at)))
                          0)
            trace-id tick-id
            {:keys [parent-trace-id root-trace-id child-trace-ids]}
            (trace-lineage event-store (:tenant-id context) tick-id)
            final-status (case root-status
                           :success :success
                           :failure :failure
                           :partial :partial
                           :blocked :blocked
                           :timeout :timeout
                           :running :running
                           :tree-generated :tree-generated
                           :failure)
            configured-max-ticks (or (:configured-max-ticks event)
                                     (get-in tick-ctx [:options :max-ticks])
                                     logic/*max-tick-iterations*)
            consumed-ticks (or (:consumed-ticks event)
                               (:iteration event)
                               (:iteration (rm/get-tick context tick-id))
                               1)
            terminal-reason (or (:terminal-reason event) root-status)]
        ((or (:orc/submit-background! context)
             (fn [task] (future (task))))
         (fn []
           (try
             (commands/publish!
              context
              (cond-> {:trace-id trace-id
                       :sheet-id sheet-id
                       :root-trace-id root-trace-id
                       :child-trace-ids child-trace-ids
                       :started-at (str (or started-at (time/now)))
                       :completed-at (str (or completed-at (time/now)))
                       :duration-ms duration-ms
                       :status final-status
                       :configured-max-ticks configured-max-ticks
                       :consumed-ticks consumed-ticks
                       :terminal-reason terminal-reason
                       :input-snapshot input-snapshot
                       :output-snapshot output-snapshot
                       :source-event-count source-event-count
                       :node-traces node-traces}
                (seq researcher-iterations)
                (assoc :researcher-iterations researcher-iterations)
                (seq researcher-events)
                (assoc :researcher-events researcher-events)
                parent-trace-id (assoc :parent-trace-id parent-trace-id)
                correlation-id (assoc :correlation-id correlation-id)
                version-number (assoc :version-number version-number)
                (:error event) (assoc :error (:error event))))
             (catch Exception _e
               nil))))
        nil))))

(defn assemble-execution-trace
  "After a tick completes, assemble an execution trace from events and store it.
   Only runs for tick-scoped executions (snapshot-based).
   Reads node-execution-started/completed events, correlates them with node
   metadata from the execution snapshot, and stores via store-execution-trace."
  [context]
  (let [release! ((or (:orc/acquire-background! context)
                      (fn [] (fn [] nil))))]
    (when release!
      (try
        (assemble-execution-trace-owned context)
        (finally
          (release!))))))

(defn refresh-terminal-execution-trace
  "Reassemble a terminal trace when durable trace evidence arrives after the
   tree terminal event. Processor delivery is asynchronous across topics, so
   the terminal assembler cannot assume every node lifecycle or ephemeral
   summary append is already visible. Reusing the durable terminal event makes
   the eventual trace independent of processor scheduling order."
  [{:keys [event event-store] :as context}]
  (let [tick-id (:tick-id event)
        terminal-event (->> (es/read event-store
                                     {:tenant-id (:tenant-id context)
                                      :tags #{[:tick tick-id]}
                                      :types #{:sheet/tree-tick-completed}})
                            (into [])
                            last)]
    (when terminal-event
      (assemble-execution-trace (assoc context :event terminal-event)))))

(defn restore-from-snapshot
  "Restore sheet state from a version or stash snapshot.
   Handles both :sheet/draft-reverted and :sheet/stash-restored events.

   This generates all events needed to:
   1. Delete all existing nodes (leaf-first)
   2. Delete all existing blackboard keys
   3. Recreate nodes from snapshot
   4. Recreate blackboard schema from snapshot"
  [{:keys [event event-store] :as context}]
  (let [event-type (:event/type event)
        sheet-id (:sheet-id event)
        snapshot (:snapshot event)]
    (when (and (#{:sheet/draft-reverted :sheet/stash-restored} event-type)
               snapshot)
      (let [
            current-nodes-by-id (rm/get-nodes-by-id context sheet-id)
            current-sheet (rm/get-sheet context sheet-id)
            current-root-id (:root-node-id current-sheet)
            current-blackboard (rm/get-blackboard-by-key context sheet-id)

            node-deletion-ids (logic/collect-deletion-order current-nodes-by-id current-root-id)
            node-deletion-events (mapv (fn [node-id]
                                         (->event
                                          {:type :sheet/node-deleted
                                           :tags #{[:sheet sheet-id]
                                                   [:node node-id]}
                                           :body {:sheet-id sheet-id
                                                  :node-id node-id}}))
                                       node-deletion-ids)

            key-deletion-events (mapv (fn [[k _]]
                                        (->event
                                         {:type :sheet/key-deleted
                                          :tags #{[:sheet sheet-id]}
                                          :body {:sheet-id sheet-id
                                                 :key k}}))
                                      current-blackboard)

            snapshot-nodes (:nodes snapshot)
            blackboard-schema (:blackboard-schema snapshot)

            key-declaration-events (mapv (fn [[k schema]]
                                           (->event
                                            {:type :sheet/key-declared
                                             :tags #{[:sheet sheet-id]}
                                             :body {:sheet-id sheet-id
                                                    :key k
                                                    :schema schema}}))
                                         blackboard-schema)

            node-records (logic/flatten-snapshot-nodes snapshot-nodes nil)

            node-events (mapcat
                         (fn [[node-id parent-id snapshot-node]]
                           (let [node-type (:type snapshot-node)
                                 create-event (->event
                                               {:type :sheet/node-created
                                                :tags #{[:sheet sheet-id]
                                                        [:node node-id]}
                                                :body (cond-> {:sheet-id sheet-id
                                                               :node-id node-id
                                                               :type node-type}
                                                        parent-id (assoc :parent-id parent-id))})
                                 name-event (when (:name snapshot-node)
                                              (->event
                                               {:type :sheet/node-name-set
                                                :tags #{[:sheet sheet-id]
                                                        [:node node-id]}
                                                :body {:sheet-id sheet-id
                                                       :node-id node-id
                                                       :name (:name snapshot-node)}}))
                                 config-events
                                 (case node-type
                                   :leaf
                                   (filterv some?
                                            [(when (:instruction snapshot-node)
                                               (->event
                                                {:type :sheet/node-instruction-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :instruction (:instruction snapshot-node)}}))
                                             (when (or (seq (:reads snapshot-node))
                                                       (seq (:writes snapshot-node)))
                                               (->event
                                                {:type :sheet/node-io-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :reads (or (:reads snapshot-node) [])
                                                        :writes (or (:writes snapshot-node) [])}}))
                                             (when (:executor snapshot-node)
                                               (->event
                                                {:type :sheet/node-executor-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :executor (:executor snapshot-node)}
                                                         (:model snapshot-node) (assoc :model (:model snapshot-node))
                                                         (:fn snapshot-node) (assoc :fn (:fn snapshot-node))
                                                         (:tools snapshot-node) (assoc :tools (:tools snapshot-node))
                                                         (:options snapshot-node)
                                                         (assoc :options (:options snapshot-node))
                                                         (:options-from snapshot-node) (assoc :options-from (:options-from snapshot-node))
                                                         (:bands-from snapshot-node) (assoc :bands-from (:bands-from snapshot-node))
                                                         (:min-confidence snapshot-node) (assoc :min-confidence (:min-confidence snapshot-node))
                                                         (some? (:abstain snapshot-node)) (assoc :abstain (:abstain snapshot-node)))}))
                                             (when (:retry snapshot-node)
                                               (->event
                                                {:type :sheet/node-retry-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :retry (:retry snapshot-node)}}))])

                                   :fallback
                                   (if-let [policy (:model-fallback-policy snapshot-node)]
                                     [(->event {:type :sheet/model-fallback-policy-set
                                                :tags #{[:sheet sheet-id] [:node node-id]}
                                                :body {:sheet-id sheet-id :node-id node-id :model-fallback-policy policy}})]
                                     [])

                                   :condition
                                   (filterv some?
                                            [(when (:check snapshot-node)
                                               (->event
                                                {:type :sheet/node-check-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :check (:check snapshot-node)}}))])

                                   :llm-condition
                                   (filterv some?
                                            [(when (or (:instruction snapshot-node)
                                                       (seq (:reads snapshot-node)))
                                               (->event
                                                {:type :sheet/llm-condition-config-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :instruction (or (:instruction snapshot-node) "")
                                                                :reads (or (:reads snapshot-node) [])}
                                                         (:model snapshot-node) (assoc :model (:model snapshot-node)))}))])

                                   :parallel
                                   [(->event
                                     {:type :sheet/parallel-config-set
                                      :tags #{[:sheet sheet-id] [:node node-id]}
                                      :body {:sheet-id sheet-id
                                             :node-id node-id
                                             :success-policy (or (:success-policy snapshot-node) :all)
                                             :failure-policy (or (:failure-policy snapshot-node) :any)}})]

                                   :map-each
                                   (filterv some?
                                            [(when (and (:source-key snapshot-node)
                                                        (:item-key snapshot-node)
                                                        (:output-key snapshot-node))
                                               (->event
                                                {:type :sheet/map-each-config-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :source-key (:source-key snapshot-node)
                                                                :item-key (:item-key snapshot-node)
                                                                :output-key (:output-key snapshot-node)}
                                                         (:max-concurrency snapshot-node)
                                                         (assoc :max-concurrency (:max-concurrency snapshot-node))
                                                         (some? (:preserve-failures? snapshot-node))
                                                         (assoc :preserve-failures? (:preserve-failures? snapshot-node)))}))])

                                   :delegate
                                   (filterv some?
                                            [(when (:target-sheet-id snapshot-node)
                                               (->event
                                                {:type :sheet/delegate-config-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :target-sheet-id (:target-sheet-id snapshot-node)
                                                                :reads (or (:reads snapshot-node) [])
                                                                :writes (or (:writes snapshot-node) [])}
                                                         (:delegate-timeout-ms snapshot-node)
                                                         (assoc :timeout-ms (:delegate-timeout-ms snapshot-node))
                                                         (:delegate-max-ticks snapshot-node)
                                                         (assoc :max-ticks (:delegate-max-ticks snapshot-node))
                                                         (some? (:inherit-ontology? snapshot-node))
                                                         (assoc :inherit-ontology? (:inherit-ontology? snapshot-node)))}))])

                                   [])]
                             (filterv some? (into [create-event name-event] config-events))))
                         node-records)]

        {:result/events
         (vec (concat node-deletion-events
                      key-deletion-events
                      key-declaration-events
                      node-events))}))))

(defprocessor :sheet recover-active-executions
  {:topics #{:sheet/recovery-scan-triggered}}
  "Rediscover this tenant's unfinished durable frontiers after startup.

   Recovery is an idempotent at-least-once effect: the existing resume command
   CAS chooses one recovered start when trigger delivery or scanners race."
  [context]
  {:result/checkpoint :after
   :result/effect #(resume-in-progress! context)})

(defprocessor :sheet start-tree-tick
  {:topics #{:sheet/tree-tick-started}}
  "Start tick execution when tree tick begins."
  [context]
  (execute-tree-tick context))

(defprocessor :sheet execute-leaf-node
  {:topics #{:sheet/node-execution-started}}
  "Execute the started node, whatever its type. Each execute-* fn guards on
   its own node type and returns nil on non-match, and node types are
   mutually exclusive, so at most one branch of the `or` acts per event.

   One processor instead of seven: every processor on a topic durably
   advances its own checkpoint cursor per event, so six no-op subscribers
   cost six fsync'd commits per node transition — the dominant fixed cost
   per hop (docs/issues/005-sheet-execution-checkpoint-amplification.md).

   Deliberately registered under the historical :sheet/execute-leaf-node
   name so it inherits that processor's checkpoint cursor. A fresh name has
   no checkpoint, and a checkpoint-less processor catches up from the START
   of the stream — re-executing every historical node-execution-started
   event. The six retired names leave inert checkpoints behind."
  [context]
  (let [context (with-resolved-tick-ctx context)
        {:keys [event]} context
        tick-ctx (:ctx (::resolved-tick-ctx context))
        node (get (:nodes-by-id tick-ctx) (:node-id event))]
    (case (:type node)
      :leaf (execute-leaf-node context)
      (:condition :llm-condition) (execute-condition-node context)
      (:sequence :fallback) (execute-composite-node context)
      :parallel (execute-parallel-node context)
      :map-each (execute-map-each-node context)
      :repl-researcher (execute-repl-researcher-node context)
      :delegate (execute-delegate-node context)
      nil)))

(defprocessor :sheet handle-child-completion
  {:topics #{:sheet/node-execution-completed}}
  "Handle child completion for composite nodes."
  [context]
  (handle-child-completion (with-resolved-tick-ctx context)))

(defprocessor :sheet handle-map-each-child-completion
  {:topics #{:sheet/node-execution-completed}}
  "Handle map-each child iteration completion."
  [context]
  (handle-map-each-child-completion context))

(defprocessor :sheet deliver-delegate-child-completion
  {:topics #{:sheet/tree-tick-completed}}
  "Durably wake the parent of a terminal delegated child tick."
  [context]
  (deliver-delegate-child-completion context))

(defprocessor :sheet complete-tree-tick
  {:topics #{:sheet/node-execution-completed}}
  "Complete the tree tick when its root completes, then refresh any terminal
   trace from the same durable node-completion delivery.

   The processor's RETURN VALUE is `complete-tree-tick`'s. The trace refresh
   runs for its effect only and returns nil, so returning it discards
   `complete-tree-tick`'s `:result/events` — including the `:running` re-tick
   pair (tree-tick-completed + tree-tick-started) that advances every workflow
   whose root deliberately stays running. Any root `:running` completion would
   silently stop re-ticking."
  [context]
  (let [result (complete-tree-tick context)]
    (refresh-terminal-execution-trace context)
    result))

(defprocessor :sheet refresh-execution-trace-after-ephemeral-summary
  {:topics #{:sheet/ephemeral-evaluations-recorded}}
  "Refresh a trace after its ordered ephemeral routing evidence is durable.
   A final routing summary can be appended after the terminal event that first
   triggered assembly, so this delivery is the settle signal for that evidence."
  [context]
  (refresh-terminal-execution-trace context))

(defprocessor :sheet refresh-execution-trace-after-rlm-bookend
  {:topics #{:sheet/rlm-tree-execution-completed}}
  "Refresh the generated child's terminal trace after its late RLM bookend.
   The event's tick-id identifies the child; source-tick-id identifies the
   separate hosting campaign and must not redirect trace assembly."
  [context]
  (refresh-terminal-execution-trace context))

(defprocessor :sheet restore-from-snapshot
  {:topics #{:sheet/draft-reverted :sheet/stash-restored}}
  "Restore sheet state from a version or stash snapshot."
  [context]
  (restore-from-snapshot context))

(defprocessor :sheet deliver-execution-result
  {:topics #{:sheet/tree-tick-completed}}
  "Deliver execution result to waiting promises (bridges async to sync)."
  [context]
  (deliver-execution-result context))

(defprocessor :sheet deliver-cancellation
  {:topics #{:sheet/tick-cancelled}}
  "Unblock callers waiting on a cancelled tick."
  [context]
  (logic/deliver-cancellation context))

(defprocessor :sheet assemble-execution-trace
  {:topics #{:sheet/tree-tick-completed}}
  "Assemble and store execution trace from events."
  [context]
  (assemble-execution-trace context))
