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
;; present, graph-health clean, a real program→cip→soc chain reads back.
(def connected-capture
  {:status :complete
   :stats  {:concept-count 1842
            :concepts-by-kind {:institution 412
                               :degree_program 980
                               :field_of_study 220
                               :soc_occupation 230}
            :graph-health {:fragmented? false
                           :fragmented-identity-count 0
                           :fragmented-identities []}}
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
  (testing "a CONNECTED graph (chain + single convention + non-zero + real kinds)
            reads :pass? true with every criterion green"
    (let [{:keys [pass? reasons]} (b/acceptance-verdict connected-capture)]
      (is (true? pass?) "the connected graph must PASS")
      (is (every? :pass? reasons) "every criterion must be green")
      (is (= #{:honest-terminal :non-zero-build :one-connected-graph
               :chain-reads-back :convention-agnostic-kinds}
             (set (map :criterion reasons)))
          "all five GC-5 criteria are evaluated"))))

(deftest fragmented-graph-fails-on-fragmentation-and-chain
  (testing "a FRAGMENTED graph (same-id-two-conventions + no chain) reads
            :pass? false, failing on one-connected-graph AND chain-reads-back"
    (let [{:keys [pass? reasons]} (b/acceptance-verdict fragmented-capture)
          failing (->> reasons (remove :pass?) (map :criterion) set)]
      (is (false? pass?) "the fragmented graph must FAIL")
      (is (contains? failing :one-connected-graph)
          "fragmentation (:graph-health/:fragmented? true) must fail the verdict")
      (is (contains? failing :chain-reads-back)
          ":no-complete-chain must fail the chain criterion"))))

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
