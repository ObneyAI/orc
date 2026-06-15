(ns ai.obney.orc.ontology.v15-label-enrichment-test
  "V15 — hybrid-search result label enrichment from the EVENT-SOURCED projection.

   Root-cause fix for the V02 Mode-A early-read finding (`:label nil` on
   hybrid-search hits over a real event-sourced graph). The result-assembly
   path (`fuse-and-enrich`) enriched `:label`/`:description` ONLY via the
   static in-memory concept store (`static/get-concept-by-uri`). For concepts
   created through the event-sourcing command path (the real substrate — V02's
   graph, any consumer build), that static store is empty, so the lookup
   returned nil and results came back `:label nil` / `:description nil`.

   The fix: enrich result maps from the event-sourced concepts projection
   (the same `rm/get-concepts` source the rest of the substrate uses for
   concept state) when a ctx with an event-store is available; fall back to
   the static store only when there is no event-sourced source. This is the
   enrichment-SOURCE correctness fix — NOT a hardcoded label table and NOT
   URI-string munging.

   These tests run through a REAL Grain in-memory event store (via
   with-test-context); concepts are created via the public
   :ontology/create-concept command path. No synthesized projection fixtures."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]))

(def section-a #uuid "f1500000-0000-0000-0000-0000000015a0")

(defn- seed-concept! [ctx ontology-id uri label description]
  (h/run-and-apply! ctx
    (fn [c]
      (cmd/ontology-create-concept
       (assoc c :command
              (h/make-concept-data
               :ontology-id ontology-id
               :uri uri
               :label label
               :description description
               :scope :custom))))))

(defn- seed-graph! [ctx]
  ;; A small REAL event-sourced graph: a director, a film, and a "directed"
  ;; edge so a graph-BFS hit (NOT a lexical hit) is reachable.
  (seed-concept! ctx section-a "concept:dir/ava" "Ava Director"
                 "An acclaimed film director")
  (seed-concept! ctx section-a "concept:film/selma" "Selma"
                 "A historical drama film")
  (h/run-and-apply! ctx
    (fn [c]
      (cmd/ontology-create-relationship
       (assoc c :command {:source-uri "concept:dir/ava"
                          :predicate "directed"
                          :target-uri "concept:film/selma"
                          :properties {}})))))

;; =============================================================================
;; AC1 — hybrid-search over an event-sourced graph returns non-nil
;;       :label + :description on the hits (the V02 bug).
;; =============================================================================

(deftest event-sourced-hits-carry-non-nil-label-and-description
  (h/with-test-context [ctx]
    (seed-graph! ctx)
    (let [r (retrieval/hybrid-search ctx {:query-text "Ava Director"
                                          :ontology-ids [section-a]})
          results (:results r)]
      (testing "at least one result returned"
        (is (seq results)))
      (testing "EVERY result carries a non-nil label resolved from the projection"
        (is (every? (comp some? :label) results)
            (str "some result had :label nil — "
                 (pr-str (map (juxt :uri :label) results)))))
      (testing "EVERY result carries a non-nil description resolved from the projection"
        (is (every? (comp some? :description) results)
            (str "some result had :description nil — "
                 (pr-str (map (juxt :uri :description) results))))))))

;; =============================================================================
;; AC2 — the enriched label/description match the concept's ACTUAL projected
;;       values (not a URI-derived placeholder, not a static stand-in).
;; =============================================================================

(deftest enriched-label-matches-the-projected-concept
  (h/with-test-context [ctx]
    (seed-graph! ctx)
    (let [r (retrieval/hybrid-search ctx {:query-text "Ava Director"
                                          :ontology-ids [section-a]})
          by-uri (into {} (map (juxt :uri identity) (:results r)))
          ava (get by-uri "concept:dir/ava")]
      (testing "ava is among the hits"
        (is (some? ava) (str "ava not found in " (pr-str (keys by-uri)))))
      (testing "label is the exact projected label"
        (is (= "Ava Director" (:label ava))))
      (testing "description is the exact projected description"
        (is (= "An acclaimed film director" (:description ava)))))))

;; =============================================================================
;; AC3 — a graph-BFS-reached hit (NOT a lexical hit) is ALSO enriched.
;;       This is the core of the bug: the lexical signal already carried its
;;       own labels; the graph/embedding/colbert signals did not. The film is
;;       reached only via the "directed" edge from the lexical seed, so its
;;       label can only come from the event-sourced projection enrichment.
;; =============================================================================

(deftest graph-reached-hit-is-enriched-from-projection
  (h/with-test-context [ctx]
    (seed-graph! ctx)
    (let [r (retrieval/hybrid-search ctx {:query-text "Ava Director"
                                          :ontology-ids [section-a]
                                          :max-depth 2})
          by-uri (into {} (map (juxt :uri identity) (:results r)))
          selma (get by-uri "concept:film/selma")]
      (testing "the film was reached via graph BFS (graph-rank present, no lexical-rank)"
        (is (some? selma)
            (str "selma not reached via BFS — " (pr-str (keys by-uri))))
        (is (some? (:graph-rank selma))
            "selma should carry a graph-rank (reached via the directed edge)")
        (is (nil? (:lexical-rank selma))
            "selma is NOT a lexical match — it must be enriched from the projection, not the lexical hit"))
      (testing "the graph-reached hit still carries its projected label + description"
        (is (= "Selma" (:label selma)))
        (is (= "A historical drama film" (:description selma)))))))

;; =============================================================================
;; AC4 — regression guard: the static-store-backed path stays labeled.
;;       With NO event-store on the ctx, enrichment must fall back to the
;;       static corpus, which carries the seed-corpus concepts' labels. We
;;       seed graph BFS directly from a known static URI so the static
;;       enrichment is exercised without needing embeddings/event-store.
;; =============================================================================

(deftest static-backed-path-stays-labeled
  ;; No event-store: a bare ctx forces the static-corpus fallback in both the
  ;; graph build and the enrichment. The static seed corpus carries real
  ;; concepts (e.g. the success/problem/failure taxonomy roots) with labels.
  (let [ctx {}
        r (retrieval/hybrid-search ctx {:seed-uris ["success:Root"]
                                        :signals #{:graph}
                                        :max-depth 1})
        results (:results r)]
    (testing "static-backed graph BFS still returns results"
      (is (seq results)
          "expected static-corpus BFS hits from success:Root"))
    (testing "the static-backed hits stay labeled (no regression)"
      (is (every? (comp some? :label) results)
          (str "static path lost labels — "
               (pr-str (map (juxt :uri :label) results)))))))
