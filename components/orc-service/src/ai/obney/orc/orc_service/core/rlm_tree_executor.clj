(ns ai.obney.orc.orc-service.core.rlm-tree-executor
  "Execute RLM-generated behavior trees via child ORC ticks.

   This module implements Phase 2 of the RLM two-phase execution:
   1. Phase 1: RLM generates a behavior tree via emit-tree!
   2. Phase 2: This module executes the tree via a child ORC tick

   The executor:
   - Takes canonical ORC DSL (output of rlm-dsl->orc-dsl)
   - Writes sandbox variables to blackboard
   - Spawns an ephemeral child sheet with the tree as root
   - Returns results via promise for the parent to await

   This follows the delegate node pattern but is specialized for
   inline tree execution rather than referencing an existing sheet."
  (:require [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.commands] ;; Load command handlers
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [clojure.walk :as walk]))

;; =============================================================================
;; Ephemeral Function Registry
;; =============================================================================
;; For Phase 2 tree execution, code nodes have inline functions that can't be
;; serialized to the event store. We store them here temporarily and pass a
;; lookup key as the :fn symbol. The executor resolves the key back to the fn.

;; =============================================================================
;; Per-Node Usage Aggregation
;; =============================================================================

(defn- input-lookup
  "Find a key in an inputs map by simple-name match, regardless of namespace.
   Returns the value or nil. Used because map-each context keys are namespaced
   keywords (::map-each-index, ::map-each-parent) and the namespace depends
   on where they're defined."
  [inputs simple-name]
  (let [k (some (fn [k]
                  (when (and (keyword? k) (= (name k) simple-name))
                    k))
                (keys (or inputs {})))]
    (when k (get inputs k))))

(defn compute-node-path
  "Compute a structured path identifying a node's position in the execution
   context. Returns a vector of segments:
     - For a leaf executed outside any map-each:
       [{:type :leaf :node-id <uuid>}]
     - For a leaf executed under a map-each (iteration N of parent M):
       [{:type :map-each :parent <uuid> :index N} {:type :leaf :node-id <uuid>}]

   Pure function — testable in isolation. Used as the key for per-node usage
   aggregation so that the same leaf node executed multiple times under
   different map-each iterations produces distinct entries.

   Args:
     node-id - the leaf node's id
     inputs  - the event :inputs map (may carry ::map-each-index / ::map-each-parent
               keys from the execution context, regardless of source namespace)"
  [node-id inputs]
  (let [map-each-index (input-lookup inputs "map-each-index")
        map-each-parent (input-lookup inputs "map-each-parent")
        leaf-segment {:type :leaf :node-id node-id}]
    (if (and (some? map-each-index) (some? map-each-parent))
      [{:type :map-each :parent map-each-parent :index map-each-index}
       leaf-segment]
      [leaf-segment])))

(defn- aggregate-by-node
  "Given a seq of :sheet/node-execution-completed events, build a per-node
   usage map keyed by the structured node-path. Each value is a map of
   {:prompt-tokens N :completion-tokens N :total-tokens N} summed across
   all completions matching that path.

   When the same leaf node runs N times under a map-each, each iteration
   gets its own entry (path includes the iteration index). This is critical
   for benchmarks where map-each is the dominant token consumer.

   Pure function — testable in isolation."
  [completion-events]
  (reduce
    (fn [acc event]
      (let [node-id (:node-id event)
            inputs (:inputs event)
            usage (:usage event)]
        (if (and node-id usage)
          (let [path (compute-node-path node-id inputs)]
            (update acc path
                    (fn [existing]
                      (merge-with +
                                  (or existing {:prompt-tokens 0
                                                :completion-tokens 0
                                                :total-tokens 0})
                                  (select-keys usage
                                               [:prompt-tokens
                                                :completion-tokens
                                                :total-tokens])))))
          acc)))
    {}
    completion-events))

(defn- compute-by-node-from-tick-events
  "Read the event store for a tick's :sheet/node-execution-completed events
   and aggregate per-node usage."
  [event-store tenant-id tick-id]
  (let [tick-events (into [] (es/read event-store
                              (cond-> {:tags #{[:tick tick-id]}}
                                tenant-id (assoc :tenant-id tenant-id))))
        completions (filter #(= :sheet/node-execution-completed (:event/type %))
                            tick-events)]
    (aggregate-by-node completions)))

;; =============================================================================
;; Ephemeral Function Registry
;; =============================================================================

(defonce ^:private ephemeral-fn-registry (atom {}))

(defn register-ephemeral-fn!
  "Register an inline function for ephemeral execution. Returns a symbol string
   that can be used as the :fn value in a code executor command."
  [f]
  (let [fn-id (str "ephemeral-fn-" (random-uuid))]
    (swap! ephemeral-fn-registry assoc fn-id f)
    fn-id))

(defn lookup-ephemeral-fn
  "Look up an ephemeral function by its registry key."
  [fn-id]
  (get @ephemeral-fn-registry fn-id))

(defn clear-ephemeral-fn!
  "Remove an ephemeral function from the registry."
  [fn-id]
  (swap! ephemeral-fn-registry dissoc fn-id))

(defn sanitize-tree-for-events
  "U8: Walk a tree and replace any :fn map-entry whose value is a function
   object with [:fn \"<inline-fn>\"]. SCI fn objects are not Fressian-
   serializable; if we store them verbatim in the event store, the
   read-model fails to project the event and the tick stays pending
   forever.

   The actual function continues to live in the ephemeral-fn-registry
   for Phase-2 execution. Only the EVENT representation needs sanitization.

   Qualified-symbol-string :fn values pass through untouched. Tree shape
   is otherwise preserved."
  [tree]
  (walk/postwalk
    (fn [node]
      (if (and (map-entry? node)
               (= :fn (key node))
               (fn? (val node)))
        [:fn "<inline-fn>"]
        node))
    tree))

;; =============================================================================
;; Command Helpers (inlined to avoid circular dependency with test-helpers)
;; =============================================================================

(defn- run-command!
  "Run a command and return the result."
  [ctx command-data]
  (cp/process-command (assoc ctx :command command-data)))

(defn- make-create-sheet-command
  "Create a create-sheet command with unique name."
  []
  {:command/name :sheet/create-sheet
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :name (str "RLM-Tree-" (random-uuid))})

(defn- make-create-node-command
  "Create a create-node command."
  [sheet-id node-type & {:keys [parent-id index]}]
  (cond-> {:command/name :sheet/create-node
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :type node-type}
    parent-id (assoc :parent-id parent-id)
    index (assoc :index index)))

(defn- make-declare-key-command
  "Create a declare-key command."
  [sheet-id key schema]
  {:command/name :sheet/declare-key
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :key key
   :schema schema})

;; =============================================================================
;; Tree Analysis
;; =============================================================================

(defn- extract-output-keys
  "Extract output keys from a generated tree by finding final! nodes.
   Trees are lists like: (sheet/sequence (sheet/llm ...) (final! {:keys [...]}))
   Returns a vector of output key keywords."
  [tree]
  (cond
    ;; Final node: (final! {:keys [:a :b]})
    (and (seq? tree) (= 'final! (first tree)))
    (let [opts (second tree)]
      (vec (:keys opts)))

    ;; List/sequence: recurse into children
    (seq? tree)
    (vec (mapcat extract-output-keys (rest tree)))

    ;; Otherwise: no keys
    :else []))

(defn- extract-all-keys
  "Extract all blackboard keys (reads and writes) from the tree.
   Used to pre-declare all keys before creating nodes."
  [tree]
  (cond
    ;; LLM node: (sheet/llm :instruction "..." :reads [...] :writes [...])
    (and (seq? tree) (= 'sheet/llm (first tree)))
    (let [args (rest tree)
          opts (apply hash-map args)]
      (concat (:reads opts) (:writes opts)))

    ;; Code node: (sheet/code :reads [...] :writes [...] :fn ...)
    ;;
    ;; U4: :fn may appear in any position in the canonical args list:
    ;;   - rlm-dsl's :chunk-document / :aggregate translations emit :fn LAST
    ;;   - rlm-dsl's :code translation (post R-2) emits :fn FIRST
    ;; Parse all keyword-value pairs uniformly via (apply hash-map ...).
    ;; The :fn value (inline fn OR qualified-symbol string) fits a hash-map
    ;; entry just as well as any other value.
    (and (seq? tree) (= 'sheet/code (first tree)))
    (let [opts (apply hash-map (rest tree))]
      (concat (:reads opts) (:writes opts)))

    ;; Map-each node: (sheet/map-each :from :x :as :y :into :z child)
    (and (seq? tree) (= 'sheet/map-each (first tree)))
    (let [args (rest tree)
          child-node (last args)
          keyword-args (butlast args)
          opts (apply hash-map keyword-args)]
      ;; Include from, as, into keys plus any keys from child
      (concat [(:from opts) (:as opts) (:into opts)]
              (extract-all-keys child-node)))

    ;; Final node: (final! {:keys [...]})
    (and (seq? tree) (= 'final! (first tree)))
    (let [opts (second tree)]
      (:keys opts))

    ;; List/sequence: recurse into children
    (seq? tree)
    (mapcat extract-all-keys (rest tree))

    ;; Otherwise: no keys
    :else []))

(defn- extract-key-schemas
  "U11: Walk the canonical tree and collect {write-key → Malli-schema} from
   :llm nodes that declared :output-schemas.

   When a write key's schema is structured (vector/map/etc.), the child
   sheet's declare-key uses it. build-module then passes it to dscloj,
   which detects complex-spec? → asks the LLM for JSON → parses the
   response back into Clojure data. Without this, the LLM's structured
   output arrives at downstream :code nodes as raw JSON text.

   Returns a map {key schema}. Last-write-wins on key conflicts.
   Returns {} when no :output-schemas declarations exist anywhere in the tree."
  [tree]
  (cond
    ;; LLM node with :output-schemas → collect each {key schema}
    (and (seq? tree) (= 'sheet/llm (first tree)))
    (let [opts (apply hash-map (rest tree))]
      (or (:output-schemas opts) {}))

    ;; Sequence-like (sheet/sequence, sheet/parallel) → recurse into children
    (and (seq? tree)
         (#{'sheet/sequence 'sheet/parallel} (first tree)))
    (apply merge (map extract-key-schemas (rest tree)))

    ;; Map-each → recurse into the (last) child arg
    (and (seq? tree) (= 'sheet/map-each (first tree)))
    (extract-key-schemas (last tree))

    ;; Code/final/etc. → no schemas to collect here
    :else {}))

;; =============================================================================
;; Tree Compilation
;; =============================================================================

(defn- parse-keyword-args
  "Parse keyword arguments from a flat list into a map.
   (parse-keyword-args [:instruction \"foo\" :reads [:a] :writes [:b]])
   => {:instruction \"foo\" :reads [:a] :writes [:b]}"
  [args]
  (apply hash-map args))

(defn- compile-tree-node
  "Compile a single tree node into ORC sheet nodes.
   Returns {:node-id N :ephemeral-fn-keys [...]} where ephemeral-fn-keys
   are the keys of any inline functions registered during compilation.

   Supported node types:
   - sheet/sequence - Creates a sequence node
   - sheet/llm - Creates a leaf node with instruction
   - sheet/code - Creates a leaf node with code executor
   - sheet/map-each - Creates a map-each iteration node
   - final! - Creates a leaf node that just writes outputs"
  [context sheet-id tree parent-id & {:keys [index]}]
  (cond
    ;; Sequence node: (sheet/sequence child1 child2 ...)
    (and (seq? tree) (= 'sheet/sequence (first tree)))
    (let [seq-result (run-command! context
                       (make-create-node-command sheet-id :sequence :parent-id parent-id :index index))
          seq-id (-> seq-result :command-result/events first :node-id)
          children (rest tree)
          ;; Compile children recursively, collecting ephemeral keys
          ;; IMPORTANT: Pass index to each child to maintain correct ordering
          child-results (vec (map-indexed
                               (fn [idx child-tree]
                                 (compile-tree-node context sheet-id child-tree seq-id :index idx))
                               children))
          all-fn-keys (vec (mapcat :ephemeral-fn-keys child-results))]
      {:node-id seq-id
       :ephemeral-fn-keys all-fn-keys})

    ;; U13: Parallel node: (sheet/parallel child1 child2 ...)
    ;; Same compile structure as :sequence — create a :parallel node and
    ;; compile children under it. The runtime executes the children
    ;; concurrently (independent work only). The framework prompt documents
    ;; :parallel as a supported tree DSL node type; without this branch the
    ;; model's emit-tree! produced trees that failed with "Unknown tree
    ;; node type: sheet/parallel".
    (and (seq? tree) (= 'sheet/parallel (first tree)))
    (let [par-result (run-command! context
                       (make-create-node-command sheet-id :parallel :parent-id parent-id :index index))
          par-id (-> par-result :command-result/events first :node-id)
          children (rest tree)
          child-results (vec (map-indexed
                               (fn [idx child-tree]
                                 (compile-tree-node context sheet-id child-tree par-id :index idx))
                               children))
          all-fn-keys (vec (mapcat :ephemeral-fn-keys child-results))]
      {:node-id par-id
       :ephemeral-fn-keys all-fn-keys})

    ;; LLM node: (sheet/llm :instruction "..." :reads [...] :writes [...] :retry {...})
    (and (seq? tree) (= 'sheet/llm (first tree)))
    (let [opts (parse-keyword-args (rest tree))
          leaf-result (run-command! context
                        (make-create-node-command sheet-id :leaf :parent-id parent-id :index index))
          leaf-id (-> leaf-result :command-result/events first :node-id)
          reads (vec (:reads opts))
          writes (vec (:writes opts))]
      ;; Set instruction
      (run-command! context
        {:command/name :sheet/set-node-instruction
         :command/id (random-uuid)
         :command/timestamp (time/now)
         :sheet-id sheet-id
         :node-id leaf-id
         :instruction (:instruction opts)})
      ;; Set I/O
      (run-command! context
        {:command/name :sheet/set-node-io
         :command/id (random-uuid)
         :command/timestamp (time/now)
         :sheet-id sheet-id
         :node-id leaf-id
         :reads reads
         :writes writes})
      ;; Set retry config if present
      (when (:retry opts)
        (run-command! context
          {:command/name :sheet/set-node-retry
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id leaf-id
           :retry (:retry opts)}))
      {:node-id leaf-id
       :ephemeral-fn-keys []})

    ;; Map-each node: (sheet/map-each :from :chunks :as :chunk :into :results child-node)
    (and (seq? tree) (= 'sheet/map-each (first tree)))
    (let [;; Parse args - last element is the child, rest are keyword args
          args (rest tree)
          ;; Find child node (last non-keyword element or element after last keyword-value pair)
          child-node (last args)
          keyword-args (butlast args)
          opts (parse-keyword-args keyword-args)
          ;; Create map-each node
          map-each-result (run-command! context
                            (make-create-node-command sheet-id :map-each :parent-id parent-id :index index))
          map-each-id (-> map-each-result :command-result/events first :node-id)]
      ;; Configure map-each with source, item, output keys, and optional max-concurrency
      (run-command! context
        (cond-> {:command/name :sheet/set-map-each-config
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :sheet-id sheet-id
                 :node-id map-each-id
                 :source-key (:from opts)
                 :item-key (:as opts)
                 :output-key (:into opts)}
          (:max-concurrency opts) (assoc :max-concurrency (:max-concurrency opts))))
      ;; Compile the child node under map-each (child is at index 0)
      (let [child-result (compile-tree-node context sheet-id child-node map-each-id :index 0)]
        {:node-id map-each-id
         :ephemeral-fn-keys (:ephemeral-fn-keys child-result)}))

    ;; Code node: (sheet/code :reads [...] :writes [...] :fn <function-or-string>)
    ;; Two cases for :fn:
    ;;   (a) An inline Clojure function (from :chunk-document / :aggregate
    ;;       transformers in rlm_dsl.clj, OR a model-authored inline fn in
    ;;       an emit-tree! :code node) — register in the ephemeral registry
    ;;       so it survives serialization across the child sheet boundary.
    ;;   (b) A fully-qualified symbol string (rare; ns-resolve at execution
    ;;       time) — pass through directly; ephemeral registry untouched.
    (and (seq? tree) (= 'sheet/code (first tree)))
    (let [opts (parse-keyword-args (rest tree))
          f (:fn opts)
          string-fn? (string? f)
          ;; Inline-fn → ephemeral registry; symbol-string → use directly
          fn-value (if string-fn? f (register-ephemeral-fn! f))
          ephemeral-keys (if string-fn? [] [fn-value])
          ;; Create leaf node
          leaf-result (run-command! context
                        (make-create-node-command sheet-id :leaf :parent-id parent-id :index index))
          leaf-id (-> leaf-result :command-result/events first :node-id)
          reads (vec (:reads opts))
          writes (vec (:writes opts))]
      ;; Set I/O
      (run-command! context
        {:command/name :sheet/set-node-io
         :command/id (random-uuid)
         :command/timestamp (time/now)
         :sheet-id sheet-id
         :node-id leaf-id
         :reads reads
         :writes writes})
      ;; Set executor to :code with the fn reference (qualified symbol or ephemeral key)
      (run-command! context
        {:command/name :sheet/set-node-executor
         :command/id (random-uuid)
         :command/timestamp (time/now)
         :sheet-id sheet-id
         :node-id leaf-id
         :executor :code
         :fn fn-value})
      {:node-id leaf-id
       :ephemeral-fn-keys ephemeral-keys})

    ;; Final node: (final! {:keys [...]})
    ;; This is a marker for output keys - we don't need to create a node
    ;; The outputs come from the blackboard state after LLM nodes execute
    (and (seq? tree) (= 'final! (first tree)))
    {:node-id nil
     :ephemeral-fn-keys []}

    ;; Unknown node type
    :else
    (throw (ex-info (str "Unknown tree node type: " (first tree))
                    {:tree tree}))))

;; =============================================================================
;; Tree Executor
;; =============================================================================

(defn execute-tree
  "Execute a canonical ORC DSL tree via child tick.

   Arguments:
   - tree: Canonical ORC DSL (output of rlm-dsl->orc-dsl)
   - context: Execution context with event-store, cache, etc.
   - options: Map with:
     - :sandbox-vars - Variables from Phase 1 to write to blackboard
     - :blackboard - Additional blackboard inputs
     - :timeout-ms - Execution timeout (default 60000)

   Returns:
   {:status :success/:failure/:timeout
    :outputs {...}
    :usage {...}
    :duration-ms N}

   U6 options:
     :blackboard-schemas - Optional {key schema} map preserving parent
                           schemas (e.g. [:string {:field-type :image}])
                           so vision/audio inputs route correctly through
                           the child sheet's leaf nodes. When absent for a
                           given key, falls back to inferring schema from
                           value type."
  [tree context {:keys [sandbox-vars blackboard blackboard-schemas timeout-ms]
                 :or {timeout-ms 60000
                      blackboard-schemas {}}}]
  (println "[DEBUG Tree] execute-tree starting")
  (println "[DEBUG Tree] sandbox-vars keys:" (keys sandbox-vars))
  (println "[DEBUG Tree] blackboard keys:" (keys blackboard))
  (try
    (let [start-time (System/currentTimeMillis)
          ;; Create ephemeral sheet for this tree execution
          _ (println "[DEBUG Tree] Creating ephemeral sheet...")
          sheet-result (run-command! context
                         (make-create-sheet-command))  ;; Uses unique name
          _ (println "[DEBUG Tree] sheet-result:" (pr-str (select-keys sheet-result [:command-result/events :cognitect.anomalies/category])))
          sheet-id (-> sheet-result :command-result/events first :sheet-id)
          _ (println "[DEBUG Tree] Created sheet:" sheet-id)

          ;; Extract all blackboard keys from tree for pre-declaration
          tree-keys (set (extract-all-keys tree))
          output-keys (extract-output-keys tree)
          _ (println "[DEBUG Tree] Tree keys:" tree-keys)
          _ (println "[DEBUG Tree] Output keys:" output-keys)

          ;; Declare blackboard keys from sandbox-vars
          ;; Use valid Malli schemas (e.g., [:vector :any] not :vector)
          _ (println "[DEBUG Tree] Declaring sandbox-vars keys...")
          _ (doseq [[k v] sandbox-vars]
              (let [schema (cond
                             (string? v) :string
                             (number? v) :number
                             (boolean? v) :boolean
                             (map? v) [:map-of :any :any]
                             (vector? v) [:vector :any]
                             (sequential? v) [:vector :any]
                             :else :any)]
                (run-command! context
                  (make-declare-key-command sheet-id k schema))))

          ;; Declare blackboard keys from inputs.
          ;; U6: If the caller supplied :blackboard-schemas, prefer that
          ;; schema (preserves :field-type :image etc.); else infer from
          ;; value type.
          _ (println "[DEBUG Tree] Declaring blackboard keys...")
          _ (doseq [[k v] blackboard]
              (let [schema (or (get blackboard-schemas k)
                               (cond
                                 (string? v) :string
                                 (number? v) :number
                                 (boolean? v) :boolean
                                 (map? v) [:map-of :any :any]
                                 (vector? v) [:vector :any]
                                 (sequential? v) [:vector :any]
                                 :else :any))]
                (run-command! context
                  (make-declare-key-command sheet-id k schema))))

          ;; U11: collect any :output-schemas declared on :llm nodes in the
          ;; tree. When the model declares the structure of an :llm write
          ;; (e.g. :targets [:vector [:map ...]]), we use that schema when
          ;; declaring the blackboard key so dscloj's complex-spec? path
          ;; triggers JSON-parsing of the LLM response. Without this,
          ;; structured LLM outputs arrive at downstream :code nodes as
          ;; raw JSON text.
          tree-schemas (extract-key-schemas tree)
          ;; Declare any additional keys from tree that aren't already declared.
          ;; Use the model-declared schema if available; else fall back to :any.
          declared-keys (set (concat (keys sandbox-vars) (keys blackboard)))
          _ (doseq [k tree-keys
                    :when (not (contains? declared-keys k))]
              (run-command! context
                (make-declare-key-command sheet-id k (or (get tree-schemas k) :any))))

          ;; Compile the actual tree structure into ORC nodes
          ;; Returns {:node-id N :ephemeral-fn-keys [...]}
          _ (println "[DEBUG Tree] Compiling tree into ORC nodes...")
          compile-result (try
                           (compile-tree-node context sheet-id tree nil)
                           (catch Exception e
                             (println "[DEBUG Tree] COMPILE ERROR:" (.getMessage e))
                             (.printStackTrace e)
                             (throw e)))
          ephemeral-fn-keys (:ephemeral-fn-keys compile-result)
          _ (println "[DEBUG Tree] Compiled. Root node:" (:node-id compile-result))
          _ (println "[DEBUG Tree] Ephemeral fn keys:" ephemeral-fn-keys)

          ;; Execute the child tick
          ;; Pass sandbox-vars as inputs - they'll be seeded into the blackboard
          tick-id (random-uuid)
          _ (println "[DEBUG Tree] Starting tick:" tick-id)
          p (runtime/register-completion! tick-id)
          _ (cp/process-command
              (assoc context :command
                     {:command/id (random-uuid)
                      :command/timestamp (time/now)
                      :command/name :sheet/tick-tree
                      :sheet-id sheet-id
                      :tick-id tick-id
                      :inputs (merge blackboard sandbox-vars)
                      :options {:timeout-ms timeout-ms}}))

          ;; Wait for completion. D-003: the timeout default includes :sheet-id
          ;; and :trace-id so the caller (execute-repl-researcher-rlm) can
          ;; dispatch :sheet cancel-tick on the child tick when Phase 2 exceeds
          ;; its budget.
          _ (println "[DEBUG Tree] Waiting for tick completion (timeout:" timeout-ms "ms)...")
          result (deref p timeout-ms {:status :timeout
                                      :error "Tree execution timed out"
                                      :sheet-id sheet-id
                                      :trace-id tick-id})
          duration-ms (- (System/currentTimeMillis) start-time)
          _ (println "[DEBUG Tree] Tick completed. Status:" (:status result))
          _ (when (:error result) (println "[DEBUG Tree] Error:" (:error result)))
          _ (println "[DEBUG Tree] Output keys:" (keys (:outputs result)))]

      ;; Clean up ephemeral functions after execution completes
      (doseq [fn-key ephemeral-fn-keys]
        (clear-ephemeral-fn! fn-key))

      ;; Enrich :usage with :by-node breakdown by reading the tick's
      ;; node-execution-completed events from the event store. Universal
      ;; per-node token tracking — works for any node type that emitted :usage.
      (let [event-store (:event-store context)
            tenant-id (:tenant-id context)
            by-node (when event-store
                      (compute-by-node-from-tick-events event-store tenant-id tick-id))
            ;; Build trajectory by reading ALL events tagged with the tick.
            ;; Per design: Grain methodology says every event is monitorable,
            ;; so the bookend captures the full per-event log.
            tick-events (when event-store
                          (into [] (es/read event-store
                                     (cond-> {:tags #{[:tick tick-id]}}
                                       tenant-id (assoc :tenant-id tenant-id)))))
            trajectory (vec
                         (for [e (sort-by :event/timestamp tick-events)]
                           (let [body (cond-> {:event-type (:event/type e)
                                               :timestamp (:event/timestamp e)}
                                        (:node-id e) (assoc :node-id (:node-id e))
                                        (:status e) (assoc :status (:status e)))]
                             body)))
            ;; Emit the bookend event. Grain-flow: dispatch a command which
            ;; emits the event. Best-effort — if emission fails, we still
            ;; return the result; the bookend is for downstream learning,
            ;; not for the caller's promise resolution.
            _ (try
                (cp/process-command
                  (assoc context :command
                         {:command/id (random-uuid)
                          :command/timestamp (time/now)
                          :command/name :sheet/record-rlm-tree-execution-completion
                          :sheet-id sheet-id
                          :tick-id tick-id
                          :trajectory trajectory
                          :total-usage (or (:usage result) {})}))
                (catch Exception e
                  (println "[DEBUG Tree] Bookend emission failed:" (.getMessage e))))
            result-with-duration (assoc result :duration-ms duration-ms)]
        (if (seq by-node)
          (update result-with-duration :usage
                  (fn [u] (assoc (or u {}) :by-node by-node)))
          result-with-duration)))
    (catch Exception e
      (println "[DEBUG Tree] EXECUTION ERROR:" (.getMessage e))
      (.printStackTrace e)
      {:status :failure
       :error (str "Tree execution failed: " (.getMessage e))
       :duration-ms 0})))
