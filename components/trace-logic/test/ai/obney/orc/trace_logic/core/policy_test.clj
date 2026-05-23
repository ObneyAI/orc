(ns ai.obney.orc.trace-logic.core.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.policy :as p]
            [ai.obney.orc.trace-logic.core.kernel :as k]))

(defn- approx= [a b] (< (Math/abs (- (double a) (double b))) 1.0e-9))

;; =============================================================================
;; Policy construction
;; =============================================================================

(deftest policy-construction-basic
  (let [policy (p/->policy {:kernel [[0.7 0.3] [0.4 0.6]]
                            :level 0})]
    (is (uuid? (:policy-id policy)))
    (is (= 0 (:level policy)))
    (is (k/markov? (:kernel policy)))
    (is (some? (:stationary policy)))
    (is (some? (:lambda-2 policy)))))

(deftest policy-rejects-non-markov
  (testing "Construction fails for non-Markov kernels"
    (is (thrown? AssertionError
                 (p/->policy {:kernel [[0.5 0.7]] :level 0})))))

(deftest policy-rejects-negative-level
  (is (thrown? AssertionError
               (p/->policy {:kernel [[1.0]] :level -1}))))

;; =============================================================================
;; Sampling
;; =============================================================================

(deftest sample-next-respects-distribution
  (testing "Sampling many times approaches the kernel's row distribution"
    (let [P [[0.7 0.3] [0.4 0.6]]
          policy (p/->policy {:kernel P :level 0})
          n-samples 5000
          samples (repeatedly n-samples #(p/sample-next policy 0))
          freq-1 (/ (count (filter #(= 1 %) samples)) (double n-samples))]
      ;; Expect ~0.3 with sampling noise
      (is (< 0.27 freq-1 0.33)))))

(deftest sample-next-deterministic-kernel
  (testing "A 1-on-row kernel always samples that row"
    (let [P [[1.0 0.0] [0.0 1.0]]
          policy (p/->policy {:kernel P :level 0})]
      (is (= 0 (p/sample-next policy 0)))
      (is (= 1 (p/sample-next policy 1))))))

;; =============================================================================
;; Trace operations on policies
;; =============================================================================

(deftest policy-leq-self
  (let [policy (p/->policy {:kernel [[0.5 0.5] [0.5 0.5]] :level 0})]
    (is (p/policy-leq? policy policy))))

(deftest policy-trace-projection
  (let [policy (p/->policy {:kernel [[0.3 0.2 0.5]
                                     [0.5 0.3 0.2]
                                     [0.3 0.3 0.4]]
                            :level 0})
        traced (p/policy-trace policy #{0 1})]
    (is (= 0 (:level traced)))
    (is (k/markov? (:kernel traced)))
    (is (= 2 (count (:kernel traced))))))

;; =============================================================================
;; Baseline policies
;; =============================================================================

(deftest uniform-policy-construction
  (let [policy (p/uniform-policy 3)]
    (is (k/markov? (:kernel policy)))
    (doseq [row (:kernel policy)
            entry row]
      (is (approx= (/ 1.0 3) entry)))))

(deftest deterministic-policy-construction
  (let [;; "Cycle" permutation: 0 → 1 → 2 → 0
        policy (p/deterministic-policy (fn [i] (mod (inc i) 3)) 3)]
    (is (k/markov? (:kernel policy)))
    (is (= 0 (p/sample-next policy 2)))
    (is (= 1 (p/sample-next policy 0)))
    (is (= 2 (p/sample-next policy 1)))))
