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
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.orc.ontology.core.static-ontology :as static]))

;; Canned, per-query signal results. Distinct per query so any cross-wiring shows up.
(def embedding-by-query
  {"q1" [{:uri "c:alpha" :similarity 0.9} {:uri "c:beta" :similarity 0.7}]
   "q2" [{:uri "c:gamma" :similarity 0.8} {:uri "c:delta" :similarity 0.6}]})

(def colbert-by-query
  {"q1" [{:uri "c:beta" :score 0.95} {:uri "c:alpha" :score 0.40}]
   "q2" [{:uri "c:delta" :score 0.88} {:uri "c:epsilon" :score 0.30}]})

(defn- with-stubbed-signals [f]
  (with-redefs [;; single-query embedding search (used by hybrid-search and the batch)
                retrieval/semantic-search-concepts
                (fn [_ctx query-text & _] (get embedding-by-query query-text))
                ;; single-query ColBERT (used by hybrid-search)
                retrieval/colbert-search-concepts
                (fn [_ctx query-text & _] (get colbert-by-query query-text))
                ;; batched ColBERT (used by hybrid-search-batch) — one call, aligned vector
                retrieval/colbert-search-concepts-batch
                (fn [_ctx query-texts & _] (mapv #(get colbert-by-query %) query-texts))]
    (f)))

(deftest batch-returns-one-aligned-result-map-per-query
  (testing "hybrid-search-batch returns a vector aligned to :query-texts"
    (with-stubbed-signals
      (fn []
        (let [out (retrieval/hybrid-search-batch
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
              single-q1 (retrieval/hybrid-search {} (assoc opts :query-text "q1"))
              single-q2 (retrieval/hybrid-search {} (assoc opts :query-text "q2"))
              batch (retrieval/hybrid-search-batch {} (assoc opts :query-texts ["q1" "q2"]))]
          (is (= (:results single-q1) (:results (nth batch 0)))
              "q1 batched fusion == q1 single fusion")
          (is (= (:results single-q2) (:results (nth batch 1)))
              "q2 batched fusion == q2 single fusion")
          ;; alignment guard: q1's results must NOT equal q2's (distinct signals)
          (is (not= (:results (nth batch 0)) (:results (nth batch 1)))
              "the two queries produce distinct results (no cross-wiring)"))))))

(deftest batch-embedding-only-skips-colbert-entirely
  (testing "with signals #{:embedding}, the batch never touches the ColBERT collaborator"
    (let [colbert-called? (atom false)]
      (with-redefs [retrieval/semantic-search-concepts
                    (fn [_ctx query-text & _] (get embedding-by-query query-text))
                    retrieval/colbert-search-concepts-batch
                    (fn [& _] (reset! colbert-called? true) nil)]
        (let [single (retrieval/hybrid-search {} {:query-text "q1" :signals #{:embedding}})
              batch (retrieval/hybrid-search-batch {} {:query-texts ["q1"] :signals #{:embedding}})]
          (is (false? @colbert-called?) "no ColBERT batch call when its signal is off")
          (is (= (:results single) (:results (first batch)))
              "embedding-only fusion is identical batched vs single"))))))

;; --- PERF: fetch concept embeddings ONCE per batch (not per query) -----------

(deftest batch-fetches-concept-embeddings-once
  (testing "hybrid-search-batch materializes the concept embeddings ONCE for the whole
            batch (a shared delay), not once per query — the O(queries) read-model fetch
            that dominated whole-transcript detection"
    (let [fetches (atom 0)]
      (with-redefs [;; count real fetches; the per-query path must NOT re-fetch
                    rm/get-all-concept-embeddings
                    (fn [& _] (swap! fetches inc) {"c:a" {:embedding [0.1] :ontology-id "o"}})
                    ;; mimic the REAL per-query call: it force's the passed :concept-embeddings
                    retrieval/semantic-search-concepts
                    (fn [_ctx q & {:keys [concept-embeddings]}]
                      (force concept-embeddings)        ; deref the shared delay (memoized)
                      (get embedding-by-query q))
                    retrieval/colbert-search-concepts-batch (fn [& _] nil)]
        (retrieval/hybrid-search-batch {} {:query-texts ["q1" "q2"] :signals #{:embedding}})
        (is (= 1 @fetches)
            "concept embeddings fetched exactly once for a 2-query batch (was 1 per query)")))))

(deftest semantic-search-uses-provided-concept-embeddings-skips-fetch
  (testing "when :concept-embeddings is supplied (value or delay), semantic-search-concepts
            uses it and does NOT hit the read-model fetch — the seam the batch relies on"
    (with-redefs [rm/get-all-concept-embeddings
                  (fn [& _] (throw (ex-info "must not fetch when embeddings are provided" {})))
                  embedding/embed-text (fn [& _] [0.1 0.2 0.3])
                  embedding/search-concepts-by-embedding
                  (fn [_q ces & _] (mapv (fn [[uri _]] {:uri uri :similarity 0.9}) ces))
                  static/get-concept-by-uri (fn [uri] {:label (str "L:" uri) :description "" :scope :x})]
      (let [out (retrieval/semantic-search-concepts
                 {} "q" :concept-embeddings {"c:a" {:embedding [0.1]}})]
        (is (= ["c:a"] (map :uri out)) "used the provided embeddings; no fetch (no throw)")))))
