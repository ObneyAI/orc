(ns ai.obney.orc.orc-service.checkpointed-researcher-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.rlm-fingerprint :as rlm-fingerprint]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.periodic-task.interface :as periodic]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]
            [malli.core :as m]))

(defn- with-checkpoint-effect-capabilities
  "Supply the durable-effect seam to direct executor tests.

   These tests deliberately isolate checkpoint encoding, resume, and iteration
   behavior from Grain command/projection behavior. RR-7's dedicated public
   tests exercise the real claim store and fail-closed boundary."
  [test-fn]
  (let [execute executor/execute-repl-researcher-rlm]
    (with-redefs [executor/execute-repl-researcher-rlm
                  (fn [node blackboard provider context & options]
                    (apply execute node blackboard provider
                           (merge {:researcher-ownership-epoch 1
                                   :claim-researcher-effect!
                                   (fn [_] {:command-result/events []})
                                   :complete-researcher-effect!
                                   (fn [_] {:command-result/events []})}
                                  context)
                           options))]
      (test-fn))))

(use-fixtures :each with-checkpoint-effect-capabilities)

(defn- checkpoint-command [sheet-id tick-id node-id checkpoint]
  {:command/id (random-uuid)
   :command/timestamp (time/now)
   :command/name :sheet/checkpoint-researcher-iteration
   :sheet-id sheet-id
   :tick-id tick-id
   :node-id node-id
   :checkpoint checkpoint
   :inputs {}})

(defn- iteration-commit-command
  [sheet-id tick-id node-id resume-state iteration-record]
  {:command/id (random-uuid)
   :command/timestamp (time/now)
   :command/name :sheet/checkpoint-researcher-iteration
   :sheet-id sheet-id
   :tick-id tick-id
   :node-id node-id
   :resume-state resume-state
   :iteration-record iteration-record
   :inputs {}})

(defn- invoke-read-model-api [symbol & args]
  (if-let [f (ns-resolve 'ai.obney.orc.orc-service.core.read-models symbol)]
    (apply f args)
    ::missing-read-model-api))

(defn- serialized-edn-bytes [value]
  (alength (.getBytes (pr-str value)
                      java.nio.charset.StandardCharsets/UTF_8)))

(defn checkpoint-predecessor [_]
  {:input "prepared-once"})

(def rr8-provider-entered (atom nil))

(defn rr8-fail-after-researcher-start [_]
  (when-let [entered @rr8-provider-entered]
    (when (= ::provider-not-entered (deref entered 3000 ::provider-not-entered))
      (throw (ex-info "researcher provider did not start" {}))))
  (throw (ex-info "parallel sibling ended the parent" {})))

(deftest det-e2e-235-automatic-recovery-recognises-a-researcher-frontier
  (testing "rebuilt runtimes resume a yielded campaign without execute or resume calls"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            definition
            (sheet/workflow "rr8-automatic-researcher-recovery"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "finish from the durable frontier"
                :writes [:summary]
                :max-iterations 3
                :rlm {:checkpointed? true
                      :quantum {:max-iterations 1}
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
            tick-id (random-uuid)
            now-ms (System/currentTimeMillis)
            resume-state {:version 2
                          :revision 1
                          :ownership-epoch 0
                          :next-iteration 1
                          :sandbox-vars {:memo "durable-before-restart"}
                          :var-creation-times {:memo 0}
                          :usage {:prompt-tokens 2
                                  :completion-tokens 1
                                  :total-tokens 3}
                          :cumulative-tree-ms 0
                          :iteration-attempts {}
                          :campaign-started-at-ms now-ms
                          :campaign-deadline-ms (+ now-ms 15000)}
            iteration-record {:iteration-index 0
                              :attempt-ordinal 0
                              :status :success
                              :started-at "2026-01-01T00:00:00Z"
                              :completed-at "2026-01-01T00:00:00.010Z"
                              :duration-ms 10
                              :code "(store! :memo \"durable-before-restart\")"
                              :generated-code-recorded? true
                              :emitted-tree-recorded? false}
            rebuilt-processors (atom nil)
            periodic-triggers (atom nil)]
        (try
          ;; The old process durably started the tick and saved one completed
          ;; quantum, then disappeared after the continuation start append.
          ;; Stopping the pubsub processors before these commands means the
          ;; abandoned start cannot execute in this process or be replayed when
          ;; the fresh processors subscribe. Only automatic recovery can append
          ;; a new start after restart.
          (h/stop-test-processors! ctx)
          (h/run-and-apply!
           ctx
           {:command/id (random-uuid)
            :command/timestamp (time/now)
            :command/name :sheet/tick-tree
            :sheet-id sheet-id
            :tick-id tick-id
            :inputs {}
            :options {:timeout-ms 15000}})
          (h/run-and-apply!
           ctx
           (iteration-commit-command sheet-id tick-id researcher-id
                                     resume-state iteration-record))

          (with-redefs [llm/predict
                        (fn [& _]
                          (swap! calls inc)
                          {:outputs
                           {:code "(final! {:summary (get-var :memo)})"}
                           :reasoning "finish after automatic recovery"
                           :usage {:prompt_tokens 2
                                   :completion_tokens 1
                                   :total_tokens 3}})]
            (let [base-ctx (dissoc ctx :processors)
                  processors (h/start-test-processors base-ctx)
                  _ (reset! rebuilt-processors processors)
                  triggers
                  (periodic/start-periodic-triggers!
                   {:append-fn #(es/append (:event-store ctx) %)
                    :tenant-ids-fn #(set (keys (es/tenants (:event-store ctx))))})]
              (reset! periodic-triggers triggers)
              (is (h/settle-until!
                   #(some? (runtime/durable-terminal-result ctx tick-id))
                   :timeout-ms 7000)
                  "the registered startup scan resumes the campaign without a caller")
              (let [result (runtime/durable-terminal-result ctx tick-id)
                    campaign (sheet/get-researcher-campaign
                              ctx tick-id researcher-id)
                    tenant-events (into [] (es/read (:event-store ctx)
                                                    {:tenant-id (:tenant-id ctx)}))
                    recovery-triggers
                    (filter #(= :sheet/recovery-scan-triggered (:event/type %))
                            tenant-events)
                    tick-events (h/read-tick-events ctx tick-id)
                    recovery-starts
                    (filter #(and (= :sheet/node-execution-started (:event/type %))
                                  (= researcher-id (:node-id %))
                                  (:resumed-from-event-id %))
                            tick-events)
                    recovery-start (first recovery-starts)
                    recovery-trigger (first recovery-triggers)
                    recovered-frontier
                    (first (filter #(and (= :rlm/researcher-frontier-claimed
                                            (:event/type %))
                                         (= tick-id (:tick-id %))
                                         (= researcher-id (:node-id %))
                                         (= 1 (:ownership-epoch %)))
                                   tenant-events))
                    recovered-iteration
                    (first (filter #(and (= :rlm/researcher-iteration-recorded
                                            (:event/type %))
                                         (= tick-id (:tick-id %))
                                         (= researcher-id (:node-id %))
                                         (= 1 (:iteration-index %)))
                                   tenant-events))
                    researcher-completion
                    (first (filter #(and (= :sheet/node-execution-completed
                                            (:event/type %))
                                         (= tick-id (:tick-id %))
                                         (= researcher-id (:node-id %)))
                                   tenant-events))
                    tick-completion
                    (first (filter #(and (= :sheet/tree-tick-completed
                                            (:event/type %))
                                         (= tick-id (:tick-id %)))
                                   tenant-events))
                    event-index (into {}
                                      (map-indexed
                                       (fn [index event]
                                         [(:event/id event) index]))
                                      tenant-events)]
                (is (= 1 (count recovery-triggers))
                    (pr-str (mapv :event/type tenant-events)))
                (is (= :success (:status result)) (pr-str result))
                (is (= :success (:status campaign)) (pr-str campaign))
                (is (some? (:completed-at campaign)) (pr-str campaign))
                (is (= "durable-before-restart"
                       (get-in result [:outputs :summary])))
                (is (= 1 @calls)
                    "the completed pre-restart provider turn is not repeated")
                (is (= 1 (count recovery-starts))
                    "automatic recovery appends exactly one recovered start")
                (is (= 1 (:researcher-ownership-epoch recovery-start))
                    "the recovered start carries latest durable frontier + 1")
                (is (= 1 (:ownership-epoch recovered-frontier))
                    "the researcher claims the carried recovery epoch")
                (is (apply < (map #(get event-index (:event/id %))
                                  [recovery-trigger recovery-start
                                   recovered-frontier recovered-iteration
                                   researcher-completion tick-completion]))
                    "durable recovery order is trigger, start, frontier, iteration, node, tick")
                (is (= [0 1]
                       (mapv :iteration-index
                             (rm/get-researcher-iteration-records
                              ctx sheet-id tick-id researcher-id)))))))
          (finally
            (when @periodic-triggers
              (periodic/stop-periodic-triggers! @periodic-triggers))
            (when @rebuilt-processors
              (h/stop-test-processors!
               (assoc ctx :processors @rebuilt-processors)))))))))

(deftest sqlite-reopen-automatically-recovers-a-researcher-campaign
  (testing "a fresh runtime discovers and resumes a campaign from persistent storage"
    (let [db-file (str "/tmp/rr8-automatic-recovery-" (random-uuid) ".db")
          event-store-conn {:type :sqlite
                            :database-file db-file
                            :maximum-pool-size 2}
          first-context (atom nil)
          reopened-context (atom nil)
          periodic-triggers (atom nil)
          calls (atom 0)]
      (try
        (let [ctx (h/create-async-test-context
                   {:context {:llm-provider :test}
                    :event-store-conn event-store-conn})
              _ (reset! first-context ctx)
              definition
              (sheet/workflow "rr8-sqlite-reopen-automatic-recovery"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "finish from the reopened durable frontier"
                  :writes [:summary]
                  :max-iterations 3
                  :rlm {:checkpointed? true
                        :quantum {:max-iterations 1}
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 3000
                                   :campaign-ms 15000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
              tick-id (random-uuid)
              now-ms (System/currentTimeMillis)
              resume-state {:version 2
                            :revision 1
                            :ownership-epoch 0
                            :next-iteration 1
                            :sandbox-vars {:memo "sqlite-survived-restart"}
                            :var-creation-times {:memo 0}
                            :usage {:prompt-tokens 2
                                    :completion-tokens 1
                                    :total-tokens 3}
                            :cumulative-tree-ms 0
                            :iteration-attempts {}
                            :campaign-started-at-ms now-ms
                            :campaign-deadline-ms (+ now-ms 15000)}
              iteration-record {:iteration-index 0
                                :attempt-ordinal 0
                                :status :success
                                :started-at "2026-01-01T00:00:00Z"
                                :completed-at "2026-01-01T00:00:00.010Z"
                                :duration-ms 10
                                :code "(store! :memo \"sqlite-survived-restart\")"
                                :generated-code-recorded? true
                                :emitted-tree-recorded? false}]
          ;; Persist the abandoned frontier without allowing this runtime's
          ;; subscribers to execute it. Closing the complete context below is
          ;; the restart boundary under test, not merely a processor rebuild.
          (h/stop-test-processors! ctx)
          (h/run-and-apply!
           ctx
           {:command/id (random-uuid)
            :command/timestamp (time/now)
            :command/name :sheet/tick-tree
            :sheet-id sheet-id
            :tick-id tick-id
            :inputs {}
            :options {:timeout-ms 15000}})
          (h/run-and-apply!
           ctx
           (iteration-commit-command sheet-id tick-id researcher-id
                                     resume-state iteration-record))
          (h/stop-async-context ctx)
          (reset! first-context nil)

          (with-redefs [llm/predict
                        (fn [& _]
                          (swap! calls inc)
                          {:outputs
                           {:code "(final! {:summary (get-var :memo)})"}
                           :reasoning "finish after a SQLite reopen"
                           :usage {:prompt_tokens 2
                                   :completion_tokens 1
                                   :total_tokens 3}})]
            (let [reopened (h/create-async-test-context
                            {:context {:llm-provider :test}
                             :event-store-conn event-store-conn})
                  _ (reset! reopened-context reopened)
                  triggers
                  (periodic/start-periodic-triggers!
                   {:append-fn #(es/append (:event-store reopened) %)
                    :tenant-ids-fn
                    #(set (keys (es/tenants (:event-store reopened))))})]
              (reset! periodic-triggers triggers)
              (is (h/settle-until!
                   #(some? (runtime/durable-terminal-result reopened tick-id))
                   :timeout-ms 7000)
                  "the fresh runtime recovers without execute or resume calls")
              (let [result (runtime/durable-terminal-result reopened tick-id)
                    campaign (sheet/get-researcher-campaign
                              reopened tick-id researcher-id)
                    tenant-events
                    (into [] (es/read (:event-store reopened)
                                      {:tenant-id (:tenant-id reopened)}))
                    recovery-triggers
                    (filter #(= :sheet/recovery-scan-triggered (:event/type %))
                            tenant-events)
                    recovery-starts
                    (filter #(and (= :sheet/node-execution-started
                                     (:event/type %))
                                  (= tick-id (:tick-id %))
                                  (= researcher-id (:node-id %))
                                  (:resumed-from-event-id %))
                            tenant-events)
                    recovered-frontiers
                    (filter #(and (= :rlm/researcher-frontier-claimed
                                     (:event/type %))
                                  (= tick-id (:tick-id %))
                                  (= researcher-id (:node-id %))
                                  (= 1 (:ownership-epoch %)))
                            tenant-events)]
                (is (= :success (:status result)) (pr-str result))
                (is (= "sqlite-survived-restart"
                       (get-in result [:outputs :summary])))
                (is (= :success (:status campaign)) (pr-str campaign))
                (is (= 1 @calls)
                    "the completed provider turn is not replayed after reopen")
                (is (= 1 (count recovery-triggers)))
                (is (= 1 (count recovery-starts))
                    "the reopened runtime appends exactly one recovered start")
                (is (= 1 (:researcher-ownership-epoch
                          (first recovery-starts))))
                (is (= 1 (count recovered-frontiers)))
                (is (= [0 1]
                       (mapv :iteration-index
                             (rm/get-researcher-iteration-records
                              reopened sheet-id tick-id researcher-id))))))))
        (finally
          (when @periodic-triggers
            (periodic/stop-periodic-triggers! @periodic-triggers))
          (when @reopened-context
            (h/stop-async-context @reopened-context))
          (when @first-context
            (h/stop-async-context @first-context))
          (doseq [suffix ["" "-wal" "-shm"]]
            (io/delete-file (str db-file suffix) true)))))))

(deftest automatic-recovery-advances-past-a-post-checkpoint-frontier
  (testing "a crash after claiming the next frontier cannot wedge the saved campaign"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            definition
            (sheet/workflow "rr8-post-claim-recovery"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "finish beyond the crashed frontier"
                :writes [:summary]
                :max-iterations 3
                :rlm {:checkpointed? true
                      :quantum {:max-iterations 1}
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
            tick-id (random-uuid)
            now-ms (System/currentTimeMillis)
            resume-state {:version 2
                          :revision 1
                          :ownership-epoch 1
                          :next-iteration 1
                          :sandbox-vars {:memo "saved-at-epoch-one"}
                          :var-creation-times {:memo 0}
                          :usage {:prompt-tokens 2
                                  :completion-tokens 1
                                  :total-tokens 3}
                          :cumulative-tree-ms 0
                          :iteration-attempts {}
                          :campaign-started-at-ms now-ms
                          :campaign-deadline-ms (+ now-ms 15000)}
            iteration-record {:iteration-index 0
                              :attempt-ordinal 0
                              :status :success
                              :started-at "2026-01-01T00:00:00Z"
                              :completed-at "2026-01-01T00:00:00.010Z"
                              :duration-ms 10
                              :code "(store! :memo \"saved-at-epoch-one\")"
                              :generated-code-recorded? true
                              :emitted-tree-recorded? false}
            rebuilt-processors (atom nil)
            periodic-triggers (atom nil)]
        (try
          (h/stop-test-processors! ctx)
          (h/run-and-apply!
           ctx
           {:command/id (random-uuid)
            :command/timestamp (time/now)
            :command/name :sheet/tick-tree
            :sheet-id sheet-id
            :tick-id tick-id
            :inputs {}
            :options {:timeout-ms 15000}})
          (h/run-and-apply!
           ctx
           {:command/id (random-uuid)
            :command/timestamp (time/now)
            :command/name :sheet/claim-researcher-frontier
            :sheet-id sheet-id
            :tick-id tick-id
            :node-id researcher-id
            :ownership-epoch 1
            :claimed-at "2026-01-01T00:00:00Z"})
          (h/run-and-apply!
           ctx
           (iteration-commit-command sheet-id tick-id researcher-id
                                     resume-state iteration-record))
          ;; The continuation acquired epoch 2 and the process died before it
          ;; could save another iteration. Resume state therefore still says 1.
          (h/run-and-apply!
           ctx
           {:command/id (random-uuid)
            :command/timestamp (time/now)
            :command/name :sheet/claim-researcher-frontier
            :sheet-id sheet-id
            :tick-id tick-id
            :node-id researcher-id
            :ownership-epoch 2
            :claimed-at "2026-01-01T00:00:01Z"})

          (with-redefs [llm/predict
                        (fn [& _]
                          (swap! calls inc)
                          {:outputs
                           {:code "(final! {:summary (get-var :memo)})"}
                           :reasoning "finish beyond the crashed owner"
                           :usage {:prompt_tokens 2
                                   :completion_tokens 1
                                   :total_tokens 3}})]
            (let [base-ctx (dissoc ctx :processors)
                  processors (h/start-test-processors base-ctx)
                  _ (reset! rebuilt-processors processors)
                  triggers
                  (periodic/start-periodic-triggers!
                   {:append-fn #(es/append (:event-store ctx) %)
                    :tenant-ids-fn #(set (keys (es/tenants (:event-store ctx))))})]
              (reset! periodic-triggers triggers)
              (is (h/settle-until!
                   #(some? (runtime/durable-terminal-result ctx tick-id))
                   :timeout-ms 7000))
              (let [events (h/read-tick-events ctx tick-id)
                    recovered-start
                    (first (filter #(and (= :sheet/node-execution-started
                                            (:event/type %))
                                         (= researcher-id (:node-id %))
                                         (:resumed-from-event-id %))
                                   events))
                    frontier-epochs
                    (mapv :ownership-epoch
                          (filter #(= :rlm/researcher-frontier-claimed
                                      (:event/type %))
                                  events))
                    stale-state (-> resume-state
                                    (assoc :revision 2
                                           :ownership-epoch 2
                                           :next-iteration 3))
                    stale-record (assoc iteration-record
                                        :iteration-index 2
                                        :code "(final! {:summary \"stale\"})")
                    stale-result
                    (h/run-and-apply!
                     ctx
                     (iteration-commit-command sheet-id tick-id researcher-id
                                               stale-state stale-record))
                    records (rm/get-researcher-iteration-records
                             ctx sheet-id tick-id researcher-id)]
                (is (= 3 (:researcher-ownership-epoch recovered-start)))
                (is (= [1 2 3] frontier-epochs))
                (is (= 1 @calls))
                (is (= [0 1] (mapv :iteration-index records)))
                (is (= ::anom/conflict (::anom/category stale-result))
                    (pr-str stale-result)))))
          (finally
            (when @periodic-triggers
              (periodic/stop-periodic-triggers! @periodic-triggers))
            (when @rebuilt-processors
              (h/stop-test-processors!
               (assoc ctx :processors @rebuilt-processors)))))))))

(deftest concurrent-and-repeated-recovery-converge-on-one-start
  (testing "racing scanners, a periodic scan, and a later explicit scan are idempotent"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            definition
            (sheet/workflow "rr8-concurrent-recovery"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "finish once despite recovery races"
                :writes [:summary]
                :max-iterations 3
                :rlm {:checkpointed? true
                      :quantum {:max-iterations 1}
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
            tick-id (random-uuid)
            now-ms (System/currentTimeMillis)
            resume-state {:version 2
                          :revision 1
                          :ownership-epoch 0
                          :next-iteration 1
                          :sandbox-vars {:memo "one-winner"}
                          :var-creation-times {:memo 0}
                          :usage {:prompt-tokens 2
                                  :completion-tokens 1
                                  :total-tokens 3}
                          :cumulative-tree-ms 0
                          :iteration-attempts {}
                          :campaign-started-at-ms now-ms
                          :campaign-deadline-ms (+ now-ms 15000)}
            iteration-record {:iteration-index 0
                              :attempt-ordinal 0
                              :status :success
                              :started-at "2026-01-01T00:00:00Z"
                              :completed-at "2026-01-01T00:00:00.010Z"
                              :duration-ms 10
                              :code "(store! :memo \"one-winner\")"
                              :generated-code-recorded? true
                              :emitted-tree-recorded? false}
            rebuilt-processors (atom nil)
            periodic-triggers (atom nil)]
        (try
          (h/stop-test-processors! ctx)
          (h/run-and-apply!
           ctx
           {:command/id (random-uuid)
            :command/timestamp (time/now)
            :command/name :sheet/tick-tree
            :sheet-id sheet-id
            :tick-id tick-id
            :inputs {}
            :options {:timeout-ms 15000}})
          (h/run-and-apply!
           ctx
           (iteration-commit-command sheet-id tick-id researcher-id
                                     resume-state iteration-record))

          (with-redefs [llm/predict
                        (fn [& _]
                          (swap! calls inc)
                          {:outputs {:code "(final! {:summary (get-var :memo)})"}
                           :reasoning "one recovery winner"
                           :usage {:prompt_tokens 2
                                   :completion_tokens 1
                                   :total_tokens 3}})]
            (let [base-ctx (dissoc ctx :processors)
                  processors (h/start-test-processors base-ctx)
                  _ (reset! rebuilt-processors processors)
                  process-command cp/process-command
                  ready (java.util.concurrent.CountDownLatch. 2)
                  release (promise)
                  scans
                  (with-redefs [cp/process-command
                                (fn [command-ctx]
                                  (if (= :sheet/resume-node-execution
                                         (get-in command-ctx
                                                 [:command :command/name]))
                                    (do
                                      (.countDown ready)
                                      @release
                                      (process-command command-ctx))
                                    (process-command command-ctx)))]
                    (let [workers [(future (sheet/resume-in-progress! ctx))
                                   (future (sheet/resume-in-progress! ctx))]]
                      (is (.await ready 2 java.util.concurrent.TimeUnit/SECONDS)
                          "both scanners reach the same abandoned frontier")
                      (deliver release true)
                      (mapv #(deref % 5000 ::timeout) workers)))
                  triggers
                  (periodic/start-periodic-triggers!
                   {:append-fn #(es/append (:event-store ctx) %)
                    :tenant-ids-fn #(set (keys (es/tenants (:event-store ctx))))})]
              (reset! periodic-triggers triggers)
              (is (not-any? #{::timeout} scans) (pr-str scans))
              (is (h/settle-until!
                   #(some? (runtime/durable-terminal-result ctx tick-id))
                   :timeout-ms 7000))
              (let [post-completion-scan (sheet/resume-in-progress! ctx)
                    events (into [] (es/read (:event-store ctx)
                                            {:tenant-id (:tenant-id ctx)}))
                    recovery-starts
                    (filter #(and (= :sheet/node-execution-started
                                     (:event/type %))
                                  (= tick-id (:tick-id %))
                                  (= researcher-id (:node-id %))
                                  (:resumed-from-event-id %))
                            events)
                    frontiers
                    (filter #(and (= :rlm/researcher-frontier-claimed
                                     (:event/type %))
                                  (= tick-id (:tick-id %))
                                  (= researcher-id (:node-id %)))
                            events)
                    triggers-seen
                    (filter #(= :sheet/recovery-scan-triggered (:event/type %))
                            events)]
                (is (= 1 (count (filter :resumed? (mapcat identity scans))))
                    (pr-str scans))
                (is (= 1 (count recovery-starts)))
                (is (= [1] (mapv :ownership-epoch frontiers)))
                (is (= 1 @calls))
                (is (= [0 1]
                       (mapv :iteration-index
                             (rm/get-researcher-iteration-records
                              ctx sheet-id tick-id researcher-id))))
                (is (seq triggers-seen)
                    "the ordinary periodic runtime also scans the completed tenant")
                (is (empty? post-completion-scan)
                    "an explicit scan after completion changes nothing"))))
          (finally
            (when @periodic-triggers
              (periodic/stop-periodic-triggers! @periodic-triggers))
            (when @rebuilt-processors
              (h/stop-test-processors!
               (assoc ctx :processors @rebuilt-processors)))))))))

(deftest terminal-parent-abandons-an-in-flight-researcher-without-a-verdict
  (testing "a campaign still running when its enclosing execution ends is abandoned"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-entered (promise)
            release-provider (promise)
            periodic-triggers (atom nil)]
        (reset! rr8-provider-entered provider-entered)
        (try
          (with-redefs [llm/predict
                        (fn [& _]
                          (deliver provider-entered true)
                          @release-provider
                          {:outputs {:code "(final! {:summary \"too-late\"})"}
                           :reasoning "the parent has already ended"
                           :usage {:prompt_tokens 1
                                   :completion_tokens 1
                                   :total_tokens 2}})]
            (let [definition
                  (sheet/workflow "rr8-abandoned-campaign"
                    (sheet/blackboard {:summary :string})
                    (sheet/parallel "race-parent"
                      {:success-policy :all :failure-policy :any}
                      (sheet/repl-researcher "researcher"
                        :instruction "remain in flight while the sibling fails"
                        :writes [:summary]
                        :max-iterations 2
                        :rlm {:checkpointed? true
                              :quantum {:max-iterations 1}
                              :timeouts {:provider-ms 10000
                                         :iteration-ms 12000
                                         :campaign-ms 15000}})
                      (sheet/code "failure"
                        :fn "ai.obney.orc.orc-service.checkpointed-researcher-test/rr8-fail-after-researcher-start"
                        :reads []
                        :writes [])))
                  sheet-id (sheet/build-workflow! ctx definition)
                  nodes (sheet/get-nodes-for-sheet ctx sheet-id)
                  researcher-id (:id (first (filter #(= :repl-researcher (:type %))
                                                    nodes)))
                  tick-id (random-uuid)
                  result (sheet/execute ctx sheet-id {} :tick-id tick-id
                                        :timeout-ms 7000)
                  campaign-api
                  (ns-resolve 'ai.obney.orc.orc-service.interface
                              'get-researcher-campaign)
                  campaign (if campaign-api
                             (campaign-api ctx tick-id researcher-id)
                             ::missing-campaign-api)
                  triggers
                  (periodic/start-periodic-triggers!
                   {:append-fn #(es/append (:event-store ctx) %)
                    :tenant-ids-fn #(set (keys (es/tenants (:event-store ctx))))})]
              (reset! periodic-triggers triggers)
              (is (= :failure (:status result)) (pr-str result))
              (is (= true (deref provider-entered 100 ::provider-not-entered)))
              (is (h/settle-until!
                   #(some (fn [event]
                            (= :sheet/recovery-scan-triggered (:event/type event)))
                          (into [] (es/read (:event-store ctx)
                                           {:tenant-id (:tenant-id ctx)})))
                   :timeout-ms 3000))
              (let [explicit-scan (sheet/resume-in-progress! ctx)
                    events (h/read-tick-events ctx tick-id)
                    recovery-starts
                    (filter #(and (= :sheet/node-execution-started
                                     (:event/type %))
                                  (= researcher-id (:node-id %))
                                  (:resumed-from-event-id %))
                            events)
                    frontiers
                    (filter #(and (= :rlm/researcher-frontier-claimed
                                     (:event/type %))
                                  (= researcher-id (:node-id %)))
                            events)]
                (is (= :abandoned (:status campaign)) (pr-str campaign))
                (is (map? campaign) (pr-str campaign))
                (is (nil? (:completed-at campaign)) (pr-str campaign))
                (is (nil? (:verdict-at campaign)) (pr-str campaign))
                (is (= [1] (mapv :ownership-epoch frontiers))
                    "the durable ownership evidence remains queryable")
                (is (empty? recovery-starts)
                    "the periodic scan never resumes a terminal parent's campaign")
                (is (empty? explicit-scan)
                    "explicit recovery uses the same terminal-parent fence"))))
          (finally
            (deliver release-provider true)
            (reset! rr8-provider-entered nil)
            (when @periodic-triggers
              (periodic/stop-periodic-triggers! @periodic-triggers))))))))

(deftest yielded-campaign-transitions-to-abandoned-without-completion
  (let [sheet-id (random-uuid)
        tick-id (random-uuid)
        node-id (random-uuid)
        started {:event/type :sheet/tree-tick-started
                 :event/timestamp (time/now)
                 :sheet-id sheet-id
                 :tick-id tick-id}
        yielded {:event/type :rlm/researcher-resume-state-saved
                 :event/timestamp (time/now)
                 :sheet-id sheet-id
                 :tick-id tick-id
                 :node-id node-id
                 :yielded? true
                 :resume-state {:ownership-epoch 4
                                :next-iteration 2
                                :campaign-started-at-ms 100
                                :campaign-deadline-ms 10000}}
        parent-ended {:event/type :sheet/tree-tick-completed
                      :event/timestamp (time/now)
                      :sheet-id sheet-id
                      :tick-id tick-id
                      :root-status :failure}
        before (-> {}
                   (rm/researcher-campaigns* started)
                   (rm/researcher-campaigns* yielded))
        after (rm/researcher-campaigns* before parent-ended)
        campaign (get-in after [tick-id :campaigns node-id])]
    (is (= :yielded (get-in before [tick-id :campaigns node-id :status])))
    (is (= :abandoned (:status campaign)))
    (is (nil? (:completed-at campaign)))
    (is (nil? (:verdict-at campaign)))
    (is (= 4 (:ownership-epoch campaign)))
    (is (= 2 (:next-iteration-index campaign)))))

(deftest iteration-record-and-resume-state-are-distinct-atomic-facts
  (testing "one command records immutable history and supersedable continuation state"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            resume-state {:version 2
                          :revision 1
                          :next-iteration 1
                          :sandbox-vars {:memo "kept"}
                          :var-creation-times {:memo 0}
                          :usage {:prompt-tokens 2 :completion-tokens 1 :total-tokens 3}
                          :cumulative-tree-ms 0
                          :iteration-attempts {}
                          :campaign-started-at-ms 100
                          :campaign-deadline-ms 10000}
            iteration-record {:iteration-index 0
                              :attempt-ordinal 0
                              :status :success
                              :started-at "2026-01-01T00:00:00Z"
                              :completed-at "2026-01-01T00:00:00.010Z"
                              :duration-ms 10
                              :generated-code-recorded? true
                              :emitted-tree-recorded? false
                              :code "(store! :memo \"kept\")"
                              :result-profile {:type :string
                                               :length 4
                                               :word-count 1
                                               :line-count 1}}
            command #(iteration-commit-command sheet-id tick-id node-id
                                                resume-state iteration-record)
            first-result (h/run-and-apply! ctx (command))
            duplicate-result (h/run-and-apply! ctx (command))
            tick-events (into [] (es/read (:event-store ctx)
                                          {:tenant-id (:tenant-id ctx)
                                           :tags #{[:tick tick-id] [:node node-id]}}))
            durable-types (->> tick-events
                               (map :event/type)
                               (filter #{:rlm/researcher-iteration-recorded
                                         :rlm/researcher-resume-state-saved})
                               vec)]
        (is (= [:rlm/researcher-iteration-recorded
                :rlm/researcher-resume-state-saved
                :sheet/node-execution-started]
               (mapv :event/type (:command-result/events first-result))))
        (is (empty? (:command-result/events duplicate-result)))
        (is (= [:rlm/researcher-iteration-recorded
                :rlm/researcher-resume-state-saved]
               durable-types))
        (is (= resume-state
               (:resume-state
                (invoke-read-model-api 'get-researcher-resume-state
                                       ctx sheet-id tick-id node-id))))
        (is (= [iteration-record]
               (invoke-read-model-api 'get-researcher-iteration-records
                                      ctx sheet-id tick-id node-id)))
        (is (not (contains? resume-state :history))
            "resume state must not carry immutable iteration history")))))

(deftest version-two-command-validates-state-and-attempt-identity
  (let [schema (ai.obney.orc.orc-service.interface.schemas/commands
                :sheet/checkpoint-researcher-iteration)
        command {:sheet-id (random-uuid)
                 :tick-id (random-uuid)
                 :node-id (random-uuid)
                 :resume-state {:version 2
                                :revision 1
                                :next-iteration 1
                                :sandbox-vars {}
                                :var-creation-times {}
                                :usage {:prompt-tokens 0
                                        :completion-tokens 0
                                        :total-tokens 0}
                                :cumulative-tree-ms 0
                                :iteration-attempts {}
                                :campaign-started-at-ms 100
                                :campaign-deadline-ms 10000}
                 :iteration-record {:iteration-index 0
                                    :attempt-ordinal 0
                                    :status :success}
                 :inputs {}}]
    (is (m/validate schema command))
    (is (not (m/validate schema
                         (assoc-in command [:resume-state :history] [])))
        "v2 continuation state may not smuggle immutable history back into the blob")
    (is (not (m/validate schema
                         (assoc-in command [:resume-state :terminal-result]
                                   {:status :success})))
        "terminal results are campaign facts, not continuation state")
    (is (not (m/validate schema
                         (assoc-in command [:iteration-record :status] :running)))
        "only terminal attempts can become immutable iteration records")
    (is (not (m/validate schema
                         (update command :iteration-record dissoc :attempt-ordinal)))
        "attempt identity is required rather than defaulted at the command boundary")
    (is (not (m/validate schema
                         (assoc-in command [:iteration-record :result]
                                   "raw-payload")))
        "the public command boundary rejects fields outside the evidence digest")
    (doseq [[profile-path profile]
            [[[:iteration-record :variable-delta]
              {:created-keys [] :updated-keys [] :removed-keys []
               :raw-value "raw-payload"}]
             [[:iteration-record :result-profile]
              {:type :string :length 11 :raw-value "raw-payload"}]
             [[:iteration-record :stdout-profile]
              {:type :string :length 11 :raw-value "raw-payload"}]]]
      (is (not (m/validate schema
                           (assoc-in command profile-path profile)))
          (str "the public command boundary rejects nested fields outside "
               profile-path)))
    (doseq [[field limit]
            [[:reasoning executor/iteration-reasoning-max-chars]
             [:error-excerpt executor/iteration-error-excerpt-max-chars]]]
      (is (m/validate schema
                      (assoc-in command [:iteration-record field]
                                (apply str (repeat limit "x"))))
          (str field " accepts the measured boundary"))
      (is (not (m/validate schema
                           (assoc-in command [:iteration-record field]
                                     (apply str (repeat (inc limit) "x")))))
          (str field " rejects evidence above the measured boundary")))
    (doseq [required-key [:sandbox-vars :var-creation-times :usage
                          :cumulative-tree-ms :iteration-attempts
                          :campaign-started-at-ms :campaign-deadline-ms]]
      (is (not (m/validate schema
                           (update command :resume-state dissoc required-key)))
          (str required-key " is required to resume the same campaign")))))

(deftest stale-version-two-commit-cannot-rewind-the-resume-frontier
  (h/with-async-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          state-at-frontier-2 {:version 2
                               :revision 2
                               :next-iteration 2
                               :sandbox-vars {}
                               :var-creation-times {}
                               :usage {:prompt-tokens 0
                                       :completion-tokens 0
                                       :total-tokens 0}
                               :cumulative-tree-ms 0
                               :iteration-attempts {}
                               :campaign-started-at-ms 100
                               :campaign-deadline-ms 10000}
          iteration-1 {:iteration-index 1
                       :attempt-ordinal 0
                       :status :success}
          stale-state {:version 2
                       :revision 1
                       :next-iteration 1
                       :sandbox-vars {}
                       :var-creation-times {}
                       :usage {:prompt-tokens 0
                               :completion-tokens 0
                               :total-tokens 0}
                       :cumulative-tree-ms 0
                       :iteration-attempts {0 1}
                       :campaign-started-at-ms 100
                       :campaign-deadline-ms 10000}
          stale-attempt {:iteration-index 0
                         :attempt-ordinal 1
                         :status :success}]
      (h/run-and-apply!
       ctx (assoc (iteration-commit-command sheet-id tick-id node-id
                                             state-at-frontier-2 iteration-1)
                  :resume? false))
      (let [stale-result
            (h/run-and-apply!
             ctx (assoc (iteration-commit-command sheet-id tick-id node-id
                                                   stale-state stale-attempt)
                        :resume? false))
            projected-state
            (:resume-state
             (rm/get-researcher-resume-state ctx sheet-id tick-id node-id))
            records
            (rm/get-researcher-iteration-records ctx sheet-id tick-id node-id)]
        (is (empty? (:command-result/events stale-result))
            "a stale continuation and its record are rejected as one atomic batch")
        (is (= state-at-frontier-2 projected-state)
            "last durable continuation state cannot move backward")
        (is (= [[1 0]]
               (mapv (juxt :iteration-index :attempt-ordinal) records))
            "the rejected stale batch cannot append a detached iteration record")))))

(deftest checkpoint-command-persists-and-fences-duplicate-resume
  (testing "checkpoint and continuation start are one durable, idempotent command batch"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            checkpoint {:version 1
                        :next-iteration 1
                        :history [{:code "(store! :memo 1)" :result 1}]
                        :sandbox-vars {:memo 1}
                        :var-creation-times {:memo 1}
                        :usage {:prompt-tokens 2 :completion-tokens 1 :total-tokens 3}
                        :cumulative-tree-ms 0
                        :campaign-started-at-ms 100
                        :campaign-deadline-ms 10000}
            first-result (h/run-and-apply!
                          ctx (checkpoint-command sheet-id tick-id node-id checkpoint))
            duplicate-result (h/run-and-apply!
                              ctx (checkpoint-command sheet-id tick-id node-id checkpoint))
            projected (rm/get-researcher-checkpoint ctx sheet-id tick-id node-id)]
        (is (= [:rlm/researcher-checkpointed :sheet/node-execution-started]
               (mapv :event/type (:command-result/events first-result))))
        (is (empty? (:command-result/events duplicate-result)))
        (is (= checkpoint (:checkpoint projected)))))))

(deftest abandoned-frontier-resume-is-cas-fenced
  (let [ctx (h/create-test-context)]
    (try
      (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          original (es/->event
                    {:type :sheet/node-execution-started
                     :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
                     :body {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                            :inputs {}}})
          _ (es/append (:event-store ctx)
                       {:tenant-id (:tenant-id ctx) :events [original]})
          original-start-event-id
          (:event/id (first (into [] (es/read (:event-store ctx)
                                              {:tenant-id (:tenant-id ctx)
                                               :tags #{[:tick tick-id]}}))))
          command (fn []
                    {:command/id (random-uuid)
                     :command/timestamp (time/now)
                     :command/name :sheet/resume-node-execution
                     :sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :original-start-event-id original-start-event-id
                     :inputs {}})
          first-result (h/run-and-apply! ctx (command))
          duplicate-result (h/run-and-apply! ctx (command))]
      (is (= 1 (count (:command-result/events first-result))))
      (is (empty? (:command-result/events duplicate-result)))
      (is (= original-start-event-id
             (-> first-result :command-result/events first :resumed-from-event-id))))
      (finally
        (h/stop-context ctx)))))

(deftest sqlite-restart-rehydrates-the-next-iteration
  (testing "a newly opened JVM store can replay the checkpoint and continue"
    (let [db-file (str "/tmp/checkpointed-researcher-" (random-uuid) ".db")
          tenant-id (random-uuid)
          support (h/create-test-context)
          sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          checkpoint {:version 1
                      :next-iteration 1
                      :history [{:code "(store! :memo \"persisted\")"}]
                      :sandbox-vars {:memo "persisted"}
                      :var-creation-times {:memo 1}
                      :usage {:prompt-tokens 2 :completion-tokens 1 :total-tokens 3}
                      :cumulative-tree-ms 0
                      :campaign-started-at-ms (System/currentTimeMillis)
                      :campaign-deadline-ms (+ (System/currentTimeMillis) 60000)}]
      (try
        (let [store (es/start {:conn {:type :sqlite :database-file db-file
                                      :maximum-pool-size 2}})
              ctx (assoc support
                         :event-store store
                         :tenant-id tenant-id
                         :auth-claims {:subject "test"}
                         :command-registry (cp/global-command-registry))
              result (cp/process-command
                      (assoc ctx :command
                             (checkpoint-command sheet-id tick-id node-id checkpoint)))
              append-result (es/append store {:tenant-id tenant-id
                                               :events (:command-result/events result)})]
          (is (= 2 (count (:command-result/events result))) (pr-str result))
          (is (nil? (:cognitect.anomalies/category append-result)) (pr-str append-result))
          (es/stop store))
        (let [reopened (es/start {:conn {:type :sqlite :database-file db-file
                                         :maximum-pool-size 2}})
              events (into [] (es/read reopened {:tenant-id tenant-id
                                                  :tags #{[:sheet sheet-id]}}))
              projected (get (reduce rm/researcher-checkpoints* {} events)
                             [tick-id node-id])
              calls (atom 0)
              node {:type :repl-researcher
                    :instruction "finish"
                    :writes [:summary]
                    :rlm {:checkpointed? true}
                    :max-iterations 3}]
          (try
            (with-redefs [llm/predict
                          (fn [& _]
                            (swap! calls inc)
                            {:outputs {:code "(final! {:summary (get-var :memo)})"}})]
              (let [result (executor/execute-repl-researcher-rlm
                            node {} :openrouter
                            {:tick-id tick-id
                             :node-id node-id
                             :researcher-checkpoint (:checkpoint projected)})]
                (is (= :success (:status result)) (pr-str result))
                (is (= "persisted" (get-in result [:outputs :summary])))
                (is (= 1 @calls))))
            (finally (es/stop reopened))))
        (finally
          (h/stop-context support)
          (doseq [suffix ["" "-wal" "-shm"]]
            (io/delete-file (str db-file suffix) true)))))))

(deftest sqlite-restart-rehydrates-v2-state-and-records-without-replaying-a-turn
  (testing "a reopened store resumes v2 at its frontier from separate durable facts"
    (let [db-file (str "/tmp/checkpointed-researcher-v2-" (random-uuid) ".db")
          tenant-id (random-uuid)
          support (h/create-test-context)
          sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          now-ms (System/currentTimeMillis)
          memo-value "RR5-REOPENED-MEMO-PAYLOAD"
          counter-value "RR5-REOPENED-UPDATED-PAYLOAD"
          resume-state {:version 2
                        :revision 1
                        :next-iteration 1
                        :sandbox-vars {:memo memo-value
                                       :counter counter-value}
                        :var-creation-times {:memo 0 :counter 0}
                        :usage {:prompt-tokens 2 :completion-tokens 1 :total-tokens 3}
                        :cumulative-tree-ms 0
                        :iteration-attempts {}
                        :campaign-started-at-ms now-ms
                        :campaign-deadline-ms (+ now-ms 60000)}
          iteration-record {:iteration-index 0
                            :attempt-ordinal 0
                            :status :success
                            :started-at "2026-01-01T00:00:00Z"
                            :completed-at "2026-01-01T00:00:00.010Z"
                            :duration-ms 10
                            :code "(store! :memo (get-input :source))"
                            :reasoning "the completed turn"
                            :generated-code-recorded? true
                            :emitted-tree-recorded? false
                            :variable-delta {:created-keys [:memo]
                                             :updated-keys [:counter]
                                             :removed-keys [:obsolete]}
                            :result-profile {:type :string :length 25
                                             :word-count 1 :line-count 1}}]
      (try
        (let [store (es/start {:conn {:type :sqlite :database-file db-file
                                      :maximum-pool-size 2}})
              ctx (assoc support
                         :event-store store
                         :tenant-id tenant-id
                         :auth-claims {:subject "test"}
                         :command-registry (cp/global-command-registry))
              result (cp/process-command
                      (assoc ctx :command
                             (assoc (iteration-commit-command
                                     sheet-id tick-id node-id
                                     resume-state iteration-record)
                                    :resume? false)))
              append-result (es/append store {:tenant-id tenant-id
                                               :events (:command-result/events result)})]
          (is (= 2 (count (:command-result/events result))) (pr-str result))
          (is (nil? (:cognitect.anomalies/category append-result)) (pr-str append-result))
          (es/stop store))
        (let [reopened (es/start {:conn {:type :sqlite :database-file db-file
                                         :maximum-pool-size 2}})
              ctx (assoc support :event-store reopened :tenant-id tenant-id)
              projected-state (rm/get-researcher-resume-state
                               ctx sheet-id tick-id node-id)
              projected-records (rm/get-researcher-iteration-records
                                 ctx sheet-id tick-id node-id)
              calls (atom 0)
              prompt-history (atom nil)
              node {:type :repl-researcher
                    :instruction "finish after restart"
                    :writes [:summary]
                    :rlm {:checkpointed? true}
                    :max-iterations 3}]
          (try
            (with-redefs [llm/predict
                          (fn [_provider _module inputs _options]
                            (swap! calls inc)
                            (reset! prompt-history (:history inputs))
                            {:outputs {:code "(final! {:summary (get-var :memo)})"}})]
              (let [result (executor/execute-repl-researcher-rlm
                            node {} :openrouter
                            {:tick-id tick-id
                             :node-id node-id
                             :researcher-resume-state (:resume-state projected-state)
                             :researcher-iteration-records projected-records})]
                (is (= resume-state (:resume-state projected-state)))
                (is (= [iteration-record] projected-records))
                (is (= :success (:status result)) (pr-str result))
                (is (= memo-value (get-in result [:outputs :summary])))
                (is (= 1 @calls) "the completed provider turn is not replayed")
                (is (str/includes? @prompt-history
                                   "(store! :memo (get-input :source))")
                    "the prompt contains code sourced only from the reopened record")
                (is (str/includes? @prompt-history "Variables created: :memo"))
                (is (str/includes? @prompt-history "Variables updated: :counter"))
                (is (str/includes? @prompt-history "Variables removed: :obsolete"))
                (doseq [payload [memo-value counter-value]]
                  (is (not (str/includes? @prompt-history payload))
                      (str "resume history embedded payload value: " payload)))))
            (finally (es/stop reopened))))
        (finally
          (h/stop-context support)
          (doseq [suffix ["" "-wal" "-shm"]]
            (io/delete-file (str db-file suffix) true)))))))

(deftest sqlite-reopen-preserves-inline-source-for-fresh-re-emission
  (testing "reopened authored source resumes without replay and recompiles outside the old registry"
    (let [db-file (str "/tmp/rr6-inline-source-" (random-uuid) ".db")
          tenant-id (random-uuid)
          support (h/create-test-context)
          sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          now-ms (System/currentTimeMillis)
          source-tree
          '[:sequence
            [:code {:reads [:n]
                    :writes [:doubled]
                    :fn (fn [{:keys [inputs]}]
                          {:doubled (* 2 (:n inputs))})}]
            [:final {:keys [:doubled]}]]
          source-fingerprint (rlm-fingerprint/fingerprint source-tree)
          resume-state {:version 2
                        :revision 1
                        :next-iteration 1
                        :sandbox-vars {:doubled 22}
                        :var-creation-times {:doubled 0}
                        :usage {:prompt-tokens 4
                                :completion-tokens 3
                                :total-tokens 7}
                        :cumulative-tree-ms 1
                        :iteration-attempts {}
                        :campaign-started-at-ms now-ms
                        :campaign-deadline-ms (+ now-ms 60000)}
          iteration-record {:iteration-index 0
                            :attempt-ordinal 0
                            :status :success
                            :started-at "2026-01-01T00:00:00Z"
                            :completed-at "2026-01-01T00:00:00.010Z"
                            :duration-ms 10
                            :code "(emit-tree! (quote ...))"
                            :generated-code-recorded? true
                            :emitted-tree-recorded? true
                            :emitted-tree source-tree
                            :emitted-tree-source (pr-str source-tree)
                            :tree-fingerprint source-fingerprint
                            :result-profile {:type :map :length 1}}
          resume-calls (atom 0)
          reemit-calls (atom 0)]
      (try
        (let [store (es/start {:conn {:type :sqlite
                                      :database-file db-file
                                      :maximum-pool-size 2}})
              ctx (assoc support
                         :event-store store
                         :tenant-id tenant-id
                         :auth-claims {:subject "test"}
                         :command-registry (cp/global-command-registry))
              result (cp/process-command
                      (assoc ctx :command
                             (assoc (iteration-commit-command
                                     sheet-id tick-id node-id
                                     resume-state iteration-record)
                                    :resume? false)))
              append-result (es/append
                             store
                             {:tenant-id tenant-id
                              :events (:command-result/events result)})]
          (is (= 2 (count (:command-result/events result))) (pr-str result))
          (is (nil? (:cognitect.anomalies/category append-result))
              (pr-str append-result))
          (es/stop store))

        (let [reopened (es/start {:conn {:type :sqlite
                                         :database-file db-file
                                         :maximum-pool-size 2}})
              ctx (assoc support :event-store reopened :tenant-id tenant-id)]
          (try
            (let [projected-state (rm/get-researcher-resume-state
                                   ctx sheet-id tick-id node-id)
                  projected-records (rm/get-researcher-iteration-records
                                     ctx sheet-id tick-id node-id)
                  projected-source (:emitted-tree (first projected-records))
                  projected-source-text
                  (:emitted-tree-source (first projected-records))
                  reopened-events (into [] (es/read reopened
                                                     {:tenant-id tenant-id
                                                      :tags #{[:tick tick-id]}}))
                  node {:type :repl-researcher
                        :instruction "finish from the reopened frontier"
                        :writes [:doubled]
                        :rlm {:checkpointed? true}
                        :max-iterations 2}]
              (is (= source-tree projected-source) (pr-str projected-records))
              (is (= source-tree (read-string projected-source-text)))
              (is (= source-fingerprint
                     (rlm-fingerprint/fingerprint projected-source)))
              (is (not (str/includes? (pr-str reopened-events) "<inline-fn>")))
              (is (not-any? fn? (tree-seq coll? seq reopened-events)))
              (with-redefs [llm/predict
                            (fn [& _]
                              (swap! resume-calls inc)
                              {:outputs
                               {:code
                                "(final! {:doubled (get-var :doubled)})"}})]
                (let [resumed (executor/execute-repl-researcher-rlm
                               node {} :openrouter
                               {:tick-id tick-id
                                :node-id node-id
                                :researcher-resume-state
                                (:resume-state projected-state)
                                :researcher-iteration-records
                                projected-records})]
                  (is (= :success (:status resumed)) (pr-str resumed))
                  (is (= 22 (get-in resumed [:outputs :doubled]))
                      (pr-str resumed))
                  (is (= 1 @resume-calls)
                      "the completed source-producing turn is not replayed")))

              (reset! @#'tree-executor/ephemeral-fn-registry {})
              (h/with-async-test-context [fresh-ctx
                                          {:context {:llm-provider :test}}]
                (let [definition
                      (sheet/workflow "rr6-reemit-sqlite-source"
                        (sheet/blackboard {:n :int :doubled :int})
                        (sheet/repl-researcher "researcher"
                          :instruction "emit the source reopened from SQLite"
                          :reads [:n]
                          :writes [:doubled]
                          :max-iterations 2
                          :rlm {:checkpointed? true
                                :timeouts {:provider-ms 1000
                                           :iteration-ms 5000
                                           :campaign-ms 20000}}))
                      fresh-sheet-id (sheet/build-workflow! fresh-ctx definition)]
                  (with-redefs [llm/predict
                                (fn [& _]
                                  (case (swap! reemit-calls inc)
                                    1 {:outputs
                                       {:code
                                        (str "(emit-tree! (quote "
                                             projected-source-text
                                             "))")}}
                                    2 {:outputs
                                       {:code
                                        "(final! {:doubled (get-var :doubled)})"}}
                                    (throw
                                     (ex-info
                                      "RR6 SQLite re-emission exceeded two turns"
                                      {}))))]
                    (let [reemit-result
                          (sheet/execute fresh-ctx fresh-sheet-id {:n 11}
                                         :timeout-ms 20000)]
                      (is (= :success (:status reemit-result))
                          (pr-str reemit-result))
                      (is (= 22 (get-in reemit-result [:outputs :doubled]))
                          (pr-str reemit-result))
                      (is (= 2 @reemit-calls))
                      (is (empty? @@#'tree-executor/ephemeral-fn-registry)))))))
            (finally
              (es/stop reopened))))
        (finally
          (h/stop-context support)
          (doseq [suffix ["" "-wal" "-shm"]]
            (io/delete-file (str db-file suffix) true)))))))

(deftest public-execution-reticks-and-traces-checkpointed-iterations
  (testing "the public workflow boundary yields internally, resumes, and exposes durable iteration trace data"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            definition
            (sheet/workflow "checkpointed-public-e2e"
              (sheet/blackboard {:input :string :summary :string})
              (sheet/sequence "campaign"
                (sheet/code "predecessor"
                  :fn "ai.obney.orc.orc-service.checkpointed-researcher-test/checkpoint-predecessor"
                  :writes [:input])
                (sheet/repl-researcher "researcher"
                  :instruction "work in two iterations"
                  :reads [:input]
                  :writes [:summary]
                  :max-iterations 4
                  :rlm {:checkpointed? true
                        :quantum {:max-iterations 1}
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 10000}})))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (case (swap! calls inc)
                          1 {:outputs {:code "(store! :memo \"durable\")"}
                             :reasoning "save intermediate state"
                             :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}}
                          2 {:outputs {:code "(final! {:summary (get-var :memo)})"}
                             :reasoning "finish from checkpoint"
                             :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}}))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                trace-id (:trace-id result)]
            (is (= :success (:status result)) (pr-str result))
            (is (= "durable" (get-in result [:outputs :summary])))
            (is (= 2 @calls))
            (is (h/settle-until! #(some? (rm/get-trace ctx trace-id))))
            (let [trace (rm/get-trace ctx trace-id)
                  predecessor-id (:id (first (filter #(= "predecessor" (:name %))
                                                     (sheet/get-nodes-for-sheet ctx sheet-id))))
                  tick-events (into [] (es/read (:event-store ctx)
                                                {:tenant-id (:tenant-id ctx)
                                                 :tags #{[:tick trace-id]}}))
                  record-events (filter #(= :rlm/researcher-iteration-recorded
                                              (:event/type %))
                                        tick-events)
                  state-events (filter #(= :rlm/researcher-resume-state-saved
                                             (:event/type %))
                                       tick-events)
                  action-events (filter #(= :rlm/researcher-action-completed (:event/type %))
                                        tick-events)]
              (is (= 2 (count record-events))
                  "the yielded and terminal iteration records are independently durable")
              (is (= 2 (count state-events))
                  "each completed iteration advances the durable resume state")
              (is (= 2 (count action-events)))
              (is (= 1 (count (filter #(and (= :sheet/node-execution-completed
                                                (:event/type %))
                                             (= predecessor-id (:node-id %)))
                                       tick-events)))
                  "the completed predecessor is not rerun when the researcher resumes")
              (is (= [1 2] (mapv :iteration (:researcher-iterations trace))))
              (is (= [:action-completed :checkpoint :yield :resume
                      :action-completed :checkpoint]
                     (mapv :type (:researcher-events trace)))))))))))

(deftest public-two-iteration-campaign-persists-v2-facts-and-reconstructs-history
  (testing "public resume reads immutable records while continuation state stays history-free"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            prompt-histories (atom [])
            definition
            (sheet/workflow "v2-iteration-facts-public-e2e"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "persist one turn, then finish from its record"
                :writes [:summary]
                :max-iterations 4
                :rlm {:checkpointed? true
                      :quantum {:max-iterations 1}
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 2000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [_provider _module inputs _options]
                        (swap! prompt-histories conj (:history inputs))
                        (case (swap! calls inc)
                          1 {:outputs {:code "(store! :memo \"cycle-one-durable\")"}
                             :reasoning "persist the first turn"
                             :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}}
                          2 {:outputs {:code "(final! {:summary (get-var :memo)})"}
                             :reasoning "finish from the immutable record"
                             :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}}))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                trace-id (:trace-id result)
                researcher-id (:id (first (filter #(= "researcher" (:name %))
                                                   (sheet/get-nodes-for-sheet ctx sheet-id))))
                tick-events (into [] (es/read (:event-store ctx)
                                              {:tenant-id (:tenant-id ctx)
                                               :tags #{[:tick trace-id]}}))
                record-events (filter #(= :rlm/researcher-iteration-recorded
                                           (:event/type %))
                                      tick-events)
                state-events (filter #(= :rlm/researcher-resume-state-saved
                                          (:event/type %))
                                     tick-events)
                legacy-checkpoint-events
                (filter #(= :rlm/researcher-checkpointed (:event/type %)) tick-events)
                records (rm/get-researcher-iteration-records
                         ctx sheet-id trace-id researcher-id)]
            (is (= :success (:status result)) (pr-str result))
            (is (= "cycle-one-durable" (get-in result [:outputs :summary])))
            (is (= 2 @calls))
            (is (str/includes? (second @prompt-histories)
                               "cycle-one-durable")
                "the resumed prompt is reconstructed with the first durable record")
            (is (= 2 (count record-events)))
            (is (= [0 1] (mapv :iteration-index records)))
            (is (= 2 (count state-events)))
            (is (empty? legacy-checkpoint-events)
                "new public executions do not retain the quadratic v1 blob")
            (is (every? #(and (= 2 (get-in % [:resume-state :version]))
                              (not (contains? (:resume-state %) :history))
                              (not (contains? (:resume-state %) :terminal-result)))
                        state-events))))))))

(deftest timed-out-and-successful-attempts-share-an-iteration-without-collapsing
  (testing "attempt identity is distinct from the logical iteration frontier"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            definition
            (sheet/workflow "v2-attempt-records-public-e2e"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "retry one timed-out turn"
                :writes [:summary]
                :max-iterations 2
                :rlm {:checkpointed? true
                      :iteration-retry {:max-attempts 2}
                      :timeouts {:provider-ms 15
                                 :iteration-ms 500
                                 :campaign-ms 5000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (if (= 1 (swap! calls inc))
                          (do (Thread/sleep 100)
                              {:outputs {:code "(final! {:summary \"late\"})"}})
                          {:outputs {:code "(final! {:summary \"recovered\"})"}}))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                trace-id (:trace-id result)
                researcher-id (:id (first (filter #(= "researcher" (:name %))
                                                   (sheet/get-nodes-for-sheet ctx sheet-id))))
                records (rm/get-researcher-iteration-records
                         ctx sheet-id trace-id researcher-id)
                resume-state (:resume-state
                              (rm/get-researcher-resume-state
                               ctx sheet-id trace-id researcher-id))]
            (is (= :success (:status result)) (pr-str result))
            (is (= "recovered" (get-in result [:outputs :summary])))
            (is (= 2 @calls))
            (is (= [[0 0 :timeout] [0 1 :success]]
                   (mapv (juxt :iteration-index :attempt-ordinal :status) records)))
            (doseq [record records]
              (let [duplicate (h/run-and-apply!
                               ctx
                               (assoc (iteration-commit-command
                                       sheet-id trace-id researcher-id
                                       resume-state record)
                                      :resume? false))]
                (is (empty? (:command-result/events duplicate)))))
            (is (= 2 (count (rm/get-researcher-iteration-records
                             ctx sheet-id trace-id researcher-id))))))))))

(deftest later-unsupported-value-preserves-the-last-durable-frontier
  (testing "durability errors identify structure without rendering runtime values"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            definition
            (sheet/workflow "v2-unsupported-value-public-e2e"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "persist one turn, then encounter local runtime state"
                :writes [:summary]
                :max-iterations 3
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 500
                                 :iteration-ms 1000
                                 :campaign-ms 5000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (case (swap! calls inc)
                          1 {:outputs {:code "(store! :memo \"durable-first\")"}}
                          2 {:outputs {:code "(store! :opaque (fn [] \"DO-NOT-STRINGIFY\"))"}}
                          (throw (ex-info "durability failure advanced the provider frontier" {}))))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                trace-id (:trace-id result)
                researcher-id (:id (first (filter #(= "researcher" (:name %))
                                                   (sheet/get-nodes-for-sheet ctx sheet-id))))
                records (rm/get-researcher-iteration-records
                         ctx sheet-id trace-id researcher-id)
                resume-state (:resume-state
                              (rm/get-researcher-resume-state
                               ctx sheet-id trace-id researcher-id))]
            (is (= :failure (:status result)) (pr-str result))
            (is (str/includes? (:error result) "[:sandbox-vars :opaque]")
                (:error result))
            (is (str/includes? (:error result) "function") (:error result))
            (is (not (str/includes? (:error result) "DO-NOT-STRINGIFY"))
                "the error must not call pr-str or toString on the unsupported value")
            (is (= 2 @calls))
            (is (= [[0 0 :success]]
                   (mapv (juxt :iteration-index :attempt-ordinal :status) records)))
            (is (= 1 (:next-iteration resume-state)))
            (is (= "durable-first" (get-in resume-state [:sandbox-vars :memo])))
            (is (not (contains? (:sandbox-vars resume-state) :opaque)))))))))

(deftest generated-code-failure-is-an-immutable-iteration-outcome
  (testing "a failed sandbox attempt is durable even when the campaign then exhausts"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [definition
            (sheet/workflow "v2-failed-iteration-public-e2e"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "record a failed generated-code attempt"
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 500
                                 :iteration-ms 1000
                                 :campaign-ms 5000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        {:outputs {:code "(missing-research-function)"}
                         :reasoning "attempt unavailable operation"})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                trace-id (:trace-id result)
                researcher-id (:id (first (filter #(= "researcher" (:name %))
                                                   (sheet/get-nodes-for-sheet ctx sheet-id))))
                records (rm/get-researcher-iteration-records
                         ctx sheet-id trace-id researcher-id)
                resume-state (:resume-state
                              (rm/get-researcher-resume-state
                               ctx sheet-id trace-id researcher-id))]
            (is (= :failure (:status result)) (pr-str result))
            (is (= [[0 0 :failure]]
                   (mapv (juxt :iteration-index :attempt-ordinal :status) records)))
            (is (= "(missing-research-function)" (:code (first records))))
            (is (and (string? (:error-class (first records)))
                     (str/includes? (:error-excerpt (first records))
                                    "missing-research-function")))
            (is (not (contains? (first records) :error)))
            (is (= 1 (:next-iteration resume-state)))))))))

(deftest version-two-trace-history-comes-verbatim-from-iteration-records
  (testing "the durable trace does not reconstruct a poorer terminal-history copy"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [definition
            (sheet/workflow "v2-trace-record-source-e2e"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "finish with rich iteration evidence"
                :writes [:summary]
                :max-iterations 2
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 500
                                 :iteration-ms 1000
                                 :campaign-ms 5000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        {:outputs {:code "(final! {:summary \"record-source\"})"}
                         :reasoning "rich durable reasoning"
                         :usage {:prompt_tokens 7 :completion_tokens 3 :total_tokens 10}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                trace-id (:trace-id result)
                researcher-id (:id (first (filter #(= "researcher" (:name %))
                                                   (sheet/get-nodes-for-sheet ctx sheet-id))))]
            (is (= :success (:status result)) (pr-str result))
            (is (h/settle-until! #(h/trace-stored? ctx trace-id)))
            (let [records (rm/get-researcher-iteration-records
                           ctx sheet-id trace-id researcher-id)
                  traced (:researcher-iterations (rm/get-trace ctx trace-id))]
              (is (= (mapv #(assoc % :iteration (inc (:iteration-index %))) records)
                     traced)
                  (str "trace must retain every immutable record field; records="
                       (pr-str records) " traced=" (pr-str traced))))))))))

(deftest successful-public-iteration-projects-an-attempt-scoped-evidence-digest
  (testing "one production-created record keeps authored evidence and payload shape only"
    (let [attempt-start-ms (System/currentTimeMillis)
          attempt-completed-ms (+ attempt-start-ms 37)
          clock-values (atom [attempt-start-ms attempt-completed-ms])
          researcher-now-ms-fn
          (fn []
            (if-let [value (first @clock-values)]
              (do
                (swap! clock-values #(vec (next %)))
                value)
              (throw (ex-info "RR5 lifecycle clock was invoked more than twice" {}))))
          sandbox-payload "SANDBOX-PAYLOAD-RR5-CYCLE-1"
          stdout-payload "STDOUT-PAYLOAD-RR5-CYCLE-1"
          result-payload "RESULT-PAYLOAD-RR5-CYCLE-1"
          reasoning "Use the three supplied values without copying payloads into durable evidence."
          code (str "(do (store! :created-payload (get-input :sandbox-source)) "
                    "(println (get-input :stdout-source)) "
                    "(final! {:summary (get-input :result-source)}))")
          raw-stdout (str stdout-payload "\n")
          string-profile
          (fn [value]
            {:type :string
             :length (count value)
             :word-count (count (str/split value #"\s+"))
             :line-count (count (str/split-lines value))})]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :researcher-now-ms-fn researcher-now-ms-fn}}]
        (let [definition
              (sheet/workflow "rr5-successful-iteration-evidence-digest"
                (sheet/blackboard {:sandbox-source :string
                                   :stdout-source :string
                                   :result-source :string
                                   :summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "complete one successful, durable iteration"
                  :reads [:sandbox-source :stdout-source :result-source]
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:checkpointed? true
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 10000}}))
              sheet-id (sheet/build-workflow! ctx definition)]
          (with-redefs [llm/predict
                        (fn [& _]
                          {:outputs {:code code}
                           :reasoning reasoning
                           :usage {:prompt_tokens 5
                                   :completion_tokens 3
                                   :total_tokens 8}})]
            (let [result (sheet/execute ctx sheet-id
                                        {:sandbox-source sandbox-payload
                                         :stdout-source stdout-payload
                                         :result-source result-payload}
                                        :timeout-ms 10000)
                  trace-id (:trace-id result)
                  researcher-id
                  (:id (first (filter #(= "researcher" (:name %))
                                      (sheet/get-nodes-for-sheet ctx sheet-id))))
                  records (rm/get-researcher-iteration-records
                           ctx sheet-id trace-id researcher-id)
                  record (first records)
                  record-text (pr-str record)]
              (is (= :success (:status result)) (pr-str result))
              (is (= result-payload (get-in result [:outputs :summary])))
              (is (= 1 (count records))
                  "the assertion reads the production-created public record projection")
              (is (empty? @clock-values)
                  "one attempt consumes exactly its injected start and completion instants")
              (is (= {:iteration-index 0
                      :attempt-ordinal 0
                      :status :success
                      :started-at (str (java.time.Instant/ofEpochMilli
                                        attempt-start-ms))
                      :completed-at (str (java.time.Instant/ofEpochMilli
                                          attempt-completed-ms))
                      :duration-ms 37
                      :code code
                      :reasoning reasoning
                      :generated-code-recorded? true
                      :result-profile {:type :map :length 1}
                      :stdout-profile (string-profile raw-stdout)}
                     (select-keys
                      record
                      [:iteration-index :attempt-ordinal :status
                       :started-at :completed-at :duration-ms
                       :code :reasoning :generated-code-recorded?
                       :result-profile :stdout-profile])))
              (is (= #{:sandbox-source :stdout-source
                       :result-source :created-payload}
                     (set (get-in record [:variable-delta :created-keys]))))
              (is (= [] (get-in record [:variable-delta :updated-keys])))
              (is (= [] (get-in record [:variable-delta :removed-keys])))
              (is (every? vector?
                          (vals (:variable-delta record)))
                  "created, updated, and removed deltas are durable key lists")
              (is (not-any? #(contains? record %)
                            [:result :stdout :sandbox-vars :inputs :outputs])
                  (str "raw payload-bearing fields leaked into the record: " record-text))
              (doseq [payload [sandbox-payload stdout-payload result-payload]]
                (is (not (str/includes? record-text payload))
                    (str "raw payload leaked into the durable record: " payload))))))))))

(deftest backward-attempt-clock-normalizes-the-complete-lifecycle-pair
  (testing "a clock rollback cannot persist completion before attempt start"
    (let [attempt-start-ms 200
          clock-values (atom [attempt-start-ms 100])
          researcher-now-ms-fn
          (fn []
            (if-let [value (first @clock-values)]
              (do
                (swap! clock-values #(vec (next %)))
                value)
              (throw (ex-info "RR5 rollback clock was invoked more than twice" {}))))]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :researcher-now-ms-fn researcher-now-ms-fn}}]
        (let [definition
              (sheet/workflow "rr5-backward-attempt-clock"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "complete one successful durable iteration"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:checkpointed? true
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 10000}}))
              sheet-id (sheet/build-workflow! ctx definition)]
          (with-redefs [llm/predict
                        (fn [& _]
                          {:outputs {:code "(final! {:summary \"done\"})"}
                           :reasoning "complete despite a wall-clock rollback"
                           :usage {:prompt_tokens 3
                                   :completion_tokens 2
                                   :total_tokens 5}})]
            (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                  trace-id (:trace-id result)
                  researcher-id
                  (:id (first (filter #(= "researcher" (:name %))
                                      (sheet/get-nodes-for-sheet ctx sheet-id))))
                  record (first (rm/get-researcher-iteration-records
                                 ctx sheet-id trace-id researcher-id))
                  normalized-time (str (java.time.Instant/ofEpochMilli
                                        attempt-start-ms))]
              (is (= :success (:status result)) (pr-str result))
              (is (empty? @clock-values)
                  "one attempt consumes exactly two lifecycle clock readings")
              (is (= {:started-at normalized-time
                      :completed-at normalized-time
                      :duration-ms 0}
                     (select-keys record
                                  [:started-at :completed-at :duration-ms]))
                  (pr-str record)))))))))

(deftest emitted-tree-record-persists-the-event-safe-tree-and-canonical-fingerprint
  (testing "a production-created tree fact carries its durable shape in the raw event and projection"
    (let [phase1-calls (atom 0)
          emitted-tree [:sequence [:final {:keys [:summary]}]]
          emitted-tree-code "(emit-tree! [:sequence [:final {:keys [:summary]}]])"
          expected-fingerprint (rlm-fingerprint/fingerprint emitted-tree)]
      (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
        (let [definition
              (sheet/workflow "rr5-emitted-tree-evidence"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "emit one tree, inspect its outcome, then finish"
                  :reads [:summary]
                  :writes [:summary]
                  :max-iterations 2
                  :rlm {:checkpointed? true
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 3000
                                   :campaign-ms 15000}}))
              sheet-id (sheet/build-workflow! ctx definition)]
          (with-redefs [llm/predict
                        (fn [& _]
                          (case (swap! phase1-calls inc)
                            1 {:outputs {:code emitted-tree-code}
                               :reasoning "delegate the current summary through a final tree"
                               :usage {:prompt_tokens 4
                                       :completion_tokens 3
                                       :total_tokens 7}}
                            2 {:outputs {:code "(final! {:summary \"done\"})"}
                               :reasoning "the emitted tree completed"
                               :usage {:prompt_tokens 5
                                       :completion_tokens 2
                                       :total_tokens 7}}
                            (throw (ex-info "RR5 emitted-tree fixture exceeded two turns" {}))))]
            (let [result (sheet/execute ctx sheet-id {:summary "seed"}
                                        :timeout-ms 15000)
                  trace-id (:trace-id result)
                  researcher-id
                  (:id (first (filter #(= "researcher" (:name %))
                                      (sheet/get-nodes-for-sheet ctx sheet-id))))
                  records (rm/get-researcher-iteration-records
                           ctx sheet-id trace-id researcher-id)
                  raw-records
                  (->> (h/read-tick-events ctx trace-id)
                       (filter #(= :rlm/researcher-iteration-recorded
                                   (:event/type %)))
                       (mapv :iteration-record))
                  emitted-record (first records)
                  raw-emitted-record (first raw-records)]
              (is (= :success (:status result)) (pr-str result))
              (is (= 2 @phase1-calls))
              (is (= [[0 true] [1 false]]
                     (mapv (juxt :iteration-index :emitted-tree-recorded?)
                           records))
                  (pr-str records))
              (is (= {:emitted-tree emitted-tree
                      :tree-fingerprint expected-fingerprint
                      :emitted-tree-recorded? true}
                     (select-keys emitted-record
                                  [:emitted-tree :tree-fingerprint
                                   :emitted-tree-recorded?]))
                  (pr-str emitted-record))
              (is (= (select-keys emitted-record
                                  [:emitted-tree :tree-fingerprint
                                   :emitted-tree-recorded?])
                     (select-keys raw-emitted-record
                                  [:emitted-tree :tree-fingerprint
                                   :emitted-tree-recorded?]))
                  (str "raw event and projection disagree: event="
                       (pr-str raw-emitted-record)
                       " projection=" (pr-str emitted-record)))
              (is (every? (fn [{:keys [emitted-tree-recorded? emitted-tree
                                        tree-fingerprint]}]
                            (or (not emitted-tree-recorded?)
                                (and (some? emitted-tree)
                                     (some? tree-fingerprint))))
                          (concat raw-records records))
                  "the tree-present flag implies both durable tree and shape")
              (is (not-any? #(contains? emitted-record %)
                            [:provider-latency-ms :provider-usage :timeout-kind
                             :child-trace-id :child-outcome :tree-outcome])
                  (str "legacy execution metadata leaked into the evidence digest: "
                       (pr-str emitted-record)))
              (is (not (str/includes? (pr-str emitted-record) "seed"))
                  (str "a generated-tree outcome leaked a raw blackboard value: "
                       (pr-str emitted-record)))
              (is (not (m/validate
                        ai.obney.orc.orc-service.interface.schemas/researcher-iteration-record
                        {:iteration-index 0
                         :attempt-ordinal 0
                         :status :success
                         :emitted-tree-recorded? true}))
                  "the durable command schema rejects a true flag without tree and shape"))))))))

(deftest quoted-inline-code-tree-is-durable-source-and-executes
  (testing "a checkpointed campaign records authored inline code before compiling it"
    (let [phase1-calls (atom 0)
          reemit-calls (atom 0)
          source-tree
          '[:sequence
            [:code {:reads [:n]
                    :writes [:doubled]
                    :fn (fn [{:keys [inputs]}]
                          {:doubled (* 2 (:n inputs))})}]
            [:final {:keys [:doubled]}]]
          emitted-tree-code
          (str "(emit-tree! (quote " (pr-str source-tree) "))")]
      (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
        (let [definition
              (sheet/workflow "rr6-quoted-inline-source"
                (sheet/blackboard {:n :int :doubled :int})
                (sheet/repl-researcher "researcher"
                  :instruction "emit one quoted code tree, inspect it, then finish"
                  :reads [:n]
                  :writes [:doubled]
                  :max-iterations 2
                  :rlm {:checkpointed? true
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 5000
                                   :campaign-ms 20000}}))
              sheet-id (sheet/build-workflow! ctx definition)]
          (with-redefs [llm/predict
                        (fn [& _]
                          (case (swap! phase1-calls inc)
                            1 {:outputs {:code emitted-tree-code}
                               :reasoning "preserve the authored transform as source"
                               :usage {:prompt_tokens 4
                                       :completion_tokens 3
                                       :total_tokens 7}}
                            2 {:outputs {:code "(final! {:doubled (get-var :doubled)})"}
                               :reasoning "the durable source tree executed"
                               :usage {:prompt_tokens 3
                                       :completion_tokens 2
                                       :total_tokens 5}}
                            (throw (ex-info "RR6 quoted-source fixture exceeded two turns" {}))))]
            (let [result (sheet/execute ctx sheet-id {:n 21} :timeout-ms 20000)
                  trace-id (:trace-id result)
                  researcher-id
                  (:id (first (filter #(= "researcher" (:name %))
                                      (sheet/get-nodes-for-sheet ctx sheet-id))))
                  projected-record
                  (first (rm/get-researcher-iteration-records
                          ctx sheet-id trace-id researcher-id))
                  raw-record
                  (->> (h/read-tick-events ctx trace-id)
                       (filter #(= :rlm/researcher-iteration-recorded
                                   (:event/type %)))
                       first
                       :iteration-record)
                  _ (is (h/settle-until!
                         (fn []
                           (let [events (into [] (es/read
                                                 (:event-store ctx)
                                                 {:tenant-id (:tenant-id ctx)}))]
                             (and (some #(and (= :rlm/tree-generated
                                                  (:event/type %))
                                               (= trace-id (:execution-id %)))
                                        events)
                                  (some #(and (= :sheet/rlm-tree-execution-completed
                                                  (:event/type %))
                                               (= trace-id (:source-tick-id %)))
                                        events))))
                         :timeout-ms 10000)
                        "tree source reaches both durable observability events")
                  all-events (into [] (es/read (:event-store ctx)
                                               {:tenant-id (:tenant-id ctx)}))
                  tree-generated-event
                  (first (filter #(and (= :rlm/tree-generated (:event/type %))
                                       (= trace-id (:execution-id %)))
                                 all-events))
                  completion-bookend
                  (first (filter
                          #(and (= :sheet/rlm-tree-execution-completed
                                    (:event/type %))
                                (= trace-id (:source-tick-id %)))
                          all-events))
                  projected-source (:emitted-tree projected-record)
                  projected-source-text (:emitted-tree-source projected-record)
                  serialized-source (pr-str projected-source)]
              (is (= :success (:status result)) (pr-str result))
              (is (= 42 (get-in result [:outputs :doubled])) (pr-str result))
              (is (= 2 @phase1-calls))
              (is (= source-tree projected-source) (pr-str projected-record))
              (is (= source-tree (:emitted-tree raw-record)) (pr-str raw-record))
              (is (= (pr-str source-tree) projected-source-text))
              (is (= projected-source-text
                     (:emitted-tree-source raw-record)))
              (is (= projected-source-text (:source-edn tree-generated-event)))
              (is (= projected-source-text
                     (:generated-tree-source completion-bookend)))
              (is (= source-tree (read-string serialized-source)))
              (is (= (rlm-fingerprint/fingerprint source-tree)
                     (rlm-fingerprint/fingerprint
                      (read-string serialized-source)))
                  "durable serialization preserves the tree's shape identity")
              (is (not (str/includes? serialized-source "<inline-fn>")))
              (is (not-any? fn? (tree-seq coll? seq projected-source))
                  "the durable tree contains source forms, never live closures")

              ;; A prior execution's registry must not be able to make this
              ;; pass. Re-emission recompiles the projected source in the
              ;; second researcher's fresh SCI context.
              (reset! @#'tree-executor/ephemeral-fn-registry {})
              (let [reemit-definition
                    (sheet/workflow "rr6-reemit-projected-source"
                      (sheet/blackboard {:n :int :doubled :int})
                      (sheet/repl-researcher "researcher"
                        :instruction "re-emit recorded source, then finish"
                        :reads [:n]
                        :writes [:doubled]
                        :max-iterations 2
                        :rlm {:checkpointed? true
                              :timeouts {:provider-ms 1000
                                         :iteration-ms 5000
                                         :campaign-ms 20000}}))
                    reemit-sheet-id (sheet/build-workflow! ctx reemit-definition)]
                (with-redefs [llm/predict
                              (fn [& _]
                                (case (swap! reemit-calls inc)
                                  1 {:outputs
                                     {:code
                                      (str "(emit-tree! (quote "
                                           projected-source-text
                                           "))")}}
                                  2 {:outputs
                                     {:code
                                      "(final! {:doubled (get-var :doubled)})"}}
                                  (throw
                                   (ex-info
                                    "RR6 re-emission fixture exceeded two turns"
                                    {}))))]
                  (let [reemit-result
                        (sheet/execute ctx reemit-sheet-id {:n 9}
                                       :timeout-ms 20000)]
                    (is (= :success (:status reemit-result))
                        (pr-str reemit-result))
                    (is (= 18 (get-in reemit-result [:outputs :doubled]))
                        (pr-str reemit-result))
                    (is (= 2 @reemit-calls))
                    (is (empty? @@#'tree-executor/ephemeral-fn-registry)
                        "the second execution cleans up its fresh compiled fn")))))))))))

(deftest non-checkpointed-inline-closure-tree-still-executes-without-durable-source
  (testing "legacy non-checkpointed trees execute without appending a live closure"
    (let [calls (atom 0)]
      (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
        (let [definition
              (sheet/workflow "rr6-non-checkpointed-inline-closure"
                (sheet/blackboard {:n :int :doubled :int})
                (sheet/repl-researcher "researcher"
                  :instruction "run the legacy inline closure tree, then finish"
                  :reads [:n]
                  :writes [:doubled]
                  :max-iterations 2
                  :rlm {:checkpointed? false
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 5000
                                   :campaign-ms 20000}}))
              sheet-id (sheet/build-workflow! ctx definition)]
          (with-redefs [llm/predict
                        (fn [& _]
                          (case (swap! calls inc)
                            1 {:outputs
                               {:code
                                (str "(emit-tree! [:sequence "
                                     "[:code {:reads [:n] :writes [:doubled] "
                                     ":fn (fn [{:keys [inputs]}] "
                                     "{:doubled (* 2 (:n inputs))})}] "
                                     "[:final {:keys [:doubled]}]])")}}
                            2 {:outputs {:code "(final! {:doubled (get-var :doubled)})"}}
                            (throw (ex-info "legacy fixture exceeded two turns" {}))))]
            (let [result (sheet/execute ctx sheet-id {:n 13} :timeout-ms 20000)
                  events (h/read-tick-events ctx (:trace-id result))]
              (is (= :success (:status result)) (pr-str result))
              (is (= 26 (get-in result [:outputs :doubled])) (pr-str result))
              (is (= 2 @calls))
              (is (not (str/includes? (pr-str events) "<inline-fn>")))
              (is (not-any? fn? (tree-seq coll? seq events))
                  "no live closure reaches an event"))))))))

(deftest checkpointed-inline-closure-is-rejected-before-tree-append
  (testing "durable execution names the quoted-source requirement instead of persisting a closure"
    (let [calls (atom 0)]
      (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
        (let [definition
              (sheet/workflow "rr6-checkpointed-live-closure-rejected"
                (sheet/blackboard {:n :int :doubled :int})
                (sheet/repl-researcher "researcher"
                  :instruction "attempt an unquoted inline closure"
                  :reads [:n]
                  :writes [:doubled]
                  :max-iterations 1
                  :rlm {:checkpointed? true
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 5000
                                   :campaign-ms 20000}}))
              sheet-id (sheet/build-workflow! ctx definition)]
          (with-redefs [llm/predict
                        (fn [& _]
                          (swap! calls inc)
                          {:outputs
                           {:code
                            (str "(emit-tree! [:sequence "
                                 "[:code {:reads [:n] :writes [:doubled] "
                                 ":fn (fn [{:keys [inputs]}] "
                                 "{:doubled (* 2 (:n inputs))})}] "
                                 "[:final {:keys [:doubled]}]])")}})]
            (let [result (sheet/execute ctx sheet-id {:n 7} :timeout-ms 20000)
                  trace-id (:trace-id result)
                  researcher-id
                  (:id (first (filter #(= "researcher" (:name %))
                                      (sheet/get-nodes-for-sheet ctx sheet-id))))
                  records (rm/get-researcher-iteration-records
                           ctx sheet-id trace-id researcher-id)
                  events (h/read-tick-events ctx trace-id)
                  error-excerpt (:error-excerpt (first records))]
              (is (= :failure (:status result)) (pr-str result))
              (is (= 1 @calls))
              (is (= 1 (count records)) (pr-str records))
              (is (= :failure (:status (first records))) (pr-str records))
              (is (str/includes? error-excerpt "Durable emit-tree! code nodes"))
              (is (str/includes? error-excerpt "quoted (fn ...) source"))
              (is (not-any? #(= :rlm/tree-generated (:event/type %)) events)
                  "the rejected tree never reaches the durable tree event")
              (is (not (str/includes? (pr-str events) "<inline-fn>")))
              (is (not-any? fn? (tree-seq coll? seq events))
                  "the rejected closure never reaches an event"))))))))

(deftest failed-attempt-records-its-own-class-excerpt-and-lifecycle
  (testing "sandbox failure evidence belongs to the attempt that generated the failing code"
    (let [clock-values (atom [1000 1011])
          error-sentinel "RR5-SANDBOX-FAILURE"
          raw-error (str error-sentinel (apply str (repeat 300 "e")))
          code (str "(throw (ex-info \"" raw-error "\" {}))")
          reasoning (str "exercise the failing sandbox boundary: "
                         (apply str (repeat 500 "r")))]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :researcher-now-ms-fn
                        (fn []
                          (if-let [value (first @clock-values)]
                            (do
                              (swap! clock-values #(vec (next %)))
                              value)
                            (throw (ex-info "RR5 failure clock exceeded two reads" {}))))}}]
        (let [definition
              (sheet/workflow "rr5-failed-attempt-evidence"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "record one failing durable attempt"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:checkpointed? true
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 10000}}))
              sheet-id (sheet/build-workflow! ctx definition)]
          (with-redefs [llm/predict
                        (fn [& _]
                          {:outputs {:code code}
                           :reasoning reasoning
                           :usage {:prompt_tokens 4
                                   :completion_tokens 2
                                   :total_tokens 6}})]
            (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                  trace-id (:trace-id result)
                  researcher-id
                  (:id (first (filter #(= "researcher" (:name %))
                                      (sheet/get-nodes-for-sheet ctx sheet-id))))
                  record (first (rm/get-researcher-iteration-records
                                 ctx sheet-id trace-id researcher-id))]
              (is (= :failure (:status result)) (pr-str result))
              (is (empty? @clock-values))
              (is (= {:iteration-index 0
                      :attempt-ordinal 0
                      :status :failure
                      :started-at "1970-01-01T00:00:01Z"
                      :completed-at "1970-01-01T00:00:01.011Z"
                      :duration-ms 11
                      :code code
                      :reasoning (subs reasoning 0
                                       executor/iteration-reasoning-max-chars)}
                     (select-keys record
                                  [:iteration-index :attempt-ordinal :status
                                   :started-at :completed-at :duration-ms
                                   :code :reasoning]))
                  (pr-str record))
              (is (and (string? (:error-class record))
                       (not (str/blank? (:error-class record))))
                  (pr-str record))
              (is (and (string? (:error-excerpt record))
                       (= executor/iteration-error-excerpt-max-chars
                          (count (:error-excerpt record)))
                       (= (:error-excerpt record)
                          (subs raw-error 0
                                executor/iteration-error-excerpt-max-chars)))
                  (pr-str record))
              (is (not (contains? record :error))
                  "the legacy unclassified error field is not part of the v2 digest"))))))))

(deftest provider-failure-before-code-still-records-the-attempt
  (let [clock-values (atom [1000 1017])
        error-sentinel "RR5-PROVIDER-ERROR"]
    (h/with-async-test-context
      [ctx {:context {:llm-provider :test
                      :researcher-now-ms-fn
                      (fn []
                        (if-let [value (first @clock-values)]
                          (do (swap! clock-values #(vec (next %))) value)
                          (throw (ex-info "RR5 provider clock exceeded two reads" {}))))}}]
      (let [definition
            (sheet/workflow "rr5-provider-failure-evidence"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "record a provider failure before code exists"
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 2000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (throw (ex-info error-sentinel {})))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                trace-id (:trace-id result)
                researcher-id
                (:id (first (filter #(= "researcher" (:name %))
                                    (sheet/get-nodes-for-sheet ctx sheet-id))))
                records (rm/get-researcher-iteration-records
                         ctx sheet-id trace-id researcher-id)]
            (is (= :failure (:status result)) (pr-str result))
            (is (empty? @clock-values)
                "the failed attempt consumes both start and completion readings")
            (is (= 1 (count records))
                "a pre-code failure is still one settled campaign iteration")
            (is (= {:iteration-index 0
                    :attempt-ordinal 0
                    :status :failure
                    :started-at "1970-01-01T00:00:01Z"
                    :completed-at "1970-01-01T00:00:01.017Z"
                    :duration-ms 17
                    :generated-code-recorded? false
                    :emitted-tree-recorded? false
                    :error-class "provider-failure"
                    :error-excerpt error-sentinel
                    :variable-delta {:created-keys []
                                     :updated-keys []
                                     :removed-keys []}}
                   (select-keys (first records)
                                [:iteration-index :attempt-ordinal :status
                                 :started-at :completed-at :duration-ms
                                 :generated-code-recorded?
                                 :emitted-tree-recorded?
                                 :error-class :error-excerpt :variable-delta]))
                (pr-str records))
            (is (not-any? #(contains? (first records) %)
                          [:code :reasoning :emitted-tree :tree-fingerprint])
                "a provider failure must not invent authored evidence")))))))

(deftest provider-failure-without-a-message-still-records-an-error
  (let [clock-values (atom [2000 2001])]
    (h/with-async-test-context
      [ctx {:context {:llm-provider :test
                      :researcher-now-ms-fn
                      (fn []
                        (if-let [value (first @clock-values)]
                          (do (swap! clock-values #(vec (next %))) value)
                          (throw (ex-info "RR5 nil-message clock exceeded two reads" {}))))}}]
      (let [definition
            (sheet/workflow "rr5-nil-message-provider-failure"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "record a provider exception without a message"
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 2000
                                 :campaign-ms 10000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict (fn [& _] (throw (Exception.)))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                trace-id (:trace-id result)
                researcher-id
                (:id (first (filter #(= "researcher" (:name %))
                                    (sheet/get-nodes-for-sheet ctx sheet-id))))
                record (first (rm/get-researcher-iteration-records
                               ctx sheet-id trace-id researcher-id))]
            (is (= :failure (:status result)) (pr-str result))
            (is (empty? @clock-values))
            (is (= "provider-failure" (:error-class record)) (pr-str record))
            (is (= "java.lang.Exception" (:error-excerpt record))
                "a nil Throwable message falls back to a stable printable error")))))))

(deftest nil-message-provider-failure-preserves-noncheckpointed-compatibility
  (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
    (let [definition
          (sheet/workflow "rr5-noncheckpointed-nil-message-compatibility"
            (sheet/blackboard {:summary :string})
            (sheet/repl-researcher "researcher"
              :instruction "preserve the legacy recursive no-code path"
              :writes [:summary]
              :max-iterations 1
              :rlm {:recursive? true
                    :checkpointed? false}))
          sheet-id (sheet/build-workflow! ctx definition)]
      (with-redefs [llm/predict (fn [& _] (throw (Exception.)))]
        (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)]
          (is (= :failure (:status result)) (pr-str result))
          (is (= "Max iterations reached without final!" (:error result))
              "RR5 must not turn the legacy no-code path into a new terminal provider error"))))))

(deftest raw-iteration-events-project-payload-shapes-without-payload-values
  (testing "large strings, nested maps, and collections become profiles in raw durable facts"
    (let [large-sentinel (str "RR5-LARGE-SENTINEL-" (apply str (repeat 4096 "x")))
          nested-sentinel "RR5-NESTED-SENTINEL"
          collection-sentinel "RR5-COLLECTION-SENTINEL"
          nested-payload {:outer {:secret nested-sentinel} :count 2}
          collection-payload ["first" collection-sentinel "last"]
          calls (atom 0)
          codes ["(do (store! :large-copy (get-input :large-source)) (get-input :large-source))"
                 "(do (store! :nested-copy (get-input :nested-source)) (get-input :nested-source))"
                 "(do (store! :items-copy (get-input :items-source)) (get-input :items-source))"]]
      (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
        (let [definition
              (sheet/workflow "rr5-raw-event-payload-profiles"
                (sheet/blackboard {:large-source :string
                                   :nested-source [:map
                                                   [:outer [:map [:secret :string]]]
                                                   [:count :int]]
                                   :items-source [:vector :string]
                                   :summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "profile three payload shapes without retaining their values"
                  :reads [:large-source :nested-source :items-source]
                  :writes [:summary]
                  :max-iterations 3
                  :rlm {:checkpointed? true
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 15000}}))
              sheet-id (sheet/build-workflow! ctx definition)]
          (with-redefs [llm/predict
                        (fn [& _]
                          (let [index (swap! calls inc)]
                            {:outputs {:code (nth codes (dec index))}
                             :reasoning (str "profile payload shape " index)
                             :usage {:prompt_tokens 3
                                     :completion_tokens 2
                                     :total_tokens 5}}))]
            (let [result (sheet/execute ctx sheet-id
                                        {:large-source large-sentinel
                                         :nested-source nested-payload
                                         :items-source collection-payload}
                                        :timeout-ms 15000)
                  trace-id (:trace-id result)
                  researcher-id
                  (:id (first (filter #(= "researcher" (:name %))
                                      (sheet/get-nodes-for-sheet ctx sheet-id))))
                  records (rm/get-researcher-iteration-records
                           ctx sheet-id trace-id researcher-id)
                  raw-events (->> (h/read-tick-events ctx trace-id)
                                  (filter #(= :rlm/researcher-iteration-recorded
                                              (:event/type %)))
                                  vec)
                  raw-event-text (pr-str raw-events)]
              (is (= :failure (:status result)) (pr-str result))
              (is (= 3 @calls))
              (is (= [{:type :string
                       :length (count large-sentinel)
                       :word-count 1
                       :line-count 1}
                      {:type :map :length 2}
                      {:type :vector :length 3}]
                     (mapv :result-profile records))
                  (pr-str records))
              (is (= 3 (count raw-events)))
              (is (= (mapv :result-profile records)
                     (mapv (comp :result-profile :iteration-record) raw-events)))
              (doseq [sentinel [large-sentinel nested-sentinel collection-sentinel]]
                (is (not (str/includes? raw-event-text sentinel))
                    (str "raw payload leaked into iteration event: " sentinel)))
              (is (every? #(not-any? (fn [field] (contains? % field))
                                     [:result :stdout :raw-result :inputs :outputs])
                          records)
                  (pr-str records)))))))))

(deftest measured-iteration-evidence-bounds-preserve-prefix-at-the-boundary
  (testing "the checked-in live maxima define below, at, and above-limit behavior"
    (is (= 438 executor/iteration-reasoning-max-chars))
    (is (= 175 executor/iteration-error-excerpt-max-chars))
    (doseq [limit [executor/iteration-reasoning-max-chars
                   executor/iteration-error-excerpt-max-chars]
            length [(dec limit) limit (inc limit)]]
      (let [text (apply str (repeat length "x"))
            bounded (executor/bound-iteration-evidence-text text limit)]
        (is (= (min length limit) (count bounded)))
        (is (= bounded (subs text 0 (min length limit))))))))

(deftest serialized-record-size-is-independent-of-profiled-payload-bytes
  (testing "growing one raw value changes only its recorded shape measurement"
    (let [clock-values (atom [1000 1010 1000 1010])
          now-ms (fn []
                   (if-let [value (first @clock-values)]
                     (do (swap! clock-values #(vec (next %))) value)
                     (throw (ex-info "RR5 size clock exceeded four reads" {}))))]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :researcher-now-ms-fn now-ms}}]
        (with-redefs [llm/predict
                      (fn [& _]
                        {:outputs {:code "(get-input :payload)"}
                         :reasoning "measure only the payload shape"
                         :usage {:prompt_tokens 3
                                 :completion_tokens 2
                                 :total_tokens 5}})]
          (let [measure
                (fn [workflow-name payload]
                  (let [definition
                        (sheet/workflow workflow-name
                          (sheet/blackboard {:payload :string})
                          (sheet/repl-researcher "researcher"
                            :instruction "profile one value without retaining it"
                            :reads [:payload]
                            :max-iterations 1
                            :rlm {:checkpointed? true
                                  :timeouts {:provider-ms 1000
                                             :iteration-ms 2000
                                             :campaign-ms 10000}}))
                        sheet-id (sheet/build-workflow! ctx definition)
                        result (sheet/execute ctx sheet-id {:payload payload}
                                              :timeout-ms 10000)
                        trace-id (:trace-id result)
                        researcher-id
                        (:id (first (filter #(= "researcher" (:name %))
                                            (sheet/get-nodes-for-sheet
                                             ctx sheet-id))))
                        record (first (rm/get-researcher-iteration-records
                                       ctx sheet-id trace-id researcher-id))]
                    (is (= :failure (:status result)) (pr-str result))
                    record))
                small-payload "x"
                large-payload (apply str (repeat 65536 "x"))
                small-record (measure "rr5-small-profiled-payload" small-payload)
                large-record (measure "rr5-large-profiled-payload" large-payload)
                normalize-length #(assoc-in % [:result-profile :length]
                                            ::profiled-length)
                byte-growth (- (serialized-edn-bytes large-record)
                               (serialized-edn-bytes small-record))
                digit-growth (- (count (str (count large-payload)))
                                (count (str (count small-payload))))]
            (is (empty? @clock-values))
            (is (= (count large-payload)
                   (get-in large-record [:result-profile :length])))
            (is (= (normalize-length small-record)
                   (normalize-length large-record))
                (str "payload values changed more than their profile length: small="
                     (pr-str small-record) " large=" (pr-str large-record)))
            (is (= digit-growth byte-growth)
                (str "serialized growth must be only the decimal length metadata; "
                     "byte-growth=" byte-growth " digit-growth=" digit-growth))
            (is (not (str/includes? (pr-str large-record) large-payload))
                "the 64 KiB payload must not appear in the serialized digest")))))))

(deftest version-two-campaign-event-bytes-grow-linearly-with-iterations
  (testing "equal-sized iteration payloads have bounded bytes per added iteration"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (with-redefs [llm/predict
                    (fn [& _]
                      {:outputs {:code "(store! :memo \"fixed-payload\")"}
                       :reasoning "fixed-reasoning"
                       :usage {:prompt_tokens 5 :completion_tokens 2 :total_tokens 7}})]
        (let [measure
              (fn [n]
                (let [definition
                      (sheet/workflow (str "v2-linear-bytes-" n)
                        (sheet/blackboard {:summary :string})
                        (sheet/repl-researcher "researcher"
                          :instruction "repeat a fixed-size durable iteration"
                          :writes [:summary]
                          :max-iterations n
                          :rlm {:checkpointed? true
                                :timeouts {:provider-ms 500
                                           :iteration-ms 1000
                                           :campaign-ms 10000}}))
                      sheet-id (sheet/build-workflow! ctx definition)
                      result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                      trace-id (:trace-id result)
                      researcher-id (:id (first (filter #(= "researcher" (:name %))
                                                         (sheet/get-nodes-for-sheet
                                                          ctx sheet-id))))]
                  (is (= :failure (:status result)) (pr-str result))
                  (is (h/settle-until!
                       #(= n (count (:researcher-iterations
                                     (rm/get-trace ctx trace-id))))))
                  (let [records (rm/get-researcher-iteration-records
                                 ctx sheet-id trace-id researcher-id)
                        events (h/read-tick-events ctx trace-id)]
                    (is (= n (count records)))
                    (is (apply = (map #(count (:code %)) records))
                        "every synthetic iteration carries the same-sized code")
                    {:iterations n
                     :bytes (reduce + (map h/event-bytes events))
                     :event-count (count events)})))
              measurements (mapv measure [2 4 8])
              bytes-per-iteration
              (mapv (fn [{:keys [iterations bytes]}]
                      (/ (double bytes) iterations))
                    measurements)
              assertion-message
              (str "measurements=" (pr-str measurements)
                   " bytes-per-iteration=" (pr-str bytes-per-iteration))]
          ;; A fixed event overhead plus a bounded amount per iteration makes
          ;; total/N fall toward the per-iteration constant. Repeated history
          ;; blobs make total/N rise with N. This compares the observed shape;
          ;; it does not encode a guessed byte or multiplier limit.
          (println assertion-message)
          (is (apply >= bytes-per-iteration) assertion-message))))))

(deftest later-timeout-retains-prior-iteration-in-public-trace
  (let [clock-values (atom [1000 1010 2000 2020])]
    (h/with-async-test-context
      [ctx {:context {:llm-provider :test
                      :researcher-now-ms-fn
                      (fn []
                        (if-let [value (first @clock-values)]
                          (do
                            (swap! clock-values #(vec (next %)))
                            value)
                          (throw (ex-info "RR5 timeout clock exceeded four reads" {}))))}}]
    (let [calls (atom 0)
          definition
          (sheet/workflow "checkpointed-timeout-trace"
            (sheet/blackboard {:summary :string})
            (sheet/repl-researcher "researcher"
              :instruction "checkpoint then timeout"
              :writes [:summary]
              :max-iterations 4
              :rlm {:checkpointed? true
                    :timeouts {:provider-ms 15 :iteration-ms 100 :campaign-ms 5000}
                    :iteration-retry {:max-attempts 1}}))
          sheet-id (sheet/build-workflow! ctx definition)]
      (with-redefs [llm/predict
                    (fn [& _]
                      (if (= 1 (swap! calls inc))
                        {:outputs {:code "(store! :memo \"kept\")"}
                         :reasoning "completed iteration"
                         :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}}
                        (do (Thread/sleep 250)
                            {:outputs {:code "(final! {:summary \"late\"})"}})))]
        (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
              trace-id (:trace-id result)
              researcher-id (:id (first (filter #(= "researcher" (:name %))
                                                 (sheet/get-nodes-for-sheet ctx sheet-id))))]
          (is (= :timeout (:status result)) (pr-str result))
          (is (h/settle-until!
               #(= 2 (count (:researcher-iterations
                             (rm/get-trace ctx trace-id))))))
          (let [trace (rm/get-trace ctx trace-id)
                iterations (:researcher-iterations trace)
                records (rm/get-researcher-iteration-records
                         ctx sheet-id trace-id researcher-id)
                timeout-record (second records)
                resume-state (:resume-state
                              (rm/get-researcher-resume-state
                               ctx sheet-id trace-id researcher-id))
                resume-starts
                (filter #(and (= :sheet/node-execution-started (:event/type %))
                              (:researcher-resume? %))
                        (h/read-tick-events ctx trace-id))]
            (is (= :timeout (:status trace)))
            (is (= [[0 0 :success] [1 0 :timeout]]
                   (mapv (juxt :iteration-index :attempt-ordinal :status) records)))
            (is (= [1 2] (mapv :iteration iterations)))
            (is (= "completed iteration" (:reasoning (first iterations))))
            (is (number? (:duration-ms (first iterations)))
                "attempt latency remains in the digest's lifecycle duration")
            (is (= 5 (get-in resume-state [:usage :total-tokens]))
                "provider usage remains in supersedable resume accounting")
            (is (= "provider-timeout" (:error-class (second iterations)))
                "the bounded digest classifies the timeout without legacy timeout metadata")
            (is (empty? @clock-values)
                "two attempts consume one start and completion reading each")
            (is (= {:started-at "1970-01-01T00:00:02Z"
                    :completed-at "1970-01-01T00:00:02.020Z"
                    :duration-ms 20
                    :error-class "provider-timeout"
                    :error-excerpt "Provider call deadline exceeded"}
                   (select-keys timeout-record
                                [:started-at :completed-at :duration-ms
                                 :error-class :error-excerpt]))
                (pr-str timeout-record))
            (is (false? (:generated-code-recorded? timeout-record))
                "a provider timeout cannot inherit generated-code evidence from the prior iteration")
            (is (false? (:emitted-tree-recorded? timeout-record))
                "a provider timeout cannot inherit emitted-tree evidence from the prior iteration")
            (is (not-any? #(contains? timeout-record %)
                          [:code :reasoning :emitted-tree :tree-fingerprint :error])
                (str "timeout inherited prior authored evidence: "
                     (pr-str timeout-record)))
            (is (= {:created-keys [] :updated-keys [] :removed-keys []}
                   (:variable-delta timeout-record))
                "a timeout records an empty attempt delta instead of prior changes")
            (is (= 1 (:next-iteration resume-state))
                "a terminal timeout records its attempt without advancing the frontier")
            (is (= 1 (count resume-starts))
                "only iteration 0's yield schedules a resume; terminal timeout does not"))))))))
