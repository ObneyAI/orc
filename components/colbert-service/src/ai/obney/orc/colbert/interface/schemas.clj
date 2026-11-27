(ns ai.obney.orc.colbert.interface.schemas
  "Malli schemas for ColBERT component.

   Events store everything needed to rebuild indexes from scratch.
   The index artifact on disk is derived data - the event store
   is the source of truth.

   Types:
   - :colbert/result - Search result structure
   - :colbert/index-config - Index configuration

   Events:
   - :colbert/index-created - Full source data for rebuild
   - :colbert/index-deleted - Soft delete marker
   - :colbert/search-performed - Search audit trail
   - :colbert/rerank-performed - Rerank audit trail"
  (:require [ai.obney.orc.colbert.core.logic :as logic]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Type Schemas
;; =============================================================================

(defschemas types
  {:colbert/document-metadata
   [:map-of :keyword [:fn {:error/message "metadata must be serializable in the index artifact"} logic/writable-metadata?]]

   :colbert/stored-index-config
   [:map
    [:split-documents? :boolean]
    [:max-document-length :int]
    [:maximum-query-tokens {:optional true} :int]
    [:use-faiss? :boolean]]

   :colbert/result
   [:map
    [:content :string]
    [:score :double]
    [:rank :int]
    [:document-id {:optional true} :string]
    [:document-metadata {:optional true} :colbert/document-metadata]]

   :colbert/index-config
   [:map
    [:index-name :string]
    [:model-name :string]
    [:split-documents? :boolean]
    [:max-document-length :int]
    [:maximum-query-tokens {:optional true} :int]
    [:use-faiss? :boolean]]})

;; =============================================================================
;; Event Schemas
;; =============================================================================

(defschemas events
  {

   :colbert/index-created
   [:map
    [:index-id :uuid]
    [:index-name :string]
    [:index-path :string]

    [:documents [:vector :string]]
    [:document-ids [:vector :string]]
    [:document-metadatas {:optional true} [:maybe [:vector :colbert/document-metadata]]]

    [:document-count :int]
    [:passage-count :int]

    [:model-name :string]
    [:config :colbert/stored-index-config]
    [:created-at :string]
    [:duration-ms {:optional true} :int]]

   :colbert/index-deleted
   [:map
    [:index-id :uuid]
    [:deleted-at :string]]

   :colbert/index-activated
   [:map
    [:alias :string]
    [:index-id :uuid]
    [:previous-index-id {:optional true} :uuid]
    [:activated-at :string]]

   :colbert/index-activation-failed
   [:map
    [:alias :string]
    [:index-id :uuid]
    [:active-index-id {:optional true} :uuid]
    [:error :string]
    [:failed-at :string]]

   ;; =========================================================================
   ;; Search Audit Events
   ;; =========================================================================

   :colbert/search-performed
   [:map
    [:search-id :uuid]
    [:index-id :uuid]
    [:query :string]
    [:k :int]
    [:result-count :int]
    [:latency-ms :int]
    [:top-score {:optional true} :double]
    [:performed-at :string]
    [:query-token-count {:optional true} :int]
    [:maximum-query-tokens {:optional true} :int]
    [:query-truncated? {:optional true} :boolean]
    [:discarded-token-count {:optional true} :int]]

   :colbert/rerank-performed
   [:map
    [:rerank-id :uuid]
    [:query :string]
    [:input-count :int]
    [:output-count :int]
    [:latency-ms :int]
    [:top-score {:optional true} :double]
    [:performed-at :string]
    [:query-token-count {:optional true} :int]
    [:maximum-query-tokens {:optional true} :int]
    [:query-truncated? {:optional true} :boolean]
    [:discarded-token-count {:optional true} :int]]})

;; =============================================================================
;; Command Schemas
;; =============================================================================

(defschemas commands
  {:colbert/create-index
   [:map
    [:collection [:vector :string]]
    [:index-name :string]
    [:document-ids {:optional true} [:vector :string]]
    [:document-metadatas {:optional true} [:vector :colbert/document-metadata]]
    [:model-name {:optional true} :string]
    [:split-documents? {:optional true} :boolean]
    [:max-document-length {:optional true} :int]
    [:maximum-query-tokens {:optional true} :int]]

   :colbert/delete-index
   [:map
    [:index-id :uuid]]

   :colbert/activate-index
   [:map
    [:alias :string]
    [:index-id :uuid]]

   :colbert/search
   [:map
    [:index-id :uuid]
    [:query :string]
    [:k {:optional true} :int]]

   :colbert/rerank
   [:map
    [:query :string]
    [:documents [:vector :string]]
    [:k {:optional true} :int]
    [:maximum-query-tokens {:optional true} :int]]})

;; =============================================================================
;; Query Schemas
;; =============================================================================

(defschemas queries
  {:colbert/get-index
   [:map
    [:index-id :uuid]]

   :colbert/list-indexes
   [:map
    [:include-deleted {:optional true} :boolean]]})

;; =============================================================================
;; Read Model Schemas
;; =============================================================================

(defschemas read-models
  {:colbert/indexes-read-model
   [:map
    [:indexes [:map-of :uuid
               [:map
                [:index-id :uuid]
                [:index-name :string]
                [:index-path :string]
                [:document-count :int]
                [:passage-count :int]
                [:model-name :string]
                [:config :colbert/stored-index-config]
                [:created-at :string]
                [:deleted-at {:optional true} :string]]]]]})
