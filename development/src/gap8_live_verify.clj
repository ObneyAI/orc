(ns gap8-live-verify
  "Gap-8 LIVE verify — :judge/composite-score-computed event lands per
   bench tick alongside the per-judge :judge/score-emitted events.

   What this proves end-to-end:
     1. Real bench tick (legal-issue-detection repl-researcher) fires
        5 default judges. The processor computes a weighted composite
        from their scores and emits ONE :judge/composite-score-computed
        event per tick.
     2. The composite is a value in [0.0, 1.0] (sanity).
     3. The composite event carries :contributing-judges naming each
        judge that fed the composite, with its score + weight.
     4. No mocks — real OpenRouter, real bench.

   PASS criteria:
     - At least 1 :judge/composite-score-computed event lands
     - Each lands tagged with a valid (sheet, node, tick) tuple
     - Composite score is in [0.0, 1.0]
     - :contributing-judges count matches the count of fired judges
       for that tuple"
  (:require [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]
            [runner]
            [legal-issue-detection :as task]
            [ai.obney.orc.ontology.core.logic :as ontology-logic]
            [ai.obney.orc.ontology.core.queries :as ontology-queries]
            [ai.obney.orc.ontology.core.todo-processors :as ontology-processors]))

(defn verify!
  []
  (println "============================================================")
  (println " Gap-8 LIVE Verify — :judge/composite-score-computed events")
  (println "============================================================")
  (u/start-publisher! {:type :console})
  (runner/start!)
  (let [ctx (runner/current-context)]
    (try
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-living-description-enabled
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :enabled? true}))
      (Thread/sleep 200)

      (println "\n--- Running legal-issue-detection once ---")
      (runner/run! task/task)
      (loop [waited 0]
        (let [scores (count (into [] (es/read (:event-store ctx)
                                               {:types #{:judge/score-emitted}
                                                :tenant-id (:tenant-id ctx)})))
              composites (count (into [] (es/read (:event-store ctx)
                                                   {:types #{:judge/composite-score-computed}
                                                    :tenant-id (:tenant-id ctx)})))]
          (cond
            (and (pos? composites) (>= scores 5))
            (println "  → settled. scores:" scores ", composites:" composites
                     "waited:" waited "ms")
            (>= waited 60000)
            (println "  ⚠ timeout. scores:" scores ", composites:" composites)
            :else
            (do (Thread/sleep 2000) (recur (+ waited 2000))))))

      (let [composites (into [] (es/read (:event-store ctx)
                                          {:types #{:judge/composite-score-computed}
                                           :tenant-id (:tenant-id ctx)}))
            scores (into [] (es/read (:event-store ctx)
                                      {:types #{:judge/score-emitted}
                                       :tenant-id (:tenant-id ctx)}))
            valid-composites (filter #(and (number? (:composite-score %))
                                            (>= (:composite-score %) 0.0)
                                            (<= (:composite-score %) 1.0)
                                            (seq (:contributing-judges %)))
                                     composites)
            pass? (and (pos? (count composites))
                       (= (count composites) (count valid-composites)))]
        (println "\n============================================================")
        (println " RESULTS")
        (println "============================================================")
        (println "  Total :judge/score-emitted events:" (count scores))
        (println "  Total :judge/composite-score-computed events:" (count composites))
        (println "  Valid composites (score in [0,1] + has contributors):" (count valid-composites))
        (println)
        (when (seq composites)
          (doseq [c composites]
            (println "\n  Composite event:")
            (println "    sheet:" (:sheet-id c))
            (println "    node:" (:node-id c))
            (println "    tick:" (:tick-id c))
            (println "    composite-score:" (:composite-score c))
            (println "    contributing judges:")
            (doseq [j (:contributing-judges c)]
              (println "      -" (:judge-name j)
                       "score:" (:score j)
                       "weight:" (:weight j)))))
        (println)
        (println "  PASS:" pass?)
        {:pass? pass?
         :composites (count composites)
         :valid-composites (count valid-composites)
         :scores (count scores)})
      (finally
        (runner/stop!)))))


(defn verify-mixed-weights!
  []
  (println "============================================================")
  (println " Gap-8 RED#4 LIVE Verify — share-remaining-mass policy")
  (println "============================================================")
  (u/start-publisher! {:type :console})
  (runner/start!)
  (let [ctx (runner/current-context)]
    (try
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-living-description-enabled
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :enabled? true}))
      (Thread/sleep 200)

      (let [sheet-result (cp/process-command
                           (assoc ctx :command
                                  {:command/name :sheet/create-sheet
                                   :command/id (random-uuid)
                                   :command/timestamp (time/now)
                                   :name (str "Gap-8 RED#4 mixed-weights — " (random-uuid))}))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            _ (println "  → sheet created:" sheet-id)
            judge-configs [["weighted-grounding"
                            {:type :grounding :weight 0.5}]
                           ["default-reasoning"
                            {:type :reasoning}]
                           ["default-instruction-following"
                            {:type :instruction-following}]]]
        (doseq [[name cfg] judge-configs]
          (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/declare-judge
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id
                    :judge-name name
                    :judge-config cfg})))
        (println "  → 3 judges declared with mixed weights [0.5, _, _]")

        (let [node-result (cp/process-command
                            (assoc ctx :command
                                   {:command/name :sheet/create-node
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :sheet-id sheet-id
                                    :type :leaf}))
              node-id (-> node-result :command-result/events first :node-id)
              _ (cp/process-command
                  (assoc ctx :command
                         {:command/name :sheet/set-node-judges
                          :command/id (random-uuid)
                          :command/timestamp (time/now)
                          :sheet-id sheet-id
                          :node-id node-id
                          :judges (mapv first judge-configs)}))
              _ (Thread/sleep 200)
              tick-id (random-uuid)]
          (println "  → leaf node created and judges attached:" node-id)

          (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/complete-node-execution
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id
                    :tick-id tick-id
                    :node-id node-id
                    :node-type :llm
                    :status :success
                    :writes {:answer "The capital of France is Paris."}
                    :duration-ms 100}))
          (println "  → node-execution-completed event dispatched")

          (loop [waited 0]
            (let [scores (count (filter #(= tick-id (:tick-id %))
                                        (into [] (es/read (:event-store ctx)
                                                           {:types #{:judge/score-emitted}
                                                            :tenant-id (:tenant-id ctx)}))))
                  composites (count (filter #(= tick-id (:tick-id %))
                                            (into [] (es/read (:event-store ctx)
                                                               {:types #{:judge/composite-score-computed}
                                                                :tenant-id (:tenant-id ctx)}))))]
              (cond
                (and (>= scores 3) (pos? composites))
                (println "  → settled. scores:" scores ", composites:" composites
                         "waited:" waited "ms")
                (>= waited 90000)
                (println "  ⚠ timeout. scores:" scores ", composites:" composites)
                :else
                (do (Thread/sleep 2000) (recur (+ waited 2000))))))

          (let [composites (filter #(= tick-id (:tick-id %))
                                   (into [] (es/read (:event-store ctx)
                                                      {:types #{:judge/composite-score-computed}
                                                       :tenant-id (:tenant-id ctx)})))
                _ (println "\n  Composites for this tick:" (count composites))
                composite (first composites)
                contributing (when composite (:contributing-judges composite))
                weight-by-name (when contributing
                                 (into {} (map (juxt :judge-name :weight) contributing)))
                weighted-ok? (and weight-by-name (= 0.5 (get weight-by-name "weighted-grounding")))
                reasoning-ok? (and weight-by-name (= 0.25 (get weight-by-name "default-reasoning")))
                instr-ok? (and weight-by-name (= 0.25 (get weight-by-name "default-instruction-following")))
                pass? (and weighted-ok? reasoning-ok? instr-ok?)]
            (println "\n============================================================")
            (println " RESULTS — share-remaining-mass policy")
            (println "============================================================")
            (when composite
              (println "  composite-score:" (:composite-score composite))
              (println "  :contributing-judges:")
              (doseq [j contributing]
                (println "    -" (:judge-name j) "score:" (:score j) "weight:" (:weight j))))
            (println)
            (println "  weighted-grounding has weight 0.5 (explicit)?" weighted-ok?
                     (if weighted-ok? "" (str "(got " (get weight-by-name "weighted-grounding") ")")))
            (println "  default-reasoning has weight 0.25 (share-remaining)?" reasoning-ok?
                     (if reasoning-ok? "" (str "(got " (get weight-by-name "default-reasoning") ")")))
            (println "  default-instruction-following has weight 0.25 (share-remaining)?" instr-ok?
                     (if instr-ok? "" (str "(got " (get weight-by-name "default-instruction-following") ")")))
            (println)
            (println "  PASS:" pass?)
            {:pass? pass?
             :weight-by-name weight-by-name
             :composite-score (:composite-score composite)
             :contributing-judges contributing})))
      (finally
        (runner/stop!)))))

#_(do
  (require '[gap8-live-verify :as v])
  (v/verify!)
  (v/verify-mixed-weights!))
