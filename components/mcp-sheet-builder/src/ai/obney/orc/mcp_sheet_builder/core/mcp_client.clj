(ns ai.obney.orc.mcp-sheet-builder.core.mcp-client
  "Portable MCP client for stdio and Streamable HTTP servers."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u])
  (:import (java.net URI)
           (java.time Duration Instant)
           (java.util.concurrent TimeUnit)))

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol MCPClient
  "Protocol for MCP client implementations."
  (list-tools* [this] "List available tools.")
  (call-tool* [this tool-name args] "Call a tool with arguments.")
  (close* [this] "Close the connection."))

(def supported-transports #{:stdio :streamable-http})

(defn create-trace-context
  "Create a sanitized MCP lifecycle trace context. `record!`, when supplied,
   receives each activity and can persist it through a consumer's durable trace
   boundary. Activities are always retained locally for inspection."
  ([] (create-trace-context nil))
  ([record!]
   {:activities (atom []) :record! record!}))

(defn trace-activities [trace-context]
  (vec (or (some-> trace-context :activities deref) [])))

(defn- now [] (Instant/now))

(defn- emit!
  [trace-context server-id transport phase status & [{:keys [request-id tool-name
                                                              started-at byte-count failure]}]]
  (when trace-context
    (let [completed-at (now)
          activity (cond-> {:server-id server-id
                            :transport transport
                            :phase phase
                            :status status
                            :started-at (or started-at completed-at)
                            :completed-at completed-at}
                     request-id (assoc :request-id request-id)
                     tool-name (assoc :tool-name tool-name)
                     true (assoc :duration-ms (.toMillis
                                                (Duration/between (or started-at completed-at)
                                                                  completed-at)))
                     byte-count (assoc :byte-count byte-count)
                     failure (assoc :failure (select-keys failure [:orc/error :mcp/phase :message])))]
      (when-let [activities (:activities trace-context)]
        (swap! activities conj activity))
      (when-let [record! (:record! trace-context)]
        (record! activity))
      activity)))

(defn- failure
  ([type server-id transport phase message]
   (failure type server-id transport phase message nil))
  ([type server-id transport phase message cause]
   (ex-info message
            {:orc/error type
             :mcp/server-id server-id
             :mcp/transport transport
             :mcp/phase phase
             :message message}
            cause)))

(defn- ensure-active! [closed? server-id transport]
  (when @closed?
    (throw (failure :mcp/connection-closed server-id transport :request
                    "MCP connection is closed"))))

;; ============================================================================
;; Streamable HTTP MCP Client (MCP 2025-03-26 spec)
;; ============================================================================

(defn- parse-sse-response
  "Parse an SSE (Server-Sent Events) response body.
   Returns the JSON-RPC result from the data lines."
  [body]
  (let [messages (->> (str/split body #"\r?\n\r?\n")
                      (keep (fn [event]
                              (let [data (->> (str/split-lines event)
                                              (filter #(str/starts-with? % "data:"))
                                              (map #(str/trim (subs % 5)))
                                              (str/join "\n"))]
                                (when-not (str/blank? data)
                                  (json/parse-string data true))))))]
    (or (first (filter :id messages)) (first messages))))

(defn- parse-response-body
  "Parse the response body, handling both JSON and SSE formats."
  [response]
  (let [content-type (or (get-in response [:headers "content-type"])
                         (get-in response [:headers "Content-Type"])
                         "")
        body (:body response)]
    (cond
      ;; SSE response - parse the event stream
      (str/includes? content-type "text/event-stream")
      (parse-sse-response (if (string? body) body (slurp body)))

      ;; JSON response - body may already be parsed or be a string
      (map? body)
      body

      (string? body)
      (json/parse-string body true)

      :else
      body)))

(defn- response-header [response header-name]
  (let [wanted (str/lower-case header-name)]
    (some (fn [[k v]] (when (= wanted (str/lower-case (clojure.core/name k))) v))
          (:headers response))))

(defn- origin [url]
  (let [uri (URI. url)
        scheme (str/lower-case (.getScheme uri))
        port (let [p (.getPort uri)]
               (if (neg? p) (if (= "https" scheme) 443 80) p))]
    (str scheme "://" (str/lower-case (.getHost uri)) ":" port)))

(defn- redirected-request
  [method configured-url url configured-headers authorized-origins request]
  (loop [current url redirects 0]
    (let [same-or-authorized? (or (= (origin current) (origin configured-url))
                                  (contains? authorized-origins (origin current)))
          response (http/request (merge request
                                        {:method method
                                         :url current
                                         :headers (merge (if same-or-authorized?
                                                           (:headers request)
                                                           (dissoc (:headers request)
                                                                   "Mcp-Session-Id"
                                                                   "Authorization"
                                                                   "Cookie"))
                                                         (when same-or-authorized? configured-headers))
                                         :redirect-strategy :none
                                         :throw-exceptions false
                                         :as :text}))
          status (:status response)]
      (if (contains? #{301 302 303 307 308} status)
        (let [location (response-header response "location")]
          (when (or (nil? location) (>= redirects 5))
            (throw (ex-info "Invalid or excessive HTTP redirect" {:status status})))
          (recur (str (.resolve (URI. current) location)) (inc redirects)))
        response))))

(defn- json-rpc-result! [parsed server-id transport phase]
  (when-not (map? parsed)
    (throw (failure :mcp/protocol-error server-id transport phase
                    "MCP server returned malformed protocol output")))
  (when-let [error (:error parsed)]
    (throw (failure :mcp/protocol-error server-id transport phase
                    (str "MCP JSON-RPC error " (:code error) ": " (:message error)))))
  (if (contains? parsed :result)
    (:result parsed)
    (throw (failure :mcp/protocol-error server-id transport phase
                    "MCP JSON-RPC response has no result"))))

(defn- http-exchange!
  [client method payload phase]
  (let [{:keys [url headers authorized-origins session-id request-id server-id transport]} client
        id (when (and payload (not (str/starts-with? (:method payload) "notifications/")))
             (swap! request-id inc))
        body (when payload (json/generate-string (cond-> payload id (assoc :id id))))
        response (redirected-request method url url headers authorized-origins
                                     {:headers (cond-> {"Content-Type" "application/json"
                                                        "Accept" "application/json, text/event-stream"}
                                                 @session-id (assoc "Mcp-Session-Id" @session-id))
                                      :body body})]
    (when-let [new-session (response-header response "mcp-session-id")]
      (reset! session-id new-session))
    (when-not (<= 200 (:status response) 299)
      (throw (failure :mcp/transport-failure server-id transport phase
                      (str "MCP HTTP request failed with status " (:status response)))))
    {:id id :response response
     :parsed (when-not (str/blank? (:body response)) (parse-response-body response))}))

(defrecord StreamableHTTPMCPClient [url session-id headers authorized-origins request-id
                                    server-id transport trace-context closed?]
  MCPClient
  (list-tools* [this]
    (ensure-active! closed? server-id transport)
    (let [started (now)
          {:keys [id parsed response]} (http-exchange! this :post
                                                       {:jsonrpc "2.0" :method "tools/list"}
                                                       :tools/list)
          tools (:tools (json-rpc-result! parsed server-id transport :tools/list))]
      (emit! trace-context server-id transport :tools-listed :succeeded
             {:request-id id :started-at started :byte-count (count (:body response))})
      (vec tools)))

  (call-tool* [this tool-name args]
    (ensure-active! closed? server-id transport)
    (let [started (now)
          correlation-id (str (random-uuid))]
      (emit! trace-context server-id transport :tool-invocation-requested :requested
             {:request-id correlation-id :tool-name tool-name :started-at started})
      (try
        (let [{:keys [id parsed response]} (http-exchange! this :post
                                                           {:jsonrpc "2.0" :method "tools/call"
                                                            :params {:name tool-name :arguments args}}
                                                           :tools/call)
              result (json-rpc-result! parsed server-id transport :tools/call)]
          (emit! trace-context server-id transport :tool-invocation-completed :succeeded
                 {:request-id correlation-id :tool-name tool-name :started-at started
                  :byte-count (count (:body response))})
          result)
        (catch Exception e
          (emit! trace-context server-id transport :tool-invocation-failed :failed
                 {:request-id correlation-id :tool-name tool-name
                  :started-at started :failure (ex-data e)})
          (throw e)))))

  (close* [this]
    (when (compare-and-set! closed? false true)
      (when @session-id
        (try (http-exchange! this :delete nil :close) (catch Exception _)))
      (reset! session-id nil)
      (emit! trace-context server-id transport :connection-closed :succeeded))))

(defn- connect-http [{:keys [url headers authorized-redirect-origins server-id] :as opts}
                     trace-context]
  (when-not (and (string? url) (contains? #{"http" "https"} (.getScheme (URI. url))))
    (throw (failure :mcp/invalid-configuration server-id :streamable-http :connect
                    "Streamable HTTP requires an absolute HTTP(S) URL")))
  (let [client (map->StreamableHTTPMCPClient
                 {:url url :headers (or headers {})
                  :authorized-origins (set (map origin authorized-redirect-origins))
                  :session-id (atom nil) :request-id (atom 0)
                  :server-id (or server-id url) :transport :streamable-http
                  :trace-context trace-context :closed? (atom false)})
        started (now)]
    (emit! trace-context (:server-id client) :streamable-http :transport-contacted :requested
           {:started-at started})
    (emit! trace-context (:server-id client) :streamable-http :initialization-requested :requested
           {:started-at started})
    (try
      (let [{:keys [parsed]} (http-exchange! client :post
                                             {:jsonrpc "2.0" :method "initialize"
                                              :params {:protocolVersion "2025-03-26"
                                                       :capabilities {}
                                                       :clientInfo {:name "orc-mcp-client"
                                                                    :version "1.0.0"}}}
                                             :initialize)
            result (json-rpc-result! parsed (:server-id client) :streamable-http :initialize)]
        (when-not (string? (:protocolVersion result))
          (throw (failure :mcp/initialization-failure (:server-id client)
                          :streamable-http :initialize "MCP initialization did not negotiate a protocol")))
        (http-exchange! client :post {:jsonrpc "2.0" :method "notifications/initialized"}
                        :initialized)
        (emit! trace-context (:server-id client) :streamable-http
               :initialization-completed :succeeded {:started-at started})
        client)
      (catch Exception e
        (reset! (:closed? client) true)
        (emit! trace-context (:server-id client) :streamable-http
               :initialization-failed :failed {:started-at started :failure (ex-data e)})
        (throw (if (:orc/error (ex-data e)) e
                 (failure :mcp/initialization-failure (:server-id client)
                          :streamable-http :initialize "MCP initialization failed" e)))))))

;; ============================================================================
;; Stdio MCP Client
;; ============================================================================

(defn- fail-pending! [pending exception]
  (let [requests (vals (swap! pending (constantly {})))]
    (doseq [response requests]
      (deliver response exception))))

(defn- stdio-send!
  [client method params notification?]
  (ensure-active! (:closed? client) (:server-id client) :stdio)
  (let [id (when-not notification? (swap! (:request-id client) inc))
        response (when id (promise))
        message (cond-> {:jsonrpc "2.0" :method method}
                  params (assoc :params params)
                  id (assoc :id id))]
    (when id (swap! (:pending client) assoc id response))
    (try
      (locking (:write-lock client)
        (.write (:writer client) (json/generate-string message))
        (.newLine (:writer client))
        (.flush (:writer client)))
      (if notification?
        nil
        (let [value (deref response (:request-timeout-ms client) ::timeout)]
          (swap! (:pending client) dissoc id)
          (when (= ::timeout value)
            (throw (failure :mcp/transport-failure (:server-id client) :stdio method
                            "Timed out waiting for MCP stdio response")))
          (when (instance? Throwable value) (throw value))
          {:id id :parsed value}))
      (catch Exception e
        (when id (swap! (:pending client) dissoc id))
        (throw e)))))

(defrecord StdioMCPClient [process writer pending request-id write-lock request-timeout-ms
                           shutdown-timeout-ms server-id transport trace-context closed?
                           stdout-reader stderr-reader exit-watcher]
  MCPClient
  (list-tools* [this]
    (let [started (now)
          {:keys [id parsed]} (stdio-send! this "tools/list" nil false)
          tools (:tools (json-rpc-result! parsed server-id transport :tools/list))]
      (emit! trace-context server-id transport :tools-listed :succeeded
             {:request-id id :started-at started})
      (vec tools)))

  (call-tool* [this tool-name args]
    (let [started (now)
          correlation-id (str (random-uuid))]
      (emit! trace-context server-id transport :tool-invocation-requested :requested
             {:request-id correlation-id :tool-name tool-name :started-at started})
      (try
        (let [{:keys [id parsed]} (stdio-send! this "tools/call"
                                               {:name tool-name :arguments args} false)
              result (json-rpc-result! parsed server-id transport :tools/call)]
          (emit! trace-context server-id transport :tool-invocation-completed :succeeded
                 {:request-id correlation-id :tool-name tool-name :started-at started})
          result)
        (catch Exception e
          (emit! trace-context server-id transport :tool-invocation-failed :failed
                 {:request-id correlation-id :tool-name tool-name
                  :started-at started :failure (ex-data e)})
          (throw e)))))

  (close* [_]
    (when (compare-and-set! closed? false true)
      (try (.close writer) (catch Exception _))
      (when-not (.waitFor process shutdown-timeout-ms TimeUnit/MILLISECONDS)
        (.destroy process)
        (when-not (.waitFor process shutdown-timeout-ms TimeUnit/MILLISECONDS)
          (.destroyForcibly process)
          (.waitFor process shutdown-timeout-ms TimeUnit/MILLISECONDS)))
      (fail-pending! pending
                     (failure :mcp/connection-closed server-id transport :close
                              "MCP stdio connection closed"))
      (emit! trace-context server-id transport :connection-closed :succeeded))))

(defn- start-stdio-readers! [client]
  (let [stdout-reader
        (future
          (try
            (with-open [reader (io/reader (.getInputStream (:process client)))]
              (doseq [line (line-seq reader)]
                (try
                  (let [message (json/parse-string line true)
                        id (:id message)]
                    (if-let [response (and id (get @(:pending client) id))]
                      (do (swap! (:pending client) dissoc id)
                          (deliver response message))
                      (when id
                        (throw (failure :mcp/protocol-error (:server-id client) :stdio
                                        :response "MCP stdio response has an unknown request id")))))
                  (catch Exception e
                    (fail-pending! (:pending client)
                                   (failure :mcp/malformed-output (:server-id client) :stdio
                                            :response "Malformed MCP stdio protocol output" e))
                    (.destroy (:process client))))))
            (catch Exception e
              (when-not @(:closed? client)
                (fail-pending! (:pending client) e)))))
        stderr-reader
        (future
          (try
            (with-open [reader (io/reader (.getErrorStream (:process client)))]
              (doseq [_ (line-seq reader)] nil))
            (catch Exception _)))
        exit-watcher
        (future
          (let [exit-code (.waitFor (:process client))]
            (when-not @(:closed? client)
              (fail-pending! (:pending client)
                             (failure :mcp/process-exited (:server-id client) :stdio
                                      :process-exited
                                      (str "MCP stdio process exited with code " exit-code))))
            (emit! (:trace-context client) (:server-id client) :stdio
                   :process-exited (if (zero? exit-code) :succeeded :failed)
                   {:failure (when-not (zero? exit-code)
                               {:orc/error :mcp/process-exited
                                :mcp/phase :process-exited
                                :message (str "Process exited with code " exit-code)})})))]
    (assoc client :stdout-reader stdout-reader :stderr-reader stderr-reader
           :exit-watcher exit-watcher)))

(defn- connect-stdio
  [{:keys [command args env working-directory server-id request-timeout-ms
           shutdown-timeout-ms]} trace-context]
  (when-not (and (string? command) (not (str/blank? command)))
    (throw (failure :mcp/invalid-configuration server-id :stdio :connect
                    "Stdio transport requires a command")))
  (let [started (now)]
    (try
      (let [builder (ProcessBuilder. ^java.util.List (vec (cons command (or args []))))
            _ (when working-directory (.directory builder (io/file working-directory)))
            environment (.environment builder)
            _ (doseq [[name value] (or env {})] (.put environment name value))
            process (.start builder)
            client (->StdioMCPClient process
                                     (io/writer (.getOutputStream process))
                                     (atom {}) (atom 0) (Object.)
                                     (or request-timeout-ms 10000)
                                     (or shutdown-timeout-ms 1000)
                                     (or server-id command) :stdio trace-context (atom false)
                                     nil nil nil)
            client (start-stdio-readers! client)]
        (emit! trace-context (:server-id client) :stdio :transport-contacted :succeeded
               {:started-at started})
        (emit! trace-context (:server-id client) :stdio :initialization-requested :requested
               {:started-at started})
        (try
          (let [{:keys [parsed]} (stdio-send! client "initialize"
                                              {:protocolVersion "2025-03-26"
                                               :capabilities {}
                                               :clientInfo {:name "orc-mcp-client" :version "1.0.0"}}
                                              false)
                result (json-rpc-result! parsed (:server-id client) :stdio :initialize)]
            (when-not (string? (:protocolVersion result))
              (throw (failure :mcp/initialization-failure (:server-id client) :stdio
                              :initialize "MCP initialization did not negotiate a protocol")))
            (stdio-send! client "notifications/initialized" nil true)
            (emit! trace-context (:server-id client) :stdio
                   :initialization-completed :succeeded {:started-at started})
            client)
          (catch Exception e
            (close* client)
            (emit! trace-context (:server-id client) :stdio :initialization-failed :failed
                   {:started-at started :failure (ex-data e)})
            (throw e))))
      (catch Exception e
        (throw (if (:orc/error (ex-data e)) e
                 (failure :mcp/initialization-failure (or server-id command) :stdio
                          :initialize "MCP stdio initialization failed" e)))))))

;; ============================================================================
;; Claude MCP Bridge (Uses Claude Code's infrastructure)
;; ============================================================================

(defrecord ClaudeMCPClient [server-name tools-cache]
  MCPClient
  (list-tools* [_]
    ;; Tools are pre-loaded from Claude Code's MCP configuration
    @tools-cache)

  (call-tool* [_ tool-name args]
    ;; This will be called via code executor in ORC context
    ;; The actual call happens through Claude Code's MCP infrastructure
    (throw (ex-info "ClaudeMCPClient.call-tool should be invoked via ORC code executor"
                    {:tool tool-name :args args})))

  (close* [_]
    nil))

(defn- connect-claude-mcp
  "Connect to Claude Code's MCP infrastructure.
   This requires tools to be pre-loaded from the MCP configuration."
  [{:keys [server-name tools]}]
  (->ClaudeMCPClient server-name (atom (or tools []))))

;; ============================================================================
;; Static Tool Definitions (for POC/testing)
;; ============================================================================

(defrecord StaticMCPClient [name tools call-tool-handler]
  MCPClient
  (list-tools* [_]
    tools)

  (call-tool* [_ tool-name args]
    (if call-tool-handler
      (call-tool-handler tool-name args)
      (do (u/log ::static-call-tool :tool tool-name :args args)
          {:result "Static MCP client - tool call not executed"})))

  (close* [_]
    nil))

(defn- connect-static*
  "Create a static MCP client with pre-defined tools.
   Useful for testing and POC without an actual MCP server.
   Optionally accepts :call-tool-handler (fn [tool-name args] -> result)
   for deterministic tool responses in tests."
  [{:keys [name tools call-tool-handler]}]
  (->StaticMCPClient name tools call-tool-handler))

;; ============================================================================
;; Tool Definitions for Known MCP Servers
;; ============================================================================

(def langfuse-tools
  "Langfuse MCP tool definitions."
  [{:name "searchLangfuseDocs"
    :description "Semantic search (RAG) over the Langfuse documentation."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "The user's question in natural language."}}
                  :required ["query"]}}
   {:name "getLangfuseDocsPage"
    :description "Fetch the raw Markdown for a single Langfuse docs page."
    :inputSchema {:type "object"
                  :properties {"pathOrUrl" {:type "string"
                                            :description "Docs path or full URL."}}
                  :required ["pathOrUrl"]}}
   {:name "getLangfuseOverview"
    :description "Get a high-level, machine-readable index by downloading llms.txt."
    :inputSchema {:type "object"
                  :properties {}}}])

(def nrepl-tools
  "nREPL MCP tool definitions."
  [{:name "connect"
    :description "Connect to an nREPL server."
    :inputSchema {:type "object"
                  :properties {"host" {:type "string"
                                       :description "nREPL server host"}
                               "port" {:type "number"
                                       :description "nREPL server port"}}
                  :required ["host" "port"]}}
   {:name "eval_form"
    :description "Evaluate Clojure code in a specific namespace or the current one."
    :inputSchema {:type "object"
                  :properties {"code" {:type "string"
                                       :description "Clojure code to evaluate"}
                               "ns" {:type "string"
                                     :description "Optional namespace to evaluate in"}}
                  :required ["code"]}}
   {:name "get_ns_vars"
    :description "Get all public vars in a namespace with their metadata and values."
    :inputSchema {:type "object"
                  :properties {"ns" {:type "string"
                                     :description "Namespace to inspect"}}
                  :required ["ns"]}}])

(def exa-tools
  "Exa MCP tool definitions.
   Based on https://github.com/exa-labs/exa-mcp-server"
  [{:name "web_search_exa"
    :description "Search the web with Exa's neural search. Returns relevant results with content snippets."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "Search query"}
                               "numResults" {:type "integer"
                                             :description "Number of results (default 10)"}
                               "type" {:type "string"
                                       :enum ["neural" "keyword" "auto"]
                                       :description "Search type (default auto)"}}
                  :required ["query"]}}
   {:name "get_code_context_exa"
    :description "Search code repositories, documentation, and Stack Overflow for code examples and technical content."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "Code-related search query"}
                               "language" {:type "string"
                                           :description "Programming language filter (optional)"}}
                  :required ["query"]}}
   {:name "crawling_exa"
    :description "Crawl a URL and extract its main content as clean text."
    :inputSchema {:type "object"
                  :properties {"url" {:type "string"
                                      :description "URL to crawl and extract content from"}}
                  :required ["url"]}}
   {:name "company_research_exa"
    :description "Research a company - find information about funding, team, products, and recent news."
    :inputSchema {:type "object"
                  :properties {"company" {:type "string"
                                          :description "Company name to research"}}
                  :required ["company"]}}])

(def tavily-tools
  "Tavily MCP tool definitions.
   Based on https://github.com/tavily-ai/tavily-mcp"
  [{:name "tavily_search"
    :description "Search the web with Tavily. Returns comprehensive results with AI-optimized content extraction."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "Search query"}
                               "search_depth" {:type "string"
                                               :enum ["basic" "advanced"]
                                               :description "Search depth - basic is faster, advanced is more thorough"}
                               "include_answer" {:type "boolean"
                                                 :description "Include AI-generated answer summary"}
                               "max_results" {:type "integer"
                                              :description "Maximum results to return (default 5)"}}
                  :required ["query"]}}
   {:name "tavily_extract"
    :description "Extract and parse content from one or more URLs. Returns clean, structured content."
    :inputSchema {:type "object"
                  :properties {"urls" {:type "array"
                                       :items {:type "string"}
                                       :description "URLs to extract content from"}}
                  :required ["urls"]}}
   {:name "tavily_qna"
    :description "Get a direct answer to a question using Tavily's QnA search. Returns a concise, factual answer."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "Question to answer"}}
                  :required ["query"]}}])

(def playwright-tools
  "Playwright MCP tool definitions.
   Based on https://github.com/microsoft/playwright-mcp"
  [{:name "browser_navigate"
    :description "Navigate to a URL in the browser."
    :inputSchema {:type "object"
                  :properties {"url" {:type "string"
                                      :description "URL to navigate to"}}
                  :required ["url"]}}
   {:name "browser_click"
    :description "Click on an element on the page."
    :inputSchema {:type "object"
                  :properties {"element" {:type "string"
                                          :description "Human-readable element description"}
                               "ref" {:type "string"
                                      :description "Exact target element reference from snapshot"}
                               "selector" {:type "string"
                                           :description "CSS selector for the element"}}}}
   {:name "browser_type"
    :description "Type text into an editable element on the page."
    :inputSchema {:type "object"
                  :properties {"element" {:type "string"
                                          :description "Human-readable element description"}
                               "ref" {:type "string"
                                      :description "Exact target element reference from snapshot"}
                               "selector" {:type "string"
                                           :description "CSS selector for the element"}
                               "text" {:type "string"
                                       :description "Text to type into the element"}
                               "submit" {:type "boolean"
                                         :description "Press Enter after typing"}}
                  :required ["text"]}}
   {:name "browser_fill_form"
    :description "Fill multiple form fields at once."
    :inputSchema {:type "object"
                  :properties {"fields" {:type "array"
                                         :items {:type "object"
                                                 :properties {"selector" {:type "string"}
                                                              "value" {:type "string"}}}
                                         :description "Array of {selector, value} pairs"}}
                  :required ["fields"]}}
   {:name "browser_snapshot"
    :description "Capture an accessibility snapshot of the current page, returning structured text content."
    :inputSchema {:type "object"
                  :properties {"filename" {:type "string"
                                           :description "Optional filename to save snapshot"}
                               "selector" {:type "string"
                                           :description "CSS selector to scope the snapshot"}
                               "depth" {:type "integer"
                                        :description "Maximum depth of the snapshot tree"}}}}
   {:name "browser_take_screenshot"
    :description "Take a screenshot of the current page or element."
    :inputSchema {:type "object"
                  :properties {"type" {:type "string"
                                       :enum ["png" "jpeg"]
                                       :description "Image format"}
                               "filename" {:type "string"
                                           :description "Filename to save screenshot"}
                               "element" {:type "string"
                                          :description "Element description to screenshot"}
                               "selector" {:type "string"
                                           :description "CSS selector for element"}
                               "fullPage" {:type "boolean"
                                           :description "Capture full scrollable page"}}}}
   {:name "browser_wait_for"
    :description "Wait for text to appear/disappear or for a specified time."
    :inputSchema {:type "object"
                  :properties {"time" {:type "integer"
                                       :description "Time to wait in milliseconds"}
                               "text" {:type "string"
                                       :description "Text to wait for"}
                               "textGone" {:type "string"
                                           :description "Text to wait to disappear"}}}}
   {:name "browser_evaluate"
    :description "Evaluate JavaScript code on the page and return the result."
    :inputSchema {:type "object"
                  :properties {"function" {:type "string"
                                           :description "JavaScript code to evaluate"}
                               "element" {:type "string"
                                          :description "Element to evaluate on"}
                               "selector" {:type "string"
                                           :description "CSS selector for context"}}
                  :required ["function"]}}
   {:name "browser_press_key"
    :description "Press a key on the keyboard."
    :inputSchema {:type "object"
                  :properties {"key" {:type "string"
                                      :description "Key to press (e.g. Enter, Escape, ArrowDown)"}}
                  :required ["key"]}}
   {:name "browser_select_option"
    :description "Select an option in a dropdown element."
    :inputSchema {:type "object"
                  :properties {"element" {:type "string"
                                          :description "Element description"}
                               "selector" {:type "string"
                                           :description "CSS selector for dropdown"}
                               "values" {:type "array"
                                         :items {:type "string"}
                                         :description "Values to select"}}
                  :required ["values"]}}
   {:name "browser_hover"
    :description "Hover over an element on the page."
    :inputSchema {:type "object"
                  :properties {"element" {:type "string"
                                          :description "Element description"}
                               "selector" {:type "string"
                                           :description "CSS selector"}}}}
   {:name "browser_close"
    :description "Close the browser page."
    :inputSchema {:type "object"
                  :properties {}}}
   {:name "browser_tabs"
    :description "List, create, close, or select a browser tab."
    :inputSchema {:type "object"
                  :properties {"action" {:type "string"
                                         :enum ["list" "create" "close" "select"]
                                         :description "Tab action to perform"}
                               "index" {:type "integer"
                                        :description "Tab index for select/close"}
                               "url" {:type "string"
                                      :description "URL for create action"}}}}
   {:name "browser_console_messages"
    :description "Return all console messages from the page."
    :inputSchema {:type "object"
                  :properties {"level" {:type "string"
                                        :enum ["log" "info" "warn" "error"]
                                        :description "Filter by log level"}
                               "all" {:type "boolean"
                                      :description "Include all levels"}}}}
   {:name "browser_network_requests"
    :description "Return all network requests made since page load."
    :inputSchema {:type "object"
                  :properties {"filter" {:type "string"
                                         :description "Filter requests by URL pattern"}
                               "requestHeaders" {:type "boolean"
                                                 :description "Include request headers"}
                               "requestBody" {:type "boolean"
                                              :description "Include request body"}}}}])

(def known-mcp-servers
  "Known MCP server definitions."
  {:langfuse {:tools langfuse-tools}
   :nrepl {:tools nrepl-tools}
   :exa {:tools exa-tools}
   :tavily {:tools tavily-tools}
   :playwright {:tools playwright-tools}})

;; ============================================================================
;; Public API
;; ============================================================================

(defn connect-static
  "Create an in-process deterministic tool connection. This is deliberately not
   a portable MCP transport and is never selected by `connect`."
  [opts]
  (connect-static* opts))

(defn connect
  "Connect to an MCP server.

   The portable public transport discriminators are exactly `:stdio` and
   `:streamable-http`. Unsupported values throw a typed exception before a
   connection is created."
  ([opts] (connect opts (create-trace-context)))
  ([{:keys [type server-id] :as opts} trace-context]
   (emit! trace-context (or server-id (:url opts) (:command opts) "unknown") type
          :connection-requested :requested)
   (case type
     :stdio (connect-stdio opts trace-context)
     :streamable-http (connect-http opts trace-context)
     (let [exception (failure :mcp/unsupported-transport (or server-id "unknown") type
                              :connect (str "Unsupported MCP transport: " type))]
       (emit! trace-context (or server-id "unknown") type :initialization-failed :failed
              {:failure (ex-data exception)})
       (throw exception)))))

(defn connect-legacy
  "Compatibility helper for deterministic builder fixtures and legacy bridges.
   Portable consumers must use `connect`."
  [{:keys [type preset] :as opts}]
  (let [merged-opts (merge (when preset (get known-mcp-servers preset)) opts)]
    (case type
      :static (connect-static merged-opts)
      :claude-mcp (connect-claude-mcp merged-opts)
      (if (and preset (nil? type))
        (connect-static merged-opts)
        (connect merged-opts)))))

(defn list-tools
  "List available tools from an MCP connection."
  [mcp-conn]
  (list-tools* mcp-conn))

(defn call-tool
  "Call a tool on an MCP connection."
  [mcp-conn tool-name args]
  (call-tool* mcp-conn tool-name args))

(defn close
  "Close an MCP connection."
  [mcp-conn]
  (close* mcp-conn))

;; ============================================================================
;; MCP Registry (Multi-Server)
;; ============================================================================

(defn- validate-server-name
  "Validate that a server name is a valid identifier."
  [server-name]
  (when-not (re-matches #"[a-zA-Z][a-zA-Z0-9_-]*" server-name)
    (throw (ex-info (str "Invalid server name: '" server-name
                         "'. Must start with a letter and contain only alphanumeric, _, -")
                    {:server-name server-name}))))

(defn create-registry
  "Create a registry of named MCP connections with auto-discovered tools.

   server-map is {\"server-name\" mcp-connection, ...}

   Auto-discovers tools from each connection. Validates server names
   are valid identifiers. Detects duplicate tool names across servers.

   Returns:
   {:connections {\"linear\" conn, \"github\" conn, ...}
    :catalog {\"linear/list_issues\" {:server \"linear\" :name \"list_issues\" :description ... :schema ...}
              \"github/list_pulls\"  {:server \"github\" :name \"list_pulls\"  :description ... :schema ...}}}"
  [server-map]
  (doseq [server-name (keys server-map)]
    (validate-server-name server-name))
  (let [catalog (reduce-kv
                  (fn [acc server-name conn]
                    (let [tools (list-tools conn)]
                      (reduce
                        (fn [cat tool]
                          (let [prefixed (str server-name "/" (:name tool))]
                            (assoc cat prefixed
                                   {:server server-name
                                    :name (:name tool)
                                    :prefixed-name prefixed
                                    :description (:description tool)
                                    :schema (:inputSchema tool)})))
                        acc
                        tools)))
                  {}
                  server-map)]
    {:connections server-map
     :catalog catalog}))

(defn registry->call-tool-fn
  "Create a multiplexing call-tool-fn from a registry.

   For 'server/tool' names: parses prefix, routes to correct connection,
   strips prefix before calling the MCP server.

   For unprefixed names: searches all connections. Errors if ambiguous."
  [registry]
  (let [catalog (:catalog registry)
        connections (:connections registry)]
    (fn [tool-name args]
      (u/trace ::registry-call-tool {:tool tool-name}
        (if-let [slash-idx (str/index-of tool-name "/")]
          ;; Namespaced: route to specific server
          (let [server (subs tool-name 0 slash-idx)
                bare-name (subs tool-name (inc slash-idx))
                conn (get connections server)]
            (if conn
              (call-tool conn bare-name args)
              (throw (ex-info (str "Unknown MCP server: " server)
                              {:server server :tool tool-name}))))
          ;; Unprefixed: find across all servers
          (let [matches (->> (vals catalog)
                             (filter #(= (:name %) tool-name)))]
            (case (count matches)
              0 (throw (ex-info (str "Unknown tool: " tool-name)
                                {:tool tool-name}))
              1 (call-tool (get connections (:server (first matches)))
                           tool-name args)
              (throw (ex-info (str "Ambiguous tool '" tool-name "' found on servers: "
                                   (str/join ", " (map :server matches))
                                   ". Use server/tool format.")
                              {:tool tool-name
                               :servers (mapv :server matches)})))))))))

(defn list-all-tools
  "List all tools in the registry with their prefixed names."
  [registry]
  (vals (:catalog registry)))

(defn close-all
  "Close all connections in a registry."
  [registry]
  (doseq [[_ conn] (:connections registry)]
    (close conn)))

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
