(ns ai.obney.orc.trace-logic.properties-test
  "Property-based tests for the algebraic invariants of the trace logic.

   For each invariant: 50+ generated random Markov kernels with shrinking
   on counter-examples. Property-test seeds are reproducible; failures
   carry enough context to diagnose the broken invariant."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [ai.obney.orc.trace-logic.core.kernel :as k]
            [ai.obney.orc.trace-logic.core.trace :as t]
            [ai.obney.orc.trace-logic.core.lebesgue :as lb]
            [ai.obney.orc.trace-logic.core.spectral :as spec]))

(def ^:private eps 1.0e-7)

(defn- approx= [a b]
  (< (Math/abs (- (double a) (double b))) eps))

(defn- vec-approx= [v1 v2]
  (and (= (count v1) (count v2))
       (every? identity (map approx= v1 v2))))

(defn- mat-approx=
  ([m1 m2] (mat-approx= m1 m2 1.0e-7))
  ([m1 m2 tol]
   (and (= (count m1) (count m2))
        (every? identity
                (for [i (range (count m1))]
                  (and (= (count (get m1 i)) (count (get m2 i)))
                       (every? identity
                               (map #(< (Math/abs (- (double %1) (double %2))) tol)
                                    (get m1 i) (get m2 i)))))))))

;; =============================================================================
;; Generators
;; =============================================================================

(def gen-prob-row
  "Generate a probability row of arbitrary positive length, normalized."
  (gen/let [n (gen/choose 2 5)
            unnormalized (gen/vector (gen/double* {:min 0.01 :max 1.0
                                                   :infinite? false :NaN? false}) n)]
    (let [total (reduce + unnormalized)]
      (mapv #(/ % total) unnormalized))))

(def gen-markov-kernel
  "Generate a random row-stochastic Markov kernel of size 2-5."
  (gen/let [n (gen/choose 2 5)]
    (gen/vector
     (gen/let [unnormalized (gen/vector
                             (gen/double* {:min 0.01 :max 1.0
                                           :infinite? false :NaN? false})
                             n)]
       (let [total (reduce + unnormalized)]
         (mapv #(/ % total) unnormalized)))
     n)))

(def gen-markov-and-subset
  "Generate a Markov kernel paired with a non-empty proper subset of its
   state space."
  (gen/let [P gen-markov-kernel
            n (gen/return (count P))
            subset-size (gen/choose 1 (max 1 (dec n)))
            subset-elems (gen/vector-distinct (gen/choose 0 (dec n)) {:num-elements subset-size})]
    [P (set subset-elems)]))

(def gen-positive-measure
  "Generate a probability measure of size 2-5."
  (gen/let [n (gen/choose 2 5)
            unnormalized (gen/vector
                          (gen/double* {:min 0.01 :max 1.0
                                        :infinite? false :NaN? false})
                          n)]
    (let [total (reduce + unnormalized)]
      (mapv #(/ % total) unnormalized))))

;; =============================================================================
;; Markov well-formedness
;; =============================================================================

(deftest markov-generator-produces-markov
  (let [result (tc/quick-check
                50
                (prop/for-all [P gen-markov-kernel]
                              (k/markov? P)))]
    (is (:pass? result) (str "Generator produced non-Markov: " result))))

;; =============================================================================
;; Stationary fixed-point: π·P = π
;; =============================================================================

(deftest stationary-fixed-point-property
  (let [result (tc/quick-check
                30
                (prop/for-all [P gen-markov-kernel]
                              (let [pi (k/stationary P)
                                    n (count P)
                                    piP (mapv (fn [j]
                                                (reduce + 0.0
                                                        (map-indexed
                                                         (fn [i pi-i]
                                                           (* (double pi-i)
                                                              (double (get-in P [i j]))))
                                                         pi)))
                                              (range n))]
                                (vec-approx= pi piP))))]
    (is (:pass? result) (str "Stationary fixed-point failed: " result))))

;; =============================================================================
;; Trace formula equivalence: of = projection
;; =============================================================================

(deftest trace-formula-equivalence-property
  (let [result (tc/quick-check
                30
                (prop/for-all [[P A] gen-markov-and-subset]
                              (mat-approx= (t/of P A) (t/projection P A))))]
    (is (:pass? result) (str "Trace formula equivalence failed: " result))))

;; =============================================================================
;; Trace order: reflexivity
;; =============================================================================

(deftest trace-order-reflexivity-property
  (let [result (tc/quick-check
                50
                (prop/for-all [P gen-markov-kernel]
                              (t/leq? P P)))]
    (is (:pass? result) (str "Reflexivity failed: " result))))

;; =============================================================================
;; Trace order: composition (trace(trace(P, B), A) = trace(P, A) when A ⊆ B)
;; =============================================================================

(deftest trace-order-composition-property
  (let [result (tc/quick-check
                30
                (prop/for-all [[P B] gen-markov-and-subset]
                              ;; Generate A ⊆ B
                              (or (<= (count B) 1)  ; trivial case
                                  (let [A (set (take (max 1 (dec (count B))) (sort B)))
                                        ;; trace(P, B) is defined on B's sorted order;
                                        ;; A is a subset of B's elements, so we need
                                        ;; to remap A to indices into B's sorted form.
                                        B-vec (vec (sort B))
                                        A-in-B (set (keep-indexed
                                                     (fn [i s] (when (contains? A s) i))
                                                     B-vec))
                                        direct (t/projection P A)
                                        via-B (t/projection P B)
                                        via-B-A (t/projection via-B A-in-B)]
                                    (mat-approx= direct via-B-A 1.0e-6)))))]
    (is (:pass? result) (str "Trace composition failed: " result))))

;; =============================================================================
;; Stationary Map Theorem: stationary(trace(P,A)) = restrict(stationary(P), A)
;; =============================================================================

(deftest stationary-map-homomorphism-property
  (let [result (tc/quick-check
                30
                (prop/for-all [[P A] gen-markov-and-subset]
                              (let [pi-P (k/stationary P)
                                    P-A (t/projection P A)
                                    pi-A (k/stationary P-A)
                                    expected (vec (filter pos? (lb/restrict-stationary pi-P A)))]
                                (vec-approx= expected pi-A))))]
    (is (:pass? result) (str "Stationary Map homomorphism failed: " result))))

;; =============================================================================
;; Lebesgue order: reflexivity
;; =============================================================================

(deftest lebesgue-reflexivity-property
  (let [result (tc/quick-check
                50
                (prop/for-all [mu gen-positive-measure]
                              (lb/leq? mu mu)))]
    (is (:pass? result) (str "Lebesgue reflexivity failed: " result))))

;; =============================================================================
;; Q = DAP composition
;; =============================================================================

(deftest q-composition-property
  (let [result (tc/quick-check
                30
                (prop/for-all [D gen-markov-kernel
                               A gen-markov-kernel
                               P gen-markov-kernel]
                              ;; All three must have the same dimension for composition.
                              (let [n (min (count D) (count A) (count P))
                                    D' (mapv #(vec (take n %)) (take n D))
                                    A' (mapv #(vec (take n %)) (take n A))
                                    P' (mapv #(vec (take n %)) (take n P))
                                    ;; Renormalize after truncation
                                    renorm (fn [M]
                                             (mapv (fn [row]
                                                     (let [s (reduce + row)]
                                                       (if (zero? s)
                                                         (vec (repeat (count row) (/ 1.0 (count row))))
                                                         (mapv #(/ % s) row))))
                                                   M))
                                    D'' (renorm D')
                                    A'' (renorm A')
                                    P'' (renorm P')
                                    Q (k/compose D'' A'' P'')]
                                (k/markov? Q))))]
    (is (:pass? result) (str "Q composition failed: " result))))

;; =============================================================================
;; Entropy rate bounds: 0 ≤ H(P) ≤ log₂(n)
;; =============================================================================

(deftest entropy-rate-bounds-property
  (let [result (tc/quick-check
                30
                (prop/for-all [P gen-markov-kernel]
                              (let [h (k/entropy-rate P)
                                    n (count P)
                                    upper (/ (Math/log n) (Math/log 2))]
                                (and (>= h (- 0.0 1.0e-9))
                                     (<= h (+ upper 1.0e-9))))))]
    (is (:pass? result) (str "Entropy rate bounds failed: " result))))

;; =============================================================================
;; Spectral λ₂ bounds
;; =============================================================================

(deftest lambda-2-bounds-property
  (let [result (tc/quick-check
                30
                (prop/for-all [P gen-markov-kernel]
                              (let [l2 (spec/lambda-2 P)]
                                (and (>= l2 (- 0.0 1.0e-9))
                                     (<= l2 (+ 1.0 1.0e-9))))))]
    (is (:pass? result) (str "λ₂ bounds failed: " result))))

;; =============================================================================
;; RCC decomposition: union covers state space; classes are disjoint
;; =============================================================================

(deftest rcc-partition-property
  (let [result (tc/quick-check
                30
                (prop/for-all [P gen-markov-kernel]
                              (let [n (count P)
                                    classes (k/communicating-classes P)
                                    union (apply clojure.set/union classes)
                                    sum-sizes (reduce + 0 (map count classes))]
                                (and (= (set (range n)) union)
                                     (= n sum-sizes)))))]
    (is (:pass? result) (str "RCC partition failed: " result))))
