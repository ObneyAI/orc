(ns ai.obney.orc.mcp-sheet-builder.core.executors
  "MCP tool executor wrappers for use in generated ORC sheets.

   These executors are invoked by ORC code nodes and wrap MCP tool calls."
  (:require [ai.obney.orc.mcp-sheet-builder.core.mcp-client :as mcp-client]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]
            [cheshire.core :as json]))

;; ============================================================================
;; Generic MCP Tool Executor
;; ============================================================================

(defn invoke-tool
  "Invoke an MCP tool for an executor. The ONE resolution order:
   1. the node's tool caller (:call-tool-fn), which carries the configured gate
      and declared contracts;
   2. else the execution's MCP connection (:mcp-session);
   3. else, only when the execution explicitly asked for a dry run
      (:mcp/dry-run? true), a stand-in marked {:dry-run? true ...};
   4. else an explicit failure.
  `invocation` is the executor's argument; :context and :execution-context are
  merged under its top-level keys exactly as `call-mcp-tool` reads them."
  [{:keys [context execution-context] :as invocation} tool-name tool-args]
  (let [ctx (merge invocation context execution-context)
        call-tool-fn (:call-tool-fn ctx)
        mcp-session (:mcp-session ctx)]
    (cond
      call-tool-fn (call-tool-fn tool-name tool-args)
      mcp-session (mcp-client/call-tool mcp-session tool-name tool-args)
      (true? (:mcp/dry-run? ctx)) {:dry-run? true :tool tool-name :args tool-args}
      :else (throw (ex-info (str "Cannot invoke MCP tool '" tool-name
                                 "': no tool caller, no MCP session and no dry run requested")
                            {:tool tool-name})))))

(defn call-mcp-tool
  "Code executor that invokes an MCP tool at runtime.

   Expected inputs:
   - tool-name: Name of the MCP tool to call
   - tool-args: Map of arguments for the tool
   - Any additional inputs are passed as tool arguments

   The tool is invoked through `invoke-tool` (node caller, else MCP session,
   else explicit dry run, else failure)."
  [{:keys [inputs context execution-context] :as invocation}]
  (let [effective-context (merge invocation context execution-context)
        tool-name (or (get inputs :tool-name)
                      (get-in effective-context [:node :options :tool-name])
                      (some-> (get-in effective-context [:node :name])
                              (str/replace-first #"^call-" "")))
        ;; Optional blackboard reads are represented as nil when absent. JSON
        ;; Schema optional means the member is omitted, not sent as explicit
        ;; null, so strip nils before invoking the MCP server.
        raw-args (into {} (remove (comp nil? val)) (dissoc inputs :tool-name))
        tool-args (if (and (= 1 (count raw-args)) (map? (val (first raw-args))))
                    (val (first raw-args))
                    raw-args)
        output-key (keyword (str tool-name "-result"))]
    (u/trace ::call-mcp-tool {:tool tool-name :args tool-args}
      {output-key (invoke-tool invocation tool-name tool-args)})))

;; ============================================================================
;; Specialized Executors for Common Patterns
;; ============================================================================

(defn search-executor
  "Executor specialized for search tools.

   Expected inputs:
   - query: Search query string
   - tool-name: Name of the search tool

   Returns:
   - search-results: Vector of search results"
  [{:keys [inputs] :as invocation}]
  (let [query (get inputs :query)
        tool-name (get inputs :tool-name "search")]
    (u/trace ::search-executor {:query query :tool tool-name}
      {:search-results (invoke-tool invocation tool-name {"query" query})})))

(defn fetch-executor
  "Executor specialized for fetch/retrieval tools.

   Expected inputs:
   - path or url: Resource to fetch
   - tool-name: Name of the fetch tool

   Returns:
   - fetched-content: The retrieved content"
  [{:keys [inputs] :as invocation}]
  (let [path (or (get inputs :path)
                 (get inputs :url)
                 (get inputs :pathOrUrl))
        tool-name (get inputs :tool-name "fetch")]
    (u/trace ::fetch-executor {:path path :tool tool-name}
      {:fetched-content (invoke-tool invocation tool-name
                                     (or (when (get inputs :pathOrUrl)
                                           {"pathOrUrl" path})
                                         {"path" path}))})))

;; ============================================================================
;; Dynamic Executor Factory
;; ============================================================================

(defn make-tool-executor
  "Create an executor function for a specific MCP tool.

   Returns a function suitable for use as an ORC code node executor."
  [tool-name input-mapping output-key]
  (fn [{:keys [inputs] :as invocation}]
    (let [mapped-args (reduce-kv
                       (fn [acc input-key tool-arg]
                         (if-let [v (get inputs (keyword input-key))]
                           (assoc acc tool-arg v)
                           acc))
                       {}
                       input-mapping)]
      (u/trace ::dynamic-executor {:tool tool-name :args mapped-args}
        {output-key (invoke-tool invocation tool-name mapped-args)}))))

;; ============================================================================
;; Executor Registry
;; ============================================================================

(def executor-registry
  "Registry of built-in executors."
  {"ai.obney.orc.mcp-sheet-builder.core.executors/call-mcp-tool" #'call-mcp-tool
   "ai.obney.orc.mcp-sheet-builder.core.executors/search-executor" #'search-executor
   "ai.obney.orc.mcp-sheet-builder.core.executors/fetch-executor" #'fetch-executor})

(defn resolve-executor
  "Resolve an executor function from its qualified name."
  [executor-name]
  (or (get executor-registry executor-name)
      (when-let [v (resolve (symbol executor-name))]
        @v)))

;; ============================================================================
;; Context Building
;; ============================================================================

(defn build-execution-context
  "Build an execution context with MCP session.

   Options:
   - :mcp-opts - Options for MCP connection
   - :additional - Additional context keys"
  [{:keys [mcp-opts additional]}]
  (let [mcp-session (when mcp-opts
                      (mcp-client/connect-legacy mcp-opts))]
    (merge {:mcp-session mcp-session}
           additional)))

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
