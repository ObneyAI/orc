(ns ai.obney.orc.trace-logic.core.lebesgue-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.lebesgue :as lb]
            [ai.obney.orc.trace-logic.core.kernel :as k]
            [ai.obney.orc.trace-logic.core.trace :as t]))

(def ^:private eps 1.0e-9)

(defn- approx= [a b]
  (< (Math/abs (- (double a) (double b))) eps))

(defn- vec-approx= [v1 v2]
  (and (= (count v1) (count v2))
       (every? identity (map approx= v1 v2))))

;; =============================================================================
;; Lebesgue order: leq?
;; =============================================================================

(deftest leq-reflexivity
  (testing "Every measure is ≤_L itself"
    (doseq [mu [[0.5 0.5]
                [1.0 0.0]
                [0.25 0.5 0.25]
                [0.1 0.2 0.3 0.4]]]
      (is (lb/leq? mu mu)))))

(deftest leq-restriction-on-support
  (testing "ν is a normalized restriction of μ ⇒ ν ≤_L μ"
    ;; μ = (.25 .5 .25); ν supported on {0, 1} normalized: (.333 .667 0)
    (let [mu [0.25 0.5 0.25]
          nu (lb/restrict-stationary mu #{0 1})]
      (is (lb/leq? nu mu))
      (is (vec-approx= [(/ 0.25 0.75) (/ 0.5 0.75) 0.0] nu)))))

(deftest leq-narrower-not-greater
  (testing "ν ≤_L μ but μ ⊄ ν when their supports differ"
    (let [mu [0.25 0.5 0.25]
          nu [(/ 0.25 0.75) (/ 0.5 0.75) 0.0]]
      (is (lb/leq? nu mu))
      (is (not (lb/leq? mu nu))))))

(deftest leq-different-shape-fails
  (testing "Same-support but different-shape measures are incomparable"
    (let [mu [0.5 0.5]
          nu [0.7 0.3]]
      (is (not (lb/leq? mu nu)))
      (is (not (lb/leq? nu mu))))))

(deftest leq-disjoint-supports-fail
  (testing "Disjoint-support measures are incomparable"
    (let [mu [1.0 0.0 0.0]
          nu [0.0 1.0 0.0]]
      (is (not (lb/leq? mu nu)))
      (is (not (lb/leq? nu mu))))))

;; =============================================================================
;; decompose
;; =============================================================================

(deftest decompose-orthogonality
  (testing "μ_ν + μ^ν = μ; μ_ν supported on supp(ν); μ^ν disjoint from supp(ν)"
    (let [mu [0.2 0.3 0.5]
          nu [0.5 0.5 0.0]
          {:keys [ac singular]} (lb/decompose mu nu)]
      (is (vec-approx= mu (mapv + ac singular)))
      ;; ac supported on {0, 1}; zero at index 2
      (is (approx= 0.0 (nth ac 2)))
      ;; singular supported on {2}; zero at indices 0, 1
      (is (approx= 0.0 (nth singular 0)))
      (is (approx= 0.0 (nth singular 1))))))

;; =============================================================================
;; meet / join
;; =============================================================================

(deftest meet-disjoint-supports
  (let [mu [1.0 0.0 0.0]
        nu [0.0 1.0 0.0]]
    (is (= :trace-logic/empty-lebesgue-meet (lb/meet mu nu)))))

(deftest meet-shared-support
  (testing "meet on shared support returns the normalized restriction"
    (let [mu [0.25 0.5 0.25]
          nu [0.4 0.4 0.2]
          m (lb/meet mu nu)]
      ;; common support = {0, 1, 2}; meet = normalize(mu) = mu since it's already prob.
      (is (vec-approx= mu m)))))

(deftest join-disjoint-supports-sums
  (let [mu [0.6 0.4 0.0]
        nu [0.0 0.0 1.0]
        j (lb/join mu nu)]
    (is (vec-approx= [0.3 0.2 0.5] j))))

;; =============================================================================
;; Stationary Map Theorem (paper §4)
;; =============================================================================

(deftest stationary-map-homomorphism-paper-eq26
  (testing "Paper eq. 26: stationary(P) = (.25 .5 .25); restrictions to {0,1}, {0,2}, {1,2} match the stationary measures of the corresponding traces (eq. 28-29)"
    (let [P [[0.25 0.5 0.25]
             [0.25 0.5 0.25]
             [0.25 0.5 0.25]]
          pi-P (k/stationary P)
          ;; Paper eq. 28: traces P_12, P_13, P_23
          ;; Paper eq. 29: their stationary measures
          P12 (t/projection P #{0 1})
          P13 (t/projection P #{0 2})
          P23 (t/projection P #{1 2})
          pi-12 (k/stationary P12)
          pi-13 (k/stationary P13)
          pi-23 (k/stationary P23)]

      (is (vec-approx= [0.25 0.5 0.25] pi-P))

      ;; Stationary Map Theorem: stationary(trace(P, A)) = restrict-stationary(pi-P, A)
      ;; Note: restrict-stationary returns full-length vector with zeros outside A;
      ;; pi-12 is length 2 (only on the trace state space). We compare via
      ;; the restricted-to-support form.
      (let [restricted-12 (vec (filter pos? (lb/restrict-stationary pi-P #{0 1})))
            restricted-13 (vec (filter pos? (lb/restrict-stationary pi-P #{0 2})))
            restricted-23 (vec (filter pos? (lb/restrict-stationary pi-P #{1 2})))]
        ;; Paper eq. 29: μ_12 = (1/3, 2/3); μ_13 = (.5, .5); μ_23 = (2/3, 1/3)
        (is (vec-approx= [(/ 1.0 3) (/ 2.0 3)] restricted-12))
        (is (vec-approx= [0.5 0.5] restricted-13))
        (is (vec-approx= [(/ 2.0 3) (/ 1.0 3)] restricted-23))
        ;; And the actual trace stationaries match
        (is (vec-approx= restricted-12 pi-12))
        (is (vec-approx= restricted-13 pi-13))
        (is (vec-approx= restricted-23 pi-23))))))

(deftest stationary-map-homomorphism-general
  (testing "Stationary Map Theorem holds on a general ergodic kernel"
    (let [P [[0.3 0.2 0.5]
             [0.5 0.3 0.2]
             [0.3 0.3 0.4]]
          pi-P (k/stationary P)
          subset #{0 1}
          P-A (t/projection P subset)
          pi-A (k/stationary P-A)
          restricted (vec (filter pos? (lb/restrict-stationary pi-P subset)))]
      (is (vec-approx= restricted pi-A)))))

(deftest restrict-stationary-correctness
  (testing "Normalized restriction is a probability measure"
    (let [pi [0.25 0.5 0.25]
          restricted (lb/restrict-stationary pi #{0 1})]
      ;; Restriction should sum to 1
      (is (approx= 1.0 (reduce + 0.0 restricted)))
      ;; Outside the subset should be zero
      (is (approx= 0.0 (nth restricted 2))))))
