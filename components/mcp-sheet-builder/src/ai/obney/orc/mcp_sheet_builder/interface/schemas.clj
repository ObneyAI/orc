(ns ai.obney.orc.mcp-sheet-builder.interface.schemas
  "Malli schemas for MCP Sheet Builder.

   Defines schemas for:
   - MCP tool definitions
   - Analysis results
   - Pattern specifications
   - Generated workflows")

;; ============================================================================
;; MCP Tool Schemas
;; ============================================================================

(def mcp-tool-parameter
  "Schema for a single MCP tool parameter."
  [:map
   [:name :string]
   [:type [:enum "string" "number" "integer" "boolean" "array" "object"]]
   [:description {:optional true} :string]
   [:required {:optional true} :boolean]
   [:default {:optional true} :any]
   [:properties {:optional true} [:map-of :string :any]]
   [:items {:optional true} :map]])

(def mcp-tool
  "Schema for an MCP tool definition."
  [:map
   [:name :string]
   [:description {:optional true} :string]
   [:inputSchema {:optional true} [:map
                                   [:type [:= "object"]]
                                   [:properties {:optional true} [:map-of :string :any]]
                                   [:required {:optional true} [:vector :string]]]]
   [:outputSchema {:optional true} :map]])

(def mcp-connection
  "Schema for a portable MCP connection configuration."
  [:or
   [:map
    [:type [:= :stdio]]
    [:server-id :string]
    [:command :string]
    [:args {:optional true} [:vector :string]]
    [:env {:optional true} [:map-of :string :string]]
    [:working-directory {:optional true} :string]]
   [:map
    [:type [:= :streamable-http]]
    [:server-id :string]
    [:url :string]
    [:headers {:optional true} [:map-of :string :string]]
    [:authorized-redirect-origins {:optional true} [:set :string]]]])

;; ============================================================================
;; Capability Tags
;; ============================================================================

(def capability-tag
  "Schema for a capability tag."
  [:enum
   :retrieval    ;; Fetches/reads data
   :search       ;; Searches for information
   :create       ;; Creates new entities
   :update       ;; Modifies existing entities
   :delete       ;; Removes entities
   :transform    ;; Transforms data
   :validate     ;; Validates data
   :generate     ;; Generates content (LLM-based)
   :aggregate    ;; Aggregates multiple items
   :route])      ;; Routes/dispatches to other tools

(def semantic-category
  "Schema for semantic category."
  [:enum
   :data-access     ;; Reads from data sources
   :data-mutation   ;; Writes to data sources
   :transformation  ;; Pure data transformation
   :side-effect     ;; External side effects
   :aggregation])   ;; Combines multiple inputs

;; ============================================================================
;; Analysis Schemas
;; ============================================================================

(def analyzed-tool
  "Schema for an analyzed MCP tool."
  [:map
   [:name :string]
   [:description {:optional true} :string]
   [:capabilities [:set capability-tag]]
   [:category semantic-category]
   [:input-schema :any]
   [:output-schema {:optional true} :any]
   [:malli-input :any]
   [:malli-output {:optional true} :any]
   [:idempotent? :boolean]])

(def tool-relationship
  "Schema for a relationship between tools."
  [:map
   [:type [:enum :sequential :parallel :alternative :complementary :refinement]]
   [:from :string]
   [:to :string]
   [:confidence :double]
   [:reason {:optional true} :string]])

(def tool-analysis
  "Schema for complete tool analysis."
  [:map
   [:tools [:vector analyzed-tool]]
   [:relationships [:vector tool-relationship]]
   [:patterns [:vector [:map
                        [:pattern :keyword]
                        [:confidence :double]
                        [:description {:optional true} :string]
                        [:tool-groups {:optional true} [:vector [:vector :string]]]]]]])

;; ============================================================================
;; Pattern Schemas
;; ============================================================================

(def agent-pattern
  "Schema for an agent pattern."
  [:enum
   :sequential-pipeline
   :coordinator-dispatcher
   :parallel-fan-out
   :hierarchical-decomposition
   :generator-critic
   :iterative-refinement
   :research-compilation
   :adversarial])

(def pattern-spec
  "Schema for a pattern specification."
  [:map
   [:pattern agent-pattern]
   [:confidence :double]
   [:description {:optional true} :string]
   [:tools [:vector :string]]
   [:orc-nodes {:optional true} [:vector :keyword]]])

;; ============================================================================
;; Generated Sheet Schemas
;; ============================================================================

(def generated-sheet
  "Schema for a generated ORC sheet."
  [:map
   [:workflow :any]
   [:blackboard [:map-of :keyword :any]]
   [:code :string]
   [:pattern agent-pattern]
   [:tools [:vector :string]]])

(def validation-result
  "Schema for sheet validation result."
  [:map
   [:valid? :boolean]
   [:errors [:vector :string]]
   [:warnings [:vector :string]]])
