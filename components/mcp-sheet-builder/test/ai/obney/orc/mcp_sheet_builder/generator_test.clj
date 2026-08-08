(ns ai.obney.orc.mcp-sheet-builder.generator-test
  "Tests for ORC workflow generation."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.mcp-sheet-builder.core.generator :as gen]))

;; ============================================================================
;; Sample Test Data
;; ============================================================================

(def search-tool
  {:name "search"
   :description "Search the web"
   :capabilities #{:search}
   :category :data-access
   :input-schema {"type" "object"
                  "properties" {"query" {"type" "string"
                                         "description" "Search query"}}}
   :output-schema {"type" "string"}
   :malli-input [:map [:query :string]]
   :malli-output :string})

(def extract-tool
  {:name "extract"
   :description "Extract content from URL"
   :capabilities #{:retrieval}
   :category :data-access
   :input-schema {"type" "object"
                  "properties" {"url" {"type" "string"}}}
   :output-schema {"type" "string"}
   :malli-input [:map [:url :string]]
   :malli-output :string})

(def sample-analysis
  {:tools [search-tool extract-tool]
   :relationships [{:type :sequential :from "search" :to "extract"}]
   :patterns [{:pattern :research-compilation :confidence 0.9}
              {:pattern :sequential-pipeline :confidence 0.8}]})

;; ============================================================================
;; Blackboard Generation Tests
;; ============================================================================

(deftest generate-blackboard-schema-test
  (testing "generates blackboard schema from tools"
    (let [bb (gen/generate-blackboard-schema [search-tool])]
      (is (map? bb))
      ;; Should have input keys
      (is (contains? bb :query))
      ;; Should have result key
      (is (contains? bb :search-result)))))

(deftest generate-blackboard-schema-multiple-tools-test
  (testing "merges schemas from multiple tools"
    (let [bb (gen/generate-blackboard-schema [search-tool extract-tool])]
      (is (contains? bb :query))
      (is (contains? bb :url))
      (is (contains? bb :search-result))
      (is (contains? bb :extract-result)))))

;; ============================================================================
;; Sheet Generation Tests
;; ============================================================================

(deftest generate-sheet-creates-workflow
  (testing "generates valid workflow structure"
    (let [result (gen/generate-sheet sample-analysis {:pattern :research-compilation})]
      (is (map? result))
      (is (contains? result :workflow))
      (is (contains? result :blackboard)))))

(deftest generate-sheet-includes-code
  (testing "includes string representation"
    (let [result (gen/generate-sheet sample-analysis {:pattern :sequential-pipeline})]
      (is (contains? result :code))
      (is (string? (:code result))))))

;; ============================================================================
;; Relationship-Aware Sorting Tests
;; ============================================================================

(deftest topological-sort-no-relationships
  (testing "preserves order when no relationships"
    (let [tools [search-tool extract-tool]
          sorted (gen/topological-sort-by-relationships tools [])]
      (is (= (count tools) (count sorted))))))

(deftest topological-sort-with-sequential
  (testing "sorts by sequential relationships"
    (let [tools [extract-tool search-tool]  ;; Reversed order
          rels [{:type :sequential :from "search" :to "extract"}]
          sorted (gen/topological-sort-by-relationships tools rels)]
      ;; search should come before extract
      (is (= "search" (:name (first sorted)))))))

;; ============================================================================
;; Parallel Grouping Tests
;; ============================================================================

(deftest group-parallel-tools-no-relationships
  (testing "all tools parallel when no relationships"
    (let [result (gen/group-parallel-tools [search-tool extract-tool] [])]
      (is (= 2 (count (:parallel result))))
      (is (empty? (:sequential result))))))

(deftest group-parallel-tools-with-parallel-rel
  (testing "groups tools by parallel relationships"
    (let [rels [{:type :parallel :from "search" :to "extract"}]
          result (gen/group-parallel-tools [search-tool extract-tool] rels)]
      (is (= 2 (count (:parallel result)))))))

;; ============================================================================
;; Refinement Pair Detection Tests
;; ============================================================================

(def generate-tool
  {:name "generate"
   :capabilities #{:generate}})

(def validate-tool
  {:name "validate"
   :capabilities #{:validate}})

(deftest find-refinement-pairs-test
  (testing "finds generator-validator pairs"
    (let [rels [{:type :refinement :from "generate" :to "validate" :confidence 0.9}]
          pairs (gen/find-refinement-pairs [generate-tool validate-tool] rels)]
      (is (seq pairs))
      (is (= "generate" (:name (:generator (first pairs)))))
      (is (= "validate" (:name (:validator (first pairs))))))))

(deftest find-refinement-pairs-empty
  (testing "returns empty when no refinement relationships"
    (let [pairs (gen/find-refinement-pairs [search-tool extract-tool] [])]
      (is (empty? pairs)))))

;; ============================================================================
;; Pattern-Specific Generation Tests
;; ============================================================================

(deftest generate-sheet-sequential-pipeline
  (testing "generates sequential pipeline workflow"
    (let [result (gen/generate-sheet sample-analysis {:pattern :sequential-pipeline})]
      (is (map? result))
      ;; Workflow should contain sequence node
      (is (some? (:workflow result))))))

(deftest generate-sheet-research-compilation
  (testing "generates research compilation workflow"
    (let [result (gen/generate-sheet sample-analysis {:pattern :research-compilation})]
      (is (map? result))
      (is (some? (:workflow result))))))
