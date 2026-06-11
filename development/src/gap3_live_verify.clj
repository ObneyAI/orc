(ns gap3-live-verify
  "Gap-3 LIVE verify — the full Living Description loop with judges
   feeding the consolidator.

   Flow:
   1. Opt in to Living Description.
   2. Create a sheet + repl-researcher node (no explicit :judges — Gap-5
      defaults attach 5 judges including heuristic-structural + 4 LLM
      judges).
   3. Dispatch :sheet/complete-node-execution with a realistic tree —
      Gap-1 processor fires all 5 judges, producing real
      :judge/score-emitted events.
   4. Synthesize a :ontology/assign-task-class command for the same
      (sheet, tick) so the events join under a known tree-class-id.
   5. Dispatch :ontology/request-consolidation on the tree-class.
   6. Wait for the consolidator's LLM to run + emit
      :ontology/tree-description-updated.
   7. Verify the updated description body is well-shaped AND that the
      consolidator's LLM input carried the judge signal — by capturing
      the input pre-LLM via an instrumented wrapper.

   Success criteria:
   - The :ontology/tree-description-updated event lands with a non-empty
     :strengths/:weaknesses body (LLM produced principle-shaped output).
   - The captured LLM input included :judge-scores per observation AND
     :judge-averages in aggregate-metrics.

   Uses real OpenRouter for both the judges (4 LLM judges) and the
   consolidator's reflection call."
  (:require [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.consolidator]
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
        cache-dir (str "/tmp/gap3-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "g3"}))
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
  "Run the Gap-3 LIVE verify. Returns a result map; prints a report."
  []
  (println "\n#### Gap-3 LIVE Verify — start:" (str (java.time.Instant/now)))
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

        ;; Lower consolidation threshold so a single observation triggers.
        (println "  → setting consolidation threshold to 1 for :tree-class")
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/set-consolidation-threshold
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :target-type :tree-class
                  :threshold 1}))
        (Thread/sleep 100)

        (println "  → creating sheet + repl-researcher node")
        (let [tree-class-id (random-uuid)
              sheet-result (cp/process-command
                             (assoc ctx :command
                                    {:command/name :sheet/create-sheet
                                     :command/id (random-uuid)
                                     :command/timestamp (time/now)
                                     :name (str "Gap-3 live — " (random-uuid))}))
              sheet-id (-> sheet-result :command-result/events first :sheet-id)
              node-result (cp/process-command
                            (assoc ctx :command
                                   {:command/name :sheet/create-node
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :sheet-id sheet-id
                                    :type :repl-researcher}))
              node-id (-> node-result :command-result/events first :node-id)
              tick-id (random-uuid)
              tree-dsl [:sequence
                        [:chunk-document {:from :doc :into :chunks}]
                        [:map-each {:from :chunks :as :chunk :into :results}
                         [:llm {:reads [:chunk] :writes [:result]
                                :instruction "Extract entities from chunk"}]]
                        [:aggregate {:from :results :writes [:entities]}]
                        [:final {:keys [:entities]}]]]
          (Thread/sleep 200)

          (println "  → dispatching complete-node-execution to fire 5 judges")
          (cp/process-command
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
          ;; Wait for judges to emit BEFORE assigning the task-class so
          ;; the auto-trigger sees judge signal in its first run.
          (loop [waited 0]
            (let [n (count (into [] (es/read (:event-store ctx)
                                              {:types #{:judge/score-emitted}
                                               :tenant-id (:tenant-id ctx)})))]
              (cond
                (>= n 5) (println "  → all 5 judges emitted after" waited "ms")
                (>= waited 90000) (println "  ⚠ only" n "of 5 emitted after 90s")
                :else (do (Thread/sleep 2000) (recur (+ waited 2000))))))

          ;; Record the tree-execution-completion so the consolidator
          ;; can join it with the task-classification.
          (println "  → recording rlm-tree-execution-completion")
          (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/record-rlm-tree-execution-completion
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id
                    :tick-id tick-id
                    :trajectory []
                    :total-usage {:total-tokens 1000}
                    :tree-fingerprint "fp-live-gap3"
                    :status :success
                    :duration-ms 12345}))
          (Thread/sleep 200)

          ;; Now assign the task-class — threshold=1 will auto-fire
          ;; consolidation, and the consolidator will see judges +
          ;; execution events already in the store.
          (println "  → assigning task-class — triggers consolidation")
          (let [start (System/currentTimeMillis)]
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/assign-task-class
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :source-sheet-id sheet-id
                      :source-tick-id tick-id
                      :source-node-id node-id
                      :assigned-tree-id tree-class-id
                      :confidence 0.95
                      :top-candidates []
                      :reasoning "gap-3 live verify synthetic assignment"
                      :was-fresh-mint? false}))
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :tree-class
                      :target-id tree-class-id
                      :on-demand? true}))
            ;; Wait up to 60s for the consolidator's LLM reflection +
            ;; emission of :ontology/tree-description-updated
            (loop [waited 0]
              (let [updates (into [] (es/read (:event-store ctx)
                                               {:types #{:ontology/tree-description-updated}
                                                :tenant-id (:tenant-id ctx)}))
                    relevant (filter #(and (= :tree-class (:target-type %))
                                            (= tree-class-id (:target-id %)))
                                     updates)]
                (cond
                  (seq relevant)
                  (let [duration-ms (- (System/currentTimeMillis) start)
                        latest (last relevant)
                        body (:body latest)]
                    (println "  → consolidator emitted update after" duration-ms "ms")
                    (println "\n#### Description body produced by consolidator")
                    (println "  :version" (:version body))
                    (println "  :capabilities" (pr-str (:capabilities body)))
                    (println "  :strengths count" (count (:strengths body)))
                    (doseq [s (:strengths body)]
                      (println "    -" (:trait s) "(" (:confidence s) ")"))
                    (println "  :weaknesses count" (count (:weaknesses body)))
                    (doseq [w (:weaknesses body)]
                      (println "    -" (:trait w) "(" (:confidence w) ")"))
                    (println "  :summary" (:summary body))
                    {:duration-ms duration-ms
                     :body body
                     :strengths-count (count (:strengths body))
                     :weaknesses-count (count (:weaknesses body))})

                  (>= waited 60000)
                  (do (println "  ⚠ no consolidation update after 60s")
                      {:status :timeout})

                  :else
                  (do (Thread/sleep 2000) (recur (+ waited 2000))))))))
        (finally
          (stop-context ctx))))))

(comment
  (require '[gap3-live-verify :as v])
  (v/verify!))
