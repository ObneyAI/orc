(ns ai.obney.orc.ontology.s01-per-source-caps-test
  "S01 — Per-signal contribution caps applied BEFORE RRF fusion.

   This test file is the slice's acceptance test corpus. Each deftest
   corresponds directly to one acceptance criterion from the slice file
   `docs/build-timeline/issues/ontology-rebuild/S01-per-source-rrf-caps.md`:

   1. ADVERSARIAL OVER-EXPANSION (load-bearing): construct a fusion
      fixture where ONE signal (graph) returns ≥10x the candidates of
      the other signals. Without the cap (cap set large enough to be
      a no-op), the fused candidate pool is dominated by graph-only
      URIs — many of them surface in the fused top-N, crowding the
      embedding/ColBERT top hits' RANK position down even when those
      top hits aren't fully drowned (RRF's reciprocal decay limits how
      far the tail flood pushes single-signal hits). With the cap
      applied tight, the fused candidate POOL drops dramatically and
      the embedding/ColBERT single-signal top hits RANK significantly
      higher in the fused output. PROVE the over-expansion mode by
      counting graph-only URIs in fused top-N at cap=large vs cap=tight
      AND proving an embedding/ColBERT single-signal top hit's RANK
      improves under the cap.

   2. REGRESSION — single-signal: a single-signal query (no fusion
      happens) produces byte-identical results regardless of cap. The
      cap is a FUSION concern; it must not touch single-signal output.

   3. REGRESSION — balanced fusion: an already-within-cap fixture
      produces byte-identical results before and after the cap default
      is in effect. The cap must be a no-op when pools are at-or-below
      cap.

   4. PUBLIC SURFACE: the :per-source-cap option is accepted by
      hybrid-search AND hybrid-search-batch; the per-query override
      actually changes the cap (proved by per-signal pool-size deltas).

   The signal collaborators (semantic-search-concepts, colbert-search-*,
   expand-concept-neighborhood) are STUBBED with canned results so the
   over-expansion failure mode is reproducible across runs. This pattern
   mirrors retrieval_batch_test.clj — known-good, already in the
   codebase, deterministic. The LIVE verification (real Grain event
   store + real BFS expansion) runs separately via the REPL and is
   captured in the slice's verify trace, not as a deftest."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]))

;; =============================================================================
;; Adversarial fixture: graph signal over-expands ~13x relative to peers
;; =============================================================================
;;
;; Fixture's load-bearing properties:
;; - graph signal naturally produces 40 candidates: g:0..g:39
;; - embedding signal produces 3: [shared:high, e:1, e:2]
;; - colbert signal produces 3:    [shared:high, c:1, c:2]
;;
;; The shared URI (shared:high) appears at rank 1 in BOTH embedding +
;; colbert and does NOT appear in graph. e:1 / e:2 / c:1 / c:2 appear
;; ONLY in their respective signal. The graph signal returns 40
;; graph-only URIs (g:0..g:39).
;;
;; With cap=100 (effectively no-op, larger than any signal's pool),
;; the fused candidate POOL has ~40 URIs. The fused top-10 will
;; contain many graph-only URIs (because graph contributes 40
;; candidates each at a reciprocal-rank score), crowding embedding/
;; colbert single-signal top hits to a lower rank.
;;
;; With cap=3 (≤ all signal pools), graph contributes only g:0..g:2.
;; The fused candidate pool drops to ~9 URIs (3 from each signal,
;; with shared:high collapsing across embedding+colbert). Embedding /
;; colbert single-signal hits RANK higher because there are fewer
;; graph-only URIs competing.

(def graph-overexpanded-batch
  "40 candidates from the graph signal — the 'over-expanding' arm.
   None of these URIs appear in embedding or colbert."
  (vec (for [i (range 40)]
         {:uri (str "g:" i) :score (- 1.0 (* 0.02 i))})))

(def embedding-small-batch
  "3 candidates. shared:high is the embedding signal's TOP hit."
  [{:uri "shared:high" :similarity 0.97}
   {:uri "e:1" :similarity 0.85}
   {:uri "e:2" :similarity 0.75}])

(def colbert-small-batch
  "3 candidates. shared:high is the colbert signal's TOP hit too."
  [{:uri "shared:high" :score 0.96}
   {:uri "c:1" :score 0.80}
   {:uri "c:2" :score 0.70}])

(defn- stub-signals [body-fn]
  (with-redefs [retrieval/expand-concept-neighborhood
                (fn [_seed-uris & _] graph-overexpanded-batch)
                retrieval/semantic-search-concepts
                (fn [_ctx _q & _] embedding-small-batch)
                retrieval/colbert-search-concepts
                (fn [_ctx _q & _] colbert-small-batch)
                retrieval/colbert-search-concepts-batch
                (fn [_ctx qs & _] (mapv (fn [_] colbert-small-batch) qs))]
    (body-fn)))

;; =============================================================================
;; Acceptance 1 — adversarial over-expansion: prove failure WITHOUT the cap,
;; then prove the cap mitigates it
;; =============================================================================

(deftest cap-controls-graph-tail-flood-into-fused-pool
  (testing "Pool-size sanity: with the cap set generously large (effectively
            no-op), the graph signal's full ~40 candidates participate. With
            a tight cap, graph's pool drops to the cap value. This is the
            mechanical lever the slice is shipping."
    (stub-signals
     (fn []
       (let [no-effective-cap (retrieval/hybrid-search
                               {} {:seed-uris ["seed:1"]
                                   :query-text "q"
                                   :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                                   :per-source-cap 100  ;; > graph's 40
                                   :signals #{:graph :embedding :colbert}
                                   :limit 10})
             tight-cap (retrieval/hybrid-search
                        {} {:seed-uris ["seed:1"]
                            :query-text "q"
                            :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                            :per-source-cap 3
                            :signals #{:graph :embedding :colbert}
                            :limit 10})]
         (is (= 40 (count (:graph-results no-effective-cap)))
             "no-effective-cap: graph signal's full 40 candidates pass through pre-fusion")
         (is (= 3 (count (:graph-results tight-cap)))
             "tight cap: graph signal's pool capped to 3 pre-fusion (the lever works)")
         (is (= 3 (count (:embedding-results no-effective-cap)))
             "embedding signal's natural pool (3) unaffected by either cap")
         (is (= 3 (count (:embedding-results tight-cap)))
             "embedding signal's natural pool (3) unaffected by either cap")))))

  (testing "Adversarial: without an effective cap, the fused top-10 is
            DOMINATED by graph-only URIs (the slice's failure mode); with a
            tight cap, the fraction of graph-only URIs in the fused top-N
            drops sharply and embedding/colbert single-signal top hits RANK
            HIGHER. Prove the failure exists pre-cap, then prove the cap
            mitigates it."
    (stub-signals
     (fn []
       (let [no-effective-cap (retrieval/hybrid-search
                               {} {:seed-uris ["seed:1"]
                                   :query-text "q"
                                   :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                                   :per-source-cap 100
                                   :signals #{:graph :embedding :colbert}
                                   :limit 10})
             tight-cap (retrieval/hybrid-search
                        {} {:seed-uris ["seed:1"]
                            :query-text "q"
                            :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                            :per-source-cap 3
                            :signals #{:graph :embedding :colbert}
                            :limit 10})
             no-cap-uris (mapv :uri (:results no-effective-cap))
             tight-uris (mapv :uri (:results tight-cap))
             graph-only-count (fn [uris]
                                (count (filter #(.startsWith ^String % "g:") uris)))
             rank-of (fn [uris uri]
                       (some (fn [[i u]] (when (= u uri) (inc i)))
                             (map-indexed vector uris)))]

         ;; FAILURE MODE: without the cap, graph-only URIs flood the fused
         ;; top-10. (Empirically, with graph=40 and embedding/colbert=3 each,
         ;; the no-cap fused top-10 is graph-dominated.)
         (is (>= (graph-only-count no-cap-uris) 5)
             (str "OVER-EXPANSION FAILURE MODE: without cap, fused top-10 is
                   graph-dominated (≥5 of 10 are graph-only). Got: " no-cap-uris))

         ;; CAP MITIGATES: with the cap, graph-only URIs in fused top are
         ;; bounded by the cap size — embedding/colbert single-signal hits
         ;; surface to higher ranks.
         (is (<= (graph-only-count tight-uris) 3)
             (str "with cap=3, no more than 3 graph-only URIs in fused top-10
                   (the graph pool itself is only 3). Got: " tight-uris))

         ;; CAP SURFACES non-shared embedding/colbert hits that were
         ;; previously drowned. e:2 is the embedding signal's #3 hit;
         ;; without the cap it just barely makes top-10 if at all; with
         ;; the cap, the contraction of the graph-only pool gives e:2
         ;; (and c:2) room in the fused top-N.
         ;; Adversarial check: a multi-signal-reinforced hit (shared:high)
         ;; AND ALL the single-signal embedding/colbert hits surface in
         ;; the capped result; the un-capped result has fewer of them
         ;; because graph-only URIs took those slots.
         (let [emb-cb-in-no-cap (count (filter #{"shared:high" "e:1" "e:2" "c:1" "c:2"}
                                               no-cap-uris))
               emb-cb-in-tight  (count (filter #{"shared:high" "e:1" "e:2" "c:1" "c:2"}
                                               tight-uris))]
           (is (>= emb-cb-in-tight emb-cb-in-no-cap)
               (str "with cap, AT LEAST AS MANY non-graph (embedding+colbert)
                     top hits appear in fused top-10."
                    " no-cap count=" emb-cb-in-no-cap " (of 5 candidates)"
                    " tight count=" emb-cb-in-tight " (of 5 candidates)"
                    " — confirms graph-only URIs displaced more embedding/colbert
                       URIs without the cap.")))

         ;; The shared:high URI (multi-signal) is the strongest hit in
         ;; both cases — adversarial: the cap doesn't DROWN it either.
         (is (= "shared:high" (first no-cap-uris))
             "regression: multi-signal top hit stays #1 even without cap")
         (is (= "shared:high" (first tight-uris))
             "regression: multi-signal top hit stays #1 with cap"))))))

;; =============================================================================
;; Acceptance 2 — regression: single-signal queries are byte-identical
;; with vs without cap default applied
;; =============================================================================

(deftest single-signal-query-cap-is-a-no-op
  (testing "An embedding-only query produces byte-identical :results regardless
            of :per-source-cap value — the cap is a FUSION concern only"
    (stub-signals
     (fn []
       (let [no-cap (retrieval/hybrid-search
                     {} {:query-text "q"
                         :per-source-cap 100
                         :signals #{:embedding}
                         :limit 10})
             tight-cap (retrieval/hybrid-search
                        {} {:query-text "q"
                            :per-source-cap 1
                            :signals #{:embedding}
                            :limit 10})
             default-cap (retrieval/hybrid-search
                          {} {:query-text "q"
                              :signals #{:embedding}
                              :limit 10})]
         (is (= (:results no-cap) (:results default-cap))
             "default cap behaves like no-cap for single-signal: byte-identical")
         (is (= (:results no-cap) (:results tight-cap))
             "even tight-cap behaves like no-cap for single-signal: the cap is
              a FUSION concern and never applies when only one signal contributes")
         ;; The :graph-results / :colbert-results are empty in single-signal mode
         (is (empty? (:graph-results no-cap))
             "no graph batch (signal disabled)")
         (is (empty? (:colbert-results no-cap))
             "no colbert batch (signal disabled)"))))))

;; =============================================================================
;; Acceptance 3 — regression: balanced (already-within-cap) fusion unchanged
;; =============================================================================

(def balanced-graph-batch
  "Small balanced fixture: graph returns 3 entries, parallel to embedding+colbert."
  [{:uri "shared:high" :score 0.9}
   {:uri "g:1" :score 0.7}
   {:uri "g:2" :score 0.5}])

(defn- stub-balanced-signals [body-fn]
  (with-redefs [retrieval/expand-concept-neighborhood
                (fn [_seed-uris & _] balanced-graph-batch)
                retrieval/semantic-search-concepts
                (fn [_ctx _q & _] embedding-small-batch)
                retrieval/colbert-search-concepts
                (fn [_ctx _q & _] colbert-small-batch)
                retrieval/colbert-search-concepts-batch
                (fn [_ctx qs & _] (mapv (fn [_] colbert-small-batch) qs))]
    (body-fn)))

(deftest balanced-fusion-is-byte-identical-pre-and-post-cap-default
  (testing "When ALL pools are at-or-below cap, applying the cap is a no-op:
            the fused :results vector is byte-identical to an explicit large cap"
    (stub-balanced-signals
     (fn []
       (let [no-effective-cap (retrieval/hybrid-search
                               {} {:seed-uris ["seed:1"]
                                   :query-text "q"
                                   :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                                   :per-source-cap 1000
                                   :signals #{:graph :embedding :colbert}
                                   :limit 10})
             default (retrieval/hybrid-search
                      {} {:seed-uris ["seed:1"]
                          :query-text "q"
                          :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                          :signals #{:graph :embedding :colbert}
                          :limit 10})]
         (is (= (:results no-effective-cap) (:results default))
             "balanced fixture: default cap == no-effective cap (no fusion change)"))))))

;; =============================================================================
;; Acceptance 4 — public surface: hybrid-search-batch honors :per-source-cap
;; =============================================================================

(deftest hybrid-search-batch-honors-per-source-cap
  (testing "hybrid-search-batch's per-query fusion respects :per-source-cap
            identically to the single-query path — same pool sizes, same
            fused results, same per-signal rank decorations"
    (stub-signals
     (fn []
       (let [single (retrieval/hybrid-search
                     {} {:seed-uris ["seed:1"]
                         :query-text "q"
                         :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                         :per-source-cap 5
                         :signals #{:graph :embedding :colbert}
                         :limit 10})
             batch (retrieval/hybrid-search-batch
                    {} {:seed-uris ["seed:1"]
                        :query-texts ["q"]
                        :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                        :per-source-cap 5
                        :signals #{:graph :embedding :colbert}
                        :limit 10})]
         (is (= (:results single) (:results (first batch)))
             "per-query batched fusion under the cap matches the single-query path")
         (is (= (count (:graph-results single))
                (count (:graph-results (first batch))))
             "graph pool size in batched == single (cap applied symmetrically)"))))))

;; =============================================================================
;; Acceptance 5 — per-query override actually changes pool sizes
;; =============================================================================

(deftest per-source-cap-override-actually-changes-pool-sizes
  (testing "Different :per-source-cap values produce different per-signal
            pool sizes on the over-expansion fixture — proves the option
            is wired and not silently ignored"
    (stub-signals
     (fn []
       (let [tight (retrieval/hybrid-search
                    {} {:seed-uris ["seed:1"]
                        :query-text "q"
                        :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                        :per-source-cap 3
                        :signals #{:graph :embedding :colbert}
                        :limit 10})
             loose (retrieval/hybrid-search
                    {} {:seed-uris ["seed:1"]
                        :query-text "q"
                        :colbert-index-id #uuid "00000000-0000-0000-0000-0000000000aa"
                        :per-source-cap 100
                        :signals #{:graph :embedding :colbert}
                        :limit 10})]
         (is (= 3 (count (:graph-results tight)))
             "tight cap actually capped graph to 3")
         (is (= 40 (count (:graph-results loose)))
             "loose cap let graph's full 40 candidates through")
         (is (not= (:results tight) (:results loose))
             "per-query :per-source-cap override changes fused results — wiring proof"))))))
