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

;; =============================================================================
;; Pre-execution validation: validate-syntax + unbound-symbols
;; =============================================================================

(deftest validate-syntax-test
  (testing "well-formed code returns nil"
    (is (nil? (sandbox/validate-syntax "(+ 1 2)")))
    (is (nil? (sandbox/validate-syntax "(let [x 1] (println x))")))
    (is (nil? (sandbox/validate-syntax "(do (def y 5) (* y y))"))))

  (testing "unbalanced parens return a structured syntax error"
    (let [err (sandbox/validate-syntax "(+ 1 2")]
      (is (= :syntax (:type err)))
      (is (string? (:message err)))
      (is (re-find #"EOF|expected|read" (:message err)))))

  (testing "unbalanced brackets return a structured error"
    (let [err (sandbox/validate-syntax "[1 2 3")]
      (is (= :syntax (:type err)))
      (is (string? (:message err))))))

(deftest unbound-symbols-clean-test
  (testing "code that uses only safe-clojure-core returns no unbound symbols"
    (is (= {} (sandbox/unbound-symbols "(+ 1 2)" {})))
    (is (= {} (sandbox/unbound-symbols "(let [x 1 y 2] (+ x y))" {})))
    (is (= {} (sandbox/unbound-symbols "(map #(* % 2) [1 2 3])" {})))
    (is (= {} (sandbox/unbound-symbols
               "(reduce + 0 (map (fn [x] (* x x)) (range 5)))" {})))))

(deftest unbound-symbols-flags-system-test
  (testing "System/* is reported as Java interop AND as unbound (System is on the
            dangerous-bare-symbols list — the LLM sees both signals at once)"
    (let [r (sandbox/unbound-symbols "(System/currentTimeMillis)" {})]
      (is (contains? (:java-interop r) 'System/currentTimeMillis))
      (is (contains? (:unbound r) 'System/currentTimeMillis))))
  (testing "Math/abs is Java interop only — Math isn't on the dangerous list"
    (let [r (sandbox/unbound-symbols "(Math/abs -5)" {})]
      (is (contains? (:java-interop r) 'Math/abs))
      (is (nil? (:unbound r))
          "Math itself isn't dangerous, just non-sandboxed Java interop"))))

(deftest unbound-symbols-flags-non-safe-symbols-test
  (testing "slurp / require / eval are unbound (not in safe-clojure-core)"
    (let [r (sandbox/unbound-symbols "(slurp \"/etc/passwd\")" {})]
      (is (contains? (:unbound r) 'slurp))
      (is (nil? (:java-interop r))))
    (let [r (sandbox/unbound-symbols "(eval '(println 1))" {})]
      (is (contains? (:unbound r) 'eval)))))

(deftest unbound-symbols-recognizes-mcp-tools-test
  (testing "MCP-style namespaced tool names are recognized as bound"
    (let [r (sandbox/unbound-symbols
              "(pdf/page-count \"x\")"
              {:call-tool-fn (fn [_ _]) :mcp-tools ["pdf/page-count"]})]
      (is (= {} r))))
  (testing "flat tool names are recognized"
    (let [r (sandbox/unbound-symbols
              "(lookup {:k 1})"
              {:call-tool-fn (fn [_ _]) :mcp-tools ["lookup"]})]
      (is (= {} r)))))

(deftest unbound-symbols-recognizes-extra-bindings-test
  (testing "host-supplied extra-bindings (e.g. predict, final!) are recognized"
    (let [r (sandbox/unbound-symbols
              "(predict {:name \"x\"})"
              {:extra-bindings {'predict (fn [_]) 'final! (fn [_])}})]
      (is (= {} r)))))

(deftest unbound-symbols-mixed-test
  (testing "mixed code: bound MCP tools pass, dangerous symbols (System) flag both
            as :unbound and :java-interop, ordinary clojure.core fns (println,
            count) pass via SCI built-ins"
    (let [r (sandbox/unbound-symbols
              "(let [pages (pdf/page-count \"x\")
                     stamp (System/currentTimeMillis)]
                 (println pages stamp))"
              {:call-tool-fn (fn [_ _]) :mcp-tools ["pdf/page-count"]})]
      (is (contains? (:java-interop r) 'System/currentTimeMillis))
      (is (contains? (:unbound r) 'System/currentTimeMillis))
      ;; pdf/page-count, println, let, etc. should NOT appear
      (is (not (contains? (:unbound r) 'pdf/page-count)))
      (is (not (contains? (:unbound r) 'println))))))
