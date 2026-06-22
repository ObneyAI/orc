(ns gap3-loop-live-verify
  "Gap-3 LIVE verify — apples-to-apples vs C-Loop-1's prior LIVE run on
   the SAME task (legal-issue-detection), SAME cycle count, SAME model.
   The ONLY meaningful differences from `c_loop_1_live_verify.clj`:
     - Living Description opt-in is enabled at start (turns on Gap-1
       per-event evaluator runtime).
     - Gap-5's default-judges resolver auto-attaches 5 judges to the
       repl-researcher node — no consumer code change required.
     - Gap-3's consolidator enhancements (judge-scores joined into
       :recent-events, judge-averages in :aggregate-metrics, judge-
       weighting clause in reflection-instruction) shape the LLM input.

   Success criteria for HONEST comparison vs C-Loop-1's 2026-06-02
   report:
     1. The full multi-cycle loop closes (consolidator emits at each
        cycle boundary; body version climbs).
     2. The body's strengths/weaknesses are at least as specific +
        actionable as C-Loop-1's were (we capture the verbatim body
        so HITL can audit side-by-side).
     3. New judge-driven content shows up — at minimum, the body
        should reflect any low judge scores seen during runs.
     4. Judge-score events actually emit during the run cycles. If
        :judge/score-emitted count stays 0, the test is invalid.

   This script reuses the runner.clj bench harness exactly as
   C-Loop-1 did, so any difference in output IS attributable to Gap-1
   + Gap-3 + Gap-5 — not setup drift."
  (:require [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.orc.orc-service.core.todo-processors :as orc-tp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as str]
            [ai.obney.orc.ontology.test-support.seed-principles :as principles]
            [runner]
            [legal-issue-detection :as task]))

(def report-dir
  "development/bench/gap3_loop_live_verify")

(def tree-class-id
  principles/legal-issue-detection-task-class-id)

(defn snapshot-body
  [ctx target-id label]
  (let [body (ontology/get-description ctx :tree-class target-id)]
    {:label label
     :captured-at (str (java.time.Instant/now))
     :body body
     :version (:version body)
     :consolidated-from-event-count (:consolidated-from-event-count body)
     :strength-count (count (:strengths body))
     :weakness-count (count (:weaknesses body))
     :summary (:summary body)}))

(defn count-events
  [ctx event-type]
  (count (into [] (es/read (:event-store ctx)
                           {:types #{event-type}
                            :tenant-id (:tenant-id ctx)}))))

(defn force-consolidation!
  [ctx target-id wait-ms]
  (println "  Pre-request event counts:")
  (println "    :ontology/task-classified         "
           (count-events ctx :ontology/task-classified))
  (println "    :judge/score-emitted              "
           (count-events ctx :judge/score-emitted))
  (println "    :ontology/consolidation-requested "
           (count-events ctx :ontology/consolidation-requested))
  (println "    :ontology/tree-description-updated"
           (count-events ctx :ontology/tree-description-updated))
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/request-consolidation
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :target-type :tree-class
            :target-id target-id
            :on-demand? true}))
  (println "  Issued :ontology/request-consolidation; waiting"
           (/ wait-ms 1000.0) "s...")
  (Thread/sleep wait-ms)
  (println "  Post-wait event counts:")
  (println "    :judge/score-emitted              "
           (count-events ctx :judge/score-emitted))
  (println "    :ontology/consolidation-requested "
           (count-events ctx :ontology/consolidation-requested))
  (println "    :ontology/tree-description-updated"
           (count-events ctx :ontology/tree-description-updated)))

(defn principle-key [entry] (:trait entry))

(defn diff-principles
  [old-entries new-entries]
  (let [old-keys (set (map principle-key old-entries))
        new-keys (set (map principle-key new-entries))]
    {:added (vec (set/difference new-keys old-keys))
     :removed (vec (set/difference old-keys new-keys))
     :preserved (vec (set/intersection old-keys new-keys))}))

(defn diff-bodies
  [from-body to-body]
  {:version-bump [(or (:version from-body) 0) (or (:version to-body) 0)]
   :event-count-bump [(or (:consolidated-from-event-count from-body) 0)
                      (or (:consolidated-from-event-count to-body) 0)]
   :summary-changed? (not= (:summary from-body) (:summary to-body))
   :strengths-diff (diff-principles (:strengths from-body) (:strengths to-body))
   :weaknesses-diff (diff-principles (:weaknesses from-body) (:weaknesses to-body))})

(defn capture-run-artifacts
  [run-record cycle-label run-idx]
  {:cycle cycle-label
   :run-idx run-idx
   :status (:status run-record)
   :duration-ms (:duration-ms run-record)
   :total-tokens (get-in run-record [:usage :total-tokens])
   :node-trace-tokens (get-in run-record [:node-trace-usage-total :total-tokens])
   :prepend (get-in run-record [:r-inject-trace :prepend])
   :iteration-reasonings (:iteration-reasonings run-record)
   :generated-tree-raw (:generated-tree-raw run-record)})

(defn capture-judge-snapshot
  "After each cycle, capture the verbatim judge scores that fired."
  [ctx label]
  (let [judges (into [] (es/read (:event-store ctx)
                                 {:types #{:judge/score-emitted}
                                  :tenant-id (:tenant-id ctx)}))
        by-judge (group-by :judge-name judges)]
    {:label label
     :total (count judges)
     :by-judge (into {}
                     (map (fn [[jname entries]]
                            [jname {:count (count entries)
                                    :scores (mapv :score entries)
                                    :mean (when (seq entries)
                                            (/ (reduce + 0.0 (map :score entries))
                                               (double (count entries))))
                                    :feedbacks (mapv :feedback entries)}]))
                     by-judge)}))

(defn render-body-section
  [snapshot]
  (str "### " (:label snapshot) " — " (:captured-at snapshot) "\n\n"
       "- **Version:** " (:version snapshot) "\n"
       "- **Consolidated-from-event-count:** " (:consolidated-from-event-count snapshot) "\n"
       "- **Strengths:** " (:strength-count snapshot) "\n"
       "- **Weaknesses:** " (:weakness-count snapshot) "\n"
       "- **Summary:** " (:summary snapshot) "\n\n"
       "<details><summary>Full body (verbatim)</summary>\n\n```clojure\n"
       (with-out-str (pprint/pprint (:body snapshot)))
       "```\n\n</details>\n\n"))

(defn render-judge-snapshot
  [snap]
  (str "### " (:label snap) "\n\n"
       "- **Total `:judge/score-emitted` events:** " (:total snap) "\n\n"
       (str/join
         (map (fn [[jname stats]]
                (str "**" jname "** (n=" (:count stats)
                     ", mean=" (format "%.2f" (or (:mean stats) 0.0)) ")\n"
                     "- Scores: " (pr-str (:scores stats)) "\n"
                     "<details><summary>Per-judge feedback (verbatim)</summary>\n\n"
                     (str/join "\n\n---\n\n" (:feedbacks stats))
                     "\n</details>\n\n"))
              (:by-judge snap)))))

(defn render-run-section
  [art]
  (str "### " (:cycle art) " — Run #" (:run-idx art) "\n\n"
       "- **Status:** " (:status art) "\n"
       "- **Duration:** " (format "%.1f" (/ (or (:duration-ms art) 0) 1000.0)) "s\n"
       "- **Tokens (total):** " (:total-tokens art) "\n"
       "- **Tokens (node-trace sum):** " (:node-trace-tokens art) "\n\n"
       "<details><summary>Generated tree (verbatim)</summary>\n\n```clojure\n"
       (with-out-str (pprint/pprint (:generated-tree-raw art)))
       "```\n\n</details>\n\n"))

(defn render-diff-section
  [label diff]
  (str "### " label "\n\n"
       "- **Version:** " (first (:version-bump diff)) " → " (second (:version-bump diff)) "\n"
       "- **Event-count:** " (first (:event-count-bump diff)) " → " (second (:event-count-bump diff)) "\n"
       "- **Summary changed?:** " (:summary-changed? diff) "\n\n"
       "**Strengths:**\n"
       "- Added (" (count (-> diff :strengths-diff :added)) "): "
       (str/join "; " (-> diff :strengths-diff :added)) "\n"
       "- Removed (" (count (-> diff :strengths-diff :removed)) "): "
       (str/join "; " (-> diff :strengths-diff :removed)) "\n"
       "- Preserved (" (count (-> diff :strengths-diff :preserved)) "): "
       (str/join "; " (-> diff :strengths-diff :preserved)) "\n\n"
       "**Weaknesses:**\n"
       "- Added (" (count (-> diff :weaknesses-diff :added)) "): "
       (str/join "; " (-> diff :weaknesses-diff :added)) "\n"
       "- Removed (" (count (-> diff :weaknesses-diff :removed)) "): "
       (str/join "; " (-> diff :weaknesses-diff :removed)) "\n"
       "- Preserved (" (count (-> diff :weaknesses-diff :preserved)) "): "
       (str/join "; " (-> diff :weaknesses-diff :preserved)) "\n\n"))

(defn render-report
  [{:keys [snapshots cycle-1-runs cycle-2-runs diffs judge-snapshots]}]
  (str "# Gap-3 Loop Live Verify Report\n\n"
       "Generated: " (str (java.time.Instant/now)) "\n\n"
       "Task: legal-issue-detection. Tree-class id: " tree-class-id "\n\n"
       "## Living Description loop with judges (Gap-1 + Gap-3 + Gap-5)\n\n"
       "This run mirrors C-Loop-1's verify exactly EXCEPT for:\n"
       "- :ontology/set-living-description-enabled is true at start\n"
       "- Gap-5's resolver auto-attaches 5 default judges to repl-researcher\n"
       "- Gap-3's consolidator enhancements feed judge signal into the LLM\n\n"
       "Compare body specificity + judge-driven content against the\n"
       "C-Loop-1 reference report at\n"
       "`development/bench/c_loop_1_live_verify/report_2026-06-02_193925.md`.\n\n"
       "## Judge-event snapshots (per cycle)\n\n"
       (str/join (map render-judge-snapshot judge-snapshots))
       "## Body snapshots\n\n"
       (str/join (map render-body-section snapshots))
       "## Cycle 1 runs\n\n"
       (str/join (map render-run-section cycle-1-runs))
       "## Cycle 1 → Cycle 2 diff\n\n"
       (render-diff-section "Bootstrap → After Cycle-1 consolidation" (:bootstrap->c1 diffs))
       "## Cycle 2 runs\n\n"
       (str/join (map render-run-section cycle-2-runs))
       "## Cycle 2 → Cycle 3 diff\n\n"
       (render-diff-section "After Cycle-1 → After Cycle-2 consolidation" (:c1->c2 diffs))
       "## Bootstrap → final composite diff\n\n"
       (render-diff-section "Bootstrap → After Cycle-2 consolidation (cumulative)" (:bootstrap->c2 diffs))
       "## HITL audit checklist\n\n"
       "- [ ] Judges actually fired in both cycles (snapshot counts > 0)\n"
       "- [ ] Final body's strengths/weaknesses are AT LEAST as specific as C-Loop-1's 2026-06-02 cycle-2 body\n"
       "- [ ] New judge-driven content present (e.g. low-grounding entries, instruction-following weaknesses)\n"
       "- [ ] No regression — every C-Loop-1 cycle-2 strength/weakness either preserved or strictly improved\n"
       "- [ ] Body version bumps version 1 → 2 → 3 across the cycles\n"))

(defn run-cycle!
  [_ctx cycle-label run-count]
  (println (str "\n--- " cycle-label " — " run-count " runs ---"))
  (mapv (fn [idx]
          (println (str "\n[" cycle-label " run " idx "/" run-count "]"))
          (let [result (runner/run! task/task)]
            (capture-run-artifacts result cycle-label idx)))
        (range 1 (inc run-count))))

(defn verify!
  [{:keys [runs-per-cycle consolidation-wait-ms]
    :or {runs-per-cycle 3 consolidation-wait-ms 60000}}]
  (println "===========================================================")
  (println "  Gap-3 LOOP LIVE VERIFY — judges feeding consolidator")
  (println "  (Apples-to-apples vs C-Loop-1's 2026-06-02 verify)")
  (println "===========================================================")
  (u/start-publisher! {:type :console})
  (runner/start!)
  (let [ctx (deref @(requiring-resolve 'runner/system-state))
        ;; Gap-3: turn on Living Description loop — this is what
        ;; unlocks Gap-1's per-event judge runtime + Gap-5's default
        ;; judge attachment on repl-researcher nodes.
        _ (do (println "\n--- Enabling Living Description (Gap-1+Gap-5 activation) ---")
              (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/set-living-description-enabled
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :enabled? true}))
              (Thread/sleep 200)
              (println "  living-description-enabled? ="
                       (ontology/get-living-description-enabled? ctx)))

        bootstrap-snap (snapshot-body ctx tree-class-id "Bootstrap (seed)")
        judge-snap-0 (capture-judge-snapshot ctx "Pre-Cycle-1 (before any runs)")
        _ (println "\nBootstrap body captured:")
        _ (println "  version:" (:version bootstrap-snap))
        _ (println "  strengths:" (:strength-count bootstrap-snap))
        _ (println "  weaknesses:" (:weakness-count bootstrap-snap))

        cycle-1-runs (run-cycle! ctx "Cycle-1 (pre-consolidation)" runs-per-cycle)
        judge-snap-1 (capture-judge-snapshot ctx "After Cycle-1 runs")
        _ (println "\n  judges fired during Cycle-1:" (:total judge-snap-1))
        _ (doseq [[jname stats] (:by-judge judge-snap-1)]
            (println "    -" jname "n=" (:count stats)
                     "mean=" (format "%.2f" (or (:mean stats) 0.0))))

        _ (println "\n--- Forcing Cycle-1 consolidation (real LLM call) ---")
        _ (force-consolidation! ctx tree-class-id consolidation-wait-ms)
        cycle-1-snap (snapshot-body ctx tree-class-id "After Cycle-1 consolidation")
        _ (println "  version:" (:version cycle-1-snap))
        _ (println "  strengths:" (:strength-count cycle-1-snap))
        _ (println "  weaknesses:" (:weakness-count cycle-1-snap))

        cycle-2-runs (run-cycle! ctx "Cycle-2 (post-Cycle-1)" runs-per-cycle)
        judge-snap-2 (capture-judge-snapshot ctx "After Cycle-2 runs")
        _ (println "\n  judges fired through Cycle-2:" (:total judge-snap-2))

        _ (println "\n--- Forcing Cycle-2 consolidation (real LLM call) ---")
        _ (force-consolidation! ctx tree-class-id consolidation-wait-ms)
        cycle-2-snap (snapshot-body ctx tree-class-id "After Cycle-2 consolidation")
        _ (println "  version:" (:version cycle-2-snap))
        _ (println "  strengths:" (:strength-count cycle-2-snap))
        _ (println "  weaknesses:" (:weakness-count cycle-2-snap))

        diffs {:bootstrap->c1 (diff-bodies (:body bootstrap-snap) (:body cycle-1-snap))
               :c1->c2 (diff-bodies (:body cycle-1-snap) (:body cycle-2-snap))
               :bootstrap->c2 (diff-bodies (:body bootstrap-snap) (:body cycle-2-snap))}
        report-input {:snapshots [bootstrap-snap cycle-1-snap cycle-2-snap]
                      :cycle-1-runs cycle-1-runs
                      :cycle-2-runs cycle-2-runs
                      :diffs diffs
                      :judge-snapshots [judge-snap-0 judge-snap-1 judge-snap-2]}
        _ (io/make-parents (str report-dir "/dummy"))
        timestamp (.format (java.time.LocalDateTime/now)
                           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HHmmss"))
        report-path (str report-dir "/report_" timestamp ".md")
        raw-path (str report-dir "/raw_" timestamp ".edn")]
    (spit raw-path (pr-str report-input))
    (spit report-path (render-report report-input))
    (println "\n===========================================================")
    (println "  Report saved: " report-path)
    (println "  Raw data:     " raw-path)
    (println "===========================================================")
    (runner/stop!)
    {:report-path report-path
     :raw-path raw-path
     :diffs diffs
     :judges-cycle-1 (:total judge-snap-1)
     :judges-cycle-2 (:total judge-snap-2)
     :final-version (:version cycle-2-snap)}))

(comment
  (require '[gap3-loop-live-verify :as v])
  (v/verify! {:runs-per-cycle 3}))
