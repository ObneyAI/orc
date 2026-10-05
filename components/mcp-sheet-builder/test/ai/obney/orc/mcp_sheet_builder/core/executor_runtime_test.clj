(ns ai.obney.orc.mcp-sheet-builder.core.executor-runtime-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.mcp-sheet-builder.core.executor-runtime :as runtime]
            [ai.obney.orc.mcp-sheet-builder.core.executor-generator :as gen]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn clear-registry-fixture [f]
  (runtime/clear-registry!)
  (f)
  (runtime/clear-registry!))

(use-fixtures :each clear-registry-fixture)

(def sample-tool
  {:name "testTool"
   :inputSchema {"type" "object"
                 "properties" {"query" {"type" "string"}}
                 "required" ["query"]}})

;; ============================================================================
;; Loading Tests
;; ============================================================================

(deftest load-executor-test
  (testing "Successfully loads valid executor"
    (let [exec-def (gen/build-executor-definition sample-tool)
          result (runtime/load-executor! (:tool-id exec-def)
                                         (:tool-name exec-def)
                                         (:source-code exec-def)
                                         (:namespace-requires exec-def))]
      (is (:success? result))
      (is (some? (:fn result)))
      (is (symbol? (:namespace result)))))

  (testing "Returns cached result on reload without force"
    (let [exec-def (gen/build-executor-definition sample-tool)
          result1 (runtime/load-executor! (:tool-id exec-def)
                                          (:tool-name exec-def)
                                          (:source-code exec-def)
                                          (:namespace-requires exec-def))
          result2 (runtime/load-executor! (:tool-id exec-def)
                                          (:tool-name exec-def)
                                          (:source-code exec-def)
                                          (:namespace-requires exec-def))]
      (is (:success? result1))
      (is (:success? result2))
      (is (= (:fn result1) (:fn result2)))))

  (testing "Verifies checksum when provided"
    (let [exec-def (gen/build-executor-definition sample-tool)
          result (runtime/load-executor! (:tool-id exec-def)
                                         (:tool-name exec-def)
                                         (:source-code exec-def)
                                         (:namespace-requires exec-def)
                                         :checksum (:checksum exec-def)
                                         :force? true)]
      (is (:success? result))))

  (testing "Fails on invalid checksum"
    (let [exec-def (gen/build-executor-definition sample-tool)]
      (runtime/clear-registry!)
      (let [result (runtime/load-executor! (:tool-id exec-def)
                                           (:tool-name exec-def)
                                           (:source-code exec-def)
                                           (:namespace-requires exec-def)
                                           :checksum "invalid-checksum"
                                           :force? true)]
        (is (not (:success? result)))
        (is (.contains (:error result) "Checksum"))))))

;; ============================================================================
;; Resolution Tests
;; ============================================================================

(deftest get-executor-test
  (testing "Resolves by tool-id"
    (let [exec-def (gen/build-executor-definition sample-tool)
          _ (runtime/load-executor! (:tool-id exec-def)
                                    (:tool-name exec-def)
                                    (:source-code exec-def)
                                    (:namespace-requires exec-def))
          executor (runtime/get-executor (:tool-id exec-def))]
      (is (fn? executor))))

  (testing "Resolves by tool name"
    (let [exec-def (gen/build-executor-definition sample-tool)
          _ (runtime/load-executor! (:tool-id exec-def)
                                    (:tool-name exec-def)
                                    (:source-code exec-def)
                                    (:namespace-requires exec-def))
          executor (runtime/get-executor "testTool")]
      (is (fn? executor))))

  (testing "Resolves by qualified name"
    (let [exec-def (gen/build-executor-definition sample-tool)
          _ (runtime/load-executor! (:tool-id exec-def)
                                    (:tool-name exec-def)
                                    (:source-code exec-def)
                                    (:namespace-requires exec-def))
          executor (runtime/get-executor (:fn-reference exec-def))]
      (is (fn? executor))))

  (testing "Returns nil for unknown executor"
    (is (nil? (runtime/get-executor "unknown-tool")))
    (is (nil? (runtime/get-executor (java.util.UUID/randomUUID))))))

;; ============================================================================
;; Executor Invocation Tests
;; ============================================================================

(deftest executor-invocation-test
  (testing "Executor can be called with inputs"
    (let [exec-def (gen/build-executor-definition sample-tool)
          _ (runtime/load-executor! (:tool-id exec-def)
                                    (:tool-name exec-def)
                                    (:source-code exec-def)
                                    (:namespace-requires exec-def))
          executor (runtime/get-executor "testTool")
          result (executor {:inputs {:query "test query"}
                            :context {:mcp/dry-run? true}})]
      ;; An explicit dry run (and only that) yields a marked stand-in
      (is (map? result))
      (is (contains? result :testTool-result))
      (is (true? (get-in result [:testTool-result :dry-run?]))))))

;; ============================================================================
;; Registry Management Tests
;; ============================================================================

(deftest list-loaded-executors-test
  (testing "Lists loaded executors"
    (let [exec1 (gen/build-executor-definition {:name "tool1" :inputSchema {}})
          exec2 (gen/build-executor-definition {:name "tool2" :inputSchema {}})]
      (runtime/load-executor! (:tool-id exec1) (:tool-name exec1)
                              (:source-code exec1) (:namespace-requires exec1))
      (runtime/load-executor! (:tool-id exec2) (:tool-name exec2)
                              (:source-code exec2) (:namespace-requires exec2))
      (let [loaded (runtime/list-loaded-executors)]
        (is (= 2 (count loaded)))
        (is (some #(= "tool1" (:tool-name %)) loaded))
        (is (some #(= "tool2" (:tool-name %)) loaded))))))

(deftest unload-executor-test
  (testing "Unloads executor from registry"
    (let [exec-def (gen/build-executor-definition sample-tool)
          _ (runtime/load-executor! (:tool-id exec-def)
                                    (:tool-name exec-def)
                                    (:source-code exec-def)
                                    (:namespace-requires exec-def))]
      (is (some? (runtime/get-executor "testTool")))
      (runtime/unload-executor! (:tool-id exec-def))
      (is (nil? (runtime/get-executor "testTool"))))))

(deftest registry-stats-test
  (testing "Returns registry statistics"
    (let [exec1 (gen/build-executor-definition {:name "statsTest1" :inputSchema {}})
          exec2 (gen/build-executor-definition {:name "statsTest2" :inputSchema {}})]
      (runtime/load-executor! (:tool-id exec1) (:tool-name exec1)
                              (:source-code exec1) (:namespace-requires exec1))
      (runtime/load-executor! (:tool-id exec2) (:tool-name exec2)
                              (:source-code exec2) (:namespace-requires exec2))
      (let [stats (runtime/registry-stats)]
        (is (= 2 (:loaded-count stats)))
        (is (contains? (set (:tool-names stats)) "statsTest1"))
        (is (contains? (set (:tool-names stats)) "statsTest2"))))))

;; ============================================================================
;; Bulk Loading Tests
;; ============================================================================

(deftest load-all-executors-test
  (testing "Loads executors from provider function"
    (let [exec1 (gen/build-executor-definition {:name "bulk1" :inputSchema {}})
          exec2 (gen/build-executor-definition {:name "bulk2" :inputSchema {}})
          get-executors-fn (fn []
                             [(select-keys exec1 [:tool-id :tool-name :source-code :namespace-requires :checksum])
                              (select-keys exec2 [:tool-id :tool-name :source-code :namespace-requires :checksum])])
          result (runtime/load-all-executors! get-executors-fn)]
      (is (= 2 (:loaded result)))
      (is (= 0 (:failed result)))
      (is (some? (runtime/get-executor "bulk1")))
      (is (some? (runtime/get-executor "bulk2"))))))
