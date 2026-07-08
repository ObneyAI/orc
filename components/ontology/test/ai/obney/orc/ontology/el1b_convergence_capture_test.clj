(ns ai.obney.orc.ontology.el1b-convergence-capture-test
  "EL-1b (ADR 0015, emergence loop): convergence-capture — bundle the :novel
   candidate by multi-signal similarity + evidence-gate retrieval.

   Two deterministic surfaces, both exercised via with-redefs on
   search-descriptions (and the consolidation-total resolver) so the tests
   cover ONLY the bundle decision + the gate filter — not LLM output.

   PART 1 — bundle the :novel candidate.
     On the confident no-match (:novel) path, before scattering a fresh
     random-uuid, classify-task probes the retrieved :tree-class candidates
     with a multi-signal fusion (reranker fitness + ColBERT score, RRF-fused)
     and, if the best :tree-class candidate's absolute fitness sits in the
     BUNDLE band [bundle-threshold, threshold), assigns to THAT class
     (converge — :was-fresh-mint? false, :bundled? true) instead of minting a
     fresh uuid. Below the bundle band -> mint fresh (unchanged). A distinct
     candidate (low fit) must NOT over-merge.

   PART 2 — retrieval-gate governs SURFACING, not accrual (CV-1, ADR 0017).
     The gate no longer filters a :tree-class out of the MATCH/BUNDLE decision
     (that candidacy-gate was the bootstrap deadlock: a described total-1 class
     was hidden from the very matching that would grow it, so it could never
     accrue past the gate). Decoupled: matching/bundling operate on the UNGATED
     candidates so a below-gate described class IS matchable-for-accrual from
     occurrence 1; the gate now MOVES to the SURFACED reference set
     (:top-candidates the R-Inject prepend shows). A one-off (0 < total < gate)
     is still kept out of the surfaced references (its noise-suppression role);
     it flips to surfaced once its total crosses the gate. A curated seed
     (total 0) and :tree-fingerprint candidates are never gated."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]))

;; =============================================================================
;; Candidate fixtures — shaped exactly as search-descriptions returns them
;; (per el3_three_state_outcome_test): reranker-success carries numeric
;; :fitness-score + :rerank-source :reranker.
;; =============================================================================

(defn- tree-class-candidate
  "A reranker-success :tree-class candidate at the given fitness + raw score."
  [target-id fitness raw-score]
  {:content "x" :score raw-score :rank 1
   :document-id (str "tc::" target-id)
   :document-metadata {:granularity :tree-class
                       :target-id target-id
                       :confidence 1.0}
   :reasoning "principle-shaped fit"
   :fitness-score fitness
   :rerank-source :reranker})

(defn- tree-fingerprint-candidate
  [target-id fitness raw-score]
  {:content "x" :score raw-score :rank 1
   :document-id (str "tf::" target-id)
   :document-metadata {:granularity :tree-fingerprint
                       :target-id target-id
                       :confidence 1.0}
   :reasoning "shape fit"
   :fitness-score fitness
   :rerank-source :reranker})

;; A consolidation-total resolver stub: a map {tree-class-id -> total}, default 0.
(defn- totals-fn [m]
  (fn [_ctx _target-type target-id] (get m target-id 0)))

;; Always-above-gate stub so Part-1 tests aren't accidentally filtered by Part 2.
(defn- all-above-gate [_ctx _target-type _target-id] 1000)

;; =============================================================================
;; PART 1 — bundle the :novel candidate by multi-signal similarity
;; =============================================================================

(deftest novel-bundles-to-existing-class-in-bundle-band
  (testing "confident no-match BUT a :tree-class candidate fit in [bundle-threshold, threshold) -> bundle to it, not a fresh uuid"
    (let [existing (random-uuid)]
      (with-redefs [ontology/search-descriptions
                    (fn [_ _] [(tree-class-candidate existing 0.62 12.0)])
                    tc/get-consolidation-total* all-above-gate]
        (let [r (ontology/classify-task {} {:task-signature "x"
                                            :threshold 0.7
                                            :bundle-threshold 0.5
                                            :walk-down? false})]
          (is (= :novel (:outcome r)) "still a :novel outcome (capture path)")
          (is (= existing (:assigned-tree-id r)) "CONVERGED: assigned the existing class, not a fresh uuid")
          (is (false? (:was-fresh-mint? r)) "a bundle is NOT a fresh-mint (no scatter)")
          (is (true? (:bundled? r)) ":bundled? marks the convergence"))))))

(deftest novel-below-bundle-band-mints-fresh
  (testing "confident no-match AND best :tree-class candidate below bundle-threshold -> fresh mint (no over-merge)"
    (let [distinct-class (random-uuid)]
      (with-redefs [ontology/search-descriptions
                    (fn [_ _] [(tree-class-candidate distinct-class 0.30 4.0)])
                    tc/get-consolidation-total* all-above-gate]
        (let [r (ontology/classify-task {} {:task-signature "x"
                                            :threshold 0.7
                                            :bundle-threshold 0.5
                                            :walk-down? false})]
          (is (= :novel (:outcome r)))
          (is (not= distinct-class (:assigned-tree-id r)) "did NOT over-merge a low-fit distinct class")
          (is (true? (:was-fresh-mint? r)) "fresh-mint preserved below the bundle band")
          (is (not (true? (:bundled? r)))))))))

(deftest novel-empty-corpus-mints-fresh
  (testing "no candidates at all -> fresh mint (nothing to bundle to)"
    (with-redefs [ontology/search-descriptions (fn [_ _] [])
                  tc/get-consolidation-total* all-above-gate]
      (let [r (ontology/classify-task {} {:task-signature "x"
                                          :threshold 0.7
                                          :bundle-threshold 0.5
                                          :walk-down? false})]
        (is (= :novel (:outcome r)))
        (is (true? (:was-fresh-mint? r)))
        (is (not (true? (:bundled? r))))))))

(deftest bundle-only-considers-tree-class-candidates
  (testing "a :tree-fingerprint candidate in the bundle band is NOT a bundle target (convergence rides the :tree-class axis only)"
    (let [fp-id (random-uuid)]
      (with-redefs [ontology/search-descriptions
                    (fn [_ _] [(tree-fingerprint-candidate fp-id 0.62 12.0)])
                    tc/get-consolidation-total* all-above-gate]
        (let [r (ontology/classify-task {} {:task-signature "x"
                                            :threshold 0.7
                                            :bundle-threshold 0.5
                                            :walk-down? false})]
          (is (= :novel (:outcome r)))
          (is (not= fp-id (:assigned-tree-id r)) "did not bundle onto a :tree-fingerprint")
          (is (true? (:was-fresh-mint? r)))
          (is (not (true? (:bundled? r)))))))))

(deftest bundle-picks-best-of-multiple-tree-class-candidates
  (testing "RRF fusion picks the strongest in-band :tree-class candidate to bundle to"
    (let [weak (random-uuid) strong (random-uuid)]
      (with-redefs [ontology/search-descriptions
                    (fn [_ _] [(tree-class-candidate weak 0.52 6.0)
                               (tree-class-candidate strong 0.66 18.0)])
                    tc/get-consolidation-total* all-above-gate]
        (let [r (ontology/classify-task {} {:task-signature "x"
                                            :threshold 0.7
                                            :bundle-threshold 0.5
                                            :walk-down? false})]
          (is (= strong (:assigned-tree-id r)) "bundled to the strongest in-band candidate")
          (is (true? (:bundled? r))))))))

;; =============================================================================
;; PART 2 — evidence-gate retrieval (the SEPARATE retrieval gate)
;; =============================================================================

(deftest below-gate-tree-class-is-matchable-for-accrual-but-not-surfaced
  (testing "CV-1: a below-gate :tree-class is MATCHED (accrues) but is kept OUT of the surfaced references"
    (let [one-off (random-uuid)]
      (with-redefs [ontology/search-descriptions
                    ;; clears the match threshold; below the retrieval gate
                    (fn [_ _] [(tree-class-candidate one-off 0.95 25.0)])
                    tc/get-consolidation-total* (totals-fn {one-off 1})]
        (let [r (ontology/classify-task {} {:task-signature "x"
                                            :threshold 0.7
                                            :bundle-threshold 0.5
                                            :retrieval-gate 5
                                            :walk-down? false})]
          ;; ACCRUAL: matching ignores the gate now, so a described total-1
          ;; class is matched and its counter can grow (dissolves the deadlock).
          (is (= :matched (:outcome r)) "below-gate class is matchable for accrual")
          (is (= one-off (:assigned-tree-id r)) "assigned the below-gate class (it accrues)")
          (is (false? (:was-fresh-mint? r)) "a match is not a fresh-mint scatter")
          ;; SURFACING: the gate still keeps the one-off out of the references
          ;; the R-Inject prepend shows (its noise-suppression role).
          (is (empty? (filterv #(= one-off (-> % :document-metadata :target-id))
                               (:top-candidates r)))
              "the below-gate one-off is absent from the SURFACED reference set"))))))

(deftest gate-passes-curated-seed-tree-class-at-total-zero
  (testing "a curated baseline :tree-class (total 0 — recorded description, never runtime-classified) is NOT gated"
    ;; Count-only (>= gate) would wrongly demote every seed (all at total 0),
    ;; breaking baseline matching. The (0, gate) band keeps curated seeds
    ;; reachable while still filtering runtime one-offs.
    (let [seed (random-uuid)]
      (with-redefs [ontology/search-descriptions
                    (fn [_ _] [(tree-class-candidate seed 0.95 25.0)])
                    tc/get-consolidation-total* (totals-fn {seed 0})]
        (let [r (ontology/classify-task {} {:task-signature "x"
                                            :threshold 0.7
                                            :retrieval-gate 3
                                            :walk-down? false})]
          (is (= :matched (:outcome r)) "a curated seed at total 0 surfaces and matches")
          (is (= seed (:assigned-tree-id r)) "the seed class is assigned, not filtered"))))))

(deftest gate-surfaces-recurring-tree-class-once-it-crosses
  (testing "CV-1: the SAME class is matched at both totals, but flips from NOT-surfaced to surfaced once its total crosses the gate"
    (let [recurring (random-uuid)
          surfaced? (fn [r] (some #(= recurring (-> % :document-metadata :target-id))
                                  (:top-candidates r)))
          run (fn [total]
                (with-redefs [ontology/search-descriptions
                              (fn [_ _] [(tree-class-candidate recurring 0.95 25.0)])
                              tc/get-consolidation-total* (totals-fn {recurring total})]
                  (ontology/classify-task {} {:task-signature "x"
                                              :threshold 0.7
                                              :bundle-threshold 0.5
                                              :retrieval-gate 5
                                              :walk-down? false})))]
      (let [below (run 1) at-gate (run 5)]
        ;; ACCRUAL is gate-independent now — the class is matched at both totals.
        (is (= :matched (:outcome below)) "below gate: matchable for accrual")
        (is (= recurring (:assigned-tree-id below)) "below gate: assigned (accrues)")
        (is (= :matched (:outcome at-gate)) "at gate: still matched")
        (is (= recurring (:assigned-tree-id at-gate)))
        ;; SURFACING is what the gate governs — flips once total crosses it.
        (is (not (surfaced? below)) "below gate: NOT surfaced as a reference")
        (is (surfaced? at-gate) "at/above gate: surfaces as a reference")))))

(deftest gate-does-not-filter-tree-fingerprint
  (testing "the gate only applies to the :tree-class axis; :tree-fingerprint candidates are untouched"
    (let [fp (random-uuid)]
      (with-redefs [ontology/search-descriptions
                    (fn [_ _] [(tree-fingerprint-candidate fp 0.95 25.0)])
                    ;; total 0 for everything — would filter a tree-class, must NOT filter a fingerprint
                    tc/get-consolidation-total* (totals-fn {})]
        (let [r (ontology/classify-task {} {:task-signature "x"
                                            :threshold 0.7
                                            :retrieval-gate 5
                                            :walk-down? false})]
          (is (= :matched (:outcome r)) ":tree-fingerprint match is unaffected by the tree-class gate")
          (is (= fp (:assigned-tree-id r))))))))

;; =============================================================================
;; Regression — :matched / :uncertain unaffected by EL-1b
;; =============================================================================

(deftest matched-unaffected-by-el1b
  (testing "a confident match above threshold is still :matched (no bundle, no gate interference)"
    (let [m (random-uuid)]
      (with-redefs [ontology/search-descriptions
                    (fn [_ _] [(tree-class-candidate m 0.95 25.0)])
                    tc/get-consolidation-total* all-above-gate]
        (let [r (ontology/classify-task {} {:task-signature "x"
                                            :threshold 0.7
                                            :bundle-threshold 0.5
                                            :walk-down? false})]
          (is (= :matched (:outcome r)))
          (is (= m (:assigned-tree-id r)))
          (is (false? (:was-fresh-mint? r)))
          (is (not (true? (:bundled? r)))))))))

(deftest uncertain-unaffected-by-el1b
  (testing "a reranker fallback is still :uncertain — EL-1b does NOT bundle or mint under uncertainty"
    (let [u (random-uuid)]
      (with-redefs [ontology/search-descriptions
                    (fn [_ _] [{:content "x" :score 0.42 :rank 1
                                :document-id "tc::u"
                                :document-metadata {:granularity :tree-class
                                                    :target-id u :confidence 0.5}
                                :reasoning nil :fitness-score nil
                                :rerank-source :colbert-fallback}])
                    tc/get-consolidation-total* all-above-gate]
        (let [r (ontology/classify-task {} {:task-signature "x"
                                            :threshold 0.7
                                            :bundle-threshold 0.5
                                            :walk-down? false})]
          (is (= :uncertain (:outcome r)))
          (is (nil? (:assigned-tree-id r)) "DEFEAT CONDITION: uncertainty creates/bundles NOTHING")
          (is (not (true? (:bundled? r))))
          (is (not (true? (:was-fresh-mint? r)))))))))
