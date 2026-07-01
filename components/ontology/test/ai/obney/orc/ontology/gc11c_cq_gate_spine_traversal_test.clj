(ns ai.obney.orc.ontology.gc11c-cq-gate-spine-traversal-test
  "GC-11c — the CQ-gate retrieval spans the cross-source SPINE (the finish line).

   The GC-11b spine joins concepts from DIFFERENT sources through canonical
   CODE NODES via a domain-agnostic `identified-by` predicate. The cross-source
   path program→occupation runs FOUR hops through TWO code-node bridges and a
   middle connector concept:

     program  --identified-by-->        code/cipA
     crosswalk --identified-by-->       code/cipA   (so program meets crosswalk at cipA)
     crosswalk --identified-by-->       code/socB
     occupation --identified-by-->      code/socB   (so occupation meets crosswalk at socB)

   i.e.  program → code/cipA → crosswalk → code/socB → occupation  (4 hops).

   The CQ-gate (`cq_runner.clj` evaluate-cqs!) retrieves evidence via
   `hybrid-search`, whose graph signal is `bfs-spreading-activation` (graph.clj)
   over this spine. The ONE risk this slice verifies (NO assumptions): the BFS
   `:max-depth` defaults to 2-3 and the per-hop activation decays, so a SINGLE
   seed from the program side does NOT reach the occupation end. The gate's
   retrieval, however, seeds from MULTIPLE query-relevant concepts (both the
   program side AND the occupation side of the question), which MEET IN THE
   MIDDLE — each within reach of the code-node bridges. This file PROVES, on a
   synthetic spine via the REAL `graph/concepts->graph` + `graph/bfs-
   spreading-activation`, that:

     1. a single-seed BFS at the default depth does NOT span the spine (the risk
        is real — the test is load-bearing), AND
     2. a MULTI-seed BFS (both ends) DOES surface BOTH ends + BOTH code-node
        bridges + the middle connector at the default depth — the gate can
        answer the cross-source question.

   PURE — no Grain, no LLM. The `identified-by` predicate is GENERAL/STRUCTURAL
   (an unknown predicate getting the default 0.5 weight in graph.clj); NO baked
   CIP/SOC shape — the URIs here are synthetic stand-ins (Discipline 12)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.graph :as graph]))

;; =============================================================================
;; Spine fixture — the read-model lands a non-SKOS predicate like `identified-by`
;; in :typed-edges (read_models.clj default branch). `concepts->graph` emits a
;; BIDIRECTIONAL edge per typed-edge (default 0.5 weight for the unknown
;; predicate). So this {uri -> concept} map is EXACTLY what the gate's graph
;; signal traverses after a real GC-11b build lands the spine.
;; =============================================================================

(def ^:private spine-concepts
  {"program/p1"    {:uri "program/p1"    :label "Source-A Program"
                    :typed-edges {"identified-by" #{"code/cipA"}}}
   "crosswalk/c1"  {:uri "crosswalk/c1"  :label "Source-B Crosswalk Row"
                    :typed-edges {"identified-by" #{"code/cipA" "code/socB"}}}
   "occupation/o1" {:uri "occupation/o1" :label "Source-C Occupation"
                    :typed-edges {"identified-by" #{"code/socB"}}}
   ;; the two canonical CODE NODES (the bridges)
   "code/cipA"     {:uri "code/cipA"     :label "Canonical Code A"}
   "code/socB"     {:uri "code/socB"     :label "Canonical Code B"}})

(def ^:private spine-graph (graph/concepts->graph spine-concepts))

(defn- reachable-uris
  "Run the REAL BFS at the given depth and return the SET of URIs surfaced."
  [seed-uris depth]
  (->> (graph/bfs-spreading-activation
        spine-graph seed-uris {:max-depth depth :decay 0.5 :min-activation 0.01})
       (map :uri)
       set))

;; =============================================================================
;; Cycle 1a — the spine edges ARE bidirectionally traversable `identified-by`
;; edges with the default unknown-predicate weight (the mechanism premise).
;; =============================================================================

(deftest spine-identified-by-edges-are-bidirectional-default-weight
  (testing "concepts->graph emits a bidirectional `identified-by` edge per spine
            typed-edge, weighted with graph.clj's default-for-unknown 0.5 — so the
            spine is traversable BOTH directions without any baked predicate"
    (let [prog-out (get (:edges spine-graph) "program/p1")
          code-out (get (:edges spine-graph) "code/cipA")]
      (is (= [{:to "code/cipA" :predicate "identified-by" :weight 0.5}]
             (vec prog-out))
          "program→code edge carries the identified-by predicate at default 0.5")
      (is (some #(and (= "program/p1" (:to %)) (:reverse %)) code-out)
          "the REVERSE code→program edge exists (bidirectional) so a seed on the
           code/occupation side can reach back toward the program side"))))

;; =============================================================================
;; Cycle 1b — THE RISK (load-bearing RED-direction): a SINGLE program-side seed
;; at the gate's default BFS depth does NOT span to the occupation end. If the
;; gate seeded from only one end, the cross-source question would be unanswerable.
;; =============================================================================

(deftest single-seed-at-default-depth-does-not-span-the-spine
  (testing "a SINGLE program-side seed at the default BFS depth (2) reaches the
            near code-node + the middle connector, but NOT the occupation end —
            so single-seed retrieval can NOT answer the cross-source question.
            This is the risk the multi-seed gate must overcome."
    (let [reached (reachable-uris ["program/p1"] 2)]
      (is (contains? reached "code/cipA")
          "the near bridge IS within reach (1 hop)")
      (is (contains? reached "crosswalk/c1")
          "the middle connector IS within reach (2 hops)")
      (is (not (contains? reached "occupation/o1"))
          "the occupation END is NOT surfaced from a lone program seed at depth 2
           — the cross-source span fails single-seeded (the real risk)")
      (is (not (contains? reached "code/socB"))
          "even the FAR bridge is beyond reach single-seeded at depth 2"))))

;; =============================================================================
;; Cycle 1c — GREEN: the gate's MULTI-seed retrieval (BOTH ends) MEETS IN THE
;; MIDDLE at the default depth and surfaces the WHOLE cross-source path — both
;; ends + both code-node bridges + the middle connector. The judge then has the
;; full spine to answer the cross-source competency question.
;; =============================================================================

(deftest multi-seed-both-ends-spans-the-spine-at-default-depth
  (testing "seeding from BOTH ends (program side AND occupation side — what the
            gate's lexical+embedding retrieval does for a cross-source CQ) MEETS
            IN THE MIDDLE at the default depth: both ends, both code-node bridges,
            and the middle connector are ALL surfaced — the cross-source path is
            spanned and the judge can answer"
    (let [reached (reachable-uris ["program/p1" "occupation/o1"] 2)]
      (is (= #{"program/p1" "occupation/o1" "code/cipA" "code/socB" "crosswalk/c1"}
             reached)
          "the ENTIRE spine is surfaced multi-seeded at depth 2: both ends, the
           code-node bridge A, the code-node bridge B, and the middle connector")
      ;; The load-bearing cross-source assertions, named explicitly:
      (is (and (contains? reached "program/p1") (contains? reached "occupation/o1"))
          "BOTH cross-source ENDS are present")
      (is (and (contains? reached "code/cipA") (contains? reached "code/socB"))
          "BOTH code-node BRIDGES are present")
      (is (contains? reached "crosswalk/c1")
          "the MIDDLE connector that links bridge-A to bridge-B is present"))))

;; =============================================================================
;; Cycle 1d — the RED revert guard: drop the occupation-side seed back to a lone
;; program seed and the far end / far bridge become unreachable at the SAME depth.
;; This is what makes 1c load-bearing: the spanning is the MULTI-seed property,
;; not an artifact of the depth. (No depth tune is needed — the meet-in-the-middle
;; is sufficient at the default depth; this guards against a future regression
;; that would silently lose the multi-seed seeding.)
;; =============================================================================

(deftest reverting-to-single-seed-loses-the-cross-source-end
  (testing "at the SAME default depth, removing the occupation-side seed loses the
            occupation end AND the far bridge — proving the span in 1c is the
            multi-seed meet-in-the-middle, not the depth alone"
    (let [multi  (reachable-uris ["program/p1" "occupation/o1"] 2)
          single (reachable-uris ["program/p1"] 2)]
      (is (contains? multi "occupation/o1") "multi-seed reaches the occupation end")
      (is (not (contains? single "occupation/o1"))
          "single-seed at the same depth does NOT — the span is the multi-seed property")
      (is (contains? multi "code/socB") "multi-seed reaches the far bridge")
      (is (not (contains? single "code/socB"))
          "single-seed at the same depth does NOT reach the far bridge either"))))
