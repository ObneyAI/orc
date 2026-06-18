(ns ai.obney.orc.orc-service.repl-researcher-test
  "Unit tests for the repl-researcher executor.
   Mocks dscloj/predict to test the iteration loop without real LLM calls."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [clojure.string :as str]
            [dscloj.core :as dscloj]))

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
   :dscloj-provider :test})

;; =============================================================================
;; Tests
;; =============================================================================

(deftest immediate-final-bang-test
  (testing "model code calls (final! {:answer \"42\"}) → :success with :outputs read off the validated map"
    (with-redefs [dscloj/predict
                  (fn [_provider _module _inputs _opts]
                    {:outputs {:code "(final! {:answer \"42\"})"}
                     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
      (let [result (executor/execute-repl-researcher
                     test-node test-blackboard :test test-context)]
        (is (= :success (:status result)))
        (is (= "42" (:answer (:outputs result))))
        (is (some? (:duration-ms result)))))))

(deftest final-bang-extra-key-rejected-test
  (testing "(final! {...}) with a key not in :writes → :failure naming the extra key (validate-final!)"
    (with-redefs [dscloj/predict
                  (fn [_provider _module _inputs _opts]
                    {:outputs {:code "(final! {:answer \"42\" :bogus \"x\"})"}
                     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
      (let [result (executor/execute-repl-researcher
                     test-node test-blackboard :test test-context)]
        (is (= :failure (:status result)))
        (is (str/includes? (:error result) ":writes"))
        (is (str/includes? (:error result) ":bogus"))))))

(deftest final-bang-missing-key-rejected-test
  (testing "(final! {}) missing a required :writes key → :failure (validate-final!)"
    (with-redefs [dscloj/predict
                  (fn [_provider _module _inputs _opts]
                    {:outputs {:code "(final! {})"}
                     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
      (let [result (executor/execute-repl-researcher
                     test-node test-blackboard :test test-context)]
        (is (= :failure (:status result)))
        (is (str/includes? (:error result) "missing required keys"))))))

(deftest final-bang-all-blank-rejected-test
  (testing "(final! {:answer \"\"}) all-blank values → :failure (validate-final!)"
    (with-redefs [dscloj/predict
                  (fn [_provider _module _inputs _opts]
                    {:outputs {:code "(final! {:answer \"\"})"}
                     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
      (let [result (executor/execute-repl-researcher
                     test-node test-blackboard :test test-context)]
        (is (= :failure (:status result)))
        (is (str/includes? (:error result) "all empty values"))))))

(deftest tool-call-then-final-bang-test
  (testing "first iteration calls tool, second calls (final! {...}) → :success, exactly one tool call"
    (let [call-count (atom 0)
          tool-calls (atom [])]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _opts]
                      (let [n (swap! call-count inc)]
                        (if (= n 1)
                          ;; First: call tool, print result (no finalize)
                          {:outputs {:code "(println (get (lookup {\"key\" \"pi\"}) \"result\"))"}
                           :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}
                          ;; Second: finalize via (final! {...})
                          {:outputs {:code "(final! {:answer \"pi is 3.14\"})"}
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
      (with-redefs [dscloj/predict
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
    (with-redefs [dscloj/predict
                  (fn [_provider _module _inputs _opts]
                    {:outputs {:code ""}
                     :usage {:prompt_tokens 10 :completion_tokens 0 :total_tokens 10}})]
      (let [result (executor/execute-repl-researcher
                     test-node test-blackboard :test test-context)]
        (is (= :failure (:status result)))
        (is (str/includes? (:error result) "did not generate code"))))))

(deftest usage-tracking-test
  (testing "usage accumulates across iterations that end in (final! {...})"
    (let [call-count (atom 0)]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _opts]
                      (let [n (swap! call-count inc)]
                        (if (< n 3)
                          {:outputs {:code (clojure.core/str "(println \"step " n "\")")}
                           :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}}
                          {:outputs {:code "(final! {:answer \"done\"})"}
                           :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}})))]
        (let [result (executor/execute-repl-researcher
                       test-node test-blackboard :test test-context)]
          (is (= :success (:status result)))
          (is (= "done" (:answer (:outputs result))))
          (is (= 300 (get-in result [:usage :prompt-tokens])))
          (is (= 150 (get-in result [:usage :completion-tokens])))
          (is (= 450 (get-in result [:usage :total-tokens]))))))))

(deftest nil-call-tool-fn-no-crash-test
  (testing "works without call-tool-fn when code calls (final! {...}) without tools"
    (with-redefs [dscloj/predict
                  (fn [_provider _module _inputs _opts]
                    {:outputs {:code "(final! {:answer \"42\"})"}
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
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _opts]
                      (let [n (swap! call-count inc)]
                        (if (= n 1)
                          {:outputs {:code "(let [issues (linear/list_issues {:project \"abc\"})
                                                  pulls (github/list_pulls {:state \"open\"})]
                                              (println \"Found\" (count (get issues \"items\")) \"issues\")
                                              (println \"Found\" (count (get pulls \"items\")) \"pulls\"))"}
                           :usage {:prompt_tokens 50 :completion_tokens 30 :total_tokens 80}}
                          {:outputs {:code "(final! {:answer \"3 issues and 2 pulls\"})"}
                           :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
        (let [node (assoc test-node :mcp-tools ["linear/list_issues" "github/list_pulls"])
              ctx {:call-tool-fn (fn [tool-name args]
                                   (swap! tool-calls conj {:tool tool-name :args args})
                                   (case tool-name
                                     "linear/list_issues" {"items" ["a" "b" "c"]}
                                     "github/list_pulls"  {"items" ["x" "y"]}
                                     {"error" "unknown"}))
                   :dscloj-provider :test}
              result (executor/execute-repl-researcher
                       node test-blackboard :test ctx)]
          (is (= :success (:status result)))
          (is (= "3 issues and 2 pulls" (:answer (:outputs result))))
          (is (= 2 (count @tool-calls)))
          (is (= #{"linear/list_issues" "github/list_pulls"}
                 (set (map :tool @tool-calls)))))))))
