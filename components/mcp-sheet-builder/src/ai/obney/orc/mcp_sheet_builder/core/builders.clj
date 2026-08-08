(ns ai.obney.orc.mcp-sheet-builder.core.builders
  "High-level build pipelines for MCP Sheet Builder.

   Orchestrates: connect -> analyze -> generate -> validate -> build sheet.
   Each function represents a complete pipeline with different output targets."
  (:require [ai.obney.orc.mcp-sheet-builder.core.mcp-client :as mcp-client]
            [ai.obney.orc.mcp-sheet-builder.core.analyzer :as analyzer]
            [ai.obney.orc.mcp-sheet-builder.core.relationships :as relationships]
            [ai.obney.orc.mcp-sheet-builder.core.patterns :as patterns]
            [ai.obney.orc.mcp-sheet-builder.core.generator :as generator]
            [ai.obney.orc.mcp-sheet-builder.core.validator :as validator]
            [ai.obney.orc.mcp-sheet-builder.core.executor-generator :as executor-gen]
            [ai.obney.orc.mcp-sheet-builder.core.executor-runtime :as executor-runtime]
            [ai.obney.orc.orc-service.interface :as sheet]
            [clojure.string :as str]))

;; ============================================================================
;; Analysis Orchestration (used by builders)
;; ============================================================================

(defn analyze-tools
  "Analyze MCP tools: list-tools -> analyze -> detect-relationships -> select-patterns.

   Returns a map with:
   - :tools - Vector of analyzed tool maps with capability tags
   - :relationships - Detected tool relationships
   - :patterns - Applicable workflow patterns with confidence scores

   Options:
   - :cache? - Whether to use cached analysis (default true)
   - :creative? - Include creative pattern exploration (default false)"
  ([mcp-conn]
   (analyze-tools mcp-conn {}))
  ([mcp-conn opts]
   (let [tools (mcp-client/list-tools mcp-conn)
         analyzed (analyzer/analyze-tools tools opts)
         rels (relationships/detect-relationships analyzed)
         pats (patterns/select-patterns analyzed rels opts)]
     {:tools analyzed
      :relationships rels
      :patterns pats})))

;; ============================================================================
;; Build Pipelines
;; ============================================================================

(defn build-from-mcp
  "End-to-end: Connect to MCP, analyze tools, generate and validate sheet.

   Returns a map with:
   - :analysis - Tool analysis results
   - :sheet - Generated workflow (if successful)
   - :validation - Validation results

   Options:
   - :pattern - Specific pattern to use (or :auto for automatic selection)
   - :problem - Problem description for LLM pattern selection
   - :validate? - Whether to validate (default true)"
  ([mcp-opts]
   (build-from-mcp mcp-opts {}))
  ([mcp-opts opts]
   (let [conn (mcp-client/connect mcp-opts)
         analysis (analyze-tools conn opts)
         pattern (or (:pattern opts)
                     (-> analysis :patterns first :pattern))
         sheet (generator/generate-sheet analysis {:pattern pattern
                                                    :problem (:problem opts)})
         validation (when (:validate? opts true)
                      (validator/validate-sheet sheet))]
     {:connection conn
      :analysis analysis
      :sheet sheet
      :validation validation})))

(defn build-sheet-from-mcp!
  "Complete pipeline: MCP server -> tool analysis -> pattern -> generate -> build sheet.

   This is the main entry point for dynamically creating ORC workflows from MCP tools.

   Args:
     ctx - Context with :event-store and optional :llm-provider
     mcp-opts - MCP connection options {:type :static/:http :preset :langfuse}

   Options:
     :pattern - Specific pattern to use (:sequential-pipeline, :research-compilation, :repl-researcher)
     :pattern-hint - Problem description for LLM pattern selection

   Returns:
   {:sheet-id uuid             ;; The built sheet ID
    :workflow-name string      ;; Name of the workflow
    :pattern keyword           ;; Pattern used
    :analysis {...}            ;; Full analysis results
    :mcp-connection {...}      ;; MCP connection for execution
    :call-tool-fn fn           ;; (tool-name args-map) -> result, for execution context
    :tools [...]               ;; Tool names}"
  ([ctx mcp-opts]
   (build-sheet-from-mcp! ctx mcp-opts {}))
  ([ctx mcp-opts opts]
   (let [;; Connect and analyze
         mcp-conn (mcp-client/connect mcp-opts)
         analysis (analyze-tools mcp-conn opts)

         ;; Select pattern
         pattern-key (or (:pattern opts)
                         (-> analysis :patterns first :pattern)
                         :sequential-pipeline)

         ;; Generate workflow data structure
         sheet-data (generator/generate-sheet-data analysis {:pattern pattern-key})
         workflow-data (:workflow-data sheet-data)

         ;; Build the sheet
         sheet-id (sheet/build-workflow! ctx workflow-data)]

     {:sheet-id sheet-id
      :workflow-name (:workflow-name workflow-data)
      :pattern pattern-key
      :analysis analysis
      :mcp-connection mcp-conn
      :call-tool-fn (partial mcp-client/call-tool mcp-conn)
      :tools (:tools sheet-data)})))

(defn build-repl-researcher-sheet!
  "Build a repl-researcher workflow for adaptive MCP tool use.

   This creates a workflow with a single repl-researcher node that can
   iteratively call MCP tools until it finds an answer.

   Args:
     ctx - Context with :event-store
     mcp-opts - MCP connection options
     instruction - What the researcher should accomplish

   Options:
     :name - Workflow name (default: 'mcp-researcher')
     :max-iterations - Max research iterations (default: 5)
     :model - LLM model to use

   Returns:
   {:sheet-id uuid
    :workflow-name string
    :mcp-connection {...}
    :call-tool-fn fn           ;; (tool-name args-map) -> result, for execution context
    :tools [...]}"
  ([ctx mcp-opts instruction]
   (build-repl-researcher-sheet! ctx mcp-opts instruction {}))
  ([ctx mcp-opts instruction opts]
   (let [;; Connect and get tools
         mcp-conn (mcp-client/connect mcp-opts)
         tools (mcp-client/list-tools mcp-conn)
         tool-names (mapv :name tools)

         ;; Build workflow data
         workflow-name (or (:name opts) "mcp-researcher")
         workflow-data {:workflow-name workflow-name
                        :blackboard-schema {:question [:string {:description "The question to research"}]
                                            :answer [:string {:description "The research findings"}]}
                        :root-node {:node-type :repl-researcher
                                    :name "researcher"
                                    :model (or (:model opts) "google/gemini-2.5-flash")
                                    :instruction instruction
                                    :reads [:question]
                                    :writes [:answer]
                                    :mcp-tools tool-names
                                    :max-iterations (or (:max-iterations opts) 5)}}

         ;; Build the sheet
         sheet-id (sheet/build-workflow! ctx workflow-data)]

     {:sheet-id sheet-id
      :workflow-name workflow-name
      :mcp-connection mcp-conn
      :call-tool-fn (partial mcp-client/call-tool mcp-conn)
      :tools tool-names})))

(defn build-sheet-with-executors!
  "Build sheet AND register all tool executors in event store.

   This is the recommended approach for production use:
   1. Connects to MCP server and analyzes tools
   2. Generates executor code for each tool
   3. Stores executors in event store (via register-executor command)
   4. Loads executors into runtime registry
   5. Builds ORC sheet with references to dynamic executors

   The executors are persisted and can be reloaded on application restart.

   Args:
     ctx - Context with :event-store and :command-dispatcher
     mcp-opts - MCP connection options
     opts - Pattern selection options

   Returns:
   {:sheet-id uuid
    :workflow-name string
    :pattern keyword
    :executors [{:tool-id :tool-name :fn-reference} ...]
    :mcp-connection {...}}"
  ([ctx mcp-opts]
   (build-sheet-with-executors! ctx mcp-opts {}))
  ([ctx mcp-opts opts]
   (let [;; Connect and analyze
         mcp-conn (mcp-client/connect mcp-opts)
         analysis (analyze-tools mcp-conn opts)
         tools (:tools analysis)

         ;; Generate executors for all tools
         executor-defs (executor-gen/build-executor-definitions
                        (map (fn [t] {:name (:name t)
                                      :inputSchema (:input-schema t)})
                             tools))

         ;; Persist executors to event store (if command dispatcher available)
         dispatch-fn (:command-dispatcher ctx)
         _ (when dispatch-fn
             (dispatch-fn
              {:command/type :mcp-sheet-builder/register-executors-batch
               :executors (mapv #(select-keys % [:tool-id :tool-name :source-code
                                                 :namespace-requires :checksum])
                                executor-defs)}))

         ;; Load executors into runtime
         load-results (for [exec-def executor-defs]
                        (executor-runtime/load-executor!
                         (:tool-id exec-def)
                         (:tool-name exec-def)
                         (:source-code exec-def)
                         (:namespace-requires exec-def)
                         :checksum (:checksum exec-def)))

         ;; Select pattern and generate workflow
         pattern-key (or (:pattern opts)
                         (-> analysis :patterns first :pattern)
                         :sequential-pipeline)

         ;; Generate workflow data
         sheet-data (generator/generate-sheet-data analysis {:pattern pattern-key})
         workflow-data (:workflow-data sheet-data)

         ;; Build the sheet
         sheet-id (sheet/build-workflow! ctx workflow-data)]

     {:sheet-id sheet-id
      :workflow-name (:workflow-name workflow-data)
      :pattern pattern-key
      :executors (mapv #(select-keys % [:tool-id :tool-name :fn-reference]) executor-defs)
      :load-results (vec load-results)
      :mcp-connection mcp-conn})))

(defn build-and-export-portable!
  "Build from MCP, persist, AND export as portable .clj files.

   Complete pipeline:
   1. Connect to MCP server and analyze tools
   2. Generate executors for all tools
   3. Persist executors to event store (if command-dispatcher available)
   4. Build sheet in event store
   5. Export sheet + executors as standalone .clj files

   The exported files can be used in any project with orc-service,
   without needing the mcp-sheet-builder component.

   Args:
     ctx - Context with :event-store (and optionally :command-dispatcher)
     mcp-opts - MCP connection options {:preset :langfuse} or {:type :http ...}
     export-dir - Directory to export files to

   Options:
     :name - Base name for exported files (default: workflow name)
     :pattern - Specific pattern to use

   Returns:
   {:sheet-id uuid
    :workflow-name string
    :sheet-file string
    :executors-file string
    :pattern keyword
    :tools [...]}"
  ([ctx mcp-opts export-dir]
   (build-and-export-portable! ctx mcp-opts export-dir {}))
  ([ctx mcp-opts export-dir opts]
   (let [;; Import exporter lazily to avoid circular dep
         exporter-ns (requiring-resolve 'ai.obney.orc.mcp-sheet-builder.core.exporter/export-portable-sheet!)

         ;; Build with executors
         result (build-sheet-with-executors! ctx mcp-opts opts)
         {:keys [sheet-id workflow-name pattern executors mcp-connection]} result

         ;; Get workflow data for export
         sheet-data (generator/generate-sheet-data
                     (analyze-tools mcp-connection opts)
                     {:pattern pattern})
         workflow-data (:workflow-data sheet-data)

         ;; Derive name from workflow or option
         base-name (or (:name opts)
                       (-> workflow-name
                           (str/replace #"[^a-zA-Z0-9-]" "-")
                           (str/replace #"-+" "-")))

         ;; Generate executor defs with input schemas
         tools (mcp-client/list-tools mcp-connection)
         executor-defs (for [t tools]
                         {:tool-name (:name t)
                          :input-schema (:inputSchema t)})

         ;; Export portable files
         export-result (exporter-ns
                        workflow-data
                        executor-defs
                        export-dir
                        base-name)]

     (merge result
            export-result
            {:tools (mapv :tool-name executor-defs)}))))
