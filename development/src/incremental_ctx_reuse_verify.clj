(ns incremental-ctx-reuse-verify
  "INC-1 HERMETIC verification (no LLM, no network) of make-ctx's {:reuse …}
   wiring — this slice's live QA:

     (a) fresh sqlite ctx → append 3 :ontology/create-concept via the REAL
         command path for a fixed oid → they project (count + URIs)
     (b) STOP everything (manual preserve-stop — files stay on disk)
     (c) reopen via make-ctx {:reuse {:dir :db-file :tenant-id}} → the SAME
         concepts project (count + URI spot-check), the tenant matches, and a
         NEW concept lands in the SAME store (the write path works on reuse)
     (d) the central evolver's DETERMINISTIC greenfield-vs-maintain condition
         (dt/greenfield-vs-maintain-branch-stub — graph-existence off the same
         projection) selects :maintain on the REUSED populated store — the
         exact branch incremental runs 2-5 depend on, witnessed without an LLM

   Cleans up its own temp store at the end (throwaway — uses h/stop-ctx's
   deleting stop ONLY here, never in the real runner).

   USAGE: clj -M:dev -m incremental-ctx-reuse-verify"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.time.interface :as time]))

(def oid #uuid "1c000000-0000-4000-8000-000000000001")

(defn- create-concept! [ctx uri label]
  (let [r (cp/process-command
           (assoc ctx :command
                  {:command/name :ontology/create-concept
                   :command/id (random-uuid)
                   :command/timestamp (time/now)
                   :ontology-id oid
                   :uri uri :label label
                   :description (str label " — INC-1 hermetic reuse verify")
                   :scope :custom :broader [] :indicators []}))]
    (if (:cognitect.anomalies/category r)
      (do (println "  !! create-concept" uri "ANOMALY:" r) false)
      (do (println "  create-concept" uri "→ ok") true))))

(defn- preserve-stop! [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (es/stop (:event-store ctx))
  (kv/stop (:cache ctx))
  (pubsub/stop (:event-pubsub ctx)))

(defn -main [& _]
  (println "=== INC-1 HERMETIC REUSE ROUNDTRIP (no LLM, no network) ===")
  (let [ctx1 (#'h/make-ctx {:store :sqlite})
        dir (::h/cache-dir ctx1)
        db-file (::h/db-file ctx1)
        tenant (:tenant-id ctx1)]
    (println "[phase 1 — FRESH ctx]")
    (println "  dir     :" dir)
    (println "  db-file :" db-file)
    (println "  tenant  :" tenant)
    (let [writes-ok? (every? true?
                             (doall (for [[u l] [["verify/alpha" "Alpha"]
                                                 ["verify/beta"  "Beta"]
                                                 ["verify/gamma" "Gamma"]]]
                                      (create-concept! ctx1 u l))))
          _ (Thread/sleep 300)
          cs1 (rm/get-concepts ctx1 {:ontology-id oid})
          phase1-ok? (and writes-ok? (= 3 (count cs1)))]
      (println "  concepts projected:" (count cs1) "(expect 3)" (sort (map :uri cs1)))
      (preserve-stop! ctx1)
      (println "[phase 2 — STOPPED processors/store/cache/pubsub; files preserved]")
      (println "  db-file exists on disk:" (.exists (java.io.File. ^String db-file)))
      (let [ctx2 (#'h/make-ctx {:reuse {:dir dir :db-file db-file :tenant-id tenant}})
            tenant-ok? (= tenant (:tenant-id ctx2))
            cs2 (rm/get-concepts ctx2 {:ontology-id oid})
            uris (set (map :uri cs2))
            spot-ok? (contains? uris "verify/beta")
            count-ok? (= 3 (count cs2))]
        (println "[phase 3 — REOPENED via {:reuse …}]")
        (println "  tenant matches       :" tenant-ok? "(" (:tenant-id ctx2) ")")
        (println "  same concepts project:" (count cs2) "(expect 3) →" (if count-ok? "PASS" "FAIL"))
        (println "  URI spot-check verify/beta present:" spot-ok?)
        (let [write-ok? (create-concept! ctx2 "verify/delta" "Delta")
              _ (Thread/sleep 300)
              cs3 (rm/get-concepts ctx2 {:ontology-id oid})
              grow-ok? (= 4 (count cs3))]
          (println "  NEW concept lands on the REUSED store:" (count cs3) "(expect 4) →"
                   (if grow-ok? "PASS" "FAIL") (sort (map :uri cs3)))
          ;; (d) the DETERMINISTIC branch condition the incremental runs 2-5
          ;; depend on: on this reused POPULATED store it must select :maintain.
          (let [branch (dt/greenfield-vs-maintain-branch-stub ctx2 {:ontology-id oid})
                maintain-ok? (= :maintain (:selected branch))
                pass? (and phase1-ok? tenant-ok? count-ok? spot-ok? write-ok? grow-ok?
                           maintain-ok?)]
            (println "  greenfield-vs-maintain on the REUSED populated store:"
                     (:selected branch) "(expect :maintain) →" (if maintain-ok? "PASS" "FAIL")
                     " reason:" (:reason branch))
            ;; throwaway cleanup — delete the temp store (stop-ctx's deleting stop).
            (#'h/stop-ctx ctx2)
            (println (if pass?
                       "=== HERMETIC REUSE ROUNDTRIP: ALL PASS ==="
                       "=== HERMETIC REUSE ROUNDTRIP: FAIL ==="))
            (shutdown-agents)
            (System/exit (if pass? 0 1))))))))
