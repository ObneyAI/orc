(ns ai.obney.orc.ontology.rs7-domain-family-merge-test
  "CV-C — the judged merge step for domain families
   (`specs/ontology.allium`'s `DomainFamilyMergeIsJudged`): when a
   classification would mint a domain family, the nearest existing
   families are retrieved by rank and one discrete question decides same
   (named) / new / unknown. Same lands on that family, new mints, unknown
   defers on the domain axis (`:merge-unresolved`) and mints nothing. No
   call on a landing or a covered match; an empty neighbourhood is 'new'
   without a call.

   Seam 1 (Cycles 1-7) — `classify-task` with a STUBBED `:domain-merge-fn`
   capturing its calls, mirroring rs2's style: the routing/plumbing is
   exercised without a real LLM call.

   Seam 2 (Cycles 8-10) — `default-domain-merge-fn` and `nearest-families`
   directly: the REAL seam's own control flow (empty-neighbourhood
   short-circuit, the shown-candidates membership check, fail-closed on a
   neighbourhood-read exception).

   Seam 3 (reranker) — the instruction byte-pin and `parse-merge-answer`.

   Seam 4 (rs3-style durable) — a merge landing records no concept and
   carries the verdict on the classified event."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.test-helpers :as th]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Fixtures — shaped exactly as search-descriptions returns them (rs2/el1b/
;; cc23 prior art).
;; =============================================================================

(defn- tree-class-candidate
  [id fitness & {:keys [domain-coverage domain-label domain-reasoning]}]
  {:content "x" :score fitness :rank 1
   :document-id (str id)
   :document-metadata {:granularity :tree-class :target-id (str id) :confidence 1.0}
   :reasoning "principle-shaped fit"
   :fitness-score fitness
   :rerank-source :reranker
   :domain-coverage domain-coverage
   :domain-label domain-label
   :domain-reasoning domain-reasoning})

;; =============================================================================
;; Seam 1 — classify-task with a stubbed :domain-merge-fn
;; =============================================================================

(deftest same-verdict-lands-on-the-named-family-and-carries-merge-verdict
  (testing "no legacy children, :partial coverage -> the merge judge is
            asked (captured), :same names a family that DOES resolve via
            :domain-family-parent-fn -> :assigned-via :land-on-domain-child,
            identity = the named family, :merge-verdict on the result"
    (let [class-id (random-uuid) family-id (random-uuid) family-parent (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "Weekly Meal Plan"
                      :domain-reasoning "Shares subject matter but not the output kind.")
          calls (atom [])]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-merge-fn (fn [ctx query]
                                     (swap! calls conj query)
                                     {:kind :same :family family-id})
                  :domain-family-parent-fn (fn [_ id]
                                             (when (= id family-id)
                                               {:parent-id family-parent :domain-label "meal-planning"}))}
                 {:task-signature "x" :threshold 0.7})]
          (is (= 1 (count @calls)) "the merge judge was asked exactly once")
          (is (contains? (first @calls) :ranking)
              "the query carries :ranking (CV-C item 3's seed for nearest-families)")
          (is (= :land-on-domain-child (:assigned-via r)))
          (is (= family-id (:assigned-tree-id r)))
          (is (= family-parent (:parent-tree-id r)))
          (is (= "meal-planning" (:domain-label r)))
          (is (false? (:was-fresh-mint? r)))
          (is (= {:kind :same :family family-id} (:merge-verdict r))))))))

(deftest new-verdict-mints-a-family-and-carries-merge-verdict
  (testing "no legacy children, :uncovered coverage -> the merge judge is
            asked, :new -> mints the derived identity, :merge-verdict {:kind :new} carried"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :uncovered
                      :domain-label "Chess Opening Study Plan"
                      :domain-reasoning "No existing coverage for this shape.")
          calls (atom 0)]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-family-parent-fn (fn [_ _] nil)
                  :domain-merge-fn (fn [_ _] (swap! calls inc) {:kind :new})}
                 {:task-signature "x" :threshold 0.7})]
          (is (= 1 @calls))
          (is (= :mint-domain-child (:assigned-via r)))
          (is (true? (:was-fresh-mint? r)))
          (is (= {:kind :new} (:merge-verdict r))))))))

(deftest unknown-verdict-defers-and-mints-nothing
  (testing ":unknown -> :domain-deferral :merge-unresolved, no mint, no identity change"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "Some New Shape"
                      :domain-reasoning "Cannot tell.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-family-parent-fn (fn [_ _] nil)
                  :domain-merge-fn (fn [_ _] {:kind :unknown})}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :match (:assigned-via r)) "no re-provenance — the shape assignment stands")
          (is (= class-id (:assigned-tree-id r)) "no identity change — still the parent shape")
          (is (false? (:was-fresh-mint? r)))
          (is (= {:axis :domain :reason :merge-unresolved} (:domain-deferral r)))
          (is (= {:kind :unknown} (:merge-verdict r))))))))

(deftest same-verdict-naming-an-unshown-family-defers
  (testing "a :same naming something with no resolvable family parent (the
            stubbed judge answered outside anything it could have been
            shown) defers exactly like :unknown — nothing lands with no
            parent"
    (let [class-id (random-uuid) rogue-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "Some New Shape"
                      :domain-reasoning "irrelevant")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-merge-fn (fn [_ _] {:kind :same :family rogue-id})
                  :domain-family-parent-fn (fn [_ _] nil)}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :merge-unresolved (get-in r [:domain-deferral :reason])))
          (is (not= :land-on-domain-child (:assigned-via r)))
          (is (not= rogue-id (:assigned-tree-id r))))))))

(deftest zero-merge-calls-on-a-landing-by-label
  (testing "top-1 would mint, but a tenant-wide family ALREADY carries the
            judged label's canonical form -> lands by label (C1's
            judge-free path) -> the merge judge is never asked"
    (let [class-id (random-uuid) family-id (random-uuid) family-parent (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "Recipe Scaling"
                      :domain-reasoning "Shares subject matter but not the output kind.")
          calls (atom 0)]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [{:target-id family-id
                                                :domain-label "recipe-scaling"
                                                :parent-id family-parent}])
                  :domain-family-parent-fn (fn [_ _] nil)
                  :domain-merge-fn (fn [_ _] (swap! calls inc) {:kind :new})}
                 {:task-signature "x" :threshold 0.7})]
          (is (= 0 @calls) "landing by label never reaches the merge judge")
          (is (= :land-on-domain-child (:assigned-via r)))
          (is (= family-id (:assigned-tree-id r))))))))

(deftest zero-merge-calls-on-a-covered-match
  (testing ":covered, no children -> byte-identical plain match, never asks the merge judge"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :covered
                      :domain-label "Marathon Training Plan"
                      :domain-reasoning "Fully covered by the existing class.")
          calls (atom 0)]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-family-parent-fn (fn [_ _] nil)
                  :domain-merge-fn (fn [_ _] (swap! calls inc) {:kind :new})}
                 {:task-signature "x" :threshold 0.7})]
          (is (= 0 @calls))
          (is (= :match (:assigned-via r)))
          (is (= class-id (:assigned-tree-id r))))))))

(deftest zero-merge-calls-on-a-covered-seed-protection-outcome
  (testing "CoveredSeedProtection wins over a would-mint top-1 -> the merge
            judge is never reached (protection runs first)"
    (let [top1-id (random-uuid) neighbour-id (random-uuid)
          top1 (tree-class-candidate top1-id 0.95
                 :domain-coverage :partial
                 :domain-label "Marathon Training Plan"
                 :domain-reasoning "Shares subject matter but not the output kind.")
          neighbour (tree-class-candidate neighbour-id 0.75
                      :domain-coverage :covered
                      :domain-label "Legal Issue Detection"
                      :domain-reasoning "Fully covered.")
          calls (atom 0)]
      (with-redefs [ontology/search-descriptions (fn [_ _] [top1 neighbour])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-family-parent-fn (fn [_ _] nil)
                  :domain-merge-fn (fn [_ _] (swap! calls inc) {:kind :new})}
                 {:task-signature "x" :threshold 0.7})]
          (is (= 0 @calls))
          (is (= :match (:assigned-via r)))
          (is (= neighbour-id (:assigned-tree-id r))))))))

;; =============================================================================
;; Seam 2 — the REAL seam: default-domain-merge-fn + nearest-families
;; =============================================================================

(deftest empty-neighbourhood-mints-with-zero-merge-family-calls
  (testing "nearest-families returns [] -> default-domain-merge-fn is :new
            WITHOUT calling reranker/merge-family! at all (grill C3: 'an
            empty neighbourhood is new without a call')"
    (let [merge-calls (atom 0)]
      (with-redefs [tc/nearest-families (fn [_ _] [])
                    reranker/merge-family! (fn [_ _] (swap! merge-calls inc) {:kind :same})]
        (let [r (tc/default-domain-merge-fn {} {:signature "x" :reasoning "y" :ranking []})]
          (is (= {:kind :new} r))
          (is (= 0 @merge-calls)))))))

(deftest same-verdict-naming-a-shown-candidate-lands
  (testing "merge-family! answers :same with an id that IS among the
            candidates nearest-families actually returned -> the verdict
            carries OUR OWN copy of that candidate's :id (not the judge's
            raw echoed value)"
    (let [family-a (random-uuid) family-b (random-uuid)
          candidates [{:id family-a :label "recipe-scaling" :description "d-a"}
                      {:id family-b :label "marathon-training-plan" :description "d-b"}]]
      (with-redefs [tc/nearest-families (fn [_ _] candidates)
                    reranker/merge-family! (fn [_ _] {:kind :same :family (str family-a)
                                                      :reasoning "matches family-a"})]
        (let [r (tc/default-domain-merge-fn {} {:signature "x" :reasoning "y" :ranking []})]
          (is (= :same (:kind r)))
          (is (= family-a (:family r)) "the typed id from OUR candidate list, not the judge's string")
          (is (= "matches family-a" (:reasoning r))))))))

(deftest same-verdict-naming-an-unshown-candidate-is-unknown
  (testing "merge-family! answers :same with an id that is NOT among the
            shown candidates -> read as :unknown (a hallucinated /
            out-of-context id never lands)"
    (let [family-a (random-uuid) other-id (random-uuid)
          candidates [{:id family-a :label "recipe-scaling" :description "d-a"}]]
      (with-redefs [tc/nearest-families (fn [_ _] candidates)
                    reranker/merge-family! (fn [_ _] {:kind :same :family (str other-id)})]
        (let [r (tc/default-domain-merge-fn {} {:signature "x" :reasoning "y" :ranking []})]
          (is (= :unknown (:kind r)))
          (is (not (contains? r :family))))))))

(deftest malformed-verdict-kind-from-merge-family-is-unknown
  (testing "an out-of-set / missing verdict kind from merge-family! is read
            as :unknown, never coerced"
    (let [candidates [{:id (random-uuid) :label "a" :description "d"}]]
      (with-redefs [tc/nearest-families (fn [_ _] candidates)
                    reranker/merge-family! (fn [_ _] {:kind :maybe})]
        (is (= :unknown (:kind (tc/default-domain-merge-fn {} {:signature "x" :reasoning "y" :ranking []}))))))))

(deftest neighbourhood-read-failure-propagates-fail-closed
  (testing "nearest-families throwing propagates OUT of default-domain-merge-fn
            (fail closed) — mint-domain-family-via-merge's own try/catch is
            what turns it into :unknown/:merge-unresolved, exercised here at
            the classify-task level with the REAL default seam"
    (let [class-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "Some New Shape"
                      :domain-reasoning "irrelevant")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)
                    tc/nearest-families (fn [_ _] (throw (ex-info "hybrid-search unavailable" {})))]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-family-parent-fn (fn [_ _] nil)}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :merge-unresolved (get-in r [:domain-deferral :reason])))
          (is (false? (:was-fresh-mint? r)))
          (is (= class-id (:assigned-tree-id r)) "no identity change on a failed neighbourhood read"))))))

;; =============================================================================
;; Seam 3 — reranker: the instruction byte-pin + parse-merge-answer
;; =============================================================================

(deftest family-merge-instruction-states-both-converses
  (testing "RS-P3's verdict fix #1: the instruction states BOTH converses
            explicitly (the ONE cross-group false merge in the corrected
            probe run happened because only the forward direction was
            stated)"
    (let [instr (str @#'reranker/family-merge-instruction)]
      (is (str/includes? instr "SUBJECT MATTER"))
      (is (str/includes? instr "OUTPUT KIND"))
      (is (str/includes? instr "a DIFFERENT subject matter is a NEW family even when the "))
      (is (str/includes? instr "a DIFFERENT output kind is a NEW family "))
      (is (str/includes? instr "merge_reasoning FIRST")
          "reason before verdict")
      (is (str/includes? instr "\"merge_reasoning\"")
          "output keys are EXACTLY merge_reasoning/verdict/family")
      (is (str/includes? instr "\"verdict\""))
      (is (str/includes? instr "\"family\"")))))

(deftest parse-merge-answer-accepts-a-map
  (testing "function calling returns a MAP — the RS-P3 run-1 defect this must not repeat"
    (is (= {:merge_reasoning "shares both axes" :verdict "same" :family "abc"}
           (#'reranker/parse-merge-answer
            {:merge_reasoning "shares both axes" :verdict "same" :family "abc"})))))

(deftest parse-merge-answer-accepts-a-json-string
  (testing "a JSON string, optionally with surrounding prose/fences, still parses"
    (is (= {:merge_reasoning "r" :verdict "new" :family nil}
           (#'reranker/parse-merge-answer
            "```json\n{\"merge_reasoning\": \"r\", \"verdict\": \"new\", \"family\": null}\n```")))))

(deftest parse-merge-answer-malformed-input-returns-nil
  (testing "unparseable / absent input returns nil -> caller reads as :unknown"
    (is (nil? (#'reranker/parse-merge-answer "not json at all")))
    (is (nil? (#'reranker/parse-merge-answer nil)))
    (is (nil? (#'reranker/parse-merge-answer 42)))))

;; =============================================================================
;; Seam 4 — rs3-style durable: a merge landing records no concept and
;; carries the verdict on the classified event
;; =============================================================================

(defn- mint-command [parent-id child-id label]
  {:command/name :ontology/mint-domain-child
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :parent-tree-id parent-id
   :child-tree-id child-id
   :domain-label label
   :source-sheet-id (random-uuid)
   :source-tick-id (random-uuid)
   :source-node-id (random-uuid)})

(defn- dispatch! [ctx command]
  (cp/process-command (assoc ctx :command command)))

(defn- concept-created-count [ctx]
  (count (into [] (es/read (:event-store ctx)
                           {:tenant-id (:tenant-id ctx)
                            :types #{:ontology/concept-created}}))))

(defn- assign-command [result]
  (merge {:command/name :ontology/assign-task-class
          :command/id (random-uuid)
          :command/timestamp (time/now)
          :source-sheet-id (random-uuid)
          :source-tick-id (random-uuid)
          :source-node-id (random-uuid)}
         (select-keys result [:assigned-tree-id :confidence :top-candidates :reasoning
                              :was-fresh-mint? :parent-tree-id :ranked-candidates
                              :assigned-via :outcome :domain-verdict :domain-label
                              :domain-children-considered :domain-deferral
                              :domain-selection :merge-verdict])))

(deftest merge-landing-records-no-concept-and-carries-the-verdict
  (testing "a REAL minted family exists tenant-wide; a second, DIFFERENTLY-
            labelled task on an UNRELATED shape is judged (stubbed judge)
            :same as that family -> the classification lands on it, mints
            NO new concept, and the :ontology/task-classified event carries
            :merge-verdict"
    (th/with-test-context [base]
      (let [ctx (assoc base :command-registry (cp/global-command-registry))
            family-parent-id (random-uuid)
            family-id (random-uuid)
            other-shape-id (random-uuid)
            candidate (tree-class-candidate other-shape-id 0.95
                        :domain-coverage :partial
                        :domain-label "Batch Recipe Adjustment"
                        :domain-reasoning "Shares subject matter but not the exact wording.")]
        (dispatch! ctx (mint-command family-parent-id family-id "recipe-scaling"))
        (let [baseline (concept-created-count ctx)
              result (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                                   tc/get-consolidation-total* (fn [_ _ _] 0)]
                       (ontology/classify-task
                        (assoc ctx :domain-merge-fn (fn [_ _] {:kind :same :family family-id}))
                        {:task-signature "batch recipe scaling for meal prep"
                         :threshold 0.7}))
              cmd-result (dispatch! ctx (assign-command result))
              events (:command-result/events cmd-result)
              classified-ev (some #(when (= :ontology/task-classified (:event/type %)) %) events)]
          (is (= :land-on-domain-child (:assigned-via result)) "sanity: a merge landing")
          (is (= family-id (:assigned-tree-id result)))
          (is (= {:kind :same :family family-id} (:merge-verdict result)))
          (is (= baseline (concept-created-count ctx))
              "a merge landing records NO new concept — no mint")
          (is (some? classified-ev) "the assign command emitted :ontology/task-classified")
          (is (= {:kind :same :family family-id} (:merge-verdict classified-ev)))
          (is (= family-id (:assigned-tree-id classified-ev))))))))

;; =============================================================================
;; Orchestrator inspection — the REAL neighbourhood on a REAL store. Every other
;; test here stubs nearest-families; this one proves the families born through
;; the mint command (birth description + embedding at birth) are what the
;; hybrid search actually finds, ranked by meaning, with ordinary shape classes
;; left out.
;; =============================================================================

(deftest nearest-families-finds-real-born-families-by-meaning
  (th/with-test-context [base]
    (let [ctx (assoc base :command-registry (cp/global-command-registry))
          recipe-id (random-uuid) marathon-id (random-uuid) shape-id (random-uuid)
          birth (fn [child label description]
                  (dispatch! ctx (assoc (mint-command shape-id child label)
                                        :birth-description description)))]
      (birth recipe-id "recipe-scaling"
             (str "Domain family \"recipe-scaling\". Purpose: scale a tested baking recipe to a new batch "
                  "size and flag ingredients that do not scale linearly. Birth task: scale my cookie recipe "
                  "from 24 to 96 cookies."))
      (birth marathon-id "marathon-training-plan"
             (str "Domain family \"marathon-training-plan\". Purpose: build a weekly running schedule toward "
                  "a race date from the runner's current mileage. Birth task: a 16-week marathon plan."))
      (let [found (tc/nearest-families ctx {:signature "double my sourdough bread recipe for a bakery order"
                                            :reasoning "scaling a baking recipe to a larger batch"
                                            :ranking []})
            ids (mapv :id found)]
        (is (= #{recipe-id marathon-id} (set ids))
            (str "both born families are found, nothing else: " (pr-str found)))
        (is (= recipe-id (first ids)) "the recipe family ranks first for a recipe-scaling task")
        (is (not (contains? (set ids) shape-id)) "the ordinary shape class is never shown to the judge")
        (is (every? #(seq (:description %)) found) "each family carries its own birth description")))))
