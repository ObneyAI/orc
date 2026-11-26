(ns ai.obney.orc.orc-service.configured-model-fallback-configuration-test
  "Public authoring and real command/projection round trips for MODEL-FALLBACK-1.
   No model transport is replaced or invoked by these configuration checks."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.scoped-model-alternative-e2e-test :as fixture]
            [ai.obney.orc.orc-service.ordinary-transport-failure-e2e-test :as http]
            [cheshire.core :as json]
            [ai.obney.grain.todo-processor-v2.interface :as tp])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch Executors ExecutorService TimeUnit]))

(def task
  {:model "fixture/primary" :fallback-models [{:model "fixture/backup"}]
   :instruction "Assess the supplied evidence for this exact request."
   :reads [:request :evidence]
   :writes [:assessment-reasoning :assessment]})

(def board
  {:request [:string {:description "The exact request owned by this task."}]
   :evidence [:string {:description "Evidence needed to assess the request."}]
   :assessment-reasoning [:string {:description "Concise basis for the boolean assessment."}]
   :assessment [:boolean {:description "Whether the request is supported; false is valid."}]})

(defn construct [options]
  (apply orc/llm "assess" (mapcat identity options)))

(defn rejected? [options]
  (try (construct options) false
       (catch clojure.lang.ExceptionInfo e
         (= :invalid-model-fallback (:type (ex-data e))))))

(deftest invalid-configurations-fail-before-execution
  (doseq [[label options]
          [["unordered candidates" (assoc task :fallback-models #{{:model "fixture/primary"}})]
           ["missing model" (assoc task :fallback-models [{}])]
           ["blank model" (assoc task :fallback-models [{:model "  "}])]
           ["non-string model" (assoc task :fallback-models [{:model :fixture/primary}])]
           ["unknown policy" (assoc task :fallback-policy :any-failure)]
           ["explicit nil policy" (assoc task :fallback-policy nil)]
           ["candidate changes instruction" (assoc task :fallback-models [{:model "fixture/primary" :instruction "Different task"}])]
           ["candidate changes reads" (assoc task :fallback-models [{:model "fixture/primary" :reads [:other]}])]
           ["candidate changes writes" (assoc task :fallback-models [{:model "fixture/primary" :writes [:other]}])]
           ["zero node attempts" (assoc task :fallback-models [{:model "fixture/primary" :retry {:max-attempts 0 :backoff-ms []}}])]
           ["negative backoff" (assoc task :fallback-models [{:model "fixture/primary" :retry {:max-attempts 2 :backoff-ms [-1]}}])]
           ["negative provider retries" (assoc task :options {:max-retries -1})]
           ["nil provider retries" (assoc task :options {:max-retries nil})]
           ["nil candidate retries" (assoc task :fallback-models [{:model "fixture/primary" :options {:max-retries nil}}])]
           ["empty retry delays" (assoc task :options {:max-retries 1 :retry-delay-ms []})]
           ["negative retry delay" (assoc task :options {:max-retries 1 :retry-delay-ms -1})]
           ["negative delay in sequence" (assoc task :options {:max-retries 1 :retry-delay-ms [0 -1]})]
           ["nil retry delay" (assoc task :options {:max-retries 1 :retry-delay-ms nil})]
           ["unknown task option" (assoc task :polciy :all-transport)]
           ["fractional candidate retries" (assoc task :fallback-models [{:model "fixture/primary" :options {:max-retries 0.5}}])]
]]
    (testing label (is (rejected? options)))))

(deftest candidates-cannot-replace-runtime-ownership-or-common-privacy
  (doseq [[k value] [[:model "fixture/injected"]
                     [:execution-deadline-ms Long/MAX_VALUE]
                     [:llm-call-budget 100000]
                     [:llm-budget-root-tick-id (random-uuid)]
                     [:tick-id (random-uuid)]
                     [:exec-context {:item-index 99}]
                     [:provider-reservation-context {:node-id (random-uuid)}]
                     [:node-attempt 99]
                     [:max-node-attempts 100000]
                     [:reserve-provider-attempt! (fn [_] true)]]]
    (testing (str "shared runtime option " k)
      (is (rejected? (assoc task :options {k value}))))
    (testing (str "candidate runtime option " k)
      (is (rejected? (assoc task :fallback-models [{:model "fixture/primary" :options {k value}}])))))
  (doseq [override [{:provider {:data_collection "allow"}}
                    {:store true}
                    {:output-mode :markers}
                    {:model "fixture/injected"}]]
    (is (rejected? (assoc task :options {:store false :provider {:data_collection "deny"}}
                         :fallback-models [{:model "fixture/backup" :options override}])))))

(defn task-view [node]
  (select-keys node [:provider :model :fallback-models :fallback-policy
                     :instruction :reads :writes :options]))

(deftest shared-task-and-generation-overrides-survive-public-roundtrips
  (h/with-test-context [ctx]
    (let [options (assoc task
                        :fallback-policy :all-transport
                        :options {:output-mode :json-schema :max-tokens 65536
                                  :store false :provider {:data_collection "deny"}}
                        :fallback-models [{:model "fixture/backup"
                                      :options {:max-tokens 32768 :temperature 0.2 :max-retries 1}}])
          authored (construct options)
          expected (task-view authored)
          definition (orc/workflow "model-fallback-config-source" (orc/blackboard board) authored)
          source-id (orc/build-workflow! ctx definition)
          exported (orc/export-sheet ctx source-id)
          _ (h/run-and-apply! ctx (h/make-publish-version-command source-id))
          version-before (orc/get-version ctx source-id 1)
          regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                        (eval (read-string (orc/export-to-dsl exported))))
          rebuilt-id (orc/build-workflow! ctx (assoc regenerated :workflow-name "model-fallback-config-rebuilt"))
          imported-id (orc/import-sheet ctx (assoc-in exported [:sheet :name] "model-fallback-config-imported"))]
      (is (= :all-transport (:fallback-policy expected)))
      (is (= "fixture/primary" (:model expected)))
      (is (= ["fixture/backup"] (mapv :model (:fallback-models expected))))
      (is (= (select-keys task [:instruction :reads :writes])
             (select-keys authored [:instruction :reads :writes])))
      (is (= false (get-in authored [:options :store])))
      (is (= {:data_collection "deny"} (get-in authored [:options :provider])))
      (is (= 32768 (get-in authored [:fallback-models 0 :options :max-tokens])))
      (doseq [[label node] [["EDN export" (:nodes exported)]
                            ["published snapshot" (get-in version-before [:snapshot :nodes])]
                            ["DSL evaluated" (:root-node regenerated)]
                            ["DSL rebuilt" (:nodes (orc/export-sheet ctx rebuilt-id))]
                            ["EDN imported" (:nodes (orc/export-sheet ctx imported-id))]]]
        (testing label (is (= expected (task-view node)))))
      (testing "a subsequent source definition does not mutate the published candidate snapshot"
        (orc/build-workflow! ctx
          (orc/workflow "model-fallback-config-source" (orc/blackboard board)
            (construct (assoc options :model "fixture/new-primary" :fallback-models []))))
        (is (= version-before (orc/get-version ctx source-id 1)))
        (is (= "fixture/new-primary"
               (get-in (orc/export-sheet ctx source-id) [:nodes :model])))
        (is (= [] (get-in (orc/export-sheet ctx source-id) [:nodes :fallback-models])))))))


(deftest raw-policy-bearing-definitions-cannot-bypass-task-validation
  (h/with-test-context [ctx]
    (doseq [[label node]
            [["unknown-policy" (assoc (construct task) :fallback-policy :any-failure)]
             ["different-inputs" (assoc-in (construct task) [:fallback-models 0 :reads] [:evidence])]
             ["different-privacy" (assoc-in (construct task) [:fallback-models 0 :options :store] true)]
             ["nil-retry" (assoc-in (construct task) [:options :max-retries] nil)]
             ["empty-delays" (assoc (construct task) :options {:max-retries 1 :retry-delay-ms []})]
             ["negative-delays" (assoc (construct task) :options {:max-retries 1 :retry-delay-ms -1})]]]
      (let [name (str "invalid-model-fallback-" label)
            error (try
                    (orc/build-workflow! ctx (orc/workflow name (orc/blackboard board) node))
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (testing label
          (is (= :invalid-model-fallback (:type error)))
          (is (nil? (orc/get-sheet-by-name ctx name)) "Rejected before creating a sheet"))))
    (let [source-id (orc/build-workflow! ctx
                      (orc/workflow "retry-import-source" (orc/blackboard board) (construct task)))
          exported (orc/export-sheet ctx source-id)]
      (doseq [[label overrides] [["nil" {:max-retries nil}]
                                 ["empty" {:max-retries 1 :retry-delay-ms []}]
                                 ["negative" {:max-retries 1 :retry-delay-ms -1}]]]
        (let [name (str "invalid-retry-import-" label)
              malformed (-> exported
                            (assoc-in [:sheet :name] name)
                            (assoc-in [:nodes :options] overrides))
              error (try (orc/import-sheet ctx malformed) nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= :invalid-model-fallback (:type error)))
          (is (nil? (orc/get-sheet-by-name ctx name)) "Import rejected before creating a sheet"))))))

(def last-overlap-proof (atom nil))

(defn start-version-server! []
  (let [requests (atom []) errors (atom [])
        old-arrived (CountDownLatch. 1)
        new-arrived (CountDownLatch. 1)
        executor (Executors/newFixedThreadPool 4)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server executor)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  inputs (fixture/wire-inputs body)
                  model (:model body)
                  primary? (contains? #{"fixture/old-primary" "fixture/new-primary"} model)
                  _ (when (= model "fixture/old-primary") (.countDown old-arrived))
                  _ (when (= model "fixture/new-primary") (.countDown new-arrived))
                  overlapped? (if (= model "fixture/old-primary")
                                (.await new-arrived 20 TimeUnit/SECONDS) true)
                  row {:body body :model model :inputs inputs :overlapped? overlapped?}]
              (swap! requests conj row)
              (if primary?
                (http/reply! exchange 502 "text/html" "<html>controlled gateway failure</html>")
                (http/reply! exchange 200 "application/json"
                  (json/generate-string
                    {:id (str "version/" model) :model model
                     :choices [{:index 0 :finish_reason "stop"
                                :message {:role "assistant"
                                          :content (json/generate-string
                                                     {:answer-reasoning "The fixture retained this task identity."
                                                      :item-id (get-in inputs [:request :item-id])
                                                      :answer false})}}]
                     :usage {:prompt_tokens 12 :completion_tokens 12 :total_tokens 24}}))))
            (catch Throwable e
              (swap! errors conj {:class (.getName (class e))})
              (try (http/reply! exchange 500 "text/plain" "fixture failed") (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :executor executor :requests requests :errors errors
     :old-arrived old-arrived :new-arrived new-arrived
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn version-workflow [prefix]
  (orc/workflow "model-fallback-overlap" (orc/blackboard fixture/board)
    (orc/sequence "task"
      (orc/code "completed-once"
        :fn "ai.obney.orc.orc-service.scoped-model-alternative-e2e-test/completed-tool"
        :reads [:request] :writes [:tool-receipt])
      (orc/llm "assess"
        :model (str "fixture/" prefix "-primary")
        :fallback-models [{:model (str "fixture/" prefix "-backup")}]
        :instruction fixture/instruction :reads fixture/task-reads :writes fixture/task-writes
        :options fixture/generation-options))))

(deftest overlapping-executions-keep-their-published-candidate-configuration
  (let [server (start-version-server!) effects (atom {})
        provider :configured-model-version-overlap]
    (try
      (llm/register-provider! provider
        {:provider :openrouter :model "fixture/unused-default"
         :config {:api-base (:url server) :api-key http/fixture-token :timeout 30000}})
      (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider provider
                                               :fixture-effects effects}}]
        (let [sheet-id (orc/build-workflow! ctx (version-workflow "old"))
              _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))
              old-id (random-uuid) new-id (random-uuid)
              execute (fn [id item version]
                        (orc/execute ctx sheet-id {:request {:item-id item :question "Assess this item"}}
                          :tick-id id :use-version version :llm-call-budget 2
                          :timeout-ms 60000 :max-ticks 1))
              old-run (future (execute old-id "OLD" 1))
              old-started? (.await ^CountDownLatch (:old-arrived server) 20 TimeUnit/SECONDS)]
          (is old-started? "The original execution is actually in flight before rebuilding")
          (orc/build-workflow! ctx (version-workflow "new"))
          (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))
          (let [new-run (future (execute new-id "NEW" 2))
                old-result (deref old-run 65000 ::timed-out)
                new-result (deref new-run 65000 ::timed-out)
                old-context (orc/get-tick-execution-context ctx old-id)
                new-context (orc/get-tick-execution-context ctx new-id)
                requested (fn [item]
                            (mapv :model (filter #(= item (get-in % [:inputs :request :item-id])) @(:requests server))))
                frozen-models (fn [context]
                                (set (mapcat (fn [node] (keep identity (cons (:model node) (map :model (:fallback-models node)))))
                                             (vals (:nodes-by-id context)))))
                events (fn [id] (orc/value-log-read-tick-events ctx (:tenant-id ctx) id))]
            (doseq [[result item] [[old-result "OLD"] [new-result "NEW"]]]
              (is (= :success (:status result)))
              (is (false? (get-in result [:outputs :answer])))
              (is (= item (get-in result [:outputs :item-id]))))
            (is (= 1 (:executed-version old-result)))
            (is (= 2 (:executed-version new-result)))
            (is (= ["fixture/old-primary" "fixture/old-backup"] (requested "OLD")))
            (is (= ["fixture/new-primary" "fixture/new-backup"] (requested "NEW")))
            (is (= #{"fixture/old-primary" "fixture/old-backup"} (frozen-models old-context)))
            (is (= #{"fixture/new-primary" "fixture/new-backup"} (frozen-models new-context)))
            (is (every? :overlapped? @(:requests server)))
            (is (= 4 (count @(:requests server))))
            (is (= {"OLD" 1 "NEW" 1} @effects))
            (is (empty? @(:errors server)))
            (doseq [item ["OLD" "NEW"]]
              (is (apply = (map :inputs (filter #(= item (get-in % [:inputs :request :item-id]))
                                               @(:requests server))))))
            (reset! last-overlap-proof
              {:old {:result old-result :context old-context :events (events old-id)}
               :new {:result new-result :context new-context :events (events new-id)}
               :requests @(:requests server) :effects @effects :server-errors @(:errors server)}))))
      (finally
        (.countDown ^CountDownLatch (:new-arrived server))
        (http/stop-server! server)))))


(def last-tenant-proof (atom nil))

(deftest shared-datastore-tenants-advance-only-their-own-model-task
  (let [server (fixture/start-server!) effects (atom {})
        provider :configured-model-tenant-isolation
        tenant-a (random-uuid) tenant-b (random-uuid)
        initial (h/create-async-test-context
                  {:context {:llm-provider provider :fixture-effects effects}})]
    ;; Replace the pubsub test workers before creating any task. One production
    ;; tenant poller owns both tenants, sharing this real store and LMDB cache.
    (h/stop-test-processors! initial)
    (let [base (assoc initial :processors {})
          a (assoc base :tenant-id tenant-a) b (assoc base :tenant-id tenant-b)
          poller (atom nil)]
      (try
        (llm/register-provider! provider
          {:provider :openrouter :model "fixture/unused-default"
           :config {:api-base (:url server) :api-key http/fixture-token :timeout 15000}})
        (let [definition (orc/workflow "tenant-model-fallback" (orc/blackboard fixture/board)
                           (orc/sequence "task"
                             (orc/code "completed-once"
                               :fn "ai.obney.orc.orc-service.scoped-model-alternative-e2e-test/completed-tool"
                               :reads [:request] :writes [:tool-receipt])
                             (orc/llm "models"
                               :model "fixture/primary" :fallback-models [{:model "fixture/alternative"}]
                               :instruction fixture/instruction :reads fixture/task-reads :writes fixture/task-writes
                               :options fixture/generation-options)))
              sheet-a (orc/build-workflow! a definition)
              sheet-b (orc/build-workflow! b definition)
              tick-a (random-uuid) tick-b (random-uuid)
              execute (fn [ctx sheet tick item]
                        (orc/execute ctx sheet {:request {:item-id item :question "Assess only this tenant's item"}}
                          :tick-id tick :timeout-ms 60000 :max-ticks 1 :llm-call-budget 2))]
          (reset! poller (tp/start-tenant-poller
                          {:event-store (:event-store base) :tenant-ids #{tenant-a tenant-b}
                           :context base :poll-interval-ms 10}))
          (is (= sheet-a sheet-b) "Deterministic sheet names collide intentionally across tenants")
          (is (= (set (keys (orc/get-nodes-by-id a sheet-a)))
                 (set (keys (orc/get-nodes-by-id b sheet-b))))
              "The isolation proof uses the same node identities, not separate graph names")
          (let [run-a (future (execute a sheet-a tick-a "TASK-A"))
                run-b (future (execute b sheet-b tick-b "TASK-B"))
                result-a (deref run-a 65000 ::timed-out)
                result-b (deref run-b 65000 ::timed-out)
                events-a (orc/value-log-read-tick-events a tenant-a tick-a)
                events-b (orc/value-log-read-tick-events b tenant-b tick-b)
                candidate-ids (set (map :id (filter #(= :ai (:executor %))
                                                    (vals (orc/get-nodes-by-id a sheet-a)))))
                models (fn [events]
                         (mapv #(get-in % [:entry :requested-model])
                           (filter #(and (= :sheet/model-candidate-recorded (:event/type %))
                                         (contains? candidate-ids (:node-id %))) events)))
                requests-a (filter #(= "TASK-A" (get-in % [:inputs :request :item-id])) @(:requests server))
                requests-b (filter #(= "TASK-B" (get-in % [:inputs :request :item-id])) @(:requests server))]
            (doseq [[result item] [[result-a "TASK-A"] [result-b "TASK-B"]]]
              (is (= :success (:status result)))
              (is (= item (get-in result [:outputs :item-id])))
              (is (false? (get-in result [:outputs :answer]))))
            (is (= ["fixture/primary" "fixture/alternative"] (mapv :model requests-a)))
            (is (= ["fixture/primary"] (mapv :model requests-b)))
            (is (= 3 (count @(:requests server))))
            (is (= ["fixture/primary" "fixture/alternative"] (models events-a)))
            (is (= ["fixture/primary"] (models events-b)))
            (is (every? :both-primary-requests-present?
                        (filter #(= "fixture/primary" (:model %)) @(:requests server))))
            (is (seq events-a)) (is (seq events-b))
            (is (empty? (orc/value-log-read-tick-events b tenant-b tick-a)))
            (is (empty? (orc/value-log-read-tick-events a tenant-a tick-b)))
            (is (nil? (orc/get-tick b tick-a)))
            (is (nil? (orc/get-tick a tick-b)))
            (is (= {"TASK-A" 1 "TASK-B" 1} @effects))
            (is (apply = (map :inputs requests-a)))
            (is (empty? @(:errors server)))
            (reset! last-tenant-proof
              {:tenant-a {:tenant-id tenant-a :tick-id tick-a :result result-a :events events-a}
               :tenant-b {:tenant-id tenant-b :tick-id tick-b :result result-b :events events-b}
               :requests @(:requests server) :effects @effects :server-errors @(:errors server)})))
        (finally
          (when @poller (tp/stop-tenant-poller @poller))
          (h/stop-async-context base)
          (http/stop-server! server))))))

(deftest every-model-node-persists-routing-and-explicit-empty-overrides
  (h/with-test-context [ctx]
    (doseq [[kind constructor task-options]
            [["llm" orc/llm {:writes [:assessment-reasoning :assessment]}]
             ["decision" orc/llm-decision {:writes [:assessment]}]
             ["condition" orc/llm-condition {}]
             ["researcher" orc/repl-researcher
              {:writes [:assessment]
               :rlm {:recursive? true :checkpointed? true
                     :sub-model "fixture/sub-primary" :sub-provider :sub-provider
                     :sub-options {:max-tokens 32768}
                     :sub-fallback-models [] :sub-fallback-policy :all-transport}}]]]
      (let [opts (merge {:provider :primary-provider :model "fixture/primary"
                         :fallback-models [] :fallback-policy :temporary-transport
                         :options {:max-tokens 65536}
                         :instruction "Assess this evidence." :reads [:request :evidence]}
                        task-options)
            node (apply constructor "assess" (mapcat identity opts))
            id (orc/build-workflow! ctx (orc/workflow (str "model-config-" kind)
                                         (orc/blackboard board) node))
            exported (orc/export-sheet ctx id)
            _ (h/run-and-apply! ctx (h/make-publish-version-command id))
            version (orc/get-version ctx id 1)
            regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.interface)]
                          (eval (read-string (orc/export-to-dsl exported))))]
        (doseq [actual [(:nodes exported) (get-in version [:snapshot :nodes])
                       (:root-node regenerated)]]
          (testing kind
            (is (= (select-keys node [:provider :model :fallback-models :fallback-policy :options :rlm])
                   (select-keys actual [:provider :model :fallback-models :fallback-policy :options :rlm])))))))))
