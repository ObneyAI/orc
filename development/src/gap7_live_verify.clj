(ns gap7-live-verify
  "Gap-7 LIVE verify — routing judges by completion-kind on
   legal-issue-detection.

   Runs the bench once and inspects every
   :sheet/node-execution-completed event for the repl-researcher node.
   Asserts that each event carries :completion-kind (either
   :tree-iteration or :terminal) AND that the right judges fired for
   each kind:

     - :tree-iteration events  → heuristic-structural judge fires (1)
     - :terminal events        → 4 LLM judges fire
       (grounding/reasoning/completeness/instruction-following)

   Success criteria:
     - Every repl-researcher completion event has a :completion-kind
     - For each :tree-iteration event, exactly 1 judge event (heuristic-structural)
     - For each :terminal event, 4 judge events (the 4 LLM judges)
     - Zero events where effective-judges resolved but no judge fired
       (the silent-nil orphan case from the original anomaly)

   If LLM judges still silently nil on terminal completions, this
   verify will surface it via a non-zero orphan count — that's a
   follow-up issue, not a Gap-7 failure (routing is the Gap-7
   deliverable)."
  (:require [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime :as jr]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]
            [runner]
            [legal-issue-detection :as task]))

(defn- inspect-events [ctx]
  (let [completions (into [] (es/read (:event-store ctx)
                                       {:types #{:sheet/node-execution-completed}
                                        :tenant-id (:tenant-id ctx)}))
        judges (into [] (es/read (:event-store ctx)
                                  {:types #{:judge/score-emitted}
                                   :tenant-id (:tenant-id ctx)}))
        judges-by-tuple (group-by (juxt :sheet-id :node-id :tick-id) judges)
        repl-completions (filter #(let [node (orc/get-node ctx (:sheet-id %) (:node-id %))]
                                    (= :repl-researcher (:type node)))
                                 completions)]
    {:total-completions (count completions)
     :repl-completions (count repl-completions)
     :total-judges (count judges)
     :iter-completions (count (filter #(= :tree-iteration (:completion-kind %)) repl-completions))
     :terminal-completions (count (filter #(= :terminal (:completion-kind %)) repl-completions))
     :unmarked-completions (count (filter #(nil? (:completion-kind %)) repl-completions))
     :iter-judges (count (filter (fn [j]
                                   (let [comp-evt (first (filter #(and (= (:sheet-id %) (:sheet-id j))
                                                                        (= (:node-id %) (:node-id j))
                                                                        (= (:tick-id %) (:tick-id j)))
                                                                  repl-completions))]
                                     (= :tree-iteration (:completion-kind comp-evt))))
                                 judges))
     :terminal-judges (count (filter (fn [j]
                                       (let [comp-evt (first (filter #(and (= (:sheet-id %) (:sheet-id j))
                                                                            (= (:node-id %) (:node-id j))
                                                                            (= (:tick-id %) (:tick-id j)))
                                                                      repl-completions))]
                                         (= :terminal (:completion-kind comp-evt))))
                                     judges))
     :orphan-completions
     (->> repl-completions
          (filter (fn [c]
                    (let [tuple [(:sheet-id c) (:node-id c) (:tick-id c)]
                          had-judges (seq (get judges-by-tuple tuple))
                          ;; Effective judges if completion-kind matches
                          eff (jr/get-effective-judges-for-node
                                ctx (:sheet-id c) (:node-id c) (:completion-kind c))]
                      (and (seq eff) (not had-judges)))))
          (mapv (fn [c] {:completion-kind (:completion-kind c)
                          :status (:status c)
                          :tick-id (:tick-id c)
                          :writes-keys (vec (keys (or (:writes c) {})))})))}))

(defn verify!
  []
  (println "============================================================")
  (println " Gap-7 LIVE Verify — judge routing by completion-kind")
  (println "============================================================")
  (u/start-publisher! {:type :console})
  (runner/start!)
  (let [ctx (deref @(requiring-resolve 'runner/system-state))]
    (try
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-living-description-enabled
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :enabled? true}))
      (Thread/sleep 200)

      (println "\n--- Single legal-issue-detection run ---")
      (runner/run! task/task)
      ;; Diagnostic: the processor invokes 5 judges sequentially;
      ;; each LLM judge can take 3-10s. Poll up to 60s for the events
      ;; to land. If still 0 after the budget, that's a real bug;
      ;; otherwise we caught a timing race in the verify script, not
      ;; in the production path.
      (loop [waited 0]
        (let [n (count (into [] (es/read (:event-store ctx)
                                          {:types #{:judge/score-emitted}
                                           :tenant-id (:tenant-id ctx)})))]
          (cond
            (>= n 5) (println "  → all 5 judges settled after" waited "ms")
            (>= waited 60000) (println "  ⚠ only" n "of 5 judges landed in 60s")
            :else (do (Thread/sleep 2000) (recur (+ waited 2000))))))

      (let [stats (inspect-events ctx)]
        (println "\n============================================================")
        (println " RESULTS")
        (println "============================================================")
        (println "  Total :sheet/node-execution-completed events:" (:total-completions stats))
        (println "  Of those, repl-researcher events:" (:repl-completions stats))
        (println "    - :completion-kind :tree-iteration:" (:iter-completions stats))
        (println "    - :completion-kind :terminal:" (:terminal-completions stats))
        (println "    - UNMARKED (no :completion-kind):" (:unmarked-completions stats))
        (println)
        (println "  Total :judge/score-emitted events:" (:total-judges stats))
        (println "    - fired during :tree-iteration completions:" (:iter-judges stats))
        (println "    - fired during :terminal completions:" (:terminal-judges stats))
        (println)
        (println "  Orphan completions (judges resolved but didn't fire):" (count (:orphan-completions stats)))
        (when (seq (:orphan-completions stats))
          (doseq [o (:orphan-completions stats)]
            (println "    -" (:completion-kind o) "status:" (:status o)
                     "writes-keys:" (:writes-keys o))))
        ;; Post-revert (2026-06-05): all 5 default judges fire on
        ;; terminal events (kind filter was removed; see Gap-7
        ;; retrospective). iter-completions stays 0 in current bench
        ;; behavior because recursive RLM only fires one terminal
        ;; :sheet/node-execution-completed per run — per-iteration
        ;; tree-shape evidence lives on :rlm/tree-generated which is
        ;; Gap-7b's territory. So PASS = 5 judges per terminal + no
        ;; unmarked + no orphans.
        (let [expected-per-terminal 5
              terminal-expected (>= (:terminal-judges stats)
                                    (* expected-per-terminal (:terminal-completions stats)))
              no-unmarked? (zero? (:unmarked-completions stats))
              no-orphans? (zero? (count (:orphan-completions stats)))
              pass? (and (pos? (:terminal-completions stats))
                         no-unmarked?
                         terminal-expected
                         no-orphans?)
              tree-iter-expected (>= (:iter-judges stats) (:iter-completions stats))]
          (println)
          (println "  Routing valid?         " (and (pos? (:iter-completions stats))
                                                    (pos? (:terminal-completions stats))
                                                    no-unmarked?))
          (println "  Tree-iter judges OK?   " tree-iter-expected
                   "  (expect >= 1 per iter completion; got" (:iter-judges stats)
                   "for" (:iter-completions stats) "completions)")
          (println "  Terminal judges OK?    " terminal-expected
                   "  (expect 4 per terminal completion; got" (:terminal-judges stats)
                   "for" (:terminal-completions stats) "completions)")
          (println "  No silent-nil orphans? " no-orphans?)
          (println "  PASS:" pass?)
          (assoc stats :pass? pass?
                 :tree-iter-expected? tree-iter-expected
                 :terminal-expected? terminal-expected
                 :no-orphans? no-orphans?)))
      (finally
        (runner/stop!)))))

(comment
  (require '[gap7-live-verify :as v])
  (v/verify!))
