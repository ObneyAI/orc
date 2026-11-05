(ns ai.obney.orc.orc-service.rate-limit-model-alternative-e2e-test
  "DET-E2E-326: expected-green characterization of the pinned client's 429 classifier.
   Reuses the accepted same-task consumer tree. Local HTTP only, real Grain in-memory store."
  (:require [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.scoped-model-alternative-e2e-test :as scoped]
            [ai.obney.orc.orc-service.ordinary-transport-failure-e2e-test :as http]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [litellm.router :as router])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors]))

(def provider :rate-limit-paired-observation)
(def last-proof (atom nil))

(defn start-server! []
  (let [requests (atom []) errors (atom []) executor (Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server executor)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  inputs (scoped/wire-inputs body) id (get-in inputs [:request :item-id])
                  model (:model body)
                  request-index (dec (count (swap! requests conj {:model model :inputs inputs :body body})))
                  [status response]
                  (case [id model]
                    ["RATE" "fixture/primary"]
                    [429 {:error {:message "fixture temporary request limit"}}]
                    ["QUOTA" "fixture/primary"]
                    [429 {:error {:message "fixture quota exceeded"}}]
                    ["RATE" "fixture/alternative"]
                    [200 {:id "fixture-rate-alternative" :model model
                          :choices [{:index 0 :finish_reason "stop"
                                     :message {:role "assistant"
                                               :content (json/generate-string
                                                          {:answer-reasoning "The controlled provider evaluated this exact fixture item."
                                                           :item-id id :answer false})}}]
                          :usage {:prompt_tokens 12 :completion_tokens 12 :total_tokens 24}}]
                    (throw (ex-info "Unexpected fixture request" {})))]
              (swap! requests update request-index assoc :response-status status :response-body response)
              (http/reply! exchange status "application/json" (json/generate-string response)))
            (catch Throwable e
              (swap! errors conj {:class (.getName (class e))})
              (try (http/reply! exchange 500 "text/plain" "fixture handler failed") (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :executor executor :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(deftest det-e2e-326-actual-rate-limit-switches-but-quota-does-not
  (let [server (start-server!) effects (atom {}) admissions (atom [])]
    (try
      (llm/register-provider! provider {:provider :openrouter :model "fixture/primary"
                                       :config {:api-base (:url server) :api-key http/fixture-token :timeout 15000}})
      (let [runs (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider provider
                                                          :fixture-effects effects :fixture-admissions admissions}}]
                   (let [package (scoped/build-package! ctx)]
                     (mapv #(scoped/execute-task ctx package % 2) ["RATE" "QUOTA"])))
            by-id (into {} (map (juxt :item-id identity) runs))
            requests @(:requests server)
            requests-for (fn [id] (filterv #(= id (get-in % [:inputs :request :item-id])) requests))
            rate-requests (requests-for "RATE")
            quota-requests (requests-for "QUOTA")]
        (reset! last-proof (http/readable-snapshot
                            {:runs runs :requests requests :effects @effects :admissions @admissions
                             :server-errors @(:errors server) :paid-provider-calls 0
                             :event-store :in-memory :scope :pinned-client-429-classification}))
        (is (empty? @(:errors server)))
        (is (= 3 (count requests)))
        (is (= [429 200] (mapv :response-status rate-requests)))
        (is (= [429] (mapv :response-status quota-requests)))
        (is (= ["fixture/primary" "fixture/alternative"] (mapv :model rate-requests)))
        (is (= ["fixture/primary"] (mapv :model quota-requests)))
        (is (= :success (get-in by-id ["RATE" :result :status])))
        (is (= "RATE" (get-in by-id ["RATE" :result :outputs :item-id])))
        (is (false? (get-in by-id ["RATE" :result :outputs :answer])))
        (is (= :failure (get-in by-id ["QUOTA" :result :status])))
        (is (= {"RATE" 1 "QUOTA" 1} @effects))
        (is (= 2 (count @admissions)))
        (is (apply = (map #(dissoc (:body %) :model) rate-requests))
            "Same serialized instruction, input, schema and options; only model differs")
        (doseq [[id type allowed? n] [["RATE" :litellm/rate-limit true 2]
                                     ["QUOTA" :litellm/quota-exceeded false 1]]
                :let [run (get by-id id) rows (vec (scoped/model-rows run))
                      primary (:completion (first rows))
                      captures (filter #(= id (get-in % [:admission :task-id])) @admissions)
                      {:keys [admission ownership]} (first captures)
                      root (first (:families run)) child (second (:families run))
                      root-options (get-in root [:context :options])
                      child-options (get-in child [:context :options])
                      starts (filter #(= :sheet/tree-tick-started (:event/type %)) (:events child))
                      deadline (:execution-deadline-ms root-options)]]
          (is (= 2 (count (:families run))))
          (is (= n (count rows)))
          (is (= :failure (:status primary)))
          (is (= :transport-failure (:failure-kind primary)))
          (is (= {:type type :http-status 429} (get-in primary [:provider-evidence :transport])))
          (is (= 1 (count captures)))
          (is (scoped/own-predecessor? ownership))
          (is (true? (:owned? admission)))
          (is (= allowed? (:allowed? admission)))
          (is (= (:event/id primary) (:predecessor-event-id admission)))
          (is (= (:event/id (:start ownership)) (:gate-start-id admission)))
          (is (= (:tick-id primary) (:tick-id admission)))
          (is (= 1 (count starts)) "Alternative retains the same delegated task tick")
          (is (= (:root-tick-id run) (:budget-root-tick-id admission) (:llm-budget-root-tick-id child-options)))
          (is (= 2 (:llm-call-budget root-options) (:llm-call-budget child-options)
                 (:root-call-budget admission) (:candidate-call-budget admission)))
          (is (and (integer? deadline) (pos? deadline)))
          (is (= deadline (:execution-deadline-ms child-options)
                 (get-in (first starts) [:options :execution-deadline-ms])
                 (:root-deadline-ms admission) (:candidate-deadline-ms admission)))
          (doseq [request (requests-for id)
                  :let [matching (filter #(= (:model request) (get-in % [:completion :requested-model])) rows)]]
            (is (= 1 (count matching)))
            (is (= (:inputs request) (:reads (first matching))))
            (is (= {:operation-id (str "completed-tool/" id) :item-id id :ordinal 1}
                   (get-in request [:inputs :tool-receipt])))))
        (is (apply = (map :reads (scoped/model-rows (get by-id "RATE"))))))
      (finally
        (router/unregister! provider)
        (http/stop-server! server)))))
