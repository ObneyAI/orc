(ns incremental-graph-b
  "INC-1 — the INCREMENTAL Graph-B accretion runner: ONE source per invocation
   into the SAME persistent store (the system's intended accretion mode).

   Manifest-driven: `/tmp/orc-graph-b-incremental.manifest.edn` holds
   `{:oid :tenant :db-file :cache-dir :completed-sources [<source-name> …]}`.
     - manifest ABSENT  → first run: fresh make-ctx (sqlite), random oid,
       manifest written BEFORE the run (crash-safety: the store coordinates are
       recorded even if the run dies mid-way).
     - manifest PRESENT → `make-ctx {:reuse …}` against the recorded db-file /
       cache-dir / tenant, and the SAME oid — so the central evolver's
       greenfield-vs-maintain condition sees the existing graph and runs 2-5
       MUST take the `:maintain` branch. If the evolver selects `:greenfield`
       on a populated store, that is a FINDING (printed loudly), never papered
       over.

   Each invocation runs the NEXT `(h/sources)` entry not yet in
   `:completed-sources` (by :name). `:completed-sources` advances ONLY when the
   source's reconcile status is `:success` — a `:partial-reconcile` run leaves
   it for the next invocation to retry. All five completed → prints
   \"ACCRETION COMPLETE\" + full graph-stats and exits 0 without running.

   The store is PRESERVED on EVERY exit path (manual stop mirroring
   full_graph_b_all_sources — never stop-ctx, which deletes the files).

   USAGE:
     clj -J-Xmx10g -M:dev:test -m incremental-graph-b --dry-run   # no LLM
     OPENROUTER_API_KEY=… nohup clj -J-Xmx10g -M:dev:test \\
       -m incremental-graph-b > /tmp/incremental-graph-b-runN.log 2>&1 &"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]))

(def manifest-path "/tmp/orc-graph-b-incremental.manifest.edn")

;; =============================================================================
;; PURE manifest logic (tested in incremental_graph_b_test.clj)
;; =============================================================================

(defn next-source
  "PURE — the next source to run: the FIRST entry of `sources` whose `:name` is
   not in `completed` (a coll of source-name keywords; order-insensitive).
   nil when every source is completed (the ACCRETION COMPLETE signal)."
  [sources completed]
  (let [done (set completed)]
    (first (remove #(done (:name %)) sources))))

(defn advance-completed
  "PURE — the new :completed-sources vector: appends `source-name` ONLY when
   `reconcile-status` is `:success`. Any other status (:failed / :timeout /
   :not-run / nil — i.e. a :partial-reconcile run) leaves it UNCHANGED so the
   next invocation retries the same source."
  [completed source-name reconcile-status]
  (if (= :success reconcile-status)
    (conj (vec completed) source-name)
    (vec completed)))

(defn reconcile-status-for
  "PURE — read THIS run's reconcile status for the source at `source-path` from
   the central evolver result's `:source-reports` (MS-2 shape:
   `{:source {:type :path} :reconcile {:status <kw> :landed <N|nil>} …}`).
   `:not-run` when the report is absent (a run that died before reconcile) —
   never a fabricated `:success`."
  [source-reports source-path]
  (or (some (fn [r]
              (when (= source-path (get-in r [:source :path]))
                (get-in r [:reconcile :status])))
            source-reports)
      :not-run))

;; =============================================================================
;; Manifest IO + store lifecycle
;; =============================================================================

(defn- read-manifest []
  (when (.exists (io/file manifest-path))
    (edn/read-string (slurp manifest-path))))

(defn- write-manifest! [m]
  (spit manifest-path (pr-str m)))

(defn- preserve-stop!
  "Stop processors/store/cache/pubsub WITHOUT deleting anything — the
   full_graph_b_all_sources manual-stop pattern (never h/stop-ctx, which
   deletes the db + cache dir)."
  [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (es/stop (:event-store ctx))
  (kv/stop (:cache ctx))
  (pubsub/stop (:event-pubsub ctx)))

(defn- graph-counts
  "Concept/relationship counts for the oid (the DELTA read — taken BEFORE and
   AFTER the run). Mirrors eb12's snapshot filtering."
  [ctx oid]
  {:concepts (count (rm/get-concepts ctx {:ontology-id oid}))
   :relationships (count (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx)))})

;; =============================================================================
;; -main
;; =============================================================================

(defn -main [& args]
  ;; SLOW-PROVIDER TUNING (2026-07-20): the per-container extract budget is a
  ;; documented-tunable knob ("Named + overridable"). The 2026-07-16 onet
  ;; attempts ground steadily for 5h (4-87 node completions per 10-min bucket,
  ;; ZERO stalls) but averaged ~70s per LLM-backed node vs the ~6-22s the 60s
  ;; calibration assumed — pure provider latency — so every 26-min delegate
  ;; attempt timed out mid-honest-work. 120s/container (52-min ceiling at cap
  ;; 25) absorbs a slow-provider evening; the ceiling still bounds a genuine
  ;; hang. Runner-level override — the component default stays 60s.
  (alter-var-root #'ce/default-per-container-budget-ms (constantly 120000))
  (println "per-container extract budget (runner override): 120s → ceiling"
           (ce/model-extract-timeout-ms {}) "ms")
  (let [dry-run? (boolean (some #{"--dry-run"} args))
        manifest (read-manifest)
        src (next-source (h/sources) (:completed-sources manifest))]
    (if (and manifest (nil? src))
      ;; ------------------------------------------------------- all five done
      (let [ctx (#'h/make-ctx {:reuse {:dir (:cache-dir manifest)
                                       :db-file (:db-file manifest)
                                       :tenant-id (:tenant manifest)}})
            oid (:oid manifest)]
        (println "=== ACCRETION COMPLETE — all" (count (h/sources)) "sources built into one graph ===")
        (println "oid    :" oid)
        (println "tenant :" (:tenant manifest))
        (println "db-file:" (:db-file manifest))
        (println "completed-sources:" (:completed-sources manifest))
        (println "\n>>> full graph-stats")
        (pp/pprint (try (h/graph-stats ctx oid) (catch Throwable t {:error (.getMessage t)})))
        (preserve-stop! ctx)
        (println "\nStore PRESERVED. Nothing ran.")
        (shutdown-agents)
        (System/exit 0))

      ;; --------------------------------------------------- run ONE source
      (let [fresh? (nil? manifest)
            run-n (inc (count (:completed-sources manifest)))
            _ (when-not dry-run?
                (when-not (System/getenv "OPENROUTER_API_KEY")
                  (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
                (h/register-openrouter! h/default-model))
            ctx (if fresh?
                  (#'h/make-ctx {:store :sqlite})
                  (#'h/make-ctx {:reuse {:dir (:cache-dir manifest)
                                         :db-file (:db-file manifest)
                                         :tenant-id (:tenant manifest)}}))
            oid (if fresh? (random-uuid) (:oid manifest))
            db-file (::h/db-file ctx)
            cache-dir (::h/cache-dir ctx)
            tenant (:tenant-id ctx)
            manifest (or manifest {:oid oid :tenant tenant :db-file db-file
                                   :cache-dir cache-dir :completed-sources []})
            ;; crash-safety: on a FRESH run, record the store coordinates BEFORE
            ;; anything runs — a mid-run crash must not orphan the store.
            _ (when fresh? (write-manifest! manifest))
            before (graph-counts ctx oid)
            expected-branch (if (pos? (:concepts before)) :maintain :greenfield)
            budget {:max-iterations 40 :total-budget-ms 1800000 :max-retries 3}]
        (println "=== INCREMENTAL GRAPH-B — run" run-n "of" (count (h/sources))
                 (if fresh? "(FRESH store)" "(REUSED store)") "===")
        (println "source :" (:name src) " (" (:type src) (:path src) ")")
        (println "oid    :" oid)
        (println "tenant :" tenant)
        (println "db-file:" db-file)
        (println "cache  :" cache-dir)
        (println "completed-sources so far:" (:completed-sources manifest))
        (println "graph BEFORE:" before)
        (println "expected greenfield-vs-maintain branch:" expected-branch)
        (if dry-run?
          (do (println "\n--dry-run: constructed ctx + manifest + before-counts;"
                       "NOT invoking run-central-evolver!. Store PRESERVED.")
              (preserve-stop! ctx)
              (shutdown-agents)
              (System/exit 0))
          (try
            (let [t0 (System/currentTimeMillis)
                  result (ce/run-central-evolver!
                          ctx {:ontology-id oid
                               :sources [(select-keys src [:type :path])]
                               :goal h/domain-goal
                               :model h/default-model :judge-fn h/real-llm-judge
                               :resilient? true :budget budget
                               :evolver-config {:max-iterations 3}
                               :max-containers nil :max-windows nil})
                  elapsed (- (System/currentTimeMillis) t0)
                  after (graph-counts ctx oid)
                  branch (get-in result [:branch-points :greenfield-vs-maintain])
                  selected (:selected branch)
                  rstatus (reconcile-status-for (:source-reports result) (:path src))
                  completed' (advance-completed (:completed-sources manifest) (:name src) rstatus)]
              (println "\n=== CENTRAL EVOLVER DONE ===")
              (println "status:" (:status result) " mode:" (:mode result)
                       " termination:" (get-in result [:cq-loop :termination-reason])
                       " elapsed:" elapsed "ms")
              ;; MS-2 — per-source outcome (extracted vs landed) at a glance.
              (println "\n>>> source-reports")
              (pp/pprint (:source-reports result))
              (when-not (= :complete (:status result))
                (println "!! NON-COMPLETE — diagnostics:")
                (pp/pprint (select-keys result [:status :mode :failed-at :error :failed-step
                                                :failed-source :branch-points :cq-loop-status])))
              ;; THE BRANCH POINT — printed loudly; runs 2-5 MUST be :maintain.
              (println "\n>>> BRANCH-POINT greenfield-vs-maintain")
              (pp/pprint branch)
              (println "    selected:" selected "  expected:" expected-branch)
              (when (not= selected expected-branch)
                (println (str "!!! FINDING: evolver selected " selected " but " expected-branch
                              " was expected (graph BEFORE had " (:concepts before)
                              " concepts). DO NOT paper over — report this.")))
              (println "\n>>> graph DELTA (before → after)")
              (println "  concepts     :" (:concepts before) "→" (:concepts after)
                       " (+" (- (:concepts after) (:concepts before)) ")")
              (println "  relationships:" (:relationships before) "→" (:relationships after)
                       " (+" (- (:relationships after) (:relationships before)) ")")
              (println "\n>>> graph-stats (cross-source links!)")
              (pp/pprint (try (h/graph-stats ctx oid) (catch Throwable t {:error (.getMessage t)})))
              (println "\n>>> cq-loop / verdict")
              (pp/pprint (select-keys result [:cq-verdict :graph-health]))
              ;; MANIFEST ADVANCE — only a reconcile-:success run marks the
              ;; source done; anything else leaves it for the next run to retry.
              (println "\n>>> manifest advance")
              (println "  reconcile status for" (:name src) ":" rstatus)
              (if (= completed' (:completed-sources manifest))
                (println "  NOT advanced —" (:name src) "will be RETRIED next invocation")
                (println "  advanced:" (:completed-sources manifest) "→" completed'))
              (write-manifest! (assoc manifest :completed-sources completed'))
              (preserve-stop! ctx)
              (println "\n=== RUN" run-n "DONE — store + cache PRESERVED ===")
              (println "MANIFEST:" manifest-path)
              (shutdown-agents)
              (System/exit 0))
            (catch Throwable t
              ;; store PRESERVED on the failure path too — manifest already on
              ;; disk (fresh runs write it before the run; reuse runs never
              ;; touch :completed-sources until success).
              (println "\n!! RUN FAILED:" (.getMessage t))
              (.printStackTrace t)
              (try (preserve-stop! ctx) (catch Throwable _))
              (println "Store PRESERVED at" db-file "— manifest unchanged; rerun retries" (:name src))
              (shutdown-agents)
              (System/exit 1))))))))
