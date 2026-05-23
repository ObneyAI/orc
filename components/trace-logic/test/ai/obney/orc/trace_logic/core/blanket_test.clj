(ns ai.obney.orc.trace-logic.core.blanket-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.trace-logic.core.blanket :as bl]
            [ai.obney.orc.trace-logic.core.ca :as ca]))

(defn- approx= [a b] (< (Math/abs (- (double a) (double b))) 1.0e-9))

;; =============================================================================
;; State-entropy computation
;; =============================================================================

(deftest entropy-deterministic-row
  (testing "Deterministic row has zero entropy"
    (let [P [[1.0 0.0 0.0]
             [0.0 1.0 0.0]
             [0.0 0.0 1.0]]
          entropies (bl/state-entropies P)]
      (is (every? #(approx= 0.0 %) entropies)))))

(deftest entropy-uniform-row-equals-log
  (testing "Uniform row has entropy log₂(n)"
    (let [P [[0.25 0.25 0.25 0.25]
             [0.25 0.25 0.25 0.25]
             [0.25 0.25 0.25 0.25]
             [0.25 0.25 0.25 0.25]]
          entropies (bl/state-entropies P)]
      (doseq [e entropies]
        (is (approx= 2.0 e))))))

;; =============================================================================
;; Partition by entropy
;; =============================================================================

(deftest partition-with-known-control-structure
  (testing "States with deterministic outgoing rows go to :self;
            uniform-row states go to :world"
    (let [;; State 0 deterministic; states 1, 2 uniform
          P [[1.0 0.0 0.0]
             [(/ 1.0 3) (/ 1.0 3) (/ 1.0 3)]
             [(/ 1.0 3) (/ 1.0 3) (/ 1.0 3)]]
          {:keys [self world]} (bl/partition-by-entropy P)]
      (is (contains? self 0))
      ;; States 1 and 2 have higher entropy → :world
      (is (or (contains? world 1) (contains? world 2))))))

(deftest partition-explicit-threshold
  (testing "Explicit threshold partitions correctly"
    (let [P [[1.0 0.0]
             [0.5 0.5]]
          {:keys [self world threshold]} (bl/partition-by-entropy P 0.5)]
      ;; Row 0: H = 0 < 0.5 → :self
      ;; Row 1: H = 1 ≥ 0.5 → :world
      (is (contains? self 0))
      (is (contains? world 1))
      (is (= 0.5 threshold)))))

(deftest partition-covers-state-space
  (testing "Self and world partitions cover all states"
    (let [P [[0.7 0.3] [0.4 0.6] [0.5 0.0]]
          ;; Make P proper (third row needs to sum to 1)
          P [[0.7 0.3 0.0] [0.4 0.4 0.2] [0.5 0.3 0.2]]
          {:keys [self world]} (bl/partition-by-entropy P)]
      (is (= #{0 1 2}
             (clojure.set/union self world)))
      (is (empty? (clojure.set/intersection self world))))))

;; =============================================================================
;; CA trace blanket
;; =============================================================================

(deftest ca-blanket-basic
  (testing "Trace blanket of a CA is well-formed"
    (let [c (ca/->conscious-agent
             {:P-kernel [[0.7 0.3] [0.4 0.6]]
              :D-kernel [[0.5 0.5] [0.4 0.6]]
              :A-kernel [[0.7 0.3] [0.5 0.5]]
              :node-id :test-node})
          blanket (bl/ca-trace-blanket c)]
      (is (set? (:self blanket)))
      (is (set? (:world blanket)))
      (is (some? (:threshold blanket)))
      (is (= :test-node (:node-id blanket))))))

;; =============================================================================
;; Relabeling invariance
;; =============================================================================

(deftest blanket-relabel-invariant
  (testing "Partition is invariant under permutation of state labels"
    (let [P [[1.0 0.0 0.0]
             [(/ 1.0 3) (/ 1.0 3) (/ 1.0 3)]
             [0.5 0.5 0.0]]
          ;; Permutation: 0 → 2, 1 → 0, 2 → 1
          permutation [2 0 1]]
      (is (bl/relabel-invariant? P permutation)))))

(deftest blanket-relabel-multiple-permutations
  (testing "Invariance holds across several random permutations"
    (let [P [[0.9 0.1 0.0]
             [0.5 0.5 0.0]
             [(/ 1.0 3) (/ 1.0 3) (/ 1.0 3)]]
          permutations [[0 1 2] [1 0 2] [2 1 0] [0 2 1] [2 0 1] [1 2 0]]]
      (doseq [perm permutations]
        (is (bl/relabel-invariant? P perm)
            (str "Failed for permutation " perm))))))
