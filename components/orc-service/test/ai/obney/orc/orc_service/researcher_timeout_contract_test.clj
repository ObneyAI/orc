(ns ai.obney.orc.orc-service.researcher-timeout-contract-test
  "Offline provider injection through real LLM normalization, Grain commands,
   researcher iteration projections and durable model receipts."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [litellm.router :as router]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def secret "FICTIONAL-TIMEOUT-REQUEST-SECRET")

(deftest normalized-provider-timeout-retries-the-same-real-campaign-iteration
  (let [calls (atom 0)]
    (with-redefs [router/completion
                  (fn [& _]
                    (swap! calls inc)
                    (throw (ex-info secret {:type :litellm/timeout :request secret})))]
      (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow "normalized-researcher-timeout"
                        (orc/blackboard {:summary :string})
                        (orc/repl-researcher "researcher" :instruction "Return a summary."
                          :writes [:summary] :max-iterations 3
                          :rlm {:checkpointed? true :iteration-retry {:max-attempts 2}
                                :timeouts {:provider-ms 30000 :iteration-ms 60000 :campaign-ms 120000}})))
              node (:id (first (filter #(= "researcher" (:name %)) (orc/get-nodes-for-sheet ctx sheet))))
              result (orc/execute ctx sheet {} :timeout-ms 90000)
              records (rm/get-researcher-iteration-records ctx sheet (:trace-id result) node)
              events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id result))]
          (is (= 2 @calls))
          (is (= :timeout (:status result)))
          (is (= [[0 0 :timeout "provider-timeout"] [0 1 :timeout "provider-timeout"]]
                 (mapv (juxt :iteration-index :attempt-ordinal :status :error-class) records)))
          (is (not (str/includes? (pr-str events) secret)))
          (is (not (str/includes? (pr-str result) secret))))))))

(deftest configured-model-exhaustion-keeps-its-terminal-policy-outcome
  (let [provider (keyword (str "timeout-policy-" (random-uuid))) calls (atom 0)]
    (try
      (llm/register-provider! provider {:provider :openrouter :model "fixture/timeout"
                                        :output-mode :function-calling
                                        :config {:api-key "CONTROLLED-NOT-A-REAL-KEY"}})
      (with-redefs [router/completion
                    (fn [& _] (swap! calls inc)
                      (throw (ex-info secret {:type :litellm/timeout :request secret})))]
        (h/with-async-test-context [ctx {:context {:llm-provider provider}}]
          (let [sheet (orc/build-workflow! ctx
                        (orc/workflow "configured-timeout-policy"
                          (orc/blackboard {:summary :string})
                          (orc/repl-researcher "researcher" :instruction "Return a summary."
                            :writes [:summary] :max-iterations 3 :fallback-models []
                            :rlm {:checkpointed? true :iteration-retry {:max-attempts 2}
                                  :timeouts {:provider-ms 30000 :iteration-ms 60000 :campaign-ms 120000}})))
                result (orc/execute ctx sheet {} :timeout-ms 90000)
                events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id result))
                requests (filter #(and (= :sheet/model-request-recorded (:event/type %))
                                        (= :completed (get-in % [:request :phase]))) events)]
            (is (= 1 @calls) "Configured candidate exhaustion does not gain iteration retries")
            (is (= :failure (:status result)))
            (is (= 1 (count requests)))
            (is (= :provider (get-in (first requests) [:request :outcome :timeout-kind])))
            (is (not (str/includes? (pr-str events) secret))))))
      (finally (router/unregister! provider)))))
