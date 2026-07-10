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
;; SCALE-1b: the 2 GiB single-buffer artifact ceiling is a TYPED pre-check
;; =============================================================================

(deftest over-ceiling-write-throws-typed-artifact-too-large
  (testing "write-index! with total embedding bytes over Integer/MAX_VALUE throws
            the typed :colbert/artifact-too-large ex-info BEFORE any file IO —
            no ArithmeticException mid-write, no partial artifact on disk"
    ;; Cheapest HONEST construction (no 2 GiB allocation): the pre-check derives
    ;; total-bytes from per-passage METADATA — (count rows) x dim x 4 — and
    ;; throws before ever touching row contents. `(repeat n x)` is a counted
    ;; O(1) clojure.lang.Repeat, so a passage can CLAIM 4.2M rows without
    ;; allocating row data: 4,200,000 rows x 128 dims x 4 B = 2,150,400,000 B
    ;; > Integer/MAX_VALUE (2,147,483,647).
    (let [claimed-rows 4200000
          dim 128
          total (* claimed-rows dim 4)
          dir (io/file (temp-dir "over-ceiling") "idx")
          ex (try (index-store/write-index!
                   dir {:checkpoint "test-checkpoint"
                        :dim dim
                        :passages [{:document-id "d1" :document-index 0
                                    :text "over the ceiling" :token-ids [1 2 3]
                                    :rows (repeat claimed-rows nil)}]
                        :document-metadatas nil})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "write-index! must throw ex-info (not ArithmeticException)")
      (when ex
        (let [data (ex-data ex)]
          (is (= :colbert/artifact-too-large (:type data)) "the typed boundary error")
          (is (= total (:total-bytes data)) "ex-data states the artifact byte size")
          (is (= Integer/MAX_VALUE (:max-bytes data)) "ex-data states the ceiling")
          (is (= 1 (:passages data)) "ex-data states the passage count")
          (is (re-find #"2 GiB" (ex-message ex))
              "message states the single-buffer ceiling")
          (is (re-find #"(?i)chunk" (ex-message ex))
              "message states chunked IO as the lift")))
      (testing "meta-json-last invariant holds trivially — NO file IO happened"
        (is (not (.exists dir))
            "the index dir was never even created — no partial artifact")
        (is (not (.exists (io/file dir index-store/meta-file-name))))
        (is (not (.exists (io/file dir index-store/embeddings-file-name))))))))
