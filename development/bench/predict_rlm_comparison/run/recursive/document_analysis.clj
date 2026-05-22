(ns predict-rlm-comparison.run.recursive.document-analysis
  "Recursive-mode variant of document_analysis (experimental).

   Usage:
     export OPENROUTER_API_KEY=sk-or-v1-...
     clj -M:dev:test -m predict-rlm-comparison.run.recursive.document-analysis"
  (:require [predict-rlm-comparison.run.recursive._common :as r]
            [predict-rlm-comparison.tasks.document-analysis :as task])
  (:gen-class))

(defn -main [& _args]
  (r/run-recursive! task/task))
