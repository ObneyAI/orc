(ns ai.obney.orc.ontology.connect4-connectivity-acceptance-test
  "CONNECT-4 — TDD for the PURE connectivity verdict (the durable /tdd deliverable).

   The verdict is fed a MEASURED summary of a built graph and decides whether the
   graph is genuinely CONNECTED through occupation↔element edges AND whether those
   edges attach to CANONICAL `occupation/*` nodes (the CONNECT-3c guarantee — NOT
   `entity/*` stubs). Fixtures cover the four failure shapes the connectivity line
   must reject: 0-edges (isolated occupations), all-entity-stub (CONNECT-3c
   regression), an occupation-island (edges exist but no shared-element bridge, so
   BFS never reaches a different occupation), and the connected PASS shape.

   Domain-agnostic (#12): the fixtures name NO real O*NET SOC/skill/predicate — the
   verdict reads only counts + one boolean."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.connectivity-acceptance :as ca]))

(def ^:private connected-summary
  "A genuinely-connected canonical graph: occupations participate in edges, every
   association edge sources off a CANONICAL occupation/* node (ZERO entity/* stubs),
   and BFS bridges a canonical occupation to a DIFFERENT one through a shared element."
  {:occupation-edge-participation 42
   :canonical-source-edges 900
   :entity-stub-edges 0
   :bfs-canonical->related? true
   :element-node-counts {:distinct-elements 35 :element-edges 900}})

(defn- criterion [v k]
  (first (filter #(= k (:criterion %)) (:reasons v))))

(deftest verdict-passes-a-connected-canonical-graph
  (testing "occupations edged, all edges on canonical occupation/* nodes (0 stubs),
            BFS reaches a different occupation → PASS"
    (let [v (ca/connectivity-verdict connected-summary)]
      (is (:pass? v))
      (is (:pass? (criterion v :occupation-edges-present)))
      (is (:pass? (criterion v :canonical-occupation-sources)))
      (is (:pass? (criterion v :bfs-canonical-to-different-occupation))))))

(deftest verdict-fails-a-zero-edge-graph
  (testing "0 occupation edge-participation → occupations are isolated (BFS-dead) → FAIL
            (the pre-CONNECT-3 state: 1016 attribute-only nodes, 0 edges)"
    (let [v (ca/connectivity-verdict
             (assoc connected-summary
                    :occupation-edge-participation 0
                    :canonical-source-edges 0
                    :bfs-canonical->related? false
                    :element-node-counts {:distinct-elements 0 :element-edges 0}))]
      (is (not (:pass? v)))
      (is (not (:pass? (criterion v :occupation-edges-present)))))))

(deftest verdict-fails-all-entity-stub-edges
  (testing "edges exist and BFS traverses, but every edge sources off an entity/* STUB
            (canonical-source-edges 0, entity-stub-edges > 0) → FAIL. This is THE
            CONNECT-3c regression guard: association edges must attach to the CANONICAL
            occupation nodes, not entity/<SOC> stubs."
    (let [v (ca/connectivity-verdict
             (assoc connected-summary :canonical-source-edges 0 :entity-stub-edges 900))]
      (is (not (:pass? v)))
      (is (not (:pass? (criterion v :canonical-occupation-sources)))
          "the canonical-source criterion fails when any entity/* stub edge is present"))))

(deftest verdict-fails-an-occupation-island
  (testing "edges exist on canonical occupations (0 stubs) but NO element is shared, so
            BFS never reaches a DIFFERENT occupation (bfs-canonical->related? false) → FAIL.
            'edges exist somewhere' is not the proof — occupation↔element↔occupation
            traversal is."
    (let [v (ca/connectivity-verdict
             (assoc connected-summary :bfs-canonical->related? false))]
      (is (not (:pass? v)))
      (is (not (:pass? (criterion v :bfs-canonical-to-different-occupation)))))))

(deftest element-dedup-is-a-nongating-diagnostic
  (testing "the element-node dedup count is a NON-GATING diagnostic — a graph that
            satisfies the three gating criteria PASSES even if the dedup detail is
            absent (it informs, it does not decide)."
    (let [v (ca/connectivity-verdict (dissoc connected-summary :element-node-counts))
          dedup (criterion v :element-node-dedup)]
      (is (:pass? v) "absent dedup diagnostic does not block a connected graph")
      (is (false? (:gating? dedup)) "the dedup criterion is explicitly non-gating"))))
