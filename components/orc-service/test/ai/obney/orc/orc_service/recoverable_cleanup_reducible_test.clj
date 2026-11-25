(ns ai.obney.orc.orc-service.recoverable-cleanup-reducible-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as sandbox]
            [ai.obney.orc.orc-service.core.read-models :as read-models]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree]))

(deftest deferred-ownership-read-failure-retains-binding-and-completed-result
  (h/with-async-test-context [ctx]
    (let [sctx (sandbox/build-rlm-context {:blackboard {} :durable-source-required? true})
          emission (sandbox/execute-rlm-code sctx
                     "(emit-tree! '[:code {:reads [:n] :writes [:result] :output-schemas {:result :int} :fn (fn [{:keys [inputs]}] {:result (inc (:n inputs))})}])")
          actual-read es/read
          ownership-types #{:sheet/tree-tick-started :sheet/tree-tick-completed :sheet/tick-cancelled}
          read-attempts (atom [])
          reductions (atom 0)
          sentinel (ex-info "Owned deferred ownership read failed" {:owned-probe true})
          result (with-redefs [es/read
                               (fn [store options]
                                 (if (and (identical? store (:event-store ctx))
                                          (= (:tenant-id ctx) (:tenant-id options))
                                          (= ownership-types (:types options))
                                          (= 1 (count (:tags options)))
                                          (= :sheet (ffirst (:tags options))))
                                   (do
                                     (swap! read-attempts conj options)
                                     (reify clojure.lang.IReduceInit
                                       (reduce [_ _ _]
                                         (swap! reductions inc)
                                         (throw sentinel))))
                                   (actual-read store options)))]
                   (tree/execute-tree (:generated-tree @(:sandbox-vars sctx)) ctx
                                      {:blackboard {:n 4} :timeout-ms 5000}))
          all (h/read-all-events ctx)
          configured (first (filter #(= :sheet/node-executor-set (:event/type %)) all))
          key (:fn configured)
          sheet (:sheet-id configured)
          completed (first (filter #(= :sheet/tree-tick-completed (:event/type %)) all))]
      (try
        (println :OWNED-DEFERRED-READ-EVIDENCE
                 (pr-str {:attempts @read-attempts :reductions @reductions
                          :primary-result result :completion completed
                          :function-id key :binding-retained? (some? (tree/lookup-ephemeral-fn key))}))
        (is (nil? (:error emission)))
        (is (= [{:tenant-id (:tenant-id ctx) :tags #{[:sheet sheet]} :types ownership-types}]
               @read-attempts) "Only the exact ownership read is injected")
        (is (= 1 @reductions) "The failure happens on actual reducible consumption")
        (is (= :success (:root-status completed)) "Real command/processor execution durably finished")
        (is (= :success (:status result)) "Cleanup uncertainty must preserve the completed public result")
        (is (= 5 (get-in result [:outputs :result])))
        (is (fn? (tree/lookup-ephemeral-fn key)) "Unknown live ownership conservatively retains the exact callable")
        (is (= (:recoverable-fn configured)
               (:orc/recoverable-fn (meta (tree/lookup-ephemeral-fn key)))))
        (finally (when key (tree/clear-ephemeral-fn! key)))))))

(deftest post-ownership-binding-observation-failure-retains-completed-result
  (h/with-async-test-context [ctx]
    (let [sctx (sandbox/build-rlm-context {:blackboard {} :durable-source-required? true})
          emission (sandbox/execute-rlm-code sctx
                     "(emit-tree! '[:code {:reads [:n] :writes [:result] :output-schemas {:result :int} :fn (fn [{:keys [inputs]}] {:result (inc (:n inputs))})}])")
          actual-read es/read
          actual-tick-context read-models/get-tick-execution-context
          cleanup-thread (Thread/currentThread)
          ownership-types #{:sheet/tree-tick-started :sheet/tree-tick-completed :sheet/tick-cancelled}
          ownership-observed? (atom false)
          ownership-reads (atom [])
          failed-lookups (atom [])
          sentinel (ex-info "Owned cleanup binding observation failed" {:owned-projection-probe true})
          result
          (with-redefs [es/read
                        (fn [store options]
                          (let [result (actual-read store options)]
                            (if (and (identical? cleanup-thread (Thread/currentThread))
                                     (identical? store (:event-store ctx))
                                     (= (:tenant-id ctx) (:tenant-id options))
                                     (= ownership-types (:types options))
                                     (= 1 (count (:tags options)))
                                     (= :sheet (ffirst (:tags options))))
                              (do
                                (swap! ownership-reads conj options)
                                (reify clojure.lang.IReduceInit
                                  (reduce [_ rf init]
                                    (let [value (reduce rf init result)]
                                      (reset! ownership-observed? true)
                                      value))))
                              result)))
                        read-models/get-tick-execution-context
                        (fn [context tick-id]
                          (if (and (identical? cleanup-thread (Thread/currentThread))
                                   (identical? ctx context)
                                   @ownership-observed?)
                            (do (swap! failed-lookups conj tick-id) (throw sentinel))
                            (actual-tick-context context tick-id)))]
            (tree/execute-tree (:generated-tree @(:sandbox-vars sctx)) ctx
                               {:blackboard {:n 4} :timeout-ms 5000}))
          all (h/read-all-events ctx)
          configured (first (filter #(= :sheet/node-executor-set (:event/type %)) all))
          key (:fn configured) sheet (:sheet-id configured)
          completed (first (filter #(= :sheet/tree-tick-completed (:event/type %)) all))]
      (try
        (println :OWNED-BINDING-OBSERVATION-EVIDENCE
                 (pr-str {:ownership-observed? @ownership-observed? :ownership-reads @ownership-reads
                          :failed-lookups @failed-lookups :primary-result result :completion completed
                          :function-id key :binding-retained? (some? (tree/lookup-ephemeral-fn key))}))
        (is (nil? (:error emission)))
        (is (= [{:tenant-id (:tenant-id ctx) :tags #{[:sheet sheet]} :types ownership-types}]
               @ownership-reads))
        (is (true? @ownership-observed?) "Real lifecycle reduction completed before the injected lookup")
        (is (= [(:tick-id completed)] @failed-lookups) "Only the cleanup's subsequent exact tick lookup fails")
        (is (= :success (:root-status completed)))
        (is (= :success (:status result)) "Cleanup-only projection failure must preserve committed success")
        (is (= 5 (get-in result [:outputs :result])))
        (is (fn? (tree/lookup-ephemeral-fn key)))
        (is (= (:recoverable-fn configured)
               (:orc/recoverable-fn (meta (tree/lookup-ephemeral-fn key)))))
        (finally (when key (tree/clear-ephemeral-fn! key)))))))

(deftest absent-or-anomalous-cleanup-projection-does-not-prove-empty-bindings
  (doseq [missing [nil {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                       :cognitect.anomalies/message "Owned projection temporarily unavailable"}]]
    (h/with-async-test-context [ctx]
      (let [sctx (sandbox/build-rlm-context {:blackboard {} :durable-source-required? true})
            emission (sandbox/execute-rlm-code sctx
                       "(emit-tree! '[:code {:reads [:n] :writes [:result] :output-schemas {:result :int} :fn (fn [{:keys [inputs]}] {:result (inc (:n inputs))})}])")
            actual-read es/read
            actual-tick-context read-models/get-tick-execution-context
            cleanup-thread (Thread/currentThread)
            ownership-types #{:sheet/tree-tick-started :sheet/tree-tick-completed :sheet/tick-cancelled}
            ownership-observed? (atom false)
            ownership-reads (atom [])
            missing-lookups (atom [])
            result
            (with-redefs [es/read
                          (fn [store options]
                            (let [result (actual-read store options)]
                              (if (and (identical? cleanup-thread (Thread/currentThread))
                                       (identical? store (:event-store ctx))
                                       (= (:tenant-id ctx) (:tenant-id options))
                                       (= ownership-types (:types options))
                                       (= 1 (count (:tags options)))
                                       (= :sheet (ffirst (:tags options))))
                                (do
                                  (swap! ownership-reads conj options)
                                  (reify clojure.lang.IReduceInit
                                    (reduce [_ rf init]
                                      (let [value (reduce rf init result)]
                                        (reset! ownership-observed? true)
                                        value))))
                                result)))
                          read-models/get-tick-execution-context
                          (fn [context tick-id]
                            (if (and (identical? cleanup-thread (Thread/currentThread))
                                     (identical? ctx context)
                                     @ownership-observed?)
                              (do (swap! missing-lookups conj tick-id) missing)
                              (actual-tick-context context tick-id)))]
              (tree/execute-tree (:generated-tree @(:sandbox-vars sctx)) ctx
                                 {:blackboard {:n 4} :timeout-ms 5000}))
            all (h/read-all-events ctx)
            configured (first (filter #(= :sheet/node-executor-set (:event/type %)) all))
            key (:fn configured) sheet (:sheet-id configured)
            completed (first (filter #(= :sheet/tree-tick-completed (:event/type %)) all))]
        (try
          (println :OWNED-MISSING-PROJECTION-EVIDENCE
                   (pr-str {:missing-projection missing :ownership-observed? @ownership-observed?
                            :ownership-reads @ownership-reads :missing-lookups @missing-lookups
                            :primary-result result :completion completed
                            :function-id key :binding-retained? (some? (tree/lookup-ephemeral-fn key))}))
          (is (nil? (:error emission)))
          (is (= [{:tenant-id (:tenant-id ctx) :tags #{[:sheet sheet]} :types ownership-types}]
                 @ownership-reads))
          (is (true? @ownership-observed?))
          (is (= [(:tick-id completed)] @missing-lookups) "Only the post-ownership cleanup lookup returns unavailable")
          (is (= :success (:root-status completed)))
          (is (= :success (:status result)))
          (is (= 5 (get-in result [:outputs :result])))
          (is (fn? (tree/lookup-ephemeral-fn key)) "Unavailable bindings cannot be treated as known ephemeral-only")
          (is (= (:recoverable-fn configured)
                 (:orc/recoverable-fn (meta (tree/lookup-ephemeral-fn key)))))
          (finally (when key (tree/clear-ephemeral-fn! key))))))))
