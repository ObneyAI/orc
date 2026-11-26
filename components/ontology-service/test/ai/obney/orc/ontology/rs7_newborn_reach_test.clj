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
   enumeration.

   CV-E (`DomainFamilyIsALeafOnTheDomainAxis`, revised C5') removed routes
   (b) and (c) entirely: a domain family is never a shape-ranking candidate,
   so walk-down never descends into one and the index-match route cannot
   occur in production. Their tests below are replaced accordingly — see
   each replacement's own doc comment for what changed and why. Route (a)
   (a shape's existing per-parent child label) is UNCHANGED (CV-D already
   made it a judged proposal, never an unjudged direct land)."
  (:require [ai.obney.orc.ontology.core.queries :as queries]
            [ai.obney.orc.ontology.core.logic :as logic]
            [ai.obney.orc.ontology.core.commands :as commands]
            [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]))


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
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ pid]
                                        (when (= pid parent-id)
                                          [{:target-id child-id :domain-label "marathon-training-plan"}]))
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


(deftest walk-down-excludes-a-domain-family-never-reaches-it
  (testing "walk-down route (revised C5'): parent top-1 at fitness 0.8
            (below specificity-threshold 0.9, above the match threshold
            0.7); its only child is a domain family (via
            get-narrower-concepts/get-description, pure-stubbed) ->
            get-tree-class-children excludes it (:domain-family-parent-fn
            says it's a family) -> walk-down finds no children to consider
            and returns the parent unchanged, a plain :match — the family is
            never reached and the merge judge is never called"
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          candidate (tree-class-candidate parent-id 0.8
                      :domain-coverage :covered
                      :domain-label "irrelevant — the family is never reached"
                      :domain-reasoning "irrelevant")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)
                    ontology/get-narrower-concepts
                    (fn [_ uri]
                      (if (= uri (str "tree-class:" parent-id))
                        #{(str "tree-class:" child-id)}
                        #{}))
                    ontology/get-description (fn [_ _ _] {:summary "the domain family's pattern"})
                    logic/reranker-rerank!
                    (fn [_ opts]
                      (mapv (fn [c] {:document-id (:document-id c)
                                     :reasoning "would have walked into the domain family"
                                     :fitness-score 0.95})
                            (:candidates opts)))]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-merge-fn (fn [_ _] (throw (ex-info "merge judge must not be called" {})))
                  :domain-family-parent-fn
                  (fn [_ target-id]
                    (when (= target-id child-id)
                      {:parent-id parent-id :domain-label "marathon-training-plan"}))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)))
          (is (= parent-id (:assigned-tree-id r))
              "the family is never reached")
          (is (not= child-id (:assigned-tree-id r))))))))


(deftest index-match-on-a-domain-familys-own-id-covered-is-a-plain-match-no-merge-call
  (testing "a family's own id can no longer reach top-1 in production; IF it
            somehow did (an input only this pure test can still construct),
            a :covered verdict is a plain match like any other candidate —
            the domain axis short-circuits before any family-parent lookup
            on the assigned class runs, so the merge judge is never asked"
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          candidate (tree-class-candidate child-id 0.95
                      :domain-coverage :covered
                      :domain-label "Marathon Training Plan"
                      :domain-reasoning "Fully covered by the existing class.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-merge-fn (fn [_ _] (throw (ex-info "merge judge must not be called on a covered match" {})))
                  :domain-family-parent-fn
                  (fn [_ target-id]
                    (when (= target-id child-id)
                      {:parent-id parent-id :domain-label "marathon-training-plan"}))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)))
          (is (= child-id (:assigned-tree-id r)))
          (is (nil? (:parent-tree-id r)))
          (is (not (contains? r :domain-deferral))))))))
