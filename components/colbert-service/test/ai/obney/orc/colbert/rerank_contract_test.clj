(ns ai.obney.orc.colbert.rerank-contract-test
  "Slice 1, cycle 4: the rerank result CONTRACT is unchanged from the
   bridge-backed implementation — [{:content :score :rank}], 1-indexed rank,
   descending score, k respected, k defaulting to all documents."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.interface :as colbert]))

(use-fixtures :once support/with-model-path)

(def query "binding arbitration of contract disputes")

(def documents
  ["Any dispute arising under this agreement shall be settled by binding arbitration."
   "Either party may terminate this agreement with thirty days written notice."
   "The midfielder scored twice in the final minutes of the championship match."
   "Preheat the oven and bake the sourdough loaf for forty minutes until golden."])

(deftest result-shape-and-ordering
  (let [results (colbert/rerank {} {:query query :documents documents})]
    (testing "k defaults to ALL documents"
      (is (= (count documents) (count results))))
    (testing "every result is exactly {:content :score :rank}"
      (doseq [r results]
        (is (= #{:content :score :rank} (set (keys r))))
        (is (string? (:content r)))
        (is (double? (:score r)))
        (is (pos-int? (:rank r)))))
    (testing "ranks are 1-indexed and consecutive"
      (is (= (range 1 (inc (count documents))) (map :rank results))))
    (testing "scores are sorted descending"
      (is (= (sort-by - (map :score results)) (map :score results))))
    (testing "every result content is one of the input documents"
      (is (= (set documents) (set (map :content results)))))))

(deftest k-is-respected
  (let [results (colbert/rerank {} {:query query :documents documents :k 2})]
    (is (= 2 (count results)))
    (is (= [1 2] (map :rank results)))
    (testing "k results are the TOP k of the full ranking"
      (is (= (->> (colbert/rerank {} {:query query :documents documents})
                  (take 2)
                  (map :content))
             (map :content results))))))

(deftest k-larger-than-corpus-returns-all
  (is (= (count documents)
         (count (colbert/rerank {} {:query query :documents documents :k 50})))))
