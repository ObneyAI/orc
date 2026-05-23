(ns ai.obney.orc.trace-logic.core.learning-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.learning :as l]
            [ai.obney.orc.trace-logic.core.policy :as p]
            [ai.obney.orc.trace-logic.core.kernel :as k]))

;; =============================================================================
;; Initial state
;; =============================================================================

(deftest initial-state-shape
  (let [policy (p/uniform-policy 3)
        state (l/->dirichlet-state policy)]
    (is (= 3 (:n state)))
    (is (= 0 (:update-count state)))
    (is (= 3 (count (:counts state))))))

;; =============================================================================
;; Single observation
;; =============================================================================

(deftest observe-single
  (let [policy (p/uniform-policy 2)
        state (l/->dirichlet-state policy)
        updated (l/observe state 0 1 1.0)]
    (is (= 1 (:update-count updated)))
    ;; Count for [0,1] should have incremented
    (let [c-before (get-in (:counts state) [0 1])
          c-after (get-in (:counts updated) [0 1])]
      (is (> c-after c-before)))))

;; =============================================================================
;; Convergence to ground truth
;; =============================================================================

(deftest converges-to-true-distribution
  (testing "With many observations from a known distribution, the
            posterior kernel converges to that distribution"
    (let [;; True kernel: state 0 transitions to {0:.3, 1:.7}
          ;;              state 1 transitions to {0:.6, 1:.4}
          true-kernel [[0.3 0.7] [0.6 0.4]]
          policy (p/uniform-policy 2)
          state-0 (l/->dirichlet-state policy :alpha 0.01 :prior-strength 0.0)
          rng (java.util.Random. 42)
          ;; Generate 5000 observations from the true kernel
          n-samples 5000
          observations
          (vec
           (repeatedly
            n-samples
            (fn []
              (let [from (.nextInt rng 2)
                    row (nth true-kernel from)
                    u (.nextDouble rng)
                    to (if (< u (first row)) 0 1)]
                [from to 1.0]))))
          state-final (l/observe-batch state-0 observations)
          fitted (l/current-posterior-kernel state-final)]
      ;; Each entry should be within ~0.05 of the true value.
      (doseq [i (range 2)
              j (range 2)]
        (is (< (Math/abs
                (- (double (get-in true-kernel [i j]))
                   (double (get-in fitted [i j]))))
               0.05)
            (str "fitted[" i "," j "] = " (get-in fitted [i j])
                 " too far from true " (get-in true-kernel [i j])))))))

;; =============================================================================
;; Reward weighting
;; =============================================================================

(deftest reward-weighting
  (testing "Higher reward yields stronger posterior shift"
    (let [policy (p/uniform-policy 2)
          state-0 (l/->dirichlet-state policy :alpha 0.01 :prior-strength 0.0)
          state-low-reward (l/observe state-0 0 1 0.1)
          state-high-reward (l/observe state-0 0 1 10.0)
          k-low (l/current-posterior-kernel state-low-reward)
          k-high (l/current-posterior-kernel state-high-reward)]
      ;; Both should shift toward [0,1], but high-reward shifts more
      (is (> (get-in k-high [0 1])
             (get-in k-low [0 1]))))))

;; =============================================================================
;; Negative rewards clipped
;; =============================================================================

(deftest negative-rewards-clipped
  (let [policy (p/uniform-policy 2)
        state-0 (l/->dirichlet-state policy)
        state-1 (l/observe state-0 0 0 -1.0)
        state-2 (l/observe state-0 0 0 0.0)]
    ;; Negative reward = clipped to 0 = no information added
    (is (= (:counts state-1) (:counts state-2)))))

;; =============================================================================
;; Update applied to policy
;; =============================================================================

(deftest update-policy-preserves-kernel
  (let [policy (p/uniform-policy 2)
        state (l/->dirichlet-state policy)
        ;; Observe many transitions favoring [0 → 1]
        state' (reduce (fn [s _] (l/observe s 0 1 1.0))
                       state
                       (range 100))
        new-policy (l/update-policy policy state')]
    (is (k/markov? (:kernel new-policy)))
    (is (= (:level policy) (:level new-policy)))
    ;; Probability of 0 → 1 should be high in the updated kernel
    (is (> (get-in (:kernel new-policy) [0 1]) 0.5))))
