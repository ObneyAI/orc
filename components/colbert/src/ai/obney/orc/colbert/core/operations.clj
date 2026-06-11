(ns ai.obney.orc.colbert.core.operations
  "Pure business logic for ColBERT search, indexing, and retrieval operations.

   Contains score normalization, hybrid search, tree profile indexing,
   and concept indexing logic. All functions are pure with respect to
   event emission — they call the Python bridge and return results,
   but never append events. Event emission is handled exclusively
   by defcommand handlers in commands.clj."
  (:require [ai.obney.orc.colbert.core.bridge :as bridge]
            [ai.obney.orc.colbert.core.read-models :as read-models]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

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
  [score & {:keys [max-score method]
            :or {max-score 40.0 method :linear}}]
  (case method
    :linear
    (min 1.0 (max 0.0 (/ (double score) max-score)))

    :sigmoid
    ;; Sigmoid normalization: centers around half max-score
    (let [x (- (/ (double score) max-score) 0.5)]
      (/ 1.0 (+ 1.0 (Math/exp (* -10.0 x)))))

    ;; Default to linear
    (min 1.0 (max 0.0 (/ (double score) max-score)))))

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
  [results & {:keys [min-score-threshold] :or {min-score-threshold 0.0}}]
  (if (empty? results)
    []
    (let [max-score (apply max (map :score results))
          ;; Prevent division by zero
          normalizer (if (pos? max-score) max-score 1.0)]
      (->> results
           (map (fn [r]
                  (assoc r :score (/ (double (:score r)) normalizer)
                         :raw-score (:score r))))
           (filter #(>= (:score %) min-score-threshold))
           vec))))

;; =============================================================================
;; Index Creation
;; =============================================================================

(defn create-index!
  "Create a ColBERT index from documents via the Python bridge.

   Pure bridge call — does NOT emit events. Event emission is handled
   by the defcommand :colbert/create-index in commands.clj.

   Args:
     ctx - Context map (unused currently, kept for signature compatibility)
     opts - Options map:
       :collection         - Vector of document strings (required)
       :index-name         - Name for the index (required)
       :document-ids       - Vector of unique IDs (auto-generated if nil)
       :document-metadatas - Vector of metadata maps (optional)
       :model-name         - ColBERT model (default: colbert-ir/colbertv2.0)
       :split-documents?   - Auto-split long docs (default: true)
       :max-document-length - Chunk size in tokens (default: 256)

   Returns map with :index-id, :index-path, :num-passages, :duration-ms,
   :document-ids, and config details."
  [ctx {:keys [collection document-ids document-metadatas index-name
               model-name split-documents? max-document-length]
        :or {model-name "colbert-ir/colbertv2.0"
             split-documents? true
             max-document-length 256}
        :as opts}]
  (let [index-id (random-uuid)
        alias (str index-id)
        ;; Coalesce explicit nils. The :colbert/create-index command forwards
        ;; omitted optional params as explicit nil, which bypasses the :or defaults
        ;; above (:or only fires on an ABSENT key, not a present nil). Without this,
        ;; the emitted :colbert/index-created event carries nil :model-name / :config
        ;; values and fails its schema. (Preserve an explicit false for
        ;; split-documents?.)
        model-name (or model-name "colbert-ir/colbertv2.0")
        split-documents? (if (nil? split-documents?) true split-documents?)
        max-document-length (or max-document-length 256)
        ;; Generate document IDs if not provided. The previous form was
        ;;   (mapv #(str (random-uuid)) (range (count collection)))
        ;; which compiled to a 0-arg fn called by mapv with 1 arg — an
        ;; arity error any time :document-ids was nil. In production the
        ;; colbert defcommand always supplied :document-ids so the
        ;; default branch was dead code; standalone callers (like
        ;; colbert/health-check) hit the bug. repeatedly with a 0-arg fn
        ;; matches mapv's intent without the throwaway arg.
        document-ids (or document-ids
                         (into [] (repeatedly (count collection)
                                              #(str (random-uuid)))))
        start-time (System/currentTimeMillis)]

    (mu/log ::creating-index :index-id index-id :index-name index-name
            :document-count (count collection))

    ;; Load model and create index via bridge
    (bridge/load-model! alias :model-name model-name)
    (let [result (bridge/create-index! alias
                   {:collection collection
                    :document-ids document-ids
                    :document-metadatas document-metadatas
                    :index-name index-name
                    :split-documents? split-documents?
                    :max-document-length max-document-length})
          duration-ms (- (System/currentTimeMillis) start-time)]

      (mu/log ::index-created :index-id index-id :duration-ms duration-ms
              :passages (:num_passages result))

      ;; Return all data needed by the command handler to emit the event
      {:index-id index-id
       :index-path (:index_path result)
       :num-passages (or (:num_passages result) (count collection))
       :duration-ms duration-ms
       :document-ids document-ids
       :document-metadatas document-metadatas
       :document-count (count collection)
       :model-name model-name
       :index-name index-name
       :config {:split-documents? split-documents?
                :max-document-length max-document-length
                :use-faiss? false}})))

;; =============================================================================
;; Search Operations
;; =============================================================================

(defn search
  "Search indexed corpus using ColBERT late-interaction.

   Pure bridge call — does NOT emit events. Event emission is handled
   by the defcommand :colbert/search in commands.clj.

   Args:
     ctx - Context map (used for read-model lookup)
     opts - Options map:
       :query    - Search query string (required)
       :index-id - Index UUID (required)
       :k        - Number of results (default: 10)

   Returns:
     [{:content \"...\" :score 0.87 :rank 1 :document-id \"...\" :document-metadata {...}}]"
  [ctx {:keys [query index-id k]
        :or {k 10}}]
  (let [index (read-models/get-index ctx index-id)]
    (when-not index
      (throw (ex-info "Index not found" {:index-id index-id})))
    (when (= :deleted (:status index))
      (throw (ex-info "Index has been deleted" {:index-id index-id})))

    (let [alias (str index-id)]

      ;; Ensure model is loaded
      (bridge/load-model! alias :index-path (:index-path index))

      (bridge/search alias {:query query :k k}))))

(defn rerank
  "Rerank documents in-memory (no index required).

   Pure bridge call — does NOT emit events. Event emission is handled
   by the defcommand :colbert/rerank in commands.clj.

   Args:
     ctx - Context map (unused currently, kept for signature compatibility)
     opts - Options map:
       :query     - Query string (required)
       :documents - Vector of document strings to rerank (required)
       :k         - Number of results (default: all documents)

   Returns:
     [{:content \"...\" :score 0.87 :rank 1}]"
  [ctx {:keys [query documents k]}]
  (let [k (or k (count documents))
        alias "rerank-default"]

    ;; Ensure default model is loaded
    (try (bridge/load-model! alias)
         (catch Exception _))

    (bridge/rerank alias {:query query :documents documents :k k})))

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
  [ctx {:keys [query index-id k normalize? weight]
        :or {k 20 normalize? true weight 1.0}}]
  (let [results (search ctx {:query query :index-id index-id :k k})
        normalized (if normalize?
                     (normalize-result-scores results)
                     results)]
    ;; The bridge keywordizes the Python response as-is (:key-fn keyword), so the
    ;; per-result key is :document_id (underscore), NOT :document-id. Read either so
    ;; the RRF :uri actually resolves back to the concept URI we indexed under.
    (mapv (fn [{:keys [score] :as r}]
            {:uri (or (:document_id r) (:document-id r))
             :score (* weight (double score))})
          normalized)))

(defn search-batch
  "Batch-search a ColBERT index for MANY queries in ONE bridge round-trip, with the
   index loaded ONCE (not once-per-query). Returns a vector of result-lists aligned
   to `queries`, each list shaped like `search`'s output.

   Args:
     ctx - Context map (used for read-model lookup)
     opts - Options map:
       :queries  - Vector of query strings (required)
       :index-id - Index UUID (required)
       :k        - Number of results per query (default: 10)"
  [ctx {:keys [queries index-id k]
        :or {k 10}}]
  (let [index (read-models/get-index ctx index-id)]
    (when-not index
      (throw (ex-info "Index not found" {:index-id index-id})))
    (when (= :deleted (:status index))
      (throw (ex-info "Index has been deleted" {:index-id index-id})))
    (let [alias (str index-id)]
      ;; Load the index ONCE; the bridge then runs every query against the resident
      ;; model in a single round-trip (no per-query load, no per-query reload).
      (bridge/load-model! alias :index-path (:index-path index))
      (bridge/search-batch alias {:queries (vec queries) :k k}))))

(defn search-for-rrf-batch
  "Batched `search-for-rrf`: ONE index load + ONE bridge round-trip for ALL queries.
   Returns a vector aligned to `queries`, each element a vector of {:uri :score}
   ready for RRF fusion. This is the batched integration point for ontology
   hybrid-search over a whole transcript — it collapses N per-line ColBERT
   round-trips into one.

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
  (->> (search-batch ctx {:queries queries :index-id index-id :k k})
       (mapv (fn [results]
               (let [normalized (if normalize?
                                  (normalize-result-scores results)
                                  results)]
                 ;; Same :document_id (underscore) ⇒ :uri mapping as search-for-rrf.
                 (mapv (fn [{:keys [score] :as r}]
                         {:uri (or (:document_id r) (:document-id r))
                          :score (* weight (double score))})
                       normalized))))))

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
  [ctx {:keys [query index-id k]
        :or {k 10}}]
  (let [results (search ctx {:query query :index-id index-id :k k})]
    ;; Format for RRF: {:id :score}
    (mapv (fn [{:keys [document-id score]}]
            {:id document-id :score score})
          results)))

;; =============================================================================
;; Tree Profile Indexing
;; =============================================================================

(defn tree-profile->document
  "Convert a tree profile to a searchable document string.

   Combines all textual fields from the profile into a single
   searchable representation optimized for ColBERT retrieval.

   Args:
     profile - Tree profile map with keys:
       :name - Tree name
       :objectives - Vector of objective strings
       :capabilities - Vector of capability strings
       :problem-types - Vector of problem type URIs
       :strengths - Vector of {:pattern :confidence} maps
       :weaknesses - Vector of {:failure :frequency} maps

   Returns:
     Concatenated string suitable for ColBERT indexing"
  [{:keys [name objectives capabilities problem-types strengths weaknesses
           solves description]}]
  (let [sections
        [(when name (str "Tree: " name))

         (when description
           (str "Description: " description))

         (when (seq objectives)
           (str "Objectives: " (str/join ", " objectives)))

         (when (seq capabilities)
           (str "Capabilities: " (str/join ", " capabilities)))

         (when (seq problem-types)
           (str "Problem Types: " (str/join ", " problem-types)))

         (when (seq solves)
           (str "Solves: "
                (str/join "; "
                  (map (fn [{:keys [problem-uri success-rate]}]
                         (str problem-uri " (success: " (when success-rate
                                                          (format "%.0f%%" (* 100 success-rate))) ")"))
                       solves))))

         (when (seq strengths)
           (str "Strengths: "
                (str/join ", "
                  (map (fn [{:keys [pattern confidence]}]
                         (str pattern
                              (when confidence
                                (str " (" (format "%.0f%%" (* 100 confidence)) ")"))))
                       strengths))))

         (when (seq weaknesses)
           (str "Weaknesses: "
                (str/join ", "
                  (map (fn [{:keys [failure frequency]}]
                         (str failure
                              (when frequency
                                (str " (" (format "%.0f%%" (* 100 frequency)) ")"))))
                       weaknesses))))]]

    (->> sections
         (remove nil?)
         (str/join "\n"))))

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
     Index ID (UUID)

   Example:
     (index-tree-profiles! ctx
       {\"tree-1\" {:name \"Lead Qualifier\" :objectives [...]}
        \"tree-2\" {:name \"Email Generator\" :objectives [...]}})

     ;; Then search:
     (search ctx {:query \"lead scoring classification\" :index-id index-id})"
  [ctx profiles & {:keys [index-name model-name]
                    :or {index-name "tree-profiles"
                         model-name "colbert-ir/colbertv2.0"}}]
  (let [;; Normalize to vector of [tree-id profile] pairs
        profile-pairs (if (map? profiles)
                        (vec profiles)
                        (map-indexed (fn [i p]
                                       [(or (:tree-id p) (str i)) p])
                                     profiles))

        ;; Build collections
        documents (mapv (comp tree-profile->document second) profile-pairs)
        doc-ids (mapv (comp str first) profile-pairs)
        metadatas (mapv (fn [[tree-id profile]]
                          {:tree-id (str tree-id)
                           :name (:name profile)
                           :problem-types (vec (:problem-types profile))})
                        profile-pairs)]

    (mu/log ::indexing-tree-profiles :profile-count (count documents)
            :index-name index-name)

    (create-index! ctx
      {:collection documents
       :document-ids doc-ids
       :document-metadatas metadatas
       :index-name index-name
       :model-name model-name
       :split-documents? false})))  ;; Profiles are already coherent units

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
  (let [results (search ctx {:query query :index-id index-id :k k})
        normalized (normalize-result-scores results :min-score-threshold min-score)]
    (mapv (fn [{:keys [document-id score document-metadata]}]
            {:tree-id document-id
             :score score
             :name (get document-metadata :name)
             :metadata document-metadata})
          normalized)))

;; =============================================================================
;; Ontology Concept Indexing
;; =============================================================================

(defn concept->document
  "Convert an ontology concept to a searchable document string.

   Combines all textual fields from the concept into a single
   searchable representation optimized for ColBERT retrieval.
   Similar to embedding/concept->embedding-text but for ColBERT.

   Args:
     concept - Concept map with keys:
       :uri         - Unique identifier (e.g., 'failure:Hallucination')
       :label       - Human-readable name
       :description - Detailed description
       :scope       - :failure, :success, or :problem
       :broader     - Parent concept URIs (optional)
       :indicators  - Vector of indicator strings (optional)
       :triggers    - Vector of trigger strings (optional)

   Returns:
     Concatenated string suitable for ColBERT indexing"
  [{:keys [uri label description scope broader indicators triggers]}]
  (let [sections
        [(when label
           (str "Concept: " label))

         (when description
           (str "Description: " description))

         (when scope
           (str "Scope: " (name scope)))

         (when (seq broader)
           (str "Broader: " (str/join ", " broader)))

         (when (seq indicators)
           (str "Indicators: " (str/join ", " indicators)))

         (when (seq triggers)
           (str "Triggers: " (str/join ", " triggers)))]]

    (->> sections
         (remove nil?)
         (str/join "\n"))))

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
     Index ID (UUID)

   Example:
     ;; Index failure concepts from static ontology
     (require '[ai.obney.orc.ontology.core.static-ontology :as static])
     (def failure-index-id
       (colbert/index-concepts! ctx
         (static/get-concepts-by-scope :failure)
         {:index-name \"failure-concepts\"}))

     ;; Search for relevant failure types
     (colbert/search ctx
       {:query \"output contradicts input evidence\"
        :index-id failure-index-id
        :k 5})
     ;; => [{:document-id \"failure:Hallucination\" :score 0.87 ...}]"
  [ctx concepts & {:keys [index-name model-name scope]
                    :or {model-name "colbert-ir/colbertv2.0"}}]
  (let [;; Determine index name
        index-name (or index-name
                       (if scope
                         (str "concepts-" (name scope))
                         "concepts"))

        ;; Filter out concepts without URIs
        valid-concepts (filter :uri concepts)

        ;; Build collections - use URI as document-id for direct ontology lookup
        documents (mapv concept->document valid-concepts)
        doc-ids (mapv :uri valid-concepts)
        metadatas (mapv (fn [{:keys [uri label scope broader]}]
                          {:uri uri
                           :label label
                           :scope (when scope (name scope))
                           :broader (vec broader)})
                        valid-concepts)]

    (mu/log ::indexing-concepts
            :concept-count (count valid-concepts)
            :index-name index-name
            :scope scope)

    (create-index! ctx
      {:collection documents
       :document-ids doc-ids
       :document-metadatas metadatas
       :index-name index-name
       :model-name model-name
       :split-documents? false})))  ;; Concepts are already coherent units

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
  (let [results (search ctx {:query query :index-id index-id :k k})
        normalized (normalize-result-scores results :min-score-threshold min-score)
        ;; Optionally filter by scope
        filtered (if scope
                   (filter #(= (name scope) (get-in % [:document-metadata :scope]))
                           normalized)
                   normalized)]
    (mapv (fn [{:keys [document-id score document-metadata]}]
            {:uri document-id
             :score score
             :label (get document-metadata :label)
             :metadata document-metadata})
          filtered)))
