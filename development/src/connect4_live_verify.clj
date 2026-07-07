(ns connect4-live-verify
  "CONNECT-4 — the LIVE bounded O*NET build launcher (the QA half; the durable
   half is `ai.obney.orc.ontology.core.connectivity-acceptance/connectivity-verdict`
   + its brick tests).

   REUSES `eb12-graph-b-central-evolver/run!` AS-IS (no fork) with the bounded caps
   the CONNECT-4 issue specifies: onet-only, :max-containers 10, :max-windows 5,
   PERSISTENT :sqlite store. The build lands occupations + the associative fold's
   element nodes + occupation→element edges (extraction+landing — UPSTREAM of the
   slow ~13x-re-embed CQ loop), then begins embedding.

   This process is launched DETACHED with -Xmx6g and KILLED after landing (once
   embedding has begun) — killing it before `run!`'s `finally` deletes the SQLite
   db-file, so the persisted store survives for the forensic read
   (`connect4-forensics`). Reading the edges does NOT depend on the CQ loop
   finishing (the CONNECT-4 perf caveat).

   USAGE (detached): OPENROUTER_API_KEY=… nohup clj -J-Xmx6g -M:dev:test \\
                       -m connect4-live-verify > /tmp/connect4-build.log 2>&1 &"
  (:require [eb12-graph-b-central-evolver :as h]))

(defn -main [& _]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (println "=== CONNECT-4 LIVE — bounded onet-only build (reuses eb12 run!) ===")
  (println "caps: :max-containers 10 :max-windows 5  store: :sqlite  -Xmx6g")
  (let [r (h/run! {:only [:onet]
                   :max-containers 10
                   :max-windows 5
                   :store :sqlite
                   :budget {:max-iterations 8 :total-budget-ms 1800000 :max-retries 3}
                   :evolver-config {:max-iterations 1}})]
    ;; If we ever reach here the CQ loop finished on its own (the db is then
    ;; deleted by run!'s finally). The intended path kills this process during
    ;; the CQ loop, so this line is a fallback only.
    (println "=== run! RETURNED (CQ loop finished) status:" (:status r)
             " oid:" (:ontology-id r) " ===")
    (shutdown-agents)
    (System/exit 0)))
