(ns ai.obney.orc.colbert.interface.schemas
  "Malli schemas for ColBERT component.

   Events store everything needed to rebuild indexes from scratch.
   The PLAID index on disk is a 'materialized view' - the event store
   is the source of truth.

   Types:
   - :colbert/result - Search result structure
   - :colbert/index-config - Index configuration

   Events:
   - :colbert/index-created - Full source data for regeneration
   - :colbert/index-updated - Incremental updates
   - :colbert/index-deleted - Soft delete marker
   - :colbert/search-performed - Search audit trail
   - :colbert/training-data-prepared - Training data preparation audit
   - :colbert/training-started - Training session start
   - :colbert/training-completed - Training session end
   - :colbert/training-failed - Training session failure"
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
    [:use-faiss? :boolean]]

   :colbert/training-config
   [:map
    [:batch-size :int]
    [:nbits :int]
    [:maxsteps :int]
    [:learning-rate :double]
    [:dim :int]
    [:doc-maxlen :int]
    [:use-ib-negatives :boolean]
    [:warmup-steps :int]
    [:accumsteps :int]]})

;; =============================================================================
;; Event Schemas
;; =============================================================================

(defschemas events
  {;; =========================================================================
   ;; Index Lifecycle Events
   ;; =========================================================================

   ;; Index creation stores FULL source data for regeneration
   ;; If disk is wiped, the PLAID index can be rebuilt from this event
   :colbert/index-created
   [:map
    [:index-id :uuid]
    [:index-name :string]
    [:index-path :string]

    ;; SOURCE DATA - enables regeneration if disk wiped
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

   ;; Incremental index updates (for audit trail)
   :colbert/index-updated
   [:map
    [:index-id :uuid]
    [:update-type [:enum :add :remove]]
    ;; For :add updates
    [:documents-added {:optional true} [:vector :string]]
    [:document-ids-added {:optional true} [:vector :string]]
    [:document-metadatas-added {:optional true} [:vector [:map-of :keyword :any]]]
    ;; For :remove updates
    [:document-ids-removed {:optional true} [:vector :string]]
    [:updated-at :string]]

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
    [:performed-at :string]]

   ;; =========================================================================
   ;; Training Events
   ;; =========================================================================

   :colbert/training-started
   [:map
    [:training-id :uuid]
    [:model-name :string]
    [:base-model :string]
    [:training-data-path :string]
    [:training-data-size :int]
    [:config [:map-of :keyword :any]]
    [:started-at :string]]

   :colbert/training-completed
   [:map
    [:training-id :uuid]
    [:checkpoint-path :string]
    [:final-step :int]
    [:duration-ms :int]
    [:completed-at :string]]

   :colbert/training-failed
   [:map
    [:training-id :uuid]
    [:error-message :string]
    [:error-details {:optional true} [:map-of :keyword :any]]
    [:failed-at :string]]

   ;; Training data preparation event - tracks data prep for audit trail
   :colbert/training-data-prepared
   [:map
    [:preparation-id :uuid]
    [:input-format [:enum :pairs :labeled-pairs :triplets]]
    [:num-queries :int]
    [:num-triplets :int]
    [:hard-negatives-mined? :boolean]
    [:num-new-negatives {:optional true} :int]
    [:output-path :string]
    [:prepared-at :string]]})

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

   :colbert/add-to-index
   [:map
    [:index-id :uuid]
    [:documents [:vector :string]]
    [:document-ids {:optional true} [:vector :string]]
    [:document-metadatas {:optional true} [:vector [:map-of :keyword :any]]]]

   :colbert/remove-from-index
   [:map
    [:index-id :uuid]
    [:document-ids [:vector :string]]]

   :colbert/search
   [:map
    [:index-id :uuid]
    [:query :string]
    [:k {:optional true} :int]]

   :colbert/rerank
   [:map
    [:query :string]
    [:documents [:vector :string]]
    [:k {:optional true} :int]]

   :colbert/start-training
   [:map
    [:model-name :string]
    [:base-model {:optional true} :string]
    [:training-data-path :string]
    [:config {:optional true} [:map-of :keyword :any]]]

   :colbert/regenerate-index
   [:map
    [:index-id :uuid]]

   :colbert/prepare-training-data
   [:map
    [:raw-data [:or
                ;; pairs: [[query positive] ...]
                [:vector [:tuple :string :string]]
                ;; labeled-pairs: [[query passage label] ...]
                [:vector [:tuple :string :string [:enum 0 1]]]
                ;; triplets: [[query positive negative] ...]
                [:vector [:tuple :string :string :string]]]]
    [:output-path :string]
    [:format [:enum :pairs :labeled-pairs :triplets]]
    [:mine-hard-negatives? {:optional true} :boolean]
    [:num-new-negatives {:optional true} :int]
    [:hard-negative-min-rank {:optional true} :int]
    [:all-documents {:optional true} [:vector :string]]]})

;; =============================================================================
;; Query Schemas
;; =============================================================================

(defschemas queries
  {:colbert/get-index
   [:map
    [:index-id :uuid]]

   :colbert/list-indexes
   [:map
    [:include-deleted {:optional true} :boolean]]

   :colbert/get-search-history
   [:map
    [:index-id {:optional true} :uuid]
    [:limit {:optional true} :int]
    [:since {:optional true} :string]]

   :colbert/get-training-status
   [:map
    [:training-id :uuid]]})

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
                [:deleted-at {:optional true} :string]]]]]

   :colbert/training-read-model
   [:map
    [:trainings [:map-of :uuid
                 [:map
                  [:training-id :uuid]
                  [:model-name :string]
                  [:status [:enum :running :completed :failed]]
                  [:started-at :string]
                  [:completed-at {:optional true} :string]
                  [:checkpoint-path {:optional true} :string]
                  [:error-message {:optional true} :string]]]]]})
