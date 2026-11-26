(ns ai.obney.orc.orc-service.structured-contract-e2e-test
  "Public structured contracts through the real adapter, executor and Grain.
   Only the final HTTP response is controlled; no predictor or executor fake."
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [hato.client :as http]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- reply [arguments]
  {:id "controlled-contract-response" :model "controlled/model"
   :choices [{:index 0 :finish_reason "tool_calls"
              :message {:role "assistant" :content nil
                        :tool_calls [{:id "controlled-tool-call" :type "function"
                                      :function {:name "submit_response"
                                                 :arguments (json/generate-string arguments)}}]}}]
   :usage {:prompt_tokens 12 :completion_tokens 8 :total_tokens 20}})

(defn- execute-response [arguments & [answer-schema]]
  (llm/register-provider! :contract-repair
    {:provider :openrouter :model "controlled/model"
     :config {:api-key "synthetic-not-sent"}})
  (let [requests (atom [])]
    (with-redefs [http/post (fn [_ options]
                            (swap! requests conj (json/parse-string (:body options) true))
                            (future {:status 200 :body (reply arguments)}))]
      (h/with-async-test-context [ctx {:context {:llm-provider :contract-repair}}]
        (let [sheet (bt/build-workflow! ctx
                      (bt/workflow "required-nullable-contract"
                        (bt/blackboard
                          {:answer-reasoning [:string {:description "Concise basis for the supplied answer."}]
                           :answer (or answer-schema
                                       [:maybe {:description "Required answer; explicit null means no known answer."} :string])})
                        (bt/llm "answer"
                          :instruction "Supply the answer according to the declared contract."
                          :reads [] :writes [:answer-reasoning :answer]
                          :retry {:max-attempts 1 :backoff-ms []}
                          :options {:use-function-calling? true :max-retries 0 :max-tokens 256})))
              result (bt/execute ctx sheet {} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 1)
              tick-id (:trace-id result)
              events (h/read-tick-events ctx tick-id)
              completion (first (filter #(= :sheet/node-execution-completed (:event/type %)) events))]
          {:result result :tick (bt/get-tick ctx tick-id) :completion completion
           :writes (when completion (bt/value-log-resolve-writes ctx (:tenant-id ctx) tick-id completion))
           :requests @requests :events events})))))

(deftest explicit-required-null-completes-and-persists
  (let [{:keys [result tick completion writes requests events]}
        (execute-response {:answer-reasoning "No answer is known." :answer nil})]
    (let [canonical (first (filter #(and (= :sheet/execution-value-written (:event/type %))
                                        (= :answer (:key %))) events))]
      (is (some? canonical))
      (is (contains? canonical :value))
      (is (nil? (:value canonical))))
    (is (= :success (:status result)))
    (is (= :success (:root-status tick)))
    (is (= :success (:status completion)))
    (is (contains? writes :answer))
    (is (nil? (:answer writes)))
    (is (= "No answer is known." (:answer-reasoning writes)))
    (is (= 1 (count requests)))
    (is (= #{"answer-reasoning" "answer"}
           (set (get-in (first requests) [:tools 0 :function :parameters :required]))))))

(deftest missing-required-nullable-output-fails-without-fabricated-write
  (let [{:keys [result tick completion events writes requests]}
        (execute-response {:answer-reasoning "No answer was supplied."})]
    (is (= :failure (:status result)))
    (is (= :failure (:root-status tick)))
    (is (= :failure (:status completion)))
    (is (not (contains? writes :answer)))
    (is (empty? (filter #(and (= :sheet/execution-value-written (:event/type %))
                             (= :answer (:key %))) events)))
    (is (= 1 (count requests)))))

(deftest flattened-nullable-field-remains-present-after-reassembly
  (let [{:keys [result writes requests]}
        (execute-response {:answer-reasoning "The detail is unknown." :detail nil}
                          [:map {:description "An answer with an explicitly unknown detail."}
                           [:detail [:maybe {:description "Required detail, null when unknown."} :string]]])]
    (is (= :success (:status result)))
    (is (= {:detail nil} (:answer writes)))
    (is (= #{"answer-reasoning" "detail"}
           (set (get-in (first requests) [:tools 0 :function :parameters :required]))))))

(deftest explicit-null-is-rejected-by-a-nonnullable-contract
  (let [{:keys [result completion writes events requests]}
        (execute-response {:answer-reasoning "No answer is known." :answer nil}
                          [:string {:description "A known answer is required."}])]
    (is (= :failure (:status result)))
    (is (= :failure (:status completion)))
    ;; Failed completion diagnostics retain the actual supplied value. They are
    ;; not accepted domain writes: assert the canonical write stream below.
    (is (contains? writes :answer))
    (is (nil? (:answer writes)))
    (is (empty? (filter #(and (= :sheet/execution-value-written (:event/type %))
                             (= :answer (:key %))) events)))
    (is (= 1 (count requests)))))
