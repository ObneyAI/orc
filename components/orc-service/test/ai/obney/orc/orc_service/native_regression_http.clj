(ns ai.obney.orc.orc-service.native-regression-http
  "Scripted loopback transport for native public-interface regression tests."
  (:require [cheshire.core :as json]
            [ai.obney.orc.llm.interface :as llm])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors]))

(def model "controlled-native-regression-model")
(def options {:max-tokens 4096 :max-retries 0 :output-mode :json-schema})

(defn with-provider
  "script [request ordinal] returns native response-content. f [provider calls]
   runs against a unique registered provider. Calls retain exact request and
   response-content; no real credential, external endpoint or model is used."
  [script f]
  (let [calls (atom []) provider (keyword (str "native-regression-" (random-uuid)))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        pool (Executors/newFixedThreadPool 2)
        handler (reify HttpHandler
                  (handle [_ exchange]
                    (try
                      (let [request (json/parse-string (slurp (.getRequestBody exchange)) true)
                            value (script request (inc (count @calls)))
                            response {:id (str (random-uuid)) :object "chat.completion" :model model
                                      :choices [{:index 0 :message {:role "assistant" :content (json/generate-string value)}
                                                 :finish_reason "stop"}]
                                      :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}
                            bytes (.getBytes (json/generate-string response) "UTF-8")]
                        (swap! calls conj {:request request :response-content value})
                        (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
                        (.sendResponseHeaders exchange 200 (alength bytes))
                        (with-open [out (.getResponseBody exchange)] (.write out bytes)))
                      (finally (.close exchange)))))]
    (.setExecutor server pool)
    (.createContext server "/v1/chat/completions" handler)
    (.start server)
    (try
      (llm/register-provider! provider {:provider :openrouter :model model
                                       :config {:api-key "local-script-only" :timeout 30000
                                                :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")}})
      (f provider calls)
      (finally (.stop server 0) (.shutdownNow pool)))))
