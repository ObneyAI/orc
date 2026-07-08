(ns ai.obney.orc.orc-service.core.dsl
  "REPL-friendly DSL for building behavior tree workflows.

   This provides a declarative way to define and build workflows:

   ```clojure
   (def my-workflow
     (workflow \"recommendations\"
       (blackboard
         {:student-profile :map
          :programs [:vector :map]
          :recommendations [:vector :map]})

       (sequence \"main\"
         (code \"fetch-programs\"
           :fn \"myapp.fns/fetch-programs\"
           :reads [:student-profile]
           :writes [:programs])

         (map-each \"score-programs\"
           :from :programs
           :as :current-program
           :into :scored-programs
           :parallel 5
           (parallel \"scoring\"
             (llm \"academic\"
               :model \"google/gemini-2.5-flash\"
               :instruction \"Score academic fit 0-100\"
               :reads [:current-program :student-profile]
               :writes [:academic-score])
             (llm \"career\"
               :model \"google/gemini-2.5-flash\"
               :instruction \"Score career alignment 0-100\"
               :reads [:current-program :student-profile]
               :writes [:career-score]))))))

   ;; Build and execute - idempotent, name is identity
   (def sheet-id (build-workflow! ctx my-workflow))
   (sheet/execute ctx sheet-id {:student-profile {...}})

   ;; Rebuild after changes - same sheet-id, nodes updated
   (build-workflow! ctx modified-workflow)
   ```

   ## Identity Model

   - Workflow name → deterministic sheet-id (UUID v5)
   - Node name → deterministic node-id (UUID v5)
   - All nodes must have unique names within a workflow
   - Reordering nodes preserves their IDs
   - Renaming a node = new identity"
  (:require [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.anomalies.interface :as anomalies]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string])
  (:import [java.security MessageDigest]
           [java.nio ByteBuffer]
           [java.util UUID]))

;; =============================================================================
;; UUID v5 (Deterministic, Name-Based)
;; =============================================================================

(def ^:private ^UUID sheets-namespace
  "Namespace UUID for generating sheet IDs from workflow names."
  #uuid "a1b2c3d4-e5f6-4789-abcd-ef0123456789")

(defn- uuid->bytes
  "Convert a UUID to a 16-byte array."
  [^UUID uuid]
  (let [bb (ByteBuffer/allocate 16)]
    (.putLong bb (.getMostSignificantBits uuid))
    (.putLong bb (.getLeastSignificantBits uuid))
    (.array bb)))

(defn- bytes->uuid
  "Convert a 16-byte array to a UUID with version 5 and variant bits set."
  [^bytes b]
  (let [bb (ByteBuffer/wrap b)]
    ;; Set version 5 (name-based SHA-1)
    (aset b 6 (unchecked-byte (bit-or (bit-and (aget b 6) 0x0f) 0x50)))
    ;; Set variant (RFC 4122)
    (aset b 8 (unchecked-byte (bit-or (bit-and (aget b 8) 0x3f) 0x80)))
    (UUID. (.getLong (ByteBuffer/wrap b))
           (.getLong (ByteBuffer/wrap b 8 8)))))

(defn uuid-v5
  "Generate a deterministic UUID v5 from a namespace UUID and a name string.
   Same inputs always produce the same UUID."
  [^UUID namespace-uuid ^String name]
  (let [md (MessageDigest/getInstance "SHA-1")
        namespace-bytes (uuid->bytes namespace-uuid)
        name-bytes (.getBytes name "UTF-8")]
    (.update md namespace-bytes)
    (.update md name-bytes)
    (let [hash (.digest md)
          uuid-bytes (byte-array 16)]
      (System/arraycopy hash 0 uuid-bytes 0 16)
      (bytes->uuid uuid-bytes))))

(defn sheet-id-for-name
  "Generate a deterministic sheet-id from a workflow name."
  [workflow-name]
  (uuid-v5 sheets-namespace workflow-name))

(defn node-id-for-name
  "Generate a deterministic node-id from a sheet-id and node name."
  [sheet-id node-name]
  (uuid-v5 sheet-id node-name))

;; =============================================================================
;; Content Hashing
;; =============================================================================

(defn- workflow-content-hash
  "Compute a deterministic SHA-256 hash of a workflow definition."
  [workflow-def]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (pr-str workflow-def) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

;; =============================================================================
;; Node Builders (Data Structures)
;; =============================================================================

;; =============================================================================
;; Node Builders (Data Structures)
;; =============================================================================

(defn llm
  "Define an LLM executor leaf node.

   Options:
     :model - OpenRouter model ID (e.g., \"google/gemini-2.5-flash\")
     :instruction - Prompt for the LLM
     :reads - Vector of blackboard keys to read (e.g., [:student-profile :programs])
     :writes - Vector of blackboard keys to write (e.g., [:result])
     :retry - {:max-attempts n :backoff-ms [100 500]}
     :judges - Vector of judge names (defined in sheet/judges)
     :context - Ontology context injection config:
                {:problem-type \"problem:Classification\"
                 :include-patterns true
                 :include-failures true
                 :tree-id uuid  ;; for self-learning
                 :self-learning? true}
     :options - Executor options passed through to DSCloj for this node"
  [name & {:keys [model instruction reads writes retry judges context options]}]
  (cond-> {:node-type :leaf
           :name name
           :executor :ai
           :model model
           :instruction instruction
           :reads (vec reads)
           :writes (vec writes)}
    retry (assoc :retry retry)
    judges (assoc :judges (vec judges))
    context (assoc :context context)
    options (assoc :options options)))

(defn code
  "Define a code executor leaf node.

   Options:
     :fn - Fully-qualified function symbol string
     :reads - Vector of blackboard keys to read (e.g., [:input])
     :writes - Vector of blackboard keys to write (e.g., [:output])
     :retry - {:max-attempts n :backoff-ms [100 500]}
     :judges - Vector of judge names (defined in sheet/judges)"
  [name & {:keys [fn reads writes retry judges]}]
  (cond-> {:node-type :leaf
           :name name
           :executor :code
           :fn fn
           :reads (vec reads)
           :writes (vec writes)
           :retry retry}
    judges (assoc :judges (vec judges))))

(defn condition
  "Define a condition node.

   Options:
     :check - {:key :bb-key :op :equals/:gt/:lt/etc :value expected}
     :on-fail - :failure (default) or :success"
  [name & {:keys [check on-fail]}]
  {:node-type :condition
   :name name
   :check check
   :on-fail (or on-fail :failure)})

(defn llm-condition
  "Define an LLM condition node - uses LLM to evaluate true/false.

   Options:
     :model - OpenRouter model ID (e.g., \"google/gemini-2.5-flash\")
     :instruction - Prompt describing what to evaluate (should be a yes/no question)
     :reads - Vector of blackboard keys to read as context (e.g., [:student-profile])"
  [name & {:keys [model instruction reads]}]
  {:node-type :llm-condition
   :name name
   :model model
   :instruction instruction
   :reads (vec reads)})

(defn repl-researcher
  "Define a repl-researcher node for iterative LLM+code research.

   The node executes an iteration loop where:
   1. LLM generates Clojure code that calls available tools
   2. Code executes in SCI sandbox with tools injected
   3. Stdout and results feed back to LLM
   4. Loop continues until FINAL_ANSWER or max iterations

   Options:
     :model - OpenRouter model ID (e.g., \"google/gemini-2.5-flash\")
     :instruction - Research goal/question
     :reads - Vector of blackboard keys (metadata only shown to LLM, not values)
     :writes - Vector of output keys (e.g., [:final-answer :iterations])
     :mcp-tools - Vector of MCP tool names (require :call-tool-fn in context)
     :browser-tools - Vector of agent-browser tool names (e.g., [\"open\" \"snapshot\" \"click\"])
     :max-iterations - Max research iterations (default 10)
     :rlm - Enable RLM mode with BT primitives (default: false)

   Browser tools are shell-based (no session management) and include:
     open, snapshot, click, fill, type, press, scroll, wait,
     get-text, get-url, get-title, back, forward, screenshot

   RLM Mode:
     When :rlm true, the sandbox gains behavior tree primitives:
     - (llm \"name\" :instruction \"...\" :writes [:key]) - Execute sub-LLM call
     - (sequence \"name\" child1 child2 ...) - Execute in order
     - (parallel \"name\" child1 child2 ...) - Execute concurrently
     - (map-each \"name\" coll f) - Process collection items
     - (fallback \"name\" child1 child2 ...) - First non-nil result
     - (condition \"name\" pred then else) - Branch on predicate
     - (code \"name\" :writes [:key] :body expr) - Pure computation
     - (final! {:key value}) - Capture validated output
     - (get-input :key) - Load full input value
     - inputs - Preview map (metadata only)"
  [name & {:keys [model instruction reads writes mcp-tools browser-tools max-iterations rlm context options]}]
  (cond-> {:node-type :repl-researcher
           :name name
           :model model
           :instruction instruction
           :reads (vec (or reads []))
           :writes (vec (or writes []))
           :mcp-tools (vec (or mcp-tools []))
           :browser-tools (vec (or browser-tools []))
           :max-iterations (or max-iterations 10)}
    (some? rlm) (assoc :rlm rlm)
    ;; :context is the ontology-injection config (same shape :leaf llm nodes
    ;; accept). The runtime apply-ontology-context pipeline reads :context and
    ;; prepends formatted principles to :instruction at execute time. The
    ;; :sheet/set-node-context command + read-model projection are generic;
    ;; this DSL wedge plus the relaxed command-side guard wire it through
    ;; for :repl-researcher.
    context (assoc :context context)
    options (assoc :options options)))

(defn delegate
  "Define a delegate node for subworkflow execution.

   Executes another sheet (workflow) with isolated blackboard,
   mapping inputs from parent and outputs back to parent.
   This enables modular, composable workflow design.

   Options:
     :target-sheet-id - UUID of the sheet to delegate to (required)
     :reads - Vector of blackboard keys to pass as inputs to target
     :writes - Vector of blackboard keys to receive outputs from target
     :timeout-ms - Max execution time for the delegation (default: 300000ms)
     :inherit-ontology? - Share ontology context with target (default: true)

   Example:
   ```clojure
   (delegate \"analyze-data\"
     :target-sheet-id #uuid \"...\"
     :reads [:input-data :config]
     :writes [:analysis-result :metrics]
     :timeout-ms 60000)
   ```"
  [name & {:keys [target-sheet-id reads writes timeout-ms inherit-ontology?]
           :or {inherit-ontology? true}}]
  (cond-> {:node-type :delegate
           :name name
           :target-sheet-id target-sheet-id
           :reads (vec (or reads []))
           :writes (vec (or writes []))}
    timeout-ms (assoc :timeout-ms timeout-ms)
    (some? inherit-ontology?) (assoc :inherit-ontology? inherit-ontology?)))

(defn sequence
  "Define a sequence node (runs children in order, fails on first failure).

   Name is required for stable identity across rebuilds."
  [name & children]
  {:node-type :sequence
   :name name
   :children (vec children)})

(defn fallback
  "Define a fallback node (runs children in order, succeeds on first success).

   Name is required for stable identity across rebuilds."
  [name & children]
  {:node-type :fallback
   :name name
   :children (vec children)})

(defn parallel
  "Define a parallel node (runs all children concurrently).

   Name is required for stable identity across rebuilds.

   Options:
     :success-policy - :all (default), :any, :majority
     :failure-policy - :any (default), :all"
  [name & args]
  (let [[opts children] (if (and (map? (first args))
                                 (contains? (first args) :success-policy))
                          [(first args) (rest args)]
                          [{} args])]
    {:node-type :parallel
     :name name
     :success-policy (or (:success-policy opts) :all)
     :failure-policy (or (:failure-policy opts) :any)
     :children (vec children)}))

(defn map-each
  "Define a map-each node (iterates over a list).

   Options:
     :from - Blackboard key containing the source list (e.g., :programs)
     :as - Blackboard key for the current item (e.g., :current-program)
     :into - Blackboard key for the results list (e.g., :scored-programs)
     :parallel - Max parallel executions (default 1 = sequential)"
  [name & args]
  (let [[opts children] (if (keyword? (first args))
                          ;; Parse keyword args
                          (let [kw-args (take-while #(or (keyword? %) (not (map? %))) args)
                                rest-args (drop (count kw-args) args)
                                opts-map (apply hash-map kw-args)]
                            [opts-map rest-args])
                          [{} args])]
    {:node-type :map-each
     :name name
     :source-key (:from opts)
     :item-key (:as opts)
     :output-key (:into opts)
     :max-concurrency (:parallel opts)
     :preserve-failures? (:preserve-failures? opts)
     :children (vec children)}))

;; =============================================================================
;; Blackboard Schema Builder
;; =============================================================================

(defn blackboard
  "Define the blackboard schema.

   Takes a map of key-name -> malli-schema:
   ```
   (blackboard
     {:input :string
      :items [:vector :map]
      :result :int})
   ```"
  [schema-map]
  {:blackboard-schema schema-map})

;; =============================================================================
;; Judge Definitions
;; =============================================================================

(defn judges
  "Define evaluation judges for the workflow.

   Takes a map of judge-name -> judge-config:
   ```
   (judges
     {:my-completeness
      {:type :completeness
       :criteria \"Must include X, Y, Z\"
       :weight 0.35}
      :my-grounding
      {:type :grounding
       :criteria \"All claims must trace to inputs\"}
      :custom-judge
      {:type :custom
       :sheet-id #uuid \"...\"
       :weight 0.2}})
   ```

   Judge types:
     :grounding - Hallucination detection
     :completeness - Coverage of requirements
     :instruction-following - Task compliance
     :reasoning - Logical coherence
     :custom - User-defined judge sheet"
  [judge-map]
  {:judges-schema judge-map})

;; =============================================================================
;; Workflow Definition
;; =============================================================================

(defn workflow
  "Define a complete workflow.

   Args:
     name - Name of the workflow
     & parts - Blackboard definition, judge definitions, and root node"
  [name & parts]
  (let [bb-def (first (filter :blackboard-schema parts))
        judges-def (first (filter :judges-schema parts))
        root-node (first (filter :node-type parts))]
    (cond-> {:workflow-name name
             :blackboard-schema (:blackboard-schema bb-def)
             :root-node root-node}
      judges-def (assoc :judges-schema (:judges-schema judges-def)))))

;; =============================================================================
;; Build Functions
;; =============================================================================

(defn run-build-command!
  "Run a single build command through the real command processor and CHECK the
   result. If the command returns an anomaly, throw — a failed build must be
   loud, never a silently-swallowed half-build.

   This replaces the deprecated `h/run-and-apply!` (which returned the command
   result unchecked) on the production `build-workflow!` path. The command
   processor stores events for a successful result; on an anomaly it returns a
   map carrying `::anom/category`, which we surface here as an ex-info."
  [ctx command]
  (let [result (cp/process-command (assoc ctx :command command))]
    (if (anomalies/anomaly? result)
      (throw (ex-info (str "build-workflow! command failed: "
                           (:cognitect.anomalies/message result))
                      {:command (:command/name command)
                       :anomaly result}))
      result)))

;; ---------------------------------------------------------------------------
;; Per-sheet build serialization
;; ---------------------------------------------------------------------------
;;
;; The structural replacement of a changed sheet (clear-sheet-content! then
;; build-sheet-content!) is several independently-validated commands. Two
;; concurrent rebuilds of the SAME deterministic sheet-id (the hash-mismatch
;; teardown window) would otherwise interleave delete/declare commands and leave
;; an observable half-built sheet ("still in use" / "not declared" /
;; "already declared" / "Node not found"). We serialize the structural mutation
;; per sheet-id so two rebuilds of one sheet run sequentially rather than
;; interleaving — the cheapest fix that fits the command/event model and is
;; store-agnostic (reproduced on in-memory AND grain SQLite).
;;
;; Locks are interned per sheet-id (deterministic UUID), so distinct sheets
;; never contend with each other; only rebuilds of the same identity serialize.

(defonce ^:private build-locks (java.util.concurrent.ConcurrentHashMap.))

(defn- build-lock-for
  "Return the canonical lock object for a sheet-id, interning a fresh one the
   first time a given sheet-id is seen."
  [sheet-id]
  (.computeIfAbsent build-locks sheet-id
                    (reify java.util.function.Function
                      (apply [_ _] (Object.)))))

(defn- build-node!
  "Recursively build a node and its children. Returns the node-id.
   Node ID is deterministic based on sheet-id and node name (v5 UUID)."
  [ctx sheet-id node parent-id index]
  (let [node-type (:node-type node)
        node-name (:name node)
        ;; Calculate deterministic node-id from name
        node-id (when node-name (node-id-for-name sheet-id node-name))
        ;; Create the node with explicit ID
        create-result (run-build-command! ctx
                        (h/make-create-node-command sheet-id node-type
                          :node-id node-id
                          :parent-id parent-id
                          :index index))]

    ;; Set name
    (when node-name
      (run-build-command! ctx (h/make-set-node-name-command sheet-id node-id node-name)))

    ;; Configure based on node type
    (case node-type
      :leaf
      (do
        ;; Set executor
        (run-build-command! ctx
          (h/make-set-node-executor-command sheet-id node-id (:executor node)
            :model (:model node)
            :fn (:fn node)
            :options (:options node)))
        ;; Set instruction if AI node
        (when (:instruction node)
          (run-build-command! ctx
            (h/make-set-node-instruction-command sheet-id node-id (:instruction node))))
        ;; Set IO
        (run-build-command! ctx
          (h/make-set-node-io-command sheet-id node-id (:reads node) (:writes node)))
        ;; Set retry if configured
        (when-let [retry (:retry node)]
          (run-build-command! ctx
            (h/make-set-node-retry-command sheet-id node-id
              (:max-attempts retry) (:backoff-ms retry))))
        ;; Set judges if configured
        (when-let [judges (:judges node)]
          (run-build-command! ctx
            (h/make-set-node-judges-command sheet-id node-id judges)))
        ;; Set ontology context if configured (for self-learning injection)
        (when-let [context (:context node)]
          (run-build-command! ctx
            (h/make-set-node-context-command sheet-id node-id context))))

      :condition
      (when-let [check (:check node)]
        (run-build-command! ctx
          (h/make-set-node-check-command sheet-id node-id check)))

      :llm-condition
      (do
        ;; Set llm-condition config
        (run-build-command! ctx
          (h/make-set-llm-condition-config-command sheet-id node-id
            (:instruction node) (:reads node)
            :model (:model node))))

      :repl-researcher
      (do
        ;; Set repl-researcher config
        (run-build-command! ctx
          (h/make-set-repl-researcher-config-command sheet-id node-id
            (:instruction node) (:reads node) (:writes node) (:mcp-tools node)
            :model (:model node)
            :max-iterations (:max-iterations node)
            :browser-tools (:browser-tools node)
            :rlm (:rlm node)
            :options (:options node)))
        ;; Set ontology context if configured. The runtime processor's
        ;; apply-ontology-context reads :context from the node and prepends
        ;; formatted principles to :instruction at execute time. The
        ;; :sheet/set-node-context command + read-model projection are
        ;; generic across node types; this build-workflow emit wires the
        ;; pipeline through for :repl-researcher.
        (when-let [context (:context node)]
          (run-build-command! ctx
            (h/make-set-node-context-command sheet-id node-id context))))

      :delegate
      (do
        ;; Set delegate config
        (run-build-command! ctx
          (h/make-set-delegate-config-command sheet-id node-id
            (:target-sheet-id node)
            :reads (:reads node)
            :writes (:writes node)
            :timeout-ms (:timeout-ms node)
            :inherit-ontology? (:inherit-ontology? node))))

      :parallel
      (do
        (run-build-command! ctx
          (h/make-set-parallel-config-command sheet-id node-id
            :success-policy (:success-policy node)
            :failure-policy (:failure-policy node)))
        ;; Build children
        (doseq [[idx child] (map-indexed vector (:children node))]
          (build-node! ctx sheet-id child node-id idx)))

      :map-each
      (do
        (run-build-command! ctx
          (h/make-set-map-each-config-command sheet-id node-id
            (:source-key node) (:item-key node) (:output-key node)
            :max-concurrency (:max-concurrency node)
            :preserve-failures? (:preserve-failures? node)))
        ;; Build children
        (doseq [[idx child] (map-indexed vector (:children node))]
          (build-node! ctx sheet-id child node-id idx)))

      ;; sequence, fallback - just build children
      (doseq [[idx child] (map-indexed vector (:children node))]
        (build-node! ctx sheet-id child node-id idx)))

    node-id))

(defn- delete-node-tree!
  "Delete a node and all its descendants, bottom-up (leaves first)."
  [ctx sheet-id node-id]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (when node
      ;; Delete children first (recursively)
      (doseq [child-id (:children-ids node)]
        (delete-node-tree! ctx sheet-id child-id))
      ;; Now delete this node (no children left)
      (run-build-command! ctx (h/make-delete-node-command sheet-id node-id)))))

(defn- clear-sheet-content!
  "Clear all nodes and blackboard keys from a sheet, preparing it for rebuild."
  [ctx sheet-id]
  (let [sheet (rm/get-sheet ctx sheet-id)
        root-id (:root-node-id sheet)
        bb-keys (rm/get-blackboard-for-sheet ctx sheet-id)]

    ;; Delete node tree bottom-up (leaves first, then root)
    (when root-id
      (delete-node-tree! ctx sheet-id root-id))

    ;; Delete all blackboard keys
    (doseq [bb-entry bb-keys]
      (run-build-command! ctx (h/make-delete-key-command sheet-id (:key bb-entry))))))

(defn- build-sheet-content!
  "Build blackboard, judges, and node tree for a sheet from workflow definition."
  [ctx sheet-id {:keys [blackboard-schema judges-schema root-node]}]
  ;; Declare blackboard keys
  (doseq [[key-name schema] blackboard-schema]
    (run-build-command! ctx
      (h/make-declare-key-command sheet-id key-name schema)))

  ;; Declare judges
  (doseq [[judge-name judge-config] judges-schema]
    (run-build-command! ctx
      (h/make-declare-judge-command sheet-id (name judge-name) judge-config)))

  ;; Build the tree
  (when root-node
    (build-node! ctx sheet-id root-node nil 0)))

(defn build-workflow!
  "Idempotent workflow builder. Creates or updates a workflow by name.

   - If the definition hasn't changed (same content hash): no-op, zero events
   - If the definition has changed: clears and rebuilds the sheet in place
   - If the sheet doesn't exist: creates a new one

   The workflow name is the identity:
   - Sheet ID is deterministic (v5 UUID from workflow name)
   - Node IDs are deterministic (v5 UUID from sheet-id + node name)
   - A SHA-256 content hash of the workflow definition is stored on the sheet
   - Unchanged definitions produce zero events (true no-op)
   - Safe to call on every application startup

   Args:
     ctx - Context with :event-store
     workflow-def - Workflow definition from (workflow ...)

   Concurrency: the hot path (hash match → zero events) is lock-free. Any
   structural mutation (create, or clear+rebuild of a changed sheet) is
   serialized per sheet-id, so two concurrent rebuilds of the SAME sheet run
   sequentially rather than interleaving their delete/declare commands — there
   is no observable half-built sheet. Distinct sheets never contend.

   Returns the sheet-id (deterministic, based on workflow name)."
  [ctx workflow-def]
  (let [{:keys [workflow-name]} workflow-def
        ;; Deterministic sheet-id from workflow name
        sheet-id (sheet-id-for-name workflow-name)
        new-hash (workflow-content-hash workflow-def)]

    ;; Hot path: if the content hash already matches, this is a true no-op
    ;; (zero events). Kept OUTSIDE the lock so unchanged startup builds stay
    ;; lock-free and don't contend.
    (if (= (:content-hash (rm/get-sheet-by-name ctx workflow-name)) new-hash)
      sheet-id

      ;; Structural mutation: serialize per sheet-id so concurrent rebuilds of
      ;; the same identity can't interleave clear/rebuild commands.
      (locking (build-lock-for sheet-id)
        ;; Double-checked: re-read state inside the lock. Another builder may
        ;; have created/rebuilt this sheet to our target hash while we waited.
        (let [existing (rm/get-sheet-by-name ctx workflow-name)]
          (cond
            ;; Already at the desired hash — nothing to do.
            (= (:content-hash existing) new-hash)
            sheet-id

            ;; Existing sheet at a different hash — clear and rebuild in place.
            existing
            (do
              (clear-sheet-content! ctx sheet-id)
              (build-sheet-content! ctx sheet-id workflow-def)
              (run-build-command! ctx (h/make-set-content-hash-command sheet-id new-hash))
              sheet-id)

            ;; No sheet yet — create with deterministic ID, then build.
            :else
            (do
              (run-build-command! ctx (h/make-create-sheet-command
                                       :name workflow-name
                                       :sheet-id sheet-id))
              (build-sheet-content! ctx sheet-id workflow-def)
              (run-build-command! ctx (h/make-set-content-hash-command sheet-id new-hash))
              sheet-id)))))))

(defn build-workflow!!
  "DEPRECATED: Use build-workflow! instead, which is now idempotent by default.

   This function is kept for backwards compatibility but simply delegates
   to build-workflow!."
  {:deprecated "Use build-workflow! instead"}
  [ctx workflow-def]
  (build-workflow! ctx workflow-def))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn print-tree
  "Print a visual representation of a workflow definition."
  ([workflow-def]
   (print-tree (:root-node workflow-def) 0))
  ([node depth]
   (let [indent (apply str (repeat depth "  "))
         node-type (:node-type node)
         name-str (or (:name node) (name node-type))]
     (println (str indent "├── [" (name node-type) "] " name-str
                   (when (:executor node) (str " (" (name (:executor node)) ")"))
                   (when (:model node) (str " model=" (:model node)))))
     (doseq [child (:children node)]
       (print-tree child (inc depth))))))

(defn describe-workflow
  "Print a description of a workflow."
  [workflow-def]
  (println "Workflow:" (:workflow-name workflow-def))
  (println "\nBlackboard Schema:")
  (doseq [[k v] (:blackboard-schema workflow-def)]
    (println "  " (name k) "->" v))
  (println "\nTree Structure:")
  (print-tree workflow-def))

;; =============================================================================
;; Export/Import Functions
;; =============================================================================

(defn- build-node-tree
  "Convert flat nodes map to nested tree structure for export.
   Preserves original node IDs for accurate structural diffing."
  [nodes-by-id root-id]
  (when root-id
    (let [node (get nodes-by-id root-id)]
      (when node
        (cond-> {:id (:id node)
                 :type (:type node)
                 :name (:name node)}
          ;; Leaf-specific fields
          (= :leaf (:type node))
          (merge (cond-> {}
                   (:executor node) (assoc :executor (:executor node))
                   (:model node) (assoc :model (:model node))
                   (:fn node) (assoc :fn (:fn node))
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (:retry node) (assoc :retry (:retry node))
                   (:context node) (assoc :context (:context node))))
          ;; Condition-specific
          (= :condition (:type node))
          (merge (cond-> {}
                   (:check node) (assoc :check (:check node))
                   (:on-fail node) (assoc :on-fail (:on-fail node))))
          ;; LLM-condition-specific
          (= :llm-condition (:type node))
          (merge (cond-> {}
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (:model node) (assoc :model (:model node))))
          ;; Parallel-specific
          (= :parallel (:type node))
          (merge {:success-policy (or (:success-policy node) :all)
                  :failure-policy (or (:failure-policy node) :any)})
          ;; Map-each-specific
          (= :map-each (:type node))
          (merge (cond-> {}
                   (:source-key node) (assoc :source-key (:source-key node))
                   (:item-key node) (assoc :item-key (:item-key node))
                   (:output-key node) (assoc :output-key (:output-key node))
                   (:max-concurrency node) (assoc :max-concurrency (:max-concurrency node))
                   (some? (:preserve-failures? node)) (assoc :preserve-failures? (:preserve-failures? node))))
          ;; Repl-researcher-specific
          (= :repl-researcher (:type node))
          (merge (cond-> {}
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (seq (:mcp-tools node)) (assoc :mcp-tools (:mcp-tools node))
                   (:model node) (assoc :model (:model node))
                   (:max-iterations node) (assoc :max-iterations (:max-iterations node))
                   (some? (:rlm node)) (assoc :rlm (:rlm node))))
          ;; Delegate-specific
          (= :delegate (:type node))
          (merge (cond-> {}
                   (:target-sheet-id node) (assoc :target-sheet-id (:target-sheet-id node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (:delegate-timeout-ms node) (assoc :timeout-ms (:delegate-timeout-ms node))
                   (some? (:inherit-ontology? node)) (assoc :inherit-ontology? (:inherit-ontology? node))))
          ;; Children for composite nodes
          (seq (:children-ids node))
          (assoc :children
                 (vec (keep #(build-node-tree nodes-by-id %) (:children-ids node)))))))))

(defn export-sheet
  "Export a sheet from the event store as an EDN structure.

   Returns a map that can be saved to file and later imported.

   Example:
     (export-sheet ctx sheet-id)
     ;; => {:version 1 :sheet {...} :blackboard-schema {...} :nodes {...}}"
  [ctx sheet-id]
  (let [sheet (rm/get-sheet ctx sheet-id)
        nodes (rm/get-nodes-by-id ctx sheet-id)
        blackboard (rm/get-blackboard-for-sheet ctx sheet-id)]
    {:version 1
     :exported-at (time/now)
     :sheet {:name (:name sheet)
             :id sheet-id}
     :blackboard-schema (into {}
                              (map (fn [bb] [(:key bb) (:schema bb)])
                                   blackboard))
     :nodes (build-node-tree nodes (:root-node-id sheet))}))

(defn- import-node!
  "Recursively import a node and its children from exported structure."
  [ctx sheet-id node parent-id index]
  (let [node-type (:type node)
        ;; Create the node
        create-result (h/run-and-apply! ctx
                        (h/make-create-node-command sheet-id node-type
                          :parent-id parent-id
                          :index index))
        node-id (-> create-result :command-result/events first :node-id)]

    ;; Set name if provided
    (when (:name node)
      (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id node-id (:name node))))

    ;; Configure based on node type
    (case node-type
      :leaf
      (do
        ;; Set executor
        (when (:executor node)
          (h/run-and-apply! ctx
            (h/make-set-node-executor-command sheet-id node-id (:executor node)
              :model (:model node)
              :fn (:fn node))))
        ;; Set instruction if AI node
        (when (:instruction node)
          (h/run-and-apply! ctx
            (h/make-set-node-instruction-command sheet-id node-id (:instruction node))))
        ;; Set IO
        (when (or (seq (:reads node)) (seq (:writes node)))
          (h/run-and-apply! ctx
            (h/make-set-node-io-command sheet-id node-id
              (vec (:reads node)) (vec (:writes node)))))
        ;; Set retry if configured
        (when-let [retry (:retry node)]
          (h/run-and-apply! ctx
            (h/make-set-node-retry-command sheet-id node-id
              (:max-attempts retry) (:backoff-ms retry)))))

      :condition
      (when-let [check (:check node)]
        (h/run-and-apply! ctx
          (h/make-set-node-check-command sheet-id node-id check)))

      :llm-condition
      (when (:instruction node)
        (h/run-and-apply! ctx
          (h/make-set-llm-condition-config-command sheet-id node-id
            (:instruction node) (vec (:reads node))
            :model (:model node))))

      :repl-researcher
      (when (:instruction node)
        (h/run-and-apply! ctx
          (h/make-set-repl-researcher-config-command sheet-id node-id
            (:instruction node) (vec (:reads node)) (vec (:writes node))
            (vec (:mcp-tools node))
            :model (:model node)
            :max-iterations (:max-iterations node)
            :rlm (:rlm node))))

      :delegate
      (when (:target-sheet-id node)
        (h/run-and-apply! ctx
          (h/make-set-delegate-config-command sheet-id node-id
            (:target-sheet-id node)
            :reads (vec (:reads node))
            :writes (vec (:writes node))
            :timeout-ms (:timeout-ms node)
            :inherit-ontology? (:inherit-ontology? node))))

      :parallel
      (do
        (h/run-and-apply! ctx
          (h/make-set-parallel-config-command sheet-id node-id
            :success-policy (:success-policy node)
            :failure-policy (:failure-policy node)))
        ;; Build children
        (doseq [[idx child] (map-indexed vector (:children node))]
          (import-node! ctx sheet-id child node-id idx)))

      :map-each
      (do
        (h/run-and-apply! ctx
          (h/make-set-map-each-config-command sheet-id node-id
            (:source-key node) (:item-key node) (:output-key node)
            :max-concurrency (:max-concurrency node)
            :preserve-failures? (:preserve-failures? node)))
        ;; Build children
        (doseq [[idx child] (map-indexed vector (:children node))]
          (import-node! ctx sheet-id child node-id idx)))

      ;; sequence, fallback - just build children
      (doseq [[idx child] (map-indexed vector (:children node))]
        (import-node! ctx sheet-id child node-id idx)))

    node-id))

(defn import-sheet
  "Import a sheet from an exported EDN structure.

   Creates a new sheet with the same structure. Returns the new sheet-id.

   Example:
     (import-sheet ctx exported-data)"
  [ctx exported]
  (let [sheet-name (get-in exported [:sheet :name])
        blackboard-schema (:blackboard-schema exported)
        root-node (:nodes exported)

        ;; Create new sheet
        sheet-result (h/run-and-apply! ctx
                       (h/make-create-sheet-command :name sheet-name))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]

    ;; Declare blackboard keys
    (doseq [[key-name schema] blackboard-schema]
      (h/run-and-apply! ctx
        (h/make-declare-key-command sheet-id key-name schema)))

    ;; Build the node tree
    (when root-node
      (import-node! ctx sheet-id root-node nil 0))

    sheet-id))

;; =============================================================================
;; File I/O Helpers
;; =============================================================================

(defn save-sheet!
  "Export a sheet and save to file.

   Example:
     (save-sheet! ctx sheet-id \"development/sheets/my-workflow.edn\")"
  [ctx sheet-id filepath]
  (let [exported (export-sheet ctx sheet-id)]
    (io/make-parents filepath)
    (spit filepath (with-out-str (pprint/pprint exported)))
    (println "Saved sheet to:" filepath)
    filepath))

(defn load-sheet!
  "Load a sheet from file and import it. Returns the new sheet-id.

   Example:
     (load-sheet! ctx \"development/sheets/my-workflow.edn\")"
  [ctx filepath]
  (let [exported (read-string (slurp filepath))
        sheet-id (import-sheet ctx exported)]
    (println "Loaded sheet from:" filepath "-> sheet-id:" sheet-id)
    sheet-id))

(defn save-all-sheets!
  "Export all sheets to a directory.

   Example:
     (save-all-sheets! ctx \"development/sheets\")"
  [ctx dir-path]
  (let [sheets (rm/get-sheets-all ctx)]
    (doseq [sheet sheets]
      (let [safe-name (-> (:name sheet)
                          (clojure.string/replace #"[^a-zA-Z0-9-_]" "_"))
            filename (str safe-name ".edn")
            filepath (str dir-path "/" filename)]
        (save-sheet! ctx (:id sheet) filepath)))
    (println "Saved" (count sheets) "sheets to:" dir-path)))

(defn load-all-sheets!
  "Load all sheets from a directory.

   Example:
     (load-all-sheets! ctx \"development/sheets\")"
  [ctx dir-path]
  (let [dir (io/file dir-path)
        files (when (.exists dir)
                (->> (.listFiles dir)
                     (filter #(.endsWith (.getName %) ".edn"))
                     (sort-by #(.getName %))))]
    (doseq [file files]
      (load-sheet! ctx (.getPath file)))
    (println "Loaded" (count files) "sheets from:" dir-path)))

;; =============================================================================
;; DSL Code Generation
;; =============================================================================

(declare node->dsl-form)

;; Dynamic var for optional namespace prefix on generated DSL symbols
(def ^:dynamic *dsl-ns* nil)

(defn- dsl-sym
  "Create a DSL symbol, optionally prefixed with *dsl-ns*."
  [sym-name]
  (if *dsl-ns*
    (symbol (str *dsl-ns*) (name sym-name))
    (symbol (name sym-name))))

(defn- escape-string
  "Escape special characters in strings for Clojure source code."
  [s]
  (when s
    (-> s
        (clojure.string/replace "\\" "\\\\")
        (clojure.string/replace "\"" "\\\"")
        (clojure.string/replace "\n" "\\n")
        (clojure.string/replace "\t" "\\t")
        (clojure.string/replace "\r" "\\r"))))

(defn- build-keyword-args
  "Build keyword argument pairs from a map, filtering nil/empty values.
   Returns a flat sequence of [:key value :key value ...] sorted by key name."
  [opts-map]
  (->> opts-map
       (remove (fn [[_ v]] (or (nil? v)
                               (and (coll? v) (empty? v)))))
       (sort-by (comp name first))
       (mapcat (fn [[k v]] [(keyword (name k)) v]))))

(defn- leaf-node->form
  "Convert a leaf node to llm or code DSL form."
  [node]
  (let [executor (:executor node)
        name (:name node)]
    (case executor
      :ai (let [opts (build-keyword-args
                       {:model (:model node)
                        :instruction (:instruction node)
                        :reads (:reads node)
                        :writes (:writes node)
                        :retry (:retry node)})]
            (if (empty? opts)
              (list (dsl-sym 'llm) name)
              (apply list (dsl-sym 'llm) name opts)))

      :code (let [opts (build-keyword-args
                         {:fn (:fn node)
                          :reads (:reads node)
                          :writes (:writes node)
                          :retry (:retry node)})]
              (if (empty? opts)
                (list (dsl-sym 'code) name)
                (apply list (dsl-sym 'code) name opts)))

      ;; Default fallback for unknown executors
      (list (dsl-sym 'llm) name))))

(defn- condition-node->form
  "Convert a condition node to DSL form."
  [node]
  (let [check (:check node)
        on-fail (:on-fail node)
        ;; Only include on-fail if it's not the default :failure
        opts (build-keyword-args
               (cond-> {:check (when check (into (sorted-map) check))}
                 (and on-fail (not= on-fail :failure))
                 (assoc :on-fail on-fail)))]
    (if (empty? opts)
      (list (dsl-sym 'condition) (:name node))
      (apply list (dsl-sym 'condition) (:name node) opts))))

(defn- llm-condition-node->form
  "Convert an llm-condition node to DSL form."
  [node]
  (let [opts (build-keyword-args
               {:model (:model node)
                :instruction (:instruction node)
                :reads (:reads node)})]
    (if (empty? opts)
      (list (dsl-sym 'llm-condition) (:name node))
      (apply list (dsl-sym 'llm-condition) (:name node) opts))))

(defn- repl-researcher-node->form
  "Convert a repl-researcher node to DSL form."
  [node]
  (let [opts (build-keyword-args
               {:model (:model node)
                :instruction (:instruction node)
                :reads (:reads node)
                :writes (:writes node)
                :mcp-tools (:mcp-tools node)
                :max-iterations (:max-iterations node)
                :rlm (:rlm node)})]
    (if (empty? opts)
      (list (dsl-sym 'repl-researcher) (:name node))
      (apply list (dsl-sym 'repl-researcher) (:name node) opts))))

(defn- delegate-node->form
  "Convert a delegate node to DSL form."
  [node]
  (let [opts (build-keyword-args
               {:target-sheet-id (:target-sheet-id node)
                :reads (:reads node)
                :writes (:writes node)
                :timeout-ms (:delegate-timeout-ms node)
                :inherit-ontology? (:inherit-ontology? node)})]
    (if (empty? opts)
      (list (dsl-sym 'delegate) (:name node))
      (apply list (dsl-sym 'delegate) (:name node) opts))))

(defn- composite-node->form
  "Convert a sequence or fallback node to DSL form."
  [fn-sym node]
  (let [name (:name node)
        children (mapv node->dsl-form (:children node))]
    (apply list (dsl-sym fn-sym) name children)))

(defn- parallel-node->form
  "Convert a parallel node to DSL form."
  [node]
  (let [name (:name node)
        success-policy (:success-policy node)
        failure-policy (:failure-policy node)
        children (mapv node->dsl-form (:children node))
        ;; Only include policies if they differ from defaults
        opts (cond-> {}
               (and success-policy (not= success-policy :all))
               (assoc :success-policy success-policy)
               (and failure-policy (not= failure-policy :any))
               (assoc :failure-policy failure-policy))]
    (if (empty? opts)
      (apply list (dsl-sym 'parallel) name children)
      (apply list (dsl-sym 'parallel) name opts children))))

(defn- map-each-node->form
  "Convert a map-each node to DSL form."
  [node]
  (let [name (:name node)
        children (mapv node->dsl-form (:children node))
        ;; Map from export fields to DSL keyword args
        opts (build-keyword-args
               {:from (:source-key node)
                :as (:item-key node)
                :into (:output-key node)
                :parallel (:max-concurrency node)
                :preserve-failures? (:preserve-failures? node)})]
    (apply list (dsl-sym 'map-each) name (concat opts children))))

(defn- node->dsl-form
  "Convert an exported node to a DSL form (unevaluated Clojure code as data)."
  [node]
  (case (:type node)
    :leaf (leaf-node->form node)
    :condition (condition-node->form node)
    :llm-condition (llm-condition-node->form node)
    :repl-researcher (repl-researcher-node->form node)
    :delegate (delegate-node->form node)
    :sequence (composite-node->form 'sequence node)
    :fallback (composite-node->form 'fallback node)
    :parallel (parallel-node->form node)
    :map-each (map-each-node->form node)
    ;; Default: try as sequence
    (composite-node->form 'sequence node)))

(defn- blackboard->form
  "Convert blackboard schema to DSL form."
  [schema-map]
  (when (and schema-map (seq schema-map))
    (list (dsl-sym 'blackboard) (into (sorted-map) schema-map))))

(defn- workflow->form
  "Convert an exported sheet to a complete workflow DSL form."
  [exported]
  (let [name (get-in exported [:sheet :name])
        bb-schema (:blackboard-schema exported)
        root-node (:nodes exported)
        parts (cond-> []
                (seq bb-schema) (conj (blackboard->form bb-schema))
                root-node (conj (node->dsl-form root-node)))]
    (apply list (dsl-sym 'workflow) name parts)))

;; =============================================================================
;; Pretty Printing
;; =============================================================================

(defn- indent-str
  "Generate indentation string for given level."
  [level]
  (apply str (repeat (* 2 level) " ")))

(declare form->pretty-string)

(defn- format-keyword-args
  "Format keyword arguments with proper indentation for leaf nodes."
  [args indent-level]
  (let [indent (indent-str indent-level)
        pairs (partition 2 args)]
    (->> pairs
         (map (fn [[k v]]
                (str "\n" indent "  " k " "
                     (cond
                       ;; Multi-line long instructions
                       (and (= k :instruction) (string? v) (> (count v) 50))
                       (str "\"" (escape-string v) "\"")
                       ;; String values
                       (string? v)
                       (str "\"" (escape-string v) "\"")
                       ;; Other values
                       :else
                       (pr-str v)))))
         (clojure.string/join))))

(defn- leaf-form->pretty-string
  "Pretty print a leaf node form (llm, code, condition, llm-condition)."
  [form indent-level]
  (let [indent (indent-str indent-level)
        [fn-sym name & args] form]
    (if (empty? args)
      (str "(" fn-sym " \"" (escape-string name) "\")")
      (str "(" fn-sym " \"" (escape-string name) "\""
           (format-keyword-args args indent-level) ")"))))

(defn- composite-form->pretty-string
  "Pretty print a composite node form (sequence, fallback)."
  [form indent-level]
  (let [indent (indent-str indent-level)
        child-indent (indent-str (inc indent-level))
        [fn-sym name & children] form]
    (if (empty? children)
      (str "(" fn-sym " \"" (escape-string name) "\")")
      (str "(" fn-sym " \"" (escape-string name) "\"\n"
           (->> children
                (map #(str child-indent (form->pretty-string % (inc indent-level))))
                (clojure.string/join "\n"))
           ")"))))

(defn- parallel-form->pretty-string
  "Pretty print a parallel node form."
  [form indent-level]
  (let [indent (indent-str indent-level)
        child-indent (indent-str (inc indent-level))
        [fn-sym name first-arg & rest-args] form
        ;; Check if first arg after name is options map
        [opts children] (if (map? first-arg)
                          [first-arg rest-args]
                          [nil (if first-arg (cons first-arg rest-args) [])])]
    (if (and (nil? opts) (empty? children))
      (str "(" fn-sym " \"" (escape-string name) "\")")
      (str "(" fn-sym " \"" (escape-string name) "\""
           (when opts (str " " (pr-str opts)))
           (when (seq children)
             (str "\n"
                  (->> children
                       (map #(str child-indent (form->pretty-string % (inc indent-level))))
                       (clojure.string/join "\n"))))
           ")"))))

(defn- map-each-form->pretty-string
  "Pretty print a map-each node form."
  [form indent-level]
  (let [indent (indent-str indent-level)
        child-indent (indent-str (inc indent-level))
        [fn-sym name & args] form
        ;; Separate keyword args from children
        [kw-args children] (loop [remaining args
                                   kw-acc []
                                   child-acc []]
                             (if (empty? remaining)
                               [kw-acc child-acc]
                               (let [item (first remaining)]
                                 (if (keyword? item)
                                   (recur (drop 2 remaining)
                                          (conj kw-acc item (second remaining))
                                          child-acc)
                                   (recur (rest remaining)
                                          kw-acc
                                          (conj child-acc item))))))]
    (str "(" fn-sym " \"" (escape-string name) "\""
         ;; Keyword args on same line
         (->> (partition 2 kw-args)
              (map (fn [[k v]]
                     (str "\n" indent "  " k " "
                          (if (string? v)
                            (str "\"" (escape-string v) "\"")
                            (pr-str v)))))
              (clojure.string/join))
         ;; Children indented
         (when (seq children)
           (str "\n"
                (->> children
                     (map #(str child-indent (form->pretty-string % (inc indent-level))))
                     (clojure.string/join "\n"))))
         ")")))

(defn- blackboard-form->pretty-string
  "Pretty print a blackboard form."
  [form indent-level]
  (let [[fn-sym schema-map] form]
    (str "(" fn-sym "\n"
         (indent-str (inc indent-level))
         (pr-str schema-map) ")")))

(defn- workflow-form->pretty-string
  "Pretty print a complete workflow form."
  [form]
  (let [[fn-sym wf-name & parts] form
        bb-form (first (filter #(and (list? %) (= "blackboard" (name (first %)))) parts))
        root-form (first (filter #(and (list? %) (not= "blackboard" (name (first %)))) parts))]
    (str "(" fn-sym " \"" (escape-string wf-name) "\"\n"
         (when bb-form
           (str "  " (blackboard-form->pretty-string bb-form 1) "\n\n"))
         (when root-form
           (str "  " (form->pretty-string root-form 1)))
         ")")))

(defn- sym-name=
  "Compare symbol name, ignoring namespace prefix."
  [sym name-str]
  (= (name sym) name-str))

(defn- form->pretty-string
  "Convert a DSL form to a pretty-printed string."
  [form indent-level]
  (let [fn-sym (when (list? form) (first form))
        fn-name (when fn-sym (name fn-sym))]
    (cond
      ;; Workflow - top level
      (and fn-sym (= "workflow" fn-name))
      (workflow-form->pretty-string form)

      ;; Composite nodes
      (and fn-sym (contains? #{"sequence" "fallback"} fn-name))
      (composite-form->pretty-string form indent-level)

      ;; Parallel
      (and fn-sym (= "parallel" fn-name))
      (parallel-form->pretty-string form indent-level)

      ;; Map-each
      (and fn-sym (= "map-each" fn-name))
      (map-each-form->pretty-string form indent-level)

      ;; Leaf nodes
      (and fn-sym (contains? #{"llm" "code" "condition" "llm-condition"} fn-name))
      (leaf-form->pretty-string form indent-level)

      ;; Blackboard
      (and fn-sym (= "blackboard" fn-name))
      (blackboard-form->pretty-string form indent-level)

      ;; Default - use pr-str
      :else (pr-str form))))

;; =============================================================================
;; Public DSL Generation API
;; =============================================================================

(defn export-to-dsl
  "Generate idiomatic Clojure DSL code from an exported sheet structure.

   Returns a string containing valid Clojure code that, when evaluated with
   the DSL namespace required, produces a workflow definition equivalent
   to the original sheet.

   Options:
     :pretty? - Pretty-print with indentation (default true)
     :ns      - Namespace prefix for DSL functions (e.g., \"sheet\" produces sheet/workflow)

   Example:
     (export-to-dsl (export-sheet ctx sheet-id))
     ;; => \"(workflow \\\"my-workflow\\\" ...)\"

     (export-to-dsl (export-sheet ctx sheet-id) :ns \"sheet\")
     ;; => \"(sheet/workflow \\\"my-workflow\\\" ...)\"

   Round-trip usage:
     (let [exported (export-sheet ctx sheet-id)
           dsl-code (export-to-dsl exported)
           workflow-def (eval (read-string dsl-code))]
       (build-workflow! ctx workflow-def))"
  [exported & {:keys [pretty? ns] :or {pretty? true}}]
  (binding [*dsl-ns* ns]
    (let [form (workflow->form exported)]
      (if pretty?
        (form->pretty-string form 0)
        (pr-str form)))))

(defn sheet->dsl
  "Generate DSL code directly from a sheet-id.

   Convenience function combining export-sheet and export-to-dsl.

   Example:
     (sheet->dsl ctx sheet-id)"
  [ctx sheet-id & opts]
  (let [exported (export-sheet ctx sheet-id)]
    (apply export-to-dsl exported opts)))

(defn save-sheet-as-dsl!
  "Export a sheet to a .clj file containing DSL code.

   The generated file can be loaded and evaluated to recreate the workflow.

   Example:
     (save-sheet-as-dsl! ctx sheet-id \"development/sheets/my-workflow.clj\")"
  [ctx sheet-id filepath]
  (let [dsl-code (sheet->dsl ctx sheet-id)]
    (io/make-parents filepath)
    (spit filepath dsl-code)
    (println "Saved sheet as DSL to:" filepath)
    filepath))
