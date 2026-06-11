(ns ai.obney.orc.orc-service.executor-options-test
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [clojure.test :refer [deftest is testing]]
            [dscloj.core :as dscloj]))

(def test-blackboard
  {:question {:key :question
              :schema :string
              :value "What is 2+2?"
              :version 1}
   :answer {:key :answer
            :schema :string
            :value nil
            :version 0}})

(deftest llm-dsl-preserves-node-options
  (testing "llm nodes carry per-node executor options"
    (let [node (dsl/llm "answer"
                 :model "qwen/qwen3.7-max"
                 :instruction "Answer the question."
                 :reads [:question]
                 :writes [:answer]
                 :options {:use-function-calling? true})]
      (is (= {:use-function-calling? true} (:options node))))))

(deftest build-workflow-persists-node-options
  (testing "workflow build stores llm options on persisted sheet nodes"
    (h/with-async-test-context [ctx]
      (let [wf (sheet/workflow "executor-options"
                 (sheet/blackboard {:question :string
                                    :answer :string})
                 (sheet/llm "answer"
                   :model "qwen/qwen3.7-max"
                   :instruction "Answer the question."
                   :reads [:question]
                   :writes [:answer]
                   :options {:use-function-calling? true}))
            sheet-id (sheet/build-workflow! ctx wf)
            llm-node (first (filter #(= "answer" (:name %))
                                    (sheet/get-nodes-for-sheet ctx sheet-id)))]
        (is (= {:use-function-calling? true} (:options llm-node)))))))

(deftest execute-ai-preserves-explicit-function-calling-option
  (testing "node options override tick options and are passed to DSCloj"
    (let [captured-options (atom nil)
          node {:type :leaf
                :executor :ai
                :model "qwen/qwen3.7-max"
                :instruction "Answer the question."
                :reads [:question]
                :writes [:answer]
                :options {:use-function-calling? true}}]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs options]
                      (reset! captured-options options)
                      {:outputs {:answer "4"}
                       :usage {:prompt_tokens 10
                               :completion_tokens 2
                               :total_tokens 12}})]
        (let [result (executor/execute-leaf
                       node test-blackboard :openrouter
                       :options {:use-function-calling? false})]
          (is (= :success (:status result)))
          (is (= "4" (get-in result [:outputs :answer])))
          (is (= false (:validate? @captured-options)))
          (is (= true (:with-metadata? @captured-options)))
          (is (= true (:use-function-calling? @captured-options)))))))

  (testing "marker parsing remains the default when no option is provided"
    (let [captured-options (atom nil)
          node {:type :leaf
                :executor :ai
                :model "google/gemini-2.5-flash"
                :instruction "Answer the question."
                :reads [:question]
                :writes [:answer]}]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs options]
                      (reset! captured-options options)
                      {:outputs {:answer "4"}})]
        (let [result (executor/execute-leaf node test-blackboard :openrouter)]
          (is (= :success (:status result)))
          (is (= false (:use-function-calling? @captured-options))))))))

(deftest repl-researcher-preserves-node-options
  (testing "repl-researcher node options are passed to its DSCloj call"
    (let [captured-options (atom nil)
          node {:type :repl-researcher
                :name "research"
                :model "qwen/qwen3.7-max"
                :instruction "Find the answer."
                :reads [:question]
                :writes [:answer]
                :max-iterations 1
                :options {:use-function-calling? true}}]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs options]
                      (reset! captured-options options)
                      {:outputs {:code "(println \"FINAL_ANSWER: 4\")"}})]
        (let [result (executor/execute-repl-researcher node test-blackboard :openrouter {})]
          (is (= :success (:status result)))
          (is (= "4" (get-in result [:outputs :answer])))
          (is (= true (:use-function-calling? @captured-options))))))))
