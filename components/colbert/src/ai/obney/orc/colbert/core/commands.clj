(ns ai.obney.orc.colbert.core.commands
  "Command handlers for ColBERT operations.

   All event emission for the colbert component is consolidated here.
   Commands validate inputs, delegate to operations (the pure-JVM
   encoder/MaxSim pipeline, ADR 0002), and emit events via the command
   processor return value."
  (:require [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.core.index-store :as index-store]
            [ai.obney.orc.colbert.core.read-models :as read-models]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]
            [com.brunobonacci.mulog :as mu]))

;; =============================================================================
;; Index Commands
;; =============================================================================

(defn- alias-tag-id [alias]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (str "colbert-index-alias:" alias)
              java.nio.charset.StandardCharsets/UTF_8)))

(defcommand :colbert create-index
  "Create a ColBERT index from documents.

   Delegates to operations/create-index! (pure JVM), then emits the
   :colbert/index-created event with full source data so the index
   artifact is always rebuildable."
  [{:keys [event-store] :as ctx
    {:keys [collection document-ids document-metadatas index-name
            model-name split-documents? max-document-length]
     :as command} :command}]
  (try
    (let [result (operations/create-index! ctx
                   {:collection collection
                    :document-ids document-ids
                    :document-metadatas document-metadatas
                    :index-name index-name
                    :model-name model-name
                    :split-documents? split-documents?
                    :max-document-length max-document-length})]

      {:command-result/events
       [(->event {:type :colbert/index-created
                  :tags #{[:index (:index-id result)]}
                  :body {:index-id (:index-id result)
                         :index-name (:index-name result)
                         :index-path (:index-path result)
                         ;; Store full source data for regeneration
                         :documents (vec collection)
                         :document-ids (vec (:document-ids result))
                         :document-metadatas (when (:document-metadatas result)
                                               (vec (:document-metadatas result)))
                         ;; Counts
                         :document-count (:document-count result)
                         :passage-count (:num-passages result)
                         ;; Config for rebuild
                         :model-name (:model-name result)
                         :config (:config result)
                         :created-at (str (time/now))
                         :duration-ms (:duration-ms result)}})]
       :command/result {:index-id (:index-id result)
                        :index-path (:index-path result)}})

    (catch Exception e
      (mu/log ::create-index-failed :error (ex-message e))
      {::anom/category ::anom/fault
       ::anom/message (str "Failed to create index: " (ex-message e))})))

(defcommand :colbert delete-index
  "Mark an index as deleted."
  [{:keys [event-store]
    {:keys [index-id]} :command
    :as ctx}]
  (if-let [index (read-models/get-index ctx index-id)]
    (if (= :deleted (:status index))
      {::anom/category ::anom/conflict
       ::anom/message "Index already deleted"}
      {:command-result/events
       [(->event {:type :colbert/index-deleted
                  :tags #{[:index index-id]}
                  :body {:index-id index-id
                         :deleted-at (str (time/now))}})]})
    {::anom/category ::anom/not-found
     ::anom/message "Index not found"}))

(defcommand :colbert activate-index
  "Atomically point a stable alias at a fully readable index artifact.

   The artifact is opened before the activation event is emitted. A failed
   validation emits visible failure evidence and deliberately leaves the
   existing alias projection unchanged."
  [{{:keys [alias index-id]} :command :as ctx}]
  (let [active-index-id (:index-id (read-models/get-active-index ctx alias))
        index (read-models/get-index ctx index-id)]
    (try
      (when-not (and index (= :active (:status index)))
        (throw (ex-info "Index not found or deleted" {:index-id index-id})))
      (index-store/read-index (:index-path index))
      {:command-result/events
       [(->event {:type :colbert/index-activated
                  :tags #{[:index index-id] [:index-alias (alias-tag-id alias)]}
                  :body (cond-> {:alias alias
                                 :index-id index-id
                                 :activated-at (str (time/now))}
                          active-index-id (assoc :previous-index-id active-index-id))})]
       :command/result {:status :activated :alias alias :index-id index-id}}
      (catch Exception e
        {:command-result/events
         [(->event {:type :colbert/index-activation-failed
                    :tags #{[:index index-id] [:index-alias (alias-tag-id alias)]}
                    :body (cond-> {:alias alias
                                   :index-id index-id
                                   :error (ex-message e)
                                   :failed-at (str (time/now))}
                            active-index-id (assoc :active-index-id active-index-id))})]
         :command/result {:status :failed
                          :alias alias
                          :index-id index-id
                          :active-index-id active-index-id
                          :error (ex-message e)}}))))

;; =============================================================================
;; Search Commands (with audit events)
;; =============================================================================

(defcommand :colbert search
  "Search an index and emit audit event.

   Delegates to operations/search (pure JVM), then emits
   a :colbert/search-performed audit event."
  [{:keys [event-store]
    {:keys [index-id query k]} :command
    :as ctx}]
  (let [k (or k 10)
        search-id (random-uuid)
        start-time (System/currentTimeMillis)]
    (try
      (let [results (operations/search ctx {:query query :index-id index-id :k k})
            latency-ms (- (System/currentTimeMillis) start-time)
            top-score (when (seq results) (:score (first results)))]

        {:command-result/events
         [(->event {:type :colbert/search-performed
                    :tags #{[:index index-id] [:search search-id]}
                    :body {:search-id search-id
                           :index-id index-id
                           :query query
                           :k k
                           :result-count (count results)
                           :latency-ms latency-ms
                           :top-score top-score
                           :performed-at (str (time/now))}})]
         :command/result {:results results
                          :search-id search-id}})

      (catch clojure.lang.ExceptionInfo e
        ;; Re-raise not-found / deleted as anomalies
        (let [data (ex-data e)]
          (if (:index-id data)
            {::anom/category ::anom/not-found
             ::anom/message (ex-message e)}
            {::anom/category ::anom/fault
             ::anom/message (str "Search failed: " (ex-message e))})))
      (catch Exception e
        {::anom/category ::anom/fault
         ::anom/message (str "Search failed: " (ex-message e))}))))

(defcommand :colbert rerank
  "Rerank documents and emit audit event.

   Delegates to operations/rerank (pure JVM), then emits
   a :colbert/rerank-performed audit event."
  [{{:keys [query documents k]} :command :as ctx}]
  (let [rerank-id (random-uuid)
        start-time (System/currentTimeMillis)]
    (try
      (let [results (operations/rerank ctx {:query query :documents documents :k k})
            latency-ms (- (System/currentTimeMillis) start-time)
            top-score (when (seq results) (:score (first results)))]

        {:command-result/events
         [(->event {:type :colbert/rerank-performed
                    :tags #{[:rerank rerank-id]}
                    :body {:rerank-id rerank-id
                           :query query
                           :input-count (count documents)
                           :output-count (count results)
                           :latency-ms latency-ms
                           :top-score top-score
                           :performed-at (str (time/now))}})]
         :command/result {:results results
                          :rerank-id rerank-id}})

      (catch Exception e
        {::anom/category ::anom/fault
         ::anom/message (str "Rerank failed: " (ex-message e))}))))
