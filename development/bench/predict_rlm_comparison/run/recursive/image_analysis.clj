(ns predict-rlm-comparison.run.recursive.image-analysis
  "Recursive-mode variant of image_analysis benchmark (experimental,
   not for external documentation).

   Usage:
     export OPENROUTER_API_KEY=sk-or-v1-...
     clj -M:dev:test -m predict-rlm-comparison.run.recursive.image-analysis"
  (:require [predict-rlm-comparison.run.recursive._common :as r]
            [predict-rlm-comparison.tasks.image-analysis :as task])
  (:gen-class))

(defn -main [& _args]
  (r/run-recursive! task/task))
