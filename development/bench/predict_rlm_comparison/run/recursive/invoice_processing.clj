(ns predict-rlm-comparison.run.recursive.invoice-processing
  "Recursive-mode variant of invoice_processing (experimental).

   Usage:
     export OPENROUTER_API_KEY=sk-or-v1-...
     clj -M:dev:test -m predict-rlm-comparison.run.recursive.invoice-processing"
  (:require [predict-rlm-comparison.run.recursive._common :as r]
            [predict-rlm-comparison.tasks.invoice-processing :as task])
  (:gen-class))

(defn -main [& _args]
  (r/run-recursive! task/task))
