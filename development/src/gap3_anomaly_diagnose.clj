(ns gap3-anomaly-diagnose
  "Diagnose the n=3-of-4 heuristic-structural anomaly observed in
   gap3_loop_live_verify.

   Hypothesis: one of the bench's repl-researcher ticks emitted a
   :sheet/node-execution-completed event that didn't fire judges,
   either because:
     (a) the node lookup returned nil from inside the processor, OR
     (b) the node had explicit :judges [] (consumer override beating
         Gap-5's default-attachment), OR
     (c) the event hit before the living-description-enabled flag
         was projected, OR
     (d) the bench's per-run abstraction includes <1 repl-researcher
         tick on some runs.

   Procedure:
     1. Enable Living Description.
     2. Run legal-issue-detection 2 times.
     3. Dump all :sheet/node-execution-completed events with sheet-id,
        node-id, node-type, status.
     4. Dump all :judge/score-emitted events with their (sheet-id,
        node-id, tick-id) join keys.
     5. Cross-reference: which completion events DIDN'T produce
        judges? Look up the node in the read-model at that time and
        check its :type and :judges fields.

   Output: a console report identifying any orphan completion events
   (events without matching judges) and the inferred reason."
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

(defn diagnose!
  []
  (println "===========================================================")
  (println "  Gap-3 anomaly diagnose — n=3-of-4 root cause hunt")
  (println "===========================================================")
  (u/start-publisher! {:type :console})
  (runner/start!)
  (let [ctx (deref @(requiring-resolve 'runner/system-state))]
    (try
      (println "\n--- Enable Living Description ---")
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-living-description-enabled
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :enabled? true}))
      (Thread/sleep 200)
      (println "  enabled? =" (ontology/get-living-description-enabled? ctx))

      (println "\n--- Run #1 ---")
      (runner/run! task/task)
      (println "  (run 1 complete)")
      (Thread/sleep 1000)

      (println "\n--- Run #2 ---")
      (runner/run! task/task)
      (println "  (run 2 complete)")
      (Thread/sleep 2000)

      (let [completions (into [] (es/read (:event-store ctx)
                                          {:types #{:sheet/node-execution-completed}
                                           :tenant-id (:tenant-id ctx)}))
            judges (into [] (es/read (:event-store ctx)
                                     {:types #{:judge/score-emitted}
                                      :tenant-id (:tenant-id ctx)}))
            judge-keys (into #{} (map (juxt :sheet-id :node-id :tick-id)) judges)
            completion-keys (into #{} (map (juxt :sheet-id :node-id :tick-id)) completions)]
        (println "\n#### Counts")
        (println "  :sheet/node-execution-completed events:" (count completions))
        (println "  :judge/score-emitted events:" (count judges))
        (println "  Distinct (sheet,node,tick) tuples in completions:" (count completion-keys))
        (println "  Distinct (sheet,node,tick) tuples in judges:" (count judge-keys))

        (println "\n#### Per-completion analysis")
        (doseq [evt completions]
          (let [tuple [(:sheet-id evt) (:node-id evt) (:tick-id evt)]
                node (orc/get-node ctx (:sheet-id evt) (:node-id evt))
                judges-for (count (filter #(= tuple [(:sheet-id %) (:node-id %) (:tick-id %)]) judges))
                effective (try
                            (jr/get-effective-judges-for-node ctx (:sheet-id evt) (:node-id evt))
                            (catch Throwable t (str "ERR: " (.getMessage t))))]
            (println "  ---")
            (println "    sheet:" (str (:sheet-id evt))
                     "  node:" (str (:node-id evt))
                     "  tick:" (str (:tick-id evt)))
            (println "    event :node-type field:" (:node-type evt))
            (println "    event :status:" (:status evt))
            (println "    node lookup → :type:" (:type node)
                     ":judges:" (:judges node "(unset)")
                     ":node?" (some? node))
            (println "    effective judges count:" (cond
                                                     (number? effective) effective
                                                     (sequential? effective) (count effective)
                                                     :else effective))
            (println "    judges actually fired for this tuple:" judges-for)))

        (println "\n#### Orphan completions (no judges fired)")
        (let [orphans (filter (fn [evt]
                                (let [tuple [(:sheet-id evt) (:node-id evt) (:tick-id evt)]]
                                  (not (contains? judge-keys tuple))))
                              completions)]
          (println "  orphan count:" (count orphans))
          (doseq [o orphans]
            (let [node (orc/get-node ctx (:sheet-id o) (:node-id o))]
              (println "    -" (:node-type o)
                       "status:" (:status o)
                       "node-type-from-rm:" (:type node)
                       "judges-field:" (:judges node "(unset)")))))

        {:completions (count completions)
         :judges (count judges)
         :orphans (count (filter (fn [evt]
                                   (let [tuple [(:sheet-id evt) (:node-id evt) (:tick-id evt)]]
                                     (not (contains? judge-keys tuple))))
                                 completions))})
      (finally
        (runner/stop!)))))

(comment
  (require '[gap3-anomaly-diagnose :as d])
  (d/diagnose!))
