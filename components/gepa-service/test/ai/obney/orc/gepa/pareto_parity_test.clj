(ns ai.obney.orc.gepa.pareto-parity-test
  "Algorithm parity tests for GEPA Pareto selection.

   Verifies EXACT behavior match with Python GEPA gepa_utils.py:
   - is_dominated
   - remove_dominated_programs
   - find_dominator_programs
   - select_program_candidate_from_pareto_front

   Test cases derived from Python GEPA behavior."
  (:require [ai.obney.orc.gepa.core.logic :as logic]
            [clojure.test :refer [deftest testing is are]]))

;; =============================================================================
;; Test Data - Matching Python GEPA Test Cases
;; =============================================================================

(def simple-pareto-front
  "Simple Pareto front with 3 instances and 3 candidates.
   Candidate 0 is best at instance 0
   Candidate 1 is best at instance 1
   Candidate 2 is best at instance 2"
  {0 #{0}
   1 #{1}
   2 #{2}})

(def overlapping-pareto-front
  "Pareto front with overlapping best-at sets.
   Candidate 0 is best at instances 0 and 1
   Candidate 1 is best at instances 1 and 2
   Candidate 2 is best at instance 2 only"
  {0 #{0}
   1 #{0 1}
   2 #{1 2}})

(def dominated-candidate-front
  "Pareto front where candidate 2 is dominated.
   Candidate 0: best at instances 0, 1, 2
   Candidate 1: best at instances 0, 1, 2
   Candidate 2: best at instances 0, 1 (subset - dominated)"
  {0 #{0 1 2}
   1 #{0 1 2}
   2 #{0 1}})


(deftest is-dominated-test
  (testing "Candidate with unique best-at is not dominated"
    (is (false? (logic/is-dominated? 0 #{1 2} simple-pareto-front)))
    (is (false? (logic/is-dominated? 1 #{0 2} simple-pareto-front)))
    (is (false? (logic/is-dominated? 2 #{0 1} simple-pareto-front))))

  (testing "Candidate is dominated when all its fronts have dominators"
    (is (true? (logic/is-dominated? 2 #{0 1} dominated-candidate-front))))

  (testing "Candidate not in any front is vacuously dominated"
    (is (true? (logic/is-dominated? 99 #{0 1 2} simple-pareto-front))))

  (testing "Candidate with overlapping coverage"
    (is (false? (logic/is-dominated? 0 #{1 2} overlapping-pareto-front)))
    (is (true? (logic/is-dominated? 2 #{0 1} overlapping-pareto-front)))))

;; =============================================================================
;; remove-dominated-programs Tests
;; =============================================================================

(deftest remove-dominated-programs-test
  (testing "Simple front - no candidates dominated"
    (let [result (logic/remove-dominated-programs simple-pareto-front)]
      (is (= #{0} (get result 0)))
      (is (= #{1} (get result 1)))
      (is (= #{2} (get result 2)))))

  (testing "Front with dominated candidate"
    (let [scores [1.0 2.0 0.5]
          result (logic/remove-dominated-programs dominated-candidate-front scores)]
      (is (not (contains? (get result 0) 2)))
      (is (contains? (get result 0) 1))))

  (testing "Overlapping front removes dominated"
    (let [scores [1.5 1.0 0.5]
          result (logic/remove-dominated-programs overlapping-pareto-front scores)]
      (is (not (contains? (get result 2) 2)))
      (is (= #{0} (get result 0))))))

;; =============================================================================
;; find-dominator-programs Tests
;; =============================================================================

(deftest find-dominator-programs-test
  (testing "All candidates are dominators in simple front"
    (let [scores [1.0 1.0 1.0]
          result (logic/find-dominator-programs simple-pareto-front scores)]
      (is (= #{0 1 2} (set result)))))

  (testing "Dominated candidate excluded"
    (let [scores [1.0 1.0 0.5]
          result (logic/find-dominator-programs dominated-candidate-front scores)]
      (is (not (contains? (set result) 2)))
      (is (or (contains? (set result) 0)
              (contains? (set result) 1))))))

;; =============================================================================
;; select-program-candidate-from-pareto-front Tests
;; =============================================================================

(deftest select-program-candidate-from-pareto-front-test
  (testing "Selection returns valid dominator"
    (let [scores [1.0 1.0 1.0]
          rng (fn [n] (mod 42 n))
          result (logic/select-program-candidate-from-pareto-front
                   simple-pareto-front scores rng)]
      (is (contains? #{0 1 2} result))))

  (testing "Selection weighted by frequency"
    (let [weighted-front {0 #{0}
                          1 #{0}
                          2 #{0 1}}
          scores [1.0 1.0]
          selections (for [seed (range 100)]
                       (let [rng (fn [n] (mod seed n))]
                         (logic/select-program-candidate-from-pareto-front
                           weighted-front scores rng)))
          freq-0 (count (filter #(= 0 %) selections))
          freq-1 (count (filter #(= 1 %) selections))]
      (is (> freq-0 freq-1) "Higher-frequency candidate should be selected more often"))))

;; =============================================================================
;; idxmax Tests
;; =============================================================================

(deftest idxmax-test
  (testing "Returns index of maximum value"
    (is (= 0 (logic/idxmax [5.0 3.0 2.0 1.0])))
    (is (= 2 (logic/idxmax [1.0 2.0 5.0 3.0])))
    (is (= 3 (logic/idxmax [1.0 2.0 3.0 4.0]))))

  (testing "Returns first occurrence for ties"
    (is (= 0 (logic/idxmax [5.0 5.0 3.0])))
    (is (= 1 (logic/idxmax [3.0 5.0 5.0])))))

;; =============================================================================
;; select-component-round-robin Tests
;; =============================================================================

(deftest select-component-round-robin-test
  (testing "Cycles through components"
    (let [component-names ["instruction" "context" "format"]
          state0 {}

          {:keys [component new-state]}
          (logic/select-component-round-robin 0 component-names state0)]
      (is (= "instruction" component))
      (is (= {0 1} new-state))

      (let [{:keys [component new-state]}
            (logic/select-component-round-robin 0 component-names new-state)]
        (is (= "context" component))
        (is (= {0 2} new-state))

        (let [{:keys [component new-state]}
              (logic/select-component-round-robin 0 component-names new-state)]
          (is (= "format" component))
          (is (= {0 0} new-state))

          (let [{:keys [component]}
                (logic/select-component-round-robin 0 component-names new-state)]
            (is (= "instruction" component)))))))

  (testing "Independent state per candidate"
    (let [component-names ["a" "b" "c"]
          state {}

          {c1 :component state :new-state}
          (logic/select-component-round-robin 0 component-names state)

          {c2 :component state :new-state}
          (logic/select-component-round-robin 1 component-names state)]

      (is (= "a" c1))
      (is (= "a" c2))

      (is (= {0 1, 1 1} state)))))

;; =============================================================================
;; Pareto Front Computation Tests
;; =============================================================================

(deftest compute-pareto-front-mapping-test
  (testing "Computes correct best-at mapping"
    (let [candidates [{:candidate-id :a :scores [1.0 0.5 0.8]}
                      {:candidate-id :b :scores [0.5 1.0 0.7]}
                      {:candidate-id :c :scores [0.8 0.6 1.0]}]
          result (logic/compute-pareto-front-mapping candidates)]
      (is (= #{:a} (get result 0)))
      (is (= #{:b} (get result 1)))
      (is (= #{:c} (get result 2)))))

  (testing "Handles ties correctly"
    (let [candidates [{:candidate-id :a :scores [1.0 0.5]}
                      {:candidate-id :b :scores [1.0 0.5]}]
          result (logic/compute-pareto-front-mapping candidates)]
      (is (= #{:a :b} (get result 0)))
      (is (= #{:a :b} (get result 1))))))

;; =============================================================================
;; Merge Algorithm Parity Tests
;; =============================================================================

(deftest does-triplet-have-desirable-predictors-test
  (testing "Finds desirable predictors when one descendant diverged"
    (let [candidates [{"pred1" "original" "pred2" "original"}
                      {"pred1" "improved" "pred2" "original"}
                      {"pred1" "original" "pred2" "improved"}]]
      (is (true? (logic/does-triplet-have-desirable-predictors? candidates 0 1 2)))))

  (testing "No desirable predictors when both changed same predictor"
    (let [candidates [{"pred1" "original" "pred2" "original"}
                      {"pred1" "changed1" "pred2" "original"}
                      {"pred1" "changed2" "pred2" "original"}]]
      (is (false? (logic/does-triplet-have-desirable-predictors? candidates 0 1 2)))))

  (testing "No desirable predictors when both match ancestor"
    (let [candidates [{"pred1" "original" "pred2" "original"}
                      {"pred1" "original" "pred2" "original"}
                      {"pred1" "original" "pred2" "original"}]]
      (is (false? (logic/does-triplet-have-desirable-predictors? candidates 0 1 2))))))

(deftest get-ancestors-test
  (testing "Finds direct parents"
    (let [parent-list [[nil]
                       [0]
                       [0]
                       [1]
                       [2]]]
      (is (= #{0} (logic/get-ancestors 1 parent-list #{})))
      (is (= #{0} (logic/get-ancestors 2 parent-list #{})))
      (is (= #{0 1} (logic/get-ancestors 3 parent-list #{})))))

  (testing "Finds all ancestors in chain"
    (let [parent-list [[nil]
                       [0]
                       [1]
                       [2]]]
      (is (= #{0 1 2} (logic/get-ancestors 3 parent-list #{}))))))

(deftest should-accept-merge-test
  (testing "Accepts when merged score >= max parent"
    (let [new-scores [0.8 0.9 0.7]
          id1-scores [0.7 0.8 0.6]
          id2-scores [0.6 0.9 0.5]]
      (is (true? (logic/should-accept-merge? new-scores id1-scores id2-scores)))))

  (testing "Accepts when exactly equal to max parent"
    (let [new-scores [0.7 0.8 0.6]
          id1-scores [0.7 0.8 0.6]
          id2-scores [0.5 0.5 0.5]]
      (is (true? (logic/should-accept-merge? new-scores id1-scores id2-scores)))))

  (testing "Rejects when below max parent"
    (let [new-scores [0.5 0.5 0.5]
          id1-scores [0.7 0.8 0.6]
          id2-scores [0.6 0.9 0.5]]
      (is (false? (logic/should-accept-merge? new-scores id1-scores id2-scores))))))

;; =============================================================================
;; Selection Strategy Tests
;; =============================================================================

(deftest select-candidate-best-test
  (testing "Selects candidate with highest aggregate score"
    (is (= 0 (logic/select-candidate-best [5.0 3.0 2.0])))
    (is (= 2 (logic/select-candidate-best [2.0 3.0 5.0])))))

(deftest select-candidate-epsilon-greedy-test
  (testing "Exploits when random value >= epsilon"
    (let [scores [3.0 5.0 2.0]
          rng (constantly 0.99)]
      (is (= 1 (logic/select-candidate-epsilon-greedy scores 0.5 rng)))))

  (testing "Explores when random value < epsilon"
    (let [scores [3.0 5.0 2.0]
          call-count (atom 0)
          rng (fn [& args]
                (swap! call-count inc)
                (if (= 1 @call-count)
                  0.1
                  1))]
      (let [result (logic/select-candidate-epsilon-greedy scores 0.5 rng)]
        (is (contains? #{0 1 2} result))))))

;; =============================================================================
;; Diversity Metrics Tests
;; =============================================================================

(deftest frontier-diversity-test
  (testing "Counts unique frontier members"
    (is (= 3 (logic/frontier-diversity simple-pareto-front)))
    (is (= 3 (logic/frontier-diversity overlapping-pareto-front)))))

(deftest dominates-test
  (testing "A dominates B when better on all and strictly better on one"
    (is (true? (logic/dominates? [1.0 1.0 1.0] [0.9 0.9 0.9])))
    (is (true? (logic/dominates? [1.0 0.9 0.9] [0.9 0.9 0.9]))))

  (testing "A does not dominate B when worse on any"
    (is (false? (logic/dominates? [1.0 0.8 1.0] [0.9 0.9 0.9]))))

  (testing "Equal scores - no domination"
    (is (not (logic/dominates? [1.0 1.0 1.0] [1.0 1.0 1.0])))))
