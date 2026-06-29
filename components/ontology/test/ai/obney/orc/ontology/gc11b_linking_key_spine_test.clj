(ns ai.obney.orc.ontology.gc11b-linking-key-spine-test
  "GC-11b — the deterministic linking-key code-node spine (the cross-source join).

   GC-11a carries each concept's discovered linking-key VALUES in `:attributes`
   (the value is present even when it is NOT the keying field). The discovered
   linking-key NAMES are aggregated from the per-source model-specs. This spine
   (NO LLM, mirroring the MC-6 / GC-10 deterministic relating + the GC-2 bounded
   cap) recovers each concept's linking-key value via the SAME GC-1
   `recover-via-value` helper, mints ONE canonical CODE NODE per distinct
   (linking-key, normalized-value), and attaches every carrier to it via a
   domain-agnostic `identified-by` predicate — so two concepts from DIFFERENT
   sources that share a linking-key value JOIN through the one code node,
   regardless of how the LLM modeled the carriers run-to-run.

   These cycles exercise the PURE `linking-key-relationship-drafts` through its
   public interface — NO Grain, NO LLM. Synthetic linking-key NAMES + VALUES (NO
   CIP/SOC — Discipline 12). The real Grain landing + traversal is proven by the
   central-evolver read-back, not here."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]))

;; =============================================================================
;; Cycle 1 — cross-source join (synthetic). Two concepts from DIFFERENT sources
;; whose :attributes carry the SAME linking-key value → ONE code-node minted +
;; BOTH identified-by it. Different values → different nodes (no join).
;; =============================================================================

(defn- by-predicate [drafts pred]
  (filter #(= pred (:predicate %)) drafts))

(deftest cycle1-cross-source-same-value-joins-through-one-code-node
  (testing "two concepts from DIFFERENT sources carrying the SAME linking-key
            value in :attributes → ONE code-node minted + BOTH attached via
            identified-by (the cross-source join)"
    (let [;; sourceA's program concept and sourceB's crosswalk concept both
          ;; carry the SAME widget-code value — but keyed at different URIs.
          concepts [{:uri "program/p1"   :attributes {"widget_code" "WX-7"}}
                    {:uri "crosswalk/c9" :attributes {"widget_code" "WX-7"}}]
          result (extract/linking-key-relationship-drafts concepts ["widget_code"])
          code-nodes (:concept-drafts result)
          edges (by-predicate (:relationship-drafts result) "identified-by")
          code-uri (:uri (first code-nodes))]
      (is (= 1 (count code-nodes))
          "exactly ONE code-node minted for the single shared (key,value)")
      (is (= "widgetcode/wx-7" code-uri)
          "the code-node URI is <normalized-linking-key>/<normalized-value>")
      (is (every? :label code-nodes)
          "every minted code-node carries a :label (the create-concept contract)")
      (is (= 2 (count edges))
          "BOTH carriers are attached to the code-node")
      (is (= #{"program/p1" "crosswalk/c9"}
             (set (map :source-uri edges)))
          "both source concepts are the edge SOURCES")
      (is (= #{code-uri} (set (map :target-uri edges)))
          "both edges point at the ONE shared code-node"))))

(deftest cycle1-different-values-mint-different-nodes-no-join
  (testing "DIFFERENT linking-key values → DIFFERENT code nodes, NOT joined (each
            value carried by ≥2 carriers so each is a real join — distinct values
            never collapse together)"
    (let [concepts [{:uri "program/p1"   :attributes {"widget_code" "WX-7"}}
                    {:uri "program/p2"   :attributes {"widget_code" "WX-7"}}
                    {:uri "crosswalk/c9" :attributes {"widget_code" "ZZ-1"}}
                    {:uri "crosswalk/c10" :attributes {"widget_code" "ZZ-1"}}]
          result (extract/linking-key-relationship-drafts concepts ["widget_code"])
          code-nodes (:concept-drafts result)
          edges (by-predicate (:relationship-drafts result) "identified-by")]
      (is (= 2 (count code-nodes))
          "two DISTINCT ≥2-carrier values → two distinct code-nodes (no spurious join)")
      (is (= #{"widgetcode/wx-7" "widgetcode/zz-1"}
             (set (map :uri code-nodes)))
          "each value mints its OWN normalized code-node URI")
      (is (= 4 (count edges))
          "all four carriers attach to their OWN value's code-node")
      (is (= #{"widgetcode/wx-7"} (set (map :target-uri (filter #(#{"program/p1" "program/p2"} (:source-uri %)) edges))))
          "the WX-7 carriers point ONLY at the WX-7 node (no cross-value join)"))))

(deftest gc11b-single-carrier-value-mints-no-node-the-join-bound
  (testing "a value carried by only ONE concept JOINS nothing → NO code-node, NO edge
            (the spine's purpose is the cross-source JOIN; a single-carrier value is
            overhead and the high-cardinality explosion source — #4 honest, bounded)"
    (let [concepts [{:uri "a/1" :attributes {"id_code" "UNIQUE-1"}}
                    {:uri "a/2" :attributes {"id_code" "UNIQUE-2"}}
                    {:uri "a/3" :attributes {"id_code" "SHARED-9"}}
                    {:uri "b/4" :attributes {"id_code" "SHARED-9"}}]
          result (extract/linking-key-relationship-drafts concepts ["id_code"])
          code-nodes (:concept-drafts result)]
      (is (= 1 (count code-nodes))
          "only the SHARED-9 value (2 carriers) mints a node; the two UNIQUE-* singles do NOT")
      (is (= "idcode/shared-9" (:uri (first code-nodes)))
          "the one node is the genuine ≥2-carrier join")
      (is (= 2 (count (by-predicate (:relationship-drafts result) "identified-by")))
          "only the two SHARED-9 carriers attach — the singles attach to nothing"))))

;; =============================================================================
;; Cycle 2 — idempotent + cross-source. The same (key,value) from N sources →
;; the SAME single code-node URI.
;; =============================================================================

(deftest cycle2-same-key-value-from-n-sources-is-one-idempotent-node
  (testing "the same (linking-key, value) carried by N concepts from N sources
            mints exactly ONE code-node URI (idempotent — the join is on the
            VALUE, not the carrier URI/shape)"
    (let [concepts [{:uri "program/p1"     :attributes {"widget_code" "WX-7"}}
                    {:uri "crosswalk/c9"   :attributes {"widget_code" "wx-7"}}   ; case-variant
                    {:uri "occupation/o3"  :attributes {"widget_code" " WX-7 "}}] ; whitespace-variant
          result (extract/linking-key-relationship-drafts concepts ["widget_code"])
          code-nodes (:concept-drafts result)
          edges (by-predicate (:relationship-drafts result) "identified-by")]
      (is (= 1 (count code-nodes))
          "case/whitespace variants of the SAME value collapse to ONE code-node")
      (is (= "widgetcode/wx-7" (:uri (first code-nodes)))
          "the idempotent URI is the normalized (key,value)")
      (is (= 3 (count edges))
          "all THREE carriers attach to the ONE node")
      (is (= #{"widgetcode/wx-7"} (set (map :target-uri edges)))
          "every edge targets the single idempotent code-node"))))

;; =============================================================================
;; Cycle 3 — bounded + honest. A value carried by MANY concepts → bounded attach
;; edges + a truncation report (never a silent drop).
;; =============================================================================

(deftest cycle3-many-carriers-per-value-is-bounded-and-honest
  (testing "a single (key,value) carried by MANY concepts fans out BOUNDED — past
            the cap the excess attach edges are DROPPED and surfaced in
            :truncated-relations honestly (never a silent top-N), like GC-2"
    (let [carriers (for [i (range 1 31)]   ; 30 concepts all carrying WX-7
                     {:uri (format "program/p%02d" i)
                      :attributes {"widget_code" "WX-7"}})
          cap 10
          result (extract/linking-key-relationship-drafts carriers ["widget_code"] cap)
          edges (by-predicate (:relationship-drafts result) "identified-by")]
      (is (= 1 (count (:concept-drafts result)))
          "still ONE code-node — only the attach fan-out is bounded")
      (is (= cap (count edges))
          "the per-code attach fan-out is capped")
      (is (seq (:truncated-relations result))
          "the dropped excess is surfaced (honest, not silent)")
      (is (= (- 30 cap)
             (reduce + 0 (map :dropped-edges (:truncated-relations result))))
          "the truncation report accounts for EVERY dropped edge (20 of 30)"))))

;; =============================================================================
;; Cycle 4 — honest absence. A concept with NO recoverable linking-key value
;; attaches to nothing (no fabricated node/edge).
;; =============================================================================

(deftest cycle4-no-recoverable-value-attaches-to-nothing
  (testing "a concept carrying NO recoverable linking-key value mints no code-node
            and attaches to nothing — no fabricated node/edge (Discipline 4/5)"
    (let [concepts [{:uri "program/p1"  :attributes {"unrelated_field" "x"}}
                    {:uri "program/p2"  :attributes {}}
                    {:uri "program/p3"}]  ; no :attributes at all
          result (extract/linking-key-relationship-drafts concepts ["widget_code"])]
      (is (empty? (:concept-drafts result))
          "no carrier has the linking key → no code-node fabricated")
      (is (empty? (by-predicate (:relationship-drafts result) "identified-by"))
          "no attach edge fabricated for a concept with no recoverable value"))))

(deftest cycle4-mixed-carriers-only-real-carriers-attach
  (testing "in a MIXED set, only the concepts that actually carry the (≥2-carrier
            join) value attach — the non-carrier is left honestly unattached"
    (let [concepts [{:uri "program/p1"  :attributes {"widget_code" "WX-7"}}
                    {:uri "program/p3"  :attributes {"widget_code" "WX-7"}}
                    {:uri "program/p2"  :attributes {"unrelated" "y"}}]
          result (extract/linking-key-relationship-drafts concepts ["widget_code"])
          edges (by-predicate (:relationship-drafts result) "identified-by")]
      (is (= 1 (count (:concept-drafts result)))
          "one code-node for the one real ≥2-carrier value")
      (is (= #{"program/p1" "program/p3"} (set (map :source-uri edges)))
          "only the real carriers attach; the non-carrier contributes no edge"))))

(deftest cycle-multi-key-aggregation-mints-per-key
  (testing "with MULTIPLE linking-keys aggregated across sources, each ≥2-carrier
            (key,value) mints its OWN code-node and the carriers attach to each —
            the spine is multi-key, domain-agnostic"
    (let [concepts [{:uri "crosswalk/c1"
                     :attributes {"widget_code" "WX-7" "gadget_code" "GG-2"}}
                    {:uri "crosswalk/c2"
                     :attributes {"widget_code" "WX-7" "gadget_code" "GG-2"}}]
          result (extract/linking-key-relationship-drafts
                  concepts ["widget_code" "gadget_code"])
          code-nodes (set (map :uri (:concept-drafts result)))
          edges (by-predicate (:relationship-drafts result) "identified-by")]
      (is (= #{"widgetcode/wx-7" "gadgetcode/gg-2"} code-nodes)
          "one code-node per distinct (linking-key, value)")
      (is (= 4 (count edges))
          "both carriers attach to BOTH code-nodes (2 carriers × 2 keys)")
      (is (= #{"widgetcode/wx-7" "gadgetcode/gg-2"}
             (set (map :target-uri edges)))
          "the carriers are identified-by each of their codes"))))
