(ns ai.obney.orc.orc-service.model-backed-condition-test
  "Generated from contract ModelBackedCondition (specs/orc-service.allium):
   ModelConditionPreservesItsAnswer, ModelConditionSharesLeafExecutionPolicy,
   ModelConditionEvidenceIsDurable.

   Every case runs through the public workflow DSL, build and execute, with
   only the provider seam (`llm/predict`) injected. The fake honours the
   predictor's documented dual return shape: bare outputs by default, and the
   {:outputs :usage :model :raw-response} envelope only when the caller asks
   for :with-metadata?."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn mark-ran [_] {:ran true})

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.model-backed-condition-test/" function-name))

(def ^:private resolved-model "resolved/decision-model")

(defn- fake-predict
  "Answers are consumed in order; the last one repeats. An answer may be a
   boolean, ::missing (no :result field), any other value (a malformed
   answer), a Throwable (the provider attempt fails) or a fn of no args
   (called to produce one of the above, e.g. to block)."
  [answers calls]
  (let [remaining (atom answers)]
    (fn [_provider module inputs options]
      (swap! calls conj {:module module :inputs inputs :options options})
      (let [answer (first @remaining)
            _ (swap! remaining #(if (next %) (next %) %))
            answer (if (fn? answer) (answer) answer)]
        (when (instance? Throwable answer) (throw answer))
        (let [outputs (if (= ::missing answer) {} {:result answer})]
          (if (:with-metadata? options)
            {:outputs outputs
             :usage {:prompt_tokens 3 :completion_tokens 1 :total_tokens 4}
             :model resolved-model
             :raw-response (pr-str outputs)}
            outputs))))))

(defn- guarded-workflow
  "A sequence whose action runs only if the model-backed condition holds."
  [workflow-name & {:keys [model conditions] :or {conditions 1}}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:claim :string :ran :boolean})
    (apply sheet/sequence "guarded"
           (concat
            (for [i (range conditions)]
              (apply sheet/llm-condition (str "holds-" i)
                     (cond-> [:instruction "Does the claim hold?" :reads [:claim]]
                       model (conj :model model))))
            [(sheet/code "act" :fn (fq "mark-ran") :writes [:ran])]))))

(def ^:dynamic *linger-ms*
  "How long the provider fake stays installed after `execute` returns, so a
   provider invocation that begins late (after the execution timed out) is
   still recorded rather than reaching the real predictor unobserved."
  0)

(defn- run-workflow
  [ctx workflow answers & execute-options]
  (let [calls (atom [])]
    (with-redefs [llm/predict (fake-predict answers calls)]
      (let [sheet-id (sheet/build-workflow! ctx workflow)
            result (apply sheet/execute
                          (assoc ctx :llm-provider :deterministic-provider)
                          sheet-id {:claim "Two plus two equals four."}
                          execute-options)]
        (when (pos? *linger-ms*) (Thread/sleep *linger-ms*))
        {:result result :calls calls}))))

(defn- condition-completions [ctx result]
  (->> (h/read-tick-events ctx (:trace-id result))
       (filter #(and (= :sheet/node-execution-completed (:event/type %))
                     (= :llm-condition (:node-type %))))
       vec))

(defn- condition-detail [ctx result]
  (let [trace-id (:trace-id result)
        _ (h/settle-until!
           #(some? (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                           [:query/result :trace])))
        trace (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                      [:query/result :trace])
        node-trace (first (filter #(= :llm-condition (:node-type %))
                                  (:node-traces trace)))]
    (is (some? node-trace) "the condition appears in the execution trace")
    (:query/result
     (h/run-query ctx {:query/name :sheet/node-trace-detail
                       :trace-id trace-id
                       :trace-instance-id (:trace-instance-id node-trace)}))))

;; ---------------------------------------------------------------------------
;; ModelConditionPreservesItsAnswer
;; ---------------------------------------------------------------------------

(deftest model-condition-true-answer-succeeds
  (testing "a valid true answer succeeds the condition and the guarded action runs"
    (h/with-async-test-context [ctx]
      (let [{:keys [result calls]}
            (run-workflow ctx (guarded-workflow "mbc-true") [true])]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (true? (get-in result [:outputs :ran])))
        (is (= 1 (count @calls)))))))

(deftest model-condition-false-answer-fails
  (testing "a valid false answer fails the condition as a semantic negative"
    (h/with-async-test-context [ctx]
      (let [{:keys [result]} (run-workflow ctx (guarded-workflow "mbc-false") [false])
            detail (condition-detail ctx result)]
        (is (= :failure (:status result)))
        (is (nil? (get-in result [:outputs :ran])))
        (is (false? (:condition-answer detail))
            "the semantic negative is recorded as the answer false")
        (is (nil? (:failure-kind detail))
            "a semantic negative is not a provider failure")))))

(deftest model-condition-missing-answer-is-not-false
  (testing "a response without an answer fails with a structured failure kind"
    (h/with-async-test-context [ctx]
      (let [{:keys [result]} (run-workflow ctx (guarded-workflow "mbc-missing")
                                           [::missing])
            detail (condition-detail ctx result)]
        (is (= :failure (:status result)))
        (is (keyword? (:failure-kind detail))
            "missing answer is a structured provider failure")
        (is (not (contains? #{true false} (:condition-answer detail)))
            "no answer is recorded when none was obtained")))))

(deftest model-condition-malformed-answer-is-not-false
  (testing "a non-boolean answer fails with a structured failure kind"
    (h/with-async-test-context [ctx]
      (let [{:keys [result]} (run-workflow ctx (guarded-workflow "mbc-malformed")
                                           ["yes"])
            detail (condition-detail ctx result)]
        (is (= :failure (:status result)))
        (is (keyword? (:failure-kind detail)))
        (is (not (contains? #{true false} (:condition-answer detail))))))))

;; ---------------------------------------------------------------------------
;; ModelConditionSharesLeafExecutionPolicy
;; ---------------------------------------------------------------------------

(deftest model-condition-node-model-reaches-provider
  (testing "a per-node model choice reaches the provider invocation"
    (h/with-async-test-context [ctx]
      (let [{:keys [result calls]}
            (run-workflow ctx (guarded-workflow "mbc-model" :model "vendor/judge")
                          [true])]
        (is (= :success (:status result)))
        (is (= "vendor/judge" (get-in (first @calls) [:options :model])))))))

(deftest model-condition-default-provider-retry-applies
  (testing "a failed provider attempt is retried by the default provider retry"
    (h/with-async-test-context [ctx]
      (let [{:keys [result calls]}
            (run-workflow ctx (guarded-workflow "mbc-retry")
                          [(ex-info "transient provider failure" {}) true])]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (= 2 (count @calls)))))))

(deftest model-condition-consumes-shared-call-budget
  (testing "condition invocations consume the execution's LLM-call budget"
    (h/with-async-test-context [ctx]
      (let [{:keys [result calls]}
            (run-workflow ctx (guarded-workflow "mbc-budget" :conditions 2)
                          [true] :llm-call-budget 1)]
        (is (= 1 (count @calls))
            "no provider invocation begins once the budget is consumed")
        (is (not= :success (:status result)))
        (is (nil? (get-in result [:outputs :ran])))))))

(deftest model-condition-no-invocation-after-deadline
  (testing "an attempt that outlives the execution deadline is not retried"
    (h/with-async-test-context [ctx]
      (let [{:keys [result calls]}
            (binding [*linger-ms* 2500]
              (run-workflow ctx (guarded-workflow "mbc-deadline")
                            [(fn [] (Thread/sleep 1500)
                               (ex-info "late provider failure" {}))
                             true]
                            :timeout-ms 1000))]
        (is (not= :success (:status result)))
        (Thread/sleep 2500)
        (is (= 1 (count @calls))
            "no further provider invocation begins after the deadline")))))

;; ---------------------------------------------------------------------------
;; ModelConditionEvidenceIsDurable
;; ---------------------------------------------------------------------------

(deftest model-condition-completion-evidence-is-durable
  (testing "the completed condition records reads, answer, model and usage"
    (h/with-async-test-context [ctx]
      (let [{:keys [result]} (run-workflow ctx (guarded-workflow "mbc-evidence") [true])
            [completion] (condition-completions ctx result)
            detail (condition-detail ctx result)]
        (is (= :success (:status result)))
        (is (some? completion))
        (is (= resolved-model (:model completion)))
        (is (= 4 (get-in completion [:usage :total-tokens])))
        (is (true? (:condition-answer completion)))
        (is (= {:claim "Two plus two equals four."} (:inputs detail)))
        (is (true? (:condition-answer detail)))))))

(deftest model-condition-provider-failure-evidence-is-structured
  (testing "an exhausted provider failure records a structured failure kind"
    (h/with-async-test-context [ctx]
      (let [{:keys [result]}
            (run-workflow ctx (guarded-workflow "mbc-provider-failure")
                          [(ex-info "provider down"
                                    {:failure-kind :transport-failure})])
            detail (condition-detail ctx result)]
        (is (= :failure (:status result)))
        (is (= :transport-failure (:failure-kind detail)))))))

(deftest model-condition-requests-structured-output-by-default
  (testing "a boolean answer on a conversational model is requested as a structured response"
    (h/with-async-test-context [ctx]
      (let [{:keys [calls]} (run-workflow ctx (guarded-workflow "mbc-structured") [true])]
        (is (true? (get-in (first @calls) [:options :use-function-calling?])))))))
