(ns ai.obney.orc.orc-service.standard-model-fallback-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.scoped-model-alternative-e2e-test :as fixture]
            [ai.obney.orc.orc-service.ordinary-transport-failure-e2e-test :as http]))

(def last-proof (atom nil))

(defn package! [ctx]
  (let [task (orc/build-workflow! ctx
               (orc/workflow "standard-fallback-task" (orc/blackboard fixture/board)
                 (orc/llm "assessment" :model "fixture/primary"
                   :fallback-models [{:model "fixture/alternative"}]
                   :instruction fixture/instruction :reads fixture/task-reads :writes fixture/task-writes
                   :options fixture/generation-options)))
        parent (orc/build-workflow! ctx
                 (orc/workflow "standard-fallback-parent" (orc/blackboard fixture/board)
                   (orc/sequence "task"
                     (orc/code "tool" :fn "ai.obney.orc.orc-service.scoped-model-alternative-e2e-test/completed-tool"
                       :reads [:request] :writes [:tool-receipt])
                     (orc/delegate "assessment" :target-sheet-id task
                       :reads fixture/task-reads :writes fixture/task-writes :timeout-ms 60000))))]
    {:task task :parent parent}))

(deftest standard-node-switches-without-replaying-upstream
  (let [server (fixture/start-server!) effects (atom {})]
    (try
      (llm/register-provider! fixture/provider {:provider :openrouter :model "fixture/primary"
        :config {:api-base (:url server) :api-key http/fixture-token :timeout 15000}})
      (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider fixture/provider
                                               :fixture-effects effects}}]
        (let [package (package! ctx)
              a (fixture/execute-task ctx package "TASK-A" 2)
              b (fixture/execute-task ctx package "AUTH" 2)]
          (reset! last-proof {:a a :b b :requests @(:requests server) :effects @effects})
          (is (= :success (get-in a [:result :status])) (pr-str (:result a)))
          (is (false? (get-in a [:result :outputs :answer])))
          (is (= :failure (get-in b [:result :status])) (pr-str (:result b)))
          (is (= ["fixture/primary" "fixture/alternative" "fixture/primary"]
                 (mapv :model @(:requests server))))
          (is (= {"TASK-A" 1 "AUTH" 1} @effects))
          (is (= :selected
                 (:reason (:model-fallback
                           (first (filter :model-fallback (mapcat #(get-in % [:trace :node-traces]) (:families a))))))))
          (is (= :policy-refused
                 (:reason (:model-fallback
                           (first (filter :model-fallback (mapcat #(get-in % [:trace :node-traces]) (:families b))))))))))
      (finally (.stop (:server server) 0) (.shutdownNow ^java.util.concurrent.ExecutorService (:executor server))))))

(require '[cheshire.core :as json])

(def last-decision-proof (atom nil))

(deftest native-decisions-and-conditions-switch-to-chat-without-changing-answers
  (let [server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
        pool (java.util.concurrent.Executors/newFixedThreadPool 2)
        requests (atom []) results (atom [])
        native :fallback-native chat :fallback-chat]
    (.setExecutor server pool)
    (.createContext server "/"
      (reify com.sun.net.httpserver.HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  native? (contains? body :questions)]
              (swap! requests conj {:native? native? :body body})
              (if native?
                (http/reply! exchange 503 "text/plain" "temporary unavailable")
                (http/reply! exchange 200 "application/json"
                  (json/generate-string
                    {:model "fixture/chat-backup"
                     :choices [{:index 0 :finish_reason "tool_calls"
                                :message {:role "assistant" :content nil
                                          :tool_calls [{:id "fixture-call" :type "function"
                                                        :function {:name "submit_response"
                                                                   :arguments "{\"answer\":false,\"result\":false}"}}]}}]}))))
            (finally (.close exchange))))))
    (.start server)
    (try
      (let [url (str "http://127.0.0.1:" (.getPort (.getAddress server)))]
        (llm/register-provider! native {:provider :openrouter :protocol :decision :model "fixture/native"
                                        :config {:api-key http/fixture-token :decisions-url (str url "/decisions")}})
        (llm/register-provider! chat {:provider :openrouter :model "fixture/chat-backup"
                                      :config {:api-key http/fixture-token :api-base url}})
        (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider native}}]
          (doseq [kind [:decision :condition]]
            (let [node (if (= kind :decision)
                         (orc/llm-decision "decision" :provider native :model "fixture/native"
                           :fallback-models [{:provider chat :model "fixture/chat-backup"}]
                           :reads [:request] :writes [:answer] :instruction "Assess this request.")
                         (orc/llm-condition "condition" :provider native :model "fixture/native"
                           :fallback-models [{:provider chat :model "fixture/chat-backup"}]
                           :reads [:request] :instruction "Does this request qualify?"))
                  sheet (orc/build-workflow! ctx (orc/workflow (name kind)
                          (orc/blackboard {:request :string :answer :boolean}) node))
                  result (orc/execute ctx sheet {:request "fixture-owned-input"}
                                     :timeout-ms 60000 :max-ticks 1 :llm-call-budget 2)]
              (swap! results conj {:kind kind :result result
                                    :events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id result))})
              (is (= (if (= kind :decision) :success :failure) (:status result)) (pr-str result))
              (when (= kind :decision) (is (false? (get-in result [:outputs :answer]))))))))
      (reset! last-decision-proof {:requests @requests :runs @results})
      (is (= [true false true false] (mapv :native? @requests)))
      (finally (.stop server 0) (.shutdownNow pool)))))
