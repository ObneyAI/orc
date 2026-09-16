(ns ai.obney.orc.ontology.rs3-domain-child-birth-test
  "RS-3 cycle 1 — `:ontology/mint-domain-child` births a domain child
   through ONE command: the child's tree-class concept exists, carrying
   its judged label; the parent's narrower set contains the child
   (`get-narrower-concepts`, the same graph edge walk-down's own child
   lookup reads); and `:ontology/domain-child-minted` is emitted.
   RS-P2's verdict: the COMMAND path (ensure both concepts + dispatch
   `create-relationship`), never a description body write (CC-6).

   Idempotent: minting the same child identity again emits nothing new
   (`@invariant DomainChildIdentityIsStable`)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.todo-processors :as ont-tp]
            [ai.obney.orc.ontology.test-helpers :as th]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

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
  (let [r (cp/process-command (assoc ctx :command command))]
    (th/apply-events! ctx r)
    r))

(defn- tree-class-uri [id] (str "tree-class:" id))

(deftest mint-domain-child-births-the-child-concept-and-parent-edge
  (testing "the child's tree-class concept exists with :label = the domain
            label, the parent's narrower set contains the child, and
            :ontology/domain-child-minted is emitted"
    (th/with-test-context [base]
      (let [ctx (assoc base :command-registry (cp/global-command-registry))
            parent-id (random-uuid)
            child-id (random-uuid)
            r (dispatch! ctx (mint-command parent-id child-id "marathon-training-plan"))
            events (:command-result/events r)
            child-concept (ontology/get-concept-by-uri ctx (tree-class-uri child-id))]
        (is (some #(= :ontology/domain-child-minted (:event/type %)) events)
            ":ontology/domain-child-minted was emitted")
        (is (some? child-concept)
            "the child's tree-class concept exists")
        (is (= "marathon-training-plan" (:label child-concept))
            "the child concept's :label is the domain label")
        (is (contains? (ontology/get-narrower-concepts ctx (tree-class-uri parent-id))
                       (tree-class-uri child-id))
            "the parent's narrower set contains the child — the same edge
             walk-down's own child lookup reads")))))

(deftest mint-domain-child-is-idempotent-on-the-same-identity
  (testing "minting the same child identity again emits nothing new"
    (th/with-test-context [base]
      (let [ctx (assoc base :command-registry (cp/global-command-registry))
            parent-id (random-uuid)
            child-id (random-uuid)]
        (dispatch! ctx (mint-command parent-id child-id "recipe-scaling"))
        (let [r2 (dispatch! ctx (mint-command parent-id child-id "recipe-scaling"))]
          (is (empty? (:command-result/events r2))
              "re-minting the SAME identity emits nothing new")
          (is (contains? (ontology/get-narrower-concepts ctx (tree-class-uri parent-id))
                         (tree-class-uri child-id))
              "the edge still holds after the no-op re-mint"))))))

;; =============================================================================
;; Cycle 2 — RS-2's `default-domain-children-fn` reads the CHILD CONCEPT's
;; :label (this slice's write), not a :tree-class description's :domain-label
;; field (RS-2's documented gap, now closed).
;; =============================================================================

(deftest default-domain-children-fn-reads-the-concepts-label-after-mint
  (testing "before any mint, the default domain-children lookup returns [];
            after cycle 1's mint, it returns the child with its label read
            from the CONCEPT (not a description)"
    (th/with-test-context [base]
      (let [ctx (assoc base :command-registry (cp/global-command-registry))
            parent-id (random-uuid)
            child-id (random-uuid)]
        (is (= [] (#'tc/default-domain-children-fn ctx parent-id))
            "no domain children before any mint")
        (dispatch! ctx (mint-command parent-id child-id "marathon-training-plan"))
        (is (= [{:target-id child-id :domain-label "marathon-training-plan"}]
               (#'tc/default-domain-children-fn ctx parent-id))
            "after the mint, the default fn returns the child with its
             CONCEPT label")))))

;; =============================================================================
;; Cycle 3 — walk-down's OWN child lookup (`get-tree-class-children`) reads a
;; child's description at :tree-class scope FIRST (the CV-1 signature route
;; RS-3's domain children describe themselves through), falling back to
;; :tree-fingerprint (the seeded instances) — RS-P2's Q-c finding, closed.
;; =============================================================================

(defn- claim-command [ctx child-id content]
  {:command/name :ontology/record-claim-deltas
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :granularity :tree-class
   :target-identifier child-id
   :deltas [{:operation :add
             :kind :representative-use
             :content content
             :context-guard nil
             :recommendation nil
             :episodes []
             :from-legacy-corpus false
             :evidence-basis :classification-signature}]
   :evidence-event-count 0
   :claim-set-version (ontology/get-claim-set-version ctx :tree-class child-id)})

(deftest get-tree-class-children-reads-tree-class-scope-description
  (testing "a child described ONLY by a :tree-class claim (CV-1's signature
            route) IS returned by walk-down's own child lookup"
    (th/with-test-context [base]
      (let [ctx (assoc base :command-registry (cp/global-command-registry))
            parent-id (random-uuid)
            child-id (random-uuid)]
        (dispatch! ctx (mint-command parent-id child-id "marathon-training-plan"))
        (dispatch! ctx (claim-command ctx child-id "marathon training plan: 16-week schedule"))
        (let [children (#'tc/get-tree-class-children ctx parent-id)]
          (is (= 1 (count children)))
          (is (= child-id (:target-id (first children))))
          (is (some? (:description (first children)))
              "the child's :tree-class-scoped assembled description is returned"))))))

(deftest get-tree-class-children-still-returns-tree-fingerprint-seeded-child
  (testing "GUARD: a seeded child described ONLY at :tree-fingerprint scope
            (walk_down_classifier_test's world) is STILL returned — the
            fallback is preserved"
    (th/with-test-context [base]
      (let [ctx (assoc base :command-registry (cp/global-command-registry))
            parent-uri "tree-class:seed-parent"
            child-fp "seed:tree:ChunkedExtraction"
            body {:capabilities [] :strengths [] :weaknesses [] :avoid-when []
                  :representative-uses [] :summary "seed child" :version 1
                  :consolidated-from-event-count 0
                  :parent-tree-id "seed-parent"}]
        (dispatch! ctx {:command/name :ontology/record-tree-description
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :target-id child-fp
                        :body body})
        ;; Project the parent/child relationship exactly as C-2d-1's reactive
        ;; processor does — real production projector fn, invoked directly on
        ;; the durable event read back from the store (the flattened shape a
        ;; processor actually receives; mirrors RS-P2's Q-a probe usage).
        (let [ev (last (into [] (es/read (:event-store ctx)
                                         {:tenant-id (:tenant-id ctx)
                                          :types #{:ontology/tree-description-updated}})))]
          (ont-tp/on-tree-description-updated-project-concept (assoc ctx :event ev)))
        (let [children (#'tc/get-tree-class-children ctx "seed-parent")]
          (is (= 1 (count children)))
          (is (= child-fp (:target-id (first children))))
          (is (= "seed child" (:summary (:description (first children))))
              "the :tree-fingerprint-scoped description is still read as a fallback"))))))
