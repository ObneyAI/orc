(ns ai.obney.orc.colbert.core.operations
  "Pure business logic for ColBERT search, indexing, and retrieval operations.

   Contains index creation, search (single + batch), rerank, score
   normalization, and the RRF integration helpers. All functions are pure
   with respect to event emission — they run the pure-JVM encoder/MaxSim
   pipeline (ADR 0002, no Python process) and return results, but never
   append events. Event emission is handled exclusively by defcommand
   handlers in commands.clj."
  (:require [ai.obney.orc.colbert.core.corpus :as corpus]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.index-store :as index-store]
            [ai.obney.orc.colbert.core.maxsim :as maxsim]
            [ai.obney.orc.colbert.core.model-store :as model-store]
            [ai.obney.orc.colbert.core.read-models :as read-models]
            [clojure.java.io :as io]
            [com.brunobonacci.mulog :as mu]))

;; =============================================================================
;; Score Normalization
;; =============================================================================

(defn maxsim-ceiling
  "The THEORETICAL MaxSim bound — which IS `maximum_query_tokens`, never a
   constant of nature.

   Every query token row is unit-normed inside the ONNX graph, so
   MaxSim = sum over maximum_query_tokens rows of (max dot <= 1.0)
          <= maximum_query_tokens.

   It used to be written down as the literal 32.0 because that was the
   checkpoint's own query_maxlen. CC-17 made the limit configuration and moved
   the shipped default to 464, so the ceiling MOVED WITH IT; a frozen 32.0
   here would clamp every real score to 1.0. Derived, not hard-coded.

   ⚠ THE NO-ARG FORM IS THE PROCESS DEFAULT ONLY (CC-25). It answers 'what
   limit would an unconfigured encoding use in THIS process', which is the
   right question ONLY when no particular encoding is in hand. It is NOT the
   ceiling of any given search or rerank: CC-17 made the limit per-index
   configuration (`IndexConfiguration.maximum_query_tokens`, threaded into
   `encoder/encode-query` by `search` / `search-batch`), so an index built
   above the process default yields raw scores ABOVE this value — every one
   of which `normalize-colbert-score` then clamps to 1.0, destroying relative
   order and breaking `invariant.BoundedNormalization`. Below the default it
   silently compresses the scale instead (an index at 96 against a default of
   464 tops out at 0.207).

   To normalize scores that an actual encoding produced, use
   `results-maxsim-ceiling` / `normalize-results-to-ceiling`, which read the
   limit off the truncation report `attach-truncation` stamped on the result
   collection — the limit that encoding REALLY ran under."
  (^double [] (maxsim-ceiling nil))
  (^double [maximum-query-tokens]
   (double (or maximum-query-tokens
               (encoder/configured-maximum-query-tokens)
               (get-in (encoder/get-encoder (model-store/resolve-model-dir))
                       [:consts :query-maxlen])))))

(defn results-maxsim-ceiling
  "The MaxSim ceiling OF THE ENCODING THAT ACTUALLY PRODUCED `results` — i.e.
   `maximum_query_tokens` as reported by that very encoding, not whatever this
   process happens to default to (CC-25).

   `search`, `search-batch` and `rerank` all run `attach-truncation`, which
   stamps the truncation report (`:maximum-query-tokens` among its fields) on
   the returned collection as metadata. That number IS the row count the
   encoder built, and therefore the theoretical MaxSim bound for every score
   in the collection — the two can never disagree because they come from the
   same encode call.

   Falls back to the process default when the collection carries no report
   (a hand-built vector, or a collection whose metadata a transformation
   dropped). That fallback is the exact condition this function exists to
   avoid, so it is recorded rather than taken silently."
  ^double [results]
  (if-let [limit (get-in (meta results) [:query-truncation :maximum-query-tokens])]
    (maxsim-ceiling limit)
    (do (mu/log ::maxsim-ceiling-from-process-default
                :reason :no-truncation-report-on-results
                :result-count (count results))
        (maxsim-ceiling))))

(defn normalize-colbert-score
  "Normalize ColBERT score to 0-1 range.

   The default ceiling is `maxsim-ceiling` — the configured
   `IndexConfiguration.maximum_query_tokens`, which is exactly the number of
   unit-normed query rows the encoder builds (ADR 0002 + CC-17). It was the
   literal 32.0 while the limit was the checkpoint's query_maxlen 32; the old
   40.0 default belonged to the colbertv2 Python-bridge era.

   NB (P-0 findings, re-confirmed by the CC-17 cost measurement): MASK query
   expansion gives even unrelated pairs a very high floor (~0.94 of ceiling at
   32 rows, ~0.98 at 464), so a FIXED-ceiling linear normalization compresses
   everything into a narrow band — fine for RRF-style rank fusion, but NOT
   enough dynamic range for score-contrast consumers. Those should normalize
   RELATIVE to the scores in the same call (see normalize-result-scores, and
   the domain-penalty :batch-relative method on the ontology side).

   Args:
     score - Raw ColBERT score (typically ~[0, maximum_query_tokens])
     opts - Options map:
       :max-score - Maximum expected score for normalization (default:
                    maxsim-ceiling; an explicit nil also falls back to it)
       :method - Normalization method: :linear, :sigmoid (default: :linear)

   Returns:
     Normalized score in [0, 1] range"
  [score & {:keys [max-score method] :or {method :linear}}]
  (let [max-score (double (or max-score (maxsim-ceiling)))]
    (case method
      :linear
      (min 1.0 (max 0.0 (/ (double score) max-score)))

      :sigmoid
      ;; Sigmoid normalization: centers around half max-score
      (let [x (- (/ (double score) max-score) 0.5)]
        (/ 1.0 (+ 1.0 (Math/exp (* -10.0 x)))))

      ;; Default to linear
      (min 1.0 (max 0.0 (/ (double score) max-score))))))

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

(defn normalize-results-to-ceiling
  "FIXED-CEILING normalization of a search/rerank result collection against the
   ceiling OF THE ENCODING THAT PRODUCED IT (CC-25) — the `normalize_results`
   form to reach for when scores must stay comparable ACROSS calls, where
   `normalize-result-scores`'s batch-relative rescaling would make every call's
   top result 1.0.

   The ceiling comes from `results-maxsim-ceiling`, i.e. the truncation report
   the encode call stamped on the collection, so a per-index
   `maximum_query_tokens` can never disagree with the number the scores are
   divided by. `invariant.BoundedNormalization` therefore holds for ANY legal
   index configuration: scores stay in [0,1] and no two of them collapse
   together at the top of the scale.

   The truncation metadata is carried through, exactly as `search-for-rrf`
   carries it — a normalized collection is still an answer to a query that may
   have been cut.

   Args:
     results - Vector of result maps with :score key
     opts - Options map (NormalizationOptions):
       :max-score - Explicit ceiling override; nil (default) means the
                    collection's OWN encoding ceiling
       :method    - :linear (default) or :sigmoid

   Returns:
     Results with :score normalized into [0,1] and the raw value kept on
     :raw-score (the normalize-result-scores idiom)."
  [results & {:keys [max-score method] :or {method :linear}}]
  (if (empty? results)
    []
    (let [ceiling (double (or max-score (results-maxsim-ceiling results)))]
      (with-meta
        (mapv (fn [r]
                (assoc r
                       :score (normalize-colbert-score (:score r)
                                                       :max-score ceiling
                                                       :method method)
                       :raw-score (:score r)))
              results)
        (meta results)))))

;; =============================================================================
;; Index Creation
;; =============================================================================

(def index-root-property
  "System property overriding the index root directory."
  "colbert.index.root")

(def default-index-root
  "Default index root, relative to cwd."
  ".orc-colbert-indexes")

(defn- index-root
  ^java.io.File []
  (io/file (or (System/getProperty index-root-property) default-index-root)))

(defn create-index!
  "Create a ColBERT index artifact on disk from documents — pure JVM (ADR
   0002), no Python process: resolve the encoder, split the collection into
   passages by token count (corpus/split-collection, overlap parity rule),
   encode every passage (encoder/encode-doc), and write the versioned index
   artifact (index-store/write-index!) under
   `<index-root>/<index-name>/` (index root defaults to .orc-colbert-indexes
   relative to cwd; override via -Dcolbert.index.root).

   Does NOT emit events. Event emission is handled by the defcommand
   :colbert/create-index in commands.clj, which consumes this exact return
   map.

   Args:
     ctx - Context map (unused currently, kept for signature compatibility)
     opts - Options map:
       :collection         - Vector of document strings (required)
       :index-name         - Name for the index (required)
       :document-ids       - Vector of unique IDs (auto-generated if nil)
       :document-metadatas - Vector of metadata maps (optional)
       :model-name         - Recorded in the event (default:
                             model-store/checkpoint — the encoder actually
                             used; the artifact records the real checkpoint
                             regardless)
       :split-documents?   - Auto-split long docs (default: true)
       :max-document-length - Chunk size in tokens (default: 256; must be
                             <= the encoder's doc-maxlen - 3)
       :maximum-query-tokens - IndexConfiguration.maximum_query_tokens: the
                             per-query row count searches of this index build,
                             and therefore its MaxSim ceiling. Default: the
                             configured default (encoder/
                             resolve-maximum-query-tokens). Validated the same
                             way max-document-length is — positive, and within
                             the encoder's max_position_embeddings.

   Returns map with :index-id, :index-path, :num-passages, :duration-ms,
   :document-ids, :document-metadatas, :document-count, :model-name,
   :index-name, and :config."
  [ctx {:keys [collection document-ids document-metadatas index-name
               model-name split-documents? max-document-length
               maximum-query-tokens]
        :or {model-name model-store/checkpoint
             split-documents? true
             max-document-length 256}
        :as opts}]
  (let [index-id (random-uuid)
        ;; Coalesce explicit nils. The :colbert/create-index command forwards
        ;; omitted optional params as explicit nil, which bypasses the :or defaults
        ;; above (:or only fires on an ABSENT key, not a present nil). Without this,
        ;; the emitted :colbert/index-created event carries nil :model-name / :config
        ;; values and fails its schema. (Preserve an explicit false for
        ;; split-documents?.)
        model-name (or model-name model-store/checkpoint)
        split-documents? (if (nil? split-documents?) true split-documents?)
        max-document-length (or max-document-length 256)
        ;; Generate document IDs if not provided. The previous form was
        ;;   (mapv #(str (random-uuid)) (range (count collection)))
        ;; which compiled to a 0-arg fn called by mapv with 1 arg — an
        ;; arity error any time :document-ids was nil. In production the
        ;; colbert defcommand always supplied :document-ids so the
        ;; default branch was dead code; standalone callers hit the bug.
        ;; repeatedly with a 0-arg fn matches mapv's intent without the
        ;; throwaway arg.
        document-ids (or document-ids
                         (into [] (repeatedly (count collection)
                                              #(str (random-uuid)))))
        start-time (System/currentTimeMillis)]

    (mu/log ::creating-index :index-id index-id :index-name index-name
            :document-count (count collection))

    (let [enc (encoder/get-encoder (model-store/resolve-model-dir))
          ;; Resolve + VALIDATE the query limit at index-creation time, so an
          ;; unusable IndexConfiguration is rejected here rather than at every
          ;; later search (mirrors corpus/validate-chunk-size!).
          maximum-query-tokens (encoder/validate-maximum-query-tokens!
                                enc (encoder/resolve-maximum-query-tokens
                                     enc maximum-query-tokens))
          passages (corpus/split-collection enc
                     {:collection collection
                      :document-ids document-ids
                      :document-metadatas document-metadatas
                      :split-documents? split-documents?
                      :max-document-length max-document-length})
          encoded (mapv (fn [{:keys [text] :as passage}]
                          (let [{:keys [ids rows]} (encoder/encode-doc enc text)]
                            (assoc passage :token-ids ids :rows rows)))
                        passages)
          ;; dim observed from the encoder's own output (96 for this
          ;; checkpoint); an empty collection writes an empty artifact
          dim (if-let [^floats row (first (:rows (first encoded)))]
                (alength row)
                96)
          index-dir (io/file (index-root) index-name)
          _ (index-store/write-index! index-dir
              {:checkpoint model-store/checkpoint
               :dim dim
               :passages encoded
               :document-metadatas (when document-metadatas
                                     (zipmap document-ids document-metadatas))})
          duration-ms (- (System/currentTimeMillis) start-time)]

      (mu/log ::index-created :index-id index-id :duration-ms duration-ms
              :passages (count encoded))

      ;; Return all data needed by the command handler to emit the event
      {:index-id index-id
       :index-path (.getAbsolutePath index-dir)
       :num-passages (count encoded)
       :duration-ms duration-ms
       :document-ids document-ids
       :document-metadatas document-metadatas
       :document-count (count collection)
       :model-name model-name
       :index-name index-name
       :config {:split-documents? split-documents?
                :max-document-length max-document-length
                :maximum-query-tokens maximum-query-tokens
                :use-faiss? false}})))

;; =============================================================================
;; Query truncation (specs/colbert.allium — OverlongQueriesTruncateVisibly)
;; =============================================================================

(defn truncation-report
  "The caller-visible truncation fields of an encoded query."
  [encoded]
  {:query-token-count (:query-token-count encoded)
   :maximum-query-tokens (:maximum-query-tokens encoded)
   :query-truncated? (:truncated? encoded)
   :discarded-token-count (:discarded-token-count encoded)})

(defn attach-truncation
  "Stamp the truncation report on a RESULT COLLECTION as Clojure metadata
   (`:query-truncation`).

   The result shape is a frozen contract (rerank-contract-test pins the exact
   key set of every entry), and the ontology hot path calls
   operations/search / rerank DIRECTLY rather than through the Grain command,
   so the audit event is not on that path. Metadata makes the signal reachable
   everywhere without changing a single value: `=`, count, seq, and every
   downstream map/filter over the entries are untouched."
  [encoded results]
  (vary-meta results assoc :query-truncation (truncation-report encoded)))

(defn query-truncation
  "The truncation report for `query` under the applicable limit — the caller-
   visible signal for `invariant.OverlongQueriesTruncateVisibly`.

   Returns {:query-token-count :maximum-query-tokens :query-truncated?
            :discarded-token-count}. Tokenizer-only (no ONNX inference), so it
   is cheap enough for every search/rerank audit.

   opts:
     :maximum-query-tokens — the limit to measure against (defaults to the
                             configured default)."
  [_ctx {:keys [query maximum-query-tokens]}]
  (let [enc (encoder/get-encoder (model-store/resolve-model-dir))
        built (encoder/build-query-ids
               enc query {:maximum-query-tokens maximum-query-tokens})]
    (truncation-report built)))

(defn index-maximum-query-tokens
  "`IndexConfiguration.maximum_query_tokens` for an index read-model row, or
   nil when the index predates the field (legacy artifacts fall back to the
   configured default — never to a silent hard-coded 32)."
  [index]
  (get-in index [:config :maximum-query-tokens]))

(defn search-query-truncation
  "The truncation report a SEARCH of `index-id` would produce — measured
   against THAT INDEX'S OWN configured maximum_query_tokens, so the audit and
   the encoding can never disagree about the limit."
  [ctx index-id query]
  (let [index (read-models/get-index ctx index-id)]
    (query-truncation ctx {:query query
                           :maximum-query-tokens (index-maximum-query-tokens index)})))

;; =============================================================================
;; Search Operations
;; =============================================================================

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
  [ctx {:keys [query index-id k]
        :or {k 10}}]
  (let [index (read-models/get-index ctx index-id)]
    (when-not index
      (throw (ex-info "Index not found" {:index-id index-id})))
    (when (= :deleted (:status index))
      (throw (ex-info "Index has been deleted" {:index-id index-id})))

    (let [k (or k 10)
          artifact (index-store/load-index (:index-path index))
          enc (encoder/get-encoder (model-store/resolve-model-dir))
          skiplist (get-in enc [:consts :skiplist])
          ;; The index's OWN IndexConfiguration governs its queries.
          q-opts {:maximum-query-tokens (index-maximum-query-tokens index)}
          encoded (encoder/encode-query enc query q-opts)
          q-rows (:rows encoded)
          document-metadatas (:document-metadatas artifact)]
      (->> (:passages artifact)
           (map (fn [{:keys [document-id text token-ids rows]}]
                  {:document-id document-id
                   :text text
                   :score (maxsim/max-sim q-rows rows token-ids skiplist)}))
           (group-by :document-id)
           ;; document score = MAX passage score; :content = that passage
           (map (fn [[_ passages]] (apply max-key :score passages)))
           (sort-by :score >)
           (take k)
           (map-indexed
            (fn [i {:keys [document-id text score]}]
              {:content text
               :score score
               :rank (inc i)
               :document_id document-id
               ;; {} when no metadata was indexed — exactly what the bridge
               ;; returned (Python r.get(\"document_metadata\", {}))
               :document_metadata (get document-metadatas document-id {})}))
           vec
           (attach-truncation encoded)))))

(defn rerank
  "Rerank documents in-memory (no index required) — pure-JVM ColBERT signal
   (ADR 0002): encode the query and each document with the JVM encoder
   (DJL OnnxRuntime, answerai-colbert-small-v1 fp32), score with exact MaxSim
   (zero-not-drop punctuation semantics), sort descending, take k. No Python
   process, no index artifact.

   Does NOT emit events. Event emission is handled by the defcommand
   :colbert/rerank in commands.clj.

   Args:
     ctx - Context map (unused currently, kept for signature compatibility)
     opts - Options map:
       :query     - Query string (required)
       :documents - Vector of document strings to rerank (required)
       :k         - Number of results (default: all documents)

   Returns:
     [{:content \"...\" :score 0.87 :rank 1}]  (1-indexed rank, descending score)"
  [_ctx {:keys [query documents k maximum-query-tokens]}]
  (let [k (or k (count documents))
        enc (encoder/get-encoder (model-store/resolve-model-dir))
        skiplist (get-in enc [:consts :skiplist])
        encoded (encoder/encode-query
                 enc query {:maximum-query-tokens maximum-query-tokens})
        q-rows (:rows encoded)]
    (->> documents
         (mapv (fn [doc]
                 (let [{:keys [ids rows]} (encoder/encode-doc enc doc)]
                   {:content doc
                    :score (maxsim/max-sim q-rows rows ids skiplist)})))
         (sort-by :score >)
         (take k)
         (map-indexed (fn [i result] (assoc result :rank (inc i))))
         vec
         (attach-truncation encoded))))

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
                     results)
        ;; Carry the truncation report through the RRF adapter — this is the
        ;; ontology hot path's actual entry point, and the report is the only
        ;; signal it gets that the query was cut (no Grain command here).
        carry #(with-meta % (meta results))]
    ;; The bridge keywordizes the Python response as-is (:key-fn keyword), so the
    ;; per-result key is :document_id (underscore), NOT :document-id. Read either so
    ;; the RRF :uri actually resolves back to the concept URI we indexed under.
    (carry (mapv (fn [{:keys [score] :as r}]
                   {:uri (or (:document_id r) (:document-id r))
                    :score (* weight (double score))})
                 normalized))))

(defn search-batch
  "Batch-search a ColBERT index for MANY queries with the artifact loaded ONCE
   (not once-per-query) — pure JVM (ADR 0002), no Python process. The one-load
   property comes from index-store/load-index's per-canonical-path in-memory
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
  [ctx {:keys [queries index-id k]
        :or {k 10}}]
  (let [index (read-models/get-index ctx index-id)]
    (when-not index
      (throw (ex-info "Index not found" {:index-id index-id})))
    (when (= :deleted (:status index))
      (throw (ex-info "Index has been deleted" {:index-id index-id})))
    (let [k (or k 10)
          artifact (index-store/load-index (:index-path index))
          enc (encoder/get-encoder (model-store/resolve-model-dir))
          skiplist (get-in enc [:consts :skiplist])
          q-opts {:maximum-query-tokens (index-maximum-query-tokens index)}
          document-metadatas (:document-metadatas artifact)]
      (mapv
       (fn [query]
         (let [encoded (encoder/encode-query enc query q-opts)
               q-rows (:rows encoded)]
           ;; The same pipeline as `search` (kept in lockstep — the contract is
           ;; "each inner list = the corresponding search output"): score every
           ;; passage, aggregate to documents by MAX passage score, sort, take k.
           (->> (:passages artifact)
                (map (fn [{:keys [document-id text token-ids rows]}]
                       {:document-id document-id
                        :text text
                        :score (maxsim/max-sim q-rows rows token-ids skiplist)}))
                (group-by :document-id)
                (map (fn [[_ passages]] (apply max-key :score passages)))
                (sort-by :score >)
                (take k)
                (map-indexed
                 (fn [i {:keys [document-id text score]}]
                   {:content text
                    :score score
                    :rank (inc i)
                    :document_id document-id
                    :document_metadata (get document-metadatas document-id {})}))
                vec
                (attach-truncation encoded))))
       (vec queries)))))

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
  (->> (search-batch ctx {:queries queries :index-id index-id :k k})
       (mapv (fn [results]
               (let [normalized (if normalize?
                                  (normalize-result-scores results)
                                  results)]
                 ;; Same :document_id (underscore) ⇒ :uri mapping as
                 ;; search-for-rrf, and the same truncation-report carry.
                 (with-meta
                   (mapv (fn [{:keys [score] :as r}]
                           {:uri (or (:document_id r) (:document-id r))
                            :score (* weight (double score))})
                         normalized)
                   (meta results)))))))
