(ns predict-rlm-comparison.run.document-analysis
  "Standalone -main runner for the document_analysis benchmark — predict-rlm port.

   Expected runtime: ~3-5 minutes
   Expected cost:    ~$1.00-1.50 (gpt-5.4 main + gpt-5.1-chat sub via OpenRouter)
   Source task:      predict-rlm/examples/document_analysis
   Reference output: references/predict-rlm/document_analysis/sample/output/report.md
   Clean report:     reports/04_document_analysis.md

   Usage:
     export OPENROUTER_API_KEY=sk-or-v1-...
     clj -M:dev:test -m predict-rlm-comparison.run.document-analysis

   This is the most expensive benchmark in the suite (136-page RFP). The model
   typically designs a tree with an adversarial-completeness verification stage
   that surfaces ~7 additional dates beyond predict-rlm's published 12.

   Compare your output's :key-dates to predict-rlm's published table in
   references/predict-rlm/document_analysis/sample/output/report.md."
  (:require [predict-rlm-comparison.run._common :as common]
            [predict-rlm-comparison.tasks.document-analysis :as task])
  (:gen-class))

(defn -main [& _args]
  (common/run-benchmark! task/task))
