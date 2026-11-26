(ns ai.obney.orc.orc-service.budget-reservation-fixture
  "Loopback transport barrier for native reservation command contention."
  (:require [cheshire.core :as json])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors ExecutorService TimeUnit]))

(defn start-held-provider! []
  (let [entered (promise) release (promise) requests (atom []) errors (atom [])
        pool (Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  _ (swap! requests conj body)
                  _ (deliver entered true)
                  _ (when-not (deref release 20000 false)
                      (throw (ex-info "Unreleased fixture transport barrier" {})))
                  response (json/generate-string
                             {:id (str (random-uuid)) :model (:model body)
                              :choices [{:finish_reason "stop"
                                         :message {:role "assistant"
                                                   :content (json/generate-string
                                                              {:reasoning "Exercise admission mechanically."
                                                               :code "(final! {:summary \"done\"})"})}}]
                              :usage {:prompt_tokens 1 :completion_tokens 1}})
                  bytes (.getBytes response "UTF-8")]
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable t
              (swap! errors conj (.getName (class t)))
              (try (.sendResponseHeaders exchange 500 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :entered entered :release release
     :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn stop! [{:keys [server pool release]}]
  (deliver release true)
  (.stop ^HttpServer server 0)
  (.shutdownNow ^ExecutorService pool)
  (when-not (.awaitTermination ^ExecutorService pool 10 TimeUnit/SECONDS)
    (throw (ex-info "Fixture HTTP workers did not join" {}))))

(defn start-capacity-provider! [on-entry]
  ;; Exactly the five original store! operations; no final!, sixth reply or
  ;; alternate workflow is supplied by this fixture.
  (let [codes ["(store! :one 1)" "(store! :two 2)" "(store! :three 3)"
               "(store! :four 4)" "(store! :five 5)"]
        requests (atom []) responses (atom []) errors (atom [])
        ordinal (atom 0) release (promise)
        pool (Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (deliver release true)
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  n (swap! ordinal inc)
                  _ (swap! requests conj {:ordinal n :request body})
                  _ (on-entry n)
                  code (or (get codes (dec n))
                           (throw (ex-info "Unexpected sixth HTTP request" {})))
                  response {:id (str (random-uuid)) :model (:model body)
                            :choices [{:finish_reason "stop"
                                       :message {:role "assistant"
                                                 :content (json/generate-string
                                                            {:reasoning "Preserve the five-store workflow."
                                                             :code code})}}]
                            :usage {:prompt_tokens 1 :completion_tokens 1}}
                  bytes (.getBytes (json/generate-string response) "UTF-8")]
              (swap! responses conj {:ordinal n :code code :response response})
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable t
              (swap! errors conj (.getName (class t)))
              (try (.sendResponseHeaders exchange 500 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :release release :requests requests
     :responses responses :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))
