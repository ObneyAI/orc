(ns ai.obney.orc.colbert.rerank-golden-test
  "Slice 1, cycle 3: end-to-end `colbert/rerank` on the JVM backend against the
   Python golden fixture (reference exact rerank, same checkpoint — see
   resources/colbert_golden/PROVENANCE.md): the 3 fixture queries over the 6
   fixture documents must reproduce every per-query RANKING exactly, with
   scores within 1e-3 (the prototype witnessed max |delta| 1e-5).

   Runs the REAL local checkpoint via -Dcolbert.model.path (the support
   fixture pins it); no Python process, no network."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.interface :as colbert]))

(use-fixtures :once support/with-model-path)

(def score-tolerance 1e-3)

(deftest rerank-reproduces-python-exact-rerank
  (let [golden (support/read-golden "python_scores.json")
        doc-text (get golden "documents")           ;; doc-id -> text
        text->doc (into {} (map (fn [[k v]] [v k])) doc-text)
        documents (vec (vals doc-text))]
    (doseq [[qid qtext] (get golden "queries")]
      (testing (str qid ": " qtext)
        (let [expected (->> (get-in golden ["scores" qid])
                            (sort-by #(get % "rank")))
              expected-order (mapv #(get % "doc") expected)
              results (colbert/rerank {} {:query qtext :documents documents})]
          (is (= (count documents) (count results)))
          (is (= expected-order (mapv (comp text->doc :content) results))
              (str qid ": per-query ranking must equal the Python exact rerank"))
          (doseq [[entry result] (map vector expected results)]
            (is (< (Math/abs (- (double (get entry "score"))
                                (double (:score result))))
                   score-tolerance)
                (str qid " " (get entry "doc")
                     ": python " (get entry "score")
                     " vs jvm " (:score result)))))))))
