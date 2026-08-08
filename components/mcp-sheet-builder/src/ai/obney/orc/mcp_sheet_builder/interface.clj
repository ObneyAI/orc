(ns ai.obney.orc.mcp-sheet-builder.interface
  "MCP Sheet Builder - Dynamic ORC behavior tree generation from MCP tool schemas.

   This is a 'sheet that builds sheets' - analyzes MCP server tools and generates
   valid ORC DSL code using pattern recognition (Google's 8 Agent Patterns).

   Key capabilities:
   - MCP tool analysis and pattern recognition
   - ORC workflow generation (code forms and data structures)
   - Dynamic executor generation and runtime loading
   - Event-sourced executor storage for persistence"
  (:require [ai.obney.orc.mcp-sheet-builder.core.mcp-client :as mcp-client]
            [ai.obney.orc.mcp-sheet-builder.core.analyzer :as analyzer]
            [ai.obney.orc.mcp-sheet-builder.core.generator :as generator]
            [ai.obney.orc.mcp-sheet-builder.core.validator :as validator]
            [ai.obney.orc.mcp-sheet-builder.core.schema-converter :as schema-converter]
            [ai.obney.orc.mcp-sheet-builder.core.executor-generator :as executor-gen]
            [ai.obney.orc.mcp-sheet-builder.core.executor-runtime :as executor-runtime]
            [ai.obney.orc.mcp-sheet-builder.core.builders :as builders]
            [ai.obney.orc.mcp-sheet-builder.core.exporter :as exporter]))

;; ============================================================================
;; MCP Client API
;; ============================================================================

(defn connect
  "Connect to an MCP server.

   Options:
   - :type - Connection type (:http, :stdio, :nrepl)
   - :url - Server URL (for HTTP type)
   - Additional options vary by type

   Returns an MCP connection map."
  [opts]
  (mcp-client/connect opts))

(defn list-tools
  "List available tools from an MCP connection.
   Returns a vector of tool definitions with schemas."
  [mcp-conn]
  (mcp-client/list-tools mcp-conn))

(defn call-tool
  "Call a tool on an MCP connection.
   Returns the tool result."
  [mcp-conn tool-name args]
  (mcp-client/call-tool mcp-conn tool-name args))

;; ============================================================================
;; MCP Registry API (Multi-Server)
;; ============================================================================

(defn create-registry
  "Create a registry of named MCP connections with auto-discovered tools.

   server-map is {\"server-name\" mcp-connection, ...}

   Returns a registry with all tools cataloged under prefixed names
   (e.g., \"linear/list_issues\"). Use with registry->call-tool-fn."
  [server-map]
  (mcp-client/create-registry server-map))

(defn registry->call-tool-fn
  "Create a multiplexing call-tool-fn from a registry.

   Routes 'server/tool' names to the appropriate connection.
   Non-namespaced tools are looked up across all servers (errors if ambiguous)."
  [registry]
  (mcp-client/registry->call-tool-fn registry))

(defn list-all-tools
  "List all tools in the registry with their prefixed names.
   Returns [{:prefixed-name \"linear/list_issues\" :server \"linear\" :name \"list_issues\" ...}]"
  [registry]
  (mcp-client/list-all-tools registry))

(defn close-all
  "Close all connections in a registry."
  [registry]
  (mcp-client/close-all registry))

;; ============================================================================
;; Schema Conversion
;; ============================================================================

(defn json-schema->malli
  "Convert JSON Schema to Malli schema, preserving descriptions.
   Returns a Malli schema suitable for ORC blackboard."
  [json-schema]
  (schema-converter/json-schema->malli json-schema))

;; ============================================================================
;; Analysis API
;; ============================================================================

(defn analyze-tools
  "Analyze MCP tools and extract semantic information.

   Returns a map with:
   - :tools - Vector of analyzed tool maps with capability tags
   - :relationships - Detected tool relationships
   - :patterns - Applicable workflow patterns with confidence scores

   Options:
   - :cache? - Whether to use cached analysis (default true)
   - :creative? - Include creative pattern exploration (default false)"
  ([mcp-conn]
   (builders/analyze-tools mcp-conn))
  ([mcp-conn opts]
   (builders/analyze-tools mcp-conn opts)))

;; ============================================================================
;; Generation API
;; ============================================================================

(defn generate-sheet
  "Generate an ORC workflow DSL from analyzed tools and a selected pattern.

   Returns a map with:
   - :workflow - The generated ORC workflow form
   - :blackboard - Generated blackboard schema
   - :code - String representation of the DSL"
  [analysis pattern-opts]
  (generator/generate-sheet analysis pattern-opts))

(defn validate-sheet
  "Validate a generated sheet for correctness.

   Returns a map with:
   - :valid? - Boolean indicating validity
   - :errors - Vector of error messages
   - :warnings - Vector of warning messages"
  [sheet]
  (validator/validate-sheet sheet))

;; ============================================================================
;; High-Level API
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
   (builders/build-from-mcp mcp-opts))
  ([mcp-opts opts]
   (builders/build-from-mcp mcp-opts opts)))

;; ============================================================================
;; Data Structure Generation (for direct build-workflow!)
;; ============================================================================

(defn generate-sheet-data
  "Generate an ORC workflow as data structure from analyzed tools.

   Unlike generate-sheet which returns quoted code forms,
   this returns a data structure that can be passed directly to build-workflow!

   Returns a map with:
   - :workflow-data - The workflow data structure {:workflow-name :blackboard-schema :root-node}
   - :blackboard - Generated blackboard schema
   - :pattern - Selected pattern key
   - :tools - Tool names"
  [analysis pattern-opts]
  (generator/generate-sheet-data analysis pattern-opts))

;; ============================================================================
;; Build API (creates sheets in event store)
;; ============================================================================

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
   (builders/build-sheet-from-mcp! ctx mcp-opts))
  ([ctx mcp-opts opts]
   (builders/build-sheet-from-mcp! ctx mcp-opts opts)))

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
   (builders/build-repl-researcher-sheet! ctx mcp-opts instruction))
  ([ctx mcp-opts instruction opts]
   (builders/build-repl-researcher-sheet! ctx mcp-opts instruction opts)))

;; ============================================================================
;; Export API (save generated sheets as reusable files)
;; ============================================================================

(defn export-generated-sheet!
  "Export a generated sheet to a file.

   Args:
     ctx - Context with :event-store
     sheet-id - UUID of the sheet to export
     path - File path to save to

   Options:
     :format - :dsl (Clojure code, default) or :edn (data structure)

   Returns the file path."
  [ctx sheet-id path & {:keys [format] :or {format :dsl}}]
  (exporter/export-generated-sheet! ctx sheet-id path :format format))

(defn build-and-export-sheet!
  "Build a sheet from MCP tools and export it to a file.

   Convenience function combining build-sheet-from-mcp! + export.
   This is the recommended way to create reusable MCP-powered sheets.

   Args:
     ctx - Context with :event-store
     mcp-opts - MCP connection options {:preset :tavily} or {:type :http ...}
     export-path - File path to save the sheet

   Options:
     :format - :dsl (default) or :edn
     :pattern - Specific pattern to use
     :pattern-hint - Description for pattern selection

   Returns:
   {:sheet-id uuid
    :workflow-name string
    :pattern keyword
    :tools [...]
    :exported-to string   ;; File path}"
  [ctx mcp-opts export-path & {:as opts}]
  (apply exporter/build-and-export-sheet! ctx mcp-opts export-path (mapcat identity opts)))

;; ============================================================================
;; Portable Export API (standalone sheets with executors)
;; ============================================================================

(defn export-executors!
  "Export executor definitions to a standalone .clj file.

   The generated file contains:
   - Namespace declaration with mcp-client and mulog requires
   - All executor functions
   - Executor registry map

   Can be loaded independently in any project with these dependencies.

   Args:
     executor-defs - Seq of executor definitions with :tool-name and :input-schema
     filepath - Output file path

   Options:
     :namespace - Custom namespace (default: derived from filepath)

   Returns the filepath."
  [executor-defs filepath & {:keys [namespace]}]
  (exporter/export-executors! executor-defs filepath :namespace namespace))

(defn export-portable-sheet!
  "Export a complete portable sheet package.

   Creates two files:
   - <dir>/<name>-executors.clj - All executor functions
   - <dir>/<name>.clj - Workflow definition that requires the executors

   The workflow's :fn references are updated to point to the executors namespace.

   Args:
     workflow-data - The workflow data structure
     executor-defs - Executor definitions (from generate-executors)
     dir - Output directory
     name - Base name for the files

   Returns {:sheet-file path :executors-file path :executors-ns string}"
  [workflow-data executor-defs dir name]
  (exporter/export-portable-sheet! workflow-data executor-defs dir name))

;; ============================================================================
;; Executor Generation API
;; ============================================================================

(defn generate-executor
  "Generate executor code for an MCP tool.

   Takes an MCP tool definition and generates:
   - :tool-id - UUID for this executor
   - :tool-name - Original MCP tool name
   - :source-code - Generated Clojure source code
   - :namespace-requires - Required namespaces for loading
   - :checksum - SHA-256 of source code
   - :fn-reference - Fully qualified function reference for ORC nodes

   The generated code follows the ORC executor signature:
   (defn call-<tool-name> [{:keys [inputs context]}] {\"output-key\" result})"
  [mcp-tool]
  (executor-gen/build-executor-definition mcp-tool))

(defn generate-executors
  "Generate executor definitions for multiple MCP tools."
  [mcp-tools]
  (executor-gen/build-executor-definitions mcp-tools))

;; ============================================================================
;; Executor Runtime API
;; ============================================================================

(defn load-executor!
  "Load a single executor into the runtime registry.

   Args:
   - tool-id: UUID of the tool
   - tool-name: Name of the MCP tool
   - source-code: Complete namespace source code
   - requires: Vector of namespace strings to require

   Options:
   - :checksum - If provided, verify source code integrity
   - :force? - If true, reload even if already loaded

   Returns {:success? bool :namespace ns-sym :fn executor-fn :error msg}"
  [tool-id tool-name source-code requires & opts]
  (apply executor-runtime/load-executor! tool-id tool-name source-code requires opts))

(defn get-executor
  "Resolve executor function by tool-id, tool-name, or qualified name.

   Args:
   - identifier: One of:
     - UUID tool-id
     - String tool-name
     - String qualified name like \"mcp.executors.dynamic.t3a4b/call-tool\"

   Returns the executor function or nil if not found."
  [identifier]
  (executor-runtime/get-executor identifier))

(defn list-loaded-executors
  "List all currently loaded executors.

   Returns seq of {:tool-id :tool-name :namespace :fn-reference}."
  []
  (executor-runtime/list-loaded-executors))

(defn clear-executor-registry!
  "Clear all loaded executors. Useful for testing."
  []
  (executor-runtime/clear-registry!))

;; ============================================================================
;; Build with Persistent Executors
;; ============================================================================

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
   (builders/build-sheet-with-executors! ctx mcp-opts))
  ([ctx mcp-opts opts]
   (builders/build-sheet-with-executors! ctx mcp-opts opts)))

;; ============================================================================
;; Build + Export Portable Files
;; ============================================================================

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
   (builders/build-and-export-portable! ctx mcp-opts export-dir))
  ([ctx mcp-opts export-dir opts]
   (builders/build-and-export-portable! ctx mcp-opts export-dir opts)))
