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
            [ai.obney.grain.event-store-v3.interface :as es]
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
  (contains? #{:leaf :delegate :repl-researcher} (:type node)))

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

;; ---------------------------------------------------------------------------
;; Queued starts: one renewer per process
;; ---------------------------------------------------------------------------

;; A start this worker has recorded but whose handler has not yet begun is
;; healthy QUEUED work. Its stamped lease covers one lease length; a start
;; that waits longer must keep being renewed or a periodic scan would resume
;; it while its owner lives. Queued entries are NOT live (see `live?`): they
;; only keep the durable lease renewed, until `begin!` takes over with the
;; per-work renewer, the start completes, its tick ends, or it is not found.

(defonce ^:private queued (atom {}))
(defonce ^:private queue-renewer (atom nil))
(defonce ^:private queue-lock (Object.))

(def ^:private poll-ms 100)
(def ^:private unseen-grace-multiple 3)

(defn- queued-key [tick-id node-id exec-context]
  [tick-id node-id (or exec-context {})])

(defn queued-count
  "Number of starts this process is keeping leased while they wait to run."
  []
  (count @queued))

(defn- renewer-alive? []
  (when-let [^Thread t @queue-renewer] (.isAlive t)))

(defn- lifecycle-view
  "{:ended? :start-id} for a queued entry, from the durable tick events: the
   latest open start of the node's execution that this worker owns."
  [{:keys [context tick-id node-id exec-context owner]}]
  (let [events (into [] (es/read (:event-store context)
                                 (cond-> {:tags #{[:tick tick-id]}}
                                   (:tenant-id context) (assoc :tenant-id (:tenant-id context)))))
        target [node-id (or exec-context {})]
        ended-tick? (some #(contains? #{:sheet/tree-tick-completed :sheet/tick-cancelled}
                                      (:event/type %))
                          events)
        open (reduce (fn [open e]
                       (case (:event/type e)
                         :sheet/node-execution-started
                         (if (= target (value-log/execution-key e)) (assoc open :start e) open)
                         :sheet/node-execution-completed
                         (if (= target (value-log/execution-key e)) (dissoc open :start) open)
                         open))
                     {} events)
        start (:start open)]
    {:ended? (boolean ended-tick?)
     :start-id (when (and start (= owner (:lease-owner start))) (:event/id start))}))

(defn- sweep-entry!
  "Renew one queued entry. Returns true to keep it, false to drop it."
  [k {:keys [context tick-id node-id exec-context created-ns lease-ms] :as entry}]
  (try
    (let [{:keys [ended? start-id]} (lifecycle-view entry)]
      (cond
        ended? false
        start-id (do (renew! context {:sheet-id (:sheet-id entry) :tick-id tick-id
                                      :node-id node-id :exec-context exec-context
                                      :start-event-id start-id})
                     true)
        ;; Not durable (yet): keep briefly, then forget a stamp that was never
        ;; recorded (a rejected command) instead of tracking it forever.
        :else (< (quot (- (System/nanoTime) created-ns) 1000000)
                 (* unseen-grace-multiple lease-ms))))
    (catch Throwable t
      (u/log ::queued-lease-sweep-failed :tick-id tick-id :node-id node-id
             :error (ex-message t))
      false)))

(defn- sweep! []
  (doseq [[k entry] @queued]
    (when-not (sweep-entry! k entry)
      (swap! queued dissoc k))))

(defn- run-renewer []
  (loop [last-sweep (System/nanoTime)]
    (Thread/sleep ^long poll-ms)
    (let [entries (vals @queued)
          interval-ms (if (seq entries)
                        (apply min (map #(max 1 (quot (:lease-ms %) 3)) entries))
                        poll-ms)
          now (System/nanoTime)
          due? (>= (quot (- now last-sweep) 1000000) interval-ms)
          _ (when due? (sweep!))]
      (if (locking queue-lock
            (if (empty? @queued)
              (do (reset! queue-renewer nil) false)
              true))
        (recur (if due? (System/nanoTime) last-sweep))
        nil))))

(defn- ensure-renewer! []
  (locking queue-lock
    (when-not (renewer-alive?)
      (reset! queue-renewer
              (doto (Thread. ^Runnable run-renewer "orc-queued-lease-renewer")
                (.setDaemon true)
                (.start))))))

(defn track-queued!
  "Record that this worker stamped a start for `node` and keeps it owned while
   it waits to run. No-op for nodes that are not leased."
  [context node {:keys [sheet-id tick-id node-id inputs]}]
  (when (and (leased-node? node) (:event-store context))
    (let [exec-context (value-log/exec-context inputs)
          k (queued-key tick-id node-id exec-context)]
      (swap! queued assoc k
             {:context (dissoc context :command :event :command-result)
              :sheet-id sheet-id :tick-id tick-id :node-id node-id
              :exec-context exec-context
              :owner (instance-id context)
              :lease-ms (lease-ms context)
              :created-ns (System/nanoTime)})
      (ensure-renewer!))))

(defn- untrack-queued! [k]
  (swap! queued dissoc k))

(defn stamp
  "Add the ownership lease to a leased node's start body (leaf, delegate,
   researcher) and track the start as queued so its lease keeps being renewed
   until it begins; composites and other node kinds are not leased."
  [context node body]
  (if (leased-node? node)
    (do (track-queued! context node body)
        (merge body (fields context)))
    body))

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
    (untrack-queued! k)
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

   - running in this process: never abandoned, unless the work is epoch-fenced
     (`:fenced? true`, a checkpointed researcher): its durable frontier rejects
     a stale worker's append, so a live-but-unrenewed owner (a paused worker
     whose lease has expired) is recoverable and only the lease decides
   - otherwise abandoned only once the latest lease has expired (a legacy
     lease-less start counts as expired). Ownership by this worker is NOT a
     shortcut: a start this worker recorded but has not yet run is healthy
     queued work, and a real crash means a new process with a new identity."
  ([context start renewals exec-context]
   (abandoned? context start renewals exec-context nil))
  ([context start renewals exec-context {:keys [fenced?]}]
   (and (or fenced? (not (live? (:tick-id start) (:node-id start) exec-context)))
        (let [expiry (latest-lease-expiry start renewals)]
          (or (nil? expiry)
              (.isAfter (now-instant context) expiry))))))
