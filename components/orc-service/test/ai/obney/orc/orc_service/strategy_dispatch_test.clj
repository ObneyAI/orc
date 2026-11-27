(ns ai.obney.orc.orc-service.strategy-dispatch-test
  "Tests for the multimethod extension points added to todo_processors.

   These verify:
     - The :linear / :boolean defaults preserve the engine's prior
       behavior bit-identically.
     - Unknown strategy ids fall back to defaults (no exception).
     - User-registered defmethods dispatch correctly.
     - Removing registered methods returns dispatch to default."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

;; =============================================================================
;; next-child-strategy
;; =============================================================================

(deftest linear-default-matches-legacy-behavior
  (testing ":linear default reproduces (get (vec siblings) (inc child-index))"
    (let [siblings [:a :b :c :d]]
      (is (= :b (service-logic/next-child-strategy
                 {:siblings siblings :child-index 0})))
      (is (= :c (service-logic/next-child-strategy
                 {:siblings siblings :child-index 1})))
      (is (= :d (service-logic/next-child-strategy
                 {:siblings siblings :child-index 2})))
      (is (nil? (service-logic/next-child-strategy
                 {:siblings siblings :child-index 3}))))))

(deftest linear-explicit-strategy-id
  (testing "Explicit :strategy-id :linear behaves identically to default"
    (let [siblings [:a :b :c]]
      (is (= :b (service-logic/next-child-strategy
                 {:strategy-id :linear :siblings siblings :child-index 0}))))))

(deftest unknown-strategy-falls-back-to-linear
  (testing "Unknown :strategy-id resolves to :linear semantics, not exception"
    (let [siblings [:a :b :c]]
      (is (= :b (service-logic/next-child-strategy
                 {:strategy-id :unregistered-strategy
                  :siblings siblings
                  :child-index 0}))))))

(deftest user-registered-method-dispatches
  (testing "A defmethod for a custom keyword overrides default"
    (try
      (defmethod service-logic/next-child-strategy ::test-custom
        [_]
        ::test-custom-result)
      (is (= ::test-custom-result
             (service-logic/next-child-strategy
              {:strategy-id ::test-custom :siblings [:a] :child-index 0})))
      (finally
        (remove-method service-logic/next-child-strategy ::test-custom)))))

(deftest remove-method-returns-to-default
  (testing "After remove-method, dispatch falls back to :linear"
    (defmethod service-logic/next-child-strategy ::ephemeral
      [_]
      ::ephemeral-result)
    (is (= ::ephemeral-result
           (service-logic/next-child-strategy
            {:strategy-id ::ephemeral :siblings [:a :b] :child-index 0})))
    (remove-method service-logic/next-child-strategy ::ephemeral)
    (is (= :b (service-logic/next-child-strategy
               {:strategy-id ::ephemeral :siblings [:a :b] :child-index 0})))))

;; =============================================================================
;; parallel-completion-strategy
;; =============================================================================

(deftest boolean-default-success-policies
  (testing ":all success policy: complete with :success when all children succeeded"
    (is (= {:status :success}
           (service-logic/parallel-completion-strategy
            {:child-counts {:success 3 :failure 0 :total-children 3 :completed 3}
             :success-policy :all
             :failure-policy :any}))))

  (testing ":any success policy: complete with :success when any child succeeded"
    (is (= {:status :success}
           (service-logic/parallel-completion-strategy
            {:child-counts {:success 1 :failure 0 :total-children 3 :completed 1}
             :success-policy :any
             :failure-policy :any}))))

  (testing ":majority success: complete with :success when more than half succeeded"
    (is (= {:status :success}
           (service-logic/parallel-completion-strategy
            {:child-counts {:success 2 :failure 0 :total-children 3 :completed 2}
             :success-policy :majority
             :failure-policy :any})))))

(deftest boolean-default-failure-policies
  (testing ":any failure policy: fail as soon as any child fails"
    (is (= {:status :failure}
           (service-logic/parallel-completion-strategy
            {:child-counts {:success 0 :failure 1 :total-children 3 :completed 1}
             :success-policy :all
             :failure-policy :any}))))

  (testing ":all failure policy: fail only when every child has failed"
    (is (nil? (service-logic/parallel-completion-strategy
               {:child-counts {:success 0 :failure 1 :total-children 3 :completed 1}
                :success-policy :all
                :failure-policy :all})))
    (is (= {:status :failure}
           (service-logic/parallel-completion-strategy
            {:child-counts {:success 0 :failure 3 :total-children 3 :completed 3}
             :success-policy :all
             :failure-policy :all})))))

(deftest boolean-default-not-yet-complete
  (testing "Returns nil when not enough children have completed"
    (is (nil? (service-logic/parallel-completion-strategy
               {:child-counts {:success 1 :failure 0 :total-children 3 :completed 1}
                :success-policy :all
                :failure-policy :any})))))

(deftest unknown-parallel-strategy-falls-back
  (testing "Unknown parallel :strategy-id resolves to :boolean semantics"
    (is (= {:status :success}
           (service-logic/parallel-completion-strategy
            {:strategy-id :unregistered
             :child-counts {:success 3 :failure 0 :total-children 3 :completed 3}
             :success-policy :all
             :failure-policy :any})))))
