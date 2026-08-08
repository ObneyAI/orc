(ns ai.obney.orc.ontology.core.field-analyzer-workflow
  "ORC workflow definition for LLM-driven field analysis.

   This workflow analyzes a Malli schema and sample data to determine
   which fields are worth embedding for semantic search.

   The workflow:
   1. Code node extracts field metadata and sample values from schema + data
   2. LLM node assesses each field for semantic content worth embedding
   3. Code node filters and ranks fields by confidence threshold

   Usage:
   ```clojure
   (def sheet-id (sheet/build-workflow! ctx field-analyzer-workflow))
   (sheet/execute ctx sheet-id
     {\"schema\" my-schema
      \"sample-data\" [record1 record2 record3]
      \"confidence-threshold\" 0.7})
   ```"
  (:require [ai.obney.orc.orc-service.interface :as sheet]))

;; =============================================================================
;; Workflow Definition
;; =============================================================================

(def ^:private sampled-field-value-schema
  [:or :string :int :double :boolean :keyword :uuid :inst
   [:vector [:or :string :int :double :boolean :keyword :uuid]]])

(def ^:private sample-record-schema
  [:map-of :keyword sampled-field-value-schema])

(def ^:private schema-form-schema
  [:or :keyword
   [:vector [:or :keyword :string
             [:map-of :keyword [:or :string :boolean]]
             [:vector :keyword]]]])

(def field-analyzer-workflow
  "ORC workflow for LLM-driven field embedding analysis.

   This workflow takes a Malli schema and optional sample data,
   then uses an LLM to determine which fields contain semantic content
   worth embedding for similarity search.

   Input keys:
   - schema: Malli [:map ...] schema to analyze
   - sample-data: Vector of sample records matching the schema (recommended: 3-5)
   - confidence-threshold: Minimum confidence to include (default: 0.7)

   Output keys:
   - embeddable-fields: Vector of field names recommended for embedding
   - confidence-scores: Map of field-name -> confidence score (0.0-1.0)
   - reasoning: Map of field-name -> explanation string"

  (sheet/workflow "field-analyzer"

    ;; =========================================================================
    ;; BLACKBOARD SCHEMA
    ;; =========================================================================
    (sheet/blackboard
      {;; === Inputs ===
       :schema schema-form-schema  ;; Malli schema as data
       :sample-data [:vector sample-record-schema]  ;; Sample records to analyze
       :confidence-threshold [:double {:description "Minimum confidence to include a field (0.0-1.0)"}]

       ;; === Intermediate State ===
       :field-candidates [:vector [:map
                                   [:field-name :keyword]
                                   [:field-type :string]
                                   [:nullable :boolean]
                                   [:description {:optional true} :string]
                                   [:sample-values {:optional true}
                                    [:vector sampled-field-value-schema]]
                                   [:value-stats {:optional true} [:map
                                                                   [:sample-count {:optional true} :int]
                                                                   [:unique-count {:optional true} :int]
                                                                   [:avg-length {:optional true} :double]]]]]

       :llm-assessment [:map
                        [:fields [:vector [:map
                                           [:fieldName :string]
                                           [:shouldEmbed :boolean]
                                           [:confidence :double]
                                           [:reasoning :string]]]]]

       ;; === Outputs ===
       :embeddable-fields [:vector :keyword]
       :confidence-scores [:map-of :keyword :double]
       :reasoning [:map-of :keyword :string]})

    ;; =========================================================================
    ;; MAIN SEQUENCE
    ;; =========================================================================
    (sheet/sequence "analyze"

      ;; -----------------------------------------------------------------------
      ;; Step 1: Extract Field Metadata
      ;; Code node walks the schema and samples actual values
      ;; -----------------------------------------------------------------------
      (sheet/code "extract-metadata"
        :fn "ai.obney.orc.ontology.core.field-analyzer/extract-field-metadata"
        :reads [:schema :sample-data]
        :writes [:field-candidates])

      ;; -----------------------------------------------------------------------
      ;; Step 2: LLM Assessment
      ;; LLM analyzes each field based on type, description, and sample values
      ;; -----------------------------------------------------------------------
      (sheet/llm "assess-fields"
        :model "google/gemini-2.5-flash"
        :instruction "You are analyzing database fields to determine which ones contain semantic content worth embedding for similarity search.

For each field in the provided candidates, assess:

1. **Semantic Content**: Does the field contain natural language, meaningful text, or concepts that would benefit from semantic search? Consider the sample values.

2. **Embedding Value**: Would embedding this field help users find similar records? Text descriptions, narratives, and categorizations are good candidates. IDs, timestamps, and pure numbers are not.

3. **Field Type**: String fields and string vectors are candidates. UUIDs, dates, numbers, and enums typically are not worth embedding.

For EACH field, provide:
- fieldName: The field name as a string
- shouldEmbed: true if this field should be embedded, false otherwise
- confidence: Your confidence score from 0.0 to 1.0
- reasoning: Brief explanation of your decision

Examples of good embedding candidates:
- Description fields with narrative text
- Notes or comments with free-form content
- Summary or analysis fields
- Keyword or tag vectors

Examples of poor embedding candidates:
- ID fields (uuid, database IDs)
- Timestamps (created-at, updated-at)
- Status enums (active, pending, closed)
- Numeric metrics without context
- Email addresses, phone numbers, URLs

Return your assessment as a JSON object with a 'fields' array containing one assessment object per field."
        :reads [:field-candidates]
        :writes [:llm-assessment]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; -----------------------------------------------------------------------
      ;; Step 3: Filter and Rank
      ;; Code node applies confidence threshold and produces final results
      ;; -----------------------------------------------------------------------
      (sheet/code "filter-results"
        :fn "ai.obney.orc.ontology.core.field-analyzer/filter-embeddable-fields"
        :reads [:llm-assessment :confidence-threshold]
        :writes [:embeddable-fields :confidence-scores :reasoning]))))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn build-field-analyzer!
  "Build the field analyzer workflow in the given context.

   Returns the sheet-id for subsequent executions."
  [ctx]
  (sheet/build-workflow! ctx field-analyzer-workflow))

(defn analyze-schema
  "Execute field analysis on a schema.

   Args:
     ctx: Application context with sheet service
     sheet-id: Pre-built sheet ID (or nil to build new)
     schema: Malli schema to analyze
     sample-data: Vector of sample records
     opts: {:confidence-threshold 0.7}

   Returns:
     {:embeddable-fields [:field1 :field2]
      :confidence-scores {...}
      :reasoning {...}
      :trace-id ...}"
  [ctx sheet-id schema sample-data & {:keys [confidence-threshold]
                                       :or {confidence-threshold 0.7}}]
  (let [sid (or sheet-id (build-field-analyzer! ctx))
        result (sheet/execute ctx sid
                              {:schema schema
                               :sample-data sample-data
                               :confidence-threshold confidence-threshold})]
    (if (:success? result)
      {:embeddable-fields (get-in result [:outputs :embeddable-fields])
       :confidence-scores (get-in result [:outputs :confidence-scores])
       :reasoning (get-in result [:outputs :reasoning])
       :trace-id (:trace-id result)
       :method :llm}
      {:error (:error result)
       :trace-id (:trace-id result)})))
