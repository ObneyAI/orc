(ns ai.obney.orc.mcp-sheet-builder.core.schema-converter
  "Convert JSON Schema to Malli schema, preserving descriptions.

   Key features:
   - Preserves :description metadata for LLM prompt generation
   - Handles nested objects, arrays, and primitives
   - Marks optional fields appropriately
   - Generates ORC blackboard-compatible schemas")

;; ============================================================================
;; JSON Schema Type Mapping
;; ============================================================================

(def json-type->malli
  "Map JSON Schema types to Malli types."
  {"string" :string
   "number" :double
   "integer" :int
   "boolean" :boolean
   "null" :nil})

;; ============================================================================
;; Schema Conversion
;; ============================================================================

(declare convert-schema)

(defn- specificity-error
  [message schema]
  (throw (ex-info (str message "; provide the most specific schema possible for the value's intent")
                  {:json-schema schema})))

(defn- add-description
  "Add description metadata to a Malli schema if present."
  [malli-schema description]
  (if (and description (string? description) (seq description))
    (if (vector? malli-schema)
      ;; Schema is already a vector, add props
      (let [[type & rest] malli-schema]
        (if (and (seq rest) (map? (first rest)))
          ;; Already has props map
          (into [type (assoc (first rest) :description description)] (rest rest))
          ;; No props map, add one
          (into [type {:description description}] rest)))
      ;; Schema is a keyword, wrap it
      [malli-schema {:description description}])
    malli-schema))

(defn- convert-primitive
  "Convert a JSON Schema primitive type to Malli."
  [{:strs [type description enum default]}]
  (let [base-schema (cond
                      enum [:enum (vec enum)]
                      :else (or (get json-type->malli type)
                                (specificity-error "JSON Schema has no supported concrete type" {:type type})))]
    (add-description base-schema description)))

(defn- convert-array
  "Convert a JSON Schema array to Malli."
  [{:strs [items description] :as schema}]
  (let [item-schema (if items
                      (convert-schema items)
                      (specificity-error "JSON array schema does not specify its items" schema))]
    (add-description [:vector item-schema] description)))

(defn- convert-object
  "Convert a JSON Schema object to Malli."
  [{:strs [properties required description additionalProperties] :as schema}]
  (if (and (not properties) additionalProperties)
    ;; Map type (additionalProperties without specific properties)
    (let [value-schema (if (map? additionalProperties)
                         (convert-schema additionalProperties)
                         (specificity-error "JSON object additionalProperties has no value schema" schema))]
      (add-description [:map-of :string value-schema] description))
    ;; Regular object with properties
    (let [_ (when (empty? properties)
              (specificity-error "JSON object schema does not specify properties" schema))
          required-set (set (or required []))
          prop-schemas (for [[prop-name prop-schema] properties
                             :let [converted (convert-schema prop-schema)
                                   is-required (contains? required-set prop-name)]]
                         (if is-required
                           [(keyword prop-name) converted]
                           [(keyword prop-name) {:optional true} converted]))]
      (add-description (into [:map] prop-schemas) description))))

(defn convert-schema
  "Convert a JSON Schema to Malli.

   Handles:
   - Primitive types (string, number, integer, boolean)
   - Objects with properties
   - Arrays with items
   - Enums
   - Descriptions (preserved as metadata)
   - Required/optional fields"
  [{:strs [type] :as json-schema}]
  (when json-schema
    (case type
      "object" (convert-object json-schema)
      "array" (convert-array json-schema)
      ("string" "number" "integer" "boolean" "null") (convert-primitive json-schema)
      ;; Default: try to infer from structure
      (cond
        (contains? json-schema "properties") (convert-object json-schema)
        (contains? json-schema "items") (convert-array json-schema)
        (contains? json-schema "enum") (convert-primitive json-schema)
        :else (specificity-error "JSON Schema is structurally unconstrained" json-schema)))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn json-schema->malli
  "Convert JSON Schema to Malli schema, preserving descriptions.

   Example input:
   {\"type\": \"object\",
    \"properties\": {
      \"query\": {\"type\": \"string\", \"description\": \"Search query\"},
      \"limit\": {\"type\": \"integer\", \"default\": 10}
    },
    \"required\": [\"query\"]}

   Example output:
   [:map
    [:query [:string {:description \"Search query\"}]]
    [:limit {:optional true} :int]]"
  [json-schema]
  (convert-schema json-schema))

(defn tool-input-schema->malli
  "Convert an MCP tool's inputSchema to Malli blackboard keys.

   Returns a map of keyword->schema suitable for ORC blackboard."
  [{:strs [properties required] :as input-schema}]
  (let [required-set (set (or required []))]
    (into {}
          (for [[prop-name prop-schema] properties
                :let [converted (convert-schema prop-schema)
                      is-required (contains? required-set prop-name)]]
            [(keyword prop-name)
             (if is-required
               converted
               (if (vector? converted)
                 (let [[type props & rest] converted]
                   (if (map? props)
                     (into [type (assoc props :optional true)] rest)
                     (into [type {:optional true} props] rest)))
                 [converted {:optional true}]))]))))

(defn tools->blackboard-schema
  "Generate a combined blackboard schema from multiple MCP tools.

   Merges all tool input/output schemas into a single blackboard map."
  [tools]
  (reduce
   (fn [acc {:keys [name inputSchema outputSchema]}]
     (let [input-keys (tool-input-schema->malli inputSchema)
           output-key (keyword (str name "-result"))]
       (-> acc
           (merge input-keys)
           (assoc output-key
                  (or (convert-schema outputSchema)
                      (specificity-error
                       (str "MCP tool " name " does not declare an output schema")
                       outputSchema))))))
   {}
   tools))

(comment
  ;; Example conversions
  (json-schema->malli
   {"type" "object"
    "properties" {"query" {"type" "string" "description" "Search query"}
                  "limit" {"type" "integer" "default" 10}}
    "required" ["query"]})
  ;; => [:map
  ;;     [:query [:string {:description "Search query"}]]
  ;;     [:limit {:optional true} :int]]

  (json-schema->malli
   {"type" "array"
    "items" {"type" "string"}
    "description" "List of keywords"})
  ;; => [:vector {:description "List of keywords"} :string]
  )
