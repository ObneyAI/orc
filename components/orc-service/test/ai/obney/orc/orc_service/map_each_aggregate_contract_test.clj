(ns ai.obney.orc.orc-service.map-each-aggregate-contract-test
  "A typed parent aggregate is validated independently of its successful items."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors TimeUnit]))

(def last-proof (atom nil))
(def model "fixture/map-aggregate-contract")
(def reference-schema [:string {:description "Original scalar source reference."}])
(def observation-schema
  [:map {:closed true :description "Successfully observed source value."}
   [:reading [:string {:description "Actual controlled source observation."}]]])
(def aggregate-schema
  [:vector {:description "Each result must retain its source reference and observation."}
   [:map {:closed true :description "Explicitly required aggregate row contract."}
    [:reference reference-schema]
    [:observation observation-schema]]])

(defn- start-http!
  ([] (start-http! false))
  ([partial?]
  (let [requests (atom []) errors (atom []) pool (Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [request (json/parse-string (slurp (.getRequestBody exchange)) true)
                  ordinal (count (swap! requests conj request))
                  _ (when-not (and (= model (:model request)) (<= ordinal 2))
                      (throw (ex-info "Unexpected controlled aggregate dispatch" {:ordinal ordinal})))
                  bytes (.getBytes (json/generate-string
                          {:id (str "map-aggregate-" ordinal) :model model
                           :choices [{:finish_reason "stop" :message {:role "assistant"
                                      :content (json/generate-string {:reading (if (and partial? (= ordinal 2)) 42 "controlled-observation")})}}]
                           :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "UTF-8")]
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable error
              (swap! errors conj (ex-message error))
              (try (.sendResponseHeaders exchange 500 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))})))

(deftest invalid-parent-aggregate-fails-without-discarding-successful-item-evidence
  (let [directory (str (java.nio.file.Files/createTempDirectory "map-aggregate-contract-"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
        provider (keyword (str "map-aggregate-" (random-uuid))) server (start-http!)]
    (try
      (llm/register-provider! provider
        {:provider :openrouter :model model
         :config {:api-base (:url server) :api-key "LOCAL-ONLY" :timeout 10000}})
      (h/with-async-test-context
        [ctx {:context {:tenant-id (random-uuid) :llm-provider provider}
              :event-store-conn {:type :sqlite :database-file (str directory "/events.sqlite") :maximum-pool-size 2}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow (str "map-aggregate-contract-" (random-uuid))
                        (orc/blackboard {:references [:vector {:description "Complete scalar source collection."} reference-schema]
                                         :reference reference-schema :observation observation-schema :rows aggregate-schema})
                        (orc/map-each "observe-sources" :from :references :as :reference :into :rows :parallel 2
                          (orc/llm "observe-one" :model model :reads [:reference] :writes [:observation]
                            :instruction "Return the controlled observation for this exact source reference."
                            :options {:output-mode :json-schema :max-retries 0 :max-tokens 128}))))
              tick (random-uuid)
              result (orc/execute ctx sheet {:references ["source-A" "source-B"]}
                                   :tick-id tick :timeout-ms 30000 :max-ticks 1 :llm-call-budget 2)
              events (h/read-all-events ctx)
              completions (filterv #(and (= tick (:tick-id %)) (= :sheet/node-execution-completed (:event/type %))) events)
              parent (first (filter #(= :map-each (:node-type %)) completions))
              items (filterv #(not= :map-each (:node-type %)) completions)
              item-writes (mapv #(orc/value-log-resolve-writes ctx (:tenant-id ctx) tick %) items)
              parent-writes (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick parent)
              hydrated (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick)
              rejected (filterv #(and (= :sheet/execution-value-rejected (:event/type %))
                                      (= :rows (:key %)) (= (:node-id parent) (:node-id %))) hydrated)
              aggregate-events (filterv #(and (= tick (:tick-id %)) (= :rows (:key %))
                                              (= :sheet/execution-value-written (:event/type %))) events)
              proof {:directory directory :result result :events events :requests @(:requests server)
                     :errors @(:errors server) :parent parent :item-writes item-writes :parent-writes parent-writes}]
          (reset! last-proof proof)
          (spit (str directory "/proof.edn") (pr-str proof))
          (println "MAP-AGGREGATE-CONTRACT-PROOF" directory)
          (is (empty? (:errors proof)) (pr-str (:errors proof)))
          (is (= 2 (count (:requests proof))))
          (is (= 2 (count items)))
          (is (every? #(= :success (:status %)) items) "Individual typed observations are valid and successful")
          (is (= [{:observation {:reading "controlled-observation"}}
                  {:observation {:reading "controlled-observation"}}] item-writes)
              "Healthy per-item evidence remains canonically resolvable after parent validation")
          (is (= :failure (:status parent)) "A schema-invalid aggregate cannot complete successfully")
          (is (true? (str/includes? (str (:error parent)) "Blackboard schema validation failed for :rows")))
          (is (true? (str/includes? (str (:error parent)) "missing required key")))
          (is (true? (str/includes? (str (:error parent)) ":reference")))
          (is (empty? aggregate-events) "Rejected aggregate is never committed as a canonical value")
          (is (not (contains? parent-writes :rows)) "Parent projection cannot expose a rejected aggregate")
          (is (= [{:observation {:reading "controlled-observation"}}
                  {:observation {:reading "controlled-observation"}}] (:value (first rejected)))
              "Rejected aggregate candidate is retained as hydrated diagnostic evidence")
          (is (= 1 (count rejected)))
          (is (= :failure (:status result)))))
      (finally
        (.stop ^HttpServer (:server server) 0)
        (.shutdownNow ^java.util.concurrent.ExecutorService (:pool server))
        (.awaitTermination ^java.util.concurrent.ExecutorService (:pool server) 10 TimeUnit/SECONDS)))))

(deftest valid-partial-aggregate-keeps-its-status-and-healthy-canonical-value
  (let [directory (str (java.nio.file.Files/createTempDirectory "map-partial-contract-"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
        provider (keyword (str "map-partial-" (random-uuid))) server (start-http! true)
        partial-schema [:vector {:description "Only successful typed observations, compacted after item failure."}
                        [:map {:closed true :description "A healthy scalar-source item result."}
                         [:observation observation-schema]]]]
    (try
      (llm/register-provider! provider
        {:provider :openrouter :model model
         :config {:api-base (:url server) :api-key "LOCAL-ONLY" :timeout 10000}})
      (h/with-async-test-context
        [ctx {:context {:tenant-id (random-uuid) :llm-provider provider}
              :event-store-conn {:type :sqlite :database-file (str directory "/events.sqlite") :maximum-pool-size 2}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow (str "map-partial-contract-" (random-uuid))
                        (orc/blackboard {:references [:vector {:description "Original source collection."} reference-schema]
                                         :reference reference-schema :observation observation-schema :rows partial-schema})
                        (orc/map-each "partial-observations" :from :references :as :reference :into :rows :parallel 2
                          (orc/llm "observe-one" :model model :reads [:reference] :writes [:observation]
                            :instruction "Return the controlled source observation."
                            :options {:output-mode :json-schema :max-retries 0 :max-tokens 128}))))
              tick (random-uuid)
              result (orc/execute ctx sheet {:references ["source-A" "source-B"]}
                                   :tick-id tick :timeout-ms 30000 :max-ticks 1 :llm-call-budget 2)
              events (h/read-all-events ctx)
              completions (filterv #(and (= tick (:tick-id %)) (= :sheet/node-execution-completed (:event/type %))) events)
              parent (first (filter #(= :map-each (:node-type %)) completions))
              items (filterv #(not= :map-each (:node-type %)) completions)
              projected (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick parent)
              proof {:directory directory :result result :events events :requests @(:requests server)
                     :errors @(:errors server) :parent parent :parent-writes projected}]
          (spit (str directory "/proof.edn") (pr-str proof))
          (println "MAP-PARTIAL-CONTRACT-PROOF" directory)
          (is (empty? (:errors proof)))
          (is (= 2 (count (:requests proof))))
          (is (= {:success 1 :failure 1} (frequencies (map :status items))))
          (is (= :partial (:status parent)))
          (is (= 1 (count (get-in parent [:partial-summary :failure-indices]))))
          (is (= [{:observation {:reading "controlled-observation"}}] (:rows projected)))
          (is (= :partial (:status result)))))
      (finally
        (.stop ^HttpServer (:server server) 0)
        (.shutdownNow ^java.util.concurrent.ExecutorService (:pool server))
        (.awaitTermination ^java.util.concurrent.ExecutorService (:pool server) 10 TimeUnit/SECONDS)))))
