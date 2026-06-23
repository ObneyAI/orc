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

;; =============================================================================
;; MC-0 fix #1 — :llm-condition MUST request :with-metadata? true
;; =============================================================================
;; REGRESSION: dscloj/predict returns TWO shapes depending on :with-metadata?:
;;   - with :with-metadata? true  → {:outputs {:result true} :usage … :model …}
;;   - WITHOUT it                 → the bare parsed map {:result true}
;; execute-llm-condition reads (get-in response [:outputs :result]). Without the
;; :with-metadata? true flag, dscloj returns the BARE map, so [:outputs :result]
;; is nil → (boolean nil) → the condition ALWAYS evaluates false (every
;; :llm-condition silently failed; this killed all EB9 resilience). This test
;; simulates dscloj's REAL dual-shape behavior and asserts the node returns true.

(def condition-blackboard
  {:claim {:key :claim
           :schema :string
           :value "The sky is blue."
           :version 1}})

(deftest llm-condition-requests-metadata-and-parses-result
  (testing "execute-llm-condition returns the LLM's true answer because it calls
            dscloj/predict with :with-metadata? true (so the {:outputs {:result …}}
            envelope is returned, not the bare parsed map). RED without the flag:
            the bare map yields nil at [:outputs :result] → boolean false."
    (let [captured-options (atom nil)
          node {:type :llm-condition
                :name "is-blue?"
                :model "google/gemini-3-flash-preview"
                :instruction "Is the claim true?"
                :reads [:claim]}]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs options]
                      (reset! captured-options options)
                      ;; Simulate dscloj's REAL dual-shape contract: the metadata
                      ;; envelope ONLY when :with-metadata? true; otherwise bare.
                      (if (:with-metadata? options)
                        {:outputs {:result true}
                         :usage {:prompt_tokens 8 :completion_tokens 1 :total_tokens 9}
                         :model "google/gemini-3-flash-preview"}
                        {:result true}))]
        (let [result (executor/execute-llm-condition
                       node condition-blackboard :openrouter)]
          (is (= :success (:status result)))
          (is (= true (:result result))
              "a clear 'yes' must surface as true — NOT silently false")
          (is (= true (:with-metadata? @captured-options))
              "the executor must request the metadata envelope dscloj returns :result in")))))

  (testing "a clear 'no' surfaces as false (the boolean is genuinely parsed, not
            constant-false)"
    (with-redefs [dscloj/predict
                  (fn [_provider _module _inputs options]
                    (if (:with-metadata? options)
                      {:outputs {:result false}
                       :usage {} :model "google/gemini-3-flash-preview"}
                      {:result false}))]
      (let [node {:type :llm-condition
                  :name "is-green?"
                  :instruction "Is the claim true?"
                  :reads [:claim]}
            result (executor/execute-llm-condition
                     node condition-blackboard :openrouter)]
        (is (= :success (:status result)))
        (is (= false (:result result)))))))

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
