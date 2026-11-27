(ns ai.obney.orc.colbert.core.logic
  "On-disk model and index artifacts, JVM tokenization and inference, and exact MaxSim scoring."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [com.brunobonacci.mulog :as mu])
  (:import [ai.djl.huggingface.tokenizers HuggingFaceTokenizer]
           [ai.djl.huggingface.tokenizers.jni CharSpan]
           [ai.djl.inference Predictor]
           [ai.djl.ndarray NDArray NDList NDManager]
           [ai.djl.ndarray.types Shape]
           [ai.djl.repository.zoo Criteria ZooModel]
           [ai.onnxruntime OrtEnvironment OrtSession$SessionOptions]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files Paths]))

;; --- Model artifacts ---

(def checkpoint
  "The encoder checkpoint (HF repo id)."
  "answerdotai/answerai-colbert-small-v1")

(def model-id
  "Local cache directory name for the checkpoint."
  "answerai-colbert-small-v1")

(def hf-base-url
  (str "https://huggingface.co/" checkpoint "/resolve/main"))

(def artifact-sizes
  "file name -> expected byte size. Recorded from the live HF repo
   (https://huggingface.co/api/models/answerdotai/answerai-colbert-small-v1/tree/main,
   read 2026-07-08). fp32 model.onnx is the production default (P-0: int8 drift
   flipped a near-tie ranking)."
  {"model.onnx"              133298178
   "tokenizer.json"          711396
   "config.json"             702
   "tokenizer_config.json"   1242
   "special_tokens_map.json" 695
   "artifact.metadata"       2219
   "onnx_config.json"        767})

(def required-files (vec (sort (keys artifact-sizes))))

(def model-path-property "colbert.model.path")

(defn default-cache-dir
  ^java.io.File []
  (io/file (System/getProperty "user.home") ".cache" "orc" "colbert" model-id))

(defn http-fetch!
  "The REAL fetch capability: stream `url` into `dest` (a File). Downloads to a
   sibling temp file first, then atomically moves into place, so a crashed
   download never leaves a plausible-but-truncated artifact at the final path."
  [url ^java.io.File dest]
  (io/make-parents dest)
  (let [tmp (io/file (.getParentFile dest) (str (.getName dest) ".part"))]
    (try
      (with-open [in (io/input-stream (java.net.URL. ^String url))]
        (io/copy in tmp))
      (java.nio.file.Files/move
       (.toPath tmp) (.toPath dest)
       (into-array java.nio.file.CopyOption
                   [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (when (.exists tmp) (.delete tmp))))))

(defn- file-size ^long [^java.io.File f]
  (if (.exists f) (.length f) -1))

(defn- verify-size!
  "Throw a precise ex-info when `f` does not match its expected byte size."
  [^java.io.File f file-name context]
  (let [expected (long (get artifact-sizes file-name))
        actual (file-size f)]
    (when (not= expected actual)
      (throw (ex-info (str "ColBERT model artifact failed size verification: "
                           file-name " expected " expected " bytes, got "
                           (if (neg? actual) "MISSING" actual))
                      {:error :colbert-model-artifact-size-mismatch
                       :file file-name
                       :path (.getAbsolutePath f)
                       :expected-bytes expected
                       :actual-bytes (when-not (neg? actual) actual)
                       :context context})))))

(defn- override-dir
  "The -Dcolbert.model.path directory, verified to contain every required
   artifact (existence only — an override dir is the operator's authority;
   byte sizes are enforced on the DOWNLOAD path, where truncation is the
   realistic failure)."
  ^java.io.File []
  (when-let [path (System/getProperty model-path-property)]
    (let [dir (io/file path)
          missing (vec (remove #(.exists (io/file dir %)) required-files))]
      (when (seq missing)
        (throw (ex-info (str "-D" model-path-property " points at " path
                             " but it is missing required model artifacts: " missing)
                        {:error :colbert-model-override-incomplete
                         :path (.getAbsolutePath dir)
                         :missing missing
                         :required required-files})))
      dir)))

(defn- cache-complete? [^java.io.File dir]
  (every? (fn [file-name]
            (= (long (get artifact-sizes file-name))
               (file-size (io/file dir file-name))))
          required-files))

(defn- populate-cache!
  "Fetch every missing/size-mismatched artifact into `dir` via `fetch-fn`,
   verifying each fetched file's byte size."
  [^java.io.File dir fetch-fn]
  (doseq [file-name required-files
          :let [dest (io/file dir file-name)]
          :when (not= (long (get artifact-sizes file-name)) (file-size dest))]
    (fetch-fn (str hf-base-url "/" file-name) dest)
    (verify-size! dest file-name :download))
  dir)

(defn resolve-model-dir
  "Resolve (and if necessary materialize) the encoder checkpoint directory.
   Returns a java.io.File. Options:

     :fetch-fn  - (fn [url dest-file]) capability used to download a missing
                  artifact. Defaults to the real HTTP download; tests MUST
                  inject a fake (no test touches the network).
     :cache-dir - cache directory override (default
                  ~/.cache/orc/colbert/answerai-colbert-small-v1/)."
  (^java.io.File [] (resolve-model-dir {}))
  (^java.io.File [{:keys [fetch-fn cache-dir]
                   :or {fetch-fn http-fetch!}}]
   (or (override-dir)
       (let [dir (io/file (or cache-dir (default-cache-dir)))]
         (if (cache-complete? dir)
           dir
           (populate-cache! dir fetch-fn))))))

;; --- Encoder ---

(def expected-input-names #{"input_ids" "attention_mask" "token_type_ids"})


(defn- read-json-artifact [model-dir file-name & {:keys [key-fn]}]
  (let [f (io/file model-dir file-name)]
    (when-not (.exists f)
      (throw (ex-info (str "Model artifact missing: " (.getAbsolutePath f))
                      {:error :colbert-model-artifact-missing :file file-name})))
    (json/read-str (slurp f) :key-fn (or key-fn identity))))

(defn- load-tokenizer
  ^HuggingFaceTokenizer [model-dir {:keys [add-special-tokens?]}]
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
        vocab (get-in (read-json-artifact model-dir "tokenizer.json") ["model" "vocab"])
        enc {:tokenizer plain-tokenizer}
        [cls sep :as empty-enc]
        (with-open [tok-spec (load-tokenizer model-dir {:add-special-tokens? true})]
          (vec (.getIds (.encode tok-spec ""))))
        [mask] (encode-ids enc "[MASK]")
        [pad] (encode-ids enc "[PAD]")
        q-name (:query_token_id artifact)
        d-name (:doc_token_id artifact)
        q-marker (get vocab q-name)
        d-marker (get vocab d-name)
        query-maxlen (:query_maxlen artifact)
        doc-maxlen (:doc_maxlen artifact)
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
     :skiplist (if mask-punctuation?
                 (set (mapcat #(encode-ids enc %) skiplist-words))
                 #{})}))


(defn- load-onnx-model
  ^ZooModel [model-dir]
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
  (mu/log :ai.obney.orc.colbert.core.encoder/loading-encoder :model-dir model-dir)
  (let [tokenizer (load-tokenizer model-dir {:add-special-tokens? false})
        consts (load-consts model-dir tokenizer)
        model (load-onnx-model model-dir)
        input-names (graph-input-names model-dir)]
    (when (not= expected-input-names (set input-names))
      (throw (ex-info (str "Unexpected ONNX graph inputs: " (pr-str input-names))
                      {:error :colbert-unexpected-graph-inputs
                       :input-names input-names
                       :expected expected-input-names})))
    (mu/log :ai.obney.orc.colbert.core.encoder/encoder-loaded :model-dir model-dir :input-names input-names
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
      (mu/log :ai.obney.orc.colbert.core.encoder/closing-encoder :model-dir path)
      (try (.close tokenizer) (catch Exception _))
      (try (.close model) (catch Exception _)))
    (reset! encoders {})))


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
   > 0` and logic/validate-chunk-size!'s 'passages would be silently
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
       (mu/log :ai.obney.orc.colbert.core.encoder/query-truncated
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

;; --- Document splitting ---

(defn chunk-overlap
  "The documented parity rule: overlap = min(size/4, min(size/2, 64)),
   integer semantics."
  [chunk-size]
  (min (quot chunk-size 4) (min (quot chunk-size 2) 64)))

(defn- validate-chunk-size!
  [max-document-length doc-maxlen]
  (let [limit (- doc-maxlen 3)]
    (when (> max-document-length limit)
      (throw (ex-info (str "ColBERT chunk size " max-document-length
                           " exceeds the encoder's usable document length "
                           limit " (doc_maxlen " doc-maxlen " minus [CLS] [D] [SEP]) — "
                           "passages would be silently truncated at encode time")
                      {:error :colbert-chunk-size-exceeds-doc-maxlen
                       :max-document-length max-document-length
                       :limit limit
                       :doc-maxlen doc-maxlen})))))

(defn- tokenize-with-spans
  "Tokenize `text` with the encoder's plain tokenizer, keeping per-token char
   spans and word-start flags (a WordPiece continuation token starts with ##)."
  [{:keys [^HuggingFaceTokenizer tokenizer]} ^String text]
  (let [encoding (.encode tokenizer text)
        tokens (vec (.getTokens encoding))
        spans (vec (.getCharTokenSpans encoding))]
    {:n (count tokens)
     :word-start? (mapv (fn [^String t] (not (.startsWith t "##"))) tokens)
     :starts (mapv (fn [^CharSpan s] (.getStart s)) spans)
     :ends (mapv (fn [^CharSpan s] (.getEnd s)) spans)}))

(defn split-document
  "Split `text` into passages of at most `max-document-length` tokens with
   `chunk-overlap` tokens of overlap between consecutive passages. Returns a
   vector of verbatim substrings of `text`. A doc whose token count <= the
   chunk size stays whole."
  [encoder ^String text max-document-length]
  (let [size (long max-document-length)
        {:keys [n word-start? starts ends]} (tokenize-with-spans encoder text)]
    (if (<= (long n) size)
      [text]
      (let [overlap (long (chunk-overlap size))
            word-start-at (fn [^long i] (boolean (nth word-start? i)))]
        (loop [s 0
               chunks []]
          (let [e0 (min (+ s size) (long n))
                e (if (= e0 (long n))
                    e0
                    (let [e' (loop [e e0]
                               (cond (<= e (inc s)) s
                                     (word-start-at e) e
                                     :else (recur (dec e))))]
                      (if (> e' s) e' e0)))
                chunk (subs text (nth starts s) (nth ends (dec e)))
                chunks (conj chunks chunk)]
            (if (>= e (long n))
              chunks
              (let [s0 (max (- e overlap) (inc s))
                    s' (loop [i s0]
                         (if (and (< i (long n)) (not (word-start-at i)))
                           (recur (inc i))
                           i))]
                (if (>= s' (long n))
                  chunks
                  (recur s' chunks))))))))))

(defn split-collection
  "Turn a document collection into the passage table for the index artifact.

     encoder - the loaded encoder handle (tokenizer + consts)
     opts    - {:collection          [text ...]           (required)
                :document-ids        [id ...]              (required, aligned)
                :document-metadatas  [map ...] or nil      (aligned)
                :split-documents?    boolean
                :max-document-length int}

   Returns a vector of {:document-id :document-index :document-metadata
   :text}, in document order, passages in document-text order.
   `:split-documents? false` bypasses splitting entirely (one verbatim
   passage per document)."
  [encoder {:keys [collection document-ids document-metadatas
                   split-documents? max-document-length]}]
  (when split-documents?
    (validate-chunk-size! max-document-length
                          (get-in encoder [:consts :doc-maxlen])))
  (vec
   (apply concat
          (map-indexed
           (fn [i text]
             (let [texts (if split-documents?
                           (split-document encoder text max-document-length)
                           [text])
                   base {:document-id (nth document-ids i)
                         :document-index i
                         :document-metadata (when document-metadatas
                                              (nth document-metadatas i))}]
               (map #(assoc base :text %) texts)))
           collection))))

;; --- MaxSim ---

(defn dot
  "Dot product of two float arrays (same length)."
  ^double [^floats a ^floats b]
  (let [n (alength a)]
    (loop [i 0 acc 0.0]
      (if (< i n)
        (recur (inc i) (+ acc (* (aget a i) (aget b i))))
        acc))))

(defn max-sim
  "Exact MaxSim with zero-not-drop skiplist semantics.

     q-rows   - seqable of query per-token float arrays (ALL rows score,
                including the MASK query-expansion rows)
     d-rows   - seqable of document per-token float arrays (CLS/marker/SEP
                rows DO score)
     d-ids    - the document token ids, aligned with d-rows
     skiplist - set of token ids whose positions contribute a 0.0 candidate
                instead of their dot (punctuation masking)

   Returns the summed per-query-token maxima (double). The max is a TRUE max
   over the candidates (an all-negative doc with no skiplisted position stays
   negative — only a skiplisted position introduces a 0.0 candidate). An empty
   document contributes 0.0 per query token."
  ^double [q-rows d-rows d-ids skiplist]
  (let [d-pairs (mapv (fn [id ^floats row]
                        [(boolean (contains? skiplist id)) row])
                      d-ids d-rows)]
    (if (empty? d-pairs)
      0.0
      (reduce
       (fn [^double acc ^floats q-row]
         (+ acc
            (double
             (reduce (fn [^double best [skip? ^floats d-row]]
                       (max best (if skip? 0.0 (dot q-row d-row))))
                     Double/NEGATIVE_INFINITY
                     d-pairs))))
       0.0
       q-rows))))

;; --- Index artifacts ---

(def format-name "orc-colbert-index")
(def format-version 1)

(def meta-file-name "index-meta.json")
(def embeddings-file-name "embeddings.bin")

(def rebuild-remedy
  (str "Index artifacts are derived data — rebuild this index from its source "
       "documents (colbert/create-index!; the :colbert/index-created event "
       "in the event store carries the full corpus)."))


(defonce ^{:doc "canonical index path -> loaded artifact."}
  indexes
  (atom {}))

(defn evict-index!
  "Drop one canonical path (or, with no args, every entry) from the cache."
  ([] (locking indexes (reset! indexes {})))
  ([dir]
   (let [path (.getCanonicalPath (io/file dir))]
     (locking indexes (swap! indexes dissoc path)))))

(defn- unreadable!
  [^java.io.File dir reason data]
  (throw (ex-info (str "ColBERT index artifact unreadable at "
                       (.getAbsolutePath dir) ": " reason ". " rebuild-remedy)
                  (merge {:error :colbert-index-artifact-unreadable
                          :path (.getAbsolutePath dir)
                          :reason reason
                          :remedy rebuild-remedy}
                         data))))

;; =============================================================================
;; Write
;; =============================================================================

(defn- passage-byte-size ^long [{:keys [rows]} ^long dim]
  (* (long (count rows)) dim 4))

(defn write-index!
  "Write an index artifact into directory `dir` (created if absent).

     index - {:checkpoint <string>            encoder checkpoint id
              :dim        <int>               embedding dim (row length)
              :passages   [{:document-id :document-index :text :token-ids
                            :rows <vec of float[dim]>} ...]
              :document-metadatas <map document-id -> metadata map, or nil>}

   Layout: embeddings.bin holds every passage's rows contiguously in passage
   order (float32 LE); index-meta.json records per-passage byte offsets and
   row counts, written LAST so a partial write never leaves a directory that
   parses as a complete artifact. Evicts `dir` from the in-memory cache."
  [dir {:keys [checkpoint dim passages document-metadatas]}]
  (let [dir (io/file dir)
        dim (long dim)
        _ (.mkdirs dir)
        total-bytes (reduce + 0 (map #(passage-byte-size % dim) passages))
        buf (doto (ByteBuffer/allocate total-bytes)
              (.order ByteOrder/LITTLE_ENDIAN))
        offsets (reductions + 0 (map #(passage-byte-size % dim) passages))]
    (doseq [{:keys [rows] :as passage} passages
            ^floats row rows]
      (when (not= dim (alength row))
        (throw (ex-info "Passage row length does not match the index dim"
                        {:error :colbert-index-row-dim-mismatch
                         :dim dim
                         :row-length (alength row)
                         :document-id (:document-id passage)})))
      (dotimes [i dim]
        (.putFloat buf (aget row i))))
    (with-open [out (io/output-stream (io/file dir embeddings-file-name))]
      (.write out (.array buf)))
    (let [meta {"format" format-name
                "format-version" format-version
                "checkpoint" checkpoint
                "dim" dim
                "num-passages" (count passages)
                "passages" (mapv (fn [{:keys [document-id document-index
                                              text token-ids rows]} offset]
                                   {"document-id" document-id
                                    "document-index" document-index
                                    "text" text
                                    "token-ids" token-ids
                                    "offset" offset
                                    "rows" (count rows)})
                                 passages offsets)
                "document-metadatas" document-metadatas}]
      (spit (io/file dir meta-file-name) (json/write-str meta)))
    (evict-index! dir)
    (mu/log :ai.obney.orc.colbert.core.index-store/index-written :path (.getAbsolutePath dir)
            :passages (count passages) :bytes total-bytes)
    dir))

;; =============================================================================
;; Read
;; =============================================================================

(defn- keywordize-metadata
  "Mirror the old bridge's JSON round-trip: every map KEY at every level of a
   metadata map comes back as a keyword (the bridge parsed the whole Python
   response with :key-fn keyword). Document-id keys of the OUTER map stay
   strings — handled by the caller."
  [m]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v])) x)
       x))
   m))

(defn read-index
  "Read an index artifact directory written by `write-index!`. Round-trip
   exact: identical metadata + bit-identical float rows.

   Returns {:checkpoint :dim :passages :document-metadatas} where each passage
   is {:document-id :document-index :text :token-ids :rows}.

   A directory without the format-version marker — a missing/foreign
   index-meta.json, an unparseable one, a wrong format name or version, or a
   legacy Python-era PLAID layout — throws a precise
   :colbert-index-artifact-unreadable ex-info naming the path and the rebuild
   remedy. Slice 3 builds its legacy handling on this being thrown HERE, at
   the reading layer."
  [dir]
  (let [dir (io/file dir)
        meta-file (io/file dir meta-file-name)]
    (when-not (.isDirectory dir)
      (unreadable! dir "not a directory" {}))
    (when-not (.exists meta-file)
      (unreadable! dir (str "no " meta-file-name
                            " format marker (foreign or legacy PLAID layout)")
                   {:missing meta-file-name}))
    (let [meta (try (json/read-str (slurp meta-file))
                    (catch Exception e
                      (unreadable! dir (str meta-file-name " is not parseable JSON")
                                   {:parse-error (ex-message e)})))]
      (when (not= format-name (get meta "format"))
        (unreadable! dir (str "format marker mismatch: expected " (pr-str format-name)
                              ", got " (pr-str (get meta "format")))
                     {:expected-format format-name :actual-format (get meta "format")}))
      (when (not= format-version (get meta "format-version"))
        (unreadable! dir (str "format version " (pr-str (get meta "format-version"))
                              " is not the supported version " format-version)
                     {:expected-version format-version
                      :actual-version (get meta "format-version")}))
      (let [dim (long (get meta "dim"))
            passages-meta (get meta "passages")
            bin-file (io/file dir embeddings-file-name)
            _ (when-not (.exists bin-file)
                (unreadable! dir (str embeddings-file-name " is missing") {}))
            expected-bytes (reduce + 0 (map #(* (long (get % "rows")) dim 4) passages-meta))
            bytes (Files/readAllBytes (.toPath bin-file))
            _ (when (not= expected-bytes (alength bytes))
                (unreadable! dir (str embeddings-file-name " byte length mismatch: expected "
                                      expected-bytes ", got " (alength bytes))
                             {:expected-bytes expected-bytes :actual-bytes (alength bytes)}))
            fbuf (.asFloatBuffer (doto (ByteBuffer/wrap bytes)
                                   (.order ByteOrder/LITTLE_ENDIAN)))
            passages (mapv (fn [p]
                             (let [offset (long (get p "offset"))
                                   n-rows (long (get p "rows"))
                                   rows (mapv (fn [r]
                                                (let [row (float-array dim)]
                                                  (.position fbuf (int (+ (quot offset 4)
                                                                          (* (long r) dim))))
                                                  (.get fbuf row)
                                                  row))
                                              (range n-rows))]
                               {:document-id (get p "document-id")
                                :document-index (get p "document-index")
                                :text (get p "text")
                                :token-ids (vec (get p "token-ids"))
                                :rows rows}))
                           passages-meta)]
        {:checkpoint (get meta "checkpoint")
         :dim dim
         :passages passages
         :document-metadatas (when-let [metas (get meta "document-metadatas")]
                               (into {} (map (fn [[doc-id m]]
                                               [doc-id (keywordize-metadata m)]))
                                     metas))}))))

(defn load-index
  "The loaded artifact for an index directory (string or File), reading it
   from disk on first use. Thread-safe, memoized per canonical path — a
   per-query search never re-reads from disk."
  [dir]
  (let [path (.getCanonicalPath (io/file dir))]
    (or (get @indexes path)
        (locking indexes
          (or (get @indexes path)
              (let [index (read-index path)]
                (swap! indexes assoc path index)
                index))))))

;; --- Retrieval and scoring ---

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
   `logic/encode-query` by `search` / `search-batch`), so an index built
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
               (configured-maximum-query-tokens)
               (get-in (get-encoder (resolve-model-dir))
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
    (let [fallback (maxsim-ceiling)]
      (mu/log :ai.obney.orc.colbert.core.operations/maxsim-ceiling-from-process-default
              :reason :no-truncation-report-on-results
              :result-count (count results)
              :process-default-ceiling fallback)
      fallback)))

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
                    maxsim-ceiling; an explicit nil also falls back to it —
                    LOUDLY since CC-27: the fallback mulogs the score and the
                    process-default ceiling it used, because callers holding
                    an encoding identity are expected to pass that encoding's
                    own ceiling via results-maxsim-ceiling)
       :method - Normalization method: :linear, :sigmoid (default: :linear)

   Returns:
     Normalized score in [0, 1] range"
  [score & {:keys [max-score method] :or {method :linear}}]
  (let [max-score (double (or max-score
                              (let [fallback (maxsim-ceiling)]
                                (mu/log :ai.obney.orc.colbert.core.operations/normalize-score-process-default-fallback
                                        :score (double score)
                                        :process-default-ceiling fallback)
                                fallback)))]
    (case method
      :linear
      (min 1.0 (max 0.0 (/ (double score) max-score)))

      :sigmoid
      (let [x (- (/ (double score) max-score) 0.5)]
        (/ 1.0 (+ 1.0 (Math/exp (* -10.0 x)))))

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
   passages by token count (logic/split-collection, overlap parity rule),
   encode every passage (logic/encode-doc), and write the versioned index
   artifact (logic/write-index!) under
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
                             logic/checkpoint — the encoder actually
                             used; the artifact records the real checkpoint
                             regardless)
       :split-documents?   - Auto-split long docs (default: true)
       :max-document-length - Chunk size in tokens (default: 256; must be
                             <= the encoder's doc-maxlen - 3)
       :maximum-query-tokens - IndexConfiguration.maximum_query_tokens: the
                             per-query row count searches of this index build,
                             and therefore its MaxSim ceiling. Default: the
                             configured default (resolve-maximum-query-tokens). Validated the same
                             way max-document-length is — positive, and within
                             the encoder's max_position_embeddings.

   Returns map with :index-id, :index-path, :num-passages, :duration-ms,
   :document-ids, :document-metadatas, :document-count, :model-name,
   :index-name, and :config."
  [_ctx {:keys [collection document-ids document-metadatas index-name
               model-name split-documents? max-document-length
               maximum-query-tokens]
        :or {model-name checkpoint
             split-documents? true
             max-document-length 256}}]
  (let [index-id (random-uuid)
        model-name (or model-name checkpoint)
        split-documents? (if (nil? split-documents?) true split-documents?)
        max-document-length (or max-document-length 256)
        document-ids (or document-ids
                         (into [] (repeatedly (count collection)
                                              #(str (random-uuid)))))
        start-time (System/currentTimeMillis)]

    (mu/log :ai.obney.orc.colbert.core.operations/creating-index :index-id index-id :index-name index-name
            :document-count (count collection))

    (let [enc (get-encoder (resolve-model-dir))
          maximum-query-tokens (validate-maximum-query-tokens!
                                enc (resolve-maximum-query-tokens
                                     enc maximum-query-tokens))
          passages (split-collection enc
                     {:collection collection
                      :document-ids document-ids
                      :document-metadatas document-metadatas
                      :split-documents? split-documents?
                      :max-document-length max-document-length})
          encoded (mapv (fn [{:keys [text] :as passage}]
                          (let [{:keys [ids rows]} (encode-doc enc text)]
                            (assoc passage :token-ids ids :rows rows)))
                        passages)
          dim (if-let [^floats row (first (:rows (first encoded)))]
                (alength row)
                96)
          index-dir (io/file (index-root) index-name)
          _ (write-index! index-dir
              {:checkpoint checkpoint
               :dim dim
               :passages encoded
               :document-metadatas (when document-metadatas
                                     (zipmap document-ids document-metadatas))})
          duration-ms (- (System/currentTimeMillis) start-time)]

      (mu/log :ai.obney.orc.colbert.core.operations/index-created :index-id index-id :duration-ms duration-ms
              :passages (count encoded))

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
   queries/search / rerank DIRECTLY rather than through the Grain command,
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
  (let [enc (get-encoder (resolve-model-dir))
        built (build-query-ids
               enc query {:maximum-query-tokens maximum-query-tokens})]
    (truncation-report built)))

(defn index-maximum-query-tokens
  "`IndexConfiguration.maximum_query_tokens` for an index read-model row, or
   nil when the index predates the field (legacy artifacts fall back to the
   configured default — never to a silent hard-coded 32)."
  [index]
  (get-in index [:config :maximum-query-tokens]))

(defn- rank-passages
  [artifact encoded skiplist k]
  (let [q-rows (:rows encoded)
        document-metadatas (:document-metadatas artifact)]
    (->> (:passages artifact)
         (map (fn [{:keys [document-id text token-ids rows]}]
                {:document-id document-id
                 :text text
                 :score (max-sim q-rows rows token-ids skiplist)}))
         (group-by :document-id)
         (map (fn [[_ passages]] (apply max-key :score passages)))
         (sort-by :score >)
         (take k)
         (map-indexed (fn [i {:keys [document-id text score]}]
                        {:content text
                         :score score
                         :rank (inc i)
                         :document_id document-id
                         :document_metadata (get document-metadatas document-id {})}))
         vec
         (attach-truncation encoded))))

(defn search-index
  "Load a projected index once and rank its documents for each supplied query."
  [index index-id queries k]
  (when-not index
    (throw (ex-info "Index not found" {:index-id index-id})))
  (when (= :deleted (:status index))
    (throw (ex-info "Index has been deleted" {:index-id index-id})))
  (let [k (or k 10)
        artifact (load-index (:index-path index))
        enc (get-encoder (resolve-model-dir))
        skiplist (get-in enc [:consts :skiplist])
        q-opts {:maximum-query-tokens (index-maximum-query-tokens index)}]
    (mapv (fn [query]
            (rank-passages artifact (encode-query enc query q-opts) skiplist k))
          queries)))

(defn rrf-results
  [results normalize? weight]
  (let [normalized (if normalize? (normalize-result-scores results) results)]
    (with-meta
      (mapv (fn [{:keys [score] :as result}]
              {:uri (or (:document_id result) (:document-id result))
               :score (* weight (double score))})
            normalized)
      (meta results))))

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
        enc (get-encoder (resolve-model-dir))
        skiplist (get-in enc [:consts :skiplist])
        encoded (encode-query
                 enc query {:maximum-query-tokens maximum-query-tokens})
        q-rows (:rows encoded)]
    (->> documents
         (mapv (fn [doc]
                 (let [{:keys [ids rows]} (encode-doc enc doc)]
                   {:content doc
                    :score (max-sim q-rows rows ids skiplist)})))
         (sort-by :score >)
         (take k)
         (map-indexed (fn [i result] (assoc result :rank (inc i))))
         vec
         (attach-truncation encoded))))

;; --- Index event projection ---

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
             :documents documents
             :document-ids document-ids
             :document-metadatas document-metadatas
             :document-count document-count
             :passage-count passage-count
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

(defn alias-tag-id [alias]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (str "colbert-index-alias:" alias)
              java.nio.charset.StandardCharsets/UTF_8)))

(defn maximum-query-tokens
  ([] (maximum-query-tokens nil))
  ([explicit]
   (resolve-maximum-query-tokens
    (get-encoder (resolve-model-dir)) explicit)))

(defn writable-metadata?
  "Metadata must survive the JSON encoder used by the index artifact writer."
  [value]
  (try
    (string? (json/write-str value))
    (catch Exception _ false)))
