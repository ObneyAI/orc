(ns ai.obney.orc.orc-service.execution-budget-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.execution-budget :as execution-budget])
  (:import [java.util.concurrent Future TimeUnit TimeoutException]))

(defn- await-future
  [future timeout-ms timeout-value]
  (try
    (.get ^Future future timeout-ms TimeUnit/MILLISECONDS)
    (catch TimeoutException _
      timeout-value)))

(deftest initial-ownership-loss-is-established-before-monitor-construction-returns
  (testing "a lost initial fence cannot release campaign work ahead of its loss gate"
    (let [tick-id (random-uuid)
          node-id (random-uuid)
          work-entered (promise)
          work-interrupted (promise)
          release-work (promise)
          loss-entered (promise)
          release-loss (promise)
          monitor-returned (promise)
          work
          (execution-budget/start-work!
           tick-id node-id
           #(do
              (deliver work-entered true)
              (try
                @release-work
                (catch InterruptedException _
                  (deliver work-interrupted true)))))
          caller
          (future
            (deliver
             monitor-returned
             (execution-budget/cancel-work-when!
              work
              (constantly false)
              #(throw (ex-info "a lost initial fence must not wait" {}))
              #(do
                 (deliver loss-entered true)
                 @release-loss))))]
      (try
        (is (= true (deref work-entered 1000 ::work-not-entered)))
        (is (= true (deref loss-entered 1000 ::loss-not-entered)))
        (is (= ::monitor-returned-before-loss
               (deref monitor-returned 100 ::monitor-returned-before-loss))
            "monitor construction remains fenced until loss is established")
        (deliver release-loss true)
        (let [monitor-handle
              (deref monitor-returned 1000 ::monitor-not-returned)]
          (is (= :ownership-lost (:initial-state monitor-handle)))
          (is (= true
                 (deref work-interrupted 1000 ::work-not-interrupted))))
        (finally
          (deliver release-loss true)
          (deliver release-work true)
          (future-cancel caller)
          (execution-budget/cancel-active-work! tick-id))))))

(deftest stopping-an-ownership-monitor-awaits-its-thread-exit
  (testing "worker cleanup cannot report completion while its monitor remains alive"
    (let [tick-id (random-uuid)
          node-id (random-uuid)
          release-work (promise)
          monitor-waiting (promise)
          monitor-interrupted (promise)
          release-monitor-exit (promise)
          stop-returned (promise)
          work
          (execution-budget/start-work!
           tick-id node-id
           #(deref release-work))
          monitor-handle
          (execution-budget/cancel-work-when!
           work
           (constantly true)
           #(do
              (deliver monitor-waiting true)
              (try
                @(promise)
                (catch InterruptedException interrupted
                  (deliver monitor-interrupted true)
                  @release-monitor-exit
                  (throw interrupted))))
           (constantly nil))]
      (try
        (is (= :owned (:initial-state monitor-handle)))
        (is (= true (deref monitor-waiting 1000 ::monitor-not-waiting)))
        (let [stopper
              (Thread.
               (fn []
                 ;; Lease cancellation can leave the worker interrupted before
                 ;; it enters finally. Cleanup must still join the monitor and
                 ;; then restore this status for its caller.
                 (.interrupt (Thread/currentThread))
                 (deliver
                  stop-returned
                  (try
                    {:result
                     (execution-budget/stop-ownership-monitor! monitor-handle)
                     :interrupt-restored?
                     (.isInterrupted (Thread/currentThread))}
                    (catch Throwable failure
                      failure)))))]
          (.start stopper)
          (is (= true
                 (deref monitor-interrupted 1000 ::monitor-not-interrupted)))
          (is (= ::stop-returned-before-exit
                 (deref stop-returned 100 ::stop-returned-before-exit))
              "bounded stop waits for the monitor's actual finally path")
          (deliver release-monitor-exit true)
          (is (= {:result true :interrupt-restored? true}
                 (deref stop-returned 1000 ::stop-not-returned)))
          (.join stopper 1000)
          (is (false? (.isAlive stopper))))
        (is (= true (deref (:exited monitor-handle) 1000 ::monitor-not-exited)))
        (is (false? (.isAlive ^Thread (:thread monitor-handle))))
        (finally
          (deliver release-monitor-exit true)
          (deliver release-work true)
          (execution-budget/cancel-active-work! tick-id))))))

(deftest ownership-monitor-is-armed-before-campaign-work-may-continue
  (testing "monitor construction waits for the initial live ownership fence"
    (let [tick-id (random-uuid)
          node-id (random-uuid)
          work-entered (promise)
          release-work (promise)
          ownership-check-entered (promise)
          release-ownership-check (promise)
          monitor-waiting (promise)
          release-monitor (promise)
          monitor-returned (promise)
          work
          (execution-budget/start-work!
           tick-id node-id
           #(do
              (deliver work-entered true)
              @release-work))
          caller
          (future
            (deliver
             monitor-returned
             (execution-budget/cancel-work-when!
              work
              #(do
                 (deliver ownership-check-entered true)
                 @release-ownership-check
                 true)
              #(do
                 (deliver monitor-waiting true)
                 @release-monitor)
              (constantly nil))))]
      (try
        (is (= true (deref work-entered 1000 ::work-not-entered)))
        (is (= true
               (deref ownership-check-entered 1000
                      ::ownership-check-not-entered)))
        (is (= ::monitor-not-armed
               (deref monitor-returned 100 ::monitor-not-armed))
            "the caller cannot advance while the initial ownership check is pending")
        (deliver release-ownership-check true)
        (is (= true (deref monitor-waiting 1000 ::monitor-not-waiting)))
        (let [monitor (deref monitor-returned 1000 ::monitor-not-returned)]
          (is (instance? Future monitor))
          (future-cancel monitor))
        (finally
          (deliver release-ownership-check true)
          (deliver release-monitor true)
          (deliver release-work true)
          (future-cancel caller)
          (execution-budget/cancel-active-work! tick-id))))))

(deftest older-work-cleanup-cannot-remove-a-newer-registration
  (testing "the active [tick,node] slot remains owned by the newer work"
    (let [tick-id (random-uuid)
          node-id (random-uuid)
          old-entered (promise)
          new-entered (promise)
          new-interrupted (promise)
          new-finished (promise)
          release-old (promise)
          release-new (promise)
          old-work
          (execution-budget/start-work!
           tick-id node-id
           #(do
              (deliver old-entered true)
              @release-old
              :old-complete))]
      (try
        (is (= true (deref old-entered 1000 ::old-not-entered)))
        (let [_new-work
              (execution-budget/start-work!
               tick-id node-id
               #(do
                  (deliver new-entered true)
                  (try
                    @release-new
                    (catch InterruptedException _
                      (deliver new-interrupted true))
                    (finally
                      (deliver new-finished true)))))]
          (is (= true (deref new-entered 1000 ::new-not-entered)))
          (deliver release-old true)
          (is (= :old-complete (deref old-work 1000 ::old-not-finished)))
          (execution-budget/cancel-active-work! tick-id)
          (is (= true (deref new-interrupted 1000 ::new-not-interrupted))
              "old cleanup preserved the newer cancellation target")
          (is (= true (deref new-finished 1000 ::new-not-finished))))
        (finally
          (deliver release-old true)
          (deliver release-new true)
          (execution-budget/cancel-active-work! tick-id))))))

(deftest delayed-old-monitor-cannot-cancel-a-replacement-registration
  (testing "the monitor retains the exact older Future identity across slot reuse"
    (let [tick-id (random-uuid)
          node-id (random-uuid)
          owner? (atom true)
          old-entered (promise)
          release-old (promise)
          monitor-waiting (promise)
          release-monitor (promise)
          lease-loss-observed (promise)
          new-entered (promise)
          new-interrupted (promise)
          new-finished (promise)
          release-new (promise)
          old-work
          (execution-budget/start-work!
           tick-id node-id
           #(do
              (deliver old-entered true)
              @release-old
              :old-complete))]
      (try
        (is (= true (deref old-entered 1000 ::old-not-entered)))
        (let [old-monitor
              (execution-budget/cancel-work-when!
               old-work
               #(true? @owner?)
               #(do
                  (deliver monitor-waiting true)
                  @release-monitor)
               #(deliver lease-loss-observed true))]
          (is (= true (deref monitor-waiting 1000 ::monitor-not-waiting)))
          (deliver release-old true)
          (is (= :old-complete (deref old-work 1000 ::old-not-finished)))
          (let [_new-work
                (execution-budget/start-work!
                 tick-id node-id
                 #(do
                    (deliver new-entered true)
                    (try
                      @release-new
                      (catch InterruptedException _
                        (deliver new-interrupted true))
                      (finally
                        (deliver new-finished true)))))]
            (is (= true (deref new-entered 1000 ::new-not-entered)))
            (reset! owner? false)
            (deliver release-monitor true)
            (is (nil? (await-future old-monitor 1000 ::monitor-not-finished)))
            (is (= ::lease-loss-not-observed
                   (deref lease-loss-observed 100
                          ::lease-loss-not-observed))
                "a settled old Future does not report loss for a replacement")
            (is (= ::new-not-interrupted
                   (deref new-interrupted 100 ::new-not-interrupted))
                "the delayed old monitor cannot cancel the newer Future")
            (execution-budget/cancel-active-work! tick-id)
            (is (= true (deref new-interrupted 1000 ::new-not-interrupted))
                "the replacement remains the ledger's real cancellation target")
            (is (= true (deref new-finished 1000 ::new-not-finished)))))
        (finally
          (deliver release-old true)
          (deliver release-monitor true)
          (deliver release-new true)
          (execution-budget/cancel-active-work! tick-id))))))

(deftest throwing-ownership-predicate-fails-closed
  (testing "an unreadable live lease cannot leave registered work running"
    (let [tick-id (random-uuid)
          node-id (random-uuid)
          work-entered (promise)
          work-interrupted (promise)
          work-finished (promise)
          release-work (promise)
          lease-loss-observed (promise)
          wait-calls (atom 0)
          work
          (execution-budget/start-work!
           tick-id node-id
           #(do
              (deliver work-entered true)
              (try
                @release-work
                (catch InterruptedException _
                  (deliver work-interrupted true))
                (finally
                  (deliver work-finished true)))))]
      (try
        (is (= true (deref work-entered 1000 ::work-not-entered)))
        (let [monitor
              (execution-budget/cancel-work-when!
               work
               #(throw (ex-info "lease projection unavailable" {}))
               #(swap! wait-calls inc)
               #(deliver lease-loss-observed true))]
          (is (= true
                 (deref lease-loss-observed 1000 ::lease-loss-not-observed)))
          (is (= true (deref work-interrupted 1000 ::work-not-interrupted)))
          (is (= true (deref work-finished 1000 ::work-not-finished)))
          (is (not= ::monitor-not-finished
                    (await-future monitor 1000 ::monitor-not-finished)))
          (is (zero? @wait-calls)))
        (finally
          (deliver release-work true)
          (execution-budget/cancel-active-work! tick-id))))))

(deftest throwing-monitor-wait-fails-closed
  (testing "a broken wait capability cannot leave registered work running"
    (let [tick-id (random-uuid)
          node-id (random-uuid)
          work-entered (promise)
          work-interrupted (promise)
          work-finished (promise)
          release-work (promise)
          lease-loss-observed (promise)
          ownership-checks (atom 0)
          work
          (execution-budget/start-work!
           tick-id node-id
           #(do
              (deliver work-entered true)
              (try
                @release-work
                (catch InterruptedException _
                  (deliver work-interrupted true))
                (finally
                  (deliver work-finished true)))))]
      (try
        (is (= true (deref work-entered 1000 ::work-not-entered)))
        (let [monitor
              (execution-budget/cancel-work-when!
               work
               #(do (swap! ownership-checks inc) true)
               #(throw (ex-info "monitor scheduler unavailable" {}))
               #(deliver lease-loss-observed true))]
          (is (= true
                 (deref lease-loss-observed 1000 ::lease-loss-not-observed)))
          (is (= true (deref work-interrupted 1000 ::work-not-interrupted)))
          (is (= true (deref work-finished 1000 ::work-not-finished)))
          (is (not= ::monitor-not-finished
                    (await-future monitor 1000 ::monitor-not-finished)))
          (is (= 1 @ownership-checks)))
        (finally
          (deliver release-work true)
          (execution-budget/cancel-active-work! tick-id))))))

(deftest current-worker-monitor-wait-failure-cancels-that-exact-worker
  (testing "the production in-worker monitor fails closed after its wait capability breaks"
    (let [tick-id (random-uuid)
          node-id (random-uuid)
          monitor-started (promise)
          lease-loss-observed (promise)
          work-interrupted (promise)
          work-finished (promise)
          release-work (promise)]
      (try
        (execution-budget/start-work!
         tick-id node-id
         #(try
            (execution-budget/cancel-work-when!
             (execution-budget/current-work)
             (constantly true)
             (fn []
               (deliver monitor-started true)
               (throw (ex-info "monitor scheduler unavailable" {})))
             (fn [] (deliver lease-loss-observed true)))
            @release-work
            (catch InterruptedException _
              (deliver work-interrupted true))
            (finally
              (deliver work-finished true))))
        (is (= true (deref monitor-started 1000 ::monitor-not-started)))
        (is (= true
               (deref lease-loss-observed 1000 ::lease-loss-not-observed)))
        (is (= true (deref work-interrupted 1000 ::work-not-interrupted))
            "loss of the monitor's scheduling capability interrupts its own registered worker")
        (is (= true (deref work-finished 1000 ::work-not-finished)))
        (finally
          (deliver release-work true)
          (execution-budget/cancel-active-work! tick-id))))))
