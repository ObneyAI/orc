(ns ai.obney.orc.colbert.interface
  "Pure-JVM ColBERT signal (ADR 0002).

   Late-interaction retrieval on the JVM: the answerai-colbert-small-v1
   encoder checkpoint runs on DJL OnnxRuntime + HuggingFace tokenizers,
   scoring with exact MaxSim. No Python process anywhere.

   ## Key Capabilities

   | Capability | Description | Use Case |
   |------------|-------------|----------|
   | Late-Interaction Retrieval | Token-level matching | Better semantic matching than single-vector embeddings |
   | Three-Signal Hybrid Search | Graph BFS + embeddings + ColBERT via RRF | Comprehensive retrieval (ontology `hybrid-search`) |
   | Reranking | In-memory rerank without index | Candidate selection, domain-penalty scoring |

   ## Usage Example

   ```clojure
   ;; Create an index (writes a versioned index artifact on disk)
   (def index-id
     (colbert/create-index! ctx
       {:collection [\"Doc 1 text...\" \"Doc 2 text...\"]
        :document-ids [\"doc1\" \"doc2\"]
        :index-name \"my-docs\"}))

   ;; Search
   (colbert/search ctx
     {:query \"What is machine learning?\"
      :index-id index-id
      :k 5})

   ;; Rerank candidates (no index required)
   (colbert/rerank ctx
     {:query \"AI systems\"
      :documents [\"Deep learning\" \"Weather\" \"ML algorithms\"]
      :k 2})
   ```

   ## Event Sourcing

   Index lifecycle and search/rerank audit events go to the event store:
   :colbert/index-created (full source data — the index artifact is derived
   data, always rebuildable), :colbert/index-deleted, :colbert/search-performed,
   :colbert/rerank-performed."
  (:require [ai.obney.orc.colbert.core.commands]
            [ai.obney.orc.colbert.interface.schemas]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.core.queries]
            [ai.obney.orc.colbert.core.read-models :as read-models]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Re-exports for Commands/Queries (auto-registered via defcommand/defquery)
;; =============================================================================

;; Commands and queries are auto-registered via defcommand/defquery macros
;; in core/commands.clj and core/queries.clj

;; =============================================================================
;; Index Operations
;; =============================================================================

(defn create-index!
  "Create a ColBERT index from documents — pure JVM, no Python process.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :collection         - Vector of document strings (required)
       :index-name         - Name for the index (required)
       :document-ids       - Vector of unique IDs (auto-generated if nil)
       :document-metadatas - Vector of metadata maps (optional)
       :model-name         - Recorded in the event (default: the encoder
                             checkpoint actually used)
       :split-documents?   - Auto-split long docs (default: true)
       :max-document-length - Chunk size in tokens (default: 256)

   Writes the versioned index artifact under the index root (default
   `.orc-colbert-indexes/` relative to cwd; override via
   `-Dcolbert.index.root`). Returns a map with :index-id and :index-path.
   Event emission (:colbert/index-created, with full source data for
   rebuild) is handled by the :colbert/create-index defcommand."
  [ctx opts]
  (operations/create-index! ctx opts))

(defn delete-index!
  "Mark an index as deleted.

   This is a soft delete - the source data remains in the event store.
   Routes through the command processor for proper CQRS event emission."
  [ctx index-id]
  (cp/process-command
    (assoc ctx :command {:command/name :colbert/delete-index
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :index-id index-id})))

(defn activate-index!
  "Atomically activate a fully readable index under a stable alias."
  [ctx alias index-id]
  (cp/process-command
   (assoc ctx :command {:command/name :colbert/activate-index
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :alias alias
                        :index-id index-id})))

(defn get-active-index
  "Return the event-sourced active pointer and resolved index for an alias."
  [ctx alias]
  (read-models/get-active-index ctx alias))

(defn search-active
  "Search exactly one atomic snapshot of a stable active-index alias."
  [ctx {:keys [alias] :as opts}]
  (if-let [index-id (:index-id (read-models/get-active-index ctx alias))]
    (operations/search ctx (-> opts (dissoc :alias) (assoc :index-id index-id)))
    (throw (ex-info "No active ColBERT index for alias" {:alias alias}))))

;; =============================================================================
;; Search Operations
;; =============================================================================

(defn search
  "Search indexed corpus using ColBERT late-interaction — pure JVM: read the
   index artifact (in-memory cached per canonical path), encode the query
   once, exact MaxSim against every passage, aggregate passages to documents
   by max passage score.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :query    - Search query string (required)
       :index-id - Index UUID (required)
       :k        - Number of results (default: 10)

   Returns (snake_case keys):
     [{:content \"...\" :score 30.87 :rank 1 :document_id \"...\"
       :document_metadata {...}}]"
  [ctx opts]
  (operations/search ctx opts))

(defn rerank
  "Rerank documents in-memory (no index required).

   Encodes query and documents with the JVM encoder and scores them with
   exact MaxSim. Useful for reranking candidates from other retrieval
   methods; also the default scorer for the ontology classifier's
   contrastive domain penalty.

   Args:
     ctx - Context map (event-store optional for audit)
     opts - Options map:
       :query     - Query string (required)
       :documents - Vector of document strings to rerank (required)
       :k         - Number of results (default: all documents)

   Returns:
     [{:content \"...\" :score 30.87 :rank 1}]"
  [ctx opts]
  (operations/rerank ctx opts))

;; =============================================================================
;; Score Normalization
;; =============================================================================

(defn normalize-colbert-score
  "Normalize ColBERT score to 0-1 range.

   The default ceiling 32.0 is the theoretical MaxSim bound for the
   answerai-colbert-small-v1 checkpoint: query_maxlen (32) MASK-expanded query
   rows, each unit-normed, so MaxSim <= 32.0. This normalizes raw scores for
   RRF fusion with other retrieval signals (graph BFS, embeddings). NB: MASK
   query expansion gives even unrelated pairs a high floor — score-CONTRAST
   consumers should normalize relative to the scores in the same call
   (see normalize-result-scores and the ontology domain-penalty's
   :batch-relative method) rather than against the fixed ceiling.

   Args:
     score - Raw ColBERT score (typically ~[0, 32] for this checkpoint)
     opts - Options map:
       :max-score - Maximum expected score for normalization (default: 32.0)
       :method - Normalization method: :linear, :sigmoid (default: :linear)

   Returns:
     Normalized score in [0, 1] range"
  [score & {:keys [max-score method] :as opts}]
  (apply operations/normalize-colbert-score score (mapcat identity opts)))

(defn normalize-result-scores
  "Normalize scores for a batch of ColBERT results.

   Uses the maximum score in the batch as the normalization factor,
   ensuring relative rankings are preserved.

   Args:
     results - Vector of result maps with :score key
     opts - Options map:
       :min-score-threshold - Minimum normalized score to keep (default: 0.0)

   Returns:
     Results with normalized scores in [0, 1] range"
  [results & {:keys [min-score-threshold] :as opts}]
  (apply operations/normalize-result-scores results (mapcat identity opts)))

;; =============================================================================
;; Hybrid Search Integration
;; =============================================================================

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
  [ctx opts]
  (operations/search-for-rrf ctx opts))

(defn search-batch
  "Batch-search a ColBERT index for many queries with the index artifact
   loaded ONCE (pure JVM). Returns a vector of result-lists aligned to
   `:queries`, each shaped like `search`'s output.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :queries  - Vector of query strings (required)
       :index-id - Index UUID (required)
       :k        - Results per query (default: 10)"
  [ctx opts]
  (operations/search-batch ctx opts))

(defn search-for-rrf-batch
  "Batched `search-for-rrf`: ONE index load for all queries (pure JVM).
   Returns a vector aligned to `:queries`, each a vector of {:uri :score} for RRF
   fusion. The batched integration point for ontology hybrid-search over a whole
   transcript.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :queries    - Vector of query strings (required)
       :index-id   - ColBERT index UUID (required)
       :k          - Results per query (default: 20)
       :normalize? - Normalize scores to [0,1] (default: true)
       :weight     - Score weight multiplier (default: 1.0)"
  [ctx opts]
  (operations/search-for-rrf-batch ctx opts))

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn list-indexes
  "List all indexes."
  [ctx & {:keys [include-deleted] :or {include-deleted false}}]
  (read-models/list-indexes ctx :include-deleted include-deleted))

;; =============================================================================
;; Event Types Export
;; =============================================================================

(def colbert-event-types read-models/colbert-event-types)

;; =============================================================================
;; Encoder Lifecycle
;; =============================================================================

(defn stop-bridge!
  "Release the JVM ColBERT encoders (DJL model + tokenizer handles).

   Historically this stopped the Python bridge subprocess; the signal is
   pure JVM now (ADR 0002), so teardown means closing the loaded encoders.
   Safe to call when nothing is loaded. Call during application shutdown or
   bench teardown."
  []
  (encoder/close-all-encoders!))
