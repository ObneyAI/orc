(ns ai.obney.orc.ontology.rs7-newborn-reach-test
  "RS-7 Slice 0 (domain-child convergence arc) — pins, deterministically and
   on structured data, the THREE ways today's runtime reaches a domain
   child, before any fix slice lands:

     (a) graph landing  — the parent is top-1; an EXISTING domain child's
                           label matches the judged label (via
                           :domain-children-fn); the child is NEVER a
                           search/index candidate.
     (b) walk-down       — the parent is top-1 at moderate confidence;
                           walk-down descends the graph into a domain
                           child.
     (c) index match     — the domain child is ITSELF the top-1 search
                           candidate: a :covered verdict is a plain match
                           on the child; a :partial verdict mints a
                           GRANDCHILD under it (today's runtime does not
                           treat a domain child as a leaf on the domain
                           axis).

   Each route asserts on :assigned-via, :assigned-tree-id, :parent-tree-id
   so Slice 1 (DomainChildIsALeafOnTheDomainAxis) and its siblings can flip
   these tests BY NAME instead of guessing today's behavior. (b) mirrors
   rs5's walk-down-into-a-newborn-returns-walk-down-provenance-not-a-landing
   on a REAL minted child + event store; this file exercises the same route
   pure/stubbed, consistent with rs2's style. (c) mirrors rs2's
   newborn-as-top-1-match-* tests, pinned again here under the reach-route
   enumeration."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.task-classifier :as tc]))

;; =============================================================================
;; Candidate fixture — shaped exactly as search-descriptions returns them
;; (per el3/el1b/cc23/rs2), optionally carrying RS-1's domain verdict.
;; =============================================================================

(defn- tree-class-candidate
  [id fitness & {:keys [domain-coverage domain-label domain-reasoning]
                 :as opts}]
  (cond-> {:content "x" :score fitness :rank 1
           :document-id (str id)
           :document-metadata {:granularity :tree-class
                               :target-id (str id)
                               :confidence 1.0}
           :reasoning "principle-shaped fit"
           :fitness-score fitness
           :rerank-source :reranker}
    (contains? opts :domain-coverage)  (assoc :domain-coverage domain-coverage)
    (contains? opts :domain-label)     (assoc :domain-label domain-label)
    (contains? opts :domain-reasoning) (assoc :domain-reasoning domain-reasoning)))

;; =============================================================================
;; (a) Graph landing — the parent is top-1; an EXISTING domain child's label
;; matches the judged label; the child is NEVER a search/index candidate.
;; =============================================================================

(deftest graph-landing-lands-on-an-existing-child-not-among-the-candidates
  (testing "graph-landing route: parent top-1 at high confidence (skips
            walk-down), an existing domain child (surfaced only via
            :domain-children-fn, never a search candidate) whose label
            matches the judged label -> :land-on-domain-child on that
            child, the child ABSENT from :top-candidates"
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          candidate (tree-class-candidate parent-id 0.95
                      :domain-coverage :covered
                      :domain-label "Marathon Training Plan"
                      :domain-reasoning "Covered — a domain child already exists for this.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ pid]
                                        (when (= pid parent-id)
                                          [{:target-id child-id :domain-label "marathon-training-plan"}]))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :land-on-domain-child (:assigned-via r)))
          (is (= child-id (:assigned-tree-id r)))
          (is (= parent-id (:parent-tree-id r)))
          (is (not (some #(= child-id (-> % :document-metadata :target-id))
                         (:top-candidates r)))
              "the landed-on child was NEVER a search candidate — reached via
               the graph (domain-children-fn), not the index"))))))

;; =============================================================================
;; (b) Walk-down — the parent is top-1 at moderate confidence; walk-down
;; descends the graph into a domain child. See rs5's
;; walk-down-into-a-newborn-returns-walk-down-provenance-not-a-landing for
;; the REAL-event-store version of this same route.
;; =============================================================================

(deftest walk-down-reaches-a-domain-child-as-walk-down-not-a-landing
  (testing "walk-down route: parent top-1 at fitness 0.8 (below
            specificity-threshold 0.9, above the match threshold 0.7) ->
            walk-down descends into its domain child (via
            get-narrower-concepts/get-description, pure-stubbed) ->
            :assigned-via :walk-down, assigned id = the child,
            :parent-tree-id = the parent, no :domain-label"
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          candidate (tree-class-candidate parent-id 0.8
                      :domain-coverage :partial
                      :domain-label "irrelevant — a walk-down is not a :match"
                      :domain-reasoning "irrelevant")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)
                    ontology/get-narrower-concepts
                    (fn [_ uri]
                      (if (= uri (str "tree-class:" parent-id))
                        #{(str "tree-class:" child-id)}
                        #{}))
                    ontology/get-description (fn [_ _ _] {:summary "the domain child's pattern"})
                    reranker/rerank!
                    (fn [_ opts]
                      (mapv (fn [c] {:document-id (:document-id c)
                                     :reasoning "walks down into the domain child"
                                     :fitness-score 0.95})
                            (:candidates opts)))]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :walk-down (:assigned-via r)))
          (is (= child-id (:assigned-tree-id r)))
          (is (= parent-id (:parent-tree-id r)))
          (is (not (contains? r :domain-label))
              "walk-down provenance is not a domain landing today"))))))

;; =============================================================================
;; (c) Index match — the domain child is ITSELF the top-1 search candidate.
;; A :covered verdict is a plain match on the child; a :partial verdict
;; mints a GRANDCHILD under it (today's runtime does not treat a domain
;; child as a leaf on the domain axis). Same scenario as rs2's
;; newborn-as-top-1-match-* tests, pinned here under the reach-route
;; enumeration.
;; =============================================================================

(deftest index-match-on-the-domain-child-itself-is-a-plain-match
  (testing "index-match route: the domain child is itself top-1 (high
            confidence, skips walk-down), :covered coverage -> a plain
            :match ON THE CHILD, no :domain-label, no :parent-tree-id"
    (let [child-id (random-uuid)
          candidate (tree-class-candidate child-id 0.95
                      :domain-coverage :covered
                      :domain-label "Marathon Training Plan"
                      :domain-reasoning "Fully covered by the existing class.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)))
          (is (= child-id (:assigned-tree-id r)))
          (is (not (contains? r :domain-label)))
          (is (nil? (:parent-tree-id r))))))))

(deftest index-match-on-the-domain-child-itself-with-partial-mints-a-grandchild
  (testing "index-match route: the domain child is itself top-1, :partial
            coverage -> mints a GRANDCHILD under it -> :assigned-via
            :mint-domain-child, :parent-tree-id = the domain child itself"
    (let [child-id (random-uuid)
          candidate (tree-class-candidate child-id 0.95
                      :domain-coverage :partial
                      :domain-label "Ultra Long Run"
                      :domain-reasoning "A more extreme variant of the child's own shape.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :mint-domain-child (:assigned-via r)))
          (is (= child-id (:parent-tree-id r))
              "the domain child reached via the index is treated as the
               PARENT of a freshly minted grandchild")
          (is (true? (:was-fresh-mint? r))))))))
