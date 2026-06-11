(ns c1-pipeline-probe
  "C-1 PROBE — exercise the EXISTING ontology pipeline end-to-end for an
   RLM-domain principle. Goal: capture what the model would actually see
   when a hand-authored principle is prepended to its instruction.

   This is the adversarial-quality audit per the user caveat. Before
   building any parallel infrastructure, validate that the existing
   record-tree-strength → get-tree-profile → format-context-for-llm
   pipeline produces useful output for RLM tree-design principles.

   Pipeline under test:

     1. Author principle via :ontology/record-tree-strength
        (existing command, no schema changes)

     2. Read profile via ontology/get-tree-profile
        (existing read model)

     3. Build ontology context via ontology/build-ontology-context
        (existing retrieval, full hybrid)

     4. Format for LLM via ontology/format-context-for-llm
        (existing formatter)

     5. Inspect the output. Decide:
        (a) Format is good — ship C-1 with existing pipeline, ~0 new code
        (b) Format is partially good — surgically extend format-context-for-llm
        (c) Format is wrong for RLM — build parallel pipeline as PRD originally specified

   Run from REPL:
     (require '[c1-pipeline-probe :as p] :reload)
     (p/run!)"
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.interface.schemas]
            [clojure.pprint :as pp]))

;; =============================================================================
;; Task-class UUIDs — the C-1 seed key for the document-analysis benchmark.
;; This is the only one used in the probe; the full seed file will add UUIDs
;; for risk-analysis, contract-comparison, legal-issue-detection, document-analysis.
;; =============================================================================

(def document-analysis-task-class-id
  "Stable UUID identifying the document-analysis benchmark task class.
   Used as the :tree-id field in hand-authored principles so retrieval finds
   them when a repl-researcher node is configured for this task class."
  #uuid "00000000-c1c1-4001-b001-d0c0a0a0a0a1")

;; =============================================================================
;; Test context (in-memory event store + cache + processors)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c1-probe-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "c1-probe"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :event-pubsub ps
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

;; =============================================================================
;; The hand-authored principle for the probe — a tree-design principle
;; about bounding map-each concurrency on chunked-extraction tasks.
;;
;; Fits the existing record-tree-strength schema:
;;   :action-taken = {:type :target :reason}
;;     :type   → the named pattern URI suffix
;;     :target → the tree-DSL snippet (the schema accepts :any)
;;     :reason → the rationale
;; =============================================================================

(def bounded-map-each-principle
  {:command/name :ontology/record-tree-strength
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :tree-id document-analysis-task-class-id
   :pattern-uri "success:BoundedMapEach"
   :confidence 1.0
   :evidence-trace-ids [#uuid "00000000-c1c1-e1de-0001-000000000001"]
   :avg-score 0.95

   :domain-type "rlm-tree-design"
   :context-conditions {:task-class :document-analysis
                        :input-shape :large-chunked-text
                        :symptom :rate-limit-risk-on-unbounded-parallelism}
   :action-taken {:type "BoundedMapEach"
                  :target "[:map-each {:from :chunks :as :chunk :into :results :max-concurrency 3} [:llm {...per-chunk...}]]"
                  :reason "Sub-LLM rate limits exhaust on unbounded concurrency; bound to 3 for chunked extraction"}
   :expected-outcome "Successful per-chunk extraction without rate-limit failures on documents with 6+ chunks"})

;; =============================================================================
;; Probe execution
;; =============================================================================

(defn run! []
  (let [ctx (create-context)]
    (try
      (println "\n=== C-1 PIPELINE PROBE ===")
      (println "Goal: capture what the model would see if we use the EXISTING")
      (println "ontology pipeline (record-tree-strength → get-tree-profile →")
      (println "format-context-for-llm) to inject an RLM-domain principle.\n")

      ;; STEP 1 — Write the principle via existing command
      (println "--- STEP 1: Authoring principle via :ontology/record-tree-strength ---")
      (let [cmd-result (cp/process-command
                         (assoc ctx :command bounded-map-each-principle))]
        (println "Command result :events:")
        (doseq [e (:command-result/events cmd-result)]
          (println "  Event type:" (:event/type e))
          (println "  Body keys:" (vec (keys (dissoc e :event/type :event/id :event/timestamp
                                                     :event/tags))))))

      (Thread/sleep 200)  ;; let read model catch up

      ;; STEP 2 — Read profile via existing read model
      (println "\n--- STEP 2: Reading back via ontology/get-tree-profile ---")
      (let [profile (ontology/get-tree-profile ctx document-analysis-task-class-id)]
        (println "Profile keys:" (vec (keys profile)))
        (println "Profile :strengths:")
        (pp/pprint (:strengths profile))
        (println "Profile :weaknesses:")
        (pp/pprint (:weaknesses profile))

        ;; STEP 3 — Build ontology context via existing helper
        (println "\n--- STEP 3: build-ontology-context ---")
        (let [oc (ontology/build-ontology-context ctx
                                                  {:problem-type "problem:Extraction"
                                                   :tree-id document-analysis-task-class-id
                                                   :self-learning? true})]
          (println "build-ontology-context keys:" (vec (keys oc)))
          (println "  :recommended-patterns count:" (count (:recommended-patterns oc)))
          (println "  :patterns-to-avoid count:" (count (:patterns-to-avoid oc)))
          (when (seq (:recommended-patterns oc))
            (println "  First recommended-pattern:")
            (pp/pprint (first (:recommended-patterns oc))))

          ;; STEP 4 — Format for LLM injection
          (println "\n--- STEP 4: format-context-for-llm ---")
          (let [formatted (ontology/format-context-for-llm oc
                                                            {:include #{:patterns :failures}
                                                             :max-items 5})]
            (println "Formatted markdown (raw):")
            (println "=== BEGIN ===")
            (println formatted)
            (println "=== END ===")
            (println "\nLength:" (count (or formatted "")) "chars")

            ;; STEP 5 — Try build-actionable-context too (the SELF-LEARNING-MANUAL fn)
            (println "\n--- STEP 5: build-actionable-context (SELF-LEARNING-MANUAL fn) ---")
            (let [actionable (ontology/build-actionable-context ctx
                                                                document-analysis-task-class-id
                                                                "problem:Extraction"
                                                                {:max-items 5})]
              (println "build-actionable-context :formatted-context:")
              (println "=== BEGIN ===")
              (println (:formatted-context actionable))
              (println "=== END ===")
              (println "  has-patterns?:" (:has-patterns? actionable))
              (println "  strength-count:" (:strength-count actionable))
              (println "  weakness-count:" (:weakness-count actionable))))))

      (println "\n=== PROBE COMPLETE ===")
      (println "Inspect the formatted output above and decide:")
      (println "  (a) Format is GOOD for RLM tree-design principles → ship C-1 with existing pipeline")
      (println "  (b) Format is PARTIALLY good → surgically extend format-context-for-llm")
      (println "  (c) Format is WRONG for RLM → build parallel build-rlm-principles-section")
      (finally (stop-context ctx)))))

(comment
  (run!))
