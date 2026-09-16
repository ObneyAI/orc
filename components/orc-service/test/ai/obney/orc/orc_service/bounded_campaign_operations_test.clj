(ns ai.obney.orc.orc-service.bounded-campaign-operations-test
  "Propagated RR-9 contracts for bounded and drainable researcher campaigns."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.control-plane.core :as control-plane-core]
            [ai.obney.grain.control-plane.interface :as control-plane]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.periodic-task.interface :as periodic]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.execution-budget :as execution-budget]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.todo-processors :as todo]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas :as schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [malli.core :as m]))

(deftest det-e2e-267-classification-commit-requires-positive-epoch-and-one-outcome
  (let [schema (schemas/commands :sheet/commit-researcher-classification)
        sheet-id (random-uuid)
        tick-id (random-uuid)
        node-id (random-uuid)
        class-id (random-uuid)
        assignment {:command/name :ontology/assign-task-class
                    :source-sheet-id sheet-id
                    :source-tick-id tick-id
                    :source-node-id node-id
                    :assigned-tree-id class-id
                    :confidence 0.9
                    :top-candidates []
                    :reasoning "bound atomic assignment"
                    :was-fresh-mint? false
                    :researcher-ownership-epoch 1}
        deferral {:command/name :ontology/record-task-classification-deferral
                  :source-sheet-id sheet-id
                  :source-tick-id tick-id
                  :source-node-id node-id
                  :fallback-source :colbert-fallback
                  :ranked-candidates []
                  :reasoning "bound atomic deferral"
                  :researcher-ownership-epoch 1}
        injection {:command/name :sheet/record-injection
                   :sheet-id sheet-id
                   :tick-id tick-id
                   :node-id node-id}
        convergence {:command/name :ontology/record-claim-deltas
                     :granularity :tree-class
                     :target-identifier class-id}
        base {:sheet-id sheet-id
              :tick-id tick-id
              :node-id node-id
              :ownership-epoch 1
              :effects [assignment]}]
    (is (m/validate schema base))
    (is (m/validate schema (assoc base :effects [deferral])))
    (is (m/validate schema (assoc base :effects [injection assignment])))
    (is (m/validate schema
                    (assoc base :effects
                           [convergence
                            (assoc assignment :was-fresh-mint? true)])))
    (is (not (m/validate schema (assoc base :ownership-epoch 0))))
    (is (not (m/validate schema (assoc base :ownership-epoch -1))))
    (is (not (m/validate schema (assoc base :effects []))))
    (is (not (m/validate schema
                         (assoc base :effects [assignment assignment]))))
    (is (not (m/validate schema
                         (assoc base :effects [assignment deferral]))))
    (is (not (m/validate schema
                         (assoc base :effects
                                [(dissoc assignment
                                         :researcher-ownership-epoch)]))))
    (is (not (m/validate schema
                         (assoc base :effects
                                [(assoc assignment
                                        :researcher-ownership-epoch 2)]))))
    (is (not (m/validate schema
                         (assoc base :effects
                                [(assoc assignment
                                        :source-sheet-id (random-uuid))]))))
    (is (not (m/validate schema
                         (assoc base :effects
                                [(assoc assignment
                                        :source-tick-id (random-uuid))]))))
    (is (not (m/validate schema
                         (assoc base :effects
                                [(assoc assignment
                                        :source-node-id (random-uuid))]))))
    (is (not (m/validate schema
                         (assoc base :effects
                                [(assoc injection :tick-id (random-uuid))
                                 assignment]))))
    (is (not (m/validate schema
                         (assoc base :effects
                                [injection injection assignment]))))
    (is (not (m/validate schema
                         (assoc base :effects [convergence assignment]))))
    (is (not (m/validate schema
                         (assoc base :effects [convergence deferral]))))
    (is (not (m/validate schema
                         (assoc base :effects
                                [(assoc convergence
                                        :target-identifier (random-uuid))
                                 (assoc assignment :was-fresh-mint? true)]))))
    (is (not (m/validate schema
                         (assoc base :effects
                                [(assoc convergence :granularity :node-type)
                                 (assoc assignment :was-fresh-mint? true)]))))
    (is (not (m/validate schema
                         (assoc base :effects
                                [convergence convergence
                                 (assoc assignment :was-fresh-mint? true)]))))
    (let [handler
          (requiring-resolve
           'ai.obney.orc.orc-service.core.commands/sheet-commit-researcher-classification)
          invalid-command
          (assoc base :effects
                 [(dissoc assignment :researcher-ownership-epoch)])
          result (handler {:command invalid-command})]
      (is (= :cognitect.anomalies/incorrect
             (:cognitect.anomalies/category result))
          "the directly callable handler independently rejects an unbound outcome")
      (is (= :cognitect.anomalies/incorrect
             (:cognitect.anomalies/category
              (handler {:command (assoc base :effects 42)})))
          "the handler returns a boundary anomaly for a scalar effect payload")
      (is (= :cognitect.anomalies/incorrect
             (:cognitect.anomalies/category
              (handler {:command (assoc base :effects [42 assignment])})))
          "the handler returns a boundary anomaly for a non-command effect")
      (let [full-injection
            (merge injection
                   {:intervention/type :pattern-injection
                    :candidates []
                    :rendered-chars 0
                    :arm :treatment
                    :baseline-policy-id "rr9-boundary-test"
                    :selection-propensity 1.0})
            canonical-result
            (handler {:command
                      (assoc base :effects [assignment full-injection])})]
        (is (= [:intervention/injection-recorded
                :ontology/task-classified]
               (mapv :event/type
                     (:command-result/events canonical-result)))
            "the handler publishes the externally visible outcome last")))))

(deftest det-e2e-267-optional-classification-epoch-is-positive-when-present
  (let [schema (ontology-schemas/commands :ontology/assign-task-class)
        base {:source-sheet-id (random-uuid)
              :source-tick-id (random-uuid)
              :source-node-id (random-uuid)
              :assigned-tree-id (random-uuid)
              :confidence 0.9
              :top-candidates []
              :reasoning "legacy-compatible classification"
              :was-fresh-mint? false}]
    (is (m/validate schema base)
        "legacy classification remains valid without a campaign epoch")
    (is (m/validate schema (assoc base :researcher-ownership-epoch 1)))
    (is (not (m/validate schema
                         (assoc base :researcher-ownership-epoch 0))))
    (is (not (m/validate schema
                         (assoc base :researcher-ownership-epoch -1))))))

(deftest det-e2e-267-running-bookend-does-not-fence-a-new-classification-epoch
  (h/with-async-test-context [ctx]
    (let [sheet-id (random-uuid)
          node-id (random-uuid)
          expire-tick-id (random-uuid)
          assign-tick-id (random-uuid)
          issue!
          (fn [command-name body]
            (cp/process-command
             (assoc ctx :command
                    (merge {:command/id (random-uuid)
                            :command/timestamp (time/now)
                            :command/name command-name}
                           body))))
          advance-to-second-epoch!
          (fn [tick-id]
            (issue! :sheet/claim-researcher-frontier
                    {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :ownership-epoch 1
                     :claimed-at "1970-01-01T00:00:01Z"})
            (issue! :sheet/emit-tick-completed
                    {:sheet-id sheet-id
                     :tick-id tick-id
                     :root-status :running})
            (issue! :sheet/claim-researcher-frontier
                    {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :ownership-epoch 2
                     :claimed-at "1970-01-01T00:00:02Z"}))]
      (advance-to-second-epoch! expire-tick-id)
      (issue! :sheet/expire-researcher-classification
              {:sheet-id sheet-id
               :tick-id expire-tick-id
               :node-id node-id
               :ownership-epoch 2
               :expired-at "1970-01-01T00:00:03Z"})
      (is (= 1
             (count
              (filter #(= :rlm/researcher-classification-expired
                          (:event/type %))
                      (h/read-tick-events ctx expire-tick-id))))
          "the shared classification CAS accepts the new epoch after a running bookend")

      (advance-to-second-epoch! assign-tick-id)
      (issue! :ontology/assign-task-class
              {:source-sheet-id sheet-id
               :source-tick-id assign-tick-id
               :source-node-id node-id
               :assigned-tree-id (random-uuid)
               :confidence 0.9
               :top-candidates []
               :reasoning "the campaign is still live"
               :was-fresh-mint? false
               :researcher-ownership-epoch 2})
      (is (= 1
             (count
              (filter #(= :ontology/task-classified (:event/type %))
                      (h/read-tick-events ctx assign-tick-id))))
          "the legacy classification command's epoch fence has the same semantics"))))

(deftest det-e2e-267-classification-commit-preserves-convergence-claim-cas
  (testing "a claim-set version change between preparation and append rejects the whole batch"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            class-id (random-uuid)
            real-append es/append
            competitor-appended? (atom false)
            envelope
            (fn [command]
              (merge {:command/id (random-uuid)
                      :command/timestamp (time/now)}
                     command))]
        (cp/process-command
         (assoc ctx :command
                (envelope
                 {:command/name :sheet/claim-researcher-frontier
                  :sheet-id sheet-id
                  :tick-id tick-id
                  :node-id node-id
                  :ownership-epoch 1
                  :claimed-at "1970-01-01T00:00:01Z"})))
        (let [convergence-command
              (envelope
               {:command/name :ontology/record-claim-deltas
                :granularity :tree-class
                :target-identifier class-id
                :deltas [{:operation :add
                          :kind :representative-use
                          :content "concurrent convergence signature"
                          :context-guard nil
                          :recommendation nil
                          :episodes []
                          :from-legacy-corpus false
                          :evidence-basis :classification-signature}]
                :evidence-event-count 0
                :claim-set-version 0})
              assignment-command
              (envelope
               {:command/name :ontology/assign-task-class
                :source-sheet-id sheet-id
                :source-tick-id tick-id
                :source-node-id node-id
                :assigned-tree-id class-id
                :confidence 0.9
                :top-candidates []
                :reasoning "prepared against claim-set version zero"
                :was-fresh-mint? true
                :researcher-ownership-epoch 1})
              result
              (with-redefs
                [es/append
                 (fn [event-store args]
                   (if-let [claim-event
                            (and (not @competitor-appended?)
                                 (some #(when (= :ontology/task-classified
                                                   (:event/type %))
                                          true)
                                       (:events args))
                                 (some #(when (= :ontology/claim-deltas-recorded
                                                   (:event/type %))
                                          %)
                                       (:events args)))]
                     (do
                       (reset! competitor-appended? true)
                       (real-append
                        event-store
                        {:tenant-id (:tenant-id args)
                         :events
                         [(es/->event
                           {:type :ontology/claim-deltas-recorded
                            :tags (:event/tags claim-event)
                            :body
                            (select-keys
                             claim-event
                             [:granularity :target-identifier :deltas
                              :evidence-event-count :claim-set-version
                              :recorded-at])})]})
                       (real-append event-store args))
                     (real-append event-store args)))]
                (cp/process-command
                 (assoc ctx :command
                        (envelope
                         {:command/name :sheet/commit-researcher-classification
                          :sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :ownership-epoch 1
                          :effects [convergence-command assignment-command]}))))
              claim-events
              (into []
                    (es/read (:event-store ctx)
                             {:tenant-id (:tenant-id ctx)
                              :types #{:ontology/claim-deltas-recorded}}))]
          (is @competitor-appended?
              "the harness advances the claim-set version in the intended race window")
          (is (= :cognitect.anomalies/conflict
                 (:cognitect.anomalies/category result))
              (pr-str result))
          (is (= 1 (count claim-events))
              "only the competing claim is durable; the stale batch is atomic")
          (is (not-any? #(= :ontology/task-classified (:event/type %))
                        (h/read-tick-events ctx tick-id))
              "the stale batch cannot publish its classification outcome"))))))

(deftest det-e2e-262-cancelling-a-checkpointed-campaign-drains-registered-work
  (testing "parent cancellation retains committed evidence and stops in-flight campaign work"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            provider-entered (promise)
            provider-interrupted (promise)
            provider-finished (promise)
            release-provider (promise)
            tick-id (random-uuid)
            definition
            (sheet/workflow "rr9-cancellable-campaign"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "retain one iteration, then wait"
                :writes [:summary]
                :max-iterations 3
                :rlm {:recursive? true
                      :checkpointed? true
                      :auto-classify? false
                      :quantum {:max-iterations 1}
                      :timeouts {:provider-ms 10000
                                 :iteration-ms 12000
                                 :campaign-ms 20000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
            predict-fn
            (fn [& _]
              (case (swap! provider-calls inc)
                1 {:outputs {:code "(store! :memo \"kept\")"}
                   :reasoning "commit durable progress"
                   :usage {:prompt_tokens 2
                           :completion_tokens 1
                           :total_tokens 3}}
                2 (do
                    (deliver provider-entered true)
                    (try
                      @release-provider
                      {:outputs {:code "(final! {:summary \"late\"})"}
                       :reasoning "must not land after cancellation"
                       :usage {:prompt_tokens 2
                               :completion_tokens 1
                               :total_tokens 3}}
                      (catch InterruptedException interrupted
                        (deliver provider-interrupted true)
                        (throw interrupted))
                      (finally
                        (deliver provider-finished true))))))]
        (with-redefs [llm/predict predict-fn]
          (let [execution (future
                            (sheet/execute ctx sheet-id {}
                                           :tick-id tick-id
                                           :timeout-ms 15000))]
          (try
          (is (= true (deref provider-entered 5000 ::provider-not-entered))
              "the second provider attempt is observably in flight")
          (is (= {:cancelled [tick-id]} (sheet/cancel! ctx tick-id)))
          (let [result (deref execution 3000 ::caller-still-blocked)]
            (is (not= ::caller-still-blocked result))
            (is (true? (:cancelled? result)) (pr-str result)))
          (is (= true (deref provider-interrupted 2000 ::provider-not-interrupted))
              "cancellation interrupts the registered provider worker")
          (is (h/settle-until!
               #(= :cancelled
                   (:status (sheet/get-researcher-campaign
                             ctx tick-id researcher-id))))
              "the campaign lifecycle reaches its durable cancellation terminal")
          (let [campaign (sheet/get-researcher-campaign ctx tick-id researcher-id)
                records (rm/get-researcher-iteration-records
                         ctx sheet-id tick-id researcher-id)
                claims (rm/get-researcher-effect-claims
                        ctx sheet-id tick-id researcher-id)
                cancellation-events
                (filter #(= :sheet/tick-cancelled (:event/type %))
                        (h/read-tick-events ctx tick-id))]
            (is (= :cancelled (:status campaign)) (pr-str campaign))
            (is (some? (:completed-at campaign)) (pr-str campaign))
            (is (and (string? (:terminal-reason campaign))
                     (not (str/blank? (:terminal-reason campaign))))
                (pr-str campaign))
            (is (= [[0 :success]]
                   (mapv (juxt :iteration-index :status) records))
                "the completed pre-cancellation iteration remains inspectable")
            (is (= 2 (count claims))
                "the completed and interrupted provider attempts remain inspectable")
            (is (= 1 (count cancellation-events))
                "the campaign has one durable cancellation terminal"))
          (deliver release-provider true)
          (is (= true (deref provider-finished 2000 ::provider-not-finished)))
          (is (not-any? (fn [event]
                          (= :sheet/node-execution-completed (:event/type event)))
                        (h/read-tick-events ctx tick-id))
              "releasing stale work cannot append a late node completion")
          (is (empty? (filter #(= tick-id (:tick-id %))
                              (runtime/resume-in-progress! ctx)))
              "explicit recovery does not resume a cancelled campaign")
          (finally
            (deliver release-provider true)))))))))

(deftest det-e2e-264-cancellation-command-normalizes-a-blank-campaign-cause
  (testing "every cancellation producer records a stable non-blank cause"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-entered (promise)
            provider-interrupted (promise)
            release-provider (promise)
            tick-id (random-uuid)
            definition
            (sheet/workflow "rr9-cancellation-cause-boundary"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "wait for command-boundary cancellation"
                :writes [:summary]
                :max-iterations 1
                :rlm {:recursive? true
                      :checkpointed? true
                      :auto-classify? false
                      :timeouts {:provider-ms 10000
                                 :iteration-ms 12000
                                 :campaign-ms 20000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
            predict-fn
            (fn [& _]
              (deliver provider-entered true)
              (try
                @release-provider
                {:outputs {:code "(final! {:summary \"late\"})"}
                 :reasoning "must not complete after cancellation"
                 :usage {:prompt_tokens 2
                         :completion_tokens 1
                         :total_tokens 3}}
                (catch InterruptedException interrupted
                  (deliver provider-interrupted true)
                  (throw interrupted))))]
        (with-redefs [llm/predict predict-fn]
          (let [execution (future
                            (sheet/execute ctx sheet-id {}
                                           :tick-id tick-id
                                           :timeout-ms 15000))]
            (try
              (is (= true (deref provider-entered 5000 ::provider-not-entered)))
              (cp/process-command
               (assoc ctx :command
                      {:command/id (random-uuid)
                       :command/timestamp (time/now)
                       :command/name :sheet/cancel-tick
                       :sheet-id sheet-id
                       :tick-id tick-id
                       :reason "   "}))
              (let [result (deref execution 3000 ::caller-still-blocked)]
                (is (not= ::caller-still-blocked result))
                (is (true? (:cancelled? result)) (pr-str result)))
              (is (= true (deref provider-interrupted 2000 ::provider-not-interrupted)))
              (is (h/settle-until!
                   #(= :cancelled
                       (:status (sheet/get-researcher-campaign
                                 ctx tick-id researcher-id)))))
              (let [campaign (sheet/get-researcher-campaign ctx tick-id researcher-id)
                    cancellation (first
                                  (filter #(= :sheet/tick-cancelled (:event/type %))
                                          (h/read-tick-events ctx tick-id)))]
                (is (and (string? (:reason cancellation))
                         (not (str/blank? (:reason cancellation))))
                    (pr-str cancellation))
                (is (and (string? (:terminal-reason campaign))
                         (not (str/blank? (:terminal-reason campaign))))
                    (pr-str campaign)))
              (finally
                (deliver release-provider true)))))))))

(deftest det-e2e-267-classification-is-bounded-inside-the-campaign-clock
  (testing "classification receives the effective deadline and times out durably before provider dispatch"
    (let [campaign-now-ms (constantly 1000)
          monotonic-ms (atom 100)]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :campaign-now-ms-fn campaign-now-ms
                        :researcher-monotonic-ms-fn #(long @monotonic-ms)}}]
        (let [classification-context (promise)
              classification-interrupted (promise)
              classification-finished (promise)
              release-classification (promise)
              provider-calls (atom 0)
              tick-id (random-uuid)
              definition
              (sheet/workflow "rr9-bounded-classification"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "classification must fit inside this campaign"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:recursive? true
                        :checkpointed? true
                        :auto-classify? true
                        :timeouts {:classification-ms 30
                                   :provider-ms 100
                                   :iteration-ms 500
                                   :campaign-ms 1000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
              classify-task-fn
              (fn [classify-ctx _]
                (reset! monotonic-ms 150)
                (deliver classification-context
                         (select-keys classify-ctx
                                      [:campaign-started-at-ms
                                       :campaign-deadline-ms
                                       :classification-deadline-ms
                                       :rerank-timeout-ms]))
                (try
                  @release-classification
                  (catch InterruptedException interrupted
                    (deliver classification-interrupted true)
                    ;; Simulate local/transport work that does not cooperate
                    ;; with interruption.  The campaign deadline must still
                    ;; fence its later classification side effect.
                    @release-classification))
                (deliver classification-finished true)
                {:assigned-tree-id (random-uuid)
                 :confidence 0.9
                 :top-candidates []
                 :ranked-candidates []
                 :reasoning "late result must be discarded"
                 :was-fresh-mint? false
                 :assigned-via :match
                 :outcome :matched})]
          (with-redefs [ontology/classify-task classify-task-fn
                        ontology/classify-behaviors
                        (fn [_ _]
                          {:behaviors []
                           :rerank-fallback? false
                           :outcome :matched})
                        llm/predict (fn [& _]
                                      (swap! provider-calls inc)
                                      {:outputs {:code "(final! {:summary \"too late\"})"}})]
            (try
              (let [result (sheet/execute ctx sheet-id {}
                                          :tick-id tick-id
                                          :timeout-ms 1000
                                          :result-grace-ms 100)]
                (is (= {:campaign-started-at-ms 1000
                        :campaign-deadline-ms 2000
                        :classification-deadline-ms 1030
                        :rerank-timeout-ms 30}
                       (deref classification-context 500 ::classification-not-entered)))
                (is (= :timeout (:status result)) (pr-str result))
                (is (= true
                       (deref classification-interrupted 500
                              ::classification-not-interrupted))
                    "the bounded classification worker is drained on timeout")
                (is (h/settle-until!
                     #(= :timeout
                         (:status (sheet/get-researcher-campaign
                                   ctx tick-id researcher-id)))
                     :timeout-ms 1000))
                (deliver release-classification true)
                (is (= true
                       (deref classification-finished 500
                              ::classification-not-finished)))
                (let [events (h/read-tick-events ctx tick-id)
                      timeout-completions
                      (filter #(and (= :sheet/node-execution-completed
                                       (:event/type %))
                                    (= researcher-id (:node-id %))
                                    (= :timeout (:status %)))
                              events)
                      campaign (sheet/get-researcher-campaign
                                ctx tick-id researcher-id)]
                  (is (= 1 (count timeout-completions)) (pr-str events))
                  (is (= [{:observed-quantum-duration-ms 50
                           :max-observed-quantum-duration-ms 50}]
                         (mapv #(select-keys
                                 %
                                 [:observed-quantum-duration-ms
                                  :max-observed-quantum-duration-ms])
                               timeout-completions))
                      "classification preparation belongs to the terminal ownership quantum")
                  (is (= {:observed-quantum-duration-ms 50
                          :max-observed-quantum-duration-ms 50}
                         (select-keys campaign
                                      [:observed-quantum-duration-ms
                                       :max-observed-quantum-duration-ms])))
                  (is (some? (:completed-at campaign)) (pr-str campaign))
                  (is (= {:campaign-started-at-ms 1000
                          :campaign-deadline-ms 2000}
                         (select-keys campaign
                                      [:campaign-started-at-ms
                                       :campaign-deadline-ms]))
                      "the durable campaign frontier retains the clock even when classification never reaches an iteration checkpoint")
                  (is (= {:started-at "1970-01-01T00:00:01Z"
                          :deadline "1970-01-01T00:00:02Z"}
                         (select-keys campaign [:started-at :deadline]))
                      "canonical campaign fields preserve the spec's Timestamp type")
                  (is (not
                       (h/settle-until!
                        #(some (fn [event]
                                 (contains?
                                  #{:ontology/task-classified
                                    :ontology/task-classification-deferred}
                                  (:event/type event)))
                               (h/read-tick-events ctx tick-id))
                        :timeout-ms 500))
                      "a classifier that ignores interruption cannot append a late assignment or deferral")
                  (is (zero? @provider-calls)
                      "no Phase-1 provider dispatch starts after classification expires")))
              (finally
                (deliver release-classification true)))))))))

(deftest det-e2e-267-fresh-mint-does-not-commit-before-classification-settles
  (testing "a timeout during fresh-mint convergence capture leaves no classification assignment"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [capture-entered (promise)
            capture-interrupted (promise)
            release-capture (promise)
            provider-calls (atom 0)
            tick-id (random-uuid)
            tree-id (random-uuid)
            definition
            (sheet/workflow "rr9-fresh-mint-classification-commit"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "do not expose a partially prepared classification"
                :writes [:summary]
                :max-iterations 1
                :rlm {:recursive? true
                      :checkpointed? true
                      :auto-classify? true
                      :timeouts {:classification-ms 50
                                 :provider-ms 100
                                 :iteration-ms 500
                                 :campaign-ms 1000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
            capture-var
            (ns-resolve
             'ai.obney.orc.orc-service.core.todo-processors
             'capture-classification-signature!)
            capture! @capture-var
            blocking-capture!
            (fn [capture-ctx class-id signature]
              (deliver capture-entered true)
              (try
                @release-capture
                (catch InterruptedException interrupted
                  (deliver capture-interrupted true)
                  ;; Model a local durable preparation step that cannot abort
                  ;; merely because its supervising future was interrupted.
                  @release-capture))
              (capture! capture-ctx class-id signature))]
        (with-redefs-fn
          {#'ontology/classify-task
           (fn [_ _]
             {:assigned-tree-id tree-id
              :confidence 0.9
              :top-candidates []
              :ranked-candidates []
              :reasoning "fresh deterministic class"
              :was-fresh-mint? true
              :assigned-via :mint
              :outcome :novel})
           #'ontology/classify-behaviors
           (fn [_ _]
             {:behaviors []
              :rerank-fallback? false
              :outcome :matched})
           #'llm/predict
           (fn [& _]
             (swap! provider-calls inc)
             {:outputs {:code "(final! {:summary \"must not run\"})"}})
           capture-var blocking-capture!}
          (fn []
            (try
              (let [execution
                    (future
                      (sheet/execute ctx sheet-id {}
                                     :tick-id tick-id
                                     :timeout-ms 1000
                                     :result-grace-ms 100))]
                (is (= true (deref capture-entered 1000 ::capture-not-entered)))
                (let [result (deref execution 2000 ::execution-not-settled)]
                  (is (= :timeout (:status result)) (pr-str result)))
                (is (= true
                       (deref capture-interrupted 500 ::capture-not-interrupted)))
                (let [events (h/read-tick-events ctx tick-id)]
                  (is (not-any? #(= :ontology/task-classified (:event/type %))
                                events)
                      (str "a classification fact became visible before its "
                           "fresh-mint preparation settled: " (pr-str events))))
                (is (= :timeout
                       (:status (sheet/get-researcher-campaign
                                 ctx tick-id researcher-id))))
                (is (zero? @provider-calls)))
              (finally
                (deliver release-capture true)))))))))

(deftest det-e2e-267-late-classification-commit-loses-to-durable-expiration
  (testing "a commit that ignores interruption cannot append after classification expiration"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [commit-entered (promise)
            commit-interrupted (promise)
            commit-finished (promise)
            release-commit (promise)
            provider-calls (atom 0)
            tick-id (random-uuid)
            tree-id (random-uuid)
            definition
            (sheet/workflow "rr9-late-classification-commit"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "late classification commits lose durably"
                :writes [:summary]
                :max-iterations 1
                :rlm {:recursive? true
                      :checkpointed? true
                      :auto-classify? true
                      :timeouts {:classification-ms 50
                                 :provider-ms 100
                                 :iteration-ms 500
                                 :campaign-ms 1000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
            process-command cp/process-command
            blocking-process-command
            (fn [command-ctx]
              (if (= :sheet/commit-researcher-classification
                     (get-in command-ctx [:command :command/name]))
                (do
                  (deliver commit-entered true)
                  (try
                    @release-commit
                    (catch InterruptedException interrupted
                      (deliver commit-interrupted true)
                      @release-commit))
                  (try
                    (process-command command-ctx)
                    (finally
                      (deliver commit-finished true))))
                (process-command command-ctx)))]
        (with-redefs [ontology/classify-task
                      (fn [_ _]
                        {:assigned-tree-id tree-id
                         :confidence 0.9
                         :top-candidates []
                         :ranked-candidates []
                         :reasoning "deterministic match"
                         :was-fresh-mint? true
                         :assigned-via :mint
                         :outcome :novel})
                      ontology/classify-behaviors
                      (fn [_ _]
                        {:behaviors []
                         :rerank-fallback? false
                         :outcome :matched})
                      llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs {:code "(final! {:summary \"must not run\"})"}})
                      cp/process-command blocking-process-command]
          (try
            (let [result (sheet/execute ctx sheet-id {}
                                        :tick-id tick-id
                                        :timeout-ms 1000
                                        :result-grace-ms 100)]
              (is (= true (deref commit-entered 1000 ::commit-not-entered)))
              (is (= :timeout (:status result)) (pr-str result))
              (is (= true
                     (deref commit-interrupted 500 ::commit-not-interrupted)))
              (is (= :timeout
                     (:status (sheet/get-researcher-campaign
                               ctx tick-id researcher-id))))
              (deliver release-commit true)
              (is (= true (deref commit-finished 1000 ::commit-not-finished)))
              (is (not
                   (h/settle-until!
                    #(some (fn [event]
                             (contains?
                              #{:ontology/task-classified
                                :ontology/task-classification-deferred
                                :intervention/injection-recorded}
                              (:event/type event)))
                           (h/read-tick-events ctx tick-id))
                    :timeout-ms 500))
                  "the expiration CAS rejects the whole late classification batch")
              (is (empty?
                   (into []
                         (es/read (:event-store ctx)
                                  {:tenant-id (:tenant-id ctx)
                                   :types #{:ontology/claim-deltas-recorded}})))
                  "the rejected atomic batch leaves no orphan convergence claim")
              (is (zero? @provider-calls)))
            (finally
              (deliver release-commit true))))))))

(deftest det-e2e-267-durable-classification-commit-wins-before-expiration
  (testing "a commit durably accepted before the deadline is not misreported as timeout when its caller returns late"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [commit-appended (promise)
            commit-interrupted (promise)
            release-commit-return (promise)
            provider-calls (atom 0)
            tick-id (random-uuid)
            tree-id (random-uuid)
            definition
            (sheet/workflow "rr9-classification-commit-wins"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "honour the durable winner"
                :writes [:summary]
                :max-iterations 1
                :rlm {:recursive? true
                      :checkpointed? true
                      :auto-classify? true
                      :timeouts {:classification-ms 50
                                 :provider-ms 200
                                 :iteration-ms 500
                                 :campaign-ms 1000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            process-command cp/process-command
            delayed-return
            (fn [command-ctx]
              (if (= :sheet/commit-researcher-classification
                     (get-in command-ctx [:command :command/name]))
                (let [result (process-command command-ctx)]
                  (deliver commit-appended true)
                  (try
                    @release-commit-return
                    (catch InterruptedException interrupted
                      (deliver commit-interrupted true)
                      @release-commit-return))
                  result)
                (process-command command-ctx)))]
        (with-redefs [ontology/classify-task
                      (fn [_ _]
                        {:assigned-tree-id tree-id
                         :confidence 0.9
                         :top-candidates []
                         :ranked-candidates []
                         :reasoning "durably accepted"
                         :was-fresh-mint? true
                         :assigned-via :mint
                         :outcome :novel})
                      ontology/classify-behaviors
                      (fn [_ _]
                        {:behaviors []
                         :rerank-fallback? false
                         :outcome :matched})
                      llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs {:code "(final! {:summary \"done\"})"}})
                      cp/process-command delayed-return]
          (try
            (let [result (sheet/execute ctx sheet-id {}
                                        :tick-id tick-id
                                        :timeout-ms 1000
                                        :result-grace-ms 100)
                  events (h/read-tick-events ctx tick-id)
                  claim-events
                  (into []
                        (es/read (:event-store ctx)
                                 {:tenant-id (:tenant-id ctx)
                                  :types #{:ontology/claim-deltas-recorded}}))]
              (is (= true (deref commit-appended 1000 ::commit-not-appended)))
              (is (= true
                     (deref commit-interrupted 500 ::commit-not-interrupted)))
              (is (= :success (:status result)) (pr-str result))
              (is (= "done" (get-in result [:outputs :summary])))
              (is (= 1 @provider-calls))
              (is (= 1 (count (filter #(= :ontology/task-classified
                                           (:event/type %))
                                      events))))
              (is (= 1 (count (filter #(= :ontology/claim-deltas-recorded
                                           (:event/type %))
                                      claim-events)))
                  "fresh-mint convergence evidence commits in the same batch")
              (is (not-any? #(= :rlm/researcher-classification-expired
                                (:event/type %))
                            events)))
            (finally
              (deliver release-commit-return true))))))))

(deftest det-e2e-267-and-280-campaign-classification-survives-yield-and-resume
  (testing "the first classification and campaign timing are carried into every resumed quantum"
    (let [campaign-started-at-ms 1000
          campaign-now-ms (constantly campaign-started-at-ms)]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :campaign-now-ms-fn campaign-now-ms}}]
        (let [classification-contexts (atom [])
              behavioral-classification-calls (atom 0)
              provider-calls (atom 0)
              provider-tasks (atom [])
              second-provider-entered (promise)
              release-second-provider (promise)
              tick-id (random-uuid)
              tree-id (random-uuid)
              definition
              (sheet/workflow "rr9-resumed-campaign-timing"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "yield once and retain the original clock"
                  :writes [:summary]
                  :max-iterations 2
                  :rlm {:recursive? true
                        :checkpointed? true
                        :auto-classify? true
                        :quantum {:max-iterations 1}
                        :timeouts {:classification-ms 500
                                   :provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 5000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
              classification-fn
              (fn [classify-ctx _]
                (swap! classification-contexts conj
                       (select-keys classify-ctx
                                    [:campaign-started-at-ms
                                     :campaign-deadline-ms
                                     :classification-deadline-ms
                                     :rerank-timeout-ms]))
                {:assigned-tree-id tree-id
                 :confidence 0.9
                 :top-candidates []
                 :ranked-candidates []
                 :reasoning "deterministic match"
                 :was-fresh-mint? false
                 :assigned-via :match
                 :outcome :matched})
              predict-fn
              (fn [_provider _module inputs _options]
                (swap! provider-tasks conj (:task inputs))
                (case (swap! provider-calls inc)
                  1 {:outputs {:code "(store! :memo \"kept\")"}
                     :reasoning "yield after durable progress"
                     :usage {:prompt_tokens 2
                             :completion_tokens 1
                             :total_tokens 3}}
                  2 (do
                      (deliver second-provider-entered true)
                      @release-second-provider
                      {:outputs {:code "(final! {:summary \"done\"})"}
                       :reasoning "finish after resume"
                       :usage {:prompt_tokens 2
                               :completion_tokens 1
                               :total_tokens 3}})))]
          (with-redefs [ontology/classify-task classification-fn
                        ontology/classify-behaviors
                        (fn [_ _]
                          (swap! behavioral-classification-calls inc)
                          {:behaviors []
                           :rerank-fallback? false
                           :outcome :matched})
                        llm/predict predict-fn]
            (let [execution (future
                              (sheet/execute ctx sheet-id {}
                                             :tick-id tick-id
                                             :timeout-ms 5000))]
              (try
                (is (= true
                       (deref second-provider-entered 3000
                              ::second-provider-not-entered)))
                (is (h/settle-until!
                     #(some? (:resume-state
                              (rm/get-researcher-resume-state
                               ctx sheet-id tick-id researcher-id)))
                     :timeout-ms 1000))
                (let [resume-state
                      (:resume-state
                       (rm/get-researcher-resume-state
                        ctx sheet-id tick-id researcher-id))
                      expected-timing
                      {:campaign-started-at-ms campaign-started-at-ms
                       :campaign-deadline-ms (+ campaign-started-at-ms 5000)}
                      expected-classification-context
                      {:tree-id tree-id
                       :r05-classifier
                       {:structural
                        {:assigned-tree-id tree-id
                         :confidence 0.9
                         :was-fresh-mint? false
                         :reasoning "deterministic match"
                         :top-candidates []
                         :rerank-fallback? false}
                        :behavioral
                        {:behaviors []
                         :rerank-fallback? false}}}]
                  (is (= expected-timing
                         (select-keys resume-state
                                      [:campaign-started-at-ms
                                       :campaign-deadline-ms]))
                      (pr-str resume-state))
                  (is (every?
                       #(= expected-timing
                           (select-keys % [:campaign-started-at-ms
                                           :campaign-deadline-ms]))
                       @classification-contexts)
                      (pr-str @classification-contexts))
                  (is (= 1 (count @classification-contexts))
                      "yield/resume does not re-run structural classification")
                  (is (= 1 @behavioral-classification-calls)
                      "yield/resume does not re-run behavioral classification")
                  (is (= expected-classification-context
                         (:classification-context resume-state))
                      (pr-str resume-state))
                  (is (= 2 (count @provider-tasks)))
                  (is (apply = @provider-tasks)
                      "every quantum receives the exact same rendered classification context")
                  (is (str/includes? (first @provider-tasks)
                                     "## Suggested patterns from corpus"))
                  (is (= {:started-at "1970-01-01T00:00:01Z"
                          :deadline "1970-01-01T00:00:06Z"}
                         (select-keys
                          (sheet/get-researcher-campaign
                           ctx tick-id researcher-id)
                          [:started-at :deadline]))))
                (deliver release-second-provider true)
                (let [result (deref execution 3000 ::execution-not-finished)]
                  (is (= :success (:status result)) (pr-str result))
                  (is (= "done" (get-in result [:outputs :summary]))
                      (pr-str result))
                  (is (= {:tree-id tree-id
                          :confidence 0.9
                          :top-candidates []
                          :was-fresh-mint? false}
                         (:auto-classification result))
                      (pr-str result))
                  (is (= 1
                         (count
                          (into []
                                (es/read
                                 (:event-store ctx)
                                 {:tenant-id (:tenant-id ctx)
                                  :types #{:ontology/task-classified}
                                  :tags #{[:tick tick-id]}}))))
                      "one yielded campaign contributes one assignment fact")
                  (is (= {:started-at "1970-01-01T00:00:01Z"
                          :deadline "1970-01-01T00:00:06Z"}
                         (select-keys
                          (sheet/get-researcher-campaign
                           ctx tick-id researcher-id)
                          [:started-at :deadline]))))
                (finally
                  (deliver release-second-provider true))))))))))

(deftest det-e2e-280-recovery-before-first-checkpoint-reuses-committed-classification
  (testing "the atomic classification outcome carries the payload across the pre-checkpoint crash window"
    (let [ctx (h/create-async-test-context
               {:context {:llm-provider :test
                          :campaign-now-ms-fn (constantly 1000)}})
          release-first-commit (promise)
          first-commit-appended (promise)
          second-classification-entered (promise)
          classify-calls (atom 0)
          commit-calls (atom 0)
          original-process-command cp/process-command
          tree-id (random-uuid)
          expected-classification-context
          {:tree-id tree-id
           :r05-classifier
           {:structural
            {:assigned-tree-id tree-id
             :confidence 0.9
             :was-fresh-mint? false
             :reasoning "classification call 1"
             :top-candidates []
             :rerank-fallback? false}
            :behavioral
            {:behaviors []
             :rerank-fallback? false}}}
          definition
          (sheet/workflow "rr18-pre-checkpoint-recovery"
            (sheet/blackboard {:summary :string})
            (sheet/repl-researcher "researcher"
              :instruction "finish after recovery"
              :writes [:summary]
              :max-iterations 1
              :rlm {:recursive? true
                    :auto-classify? true
                    :timeouts {:classification-ms 10000
                               :provider-ms 1000
                               :iteration-ms 2000
                               :campaign-ms 20000}}))
          sheet-id (sheet/build-workflow! ctx definition)
          node-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
          tick-id (random-uuid)
          execution (atom nil)]
      (try
        (with-redefs
          [ontology/classify-task
           (fn [& _]
             (let [call (swap! classify-calls inc)]
               (when (= 2 call)
                 (deliver second-classification-entered true))
               {:assigned-tree-id tree-id
                :confidence 0.9
                :top-candidates []
                :ranked-candidates []
                :reasoning (str "classification call " call)
                :was-fresh-mint? false
                :assigned-via :match
                :outcome :matched}))
           ontology/classify-behaviors
           (fn [& _]
             {:behaviors []
              :rerank-fallback? false
              :outcome :matched})
           llm/predict
           (fn [& _]
             {:outputs {:code "(final! {:summary \"recovered\"})"}
              :usage {:prompt_tokens 1
                      :completion_tokens 1
                      :total_tokens 2}})
           cp/process-command
           (fn [command-context]
             (if (= :sheet/commit-researcher-classification
                    (get-in command-context [:command :command/name]))
               (let [call (swap! commit-calls inc)
                     result (original-process-command command-context)]
                 (when (= 1 call)
                   (deliver first-commit-appended true)
                   @release-first-commit)
                 result)
               (original-process-command command-context)))]
          (reset! execution
                  (future
                    (sheet/execute ctx sheet-id {}
                                   :tick-id tick-id
                                   :timeout-ms 15000)))
          (is (= true
                 (deref first-commit-appended 3000
                        ::classification-not-committed)))
          (is (nil? (:resume-state
                     (rm/get-researcher-resume-state
                      ctx sheet-id tick-id node-id)))
              "the recovery seam is specifically before the first checkpoint")
          (let [recovery-results (runtime/resume-in-progress! ctx)
                classification-events
                (into []
                      (es/read (:event-store ctx)
                               {:tenant-id (:tenant-id ctx)
                                :types #{:ontology/task-classified}
                                :tags #{[:tick tick-id]}}))]
            (is (= 1 (count recovery-results)))
            (is (false?
                 (deref second-classification-entered 1500 false))
                "recovery must use the payload committed before any checkpoint")
            (is (= 1 @classify-calls))
            (is (= 1 (count classification-events)))
            (is (= expected-classification-context
                   (:classification-context (first classification-events)))
                (pr-str (first classification-events)))))
        (finally
          (deliver release-first-commit true)
          (when-let [running @execution]
            (deref running 5000 ::execution-not-finished))
          (h/stop-async-context ctx))))))

(deftest det-e2e-268-provider-and-tool-transports-receive-live-bounded-timeout
  (testing "the host sees the remaining budget at each real dispatch boundary"
    (let [campaign-clock (atom 1000)
          provider-options (atom nil)
          tool-dispatch (atom nil)]
      (h/with-async-test-context
        [ctx {:context
              {:llm-provider :test
               :campaign-now-ms-fn #(long @campaign-clock)
               :execution-now-ms-fn (constantly 1000)
               :tool-context {:orc/timeout-ms 999999
                              :consumer :kept}
               :call-tool-fn
               (fn [tool-name arguments tool-context]
                 (reset! tool-dispatch
                         {:tool-name tool-name
                          :arguments arguments
                          :tool-context tool-context})
                 {:value "bounded"})}}]
        (let [definition
              (sheet/workflow "rr9-live-transport-timeout"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "call the checkpoint-safe search tool"
                  :writes [:summary]
                  :mcp-tools ["search"]
                  :tool-contracts
                  {"search" {:arguments [:map]
                             :result :any
                             :checkpoint-safe? true}}
                  :max-iterations 1
                  :rlm {:recursive? false
                        :checkpointed? true
                        :timeouts {:provider-ms 5500
                                   :iteration-ms 6000
                                   :campaign-ms 6500}}))
              sheet-id (sheet/build-workflow! ctx definition)]
          (with-redefs
            [llm/predict
             (fn [_provider _module _inputs options]
               (reset! provider-options options)
               ;; The host tool must see the budget remaining at its own
               ;; dispatch, not the provider's earlier snapshot.
               (reset! campaign-clock 2200)
               {:outputs
                {:code
                 "(final! {:summary (get (search {:query \"budget\"}) :value)})"}
                :usage {:prompt_tokens 2
                        :completion_tokens 1
                        :total_tokens 3}})]
            (let [result (sheet/execute ctx sheet-id {} :timeout-ms 5000)
                  tool-context (:tool-context @tool-dispatch)]
              (is (= :success (:status result)) (pr-str result))
              (is (= "bounded" (get-in result [:outputs :summary])))
              (is (= 5000 (:timeout-ms @provider-options))
                  "the enclosing workflow is shorter than provider, iteration, and campaign")
              (is (= "search" (:tool-name @tool-dispatch)))
              (is (= {:query "budget"} (:arguments @tool-dispatch)))
              (is (string? (:orc/idempotency-key tool-context)))
              (is (= 3800 (:orc/timeout-ms tool-context))
                  "the tool receives the live workflow remainder after provider work")
              (is (= :kept (:consumer tool-context))
                  "ORC overwrites only its reserved timeout while preserving consumer context")
              (is (and (integer? (:orc/timeout-ms tool-context))
                       (pos? (:orc/timeout-ms tool-context)))
                  "a dispatched transport is never handed a zero/unbounded timeout"))))))))

(deftest det-e2e-268-expired-tool-is-rejected-before-claim-or-dispatch
  (let [executor-thread (atom nil)
        phase (atom :before-provider)
        provider-calls (atom 0)
        tool-calls (atom 0)
        campaign-now-ms
        (fn []
          (let [thread (Thread/currentThread)]
            (compare-and-set! executor-thread nil thread)
            (long
             (cond
               (= :before-provider @phase) 1000
               (identical? thread @executor-thread) 1200
               :else 1650))))]
    (h/with-async-test-context
      [ctx {:context
            {:llm-provider :test
             :campaign-now-ms-fn campaign-now-ms
             :call-tool-fn
             (fn [_tool-name _arguments _tool-context]
               (swap! tool-calls inc)
               {:value "must-not-run"})}}]
      (let [definition
            (sheet/workflow "rr9-expired-tool-transport"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "do not dispatch an expired tool"
                :writes [:summary]
                :mcp-tools ["search"]
                :tool-contracts
                {"search" {:arguments [:map]
                           :result :any
                           :checkpoint-safe? true}}
                :max-iterations 1
                :rlm {:recursive? false
                      :checkpointed? true
                      :iteration-retry {:max-attempts 1}
                      :timeouts {:provider-ms 600
                                 :iteration-ms 700
                                 :campaign-ms 650}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs
          [llm/predict
           (fn [& _]
             (swap! provider-calls inc)
             (reset! phase :after-provider)
             {:outputs
              {:code
               "(final! {:summary (get (search {:query \"expired\"}) :value)})"}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 10000)
                tool-claims
                (filter #(and (= :rlm/researcher-effect-claimed
                                 (:event/type %))
                              (= :tool (:kind %)))
                        (h/read-all-events ctx))]
            (is (= :timeout (:status result)) (pr-str result))
            (is (= 1 @provider-calls))
            (is (zero? @tool-calls)
                "an exhausted timeout must not be interpreted as unbounded")
            (is (empty? tool-claims)
                "deadline rejection precedes the durable external-effect claim")))))))

(deftest det-e2e-268-expired-provider-is-rejected-before-claim-or-dispatch
  (let [provider-calls (atom 0)]
    (h/with-async-test-context
      [ctx {:context {:llm-provider :test
                      :campaign-now-ms-fn (constantly 6000)
                      :execution-now-ms-fn (constantly 1000)}}]
      (let [definition
            (sheet/workflow "rr9-expired-provider-transport"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "do not dispatch an expired provider"
                :writes [:summary]
                :max-iterations 1
                :rlm {:recursive? false
                      :checkpointed? true
                      :iteration-retry {:max-attempts 1}
                      :timeouts {:provider-ms 5500
                                 :iteration-ms 6000
                                 :campaign-ms 6500}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs {:code "(final! {:summary \"late\"})"}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 5000)
                provider-claims
                (filter #(and (= :rlm/researcher-effect-claimed
                                 (:event/type %))
                              (= :provider (:kind %)))
                        (h/read-all-events ctx))]
            (is (= :timeout (:status result)) (pr-str result))
            (is (zero? @provider-calls)
                "an exhausted workflow deadline cannot fire the provider")
            (is (empty? provider-claims)
                "deadline rejection precedes the durable provider claim")))))))

(deftest det-e2e-269-completed-quantum-durations-and-running-maximum-are-durable
  (testing "whole ownership quanta are measured once and survive projection replay"
    (let [monotonic-ms (atom 100)]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :campaign-now-ms-fn (constantly 1000)
                        :researcher-monotonic-ms-fn #(long @monotonic-ms)}}]
        (let [provider-calls (atom 0)
              tick-id (random-uuid)
              definition
              (sheet/workflow "rr9-recorded-quantum-duration"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "complete two multi-step ownership quanta"
                  :writes [:summary]
                  :max-iterations 4
                  :rlm {:recursive? true
                        :checkpointed? true
                        :auto-classify? false
                        :quantum {:max-iterations 2}
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 10000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))]
          (with-redefs
            [llm/predict
             (fn [& _]
               (case (swap! provider-calls inc)
                 1 {:outputs {:code "(store! :first :done)"}}
                 2 (do (reset! monotonic-ms 150)
                       {:outputs {:code "(store! :second :done)"}})
                 3 {:outputs {:code "(store! :third :done)"}}
                 4 (do (reset! monotonic-ms 180)
                       {:outputs {:code "(final! {:summary \"done\"})"}})
                 (throw (ex-info "completed quantum replayed provider work" {}))))]
            (let [result (sheet/execute ctx sheet-id {}
                                        :tick-id tick-id
                                        :timeout-ms 5000)
                  tick-events (h/read-tick-events ctx tick-id)
                  resume-events
                  (filterv #(= :rlm/researcher-resume-state-saved
                               (:event/type %))
                           tick-events)
                  quantum-events
                  (filterv #(or (contains? (:resume-state %)
                                           :observed-quantum-duration-ms)
                                (contains? % :observed-quantum-duration-ms))
                           tick-events)
                  campaign (sheet/get-researcher-campaign
                            ctx tick-id researcher-id)
                  replayed-campaign
                  (get-in (reduce rm/researcher-campaigns* {} tick-events)
                          [tick-id :campaigns researcher-id])]
              (is (= :success (:status result)) (pr-str result))
              (is (= 4 @provider-calls))
              (is (= 4 (count resume-events))
                  "each iteration still has one immutable record and resume state")
              (is (= [:rlm/researcher-resume-state-saved
                      :sheet/node-execution-completed]
                     (mapv :event/type quantum-events))
                  "yielded and terminal boundaries each have one canonical durable fact")
              (is (= [50 30]
                     (mapv #(or (:observed-quantum-duration-ms %)
                                (get-in % [:resume-state
                                           :observed-quantum-duration-ms]))
                           quantum-events))
                  "a two-iteration quantum records one duration at its boundary")
              (is (= [50 50]
                     (mapv #(or (:max-observed-quantum-duration-ms %)
                                (get-in % [:resume-state
                                           :max-observed-quantum-duration-ms]))
                           quantum-events))
                  "a shorter later quantum cannot regress the running maximum")
              (is (= {:observed-quantum-duration-ms 30
                      :max-observed-quantum-duration-ms 50}
                     (select-keys campaign
                                  [:observed-quantum-duration-ms
                                   :max-observed-quantum-duration-ms])))
              (is (= (select-keys campaign
                                  [:observed-quantum-duration-ms
                                   :max-observed-quantum-duration-ms])
                     (select-keys replayed-campaign
                                  [:observed-quantum-duration-ms
                                   :max-observed-quantum-duration-ms]))
                  "replaying the raw event stream reconstructs the live projection"))))))))

(deftest det-e2e-269-command-boundary-preserves-the-running-quantum-maximum
  (testing "a live higher revision may advance the observation but cannot lower its maximum"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            command!
            (fn [command-name body]
              (cp/process-command
               (assoc ctx :command
                      (merge {:command/id (random-uuid)
                              :command/timestamp (time/now)
                              :command/name command-name}
                             body))))
            resume-state
            (fn [revision next-iteration observed running-max]
              {:version 2
               :revision revision
               :ownership-epoch 1
               :next-iteration next-iteration
               :sandbox-vars {}
               :var-creation-times {}
               :usage {:prompt-tokens 0
                       :completion-tokens 0
                       :total-tokens 0}
               :cumulative-tree-ms 0
               :iteration-attempts {}
               :campaign-started-at-ms 1000
               :campaign-deadline-ms 11000
               :observed-quantum-duration-ms observed
               :max-observed-quantum-duration-ms running-max})
            record
            (fn [iteration duration]
              {:iteration-index iteration
               :attempt-ordinal 0
               :status :success
               :started-at "1970-01-01T00:00:00.100Z"
               :completed-at "1970-01-01T00:00:00.150Z"
               :duration-ms duration
               :generated-code-recorded? false
               :emitted-tree-recorded? false})
            checkpoint!
            (fn [state iteration-record]
              (command! :sheet/checkpoint-researcher-iteration
                        {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :resume-state state
                         :iteration-record iteration-record
                         :resume? false
                         :inputs {}}))]
        (command! :sheet/emit-tick-started
                  {:sheet-id sheet-id :tick-id tick-id})
        (command! :sheet/claim-researcher-frontier
                  {:sheet-id sheet-id
                   :tick-id tick-id
                   :node-id node-id
                   :ownership-epoch 1
                   :claimed-at "1970-01-01T00:00:01Z"})
        (checkpoint! (resume-state 1 1 50 50) (record 0 50))
        (let [regression-state (resume-state 2 2 10 10)
              regression-command
              {:sheet-id sheet-id
               :tick-id tick-id
               :node-id node-id
               :resume-state regression-state
               :iteration-record (record 1 10)
               :resume? false
               :inputs {}}]
          (is (m/validate
               (schemas/commands :sheet/checkpoint-researcher-iteration)
               regression-command)
              "the lower maximum is schema-valid input, so the command owns monotonicity")
          (command! :sheet/checkpoint-researcher-iteration regression-command)
          (let [stored-state
                (:resume-state
                 (rm/get-researcher-resume-state
                  ctx sheet-id tick-id node-id))]
            (is (= 10 (:observed-quantum-duration-ms stored-state)))
            (is (= 50 (:max-observed-quantum-duration-ms stored-state))
                "the live command boundary preserves the prior durable maximum")))))))

(deftest det-e2e-269-terminal-campaign-fences-late-quantum-state
  (testing "a terminal tick cannot acquire newer iteration or resume evidence"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            command!
            (fn [command-name body]
              (cp/process-command
               (assoc ctx :command
                      (merge {:command/id (random-uuid)
                              :command/timestamp (time/now)
                              :command/name command-name}
                             body))))
            state
            (fn [revision next-iteration observed running-max]
              {:version 2
               :revision revision
               :ownership-epoch 1
               :next-iteration next-iteration
               :sandbox-vars {}
               :var-creation-times {}
               :usage {:prompt-tokens 0
                       :completion-tokens 0
                       :total-tokens 0}
               :cumulative-tree-ms 0
               :iteration-attempts {}
               :campaign-started-at-ms 1000
               :campaign-deadline-ms 11000
               :observed-quantum-duration-ms observed
               :max-observed-quantum-duration-ms running-max})
            record
            (fn [iteration duration]
              {:iteration-index iteration
               :attempt-ordinal 0
               :status :success
               :duration-ms duration
               :generated-code-recorded? false
               :emitted-tree-recorded? false})
            checkpoint!
            (fn [resume-state iteration-record]
              (command! :sheet/checkpoint-researcher-iteration
                        {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :resume-state resume-state
                         :iteration-record iteration-record
                         :resume? false
                         :inputs {}}))]
        (command! :sheet/emit-tick-started
                  {:sheet-id sheet-id :tick-id tick-id})
        (command! :sheet/claim-researcher-frontier
                  {:sheet-id sheet-id
                   :tick-id tick-id
                   :node-id node-id
                   :ownership-epoch 1
                   :claimed-at "1970-01-01T00:00:01Z"})
        (checkpoint! (state 1 1 50 50) (record 0 50))
        (command! :sheet/emit-tick-completed
                  {:sheet-id sheet-id
                   :tick-id tick-id
                   :root-status :success})
        (let [before-events (h/read-tick-events ctx tick-id)
              result (checkpoint! (state 2 2 10 10) (record 1 10))
              after-events (h/read-tick-events ctx tick-id)
              stored-state
              (:resume-state
               (rm/get-researcher-resume-state ctx sheet-id tick-id node-id))]
          (is (= :cognitect.anomalies/conflict
                 (:cognitect.anomalies/category result))
              (pr-str result))
          (is (= (count before-events) (count after-events))
              "the terminal fence rejects the entire iteration+resume batch")
          (is (= {:revision 1
                  :next-iteration 1
                  :observed-quantum-duration-ms 50
                  :max-observed-quantum-duration-ms 50}
                 (select-keys stored-state
                              [:revision
                               :next-iteration
                               :observed-quantum-duration-ms
                               :max-observed-quantum-duration-ms])))
          (is (= :abandoned
                 (:status (sheet/get-researcher-campaign
                           ctx tick-id node-id)))))))))

(deftest det-e2e-269-concurrent-higher-observation-fences-a-stale-lower-maximum
  (testing "the CAS compares the proposed maximum with the state current at append"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            original-read es/read
            stale-reader (atom nil)
            stale-read-completed (promise)
            release-stale (promise)
            command!
            (fn [command-name body]
              (cp/process-command
               (assoc ctx :command
                      (merge {:command/id (random-uuid)
                              :command/timestamp (time/now)
                              :command/name command-name}
                             body))))
            state
            (fn [revision next-iteration observed running-max]
              {:version 2
               :revision revision
               :ownership-epoch 1
               :next-iteration next-iteration
               :sandbox-vars {}
               :var-creation-times {}
               :usage {:prompt-tokens 0
                       :completion-tokens 0
                       :total-tokens 0}
               :cumulative-tree-ms 0
               :iteration-attempts {}
               :campaign-started-at-ms 1000
               :campaign-deadline-ms 11000
               :observed-quantum-duration-ms observed
               :max-observed-quantum-duration-ms running-max})
            record
            (fn [iteration duration]
              {:iteration-index iteration
               :attempt-ordinal 0
               :status :success
               :duration-ms duration
               :generated-code-recorded? false
               :emitted-tree-recorded? false})
            checkpoint!
            (fn [resume-state iteration-record]
              (command! :sheet/checkpoint-researcher-iteration
                        {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :resume-state resume-state
                         :iteration-record iteration-record
                         :resume? false
                         :inputs {}}))]
        (command! :sheet/emit-tick-started
                  {:sheet-id sheet-id :tick-id tick-id})
        (command! :sheet/claim-researcher-frontier
                  {:sheet-id sheet-id
                   :tick-id tick-id
                   :node-id node-id
                   :ownership-epoch 1
                   :claimed-at "1970-01-01T00:00:01Z"})
        (checkpoint! (state 1 1 50 50) (record 0 50))
        (with-redefs
          [es/read
           (fn [store options]
             (let [events (original-read store options)]
               (when (and (identical? (Thread/currentThread) @stale-reader)
                          (= #{:rlm/researcher-iteration-recorded
                               :rlm/researcher-resume-state-saved}
                             (:types options)))
                 (deliver stale-read-completed true)
                 @release-stale)
               events))]
          (let [stale-result
                (future
                  (reset! stale-reader (Thread/currentThread))
                  (checkpoint! (state 3 3 10 10) (record 2 10)))]
            (try
              (is (= true
                     (deref stale-read-completed 1000
                            ::stale-read-not-reached)))
              (let [winner-result
                    (checkpoint! (state 2 2 100 100) (record 1 100))]
                (is (nil? (:cognitect.anomalies/category winner-result))
                    (pr-str winner-result)))
              (deliver release-stale true)
              (let [loser-result (deref stale-result 1000 ::stale-not-finished)
                    resume-events
                    (filter #(= :rlm/researcher-resume-state-saved
                                (:event/type %))
                            (h/read-tick-events ctx tick-id))
                    stored-state
                    (:resume-state
                     (rm/get-researcher-resume-state
                      ctx sheet-id tick-id node-id))]
                (is (= :cognitect.anomalies/conflict
                       (:cognitect.anomalies/category loser-result))
                    (pr-str loser-result))
                (is (= [[1 50 50] [2 100 100]]
                       (mapv (fn [event]
                               (let [resume-state (:resume-state event)]
                                 [(:revision resume-state)
                                  (:observed-quantum-duration-ms resume-state)
                                  (:max-observed-quantum-duration-ms resume-state)]))
                             resume-events)))
                (is (= 100 (:max-observed-quantum-duration-ms stored-state))))
              (finally
                (deliver release-stale true)))))))))

(deftest det-e2e-269-max-only-rolling-states-cannot-erase-or-invent-evidence
  (testing "the command preserves a prior zero max and strips an ungrounded first max"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            command!
            (fn [command-name body]
              (cp/process-command
               (assoc ctx :command
                      (merge {:command/id (random-uuid)
                              :command/timestamp (time/now)
                              :command/name command-name}
                             body))))
            base-state
            (fn [revision next-iteration]
              {:version 2
               :revision revision
               :ownership-epoch 1
               :next-iteration next-iteration
               :sandbox-vars {}
               :var-creation-times {}
               :usage {:prompt-tokens 0
                       :completion-tokens 0
                       :total-tokens 0}
               :cumulative-tree-ms 0
               :iteration-attempts {}
               :campaign-started-at-ms 1000
               :campaign-deadline-ms 11000})
            record
            (fn [iteration]
              {:iteration-index iteration
               :attempt-ordinal 0
               :status :success
               :generated-code-recorded? false
               :emitted-tree-recorded? false})
            start!
            (fn [tick-id node-id]
              (command! :sheet/emit-tick-started
                        {:sheet-id sheet-id :tick-id tick-id})
              (command! :sheet/claim-researcher-frontier
                        {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :ownership-epoch 1
                         :claimed-at "1970-01-01T00:00:01Z"}))
            checkpoint!
            (fn [tick-id node-id resume-state iteration-record]
              (command! :sheet/checkpoint-researcher-iteration
                        {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :resume-state resume-state
                         :iteration-record iteration-record
                         :resume? false
                         :inputs {}}))
            zero-tick (random-uuid)
            zero-node (random-uuid)
            invented-tick (random-uuid)
            invented-node (random-uuid)]
        (start! zero-tick zero-node)
        (checkpoint! zero-tick zero-node
                     (assoc (base-state 1 1)
                            :observed-quantum-duration-ms 0
                            :max-observed-quantum-duration-ms 0)
                     (record 0))
        (checkpoint! zero-tick zero-node (base-state 2 2) (record 1))
        (let [stored
              (:resume-state
               (rm/get-researcher-resume-state
                ctx sheet-id zero-tick zero-node))]
          (is (not (contains? stored :observed-quantum-duration-ms)))
          (is (contains? stored :max-observed-quantum-duration-ms))
          (is (zero? (:max-observed-quantum-duration-ms stored))
              "an older writer cannot erase a real zero-duration maximum"))

        (start! invented-tick invented-node)
        (let [max-only (assoc (base-state 1 1)
                              :max-observed-quantum-duration-ms 999)
              command-body
              {:sheet-id sheet-id
               :tick-id invented-tick
               :node-id invented-node
               :resume-state max-only
               :iteration-record (record 0)
               :resume? false
               :inputs {}}]
          (is (m/validate
               (schemas/commands :sheet/checkpoint-researcher-iteration)
               command-body)
              "max-only is an additive rolling shape, so provenance belongs to the command")
          (command! :sheet/checkpoint-researcher-iteration command-body)
          (let [stored
                (:resume-state
                 (rm/get-researcher-resume-state
                  ctx sheet-id invented-tick invented-node))]
            (is (not (contains? stored :max-observed-quantum-duration-ms))
                "without prior observed evidence a max-only value is discarded")))))))

(deftest det-e2e-269-noncheckpointed-researcher-does-not-touch-the-quantum-clock
  (let [provider-calls (atom 0)]
    (h/with-async-test-context
      [ctx {:context {:llm-provider :test
                      :researcher-monotonic-ms-fn
                      #(throw (ex-info "legacy path must not read the quantum clock" {}))}}]
      (let [definition
            (sheet/workflow "rr9-noncheckpointed-quantum-clock-compatibility"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "finish without checkpoint measurement"
                :writes [:summary]
                :max-iterations 1
                :rlm {:recursive? false
                      :checkpointed? false}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs {:code "(final! {:summary \"legacy-ok\"})"}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 5000)
                events (h/read-tick-events ctx (:trace-id result))]
            (is (= :success (:status result))
                (pr-str {:result result
                         :provider-calls @provider-calls
                         :events (mapv #(select-keys
                                        % [:event/type :node-id :status :error])
                                       events)}))
            (is (= "legacy-ok" (get-in result [:outputs :summary])))
            (is (= 1 @provider-calls))))))))

(deftest det-e2e-269-terminal-failure-records-the-whole-ownership-quantum
  (testing "a terminal quantum includes preparation and completed iteration work"
    (let [monotonic-ms (atom 100)]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :campaign-now-ms-fn (constantly 1000)
                        :researcher-monotonic-ms-fn #(long @monotonic-ms)}}]
        (let [tick-id (random-uuid)
              definition
              (sheet/workflow "rr9-terminal-quantum-duration"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "exhaust the campaign after one stored value"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:recursive? true
                        :checkpointed? true
                        :auto-classify? false
                        :quantum {:max-iterations 2}
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 10000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))]
          (with-redefs
            [llm/predict
             (fn [& _]
               (reset! monotonic-ms 150)
               {:outputs {:code "(store! :evidence :durable)"}})]
            (let [result (sheet/execute ctx sheet-id {}
                                        :tick-id tick-id
                                        :timeout-ms 5000)
                  completion
                  (some #(when (and (= :sheet/node-execution-completed
                                       (:event/type %))
                                    (= researcher-id (:node-id %)))
                           %)
                        (h/read-tick-events ctx tick-id))
                  campaign (sheet/get-researcher-campaign
                            ctx tick-id researcher-id)]
              (is (= :failure (:status result)) (pr-str result))
              (is (= 50 (:observed-quantum-duration-ms completion))
                  "the terminal event is the one durable fact for this quantum")
              (is (= 50 (:max-observed-quantum-duration-ms completion)))
              (is (= {:observed-quantum-duration-ms 50
                      :max-observed-quantum-duration-ms 50}
                     (select-keys campaign
                                  [:observed-quantum-duration-ms
                                   :max-observed-quantum-duration-ms]))))))))))

(deftest det-e2e-269-unexpected-terminal-failure-is-measured-and-epoch-fenced
  (testing "an exception after researcher work uses the measured researcher terminal boundary"
    (let [monotonic-values (atom [100 150])
          monotonic-ms (fn []
                         (let [value (or (first @monotonic-values) 150)]
                           (swap! monotonic-values #(if (next %) (vec (next %)) %))
                           (long value)))]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :campaign-now-ms-fn (constantly 1000)
                        :researcher-monotonic-ms-fn monotonic-ms}}]
        (let [tick-id (random-uuid)
              definition
              (sheet/workflow "rr9-exceptional-terminal-quantum"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "finish before the injected post-executor fault"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:recursive? true
                        :checkpointed? true
                        :auto-classify? false
                        :quantum {:max-iterations 2}
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 10000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))]
          (with-redefs
            [llm/predict
             (fn [& _]
               {:outputs {:code "(final! {:summary \"done\"})"}})
             executor/validate-leaf-outputs
             (fn [& _]
               (throw (ex-info "post-executor validation failed" {})))]
            (let [result (sheet/execute ctx sheet-id {}
                                        :tick-id tick-id
                                        :timeout-ms 5000)
                  completion
                  (some #(when (and (= :sheet/node-execution-completed
                                       (:event/type %))
                                    (= researcher-id (:node-id %)))
                           %)
                        (h/read-tick-events ctx tick-id))
                  campaign (sheet/get-researcher-campaign
                            ctx tick-id researcher-id)]
              (is (= :failure (:status result)) (pr-str result))
              (is (= "post-executor validation failed" (:error result)))
              (is (= 1 (:researcher-ownership-epoch completion))
                  "unexpected researcher failure remains fenced by its live epoch")
              (is (= 50 (:observed-quantum-duration-ms completion)))
              (is (= 50 (:max-observed-quantum-duration-ms completion)))
              (is (= {:status :failure
                      :observed-quantum-duration-ms 50
                      :max-observed-quantum-duration-ms 50}
                     (select-keys campaign
                                  [:status
                                   :observed-quantum-duration-ms
                                   :max-observed-quantum-duration-ms]))))))))))

(deftest det-e2e-269-instrumentation-failure-does-not-bypass-the-epoch-fence
  (testing "an unknowable duration is omitted without falling back to an unfenced terminal"
    (let [clock-calls (atom 0)]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :campaign-now-ms-fn (constantly 1000)
                        :researcher-monotonic-ms-fn
                        (fn []
                          (if (= 1 (swap! clock-calls inc))
                            100
                            (throw (ex-info "terminal clock failed" {}))))}}]
        (let [tick-id (random-uuid)
              definition
              (sheet/workflow "rr9-failed-quantum-instrumentation"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "finish while terminal instrumentation fails"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:recursive? true
                        :checkpointed? true
                        :auto-classify? false
                        :quantum {:max-iterations 2}
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 10000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))]
          (with-redefs [llm/predict
                        (fn [& _]
                          {:outputs {:code "(final! {:summary \"done\"})"}})]
            (let [result (sheet/execute ctx sheet-id {}
                                        :tick-id tick-id
                                        :timeout-ms 5000)
                  completions
                  (filterv #(and (= :sheet/node-execution-completed
                                    (:event/type %))
                                 (= researcher-id (:node-id %)))
                           (h/read-tick-events ctx tick-id))
                  completion (first completions)
                  campaign (sheet/get-researcher-campaign
                            ctx tick-id researcher-id)]
              (is (= {:status :failure :error "terminal clock failed"}
                     (select-keys result [:status :error])))
              (is (= 1 (count completions)))
              (is (= {:status :failure
                      :error "terminal clock failed"
                      :researcher-ownership-epoch 1}
                     (select-keys completion
                                  [:status :error
                                   :researcher-ownership-epoch])))
              (is (not (contains? completion
                                  :observed-quantum-duration-ms))
                  "zero would be fabricated evidence when the end sample failed")
              (is (not (contains? completion
                                  :max-observed-quantum-duration-ms)))
              (is (= {:status :failure :ownership-epoch 1}
                     (select-keys campaign [:status :ownership-epoch])))
              (is (not (contains? campaign
                                  :observed-quantum-duration-ms)))
              (is (not (contains? campaign
                                  :max-observed-quantum-duration-ms))))))))))

(deftest det-e2e-269-pre-frontier-failure-cannot-terminalize-a-newer-owner
  (testing "a stale recovery worker that fails before its claim appends no terminal"
    (let [provider-entered (promise)
          release-provider (promise)
          stale-clock-called (promise)
          tick-id (random-uuid)]
      (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
        (let [definition
              (sheet/workflow "rr9-pre-frontier-failure-fence"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "hold the original owner while a stale worker fails"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:recursive? true
                        :checkpointed? true
                        :auto-classify? false
                        :quantum {:max-iterations 2}
                        :timeouts {:provider-ms 10000
                                   :iteration-ms 12000
                                   :campaign-ms 20000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
              command!
              (fn [command-name body]
                (cp/process-command
                 (assoc ctx :command
                        (merge {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name command-name}
                               body))))]
          (with-redefs [llm/predict
                        (fn [& _]
                          (deliver provider-entered true)
                          @release-provider
                          {:outputs {:code "(final! {:summary \"late\"})"}})]
            (let [execution
                  (future
                    (sheet/execute ctx sheet-id {}
                                   :tick-id tick-id
                                   :timeout-ms 5000))]
              (try
                (is (= true (deref provider-entered 5000 ::provider-not-entered)))
                (let [start-event
                      (first
                       (filter #(and (= :sheet/node-execution-started
                                        (:event/type %))
                                     (= researcher-id (:node-id %)))
                               (h/read-tick-events ctx tick-id)))
                      newer-claim
                      (command! :sheet/claim-researcher-frontier
                                {:sheet-id sheet-id
                                 :tick-id tick-id
                                 :node-id researcher-id
                                 :ownership-epoch 2
                                 :claimed-at "1970-01-01T00:00:02Z"})]
                  (is (some? start-event))
                  (is (nil? (:cognitect.anomalies/category newer-claim))
                      (pr-str newer-claim))
                  (todo/execute-repl-researcher-node
                   (assoc ctx
                          :event (assoc start-event
                                        :researcher-ownership-epoch 1)
                          :researcher-monotonic-ms-fn
                          (fn []
                            (deliver stale-clock-called true)
                            (throw (ex-info "start clock failed" {})))))
                  (is (= true
                         (deref stale-clock-called 2000
                                ::stale-clock-not-called)))
                  (is (not
                       (h/settle-until!
                        #(some (fn [event]
                                 (and (= :sheet/node-execution-completed
                                          (:event/type event))
                                      (= researcher-id (:node-id event))))
                               (h/read-tick-events ctx tick-id))
                        :timeout-ms 500))
                      "a worker that never owned the frontier cannot publish a terminal")
                  (is (= {:status :running :ownership-epoch 2}
                         (select-keys
                          (sheet/get-researcher-campaign
                           ctx tick-id researcher-id)
                          [:status :ownership-epoch]))))
                (finally
                  (sheet/cancel! ctx tick-id)
                  (deliver release-provider true)
                  (deref execution 3000 ::execution-not-settled))))))))))

(deftest det-e2e-269-frontier-command-failure-cas-cannot-terminalize-a-new-owner
  (testing "a delayed claim-error terminal is rejected after the frontier advances"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            command!
            (fn [command-name body]
              (cp/process-command
               (assoc ctx :command
                      (merge {:command/id (random-uuid)
                              :command/timestamp (time/now)
                              :command/name command-name}
                             body))))]
        (command! :sheet/emit-tick-started
                  {:sheet-id sheet-id :tick-id tick-id})
        (let [claim-result
              (command! :sheet/claim-researcher-frontier
                        {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :ownership-epoch 1
                         :claimed-at "1970-01-01T00:00:01Z"})]
          (is (nil? (:cognitect.anomalies/category claim-result))
              (pr-str claim-result)))
        (let [before-events (h/read-tick-events ctx tick-id)
              stale-failure
              (command! :sheet/fail-node-execution
                        {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :researcher-expected-frontier-epoch 0
                         :error "ambiguous frontier append failed"})
              after-events (h/read-tick-events ctx tick-id)]
          (is (= :cognitect.anomalies/conflict
                 (:cognitect.anomalies/category stale-failure))
              (pr-str stale-failure))
          (is (= (count before-events) (count after-events))
              "the rejected terminal appends no event")
          (is (empty?
               (filter #(and (= :sheet/node-execution-completed
                                (:event/type %))
                             (= node-id (:node-id %)))
                       after-events)))
          (is (= {:status :running :ownership-epoch 1}
                 (select-keys
                  (sheet/get-researcher-campaign ctx tick-id node-id)
                  [:status :ownership-epoch]))))))))

(deftest det-e2e-269-terminal-observation-is-fenced-by-the-live-frontier
  (testing "a stale owner cannot complete over a newer maximum"
    (h/with-async-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            command!
            (fn [command-name body]
              (cp/process-command
               (assoc ctx :command
                      (merge {:command/id (random-uuid)
                              :command/timestamp (time/now)
                              :command/name command-name}
                             body))))
            base-state
            {:version 2
             :revision 1
             :ownership-epoch 2
             :next-iteration 1
             :sandbox-vars {}
             :var-creation-times {}
             :usage {:prompt-tokens 0
                     :completion-tokens 0
                     :total-tokens 0}
             :cumulative-tree-ms 0
             :iteration-attempts {}
             :campaign-started-at-ms 1000
             :campaign-deadline-ms 11000
             :observed-quantum-duration-ms 100
             :max-observed-quantum-duration-ms 100}
            iteration-record
            {:iteration-index 0
             :attempt-ordinal 0
             :status :success
             :generated-code-recorded? false
             :emitted-tree-recorded? false}]
        (command! :sheet/emit-tick-started
                  {:sheet-id sheet-id :tick-id tick-id})
        (command! :sheet/claim-researcher-frontier
                  {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                   :ownership-epoch 1 :claimed-at "1970-01-01T00:00:01Z"})
        (command! :sheet/claim-researcher-frontier
                  {:sheet-id sheet-id :tick-id tick-id :node-id node-id
                   :ownership-epoch 2 :claimed-at "1970-01-01T00:00:02Z"})
        (command! :sheet/checkpoint-researcher-iteration
                  {:sheet-id sheet-id
                   :tick-id tick-id
                   :node-id node-id
                   :resume-state base-state
                   :iteration-record iteration-record
                   :resume? false
                   :inputs {}})
        (let [stale-result
              (command! :sheet/complete-node-execution
                        {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :node-type :repl-researcher
                         :researcher-ownership-epoch 1
                         :status :failure
                         :writes {}
                         :observed-quantum-duration-ms 10
                         :max-observed-quantum-duration-ms 10})]
          (is (= :cognitect.anomalies/conflict
                 (:cognitect.anomalies/category stale-result))
              (pr-str stale-result))
          (is (empty?
               (filter #(and (= :sheet/node-execution-completed
                                (:event/type %))
                             (= node-id (:node-id %)))
                       (h/read-tick-events ctx tick-id)))))

        (let [winner-result
              (command! :sheet/complete-node-execution
                        {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :node-type :repl-researcher
                         :researcher-ownership-epoch 2
                         :status :failure
                         :writes {}
                         :observed-quantum-duration-ms 10
                         :max-observed-quantum-duration-ms 10})
              completion
              (some #(when (and (= :sheet/node-execution-completed
                                   (:event/type %))
                                (= node-id (:node-id %)))
                       %)
                    (h/read-tick-events ctx tick-id))]
          (is (nil? (:cognitect.anomalies/category winner-result))
              (pr-str winner-result))
          (is (= 100 (:max-observed-quantum-duration-ms completion))
              "the command boundary canonicalizes against live durable evidence"))))))

(deftest det-e2e-269-sqlite-reopen-preserves-the-running-quantum-maximum
  (testing "a fresh runtime reads the maximum from reopened durable storage"
    (let [db-file (str "/tmp/rr9-quantum-duration-" (random-uuid) ".db")
          event-store-conn {:type :sqlite
                            :database-file db-file
                            :maximum-pool-size 2}
          first-context (atom nil)
          reopened-context (atom nil)
          monotonic-ms (atom 100)]
      (try
        (let [ctx (h/create-async-test-context
                   {:event-store-conn event-store-conn
                    :context {:llm-provider :test
                              :campaign-now-ms-fn (constantly 1000)
                              :researcher-monotonic-ms-fn
                              #(long @monotonic-ms)}})
              _ (reset! first-context ctx)
              tick-id (random-uuid)
              definition
              (sheet/workflow "rr9-reopened-quantum-maximum"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "record a terminal quantum before restart"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:recursive? true
                        :checkpointed? true
                        :auto-classify? false
                        :quantum {:max-iterations 2}
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 2000
                                   :campaign-ms 10000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))]
          (with-redefs [llm/predict
                        (fn [& _]
                          (reset! monotonic-ms 175)
                          {:outputs {:code "(store! :restart-proof :durable)"}})]
            (let [result (sheet/execute ctx sheet-id {}
                                        :tick-id tick-id
                                        :timeout-ms 5000)]
              (is (= :failure (:status result)) (pr-str result))
              (is (= 75
                     (:max-observed-quantum-duration-ms
                      (sheet/get-researcher-campaign
                       ctx tick-id researcher-id))))))

          ;; Close processors, pubsub, projection cache, and the SQLite store.
          ;; The next context is a real read-back boundary, not an in-process
          ;; reducer replay.
          (h/stop-async-context ctx)
          (reset! first-context nil)
          (let [reopened (h/create-async-test-context
                          {:event-store-conn event-store-conn})]
            (reset! reopened-context reopened)
            (is (h/settle-until!
                 #(= 75
                     (:max-observed-quantum-duration-ms
                      (sheet/get-researcher-campaign
                       reopened tick-id researcher-id)))
                 :timeout-ms 5000)
                "the reopened runtime rebuilds the maximum from durable facts")))
        (finally
          (when @reopened-context
            (h/stop-async-context @reopened-context))
          (when @first-context
            (h/stop-async-context @first-context))
          (doseq [suffix ["" "-wal" "-shm"]]
            (io/delete-file (str db-file suffix) true)))))))

(deftest det-e2e-270-initial-lease-loss-skips-the-checkpointed-quantum
  (testing "an initially lost live fence prevents frontier and provider work"
    (let [provider-calls (atom 0)
          foreign-provider-calls (atom 0)
          monitor-waits (atom 0)
          researcher-worker-finished (promise)
          tick-id (random-uuid)
          execution (atom nil)
          ctx (h/create-async-test-context
               {:context
                {:llm-provider :test
                 :lease-owned? (constantly false)
                 :researcher-lease-monitor-wait-fn
                 #(swap! monitor-waits inc)
                 :researcher-worker-finished-fn
                 #(deliver researcher-worker-finished true)}})]
      (try
        (let [definition
              (sheet/workflow "rr9-initial-lease-loss"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "initially lost ownership must skip this quantum"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:recursive? true
                        :checkpointed? true
                        :auto-classify? false
                        :quantum {:max-iterations 1}
                        :timeouts {:provider-ms 10000
                                   :iteration-ms 12000
                                   :campaign-ms 20000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))]
          (with-redefs
            [llm/predict
             (fn [_provider module _inputs _options]
               (if (str/includes? (str (:instructions module))
                                  "initially lost ownership must skip this quantum")
                 (do
                   (swap! provider-calls inc)
                   {:outputs {:code "(final! {:summary \"must-not-run\"})"}})
                 (do
                   (swap! foreign-provider-calls inc)
                   {:outputs {:code "(final! {:summary \"foreign-test-work\"})"}})))]
            (reset! execution
                    (future
                      (sheet/execute ctx sheet-id {}
                                     :tick-id tick-id
                                     :timeout-ms 15000)))
            (is (= true
                   (deref researcher-worker-finished 5000
                          ::researcher-worker-not-finished))
                "the lost worker exits through its lifecycle cleanup")
            (is (zero? @provider-calls)
                "initial ownership loss gates the provider")
            (is (zero? @monitor-waits)
                "an initially lost fence never enters the monitor wait loop")
            (is (not-any? #(and (= :rlm/researcher-frontier-claimed
                                   (:event/type %))
                                (= researcher-id (:node-id %)))
                          (h/read-tick-events ctx tick-id))
                "initial ownership loss gates the durable frontier")
            (is (zero? @foreign-provider-calls)
                (str "the initial-loss tracer observed "
                     @foreign-provider-calls
                     " unrelated provider call(s)"))))
        (finally
          (when-let [running @execution]
            (sheet/cancel! ctx tick-id)
            (execution-budget/cancel-active-work! tick-id)
            (when (= ::execution-not-settled
                     (deref running 5000 ::execution-not-settled))
              (throw
               (ex-info "Cancelled initial-loss execution did not settle"
                        {:tick-id tick-id}))))
          (h/stop-async-context ctx))))))

(deftest det-e2e-270-lease-monitor-compatibility-boundaries
  (testing "checkpointed standalone execution remains valid without a lease capability"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [definition
            (sheet/workflow "rr9-checkpointed-standalone-lease-compatibility"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "finish without a control-plane lease"
                :writes [:summary]
                :max-iterations 1
                :rlm {:recursive? true
                      :checkpointed? true
                      :auto-classify? false
                      :quantum {:max-iterations 1}
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 2000
                                 :campaign-ms 5000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        {:outputs
                         {:code "(final! {:summary \"standalone-ok\"})"}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 5000)]
            (is (= :success (:status result)) (pr-str result))
            (is (= "standalone-ok" (get-in result [:outputs :summary]))))))))

  (testing "non-checkpointed execution never consults lease-monitor capabilities"
    (let [lease-checks (atom 0)
          monitor-waits (atom 0)]
      (h/with-async-test-context
        [ctx {:context {:llm-provider :test
                        :lease-owned?
                        (fn []
                          (swap! lease-checks inc)
                          (throw (ex-info "legacy path read lease state" {})))
                        :researcher-lease-monitor-wait-fn
                        (fn []
                          (swap! monitor-waits inc)
                          (throw (ex-info "legacy path started lease monitor" {})))}}]
        (let [definition
              (sheet/workflow "rr9-noncheckpointed-lease-compatibility"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "finish through the legacy worker path"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:recursive? false
                        :checkpointed? false}))
              sheet-id (sheet/build-workflow! ctx definition)]
          (with-redefs [llm/predict
                        (fn [& _]
                          {:outputs
                           {:code "(final! {:summary \"legacy-ok\"})"}})]
            (let [result (sheet/execute ctx sheet-id {} :timeout-ms 5000)]
              (is (= :success (:status result)) (pr-str result))
              (is (= "legacy-ok" (get-in result [:outputs :summary])))
              (is (zero? @lease-checks))
              (is (zero? @monitor-waits)))))))))

(deftest det-e2e-270-noncooperative-stale-owner-loses-the-epoch-cas
  (testing "lease drain is not exclusivity; the durable frontier rejects a paused old append"
    (let [owner? (atom true)
          provider-calls (atom 0)
          foreign-provider-calls (atom 0)
          release-old-monitor (promise)
          release-new-monitor (promise)
          stale-completion-entered (promise)
          stale-completion-interrupted (promise)
          release-stale-completion (promise)
          stale-completion-result (promise)
          old-worker-finished (promise)
          new-worker-finished (promise)
          tick-id (random-uuid)
          original-process-command cp/process-command
          ctx (h/create-async-test-context
               {:context
                {:llm-provider :test
                 :lease-owned? #(true? @owner?)
                 :researcher-lease-monitor-wait-fn
                 (fn [] @release-old-monitor)
                 :researcher-worker-finished-fn
                 #(deliver old-worker-finished true)}})
          old-leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
          replacement-processor (atom nil)
          execution (atom nil)]
      (try
        (let [definition
              (sheet/workflow "rr9-stale-owner-epoch-cas"
                (sheet/blackboard {:summary :string})
                (sheet/repl-researcher "researcher"
                  :instruction "let recovery own the durable result"
                  :writes [:summary]
                  :max-iterations 1
                  :rlm {:recursive? true
                        :checkpointed? true
                        :auto-classify? false
                        :quantum {:max-iterations 1}
                        :timeouts {:provider-ms 10000
                                   :iteration-ms 12000
                                   :campaign-ms 20000}}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (sheet/get-nodes-for-sheet ctx sheet-id)))
              {:keys [handler-fn topics]}
              (get @tp/processor-registry* :sheet/execute-leaf-node)]
          (with-redefs
            [llm/predict
             (fn [_provider module _inputs _options]
               (if (str/includes? (str (:instructions module))
                                  "let recovery own the durable result")
                 (case (swap! provider-calls inc)
                   1 {:outputs
                      {:code "(final! {:summary \"stale-owner\"})"}}
                   2 {:outputs
                      {:code "(final! {:summary \"new-owner\"})"}})
                 (do
                   (swap! foreign-provider-calls inc)
                   {:outputs
                    {:code "(final! {:summary \"foreign-test-work\"})"}})))
             cp/process-command
             (fn [command-context]
               (let [command (:command command-context)
                     stale-provider-completion?
                     (and (= :sheet/complete-researcher-effect
                             (:command/name command))
                          (= tick-id (:tick-id command))
                          (= researcher-id (:node-id command))
                          (= 1 (:ownership-epoch command)))]
                 (when stale-provider-completion?
                   (deliver stale-completion-entered true)
                   (loop []
                     (when-not
                      (try
                        @release-stale-completion
                        true
                        (catch InterruptedException _
                          (deliver stale-completion-interrupted true)
                          false))
                       (recur))))
                 (let [result (original-process-command command-context)]
                   (when stale-provider-completion?
                     (deliver stale-completion-result result))
                   result)))]
            (reset! execution
                    (future
                      (sheet/execute ctx sheet-id {}
                                     :tick-id tick-id
                                     :timeout-ms 15000)))
            (is (= true
                   (deref stale-completion-entered 10000
                          ::stale-completion-not-entered))
                "the old owner is paused after provider return but before its durable completion")

            ;; Prevent the original leaf subscription from consuming the
            ;; recovery event; a fresh processor represents the new owner.
            (tp/stop old-leaf-processor)
            (reset! owner? false)
            (deliver release-old-monitor true)
            (is (= true
                   (deref stale-completion-interrupted 2000
                          ::stale-completion-not-interrupted))
                "the old worker observed cancellation but deliberately ignored it")

            (reset! replacement-processor
                    (tp/start
                     {:event-pubsub (:event-pubsub ctx)
                      :topics topics
                      :handler-fn handler-fn
                      :context
                      (assoc (dissoc ctx :processors)
                             :lease-owned? (constantly true)
                             :researcher-lease-monitor-wait-fn
                             (fn [] @release-new-monitor)
                             :researcher-worker-finished-fn
                             #(deliver new-worker-finished true))}))
            (let [recovery (sheet/resume-in-progress! ctx)]
              (is (= [{:resumed? true
                       :researcher-ownership-epoch 2}]
                     (mapv #(select-keys
                             % [:resumed? :researcher-ownership-epoch])
                           recovery))
                  (pr-str recovery)))

            (let [result (deref @execution 5000 ::execution-not-settled)]
              (is (= :success (:status result)) (pr-str result))
              (is (= "new-owner" (get-in result [:outputs :summary])))
              (is (= true
                     (deref new-worker-finished 2000
                            ::new-worker-not-finished)))
              (is (= 2 @provider-calls))
              (let [campaign-before
                    (select-keys
                     (sheet/get-researcher-campaign
                      ctx tick-id researcher-id)
                     [:status :ownership-epoch])
                    guarded-events
                    (fn []
                      (filterv
                       #(contains?
                         #{:rlm/researcher-frontier-claimed
                           :rlm/researcher-effect-claimed
                           :rlm/researcher-effect-completed
                           :sheet/node-execution-completed}
                         (:event/type %))
                       (h/read-tick-events ctx tick-id)))
                    events-before (guarded-events)]
                (is (= {:status :success :ownership-epoch 2}
                       campaign-before))
                (deliver release-stale-completion true)
                (let [stale-result
                      (deref stale-completion-result 2000
                             ::stale-completion-not-finished)]
                  (is (= :cognitect.anomalies/conflict
                         (:cognitect.anomalies/category stale-result))
                      (pr-str stale-result)))
                (is (= true
                       (deref old-worker-finished 2000
                              ::old-worker-not-finished)))
                (is (zero? @foreign-provider-calls)
                    (str "the stale-owner tracer observed "
                         @foreign-provider-calls
                         " unrelated provider call(s)"))
                (is (= events-before (guarded-events))
                    "the rejected old completion appends no guarded event")
                (is (= campaign-before
                       (select-keys
                        (sheet/get-researcher-campaign
                         ctx tick-id researcher-id)
                        [:status :ownership-epoch]))
                    "the winning campaign projection is unchanged")))))
        (finally
          (reset! owner? true)
          (deliver release-old-monitor true)
          (deliver release-new-monitor true)
          (deliver release-stale-completion true)
          (when (and @execution (not (realized? @execution)))
            (sheet/cancel! ctx tick-id))
          (execution-budget/cancel-active-work! tick-id)
          (when-let [running @execution]
            (when (= ::execution-not-settled
                     (deref running 5000 ::execution-not-settled))
              (throw
               (ex-info "Stale-owner execution did not settle"
                        {:tick-id tick-id}))))
          (when-let [started @replacement-processor]
            (tp/stop started))
          (h/stop-async-context ctx))))))
