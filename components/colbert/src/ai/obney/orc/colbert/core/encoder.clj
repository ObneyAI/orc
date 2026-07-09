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

     query: [CLS] [Q] tokens [SEP], MASK-padded to query_maxlen; attention 1 on
            real tokens, 0 on MASK padding; ALL rows participate in scoring
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
            :mask-punctuation? :skiplist}."
  [model-dir plain-tokenizer]
  (let [artifact (read-json-artifact model-dir "artifact.metadata" :key-fn keyword)
        onnx-cfg (read-json-artifact model-dir "onnx_config.json" :key-fn keyword)
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
    (cross-check! "mask_punctuation=true requires a non-empty skiplist_words"
                  (or (not mask-punctuation?) (seq skiplist-words))
                  {:mask-punctuation? mask-punctuation? :skiplist-words skiplist-words})
    {:cls cls :sep sep :mask mask :pad pad
     :q-marker q-marker :d-marker d-marker
     :query-maxlen query-maxlen :doc-maxlen doc-maxlen
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

(defn build-query-ids
  "ColBERT query encoding: [CLS] [Q] tokens [SEP], MASK-padded to query_maxlen
   (query expansion). Attention covers only the real tokens
   (attend_to_mask_tokens=false); ALL rows participate in scoring."
  [{{:keys [cls sep mask q-marker query-maxlen]} :consts :as encoder} text]
  (let [toks (take (- query-maxlen 3) (encode-ids encoder text))
        real (concat [cls q-marker] toks [sep])
        n-real (count real)]
    {:ids (vec (take query-maxlen (concat real (repeat mask))))
     :attention (vec (take query-maxlen (concat (repeat n-real 1) (repeat 0))))}))

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
  "Encode a query: {:ids :attention :rows} where :rows is a vector of
   query_maxlen per-token float arrays (unit-normed, dim 96)."
  [encoder text]
  (let [built (build-query-ids encoder text)]
    (assoc built :rows (run-inference encoder built))))

(defn encode-doc
  "Encode a document: {:ids :attention :rows} where :rows is a vector of
   per-token float arrays (unit-normed, dim 96), one per document token —
   including the CLS/marker/SEP rows."
  [encoder text]
  (let [built (build-doc-ids encoder text)]
    (assoc built :rows (run-inference encoder built))))
