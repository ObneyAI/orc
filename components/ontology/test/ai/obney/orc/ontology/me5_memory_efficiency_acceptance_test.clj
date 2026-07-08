(ns ai.obney.orc.ontology.me5-memory-efficiency-acceptance-test
  "ME-5 — TDD for the PURE memory-efficiency verdict (the durable /tdd deliverable).

   The verdict is fed a MEASURED store event-type histogram of a completed build
   and decides whether the ME-1..ME-3 slimming genuinely landed AND the CONSUMED
   sets survived. Fixtures cover the slimmed PASS shape and the four regression
   shapes the ME line must reject: 2× re-embed (ME-1), a still-emitting write-only
   dedup ledger (ME-2), per-pair evidence write-amplification (ME-3), and an empty
   consumed set (concepts/relationships dropped).

   Domain-agnostic: the fixtures name NO real O*NET SOC/skill/predicate — the
   verdict reads only event-type counts off the histogram map. Note the gated
   write-only ledgers are ABSENT keys in the slimmed histogram (0 events ⇒ no key)
   — the verdict treats an absent key as 0."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.connectivity-acceptance :as ca]))

(def ^:private slimmed-histogram
  "A post-ME-1..3 slimmed build: embed once per concept, write-only dedup ledgers
   gated off (absent keys ⇒ 0), evidence rolled once-per-concept (≤ concepts), and
   the consumed sets (concepts, relationships, equivalences) present."
  {:ontology/concept-created 1000
   :ontology/concept-embedded 1000
   :ontology/concept-evidence-aggregated 640
   :ontology/relationship-created 2500
   :ontology/equivalence-recorded 300})

(defn- criterion [v k]
  (first (filter #(= k (:criterion %)) (:reasons v))))

(deftest verdict-passes-a-slimmed-build
  (testing "embed==created, write-only ledgers gated (absent⇒0), evidence<=created,
            consumed sets present → PASS"
    (let [v (ca/memory-efficiency-verdict slimmed-histogram)]
      (is (:pass? v))
      (is (:pass? (criterion v :one-embed-per-concept)))
      (is (:pass? (criterion v :writeonly-ledgers-gated)))
      (is (:pass? (criterion v :evidence-once-per-concept)))
      (is (:pass? (criterion v :consumed-sets-intact))))))

(deftest verdict-fails-2x-re-embed
  (testing "concept-embedded == 2× concept-created → the ME-1 auto-embed! re-embed
            regression → FAIL on the embed criterion"
    (let [v (ca/memory-efficiency-verdict
             (assoc slimmed-histogram :ontology/concept-embedded 2000))]
      (is (not (:pass? v)))
      (is (not (:pass? (criterion v :one-embed-per-concept)))))))

(deftest verdict-passes-when-blank-text-concepts-skipped
  (testing "embedded < created (some concepts honestly blank-text-skipped, still 1× the
            rest) → PASS — the criterion rejects the 2× amplification, not honest skips"
    (let [v (ca/memory-efficiency-verdict
             (assoc slimmed-histogram :ontology/concept-embedded 940))]
      (is (:pass? v))
      (is (:pass? (criterion v :one-embed-per-concept)))))
  (testing "embedded == 0 (nothing embedded) → FAIL"
    (let [v (ca/memory-efficiency-verdict
             (assoc slimmed-histogram :ontology/concept-embedded 0))]
      (is (not (:pass? (criterion v :one-embed-per-concept)))))))

(deftest verdict-fails-a-still-emitting-writeonly-ledger
  (testing "a write-only dedup ledger still emitting (co-occurrence > 0 OR
            distinct-recorded > 0) → the ME-2 gate is off → FAIL"
    (let [v-co (ca/memory-efficiency-verdict
                (assoc slimmed-histogram :ontology/concept-pair-co-occurrence 463707))
          v-di (ca/memory-efficiency-verdict
                (assoc slimmed-histogram :ontology/dedup-distinct-recorded 260118))]
      (is (not (:pass? v-co)))
      (is (not (:pass? (criterion v-co :writeonly-ledgers-gated))))
      (is (not (:pass? v-di)))
      (is (not (:pass? (criterion v-di :writeonly-ledgers-gated)))))))

(deftest verdict-fails-per-pair-evidence-amplification
  (testing "concept-evidence-aggregated > concept-created (≈ 2×candidate-pairs, the
            pre-ME-3 cascade write-amplification) → FAIL on the evidence criterion"
    (let [v (ca/memory-efficiency-verdict
             (assoc slimmed-histogram :ontology/concept-evidence-aggregated 927414))]
      (is (not (:pass? v)))
      (is (not (:pass? (criterion v :evidence-once-per-concept)))))))

(deftest verdict-fails-empty-consumed-sets
  (testing "a consumed set dropped — zero/missing concept-created or
            relationship-created, or an absent equivalence-recorded key → FAIL"
    (let [v-no-concepts (ca/memory-efficiency-verdict
                         (assoc slimmed-histogram :ontology/concept-created 0
                                :ontology/concept-embedded 0
                                :ontology/concept-evidence-aggregated 0))
          v-no-rels     (ca/memory-efficiency-verdict
                         (assoc slimmed-histogram :ontology/relationship-created 0))
          v-no-equiv    (ca/memory-efficiency-verdict
                         (dissoc slimmed-histogram :ontology/equivalence-recorded))]
      (is (not (:pass? v-no-concepts)))
      (is (not (:pass? (criterion v-no-concepts :consumed-sets-intact))))
      (is (not (:pass? v-no-rels)))
      (is (not (:pass? (criterion v-no-rels :consumed-sets-intact))))
      (is (not (:pass? v-no-equiv)))
      (is (not (:pass? (criterion v-no-equiv :consumed-sets-intact)))))))
