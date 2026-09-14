(ns ai.obney.orc.orc-service.checkpointing-default-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]))

(def ^:private nondeterministic-event-keys
  #{:event/id :event/timestamp
    :started-at :completed-at :recorded-at :saved-at :claimed-at
    :emitted-at :generated-at :checkpointed-at :resolved-at :at
    :duration-ms :observed-quantum-duration-ms
    :max-observed-quantum-duration-ms :provider-latency-ms
    :campaign-started-at-ms :campaign-deadline-ms :execution-deadline-ms
    :action-id :logical-action-identity :attempt-identity})

(defn- canonical-event-value [value]
  (cond
    (uuid? value) ::uuid
    (inst? value) ::instant
    (map? value)
    (let [canonical
          (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                (keep (fn [[k v]]
                        (when-not (contains? nondeterministic-event-keys k)
                          [(canonical-event-value k)
                           (canonical-event-value v)])))
                value)]
      (cond-> canonical
        (contains? canonical :sheet-name)
        (assoc :sheet-name ::sheet-name)

        (and (= :repl-researcher (:type canonical))
             (map? (:rlm canonical)))
        (assoc-in [:rlm :checkpointed?] true)))
    (set? value) (->> value (map canonical-event-value) (sort-by pr-str) vec)
    (vector? value) (mapv canonical-event-value value)
    (sequential? value) (mapv canonical-event-value value)
    :else value))

(defn- canonical-event-bytes [events]
  (alength (.getBytes (pr-str events)
                      java.nio.charset.StandardCharsets/UTF_8)))

(deftest det-e2e-277-recursive-researchers-checkpoint-by-default
  (testing "an omitted checkpoint flag yields after one durable quantum"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            definition
            (sheet/workflow "rr15-default-checkpoint-quantum"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "checkpoint once, then finish"
                :writes [:summary]
                :max-iterations 2
                :rlm {:timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id
            (:id (first (filter #(= :repl-researcher (:type %))
                                (sheet/get-nodes-for-sheet ctx sheet-id))))]
        (with-redefs [llm/predict
                      (fn [& _]
                        (case (swap! calls inc)
                          1 {:outputs {:code "(store! :memo \"durable\")"}
                             :reasoning "complete the first bounded quantum"
                             :usage {:prompt_tokens 2
                                     :completion_tokens 1
                                     :total_tokens 3}}
                          2 {:outputs
                             {:code
                              "(final! {:summary (get-var :memo)})"}
                             :reasoning "finish from the durable frontier"
                             :usage {:prompt_tokens 2
                                     :completion_tokens 1
                                     :total_tokens 3}}
                          (throw (ex-info "RR15 fixture exceeded two turns" {}))))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                tick-id (:trace-id result)
                records (rm/get-researcher-iteration-records
                         ctx sheet-id tick-id researcher-id)
                events (h/read-tick-events ctx tick-id)
                resume-starts
                (filter #(and (= :sheet/node-execution-started (:event/type %))
                              (= researcher-id (:node-id %))
                              (:researcher-resume? %))
                        events)]
            (is (= :success (:status result)) (pr-str result))
            (is (= "durable" (get-in result [:outputs :summary]))
                (pr-str result))
            (is (= 2 @calls))
            (is (= [0 1] (mapv :iteration-index records))
                (pr-str records))
            (is (= 1 (count resume-starts))
                "the first default quantum durably yields before iteration two")))))))

(deftest det-e2e-277-default-campaign-resumes-after-sqlite-reopen
  (testing "a fresh runtime resumes the exact default-on durable frontier"
    (let [db-file (str "/tmp/rr15-default-reopen-" (random-uuid) ".db")
          event-store-conn {:type :sqlite
                            :database-file db-file
                            :maximum-pool-size 2}
          owned? (atom true)
          second-turn-entered (promise)
          first-worker-finished (promise)
          first-context (atom nil)
          reopened-context (atom nil)
          execute-future (atom nil)
          calls (atom 0)
          sheet-id-ref (atom nil)
          tick-id (random-uuid)
          researcher-id-ref (atom nil)]
      (try
        (with-redefs
          [llm/predict
           (fn [& _]
             (case (swap! calls inc)
               1 {:outputs {:code "(store! :memo \"survived-reopen\")"}
                  :reasoning "finish one durable iteration before restart"
                  :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}}
               2 (do
                   (deliver second-turn-entered true)
                   (Thread/sleep 10000)
                   {:outputs {:code "(final! {:summary \"stale\"})"}})
               3 {:outputs
                  {:code "(final! {:summary (get-var :memo)})"}
                  :reasoning "finish after reopening the durable frontier"
                  :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}}
               (throw (ex-info "RR15 restart fixture exceeded three turns" {}))))]
          (let [ctx
                (h/create-async-test-context
                 {:context {:llm-provider :test
                            :lease-owned? (fn [] @owned?)
                            :researcher-lease-monitor-wait-fn
                            #(Thread/sleep 10)
                            :researcher-worker-finished-fn
                            #(deliver first-worker-finished true)}
                  :event-store-conn event-store-conn})
                _ (reset! first-context ctx)
                definition
                (sheet/workflow "rr15-default-sqlite-reopen"
                  (sheet/blackboard {:summary :string})
                  (sheet/repl-researcher "researcher"
                    :instruction "checkpoint before restart, then finish"
                    :writes [:summary]
                    :max-iterations 3
                    :rlm {:timeouts {:provider-ms 20000
                                     :iteration-ms 25000
                                     :campaign-ms 60000}}))
                sheet-id (sheet/build-workflow! ctx definition)
                researcher-id
                (:id (first (filter #(= :repl-researcher (:type %))
                                    (sheet/get-nodes-for-sheet ctx sheet-id))))]
            (reset! sheet-id-ref sheet-id)
            (reset! researcher-id-ref researcher-id)
            (reset! execute-future
                    (future (sheet/execute ctx sheet-id {} :tick-id tick-id
                                           :timeout-ms 60000)))
            (is (h/settle-until!
                 #(= [0]
                     (mapv :iteration-index
                           (rm/get-researcher-iteration-records
                            ctx sheet-id tick-id researcher-id)))
                 :timeout-ms 10000)
                "iteration zero reaches the durable store before restart")
            (is (= true (deref second-turn-entered 10000 ::not-entered))
                "the next quantum is interrupted at the process boundary")
            (reset! owned? false)
            (is (= true (deref first-worker-finished 10000 ::not-finished))
                "the old owner drains before its store closes")
            (future-cancel @execute-future)
            (h/stop-async-context ctx)
            (reset! first-context nil)

            (let [reopened
                  (h/create-async-test-context
                   {:context {:llm-provider :test
                              :lease-owned? (constantly true)}
                    :event-store-conn event-store-conn})]
              (reset! reopened-context reopened)
              (let [scan (sheet/resume-in-progress! reopened)]
                (is (= 1 (count (filter :resumed? scan))) (pr-str scan)))
              (is (h/settle-until!
                   #(some? (runtime/durable-terminal-result reopened tick-id))
                   :timeout-ms 15000)
                  "the reopened runtime reaches a durable terminal result")
              (let [result (runtime/durable-terminal-result reopened tick-id)
                    records (rm/get-researcher-iteration-records
                             reopened sheet-id tick-id researcher-id)
                    events (into []
                                 (es/read (:event-store reopened)
                                          {:tenant-id (:tenant-id reopened)
                                           :tags #{[:tick tick-id]}}))
                    recovery-starts
                    (filter #(and (= :sheet/node-execution-started
                                     (:event/type %))
                                  (= researcher-id (:node-id %))
                                  (:resumed-from-event-id %))
                            events)]
                (is (= :success (:status result)) (pr-str result))
                (is (= "survived-reopen" (get-in result [:outputs :summary]))
                    (pr-str result))
                (is (= tick-id (:trace-id result)))
                (is (= [0 1] (mapv :iteration-index records))
                    "the completed iteration is not repeated")
                (is (= 1 (count (filter #(= 0 (:iteration-index %)) records))))
                (is (= 1 (count recovery-starts)))
                (is (= 3 @calls)
                    "one interrupted in-flight turn is retried; the completed turn is not")))))
        (finally
          (when-let [f @execute-future]
            (future-cancel f))
          (when-let [ctx @reopened-context]
            (h/stop-async-context ctx))
          (when-let [ctx @first-context]
            (h/stop-async-context ctx))
          (io/delete-file db-file true))))))

(deftest det-e2e-277-explicit-false-preserves-the-legacy-path
  (testing "the compatibility escape hatch remains one non-checkpointed invocation"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            definition
            (sheet/workflow "rr15-explicit-legacy-compatibility"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "run both recursive turns without checkpointing"
                :writes [:summary]
                :max-iterations 2
                :rlm {:checkpointed? false
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id
            (:id (first (filter #(= :repl-researcher (:type %))
                                (sheet/get-nodes-for-sheet ctx sheet-id))))]
        (with-redefs [llm/predict
                      (fn [& _]
                        (case (swap! calls inc)
                          1 {:outputs {:code "(store! :memo \"legacy\")"}
                             :reasoning "continue inside the same invocation"
                             :usage {:prompt_tokens 2
                                     :completion_tokens 1
                                     :total_tokens 3}}
                          2 {:outputs
                             {:code
                              "(final! {:summary (get-var :memo)})"}
                             :reasoning "finish the legacy invocation"
                             :usage {:prompt_tokens 2
                                     :completion_tokens 1
                                     :total_tokens 3}}
                          (throw (ex-info "RR15 legacy fixture exceeded two turns" {}))))]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                events (h/read-tick-events ctx (:trace-id result))
                checkpoint-types
                #{:rlm/researcher-frontier-claimed
                  :rlm/researcher-iteration-recorded
                  :rlm/researcher-resume-state-saved
                  :rlm/researcher-checkpointed}
                researcher-starts
                (filter #(and (= :sheet/node-execution-started (:event/type %))
                              (= researcher-id (:node-id %)))
                        events)
                researcher-completions
                (filter #(and (= :sheet/node-execution-completed (:event/type %))
                              (= researcher-id (:node-id %)))
                        events)]
            (is (= :success (:status result)) (pr-str result))
            (is (= "legacy" (get-in result [:outputs :summary]))
                (pr-str result))
            (is (= 2 @calls))
            (is (= 1 (count researcher-starts))
                "legacy recursion stays inside one node invocation")
            (is (= 1 (count researcher-completions)))
            (is (not-any? #(contains? checkpoint-types (:event/type %)) events)
                (pr-str (mapv :event/type events)))
            (is (nil? (rm/get-researcher-campaign
                       ctx (:trace-id result) researcher-id))
                "the legacy event stream does not create a durable campaign")))))))

(deftest det-e2e-277-terminal-mode-is-not-silently-redefined
  (testing "omitting checkpoint configuration does not change terminal mode"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [definition
            (sheet/workflow "rr15-terminal-mode-compatibility"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "finish through the terminal compatibility mode"
                :writes [:summary]
                :max-iterations 1
                :rlm {:recursive? false}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id
            (:id (first (filter #(= :repl-researcher (:type %))
                                (sheet/get-nodes-for-sheet ctx sheet-id))))]
        (with-redefs [llm/predict
                      (fn [& _]
                        {:outputs {:code "(final! {:summary \"terminal\"})"}})]
          (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                events (h/read-tick-events ctx (:trace-id result))]
            (is (= :success (:status result)) (pr-str result))
            (is (= "terminal" (get-in result [:outputs :summary]))
                (pr-str result))
            (is (= 1
                   (count
                    (filter #(and (= :sheet/node-execution-started
                                     (:event/type %))
                                  (= researcher-id (:node-id %)))
                            events))))
            (is (not-any?
                 #(contains? #{:rlm/researcher-frontier-claimed
                               :rlm/researcher-iteration-recorded
                               :rlm/researcher-resume-state-saved}
                             (:event/type %))
                 events))))))))

(deftest det-e2e-277-omitted-default-costs-the-same-as-explicit-true
  (testing "default selection adds no durable write to the qualified mechanism"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [measure
            (fn [workflow-name rlm-config]
              (let [calls (atom 0)
                    definition
                    (sheet/workflow workflow-name
                      (sheet/blackboard {:summary :string})
                      (sheet/repl-researcher "researcher"
                        :instruction "checkpoint once, then finish"
                        :writes [:summary]
                        :max-iterations 2
                        :rlm (merge
                              {:timeouts {:provider-ms 1000
                                          :iteration-ms 3000
                                          :campaign-ms 15000}}
                              rlm-config)))
                    sheet-id (sheet/build-workflow! ctx definition)]
                (with-redefs
                  [llm/predict
                   (fn [& _]
                     (case (swap! calls inc)
                       1 {:outputs {:code "(store! :memo \"same\")"}
                          :reasoning "same first durable turn"
                          :usage {:prompt_tokens 2
                                  :completion_tokens 1
                                  :total_tokens 3}}
                       2 {:outputs
                          {:code "(final! {:summary (get-var :memo)})"}
                          :reasoning "same terminal durable turn"
                          :usage {:prompt_tokens 2
                                  :completion_tokens 1
                                  :total_tokens 3}}
                       (throw (ex-info "RR15 cost fixture exceeded two turns" {}))))]
                  (let [result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                        tick-id (:trace-id result)]
                    (is (= :success (:status result)) (pr-str result))
                    (is (= "same" (get-in result [:outputs :summary]))
                        (pr-str result))
                    (is (= 2 @calls))
                    (is (h/settle-until! #(h/trace-stored? ctx tick-id)))
                    (let [events (h/read-tick-events ctx tick-id)
                          canonical-events
                          (->> events
                               (map canonical-event-value)
                               (sort-by pr-str)
                               vec)]
                      {:event-count (count events)
                       :raw-event-bytes (reduce + (map h/event-bytes events))
                       :event-type-counts (frequencies (map :event/type events))
                       :canonical-bytes (canonical-event-bytes canonical-events)})))))
            omitted (measure "rr15-cost-omitted" {})
            explicit (measure "rr15-cost-explicit" {:checkpointed? true})]
        (println "[RR15 durable cost]"
                 (pr-str {:omitted omitted :explicit explicit}))
        (is (= (:event-type-counts explicit)
               (:event-type-counts omitted))
            (pr-str {:omitted (:event-type-counts omitted)
                     :explicit (:event-type-counts explicit)}))
        (is (= (:event-count explicit) (:event-count omitted))
            (pr-str {:omitted (:event-count omitted)
                     :explicit (:event-count explicit)}))
        (is (<= (:canonical-bytes omitted) (:canonical-bytes explicit))
            "omitting the flag adds neither a durable event nor authoring metadata")
        (is (<= (- (:canonical-bytes explicit) (:canonical-bytes omitted)) 64)
            "the only canonical byte difference is the small explicit config key")
        (is (pos? (:canonical-bytes omitted))
            (pr-str {:omitted (:canonical-bytes omitted)
                     :explicit (:canonical-bytes explicit)}))
        (is (<= (:raw-event-bytes omitted) (:raw-event-bytes explicit))
            (pr-str {:omitted (:raw-event-bytes omitted)
                     :explicit (:raw-event-bytes explicit)}))))))
