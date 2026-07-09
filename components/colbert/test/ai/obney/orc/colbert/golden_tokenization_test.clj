(ns ai.obney.orc.colbert.golden-tokenization-test
  "Slice 1, cycle 1: the JVM encoder's ColBERT sequence building must equal the
   Python QueryTokenizer/DocTokenizer output EXACTLY (ids AND attention masks),
   per the P-0 golden fixture token_ids.json.

   Also CHECK-1 semantics: the special-token ids / maxlens the encoder loads
   from the model directory's OWN artifacts must equal the P-0 verified
   constants — the constants live in the artifacts, the cross-check lives here."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.core.model-store :as model-store]
            [ai.obney.orc.colbert.core.encoder :as encoder]))

(use-fixtures :once support/with-model-path)

(defn- test-encoder []
  ;; The support fixture pins the override property at the resolved local
  ;; checkpoint, so resolution never fetches here.
  (encoder/get-encoder (model-store/resolve-model-dir)))

(deftest constants-load-from-model-artifacts
  (testing "CHECK-1 cross-check: ids/maxlens read from the checkpoint's own artifacts equal the P-0 verified constants"
    (let [{:keys [cls sep mask pad q-marker d-marker query-maxlen doc-maxlen
                  mask-punctuation? skiplist]} (:consts (test-encoder))]
      (is (= 101 cls))
      (is (= 102 sep))
      (is (= 103 mask))
      (is (= 0 pad))
      (is (= 1 q-marker) "[unused0] resolved from artifacts, not by encoding the marker string")
      (is (= 2 d-marker) "[unused1] resolved from artifacts, not by encoding the marker string")
      (is (= 32 query-maxlen))
      (is (= 300 doc-maxlen))
      (is (true? mask-punctuation?))
      (is (= 32 (count skiplist)) "skiplist derived by tokenizing the checkpoint's 32 skiplist_words"))))

(deftest query-tokenization-matches-python-golden
  (let [enc (test-encoder)
        golden (support/read-golden "token_ids.json")]
    (doseq [[qid {:strs [text input_ids attention_mask]}] (get golden "queries")]
      (testing (str qid ": " text)
        (let [{:keys [ids attention]} (encoder/build-query-ids enc text)]
          (is (= input_ids ids) (str qid " input_ids"))
          (is (= attention_mask attention) (str qid " attention_mask")))))))

(deftest doc-tokenization-matches-python-golden
  (let [enc (test-encoder)
        golden (support/read-golden "token_ids.json")]
    (doseq [[did {:strs [text input_ids attention_mask]}] (get golden "documents")]
      (testing (str did ": " text)
        (let [{:keys [ids attention]} (encoder/build-doc-ids enc text)]
          (is (= input_ids ids) (str did " input_ids"))
          (is (= attention_mask attention) (str did " attention_mask")))))))
