(ns ai.obney.orc.ontology.core.source-registry
  "Scoped source queries backed by committed ontology contributions."
  (:require [ai.obney.ontology.interface :as ontology]
            [ai.obney.ontology.algorithms :as algorithms]))

(defn get-source [ctx ontology-id source-id]
  (first (filter #(= source-id (:source-id %)) (ontology/get-sources ctx ontology-id))))
(defn get-source-by-hash [ctx ontology-id content-hash]
  (first (filter #(= content-hash (:content-hash %)) (ontology/get-sources ctx ontology-id))))
(defn check-source-processed [ctx ontology-id source-uri content]
  (let [hash (algorithms/compute-content-hash source-uri content)
        source (get-source-by-hash ctx ontology-id hash)]
    {:processed? (boolean source) :source-id (:source-id source) :computed-hash hash}))
