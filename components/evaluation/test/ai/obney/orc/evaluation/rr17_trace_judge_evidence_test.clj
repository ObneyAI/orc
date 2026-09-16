(ns ai.obney.orc.evaluation.rr17-trace-judge-evidence-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.evaluation.core.judge-runtime :as judge-runtime]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.orc.llm.interface :as llm]))

(deftest evaluation-raw-trace-retains-the-durable-campaign-account
  (testing "the evaluation trace API preserves ordered iterations and claim/yield/resume evidence"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            trace-id (random-uuid)
            iteration-records
            [{:iteration-index 0
              :attempt-ordinal 0
              :status :failure
              :started-at "2026-01-01T00:00:00Z"
              :completed-at "2026-01-01T00:00:01Z"
              :duration-ms 1000
              :generated-code-recorded? true
              :emitted-tree-recorded? false
              :error-class "clojure.lang.ExceptionInfo"
              :error-excerpt "missing source"}
             {:iteration-index 1
              :attempt-ordinal 0
              :status :success
              :started-at "2026-01-01T00:00:02Z"
              :completed-at "2026-01-01T00:00:03Z"
              :duration-ms 1000
              :generated-code-recorded? true
              :emitted-tree-recorded? true
              :tree-fingerprint "sha256:repair"}]
            campaign-evidence
            [{:type :effect-claimed
              :iteration 1
              :logical-action-id "provider-1"
              :attempt-id "provider-1/epoch-1/attempt-0"
              :at "2026-01-01T00:00:00Z"}
             {:type :yield
              :iteration 1
              :at "2026-01-01T00:00:01Z"}
             {:type :resume
              :iteration 1
              :at "2026-01-01T00:00:02Z"}]
            _ (h/run-and-apply!
               ctx {:command/id (random-uuid)
                    :command/timestamp (java.time.OffsetDateTime/now)
                    :command/name :sheet/store-execution-trace
                    :trace-id trace-id
                    :sheet-id sheet-id
                    :root-trace-id trace-id
                    :child-trace-ids []
                    :started-at "2026-01-01T00:00:00Z"
                    :completed-at "2026-01-01T00:00:03Z"
                    :duration-ms 3000
                    :status :success
                    :input-snapshot {}
                    :output-snapshot {}
                    :node-traces []
                    :researcher-iterations iteration-records
                    :researcher-events campaign-evidence})
            trace (first (evaluation/get-traces-raw
                          ctx {:sheet-id sheet-id}))]
        (is (= iteration-records (:researcher-iterations trace))
            (pr-str trace))
        (is (= campaign-evidence (:researcher-events trace))
            (pr-str trace))))))

(deftest terminal-researcher-judge-data-reads-durable-iterations-directly
  (testing "judge input does not race asynchronous execution-trace publication"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command
                                               :name "RR17 judge trace"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            _ (h/run-and-apply!
               ctx {:command/name :sheet/declare-judge
                    :command/id (random-uuid)
                    :command/timestamp (java.time.OffsetDateTime/now)
                    :sheet-id sheet-id
                    :judge-name "rr17-reasoning"
                    :judge-config {:type :reasoning}})
            node-result (h/run-and-apply! ctx (h/make-create-node-command
                                              sheet-id :repl-researcher))
            node-id (-> node-result :command-result/events first :node-id)
            _ (h/run-and-apply!
               ctx {:command/name :sheet/set-node-judges
                    :command/id (random-uuid)
                    :command/timestamp (java.time.OffsetDateTime/now)
                    :sheet-id sheet-id
                    :node-id node-id
                    :judges ["rr17-reasoning"]})
            tick-id (random-uuid)
            iteration-records [{:iteration-index 0
                                :attempt-ordinal 0
                                :status :failure
                                :error-excerpt "missing source"
                                :code "(get-var :missing-source)"}
                               {:iteration-index 1
                                :attempt-ordinal 0
                                :status :success
                                :code "(final! {:answer \"repaired\"})"}]
            _ (event-store/append
               (:event-store ctx)
               {:tenant-id (:tenant-id ctx)
                :events
                (mapv
                 (fn [record]
                   (event-store/->event
                    {:type :rlm/researcher-iteration-recorded
                     :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
                     :body {:sheet-id sheet-id
                            :tick-id tick-id
                            :node-id node-id
                            :iteration-index (:iteration-index record)
                            :attempt-ordinal (:attempt-ordinal record)
                            :iteration-record record
                            :recorded-at "2026-01-01T00:00:03Z"}}))
                 iteration-records)})
            judge-input (atom nil)
            _
            (with-redefs-fn
              {(ns-resolve 'ai.obney.orc.evaluation.core.judge-runtime
                           'living-description-enabled?)
               (constantly true)
               #'llm/predict
               (fn [_provider _module inputs _options]
                 (reset! judge-input inputs)
                 {:outputs
                  {:level 2
                   :reasoning "Iteration 1 failed before iteration 2 repaired it."
                   :reasoning-strengths ["The repair completed."]
                   :reasoning-weaknesses ["The first source was missing."]
                   :feedback "Check source availability first."}})}
              #(let [handler-result
                     (judge-runtime/on-node-execution-completed
                      (assoc ctx :event
                             {:event/type :sheet/node-execution-completed
                              :sheet-id sheet-id
                              :tick-id tick-id
                              :node-id node-id
                              :status :success
                              :write-keys []}))
                     judge-future ((:result/effect handler-result))]
                 @judge-future))]
        (is (str/includes? (:iteration_evidence @judge-input)
                           "missing-source")
            (pr-str @judge-input))
        (let [scores (into []
                           (event-store/read
                            (:event-store ctx)
                            {:tenant-id (:tenant-id ctx)
                             :types #{:judge/score-emitted}
                             :tags #{[:tick tick-id]}}))]
          (is (= 1 (count scores)) (pr-str scores)))))))

(deftest reasoning-judge-names-the-failed-iteration-before-the-repair
  (testing "one campaign verdict is grounded in its ordered multi-iteration account"
    (let [requests (atom [])
          trace-data {:inputs {:question "Find the source and answer"}
                      :outputs {:answer "repaired answer"}
                      :instruction "Use the named source"
                      :researcher-iterations
                      [{:iteration-index 0
                        :attempt-ordinal 0
                        :status :failure
                        :code "(get-var :missing-source)"
                        :error-class "clojure.lang.ExceptionInfo"
                        :error-excerpt "No value stored for :missing-source"}
                       {:iteration-index 1
                        :attempt-ordinal 0
                        :status :success
                        :code "(get-var :available-source)"}]}
          result
          (with-redefs [llm/predict
                        (fn [_provider module inputs _options]
                          (swap! requests conj {:module module :inputs inputs})
                          {:outputs
                           {:level 2
                            :reasoning "Iteration 1 failed on :missing-source before iteration 2 repaired it."
                            :reasoning-strengths ["The second iteration recovered."]
                            :reasoning-weaknesses ["Iteration 1 assumed an unavailable source."]
                            :feedback "Check source availability before the first read."}})]
            (:reasoning-result
             (evaluation/evaluate-single :reasoning trace-data)))]
      (is (= 1 (count @requests)) (pr-str @requests))
      (is (str/includes? (get-in (first @requests) [:inputs :iteration_evidence])
                         "missing-source")
          (pr-str @requests))
      (is (str/includes? (:reasoning result) "Iteration 1 failed")
          (pr-str result)))))

(deftest duplicate-judge-score-check-is-tick-scoped-and-records-one-verdict
  (testing "duplicate delivery reads only the completion tick and remains idempotent"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            node-id (random-uuid)
            tick-id (random-uuid)
            real-read event-store/read
            observed-queries (atom [])
            command {:command/name :evaluation/record-judge-score
                     :command/id (random-uuid)
                     :command/timestamp (java.time.OffsetDateTime/now)
                     :sheet-id sheet-id
                     :node-id node-id
                     :tick-id tick-id
                     :judge-name "reasoning"
                     :judge-config {:type :reasoning}
                     :score 0.5
                     :feedback "iteration-aware verdict"
                     :dimensions []}]
        (with-redefs [event-store/read
                      (fn [store query]
                        (swap! observed-queries conj query)
                        (real-read store query))]
          (command-processor/process-command (assoc ctx :command command))
          (command-processor/process-command
           (assoc ctx :command (assoc command :command/id (random-uuid)))))
        (let [duplicate-queries
              (filter #(= #{:judge/score-emitted} (:types %))
                      @observed-queries)
              scores (into []
                           (event-store/read
                            (:event-store ctx)
                            {:tenant-id (:tenant-id ctx)
                             :types #{:judge/score-emitted}
                             :tags #{[:tick tick-id]}}))]
          (is (= 2 (count duplicate-queries))
              (pr-str @observed-queries))
          (is (every? #(= #{[:tick tick-id]} (:tags %)) duplicate-queries)
              (pr-str duplicate-queries))
          (is (= 1 (count scores)) (pr-str scores)))))))

(deftest concurrent-duplicate-judge-scores-record-one-verdict
  (testing "the identity invariant survives two commands that both observe no prior score"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            node-id (random-uuid)
            tick-id (random-uuid)
            real-read event-store/read
            both-at-read (java.util.concurrent.CountDownLatch. 2)
            command {:command/name :evaluation/record-judge-score
                     :command/timestamp (java.time.OffsetDateTime/now)
                     :sheet-id sheet-id
                     :node-id node-id
                     :tick-id tick-id
                     :judge-name "reasoning"
                     :judge-config {:type :reasoning}
                     :score 0.5
                     :feedback "iteration-aware verdict"
                     :dimensions []}]
        (with-redefs [event-store/read
                      (fn [store query]
                        (when (and (= #{:judge/score-emitted} (:types query))
                                   (= #{[:tick tick-id]} (:tags query)))
                          (.countDown both-at-read)
                          (when-not (.await both-at-read 5
                                            java.util.concurrent.TimeUnit/SECONDS)
                            (throw (ex-info "duplicate commands did not overlap"
                                            {}))))
                        (real-read store query))]
          (let [results (mapv deref
                              [(future
                                 (command-processor/process-command
                                  (assoc ctx :command
                                         (assoc command :command/id (random-uuid)))))
                               (future
                                 (command-processor/process-command
                                  (assoc ctx :command
                                         (assoc command :command/id (random-uuid)))))])]
            (is (= 2 (count results)) (pr-str results))))
        (let [scores (into []
                           (event-store/read
                            (:event-store ctx)
                            {:tenant-id (:tenant-id ctx)
                             :types #{:judge/score-emitted}
                             :tags #{[:tick tick-id]}}))]
          (is (= 1 (count scores)) (pr-str scores)))))))

(deftest public-researcher-trace-assembles-claim-yield-and-resume-evidence
  (testing "the evaluation API receives claim lifecycle from the real trace producer"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            definition
            (sheet/workflow "rr17-public-campaign-evidence"
              (sheet/blackboard {:answer :string})
              (sheet/repl-researcher "researcher"
                :instruction "fail once, then repair"
                :writes [:answer]
                :max-iterations 3
                :rlm {:checkpointed? true
                      :quantum {:max-iterations 1}
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (case (swap! calls inc)
                          1 {:outputs {:code "(get-var :missing-source)"}
                             :reasoning "first attempt names a missing source"
                             :usage {:prompt_tokens 2
                                     :completion_tokens 1
                                     :total_tokens 3}}
                          2 {:outputs {:code "(final! {:answer \"repaired\"})"}
                             :reasoning "repair after inspecting the failure"
                             :usage {:prompt_tokens 2
                                     :completion_tokens 1
                                     :total_tokens 3}}
                          (throw (ex-info "RR17 fixture exceeded two turns" {}))))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                trace-id (:trace-id result)]
            (is (= :success (:status result)) (pr-str result))
            (is (= 2 @calls))
            (is (h/settle-until! #(h/trace-stored? ctx trace-id)))
            (let [trace (first (evaluation/get-traces-raw
                                ctx {:sheet-id sheet-id}))
                  researcher-events (:researcher-events trace)
                  evidence-types (set (map :type researcher-events))
                  effect-events (filter #(contains? #{:effect-claimed
                                                       :effect-completed}
                                                     (:type %))
                                        researcher-events)]
              (is (= [0 1]
                     (mapv :iteration-index (:researcher-iterations trace)))
                  (pr-str trace))
              (is (every? evidence-types
                          [:effect-claimed :effect-completed
                           :checkpoint :yield :resume])
                  (pr-str researcher-events))
              (is (every? #(and (string? (:logical-action-identity %))
                                (string? (:attempt-identity %)))
                          effect-events)
                  (pr-str effect-events))
              (is (not-any? #(contains? % :result) effect-events)
                  "trace evidence must identify effects without copying their results"))))))))

(deftest extracted-researcher-trace-retains-the-durable-campaign-account
  (testing "the LLM trace for a researcher carries its enclosing campaign evidence"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command
                                               :name "RR17 extracted trace"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            node-result (h/run-and-apply! ctx (h/make-create-node-command
                                              sheet-id :repl-researcher))
            node-id (-> node-result :command-result/events first :node-id)
            trace-id (random-uuid)
            iteration-records [{:iteration-index 0
                                :attempt-ordinal 0
                                :status :failure
                                :error-excerpt "missing source"}
                               {:iteration-index 1
                                :attempt-ordinal 0
                                :status :success}]
            campaign-evidence [{:type :yield :iteration 1
                                :at "2026-01-01T00:00:01Z"}
                               {:type :resume :iteration 1
                                :at "2026-01-01T00:00:02Z"}]
            _ (h/run-and-apply!
               ctx {:command/id (random-uuid)
                    :command/timestamp (java.time.OffsetDateTime/now)
                    :command/name :sheet/store-execution-trace
                    :trace-id trace-id
                    :sheet-id sheet-id
                    :root-trace-id trace-id
                    :child-trace-ids []
                    :started-at "2026-01-01T00:00:00Z"
                    :completed-at "2026-01-01T00:00:03Z"
                    :duration-ms 3000
                    :status :success
                    :input-snapshot {}
                    :output-snapshot {}
                    :node-traces [{:node-id node-id
                                   :node-type :repl-researcher
                                   :status :success
                                   :started-at "2026-01-01T00:00:00Z"
                                   :completed-at "2026-01-01T00:00:03Z"}]
                    :researcher-iterations iteration-records
                    :researcher-events campaign-evidence})
            trace (first (evaluation/get-llm-traces
                          ctx {:sheet-id sheet-id :node-id node-id}))]
        (is (= iteration-records (:researcher-iterations trace))
            (pr-str trace))
        (is (= campaign-evidence (:researcher-events trace))
            (pr-str trace))))))
