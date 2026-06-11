(ns predict-rlm-comparison.run.recursive.document-redaction
  "Recursive-mode variant of document_redaction (experimental).

   Usage:
     export OPENROUTER_API_KEY=sk-or-v1-...
     clj -M:dev:test -m predict-rlm-comparison.run.recursive.document-redaction"
  (:require [predict-rlm-comparison.run.recursive._common :as r]
            [predict-rlm-comparison.tasks.document-redaction :as task])
  (:gen-class))

(defn -main [& _args]
  (r/run-recursive! task/task))
