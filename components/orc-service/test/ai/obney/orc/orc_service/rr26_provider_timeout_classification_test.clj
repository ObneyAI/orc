(ns ai.obney.orc.orc-service.rr26-provider-timeout-classification-test
  "RR-26 — a provider call that does not answer within its deadline is a PROVIDER
   TIMEOUT however the deadline is detected.

   `specs/orc-service.allium` `@invariant TimedOutIterationRetriesFromCheckpoint`:
   an iteration timeout preserves every preceding completed iteration and yields a
   retry of the same iteration from the last checkpoint; exhausting the configured
   iteration-attempt limit terminates the campaign as timeout.

   Two timers race on every checkpointed provider call, and both are set from the
   SAME live remainder: ORC's own bounded-call deadline, and the HTTP transport's
   request timeout that ORC hands the provider. Whichever fires first used to
   decide the classification — ORC's timer produced `:timeout-kind :provider` and
   a retry, while the transport's timer surfaced a timeout EXCEPTION that fell
   through to `:error-class \"provider-failure\"` and terminated the campaign with
   the retry budget unspent. Found on a live pinned-model run under a deliberately
   constrained `:provider-ms`: consecutive attempts on the same campaign were
   classified `:timeout` at 1,226 ms and `:failure` at 1,213 ms.

   Classification is by exception TYPE and cause chain, never by message text."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- campaign
  [name]
  (sheet/workflow name
    (sheet/blackboard {:summary :string})
    (sheet/repl-researcher "researcher"
      :instruction "the provider transport stops answering"
      :writes [:summary]
      :max-iterations 3
      :rlm {:checkpointed? true
            :iteration-retry {:max-attempts 2}
            ;; Deliberately generous: ORC's own bounded-call deadline must NOT be
            ;; the thing that fires. Only the transport's own timeout does.
            :timeouts {:provider-ms 30000
                       :iteration-ms 60000
                       :campaign-ms 120000}})))

(defn- run!
  [ctx name thrown]
  (let [sheet-id (sheet/build-workflow! ctx (campaign name))
        researcher-id (:id (first (filter #(= "researcher" (:name %))
                                          (sheet/get-nodes-for-sheet ctx sheet-id))))
        calls (atom 0)]
    (with-redefs [llm/predict (fn [& _] (swap! calls inc) (throw (thrown)))]
      (let [result (sheet/execute ctx sheet-id {} :timeout-ms 90000)
            tick-id (:trace-id result)]
        {:result result
         :calls @calls
         :records (rm/get-researcher-iteration-records ctx sheet-id tick-id researcher-id)}))))

(deftest det-e2e-289-transport-timeout-is-a-provider-timeout-not-a-failure
  (testing "the transport's own request timeout retries from checkpoint and exhausts to a campaign timeout"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [{:keys [result calls records]}
            (run! ctx "rr26-transport-timeout"
                  #(java.net.http.HttpTimeoutException. "request timed out"))]
        (is (= 2 calls) "the timed-out attempt is retried from its checkpoint, spending the configured budget")
        (is (= [[0 0 :timeout] [0 1 :timeout]]
               (mapv (juxt :iteration-index :attempt-ordinal :status) records))
            (pr-str (mapv (juxt :iteration-index :attempt-ordinal :status :error-class) records)))
        (is (every? #(= "provider-timeout" (:error-class %)) records)
            (pr-str (mapv :error-class records)))
        (is (= :timeout (:status result)) (pr-str result))))))

(deftest det-e2e-289-a-wrapped-transport-timeout-is-still-a-provider-timeout
  (testing "a timeout wrapped by the provider adapter is classified from its cause chain"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [{:keys [result calls records]}
            (run! ctx "rr26-wrapped-transport-timeout"
                  #(ex-info "provider call failed"
                            {:provider :openrouter}
                            (java.net.http.HttpTimeoutException. "request timed out")))]
        (is (= 2 calls))
        (is (= [[0 0 :timeout] [0 1 :timeout]]
               (mapv (juxt :iteration-index :attempt-ordinal :status) records))
            (pr-str (mapv (juxt :iteration-index :attempt-ordinal :status :error-class) records)))
        (is (= :timeout (:status result)) (pr-str result))))))

(deftest a-genuine-provider-error-is-still-a-failure-not-a-timeout
  (testing "the timeout classification does not swallow ordinary provider errors"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [{:keys [result calls records]}
            (run! ctx "rr26-ordinary-provider-error"
                  #(ex-info "provider returned 400 Bad Request" {:status 400}))]
        (is (= 1 calls) "a non-timeout provider error is terminal and is not retried")
        (is (= [:failure] (mapv :status records)) (pr-str records))
        (is (= :failure (:status result)) (pr-str result))))))

(deftest orcs-own-provider-deadline-fires-before-the-transports
  (testing "the transport still receives the EXACT live remainder — it is the outer bound"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [seen (atom [])
            sheet-id (sheet/build-workflow! ctx (campaign "rr26-transport-bound"))]
        (with-redefs [llm/predict (fn [_provider _module _inputs & [options]]
                                    (swap! seen conj (:timeout-ms options))
                                    {:outputs {:code "(final! {:summary \"done\"})"}
                                     :reasoning "finish"
                                     :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 90000)]
            (is (= :success (:status result)) (pr-str result))
            (is (seq @seen) "the provider was invoked with an explicit transport deadline")
            (is (every? #(= 30000 %) @seen)
                (str "the transport gets the configured :provider-ms unchanged, got " (pr-str @seen))))))))
  (testing "ORC's own deadline is strictly earlier, and degrades proportionally on tiny budgets"
    (is (< (executor/provider-classification-deadline-ms 30000) 30000))
    (is (= (- 30000 executor/provider-classification-lead-ms)
           (executor/provider-classification-deadline-ms 30000)))
    (is (< (executor/provider-classification-deadline-ms 1200) 1200))
    (is (= 1080 (executor/provider-classification-deadline-ms 1200))
        "the lead is capped at a tenth of the budget")
    (is (= 14 (executor/provider-classification-deadline-ms 15))
        "a deliberately tiny deadline shrinks proportionally, never to zero")
    (is (pos? (executor/provider-classification-deadline-ms 1)))
    (is (= 0 (executor/provider-classification-deadline-ms 0))
        "a non-positive budget is left alone for the caller's own zero check")))
