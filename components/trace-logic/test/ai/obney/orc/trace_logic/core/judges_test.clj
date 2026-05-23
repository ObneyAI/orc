(ns ai.obney.orc.trace-logic.core.judges-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.judges :as j]
            [ai.obney.orc.trace-logic.core.ca :as ca]))

(defn- approx= [a b]
  (< (Math/abs (- (double a) (double b))) 1.0e-6))

;; Common fixtures
(defn fast-ca []
  (ca/->conscious-agent
   {:P-kernel [[0.5 0.5] [0.5 0.5]]   ; uniform → λ₂ = 0
    :D-kernel [[1.0 0.0] [0.0 1.0]]
    :A-kernel [[0.5 0.5] [0.5 0.5]]}))

(defn slow-ca []
  (ca/->conscious-agent
   {:P-kernel [[0.99 0.01] [0.01 0.99]]
    :D-kernel [[0.99 0.01] [0.01 0.99]]
    :A-kernel [[0.99 0.01] [0.01 0.99]]}))

;; =============================================================================
;; λ-2 judge
;; =============================================================================

(deftest lambda-2-bounded
  (testing "λ-2-judge returns score in [0, 1] for all CAs"
    (doseq [ca [(fast-ca) (slow-ca)]]
      (let [score (j/lambda-2-judge ca)]
        (is (<= 0.0 score 1.0))))))

(deftest lambda-2-fast-scores-higher
  (testing "Faster-converging CA gets a higher λ-2 score"
    (let [s-fast (j/lambda-2-judge (fast-ca))
          s-slow (j/lambda-2-judge (slow-ca))]
      (is (> s-fast s-slow)))))

(deftest lambda-2-uniform-perfect
  (testing "Uniform-row CA has λ-2 = 0 ⇒ score = 1.0"
    (let [ca-uniform (ca/->conscious-agent
                      {:P-kernel [[0.5 0.5] [0.5 0.5]]
                       :D-kernel [[0.5 0.5] [0.5 0.5]]
                       :A-kernel [[0.5 0.5] [0.5 0.5]]})]
      (is (approx= 1.0 (j/lambda-2-judge ca-uniform))))))

;; =============================================================================
;; Stationary proximity
;; =============================================================================

(deftest stationary-proximity-perfect-match
  (testing "Score is 1.0 when target = current stationary"
    (let [ca-instance (fast-ca)
          pi (:stationary-Q ca-instance)
          score (j/stationary-proximity-judge ca-instance pi)]
      (is (approx= 1.0 score)))))

(deftest stationary-proximity-disjoint
  (testing "Maximum TV-distance gives the lowest score (≈ 0)"
    (let [ca-instance (fast-ca)
          ;; fast-ca has stationary ≈ (.5 .5); a Dirac at state 0 is maximally far
          dirac [1.0 0.0]
          score (j/stationary-proximity-judge ca-instance dirac)]
      ;; TV(.5 .5, 1 0) = .5, so score = 1 - .5 = .5
      (is (approx= 0.5 score)))))

(deftest stationary-proximity-dimension-mismatch
  (testing "Mismatched dimensions return 0.0 gracefully"
    (let [ca-instance (fast-ca)
          target [0.33 0.33 0.34]]   ; wrong size
      (is (zero? (j/stationary-proximity-judge ca-instance target))))))

;; =============================================================================
;; Strategy coherence
;; =============================================================================

(deftest strategy-coherence-bounds
  (testing "Strategy coherence ∈ [0, 1]"
    (doseq [ca [(fast-ca) (slow-ca)]]
      (let [s (j/strategy-coherence-judge ca)]
        (is (<= 0.0 s 1.0))))))

(deftest strategy-coherence-deterministic-perfect
  (testing "Deterministic kernel (zero entropy) → coherence = 1.0"
    (let [ca-det (ca/->conscious-agent
                  {:P-kernel [[1.0 0.0] [0.0 1.0]]
                   :D-kernel [[1.0 0.0] [0.0 1.0]]
                   :A-kernel [[1.0 0.0] [0.0 1.0]]})]
      (is (approx= 1.0 (j/strategy-coherence-judge ca-det))))))

;; =============================================================================
;; Trace comparison
;; =============================================================================

(deftest trace-comparison-reflexive
  (testing "Every CA precedes itself"
    (let [c (fast-ca)]
      (is (j/trace-comparison-judge c c)))))

;; =============================================================================
;; RCC report
;; =============================================================================

(deftest rcc-report-shape
  (testing "rcc-report returns structured analytics"
    (let [r (j/rcc-report (fast-ca))]
      (is (contains? r :communicating-classes))
      (is (contains? r :recurrent-classes))
      (is (contains? r :bipartite))
      (is (contains? r :num-rccs))
      (is (contains? r :is-confined?))
      (is (contains? r :is-free?))
      (is (boolean? (:is-confined? r)))
      (is (boolean? (:is-free? r))))))

(deftest rcc-report-ergodic
  (testing "Ergodic CA: 1 RCC, not free"
    (let [c (fast-ca)
          r (j/rcc-report c)]
      (is (= 1 (:num-rccs r)))
      (is (false? (:is-free? r))))))
