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
            [cheshire.core :as json]
            [malli.core :as m]
            [ai.obney.orc.orc-service.core.observability :as obs]
            [ai.obney.orc.orc-service.core.sci-sandbox :as sci-sandbox]))

;; =============================================================================
;; Usage Normalization
;; =============================================================================

(defn- normalize-usage
  "Normalize DSCloj/litellm usage map from snake_case to kebab-case."
  [usage]
  (when usage
    {:prompt-tokens (:prompt_tokens usage 0)
     :completion-tokens (:completion_tokens usage 0)
     :total-tokens (:total_tokens usage 0)}))

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
;; Code Executor
;; =============================================================================

(defn resolve-fn
  "Resolve a fully-qualified function symbol string to a function.
   Returns {:fn f} on success or {:error msg} on failure."
  [fn-symbol-str]
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
      {:error (str "Failed to resolve function: " fn-symbol-str " - " (.getMessage e))})))

(defn execute-code
  "Execute a Clojure function as a leaf node.

   The function receives a context map with:
   - :event-store - The event store (if provided)
   - :inputs - Map of blackboard key -> value for node's reads

   The function should return a map of blackboard key -> value for writes.

   Args:
     node - The leaf node map with :fn (fully-qualified symbol string)
     blackboard - Map of key -> {:key, :type, :value, :version}
     context - Additional context (event-store, etc.)

   Returns:
     {:status :success/:failure
      :outputs {string-key value}
      :error string?
      :duration-ms int}"
  [node blackboard context]
  (let [start-time (System/currentTimeMillis)
        fn-symbol (:fn node)
        resolved (resolve-fn fn-symbol)]
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
              result (f (assoc context :inputs inputs))
              duration-ms (- (System/currentTimeMillis) start-time)]
          ;; Result should be a map of key -> value
          (if (map? result)
            {:status :success
             :outputs result
             :duration-ms duration-ms}
            {:status :failure
             :error (str "Code executor function must return a map, got: " (type result))
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
        ;; Request metadata for usage tracking
        ;; Disable validation since we serialize complex inputs to JSON strings
        dscloj-options (assoc options :validate? false)
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
  "Format iteration history for LLM context."
  [history]
  (when (seq history)
    (str "\n\n## Previous Iterations\n"
         (str/join "\n\n"
                   (map-indexed
                    (fn [idx {:keys [code result stdout]}]
                      (str "### Iteration " (inc idx) "\n"
                           "Code:\n```clojure\n" code "\n```\n"
                           (when (seq stdout) (str "Output:\n" stdout "\n"))
                           "Result: " result))
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

   This implements the RLM (Research Language Model) pattern where:
   1. LLM generates Clojure code to call MCP tools
   2. Code executes in a safe SCI sandbox
   3. Results feed back to LLM for next iteration
   4. Converges when FINAL_ANSWER is detected

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
          (let [module (build-code-generation-module node bb-metadata history mcp-tools browser-tools)
                all-tools (concat mcp-tools browser-tools)
                inputs {:task (serialize-for-llm (:instruction node))
                        :context bb-metadata
                        :history (or (build-iteration-history history) "None")
                        :tools (str/join ", " all-tools)}
                ;; Ensure model is passed in options for DSCloj
                dscloj-options (cond-> (assoc options :validate? false)
                                 (:model node) (assoc :model (:model node)))

                ;; Generate code - use base provider, model is in dscloj-options
                llm-result (dscloj/predict provider module inputs dscloj-options)
                ;; DSCloj returns {:code "..."} directly, not {:outputs {:code "..."}}
                code (or (:code llm-result) (get-in llm-result [:outputs :code]))

                ;; Update usage tracking
                _ (when-let [usage (:usage llm-result)]
                    (swap! total-usage
                           (fn [u]
                             {:prompt-tokens (+ (:prompt-tokens u 0) (:prompt_tokens usage 0))
                              :completion-tokens (+ (:completion-tokens u 0) (:completion_tokens usage 0))
                              :total-tokens (+ (:total-tokens u 0) (:total_tokens usage 0))})))]

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
                                       :error (:error exec-result)})]
                (cond
                  ;; Check for FINAL_ANSWER in result or stdout
                  (or (sci-sandbox/contains-final-answer? (:result exec-result))
                      (sci-sandbox/contains-final-answer? (:stdout exec-result)))
                  (let [final-answer (or (sci-sandbox/extract-final-answer (:result exec-result))
                                         (sci-sandbox/extract-final-answer (:stdout exec-result)))
                        write-key (first (:writes node))]
                    {:status :success
                     :outputs {write-key final-answer}
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
