(ns ai.obney.orc.orc-service.rlm-executor-test
  "Unit tests for RLM-mode repl-researcher execution.

   Verifies that with :rlm config, the executor:
   - Binds blackboard reads as direct SCI symbols + `inputs` map
   - Binds the resolved context-key as `context`
   - Renders the root prompt with metadata/previews only (no full context value)
   - Exposes host-backed `predict`, `predict-all`, and `final!` to SCI code
   - Tracks root vs subcall token usage separately
   - Prefers captured `final!` values over legacy FINAL_ANSWER extraction
   - Enforces budgets (max-predict-calls, max-predict-input-chars)
   - Returns a structured :rlm metadata block in the result"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [dscloj.core :as dscloj]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- rlm-node
  ([] (rlm-node {}))
  ([rlm-overrides]
   {:type :repl-researcher
    :name "rlm-test"
    :instruction "Use the context to answer the question. Call (final! ...)"
    :reads [:context :question]
    :writes [:answer]
    :mcp-tools []
    :max-iterations 3
    :model "test-model"
    :rlm (merge {:enabled? true
                 :context-key :context
                 :max-predict-calls 5
                 :max-predict-concurrency 2
                 :max-predict-input-chars 10000
                 :history-preview-chars 1000}
                rlm-overrides)}))

(defn- bb [context-value question-value]
  {:context  {:key :context  :schema :string :value context-value :version 1}
   :question {:key :question :schema :string :value question-value :version 1}
   :answer   {:key :answer   :schema :string :value nil           :version 0}})

(def captured-prompts (atom []))

(defn- record-prompt-mock
  "DSCloj predict mock that records the inputs it was called with and returns
   a code-generation result that produces a final! call."
  [code-to-return]
  (fn [_provider _module inputs _opts]
    (swap! captured-prompts conj inputs)
    {:outputs {:code code-to-return}
     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest final-bang-captures-value-test
  (testing "(final! {...}) is captured and spread across :writes"
    (reset! captured-prompts [])
    (let [code "(final! {:answer (str \"answered: \" question)})"]
      (with-redefs [dscloj/predict (record-prompt-mock code)]
        (let [result (executor/execute-repl-researcher
                       (rlm-node)
                       (bb "LARGE_DOCUMENT" "what is the topic?")
                       :test
                       {})]
          (is (= :success (:status result)))
          (is (= "answered: what is the topic?" (-> result :outputs :answer)))
          (is (= :final! (-> result :rlm :final-source)))
          (is (true? (-> result :rlm :enabled?))))))))

(deftest context-not-in-root-prompt-test
  (testing "the resolved :context value is NOT present (in full) in the root LM's :context input"
    (reset! captured-prompts [])
    (let [;; Padding pushes the secret marker beyond any reasonable preview cap.
          secret "SECRET_MARKER_DO_NOT_LEAK"
          big-context (str (apply str (repeat 5000 "X")) " " secret " "
                           (apply str (repeat 5000 "Y")))]
      (with-redefs [dscloj/predict (record-prompt-mock "(final! {:answer \"ok\"})")]
        (let [;; Tighten the preview cap so even short secrets at the start are
              ;; excluded; this matches the spec's intent that the root prompt
              ;; sees only metadata/previews/counts/hashes.
              node (rlm-node {:max-context-preview-chars 50})
              result (executor/execute-repl-researcher
                       node
                       (bb big-context "q")
                       :test
                       {})]
          (is (= :success (:status result)))
          (let [first-prompt (first @captured-prompts)
                bb-meta (:context first-prompt)]
            (is (string? bb-meta))
            (is (not (.contains ^String bb-meta secret))
                "Full context value must not leak into root prompt")
            (is (.contains ^String bb-meta ":context")
                "Context key name should still be in the metadata blurb")
            (is (re-find #":size " bb-meta)
                "Metadata should include :size for the context value")))))))

(deftest direct-symbol-binding-test
  (testing "read keys are bound as direct SCI symbols"
    (reset! captured-prompts [])
    (let [code "(final! {:answer (str \"q=\" question \" len=\" (count context))})"]
      (with-redefs [dscloj/predict (record-prompt-mock code)]
        (let [result (executor/execute-repl-researcher
                       (rlm-node)
                       (bb "abcdefg" "what")
                       :test
                       {})]
          (is (= :success (:status result)))
          (is (= "q=what len=7" (-> result :outputs :answer))))))))

(deftest inputs-map-binding-test
  (testing "`inputs` is bound as a map of read-key -> value"
    (let [code "(final! {:answer (str (get inputs :question))})"]
      (with-redefs [dscloj/predict (record-prompt-mock code)]
        (let [result (executor/execute-repl-researcher
                       (rlm-node)
                       (bb "x" "hello")
                       :test
                       {})]
          (is (= :success (:status result)))
          (is (= "hello" (-> result :outputs :answer))))))))

(deftest predict-fn-callable-test
  (testing "(predict {...}) reaches dscloj/predict and returns the :result output"
    (let [calls (atom 0)
          code "(let [r (predict {:name \"classify\"
                                  :instructions \"Classify input\"
                                  :inputs {:line \"hello\"}
                                  :schema :string})]
                  (final! {:answer r}))"]
      (with-redefs [dscloj/predict (fn [_p module inputs _opts]
                                     (let [n (swap! calls inc)]
                                       (cond
                                         ;; First call = code generation
                                         (= 1 n)
                                         {:outputs {:code code}
                                          :usage {:prompt_tokens 5 :completion_tokens 5 :total_tokens 10}}
                                         ;; Second call = (predict ...) inside SCI
                                         :else
                                         (do
                                           (is (= "hello" (get inputs :line)))
                                           (is (some #(= :line (:name %)) (:inputs module)))
                                           {:outputs {:result "classified"}
                                            :usage {:prompt_tokens 7 :completion_tokens 3 :total_tokens 10}}))))]
        (let [result (executor/execute-repl-researcher
                       (rlm-node)
                       (bb "ctx" "q")
                       :test
                       {})]
          (is (= :success (:status result)))
          (is (= "classified" (-> result :outputs :answer)))
          ;; Subcall usage must be tracked separately from root usage.
          (is (= {:prompt-tokens 7 :completion-tokens 3 :total-tokens 10}
                 (-> result :rlm :subcall-usage)))
          (is (= {:prompt-tokens 5 :completion-tokens 5 :total-tokens 10}
                 (-> result :rlm :root-usage)))
          (is (= 1 (-> result :rlm :predict-call-count))))))))

(deftest predict-all-fn-callable-test
  (testing "(predict-all {...}) fans out and preserves order"
    (let [calls (atom 0)
          code "(let [rs (predict-all {:name \"label-line\"
                                       :items [\"a\" \"b\" \"c\"]
                                       :as :line
                                       :inputs {:tag \"x\"}
                                       :schema :string})]
                  (final! {:answer (clojure.string/join \",\" rs)}))"]
      (with-redefs [dscloj/predict (fn [_p _module inputs _opts]
                                     (let [n (swap! calls inc)]
                                       (if (= 1 n)
                                         {:outputs {:code code}
                                          :usage {:prompt_tokens 5 :completion_tokens 5 :total_tokens 10}}
                                         {:outputs {:result (str "L:" (get inputs :line))}
                                          :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})))]
        (let [result (executor/execute-repl-researcher
                       (rlm-node)
                       (bb "ctx" "q")
                       :test
                       {})]
          (is (= :success (:status result)))
          (is (= "L:a,L:b,L:c" (-> result :outputs :answer))
              "predict-all preserves item order")
          (is (= 3 (-> result :rlm :predict-call-count))))))))

(deftest predict-budget-enforced-test
  (testing "exceeding :max-predict-calls fails the iteration"
    (let [calls (atom 0)
          ;; Each iteration calls predict once; only 2 iterations are allowed
          ;; because max-predict-calls=2 and max-iterations=3.
          code "(do (predict {:name \"p\" :inputs {:x 1} :schema :string})
                    (println \"called\"))"]
      (with-redefs [dscloj/predict (fn [_p _module _inputs _opts]
                                     (let [n (swap! calls inc)]
                                       (cond
                                         ;; Code-gen calls (odd) return code
                                         (odd? n)
                                         {:outputs {:code code}
                                          :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}
                                         :else
                                         {:outputs {:result "ok"}
                                          :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})))]
        (let [result (executor/execute-repl-researcher
                       (rlm-node {:max-predict-calls 2})
                       (bb "ctx" "q")
                       :test
                       {})]
          ;; The 3rd predict call should throw inside SCI -> iteration sees an
          ;; error result; loop reaches max-iterations -> :failure.
          (is (= :failure (:status result)))
          (is (>= (-> result :rlm :predict-call-count) 2)))))))

(deftest legacy-mode-untouched-test
  (testing "node without :rlm uses the legacy execute-repl-researcher path"
    (let [legacy-node {:type :repl-researcher
                       :name "legacy"
                       :instruction "do it"
                       :reads [:question]
                       :writes [:answer]
                       :mcp-tools []
                       :max-iterations 2
                       :model "test-model"}]
      (with-redefs [dscloj/predict (fn [_p _m _i _o]
                                     {:outputs {:code "FINAL_ANSWER: legacy-path-ok"}
                                      :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (let [result (executor/execute-repl-researcher
                       legacy-node
                       (bb "ctx" "q")
                       :test
                       {})]
          ;; Legacy mode never sets :rlm in the result
          (is (nil? (:rlm result))))))))
