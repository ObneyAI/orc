(ns predict-rlm-comparison.all
  "Convenience aggregator for running all 5 predict-rlm comparison benchmarks.

   Mirrors the pattern of `development/bench/all.clj` (the existing ORC RLM
   generalization suite's aggregator) — external developers copying a single
   task file from `tasks/` can ignore this namespace. This module is for
   running the full apples-to-apples comparison suite + summary generation
   in one require.

   Task execution order is fastest-first (cheap fails fast):
     1. image_analysis        (~25-35s, ~$0.05)
     2. document_redaction    (~30-60s, ~$0.10-0.15)
     3. invoice_processing    (~25-40s, ~$0.05-0.10)
     4. contract_comparison   (~50-90s, ~$0.15-0.25)
     5. document_analysis     (~3-5min, ~$1.00-1.50  — the expensive one)

   Total: ~6-10 minutes / ~$1.30-2.10

   Run from REPL:
     (require '[predict-rlm-comparison.all :as bench])
     (bench/start!)
     (bench/run-all!)
     (bench/summary!)
     (bench/stop!)

   Or via the standalone shell script (no REPL needed):
     export OPENROUTER_API_KEY=sk-or-v1-...
     ./development/bench/predict-rlm-comparison/scripts/run_all.sh"
  (:require [predict-rlm-comparison.runner :as runner]
            [predict-rlm-comparison.tasks.image-analysis :as image-analysis]
            [predict-rlm-comparison.tasks.document-redaction :as document-redaction]
            [predict-rlm-comparison.tasks.invoice-processing :as invoice-processing]
            [predict-rlm-comparison.tasks.contract-comparison :as contract-comparison]
            [predict-rlm-comparison.tasks.document-analysis :as document-analysis]))

(def tasks
  "All 5 task definitions in fastest-first execution order. Pulling
   this out as a def lets users run any subset by passing a slice to
   `run-all!`, e.g. (run-all! (take 3 tasks))."
  [image-analysis/task
   document-redaction/task
   invoice-processing/task
   contract-comparison/task
   document-analysis/task])

(defn start!
  "Initialize the runner system. Delegates to `runner/start!`. Safe to call
   multiple times — internally idempotent."
  []
  (runner/start!))

(defn stop!
  "Stop the runner system. Delegates to `runner/stop!`. Frees the LMDB cache
   and event store. Safe to call when system isn't started."
  []
  (runner/stop!))

(defn run-all!
  "Run all 5 (or a subset of) task definitions sequentially. Each task writes
   its own result EDN under `development/bench/predict-rlm-comparison/results/`.

   Returns a vector of result maps, one per task. Each result has the runner's
   standard shape: `:status :outputs :duration-ms :usage :node-trace :iterations`
   etc.

   Usage:
     (run-all!)                ; all 5 tasks
     (run-all! tasks)          ; explicit task list (same as default)
     (run-all! (take 3 tasks)) ; fastest 3 (image + redaction + invoice)
     (run-all! [your-task])    ; just one"
  ([] (run-all! tasks))
  ([task-list]
   (mapv runner/run! task-list)))

(defn summary!
  "Generate a markdown summary table of the latest result EDN per task,
   compared against predict-rlm's published metadata where available.
   Delegates to `runner/generate-summary!`."
  []
  (runner/generate-summary! "development/bench/predict-rlm-comparison/results"))
