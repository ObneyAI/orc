(ns ai.obney.orc.colbert.core.queries
  "Query handlers for ColBERT component.

   Queries read from the event store via read models and return data."
  (:require [ai.obney.orc.colbert.core.logic :as logic]
            [ai.obney.orc.colbert.core.read-models :as read-models]
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

;; --- Index retrieval ---

(defn search-query-truncation
  "The truncation report a SEARCH of `index-id` would produce — measured
   against THAT INDEX'S OWN configured maximum_query_tokens, so the audit and
   the encoding can never disagree about the limit."
  [ctx index-id query]
  (let [index (read-models/get-index ctx index-id)]
    (logic/query-truncation ctx {:query query
                                 :maximum-query-tokens (logic/index-maximum-query-tokens index)})))

(defn search
  "Search indexed corpus using ColBERT late-interaction — pure JVM (ADR
   0002), no Python process: read the index artifact (in-memory cached per
   canonical path), encode the query ONCE, exact MaxSim (zero-not-drop
   punctuation semantics) against every passage, aggregate passages to
   documents by MAX passage score, sort descending, take k.

   Does NOT emit events. Event emission is handled by the defcommand
   :colbert/search in commands.clj.

   Args:
     ctx - Context map (used for read-model lookup)
     opts - Options map:
       :query    - Search query string (required)
       :index-id - Index UUID (required)
       :k        - Number of DOCUMENTS to return (default: 10)

   Returns (snake_case keys — the exact shape the bridge returned;
   downstream search-for-rrf and ontology normalize-search-result read
   :document_id/:document_metadata):
     [{:content <best-scoring passage's text> :score <double>
       :rank <1-indexed int> :document_id <id> :document_metadata <map>}]"
  [ctx {:keys [query index-id k]}]
  (first (logic/search-index (read-models/get-index ctx index-id) index-id [query] k)))

(defn search-for-rrf
  "Search ColBERT index and return results formatted for RRF fusion.

   This is the primary integration point for ontology hybrid-search.
   Returns results in the format expected by graph/merge-batches.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :query         - Search query (required)
       :index-id      - ColBERT index UUID (required)
       :k             - Number of results (default: 20)
       :normalize?    - Whether to normalize scores to [0,1] (default: true)
       :weight        - Score weight multiplier (default: 1.0)

   Returns:
     Vector of {:uri :score} compatible with RRF merge-batches"
  [ctx {:keys [query index-id k normalize? weight]
        :or {k 20 normalize? true weight 1.0}}]
  (logic/rrf-results (search ctx {:query query :index-id index-id :k k}) normalize? weight))

(defn search-batch
  "Batch-search a ColBERT index for MANY queries with the artifact loaded ONCE
   (not once-per-query) — pure JVM (ADR 0002), no Python process. The one-load
   property comes from logic/load-index's per-canonical-path in-memory
   cache: the artifact is read from disk at most once for the whole batch (and
   for every later search against the same path). Each query is encoded ONCE
   and scored with the same exact-MaxSim document pipeline as `search`.

   Returns a vector of result-lists ALIGNED to `queries`, each list shaped
   exactly like `search`'s output (same snake_case keys).

   Args:
     ctx - Context map (used for read-model lookup)
     opts - Options map:
       :queries  - Vector of query strings (required)
       :index-id - Index UUID (required)
       :k        - Number of DOCUMENTS to return per query (default: 10)"
  [ctx {:keys [queries index-id k]}]
  (logic/search-index (read-models/get-index ctx index-id) index-id queries k))

(defn search-for-rrf-batch
  "Batched `search-for-rrf`: ONE index load (the search-batch cache pin) for
   ALL queries — pure JVM, no Python round-trips. Returns a vector aligned to
   `queries`, each element a vector of {:uri :score} ready for RRF fusion.
   This is the batched integration point for ontology hybrid-search over a
   whole transcript — it collapses N per-line index loads into one.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :queries    - Vector of query strings (required)
       :index-id   - ColBERT index UUID (required)
       :k          - Results per query (default: 20)
       :normalize? - Normalize scores to [0,1] (default: true)
       :weight     - Score weight multiplier (default: 1.0)"
  [ctx {:keys [queries index-id k normalize? weight]
        :or {k 20 normalize? true weight 1.0}}]
  (mapv #(logic/rrf-results % normalize? weight)
        (search-batch ctx {:queries queries :index-id index-id :k k})))

(defn search-active
  [ctx {:keys [alias] :as opts}]
  (if-let [index-id (:index-id (read-models/get-active-index ctx alias))]
    (search ctx (-> opts (dissoc :alias) (assoc :index-id index-id)))
    (throw (ex-info "No active ColBERT index for alias" {:alias alias}))))
