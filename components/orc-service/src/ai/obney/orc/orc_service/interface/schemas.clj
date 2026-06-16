(ns ai.obney.orc.orc-service.interface.schemas
  "Behavior Tree Sheet schemas.

   This is a behavior tree visualization system where:
   - The grid represents a tree (rows = depth levels, cells subdivide horizontally)
   - Nodes are leaf (execute), sequence (run all until failure), or fallback (run until success)
   - Data flows through a shared blackboard
   - Nodes return status: success, failure, or running"
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Domain Enums
;; =============================================================================

(def node-type
  "Behavior tree node types"
  [:enum :leaf :sequence :fallback :condition :llm-condition :parallel :map-each :repl-researcher :delegate])

(def executor-type
  "Executor types for leaf nodes"
  [:enum :ai :code :tool])

(def node-status
  "Node execution status.

   :partial is emitted by map-each when 0 < failed < total items (D-008).
   Sequence/fallback parents treat :partial as continuation (same as :success).

   :timeout is emitted by RLM repl-researcher nodes when Phase 2 exceeds the
   budget (D-003). Surfaces to the parent so callers can distinguish 'no
   useful output' from a deliberate failure."
  [:enum :idle :running :success :failure :partial :timeout])

(def partial-summary
  "Denormalized at-a-glance synopsis of partial/failure outcome on a map-each.
   The canonical record stays in per-child :sheet/node-execution-completed
   events; this summary is a convenience for judges and dashboards.

   D-008: :failure-input-profiles is reserved for future enrichment when
   the map-each handler has access to per-child input profiles."
  [:map
   [:total :int]
   [:succeeded :int]
   [:failed :int]
   [:failure-indices [:vector :int]]
   [:failure-reasons [:map-of :int :string]]
   [:failure-input-profiles {:optional true} [:map-of :int :map]]])

;; Legacy field-type enum - kept for migration from old format
(def field-type
  "Supported field types for blackboard values (legacy)"
  [:enum :text :number :yesno :list :document :image :table])

;; Migration helper: convert legacy type to Malli schema
(def legacy-type->malli
  "Maps legacy field types to Malli schemas"
  {:text :string
   :number :double
   :yesno :boolean
   :list [:vector :string]
   :document :string
   :image :string
   :table [:vector [:map-of :string :any]]})

(def decorator-type
  "Decorator types that modify node behavior"
  [:enum :retry :timeout :repeat])

(def condition-op
  "Operators for condition node checks"
  [:enum :equals :not-equals :gt :lt :gte :lte :contains :exists :truthy])

(def on-fail-behavior
  "What to return when a condition check fails"
  [:enum :failure :running])

(def execution-mode
  "Which version to use for execution"
  [:enum :draft :published])

;; =============================================================================
;; Domain Value Objects
;; =============================================================================

(def decorator
  "Decorator that modifies node execution behavior"
  [:map
   [:type decorator-type]
   [:config :map]])

(def field-value
  "A typed field value for blackboard entries.
   Schema can be any valid Malli schema (keyword like :string, or vector like [:map ...])"
  [:map
   [:schema :any]  ;; Malli schema EDN
   [:value :any]])

(def condition-check
  "A condition check definition for condition nodes"
  [:map
   [:key :keyword]
   [:op condition-op]
   [:value {:optional true} :any]
   [:on-fail {:optional true} on-fail-behavior]])

;; =============================================================================
;; Domain Schemas (for use in query results)
;; =============================================================================

(defschemas domain
  {::sheet
   [:map
    [:id :uuid]
    [:name :string]
    [:root-node-id {:optional true} :uuid]
    [:created-at {:optional true} :string]
    ;; Versioning fields
    [:draft-dirty? {:optional true} :boolean]
    [:published-version {:optional true} :int]
    [:execution-mode {:optional true} execution-mode]
    [:has-stash? {:optional true} :boolean]]

   ::node
   [:map
    [:id :uuid]
    [:sheet-id :uuid]
    [:type node-type]
    [:name :string]
    [:parent-id {:optional true} :uuid]
    [:children-ids [:vector :uuid]]
    [:status node-status]
    ;; Leaf-only fields
    [:instruction {:optional true} :string]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]
    [:decorators [:vector decorator]]
    ;; Executor fields (for leaf nodes)
    [:executor {:optional true} executor-type]     ;; :ai, :code, :tool
    [:model {:optional true} :string]              ;; OpenRouter model ID (e.g., "google/gemini-2.5-flash")
    [:fn {:optional true} :string]                 ;; Fully-qualified fn symbol for :code executor
    [:tools {:optional true} [:vector :keyword]]   ;; Tools available to AI for :ai executor
    [:options {:optional true} :map]               ;; Per-node executor/DSCloj options
    [:retry {:optional true} [:map
                              [:max-attempts :int]
                              [:backoff-ms [:vector :int]]]]
    ;; Condition-only fields
    [:check {:optional true} condition-check]
    ;; Parallel-only fields
    [:success-policy {:optional true} [:enum :all :any :majority]]
    [:failure-policy {:optional true} [:enum :all :any]]
    ;; Map-each-only fields
    [:source-key {:optional true} :keyword]        ;; Blackboard key with list to iterate
    [:item-key {:optional true} :keyword]          ;; Blackboard key for current item
    [:output-key {:optional true} :keyword]        ;; Blackboard key for collected results
    [:max-concurrency {:optional true} :int]       ;; Max parallel iterations (nil = sequential)
    ;; Repl-researcher-only fields
    [:mcp-tools {:optional true} [:vector :string]] ;; Available MCP tool names for research
    [:max-iterations {:optional true} :int]         ;; Max research iterations (default 10)
    ;; D-003: total budget (Phase 1 + Phase 2) in ms. When set, takes precedence
    ;; over the parent tick :options :timeout-ms and the 900_000ms hardcoded
    ;; fallback. Phase 2 receives the remaining budget after Phase 1 completes.
    [:timeout-ms {:optional true} :int]
    ;; Delegate-only fields
    [:target-sheet-id {:optional true} :uuid]       ;; Sheet to delegate execution to
    [:delegate-timeout-ms {:optional true} :int]    ;; Timeout for delegated execution
    [:inherit-ontology? {:optional true} :boolean]  ;; Share ontology context with target
    ;; Execution tracking
    [:last-error {:optional true} :string]]

   ::blackboard-entry
   [:map
    [:key :keyword]
    [:schema :any]  ;; Malli schema EDN (e.g., :string, [:map [:name :string]])
    [:value {:optional true} :any]
    [:version :int]]

   ::node-layout
   [:map
    [:node-id :uuid]
    [:row :int]
    [:start-col :double]
    [:end-col :double]]

   ::version-snapshot
   [:map
    [:snapshot-id :uuid]
    [:sheet-id :uuid]
    [:version-number :int]
    [:published-at :any]
    [:description {:optional true} :string]
    [:snapshot :map]]  ;; Same structure as export-sheet result

   ::stash
   [:map
    [:stash-id :uuid]
    [:sheet-id :uuid]
    [:stashed-at :any]
    [:snapshot :map]]

   ;; -------------------------------------------------------------------------
   ;; Execution Trace Schemas
   ;; -------------------------------------------------------------------------

   ::node-trace
   [:map
    [:node-id :uuid]
    [:trace-instance-id :uuid]                    ;; Unique ID for this execution instance
    [:parent-trace-instance-id {:optional true} :uuid]  ;; Links to parent's trace-instance-id
    [:node-name :string]
    [:node-type node-type]
    [:parent-id {:optional true} :uuid]           ;; Parent node ID (for reference only)
    [:path [:vector :string]]                     ;; Path from root e.g. ["root" "fallback-1" "task-a"]
    [:child-index {:optional true} :int]          ;; Which child of parent (0-indexed)
    [:status [:enum :success :failure :running :skipped :partial :timeout]]
    [:started-at :any]
    [:completed-at {:optional true} :any]
    [:duration-ms {:optional true} :int]
    [:inputs {:optional true} :map]               ;; Blackboard values read
    [:outputs {:optional true} :map]              ;; Blackboard values written
    [:error {:optional true} :string]]

   ::execution-trace
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:version-number {:optional true} :int]       ;; nil = draft execution
    [:started-at :any]
    [:completed-at :any]
    [:duration-ms :int]
    [:status [:enum :success :failure :timeout :partial]]
    [:input-snapshot :map]                        ;; Blackboard at start
    [:output-snapshot :map]                       ;; Blackboard at end
    [:node-traces [:vector ::node-trace]]
    [:error {:optional true} :string]]

   ;; -------------------------------------------------------------------------
   ;; Tree Metadata Schemas
   ;; -------------------------------------------------------------------------

   ::tree-metadata
   [:map
    [:sheet-id :uuid]
    [:name :string]
    [:description {:optional true} :string]
    ;; What problems this tree solves
    [:problem-types [:vector :string]]            ;; e.g., ["problem:Classification"]
    ;; What capabilities it has
    [:capabilities [:vector :string]]             ;; e.g., ["validation-loop", "parallel-gathering"]
    ;; What patterns it uses
    [:patterns [:vector :string]]                 ;; e.g., ["success:ExplicitSchema"]
    ;; Auto-extracted from structure
    [:node-types [:vector :keyword]]              ;; e.g., [:llm :code :map-each]
    [:node-count :int]
    [:has-validation? :boolean]
    [:has-retry? :boolean]
    [:has-parallel? :boolean]
    [:has-map-each? :boolean]
    ;; Updated over time
    [:execution-count {:optional true} :int]
    [:avg-score {:optional true} :double]
    [:last-executed {:optional true} :string]
    [:extracted-at :string]]})

;; =============================================================================
;; Command Schemas
;; =============================================================================

(defschemas commands
  {;; -------------------------------------------------------------------------
   ;; Sheet Commands
   ;; -------------------------------------------------------------------------

   :sheet/create-sheet
   [:map
    [:name :string]]

   :sheet/rename-sheet
   [:map
    [:sheet-id :uuid]
    [:name :string]]

   :sheet/delete-sheet
   [:map
    [:sheet-id :uuid]]

   ;; -------------------------------------------------------------------------
   ;; Node Commands
   ;; -------------------------------------------------------------------------

   :sheet/create-node
   [:map
    [:sheet-id :uuid]
    [:node-id {:optional true} :uuid]
    [:type node-type]
    [:parent-id {:optional true} :uuid]
    [:index {:optional true} :int]]

   :sheet/move-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:new-parent-id :uuid]
    [:index :int]]

   :sheet/reorder-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:index :int]]

   :sheet/delete-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]]

   :sheet/set-node-name
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:name :string]]

   :sheet/set-node-instruction
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]]

   :sheet/set-node-context
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:context [:map-of :keyword :any]]]

   :sheet/set-node-io
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]]

   :sheet/set-node-decorators
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:decorators [:vector decorator]]]

   :sheet/set-node-check
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:check condition-check]]

   :sheet/set-node-executor
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:executor executor-type]
    [:model {:optional true} :string]
    [:fn {:optional true} :string]
    [:tools {:optional true} [:vector :keyword]]
    [:options {:optional true} :map]]

   :sheet/set-node-retry
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:retry [:map
             [:max-attempts :int]
             [:backoff-ms [:vector :int]]]]]

   :sheet/set-parallel-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:success-policy {:optional true} [:enum :all :any :majority]]
    [:failure-policy {:optional true} [:enum :all :any]]]

   :sheet/set-map-each-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:source-key :keyword]
    [:item-key :keyword]
    [:output-key :keyword]
    [:max-concurrency {:optional true} :int]]

   :sheet/set-llm-condition-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]
    [:reads [:vector :keyword]]
    [:model {:optional true} :string]]

   :sheet/set-repl-researcher-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]                              ;; Research goal/question
    [:reads [:vector :keyword]]                         ;; Blackboard keys (metadata only shown to LLM)
    [:writes [:vector :keyword]]                        ;; Output keys (final-answer, iterations, etc.)
    [:mcp-tools [:vector :string]]                      ;; Available MCP tool names
    [:model {:optional true} :string]                   ;; OpenRouter model ID
    [:max-iterations {:optional true} :int]             ;; Default 10
    [:rlm {:optional true} [:or :boolean :map]]         ;; Enable RLM mode (true or {:debug? true})
    [:options {:optional true} :map]                    ;; Per-node executor/DSCloj options
    [:timeout-ms {:optional true} :int]]                ;; D-003: total Phase-1+Phase-2 budget

   :sheet/set-delegate-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:target-sheet-id :uuid]                            ;; Sheet to delegate to
    [:reads [:vector :keyword]]                         ;; Blackboard keys to pass as inputs
    [:writes [:vector :keyword]]                        ;; Output keys to receive from target
    [:timeout-ms {:optional true} :int]                 ;; Timeout for delegated execution
    [:inherit-ontology? {:optional true} :boolean]]     ;; Share ontology context (default true)

   ;; -------------------------------------------------------------------------
   ;; Blackboard Commands
   ;; -------------------------------------------------------------------------

   :sheet/declare-key
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:schema :any]]  ;; Malli schema EDN

   :sheet/update-key-schema
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:schema :any]]  ;; New Malli schema EDN

   :sheet/set-key-value
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:value :any]]

   :sheet/delete-key
   [:map
    [:sheet-id :uuid]
    [:key :keyword]]

   :sheet/set-content-hash
   [:map
    [:sheet-id :uuid]
    [:content-hash :string]]

   ;; -------------------------------------------------------------------------
   ;; Judge Commands
   ;; -------------------------------------------------------------------------

   :sheet/declare-judge
   [:map
    [:sheet-id :uuid]
    [:judge-name :string]
    [:judge-config [:map
                    [:type :keyword]  ;; :grounding, :completeness, :instruction-following, :reasoning, :custom
                    [:criteria {:optional true} :string]  ;; Custom criteria description
                    ;; Weight for aggregation. The Gap-8 composite-score
                    ;; normalizer accepts ANY non-negative number (integer
                    ;; or double) — values are re-scaled per the share-
                    ;; remaining-mass policy. Negative weights are rejected
                    ;; because they're nonsensical for a probability mass.
                    [:weight {:optional true} [:and number? [:>= 0.0]]]
                    [:sheet-id {:optional true} :uuid]]]] ;; For :custom type - reference to judge sheet

   :sheet/set-node-judges
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:judges [:vector :string]]]  ;; List of judge names defined in sheet/judges

   ;; -------------------------------------------------------------------------
   ;; Execution Commands
   ;; -------------------------------------------------------------------------

   :sheet/tick-tree
   [:map
    [:sheet-id :uuid]
    [:tick-id {:optional true} :uuid]
    ;; Lineage: tick that spawned this one (RLM Phase 2 trees, delegate
    ;; nodes). Lets observers correlate a child-tick cascade to its root.
    [:parent-tick-id {:optional true} :uuid]
    ;; Fields for async execution with isolated blackboard
    [:inputs {:optional true} :map]
    [:use-version {:optional true} :int]
    [:force-draft {:optional true} :boolean]
    [:options {:optional true} :map]]

   :sheet/tick-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:tick-id {:optional true} :uuid]
    [:overrides {:optional true} [:map-of :keyword :any]]]

   :sheet/cancel-tick
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]]

   ;; Internal commands (issued by todo processors)
   :sheet/complete-node-execution
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:status [:enum :success :failure :tree-generated :partial :timeout]]
    [:writes [:map-of :keyword :any]]
    [:duration-ms {:optional true} :int]
    [:inputs {:optional true} [:map-of :keyword :any]]
    ;; Verbatim raw LLM response text, present only on parse-failure
    ;; completions (the model answered but no value could be extracted
    ;; for declared writes). Persisted so (node-output <node-id>) can
    ;; retrieve the full text post-hoc.
    [:raw-response {:optional true} :string]
    ;; Gap-7: carry through to the event body. See :completion-kind on
    ;; :sheet/node-execution-completed.
    [:completion-kind {:optional true} [:enum :tree-iteration :terminal]]
    ;; Optional per-node token usage from LLM calls. Universal — applies to
    ;; any leaf node that calls an LLM, not just RLM Phase 2 contexts.
    [:usage {:optional true}
     [:map
      [:prompt-tokens {:optional true} :int]
      [:completion-tokens {:optional true} :int]
      [:total-tokens {:optional true} :int]]]
    ;; D-008: present when a map-each terminates in :partial or :failure.
    [:partial-summary {:optional true} partial-summary]
    ;; C-2a-2: node-type keyword (:llm, :code, :map-each, :parallel, ...).
    ;; Drives the per-node-type rolling-metrics aggregator without forcing
    ;; the read-model to look up node-type via the sheets read-model.
    ;; Optional for backwards compatibility — older callers / replayed
    ;; events without this field are skipped by the per-node-type
    ;; aggregator.
    [:node-type {:optional true} :keyword]]

   :sheet/fail-node-execution
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:error :string]
    [:duration-ms {:optional true} :int]]

   ;; Records an RLM-tree node completion. Emitted by execute-leaf-node
   ;; alongside the generic complete-node-execution when an LLM call has
   ;; usage. Produces a :sheet/rlm-tree-node-completed event with a
   ;; precomputed node-path. Future fields (scores, feedback) get added
   ;; by downstream judge work.
   :sheet/record-rlm-tree-node-completion
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:node-path [:vector :map]]
    [:usage
     [:map
      [:prompt-tokens {:optional true} :int]
      [:completion-tokens {:optional true} :int]
      [:total-tokens {:optional true} :int]]]
    ;; Optional :input-profile keyed by node :reads — describes input
    ;; characteristics so future judges/pattern-matchers can correlate
    ;; outcomes to input shape.
    [:input-profile {:optional true} [:map-of :keyword :map]]]

   ;; Bookend event for an RLM Phase 2 tree-execution. Emitted once when
   ;; the tree-tick finishes (success or failure). Carries the full
   ;; trajectory of events, total token usage, and a placeholder for
   ;; task-fingerprint (filled by later issue 012 work).
   :sheet/record-rlm-tree-execution-completion
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:trajectory [:vector :map]]
    [:total-usage [:map]]
    [:task-fingerprint {:optional true} [:maybe :string]]
    ;; C-2a-2: deterministic hash of canonical tree-raw S-expression.
    ;; Stable across the inline-fn sanitization step. Drives the per-
    ;; tree-fingerprint rolling-metrics aggregator. Optional for
    ;; backwards compatibility with replays of older events.
    [:tree-fingerprint {:optional true} [:maybe :string]]
    ;; C-2a-2: tree-execution outcome. Required for the per-tree-
    ;; fingerprint aggregator to count successes vs failures. Optional
    ;; for backwards compatibility — older events without this field
    ;; are skipped by the aggregator.
    [:status {:optional true} [:enum :success :failure :partial :timeout]]
    ;; C-2a-2: wall-clock duration of Phase 2 execution. Optional for
    ;; the same reason :status is.
    [:duration-ms {:optional true} :int]]

   ;; -------------------------------------------------------------------------
   ;; Versioning Commands
   ;; -------------------------------------------------------------------------

   :sheet/publish-version
   [:map
    [:sheet-id :uuid]
    [:description {:optional true} :string]]

   :sheet/revert-to-version
   [:map
    [:sheet-id :uuid]
    [:version-number :int]]

   :sheet/restore-stash
   [:map
    [:sheet-id :uuid]]

   :sheet/set-execution-mode
   [:map
    [:sheet-id :uuid]
    [:mode execution-mode]]

   ;; -------------------------------------------------------------------------
   ;; Version-Targeted Execution Commands
   ;; -------------------------------------------------------------------------

   :sheet/execute-version
   [:map
    [:sheet-id :uuid]
    [:version-number :int]                        ;; Specific version to execute
    [:inputs {:optional true} :map]]              ;; Input overrides

   :sheet/batch-execute
   [:map
    [:sheet-id :uuid]
    [:version-number {:optional true} :int]       ;; nil = draft
    [:inputs [:vector :map]]]

   ;; -------------------------------------------------------------------------
   ;; System Commands (called internally via cp/process-command)
   ;; -------------------------------------------------------------------------

   :sheet/emit-tick-started
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]]

   :sheet/emit-tick-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:root-status :keyword]]

   :sheet/store-execution-trace
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:version-number {:optional true} :int]
    [:started-at :any]
    [:completed-at :any]
    [:duration-ms :int]
    [:status :keyword]
    [:input-snapshot :map]
    [:output-snapshot :map]
    [:node-traces [:vector :any]]
    [:error {:optional true} :string]]

   ;; -------------------------------------------------------------------------
   ;; Tree Metadata Commands
   ;; -------------------------------------------------------------------------

   :sheet/extract-tree-metadata
   [:map
    [:sheet-id :uuid]
    [:problem-types {:optional true} [:vector :string]]
    [:description {:optional true} :string]]})

;; =============================================================================
;; Event Schemas
;; =============================================================================

(defschemas events
  {;; -------------------------------------------------------------------------
   ;; Sheet Events
   ;; -------------------------------------------------------------------------

   :sheet/sheet-created
   [:map
    [:sheet-id :uuid]
    [:name :string]]

   :sheet/sheet-renamed
   [:map
    [:sheet-id :uuid]
    [:old-name :string]
    [:new-name :string]]

   :sheet/sheet-deleted
   [:map
    [:sheet-id :uuid]]

   :sheet/content-hash-set
   [:map
    [:sheet-id :uuid]
    [:content-hash :string]]

   ;; -------------------------------------------------------------------------
   ;; Node Events
   ;; -------------------------------------------------------------------------

   :sheet/node-created
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:type node-type]
    [:parent-id {:optional true} :uuid]
    [:index {:optional true} :int]]

   :sheet/node-moved
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:old-parent-id {:optional true} :uuid]
    [:new-parent-id :uuid]
    [:index :int]]

   :sheet/node-reordered
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:old-index :int]
    [:new-index :int]]

   :sheet/node-deleted
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]]

   :sheet/node-name-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:name :string]
    [:previous-name {:optional true} :string]]

   :sheet/node-instruction-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]
    [:previous-instruction {:optional true} :string]]

   :sheet/node-context-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:context [:map-of :keyword :any]]
    [:previous-context {:optional true} [:map-of :keyword :any]]]

   :sheet/node-io-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]
    [:previous-reads {:optional true} [:vector :keyword]]
    [:previous-writes {:optional true} [:vector :keyword]]]

   :sheet/node-decorators-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:decorators [:vector decorator]]
    [:previous-decorators {:optional true} [:vector decorator]]]

   :sheet/node-check-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:check condition-check]
    [:previous-check {:optional true} condition-check]]

   :sheet/node-executor-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:executor executor-type]
    [:model {:optional true} :string]
    [:fn {:optional true} :string]
    [:tools {:optional true} [:vector :keyword]]
    [:options {:optional true} :map]
    [:previous-executor {:optional true} executor-type]
    [:previous-model {:optional true} :string]
    [:previous-fn {:optional true} :string]
    [:previous-tools {:optional true} [:vector :keyword]]
    [:previous-options {:optional true} :map]]

   :sheet/node-retry-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:retry [:map
             [:max-attempts :int]
             [:backoff-ms [:vector :int]]]]
    [:previous-retry {:optional true} [:map
                                       [:max-attempts :int]
                                       [:backoff-ms [:vector :int]]]]]

   :sheet/parallel-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:success-policy {:optional true} [:enum :all :any :majority]]
    [:failure-policy {:optional true} [:enum :all :any]]
    [:previous-success-policy {:optional true} [:enum :all :any :majority]]
    [:previous-failure-policy {:optional true} [:enum :all :any]]]

   :sheet/map-each-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:source-key :keyword]
    [:item-key :keyword]
    [:output-key :keyword]
    [:max-concurrency {:optional true} :int]
    [:previous-source-key {:optional true} :keyword]
    [:previous-item-key {:optional true} :keyword]
    [:previous-output-key {:optional true} :keyword]
    [:previous-max-concurrency {:optional true} :int]]

   :sheet/llm-condition-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]
    [:reads [:vector :keyword]]
    [:model {:optional true} :string]
    [:previous-instruction {:optional true} :string]
    [:previous-reads {:optional true} [:vector :keyword]]
    [:previous-model {:optional true} :string]]

   :sheet/repl-researcher-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]
    [:mcp-tools [:vector :string]]
    [:model {:optional true} :string]
    [:max-iterations {:optional true} :int]
    [:rlm {:optional true} [:or :boolean :map]]
    [:options {:optional true} :map]
    [:timeout-ms {:optional true} :int]                            ;; D-003
    [:previous-instruction {:optional true} :string]
    [:previous-reads {:optional true} [:vector :keyword]]
    [:previous-writes {:optional true} [:vector :keyword]]
    [:previous-mcp-tools {:optional true} [:vector :string]]
    [:previous-model {:optional true} :string]
    [:previous-max-iterations {:optional true} :int]
    [:previous-rlm {:optional true} [:or :boolean :map]]
    [:previous-options {:optional true} :map]
    [:previous-timeout-ms {:optional true} :int]]                  ;; D-003

   :sheet/delegate-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:target-sheet-id :uuid]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]
    [:timeout-ms {:optional true} :int]
    [:inherit-ontology? {:optional true} :boolean]
    [:previous-target-sheet-id {:optional true} :uuid]
    [:previous-reads {:optional true} [:vector :keyword]]
    [:previous-writes {:optional true} [:vector :keyword]]
    [:previous-timeout-ms {:optional true} :int]
    [:previous-inherit-ontology? {:optional true} :boolean]]

   ;; -------------------------------------------------------------------------
   ;; Blackboard Events
   ;; -------------------------------------------------------------------------

   :sheet/key-declared
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:schema :any]]  ;; Malli schema EDN

   :sheet/key-schema-updated
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:schema :any]
    [:previous-schema {:optional true} :any]]

   :sheet/key-value-set
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:value :any]
    [:previous-value {:optional true} :any]
    [:version :int]]

   :sheet/key-deleted
   [:map
    [:sheet-id :uuid]
    [:key :keyword]]

   ;; -------------------------------------------------------------------------
   ;; Judge Events
   ;; -------------------------------------------------------------------------

   :sheet/judge-declared
   [:map
    [:sheet-id :uuid]
    [:judge-name :string]
    [:judge-config [:map
                    [:type :keyword]
                    [:criteria {:optional true} :string]
                    ;; Mirror of :sheet/declare-judge :weight constraint.
                    [:weight {:optional true} [:and number? [:>= 0.0]]]
                    [:sheet-id {:optional true} :uuid]]]
    [:criteria-version {:optional true} :int]]

   :sheet/node-judges-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:judges [:vector :string]]
    [:previous-judges {:optional true} [:vector :string]]]

   ;; -------------------------------------------------------------------------
   ;; Execution Events
   ;; -------------------------------------------------------------------------

   :sheet/tree-tick-started
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    ;; Lineage: tick that spawned this one (RLM Phase 2 trees, delegate
    ;; nodes). Absent for root ticks and on events from older versions.
    [:parent-tick-id {:optional true} :uuid]
    [:iteration {:optional true} :int]
    ;; Fields for async execution with isolated blackboard
    [:inputs {:optional true} :map]
    [:execution-snapshot {:optional true} :map]
    [:version-number {:optional true} :int]
    [:options {:optional true} :map]]

   :sheet/node-execution-started
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:inputs [:map-of :keyword :any]]]

   :sheet/node-execution-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:status [:enum :success :failure :running :tree-generated :partial :timeout]]
    [:writes {:optional true} [:map-of :keyword :any]]
    [:duration-ms {:optional true} :int]
    [:inputs {:optional true} [:map-of :keyword :any]]
    ;; Verbatim raw LLM response text, present only on parse-failure
    ;; completions. Source for the (node-output <node-id>) drill-down
    ;; when a failed LLM leaf has no successful writes to show.
    [:raw-response {:optional true} :string]
    ;; Optional per-node token usage when the node was an LLM call.
    [:usage {:optional true}
     [:map
      [:prompt-tokens {:optional true} :int]
      [:completion-tokens {:optional true} :int]
      [:total-tokens {:optional true} :int]]]
    ;; D-008: present on map-each completion events when status is :partial or :failure.
    [:partial-summary {:optional true} partial-summary]
    ;; C-2a-2: node-type keyword carried through from the command for the
    ;; per-node-type rolling-metrics aggregator. Optional for backwards
    ;; compatibility with old replayed events.
    [:node-type {:optional true} :keyword]
    ;; Gap-7: distinguishes intermediate-tree-iteration completions of
    ;; recursive repl-researcher nodes (where :generated-tree-raw is in
    ;; :writes) from the terminal (final!) completion (where the
    ;; synthesized task outputs are in :writes). Used by the judge
    ;; runtime's resolver to route appropriate judges per kind.
    ;; Optional — legacy / non-repl-researcher events omit it.
    [:completion-kind {:optional true} [:enum :tree-iteration :terminal]]]

   ;; RLM-specific learning-signal event. Fires alongside the generic
   ;; node-execution-completed when an LLM call completes inside an RLM
   ;; Phase 2 tree. Carries a precomputed :node-path identifying the
   ;; node's position in map-each iterations, plus :usage. Future
   ;; iterations of this PRD's work (O03+) will extend this event with
   ;; :input-profile, and downstream judge work will add :scores and
   ;; :feedback. Kept distinct from the generic event so the RLM-specific
   ;; fields don't pollute universal node lifecycle.
   :sheet/rlm-tree-node-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    ;; Structured path: [{:type :map-each :parent uuid :index N} ... {:type :leaf :node-id uuid}]
    [:node-path [:vector :map]]
    [:usage
     [:map
      [:prompt-tokens {:optional true} :int]
      [:completion-tokens {:optional true} :int]
      [:total-tokens {:optional true} :int]]]
    ;; :input-profile keyed by :reads keys. Each value is
    ;; {:length N :word-count N :line-count N}. Captures input shape
    ;; so future judges can correlate quality outcomes to inputs.
    [:input-profile {:optional true} [:map-of :keyword :map]]
    ;; Future-expansion placeholders documented for forward compatibility
    [:scores {:optional true} :map]
    [:feedback {:optional true} :string]]

   ;; Bookend event emitted when a Phase 2 RLM tree-execution finishes.
   :sheet/rlm-tree-execution-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    ;; Full event log for the tick — vec of {:event-type :timestamp
    ;; :node-id (optional) ...}. Consistent with Grain "all events
    ;; flow through the log" methodology.
    [:trajectory [:vector :map]]
    ;; Aggregate usage at completion time.
    [:total-usage [:map]]
    ;; Placeholder for task-fingerprint (filled by issue 012).
    [:task-fingerprint {:optional true} [:maybe :string]]
    ;; C-2a-2: deterministic hash of canonical tree-raw S-expression.
    ;; Drives per-tree-fingerprint rolling metrics. Optional for
    ;; backwards compatibility with replayed older events.
    [:tree-fingerprint {:optional true} [:maybe :string]]
    ;; C-2a-2: tree-execution outcome. Required for the per-tree-
    ;; fingerprint aggregator to count successes vs failures.
    [:status {:optional true} [:enum :success :failure :partial :timeout]]
    ;; C-2a-2: wall-clock duration of Phase 2 execution.
    [:duration-ms {:optional true} :int]
    [:timestamp [:fn inst?]]]

   :sheet/tree-tick-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:iteration {:optional true} :int]
    ;; D-008: :partial added so map-each can surface partial outcomes.
    ;; D-003: :timeout added so RLM repl-researcher can surface Phase 2
    ;; budget cancellation as a tree-level signal.
    [:root-status [:enum :success :failure :running :tree-generated :partial :timeout]]
    [:outputs {:optional true} :map]
    [:error {:optional true} :string]]

   :sheet/execution-value-written
   [:map
    [:tick-id :uuid]
    [:sheet-id :uuid]
    [:key :keyword]
    [:value :any]]

   :sheet/tick-cancelled
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]]

   :sheet/sequence-progress-updated
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:child-index :int]
    [:total-children :int]]

   :sheet/map-each-progress-updated
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:item-index :int]
    [:total-items :int]]

   ;; -------------------------------------------------------------------------
   ;; Versioning Events
   ;; -------------------------------------------------------------------------

   :sheet/version-published
   [:map
    [:sheet-id :uuid]
    [:snapshot-id :uuid]
    [:version-number :int]
    [:description {:optional true} :string]
    [:snapshot :map]]

   :sheet/draft-stashed
   [:map
    [:sheet-id :uuid]
    [:stash-id :uuid]
    [:snapshot :map]]

   :sheet/draft-reverted
   [:map
    [:sheet-id :uuid]
    [:target-version :int]
    [:snapshot-id :uuid]
    [:snapshot :map]]

   :sheet/stash-restored
   [:map
    [:sheet-id :uuid]
    [:stash-id :uuid]
    [:snapshot :map]]

   :sheet/execution-mode-set
   [:map
    [:sheet-id :uuid]
    [:mode execution-mode]
    [:previous-mode {:optional true} execution-mode]]

   ;; -------------------------------------------------------------------------
   ;; Trace Events
   ;; -------------------------------------------------------------------------

   :sheet/execution-traced
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:version-number {:optional true} :int]
    [:started-at :any]
    [:completed-at :any]
    [:duration-ms :int]
    [:status [:enum :success :failure :timeout]]
    [:input-snapshot :map]
    [:output-snapshot :map]
    [:node-traces [:vector :any]]                 ;; Vector of ::node-trace
    [:error {:optional true} :string]]

   ;; -------------------------------------------------------------------------
   ;; Execution Audit Events
   ;; -------------------------------------------------------------------------

   :sheet/version-executed
   [:map
    [:sheet-id :uuid]
    [:version-number :int]
    [:trace-id :uuid]
    [:status :keyword]
    [:duration-ms :int]]

   :sheet/batch-executed
   [:map
    [:sheet-id :uuid]
    [:version-number {:optional true} :int]
    [:total-executions :int]
    [:successful-count :int]
    [:failed-count :int]
    [:duration-ms :int]]

   ;; -------------------------------------------------------------------------
   ;; Tree Metadata Events
   ;; -------------------------------------------------------------------------

   :sheet/tree-metadata-extracted
   [:map
    [:sheet-id :uuid]
    [:metadata :any]]

   ;; -------------------------------------------------------------------------
   ;; RLM (Recursive Language Model) Events
   ;; -------------------------------------------------------------------------

   :rlm/tree-generated
   [:map
    [:tree-id :uuid]
    [:execution-id :uuid]
    [:raw-dsl :any]                              ;; S-expr literal from LLM (serializable)
    [:iteration-count {:optional true} :int]    ;; How many REPL iterations
    [:input-metadata {:optional true} [:map
                                       [:size :int]
                                       [:type :keyword]]]
    [:generated-at :string]
    ;; Gap-7b: the repl-researcher node that emitted the tree. Without
    ;; this, downstream evaluators (e.g. heuristic-structural via
    ;; :rlm/tree-generated) must scan :sheet/node-execution-started
    ;; events to find the host node, which is ambiguous when multiple
    ;; nodes share the same tick (root + Phase 2 children). Optional
    ;; for backwards compatibility with replayed events.
    [:node-id {:optional true} :uuid]
    [:sheet-id {:optional true} :uuid]]

   :rlm/tree-executed
   [:map
    [:tree-id :uuid]
    [:execution-id :uuid]
    [:status [:enum :success :failure]]
    [:outputs {:optional true} :map]
    [:duration-ms :int]
    [:node-traces {:optional true} [:vector :any]]
    [:error {:optional true} :string]]

   :rlm/tree-evaluated
   [:map
    [:tree-id :uuid]
    [:execution-id :uuid]
    [:score :double]                             ;; 0.0 to 1.0
    [:feedback :string]                          ;; Actionable feedback
    [:dimensions {:optional true} [:vector       ;; Optional breakdown by dimension
                                   [:map
                                    [:name :string]
                                    [:weight :double]
                                    [:score :double]
                                    [:feedback :string]]]]
    [:evaluated-at :string]]

   ;; U10: Phase 1 researcher iteration capture. Emitted by the
   ;; repl-researcher processor whenever the researcher ran at least
   ;; one iteration — including direct-execution (no emit-tree!) runs.
   ;; Lets observability tools query iteration history uniformly
   ;; across execution modes.
   :rlm/researcher-iterations
   [:map
    [:execution-id :uuid]
    [:iterations [:vector :any]]                 ;; Each: {:code :result :stdout :error :vars-created}
    [:iteration-count :int]
    [:emitted-at :string]]})

;; =============================================================================
;; Query Schemas (Fat Query Model - one query per screen)
;; =============================================================================

(defschemas queries
  {;; -------------------------------------------------------------------------
   ;; Sheets List Screen (/sheets)
   ;; -------------------------------------------------------------------------

   :sheet/sheets-list-screen
   [:map]

   :sheet/sheets-list-screen-result
   [:map
    [:sheets [:vector ::sheet]]
    [:total :int]]

   ;; -------------------------------------------------------------------------
   ;; Sheet View Screen (/sheet?sheet-id=...)
   ;; -------------------------------------------------------------------------

   :sheet/sheet-view-screen
   [:map
    [:sheet-id :uuid]]

   :sheet/sheet-view-screen-result
   [:map
    [:sheet ::sheet]
    [:nodes [:vector ::node]]
    [:blackboard [:vector ::blackboard-entry]]
    [:layout [:vector ::node-layout]]
    [:version-info {:optional true}
     [:map
      [:published-version {:optional true} :int]
      [:draft-dirty? :boolean]
      [:execution-mode execution-mode]
      [:has-stash? :boolean]]]]

   ;; -------------------------------------------------------------------------
   ;; Export Sheet (for download/backup)
   ;; -------------------------------------------------------------------------

   :sheet/export-sheet
   [:map
    [:sheet-id :uuid]]

   :sheet/export-sheet-result
   [:map
    [:version :int]
    [:exported-at :any]
    [:sheet [:map
             [:name :string]
             [:id :uuid]]]
    [:blackboard-schema [:map-of :keyword :any]]
    [:nodes {:optional true} :any]]

   ;; -------------------------------------------------------------------------
   ;; Versioning Queries
   ;; -------------------------------------------------------------------------

   :sheet/version-history
   [:map
    [:sheet-id :uuid]]

   :sheet/version-history-result
   [:map
    [:versions [:vector ::version-snapshot]]
    [:current-published-version {:optional true} :int]
    [:draft-dirty? :boolean]
    [:has-stash? :boolean]]

   :sheet/get-version
   [:map
    [:sheet-id :uuid]
    [:version-number :int]]

   :sheet/get-version-result
   [:map
    [:version ::version-snapshot]]

   :sheet/get-stash
   [:map
    [:sheet-id :uuid]]

   :sheet/get-stash-result
   [:map
    [:stash {:optional true} ::stash]]

   ;; -------------------------------------------------------------------------
   ;; Trace Queries
   ;; -------------------------------------------------------------------------

   :sheet/get-trace
   [:map
    [:trace-id :uuid]]

   :sheet/get-trace-result
   [:map
    [:trace ::execution-trace]]

   :sheet/get-traces
   [:map
    [:sheet-id :uuid]
    [:version-number {:optional true} :int]       ;; Filter by version
    [:status {:optional true} [:enum :success :failure :timeout]]
    [:node-id {:optional true} :uuid]             ;; Filter by node involvement
    [:since {:optional true} :any]                ;; Filter by time
    [:limit {:optional true} :int]]

   :sheet/get-traces-result
   [:map
    [:traces [:vector ::execution-trace]]
    [:total :int]]

   ;; -------------------------------------------------------------------------
   ;; Runs Screen Query (all traces across all sheets)
   ;; -------------------------------------------------------------------------

   :sheet/runs-screen
   [:map
    [:trace-id {:optional true} :uuid]
    [:status {:optional true} [:enum :success :failure :timeout]]
    [:limit {:optional true} :int]]

   :sheet/runs-screen-result
   [:map
    [:traces [:vector ::execution-trace-summary]]
    [:total :int]
    [:selected-trace {:optional true} ::execution-trace]]

   ;; Summary version of execution trace for list views
   ::execution-trace-summary
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:sheet-name :string]
    [:status [:enum :success :failure :timeout]]
    [:started-at :any]
    [:duration-ms :int]
    [:node-count :int]
    [:version-number {:optional true} :int]]

   ;; On-demand node trace detail (inputs/outputs)
   :sheet/node-trace-detail
   [:map
    [:trace-id :uuid]
    [:node-id :uuid]]

   :sheet/node-trace-detail-result
   [:map
    [:node-id :uuid]
    [:inputs {:optional true} :map]
    [:outputs {:optional true} :map]]

   ;; Run Detail Screen Query (single trace with full data)
   :sheet/run-detail-screen
   [:map
    [:trace-id :uuid]]

   :sheet/run-detail-screen-result
   [:map
    [:trace ::execution-trace]]

   ;; -------------------------------------------------------------------------
   ;; Structural Diff Query
   ;; -------------------------------------------------------------------------

   :sheet/diff-versions
   [:map
    [:sheet-id :uuid]
    [:from-version :int]
    [:to-version :int]]

   :sheet/diff-versions-result
   [:map
    [:node-diff [:map
                 [:added-nodes [:vector :any]]
                 [:removed-nodes [:vector :any]]
                 [:modified-nodes [:vector :any]]]]
    [:blackboard-diff [:map
                       [:added [:vector :keyword]]
                       [:removed [:vector :keyword]]
                       [:modified [:vector :any]]]]]

   ;; -------------------------------------------------------------------------
   ;; Node Statistics Query
   ;; -------------------------------------------------------------------------

   :sheet/node-stats
   [:map
    [:sheet-id :uuid]
    [:version-number {:optional true} :int]       ;; Filter to specific version
    [:since {:optional true} :any]                ;; Time window
    [:node-ids {:optional true} [:vector :uuid]]] ;; Specific nodes, or all if nil

   :sheet/node-stats-result
   [:map
    [:stats [:vector [:map
                      [:node-id :uuid]
                      [:node-name :string]
                      [:node-type node-type]
                      [:execution-count :int]
                      [:success-count :int]
                      [:failure-count :int]
                      [:skip-count :int]
                      [:success-rate :double]
                      [:avg-duration-ms {:optional true} :double]
                      [:p50-duration-ms {:optional true} :int]
                      [:p95-duration-ms {:optional true} :int]
                      [:common-errors {:optional true} [:vector :any]]]]]
    [:trace-count :int]]

   ;; -------------------------------------------------------------------------
   ;; Active Executions Dashboard Query
   ;; -------------------------------------------------------------------------

   :sheet/active-executions-screen
   [:map]

   :sheet/active-executions-screen-result
   [:map
    [:executions [:vector [:map
                           [:tick-id :uuid]
                           [:sheet-id :uuid]
                           [:sheet-name :string]
                           [:status [:enum :running :completed]]
                           [:started-at :any]
                           [:current-node-id {:optional true} :uuid]
                           [:current-node-name {:optional true} :string]
                           [:current-node-type {:optional true} node-type]
                           [:nodes-in-progress-count :int]
                           [:sequence-progress :map]
                           [:map-each-progress :map]]]]
    [:total :int]]})

;; =============================================================================
;; Read Model Schemas
;; =============================================================================

(defschemas read-models
  {:sheet/sheets       [:map-of :uuid ::sheet]
   :sheet/nodes        [:map-of :uuid ::node]
   :sheet/blackboard   [:map-of :keyword ::blackboard-entry]
   :sheet/judges       [:map-of :string :map]
   :sheet/ticks        [:map-of :uuid :map]
   :sheet/versions     [:map-of :uuid [:map-of :int ::version-snapshot]]
   :sheet/stashes      [:map-of :uuid :map]
   :sheet/traces       [:map-of :uuid ::execution-trace]})
