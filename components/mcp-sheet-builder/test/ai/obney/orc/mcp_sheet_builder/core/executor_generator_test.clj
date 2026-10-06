(ns ai.obney.orc.mcp-sheet-builder.core.executor-generator-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.mcp-sheet-builder.core.executor-generator :as gen]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def sample-tool
  {:name "searchLangfuseDocs"
   :description "Search Langfuse documentation"
   :inputSchema {"type" "object"
                 "properties" {"query" {"type" "string"
                                        "description" "Search query"}}
                 "required" ["query"]}})

(def multi-param-tool
  {:name "tavilySearch"
   :description "Web search via Tavily"
   :inputSchema {"type" "object"
                 "properties" {"query" {"type" "string"}
                               "maxResults" {"type" "integer"}
                               "searchDepth" {"type" "string"}}
                 "required" ["query"]}})

;; ============================================================================
;; Name Sanitization Tests
;; ============================================================================

(deftest sanitize-name-test
  (testing "Basic name sanitization"
    (is (= "searchLangfuseDocs" (gen/sanitize-name "searchLangfuseDocs")))
    (is (= "tavily-search" (gen/sanitize-name "tavily-search")))
    ;; Underscores are valid in Clojure identifiers, so they're kept
    (is (= "search_docs" (gen/sanitize-name "search_docs"))))

  (testing "Special characters removed"
    (is (= "search-api" (gen/sanitize-name "search.api")))
    (is (= "query-v2" (gen/sanitize-name "query@v2")))))

;; ============================================================================
;; Checksum Tests
;; ============================================================================

(deftest compute-checksum-test
  (testing "Checksum is deterministic"
    (let [code "(defn foo [] 1)"
          checksum1 (gen/compute-checksum code)
          checksum2 (gen/compute-checksum code)]
      (is (= checksum1 checksum2))))

  (testing "Different code produces different checksums"
    (let [checksum1 (gen/compute-checksum "(defn foo [] 1)")
          checksum2 (gen/compute-checksum "(defn foo [] 2)")]
      (is (not= checksum1 checksum2))))

  (testing "Checksum is 64 hex characters (SHA-256)"
    (let [checksum (gen/compute-checksum "test")]
      (is (= 64 (count checksum)))
      (is (re-matches #"[0-9a-f]+" checksum)))))

(deftest verify-checksum-test
  (testing "Valid checksum passes"
    (let [code "(defn foo [] 1)"
          checksum (gen/compute-checksum code)]
      (is (nil? (gen/verify-checksum code checksum)))))

  (testing "Invalid checksum throws"
    (let [code "(defn foo [] 1)"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Checksum mismatch"
                            (gen/verify-checksum code "invalid-checksum"))))))

;; ============================================================================
;; Code Generation Tests
;; ============================================================================

(deftest generate-executor-code-test
  (testing "Generates valid executor function"
    (let [code (gen/generate-executor-code {:tool-name "searchDocs"
                                            :input-schema {"type" "object"
                                                           "properties" {"query" {"type" "string"}}}
                                            :output-key "search-result"})]
      (is (string? code))
      (is (.contains code "defn call-searchDocs"))
      (is (.contains code "inputs"))
      (is (.contains code "executors/invoke-tool invocation"))
      (is (.contains code "search-result"))))

  (testing "Handles multiple parameters"
    (let [code (gen/generate-executor-code {:tool-name "multiTool"
                                            :input-schema {"type" "object"
                                                           "properties" {"a" {"type" "string"}
                                                                         "b" {"type" "integer"}
                                                                         "c" {"type" "boolean"}}}})]
      (is (.contains code ":a"))
      (is (.contains code ":b"))
      (is (.contains code ":c")))))

(deftest generate-namespace-code-test
  (testing "Generates complete namespace"
    (let [tool-id (java.util.UUID/randomUUID)
          code (gen/generate-namespace-code {:tool-id tool-id
                                             :tool-name "searchDocs"
                                             :input-schema {"type" "object"
                                                            "properties" {"query" {"type" "string"}}}})]
      (is (string? code))
      (is (.contains code "ns mcp.executors.dynamic"))
      (is (.contains code "require"))
      (is (.contains code "mcp-sheet-builder.core.executors"))
      (is (.contains code "defn call-searchDocs"))))

  (testing "Namespace includes tool ID prefix"
    (let [tool-id #uuid "12345678-1234-1234-1234-123456789012"
          code (gen/generate-namespace-code {:tool-id tool-id
                                             :tool-name "test"
                                             :input-schema {}})]
      (is (.contains code "mcp.executors.dynamic.t12345678")))))

(deftest generate-executor-fn-reference-test
  (testing "Generates correct qualified reference"
    (let [tool-id #uuid "abcdef12-1234-1234-1234-123456789012"
          ref (gen/generate-executor-fn-reference tool-id "searchDocs")]
      (is (= "mcp.executors.dynamic.tabcdef12/call-searchDocs" ref)))))

;; ============================================================================
;; Full Definition Tests
;; ============================================================================

(deftest build-executor-definition-test
  (testing "Builds complete definition"
    (let [def (gen/build-executor-definition sample-tool)]
      (is (uuid? (:tool-id def)))
      (is (= "searchLangfuseDocs" (:tool-name def)))
      (is (string? (:source-code def)))
      (is (vector? (:namespace-requires def)))
      (is (string? (:checksum def)))
      (is (string? (:fn-reference def)))))

  (testing "Source code is valid Clojure"
    (let [def (gen/build-executor-definition sample-tool)
          {:keys [valid? errors]} (gen/validate-generated-code (:source-code def))]
      (is valid? (str "Code validation failed: " errors)))))

(deftest build-executor-definitions-test
  (testing "Builds definitions for multiple tools"
    (let [defs (gen/build-executor-definitions [sample-tool multi-param-tool])]
      (is (= 2 (count defs)))
      (is (= "searchLangfuseDocs" (:tool-name (first defs))))
      (is (= "tavilySearch" (:tool-name (second defs)))))))

;; ============================================================================
;; Validation Tests
;; ============================================================================

(deftest validate-generated-code-test
  (testing "Valid code passes"
    (let [result (gen/validate-generated-code "(defn foo [] 1)")]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "Invalid code fails"
    (let [result (gen/validate-generated-code "(defn foo [")]
      (is (not (:valid? result)))
      (is (seq (:errors result))))))
