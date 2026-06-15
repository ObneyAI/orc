(ns s18-live-verify
  "S18 live verification — exercises the recursive-RLM discovery wiring
   against a REAL Grain event-store and a REAL OpenRouter LLM, with the
   AFK-derived ontology-discovery seed corpus loaded into the corpus
   the session retrieves from.

   This is the manual gate required by Discipline #4 (synthetic tests
   are the floor; live verification is the ceiling).

   USAGE (from a REPL with :dev alias active and OPENROUTER_API_KEY set):

     (require '[s18-live-verify :as v])
     (v/run-once! {:model \"gemini-3-flash-preview\"})

   The session:
   - Seeds the baseline corpus (so classify-behaviors can retrieve the
     5 AFK-derived ontology-discovery patterns).
   - Constructs a real run-discovery! session with an ontology-id grant
     so the S19 tools + S20 card are available.
   - Drives the session against a small synthetic source.
   - Captures the discovery output + rlm-trace for adversarial review:
     - Did the model call classify-behaviors and retrieve a pattern?
     - Did it adapt or mint a fresh shape?
     - Are the concept-drafts / relationship-drafts grounded?
   - Threads the output through compile-discovery-source! +
     deterministic-skeleton/build! to confirm the events ingest cleanly."
  (:require [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            ;; The orc-service todo-processors must be LOADED so they self-
            ;; register in tp/processor-registry*, and STARTED on the pubsub so
            ;; Phase-2 child ticks (emit-tree! execution) are actually driven
            ;; and their completion promises delivered. Without this the
            ;; discovery session designs a tree, dispatches :sheet/tick-tree,
            ;; and then hangs forever waiting for a completion nothing fires —
            ;; the S18 root cause. The bench runner + recursive-rlm tests do
            ;; this same tp/start wiring.
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            ;; The litellm router must have the :openrouter provider registered
            ;; with the api-key BEFORE the discovery session calls dscloj/predict.
            ;; Without it dscloj returns no completion and the executor reports
            ;; "LLM did not generate code" (0 prompt tokens) — the S18-driver
            ;; root cause when run outside the bench runner that registers it.
            [litellm.router :as litellm-router]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn- register-openrouter!
  "Register the :openrouter provider (and the model-suffixed alias) with the
   litellm router, sourcing the key from the OPENROUTER_API_KEY env var ONLY."
  [model]
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set (env var only)" {})))
        base-config {:provider :openrouter
                     :model model
                     :config {:api-base "https://openrouter.ai/api/v1"
                              :api-key api-key}}]
    (litellm-router/register! :openrouter base-config)
    (litellm-router/register! (keyword (str "openrouter/" model)) base-config)))

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/s18-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
        base-ctx {:event-store store
                  :cache cache
                  :tenant-id (random-uuid)
                  ;; Required so the tick-driving processors can execute the
                  ;; Phase-2 tree's :llm leaves against the real provider.
                  :dscloj-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :event-pubsub ps
                  ::cache-dir dir}
        ;; Start every registered todo processor on this pubsub — this is the
        ;; engine that drives child ticks and delivers tree-completion promises.
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

(defn- stop-ctx [ctx]
  (doseq [[_ processor] (:processors ctx)] (tp/stop processor))
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(def fixture-source
  "A small policy-style source fixture for discovery — chosen so the
   small-source DirectExtractionDiscovery pattern should be a good fit."
  (str
   "EMPLOYMENT POLICY (excerpt)\n\n"
   "Section 1. Definitions.\n"
   "  - 'Employee' means a person hired under this policy.\n"
   "  - 'Manager' means an Employee with direct-report responsibility.\n"
   "  - 'Onboarding' means the 90-day period beginning on the Employee's start date.\n\n"
   "Section 2. Roles.\n"
   "  - An Employee may be a Manager. A Manager is an Employee.\n"
   "  - Every Manager supervises one or more Employees.\n\n"
   "Section 3. Onboarding.\n"
   "  - Each Employee completes Onboarding before assuming full duties.\n"
   "  - The Manager assigned at hire conducts weekly reviews during Onboarding.\n\n"
   "Section 4. Constraints.\n"
   "  - An Onboarding period is never itself an Employee; the two are mutually exclusive categories.\n"
   "  - Supervision is transitive: if A supervises B and B supervises C, then A supervises C.\n"))

(defn run-once!
  "Run the S18 live verify ONCE. Returns the discovery output + build
   result + any anomalies surfaced.

   Required env: OPENROUTER_API_KEY"
  [{:keys [model max-iterations debug?]
    :or {model "google/gemini-3-flash-preview"
         max-iterations 6}}]
  (let [ctx (make-ctx)
        oid (random-uuid)]
    (try
      ;; Register the OpenRouter provider with the litellm router (key from
      ;; the env var only). Without this the discovery session's dscloj/predict
      ;; returns no completion.
      (register-openrouter! model)
      ;; Bootstrap the corpus so classify-behaviors has the 5 discovery
      ;; patterns to retrieve.
      (ontology/seed-baseline-corpus! ctx)
      (Thread/sleep 300)  ;; let projections settle

      (println "=== S18 LIVE VERIFY ===")
      (println "Ontology-id (granted scope):" oid)
      (println "Discovery patterns shipped:" (count (ontology/ontology-discovery-patterns)))
      (println "Discovery patterns (HITL-reviewed only):"
               (count (ontology/ontology-discovery-patterns true)))

      (println "\n>>> Running discovery session...")
      (let [discovery-out (ontology/run-discovery!
                            ctx
                            {:ontology-id oid
                             :sources [{:name :policy
                                        :type :text
                                        :content fixture-source}]
                             :model model
                             :budget {:max-iterations max-iterations
                                      :total-budget-ms (* 1000 180)}
                             :debug? debug?})]
        (println "\n=== DISCOVERY OUTPUT ===")
        (println "Status:" (:status discovery-out))
        (println "Patterns offered:" (:patterns-offered discovery-out))
        (println "Concepts drafted:" (count (:emitted-concepts discovery-out)))
        (println "Relationships drafted:" (count (:emitted-relationships discovery-out)))
        (println "Axioms drafted:" (count (:emitted-axioms discovery-out)))
        (println "Token usage:" (:usage discovery-out))
        (println "\n=== RLM TRACE ===")
        (pp/pprint (:rlm-trace discovery-out))
        (println "\n=== ITERATION REASONINGS ===")
        (doseq [r (:iteration-reasonings discovery-out)]
          (println "----")
          (println r))

        (println "\n=== AXIOM DRAFTS (V07) ===")
        (pp/pprint (:emitted-axioms discovery-out))

        (when (= :emitted-drafts (:status discovery-out))
          (println "\n>>> Compiling discovery output → S17 source stub...")
          (let [stub (ontology/compile-discovery-source! ctx oid discovery-out)]
            (println "Stub:")
            (pp/pprint stub)
            (println "\n=== V07 AXIOM INGEST ===")
            (println "Axioms emitted (provenance):"
                     (get-in stub [:discovery-provenance :axioms-emitted]))
            ;; Read the S07 :ontology/axioms projection back to PROVE the
            ;; discovered axioms actually landed in the graph.
            (let [axioms (ai.obney.orc.ontology.core.read-models/get-axioms ctx oid)]
              (println "Axiom projection for ontology-id:")
              (pp/pprint axioms))

            (println "\n>>> Triggering deterministic skeleton build! to ingest...")
            (let [build-fn (requiring-resolve
                             'ai.obney.orc.ontology.core.deterministic-skeleton/build!)
                  build-result (build-fn ctx
                                         {:ontology-id oid
                                          :sources [stub]
                                          :alignment-ontology-id oid})]
              (println "Build status:" (:status build-result))
              (println "Stages run:" (:stages-run build-result))
              (println "Concepts in graph:" (:concepts-count build-result))
              (println "Relationships in graph:" (:relationships-count build-result))
              {:discovery discovery-out
               :stub stub
               :axioms (ai.obney.orc.ontology.core.read-models/get-axioms ctx oid)
               :build build-result})))

        (println "=== END S18 LIVE VERIFY ===")
        {:discovery discovery-out})
      (finally
        (stop-ctx ctx)))))
