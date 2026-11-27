(ns ai.obney.orc.colbert.golden-tokenization-test
  "Slice 1, cycle 1: the JVM encoder's ColBERT sequence building must equal the
   Python QueryTokenizer/DocTokenizer output EXACTLY (ids AND attention masks),
   per the P-0 golden fixture token_ids.json.

   Also CHECK-1 semantics: the special-token ids / maxlens the encoder loads
   from the model directory's OWN artifacts must equal the P-0 verified
   constants — the constants live in the artifacts, the cross-check lives here."
  (:require [ai.obney.orc.colbert.core.logic :as logic]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.colbert.colbert-test-support :as support]))

(use-fixtures :once support/with-model-path)

(defn- test-encoder []
  (logic/get-encoder (logic/resolve-model-dir)))

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
        golden (support/read-golden "token_ids.json")
        reference-limit (get golden "query_maxlen")]
    (is (= 32 reference-limit)
        "the fixture records the query_maxlen it was captured at")
    (doseq [[qid {:strs [text input_ids attention_mask]}] (get golden "queries")]
      (testing (str qid ": " text)
        (let [{:keys [ids attention]}
              (logic/build-query-ids enc text {:maximum-query-tokens reference-limit})]
          (is (= input_ids ids) (str qid " input_ids"))
          (is (= attention_mask attention) (str qid " attention_mask")))))))

(deftest shipped-limit-strictly-extends-the-python-golden
  (let [enc (test-encoder)
        golden (support/read-golden "token_ids.json")
        reference-limit (get golden "query_maxlen")
        shipped (logic/resolve-maximum-query-tokens enc nil)
        mask (get-in enc [:consts :mask])]
    (println "  [N] shipped maximum_query_tokens =" shipped
             "| reference fixture query_maxlen =" reference-limit)
    (is (>= shipped reference-limit)
        "this guard assumes the shipped limit is at least the reference one")
    (doseq [[qid {:strs [text input_ids attention_mask]}] (get golden "queries")]
      (testing (str qid ": " text)
        (let [{:keys [ids attention]} (logic/build-query-ids enc text)]
          (is (= shipped (count ids)))
          (is (= input_ids (vec (take reference-limit ids)))
              (str qid " reference ids are an exact prefix at the shipped limit"))
          (is (= attention_mask (vec (take reference-limit attention)))
              (str qid " reference attention is an exact prefix"))
          (is (every? #(= mask %) (drop reference-limit ids))
              (str qid " the extension is pure [MASK] query expansion"))
          (is (every? zero? (drop reference-limit attention))
              (str qid " and it is not attended (attend_to_mask_tokens=false)")))))))

(deftest doc-tokenization-matches-python-golden
  (let [enc (test-encoder)
        golden (support/read-golden "token_ids.json")]
    (doseq [[did {:strs [text input_ids attention_mask]}] (get golden "documents")]
      (testing (str did ": " text)
        (let [{:keys [ids attention]} (logic/build-doc-ids enc text)]
          (is (= input_ids ids) (str did " input_ids"))
          (is (= attention_mask attention) (str did " attention_mask")))))))
