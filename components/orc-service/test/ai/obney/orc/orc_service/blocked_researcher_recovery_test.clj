(ns ai.obney.orc.orc-service.blocked-researcher-recovery-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors :as todo]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [cognitect.anomalies :as anom]))

(def ^:private blocked-reason
  {:kind :approval-required
   :request-id "approval-rr13"
   :question "May this durable action continue?"})

(def rr13-blocking-child-calls (atom 0))
(def rr13-blocking-child-payload (atom blocked-reason))

(defn rr13-blocking-child [_]
  (swap! rr13-blocking-child-calls inc)
  (sheet/block! @rr13-blocking-child-payload))

(defn- generated-blocking-tree []
  [:sequence
   [:code {:writes [:summary]
           :output-schemas {:summary :string}
           :fn "ai.obney.orc.orc-service.blocked-researcher-recovery-test/rr13-blocking-child"}]
   [:final {:keys [:summary]}]])

(defn- provider-result []
  {:outputs
   {:code (str "(emit-tree! (quote " (pr-str (generated-blocking-tree)) "))")}
   :reasoning "The generated child requires an external approval."
   :usage {:prompt_tokens 2
           :completion_tokens 1
           :total_tokens 3}})

(deftest det-e2e-275-blocked-generated-child-is-durable-terminal-evidence
  (testing "a blocked generated child records its exact reason at every durable campaign boundary"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [definition
            (sheet/workflow "rr13-blocked-child-durable-evidence"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "emit one deterministic child that requires approval"
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 5000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id
            (:id (first (filter #(= "researcher" (:name %))
                                (sheet/get-nodes-for-sheet ctx sheet-id))))]
        (reset! rr13-blocking-child-calls 0)
        (reset! rr13-blocking-child-payload blocked-reason)
        (with-redefs [llm/predict (fn [& _] (provider-result))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                tick-id (:trace-id result)
                generated-child-claims
                (filterv #(= :generated-child (:kind %))
                         (sheet/get-researcher-effect-claims
                          ctx sheet-id tick-id researcher-id))
                generated-child-actions
                (->> (rm/get-researcher-actions ctx sheet-id tick-id researcher-id)
                     vals
                     (filter #(= :phase2 (:action-kind %)))
                     vec)
                records
                (rm/get-researcher-iteration-records
                 ctx sheet-id tick-id researcher-id)
                campaign (sheet/get-researcher-campaign
                          ctx tick-id researcher-id)]
            (is (= :blocked (:status result)) (pr-str result))
            (is (= blocked-reason (:block-payload result)) (pr-str result))
            (is (= 1 @rr13-blocking-child-calls)
                "the generated child executes once")
            (is (= 1 (count generated-child-claims))
                (pr-str generated-child-claims))
            (is (= :completed (:status (first generated-child-claims)))
                (pr-str generated-child-claims))
            (is (= {:status :blocked :block-payload blocked-reason}
                   (select-keys (:result (first generated-child-claims))
                                [:status :block-payload])))
            (is (= 1 (count generated-child-actions))
                (pr-str generated-child-actions))
            (is (= {:status :blocked :block-payload blocked-reason}
                   (select-keys (:result (first generated-child-actions))
                                [:status :block-payload])))
            (is (= 1 (count records)) (pr-str records))
            (is (= :blocked (:status (first records))) (pr-str records))
            (is (= blocked-reason (:block-reason (first records)))
                (pr-str records))
            (is (= :blocked (:status campaign)) (pr-str campaign))
            (is (some? (:completed-at campaign)) (pr-str campaign))
            (is (= blocked-reason (:terminal-reason campaign))
                (pr-str campaign))))))))

(deftest det-e2e-275-recovery-rejoins-a-durably-blocked-generated-child
  (testing "recovery after child completion but before iteration commit does not repeat work"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            checkpoint-paused? (atom false)
            old-checkpoint-paused (promise)
            release-old-checkpoint (promise)
            old-checkpoint-result (promise)
            definition
            (sheet/workflow "rr13-blocked-child-rejoin"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "emit one recoverable child that requires approval"
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 5000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id
            (:id (first (filter #(= "researcher" (:name %))
                                (sheet/get-nodes-for-sheet ctx sheet-id))))
            real-process-command cp/process-command]
        (reset! rr13-blocking-child-calls 0)
        (reset! rr13-blocking-child-payload blocked-reason)
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        (provider-result))
                      cp/process-command
                      (fn [command-context]
                        (let [command (:command command-context)]
                          (if (and (= :sheet/checkpoint-researcher-iteration
                                      (:command/name command))
                                   (= 1 (get-in command
                                                [:resume-state :ownership-epoch]))
                                   (compare-and-set! checkpoint-paused? false true))
                            (do
                              (deliver old-checkpoint-paused true)
                              @release-old-checkpoint
                              (let [result (real-process-command command-context)]
                                (deliver old-checkpoint-result result)
                                result))
                            (real-process-command command-context))))]
          (let [execution (future (sheet/execute ctx sheet-id {} :timeout-ms 15000))
                paused? (deref old-checkpoint-paused 7000 false)
                events-at-crash (h/read-all-events ctx)
                start-event
                (when paused?
                  (first (filter #(and (= :sheet/node-execution-started
                                          (:event/type %))
                                       (= researcher-id (:node-id %)))
                                 events-at-crash)))
                claims-at-crash
                (when start-event
                  (sheet/get-researcher-effect-claims
                   ctx sheet-id (:tick-id start-event) researcher-id))
                recovered-worker
                (when start-event
                  (todo/execute-repl-researcher-node
                   (assoc ctx
                          :llm-provider :test
                          :event (assoc start-event
                                        :researcher-ownership-epoch 2))))
                recovered-result
                (when recovered-worker
                  (deref recovered-worker 10000 ::timeout))
                result (deref execution 15000 ::timeout)
                tick-id (:tick-id start-event)
                events-before-release (h/read-all-events ctx)
                child-starts
                (filterv #(and (= :sheet/tree-tick-started (:event/type %))
                               (= tick-id (:parent-tick-id %)))
                         events-before-release)
                claims (sheet/get-researcher-effect-claims
                        ctx sheet-id tick-id researcher-id)
                generated-child-claims
                (filterv #(= :generated-child (:kind %)) claims)
                records (rm/get-researcher-iteration-records
                         ctx sheet-id tick-id researcher-id)
                campaign (sheet/get-researcher-campaign
                          ctx tick-id researcher-id)
                parent-node-completions
                (filterv #(and (= :sheet/node-execution-completed
                                   (:event/type %))
                               (= researcher-id (:node-id %)))
                         events-before-release)
                parent-tick-completions
                (filterv #(and (= :sheet/tree-tick-completed (:event/type %))
                               (= tick-id (:tick-id %)))
                         events-before-release)]
            (try
              (is paused? "the old worker reaches the intended crash window")
              (is (some? start-event))
              (is (= [:completed]
                     (mapv :status
                           (filter #(= :generated-child (:kind %))
                                   claims-at-crash)))
                  (pr-str claims-at-crash))
              (is (not= ::timeout recovered-result))
              (is (= :blocked (:status result)) (pr-str result))
              (is (= blocked-reason (:block-payload result)) (pr-str result))
              (is (= 1 @provider-calls)
                  "recovery reuses the completed provider result")
              (is (= 1 @rr13-blocking-child-calls)
                  "recovery reuses the completed child result")
              (is (= 1 (count child-starts)) (pr-str child-starts))
              (is (= 1 (count generated-child-claims))
                  (pr-str generated-child-claims))
              (is (= :completed (:status (first generated-child-claims))))
              (is (= 1 (count records)) (pr-str records))
              (is (= {:status :blocked :block-reason blocked-reason}
                     (select-keys (first records) [:status :block-reason])))
              (is (= {:status :blocked :terminal-reason blocked-reason}
                     (select-keys campaign [:status :terminal-reason])))
              (is (= 1 (count parent-node-completions))
                  (pr-str parent-node-completions))
              (is (= :blocked (:status (first parent-node-completions))))
              (is (= 1 (count parent-tick-completions))
                  (pr-str parent-tick-completions))
              (is (= :blocked (:root-status (first parent-tick-completions))))
              (finally
                (deliver release-old-checkpoint true)))
            (let [stale-result (deref old-checkpoint-result 5000 {})]
              (is (or (= ::anom/conflict (::anom/category stale-result))
                      (empty? (:command-result/events stale-result)))
                  (str "the stale epoch must conflict or canonicalize to a no-op: "
                       (pr-str stale-result))))
            (is (= 1 (count (rm/get-researcher-iteration-records
                             ctx sheet-id tick-id researcher-id))))
            (is (= 1 @rr13-blocking-child-calls))))))))

(deftest det-e2e-275-block-reason-is-status-carried-not-truthiness-filtered
  (testing "an opaque false block reason survives every durable campaign boundary"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [definition
            (sheet/workflow "rr13-false-block-reason"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "emit one deterministic child with an opaque false reason"
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 5000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id
            (:id (first (filter #(= "researcher" (:name %))
                                (sheet/get-nodes-for-sheet ctx sheet-id))))]
        (reset! rr13-blocking-child-calls 0)
        (reset! rr13-blocking-child-payload false)
        (try
          (with-redefs [llm/predict (fn [& _] (provider-result))]
            (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                  tick-id (:trace-id result)
                  child-claim
                  (first (filter #(= :generated-child (:kind %))
                                 (sheet/get-researcher-effect-claims
                                  ctx sheet-id tick-id researcher-id)))
                  record (first (rm/get-researcher-iteration-records
                                 ctx sheet-id tick-id researcher-id))
                  campaign (sheet/get-researcher-campaign
                            ctx tick-id researcher-id)]
              (is (= :blocked (:status result)) (pr-str result))
              (is (contains? result :block-payload) (pr-str result))
              (is (false? (:block-payload result)) (pr-str result))
              (is (contains? (:result child-claim) :block-payload)
                  (pr-str child-claim))
              (is (false? (get-in child-claim [:result :block-payload]))
                  (pr-str child-claim))
              (is (= :blocked (:status record)) (pr-str record))
              (is (contains? record :block-reason) (pr-str record))
              (is (false? (:block-reason record)) (pr-str record))
              (is (= :blocked (:status campaign)) (pr-str campaign))
              (is (contains? campaign :terminal-reason) (pr-str campaign))
              (is (false? (:terminal-reason campaign)) (pr-str campaign))))
          (finally
            (reset! rr13-blocking-child-payload blocked-reason)))))))

(deftest det-e2e-275-nil-block-reason-retains-presence
  (testing "an opaque nil block reason remains present as durable blocked evidence"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [definition
            (sheet/workflow "rr13-nil-block-reason"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "emit one deterministic child with an opaque nil reason"
                :writes [:summary]
                :max-iterations 1
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 5000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id
            (:id (first (filter #(= "researcher" (:name %))
                                (sheet/get-nodes-for-sheet ctx sheet-id))))]
        (reset! rr13-blocking-child-calls 0)
        (reset! rr13-blocking-child-payload nil)
        (try
          (with-redefs [llm/predict (fn [& _] (provider-result))]
            (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                  tick-id (:trace-id result)
                  child-claim
                  (first (filter #(= :generated-child (:kind %))
                                 (sheet/get-researcher-effect-claims
                                  ctx sheet-id tick-id researcher-id)))
                  record (first (rm/get-researcher-iteration-records
                                 ctx sheet-id tick-id researcher-id))
                  campaign (sheet/get-researcher-campaign
                            ctx tick-id researcher-id)]
              (is (= :blocked (:status result)) (pr-str result))
              (is (contains? result :block-payload) (pr-str result))
              (is (nil? (:block-payload result)) (pr-str result))
              (is (contains? (:result child-claim) :block-payload)
                  (pr-str child-claim))
              (is (nil? (get-in child-claim [:result :block-payload]))
                  (pr-str child-claim))
              (is (= :blocked (:status record)) (pr-str record))
              (is (contains? record :block-reason) (pr-str record))
              (is (nil? (:block-reason record)) (pr-str record))
              (is (= :blocked (:status campaign)) (pr-str campaign))
              (is (contains? campaign :terminal-reason) (pr-str campaign))
              (is (nil? (:terminal-reason campaign)) (pr-str campaign))))
          (finally
            (reset! rr13-blocking-child-payload blocked-reason)))))))
