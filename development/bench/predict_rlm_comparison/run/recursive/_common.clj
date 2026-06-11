(ns predict-rlm-comparison.run.recursive._common
  "Shared helper for recursive-mode experimental runs.

   This namespace is LOCAL/EXPERIMENTAL — not for external documentation.
   It builds a recursive-mode variant of a benchmark task (overrides
   :recursive? to true, suffixes the :slug with -recursive so result EDN
   files land alongside but distinguishable from the terminal-mode
   headlines), then delegates to the standard runner.

   End-goal motivation: investigate whether recursive mode (model can
   emit follow-up trees + inspect outputs + retry) produces equivalent
   or better quality than terminal mode (single tree → result) across
   the 5 predict-rlm comparison benchmarks. If recursive matches or
   beats terminal on all 5, recursive can become the always-on default."
  (:require [predict-rlm-comparison.run._common :as common]))

(defn ->recursive
  "Take a task map and return a recursive-mode variant:
   - :recursive? true (picked up by runner's :rlm config)
   - :slug suffixed with -recursive so EDN files don't overwrite headlines
   - :name suffixed with (recursive)"
  [task]
  (assoc task
         :recursive? true
         :slug (str (:slug task) "-recursive")
         :name (str (:name task) " (recursive)")))

(defn run-recursive!
  "Build the recursive variant of `task` and dispatch through the standard
   benchmark runner. Result EDN lands in results/ with the -recursive slug."
  [task]
  (common/run-benchmark! (->recursive task)))
