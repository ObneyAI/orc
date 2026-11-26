# Component REPL examples

These examples preserve the original REPL forms and source paths from revision
`7cf444ec`. To reproduce an example that uses private helpers, evaluate its
individual forms in the indicated namespace at that revision.

## components/file-store-s3/src/ai/obney/orc/file_store_s3/core.clj

```clojure
(comment
  ;; Example usage with LocalStack
  (def file-store
    (p/start (->S3FileStore {:s3-bucket "orc-files"
                             :localstack/enabled true
                             :localstack/endpoint "http://localhost:4566"
                             :aws/region "us-east-1"})))

  (def file-id (random-uuid))

  (p/put-file file-store
              {:file-id file-id
               :file-contents (byte-array (.getBytes "Hello, world!"))})

  (String. (p/get-file file-store {:file-id file-id}))

  "")
```

## components/gepa/src/ai/obney/orc/gepa/core/sampler.clj

```clojure
(comment
  ;; Example usage with 18 training examples, batch-size 3
  ;; 18 / 3 = 6 batches per epoch

  (def trainset-size 18)
  (def batch-size 3)

  ;; First epoch - iterations 0-5
  (epoch-shuffled-batch-sampler trainset-size batch-size 0) ; => e.g., [5 2 17]
  (epoch-shuffled-batch-sampler trainset-size batch-size 1) ; => e.g., [0 8 12]
  (epoch-shuffled-batch-sampler trainset-size batch-size 5) ; => e.g., [10 16 13] (last batch of epoch 0)

  ;; Second epoch - iterations 6-11 (reshuffled!)
  (epoch-shuffled-batch-sampler trainset-size batch-size 6) ; => different shuffle

  ;; Verify all indices are covered in one epoch
  (let [all-batches (for [i (range 6)]
                      (epoch-shuffled-batch-sampler trainset-size batch-size i))]
    (= (set (range trainset-size))
       (set (flatten all-batches)))) ; => true

  ;; With real trainset
  (def trainset [{:q "Q1" :a "A1"} {:q "Q2" :a "A2"} {:q "Q3" :a "A3"}
                 {:q "Q4" :a "A4"} {:q "Q5" :a "A5"} {:q "Q6" :a "A6"}])
  (get-minibatch-for-iteration trainset 2 0) ; => first 2 examples (shuffled)
  (get-minibatch-for-iteration trainset 2 1) ; => next 2 examples
  (get-minibatch-for-iteration trainset 2 2) ; => last 2 examples

  ;; After 3 iterations (one epoch), reshuffle
  (get-minibatch-for-iteration trainset 2 3) ; => new shuffle
  )
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/analyzer.clj

```clojure
(comment
  ;; Example analysis
  (analyze-tool {:name "searchLangfuseDocs"
                 :description "Semantic search over Langfuse docs"
                 :inputSchema {"type" "object"
                               "properties" {"query" {"type" "string"}}}})
  ;; => {:name "searchLangfuseDocs"
  ;;     :description "Semantic search over Langfuse docs"
  ;;     :capabilities #{:search}
  ;;     :category :data-access
  ;;     :malli-input [:map [:query :string]]
  ;;     :idempotent? true}

  (exploration-budget 15)
  ;; => {:patterns 10, :creative 5, :total 15}
  )
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/comparison.clj

```clojure
(comment
  ;; Example usage

  ;; Run single comparison
  (def result
    (run-comparison ctx
      phase1-sheet-id
      phase2-sheet-id
      {:question "How do I trace LLM calls in Langfuse?"}
      {:answer #"trace|Langfuse"}))

  (println (format-comparison-report result))

  ;; Run batch comparison
  (def batch-result
    (batch-comparison ctx
      phase1-sheet-id
      phase2-sheet-id
      [{:inputs {:question "How do I trace LLM calls?"}
        :expected {:answer #"trace"}}
       {:inputs {:question "What is the Python SDK?"}
        :expected {:answer #"Python|SDK"}}
       {:inputs {:question "How do sessions work?"}
        :expected {:answer #"session"}}]))

  (println (format-batch-summary batch-result)))
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/executor_generator.clj

```clojure
(comment
  ;; Example usage:
  (def sample-tool
    {:name "searchLangfuseDocs"
     :description "Search Langfuse documentation"
     :inputSchema {"type" "object"
                   "properties" {"query" {"type" "string"
                                          "description" "Search query"}}
                   "required" ["query"]}})

  ;; Generate executor code only
  (println (generate-executor-code {:tool-name "searchLangfuseDocs"
                                    :input-schema (:inputSchema sample-tool)}))

  ;; Generate complete namespace
  (println (generate-namespace-code {:tool-id (UUID/randomUUID)
                                     :tool-name "searchLangfuseDocs"
                                     :input-schema (:inputSchema sample-tool)}))

  ;; Build full executor definition
  (build-executor-definition sample-tool)
  )
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/executor_runtime.clj

```clojure
(comment
  ;; Example usage:

  ;; Generate and load an executor
  (require '[ai.obney.orc.mcp-sheet-builder.core.executor-generator :as gen])

  (def sample-tool
    {:name "searchLangfuseDocs"
     :inputSchema {"type" "object"
                   "properties" {"query" {"type" "string"}}
                   "required" ["query"]}})

  (def exec-def (gen/build-executor-definition sample-tool))

  (load-executor! (:tool-id exec-def)
                  (:tool-name exec-def)
                  (:source-code exec-def)
                  (:namespace-requires exec-def)
                  :checksum (:checksum exec-def))

  ;; Resolve and call
  (def exec-fn (get-executor "searchLangfuseDocs"))
  (exec-fn {:inputs {:query "test"} :context {}})

  ;; List loaded executors
  (list-loaded-executors)

  ;; Clear for testing
  (clear-registry!)
  )
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/executors.clj

```clojure
(comment
  ;; Example: Create a tool executor
  (def langfuse-search
    (make-tool-executor
     "searchLangfuseDocs"
     {:query "query"}
     :search-result))

  ;; Example: Use the generic executor
  (call-mcp-tool
   {:inputs {:tool-name "searchLangfuseDocs"
             :query "How to trace LLM calls?"}
    :context {:mcp-session nil}}))
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/generator.clj

```clojure
(comment
  ;; Example generation
  (def tools
    [{:name "searchDocs"
      :capabilities #{:search}
      :input-schema {"properties" {"query" {"type" "string"}}}}
     {:name "getPage"
      :capabilities #{:retrieval}
      :input-schema {"properties" {"path" {"type" "string"}}}}
     {:name "summarize"
      :capabilities #{:generate :transform}}])

  (generate-sheet
   {:tools tools
    :relationships []
    :patterns [{:pattern :research-compilation}]}
   {:pattern :research-compilation}))
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/intent_analyzer.clj

```clojure
(comment
  ;; Example usage
  (def sample-tools
    [{:name "searchLangfuseDocs"
      :description "Search Langfuse documentation"
      :capabilities #{:search}}
     {:name "getLangfuseDocsPage"
      :description "Get a specific documentation page"
      :capabilities #{:retrieval}}
     {:name "getLangfuseOverview"
      :description "Get high-level overview"
      :capabilities #{:retrieval}}])

  ;; Quick inference (no LLM)
  (infer-intent-from-keywords "I want to find information about Langfuse pricing")

  ;; Full LLM-based analysis
  (analyze-intent "I want to understand Langfuse pricing and compare plans" sample-tools)

  ;; Score tools against intent
  (let [intent (infer-intent-from-keywords "search for pricing information")]
    (rank-tools-by-relevance sample-tools intent)))
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/mcp_client.clj

```clojure
(comment
  ;; Example: Create a static client with Langfuse tools
  (def conn (connect {:preset :langfuse}))
  (list-tools conn)

  ;; Example: Create a Streamable HTTP client
  (def http-conn (connect {:type :streamable-http
                           :server-id "langfuse"
                           :url "https://langfuse-mcp.example.com"
                           :api-key "..."}))
  (list-tools http-conn)

  ;; Example: Multi-server registry
  (def registry (create-registry {"langfuse" (connect {:preset :langfuse})
                                   "exa" (connect {:preset :exa})}))
  (list-all-tools registry)
  (def multi-call (registry->call-tool-fn registry))
  ;; (multi-call "langfuse/searchLangfuseDocs" {:query "tracing"})
  ;; (multi-call "exa/web_search_exa" {:query "MCP protocol"})
  )
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/patterns.clj

```clojure
(comment
  ;; Example usage
  (def tools
    [{:name "search" :capabilities #{:search} :idempotent? true}
     {:name "getPage" :capabilities #{:retrieval} :idempotent? true}
     {:name "summarize" :capabilities #{:generate :transform}}])

  (def rels
    [{:type :sequential :from "search" :to "getPage" :confidence 0.8}
     {:type :sequential :from "getPage" :to "summarize" :confidence 0.7}])

  (select-patterns tools rels))
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/relationships.clj

```clojure
(comment
  ;; Example usage
  (def tools
    [{:name "searchDocs" :capabilities #{:search} :idempotent? true
      :input-schema {"properties" {"query" {}}}}
     {:name "getPage" :capabilities #{:retrieval} :idempotent? true
      :input-schema {"properties" {"path" {}}}}
     {:name "summarize" :capabilities #{:generate :transform} :idempotent? true}])

  (detect-relationships tools))
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/schema_converter.clj

```clojure
(comment
  ;; Example conversions
  (json-schema->malli
   {"type" "object"
    "properties" {"query" {"type" "string" "description" "Search query"}
                  "limit" {"type" "integer" "default" 10}}
    "required" ["query"]})
  ;; => [:map
  ;;     [:query [:string {:description "Search query"}]]
  ;;     [:limit {:optional true} :int]]

  (json-schema->malli
   {"type" "array"
    "items" {"type" "string"}
    "description" "List of keywords"})
  ;; => [:vector {:description "List of keywords"} :string]
  )
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/tool_relevance.clj

```clojure
(comment
  ;; Example usage with many tools
  (def many-tools
    [{:name "searchDocs" :capabilities #{:search} :description "Search docs"}
     {:name "getPage" :capabilities #{:retrieval} :description "Get page"}
     {:name "sendEmail" :capabilities #{:create} :description "Send email"}
     {:name "validateCode" :capabilities #{:validate} :description "Validate code"}
     {:name "generateReport" :capabilities #{:generate} :description "Generate reports"}
     {:name "searchAPI" :capabilities #{:search} :description "Search API"}
     {:name "fetchData" :capabilities #{:retrieval} :description "Fetch data"}
     {:name "transformData" :capabilities #{:transform} :description "Transform data"}])

  ;; Score all tools
  (def intent (intent/infer-intent-from-keywords "search for pricing documentation"))
  (score-all-tools many-tools intent)

  ;; Get suggestions
  (suggest-tools many-tools intent {:max-tools 5})

  ;; High-level API
  (select-tools-for-workflow many-tools "I want to search and compile pricing information"
                             {:max-tools 5
                              :exclude-tools ["sendEmail"]})

  ;; Format for display
  (-> (suggest-tools many-tools intent {:max-tools 5})
      format-tool-suggestion
      println))
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/core/validator.clj

```clojure
(comment
  ;; Example validation
  (validate-sheet
   {:blackboard {:query :string
                 :searchDocs-result [:map [:content :string]]}
    :workflow '(sheet/workflow "test"
                 (sheet/blackboard {:query :string})
                 (sheet/sequence "main"
                   (sheet/code "call-searchDocs"
                     :fn "..."
                     :reads [:query]
                     :writes [:searchDocs-result])))}))
```

## components/mcp-sheet-builder/src/ai/obney/orc/mcp_sheet_builder/workflows/builder.clj

```clojure
(comment
  ;; Example: Build a sheet from Langfuse tools
  (def result
    (build-sheet-directly
     {:mcp-opts {:preset :langfuse}
      :problem "Research Langfuse documentation"}))

  (:sheet result)
  (:validation result))
```

## components/ontology/dev/manual_verification.clj

```clojure
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
```

## components/ontology/src/ai/obney/orc/ontology/core/entity_resolver.clj

```clojure
(comment
  ;; Example usage

  ;; Sample concepts from two sources
  (def concepts
    [{:uri "onet:11-1011" :label "Chief Executives" :entity-type "Occupation"
      :source-id "onet" :embedding [0.1 0.2 0.3]}
     {:uri "programs:ceo" :label "chief executive" :entity-type "Occupation"
      :source-id "programs" :embedding [0.11 0.21 0.29]}
     {:uri "onet:15-1252" :label "Software Developers" :entity-type "Occupation"
      :source-id "onet" :embedding [0.5 0.6 0.7]}
     {:uri "skills:programming" :label "Programming" :entity-type "Skill"
      :source-id "skills" :embedding [0.8 0.9 0.1]}])

  ;; Resolve - should match Chief Executives across sources
  (resolve-within-batch concepts {:similarity-threshold 0.85})
  ;; => {:matches [{:source1-uri "onet:11-1011" :source2-uri "programs:ceo"
  ;;                :similarity-score 1.0 :match-type "exact"}]
  ;;     :canonical-map {"programs:ceo" "onet:11-1011"
  ;;                     "onet:11-1011" "onet:11-1011"}
  ;;     ...}

  ,)
```

## components/ontology/src/ai/obney/orc/ontology/core/evolutionary_builder.clj

```clojure
(comment
  ;; Example usage

  ;; Build from CSV sources
  (build-from-sources ctx
    {:sources [{:path "/path/to/occupations.csv" :type "csv"}
               {:path "/path/to/programs.csv" :type "csv"}]
     :config {:base-uri "http://bryc.ai/ontology#"
              :similarity-threshold 0.85}})

  ;; Build from inline content
  (build-from-sources ctx
    {:sources [{:content "Software Developer, writes code"
                :type "text"}]
     :config {:base-uri "http://example.org/"}})

  ;; Incremental evolution
  (evolve ctx
    {:ontology-id (random-uuid)  ;; Use actual ontology-id from build-from-sources
     :sources [{:path "/path/to/new-data.csv" :type "csv"}]})

  ,)
```

## components/ontology/src/ai/obney/orc/ontology/core/graph_evolver.clj

```clojure
(comment
  ;; Example usage

  ;; Sample data
  (def concepts
    [{:uri "onet:11-1011" :label "Chief Executives" :entity-type "Occupation"
      :definition "Plan and direct all aspects of an organization"}
     {:uri "programs:ceo" :label "CEO Program" :entity-type "Program"
      :definition "Training program for executives"}])

  (def relationships
    [{:subject "onet:11-1011" :predicate "skos:related" :object "skills:leadership"}
     {:subject "programs:ceo" :predicate "prepares" :object "onet:11-1011"}])

  (def canonical-map
    {"programs:ceo" "onet:11-1011"})

  ;; Evolve graph
  (evolve-graph!
    {:ontology-id (java.util.UUID/randomUUID)
     :source-ids ["onet" "programs"]
     :extracted-concepts concepts
     :extracted-relationships relationships
     :resolution-result {:canonical-map canonical-map}})

  ;; Generate TTL
  (generate-ttl-snapshot
    {:concepts {"onet:11-1011" (first concepts)}
     :relationships relationships}
    {:base-uri "http://bryc.ai/ontology#"})

  ,)
```

## components/ontology/src/ai/obney/orc/ontology/core/source_registry.clj

```clojure
(comment
  ;; Example usage

  ;; Register a CSV source
  (register-source! {:source-registry {}
                     :content-hash-index {}}
                    {:source-uri "/path/to/data.csv"
                     :source-type "csv"
                     :namespace "http://example.org/data#"})

  ;; Register inline text
  (register-source! {:source-registry {}
                     :content-hash-index {}}
                    {:content "Some text to process"
                     :source-type "text"})

  ;; Check if processed
  (was-processed? {:source-registry {}
                   :content-hash-index {"abc123" "source-abc123"}}
                  nil
                  "same content that produces abc123 hash")

  ,)
```

## components/ontology/src/ai/obney/orc/ontology/interface/evolutionary.clj

```clojure
(comment
  ;; === Complete Example Workflow ===

  ;; 1. Build initial ontology from CSV
  (def result1
    (build-from-sources ctx
      {:sources [{:path "occupations.csv" :type "csv"}]
       :config {:base-uri "http://bryc.ai/"}}))

  ;; 2. Check what was built
  (get-statistics ctx (:ontology-id result1))
  ;; => {:total-sources 1 :total-concepts 50 ...}

  ;; 3. Get the TTL output
  (spit "ontology.ttl" (:ttl-output result1))

  ;; 4. Evolve with new data
  (def result2
    (evolve ctx
      {:ontology-id (:ontology-id result1)
       :sources [{:path "programs.csv" :type "csv"}]}))

  ;; 5. Check resolution
  (get-all-canonical-mappings ctx)
  ;; => {"prog:CEO" "onet:11-1011" ...}

  ;; 6. Query by entity type
  (get-concepts-by-type ctx "Occupation")
  ;; => [{:uri "onet:11-1011" :label "Chief Executives" ...} ...]

  ;; 7. Check build history
  (get-build-history ctx (:ontology-id result1))
  ;; => [{:build-id ... :total-concepts 100 :completed-at ...} ...]

  ,)
```

## components/ontology/src/ai/obney/orc/ontology/sheets/csv_ontology.clj

```clojure
(comment
  ;; ============================================================
  ;; TESTING
  ;; ============================================================

  ;; Step 1: Start the service (run in repl_stuff.clj first)

  ;; Step 2: Build the workflow
  (build-csv-ontology-pipeline! rs/context)

  ;; Step 3: Run with sample data
  (run-demo rs/context csv-ontology-sheet-id)

  ;; Step 4: Run with custom CSV file
  (run-csv-to-ontology rs/context csv-ontology-sheet-id
    {:csv-path "/path/to/your/data.csv"
     :entity-column "name"
     :entity-type "MyEntity"
     :base-uri "http://example.org/my-ontology#"})

  "")
```

## components/ontology/src/ai/obney/orc/ontology/sheets/ontology_exploration.clj

```clojure
#_(defn run-full-test
    "Run the complete taxonomy pipeline test with proper context setup."
    []
    (println "=" (apply str (repeat 69 "=")))
    (println "CLOJURE ORC TAXONOMY PIPELINE TEST")
    (println (str "Timestamp: " (java.time.LocalDateTime/now)))
    (println "=" (apply str (repeat 69 "=")))

    ;; Setup providers
    (println "\nSetting up ORC LLM providers...")
    (llm/quick-setup!)
    (println "Available providers:" (llm/list-providers))

    ;; Check context
    (let [context (ensure-context-has-provider rs/context)]
      (println "\nContext provider:" (:llm-provider context))

      ;; Build workflow
      (println "\nBuilding taxonomy pipeline...")
      (build-taxonomy-pipeline! context)
      (println "Pipeline built with ID:" taxonomy-sheet-id)

      ;; Run with debug enabled
      (println "\nRunning pipeline with debug enabled...")
      (println "Sample text (first 200 chars):")
      (println (subs sample-source-text 0 (min 200 (count sample-source-text))))
      (println "\nDomain:" sample-domain)

      (binding [executor/*debug-raw-response* true]
        (let [result (run-taxonomy-pipeline context taxonomy-sheet-id
                       {:source-text sample-source-text
                        :domain sample-domain})]
          (println "\n" (apply str (repeat 70 "=")))
          (println "RESULTS")
          (println (apply str (repeat 70 "=")))
          (println "\nStatus:" (:status result))
          (println "Concepts count:" (count (:concepts result)))
          (println "Relationships count:" (count (:relationships result)))
          (println "Top concepts:" (:top-concepts result))
          (println "Quality:" (get-in result [:validation :quality]))
          result))))
```

## components/ontology/src/ai/obney/orc/ontology/sheets/ontology_exploration.clj

```clojure
(comment
  ;; ============================================================
  ;; STEP-BY-STEP TESTING
  ;; ============================================================

  ;; Step 1: Start the service (run in repl_stuff.clj first)
  ;; (do
  ;;   (def service (service/start))
  ;;   (def context (::service/context service))
  ;;   (def event-store (:event-store context)))

  ;; Step 2: Setup providers and check
  (do
    (require '[ai.obney.orc.llm.interface :as llm])
    (llm/quick-setup!)
    (println "Providers:" (llm/list-providers))
    (println "Context provider:" (:llm-provider rs/context)))

  ;; Step 3: Build the workflow
  (build-taxonomy-pipeline! rs/context)

  ;; Step 4: Run with debug output
  (run-demo-with-debug rs/context taxonomy-sheet-id)

  ;; Step 5: Run full test (combines all steps)
  (run-full-test)

  ;; ============================================================
  ;; MANUAL TESTING
  ;; ============================================================

  ;; Build the workflow (only needed once):
  (build-taxonomy-pipeline! rs/context)

  ;; Run with sample data:
  (run-demo rs/context taxonomy-sheet-id)

  ;; Run with custom input:
  (run-taxonomy-pipeline rs/context taxonomy-sheet-id
    {:source-text "Your text here..."
     :domain "your domain"})

  "")
```

## components/ontology/src/ai/obney/orc/ontology/sheets/sql_ontology.clj

```clojure
(comment
  ;; ============================================================
  ;; TESTING with IPEDS Database
  ;; ============================================================

  ;; Step 1: Start the service (run in repl_stuff.clj first)

  ;; Step 2: Build the workflow
  (build-sql-ontology-pipeline! rs/context)

  ;; Step 3: Run with IPEDS database
  (run-sql-to-ontology rs/context sql-ontology-sheet-id
    {:db-path "/Users/darylroberts/Downloads/output.db"
     :base-uri "http://ipeds.ai/ontology#"
     :max-tables 20
     :max-instances 5})

  "")
```

## components/ontology/src/ai/obney/orc/ontology/sheets/unified_ontology.clj

```clojure
(comment
  ;; ============================================================
  ;; TESTING
  ;; ============================================================

  ;; Auto-detect from file extension
  (extract rs/context {:path "/path/to/data.csv"})
  (extract rs/context {:path "/path/to/database.db"})
  (extract rs/context {:path "/path/to/document.txt"})

  ;; Explicit type
  (extract rs/context {:path "/path/to/file" :type :csv})

  ;; Inline content
  (extract rs/context {:content "Machine learning is a subset of AI." :type :text :domain "AI"})

  ;; Multiple sources
  (extract-multiple rs/context
    [{:path "data.csv"}
     {:path "database.db"}
     {:content "Some text" :type :text}])

  ;; Convenience functions
  (extract-file rs/context "/path/to/file.csv")
  (extract-database rs/context "/Users/darylroberts/Downloads/output.db")
  (extract-text-content rs/context "AI is transforming education" :domain "education")

  "")
```

## components/ontology/test/ai/obney/orc/ontology/test_support/c2a_live_verify.clj

```clojure
(comment
  (run!))
```

## components/ontology/test/ai/obney/orc/ontology/test_support/seed_descriptions.clj

```clojure
(comment
  ;; Run from REPL after starting a context:
  (require '[ai.obney.orc.ontology.test-support.seed-descriptions :as s] :reload)
  (count s/all-node-type-seeds)
  (count s/all-tree-fingerprint-seeds)
  (count s/all-behavioral-subtree-seeds)
  (:target-id s/scheduling-tree-seed))
```

## components/ontology/test/ai/obney/orc/ontology/test_support/seed_principles.clj

```clojure
(comment
  ;; Smoke test from REPL — see development/src/c1_pipeline_probe.clj for a
  ;; full end-to-end probe that exercises seed → retrieve → format.
  (require '[ai.obney.orc.ontology.test-support.seed-principles :as s]))
```

## components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj

```clojure
(comment
  ;; Example usage:

  ;; 1. Setup providers (do once at app startup)
  (setup-providers!)

  ;; 2. Define a node and blackboard
  (def example-node
    {:instruction "Given the question, provide a clear and concise answer."
     :reads [:question]
     :writes [:answer]})

  (def example-blackboard
    {:question {:key :question :schema :string :value "What is 2+2?" :version 1}
     :answer {:key :answer :schema :string :value nil :version 0}})

  ;; 3. Execute
  (execute-leaf example-node example-blackboard :openrouter)
  ;; => {:status :success, :outputs {:answer "4"}, :duration-ms 1234}

  ;; 4. Or use mock for testing
  (execute-leaf-mock example-node example-blackboard)
  ;; => {:status :success, :outputs {:answer "mock-value-for-answer"}, :duration-ms 0}
  )
```

## components/orc-service/test/ai/obney/orc/orc_service/rlm_mode_test.clj

```clojure
(comment
  ;; Run individual test
  (clojure.test/run-tests 'ai.obney.orc.orc-service.rlm-mode-test)

  ;; Run specific test
  (clojure.test/test-vars [#'rlm-mode-llm-primitive-test])
  (clojure.test/test-vars [#'rlm-store!-returns-stored-value-test])
  )
```

## components/orc-service/test/ai/obney/orc/orc_service/rlm_tree_executor_test.clj

```clojure
(comment
  ;; Run individual test
  (clojure.test/run-tests 'ai.obney.orc.orc-service.rlm-tree-executor-test))
```
