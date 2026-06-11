(ns gap5-live-verify
  "Gap-5 LIVE verify — default judge attachment on a real bench-shaped
   sheet, with real OpenRouter LLM calls for the 4 LLM judges.

   Setup mirrors what the bench runner does: a sheet with one
   `:repl-researcher` node. NO explicit `:sheet/set-node-judges` —
   relying entirely on Gap-5's resolver to apply defaults.

   What 'success' looks like:
   - Exactly 5 `:judge/score-emitted` events land (heuristic-structural
     + 4 LLM)
   - heuristic-structural scores the realistic tree (no LLM call)
   - The 4 LLM judges produce real OpenRouter responses with substantive
     feedback
   - Total wall clock bounded by max-judge-latency, since judges run in
     parallel on async threads
   - Token cost ≈ 4 LLM calls per repl-researcher tick

   What this does NOT prove:
   - Real Phase 1 emit-tree! → defaults fire on the resulting tree
     (would require the bench runner's full RLM machinery; covered by
     Gap-5's eventual integration into runner.clj — out of scope for
     Gap-5's slice)"
  (:require [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]
            [litellm.router :as litellm-router]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/gap5-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "g5"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :dscloj-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps :topics topics
                                         :handler-fn handler-fn :context base-ctx})))
                     {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defn verify!
  "Run the Gap-5 LIVE verify. Returns a result map; prints a report
   to stdout."
  []
  (println "\n#### Gap-5 LIVE Verify — start:" (str (java.time.Instant/now)))
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set" {})))
        model "google/gemini-3-flash-preview"
        base-config {:provider :openrouter
                     :model model
                     :config {:api-base "https://openrouter.ai/api/v1"
                              :api-key api-key}}]
    (litellm-router/register! :openrouter base-config)
    (litellm-router/register! (keyword (str "openrouter/" model))
                              (assoc base-config :model model))
    (u/start-publisher! {:type :console})
    (let [ctx (create-context)]
      (try
        (println "  → opt-in true")
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/set-living-description-enabled
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :enabled? true}))
        (Thread/sleep 100)

        (println "  → creating sheet + repl-researcher node (NO explicit set-node-judges)")
        (let [sheet-result (cp/process-command
                             (assoc ctx :command
                                    {:command/name :sheet/create-sheet
                                     :command/id (random-uuid)
                                     :command/timestamp (time/now)
                                     :name (str "Gap-5 live — " (random-uuid))}))
              sheet-id (-> sheet-result :command-result/events first :sheet-id)
              node-result (cp/process-command
                            (assoc ctx :command
                                   {:command/name :sheet/create-node
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :sheet-id sheet-id
                                    :type :repl-researcher}))
              node-id (-> node-result :command-result/events first :node-id)
              _ (Thread/sleep 200)
              effective (evaluation/get-effective-judges-for-node ctx sheet-id node-id)
              _ (println "  → effective judges for the node:" (count effective))
              _ (doseq [j effective]
                  (println "      -" (:judge-name j) "type:" (get-in j [:judge-config :type])))

              tick-id (random-uuid)
              ;; Realistic tree the model might generate — :sequence
              ;; with :map-each + :final = excellent shape per the
              ;; heuristic.
              tree-dsl [:sequence
                        [:chunk-document {:from :doc :into :chunks}]
                        [:map-each {:from :chunks :as :chunk :into :results}
                         [:llm {:reads [:chunk] :writes [:result]
                                :instruction "Extract entities from chunk"}]]
                        [:aggregate {:from :results :writes [:entities]}]
                        [:final {:keys [:entities]}]]
              start (System/currentTimeMillis)
              _ (println "\n  → dispatching complete-node-execution at" (str (java.time.Instant/now)))
              _ (cp/process-command
                  (assoc ctx :command
                         {:command/name :sheet/complete-node-execution
                          :command/id (random-uuid)
                          :command/timestamp (time/now)
                          :sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :node-type :repl-researcher
                          :status :success
                          :writes {:generated-tree-raw tree-dsl
                                   :entities ["Acme Corp" "John Doe" "2026-01-01"]}
                          :duration-ms 12345}))
              ;; Real LLM calls: poll up to 90s for all 5 judges to emit
              _ (loop [waited 0]
                  (let [n (count (into [] (es/read (:event-store ctx)
                                                    {:types #{:judge/score-emitted}
                                                     :tenant-id (:tenant-id ctx)})))]
                    (cond
                      (>= n 5) (println "  → all 5 judges emitted after" waited "ms")
                      (>= waited 90000) (println "  ⚠ only" n "of 5 emitted after 90s")
                      :else (do (Thread/sleep 2000) (recur (+ waited 2000))))))
              duration-ms (- (System/currentTimeMillis) start)
              scores (evaluation/get-judge-scores ctx sheet-id node-id tick-id)]
          (println "\n#### Results")
          (println "  duration-ms:" duration-ms)
          (println "  scores returned:" (count scores))
          (doseq [s scores]
            (println "    -" (:judge-name s)
                     "score:" (:score s)
                     "feedback chars:" (count (:feedback s))))
          (println "\n#### Per-judge feedback (verbatim)")
          (doseq [s scores]
            (println "\n--- judge:" (:judge-name s) "---")
            (println "score:" (:score s))
            (println "feedback:" (:feedback s)))
          {:duration-ms duration-ms
           :judge-count (count scores)
           :scores scores})
        (finally
          (stop-context ctx))))))

(comment
  (require '[gap5-live-verify :as v])
  (v/verify!))
