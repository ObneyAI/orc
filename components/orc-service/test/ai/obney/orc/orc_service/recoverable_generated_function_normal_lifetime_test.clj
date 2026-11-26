(ns ai.obney.orc.orc-service.recoverable-generated-function-normal-lifetime-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.recoverable-generated-function-test :as source]
            [ai.obney.orc.orc-service.core.logic :as service-logic]
            [ai.obney.orc.orc-service.core.todo-processors :as service-processors]))
(deftest normal-child-completion-preserves-an-unrelated-replacement-binding
  (let [entered (promise) release (promise)]
    (h/with-async-test-context
      [ctx {:context {:owned-gate (fn []
                                   (deliver entered true)
                                   (when-not (= true (deref release 5000 :timeout))
                                     (throw (ex-info "Owned normal-completion gate was not released" {}))))}}]
      (let [sctx (service-processors/build-rlm-context {:blackboard {} :durable-source-required? true})
            emission (service-logic/execute-rlm-code sctx
                       "(emit-tree! '[:code {:reads [:n] :writes [:result] :output-schemas {:result :int} :fn (fn [{:keys [inputs owned-gate]}] (owned-gate) {:result (inc (:n inputs))})}])")
            work (future (service-processors/execute-tree (:generated-tree @(:sandbox-vars sctx)) ctx {:blackboard {:n 4} :timeout-ms 8000}))
            key* (atom nil)]
        (try
          (is (nil? (:error emission)))
          (is (= true (deref entered 5000 :timeout)))
          (let [configured (first (filter #(= :sheet/node-executor-set (:event/type %)) (h/read-all-events ctx)))
                key (:fn configured)
                different (source/binding-for "(fn [_] {:result 999})")]
            (reset! key* key)
            (is (some? (:recoverable-fn configured)))
            (service-logic/clear-ephemeral-fn! key)
            (let [replacement (service-logic/restore-ephemeral-fn! key different)]
              (deliver release true)
              (is (= :success (:status (deref work 10000 {:status :timeout}))))
              (is (identical? replacement (service-logic/lookup-ephemeral-fn key))
                  "Normal completion must release only its own matching source binding")))
          (finally
            (deliver release true)
            (deref work 10000 nil)
            (when @key* (service-logic/clear-ephemeral-fn! @key*))))))))

(deftest normal-child-completion-retains-a-callable-needed-by-another-live-tick
  (let [first-entered (promise) second-entered (promise)
        first-release (promise) second-release (promise) first-tick* (atom nil)]
    (h/with-async-test-context
      [ctx {:context {:owned-gate (fn [tick]
                                   (let [first? (or (= tick @first-tick*)
                                                    (compare-and-set! first-tick* nil tick))
                                         entered (if first? first-entered second-entered)
                                         release (if first? first-release second-release)]
                                     (deliver entered true)
                                     (when-not (= true (deref release 10000 :timeout))
                                       (throw (ex-info "Owned live-user gate was not released" {})))))}}]
      (let [sctx (service-processors/build-rlm-context {:blackboard {} :durable-source-required? true})
            emission (service-logic/execute-rlm-code sctx
                       "(emit-tree! '[:code {:reads [:n] :writes [:result] :output-schemas {:result :int} :fn (fn [{:keys [inputs owned-gate tick-id]}] (owned-gate tick-id) {:result (inc (:n inputs))})}])")
            first-work (future (service-processors/execute-tree (:generated-tree @(:sandbox-vars sctx)) ctx {:blackboard {:n 4} :timeout-ms 15000}))
            second-work* (atom nil) key* (atom nil)]
        (try
          (is (nil? (:error emission)))
          (is (= true (deref first-entered 5000 :timeout)))
          (let [configured (first (filter #(= :sheet/node-executor-set (:event/type %)) (h/read-all-events ctx)))
                sheet (:sheet-id configured) key (:fn configured)
                registered (service-logic/lookup-ephemeral-fn key) second-tick (random-uuid)]
            (reset! key* key)
            (reset! second-work* (future (orc/execute ctx sheet {:n 4} :tick-id second-tick :timeout-ms 15000 :force-draft true)))
            (is (= true (deref second-entered 5000 :timeout)))
            (deliver first-release true)
            (is (= :success (:status (deref first-work 5000 {:status :timeout}))))
            (is (identical? registered (service-logic/lookup-ephemeral-fn key)) "The second live tick retains its callable")
            (deliver second-release true)
            (is (= :success (:status (deref @second-work* 5000 {:status :timeout}))))
            (service-processors/execute-tree nil ctx {:stable-tick-id second-tick :timeout-ms 1000})
            (is (nil? (service-logic/lookup-ephemeral-fn key))))
          (finally
            (deliver first-release true) (deliver second-release true)
            (deref first-work 10000 nil)
            (when @second-work* (deref @second-work* 10000 nil))
            (when @key* (service-logic/clear-ephemeral-fn! @key*))))))))
