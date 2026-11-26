(ns ai.obney.orc.orc-service.researcher-model-fallback-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.ordinary-transport-failure-e2e-test :as http]
            [ai.obney.orc.orc-service.core.model-call-journal]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.time.interface :as time]))

(def proof (atom nil))
(defn server! []
  (let [requests (atom []) counts (atom {})
        pool (java.util.concurrent.Executors/newFixedThreadPool 4)
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify com.sun.net.httpserver.HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  model (:model body) n (get (swap! counts update model (fnil inc 0)) model)]
              (swap! requests conj body)
              (if (#{"fixture/prime" "fixture/sub" "fixture/sub-explicit"} model)
                (http/reply! exchange 503 "text/html" "fixture temporary unavailable")
                (let [outputs (case model
                                "fixture/prime-backup"
                                {:reasoning "Execute the fixture protocol."
                                 :code (case n
                                         1 "(do (touch {}) (llm \"inline\" :instruction \"Return inline value\" :writes [:inline-value]) (println \"continue\"))"
                                         2 "(emit-tree! [:sequence [:llm {:model \"fixture/sub-explicit\" :instruction \"Return emitted value\" :reads [] :writes [:emitted-value] :output-schemas {:emitted-value :string} :options {:max-tokens 8192}}] [:final {:keys [:emitted-value]}]])"
                                         "(final! {:summary (str (get-var :inline-value) \"/\" (get-var :emitted-value))})")}
                                "fixture/no-fallback" {:reasoning "Finish the fixture" :code "(final! {:summary \"single\"})"}
                                "fixture/sub-backup" (if (= n 1) {:inline-value "inline"} {:emitted-value "emitted"}))]
                  (http/reply! exchange 200 "application/json"
                    (json/generate-string {:id (str model n) :model model
                      :choices [{:index 0 :finish_reason "stop"
                                 :message {:role "assistant" :content (json/generate-string outputs)}}]
                      :usage {:prompt_tokens 2 :completion_tokens 2 :total_tokens 4}})))))
            (finally (.close exchange))))))
    (.start server)
    {:server server :executor pool :requests requests
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn build-researcher! [ctx]
  (orc/build-workflow! ctx
                      (orc/workflow "researcher-fallback"
                        (orc/blackboard {:summary :string :inline-value :string})
                        (orc/repl-researcher "research"
                          :model "fixture/prime"
                          :fallback-models [{:model "fixture/prime-backup"}]
                          :instruction "Use the declared fixture workflow and finalize its summary."
                          :writes [:summary] :mcp-tools ["touch"]
                          :tool-contracts {"touch" {:arguments [:map] :result [:map [:ok :boolean]]
                                                    :checkpoint-safe? true}}
                          :max-iterations 5
                          :options {:output-mode :json-schema :max-tokens 65536}
                          :rlm {:recursive? true :checkpointed? true :quantum {:max-iterations 1}
                                :sub-model "fixture/sub"
                                :sub-options {:output-mode :json-schema :max-tokens 16384}
                                :sub-fallback-models [{:model "fixture/sub-backup"}]
                                :timeouts {:provider-ms 20000 :iteration-ms 60000 :campaign-ms 120000}}))))

(deftest researcher-prime-inline-and-emitted-model-fallback
  (let [server (server!) effects (atom 0) tenant (random-uuid)
        db-file (str "/tmp/researcher-fallback-" (random-uuid) ".db")
        store {:type :sqlite :database-file db-file :maximum-pool-size 2}
        first-context (atom nil) reopened-context (atom nil)
        common {:tenant-id tenant :llm-provider :researcher-fallback-fixture
                :call-tool-fn (fn [_ _ _] (swap! effects inc) {:ok true})}]
    (try
      (llm/register-provider! :researcher-fallback-fixture
        {:provider :openrouter :model "fixture/prime"
         :config {:api-base (:url server) :api-key http/fixture-token :timeout 10000}})
      (let [ctx (h/create-async-test-context {:context common :event-store-conn store})
            _ (reset! first-context ctx)
            sheet (build-researcher! ctx) tick (random-uuid)
            held (promise) starts (atom 0) execute-researcher processors/execute-repl-researcher-node]
        ;; Suppress dispatch only after the real first quantum has durably completed.
        ;; No model or effect seam is faked. The continuation start remains durable,
        ;; representing a stopped worker before it acquired its next quantum.
        (with-redefs [processors/execute-repl-researcher-node
                      (fn [context]
                        (if (= 2 (swap! starts inc))
                          (do (deliver held true) nil)
                          (execute-researcher context)))]
          (h/run-and-apply! ctx {:command/name :sheet/tick-tree
                                :command/id (random-uuid) :command/timestamp (time/now)
                                :sheet-id sheet :tick-id tick :inputs {}
                                :options {:timeout-ms 120000 :llm-call-budget 8}})
          (is (= true (deref held 20000 :timeout)) "The first real quantum reached its saved continuation")
          (is (= 1 @effects))
          (h/stop-async-context ctx)
          (reset! first-context nil))
        (let [reopened (h/create-async-test-context {:context common :event-store-conn store})
              _ (reset! reopened-context reopened)
              _ (orc/resume-in-progress! (assoc reopened :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600)))]
          (is (h/settle-until! #(some? (runtime/durable-terminal-result reopened tick)) :timeout-ms 30000))
          (let [result (runtime/durable-terminal-result reopened tick)
                events (orc/value-log-read-tick-events reopened tenant tick)
                journal (qp/process-query (assoc reopened :query {:query/name :sheet/model-candidate-history
                                                                  :query/id (random-uuid) :query/timestamp (time/now)
                                                                  :tick-id tick}))]
            (reset! proof {:result result :events events :journal journal :requests @(:requests server) :effects @effects})
            (is (= :success (:status result)) (pr-str result))
            (is (= "inline/emitted" (get-in result [:outputs :summary])))
            (is (= 1 @effects))
            (is (= ["fixture/prime" "fixture/prime-backup" "fixture/sub" "fixture/sub-backup"
                    "fixture/prime-backup" "fixture/sub-explicit" "fixture/sub-backup" "fixture/prime-backup"]
                   (mapv :model @(:requests server))))
            (is (= 8192 (:max_tokens (first (filter #(= "fixture/sub-explicit" (:model %)) @(:requests server))))))
            (is (vector? (:query/result journal)))
            (is (some #(= :sheet/model-candidate-selected (:event/type %)) (:query/result journal)))
            (is (= 12 (count (filter #(= :sheet/model-request-recorded (:event/type %)) events)))
                "Prime and inline requests persist both start and completion; emitted requests use their node journal")
            (is (>= (count (filter #(= :sheet/model-candidate-selected (:event/type %)) events)) 2)))))
      (finally
        (when-let [ctx @first-context] (h/stop-async-context ctx))
        (when-let [ctx @reopened-context] (h/stop-async-context ctx))
        (http/stop-server! server)
        (doseq [suffix ["" "-shm" "-wal"]] (io/delete-file (str db-file suffix) true))))))


(deftest researcher-without-fallback-reserves-its-only-request-once
  (let [server (server!)]
    (try
      (llm/register-provider! :researcher-single-fixture
        {:provider :openrouter :model "fixture/no-fallback"
         :config {:api-base (:url server) :api-key http/fixture-token :timeout 10000}})
      (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid)
                                                :llm-provider :researcher-single-fixture}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow "single-request-researcher"
                        (orc/blackboard {:summary :string})
                        (orc/repl-researcher "research" :model "fixture/no-fallback"
                          :instruction "Finish the fixture" :writes [:summary]
                          :options {:output-mode :json-schema}
                          :rlm {:recursive? true :checkpointed? true})))
              result (orc/execute ctx sheet {} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 1)]
          (is (= :success (:status result)) (pr-str result))
          (is (= "single" (get-in result [:outputs :summary])))
          (is (= ["fixture/no-fallback"] (mapv :model @(:requests server))))))
      (finally (http/stop-server! server)))))
