(ns ai.obney.orc.orc-service.strict-contract-e2e-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [hato.client :as http]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- run-strict [content finish-reason refusal]
  (llm/register-provider! :strict-contract
    {:provider :openrouter :model "controlled/model" :output-mode :json-schema
     :config {:api-key "synthetic-not-sent"}})
  (let [requests (atom [])]
    (with-redefs [http/post
                  (fn [_ options]
                    (swap! requests conj (json/parse-string (:body options) true))
                    (future {:status 200
                             :body {:id "strict-response" :model "controlled/model"
                                    :choices [{:index 0 :finish_reason finish-reason
                                               :message (cond-> {:role "assistant" :content content}
                                                          refusal (assoc :refusal refusal))}]
                                    :usage {:prompt_tokens 20 :completion_tokens 15 :total_tokens 35}}}))]
      (h/with-async-test-context [ctx {:context {:llm-provider :strict-contract}}]
        (let [workflow (bt/build-workflow! ctx
                         (bt/workflow "strict-contract-leaf"
                           (bt/blackboard
                             {:request [:string {:description "Exact source identifier supplied by the requester."}]
                              :answer-reasoning [:string {:description "Concise basis for the answer."}]
                              :answer [:map {:closed true :description "Result grounded in the supplied source."}
                                       [:source-id [:string {:description "Exact selected source identifier."}]]
                                       [:coolant-status [:maybe {:description "Required status, null if unknown."} :string]]
                                       [:reference {:optional true} [:string {:description "Optional supporting reference."}]]]})
                           (bt/llm "answer"
                             :instruction "Report the known source and its status."
                             :reads [:request] :writes [:answer-reasoning :answer]
                             :retry {:max-attempts 1 :backoff-ms []}
                             :options {:max-retries 0 :max-tokens 256})))
              result (bt/execute ctx workflow {:request "REF-24"}
                                 :timeout-ms 30000 :max-ticks 1 :llm-call-budget 1)
              tick-id (:trace-id result)
              events (bt/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)
              completion (first (filter #(= :sheet/node-execution-completed (:event/type %)) events))]
          {:result result :tick (bt/get-tick ctx tick-id) :completion completion
           :requests @requests :events events
           :writes (when completion
                     (bt/value-log-resolve-writes ctx (:tenant-id ctx) tick-id completion))})))))

(def valid-content
  (json/generate-string {:answer-reasoning "The source is known; its status is not."
                        :source-id "REF-24" :coolant-status nil :reference nil}))

(deftest provider-profile-pairs-derived-strict-request-with-content-parser
  (let [{:keys [result tick completion requests writes]} (run-strict valid-content "stop" nil)
        request (first requests)
        schema (get-in request [:response_format :json_schema :schema])]
    (is (= :success (:status result)))
    (is (= :success (:root-status tick)))
    (is (= :success (:status completion)))
    (is (= {:source-id "REF-24" :coolant-status nil} (:answer writes)))
    (is (= "The source is known; its status is not." (:answer-reasoning writes)))
    (is (= 1 (count requests)))
    (is (= true (get-in request [:response_format :json_schema :strict])))
    (is (= false (:additionalProperties schema)))
    (is (= #{"answer-reasoning" "source-id" "coolant-status" "reference"}
           (set (:required schema))))
    (is (str/includes? (get-in schema [:properties :coolant-status :description] "") "Required status"))
    (is (str/includes? (get-in request [:messages 0 :content] "") "Exact source identifier supplied"))
    (is (str/includes? (get-in request [:messages 0 :content] "") "Exact selected source identifier."))
    (is (str/includes? (get-in request [:messages 0 :content] "") "Required status, null if unknown."))
    (is (str/includes? (get-in request [:messages 0 :content] "") "Optional supporting reference."))
    (is (= true (get-in request [:provider :require_parameters])))
    (is (not (contains? request :tools)))))

(deftest terminal-provider-rejection-does-not-admit-parseable-content
  (doseq [[finish refusal] [["length" nil] ["stop" "Unable to comply."]]]
    (let [{:keys [result completion requests events]}
          (run-strict valid-content finish refusal)]
      (is (= :failure (:status result)))
      (is (= :failure (:status completion)))
      (is (= :schema-validation-failed (:failure-kind completion)))
      (is (empty? (filter #(and (= :sheet/execution-value-written (:event/type %))
                               (contains? #{:answer :answer-reasoning} (:key %))) events)))
      (is (= 1 (count requests))))))

(deftest malformed-strict-json-fails-durably-without-domain-writes
  (let [{:keys [result completion requests events]} (run-strict "{" "stop" nil)]
    (is (= :failure (:status result)))
    (is (= :failure (:status completion)))
    (is (= :schema-validation-failed (:failure-kind completion)))
    (is (empty? (filter #(and (= :sheet/execution-value-written (:event/type %))
                             (contains? #{:answer :answer-reasoning} (:key %))) events)))
    (is (= 1 (count requests)))))
