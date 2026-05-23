(ns ai.obney.orc.trace-logic.core.kernel-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.kernel :as k]))

(def ^:private eps 1.0e-9)

(defn- approx=
  ([a b] (approx= a b eps))
  ([a b tol] (< (Math/abs (- (double a) (double b))) tol)))

(defn- vec-approx= [v1 v2]
  (and (= (count v1) (count v2))
       (every? identity (map approx= v1 v2))))

(defn- mat-approx= [m1 m2]
  (and (= (count m1) (count m2))
       (every? identity (map vec-approx= m1 m2))))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def I2 [[1.0 0.0] [0.0 1.0]])
(def I3 [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]])

(def NOT2 [[0.0 1.0] [1.0 0.0]])
(def NOT3 [[0.0 1.0 0.0] [0.0 0.0 1.0] [1.0 0.0 0.0]])

(def Q-rgb
  "Paper eq. 8 — the canonical 3-state qualia kernel."
  [[0.2 0.3 0.5]
   [0.5 0.1 0.4]
   [0.4 0.5 0.1]])

(def P-paper-eq26
  "Paper eq. 26 — kernel with stationary measure (.25 .5 .25)."
  [[0.25 0.5 0.25]
   [0.25 0.5 0.25]
   [0.25 0.5 0.25]])

(def reducible-2-rccs
  "Two RCCs: {0,1} and {2,3}, no transitions between them."
  [[0.4 0.6 0.0 0.0]
   [0.3 0.7 0.0 0.0]
   [0.0 0.0 0.5 0.5]
   [0.0 0.0 0.2 0.8]])

;; =============================================================================
;; markov?
;; =============================================================================

(deftest markov?-positive-cases
  (testing "identity matrices are Markov"
    (is (k/markov? I2))
    (is (k/markov? I3)))

  (testing "permutation matrices are Markov"
    (is (k/markov? NOT2))
    (is (k/markov? NOT3)))

  (testing "paper examples are Markov"
    (is (k/markov? Q-rgb))
    (is (k/markov? P-paper-eq26)))

  (testing "reducible kernels are Markov"
    (is (k/markov? reducible-2-rccs))))

(deftest markov?-negative-cases
  (testing "rows that don't sum to 1 fail"
    (is (not (k/markov? [[0.2 0.3] [0.5 0.5]])))
    (is (not (k/markov? [[1.5 -0.5] [0.5 0.5]]))))

  (testing "negative entries fail"
    (is (not (k/markov? [[1.2 -0.2] [0.5 0.5]]))))

  (testing "non-square matrices fail"
    (is (not (k/markov? [[0.5 0.5 0.0] [0.5 0.5 0.0]]))))

  (testing "non-sequential input fails"
    (is (not (k/markov? nil)))
    (is (not (k/markov? "not-a-matrix")))
    (is (not (k/markov? [])))))

;; =============================================================================
;; semi-markov?
;; =============================================================================

(deftest semi-markov?-cases
  (testing "Markov kernel is semi-Markov on full support"
    (is (k/semi-markov? Q-rgb #{0 1 2})))

  (testing "kernel with zero rows outside support is semi-Markov"
    (let [P [[0.5 0.5 0.0]
             [0.3 0.7 0.0]
             [0.0 0.0 0.0]]]
      (is (k/semi-markov? P #{0 1}))))

  (testing "kernel with non-zero rows outside support fails"
    (let [P [[0.5 0.5 0.0]
             [0.3 0.7 0.0]
             [0.5 0.5 0.0]]]
      (is (not (k/semi-markov? P #{0 1}))))))

;; =============================================================================
;; stationary
;; =============================================================================

(deftest stationary-fixed-point
  (testing "stationary measure satisfies π·P = π for ergodic kernels"
    (doseq [P [Q-rgb
               P-paper-eq26
               [[0.7 0.3] [0.4 0.6]]
               [[0.1 0.6 0.3] [0.5 0.2 0.3] [0.2 0.4 0.4]]]]
      (let [pi (k/stationary P)
            piP (mapv (fn [j]
                        (reduce + 0.0
                                (map-indexed
                                 (fn [i pi-i]
                                   (* (double pi-i) (double (get-in P [i j]))))
                                 pi)))
                      (range (count P)))]
        (is (vec-approx= pi piP)
            (str "Expected stationary fixed-point for " P
                 ", got π=" pi " but πP=" piP)))))

  (testing "stationary measure sums to 1"
    (doseq [P [Q-rgb P-paper-eq26]]
      (is (approx= 1.0 (reduce + 0.0 (k/stationary P))))))

  (testing "paper eq. 26 has stationary (.25 .5 .25)"
    (let [pi (k/stationary P-paper-eq26)]
      (is (vec-approx= [0.25 0.5 0.25] pi))))

  (testing "single-state kernel has stationary [1.0]"
    (is (vec-approx= [1.0] (k/stationary [[1.0]])))))

;; =============================================================================
;; compose / strategy-of
;; =============================================================================

(deftest compose-matrix-multiplication
  (testing "compose two kernels reduces to matrix multiplication"
    (let [P [[0.5 0.5] [0.3 0.7]]
          Q [[0.8 0.2] [0.4 0.6]]
          PQ (k/compose P Q)
          expected [[(+ (* 0.5 0.8) (* 0.5 0.4)) (+ (* 0.5 0.2) (* 0.5 0.6))]
                    [(+ (* 0.3 0.8) (* 0.7 0.4)) (+ (* 0.3 0.2) (* 0.7 0.6))]]]
      (is (mat-approx= expected PQ))))

  (testing "compose with identity is identity"
    (is (mat-approx= Q-rgb (k/compose Q-rgb I3)))
    (is (mat-approx= Q-rgb (k/compose I3 Q-rgb))))

  (testing "compose preserves Markov property"
    (let [D [[0.5 0.3 0.2] [0.1 0.6 0.3] [0.4 0.2 0.4]]
          A [[0.7 0.3 0.0] [0.2 0.5 0.3] [0.1 0.1 0.8]]
          P [[0.4 0.4 0.2] [0.3 0.3 0.4] [0.5 0.2 0.3]]]
      (is (k/markov? (k/compose D A P))))))

(deftest strategy-of-is-APD
  (testing "strategy-of D A P returns A·P·D (paper eq. 24)"
    (let [D [[0.5 0.5] [0.3 0.7]]
          A [[0.8 0.2] [0.4 0.6]]
          P [[0.6 0.4] [0.5 0.5]]
          S (k/strategy-of D A P)
          expected (k/compose A P D)]
      (is (mat-approx= expected S)))))

;; =============================================================================
;; entropy-rate
;; =============================================================================

(deftest entropy-rate-bounds
  (testing "entropy rate of NOT (period-2) is 0 — periodic kernel"
    (is (approx= 0.0 (k/entropy-rate NOT2))))

  (testing "entropy rate of identity is 0 — no transitions"
    (is (approx= 0.0 (k/entropy-rate I2))))

  (testing "uniform-rows kernel has entropy rate log₂ n"
    ;; For P-paper-eq26 (rows all (.25 .5 .25)), each row has entropy
    ;; H = -.25 log .25 -.5 log .5 -.25 log .25 = 1.5 (in bits)
    (is (approx= 1.5 (k/entropy-rate P-paper-eq26) 1.0e-6)))

  (testing "entropy rate is bounded by log₂ n for any P"
    (doseq [P [Q-rgb P-paper-eq26]]
      (let [n (count P)]
        (is (<= 0.0 (k/entropy-rate P) (/ (Math/log n) (Math/log 2))))))))

;; =============================================================================
;; communicating-classes / recurrent-classes
;; =============================================================================

(deftest communicating-classes-decomposition
  (testing "ergodic kernel has one communicating class containing all states"
    (let [classes (k/communicating-classes Q-rgb)]
      (is (= 1 (count classes)))
      (is (= #{0 1 2} (first classes)))))

  (testing "reducible kernel with 2 RCCs has 2 communicating classes"
    (let [classes (k/communicating-classes reducible-2-rccs)]
      (is (= 2 (count classes)))
      (is (= #{#{0 1} #{2 3}} (set classes)))))

  (testing "permutation NOT3 has one communicating class"
    (let [classes (k/communicating-classes NOT3)]
      (is (= 1 (count classes)))
      (is (= #{0 1 2} (first classes)))))

  (testing "identity has n classes of size 1"
    (let [classes (k/communicating-classes I3)]
      (is (= 3 (count classes)))
      (is (= #{#{0} #{1} #{2}} (set classes))))))

(deftest recurrent-classes-are-closed
  (testing "ergodic kernel: the unique class is recurrent"
    (is (= 1 (count (k/recurrent-classes Q-rgb)))))

  (testing "reducible 2-RCCs kernel has 2 RCCs"
    (is (= 2 (count (k/recurrent-classes reducible-2-rccs)))))

  (testing "kernel with absorbing state and transient states"
    (let [P [[1.0 0.0 0.0]
             [0.5 0.5 0.0]
             [0.3 0.3 0.4]]
          rccs (k/recurrent-classes P)]
      ;; State 0 is absorbing → its singleton is the only RCC
      (is (= [#{0}] rccs)))))

;; =============================================================================
;; Edge cases
;; =============================================================================

(deftest single-state-kernel
  (let [P [[1.0]]]
    (is (k/markov? P))
    (is (vec-approx= [1.0] (k/stationary P)))
    (is (= [#{0}] (k/communicating-classes P)))
    (is (= [#{0}] (k/recurrent-classes P)))
    (is (approx= 0.0 (k/entropy-rate P)))))

(deftest near-singular-rows
  (testing "rows differing by < 1e-9 are accepted as Markov"
    (let [P [[0.49999999 0.50000001]
             [0.5 0.5]]]
      (is (k/markov? P)))))

(deftest sparse-kernel
  (testing "mostly-zero rows compute correctly"
    (let [P [[0.0 1.0 0.0 0.0]
             [0.0 0.0 1.0 0.0]
             [0.0 0.0 0.0 1.0]
             [1.0 0.0 0.0 0.0]]]
      (is (k/markov? P))
      ;; period-4 rotation; stationary is uniform
      (is (vec-approx= [0.25 0.25 0.25 0.25] (k/stationary P)))
      (is (approx= 0.0 (k/entropy-rate P))))))
