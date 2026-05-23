(ns ai.obney.orc.trace-logic.core.strategy-test
  "Tests that loading trace-logic.core.strategy registers :adaptive
   and :policy methods with orc-service's multimethods, and that
   default-fallback semantics hold when no policy is registered."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.orc.trace-logic.core.policy :as policy]
            [ai.obney.orc.trace-logic.core.strategy :as strategy]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]))

(use-fixtures :each
  (fn [test-fn]
    (reset! strategy/*policy-registry* {})
    (reset! strategy/*current-state-registry* {})
    (test-fn)))

;; =============================================================================
;; Multimethod registration
;; =============================================================================

(deftest adaptive-method-registered
  (testing ":adaptive is registered with next-child-strategy after loading strategy"
    (is (some? (get-method tp/next-child-strategy :adaptive)))))

(deftest policy-method-registered
  (testing ":policy is registered with parallel-completion-strategy"
    (is (some? (get-method tp/parallel-completion-strategy :policy)))))

;; =============================================================================
;; :adaptive falls back to linear when no policy registered
;; =============================================================================

(deftest adaptive-without-policy-uses-linear
  (testing "When no policy is registered, :adaptive returns the next sibling"
    (is (= :b (tp/next-child-strategy
               {:strategy-id :adaptive
                :siblings [:a :b :c]
                :child-index 0
                :parent {:id :test-parent}})))))

(deftest adaptive-with-mismatched-kernel-size-falls-back
  (testing "When the registered policy has wrong size, falls back to linear"
    (let [p (policy/uniform-policy 5)]
      (strategy/register! p)
      (is (= :b (tp/next-child-strategy
                 {:strategy-id :adaptive
                  :siblings [:a :b :c]
                  :child-index 0
                  :parent {:id :test-parent :policy-id (:policy-id p)}}))))))

;; =============================================================================
;; :adaptive with registered policy samples from kernel
;; =============================================================================

(deftest adaptive-with-deterministic-policy
  (testing "Deterministic policy that always picks index 2 yields :c"
    (let [p (policy/deterministic-policy (constantly 2) 3)]
      (strategy/register! p)
      ;; First call: kernel[0,2] = 1.0, returns sibling index 2 = :c
      (is (= :c (tp/next-child-strategy
                 {:strategy-id :adaptive
                  :siblings [:a :b :c]
                  :child-index 0
                  :parent {:id :test-parent :policy-id (:policy-id p)}}))))))

;; =============================================================================
;; :policy parallel completion currently delegates to :boolean
;; =============================================================================

(deftest policy-parallel-completion-without-policy-defaults
  (testing ":policy without registered policy uses boolean semantics"
    (is (= {:status :success}
           (tp/parallel-completion-strategy
            {:strategy-id :policy
             :child-counts {:success 3 :failure 0 :total-children 3 :completed 3}
             :success-policy :all
             :failure-policy :any
             :parent {:id :test-parent}})))))

;; =============================================================================
;; Tick-state cleanup
;; =============================================================================

(deftest clear-tick-state
  (testing "clear-tick-state! removes entries for the given tick"
    (swap! strategy/*current-state-registry*
           assoc [:tick-1 :node-a] 5 [:tick-2 :node-b] 7)
    (strategy/clear-tick-state! :tick-1)
    (is (not (contains? @strategy/*current-state-registry* [:tick-1 :node-a])))
    (is (contains? @strategy/*current-state-registry* [:tick-2 :node-b]))))
