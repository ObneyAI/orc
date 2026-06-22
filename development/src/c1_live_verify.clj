(ns c1-live-verify
  "C-1 MANDATORY live-verify — 3-way comparison proving that injecting a
   hand-authored principle materially changes the model's emitted tree.

   Per the user discipline (memory: infra-existence-is-not-quality), we
   don't just verify 'the pipe works' — we verify the model DEMONSTRABLY
   ACTS DIFFERENTLY when the principle is in scope vs not.

   Three runs:

     Run X (control, no :context set)        → baseline tree structure
     Run Y (principle injected, matching task-class UUID)
                                              → tree should visibly reflect
                                                the principle's pattern
     Run Z (principle injected, WRONG UUID)
                                              → behavior should match X
                                                (principle NOT applied)

   Observability strategy (U10): after each run we query the event store
   for the :rlm/researcher-iterations event tagged with [:tick tick-id]
   to capture each iteration's raw model :code output. This is independent
   of whether downstream SCI execution succeeded — even if the model's
   emit-tree! response gets parse-rejected, we still see the model's
   raw text and can compare X vs Y at the source level.

   Pass criteria (all three must hold):
     - X reproduces a baseline tree pattern
     - Y's iter-1 model :code visibly includes the principle's pattern
       hint (e.g., :max-concurrency 3 on map-each)
     - Z's iter-1 model :code matches X's pattern (principle not applied)"
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.test-support.seed-principles :as seed]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; =============================================================================
;; Context setup
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c1-live-verify-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "c1-live"}))
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

(defn- save-edn! [filename data]
  (let [dir "development/bench/generalization-results"]
    (.mkdirs (io/file dir))
    (let [path (str dir "/" filename)]
      (spit path (with-out-str (pp/pprint data)))
      path)))

;; =============================================================================
;; Task setup — small synthetic chunked document, no external fixtures
;; =============================================================================

(defn- synthetic-doc []
  (let [sections
        ["Section 1: On May 15, 2026, OpenAI announced a partnership with Microsoft for GPT-5. Sam Altman and Satya Nadella signed in Seattle."
         "Section 2: Stanford University launched an AI alignment program on June 1, 2026. Professor Smith leads with DoE funding through 2028."
         "Section 3: The European Commission published draft AI rules on June 15, 2026 requiring $1B+ revenue companies to file quarterly safety reports."
         "Section 4: Joint statement on July 1, 2026 from Altman, Amodei, Hassabis committed to coordinated evaluation and quarterly cross-company audits."
         "Section 5: Industry analysts project $50B+ AI safety investment by 2028. Key dates: Q3 2026 audit launch, Q1 2027 reports, Q4 2027 EU deadline."
         "Section 6: Q3-Q4 2026 is the inflection point. September 2026 EU compliance vote is critical."]]
    (str/join "\n\n===\n\n" sections)))

(def task-instruction
  (str "Document available: :document (article with ~6 sections separated by '===').\n\n"
       "Task: Extract structured information. Outputs:\n"
       "  :summary - one-paragraph executive summary\n"
       "  :key-dates - vector of important dates with context\n"
       "  :entities - vector of named people and organizations"))

(defn- workflow-for [label context-config]
  (orc/workflow (str "c1-live-verify-" label)
    (orc/blackboard {:document :string
                     :summary :string
                     :key-dates :string
                     :entities :string})
    (apply orc/repl-researcher "researcher"
           :model "google/gemini-2.5-flash"
           :instruction task-instruction
           :reads [:document]
           :writes [:summary :key-dates :entities]
           :max-iterations 3
           :rlm {:debug? true}
           (when context-config
             [:context context-config]))))

;; =============================================================================
;; Event-store inspection — pull iteration history via U10 event
;; =============================================================================

(defn- find-iterations-event
  "Query the event store for the U10 :rlm/researcher-iterations event
   tagged with [:sheet sheet-id]. Returns the event body or nil."
  [ctx sheet-id]
  (let [events (into [] (es/read (:event-store ctx)
                                  (cond-> {:tags #{[:sheet sheet-id]}
                                           :types #{:rlm/researcher-iterations}}
                                    (:tenant-id ctx) (assoc :tenant-id (:tenant-id ctx)))))]
    (first events)))

(defn- has-bounded-concurrency-in-code?
  "True if the raw model output mentions :max-concurrency with a value > 1
   inside a :map-each form. Crude regex match — sufficient for distinguishing
   'principle applied' vs 'principle not applied' on iter-1 code."
  [code-str]
  (boolean (re-find #":max-concurrency\s+([2-9]|[1-9]\d+)" (str code-str))))

(defn- has-map-each?
  "True if the raw model output contains a :map-each form."
  [code-str]
  (boolean (re-find #":map-each" (str code-str))))

;; =============================================================================
;; Run a variant
;; =============================================================================

(defn- run-task!
  "Run one variant of the live-verify. Returns a result map including
   the iteration history extracted from the :rlm/researcher-iterations event."
  [label context-config]
  (let [ctx (create-context)]
    (try
      (executor/setup-providers!)
      ;; Seed the principle into THIS context's event store.
      ;; Y will retrieve it (matching task-class-id).
      ;; Z will NOT retrieve it (mismatched task-class-id).
      ;; X has no :context so it won't try to retrieve at all.
      (when context-config
        (seed/seed-one! ctx seed/bounded-map-each-for-chunked-extraction))
      (Thread/sleep 200)
      ;; Build + execute
      (let [wf (workflow-for label context-config)
            sheet-id (orc/build-workflow! ctx wf)
            t0 (System/currentTimeMillis)
            result (orc/execute ctx sheet-id
                                {:document (synthetic-doc)}
                                :timeout-ms 120000)
            elapsed (- (System/currentTimeMillis) t0)
            ;; U10 — pull the iteration history from the event store
            _ (Thread/sleep 500)
            iter-event (find-iterations-event ctx sheet-id)
            iterations (:iterations iter-event)
            iter-1-code (when (seq iterations) (str (:code (first iterations))))
            iter-1-error (when (seq iterations) (:error (first iterations)))
            outcome {:label label
                     :context-config context-config
                     :status (:status result)
                     :error (:error result)
                     :elapsed-ms elapsed
                     :sheet-id sheet-id
                     :iteration-count (:iteration-count iter-event)
                     :iter-1-code iter-1-code
                     :iter-1-error iter-1-error
                     :iter-1-bounded-concurrency? (has-bounded-concurrency-in-code? iter-1-code)
                     :iter-1-has-map-each? (has-map-each? iter-1-code)
                     :all-iterations iterations
                     :usage (:usage result)}]
        (save-edn! (str "c1-live-verify-run-" label "_" (System/currentTimeMillis) ".edn")
                   outcome)
        outcome)
      (finally (stop-context ctx)))))

;; =============================================================================
;; Main runner
;; =============================================================================

(defn run! []
  (println "\n=== C-1 LIVE VERIFY — 3-WAY COMPARISON ===\n")
  (println "Principle under test: bounded-map-each-for-chunked-extraction")
  (println "Task class UUID:     " seed/chunked-extraction-task-class-id)
  (println "Model:               google/gemini-2.5-flash")
  (println "Observability:       U10 :rlm/researcher-iterations event\n")

  (let [wrong-task-class-uuid #uuid "ffffffff-c1c1-4001-b005-d0c0a0a0a0a5"

        run-x (run-task! "X-control" nil)

        run-y (run-task! "Y-injected"
                         {:tree-id seed/chunked-extraction-task-class-id
                          :self-learning? true
                          :include-patterns true
                          :include-failures true
                          :problem-type "problem:Extraction"})

        run-z (run-task! "Z-wrong-uuid"
                         {:tree-id wrong-task-class-uuid
                          :self-learning? true
                          :include-patterns true
                          :include-failures true
                          :problem-type "problem:Extraction"})]

    (println "\n--- Results table ---")
    (printf "%-13s | %-9s | iter1 has :map-each | iter1 bounded?\n" "Run" "Status")
    (println "--------------+-----------+----------------------+----------------")
    (doseq [r [run-x run-y run-z]]
      (printf "%-13s | %-9s | %-20s | %s\n"
              (:label r)
              (str (:status r))
              (str (:iter-1-has-map-each? r))
              (str (:iter-1-bounded-concurrency? r))))

    (println "\n--- Iter-1 raw code snippets (first 600 chars) ---")
    (doseq [r [run-x run-y run-z]]
      (println "\n>>>" (:label r) "<<<")
      (println (subs (or (:iter-1-code r) "") 0 (min 600 (count (or (:iter-1-code r) ""))))))

    (let [x-bounded (:iter-1-bounded-concurrency? run-x)
          y-bounded (:iter-1-bounded-concurrency? run-y)
          z-bounded (:iter-1-bounded-concurrency? run-z)

          y-applies-principle (and y-bounded (not x-bounded))
          z-matches-x (= x-bounded z-bounded)
          verdict (cond
                    (and y-applies-principle z-matches-x) :pass
                    y-applies-principle :pass-y-z-ambiguous
                    :else :fail)]

      (println "\n--- Pass criteria ---")
      (printf "X iter1 has bounded :map-each concurrency?  %s\n" x-bounded)
      (printf "Y iter1 has bounded :map-each concurrency?  %s\n" y-bounded)
      (printf "Z iter1 has bounded :map-each concurrency?  %s\n" z-bounded)
      (printf "Principle visibly applied in Y vs X?         %s\n" y-applies-principle)
      (printf "Z behavior matches X (not Y)?                %s\n" z-matches-x)

      (println "\n=== VERDICT:" verdict "===")
      (when (not= :pass verdict)
        (println "\nRoot-cause considerations:")
        (println "  - Did iter-1 even emit a :map-each at all?")
        (println "    Inspect the iter-1 code snippet above.")
        (println "  - If Y had :max-concurrency but X did too, the model")
        (println "    already uses the pattern by default; pick a more")
        (println "    differentiating principle for the next iteration.")
        (println "  - If neither had :map-each, the task may not justify it.")
        (println "    Use a longer document to push chunking as a design choice."))

      {:verdict verdict
       :run-x run-x
       :run-y run-y
       :run-z run-z})))

(comment
  (run!))
