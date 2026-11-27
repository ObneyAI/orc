(ns ai.obney.orc.orc-service.metadata-test
  "Unit tests for tree metadata extraction.

   Tests the metadata module's ability to:
   - Classify node types (leaf -> llm/code, control nodes)
   - Extract structural metadata (validation, retry, parallel)
   - Infer capabilities from node types
   - Infer problem types from names
   - Merge user overrides with extracted metadata"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

;; =============================================================================
;; Access private functions for testing
;; =============================================================================

(def ^:private classify-node-type
  #'service-logic/classify-node-type)

;; =============================================================================
;; classify-node-type Tests
;; =============================================================================

(deftest classify-node-type-test
  (testing "classifies leaf nodes with :ai executor as :llm"
    (is (= :llm (classify-node-type {:type :leaf :executor :ai}))))

  (testing "classifies leaf nodes with :code executor as :code"
    (is (= :code (classify-node-type {:type :leaf :executor :code}))))

  (testing "classifies leaf nodes with no executor as :leaf"
    (is (= :leaf (classify-node-type {:type :leaf}))))

  (testing "passes through non-leaf node types unchanged"
    (is (= :sequence (classify-node-type {:type :sequence})))
    (is (= :parallel (classify-node-type {:type :parallel})))
    (is (= :fallback (classify-node-type {:type :fallback})))
    (is (= :map-each (classify-node-type {:type :map-each})))))

;; =============================================================================
;; infer-problem-types Tests
;; =============================================================================

(deftest infer-problem-types-classification-test
  (testing "detects Classification from node names"
    (let [nodes [{:name "classify-input"}]
          result (service-logic/infer-problem-types {} nodes [])]
      (is (some #{"problem:Classification"} result))))

  (testing "detects Classification from blackboard keys"
    (let [blackboard [{:key "category-label"}]
          result (service-logic/infer-problem-types {} [] blackboard)]
      (is (some #{"problem:Classification"} result)))))

(deftest infer-problem-types-scoring-test
  (testing "detects Scoring from node names"
    (let [nodes [{:name "score-candidate"}]
          result (service-logic/infer-problem-types {} nodes [])]
      (is (some #{"problem:Scoring"} result))))

  (testing "detects Scoring from rating/rank patterns"
    (let [nodes [{:name "rank-options"}]
          result (service-logic/infer-problem-types {} nodes [])]
      (is (some #{"problem:Scoring"} result)))))

(deftest infer-problem-types-extraction-test
  (testing "detects DataExtraction from extract patterns"
    (let [nodes [{:name "extract-fields"}]
          result (service-logic/infer-problem-types {} nodes [])]
      (is (some #{"problem:DataExtraction"} result))))

  (testing "detects DataExtraction from parse patterns"
    (let [blackboard [{:key "parsed-data"}]
          result (service-logic/infer-problem-types {} [] blackboard)]
      (is (some #{"problem:DataExtraction"} result)))))

(deftest infer-problem-types-summarization-test
  (testing "detects Summarization"
    (let [nodes [{:name "summarize-document"}]
          result (service-logic/infer-problem-types {} nodes [])]
      (is (some #{"problem:Summarization"} result)))))

(deftest infer-problem-types-generation-test
  (testing "detects Generation from generate patterns"
    (let [nodes [{:name "generate-response"}]
          result (service-logic/infer-problem-types {} nodes [])]
      (is (some #{"problem:Generation"} result))))

  (testing "detects Generation from create/write patterns"
    (let [blackboard [{:key "draft-content"}]
          result (service-logic/infer-problem-types {} [] blackboard)]
      (is (some #{"problem:Generation"} result)))))

(deftest infer-problem-types-qa-test
  (testing "detects QuestionAnswering"
    (let [nodes [{:name "answer-question"}]
          result (service-logic/infer-problem-types {} nodes [])]
      (is (some #{"problem:QuestionAnswering"} result)))))

(deftest infer-problem-types-transformation-test
  (testing "detects Transformation"
    (let [nodes [{:name "transform-data"}]
          result (service-logic/infer-problem-types {} nodes [])]
      (is (some #{"problem:Transformation"} result)))))

(deftest infer-problem-types-comparison-test
  (testing "detects Comparison"
    (let [nodes [{:name "compare-documents"}]
          result (service-logic/infer-problem-types {} nodes [])]
      (is (some #{"problem:Comparison"} result)))))

(deftest infer-problem-types-multiple-test
  (testing "detects multiple problem types from complex structure"
    (let [nodes [{:name "classify-and-score"}
                 {:name "generate-summary"}]
          blackboard [{:key "comparison-result"}]
          result (service-logic/infer-problem-types {} nodes blackboard)]
      (is (>= (count result) 3))
      (is (some #{"problem:Classification"} result))
      (is (some #{"problem:Scoring"} result))
      (is (some #{"problem:Comparison"} result)))))

(deftest infer-problem-types-empty-test
  (testing "returns empty vector when no patterns match"
    (let [nodes [{:name "process-data"}]
          result (service-logic/infer-problem-types {} nodes [])]
      (is (vector? result))
      (is (empty? result)))))

;; =============================================================================
;; merge-metadata Tests
;; =============================================================================

(deftest merge-metadata-override-problem-types-test
  (testing "overrides problem-types when provided"
    (let [extracted {:problem-types ["problem:Scoring"]
                     :description nil
                     :name "test-sheet"}
          overrides {:problem-types ["problem:Classification" "problem:Generation"]}
          result (service-logic/merge-metadata extracted overrides)]
      (is (= ["problem:Classification" "problem:Generation"]
             (:problem-types result))))))

(deftest merge-metadata-override-description-test
  (testing "overrides description when provided"
    (let [extracted {:problem-types []
                     :description nil
                     :name "test-sheet"}
          overrides {:description "Custom description for this workflow"}
          result (service-logic/merge-metadata extracted overrides)]
      (is (= "Custom description for this workflow"
             (:description result))))))

(deftest merge-metadata-preserves-extracted-test
  (testing "preserves extracted values when no override provided"
    (let [extracted {:problem-types ["problem:Scoring"]
                     :description nil
                     :name "test-sheet"
                     :node-count 5}
          overrides {}
          result (service-logic/merge-metadata extracted overrides)]
      (is (= ["problem:Scoring"] (:problem-types result)))
      (is (nil? (:description result)))
      (is (= "test-sheet" (:name result)))
      (is (= 5 (:node-count result))))))

(deftest merge-metadata-empty-problem-types-not-override-test
  (testing "empty problem-types override does NOT replace extracted"
    (let [extracted {:problem-types ["problem:Scoring"]
                     :description nil}
          overrides {:problem-types []}
          result (service-logic/merge-metadata extracted overrides)]
      (is (= ["problem:Scoring"] (:problem-types result))))))

(deftest merge-metadata-both-overrides-test
  (testing "applies both overrides together"
    (let [extracted {:problem-types []
                     :description nil
                     :name "original"}
          overrides {:problem-types ["problem:QA"]
                     :description "New description"}
          result (service-logic/merge-metadata extracted overrides)]
      (is (= ["problem:QA"] (:problem-types result)))
      (is (= "New description" (:description result)))
      (is (= "original" (:name result))))))
