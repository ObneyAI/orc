(ns ai.obney.orc.orc-service.inherited-profile-fallback-repro-test
  (:require [clojure.test :refer [deftest is]] [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress] [java.nio.file Files]
           [java.util.concurrent TimeUnit]))
(def proof (atom nil))
(defn prefix! [{:keys [probe-effects]}]
  (swap! probe-effects inc)
  {:source "original-source"})
(deftest inherited-function-contract-survives-cross-provider-fallback
  (let [requests (atom []) responses (atom []) errors (atom []) effects (atom 0)
        dir (.toFile (Files/createTempDirectory "orc-inherited-profile-" (make-array java.nio.file.attribute.FileAttribute 0)))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        pool (java.util.concurrent.Executors/newFixedThreadPool 2)
        primary (keyword (str "inherited-primary-" (random-uuid)))
        backup (keyword (str "inherited-backup-" (random-uuid)))]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [raw (slurp (.getRequestBody exchange)) body (json/parse-string raw true)
                  _ (swap! requests conj {:raw raw :body body})
                  primary? (= "fixture/primary" (:model body))
                  tools? (seq (:tools body))
                  response (if primary? {:error {:message "controlled temporary unavailable" :code 503}}
                             {:id "controlled-backup-response" :model "fixture/backup-resolved"
                              :choices [{:index 0 :finish_reason (if tools? "tool_calls" "stop")
                                         :message (if tools?
                                                    {:role "assistant" :content nil
                                                     :tool_calls [{:id "controlled-call" :type "function"
                                                                   :function {:name "submit_response"
                                                                              :arguments "{\"answer\":\"original-source\",\"accepted\":false}"}}]}
                                                    {:role "assistant" :content "[[ ## answer ## ]]\noriginal-source\n[[ ## accepted ## ]]\nfalse\n[[ ## completed ## ]]"})}]
                              :usage {:prompt_tokens 8 :completion_tokens 5 :total_tokens 13}})
                  raw-response (json/generate-string response) bytes (.getBytes raw-response "UTF-8")]
              (swap! responses conj {:status (if primary? 503 200) :raw raw-response :body response})
              (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.sendResponseHeaders exchange (if primary? 503 200) (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable e (swap! errors conj {:class (.getName (class e))}))
            (finally (.close exchange))))))
    (.start server)
    (try
      (let [config {:provider :openrouter :config {:api-key "CONTROLLED-NOT-A-REAL-KEY"
                    :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server))) :timeout 5000}}]
        (llm/register-provider! primary (assoc config :model "fixture/primary" :output-mode :function-calling))
        (llm/register-provider! backup (assoc config :model "fixture/backup")))
      (h/with-async-test-context [ctx {:event-store-conn {:type :sqlite :database-file (str dir "/events.sqlite") :maximum-pool-size 2}
                                     :context {:tenant-id (random-uuid) :llm-provider primary :probe-effects effects}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow "inherited-profile-fallback-probe"
                        (orc/blackboard {:source [:string {:description "Original source supplied by the completed prefix."}]
                                         :answer [:string {:description "Literal original source label."}]
                                         :accepted [:boolean {:description "False is a valid assessment."}]})
                        (orc/sequence "task"
                          (orc/code "prefix" :fn "ai.obney.orc.orc-service.inherited-profile-fallback-repro-test/prefix!"
                            :reads [] :writes [:source])
                          (orc/llm "produce" :instruction "Copy the source label and set accepted to false."
                            :reads [:source] :writes [:answer :accepted]
                            :fallback-models [{:provider backup :model "fixture/backup"}]
                            :retry {:max-attempts 1 :backoff-ms []}
                            :options {:max-retries 0 :max-tokens 256}))))
              result (orc/execute ctx sheet {} :timeout-ms 20000 :max-ticks 1 :llm-call-budget 2)
              tick-id (:trace-id result)
              events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)
              nodes (orc/get-nodes-for-sheet ctx sheet)
              producer-id (:id (first (filter #(= "produce" (:name %)) nodes)))
              completion (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                              (= producer-id (:node-id %))) events))
              writes (when completion (orc/value-log-resolve-writes ctx (:tenant-id ctx) tick-id completion))
              bodies (mapv :body @requests)]
          (reset! proof {:directory (.getAbsolutePath dir) :primary primary :backup backup
                         :requests @requests :responses @responses :fixture-errors @errors
                         :result result :tick (orc/get-tick ctx tick-id) :events events :completion completion
                         :canonical-writes writes :prefix-effects @effects})
          (is (empty? @errors))
          (is (= :success (:status result) (:status completion)))
          (is (= {:answer "original-source" :accepted false} writes))
          (is (= "original-source" (get-in result [:outputs :answer])))
          (is (false? (get-in result [:outputs :accepted])))
          (is (= 1 @effects))
          (is (= ["fixture/primary" "fixture/backup"] (mapv :model bodies)))
          (is (= 2 (count bodies)))
          (is (= 2 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))
          (is (= :selected (get-in completion [:model-fallback :reason])))
          (is (= 1 (get-in completion [:model-fallback :candidate-index])))
          (let [plans (filter #(= :sheet/model-plan-retained (:event/type %)) events)
                plan (:plan (first plans))]
            (is (= 1 (count plans)))
            (is (= 1 (:version plan)))
            (is (= [:function-calling :function-calling]
                   (mapv #(get-in % [:options :output-mode]) (:candidates plan))))
            (is (= ["paired-function-v1-strict" "paired-function-v1-strict"]
                   (mapv #(get-in % [:profile :version]) (:candidates plan))))
            (is (not (str/includes? (pr-str plan) "CONTROLLED-NOT-A-REAL-KEY"))))
          (doseq [body bodies]
            (is (= "auto" (:tool_choice body)))
            (is (true? (get-in body [:tools 0 :function :strict])))
            (is (= #{"answer" "accepted"} (set (get-in body [:tools 0 :function :parameters :required]))))
            (is (= "string" (get-in body [:tools 0 :function :parameters :properties :answer :type])))
            (is (= "boolean" (get-in body [:tools 0 :function :parameters :properties :accepted :type])))
            (is (not (contains? body :response_format)))
            (is (not (str/includes? (pr-str (:messages body)) "[[ ##"))))))
      (finally (.stop server 0) (.shutdownNow pool)
               (when-not (.awaitTermination pool 5 TimeUnit/SECONDS)
                 (throw (ex-info "Owned HTTP fixture executor did not terminate" {})))))))
