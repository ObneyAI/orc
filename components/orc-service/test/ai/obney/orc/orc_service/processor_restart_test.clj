(ns ai.obney.orc.orc-service.processor-restart-test
  "Deterministic regression for the shared live-GEPA processor restart fixture.
   Kept outside the namespace whose discovery count guarantees paid live tests."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.real-llm-gepa-e2e-test :as gepa-live]))

(defn restart-probe [{:keys [inputs]}]
  {:answer (:question inputs)})

(deftest processor-restart-retains-executable-context
  ;; Register the public evaluator runtime explicitly, independent of test order.
  (require 'ai.obney.orc.evaluation.interface)
  (testing "restart respects polling and pubsub lifecycle handles without model calls"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow!
                      ctx (sheet/workflow "processor-restart-probe"
                            (sheet/blackboard {:question :string :answer :string})
                            (sheet/code "copy"
                              :fn "ai.obney.orc.orc-service.processor-restart-test/restart-probe"
                              :reads [:question] :writes [:answer])))
            rebuilt (gepa-live/restart-processors ctx)]
        (try
          (let [result (sheet/execute rebuilt sheet-id {:question "retained"}
                                     :timeout-ms 20000)]
            (is (= :success (:status result)))
            (is (= "retained" (get-in result [:outputs :answer])))
            (is (some #(= (:trace-id result) (:tick-id %))
                      (live/events rebuilt :sheet/tree-tick-completed))))
          (finally
            (h/stop-test-processors! rebuilt)))))))

