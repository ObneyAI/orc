(ns ai.obney.orc.orc-service.core.runtime
  "Runtime for executing behavior trees via async pipeline.

   This module provides:
   - `execute` - Dispatch execution to async pipeline and wait for completion
   - `build-execution-snapshot` - Load sheet, resolve version, build snapshot
   - Completion registry for sync callers waiting on async execution"
  (:require [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

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

;; =============================================================================
;; Public API
;; =============================================================================

(defn execute
  "Execute a sheet (behavior tree) by dispatching to async pipeline and waiting.

   This is a blocking call that:
   1. Dispatches a tick-tree command with an execution snapshot
   2. Waits for the async pipeline to complete (todo processors)
   3. Returns the result delivered by the completion registry

   Args:
     context - Map with :event-store, :pubsub, and optional :dscloj-provider
     sheet-id - UUID of the sheet to execute
     inputs - Map of blackboard key -> value for initial inputs

   Options:
     :timeout-ms - Max execution time in ms (default 300000 = 5 minutes)
     :use-version - Specific version number to execute (overrides execution-mode)
     :force-draft - Force draft execution even if execution-mode is :published
     :trace? - Enable tracing (passed to async pipeline via options)
     :langfuse-client - Langfuse client (passed to async pipeline via options)
     :store-trace? - Store trace in event store (default true, passed via options)
     :max-ticks - Override re-tick budget for this execution (defaults to *max-tick-iterations*)
     :tick-id - Optional caller-supplied execution id for correlating live progress
     :parent-tick-id - Optional lineage marker when this execution is a child of
                       another tick (RLM Phase 2 trees, delegate nodes)
     :llm-call-budget - Max LLM calls before failing (opt-in only, NO default)

   Returns:
     {:status :success | :failure | :timeout
      :outputs {\"key\" value ...}
      :duration-ms 1234
      :error string?             ;; Present if status is :failure
      :executed-version ...}     ;; Version number if published version was used"
  [context sheet-id inputs & {:keys [timeout-ms use-version force-draft
                                      trace? langfuse-client store-trace?
                                      max-ticks llm-call-budget tick-id parent-tick-id]
                               :or {timeout-ms 300000 store-trace? true}}]
  (let [tick-id (or tick-id (random-uuid))
        p (register-completion! tick-id)
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
                                                        :store-trace? store-trace?}
                                                 trace? (assoc :trace? true)
                                                 langfuse-client (assoc :langfuse-client langfuse-client)
                                                 max-ticks (assoc :max-ticks max-ticks)
                                                 llm-call-budget (assoc :llm-call-budget llm-call-budget))}
                              parent-tick-id (assoc :parent-tick-id parent-tick-id)
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
          {:status :failure
           :error (:cognitect.anomalies/message cmd-result)
           :duration-ms (- (System/currentTimeMillis) start-time)})
      ;; Wait for async completion
      (let [result (deref p timeout-ms ::timeout)
            duration-ms (- (System/currentTimeMillis) start-time)]
        (swap! completion-registry dissoc tick-id)
        (if (= result ::timeout)
          {:status :timeout
           :error "Execution timed out"
           :duration-ms duration-ms}
          (cond-> (assoc result :duration-ms duration-ms)
            ;; Fold the C-2c-2 auto-classification envelope when an
            ;; :ontology/task-classified event was emitted during this tick.
            (collect-tick-classification context tick-id)
            (assoc :auto-classification
                   (collect-tick-classification context tick-id))))))))
