(ns ai.obney.orc.orc-service.interface.read-models
  "Public interface for behavior tree sheet service read models."
  (:require [ai.obney.orc.orc-service.core.read-models :as core]))

;; =============================================================================
;; Sheet Read Model Functions
;; =============================================================================

(defn get-sheet
  "Get a single sheet by ID"
  [ctx sheet-id]
  (core/get-sheet ctx sheet-id))

(defn get-sheets-all
  "Get all sheets"
  [ctx]
  (core/get-sheets-all ctx))

(defn get-sheet-by-name
  "Find a sheet by name. Returns nil if not found."
  [ctx name]
  (core/get-sheet-by-name ctx name))

;; =============================================================================
;; Node Read Model Functions
;; =============================================================================

(defn get-node
  "Get a single node by ID"
  [ctx sheet-id node-id]
  (core/get-node ctx sheet-id node-id))

(defn get-nodes-for-sheet
  "Get all nodes in a sheet"
  [ctx sheet-id]
  (core/get-nodes-for-sheet ctx sheet-id))

(defn get-nodes-by-id
  "Get all nodes in a sheet as a map keyed by node-id"
  [ctx sheet-id]
  (core/get-nodes-by-id ctx sheet-id))

(defn get-root-node
  "Get the root node for a sheet"
  [ctx sheet-id]
  (core/get-root-node ctx sheet-id))

(defn get-children
  "Get child nodes of a parent node"
  [ctx sheet-id parent-id]
  (core/get-children ctx sheet-id parent-id))

(defn get-descendants
  "Get all descendant nodes of a node (recursive)"
  [ctx sheet-id node-id]
  (core/get-descendants ctx sheet-id node-id))

;; =============================================================================
;; Blackboard Read Model Functions
;; =============================================================================

(defn get-blackboard-for-sheet
  "Get the blackboard for a sheet as a list of entries"
  [ctx sheet-id]
  (core/get-blackboard-for-sheet ctx sheet-id))

(defn get-blackboard-by-key
  "Get the blackboard for a sheet as a map keyed by key name"
  [ctx sheet-id]
  (core/get-blackboard-by-key ctx sheet-id))

;; =============================================================================
;; Judge Read Model Functions (Gap-1)
;; =============================================================================

(defn get-judges
  "Get all judges declared for a sheet as a map keyed by judge name."
  [ctx sheet-id]
  (core/get-judges ctx sheet-id))

(defn get-judge
  "Get a single declared judge by name."
  [ctx sheet-id judge-name]
  (core/get-judge ctx sheet-id judge-name))

;; =============================================================================
;; Tick Read Model Functions
;; =============================================================================

(defn get-tick
  "Get a single tick by ID"
  [ctx tick-id]
  (core/get-tick ctx tick-id))

;; =============================================================================
;; Version Read Model Functions
;; =============================================================================

(defn get-versions-for-sheet
  "Get all published versions for a sheet, sorted by version number"
  [ctx sheet-id]
  (core/get-versions-for-sheet ctx sheet-id))

(defn get-version
  "Get a specific published version by version number"
  [ctx sheet-id version-number]
  (core/get-version ctx sheet-id version-number))

(defn get-latest-version
  "Get the latest published version for a sheet"
  [ctx sheet-id]
  (core/get-latest-version ctx sheet-id))

(defn get-stash
  "Get the stash for a sheet, if any"
  [ctx sheet-id]
  (core/get-stash ctx sheet-id))

;; =============================================================================
;; Tree Metadata Read Model Functions
;; =============================================================================

(defn get-tree-metadata
  "Get metadata for a specific sheet.
   Returns the most recent metadata or nil."
  [ctx sheet-id]
  (core/get-tree-metadata ctx sheet-id))

(defn get-all-tree-metadata
  "Get metadata for all sheets.
   Returns map of sheet-id -> metadata."
  [ctx]
  (core/get-all-tree-metadata ctx))

(defn find-trees-by-problem-type
  "Find sheets that solve a specific problem type based on metadata.
   Returns vector of metadata maps (with :sheet-id) sorted by avg-score desc.

   Options:
   - :min-score - Minimum avg-score filter (default 0)
   - :limit - Maximum results (default 10)"
  [ctx problem-type & [options]]
  (core/find-trees-by-problem-type ctx problem-type (or options {})))

;; =============================================================================
;; Rolling Metrics Read Model Functions
;; =============================================================================

(defn get-node-rolling-metrics
  "Get rolling metrics for a specific node.

   Returns map with:
   - :sheet-id, :node-id - identifiers
   - :execution-count - number of executions in window
   - :success-rate, :failure-rate - ratios (0.0-1.0)
   - :avg-duration-ms - average execution time
   - :recent-trend - :improving, :declining, or :stable"
  [ctx sheet-id node-id]
  (core/get-node-rolling-metrics ctx sheet-id node-id))

(defn get-tree-rolling-metrics
  "Get rolling metrics for all nodes in a sheet.

   Returns map with:
   - :sheet-id - sheet identifier
   - :nodes - vector of node metrics
   - :total-executions - total number of executions"
  [ctx sheet-id]
  (core/get-tree-rolling-metrics ctx sheet-id))

(defn get-node-type-metrics
  "C-2a-2: get cross-sheet rolling metrics for a node-type keyword.

   Returns map with :node-type :success-count :failure-count
   :total-duration :executions, or nil when no events with that
   :node-type have been recorded."
  [ctx node-type]
  (core/get-node-type-metrics ctx node-type))

(defn get-tree-fingerprint-metrics
  "C-2a-2: get cross-sheet rolling metrics for a tree-fingerprint hash.

   Returns map with :tree-fingerprint :success-count :failure-count
   :total-duration :executions, or nil when no rlm-tree-execution-
   completed events with that :tree-fingerprint have been recorded."
  [ctx tree-fingerprint]
  (core/get-tree-fingerprint-metrics ctx tree-fingerprint))
