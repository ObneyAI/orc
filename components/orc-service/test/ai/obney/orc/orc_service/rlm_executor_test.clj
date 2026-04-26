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

;; =============================================================================
;; Nested-map schema flattening (DSCloj bug repro + fix)
;; =============================================================================

(deftest predict-with-map-schema-flattens-and-reassembles-test
  (testing "(predict {:schema [:map …]}) — top-level fields are flattened to
            separate DSCloj outputs, then reassembled into a structured map for
            the SCI caller. Without this fix, DSCloj receives a single nested
            spec and returns mangled data (:total as a map instead of a double)."
    (let [calls (atom 0)
          ;; SCI code calls (predict {:schema [:map …]}) and asserts the result
          ;; is a real map with concrete values pulled by key.
          code "(let [r (predict {:name \"extract\"
                                   :instructions \"extract\"
                                   :inputs {:text \"INVOICE Acme Total $100\"}
                                   :schema [:map
                                            [:vendor-name :string]
                                            [:total :double]]})]
                  (final! {:answer (str (:vendor-name r) \" total=\" (:total r))}))"]
      (with-redefs [dscloj/predict
                    (fn [_p module _inputs _opts]
                      (let [n (swap! calls inc)]
                        (if (= 1 n)
                          ;; First call: code generation — return the SCI code
                          {:outputs {:code code}
                           :usage {:prompt-tokens 5 :completion-tokens 5 :total-tokens 10}}
                          ;; Second call: the (predict {…}) inside SCI.
                          (do
                            ;; Assert the executor passed FLATTENED outputs to DSCloj.
                            (let [out-names (set (mapv :name (:outputs module)))]
                              (is (= #{:vendor-name :total} out-names)
                                  "make-rlm-predict-fn must flatten top-level :map fields into separate DSCloj outputs"))
                            ;; Return what DSCloj would return after flattening:
                            ;; flat fields, each cleanly typed.
                            {:outputs {:vendor-name "Acme" :total 100.0}
                             :usage {:prompt-tokens 7 :completion-tokens 3 :total-tokens 10}}))))]
        (let [result (executor/execute-repl-researcher
                       (rlm-node)
                       (bb "ctx" "q")
                       :test
                       {})]
          (is (= :success (:status result)))
          (is (= "Acme total=100.0" (-> result :outputs :answer))
              "predict should reassemble flat DSCloj outputs back into a structured map keyed by :vendor-name and :total"))))))

(deftest predict-with-scalar-schema-still-works-test
  (testing "Non-map schemas (the existing predict-fn-callable-test case) are
            untouched by the flatten-then-reassemble fix — single output, no
            field decomposition, identical wire shape to before."
    (let [calls (atom 0)
          code "(let [r (predict {:name \"classify\"
                                   :instructions \"Classify input\"
                                   :inputs {:line \"hello\"}
                                   :schema :string})]
                  (final! {:answer r}))"]
      (with-redefs [dscloj/predict
                    (fn [_p module _inputs _opts]
                      (let [n (swap! calls inc)]
                        (if (= 1 n)
                          {:outputs {:code code}
                           :usage {:prompt-tokens 5 :completion-tokens 5 :total-tokens 10}}
                          (do
                            ;; Scalar schema: still a single output named :result
                            (let [out-names (mapv :name (:outputs module))]
                              (is (= [:result] out-names)
                                  "Non-map schema should remain a single :result output"))
                            {:outputs {:result "classified"}
                             :usage {:prompt-tokens 7 :completion-tokens 3 :total-tokens 10}}))))]
        (let [result (executor/execute-repl-researcher
                       (rlm-node)
                       (bb "ctx" "q")
                       :test
                       {})]
          (is (= :success (:status result)))
          (is (= "classified" (-> result :outputs :answer))
              "Scalar predict still returns the bare value (not wrapped)"))))))

;; =============================================================================
;; Pre-execution validation in the RLM iteration loop
;; =============================================================================

(deftest pre-execution-syntax-error-surfaces-in-history-test
  (testing "When the LLM emits unbalanced parens, the iteration loop reports a
            structured 'SYNTAX ERROR' rejection BEFORE running SCI eval. The
            iteration history records it so the next iteration prompt can
            adapt."
    (let [calls (atom 0)]
      (with-redefs [dscloj/predict
                    (fn [_p _module _i _o]
                      (let [n (swap! calls inc)]
                        (cond
                          ;; First call: emit unbalanced code
                          (= 1 n)
                          {:outputs {:code "(let [x 1 y 2] (+ x y"}
                           :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}}
                          ;; Second call: emit valid final!
                          :else
                          {:outputs {:code "(final! {:answer \"recovered\"})"}
                           :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}})))]
        (let [node {:type :repl-researcher :name "test"
                    :instruction "do" :reads [:q] :writes [:answer]
                    :mcp-tools [] :max-iterations 4 :model "test-model"
                    :rlm {:enabled? true :max-predict-calls 10}}
              bb {:q      {:key :q :schema :string :value "?" :version 1}
                  :answer {:key :answer :schema :string :value nil :version 0}}
              result (executor/execute-repl-researcher node bb :test {})
              first-iter (first (:iterations result))]
          (is (= :success (:status result))
              "Loop should recover after the syntax-error iteration")
          (is (string? (:error first-iter))
              "Iteration 1 should record an error string")
          (is (re-find #"SYNTAX ERROR" (:error first-iter))
              "Pre-execution rejection should be tagged 'SYNTAX ERROR' for LLM clarity")
          (is (= "recovered" (-> result :outputs :answer))))))))

(deftest pre-execution-sandbox-escape-rejected-test
  (testing "Code referencing System/* or other off-sandbox symbols is rejected
            BEFORE eval, with a 'SANDBOX ESCAPE' message naming the offending
            symbols."
    (let [calls (atom 0)]
      (with-redefs [dscloj/predict
                    (fn [_p _module _i _o]
                      (let [n (swap! calls inc)]
                        (cond
                          (= 1 n)
                          {:outputs {:code "(let [t (System/currentTimeMillis)] (println t))"}
                           :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}}
                          :else
                          {:outputs {:code "(final! {:answer \"recovered\"})"}
                           :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}})))]
        (let [node {:type :repl-researcher :name "test"
                    :instruction "do" :reads [:q] :writes [:answer]
                    :mcp-tools [] :max-iterations 4 :model "test-model"
                    :rlm {:enabled? true :max-predict-calls 10}}
              bb {:q      {:key :q :schema :string :value "?" :version 1}
                  :answer {:key :answer :schema :string :value nil :version 0}}
              result (executor/execute-repl-researcher node bb :test {})
              first-iter (first (:iterations result))]
          (is (= :success (:status result)))
          (is (re-find #"SANDBOX ESCAPE" (:error first-iter)))
          (is (re-find #"System/currentTimeMillis" (:error first-iter))
              "The specific offending symbol should appear in the error message")
          (is (re-find #"Java interop \(disallowed\)" (:error first-iter))
              "Java-interop refs should be flagged with their own subheading")
          (is (= "recovered" (-> result :outputs :answer))))))))

(deftest pre-execution-recognizes-host-bindings-test
  (testing "Code that uses predict / final! / context / inputs (RLM extra-bindings)
            is NOT rejected — those symbols are bound by the executor."
    (with-redefs [dscloj/predict
                  (fn [_p _module _i _o]
                    {:outputs {:code "(final! {:answer (str \"q=\" question)})"}
                     :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}})]
      (let [node {:type :repl-researcher :name "test"
                  :instruction "do" :reads [:question] :writes [:answer]
                  :mcp-tools [] :max-iterations 2 :model "test-model"
                  :rlm {:enabled? true :max-predict-calls 10}}
            bb {:question {:key :question :schema :string :value "hi" :version 1}
                :answer   {:key :answer   :schema :string :value nil :version 0}}
            result (executor/execute-repl-researcher node bb :test {})]
        (is (= :success (:status result)))
        ;; If pre-validation incorrectly rejected `question` or `final!`,
        ;; we'd see a SANDBOX ESCAPE error in the only iteration.
        (let [first-iter (first (:iterations result))]
          (is (nil? (:error first-iter))
              "Bound symbols (question, final!) should pass pre-validation"))
        (is (= "q=hi" (-> result :outputs :answer)))))))

;; =============================================================================
;; Convergence-detector classification (error-repeat vs output-repeat)
;; =============================================================================

(deftest convergence-error-repeat-distinguished-test
  (testing "When two iterations crash identically, the failure message
            surfaces the underlying error (not the generic 'Output repeated')"
    (let [calls (atom 0)
          ;; Both iterations emit the same code that causes the same SCI error
          ;; (calling slurp — flagged by pre-validation). The convergence
          ;; detector should classify this as :error and surface a message
          ;; mentioning the actual error.
          bad-code "(slurp \"/tmp/x\")"]
      (with-redefs [dscloj/predict
                    (fn [_p _module _i _o]
                      (swap! calls inc)
                      {:outputs {:code bad-code}
                       :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}})]
        (let [node {:type :repl-researcher :name "test"
                    :instruction "do" :reads [:q] :writes [:answer]
                    :mcp-tools [] :max-iterations 4 :model "test-model"
                    :rlm {:enabled? true :max-predict-calls 10}}
              bb {:q      {:key :q :schema :string :value "?" :version 1}
                  :answer {:key :answer :schema :string :value nil :version 0}}
              result (executor/execute-repl-researcher node bb :test {})]
          (is (= :failure (:status result)))
          (is (= :error (-> result :rlm :repeat-kind))
              "Convergence detector should classify identical errors as :error kind")
          (is (re-find #"SAME ERROR" (:error result))
              "Failure message should explicitly say SAME ERROR (not generic 'Output repeated')")
          (is (re-find #"SANDBOX ESCAPE|slurp" (:error result))
              "Failure message should include the underlying rejection reason"))))))

(deftest convergence-output-repeat-distinguished-test
  (testing "When two iterations produce the same successful output without
            calling (final!), the convergence message says SAME OUTPUT
            (not SAME ERROR), pointing the LLM at the missing final! call"
    (let [calls (atom 0)
          ;; Both iterations succeed but neither calls (final! …) — they just
          ;; produce a stable expression value
          stable-code "(println \"hello\") 42"]
      (with-redefs [dscloj/predict
                    (fn [_p _module _i _o]
                      (swap! calls inc)
                      {:outputs {:code stable-code}
                       :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}})]
        (let [node {:type :repl-researcher :name "test"
                    :instruction "do" :reads [:q] :writes [:answer]
                    :mcp-tools [] :max-iterations 4 :model "test-model"
                    :rlm {:enabled? true :max-predict-calls 10}}
              bb {:q      {:key :q :schema :string :value "?" :version 1}
                  :answer {:key :answer :schema :string :value nil :version 0}}
              result (executor/execute-repl-researcher node bb :test {})]
          (is (= :failure (:status result)))
          (is (= :output (-> result :rlm :repeat-kind))
              "Convergence detector should classify identical successful output as :output kind")
          (is (re-find #"SAME OUTPUT" (:error result)))
          (is (re-find #"final!" (:error result))
              "Message should remind the LLM to wrap its result in (final! …)"))))))

;; =============================================================================
;; SCI validation/discovery helpers (check-shape, describe, ns-explore)
;; =============================================================================

(deftest check-shape-helper-test
  (testing "(check-shape …) validates without coercing — returns {:ok? true} or
            {:ok? false :errors […]}, never silently converts"
    (let [code "(let [r (check-shape [:map [:total :double]] {:total 100.0})]
                  (final! {:answer (str (:ok? r))}))"]
      (with-redefs [dscloj/predict
                    (fn [_p _module _i _o]
                      {:outputs {:code code}
                       :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}})]
        (let [node {:type :repl-researcher :name "test"
                    :instruction "do" :reads [:q] :writes [:answer]
                    :mcp-tools [] :max-iterations 2 :model "test-model"
                    :rlm {:enabled? true :max-predict-calls 10}}
              bb {:q      {:key :q :schema :string :value "?" :version 1}
                  :answer {:key :answer :schema :string :value nil :version 0}}
              result (executor/execute-repl-researcher node bb :test {})]
          (is (= :success (:status result)))
          (is (= "true" (-> result :outputs :answer)))))))

  (testing "(check-shape …) flags shape mismatches with structured errors"
    (let [code "(let [r (check-shape [:map [:total :double]] {:total \"not-a-number\"})]
                  (final! {:answer (str (:ok? r) \" errors:\" (count (:errors r)))}))"]
      (with-redefs [dscloj/predict
                    (fn [_p _module _i _o]
                      {:outputs {:code code}
                       :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}})]
        (let [node {:type :repl-researcher :name "test"
                    :instruction "do" :reads [:q] :writes [:answer]
                    :mcp-tools [] :max-iterations 2 :model "test-model"
                    :rlm {:enabled? true :max-predict-calls 10}}
              bb {:q      {:key :q :schema :string :value "?" :version 1}
                  :answer {:key :answer :schema :string :value nil :version 0}}
              result (executor/execute-repl-researcher node bb :test {})]
          (is (= :success (:status result)))
          (is (re-find #"false errors:" (-> result :outputs :answer))))))))

(deftest describe-helper-test
  (testing "(describe …) returns inspection metadata without mutating the value"
    (let [code "(let [d1 (describe \"hello\")
                      d2 (describe {:a 1 :b 2})
                      d3 (describe [1 2 3])]
                  (final! {:answer (str (:type d1) \"|\" (:type d2) \"|\" (:type d3))}))"]
      (with-redefs [dscloj/predict
                    (fn [_p _module _i _o]
                      {:outputs {:code code}
                       :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}})]
        (let [node {:type :repl-researcher :name "test"
                    :instruction "do" :reads [:q] :writes [:answer]
                    :mcp-tools [] :max-iterations 2 :model "test-model"
                    :rlm {:enabled? true :max-predict-calls 10}}
              bb {:q      {:key :q :schema :string :value "?" :version 1}
                  :answer {:key :answer :schema :string :value nil :version 0}}
              result (executor/execute-repl-researcher node bb :test {})]
          (is (= :success (:status result)))
          (is (= ":string|:map|:vector" (-> result :outputs :answer))))))))

(deftest ns-explore-helper-test
  (testing "(ns-explore \"…\") lists tools available under a namespace prefix"
    (let [code "(final! {:answer (clojure.string/join \",\" (ns-explore \"pdf\"))})"]
      (with-redefs [dscloj/predict
                    (fn [_p _module _i _o]
                      {:outputs {:code code}
                       :usage {:prompt-tokens 1 :completion-tokens 1 :total-tokens 2}})]
        (let [node {:type :repl-researcher :name "test"
                    :instruction "do" :reads [:q] :writes [:answer]
                    :mcp-tools ["pdf/page-count" "pdf/page-text" "xlsx/write-workbook"]
                    :max-iterations 2 :model "test-model"
                    :rlm {:enabled? true :max-predict-calls 10}}
              bb {:q      {:key :q :schema :string :value "?" :version 1}
                  :answer {:key :answer :schema :string :value nil :version 0}}
              result (executor/execute-repl-researcher node bb :test {})]
          (is (= :success (:status result)))
          (is (= "page-count,page-text" (-> result :outputs :answer))
              "ns-explore should return only tools in the requested namespace, sorted"))))))

;; =============================================================================
;; Value Preview Truncation
;; =============================================================================
;;
;; The root LM sees a structured preview map for each blackboard variable.
;; Long values use head + tail truncation (matching dspy/predict-rlm) so the
;; LM gets both the start (schema/structure) and the end (recent state).

(deftest value-preview-short-string-not-truncated-test
  (testing "values shorter than the budget are returned verbatim, no marker"
    (let [vp #'executor/value-preview
          result (vp "hello world" 100)]
      (is (= "hello world" (:preview result)))
      (is (= 11 (:size result)))
      (is (= :string (:type result)))
      (is (not (clojure.string/includes? (:preview result) "…"))
          "no truncation marker when value fits"))))

(deftest value-preview-long-string-uses-head-tail-test
  (testing "long strings show both head and tail with omitted-count marker"
    (let [vp #'executor/value-preview
          long-s (str (apply str (repeat 100 \A))
                      (apply str (repeat 100 \B))
                      (apply str (repeat 100 \C)))
          result (vp long-s 80)]
      (is (= 300 (:size result)))
      (is (clojure.string/starts-with? (:preview result) "A")
          "preview includes head")
      (is (clojure.string/ends-with? (:preview result) "C")
          "preview includes tail")
      (is (clojure.string/includes? (:preview result) "chars omitted")
          "preview includes truncation marker with omitted count")
      (is (<= (count (:preview result)) 80)
          "preview stays within the char budget"))))

(deftest value-preview-vector-tail-shown-test
  (testing "vectors show keys/items from the END as well as the start"
    (let [vp #'executor/value-preview
          ;; pr-str of this vector is ~120 chars; tail-only truncation would hide :z
          v [:apple :banana :cherry :date :eggplant :fig :grape
             :honeydew :iceberg :jicama :kiwi :lemon :mango :nectarine
             :orange :papaya :quince :raspberry :strawberry :tomato
             :ugli :vanilla :watermelon :xigua :yam :zucchini]
          result (vp v 80)]
      (is (= :vector (:type result)))
      (is (= 26 (:count result)))
      (is (clojure.string/includes? (:preview result) "apple")
          "preview includes head item")
      (is (clojure.string/includes? (:preview result) "zucchini")
          "preview includes tail item — the whole point of head+tail")
      (is (clojure.string/includes? (:preview result) "chars omitted")
          "marker present when truncated"))))

(deftest value-preview-map-keys-still-listed-test
  (testing "maps expose :keys directly even when preview is truncated"
    (let [vp #'executor/value-preview
          m (into {} (for [i (range 20)] [(keyword (str "k" i)) (str "value-" i)]))
          result (vp m 60)]
      (is (= :map (:type result)))
      (is (= 20 (count (:keys result))))
      (is (some #{:k0} (:keys result)))
      (is (some #{:k19} (:keys result))
          ":keys gives the LM full structural shape regardless of preview budget"))))

(deftest value-preview-tiny-budget-falls-back-to-head-test
  (testing "very small budgets degrade to head-only (marker would exceed budget)"
    (let [vp #'executor/value-preview
          long-s (apply str (repeat 100 \X))
          result (vp long-s 10)]
      (is (= 10 (count (:preview result))))
      (is (= "XXXXXXXXXX" (:preview result))
          "tiny budget = head-only; no marker"))))
