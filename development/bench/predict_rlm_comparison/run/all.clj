(ns predict-rlm-comparison.run.all
  "Standalone -main runner for all 5 predict-rlm comparison benchmarks.

   Replaces the previous run_all.sh shell script.

   Expected total runtime: ~6-10 minutes
   Expected total cost:    ~$1.30-2.10

   Order is fastest-first (good for early-failure feedback):
     1. image_analysis        (~25-35s, ~$0.05)
     2. document_redaction    (~30-60s, ~$0.10-0.15)
     3. invoice_processing    (~25-40s, ~$0.05-0.10)
     4. contract_comparison   (~50-90s, ~$0.15-0.25)
     5. document_analysis     (~3-5min, ~$1.00-1.50  — the expensive one)

   Usage:
     export OPENROUTER_API_KEY=sk-or-v1-...
     clj -M:dev:test -m predict-rlm-comparison.run.all

   Each benchmark writes its own result EDN to results/<benchmark>_<timestamp>.edn.
   After all 5 complete the runner prints a summary table; failures exit non-zero."
  (:require [predict-rlm-comparison.runner :as runner]
            [predict-rlm-comparison.tasks.image-analysis :as image-analysis]
            [predict-rlm-comparison.tasks.document-redaction :as document-redaction]
            [predict-rlm-comparison.tasks.invoice-processing :as invoice-processing]
            [predict-rlm-comparison.tasks.contract-comparison :as contract-comparison]
            [predict-rlm-comparison.tasks.document-analysis :as document-analysis])
  (:gen-class))

(def ^:private tasks
  "Tasks in fastest-first execution order."
  [image-analysis/task
   document-redaction/task
   invoice-processing/task
   contract-comparison/task
   document-analysis/task])

(defn- check-prerequisites! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (binding [*out* *err*]
      (println "ERROR: OPENROUTER_API_KEY environment variable not set.")
      (println "Get a key at https://openrouter.ai and export it:")
      (println "  export OPENROUTER_API_KEY=sk-or-v1-..."))
    (System/exit 1)))

(defn -main [& _args]
  (check-prerequisites!)
  (let [start-ms (System/currentTimeMillis)]
    (println)
    (println "==========================================================================")
    (println "Running all 5 predict-rlm comparison benchmarks (fastest-first)")
    (println "Expected total runtime: 6-10 minutes / total cost ~$1.30-2.10")
    (println "==========================================================================")
    (try
      (runner/start!)
      (let [results (mapv runner/run! tasks)
            any-failed? (some #(not= :success (:status %)) results)
            duration-ms (- (System/currentTimeMillis) start-ms)
            mins (long (/ duration-ms 60000))
            secs (long (/ (mod duration-ms 60000) 1000))]
        (println)
        (println "==========================================================================")
        (println (str "All 5 benchmarks complete in " mins "m " secs "s"))
        (println "==========================================================================")
        (println)
        (println "## Per-benchmark status")
        (doseq [r results]
          (println (format "  %-40s %s  duration=%.1fs  tokens=%s"
                          (:task-name r "<unknown>")
                          (:status r)
                          (/ (:duration-ms r 0) 1000.0)
                          (or (get-in r [:usage :total-tokens]) "n/a"))))
        (println)
        (runner/generate-summary! "development/bench/predict_rlm_comparison/results")
        (runner/stop!)
        (when any-failed?
          (binding [*out* *err*]
            (println "One or more benchmarks did not return :success — see EDN files for detail."))
          (System/exit 1)))
      (catch Throwable t
        (binding [*out* *err*]
          (println "Run failed:" (.getMessage t))
          (.printStackTrace t))
        (try (runner/stop!) (catch Throwable _))
        (System/exit 1)))))
