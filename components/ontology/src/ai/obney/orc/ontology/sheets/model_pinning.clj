(ns ai.obney.orc.ontology.sheets.model-pinning
  "Explicit model pinning for ontology extraction workflows."
  (:require [clojure.walk :as walk]))

(defn pin-model
  "Replace every explicitly model-backed node's model. With nil, preserve the
  workflow's production default. This operates on the DSL definition before
  it is built, so the pinned value becomes part of durable node metadata."
  [workflow model]
  (if-not model
    workflow
    (walk/postwalk
     (fn [form]
       (if (and (map? form) (contains? form :model))
         (assoc form :model model)
         form))
     workflow)))
