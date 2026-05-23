(ns ai.obney.orc.trace-logic.paper-examples-test
  "Consolidated regression tests against numerical examples from
   *Traces of Consciousness* (Hoffman, Prakash, Chattopadhyay 2024).

   This file is the authoritative cross-reference between the paper's
   equations/figures and the substrate's test coverage. Many of these
   examples are also exercised in per-primitive test files; we
   re-assert them here for one-stop verification."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.kernel :as k]
            [ai.obney.orc.trace-logic.core.trace :as t]
            [ai.obney.orc.trace-logic.core.lebesgue :as lb]
            [ai.obney.orc.trace-logic.core.spectral :as spec]
            [ai.obney.orc.trace-logic.core.community :as comm]
            [ai.obney.orc.trace-logic.core.sampled :as s]))

(def ^:private eps 5.0e-4)

(defn- approx= [a b]
  (< (Math/abs (- (double a) (double b))) eps))

(defn- mat-approx= [m1 m2]
  (and (= (count m1) (count m2))
       (every? identity
               (for [i (range (count m1))]
                 (and (= (count (get m1 i)) (count (get m2 i)))
                      (every? identity
                              (map approx= (get m1 i) (get m2 i))))))))

(defn- vec-approx= [v1 v2]
  (and (= (count v1) (count v2))
       (every? identity (map approx= v1 v2))))

;; =============================================================================
;; Section 3 — Trace formula and trace order
;; =============================================================================

(deftest paper-eq-8-Q-rgb-is-markov
  (testing "Paper eq. 8 — Q_rgb is a valid 3-state Markov kernel"
    (let [Q-rgb [[0.2 0.3 0.5]
                 [0.5 0.1 0.4]
                 [0.4 0.5 0.1]]]
      (is (k/markov? Q-rgb)))))

(deftest paper-eq-13-to-14-trace
  (testing "Paper eq. 13 → eq. 14 — trace of Q on {0,1} matches Q_{12}"
    (let [Q [[0.3 0.2 0.5]
             [0.5 0.3 0.2]
             [0.3 0.3 0.4]]
          Q12 [[0.55 0.45]
               [0.6 0.4]]]
      (is (mat-approx= Q12 (t/projection Q #{0 1}))))))

;; =============================================================================
;; Section 4 — Stationary Map Theorem
;; =============================================================================

(deftest paper-eq-26-stationary-map-homomorphism
  (testing "Paper eq. 26-29 — Stationary Map Theorem on full P with 3 traces"
    (let [P [[0.25 0.5 0.25]
             [0.25 0.5 0.25]
             [0.25 0.5 0.25]]
          ;; Paper eq. 27: μ_P = (.25, .5, .25)
          ;; Paper eq. 28: P_12, P_13, P_23
          ;; Paper eq. 29: μ_12 = (1/3, 2/3); μ_13 = (.5, .5); μ_23 = (2/3, 1/3)
          pi (k/stationary P)]

      (is (vec-approx= [0.25 0.5 0.25] pi))

      (testing "P_12 = trace(P, {0, 1})"
        (is (mat-approx= [[(/ 1.0 3) (/ 2.0 3)]
                          [(/ 1.0 3) (/ 2.0 3)]]
                         (t/projection P #{0 1}))))

      (testing "P_13 = trace(P, {0, 2})"
        (is (mat-approx= [[0.5 0.5]
                          [0.5 0.5]]
                         (t/projection P #{0 2}))))

      (testing "P_23 = trace(P, {1, 2})"
        (is (mat-approx= [[(/ 2.0 3) (/ 1.0 3)]
                          [(/ 2.0 3) (/ 1.0 3)]]
                         (t/projection P #{1 2}))))

      (testing "Stationary measures match paper eq. 29 via Stationary Map"
        (let [restrict (fn [A]
                         (vec (filter pos? (lb/restrict-stationary pi A))))]
          (is (vec-approx= [(/ 1.0 3) (/ 2.0 3)] (restrict #{0 1})))
          (is (vec-approx= [0.5 0.5] (restrict #{0 2})))
          (is (vec-approx= [(/ 2.0 3) (/ 1.0 3)] (restrict #{1 2}))))))))

;; =============================================================================
;; Section 6 — Communicating-class structure (free / bound / confined)
;; =============================================================================

(deftest paper-fig-10-free-kernel
  (testing "Paper fig. 10 — 'free' kernel has 2 RCCs (disconnected components)"
    (let [free [[0.4 0.6 0.0 0.0]
                [0.3 0.7 0.0 0.0]
                [0.0 0.0 0.5 0.5]
                [0.0 0.0 0.2 0.8]]]
      (is (= 2 (count (k/recurrent-classes free)))))))

(deftest paper-fig-10-bound-kernel
  (testing "Paper fig. 10 — 'bound' kernel has 1 RCC (small inter-class edges)"
    (let [bound [[0.4 0.5 0.0 0.1]
                 [0.3 0.7 0.0 0.0]
                 [0.0 0.0 0.5 0.5]
                 [0.1 0.0 0.2 0.7]]]
      (is (= 1 (count (k/recurrent-classes bound)))))))

(deftest paper-fig-10-confined-bipartite
  (testing "Paper fig. 10 — 'confined' kernel is bipartite into {0,1} and {2,3}"
    (let [confined [[0.0 0.0 0.4 0.6]
                    [0.0 0.0 0.3 0.7]
                    [0.5 0.5 0.0 0.0]
                    [0.2 0.8 0.0 0.0]]
          partition (comm/bipartite-decomposition confined)]
      (is (= #{#{0 1} #{2 3}} (set partition))))))

;; =============================================================================
;; Section 6 — Entropy rate as physical mass analog
;; =============================================================================

(deftest paper-fig-6-entropy-rate-bounded-by-log-n
  (testing "Paper fig. 6 — entropy rate of 2x2 polytope ∈ [0, 1]; max at uniform rows"
    (let [;; Sample 50 2x2 Markov kernels and check H ∈ [0, log₂ 2]
          samples (for [_ (range 50)]
                    (let [a (rand)
                          b (rand)]
                      [[a (- 1 a)] [b (- 1 b)]]))]
      (doseq [P samples]
        (let [h (k/entropy-rate P)]
          (is (<= 0.0 h 1.0001) (str "H=" h " out of bounds for " P)))))

    (testing "Maximum entropy rate (log₂ 2 = 1) at uniform"
      (let [P [[0.5 0.5] [0.5 0.5]]]
        (is (approx= 1.0 (k/entropy-rate P)))))

    (testing "Zero entropy rate at NOT (period-2)"
      (let [P [[0.0 1.0] [1.0 0.0]]]
        (is (approx= 0.0 (k/entropy-rate P)))))))

;; =============================================================================
;; Section 6 — Speed-of-light analog: minimal commute time on periodic chains
;; =============================================================================

(deftest paper-eq-48-NOT-zero-entropy-period-2
  (testing "Paper eq. 48 — NOT matrix has zero entropy rate"
    (let [NOT [[0.0 1.0] [1.0 0.0]]]
      (is (approx= 0.0 (k/entropy-rate NOT))))))

;; =============================================================================
;; Section 3 — Sampled trace chains
;; =============================================================================

(deftest paper-section-3-windowing-restricted-run
  (testing "restrict-run preserves sequence order and removes non-A states"
    ;; Paper §3, around eq. 16: T_123 = (1,3,1,2,1,3,2,3,3,2,2,1,3,2,2,1,1,1,3,3,2,2,1,1,3,1,2,1,...)
    ;; Restrict to {1, 2} (remove 3s): T_12 = (1,1,2,1,2,2,1,2,2,1,1,1,2,2,1,1,2,1,...)
    ;; Note: paper indexes from 1; we use 0-indexed states. State 1↔0, 2↔1, 3↔2.
    (let [run [0 2 0 1 0 2 1 2 2 1 1 0 2 1 1 0 0 0 2 2 1 1 0 0 2 0 1 0]
          A #{0 1}
          restricted (s/restrict-run run A)
          expected [0 0 1 0 1 1 1 0 1 1 0 0 0 1 1 0 0 0 1 0]]
      (is (= expected restricted)))))

(deftest paper-section-3-window-matrices-are-markov
  (testing "Paper §3 — window-matrix produces row-stochastic matrices"
    (let [run [0 2 0 1 0 2 1 2 2 1 1 0 2 1 1 0 0 0 2 2 1 1 0 0 2 0 1 0]
          windows (s/window-matrix run #{0 1} 6)]
      (is (every? k/markov? windows)))))

;; =============================================================================
;; λ₂ as intelligence metric (Hoffman lecture)
;; =============================================================================

(deftest lambda-2-converges-rate-relationship
  (testing "Hoffman lecture: smaller λ₂ ⇒ faster convergence to stationary"
    (let [fast [[0.5 0.5] [0.5 0.5]]       ; uniform rows, λ₂ = 0
          medium [[0.7 0.3] [0.4 0.6]]
          slow [[0.99 0.01] [0.01 0.99]]]  ; nearly identity, λ₂ ≈ 0.98
      (is (< (spec/lambda-2 fast)
             (spec/lambda-2 medium)
             (spec/lambda-2 slow))))))
