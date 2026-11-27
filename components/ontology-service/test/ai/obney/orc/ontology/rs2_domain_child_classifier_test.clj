(ns ai.obney.orc.ontology.rs2-domain-child-classifier-test
  "RS-2: after the existing match/bundle/walk-down/deferral logic has
   produced an outcome, the classifier applies the assigned candidate's
   domain verdict and label (RS-1's :domain-coverage/:domain-label/
   :domain-reasoning) and produces the domain-child assignment the spec's
   three rules describe (`MintDomainChild` / `LandOnDomainChild` /
   `MintSiblingDomainChild`). Pure classifier work: no event is recorded
   here (RS-3 does that); the result map carries everything RS-3 needs.

   Seam 1 — `classify-task` with the reranker stubbed via
   `ontology/search-descriptions` to a typed payload (prior art: el3, walk-
   down, el1b, cc23). apply-rerank's OWN join is exercised one level lower,
   stubbing `reranker/rerank!` directly (prior art: rr1)."
  (:require [ai.obney.orc.ontology.core.queries :as queries]
            [ai.obney.orc.ontology.core.logic :as logic]
            [ai.obney.orc.ontology.core.commands :as commands]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.test-helpers :as th]))

(use-fixtures :each
  (fn [t]
    (with-redefs [logic/default-domain-families-fn (fn [_ctx] [])
                  logic/default-domain-family-parent-fn (fn [_ctx _target-id] nil)
                  logic/nearest-families (fn [_ctx _query] [])]
      (t))))


(def ^:private sample-candidates
  [{:content "marathon training plan generator"
    :score 0.8 :rank 1 :document-id "a"
    :document-metadata {:granularity :tree-class
                        :target-id (str (random-uuid))
                        :confidence 0.9 :last-update "2026"}}])

(deftest apply-rerank-join-carries-domain-verdict-fields
  (testing "apply-rerank's JOIN carries :domain-coverage/:domain-label/
            :domain-reasoning from the reranked entry onto the returned
            candidate, alongside the existing :reasoning/:fitness-score/
            :rerank-source triple"
    (th/with-test-context [ctx]
      (with-redefs [logic/reranker-rerank!
                    (fn [_ctx _opts]
                      [{:document-id "a"
                        :reasoning "Fits the structured-output need."
                        :fitness-score 0.87
                        :domain-coverage :partial
                        :domain-label "marathon-training-plan"
                        :domain-reasoning "Shares subject matter but not the output kind."}])]
        (let [[joined] (#'queries/apply-rerank ctx sample-candidates "intent" "query" 5 nil)]
          (is (= :partial (:domain-coverage joined)))
          (is (= "marathon-training-plan" (:domain-label joined)))
          (is (= "Shares subject matter but not the output kind."
                 (:domain-reasoning joined)))
          (is (= 0.87 (:fitness-score joined)) "existing fields still carried")
          (is (= :reranker (:rerank-source joined)) "existing fields still carried"))))))


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

(defn- expected-domain-child-id
  "Mirrors the production `stable-domain-child-identity` derivation
   (`stable_domain_child_identity`): UUID/nameUUIDFromBytes over
   \"domain-child:\" + parent-id + \":\" + the CANONICAL label."
  [parent-id canonical-label]
  (java.util.UUID/nameUUIDFromBytes
    (.getBytes (str "domain-child:" parent-id ":" canonical-label) "UTF-8")))


(deftest no-children-partial-verdict-mints-domain-child
  (testing "a :tree-class :match with NO domain children and a :partial verdict
            mints a domain child: the derived identity, the parent, the label,
            :assigned-via :mint-domain-child, :was-fresh-mint? true — and the
            SAME task classified again derives the IDENTICAL child identity
            (@invariant DomainChildIdentityIsStable)"
    (let [class-id (random-uuid)
          candidates [(tree-class-candidate class-id 0.95
                        :domain-coverage :partial
                        :domain-label "Marathon Training Plan"
                        :domain-reasoning "Shares subject matter but not the output kind.")]
          expected-id (expected-domain-child-id class-id "marathon-training-plan")]
      (with-redefs [ontology/search-descriptions (fn [_ _] candidates)
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [ctx {:domain-children-fn (fn [_ _] [])}
              opts {:task-signature "x" :threshold 0.7}
              r1 (ontology/classify-task ctx opts)
              r2 (ontology/classify-task ctx opts)]
          (is (= :mint-domain-child (:assigned-via r1)))
          (is (= expected-id (:assigned-tree-id r1))
              "derived from the parent + the CANONICAL (trim/lower/hyphenated) label")
          (is (= class-id (:parent-tree-id r1)))
          (is (= "marathon-training-plan" (:domain-label r1))
              "canonicalised: trim + lower-case + spaces -> hyphens")
          (is (true? (:was-fresh-mint? r1)))
          (is (= {:domain-coverage :partial
                  :domain-label "Marathon Training Plan"
                  :domain-reasoning "Shares subject matter but not the output kind."}
                 (:domain-verdict r1))
              "the RAW verdict is carried on :domain-verdict, unnormalised")
          (is (= expected-id (:assigned-tree-id r2))
              "the SAME task classified again derives the IDENTICAL child identity"))))))


(deftest no-children-uncovered-verdict-mints-domain-child
  (testing "an :uncovered verdict behaves exactly like :partial (no children): mints"
    (let [class-id (random-uuid)
          candidates [(tree-class-candidate class-id 0.95
                        :domain-coverage :uncovered
                        :domain-label "Recipe Scaling"
                        :domain-reasoning "No existing coverage for this shape.")]
          expected-id (expected-domain-child-id class-id "recipe-scaling")]
      (with-redefs [ontology/search-descriptions (fn [_ _] candidates)
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :mint-domain-child (:assigned-via r)))
          (is (= expected-id (:assigned-tree-id r)))
          (is (= class-id (:parent-tree-id r)))
          (is (= "recipe-scaling" (:domain-label r)))
          (is (true? (:was-fresh-mint? r))))))))

(deftest no-children-covered-verdict-is-byte-identical-plus-domain-verdict
  (testing "a :covered verdict (no children) changes NOTHING about the match —
            byte-identical to today's plain :match result — plus :domain-verdict
            and :domain-children-considered carried"
    (let [class-id (random-uuid)
          candidate-covered (tree-class-candidate class-id 0.95
                              :domain-coverage :covered
                              :domain-label "Marathon Training Plan"
                              :domain-reasoning "Fully covered by the existing class.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate-covered])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)))
          (is (= class-id (:assigned-tree-id r)) "still the PARENT, no child minted")
          (is (false? (:was-fresh-mint? r)))
          (is (nil? (:parent-tree-id r)) "a plain :match carries no :parent-tree-id, same as before RS-2")
          (is (= {:domain-coverage :covered
                  :domain-label "Marathon Training Plan"
                  :domain-reasoning "Fully covered by the existing class."}
                 (:domain-verdict r)))
          (is (= [] (:domain-children-considered r)))
          (is (not (contains? r :domain-deferral))
              ":covered never defers — the axis was resolved"))))))


(deftest no-children-unknown-verdict-defers
  (testing "an explicit :unknown verdict (no children) → the plain :match result
            plus :domain-deferral {:axis :domain}; no child, no identity change"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :unknown
                      :domain-label nil
                      :domain-reasoning nil)]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)) "no re-provenance — the shape assignment stands")
          (is (= class-id (:assigned-tree-id r)) "no identity change — still the parent")
          (is (false? (:was-fresh-mint? r)))
          (is (= :domain (:axis (:domain-deferral r))))
          (is (= {:domain-coverage :unknown :domain-label nil :domain-reasoning nil}
                 (:domain-verdict r))))))))

(deftest no-children-missing-verdict-defers
  (testing "a MISSING verdict (pre-RS-1 candidate, no domain keys at all; no
            children) → the plain :match result plus :domain-deferral
            {:axis :domain}; no child, no identity change"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95)]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)) "no re-provenance — the shape assignment stands")
          (is (= class-id (:assigned-tree-id r)) "no identity change — still the parent")
          (is (false? (:was-fresh-mint? r)))
          (is (= :domain (:axis (:domain-deferral r))))
          (is (= {} (:domain-verdict r))
              "the verdict is carried as-seen: all three keys absent on the candidate
               stay ABSENT (select-keys never fabricates nils) — the CC-31 omit-not-nil
               idiom this codebase uses elsewhere"))))))


(deftest children-present-matching-label-lands-on-existing-child
  (testing "a domain child whose :domain-label equals the (normalised) judged
            label → the MATCH is now a PROPOSED landing on that child,
            judged (CV-D, DomainFamilyMergeIsJudged revised C3': a per-parent
            existing-child label match is no longer an unjudged direct
            land). A :same verdict naming that exact child -> assign THAT
            child's identity, :assigned-via :land-on-domain-child, the
            coverage verdict still IGNORED even when :covered (D7b: once a
            class has children the reranker calls it covered regardless, so
            the coverage verdict is consulted ONLY when the parent has no
            children — the merge judge's OWN verdict is a separate, always-
            asked question under CV-D)"
    (let [class-id (random-uuid)
          sibling-id (random-uuid)
          existing-child-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :covered
                      :domain-label "Marathon Training Plan"
                      :domain-reasoning "Covered — a domain child already exists for this.")
          children [{:target-id existing-child-id :domain-label "marathon-training-plan"}
                    {:target-id sibling-id :domain-label "recipe-scaling"}]
          merge-calls (atom [])]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] children)
                  :domain-merge-fn (fn [_ q] (swap! merge-calls conj q)
                                     {:kind :same :family existing-child-id})
                  :domain-family-parent-fn
                  (fn [_ id] (when (= id existing-child-id)
                              {:parent-id class-id :domain-label "marathon-training-plan"}))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= 1 (count @merge-calls))
              "CV-D flip: the per-parent existing-child label match is now judged, not landed directly")
          (is (= existing-child-id (:proposed (first @merge-calls)))
              "the matching existing child rode as the proposed family")
          (is (= :land-on-domain-child (:assigned-via r)))
          (is (= existing-child-id (:assigned-tree-id r))
              "assigned the MATCHING child's identity, not a derived one")
          (is (= class-id (:parent-tree-id r)))
          (is (false? (:was-fresh-mint? r)))
          (is (= "marathon-training-plan" (:domain-label r))
              "LandOnDomainChild records the landed child's canonical label — the
               spec's TaskClassified carries domain_label on every rule, and the
               R-Inject render (RS-4) names the child by it")
          (is (= #{"marathon-training-plan" "recipe-scaling"}
                 (set (:domain-children-considered r))))
          (is (= {:domain-coverage :covered
                  :domain-label "Marathon Training Plan"
                  :domain-reasoning "Covered — a domain child already exists for this."}
                 (:domain-verdict r))
              "the verdict is still CARRIED for RS-3, even though it was not CONSULTED")
          (is (= {:kind :same :family existing-child-id} (:merge-verdict r))
              "CV-D: the merge judge's own verdict is now carried too"))))))

(deftest children-present-new-label-same-verdict-lands-on-the-named-family
  (let [class-id (random-uuid) family-id (random-uuid) family-parent (random-uuid)
        candidate (tree-class-candidate class-id 0.95
                    :domain-coverage :partial
                    :domain-label "Weekly Meal Plan"
                    :domain-reasoning "Meal planning.")
        children [{:target-id (random-uuid) :domain-label "recipe-scaling"}]]
    (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                  queries/get-consolidation-total* (fn [_ _ _] 0)]
      (testing "same → lands on the named family, under the family's own parent"
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] children)
                  :domain-merge-fn (fn [_ _] {:kind :same :family family-id})
                  :domain-family-parent-fn (fn [_ id] (when (= id family-id)
                                                        {:parent-id family-parent
                                                         :domain-label "meal-planning"}))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :land-on-domain-child (:assigned-via r)))
          (is (= family-id (:assigned-tree-id r)))
          (is (= family-parent (:parent-tree-id r)))
          (is (= "meal-planning" (:domain-label r)))
          (is (false? (:was-fresh-mint? r)))))
      (testing "same naming a non-family → deferral :merge-unresolved, nothing assigned to it"
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] children)
                  :domain-merge-fn (fn [_ _] {:kind :same :family (random-uuid)})
                  :domain-family-parent-fn (fn [_ _] nil)}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :merge-unresolved (get-in r [:domain-deferral :reason])))
          (is (not= :land-on-domain-child (:assigned-via r))))))))


(deftest children-present-new-label-mints-sibling-domain-child
  (testing "a judged label matching NO existing domain child → the merge
            judge is asked, and on :new a family is born under the shape:
            the derived identity, :assigned-via :mint-domain-child with the
            merge verdict carried, the coverage verdict NOT consulted"
    (let [class-id (random-uuid)
          sibling-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :unknown
                      :domain-label "Weekly Meal Plan"
                      :domain-reasoning "A new domain shape not seen before.")
          children [{:target-id sibling-id :domain-label "recipe-scaling"}]
          expected-id (expected-domain-child-id class-id "weekly-meal-plan")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [merge-calls (atom 0)
              r (ontology/classify-task {:domain-children-fn (fn [_ _] children)
                                         :domain-merge-fn (fn [_ _] (swap! merge-calls inc) {:kind :new})}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= 1 @merge-calls) "the second family under a shape is judged too")
          (is (= :mint-domain-child (:assigned-via r)))
          (is (= {:kind :new} (:merge-verdict r)))
          (is (= expected-id (:assigned-tree-id r)) "derived from parent + the CANONICAL new label")
          (is (= class-id (:parent-tree-id r)))
          (is (= "weekly-meal-plan" (:domain-label r)))
          (is (true? (:was-fresh-mint? r)))
          (is (= ["recipe-scaling"] (:domain-children-considered r))))))))


(deftest matched-leaf-with-domain-children-considered-above-specificity-threshold
  (testing "top-1 fitness (0.95) >= the default :specificity-threshold (0.9)
            takes the walk-down 'don't walk, trust top-1' branch — walk-down's
            OWN children lookup (get-narrower-concepts) is never consulted —
            yet the domain-child sibling logic STILL runs and still mints"
    (let [class-id (random-uuid)
          sibling-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :uncovered
                      :domain-label "Ultra Marathon Plan"
                      :domain-reasoning "A distinct, more extreme training shape.")
          children [{:target-id sibling-id :domain-label "marathon-training-plan"}]
          walk-down-lookup-called? (atom false)
          expected-id (expected-domain-child-id class-id "ultra-marathon-plan")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)
                    ontology/get-narrower-concepts
                    (fn [_ _] (reset! walk-down-lookup-called? true) #{})]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] children)}
                                        {:task-signature "x" :threshold 0.7
                                         :walk-down? true})]
          (is (>= 0.95 0.95) "sanity: at the specificity threshold")
          (is (false? @walk-down-lookup-called?)
              "walk-down's OWN children lookup is never reached — the specificity
               gate returns top-1 without descending")
          (is (= :mint-domain-child (:assigned-via r))
              "domain children were STILL considered — the class is NOT treated as a leaf")
          (is (= expected-id (:assigned-tree-id r)))
          (is (true? (:was-fresh-mint? r))))))))


(deftest bundle-branch-untouched-by-domain-child-logic
  (testing "an in-band near-miss BUNDLE onto an existing tree-class carries NO
            domain-child keys at all, even when the candidate has a domain
            verdict — :bundle is not a :match, so the guard never fires"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.65
                      :domain-coverage :partial
                      :domain-label "Some Label"
                      :domain-reasoning "irrelevant — bundle is not a :match")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :bundle (:assigned-via r)) "sanity: bundle branch reached")
          (is (= class-id (:assigned-tree-id r)))
          (is (not (contains? r :domain-verdict)))
          (is (not (contains? r :domain-children-considered)))
          (is (not (contains? r :domain-deferral))))))))

(deftest walk-down-own-fresh-mint-untouched-by-domain-child-logic
  (testing "walk-down's OWN fresh-leaf-mint (descended at least once, the
            deeper level found nothing above threshold) reports :assigned-via
            :mint and carries NO domain-child keys — a :mint is not a :match"
    (let [parent-id (random-uuid)
          child1-id (random-uuid)
          child2-id (random-uuid)
          candidate (tree-class-candidate parent-id 0.75
                      :domain-coverage :partial
                      :domain-label "Some Label"
                      :domain-reasoning "irrelevant — a walk-down :mint is not a :match")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)
                    ontology/get-narrower-concepts
                    (fn [_ uri]
                      (cond
                        (= uri (str "tree-class:" parent-id)) #{(str "tree-class:" child1-id)}
                        (= uri (str "tree-class:" child1-id)) #{(str "tree-class:" child2-id)}
                        :else #{}))
                    ontology/get-description (fn [_ _ _] {:summary "a child pattern"})
                    logic/reranker-rerank!
                    (fn [_ opts]
                      (let [doc-id (:document-id (first (:candidates opts)))]
                        (cond
                          (= doc-id (str child1-id))
                          [{:document-id doc-id :reasoning "fits" :fitness-score 0.8}]
                          :else [])))]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.5})]
          (is (= :mint (:assigned-via r)) "sanity: walk-down's own fresh-mint reached")
          (is (true? (:was-fresh-mint? r)))
          (is (= child1-id (:parent-tree-id r)) "sanity: minted under the deepest matched ancestor")
          (is (not (contains? r :domain-verdict)))
          (is (not (contains? r :domain-children-considered)))
          (is (not (contains? r :domain-deferral))))))))

(deftest uncertain-branch-untouched-by-domain-child-logic
  (testing "a reranker fallback (:outcome :uncertain) carries NO domain-child
            keys — :uncertain has no :assigned-via at all, so the guard never
            fires"
    (let [candidates [{:content "x" :score 0.3 :document-id "a"
                       :document-metadata {:granularity :tree-class
                                           :target-id (str (random-uuid))
                                           :confidence 0.5}
                       :reasoning nil
                       :fitness-score nil
                       :rerank-source :colbert-fallback}]]
      (with-redefs [ontology/search-descriptions (fn [_ _] candidates)
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :uncertain (:outcome r)) "sanity: fallback reached")
          (is (not (contains? r :assigned-via)))
          (is (not (contains? r :domain-verdict)))
          (is (not (contains? r :domain-children-considered)))
          (is (not (contains? r :domain-deferral))))))))

(deftest tree-fingerprint-axis-match-untouched-by-domain-child-logic
  (testing "a :match on the :tree-fingerprint axis is byte-identical to today
            — no domain-child keys added — even defensively, if the candidate
            somehow carried domain fields (RS-1 only instructs the reranker to
            judge domain fit for :tree-class candidates)"
    (let [fp-id (random-uuid)
          candidate (-> (tree-class-candidate fp-id 0.95
                          :domain-coverage :partial
                          :domain-label "Some Label"
                          :domain-reasoning "should never be read — wrong axis")
                        (assoc-in [:document-metadata :granularity] :tree-fingerprint))]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)) "sanity: matched")
          (is (= fp-id (:assigned-tree-id r)))
          (is (not (contains? r :domain-verdict)))
          (is (not (contains? r :domain-children-considered)))
          (is (not (contains? r :domain-deferral))))))))

(deftest blank-label-defers-instead-of-minting-no-children
  (testing "a blank/whitespace-only label where a domain child WOULD be
            minted (no children, :partial coverage) → defers, mints NOTHING —
            'no child from an empty label'"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "   "
                      :domain-reasoning "the model gave a blank label")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)) "no re-provenance — no mint happened")
          (is (= class-id (:assigned-tree-id r)) "no identity change")
          (is (false? (:was-fresh-mint? r)))
          (is (= :domain (:axis (:domain-deferral r)))))))))

(deftest blank-label-defers-instead-of-minting-sibling
  (testing "a blank/whitespace-only label where a SIBLING would be minted
            (children present, matches none) → defers, mints NOTHING"
    (let [class-id (random-uuid)
          sibling-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :covered
                      :domain-label ""
                      :domain-reasoning "the model gave a blank label")
          children [{:target-id sibling-id :domain-label "recipe-scaling"}]]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] children)}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)) "no re-provenance — no mint happened")
          (is (= class-id (:assigned-tree-id r)) "no identity change")
          (is (false? (:was-fresh-mint? r)))
          (is (= :domain (:axis (:domain-deferral r))))
          (is (= ["recipe-scaling"] (:domain-children-considered r))))))))



(deftest covered-top-1-with-a-family-parent-fn-is-a-plain-match-no-merge-call
  (testing "a :covered top-1 match never reaches the merge judge, whatever
            :domain-family-parent-fn would say about its id — coverage
            :covered short-circuits the domain axis before any family lookup
            on the ASSIGNED class runs (`DomainChildrenAreAlwaysConsidered`'s
            covered no-op)"
    (let [family-parent-id (random-uuid)
          candidate-id (random-uuid)
          candidate (tree-class-candidate candidate-id 0.95
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
                    (when (= target-id candidate-id)
                      {:parent-id family-parent-id :domain-label "marathon-training-plan"}))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)))
          (is (= candidate-id (:assigned-tree-id r)))
          (is (nil? (:parent-tree-id r)))
          (is (not (contains? r :domain-deferral))
              "a covered match is neither a landing nor a deferral — plain base")
          (is (false? (:was-fresh-mint? r))))))))


(deftest label-existing-under-another-parent-lands-on-that-family
  (testing "top-1 = shape B (no legacy children, no covered neighbour), but
            a domain FAMILY already exists tenant-wide under a DIFFERENT
            shape A with the matching canonical label -> now a PROPOSED
            landing (CV-D: the tenant-wide label match is judged, not
            landed directly). A :same verdict naming that family -> lands
            on A's family, :parent-tree-id = A (the family's OWN birth
            shape), never a mint under B"
    (let [shape-a (random-uuid) shape-b (random-uuid) family-id (random-uuid)
          candidate (tree-class-candidate shape-b 0.95
                      :domain-coverage :partial
                      :domain-label "Recipe Scaling"
                      :domain-reasoning "Shares subject matter but not the output kind.")
          merge-calls (atom [])]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn
                  (fn [_] [{:target-id family-id :domain-label "recipe-scaling" :parent-id shape-a}])
                  :domain-merge-fn (fn [_ q] (swap! merge-calls conj q) {:kind :same :family family-id})
                  :domain-family-parent-fn
                  (fn [_ id] (when (= id family-id) {:parent-id shape-a :domain-label "recipe-scaling"}))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= 1 (count @merge-calls)) "CV-D flip: the tenant-wide label match is now judged")
          (is (= family-id (:proposed (first @merge-calls))))
          (is (= :land-on-domain-child (:assigned-via r)))
          (is (= family-id (:assigned-tree-id r)))
          (is (= shape-a (:parent-tree-id r)) "the family's OWN parent, not shape-b")
          (is (= "recipe-scaling" (:domain-label r)))
          (is (false? (:was-fresh-mint? r))))))))

(deftest families-lookup-failure-defers
  (testing "when the tenant-wide :domain-families-fn throws, the match is
            left as-is with a domain deferral naming the failure — never
            read as \"no families\" (fail closed)"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "Marathon Training Plan"
                      :domain-reasoning "shares the subject matter only")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] (throw (ex-info "store unavailable" {})))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)) "the pre-existing assignment is untouched")
          (is (= class-id (:assigned-tree-id r)) "no child/family identity minted or landed")
          (is (= {:axis :domain :reason :families-lookup-failed} (:domain-deferral r)))
          (is (false? (:was-fresh-mint? r))))))))


(deftest covered-leaf-neighbour-at-threshold-wins-over-a-partial-top-1
  (testing "top-1 would mint (:partial, no tenant family, no legacy
            children) but another :tree-class candidate in the ranking is
            :covered, has no families of its own, and is at/above the match
            threshold -> that candidate wins as a plain :match;
            :domain-selection records the passed-over shape"
    (let [top1-id (random-uuid) neighbour-id (random-uuid)
          top1 (tree-class-candidate top1-id 0.95
                 :domain-coverage :partial
                 :domain-label "Marathon Training Plan"
                 :domain-reasoning "Shares subject matter but not the output kind.")
          neighbour (tree-class-candidate neighbour-id 0.75
                      :domain-coverage :covered
                      :domain-label "Legal Issue Detection"
                      :domain-reasoning "Fully covered.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [top1 neighbour])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)))
          (is (= neighbour-id (:assigned-tree-id r)) "the covered neighbour wins, not top-1")
          (is (false? (:was-fresh-mint? r)))
          (is (= {:preferred neighbour-id :over top1-id :reason :covered-leaf-neighbour}
                 (:domain-selection r))))))))

(deftest covered-top-1-is-never-overridden-by-a-covered-neighbour
  (testing "CoveredSeedWins requires a top match that would mint: a top-1
            judged :covered keeps its own plain match even when another
            covered, childless candidate sits in the ranking; no
            :domain-selection is recorded"
    (let [top1-id (random-uuid) neighbour-id (random-uuid)
          top1 (tree-class-candidate top1-id 0.95
                 :domain-coverage :covered
                 :domain-label "Legal Issue Detection"
                 :domain-reasoning "Fully covered.")
          neighbour (tree-class-candidate neighbour-id 0.8
                      :domain-coverage :covered
                      :domain-label "Contract Comparison"
                      :domain-reasoning "Also covered.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [top1 neighbour])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)))
          (is (= top1-id (:assigned-tree-id r)))
          (is (nil? (:domain-selection r))))))))

(deftest covered-neighbour-with-families-does-not-protect
  (testing "D7b: a :covered candidate in the ranking that already HAS
            domain families of its own is not evidence and never protects
            — top-1 mints as usual"
    (let [top1-id (random-uuid) neighbour-id (random-uuid)
          top1 (tree-class-candidate top1-id 0.95
                 :domain-coverage :partial
                 :domain-label "Marathon Training Plan"
                 :domain-reasoning "Shares subject matter but not the output kind.")
          neighbour (-> (tree-class-candidate neighbour-id 0.75
                          :domain-coverage :covered
                          :domain-label "Legal Issue Detection"
                          :domain-reasoning "Fully covered.")
                        (assoc :existing-domain-children ["already-has-a-family"]))
          expected-id (expected-domain-child-id top1-id "marathon-training-plan")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [top1 neighbour])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :mint-domain-child (:assigned-via r)) "protection did not fire")
          (is (= expected-id (:assigned-tree-id r)))
          (is (not (contains? r :domain-selection))))))))

(deftest no-neighbour-is-byte-identical
  (testing "no covered neighbour in the ranking at all -> byte-identical
            mint on top-1 (plus :merge-verdict :new, CV-A item 7's shipped
            default), no :domain-selection"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "Marathon Training Plan"
                      :domain-reasoning "Shares subject matter but not the output kind.")
          expected-id (expected-domain-child-id class-id "marathon-training-plan")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :mint-domain-child (:assigned-via r)))
          (is (= expected-id (:assigned-tree-id r)))
          (is (= class-id (:parent-tree-id r)))
          (is (= "marathon-training-plan" (:domain-label r)))
          (is (true? (:was-fresh-mint? r)))
          (is (not (contains? r :domain-selection)))
          (is (= {:kind :new} (:merge-verdict r))))))))

(deftest protection-runs-before-family-landing
  (testing "grill C4's stated order (protection -> exact-label landing): a
            MIS-PARENTED family already exists tenant-wide for top-1's
            canonical label under the WRONG shape, AND a covered, childless
            neighbour also sits in the ranking at/above threshold — the
            covered neighbour wins; the mis-parented family never captures
            this task"
    (let [top1-id (random-uuid) neighbour-id (random-uuid)
          wrong-parent-id (random-uuid) mis-parented-family-id (random-uuid)
          top1 (tree-class-candidate top1-id 0.95
                 :domain-coverage :partial
                 :domain-label "Recipe Scaling"
                 :domain-reasoning "Shares subject matter but not the output kind.")
          neighbour (tree-class-candidate neighbour-id 0.75
                      :domain-coverage :covered
                      :domain-label "Legal Issue Detection"
                      :domain-reasoning "Fully covered.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [top1 neighbour])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn
                  (fn [_] [{:target-id mis-parented-family-id
                           :domain-label "recipe-scaling"
                           :parent-id wrong-parent-id}])}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)))
          (is (= neighbour-id (:assigned-tree-id r))
              "the covered seed wins — the mis-parented family is never landed on")
          (is (not= mis-parented-family-id (:assigned-tree-id r)))
          (is (= {:preferred neighbour-id :over top1-id :reason :covered-leaf-neighbour}
                 (:domain-selection r))))))))

(deftest children-lookup-failure-defers-instead-of-minting
  (testing "when the domain-children capability throws, the match is left as-is with a domain deferral naming the failure, and no child identity is minted"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "Marathon Training Plan"
                      :domain-reasoning "shares the subject matter only")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    queries/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] (throw (ex-info "store unavailable" {})))}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)) "the pre-existing assignment is untouched")
          (is (= class-id (:assigned-tree-id r)) "no child identity is minted from a failed lookup")
          (is (= {:axis :domain :reason :children-lookup-failed} (:domain-deferral r)))
          (is (false? (:was-fresh-mint? r)))
          (is (= :partial (get-in r [:domain-verdict :domain-coverage]))
              "the verdict is still carried for RS-3 to record"))))))
