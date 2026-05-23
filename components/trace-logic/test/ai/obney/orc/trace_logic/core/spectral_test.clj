(ns ai.obney.orc.trace-logic.core.spectral-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.spectral :as spec]))

(def ^:private eps 1.0e-6)

(defn- approx= [a b]
  (< (Math/abs (- (double a) (double b))) eps))

;; =============================================================================
;; eigen-decompose
;; =============================================================================

(deftest principal-eigenvalue-is-one
  (testing "Largest eigenvalue magnitude of any Markov kernel is 1"
    (doseq [P [[[0.5 0.5] [0.3 0.7]]
               [[0.2 0.3 0.5] [0.5 0.1 0.4] [0.4 0.5 0.1]]
               [[1.0]]
               [[0.0 1.0] [1.0 0.0]]]]
      (let [{:keys [magnitudes]} (spec/eigen-decompose P)
            max-mag (apply max magnitudes)]
        (is (approx= 1.0 max-mag) (str "Failed for " P))))))

(deftest decompose-shape
  (testing "eigen-decompose returns n eigenvalues for n×n kernel"
    (let [P [[0.4 0.6] [0.7 0.3]]
          {:keys [values magnitudes vectors]} (spec/eigen-decompose P)]
      (is (= 2 (count values)))
      (is (= 2 (count magnitudes)))
      (is (= 2 (count vectors))))))

;; =============================================================================
;; lambda-2
;; =============================================================================

(deftest lambda-2-bounds
  (testing "λ₂ ∈ [0, 1] for any Markov kernel"
    (doseq [P [[[0.5 0.5] [0.3 0.7]]
               [[0.2 0.3 0.5] [0.5 0.1 0.4] [0.4 0.5 0.1]]
               [[0.9 0.1] [0.1 0.9]]]]
      (let [l2 (spec/lambda-2 P)]
        (is (<= 0.0 l2 1.0) (str "λ₂ out of bounds for " P ": " l2))))))

(deftest lambda-2-uniform-rows
  (testing "Kernel with all-equal rows has λ₂ = 0 (instant convergence)"
    (let [P [[0.25 0.5 0.25]
             [0.25 0.5 0.25]
             [0.25 0.5 0.25]]]
      (is (approx= 0.0 (spec/lambda-2 P))))))

(deftest lambda-2-period-2
  (testing "Period-2 NOT kernel has λ₂ = 1 (oscillatory, no convergence)"
    (let [P [[0.0 1.0] [1.0 0.0]]
          l2 (spec/lambda-2 P)]
      ;; The eigenvalues are +1 and -1; |λ₂| = 1.
      (is (< (Math/abs (- 1.0 l2)) 1.0e-3)))))

(deftest lambda-2-near-identity
  (testing "Slowly-mixing kernel has λ₂ near 1"
    (let [P [[0.99 0.01] [0.01 0.99]]
          l2 (spec/lambda-2 P)]
      ;; Eigenvalues of [[1-a a][a 1-a]] are 1 and 1-2a; here 1-2(.01) = .98.
      (is (approx= 0.98 l2)))))

;; =============================================================================
;; convergence-half-life
;; =============================================================================

(deftest half-life-monotone-in-lambda-2
  (testing "Larger λ₂ ⇒ longer half-life"
    (let [fast [[0.5 0.5] [0.5 0.5]]      ; λ₂ = 0
          slow [[0.99 0.01] [0.01 0.99]]] ; λ₂ ≈ 0.98
      (is (< (spec/convergence-half-life fast)
             (spec/convergence-half-life slow))))))

(deftest half-life-period-2-is-infinite
  (let [P [[0.0 1.0] [1.0 0.0]]]
    (is (= Double/POSITIVE_INFINITY (spec/convergence-half-life P)))))
