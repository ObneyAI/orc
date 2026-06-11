(ns c-loop-1-live-verify
  "C-Loop-1 live verification — exercises the Living Description loop
   end-to-end with a real LLM and a real bench task.

   Flow:
     - start! (seed corpus + build ColBERT index)
     - snapshot bootstrap body for legal-issue-detection tree-class
     - Cycle 1: run task 3 times → on-demand consolidation → snapshot updated body
     - Cycle 2: run task 3 more times → on-demand consolidation → snapshot updated body
     - Produce a markdown report comparing bodies, prepends, model reasoning,
       generated trees, token usage across the two cycles.

   Why this exists beyond the synthetic tests: synthetic tests stub the
   LLM. The live verify proves the consolidator's prompt produces a
   well-shaped, substantively-different, principle-shaped body when fed
   real evidence — not just a different body."
  (:require [ai.obney.orc.ontology.interface :as ontology]
            ;; Load the consolidator namespace so its defprocessor
            ;; registers into tp/processor-registry* BEFORE runner/start!
            ;; reads the registry to spin up processors. Without this
            ;; require, the consolidator never subscribes to
            ;; :ontology/consolidation-requested and the Living
            ;; Description loop is silent in live runs (the runner.clj
            ;; bench harness was authored before C-2a-3b's consolidator
            ;; landed and never re-included it).
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
            [seed-principles :as principles]
            [runner]
            [legal-issue-detection :as task]))

(def report-dir
  "development/bench/c_loop_1_live_verify")

(def tree-class-id
  "The seed UUID for legal-issue-detection. The classifier assigns
   tasks to this UUID; the consolidator updates the body under
   :tree-class scope keyed by this UUID."
  principles/legal-issue-detection-task-class-id)

;; =============================================================================
;; Body snapshot helpers
;; =============================================================================

(defn snapshot-body
  "Pull the current :tree-class body and the count of underlying source
   events that drive consolidation."
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
  "Diagnostic — how many events of the given type are in the store?"
  [ctx event-type]
  (count (into [] (es/read (:event-store ctx)
                           {:types #{event-type}
                            :tenant-id (:tenant-id ctx)}))))

(defn synthetic-prepend!
  "Closure-proof helper: construct the prepend the model WOULD see for
   a hypothetical R-Inject run right now, by feeding apply-r05-classifier-
   context a payload pointing at this tree-class. Bypasses the bench
   (which has provider-side hangs after the first run in the same JVM)
   so we can directly inspect what fetch-tree-body returns post-
   consolidation."
  [ctx target-id]
  (let [synth-node {:id (random-uuid)
                    :type :repl-researcher
                    :name "synthetic-prepend-probe"
                    :instruction "(synthetic — exercises the read path only)"
                    :context {:tree-id target-id
                              :r05-classifier
                              {:structural
                               {:assigned-tree-id target-id
                                :confidence 1.0
                                :was-fresh-mint? false
                                :reasoning "synthetic — proves fetch-tree-body returns the latest tree-class body"
                                :top-candidates
                                [{:content "synthetic candidate"
                                  :document-id (str "tree-fingerprint:" target-id)
                                  :document-metadata {:granularity :tree-fingerprint
                                                      :target-id target-id}
                                  :fitness-score 1.0
                                  :reasoning "synthetic — proves fetch-tree-body returns the latest tree-class body"
                                  :rerank-source :reranker}]
                                :rerank-fallback? false}
                               :behavioral {:behaviors [] :rerank-fallback? false}}}}
        result (orc-tp/apply-r05-classifier-context synth-node ctx)]
    (:instruction result)))

(defn force-consolidation!
  "Issue an on-demand :tree-class consolidation request and wait for
   the consolidator to complete its LLM reflection + emit the updated
   description event. Prints diagnostic event-store counts before
   and after so we can see whether the consolidator actually fired."
  [ctx target-id wait-ms]
  (println "  Pre-request event counts:")
  (println "    :ontology/task-classified         "
           (count-events ctx :ontology/task-classified))
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
  (println "    :ontology/consolidation-requested "
           (count-events ctx :ontology/consolidation-requested))
  (println "    :ontology/tree-description-updated"
           (count-events ctx :ontology/tree-description-updated)))

;; =============================================================================
;; Diff helpers — structural, not phrase-matching
;; =============================================================================

(defn principle-key
  "Identity for a principle entry — :trait is the actionable handle.
   Used to detect added / removed / mutated entries across cycles."
  [entry]
  (:trait entry))

(defn diff-principles
  "Structural diff between two principle vectors. Returns
     {:added [traits-in-new-not-in-old]
      :removed [traits-in-old-not-in-new]
      :preserved [traits-in-both]}"
  [old-entries new-entries]
  (let [old-keys (set (map principle-key old-entries))
        new-keys (set (map principle-key new-entries))]
    {:added (vec (set/difference new-keys old-keys))
     :removed (vec (set/difference old-keys new-keys))
     :preserved (vec (set/intersection old-keys new-keys))}))

(defn diff-bodies
  "Compare two description bodies and surface what the consolidator
   actually changed. Quality monitoring depends on this being honest —
   we report what moved, not whether we like it."
  [from-body to-body]
  {:version-bump [(or (:version from-body) 0) (or (:version to-body) 0)]
   :event-count-bump [(or (:consolidated-from-event-count from-body) 0)
                      (or (:consolidated-from-event-count to-body) 0)]
   :summary-changed? (not= (:summary from-body) (:summary to-body))
   :capabilities-diff (diff-principles (map #(hash-map :trait %) (or (:capabilities from-body) []))
                                       (map #(hash-map :trait %) (or (:capabilities to-body) [])))
   :strengths-diff (diff-principles (:strengths from-body) (:strengths to-body))
   :weaknesses-diff (diff-principles (:weaknesses from-body) (:weaknesses to-body))})

;; =============================================================================
;; Run capture
;; =============================================================================

(defn capture-run-artifacts
  "Extract the artifacts the report needs from a runner/run! result."
  [run-record cycle-label run-idx]
  {:cycle cycle-label
   :run-idx run-idx
   :status (:status run-record)
   :duration-ms (:duration-ms run-record)
   :total-tokens (get-in run-record [:usage :total-tokens])
   :node-trace-tokens (get-in run-record [:node-trace-usage-total :total-tokens])
   :prepend (get-in run-record [:r-inject-trace :prepend])
   :classifier-payload (get-in run-record [:r-inject-trace :classifier-payload])
   :iteration-reasonings (:iteration-reasonings run-record)
   :generated-tree-raw (:generated-tree-raw run-record)})

;; =============================================================================
;; Report rendering
;; =============================================================================

(defn render-body-section
  [snapshot]
  (str "### " (:label snapshot) " — " (:captured-at snapshot) "\n\n"
       "- **Version:** " (:version snapshot) "\n"
       "- **Consolidated-from-event-count:** " (:consolidated-from-event-count snapshot) "\n"
       "- **Strengths:** " (:strength-count snapshot) "\n"
       "- **Weaknesses:** " (:weakness-count snapshot) "\n"
       "- **Summary:** " (:summary snapshot) "\n\n"
       "<details><summary>Full body</summary>\n\n```clojure\n"
       (with-out-str (pprint/pprint (:body snapshot)))
       "```\n\n</details>\n\n"))

(defn render-diff-section
  [label diff]
  (str "### " label " — what the consolidator changed\n\n"
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

(defn render-run-section
  [art]
  (str "### " (:cycle art) " — Run #" (:run-idx art) "\n\n"
       "- **Status:** " (:status art) "\n"
       "- **Duration:** " (format "%.1f" (/ (or (:duration-ms art) 0) 1000.0)) "s\n"
       "- **Tokens (total):** " (:total-tokens art) "\n"
       "- **Tokens (node-trace sum):** " (:node-trace-tokens art) "\n\n"
       "<details><summary>Prepend the model literally saw</summary>\n\n```\n"
       (or (:prepend art) "(no prepend captured)")
       "\n```\n\n</details>\n\n"
       "<details><summary>Generated tree (verbatim)</summary>\n\n```clojure\n"
       (with-out-str (pprint/pprint (:generated-tree-raw art)))
       "```\n\n</details>\n\n"
       "<details><summary>Iteration reasonings (Phase 1)</summary>\n\n```\n"
       (str/join "\n\n---\n\n" (or (:iteration-reasonings art) []))
       "\n```\n\n</details>\n\n"))

(defn prepend-contains-body-summary?
  "Quality check — does the prepend the model literally saw contain the
   tree-class body summary at that moment? The Living Description loop
   only matters if the consolidator's updates surface in subsequent
   prepends."
  [run-artifact body-snapshot]
  (let [prepend (or (:prepend run-artifact) "")
        summary (or (:summary body-snapshot) "")]
    (and (seq prepend) (seq summary)
         (str/includes? prepend summary))))

(defn render-prepend-evolution-section
  "Did the prepend actually CHANGE between cycles? This is the
   load-bearing assertion for the Living Description loop closing back
   into R-Inject. Compares Run-1 prepend (bootstrap content) against
   Cycle-2 Run-1 prepend (post-consolidation content)."
  [pre-consolidation-runs post-consolidation-runs bootstrap-snap cycle-1-snap]
  (let [pre-prepend (some :prepend pre-consolidation-runs)
        post-prepend (some :prepend post-consolidation-runs)
        pre-contains-bootstrap? (some #(prepend-contains-body-summary? % bootstrap-snap)
                                      pre-consolidation-runs)
        post-contains-cycle-1? (some #(prepend-contains-body-summary? % cycle-1-snap)
                                     post-consolidation-runs)]
    (str "## Loop-closure proof — prepend changes after consolidation\n\n"
         "Does R-Inject's prepend actually use the consolidator's updates?\n\n"
         "- **Pre-consolidation runs included bootstrap summary in prepend?:** "
         (boolean pre-contains-bootstrap?) "\n"
         "- **Post-consolidation runs included Cycle-1 updated summary in prepend?:** "
         (boolean post-contains-cycle-1?) "\n"
         "- **Pre-prepend = Post-prepend (no change)?:** "
         (= pre-prepend post-prepend) "\n\n"
         (if (and pre-contains-bootstrap? post-contains-cycle-1?)
           "✓ Loop closed — bootstrap content present in pre-consolidation prepend; consolidator-refined content present in post-consolidation prepend.\n\n"
           (str "⚠ Could not fully verify loop closure — likely insufficient successful runs in one of the cycles. Pre-prepend present: "
                (boolean pre-prepend) "; Post-prepend present: " (boolean post-prepend) "\n\n")))))

(defn render-synthetic-prepend-section
  "Empirical proof — at three points in the loop, what does
   apply-r05-classifier-context's prepend literally contain? If the
   bodies evolved and the read path goes through fetch-tree-body, the
   prepends MUST diverge."
  [synthetic-prepends snapshots]
  (let [{:keys [bootstrap after-c1 after-c2]} synthetic-prepends
        [boot-snap c1-snap c2-snap] snapshots
        boot-contains-bootstrap-summary? (str/includes? bootstrap (:summary boot-snap))
        c1-contains-c1-summary? (str/includes? after-c1 (:summary c1-snap))
        c2-contains-c2-summary? (str/includes? after-c2 (:summary c2-snap))
        bootstrap-prepend-differs-from-c1? (not= bootstrap after-c1)
        c1-prepend-differs-from-c2? (not= after-c1 after-c2)
        all-three-differ? (= 3 (count (distinct [bootstrap after-c1 after-c2])))]
    (str "## Loop closure — empirical proof via synthetic R-Inject prepend\n\n"
         "The bench's same-process LLM hang prevents a second live R-Inject run from completing,\n"
         "so we directly probe `apply-r05-classifier-context` (the exact fn R-Inject uses) at\n"
         "three points in the loop. This proves whether the consolidator's updates surface in\n"
         "the prepend that the model WOULD see on the next run.\n\n"
         "- **Bootstrap synth-prepend contains bootstrap summary?:** " boot-contains-bootstrap-summary? "\n"
         "- **Post-Cycle-1 synth-prepend contains Cycle-1 summary?:** " c1-contains-c1-summary? "\n"
         "- **Post-Cycle-2 synth-prepend contains Cycle-2 summary?:** " c2-contains-c2-summary? "\n"
         "- **All three prepends differ from each other?:** " all-three-differ? "\n"
         "- **Bootstrap → Post-C1 prepend differs?:** " bootstrap-prepend-differs-from-c1? "\n"
         "- **Post-C1 → Post-C2 prepend differs?:** " c1-prepend-differs-from-c2? "\n\n"
         (if (and boot-contains-bootstrap-summary?
                  c1-contains-c1-summary?
                  c2-contains-c2-summary?
                  all-three-differ?)
           "✓✓ LOOP CLOSED — each cycle's consolidator output surfaces verbatim in the prepend the next R-Inject run would see. The Living Description loop genuinely refines what the model receives over time.\n\n"
           "⚠ Loop closure incomplete — check the assertions above to see which point failed.\n\n")
         "<details><summary>Bootstrap synth-prepend (verbatim, what Run-1 saw / would see)</summary>\n\n```\n"
         bootstrap "\n```\n\n</details>\n\n"
         "<details><summary>After-Cycle-1 synth-prepend (verbatim, what Run-N+1 would see)</summary>\n\n```\n"
         after-c1 "\n```\n\n</details>\n\n"
         "<details><summary>After-Cycle-2 synth-prepend (verbatim, what Run-N+M would see)</summary>\n\n```\n"
         after-c2 "\n```\n\n</details>\n\n")))

(defn render-report
  [{:keys [snapshots cycle-1-runs cycle-2-runs diffs synthetic-prepends]}]
  (str "# C-Loop-1 Live Verify Report\n\n"
       "Generated: " (str (java.time.Instant/now)) "\n\n"
       "Task: legal-issue-detection. Tree-class id: " tree-class-id "\n\n"
       (render-synthetic-prepend-section synthetic-prepends snapshots)
       (render-prepend-evolution-section cycle-1-runs cycle-2-runs
                                          (first snapshots) (second snapshots))
       "## Body snapshots\n\n"
       (str/join (map render-body-section snapshots))
       "## Cycle 1 — runs 1-3 (with bootstrap body in prepend)\n\n"
       (str/join (map render-run-section cycle-1-runs))
       "## Consolidation cycle 1 → 2 diff\n\n"
       (render-diff-section "Bootstrap → After Cycle-1 consolidation" (:bootstrap->c1 diffs))
       "## Cycle 2 — runs 4-6 (with consolidator-updated body in prepend)\n\n"
       (str/join (map render-run-section cycle-2-runs))
       "## Consolidation cycle 2 → 3 diff\n\n"
       (render-diff-section "After Cycle-1 → After Cycle-2 consolidation" (:c1->c2 diffs))
       "## Bootstrap → final composite diff\n\n"
       (render-diff-section "Bootstrap → After Cycle-2 consolidation (cumulative)" (:bootstrap->c2 diffs))
       "## Quality monitoring checklist (HITL review)\n\n"
       "Per the user's request to monitor actual quality, review the following:\n\n"
       "- [ ] **Body remains principle-shaped:** each `:strengths` / `:weaknesses` entry has actionable `:trait` + context guard (`:good-when` / `:avoid-when`) + actionable advice (`:recommended-pattern` / `:recommended-alternative`)\n"
       "- [ ] **No status-shape placeholders:** no `:trait` is \"investigate further\" / \"observed\" / \"unclear\"\n"
       "- [ ] **Cycle-1 evolved from bootstrap:** new strengths/weaknesses reflect observed evidence, or existing entries gained evidence-count\n"
       "- [ ] **Cycle-2 evolved from Cycle-1 (not from bootstrap):** the version bump is 2→3 not 1→2\n"
       "- [ ] **Run-2 prepend differs from Run-1 prepend AFTER Cycle-1 consolidation:** the Living Description loop actually feeds back into R-Inject\n"
       "- [ ] **Model reasoning references the updated content:** Phase 1 iteration-reasonings show the model attended to the prepend\n"
       "- [ ] **Generated trees stable across cycles or shifted in observable ways:** tree shapes, output schemas, max-concurrency bounds, etc.\n"
       "- [ ] **Token cost is sustainable:** per-run + per-consolidation budgets reasonable\n"))

;; =============================================================================
;; Orchestrator
;; =============================================================================

(defn run-cycle!
  [ctx cycle-label run-count]
  (println (str "\n--- " cycle-label " — " run-count " runs ---"))
  (mapv (fn [idx]
          (println (str "\n[" cycle-label " run " idx "/" run-count "]"))
          (let [result (runner/run! task/task)]
            (capture-run-artifacts result cycle-label idx)))
        (range 1 (inc run-count))))

(defn verify!
  "Run the full C-Loop-1 live verify. Returns a path to the saved
   markdown report."
  [{:keys [runs-per-cycle consolidation-wait-ms]
    :or {runs-per-cycle 3 consolidation-wait-ms 60000}}]
  (println "===========================================================")
  (println "  C-Loop-1 LIVE VERIFY — Living Description loop")
  (println "===========================================================")
  ;; Start a mulog console publisher so consolidator events (which use
  ;; mulog's silent-by-default u/log) become visible in stdout.
  (def ^:private mulog-stop
    (u/start-publisher! {:type :console}))
  (runner/start!)
  ;; runner/system-state is `(atom <ctx>)`; resolve the var, deref the
  ;; var to get the atom, deref the atom to get the ctx map.
  (let [ctx (deref @(requiring-resolve 'runner/system-state))
        bootstrap-snap (snapshot-body ctx tree-class-id "Bootstrap (seed)")
        ;; Synthetic prepend at bootstrap — the prepend a HYPOTHETICAL
        ;; next R-Inject run would see at this point (before any
        ;; consolidation).
        synth-prepend-bootstrap (synthetic-prepend! ctx tree-class-id)
        _ (println "\nBootstrap body captured:")
        _ (println "  version:" (:version bootstrap-snap))
        _ (println "  strengths:" (:strength-count bootstrap-snap))
        _ (println "  weaknesses:" (:weakness-count bootstrap-snap))
        _ (println "  synth-prepend chars:" (count synth-prepend-bootstrap))

        cycle-1-runs (run-cycle! ctx "Cycle-1 (pre-consolidation)" runs-per-cycle)

        _ (println "\n--- Forcing Cycle-1 consolidation (real LLM call) ---")
        _ (force-consolidation! ctx tree-class-id consolidation-wait-ms)
        cycle-1-snap (snapshot-body ctx tree-class-id "After Cycle-1 consolidation")
        synth-prepend-after-c1 (synthetic-prepend! ctx tree-class-id)
        _ (println "  version:" (:version cycle-1-snap))
        _ (println "  strengths:" (:strength-count cycle-1-snap))
        _ (println "  weaknesses:" (:weakness-count cycle-1-snap))
        _ (println "  synth-prepend chars:" (count synth-prepend-after-c1))

        cycle-2-runs (run-cycle! ctx "Cycle-2 (post-Cycle-1)" runs-per-cycle)

        _ (println "\n--- Forcing Cycle-2 consolidation (real LLM call) ---")
        _ (force-consolidation! ctx tree-class-id consolidation-wait-ms)
        cycle-2-snap (snapshot-body ctx tree-class-id "After Cycle-2 consolidation")
        synth-prepend-after-c2 (synthetic-prepend! ctx tree-class-id)
        _ (println "  version:" (:version cycle-2-snap))
        _ (println "  strengths:" (:strength-count cycle-2-snap))
        _ (println "  weaknesses:" (:weakness-count cycle-2-snap))
        _ (println "  synth-prepend chars:" (count synth-prepend-after-c2))

        diffs {:bootstrap->c1 (diff-bodies (:body bootstrap-snap) (:body cycle-1-snap))
               :c1->c2 (diff-bodies (:body cycle-1-snap) (:body cycle-2-snap))
               :bootstrap->c2 (diff-bodies (:body bootstrap-snap) (:body cycle-2-snap))}
        report-input {:snapshots [bootstrap-snap cycle-1-snap cycle-2-snap]
                      :cycle-1-runs cycle-1-runs
                      :cycle-2-runs cycle-2-runs
                      :diffs diffs
                      :synthetic-prepends {:bootstrap synth-prepend-bootstrap
                                           :after-c1 synth-prepend-after-c1
                                           :after-c2 synth-prepend-after-c2}}
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
     :diffs diffs}))

(comment
  ;; Run via REPL:
  ;;   OPENROUTER_API_KEY=... clj -M:dev -e \
  ;;     "(require '[c-loop-1-live-verify :as v]) (v/verify! {})"
  ;;
  ;; Tune sample size for cheaper iteration:
  ;;   (v/verify! {:runs-per-cycle 2})
  ;;
  ;; Note: each bench run on legal-issue-detection is ~30-60s + tokens.
  ;; Full 6-run verify is ~5-10 min wall clock.
  )
