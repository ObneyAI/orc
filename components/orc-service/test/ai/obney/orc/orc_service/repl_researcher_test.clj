(ns ai.obney.orc.orc-service.repl-researcher-test
  "Unit tests for the repl-researcher executor.
   Mocks llm/predict to test the iteration loop without real LLM calls."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [clojure.string :as str]
            [ai.obney.orc.llm.interface :as llm]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-node
  {:type :repl-researcher
   :name "test-researcher"
   :instruction "Find the answer"
   :reads [:question]
   :writes [:answer]
   :mcp-tools ["lookup"]
   :max-iterations 5
   :model "test-model"})

(def test-blackboard
  {:question {:key :question
              :schema :string
              :value "What is 2+2?"
              :version 1}
   :answer   {:key :answer
              :schema :string
              :value nil
              :version 0}})

(defn mock-call-tool [tool-name args]
  {"result" (clojure.core/str "looked-up:" (get args "key" "default"))})

(def test-context
  {:call-tool-fn mock-call-tool
   :llm-provider :test})

;; =============================================================================
;; Tests
;; =============================================================================

(deftest immediate-final-answer-in-code-text-test
  (testing "LLM returns plain-text FINAL_ANSWER (not code) — caught in code-text check"
    (with-redefs [llm/predict
                  (fn [_provider _module _inputs _opts]
                    {:outputs {:code "FINAL_ANSWER: 42"}
                     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
      (let [result (executor/execute-repl-researcher
                     test-node test-blackboard :test test-context)]
        (is (= :success (:status result)))
        (is (= "42" (:answer (:outputs result))))
        (is (some? (:duration-ms result)))))))

(deftest final-answer-via-execution-test
  (testing "code executes and produces FINAL_ANSWER in stdout"
    (with-redefs [llm/predict
                  (fn [_provider _module _inputs _opts]
                    ;; Build FINAL_ANSWER at runtime to avoid code-text detection
                    {:outputs {:code "(let [m (str \"FINAL\" \"_ANSWER: \")] (println (str m \"hello\")))"}
                     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
      (let [result (executor/execute-repl-researcher
                     test-node test-blackboard :test test-context)]
        (is (= :success (:status result)))
        (is (= "hello" (:answer (:outputs result))))))))

(deftest tool-call-then-answer-test
  (testing "first iteration calls tool, second produces FINAL_ANSWER"
    (let [call-count (atom 0)
          tool-calls (atom [])]
      (with-redefs [llm/predict
                    (fn [_provider _module _inputs _opts]
                      (let [n (swap! call-count inc)]
                        (if (= n 1)
                          ;; First: call tool, print result (no FINAL_ANSWER)
                          {:outputs {:code "(println (get (lookup {\"key\" \"pi\"}) \"result\"))"}
                           :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}
                          ;; Second: plain-text FINAL_ANSWER
                          {:outputs {:code "FINAL_ANSWER: pi is 3.14"}
                           :usage {:prompt_tokens 15 :completion_tokens 8 :total_tokens 23}})))]
        (let [ctx (assoc test-context :call-tool-fn
                         (fn [tool-name args]
                           (swap! tool-calls conj {:tool tool-name :args args})
                           {"result" "3.14"}))
              result (executor/execute-repl-researcher
                       test-node test-blackboard :test ctx)]
          (is (= :success (:status result)))
          (is (= "pi is 3.14" (:answer (:outputs result))))
          (is (= 1 (count @tool-calls)))
          (is (= "lookup" (:tool (first @tool-calls)))))))))

(deftest max-iterations-test
  (testing "fails when max iterations reached without FINAL_ANSWER"
    (let [call-count (atom 0)]
      (with-redefs [llm/predict
                    (fn [_provider _module _inputs _opts]
                      ;; Different output each time to avoid convergence detection
                      (let [n (swap! call-count inc)]
                        {:outputs {:code (clojure.core/str "(println \"iteration " n " searching...\")")}
                         :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}))]
        (let [node (assoc test-node :max-iterations 3)
              result (executor/execute-repl-researcher
                       node test-blackboard :test test-context)]
          (is (= :failure (:status result)))
          (is (str/includes? (:error result) "Max iterations")))))))

(deftest blank-code-test
  (testing "fails when LLM returns blank code"
    (with-redefs [llm/predict
                  (fn [_provider _module _inputs _opts]
                    {:outputs {:code ""}
                     :usage {:prompt_tokens 10 :completion_tokens 0 :total_tokens 10}})]
      (let [result (executor/execute-repl-researcher
                     test-node test-blackboard :test test-context)]
        (is (= :failure (:status result)))
        (is (str/includes? (:error result) "did not generate code"))))))

(deftest usage-tracking-test
  (testing "usage accumulates across iterations"
    (let [call-count (atom 0)]
      (with-redefs [llm/predict
                    (fn [_provider _module _inputs _opts]
                      (let [n (swap! call-count inc)]
                        (if (< n 3)
                          {:outputs {:code (clojure.core/str "(println \"step " n "\")")}
                           :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}}
                          {:outputs {:code "FINAL_ANSWER: done"}
                           :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}})))]
        (let [result (executor/execute-repl-researcher
                       test-node test-blackboard :test test-context)]
          (is (= :success (:status result)))
          (is (= 300 (get-in result [:usage :prompt-tokens])))
          (is (= 150 (get-in result [:usage :completion-tokens])))
          (is (= 450 (get-in result [:usage :total-tokens]))))))))

(deftest nil-call-tool-fn-no-crash-test
  (testing "works without call-tool-fn when code doesn't call tools"
    (with-redefs [llm/predict
                  (fn [_provider _module _inputs _opts]
                    {:outputs {:code "FINAL_ANSWER: 42"}
                     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
      (let [ctx (dissoc test-context :call-tool-fn)
            result (executor/execute-repl-researcher
                     test-node test-blackboard :test ctx)]
        (is (= :success (:status result)))
        (is (= "42" (:answer (:outputs result))))))))

(deftest namespaced-tools-test
  (testing "repl-researcher with namespaced MCP tools from multiple servers"
    (let [tool-calls (atom [])
          call-count (atom 0)]
      (with-redefs [llm/predict
                    (fn [_provider _module _inputs _opts]
                      (let [n (swap! call-count inc)]
                        (if (= n 1)
                          {:outputs {:code "(let [issues (linear/list_issues {:project \"abc\"})
                                                  pulls (github/list_pulls {:state \"open\"})]
                                              (println \"Found\" (count (get issues \"items\")) \"issues\")
                                              (println \"Found\" (count (get pulls \"items\")) \"pulls\"))"}
                           :usage {:prompt_tokens 50 :completion_tokens 30 :total_tokens 80}}
                          {:outputs {:code "FINAL_ANSWER: 3 issues and 2 pulls"}
                           :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
        (let [node (assoc test-node :mcp-tools ["linear/list_issues" "github/list_pulls"])
              ctx {:call-tool-fn (fn [tool-name args]
                                   (swap! tool-calls conj {:tool tool-name :args args})
                                   (case tool-name
                                     "linear/list_issues" {"items" ["a" "b" "c"]}
                                     "github/list_pulls"  {"items" ["x" "y"]}
                                     {"error" "unknown"}))
                   :llm-provider :test}
              result (executor/execute-repl-researcher
                       node test-blackboard :test ctx)]
          (is (= :success (:status result)))
          (is (= "3 issues and 2 pulls" (:answer (:outputs result))))
          (is (= 2 (count @tool-calls)))
          (is (= #{"linear/list_issues" "github/list_pulls"}
                 (set (map :tool @tool-calls)))))))))
