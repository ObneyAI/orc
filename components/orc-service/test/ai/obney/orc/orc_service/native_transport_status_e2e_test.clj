(ns ai.obney.orc.orc-service.native-transport-status-e2e-test
  "DET-E2E-324: three real loopback requests; native decision status only.
   Desired status preservation may fail on the pre-repair source. No paid calls."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [hato.client :as http]
            [litellm.router :as router]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.ordinary-transport-failure-e2e-test :as fixture])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors]))

(def provider :native-local-http-status)
(def model "fixture/native-status")
(def last-proof (atom nil))
(def route-schema [:enum {:descriptions {"lookup" "Read the existing fixture fact."
                                         "clarify" "Ask for the missing fixture fact."}}
                   "lookup" "clarify"])
(def request-spec
  {:inputs [{:name :request :spec :string :description "Exact fixture item to assess."}]
   :outputs [{:name :route :spec route-schema :description "Which operation addresses this item."}]
   :instructions "Choose the operation for this fixture item."})

(defn start-server! []
  (let [requests (atom []) errors (atom [])
        executor (Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server executor)
    (.createContext server "/decisions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)]
              (swap! requests conj
                     {:path (str (.getRequestURI exchange)) :body body
                      :fixture-auth? (= (str "Bearer " fixture/fixture-token)
                                        (.getFirst (.getRequestHeaders exchange) "Authorization"))})
              ;; A response marker makes the unsafe direct-transport detector falsifiable.
              (fixture/reply! exchange 429 "application/json"
                              (json/generate-string {:error {:message fixture/fixture-token}})))
            (catch Throwable e
              (swap! errors conj {:class (.getName (class e))})
              (try (fixture/reply! exchange 500 "text/plain" "fixture handler failed")
                   (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :executor executor :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/decisions")}))

(defn direct-hato-control [url]
  (try
    (http/post url {:headers {"Authorization" (str "Bearer " fixture/fixture-token)
                             "Content-Type" "application/json"}
                    :content-type :json
                    :form-params {:model model :state {:request "NATIVE-CONTROL"}
                                  :questions {:route {:type "choice" :options ["lookup" "clarify"]}}}
                    :as :json-string-keys :timeout 10000})
    {:unexpected-success? true}
    (catch Throwable e
      ;; Never retain the raw error data, message, body or cause.
      {:exception-class (.getName (class e))
       :status (:status (ex-data e))
       :type (:type (ex-data e))
       :data-contains-fixture? (fixture/contains-fixture? (ex-data e))})))

(defn run-native-node [ctx]
  (let [sheet (orc/build-workflow! ctx
                (orc/workflow "native-status-witness"
                  (orc/blackboard {:request [:string {:description "Exact fixture item."}]
                                   :route route-schema})
                  (orc/llm-decision "native-choice" :model model
                    :instruction "Choose the operation for this fixture item."
                    :reads [:request] :writes [:route]
                    :retry {:max-attempts 1 :backoff-ms []}
                    :options {:max-retries 0 :timeout-ms 10000})))
        tick-id (random-uuid)
        result (orc/execute ctx sheet {:request "NATIVE-ORC"}
                            :tick-id tick-id :timeout-ms 120000 :max-ticks 1 :llm-call-budget 1)
        events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)
        completions (filterv #(= :sheet/node-execution-completed (:event/type %)) events)
        _ (h/settle-until! #(some? (get-in (h/run-query ctx (h/make-get-trace-query tick-id))
                                         [:query/result :trace])))
        trace (get-in (h/run-query ctx (h/make-get-trace-query tick-id)) [:query/result :trace])
        leaf (first (:node-traces trace))
        detail (when leaf
                 (get-in (h/run-query ctx {:query/name :sheet/node-trace-detail
                                          :trace-id tick-id :trace-instance-id (:trace-instance-id leaf)})
                         [:query/result]))]
    {:result result :completions completions :trace trace :detail detail
     :events events
     :fixture-exposure {:result (fixture/contains-fixture? result)
                        :completions (fixture/contains-fixture? completions)
                        :events (fixture/contains-fixture? events)
                        :trace (fixture/contains-fixture? trace)
                        :detail (fixture/contains-fixture? detail)}}))

(defn run-native-wrapper []
  (let [ch (llm/predict-stream-v2 provider request-spec {:request "NATIVE-WRAPPER"}
                                 {:timeout-ms 10000})
        deadline (async/timeout 15000)]
    (loop [events []]
      (let [[event port] (async/alts!! [ch deadline])]
        (cond
          (= port deadline) {:events events :closed? false :timed-out? true}
          (nil? event) {:events events :closed? true :timed-out? false}
          :else (recur (conj events event)))))))

(deftest det-e2e-324-native-http-status-survives-blocking-and-wrapper-boundaries
  (let [server (start-server!)]
    (try
      (llm/register-provider! provider {:provider :openrouter :protocol :decision :model model
                                       :config {:api-key fixture/fixture-token :decisions-url (:url server)}})
      (let [control (direct-hato-control (:url server))
            failed (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider provider}}]
                     (run-native-node ctx))
            wrapper (run-native-wrapper)
            completion (first (:completions failed))
            terminal (first (:events wrapper))
            requests @(:requests server)
            checks
            [{:name :exact-three-real-http-requests :kind :harness :pass? (= 3 (count requests))}
             {:name :fixture-handler-and-auth :kind :harness
              :pass? (and (empty? @(:errors server)) (every? :fixture-auth? requests))}
             {:name :each-intended-boundary-called-once :kind :harness
              :pass? (= ["NATIVE-CONTROL" "NATIVE-ORC" "NATIVE-WRAPPER"]
                         (mapv #(get-in % [:body :state :request]) requests))}
             {:name :hato-has-status-without-category :kind :harness
              :pass? (and (= 429 (:status control)) (nil? (:type control)))}
             {:name :unsafe-response-detector-works :kind :harness
              :pass? (true? (:data-contains-fixture? control))}
             {:name :native-node-is-actual-transport-failure :kind :harness
              :pass? (and (= :failure (get-in failed [:result :status]))
                          (= 1 (count (:completions failed)))
                          (= :failure (:status completion))
                          (= :transport-failure (:failure-kind completion)))}
             {:name :native-wrapper-is-one-error-and-closed :kind :harness
              :pass? (and (:closed? wrapper) (not (:timed-out? wrapper))
                          (= [:error] (mapv :orc/event (:events wrapper)))
                          (= :transport-failure (get-in terminal [:error :failure-kind])))}
             {:name :public-failures-are-safe :kind :harness
              :pass? (and (not-any? true? (vals (:fixture-exposure failed)))
                          (not (fixture/contains-fixture? wrapper)))}
             {:name :native-completion-retains-status-only :kind :desired-contract
              :pass? (= {:http-status 429} (get-in completion [:provider-evidence :transport]))}
             {:name :native-trace-detail-retains-status-only :kind :desired-contract
              :pass? (= {:http-status 429} (get-in failed [:detail :provider-evidence :transport]))}
             {:name :native-wrapper-retains-status-only :kind :desired-contract
              :pass? (= {:http-status 429} (get-in terminal [:error :provider-evidence :transport]))}]]
        (reset! last-proof
                (fixture/readable-snapshot
                 {:control control :failed failed :wrapper wrapper :checks checks
                  :requests requests :server-errors @(:errors server)
                  :event-store :in-memory :paid-provider-calls 0
                  :llm-source (str (io/resource "ai/obney/orc/llm/core.clj"))
                  :hato-source (str (io/resource "hato/client.clj"))}))
        (doseq [{:keys [name pass?]} checks] (is pass? (str name))))
      (finally
        (router/unregister! provider)
        (fixture/stop-server! server)))))
