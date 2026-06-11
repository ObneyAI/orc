(ns diagnose-llm-hang
  "Isolate the 'second LLM call hangs' failure mode by exercising
   different layers of the stack in sequence:

   1. Direct dscloj/predict calls back-to-back — proves whether the
      LLM client itself hangs after a burst.
   2. Bench tick-tree without any consolidator/wedge — proves whether
      the orc workflow infrastructure hangs.
   3. Full bench run pair — reproduces the observed failure.

   Each step times itself with explicit println so we see exactly where
   the hang manifests instead of waiting for a 10-min timeout."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.executor]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.orc.orc-service.core.todo-processors]
            ;; NOTE: DELIBERATELY NOT loading the consolidator namespace.
            ;; If bench runs back-to-back NOW succeed (where they timed
            ;; out before), the consolidator's processor subscriptions
            ;; are the back-pressure source on the shared pubsub.
            ;; [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [dscloj.core :as dscloj]
            [litellm.router :as litellm-router]
            [com.brunobonacci.mulog :as u]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.time.interface :as time]
            [runner]
            [legal-issue-detection]))

(defn- ts [] (str (java.time.Instant/now)))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/orc-diag-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "diag"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :openrouter
                  ::cache-dir cache-dir}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps :topics topics
                                         :handler-fn handler-fn :context base-ctx})))
                     {} @tp/processor-registry*)]
    (assoc base-ctx :event-pubsub ps :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es)))

(defn- minimal-llm-workflow
  "Build a one-:llm-node ORC workflow that asks for a short answer.
   Bypasses the reranker/wedge entirely."
  []
  (orc/workflow "diag-minimal-llm"
    (orc/blackboard {:question :string
                     :answer :string})
    (orc/llm "ask"
      :instruction "Answer the question in one short sentence."
      :reads [:question]
      :writes [:answer])))

(defn- step-1-direct-dscloj-calls
  "Call dscloj/predict directly — bypasses ORC workflows entirely.
   If the second call here hangs, the issue is in litellm/HTTP layer."
  [_ctx]
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println " STEP 1 — direct dscloj/predict back-to-back")
  (println " (no ORC workflow, no event store — just the LLM client)")
  (println (apply str (repeat 60 "=")) "\n")
  (let [module {:inputs [{:name :q :spec :string :description "the question"}]
                :outputs [{:name :a :spec :string :description "one-short-sentence answer"}]
                :instructions "Answer the question in one short sentence."}
        predict-once (fn [label]
                       (println (str "[" (ts) "] " label ": calling dscloj/predict..."))
                       (let [start (System/currentTimeMillis)
                             result (try
                                      (dscloj/predict :openrouter
                                                       module
                                                       {:q "Say 'hello' and nothing else."}
                                                       {:model "google/gemini-3-flash-preview"
                                                        :timeout-ms 60000
                                                        :validate? false})
                                      (catch Throwable t {:error (.getMessage t)
                                                          :class (.getName (class t))}))
                             duration (- (System/currentTimeMillis) start)]
                         (println (str "[" (ts) "] " label ": done in " duration "ms"))
                         (println (str "  result: " (pr-str (select-keys result [:outputs :usage :error :class]))))
                         {:label label :duration-ms duration :result result}))]
    (let [r1 (predict-once "call 1")
          _  (println (str "[" (ts) "] Sleeping 5s to simulate gap..."))
          _  (Thread/sleep 5000)
          r2 (predict-once "call 2")
          _  (println (str "[" (ts) "] Sleeping 5s..."))
          _  (Thread/sleep 5000)
          r3 (predict-once "call 3")]
      [r1 r2 r3])))

(defn- step-2-minimal-orc-llm-workflows
  "Run two identical minimal :llm workflows back-to-back through ORC's
   execute infrastructure. If this hangs while step 1 succeeds, the
   issue is in ORC's tick-tree/processor flow, not the LLM client."
  [ctx]
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println " STEP 2 — minimal ORC workflow back-to-back (no reranker)")
  (println (apply str (repeat 60 "=")) "\n")
  (let [wf (minimal-llm-workflow)
        run-once (fn [label]
                   (println (str "[" (ts) "] " label ": building workflow..."))
                   (let [sheet-id (orc/build-workflow! ctx wf)
                         _ (println (str "[" (ts) "] " label ": built sheet " sheet-id ", executing..."))
                         start (System/currentTimeMillis)
                         result (try
                                  (orc/execute ctx sheet-id
                                                {:question "Say 'hello' and nothing else."}
                                                {:timeout-ms 60000})
                                  (catch Throwable t {:error (.getMessage t)
                                                       :class (.getName (class t))}))
                         duration (- (System/currentTimeMillis) start)]
                     (println (str "[" (ts) "] " label ": done in " duration "ms — status " (:status result)))
                     {:label label :duration-ms duration :status (:status result)
                      :answer (get-in result [:outputs :answer])
                      :error (:error result)}))]
    (let [r1 (run-once "wf-run 1")
          _  (println (str "[" (ts) "] Sleeping 5s..."))
          _  (Thread/sleep 5000)
          r2 (run-once "wf-run 2")
          _  (println (str "[" (ts) "] Sleeping 5s..."))
          _  (Thread/sleep 5000)
          r3 (run-once "wf-run 3")]
      [r1 r2 r3])))

(defn- dump-threads!
  "Dump all JVM thread stack traces. Used to pinpoint where Run #2
   is blocked. Limit to BLOCKED/WAITING/PARKED states."
  [label]
  (let [tmx (java.lang.management.ManagementFactory/getThreadMXBean)
        infos (.dumpAllThreads tmx true true)]
    (println (str "\n##### THREAD DUMP — " label " — total threads: " (count infos)))
    (doseq [info infos]
      (let [state (.getThreadState info)
            name (.getThreadName info)]
        (when (or (= state Thread$State/BLOCKED)
                  (= state Thread$State/WAITING)
                  (= state Thread$State/TIMED_WAITING))
          (let [trace (.getStackTrace info)
                top-10 (take 10 trace)
                ;; Skip threads whose top frames are all in idle pool wait
                ;; states (LockSupport.park called via ThreadPool$Worker.run
                ;; with no application frames in stack).
                interesting? (some (fn [f]
                                     (let [cn (.getClassName f)
                                           fn-name (.getFileName f)]
                                       (and fn-name
                                            (or (.startsWith cn "ai.obney")
                                                (.startsWith cn "runner")
                                                (.startsWith cn "legal_issue")
                                                (.startsWith cn "diagnose")
                                                (.startsWith cn "clojure.core$promise")
                                                (.contains cn "$deref")))))
                                   (take 30 trace))]
            (when interesting?
              (println (str "  [" state "] " name))
              (doseq [f (take 12 top-10)]
                (println (str "    " (.getClassName f) "." (.getMethodName f) " (" (.getFileName f) ":" (.getLineNumber f) ")"))))))))))

(defn- step-5-poll-events-during-hang
  "Run ONE bench task, then dispatch a second one in a background thread
   while POLLING the event store every 5 seconds. Each poll counts
   events by type so we can see exactly which event the second run
   stops at. The polling pinpoints the broken processor."
  [ctx]
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println " STEP 5 — instrumented bench Run #2 (poll event-store)")
  (println (apply str (repeat 60 "=")) "\n")
  (let [task-ns (requiring-resolve 'legal-issue-detection/task)
        runner-run! (requiring-resolve 'runner/run!)
        task (-> @task-ns (assoc :rlm {:auto-classify? false :debug? false}))
        relevant-types [:sheet/tick-tree
                        :sheet/tree-tick-started
                        :sheet/node-execution-started
                        :sheet/node-execution-completed
                        :sheet/tree-tick-completed
                        :sheet/sheet-created
                        :sheet/repl-researcher-config-set]
        count-by-type (fn [label]
                        (println (str "\n[" (ts) "] " label " — event counts:"))
                        (doseq [t relevant-types]
                          (let [n (count (into [] (es/read (:event-store ctx)
                                                            {:types #{t}
                                                             :tenant-id (:tenant-id ctx)})))]
                            (println (str "  " t " = " n)))))]
    (println (str "[" (ts) "] Step 5 — pre-warm: running bench Run #1..."))
    (let [r1 (runner-run! task)]
      (println (str "[" (ts) "] Step 5 — Run #1 status: " (:status r1))))
    (count-by-type "after Run #1")

    ;; Directly probe what cp/process-command returns post-Run-1 by issuing
    ;; the SAME first command setup-task-sheet! issues. If it returns an
    ;; anomaly, we'll see it printed. If it hangs, we'll see it never return.
    (println (str "\n[" (ts) "] Step 5 — probing direct cp/process-command :sheet/create-sheet after Run #1..."))
    (let [probe-result (try (h/run-and-apply! ctx (h/make-create-sheet-command :name "PROBE-after-run-1"))
                            (catch Throwable t {:probe-error (.getMessage t)
                                                :probe-class (.getName (class t))}))]
      (println (str "[" (ts) "] Step 5 — probe result:"))
      (println (str "  keys: " (try (keys probe-result) (catch Throwable _ :unparseable))))
      (println (str "  result: " (pr-str (select-keys probe-result
                                                       [:command-result/events
                                                        :cognitect.anomalies/category
                                                        :cognitect.anomalies/message
                                                        :probe-error :probe-class
                                                        :error/explain]))))
      (count-by-type "after probe"))
    (println (str "\n[" (ts) "] Step 5 — running Run #2 DIRECTLY on caller thread (no future) with poller in background..."))
    (let [run2-result (atom nil)
          poller (future
                   (Thread/sleep 3000) (count-by-type "Run #2 +3s")
                   (Thread/sleep 12000) (count-by-type "Run #2 +15s")
                   (Thread/sleep 15000) (count-by-type "Run #2 +30s")
                   (println (str "[" (ts) "] Step 5 — probing AGAIN while Run #2 hung..."))
                   (let [probe2-start (System/currentTimeMillis)
                         probe2-fut (future
                                      (try (h/run-and-apply! ctx (h/make-create-sheet-command :name "PROBE-during-run-2"))
                                           (catch Throwable t {:probe-error (.getMessage t)})))]
                     (.get probe2-fut 10 java.util.concurrent.TimeUnit/SECONDS)
                     (println (str "[" (ts) "] Step 5 — probe2 result: "
                                   (if (.isDone probe2-fut)
                                     (str "done in " (- (System/currentTimeMillis) probe2-start) "ms")
                                     "STILL HUNG after 10s")))
                     (when (.isDone probe2-fut)
                       (println (str "  keys: " (try (keys @probe2-fut) (catch Throwable _ :unparseable))))
                       (println (str "  events: " (count (:command-result/events @probe2-fut))))))
                   (count-by-type "after probe2")
                   (dump-threads! "Run #2 +30s — looking for blocked threads"))
          run2-start (System/currentTimeMillis)
          run2-fut (future
                     (try
                       (let [r (runner-run! task)]
                         (reset! run2-result r)
                         (println (str "[" (ts) "] Run #2 RETURNED with status: " (:status r))))
                       (catch Throwable t
                         (reset! run2-result {:error (.getMessage t)})
                         (println (str "[" (ts) "] Run #2 THREW: " (.getMessage t))))))]
      (Thread/sleep 35000)
      (println (str "[" (ts) "] Step 5 — Run #2 done? " (.isDone run2-fut)
                    " (after " (- (System/currentTimeMillis) run2-start) "ms)"))
      (when-not (.isDone run2-fut) (future-cancel run2-fut))
      (when-not (.isDone poller) (future-cancel poller))
      {:run-1 :examined :run-2-result @run2-result})))

(defn- step-4-bench-runs-without-auto-classify
  "Run the bench's runner/run! 2x with auto-classify DISABLED. If this
   SUCCEEDS while Step 3 (auto-classify on) HANGS, the bench's auto-
   classify wedge — which dispatches NESTED tick-tree workflows from
   inside the outer tick-tree handler — is responsible."
  [_ctx]
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println " STEP 4 — bench runner/run! WITHOUT auto-classify (rlm {:auto-classify? false})")
  (println (apply str (repeat 60 "=")) "\n")
  (let [task-ns (requiring-resolve 'legal-issue-detection/task)
        runner-run! (requiring-resolve 'runner/run!)
        task-no-classify (-> @task-ns
                             (assoc :rlm {:auto-classify? false :debug? true}))
        run-once (fn [label]
                   (println (str "[" (ts) "] " label ": dispatching runner/run! (auto-classify OFF)..."))
                   (let [start (System/currentTimeMillis)
                         result (try (runner-run! task-no-classify)
                                     (catch Throwable t {:status :error
                                                          :error (.getMessage t)
                                                          :class (.getName (class t))}))
                         duration (- (System/currentTimeMillis) start)]
                     (println (str "[" (ts) "] " label ": finished in " duration "ms — status " (:status result)
                                   (when (:error result) (str " [" (:class result) ": " (:error result) "]"))))
                     {:label label :duration-ms duration :status (:status result)
                      :error (:error result)}))]
    (let [r1 (run-once "no-classify-run 1")
          _  (println (str "[" (ts) "] Sleeping 5s..."))
          _  (Thread/sleep 5000)
          r2 (run-once "no-classify-run 2")]
      [r1 r2])))

(defn- step-3-actual-bench-runs
  "Run the bench's full runner/run! twice. This is the SAME flow the
   live verify used. If this reproduces the hang while step 2 succeeded,
   the issue is somewhere in the bench-specific path: setup-task-sheet,
   auto-classify wedge (with NESTED tick-tree from reranker), or the
   full Phase 2 tree execution. The presence/absence of a SUBCALL
   between the runs tells us where the hang sits."
  [_ctx]
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println " STEP 3 — bench runner/run! back-to-back (the failing flow)")
  (println (apply str (repeat 60 "=")) "\n")
  (let [task-ns (requiring-resolve 'legal-issue-detection/task)
        runner-run! (requiring-resolve 'runner/run!)
        run-once (fn [label]
                   (println (str "[" (ts) "] " label ": dispatching runner/run!..."))
                   (let [start (System/currentTimeMillis)
                         result (try (runner-run! @task-ns)
                                     (catch Throwable t {:status :error
                                                          :error (.getMessage t)
                                                          :class (.getName (class t))}))
                         duration (- (System/currentTimeMillis) start)]
                     (println (str "[" (ts) "] " label ": finished in " duration "ms — status " (:status result)
                                   (when (:error result) (str " [" (:class result) ": " (:error result) "]"))))
                     {:label label :duration-ms duration :status (:status result)
                      :error (:error result)}))]
    (let [r1 (run-once "bench-run 1")
          _  (println (str "[" (ts) "] Sleeping 5s..."))
          _  (Thread/sleep 5000)
          r2 (run-once "bench-run 2")]
      [r1 r2])))

(defn diagnose!
  "Top-level entry — runs both steps and prints a summary at the end.
   Uses the BENCH'S system (runner/start!) so step 3 can hit the same
   seeded corpus + ColBERT index the live verify uses."
  []
  (println "\n###################################")
  (println "# LLM HANG DIAGNOSTIC — start: " (ts))
  (println "###################################\n")
  ;; Surface mulog events so cp/process-command anomalies become visible.
  (defonce ^:private mulog-publisher
    (u/start-publisher! {:type :console}))
  ;; Use the bench's start! so step 3's runner/run! has a proper context
  ;; (seeded corpus + built ColBERT index). start! also registers the
  ;; openrouter client.
  (let [runner-start! (requiring-resolve 'runner/start!)
        runner-stop!  (requiring-resolve 'runner/stop!)]
    (runner-start!)
    (let [ctx (deref @(requiring-resolve 'runner/system-state))]
    (try
      (let [step-1 (step-1-direct-dscloj-calls ctx)
            step-2 (step-2-minimal-orc-llm-workflows ctx)
            step-5 (step-5-poll-events-during-hang ctx)
            step-4 [] step-3 []]
        (println "\n###################################")
        (println "# SUMMARY")
        (println "###################################")
        (println "\nSTEP 1 — direct dscloj/predict durations:")
        (doseq [r step-1]
          (println (str "  " (:label r) ": " (:duration-ms r) "ms"
                        (when (:error (:result r))
                          (str " [ERROR " (:class (:result r)) ": " (:error (:result r)) "]")))))
        (println "\nSTEP 2 — ORC workflow durations:")
        (doseq [r step-2]
          (println (str "  " (:label r) ": " (:duration-ms r) "ms status " (:status r)
                        (when (:error r) (str " [ERROR: " (:error r) "]")))))
        (println "\nSTEP 4 — bench runner/run! (auto-classify OFF) durations:")
        (doseq [r step-4]
          (println (str "  " (:label r) ": " (:duration-ms r) "ms status " (:status r)
                        (when (:error r) (str " [ERROR: " (:error r) "]")))))
        (println "\nSTEP 3 — bench runner/run! (auto-classify ON) durations:")
        (doseq [r step-3]
          (println (str "  " (:label r) ": " (:duration-ms r) "ms status " (:status r)
                        (when (:error r) (str " [ERROR: " (:error r) "]")))))
        {:step-1 step-1 :step-2 step-2 :step-3 step-3 :step-4 step-4})
      (finally
        (runner-stop!))))))

(comment
  (require '[diagnose-llm-hang :as d] :reload)
  (d/diagnose!))
