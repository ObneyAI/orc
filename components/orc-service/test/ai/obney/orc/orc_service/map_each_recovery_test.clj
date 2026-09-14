(ns ai.obney.orc.orc-service.map-each-recovery-test
  "DET-E2E-274: durable map-each recovery across lost process coordination."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.todo-processors :as todo-processors]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def recovery-state (atom nil))
(def partial-recovery-state (atom nil))
(def terminal-recovery-calls (atom []))
(def nil-recovery-calls (atom 0))
(def generated-source-recovery-state (atom nil))
(def overlapping-recovery-state (atom nil))

(defn stop-after-first-item [{:keys [inputs]}]
  (let [{:keys [calls leaf-processor]} @recovery-state
        item (:item inputs)
        call-number (count (swap! calls conj item))]
    (when (= 1 call-number)
      (tp/stop leaf-processor))
    {:item (* 10 item)}))

(defn fail-second-item [{:keys [inputs]}]
  (let [{:keys [calls]} @partial-recovery-state
        item (:item inputs)]
    (swap! calls conj item)
    (if (= 2 item)
      (throw (ex-info "item 2 failed" {:item item}))
      {:item (* 10 item)})))

(defn record-terminal-item [{:keys [inputs]}]
  (let [item (:item inputs)]
    (swap! terminal-recovery-calls conj item)
    {:item (* 10 item)}))

(defn return-source-only [_]
  (swap! nil-recovery-calls inc)
  {:items :ignored})

(defn produce-items [_]
  {:items [1 2 3]})

(defn stop-after-first-generated-item [{:keys [inputs]}]
  (let [{:keys [calls leaf-processor]} @generated-source-recovery-state
        item (:item inputs)
        call-number (count (swap! calls conj item))]
    (when (= 1 call-number)
      (tp/stop leaf-processor))
    ;; Deliberately overwrite the source key. Recovery must use the immutable
    ;; source captured by the map parent's original start, not latest state.
    {:item (* 10 item)
     :items :mutated-by-child}))

(defn coordinate-overlapping-recovery [{:keys [inputs]}]
  (let [{:keys [calls worker stop-item block-item third-entered allow-third]}
        @overlapping-recovery-state
        item (:item inputs)]
    (when (= block-item item)
      (deliver third-entered true)
      @allow-third)
    (swap! calls conj item)
    (when (= stop-item item)
      (tp/stop @worker))
    {:item (* 10 item)}))

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.map-each-recovery-test/" function-name))

(defn- tick-events [ctx tick-id]
  (into [] (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx)
                     :tags #{[:tick tick-id]}})))

(defn- child-starts [ctx tick-id child-id]
  (filterv #(and (= :sheet/node-execution-started (:event/type %))
                 (= child-id (:node-id %)))
           (tick-events ctx tick-id)))

(defn- map-parent-completion [ctx tick-id parent-id]
  (some #(when (and (= :sheet/node-execution-completed (:event/type %))
                    (= parent-id (:node-id %)))
           %)
        (tick-events ctx tick-id)))

(defn- discard-process-local-map-coordinators! []
  ;; A processor-only restart in this JVM would preserve the defonce atom and
  ;; fake the process-loss boundary. Resolve the private cache solely to model
  ;; a fresh process; every behavioral assertion remains on public/durable data.
  (let [state-var (ns-resolve
                   'ai.obney.orc.orc-service.core.todo-processors
                   'map-each-state)]
    (is (some? state-var) "the restart harness must locate the ephemeral cache")
    (reset! (var-get state-var) {}))
  (let [seed-var (ns-resolve 'ai.obney.orc.orc-service.core.value-log
                             'tick-seeds*)]
    (is (some? seed-var) "the restart harness must locate the tick-seed cache")
    (reset! (var-get seed-var) {})))

(deftest det-e2e-274-restart-rejoins-survivors-and-dispatches-only-pending-items
  (testing "a lost map coordinator is rebuilt from durable item execution contexts"
    (h/with-async-test-context [ctx]
      (let [calls (atom [])
            leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
            workflow (sheet/workflow "det-e2e-274-map-rejoin"
                       (sheet/blackboard {:items [:vector :int]
                                          :item :int
                                          :results [:vector :int]})
                       (sheet/map-each "recoverable-map"
                         :from :items :as :item :into :results :parallel 1
                         (sheet/code "multiply" :fn (fq "stop-after-first-item")
                           :reads [:item] :writes [:item])))
            sheet-id (sheet/build-workflow! ctx workflow)
            nodes-by-name (into {} (map (juxt :name identity)
                                        (sheet/get-nodes-for-sheet ctx sheet-id)))
            child-id (get-in nodes-by-name ["multiply" :id])]
        (reset! recovery-state {:calls calls :leaf-processor leaf-processor})
        (let [{:keys [tick-id result]} (sheet/execute-stream
                                        ctx sheet-id {:items [1 2 3]}
                                        :timeout-ms 10000)]
          (try
            (is (h/settle-until!
                 #(let [events (tick-events ctx tick-id)]
                    (and (= 1 (count (filter (fn [event]
                                               (and (= :sheet/node-execution-completed
                                                       (:event/type event))
                                                    (= child-id (:node-id event))))
                                             events)))
                         (= 2 (count (child-starts ctx tick-id child-id)))))
                 :timeout-ms 5000)
                "crash boundary has one durable survivor and one pending start")
            (is (= [1] @calls) "the completed item ran exactly once before restart")
            (discard-process-local-map-coordinators!)
            (let [{:keys [handler-fn topics]} (get @tp/processor-registry*
                                                   :sheet/execute-leaf-node)
                  restarted (tp/start {:event-pubsub (:event-pubsub ctx)
                                       :topics topics
                                       :handler-fn handler-fn
                                       :context (dissoc ctx :processors)})]
              (try
                (is (= 1 (count (filter :resumed?
                                        (sheet/resume-in-progress! ctx))))
                    "the durable pending child is re-enqueued once")
                (is (empty? (filter :resumed? (sheet/resume-in-progress! ctx)))
                    "a repeated recovery scan cannot duplicate the pending child")
                (let [recovered (deref result 5000 ::timeout)]
                  (is (not= ::timeout recovered)
                      "the recovered map must settle without waiting for tick timeout")
                  (is (= :success (:status recovered)))
                  (is (= [10 20 30] (get-in recovered [:outputs :results])))
                  (is (= [1 2 3] @calls)
                      "the survivor is rejoined and each pending item runs once"))
                (finally
                  (tp/stop restarted))))
            (finally
              (sheet/cancel! ctx tick-id)
              (reset! recovery-state nil))))))))

(deftest det-e2e-274-recovery-uses-generated-map-parent-source
  (testing "recovery retains the source produced by an earlier sequence child"
    (h/with-async-test-context [ctx]
      (let [calls (atom [])
            leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
            workflow (sheet/workflow "det-e2e-274-generated-map-source"
                       (sheet/blackboard {:items [:or [:vector :int] :keyword]
                                          :item :int
                                          :results [:vector :int]})
                       (sheet/sequence "main"
                         (sheet/code "produce-items" :fn (fq "produce-items")
                           :reads [] :writes [:items])
                         (sheet/map-each "recoverable-map"
                           :from :items :as :item :into :results :parallel 1
                           (sheet/code "multiply-and-mutate-source"
                             :fn (fq "stop-after-first-generated-item")
                             :reads [:item] :writes [:item :items]))))
            sheet-id (sheet/build-workflow! ctx workflow)
            nodes-by-name (into {} (map (juxt :name identity)
                                        (sheet/get-nodes-for-sheet ctx sheet-id)))
            parent-id (get-in nodes-by-name ["recoverable-map" :id])
            child-id (get-in nodes-by-name ["multiply-and-mutate-source" :id])]
        (reset! generated-source-recovery-state
                {:calls calls :leaf-processor leaf-processor})
        (let [{:keys [tick-id result]} (sheet/execute-stream
                                        ctx sheet-id {}
                                        :timeout-ms 10000)]
          (try
            (is (h/settle-until!
                 #(and (= [1] @calls)
                       (= 1 (count (filter (fn [event]
                                            (and (= :sheet/node-execution-completed
                                                    (:event/type event))
                                                 (= child-id (:node-id event))))
                                          (tick-events ctx tick-id))))
                       (= 2 (count (child-starts ctx tick-id child-id))))
                 :timeout-ms 5000))
            (let [events (tick-events ctx tick-id)
                  parent-start (some #(when (and (= :sheet/node-execution-started
                                                    (:event/type %))
                                                 (= parent-id (:node-id %))
                                                 (nil? (:resumed-from-event-id %)))
                                        %)
                                     events)
                  [before-parent from-parent]
                  (split-with #(not= (:event/id parent-start) (:event/id %))
                              events)
                  after-parent (rest from-parent)]
              (is (nil? (runtime/durable-terminal-result ctx tick-id))
                  "the interrupted tick is not terminal")
              (is (not (contains? (:inputs parent-start) :items))
                  "ordinary sequence starts do not inline preceding writes")
              (is (some #(and (= :sheet/execution-value-written (:event/type %))
                              (= :items (:key %))
                              (= [1 2 3] (:value %)))
                        before-parent)
                  "the generated declaration is canonical before the parent start")
              (is (some #(and (= :sheet/execution-value-written (:event/type %))
                              (= :items (:key %))
                              (= :mutated-by-child (:value %)))
                        after-parent)
                  "the child's later source overwrite is durable"))
            (discard-process-local-map-coordinators!)
            (let [{:keys [handler-fn topics]} (get @tp/processor-registry*
                                                   :sheet/execute-leaf-node)
                  restarted (tp/start {:event-pubsub (:event-pubsub ctx)
                                       :topics topics
                                       :handler-fn handler-fn
                                       :context (dissoc ctx :processors)})]
              (try
                (is (= 1 (count (filter :resumed?
                                        (sheet/resume-in-progress! ctx)))))
                (let [recovered (deref result 5000 ::timeout)]
                  (is (not= ::timeout recovered))
                  (is (= :success (:status recovered)))
                  (is (= [10 20 30] (get-in recovered [:outputs :results])))
                  (is (= [1 2 3] @calls)))
                (finally
                  (tp/stop restarted))))
            (finally
              (sheet/cancel! ctx tick-id)
              (reset! generated-source-recovery-state nil))))))))

(deftest det-e2e-274-recovery-preserves-partial-evidence
  (testing "successes, failed indices, and reasons match an uninterrupted control"
    (h/with-async-test-context [ctx]
      (let [leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
            workflow (sheet/workflow "det-e2e-274-map-partial-rejoin"
                       (sheet/blackboard {:items [:vector :int]
                                          :item :int
                                          :results [:vector :int]})
                       (sheet/map-each "recoverable-map"
                         :from :items :as :item :into :results :parallel 3
                         (sheet/code "sometimes-fails" :fn (fq "fail-second-item")
                           :reads [:item] :writes [:item])))
            sheet-id (sheet/build-workflow! ctx workflow)
            nodes-by-name (into {} (map (juxt :name identity)
                                        (sheet/get-nodes-for-sheet ctx sheet-id)))
            parent-id (get-in nodes-by-name ["recoverable-map" :id])
            child-id (get-in nodes-by-name ["sometimes-fails" :id])
            control-calls (atom [])]
        (reset! partial-recovery-state {:calls control-calls})
        (let [control (sheet/execute ctx sheet-id {:items [1 2 3]}
                                     :timeout-ms 10000)
              control-completion (map-parent-completion ctx (:trace-id control)
                                                        parent-id)
              recovery-calls (atom [])]
          (reset! partial-recovery-state {:calls recovery-calls})
          ;; Simulate a process dying after two of three parallel starts were
          ;; handled. The third start is durable but never reaches the worker.
          (tp/stop leaf-processor)
          (let [{:keys [handler-fn topics]} (get @tp/processor-registry*
                                                 :sheet/execute-leaf-node)
                first-process (tp/start
                               {:event-pubsub (:event-pubsub ctx)
                                :topics topics
                                :handler-fn
                                (fn [{:keys [event] :as processor-context}]
                                  (let [idx (get-in event [:inputs
                                                           :ai.obney.orc.orc-service.core.todo-processors/map-each-index])]
                                    (when (or (nil? idx) (< idx 2))
                                      (handler-fn processor-context))))
                                :context (dissoc ctx :processors)})
                {:keys [tick-id result]} (sheet/execute-stream
                                          ctx sheet-id {:items [1 2 3]}
                                          :timeout-ms 10000)]
            (try
              (is (h/settle-until!
                   #(and (= 2 (count (filter (fn [event]
                                               (and (= :sheet/node-execution-completed
                                                       (:event/type event))
                                                    (= child-id (:node-id event))))
                                             (tick-events ctx tick-id))))
                         (= 3 (count (child-starts ctx tick-id child-id))))
                   :timeout-ms 5000)
                  "the crash retains one success, one failure, and one pending start")
              (is (= #{1 2} (set @recovery-calls)))
              (is (= 2 (count @recovery-calls)))
              (tp/stop first-process)
              (discard-process-local-map-coordinators!)
              (let [{:keys [handler-fn topics]} (get @tp/processor-registry*
                                                     :sheet/execute-leaf-node)
                    restarted (tp/start {:event-pubsub (:event-pubsub ctx)
                                         :topics topics
                                         :handler-fn handler-fn
                                         :context (dissoc ctx :processors)})]
                (try
                  (is (= 1 (count (filter :resumed?
                                          (sheet/resume-in-progress! ctx)))))
                  (let [recovered (deref result 5000 ::timeout)
                        recovered-completion (map-parent-completion ctx tick-id
                                                                    parent-id)]
                    (is (not= ::timeout recovered))
                    (is (= (:status control) (:status recovered)))
                    (is (= (get-in control [:outputs :results])
                           (get-in recovered [:outputs :results]))
                        "the declared map output, not the shared :as slot, is the contract")
                    (is (= (:partial-summary control-completion)
                           (:partial-summary recovered-completion)))
                    (is (= #{1 2 3} (set @recovery-calls)))
                    (is (= 3 (count @recovery-calls))))
                  (finally
                    (tp/stop restarted))))
              (finally
                (tp/stop first-process)
                (sheet/cancel! ctx tick-id)
                (reset! partial-recovery-state nil)))))))))

(deftest det-e2e-274-recovery-finalizes-when-every-child-is-already-terminal
  (testing "a lost parent-completion boundary dispatches no child again"
    (h/with-async-test-context [ctx]
      (let [map-processor (get-in ctx [:processors
                                       :sheet/handle-map-each-child-completion])
            workflow (sheet/workflow "det-e2e-274-map-terminal-rejoin"
                       (sheet/blackboard {:items [:vector :int]
                                          :item :int
                                          :results [:vector :int]})
                       (sheet/map-each "recoverable-map"
                         :from :items :as :item :into :results :parallel 3
                         (sheet/code "record" :fn (fq "record-terminal-item")
                           :reads [:item] :writes [:item])))
            sheet-id (sheet/build-workflow! ctx workflow)
            nodes-by-name (into {} (map (juxt :name identity)
                                        (sheet/get-nodes-for-sheet ctx sheet-id)))
            parent-id (get-in nodes-by-name ["recoverable-map" :id])
            child-id (get-in nodes-by-name ["record" :id])
            {:keys [handler-fn topics]} (get @tp/processor-registry*
                                             :sheet/handle-map-each-child-completion)]
        (reset! terminal-recovery-calls [])
        (tp/stop map-processor)
        (let [suppress-parent-completion
              (tp/start
               {:event-pubsub (:event-pubsub ctx)
                :topics topics
                :handler-fn
                (fn [processor-context]
                  (when-let [result (handler-fn processor-context)]
                    (update result :result/events
                            (fn [events]
                              (filterv #(not (and (= :sheet/node-execution-completed
                                                      (:event/type %))
                                                  (= parent-id (:node-id %))))
                                       events)))))
                :context (dissoc ctx :processors)})
              {:keys [tick-id result]} (sheet/execute-stream
                                        ctx sheet-id {:items [1 2 3]}
                                        :timeout-ms 10000)]
          (try
            (is (h/settle-until!
                 #(= 3 (count (filter (fn [event]
                                        (and (= :sheet/node-execution-completed
                                                (:event/type event))
                                             (= child-id (:node-id event))))
                                      (tick-events ctx tick-id))))
                 :timeout-ms 5000))
            (is (nil? (map-parent-completion ctx tick-id parent-id)))
            (tp/stop suppress-parent-completion)
            (discard-process-local-map-coordinators!)
            (is (empty? (filter :resumed? (sheet/resume-in-progress! ctx)))
                "no child frontier remains to re-enqueue")
            (let [recovered (deref result 5000 ::timeout)
                  parent-completions (filter #(and (= :sheet/node-execution-completed
                                                     (:event/type %))
                                                   (= parent-id (:node-id %)))
                                             (tick-events ctx tick-id))]
              (is (not= ::timeout recovered))
              (is (= :success (:status recovered)))
              (is (= [10 20 30] (get-in recovered [:outputs :results])))
              (is (= #{1 2 3} (set @terminal-recovery-calls)))
              (is (= 3 (count @terminal-recovery-calls)))
              (is (= 3 (count (child-starts ctx tick-id child-id))))
              (is (= 1 (count parent-completions))))
            (finally
              (tp/stop suppress-parent-completion)
              (sheet/cancel! ctx tick-id)
              (reset! terminal-recovery-calls []))))))))

(deftest det-e2e-274-recovery-dedupes-reordered-contexts-and-preserves-nil
  (testing "terminal membership is execution context, not result truthiness"
    (h/with-async-test-context [ctx]
      (let [leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
            workflow (sheet/workflow "det-e2e-274-map-nil-rejoin"
                       (sheet/blackboard {:items [:or [:vector [:maybe :int]] :keyword]
                                          :item [:maybe :int]
                                          :results [:vector [:maybe :int]]})
                       (sheet/map-each "recoverable-map"
                         :from :items :as :item :into :results :parallel 3
                         (sheet/code "nil-is-a-result" :fn (fq "return-source-only")
                           :reads [] :writes [:items])))
            sheet-id (sheet/build-workflow! ctx workflow)
            nodes-by-name (into {} (map (juxt :name identity)
                                        (sheet/get-nodes-for-sheet ctx sheet-id)))
            parent-id (get-in nodes-by-name ["recoverable-map" :id])
            child-id (get-in nodes-by-name ["nil-is-a-result" :id])
            {:keys [handler-fn topics]} (get @tp/processor-registry*
                                             :sheet/execute-leaf-node)]
        (reset! nil-recovery-calls 0)
        (tp/stop leaf-processor)
        (let [first-process-seen (atom {})
              first-process
              (tp/start
               {:event-pubsub (:event-pubsub ctx)
                :topics topics
                :handler-fn
                (fn [{:keys [event] :as processor-context}]
                  (let [idx (get-in event [:inputs
                                           :ai.obney.orc.orc-service.core.todo-processors/map-each-index])]
                    (when (or (nil? idx)
                              (and (< idx 2)
                                   (= 1 (get (swap! first-process-seen
                                                    update idx (fnil inc 0))
                                             idx))))
                      (handler-fn processor-context))))
                :context (dissoc ctx :processors)})
              {:keys [tick-id result]} (sheet/execute-stream
                                        ctx sheet-id {:items [1 nil 3]}
                                        :timeout-ms 10000)]
          (try
            (is (h/settle-until!
                 #(and (= 2 (count (filter (fn [event]
                                             (and (= :sheet/node-execution-completed
                                                     (:event/type event))
                                                  (= child-id (:node-id event))))
                                           (tick-events ctx tick-id))))
                       (= 3 (count (child-starts ctx tick-id child-id))))
                 :timeout-ms 5000))
            (tp/stop first-process)
            (discard-process-local-map-coordinators!)
            (let [restarted (tp/start {:event-pubsub (:event-pubsub ctx)
                                       :topics topics
                                       :handler-fn handler-fn
                                       :context (dissoc ctx :processors)})]
              (try
                (is (= 1 (count (filter :resumed?
                                        (sheet/resume-in-progress! ctx)))))
                (is (empty? (filter :resumed? (sheet/resume-in-progress! ctx))))
                (let [recovered (deref result 5000 ::timeout)
                      parent-completions (filter #(and (= :sheet/node-execution-completed
                                                         (:event/type %))
                                                       (= parent-id (:node-id %)))
                                                 (tick-events ctx tick-id))]
                  (is (not= ::timeout recovered))
                  (is (= :success (:status recovered)))
                  (is (= [1 nil 3] (get-in recovered [:outputs :results])))
                  (is (= 3 @nil-recovery-calls))
                  (let [starts (child-starts ctx tick-id child-id)
                        contexts (set (map #(select-keys
                                            (:inputs %)
                                            [:ai.obney.orc.orc-service.core.todo-processors/map-each-index
                                             :ai.obney.orc.orc-service.core.todo-processors/map-each-parent])
                                           starts))]
                    (is (= 4 (count starts))
                        "one recovery delivery exists for the one pending context")
                    (is (= 3 (count contexts))
                        "recovery never creates a second item slot"))
                  (is (= 1 (count parent-completions))))
                (finally
                  (tp/stop restarted))))
            (finally
              (tp/stop first-process)
              (sheet/cancel! ctx tick-id)
              (reset! nil-recovery-calls 0))))))))

(deftest det-e2e-274-sqlite-close-reopen-rejoins-the-original-tick
  (testing "persistent recovery uses the same tick without resubmitting the workflow"
    (let [db-file (str "/tmp/rr12-map-recovery-" (random-uuid) ".db")
          connection {:type :sqlite :database-file db-file :maximum-pool-size 2}
          first-context (atom nil)
          reopened-context (atom nil)
          calls (atom [])
          tick-id (random-uuid)]
      (try
        (let [ctx (h/create-async-test-context {:event-store-conn connection})
              _ (reset! first-context ctx)
              leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
              workflow (sheet/workflow "det-e2e-274-sqlite-map-rejoin"
                         (sheet/blackboard {:items [:vector :int]
                                            :item :int
                                            :results [:vector :int]})
                         (sheet/map-each "recoverable-map"
                           :from :items :as :item :into :results :parallel 1
                           (sheet/code "multiply" :fn (fq "stop-after-first-item")
                             :reads [:item] :writes [:item])))
              sheet-id (sheet/build-workflow! ctx workflow)
              nodes-by-name (into {} (map (juxt :name identity)
                                          (sheet/get-nodes-for-sheet ctx sheet-id)))
              parent-id (get-in nodes-by-name ["recoverable-map" :id])
              child-id (get-in nodes-by-name ["multiply" :id])]
          (reset! recovery-state {:calls calls :leaf-processor leaf-processor})
          (sheet/execute-stream ctx sheet-id {:items [1 2 3]}
                                :tick-id tick-id :timeout-ms 15000)
          (is (h/settle-until!
               #(and (= [1] @calls)
                     (= 1 (count (filter (fn [event]
                                          (and (= :sheet/node-execution-completed
                                                  (:event/type event))
                                               (= child-id (:node-id event))))
                                        (tick-events ctx tick-id))))
                     (= 2 (count (child-starts ctx tick-id child-id))))
               :timeout-ms 5000))
          (h/stop-async-context ctx)
          (reset! first-context nil)
          (discard-process-local-map-coordinators!)

          (let [reopened (h/create-async-test-context
                          {:event-store-conn connection})]
            (reset! reopened-context reopened)
            ;; Catch-up may consume the durable pending start before this scan;
            ;; either way, recovery must reconstruct the map and settle the
            ;; original tick without executing a survivor twice.
            (sheet/resume-in-progress! reopened)
            (is (h/settle-until!
                 #(some? (runtime/durable-terminal-result reopened tick-id))
                 :timeout-ms 7000))
            (let [result (runtime/durable-terminal-result reopened tick-id)
                  events (tick-events reopened tick-id)
                  parent-completions (filter #(and (= :sheet/node-execution-completed
                                                      (:event/type %))
                                                    (= parent-id (:node-id %)))
                                              events)]
              (is (= :success (:status result)))
              (is (= [10 20 30] (get-in result [:outputs :results])))
              (is (= [1 2 3] @calls))
              (is (= 1 (count parent-completions))))))
        (finally
          (when @reopened-context
            (h/stop-async-context @reopened-context))
          (when @first-context
            (h/stop-async-context @first-context))
          (reset! recovery-state nil)
          (doseq [suffix ["" "-wal" "-shm"]]
            (io/delete-file (str db-file suffix) true)))))))

(deftest det-e2e-274-overlapping-recovery-cannot-regress-coordinator-state
  (testing "a stale overlapping recovery snapshot cannot erase a newer completion"
    (h/with-async-test-context [ctx]
      (let [calls (atom [])
            worker (atom (get-in ctx [:processors :sheet/execute-leaf-node]))
            workflow (sheet/workflow "det-e2e-274-overlapping-recovery"
                       (sheet/blackboard {:items [:vector :int]
                                          :item :int
                                          :results [:vector :int]})
                       (sheet/map-each "recoverable-map"
                         :from :items :as :item :into :results :parallel 1
                         (sheet/code "multiply" :fn (fq "coordinate-overlapping-recovery")
                           :reads [:item] :writes [:item])))
            sheet-id (sheet/build-workflow! ctx workflow)
            nodes-by-name (into {} (map (juxt :name identity)
                                        (sheet/get-nodes-for-sheet ctx sheet-id)))
            parent-id (get-in nodes-by-name ["recoverable-map" :id])
            child-id (get-in nodes-by-name ["multiply" :id])]
        (let [third-entered (promise)
              allow-third (promise)]
          (reset! overlapping-recovery-state
                  {:calls calls :worker worker :stop-item 1 :block-item 3
                   :third-entered third-entered :allow-third allow-third})
        (let [{:keys [tick-id result]} (sheet/execute-stream
                                        ctx sheet-id {:items [1 2 3]}
                                        :timeout-ms 10000)]
          (try
            (is (h/settle-until!
                 #(and (= [1] @calls)
                       (= 1 (count (filter (fn [event]
                                             (and (= :sheet/node-execution-completed
                                                     (:event/type event))
                                                  (= child-id (:node-id event))))
                                           (tick-events ctx tick-id))))
                       (= 2 (count (child-starts ctx tick-id child-id))))
                 :timeout-ms 5000)
                "the crash boundary has one survivor and one durable pending start")
            (discard-process-local-map-coordinators!)
            (let [snapshot-taken (promise)
                  release-snapshot (promise)
                  stale-thread (atom nil)
                  read-events value-log/read-tick-events
                  wrapper (fn [context tenant-id requested-tick-id]
                            (let [events (read-events context tenant-id
                                                      requested-tick-id)]
                              (if (= @stale-thread (Thread/currentThread))
                                (do (deliver snapshot-taken true)
                                    @release-snapshot
                                    events)
                                events)))]
              (with-redefs [value-log/read-tick-events wrapper]
                (let [stale-recovery
                      (future
                        (reset! stale-thread (Thread/currentThread))
                        (todo-processors/recover-map-each-coordinator!
                         ctx sheet-id tick-id parent-id))]
                  (is (= true (deref snapshot-taken 2000 ::timeout)))
                  (let [{:keys [handler-fn topics]} (get @tp/processor-registry*
                                                         :sheet/execute-leaf-node)
                        second-worker
                        (tp/start {:event-pubsub (:event-pubsub ctx)
                                   :topics topics
                                   :handler-fn handler-fn
                                   :context (dissoc ctx :processors)})]
                    (reset! worker second-worker)
                    (try
                      (is (= 1 (count (filter :resumed?
                                              (sheet/resume-in-progress! ctx))))
                          "the public recovery boundary re-enqueues item 2")
                      (is (h/settle-until!
                           #(and (= [1 2] @calls)
                                 (= 2 (count (filter
                                              (fn [event]
                                                (and (= :sheet/node-execution-completed
                                                        (:event/type event))
                                                     (= child-id (:node-id event))))
                                              (tick-events ctx tick-id))))
                                 (= 4 (count (child-starts ctx tick-id child-id))))
                           :timeout-ms 5000)
                          "new durable evidence advances the installed coordinator")
                      (is (= true (deref third-entered 2000 ::timeout))
                          "the final item is held after its durable start")
                      ;; The delayed scan now attempts to install its older view.
                      (deliver release-snapshot true)
                      (deref stale-recovery 2000 ::timeout)
                      (deliver allow-third true)
                      (let [recovered (deref result 5000 ::timeout)]
                        (is (not= ::timeout recovered)
                            "stale recovery must not wedge the final pending item")
                        (is (= :success (:status recovered)))
                        (is (= [10 20 30] (get-in recovered [:outputs :results])))
                        (is (= [1 2 3] @calls)))
                      (finally
                        (deliver allow-third true)
                        (tp/stop second-worker)))))))
            (finally
              (sheet/cancel! ctx tick-id)
              (reset! overlapping-recovery-state nil)))))))))
