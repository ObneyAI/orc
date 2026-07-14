(ns full-graph-b-all-sources
  "THE PLANNED DELIVERABLE — full Graph-B build over ALL FIVE official sources
   (ipeds SQLite + cip/soc crosswalk CSV + O*NET Excel + Louisiana wages CSV +
   pseo Excel), uncapped (default 25/50 coverage-ranked), sqlite PRESERVED.
   This is the BRYC-comparison Graph B: same sources the hand-made A2 graph
   models, one domain goal, no per-source recipes.

   Mirrors full-onet-build's PRESERVE + cache-warm + manifest pattern; differs
   only in: srcs = (h/sources) ALL FIVE, and a 60-min CQ-loop budget (the
   5-source graph is bigger than O*NET-only's 38k concepts — give the gate
   room to judge rather than guaranteeing :budget-exhausted).

   USAGE (detached): OPENROUTER_API_KEY=… nohup clj -J-Xmx10g -M:dev:test \\
                       -m full-graph-b-all-sources > /tmp/full-graph-b-all.log 2>&1 &"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn -main [& _]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx     (#'h/make-ctx {:store :sqlite})
        oid     (random-uuid)
        db-file (:eb12-graph-b-central-evolver/db-file ctx)
        tenant  (:tenant-id ctx)
        srcs    (mapv #(select-keys % [:type :path]) (h/sources))
        budget  {:max-iterations 40 :total-budget-ms 3600000 :max-retries 3}]
    (println "=== FULL GRAPH-B BUILD — ALL 5 SOURCES (uncapped, sqlite PRESERVED) ===")
    (println "oid    :" oid)
    (println "tenant :" tenant)
    (println "db-file:" db-file "  (PRESERVED for independent forensics)")
    (println "sources:" (mapv :name (h/sources)))
    (println "budget :" budget "  caps: default (25/50)  model:" h/default-model)
    (let [t0 (System/currentTimeMillis)
          result (ce/run-central-evolver!
                  ctx {:ontology-id oid :sources srcs :goal h/domain-goal
                       :model h/default-model :judge-fn h/real-llm-judge
                       :resilient? true :budget budget
                       :evolver-config {:max-iterations 3}
                       :max-containers nil :max-windows nil})
          elapsed (- (System/currentTimeMillis) t0)]
      (println "\n=== CENTRAL EVOLVER DONE ===")
      (println "status:" (:status result) " mode:" (:mode result)
               " termination:" (get-in result [:cq-loop :termination-reason])
               " elapsed:" elapsed "ms")
      ;; MS-2 — the per-source outcome reports (extracted counts + reconcile/
      ;; axiom/embed statuses + landed counts). A ZERO-LANDED source (its
      ;; reconcile timed out / failed) must be readable in the log at a glance.
      (println "\n>>> source-reports (per-source extracted vs landed)")
      (pp/pprint (:source-reports result))
      (when-not (= :complete (:status result))
        (println "!! NON-COMPLETE — diagnostics:")
        (pp/pprint (select-keys result [:status :mode :failed-at :error :failed-step
                                        :failed-source :branch-points :cq-loop-status])))
      (println "\n>>> graph-stats")
      (pp/pprint (try (h/graph-stats ctx oid) (catch Throwable t {:error (.getMessage t)})))
      (println "\n>>> connectivity-proof")
      (pp/pprint (try (h/connectivity-proof ctx oid) (catch Throwable t {:error (.getMessage t)})))
      (println "\n>>> cq-loop / verdict")
      (pp/pprint (select-keys result [:cq-verdict :graph-health]))
      (println "\n>>> warming the L2 read-model cache (concepts + relationships)…")
      (let [cw (System/currentTimeMillis)
            nc (count (rm/get-concepts ctx {}))
            nr (count (rm/get-relationships ctx))]
        (println "  cached concepts:" nc " relationships:" nr
                 " (" (- (System/currentTimeMillis) cw) "ms to project+cache)"))
      (let [cache-dir (when db-file (str/replace db-file #"-events\.db$" ""))
            manifest {:oid oid :tenant tenant :db-file db-file :cache-dir cache-dir
                      :sources (mapv :name (h/sources))
                      :status (:status result)}]
        (es/stop (:event-store ctx))
        (kv/stop (:cache ctx))
        (pubsub/stop (:event-pubsub ctx))
        (spit "/tmp/orc-graph-b-all-sources.manifest.edn" (pr-str manifest))
        (println "\n=== BUILD COMPLETE — store + L2 cache PRESERVED ===")
        (println "OID:" oid)
        (println "TENANT:" tenant)
        (println "DB-FILE  :" db-file)
        (println "CACHE-DIR:" cache-dir)
        (println "MANIFEST :" "/tmp/orc-graph-b-all-sources.manifest.edn")))
    (shutdown-agents)
    (System/exit 0)))
