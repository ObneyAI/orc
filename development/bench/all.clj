(ns all
  "Convenience aggregator for running all 5 ORC RLM generalization benchmarks.

   External developers copying a single task file can ignore this namespace.
   This module is for internal benchmark workflows that want the full suite +
   summary generation in one require.

   Run from REPL:
     (require '[all :as bench])
     (bench/start!)       ; (or skip — run-all! starts the system itself if needed)
     (bench/run-all!)     ; runs all 5 tasks sequentially
     (bench/summary!)     ; generates markdown summary table from saved EDNs
     (bench/stop!)
  "
  (:require [runner]
            [document-analysis]
            [risk-analysis]
            [contract-comparison]
            [contract-comparison-validated]
            [legal-issue-detection]))

(def tasks
  "All 5 task definitions in execution order (cheapest first)."
  [legal-issue-detection/task
   contract-comparison-validated/task
   contract-comparison/task
   risk-analysis/task
   document-analysis/task])

(defn start!
  "Initialize the benchmark system. Delegates to `runner/start!`."
  []
  (runner/start!))

(defn stop!
  "Stop the benchmark system. Delegates to `runner/stop!`."
  []
  (runner/stop!))

(defn run-all!
  "Run all 5 generalization tasks sequentially. Returns a list of result maps."
  []
  (runner/run-all! tasks))

(defn summary!
  "Generate a markdown summary table from saved EDN result files."
  []
  (runner/generate-summary! (:results-dir runner/config)))
