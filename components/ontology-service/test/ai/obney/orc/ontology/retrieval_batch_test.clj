(ns ai.obney.orc.ontology.retrieval-batch-test
  "TDD for retrieval/hybrid-search-batch — the batched hybrid search that powers
   whole-transcript semantic detection (one ColBERT pass, index loaded once).

   The ColBERT batching itself is verified LIVE against a real index in the
   workshop (real bridge, real PLAID index — no mocks of the thing under test).
   Here we prove the DETERMINISTIC contract: the batch returns one result-map per
   query, correctly ALIGNED, and its per-query fusion is substance-identical to the
   single-query hybrid-search. The two in-JVM collaborators (embedding concept
   search + the batched ColBERT search) are stubbed with canned per-query results
   so a mis-alignment would surface as a mismatch."
  (:require [ai.obney.orc.ontology.core.queries :as queries]
            [ai.obney.orc.ontology.core.logic :as logic]
            [clojure.test :refer [deftest testing is]]))

(def embedding-by-query
  {"q1" [{:uri "c:alpha" :similarity 0.9} {:uri "c:beta" :similarity 0.7}]
   "q2" [{:uri "c:gamma" :similarity 0.8} {:uri "c:delta" :similarity 0.6}]})

(def colbert-by-query
  {"q1" [{:uri "c:beta" :score 0.95} {:uri "c:alpha" :score 0.40}]
   "q2" [{:uri "c:delta" :score 0.88} {:uri "c:epsilon" :score 0.30}]})

(defn- with-stubbed-signals [f]
  (with-redefs [
                queries/retrieval-semantic-search-concepts
                (fn [_ctx query-text & _] (get embedding-by-query query-text))
                logic/colbert-search-concepts
                (fn [_ctx query-text & _] (get colbert-by-query query-text))
                logic/colbert-search-concepts-batch
                (fn [_ctx query-texts & _] (mapv #(get colbert-by-query %) query-texts))]
    (f)))

(deftest batch-returns-one-aligned-result-map-per-query
  (testing "hybrid-search-batch returns a vector aligned to :query-texts"
    (with-stubbed-signals
      (fn []
        (let [out (queries/hybrid-search-batch
                   {} {:query-texts ["q1" "q2"]
                       :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                       :signals #{:embedding :colbert}})]
          (is (= 2 (count out)) "one result-map per query")
          (is (every? #(contains? % :results) out) "each is a hybrid-search result-map"))))))

(deftest batch-per-query-is-substance-identical-to-single-hybrid-search
  (testing "each batched per-query result equals the single-query hybrid-search — same fusion, correctly aligned"
    (with-stubbed-signals
      (fn []
        (let [opts {:colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                    :signals #{:embedding :colbert}}
              single-q1 (queries/hybrid-search {} (assoc opts :query-text "q1"))
              single-q2 (queries/hybrid-search {} (assoc opts :query-text "q2"))
              batch (queries/hybrid-search-batch {} (assoc opts :query-texts ["q1" "q2"]))]
          (is (= (:results single-q1) (:results (nth batch 0)))
              "q1 batched fusion == q1 single fusion")
          (is (= (:results single-q2) (:results (nth batch 1)))
              "q2 batched fusion == q2 single fusion")
          (is (not= (:results (nth batch 0)) (:results (nth batch 1)))
              "the two queries produce distinct results (no cross-wiring)"))))))

(deftest batch-embedding-only-skips-colbert-entirely
  (testing "with signals #{:embedding}, the batch never touches the ColBERT collaborator"
    (let [colbert-called? (atom false)]
      (with-redefs [queries/retrieval-semantic-search-concepts
                    (fn [_ctx query-text & _] (get embedding-by-query query-text))
                    logic/colbert-search-concepts-batch
                    (fn [& _] (reset! colbert-called? true) nil)]
        (let [single (queries/hybrid-search {} {:query-text "q1" :signals #{:embedding}})
              batch (queries/hybrid-search-batch {} {:query-texts ["q1"] :signals #{:embedding}})]
          (is (false? @colbert-called?) "no ColBERT batch call when its signal is off")
          (is (= (:results single) (:results (first batch)))
              "embedding-only fusion is identical batched vs single"))))))
