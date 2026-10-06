(ns ai.obney.orc.orc-service.model-decision-protocol-test
  "Workflow-level integration of contract ModelDecision (orc-service) with
   contract DecisionProtocolPrediction (llm): an `sheet/llm-decision` executed
   over a provider registered with :protocol :decision. Nothing above the
   network is faked — the provider is registered through the public llm
   boundary with an injected decision transport, so request construction,
   validation, metadata and the leaf pipeline all run for real."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private route-descriptions
  {"lookup" "Retrieve an existing catalog fact."
   "research" "Investigate a question that needs new evidence."
   "clarify" "The request is too ambiguous to act on; ask the user."})

(defn- choice-response [choice probabilities confidence]
  {:id "dec-1" :model "typesafe/jev-1.13-20260917" :provider "TypeSafe"
   :answers {"route" (cond-> {"type" "choice" "choice" choice}
                       probabilities (assoc "probabilities" probabilities)
                       confidence (assoc "confidence" confidence))}
   :usage {"input_tokens" 300 "output_tokens" 30 "cost" 0.00002}})

(defn- register-jev! [provider-name reply requests]
  (llm/register-provider!
   provider-name
   {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
    :config {:api-key "dummy-not-a-key"
             :decision-transport (fn [request] (swap! requests conj request) reply)}}))

(defn- workflow [workflow-name & {:as decision-options}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:request :string
                       :route (into [:enum {:descriptions route-descriptions}]
                                    (keys route-descriptions))})
    (apply sheet/llm-decision "route"
           (mapcat identity (merge {:instruction "Which operation addresses the request?"
                                    :reads [:request] :writes [:route]}
                                   decision-options)))))

(defn- run!
  "The async processors capture :llm-provider when the context starts, so the
   provider is fixed at :wf-jev and re-registered per case."
  [ctx _provider wf]
  (let [sheet-id (sheet/build-workflow! ctx wf)]
    (sheet/execute ctx sheet-id
                   {:request "Q3 revenue from the board report"} :timeout-ms 20000)))

(defn- decision-record [ctx result]
  (:decision (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                  (some? (:decision %)))
                            (h/read-tick-events ctx (:trace-id result))))))

(def ^:private probabilities {"lookup" 0.98 "clarify" 0.02 "research" 0})

(deftest decision-over-a-decision-model-carries-option-meanings
  (h/with-async-test-context [ctx {:context {:llm-provider :wf-jev}}]
    (let [requests (atom [])]
      (register-jev! :wf-jev (choice-response "lookup" probabilities 0.96) requests)
      (let [result (run! ctx :wf-jev (workflow "wf-jev-meaning"))
            question (get-in (first @requests) [:questions "route"])]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (= "lookup" (get-in result [:outputs :route])))
        (is (= "choice" (:type question)))
        (is (= route-descriptions (:criteria question))
            "each option's meaning is the decision model's criterion for it")))))

(deftest node-model-reaches-the-decision-model
  (h/with-async-test-context [ctx {:context {:llm-provider :wf-jev}}]
    (let [requests (atom [])]
      (register-jev! :wf-jev (choice-response "lookup" probabilities 0.96) requests)
      (run! ctx :wf-jev (workflow "wf-jev-model" :model "typesafe/jev-1.13-20260917"))
      (is (= "typesafe/jev-1.13-20260917" (:model (first @requests)))))))

(deftest reported-confidence-reaches-the-record-and-the-floor
  (h/with-async-test-context [ctx {:context {:llm-provider :wf-jev}}]
    (testing "confidence above the floor writes the answer and is recorded"
      (register-jev! :wf-jev (choice-response "lookup" probabilities 0.96) (atom []))
      (let [result (run! ctx :wf-jev (workflow "wf-jev-high"
                                                    :min-confidence 0.6 :abstain "clarify"))
            record (decision-record ctx result)]
        (is (= "lookup" (get-in result [:outputs :route])))
        (is (false? (:abstained? record)))
        (is (= 0.96 (:confidence record)))
        (is (= probabilities (:probabilities record)))))
    (testing "confidence below the floor abstains and sets the answer aside"
      (register-jev! :wf-jev
                     (choice-response "research" {"lookup" 0.3 "research" 0.4 "clarify" 0.3} 0.1)
                     (atom []))
      (let [result (run! ctx :wf-jev (workflow "wf-jev-low"
                                                   :min-confidence 0.6 :abstain "clarify"))
            record (decision-record ctx result)]
        (is (= "clarify" (get-in result [:outputs :route])))
        (is (true? (:abstained? record)))
        (is (= "research" (:set-aside record)))
        (is (= 0.1 (:confidence record)))))))

(deftest decision-model-usage-is-recorded
  (h/with-async-test-context [ctx {:context {:llm-provider :wf-jev}}]
    (register-jev! :wf-jev (choice-response "lookup" probabilities 0.96) (atom []))
    (let [result (run! ctx :wf-jev (workflow "wf-jev-usage"))
          completion (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                          (some? (:decision %)))
                                    (h/read-tick-events ctx (:trace-id result))))]
      (is (= "typesafe/jev-1.13-20260917" (:model completion)))
      (is (= 330 (get-in completion [:usage :total-tokens]))))))

(defn- catalog-workflow [workflow-name instruction]
  (sheet/workflow workflow-name
    (sheet/blackboard {:request :string
                       :catalog [:vector [:map [:id :string] [:description :string]]]
                       :report :string})
    (sheet/llm-decision "pick-report"
      :instruction instruction
      :reads [:request :catalog] :options-from :catalog
      :min-confidence 0.6 :abstain "none"
      :writes [:report])))

(deftest published-decision-keeps-its-configuration
  (testing "a pinned published version runs its own decision configuration, not the draft's"
    (h/with-async-test-context [ctx {:context {:llm-provider :wf-jev}}]
      (let [requests (atom [])
            catalog [{:id "rpt/Q3#rev" :description "Q3 revenue"}
                     {:id "none" :description "No listed report matches"}]
            _ (llm/register-provider!
               :wf-jev
               {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
                :config {:api-key "dummy-not-a-key"
                         :decision-transport
                         (fn [request]
                           (swap! requests conj request)
                           {:model "typesafe/jev-1.13-20260917"
                            :answers {"report" {"type" "choice" "choice" "rpt/Q3#rev"
                                                "probabilities" {"rpt/Q3#rev" 0.55 "none" 0.45}
                                                "confidence" 0.1}}
                            :usage {"input_tokens" 10 "output_tokens" 1}})}})
            sheet-id (sheet/build-workflow! ctx (catalog-workflow "wf-published-decision"
                                                                  "Version one: which report?"))
            publish (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v1"))
            _ (sheet/build-workflow! ctx (catalog-workflow "wf-published-decision"
                                                           "Version two draft wording"))
            pinned (sheet/execute ctx sheet-id {:request "Q3 revenue" :catalog catalog}
                                  :use-version 1 :timeout-ms 20000)
            question (get-in (first @requests) [:questions "report"])]
        (is (not (h/is-anomaly? publish)))
        (is (= 1 (:executed-version pinned)))
        (is (= :success (:status pinned)) (pr-str (select-keys pinned [:status :error])))
        (is (= "none" (get-in pinned [:outputs :report]))
            "the published floor and abstention option still apply")
        (is (= {"rpt/Q3#rev" "Q3 revenue" "none" "No listed report matches"} (:criteria question))
            "the published :options-from still supplies the run-time options")
        (is (clojure.string/includes? (:instructions question) "Version one")
            "the published instruction, not the draft's")))))

(deftest decision-model-cost-is-recorded
  (testing "a provider-reported cost reaches the completion's usage and the family total"
    (h/with-async-test-context [ctx {:context {:llm-provider :wf-jev}}]
      (register-jev! :wf-jev (choice-response "lookup" probabilities 0.96) (atom []))
      (let [result (run! ctx :wf-jev (workflow "wf-jev-cost"))
            completion (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                            (some? (:decision %)))
                                      (h/read-tick-events ctx (:trace-id result))))]
        (is (= 0.00002 (get-in completion [:usage :cost])))
        (is (= 0.00002 (:cost (sheet/get-family-usage ctx (:trace-id result)))))))))
