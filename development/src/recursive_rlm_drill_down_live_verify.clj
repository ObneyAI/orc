(ns recursive-rlm-drill-down-live-verify
  "Live verification for R-2: Drill-down primitives with a REAL LLM.

   The task is engineered to encourage the model to drill down:
     - Document contains 3 mock 'chunks' to process via map-each
     - One chunk is corrupted/noisy (sentinel) — meant to push the sub-LLM
       toward a failure or low-quality output, surfacing a :partial outcome
     - Task explicitly says: 'After the tree, inspect which chunk failed
       (if any) and decide whether to retry, then final!'

   Pass criterion (per the issue): in iter 2's emitted code, at least one of
   the 5 drill-down primitives (`tree-detail`, `tree-trajectory`,
   `tree-failures`, `node-output`, `node-input-profile`) is called.

   For full production-truth verification per the project rule
   'mocks are dev-only; live runs are mandatory before declaring slice done.'"
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.interface.schemas]
            [litellm.router]
            [dscloj.core]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/recursive-rlm-drill-down-live-" (random-uuid))
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

(defn- save-edn! [filename data]
  (let [dir "development/bench/generalization-results"]
    (.mkdirs (io/file dir))
    (let [path (str dir "/" filename)]
      (spit path (with-out-str (pp/pprint data)))
      path)))

(defn- doc-text []
  ;; 3 chunks separated by ===CHUNK===. The MIDDLE chunk is the sentinel —
  ;; a deliberate poison-pill the sub-LLM is likely to fail on. We frame it
  ;; as if it's part of the input data, not a system instruction, so it
  ;; surfaces as a per-chunk failure rather than getting filtered.
  (str "===CHUNK===\n"
       "Section 1 — Partnership Announcement\n"
       "On May 15, 2026, OpenAI announced a partnership with Microsoft "
       "to release a new model called GPT-5. Sam Altman and Satya Nadella "
       "signed the agreement in Seattle.\n"
       "===CHUNK===\n"
       "Section 2 — CORRUPTED DATA\n"
       "##!@#$%^&*()_+ binary noise }}}{{ "
       "qwertyqwertyqwerty zzzzzzzzzzz "
       "INVALID: cannot extract anything meaningful from this section. "
       "The data here is intentionally garbled and contains no real names, "
       "dates, or facts to extract.\n"
       "===CHUNK===\n"
       "Section 3 — Stanford Initiative\n"
       "Stanford University announced a related research initiative on "
       "June 1, 2026, focused on AI alignment, led by Professor Smith.\n"))

(defn- iter-code [it] (str (:code it "")))

(defn- primitives-used-in-code [code-str]
  (let [primitives ["tree-detail" "tree-trajectory" "tree-failures"
                    "node-output" "node-input-profile"]]
    (into [] (filter (fn [p] (str/includes? code-str (str "(" p))) primitives))))

(defn run! []
  (let [ctx (create-context)]
    (try
      (executor/setup-providers!)
      (let [node {:type :repl-researcher
                  :instruction
                  (str "Document available: :document (3 sections separated by ===CHUNK===)\n\n"
                       "Task: Extract entities from each section, then produce a final summary.\n\n"
                       "Approach:\n"
                       "  Step 1 — call (emit-tree! ...) to run a map-each over the chunks, with\n"
                       "    each leaf an :llm node that extracts entities from one chunk and\n"
                       "    writes them to :section-entities.\n"
                       "  Step 2 — after the tree returns, look at :tree-results. The summary\n"
                       "    has :status, :nodes-succeeded, :nodes-failed. If it's :partial or has\n"
                       "    failures, you may use the drill-down primitives (tree-failures,\n"
                       "    tree-detail, etc.) described above to investigate. Then call (final!\n"
                       "    {:all-entities ... :report \"a short report\"}) with whatever info\n"
                       "    you have.\n\n"
                       "IMPORTANT: do not use [:code ...] inside the tree — the only tree node\n"
                       "types you may emit are :sequence, :parallel, :fallback, :map-each, :llm,\n"
                       ":chunk-document, :aggregate, :condition, and :final.")
                  :reads [:document]
                  :writes [:all-entities :report]
                  :model "google/gemini-2.5-flash"
                  :max-iterations 5
                  :rlm {:recursive? true :debug? true}
                  :timeout-ms 180000}
            blackboard {:document {:key :document
                                   :schema :string
                                   :value (doc-text)
                                   :version 1}}
            _ (println "\n=== R-2 DRILL-DOWN PRIMITIVES LIVE VERIFY ===")
            _ (println "Task: 3-chunk extraction; section 2 is a sentinel/corrupted")
            _ (println "Model:" (:model node))
            _ (println "Mode :recursive?:" (get-in node [:rlm :recursive?]))
            _ (println "Pass criterion: iter 2+ code calls at least one drill-down primitive")

            t0 (System/currentTimeMillis)
            result (executor/execute-repl-researcher-rlm node blackboard :openrouter ctx)
            elapsed (- (System/currentTimeMillis) t0)

            iterations (or (:iterations result) [])
            ;; Iter 1 emits the tree; drill-down primitives are only relevant
            ;; in iter 2+ (after the tree returned). Look there for usage.
            post-tree-iters (drop 1 iterations)
            all-post-tree-code (str/join "\n\n" (map iter-code post-tree-iters))
            primitives-found (primitives-used-in-code all-post-tree-code)]

        (println "\n--- Result ---")
        (println "Status:                  " (:status result))
        (println "Wall-clock elapsed:      " elapsed "ms")
        (println "Cumulative thinking-ms:  " (:cumulative-thinking-ms result))
        (println "Cumulative tree-ms:      " (:cumulative-tree-ms result))
        (println "Iteration count:         " (count iterations))
        (println "Output :report:          " (let [v (str (get-in result [:outputs :report]))]
                                                (subs v 0 (min 200 (count v)))))
        (println "Output :all-entities sample:"
                 (let [v (str (get-in result [:outputs :all-entities]))]
                   (subs v 0 (min 200 (count v)))))
        (println "Result :error:           " (or (:error result) "<none>"))

        (println "\n--- Iteration history ---")
        (doseq [[i it] (map-indexed vector iterations)]
          (println "\n--- ITERATION" (inc i) "---")
          (println "Code:")
          (println (subs (str (:code it)) 0 (min 800 (count (str (:code it))))))
          (when (:error it) (println "Error:" (:error it))))

        (println "\n--- Drill-down primitive usage in iter 2+ ---")
        (if (seq primitives-found)
          (do
            (println "Primitives used:        " primitives-found)
            (println "VERDICT:                 ✓ PASS — drill-down primitive exercised in iter 2+"))
          (do
            (println "Primitives used:         (none)")
            (println "VERDICT:                 ✗ FAIL — no drill-down primitive call detected")))

        (let [saved (save-edn!
                      (str "recursive-rlm-drill-down-live-verify_" (System/currentTimeMillis) ".edn")
                      {:result-status (:status result)
                       :result-outputs (:outputs result)
                       :result-error (:error result)
                       :elapsed-wall-ms elapsed
                       :iterations iterations
                       :primitives-used primitives-found
                       :cumulative-thinking-ms (:cumulative-thinking-ms result)
                       :cumulative-tree-ms (:cumulative-tree-ms result)
                       :usage (:usage result)})]
          (println "\nFull EDN saved to:" saved))
        {:status (:status result)
         :primitives-used primitives-found
         :iteration-count (count iterations)})
      (finally (stop-context ctx)))))

(comment (run!))
