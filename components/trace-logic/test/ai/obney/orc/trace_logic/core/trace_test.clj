(ns ai.obney.orc.trace-logic.core.trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.trace :as t]))

(def ^:private eps 5.0e-4)  ; paper values rounded to 4 decimal places

(defn- approx= [a b]
  (< (Math/abs (- (double a) (double b))) eps))

(defn- mat-approx= [m1 m2]
  (and (= (count m1) (count m2))
       (every? identity
               (for [i (range (count m1))]
                 (and (= (count (get m1 i)) (count (get m2 i)))
                      (every? identity
                              (map approx= (get m1 i) (get m2 i))))))))

;; =============================================================================
;; Paper regression tests
;; =============================================================================

(def Q-paper-eq13
  "Paper eq. 13 — 3-state kernel used as the trace example."
  [[0.3 0.2 0.5]
   [0.5 0.3 0.2]
   [0.3 0.3 0.4]])

(def Q12-paper-eq14
  "Paper eq. 14 — the trace of Q (eq. 13) on states {0, 1}."
  [[0.55 0.45]
   [0.6 0.4]])

(deftest paper-eq13-to-eq14
  (testing "Trace of paper eq. 13 onto {0, 1} matches eq. 14 exactly"
    (let [traced (t/projection Q-paper-eq13 #{0 1})]
      (is (mat-approx= Q12-paper-eq14 traced)
          (str "Expected " Q12-paper-eq14 ", got " traced)))))

(def Q-rgb
  "Paper eq. 8 — 3-state kernel."
  [[0.2 0.3 0.5]
   [0.5 0.1 0.4]
   [0.4 0.5 0.1]])

(deftest q-rgb-trace-rg-row-stochastic
  (testing "trace(Q_rgb, {0, 1}) is Markov and row 0 matches paper eq. 9"
    (let [traced (t/projection Q-rgb #{0 1})]
      ;; Both rows must sum to 1.
      (doseq [row traced]
        (is (approx= 1.0 (reduce + 0.0 row))))
      ;; Row 0 (red): paper eq. 9 = [0.4222, 0.5778]; computed value matches.
      ;; (Row 1 in paper eq. 9 lists [0.7071 0.2929], which doesn't follow
      ;; from eq. 8's Q_rgb under the trace formula — likely a typo. Our
      ;; eq. 13 → eq. 14 regression validates the formula authoritatively.)
      (is (approx= 0.4222 (get-in traced [0 0])))
      (is (approx= 0.5778 (get-in traced [0 1]))))))

;; =============================================================================
;; Block-decomposition correctness on simple constructed kernels
;; =============================================================================

(deftest trace-with-no-hidden-states
  (testing "Tracing P onto its full state space returns P unchanged"
    (let [P [[0.4 0.6] [0.7 0.3]]]
      (is (mat-approx= P (t/projection P #{0 1}))))))

(deftest trace-onto-singleton
  (testing "Tracing onto a single state returns a 1×1 [[1.0]] kernel"
    (let [P [[0.4 0.6] [0.7 0.3]]
          traced (t/projection P #{0})]
      (is (= 1 (count traced)))
      (is (= 1 (count (first traced))))
      (is (approx= 1.0 (reduce + 0.0 (first traced)))))))

(deftest trace-rejects-empty-subset
  (testing "Tracing onto the empty set throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (t/projection [[0.5 0.5] [0.5 0.5]] #{})))))

;; =============================================================================
;; Trace order: leq?
;; =============================================================================

(deftest leq-reflexivity
  (testing "Every Markov kernel is a trace of itself"
    (doseq [P [Q-paper-eq13 Q-rgb [[1.0]] [[0.5 0.5] [0.3 0.7]]]]
      (is (t/leq? P P) (str P)))))

(deftest leq-of-trace
  (testing "trace(P, A) ≤ₜ P for any A"
    (let [P Q-paper-eq13
          A #{0 1}
          P_A (t/projection P A)]
      (is (t/leq? P_A P)))))

(deftest leq-fails-when-not-a-trace
  (testing "An unrelated 2x2 kernel is not generally a trace of a different 3x3 kernel"
    (let [unrelated [[0.9 0.1] [0.2 0.8]]]
      ;; This kernel is *very* unlikely to be a trace of Q-paper-eq13;
      ;; the trace order rules out arbitrary 2x2 matrices.
      (is (not (t/leq? unrelated Q-paper-eq13))))))

(deftest leq-trivial-cases
  (testing "Empty matrix is ≤ anything (vacuously); larger never ≤ smaller"
    (is (t/leq? [] Q-paper-eq13))
    (is (not (t/leq? Q-paper-eq13 [[1.0]])))))

;; =============================================================================
;; all-traces enumeration
;; =============================================================================

(deftest all-traces-counts
  (testing "all-traces of an n-state kernel returns 2ⁿ − 1 entries"
    (is (= 3 (count (t/all-traces [[0.4 0.6] [0.7 0.3]]))))         ; 2² − 1
    (is (= 7 (count (t/all-traces Q-paper-eq13))))                  ; 2³ − 1
    (is (= 15 (count (t/all-traces [[0.25 0.25 0.25 0.25]
                                    [0.25 0.25 0.25 0.25]
                                    [0.25 0.25 0.25 0.25]
                                    [0.25 0.25 0.25 0.25]]))))))    ; 2⁴ − 1

(deftest all-traces-includes-original
  (testing "all-traces includes the full-state-space subset (= P itself)"
    (let [traces (t/all-traces Q-paper-eq13)
          full (some (fn [[A K]] (when (= A #{0 1 2}) K)) traces)]
      (is (mat-approx= Q-paper-eq13 full)))))

(deftest all-traces-includes-paper-eq14
  (testing "all-traces includes the {0,1} trace matching paper eq. 14"
    (let [traces (t/all-traces Q-paper-eq13)
          rg (some (fn [[A K]] (when (= A #{0 1}) K)) traces)]
      (is (mat-approx= Q12-paper-eq14 rg)))))

;; =============================================================================
;; Partial-order axioms (deterministic small cases)
;; =============================================================================

(deftest axioms-hold-on-paper-example
  (let [P Q-paper-eq13
        Q (t/projection P #{0 1})    ; Q is a trace of P
        R (t/projection Q #{0})      ; R is a trace of Q (and thus of P)
        result (t/order-axioms-hold? R Q P)]
    (is (every? true? (vals result)) (str "Axioms failed: " result))))

;; =============================================================================
;; meet / join — closed-form cases
;; =============================================================================

(deftest meet-with-common-ancestor
  (testing "meet(P, A, B) = trace(P, A ∩ B)"
    (let [P Q-paper-eq13
          A #{0 1}
          B #{1 2}
          m (t/meet P A B)
          expected (t/projection P #{1})]
      (is (mat-approx= expected m)))))

(deftest meet-disjoint-subsets
  (testing "meet on disjoint subsets returns the empty-meet sentinel"
    (is (= :trace-logic/empty-meet
           (t/meet Q-paper-eq13 #{0} #{1 2})))))

(deftest meet-no-ancestor-trivial-cases
  (testing "meet(M, M) = M"
    (is (mat-approx= Q-paper-eq13 (t/meet Q-paper-eq13 Q-paper-eq13))))

  (testing "meet(M, N) where M ≤ₜ N returns M"
    (let [P Q-paper-eq13
          Q (t/projection P #{0 1})]
      (is (mat-approx= Q (t/meet Q P)))))

  (testing "meet of incomparable arbitrary M, N returns sentinel"
    (let [arbitrary [[0.9 0.1] [0.2 0.8]]
          unrelated [[0.3 0.7] [0.6 0.4]]]
      (is (= :trace-logic/meet-requires-common-ancestor
             (t/meet arbitrary unrelated))))))

(deftest join-with-common-ancestor
  (testing "join(P, A, B) = trace(P, A ∪ B)"
    (let [P Q-paper-eq13
          A #{0}
          B #{1}
          j (t/join P A B)
          expected (t/projection P #{0 1})]
      (is (mat-approx= expected j)))))

(deftest join-no-ancestor-trivial-cases
  (testing "join(M, M) = M"
    (is (mat-approx= Q-paper-eq13 (t/join Q-paper-eq13 Q-paper-eq13))))

  (testing "join(M, N) where M ≤ₜ N returns N"
    (let [P Q-paper-eq13
          Q (t/projection P #{0 1})]
      (is (mat-approx= P (t/join Q P)))))

  (testing "join of incomparable arbitrary M, N returns sentinel"
    (let [arbitrary [[0.9 0.1] [0.2 0.8]]
          unrelated [[0.3 0.7] [0.6 0.4]]]
      (is (= :trace-logic/no-closed-form
             (t/join arbitrary unrelated))))))
