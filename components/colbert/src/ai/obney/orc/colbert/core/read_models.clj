(ns ai.obney.orc.colbert.core.read-models
  "Event projections for ColBERT component.

   Read models reconstruct state from events:
   - indexes: All created indexes (including source docs for rebuild)

   The index read model stores full document content, so an index artifact
   on disk (derived data) can always be rebuilt from the event store."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
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
             ;; Store full source data for rebuild
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

(defmethod apply-index-event :colbert/index-deleted
  [state {:keys [index-id deleted-at]}]
  (-> state
      (assoc-in [:indexes index-id :status] :deleted)
      (assoc-in [:indexes index-id :deleted-at] (str deleted-at))))

(defmethod apply-index-event :colbert/index-activated
  [state {:keys [alias index-id activated-at]}]
  (assoc-in state [:active-aliases alias]
            {:alias alias :index-id index-id :activated-at (str activated-at)}))

(defmethod apply-index-event :colbert/index-activation-failed
  [state {:keys [alias index-id active-index-id error failed-at]}]
  (assoc-in state [:activation-failures alias]
            {:alias alias :index-id index-id :active-index-id active-index-id
             :error error :failed-at (str failed-at)}))

(defmethod apply-index-event :default
  [state _event]
  state)

(defn apply-index-events
  "Reduce a sequence of events into an indexes read model."
  [events]
  (reduce apply-index-event {:indexes {}} events))

(defreadmodel :colbert indexes
  {:events index-event-types :version 2}
  [state event] (apply-index-event state event))

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
  ;; Alias activation is a single-writer event pointer. Read it directly so
  ;; command-side validation observes the latest committed activation even
  ;; when a cached read-model projection has not yet incorporated that event.
  ;; Failed activations are deliberately ignored: they are audit evidence and
  ;; never replace the last successfully validated pointer.
  (when-let [{:keys [index-id] :as activation}
             (->> (es/read (:event-store ctx)
                           {:tenant-id (:tenant-id ctx)
                            :types #{:colbert/index-activated}})
                  (into [])
                  (filter #(= alias (:alias %)))
                  last)]
    (assoc (select-keys activation [:alias :index-id :activated-at])
           :index (get-index ctx index-id))))
