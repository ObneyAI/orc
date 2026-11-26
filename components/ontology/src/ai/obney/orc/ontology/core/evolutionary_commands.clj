(ns ai.obney.orc.ontology.core.evolutionary-commands
  "Command handlers for the evolutionary ontology builder.

   These commands replace direct event-store/append calls with proper
   CQRS command processing through the Grain command processor."
  (:require [ai.obney.orc.ontology.core.evolutionary-builder :as builder]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(defn- run-workflow [ctx workflow keys]
  (try
    (let [result (workflow (dissoc ctx :command) (select-keys (:command ctx) keys))]
      {:command-result/events (vec (:events result))
       :command-result/data (dissoc result :events)})
    (catch clojure.lang.ExceptionInfo e
      (if (::anom/category (ex-data e)) (ex-data e) (throw e)))))

(defcommand :ontology build-from-sources
  "Build ontology from sources in batch mode."
  [ctx]
  (run-workflow ctx builder/build-from-sources [:sources :config]))

(defcommand :ontology evolve
  "Evolve existing ontology with new sources."
  [ctx]
  (run-workflow ctx builder/evolve [:ontology-id :sources :config]))

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
