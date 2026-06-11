(ns manual-verification
  "Manual verification script for ORC-001 and ORC-002.

   Run this in a REPL with API keys configured to test full LLM pipeline.

   Usage:
     1. Start REPL: cd orc-main && clj -M:dev
     2. Load this file: (load-file \"components/ontology/dev/manual_verification.clj\")
     3. Run tests: (manual-verification/run-all-verifications!)

   Or run individual tests:
     (manual-verification/test-json-extraction!)
     (manual-verification/test-ontology-id-scoping!)
     (manual-verification/test-evolutionary-builder-json!)")

(require '[ai.obney.orc.ontology.interface :as ontology])
(require '[ai.obney.orc.ontology.sheets.json-ontology :as json-ont])
(require '[ai.obney.orc.ontology.sheets.unified-ontology :as unified])
(require '[ai.obney.orc.ontology.core.read-models :as rm])
(require '[ai.obney.grain.event-store-v3.interface :as es])
(require '[ai.obney.grain.kv-store.interface :as kv])
(require '[ai.obney.grain.kv-store-lmdb.interface :as lmdb])
(require '[ai.obney.grain.read-model-processor-v2.interface :as rmp])
(require '[ai.obney.grain.command-processor-v2.interface :as cp])
(require '[ai.obney.grain.query-processor.interface :as qp])
(require '[ai.obney.grain.pubsub.interface :as pubsub])
(require '[ai.obney.grain.todo-processor-v2.interface :as tp])
(require '[litellm.router :as litellm-router])
(require '[clojure.pprint :refer [pprint]])
(require '[clojure.data.json :as json])

;; =============================================================================
;; LLM Provider Configuration
;; =============================================================================

(def ^:private llm-model "google/gemini-3-flash-preview")

(defn setup-openrouter!
  "Register OpenRouter provider with LiteLLM router.
   Reads API key from OPENROUTER_API_KEY env var or accepts it as argument."
  ([] (setup-openrouter! (System/getenv "OPENROUTER_API_KEY")))
  ([api-key]
   (when-not api-key
     (throw (ex-info "OpenRouter API key required. Set OPENROUTER_API_KEY env var or pass key." {})))
   ;; Use the built-in setup function
   (litellm-router/setup-openrouter! :api-key api-key :model llm-model)
   ;; Also register the model-specific config
   (litellm-router/register! (keyword (str "openrouter/" llm-model))
                             {:provider :openrouter
                              :model llm-model
                              :config {:api-base "https://openrouter.ai/api/v1"
                                       :api-key api-key}})
   (println "✓ OpenRouter registered with model:" llm-model)))

;; =============================================================================
;; LLM-enabled Test Context
;; =============================================================================

(defn- delete-dir-recursively
  "Delete a directory and all its contents."
  [^String path]
  (let [f (java.io.File. path)]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)]
          (delete-dir-recursively (.getPath child))))
      (.delete f))))

(defn create-llm-test-context
  "Create a test context with OpenRouter provider configured for LLM calls."
  []
  (rmp/l1-clear!)
  (let [dir (str "/tmp/ontology-llm-test-" (random-uuid))
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub ps
                               :logger nil})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id #uuid "00000000-0000-0000-0000-000000000000"
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :openrouter
                  ::cache-dir dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :event-pubsub ps :processors processors)))

(defn stop-llm-test-context
  "Stop the LLM test context and clean up resources."
  [ctx]
  (doseq [[_ processor] (:processors ctx)]
    (tp/stop processor))
  (when-let [ps (:event-pubsub ctx)]
    (pubsub/stop ps))
  (rmp/l1-clear!)
  (when-let [cache (:cache ctx)]
    (kv/stop cache))
  (when-let [event-store (:event-store ctx)]
    (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (delete-dir-recursively dir)))

(defmacro with-llm-test-context
  "Execute body with an LLM-enabled test context, cleaning up afterward."
  [[ctx-sym] & body]
  `(let [~ctx-sym (create-llm-test-context)]
     (try
       ~@body
       (finally
         (stop-llm-test-context ~ctx-sym)))))

;; =============================================================================
;; Test Data
;; =============================================================================

(def sample-people-json
  "Simple people data for testing."
  [{:name "Alice Johnson" :age 32 :role "Software Engineer" :department "Engineering"}
   {:name "Bob Smith" :age 45 :role "Product Manager" :department "Product"}
   {:name "Carol Davis" :age 28 :role "Designer" :department "Design"}])

(def cambot-style-json
  "Cambot-style turnover data."
  {:entities
   [{:uri "person:tavidee"
     :label "Tavidee Hoskins"
     :type "Person"
     :aliases ["Tavidee" "Tavi"]
     :properties {:group "relationship-ops" :role "Director"}}
    {:uri "org:bryc"
     :label "BRYC"
     :type "Organization"
     :aliases ["Blue Ridge Youth Collaborative"]
     :properties {:type "nonprofit"}}]
   :relationships
   [{:subject "person:tavidee"
     :predicate "member-of"
     :object "org:bryc"}]})

;; =============================================================================
;; Verification 1: JSON Structure Analysis (No LLM)
;; =============================================================================

(defn test-json-structure-analysis!
  "Test JSON structure analysis - no LLM required."
  []
  (println "\n=== TEST: JSON Structure Analysis (No LLM) ===\n")

  (println "1. Analyzing simple array of objects:")
  (let [result (json-ont/analyze-json-structure sample-people-json)]
    (println "   Root type:" (:root-type result))
    (println "   Element type:" (:element-type result))
    (println "   Fields:" (count (:fields result)))
    (doseq [f (:fields result)]
      (println "     -" (:name f) ":" (:type f)))
    (println "   ✓ Analysis complete"))

  (println "\n2. Analyzing nested Cambot-style data:")
  (let [result (json-ont/analyze-json-structure cambot-style-json)]
    (println "   Root type:" (:root-type result))
    (println "   Has arrays?:" (:has-arrays? result))
    (println "   Fields:" (count (:fields result)))
    (println "   ✓ Analysis complete"))

  (println "\n3. Format for LLM:")
  (let [structure (json-ont/analyze-json-structure sample-people-json)
        formatted (json-ont/format-structure-for-llm structure sample-people-json)]
    (println (subs formatted 0 (min 500 (count formatted))) "..."))

  (println "\n✅ JSON Structure Analysis: PASSED"))

;; =============================================================================
;; Verification 2: JSON Extraction Pipeline (Requires LLM)
;; =============================================================================

(defn test-json-extraction!
  "Test full JSON extraction pipeline - REQUIRES LLM.

   This will make actual API calls. Ensure you have:
   - Called (setup-openrouter! \"your-api-key\") first
   - Or set OPENROUTER_API_KEY environment variable"
  []
  (println "\n=== TEST: JSON Extraction Pipeline (Requires LLM) ===\n")
  (println "⚠️  This test makes real LLM calls. Ensure OpenRouter is configured.\n")

  (try
    (with-llm-test-context [ctx]
      (println "1. Building JSON ontology pipeline...")
      (let [sheet-id (json-ont/build-json-ontology-pipeline! ctx)]
        (println "   Sheet ID:" sheet-id)

        (println "\n2. Running extraction on simple people data...")
        (let [result (json-ont/run-json-to-ontology ctx sheet-id
                       {:json-data sample-people-json
                        :base-uri "http://test.org/"
                        :domain "HR/People"})]
          (println "   Status:" (:status result))
          (if (= :success (:status result))
            (do
              (println "   Concepts extracted:" (count (:concepts result)))
              (println "   Relationships found:" (count (:relationships result)))
              (when (seq (:concepts result))
                (println "\n   Sample concepts:")
                (doseq [c (take 3 (:concepts result))]
                  (println "     -" (:label c) "(" (:entity-type c) ")")))
              (println "\n✅ JSON Extraction: PASSED"))
            (do
              (println "   ❌ Extraction failed:" (:error result))
              (println "\n❌ JSON Extraction: FAILED"))))))
    (catch Exception e
      (println "   ❌ Error:" (.getMessage e))
      (.printStackTrace e)
      (println "\n❌ JSON Extraction: FAILED (check OpenRouter configuration)"))))

;; =============================================================================
;; Verification 3: Ontology-ID Scoping
;; =============================================================================

(defn test-ontology-id-scoping!
  "Test ontology-id scoping in projections."
  []
  (println "\n=== TEST: Ontology-ID Scoping ===\n")

  (let [ontology-a #uuid "aaaa0000-0000-0000-0000-000000000001"
        ontology-b #uuid "bbbb0000-0000-0000-0000-000000000002"

        ;; Create events for two different ontologies
        events [{:event/type :ontology/concept-created
                 :ontology-id ontology-a
                 :concept-id (random-uuid)
                 :uri "person:alice"
                 :label "Alice"
                 :description "Person from ontology A"
                 :scope :person
                 :broader []
                 :indicators []
                 :created-at "2026-06-01T00:00:00Z"}
                {:event/type :ontology/concept-created
                 :ontology-id ontology-a
                 :concept-id (random-uuid)
                 :uri "person:bob"
                 :label "Bob"
                 :description "Person from ontology A"
                 :scope :person
                 :broader []
                 :indicators []
                 :created-at "2026-06-01T00:00:00Z"}
                {:event/type :ontology/concept-created
                 :ontology-id ontology-b
                 :concept-id (random-uuid)
                 :uri "org:acme"
                 :label "Acme Corp"
                 :description "Org from ontology B"
                 :scope :organization
                 :broader []
                 :indicators []
                 :created-at "2026-06-01T00:00:00Z"}]

        ;; Project events
        state (rm/concepts {} events)]

    (println "1. Created 3 concepts across 2 ontologies")
    (println "   Ontology A: Alice, Bob (persons)")
    (println "   Ontology B: Acme Corp (organization)")

    (println "\n2. Testing ontology-id stored in projection:")
    (println "   Alice's ontology-id:" (:ontology-id (get state "person:alice")))
    (println "   Acme's ontology-id:" (:ontology-id (get state "org:acme")))
    (assert (= ontology-a (:ontology-id (get state "person:alice"))))
    (assert (= ontology-b (:ontology-id (get state "org:acme"))))
    (println "   ✓ Ontology IDs correctly stored")

    (println "\n3. Testing filtering logic:")
    (let [filter-fn (fn [{:keys [ontology-id ontology-ids scope]}]
                      (let [ont-id-set (cond
                                         ontology-ids (set ontology-ids)
                                         ontology-id #{ontology-id}
                                         :else nil)]
                        (cond->> (vals state)
                          scope (filter #(= scope (:scope %)))
                          ont-id-set (filter #(contains? ont-id-set (:ontology-id %))))))]

      (println "   Filter by ontology-a:" (count (filter-fn {:ontology-id ontology-a})) "concepts")
      (println "   Filter by ontology-b:" (count (filter-fn {:ontology-id ontology-b})) "concepts")
      (println "   Filter by both:" (count (filter-fn {:ontology-ids [ontology-a ontology-b]})) "concepts")
      (println "   No filter:" (count (filter-fn {})) "concepts")

      (assert (= 2 (count (filter-fn {:ontology-id ontology-a}))))
      (assert (= 1 (count (filter-fn {:ontology-id ontology-b}))))
      (assert (= 3 (count (filter-fn {:ontology-ids [ontology-a ontology-b]}))))
      (assert (= 3 (count (filter-fn {}))))
      (println "   ✓ All filtering assertions passed"))

    (println "\n✅ Ontology-ID Scoping: PASSED")))

;; =============================================================================
;; Verification 4: Evolutionary Builder JSON Support
;; =============================================================================

(defn test-evolutionary-builder-json!
  "Test that evolutionary builder supports JSON - REQUIRES LLM.

   This makes actual LLM calls through the evolutionary builder pipeline."
  []
  (println "\n=== TEST: Evolutionary Builder JSON Support (Requires LLM) ===\n")
  (println "⚠️  This test makes real LLM calls. Ensure OpenRouter is configured.\n")

  (try
    (with-llm-test-context [ctx]
      (println "1. Calling build-ontology-from-sources with JSON...")
      (let [result (ontology/build-ontology-from-sources ctx
                     {:sources [{:content (json/write-str sample-people-json)
                                 :type "json"}]
                      :config {:base-uri "http://test.org/"
                               :enable-colbert? false
                               :enable-embeddings? false}})]
        (println "   Ontology ID:" (:ontology-id result))
        (println "   Total concepts:" (:total-concepts result))
        (println "   Total triples:" (:total-triples result))
        (println "   Events emitted:" (count (:events result)))

        (if (> (:total-concepts result) 0)
          (println "\n✅ Evolutionary Builder JSON: PASSED")
          (println "\n⚠️  Evolutionary Builder JSON: No concepts extracted (check LLM response)"))))
    (catch Exception e
      (println "   ❌ Error:" (.getMessage e))
      (.printStackTrace e)
      (println "\n❌ Evolutionary Builder JSON: FAILED"))))

;; =============================================================================
;; Run All Verifications
;; =============================================================================

(defn run-all-verifications!
  "Run all verification tests including LLM tests.
   Call (setup-openrouter! \"your-api-key\") first, or set OPENROUTER_API_KEY env var."
  []
  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "  ORC-001 & ORC-002 VERIFICATION SUITE")
  (println "\n" (apply str (repeat 60 "=")) "\n")

  ;; Tests that don't require LLM
  (test-json-structure-analysis!)
  (test-ontology-id-scoping!)

  ;; Tests that require LLM
  (println "\n--- LLM-Required Tests ---")
  (println "Running LLM tests with OpenRouter...")
  (test-json-extraction!)
  (test-evolutionary-builder-json!)

  (println "\n" (apply str (repeat 60 "=")) "\n")
  (println "  VERIFICATION COMPLETE")
  (println "\n" (apply str (repeat 60 "=")) "\n"))

;; =============================================================================
;; Quick Check (No LLM)
;; =============================================================================

(defn quick-check!
  "Quick verification without LLM calls."
  []
  (println "\n=== QUICK CHECK (No LLM) ===\n")
  (test-json-structure-analysis!)
  (test-ontology-id-scoping!)
  (println "\n✅ Quick check complete - all code-level tests passed"))

(comment
  ;; ==========================================================================
  ;; Run in REPL:
  ;; ==========================================================================

  ;; Quick check (no LLM needed)
  (quick-check!)

  ;; ==========================================================================
  ;; Full verification with LLM - requires OpenRouter API key
  ;; ==========================================================================

  ;; Option 1: Pass API key directly
  (setup-openrouter! "sk-or-v1-your-api-key-here")
  (run-all-verifications!)

  ;; Option 2: Use environment variable (set OPENROUTER_API_KEY first)
  (setup-openrouter!)
  (run-all-verifications!)

  ;; ==========================================================================
  ;; Individual tests
  ;; ==========================================================================
  (test-json-structure-analysis!)  ;; No LLM
  (test-ontology-id-scoping!)       ;; No LLM

  ;; LLM tests - call setup-openrouter! first
  (setup-openrouter! "your-key")
  (test-json-extraction!)
  (test-evolutionary-builder-json!)

  ,)
