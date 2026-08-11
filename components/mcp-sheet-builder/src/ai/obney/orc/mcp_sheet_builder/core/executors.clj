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

(defn call-mcp-tool
  "Code executor that invokes an MCP tool at runtime.

   Expected inputs:
   - tool-name: Name of the MCP tool to call
   - tool-args: Map of arguments for the tool
   - Any additional inputs are passed as tool arguments

   Context must include:
   - :mcp-session - An MCP connection"
  [{:keys [inputs context execution-context] :as invocation}]
  (let [effective-context (merge invocation context execution-context)
        tool-name (or (get inputs :tool-name)
                      (get-in effective-context [:node :options :tool-name])
                      (some-> (get-in effective-context [:node :name])
                              (str/replace-first #"^call-" "")))
        mcp-session (:mcp-session effective-context)
        ;; Optional blackboard reads are represented as nil when absent. JSON
        ;; Schema optional means the member is omitted, not sent as explicit
        ;; null, so strip nils before invoking the MCP server.
        raw-args (into {} (remove (comp nil? val)) (dissoc inputs :tool-name))
        tool-args (if (and (= 1 (count raw-args)) (map? (val (first raw-args))))
                    (val (first raw-args))
                    raw-args)
        output-key (keyword (str tool-name "-result"))]
    (u/trace ::call-mcp-tool {:tool tool-name :args tool-args}
      (if mcp-session
        (let [result (mcp-client/call-tool mcp-session tool-name tool-args)]
          {output-key result})
        ;; Mock response for testing without MCP connection
        {output-key {:mock true
                     :tool tool-name
                     :args tool-args
                     :message "No MCP session in context - mock response"}}))))

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
  [{:keys [inputs context]}]
  (let [query (get inputs :query)
        tool-name (get inputs :tool-name "search")
        mcp-session (:mcp-session context)]
    (u/trace ::search-executor {:query query :tool tool-name}
      (if mcp-session
        (let [result (mcp-client/call-tool mcp-session tool-name {"query" query})]
          {:search-results result})
        {:search-results [{:mock true :query query}]}))))

(defn fetch-executor
  "Executor specialized for fetch/retrieval tools.

   Expected inputs:
   - path or url: Resource to fetch
   - tool-name: Name of the fetch tool

   Returns:
   - fetched-content: The retrieved content"
  [{:keys [inputs context]}]
  (let [path (or (get inputs :path)
                 (get inputs :url)
                 (get inputs :pathOrUrl))
        tool-name (get inputs :tool-name "fetch")
        mcp-session (:mcp-session context)]
    (u/trace ::fetch-executor {:path path :tool tool-name}
      (if mcp-session
        (let [result (mcp-client/call-tool mcp-session tool-name
                                           (or (when (get inputs :pathOrUrl)
                                                 {"pathOrUrl" path})
                                               {"path" path}))]
          {:fetched-content result})
        {:fetched-content {:mock true :path path}}))))

;; ============================================================================
;; Dynamic Executor Factory
;; ============================================================================

(defn make-tool-executor
  "Create an executor function for a specific MCP tool.

   Returns a function suitable for use as an ORC code node executor."
  [tool-name input-mapping output-key]
  (fn [{:keys [inputs context]}]
    (let [mcp-session (:mcp-session context)
          mapped-args (reduce-kv
                       (fn [acc input-key tool-arg]
                         (if-let [v (get inputs (keyword input-key))]
                           (assoc acc tool-arg v)
                           acc))
                       {}
                       input-mapping)]
      (u/trace ::dynamic-executor {:tool tool-name :args mapped-args}
        (if mcp-session
          (let [result (mcp-client/call-tool mcp-session tool-name mapped-args)]
            {output-key result})
          {output-key {:mock true :tool tool-name :args mapped-args}})))))

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
