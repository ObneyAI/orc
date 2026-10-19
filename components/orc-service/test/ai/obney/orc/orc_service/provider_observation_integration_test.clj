(ns ai.obney.orc.orc-service.provider-observation-integration-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [hato.client :as http]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]
            [cognitect.anomalies :as anom]))

(defn response [n answer]
  {:status 200 :body {:id (str "response-" n) :model "actual/model"
                     :choices [{:index 0 :finish_reason "tool_calls"
                                :message {:role "assistant" :content nil :reasoning (str "basis-" n)
                                          :reasoning_details [{:type "reasoning.text" :text (str "basis-" n)}]
                                          :tool_calls [{:id (str "call-" n) :type "function"
                                                        :function {:name "submit_response"
                                                                   :arguments (json/generate-string {:answer answer})}}]}}]
                     :usage {:prompt_tokens 2 :completion_tokens 3 :total_tokens 5
                             :completion_tokens_details {:reasoning_tokens 2}}}})
(defn run-tree [ctx & [node-options]]
  (llm/register-provider! :ledger-integration {:provider :openrouter :model "controlled/model"
                                             :config {:api-key "synthetic-never-sent"}})
  (let [sheet (bt/build-workflow! ctx
                (bt/workflow (str "ledger-integration-" (random-uuid))
                  (bt/blackboard {:answer [:int {:description "Required integer answer."}]})
                  (bt/llm "answer" :reads [] :writes [:answer]
                          :retry {:max-attempts 2 :backoff-ms [0]}
                          :options (merge {:use-function-calling? true :max-retries 1 :retry-delay-ms 0} node-options))))]
    (bt/execute ctx sheet {} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 8)))
(defn records [ctx tick-id]
  (let [starts (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                                   :tags #{[:tick tick-id]}
                                                   :types #{:sheet/node-execution-started}}))]
    (vec (mapcat #(or (:query/result (bt/provider-attempts ctx {:tick-id tick-id :node-start-id (:event/id %)})) []) starts))))

(deftest failed-output-then-valid-retry-retains-two-owned-attempts
  (let [calls (atom [])
        dispatch-ledger (atom [])
        ctx (h/create-async-test-context {:context {:auth-claims {:sub "trusted-test"}
                                                   :llm-provider :ledger-integration}})]
    (try
      (with-redefs [http/post (fn [_ options]
                               (swap! dispatch-ledger conj
                                      (mapv :event/type
                                            (into [] (es/read (:event-store ctx)
                                                       {:tenant-id (:tenant-id ctx)
                                                        :types #{:sheet/provider-attempt-started :sheet/provider-attempt-completed}}))))
                               (let [n (count (swap! calls conj (json/decode (:body options) true)))]
                                 (future (response n (if (= 1 n) "invalid" 42)))))]
        (let [result (run-tree ctx)
              rows (records ctx (:trace-id result))]
          (is (= :success (:status result)))
          (is (= 2 (count @calls)))
          (is (= [[:sheet/provider-attempt-started]
                  [:sheet/provider-attempt-started :sheet/provider-attempt-completed :sheet/provider-attempt-started]]
                 @dispatch-ledger))
          (is (= 2 (count rows)))
          (is (= [:failure :success] (mapv #(get-in % [:outcome :status]) rows)))
          (is (= ["response-1" "response-2"] (mapv #(get-in % [:outcome :provider-observations :response-id]) rows)))
          (is (= [1 2] (mapv :provider-attempt rows)))
          (is (= 2 (count (set (map :invocation-id rows)))))
          (is (= 1 (count (set (map :node-start-id rows)))))
          (is (= 10 (:total-tokens (bt/get-family-usage ctx (:trace-id result)))))
          (is (every? #(not-any? (set (keys %)) [:start-provider-attempt! :complete-provider-attempt!]) @calls))))
      (finally (h/stop-async-context ctx)))))

(deftest persistence-failure-never-duplicates-provider-work
  (doseq [[failed-type expected-calls expected-records]
          [[:sheet/provider-attempt-started 0 0]
           [:sheet/provider-attempt-completed 1 1]]]
    (let [calls (atom 0)
          append es/append
          ctx (h/create-async-test-context {:context {:auth-claims {:sub "trusted-test"}
                                                     :llm-provider :ledger-integration}})]
      (try
        (with-redefs [es/append (fn [store request]
                                 (if (some #(= failed-type (:event/type %)) (:events request))
                                   {::anom/category ::anom/unavailable ::anom/message "Controlled persistence failure"}
                                   (append store request)))
                      http/post (fn [& _] (future (response (swap! calls inc) 42)))]
          (let [result (run-tree ctx)
                rows (records ctx (:trace-id result))]
            (is (= :failure (:status result)))
            (is (= expected-calls @calls))
            (is (= expected-records (count rows)))
            (when (seq rows)
              (is (= :started (:status (first rows))))
              (is (not (contains? (first rows) :outcome))))))
        (finally (h/stop-async-context ctx))))))

(deftest malformed-first-response-retains-failed-observations
  (let [calls (atom 0)
        ctx (h/create-async-test-context {:context {:auth-claims {:sub "trusted-test"}
                                                   :llm-provider :ledger-integration}})]
    (try
      (with-redefs [http/post (fn [& _]
                               (let [n (swap! calls inc)]
                                 (future (cond-> (response n 42)
                                           (= n 1) (assoc-in [:body :choices 0 :message :tool_calls 0 :function :arguments] "{")))))]
        (let [result (run-tree ctx)
              rows (records ctx (:trace-id result))]
          (is (= :success (:status result)))
          (is (= 2 @calls))
          (is (= [:failure :success] (mapv #(get-in % [:outcome :status]) rows)))
          (is (= :tool-call-parsing-failed (get-in rows [0 :outcome :failure-kind])))
          (is (= "basis-1" (get-in rows [0 :outcome :provider-observations :choices 0 :reasoning-content])))))
      (finally (h/stop-async-context ctx)))))

(deftest identical-parallel-responses-have-distinct-execution-owners
  (let [calls (atom 0)
        ctx (h/create-async-test-context {:context {:auth-claims {:sub "trusted-test"}
                                                   :llm-provider :ledger-integration}})]
    (try
      (llm/register-provider! :ledger-integration {:provider :openrouter :model "controlled/model"
                                                 :config {:api-key "synthetic-never-sent"}})
      (with-redefs [http/post (fn [& _] (swap! calls inc) (future (response 1 42)))]
        (let [sheet (bt/build-workflow! ctx
                      (bt/workflow (str "parallel-owned-" (random-uuid))
                        (bt/blackboard {:answer [:int {:description "Shared identical synthetic result."}]})
                        (bt/parallel "both"
                          (bt/llm "first" :writes [:answer] :options {:use-function-calling? true :max-retries 0})
                          (bt/llm "second" :writes [:answer] :options {:use-function-calling? true :max-retries 0}))))
              result (bt/execute ctx sheet {} :timeout-ms 30000 :max-ticks 1)
              rows (records ctx (:trace-id result))]
          (is (= :success (:status result)))
          (is (= 2 @calls))
          (is (= 2 (count rows)))
          (is (= 2 (count (set (map :node-id rows)))))
          (is (= 2 (count (set (map :node-start-id rows)))))
          (is (= 2 (count (set (map :invocation-id rows)))))
          (is (= ["response-1" "response-1"] (mapv #(get-in % [:outcome :provider-observations :response-id]) rows)))))
      (finally (h/stop-async-context ctx)))))

(deftest node-options-cannot-disable-host-observation-capabilities
  (let [ctx (h/create-async-test-context {:context {:auth-claims {:sub "trusted-test"}
                                                   :llm-provider :ledger-integration}})]
    (try
      (with-redefs [http/post (fn [& _] (future (response 1 42)))]
        (let [result (run-tree ctx {:start-provider-attempt! nil :complete-provider-attempt! nil})]
          (is (= :success (:status result)))
          (is (= 1 (count (records ctx (:trace-id result)))))))
      (finally (h/stop-async-context ctx)))))
