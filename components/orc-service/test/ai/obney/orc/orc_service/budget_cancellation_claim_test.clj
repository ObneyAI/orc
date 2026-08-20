(ns ai.obney.orc.orc-service.budget-cancellation-claim-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]))

(deftest terminal-cleanup-does-not-reopen-budget-cancellation-claim
  (testing "queued budget exhaustion deliveries cannot cancel one tick twice"
    (let [tick-id (random-uuid)
          claim! (ns-resolve 'ai.obney.orc.orc-service.core.todo-processors
                             'claim-budget-cancellation!)]
      (is (true? (claim! tick-id)) "the first delivery owns cancellation")
      (tp/clear-llm-count! tick-id)
      (is (false? (claim! tick-id))
          "terminal cleanup retains the claim while queued deliveries drain"))))
