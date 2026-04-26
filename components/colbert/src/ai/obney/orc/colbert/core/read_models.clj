(ns ai.obney.orc.colbert.core.read-models
  "Event projections for ColBERT component.

   Read models reconstruct state from events:
   - indexes: All created indexes (including source docs for regeneration)
   - trainings: Training session status

   The index read model stores full document content enabling
   index regeneration if the PLAID files on disk are lost."
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [com.brunobonacci.mulog :as mu]))

;; =============================================================================
;; Event Types
;; =============================================================================

(def colbert-event-types
  #{:colbert/index-created
    :colbert/index-updated
    :colbert/index-deleted
    :colbert/search-performed
    :colbert/rerank-performed
    :colbert/training-started
    :colbert/training-completed
    :colbert/training-failed})

(def index-event-types
  #{:colbert/index-created
    :colbert/index-updated
    :colbert/index-deleted})

(def training-event-types
  #{:colbert/training-started
    :colbert/training-completed
    :colbert/training-failed})

(def search-event-types
  #{:colbert/search-performed})

;; =============================================================================
;; Index Read Model
;; =============================================================================

(defmulti apply-index-event
  "Apply an event to the indexes read model."
  (fn [_state event] (:event/type event)))

(defmethod apply-index-event :colbert/index-created
  [state {:keys [index-id index-name index-path documents document-ids
                 document-metadatas document-count passage-count
                 model-name config created-at]}]
  (assoc-in state [:indexes index-id]
            {:index-id index-id
             :index-name index-name
             :index-path index-path
             ;; Store full source data for regeneration
             :documents documents
             :document-ids document-ids
             :document-metadatas document-metadatas
             ;; Counts for quick queries
             :document-count document-count
             :passage-count passage-count
             ;; Config for exact rebuild
             :model-name model-name
             :config config
             :created-at (str created-at)
             :status :active}))

(defmethod apply-index-event :colbert/index-updated
  [state {:keys [index-id update-type documents-added document-ids-added
                 document-metadatas-added document-ids-removed updated-at]}]
  (if-let [index (get-in state [:indexes index-id])]
    (case update-type
      :add
      (-> state
          (update-in [:indexes index-id :documents]
                     #(into (vec %) documents-added))
          (update-in [:indexes index-id :document-ids]
                     #(into (vec %) document-ids-added))
          (update-in [:indexes index-id :document-metadatas]
                     #(into (vec %) document-metadatas-added))
          (update-in [:indexes index-id :document-count]
                     + (count documents-added))
          (assoc-in [:indexes index-id :updated-at] (str updated-at)))

      :remove
      (let [remove-set (set document-ids-removed)
            current-docs (:documents index)
            current-ids (:document-ids index)
            current-meta (:document-metadatas index)
            ;; Find indices to keep
            keep-indices (keep-indexed
                          (fn [i id]
                            (when-not (remove-set id) i))
                          current-ids)]
        (-> state
            (assoc-in [:indexes index-id :documents]
                      (mapv #(nth current-docs %) keep-indices))
            (assoc-in [:indexes index-id :document-ids]
                      (mapv #(nth current-ids %) keep-indices))
            (assoc-in [:indexes index-id :document-metadatas]
                      (when current-meta
                        (mapv #(nth current-meta %) keep-indices)))
            (update-in [:indexes index-id :document-count]
                       - (count document-ids-removed))
            (assoc-in [:indexes index-id :updated-at] (str updated-at)))))
    state))

(defmethod apply-index-event :colbert/index-deleted
  [state {:keys [index-id deleted-at]}]
  (-> state
      (assoc-in [:indexes index-id :status] :deleted)
      (assoc-in [:indexes index-id :deleted-at] (str deleted-at))))

(defmethod apply-index-event :default
  [state _event]
  state)

(defn apply-index-events
  "Reduce a sequence of events into an indexes read model."
  [events]
  (reduce apply-index-event {:indexes {}} events))

(defreadmodel :colbert indexes
  {:events index-event-types :version 1 :l1-ttl-ms 0}
  [state event] (apply-index-event state event))

;; =============================================================================
;; Training Read Model
;; =============================================================================

(defmulti apply-training-event
  "Apply an event to the trainings read model."
  (fn [_state event] (:event/type event)))

(defmethod apply-training-event :colbert/training-started
  [state {:keys [training-id model-name base-model training-data-path
                 training-data-size config started-at]}]
  (assoc-in state [:trainings training-id]
            {:training-id training-id
             :model-name model-name
             :base-model base-model
             :training-data-path training-data-path
             :training-data-size training-data-size
             :config config
             :status :running
             :started-at (str started-at)}))

(defmethod apply-training-event :colbert/training-completed
  [state {:keys [training-id checkpoint-path final-step duration-ms completed-at]}]
  (-> state
      (assoc-in [:trainings training-id :status] :completed)
      (assoc-in [:trainings training-id :checkpoint-path] checkpoint-path)
      (assoc-in [:trainings training-id :final-step] final-step)
      (assoc-in [:trainings training-id :duration-ms] duration-ms)
      (assoc-in [:trainings training-id :completed-at] (str completed-at))))

(defmethod apply-training-event :colbert/training-failed
  [state {:keys [training-id error-message error-details failed-at]}]
  (-> state
      (assoc-in [:trainings training-id :status] :failed)
      (assoc-in [:trainings training-id :error-message] error-message)
      (assoc-in [:trainings training-id :error-details] error-details)
      (assoc-in [:trainings training-id :failed-at] (str failed-at))))

(defmethod apply-training-event :default
  [state _event]
  state)

(defn apply-training-events
  "Reduce a sequence of events into a trainings read model."
  [events]
  (reduce apply-training-event {:trainings {}} events))

(defreadmodel :colbert trainings
  {:events training-event-types :version 1 :l1-ttl-ms 0}
  [state event] (apply-training-event state event))

;; =============================================================================
;; Search History Read Model
;; =============================================================================

(defn apply-search-event
  "Apply a search event to the search history read model."
  [state event]
  (update state :searches (fnil conj [])
          {:index-id (:index-id event)
           :query (:query event)
           :top-k (:top-k event)
           :result-count (:result-count event)
           :searched-at (str (:event/timestamp event))}))

(defreadmodel :colbert search-history
  {:events search-event-types :version 1 :l1-ttl-ms 0}
  [state event] (apply-search-event state event))

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

(defn get-training
  "Get training info by ID from event store."
  [ctx training-id]
  (get-in (rmp/project ctx :colbert/trainings {:tags #{[:training training-id]}})
          [:trainings training-id]))

(defn list-trainings
  "List all trainings, optionally filtered by status."
  [ctx & {:keys [status]}]
  (let [state (rmp/project ctx :colbert/trainings)
        all-trainings (vals (:trainings state))]
    (if status
      (filter #(= status (:status %)) all-trainings)
      all-trainings)))

(defn get-index-for-regeneration
  "Get index with full source data for regeneration.

   Returns the documents and config needed to recreate the PLAID index."
  [ctx index-id]
  (when-let [index (get-index ctx index-id)]
    (when (= :active (:status index))
      (select-keys index [:index-id :index-name :documents :document-ids
                          :document-metadatas :model-name :config]))))

(defn get-search-history
  "Get search audit history for an index."
  [ctx & {:keys [index-id limit since]}]
  (let [opts (cond-> {}
               index-id (assoc :tags #{[:index index-id]})
               since (assoc :after since))
        state (rmp/project ctx :colbert/search-history opts)
        searches (:searches state)]
    (cond->> searches
      limit (take limit))))
