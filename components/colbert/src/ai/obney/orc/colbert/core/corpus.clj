(ns ai.obney.orc.colbert.core.corpus
  "Token-count document splitting for the ColBERT index artifact.

   Long documents split into passages by TOKEN count (the real tokenizer
   counts, via the encoder handle), chunk size = max-document-length
   (default 256), with the documented overlap parity rule
   (COLBERT-INTEGRATION.md 'Algorithm Parity'):

     overlap = min(size/4, min(size/2, 64))   (integer semantics)

   A doc whose token count <= chunk size stays whole, verbatim. Every passage
   carries its source document-id / document-index / document-metadata.

   Chunk boundaries snap to WORD boundaries (WordPiece continuation tokens
   start with \"##\"): a passage substring then re-tokenizes to exactly the
   token window it was cut from, so the token budget is honored after
   re-encoding — never split a word across passages. Passage text is taken
   from the ORIGINAL document by the tokenizer's char spans (verbatim
   substrings, no decode round-trip).

   Doc-side hard cap: encode-doc truncates at the checkpoint's doc_maxlen
   (300), spending 3 tokens on [CLS] [D] ... [SEP]. A chunk size above
   doc-maxlen - 3 would emit passages the encoder silently truncates, so it
   throws a precise ex-info instead."
  (:import [ai.djl.huggingface.tokenizers HuggingFaceTokenizer]
           [ai.djl.huggingface.tokenizers.jni CharSpan]))

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
                ;; shrink the end to a word boundary (token e starts a new
                ;; word => tokens s..e-1 end at a word end); fall back to the
                ;; mid-word cut only for a single word longer than the budget
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
              ;; next window starts `overlap` tokens back from the cut,
              ;; snapped FORWARD to the next word start (never resume
              ;; mid-word), always making progress
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
