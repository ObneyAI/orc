(ns ai.obney.orc.examples.image-analysis.service
  "Build + execute helpers for both styles of the image_analysis port.

   Both functions return orc's standard result map:
     {:status :success | :failure | :timeout
      :outputs {:answer string}
      :duration-ms n
      :rlm {…}                ;; only on agentic style with :rlm true}"
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.examples.image-analysis.pipeline :as pipeline]
            [ai.obney.orc.examples.image-analysis.agentic :as agentic]))

(defn execute-pipeline
  "Build (idempotent) and execute the Style-A pipeline workflow.
   `inputs` is a map of {:image-paths […] :query \"…\"}."
  [ctx inputs & opts]
  (let [sheet-id (dsl/build-workflow! ctx pipeline/workflow)]
    (apply runtime/execute ctx sheet-id inputs opts)))

(defn execute-agentic
  "Build (idempotent) and execute the Style-B repl-researcher workflow."
  [ctx inputs & opts]
  (let [sheet-id (dsl/build-workflow! ctx agentic/workflow)]
    (apply runtime/execute ctx sheet-id inputs opts)))

(defn pipeline-sheet-id
  "Returns the deterministic sheet-id for the pipeline workflow."
  [ctx]
  (dsl/build-workflow! ctx pipeline/workflow))

(defn agentic-sheet-id
  "Returns the deterministic sheet-id for the agentic workflow."
  [ctx]
  (dsl/build-workflow! ctx agentic/workflow))
