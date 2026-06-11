(ns recursive-rlm-live-verify
  "Live verification for R-1: Core recursive emit-tree! loop with a REAL LLM.

   The two-step task: the model is asked to (a) summarize the document and
   (b) count distinct entities, returning BOTH :summary and :entity-count.
   A single emit-tree! tree CAN do both, but the natural shape is:

     iteration 1: (emit-tree! [chunk → summarize → :summary])
                  ↓ recur
     iteration 2: (let [s (get-var :summary)
                        entities (llm \"count entities\" :reads [:summary])]
                    (final! {:summary s :entity-count (:count entities)}))

   This proves the model can:
     - Use emit-tree! WITHOUT it being a terminator
     - See the tree's outputs after the recur via (get-var :summary)
     - Run inline (llm ...) follow-up reasoning
     - Call (final! {...}) to terminate with composite outputs

   For full production-truth verification per the project rule
   'mocks are dev-only, live runs are mandatory before declaring slice done.'"
  (:require [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [litellm.router]
            [dscloj.core]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/recursive-rlm-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "live-verify"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :openrouter
                  :event-pubsub ps
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ processor] (:processors ctx)] (tp/stop processor))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [cache (:cache ctx)] (kv/stop cache))
  (when-let [event-store (:event-store ctx)] (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [child (.listFiles f)] (.delete child))
        (.delete f)))))

(defn- build-sheet! [ctx]
  (executor/setup-providers!)
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "R-1 Recursive Live Verify"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :document :string))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :summary :string))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :entity-count :int))
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :repl-researcher :parent-id seq-id))
          node-id (-> node-result :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                              sheet-id node-id
                              (str "You have :document. Produce two outputs:\n"
                                   "- :summary  (one paragraph)\n"
                                   "- :entity-count  (number of distinct named entities)\n\n"
                                   "Use emit-tree! for :summary, then a follow-up llm call for entity count, "
                                   "then final!.")
                              [:document]
                              [:summary :entity-count]
                              []
                              :model "google/gemini-2.5-flash"
                              :max-iterations 6
                              ;; R-1 opt-in flag
                              :rlm {:recursive? true :debug? true}
                              :timeout-ms 120000))
      {:sheet-id sheet-id :node-id node-id})))

(defn- save-edn! [filename data]
  (let [dir "development/bench/generalization-results"]
    (.mkdirs (io/file dir))
    (let [path (str dir "/" filename)]
      (spit path (with-out-str (pp/pprint data)))
      path)))

(defn- doc-text []
  (str "On May 15, 2026, OpenAI announced a partnership with Microsoft to release "
       "a new model called GPT-5. The agreement was signed by Sam Altman (CEO of OpenAI) "
       "and Satya Nadella (CEO of Microsoft) in Seattle. The collaboration will involve "
       "Anthropic's safety researchers as advisors, and Google DeepMind has expressed "
       "interest in similar partnerships. Stanford University announced a related "
       "research initiative on June 1, 2026, focused on AI alignment."))

(defn run! []
  (let [ctx (create-context)]
    (try
      (executor/setup-providers!)
      (let [;; Call execute-repl-researcher-rlm DIRECTLY (not via sheet/execute) so
            ;; the iterations history is visible. Same execution path the production
            ;; processor uses — we're just skipping the Grain tick-tree wrapper to
            ;; surface debug info.
            node {:type :repl-researcher
                  :instruction (str "Document available: :document (a short article, ~450 chars)\n\n"
                                    "Task: Two-step extraction.\n"
                                    "Step 1: Produce a one-paragraph :summary of the document\n"
                                    "Step 2: Count the distinct named entities (people, organizations, dates) → :entity-count\n\n"
                                    "Approach: use emit-tree! for the summary step, then in the next iteration "
                                    "call (llm ...) to count entities and (final!) with both results.")
                  :reads [:document]
                  :writes [:summary :entity-count]
                  :model "google/gemini-2.5-flash"
                  :max-iterations 6
                  :rlm {:recursive? true :debug? true}
                  :timeout-ms 120000}
            blackboard {:document {:key :document
                                   :schema :string
                                   :value (doc-text)
                                   :version 1}}
            _ (println "\n=== R-1 RECURSIVE RLM LIVE VERIFY ===")
            _ (println "Task: summarize doc + count distinct entities (two-step)")
            _ (println "Mode :recursive?:" (get-in node [:rlm :recursive?]))

            t0 (System/currentTimeMillis)
            ;; Intercept dscloj/predict to log raw LLM output for debugging.
            ;; Capture the FUNCTION VALUE before with-redefs (not a var ref —
            ;; that would recurse infinitely once the redef takes effect).
            real-completion-fn @#'litellm.router/completion
            real-predict-fn @#'dscloj.core/predict
            call-num (atom 0)
            result (with-redefs [;; Capture raw LLM response text at the router level
                                 litellm.router/completion
                                 (fn [provider-config req]
                                   (let [r (real-completion-fn provider-config req)
                                         msg (-> r :choices first :message)]
                                     (println "\n========== litellm raw response ==========")
                                     (println "Message keys:" (keys msg))
                                     (println "Content:" (pr-str (:content msg)))
                                     (println "Reasoning:" (pr-str (:reasoning msg)))
                                     (println "Tool-calls:" (pr-str (:tool_calls msg)))
                                     (println "Finish reason:" (-> r :choices first :finish_reason))
                                     (println "Full message (first 2000):"
                                              (let [s (pr-str msg)]
                                                (subs s 0 (min 2000 (count s)))))
                                     (println "Usage:" (:usage r))
                                     r))
                                 dscloj.core/predict
                                 (fn [provider module inputs opts]
                                   (let [n (swap! call-num inc)
                                         is-phase1? (some #(= :code (:name %)) (:outputs module))
                                         r (real-predict-fn provider module inputs opts)]
                                     (when is-phase1?
                                       (println "\n========== PHASE 1 CALL #" n " ==========")
                                       (println "Task instruction:" (subs (str (:task inputs)) 0 (min 200 (count (str (:task inputs)))))))
                                     r))]
                     (executor/execute-repl-researcher-rlm node blackboard :openrouter ctx))
            elapsed (- (System/currentTimeMillis) t0)

            _ (println "\nElapsed wall-time:" elapsed "ms")
            _ (println "Result status:" (:status result))
            _ (println "Result :summary:" (when-let [s (get-in result [:outputs :summary])]
                                            (subs (str s) 0 (min 200 (count (str s))))))
            _ (println "Result :entity-count:" (get-in result [:outputs :entity-count]))
            _ (println "Cumulative thinking-ms:" (:cumulative-thinking-ms result))
            _ (println "Cumulative tree-ms:" (:cumulative-tree-ms result))

            ;; Events are all tagged under the child Phase 2 tick. Read everything
            ;; from the tenant scope.
            events (into [] (es/read (:event-store ctx)
                              {:tenant-id (:tenant-id ctx)}))
            _ (println "\n--- Event store evidence ---")
            _ (println "Total events:" (count events))
            _ (println "Tree-execution-completed events:"
                       (count (filter #(= :sheet/rlm-tree-execution-completed (:event/type %)) events)))
            _ (println "Rlm-tree-node-completed events:"
                       (count (filter #(= :sheet/rlm-tree-node-completed (:event/type %)) events)))

            ;; Iteration history is buried inside the result — surface it so we
            ;; can see EXACTLY what the model wrote each iteration + what came
            ;; back from SCI execution.
            iterations (or (:iterations result) [])
            _ (println "\n--- Iteration history (" (count iterations) "iterations) ---")
            _ (doseq [[i it] (map-indexed vector iterations)]
                (println "\n--- ITERATION" (inc i) "---")
                (println "Code:" (subs (str (:code it)) 0 (min 600 (count (str (:code it))))))
                (when (:result it)
                  (println "Result:" (subs (str (:result it)) 0 (min 200 (count (str (:result it)))))))
                (when (:stdout it)
                  (println "Stdout:" (subs (str (:stdout it)) 0 (min 200 (count (str (:stdout it)))))))
                (when (:error it)
                  (println "Error:" (:error it)))
                (println "Vars created:" (:vars-created it)))

            saved-path (save-edn!
                         (str "recursive-rlm-live-verify_" (System/currentTimeMillis) ".edn")
                         {:result-status (:status result)
                          :result-outputs (:outputs result)
                          :result-error (:error result)
                          :elapsed-wall-ms elapsed
                          :event-types (frequencies (map :event/type events))
                          ;; FULL iteration history for debugging
                          :iterations iterations
                          :cumulative-thinking-ms (:cumulative-thinking-ms result)
                          :cumulative-tree-ms (:cumulative-tree-ms result)
                          :usage (:usage result)})
            _ (println "\nFull EDN saved to:" saved-path)]
        result)
      (finally
        (stop-context ctx)))))

(comment
  (run!)
  )
