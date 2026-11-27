(ns ai.obney.orc.mcp-sheet-builder.interface-test
  "Integration tests for the MCP Sheet Builder interface."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [ai.obney.orc.mcp-sheet-builder.core.analyzer :as analyzer]
            [ai.obney.orc.mcp-sheet-builder.interface :as msb]))

;; ============================================================================
;; Sample Test Data - Simulating MCP Tool Definitions
;; ============================================================================

(def sample-tavily-tools
  [{:name "tavily_search"
    :description "Search the web for information using Tavily"
    :inputSchema {"type" "object"
                  "properties" {"query" {"type" "string"
                                         "description" "Search query"}}
                  "required" ["query"]}
    :outputSchema {"type" "string"}}
   {:name "tavily_extract"
    :description "Extract content from URLs"
    :inputSchema {"type" "object"
                  "properties" {"urls" {"type" "array"
                                        "items" {"type" "string"}
                                        "description" "URLs to extract from"}}
                  "required" ["urls"]}
    :outputSchema {"type" "string"}}
   {:name "tavily_qna"
    :description "Answer questions based on web search"
    :inputSchema {"type" "object"
                  "properties" {"query" {"type" "string"}}
                  "required" ["query"]}
    :outputSchema {"type" "string"}}])

;; ============================================================================
;; Schema Conversion Integration Tests
;; ============================================================================

(deftest json-schema-malli-conversion-integration
  (testing "converts MCP tool schemas to Malli"
    (let [tool-schema (get-in (first sample-tavily-tools) [:inputSchema])
          result (msb/json-schema->malli tool-schema)]
      (is (vector? result))
      (is (= :map (first result))))))

;; ============================================================================
;; Analysis Integration Tests
;; ============================================================================

(deftest analyze-tools-directly-test
  (testing "analyzes tools directly using core analyzer"
    (let [analyzed (analyzer/analyze-tools sample-tavily-tools {})]
      (is (seq analyzed))
      (is (every? :capabilities analyzed))
      (is (every? :category analyzed)))))


(def mock-analysis
  {:tools [{:name "tavily_search"
            :description "Search the web"
            :capabilities #{:search}
            :category :data-access
            :input-schema {"type" "object"
                           "properties" {"query" {"type" "string"}}}
            :output-schema {"type" "string"}
            :malli-input [:map [:query :string]]
            :malli-output :string}
           {:name "tavily_extract"
            :description "Extract content from URLs"
            :capabilities #{:retrieval}
            :category :data-access
            :input-schema {"type" "object"
                           "properties" {"urls" {"type" "array"
                                                 "items" {"type" "string"}}}}
            :output-schema {"type" "string"}
            :malli-input [:map [:urls [:vector :string]]]
            :malli-output :string}]
   :relationships [{:type :sequential :from "tavily_search" :to "tavily_extract"}]
   :patterns [{:pattern :research-compilation
               :confidence 0.9
               :description "Gather from multiple sources, synthesize"
               :orc-nodes [:parallel :map-each :sequence]}
              {:pattern :sequential-pipeline
               :confidence 0.8
               :description "Linear data flow through tool chain"
               :orc-nodes [:sequence]}]})

(deftest generate-sheet-integration
  (testing "generates sheet from mock analysis"
    (let [result (msb/generate-sheet mock-analysis {:pattern :research-compilation})]
      (is (map? result))
      (is (contains? result :workflow))
      (is (contains? result :blackboard)))))

(deftest generate-sheet-sequential-integration
  (testing "generates sequential pipeline sheet"
    (let [result (msb/generate-sheet mock-analysis {:pattern :sequential-pipeline})]
      (is (map? result))
      (is (contains? result :workflow)))))

;; ============================================================================
;; Validation Integration Tests
;; ============================================================================

(deftest validate-sheet-integration
  (testing "validates generated sheet"
    (let [generated (msb/generate-sheet mock-analysis {:pattern :sequential-pipeline})
          validation (msb/validate-sheet generated)]
      (is (map? validation))
      (is (contains? validation :valid?))
      (is (contains? validation :errors))
      (is (contains? validation :warnings)))))


(deftest full-workflow-mock-test
  (testing "full workflow: analyze -> generate -> validate"
    (let [
          sheet (msb/generate-sheet mock-analysis {:pattern :research-compilation})
          validation (msb/validate-sheet sheet)]
      (is (map? sheet))
      (is (contains? sheet :workflow))
      (is (map? validation)))))

;; ============================================================================
;; Pattern Selection Tests
;; ============================================================================

(deftest pattern-auto-selection-test
  (testing "automatically selects highest confidence pattern"
    (let [result (msb/generate-sheet mock-analysis {:pattern :auto})]
      (is (map? result)))))

;; ============================================================================
;; Edge Case Tests
;; ============================================================================

(deftest generate-sheet-empty-analysis
  (testing "handles empty analysis gracefully"
    (let [empty-analysis {:tools []
                          :relationships []
                          :patterns []}
          result (msb/generate-sheet empty-analysis {:pattern :sequential-pipeline})]
      (is (map? result)))))

(deftest validate-empty-sheet
  (testing "validates empty sheet"
    (let [result (msb/validate-sheet {:workflow nil :blackboard {}})]
      (is (map? result)))))

;; ============================================================================
;; Schema Conversion Edge Cases
;; ============================================================================

(deftest nested-object-schema-conversion
  (testing "converts deeply nested object schemas"
    (let [nested-schema {"type" "object"
                         "properties" {"search_config" {"type" "object"
                                                        "properties" {"query" {"type" "string"}
                                                                      "options" {"type" "object"
                                                                                 "properties" {"limit" {"type" "integer"}}}}}}}
          result (msb/json-schema->malli nested-schema)]
      (is (vector? result))
      (is (= :map (first result))))))

(deftest array-of-objects-schema-conversion
  (testing "converts array of objects schema"
    (let [array-schema {"type" "array"
                        "items" {"type" "object"
                                 "properties" {"url" {"type" "string"}}}}
          result (msb/json-schema->malli array-schema)]
      (is (vector? result))
      (is (= :vector (first result))))))

;; ============================================================================
;; Portable Export Tests
;; ============================================================================

(def sample-executor-defs
  "Sample executor definitions for testing export functions."
  [{:tool-name "searchDocs"
    :input-schema {"type" "object"
                   "properties" {"query" {"type" "string"
                                          "description" "Search query"}}
                   "required" ["query"]}}
   {:tool-name "getPage"
    :input-schema {"type" "object"
                   "properties" {"path" {"type" "string"
                                         "description" "Page path"}}}}])

(def sample-workflow-data
  "Sample workflow data for testing portable sheet export.
   Note: :fn values must contain tool name patterns for replacement to work.
   This matches how the MCP generator creates workflows."
  {:workflow-name "test-research"
   :blackboard-schema {:query :string
                       :result :string}
   :root-node {:node-type :sequence
               :children [{:node-type :leaf
                           :executor :code
                           :fn "mcp.executors.dynamic.t1234/call-searchDocs"
                           :reads [:query]
                           :writes [:result]}]}})

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture
  "Creates a temporary directory for each test, cleans up afterward."
  [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "mcp-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (binding [*test-dir* (.getAbsolutePath dir)]
      (try
        (f)
        (finally
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(use-fixtures :each temp-dir-fixture)


(deftest export-executors-generates-valid-code-test
  (testing "generates valid Clojure namespace with executors"
    (let [filepath (str *test-dir* "/test-executors.clj")]
      (msb/export-executors! sample-executor-defs filepath
                             :namespace "test-executors")
      (let [content (slurp filepath)]
        (is (read-string (str "(do " content ")"))
            "Generated code should be valid Clojure")
        (is (re-find #"\(ns test-executors" content)
            "Should have namespace declaration")
        (is (re-find #"defn call-searchDocs" content)
            "Should have call-searchDocs function")
        (is (re-find #"defn call-getPage" content)
            "Should have call-getPage function")
        (is (re-find #"def executors" content)
            "Should have executors registry map")))))

(deftest export-executors-creates-directories-test
  (testing "creates parent directories if they don't exist"
    (let [filepath (str *test-dir* "/nested/deep/path/executors.clj")]
      (msb/export-executors! sample-executor-defs filepath)
      (is (.exists (io/file filepath))
          "File should exist in nested directory"))))

(deftest export-executors-derives-namespace-from-path-test
  (testing "derives namespace from filepath when not explicitly provided"
    (let [filepath (str *test-dir* "/my-cool-executors.clj")]
      (msb/export-executors! sample-executor-defs filepath)
      (let [content (slurp filepath)]
        (is (re-find #"\(ns my-cool-executors" content)
            "Should derive namespace from filename")))))

(deftest export-executors-handles-special-characters-test
  (testing "generates functions for tools with special characters in names"
    (let [filepath (str *test-dir* "/special-executors.clj")
          executor-defs [{:tool-name "mcp.server@tool"
                          :input-schema {"type" "object"
                                         "properties" {}}}]]
      (msb/export-executors! executor-defs filepath)
      (let [content (slurp filepath)]
        (is (re-find #"defn call-mcp-server-tool" content)
            "Should sanitize special characters in function name")))))

(deftest export-executors-includes-mcp-client-require-test
  (testing "includes required dependencies in namespace"
    (let [filepath (str *test-dir* "/deps-test-executors.clj")]
      (msb/export-executors! sample-executor-defs filepath)
      (let [content (slurp filepath)]
        (is (re-find #"mcp-sheet-builder\.core\.executors" content)
            "Should require the shared tool-invocation namespace")))))

(deftest export-executors-generates-correct-function-signatures-test
  (testing "generated functions have correct ORC executor signature"
    (let [filepath (str *test-dir* "/sig-test-executors.clj")]
      (msb/export-executors! sample-executor-defs filepath)
      (let [content (slurp filepath)]
        (is (re-find #"\[invocation\]" content)
            "Should have standard executor signature")
        (is (re-find #"executors/invoke-tool invocation" content)
            "Should invoke the tool through the shared seam")
        (is (re-find #"get inputs :query" content)
            "Should extract query parameter from inputs")))))


(deftest export-portable-sheet-creates-both-files-test
  (testing "creates both executors and workflow files"
    (let [dir *test-dir*
          name "my-research"]
      (msb/export-portable-sheet! sample-workflow-data
                                  sample-executor-defs
                                  dir
                                  name)
      (is (.exists (io/file dir (str name "-executors.clj")))
          "Should create executors file")
      (is (.exists (io/file dir (str name ".clj")))
          "Should create workflow file"))))

(deftest export-portable-sheet-workflow-requires-executors-test
  (testing "workflow file requires executors namespace"
    (let [dir *test-dir*
          name "req-test"]
      (msb/export-portable-sheet! sample-workflow-data
                                  sample-executor-defs
                                  dir
                                  name)
      (let [workflow-content (slurp (io/file dir (str name ".clj")))]
        (is (re-find #"req-test-executors" workflow-content)
            "Workflow should require executors namespace")))))

(deftest export-portable-sheet-has-build-function-test
  (testing "workflow file has build! convenience function"
    (let [dir *test-dir*
          name "build-test"]
      (msb/export-portable-sheet! sample-workflow-data
                                  sample-executor-defs
                                  dir
                                  name)
      (let [workflow-content (slurp (io/file dir (str name ".clj")))]
        (is (re-find #"defn build!" workflow-content)
            "Workflow should have build! function")
        (is (re-find #"sheet/build-workflow!" workflow-content)
            "build! should call sheet/build-workflow!")))))

(deftest export-portable-sheet-updates-fn-references-test
  (testing "updates :fn references to point to executors namespace"
    (let [dir *test-dir*
          name "ref-test"
          workflow-with-fn {:workflow-name "ref-test"
                            :blackboard-schema {:query :string}
                            :root-node {:node-type :leaf
                                        :executor :code
                                        :fn "mcp.executors.dynamic.t1234/call-searchDocs"
                                        :reads [:query]
                                        :writes [:result]}}]
      (msb/export-portable-sheet! workflow-with-fn
                                  sample-executor-defs
                                  dir
                                  name)
      (let [content (slurp (io/file dir (str name ".clj")))]
        (is (not (re-find #"mcp\.executors\.dynamic" content))
            "Should not have dynamic namespace reference")
        (is (re-find #"ref-test-executors/call-searchDocs" content)
            "Should reference exported executors namespace")))))

(deftest export-portable-sheet-generates-valid-clojure-test
  (testing "both exported files are valid Clojure"
    (let [dir *test-dir*
          name "valid-clj-test"]
      (msb/export-portable-sheet! sample-workflow-data
                                  sample-executor-defs
                                  dir
                                  name)
      (let [exec-content (slurp (io/file dir (str name "-executors.clj")))]
        (is (read-string (str "(do " exec-content ")"))
            "Executors file should be valid Clojure"))
      (let [wf-content (slurp (io/file dir (str name ".clj")))]
        (is (read-string (str "(do " wf-content ")"))
            "Workflow file should be valid Clojure")))))

;; ============================================================================
;; Edge Cases
;; ============================================================================

(deftest export-executors-empty-list-test
  (testing "handles empty executor list gracefully"
    (let [filepath (str *test-dir* "/empty-executors.clj")]
      (msb/export-executors! [] filepath)
      (is (.exists (io/file filepath))
          "Should create file even with empty executor list")
      (let [content (slurp filepath)]
        (is (re-find #"\(ns" content)
            "Should still have namespace declaration")
        (is (re-find #"def executors\s+\{\s*\}" content)
            "Should have empty executors map")))))

(deftest export-executors-no-params-tool-test
  (testing "handles tools with no parameters"
    (let [filepath (str *test-dir* "/no-params-executors.clj")
          executor-defs [{:tool-name "getOverview"
                          :input-schema {"type" "object"
                                         "properties" {}}}]]
      (msb/export-executors! executor-defs filepath)
      (let [content (slurp filepath)]
        (is (re-find #"defn call-getOverview" content)
            "Should generate function for parameterless tool")))))

(deftest export-portable-sheet-complex-workflow-test
  (testing "handles workflow with multiple code nodes"
    (let [dir *test-dir*
          name "complex-test"
          complex-workflow {:workflow-name "complex-test"
                            :blackboard-schema {:query :string
                                                :intermediate :string
                                                :result :string}
                            :root-node {:node-type :sequence
                                        :children [{:node-type :leaf
                                                    :executor :code
                                                    :fn "mcp.executors.dynamic.t1234/call-searchDocs"
                                                    :reads [:query]
                                                    :writes [:intermediate]}
                                                   {:node-type :leaf
                                                    :executor :code
                                                    :fn "mcp.executors.dynamic.t5678/call-getPage"
                                                    :reads [:intermediate]
                                                    :writes [:result]}]}}]
      (msb/export-portable-sheet! complex-workflow
                                  sample-executor-defs
                                  dir
                                  name)
      (let [content (slurp (io/file dir (str name ".clj")))]
        (is (re-find #"complex-test-executors/call-searchDocs" content)
            "Should update first fn reference")
        (is (re-find #"complex-test-executors/call-getPage" content)
            "Should update second fn reference")
        (is (not (re-find #"mcp\.executors\.dynamic" content))
            "Should not have dynamic namespace references")))))
