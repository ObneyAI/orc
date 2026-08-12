(ns ai.obney.orc.colbert.core.encoder
  "The pure-JVM ColBERT encoder (ADR 0002): DJL 0.31.1 OnnxRuntime + DJL
   HuggingFace tokenizers running the answerai-colbert-small-v1 checkpoint.

   Loaded ONCE per model directory (thread-safe defonce-atom + locking, the
   embedding.clj idiom). Every special-token id, maxlen, and the punctuation
   skiplist are read from the model directory's OWN artifacts at load time and
   cross-checked across sources (CHECK-1 semantics — no folklore constants):

     - [CLS]/[SEP]  observed from the tokenizer's own post-processor
       (encoding \"\" WITH special tokens yields exactly [CLS SEP])
     - [MASK]/[PAD] added tokens, matched verbatim by the plain tokenizer;
       [MASK] cross-checked against onnx_config.json mask_token_id
     - Q/D markers  ([unused0]/[unused1]) resolved by DIRECT VOCAB LOOKUP in
       tokenizer.json (the pre-tokenizer SPLITS the marker strings, so they can
       NOT be obtained by encoding them); names from artifact.metadata,
       cross-checked against onnx_config.json query_prefix_id/document_prefix_id
     - query_maxlen/doc_maxlen from artifact.metadata, cross-checked against
       onnx_config.json query_length/document_length
     - skiplist derived by tokenizing the checkpoint's skiplist_words
       (mask_punctuation=true; scoring applies ZERO-NOT-DROP semantics, see
       maxsim.clj)

   Encoding schemes (P-0 verified, token-for-token equal to the Python
   QueryTokenizer/DocTokenizer):

     query: [CLS] [Q] tokens [SEP], MASK-padded to maximum_query_tokens;
            attention 1 on real tokens, 0 on MASK padding; ALL rows participate
            in scoring. CC-17: the ROW COUNT is CONFIGURATION
            (`IndexConfiguration.maximum_query_tokens`, see
            default-maximum-query-tokens), not the checkpoint's query_maxlen —
            the checkpoint's MS-MARCO-shaped 32 truncated 100% of this system's
            real queries. A query over the limit is truncated VISIBLY
            (build-query-ids reports it; search/rerank stamp it on their audit
            events).
     doc:   [CLS] [D] tokens [SEP], no padding; CLS/marker/SEP rows DO score

   The ONNX graph is the complete encoder: inputs input_ids / attention_mask /
   token_type_ids (INT64; token_type_ids = zeros), output float32 [1, seq, 96]
   with rows ALREADY unit-normed (no normalization step here)."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [com.brunobonacci.mulog :as mu])
  (:import [ai.djl.huggingface.tokenizers HuggingFaceTokenizer]
           [ai.djl.inference Predictor]
           [ai.djl.ndarray NDArray NDList NDManager]
           [ai.djl.ndarray.types Shape]
           [ai.djl.repository.zoo Criteria ZooModel]
           [ai.onnxruntime OrtEnvironment OrtSession$SessionOptions]
           [java.nio.file Paths]))

(def expected-input-names #{"input_ids" "attention_mask" "token_type_ids"})

;; =============================================================================
;; Artifact reading (CHECK-1 semantics)
;; =============================================================================

(defn- read-json-artifact [model-dir file-name & {:keys [key-fn]}]
  (let [f (io/file model-dir file-name)]
    (when-not (.exists f)
      (throw (ex-info (str "Model artifact missing: " (.getAbsolutePath f))
                      {:error :colbert-model-artifact-missing :file file-name})))
    (json/read-str (slurp f) :key-fn (or key-fn identity))))

(defn- load-tokenizer
  ^HuggingFaceTokenizer [model-dir {:keys [add-special-tokens?]}]
  ;; ^java.util.Map hint: HuggingFaceTokenizer/newInstance(Path, Map) hits
  ;; reflection ambiguity from Clojure otherwise (P-0 sharp edge 4). Options
  ;; are string-valued. We build ColBERT sequences ourselves, so the plain
  ;; tokenizer adds no specials, never truncates, never pads.
  (let [^java.util.Map opts {"addSpecialTokens" (str (boolean add-special-tokens?))
                             "truncation" "false"
                             "padding" "false"}]
    (HuggingFaceTokenizer/newInstance
     (Paths/get (str model-dir) (into-array String ["tokenizer.json"]))
     opts)))

(defn encode-ids
  "Token ids (vector of longs) for text under the encoder's plain tokenizer
   (no special tokens, no truncation, no padding)."
  [{:keys [^HuggingFaceTokenizer tokenizer]} ^String text]
  (vec (.getIds (.encode tokenizer text))))

(defn- cross-check!
  [claim ok? data]
  (when-not ok?
    (throw (ex-info (str "ColBERT model artifacts are inconsistent: " claim)
                    (assoc data :error :colbert-model-artifacts-inconsistent)))))

(defn- load-consts
  "Read + cross-check every constant from the model directory's own artifacts.
   Returns {:cls :sep :mask :pad :q-marker :d-marker :query-maxlen :doc-maxlen
            :max-position-embeddings :mask-punctuation? :skiplist}."
  [model-dir plain-tokenizer]
  (let [artifact (read-json-artifact model-dir "artifact.metadata" :key-fn keyword)
        onnx-cfg (read-json-artifact model-dir "onnx_config.json" :key-fn keyword)
        bert-cfg (read-json-artifact model-dir "config.json" :key-fn keyword)
        ;; tokenizer.json parsed with STRING keys: the vocab map's keys are
        ;; literal token strings ("[unused0]").
        vocab (get-in (read-json-artifact model-dir "tokenizer.json") ["model" "vocab"])
        enc {:tokenizer plain-tokenizer}
        ;; [CLS]/[SEP] observed from the tokenizer's own post-processor.
        [cls sep :as empty-enc]
        (with-open [tok-spec (load-tokenizer model-dir {:add-special-tokens? true})]
          (vec (.getIds (.encode tok-spec ""))))
        ;; [MASK]/[PAD] are added tokens -> matched verbatim by the plain tokenizer.
        [mask] (encode-ids enc "[MASK]")
        [pad] (encode-ids enc "[PAD]")
        ;; Marker NAMES from artifact.metadata (query_token_id/doc_token_id hold
        ;; the token STRINGS); ids by direct vocab lookup — the pre-tokenizer
        ;; splits "[unused0]" so encoding it can NOT resolve the id.
        q-name (:query_token_id artifact)
        d-name (:doc_token_id artifact)
        q-marker (get vocab q-name)
        d-marker (get vocab d-name)
        query-maxlen (:query_maxlen artifact)
        doc-maxlen (:doc_maxlen artifact)
        ;; The HARD ceiling on any sequence length the graph can carry — the
        ;; BERT position-embedding table. CC-17 made maximum_query_tokens
        ;; configuration, so the physical bound has to be a read constant
        ;; rather than folklore (CHECK-1 semantics).
        max-positions (:max_position_embeddings bert-cfg)
        mask-punctuation? (boolean (:mask_punctuation artifact))
        skiplist-words (:skiplist_words onnx-cfg)]
    (cross-check! "encoding \"\" with special tokens must yield exactly [CLS SEP]"
                  (= 2 (count empty-enc)) {:empty-encoding empty-enc})
    (cross-check! "[MASK] must be a single added token matching onnx_config mask_token_id"
                  (and (some? mask) (= mask (:mask_token_id onnx-cfg)))
                  {:mask mask :onnx-config-mask (:mask_token_id onnx-cfg)})
    (cross-check! "query marker id (vocab lookup) must match onnx_config query_prefix_id"
                  (and (some? q-marker) (= q-marker (:query_prefix_id onnx-cfg)))
                  {:name q-name :vocab-id q-marker :onnx-config-id (:query_prefix_id onnx-cfg)})
    (cross-check! "doc marker id (vocab lookup) must match onnx_config document_prefix_id"
                  (and (some? d-marker) (= d-marker (:document_prefix_id onnx-cfg)))
                  {:name d-name :vocab-id d-marker :onnx-config-id (:document_prefix_id onnx-cfg)})
    (cross-check! "query_maxlen must match onnx_config query_length"
                  (and (pos-int? query-maxlen) (= query-maxlen (:query_length onnx-cfg)))
                  {:artifact query-maxlen :onnx-config (:query_length onnx-cfg)})
    (cross-check! "doc_maxlen must match onnx_config document_length"
                  (and (pos-int? doc-maxlen) (= doc-maxlen (:document_length onnx-cfg)))
                  {:artifact doc-maxlen :onnx-config (:document_length onnx-cfg)})
    (cross-check! "[PAD] must resolve to a single added token"
                  (some? pad) {:pad pad})
    (cross-check! "max_position_embeddings must bound BOTH maxlens"
                  (and (pos-int? max-positions)
                       (>= max-positions query-maxlen)
                       (>= max-positions doc-maxlen))
                  {:max-position-embeddings max-positions
                   :query-maxlen query-maxlen :doc-maxlen doc-maxlen})
    (cross-check! "mask_punctuation=true requires a non-empty skiplist_words"
                  (or (not mask-punctuation?) (seq skiplist-words))
                  {:mask-punctuation? mask-punctuation? :skiplist-words skiplist-words})
    {:cls cls :sep sep :mask mask :pad pad
     :q-marker q-marker :d-marker d-marker
     :query-maxlen query-maxlen :doc-maxlen doc-maxlen
     :max-position-embeddings max-positions
     :mask-punctuation? mask-punctuation?
     ;; The skiplist mirrors ColBERT's {symbol: encode(symbol)} construction:
     ;; tokenize each of the checkpoint's skiplist_words.
     :skiplist (if mask-punctuation?
                 (set (mapcat #(encode-ids enc %) skiplist-words))
                 #{})}))

;; =============================================================================
;; Model loading (memoized per model dir — defonce atom + locking idiom)
;; =============================================================================

(defn- load-onnx-model
  ^ZooModel [model-dir]
  ;; Criteria pointed directly at the .onnx file, NDList->NDList, no
  ;; translator. Logs a harmless `SimpleRepository ... non-archive file` WARN
  ;; (P-0 sharp edge 2).
  (-> (Criteria/builder)
      (.setTypes NDList NDList)
      (.optModelPath (Paths/get (str model-dir) (into-array String ["model.onnx"])))
      (.optEngine "OnnxRuntime")
      (.build)
      (.loadModel)))

(defn- graph-input-names
  "The ONNX graph's input tensor names in DECLARED order, via raw OnnxRuntime
   introspection. MUST run after a DJL model load: raw OrtEnvironment access
   BEFORE DJL's engine init makes the engine's own init throw (P-0 sharp
   edge 1)."
  [model-dir]
  (let [env (OrtEnvironment/getEnvironment)]
    (with-open [session (.createSession env
                                        (str (io/file model-dir "model.onnx"))
                                        (OrtSession$SessionOptions.))]
      (vec (.getInputNames session)))))

(defn- load-encoder-internal
  [^String model-dir]
  (mu/log ::loading-encoder :model-dir model-dir)
  (let [tokenizer (load-tokenizer model-dir {:add-special-tokens? false})
        consts (load-consts model-dir tokenizer)
        model (load-onnx-model model-dir)          ;; DJL first (sharp edge 1)
        input-names (graph-input-names model-dir)]
    (when (not= expected-input-names (set input-names))
      (throw (ex-info (str "Unexpected ONNX graph inputs: " (pr-str input-names))
                      {:error :colbert-unexpected-graph-inputs
                       :input-names input-names
                       :expected expected-input-names})))
    (mu/log ::encoder-loaded :model-dir model-dir :input-names input-names
            :consts (dissoc consts :skiplist))
    {:model-dir model-dir
     :tokenizer tokenizer
     :model model
     :input-names input-names
     :consts consts}))

(defonce ^{:private true :doc "canonical model-dir path -> loaded encoder."}
  encoders
  (atom {}))

(defn get-encoder
  "The encoder handle for a model directory (string or File), loading it on
   first use. Thread-safe, memoized per canonical path."
  [model-dir]
  (let [path (.getCanonicalPath (io/file model-dir))]
    (or (get @encoders path)
        (locking encoders
          (or (get @encoders path)
              (let [enc (load-encoder-internal path)]
                (swap! encoders assoc path enc)
                enc))))))

(defn close-all-encoders!
  "Close every loaded encoder (REPL/test hygiene)."
  []
  (locking encoders
    (doseq [[path {:keys [^HuggingFaceTokenizer tokenizer ^ZooModel model]}] @encoders]
      (mu/log ::closing-encoder :model-dir path)
      (try (.close tokenizer) (catch Exception _))
      (try (.close model) (catch Exception _)))
    (reset! encoders {})))

;; =============================================================================
;; ColBERT sequence building (P-0 verified schemes)
;; =============================================================================

(def query-specials
  "[CLS] [Q] [SEP] — the 3 rows every ColBERT query sequence spends on
   structure, so the usable CONTENT budget is maximum_query_tokens - 3."
  3)

(def maximum-query-tokens-property
  "System property overriding the default maximum_query_tokens
   (`IndexConfiguration.maximum_query_tokens`). Mirrors the
   colbert.index.root / colbert.model.path operator-override idiom."
  "colbert.query.max-tokens")

(def default-maximum-query-tokens
  "The SHIPPED default for `IndexConfiguration.maximum_query_tokens` — the
   per-query row count the encoder builds, and therefore the MaxSim ceiling.

   CC-17, chosen from a REAL-CORPUS measurement, not from the checkpoint's
   MS-MARCO-shaped default (evidence: doc/build-timeline/evidence/cc17). Over
   the 2,713-event production dump (2026-07-30 -> 2026-08-03): 221 real
   consolidator inference signatures plus the real classifier task signature,
   tokenized with this very encoder —

     min 150 | p50 439 | p75 439 | p90 439 | p95 439 | p99 448 | max 455
     word-piece tokens, i.e. 153..458 ROWS once [CLS] [Q] [SEP] are counted.

   At the checkpoint's own query_maxlen 32 that is 100% of production queries
   truncated, discarding a MEDIAN of 410 word-piece tokens — the encoder saw
   roughly the first 7% of every real query. 464 is the model card's own
   sizing rule ('the nearest higher multiple of 16 to your query') applied to
   OUR measured maximum requirement of 458, and it sits under the checkpoint's
   512 max_position_embeddings hard ceiling.

   MEASURED COSTS (same evidence file), not hand-waved:
     - truncation 221/221 -> 0/221;
     - one rerank over a 161-document guard pool: 1391ms -> 1648ms (+18%) —
       document encoding dominates, so this is NOT the 14.5x the row count
       might suggest;
     - the [MASK] pedestal GROWS for SHORT queries: related-vs-unrelated
       headroom as a fraction of ceiling falls 0.0218 -> 0.0078 (~2.8x less
       dynamic range). No query in the measured production corpus is short —
       the shortest is 150 tokens — but any future short-query surface pays
       this. The checkpoint supports `dynamic_query_maxlen` /
       `dynamic_querylen_multiples` precisely to avoid the trade; adopting it
       would make the ceiling per-query and is deliberately OUT of scope here
       (the spec models maximum_query_tokens as a fixed per-query row count).

   nil means 'use the checkpoint's own query_maxlen'. Operators override per
   deployment with -Dcolbert.query.max-tokens; an index records the value it
   was built under in its IndexConfiguration."
  464)

(defn configured-maximum-query-tokens
  "The configured limit WITHOUT touching the model directory: the
   -Dcolbert.query.max-tokens override, else `default-maximum-query-tokens`,
   else nil (meaning 'ask the checkpoint')."
  []
  (or (when-let [p (System/getProperty maximum-query-tokens-property)]
        (Long/parseLong p))
      default-maximum-query-tokens))

(defn resolve-maximum-query-tokens
  "The limit actually applied, in precedence order:
     1. an explicit per-call/per-index :maximum-query-tokens
     2. -Dcolbert.query.max-tokens
     3. `default-maximum-query-tokens`
     4. the checkpoint's own query_maxlen
   Always returns a long."
  ^long [encoder maximum-query-tokens]
  (long (or maximum-query-tokens
            (configured-maximum-query-tokens)
            (get-in encoder [:consts :query-maxlen]))))

(defn validate-maximum-query-tokens!
  "Reject a limit that cannot produce a well-formed query sequence, at the
   source rather than silently. Mirrors `configuration.maximum_passage_tokens
   > 0` and corpus/validate-chunk-size!'s 'passages would be silently
   truncated at encode time' guard."
  [encoder ^long limit]
  (when-not (pos? limit)
    (throw (ex-info (str "maximum_query_tokens must be a positive number of rows, got " limit)
                    {:error :colbert-invalid-maximum-query-tokens
                     :maximum-query-tokens limit})))
  (when (<= limit query-specials)
    (throw (ex-info (str "maximum_query_tokens " limit " leaves no room for query content — "
                         "[CLS] [Q] [SEP] alone need " query-specials " rows")
                    {:error :colbert-invalid-maximum-query-tokens
                     :maximum-query-tokens limit
                     :query-specials query-specials})))
  (let [positions (get-in encoder [:consts :max-position-embeddings])]
    (when (and positions (> limit (long positions)))
      (throw (ex-info (str "maximum_query_tokens " limit " exceeds the encoder's "
                           "max_position_embeddings " positions
                           " — the graph cannot carry a sequence that long")
                      {:error :colbert-maximum-query-tokens-exceeds-model-positions
                       :maximum-query-tokens limit
                       :max-position-embeddings positions}))))
  limit)

(defn build-query-ids
  "ColBERT query encoding: [CLS] [Q] tokens [SEP], MASK-padded to the configured
   maximum_query_tokens (query expansion). Attention covers only the real tokens
   (attend_to_mask_tokens=false); ALL rows participate in scoring.

   VISIBLE TRUNCATION (specs/colbert.allium invariant
   OverlongQueriesTruncateVisibly). A query longer than the limit is TRUNCATED,
   not rejected — the excess word-piece tokens are discarded and retrieval
   proceeds against the prefix. That discard used to be invisible to every
   caller; it is now reported on the returned map, and a caller that never
   inspects it still gets a mulog record:

     :query-token-count     the rows the query WOULD have needed — its
                            word-piece tokens PLUS the 3 specials. Measured on
                            the same scale as the limit, which the spec defines
                            as 'the encoder's per-query ROW count'.
     :maximum-query-tokens  the limit actually applied.
     :truncated?            query-token-count > maximum-query-tokens.
     :discarded-token-count how many word-piece tokens the encoder never saw.

   A retrieval key can never be matched against text the encoder never saw, so
   a truncated query is a materially different query — the caller has to be
   able to tell.

   opts:
     :maximum-query-tokens — the limit for this call. Omitted =>
                             `resolve-maximum-query-tokens`."
  ([encoder text] (build-query-ids encoder text nil))
  ([{{:keys [cls sep mask q-marker]} :consts :as encoder} text
    {:keys [maximum-query-tokens]}]
   (let [limit (validate-maximum-query-tokens!
                encoder (resolve-maximum-query-tokens encoder maximum-query-tokens))
         all-toks (encode-ids encoder text)
         needed (+ (count all-toks) query-specials)
         truncated? (> needed limit)
         toks (take (- limit query-specials) all-toks)
         real (concat [cls q-marker] toks [sep])
         n-real (count real)]
     (when truncated?
       (mu/log ::query-truncated
               :query-token-count needed
               :maximum-query-tokens limit
               :discarded-token-count (- needed limit)))
     {:ids (vec (take limit (concat real (repeat mask))))
      :attention (vec (take limit (concat (repeat n-real 1) (repeat 0))))
      :query-token-count needed
      :maximum-query-tokens limit
      :truncated? truncated?
      :discarded-token-count (if truncated? (- needed limit) 0)})))

(defn build-doc-ids
  "ColBERT document encoding: [CLS] [D] tokens [SEP], no padding, attention all
   1. CLS/marker/SEP rows DO participate in scoring (the skiplist filters
   punctuation only)."
  [{{:keys [cls sep d-marker doc-maxlen]} :consts :as encoder} text]
  (let [toks (take (- doc-maxlen 3) (encode-ids encoder text))
        ids (vec (concat [cls d-marker] toks [sep]))]
    {:ids ids
     :attention (vec (repeat (count ids) 1))}))

;; =============================================================================
;; Inference
;; =============================================================================

(defn- run-inference
  "Run one built {:ids :attention} sequence through the graph. Returns a vector
   of per-token float arrays (rows already unit-normed inside the graph).
   NDArrays are BOTH named and supplied in the graph's declared input order
   (P-0 sharp edge 3: DJL's binding mechanism was not pinned down — do both)."
  [{:keys [^ZooModel model input-names]} {:keys [ids attention]}]
  (let [n (count ids)
        shape (Shape. (long-array [1 n]))]
    (with-open [manager (.newSubManager (.getNDManager model))
                predictor (.newPredictor model)]
      (let [mk (fn ^NDArray [^String nm xs]
                 (doto (.create ^NDManager manager (long-array xs) shape)
                   (.setName nm)))
            arrays (mapv (fn [in-name]
                           (case in-name
                             "input_ids" (mk "input_ids" ids)
                             "attention_mask" (mk "attention_mask" attention)
                             "token_type_ids" (mk "token_type_ids" (repeat n 0))))
                         input-names)
            out ^NDList (.predict ^Predictor predictor
                                  (NDList. ^"[Lai.djl.ndarray.NDArray;"
                                           (into-array NDArray arrays)))
            arr ^NDArray (.get out 0)
            [_ seq-len dim] (vec (.getShape (.getShape arr)))
            flat ^floats (.toFloatArray arr)
            rows (mapv (fn [t]
                         (java.util.Arrays/copyOfRange
                          flat (int (* (long t) (long dim))) (int (* (inc (long t)) (long dim)))))
                       (range seq-len))]
        (.close out)
        rows))))

(defn encode-query
  "Encode a query: {:ids :attention :rows} plus build-query-ids' truncation
   report, where :rows is a vector of maximum_query_tokens per-token float
   arrays (unit-normed, dim 96)."
  ([encoder text] (encode-query encoder text nil))
  ([encoder text opts]
   (let [built (build-query-ids encoder text opts)]
     (assoc built :rows (run-inference encoder built)))))

(defn encode-doc
  "Encode a document: {:ids :attention :rows} where :rows is a vector of
   per-token float arrays (unit-normed, dim 96), one per document token —
   including the CLS/marker/SEP rows."
  [encoder text]
  (let [built (build-doc-ids encoder text)]
    (assoc built :rows (run-inference encoder built))))
