(ns ai.obney.orc.orc-service.reconciled-contract-test
  "Public integration contracts at the judge/input-guide/strict-repair seams."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [hato.client :as http]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- nullable-workflow [name options]
  (bt/workflow name
    (bt/blackboard {:answer [:maybe {:description "Required answer; explicit null means unknown."} :string]})
    (bt/llm "answer"
      :instruction "Return the answer from the supplied evidence."
      :reads [] :writes [:answer]
      :retry {:max-attempts 1 :backoff-ms []}
      :options (merge {:max-retries 0} options))))

(defn- written-values [ctx tick-id]
  (filterv #(= :sheet/execution-value-written (:event/type %))
           (bt/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)))

(deftest absent-prediction-outputs-cannot-become-an-explicit-null
  (h/with-async-test-context [ctx]
    (with-redefs [llm/predict (fn [& _]
                              {:outputs nil
                               :usage {:prompt_tokens 3 :completion_tokens 0 :total_tokens 3}
                               :model "controlled/model"
                               :raw-response nil})]
      (let [sheet (bt/build-workflow! ctx (nullable-workflow "missing-nullable-answer" {}))
            result (bt/execute (assoc ctx :llm-provider :controlled) sheet {}
                               :max-ticks 1 :timeout-ms 30000 :llm-call-budget 1)]
        (is (= :failure (:status result)))
        (is (empty? (written-values ctx (:trace-id result)))
            "An absent response creates neither an authored null nor metadata writes.")))))

(deftest schema-error-policy-rejects-missing-nullable-writes-without-fabricating-them
  (h/with-async-test-context [ctx]
    (let [calls (atom 0)]
      (with-redefs [llm/predict (fn [& _]
                                (swap! calls inc)
                                {:outputs {} :model "controlled/model"})]
        (let [sheet (bt/build-workflow! ctx
                      (nullable-workflow "schema-policy-missing-nullable"
                        {:unparseable-is-schema-error? true :max-retries 1 :retry-delay-ms 0}))
              result (bt/execute (assoc ctx :llm-provider :controlled) sheet {}
                                 :max-ticks 1 :timeout-ms 30000 :llm-call-budget 2)
              completion (first (filter #(= :sheet/node-execution-completed (:event/type %))
                                        (h/read-tick-events ctx (:trace-id result))))]
          (is (= :failure (:status result)))
          (is (= :schema-validation-failed (:failure-kind completion)))
          (is (= [:answer] (:missing-write-keys completion)))
          (is (not (contains? completion :rejected-write-keys)) "An absent value is not a rejected supplied value.")
          (is (= 2 @calls) "The existing schema-error retry policy is retained.")
          (is (empty? (written-values ctx (:trace-id result)))))))))

(deftest explicit-null-and-provider-observations-have-separate-durable-owners
  (h/with-async-test-context [ctx {:context {:auth-claims {:sub "trusted-test"}}}]
    (with-redefs [llm/predict (fn [& _]
                              {:outputs {:answer nil}
                               :usage {:prompt_tokens 3 :completion_tokens 1 :total_tokens 4}
                               :model "resolved/model"
                               :provider-observations {:provider "controlled" :response-id "observed-null" :choices []}})]
      (let [sheet (bt/build-workflow! ctx (nullable-workflow "observed-explicit-null" {}))
            result (bt/execute (assoc ctx :llm-provider :controlled) sheet {}
                               :max-ticks 1 :timeout-ms 30000 :llm-call-budget 1)
            events (h/read-tick-events ctx (:trace-id result))
            start (first (filter #(= :sheet/node-execution-started (:event/type %)) events))
            attempts (:query/result (bt/provider-attempts ctx {:tick-id (:trace-id result)
                                                               :node-start-id (:event/id start)}))
            writes (written-values ctx (:trace-id result))]
        (is (= :success (:status result)))
        (is (= [{:key :answer :value nil}] (mapv #(select-keys % [:key :value]) writes))
            "Only the explicitly returned declared null is written, never metadata.")
        (is (= 1 (count attempts)))
        (is (= "observed-null" (get-in (first attempts) [:outcome :provider-observations :response-id])))
        (is (= :success (get-in (first attempts) [:outcome :status])))))))

(def ^:private score-inputs
  {:packet {:claim "The release is approved." :evidence ["Signed approval."]}
   :rubric {:bands {1 "Unsupported" 2 "Partially supported" 3 "Fully supported"}}})

(defn- score-workflow [workflow-name]
  (bt/workflow workflow-name
    (bt/blackboard
      {:packet [:map {:description "Evidence packet for this specific decision."}
                [:claim [:string {:description "Exact claim to assess."}]]
                [:evidence [:vector {:description "Authoritative supporting evidence."} :string]]
                [:withheld {:optional true :description "Omitted private context."} :string]]
       :rubric [:map {:description "Ordered assessment rubric."}
                 [:bands [:map-of :int :string]]]
       :band [:int {:description "Selected rubric band."}]})
    (bt/llm-decision "assess"
      :instruction "Assess the claim using the evidence supplied."
      :reads [:packet :rubric] :bands-from :rubric :writes [:band]
      :options {:max-retries 0})))

(deftest native-score-question-receives-the-focused-input-field-guide
  (h/with-async-test-context [ctx {:context {:llm-provider :reconciled-score}}]
    (let [requests (atom [])]
      (llm/register-provider! :reconciled-score
        {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
         :config {:api-key "synthetic-never-sent"
                  :decision-transport
                  (fn [request]
                    (swap! requests conj request)
                    {:model "resolved/score-model"
                     :answers {"band" {"type" "score" "score" 1.8
                                        "probabilities" {"0" 0.0 "1" 0.2 "2" 0.8}
                                        "confidence" 0.6}}})}})
      (let [sheet (bt/build-workflow! ctx (score-workflow "score-input-guide"))
            result (bt/execute ctx sheet score-inputs :max-ticks 1 :timeout-ms 30000 :llm-call-budget 1)
            request (first @requests)
            question (get-in request [:questions "band"])]
        (is (= :success (:status result)))
        (is (= 3 (get-in result [:outputs :band])))
        (is (= 1 (count @requests)))
        (is (= "score" (:type question)))
        (is (= ["Unsupported" "Partially supported" "Fully supported"] (:criteria question)))
        (doseq [description ["Evidence packet for this specific decision."
                             "Exact claim to assess." "Authoritative supporting evidence."]]
          (is (str/includes? (:instructions question) description)))
        (is (not (str/includes? (:instructions question) "Omitted private context.")))
        (is (= (:packet score-inputs) (json/parse-string (get-in request [:state :packet]) true)))
        (is (not (contains? (:state request) :rubric)) "The rubric is criteria, not duplicated evidence.")))))

(deftest ordinary-strict-model-accepts-the-same-ordered-band-and-input-contract
  (h/with-async-test-context [ctx {:context {:llm-provider :reconciled-strict-band}}]
    (let [requests (atom [])]
      (llm/register-provider! :reconciled-strict-band
        {:provider :openrouter :model "controlled/model" :output-mode :json-schema
         :config {:api-key "synthetic-never-sent"}})
      (with-redefs [http/post (fn [_ options]
                               (swap! requests conj (json/parse-string (:body options) true))
                               (future {:status 200
                                        :body {:id "strict-band-response" :model "resolved/chat-model"
                                               :choices [{:index 0 :finish_reason "stop"
                                                          :message {:role "assistant" :content "{\"band\":\"3\"}"}}]
                                               :usage {:prompt_tokens 3 :completion_tokens 1 :total_tokens 4}}}))]
        (let [sheet (bt/build-workflow! ctx (score-workflow "strict-band-input-guide"))
              result (bt/execute ctx sheet score-inputs :max-ticks 1 :timeout-ms 30000 :llm-call-budget 1)
              request (first @requests)
              schema (get-in request [:response_format :json_schema :schema])]
          (is (= :success (:status result)))
          (is (= 3 (get-in result [:outputs :band])))
          (is (= 1 (count @requests)))
          (is (= ["1" "2" "3"] (get-in schema [:properties :band :enum])))
          (is (not (contains? request :tools)))
          (doseq [description ["Exact claim to assess." "Authoritative supporting evidence."]]
            (is (str/includes? (get-in request [:messages 0 :content]) description))))))))
