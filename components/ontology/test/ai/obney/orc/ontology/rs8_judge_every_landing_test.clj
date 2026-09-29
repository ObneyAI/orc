(ns ai.obney.orc.ontology.rs8-judge-every-landing-test
  "CV-D (decision C3', revising C1's landing and C3's 'never on a landing') —
   `specs/ontology.allium`'s `DomainFamilyMergeIsJudged` revised: no task
   enters an existing domain family without the merge judge. The three
   paths that used to land UNJUDGED (a tenant-wide canonical-label match, a
   shape's own existing per-parent child label, and a match/walk-down that
   REACHES a family) now only PROPOSE a family; `nearest-families` always
   shows the judge the proposed family (fetching it directly when the
   hybrid search did not surface it, never dropping it for the bound); a
   reached family is judged as a match on its OWN parent shape
   (`JudgeReachedDomainFamily`) — never mints under the family itself. A
   `:new` verdict whose derived identity collides with an EXISTING family's
   identity defers `:label-taken` instead of silently re-minting it.

   Companion to rs2 (pure classifier), rs7-newborn-reach (the three reach
   routes) and rs7-domain-family-merge (the merge seam itself) — those
   files' existing 'a landing is unjudged' tests are updated in place to
   declare the judge's verdict explicitly (see their own diffs); this file
   holds CV-D's OWN new obligations: the proposed-family guarantee on
   `nearest-families`, the identity-collision guard, and a reached family's
   :new verdict minting under its PARENT, never under the family."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.test-helpers :as th]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Fixtures — shaped exactly as search-descriptions returns them (rs2/rs7
;; prior art).
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

(defn- expected-domain-child-id
  "Mirrors the production `stable-domain-child-identity` derivation."
  [parent-id canonical-label]
  (java.util.UUID/nameUUIDFromBytes
    (.getBytes (str "domain-child:" parent-id ":" canonical-label) "UTF-8")))

(defn- family-concept [label]
  {:label label :provenance {:kind :agent-authored}})

;; =============================================================================
;; nearest-families — the proposed family is ALWAYS among the candidates,
;; fetched directly when the hybrid search did not surface it, and it is
;; NEVER the one dropped for the merge-candidate-count bound.
;; =============================================================================

(deftest nearest-families-always-includes-the-proposed-family-even-when-omitted
  (testing "hybrid-search surfaces 6 OTHER families (more than the default
            bound of 5) and never the proposed one -> nearest-families still
            returns the proposed family, at the bound (5), with one of the
            'other' candidates dropped to make room"
    (let [proposed-id (random-uuid)
          other-ids (repeatedly 6 random-uuid)
          other-uri->label (into {} (map (fn [id] [(str "tree-class:" id) (str "other-" id)]) other-ids))]
      (with-redefs [ontology/hybrid-search
                    (fn [_ _]
                      {:results (mapv (fn [id] {:uri (str "tree-class:" id)}) other-ids)})
                    ontology/get-concept-by-uri
                    (fn [_ uri]
                      (if-let [label (get other-uri->label uri)]
                        (family-concept label)
                        (when (= uri (str "tree-class:" proposed-id))
                          (family-concept "proposed-family"))))]
        (let [found (tc/nearest-families {} {:signature "x" :reasoning "y" :ranking []
                                              :proposed proposed-id})]
          (is (= 5 (count found)) "still bounded by merge-candidate-count")
          (is (some #(= proposed-id (:id %)) found)
              "the proposed family is present even though hybrid-search never returned it")
          (is (not (every? (set other-ids) (map :id found)))
              "one of the 'other' candidates was dropped to make room — the proposed family is never the one dropped"))))))

(deftest nearest-families-does-not-duplicate-an-already-shown-proposed-family
  (testing "when the hybrid search DID surface the proposed family, it is not
            fetched or inserted a second time"
    (let [proposed-id (random-uuid)
          fetch-calls (atom 0)]
      (with-redefs [ontology/hybrid-search
                    (fn [_ _] {:results [{:uri (str "tree-class:" proposed-id)}]})
                    ontology/get-concept-by-uri
                    (fn [_ uri]
                      (swap! fetch-calls inc)
                      (family-concept "proposed-family"))]
        (let [found (tc/nearest-families {} {:signature "x" :reasoning "y" :ranking []
                                              :proposed proposed-id})]
          (is (= 1 (count found)))
          (is (= proposed-id (:id (first found))))
          (is (= 1 @fetch-calls) "fetched once, for the single hybrid-search hit — no second lookup"))))))

;; =============================================================================
;; Label proposal, :new verdict — a genuinely different identity mints; a
;; COLLIDING identity (the judge said :new despite the shown family
;; deriving the SAME identity this mint would) defers :label-taken.
;; =============================================================================

(deftest label-proposal-new-verdict-mints-under-the-shape-with-a-different-identity
  (testing "a tenant-wide family already carries the judged label under a
            DIFFERENT parent (proposed, shown to the judge); the judge says
            :new -> mints under THIS shape, a distinct identity from the
            proposed family's own"
    (let [class-id (random-uuid) other-shape (random-uuid) family-f-id (random-uuid)
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "Recipe Scaling"
                      :domain-reasoning "Shares subject matter but not the output kind.")
          expected-id (expected-domain-child-id class-id "recipe-scaling")
          merge-calls (atom [])]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [{:target-id family-f-id
                                                :domain-label "recipe-scaling"
                                                :parent-id other-shape}])
                  ;; nothing lives at the derived identity — no collision
                  :domain-family-parent-fn (fn [_ _] nil)
                  :domain-merge-fn (fn [_ q] (swap! merge-calls conj q) {:kind :new})}
                 {:task-signature "x" :threshold 0.7})]
          (is (= 1 (count @merge-calls)) "the label proposal was judged")
          (is (= family-f-id (:proposed (first @merge-calls)))
              "the proposed family rode on the query")
          (is (= :mint-domain-child (:assigned-via r)))
          (is (= expected-id (:assigned-tree-id r)))
          (is (not= family-f-id (:assigned-tree-id r)))
          (is (= class-id (:parent-tree-id r)))
          (is (true? (:was-fresh-mint? r))))))))

(deftest label-proposal-new-verdict-with-colliding-identity-defers-label-taken
  (testing "the SAME shape already has a family at the EXACT identity this
            mint would derive (this shape + this canonical label) — the
            judge nonetheless says :new; the derived identity collision is
            caught and the mint defers :label-taken instead of re-minting"
    (let [class-id (random-uuid)
          colliding-id (expected-domain-child-id class-id "recipe-scaling")
          candidate (tree-class-candidate class-id 0.95
                      :domain-coverage :partial
                      :domain-label "Recipe Scaling"
                      :domain-reasoning "Shares subject matter but not the output kind.")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [{:target-id colliding-id
                                                :domain-label "recipe-scaling"
                                                :parent-id class-id}])
                  ;; a real family already lives at the EXACT derived identity
                  :domain-family-parent-fn (fn [_ id] (when (= id colliding-id)
                                                        {:parent-id class-id
                                                         :domain-label "recipe-scaling"}))
                  :domain-merge-fn (fn [_ _] {:kind :new})}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :label-taken (get-in r [:domain-deferral :reason])))
          (is (= :domain (get-in r [:domain-deferral :axis])))
          (is (not= :mint-domain-child (:assigned-via r)) "no re-mint of an existing identity")
          (is (not= colliding-id (:assigned-tree-id r)))
          (is (= {:kind :new} (:merge-verdict r)) "the raw verdict is still carried for RS-3"))))))

;; =============================================================================
;; CV-E (`DomainFamilyIsALeafOnTheDomainAxis`, revised C5') removed the
;; reach-and-rewrite mechanism entirely: `search-descriptions` excludes every
;; family from the ranking before the reranker's candidate set is taken, so a
;; family's own id can no longer reach top-1 in production at all. The two
;; tests that pinned `JudgeReachedDomainFamily`'s special casing (mint under
;; the family's OWN PARENT shape, never under the family itself; an unknown
;; verdict defers with the pre-existing rewritten-to-parent match standing)
;; pinned an input production can no longer construct AND a mechanism that no
;; longer exists — there is no more rewrite. Replaced below with a single
;; test proving the residual (honest, current) behavior: a stubbed
;; :domain-family-parent-fn can still make the pure classifier BELIEVE top-1's
;; id is a family, but the classifier now mints or defers under THAT id
;; directly, exactly like any other candidate. The :new/:unknown judged-mint
;; mechanics themselves are already covered by
;; rs7_domain_family_merge_test.clj's
;; new-verdict-mints-a-family-and-carries-merge-verdict /
;; unknown-verdict-defers-and-mints-nothing.
;; =============================================================================

(deftest a-family-labeled-top-1-mints-under-itself-not-a-rewritten-parent
  (testing "top-1's own id happens to be one :domain-family-parent-fn calls a
            family (an input production can no longer construct since C5') —
            a :new verdict mints a child under THAT id directly; there is no
            more rewrite to any 'family's own parent shape'"
    (let [family-id (random-uuid) family-parent-id (random-uuid)
          candidate (tree-class-candidate family-id 0.95
                      :domain-coverage :partial
                      :domain-label "Ultra Distance Coaching"
                      :domain-reasoning "A distinct domain from the family's own.")
          expected-id (expected-domain-child-id family-id "ultra-distance-coaching")]
      (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                    tc/get-consolidation-total* (fn [_ _ _] 0)]
        (let [r (ontology/classify-task
                 {:domain-children-fn (fn [_ _] [])
                  :domain-families-fn (fn [_] [])
                  :domain-family-parent-fn
                  (fn [_ id] (when (= id family-id)
                              {:parent-id family-parent-id :domain-label "marathon-training-plan"}))
                  :domain-merge-fn (fn [_ _] {:kind :new})}
                 {:task-signature "x" :threshold 0.7})]
          (is (= :mint-domain-child (:assigned-via r)))
          (is (= expected-id (:assigned-tree-id r)))
          (is (= family-id (:parent-tree-id r))
              "minted under top-1's own id directly — no rewrite to any other parent")
          (is (true? (:was-fresh-mint? r))))))))

;; =============================================================================
;; Store-backed (rs3-durable style, real in-memory store) — an EXACT
;; canonical-label match against a real tenant-wide family is STILL judged
;; (the dominant unjudged false-merge path this bundle closes): the merge
;; judge is called exactly once, the landing records the verdict, and no
;; second concept is created.
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

(deftest exact-label-landing-is-judged-records-verdict-and-creates-no-concept
  (testing "a REAL minted family carries label recipe-scaling; a second task
            on a DIFFERENT shape whose OWN judged label canonicalises to the
            EXACT SAME string is now JUDGED (call count 1, was 0 before this
            bundle) before it lands; no new concept, the verdict carried on
            the classified event"
    (th/with-test-context [base]
      (let [ctx (assoc base :command-registry (cp/global-command-registry))
            family-parent-id (random-uuid)
            family-id (random-uuid)
            other-shape-id (random-uuid)
            candidate (tree-class-candidate other-shape-id 0.95
                        :domain-coverage :partial
                        :domain-label "Recipe Scaling"
                        :domain-reasoning "Shares subject matter but not the output kind.")]
        (dispatch! ctx (mint-command family-parent-id family-id "recipe-scaling"))
        (let [baseline (concept-created-count ctx)
              merge-calls (atom [])
              result (with-redefs [ontology/search-descriptions (fn [_ _] [candidate])
                                   tc/get-consolidation-total* (fn [_ _ _] 0)]
                       (ontology/classify-task
                        (assoc ctx :domain-merge-fn (fn [_ q] (swap! merge-calls conj q) {:kind :same :family family-id}))
                        {:task-signature "batch scaling of recipe quantities"
                         :threshold 0.7}))
              cmd-result (dispatch! ctx (assign-command result))
              events (:command-result/events cmd-result)
              classified-ev (some #(when (= :ontology/task-classified (:event/type %)) %) events)]
          (is (= 1 (count @merge-calls))
              "the exact-label landing is now JUDGED — this is the core CV-D flip")
          (is (= family-id (:proposed (first @merge-calls)))
              "the tenant-wide label match rode as the proposed family")
          (is (= :land-on-domain-child (:assigned-via result)))
          (is (= family-id (:assigned-tree-id result)))
          (is (= family-parent-id (:parent-tree-id result)))
          (is (= baseline (concept-created-count ctx)) "a landing records NO new concept")
          (is (some? classified-ev))
          (is (= {:kind :same :family family-id} (:merge-verdict classified-ev))))))))
