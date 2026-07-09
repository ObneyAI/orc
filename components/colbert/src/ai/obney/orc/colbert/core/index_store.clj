(ns ai.obney.orc.colbert.core.index-store
  "The on-disk ColBERT index artifact (CONTEXT.md Retrieval: derived data,
   always rebuildable from the events that describe its corpus, never the
   source of truth).

   An index is a DIRECTORY containing exactly two files:

     index-meta.json  - versioned metadata: the format marker
                        (\"orc-colbert-index\" version 1), the encoder
                        checkpoint id, the embedding dim, the passage count,
                        the passage table (per passage: document-id,
                        document-index, passage text, token ids, byte offset +
                        row count into the embeddings file), and the
                        per-document metadata map (document-id -> map).
     embeddings.bin   - float32 LITTLE-ENDIAN token embeddings, contiguous
                        per passage in passage-table order; each passage
                        occupies (rows x dim x 4) bytes at its recorded
                        offset.

   Reading a directory WITHOUT the format marker (including legacy
   Python-era PLAID layouts) throws a precise
   :colbert-index-artifact-unreadable ex-info from THIS reading layer — loud
   at the source, naming the path and the rebuild remedy.

   Loaded artifacts are cached in memory per canonical index path (defonce
   atom + locking, the encoder.clj idiom) so per-query search never re-reads
   from disk. `write-index!` evicts the written path from the cache."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.walk :as walk]
            [com.brunobonacci.mulog :as mu])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files]))

(def format-name "orc-colbert-index")
(def format-version 1)

(def meta-file-name "index-meta.json")
(def embeddings-file-name "embeddings.bin")

(def rebuild-remedy
  (str "Index artifacts are derived data — rebuild this index from its source "
       "documents (operations/create-index!; the :colbert/index-created event "
       "in the event store carries the full corpus)."))

;; =============================================================================
;; In-memory cache (defonce atom + locking, the encoder.clj idiom)
;; =============================================================================

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
        ;; embeddings.bin: contiguous float32 LE rows, per passage
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
    ;; index-meta.json last — the commit marker
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
    (mu/log ::index-written :path (.getAbsolutePath dir)
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
