(ns full-onet-build
  "FULL uncapped O*NET graph-B build (the culmination). Reuses the eb12 harness's
   own ctx + analysis helpers, but PRESERVES the SQLite store on completion (eb12
   `run!` deletes it in its finally) so the graph can be INDEPENDENTLY re-read /
   verified afterward (connectivity-verdict, node-origin, embed accounting).

   Config: onet-only, DEFAULT caps (nil → the extract's own 25 containers / 50
   windows — the MT-12 coverage ranker selects the high-relevance connecting
   sheets), PERSISTENT :sqlite, generous per-source budget so extraction of the
   connecting sheets is NOT starved (the earlier disconnection root cause), the
   embed phase now O(n) (~1100 concepts/s) so the CQ loop terminates.

   USAGE (detached): OPENROUTER_API_KEY=… nohup clj -J-Xmx10g -M:dev:test \\
                       -m full-onet-build > /tmp/full-onet-build.log 2>&1 &"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [clojure.pprint :as pp]))

(defn -main [& _]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx     (#'h/make-ctx {:store :sqlite})
        oid     (random-uuid)
        db-file (:eb12-graph-b-central-evolver/db-file ctx)
        tenant  (:tenant-id ctx)
        onet    (first (filter #(= :onet (:name %)) (h/sources)))
        srcs    [(select-keys onet [:type :path])]
        budget  {:max-iterations 40 :total-budget-ms 1800000 :max-retries 3}]
    (println "=== FULL O*NET GRAPH-B BUILD (uncapped, sqlite PRESERVED) ===")
    (println "oid    :" oid)
    (println "tenant :" tenant)
    (println "db-file:" db-file "  (PRESERVED for independent forensics)")
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
      (when-not (= :complete (:status result))
        (println "!! NON-COMPLETE — diagnostics:")
        (pp/pprint (select-keys result [:status :mode :failed-at :error :failed-step
                                        :failed-source :branch-points])))
      (println "\n>>> graph-stats")
      (pp/pprint (try (h/graph-stats ctx oid) (catch Throwable t {:error (.getMessage t)})))
      (println "\n>>> connectivity-proof")
      (pp/pprint (try (h/connectivity-proof ctx oid) (catch Throwable t {:error (.getMessage t)})))
      (println "\n>>> cq-loop / verdict")
      (pp/pprint (select-keys result [:cq-verdict :graph-health]))
      ;; PRESERVE the db: close store/cache/pubsub WITHOUT stop-ctx's file deletion.
      (es/stop (:event-store ctx))
      (kv/stop (:cache ctx))
      (pubsub/stop (:event-pubsub ctx))
      (println "\n=== BUILD COMPLETE — store preserved ===")
      (println "OID:" oid)
      (println "TENANT:" tenant)
      (println "DB-FILE:" db-file))
    (shutdown-agents)
    (System/exit 0)))
