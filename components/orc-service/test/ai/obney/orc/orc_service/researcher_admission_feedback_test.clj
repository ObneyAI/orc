(ns ai.obney.orc.orc-service.researcher-admission-feedback-test
  "Child admission failures must reach the next real HTTP request as setup failures."
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
(def model "fixture/researcher-admission-feedback")
(def rows-schema
  [:vector {:description "Retained source identities."}
   [:map {:closed true :description "One source identity."}
    [:reference [:string {:description "Exact source reference."}]]]])
(def answer-schema [:string {:description "Reference actually consumed by the admitted child."}])
(def child
  [:code {:reads [:rows] :writes [:answer]
          :fn '(fn [{:keys [inputs]}] {:answer (:reference (first (:rows inputs)))})}])
(def programs
  [(str "(store! :rows [{}]) (emit-tree! '" (pr-str child) ")")
   (str "(store! :rows [{:reference \"source-1\"}]) (emit-tree! '" (pr-str child) ")")
   "(final! {:answer (get-var :answer)})"])

(defn- start-http! []
  (let [requests (atom []) errors (atom []) pool (Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [request (json/parse-string (slurp (.getRequestBody exchange)) true)
                  ordinal (count (swap! requests conj request))
                  _ (when-not (and (= model (:model request)) (<= ordinal 3))
                      (throw (ex-info "Unexpected local fixture dispatch" {:ordinal ordinal})))
                  fields (set (keys (get-in request [:response_format :json_schema :schema :properties])))
                  reply (cond-> {:code (nth programs (dec ordinal))}
                          (contains? fields :reasoning)
                          (assoc :reasoning "Repair the reported setup error before executing the child."))
                  bytes (.getBytes (json/generate-string
                          {:id (str "admission-feedback-" ordinal) :model model
                           :choices [{:finish_reason "stop" :message {:role "assistant" :content (json/generate-string reply)}}]
                           :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}) "UTF-8")]
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable error
              (swap! errors conj (ex-message error))
              (try (.sendResponseHeaders exchange 500 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(deftest rejected-child-input-reason-reaches-next-model-request-and-repair-runs-once
  (let [directory (str (java.nio.file.Files/createTempDirectory "researcher-admission-feedback-"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
        provider (keyword (str "admission-feedback-" (random-uuid)))
        server (start-http!)]
    (try
      (llm/register-provider! provider
        {:provider :openrouter :model model
         :config {:api-base (:url server) :api-key "LOCAL-ONLY" :timeout 10000}})
      (h/with-async-test-context
        [ctx {:context {:tenant-id (random-uuid) :llm-provider provider}
              :event-store-conn {:type :sqlite :database-file (str directory "/events.sqlite") :maximum-pool-size 2}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow (str "admission-feedback-" (random-uuid))
                        (orc/blackboard {:answer answer-schema :rows rows-schema})
                        (orc/repl-researcher "repair-child-admission" :model model :reads [:rows] :writes [:answer]
                          :instruction "Use the declared rows contract, repair invalid retained data, and return the child result."
                          :browser-tools [] :mcp-tools [] :max-iterations 3
                          :options {:output-mode :json-schema :max-retries 0 :max-tokens 4096}
                          :rlm {:recursive? true :checkpointed? true :auto-classify? false
                                :timeouts {:provider-ms 10000 :iteration-ms 30000 :phase2-ms 30000 :campaign-ms 60000}})))
              tick (random-uuid)
              result (orc/execute ctx sheet {} :tick-id tick :timeout-ms 60000 :max-ticks 1 :llm-call-budget 3)
              events (h/read-all-events ctx)
              child-starts (filterv #(and (= :sheet/tree-tick-started (:event/type %))
                                          (not= tick (:tick-id %))) events)
              child-ticks (set (map :tick-id child-starts))
              child-completions (filterv #(and (= :sheet/node-execution-completed (:event/type %))
                                               (contains? child-ticks (:tick-id %))) events)
              parent-completion (last (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                                    (= tick (:tick-id %))) events))
              projected (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick parent-completion)
              requests @(:requests server)
              next-wire (pr-str (:messages (second requests)))
              proof {:directory directory :result result :events events :requests requests :errors @(:errors server)}]
          (reset! last-proof proof)
          (spit (str directory "/proof.edn") (pr-str proof))
          (println "RESEARCHER-ADMISSION-FEEDBACK-PROOF" directory)
          (is (empty? (:errors proof)) (pr-str (:errors proof)))
          (is (= 3 (count requests)))
          (is (= :success (:status result)) (pr-str result))
          (is (= "source-1" (get-in result [:outputs :answer])))
          (is (= "source-1" (:answer projected)) "Parent canonical projection resolves the repaired child result")
          (is (= 1 (count child-starts)) "The rejected child never started; only the repaired child tick ran")
          (is (= 1 (count child-completions)) "No leaf ran under rejected admission; repaired code ran exactly once")
          (is (true? (str/includes? next-wire "Blackboard schema validation failed for :rows"))
              "The next actual provider request must carry the original admission reason")
          (is (true? (str/includes? next-wire "missing required key")))
          (is (true? (str/includes? next-wire ":reference")))
          (is (true? (str/includes? next-wire "REJECTED BEFORE EXECUTION"))
              "Failure before tick admission is distinct from an executed child failure")))
      (finally
        (.stop ^HttpServer (:server server) 0)
        (.shutdownNow ^java.util.concurrent.ExecutorService (:pool server))
        (.awaitTermination ^java.util.concurrent.ExecutorService (:pool server) 10 TimeUnit/SECONDS)))))
