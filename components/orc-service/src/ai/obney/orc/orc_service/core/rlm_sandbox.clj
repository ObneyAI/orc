(ns ai.obney.orc.orc-service.core.rlm-sandbox
  "RLM (Research Language Model) sandbox for behavior tree construction.

   Extends the SCI sandbox with BT primitives that execute immediately:
   - (llm \"name\" :instruction \"...\" :writes [:key]) - Execute LLM node
   - (sequence \"name\" child1 child2 ...) - Execute children in order
   - (parallel \"name\" child1 child2 ...) - Execute children (conceptually parallel)
   - (map-each \"name\" coll f) - Apply function f to each item, returns vector of results
   - (fallback \"name\" child1 child2 ...) - Returns first non-nil child result
   - (condition \"name\" pred then else) - Returns then if pred is true, else otherwise
   - (code \"name\" :writes [:key] :body expr) - Pure computation, no LLM call
   - (final! {:key value}) - Capture result with writes validation
   - (get-input :key) - Load input value into variable space

   R-2 — in recursive RLM mode (:rlm {:recursive? true}), additional drill-down
   primitives are exposed for inspecting prior tree executions:
   - (tree-detail), (tree-detail tick-id)
   - (tree-trajectory), (tree-trajectory tick-id)
   - (tree-failures)
   - (node-output node-id), (node-input-profile node-id)

   Key principle: Separates variable space (sandbox memory) from token space
   (what the LLM sees). Large data stays in variable space; only sliced data
   goes to sub-LLM calls."
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [clojure.set]
            [dscloj.core :as dscloj]
            [litellm.router :as litellm-router]
            [malli.core :as m]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.core.sci-sandbox :as base-sandbox]
            [ai.obney.orc.orc-service.core.rlm-dsl :as rlm-dsl]
            [ai.obney.orc.orc-service.core.rlm-drill-down :as drill]
            [com.brunobonacci.mulog :as u])
  (:import [java.io StringWriter]))

;; =============================================================================
;; Input Preview (Variable Space vs Token Space)
;; =============================================================================

(defn- preview-string
  "Preview a string value with head/tail truncation for large strings."
  [s max-preview]
  (let [len (count s)]
    (if (<= len max-preview)
      {:type :string :size len :value s}
      (let [head-size (quot max-preview 2)
            tail-size (- max-preview head-size 20)  ;; Leave room for "[...N chars...]"
            head (subs s 0 head-size)
            tail (subs s (- len tail-size))
            hidden (- len head-size tail-size)]
        {:type :string
         :size len
         :preview (str head " [..." hidden " chars...] " tail)}))))

(defn- preview-map
  "Preview a map showing its keys and structure."
  [m]
  {:type :map
   :keys (vec (keys m))
   :size (count m)})

(defn- preview-vector
  "Preview a vector showing length and sample."
  [v max-sample]
  {:type :vector
   :length (count v)
   :sample (vec (take max-sample v))})

(defn preview-value
  "Create a preview of a value for LLM context (token space).

   Small values are shown in full. Large values get metadata preview:
   - Strings: head + tail with middle truncated
   - Maps: key names and size
   - Vectors: length and sample elements"
  [value & {:keys [max-string max-sample] :or {max-string 500 max-sample 3}}]
  (cond
    (nil? value)
    {:type :nil :value nil}

    (string? value)
    (preview-string value max-string)

    (map? value)
    (preview-map value)

    (vector? value)
    (preview-vector value max-sample)

    (coll? value)
    (preview-vector (vec value) max-sample)

    :else
    {:type (keyword (.getSimpleName (type value))) :value value}))

(defn build-inputs-preview
  "Build preview map of all inputs for LLM context."
  [inputs]
  (reduce-kv
   (fn [acc k v]
     (assoc acc k (preview-value (:value v))))
   {}
   inputs))

;; =============================================================================
;; LLM Primitive Execution
;; =============================================================================

(defn- get-provider-with-model
  "Get or create a provider config with the specified model."
  [provider model-override]
  (if (and model-override (keyword? provider))
    (let [model-provider-name (keyword (str (name provider) "/" model-override))
          existing (litellm-router/get-config model-provider-name)]
      (when-not existing
        (let [base-config (litellm-router/get-config provider)]
          (when base-config
            (litellm-router/register! model-provider-name
                                      (assoc base-config :model model-override)))))
      model-provider-name)
    provider))

(defn- schema-field-type
  "Extract :field-type from a Malli schema's properties map, if present.
   Mirrors the same helper in executor.clj's build-field — kept inline here
   to avoid a cross-file dependency for a few LOC.

   Returns the field-type keyword (e.g. :image) or nil."
  [schema]
  (when (and schema (vector? schema))
    (try
      (:field-type (m/properties schema))
      (catch Exception _ nil))))

(defn execute-llm-primitive
  "Execute an LLM primitive from the RLM sandbox.

   Args:
     name - Node name for tracing
     opts - {:instruction \"...\" :writes [:key] :model \"...\" :reads [...]}
     context - {:provider :openrouter :parent-trace-id uuid :blackboard {...} :usage-tracker atom}

   Returns {:outputs {...} :usage {:prompt_tokens N :completion_tokens N :total_tokens N}}

   Sub-LLM calls receive FULL values from :reads. The generated code is responsible
   for managing chunk sizes appropriately. Previews are only used for the code-generating
   LLM to understand variable shapes - actual data processing uses full values.

   U5: For inputs whose blackboard schema carries :field-type :image (or any
   other field-type), the dscloj module's input field is given :type so that
   dscloj's build-message-content routes the value as a multimodal content
   block rather than as inline text. This is the Phase-1 mirror of
   executor.clj's build-field behavior for Phase-2 leaf nodes."
  [name opts context]
  (let [{:keys [instruction writes model reads]} opts
        {:keys [provider blackboard sandbox-vars usage-tracker]} context
        ;; Build inputs from reads - pass FULL values (no truncation)
        ;; The generated code manages chunk sizes; we don't second-guess it
        inputs (reduce (fn [acc k]
                         (let [v (or (get sandbox-vars k)
                                     (get-in blackboard [k :value]))]
                           (if (some? v)
                             (assoc acc k v)  ;; Full value, no truncation
                             acc)))
                       {}
                       (or reads []))
        ;; Build DSCloj module — U5: propagate :field-type from the blackboard
        ;; schema for each read key so vision/audio/etc. inputs get routed
        ;; as proper multimodal content blocks, not as inline text.
        module {:inputs (mapv (fn [[k _v]]
                                (let [schema (get-in blackboard [k :schema])
                                      ft (schema-field-type schema)]
                                  (cond-> {:name k
                                           :spec :any
                                           :description (str "Input: " (clojure.core/name k))}
                                    ft (assoc :type ft))))
                              inputs)
                :outputs (mapv (fn [k]
                                 {:name k
                                  :spec :any
                                  :description (str "Output: " (clojure.core/name k))})
                               writes)
                :instructions instruction}
        ;; Get effective provider
        effective-provider (get-provider-with-model provider model)]

    (u/trace ::rlm-llm-primitive
      {:name name :writes writes :model model}
      (try
        ;; :with-metadata? true ensures dscloj returns {:outputs ... :usage ...} instead of just outputs
        (let [result (dscloj/predict effective-provider module inputs {:validate? false :with-metadata? true})
              outputs (or (:outputs result) result)
              usage (:usage result)
              ;; Aggregate usage into tracker if provided
              ;; Handle both snake_case (raw API) and kebab-case (dscloj normalized) keys
              _ (when (and usage-tracker usage)
                  (swap! usage-tracker
                         (fn [u]
                           {:prompt-tokens (+ (:prompt-tokens u 0)
                                              (or (:prompt-tokens usage) (:prompt_tokens usage) 0))
                            :completion-tokens (+ (:completion-tokens u 0)
                                                  (or (:completion-tokens usage) (:completion_tokens usage) 0))
                            :total-tokens (+ (:total-tokens u 0)
                                             (or (:total-tokens usage) (:total_tokens usage) 0))})))]
          ;; Return map with write keys (for sandbox-vars merge)
          (reduce (fn [acc k]
                    (assoc acc k (get outputs k)))
                  {}
                  writes))
        (catch Exception e
          (throw (ex-info (str "LLM primitive '" name "' failed: " (.getMessage e))
                          {:name name :instruction instruction :writes writes}
                          e)))))))

;; =============================================================================
;; Final! Validation
;; =============================================================================

(defn validate-final!
  "Validate that final! output matches declared writes.

   Throws if:
   - Output contains keys not in writes
   - Output is missing required keys from writes"
  [output declared-writes]
  (let [output-keys (set (keys output))
        writes-set (set declared-writes)
        extra-keys (clojure.set/difference output-keys writes-set)
        missing-keys (clojure.set/difference writes-set output-keys)]
    (when (seq extra-keys)
      (throw (ex-info (str "final! contains keys not in :writes declaration: " extra-keys
                           ". Declared writes: " declared-writes)
                      {:extra-keys extra-keys
                       :declared-writes declared-writes
                       :output-keys output-keys})))
    (when (seq missing-keys)
      (throw (ex-info (str "final! is missing required keys from :writes: " missing-keys
                           ". Declared writes: " declared-writes)
                      {:missing-keys missing-keys
                       :declared-writes declared-writes
                       :output-keys output-keys})))
    output))

;; =============================================================================
;; RLM Sandbox Context
;; =============================================================================

(defn build-rlm-context
  "Build a SCI execution context for RLM mode.

   Extends the base sandbox with BT primitives:
   - llm - Execute an LLM node immediately
   - final! - Capture result with validation
   - get-input - Load input value into variable space
   - store! - Store computed value in sandbox-vars
   - get-var - Retrieve value from sandbox-vars
   - inputs - Map of input previews (metadata only)

   Options:
   - :provider - DSCloj provider keyword (e.g., :openrouter)
   - :blackboard - Map of key -> {:key, :schema, :value, :version}
   - :declared-writes - Vector of declared write keys for validation
   - :parent-trace-id - UUID for event tracing
   - :call-tool-fn - Optional MCP tool function
   - :mcp-tools - Optional vector of MCP tool names
   - :browser-tools - Optional vector of browser tool names
   - :sandbox-vars - Optional existing sandbox-vars atom (for persistence across iterations)
   - :usage-tracker - Optional atom for aggregating sub-LLM token usage
   - :recursive? - When true, enables R-2 drill-down primitives (tree-detail, etc.)
   - :event-store - Required when :recursive? true. Used by drill-down primitives.
   - :tenant-id - Optional; passed through to the event-store read"
  [{:keys [provider blackboard declared-writes parent-trace-id
           call-tool-fn mcp-tools browser-tools sandbox-vars usage-tracker
           recursive? event-store tenant-id]}]
  (let [;; Atom to capture final! output
        final-output (atom nil)

        ;; Atom to track sandbox variables (for llm :reads)
        ;; Use provided atom or create new one
        sandbox-vars (or sandbox-vars (atom {}))

        ;; Atom to track token usage across all sub-LLM calls
        ;; Use provided atom or create new one
        usage-tracker (or usage-tracker (atom {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}))

        ;; Context for LLM primitive execution
        exec-context {:provider provider
                      :blackboard blackboard
                      :parent-trace-id parent-trace-id
                      :usage-tracker usage-tracker}

        ;; Build input previews for token space
        inputs-preview (build-inputs-preview blackboard)

        ;; LLM primitive function
        llm-fn (fn [name & args]
                 (let [opts (if (= 1 (count args))
                              (first args)
                              (apply hash-map args))
                       ;; Pass sandbox-vars so llm can read from them
                       ctx (assoc exec-context :sandbox-vars @sandbox-vars)
                       result (execute-llm-primitive name opts ctx)]
                   ;; Store results in sandbox-vars for subsequent access
                   (swap! sandbox-vars merge result)
                   result))

        ;; final! function with validation
        final!-fn (fn [output]
                    (let [validated (validate-final! output declared-writes)]
                      (reset! final-output validated)
                      ;; Return a marker string for detection
                      (str "FINAL_ANSWER: " (pr-str validated))))

        ;; get-input function - loads full value into variable space
        get-input-fn (fn [k]
                       (let [v (get-in blackboard [k :value])]
                         (swap! sandbox-vars assoc k v)
                         v))

        ;; Build safe clojure.core namespace (same as base sandbox).
        ;;
        ;; CRITICAL: DO NOT include special forms / core macros like
        ;; let, if, cond, when, do, fn, def here. SCI handles those NATIVELY.
        ;; Selecting them from (ns-publics 'clojure.core) overrides SCI's
        ;; native handling with clojure.core's macro implementations whose
        ;; macroexpansion emits references to JVM internals
        ;; (e.g. clojure.lang.PersistentArrayMap/createAsIfByAssoc for
        ;; {:keys [...]} destructuring) that SCI can't resolve. The result:
        ;; any inline (fn [{:keys [...]}] ...) or (let [...] ...) form the
        ;; model writes would fail with "Could not resolve symbol" or class
        ;; cast errors. (The base sci-sandbox.clj never had this bug because
        ;; its safe-clojure-core list stops at `class`.)
        safe-clojure-core '[+ - * / mod quot rem
                           = not= < > <= >= compare
                           str pr-str prn-str println print
                           inc dec min max abs
                           first rest next last butlast
                           cons conj concat into
                           map filter remove reduce
                           take drop take-while drop-while
                           partition partition-all partition-by
                           sort sort-by reverse shuffle
                           get get-in assoc assoc-in dissoc update update-in
                           select-keys merge merge-with
                           keys vals contains? find
                           count empty? not-empty seq vec set list
                           apply comp partial juxt
                           identity constantly
                           some every? not-any? not-every?
                           group-by frequencies
                           zipmap interleave interpose
                           repeat range iterate
                           true? false? nil? some? boolean
                           keyword keyword? symbol symbol? string? number? integer? float? map? vector? set? list? coll? seq? fn?
                           name namespace
                           re-find re-matches re-seq
                           subs format
                           type class]
        core-publics (ns-publics 'clojure.core)
        safe-core (select-keys core-publics safe-clojure-core)

        ;; Sequence primitive - executes children in order
        ;; Children are passed as thunks (fn []) or as direct return values
        ;; Since SCI evaluates args before passing, children will be results already
        sequence-fn (fn [name & children]
                      ;; Children are already evaluated by SCI (eager evaluation)
                      ;; Each child should be a map of results from llm calls
                      ;; Merge all results together
                      (let [results (reduce (fn [acc child]
                                              (if (map? child)
                                                (merge acc child)
                                                acc))
                                            {}
                                            children)]
                        ;; Store merged results in sandbox-vars
                        (swap! sandbox-vars merge results)
                        results))

        ;; Parallel primitive - executes children (conceptually in parallel, but SCI is single-threaded)
        ;; For now, same as sequence since SCI evaluates eagerly
        parallel-fn (fn [name & children]
                      (let [results (reduce (fn [acc child]
                                              (if (map? child)
                                                (merge acc child)
                                                acc))
                                            {}
                                            children)]
                        (swap! sandbox-vars merge results)
                        results))

        ;; Map-each primitive - iterates over a collection, injecting each item
        ;; Two syntaxes supported:
        ;; 1. New: (map-each "name" :key :as :item-name (fn [] ...body...))
        ;;    - Looks up collection from :key in sandbox-vars
        ;;    - Injects each item as preview under :item-name
        ;;    - Calls the body function for each item
        ;; 2. Old: (map-each "name" coll f) - backward compat
        ;;    - Applies f directly to each item in coll
        map-each-fn (fn [name & args]
                      (cond
                        ;; New syntax: (map-each "name" :key :as :item-name body-fn)
                        (and (keyword? (first args))
                             (= :as (second args))
                             (keyword? (nth args 2))
                             (fn? (nth args 3)))
                        (let [coll-key (first args)
                              item-name (nth args 2)
                              body-fn (nth args 3)
                              coll (get @sandbox-vars coll-key)]
                          (vec
                           (for [item coll]
                             ;; Inject item as preview into sandbox-vars
                             (do
                               (swap! sandbox-vars assoc item-name item)
                               ;; Call body function
                               (body-fn)))))

                        ;; Old syntax: (map-each "name" coll f) - backward compat
                        (and (coll? (first args))
                             (fn? (second args)))
                        (let [coll (first args)
                              f (second args)]
                          (vec (map f coll)))

                        ;; Error - invalid syntax
                        :else
                        (throw (ex-info (str "Invalid map-each syntax. Use: "
                                             "(map-each \"name\" :key :as :item-name (fn [] ...)) or "
                                             "(map-each \"name\" coll f)")
                                        {:args args}))))

        ;; Fallback primitive - tries children until one succeeds (doesn't throw)
        ;; Note: In SCI, we can't use macros easily, so children are thunks (fn [])
        ;; However, since SCI evaluates eagerly, we need a different approach
        ;; The fallback takes a name and variadic children which are already evaluated
        ;; To handle exceptions, children should be wrapped in try-catch in the code
        ;; For now, fallback just returns the first non-nil, non-exception result
        fallback-fn (fn [name & children]
                      (loop [remaining children]
                        (if (empty? remaining)
                          nil
                          (let [child (first remaining)]
                            (if (and (some? child) (not (instance? Exception child)))
                              (do
                                (when (map? child)
                                  (swap! sandbox-vars merge child))
                                child)
                              (recur (rest remaining)))))))

        ;; Condition primitive - evaluates predicate and executes appropriate branch
        ;; condition(name, pred, then-branch, else-branch)
        ;; Since SCI evaluates eagerly, both branches get evaluated before condition runs
        ;; To make this work properly, branches need to be thunks, but SCI doesn't support that well
        ;; For now, condition takes pre-evaluated branches and just selects based on pred
        condition-fn (fn [name pred then-branch else-branch]
                       (let [result (if pred then-branch else-branch)]
                         (when (map? result)
                           (swap! sandbox-vars merge result))
                         result))

        ;; Code primitive - executes pure computation (no LLM call)
        ;; code(name, :writes [:key], :body <expression>)
        ;; The :body is already evaluated by SCI, so we just wrap it in the write key
        code-fn (fn [name & args]
                  (let [opts (apply hash-map args)
                        writes (:writes opts)
                        body-result (:body opts)
                        ;; Wrap the body result in the first write key
                        result (if (and (= 1 (count writes)) (not (map? body-result)))
                                 {(first writes) body-result}
                                 (if (map? body-result)
                                   body-result
                                   {(first writes) body-result}))]
                    (swap! sandbox-vars merge result)
                    result))

        ;; store! primitive - explicitly store computed value in sandbox-vars
        ;; (store! :name value) -> value
        store!-fn (fn [k v]
                    (swap! sandbox-vars assoc k v)
                    v)

        ;; get-var primitive - retrieve value from sandbox-vars
        ;; (get-var :name) -> value or nil
        get-var-fn (fn [k]
                     (get @sandbox-vars k))

        ;; list-vars primitive - returns all sandbox-vars with previews
        ;; (list-vars) -> {:key1 {:type ... :preview ...}, :key2 ...}
        list-vars-fn (fn []
                       (reduce-kv
                        (fn [acc k v]
                          (assoc acc k (preview-value v)))
                        {}
                        @sandbox-vars))

        ;; emit-tree! primitive - outputs a behavior tree for two-phase execution
        ;; (emit-tree! [:sequence ...]) -> stores both raw S-expr and canonical ORC DSL
        ;; This signals that tree generation is complete and stores the tree for:
        ;; 1. Transformation to canonical ORC DSL
        ;; 2. Storage in ontology for learning
        ;; 3. Execution in phase 2
        emit-tree!-fn (fn [tree]
                        ;; Validate and transform the tree
                        (let [canonical (rlm-dsl/rlm-dsl->orc-dsl tree)]
                          ;; Store both forms
                          (swap! sandbox-vars assoc
                                 :generated-tree canonical
                                 :generated-tree-raw tree)
                          ;; Return the canonical form
                          canonical))

        ;; ---------------------------------------------------------------
        ;; R-2: Drill-down primitives, exposed ONLY when :recursive? true.
        ;;
        ;; These let the model pull detailed tree-execution information on
        ;; demand when the lightweight :tree-results summary isn't enough.
        ;; Each closure reads from the event store using [:tick tick-id] tag
        ;; filtering and delegates to the pure query fns in rlm-drill-down.
        ;;
        ;; Tick-id resolution:
        ;;   - No-arg form: use the MOST RECENT entry in :tree-results.
        ;;   - 1-arg form (tick-id): look up that entry; nil if not found.
        ;;
        ;; The "matching entry" carries both the :tick-id and the :tree-raw
        ;; needed by `tree-detail-from-events`.
        find-tree-result (fn [tick-id]
                           (let [entries (or (:tree-results @sandbox-vars) [])]
                             (if tick-id
                               (first (filter #(= tick-id (:tick-id %)) entries))
                               (last entries))))
        read-tick-events (fn [tick-id]
                           (when tick-id
                             (into [] (es/read event-store
                                        (cond-> {:tags #{[:tick tick-id]}}
                                          tenant-id (assoc :tenant-id tenant-id))))))
        tree-detail-fn (fn
                         ([] (when-let [entry (find-tree-result nil)]
                               (drill/tree-detail-from-events
                                (read-tick-events (:tick-id entry))
                                (:tree-raw entry))))
                         ([tick-id]
                          (when-let [entry (find-tree-result tick-id)]
                            (drill/tree-detail-from-events
                             (read-tick-events (:tick-id entry))
                             (:tree-raw entry)))))
        tree-trajectory-fn (fn
                             ([] (when-let [entry (find-tree-result nil)]
                                   (drill/tree-trajectory-from-events
                                    (read-tick-events (:tick-id entry)))))
                             ([tick-id]
                              (when-let [entry (find-tree-result tick-id)]
                                (drill/tree-trajectory-from-events
                                 (read-tick-events (:tick-id entry))))))
        tree-failures-fn (fn []
                           (when-let [entry (find-tree-result nil)]
                             (drill/tree-failures-from-events
                              (read-tick-events (:tick-id entry)))))
        node-output-fn (fn [node-id]
                         (when-let [entry (find-tree-result nil)]
                           (drill/node-output-from-events
                            (read-tick-events (:tick-id entry))
                            node-id)))
        node-input-profile-fn (fn [node-id]
                                (when-let [entry (find-tree-result nil)]
                                  (drill/node-input-profile-from-events
                                   (read-tick-events (:tick-id entry))
                                   node-id)))
        drill-bindings (when recursive?
                         {'tree-detail tree-detail-fn
                          'tree-trajectory tree-trajectory-fn
                          'tree-failures tree-failures-fn
                          'node-output node-output-fn
                          'node-input-profile node-input-profile-fn})

        ;; RLM bindings - these are the key primitives
        rlm-bindings (merge {'llm llm-fn
                             'final! final!-fn
                             'get-input get-input-fn
                             'store! store!-fn
                             'get-var get-var-fn
                             'list-vars list-vars-fn
                             'inputs inputs-preview
                             'sequence sequence-fn
                             'parallel parallel-fn
                             'map-each map-each-fn
                             'fallback fallback-fn
                             'condition condition-fn
                             'code code-fn
                             'emit-tree! emit-tree!-fn}
                            drill-bindings)

        ;; Combine all bindings
        all-bindings (merge rlm-bindings)

        ;; Create SCI context with all bindings
        sci-ctx (sci/init
                 {:namespaces {'clojure.core safe-core
                               'user rlm-bindings}
                  :bindings all-bindings})]

    ;; Return context with final-output atom accessible
    {:sci-ctx sci-ctx
     :final-output final-output
     :sandbox-vars sandbox-vars
     :usage-tracker usage-tracker}))

(defn execute-rlm-code
  "Execute Clojure code in the RLM sandbox.

   Returns:
   - :stdout - Captured stdout
   - :result - Evaluation result
   - :error - Error message if failed
   - :final-output - The validated output from final! (if called)
   - :sub-llm-usage - Aggregated token usage from all sub-LLM calls"
  [{:keys [sci-ctx final-output usage-tracker]} code-string]
  (let [stdout-writer (StringWriter.)]
    (try
      (let [result (binding [*out* stdout-writer]
                     (sci/eval-string* sci-ctx code-string))]
        {:stdout (str stdout-writer)
         :result (pr-str result)
         :raw-result result
         :error nil
         :final-output @final-output
         :sub-llm-usage @usage-tracker})
      (catch Exception e
        {:stdout (str stdout-writer)
         :result nil
         :raw-result nil
         :error (.getMessage e)
         :final-output @final-output
         :sub-llm-usage @usage-tracker}))))
