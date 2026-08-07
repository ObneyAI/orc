(ns ai.obney.orc.ontology.core.evolutionary-commands
  "Command handlers for the evolutionary ontology builder.

   These commands replace direct event-store/append calls with proper
   CQRS command processing through the Grain command processor."
  (:require [ai.obney.orc.ontology.core.evolutionary-builder :as builder]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(defcommand :ontology build-from-sources
  "Build ontology from sources in batch mode."
  [ctx]
  (let [params (select-keys (:command ctx) [:sources :config])
        result (builder/build-from-sources ctx params)
        events (filterv :event/type (or (:events result) []))]
    {:command-result/events events
     :command/result (dissoc result :events)}))

(defcommand :ontology evolve
  "Evolve existing ontology with new sources."
  [ctx]
  (let [params (select-keys (:command ctx) [:ontology-id :sources :config])]
    (if-not (rm/ontology-exists? ctx (:ontology-id params))
      {::anom/category ::anom/not-found
       ::anom/message (str "Ontology not found: " (:ontology-id params))}
      (let [result (builder/evolve ctx params)
            events (vec (or (:events result) []))]
        {:command-result/events events
         :command/result (dissoc result :events)}))))

(defcommand :ontology record-colbert-index
  "Record that a ColBERT index was created for an ontology."
  [{{:keys [ontology-id index-id index-name document-count colbert-fields]} :command}]
  {:command-result/events
   [(event-store/->event
     {:type :evolutionary/colbert-indexed
      :tags #{[:ontology ontology-id] [:colbert-index index-id]}
      :body {:ontology-id ontology-id
             :index-id index-id
             :index-name index-name
             :document-count document-count
             :colbert-fields (vec colbert-fields)
             :indexed-at (str (time/now))}})]})
