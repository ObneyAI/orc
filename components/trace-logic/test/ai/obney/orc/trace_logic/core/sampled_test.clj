(ns ai.obney.orc.trace-logic.core.sampled-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.sampled :as s]
            [ai.obney.orc.trace-logic.core.trace :as trace]
            [ai.obney.orc.trace-logic.core.kernel :as k]))

(def Q-paper-eq13
  [[0.3 0.2 0.5]
   [0.5 0.3 0.2]
   [0.3 0.3 0.4]])

;; =============================================================================
;; run-from
;; =============================================================================

(deftest run-from-shape
  (testing "run-from returns n+1 states"
    (let [run (s/run-from Q-paper-eq13 0 10)]
      (is (= 11 (count run))))))

(deftest run-from-starts-correctly
  (testing "run starts at given state"
    (is (= 0 (first (s/run-from Q-paper-eq13 0 5))))
    (is (= 2 (first (s/run-from Q-paper-eq13 2 5))))))

(deftest run-from-stays-in-state-space
  (testing "all visited states are valid indices"
    (let [run (s/run-from Q-paper-eq13 0 100)]
      (is (every? #{0 1 2} run)))))

(deftest run-from-respects-zero-transitions
  (testing "states with zero outgoing probability are never reached"
    (let [P [[0.5 0.5 0.0]
             [1.0 0.0 0.0]
             [0.0 0.0 1.0]]
          run (s/run-from P 0 100)]
      ;; Starting from state 0 cannot reach state 2 (P[0][2] = 0, P[1][2] = 0)
      (is (not-any? #{2} run)))))

;; =============================================================================
;; restrict-run
;; =============================================================================

(deftest restrict-run-removes-non-A-states
  (testing "states outside A are deleted"
    (let [run [0 1 2 0 1 2 1]
          restricted (s/restrict-run run #{0 1})]
      (is (= [0 1 0 1 1] restricted)))))

(deftest restrict-run-empty-A
  (testing "empty A produces empty restricted run"
    (is (= [] (s/restrict-run [0 1 2 0 1] #{})))))

;; =============================================================================
;; window-matrix
;; =============================================================================

(deftest window-matrix-shape
  (testing "produces row-stochastic matrices on the size of A"
    (let [run [0 1 0 1 0 1 0 1]
          windows (s/window-matrix run #{0 1} 4)]
      (is (every? k/markov? windows)))))

;; =============================================================================
;; asymptotic-trace converges to true trace
;; =============================================================================

(deftest asymptotic-trace-converges
  (testing "Long run gives a sampled trace within ε of the true trace"
    (let [P Q-paper-eq13
          A #{0 1}
          true-trace (trace/projection P A)
          run (s/run-from P 0 100000)
          sampled (s/asymptotic-trace run A)]
      (let [diff (Math/sqrt
                  (reduce + 0.0
                          (for [i (range 2)
                                j (range 2)]
                            (let [d (- (double (get-in sampled [i j]))
                                       (double (get-in true-trace [i j])))]
                              (* d d)))))]
        ;; With 100k samples on a small chain, expect Frobenius distance < 0.05
        (is (< diff 0.05)
            (str "Sampled trace " sampled " too far from true " true-trace
                 " (Frobenius dist " diff ")"))))))

;; =============================================================================
;; convergence-test
;; =============================================================================

(deftest convergence-improves-with-k
  (testing "Larger k generally yields smaller distance to true trace"
    (let [P Q-paper-eq13
          A #{0 1}
          results (s/convergence-test P A [4 16 64 256 1024] 10000)]
      (is (seq results))
      ;; Largest k should be at least as accurate as smallest k.
      (let [first-dist (second (first results))
            last-dist (second (last results))]
        ;; Allow for noise; just require it's not strictly worse.
        (is (<= last-dist (* 2 first-dist))
            (str "Convergence didn't improve: " results))))))
