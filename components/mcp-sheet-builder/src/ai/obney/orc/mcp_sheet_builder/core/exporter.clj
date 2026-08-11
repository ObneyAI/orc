(ns ai.obney.orc.mcp-sheet-builder.core.exporter
  "Export API for MCP Sheet Builder.

   Handles format-aware sheet export, executor file I/O,
   and portable sheet package generation."
  (:require [ai.obney.orc.mcp-sheet-builder.core.executor-generator :as executor-gen]
            [ai.obney.orc.orc-service.interface :as sheet]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]))

;; ============================================================================
;; Sheet Export
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
  (case format
    :dsl (sheet/save-sheet-as-dsl! ctx sheet-id path)
    :edn (sheet/save-sheet! ctx sheet-id path))
  path)

(defn build-and-export-sheet!
  "Build a sheet from MCP tools and export it to a file.

   Convenience function combining build-sheet-from-mcp! + export.
   This is the recommended way to create reusable MCP-powered sheets.

   Args:
     ctx - Context with :event-store
     mcp-opts - MCP connection options {:preset :tavily} or {:type :streamable-http ...}
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
  [ctx mcp-opts export-path & {:keys [format] :or {format :dsl} :as opts}]
  (let [build-sheet-from-mcp! (requiring-resolve 'ai.obney.orc.mcp-sheet-builder.core.builders/build-sheet-from-mcp!)
        build-opts (dissoc opts :format)
        result (build-sheet-from-mcp! ctx mcp-opts build-opts)]
    (export-generated-sheet! ctx (:sheet-id result) export-path :format format)
    (assoc result :exported-to export-path)))

;; ============================================================================
;; Executor Export
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
  (let [ns-name (or namespace (executor-gen/derive-namespace-from-path filepath))
        content (executor-gen/generate-executors-file-content
                 (map (fn [ed] {:tool-name (:tool-name ed)
                                :input-schema (or (:input-schema ed)
                                                  (:inputSchema ed)
                                                  {})})
                      executor-defs)
                 ns-name)]
    (io/make-parents filepath)
    (spit filepath content)
    (println "Exported executors to:" filepath)
    filepath))

;; ============================================================================
;; Portable Export
;; ============================================================================

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
  (let [executors-ns (str name "-executors")
        workflow-ns name
        executors-file (str dir "/" name "-executors.clj")
        sheet-file (str dir "/" name ".clj")
        tools (mapv :tool-name executor-defs)]

    ;; Export executors
    (export-executors! executor-defs executors-file :namespace executors-ns)

    ;; Export workflow
    (let [workflow-content (executor-gen/generate-workflow-file-content
                            workflow-data executors-ns workflow-ns tools)]
      (io/make-parents sheet-file)
      (spit sheet-file workflow-content)
      (println "Exported workflow to:" sheet-file))

    {:sheet-file sheet-file
     :executors-file executors-file
     :executors-ns executors-ns}))
