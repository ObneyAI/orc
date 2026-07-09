(ns ai.obney.orc.colbert.corpus-test
  "Slice 2, cycle 3: token-count document splitting with the documented
   overlap parity rule (COLBERT-INTEGRATION.md 'Algorithm Parity'):

     overlap = min(size/4, min(size/2, 64))   (integer semantics)

   Counting uses the REAL tokenizer (encoder/encode-ids) via the local
   checkpoint the support fixture pins; no network."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.core.corpus :as corpus]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.model-store :as model-store]))

(use-fixtures :once support/with-model-path)

(defn- encoder-under-test []
  (encoder/get-encoder
   (model-store/resolve-model-dir)))

;; A LONG, deliberately non-repetitive document (varied vocabulary so the
;; token-overlap reconstruction below cannot be fooled by coincidental
;; repeats).
(def long-text
  (str "Any dispute arising under this agreement shall be settled by binding "
       "arbitration before a panel of three neutral arbitrators seated in Geneva. "
       "Either party may terminate the engagement with thirty days written notice "
       "delivered by registered mail to the registered office. All intellectual "
       "property created during the collaboration remains the sole property of the "
       "originating inventor unless assigned in a separate instrument. The hurricane "
       "weakened to a tropical storm before making landfall near the coastal wetlands, "
       "sparing the fishing villages from the worst of the storm surge. Meanwhile the "
       "midfielder scored twice in the final minutes of the championship match, "
       "sending the crowd into raptures and the commentators into hyperbole. Preheat "
       "the oven to two hundred twenty degrees and bake the sourdough loaf for forty "
       "minutes until the crust turns a deep golden brown and sounds hollow when "
       "tapped. Quantum error correction encodes logical qubits across many physical "
       "qubits, trading hardware overhead for resilience against decoherence. The "
       "glacier retreated eleven metres last summer, exposing gravel beds that had "
       "been sealed beneath ice since the seventeenth century."))

(deftest overlap-parity-rule
  (testing "the documented examples"
    (is (= 64 (corpus/chunk-overlap 256)) "chunk-size=256 -> overlap=64")
    (is (= 32 (corpus/chunk-overlap 128)) "chunk-size=128 -> overlap=32"))
  (testing "integer semantics and the 64 cap"
    (is (= 16 (corpus/chunk-overlap 65)) "quot semantics: 65/4 -> 16")
    (is (= 64 (corpus/chunk-overlap 297)) "capped at 64 for large sizes")
    (is (= 10 (corpus/chunk-overlap 40)))))

(deftest short-document-stays-whole
  (let [enc (encoder-under-test)
        text "Either party may terminate this agreement with thirty days written notice."
        n (count (encoder/encode-ids enc text))]
    (is (<= n 256) "precondition: the doc fits one default chunk")
    (is (= [text] (corpus/split-document enc text 256))
        "a doc whose token count <= chunk size stays whole, verbatim")
    (testing "exactly at the boundary stays whole"
      (is (= [text] (corpus/split-document enc text n))))))

(defn- merge-by-token-overlap
  "Reconstruct one id sequence from overlapping chunk id sequences by joining
   each chunk at its maximal suffix/prefix id overlap with the accumulator."
  [id-seqs]
  (reduce (fn [acc ids]
            (let [max-k (min (count acc) (count ids))
                  k (or (some (fn [k] (when (= (take-last k acc) (take k ids)) k))
                              (range max-k -1 -1))
                        0)]
              (into (vec acc) (drop k ids))))
          (vec (first id-seqs))
          (rest id-seqs)))

(deftest chunking-honors-max-document-length-and-covers-the-document
  (let [enc (encoder-under-test)
        size 40
        full-ids (encoder/encode-ids enc long-text)
        chunks (corpus/split-document enc long-text size)]
    (is (> (count full-ids) size) "precondition: the doc needs splitting")
    (is (>= (count chunks) 2) "a long doc yields multiple passages")
    (testing "every chunk re-tokenizes to <= max-document-length tokens"
      (doseq [chunk chunks]
        (is (<= (count (encoder/encode-ids enc chunk)) size)
            (str "chunk over budget: " (pr-str chunk)))))
    (testing "chunks are verbatim substrings covering the whole document"
      (doseq [chunk chunks]
        (is (str/includes? long-text chunk)))
      (is (str/starts-with? long-text (first chunks)))
      (is (str/ends-with? long-text (last chunks))))
    (testing "consecutive chunks OVERLAP and reconstruct the exact token stream"
      (is (> (reduce + (map #(count (encoder/encode-ids enc %)) chunks))
             (count full-ids))
          "total chunk tokens exceed the doc's tokens (overlap duplicates)")
      (is (= full-ids
             (merge-by-token-overlap (map #(encoder/encode-ids enc %) chunks)))
          "merging chunks at their token overlaps reproduces the document's ids"))))

(deftest default-256-chunking-honors-budget
  (let [enc (encoder-under-test)
        text (str long-text " " long-text)   ;; > 256 tokens
        chunks (corpus/split-document enc text 256)]
    (is (> (count (encoder/encode-ids enc text)) 256) "precondition")
    (is (>= (count chunks) 2))
    (doseq [chunk chunks]
      (is (<= (count (encoder/encode-ids enc chunk)) 256)))))

(deftest split-collection-carries-document-provenance
  (let [enc (encoder-under-test)
        short-text "The midfielder scored twice in the final minutes."
        passages (corpus/split-collection enc
                   {:collection [long-text short-text]
                    :document-ids ["doc-long" "doc-short"]
                    :document-metadatas [{:kind "contract"} {:kind "sport"}]
                    :split-documents? true
                    :max-document-length 40})]
    (testing "the long doc split into several passages, all tagged with its id"
      (let [long-passages (filter #(= "doc-long" (:document-id %)) passages)]
        (is (>= (count long-passages) 2))
        (doseq [p long-passages]
          (is (= 0 (:document-index p)))
          (is (= {:kind "contract"} (:document-metadata p))))))
    (testing "the short doc stays one passage, verbatim"
      (let [short-passages (filter #(= "doc-short" (:document-id %)) passages)]
        (is (= 1 (count short-passages)))
        (is (= short-text (:text (first short-passages))))
        (is (= 1 (:document-index (first short-passages))))
        (is (= {:kind "sport"} (:document-metadata (first short-passages))))))))

(deftest split-documents-false-bypasses-splitting-entirely
  (let [enc (encoder-under-test)
        passages (corpus/split-collection enc
                   {:collection [long-text]
                    :document-ids ["doc-long"]
                    :document-metadatas nil
                    :split-documents? false
                    :max-document-length 40})]
    (is (= 1 (count passages)) "no splitting at all")
    (is (= long-text (:text (first passages))) "text passes through verbatim")
    (is (= "doc-long" (:document-id (first passages))))
    (is (nil? (:document-metadata (first passages))))))

(deftest chunk-size-above-doc-maxlen-minus-3-throws
  (let [enc (encoder-under-test)
        doc-maxlen (get-in enc [:consts :doc-maxlen])
        opts {:collection [long-text]
              :document-ids ["d"]
              :document-metadatas nil
              :split-documents? true}]
    (is (= 300 doc-maxlen) "precondition: the checkpoint's doc_maxlen")
    (testing "doc-maxlen - 3 is accepted"
      (is (vector? (corpus/split-collection enc (assoc opts :max-document-length 297)))))
    (testing "doc-maxlen - 2 throws the precise ex-info"
      (let [ex (try (corpus/split-collection enc (assoc opts :max-document-length 298))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (let [data (ex-data ex)]
          (is (= :colbert-chunk-size-exceeds-doc-maxlen (:error data)))
          (is (= 298 (:max-document-length data)))
          (is (= 297 (:limit data)))
          (is (= 300 (:doc-maxlen data))))))))
