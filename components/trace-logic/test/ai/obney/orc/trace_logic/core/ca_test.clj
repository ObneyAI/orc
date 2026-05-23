(ns ai.obney.orc.trace-logic.core.ca-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.ca :as ca]
            [ai.obney.orc.trace-logic.core.kernel :as k]))

(def ^:private eps 1.0e-9)

(defn- approx= [a b]
  (< (Math/abs (- (double a) (double b))) eps))

(defn- mat-approx= [m1 m2]
  (and (= (count m1) (count m2))
       (every? identity
               (for [i (range (count m1))]
                 (every? identity
                         (map approx= (get m1 i) (get m2 i)))))))

;; =============================================================================
;; CA construction
;; =============================================================================

(def P-test
  "Sample P kernel (world → qualia)."
  [[0.7 0.2 0.1]
   [0.3 0.5 0.2]
   [0.4 0.4 0.2]])

(def D-test
  "Sample D kernel (qualia → action)."
  [[0.6 0.3 0.1]
   [0.2 0.5 0.3]
   [0.4 0.3 0.3]])

(def A-test
  "Sample A kernel (action → world)."
  [[0.5 0.4 0.1]
   [0.3 0.4 0.3]
   [0.2 0.5 0.3]])

(deftest ca-construction-basic
  (testing "->conscious-agent computes derived kernels Q and S"
    (let [ca-instance (ca/->conscious-agent
                       {:P-kernel P-test
                        :D-kernel D-test
                        :A-kernel A-test
                        :node-id :test-node})]
      (is (uuid? (:ca-id ca-instance)))
      (is (= :test-node (:node-id ca-instance)))
      (is (= P-test (:P-kernel ca-instance)))
      (is (= D-test (:D-kernel ca-instance)))
      (is (= A-test (:A-kernel ca-instance))))))

(deftest ca-Q-equals-DAP
  (testing "Q-kernel = D·A·P (paper eq. 5)"
    (let [ca-instance (ca/->conscious-agent
                       {:P-kernel P-test
                        :D-kernel D-test
                        :A-kernel A-test})
          expected (k/compose D-test A-test P-test)]
      (is (mat-approx= expected (ca/qualia-kernel ca-instance))))))

(deftest ca-S-equals-APD
  (testing "S-kernel = A·P·D (paper eq. 24)"
    (let [ca-instance (ca/->conscious-agent
                       {:P-kernel P-test
                        :D-kernel D-test
                        :A-kernel A-test})
          expected (k/compose A-test P-test D-test)]
      (is (mat-approx= expected (ca/strategy-kernel ca-instance))))))

(deftest ca-derived-fields
  (testing "Q and S kernels are Markov; stationary measures sum to 1"
    (let [ca-instance (ca/->conscious-agent
                       {:P-kernel P-test
                        :D-kernel D-test
                        :A-kernel A-test})]
      (is (k/markov? (ca/qualia-kernel ca-instance)))
      (is (k/markov? (ca/strategy-kernel ca-instance)))
      (is (approx= 1.0 (reduce + 0.0 (:stationary-Q ca-instance))))
      (is (approx= 1.0 (reduce + 0.0 (:stationary-S ca-instance))))
      (is (>= (:entropy-rate-Q ca-instance) 0.0))
      (is (>= (:entropy-rate-S ca-instance) 0.0)))))

(deftest ca-rejects-non-markov
  (testing "Construction fails for non-Markov inputs"
    (is (thrown? AssertionError
                 (ca/->conscious-agent
                  {:P-kernel [[1.0 0.5] [0.5 0.5]]   ; row 0 sums to 1.5
                   :D-kernel [[0.5 0.5] [0.5 0.5]]
                   :A-kernel [[0.5 0.5] [0.5 0.5]]})))))

(deftest ca-rejects-mismatched-dimensions
  (testing "Construction fails when P, D, A have different dimensions"
    (is (thrown? AssertionError
                 (ca/->conscious-agent
                  {:P-kernel [[1.0 0.0] [0.0 1.0]]            ; 2x2
                   :D-kernel [[1.0 0.0 0.0]                   ; 3x3
                              [0.0 1.0 0.0]
                              [0.0 0.0 1.0]]
                   :A-kernel [[1.0 0.0] [0.0 1.0]]})))))

;; =============================================================================
;; CA ordering: both Q and S must be in trace order
;; =============================================================================

(deftest ca-leq-reflexive
  (testing "Every CA ≤ itself"
    (let [c (ca/->conscious-agent
             {:P-kernel P-test :D-kernel D-test :A-kernel A-test})]
      (is (ca/leq? c c)))))

(deftest ca-equiv-self
  (testing "Every CA is trace-equivalent to itself (both Q and S match)"
    (let [c (ca/->conscious-agent
             {:P-kernel P-test :D-kernel D-test :A-kernel A-test})]
      (is (ca/equiv? c c)))))

(deftest counter-increment
  (testing "Counter starts at 0 and increments correctly"
    (let [c (ca/->conscious-agent
             {:P-kernel P-test :D-kernel D-test :A-kernel A-test})]
      (is (= 0 (:counter-N c)))
      (is (= 1 (:counter-N (ca/increment-counter c))))
      (is (= 5 (:counter-N
                (-> c
                    ca/increment-counter
                    ca/increment-counter
                    ca/increment-counter
                    ca/increment-counter
                    ca/increment-counter)))))))

(deftest counter-can-be-initialized
  (testing "counter-N can be initialized to a non-zero value"
    (let [c (ca/->conscious-agent
             {:P-kernel P-test :D-kernel D-test :A-kernel A-test
              :counter-N 42})]
      (is (= 42 (:counter-N c))))))
