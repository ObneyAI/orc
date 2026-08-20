(ns ai.obney.orc.orc-service.durability-boundary-e2e-test
  "Deterministic end-to-end coverage for ephemeral routing and durable effects."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.todo-processor-v2.interface :as tp]))

(def effect-count (atom 0))
(def restart-state (atom nil))

(defn selected-effect [_]
  (swap! effect-count inc)
  {:route :alternative})

(defn forbidden-effect [_]
  (throw (ex-info "unselected branch executed" {})))

(defn completed-boundary [{:keys [inputs]}]
  (let [{:keys [leaf-processor completed-count]} @restart-state]
    (swap! completed-count inc)
    (tp/stop leaf-processor)
    {:middle (str (:input inputs) "-durable")}))

(defn resumed-boundary [{:keys [inputs]}]
  (swap! (:resumed-count @restart-state) inc)
  {:output (str (:middle inputs) "-resumed")})

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.durability-boundary-e2e-test/"
       function-name))

(defn- tick-events [ctx tick-id]
  (into [] (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx)
                     :tags #{[:tick tick-id]}})))

(defn- sheet-events [ctx sheet-id]
  (into [] (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx)
                     :tags #{[:sheet sheet-id]}})))

(defn- execution-metrics [ctx result elapsed-ns]
  (let [events (tick-events ctx (:trace-id result))]
    {:elapsed-ns elapsed-ns
     :event-count (count events)
     :event-bytes (reduce + (map #(alength (.getBytes (pr-str %) "UTF-8")) events))
     :append-transactions (count (set (map :event/timestamp events)))
     :storage-writes (count (filter #(= :sheet/execution-value-written
                                         (:event/type %)) events))
     :processor-dispatches (count (filter #(contains? #{:sheet/tree-tick-started
                                                        :sheet/node-execution-started
                                                        :sheet/node-execution-completed
                                                        :sheet/tree-tick-completed}
                                                      (:event/type %)) events))}))

(deftest det-e2e-151-ephemeral-routing-retains-observable-trace
  (testing "deterministic routing is ephemeral while selected consumer code stays durable"
    (h/with-async-test-context [ctx]
      (reset! effect-count 0)
      (let [definition (sheet/workflow "det-e2e-151-ephemeral-routing"
                         (sheet/blackboard {:enabled :boolean
                                            :route :keyword})
                         (sheet/fallback "route"
                           (sheet/sequence "guarded-route"
                             (sheet/condition "enabled?"
                               :check {:key :enabled :op :equals :value true})
                             (sheet/code "forbidden-effect" :fn (fq "forbidden-effect")
                               :reads [] :writes [:route]))
                           (sheet/code "selected-effect" :fn (fq "selected-effect")
                             :reads [] :writes [:route])))
            sheet-id (sheet/build-workflow! ctx definition)
            nodes-by-name (into {} (map (juxt :name identity)
                                        (sheet/get-nodes-for-sheet ctx sheet-id)))
            result (sheet/execute ctx sheet-id {:enabled false})
            events (tick-events ctx (:trace-id result))
            lifecycle? #{:sheet/node-execution-started
                         :sheet/node-execution-completed}
            lifecycle-events (filter #(lifecycle? (:event/type %)) events)
            lifecycle-by-node (group-by :node-id lifecycle-events)
            ephemeral-ids (mapv #(get-in nodes-by-name [% :id])
                                ["route" "guarded-route" "enabled?"])
            selected-id (get-in nodes-by-name ["selected-effect" :id])]
        (is (= :success (:status result)))
        (is (= :alternative (get-in result [:outputs :route])))
        (is (= 1 @effect-count))
        (is (every? #(empty? (get lifecycle-by-node %)) ephemeral-ids)
            "static condition and composite bookkeeping have no independent durable lifecycle")
        (is (= [:sheet/node-execution-started :sheet/node-execution-completed]
               (mapv :event/type (get lifecycle-by-node selected-id)))
            "consumer code remains an independently durable boundary")
        (is (h/settle-until!
             #(some? (get-in (h/run-query ctx (h/make-get-trace-query (:trace-id result)))
                             [:query/result :trace]))))
        (let [trace (get-in (h/run-query ctx (h/make-get-trace-query (:trace-id result)))
                            [:query/result :trace])]
          (is (= #{"route" "guarded-route" "enabled?" "selected-effect"}
                 (set (map :node-name (:node-traces trace))))
              "the public trace retains both summarized routing and the durable leaf"))))))

(deftest det-e2e-152-153-atomic-frontier-and-restart-recovery
  (testing "routing summary and selected durable frontier survive processor restart"
    (h/with-async-test-context [ctx]
      (let [completed-count (atom 0)
            resumed-count (atom 0)
            leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
            definition (sheet/workflow "det-e2e-152-153-frontier-recovery"
                         (sheet/blackboard {:enabled :boolean
                                            :input :string
                                            :middle :string
                                            :output :string})
                         (sheet/fallback "route"
                           (sheet/sequence "disabled"
                             (sheet/condition "enabled?"
                               :check {:key :enabled :op :equals :value true})
                             (sheet/code "forbidden-effect" :fn (fq "forbidden-effect")
                               :reads [] :writes [:output]))
                           (sheet/sequence "selected"
                             (sheet/code "completed-boundary" :fn (fq "completed-boundary")
                               :reads [:input] :writes [:middle])
                             (sheet/code "resumed-boundary" :fn (fq "resumed-boundary")
                               :reads [:middle] :writes [:output]))))
            sheet-id (sheet/build-workflow! ctx definition)
            nodes-by-name (into {} (map (juxt :name identity)
                                        (sheet/get-nodes-for-sheet ctx sheet-id)))
            completed-id (get-in nodes-by-name ["completed-boundary" :id])
            resumed-id (get-in nodes-by-name ["resumed-boundary" :id])]
        (reset! restart-state {:leaf-processor leaf-processor
                               :completed-count completed-count
                               :resumed-count resumed-count})
        (let [execution (future (sheet/execute ctx sheet-id
                                               {:enabled false :input "x"}
                                               :timeout-ms 15000))]
          (is (h/settle-until!
               #(let [events (sheet-events ctx sheet-id)]
                  (and (some (fn [event]
                               (and (= :sheet/node-execution-completed (:event/type event))
                                    (= completed-id (:node-id event)))) events)
                       (some (fn [event]
                               (and (= :sheet/node-execution-started (:event/type event))
                                    (= resumed-id (:node-id event)))) events)))
               :timeout-ms 5000))
          (let [tick-id (:tick-id (last (filter #(= resumed-id (:node-id %))
                                                (sheet-events ctx sheet-id))))
                before (tick-events ctx tick-id)
                summary-index (first (keep-indexed
                                      (fn [index event]
                                        (when (= :sheet/ephemeral-evaluations-recorded
                                                 (:event/type event)) index)) before))
                first-start-index (first (keep-indexed
                                          (fn [index event]
                                            (when (and (= :sheet/node-execution-started
                                                            (:event/type event))
                                                       (= completed-id (:node-id event))) index)) before))
                {:keys [handler-fn topics]} (get @tp/processor-registry*
                                                 :sheet/execute-leaf-node)
                restarted (tp/start {:event-pubsub (:event-pubsub ctx)
                                     :topics topics
                                     :handler-fn handler-fn
                                     :context (dissoc ctx :processors)})]
            (try
              (is (< summary-index first-start-index)
                  "routing evidence is committed before the selected effect can start")
              (is (= 1 @completed-count))
              (is (= 1 (count (filter :resumed? (sheet/resume-in-progress! ctx)))))
              (is (empty? (sheet/resume-in-progress! ctx)))
              (let [result (deref execution 10000 ::timeout)]
                (is (= :success (:status result)))
                (is (= "x-durable-resumed" (get-in result [:outputs :output])))
                (is (= 1 @completed-count) "completed durable work is not repeated")
                (is (= 1 @resumed-count)))
              (finally
                (tp/stop restarted)
                (reset! restart-state nil)))))))))

(deftest det-e2e-154-durability-optimization-measurement
  (testing "identical fallback traversal records before/after durability metrics"
    (h/with-async-test-context [ctx]
      (let [definition (sheet/workflow "det-e2e-154-durability-measurement"
                         (sheet/blackboard {:route :keyword})
                         (sheet/fallback "routes"
                           (sheet/sequence "route-a"
                             (sheet/condition "a?" :check {:key :route :op :equals :value :a})
                             (sheet/code "forbidden-a" :fn (fq "forbidden-effect")
                               :reads [] :writes [:route]))
                           (sheet/sequence "route-b"
                             (sheet/condition "b?" :check {:key :route :op :equals :value :b})
                             (sheet/code "forbidden-b" :fn (fq "forbidden-effect")
                               :reads [] :writes [:route]))
                           (sheet/sequence "route-c"
                             (sheet/condition "c?" :check {:key :route :op :equals :value :c})
                             (sheet/code "forbidden-c" :fn (fq "forbidden-effect")
                               :reads [] :writes [:route]))
                           (sheet/code "selected" :fn (fq "selected-effect")
                             :reads [] :writes [:route])))
            sheet-id (sheet/build-workflow! ctx definition)
            legacy-start (System/nanoTime)
            legacy-result (sheet/execute ctx sheet-id {:route :none}
                                         :durability-mode :legacy)
            legacy (execution-metrics ctx legacy-result (- (System/nanoTime) legacy-start))
            optimized-start (System/nanoTime)
            optimized-result (sheet/execute ctx sheet-id {:route :none})
            optimized (execution-metrics ctx optimized-result
                                         (- (System/nanoTime) optimized-start))]
        (is (= (:outputs legacy-result) (:outputs optimized-result)))
        (is (= :success (:status optimized-result)))
        (is (< (:event-count optimized) (:event-count legacy)) legacy)
        (is (< (:event-bytes optimized) (:event-bytes legacy))
            {:legacy legacy :optimized optimized})
        (is (< (:append-transactions optimized) (:append-transactions legacy))
            {:legacy legacy :optimized optimized})
        (is (< (:processor-dispatches optimized) (:processor-dispatches legacy))
            {:legacy legacy :optimized optimized})
        (is (= (:storage-writes legacy) (:storage-writes optimized))
            "optimization does not weaken durable blackboard writes")
        (is (pos? (:elapsed-ns legacy)))
        (is (pos? (:elapsed-ns optimized)))))))
