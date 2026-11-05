(ns ai.obney.orc.orc-service.decision-input-guide-test
  "Workflow-level integration of EveryRequestCarriesItsInputFieldGuide
   (llm, decision path): an `sheet/llm-decision` leaf executed over a provider
   registered with :protocol :decision. The input values are the ones the
   executor really gathers from the blackboard (`gather-inputs`), and the
   request is captured at the decision transport, so the declared meanings
   travel the whole way: blackboard schema -> module -> predict -> request."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private route-descriptions
  {"lookup" "Retrieve an existing catalog fact."
   "research" "Investigate a question that needs new evidence."
   "clarify" "The request is too ambiguous to act on; ask the user."})

(def ^:private probabilities {"lookup" 0.98 "clarify" 0.02 "research" 0})

(def ^:private reply
  {:id "dec-1" :model "typesafe/jev-1.13-20260917" :provider "TypeSafe"
   :answers {"route" {"type" "choice" "choice" "lookup"
                      "probabilities" probabilities "confidence" 0.96}}
   :usage {"input_tokens" 300 "output_tokens" 30 "cost" 0.00002}})

(defn- register-jev! [provider-name requests]
  (llm/register-provider!
   provider-name
   {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
    :config {:api-key "dummy-not-a-key"
             :decision-transport (fn [request] (swap! requests conj request) reply)}}))

(def ^:private workflow
  (sheet/workflow "wf-decision-input-guide"
    (sheet/blackboard
     {:request [:string {:description "The user's request"}]
      :account [:map {:description "The customer account"}
                [:tier {:description "Support tier"} [:enum "free" "pro"]]
                [:region {:optional true :description "Where they are based"} :string]]
      :messages [:vector {:description "The conversation so far, oldest first"}
                 [:map {:description "One message"}
                  [:author-kind {:description "human means a staff member, never the customer's own words"}
                   [:enum "human" "customer"]]
                  [:text {:description "What was written"} :string]]]
      :route (into [:enum {:descriptions route-descriptions}] (keys route-descriptions))})
    (sheet/llm-decision "route"
      :instruction "Which operation addresses the request?"
      :reads [:request :account :messages] :writes [:route])))

(deftest the-decision-request-explains-the-fields-the-executor-sends
  (h/with-async-test-context [ctx {:context {:llm-provider :wf-guide}}]
    (let [requests (atom [])
          _ (register-jev! :wf-guide requests)
          sheet-id (sheet/build-workflow! ctx workflow)
          result (sheet/execute ctx sheet-id
                                {:request "Q3 revenue from the board report"
                                 :account {:tier "pro"}
                                 :messages [{:author-kind "human" :text "How can I help?"}
                                            {:author-kind "customer" :text "I was charged twice"}]}
                                :timeout-ms 20000)
          {:keys [state questions]} (first @requests)
          instructions (:instructions (get questions "route"))]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (= 1 (count @requests)))
      (testing "the instruction is followed by the guide for the shown fields, nested ones included"
        (is (str/starts-with? instructions "Which operation addresses the request?"))
        (doseq [shown ["The user's request" "The customer account" "Support tier"
                       "The conversation so far, oldest first" "One message"
                       "human means a staff member, never the customer's own words"
                       "What was written"]]
          (is (str/includes? instructions shown) shown)))
      (testing "a nested field the request does not carry is not described"
        (is (not (str/includes? instructions "Where they are based"))))
      (testing "the state holds the values the executor gathered, and no guide"
        (is (= "Q3 revenue from the board report" (:request state)))
        (is (= "{\"tier\":\"pro\"}" (:account state)))
        (is (not (str/includes? (pr-str state) "Your input fields")))))))
