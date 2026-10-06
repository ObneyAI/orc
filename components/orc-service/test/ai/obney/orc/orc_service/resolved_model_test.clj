(ns ai.obney.orc.orc-service.resolved-model-test
  "Generated from invariant ModelLeafRecordsResolvedModel (specs/orc-service.allium):
   every model-backed leaf durably records the model it was configured to use,
   when one was configured, and separately the model the provider reports it
   actually used, when one was reported. Neither stands in for the other, and a
   failed call does not invent a resolved model.

   Every case runs through the public workflow DSL, build and execute, with only
   the provider seam (`llm/predict`) injected. The fake honours the predictor's
   documented dual return shape: bare outputs by default, and the
   {:outputs :usage :model :raw-response} envelope only when asked for
   :with-metadata?."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private requested-model "fixture/requested-chat")
(def ^:private resolved-model "fixture/resolved-chat")

(defn- fake-predict
  "Answers the leaf with {:answer \"ok\"}, reporting `resolved-model`. A
   Throwable in `behaviour` is thrown instead."
  [behaviour]
  (fn [_provider _module _inputs options]
    (when (instance? Throwable behaviour) (throw behaviour))
    (let [outputs {:answer "ok"}]
      (if (:with-metadata? options)
        {:outputs outputs
         :usage {:prompt_tokens 3 :completion_tokens 1 :total_tokens 4}
         :model resolved-model
         :raw-response (pr-str outputs)}
        outputs))))

(defn- leaf-workflow [workflow-name & {:keys [model]}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:claim :string :answer :string})
    (apply sheet/llm "answer"
           (cond-> [:instruction "Answer the claim." :reads [:claim] :writes [:answer]]
             model (conj :model model)))))

(defn- run-workflow [ctx workflow behaviour]
  (with-redefs [llm/predict (fake-predict behaviour)]
    (let [sheet-id (sheet/build-workflow! ctx workflow)]
      (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                     sheet-id {:claim "Two plus two equals four."}))))

(defn- leaf-completion [ctx result]
  (->> (h/read-tick-events ctx (:trace-id result))
       (filter #(and (= :sheet/node-execution-completed (:event/type %))
                     (= :leaf (:node-type %))))
       first))

(defn- leaf-detail [ctx result]
  (let [trace-id (:trace-id result)
        _ (h/settle-until!
           #(some? (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                           [:query/result :trace])))
        trace (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                      [:query/result :trace])
        node-trace (first (filter #(= :leaf (:node-type %)) (:node-traces trace)))]
    (is (some? node-trace) "the leaf appears in the execution trace")
    (:query/result
     (h/run-query ctx {:query/name :sheet/node-trace-detail
                       :trace-id trace-id
                       :trace-instance-id (:trace-instance-id node-trace)}))))

(deftest configured-and-resolved-models-are-both-recorded
  (testing "a leaf with a configured model records requested and resolved separately"
    (h/with-async-test-context [ctx]
      (let [result (run-workflow ctx (leaf-workflow "rm-both" :model requested-model) nil)
            completion (leaf-completion ctx result)
            detail (leaf-detail ctx result)]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (= requested-model (:requested-model completion)))
        (is (= resolved-model (:resolved-model completion)))
        (is (= resolved-model (:model completion)))
        (is (= requested-model (:requested-model detail)))
        (is (= resolved-model (:resolved-model detail)))
        (is (= resolved-model (:model detail)))))))

(deftest provider-default-model-records-resolved-only
  (testing "a leaf with no configured model still records the resolved model, and no requested model"
    (h/with-async-test-context [ctx]
      (let [result (run-workflow ctx (leaf-workflow "rm-default") nil)
            completion (leaf-completion ctx result)
            detail (leaf-detail ctx result)]
        (is (= :success (:status result)))
        (is (= resolved-model (:resolved-model completion)))
        (is (= resolved-model (:model completion)))
        (is (not (contains? completion :requested-model)))
        (is (= resolved-model (:resolved-model detail)))
        (is (not (contains? detail :requested-model)))))))

(deftest failed-call-does-not-invent-a-resolved-model
  (testing "a provider failure keeps the requested model and records no resolved model"
    (h/with-async-test-context [ctx]
      (let [result (run-workflow ctx (leaf-workflow "rm-fail" :model requested-model)
                                 (ex-info "provider down" {:failure-kind :transport-failure}))
            completion (leaf-completion ctx result)
            detail (leaf-detail ctx result)]
        (is (= :failure (:status result)))
        (is (= requested-model (:requested-model completion)))
        (is (not (contains? completion :resolved-model)))
        (is (= requested-model (:model completion)))
        (is (= requested-model (:requested-model detail)))
        (is (not (contains? detail :resolved-model))))))
  (testing "a failure with no configured model records neither"
    (h/with-async-test-context [ctx]
      (let [result (run-workflow ctx (leaf-workflow "rm-fail-default")
                                 (ex-info "provider down" {:failure-kind :transport-failure}))
            completion (leaf-completion ctx result)]
        (is (= :failure (:status result)))
        (is (not (contains? completion :resolved-model)))
        (is (not (contains? completion :requested-model)))))))

(deftest failure-evidence-model-is-the-only-resolved-source-on-failure
  (testing "a provider-reported model on failure evidence is recorded as resolved"
    (h/with-async-test-context [ctx]
      (let [result (run-workflow ctx (leaf-workflow "rm-fail-evidence" :model requested-model)
                                 (ex-info "provider down"
                                          {:failure-kind :transport-failure
                                           :provider-evidence
                                           {:provider "fixture" :model "fixture/evidence-model"}}))
            completion (leaf-completion ctx result)]
        (is (= :failure (:status result)))
        (is (= requested-model (:requested-model completion)))
        (is (= "fixture/evidence-model" (:resolved-model completion)))))))

(deftest condition-and-decision-record-requested-and-resolved
  (testing "a model-backed condition records both fields beside the unchanged :model"
    (h/with-async-test-context [ctx]
      (let [wf (sheet/workflow "rm-condition"
                 (sheet/blackboard {:claim :string :ran :boolean})
                 (sheet/sequence "guarded"
                   (sheet/llm-condition "holds" :instruction "Does the claim hold?"
                                        :reads [:claim] :model requested-model)))
            result (with-redefs [llm/predict
                                 (fn [_ _ _ options]
                                   (if (:with-metadata? options)
                                     {:outputs {:result true} :model resolved-model
                                      :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}
                                      :raw-response "{}"}
                                     {:result true}))]
                     (let [sheet-id (sheet/build-workflow! ctx wf)]
                       (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                      sheet-id {:claim "x"})))
            completion (->> (h/read-tick-events ctx (:trace-id result))
                            (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                          (= :llm-condition (:node-type %))))
                            first)]
        (is (= resolved-model (:model completion)))
        (is (= requested-model (:requested-model completion)))
        (is (= resolved-model (:resolved-model completion))))))
  (testing "a model decision records both fields beside the unchanged :model"
    (h/with-async-test-context [ctx]
      (let [wf (sheet/workflow "rm-decision"
                 (sheet/blackboard {:claim :string :verdict :boolean})
                 (sheet/llm-decision "decide" :instruction "Does the claim hold?"
                                     :reads [:claim] :writes [:verdict]
                                     :model requested-model))
            result (with-redefs [llm/predict
                                 (fn [_ _ _ options]
                                   (if (:with-metadata? options)
                                     {:outputs {:verdict true} :model resolved-model
                                      :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}
                                      :raw-response "{}"}
                                     {:verdict true}))]
                     (let [sheet-id (sheet/build-workflow! ctx wf)]
                       (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                      sheet-id {:claim "x"})))
            completion (->> (h/read-tick-events ctx (:trace-id result))
                            (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                          (= :leaf (:node-type %))))
                            first)]
        (is (= resolved-model (:model completion)))
        (is (= requested-model (:requested-model completion)))
        (is (= resolved-model (:resolved-model completion)))))))
