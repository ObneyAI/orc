(ns ai.obney.orc.orc-service.phase2-budget-test
  "Unit tests for resolve-phase2-budget (D-003).

   Resolver signature:
     (resolve-phase2-budget {:node node
                             :parent-timeout-ms nil-or-int
                             :phase1-elapsed-ms int})
     ;; => {:total-budget-ms :remaining-ms :source :exhausted?}

   Lookup order:
     1. (:timeout-ms node)        → :source :node
     2. :parent-timeout-ms arg    → :source :tick
     3. 900_000 ms hardcoded      → :source :hardcoded"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.executor :as executor]))

(deftest node-timeout-takes-precedence
  (testing "node :timeout-ms takes precedence over parent-timeout-ms and hardcoded"
    (let [result (executor/resolve-phase2-budget
                   {:node {:timeout-ms 30000}
                    :parent-timeout-ms 60000
                    :phase1-elapsed-ms 5000})]
      (is (= :node (:source result)))
      (is (= 30000 (:total-budget-ms result)))
      (is (= 25000 (:remaining-ms result)))
      (is (false? (:exhausted? result))))))

(deftest tick-fallback-when-no-node-timeout
  (testing "uses parent-timeout-ms when node has no :timeout-ms"
    (let [result (executor/resolve-phase2-budget
                   {:node {}
                    :parent-timeout-ms 60000
                    :phase1-elapsed-ms 5000})]
      (is (= :tick (:source result)))
      (is (= 60000 (:total-budget-ms result)))
      (is (= 55000 (:remaining-ms result)))
      (is (false? (:exhausted? result))))))

(deftest hardcoded-fallback-when-no-config-anywhere
  (testing "falls back to 900_000 ms when neither node nor parent has :timeout-ms"
    (let [result (executor/resolve-phase2-budget
                   {:node {}
                    :parent-timeout-ms nil
                    :phase1-elapsed-ms 5000})]
      (is (= :hardcoded (:source result)))
      (is (= 900000 (:total-budget-ms result)))
      (is (= 895000 (:remaining-ms result)))
      (is (false? (:exhausted? result))))))

(deftest exhausted-when-phase1-eats-the-budget
  (testing "phase1 elapsed exceeds total budget → :exhausted? true, :remaining 0"
    (let [result (executor/resolve-phase2-budget
                   {:node {:timeout-ms 5000}
                    :parent-timeout-ms 60000
                    :phase1-elapsed-ms 6000})]
      (is (= :node (:source result)) "node :timeout-ms still wins for source")
      (is (= 5000 (:total-budget-ms result)))
      (is (= 0 (:remaining-ms result)) "remaining clamped to 0, not negative")
      (is (true? (:exhausted? result)))))

  (testing "phase1 elapsed exactly equals total budget → :exhausted? true"
    (let [result (executor/resolve-phase2-budget
                   {:node {:timeout-ms 5000}
                    :parent-timeout-ms nil
                    :phase1-elapsed-ms 5000})]
      (is (= 0 (:remaining-ms result)))
      (is (true? (:exhausted? result))
          "boundary: elapsed == budget should be exhausted (no useful time left for Phase 2)"))))
