(ns ai.obney.orc.ontology.el3-three-state-outcome-test
  "EL-3 (ADR 0015, emergence loop): three-state classification :outcome —
   de-conflate uncertainty from novelty.

   classify-task + classify-behaviors return a first-class :outcome ∈
   {:matched :novel :uncertain} derived from (top-score, threshold,
   rerank-fallback?):
     :matched   — top-score >= threshold AND not fallback (confident fit;
                  assigned target-id; :was-fresh-mint? false — unchanged).
     :novel     — top-score <  threshold AND NOT fallback (confident no-match;
                  keep the novelty marker :was-fresh-mint? true — EL-1b will
                  later turn this into the emitted-tree capture).
     :uncertain — rerank-fallback? true (reranker fell back to raw ColBERT;
                  we do NOT know the fit). Detect-and-defer: NO fresh
                  random-uuid, NO :was-fresh-mint? true. Keep :rerank-fallback?
                  for the caution.

   Detect-and-defer (ADR 0015): the :uncertain outcome creates NOTHING and
   captures NOTHING. The defeat condition is an :uncertain outcome that still
   mints/assigns a class.

   These cycles drive search-descriptions via with-redefs so they exercise
   ONLY the outcome derivation + the not-mint-under-uncertain orchestration —
   the deterministic surface. The induced-fallback live proof + the E4-harness
   8/8→0/8 fallback-mint drop are separate (real grain + real ColBERT)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]))

;; =============================================================================
;; Candidate fixtures — shaped exactly as search-descriptions returns them
;; (per rerank_failure_surfacing_test.clj): a reranker-success result carries
;; numeric :fitness-score + :rerank-source :reranker; a fallback result carries
;; :fitness-score nil + :rerank-source :colbert-fallback.
;; =============================================================================

(defn- success-candidate
  "A reranker-success structural candidate at the given fitness."
  [target-id fitness]
  {:content "x" :score fitness :rank 1
   :document-id "tf::a"
   :document-metadata {:granularity :tree-fingerprint
                       :target-id target-id
                       :confidence 1.0}
   :reasoning "principle-shaped fit"
   :fitness-score fitness
   :rerank-source :reranker})

(defn- fallback-candidate
  "A ColBERT-fallback structural candidate (reranker fell back): fitness nil,
   reasoning nil, :rerank-source :colbert-fallback — what apply-rerank stamps."
  [target-id]
  {:content "x" :score 0.42 :rank 1
   :document-id "tf::a"
   :document-metadata {:granularity :tree-fingerprint
                       :target-id target-id
                       :confidence 0.5}
   :reasoning nil
   :fitness-score nil
   :rerank-source :colbert-fallback})

(defn- behavioral-success-candidate
  [target-id fitness]
  {:content "x" :score fitness :rank 1
   :document-id "bs::a"
   :document-metadata {:granularity :behavioral-subtree
                       :target-id target-id
                       :confidence 1.0}
   :reasoning "behavioral fit"
   :fitness-score fitness
   :rerank-source :reranker})

(defn- behavioral-fallback-candidate
  [target-id]
  {:content "x" :score 0.42 :rank 1
   :document-id "bs::a"
   :document-metadata {:granularity :behavioral-subtree
                       :target-id target-id
                       :confidence 0.5}
   :reasoning nil
   :fitness-score nil
   :rerank-source :colbert-fallback})

;; =============================================================================
;; classify-task — :outcome across all three branches
;; =============================================================================

(deftest classify-task-outcome-matched
  (testing "top-score >= threshold AND not fallback → :outcome :matched, assigned target-id, not a mint"
    (let [target (random-uuid)]
      (with-redefs [ontology/search-descriptions
                    (fn [_ _] [(success-candidate target 0.95)])]
        (let [r (ontology/classify-task {} {:task-signature "x"
                                            :threshold 0.7
                                            :walk-down? false})]
          (is (= :matched (:outcome r)) ":outcome is :matched on a confident fit")
          (is (= target (:assigned-tree-id r)) "assigned the matched target-id")
          (is (false? (:was-fresh-mint? r)) "a match is not a fresh-mint")
          (is (false? (:rerank-fallback? r)) "no fallback on the success path"))))))

(deftest classify-task-outcome-novel
  (testing "top-score < threshold AND NOT fallback (confident no-match) → :outcome :novel, novelty marker kept"
    (with-redefs [ontology/search-descriptions
                  (fn [_ _] [(success-candidate (random-uuid) 0.30)])]
      (let [r (ontology/classify-task {} {:task-signature "x"
                                          :threshold 0.7
                                          :walk-down? false})]
        (is (= :novel (:outcome r)) ":outcome is :novel on a confident no-match")
        (is (true? (:was-fresh-mint? r)) "novel KEEPS the existing :was-fresh-mint? novelty marker (EL-1b capture later)")
        (is (false? (:rerank-fallback? r)) "novel is NOT a fallback")
        (is (some? (:assigned-tree-id r)) "novel still carries the fresh-minted marker id")))))

(deftest classify-task-outcome-novel-empty-corpus
  (testing "no candidates at all (empty corpus) → :outcome :novel (confident no-match), not :uncertain"
    (with-redefs [ontology/search-descriptions (fn [_ _] [])]
      (let [r (ontology/classify-task {} {:task-signature "x"
                                          :threshold 0.7
                                          :walk-down? false})]
        (is (= :novel (:outcome r)) "empty corpus is a confident no-match, not uncertainty")
        (is (true? (:was-fresh-mint? r)))
        (is (false? (:rerank-fallback? r)))))))

(deftest classify-task-outcome-uncertain
  (testing "rerank-fallback? true → :outcome :uncertain, NO fresh-mint, NO assigned class"
    (with-redefs [ontology/search-descriptions
                  (fn [_ _] [(fallback-candidate (random-uuid))])]
      (let [r (ontology/classify-task {} {:task-signature "x"
                                          :threshold 0.7
                                          :walk-down? false})]
        (is (= :uncertain (:outcome r)) ":outcome is :uncertain under a reranker fallback")
        ;; Detect-and-defer: uncertainty creates NOTHING.
        (is (not (true? (:was-fresh-mint? r)))
            "DEFEAT CONDITION: :uncertain must NOT set :was-fresh-mint? true (that is the conflation)")
        (is (nil? (:assigned-tree-id r))
            "DEFEAT CONDITION: :uncertain must NOT fresh-mint a random-uuid as if novel")
        (is (true? (:rerank-fallback? r))
            ":rerank-fallback? kept true so the R-Inject caution still surfaces")))))

(deftest classify-task-outcome-uncertain-takes-precedence-over-novel
  (testing "fallback with a high raw colbert score is STILL :uncertain, never :matched/:novel"
    ;; A fallback candidate's :fitness-score is nil → top-score 0.0; but even a
    ;; non-nil raw colbert score under fallback must defer, not classify.
    (with-redefs [ontology/search-descriptions
                  (fn [_ _] [(assoc (fallback-candidate (random-uuid)) :fitness-score 0.95)])]
      (let [r (ontology/classify-task {} {:task-signature "x"
                                          :threshold 0.7
                                          :walk-down? false})]
        (is (= :uncertain (:outcome r))
            "fallback short-circuits to :uncertain regardless of the raw score")
        (is (not (true? (:was-fresh-mint? r))))
        (is (nil? (:assigned-tree-id r)))))))

;; =============================================================================
;; classify-behaviors — :outcome across all three branches
;; =============================================================================

(deftest classify-behaviors-outcome-matched
  (testing "a candidate above threshold AND not fallback → :outcome :matched"
    (with-redefs [ontology/search-descriptions
                  (fn [_ _] [(behavioral-success-candidate (random-uuid) 0.9)])]
      (let [r (ontology/classify-behaviors {} {:task-signature "x" :threshold 0.6 :top-n 3})]
        (is (= :matched (:outcome r)) ":outcome :matched when a behavior is kept")
        (is (false? (:rerank-fallback? r)))
        (is (false? (:was-fresh-mint? (first (:behaviors r)))) "kept behavior is not a mint")))))

(deftest classify-behaviors-outcome-novel
  (testing "no candidate above threshold AND NOT fallback → :outcome :novel, novelty marker kept"
    (with-redefs [ontology/search-descriptions
                  (fn [_ _] [(behavioral-success-candidate (random-uuid) 0.10)])]
      (let [r (ontology/classify-behaviors {} {:task-signature "x" :threshold 0.6 :top-n 3})]
        (is (= :novel (:outcome r)) ":outcome :novel on a confident behavioral no-match")
        (is (false? (:rerank-fallback? r)))
        (is (= 1 (count (:behaviors r))) "novel emits exactly one fresh-mint marker")
        (is (true? (:was-fresh-mint? (first (:behaviors r)))) "novel KEEPS the novelty marker")))))

(deftest classify-behaviors-outcome-uncertain
  (testing "rerank-fallback? true → :outcome :uncertain, NO behavioral fresh-mint"
    (with-redefs [ontology/search-descriptions
                  (fn [_ _] [(behavioral-fallback-candidate (random-uuid))])]
      (let [r (ontology/classify-behaviors {} {:task-signature "x" :threshold 0.6 :top-n 3})]
        (is (= :uncertain (:outcome r)) ":outcome :uncertain under a behavioral reranker fallback")
        (is (true? (:rerank-fallback? r)) ":rerank-fallback? kept for the caution")
        ;; Detect-and-defer: NO fresh-mint marker under uncertainty.
        (is (not-any? #(true? (:was-fresh-mint? %)) (:behaviors r))
            "DEFEAT CONDITION: :uncertain must NOT emit a behavioral :was-fresh-mint? true marker")))))
