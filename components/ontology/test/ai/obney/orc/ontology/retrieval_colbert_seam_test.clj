(ns ai.obney.orc.ontology.retrieval-colbert-seam-test
  "Slice 3, cycle 5 (ontology side): the retrieval->colbert seam contracts that
   the JVM-ColBERT switch must never break. No production change expected —
   these are regression pins.

   1. RESOLUTION: retrieval resolves the colbert entry points DYNAMICALLY by
      the exact interface symbols ai.obney.orc.colbert.interface/search-for-rrf
      and /search-for-rrf-batch (never a static require — the shipped
      orc-ontology project excludes colbert). Pin the symbols from this side so
      a rename on either side fails a test instead of silently degrading every
      hybrid-search to 2-signal.
   2. DEGRADATION: when the colbert layer THROWS (e.g. the loud
      :colbert-index-artifact-unreadable legacy ex-info), colbert-search-concepts
      (+ -batch) return nil — logged, no throw — and hybrid-search(-batch)
      still returns fused 2-signal results with no exception.

   Stubbing idiom follows retrieval_batch_test (canned per-query embedding
   results); the throwing colbert layer is stubbed at the colbert INTERFACE var
   so the seam's own try/catch is what's under test."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.orc.colbert.interface :as colbert]))

;; =============================================================================
;; 1. Resolution pins — the exact interface symbols retrieval depends on
;; =============================================================================

(deftest colbert-rrf-entry-points-resolve-by-exact-symbol
  (testing "the single-query entry point"
    (let [v (requiring-resolve 'ai.obney.orc.colbert.interface/search-for-rrf)]
      (is (some? v) "ai.obney.orc.colbert.interface/search-for-rrf must resolve")
      (is (fn? @v))))
  (testing "the batch entry point"
    (let [v (requiring-resolve 'ai.obney.orc.colbert.interface/search-for-rrf-batch)]
      (is (some? v) "ai.obney.orc.colbert.interface/search-for-rrf-batch must resolve")
      (is (fn? @v)))))

(deftest retrieval-resolves-the-same-vars-it-calls
  (testing "retrieval's private resolvers return exactly those interface vars"
    (is (= (resolve 'ai.obney.orc.colbert.interface/search-for-rrf)
           (#'retrieval/resolve-colbert-search-fn))
        "resolve-colbert-search-fn -> the exact search-for-rrf var")
    (is (= (resolve 'ai.obney.orc.colbert.interface/search-for-rrf-batch)
           (#'retrieval/resolve-colbert-search-batch-fn))
        "resolve-colbert-search-batch-fn -> the exact search-for-rrf-batch var")))

(deftest colbert-search-concepts-calls-through-the-resolved-var
  (testing "with-redefs on the interface var is seen by colbert-search-concepts
            (var-call semantics — the seam calls through the var, not a cached fn)"
    (let [captured (atom nil)]
      (with-redefs [colbert/search-for-rrf (fn [_ctx opts]
                                             (reset! captured opts)
                                             [{:uri "c:x" :score 1.0}])]
        (let [out (retrieval/colbert-search-concepts {} "the query"
                                                     :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                                                     :limit 7
                                                     :weight 2.0)]
          (is (= [{:uri "c:x" :score 1.0}] out))
          (is (= {:query "the query"
                  :index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                  :k 7
                  :normalize? true
                  :weight 2.0}
                 @captured)
              "the frozen opts contract: :query :index-id :k :normalize? :weight"))))))

;; =============================================================================
;; 2. Degradation pins — a throwing colbert layer never breaks the seam
;; =============================================================================

(def ^:private legacy-ex
  (ex-info "ColBERT index artifact unreadable at /tmp/x: no index-meta.json format marker"
           {:error :colbert-index-artifact-unreadable
            :path "/tmp/x"
            :remedy "rebuild"}))

(def ^:private index-id #uuid "00000000-0000-0000-0000-0000000000bb")

(def ^:private embedding-by-query
  {"q1" [{:uri "c:alpha" :similarity 0.9} {:uri "c:beta" :similarity 0.7}]
   "q2" [{:uri "c:gamma" :similarity 0.8} {:uri "c:delta" :similarity 0.6}]})

(deftest colbert-search-concepts-returns-nil-when-the-colbert-layer-throws
  (testing "single: nil, no throw"
    (with-redefs [colbert/search-for-rrf (fn [_ _] (throw legacy-ex))]
      (is (nil? (retrieval/colbert-search-concepts {} "q1"
                                                   :colbert-index-id index-id)))))
  (testing "batch: nil, no throw"
    (with-redefs [colbert/search-for-rrf-batch (fn [_ _] (throw legacy-ex))]
      (is (nil? (retrieval/colbert-search-concepts-batch {} ["q1" "q2"]
                                                         :colbert-index-id index-id))))))

(deftest hybrid-search-degrades-to-two-signals-when-colbert-throws
  (testing "hybrid-search with a legacy-throwing colbert layer: fused results,
            no exception, :colbert absent from :batches-used, no :colbert-rank"
    (with-redefs [retrieval/semantic-search-concepts
                  (fn [_ctx query-text & _] (get embedding-by-query query-text))
                  colbert/search-for-rrf (fn [_ _] (throw legacy-ex))]
      (let [out (retrieval/hybrid-search {} {:query-text "q1"
                                             :colbert-index-id index-id
                                             :signals #{:embedding :colbert}})]
        (is (map? out) "no exception escaped")
        (is (seq (:results out)) "fused results still come back")
        (is (= [:embedding] (:batches-used out)) "colbert contributed no batch")
        (is (every? #(nil? (:colbert-rank %)) (:results out)))
        (is (= [] (:colbert-results out)) "colbert-results empty, not poisoned"))))
  (testing "hybrid-search-batch: same degradation, aligned per query"
    (with-redefs [retrieval/semantic-search-concepts
                  (fn [_ctx query-text & _] (get embedding-by-query query-text))
                  colbert/search-for-rrf-batch (fn [_ _] (throw legacy-ex))]
      (let [out (retrieval/hybrid-search-batch {} {:query-texts ["q1" "q2"]
                                                   :colbert-index-id index-id
                                                   :signals #{:embedding :colbert}})]
        (is (= 2 (count out)) "one result-map per query, no exception")
        (doseq [per-query out]
          (is (seq (:results per-query)))
          (is (= [:embedding] (:batches-used per-query))))
        (is (not= (:results (nth out 0)) (:results (nth out 1)))
            "still aligned per query (distinct embedding signals)")))))
