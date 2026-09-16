(ns ai.obney.orc.orc-service.core.execution-budget
  "Shared wall-clock deadline and active-attempt state for workflow execution."
  (:import [java.util.concurrent Callable Future FutureTask]))

(defonce ^:private active-attempts (atom {}))
(defonce ^:private active-work (atom {}))
(def ^:dynamic ^:private *registered-work* nil)

(def ^:private monitor-startup-timeout-ms 5000)
(def ^:private monitor-stop-timeout-ms 5000)

(defrecord OwnershipMonitor [^Future monitor thread initial-state exited]
  Future
  (cancel [_ may-interrupt-if-running]
    (.cancel monitor may-interrupt-if-running))
  (isCancelled [_]
    (.isCancelled monitor))
  (isDone [_]
    (.isDone monitor))
  (get [_]
    (.get monitor))
  (get [_ timeout unit]
    (.get monitor timeout unit)))

(defn remaining-ms [deadline-ms]
  (when deadline-ms
    (- (long deadline-ms) (System/currentTimeMillis))))

(defn record-attempt! [tick-id node-id state]
  (when (and tick-id node-id)
    (swap! active-attempts assoc [tick-id node-id (or (:exec-context state) {})] state))
  state)

(defn attempts-for-tick [tick-id]
  (into {} (for [[[candidate-tick-id node-id exec-context] state] @active-attempts
                 :when (= tick-id candidate-tick-id)]
             [[node-id exec-context] state])))

(defn clear-node! [tick-id node-id]
  (when (and tick-id node-id)
    (swap! active-attempts
           (fn [attempts]
             (into {} (remove (fn [[[candidate-tick-id candidate-node-id _] _]]
                                (and (= tick-id candidate-tick-id)
                                     (= node-id candidate-node-id)))
                              attempts))))))

(defn clear-tick! [tick-id]
  (swap! active-attempts
         (fn [attempts]
           (into {} (remove (fn [[[candidate-tick-id _ _] _]]
                              (= tick-id candidate-tick-id))
                            attempts))))
  (swap! active-work dissoc tick-id))

(defn register-work! [tick-id node-id work]
  (swap! active-work assoc-in [tick-id node-id] work)
  work)

(defn current-work
  "Return the exact registered Future bound to the current worker thread.

   This is intentionally identity-based rather than a lookup by tick/node: a
   later quantum may already have replaced that mutable slot."
  []
  *registered-work*)

(defn deregister-work!
  "Forget work only when it is still the registration for this node.

   A later quantum may replace the same [tick-id node-id] slot before an older
   future finishes. Identity checking prevents the older future's cleanup from
   erasing the newer cancellation target."
  [tick-id node-id work]
  (swap! active-work
         (fn [work-by-tick]
           (if (identical? work (get-in work-by-tick [tick-id node-id]))
             (let [remaining (dissoc (get work-by-tick tick-id) node-id)]
               (if (seq remaining)
                 (assoc work-by-tick tick-id remaining)
                 (dissoc work-by-tick tick-id)))
             work-by-tick)))
  nil)

(defn start-work!
  "Start f asynchronously after registering its future for cancellation.

   The start gate closes the create-future/register race: f cannot complete
   and run cleanup before its future is visible in active-work."
  [tick-id node-id f]
  (let [start-signal (promise)
        work-ref (promise)
        work (future
               (try
                 @start-signal
                 (binding [*registered-work* @work-ref]
                   (f))
                 (finally
                   (deregister-work! tick-id node-id @work-ref))))]
    (deliver work-ref work)
    (register-work! tick-id node-id work)
    (deliver start-signal true)
    work))

(defmacro registered-future
  "Use the cancellation ledger only for work that opted into registration.

   The false branch expands to the original `(future body...)` expression so
   callers outside the registered boundary keep their prior behavior."
  [registered? tick-id node-id & body]
  `(if ~registered?
     (start-work! ~tick-id ~node-id (fn [] ~@body))
     (future ~@body)))

(defn cancel-work-when!
  "Monitor active? and cooperatively cancel the exact supplied Future on loss.

   wait-for-next-check! is an injected blocking capability. Production uses a
   timed wait; tests can release a barrier without scheduler-duration claims.
   The monitor owns a daemon thread rather than sharing Clojure's process-global
   future executor: unrelated application futures cannot prevent the ownership
   fence from starting. Construction does not return until that thread has made
   its initial live ownership check, so campaign work cannot outrun an unarmed
   monitor. Returns a Future-compatible OwnershipMonitor containing the
   authoritative :initial-state and an exit signal for bounded cleanup."
  [work active? wait-for-next-check! on-loss!]
  (let [initial-fence (promise)
        exited (promise)
        monitoring-current-work? (identical? work (current-work))
        monitor
        (FutureTask.
         ^Callable
         (fn []
           (try
             (loop [initial? true]
               (if (future-done? work)
                 (when initial?
                   (deliver initial-fence :work-settled))
                 (let [owned?
                       (try
                         (boolean (active?))
                         (catch Throwable _
                           false))]
                   (if owned?
                     (do
                       (when initial?
                         (deliver initial-fence :owned))
                       (wait-for-next-check!)
                       (recur false))
                     (do
                       (on-loss!)
                       (when initial?
                         (deliver initial-fence :ownership-lost))
                       ;; The current worker uses :initial-state as its gate,
                       ;; so interrupting it before that gate can run is both
                       ;; unnecessary and hostile to its finally cleanup.
                       (when-not (and initial? monitoring-current-work?)
                         (future-cancel work)))))))
             (catch InterruptedException _
               ;; Normal worker cleanup interrupts a monitor blocked in its wait.
               (deliver initial-fence :interrupted)
               nil)
             (catch Throwable _
               ;; A broken wait capability cannot safely assert continuing ownership.
               ;; Reaching this catch means the authoritative initial check
               ;; already returned :owned and the failure came from the wait;
               ;; unlike initial lease loss, it is now safe and necessary to
               ;; interrupt even when this monitor was created by the worker.
               (on-loss!)
               (deliver initial-fence :failed)
               (future-cancel work))
             (finally
               (deliver exited true)))))
        monitor-thread
        (doto (Thread. monitor "orc-researcher-lease-monitor")
          (.setDaemon true))]
    (.start monitor-thread)
    (let [initial-state
          (deref initial-fence monitor-startup-timeout-ms :startup-timeout)]
      (when (= :startup-timeout initial-state)
        ;; A blocked ownership source is not evidence of ownership. Fail
        ;; closed, interrupt the monitor, and let the caller skip its body.
        (on-loss!)
        (future-cancel monitor))
      (->OwnershipMonitor monitor monitor-thread
                          (if (= :startup-timeout initial-state)
                            :failed
                            initial-state)
                          exited))))

(defn stop-ownership-monitor!
  "Interrupt monitor and prove its daemon thread exited within a bounded wait.

   Injected wait capabilities must honour interruption. A false return would
   silently turn cleanup into a thread leak, so failure is surfaced. Cleanup
   itself is uninterruptible within the bound and restores the caller's prior
   or newly observed interrupt status before returning."
  [{:keys [monitor thread]}]
  (let [deadline-ns (+ (System/nanoTime)
                       (* monitor-stop-timeout-ms 1000000))
        interrupted? (volatile! false)
        timeout-error
        #(ex-info "Researcher ownership monitor did not stop"
                  {:timeout-ms monitor-stop-timeout-ms
                   :thread-name (some-> ^Thread thread .getName)})]
    (try
      (when monitor
        (future-cancel monitor))
      ;; Callable.finally runs just before FutureTask.run and Thread.run return.
      ;; Only joining the captured raw thread proves the lifecycle itself has
      ;; ended. Lease cancellation may already have interrupted this caller, so
      ;; consume interruptions while joining and restore that status afterward.
      (when thread
        (loop []
          (when (.isAlive ^Thread thread)
            (let [remaining-ns (- deadline-ns (System/nanoTime))]
              (when-not (pos? remaining-ns)
                (throw (timeout-error)))
              (try
                (.join ^Thread thread
                       (max 1 (quot remaining-ns 1000000)))
                (catch InterruptedException _
                  (vreset! interrupted? true)))
              (recur)))))
      true
      (finally
        (when @interrupted?
          (.interrupt (Thread/currentThread)))))))

(defn cancel-active-work! [tick-id]
  (doseq [[_ work] (get @active-work tick-id)]
    (future-cancel work))
  (swap! active-work dissoc tick-id))
