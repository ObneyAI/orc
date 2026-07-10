(ns ai.obney.orc.colbert.index-store-test
  "Slice 2, cycles 1-2: the versioned on-disk index artifact.

   - Cycle 1: write-index! / read-index round-trip is EXACT — identical
     metadata, identical passage table, bit-identical float rows (real
     encoder rows from the local checkpoint).
   - Cycle 2: reading a directory WITHOUT the format-version marker
     (including a legacy Python-era PLAID layout) throws the precise
     :colbert-index-artifact-unreadable ex-info from the reading layer.

   Runs the REAL local model via -Dcolbert.model.path; no network."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.index-store :as index-store]
            [ai.obney.orc.colbert.core.model-store :as model-store]))

(use-fixtures :once support/with-model-path)

(defn- temp-dir ^java.io.File [label]
  (.toFile (java.nio.file.Files/createTempDirectory
            (str "colbert-index-store-test-" label)
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- encoder-under-test []
  (encoder/get-encoder
   (model-store/resolve-model-dir)))

(def texts
  ["Any dispute arising under this agreement shall be settled by binding arbitration."
   "Either party may terminate this agreement with thirty days written notice."
   "The midfielder scored twice in the final minutes of the championship match."])

(defn- encode-passages [enc]
  (vec (map-indexed
        (fn [i text]
          (let [{:keys [ids rows]} (encoder/encode-doc enc text)]
            {:document-id (str "d" (inc i))
             :document-index i
             :text text
             :token-ids ids
             :rows rows}))
        texts)))

(deftest artifact-round-trip-is-exact
  (let [enc (encoder-under-test)
        passages (encode-passages enc)
        index {:checkpoint model-store/checkpoint
               :dim 96
               :passages passages
               :document-metadatas {"d1" {:kind "contract" :weight 2}
                                    "d2" {:kind "contract" :tags ["notice" "term"]}
                                    "d3" {:kind "sport"}}}
        dir (io/file (temp-dir "round-trip") "idx")]
    (index-store/write-index! dir index)
    (testing "both artifact files exist"
      (is (.exists (io/file dir "index-meta.json")))
      (is (.exists (io/file dir "embeddings.bin"))))
    (let [read-back (index-store/read-index dir)]
      (testing "metadata round-trips identically"
        (is (= model-store/checkpoint (:checkpoint read-back)))
        (is (= 96 (:dim read-back)))
        (is (= (:document-metadatas index) (:document-metadatas read-back)))
        (is (= (count passages) (count (:passages read-back)))))
      (testing "passage table round-trips identically"
        (doseq [[w r] (map vector passages (:passages read-back))]
          (is (= (:document-id w) (:document-id r)))
          (is (= (:document-index w) (:document-index r)))
          (is (= (:text w) (:text r)))
          (is (= (:token-ids w) (:token-ids r)))))
      (testing "float rows are BIT-identical"
        (doseq [[w r] (map vector passages (:passages read-back))]
          (is (= (count (:rows w)) (count (:rows r)))
              "row count per passage preserved")
          (doseq [[wr rr] (map vector (:rows w) (:rows r))]
            (is (java.util.Arrays/equals ^floats wr ^floats rr)
                "every per-token float row round-trips bit-identically")))))))

;; =============================================================================
;; Cycle 2: format-version marker — unversioned/legacy layouts refuse loudly
;; =============================================================================

(defn- unreadable-ex
  "Run read-index on `dir`, return the ex-info it throws (nil if none)."
  [dir]
  (try (ai.obney.orc.colbert.core.index-store/read-index dir)
       nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- assert-unreadable! [^java.io.File dir]
  (let [ex (unreadable-ex dir)]
    (is (some? ex) "read-index must throw")
    (let [data (ex-data ex)]
      (is (= :colbert-index-artifact-unreadable (:error data))
          "the precise :error key")
      (is (= (.getAbsolutePath dir) (:path data))
          "the ex-info names the offending path")
      (is (string? (:remedy data)) "the ex-info carries the rebuild remedy")
      (is (re-find #"rebuild" (ex-message ex))
          "the message states the rebuild remedy"))
    ex))

(deftest legacy-plaid-layout-throws-precise-ex-info
  (testing "a dir mimicking a legacy Python-era PLAID index has no marker"
    (let [dir (io/file (temp-dir "legacy") ".legacy-plaid" "colbert" "indexes" "my-index")]
      (.mkdirs dir)
      ;; the files a legacy PLAID index directory actually contains
      (spit (io/file dir "metadata.json") "{\"config\": {\"ncells\": null}}")
      (spit (io/file dir "plan.json") "{\"config\": {}}")
      (spit (io/file dir "collection.json") "[\"doc one\", \"doc two\"]")
      (spit (io/file dir "0.codes.pt") "binary-ish")
      (assert-unreadable! dir))))

(deftest missing-directory-throws-precise-ex-info
  (assert-unreadable! (io/file (temp-dir "empty-parent") "no-such-index")))

(deftest foreign-format-name-throws-precise-ex-info
  (let [dir (doto (io/file (temp-dir "foreign") "idx") (.mkdirs))]
    (spit (io/file dir "index-meta.json")
          "{\"format\": \"somebody-elses-index\", \"format-version\": 1}")
    (assert-unreadable! dir)))

(deftest unsupported-format-version-throws-precise-ex-info
  (let [dir (doto (io/file (temp-dir "future-version") "idx") (.mkdirs))]
    (spit (io/file dir "index-meta.json")
          "{\"format\": \"orc-colbert-index\", \"format-version\": 999}")
    (let [ex (assert-unreadable! dir)]
      (is (= 999 (:actual-version (ex-data ex))))
      (is (= 1 (:expected-version (ex-data ex)))))))

(deftest unparseable-meta-throws-precise-ex-info
  (let [dir (doto (io/file (temp-dir "garbage") "idx") (.mkdirs))]
    (spit (io/file dir "index-meta.json") "{not json at all")
    (assert-unreadable! dir)))

;; =============================================================================
;; SCALE-2: byte-identity golden — the artifact FORMAT is the compat contract.
;;
;; The golden artifact directory (test/resources/colbert_index_golden/) was
;; captured from the PRE-SCALE-2 single-buffer write-index! implementation
;; over the deterministic `golden-fixture` below (see the PROVENANCE.md inside
;; the directory). The tests pin:
;;   1. the resource itself (SHA-256 — drift guard),
;;   2. the CURRENT writer against the old capture (byte-identical bin +
;;      string-identical meta — no format change, no version bump),
;;   3. the CURRENT reader over the OLD-code-written artifact (compat), and
;;   4. exact roundtrip of the fixture through the current write/read pair.
;; =============================================================================

(def golden-resource-path
  "Classpath location of the golden artifact directory."
  "resources/colbert_index_golden")

(def golden-embeddings-sha256
  "SHA-256 of the golden embeddings.bin as written by the OLD single-buffer
   implementation. Pins the resource against drift AND the new streamed
   writer against the old bytes."
  "525018090cc600cf83b81c38ba4780ef57ed017eda911759e787a9688db1835c")

(defn golden-fixture
  "Deterministic fixture index: varied row counts (including a 0-row
   passage), seeded floats — java.util.Random's algorithm is specified, so
   the bytes reproduce on any JVM. Shared by the one-time capture script and
   the byte-identity tests; do not change it, or the golden dies."
  []
  (let [rng (java.util.Random. 20260709)
        dim 8
        row! (fn [] (let [a (float-array dim)]
                      (dotimes [i dim] (aset a i (.nextFloat rng)))
                      a))
        rows! (fn [n] (vec (repeatedly n row!)))]
    {:checkpoint "golden-checkpoint"
     :dim dim
     :passages [{:document-id "d1" :document-index 0
                 :text "first passage - arbitration clause"
                 :token-ids [101 7592 2088 102] :rows (rows! 3)}
                {:document-id "d2" :document-index 1
                 :text "second passage - zero-row edge"
                 :token-ids [] :rows []}
                {:document-id "d3" :document-index 2
                 :text "third passage - five rows"
                 :token-ids [101 2003 102] :rows (rows! 5)}
                {:document-id "d4" :document-index 3
                 :text "fourth passage - single row"
                 :token-ids [101 102] :rows (rows! 1)}]
     :document-metadatas {"d1" {:kind "contract" :weight 2}
                          "d3" {:kind "sport" :tags ["a" "b"]}}}))

(defn- sha-256 ^String [^bytes bs]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" %) d))))

(defn- file-bytes ^bytes [f]
  (java.nio.file.Files/readAllBytes (.toPath (io/file f))))

(defn- golden-dir
  "The golden artifact directory, resolved via the test classpath."
  ^java.io.File []
  (let [res (io/resource (str golden-resource-path "/index-meta.json"))]
    (when-not res
      (throw (ex-info "golden artifact not on the test classpath — capture it first"
                      {:resource golden-resource-path})))
    (.getParentFile (io/file (.toURI res)))))

(defn- assert-passages-exact!
  "Every passage field identical; float rows BIT-identical."
  [expected-passages actual-passages]
  (is (= (count expected-passages) (count actual-passages)))
  (doseq [[w r] (map vector expected-passages actual-passages)]
    (is (= (:document-id w) (:document-id r)))
    (is (= (:document-index w) (:document-index r)))
    (is (= (:text w) (:text r)))
    (is (= (:token-ids w) (:token-ids r)))
    (is (= (count (:rows w)) (count (:rows r))) "row count per passage preserved")
    (doseq [[wr rr] (map vector (:rows w) (:rows r))]
      (is (java.util.Arrays/equals ^floats wr ^floats rr)
          "every float row bit-identical"))))

(deftest golden-write-is-byte-identical-to-old-implementation
  (let [gdir (golden-dir)
        golden-bin (file-bytes (io/file gdir index-store/embeddings-file-name))
        golden-meta (slurp (io/file gdir index-store/meta-file-name))
        out-dir (io/file (temp-dir "golden-write") "idx")]
    (testing "the golden resource itself has not drifted"
      (is (= golden-embeddings-sha256 (sha-256 golden-bin))))
    (index-store/write-index! out-dir (golden-fixture))
    (testing "embeddings.bin is byte-identical to the old-implementation capture"
      (let [new-bin (file-bytes (io/file out-dir index-store/embeddings-file-name))]
        (is (= (alength golden-bin) (alength new-bin)) "identical byte length")
        (is (java.util.Arrays/equals golden-bin new-bin) "identical bytes")
        (is (= golden-embeddings-sha256 (sha-256 new-bin)) "identical SHA-256")))
    (testing "index-meta.json is string-identical to the old capture"
      (is (= golden-meta (slurp (io/file out-dir index-store/meta-file-name)))))))

(deftest golden-old-code-artifact-reads-back-exact
  (testing "read-index over the artifact WRITTEN BY THE OLD CODE — existing
            on-disk artifacts stay readable with no rebuild, no version bump"
    (let [fix (golden-fixture)
          r (index-store/read-index (golden-dir))]
      (is (= (:checkpoint fix) (:checkpoint r)))
      (is (= (:dim fix) (:dim r)))
      (is (= (:document-metadatas fix) (:document-metadatas r)))
      (assert-passages-exact! (:passages fix) (:passages r))
      (testing "the 0-row passage reads back with 0 rows"
        (is (= [] (:rows (nth (:passages r) 1))))))))

(deftest golden-fixture-roundtrips-exactly
  (testing "write -> read of the fixture through the CURRENT pair is exact,
            including the 0-row passage"
    (let [fix (golden-fixture)
          dir (io/file (temp-dir "golden-roundtrip") "idx")]
      (index-store/write-index! dir fix)
      (let [r (index-store/read-index dir)]
        (is (= (:checkpoint fix) (:checkpoint r)))
        (is (= (:dim fix) (:dim r)))
        (is (= (:document-metadatas fix) (:document-metadatas r)))
        (assert-passages-exact! (:passages fix) (:passages r))))))

;; =============================================================================
;; SCALE-2: the streamed read's bounded chunk loop crosses batch boundaries
;; correctly. dim 4 -> row-bytes 16 -> chunk-rows 65536 (1 MiB / 16); a
;; 200,000-row passage spans 4 read batches (65536+65536+65536+3392). The
;; 59k witness is the over-2-GiB proof; this pins the batching arithmetic
;; in-suite. (The SCALE-1b over-ceiling pre-check test is GONE with the
;; pre-check itself — there is no artifact-size boundary anymore.)
;; =============================================================================

(deftest roundtrip-crosses-read-chunk-boundary
  (testing "a passage larger than the read chunk roundtrips exactly across
            multiple chunk batches (plus neighbors on both sides)"
    (let [dim 4
          rng (java.util.Random. 424242)
          row! (fn [] (let [a (float-array dim)]
                        (dotimes [i dim] (aset a i (.nextFloat rng)))
                        a))
          big-rows (vec (repeatedly 200000 row!))
          passages [{:document-id "small-before" :document-index 0
                     :text "before" :token-ids [1] :rows [(row!)]}
                    {:document-id "big" :document-index 1
                     :text "big" :token-ids [1 2] :rows big-rows}
                    {:document-id "small-after" :document-index 2
                     :text "after" :token-ids [3] :rows [(row!) (row!)]}]
          dir (io/file (temp-dir "chunk-boundary") "idx")]
      (index-store/write-index! dir {:checkpoint "chunk-checkpoint"
                                     :dim dim
                                     :passages passages
                                     :document-metadatas nil})
      (let [r (index-store/read-index dir)]
        (is (= 3 (count (:passages r))))
        (doseq [[w rp] (map vector passages (:passages r))]
          (is (= (:document-id w) (:document-id rp)))
          (is (= (count (:rows w)) (count (:rows rp)))
              (str "row count preserved for " (:document-id w)))
          (is (every? (fn [[wr rr]] (java.util.Arrays/equals ^floats wr ^floats rr))
                      (map vector (:rows w) (:rows rp)))
              (str "every row bit-identical for " (:document-id w))))))))
