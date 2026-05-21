(ns predict-rlm-comparison.run.image-analysis
  "Standalone -main runner for the image_analysis benchmark — predict-rlm port.

   Expected runtime: ~25-35 seconds
   Expected cost:    ~$0.05 (gpt-5.4 main + gpt-5.1-chat sub via OpenRouter)
   Source task:      predict-rlm/examples/image_analysis
   Reference output: references/predict-rlm/image_analysis/sample/output/output.md
   Clean report:     reports/01_image_analysis.md

   Usage:
     export OPENROUTER_API_KEY=sk-or-v1-...
     clj -M:dev:test -m predict-rlm-comparison.run.image-analysis

   The result EDN is written to results/image-analysis_<timestamp>.edn.
   Compare your output's letter counts to predict-rlm's published table in
   references/predict-rlm/image_analysis/sample/output/output.md."
  (:require [predict-rlm-comparison.run._common :as common]
            [predict-rlm-comparison.tasks.image-analysis :as task])
  (:gen-class))

(defn -main [& _args]
  (common/run-benchmark! task/task))
