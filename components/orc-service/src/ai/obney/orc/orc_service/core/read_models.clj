(ns ai.obney.orc.orc-service.core.read-models
  "Behavior Tree Sheet read models - projections built from events.

   This namespace provides:
   - Event type sets for querying
   - Multimethod projections for sheets, nodes, blackboard, and ticks
   - defreadmodel registrations for L1/L2 caching
   - Helper functions for common queries (via rmp/project)"
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.orc.orc-service.core.logic :as logic]))

;; --- Budget facts ---

(defn- first-start [context tick-id]
  (when tick-id
    (let [result (es/read (:event-store context)
                          {:tenant-id (:tenant-id context) :tags #{[:tick tick-id]}
                           :types #{:sheet/tree-tick-started}})]
      (when (:cognitect.anomalies/category result)
        (throw (ex-info "Cannot read canonical model campaign start"
                        {:failure-kind :model-plan-incompatible :reason :canonical-start-unavailable})))
      (first (filter #(= tick-id (:tick-id %)) (into [] result))))))

(defn researcher-campaign
  "Original emitted campaign metadata from the canonical first start.
   This is an index description, not permission to dispatch a model request."
  [context tick-id]
  (logic/campaign-options (first-start context tick-id)))

(defn root
  "Resolve a bounded owner through complete canonical first-start lineage.
   Unknown/cyclic ancestry and read anomalies never imply an unlimited family."
  [context tick-id]
  (loop [tick-id tick-id seen #{} owner nil]
    (cond
      (nil? tick-id) owner
      (contains? seen tick-id)
      (throw (ex-info "Cyclic LLM budget ancestry" {:failure-kind :budget-history-unavailable}))
      :else
      (let [result (es/read (:event-store context)
                            {:tenant-id (:tenant-id context) :tags #{[:tick tick-id]}
                             :types #{:sheet/tree-tick-started}})
            _ (when (:cognitect.anomalies/category result)
                (throw (ex-info "Cannot read LLM budget ancestry" {:failure-kind :budget-history-unavailable :anomaly result})))
            start (first (into [] result))
            _ (when (and (nil? start) (seq seen))
                (throw (ex-info "Incomplete LLM budget ancestry" {:failure-kind :budget-history-unavailable :tick-id tick-id})))
            budget (get-in start [:options :llm-call-budget])
            owner (if (and (integer? budget) (pos? budget))
                    {:sheet-id (:sheet-id start) :tick-id (:tick-id start)
                     :budget budget :accounting-version (get-in start [:options :llm-budget-accounting-version])}
                    owner)]
        (recur (:parent-tick-id start) (conj seen tick-id) owner)))))

(defn start-options [context sheet-id tick-id parent-tick-id options]
  (let [parent-root (when parent-tick-id (root context parent-tick-id))
        budget (:llm-call-budget options)]
    (cond
      parent-root
      (assoc options :llm-call-budget (:budget parent-root)
                     :llm-budget-root-sheet-id (:sheet-id parent-root)
                     :llm-budget-root-tick-id (:tick-id parent-root))
      (and (integer? budget) (pos? budget))
      (assoc options :llm-budget-root-sheet-id sheet-id :llm-budget-root-tick-id tick-id
                     :llm-budget-accounting-version logic/accounting-version)
      :else options)))

(defn tags [context tick-id existing]
  (if-let [owner (root context tick-id)]
    (conj existing [:llm-budget-root (:tick-id owner)])
    existing))

(defn event
  "Construct a canonical admission fact with its dedicated family tag.
   New first starts use their already-derived options; later facts use durable lineage."
  [context {:keys [type body] :as definition}]
  (let [first? (= :sheet/tree-tick-started type)
        start (if first? body (first-start context (:tick-id body)))
        root-id (when first? (get-in body [:options :llm-budget-root-tick-id]))]
    (es/->event (update definition :tags
                        (fn [existing]
                          (into (if root-id
                                  (conj existing [:llm-budget-root root-id])
                                  (tags context (:tick-id body) existing))
                                (logic/campaign-tags start body)))))))

(defreadmodel :sheet sheets
  {:events logic/sheet-events :version 1}
  [state event] (logic/sheets* state event))

(defreadmodel :sheet nodes
  {:events logic/node-events :version 1}
  [state event] (logic/nodes* state event))

(defreadmodel :sheet blackboard
  {:events logic/blackboard-events :version 1
   :partition-fn :sheet-id
   :entity-id-fn logic/blackboard-entity-id}
  [state event] (logic/blackboard* state event))

(defreadmodel :sheet judges
  {:events logic/judge-events :version 1
   :partition-fn :sheet-id
   :entity-id-fn logic/judge-entity-id}
  [state event] (logic/judges* state event))

(defreadmodel :sheet ticks
  {:events logic/tick-events :version 1
   :partition-fn :sheet-id
   :entity-id-fn :tick-id}
  [state event] (logic/ticks* state event))

(defreadmodel :sheet versions
  {:events logic/version-events :version 1}
  [state event] (logic/versions* state event))

(defreadmodel :sheet stashes
  {:events logic/version-events :version 1}
  [state event] (logic/stashes* state event))

;; =============================================================================
;; Query Helper Functions
;; =============================================================================

(defn get-sheet
  "Get a single sheet by ID"
  [ctx sheet-id]
  (get (rmp/project ctx :sheet/sheets {:tags #{[:sheet sheet-id]}})
       sheet-id))

(defn get-sheets-all
  "Get all sheets"
  [ctx]
  (vals (rmp/project ctx :sheet/sheets)))

(defn get-sheet-by-name
  "Find a sheet by name. Returns nil if not found."
  [ctx name]
  (->> (vals (rmp/project ctx :sheet/sheets))
       (filter #(= (:name %) name))
       first))

(defn get-node
  "Get a single node by ID"
  [ctx sheet-id node-id]
  (get (rmp/project ctx :sheet/nodes {:tags #{[:sheet sheet-id]}})
       node-id))

(defn get-nodes-for-sheet
  "Get all nodes in a sheet"
  [ctx sheet-id]
  (vals (rmp/project ctx :sheet/nodes {:tags #{[:sheet sheet-id]}})))

(defn get-nodes-by-id
  "Get all nodes in a sheet as a map keyed by node-id"
  [ctx sheet-id]
  (rmp/project ctx :sheet/nodes {:tags #{[:sheet sheet-id]}}))

(defn get-blackboard-for-sheet
  "Get the blackboard for a sheet"
  [ctx sheet-id]
  (vals (rmp/project ctx :sheet/blackboard {:partition-key sheet-id})))

(defn get-blackboard-by-key
  "Get the blackboard for a sheet as a map keyed by key name, from the
   :sheet/blackboard projection. Cheap while the sheet is being built: each
   call only applies the events appended since the previous one."
  [ctx sheet-id]
  (into {}
        (map (juxt :key identity))
        (vals (rmp/project ctx :sheet/blackboard {:partition-key sheet-id}))))

(defn get-blackboard-by-key-from-events
  "The same map as `get-blackboard-by-key`, folded directly from the sheet's
   own events.

   Every blackboard event is tagged [:sheet sheet-id], so this reads exactly the
   events the sheet's partition is built from, with the same reducer
   (`blackboard`), and costs one read of THIS sheet's events however many other
   sheets the tenant holds. The single-partition projection instead re-evaluates
   every other sheet's key-declared events after its watermark on each call and
   decodes each source partition (about 5 s per call with 600 foreign events).

   Use it for a one-off read of a settled sheet, such as the snapshot a child
   execution starts from. Do NOT call it once per command while a sheet is being
   built: each call re-reads the whole sheet, so building n keys reads O(n^2)
   events; the projection is incremental and is the right read there."
  [ctx sheet-id]
  (let [events (into [] (es/read (:event-store ctx)
                                 {:tenant-id (:tenant-id ctx)
                                  :tags #{[:sheet sheet-id]}
                                  :types logic/blackboard-events}))]
    (into {}
          (map (juxt :key identity))
          (vals (logic/read-models-blackboard {} events)))))

(defn get-judges
  "Get all judges declared for a sheet as a map keyed by judge name"
  [ctx sheet-id]
  (into {}
        (map (juxt :judge-name identity))
        (vals (rmp/project ctx :sheet/judges {:partition-key sheet-id}))))

(defn get-judge
  "Get a single judge by name"
  [ctx sheet-id judge-name]
  (get (get-judges ctx sheet-id) judge-name))

(defn get-tick
  "Get a single tick by ID"
  [ctx tick-id]
  (get (rmp/project ctx :sheet/ticks {:tags #{[:tick tick-id]}})
       tick-id))

(defn is-tick-cancelled?
  "Check if a tick has been cancelled"
  [ctx tick-id]
  (let [tick (get-tick ctx tick-id)]
    (= :cancelled (:status tick))))

(defn is-tick-or-ancestor-cancelled?
  "Check if a tick — or any ancestor tick in its :parent-tick-id chain — has
   been cancelled. Catches child ticks (RLM Phase 2, delegate) spawned in
   the window where the parent's cancellation hadn't reached them; depth
   capped defensively."
  [ctx tick-id]
  (loop [t tick-id
         depth 0]
    (if (and t (< depth 16))
      (let [tick (get-tick ctx t)]
        (if (= :cancelled (:status tick))
          true
          (recur (:parent-tick-id tick) (inc depth))))
      false)))

(defn get-ticks-for-sheet
  "Get all ticks for a sheet"
  [ctx sheet-id]
  (vals (rmp/project ctx :sheet/ticks {:partition-key sheet-id})))

(defn get-current-tick
  "Get the current running tick for a sheet, if any"
  [ctx sheet-id]
  (->> (get-ticks-for-sheet ctx sheet-id)
       (filter #(= :running (:status %)))
       (sort-by :started-at)
       last))

(defn get-root-node
  "Get the root node for a sheet"
  [ctx sheet-id]
  (let [sheet (get-sheet ctx sheet-id)]
    (when-let [root-id (:root-node-id sheet)]
      (get-node ctx sheet-id root-id))))

(defn get-children
  "Get child nodes of a parent node"
  [ctx sheet-id parent-id]
  (let [parent (get-node ctx sheet-id parent-id)]
    (when parent
      (mapv #(get-node ctx sheet-id %)
            (:children-ids parent)))))

(defn get-descendants
  "Get all descendant nodes of a node (recursive)"
  [ctx sheet-id node-id]
  (let [nodes-by-id (get-nodes-by-id ctx sheet-id)]
    (letfn [(descendants [nid]
              (let [node (get nodes-by-id nid)]
                (cons node
                      (mapcat descendants (:children-ids node)))))]
      (rest (descendants node-id)))))

;; =============================================================================
;; Version Query Helpers
;; =============================================================================

(defn get-versions-for-sheet
  "Get all published versions for a sheet, sorted by version number"
  [ctx sheet-id]
  (->> (get (rmp/project ctx :sheet/versions {:tags #{[:sheet sheet-id]}})
            sheet-id)
       vals
       (sort-by :version-number)))

(defn get-version
  "Get a specific published version by version number"
  [ctx sheet-id version-number]
  (get-in (rmp/project ctx :sheet/versions {:tags #{[:sheet sheet-id]}})
          [sheet-id version-number]))

(defn get-latest-version
  "Get the latest published version for a sheet"
  [ctx sheet-id]
  (let [sheet (get-sheet ctx sheet-id)
        version-num (:published-version sheet)]
    (when version-num
      (get-version ctx sheet-id version-num))))

(defn get-stash
  "Get the stash for a sheet, if any"
  [ctx sheet-id]
  (get (rmp/project ctx :sheet/stashes {:tags #{[:sheet sheet-id]}})
       sheet-id))

(defreadmodel :sheet traces
  {:events logic/trace-events :version 1
   :partition-fn :sheet-id
   :entity-id-fn :trace-id}
  [state event] (logic/traces* state event))

;; =============================================================================
;; Trace Query Helpers
;; =============================================================================

(defn get-trace
  "Get a single execution trace by ID"
  [ctx trace-id]
  (get (rmp/project ctx :sheet/traces {:tags #{[:trace trace-id]}})
       trace-id))

(defn get-traces-for-sheet
  "Get all execution traces for a sheet"
  [ctx sheet-id]
  (vals (rmp/project ctx :sheet/traces {:partition-key sheet-id})))

(defn get-traces-for-correlation
  "Get traces carrying a caller-defined operation correlation UUID."
  [ctx correlation-id]
  (vals (rmp/project ctx :sheet/traces
                     {:tags #{[:correlation correlation-id]}})))

(defn get-traces-for-version
  "Get all execution traces for a specific version of a sheet"
  [ctx sheet-id version-number]
  (->> (get-traces-for-sheet ctx sheet-id)
       (filter #(= version-number (:version-number %)))))

(defn get-all-traces
  "Get all execution traces across all sheets"
  [ctx]
  (vals (rmp/project ctx :sheet/traces)))

(defn get-sheets-name-map
  "Get map of sheet-id -> sheet-name for denormalization"
  [ctx]
  (into {} (map (juxt :id :name) (get-sheets-all ctx))))

(defreadmodel :sheet tick-execution-contexts
  {:events logic/tick-execution-context-events :version 1
   :partition-fn :sheet-id
   :entity-id-fn :tick-id}
  [state event] (logic/tick-execution-contexts* state event))

(defn get-tick-execution-context
  "Get the full execution context for a tick-scoped execution.
   Returns nil if this is a snapshot-less UI tick."
  [ctx tick-id]
  (get (rmp/project ctx :sheet/tick-execution-contexts {:tags #{[:tick tick-id]}})
       tick-id))

(defn assessment-origin
  "The durable assessment origin (`{:assessment-id uuid}`) of a tick, or nil when
   the tick is not work done for an assessment. Read from the tick's start event
   (the origin is copied onto every child run), never from in-memory context."
  [ctx tick-id]
  (when tick-id
    (get-in (get-tick-execution-context ctx tick-id) [:options :assessment-origin])))

(defn get-tick-blackboard
  "Get the blackboard for a tick-scoped execution.
   Returns nil if this tick has no snapshot."
  [ctx tick-id]
  (:blackboard (get-tick-execution-context ctx tick-id)))

(defn get-tick-nodes-by-id
  "Get the nodes-by-id for a tick-scoped execution.
   Returns nil if this tick has no snapshot."
  [ctx tick-id]
  (:nodes-by-id (get-tick-execution-context ctx tick-id)))

(defreadmodel :sheet in-progress-executions
  {:events logic/in-progress-execution-events :version 1
   :partition-fn :sheet-id
   :entity-id-fn :tick-id}
  [state event] (logic/in-progress-executions* state event))

;; =============================================================================
;; In-Progress Execution Query Helpers
;; =============================================================================

(defn get-active-executions
  "Get all currently running executions for a sheet.
   Returns a list of execution contexts with status :running."
  [ctx sheet-id]
  (->> (rmp/project ctx :sheet/in-progress-executions {:partition-key sheet-id})
       vals
       (filter #(= :running (:status %)))))

(defn get-all-active-executions
  "Get all currently running executions across all sheets.
   Returns a list of execution contexts with status :running."
  [ctx]
  (->> (rmp/project ctx :sheet/in-progress-executions)
       vals
       (filter #(= :running (:status %)))))

(defn get-execution-context
  "Get the execution context for a specific tick.
   Returns nil if not found."
  [ctx tick-id]
  (get (rmp/project ctx :sheet/in-progress-executions {:tags #{[:tick tick-id]}})
       tick-id))

(defn get-in-progress-node
  "Get the current node being executed for a tick.
   Returns the node-id or nil if no node is running."
  [ctx tick-id]
  (:current-node-id (get-execution-context ctx tick-id)))

(defn get-sequence-progress
  "Get sequence progress for a specific node in a tick.
   Returns {:index N :total M} or nil."
  [ctx tick-id node-id]
  (get-in (get-execution-context ctx tick-id) [:sequence-progress node-id]))

(defn get-map-each-progress
  "Get map-each progress for a specific node in a tick.
   Returns {:index N :total M} or nil."
  [ctx tick-id node-id]
  (get-in (get-execution-context ctx tick-id) [:map-each-progress node-id]))

(defreadmodel :sheet tree-metadata
  {:events logic/tree-metadata-events :version 1}
  [state event] (logic/tree-metadata* state event))

(defn get-tree-metadata
  "Get metadata for a specific sheet.
   Returns the most recent metadata or nil."
  [ctx sheet-id]
  (get (rmp/project ctx :sheet/tree-metadata {:tags #{[:sheet sheet-id]}})
       sheet-id))

(defn get-all-tree-metadata
  "Get metadata for all sheets.
   Returns map of sheet-id -> metadata."
  [ctx]
  (rmp/project ctx :sheet/tree-metadata))

(defn find-trees-by-problem-type
  "Find sheets that solve a specific problem type.
   Returns vector of metadata maps (with :sheet-id) sorted by avg-score desc."
  [ctx problem-type {:keys [min-score limit]
                     :or {min-score 0 limit 10}}]
  (let [all-metadata (get-all-tree-metadata ctx)]
    (->> all-metadata
         (filter (fn [[_id meta]]
                   (and (some #{problem-type} (:problem-types meta))
                        (>= (or (:avg-score meta) 0) min-score))))
         (sort-by (comp #(or (:avg-score %) 0) second) >)
         (take limit)
         (mapv (fn [[id meta]] (assoc meta :sheet-id id))))))

(defreadmodel :sheet rolling-metrics
  {:events logic/rolling-metrics-events :version 1
   :partition-fn :sheet-id
   :entity-id-fn (fn [event] [(:sheet-id event) (:node-id event)])}
  [state event] (logic/rolling-metrics* state event))

(defn get-node-rolling-metrics
  "Get rolling metrics for a specific node.
   Returns metrics map or nil if no data."
  [ctx sheet-id node-id]
  (let [all-metrics (rmp/project ctx :sheet/rolling-metrics {:partition-key sheet-id})
        metrics (get all-metrics [sheet-id node-id])]
    (when metrics
      (merge {:sheet-id sheet-id
              :node-id node-id}
             (logic/compute-node-metrics metrics)))))

(defn get-tree-rolling-metrics
  "Get rolling metrics for all nodes in a sheet.
   Returns map with :nodes vector and :total-executions."
  [ctx sheet-id]
  (let [all-metrics (rmp/project ctx :sheet/rolling-metrics {:partition-key sheet-id})
        sheet-metrics (filter (fn [[[sid _nid] _]] (= sid sheet-id)) all-metrics)]
    {:sheet-id sheet-id
     :nodes (->> sheet-metrics
                 (keep (fn [[[_ nid] metrics]]
                         (when metrics
                           (merge {:node-id nid}
                                  (logic/compute-node-metrics metrics)))))
                 vec)
     :total-executions (->> sheet-metrics
                            (map (comp count :executions second))
                            (reduce + 0))}))

(defreadmodel :sheet node-type-rolling-metrics
  {:events #{:sheet/node-execution-completed} :version 1
   :partition-fn (fn [event] (or (:node-type event) ::no-node-type))
   :entity-id-fn :node-type}
  [state event] (logic/node-type-rolling-metrics* state event))

(defn get-node-type-metrics
  "Cross-sheet rolling metrics for a given node-type keyword.
   Returns {:node-type :success-count :failure-count :total-duration
            :executions ...} or nil when no events have been recorded."
  [ctx node-type]
  (let [all-metrics (rmp/project ctx :sheet/node-type-rolling-metrics
                                 {:partition-key node-type})]
    (get all-metrics node-type)))

(defreadmodel :sheet tree-fingerprint-rolling-metrics
  {:events #{:sheet/rlm-tree-execution-completed} :version 1
   :partition-fn (fn [event] (or (:tree-fingerprint event) ::no-tree-fingerprint))
   :entity-id-fn :tree-fingerprint}
  [state event] (logic/tree-fingerprint-rolling-metrics* state event))

(defn get-tree-fingerprint-metrics
  "Cross-sheet rolling metrics for a given tree-fingerprint hash.
   Returns {:tree-fingerprint :success-count :failure-count
            :total-duration :executions ...} or nil when no events
   have been recorded."
  [ctx tree-fingerprint]
  (let [all-metrics (rmp/project ctx :sheet/tree-fingerprint-rolling-metrics
                                 {:partition-key tree-fingerprint})]
    (get all-metrics tree-fingerprint)))

(defreadmodel :sheet injection-records
  {:events logic/injection-record-events :version 1
   :partition-fn :sheet-id
   :entity-id-fn logic/injection-record-entity-id}
  [state event] (logic/injection-records* state event))

(defn get-injection-records
  "Every injection record for a sheet, as a map of
   [sheet-id tick-id node-id] -> record."
  [ctx sheet-id]
  (rmp/project ctx :sheet/injection-records {:partition-key sheet-id}))

(defn get-injection-record
  "The injection record for one render occurrence, or nil."
  [ctx sheet-id tick-id node-id]
  (get (get-injection-records ctx sheet-id) [sheet-id tick-id node-id]))

(defreadmodel :sheet researcher-checkpoints
  {:events #{:rlm/researcher-checkpointed} :version 1
   :partition-fn :sheet-id
   :entity-id-fn (juxt :tick-id :node-id)}
  [state event] (logic/researcher-checkpoints* state event))

(defn get-researcher-checkpoint [ctx sheet-id tick-id node-id]
  (get (rmp/project ctx :sheet/researcher-checkpoints {:partition-key sheet-id})
       [tick-id node-id]))

(defreadmodel :sheet researcher-campaigns
  {:events logic/researcher-campaign-events :version 1
   :partition-fn :sheet-id
   :entity-id-fn :tick-id}
  [state event] (logic/researcher-campaigns* state event))

(defn get-researcher-campaign [ctx tick-id node-id]
  (get-in (rmp/project ctx :sheet/researcher-campaigns
                       {:tags #{[:tick tick-id]}})
          [tick-id :campaigns node-id]))

(defreadmodel :sheet researcher-resume-states
  {:events #{:rlm/researcher-resume-state-saved} :version 1
   :partition-fn :sheet-id
   :entity-id-fn (juxt :tick-id :node-id)}
  [state event] (logic/researcher-resume-states* state event))

(defn get-researcher-resume-state [ctx sheet-id tick-id node-id]
  (let [facts (->> (es/read (:event-store ctx)
                            {:tenant-id (:tenant-id ctx)
                             :types #{:rlm/researcher-resume-state-saved}
                             :tags #{[:tick tick-id] [:node node-id]}})
                   (into [])
                   (mapv :resume-state))]
    (when (seq facts)
      {:sheet-id sheet-id
       :resume-state (logic/hydrate-latest facts)})))

(defreadmodel :sheet researcher-iteration-records
  {:events #{:rlm/researcher-iteration-recorded} :version 1
   :partition-fn :sheet-id
   :entity-id-fn (juxt :tick-id :node-id :iteration-index :attempt-ordinal)}
  [state event] (logic/researcher-iteration-records* state event))

(defn get-researcher-iteration-records [ctx sheet-id tick-id node-id]
  (->> (rmp/project ctx :sheet/researcher-iteration-records
                    {:partition-key sheet-id})
       (keep (fn [[[event-tick event-node _iteration _attempt] entity]]
               (when (and (= tick-id event-tick) (= node-id event-node))
                 (:iteration-record entity))))
       (sort-by (juxt :iteration-index :attempt-ordinal))
       vec))

(defreadmodel :sheet researcher-actions
  {:events #{:rlm/researcher-action-completed} :version 1
   :partition-fn :sheet-id
   :entity-id-fn (juxt :tick-id :node-id :action-id)}
  [state event] (logic/researcher-actions* state event))

(defn get-researcher-actions [ctx sheet-id tick-id node-id]
  (->> (rmp/project ctx :sheet/researcher-actions {:partition-key sheet-id})
       (keep (fn [[[event-tick event-node action-id] action]]
               (when (and (= tick-id event-tick) (= node-id event-node))
                 [action-id action])))
       (into {})))

(defreadmodel :sheet researcher-effect-claims
  {:events #{:rlm/researcher-effect-claimed
             :rlm/researcher-effect-completed
             :rlm/researcher-effect-indeterminate}
   :version 1
   :partition-fn :sheet-id
   :entity-id-fn (juxt :tick-id :node-id :attempt-identity)}
  [state event] (logic/researcher-effect-claims* state event))

(defn get-researcher-effect-claims [ctx sheet-id tick-id node-id]
  (->> (rmp/project ctx :sheet/researcher-effect-claims
                    {:partition-key sheet-id})
       (keep (fn [[[event-tick event-node _attempt] claim]]
               (when (and (= tick-id event-tick) (= node-id event-node))
                 claim)))
       (sort-by (juxt :ownership-epoch :attempt-ordinal :attempt-identity))
       vec))

(defreadmodel :sheet provider-call-reservations
  {:events #{:sheet/provider-call-reserved}
   :version 1
   :partition-fn :budget-sheet-id
   :entity-id-fn (juxt :budget-tick-id :invocation-identity)}
  [state event]
  (logic/provider-call-reservations* state event))

(defn get-provider-call-reservations
  "Return the immutable reservation ledger for one durable budget root."
  [ctx budget-sheet-id budget-tick-id]
  (->> (rmp/project ctx :sheet/provider-call-reservations
                    {:partition-key budget-sheet-id})
       (keep (fn [[[event-budget-tick-id _invocation] reservation]]
               (when (= budget-tick-id event-budget-tick-id)
                 reservation)))
       (sort-by (juxt :provider-attempt-ordinal :invocation-identity))
       vec))

;; --- Value log ---

(defn read-tick-events
  "All events for a tick. Event-store read failures return []; external value
   failures propagate so execution never silently substitutes missing data."
  [runtime-source tenant-id tick-id]
  (let [events (try
                 (if-let [event-store (logic/event-store-of runtime-source)]
                   (into [] (es/read event-store (cond-> {:tags #{[:tick tick-id]}}
                                                  tenant-id (assoc :tenant-id tenant-id))))
                   [])
                 (catch Exception _ []))]
    (logic/value-log-hydrate runtime-source events)))

(defn resolve-write-sources
  "Return {key canonical-source-ref} for a node completion."
  [source tenant-id tick-id completion]
  (let [ek (logic/execution-key completion)]
    (reduce (fn [acc event]
              (if (and (logic/value-event? event)
                       (= ek (logic/execution-key event))
                       (not (logic/input-seed? event)))
                (assoc acc (:key event) (or (:source event) (logic/source-ref event)))
                acc))
            (or (:write-sources completion) {})
            (read-tick-events source tenant-id tick-id))))

(defn- read-tick-writes
  "A tick's :sheet/execution-value-written events, oldest first."
  [runtime-source tenant-id tick-id]
  (let [events (try
                 (if-let [event-store (logic/event-store-of runtime-source)]
                   (into [] (es/read event-store (cond-> {:types #{:sheet/execution-value-written
                                                                   :sheet/execution-value-referenced}
                                                         :tags #{[:tick tick-id]}}
                                                  tenant-id (assoc :tenant-id tenant-id))))
                   [])
                 (catch Exception _ []))]
    (logic/value-log-hydrate runtime-source events)))

(defn- write-index
  [cache runtime-source tenant-id tick-id]
  (if-let [idx (get @cache tick-id)]
    idx
    (let [idx (logic/index-writes (read-tick-writes runtime-source tenant-id tick-id))]
      (vswap! cache assoc tick-id idx)
      idx)))

(defn- resolve-source-entry-in
  "A found value envelope, including explicit nil; nil means unresolved.
   Reads each tick's write events at most once per `cache`."
  [cache runtime-source tenant-id {:keys [tick-id event-id] :as source} seen]
  (when (and tick-id event-id (not (contains? seen source)))
    (when-let [event (get (write-index cache runtime-source tenant-id tick-id) event-id)]
      (if (= :sheet/execution-value-referenced (:event/type event))
        (resolve-source-entry-in cache runtime-source tenant-id (:source event) (conj seen source))
        (when (contains? event :value) (select-keys event [:value]))))))

(defn- resolve-source-entry
  "A found value envelope, including explicit nil; nil means unresolved."
  ([runtime-source tenant-id source]
   (resolve-source-entry runtime-source tenant-id source #{}))
  ([runtime-source tenant-id source seen]
   (resolve-source-entry-in (logic/new-write-index-cache) runtime-source tenant-id source seen)))

(defn resolve-writes
  "Convenience: read a tick's events and return what one node execution
   wrote. Prefer the `events`-taking fns when you already hold the events."
  [runtime-source tenant-id tick-id completion]
  (let [events (read-tick-events runtime-source tenant-id tick-id)
        ek (logic/execution-key completion)
        sources (reduce (fn [acc event]
                          (if (and (logic/value-event? event)
                                   (= ek (logic/execution-key event))
                                   (not (logic/input-seed? event)))
                            (assoc acc (:key event)
                                   (or (:source event) (logic/source-ref event)))
                            acc))
                        (or (:write-sources completion) {}) events)]
    (if (seq sources)
      (let [writes (logic/new-write-index-cache)]
        (reduce-kv (fn [acc k value-source]
                     (if-let [entry (resolve-source-entry-in writes runtime-source tenant-id value-source #{})]
                       (assoc acc k (:value entry)) acc))
                   {} sources))
      (logic/writes-for events completion))))

(defn resolve-source
  "Resolve a {:tick-id :event-id} reference, following cross-tick references.
   Missing, foreign, and cyclic references resolve to nil. Explicit null also
   returns nil; map-producing resolution APIs preserve its key presence."
  ([runtime-source tenant-id source]
   (:value (resolve-source-entry runtime-source tenant-id source)))
  ([runtime-source tenant-id source seen]
   (:value (resolve-source-entry runtime-source tenant-id source seen))))

(defn tick-started-event
  "The tick's ORIGINATING :sheet/tree-tick-started — the one carrying
   :execution-snapshot and :seed-sources.

   complete-tree-tick emits further events of that type for the SAME tick-id on
   each re-tick, carrying neither, so this returns the snapshot-bearing one."
  [runtime-source tenant-id tick-id]
  (try
    (when-let [event-store (logic/event-store-of runtime-source)]
      (->> (es/read event-store (cond-> {:types #{:sheet/tree-tick-started}
                                          :tags #{[:tick tick-id]}}
                                   tenant-id (assoc :tenant-id tenant-id)))
           (into [])
           (some #(when (:execution-snapshot %) %))))
    (catch Exception _ nil)))

(defn tick-seeds
  "Memoized resolution of a tick's canonical seed references.

   Safe to memoize without an invalidation rule: the originating
   tree-tick-started is appended by the tick-tree command before any processor
   can run, and events are immutable."
  [runtime-source tenant-id tick-id]
  (if-let [cached (get @logic/tick-seeds* tick-id)]
    cached
    (let [started (tick-started-event runtime-source tenant-id tick-id)
          writes (logic/new-write-index-cache)
          v (reduce-kv (fn [acc k value-source]
                         (if-let [entry (resolve-source-entry-in writes runtime-source tenant-id value-source #{})]
                           (assoc acc k (:value entry)) acc))
                       {} (or (:seed-sources started) {}))]
      (swap! logic/tick-seeds* assoc tick-id v)
      v)))

(defn resolve-reads
  "Resolve the exact values read by one node completion.

   Recorded :read-sources win. Inside map-each, where shared blackboard
   pointers are intentionally not recorded, use the iteration seed selected
   by the completion's execution context. Remaining keys came from the tick's
   original seeds."
  [runtime-source tenant-id tick-id completion]
  (let [read-keys (or (:read-keys completion) [])
        sources (or (:read-sources completion) {})
        events (read-tick-events runtime-source tenant-id tick-id)
        iteration-seeds (get (logic/input-seeds-by-iteration events)
                             (logic/exec-context (:inputs completion)))
        tick-seed-values (tick-seeds runtime-source tenant-id tick-id)
        writes (logic/new-write-index-cache)]
    (reduce (fn [acc k]
              (let [source (get sources k)
                    entry (cond
                            source (resolve-source-entry-in writes runtime-source tenant-id source #{})
                            (contains? iteration-seeds k) {:value (get iteration-seeds k)}
                            (contains? tick-seed-values k) {:value (get tick-seed-values k)})]
                (if entry (assoc acc k (:value entry)) acc)))
            {}
            read-keys)))

(defn values-by-event-id
  "{event-id value} for specific write events of a tick.

   Resolves by scanning the tick's write events and indexing them by
   :event/id. Deliberately NOT by a per-pointer
   {:as-of id :reverse? true :limit 1} seek, which looks like the obvious
   optimization and is unsound:

     :as-of is an inclusive upper BOUND, not an equality filter, so such a
     query returns \"the last matching event at or before this id\" — and
     'last' means last in the store's iteration order, which is APPEND order,
     not id order. Concurrent appends commit out of id order routinely (a
     map-each with max-concurrency > 1 does it on every run), and when they
     do the seek returns a neighbouring write instead of the one asked for.
     Measured on the composite map-each fixture, that missed roughly one
     resolution in three.

   The scan has no ordering assumption, so it is correct on any backend and
   under any interleaving. An event whose id was not requested is ignored, so
   a mismatched or foreign pointer resolves to nothing rather than to a
   plausible wrong value."
  [source tenant-id tick-id ids]
  (let [ids (set (remove nil? ids))]
    (if (or (empty? ids) (nil? (logic/event-store-of source)))
      {}
      (reduce (fn [acc e]
              (let [id (cond
                         (contains? ids (:event/id e)) (:event/id e)
                         (contains? ids (:value-id e)) (:value-id e))]
                (if id (assoc acc id (:value e)) acc)))
              {}
              (read-tick-writes source tenant-id tick-id)))))

(defn resolve-values
  "The values behind a METADATA blackboard, for `ks` (nil = every key).

   `metadata-bb` is the :blackboard of a tick execution context:
   {k {:key :schema :version :source :profile}} with no :value.
   Keys that resolve to nothing are omitted."
  [source tenant-id tick-id metadata-bb ks]
  (let [ks (if (nil? ks) (keys metadata-bb) ks)
        entries (into {} (for [k ks :when (contains? metadata-bb k)]
                           [k (get metadata-bb k)]))
        seeds (when (some (fn [[_ e]] (nil? (:source e))) entries)
                (tick-seeds source tenant-id tick-id))
        writes (logic/new-write-index-cache)]
    (reduce (fn [acc [k e]]
              (let [src (:source e)
                    entry (if src
                            (resolve-source-entry-in writes source tenant-id src #{})
                            (when (contains? seeds k) {:value (get seeds k)}))]
                (if entry (assoc acc k (:value entry)) acc)))
            {}
            entries)))

(defn final-values
  "The tick's END STATE as {key value}, read straight from the log.

   Unlike resolve-values this does not consult the read model at all, so it
   cannot be affected by how far the projection has caught up. That matters
   for the one caller that owes an answer rather than a view — result
   delivery. A tick's writes are appended atomically with the node completion
   that produced them, so by the time tree-tick-completed is observed the log
   is authoritative and complete, while the cache may still be catching up
   under concurrent projections.

   Seeds first, then every write in order, so the last write to a key wins —
   the same end state the cached blackboard converges to."
  [source tenant-id tick-id]
  (let [writes (logic/new-write-index-cache)]
  (merge (tick-seeds source tenant-id tick-id)
         (reduce (fn [acc event]
                   (let [entry (if (= :sheet/execution-value-referenced (:event/type event))
                                 (resolve-source-entry-in writes source tenant-id (:source event) #{})
                                 (when (contains? event :value) (select-keys event [:value])))]
                     (if entry (assoc acc (:key event) (:value entry)) acc)))
                 {}
                 (read-tick-writes source tenant-id tick-id)))))

(defn final-sources
  "The canonical source reference currently backing each key at tick end."
  [source tenant-id tick-id]
  (reduce (fn [acc event]
            (assoc acc (:key event) (or (:source event) (logic/source-ref event))))
          (or (:seed-sources (tick-started-event source tenant-id tick-id)) {})
          (read-tick-writes source tenant-id tick-id)))

(defn hydrate-blackboard
  "`metadata-bb` with :value filled in for `ks` (nil = every key).

   THE ENTRY SET IS PRESERVED EXACTLY. A declared-but-unresolvable key keeps
   its metadata and simply gets no :value — which is what the read model
   produced for a declared-but-unwritten key. executor/gather-inputs skips an
   entry with no :value and preserves an explicit :value nil as a native null.
   Shared request presentation renders that value under its declared contract;
   metadata-only declarations must not become supplied null inputs."
  ([source tenant-id tick-id metadata-bb]
   (hydrate-blackboard source tenant-id tick-id metadata-bb nil))
  ([source tenant-id tick-id metadata-bb ks]
   (let [vals* (resolve-values source tenant-id tick-id metadata-bb ks)]
     (reduce-kv (fn [bb k v] (assoc-in bb [k :value] v))
                (or metadata-bb {})
                vals*))))

(defn tick-family-ids
  "Return `tick-id` and every transitively nested child tick.

   Child starts carry a [:parent-tick parent-id] tag, so this walk is bounded
   by the execution tree. It never scans unrelated tenant history."
  [event-store tenant-id tick-id]
  (loop [seen #{tick-id}
         frontier [tick-id]]
    (if (empty? frontier)
      seen
      (let [children
            (into #{}
                  (mapcat (fn [parent-id]
                            (into []
                                  (comp (map :tick-id)
                                        (remove seen))
                                  (es/read event-store
                                           (cond-> {:types #{:sheet/tree-tick-started}
                                                    :tags #{[:parent-tick parent-id]}}
                                             tenant-id (assoc :tenant-id tenant-id))))))
                  frontier)]
        (recur (into seen children) (vec children))))))

(defn get-family-usage
  "Total provider usage for `trace-id` and all descendant executions:
   {:prompt-tokens :completion-tokens :total-tokens} plus :cost when any
   usage carries one. Each node completion contributes its :own-usage when present (a completion
   whose :usage folds in generated child ticks), otherwise its top-level :usage;
   the :by-node breakdown is a view of the same tokens, never added again."
  [{:keys [event-store tenant-id]} trace-id]
  (let [events (into []
                     (mapcat (fn [tick-id]
                               (into []
                                     (es/read event-store
                                              (cond-> {:types #{:sheet/node-execution-completed}
                                                       :tags #{[:tick tick-id]}}
                                                tenant-id (assoc :tenant-id tenant-id))))))
                     (tick-family-ids event-store tenant-id trace-id))
        usages (keep #(or (:own-usage %) (:usage %)) events)
        total (fn [& ks] (reduce + 0 (keep #(apply logic/usage-number % ks) usages)))
        costs (keep #(logic/usage-number % :cost) usages)]
    (cond-> {:prompt-tokens (total :prompt-tokens :prompt_tokens)
             :completion-tokens (total :completion-tokens :completion_tokens)
             :total-tokens (total :total-tokens :total_tokens)}
      (seq costs) (assoc :cost (reduce + 0 costs)))))

(defreadmodel :sheet model-candidate-ledger {:events logic/model-call-journal-event-types :version 1} [state event]
  (update state :entries (fnil conj [])
          (select-keys event (concat logic/model-call-journal-owner-keys [:event/type :event/id :candidate-index :entry :request :selection-scope :plan-scope-id :plan]))))

(defreadmodel :sheet provider-attempt-ledger
  {:events logic/provider-observations-event-types :version 1}
  [state event]
  (let [id (:invocation-id event)]
    (case (:event/type event)
      :sheet/provider-attempt-started
      (-> state
          (update :order (fnil conj []) id)
          (assoc-in [:by-id id] (assoc (select-keys event logic/provider-observations-owner-keys) :status :started)))
      :sheet/provider-attempt-completed
      (update-in state [:by-id id] assoc :status :completed :outcome (:outcome event))
      state)))

;; --- Execution family ---

(defn- make-tick-reader
  "A memoized reader of a tick's events: each tick's events are read from the
   store at most ONCE per family query, however many entries (or cross-tick
   references) consult them."
  [{:keys [tenant-id] :as ctx}]
  (let [cache (atom {})]
    (fn [tick-id]
      (or (get @cache tick-id)
          (let [events (read-tick-events ctx tenant-id tick-id)
                by-ref (reduce (fn [acc e]
                                 (if (logic/value-event? e)
                                   (let [acc (assoc acc (:event/id e) e)]
                                     (if (:value-id e) (assoc acc (:value-id e) e) acc))
                                   acc))
                               {} events)
                v {:events events :by-ref by-ref}]
            (swap! cache assoc tick-id v)
            v)))))

(defn- tick-entries
  "Every node-execution entry of one tick, plus the tick's lineage facts."
  [ctx read-tick resolve-ref tick-id]
  (let [tick-events (:events (read-tick tick-id))
        started (some #(when (and (= :sheet/tree-tick-started (:event/type %))
                                  (= tick-id (:tick-id %)))
                         %)
                      tick-events)
        sheet-id (:sheet-id started)
        nodes-by-id (or (get-tick-nodes-by-id ctx tick-id)
                        (when sheet-id (get-nodes-by-id ctx sheet-id))
                        {})
        options (:options started)
        tick {:tick-id tick-id
              :sheet-id sheet-id
              :parent-tick-id (:parent-tick-id started)
              :nodes-by-id nodes-by-id
              :attributed-to (or (:delegate-parent-node-id options)
                                 (:researcher-campaign-node-id options))}
        index (logic/tick-index resolve-ref tick-events)]
    {:tick tick
     :entries (->> tick-events
                   (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                 (= tick-id (:tick-id %))))
                   (mapv #(logic/entry-for resolve-ref index tick-events tick %)))}))

(defn get-execution-family
  "Every node execution of `trace-id` and of every nested child execution
   (delegated workflows, generated child trees), in durable completion order.

   Each entry: :tick-id :parent-tick-id (child ticks only) :sheet-id :node-id
   :node-name :node-type :executor :instruction :status :exec-context :inputs
   and :outputs (resolved VALUES, never truncated), :failure {:kind :message
   :provider-evidence} when it failed, :tool-receipts when the tick recorded
   tool effects for it, usage/model fields when recorded, :event-id and
   :completed-at.

   Option :node-id scopes the family to the executions beneath the executions
   of that composite or delegate node (the node itself is excluded)."
  ([ctx trace-id] (get-execution-family ctx trace-id {}))
  ([{:keys [event-store tenant-id] :as ctx} trace-id {:keys [node-id]}]
   (let [tick-ids (tick-family-ids event-store tenant-id trace-id)
         read-tick (make-tick-reader ctx)
         resolve-ref (logic/resolver read-tick)
         per-tick (mapv #(tick-entries ctx read-tick resolve-ref %) tick-ids)
         entries (into [] (mapcat :entries) per-tick)
         ticks (mapv :tick per-tick)
         scoped (if node-id (logic/scope-to-node entries ticks node-id) entries)]
     (->> scoped
          (sort-by #(str (:event-id %)))
          vec))))
