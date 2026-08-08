(ns ai.obney.orc.mcp-sheet-builder.validator-test
  "Tests for sheet validation."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.mcp-sheet-builder.core.validator :as v]))

;; ============================================================================
;; Valid Sheet Tests
;; ============================================================================

(deftest validate-sheet-valid-simple
  (testing "valid sheet passes validation"
    (let [sheet {:workflow '(sheet/workflow "test"
                              (sheet/blackboard {:query :string})
                              (sheet/sequence "main"
                                (sheet/code "call-search"
                                  :fn "search-fn"
                                  :reads [:query]
                                  :writes [:search-result])))
                 :blackboard {:query :string
                              :search-result :string}}
          result (v/validate-sheet sheet)]
      (is (:valid? result))
      (is (empty? (:errors result))))))

(deftest validate-sheet-empty-workflow
  (testing "minimal workflow passes"
    (let [sheet {:workflow '(sheet/workflow "test"
                              (sheet/blackboard {}))
                 :blackboard {}}
          result (v/validate-sheet sheet)]
      (is (:valid? result)))))

;; ============================================================================
;; Invalid Sheet Tests - Missing Blackboard Keys
;; ============================================================================

(deftest validate-sheet-missing-read-key
  (testing "missing read key fails validation"
    (let [sheet {:workflow '(sheet/workflow "test"
                              (sheet/sequence "main"
                                (sheet/code "call-search"
                                  :fn "search-fn"
                                  :reads [:query]
                                  :writes [:result])))
                 :blackboard {:result :string}}  ;; Missing :query
          result (v/validate-sheet sheet)]
      (is (not (:valid? result)))
      (is (seq (:errors result))))))

(deftest validate-sheet-missing-write-key
  (testing "missing write key fails validation"
    (let [sheet {:workflow '(sheet/workflow "test"
                              (sheet/sequence "main"
                                (sheet/code "call-search"
                                  :fn "search-fn"
                                  :reads [:query]
                                  :writes [:result])))
                 :blackboard {:query :string}}  ;; Missing :result
          result (v/validate-sheet sheet)]
      (is (not (:valid? result)))
      (is (seq (:errors result))))))

;; ============================================================================
;; LLM Node Validation Tests
;; ============================================================================

(deftest validate-sheet-llm-node
  (testing "validates LLM nodes correctly"
    (let [sheet {:workflow '(sheet/workflow "test"
                              (sheet/sequence "main"
                                (sheet/llm "analyze"
                                  :instruction "Analyze the input"
                                  :reads [:input]
                                  :writes [:output])))
                 :blackboard {:input :string
                              :output :string}}
          result (v/validate-sheet sheet)]
      (is (:valid? result)))))

;; ============================================================================
;; Nested Structure Tests
;; ============================================================================

(deftest validate-sheet-nested-sequence
  (testing "validates nested sequences"
    (let [sheet {:workflow '(sheet/workflow "test"
                              (sheet/sequence "outer"
                                (sheet/sequence "inner"
                                  (sheet/code "step1"
                                    :fn "fn1"
                                    :reads [:a]
                                    :writes [:b])
                                  (sheet/code "step2"
                                    :fn "fn2"
                                    :reads [:b]
                                    :writes [:c]))))
                 :blackboard {:a :string
                              :b :string
                              :c :string}}
          result (v/validate-sheet sheet)]
      (is (:valid? result)))))

(deftest validate-sheet-parallel-node
  (testing "validates parallel nodes"
    (let [sheet {:workflow '(sheet/workflow "test"
                              (sheet/parallel "fan-out"
                                (sheet/code "branch1"
                                  :fn "fn1"
                                  :reads [:input]
                                  :writes [:out1])
                                (sheet/code "branch2"
                                  :fn "fn2"
                                  :reads [:input]
                                  :writes [:out2])))
                 :blackboard {:input :string
                              :out1 :string
                              :out2 :string}}
          result (v/validate-sheet sheet)]
      (is (:valid? result)))))

;; ============================================================================
;; validate-and-explain Tests
;; ============================================================================

(deftest validate-and-explain-valid
  (testing "explain provides summary for valid sheet"
    (let [sheet {:workflow '(sheet/workflow "test")
                 :blackboard {}}
          result (v/validate-and-explain sheet)]
      (is (contains? result :summary))
      (is (= "Valid" (:summary result))))))

(deftest validate-and-explain-invalid
  (testing "explain provides error count for invalid sheet"
    (let [sheet {:workflow '(sheet/sequence "main"
                              (sheet/code "step"
                                :reads [:missing-key]
                                :writes []))
                 :blackboard {}}
          result (v/validate-and-explain sheet)]
      (is (contains? result :summary))
      (is (clojure.string/includes? (:summary result) "Invalid")))))

(deftest validate-and-explain-warnings
  (testing "explain reports warnings"
    (let [sheet {:workflow '(sheet/workflow "test"
                              (sheet/code "call-unknown"
                                :fn "some-unknown-fn"
                                :reads []
                                :writes []))
                 :blackboard {}
                 :tools []}
          result (v/validate-and-explain sheet)]
      (is (contains? result :warnings)))))

;; ============================================================================
;; Edge Cases
;; ============================================================================

(deftest validate-sheet-nil-workflow
  (testing "handles nil workflow"
    (let [sheet {:workflow nil
                 :blackboard {}}
          result (v/validate-sheet sheet)]
      ;; Should not crash
      (is (map? result)))))

(deftest validate-sheet-nil-blackboard
  (testing "handles nil blackboard"
    (let [sheet {:workflow '(sheet/workflow "test")
                 :blackboard nil}
          result (v/validate-sheet sheet)]
      ;; Should not crash
      (is (map? result)))))
