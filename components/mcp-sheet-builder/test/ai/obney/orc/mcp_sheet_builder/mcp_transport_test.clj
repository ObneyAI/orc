(ns ai.obney.orc.mcp-sheet-builder.mcp-transport-test
  (:require [ai.obney.orc.mcp-sheet-builder.interface :as mcp]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors)))

(defn- stdio-config [& args]
  {:type :stdio
   :server-id "stdio-fixture"
   :command "clojure"
   :args (into ["-M:dev:test" "-m"
                "ai.obney.orc.mcp-sheet-builder.fixtures.stdio-server"] args)
   :working-directory (System/getProperty "user.dir")
   :env {"ORC_MCP_TEST_SECRET" "never-trace-this"}})

(defn- response! [^HttpExchange exchange status headers body]
  (doseq [[name value] headers] (.add (.getResponseHeaders exchange) name value))
  (let [bytes (.getBytes (or body "") StandardCharsets/UTF_8)]
    (.sendResponseHeaders exchange status (long (count bytes)))
    (with-open [out (.getResponseBody exchange)] (.write out bytes))))

(defn- start-server [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/mcp" (reify HttpHandler (handle [_ exchange] (handler exchange))))
    (.setExecutor server (Executors/newCachedThreadPool))
    (.start server)
    server))

(defn- url [^HttpServer server]
  (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/mcp"))

(defn- rpc-handler [requests]
  (fn [^HttpExchange exchange]
    (let [method (.getRequestMethod exchange)
          body (slurp (.getRequestBody exchange))
          request (when-not (empty? body) (json/parse-string body true))]
      (swap! requests conj {:http-method method
                            :rpc-method (:method request)
                            :headers (into {} (.entrySet (.getRequestHeaders exchange)))})
      (cond
        (= method "DELETE") (response! exchange 204 {} "")
        (= "notifications/initialized" (:method request)) (response! exchange 202 {} "")
        (= "initialize" (:method request))
        (response! exchange 200 {"Content-Type" "application/json"
                                 "Mcp-Session-Id" "session-1"}
                   (json/generate-string {:jsonrpc "2.0" :id (:id request)
                                          :result {:protocolVersion "2025-03-26"
                                                   :capabilities {:tools {}}
                                                   :serverInfo {:name "http-test" :version "1"}}}))
        (= "tools/list" (:method request))
        (response! exchange 200 {"Content-Type" "text/event-stream"
                                 "Mcp-Session-Id" "session-2"}
                   (str "event: message\ndata: "
                        (json/generate-string {:jsonrpc "2.0" :id (:id request)
                                               :result {:tools [{:name "echo" :inputSchema {:type "object"}}]}})
                        "\n\n"))
        (= "tools/call" (:method request))
        (response! exchange 200 {"Content-Type" "application/json"}
                   (json/generate-string {:jsonrpc "2.0" :id (:id request)
                                          :result {:content [{:type "text" :text "ok"}]}}))
        :else (response! exchange 400 {} "bad request")))))

(deftest real-stdio-lifecycle-and-literal-arguments
  (let [trace (mcp/create-trace-context)
        literal "$(touch /tmp/orc-mcp-must-not-exist); * | &"
        conn (mcp/connect (stdio-config literal) trace)]
    (try
      (is (= #{"literal_args" "echo"} (set (map :name (mcp/list-tools conn)))))
      (let [result (mcp/call-tool conn "literal_args" {})
            returned (json/parse-string (get-in result [:content 0 :text]))]
        (is (= [literal] returned)))
      (let [calls (doall (map deref
                              (repeatedly 8 #(future (mcp/call-tool conn "echo" {:value (random-uuid)})))))]
        (is (= 8 (count calls))))
      (finally (mcp/close conn)))
    (is (some #(= :process-exited (:phase %))
              (do (Thread/sleep 100) (mcp/trace-activities trace))))
    (is (every? (set (map :phase (mcp/trace-activities trace)))
                #{:connection-requested :transport-contacted
                  :initialization-requested :initialization-completed
                  :tools-listed :tool-invocation-requested
                  :tool-invocation-completed :connection-closed :process-exited}))
    (is (not (.exists (io/file "/tmp/orc-mcp-must-not-exist"))))
    (is (not (re-find #"never-trace-this" (pr-str (mcp/trace-activities trace)))))))

(deftest streamable-http-lifecycle-headers-session-and-sse
  (let [requests (atom [])
        server (start-server (rpc-handler requests))
        persisted (atom [])
        trace (mcp/create-trace-context #(swap! persisted conj %))]
    (try
      (let [conn (mcp/connect {:type :streamable-http :server-id "http-fixture"
                               :url (url server) :headers {"X-Plugin-Key" "secret-value"}}
                              trace)]
        (try
          (is (= ["echo"] (mapv :name (mcp/list-tools conn))))
          (is (= "ok" (get-in (mcp/call-tool conn "echo" {}) [:content 0 :text])))
          (finally (mcp/close conn))))
      (is (= ["initialize" "notifications/initialized" "tools/list" "tools/call" nil]
             (mapv :rpc-method @requests)))
      (is (every? #(contains? (:headers %) "X-plugin-key") @requests))
      (is (some #(= :tools-listed (:phase %)) (mcp/trace-activities trace)))
      (is (= @persisted (mcp/trace-activities trace)))
      (is (not (re-find #"secret-value" (pr-str (mcp/trace-activities trace)))))
      (finally (.stop server 0)))))

(deftest exact-dispatch-and-initialization-fence
  (is (= #{:stdio :streamable-http} (mcp/supported-transports)))
  (is (= (mcp/supported-transports)
         (set (map #(get-in % [1 1 1]) (rest (mcp/connection-schema))))))
  (testing "unknown and legacy transport names fail with typed data"
    (doseq [transport [:unknown :http :static]]
      (try
        (mcp/connect {:type transport :server-id "bad"})
        (is false "unsupported transport unexpectedly connected")
        (catch clojure.lang.ExceptionInfo e
          (is (= :mcp/unsupported-transport (:orc/error (ex-data e))))))))
  (testing "an unsuccessful initialize never returns a connection"
    (let [server (start-server (fn [exchange] (response! exchange 500 {} "no")))]
      (try
        (is (thrown? clojure.lang.ExceptionInfo
                     (mcp/connect {:type :streamable-http :server-id "failed"
                                   :url (url server)})))
        (finally (.stop server 0))))))

(deftest sibling-failure-isolation
  (let [requests (atom []) server (start-server (rpc-handler requests))]
    (try
      (let [healthy (mcp/connect {:type :streamable-http :server-id "healthy" :url (url server)})]
        (try
          (is (thrown? clojure.lang.ExceptionInfo
                       (mcp/connect {:type :unknown :server-id "failed"})))
          (is (= ["echo"] (mapv :name (mcp/list-tools healthy))))
          (finally (mcp/close healthy))))
      (finally (.stop server 0)))))

(deftest cross-origin-redirect-header-policy
  (let [received (atom [])
        destination (start-server (rpc-handler received))
        redirect (start-server
                   (fn [exchange]
                     (response! exchange 307 {"Location" (url destination)} "")))]
    (try
      (let [conn (mcp/connect {:type :streamable-http :server-id "redirected"
                               :url (url redirect)
                               :headers {"Authorization" "Bearer secret"
                                         "X-Plugin-Key" "plugin-secret"}})]
        (mcp/close conn))
      (is (every? #(not (contains? (:headers %) "Authorization")) @received))
      (is (every? #(not (contains? (:headers %) "X-plugin-key")) @received))
      (is (every? #(not (contains? (:headers %) "Mcp-session-id")) @received))
      (reset! received [])
      (let [conn (mcp/connect {:type :streamable-http :server-id "authorized-redirect"
                               :url (url redirect)
                               :headers {"Authorization" "Bearer secret"
                                         "X-Plugin-Key" "plugin-secret"}
                               :authorized-redirect-origins #{(url destination)}})]
        (mcp/close conn))
      (is (every? #(contains? (:headers %) "Authorization") @received))
      (is (every? #(contains? (:headers %) "X-plugin-key") @received))
      (finally
        (.stop redirect 0)
        (.stop destination 0)))))
