(ns ai.obney.orc.orc-service.recoverable-generated-function-lifecycle-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.recoverable-generated-function-test :as source]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as sandbox]))

(deftest terminal-generated-child-reconstruction-cleans-only-its-matching-binding
  (h/with-async-test-context [ctx]
           (let [binding (source/binding-for "(fn [{:keys [inputs]}] {:result (inc (:n inputs))})")
                 different (source/binding-for "(fn [{:keys [inputs]}] {:result (dec (:n inputs))})")
                 key (str "ephemeral-fn-" (random-uuid))
                 sheet (orc/build-workflow! ctx
                         (orc/workflow (str "recoverable-cleanup-" (random-uuid))
                           (orc/blackboard
                            {:n [:int {:description "Original input quantity."}]
                             :result [:int {:description "Computed quantity."}]})
                           (orc/code "compute" :fn "clojure.core/identity" :reads [:n] :writes [:result])))
                 node (first (orc/get-nodes-for-sheet ctx sheet))
                 tick (random-uuid)]
             (try
               (h/run-and-apply! ctx
                {:command/name :sheet/set-node-executor :command/id (random-uuid)
                 :command/timestamp (java.time.OffsetDateTime/now)
                 :sheet-id sheet :node-id (:id node) :executor :code :fn key :recoverable-fn binding})
               (is (= :success (:status (orc/execute ctx sheet {:n 4} :tick-id tick :timeout-ms 5000 :force-draft true))))
               (is (fn? (tree/lookup-ephemeral-fn key)) "Keep the binding until child terminal reconstruction")
               (is (= {:result 5} (select-keys (:outputs (tree/execute-tree nil ctx {:stable-tick-id tick :timeout-ms 1000})) [:result])))
               (is (nil? (tree/lookup-ephemeral-fn key)) "Release this completed child's restored callable")
               ;; A different registration under that key belongs to someone else;
               ;; terminal reconstruction must not delete it.
               (tree/clear-ephemeral-fn! key)
               (let [replacement (tree/restore-ephemeral-fn! key different)]
                 (tree/execute-tree nil ctx {:stable-tick-id tick :timeout-ms 1000})
                 (is (identical? replacement (tree/lookup-ephemeral-fn key))))
               (finally (tree/clear-ephemeral-fn! key))))))

(deftest completing-one-tick-keeps-a-shared-callable-needed-by-another-live-tick
  (let [entered (promise) release (promise)]
    (h/with-async-test-context
      [ctx {:context {:probe-wait (fn []
                                   (deliver entered true)
                                   (when-not (= true (deref release 5000 :timeout))
                                     (throw (ex-info "Owned shared-tick gate was not released" {}))))}}]
      (let [binding (source/binding-for "(fn [{:keys [inputs probe-wait]}] (when (:wait inputs) (probe-wait)) {:result (inc (:n inputs))})")
            key (str "ephemeral-fn-" (random-uuid))
            sheet (orc/build-workflow! ctx
                    (orc/workflow (str "shared-recoverable-key-" (random-uuid))
                      (orc/blackboard {:n [:int {:description "Original input."}]
                                       :wait [:boolean {:description "Whether this owned test invocation pauses."}]
                                       :result [:int {:description "Computed result."}]})
                      (orc/code "compute" :fn "clojure.core/identity" :reads [:n :wait] :writes [:result])))
            node (first (orc/get-nodes-for-sheet ctx sheet))
            first-tick (random-uuid) second-tick (random-uuid)
            work (atom nil)]
        (try
          (h/run-and-apply! ctx
           {:command/name :sheet/set-node-executor :command/id (random-uuid)
            :command/timestamp (java.time.OffsetDateTime/now)
            :sheet-id sheet :node-id (:id node) :executor :code :fn key :recoverable-fn binding})
          (is (= :success (:status (orc/execute ctx sheet {:n 4 :wait false} :tick-id first-tick :timeout-ms 5000 :force-draft true))))
          (let [registered (tree/lookup-ephemeral-fn key)]
            (reset! work (future (orc/execute ctx sheet {:n 4 :wait true} :tick-id second-tick :timeout-ms 5000 :force-draft true)))
            (is (= true (deref entered 3000 :timeout)))
            (tree/execute-tree nil ctx {:stable-tick-id first-tick :timeout-ms 1000})
            (is (identical? registered (tree/lookup-ephemeral-fn key)) "The other live tick still owns this callable")
            (deliver release true)
            (is (= :success (:status (deref @work 5000 {:status :timeout}))))
            (tree/execute-tree nil ctx {:stable-tick-id second-tick :timeout-ms 1000})
            (is (nil? (tree/lookup-ephemeral-fn key)) "Release after all same-sheet ticks become terminal"))
          (finally
            (deliver release true)
            (when @work (deref @work 5000 nil))
            (tree/clear-ephemeral-fn! key)))))))

(deftest generated-source-metadata-crosses-public-command-event-and-projection
  (h/with-async-test-context [ctx]
    (let [sctx (sandbox/build-rlm-context {:blackboard {} :durable-source-required? true})
          emitted (sandbox/execute-rlm-code sctx
                    "(emit-tree! '[:code {:reads [:n] :writes [:result] :output-schemas {:result :int} :fn (fn [{:keys [inputs]}] {:result (inc (:n inputs))})}])")
          _ (is (nil? (:error emitted)))
          canonical (:generated-tree @(:sandbox-vars sctx))
          result (tree/execute-tree canonical ctx {:blackboard {:n 4} :timeout-ms 5000})
          configurations (filter #(= :sheet/node-executor-set (:event/type %)) (h/read-all-events ctx))
          configured (first configurations)
          sheet (:sheet-id configured)
          projected (orc/get-node ctx sheet (:node-id configured))
          binding (:recoverable-fn configured)
          command {:command/name :sheet/set-node-executor :command/id (random-uuid)
                   :command/timestamp (java.time.OffsetDateTime/now) :sheet-id sheet :node-id (:node-id configured)
                   :executor :code :fn (:fn configured)}]
      (is (= 1 (count configurations)))
      (is (= :success (:status result)))
      (is (= 5 (get-in result [:outputs :result])))
      (is (= 1 (:version binding) (:context-version binding)))
      (is (= {} (:bindings binding)))
      (is (= (:recoverable-fn configured) (:recoverable-fn projected)))
      (is (= (:fn configured) (:fn projected)))
      (doseq [invalid [(assoc binding :extra :unaccepted)
                       (assoc binding :version 2) (assoc binding :context-version 2)
                       (assoc binding :sha256 "invalid") (assoc binding :bindings {:captured 4})]]
        (let [refused (h/run-and-apply! ctx (assoc command :command/id (random-uuid) :recoverable-fn invalid))]
          (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category refused)))
          (is (empty? (:command-result/events refused)))
          (is (= binding (:recoverable-fn (orc/get-node ctx sheet (:node-id configured))))))))))
