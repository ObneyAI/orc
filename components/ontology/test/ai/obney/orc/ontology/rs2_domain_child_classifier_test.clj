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
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.test-helpers :as th]))

;; =============================================================================
;; Cycle 1 — apply-rerank's JOIN carries the three domain keys
;; =============================================================================

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
      (with-redefs [reranker/rerank!
                    (fn [_ctx _opts]
                      [{:document-id "a"
                        :reasoning "Fits the structured-output need."
                        :fitness-score 0.87
                        :domain-coverage :partial
                        :domain-label "marathon-training-plan"
                        :domain-reasoning "Shares subject matter but not the output kind."}])]
        (let [[joined] (#'ontology/apply-rerank ctx sample-candidates "intent" "query" 5 nil)]
          (is (= :partial (:domain-coverage joined)))
          (is (= "marathon-training-plan" (:domain-label joined)))
          (is (= "Shares subject matter but not the output kind."
                 (:domain-reasoning joined)))
          (is (= 0.87 (:fitness-score joined)) "existing fields still carried")
          (is (= :reranker (:rerank-source joined)) "existing fields still carried"))))))

;; =============================================================================
;; classify-task fixtures — shaped exactly as search-descriptions returns them
;; (per el3/el1b/cc23), optionally carrying RS-1's domain verdict. Omitting
;; the domain kwargs entirely simulates a pre-RS-1/missing-verdict candidate
;; (no domain keys at all).
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

(defn- expected-domain-child-id
  "Mirrors the production `stable-domain-child-identity` derivation
   (`stable_domain_child_identity`): UUID/nameUUIDFromBytes over
   \"domain-child:\" + parent-id + \":\" + the CANONICAL label."
  [parent-id canonical-label]
  (java.util.UUID/nameUUIDFromBytes
    (.getBytes (str "domain-child:" parent-id ":" canonical-label) "UTF-8")))

;; =============================================================================
;; Cycle 3 — no children, :partial verdict → :mint-domain-child, stable identity
;; =============================================================================

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
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
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

;; =============================================================================
;; Cycle 4 — no children: :uncovered mints too; :covered is byte-identical to
;; today's :match result plus :domain-verdict
;; =============================================================================

(deftest no-children-uncovered-verdict-mints-domain-child
  (testing "an :uncovered verdict behaves exactly like :partial (no children): mints"
    (let [class-id (random-uuid)
          candidates [(tree-class-candidate class-id 0.95
                        :domain-coverage :uncovered
                        :domain-label "Recipe Scaling"
                        :domain-reasoning "No existing coverage for this shape.")]
          expected-id (expected-domain-child-id class-id "recipe-scaling")]
      (with-redefs [ontology/search-descriptions (fn [_ _] candidates)
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
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
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] [])}
                                        {:task-signature "x" :threshold 0.7})]
          ;; The core assignment is UNCHANGED from a plain :match — no child,
          ;; no re-provenance, same target, same everything else classify-task
          ;; already computed before RS-2 (:top-candidates/:ranked-candidates/
          ;; :confidence/:reasoning naturally still carry RS-1's domain keys on
          ;; the candidate itself — that is RS-1/cycle-1-2's join, not RS-2's
          ;; concern; RS-2 adds NOTHING beyond :domain-verdict/
          ;; :domain-children-considered here).
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

;; =============================================================================
;; Cycle 5 — :unknown (explicit) and a missing verdict both defer; no child,
;; no identity change
;; =============================================================================

(deftest no-children-unknown-verdict-defers
  (testing "an explicit :unknown verdict (no children) → the plain :match result
            plus :domain-deferral {:axis :domain}; no child, no identity change"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :unknown
                      :domain-label nil
                      :domain-reasoning nil)]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
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
          candidate (tree-class-candidate class-id 0.95)] ;; no domain kwargs at all
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
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

;; =============================================================================
;; Cycle 6 — children present, judged label equals an existing child's →
;; :land-on-domain-child, verdict NOT consulted (even :covered)
;; =============================================================================

(deftest children-present-matching-label-lands-on-existing-child
  (testing "a domain child whose :domain-label equals the (normalised) judged
            label → assign THAT child's identity, :assigned-via
            :land-on-domain-child, verdict IGNORED even when :covered (D7b:
            once a class has children the reranker calls it covered
            regardless, so the verdict is consulted ONLY when the parent has
            no children)"
    (let [class-id (random-uuid)
          sibling-id (random-uuid)
          existing-child-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :covered
                      :domain-label "Marathon Training Plan"
                      :domain-reasoning "Covered — a domain child already exists for this.")
          children [{:target-id existing-child-id :domain-label "marathon-training-plan"}
                    {:target-id sibling-id :domain-label "recipe-scaling"}]]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] children)}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :land-on-domain-child (:assigned-via r)))
          (is (= existing-child-id (:assigned-tree-id r))
              "assigned the MATCHING child's identity, not a derived one")
          (is (= class-id (:parent-tree-id r)))
          (is (false? (:was-fresh-mint? r)))
          (is (not (contains? r :domain-label))
              "LandOnDomainChild does not stamp a top-level :domain-label — the spec's
               ensures clause carries no label, unlike the two minting rules")
          (is (= #{"marathon-training-plan" "recipe-scaling"}
                 (set (:domain-children-considered r))))
          (is (= {:domain-coverage :covered
                  :domain-label "Marathon Training Plan"
                  :domain-reasoning "Covered — a domain child already exists for this."}
                 (:domain-verdict r))
              "the verdict is still CARRIED for RS-3, even though it was not CONSULTED"))))))

;; =============================================================================
;; Cycle 7 — children present, judged label is NEW → :mint-sibling-domain-child,
;; verdict NOT consulted
;; =============================================================================

(deftest children-present-new-label-mints-sibling-domain-child
  (testing "a judged label matching NO existing domain child → mint a sibling:
            the derived sibling identity, :assigned-via
            :mint-sibling-domain-child, the verdict NOT consulted"
    (let [class-id (random-uuid)
          sibling-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :unknown ;; deliberately irrelevant — must be ignored
                      :domain-label "Weekly Meal Plan"
                      :domain-reasoning "A new domain shape not seen before.")
          children [{:target-id sibling-id :domain-label "recipe-scaling"}]
          expected-id (expected-domain-child-id class-id "weekly-meal-plan")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] children)}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :mint-sibling-domain-child (:assigned-via r)))
          (is (= expected-id (:assigned-tree-id r)) "derived from parent + the CANONICAL new label")
          (is (= class-id (:parent-tree-id r)))
          (is (= "weekly-meal-plan" (:domain-label r)))
          (is (true? (:was-fresh-mint? r)))
          (is (= ["recipe-scaling"] (:domain-children-considered r))))))))

;; =============================================================================
;; Cycle 8 — @invariant DomainChildrenAreAlwaysConsidered: a matched leaf with
;; domain children is considered even when top-1 fitness >= the specificity
;; threshold (0.95) — walk-down's OWN specificity gate skips the WALK, but
;; domain-child consideration runs regardless
;; =============================================================================

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
                    tc/get-consolidation-total* (fn [_ _ _] 0)
                    ontology/get-narrower-concepts
                    (fn [_ _] (reset! walk-down-lookup-called? true) #{})]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] children)}
                                        {:task-signature "x" :threshold 0.7
                                         :walk-down? true})]
          (is (>= 0.95 0.95) "sanity: at the specificity threshold")
          (is (false? @walk-down-lookup-called?)
              "walk-down's OWN children lookup is never reached — the specificity
               gate returns top-1 without descending")
          (is (= :mint-sibling-domain-child (:assigned-via r))
              "domain children were STILL considered — the class is NOT treated as a leaf")
          (is (= expected-id (:assigned-tree-id r)))
          (is (true? (:was-fresh-mint? r))))))))

;; =============================================================================
;; Cycle 9 — Guard: :bundle, walk-down's own :mint, :uncertain, and a
;; :tree-fingerprint-axis :match are byte-identical to today (no domain keys
;; added at all); a blank label defers instead of minting.
;; =============================================================================

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
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
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
          candidate (tree-class-candidate parent-id 0.75 ;; between threshold (0.5) and specificity (0.9) -> walk
                      :domain-coverage :partial
                      :domain-label "Some Label"
                      :domain-reasoning "irrelevant — a walk-down :mint is not a :match")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)
                    ontology/get-narrower-concepts
                    (fn [_ uri]
                      (cond
                        (= uri (str "tree-class:" parent-id)) #{(str "tree-class:" child1-id)}
                        (= uri (str "tree-class:" child1-id)) #{(str "tree-class:" child2-id)}
                        :else #{}))
                    ontology/get-description (fn [_ _ _] {:summary "a child pattern"})
                    reranker/rerank!
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
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
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
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
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
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
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
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] children)}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)) "no re-provenance — no mint happened")
          (is (= class-id (:assigned-tree-id r)) "no identity change")
          (is (false? (:was-fresh-mint? r)))
          (is (= :domain (:axis (:domain-deferral r))))
          (is (= ["recipe-scaling"] (:domain-children-considered r))))))))

;; =============================================================================
;; Orchestrator inspection — a FAILED children lookup must DEFER, never read
;; as "no children": reading a store failure as an empty child list would mint
;; a fresh sibling for a domain that already has a child (the scatter this arc
;; exists to prevent). DomainCoverageIsJudgedNotInferred: what cannot be
;; resolved defers on the domain axis and records why.
;; =============================================================================

(deftest children-lookup-failure-defers-instead-of-minting
  (testing "when the domain-children capability throws, the match is left as-is with a domain deferral naming the failure, and no child identity is minted"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "Marathon Training Plan"
                      :domain-reasoning "shares the subject matter only")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task {:domain-children-fn (fn [_ _] (throw (ex-info "store unavailable" {})))}
                                        {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)) "the pre-existing assignment is untouched")
          (is (= class-id (:assigned-tree-id r)) "no child identity is minted from a failed lookup")
          (is (= {:axis :domain :reason :children-lookup-failed} (:domain-deferral r)))
          (is (false? (:was-fresh-mint? r)))
          (is (= :partial (get-in r [:domain-verdict :domain-coverage]))
              "the verdict is still carried for RS-3 to record"))))))
