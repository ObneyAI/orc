(ns ai.obney.orc.orc-service.budget-cancellation-claim-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

(deftest terminal-cleanup-does-not-reopen-budget-cancellation-claim
  (testing "queued budget exhaustion deliveries cannot cancel one tick twice"
    (let [tick-id (random-uuid)
          claim! #'service-logic/claim-budget-cancellation!]
      (is (true? (claim! tick-id)) "the first delivery owns cancellation")
      (service-logic/clear-llm-count! tick-id)
      (is (false? (claim! tick-id))
          "terminal cleanup retains the claim while queued deliveries drain"))))
