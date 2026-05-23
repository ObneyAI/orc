(ns ai.obney.orc.trace-logic.core.meta-policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.meta-policy :as mp]
            [ai.obney.orc.trace-logic.core.policy :as p]
            [ai.obney.orc.trace-logic.core.kernel :as k]))

;; =============================================================================
;; Construction
;; =============================================================================

(deftest meta-policy-construction
  (testing "Meta-policy has level = max(sub-levels) + 1"
    (let [p0-a (p/uniform-policy 2)
          p0-b (p/uniform-policy 2)
          mp1 (mp/->meta-policy
               {:kernel [[0.5 0.5] [0.5 0.5]]
                :sub-policies [p0-a p0-b]})]
      (is (= 1 (:level mp1)))
      (is (= 2 (count (:sub-policies mp1)))))))

(deftest level-stacking
  (testing "Level increases when stacking meta-policies"
    (let [p0 (p/uniform-policy 2)
          p1 (mp/lift-to-meta p0)
          p2 (mp/lift-to-meta p1)]
      (is (= 0 (:level p0)))
      (is (= 1 (:level p1)))
      (is (= 2 (:level p2))))))

(deftest construction-rejects-mismatched-kernel-size
  (let [p0-a (p/uniform-policy 2)
        p0-b (p/uniform-policy 2)
        p0-c (p/uniform-policy 2)]
    (is (thrown? AssertionError
                 (mp/->meta-policy
                  {:kernel [[0.5 0.5] [0.5 0.5]]
                   :sub-policies [p0-a p0-b p0-c]})))))

;; =============================================================================
;; Sub-policy selection
;; =============================================================================

(deftest select-sub-policy-deterministic
  (testing "Deterministic meta-policy always selects the same sub-policy"
    (let [p0-a (assoc (p/uniform-policy 2) :tag :a)
          p0-b (assoc (p/uniform-policy 2) :tag :b)
          ;; Always-select-A meta-policy
          mp1 (mp/->meta-policy
               {:kernel [[1.0 0.0] [1.0 0.0]]
                :sub-policies [p0-a p0-b]})]
      (is (= :a (:tag (mp/select-sub-policy mp1 0))))
      (is (= :a (:tag (mp/select-sub-policy mp1 1)))))))

;; =============================================================================
;; Type-uniformity (Phase 1 ops apply at every level)
;; =============================================================================

(deftest meta-policy-leq-self
  (testing "Meta-policy is in trace-order with itself"
    (let [p0-a (p/uniform-policy 2)
          p0-b (p/uniform-policy 2)
          mp1 (mp/->meta-policy
               {:kernel [[0.5 0.5] [0.5 0.5]]
                :sub-policies [p0-a p0-b]})]
      (is (p/policy-leq? mp1 mp1)))))

(deftest meta-policy-stationary-defined
  (let [p0-a (p/uniform-policy 2)
        p0-b (p/uniform-policy 2)
        mp1 (mp/->meta-policy
             {:kernel [[0.7 0.3] [0.4 0.6]]
              :sub-policies [p0-a p0-b]})]
    (is (some? (:stationary mp1)))))

;; =============================================================================
;; Recursion: flattening
;; =============================================================================

(deftest flatten-policy-stack-returns-base
  (testing "Flattening a Policy_2 stack samples down to a Policy_0"
    (let [p0-a (p/uniform-policy 2)
          p0-b (p/uniform-policy 2)
          p1 (mp/->meta-policy
              {:kernel [[1.0 0.0] [1.0 0.0]]   ; deterministic, always pick first
               :sub-policies [p0-a p0-b]})
          p2 (mp/lift-to-meta p1)
          flat (mp/flatten-policy-stack p2 0)]
      (is (= 0 (:level flat))))))
