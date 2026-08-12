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

(defn- reference-limit
  "The maximum_query_tokens the golden fixtures were captured at — recorded in
   token_ids.json alongside them (PROVENANCE.md covers both files). Read, not
   hard-coded, so the parity claim can never drift from the fixture."
  []
  (get (support/read-golden "token_ids.json") "query_maxlen"))

(deftest rerank-reproduces-python-exact-rerank
  ;; CC-17 made maximum_query_tokens configuration, so this parity assertion
  ;; now NAMES the configuration the reference was captured under (32) rather
  ;; than inheriting it from the checkpoint. The claim is unchanged: same
  ;; rankings, scores within 1e-3.
  (let [golden (support/read-golden "python_scores.json")
        doc-text (get golden "documents")           ;; doc-id -> text
        text->doc (into {} (map (fn [[k v]] [v k])) doc-text)
        documents (vec (vals doc-text))
        limit (reference-limit)]
    (doseq [[qid qtext] (get golden "queries")]
      (testing (str qid ": " qtext)
        (let [expected (->> (get-in golden ["scores" qid])
                            (sort-by #(get % "rank")))
              expected-order (mapv #(get % "doc") expected)
              results (colbert/rerank {} {:query qtext :documents documents
                                          :maximum-query-tokens limit})]
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

(deftest shipped-limit-preserves-top-1-on-the-golden-corpus
  ;; CC-17, MEASURED — not assumed. Raising maximum_query_tokens from the
  ;; reference 32 to the shipped default adds [MASK] query-expansion rows, and
  ;; on SHORT queries those rows carry a large share of the score. Witnessed on
  ;; the golden corpus at 464:
  ;;   q1 'arbitration clause'            [d1 d2 d3 d6 d5 d4] -> UNCHANGED
  ;;   q2 'termination notice period'     [d2 d1 d3 d4 d6 d5] -> [d2 d3 d1 d6 d5 d4]
  ;;   q3 'intellectual property ownersh' [d3 d1 d2 d5 d6 d4] -> UNCHANGED
  ;; So TOP-1 survives everywhere, but the tail ordering of a short query does
  ;; NOT. That is the pedestal cost made concrete; the production corpus has no
  ;; short queries (measured minimum 150 tokens), but this test pins the
  ;; guarantee that actually matters downstream and makes any further erosion
  ;; visible.
  (let [golden (support/read-golden "python_scores.json")
        doc-text (get golden "documents")
        text->doc (into {} (map (fn [[k v]] [v k])) doc-text)
        documents (vec (vals doc-text))
        rows (for [[qid qtext] (get golden "queries")]
               (let [expected (mapv #(get % "doc")
                                    (sort-by #(get % "rank") (get-in golden ["scores" qid])))
                     shipped (mapv (comp text->doc :content)
                                   (colbert/rerank {} {:query qtext :documents documents}))]
                 {:qid qid :expected expected :shipped shipped}))]
    (println "  [N] golden queries under test =" (count rows))
    (doseq [{:keys [qid expected shipped]} rows]
      (println (format "    %s python %s | shipped %s | full-order match %s"
                       qid (pr-str expected) (pr-str shipped) (= expected shipped)))
      (is (= (first expected) (first shipped))
          (str qid ": top-1 must survive the maximum_query_tokens change")))))
