(ns predict-rlm-comparison.run.invoice-processing
  "Standalone -main runner for the invoice_processing benchmark — predict-rlm port.

   Expected runtime: ~25-40 seconds
   Expected cost:    ~$0.05-0.10 (gpt-5.4 main + gpt-5.1-chat sub via OpenRouter)
   Source task:      predict-rlm/examples/invoice_processing
   Reference output: references/predict-rlm/invoice_processing/sample/output/
   Clean report:     reports/03_invoice_processing.md

   Usage:
     export OPENROUTER_API_KEY=sk-or-v1-...
     clj -M:dev:test -m predict-rlm-comparison.run.invoice-processing

   This benchmark produces an actual .xlsx workbook at
     results/invoice_extraction.xlsx
   Compare it side-by-side with predict-rlm's published reference:
     references/predict-rlm/invoice_processing/sample/output/invoice_extraction.xlsx

   Both workbooks have 3 sheets (Summary + per-vendor); same 7-column Summary
   layout; per-invoice line-item columns Description/Quantity/Unit Price/Amount."
  (:require [predict-rlm-comparison.run._common :as common]
            [predict-rlm-comparison.tasks.invoice-processing :as task]
            [clojure.java.io :as io])
  (:gen-class))

(defn -main [& _args]
  (common/run-benchmark! task/task)
  (let [out-path "development/bench/predict-rlm-comparison/results/invoice_extraction.xlsx"
        ref-path "development/bench/predict-rlm-comparison/references/predict-rlm/invoice_processing/sample/output/invoice_extraction.xlsx"]
    (when (.exists (io/file out-path))
      (println)
      (println (str "Output workbook:                 " out-path))
      (println (str "Reference workbook (predict-rlm): " ref-path))
      (println)
      (println "Open both side-by-side to compare:")
      (println (str "  open " out-path))
      (println (str "  open " ref-path)))))
