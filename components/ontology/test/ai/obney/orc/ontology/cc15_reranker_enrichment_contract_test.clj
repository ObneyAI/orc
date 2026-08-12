(ns ai.obney.orc.ontology.cc15-reranker-enrichment-contract-test
  "CC-15 integration finding (live, 2026-08-11): EVERY live rerank failed
   blackboard validation and classification deferred 100% — caught in the
   real store by CC-23's deferral events on their first production traffic.

   Root cause (a producer/contract mismatch between two components' eras,
   BOTH on upstream orc main d0a6695):
     - EL-2's enrichment (ac4053df) deliberately COMPACTS strengths/
       weaknesses to {:trait + guard + advice} — 'keep the enrichment
       compact' — dropping :confidence/:evidence-count.
     - The sio-era blackboard schema enforcement (48eae6f8) declared the
       reranker's :candidates strengths/weaknesses as FULL
       `ontology-schemas/principle-entry`, which REQUIRES those two keys.
   The mismatch was invisible until a store with enriched descriptions met
   the schema-enforcing engine: rerank-workflow-failed -> pure-ColBERT
   fallback -> EL-3 defer -> zero classifications.

   THE CONTRACT UNDER TEST: what EL-2 actually sends — built through the
   REAL compaction fns from a REAL full-shaped body — validates at the
   reranker's declared candidate boundary. The schema must describe the
   payload, not an idealized one."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.core.reranker]
            [ai.obney.orc.ontology.interface]))

(def ^:private candidate-schema @#'ai.obney.orc.ontology.core.reranker/candidate-schema)
(def ^:private compact-strengths @#'ai.obney.orc.ontology.interface/compact-strengths)
(def ^:private compact-weaknesses @#'ai.obney.orc.ontology.interface/compact-weaknesses)

(def ^:private full-body-strengths
  "Schema-conformant `principle-entry`s exactly as a stored description body
   carries them (the shape the live store's seeded/consolidated bodies have)."
  [{:trait "comprehensive edge-case verification"
    :good-when "the change is a single-file mechanical edit"
    :recommended-pattern "verify by direct invocation after editing"
    :confidence 0.9 :evidence-count 7}
   {:trait "clean intermediate representations"
    :confidence 0.6 :evidence-count 2}])

(def ^:private full-body-weaknesses
  [{:trait "introduces syntax errors during multi-edit diffs"
    :avoid-when "the diff spans many hunks in one file"
    :recommended-alternative "apply one hunk at a time and re-verify"
    :confidence 0.8 :evidence-count 4}])

(deftest el2-compacted-enrichment-validates-at-the-reranker-boundary
  (testing "the candidate EL-2 actually sends — real compaction of a real
             full-shaped body — is valid at the reranker's declared
             :candidates boundary. RED before the fix: the compacted entries
             lack :confidence/:evidence-count, which the boundary (wrongly)
             required, so every live enriched rerank failed validation and
             classification deferred 100%."
    (let [candidate {:content "Capabilities: edits files; verifies changes."
                     :score 41.5
                     :document-id "doc-1"
                     :document-metadata {:granularity :tree-class
                                         :target-id (random-uuid)}
                     :avoid-when ["the diff spans many hunks in one file"]
                     :strengths (compact-strengths full-body-strengths)
                     :weaknesses (compact-weaknesses full-body-weaknesses)}]
      (is (= [{:trait "comprehensive edge-case verification"
               :good-when "the change is a single-file mechanical edit"
               :recommended-pattern "verify by direct invocation after editing"}
              {:trait "clean intermediate representations"}]
             (:strengths candidate))
          "non-vacuous: the compaction really does strip confidence/evidence-count")
      (is (m/validate candidate-schema candidate)
          (str "EL-2's real compacted payload must validate at the reranker "
               "boundary; explain: "
               (pr-str (m/explain candidate-schema candidate)))))))

(deftest rr3-capped-child-candidate-still-validates
  (testing "RR-3's deliberately-terse child candidates (content/score/
             document-id only) stay valid — the fix must not tighten the
             boundary against the other real producer."
    (is (m/validate candidate-schema {:content "terse child"
                                      :score 12.0
                                      :document-id "doc-2"}))))

(deftest full-principle-entries-do-not-sneak-extra-requirements
  (testing "a body-shaped FULL entry (with confidence/evidence-count) must
             ALSO remain valid at the boundary — the compact shape is a
             floor, not a different dialect — so any future producer that
             chooses to send the weight signal is not rejected."
    (is (m/validate candidate-schema
                    {:content "full entry"
                     :score 3.0
                     :document-id "doc-3"
                     :strengths full-body-strengths
                     :weaknesses full-body-weaknesses}))))
