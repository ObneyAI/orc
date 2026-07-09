(ns ai.obney.orc.colbert.core.queries
  "Query handlers for ColBERT component.

   Queries read from the event store via read models and return data."
  (:require [ai.obney.orc.colbert.core.read-models :as read-models]
            [ai.obney.grain.query-processor.interface :refer [defquery]]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Index Queries
;; =============================================================================

(defquery :colbert get-index
  "Get index details by ID."
  [{:keys [event-store]
    {:keys [index-id]} :query}]
  (if-let [index (read-models/get-index event-store index-id)]
    {:query/result (dissoc index :documents :document-metadatas)}
    {::anom/category ::anom/not-found
     ::anom/message "Index not found"}))

(defquery :colbert get-index-full
  "Get index with full source data (for regeneration or inspection).

   Warning: May be large if index has many documents."
  [{:keys [event-store]
    {:keys [index-id]} :query}]
  (if-let [index (read-models/get-index event-store index-id)]
    {:query/result index}
    {::anom/category ::anom/not-found
     ::anom/message "Index not found"}))

(defquery :colbert list-indexes
  "List all indexes.

   Options:
     include-deleted - Include soft-deleted indexes (default: false)"
  [{:keys [event-store]
    {:keys [include-deleted]} :query}]
  (let [indexes (read-models/list-indexes event-store
                                           :include-deleted (boolean include-deleted))]
    {:query/result
     {:indexes (mapv #(dissoc % :documents :document-metadatas) indexes)
      :count (count indexes)}}))

(defquery :colbert get-index-for-regeneration
  "Get index with data needed for regeneration.

   Returns: {:index-id :index-name :documents :document-ids
             :document-metadatas :model-name :config}"
  [{:keys [event-store]
    {:keys [index-id]} :query}]
  (if-let [data (read-models/get-index-for-regeneration event-store index-id)]
    {:query/result data}
    {::anom/category ::anom/not-found
     ::anom/message "Index not found or deleted"}))
