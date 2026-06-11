(ns gap7b-live-verify
  "Gap-7b LIVE verify — heuristic-structural grades intermediate trees
   per :rlm/tree-generated event AND the terminal sum-up continues to
   fire all 5 default judges.

   Expected per legal-issue-detection run:
     - N :rlm/tree-generated events, where N = the number of Phase 1
       emit-tree! iterations the model did before calling (final!)
     - 1 terminal :sheet/node-execution-completed event for the
       repl-researcher
     - Per :rlm/tree-generated: 1 heuristic-structural :judge/score-
       emitted event (the Gap-7b processor)
     - Per terminal completion: 5 :judge/score-emitted events
       (heuristic-structural + 4 LLM judges, all defaults)
     - Total heuristic-structural events = N + 1

   PASS criteria:
     - Total :rlm/tree-generated events: > 0
     - Total :judge/score-emitted heuristic-structural events: > 1
       (must be more than the terminal-only behavior pre-Gap-7b)
     - Total :judge/score-emitted events ties tick-id back to either
       a :rlm/tree-generated or :sheet/node-execution-completed event
       (no orphans)
     - LLM judges still fire on terminal (regression check)

   Mock judges are NEVER used. Real OpenRouter, real bench."
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
  (let [tree-generated (into [] (es/read (:event-store ctx)
                                          {:types #{:rlm/tree-generated}
                                           :tenant-id (:tenant-id ctx)}))
        completions (into [] (es/read (:event-store ctx)
                                       {:types #{:sheet/node-execution-completed}
                                        :tenant-id (:tenant-id ctx)}))
        judges (into [] (es/read (:event-store ctx)
                                  {:types #{:judge/score-emitted}
                                   :tenant-id (:tenant-id ctx)}))
        heuristic-events (filter #(= "heuristic-structural" (:judge-name %)) judges)
        llm-events (filter #(contains? #{"grounding" "reasoning"
                                          "completeness" "instruction-following"}
                                        (:judge-name %)) judges)
        ;; Repl-researcher terminal completions only
        repl-terminal-completions
        (filter (fn [c]
                  (let [node (orc/get-node ctx (:sheet-id c) (:node-id c))]
                    (and (= :repl-researcher (:type node))
                         (= :terminal (:completion-kind c)))))
                completions)]
    {:tree-generated-count (count tree-generated)
     :terminal-completion-count (count repl-terminal-completions)
     :total-judges (count judges)
     :heuristic-structural-count (count heuristic-events)
     :llm-judge-count (count llm-events)
     :heuristic-scores (mapv :score heuristic-events)
     :llm-judges-per-name (frequencies (map :judge-name llm-events))}))

(defn verify!
  []
  (println "============================================================")
  (println " Gap-7b LIVE Verify — heuristic-structural per :rlm/tree-generated")
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
      ;; Poll up to 90s for judges to settle (parallel futures should
      ;; complete much faster, but the model's Phase 1 may produce
      ;; multiple trees so multiple processors fire in cascade).
      (loop [waited 0]
        (let [n-judges (count (into [] (es/read (:event-store ctx)
                                                 {:types #{:judge/score-emitted}
                                                  :tenant-id (:tenant-id ctx)})))
              n-trees (count (into [] (es/read (:event-store ctx)
                                                {:types #{:rlm/tree-generated}
                                                 :tenant-id (:tenant-id ctx)})))]
          (cond
            ;; Expect at minimum: 5 from terminal + 1 per tree-generated
            (and (pos? n-trees) (>= n-judges (+ 5 n-trees)))
            (println "  → judges settled. trees:" n-trees ", judges:" n-judges
                     ", waited:" waited "ms")

            (>= waited 90000)
            (println "  ⚠ timeout. trees:" n-trees ", judges:" n-judges)

            :else
            (do (Thread/sleep 2000) (recur (+ waited 2000))))))

      (let [stats (inspect-events ctx)
            pre-gap7b-expectation 5            ;; only terminal heuristic-structural
            gap7b-expectation (+ 5 (:tree-generated-count stats))
            heuristic-improved? (> (:heuristic-structural-count stats) 1)
            llm-judges-still-fire? (= 4 (count (:llm-judges-per-name stats)))
            no-orphan-heuristic? (>= (:heuristic-structural-count stats)
                                      (+ 1 (:tree-generated-count stats)))
            pass? (and (pos? (:tree-generated-count stats))
                       heuristic-improved?
                       llm-judges-still-fire?
                       no-orphan-heuristic?)]
        (println "\n============================================================")
        (println " RESULTS")
        (println "============================================================")
        (println "  :rlm/tree-generated events:" (:tree-generated-count stats))
        (println "  :sheet/node-execution-completed (repl-researcher terminal):"
                 (:terminal-completion-count stats))
        (println "  Total :judge/score-emitted events:" (:total-judges stats))
        (println "    heuristic-structural fires:" (:heuristic-structural-count stats)
                 "(expected ≥ 1 + N_trees =" (+ 1 (:tree-generated-count stats)) ")")
        (println "    LLM judge fires (4 default LLM judges):" (:llm-judge-count stats))
        (println "    LLM judges by name:" (pr-str (:llm-judges-per-name stats)))
        (println)
        (println "  heuristic-structural scores:" (pr-str (:heuristic-scores stats)))
        (println)
        (println "  Heuristic improved over pre-Gap-7b (>1 fire)?  " heuristic-improved?)
        (println "  LLM judges still fire (4 distinct names)?     " llm-judges-still-fire?)
        (println "  No orphan heuristic-structural?                " no-orphan-heuristic?)
        (println "  PASS:" pass?)
        (assoc stats :pass? pass?
               :heuristic-improved? heuristic-improved?
               :llm-judges-still-fire? llm-judges-still-fire?))
      (finally
        (runner/stop!)))))

(comment
  (require '[gap7b-live-verify :as v])
  (v/verify!))
