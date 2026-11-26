(ns ai.obney.orc.orc-service.retained-model-options-test
  (:require [clojure.test :refer [deftest is]] [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.ordinary-model-plan :as plan]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.inherited-profile-fallback-repro-test])
  (:import [com.sun.net.httpserver HttpServer HttpHandler] [java.net InetSocketAddress]
           [java.nio.file Files] [java.util.concurrent TimeUnit]))

(def proofs (atom []))

(deftest ordinary-fallback-retains-stop-and-reasoning-controls-in-wire-and-plan
  (let [requests (atom []) errors (atom []) effects (atom 0)
        primary (keyword (str "option-primary-" (random-uuid))) backup (keyword (str "option-backup-" (random-uuid)))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        pool (java.util.concurrent.Executors/newFixedThreadPool 2)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  primary? (= "fixture/primary" (:model body))
                  response (if primary? {:error {:message "controlled unavailable" :code 503}}
                             {:id "controlled-option-response" :model "fixture/backup-resolved"
                              :choices [{:finish_reason "tool_calls" :message {:role "assistant" :content nil
                                         :tool_calls [{:type "function" :function {:name "submit_response"
                                                       :arguments "{\"answer\":\"original-source\",\"accepted\":false}"}}]}}]})
                  bytes (.getBytes (json/generate-string response) "UTF-8")]
              (swap! requests conj body)
              (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.sendResponseHeaders exchange (if primary? 503 200) (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable error (swap! errors conj {:class (.getName (class error))}))
            (finally (.close exchange))))))
    (.start server)
    (try
      (let [config {:provider :openrouter :config {:api-key "SYNTHETIC-NOT-SENT"
                     :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server))) :timeout 5000}}]
        (llm/register-provider! primary (assoc config :model "fixture/primary" :output-mode :function-calling))
        (llm/register-provider! backup (assoc config :model "fixture/backup")))
      (doseq [[variant opts backup-opts expected-reasonings]
              [[:effort {:stop ["END-FIELD"] :reasoning-effort :high}
                {:stop ["BACKUP-END"] :reasoning-effort :low}
                [{:effort "high"} {:effort "low"}]]
               [:thinking {:stop ["FINISH-FIELD"] :reasoning-effort :high
                           :thinking {:type :enabled :budget-tokens 2048}}
                {:stop ["BACKUP-FINISH"] :reasoning-effort :low
                 :thinking {:type :enabled :budget-tokens 3072}}
                [{:enabled true :max_tokens 2048} {:enabled true :max_tokens 3072}]]]]
        (let [dir (.toFile (Files/createTempDirectory "orc-retained-options-" (make-array java.nio.file.attribute.FileAttribute 0)))
              before-calls (count @requests) before-effects @effects]
          (h/with-async-test-context
            [ctx {:event-store-conn {:type :sqlite :database-file (str dir "/events.sqlite") :maximum-pool-size 2}
                  :context {:tenant-id (random-uuid) :auth-claims {} :llm-provider primary :probe-effects effects}}]
            (let [sheet (orc/build-workflow! ctx
                          (orc/workflow (str "retained-options-" variant "-" (random-uuid))
                            (orc/blackboard {:source :string :answer :string :accepted :boolean})
                            (orc/sequence "task"
                              (orc/code "prefix" :fn "ai.obney.orc.orc-service.inherited-profile-fallback-repro-test/prefix!"
                                :reads [] :writes [:source])
                              (orc/llm "produce" :instruction "Copy the source and assess it."
                                :reads [:source] :writes [:answer :accepted]
                                :fallback-models [{:provider backup :model "fixture/backup" :options backup-opts}]
                                :options (merge {:max-retries 0 :max-tokens 4096} opts)))))
                  result (orc/execute ctx sheet {} :timeout-ms 20000 :max-ticks 1 :llm-call-budget 2)
                  tick (:trace-id result) events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick)
                  retained (:plan (first (filter #(= :sheet/model-plan-retained (:event/type %)) events)))
                  actual (subvec (vec @requests) before-calls)]
              (is (= :success (:status result)))
              (is (= "original-source" (get-in result [:outputs :answer])))
              (is (false? (get-in result [:outputs :accepted])))
              (is (= 1 (- @effects before-effects)))
              (is (= ["fixture/primary" "fixture/backup"] (mapv :model actual)))
              (is (= 2 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))
              (doseq [[body expected-opts expected-reasoning]
                      (map vector actual [opts (merge opts backup-opts)] expected-reasonings)]
                (is (= (:stop expected-opts) (:stop body)))
                (is (= expected-reasoning (:reasoning body))))
              (doseq [[candidate expected-opts]
                      (map vector (:candidates retained) [opts (merge opts backup-opts)])]
                (is (= expected-opts (select-keys (:options candidate) (keys expected-opts)))))
              (is (not= (journal/retained-configuration-id retained)
                        (journal/retained-configuration-id (assoc-in retained [:candidates 0 :options :stop] ["CHANGED"]))))
              (is (not= (journal/retained-configuration-id retained)
                        (journal/retained-configuration-id (assoc-in retained [:candidates 0 :options :reasoning-effort] :low))))
              (swap! proofs conj {:variant variant :directory (.getAbsolutePath dir) :requests actual :events events
                                  :plan retained :result result})))))
      (is (empty? @errors))
      (finally (.stop server 0) (.shutdownNow pool)
               (is (.awaitTermination pool 5 TimeUnit/SECONDS))))))

(deftest unknown-and-credential-options-are-refused-rather-than-dropped
  (let [primary (keyword (str "reject-option-primary-" (random-uuid)))
        module {:inputs [{:name :source :spec :string}]
                :outputs [{:name :answer :spec :boolean}] :instructions "Assess source."}
        node {:provider primary :fallback-models [{:model "fixture/backup"}]}]
    (llm/register-provider! primary {:provider :openrouter :model "fixture/primary" :output-mode :function-calling
                                    :config {:api-key "SYNTHETIC-NOT-SENT"}})
    (doseq [options [{:unknown-generation-control true} {:api-key "SYNTHETIC-NEVER-PERSIST"}
                    {:api-base "https://must-not-override.example"}]]
      (let [failure (try (plan/resolve-plan node primary module options) nil
                         (catch clojure.lang.ExceptionInfo error error))]
        (is (instance? clojure.lang.ExceptionInfo failure))
        (is (= :model-plan-incompatible (:failure-kind (ex-data failure))))
        (is (not (.contains (str failure) "SYNTHETIC-NEVER-PERSIST")))))))
