(ns ai.obney.orc.examples.document-analysis.service
  "Build + execute helpers for both styles. Wraps the user-supplied ctx with
   the doc-skills :call-tool-fn so the Style-B repl-researcher can invoke
   pdf/* and docx/* tools."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.document-analysis.pipeline :as pipeline]
            [ai.obney.orc.examples.document-analysis.agentic :as agentic]))

(defn- with-skills [ctx]
  (cond-> ctx
    (not (:call-tool-fn ctx)) (assoc :call-tool-fn (ds/call-tool-fn))))

(defn execute-pipeline
  [ctx inputs & opts]
  (let [ctx (with-skills ctx)
        sheet-id (dsl/build-workflow! ctx pipeline/workflow)]
    (apply runtime/execute ctx sheet-id inputs opts)))

(defn execute-agentic
  [ctx inputs & opts]
  (let [ctx (with-skills ctx)
        sheet-id (dsl/build-workflow! ctx agentic/workflow)]
    (apply runtime/execute ctx sheet-id inputs opts)))

(defn pipeline-sheet-id [ctx] (dsl/build-workflow! (with-skills ctx) pipeline/workflow))
(defn agentic-sheet-id  [ctx] (dsl/build-workflow! (with-skills ctx) agentic/workflow))
