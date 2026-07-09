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
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Type Schemas
;; =============================================================================

(defschemas types
  {:colbert/result
   [:map
    [:content :string]
    [:score :double]
    [:rank :int]
    [:document-id {:optional true} :string]
    [:document-metadata {:optional true} [:map-of :keyword :any]]]

   :colbert/index-config
   [:map
    [:index-name :string]
    [:model-name :string]
    [:split-documents? :boolean]
    [:max-document-length :int]
    [:use-faiss? :boolean]]})

;; =============================================================================
;; Event Schemas
;; =============================================================================

(defschemas events
  {;; =========================================================================
   ;; Index Lifecycle Events
   ;; =========================================================================

   ;; Index creation stores FULL source data for rebuild
   ;; If disk is wiped, the index artifact can be rebuilt from this event
   :colbert/index-created
   [:map
    [:index-id :uuid]
    [:index-name :string]
    [:index-path :string]

    ;; SOURCE DATA - enables rebuild if disk wiped
    [:documents [:vector :string]]
    [:document-ids [:vector :string]]
    ;; nil = "no per-document metadata" is a valid state; the create-index command
    ;; emits the key as nil when none are supplied (concept indexing), so accept it
    ;; (optional alone permits absence, not a present nil).
    [:document-metadatas {:optional true} [:maybe [:vector [:map-of :keyword :any]]]]

    ;; COUNTS - for quick queries without loading docs
    [:document-count :int]
    [:passage-count :int]  ; After splitting

    ;; CONFIG - enables exact rebuild with same parameters
    [:model-name :string]
    [:config [:map
              [:split-documents? :boolean]
              [:max-document-length :int]
              [:use-faiss? :boolean]]]
    [:created-at :string]
    [:duration-ms {:optional true} :int]]

   ;; Soft delete marker
   :colbert/index-deleted
   [:map
    [:index-id :uuid]
    [:deleted-at :string]]

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
    [:performed-at :string]]

   :colbert/rerank-performed
   [:map
    [:rerank-id :uuid]
    [:query :string]
    [:input-count :int]
    [:output-count :int]
    [:latency-ms :int]
    [:top-score {:optional true} :double]
    [:performed-at :string]]})

;; =============================================================================
;; Command Schemas
;; =============================================================================

(defschemas commands
  {:colbert/create-index
   [:map
    [:collection [:vector :string]]
    [:index-name :string]
    [:document-ids {:optional true} [:vector :string]]
    [:document-metadatas {:optional true} [:vector [:map-of :keyword :any]]]
    [:model-name {:optional true} :string]
    [:split-documents? {:optional true} :boolean]
    [:max-document-length {:optional true} :int]]

   :colbert/delete-index
   [:map
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
    [:k {:optional true} :int]]})

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
                [:config [:map-of :keyword :any]]
                [:created-at :string]
                [:deleted-at {:optional true} :string]]]]]})
