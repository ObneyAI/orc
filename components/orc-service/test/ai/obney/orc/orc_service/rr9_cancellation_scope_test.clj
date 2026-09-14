(ns ai.obney.orc.orc-service.rr9-cancellation-scope-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(deftest non-checkpointed-researcher-keeps-legacy-cancellation-boundary
  (testing "operator cancellation does not register or interrupt legacy researcher work"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-entered (promise)
            provider-interrupted (promise)
            provider-finished (promise)
            release-provider (promise)
            tick-id (random-uuid)
            definition
            (sheet/workflow "rr9-non-checkpointed-cancellation-scope"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "remain on the legacy cancellation boundary"
                :writes [:summary]
                :max-iterations 1
                :rlm {:recursive? true
                      :checkpointed? false
                      :auto-classify? false
                      :timeouts {:provider-ms 10000
                                 :iteration-ms 12000
                                 :campaign-ms 20000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            predict-fn
            (fn [& _]
              (deliver provider-entered true)
              (try
                @release-provider
                {:outputs {:code "(final! {:summary \"released\"})"}
                 :reasoning "finish only after the test releases legacy work"
                 :usage {:prompt_tokens 2
                         :completion_tokens 1
                         :total_tokens 3}}
                (catch InterruptedException interrupted
                  (deliver provider-interrupted true)
                  (throw interrupted))
                (finally
                  (deliver provider-finished true))))]
        (with-redefs [llm/predict predict-fn]
          (let [execution (future
                            (sheet/execute ctx sheet-id {}
                                           :tick-id tick-id
                                           :timeout-ms 15000))]
            (try
              (is (= true (deref provider-entered 5000 ::provider-not-entered)))
              (is (= {:cancelled [tick-id]} (sheet/cancel! ctx tick-id)))
              (let [result (deref execution 3000 ::caller-still-blocked)]
                (is (not= ::caller-still-blocked result))
                (is (true? (:cancelled? result)) (pr-str result)))
              ;; The unblocked caller is the public cancellation-delivery
              ;; barrier. Before RR-9, non-checkpointed researcher work was not
              ;; in the active-work ledger and therefore remained untouched.
              (is (false? (realized? provider-interrupted))
                  "legacy researcher work is not newly interrupted")
              (deliver release-provider true)
              (is (= true (deref provider-finished 2000 ::provider-not-finished))
                  "the test releases all legacy work without a leaked future")
              (finally
                (deliver release-provider true)))))))))
