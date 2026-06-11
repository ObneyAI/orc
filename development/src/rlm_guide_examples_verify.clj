(ns rlm-guide-examples-verify
  "Ground-truth verification: execute the exact DSL examples shown in
   docs/RLM-GUIDE.md against a real LLM and confirm they work as documented.

   Two examples are tested:

   1. Composition pattern: workflow with three children in a sequence —
      pre-process LLM → repl-researcher → post-process LLM
   2. Basic usage: workflow whose root is a single repl-researcher node

   For both, we assert :status :success and that all declared :writes
   keys appear in the final outputs."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.orc.orc-service.core.executor :as executor]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rlm-guide-verify-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "verify"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store :cache cache :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :openrouter
                  :event-pubsub ps
                  ::cache-dir cache-dir}
        processors (reduce-kv (fn [acc proc-name {:keys [handler-fn topics]}]
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

;; =============================================================================
;; Example #1 — Composition pattern (verbatim from RLM-GUIDE.md)
;; =============================================================================

(defn- composition-workflow []
  (orc/workflow "research-pipeline"
    (orc/blackboard
      {:raw-input          :string
       :cleaned-input      :string
       :researched-summary :string
       :final-report       :string})

    (orc/sequence "main"
      (orc/llm "pre-process"
        :model "google/gemini-2.5-flash"
        :instruction "Clean the raw input. Remove extra whitespace, normalize punctuation."
        :reads  [:raw-input]
        :writes [:cleaned-input])

      (orc/repl-researcher "research"
        :model "google/gemini-2.5-flash"
        :instruction "Produce a one-paragraph summary highlighting key facts."
        :reads  [:cleaned-input]
        :writes [:researched-summary]
        :max-iterations 3
        :rlm true)

      (orc/llm "post-process"
        :model "google/gemini-2.5-flash"
        :instruction "Wrap the summary as a brief executive report."
        :reads  [:researched-summary]
        :writes [:final-report]))))

;; =============================================================================
;; Example #2 — Basic usage (verbatim from RLM-GUIDE.md, simplified for test)
;; =============================================================================

(defn- basic-workflow []
  (orc/workflow "risk-analysis"
    (orc/blackboard
      {:document          :string
       :risk-matrix       :string
       :executive-summary :string})

    (orc/repl-researcher "researcher"
      :model "google/gemini-2.5-flash"
      :instruction "Analyze the document for risks and obligations. Produce
                    a brief :risk-matrix and :executive-summary."
      :reads  [:document]
      :writes [:risk-matrix :executive-summary]
      :max-iterations 5
      :rlm true)))

;; =============================================================================
;; Verification harness
;; =============================================================================

(defn- short-doc-text []
  (str "On May 15, 2026, OpenAI announced a partnership with Microsoft "
       "to release GPT-5. The agreement requires quarterly milestone "
       "deliveries with $10M penalties for delays. Termination requires "
       "60-day written notice."))

(defn- assert-example
  "Run the example, assert structural expectations, print a tabular result."
  [example-name workflow inputs expected-writes-keys]
  (let [ctx (create-context)]
    (try
      (executor/setup-providers!)
      (let [sheet-id (orc/build-workflow! ctx workflow)
            t0 (System/currentTimeMillis)
            result (orc/execute ctx sheet-id inputs :timeout-ms 180000)
            elapsed (- (System/currentTimeMillis) t0)
            outputs (:outputs result)
            present-keys (set (filter outputs expected-writes-keys))
            missing (remove present-keys expected-writes-keys)
            non-empty? (fn [k] (let [v (get outputs k)] (and v (not (= "" (str v))))))
            non-empty-keys (filter non-empty? expected-writes-keys)]
        (println "\n========================================")
        (println example-name)
        (println "========================================")
        (println "Sheet-id (deterministic):" sheet-id)
        (println "Wall time:                " elapsed "ms")
        (println "Result :status:           " (:status result))
        (println "Expected :writes keys:    " expected-writes-keys)
        (println "Keys actually populated:  " (vec present-keys))
        (println "Non-empty values:         " (vec non-empty-keys))
        (println "Missing keys:             " (vec missing))
        (println "Result :error:            " (or (:error result) "<none>"))
        (println)
        (println "VERDICT:" (if (and (= :success (:status result))
                                     (empty? missing)
                                     (= (set expected-writes-keys) (set non-empty-keys)))
                              "✓ PASS — docs example works as documented"
                              "✗ FAIL — see details above"))
        {:example example-name
         :status (:status result)
         :elapsed-ms elapsed
         :missing-keys (vec missing)
         :present-keys (vec present-keys)
         :non-empty-keys (vec non-empty-keys)
         :error (:error result)})
      (finally (stop-context ctx)))))

(defn run! []
  (println "\n=== RLM GUIDE EXAMPLES — GROUND TRUTH VERIFICATION ===")
  (println "Running each documented DSL example against a real LLM.")
  (println "All examples MUST produce :status :success with all :writes keys populated.")

  (let [r1 (assert-example
             "EXAMPLE #1 — Composition (pre-process → repl-researcher → post-process)"
             (composition-workflow)
             {:raw-input (str "  On May 15,, 2026   OpenAI announced a partnership with Microsoft "
                              "to release  GPT-5.")}
             [:cleaned-input :researched-summary :final-report])
        r2 (assert-example
             "EXAMPLE #2 — Basic usage (single repl-researcher as workflow root)"
             (basic-workflow)
             {:document (short-doc-text)}
             [:risk-matrix :executive-summary])]
    (println "\n========================================")
    (println "SUMMARY")
    (println "========================================")
    (doseq [r [r1 r2]]
      (println "•" (:example r) "→"
               (if (and (= :success (:status r)) (empty? (:missing-keys r)))
                 "PASS"
                 "FAIL")))
    [r1 r2]))

(comment (run!))
