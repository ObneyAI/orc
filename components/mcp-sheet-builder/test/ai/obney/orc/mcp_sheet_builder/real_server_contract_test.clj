(ns ai.obney.orc.mcp-sheet-builder.real-server-contract-test
  "Two defects found by driving the official MCP filesystem server from a
   behaviour tree:
   - an executor generated from a DISCOVERED tool (whose input schema the
     client returns with keyword keys) bound no arguments, so every call sent
     {} (McpToolTransport SchemaFaithfulness);
   - a tool result the server marks `isError` came back as a successful value
     (McpToolTransport ToolErrorsFailTheCall)."
  (:require [ai.obney.orc.mcp-sheet-builder.core.executor-generator :as gen]
            [ai.obney.orc.mcp-sheet-builder.core.executor-runtime :as runtime]
            [ai.obney.orc.mcp-sheet-builder.core.mcp-client :as client]
            [ai.obney.orc.mcp-sheet-builder.interface :as mcp]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors)))

;; The shape `mcp/list-tools` returns for a real server: keyword keys all the way down.
(def ^:private discovered-tool
  {:name "list_directory"
   :inputSchema {:$schema "http://json-schema.org/draft-07/schema#"
                 :type "object"
                 :properties {:path {:type "string"}}
                 :required ["path"]}})

(deftest executor-generated-from-a-discovered-tool-sends-its-arguments
  (let [exec-def (gen/build-executor-definition discovered-tool)
        _ (runtime/load-executor! (:tool-id exec-def) (:tool-name exec-def)
                                  (:source-code exec-def) (:namespace-requires exec-def))
        f (runtime/get-executor (:fn-reference exec-def))
        sent (atom nil)]
    (with-redefs [client/call-tool (fn [_conn tool args] (reset! sent [tool args]) {:content []})]
      (f {:inputs {:path "/data/reports"} :mcp-session :live}))
    (is (= ["list_directory" {:path "/data/reports"}] @sent)
        (str "generated source: " (:source-code exec-def)))))

(defn- response! [^HttpExchange exchange status headers body]
  (doseq [[name value] headers] (.add (.getResponseHeaders exchange) name value))
  (let [bytes (.getBytes (or body "") StandardCharsets/UTF_8)]
    (.sendResponseHeaders exchange status (long (count bytes)))
    (with-open [out (.getResponseBody exchange)] (.write out bytes))))

(defn- erroring-server []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/mcp"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [method (.getRequestMethod exchange)
                              body (slurp (.getRequestBody exchange))
                              request (when-not (empty? body) (json/parse-string body true))
                              reply #(response! exchange 200 {"Content-Type" "application/json"
                                                              "Mcp-Session-Id" "s-1"}
                                                (json/generate-string {:jsonrpc "2.0" :id (:id request)
                                                                       :result %}))]
                          (cond
                            (= method "DELETE") (response! exchange 204 {} "")
                            (= "notifications/initialized" (:method request)) (response! exchange 202 {} "")
                            (= "initialize" (:method request))
                            (reply {:protocolVersion "2025-03-26" :capabilities {:tools {}}
                                    :serverInfo {:name "erroring" :version "1"}})
                            (= "tools/list" (:method request))
                            (reply {:tools [{:name "read_text_file" :inputSchema {:type "object"}}]})
                            (= "tools/call" (:method request))
                            (reply {:isError true
                                    :content [{:type "text" :text "ENOENT: no such file, open '/data/q4.txt'"}]})
                            :else (response! exchange 400 {} "bad request"))))))
    (.setExecutor server (Executors/newCachedThreadPool))
    (.start server)
    server))

(deftest a-tool-result-marked-as-an-error-fails-the-call
  (let [server (erroring-server)
        conn (mcp/connect {:type :streamable-http :server-id "erroring"
                           :url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/mcp")})]
    (try
      (let [outcome (try (mcp/call-tool conn "read_text_file" {:path "/data/q4.txt"})
                         (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo outcome)
            (str "an isError result must not be returned as a value: " (pr-str outcome)))
        (when (instance? clojure.lang.ExceptionInfo outcome)
          (is (str/includes? (str (:orc.tool/message (ex-data outcome))) "no such file")
              "the server's error text is the message the tool shares with its caller")
          (is (str/includes? (ex-message outcome) "read_text_file"))))
      (finally (mcp/close conn) (.stop server 0)))))
