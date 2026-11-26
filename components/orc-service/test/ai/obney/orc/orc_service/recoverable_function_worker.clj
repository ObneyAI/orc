(ns ai.obney.orc.orc-service.recoverable-function-worker
  "Owned cold-recovery fixture. No models or network. Real SQLite, processors,
   commands and queries; capabilities are local files and a process-owned gate."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.logic :as service-logic]
            [ai.obney.orc.orc-service.core.todo-processors :as service-processors]))
(def source '(fn [{:keys [inputs probe-gate tick-id node-id]}]
               (probe-gate {:tick-id tick-id :node-id node-id})
               {:result (* 2 (:prefix inputs))}))
(defn prefix [{:keys [inputs once-effect]}]
  (once-effect)
  {:prefix (+ 3 (:n inputs))})
(defn- source-origins []
  (into {}
        (for [path ["core/agent_runtime.clj"
                    "core/block.clj"
                    "core/commands.clj"
                    "core/execution_budget.clj"
                    "core/logic.clj"
                    "core/periodic_tasks.clj"
                    "core/queries.clj"
                    "core/read_models.clj"
                    "core/todo_processors.clj"
                    "interface/agent_runtime.clj"
                    "interface/processor_registration.clj"
                    "interface/projections.clj"
                    "interface/schemas.clj"
                    "interface/stream_schemas.clj"
                    "interface.clj"]
              :let [url (io/resource (str "ai/obney/orc/orc_service/" path))
                    bytes (.getBytes (slurp url) "UTF-8")]]
          [path {:url (str url)
                 :sha256 (format "%064x" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)))}])))
(defn- save! [dir name value] (spit (io/file dir name) (pr-str value)))
(defn- events [ctx] (h/read-all-events ctx))
(defn -main [mode dir]
  (let [manifest-file (io/file dir "manifest.edn")
        manifest (when (.exists manifest-file) (edn/read-string (slurp manifest-file)))
        tenant (or (:tenant manifest) (random-uuid))
        gated (promise)
        ctx* (atom nil)
        ctx (h/create-async-test-context
             {:event-store-conn {:type :sqlite :database-file (str (io/file dir "events.sqlite")) :maximum-pool-size 2}
              :context {:tenant-id tenant :orc/execution-lease-ms 2000
                        :once-effect #(spit (io/file dir "effects.edn") (str (pr-str {:prefix-effect true}) "\n") :append true)
                        :probe-gate (fn [identity]
                                      (when (= mode "prepare")
                                        (let [ctx @ctx* tick (:tick-id identity)
                                              snapshot (orc/get-tick-execution-context ctx tick)]
                                          (save! dir "pending.edn" {:identity identity :snapshot snapshot :events (events ctx)}))
                                        @gated))}})]
    (reset! ctx* ctx)
    (try
      (if (= mode "prepare")
        (let [sctx (service-processors/build-rlm-context {:blackboard {} :durable-source-required? true})
              program (str "(emit-tree! [:code {:reads [:prefix] :writes [:result] :output-schemas {:result :int} :fn '" (pr-str source) "}])")
              emission (service-logic/execute-rlm-code sctx program)
              _ (assert (nil? (:error emission)) (pr-str emission))
              canonical (:generated-tree @(:sandbox-vars sctx))
              f (:fn (apply hash-map (rest canonical)))
              key (service-logic/register-ephemeral-fn! f)
              binding (:orc/recoverable-fn (meta f))
              sheet (orc/build-workflow! ctx
                      (orc/workflow (str "cold-callable-" (random-uuid))
                        (orc/blackboard {:n :int :prefix :int :result :int})
                        (orc/sequence "original-sequence"
                         (orc/code "completed-prefix" :fn "ai.obney.orc.orc-service.recoverable-function-worker/prefix" :reads [:n] :writes [:prefix])
                         (orc/code "original-pending-callable" :fn key :reads [:prefix] :writes [:result]))))
              node (first (filter #(= key (:fn %)) (orc/get-nodes-for-sheet ctx sheet)))
              tick (random-uuid)
              deadline (+ (System/currentTimeMillis) 180000)
              command {:command/name :sheet/set-node-executor :command/id (random-uuid)
                       :command/timestamp (java.time.OffsetDateTime/now) :sheet-id sheet :node-id (:id node)
                       :executor :code :fn key :recoverable-fn binding}
              accepted (h/run-and-apply! ctx command)]
          (assert (seq (:command-result/events accepted)) (pr-str accepted))
          (save! dir "manifest.edn" {:tenant tenant :sheet sheet :node (:id node) :tick tick :key key :binding binding :source program :source-origins (source-origins) :deadline deadline :pid (.pid (java.lang.ProcessHandle/current))})
          (save! dir "unexpected-prepare-return.edn"
                 (orc/execute ctx sheet {:n 4} :tick-id tick :timeout-ms 180000 :execution-deadline-ms deadline :force-draft true)))
        (let [{:keys [tick key]} manifest
              before (events ctx)
              _ (assert (nil? (service-logic/lookup-ephemeral-fn key)) "New JVM must start with no callable")
              resumed (orc/resume-in-progress! ctx)
              _ (assert (h/settle-until! #(some (fn [e] (and (= tick (:tick-id e)) (= :sheet/tree-tick-completed (:event/type e)))) (events ctx)) :timeout-ms 15000) "Original tick did not finish")
              after (events ctx)
              second-resume (orc/resume-in-progress! ctx)
              snapshot (orc/get-tick-execution-context ctx tick)
              result (service-processors/execute-tree nil ctx {:stable-tick-id tick :timeout-ms 1000})]
          (save! dir "recovered.edn" {:pid (.pid (java.lang.ProcessHandle/current)) :source-origins (source-origins) :before before :resumed resumed :events after :second-resume second-resume :snapshot snapshot :result result :callable-after-terminal? (some? (service-logic/lookup-ephemeral-fn key))})))
      (finally
        (deliver gated true)
        (h/stop-async-context ctx)
        (shutdown-agents)))))
