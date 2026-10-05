(ns ai.obney.orc.orc-service.generated-child-receipts-test
  "Generated from CheckpointedResearcherExecution GeneratedChildToolCallsAreCheckpointed
   (specs/orc-service.allium).

   A checkpointed researcher emits a child tree whose code leaf calls a
   checkpoint-safe tool and then dies (a JVM Error: the leaf handler records no
   completion, the way a crashed worker records none). Recovery, viewed an hour
   later so the dead worker's lease has expired, re-runs the child leaf. The
   tool's effect already completed, so its durable receipt must be returned
   instead of calling the tool again."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def tool-calls (atom 0))
(def leaf-runs (atom 0))

(defn- charge-tool [tool args & _]
  (swap! tool-calls inc)
  {:charged (str tool ":" (get args "amount" (:amount args)))})

(defn crashing-child-leaf
  "Generated child work: charge once, then (first run only) the worker dies."
  [{:keys [call-tool-fn]}]
  (let [run (swap! leaf-runs inc)
        receipt (call-tool-fn "charge" {"amount" 5})]
    (when (= 1 run)
      (throw (Error. "simulated worker death after the effect")))
    {:out (:charged receipt)}))

(defn- an-hour-later [ctx]
  (assoc ctx :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600)))

(defn- tool-claims [ctx]
  (filterv #(and (= :rlm/researcher-effect-claimed (:event/type %)) (= :tool (:kind %)))
           (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))))

(deftest generated-child-tool-effect-is-not-repeated-after-a-crash
  (reset! tool-calls 0)
  (reset! leaf-runs 0)
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn charge-tool}}]
    (let [definition
          (sheet/workflow "receipts-generated-child"
            (sheet/blackboard {:question :string :out :string})
            (sheet/repl-researcher "researcher"
              :instruction "Charge via a generated child."
              :reads [:question] :writes [:out]
              :mcp-tools ["charge"]
              :tool-contracts {"charge" {:checkpoint-safe? true}}
              :rlm {:checkpointed? true :recursive? false
                    :timeouts {:provider-ms 5000 :iteration-ms 20000 :campaign-ms 60000}}
              :max-iterations 2))
          sheet-id (sheet/build-workflow! ctx definition)]
      (with-redefs [llm/predict
                    (fn [& _]
                      {:outputs {:code (str "(emit-tree! [:sequence "
                                            "[:code {:fn \"ai.obney.orc.orc-service.generated-child-receipts-test/crashing-child-leaf\" "
                                            ":reads [] :writes [:out]}] "
                                            "[:final {:keys [:out]}]])")}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (let [run (future (sheet/execute ctx sheet-id {:question "q"} :timeout-ms 60000))]
          (is (h/settle-until! #(= 1 @leaf-runs) :timeout-ms 20000) "the child leaf ran and died")
          (is (= 1 @tool-calls))
          (Thread/sleep 500)
          (is (h/settle-until!
               #(some :resumed? (sheet/resume-in-progress! (an-hour-later ctx)))
               :timeout-ms 20000)
              "the dead child leaf is recovered")
          (let [result (deref run 60000 ::timeout)]
            (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
            (is (= 2 @leaf-runs) "the child leaf ran again after recovery")
            (is (= 1 @tool-calls) "the completed effect was NOT repeated")
            (is (= "charge:5" (get-in result [:outputs :out]))
                "the recorded receipt was returned in its place")
            (is (= 1 (count (tool-claims ctx)))
                "one durable claim for the one logical tool action")))))))

(deftest generated-child-requires-checkpoint-safe-tools
  (reset! tool-calls 0)
  (reset! leaf-runs 0)
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn charge-tool}}]
    (let [definition
          (sheet/workflow "receipts-unsafe-tool"
            (sheet/blackboard {:question :string :out :string})
            (sheet/repl-researcher "researcher"
              :instruction "Charge via a generated child."
              :reads [:question] :writes [:out]
              :mcp-tools ["charge"]
              :rlm {:checkpointed? true :recursive? false
                    :timeouts {:provider-ms 5000 :iteration-ms 20000 :campaign-ms 60000}}
              :max-iterations 1))
          sheet-id (sheet/build-workflow! ctx definition)]
      (with-redefs [llm/predict
                    (fn [& _]
                      {:outputs {:code (str "(emit-tree! [:sequence "
                                            "[:code {:fn \"ai.obney.orc.orc-service.generated-child-receipts-test/crashing-child-leaf\" "
                                            ":reads [] :writes [:out]}] "
                                            "[:final {:keys [:out]}]])")}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (let [result (sheet/execute ctx sheet-id {:question "q"} :timeout-ms 30000)]
          (is (zero? @tool-calls) "a tool not declared checkpoint-safe is never called from the child")
          (is (not= :success (:status result))))))))
