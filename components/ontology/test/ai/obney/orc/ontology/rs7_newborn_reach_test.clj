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
            matches the judged label -> now a PROPOSED landing (CV-D: a
            per-parent child label match is judged, not landed directly).
            A :same verdict naming that child -> :land-on-domain-child on
            that child, the child ABSENT from :top-candidates"
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
                                          [{:target-id child-id :domain-label "marathon-training-plan"}]))
                  ;; pure test, no store: declare the family seams explicitly
                  :domain-families-fn (fn [_] [])
                  :domain-merge-fn (fn [_ _] {:kind :same :family child-id})
                  :domain-family-parent-fn (fn [_ id] (when (= id child-id)
                                                        {:parent-id parent-id
                                                         :domain-label "marathon-training-plan"}))}
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
;; descends the graph into a domain family, which is a LEAF on the domain
;; axis (DomainFamilyIsALeafOnTheDomainAxis): the descent is a LANDING, not
;; a :walk-down. See rs5's
;; walk-down-into-a-family-lands-on-it-no-walk-down-provenance for the
;; REAL-event-store version of this same route.
;; =============================================================================

(deftest walk-down-reaches-a-domain-family-as-a-landing
  (testing "walk-down route: parent top-1 at fitness 0.8 (below
            specificity-threshold 0.9, above the match threshold 0.7) ->
            walk-down descends into its domain family (via
            get-narrower-concepts/get-description, pure-stubbed) -> the
            family-is-leaf check (:domain-family-parent-fn) widens the
            result into a landing: :assigned-via :land-on-domain-child,
            assigned id = the family, :parent-tree-id = the family's OWN
            birth shape, :domain-label = the family's own label"
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          candidate (tree-class-candidate parent-id 0.8
                      :domain-coverage :partial
                      :domain-label "irrelevant — a landing on a family never consults the verdict"
                      :domain-reasoning "irrelevant")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)
                    ontology/get-narrower-concepts
                    (fn [_ uri]
                      (if (= uri (str "tree-class:" parent-id))
                        #{(str "tree-class:" child-id)}
                        #{}))
                    ontology/get-description (fn [_ _ _] {:summary "the domain family's pattern"})
                    reranker/rerank!
                    (fn [_ opts]
                      (mapv (fn [c] {:document-id (:document-id c)
                                     :reasoning "walks down into the domain family"
                                     :fitness-score 0.95})
                            (:candidates opts)))]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-merge-fn (fn [_ _] {:kind :same :family child-id})
                  :domain-family-parent-fn
                  (fn [_ target-id]
                    (when (= target-id child-id)
                      {:parent-id parent-id :domain-label "marathon-training-plan"}))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :land-on-domain-child (:assigned-via r)))
          (is (= child-id (:assigned-tree-id r)))
          (is (= parent-id (:parent-tree-id r))
              "the family's own birth shape — here the same id it walked down from")
          (is (= "marathon-training-plan" (:domain-label r))
              "a landing carries the family's own label"))))))

;; =============================================================================
;; (c) Index match — the domain family is ITSELF the top-1 search candidate.
;; DomainFamilyIsALeafOnTheDomainAxis: whatever the judged coverage, this is
;; a LANDING on the family, never a grandchild. Same scenario as rs2's
;; newborn-as-top-1-match-* tests, pinned here under the reach-route
;; enumeration.
;; =============================================================================

(deftest index-match-on-the-domain-family-itself-is-a-landing-covered
  (testing "index-match route: the domain family is itself top-1 (high
            confidence, skips walk-down), :covered coverage -> a landing ON
            THE FAMILY, its own label, its own birth shape as :parent-tree-id"
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          candidate (tree-class-candidate child-id 0.95
                      :domain-coverage :covered
                      :domain-label "Marathon Training Plan"
                      :domain-reasoning "Fully covered by the existing class.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-merge-fn (fn [_ _] {:kind :same :family child-id})
                  :domain-family-parent-fn
                  (fn [_ target-id]
                    (when (= target-id child-id)
                      {:parent-id parent-id :domain-label "marathon-training-plan"}))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :land-on-domain-child (:assigned-via r)))
          (is (= child-id (:assigned-tree-id r)))
          (is (= "marathon-training-plan" (:domain-label r)))
          (is (= parent-id (:parent-tree-id r))))))))

(deftest index-match-on-the-domain-family-itself-is-a-landing-partial-no-grandchild
  (testing "index-match route: the domain family is itself top-1, :partial
            coverage -> STILL a landing, never a grandchild — the verdict is
            not consulted once the reached class IS the family"
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          candidate (tree-class-candidate child-id 0.95
                      :domain-coverage :partial
                      :domain-label "Ultra Long Run"
                      :domain-reasoning "A more extreme variant of the family's own shape.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-merge-fn (fn [_ _] {:kind :same :family child-id})
                  :domain-family-parent-fn
                  (fn [_ target-id]
                    (when (= target-id child-id)
                      {:parent-id parent-id :domain-label "marathon-training-plan"}))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :land-on-domain-child (:assigned-via r))
              "a landing, never a :mint-domain-child — no grandchild")
          (is (= child-id (:assigned-tree-id r)))
          (is (= parent-id (:parent-tree-id r))
              "the family's OWN birth shape — never the family itself as a parent")
          (is (= "marathon-training-plan" (:domain-label r)))
          (is (false? (:was-fresh-mint? r))))))))
