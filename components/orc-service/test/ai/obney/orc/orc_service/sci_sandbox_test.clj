(ns ai.obney.orc.orc-service.sci-sandbox-test
  "Tests for the SCI sandbox used by repl-researcher nodes."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.sci-sandbox :as sandbox]))

;; =============================================================================
;; Basic Execution
;; =============================================================================

(deftest basic-execution-test
  (testing "simple arithmetic"
    (let [ctx (sandbox/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (sandbox/execute-code ctx "(+ 1 2 3)")]
      (is (nil? (:error result)))
      (is (= "6" (:result result)))
      (is (= 6 (:raw-result result)))))

  (testing "string operations"
    (let [ctx (sandbox/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (sandbox/execute-code ctx "(str \"hello\" \" \" \"world\")")]
      (is (nil? (:error result)))
      (is (= "\"hello world\"" (:result result))))))

(deftest stdout-capture-test
  (testing "println output captured in :stdout"
    (let [ctx (sandbox/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (sandbox/execute-code ctx "(do (println \"hello\") 42)")]
      (is (nil? (:error result)))
      (is (= "42" (:result result)))
      (is (= "hello\n" (:stdout result)))))

  (testing "multiple prints captured"
    (let [ctx (sandbox/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (sandbox/execute-code ctx "(do (print \"a\") (print \"b\") (println \"c\") :done)")]
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
          ctx (sandbox/build-sci-context {:call-tool-fn mock-call-tool
                                          :mcp-tools ["myTool"]})
          result (sandbox/execute-code ctx "(myTool {:query \"test\"})")]
      (is (nil? (:error result)))
      (is (= 1 (count @calls)))
      (is (= "myTool" (:tool (first @calls))))
      (is (= {:query "test"} (:args (first @calls))))))

  (testing "multiple tools available"
    (let [mock-call-tool (fn [tool-name args]
                           {"tool" tool-name "value" 42})
          ctx (sandbox/build-sci-context {:call-tool-fn mock-call-tool
                                          :mcp-tools ["search" "fetch"]})
          result (sandbox/execute-code ctx "(let [a (search {:q \"x\"}) b (fetch {:id 1})] [a b])")]
      (is (nil? (:error result)))
      (is (= 2 (count (:raw-result result)))))))

;; =============================================================================
;; Nil call-tool-fn
;; =============================================================================

(deftest nil-call-tool-fn-test
  (testing "nil call-tool-fn produces no tool bindings, no crash"
    (let [ctx (sandbox/build-sci-context {:call-tool-fn nil :mcp-tools ["someTool"]})]
      ;; Tools aren't bound, but basic code still works
      (let [result (sandbox/execute-code ctx "(+ 1 2)")]
        (is (nil? (:error result)))
        (is (= "3" (:result result)))))))

;; =============================================================================
;; FINAL_ANSWER Detection
;; =============================================================================

(deftest final-answer-detection-test
  (testing "extract-final-answer from various patterns"
    (is (= "42" (sandbox/extract-final-answer "FINAL_ANSWER: 42")))
    (is (= "hello world" (sandbox/extract-final-answer "FINAL_ANSWER: hello world")))
    (is (= "42" (sandbox/extract-final-answer "FINAL-ANSWER: 42")))
    (is (nil? (sandbox/extract-final-answer "no answer here")))
    (is (nil? (sandbox/extract-final-answer nil))))

  (testing "contains-final-answer?"
    (is (true? (sandbox/contains-final-answer? "FINAL_ANSWER: done")))
    (is (false? (sandbox/contains-final-answer? "no answer")))
    (is (false? (sandbox/contains-final-answer? nil)))))

;; =============================================================================
;; Convergence Detection
;; =============================================================================

(deftest repeated-output-test
  (testing "detects repeated output"
    (let [history [{:stdout "hello" :result "42"}
                   {:stdout "hello" :result "42"}]
          current {:stdout "hello" :result "42"}]
      (is (sandbox/repeated-output? history current))))

  (testing "no repeat when output differs"
    (let [history [{:stdout "hello" :result "42"}]
          current {:stdout "hello" :result "43"}]
      (is (not (sandbox/repeated-output? history current)))))

  (testing "empty history never repeats"
    (is (not (sandbox/repeated-output? [] {:stdout "" :result "42"})))))

;; =============================================================================
;; Sandbox Safety
;; =============================================================================

(deftest sandbox-safety-test
  (testing "slurp is not available"
    (let [ctx (sandbox/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (sandbox/execute-code ctx "(slurp \"/etc/passwd\")")]
      (is (some? (:error result)))))

  (testing "System/exit is not available"
    (let [ctx (sandbox/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (sandbox/execute-code ctx "(System/exit 0)")]
      (is (some? (:error result)))))

  (testing "require is not available"
    (let [ctx (sandbox/build-sci-context {:call-tool-fn nil :mcp-tools []})
          result (sandbox/execute-code ctx "(require '[clojure.java.io])")]
      (is (some? (:error result))))))

;; =============================================================================
;; execute-with-mcp Convenience
;; =============================================================================

(deftest execute-with-mcp-test
  (testing "combines build + execute + final-answer extraction"
    (let [mock-call-tool (fn [tool-name _args]
                           {"answer" "the answer is 42"})
          result (sandbox/execute-with-mcp
                  {:call-tool-fn mock-call-tool
                   :mcp-tools ["lookup"]
                   :code "(str \"FINAL_ANSWER: \" (get (lookup {:key \"x\"}) \"answer\"))"})]
      (is (nil? (:error result)))
      (is (some? (:final-answer result))))))

;; =============================================================================
;; Namespaced Tool Bindings (Multi-MCP)
;; =============================================================================

(deftest namespaced-tool-call-test
  (testing "server/tool callable via namespace-qualified symbol"
    (let [calls (atom [])
          mock-fn (fn [tool-name args]
                    (swap! calls conj {:tool tool-name :args args})
                    {"result" "ok"})
          ctx (sandbox/build-sci-context {:call-tool-fn mock-fn
                                          :mcp-tools ["linear/list_issues"]})
          result (sandbox/execute-code ctx "(linear/list_issues {:project \"abc\"})")]
      (is (nil? (:error result)))
      (is (= 1 (count @calls)))
      ;; call-tool-fn receives the full prefixed name
      (is (= "linear/list_issues" (:tool (first @calls))))))

  (testing "multiple servers with distinct namespaces"
    (let [calls (atom [])
          mock-fn (fn [tool-name args]
                    (swap! calls conj {:tool tool-name :args args})
                    {"from" tool-name})
          ctx (sandbox/build-sci-context {:call-tool-fn mock-fn
                                          :mcp-tools ["linear/list_issues"
                                                      "github/list_pulls"]})
          result (sandbox/execute-code ctx
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
          ctx (sandbox/build-sci-context {:call-tool-fn mock-fn
                                          :mcp-tools ["lookup"
                                                      "linear/list_issues"]})
          result (sandbox/execute-code ctx
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
          ctx (sandbox/build-sci-context {:call-tool-fn mock-fn
                                          :mcp-tools ["exa/search"
                                                      "tavily/search"]})
          result (sandbox/execute-code ctx
                   "(let [a (exa/search {:q \"x\"})
                          b (tavily/search {:q \"y\"})]
                      [(get a \"source\") (get b \"source\")])")]
      (is (nil? (:error result)))
      (is (= ["exa/search" "tavily/search"] (mapv :tool @calls))))))

;; =============================================================================
;; Extra Bindings (host-provided, generic)
;; =============================================================================

(deftest extra-bindings-test
  (testing "scalar extra binding is reachable as a value in SCI"
    (let [ctx (sandbox/build-sci-context
                {:call-tool-fn nil
                 :mcp-tools []
                 :extra-bindings {'question "what is 2+2?"
                                  'answer 4}})
          result (sandbox/execute-code ctx "[question answer]")]
      (is (nil? (:error result)))
      (is (= ["what is 2+2?" 4] (:raw-result result)))))

  (testing "function extra binding is callable from SCI"
    (let [calls (atom [])
          predict-fn (fn [m]
                       (swap! calls conj m)
                       {:label "ok" :input m})
          ctx (sandbox/build-sci-context
                {:call-tool-fn nil
                 :mcp-tools []
                 :extra-bindings {'predict predict-fn}})
          result (sandbox/execute-code ctx
                   "(predict {:name \"classify\" :inputs {:line \"hello\"}})")]
      (is (nil? (:error result)))
      (is (= 1 (count @calls)))
      (is (= "classify" (:name (first @calls))))
      (is (= {:label "ok" :input {:name "classify"
                                  :inputs {:line "hello"}}}
             (:raw-result result)))))

  (testing "extra bindings coexist with MCP tools"
    (let [ctx (sandbox/build-sci-context
                {:call-tool-fn (fn [_ _] {"result" "tool-value"})
                 :mcp-tools ["lookup"]
                 :extra-bindings {'helper (fn [x] (* x 2))}})
          result (sandbox/execute-code ctx
                   "[(helper 21) (get (lookup {:k 1}) \"result\")]")]
      (is (nil? (:error result)))
      (is (= [42 "tool-value"] (:raw-result result)))))

  (testing "extra bindings can shadow built-in helpers if needed"
    (let [ctx (sandbox/build-sci-context
                {:call-tool-fn nil
                 :mcp-tools []
                 :extra-bindings {'realize-all (fn [_] :overridden)}})
          result (sandbox/execute-code ctx "(realize-all {:x 1})")]
      (is (nil? (:error result)))
      (is (= :overridden (:raw-result result))))))
