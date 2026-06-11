(ns gap1-live-verify
  "Gap-1 LIVE verify — exercise the per-event evaluator runtime end-to-end
   with REAL OpenRouter LLM calls. Mocks bypassed: this is the
   production-shaped path.

   Per project memory `feedback_verification_live_runs`: synthetic tests
   prove wiring; only a real-LLM run proves the loop. This script:

   1. Brings up the bench's runner context (seeded corpus, real provider
      registered)
   2. Loads `evaluation.interface` so the new judge-runtime defprocessor
      registers + the read-model is available
   3. Sets `:ontology/set-living-description-enabled true`
   4. Creates a sheet + declares 2 LLM judges + leaf node + attaches them
   5. Dispatches a node-execution-completed event via the standard
      command path
   6. Waits for the async judge processor to invoke both LLM judges
      against the real provider
   7. Reports per-judge tokens, scores, feedback, total wall clock

   What 'success' looks like for this verify:
   - Exactly 2 :judge/score-emitted events land in the store
   - Each carries a valid 0.0-1.0 :score
   - Each carries non-empty :feedback (text from the LLM)
   - Total wall clock is bounded (~5-20s per LLM judge depending on model)
   - Token usage is finite (we'll get it from OpenRouter via dscloj)

   Failures to surface honestly:
   - LLM timeout or rate-limit → investigation, not silent retry
   - Schema validation failure on the emitted event → fix the shape, do not
     downgrade to mock
   - Zero events → the chain is broken; trace the gap, do not paper over

   Per `feedback_no_truncating_model_output`: the LLM's :feedback strings
   are printed verbatim in the report — no truncation."
  (:require [ai.obney.orc.evaluation.interface :as evaluation]
            ;; Loading evaluation.interface transitively requires
            ;; judge-runtime which registers the defprocessor.
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

(defn- create-context
  "Minimal Grain context — no ColBERT, no bench seeding (Gap-1 doesn't
   need the corpus). Just event store + cache + pubsub + processors."
  []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/gap1-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "g1"}))
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

(defn- register-openrouter!
  [api-key model]
  (let [base-config {:provider :openrouter
                     :model model
                     :config {:api-base "https://openrouter.ai/api/v1"
                              :api-key api-key}}]
    (litellm-router/register! :openrouter base-config)
    (litellm-router/register! (keyword (str "openrouter/" model))
                              (assoc base-config :model model))))

(defn- setup-sheet+judges!
  [ctx]
  (let [sheet-result (cp/process-command
                       (assoc ctx :command
                              {:command/name :sheet/create-sheet
                               :command/id (random-uuid)
                               :command/timestamp (time/now)
                               :name (str "Gap-1 live verify — " (random-uuid))}))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)
        _ (doseq [[judge-name judge-config]
                  [["grounding" {:type :grounding}]
                   ["reasoning" {:type :reasoning}]]]
            (cp/process-command
              (assoc ctx :command
                     {:command/name :sheet/declare-judge
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :sheet-id sheet-id
                      :judge-name judge-name
                      :judge-config judge-config})))
        node-result (cp/process-command
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
                    :judges ["grounding" "reasoning"]}))]
    (Thread/sleep 200)
    {:sheet-id sheet-id :node-id node-id}))

(defn- emit-node-execution-completed!
  [ctx sheet-id tick-id node-id]
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
            :writes {:answer "The non-compete clause in Schedule E imposes a 12-month restriction on employment with competing financial services firms within Greater Vancouver."}
            :duration-ms 12345})))

(defn verify!
  "Run the Gap-1 LIVE verify. Returns a result map with timing,
   per-judge scores, and tokens. Side effects: prints a clean report
   to stdout."
  []
  (println "\n#### Gap-1 LIVE Verify — start:" (str (java.time.Instant/now)))
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set" {})))
        model "google/gemini-3-flash-preview"
        _ (register-openrouter! api-key model)
        ;; Surface mulog events so any judge failures land in stdout
        _ (u/start-publisher! {:type :console})
        ctx (create-context)]
    (try
      (println "  → enabling :ontology/set-living-description-enabled true")
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-living-description-enabled
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :enabled? true}))
      (Thread/sleep 100)
      (println "  → opt-in flag is now:"
               (ontology/get-living-description-enabled? ctx))

      (println "  → creating sheet, declaring [grounding, reasoning] judges, leaf node, attaching")
      (let [{:keys [sheet-id node-id]} (setup-sheet+judges! ctx)
            tick-id (random-uuid)
            _ (println "    sheet-id:" sheet-id)
            _ (println "    node-id: " node-id)
            _ (println "    tick-id: " tick-id)
            start (System/currentTimeMillis)
            _ (println (str "\n  → dispatching :sheet/complete-node-execution at "
                            (java.time.Instant/now) "..."))
            _ (emit-node-execution-completed! ctx sheet-id tick-id node-id)
            ;; Real LLM calls take 5-30s each; both judges fire in parallel
            ;; on async threads. Poll up to 60s for both to complete.
            _ (loop [waited 0]
                (let [n (count (into [] (es/read (:event-store ctx)
                                                  {:types #{:judge/score-emitted}
                                                   :tenant-id (:tenant-id ctx)})))]
                  (cond
                    (>= n 2) (println "  → both judges emitted after" waited "ms")
                    (>= waited 60000) (println "  ⚠ only" n "of 2 judges emitted after 60s wait")
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
        (println "\n#### Per-judge feedback (verbatim, no truncation)")
        (doseq [s scores]
          (println "\n--- judge:" (:judge-name s) "---")
          (println "score:" (:score s))
          (println "feedback:" (:feedback s)))
        {:duration-ms duration-ms
         :judge-count (count scores)
         :scores scores})
      (finally
        (stop-context ctx)))))

(comment
  ;; Run from REPL:
  ;;   OPENROUTER_API_KEY=... clj -M:dev -e \
  ;;     "(require '[gap1-live-verify :as v]) (v/verify!)"
  )
