(ns ai.obney.orc.orc-service.rlm-async-test
  "Integration tests for :rlm mode through the full async pipeline.
   Verifies that :rlm config round-trips DSL → command → event → read model →
   execution snapshot → executor, and that the captured :rlm metadata reaches
   the node-execution-completed event."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [dscloj.core :as dscloj]))

;; =============================================================================
;; Async Test Context (no MCP needed)
;; =============================================================================

(defn- create-async-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub ps
                               :logger nil})
        cache-dir (str "/tmp/rlm-async-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :test}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps
                                         :topics topics
                                         :handler-fn handler-fn
                                         :context base-ctx})))
                     {}
                     @tp/processor-registry*)]
    (assoc base-ctx
           :event-pubsub ps
           :processors processors)))

(defn- stop-async-context [ctx]
  (doseq [[_ proc] (:processors ctx)] (tp/stop proc))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es)))

(defmacro with-async-ctx [[ctx-sym] & body]
  `(let [~ctx-sym (create-async-context)]
     (try ~@body (finally (stop-async-context ~ctx-sym)))))

(defn- dispatch-execute! [ctx sheet-id inputs]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)]
    (cp/process-command
      (assoc ctx :command
             {:command/id (random-uuid)
              :command/timestamp (time/now)
              :command/name :sheet/tick-tree
              :sheet-id sheet-id
              :tick-id tick-id
              :inputs inputs
              :options {:timeout-ms 15000}}))
    {:tick-id tick-id :promise p}))

(defn- wait-for [p]
  (let [r (deref p 15000 ::timeout)]
    (if (= ::timeout r) :timeout r)))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest rlm-config-round-trips-through-pipeline-test
  (testing ":rlm config built via DSL is reachable via read-model after build-workflow!"
    (with-async-ctx [ctx]
      (let [rlm-cfg {:enabled? true
                     :context-key :context
                     :predict-model "openai/gpt-5-mini"
                     :max-predict-calls 10
                     :max-predict-concurrency 2
                     :max-predict-input-chars 5000
                     :history-preview-chars 500}
            wf (dsl/workflow "rlm-pipeline-test"
                 (dsl/blackboard {:context :string :question :string :answer :string})
                 (dsl/repl-researcher "rlm-node"
                   :model "test-model"
                   :instruction "answer using context"
                   :reads [:context :question]
                   :writes [:answer]
                   :max-iterations 2
                   :rlm rlm-cfg))
            sheet-id (dsl/build-workflow! ctx wf)
            nodes (rm/get-nodes-by-id ctx sheet-id)
            node (->> nodes vals (filter #(= :repl-researcher (:type %))) first)]
        (is (some? node))
        (is (= rlm-cfg (:rlm node))
            ":rlm config should round-trip through commands/events/read-model")))))

(deftest rlm-final-bang-async-execution-test
  (testing "RLM mode runs end-to-end through async pipeline; final! result reaches outputs"
    (with-async-ctx [ctx]
      (let [code "(final! {:answer (str \"q=\" question)})"]
        (with-redefs [dscloj/predict
                      (fn [_p _m _i _o]
                        {:outputs {:code code}
                         :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
          (let [wf (dsl/workflow "rlm-final-bang-test"
                     (dsl/blackboard {:context :string :question :string :answer :string})
                     (dsl/repl-researcher "node"
                       :model "test-model"
                       :instruction "compute answer"
                       :reads [:context :question]
                       :writes [:answer]
                       :max-iterations 2
                       :rlm true))
                sheet-id (dsl/build-workflow! ctx wf)
                {:keys [promise]} (dispatch-execute! ctx sheet-id
                                                     {:context "ignored"
                                                      :question "hello"})
                result (wait-for promise)]
            (is (not= :timeout result))
            (is (= :success (:status result)))
            (is (= "q=hello" (-> result :outputs :answer)))))))))

(deftest rlm-metadata-reaches-completion-event-test
  (testing ":rlm telemetry surfaces in node-execution-completed event body"
    (with-async-ctx [ctx]
      (let [code "(final! {:answer \"done\"})"
            captured-events (atom [])]
        (with-redefs [dscloj/predict
                      (fn [_p _m _i _o]
                        {:outputs {:code code}
                         :usage {:prompt_tokens 7 :completion_tokens 3 :total_tokens 10}})]
          (let [wf (dsl/workflow "rlm-trace-test"
                     (dsl/blackboard {:context :string :question :string :answer :string})
                     (dsl/repl-researcher "node"
                       :model "test-model"
                       :instruction "compute"
                       :reads [:context :question]
                       :writes [:answer]
                       :max-iterations 2
                       :rlm true))
                sheet-id (dsl/build-workflow! ctx wf)
                {:keys [promise]} (dispatch-execute! ctx sheet-id
                                                     {:context "x" :question "y"})
                result (wait-for promise)
                ;; Pull all completion events for this sheet
                events (vec (es/read (:event-store ctx)
                                     {:tenant-id (:tenant-id ctx)
                                      :types #{:sheet/node-execution-completed}
                                      :tags #{[:sheet sheet-id]}}))
                completed (filter #(= :success (:status %)) events)]
            (is (= :success (:status result)))
            (is (seq completed) "Should see at least one successful node-execution-completed")
            (let [evt (first completed)]
              (is (some? (:rlm evt))
                  ":rlm metadata should be present on the completion event")
              (is (true? (-> evt :rlm :enabled?)))
              (is (= :context (-> evt :rlm :context-key)))
              (is (some? (-> evt :rlm :root-usage)))
              (is (some? (:usage evt))
                  "Aggregated :usage should also be on the event"))))))))
