(ns ai.obney.orc.orc-service.execution-lease-recovery-test
  "Generated from ExecutionRecovery invariants OwnedWorkIsNotAbandoned and
   AbandonedWorkStaysRecoverable (specs/orc-service.allium).

   Reproduces the ordinary-recovery handoff defect through the public runtime:
   a healthy ordinary code leaf held at a gate must never be invoked a second
   time by recovery. Ownership is per worker instance (`:orc/instance-id` in
   the context, defaulting to the real process identity); the lease length is
   `:orc/execution-lease-ms`. A stopped leaf processor stands in for an owner
   that is gone: its starts are recorded but never run or renewed."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.periodic-task.interface :as periodic]
            [ai.obney.grain.todo-processor-v2.interface :as tp]))

(def invocations (atom 0))
(def gate (atom (promise)))

(defn gated-double
  "Ordinary consumer code that blocks until the test releases it."
  [{:keys [inputs]}]
  (swap! invocations inc)
  (when (= ::timeout (deref @gate 20000 ::timeout))
    (throw (ex-info "test gate never released" {})))
  {:out (* 2 (:n inputs))})

(defn- fq [f] (str "ai.obney.orc.orc-service.execution-lease-recovery-test/" f))

(defn- workflow [workflow-name]
  (sheet/workflow workflow-name
    (sheet/blackboard {:n :int :out :int})
    (sheet/code "double" :fn (fq "gated-double") :reads [:n] :writes [:out])))

(defn- sheet-events [ctx sheet-id]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :tags #{[:sheet sheet-id]}})))

(defn- node-id-of [ctx sheet-id]
  (some #(when (= "double" (:name %)) (:id %)) (sheet/get-nodes-for-sheet ctx sheet-id)))

(defn- starts [ctx sheet-id node-id]
  (filterv #(and (= :sheet/node-execution-started (:event/type %)) (= node-id (:node-id %)))
           (sheet-events ctx sheet-id)))

(defn- completions [ctx sheet-id node-id]
  (filterv #(and (= :sheet/node-execution-completed (:event/type %)) (= node-id (:node-id %)))
           (sheet-events ctx sheet-id)))

(defn- renewals [ctx sheet-id node-id]
  (filterv #(and (= :sheet/node-execution-lease-renewed (:event/type %)) (= node-id (:node-id %)))
           (sheet-events ctx sheet-id)))

(defn- resumed [results] (filterv :resumed? results))

(defn- reset-gate! []
  (reset! invocations 0)
  (reset! gate (promise)))

(defn- restart-leaf-processor! [ctx]
  (let [{:keys [handler-fn topics]} (get @tp/processor-registry* :sheet/execute-leaf-node)]
    (tp/start {:event-pubsub (:event-pubsub ctx) :topics topics :handler-fn handler-fn
               :context (dissoc ctx :processors)})))

;; ---------------------------------------------------------------------------
;; OwnedWorkIsNotAbandoned
;; ---------------------------------------------------------------------------

(deftest healthy-running-leaf-is-never-invoked-twice
  (testing "recovery while the original invocation is still running resumes nothing"
    (reset-gate!)
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (workflow "lease-healthy"))
            node-id (node-id-of ctx sheet-id)
            run (future (sheet/execute ctx sheet-id {:n 21} :timeout-ms 30000))]
        (is (h/settle-until! #(= 1 @invocations) :timeout-ms 10000))
        (is (empty? (resumed (sheet/resume-in-progress! ctx))))
        (is (empty? (resumed (sheet/resume-in-progress! ctx))) "repeated recovery too")
        (is (= 1 @invocations))
        (is (= 1 (count (starts ctx sheet-id node-id))) "no resumed start is recorded")
        (is (not (realized? run)) "the original caller is still pending")
        (deliver @gate :go)
        (let [result (deref run 20000 ::timeout)]
          (is (= :success (:status result)))
          (is (= 42 (get-in result [:outputs :out])))
          (is (= 1 @invocations))
          (is (= 1 (count (completions ctx sheet-id node-id)))))))))

(deftest start-carries-its-ownership-lease
  (reset-gate!)
  (let [instance (random-uuid)]
    (h/with-async-test-context [ctx {:context {:orc/instance-id instance
                                               :orc/execution-lease-ms 5000}}]
      (let [sheet-id (sheet/build-workflow! ctx (workflow "lease-on-start"))
            node-id (node-id-of ctx sheet-id)
            run (future (sheet/execute ctx sheet-id {:n 1} :timeout-ms 30000))]
        (is (h/settle-until! #(= 1 (count (starts ctx sheet-id node-id))) :timeout-ms 10000))
        (let [start (first (starts ctx sheet-id node-id))]
          (is (= instance (:lease-owner start)))
          (is (some? (:lease-expires-at start))))
        (deliver @gate :go)
        (deref run 20000 ::timeout)))))

(deftest long-running-leaf-renews-its-lease-and-is-left-to-finish
  (testing "a leaf running far past one lease length keeps it renewed; another worker's recovery waits"
    (reset-gate!)
    (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                               :orc/execution-lease-ms 300}}]
      (let [sheet-id (sheet/build-workflow! ctx (workflow "lease-renewal"))
            node-id (node-id-of ctx sheet-id)
            other-worker (assoc ctx :orc/instance-id (random-uuid))
            run (future (sheet/execute ctx sheet-id {:n 5} :timeout-ms 30000))]
        (is (h/settle-until! #(= 1 @invocations) :timeout-ms 10000))
        (Thread/sleep 1200)
        (is (<= 2 (count (renewals ctx sheet-id node-id))) "the owner renews durably")
        (is (empty? (resumed (sheet/resume-in-progress! other-worker)))
            "another worker sees a live lease and leaves the work alone")
        (deliver @gate :go)
        (let [result (deref run 20000 ::timeout)]
          (is (= :success (:status result)))
          (is (= 1 @invocations))
          (is (= 1 (count (starts ctx sheet-id node-id)))))))))

(deftest queued-start-under-a-live-foreign-lease-is-not-resumed
  (testing "a recorded start that no worker has picked up yet is owned from the moment it is recorded"
    (reset-gate!)
    (deliver @gate :go)
    (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                               :orc/execution-lease-ms 1500}}]
      (let [leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
            _ (tp/stop leaf-processor)
            sheet-id (sheet/build-workflow! ctx (workflow "lease-queued"))
            node-id (node-id-of ctx sheet-id)
            other-worker (assoc ctx :orc/instance-id (random-uuid))
            run (future (sheet/execute ctx sheet-id {:n 4} :timeout-ms 30000))]
        (is (h/settle-until! #(= 1 (count (starts ctx sheet-id node-id))) :timeout-ms 10000))
        (is (empty? (resumed (sheet/resume-in-progress! other-worker)))
            "the lease is live: the start is not abandoned")
        (future-cancel run)))))

;; ---------------------------------------------------------------------------
;; AbandonedWorkStaysRecoverable
;; ---------------------------------------------------------------------------

(deftest expired-foreign-lease-is-resumed-once
  (testing "after the owner's lease expires another worker resumes it exactly once"
    (reset-gate!)
    (deliver @gate :go)
    (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                               :orc/execution-lease-ms 600}}]
      (let [leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
            _ (tp/stop leaf-processor)
            sheet-id (sheet/build-workflow! ctx (workflow "lease-expired"))
            node-id (node-id-of ctx sheet-id)
            recoverer-id (random-uuid)
            recoverer (assoc ctx :orc/instance-id recoverer-id)
            run (future (sheet/execute ctx sheet-id {:n 8} :timeout-ms 30000))]
        (is (h/settle-until! #(= 1 (count (starts ctx sheet-id node-id))) :timeout-ms 10000))
        (Thread/sleep 1000)
        ;; A live worker must be listening before recovery publishes the
        ;; resumed start (pub/sub delivery is not replayed to late subscribers).
        (let [restarted (restart-leaf-processor! ctx)]
          (try
            (is (= 1 (count (resumed (sheet/resume-in-progress! recoverer)))))
            (is (empty? (resumed (sheet/resume-in-progress! recoverer))) "idempotent")
            (let [[original resumed-start] (starts ctx sheet-id node-id)]
              (is (= (:event/id original) (:resumed-from-event-id resumed-start)))
              (is (= recoverer-id (:lease-owner resumed-start)) "the resumed start is itself leased"))
            (let [result (deref run 20000 ::timeout)]
              (is (= :success (:status result)))
              (is (= 16 (get-in result [:outputs :out])))
              (is (= 1 @invocations)))
            (finally (tp/stop restarted))))))))

(deftest own-queued-start-is-not-resumed-under-its-live-lease
  (testing "a start this worker recorded but has not yet run is healthy queued work, not abandoned"
    (reset-gate!)
    (deliver @gate :go)
    (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                               :orc/execution-lease-ms 60000}}]
      (let [leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
            _ (tp/stop leaf-processor)
            sheet-id (sheet/build-workflow! ctx (workflow "lease-own-queued"))
            node-id (node-id-of ctx sheet-id)
            run (future (sheet/execute ctx sheet-id {:n 3} :timeout-ms 30000))]
        (is (h/settle-until! #(= 1 (count (starts ctx sheet-id node-id))) :timeout-ms 10000))
        (is (empty? (resumed (sheet/resume-in-progress! ctx)))
            "the owner's own queued start is under a live lease")
        (future-cancel run)))))

(deftest own-start-is-resumed-once-its-lease-expires
  (testing "a start whose owner stopped running it is resumed once its lease lapses"
    (reset-gate!)
    (deliver @gate :go)
    (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                               :orc/execution-lease-ms 500}}]
      (let [leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
            _ (tp/stop leaf-processor)
            sheet-id (sheet/build-workflow! ctx (workflow "lease-own"))
            node-id (node-id-of ctx sheet-id)
            run (future (sheet/execute ctx sheet-id {:n 3} :timeout-ms 30000))]
        (is (h/settle-until! #(= 1 (count (starts ctx sheet-id node-id))) :timeout-ms 10000))
        (Thread/sleep 900)
        (let [restarted (restart-leaf-processor! ctx)]
          (try
            (is (= 1 (count (resumed (sheet/resume-in-progress! ctx)))))
            (is (= 6 (get-in (deref run 20000 ::timeout) [:outputs :out])))
            (finally (tp/stop restarted))))))))

(deftest resumed-start-whose-resumer-stops-is-recoverable-in-turn
  (testing "a resumed start is leased, and resumed again once its lease expires"
    (reset-gate!)
    (deliver @gate :go)
    (h/with-async-test-context [ctx {:context {:orc/instance-id (random-uuid)
                                               :orc/execution-lease-ms 500}}]
      (let [leaf-processor (get-in ctx [:processors :sheet/execute-leaf-node])
            _ (tp/stop leaf-processor)
            sheet-id (sheet/build-workflow! ctx (workflow "lease-chain"))
            node-id (node-id-of ctx sheet-id)
            first-recoverer (assoc ctx :orc/instance-id (random-uuid))
            second-recoverer (assoc ctx :orc/instance-id (random-uuid))
            run (future (sheet/execute ctx sheet-id {:n 10} :timeout-ms 30000))]
        (is (h/settle-until! #(= 1 (count (starts ctx sheet-id node-id))) :timeout-ms 10000))
        (Thread/sleep 900)
        (is (= 1 (count (resumed (sheet/resume-in-progress! first-recoverer)))))
        (Thread/sleep 900)
        (let [restarted (restart-leaf-processor! ctx)]
          (try
            (is (= 1 (count (resumed (sheet/resume-in-progress! second-recoverer))))
                "the first resumer stopped too; its lease expired")
            (is (empty? (resumed (sheet/resume-in-progress! second-recoverer))))
            (let [[_ first-resume second-resume] (starts ctx sheet-id node-id)]
              (is (= (:event/id first-resume) (:resumed-from-event-id second-resume))))
            (is (= 20 (get-in (deref run 20000 ::timeout) [:outputs :out])))
            (is (= 1 @invocations))
            (finally (tp/stop restarted))))))))

(deftest real-periodic-recovery-scan-leaves-a-healthy-leaf-alone
  (testing "the handoff reproduction: the actual registered recovery trigger fires while a healthy leaf runs"
    (reset-gate!)
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (workflow "lease-real-scheduler"))
            node-id (node-id-of ctx sheet-id)
            run (future (sheet/execute ctx sheet-id {:n 21} :timeout-ms 30000))
            scans #(into [] (es/read (:event-store ctx)
                                     {:tenant-id (:tenant-id ctx)
                                      :types #{:sheet/recovery-scan-triggered}}))]
        (is (h/settle-until! #(= 1 @invocations) :timeout-ms 10000))
        (let [triggers (periodic/start-periodic-triggers!
                        {:append-fn #(es/append (:event-store ctx) %)
                         :tenant-ids-fn #(set (keys (es/tenants (:event-store ctx))))})]
          (try
            (is (h/settle-until! #(seq (scans)) :timeout-ms 10000)
                "the registered startup recovery scan actually fired")
            ;; Give the recovery processor time to act on the scan.
            (Thread/sleep 1500)
            (is (= 1 @invocations) "the healthy leaf is not invoked a second time")
            (is (= 1 (count (starts ctx sheet-id node-id))) "no resumed start was recorded")
            (finally (periodic/stop-periodic-triggers! triggers))))
        (deliver @gate :go)
        (let [result (deref run 20000 ::timeout)]
          (is (= :success (:status result)))
          (is (= 42 (get-in result [:outputs :out])))
          (is (= 1 @invocations)))))))
