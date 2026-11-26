(ns ai.obney.orc.orc-service.recovery-scan-quiet-test
  "Generated from ExecutionRecovery invariant
   PeriodicRecoveryWritesOnlyWhenWorkIsAbandoned (specs/orc-service.allium).

   Drives the registered periodic recovery scan the way Grain does: the
   handler is called with a tenant id and a time and its events are appended.
   A consumer that registers its tenant context gets a scan that records
   nothing unless a start is abandoned."
  {:clj-kondo/config '{:lint-as {ai.obney.orc.orc-service.recovery-scan-quiet-test/with-registered clojure.core/let}}}
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.execution-lease]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.periodic-task.interface :as periodic]
            [ai.obney.grain.todo-processor-v2.interface :as tp]))

(def invocations (atom 0))
(def gate (atom (promise)))

(defn gated-double
  [{:keys [inputs]}]
  (swap! invocations inc)
  (when (= ::timeout (deref @gate 20000 ::timeout))
    (throw (ex-info "test gate never released" {})))
  {:out (* 2 (:n inputs))})

(defn gated-item
  [{:keys [inputs]}]
  (swap! invocations inc)
  (when (= ::timeout (deref @gate 20000 ::timeout))
    (throw (ex-info "test gate never released" {})))
  {:item (* 2 (:item inputs))})

(defn- fq [f] (str "ai.obney.orc.orc-service.recovery-scan-quiet-test/" f))

(defn- workflow [workflow-name]
  (sheet/workflow workflow-name
    (sheet/blackboard {:n :int :out :int})
    (sheet/code "double" :fn (fq "gated-double") :reads [:n] :writes [:out])))

(defn- reset-gate! []
  (reset! invocations 0)
  (reset! gate (promise)))

(defn- forget-owner!
  "Stand in for an owner that died: this process's queued-lease renewer stops
   renewing every start it tracks, so only the recorded lease remains."
  []
  (reset! @#'ai.obney.orc.orc-service.core.execution-lease/queued {})
  (Thread/sleep 300))

(defn- all-events [ctx]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))

(defn- scan-triggers [ctx]
  (filterv #(= :sheet/recovery-scan-triggered (:event/type %)) (all-events ctx)))

(defn- starts [ctx]
  (filterv #(= :sheet/node-execution-started (:event/type %)) (all-events ctx)))

(defn- handler []
  (:handler-fn (get @periodic/periodic-trigger-registry* :sheet/recover-active-executions)))

(defn- beat!
  "One scheduler beat, as Grain's start-periodic-triggers! performs it."
  [ctx]
  (let [result ((handler) (:tenant-id ctx) (java.time.Instant/now))]
    (when-let [events (:result/events result)]
      (es/append (:event-store ctx)
                 (cond-> {:tenant-id (:tenant-id ctx) :events events}
                   (:result/cas result) (assoc :cas (:result/cas result)))))
    result))

(defmacro ^:private with-registered [[unreg ctx-expr] & body]
  `(let [~unreg (sheet/register-recovery-scan-context-fn! ~ctx-expr)]
     (try ~@body (finally (~unreg)))))

(defn- restart-leaf-processor! [ctx]
  (let [{:keys [handler-fn topics]} (get @tp/processor-registry* :sheet/execute-leaf-node)]
    (tp/start {:event-pubsub (:event-pubsub ctx) :topics topics :handler-fn handler-fn
               :context (dissoc ctx :processors)})))

(deftest idle-tenant-records-nothing-over-an-hour-of-scans
  (testing "120 beats (one hour at 30 s) leave the tenant's store untouched"
    (h/with-async-test-context [ctx]
      (with-registered [_ (constantly ctx)]
        (dotimes [_ 120] (beat! ctx))
        (Thread/sleep 500)
        (is (empty? (all-events ctx))
            "no trigger, no processor checkpoint, no transaction marker")))))

(deftest only-live-lease-work-records-nothing
  (testing "a healthy leaf running in this process"
    (reset-gate!)
    (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                               :orc/execution-lease-ms 60000}}]
      (with-registered [_ (constantly ctx)]
        (let [sheet-id (sheet/build-workflow! ctx (workflow "quiet-live"))
              run (future (sheet/execute ctx sheet-id {:n 21} :timeout-ms 30000))]
          (is (h/settle-until! #(= 1 @invocations) :timeout-ms 10000))
          (let [before (count (all-events ctx))]
            (dotimes [_ 20] (beat! ctx))
            (Thread/sleep 500)
            (is (empty? (scan-triggers ctx)))
            (is (= before (count (all-events ctx)))
                "scans added no event of any kind while the lease is live"))
          (deliver @gate :go)
          (is (= 42 (get-in (deref run 20000 ::timeout) [:outputs :out])))))))
  (testing "a start recorded but not yet run, under its owner's live lease"
    (reset-gate!)
    (deliver @gate :go)
    (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                               :orc/execution-lease-ms 60000}}]
      (with-registered [_ (constantly ctx)]
        (tp/stop (get-in ctx [:processors :sheet/execute-leaf-node]))
        (let [sheet-id (sheet/build-workflow! ctx (workflow "quiet-queued"))
              run (future (sheet/execute ctx sheet-id {:n 3} :timeout-ms 30000))]
          (is (h/settle-until! #(= 1 (count (starts ctx))) :timeout-ms 10000))
          (let [before (count (all-events ctx))]
            (dotimes [_ 20] (beat! ctx))
            (Thread/sleep 500)
            (is (empty? (scan-triggers ctx)))
            (is (= before (count (all-events ctx)))))
          (future-cancel run))))))

(deftest abandoned-start-is-recorded-and-resumed-as-before
  (testing "quiet while the lease is live; recorded and resumed on the first beat after it lapses"
    (reset-gate!)
    (deliver @gate :go)
    (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                               :orc/execution-lease-ms 500}}]
      (let [view (atom ctx)]
        (with-registered [_ (fn [_] @view)]
          (tp/stop (get-in ctx [:processors :sheet/execute-leaf-node]))
          (let [sheet-id (sheet/build-workflow! ctx (workflow "quiet-abandoned"))
                run (future (sheet/execute ctx sheet-id {:n 3} :timeout-ms 30000))]
            (is (h/settle-until! #(= 1 (count (starts ctx))) :timeout-ms 10000))
            (forget-owner!)
            (beat! ctx)
            (Thread/sleep 100)
            (is (empty? (scan-triggers ctx)) "lease still live: quiet")
            (Thread/sleep 800)
            (let [restarted (restart-leaf-processor! ctx)]
              (try
                (beat! ctx)
                (is (= 6 (get-in (deref run 20000 ::timeout) [:outputs :out]))
                    "the abandoned start was recovered by the recorded trigger")
                (is (= 1 (count (scan-triggers ctx))))
                (is (= 2 (count (starts ctx))) "original plus exactly one resumed start")
                (testing "repeated scans stay idempotent"
                  (beat! ctx)
                  (beat! ctx)
                  (Thread/sleep 500)
                  (is (= 1 (count (scan-triggers ctx))))
                  (is (= 2 (count (starts ctx))))
                  (is (= 1 @invocations)))
                (finally (tp/stop restarted))))))))))

(deftest startup-run-still-recovers-abandoned-work
  (testing "the real scheduler's first beat records the trigger when a start is abandoned"
    (reset-gate!)
    (deliver @gate :go)
    (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                               :orc/execution-lease-ms 500}}]
      (with-registered [_ (constantly ctx)]
        (tp/stop (get-in ctx [:processors :sheet/execute-leaf-node]))
        (let [sheet-id (sheet/build-workflow! ctx (workflow "quiet-startup"))
              run (future (sheet/execute ctx sheet-id {:n 5} :timeout-ms 30000))]
          (is (h/settle-until! #(= 1 (count (starts ctx))) :timeout-ms 10000))
          (forget-owner!)
          (Thread/sleep 800)
          (let [restarted (restart-leaf-processor! ctx)
                triggers (periodic/start-periodic-triggers!
                          {:append-fn #(es/append (:event-store ctx) %)
                           :tenant-ids-fn #(set (keys (es/tenants (:event-store ctx))))})]
            (try
              (is (= 10 (get-in (deref run 20000 ::timeout) [:outputs :out])))
              (is (seq (scan-triggers ctx)))
              (finally
                (periodic/stop-periodic-triggers! triggers)
                (tp/stop restarted)))))))))

(deftest unreadable-state-records-the-trigger
  (testing "a context function that throws"
    (h/with-async-test-context [ctx]
      (with-registered [_ (fn [_] (throw (ex-info "no context" {})))]
        (beat! ctx)
        (is (h/settle-until! #(= 1 (count (scan-triggers ctx))))))))
  (testing "a context whose projection cannot be read"
    (h/with-async-test-context [ctx]
      (with-registered [_ (fn [_] (assoc ctx :cache nil :event-store nil))]
        (beat! ctx)
        (is (h/settle-until! #(= 1 (count (scan-triggers ctx))))))))
  (testing "a tenant the consumer does not know"
    (h/with-async-test-context [ctx]
      (with-registered [_ (fn [_] nil)]
        (beat! ctx)
        (is (h/settle-until! #(= 1 (count (scan-triggers ctx)))))))))

(deftest without-registration-every-beat-is-recorded-as-before
  (h/with-async-test-context [ctx]
    (beat! ctx)
    (beat! ctx)
    (is (h/settle-until! #(= 2 (count (scan-triggers ctx)))))))

(deftest running-map-coordinator-is-recovered-once-then-scans-are-quiet
  (testing "recovery rebuilds a map coordinator it has not resumed; once resumed, scans are quiet"
    (reset-gate!)
    (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                               :orc/execution-lease-ms 60000}}]
      (with-registered [_ (constantly ctx)]
        (let [wf (sheet/workflow "quiet-map"
                   (sheet/blackboard {:items [:vector :int] :item :int :results [:vector :int]})
                   (sheet/map-each "each" :from :items :as :item :into :results :parallel 1
                     (sheet/code "item" :fn (fq "gated-item") :reads [:item] :writes [:item])))
              sheet-id (sheet/build-workflow! ctx wf)
              run (future (sheet/execute ctx sheet-id {:items [1 2]} :timeout-ms 30000))]
          (is (h/settle-until! #(= 1 @invocations) :timeout-ms 10000))
          (beat! ctx)
          (is (h/settle-until! #(= 1 (count (scan-triggers ctx)))) "unresumed coordinator: recorded")
          (Thread/sleep 800)
          (let [before (count (all-events ctx))]
            (dotimes [_ 10] (beat! ctx))
            (Thread/sleep 500)
            (is (= 1 (count (scan-triggers ctx))) "coordinator resumed; leaf lease live: quiet")
            (is (= before (count (all-events ctx)))))
          (deliver @gate :go)
          (is (= [2 4] (get-in (deref run 20000 ::timeout) [:outputs :results]))))))))
