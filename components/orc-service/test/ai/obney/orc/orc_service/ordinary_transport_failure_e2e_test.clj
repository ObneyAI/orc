(ns ai.obney.orc.orc-service.ordinary-transport-failure-e2e-test
  "DET-E2E-322: promote the existing real HTTP502 witness; no paid calls."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [ai.obney.orc.orc-service.interface.schemas :as schemas]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [litellm.router :as router])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors ExecutorService TimeUnit]))

(def fixture-token "sk-or-PROTOTYPE-NOT-A-CREDENTIAL-502-witness")
(def provider-name :ordinary-local-http-failure)
(def last-proof (atom nil))
(defn contains-fixture? [x] (str/includes? (pr-str x) fixture-token))
(defn readable-snapshot [x]
  (walk/postwalk #(if (string? %) (str/replace % fixture-token "[FIXTURE-BEARER]") %) x))

(defn reply! [^HttpExchange exchange status content-type body]
  (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "Content-Type" content-type)
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)] (.write out bytes))))

(defn start-server! []
  (let [requests (atom []) errors (atom [])
        executor (Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server executor)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  model (:model body)
                  fixture-auth? (= (str "Bearer " fixture-token)
                                   (.getFirst (.getRequestHeaders exchange) "Authorization"))]
              (swap! requests conj {:model model :body body :fixture-auth? fixture-auth?})
              (case model
                "fixture/success"
                (reply! exchange 200 "application/json"
                  (json/generate-string
                    {:id "fixture-success-response" :model model
                     :choices [{:index 0 :finish_reason "stop"
                                :message {:role "assistant"
                                          :content (json/generate-string
                                                     {:answer-reasoning "The fixture identifies the requested item."
                                                      :item-id "ITEM-502" :answer false})}}]
                     :usage {:prompt_tokens 12 :completion_tokens 12 :total_tokens 24}}))
                "fixture/html-502"
                (reply! exchange 502 "text/html" "<html><body>fixture gateway unavailable</body></html>")
                (reply! exchange 400 "application/json"
                        "{\"error\":{\"message\":\"unknown fixture model\"}}")))
            (catch Throwable e
              (swap! errors conj {:class (.getName (class e))})
              (try (reply! exchange 500 "text/plain" "fixture handler failure")
                   (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :executor executor :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn stop-server! [{:keys [^HttpServer server ^ExecutorService executor]}]
  (.stop server 0)
  (.shutdown executor)
  (when-not (.awaitTermination executor 10 TimeUnit/SECONDS)
    (.shutdownNow executor)
    (when-not (.awaitTermination executor 10 TimeUnit/SECONDS)
      (throw (ex-info "Owned fixture HTTP executor did not stop" {})))))

(defn client-witness []
  (try
    (router/completion provider-name
                       {:model "fixture/html-502"
                        :messages [{:role :user :content "Fixture transport witness."}]
                        :max-tokens 65536})
    {:unexpected-success? true}
    (catch Throwable e
      {:exception-class (.getName (class e))
       :classification (select-keys (ex-data e) [:type :http-status])
       :message-contains-fixture? (contains-fixture? (ex-message e))
       :data-contains-fixture? (contains-fixture? (ex-data e))
       :cause-contains-fixture? (contains-fixture? (ex-cause e))
       :message (readable-snapshot (ex-message e))})))

(defn run-node [ctx model]
  (let [workflow (orc/build-workflow! ctx
                   (orc/workflow (str "ordinary-http-failure/" model)
                     (orc/blackboard
                       {:request [:string {:description "Exact requested fixture item identifier."}]
                        :answer-reasoning [:string {:description "Concise basis for this fixture answer."}]
                        :item-id [:string {:description "Exact item identifier copied from the request."}]
                        :answer [:boolean {:description "Whether the fixture item satisfies the requested check."}]})
                     (orc/llm "answer" :model model
                       :instruction "Return the requested item and its boolean check result."
                       :reads [:request] :writes [:answer-reasoning :item-id :answer]
                       :retry {:max-attempts 1 :backoff-ms []}
                       :options {:output-mode :json-schema :max-retries 0
                                 :timeout-ms 10000 :max-tokens 65536})))
        tick-id (random-uuid)
        result (orc/execute ctx workflow {:request "ITEM-502"}
                            :tick-id tick-id :timeout-ms 120000 :max-ticks 1 :llm-call-budget 1)
        events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)
        completions (filterv #(= :sheet/node-execution-completed (:event/type %)) events)
        tick (orc/get-tick ctx tick-id)
        completion (first completions)
        _ (h/settle-until! #(some? (get-in (h/run-query ctx (h/make-get-trace-query (:trace-id result)))
                                                        [:query/result :trace])))
        trace (get-in (h/run-query ctx (h/make-get-trace-query (:trace-id result))) [:query/result :trace])
        leaf (first (filter #(= :leaf (:node-type %)) (:node-traces trace)))
        detail (when leaf (get-in (h/run-query ctx {:query/name :sheet/node-trace-detail
                                                  :trace-id (:trace-id result)
                                                  :trace-instance-id (:trace-instance-id leaf)}) [:query/result]))
        writes (when completion
                 (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick-id completion))]
    {:result result :tick tick :completions completions :writes writes :trace trace :detail detail
     :event-types (frequencies (map :event/type events))
     :fixture-exposure {:result (contains-fixture? result)
                        :completions (contains-fixture? completions)
                        :tick-projection (contains-fixture? tick)
                        :all-tick-events (contains-fixture? events)
                        :trace (contains-fixture? trace)
                        :detail (contains-fixture? detail)}}))

(defn contract-checks [client control failed requests errors]
  (let [completion (first (:completions failed))
        transport (get-in completion [:provider-evidence :transport])]
    [{:name :http-fixture-received-three-real-requests
      :kind :harness :pass? (= 3 (count requests))}
     {:name :fixture-auth-and-handler-valid :kind :harness
      :pass? (and (empty? errors) (every? :fixture-auth? requests))}
     {:name :real-client-classifies-non-json-502 :kind :harness
      :pass? (= {:type :litellm/server-error :http-status 502} (:classification client))}
     {:name :strict-success-control-has-correct-projected-writes :kind :harness
      :pass? (and (= :success (get-in control [:result :status]))
                  (= :success (get-in control [:tick :root-status]))
                  (= "ITEM-502" (get-in control [:writes :item-id]))
                  (false? (get-in control [:writes :answer])))}
     {:name :failure-is-recorded-on-the-real-node :kind :harness
      :pass? (and (= 1 (count (:completions failed)))
                  (= :failure (:status completion))
                  (= :transport-failure (:failure-kind completion)))}
     {:name :typed-provider-category-and-status-survive-completion :kind :desired-contract
      :pass? (= {:type :litellm/server-error :http-status 502} transport)}
     {:name :no-fixture-bearer-in-result-or-durable-event :kind :desired-contract
      :pass? (not-any? true? (vals (:fixture-exposure failed)))}]))


(deftest det-e2e-322-ordinary-http-failure-is-safe-and-typed-through-storage
  (let [server (start-server!)]
    (try
      (llm/register-provider! provider-name
        {:provider :openrouter :model "fixture/success"
         :config {:api-base (:url server) :api-key fixture-token :timeout 10000}})
      (let [client (client-witness)
            {:keys [control failed]}
            (h/with-async-test-context
              [ctx {:context {:llm-provider provider-name :tenant-id (random-uuid)}}]
              {:control (run-node ctx "fixture/success")
               :failed (run-node ctx "fixture/html-502")})
            checks (contract-checks client control failed @(:requests server) @(:errors server))]
        (reset! last-proof
                (readable-snapshot
                 {:checks checks :client client :control control :failed failed
                  :requests @(:requests server) :server-errors @(:errors server)
                  :paid-provider-calls 0 :event-store :in-memory
                  :llm-source (str (io/resource "ai/obney/orc/llm/core.clj"))
                  :client-source (str (io/resource "litellm/providers/openrouter.clj"))
                  :sio-source (str (io/resource "sio/core.clj"))}))
        (doseq [{:keys [name pass?]} checks] (is pass? (str name)))
        (is (true? (:message-contains-fixture? client)) "The unsafe direct-client control detects the marker")
        (is (= :transport-failure (get-in failed [:detail :failure-kind])))
        (is (= {:type :litellm/server-error :http-status 502}
               (get-in failed [:detail :provider-evidence :transport])))
        (is (= "LLM provider call failed [server-error] (HTTP 502)"
               (get-in failed [:completions 0 :error])))
        (is (m/validate schemas/provider-failure-evidence
                        (get-in failed [:completions 0 :provider-evidence]))))
      (finally
        (router/unregister! provider-name)
        (stop-server! server)))))

(deftest transport-evidence-is-a-closed-nonempty-optional-contract
  (is (m/validate schemas/provider-failure-evidence {:provider "legacy-provider"}))
  (doseq [transport [{:type :litellm/rate-limit} {:http-status 429}
                     {:type :litellm/quota-exceeded :http-status 429}]]
    (is (m/validate schemas/provider-failure-evidence {:provider "provider" :transport transport})))
  (doseq [transport [{} {:type :unknown/provider} {:http-status "502"}
                     {:http-status 99} {:http-status 600}
                     {:type :litellm/server-error :http-status 502 :message "not allowed"}]]
    (is (false? (m/validate schemas/provider-failure-evidence {:provider "provider" :transport transport})))))
