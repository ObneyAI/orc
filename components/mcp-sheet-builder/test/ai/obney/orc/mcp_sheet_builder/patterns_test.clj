(ns ai.obney.orc.mcp-sheet-builder.patterns-test
  "Tests for pattern selection."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.mcp-sheet-builder.core.patterns :as patterns]))

;; ============================================================================
;; Sample Test Data
;; ============================================================================

(def search-tool
  {:name "search"
   :capabilities #{:search}
   :category :data-access})

(def extract-tool
  {:name "extract"
   :capabilities #{:retrieval}
   :category :data-access})

(def generate-tool
  {:name "generate"
   :capabilities #{:generate}
   :category :side-effect})

(def validate-tool
  {:name "validate"
   :capabilities #{:validate}
   :category :transformation})

(def create-tool
  {:name "create"
   :capabilities #{:create}
   :category :data-mutation})

;; ============================================================================
;; Pattern Selection Tests
;; ============================================================================

(deftest select-patterns-returns-vector
  (testing "returns a vector of patterns"
    (let [result (patterns/select-patterns [search-tool extract-tool] [] {})]
      (is (vector? result)))))

(deftest select-patterns-returns-ranked-list
  (testing "returns patterns with confidence scores"
    (let [tools [search-tool extract-tool]
          rels [{:type :sequential :from "search" :to "extract"}]
          result (patterns/select-patterns tools rels {})]
      (is (seq result))
      (is (every? :confidence result))
      (is (every? :pattern result)))))

(deftest select-patterns-sorted-by-confidence
  (testing "patterns are sorted by confidence descending"
    (let [tools [search-tool extract-tool generate-tool]
          rels [{:type :sequential :from "search" :to "extract"}]
          result (patterns/select-patterns tools rels {})]
      (when (> (count result) 1)
        (is (>= (:confidence (first result))
                (:confidence (second result))))))))

;; ============================================================================
;; Pattern-Specific Detection Tests
;; ============================================================================

(deftest select-patterns-research-compilation
  (testing "selects research-compilation for search+retrieval tools"
    (let [tools [search-tool extract-tool]
          rels []
          result (patterns/select-patterns tools rels {})]
      (is (some #(= :research-compilation (:pattern %)) result)))))

(deftest select-patterns-sequential-pipeline
  (testing "selects sequential-pipeline for sequential relationships"
    (let [tools [search-tool extract-tool]
          rels [{:type :sequential :from "search" :to "extract"}]
          result (patterns/select-patterns tools rels {})]
      (is (some #(= :sequential-pipeline (:pattern %)) result)))))

(deftest select-patterns-parallel-fan-out
  (testing "selects parallel-fan-out for parallel relationships"
    (let [tools [search-tool extract-tool generate-tool]
          rels [{:type :parallel :from "search" :to "extract"}]
          result (patterns/select-patterns tools rels {})]
      (is (some #(= :parallel-fan-out (:pattern %)) result)))))

;; ============================================================================
;; Minimum Tool Count Tests
;; ============================================================================

(deftest select-patterns-respects-min-tools
  (testing "respects minimum tool requirements"
    (let [tools [search-tool]
          rels []
          result (patterns/select-patterns tools rels {})]
      (is (not (some #(and (= :sequential-pipeline (:pattern %))
                          (>= (:confidence %) 0.5))
                    result))))))

(deftest select-patterns-coordinator-needs-three-tools
  (testing "coordinator-dispatcher requires at least 3 tools"
    (let [two-tools [search-tool extract-tool]
          three-tools [search-tool extract-tool generate-tool]
          rels [{:type :alternative :from "search" :to "extract"}]
          result-2 (patterns/select-patterns two-tools rels {})
          result-3 (patterns/select-patterns three-tools rels {})]
      (let [coord-2 (first (filter #(= :coordinator-dispatcher (:pattern %)) result-2))
            coord-3 (first (filter #(= :coordinator-dispatcher (:pattern %)) result-3))]
        (when coord-2
          (is (< (:confidence coord-2) 0.5)))))))

;; ============================================================================
;; Pattern Descriptions Tests
;; ============================================================================

(deftest select-patterns-includes-description
  (testing "patterns include descriptions"
    (let [tools [search-tool extract-tool]
          rels [{:type :sequential :from "search" :to "extract"}]
          result (patterns/select-patterns tools rels {})]
      (is (every? :description result)))))

(deftest select-patterns-includes-orc-nodes
  (testing "patterns include ORC node types"
    (let [tools [search-tool extract-tool]
          rels [{:type :sequential :from "search" :to "extract"}]
          result (patterns/select-patterns tools rels {})]
      (is (every? :orc-nodes result)))))

;; ============================================================================
;; Edge Cases
;; ============================================================================

(deftest select-patterns-empty-tools
  (testing "handles empty tool list"
    (let [result (patterns/select-patterns [] [] {})]
      (is (vector? result))
      (is (empty? result)))))

(deftest select-patterns-no-relationships
  (testing "works without relationships"
    (let [tools [search-tool extract-tool]
          result (patterns/select-patterns tools [] {})]
      (is (seq result)))))
