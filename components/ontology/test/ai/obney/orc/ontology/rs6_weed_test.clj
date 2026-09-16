(ns ai.obney.orc.ontology.rs6-weed-test
  "RS-6 weed findings turned into guards.

   1.1 — the reranker is SHOWN a tree-class candidate's existing domain
   children (DomainChildIdentityIsStable: the label is canonical because it is
   CHOSEN among the parent's existing children). Until this guard the seam
   existed (candidate schema + instruction) and nothing in production filled
   it, so label reuse was coincidence and a variant label minted a sibling.

   1.12 — a domain-axis deferral is recorded at most once per occurrence
   (ClassificationIsOnePerCampaign: the domain axis rides the same
   occurrence; a retried tick emits nothing new)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
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
  (cp/process-command (assoc ctx :command command)))

(defn- tree-class-candidate [id]
  {:content "x" :score 0.9 :rank 1
   :document-id (str id)
   :document-metadata {:granularity :tree-class :target-id id :confidence 1.0}})

(deftest weed-1-1-reranker-candidate-carries-the-parents-existing-domain-children
  (testing "a tree-class candidate whose class has domain children carries
            their labels as :existing-domain-children (sorted); a class with
            none carries no such key"
    (th/with-test-context [base]
      (let [ctx (assoc base :command-registry (cp/global-command-registry))
            parent-id (random-uuid)
            other-id (random-uuid)]
        (dispatch! ctx (mint-command parent-id (random-uuid) "recipe-scaling"))
        (dispatch! ctx (mint-command parent-id (random-uuid) "marathon-training-plan"))
        (let [enriched (#'ontology/enrich-candidate-evidence ctx (tree-class-candidate parent-id))
              bare (#'ontology/enrich-candidate-evidence ctx (tree-class-candidate other-id))]
          (is (= ["marathon-training-plan" "recipe-scaling"]
                 (:existing-domain-children enriched)))
          (is (not (contains? bare :existing-domain-children))
              "a class with no domain children shows the reranker nothing"))))))

(deftest weed-1-1-string-form-target-id-still-resolves-children
  (testing "retrieval hands the target id back as a STRING (ColBERT metadata
            round-trips through JSON); the lookup resolves it the same"
    (th/with-test-context [base]
      (let [ctx (assoc base :command-registry (cp/global-command-registry))
            parent-id (random-uuid)]
        (dispatch! ctx (mint-command parent-id (random-uuid) "recipe-scaling"))
        (let [enriched (#'ontology/enrich-candidate-evidence
                        ctx (assoc-in (tree-class-candidate parent-id)
                                      [:document-metadata :target-id] (str parent-id)))]
          (is (= ["recipe-scaling"] (:existing-domain-children enriched))))))))

(deftest weed-1-12-domain-axis-deferral-is-recorded-once-per-occurrence
  (testing "dispatching the domain-axis deferral twice for the same occurrence
            leaves ONE deferred event; a fitness-axis deferral is untouched
            by this guard"
    (th/with-test-context [base]
      (let [ctx (assoc base :command-registry (cp/global-command-registry))
            sheet-id (random-uuid) tick-id (random-uuid) node-id (random-uuid)
            deferral (fn [source]
                       {:command/name :ontology/record-task-classification-deferral
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :source-sheet-id sheet-id
                        :source-tick-id tick-id
                        :source-node-id node-id
                        :fallback-source source
                        :ranked-candidates []
                        :reasoning "Domain classification deferred: axis=domain reason=unknown-coverage"})
            deferred (fn []
                       (into [] (es/read (:event-store ctx)
                                         {:tenant-id (:tenant-id ctx)
                                          :types #{:ontology/task-classification-deferred}
                                          :tags #{[:tick tick-id]}})))]
        (dispatch! ctx (deferral :domain-coverage))
        (let [second-result (dispatch! ctx (deferral :domain-coverage))]
          (is (empty? (:command-result/events second-result))
              "the retried domain deferral emits nothing new")
          (is (= 1 (count (deferred))) "exactly one domain deferral for the occurrence"))
        (dispatch! ctx (deferral :colbert-fallback))
        (is (= 2 (count (deferred)))
            "a fitness-axis deferral on the same tick is not deduped by the domain guard")))))
