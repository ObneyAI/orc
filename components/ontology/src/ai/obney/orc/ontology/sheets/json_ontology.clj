(ns ai.obney.orc.ontology.sheets.json-ontology
  "JSON-to-Ontology extraction pipeline.

   Extracts ontologies from arbitrary JSON using LLM reasoning.
   Handles poorly labeled/cryptic data where the LLM must infer
   entity types and relationships from evidence (values, patterns, structure).

   Phases:
   1. Parse Structure - Analyze JSON arrays, objects, nesting, field types
   2. Analyze Domain (LLM) - Identify domain and purpose
   3. Discover Entity Types (LLM) - Infer meaningful entity names
   4. Deduplicate - Remove duplicate concepts
   5. Generate Definitions (LLM) - Create formal definitions
   6. Discover Relationships (LLM) - Find relationships from structure
   7. Validate Quality (LLM) - Check for ambiguity
   8. Build T-Box - Construct OWL classes/properties
   9. Build A-Box - Construct instances
   10. Serialize - Generate OWL output"
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [clojure.string :as str]
            [clojure.data.json :as json]))

;; =============================================================================
;; JSON Structure Analysis (Code - No LLM)
;; =============================================================================

(defn- infer-type
  "Infer the type of a JSON value."
  [v]
  (cond
    (nil? v) :null
    (string? v) :string
    (number? v) (if (integer? v) :integer :number)
    (boolean? v) :boolean
    (vector? v) :array
    (map? v) :object
    :else :unknown))

(defn- analyze-field
  "Analyze a single field across multiple objects."
  [field-name values]
  (let [non-nil-values (remove nil? values)
        types (set (map infer-type non-nil-values))
        sample-values (take 3 (distinct non-nil-values))]
    {:name field-name
     :type (if (= 1 (count types)) (first types) :mixed)
     :nullable? (some nil? values)
     :sample-values (vec sample-values)
     :distinct-count (count (distinct non-nil-values))}))

(defn analyze-json-structure
  "Analyze the structure of arbitrary JSON data.
   Returns a map describing root type, fields, nesting, etc."
  [json-data]
  (cond
    ;; Array of objects (most common case)
    (and (vector? json-data) (every? map? json-data))
    (let [all-keys (distinct (mapcat keys json-data))
          fields (for [k all-keys]
                   (analyze-field (name k) (map #(get % k) json-data)))]
      {:root-type :array
       :element-type :object
       :total-count (count json-data)
       :fields (vec fields)
       :has-nested-objects? (some #(some map? (vals %)) json-data)
       :has-arrays? (some #(some vector? (vals %)) json-data)})

    ;; Array of primitives
    (vector? json-data)
    {:root-type :array
     :element-type :primitive
     :total-count (count json-data)
     :fields []
     :sample-values (vec (take 5 json-data))}

    ;; Single object
    (map? json-data)
    (let [fields (for [[k v] json-data]
                   {:name (name k)
                    :type (infer-type v)
                    :sample-values [(if (coll? v) (str (type v)) v)]})]
      {:root-type :object
       :element-type nil
       :total-count 1
       :fields (vec fields)
       :has-nested-objects? (some map? (vals json-data))
       :has-arrays? (some vector? (vals json-data))})

    :else
    {:root-type :primitive
     :element-type nil
     :total-count 1
     :fields []}))

(defn format-structure-for-llm
  "Format structure analysis in a way that's useful for LLM reasoning."
  [structure json-data]
  (let [field-details (str/join "\n"
                        (for [f (:fields structure)]
                          (format "  - %s: %s (samples: %s)"
                                  (:name f)
                                  (name (:type f))
                                  (str/join ", " (map pr-str (:sample-values f))))))]
    (format "Root type: %s
Element type: %s
Total records: %d
Has nested objects: %s
Has arrays: %s

Field Details:
%s

Sample Data (first 2 records):
%s"
            (name (:root-type structure))
            (if (:element-type structure) (name (:element-type structure)) "N/A")
            (:total-count structure)
            (:has-nested-objects? structure)
            (:has-arrays? structure)
            field-details
            (with-out-str
              (clojure.pprint/pprint (take 2 (if (vector? json-data) json-data [json-data])))))))

;; =============================================================================
;; Code Executor Functions
;; =============================================================================

(defn parse-json-fn
  "Phase 1: Parse JSON and analyze structure.
   Handles both pre-parsed data and JSON strings."
  [{:keys [inputs]}]
  (let [{:keys [json-data json-path content]} inputs
        data (cond
               json-data json-data
               content (json/read-str content :key-fn keyword)
               json-path (json/read-str (slurp json-path) :key-fn keyword)
               :else (throw (ex-info "No JSON source provided" {})))
        structure (analyze-json-structure data)]
    {:json-data data
     :structure structure
     :structure-summary (format-structure-for-llm structure data)}))

(defn extract-concepts-from-analysis-fn
  "Phase 4: Extract concepts from LLM-discovered entity types."
  [{:keys [inputs]}]
  (let [{:keys [entity-types base-uri json-data]} inputs
        concepts (if (seq entity-types)
                   (mapv (fn [et]
                           {:uri (str base-uri (:name et))
                            :label (:name et)
                            :definition (:definition et)
                            :entity-type (:type et)
                            :source-fields (:source-fields et)
                            :confidence (:confidence et)})
                         entity-types)
                   ;; Fallback if LLM returned nothing
                   [{:uri (str base-uri "Entity")
                     :label "Entity"
                     :definition "A generic entity extracted from JSON"
                     :entity-type "Entity"
                     :source-fields []
                     :confidence 0.5}])]
    {:concepts concepts}))

(defn deduplicate-concepts-fn
  "Phase 5: Remove duplicate concepts by label (case-insensitive)."
  [{:keys [inputs]}]
  (let [{:keys [concepts]} inputs
        seen (atom #{})
        deduped (reduce (fn [acc concept]
                          (let [key (str/lower-case (:label concept))]
                            (if (@seen key)
                              acc
                              (do (swap! seen conj key)
                                  (conj acc concept)))))
                        []
                        concepts)]
    {:concepts deduped
     :dedup-stats {:before (count concepts)
                   :after (count deduped)
                   :removed (- (count concepts) (count deduped))}}))

(defn build-tbox-fn
  "Phase 8: Build T-Box (OWL classes and properties)."
  [{:keys [inputs]}]
  (let [{:keys [concepts relationships base-uri]} inputs
        classes (mapv (fn [c]
                        {:uri (:uri c)
                         :label (:label c)
                         :definition (:definition c)
                         :type "owl:Class"})
                      concepts)
        object-properties (mapv (fn [r]
                                  {:uri (str base-uri (:predicate r))
                                   :label (:predicate r)
                                   :domain (:subject r)
                                   :range (:object r)
                                   :type "owl:ObjectProperty"})
                                relationships)]
    {:tbox {:classes classes
            :object-properties object-properties
            :datatype-properties []}}))

(defn- extract-label
  "Extract label from item using fallback chain (Issue 003).

   Fallback order:
   1. Use LLM-specified label-field if present
   2. Fall back to first source-field
   3. Fall back to synthetic label (type-index)"
  [item label-field source-fields primary-type idx]
  (let [;; Helper to get value from item (handles both keyword and string keys)
        get-field (fn [field-name]
                    (when field-name
                      (or (get item (keyword field-name))
                          (get item field-name))))
        ;; Try label-field first
        label-from-field (get-field label-field)
        ;; Fall back to first source-field
        label-from-source (when (and (nil? label-from-field) (seq source-fields))
                            (get-field (first source-fields)))]
    ;; Return first non-nil, or synthetic label
    (or (some-> label-from-field str)
        (some-> label-from-source str)
        (str primary-type "-" idx))))

(defn build-abox-fn
  "Phase 9: Build A-Box (instances from JSON data).

   Issue 003: Includes :label field in each individual for Grain schema compliance.
   Label is extracted using fallback chain: label-field → source-field → synthetic."
  [{:keys [inputs]}]
  (let [{:keys [json-data concepts base-uri]} inputs
        primary-concept (first concepts)
        primary-type (or (:entity-type primary-concept) "Entity")
        label-field (:label-field primary-concept)
        source-fields (:source-fields primary-concept)
        data-items (if (vector? json-data) json-data [json-data])
        instances (map-indexed
                    (fn [idx item]
                      {:uri (str base-uri (str/lower-case primary-type) "-" idx)
                       :type primary-type
                       :label (extract-label item label-field source-fields primary-type idx)
                       :properties (into {} (map (fn [[k v]] [(name k) v]) item))})
                    data-items)]
    {:abox (vec instances)}))

(defn serialize-to-owl-fn
  "Phase 10: Serialize to OWL Turtle format."
  [{:keys [inputs]}]
  (let [{:keys [tbox abox base-uri]} inputs
        prefixes (format "@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
@prefix : <%s> .

" base-uri)
        classes-ttl (str/join "\n"
                      (for [c (:classes tbox)]
                        (format ":%s a owl:Class ;
    rdfs:label \"%s\" ;
    rdfs:comment \"%s\" ."
                                (:label c)
                                (:label c)
                                (or (:definition c) ""))))
        owl-output (str prefixes classes-ttl)]
    {:owl-output owl-output}))

;; =============================================================================
;; Pipeline Definition
;; =============================================================================

(def json-to-ontology-pipeline
  "Behavior tree pipeline for JSON-to-Ontology extraction."
  (sheet/workflow "json-to-ontology"
    (sheet/blackboard
      {;; === Inputs ===
       :json-data :any
       :json-path [:string {:optional true}]
       :content [:string {:optional true}]
       :base-uri [:string {:description "Ontology namespace URI"}]
       :domain [:string {:optional true :description "Optional domain hint"}]
       :existing-concepts [:vector {:optional true} :any]

       ;; === Phase 1: Parse ===
       :structure [:map {:description "JSON structure analysis"}]
       :structure-summary [:string {:description "Human-readable structure summary for LLM"}]

       ;; === Phase 2: Domain Analysis ===
       :domain-reasoning [:string {:description "Step-by-step reasoning for domain analysis"}]
       :domain-description [:string {:description "Description of the domain"}]
       :naming-patterns [:string {:description "Detected naming conventions"}]

       ;; === Phase 3: Entity Discovery ===
       :entity-reasoning [:string {:description "Step-by-step reasoning for entity discovery"}]
       ;; Issue 002: Added :label-field for Grain schema compliance
       :entity-types [:vector {:description "Discovered entity types"}
                      [:map
                       [:name :string]
                       [:type :string]
                       [:source-fields [:vector :string]]
                       [:label-field {:optional true} :string]  ;; Field to use as label for individuals
                       [:definition :string]
                       [:confidence :double]]]

       ;; === Phase 4-5: Concepts ===
       :concepts [:vector {:description "Extracted concepts"}
                  [:map
                   [:uri :string]
                   [:label :string]
                   [:definition :string]
                   [:entity-type :string]
                   [:confidence :double]]]
       :dedup-stats [:map [:before :int] [:after :int] [:removed :int]]

       ;; === Phase 6: Definitions ===
       :definition-reasoning [:string {:description "Reasoning for definitions"}]
       :enriched-definitions [:map-of :string :any]

       ;; === Phase 7: Relationships ===
       :relationship-reasoning [:string {:description "Reasoning for relationships"}]
       :relationships [:vector {:description "Discovered relationships"}
                       [:map
                        [:subject :string]
                        [:predicate :string]
                        [:object :string]
                        [:evidence :string]
                        [:confidence :double]]]

       ;; === Phase 8: Validation ===
       :validation-reasoning [:string {:description "Reasoning for validation"}]
       :quality [:string {:description "Quality assessment: good/needs-work"}]
       :issues [:vector :string]
       :suggestions [:vector :string]

       ;; === Phase 9-10: Output ===
       :tbox [:map {:description "Ontology schema"}
              [:classes [:vector :any]]
              [:object-properties [:vector :any]]
              [:datatype-properties [:vector :any]]]
       :abox [:vector {:description "Instances"} :any]
       :owl-output [:string {:description "OWL Turtle serialization"}]})

    (sheet/sequence "json-main-pipeline"
      ;; PHASE 1: Parse JSON
      (sheet/code "parse-json"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/parse-json-fn"
        :reads [:json-data :json-path :content]
        :writes [:json-data :structure :structure-summary])

      ;; PHASE 2: Domain Analysis (LLM)
      (sheet/llm "analyze-json-domain"
        :model "google/gemini-3-flash-preview"
        :instruction "Analyze this JSON structure to understand its domain and purpose.

Based on the field names, values, and structure:
1. Identify the main domain (e.g., 'Human Resources', 'E-commerce', 'Healthcare')
2. Describe what this JSON data represents
3. Note any naming conventions or patterns (including cryptic/abbreviated field names)

Even if field names are cryptic (like 'n' for name, 'a' for age), infer their meaning from sample values."
        :reads [:structure-summary]
        :writes [:domain-reasoning :domain :domain-description :naming-patterns]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; PHASE 3: Entity Type Discovery (LLM)
      ;; Issue 002: Added label-field request for Grain schema compliance
      (sheet/llm "discover-entity-types"
        :model "google/gemini-3-flash-preview"
        :instruction "Identify entity types represented in this JSON.

For each entity type, provide:
1. A meaningful name (PascalCase) - even if the JSON fields are cryptic
2. The type category (Person, Organization, Event, Product, Location, Concept, etc.)
3. Which JSON fields belong to this entity
4. Which field should be used as the human-readable label (label-field)
5. A brief definition based on the data

Return as JSON array:
[{\"name\": \"Person\", \"type\": \"Person\", \"source-fields\": [\"n\", \"a\"], \"label-field\": \"n\", \"definition\": \"An individual with name and age\", \"confidence\": 0.9}]

The label-field should be the field that best identifies each instance (usually name, title, or id).
For cryptic fields, explain your reasoning (e.g., 'n' appears to be name based on string values like 'John Smith')."
        :reads [:structure-summary :domain :domain-description :naming-patterns]
        :writes [:entity-reasoning :entity-types]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; PHASE 4: Extract Concepts
      (sheet/code "extract-concepts"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/extract-concepts-from-analysis-fn"
        :reads [:entity-types :base-uri :json-data]
        :writes [:concepts])

      ;; PHASE 5: Deduplicate
      (sheet/code "deduplicate-concepts"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/deduplicate-concepts-fn"
        :reads [:concepts]
        :writes [:concepts :dedup-stats])

      ;; PHASE 6: Enrich Definitions (LLM)
      (sheet/llm "enrich-definitions"
        :model "google/gemini-3-flash-preview"
        :instruction "For each entity type, generate a formal 2-3 sentence definition.

Include:
1. What the entity represents
2. Key properties/characteristics
3. How it relates to other entities in the domain

Return as JSON object with entity names as keys:
{\"Person\": {\"definition\": \"...\", \"scope-note\": \"...\"}}"
        :reads [:concepts :domain :domain-description]
        :writes [:definition-reasoning :enriched-definitions]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; PHASE 7: Discover Relationships (LLM)
      (sheet/llm "discover-relationships"
        :model "google/gemini-3-flash-preview"
        :instruction "Discover relationships between entities in this JSON.

Look for:
1. Nesting patterns (parent contains child)
2. ID references (field_id pointing to another entity)
3. Semantic relationships based on domain knowledge
4. Implicit relationships from field names

Return as JSON array:
[{\"subject\": \"Employee\", \"predicate\": \"worksFor\", \"object\": \"Company\", \"evidence\": \"...\", \"confidence\": 0.8}]"
        :reads [:structure-summary :concepts :domain]
        :writes [:relationship-reasoning :relationships]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; PHASE 8: Validate Quality (LLM)
      (sheet/llm "validate-quality"
        :model "google/gemini-3-flash-preview"
        :instruction "Validate the extracted ontology for quality issues.

Check for:
1. Ambiguous entity names
2. Overlapping concepts that should be merged
3. Missing definitions
4. Inconsistent naming conventions

Return as JSON:
{\"quality\": \"good\"/\"needs-work\", \"issues\": [...], \"suggestions\": [...]}"
        :reads [:concepts :relationships :domain]
        :writes [:validation-reasoning :quality :issues :suggestions]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; PHASE 9: Build T-Box
      (sheet/code "build-tbox"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/build-tbox-fn"
        :reads [:concepts :relationships :base-uri]
        :writes [:tbox])

      ;; PHASE 10: Build A-Box
      (sheet/code "build-abox"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/build-abox-fn"
        :reads [:json-data :concepts :base-uri]
        :writes [:abox])

      ;; PHASE 11: Serialize to OWL
      (sheet/code "serialize-to-owl"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/serialize-to-owl-fn"
        :reads [:tbox :abox :base-uri]
        :writes [:owl-output]))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn build-json-ontology-pipeline!
  "Build the JSON ontology extraction pipeline in the given context.
   Returns the sheet-id."
  [ctx]
  (sheet/build-workflow! ctx json-to-ontology-pipeline))

(defn run-json-to-ontology
  "Run the JSON-to-ontology extraction pipeline.

   Args:
     ctx - ORC context with :event-store
     sheet-id - UUID of the built pipeline
     opts:
       :json-data - Pre-parsed JSON data
       :json-path - Path to JSON file
       :content - JSON string
       :base-uri - Ontology namespace URI (required)
       :domain - Optional domain hint

   Returns:
     {:status :success/:failure
      :concepts [...]
      :relationships [...]
      :tbox {...}
      :abox [...]
      :owl-output \"...\"}"
  [ctx sheet-id {:keys [json-data json-path content base-uri domain]}]
  (let [inputs (cond-> {:base-uri base-uri}
                 json-data (assoc :json-data json-data)
                 json-path (assoc :json-path json-path)
                 content (assoc :content content)
                 domain (assoc :domain domain))
        result (sheet/execute ctx sheet-id inputs)]
    (if (= :success (:status result))
      (merge {:status :success}
             (select-keys (:outputs result)
                          [:domain :concepts :relationships :tbox :abox :owl-output]))
      {:status :failure
       :error (:error result)})))
