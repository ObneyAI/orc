(ns gc7-live-build-verify
  "GC-7 — Cycle 4: the comprehensive multi-container build COMPLETES on the GC-4
   :sqlite store (no never-finishing, no OOM). This is the proof GC-4 could not
   produce: with the unbounded attribute-links cross-product + unbounded per-draft
   probe, the build over IPEDS C2022_A (~1.6M rows) never terminated (two stuck
   builds were killed). GC-7 bounds the WORK (key-pair-memoized attribute-links +
   capped probe) so the same build reaches a TERMINAL status.

   JVM HYGIENE (binding): the build runs inside a future with a HARD deref timeout
   + System/exit, so it can NEVER spin forever. On timeout we exit non-zero (a
   FINDING), never leave a hot-loop JVM.

   Run (real LLM, OPENROUTER_API_KEY env only):
     clj -M:dev -e \"(require 'gc7-live-build-verify)(gc7-live-build-verify/-main)\""
  (:require [eb12-graph-b-central-evolver :as b]
            [clojure.pprint :as pp]))

(defn run-bounded!
  "Run the IPEDS-only comprehensive build on :sqlite (the C2022_A blowup source),
   bounded. Returns the terminal status + projection-read stats (read off the live
   sqlite store inside `run!` BEFORE its own stop-ctx cleans the db-file)."
  []
  ;; A modest per-source budget — the point is TERMINATION, not exhaustive coverage.
  (b/run! {:store :sqlite
           :only [:ipeds]
           :budget {:max-iterations 8 :total-budget-ms 420000 :max-retries 3}
           :evolver-config {:max-iterations 2}}))

(defn -main [& _]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "OPENROUTER_API_KEY missing") (System/exit 2))
  (let [fut (future
              (try
                (let [r (run-bounded!)]
                  {:status (:status r)
                   :mode (:mode r)
                   :elapsed-ms (:elapsed-ms r)
                   :stats (:stats r)
                   :cq-termination (get-in r [:cq-loop :termination-reason])})
                (catch Throwable t
                  (println "GC-7 live build FAILED:" (.getMessage t))
                  (.printStackTrace t)
                  {:status :exception :error (.getMessage t)})))
        ;; HARD ceiling — 16 min. If the bound regressed and the build spins, this
        ;; deref returns :TIMEOUT and we exit non-zero rather than hang forever.
        result (deref fut (* 16 60 1000) :TIMEOUT)]
    (println "\n================ GC-7 LIVE BUILD — TERMINAL RESULT ================")
    (if (= :TIMEOUT result)
      (do (println "!!! BUILD DID NOT TERMINATE within the 16-min ceiling — FINDING")
          (shutdown-agents)
          ;; halt! — a future that's still spinning would otherwise keep the JVM
          ;; alive; force-exit so we leave NO orphan hot-loop JVM.
          (.halt (Runtime/getRuntime) 3))
      (do (println "terminal status:" (:status result) " mode:" (:mode result)
                   " elapsed-ms:" (:elapsed-ms result))
          (println "cq-termination:" (:cq-termination result))
          (println "PROJECTION-READ stats (off the live :sqlite store):")
          (pp/pprint (:stats result))
          (shutdown-agents)
          ;; Terminal = the build reached a definite status (NOT timeout/exception).
          ;; :complete or :failed-cq are both TERMINAL (the bound let it finish; a
          ;; CQ verdict is a real terminal outcome, not a hang).
          (System/exit (if (#{:complete :failed-cq} (:status result)) 0 1))))))
