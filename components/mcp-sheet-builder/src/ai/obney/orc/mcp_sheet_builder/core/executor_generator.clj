(ns ai.obney.orc.mcp-sheet-builder.core.executor-generator
  "Generates Clojure code for MCP tool executors from schemas.

   This module generates executable Clojure code strings that can be:
   1. Evaluated dynamically via load-string
   2. Stored in the event store for persistence
   3. Loaded at runtime into isolated namespaces

   Generated code follows the ORC executor signature:
   (defn fn-name [{:keys [inputs context]}] {\"output-key\" result})"
  (:require [clojure.string :as str]
            [clojure.pprint :as pprint]
            [clojure.walk :as walk]
            [cheshire.core :as json])
  (:import [java.security MessageDigest]
           [java.util UUID]))

;; ============================================================================
;; Checksum Generation
;; ============================================================================

(defn compute-checksum
  "Compute SHA-256 checksum of source code for integrity verification."
  [source-code]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest (.getBytes source-code "UTF-8"))]
    (apply str (map #(format "%02x" %) hash-bytes))))

(defn verify-checksum
  "Verify source code integrity against stored checksum."
  [source-code expected-checksum]
  (let [actual (compute-checksum source-code)]
    (when-not (= actual expected-checksum)
      (throw (ex-info "Checksum mismatch - code may have been tampered with"
                      {:expected expected-checksum
                       :actual actual})))))

;; ============================================================================
;; Code Generation Helpers
;; ============================================================================

(defn sanitize-name
  "Convert tool name to valid Clojure function name."
  [tool-name]
  (-> tool-name
      (str/replace #"[^a-zA-Z0-9_-]" "-")
      (str/replace #"-+" "-")
      (str/replace #"^-|-$" "")))

(defn- extract-input-params
  "Extract parameter names from JSON Schema properties.
   Returns vector of parameter name strings."
  [input-schema]
  ;; A schema discovered through the MCP client arrives with keyword keys; a
  ;; hand-written one may use strings. Both name the same parameters.
  (when-let [props (or (get input-schema "properties") (get input-schema :properties))]
    (mapv name (keys props))))

(defn- required-params
  "Get required parameter names from input schema."
  [input-schema]
  (set (map name (or (get input-schema "required") (get input-schema :required) []))))

;; ============================================================================
;; Executor Code Generation
;; ============================================================================

(defn generate-executor-code
  "Generate executor function code from MCP tool definition.

   Args:
   - tool-name: Name of the MCP tool
   - input-schema: JSON Schema for tool inputs
   - output-key: Key for output in result map (defaults to tool-name-result)

   Returns source code string that can be eval'd or stored."
  [{:keys [tool-name input-schema output-key]}]
  (let [fn-name (str "call-" (sanitize-name tool-name))
        params (or (extract-input-params input-schema) [])
        output-k (or output-key (str tool-name "-result"))
        ;; Build tool-args extraction code
        args-bindings (if (empty? params)
                        "{}"
                        (str "{"
                             (str/join ", "
                                       (for [p params]
                                         (str ":" p " (get inputs :" p ")")))
                             "}"))]
    (str
     "(defn " fn-name "\n"
     "  \"Generated executor for MCP tool: " tool-name "\n"
     "   Invokes the tool through the node's tool caller, else the execution's\n"
     "   :mcp-session, else (explicit dry run only) a marked stand-in; with none\n"
     "   of these it fails. Auto-generated - do not edit directly.\"\n"
     "  [invocation]\n"
     "  (let [{:keys [inputs]} invocation\n"
     "        raw-args " args-bindings "\n"
     "        tool-args (into {} (filter (fn [[_ v]] (some? v)) raw-args))]\n"
     "    {:" output-k " (executors/invoke-tool invocation \"" tool-name "\" tool-args)}))\n")))

(defn generate-namespace-code
  "Generate complete namespace with requires and executor.

   Args:
   - tool-id: UUID for the tool (used in namespace name)
   - tool-name: MCP tool name
   - input-schema: JSON Schema for tool inputs
   - output-key: Optional custom output key

   Returns complete namespace source code string."
  [{:keys [tool-id tool-name input-schema output-key mcp-server]
    :or {mcp-server "mcp"}}]
  (let [ns-suffix (subs (str tool-id) 0 8)
        ns-name (str "mcp.executors.dynamic.t" ns-suffix)
        fn-name (str "call-" (sanitize-name tool-name))
        executor-code (generate-executor-code {:tool-name tool-name
                                                :input-schema input-schema
                                                :output-key output-key})]
    (str
     "(ns " ns-name "\n"
     "  \"Dynamically generated executor namespace for MCP tool: " tool-name "\n"
     "   Generated at: " (java.time.Instant/now) "\n"
     "   Tool ID: " tool-id "\"\n"
     "  (:require [ai.obney.orc.mcp-sheet-builder.core.executors :as executors]\n"
     "            [com.brunobonacci.mulog :as u]))\n"
     "\n"
     executor-code)))

(defn generate-executor-fn-reference
  "Generate the fully-qualified function reference for ORC code nodes.

   Returns string like: \"mcp.executors.dynamic.t3a4b5c6d/call-tool-name\""
  [tool-id tool-name]
  (let [ns-suffix (subs (str tool-id) 0 8)
        fn-name (str "call-" (sanitize-name tool-name))]
    (str "mcp.executors.dynamic.t" ns-suffix "/" fn-name)))

;; ============================================================================
;; Executor Definition Builder
;; ============================================================================

(defn build-executor-definition
  "Build a complete executor definition from an MCP tool.

   Returns a map with:
   - :tool-id - UUID for this executor
   - :tool-name - Original MCP tool name
   - :source-code - Generated Clojure source code
   - :namespace-requires - Required namespaces for loading
   - :checksum - SHA-256 of source code
   - :fn-reference - Fully qualified function reference for ORC nodes"
  [{:keys [name inputSchema] :as mcp-tool}]
  (let [tool-id (UUID/randomUUID)
        source-code (generate-namespace-code
                     {:tool-id tool-id
                      :tool-name name
                      :input-schema inputSchema})
        checksum (compute-checksum source-code)]
    {:tool-id tool-id
     :tool-name name
     :source-code source-code
     :namespace-requires ["ai.obney.orc.mcp-sheet-builder.core.executors"
                          "com.brunobonacci.mulog"]
     :checksum checksum
     :fn-reference (generate-executor-fn-reference tool-id name)}))

(defn build-executor-definitions
  "Build executor definitions for multiple MCP tools.

   Returns a vector of executor definition maps."
  [mcp-tools]
  (mapv build-executor-definition mcp-tools))

;; ============================================================================
;; Code Validation
;; ============================================================================

(defn validate-generated-code
  "Validate generated code can be read as Clojure.
   Returns {:valid? bool :errors [...]} "
  [source-code]
  (try
    (read-string (str "(do " source-code ")"))
    {:valid? true :errors []}
    (catch Exception e
      {:valid? false
       :errors [(str "Syntax error: " (.getMessage e))]})))

;; ============================================================================
;; File Content Generation (for portable export)
;; ============================================================================

(defn derive-namespace-from-path
  "Derive a Clojure namespace from a file path."
  [filepath]
  (-> filepath
      (str/replace #"\.clj$" "")
      (str/replace #".*/" "")
      (str/replace #"_" "-")))

(defn generate-executors-file-content
  "Generate complete executors file content with namespace and all functions."
  [executor-defs ns-name]
  (let [header (str "(ns " ns-name "\n"
                    "  \"Generated MCP tool executors.\n"
                    "   Auto-generated by MCP Sheet Builder - do not edit directly.\n"
                    "   Generated at: " (java.time.Instant/now) "\"\n"
                    "  (:require [ai.obney.orc.mcp-sheet-builder.core.executors :as executors]\n"
                    "            [com.brunobonacci.mulog :as u]))\n\n")
        ;; Generate each executor function
        executor-fns (for [{:keys [tool-name input-schema]} executor-defs]
                       (generate-executor-code
                        {:tool-name tool-name
                         :input-schema input-schema}))
        ;; Generate registry map
        registry-entries (for [{:keys [tool-name]} executor-defs]
                           (str "   \"" tool-name "\" call-" (sanitize-name tool-name)))
        registry (str "\n;; Executor registry for lookup\n"
                      "(def executors\n"
                      "  {" (str/join "\n" registry-entries) "})\n")]
    (str header
         (str/join "\n" executor-fns)
         registry)))

(defn generate-workflow-file-content
  "Generate workflow file content with namespace and workflow definition."
  [workflow-data executors-ns ns-name tools]
  (let [header (str "(ns " ns-name "\n"
                    "  \"" (:workflow-name workflow-data) " - Generated by MCP Sheet Builder.\n"
                    "   Auto-generated - do not edit directly.\n"
                    "   Generated at: " (java.time.Instant/now) "\"\n"
                    "  (:require [ai.obney.orc.orc-service.interface :as sheet]\n"
                    "            [" executors-ns " :as exec]))\n\n")
        ;; Update :fn references to point to executors namespace
        updated-workflow (walk/postwalk
                          (fn [x]
                            (if (and (map? x)
                                     (= :code (:executor x))
                                     (string? (:fn x)))
                              (let [;; Try to extract tool name from node name "call-<tool>"
                                    name-match (when-let [n (:name x)]
                                                 (second (re-matches #"call-(.+)" n)))
                                    ;; Or from writes "<tool>-result"
                                    writes-match (when-let [w (first (:writes x))]
                                                   (second (re-matches #"(.+)-result" (name w))))
                                    ;; Or check if :fn already contains a tool name pattern
                                    fn-match (some #(when (str/includes? (:fn x) (str "call-" (sanitize-name %))) %)
                                                   tools)
                                    ;; Use the first match found
                                    tool-name (or fn-match name-match writes-match)]
                                (if (and tool-name (some #(= tool-name %) tools))
                                  (assoc x :fn (str executors-ns "/call-" (sanitize-name tool-name)))
                                  x))
                              x))
                          workflow-data)
        workflow-def (str "(def workflow\n"
                          "  \"Workflow definition - pass to (sheet/build-workflow! ctx workflow)\"\n"
                          "  " (with-out-str (pprint/pprint updated-workflow)) ")\n\n")
        build-fn (str "(defn build!\n"
                      "  \"Build this workflow in the event store. Returns sheet-id.\"\n"
                      "  [ctx]\n"
                      "  (sheet/build-workflow! ctx workflow))\n")]
    (str header workflow-def build-fn)))

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
