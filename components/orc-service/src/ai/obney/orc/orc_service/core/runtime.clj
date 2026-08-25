(ns ai.obney.orc.orc-service.core.runtime
  "Runtime for executing behavior trees via async pipeline.

   This module provides:
   - `execute` - Dispatch execution to async pipeline and wait for completion
   - `build-execution-snapshot` - Load sheet, resolve version, build snapshot
   - Completion registry for sync callers waiting on async execution"
  (:require [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.execution-budget :as execution-budget]
            [ai.obney.orc.orc-service.core.profile :as profile]
            [ai.obney.orc.orc-service.core.trace-time :as trace-time]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]))

(defn- timeout-node-path [nodes-by-id node-id]
  (loop [current node-id path () seen #{}]
    (if (or (nil? current) (contains? seen current))
      (vec path)
      (let [node (get nodes-by-id current)]
        (recur (:parent-id node) (conj path (:name node)) (conj seen current))))))

(defn- partial-timeout-node-traces
  "Reconstruct partial node history from durable lifecycle events. Active
   attempts enrich the durable timeout trace; lifecycle identity and completed
   outcomes always come from the event log."
  [context tick-id completed-at attempts]
  (let [tick-ctx (rm/get-tick-execution-context context tick-id)
        nodes-by-id (:nodes-by-id tick-ctx)
        events (into [] (es/read (:event-store context)
                                {:tenant-id (:tenant-id context)
                                 :tags #{[:tick tick-id]}}))
        started (filter #(= :sheet/node-execution-started (:event/type %)) events)
        completed (filter #(= :sheet/node-execution-completed (:event/type %)) events)
        execution-context (fn [event]
                            (select-keys (or (:inputs event) {})
                                         [:ai.obney.orc.orc-service.core.todo-processors/map-each-index
                                          :ai.obney.orc.orc-service.core.todo-processors/map-each-parent]))
        execution-key (fn [event] [(:node-id event) (execution-context event)])
        started-by (into {} (map (juxt execution-key identity) started))
        completed-by (into {} (map (juxt execution-key identity) completed))]
    (vec
     (keep
      (fn [start]
        (when-let [node (get nodes-by-id (:node-id start))]
          (let [completion (get completed-by (execution-key start))
                active-attempt (get attempts [(:node-id start) (execution-context start)])
                parent-start (when-let [parent-id (:parent-id node)]
                               (or (get started-by [parent-id (execution-context start)])
                                   (get started-by [parent-id {}])))
                timed-out? (nil? completion)]
            (cond-> {:node-id (:node-id start)
                     :trace-instance-id (:event/id start)
                     :node-name (:name node)
                     :node-type (:type node)
                     :path (timeout-node-path nodes-by-id (:node-id start))
                     :status (if timed-out? :timeout (:status completion))
                     :started-at (str (:event/timestamp start))
                     :completed-at (str (or (:event/timestamp completion) completed-at))}
              (:executor node) (assoc :executor (:executor node))
              (:parent-id node) (assoc :parent-id (:parent-id node))
              parent-start (assoc :parent-trace-instance-id (:event/id parent-start))
              (:duration-ms completion) (assoc :duration-ms (:duration-ms completion))
              (seq (:read-keys completion))
              (assoc :read-keys (:read-keys completion)
                     :input-profile (:input-profile completion))
              (seq (:write-keys completion))
              (assoc :write-keys (:write-keys completion)
                     :output-profile (:write-profile completion))
              (seq (:rejected-write-keys completion))
              (assoc :rejected-write-keys (:rejected-write-keys completion)
                     :rejected-output-profile (:rejected-write-profile completion))
              (:error completion) (assoc :error (:error completion))
              timed-out? (assoc :error "Execution timed out")
              active-attempt (merge active-attempt)
              (and timed-out? (nil? active-attempt))
              (assoc :node-attempt 1
                     :max-node-attempts (or (get-in node [:retry :max-attempts]) 1)
                     :execution-budget-remaining-ms 0)))))
      started))))

;; =============================================================================
;; C-2c-2 — auto-classification envelope helper
;;
;; The wedge in todo_processors.clj dispatches :ontology/assign-task-class
;; which emits :ontology/task-classified tagged with [:tick tick-id]. After
;; a tick completes, we query by that tag and fold the latest match into
;; the run-result envelope as :auto-classification.
;; =============================================================================

(defn collect-tick-classification
  "Query the event store for :ontology/task-classified events tagged with
   [:tick tick-id]; if any, return the latest as a run-result envelope
   map {:tree-id :confidence :top-candidates :was-fresh-mint?}. Returns
   nil when no classification event exists for this tick."
  [context tick-id]
  (try
    (when-let [event-store (:event-store context)]
      (let [events (->> (es/read event-store
                                  {:tenant-id (:tenant-id context)
                                   :types #{:ontology/task-classified}
                                   :tags #{[:tick tick-id]}})
                        (into []))]
        (when-let [e (last events)]
          {:tree-id (:assigned-tree-id e)
           :confidence (:confidence e)
           :top-candidates (vec (take 3 (:top-candidates e)))
           :was-fresh-mint? (:was-fresh-mint? e)})))
    (catch Exception _ nil)))

;; =============================================================================
;; Snapshot Parsing for Published Version Execution
;; =============================================================================

(defn- parse-snapshot-nodes
  "Parse a nested snapshot tree into a flat nodes-by-id map.
   Generates deterministic UUIDs based on tree position for consistent execution."
  [snapshot-node parent-id index path]
  (when snapshot-node
    (let [;; Generate a deterministic UUID based on path
          node-id (java.util.UUID/nameUUIDFromBytes
                   (.getBytes (str path) "UTF-8"))
          children (or (:children snapshot-node) [])
          node-record {:id node-id
                       :type (:type snapshot-node)
                       :name (:name snapshot-node)
                       :parent-id parent-id
                       :children-ids (mapv (fn [i _]
                                             (java.util.UUID/nameUUIDFromBytes
                                              (.getBytes (str path "/" i) "UTF-8")))
                                           (range (count children))
                                           children)
                       :status :idle
                       ;; Leaf fields
                       :instruction (:instruction snapshot-node)
                       :reads (or (:reads snapshot-node) [])
                       :writes (or (:writes snapshot-node) [])
                       :decorators []
                       :executor (:executor snapshot-node)
                       :model (:model snapshot-node)
                       :fn (:fn snapshot-node)
                       :tools (:tools snapshot-node)
                       :options (:options snapshot-node)
                       :retry (:retry snapshot-node)
                       ;; Condition fields
                       :check (:check snapshot-node)
                       ;; Parallel fields
                       :success-policy (:success-policy snapshot-node)
                       :failure-policy (:failure-policy snapshot-node)
                       ;; Map-each fields
                       :source-key (:source-key snapshot-node)
                       :item-key (:item-key snapshot-node)
                       :output-key (:output-key snapshot-node)
                       :max-concurrency (:max-concurrency snapshot-node)
                       :preserve-failures? (:preserve-failures? snapshot-node)
                       ;; Repl-researcher fields
                       :mcp-tools (or (:mcp-tools snapshot-node) [])
                       :max-iterations (:max-iterations snapshot-node)
                       ;; Delegate fields. Published snapshots use the public
                       ;; export names for limits; runtime nodes use the
                       ;; durable internal names consumed by the processor.
                       :target-sheet-id (:target-sheet-id snapshot-node)
                       :delegate-timeout-ms (or (:delegate-timeout-ms snapshot-node)
                                                (:timeout-ms snapshot-node))
                       :delegate-max-ticks (or (:delegate-max-ticks snapshot-node)
                                               (:max-ticks snapshot-node))
                       :inherit-ontology? (if (contains? snapshot-node :inherit-ontology?)
                                            (:inherit-ontology? snapshot-node)
                                            true)
                       ;; Ontology context injection
                       :context (:context snapshot-node)}
          ;; Recursively parse children
          child-records (mapcat (fn [i child]
                                  (parse-snapshot-nodes child node-id i (str path "/" i)))
                                (range)
                                children)]
      (cons [node-id node-record] child-records))))

(defn- parse-snapshot-for-execution
  "Parse a version snapshot into the format expected by execute.
   Returns {:nodes-by-id {...} :root-id uuid :blackboard {...}}"
  [snapshot]
  (let [snapshot-nodes (:nodes snapshot)
        blackboard-schema (:blackboard-schema snapshot)
        ;; Parse nodes
        node-pairs (parse-snapshot-nodes snapshot-nodes nil 0 "root")
        nodes-by-id (into {} node-pairs)
        ;; Get root ID (first node)
        root-id (when (seq node-pairs) (first (first node-pairs)))
        ;; Build blackboard from schema (values will be set from inputs)
        blackboard (reduce (fn [bb [k schema]]
                            (assoc bb k {:key k
                                         :schema schema
                                         :value nil
                                         :version 0}))
                          {}
                          blackboard-schema)]
    {:nodes-by-id nodes-by-id
     :root-id root-id
     :blackboard blackboard}))

;; =============================================================================
;; Execution Snapshot Builder
;; =============================================================================

(defn build-execution-snapshot
  "Load sheet, resolve version, and build an executable snapshot.

   This is a pure read-model query — no side effects. Returns a map with:
     :sheet-id      - UUID of the sheet
     :sheet-name    - Name of the sheet
     :nodes-by-id   - Map of node-id -> node record
     :root-node-id  - UUID of the root node
     :blackboard-entries - Map of key-name -> {:key, :schema, :value, :version}
     :version-number - Version number if using published version (nil for draft)

   When :sheet-tenant-id is provided, reads sheet definitions from that tenant
   instead of the context's :tenant-id. This enables cross-tenant execution where
   workflows live in a system tenant but executions run in user tenants.

   Or returns an anomaly map if the sheet/version is not found."
  [context sheet-id & {:keys [use-version force-draft instruction-overrides sheet-tenant-id]}]
  (let [read-ctx (if sheet-tenant-id
                   (assoc context :tenant-id sheet-tenant-id)
                   context)
        sheet (rm/get-sheet read-ctx sheet-id)]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      :else
      (let [execution-mode (or (:execution-mode sheet) :draft)
            version-to-use (cond
                             use-version use-version
                             force-draft nil
                             (= :published execution-mode) (:published-version sheet)
                             :else nil)
            version-snapshot (when version-to-use
                               (rm/get-version read-ctx sheet-id version-to-use))
            {:keys [nodes-by-id root-id blackboard-entries version-number]}
            (if version-snapshot
              (let [parsed (parse-snapshot-for-execution (:snapshot version-snapshot))]
                {:nodes-by-id (:nodes-by-id parsed)
                 :root-id (:root-id parsed)
                 :blackboard-entries (:blackboard parsed)
                 :version-number (:version-number version-snapshot)})
              {:nodes-by-id (rm/get-nodes-by-id read-ctx sheet-id)
               :root-id (:root-node-id sheet)
               :blackboard-entries (rm/get-blackboard-by-key read-ctx sheet-id)
               :version-number nil})]
        (if-not root-id
          {:cognitect.anomalies/category :cognitect.anomalies/not-found
           :cognitect.anomalies/message "Sheet has no root node"}
          (cond-> {:sheet-id sheet-id
                   :sheet-name (:name sheet)
                   :nodes-by-id nodes-by-id
                   :root-node-id root-id
                   :blackboard-entries blackboard-entries
                   :version-number version-number}
            (seq instruction-overrides)
            (assoc :instruction-overrides instruction-overrides)))))))

;; =============================================================================
;; Completion Registry (for sync callers waiting on async execution)
;; =============================================================================

(defonce ^:private completion-registry (atom {}))
(defonce ^:private ephemeral-context-registry (atom {}))

(defn ephemeral-context-for [tick-id]
  (get @ephemeral-context-registry tick-id))

(defn forget-ephemeral-context! [tick-id]
  (swap! ephemeral-context-registry dissoc tick-id))

(defn- durable-trace-lineage
  [context tick-id]
  (let [store (:event-store context)
        tenant-id (:tenant-id context)
        started (fn [id]
                  (first (into [] (es/read store {:tenant-id tenant-id
                                                  :types #{:sheet/tree-tick-started}
                                                  :tags #{[:tick id]}}))))
        parent-id (:parent-tick-id (started tick-id))
        root-id (loop [id tick-id]
                  (if-let [parent (:parent-tick-id (started id))]
                    (recur parent)
                    id))
        child-ids (mapv :tick-id
                        (into [] (es/read store {:tenant-id tenant-id
                                                :types #{:sheet/tree-tick-started}
                                                :tags #{[:parent-tick tick-id]}})))]
    {:parent-trace-id parent-id
     :root-trace-id root-id
     :child-trace-ids child-ids}))

(defn timeout-execution!
  "Make a caller deadline terminal, interrupt active work, and synchronously
   persist the partial event-derived trace before returning.

   The optional opts map accepts :error — an override for the durable trace's
   error string (and the cancel reason), used by `expire-run!` so a WEDGED
   run's trace carries the liveness attribution instead of the generic
   \"Execution timed out\". The returned map keeps :status :timeout either
   way; wedge-distinct RESULT shaping is `expire-run!`'s job."
  ([context sheet-id tick-id inputs duration-ms]
   (timeout-execution! context sheet-id tick-id inputs duration-ms nil))
  ([context sheet-id tick-id inputs duration-ms {:keys [error]}]
  (let [error (or error "Execution timed out")
        active-attempts (execution-budget/attempts-for-tick tick-id)
        cancel-command-time (time/now)
        cancel-result (cp/process-command
                       (assoc context :command
                              {:command/id (random-uuid)
                               :command/timestamp cancel-command-time
                               :command/name :sheet/cancel-tick
                               :sheet-id sheet-id :tick-id tick-id
                               :reason error}))
        cancellation-events (into [] (es/read (:event-store context)
                                              {:tenant-id (:tenant-id context)
                                               :types #{:sheet/tick-cancelled}
                                               :tags #{[:tick tick-id]}}))
        completed-at (or (:event/timestamp (last cancellation-events))
                         cancel-command-time)
        tick-events (into [] (es/read (:event-store context)
                                     {:tenant-id (:tenant-id context)
                                      :types #{:sheet/tree-tick-started}
                                      :tags #{[:tick tick-id]}}))
        started-at (or (:event/timestamp (first tick-events)) completed-at)
        started-at-str (trace-time/canonical-string started-at)
        completed-at-str (trace-time/canonical-string completed-at)
        trace-duration-ms (or (trace-time/elapsed-ms started-at completed-at)
                              duration-ms)
        node-traces (partial-timeout-node-traces context tick-id completed-at-str
                                                 active-attempts)
        {:keys [parent-trace-id root-trace-id child-trace-ids]}
        (durable-trace-lineage context tick-id)
        tick-ctx (rm/get-tick-execution-context context tick-id)
        _terminal (cp/process-command
                   (assoc context :command
                          (cond-> {:command/id (random-uuid)
                                   :command/timestamp (time/now)
                                   :command/name :sheet/emit-tick-completed
                                   :sheet-id sheet-id
                                   :tick-id tick-id
                                   :root-status :timeout
                                   :terminal-reason :timeout
                                   :error error}
                            (:correlation-id tick-ctx)
                            (assoc :correlation-id (:correlation-id tick-ctx))
                            (get-in tick-ctx [:options :max-ticks])
                            (assoc :configured-max-ticks
                                   (get-in tick-ctx [:options :max-ticks])))))
        _cancelled-work (execution-budget/cancel-active-work! tick-id)]
    (loop [attempt 0]
      (let [stored (cp/process-command
                    (assoc context :command
                           (cond-> {:command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :command/name :sheet/store-execution-trace
                                    :trace-id tick-id :sheet-id sheet-id
                                    :root-trace-id root-trace-id
                                    :child-trace-ids child-trace-ids
                                    :started-at started-at-str
                                    :completed-at completed-at-str
                                    :duration-ms trace-duration-ms :status :timeout
                                    :input-snapshot (profile/profile-values (or inputs {}))
                                    :output-snapshot {}
                                    :node-traces node-traces
                                    :error error}
                             parent-trace-id
                             (assoc :parent-trace-id parent-trace-id))))]
        (when (and (:cognitect.anomalies/category stored) (< attempt 4))
          (recur (inc attempt)))))
    (execution-budget/clear-tick! tick-id)
    (forget-ephemeral-context! tick-id)
    {:status :timeout
     :trace-id tick-id
     :error error
     :duration-ms duration-ms})))

;; =============================================================================
;; PR-4 per-run liveness (register W37, forensic evidence/pr3/PR3-FORENSIC.md)
;;
;; Every promise-deref-on-a-run seam is bounded by the run's own :timeout-ms.
;; What was missing is DISTINCTNESS at expiry: a run whose engine went SILENT
;; (Shape A: no thread executes it at all; Shape B: wedged inside a nested
;; engine run) produced the same {:status :timeout} as a live run that merely
;; overran its budget — and :timeout is a RETRYABLE status (the ontology
;; reranker retries it once, RR-1), so a wedge got retried, doubling the
;; stall. These helpers classify the expiry from the engine's own durable
;; evidence and give a wedge a distinct, attributable, non-retryable failure.
;; The completion-registry architecture is untouched (parked decision): we
;; bound and classify the waits, we do not redesign delivery.
;; =============================================================================

(def default-result-grace-ms
  "How much silence beyond the run budget the engine is granted before a run
   is declared wedged, when the caller does not pass :result-grace-ms.

   DERIVED, not invented: this is the engine's own result-delivery slack —
   execute-stream keeps a run's stream subscription alive for
   (+ timeout-ms default-result-grace-ms) (\"stream at least as long as the
   execution\"), i.e. the engine itself declares that a run's machinery may
   lag its budget by at most this much before the stream is reaped. The
   consumer-side W35 bound uses the same relation:
   (:timeout-ms execution | 300000) + (:result-grace-ms | 60000). An engine
   silent for longer than its own reaping slack is not slow — it is gone."
  60000)

(defn run-liveness-verdict
  "Classify a run whose completion-promise deref just expired: WEDGED or
   merely over budget. Reads only the engine's own evidence:

   - In-flight provider attempts (`execution-budget/attempts-for-tick`).
     A recorded attempt is a SELF-BOUNDED wait — the executor caps each
     provider request at the remaining deadline — so its presence proves
     the engine is alive inside a bounded call: NOT wedged.
   - The run's durable tick events. If the newest event is older than
     `grace-ms`, the engine went silent for longer than its own declared
     result-delivery slack (see `default-result-grace-ms`) while still
     owing a result: WEDGED. A run that never emitted a single event has
     been silent since dispatch, so its silence age is the whole wait
     (`waited-ms`) — a short-budget run whose tick simply had not started
     yet stays an ordinary :timeout instead of a false wedge.

   Registered :code work (`active-work`) is deliberately NOT liveness
   evidence — it has no self-bound, and work-that-never-finishes is exactly
   the wedge shape this classifier exists to name (the forensic's
   forever-blocking leaf). A run silent because a nested run under it is
   busy is still, at THIS seam, past its own budget with no bounded wait to
   point at; retrying it would relaunch the nested work — the observed
   W2R-4 harm — so it too classifies as wedged.

   Returns {:wedged? bool
            :in-flight-attempts n
            :last-activity-age-ms ms   ;; the whole wait when the run never
            :grace-ms ms}              ;; emitted a single event"
  [context tick-id grace-ms waited-ms]
  (let [attempts (execution-budget/attempts-for-tick tick-id)
        events (into [] (es/read (:event-store context)
                                 {:tenant-id (:tenant-id context)
                                  :tags #{[:tick tick-id]}}))
        now (time/now)
        last-activity-age-ms (or (when-let [newest (:event/timestamp (last events))]
                                   (trace-time/elapsed-ms newest now))
                                 waited-ms)
        wedged? (and (empty? attempts)
                     (>= last-activity-age-ms grace-ms))]
    {:wedged? wedged?
     :in-flight-attempts (count attempts)
     :last-activity-age-ms last-activity-age-ms
     :grace-ms grace-ms}))

(defn expire-run!
  "Handle a completion-promise deref that expired at the run's bound.

   Classifies the expiry via `run-liveness-verdict`, then runs the same
   durable containment either way (`timeout-execution!`: cancel the tick,
   interrupt active work, persist the partial trace, clear budgets).

   - Over budget but alive (recent events or an in-flight bounded provider
     attempt): the existing contract, {:status :timeout} — retryable.
   - WEDGED (silent past the run's own budget + the engine's own grace):
     a DISTINCT, attributable, NON-RETRYABLE failure:
       {:status :failure         ;; never :timeout — no retry loop matches
        :wedged? true
        :error \"Run wedged: ...\"  ;; carries no retryable fragment
        :liveness {:seam :tick-id :sheet-id :waited-ms
                   :last-activity-age-ms :grace-ms :in-flight-attempts}}
     The durable trace keeps :status :timeout (schema untouched) but its
     :error carries the wedge attribution.

   `seam` names the deref site that expired (e.g. :orc.runtime/execute)."
  [context sheet-id tick-id inputs duration-ms {:keys [grace-ms seam]}]
  (let [grace-ms (or grace-ms default-result-grace-ms)
        {:keys [wedged? in-flight-attempts last-activity-age-ms]
         :as verdict} (run-liveness-verdict context tick-id grace-ms duration-ms)]
    (if-not wedged?
      (timeout-execution! context sheet-id tick-id inputs duration-ms)
      (let [error (str "Run wedged at " seam ": engine silent for "
                       last-activity-age-ms
                       " ms during a " duration-ms " ms wait on tick " tick-id
                       " (grace " grace-ms " ms); a wedged run is terminal"
                       " and must not be retried")]
        (u/log ::run-wedged
               :seam seam
               :tick-id tick-id
               :sheet-id sheet-id
               :waited-ms duration-ms
               :last-activity-age-ms last-activity-age-ms
               :grace-ms grace-ms
               :in-flight-attempts in-flight-attempts)
        (timeout-execution! context sheet-id tick-id inputs duration-ms
                            {:error error})
        {:status :failure
         :wedged? true
         :error error
         :outputs {}
         :trace-id tick-id
         :duration-ms duration-ms
         :liveness (assoc verdict
                          :seam seam
                          :tick-id tick-id
                          :sheet-id sheet-id
                          :waited-ms duration-ms)}))))

(defn register-completion!
  "Register a promise for a tick-id. Returns the promise."
  [tick-id]
  (let [p (promise)]
    (swap! completion-registry assoc tick-id p)
    p))

(defn deliver-completion!
  "Deliver a result to any waiting promise for a tick-id."
  [tick-id result]
  (when-let [p (get @completion-registry tick-id)]
    (deliver p result)
    (swap! completion-registry dissoc tick-id)))

(defn deregister-completion!
  "Remove a tick-id's promise without delivering. Use when the upstream
   command dispatch was rejected (anomaly) and no events will ever
   resolve the promise — otherwise the caller hangs on (deref p timeout)
   for the full budget."
  [tick-id]
  (swap! completion-registry dissoc tick-id))

(defn durable-terminal-result
  "Reconstruct the result of an already-terminal tick from durable facts.
   This is the process-recovery path for callers reattaching after the
   completion promise and its observer thread were lost."
  [{:keys [event-store tenant-id] :as context} tick-id]
  (when event-store
    (when-let [completion
               (last (filter #(and (= :sheet/tree-tick-completed (:event/type %))
                                   (not= :running (:root-status %)))
                             (into [] (es/read event-store
                                               {:tenant-id tenant-id
                                                :types #{:sheet/tree-tick-completed}
                                                :tags #{[:tick tick-id]}}))))]
      (let [tick-ctx (rm/get-tick-execution-context context tick-id)]
        (cond-> {:status (case (:root-status completion)
                           :success :success
                           :failure :failure
                           :partial :partial
                           :timeout :timeout
                           :blocked :blocked
                           :tree-generated :tree-generated
                           :failure)
                 :outputs (value-log/final-values context tenant-id tick-id)
                 :output-sources (value-log/final-sources context tenant-id tick-id)
                 :trace-id tick-id
                 :error (:error completion)
                 :configured-max-ticks (:configured-max-ticks completion)
                 :consumed-ticks (:consumed-ticks completion)
                 :terminal-reason (:terminal-reason completion)}
          (:version-number tick-ctx)
          (assoc :executed-version (:version-number tick-ctx))
          (= :blocked (:root-status completion))
          (assoc :block-payload (:block-payload completion)))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn resume-in-progress!
  "Resume abandoned leaf and delegate frontiers from durable execution state.

   Intended to be called after todo processors have been rebuilt against the
   same event store. Completed nodes are never re-enqueued. Each recovery start
   durably references the abandoned start event, making repeated calls
   idempotent. Returns one result map per active leaf frontier inspected."
  [context]
  (vec
   (mapcat
    (fn [{:keys [tick-id sheet-id nodes-in-progress]}]
      (let [tick-ctx (rm/get-tick-execution-context context tick-id)
            nodes-by-id (:nodes-by-id tick-ctx)
            tick-events (into [] (es/read (:event-store context)
                                          {:tenant-id (:tenant-id context)
                                           :tags #{[:tick tick-id]}}))]
        (keep
         (fn [node-id]
           (when (contains? #{:leaf :delegate} (:type (get nodes-by-id node-id)))
             (when-let [start (last (filter #(and (= :sheet/node-execution-started
                                                      (:event/type %))
                                                   (= node-id (:node-id %)))
                                             tick-events))]
               (when-not (:resumed-from-event-id start)
                 (let [result (cp/process-command
                             (assoc context :command
                                    {:command/id (random-uuid)
                                     :command/timestamp (time/now)
                                     :command/name :sheet/resume-node-execution
                                     :sheet-id sheet-id
                                     :tick-id tick-id
                                     :node-id node-id
                                     :original-start-event-id (:event/id start)
                                     :inputs (:inputs start)}))]
                 {:tick-id tick-id
                  :sheet-id sheet-id
                  :node-id node-id
                  :original-start-event-id (:event/id start)
                  :resumed? (boolean (seq (:command-result/events result)))
                  :command-result result})))))
         nodes-in-progress)))
    (rm/get-all-active-executions context))))

(defn execute
  "Execute a sheet (behavior tree) by dispatching to async pipeline and waiting.

   This is a blocking call that:
   1. Dispatches a tick-tree command with an execution snapshot
   2. Waits for the async pipeline to complete (todo processors)
   3. Returns the result delivered by the completion registry

   Args:
     context - Map with :event-store, :pubsub, and optional :llm-provider
     sheet-id - UUID of the sheet to execute
     inputs - Map of blackboard key -> value for initial inputs

   Options:
     :timeout-ms - Max execution time in ms (default 300000 = 5 minutes)
     :result-grace-ms - Silence tolerance used to classify an expired wait
                        as WEDGED vs merely over budget (default
                        `default-result-grace-ms`, the engine's own
                        stream-reaping slack; see `run-liveness-verdict`)
     :use-version - Specific version number to execute (overrides execution-mode)
     :force-draft - Force draft execution even if execution-mode is :published
     :trace? - Enable tracing (passed to async pipeline via options)
     :langfuse-client - Langfuse client (passed to async pipeline via options)
     :store-trace? - Store trace in event store (default true, passed via options)
     :max-ticks - Override re-tick budget for this execution (defaults to *max-tick-iterations*)
     :tick-id - Optional caller-supplied execution id for correlating live progress
     :parent-tick-id - Optional lineage marker when this execution is a child of
                       another tick (RLM Phase 2 trees, delegate nodes)
     :correlation-id - Optional UUID grouping independent root executions into
                       one caller-defined operation. Overrides
                       :orc/correlation-id on context.
     :input-sources - Internal {key {:tick-id :event-id}} provenance map; values
                      already durable elsewhere are referenced, not copied
     :return-references? - Internal flag to include :output-sources for delegates
     :llm-call-budget - Max LLM calls before failing (opt-in only, NO default)
     :durability-mode - Internal comparison mode; :legacy restores per-node
                        routing lifecycle events (default uses summarized routing)

   Returns:
     {:status :success | :failure | :timeout
      :outputs {\"key\" value ...}
      :duration-ms 1234
      :error string?             ;; Present if status is :failure
      :executed-version ...      ;; Version number if published version was used
      :wedged? true              ;; Present only when the run was declared
      :liveness {...}}           ;; wedged — see `expire-run!` (non-retryable)"
  [context sheet-id inputs & {:keys [timeout-ms result-grace-ms use-version force-draft
                                      trace? langfuse-client store-trace?
                                      max-ticks llm-call-budget tick-id parent-tick-id
                                      delegate-parent-sheet-id delegate-parent-node-id
                                      delegate-parent-exec-context delegate-parent-read-inputs
                                      delegate-parent-read-sources
                                      correlation-id input-sources return-references?
                                      durability-mode]
                               :or {timeout-ms 300000
                                    result-grace-ms default-result-grace-ms
                                    store-trace? true}}]
  (let [correlation-id (or correlation-id (:orc/correlation-id context))
        tick-id (or tick-id (random-uuid))
        p (register-completion! tick-id)
        _ (when-let [ephemeral (not-empty (select-keys context [:mcp-session :call-tool-fn]))]
            (swap! ephemeral-context-registry assoc tick-id ephemeral))
        start-time (System/currentTimeMillis)
        cmd-result (cp/process-command
                     (assoc context :command
                            (cond-> {:command/id (random-uuid)
                                     :command/timestamp (time/now)
                                     :command/name :sheet/tick-tree
                                     :sheet-id sheet-id
                                     :tick-id tick-id
                                     :inputs (or inputs {})
                                     :options (cond-> {:timeout-ms timeout-ms
                                                        :execution-deadline-ms (+ start-time timeout-ms)
                                                        :store-trace? store-trace?}
                                                 trace? (assoc :trace? true)
                                                 langfuse-client (assoc :langfuse-client langfuse-client)
                                                 max-ticks (assoc :max-ticks max-ticks)
                                                 delegate-parent-sheet-id
                                                 (assoc :delegate-parent-sheet-id delegate-parent-sheet-id)
                                                 delegate-parent-node-id
                                                 (assoc :delegate-parent-node-id delegate-parent-node-id)
                                                 (seq delegate-parent-exec-context)
                                                 (assoc :delegate-parent-exec-context delegate-parent-exec-context)
                                                 (seq delegate-parent-read-inputs)
                                                 (assoc :delegate-parent-read-inputs delegate-parent-read-inputs)
                                                 (seq delegate-parent-read-sources)
                                                 (assoc :delegate-parent-read-sources delegate-parent-read-sources)
                                                 llm-call-budget (assoc :llm-call-budget llm-call-budget)
                                                 durability-mode (assoc :durability-mode durability-mode))}
                              parent-tick-id (assoc :parent-tick-id parent-tick-id)
                              correlation-id (assoc :correlation-id correlation-id)
                              (seq input-sources) (assoc :input-sources input-sources)
                              use-version (assoc :use-version use-version)
                              force-draft (assoc :force-draft force-draft)
                              ;; CE-6c (ADR 0018): parity with execute-stream's
                              ;; CE-5b FIX A — carry the OPAQUE :tool-context
                              ;; off the execute context onto the root
                              ;; :sheet/tick-tree command so it survives the
                              ;; async command -> event -> tick-execution-context
                              ;; read model boundary and can be read back at
                              ;; node/leaf depth. Absent -> not carried
                              ;; (backward-compatible; non-coding turns see
                              ;; no change).
                              (:tool-context context)
                              (assoc :tool-context (:tool-context context)))))]
    (if (:cognitect.anomalies/category cmd-result)
      ;; Command failed (e.g., sheet not found, no root node)
      (do (swap! completion-registry dissoc tick-id)
          (forget-ephemeral-context! tick-id)
          {:status :failure
           :error (:cognitect.anomalies/message cmd-result)
           :duration-ms (- (System/currentTimeMillis) start-time)})
      ;; Wait for async completion
      (let [result (or (durable-terminal-result context tick-id)
                       (deref p timeout-ms ::timeout))
            duration-ms (- (System/currentTimeMillis) start-time)]
        (swap! completion-registry dissoc tick-id)
        (if (= result ::timeout)
          (expire-run! context sheet-id tick-id inputs duration-ms
                       {:grace-ms result-grace-ms
                        :seam :orc.runtime/execute})
          (cond-> (assoc (if return-references?
                           result
                           (dissoc result :output-sources))
                         :duration-ms duration-ms)
            ;; Fold the C-2c-2 auto-classification envelope when an
            ;; :ontology/task-classified event was emitted during this tick.
            (collect-tick-classification context tick-id)
            (assoc :auto-classification
                   (collect-tick-classification context tick-id))))))))
