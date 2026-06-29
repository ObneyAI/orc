(ns ai.obney.orc.ontology.gc5-acceptance-verdict-test
  "GC-5 — the ACCEPTANCE VERDICT logic, TDD'd on SYNTHETIC FIXTURES (Discipline
   2/6: tests FIRST, behavior through the public `acceptance-verdict` fn).

   This is a PURE unit test — no Grain, no LLM, no network. It feeds
   `acceptance-verdict` the SAME captured-map shape `run!` produces (`:status`,
   `:stats` with `:concepts-by-kind` + `:graph-health`, `:connectivity`) and
   asserts the PASS/FAIL verdict over the GC-5 criteria.

   The guard: a CONNECTED graph (real program→cip→soc chain, single convention,
   non-zero, institutions ≫ 1 + occupations present) reads :pass? true; a
   FRAGMENTED graph (same-label-different-type / :no-complete-chain / 0 concepts)
   reads :pass? false WITH the failing criterion. Reverting the verdict logic
   must turn one of these RED — this is what stops a fragmented/0-draft build
   from reading green (\"how could this pass while still being wrong?\")."
  (:require [clojure.test :refer [deftest testing is]]
            [eb12-graph-b-central-evolver :as b]))

;; -- FIXTURE A: a CONNECTED, healthy graph (should PASS) ----------------------
;; Single convention (slash form), non-zero, institutions ≫ 1, occupations
;; present, graph-health clean, AND the CQ-gate ANSWERED the cross-source CQ
;; (GC-11c: the acceptance reads the CQ-gate :pass verdict, not connectivity).
(def connected-capture
  {:status :complete
   :cq-verdict [{:cq-index 0
                 :cq-text "Which occupations do the educational programs lead to?"
                 :verdict :pass
                 :evidence-uris ["degree_program/236753-51.3801"
                                 "field_of_study/51.3801" "soc_occupation/29-1141"]
                 :judged-by? true
                 :layer :layer-2-semantic-exists}]
   :stats  {:concept-count 1842
            :concepts-by-kind {:institution 412
                               :degree_program 980
                               :field_of_study 220
                               :soc_occupation 230}
            :graph-health {:fragmented? false
                           :fragmented-identity-count 0
                           :fragmented-identities []}}
   ;; connectivity-proof present but DEBUG-only under GC-11c.
   :connectivity {:program {:uri "degree_program/236753-51.3801"
                            :label "Registered Nursing (BSN)"
                            :attributes {}}
                  :program->cip {:source-uri "degree_program/236753-51.3801"
                                 :predicate :in-field
                                 :target-uri "field_of_study/51.3801"}
                  :cip {:uri "field_of_study/51.3801"
                        :label "Registered Nursing/Registered Nurse"}
                  :cip->soc {:source-uri "field_of_study/51.3801"
                             :predicate :leads-to-occupation
                             :target-uri "soc_occupation/29-1141"}
                  :soc {:uri "soc_occupation/29-1141"
                        :label "Registered Nurses"
                        :attributes {:median-wage 75000}}}})

;; -- FIXTURE B: a FRAGMENTED graph (should FAIL) ------------------------------
;; The classic GC-1 fragmentation: CIP 51.3801 minted under TWO conventions
;; (degree_program: colon form AND programofstudy/ slash form) — graph-health
;; flags :fragmented? true — AND the chain did NOT read back.
(def fragmented-capture
  {:status :complete
   :stats  {:concept-count 1200
            :concepts-by-kind {:institution 400
                               :degree_program 600
                               :programofstudy 200}
            :graph-health {:fragmented? true
                           :fragmented-identity-count 37
                           :fragmented-identities
                           [{:identity "51.3801"
                             :schemes [:degree_program :programofstudy]
                             :scheme-counts {:degree_program 1 :programofstudy 1}
                             :count 2}]}}
   :connectivity {:no-complete-chain true
                  :roles-detected {:program :degree_program :cip :field :soc nil}
                  :program-count 600
                  :note "No program->field->occupation chain — see cross-source-links."}})

;; -- FIXTURE C: a 0-draft / 0-concept build (should FAIL — no false green) ----
(def zero-draft-capture
  {:status :complete
   :stats  {:concept-count 0
            :concepts-by-kind {}
            :graph-health {:fragmented? false
                           :fragmented-identity-count 0
                           :fragmented-identities []}}
   :connectivity {:no-complete-chain true :roles-detected {} :program-count 0}})

;; -- FIXTURE D: a CRASH / non-honest terminal (should FAIL) -------------------
(def crash-capture
  (assoc connected-capture :status :error))

(deftest connected-graph-passes
  (testing "a CONNECTED graph (gate-answered + single convention + non-zero + real
            kinds) reads :pass? true with every criterion green"
    (let [{:keys [pass? reasons]} (b/acceptance-verdict connected-capture)]
      (is (true? pass?) "the connected graph must PASS")
      (is (every? :pass? reasons) "every criterion must be green")
      (is (= #{:honest-terminal :non-zero-build :one-connected-graph
               :cq-gate-answers :convention-agnostic-kinds}
             (set (map :criterion reasons)))
          "all five criteria are evaluated; the gate is the cross-source arbiter
           (GC-11c demoted :chain-reads-back → :cq-gate-answers)")
      (is (not (contains? (set (map :criterion reasons)) :chain-reads-back))
          "connectivity-proof chain-reads-back is demoted out of the criteria"))))

(deftest fragmented-graph-fails-on-the-gate-not-on-fragmentation
  (testing "a FRAGMENTED graph whose gate did NOT answer reads :pass? false — but
            (GC-11c) it fails on the GATE, not on fragmentation: :one-connected-graph
            is reported with :pass? false yet is NON-GATING (the spine makes
            :fragmented? a false positive, so it no longer gates acceptance)"
    (let [{:keys [pass? reasons]} (b/acceptance-verdict fragmented-capture)
          failing (->> reasons (remove :pass?) (map :criterion) set)
          ocg     (->> reasons (filter #(= :one-connected-graph (:criterion %))) first)]
      (is (false? pass?) "the gate did not answer → the build must FAIL")
      (is (contains? failing :cq-gate-answers)
          "no :pass CQ verdict must fail the cq-gate-answers criterion (the gate)")
      (is (and (some? ocg) (false? (:pass? ocg)) (false? (:gating? ocg)))
          "fragmentation is still REPORTED (:one-connected-graph :pass? false) but
           is NON-GATING — it does not by itself fail acceptance"))))

(deftest zero-draft-build-fails
  (testing "a 0-concept build reads :pass? false (no 0-draft false-green)"
    (let [{:keys [pass? reasons]} (b/acceptance-verdict zero-draft-capture)
          failing (->> reasons (remove :pass?) (map :criterion) set)]
      (is (false? pass?) "a 0-concept build must FAIL")
      (is (contains? failing :non-zero-build)
          "non-zero-build must fail when concept-count is 0")
      (is (contains? failing :convention-agnostic-kinds)
          "with no kinds, institutions ≫ 1 / occupations-present must also fail"))))

(deftest crash-terminal-fails
  (testing "a non-honest terminal (:error) reads :pass? false even with an
            otherwise-connected graph (no fabricated complete)"
    (let [{:keys [pass? reasons]} (b/acceptance-verdict crash-capture)
          failing (->> reasons (remove :pass?) (map :criterion) set)]
      (is (false? pass?) "a crashed build must FAIL")
      (is (contains? failing :honest-terminal)
          ":error is not an honest terminal"))))
