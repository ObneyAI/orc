(ns ai.obney.orc.orc-service.core.execution-lease
  "Durable ownership leases for leaf and delegate starts.

   OwnedWorkIsNotAbandoned / AbandonedWorkStaysRecoverable
   (specs/orc-service.allium, contract ExecutionRecovery).

   Every leaf or delegate start records which worker owns it and when that
   ownership lapses. The owner renews the lease while the work runs. Recovery
   resumes a start only when its owner is known not to be working on it, so a
   periodic scan never begins a second invocation of healthy work.

   Capabilities, all defaulting to the real implementation and injectable
   through the context:
   - :orc/instance-id        worker identity (default: one UUID per process)
   - :orc/execution-lease-ms lease length (default: three scan intervals)
   - :orc/clock-fn           () -> java.time.Instant (default: Grain clock)"
  (:require [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u])
  (:import [java.time Instant OffsetDateTime]))

(def default-lease-ms 90000)

(defonce ^:private process-instance-id (random-uuid))

(defn instance-id [context]
  (or (:orc/instance-id context) process-instance-id))

(defn lease-ms [context]
  (or (:orc/execution-lease-ms context) default-lease-ms))

(defn now-instant ^Instant [context]
  (if-let [clock (:orc/clock-fn context)]
    (clock)
    (.toInstant ^OffsetDateTime (time/now))))

(defn fields
  "The lease a start recorded by this worker carries right now."
  [context]
  {:lease-owner (instance-id context)
   :lease-expires-at (str (.plusMillis (now-instant context) (lease-ms context)))})

(defn leased-node? [node]
  (contains? #{:leaf :delegate} (:type node)))

(defn stamp
  "Add the ownership lease to a leaf or delegate start body; composites and
   other node kinds are not leased."
  [context node body]
  (cond-> body
    (leased-node? node) (merge (fields context))))

;; ---------------------------------------------------------------------------
;; Live-work registry: exact in-process liveness
;; ---------------------------------------------------------------------------

(defonce ^:private live-work (atom {}))

(defn- work-key [tick-id node-id exec-context]
  [tick-id node-id (or exec-context {})])

(defn- exec-context-of [inputs]
  (select-keys (or inputs {})
               [:ai.obney.orc.orc-service.core.todo-processors/map-each-index
                :ai.obney.orc.orc-service.core.todo-processors/map-each-parent
                :ai.obney.orc.orc-service.core.todo-processors/tick-iteration]))

(defn live?
  "True while this process is running the execution."
  [tick-id node-id exec-context]
  (contains? @live-work (work-key tick-id node-id exec-context)))

(defn- mark-live! [k]
  (swap! live-work update k (fnil inc 0)))

(defn- unmark-live! [k]
  (swap! live-work
         (fn [work]
           (let [n (dec (get work k 0))]
             (if (pos? n) (assoc work k n) (dissoc work k))))))

;; ---------------------------------------------------------------------------
;; Renewal
;; ---------------------------------------------------------------------------

(defn- renew!
  [context {:keys [sheet-id tick-id node-id exec-context start-event-id]}]
  (try
    (let [{:keys [lease-owner lease-expires-at]} (fields context)]
      (cp/process-command
       (assoc context :command
              (cond-> {:command/id (random-uuid)
                       :command/timestamp (time/now)
                       :command/name :sheet/renew-node-execution-lease
                       :sheet-id sheet-id
                       :tick-id tick-id
                       :node-id node-id
                       :start-event-id start-event-id
                       :lease-owner lease-owner
                       :lease-expires-at lease-expires-at}
                (seq exec-context) (assoc :exec-context exec-context)))))
    (catch Throwable t
      ;; A failed renewal must never kill the work it protects.
      (u/log ::lease-renewal-failed :tick-id tick-id :node-id node-id
             :error (ex-message t)))))

(defn begin!
  "Mark one execution live in this process and start renewing its lease.
   Returns a handle for `end!`. Call on the dispatching thread, before the
   work is handed to its future, so liveness is visible from the moment the
   worker accepts the start."
  [context {:keys [tick-id node-id exec-context start-event-id] :as ids}]
  (let [k (work-key tick-id node-id exec-context)
        stop (promise)
        interval-ms (max 1 (quot (lease-ms context) 3))]
    (mark-live! k)
    (let [renewer
          (try
            (doto (Thread.
                   ^Runnable
                   (fn []
                     (loop []
                       (when (= ::wait (deref stop interval-ms ::wait))
                         (renew! context ids)
                         (recur))))
                   "orc-lease-renewer")
              (.setDaemon true)
              (.start))
            (catch Throwable t
              (unmark-live! k)
              (throw t)))]
      {:key k :stop stop :renewer renewer :ended? (atom false)
       :start-event-id start-event-id})))

(defn end!
  "Stop renewing and forget the execution. Idempotent per handle; always call
   from a `finally`."
  [{:keys [key stop ^Thread renewer ended?]}]
  (when (compare-and-set! ended? false true)
    (deliver stop true)
    (unmark-live! key)
    (try
      (.join renewer 5000)
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))))))

;; ---------------------------------------------------------------------------
;; Recovery decision
;; ---------------------------------------------------------------------------

(defn- parse-instant [s]
  (when s
    (try (Instant/parse s)
         (catch Exception _ (.toInstant (OffsetDateTime/parse s))))))

(defn latest-lease-expiry
  "Latest known lease expiry for a start: its own and its renewals'."
  [start renewals]
  (->> (cons (:lease-expires-at start) (map :lease-expires-at renewals))
       (keep parse-instant)
       (reduce (fn [latest i] (if (or (nil? latest) (.isAfter ^Instant i ^Instant latest)) i latest))
               nil)))

(defn abandoned?
  "True when recovery may resume `start`. `renewals` are the lease-renewed
   events naming this start.

   - running in this process: never abandoned
   - otherwise abandoned only once the latest lease has expired (a legacy
     lease-less start counts as expired). Ownership by this worker is NOT a
     shortcut: a start this worker recorded but has not yet run is healthy
     queued work, and a real crash means a new process with a new identity."
  [context start renewals exec-context]
  (and (not (live? (:tick-id start) (:node-id start) exec-context))
       (let [expiry (latest-lease-expiry start renewals)]
         (or (nil? expiry)
             (.isAfter (now-instant context) expiry)))))
