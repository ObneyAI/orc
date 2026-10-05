(ns ai.obney.orc.orc-service.real-llm-decision-e2e-test
  "REAL-model proof of `sheet/llm-decision` through the public workflow: the
   same decision trees run on a native decision model (Jev via OpenRouter
   /api/alpha/decisions) and on the pinned conversational OpenRouter model.
   Opt-in through ORC_OPENROUTER_E2E_TESTS. Clear-cut inputs only; no
   accuracy or calibration claim."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn do-lookup [_] {:did "lookup"})
(defn do-research [_] {:did "research"})
(defn- fq [f] (str "ai.obney.orc.orc-service.real-llm-decision-e2e-test/" f))

(def ^:private routes
  {"lookup" "Retrieve a figure that already exists in a stored report."
   "research" "Investigate an open question that needs new evidence gathered."
   "clarify" "The request is too ambiguous to act on; ask the user what they mean."})

(defn- routing-workflow [workflow-name & {:as opts}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:request :string
                       :route (into [:enum {:descriptions routes}] (keys routes))
                       :did :string})
    (sheet/sequence "main"
      (apply sheet/llm-decision "route"
             (mapcat identity (merge {:instruction "Choose the operation that addresses the user's request."
                                      :reads [:request] :writes [:route]}
                                     opts)))
      (sheet/fallback "dispatch"
        (sheet/sequence "lookup-route"
          (sheet/condition "is-lookup" :check {:key :route :op :equals :value "lookup"})
          (sheet/code "lookup" :fn (fq "do-lookup") :writes [:did]))
        (sheet/sequence "research-route"
          (sheet/condition "is-research" :check {:key :route :op :equals :value "research"})
          (sheet/code "research" :fn (fq "do-research") :writes [:did]))
        (sheet/condition "is-clarify" :check {:key :route :op :equals :value "clarify"})))))

(defn- catalog-workflow [workflow-name & {:as opts}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:request :string
                       :catalog [:vector [:map [:id :string] [:description :string]]]
                       :report :string})
    (apply sheet/llm-decision "pick-report"
           (mapcat identity (merge {:instruction "Which listed report is the user asking for?"
                                    :reads [:request :catalog] :options-from :catalog
                                    :writes [:report]}
                                   opts)))))

(defn- truth-workflow [workflow-name & {:as opts}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:claim :string :holds :boolean})
    (apply sheet/llm-decision "holds"
           (mapcat identity (merge {:instruction "Is the claim factually true?"
                                    :reads [:claim] :writes [:holds]}
                                   opts)))))

(def ^:private catalog
  [{:id "rpt/Q3-2025#rev" :description "Third-quarter 2025 revenue report"}
   {:id "rpt/Q2-2025#rev" :description "Second-quarter 2025 revenue report"}
   {:id "hr/2025#headcount" :description "2025 headcount summary"}])

(defn- run! [ctx workflow inputs]
  (sheet/execute ctx (sheet/build-workflow! ctx workflow) inputs :timeout-ms 120000))

(defn- record [ctx result]
  (first (filter #(and (= :sheet/node-execution-completed (:event/type %)) (some? (:decision %)))
                 (h/read-tick-events ctx (:trace-id result)))))

(defn- show [label ctx result]
  (let [c (record ctx result)]
    (println label (pr-str {:status (:status result) :error (:error result)
                            :outputs (dissoc (:outputs result) :catalog)
                            :decision (:decision c) :model (:model c) :usage (:usage c)}))
    c))

(def ^:private clear-lookup "Please pull the Q3 2025 revenue number from the board report we filed.")

(deftest real-jev-llm-decision-workflows
  (live/with-real-openrouter
    (llm/register-provider! :real-jev-wf
                            {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
                             :config {:api-key (System/getenv "OPENROUTER_API_KEY")}})
    (h/with-async-test-context [ctx {:context {:llm-provider :real-jev-wf}}]
      (testing "routing: a clear lookup request routes and dispatches"
        (let [result (run! ctx (routing-workflow "real-jev-route" :min-confidence 0.6 :abstain "clarify")
                           {:request clear-lookup})
              c (show :JEV-ROUTE ctx result)]
          (is (= :success (:status result)))
          (is (= "lookup" (get-in result [:outputs :route])))
          (is (= "lookup" (get-in result [:outputs :did])))
          (is (number? (get-in c [:decision :confidence])) "Jev reports a confidence")
          (is (map? (get-in c [:decision :probabilities])))
          (is (string? (:model c)))))
      (testing "routing: an empty-of-meaning request ends in clarify"
        (let [result (run! ctx (routing-workflow "real-jev-route-vague" :min-confidence 0.6 :abstain "clarify")
                           {:request "hmm"})]
          (show :JEV-VAGUE ctx result)
          (is (= :success (:status result)))
          (is (= "clarify" (get-in result [:outputs :route])))
          (is (nil? (get-in result [:outputs :did])))))
      (testing "run-time catalog ids are offered and kept verbatim"
        (let [result (run! ctx (catalog-workflow "real-jev-catalog")
                           {:request "I need the third quarter 2025 revenue report." :catalog catalog})]
          (show :JEV-CATALOG ctx result)
          (is (= :success (:status result)))
          (is (= "rpt/Q3-2025#rev" (get-in result [:outputs :report])))))
      (testing "boolean decisions: true and false are both successful assessments"
        (let [t (run! ctx (truth-workflow "real-jev-true") {:claim "Two plus two equals four."})
              f (run! ctx (truth-workflow "real-jev-false") {:claim "Two plus two equals five."})]
          (show :JEV-TRUE ctx t) (show :JEV-FALSE ctx f)
          (is (= [:success true] [(:status t) (get-in t [:outputs :holds])]))
          (is (= [:success false] [(:status f) (get-in f [:outputs :holds])]))
          (is (number? (get-in (record ctx t) [:decision :probability]))))))))

(deftest real-chat-llm-decision-workflows
  (live/with-real-openrouter
    (live/register-openrouter!)
    (h/with-async-test-context [ctx {:context {:llm-provider :openrouter}}]
      (testing "routing on a conversational model writes an offered option and dispatches"
        (let [result (run! ctx (routing-workflow "real-chat-route" :model live/openrouter-model)
                           {:request clear-lookup})
              c (show :CHAT-ROUTE ctx result)]
          (is (= :success (:status result)))
          (is (= "lookup" (get-in result [:outputs :route])))
          (is (= "lookup" (get-in result [:outputs :did])))
          (is (not (contains? (:decision c) :confidence)) "no confidence is synthesised")))
      (testing "a floor on a model that reports no confidence abstains explicitly"
        (let [result (run! ctx (routing-workflow "real-chat-floor" :model live/openrouter-model
                                                 :min-confidence 0.6 :abstain "clarify")
                           {:request clear-lookup})
              c (show :CHAT-FLOOR ctx result)]
          (is (= "clarify" (get-in result [:outputs :route])))
          (is (true? (get-in c [:decision :abstained?])))
          (is (= "lookup" (get-in c [:decision :set-aside])))))
      (testing "run-time catalog on a conversational model"
        (let [result (run! ctx (catalog-workflow "real-chat-catalog" :model live/openrouter-model)
                           {:request "I need the third quarter 2025 revenue report." :catalog catalog})]
          (show :CHAT-CATALOG ctx result)
          (is (= "rpt/Q3-2025#rev" (get-in result [:outputs :report])))))
      (testing "boolean false is a successful assessment"
        (let [f (run! ctx (truth-workflow "real-chat-false" :model live/openrouter-model)
                      {:claim "Two plus two equals five."})]
          (show :CHAT-FALSE ctx f)
          (is (= [:success false] [(:status f) (get-in f [:outputs :holds])])))))))
