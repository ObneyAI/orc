(ns ai.obney.orc.colbert.core.read-models
  "Event projections for ColBERT component.

   Read models reconstruct state from events:
   - indexes: All created indexes (including source docs for rebuild)

   The index read model stores full document content, so an index artifact
   on disk (derived data) can always be rebuilt from the event store."
  (:require [ai.obney.orc.colbert.core.logic :as logic]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]))

;; =============================================================================
;; Event Types
;; =============================================================================

(def colbert-event-types
  #{:colbert/index-created
    :colbert/index-deleted
    :colbert/index-activated
    :colbert/index-activation-failed
    :colbert/search-performed
    :colbert/rerank-performed})

(def index-event-types
  #{:colbert/index-created
    :colbert/index-deleted
    :colbert/index-activated
    :colbert/index-activation-failed})

;; =============================================================================
;; Index Read Model
;; =============================================================================

(defreadmodel :colbert indexes
  {:events index-event-types :version 1}
  [state event] (logic/apply-index-event state event))

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn get-index
  "Get index info by ID from event store."
  [ctx index-id]
  (get-in (rmp/project ctx :colbert/indexes {:tags #{[:index index-id]}})
          [:indexes index-id]))

(defn list-indexes
  "List all indexes, optionally including deleted ones."
  [ctx & {:keys [include-deleted] :or {include-deleted false}}]
  (let [state (rmp/project ctx :colbert/indexes)
        all-indexes (vals (:indexes state))]
    (if include-deleted
      all-indexes
      (filter #(= :active (:status %)) all-indexes))))

(defn get-index-for-regeneration
  "Get index with full source data for rebuild.

   Returns the documents and config needed to recreate the index artifact."
  [ctx index-id]
  (when-let [index (get-index ctx index-id)]
    (when (= :active (:status index))
      (select-keys index [:index-id :index-name :documents :document-ids
                          :document-metadatas :model-name :config]))))

(defn get-active-index
  "Resolve one active alias from its event-sourced pointer."
  [ctx alias]
  (when-let [{:keys [index-id] :as activation}
             (->> (es/read (:event-store ctx)
                           {:tenant-id (:tenant-id ctx)
                            :types #{:colbert/index-activated}})
                  (into [])
                  (filter #(= alias (:alias %)))
                  last)]
    (assoc (select-keys activation [:alias :index-id :activated-at])
           :index (get-index ctx index-id))))
