(ns ai.obney.orc.ontology.gc10-hierarchy-traversal-test
  "GC-10 Fix B2 — the CONNECTIVITY-PROOF traversal hop, TDD'd on a SYNTHETIC
   FIXTURE (Discipline 2/6: tests FIRST, behavior through the public
   `find-connectivity-chain` fn).

   This is a PURE unit test — no Grain, no LLM, no network. It feeds
   `find-connectivity-chain` a `{:concepts :relationships}` snapshot and asserts the
   program→field→occupation chain reads back ACROSS the family↔detail grain split:
   the program connects to a 2-digit FAMILY field, that family is `skos:narrower`
   the 6-digit DETAIL field, and the DETAIL field is what carries the
   `leads to`→occupation edge. WITHOUT the hierarchy hop the chain dead-ends at the
   family (`:no-complete-chain`); WITH it the chain completes.

   The chain criterion ALSO tolerates the predicate variant `leads to` ↔
   `prepares_for` (a minor synonym)."
  (:require [clojure.test :refer [deftest testing is]]
            [eb12-graph-b-central-evolver :as b]))

;; -- FIXTURE: the grain-split graph that the family↔detail hierarchy bridges. -----
;; program/p1  --in-field-->        fieldofstudy/01        (the 2-digit FAMILY)
;; fieldofstudy/01  --skos:narrower--> fieldofstudy/01.0407 (the 6-digit DETAIL)
;; fieldofstudy/01.0407 --leads to--> occupation/19-1013    (detail→occupation)
;;
;; Domain-AGNOSTIC kinds via the uri-kind scheme: "program", "fieldofstudy",
;; "occupation" — guess-kinds maps program→:program, fieldofstudy→:cip (matches
;; "field"), occupation→:soc (matches "occ").
(def grain-split-snapshot
  {:concepts
   [{:uri "program/p1" :label "Agribusiness BS"}
    {:uri "fieldofstudy/01" :label "Agriculture (family)"}
    {:uri "fieldofstudy/01.0407" :label "Agricultural Economics (detail)"}
    {:uri "occupation/19-1013" :label "Agricultural Economist"}]
   :relationships
   [{:source-uri "program/p1" :target-uri "fieldofstudy/01" :predicate "in-field"}
    ;; the family↔detail hierarchy hop (GC-10 Fix B2 lands this skos:narrower edge)
    {:source-uri "fieldofstudy/01" :target-uri "fieldofstudy/01.0407" :predicate "skos:narrower"}
    {:source-uri "fieldofstudy/01.0407" :target-uri "occupation/19-1013" :predicate "leads to"}]})

;; -- A snapshot that completes WITHOUT a hierarchy hop (the direct case must keep
;;    working — behavior-preserving). program→field(detail)→occupation directly. ---
(def direct-snapshot
  {:concepts
   [{:uri "program/p2" :label "Nursing BSN"}
    {:uri "fieldofstudy/51.3801" :label "Registered Nursing"}
    {:uri "occupation/29-1141" :label "Registered Nurse"}]
   :relationships
   [{:source-uri "program/p2" :target-uri "fieldofstudy/51.3801" :predicate "in-field"}
    {:source-uri "fieldofstudy/51.3801" :target-uri "occupation/29-1141" :predicate "leads to"}]})

(deftest fix-b2-traversal-chain-reads-back-via-family-detail-hierarchy-hop
  (testing "the program→field→occupation chain completes when the program connects to
            the FAMILY field, the family is skos:narrower the DETAIL field, and the
            DETAIL field carries the occupation edge (the grain split the GC-5 chain
            dead-ended on)"
    (let [chain (b/find-connectivity-chain grain-split-snapshot)]
      (is (not (:no-complete-chain chain))
          "a complete chain reads back across the family→detail hierarchy hop")
      (is (= "program/p1" (get-in chain [:program :uri]))
          "the chain starts at the program")
      (is (= "occupation/19-1013" (get-in chain [:soc :uri]))
          "the chain ends at the occupation reached THROUGH the detail field")
      ;; the chain must surface the hierarchy bridge it traversed (auditable — the
      ;; family node and the detail node it narrowed to).
      (is (= "fieldofstudy/01" (get-in chain [:cip :uri]))
          "the program's directly-connected FAMILY field is surfaced")
      (is (= "fieldofstudy/01.0407" (get-in chain [:cip-detail :uri]))
          "the DETAIL field reached via skos:narrower is surfaced (the bridge)"))))

(deftest fix-b2-traversal-direct-chain-still-reads-back-behavior-preserving
  (testing "a program whose field DIRECTLY carries the occupation edge (no grain
            split, no hierarchy hop needed) still reads back a complete chain
            (behavior-preserving for the non-split case)"
    (let [chain (b/find-connectivity-chain direct-snapshot)]
      (is (not (:no-complete-chain chain))
          "the direct program→field→occupation chain still completes")
      (is (= "program/p2" (get-in chain [:program :uri])))
      (is (= "occupation/29-1141" (get-in chain [:soc :uri]))))))

(deftest fix-b2-traversal-predicate-variant-prepares-for-tolerated
  (testing "the chain criterion tolerates the field→occupation predicate variant
            `prepares_for` as a synonym of `leads to`"
    (let [snap (assoc-in direct-snapshot [:relationships 1 :predicate] "prepares_for")
          chain (b/find-connectivity-chain snap)]
      (is (not (:no-complete-chain chain))
          "prepares_for is honored as a leads-to synonym → chain completes"))))

(deftest fix-b2-traversal-no-chain-when-detail-has-no-occupation
  (testing "when the detail field carries NO occupation edge, the chain honestly
            reports :no-complete-chain (no fabricated hop)"
    (let [snap (update grain-split-snapshot :relationships
                       (fn [rels] (remove #(= "leads to" (:predicate %)) rels)))
          chain (b/find-connectivity-chain snap)]
      (is (:no-complete-chain chain)
          "no occupation edge anywhere → honest :no-complete-chain"))))
