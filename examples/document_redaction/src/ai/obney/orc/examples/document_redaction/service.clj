(ns ai.obney.orc.examples.document-redaction.service
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.document-redaction.pipeline :as pipeline]
            [ai.obney.orc.examples.document-redaction.agentic :as agentic]))

(defn- with-skills [ctx]
  (cond-> ctx (not (:call-tool-fn ctx)) (assoc :call-tool-fn (ds/call-tool-fn))))

(defn execute-pipeline [ctx inputs & opts]
  (let [ctx (with-skills ctx)
        sid (dsl/build-workflow! ctx pipeline/workflow)]
    (apply runtime/execute ctx sid inputs opts)))

(defn execute-agentic [ctx inputs & opts]
  (let [ctx (with-skills ctx)
        sid (dsl/build-workflow! ctx agentic/workflow)]
    (apply runtime/execute ctx sid inputs opts)))

(defn pipeline-sheet-id [ctx] (dsl/build-workflow! (with-skills ctx) pipeline/workflow))
(defn agentic-sheet-id  [ctx] (dsl/build-workflow! (with-skills ctx) agentic/workflow))
