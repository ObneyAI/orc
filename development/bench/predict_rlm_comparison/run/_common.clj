(ns predict-rlm-comparison.run._common
  "Shared bootstrap for per-benchmark -main runners.

   Each run namespace (e.g. predict-rlm-comparison.run.image-analysis)
   has a (defn -main [& args] ...) that calls (run-benchmark! task-var)
   from here.

   Responsibilities:
   - Verify OPENROUTER_API_KEY is set, fail fast with helpful error
   - Boot the runner (start!), execute the task, tear down (stop!)
   - Print a structured status block: STATUS, DURATION, TOTAL TOKENS
   - Exit non-zero on failure for CI / shell pipelines"
  (:require [predict-rlm-comparison.runner :as runner]))

(defn- check-prerequisites!
  "Fail fast with a helpful error if OPENROUTER_API_KEY is missing.
   Returns nil when OK; calls System/exit otherwise."
  []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (binding [*out* *err*]
      (println "ERROR: OPENROUTER_API_KEY environment variable not set.")
      (println "Get a key at https://openrouter.ai and export it:")
      (println "  export OPENROUTER_API_KEY=sk-or-v1-..."))
    (System/exit 1)))

(defn run-benchmark!
  "Standardized benchmark runner entry-point for the run.* namespaces.

   Arguments:
     task — the task map (typically the :task def from a task ns).

   Behavior:
   1. Check OPENROUTER_API_KEY (exit 1 with helpful message if missing).
   2. Call (runner/start!), (runner/run! task), (runner/stop!).
   3. Print structured status block (STATUS / DURATION / TOTAL TOKENS).
   4. Exit non-zero if status isn't :success."
  [task]
  (check-prerequisites!)
  (try
    (runner/start!)
    (let [result (runner/run! task)
          status (:status result)
          duration-ms (:duration-ms result)
          tokens (get-in result [:usage :total-tokens])]
      (println)
      (println "========================================")
      (println (str "STATUS:       " status))
      (println (str "DURATION:     " duration-ms " ms"))
      (when tokens
        (println (str "TOTAL TOKENS: " tokens)))
      (println "========================================")
      (runner/stop!)
      (println)
      (println "Run complete. Look under development/bench/predict-rlm-comparison/results/")
      (println "for the freshly-written EDN files.")
      (when-not (= :success status)
        (System/exit 1)))
    (catch Throwable t
      (binding [*out* *err*]
        (println "Run failed:" (.getMessage t))
        (.printStackTrace t))
      (try (runner/stop!) (catch Throwable _))
      (System/exit 1))))
