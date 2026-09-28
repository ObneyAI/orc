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
            [ai.obney.orc.ontology.core.commands :as commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors :as ont-tp]
            [ai.obney.orc.ontology.core.task-classifier :as classifier]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

(defn- cmd! [ctx m]
  (let [r (cp/process-command (assoc ctx :command (merge {:command/id (random-uuid)
                                                          :command/timestamp (time/now)} m)))]
    (h/apply-events! ctx r)
    r))

(defn- uri [id] (str "tree-class:" id))

(defn run! []
  (h/with-test-context [base]
    (let [ctx (assoc base :command-registry (cp/global-command-registry))
          parent (random-uuid) child-a (random-uuid) child-b (random-uuid) child-c (random-uuid)
          body {:capabilities ["probe"] :strengths [] :weaknesses [] :avoid-when []
                :representative-uses ["probe"] :summary "probe child" :version 1}]
      ;; Q-a — seeded path
      (let [r (cmd! ctx {:command/name :ontology/record-tree-description
                         :target-id (str child-a) :body (assoc body :parent-tree-id parent)})
            ev (first (:command-result/events r))]
        (ont-tp/on-tree-description-updated-project-concept (assoc ctx :event (merge (:event/body ev) (select-keys ev [:target-type :target-id :body]))))
        ;; the processor receives the event BODY as :event (grain convention) — try both shapes
        (ont-tp/on-tree-description-updated-project-concept (assoc ctx :event (:event/body ev))))
      (println "Q-a seeded path (fingerprint-scope description with :parent-tree-id → projector):"
               "narrower of parent =" (ontology/get-narrower-concepts ctx (uri parent)))
      ;; Q-b — command path, no description
      (let [ensure @#'ont-tp/ensure-tree-class-concept!]
        (ensure ctx parent) (ensure ctx child-b)
        (cmd! ctx {:command/name :ontology/create-relationship
                   :source-ontology-id @#'ont-tp/tree-class-ontology-id
                   :target-ontology-id @#'ont-tp/tree-class-ontology-id
                   :source-uri (uri child-b) :target-uri (uri parent) :predicate "skos:broader"}))
      (println "Q-b command path (ensure concepts + create-relationship, no body):"
               "narrower of parent =" (ontology/get-narrower-concepts ctx (uri parent)))
      ;; Q-c — child described only by a :tree-class claim (CV-1's route)
      (let [ensure @#'ont-tp/ensure-tree-class-concept!]
        (ensure ctx child-c)
        (cmd! ctx {:command/name :ontology/create-relationship
                   :source-ontology-id @#'ont-tp/tree-class-ontology-id
                   :target-ontology-id @#'ont-tp/tree-class-ontology-id
                   :source-uri (uri child-c) :target-uri (uri parent) :predicate "skos:broader"})
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
               (mapv :target-id (@#'classifier/get-tree-class-children ctx parent)))
      (println "narrower of parent now =" (ontology/get-narrower-concepts ctx (uri parent))))))
