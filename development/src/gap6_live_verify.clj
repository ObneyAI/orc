(ns gap6-live-verify
  "Gap-6 LIVE verify — confirm the anti-recency validator doesn't
   false-positive reject legitimate consolidations.

   Re-runs the same legal-issue-detection bench shape as
   `gap3_loop_live_verify`. The critical assertion is NOT that the
   validator fires — it's that it DOES NOT fire during healthy
   evolution. A false-positive rejection would block the loop
   entirely, defeating the whole point of having safeguards.

   Pass criteria:
     - Both cycles complete; body version reaches 3
     - `:ontology/anti-recency-rejection` events count: 0
     - `:ontology/anti-recency-clamp-applied` events: 0 expected on
       healthy evolution (the LLM keeps protected entries' confidence
       stable when evidence sustains them). >0 is not a failure — the
       point is that legitimate consolidations aren't BLOCKED — but
       worth investigating if it happens, since it suggests the LLM
       is dropping confidence aggressively under the new prompt.

   Adversarial framing: if this run produces ANY rejection events,
   Gap-6 has a false-positive bug that needs fixing before merge.
   The validator's defaults (confidence 0.7, evidence-count 5,
   max-decrease 0.2) were spec-chosen to err on the side of
   non-intervention — observed C-Loop-1 evolutions stayed well
   inside those bounds."
  (:require [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]
            [runner]
            [legal-issue-detection :as task]))

(def tree-class-id
  "Stable seed UUID for legal-issue-detection."
  (java.util.UUID/fromString "153f1c69-e1d8-3592-8e62-391a7fab2dac"))

(defn- snapshot-body [ctx label]
  (let [body (ontology/get-description ctx :tree-class tree-class-id)]
    {:label label
     :version (:version body)
     :strengths-count (count (:strengths body))
     :weaknesses-count (count (:weaknesses body))
     :strengths-traits (mapv :trait (:strengths body))
     :weaknesses-traits (mapv :trait (:weaknesses body))}))

(defn- count-events [ctx t]
  (count (into [] (es/read (:event-store ctx) {:types #{t} :tenant-id (:tenant-id ctx)}))))

(defn- force-consolidation! [ctx wait-ms]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/request-consolidation
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :target-type :tree-class
            :target-id tree-class-id
            :on-demand? true}))
  (Thread/sleep wait-ms))

(defn verify!
  [{:keys [runs-per-cycle consolidation-wait-ms]
    :or {runs-per-cycle 2 consolidation-wait-ms 60000}}]
  (println "============================================================")
  (println " Gap-6 LIVE Verify — anti-recency validator false-positive check")
  (println "============================================================")
  (u/start-publisher! {:type :console})
  (runner/start!)
  (let [ctx (deref @(requiring-resolve 'runner/system-state))]
    (try
      ;; Living Description on (turns on Gap-1+Gap-3+Gap-5 judges)
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-living-description-enabled
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :enabled? true}))
      (Thread/sleep 200)
      (let [bootstrap (snapshot-body ctx "Bootstrap (seed)")]
        (println "\nBootstrap body:")
        (println "  version:" (:version bootstrap))
        (println "  strengths:" (:strengths-count bootstrap)
                 "weaknesses:" (:weaknesses-count bootstrap))

        ;; Cycle 1
        (println "\n--- Cycle-1 runs ---")
        (dotimes [i runs-per-cycle]
          (println "  run" (inc i))
          (runner/run! task/task))
        (Thread/sleep 1000)

        (println "\n--- Force Cycle-1 consolidation ---")
        (force-consolidation! ctx consolidation-wait-ms)
        (let [c1 (snapshot-body ctx "After Cycle-1 consolidation")]
          (println "  version:" (:version c1)
                   "strengths:" (:strengths-count c1)
                   "weaknesses:" (:weaknesses-count c1)))

        ;; Cycle 2
        (println "\n--- Cycle-2 runs ---")
        (dotimes [i runs-per-cycle]
          (println "  run" (inc i))
          (runner/run! task/task))
        (Thread/sleep 1000)

        (println "\n--- Force Cycle-2 consolidation ---")
        (force-consolidation! ctx consolidation-wait-ms)

        (let [final-snap (snapshot-body ctx "After Cycle-2 consolidation")
              rejections (count-events ctx :ontology/anti-recency-rejection)
              clamps (count-events ctx :ontology/anti-recency-clamp-applied)
              updates (count-events ctx :ontology/tree-description-updated)
              false-positive? (pos? rejections)
              pass? (and (not false-positive?)
                         (= 3 (:version final-snap)))]
          (println "\n============================================================")
          (println " RESULTS")
          (println "============================================================")
          (println "  Final body version:" (:version final-snap)
                   "(expect 3: bootstrap=1 + 2 consolidations)")
          (println "  Final strengths:" (:strengths-count final-snap))
          (println "  Final weaknesses:" (:weaknesses-count final-snap))
          (println "  Final strengths traits:")
          (doseq [t (:strengths-traits final-snap)] (println "    -" t))
          (println "  Final weaknesses traits:")
          (doseq [t (:weaknesses-traits final-snap)] (println "    -" t))
          (println)
          (println "  :ontology/tree-description-updated events:" updates)
          (println "  :ontology/anti-recency-rejection events:" rejections
                   (if (pos? rejections) "  ⚠ FALSE POSITIVE — Gap-6 BLOCKED a legitimate consolidation" "  ✓ no false-positive rejections"))
          (println "  :ontology/anti-recency-clamp-applied events:" clamps
                   (if (zero? clamps) "  ✓ no clamps needed" "  ℹ clamps fired — investigate whether the LLM is dropping confidences aggressively"))
          (println)
          (println "  PASS:" pass?)
          (when false-positive?
            (println "\n  ⚠⚠⚠ REJECTION DETAILS:")
            (doseq [e (into [] (es/read (:event-store ctx)
                                        {:types #{:ontology/anti-recency-rejection}
                                         :tenant-id (:tenant-id ctx)}))]
              (println "    -" (:entry-trait e) "prior-conf:" (:prior-confidence e)
                       "evidence:" (:prior-evidence-count e))))
          (when (pos? clamps)
            (println "\n  CLAMP DETAILS:")
            (doseq [e (into [] (es/read (:event-store ctx)
                                        {:types #{:ontology/anti-recency-clamp-applied}
                                         :tenant-id (:tenant-id ctx)}))]
              (println "    -" (:entry-trait e)
                       "prior:" (:prior-confidence e)
                       "llm:" (:llm-confidence e)
                       "clamped:" (:clamped-confidence e))))
          {:pass? pass?
           :version (:version final-snap)
           :rejections rejections
           :clamps clamps
           :updates updates}))
      (finally
        (runner/stop!)))))

(comment
  (require '[gap6-live-verify :as v])
  (v/verify! {:runs-per-cycle 2}))
