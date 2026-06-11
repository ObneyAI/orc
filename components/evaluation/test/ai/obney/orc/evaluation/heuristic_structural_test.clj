(ns ai.obney.orc.evaluation.heuristic-structural-test
  "Gap-2 RED#1: structural heuristic extracted to a public function in
   evaluation. Tests the pure-function behavior against known tree
   shapes. The function preserves the original scoring logic from the
   retiring RLM Rolling Judge processor — just moved + made public."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.evaluation.core.heuristic-structural :as hs]))

;; =============================================================================
;; RED#1 — Public evaluate-tree-structure
;; =============================================================================

(deftest evaluates-excellent-tree-shape
  (testing "sequence + map-each + final → excellent score (>= 0.5)"
    (let [tree-dsl [:sequence
                    [:chunk-document {:from :doc :into :chunks}]
                    [:map-each {:from :chunks :as :chunk :into :results}
                     [:llm {:reads [:chunk] :writes [:result]}]]
                    [:aggregate {:from :results :writes [:out]}]
                    [:final {:keys [:out]}]]
          {:keys [score feedback dimensions]} (hs/evaluate-tree-structure tree-dsl)]
      (is (number? score) "Score is numeric")
      (is (and (>= score 0.0) (<= score 1.0)) "Score in [0.0, 1.0]")
      (is (>= score 0.5) "Excellent tree shape gets above-baseline score")
      (is (string? feedback) "Feedback is a string")
      (is (vector? dimensions) "Dimensions is a vector")
      (is (= 2 (count dimensions)) "Two dimensions reported (structure + decomposition)"))))

(deftest evaluates-basic-sequence
  (testing "sequence + final but no map-each → middling score"
    (let [tree-dsl [:sequence
                    [:llm {:reads [:doc] :writes [:out]}]
                    [:final {:keys [:out]}]]
          {:keys [score feedback]} (hs/evaluate-tree-structure tree-dsl)]
      (is (and (>= score 0.0) (<= score 1.0)))
      (is (< score 0.5) "Tree without map-each scores lower than 0.5")
      (is (string? feedback)))))

(deftest evaluates-malformed-tree
  (testing "Non-vector input → low score and clear feedback"
    (let [{:keys [score feedback]} (hs/evaluate-tree-structure :not-a-tree)]
      (is (and (>= score 0.0) (<= score 1.0)))
      (is (< score 0.3) "Malformed tree gets a low score")
      (is (string? feedback)))))

(deftest dimensions-shape
  (testing "Each dimension has :name :weight :score :feedback fields"
    (let [tree-dsl [:sequence [:llm {}] [:final {}]]
          {:keys [dimensions]} (hs/evaluate-tree-structure tree-dsl)]
      (doseq [d dimensions]
        (is (string? (:name d)))
        (is (number? (:weight d)))
        (is (number? (:score d)))
        (is (string? (:feedback d)))))))
