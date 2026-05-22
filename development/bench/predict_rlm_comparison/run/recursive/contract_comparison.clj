(ns predict-rlm-comparison.run.recursive.contract-comparison
  "Recursive-mode variant of contract_comparison (experimental).

   Usage:
     export OPENROUTER_API_KEY=sk-or-v1-...
     clj -M:dev:test -m predict-rlm-comparison.run.recursive.contract-comparison"
  (:require [predict-rlm-comparison.run.recursive._common :as r]
            [predict-rlm-comparison.tasks.contract-comparison :as task])
  (:gen-class))

(defn -main [& _args]
  (r/run-recursive! task/task))
