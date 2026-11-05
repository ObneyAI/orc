(ns ai.obney.orc.orc-service.configured-model-fallback-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.interface :as orc]))

(deftest configured-model-fallback-is-public
  (let [constructor (ns-resolve 'ai.obney.orc.orc-service.interface 'llm-fallback)]
    (is (some? constructor) "The public constructor must exist")
    (when constructor
      (let [node (constructor "assessment" :candidates [{:model "fixture/one"} {:model "fixture/two"}]
                              :instruction "Assess" :reads [:request] :writes [:answer])]
        (is (= :fallback (:node-type node)))
        (is (= :temporary-transport (:model-fallback-policy node)))
        (is (= ["fixture/one" "fixture/two"] (mapv :model (:children node))))))))

(require '[ai.obney.orc.llm.interface :as llm]
         '[ai.obney.orc.orc-service.test-helpers :as h]
         '[ai.obney.orc.orc-service.scoped-model-alternative-e2e-test :as fixture]
         '[ai.obney.orc.orc-service.ordinary-transport-failure-e2e-test :as http])

(def last-proof (atom nil))

(defn package! [ctx]
  (let [task (orc/build-workflow! ctx
               (orc/workflow "configured-task" (orc/blackboard fixture/board)
                 (orc/llm-fallback "models" :candidates [{:model "fixture/primary"} {:model "fixture/alternative"}]
                   :instruction fixture/instruction :reads fixture/task-reads :writes fixture/task-writes
                   :options fixture/generation-options)))
        parent (orc/build-workflow! ctx
                 (orc/workflow "configured-parent" (orc/blackboard fixture/board)
                   (orc/sequence "task"
                     (orc/code "tool" :fn "ai.obney.orc.orc-service.scoped-model-alternative-e2e-test/completed-tool"
                       :reads [:request] :writes [:tool-receipt])
                     (orc/delegate "assessment" :target-sheet-id task
                       :reads fixture/task-reads :writes fixture/task-writes :timeout-ms 60000))))]
    {:task task :parent parent}))

(deftest public-model-fallback-http-checkpoint
  (let [server (fixture/start-server!) effects (atom {})]
    (try
      (llm/register-provider! fixture/provider {:provider :openrouter :model "fixture/primary"
        :config {:api-base (:url server) :api-key http/fixture-token :timeout 15000}})
      (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider fixture/provider
                                                 :fixture-effects effects}}]
        (let [package (package! ctx)
              a (future (fixture/execute-task ctx package "TASK-A" 2))
              b (future (fixture/execute-task ctx package "TASK-B" 2))
              runs (into [@a @b] (map #(fixture/execute-task ctx package % (if (= % "BUDGET") 1 2))
                                    ["AUTH" "QUOTA" "SCHEMA" "BUDGET"]))]
          (doseq [run (take 2 runs)]
            (is (= :success (get-in run [:result :status])))
            (is (false? (get-in run [:result :outputs :answer]))))
          (doseq [run (drop 2 runs)]
            (is (= :failure (get-in run [:result :status])))
            (is (= ["fixture/primary"]
                   (mapv :model (filter #(= (:item-id run) (get-in % [:inputs :request :item-id]))
                                        @(:requests server)))))
            (let [expected (case (:item-id run)
                             "AUTH" "LLM provider call failed [authentication-error] (HTTP 401)"
                             "QUOTA" "LLM provider call failed [quota-exceeded] (HTTP 429)"
                             "SCHEMA" "Strict response violates the output contract"
                             "BUDGET" "LLM call budget exceeded: 1/1")]
              (if (= "BUDGET" (:item-id run))
                (do
                  (is (= expected (get-in run [:result :error]))
                      "Family cancellation returns the exact budget reason")
                  (is (every? (fn [family]
                                (some #(and (= :sheet/tick-cancelled (:event/type %))
                                            (= expected (:reason %))) (:events family)))
                              (:families run))
                      "The same reason is durable for both cancelled ticks"))
                (is (some #(= expected (:error %))
                          (mapcat #(get-in % [:trace :node-traces]) (:families run)))
                    "The public trace keeps the exact candidate diagnosis"))))
          (is (= 7 (count @(:requests server))))
          (is (every? #(= 1 %) (vals @effects)))
          (reset! last-proof (http/readable-snapshot
             {:runs runs :requests @(:requests server) :effects @effects}))))
      (finally (.stop (:server server) 0) (.shutdownNow ^java.util.concurrent.ExecutorService (:executor server))))))

(require '[cheshire.core :as json]
         '[ai.obney.orc.orc-service.core.todo-processors :as processors])

(defn chain-server! []
  (let [requests (atom []) errors (atom []) counts (atom {})
        entered (java.util.concurrent.CountDownLatch. 1)
        release (java.util.concurrent.CountDownLatch. 1)
        expiry (atom nil)
        executor (java.util.concurrent.Executors/newFixedThreadPool 4)
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server executor)
    (.createContext server "/chat/completions"
      (reify com.sun.net.httpserver.HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  inputs (fixture/wire-inputs body) id (get-in inputs [:request :item-id])
                  model (:model body) ordinal (get (swap! counts update [id model] (fnil inc 0)) [id model])
                  success? (and (not= id "EXHAUST")
                                (or (= model "fixture/third")
                                    (and (= id "RETRY") (> ordinal 1))))]
              (swap! requests conj {:model model :inputs inputs :ordinal ordinal})
              (when (and (= id "REPLAY") (= model "fixture/second"))
                (.countDown entered)
                (.await release 20 java.util.concurrent.TimeUnit/SECONDS))
              (when (and (= id "DEADLINE") (= model "fixture/first"))
                (when-let [deadline @expiry]
                  (Thread/sleep (max 1 (+ 50 (- deadline (System/currentTimeMillis)))))))
              (if success?
                (http/reply! exchange 200 "application/json"
                  (json/generate-string {:id (str id "/" model) :model model
                    :choices [{:index 0 :finish_reason "stop"
                               :message {:role "assistant" :content
                                         (json/generate-string {:answer-reasoning "Fixture assessment for this item."
                                                               :item-id id :answer false})}}]
                    :usage {:prompt_tokens 10 :completion_tokens 10 :total_tokens 20}}))
                (http/reply! exchange 502 "text/html" "<html>fixture gateway unavailable</html>")))
            (catch Throwable t (swap! errors conj (.getName (class t))))
            (finally (.close exchange))))))
    (.start server)
    {:server server :executor executor :requests requests :errors errors
     :entered entered :release release :expiry expiry
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn chain-node [name retry]
  (orc/llm-fallback name
    :candidates [(cond-> {:model "fixture/first"} retry (assoc :retry retry))
                 {:model "fixture/second"} {:model "fixture/third"}]
    :instruction fixture/instruction :reads fixture/task-reads :writes fixture/task-writes
    :options fixture/generation-options))

(defn run-record [ctx sheet inputs budget]
  (let [tick-id (random-uuid)
        result (orc/execute ctx sheet inputs :tick-id tick-id :timeout-ms 60000 :max-ticks 1 :llm-call-budget budget)
        events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)
        _ (h/settle-until! #(some? (get-in (h/run-query ctx (h/make-get-trace-query tick-id)) [:query/result :trace])))
        trace (get-in (h/run-query ctx (h/make-get-trace-query tick-id)) [:query/result :trace])]
    {:tick-id tick-id :result result :events events :trace trace}))

(deftest ordered-candidates-and-mapped-ownership
  (let [server (chain-server!)]
    (try
      (llm/register-provider! :configured-chain {:provider :openrouter :model "fixture/default"
        :config {:api-base (:url server) :api-key http/fixture-token :timeout 15000}})
      (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider :configured-chain}}]
        (let [sheet (orc/build-workflow! ctx (orc/workflow "three-models" (orc/blackboard fixture/board)
                                             (chain-node "models" nil)))
              input (fn [id] {:request {:item-id id :question "Assess"}
                               :tool-receipt {:operation-id id :item-id id :ordinal 1}})
              success (run-record ctx sheet (input "CHAIN") 3)
              exhausted (run-record ctx sheet (input "EXHAUST") 3)
              retry-sheet (orc/build-workflow! ctx (orc/workflow "retry-models" (orc/blackboard fixture/board)
                                                    (chain-node "retry-models" {:max-attempts 2 :backoff-ms [0]})))
              retry-run (run-record ctx retry-sheet (input "RETRY") 3)
              mapped-board (assoc fixture/board :requests [:vector (:request fixture/board)]
                                  :answers [:vector [:map [:answer-reasoning :string] [:item-id :string] [:answer :boolean]]])
              mapped-sheet (orc/build-workflow! ctx
                             (orc/workflow "mapped-models" (orc/blackboard mapped-board)
                               (orc/map-each "items" :from :requests :as :request :into :answers :parallel 2
                                 (chain-node "mapped-models" nil))))
              mapped (run-record ctx mapped-sheet
                       {:requests [{:item-id "MAP-A" :question "First"} {:item-id "MAP-B" :question "Second"}]
                        :tool-receipt {:operation-id "shared-read" :item-id "shared" :ordinal 1}} 6)
              transitions #(filter (fn [e] (= :sheet/model-fallback-transition-recorded (:event/type e))) (:events %))
              models #(mapv :model (filter (fn [r] (= % (get-in r [:inputs :request :item-id]))) @(:requests server)))
              success-outcome (:model-fallback (first (filter :model-fallback (get-in success [:trace :node-traces]))))]
          (is (= :success (get-in success [:result :status])))
          (is (false? (get-in success [:result :outputs :answer])))
          (is (= ["fixture/first" "fixture/second" "fixture/third"] (models "CHAIN")))
          (is (= :selected (:reason success-outcome)))
          (is (= "fixture/third" (:requested-model success-outcome)))
          (is (= :failure (get-in exhausted [:result :status])))
          (is (= :candidates-exhausted (:reason (last (transitions exhausted)))))
          (is (= 3 (count (models "EXHAUST"))))
          (is (= :success (get-in retry-run [:result :status])))
          (is (= ["fixture/first" "fixture/first"] (models "RETRY")))
          (is (= :success (get-in mapped [:result :status])))
          (is (= #{"MAP-A" "MAP-B"} (set (map :item-id (get-in mapped [:result :outputs :answers])))))
          (is (= 2 (count (get-in mapped [:result :outputs :answers]))))
          (doseq [id ["MAP-A" "MAP-B"]]
            (is (= ["fixture/first" "fixture/second" "fixture/third"] (models id)))
            (is (= 1 (count (set (map :inputs (filter #(= id (get-in % [:inputs :request :item-id])) @(:requests server))))))))
          ;; Replay the exact already committed predecessor at the actual handler:
          ;; no returned event means no provider request can be admitted again.
          (let [event (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                          (= "fixture/first" (:requested-model %))) (:events success)))
                before (count @(:requests server))]
            (is (some? event))
            (is (nil? (processors/handle-child-completion (assoc ctx :event event))))
            (is (= before (count @(:requests server)))))
          (is (empty? @(:errors server)))
          (swap! last-proof assoc :chain {:success success :exhausted exhausted :retry retry-run :mapped mapped
                                         :requests @(:requests server)})))
      (finally (.stop (:server server) 0) (.shutdownNow ^java.util.concurrent.ExecutorService (:executor server))))))

(require '[ai.obney.grain.todo-processor-v2.core :as todo])

(deftest active-replay-and-expired-admission
  (let [server (chain-server!)]
    (try
      (llm/register-provider! :configured-limits {:provider :openrouter :model "fixture/default"
        :config {:api-base (:url server) :api-key http/fixture-token :timeout 15000}})
      (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider :configured-limits}}]
        (let [sheet (orc/build-workflow! ctx (orc/workflow "active-chain" (orc/blackboard fixture/board)
                                              (chain-node "models" nil)))
              input (fn [id] {:request {:item-id id :question "Assess"}
                               :tool-receipt {:operation-id id :item-id id :ordinal 1}})
              tick-id (random-uuid)
              running (future (orc/execute ctx sheet (input "REPLAY") :tick-id tick-id
                                           :timeout-ms 60000 :max-ticks 1 :llm-call-budget 3))]
          (try
            (is (.await ^java.util.concurrent.CountDownLatch (:entered server) 20 java.util.concurrent.TimeUnit/SECONDS))
            (let [events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)
                  event (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                            (= "fixture/first" (:requested-model %))) events))
                  before (count @(:requests server))
                  replay-context (assoc ctx :event event :processor-name :sheet/handle-child-completion
                                        :handler-fn processors/handle-child-completion)
                  a (future (todo/process-event replay-context))
                  b (future (todo/process-event replay-context))]
              (is (some? event))
              @a @b
              (is (= before (count @(:requests server))))
              (is (= 1 (count (filter #(= :sheet/model-fallback-transition-recorded (:event/type %))
                                      (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick-id))))))
            (finally (.countDown ^java.util.concurrent.CountDownLatch (:release server))))
          (is (= :success (:status @running)))
          (is (= 3 (count (filter #(= "REPLAY" (get-in % [:inputs :request :item-id])) @(:requests server)))))
          (let [expiry-sheet (orc/build-workflow! ctx
                               (orc/workflow "expiry-chain" (orc/blackboard fixture/board)
                                 (orc/llm-fallback "expiry-models"
                                   :candidates [{:model "fixture/first"} {:model "fixture/second"}]
                                   :policy :all-transport
                                   :instruction fixture/instruction :reads fixture/task-reads
                                   :writes fixture/task-writes :options fixture/generation-options)))
                expiry-tick (random-uuid) deadline (+ 6000 (System/currentTimeMillis))
                _ (reset! (:expiry server) deadline)
                command-result (h/run-command ctx
                  (assoc (h/make-tick-tree-command expiry-sheet :tick-id expiry-tick)
                         :inputs (input "DEADLINE")
                         :options {:timeout-ms 20000 :execution-deadline-ms deadline
                                   :max-ticks 1 :llm-call-budget 3 :store-trace? true}))
                settled? (h/settle-until! #(#{:success :failure :timeout :cancelled}
                                            (:root-status (orc/get-tick ctx expiry-tick))) :timeout-ms 25000)
                events (orc/value-log-read-tick-events ctx (:tenant-id ctx) expiry-tick)
                completions (filter #(= :sheet/node-execution-completed (:event/type %)) events)
                refused (first (filter #(= "Execution deadline exhausted before provider attempt" (:error %)) completions))
                _ (h/settle-until! #(some? (get-in (h/run-query ctx (h/make-get-trace-query expiry-tick)) [:query/result :trace])))
                trace (get-in (h/run-query ctx (h/make-get-trace-query expiry-tick)) [:query/result :trace])]

            (is (not (contains? command-result :cognitect.anomalies/category)))
            (is settled?)
            (is (= 1 (count (filter #(= "DEADLINE" (get-in % [:inputs :request :item-id])) @(:requests server)))))
            (is (some? refused) "The next candidate retains the expired absolute cap")
            (is (some #(= "Execution deadline exhausted before provider attempt" (:error %)) (:node-traces trace))
                "The real refusal is visible through the public trace query")
            (is (= deadline (get-in (orc/get-tick-execution-context ctx expiry-tick) [:options :execution-deadline-ms])))
            (swap! last-proof assoc :limits {:events events :requests @(:requests server) :deadline deadline}))))
      (finally (.countDown ^java.util.concurrent.CountDownLatch (:release server))
               (.stop (:server server) 0)
               (.shutdownNow ^java.util.concurrent.ExecutorService (:executor server))))))
