(ns ai.obney.orc.orc-service.dictionary-key-contract-test
  "MODEL-CONTRACT-14: colliding finite dictionary keys survive strict HTTP,
   canonical values, delegation and a deterministic native-value consumer."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def proof (atom nil))
(def dictionary-schema
  [:map-of {:description "Decisions keyed by exact source identifiers." :min 2 :max 2}
   [:enum {:description "Keyword and string identifiers are distinct."
           :descriptions {:same "Keyword source decision." "same" "Literal string source decision."}}
    :same "same"]
   :boolean])
(def receipt-schema
  [:map {:closed true} [:keyword-value :boolean] [:string-value :boolean]
   [:key-count :int] [:dictionary dictionary-schema]])

(defn consume [{:keys [inputs]}]
  (let [dictionary (:dictionary inputs)]
    {:receipt {:keyword-value (get dictionary :same)
               :string-value (get dictionary "same")
               :key-count (count dictionary) :dictionary dictionary}}))

(defn- fixture! []
  (let [requests (atom []) errors (atom [])
        pool (java.util.concurrent.Executors/newFixedThreadPool 1)
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify com.sun.net.httpserver.HttpHandler
        (handle [_ exchange]
          (try
            (let [raw (slurp (.getRequestBody exchange))
                  output {:dictionary [{:key {:type "keyword" :value "same"} :value false}
                                       {:key {:type "string" :value "same"} :value true}]}
                  response (json/generate-string
                             {:model "fixture/dictionary"
                              :choices [{:index 0 :finish_reason "stop"
                                         :message {:role "assistant" :content (json/generate-string output)}}]
                              :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})
                  bytes (.getBytes response "UTF-8")]
              (swap! requests conj {:request-body raw :request (json/parse-string raw true) :response-body response})
              (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable e (swap! errors conj {:class (.getName (class e)) :message (.getMessage e)}))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(deftest typed-dictionary-keys-survive-public-delegation-and-native-consumption
  (let [{:keys [server pool requests errors url]} (fixture!)
        db (str "/tmp/dictionary-contract-" (random-uuid) ".sqlite")
        expected {:keyword-value false :string-value true :key-count 2 :dictionary {:same false "same" true}}]
    (try
      (llm/register-provider! :dictionary-contract
        {:provider :openrouter :model "fixture/dictionary" :output-mode :json-schema
         :config {:api-base url :api-key "CONTROLLED-NOT-A-REAL-KEY" :timeout 10000}})
      (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider :dictionary-contract}
                                      :event-store-conn {:type :sqlite :database-file db :maximum-pool-size 4}}]
        (let [board {:dictionary dictionary-schema :receipt receipt-schema}
              child (orc/build-workflow! ctx
                      (orc/workflow "dictionary-consumer" (orc/blackboard board)
                        (orc/code "consume" :reads [:dictionary] :writes [:receipt]
                          :fn "ai.obney.orc.orc-service.dictionary-key-contract-test/consume")))
              root (orc/build-workflow! ctx
                     (orc/workflow "dictionary-producer" (orc/blackboard board)
                       (orc/sequence "sequence"
                         (orc/llm "produce" :instruction "Return the declared source decisions."
                           :reads [] :writes [:dictionary] :options {:max-retries 0}
                           :retry {:max-attempts 1 :backoff-ms []})
                         (orc/delegate "consume-native" :target-sheet-id child :reads [:dictionary] :writes [:receipt]))))
              result (orc/execute ctx root {} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 1)
              events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id result))
              completions (filter #(= :sheet/node-execution-completed (:event/type %)) events)
              writes (mapv #(orc/value-log-resolve-writes ctx (:tenant-id ctx) (:trace-id result) %) completions)
              delegate-id (:id (first (filter #(= "consume-native" (:name %)) (orc/get-nodes-for-sheet ctx root))))
              delegate-event (first (filter #(= delegate-id (:node-id %)) completions))
              child-tick-id (get-in delegate-event [:write-sources :receipt :tick-id])
              child-start (orc/value-log-tick-started-event ctx (:tenant-id ctx) child-tick-id)
              child-tick (orc/get-tick ctx child-tick-id)
              consumer-id (:id (first (filter #(= "consume" (:name %)) (orc/get-nodes-for-sheet ctx child))))
              consumer-event (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                                  (= consumer-id (:node-id %)))
                                            (orc/value-log-read-tick-events ctx (:tenant-id ctx) child-tick-id)))
              consumer-reads (orc/value-log-resolve-reads ctx (:tenant-id ctx) child-tick-id consumer-event)
              consumer-writes (orc/value-log-resolve-writes ctx (:tenant-id ctx) child-tick-id consumer-event)
              request (:request (first @requests))
              key-schema (get-in request [:response_format :json_schema :schema :properties :dictionary :items :properties :key])
              prompt (get-in request [:messages 0 :content])]
          (reset! proof {:result result :events events :writes writes :requests @requests :fixture-errors @errors
                         :child-start child-start :child-tick child-tick :consumer-event consumer-event
                         :consumer-reads consumer-reads :consumer-writes consumer-writes})
          (is (empty? @errors))
          (is (= :success (:status result)) (pr-str result))
          (is (= expected (get-in result [:outputs :receipt])))
          (is (= :success (:root-status (orc/get-tick ctx (:trace-id result)))))
          (is (= 1 (count @requests)))
          (is (= #{["keyword"] ["string"]}
                 (set (map #(get-in % [:properties :type :enum]) (:anyOf key-schema)))))
          (is (every? #(= ["type" "value"] (:required %)) (:anyOf key-schema)))
          (doseq [meaning ["Keyword source decision." "Literal string source decision."
                           "Keyword and string identifiers are distinct."]]
            (is (str/includes? prompt meaning)))
          (is (some #(= {:same false "same" true} (:dictionary %)) writes))
          (is (some #(= expected (:receipt %)) writes))
          (is (some? child-tick-id))
          (is (not= (:trace-id result) child-tick-id))
          (is (= (:trace-id result) (:parent-tick-id child-start)))
          (is (= :success (:root-status child-tick)))
          (is (= child-tick-id (:tick-id consumer-event)))
          (is (= {:same false "same" true} (:dictionary consumer-reads)))
          (is (= expected (:receipt consumer-writes)))
          (is (= dictionary-schema (get-in (orc/get-blackboard-by-key ctx root) [:dictionary :schema])))))
      (finally
        (.stop server 0) (.shutdownNow pool)
        (doseq [suffix ["" "-shm" "-wal"]] (.delete (java.io.File. (str db suffix))))))))
