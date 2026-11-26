(ns rs-p2-parent-edge-probe
  "PROTOTYPE — throwaway. RS-P2: can a domain child's parent edge be born from
   the classification path, and can walk-down then see the child?
   Three questions against the real in-memory store (sync context, projector
   fn invoked directly where a processor would run):
     Q-a  the seeded path: record-tree-description at :tree-fingerprint scope
          with :parent-tree-id → projector → skos:broader edge?
     Q-b  the command path: ensure both concepts + create-relationship
          (no description written) → edge?
     Q-c  a child whose only description comes from a :tree-class claim (the
          CV-1 signature route): does get-tree-class-children (walk-down's
          child lookup, which reads :tree-fingerprint scope) see it?
   Delete when RS-3 lands."
  (:require [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.core.logic :as ontology-logic]))

(defn- cmd! [ctx m]
  (cp/process-command (assoc ctx :command (merge {:command/id (random-uuid)
                                                 :command/timestamp (time/now)} m))))

(defn- uri [id] (str "tree-class:" id))

(defn run! []
  (h/with-test-context [base]
    (let [ctx (assoc base :command-registry (cp/global-command-registry))
          parent (random-uuid) child-a (random-uuid) child-b (random-uuid) child-c (random-uuid)
          body {:capabilities ["probe"] :strengths [] :weaknesses [] :avoid-when []
                :representative-uses ["probe"] :summary "probe child" :version 1
                :consolidated-from-event-count 0}]
      (cmd! ctx {:command/name :ontology/record-tree-description
                 :target-id (str child-a) :body (assoc body :parent-tree-id parent)})
      (let [ev (first (into [] (es/read (:event-store ctx)
                                       {:tenant-id (:tenant-id ctx)
                                        :types #{:ontology/tree-description-updated}})))]
        (when-not ev
          (throw (ex-info "Seed description was not recorded" {:child child-a})))
        (ontology-logic/on-tree-description-updated-project-concept (assoc ctx :event ev)))
      (println "Q-a seeded path (fingerprint-scope description with :parent-tree-id → projector):"
               "narrower of parent =" (ontology/get-narrower-concepts ctx (uri parent)))
      (let [target (ontology-logic/ensure-concept! ctx :tree-class parent)
            source (ontology-logic/ensure-concept! ctx :tree-class child-b)]
        (ontology-logic/ensure-edge! ctx source target "skos:broader"))
      (println "Q-b command path (ensure concepts + create-relationship, no body):"
               "narrower of parent =" (ontology/get-narrower-concepts ctx (uri parent)))
      (let [target (ontology-logic/ensure-concept! ctx :tree-class parent)
            source (ontology-logic/ensure-concept! ctx :tree-class child-c)]
        (ontology-logic/ensure-edge! ctx source target "skos:broader")
        (cmd! ctx {:command/name :ontology/record-claim-deltas
                   :granularity :tree-class :target-identifier child-c
                   :deltas [{:operation :add :kind :representative-use
                             :content "marathon training plan: 16-week progressive schedule"
                             :context-guard nil :recommendation nil :episodes []
                             :from-legacy-corpus false :evidence-basis :classification-signature}]
                   :evidence-event-count 0
                   :claim-set-version (ontology/get-claim-set-version ctx :tree-class child-c)}))
      (println "Q-c description at :tree-class scope =" (some? (ontology/get-description ctx :tree-class child-c))
               "| at :tree-fingerprint scope =" (some? (ontology/get-description ctx :tree-fingerprint (str child-c))))
      (println "Q-c walk-down child lookup (get-tree-class-children parent) sees:"
               (mapv :target-id (@#'ontology-logic/get-tree-class-children ctx parent)))
      (println "narrower of parent now =" (ontology/get-narrower-concepts ctx (uri parent))))))
