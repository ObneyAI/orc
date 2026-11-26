(ns ai.obney.orc.orc-service.sci-sandbox-test
  "Tests for the SCI sandbox used by repl-researcher nodes."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))

;; =============================================================================
;; Basic Execution
;; =============================================================================

(deftest basic-execution-test
  (testing "simple arithmetic"
    (let [ctx (service-logic/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (service-logic/sci-sandbox-execute-code ctx "(+ 1 2 3)")]
      (is (nil? (:error result)))
      (is (= "6" (:result result)))
      (is (= 6 (:raw-result result)))))

  (testing "string operations"
    (let [ctx (service-logic/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (service-logic/sci-sandbox-execute-code ctx "(str \"hello\" \" \" \"world\")")]
      (is (nil? (:error result)))
      (is (= "\"hello world\"" (:result result))))))

(deftest stdout-capture-test
  (testing "println output captured in :stdout"
    (let [ctx (service-logic/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (service-logic/sci-sandbox-execute-code ctx "(do (println \"hello\") 42)")]
      (is (nil? (:error result)))
      (is (= "42" (:result result)))
      (is (= "hello\n" (:stdout result)))))

  (testing "multiple prints captured"
    (let [ctx (service-logic/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (service-logic/sci-sandbox-execute-code ctx "(do (print \"a\") (print \"b\") (println \"c\") :done)")]
      (is (= "abc\n" (:stdout result))))))

;; =============================================================================
;; MCP Tool Calls
;; =============================================================================

(deftest mcp-tool-call-test
  (testing "tool function is callable from SCI code"
    (let [calls (atom [])
          mock-call-tool (fn [tool-name args]
                           (swap! calls conj {:tool tool-name :args args})
                           {"result" (str "response-for-" tool-name)})
          ctx (service-logic/build-sci-context {:call-tool-fn mock-call-tool
                                          :mcp-tools ["myTool"]})
          result (service-logic/sci-sandbox-execute-code ctx "(myTool {:query \"test\"})")]
      (is (nil? (:error result)))
      (is (= 1 (count @calls)))
      (is (= "myTool" (:tool (first @calls))))
      (is (= {:query "test"} (:args (first @calls))))))

  (testing "multiple tools available"
    (let [mock-call-tool (fn [tool-name args]
                           {"tool" tool-name "value" 42})
          ctx (service-logic/build-sci-context {:call-tool-fn mock-call-tool
                                          :mcp-tools ["search" "fetch"]})
          result (service-logic/sci-sandbox-execute-code ctx "(let [a (search {:q \"x\"}) b (fetch {:id 1})] [a b])")]
      (is (nil? (:error result)))
      (is (= 2 (count (:raw-result result)))))))

;; =============================================================================
;; Nil call-tool-fn
;; =============================================================================

(deftest nil-call-tool-fn-test
  (testing "nil call-tool-fn produces no tool bindings, no crash"
    (let [ctx (service-logic/build-sci-context {:call-tool-fn nil :mcp-tools ["someTool"]})]
      (let [result (service-logic/sci-sandbox-execute-code ctx "(+ 1 2)")]
        (is (nil? (:error result)))
        (is (= "3" (:result result)))))))

;; =============================================================================
;; FINAL_ANSWER Detection
;; =============================================================================

(deftest final-answer-detection-test
  (testing "extract-final-answer from various patterns"
    (is (= "42" (service-logic/extract-final-answer "FINAL_ANSWER: 42")))
    (is (= "hello world" (service-logic/extract-final-answer "FINAL_ANSWER: hello world")))
    (is (= "42" (service-logic/extract-final-answer "FINAL-ANSWER: 42")))
    (is (nil? (service-logic/extract-final-answer "no answer here")))
    (is (nil? (service-logic/extract-final-answer nil))))

  (testing "contains-final-answer?"
    (is (true? (service-logic/contains-final-answer? "FINAL_ANSWER: done")))
    (is (false? (service-logic/contains-final-answer? "no answer")))
    (is (false? (service-logic/contains-final-answer? nil)))))

;; =============================================================================
;; Convergence Detection
;; =============================================================================

(deftest repeated-output-test
  (testing "detects repeated output"
    (let [history [{:stdout "hello" :result "42"}
                   {:stdout "hello" :result "42"}]
          current {:stdout "hello" :result "42"}]
      (is (service-logic/repeated-output? history current))))

  (testing "no repeat when output differs"
    (let [history [{:stdout "hello" :result "42"}]
          current {:stdout "hello" :result "43"}]
      (is (not (service-logic/repeated-output? history current)))))

  (testing "empty history never repeats"
    (is (not (service-logic/repeated-output? [] {:stdout "" :result "42"})))))

;; =============================================================================
;; Sandbox Safety
;; =============================================================================

(deftest sandbox-safety-test
  (testing "slurp is not available"
    (let [ctx (service-logic/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (service-logic/sci-sandbox-execute-code ctx "(slurp \"/etc/passwd\")")]
      (is (some? (:error result)))))

  (testing "System/exit is not available"
    (let [ctx (service-logic/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (service-logic/sci-sandbox-execute-code ctx "(System/exit 0)")]
      (is (some? (:error result)))))

  (testing "require is not available"
    (let [ctx (service-logic/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (service-logic/sci-sandbox-execute-code ctx "(require '[clojure.java.io])")]
      (is (some? (:error result))))))

;; =============================================================================
;; execute-with-mcp Convenience
;; =============================================================================

(deftest execute-with-mcp-test
  (testing "combines build + execute + final-answer extraction"
    (let [mock-call-tool (fn [tool-name _args]
                           {"answer" "the answer is 42"})
          result (service-logic/execute-with-mcp
                  {:call-tool-fn mock-call-tool
                   :mcp-tools ["lookup"]
                   :code "(str \"FINAL_ANSWER: \" (get (lookup {:key \"x\"}) \"answer\"))"})]
      (is (nil? (:error result)))
      (is (some? (:final-answer result))))))


(deftest namespaced-tool-call-test
  (testing "server/tool callable via namespace-qualified symbol"
    (let [calls (atom [])
          mock-fn (fn [tool-name args]
                    (swap! calls conj {:tool tool-name :args args})
                    {"result" "ok"})
          ctx (service-logic/build-sci-context {:call-tool-fn mock-fn
                                          :mcp-tools ["linear/list_issues"]})
          result (service-logic/sci-sandbox-execute-code ctx "(linear/list_issues {:project \"abc\"})")]
      (is (nil? (:error result)))
      (is (= 1 (count @calls)))
      (is (= "linear/list_issues" (:tool (first @calls))))))

  (testing "multiple servers with distinct namespaces"
    (let [calls (atom [])
          mock-fn (fn [tool-name args]
                    (swap! calls conj {:tool tool-name :args args})
                    {"from" tool-name})
          ctx (service-logic/build-sci-context {:call-tool-fn mock-fn
                                          :mcp-tools ["linear/list_issues"
                                                      "github/list_pulls"]})
          result (service-logic/sci-sandbox-execute-code ctx
                   "(let [a (linear/list_issues {:project \"abc\"})
                          b (github/list_pulls {:state \"open\"})]
                      [(get a \"from\") (get b \"from\")])")]
      (is (nil? (:error result)))
      (is (= 2 (count @calls)))
      (is (= "linear/list_issues" (:tool (first @calls))))
      (is (= "github/list_pulls" (:tool (second @calls))))))

  (testing "mixed namespaced and flat tools in same context"
    (let [calls (atom [])
          mock-fn (fn [tool-name args]
                    (swap! calls conj {:tool tool-name})
                    {"ok" true})
          ctx (service-logic/build-sci-context {:call-tool-fn mock-fn
                                          :mcp-tools ["lookup"
                                                      "linear/list_issues"]})
          result (service-logic/sci-sandbox-execute-code ctx
                   "(do (lookup {:key \"x\"}) (linear/list_issues {:p \"y\"}) :done)")]
      (is (nil? (:error result)))
      (is (= 2 (count @calls)))
      (is (= "lookup" (:tool (first @calls))))
      (is (= "linear/list_issues" (:tool (second @calls))))))

  (testing "same tool name on different servers resolves independently"
    (let [calls (atom [])
          mock-fn (fn [tool-name args]
                    (swap! calls conj {:tool tool-name})
                    {"source" tool-name})
          ctx (service-logic/build-sci-context {:call-tool-fn mock-fn
                                          :mcp-tools ["exa/search"
                                                      "tavily/search"]})
          result (service-logic/sci-sandbox-execute-code ctx
                   "(let [a (exa/search {:q \"x\"})
                          b (tavily/search {:q \"y\"})]
                      [(get a \"source\") (get b \"source\")])")]
      (is (nil? (:error result)))
      (is (= ["exa/search" "tavily/search"] (mapv :tool @calls))))))
