(ns ai.obney.orc.orc-service.core.executor
  "DSCloj-based executor for behavior tree leaf nodes.

   This module bridges the gap between the behavior tree's leaf nodes
   and DSCloj's AI execution capabilities.

   Supports multiple executor types:
   - :ai - DSCloj AI execution with optional model selection
   - :code - Clojure function execution
   - :tool - Direct tool invocation (future)
   - :repl-researcher - Iterative LLM+SCI code execution

   Mapping:
   - Node instruction → DSCloj module instructions
   - Node reads + blackboard types → DSCloj module inputs
   - Node writes + blackboard types → DSCloj module outputs
   - Blackboard values → DSCloj input values
   - DSCloj output values → Blackboard writes"
  (:require [dscloj.core :as dscloj]
            [litellm.router :as litellm-router]
            [clojure.string :as str]
            [clojure.set]
            [clojure.walk :as walk]
            [cheshire.core :as json]
            [malli.core :as m]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.core.observability :as obs]
            [ai.obney.orc.orc-service.core.sci-sandbox :as sci-sandbox]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]))

;; Forward declarations
(declare execute-repl-researcher-rlm)

;; =============================================================================
;; D-003: resolve-phase2-budget — pure deep module
;; =============================================================================

(def ^:private phase2-default-budget-ms
  "Hardcoded fallback budget when no :timeout-ms is set on the repl-researcher
   node and no :timeout-ms is set in the parent tick options. Preserves the
   pre-D-003 behavior of a generous 15-minute ceiling for Phase 2."
  900000)

(defn resolve-phase2-budget
  "Resolve the budget that Phase 2 (tree execution) should be allowed to consume,
   given the repl-researcher node config, the parent tick's timeout (if any),
   and the wall-time already spent in Phase 1.

   Lookup order:
     1. (:timeout-ms node)        → :source :node
     2. :parent-timeout-ms arg    → :source :tick
     3. 900_000 ms hardcoded      → :source :hardcoded

   Returns:
     {:total-budget-ms N
      :remaining-ms N             ;; clamped to >= 0
      :source :node | :tick | :hardcoded
      :exhausted? boolean}        ;; true iff remaining <= 0"
  [{:keys [node parent-timeout-ms phase1-elapsed-ms]}]
  (let [[total source] (cond
                         (:timeout-ms node) [(:timeout-ms node) :node]
                         parent-timeout-ms  [parent-timeout-ms :tick]
                         :else              [phase2-default-budget-ms :hardcoded])
        remaining-raw (- total phase1-elapsed-ms)
        remaining (max 0 remaining-raw)]
    {:total-budget-ms total
     :remaining-ms remaining
     :source source
     :exhausted? (<= remaining-raw 0)}))

;; =============================================================================
;; R-1: compute-tree-result-summary — pure deep module
;; =============================================================================

(defn compute-tree-result-summary
  "Build the lightweight :tree-results summary entry for a Phase 2 tree execution.

   Inputs:
     {:phase2-result <result from tree-executor/execute-tree>
      :tick-events   <vec of events tagged with [:tick child-tick-id]>
      :tree-raw      <S-expr the model wrote — from sandbox-vars :generated-tree-raw>
      :writes        [<node :writes declared keys>]}

   Returns a map shaped per the R-1 PRD (factual fields only — no prose,
   no severity, no retry hints, no full trajectory).

   Conditional fields:
     - :failure-indices + :failure-reasons appear only when :status is :partial
       or :failure (derived from D-008's :partial-summary in the map-each's
       :sheet/node-execution-completed event).
     - :phase2-elapsed-ms + :budget-remaining-ms + :nodes-completed-before-cancel
       appear only when :status is :timeout (from D-003's response shape)."
  [{:keys [phase2-result tick-events tree-raw writes]}]
  (let [node-completions (filter #(= :sheet/node-execution-completed (:event/type %))
                                 tick-events)
        ;; D-008 partial-summary lives on the map-each's own completion event.
        ;; There can be zero or more such events; surface them all.
        partial-summaries (keep :partial-summary node-completions)
        ;; Leaf counts — exclude any node that carries :partial-summary, which
        ;; is the D-008 marker that distinguishes map-each parent completions
        ;; from leaf completions. Map-each can emit :partial OR :failure on its
        ;; own completion event (depending on how many children failed), so
        ;; status-filtering alone double-counts the parent into the leaf total.
        leaf-completions (remove :partial-summary
                                 (filter #(contains? #{:success :failure} (:status %))
                                         node-completions))
        succeeded (count (filter #(= :success (:status %)) leaf-completions))
        failed (count (filter #(= :failure (:status %)) leaf-completions))
        total (count leaf-completions)
        writes-set (set writes)
        outputs-keys (vec (filter writes-set (keys (:outputs phase2-result))))
        status (:status phase2-result)
        ;; Combine failure-indices + failure-reasons across all partial-summary
        ;; events (typically only one map-each per tree, but support multiple).
        all-failure-indices (vec (mapcat :failure-indices partial-summaries))
        all-failure-reasons (apply merge {} (map :failure-reasons partial-summaries))]
    (cond-> {:tick-id (:trace-id phase2-result)
             :tree-raw tree-raw
             :status status
             :elapsed-ms (:duration-ms phase2-result)
             :outputs-keys outputs-keys
             :nodes-succeeded succeeded
             :nodes-failed failed
             :nodes-total total
             :usage (:usage phase2-result)}
      (and (contains? #{:partial :failure} status) (seq partial-summaries))
      (assoc :failure-indices all-failure-indices
             :failure-reasons all-failure-reasons)

      (= :timeout status)
      (assoc :phase2-elapsed-ms (:phase2-elapsed-ms phase2-result)
             :budget-remaining-ms (:budget-remaining-ms phase2-result)
             :nodes-completed-before-cancel (count leaf-completions)))))

(defn merge-tree-result-into-sandbox
  "Return new sandbox-vars after a Phase 2 tree execution.

   - Merges Phase 2's :writes-declared output keys into sandbox-vars (NOT input
     blackboard keys, even though they appear in phase2-result :outputs).
   - Appends the summary entry to :tree-results (existing vector preserved;
     nil → []).
   - Dissoc's :generated-tree and :generated-tree-raw so the dispatch doesn't
     re-fire on the same tree on the next iteration."
  [sandbox-vars phase2-result writes summary]
  (let [writes-set (set writes)
        outputs-to-merge (select-keys (:outputs phase2-result) writes-set)
        prior-results (get sandbox-vars :tree-results [])]
    (-> sandbox-vars
        (dissoc :generated-tree :generated-tree-raw)
        (merge outputs-to-merge)
        (assoc :tree-results (conj prior-results summary)))))

;; =============================================================================
;; Levenshtein Distance and Variable Suggestions
;; =============================================================================

(defn levenshtein-distance
  "Calculate the Levenshtein (edit) distance between two strings."
  [s1 s2]
  (let [len1 (count s1)
        len2 (count s2)]
    (cond
      (zero? len1) len2
      (zero? len2) len1
      :else
      (let [matrix (make-array Long/TYPE (inc len1) (inc len2))]
        ;; Initialize first row and column
        (doseq [i (range (inc len1))]
          (aset matrix i 0 (long i)))
        (doseq [j (range (inc len2))]
          (aset matrix 0 j (long j)))
        ;; Fill in the rest of the matrix
        (doseq [i (range 1 (inc len1))
                j (range 1 (inc len2))]
          (let [cost (if (= (nth s1 (dec i)) (nth s2 (dec j))) 0 1)]
            (aset matrix i j
                  (long (min (inc (aget matrix (dec i) j))           ; deletion
                             (inc (aget matrix i (dec j)))           ; insertion
                             (+ (aget matrix (dec i) (dec j)) cost)))))) ; substitution
        (aget matrix len1 len2)))))

(defn suggest-similar-key
  "Suggest a similar key from available keys using Levenshtein distance.
   Returns the most similar key if:
   - The distance is <= half the longer string's length + 1
   - Or one is a prefix/substring of the other
   This allows 'doc' to match 'document' and 'chunk' to match 'chunks'."
  [missing-key available-keys]
  (let [missing-str (name missing-key)
        scored-keys (for [k available-keys
                         :let [k-str (name k)
                               dist (levenshtein-distance missing-str k-str)
                               max-len (max (count missing-str) (count k-str))
                               ;; Allow edit distance up to half the longer string + 1
                               threshold (inc (quot max-len 2))
                               ;; Also match if one is prefix of the other
                               is-prefix? (or (str/starts-with? k-str missing-str)
                                             (str/starts-with? missing-str k-str))]
                         :when (or (<= dist threshold) is-prefix?)]
                     [k dist])
        best (first (sort-by second scored-keys))]
    (first best)))

(defn format-error-with-suggestions
  "Format an error message with helpful suggestions.
   For missing variable errors, includes available variables and suggestions."
  [error-msg available-vars]
  (let [;; Try to extract the missing key from error message
        missing-key-match (re-find #":(\w+)" error-msg)
        missing-key (when missing-key-match (keyword (second missing-key-match)))
        suggestion (when missing-key (suggest-similar-key missing-key available-vars))]
    (str error-msg
         (when (seq available-vars)
           (str "\nAvailable variables: " (str/join ", " (map str available-vars))))
         (when suggestion
           (str "\nDid you mean: " suggestion "?")))))

;; =============================================================================
;; Usage Normalization
;; =============================================================================

(defn- normalize-usage
  "Normalize DSCloj/litellm usage map to kebab-case.
   Handles both snake_case (raw API) and kebab-case (already normalized) inputs."
  [usage]
  (when usage
    {:prompt-tokens (or (:prompt-tokens usage) (:prompt_tokens usage) 0)
     :completion-tokens (or (:completion-tokens usage) (:completion_tokens usage) 0)
     :total-tokens (or (:total-tokens usage) (:total_tokens usage) 0)}))

;; =============================================================================
;; Schema Description Generation
;; =============================================================================

(defn malli-schema->description
  "Generate a human-readable description from a Malli schema for AI context.
   This helps the AI understand what structure to produce.

   Handles Malli schemas with optional properties maps:
     :string                               -> \"string\"
     [:string {:description \"...\"}]      -> \"string\"
     [:map [:field :type]]                 -> \"object with {...}\""
  [schema]
  (cond
    ;; Simple keyword types
    (keyword? schema)
    (case schema
      :string "string"
      :int "integer"
      :double "number"
      :number "number"
      :boolean "boolean (true/false)"
      :any "any value"
      :uuid "UUID string"
      (name schema))

    ;; Vector schemas like [:map ...], [:vector ...], [:enum ...]
    (vector? schema)
    (let [[schema-type & args] schema
          ;; Skip properties map if present (e.g., [:string {:description "..."}])
          args (if (and (seq args) (map? (first args)))
                 (rest args)
                 args)]
      (case schema-type
        :map
        (let [fields (filter vector? args)  ;; Skip property maps
              field-descs (for [field fields]
                            (let [[field-key & rest] field
                                  ;; Handle optional {:optional true} map
                                  opts (when (map? (first rest)) (first rest))
                                  field-schema (if opts (second rest) (first rest))
                                  optional? (:optional opts)]
                              (str (name field-key)
                                   (when optional? "?")
                                   ": " (malli-schema->description field-schema))))]
          (str "object with {" (clojure.string/join ", " field-descs) "}"))

        :vector
        (str "list of " (malli-schema->description (first args)))

        :enum
        (str "one of: " (clojure.string/join ", " (map str args)))

        :maybe
        (str (malli-schema->description (first args)) " (optional)")

        :or
        (str "either " (clojure.string/join " or " (map malli-schema->description args)))

        :map-of
        (let [[key-schema val-schema] args]
          (str "JSON object with " (malli-schema->description key-schema)
               " keys and " (malli-schema->description val-schema) " values"))

        ;; Handle simple type with properties: [:string {:description "..."}]
        ;; This case occurs when the schema-type is a keyword and args is empty after stripping props
        (if (and (keyword? schema-type) (empty? args))
          (malli-schema->description schema-type)
          ;; Default for unknown vector schemas
          (str schema-type " " (clojure.string/join " " (map malli-schema->description args))))))

    ;; Fallback
    :else (pr-str schema)))

(defn- sanitize-field-name
  "Sanitize field name for DSCloj - remove ? and other problematic chars"
  [key-name]
  (-> key-name
      (clojure.string/replace "?" "")
      (clojure.string/replace "!" "")
      (clojure.string/replace #"[^a-zA-Z0-9_-]" "_")))

(defn- extract-schema-description
  "Extract :description from Malli schema properties if present.

   Malli schemas with properties look like:
     [:string {:description \"The question to answer\"}]
     [:map {:description \"A map of...\"} [:field :type]]

   Returns the description string or nil if not present."
  [schema]
  (when (and (vector? schema)
             (> (count schema) 1)
             (map? (second schema)))
    (:description (second schema))))

;; =============================================================================
;; Output Flattening (Python DSPy Alignment)
;; =============================================================================

(defn- map-schema?
  "Check if a schema is a Malli :map schema that should be flattened."
  [schema]
  (and (vector? schema) (= :map (first schema))))

(defn- map-of-schema?
  "Check if a schema is a Malli :map-of schema (dynamic keys, can't be flattened)."
  [schema]
  (and (vector? schema) (= :map-of (first schema))))

(defn- flatten-output-schema
  "Flatten a nested :map schema into separate output fields.

   Given blackboard key 'academic-score' with schema:
     [:map [:score :double] [:reasoning :string] [:keyFactors [:vector :string]]]

   Returns vector of flattened fields:
     [{:name :score :original-key 'academic-score' :nested-key 'score' :spec :double :description ...}
      {:name :reasoning :original-key 'academic-score' :nested-key 'reasoning' :spec :string ...}
      {:name :keyFactors :original-key 'academic-score' :nested-key 'keyFactors' :spec [:vector :string] ...}]

   This matches Python DSPy's approach of having separate output fields.

   Supports custom :description in Malli field options:
     [:map
      [:score [:double {:description \"Academic fit score from 0.0 to 1.0\"}]]
      [:reasoning [:string {:description \"Detailed explanation\"}]]]"
  [key-name schema]
  (if (map-schema? schema)
    ;; Flatten the map fields into separate output fields
    (let [fields (filter vector? (rest schema))]
      (vec
       (for [[field-key & rest] fields
             :let [opts (when (map? (first rest)) (first rest))
                   field-spec (if opts (second rest) (first rest))
                   field-name (name field-key)
                   ;; Extract custom description from field options or nested schema
                   custom-desc (or (:description opts)
                                   (extract-schema-description field-spec))
                   type-desc (malli-schema->description field-spec)
                   ;; Combine custom description with type info
                   description (if custom-desc
                                 (str custom-desc " (" type-desc ")")
                                 (str field-name " - " type-desc))]]
         {:name (keyword field-name)
          :original-key key-name
          :nested-key field-name
          :spec field-spec
          :description description})))
    ;; Not a flattened map - check if it's a map-of (needs JSON guidance)
    (if (map-of-schema? schema)
      (let [custom-desc (extract-schema-description schema)
            type-desc (malli-schema->description schema)]
        [{:name key-name
          :original-key key-name
          :nested-key nil
          :spec schema
          :description (if custom-desc
                         (str custom-desc " - Return a valid JSON object. (" type-desc ")")
                         (str key-name " - Return a valid JSON object. " type-desc))}])
      ;; Regular non-map schema
      (let [custom-desc (extract-schema-description schema)
            type-desc (malli-schema->description schema)]
        [{:name key-name
          :original-key key-name
          :nested-key nil
          :spec schema
          :description (if custom-desc
                         (str custom-desc " (" type-desc ")")
                         (str "Output: " key-name " - " type-desc))}]))))

(defn- reassemble-flattened-outputs
  "Reassemble flattened outputs back into nested structure for blackboard.

   Given DSCloj outputs:
     {:score 0.85 :reasoning '...' :keyFactors [...]}

   And output-mapping:
     {:score {:original-key 'academic-score' :nested-key 'score'}
      :reasoning {:original-key 'academic-score' :nested-key 'reasoning'}
      ...}

   Returns:
     {'academic-score' {:score 0.85 :reasoning '...' :keyFactors [...]}}"
  [raw-outputs output-mapping]
  (reduce-kv
   (fn [acc output-key output-value]
     (if-let [mapping (get output-mapping output-key)]
       (let [original-key (:original-key mapping)
             nested-key (:nested-key mapping)]
         (if nested-key
           ;; Nested field - assoc into nested map
           (update acc original-key assoc (keyword nested-key) output-value)
           ;; Non-nested field - use directly
           (assoc acc original-key output-value)))
       ;; No mapping found, use as-is
       (assoc acc output-key output-value)))
   {}
   raw-outputs))

(defn- schema-field-type
  "Extract :field-type from a Malli schema's properties, if present.
   E.g., [:vector {:field-type :image} :string] → :image"
  [schema]
  (when (and schema (vector? schema))
    (try
      (:field-type (m/properties schema))
      (catch Exception _ nil))))

(defn- build-field
  "Build a DSCloj field definition from a blackboard key and its entry.
   Now uses Malli schemas directly instead of legacy field types.

   If the Malli schema has a :description property (e.g., [:string {:description \"...\"}]),
   it will be used as the field description, combined with type info.
   This aligns with Python DSPy's InputField(desc=\"...\") pattern.

   If the Malli schema has a :field-type property (e.g., [:vector {:field-type :image} :string]),
   it will be set as :type on the DSCloj field definition, enabling multimodal support."
  [key-name blackboard-entry]
  (let [schema (:schema blackboard-entry)
        field-type (schema-field-type schema)
        ;; Extract custom description from Malli schema properties
        custom-desc (extract-schema-description schema)
        type-desc (when schema (malli-schema->description schema))
        ;; Combine: custom description + type info, or fallback to auto-generated
        description (cond
                      ;; Custom description provided - combine with type info
                      (and custom-desc type-desc)
                      (str custom-desc " (" type-desc ")")

                      ;; Custom description only (no type info)
                      custom-desc
                      custom-desc

                      ;; No custom description - use auto-generated
                      type-desc
                      (str "Blackboard key: " key-name " - " type-desc)

                      ;; Fallback when no schema
                      :else
                      (str "Blackboard key: " key-name))]
    (cond-> {:name key-name
             :original-key key-name  ;; Keep original for mapping back
             :spec (or schema :any)  ;; Use the Malli schema directly
             :description description}
      field-type (assoc :type field-type))))

;; =============================================================================
;; Module Builder
;; =============================================================================

(defn build-module
  "Build a DSCloj module from a leaf node and blackboard metadata.

   Args:
     node - The leaf node map with :instruction, :reads, :writes
     blackboard - Map of key -> {:key, :type, :value, :version}

   Returns a DSCloj module map with :inputs, :outputs, :instructions
   and :output-mapping for converting flattened outputs back to nested structure.

   OUTPUT FLATTENING (Python DSPy Alignment):
   When an output has a :map schema, we flatten it into separate fields.
   E.g., 'academic-score' with schema [:map [:score :double] [:reasoning :string]]
   becomes separate fields: 'score', 'reasoning' - matching how Python DSPy works."
  [node blackboard]
  (let [inputs (mapv (fn [key-name]
                       (if-let [entry (get blackboard key-name)]
                         (build-field key-name entry)
                         {:name key-name
                          :original-key key-name
                          :spec :string
                          :description (str "Input: " key-name)}))
                     (:reads node))
        ;; Flatten output schemas to match Python DSPy's approach
        ;; Each :map field becomes a separate output field
        outputs (->> (:writes node)
                     (mapcat (fn [key-name]
                               (if-let [entry (get blackboard key-name)]
                                 (flatten-output-schema key-name (:schema entry))
                                 [{:name key-name
                                   :original-key key-name
                                   :nested-key nil
                                   :spec :string
                                   :description (str "Output: " key-name)}])))
                     vec)
        ;; Warn about map-of schemas - they work but explicit [:map ...] is more reliable
        _ (when (some #(map-of-schema? (:spec %)) outputs)
            (println "[WARN] Node" (:name node) "uses [:map-of ...] schema for LLM output."
                     "Consider using explicit [:map [:field :type] ...] for better reliability."))
        ;; Build mapping from output field name -> {:original-key :nested-key}
        ;; Used for reassembling flattened outputs into nested structure
        output-mapping (into {}
                             (map (fn [o]
                                    [(:name o)
                                     {:original-key (:original-key o)
                                      :nested-key (:nested-key o)}])
                                  outputs))]
    {:inputs inputs
     :outputs outputs
     :instructions (or (:instruction node) "Execute this task.")
     :output-mapping output-mapping}))

(defn- serialize-for-llm
  "Serialize a value for LLM consumption.
   Complex values (maps, vectors) are serialized as JSON.
   Simple values (strings, numbers, booleans) are passed as-is."
  [value]
  (cond
    (nil? value) ""
    (map? value) (json/generate-string value)
    (vector? value) (json/generate-string value)
    (coll? value) (json/generate-string (vec value))
    :else value))

(defn gather-inputs
  "Gather input values from the blackboard for the node's reads.

   Args:
     node - The leaf node with :reads
     blackboard - Map of key -> {:key, :schema, :value, :version}

   Returns a map of keyword -> value for DSCloj (using sanitized names).
   Complex values are serialized as JSON for better LLM understanding.
   Values with :field-type in their schema properties (e.g., :image) are
   passed through raw — they should not be JSON-serialized."
  [node blackboard]
  (reduce (fn [acc key-name]
            (if-let [entry (get blackboard key-name)]
              (let [value (:value entry)
                    ft (schema-field-type (:schema entry))
                    serialized (if ft value (serialize-for-llm value))]
                (assoc acc key-name serialized))
              acc))
          {}
          (:reads node)))

;; =============================================================================
;; PR-Dual-Model: sub-model tree-walk injection
;; =============================================================================

(defn- inject-sub-model
  "Walk a canonical-DSL emit-tree! tree and inject :model sub-model into each
   (sheet/llm ...) form that does not already specify :model.

   No-op when sub-model is nil (single-model setup).

   Used by the Phase-2 dispatch in execute-repl-researcher-rlm to route
   sub-LLM calls through a different model than the Phase-1 researcher
   (e.g. main-LM gpt-5.4 for tree-design + sub-LM gpt-5.1-chat for the
   actual leaf executions, matching predict-rlm's apples-to-apples setup).

   :llm forms with an explicit :model are left untouched."
  [tree sub-model]
  (if (nil? sub-model)
    tree
    (walk/postwalk
      (fn [node]
        (if (and (seq? node)
                 (= 'sheet/llm (first node))
                 (let [opts (try (apply hash-map (rest node)) (catch Exception _ nil))]
                   (and opts (not (contains? opts :model)))))
          (concat node [:model sub-model])
          node))
      tree)))

;; =============================================================================
;; Code Executor
;; =============================================================================

(defn resolve-fn
  "Resolve a fully-qualified function symbol string to a function.
   Also supports ephemeral functions registered via tree-executor for Phase 2.
   Returns {:fn f} on success or {:error msg} on failure."
  [fn-symbol-str]
  (cond
    ;; Check ephemeral function registry first (for Phase 2 tree execution)
    (str/starts-with? fn-symbol-str "ephemeral-fn-")
    (if-let [f (tree-executor/lookup-ephemeral-fn fn-symbol-str)]
      {:fn f}
      {:error (str "Ephemeral function not found: " fn-symbol-str)})

    ;; Standard namespace/function resolution
    :else
    (try
      (let [[ns-str fn-str] (str/split fn-symbol-str #"/")
            ns-sym (symbol ns-str)
            fn-sym (symbol fn-str)]
        ;; Try to find namespace first (may already be loaded)
        (when-not (find-ns ns-sym)
          ;; Only require if namespace not already loaded
          (require ns-sym))
        (if-let [f (ns-resolve (find-ns ns-sym) fn-sym)]
          {:fn (if (var? f) @f f)}
          {:error (str "Function not found: " fn-symbol-str)}))
      (catch Exception e
        {:error (str "Failed to resolve function: " fn-symbol-str " - " (.getMessage e))}))))

(defn execute-code
  "Execute a Clojure function as a leaf node.

   The function receives a context map with:
   - :event-store - The event store (if provided)
   - :inputs - Map of blackboard key -> value for node's reads

   The function should return a map of blackboard key -> value for writes.

   Args:
     node - The leaf node map with :fn (fully-qualified symbol string or inline fn)
     blackboard - Map of key -> {:key, :type, :value, :version}
     context - Additional context (event-store, etc.)

   Returns:
     {:status :success/:failure
      :outputs {string-key value}
      :error string?
      :duration-ms int}"
  [node blackboard context]
  (let [start-time (System/currentTimeMillis)
        fn-or-symbol (:fn node)
        ;; Support both inline functions and symbol strings
        resolved (if (fn? fn-or-symbol)
                   {:fn fn-or-symbol}
                   (resolve-fn fn-or-symbol))]
    (if (:error resolved)
      {:status :failure
       :error (:error resolved)
       :duration-ms (- (System/currentTimeMillis) start-time)}
      (try
        (let [f (:fn resolved)
              ;; Gather inputs from blackboard
              inputs (reduce (fn [acc key-name]
                               (if-let [entry (get blackboard key-name)]
                                 (assoc acc key-name (:value entry))
                                 acc))
                             {}
                             (:reads node))
              ;; Call the function with context
              result (f (assoc context :inputs inputs :execution-context context))
              duration-ms (- (System/currentTimeMillis) start-time)
              writes (:writes node)
              ;; U7: Reconcile the function's return value with the declared :writes.
              ;;
              ;; Accepted shapes:
              ;;   1. A map containing at least one declared write key → keep
              ;;      declared keys only (extra keys ignored).
              ;;   2. A map containing NONE of the declared write keys, with
              ;;      exactly one :write → wrap entire map under that key.
              ;;      (Useful when fns like `assoc-in` return a transformed
              ;;      map and the model declares a single :writes target.)
              ;;   3. A non-map non-nil scalar with exactly one :write
              ;;      → wrap under that key (lets simple transforms like
              ;;      `clojure.string/upper-case` or `frequencies` compose
              ;;      naturally without forcing the model to remember to wrap).
              ;;
              ;; Failure cases:
              ;;   - nil result → fail clearly.
              ;;   - Non-map result with multiple :writes → ambiguous, fail clearly.
              ;;   - Map result with NONE of declared writes AND multiple :writes
              ;;     declared → ambiguous (which key gets the result?), fail clearly.
              writes-set (set writes)
              result-keys (when (map? result) (set (keys result)))
              has-some-write? (and result-keys
                                   (seq (clojure.set/intersection writes-set result-keys)))
              outputs (cond
                        (nil? result) nil
                        ;; Map with at least one declared write → keep declared keys
                        has-some-write? (select-keys result writes)
                        ;; Single-write + non-map scalar → wrap
                        (and (= 1 (count writes)) (not (map? result)))
                        {(first writes) result}
                        ;; Single-write + map (no matching keys) → wrap whole map
                        (and (= 1 (count writes)) (map? result))
                        {(first writes) result}
                        :else nil)]
          (if (map? outputs)
            {:status :success
             :outputs outputs
             :duration-ms duration-ms}
            {:status :failure
             :error (str "Code executor result could not be reconciled with declared :writes "
                         (pr-str writes)
                         ". Function returned: "
                         (cond
                           (nil? result) "nil"
                           (map? result) (str "map with keys " (pr-str (keys result)))
                           :else (str (type result))))
             :duration-ms duration-ms}))
        (catch Exception e
          {:status :failure
           :error (.getMessage e)
           :duration-ms (- (System/currentTimeMillis) start-time)})))))

;; =============================================================================
;; AI Execution
;; =============================================================================

(defn- get-provider-with-model
  "Get or create a provider config with the specified model.

   litellm-clj's router ignores :model in request options when using a registered
   provider keyword. To work around this, when a model override is specified,
   we dynamically register a model-specific provider if it doesn't exist.

   Returns the provider keyword to use (either original or model-specific)."
  [provider model-override]
  (if (and model-override (keyword? provider))
    ;; Create a model-specific provider name
    (let [model-provider-name (keyword (str (name provider) "/" model-override))
          existing (litellm-router/get-config model-provider-name)]
      (when-not existing
        ;; Register the model-specific provider
        (let [base-config (litellm-router/get-config provider)]
          (when base-config
            (litellm-router/register! model-provider-name
                                      (assoc base-config :model model-override)))))
      model-provider-name)
    ;; No override needed, use provider as-is
    provider))

(defn- outputs-have-nil?
  "Check if any output values are nil, including nested maps where all values are nil."
  [outputs]
  (some (fn [v]
          (or (nil? v)
              (and (map? v) (every? nil? (vals v)))))
        (vals outputs)))

(defn execute-ai
  "Execute a leaf node using DSCloj AI.

   Args:
     node - The leaf node map
     blackboard - Map of key -> {:key, :type, :value, :version}
     provider - DSCloj provider keyword (e.g., :openrouter, :anthropic)
     options - Optional DSCloj options map (can include :model, :max-retries, :retry-delay-ms)

   Returns:
     {:status :success/:failure
      :outputs {string-key value} - outputs to write to blackboard
      :error string?             - error message if failed
      :duration-ms int           - execution time
      :usage {:prompt-tokens N :completion-tokens N :total-tokens N} - token usage (when available)
      :model string?}            - model used (when available)

   OUTPUT FLATTENING:
   This function flattens nested :map schemas into separate output fields
   (matching Python DSPy's approach), then reassembles them back into
   nested structure for the blackboard."
  [node blackboard provider & {:keys [options] :or {options {}}}]
  (let [start-time (System/currentTimeMillis)
        module (build-module node blackboard)
        inputs (gather-inputs node blackboard)
        output-mapping (:output-mapping module)
        ;; Remove the mapping from module before passing to DSCloj
        dscloj-module (dissoc module :output-mapping)
        ;; Build effective provider config with model override if specified
        effective-provider (get-provider-with-model provider (:model node))
        ;; Request metadata for usage tracking via :with-metadata? true
        ;; Disable validation since we serialize complex inputs to JSON strings
        ;; Disable function calling - OpenRouter/Gemini don't reliably return tool_calls
        dscloj-options (assoc options :validate? false :with-metadata? true :use-function-calling? false)
        ;; Retry config - defaults to 1 retry with 500ms delay
        max-retries (get options :max-retries 1)
        retry-delay-ms (get options :retry-delay-ms 500)

        ;; Single attempt function
        try-once (fn []
                   (let [result (dscloj/predict effective-provider dscloj-module inputs dscloj-options)
                         ;; DSCloj returns outputs directly as a flat map, not wrapped in {:outputs ...}
                         raw-outputs (or (:outputs result) result)
                         ;; Reassemble flattened outputs back into nested structure
                         outputs (reassemble-flattened-outputs raw-outputs output-mapping)]
                     {:outputs outputs :usage (normalize-usage (:usage result)) :model (or (:model result) (:model node))}))

        ;; Compute backoff delay for a given attempt
        backoff-for (fn [attempt]
                      (if (sequential? retry-delay-ms)
                        (nth retry-delay-ms (min attempt (dec (count retry-delay-ms))))
                        retry-delay-ms))]

    (loop [attempt 0]
      (let [{:keys [outputs usage model error]}
            (try
              (try-once)
              (catch Exception e
                {:error (.getMessage e)}))]
        (cond
          ;; Exception — retry with backoff (handles rate limits, transient errors)
          (and error (< attempt max-retries))
          (do (obs/log-retry!
                {:node-id (:id node) :node-name (:name node)
                 :attempt (inc attempt) :max-attempts (inc max-retries)
                 :reason error :trace-id nil})
              (Thread/sleep (backoff-for attempt))
              (recur (inc attempt)))

          ;; Exception — retries exhausted
          error
          (let [result {:status :failure :error error
                        :duration-ms (- (System/currentTimeMillis) start-time)}]
            (obs/log-ai-execution!
              {:node-id (:id node) :node-name (:name node) :model nil
               :executor :ai :duration-ms (:duration-ms result)
               :status :failure :usage nil :trace-id nil :error error})
            result)

          ;; Nil outputs — retry with backoff (LLM returned empty/unparseable response)
          (and (outputs-have-nil? outputs) (< attempt max-retries))
          (do (obs/log-retry!
                {:node-id (:id node) :node-name (:name node)
                 :attempt (inc attempt) :max-attempts (inc max-retries)
                 :reason "nil outputs" :trace-id nil})
              (Thread/sleep (backoff-for attempt))
              (recur (inc attempt)))

          ;; Success (or retries exhausted with nil outputs)
          :else
          (let [result {:status :success :outputs outputs
                        :duration-ms (- (System/currentTimeMillis) start-time)
                        :usage usage :model model}]
            (obs/log-ai-execution!
              {:node-id (:id node) :node-name (:name node) :model model
               :executor :ai :duration-ms (:duration-ms result)
               :status :success :usage usage :trace-id nil})
            result))))))

(defn execute-llm-condition
  "Execute an LLM condition node - uses LLM to evaluate a yes/no question.

   Args:
     node - The llm-condition node map with :instruction, :reads, :model
     blackboard - Map of key -> {:key, :schema, :value, :version}
     provider - DSCloj provider keyword (e.g., :openrouter)
     options - Optional DSCloj options map

   Returns:
     {:status :success/:failure
      :result boolean?          - the LLM's yes/no answer
      :error string?            - error message if failed
      :duration-ms int          - execution time
      :usage {:prompt-tokens N :completion-tokens N :total-tokens N} - token usage (when available)
      :model string?}           - model used (when available)"
  [node blackboard provider & {:keys [options] :or {options {}}}]
  (let [start-time (System/currentTimeMillis)
        ;; Build inputs from reads
        inputs (mapv (fn [key-name]
                       (if-let [entry (get blackboard key-name)]
                         (build-field key-name entry)
                         {:name key-name
                          :original-key key-name
                          :spec :string
                          :description (str "Input: " key-name)}))
                     (:reads node))
        ;; Build module with fixed boolean output
        module {:inputs inputs
                :outputs [{:name :result
                           :spec :boolean
                           :description "True if the condition is met, false otherwise"}]
                :instructions (:instruction node)}
        ;; Gather input values
        input-values (into {}
                           (for [key-name (:reads node)
                                 :let [entry (get blackboard key-name)]
                                 :when entry]
                             [key-name (:value entry)]))
        ;; Build effective provider config with model override if specified
        effective-provider (get-provider-with-model provider (:model node))
        ;; Request metadata for usage tracking
        ;; Disable validation since inputs may be JSON serialized
        dscloj-options (assoc options :validate? false)]
    (try
      (let [response (dscloj/predict effective-provider module input-values dscloj-options)
            ;; Response now has {:outputs {...} :usage {...} :model "..."}
            bool-result (get-in response [:outputs :result])
            duration-ms (- (System/currentTimeMillis) start-time)]
        {:status :success
         :result (boolean bool-result)
         :duration-ms duration-ms
         :usage (normalize-usage (:usage response))
         :model (:model response)})
      (catch Exception e
        {:status :failure
         :error (.getMessage e)
         :duration-ms (- (System/currentTimeMillis) start-time)}))))

;; =============================================================================
;; REPL Researcher Execution (RLM Pattern)
;; =============================================================================

(defn- build-blackboard-metadata
  "Build metadata description of blackboard variables for LLM context.
   Returns a string describing available variables and their types.
   Values are NOT included - only names, types, and descriptions."
  [node blackboard]
  (let [reads (:reads node)
        writes (:writes node)]
    (str "Available variables:\n"
         (str/join "\n"
                   (for [key-name reads
                         :let [entry (get blackboard key-name)
                               schema (:schema entry)
                               desc (malli-schema->description schema)
                               custom-desc (extract-schema-description schema)]]
                     (str "- " key-name ": " desc
                          (when custom-desc (str " - " custom-desc)))))
         "\n\nOutput variables (write your FINAL_ANSWER to these):\n"
         (str/join "\n"
                   (for [key-name writes
                         :let [entry (get blackboard key-name)
                               schema (:schema entry)
                               desc (malli-schema->description schema)]]
                     (str "- " key-name ": " desc))))))

(defn- build-iteration-history
  "Format iteration history for LLM context.

   The model sees its own prior code, results, stdout, errors, and the
   variables each iteration created — VERBATIM. Truncating any of this
   second-guesses the model and hides what it actually did, degrading
   its ability to reason across iterations (and, in recursive mode,
   to see the full trees it has already emitted)."
  [history]
  (when (seq history)
    (str "\n\n## Previous Iterations\n"
         (str/join "\n\n"
                   (map-indexed
                    (fn [idx {:keys [code result stdout error vars-created]}]
                      (str "### Iteration " (inc idx) "\n"
                           "Code:\n```clojure\n" code "\n```\n"
                           (when (seq stdout) (str "Output:\n" stdout "\n"))
                           (if error
                             (str "Error: " error)
                             (str "Result: " result))
                           (when (seq vars-created)
                             (str "\nVariables created: " (str/join ", " (map str vars-created))))))
                    history)))))

(defn- build-code-generation-module
  "Build DSCloj module for generating Clojure code."
  [node blackboard-metadata history mcp-tools browser-tools]
  (let [has-mcp? (seq mcp-tools)
        has-browser? (seq browser-tools)
        has-namespaced? (some #(str/includes? % "/") mcp-tools)
        mcp-tool-list (str/join ", " mcp-tools)
        browser-tool-list (str/join ", " browser-tools)]
    {:inputs [{:name :task
               :spec :string
               :description "The research task to complete"}
              {:name :context
               :spec :string
               :description "Available variables and their types"}
              {:name :history
               :spec :string
               :description "Results from previous iterations (if any)"}
              {:name :tools
               :spec :string
               :description "Available tools you can call as functions"}]
     :outputs [{:name :code
                :spec :string
                :description "Clojure code to execute. Call tools as functions, use println to log progress, and output FINAL_ANSWER: <result> when done."}]
     :instructions (str "You are a research assistant that writes Clojure code to solve tasks.\n\n"
                        "IMPORTANT RULES:\n"
                        "1. Write valid Clojure code that calls the available tools\n"
                        "2. Use println to log your progress and findings\n"
                        "3. When you have the final answer, output it as: (str \"FINAL_ANSWER: \" your-answer)\n"

                        ;; Browser tools section
                        (when has-browser?
                          (str "\n## BROWSER TOOLS (agent-browser)\n"
                               "Available: " browser-tool-list "\n"
                               "These control a real browser. Key functions:\n"
                               "- (open \"https://url.com\") - Navigate to URL\n"
                               "- (snapshot) - Get accessibility tree with @refs (e.g., @e1, @e2)\n"
                               "- (click \"@e1\") - Click element by ref\n"
                               "- (fill \"@e2\" \"text\") - Fill form field\n"
                               "- (press \"Enter\") - Press key\n"
                               "- (get-text \"@e1\") - Get element text\n"
                               "- (get-title) - Get page title\n"
                               "- (wait 2000) - Wait milliseconds\n\n"
                               "Workflow: open -> snapshot -> interact using @refs -> snapshot to see result\n"))

                        ;; MCP tools section
                        (when has-mcp?
                          (str "\n## MCP TOOLS\n"
                               "Available: " mcp-tool-list "\n"
                               (if has-namespaced?
                                 (str "Namespaced: (server/tool {:arg \"value\"})\n")
                                 "")
                               "Each takes a map of arguments.\n"))

                        "\n## GENERAL\n"
                        "- Use standard Clojure: map, filter, reduce, str, get, get-in, etc.\n"
                        "- Do NOT use require, eval, slurp, or any I/O functions\n\n"
                        "Your task: " (:instruction node))}))

(defn execute-repl-researcher
  "Execute a repl-researcher node using iterative LLM+SCI code execution.

   This implements the RLM (Recursive Language Model) pattern where:
   1. LLM generates Clojure code to call MCP tools
   2. Code executes in a safe SCI sandbox
   3. Results feed back to LLM for next iteration
   4. Converges when FINAL_ANSWER is detected

   When :rlm is true on the node, uses RLM mode with BT primitives:
   - (llm ...) for sub-LLM calls
   - (final! ...) for validated output capture
   - (get-input ...) for loading input values

   Args:
     node - The repl-researcher node map with :instruction, :reads, :writes, :mcp-tools
     blackboard - Map of key -> {:key, :schema, :value, :version}
     provider - DSCloj provider keyword
     context - Context map with :call-tool-fn (fn [tool-name args-map] -> result) for MCP tool calls
     options - Optional DSCloj options map

   Returns:
     {:status :success/:failure
      :outputs {string-key value}
      :iterations [{:code ... :result ... :stdout ...}]
      :final-answer string?
      :error string?
      :duration-ms int
      :usage {:prompt-tokens N :completion-tokens N :total-tokens N}}"
  [node blackboard provider context & {:keys [options] :or {options {}}}]
  ;; Route to RLM mode if enabled
  (if (:rlm node)
    (execute-repl-researcher-rlm node blackboard provider context :options options)
    (let [start-time (System/currentTimeMillis)
          max-iterations (or (:max-iterations node) 10)
        mcp-tools (or (:mcp-tools node) [])
        browser-tools (or (:browser-tools node) [])
        call-tool-fn (:call-tool-fn context)

        ;; Build SCI context with MCP and browser tools injected
        sci-ctx (sci-sandbox/build-sci-context
                 {:call-tool-fn call-tool-fn
                  :mcp-tools mcp-tools
                  :browser-tools browser-tools})

        ;; Build blackboard metadata (types only, no values)
        bb-metadata (build-blackboard-metadata node blackboard)

        ;; Build effective provider config with model override if specified
        effective-provider (get-provider-with-model provider (:model node))

        ;; Track usage across iterations
        total-usage (atom {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})]

    (try
      (loop [iteration 0
             history []]
        (if (>= iteration max-iterations)
          ;; Max iterations reached
          {:status :failure
           :error "Max iterations reached without FINAL_ANSWER"
           :iterations history
           :duration-ms (- (System/currentTimeMillis) start-time)
           :usage @total-usage}

          ;; Generate code using LLM
          (let [;; Get blackboard values for template substitution in instruction
                bb-values (reduce (fn [acc k]
                                    (if-let [entry (get blackboard k)]
                                      (assoc acc (name k) (serialize-for-llm (:value entry)))
                                      acc))
                                  {}
                                  (:reads node))
                ;; Pre-process instruction to substitute template variables like {site-url}
                processed-instruction (reduce (fn [instr [k v]]
                                                (str/replace instr
                                                             (str "{" k "}")
                                                             (if (string? v) v (pr-str v))))
                                              (:instruction node)
                                              bb-values)
                module (build-code-generation-module
                         (assoc node :instruction processed-instruction)
                         bb-metadata history mcp-tools browser-tools)
                all-tools (concat mcp-tools browser-tools)
                inputs {:task (serialize-for-llm processed-instruction)
                        :context bb-metadata
                        :history (or (build-iteration-history history) "None")
                        :tools (str/join ", " all-tools)}
                ;; Note: Don't pass :model in dscloj-options - effective-provider already has it
                ;; Passing :model causes response parsing issues in dscloj
                ;; :with-metadata? true ensures dscloj returns {:outputs ... :usage ...} instead of just outputs
                dscloj-options (assoc options :validate? false :with-metadata? true)

                ;; Generate code - use effective-provider for correct model override
                llm-result (dscloj/predict effective-provider module inputs dscloj-options)
                ;; Extract code from LLM result
                ;; With :with-metadata? true, dscloj returns {:outputs {:code "..."} :usage {...}}
                ;; Code may be a string or a parsed Clojure form (if function calling mode parsed it)
                code (let [raw (or (:code llm-result) (get-in llm-result [:outputs :code]))]
                       (cond
                         (string? raw)
                         (-> raw
                             (str/replace #"^```(?:clojure|clj|edn)?\s*\n?" "")
                             (str/replace #"\n?```\s*$" "")
                             str/trim)

                         (some? raw)
                         ;; Already parsed Clojure form - convert back to string for sandbox
                         (pr-str raw)

                         :else nil))

                ;; Update usage tracking (normalize handles snake_case -> kebab-case)
                _ (when-let [u (normalize-usage (:usage llm-result))]
                    (swap! total-usage
                           (fn [acc]
                             {:prompt-tokens (+ (:prompt-tokens acc 0) (:prompt-tokens u))
                              :completion-tokens (+ (:completion-tokens acc 0) (:completion-tokens u))
                              :total-tokens (+ (:total-tokens acc 0) (:total-tokens u))})))]

            (cond
              ;; No code generated
              (str/blank? code)
              {:status :failure
               :error "LLM did not generate code"
               :iterations history
               :duration-ms (- (System/currentTimeMillis) start-time)
               :usage @total-usage}

              :else
              ;; Execute code in SCI sandbox (always execute, even if code contains FINAL_ANSWER pattern)
              (let [exec-result (sci-sandbox/execute-code sci-ctx code)
                    new-history (conj history
                                      {:code code
                                       :result (:result exec-result)
                                       :stdout (:stdout exec-result)
                                       :error (:error exec-result)})
                    ;; Use raw-result first (unescaped), fall back to pr-str'd result
                    raw-result (when (string? (:raw-result exec-result))
                                 (:raw-result exec-result))
                    result-for-extraction (or raw-result (:result exec-result))]
                (cond
                  ;; Check for FINAL_ANSWER in result or stdout
                  (or (sci-sandbox/contains-final-answer? result-for-extraction)
                      (sci-sandbox/contains-final-answer? (:stdout exec-result)))
                  (let [final-answer (or (sci-sandbox/extract-final-answer result-for-extraction)
                                         (sci-sandbox/extract-final-answer (:stdout exec-result)))
                        write-keys (:writes node)
                        ;; If final-answer is a map, try to spread its values across write keys
                        ;; This handles both single and multiple write keys
                        outputs (if (map? final-answer)
                                  ;; Map case: extract values for each write key
                                  ;; Support both keyword and string keys from LLM
                                  (let [extracted (reduce (fn [acc k]
                                                            (let [kw (if (keyword? k) k (keyword k))
                                                                  str-k (name kw)
                                                                  v (or (get final-answer kw)
                                                                        (get final-answer str-k))]
                                                              (if (some? v)
                                                                (assoc acc kw v)
                                                                acc)))
                                                          {}
                                                          write-keys)]
                                    ;; If we extracted values, use them; otherwise put whole map under first key
                                    (if (seq extracted)
                                      extracted
                                      {(first write-keys) final-answer}))
                                  ;; Non-map: use first key
                                  {(first write-keys) final-answer})]
                    {:status :success
                     :outputs outputs
                     :final-answer final-answer
                     :iterations new-history
                     :duration-ms (- (System/currentTimeMillis) start-time)
                     :usage @total-usage})

                  ;; Check for repeated output (convergence)
                  (sci-sandbox/repeated-output? history exec-result)
                  {:status :failure
                   :error "Output repeated - possible infinite loop"
                   :iterations new-history
                   :duration-ms (- (System/currentTimeMillis) start-time)
                   :usage @total-usage}

                  ;; Continue iteration
                  :else
                  (recur (inc iteration) new-history)))))))

      (catch Exception e
        {:status :failure
         :error (.getMessage e)
         :duration-ms (- (System/currentTimeMillis) start-time)
         :usage @total-usage})))))

;; =============================================================================
;; RLM Mode Execution (BT as Primitive)
;; =============================================================================

(defn- format-variable-preview
  "Format a single variable preview for the Available Variables section."
  [k preview source]
  (let [type-str (name (or (:type preview) :unknown))
        size-str (cond
                   (:size preview) (str (:size preview) " chars")
                   (:length preview) (str (:length preview) " items")
                   :else nil)
        type-size (if size-str
                    (str "(" type-str ", " size-str ")")
                    (str "(" type-str ")"))
        preview-str (cond
                      (:preview preview) (str "  Preview: \"" (:preview preview) "\"")
                      (:value preview) (str "  Value: " (pr-str (:value preview)))
                      (:sample preview) (str "  Sample: " (pr-str (:sample preview)))
                      (:keys preview) (str "  Keys: " (pr-str (:keys preview)))
                      :else nil)]
    (str k " " type-size " " source
         (when preview-str (str "\n" preview-str)))))

(defn- build-available-variables-section
  "Build the Available Variables section for the RLM prompt."
  [blackboard sandbox-vars-map var-creation-times]
  (let [;; Format blackboard variables
        blackboard-entries (for [[k v] blackboard
                                 :let [preview (rlm-sandbox/preview-value (:value v))]]
                             (format-variable-preview k preview "[from blackboard]"))
        ;; Format sandbox variables (created during execution)
        sandbox-entries (for [[k v] sandbox-vars-map
                              :when (not (contains? blackboard k))]  ;; Don't duplicate blackboard keys
                          (let [preview (rlm-sandbox/preview-value v)
                                iteration (get var-creation-times k 0)
                                source (str "[created iteration " iteration "]")]
                            (format-variable-preview k preview source)))]
    (when (or (seq blackboard-entries) (seq sandbox-entries))
      (str "## Available Variables\n\n"
           (str/join "\n\n" (concat blackboard-entries sandbox-entries))
           "\n\n"))))

(defn- build-ontology-examples-section
  "Build a section of the prompt with few-shot examples from ontology.
   Returns nil if no examples available."
  [node]
  (when-let [examples-fn (get-in node [:rlm :examples-fn])]
    (let [examples (try (examples-fn {}) (catch Exception _ []))]
      (when (seq examples)
        (str "## Example from ontology (successful patterns)\n\n"
             (str/join "\n\n"
                       (for [{:keys [task tree score]} examples]
                         (str "**Task**: " task "\n"
                              "**Score**: " (when score (format "%.2f" (double score))) "\n"
                              "```clojure\n"
                              "(emit-tree! " (pr-str tree) ")\n"
                              "```")))
             "\n\n")))))

(defn- build-rlm-code-generation-module
  "Build DSCloj module for generating code in RLM mode.

   In RLM mode, the LLM generates code that can:
   - Call (llm \"name\" :instruction \"...\" :writes [:key]) to execute sub-LLM calls
   - Call (final! {:key value}) to return validated results
   - Call (get-input :key) to load input values into variable space
   - Call store!/get-var to manage computed variables
   - Access 'inputs' map for metadata previews of available data

   U9: When (:rlm node) is a map containing :available-code-nodes (string), that
   catalog is surfaced as an extra dscloj input field so the model can use
   the listed functions inside emit-tree! :code nodes."
  [node inputs-preview history blackboard sandbox-vars-map var-creation-times]
  (let [rlm-config (let [rlm (:rlm node)] (if (map? rlm) rlm {}))
        available-code-nodes (get rlm-config :available-code-nodes)
        base-inputs [{:name :task
                      :spec :string
                      :description "The research task to complete"}
                     {:name :inputs-info
                      :spec :string
                      :description "Available inputs with metadata previews (type, size, sample)"}
                     {:name :history
                      :spec :string
                      :description "Results from previous iterations (if any)"}]
        all-inputs (cond-> base-inputs
                     available-code-nodes
                     (conj {:name :available-code-nodes
                            :spec :string
                            :description "Catalog of pre-built Clojure functions you can reference in emit-tree! :code nodes via {:fn \"ns/sym\" ...}. Read this carefully if present."}))]
    {:inputs all-inputs
   :outputs [{:name :code
              :spec :string
              :description "Clojure code to execute"}]
   :instructions (str "You are an RLM (Recursive Language Model) that constructs behavior trees to solve tasks.\n\n"
                      ;; Two-space architecture explanation
                      "## Two-Space Architecture\n\n"
                      "RLM separates **Variable Space** (full data in sandbox memory) from **Token Space** (what LLMs see).\n"
                      "- Available Variables show PREVIEWS (type, size, sample) - not full content\n"
                      "- Use (get-input :key) to load full values into variable space\n"
                      "- Sub-LLM calls via :reads receive previews, keeping token usage bounded\n\n"
                      ;; Add Available Variables section
                      (build-available-variables-section blackboard sandbox-vars-map var-creation-times)
                      "## Available Primitives\n\n"
                      "### llm - Execute a sub-LLM call\n"
                      "```clojure\n"
                      "(llm \"name\" :instruction \"What to do\" :reads [:key] :writes [:output-key])\n"
                      "```\n"
                      "- Returns a map with the :writes keys populated\n"
                      "- :reads [...] passes FULL values to the sub-LLM (no truncation)\n"
                      "- You control chunk sizes in your code - sub-LLMs receive exactly what you pass\n"
                      "- :reads keys MUST match exact names from Available Variables section\n\n"
                      "### final! - Return validated result\n"
                      "```clojure\n"
                      "(final! {:key value})\n"
                      "```\n"
                      "- MUST include exactly the keys declared in :writes\n"
                      "- Validates output matches contract\n\n"
                      "### get-input - Load full value into variable space\n"
                      "```clojure\n"
                      "(get-input :key)\n"
                      "```\n"
                      "- Returns the full value (not just preview)\n"
                      "- Use this to access input data for processing\n\n"
                      "### store!/get-var - Manage computed variables\n"
                      "```clojure\n"
                      "(store! :name value)  ;; Store and return value\n"
                      "(get-var :name)       ;; Retrieve stored value (nil if not found)\n"
                      "(list-vars)           ;; List all variables with previews\n"
                      "```\n\n"
                      "### sequence - Execute children in order\n"
                      "```clojure\n"
                      "(sequence \"name\" child1 child2 ...)\n"
                      "```\n"
                      "- Executes each child in sequence\n"
                      "- Merges all result maps together\n\n"
                      "### map-each - Process collection items\n"
                      "```clojure\n"
                      "(map-each \"name\" :collection-key :as :item-name\n"
                      "  (fn [] (llm \"process\" :reads [:item-name] :writes [:result])))\n"
                      "```\n"
                      "- Iterates over collection from :collection-key in variables\n"
                      "- Injects each item as :item-name (as preview)\n"
                      "- Body function called for each item\n"
                      "- Returns vector of results\n\n"
                      "### code - Pure computation (no LLM)\n"
                      "```clojure\n"
                      "(code \"name\" :writes [:result] :body (+ 1 2))\n"
                      "```\n"
                      "- Executes pure Clojure computation\n"
                      "- No LLM call involved\n"
                      "- Result stored in :writes key\n\n"
                      "### emit-tree! - Generate a behavior tree for execution\n"
                      "```clojure\n"
                      "(emit-tree! [:sequence\n"
                      "              [:chunk-document {:from :document :size 5000 :into :chunks}]\n"
                      "              [:map-each {:from :chunks :as :chunk :into :results}\n"
                      "                [:llm {:instruction \"Extract key info\" :reads [:chunk] :writes [:info]}]]\n"
                      "              [:aggregate {:from :results :writes [:all-info]}]\n"
                      "              [:final {:keys [:summary]}]])\n"
                      "```\n"
                      "- Emits a behavior tree S-expression for two-phase execution\n"
                      "- Use this when you need to design a processing pipeline\n"
                      "- Available node types:\n"
                      "  - :sequence - Execute children in order\n"
                      "  - :parallel - Execute children concurrently (independent work only)\n"
                      "  - :llm - Execute a sub-LLM call with {:instruction :reads :writes}\n"
                      "      Optional :output-schemas {<write-key> <Malli-schema>} declares the shape of each\n"
                      "      :writes value. When you set this and the schema is structured (e.g. [:vector [:map-of :any :any]],\n"
                      "      [:map [:foo :string] [:bar :int]]), the framework asks the LLM for valid JSON and parses\n"
                      "      the response back into Clojure data automatically. Without :output-schemas, the LLM's :writes\n"
                      "      values arrive as raw text strings — fine if your downstream consumer is another :llm prompt,\n"
                      "      but problematic if a :code node expects parsed Clojure data (vectors, maps, etc.).\n"
                      "      Example:\n"
                      "        [:llm {:instruction \"Identify PII targets on this page; return :targets as a vector of maps.\"\n"
                      "               :reads [:page-text]\n"
                      "               :writes [:targets]\n"
                      "               :output-schemas {:targets [:vector [:map-of :any :any]]}}]\n"
                      "  - :map-each - Process collection items with {:from :as :into :max-concurrency N} (N=1 default, use 3-5 for parallel independent items)\n"
                      "  - :chunk-document - Split document into chunks with {:from :size :into}\n"
                      "  - :aggregate - Combine results with {:from :writes}\n"
                      "  - :code - Deterministic Clojure computation. Two forms:\n"
                      "      (a) Pre-built fn by qualified-symbol string: {:fn \"ns/sym\" :reads [...] :writes [...]}\n"
                      "          (see :available-code-nodes for fns available in this task, if any)\n"
                      "      (b) INLINE function written by you: {:fn (fn [{:keys [inputs]}] {...output-map...}) :reads [...] :writes [...]}\n"
                      "          The inline fn receives a context map with :inputs (the :reads keys -> values).\n"
                      "          It must return either a map with the :writes keys, or a single value (auto-wrapped under the\n"
                      "          single declared :write). Use this when no pre-built fn exists and you need a deterministic transform.\n"
                      "          Example: [:code {:fn (fn [{:keys [inputs]}] (let [s (-> inputs vals first)] {:counts (frequencies s)}))\n"
                      "                            :reads [:text] :writes [:counts]}]\n"
                      "  - :final - Return validated output with {:keys [...]}\n"
                      "- The tree is stored for learning and can be reused\n\n"
                      "## Default Mode: emit-tree!\n\n"
                      "**emit-tree! is your default execution mode.** For ANY non-trivial workflow — anything\n"
                      "with multiple steps, parallel sub-tasks, deterministic transforms alongside LLM calls,\n"
                      "large inputs, or quality/verification requirements — emit a tree.\n\n"
                      "The narrow exceptions (when emit-tree! is overkill):\n"
                      "- The task is trivially small: single short input, single output, no intermediate work.\n"
                      "- A single (llm ...) call OR a single :code computation would clearly suffice end-to-end.\n\n"
                      "### Anti-pattern: chained sequential (llm ...) calls in Phase 1\n\n"
                      "**If you find yourself writing 2+ sequential (llm ...) calls in Phase 1 code, that is\n"
                      "a strong signal you should use emit-tree! instead.** The chained pattern translates\n"
                      "directly to [:sequence [:llm ...] [:llm ...] [:final {...}]]. The tree gives you:\n"
                      "- Per-node observability and retry\n"
                      "- Composition with :code nodes for deterministic transforms\n"
                      "- Event-trace coverage that direct Phase-1 chaining does not produce\n\n"
                      "### Use :code nodes for deterministic transforms\n\n"
                      "For deterministic work — counting, regex matching, deduplication, string\n"
                      "replacement, aggregation, format conversion — prefer a :code node over a\n"
                      "sub-LLM call. Counting characters/items via (llm ...) is hallucination-\n"
                      "prone; a pure-Clojure function is definitively correct.\n\n"
                      "### Common emit-tree! patterns\n\n"
                      "For any large data that exceeds token limits, use emit-tree! to design a processing pipeline:\n"
                      "- **Documents**: :chunk-document to split text, then :map-each + :llm per chunk, then :aggregate.\n"
                      "- **Graphs/Ontology**: Traverse by neighborhood or sample subgraphs, then :map-each + :llm.\n"
                      "- **Collections**: Partition into batches, then :map-each + :llm per batch.\n"
                      "- **Vision over multiple images**: :map-each over an image vector with :llm reading individual image keys.\n\n"
                      "The pattern is always: break into bounded sub-problems → sub-LLM per piece → :code or :aggregate.\n"
                      "Previews adapt to data type: text samples for documents, T-box/A-box summaries for graphs.\n\n"
                      ;; Include ontology examples if available
                      (or (build-ontology-examples-section node) "")
                      ;; R-1: Descriptive recursive-mode section when :recursive? is true.
                      ;; Factual framing only — no urgency language.
                      (if (get-in node [:rlm :recursive?])
                        (str "## Recursive emit-tree! (this mode)\n\n"
                             "When you call `(emit-tree! ...)`, the tree executes and then "
                             "control returns to you for another iteration. The tree's "
                             "outputs are merged into your variables (use `(get-var :summary)` "
                             "etc.), and a summary entry is appended to `:tree-results`. The loop "
                             "ends only when you call `(final! {...})` or you exceed "
                             ":max-iterations.\n\n"
                             "### Reading `:tree-results`\n"
                             "Each entry has `:status` — one of:\n"
                             "  - `:success` — the tree completed and all sub-nodes succeeded\n"
                             "  - `:partial` — some sub-nodes failed; outputs reflect the successful subset\n"
                             "  - `:failure` — the tree did not produce useful outputs\n"
                             "  - `:timeout` — the tree was cancelled before completion (budget exhausted)\n\n"
                             "Other fields per entry: `:elapsed-ms`, `:outputs-keys` (what was merged), "
                             "`:nodes-succeeded`, `:nodes-failed`, `:nodes-total`. On `:partial` or "
                             "`:failure` you also get `:failure-indices` + `:failure-reasons` "
                             "(verbatim error strings).\n\n"
                             "### Interpretation depends on your task\n"
                             "For some tasks `:partial` is acceptable (e.g., document summarization "
                             "with 22 of 24 chunks succeeded is usually fine). For others it requires "
                             "follow-up (e.g., obligations extraction where missing chunks could miss "
                             "obligations). You decide based on your task and the outputs you got.\n\n"
                             "### After a tree completes\n"
                             "You can:\n"
                             "  - Call `(emit-tree! ...)` again to run another tree\n"
                             "  - Run `(llm ...)` or `(code ...)` inline for follow-up work\n"
                             "  - Call `(final! {...})` to terminate and return your answer\n\n"
                             "### Drill-down primitives — use only when the summary is insufficient\n"
                             "If `:tree-results` summary fields don't give you enough to decide your next step, "
                             "you can read the full event-store record of the most-recent (or a specific) tree:\n"
                             "  - `(tree-detail)` / `(tree-detail tick-id)` — full structured record: per-node "
                             "`:status`, `:duration-ms`, `:writes`, `:usage`, `:input-profile`, and any `:partial-summary`\n"
                             "  - `(tree-trajectory)` / `(tree-trajectory tick-id)` — chronological per-event "
                             "log of what happened inside the tree\n"
                             "  - `(tree-failures)` — failure entries with errors + per-failure input profiles "
                             "(joins direct leaf failures with map-each `:partial-summary` failure indices)\n"
                             "  - `(node-output node-id)` — writes map of a specific completed node\n"
                             "  - `(node-input-profile node-id)` — input profile (chunk shape, etc.) of a specific node\n\n"
                             "These return potentially large data — prefer the `:tree-results` summary first and only "
                             "drill down when you genuinely need the extra detail to make a decision.\n\n")
                        "")
                      "## Output Contract\n"
                      "You MUST call (final! {...}) with keys: " (pr-str (:writes node)) "\n\n"
                      "## CRITICAL OUTPUT FORMAT\n"
                      "Your response MUST start with `[[ ## code ## ]]` on its own line, followed by RAW Clojure code (NO markdown code fences, NO ```clojure or ``` tags), and end with `[[ ## completed ## ]]`.\n\n"
                      "Correct format:\n"
                      "```\n"
                      "[[ ## code ## ]]\n"
                      "(emit-tree! [:sequence ...])\n"
                      "[[ ## completed ## ]]\n"
                      "```\n\n"
                      "WRONG (do NOT use markdown fences around your code):\n"
                      "```\n"
                      "```clojure\n"
                      "(emit-tree! [:sequence ...])\n"
                      "```\n"
                      "```\n\n"
                      "## Example: Simple Analysis (small data)\n"
                      "```clojure\n"
                      "(let [data (get-input :document)\n"
                      "      result (llm \"analyze\" \n"
                      "               :instruction \"Analyze this text\"\n"
                      "               :reads [:document]\n"
                      "               :writes [:analysis])]\n"
                      "  (final! {:answer (:analysis result)}))\n"
                      "```\n\n"
                      "## Example: Processing Large Data (PREFERRED - use emit-tree!)\n"
                      "For ANY large data (documents, collections, etc.), ALWAYS use emit-tree!:\n"
                      "```clojure\n"
                      "(emit-tree!\n"
                      "  [:sequence\n"
                      "   [:chunk-document {:from :document :size 8000 :into :chunks}]\n"
                      "   [:map-each {:from :chunks :as :chunk :into :chunk_results}\n"
                      "    [:llm {:instruction \"Extract key information from this section\"\n"
                      "           :reads [:chunk]\n"
                      "           :writes [:info]}]]\n"
                      "   [:aggregate {:from :chunk_results :writes [:all_info]}]\n"
                      "   [:llm {:instruction \"Synthesize the extracted information into a final summary\"\n"
                      "          :reads [:all_info]\n"
                      "          :writes [:summary]}]\n"
                      "   [:final {:keys [:summary]}]])\n"
                      "```\n"
                      "This is the PREFERRED approach because:\n"
                      "- Tree structure is stored for learning and reuse\n"
                      "- Proper chunking with :chunk-document\n"
                      "- Clean separation of concerns\n"
                      "- Integrates with ORC behavior tree engine\n\n"
                      "## Your Task\n"
                      (:instruction node)
                      ;; U9: When :available-code-nodes is configured on the
                      ;; repl-researcher, surface the catalog cross-reference
                      ;; at the end of the prompt so the model knows to read
                      ;; it before designing emit-tree! :code nodes.
                      (when available-code-nodes
                        "\n\nA catalog of pre-built code-node functions is provided in the :available-code-nodes input above. Use them via [:code {:fn \"...\"}] in your emit-tree! tree when their semantics match what you need. Their input/output shapes are documented there.\n\n"))}))

(defn execute-repl-researcher-rlm
  "Execute a repl-researcher node in RLM mode.

   RLM mode provides BT primitives in the sandbox:
   - (llm ...) executes sub-LLM calls
   - (final! ...) validates and captures output
   - (get-input ...) loads input values

   This separates variable space (sandbox memory) from token space (LLM context).

   Options:
   - :debug? - Enable verbose debug output for troubleshooting (default: false)"
  [node blackboard provider context & {:keys [options] :or {options {}}}]
  (let [start-time (System/currentTimeMillis)
        max-iterations (or (:max-iterations node) 10)
        mcp-tools (or (:mcp-tools node) [])
        browser-tools (or (:browser-tools node) [])
        call-tool-fn (:call-tool-fn context)
        declared-writes (:writes node)
        ;; Extract debug? from node's :rlm config (can be {:debug? true}) or from options
        rlm-config (let [rlm (:rlm node)] (if (map? rlm) rlm {}))
        debug? (or (get rlm-config :debug? false) (get options :debug? false))

        ;; Debug helper
        dbg (fn [& args] (when debug? (apply println "[DEBUG RLM]" args)))

        ;; Build inputs preview for LLM context
        inputs-preview (rlm-sandbox/build-inputs-preview blackboard)

        ;; Track usage across iterations
        total-usage (atom {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})

        ;; Persistent sandbox-vars across iterations (for store!/get-var)
        sandbox-vars (atom {})

        ;; Track when each variable was created (iteration number)
        var-creation-times (atom {})

        ;; R-1: Cumulative timing metrics for the recursive mode response
        ;; observability fields. Updated only when :recursive? is true.
        cumulative-tree-ms (atom 0)
        recursive-mode? (boolean (get-in node [:rlm :recursive?]))]

    (try
      (loop [iteration 0
             history []]
        (if (>= iteration max-iterations)
          (let [total-elapsed (- (System/currentTimeMillis) start-time)]
            {:status :failure
             :error "Max iterations reached without final!"
             :iterations history
             :duration-ms total-elapsed
             :usage @total-usage
             :cumulative-tree-ms @cumulative-tree-ms
             :cumulative-thinking-ms (max 0 (- total-elapsed @cumulative-tree-ms))})

          ;; Generate code using LLM
          (let [module (build-rlm-code-generation-module node inputs-preview history
                                                          blackboard @sandbox-vars @var-creation-times)
                inputs {:task (:instruction node)
                        :inputs-info (pr-str inputs-preview)
                        :history (or (build-iteration-history history) "None")}
                ;; Note: Don't pass :model in dscloj-options - effective-provider already has it
                ;; Passing :model causes response parsing issues in dscloj
                ;; Disable function calling - OpenRouter/Gemini don't reliably return tool_calls
                ;; DSCloj marker-based parsing works better for structured output extraction
                ;; :with-metadata? true ensures dscloj returns {:outputs ... :usage ...} instead of just outputs
                dscloj-options (assoc options :validate? false :use-function-calling? false :with-metadata? true)
                effective-provider (get-provider-with-model provider (:model node))

                _ (dbg "\n========== ITERATION" (inc (count history)) "==========")
                _ (dbg "node :model =" (:model node))
                _ (dbg "provider =" provider)
                _ (dbg "effective-provider =" effective-provider)
                _ (dbg "dscloj-options =" dscloj-options)
                _ (dbg "module :outputs =" (:outputs module))
                _ (dbg "module :instructions length =" (count (:instructions module)))
                _ (dbg "inputs :task =" (subs (:task inputs) 0 (min 100 (count (:task inputs)))) "...")
                _ (dbg "inputs :inputs-info =" (subs (:inputs-info inputs) 0 (min 200 (count (:inputs-info inputs)))) "...")
                _ (dbg "calling dscloj/predict...")
                llm-result (try
                             (dscloj/predict effective-provider module inputs dscloj-options)
                             (catch Exception e
                               (dbg "dscloj/predict EXCEPTION:" (.getMessage e))
                               {:code nil :error (.getMessage e)}))
                _ (dbg "dscloj/predict returned")
                _ (dbg "llm-result keys:" (keys llm-result))
                _ (when (and debug? (:usage llm-result))
                    (dbg "usage:" (:usage llm-result)))
                _ (dbg ":code type:" (type (:code llm-result)))
                _ (dbg ":code nil?:" (nil? (:code llm-result)))
                _ (dbg "[:outputs :code] nil?:" (nil? (get-in llm-result [:outputs :code])))
                _ (dbg "[:outputs] keys:" (keys (:outputs llm-result)))
                _ (when debug?
                    (dbg "FULL llm-result:" (pr-str (update llm-result :outputs #(into {} (map (fn [[k v]] [k (if (string? v) (subs v 0 (min 500 (count v))) v)]) %))))))
                _ (when (and debug? (get-in llm-result [:outputs :code]))
                    (dbg "GENERATED CODE (first 1000 chars):" (subs (get-in llm-result [:outputs :code]) 0 (min 1000 (count (get-in llm-result [:outputs :code]))))))
                _ (dbg ":code blank?:" (if (string? (:code llm-result))
                                         (str/blank? (:code llm-result))
                                         "N/A"))
                _ (when (and debug? (string? (:code llm-result)))
                    (dbg ":code length:" (count (:code llm-result)))
                    (dbg ":code first 200 chars:" (subs (:code llm-result) 0 (min 200 (count (:code llm-result))))))
                _ (dbg "========================================\n")
                ;; Extract code from LLM result
                ;; With function calling mode, code may be returned as:
                ;; 1. A string (normal case) - strip markdown fences and whitespace
                ;; 2. A parsed Clojure data structure (function calling parsed the JSON) - convert to string
                code (let [raw (or (:code llm-result) (get-in llm-result [:outputs :code]))]
                       (cond
                         (string? raw)
                         (-> raw
                             (str/replace #"^```(?:clojure|clj|edn)?\s*\n?" "")
                             (str/replace #"\n?```\s*$" "")
                             str/trim)

                         (some? raw)
                         ;; Already parsed Clojure form - convert back to string for sandbox
                         (pr-str raw)

                         :else nil))

                ;; Update usage tracking (normalize handles snake_case -> kebab-case)
                _ (when-let [u (normalize-usage (:usage llm-result))]
                    (swap! total-usage
                           (fn [acc]
                             {:prompt-tokens (+ (:prompt-tokens acc 0) (:prompt-tokens u))
                              :completion-tokens (+ (:completion-tokens acc 0) (:completion-tokens u))
                              :total-tokens (+ (:total-tokens acc 0) (:total-tokens u))})))]

            (cond
                (str/blank? code)
                {:status :failure
                 :error "LLM did not generate code"
                 :iterations history
                 :duration-ms (- (System/currentTimeMillis) start-time)
                 :usage @total-usage}

                :else
                ;; Build RLM sandbox context with BT primitives
                ;; Pass persistent sandbox-vars so variables survive across iterations
                (let [vars-before (set (keys @sandbox-vars))
                    rlm-ctx (rlm-sandbox/build-rlm-context
                            {:provider provider
                             :blackboard blackboard
                             :declared-writes declared-writes
                             :call-tool-fn call-tool-fn
                             :mcp-tools mcp-tools
                             :browser-tools browser-tools
                             :sandbox-vars sandbox-vars
                             :recursive? recursive-mode?
                             :event-store (:event-store context)
                             :tenant-id (:tenant-id context)})
                    exec-result (rlm-sandbox/execute-rlm-code rlm-ctx code)
                    ;; Track new variables created in this iteration
                    vars-after (set (keys @sandbox-vars))
                    new-vars (clojure.set/difference vars-after vars-before)
                    _ (doseq [k new-vars]
                        (swap! var-creation-times assoc k (inc iteration)))
                    new-history (conj history
                                      {:code code
                                       :result (:result exec-result)
                                       :stdout (:stdout exec-result)
                                       :error (:error exec-result)
                                       :vars-created (vec new-vars)})
                    final-output (:final-output exec-result)
                    ;; Aggregate sub-LLM usage into total usage
                    sub-llm-usage (:sub-llm-usage exec-result)
                    _ (when (and sub-llm-usage (pos? (:total-tokens sub-llm-usage 0)))
                        (swap! total-usage
                               (fn [acc]
                                 {:prompt-tokens (+ (:prompt-tokens acc 0) (:prompt-tokens sub-llm-usage 0))
                                  :completion-tokens (+ (:completion-tokens acc 0) (:completion-tokens sub-llm-usage 0))
                                  :total-tokens (+ (:total-tokens acc 0) (:total-tokens sub-llm-usage 0))})))
                    _ (when debug?
                        (dbg "exec-result :error:" (:error exec-result))
                        (dbg "exec-result :final-output:" final-output)
                        (when (pos? (:total-tokens sub-llm-usage 0))
                          (dbg "sub-llm-usage:" sub-llm-usage)))]

                (cond
                  ;; Security violation - fail immediately (no retry)
                  (and (:error exec-result)
                       (str/includes? (str (:error exec-result)) "Security"))
                  {:status :failure
                   :error (:error exec-result)
                   :iterations new-history
                   :duration-ms (- (System/currentTimeMillis) start-time)
                   :usage @total-usage}

                  ;; Recoverable error - add to history and continue iteration
                  ;; This gives the model a chance to self-correct
                  (:error exec-result)
                  (let [error-msg (:error exec-result)
                        available-vars (concat (keys blackboard) (keys @sandbox-vars))
                        enhanced-error (format-error-with-suggestions error-msg available-vars)
                        error-history (conj history
                                           {:code code
                                            :result nil
                                            :stdout (:stdout exec-result)
                                            :error enhanced-error
                                            :vars-created []})]
                    (recur (inc iteration) error-history))

                  ;; final! was called - return the validated output
                  final-output
                  (let [total-elapsed (- (System/currentTimeMillis) start-time)]
                    {:status :success
                     :outputs final-output
                     :final-answer final-output
                     :iterations new-history
                     :duration-ms total-elapsed
                     :usage @total-usage
                     :cumulative-tree-ms @cumulative-tree-ms
                     :cumulative-thinking-ms (max 0 (- total-elapsed @cumulative-tree-ms))})

                  ;; emit-tree! was called - trigger Phase 2 execution automatically
                  ;; Phase 1 generated the tree, now execute it via child ORC tick
                  (contains? @sandbox-vars :generated-tree)
                  (let [;; PR-Dual-Model: when (:sub-model rlm-config) or
                        ;; (:sub-model node) is set, walk the canonical tree
                        ;; and inject :model sub-model into each (sheet/llm ...)
                        ;; form that lacks an explicit :model. The Phase-2 leaf
                        ;; executor's get-provider-with-model then routes those
                        ;; calls through the sub-model. Backward compatible —
                        ;; nil sub-model is a no-op.
                        sub-model (or (get rlm-config :sub-model)
                                      (:sub-model node))
                        generated-tree (inject-sub-model
                                         (:generated-tree @sandbox-vars)
                                         sub-model)
                        generated-tree-raw (:generated-tree-raw @sandbox-vars)
                        ;; Debug: Print the generated tree
                        _ (when debug?
                            (when sub-model
                              (println "\n[DEBUG RLM] Sub-model injected into :llm nodes lacking :model:" sub-model))
                            (println "\n[DEBUG RLM] Phase 2 triggered - emit-tree! was called")
                            (println "[DEBUG RLM] Generated tree (raw S-expr):")
                            (clojure.pprint/pprint generated-tree-raw)
                            (println "\n[DEBUG RLM] Generated tree (canonical ORC DSL):")
                            (clojure.pprint/pprint generated-tree))
                        ;; D-003: resolve Phase 2 budget from node :timeout-ms,
                        ;; parent tick :timeout-ms (passed via context), or hardcoded fallback.
                        phase1-elapsed-ms (- (System/currentTimeMillis) start-time)
                        budget (resolve-phase2-budget
                                 {:node node
                                  :parent-timeout-ms (:parent-timeout-ms context)
                                  :phase1-elapsed-ms phase1-elapsed-ms})
                        _ (when debug?
                            (println "\n[DEBUG RLM] Phase 2 budget:" budget))]
                    (if (:exhausted? budget)
                      ;; D-003: skip Phase 2 entirely when budget is exhausted in Phase 1.
                      {:status :failure
                       :error (str "Budget exhausted in Phase 1 ("
                                   phase1-elapsed-ms "ms elapsed of "
                                   (:total-budget-ms budget) "ms "
                                   "[source=" (name (:source budget)) "]; "
                                   "no time left for Phase 2)")
                       :iterations new-history
                       :duration-ms phase1-elapsed-ms
                       :phase1-elapsed-ms phase1-elapsed-ms
                       :usage @total-usage
                       :budget budget}
                      (let [;; Execute Phase 2: spawn child tick with the generated tree
                            ;; Pass all sandbox-vars (except the tree itself) as inputs
                            phase2-vars (dissoc @sandbox-vars :generated-tree :generated-tree-raw)
                            _ (when debug?
                                (println "\n[DEBUG RLM] Phase 2 sandbox vars:" (keys phase2-vars)))
                            phase2-result (try
                                            (tree-executor/execute-tree
                                              generated-tree
                                              context
                                              {:sandbox-vars phase2-vars
                                               :blackboard (reduce-kv
                                                             (fn [acc k entry]
                                                               (assoc acc k (:value entry)))
                                                             {}
                                                             blackboard)
                                               ;; U6: preserve parent blackboard schemas
                                               ;; (e.g. [:string {:field-type :image}]) so
                                               ;; the child sheet's leaves see proper
                                               ;; routing. Without this the child falls back
                                               ;; to type-inference from value, which loses
                                               ;; image routing.
                                               :blackboard-schemas (reduce-kv
                                                                     (fn [acc k entry]
                                                                       (if-let [s (:schema entry)]
                                                                         (assoc acc k s)
                                                                         acc))
                                                                     {}
                                                                     blackboard)
                                               :timeout-ms (:remaining-ms budget)})
                                            (catch Exception e
                                              (println "[DEBUG RLM] Phase 2 execution ERROR:" (.getMessage e))
                                              (.printStackTrace e)
                                              {:status :failure
                                               :error (str "Phase 2 execution failed: " (.getMessage e))}))
                            ;; D-003: when Phase 2 times out, dispatch :sheet cancel-tick
                            ;; on the child tick so it stops executing in the background.
                            ;; The tree executor's timeout default carries :sheet-id +
                            ;; :trace-id specifically so we can cancel here. Wait ~500ms
                            ;; after dispatching so in-flight nodes settle their writes
                            ;; (the bookend :sheet/rlm-tree-execution-completed event
                            ;; needs that window).
                            _ (when (= :timeout (:status phase2-result))
                                (let [child-tick-id (:trace-id phase2-result)
                                      child-sheet-id (:sheet-id phase2-result)]
                                  (when (and child-tick-id child-sheet-id)
                                    (cp/process-command
                                      (assoc context :command
                                             {:command/id (random-uuid)
                                              :command/timestamp (time/now)
                                              :command/name :sheet/cancel-tick
                                              :sheet-id child-sheet-id
                                              :tick-id child-tick-id}))
                                    ;; ~500ms drain so in-flight nodes settle their
                                    ;; writes into the event store before we return.
                                    (Thread/sleep 500))))
                            phase1-duration phase1-elapsed-ms
                        _ (when debug?
                            (println "\n[DEBUG RLM] Phase 2 result:")
                            (println "  status:" (:status phase2-result))
                            (println "  error:" (:error phase2-result))
                            (println "  outputs keys:" (keys (:outputs phase2-result)))
                            (println "  duration-ms:" (:duration-ms phase2-result)))]
                    ;; R-1: When :recursive? true, DON'T return Phase 2's result —
                    ;; instead merge outputs into sandbox-vars, append a summary entry
                    ;; to :tree-results, clear :generated-tree, and recur to give the
                    ;; model another iteration to reason about the tree's outcome.
                    (if recursive-mode?
                      (let [child-tick-id (:trace-id phase2-result)
                            tenant-id (:tenant-id context)
                            event-store (:event-store context)
                            tick-events (when (and event-store child-tick-id)
                                          (into [] (es/read event-store
                                                     (cond-> {:tags #{[:tick child-tick-id]}}
                                                       tenant-id (assoc :tenant-id tenant-id)))))
                            ;; Augment phase2-result with R-1 fields so the summary
                            ;; can surface timeout-specific data when present.
                            phase2-result+budget
                            (cond-> phase2-result
                              (= :timeout (:status phase2-result))
                              (assoc :phase2-elapsed-ms (or (:duration-ms phase2-result)
                                                            (:remaining-ms budget))
                                     :budget-remaining-ms 0))
                            summary (compute-tree-result-summary
                                      {:phase2-result phase2-result+budget
                                       :tick-events tick-events
                                       :tree-raw generated-tree-raw
                                       :writes declared-writes})
                            _ (swap! sandbox-vars merge-tree-result-into-sandbox
                                     phase2-result+budget declared-writes summary)
                            _ (swap! cumulative-tree-ms + (or (:duration-ms phase2-result) 0))
                            _ (dbg "\n[DEBUG RLM] Recursive recur — :tree-results entries:"
                                   (count (:tree-results @sandbox-vars))
                                   "summary status:" (:status summary))]
                        (recur (inc iteration) new-history))
                      ;; Non-recursive (current behavior, preserved) — merge results and return.
                      (let [p1-usage @total-usage
                            p2-usage (:usage phase2-result)
                            ;; Preserve :by-node from Phase 2 (the per-node breakdown
                            ;; of sub-LLM calls inside the generated tree).
                            p2-by-node (:by-node p2-usage)
                            combined-usage (cond-> {:prompt-tokens (+ (:prompt-tokens p1-usage 0)
                                                                     (:prompt-tokens p2-usage 0))
                                                    :completion-tokens (+ (:completion-tokens p1-usage 0)
                                                                          (:completion-tokens p2-usage 0))
                                                    :total-tokens (+ (:total-tokens p1-usage 0)
                                                                     (:total-tokens p2-usage 0))}
                                             (seq p2-by-node) (assoc :by-node p2-by-node))]
                        {:status (:status phase2-result)
                         :outputs (:outputs phase2-result)
                         :generated-tree-raw generated-tree-raw
                         :iterations new-history
                         :duration-ms (+ phase1-duration (or (:duration-ms phase2-result) 0))
                         :usage combined-usage
                         :breakdown {:phase1 p1-usage :phase2 p2-usage}
                         :phase2-duration-ms (:duration-ms phase2-result)
                         :phase2-error (:error phase2-result)
                         ;; D-003: surface elapsed timings on the happy path so callers
                         ;; can debug whether budget is being spent on Phase 1 or Phase 2.
                         :phase1-elapsed-ms phase1-duration
                         :phase2-elapsed-ms (cond
                                              (number? (:duration-ms phase2-result))
                                              (:duration-ms phase2-result)
                                              ;; On timeout, execute-tree returns no duration —
                                              ;; we can derive it from the remaining budget that
                                              ;; we passed (it's what actually got consumed).
                                              (= :timeout (:status phase2-result))
                                              (:remaining-ms budget)
                                              :else nil)
                         :phase2-tick-id (:trace-id phase2-result)
                         :budget budget})))))

                  ;; Check for FINAL_ANSWER pattern (fallback)
                  (or (sci-sandbox/contains-final-answer? (:result exec-result))
                      (sci-sandbox/contains-final-answer? (:stdout exec-result)))
                  (let [final-answer (or (sci-sandbox/extract-final-answer (:result exec-result))
                                         (sci-sandbox/extract-final-answer (:stdout exec-result)))
                        outputs (if (map? final-answer)
                                  final-answer
                                  {(first declared-writes) final-answer})]
                    {:status :success
                     :outputs outputs
                     :final-answer final-answer
                     :iterations new-history
                     :duration-ms (- (System/currentTimeMillis) start-time)
                     :usage @total-usage})

                  ;; Continue iteration
                  :else
                  (recur (inc iteration) new-history)))))))

      (catch Exception e
        {:status :failure
         :error (.getMessage e)
         :duration-ms (- (System/currentTimeMillis) start-time)
         :usage @total-usage}))))

;; =============================================================================
;; Retry Logic
;; =============================================================================

(defn get-backoff
  "Get backoff duration for a given attempt (0-indexed)."
  [retry-config attempt]
  (let [backoff-ms (:backoff-ms retry-config)]
    (get backoff-ms (min attempt (dec (count backoff-ms))))))

(defn execute-with-retry
  "Execute a function with retry logic.

   Args:
     execute-fn - Zero-arg function that returns {:status :success/:failure ...}
     retry-config - {:max-attempts n :backoff-ms [100 500 2000]}

   Returns the result of execute-fn, retrying on failure up to max-attempts."
  [execute-fn retry-config]
  (let [max-attempts (or (:max-attempts retry-config) 1)]
    (loop [attempt 0]
      (let [result (execute-fn)]
        (if (or (= :success (:status result))
                (>= (inc attempt) max-attempts))
          result
          (do
            (when-let [backoff (get-backoff retry-config attempt)]
              (Thread/sleep backoff))
            (recur (inc attempt))))))))

;; =============================================================================
;; Main Execution Entry Point
;; =============================================================================

(defn execute-leaf
  "Execute a leaf node based on its executor type.

   Executor types:
   - :ai (default) - DSCloj AI execution
   - :code - Clojure function execution
   - :tool - Direct tool invocation (not yet implemented)

   Args:
     node - The leaf node map
     blackboard - Map of key -> {:key, :type, :value, :version}
     provider - DSCloj provider keyword (for :ai executor)
     context - Additional context map (event-store, etc.)

   Returns:
     {:status :success/:failure
      :outputs {string-key value}
      :error string?
      :duration-ms int}"
  [node blackboard provider & {:keys [context options] :or {context {} options {}}}]
  (let [executor-type (or (:executor node) :ai)
        retry-config (:retry node)
        execute-fn (fn []
                     (case executor-type
                       :ai (execute-ai node blackboard provider :options options)
                       :code (execute-code node blackboard context)
                       :tool {:status :failure
                              :error "Tool executor not yet implemented"
                              :duration-ms 0}
                       ;; Default to AI
                       (execute-ai node blackboard provider :options options)))]
    (if retry-config
      (execute-with-retry execute-fn retry-config)
      (execute-fn))))

;; =============================================================================
;; Mock Executor (for testing without AI)
;; =============================================================================

(defn execute-leaf-mock
  "Mock executor that returns success with placeholder outputs.
   Useful for testing the behavior tree flow without AI calls."
  [node _blackboard]
  (let [start-time (System/currentTimeMillis)
        outputs (into {}
                      (map (fn [k] [k (str "mock-value-for-" k)])
                           (:writes node)))
        duration-ms (- (System/currentTimeMillis) start-time)]
    {:status :success
     :outputs outputs
     :duration-ms duration-ms}))

;; =============================================================================
;; Provider Setup
;; =============================================================================

(defn setup-providers!
  "Set up DSCloj providers from environment variables.
   Call this at application startup."
  []
  (dscloj/quick-setup!))

(defn list-available-providers
  "List all registered DSCloj providers."
  []
  (dscloj/list-providers))

(comment
  ;; Example usage:

  ;; 1. Setup providers (do once at app startup)
  (setup-providers!)

  ;; 2. Define a node and blackboard
  (def example-node
    {:instruction "Given the question, provide a clear and concise answer."
     :reads [:question]
     :writes [:answer]})

  (def example-blackboard
    {:question {:key :question :schema :string :value "What is 2+2?" :version 1}
     :answer {:key :answer :schema :string :value nil :version 0}})

  ;; 3. Execute
  (execute-leaf example-node example-blackboard :openrouter)
  ;; => {:status :success, :outputs {:answer "4"}, :duration-ms 1234}

  ;; 4. Or use mock for testing
  (execute-leaf-mock example-node example-blackboard)
  ;; => {:status :success, :outputs {:answer "mock-value-for-answer"}, :duration-ms 0}
  )
