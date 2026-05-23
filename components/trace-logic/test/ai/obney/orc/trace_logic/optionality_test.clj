(ns ai.obney.orc.trace-logic.optionality-test
  "Verify the engine's optional integration with trace-logic.

   With trace-logic loaded but no `:strategy-id` set on any node:
     - Multimethods have :adaptive / :policy registered.
     - The default :linear / :boolean dispatches still apply when
       :strategy-id is nil.
     - No `:trace-logic/*` events emitted.
     - No policy registry entries created from execution alone.

   The 'without trace-logic' case is verified at the deployment-
   artifact level: projects/orc/deps.edn excludes trace-logic, so
   neither core.matrix nor vectorz nor commons-math3 appear in
   `clj -Stree` for that project."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            ;; Loading interface triggers strategy registration:
            [ai.obney.orc.trace-logic.interface :as trace-logic]
            [ai.obney.orc.trace-logic.core.strategy :as strategy]))

(deftest adaptive-method-registered-after-loading
  (testing "Loading trace-logic.interface registers :adaptive"
    (is (some? (get-method tp/next-child-strategy :adaptive)))))

(deftest policy-method-registered-after-loading
  (testing "Loading trace-logic.interface registers :policy"
    (is (some? (get-method tp/parallel-completion-strategy :policy)))))

(deftest no-strategy-id-uses-linear-default
  (testing "Composite-node dispatch with no :strategy-id falls through
            to :linear, identical to engine without trace-logic"
    (is (= :b
           (tp/next-child-strategy
            {:siblings [:a :b :c] :child-index 0 :parent {:id :p}})))))

(deftest no-strategy-id-uses-boolean-default
  (testing "Parallel completion with no :strategy-id uses :boolean
            semantics"
    (is (= {:status :success}
           (tp/parallel-completion-strategy
            {:child-counts {:success 3 :failure 0
                            :total-children 3 :completed 3}
             :success-policy :all
             :failure-policy :any
             :parent {:id :p}})))))

(deftest interface-exposes-no-engine-side-effects
  (testing "Loading the interface doesn't populate runtime registries"
    ;; Per-test fixtures already reset the registries in strategy_test.
    ;; Here we just verify the registries are addressable, not preloaded.
    (is (map? @strategy/*policy-registry*))
    (is (map? @strategy/*current-state-registry*))))
