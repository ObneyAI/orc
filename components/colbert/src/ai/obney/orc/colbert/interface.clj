(ns ai.obney.orc.colbert.interface
  "Native Clojure ColBERT/RAGatouille implementation.

   Provides late-interaction retrieval, reranking, and training
   capabilities with full Grain event sourcing integration.

   ## Key Capabilities

   | Capability | Description | Use Case |
   |------------|-------------|----------|
   | Late-Interaction Retrieval | Token-level matching | Better semantic matching than MiniLM |
   | Three-Signal Hybrid Search | Graph BFS + MiniLM + ColBERT via RRF | Comprehensive retrieval |
   | Tree Profile Indexing | Index tree self-descriptions | Few-shot example retrieval |
   | Reranking | In-memory rerank without index | Candidate selection |
   | Domain Training | Fine-tune on pairs/triplets | Custom retrievers |

   ## Usage Example

   ```clojure
   ;; Create an index
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

   ;; Rerank candidates
   (colbert/rerank ctx
     {:query \"AI systems\"
      :documents [\"Deep learning\" \"Weather\" \"ML algorithms\"]
      :k 2})
   ```

   ## Event Sourcing

   All operations emit events to the event store:
   - :colbert/index-created - Full source data for regeneration
   - :colbert/search-performed - Search audit trail
   - :colbert/training-started/completed - Training lifecycle"
  (:require [ai.obney.orc.colbert.core.bridge :as bridge]
            [ai.obney.orc.colbert.core.commands]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.core.queries]
            [ai.obney.orc.colbert.core.read-models :as read-models]
            [ai.obney.orc.colbert.core.training-data-processor :as training-data-processor]
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
  "Create a ColBERT index from documents.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :collection         - Vector of document strings (required)
       :index-name         - Name for the index (required)
       :document-ids       - Vector of unique IDs (auto-generated if nil)
       :document-metadatas - Vector of metadata maps (optional)
       :model-name         - ColBERT model (default: colbert-ir/colbertv2.0)
       :split-documents?   - Auto-split long docs (default: true)
       :max-document-length - Chunk size in tokens (default: 256)

   Returns index-id (UUID).
   Emits :colbert/index-created event with full source data for regeneration."
  [ctx opts]
  (operations/create-index! ctx opts))

(defn load-index
  "Load an existing index by ID.

   Loads the index from disk into memory for searching.
   Returns the model alias for direct bridge operations."
  [ctx index-id]
  (let [index (read-models/get-index ctx index-id)]
    (when-not index
      (throw (ex-info "Index not found" {:index-id index-id})))
    (when (= :deleted (:status index))
      (throw (ex-info "Index has been deleted" {:index-id index-id})))

    (let [alias (str index-id)]
      (bridge/load-model! alias :index-path (:index-path index))
      alias)))

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

;; =============================================================================
;; Search Operations
;; =============================================================================

(defn search
  "Search indexed corpus using ColBERT late-interaction.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :query    - Search query string (required)
       :index-id - Index UUID (required)
       :k        - Number of results (default: 10)

   Returns:
     [{:content \"...\" :score 0.87 :rank 1 :document-id \"...\" :document-metadata {...}}]

   Emits :colbert/search-performed event for audit."
  [ctx opts]
  (operations/search ctx opts))

(defn rerank
  "Rerank documents in-memory (no index required).

   This encodes query and documents on-the-fly and scores them.
   Useful for reranking candidates from other retrieval methods.

   Args:
     ctx - Context map (event-store optional for audit)
     opts - Options map:
       :query     - Query string (required)
       :documents - Vector of document strings to rerank (required)
       :k         - Number of results (default: all documents)

   Returns:
     [{:content \"...\" :score 0.87 :rank 1}]"
  [ctx opts]
  (operations/rerank ctx opts))

;; =============================================================================
;; Score Normalization
;; =============================================================================

(defn normalize-colbert-score
  "Normalize ColBERT score to 0-1 range.

   ColBERT scores are typically in the range 0-40+ depending on query/document
   length and content overlap. This function normalizes them for RRF fusion
   with other retrieval signals (graph BFS, embeddings).

   Args:
     score - Raw ColBERT score (typically 0-40+)
     opts - Options map:
       :max-score - Maximum expected score for normalization (default: 40.0)
       :method - Normalization method: :linear, :sigmoid, :softmax (default: :linear)

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
  "Batch-search a ColBERT index for many queries in ONE round-trip, index loaded
   ONCE. Returns a vector of result-lists aligned to `:queries`.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :queries  - Vector of query strings (required)
       :index-id - Index UUID (required)
       :k        - Results per query (default: 10)"
  [ctx opts]
  (operations/search-batch ctx opts))

(defn search-for-rrf-batch
  "Batched `search-for-rrf`: ONE index load + ONE round-trip for all queries.
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

(defn hybrid-search
  "Combine ColBERT with existing ontology search via RRF.

   This function performs ColBERT search and returns results formatted
   for merging with other retrieval signals via RRF.

   Args:
     ctx - Context map
     opts - Options map:
       :query         - Search query (required)
       :index-id      - ColBERT index UUID (required)
       :k             - Number of results (default: 10)

   Returns results compatible with ontology/merge-batches."
  [ctx opts]
  (operations/hybrid-search ctx opts))

;; =============================================================================
;; Training Functions
;; =============================================================================

(defn prepare-training-data!
  "Prepare training data with optional hard negative mining.

   Raw data formats supported:
   - pairs: [[query positive] ...]
   - labeled-pairs: [[query passage label] ...] where label is 0/1
   - triplets: [[query positive negative] ...]

   Args:
     ctx - Context map
     opts - Options map:
       :raw-data                   - Training data (required)
       :data-out-path              - Path to write processed data (required)
       :all-documents              - Corpus for hard negative mining (optional)
       :mine-hard-negatives?       - Whether to mine negatives (default: true)
       :num-new-negatives          - Negatives per positive (default: 10)
       :hard-negative-minimum-rank - Skip top-N as too easy (default: 10)

   Returns:
     {:data-path \"...\" :num-triplets int}"
  [ctx {:keys [trainer-alias] :or {trainer-alias "default-trainer"} :as opts}]
  (bridge/prepare-training-data! trainer-alias
    (dissoc opts :trainer-alias)))

(defn train!
  "Train/fine-tune a ColBERT model.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :model-name     - Name for the new model (required)
       :base-model     - Model to fine-tune from (default: colbert-ir/colbertv2.0)
       :training-data-path - Path to prepared training data (required)
       :config         - Training config (see bridge/train! for options)

   Returns:
     Command result with :command/result containing {:training-id uuid :checkpoint-path \"...\"}

   Emits training lifecycle events via command processor."
  [ctx opts]
  (cp/process-command
    (assoc ctx :command (merge {:command/name :colbert/train
                                :command/id (random-uuid)
                                :command/timestamp (time/now)}
                               opts))))

;; =============================================================================
;; Index Regeneration
;; =============================================================================

(defn regenerate-index!
  "Regenerate a PLAID index from event store data.

   Use this if the PLAID files on disk are lost/corrupted.
   The event store contains full source documents.
   Routes through the command processor for proper CQRS event emission."
  [ctx index-id]
  (cp/process-command
    (assoc ctx :command {:command/name :colbert/regenerate-index
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :index-id index-id})))

;; =============================================================================
;; Query Helpers
;; =============================================================================

(defn get-index-info
  "Get index metadata from event store."
  [ctx index-id]
  (read-models/get-index ctx index-id))

(defn list-indexes
  "List all indexes."
  [ctx & {:keys [include-deleted] :or {include-deleted false}}]
  (read-models/list-indexes ctx :include-deleted include-deleted))

(defn get-search-history
  "Get search audit trail."
  [ctx & {:keys [index-id limit since]}]
  (read-models/get-search-history ctx
                                   :index-id index-id
                                   :limit limit
                                   :since since))

;; =============================================================================
;; Event Types Export
;; =============================================================================

(def colbert-event-types read-models/colbert-event-types)

;; =============================================================================
;; Bridge Lifecycle
;; =============================================================================

(defn stop-bridge!
  "Stop the Python bridge subprocess.

   Call this during application shutdown."
  []
  (bridge/stop-bridge!))

(defn ping
  "Health check for the Python bridge."
  []
  (bridge/ping))

(defn health-check
  "Deeper health check than `ping`: verifies the bridge AND that the
   ColBERT model+tokenizer dependencies are intact enough to build an
   index over a 2-document corpus.

   Catches the failure modes that a bare `(ping)` misses — `(ping)`
   only verifies the bridge subprocess started, but the ragatouille
   import chain has historically broken on package-version skews that
   only surface when create_index is actually called:

     - ragatouille 0.0.9 importing langchain.retrievers (broken on
       langchain >= 0.3 because that module moved)
     - colbert-ai 0.2.22 referencing HF_ColBERT.all_tied_weights_keys
       (removed in transformers 5.x)
     - fast-pytorch-kmeans needing psutil (undeclared dependency)

   The tiny in-memory build below exercises the same code path the
   bench runs through and surfaces those errors immediately. Total
   runtime: ~5-10s on a fresh JVM (cold ColBERT model load).

   Returns a map:
     {:status :ok    :ping {:status \"ok\"} :index-id <uuid>}
     {:status :fail  :stage :ping        :error \"...\"}
     {:status :fail  :stage :index-build :error \"...\"}

   Standalone — does NOT require a ctx with an event-store. Uses an
   in-memory event store so the create-index command can dispatch
   without bringing up the full Grain machinery."
  []
  (let [ping-result (try (bridge/ping) (catch Exception e {:error (.getMessage e)}))]
    (cond
      (:error ping-result)
      {:status :fail :stage :ping :error (:error ping-result)}

      (not= "ok" (:status ping-result))
      {:status :fail :stage :ping :error (str "ping returned: " (pr-str ping-result))}

      :else
      (try
        ;; Build a 2-document index against a minimal in-memory event-
        ;; store ctx. This exercises the model load + index materialization
        ;; path without depending on the bench setup.
        (let [es-ns (requiring-resolve 'ai.obney.grain.event-store-v3.interface/start)
              ps-ns (requiring-resolve 'ai.obney.grain.pubsub.interface/start)
              ps    (ps-ns {:type :core-async :topic-fn :event/type})
              es    (es-ns {:conn {:type :in-memory} :event-pubsub ps :logger nil})
              ctx   {:event-store es
                     :tenant-id   (random-uuid)
                     :event-pubsub ps}
              ;; Use a bigger corpus so faiss's k-means clusterer has
              ;; enough training points. ColBERT/PLAID defaults to 32
              ;; clusters; the corpus's token count must exceed that.
              ;; The 8 sentences below give ~50+ tokens once split.
              corpus ["The quick brown fox jumps over the lazy dog every morning."
                      "ColBERT enables late-interaction retrieval via per-token embeddings."
                      "Late-interaction beats single-vector encoding for nuanced queries."
                      "RAGatouille wraps ColBERT for plug-and-play index building."
                      "Vector databases store dense embeddings for similarity search."
                      "Schema-driven retrieval improves precision on structured tasks."
                      "The Python bridge subprocess loads the ColBERT model lazily."
                      "Faiss provides fast approximate nearest-neighbor search."]
              result (operations/create-index! ctx
                       {:collection corpus
                        :index-name "health-check-tiny"
                        :model-name "colbert-ir/colbertv2.0"
                        :split-documents? false
                        :max-document-length 256})]
          {:status :ok
           :ping ping-result
           :index-id (:index-id result)})
        (catch Exception e
          {:status :fail
           :stage :index-build
           :error (.getMessage e)})))))

;; =============================================================================
;; Tree Profile Indexing
;; =============================================================================

(defn tree-profile->document
  "Convert a tree profile to a searchable document string.

   Combines all textual fields from the profile into a single
   searchable representation optimized for ColBERT retrieval."
  [profile]
  (operations/tree-profile->document profile))

(defn index-tree-profiles!
  "Create ColBERT index from tree profiles.

   Indexes all tree self-descriptions for few-shot retrieval.
   Each tree profile becomes a document in the index.

   Args:
     ctx - Context map containing :event-store
     profiles - Seq of tree profile maps, or map of tree-id -> profile
     opts - Options map:
       :index-name - Name for the index (default: \"tree-profiles\")
       :model-name - ColBERT model (default: colbert-ir/colbertv2.0)

   Returns:
     Index ID (UUID)"
  [ctx profiles & {:keys [index-name model-name]
                    :or {index-name "tree-profiles"
                         model-name "colbert-ir/colbertv2.0"}}]
  (operations/index-tree-profiles! ctx profiles
    :index-name index-name :model-name model-name))

(defn search-similar-trees
  "Search for trees similar to a query description.

   Convenience wrapper around search for tree profile indexes.

   Args:
     ctx - Context map
     index-id - Tree profiles index UUID
     query - Natural language description of desired tree
     opts - Options map:
       :k - Number of results (default: 5)
       :min-score - Minimum normalized score threshold (default: 0.0)

   Returns:
     Vector of {:tree-id :score :name :metadata}"
  [ctx index-id query & {:keys [k min-score]
                          :or {k 5 min-score 0.0}}]
  (operations/search-similar-trees ctx index-id query
    :k k :min-score min-score))

;; =============================================================================
;; Ontology Concept Indexing
;; =============================================================================

(defn concept->document
  "Convert an ontology concept to a searchable document string.

   Combines all textual fields from the concept into a single
   searchable representation optimized for ColBERT retrieval."
  [concept]
  (operations/concept->document concept))

(defn index-concepts!
  "Create ColBERT index from ontology concepts.

   Similar to embed-concepts-batch for MiniLM but creates a ColBERT index
   for late-interaction retrieval. Uses concept URIs as document-ids,
   so search results can be directly used with ontology lookups.

   Args:
     ctx - Context map containing :event-store
     concepts - Collection of concept maps, each with :uri, :label, :description
     opts - Options map:
       :index-name - Name for the index (default: 'concepts-{scope}' or 'concepts')
       :model-name - ColBERT model (default: colbert-ir/colbertv2.0)
       :scope      - Used for default index-name if provided

   Returns:
     Index ID (UUID)"
  [ctx concepts & {:keys [index-name model-name scope]
                    :or {model-name "colbert-ir/colbertv2.0"}}]
  (operations/index-concepts! ctx concepts
    :index-name index-name :model-name model-name :scope scope))

(defn search-similar-concepts
  "Search for concepts similar to a query description.

   Convenience wrapper around search for concept indexes.
   Returns results with URIs that can be used directly with ontology lookups.

   Args:
     ctx - Context map
     index-id - Concepts index UUID
     query - Natural language description or failure feedback
     opts - Options map:
       :k - Number of results (default: 10)
       :min-score - Minimum normalized score threshold (default: 0.0)
       :scope - Optional scope filter (:failure, :success, :problem)

   Returns:
     Vector of {:uri :score :label :metadata}"
  [ctx index-id query & {:keys [k min-score scope]
                          :or {k 10 min-score 0.0}}]
  (operations/search-similar-concepts ctx index-id query
    :k k :min-score min-score :scope scope))

;; =============================================================================
;; Training Data Extraction
;; =============================================================================

(defn extract-training-pairs-from-traces
  "Extract query->output pairs from successful tree executions.

   This function queries the event store for execution traces and evaluation
   scores, filtering for high-quality outputs suitable for ColBERT training.
   Produces pairs of [input-text, output-text] that can be used with
   prepare-training-data!.

   Args:
     ctx - Context map containing :event-store
     opts - Options map:
       :sheet-id          - UUID of the sheet to extract from (required)
       :min-score         - Minimum evaluation score to include (default: 0.7)
       :since             - Only include traces after this timestamp (optional)
       :limit             - Maximum number of pairs to extract (default: 1000)
       :input-keys        - Blackboard keys to use as query text (default: all inputs)
       :output-keys       - Blackboard keys to use as positive text (default: all outputs)
       :format-fn         - Custom function to format input/output maps to strings
                            (default: JSON-encodes the map)

   Returns:
     {:pairs     [[query positive] ...]  - Training pairs for ColBERT
      :stats     {:total-traces int
                  :evaluated-traces int
                  :passing-traces int
                  :avg-score double}
      :trace-ids [uuid ...]              - IDs of included traces}"
  [ctx opts]
  (training-data-processor/extract-training-pairs-from-traces ctx opts))

;; =============================================================================
;; Cache Preloading (Warm Startup)
;; =============================================================================

(defn preload-index!
  "Preload a ColBERT index into Python subprocess memory.

   Call at startup to avoid cold-start latency on first search.
   The model stays resident in Python process memory.

   Args:
     ctx - Context map containing :event-store
     index-id - UUID of the index to preload

   Returns:
     {:preloaded true
      :index-id uuid
      :index-name string}
   Or nil if index not found."
  [ctx index-id]
  (when-let [index (read-models/get-index ctx index-id)]
    (try
      (bridge/load-model! (str index-id)
                          :index-path (:index-path index))
      {:preloaded true
       :index-id index-id
       :index-name (:index-name index)}
      (catch Exception e
        (println "[WARNING] Failed to preload ColBERT index:" (.getMessage e))
        nil))))

(defn preload-all-indexes!
  "Preload all active ColBERT indexes into Python subprocess memory.

   Call at startup to warm the ColBERT cache and avoid cold-start latency.
   This starts the Python subprocess if not already running and loads
   all active indexes into memory.

   Args:
     ctx - Context map containing :event-store

   Returns:
     {:preloaded-count int
      :indexes [{:index-id :index-name :preloaded bool} ...]
      :elapsed-ms int}"
  [ctx]
  (let [start (System/currentTimeMillis)
        indexes (read-models/list-indexes ctx)
        results (doall
                 (for [idx indexes]
                   (let [result (preload-index! ctx (:index-id idx))]
                     {:index-id (:index-id idx)
                      :index-name (:index-name idx)
                      :preloaded (some? result)})))
        elapsed (- (System/currentTimeMillis) start)]
    {:preloaded-count (count (filter :preloaded results))
     :indexes results
     :elapsed-ms elapsed}))
