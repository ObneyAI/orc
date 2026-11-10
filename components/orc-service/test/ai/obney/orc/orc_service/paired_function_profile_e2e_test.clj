(ns ai.obney.orc.orc-service.paired-function-profile-e2e-test
  "FUNCTION-2: subscribed ordinary generation preserves its effective profile."
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(def proofs (atom []))
(def packet-schema
  [:map {:closed true :description "Complete original identity packet."}
   [:items [:vector {:description "Independent source identities."}
            [:map {:closed true}
             [:same [:string {:description "Keyword identity label."}]]
             ["same" [:int {:description "String identity count; zero is valid."}]]]]]])
(def packet {:items [{:same "original-source" "same" 0}]})
(def wire {:items [{:__sio_key_0 "original-source" :__sio_key_1 0}]})
(defn receive [{:keys [inputs]}] {:received (:packet inputs)})

(defn- drain [ch]
  (loop [xs []]
    (let [[v port] (async/alts!! [ch (async/timeout 10000)])]
      (cond (not= port ch) (conj xs ::timeout)
            (nil? v) xs
            :else (recur (conj xs v))))))

(defn run-case! [provider-mode node-mode]
  (let [requests (atom []) errors (atom [])
        directory (.toFile (Files/createTempDirectory "orc-paired-profile-" (make-array java.nio.file.attribute.FileAttribute 0)))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        pool (java.util.concurrent.Executors/newFixedThreadPool 2)
        provider (keyword (str "paired-profile-" (random-uuid)))]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [request (json/parse-string (slurp (.getRequestBody exchange)) true)
                  _ (swap! requests conj request)
                  response {:id "controlled-profile-response" :model "fixture/resolved"
                            :choices [{:index 0 :finish_reason (if (:tools request) "tool_calls" "stop")
                                       :message (if (:tools request)
                                                  {:role "assistant" :content nil
                                                   :tool_calls [{:id "response-call" :type "function"
                                                                 :function {:name "submit_response"
                                                                            :arguments (json/generate-string wire)}}]}
                                                  {:role "assistant" :content (json/generate-string wire)})}]
                            :usage {:prompt_tokens 8 :completion_tokens 5 :total_tokens 13}}
                  bytes (.getBytes (json/generate-string response) "UTF-8")]
              (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable e (swap! errors conj {:class (.getName (class e))}))
            (finally (.close exchange))))))
    (.start server)
    (try
      (llm/register-provider! provider
        (cond-> {:provider :openrouter :model "fixture/requested"
                 :config {:api-key "CONTROLLED-NOT-A-REAL-KEY"
                          :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server))) :timeout 5000}}
          provider-mode (assoc :output-mode provider-mode)))
      (h/with-async-test-context [ctx {:event-store-conn {:type :sqlite :database-file (str directory "/events.sqlite") :maximum-pool-size 2}
                                     :context {:tenant-id (random-uuid) :llm-provider provider}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow "paired-profile-subscriber"
                        (orc/blackboard {:packet packet-schema :received packet-schema})
                        (orc/sequence "preserve"
                          (orc/llm "produce" :instruction "Return the contracted identity packet."
                            :reads [] :writes [:packet]
                            :retry {:max-attempts 1 :backoff-ms []}
                            :options (cond-> {:use-function-calling? false :max-retries 0 :max-tokens 256}
                                       node-mode (assoc :output-mode node-mode)))
                          (orc/code "receive" :fn "ai.obney.orc.orc-service.paired-function-profile-e2e-test/receive"
                            :reads [:packet] :writes [:received]))))
              stream (orc/execute-stream ctx sheet {} :llm-deltas? true :raw-deltas? true
                       :timeout-ms 20000 :max-ticks 1 :llm-call-budget 1)
              result (deref (:result stream) 25000 ::timeout)
              envelopes (drain (:events-ch stream))
              tick-id (:tick-id stream)
              events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)
              nodes (orc/get-nodes-for-sheet ctx sheet)
              completion (fn [name]
                           (let [id (:id (first (filter #(= name (:name %)) nodes)))]
                             (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                                  (= id (:node-id %))) events))))
              producer (completion "produce") consumer (completion "receive")
              proof {:directory (.getAbsolutePath directory)
                     :provider-mode provider-mode :node-mode node-mode :result result :tick (orc/get-tick ctx tick-id)
                     :requests @requests :fixture-errors @errors :envelopes envelopes :events events :producer producer :consumer consumer
                     :producer-writes (when producer (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick-id producer))
                     :consumer-reads (when consumer (orc/value-log-resolve-reads ctx (:tenant-id ctx) tick-id consumer))
                     :consumer-writes (when consumer (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick-id consumer))}]
          (swap! proofs conj proof)
          proof))
      (finally
        (.stop server 0)
        (.shutdownNow pool)
        (when-not (.awaitTermination pool 5 TimeUnit/SECONDS)
          (throw (ex-info "Owned HTTP fixture executor did not terminate" {})))))))

(deftest paired-profile-remains-blocking-through-subscribed-sqlite-execution
  (doseq [[provider-mode node-mode] [[:function-calling nil] [nil :function-calling]]]
    (let [{:keys [result tick requests fixture-errors envelopes producer consumer producer-writes consumer-reads consumer-writes]}
          (run-case! provider-mode node-mode)
          request (first requests)
          schema (get-in request [:tools 0 :function :parameters])]
      (is (empty? fixture-errors))
      (is (= :success (:status result) (:root-status tick) (:status producer) (:status consumer)))
      (is (= packet (:packet producer-writes) (:packet consumer-reads) (:received consumer-writes)))
      (is (= packet (get-in result [:outputs :received])))
      (is (= 1 (count requests)))
      (is (= "auto" (:tool_choice request)))
      (is (true? (get-in request [:tools 0 :function :strict])))
      (is (not (true? (:stream request))))
      (is (not (contains? request :response_format)))
      (is (= #{:__sio_key_0 :__sio_key_1} (set (keys (get-in schema [:properties :items :items :properties])))))
      (doseq [meaning ["Keyword identity label." "String identity count; zero is valid." "Complete original identity packet."]]
        (is (str/includes? (pr-str request) meaning)))
      (is (not (str/includes? (pr-str (:messages request)) "[[ ##")))
      (is (= :stream-closed (:orc.stream/type (last envelopes))))
      (is (not-any? #(#{:llm-raw-delta :llm-fields} (:orc.stream/type %)) envelopes)))))

(deftest strict-json-profile-remains-json-through-subscribed-execution
  (let [{:keys [result requests producer-writes consumer-reads envelopes]}
        (run-case! :function-calling :json-schema)
        request (first requests)]
    (is (= :success (:status result)))
    (is (= packet (:packet producer-writes) (:packet consumer-reads)))
    (is (= 1 (count requests)))
    (is (true? (get-in request [:response_format :json_schema :strict])))
    (is (not (contains? request :tools)))
    (is (not (true? (:stream request))))
    (is (= :stream-closed (:orc.stream/type (last envelopes))))))

(deftest invalid-inherited-profile-fails-durably-before-http
  (let [{:keys [result tick requests producer consumer events envelopes]}
        (run-case! :unsupported nil)]
    (is (= :failure (:status result) (:root-status tick) (:status producer)))
    (is (= :schema-validation-failed (:failure-kind producer)))
    (is (nil? consumer))
    (is (empty? requests))
    (is (empty? (filter #(and (= :sheet/execution-value-written (:event/type %))
                             (contains? #{:packet :received} (:key %))) events)))
    (is (= :stream-closed (:orc.stream/type (last envelopes))))))
