(ns ai.obney.orc.orc-service.retained-prime-model-plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.researcher-models :as models]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]
            [litellm.router :as router])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent TimeUnit]))

(def proof (atom nil))
(def proofs (atom []))
(def code "(final! {:summary \"original-source\"})")
(def reasoning "Return the controlled original source summary.")
(defn prefix! [{:keys [probe-effects]}]
  (swap! probe-effects inc)
  {:source "original-source"})

(defn response-for [body]
  ;; Respond in the ACTUAL requested protocol. This fixture does not repair,
  ;; normalize or change the generated Clojure source to accommodate parsing.
  (let [tools? (seq (:tools body))]
    {:id "controlled-prime-backup-response" :model "fixture/prime-backup-resolved"
     :choices [{:index 0 :finish_reason (if tools? "tool_calls" "stop")
                :message (if tools?
                           {:role "assistant" :content nil
                            :tool_calls [{:id "controlled-prime-call" :type "function"
                                          :function {:name "submit_response"
                                                     :arguments (json/generate-string {:reasoning reasoning :code code})}}]}
                           {:role "assistant"
                            :content (str "[[ ## reasoning ## ]]\n" reasoning
                                          "\n[[ ## code ## ]]\n" code
                                          "\n[[ ## completed ## ]]")})}]
     :usage {:prompt_tokens 8 :completion_tokens 5 :total_tokens 13}}))

(defn server!
  ([] (server! response-for))
  ([respond]
  (let [requests (atom []) responses (atom []) errors (atom [])
        pool (java.util.concurrent.Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [raw (slurp (.getRequestBody exchange))
                  body (json/parse-string raw true)
                  index (count (swap! requests conj {:raw raw :body body}))
                  primary? (= "fixture/prime" (:model body))
                  status (if primary? 503 200)
                  response (if primary?
                             {:error {:message "controlled temporary unavailable" :code 503}}
                             (respond body))
                  serialized (json/generate-string response)
                  bytes (.getBytes serialized "UTF-8")]
              (swap! responses conj {:request-index index :status status :raw serialized :body response})
              (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.sendResponseHeaders exchange status (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable e (swap! errors conj {:class (.getName (class e))}))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :requests requests :responses responses :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))})))

(deftest checkpointed-prime-preserves-inherited-paired-function-contract
  (let [out (or (System/getProperty "orc.probe.out")
                (str (java.nio.file.Files/createTempDirectory "retained-prime-" (make-array java.nio.file.attribute.FileAttribute 0))))
        _ (assert out "Set orc.probe.out before loading/running this staged probe")
        db (io/file out "events.sqlite")
        _ (assert (not (.exists db)) "Fresh SQLite database required; never overwrite an earlier run")
        fixture (server!) effects (atom 0)
        primary (keyword (str "prime-profile-primary-" (random-uuid)))
        backup (keyword (str "prime-profile-backup-" (random-uuid)))
        tenant (random-uuid) tick-id (random-uuid)]
    (try
      (let [config {:provider :openrouter
                    :config {:api-key "CONTROLLED-NOT-A-REAL-KEY" :api-base (:url fixture) :timeout 5000}}]
        (llm/register-provider! primary (assoc config :model "fixture/prime" :output-mode :function-calling))
        (llm/register-provider! backup (assoc config :model "fixture/prime-backup")))
      ;; The provider alias is present before context creation: real processors
      ;; and the tenant poller capture this same base context, not a late assoc.
      (h/with-async-test-context [ctx {:event-store-conn {:type :sqlite :database-file (str db) :maximum-pool-size 2}
                                     :context {:tenant-id tenant :llm-provider primary :probe-effects effects}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow "checkpointed-prime-inherited-profile-repro"
                        (orc/blackboard {:source [:string {:description "Original source label supplied once by the completed prefix."}]
                                         :summary [:string {:description "Original source label returned as the task summary."}]})
                        (orc/sequence "task"
                          (orc/code "prefix" :fn "ai.obney.orc.orc-service.retained-prime-model-plan-test/prefix!" :reads [] :writes [:source])
                          (orc/repl-researcher "research"
                            :model "fixture/prime"
                            :fallback-models [{:provider backup :model "fixture/prime-backup"}]
                            :instruction "Return the original source label as the summary using final!."
                            :reads [:source] :writes [:summary]
                            :max-iterations 1
                            :options {:max-retries 0 :max-tokens 65536}
                            :rlm {:recursive? true :checkpointed? true
                                  :timeouts {:provider-ms 5000 :iteration-ms 20000 :campaign-ms 30000}}))))
              result (try (orc/execute ctx sheet {} :tick-id tick-id :timeout-ms 30000
                                      :max-ticks 1 :llm-call-budget 2)
                          (catch Throwable e {:probe/exception-class (.getName (class e))}))
              events (orc/value-log-read-tick-events ctx tenant tick-id)
              nodes (orc/get-nodes-for-sheet ctx sheet)
              researcher-id (:id (first (filter #(= "research" (:name %)) nodes)))
              prefix-id (:id (first (filter #(= "prefix" (:name %)) nodes)))
              completions (filter #(= :sheet/node-execution-completed (:event/type %)) events)
              completion (first (filter #(= researcher-id (:node-id %)) completions))
              prefix-completions (filter #(= prefix-id (:node-id %)) completions)
              writes (when completion (orc/value-log-resolve-writes ctx tenant tick-id completion))
              prefix-writes (mapv #(orc/value-log-resolve-writes ctx tenant tick-id %) prefix-completions)
              bodies (mapv :body @(:requests fixture))]
          (reset! proof {:database (str db) :tenant tenant :sheet-id sheet :tick-id tick-id
                         :provider-aliases {:primary primary :backup backup}
                         :authored {:options {:max-retries 0 :max-tokens 65536} :output-mode-explicit? false
                                    :primary-provider-output-mode :function-calling :backup-provider-output-mode nil}
                         :requests @(:requests fixture) :responses @(:responses fixture)
                         :fixture-errors @(:errors fixture) :result result :tick (orc/get-tick ctx tick-id)
                         :events events :nodes nodes :researcher-completion completion
                         :canonical-writes writes :prefix-completions (vec prefix-completions)
                         :prefix-writes prefix-writes :prefix-effects @effects
                         :budget-reservations (vec (filter #(= :sheet/provider-call-reserved (:event/type %)) events))})
          (testing "Actual HTTP request mode is the primary regression oracle"
            (is (empty? @(:errors fixture)))
            (is (= ["fixture/prime" "fixture/prime-backup"] (mapv :model bodies)))
            (is (= 2 (count bodies)))
            (doseq [body bodies]
              (is (= "auto" (:tool_choice body)))
              (is (= "submit_response" (get-in body [:tools 0 :function :name])))
              (is (true? (get-in body [:tools 0 :function :strict])))
              (is (= #{"reasoning" "code"} (set (get-in body [:tools 0 :function :parameters :required]))))
              (is (= "string" (get-in body [:tools 0 :function :parameters :properties :code :type])))
              (is (= "Clojure code to execute" (get-in body [:tools 0 :function :parameters :properties :code :description])))
              (is (not (contains? body :response_format)))
              (is (not (str/includes? (pr-str (:messages body)) "[[ ##"))))
            (is (= (get-in (first bodies) [:tools 0 :function :parameters])
                   (get-in (second bodies) [:tools 0 :function :parameters]))))
          (testing "Task result, canonical values, prefix, and budget remain separate observations"
            (is (= :success (:status result)) (pr-str result))
            (is (= :success (:status completion)) (pr-str completion))
            (is (= "original-source" (get-in result [:outputs :summary])))
            ;; Reserved researcher diagnostics are intentionally attached by
            ;; todo-processors/effective-outputs. Assert them explicitly while
            ;; keeping the declared task value and full key set exact.
            (is (= {:summary "original-source"} (select-keys writes [:summary])))
            (is (= #{:summary :iteration-reasonings :iterations} (set (keys writes))))
            (is (= [reasoning] (:iteration-reasonings writes)))
            (is (= [code] (mapv :code (:iterations writes))))
            (is (= 1 @effects))
            (is (= [{:source "original-source"}] prefix-writes))
            (is (= 2 (count (:budget-reservations @proof))))
            (let [physical (last (filter #(and (= :sheet/model-request-recorded (:event/type %))
                                                (= :completed (get-in % [:request :phase]))) events))
                  prediction (get-in physical [:request :outcome :result])]
              (is (= {:reasoning reasoning :code code} (:outputs prediction)))
              (is (= {:prompt-tokens 8 :completion-tokens 5 :total-tokens 13} (:usage prediction)))
              (is (= "fixture/prime-backup-resolved" (:model prediction)))
              (is (= {:provider (name backup) :model "fixture/prime-backup-resolved"
                      :usage {:prompt-tokens 8 :completion-tokens 5 :total-tokens 13}}
                     (select-keys (:provider-observations prediction) [:provider :model :usage])))))))
      (finally
        (.stop (:server fixture) 0)
        (.shutdownNow (:pool fixture))
        (when-not (.awaitTermination (:pool fixture) 5 TimeUnit/SECONDS)
          (throw (ex-info "Owned HTTP fixture executor did not terminate" {})))))))


(defn command [name data]
  (merge {:command/name name :command/id (random-uuid) :command/timestamp (time/now)} data))

(defn fresh-directory []
  (str (java.nio.file.Files/createTempDirectory "retained-prime-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn register-fixture! [fixture primary backup]
  (let [config {:provider :openrouter
                :config {:api-key "CONTROLLED-NOT-A-REAL-KEY" :api-base (:url fixture) :timeout 5000}}]
    (llm/register-provider! primary (assoc config :model "fixture/prime" :output-mode :function-calling))
    (llm/register-provider! backup (assoc config :model "fixture/prime-backup"))
    config))

(defn workflow [backup rlm]
  (orc/workflow (str "retained-prime-" (random-uuid))
    (orc/blackboard {:source [:string {:description "Original source label supplied once by the completed prefix."}]
                     :summary [:string {:description "Original source label returned as the task summary."}]})
    (orc/sequence "task"
      (orc/code "prefix" :fn "ai.obney.orc.orc-service.retained-prime-model-plan-test/prefix!"
        :reads [] :writes [:source])
      (orc/repl-researcher "research" :model "fixture/prime"
        :fallback-models [{:provider backup :model "fixture/prime-backup"}]
        :instruction "Return the original source label as the summary using final!."
        :reads [:source] :writes [:summary] :max-iterations 3
        :options {:max-retries 0 :max-tokens 65536}
        :rlm (merge {:recursive? true :checkpointed? true
                     :timeouts {:provider-ms 5000 :iteration-ms 20000 :campaign-ms 120000}} rlm)))))

(defn stop-fixture! [fixture]
  (.stop (:server fixture) 0)
  (.shutdownNow (:pool fixture))
  (is (.awaitTermination (:pool fixture) 5 TimeUnit/SECONDS)))

(deftest prime-role-plan-and-backup-selection-survive-saved-quantum-and-registry-drift
  (let [responses (atom 0)
        first-code "(store! :retained-turn-value \"first-turn-value\")"
        second-code "(final! {:summary (str (get-var :retained-turn-value) \"/second-turn-value\")})"
        fixture (server! (fn [body]
                           (let [turn (swap! responses inc)]
                             (binding []
                               (with-redefs [code (if (= 1 turn) first-code second-code)]
                                 (response-for body))))))
        primary (keyword (str "prime-role-primary-" (random-uuid)))
        backup (keyword (str "prime-role-backup-" (random-uuid)))
        config (register-fixture! fixture primary backup)
        tenant (random-uuid) tick (random-uuid) prefix-effects (atom 0)
        store {:type :sqlite :database-file (str (fresh-directory) "/events.sqlite") :maximum-pool-size 2}
        common {:tenant-id tenant :llm-provider primary :probe-effects prefix-effects}
        first-context (atom nil) second-context (atom nil)
        held (promise) starts (atom 0) real-handler processors/execute-repl-researcher-node]
    (try
      (let [ctx (h/create-async-test-context {:context common :event-store-conn store})
            _ (reset! first-context ctx)
            sheet (orc/build-workflow! ctx (workflow backup {:quantum {:max-iterations 1}}))]
        (with-redefs [processors/execute-repl-researcher-node
                      (fn [context]
                        (if (= 2 (swap! starts inc)) (deliver held true) (real-handler context)))]
          (h/run-and-apply! ctx (command :sheet/tick-tree
                                {:sheet-id sheet :tick-id tick :inputs {}
                                 :options {:timeout-ms 120000 :llm-call-budget 3}}))
          (is (= true (deref held 20000 :timeout)))
          (is (= 1 @prefix-effects))
          (is (= 2 (count @(:requests fixture))))
          (h/stop-async-context ctx)
          (reset! first-context nil))
        ;; Change both provider defaults and the contextual default alias. The
        ;; retained first-turn route/profile is still the role's actual plan.
        (llm/register-provider! primary (assoc config :model "fixture/drifted-primary" :output-mode :json-schema))
        (llm/register-provider! backup (assoc config :model "fixture/drifted-backup" :output-mode :json-schema))
        (let [ctx (h/create-async-test-context
                   {:context (assoc common :llm-provider :unregistered-new-default
                                           :orc/instance-id (random-uuid)
                                           :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600))
                    :event-store-conn store})
              _ (reset! second-context ctx)
              resumed (orc/resume-in-progress! ctx)
              _ (is (some :resumed? resumed))
              _ (is (h/settle-until! #(some? (runtime/durable-terminal-result ctx tick)) :timeout-ms 20000))
              result (runtime/durable-terminal-result ctx tick)
              events (orc/value-log-read-tick-events ctx tenant tick)
              plans (filter #(= :sheet/model-plan-retained (:event/type %)) events)
              requests (filter #(and (= :sheet/model-request-recorded (:event/type %))
                                     (= :completed (get-in % [:request :phase]))) events)
              bodies (mapv :body @(:requests fixture))
              completion (last (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                             (= "research" (:name %))) events))]
          (is (= :success (:status result)) (pr-str result))
          (is (= "first-turn-value/second-turn-value" (get-in result [:outputs :summary])))
          (is (= ["fixture/prime" "fixture/prime-backup" "fixture/prime-backup"] (mapv :model bodies)))
          (is (every? #(and (= "auto" (:tool_choice %))
                           (true? (get-in % [:tools 0 :function :strict]))) bodies))
          (is (= 1 (count plans)))
          (is (= 1 (count (set (map :configuration-id requests)))))
          (is (= 2 (count (set (map :logical-action-id requests)))))
          (is (= 3 (count (set (map #(get-in % [:request :request-id]) requests)))))
          (is (= [first-code second-code]
                 (mapv #(get-in % [:request :outcome :result :outputs :code]) (filter #(= 1 (get-in % [:request :candidate-index])) requests))))
          (is (= 3 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))
          (is (= 1 @prefix-effects))
          (is (empty? @(:errors fixture)))
          (swap! proofs conj {:kind :saved-role-plan :resumed resumed :events events :result result
                              :requests @(:requests fixture) :prefix-effects @prefix-effects})))
      (finally
        (when-let [ctx @first-context] (h/stop-async-context ctx))
        (when-let [ctx @second-context] (h/stop-async-context ctx))
        (stop-fixture! fixture)))))

(defn checkpoint-cutpoint-case! [cutpoint]
  (let [fixture (server!)
        primary (keyword (str "prime-recover-primary-" (random-uuid)))
        backup (keyword (str "prime-recover-backup-" (random-uuid)))
        _ (register-fixture! fixture primary backup)
        tenant (random-uuid) tick (random-uuid) prefix-effects (atom 0)
        store {:type :sqlite :database-file (str (fresh-directory) "/events.sqlite") :maximum-pool-size 2}
        common {:tenant-id tenant :llm-provider primary :probe-effects prefix-effects}
        first-context (atom nil) second-context (atom nil) target (atom nil)
        held (promise) actual-command cp/process-command saved (atom nil)]
    (try
      (let [ctx (h/create-async-test-context {:context common :event-store-conn store})
            _ (reset! first-context ctx)
            sheet (orc/build-workflow! ctx (workflow backup {}))
            node (:id (first (filter #(= "research" (:name %)) (orc/get-nodes-for-sheet ctx sheet))))]
        (reset! target node)
        ;; Withhold only writes AFTER the measured cutpoint. The real provider,
        ;; SQLite requests, prefix and guarded effect execute normally. This is
        ;; a close/reopen contract; it makes no SIGKILL or fresh-JVM claim.
        (with-redefs [cp/process-command
                      (fn [context]
                        (let [c (:command context) name (:command/name c)
                              same? (= @target (:node-id c))]
                          (cond
                            (and same? (= :sheet/complete-node-execution name))
                            (do (deliver held true) {})
                            (and same? (#{:sheet/checkpoint-researcher-iteration :sheet/record-researcher-action} name)) {}
                            (and same? (= :physical-response cutpoint)
                                 (#{:sheet/record-model-candidate :sheet/select-model-candidate
                                    :sheet/complete-researcher-effect} name)
                                 (= 1 (or (get-in c [:entry :candidate-index]) (:candidate-index c)
                                          ;; The effect completion is unconditionally withheld.
                                          1))) {}
                            :else (actual-command context))))]
          (h/run-and-apply! ctx (command :sheet/tick-tree
                                {:sheet-id sheet :tick-id tick :inputs {}
                                 :options {:timeout-ms 120000 :llm-call-budget 2}}))
          (is (= true (deref held 15000 :timeout)))
          (h/stop-async-context ctx)
          (reset! first-context nil))
        ;; Reopen once to inspect only committed facts before recovery. The
        ;; before projection, not the injected handler, establishes the cutpoint.
        (h/with-async-test-context [inspect {:context common :event-store-conn store}]
          (let [before (orc/value-log-read-tick-events inspect tenant tick)
                physical (filter #(and (= :sheet/model-request-recorded (:event/type %))
                                        (= :completed (get-in % [:request :phase]))
                                        (= 1 (get-in % [:request :candidate-index]))) before)
                logical (:logical-action-id (first physical))
                effect-completions (filter #(and (= :rlm/researcher-effect-completed (:event/type %))
                                                  (= logical (:logical-action-identity %))) before)]
            (is (= 1 (count physical)))
            (is (= {:code code :reasoning reasoning} (get-in (first physical) [:request :outcome :result :outputs])))
            (is (= {:prompt-tokens 8 :completion-tokens 5 :total-tokens 13}
                   (get-in (first physical) [:request :outcome :result :usage])))
            (is (= "fixture/prime-backup-resolved" (get-in (first physical) [:request :outcome :result :model])))
            (is (= {:provider (name backup) :model "fixture/prime-backup-resolved"
                    :usage {:prompt-tokens 8 :completion-tokens 5 :total-tokens 13}}
                   (select-keys (get-in (first physical) [:request :outcome :result :provider-observations])
                                [:provider :model :usage])))
            (is (= (if (= :physical-response cutpoint) 0 1) (count effect-completions)))
            (is (not-any? #(and (= :sheet/node-execution-completed (:event/type %)) (= node (:node-id %))) before))
            (is (not-any? #(= :rlm/researcher-action-completed (:event/type %)) before))
            (is (not-any? #(= :rlm/researcher-checkpointed (:event/type %)) before))
            (reset! saved {:before before :logical logical :request-id (get-in (first physical) [:request :request-id])})
            (when (= :effect-completed cutpoint)
              ;; A stale explicit legacy mirror cannot replace the canonical
              ;; guarded effect that owns this actual prime request.
              (is (nil? (:cognitect.anomalies/category
                         (h/run-and-apply! inspect
                           (command :sheet/record-researcher-action
                             {:sheet-id sheet :tick-id tick :node-id node
                              :action-id (str tick "/" node "/0/provider/1") :action-kind :provider :iteration 0
                              :result {:provider-result {:outputs {:reasoning "stale mirror"
                                                                  :code "(final! {:summary \"stale-mirror\"})"}}}
                              :completed-at (str (java.time.Instant/now))}))))))))
        (router/unregister! primary)
        (router/unregister! backup)
        (let [ctx (h/create-async-test-context
                   {:context (assoc common :orc/instance-id (random-uuid)
                                           :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600))
                    :event-store-conn store})
              _ (reset! second-context ctx)
              resumed (orc/resume-in-progress! ctx)
              _ (is (some :resumed? resumed))
              _ (is (h/settle-until! #(some? (runtime/durable-terminal-result ctx tick)) :timeout-ms 15000))
              result (runtime/durable-terminal-result ctx tick)
              events (orc/value-log-read-tick-events ctx tenant tick)
              completion (last (filter #(and (= :sheet/node-execution-completed (:event/type %)) (= node (:node-id %))) events))
              writes (when completion (orc/value-log-resolve-writes ctx tenant tick completion))
              after-physical (filter #(and (= :sheet/model-request-recorded (:event/type %))
                                            (= :completed (get-in % [:request :phase]))
                                            (= 1 (get-in % [:request :candidate-index]))) events)
              effects (filter #(and (= :rlm/researcher-effect-completed (:event/type %))
                                    (= (:logical @saved) (:logical-action-identity %))) events)]
          (is (= :success (:status result)) (pr-str result))
          (is (= {:summary "original-source"} (select-keys writes [:summary])))
          (is (= #{:summary :iteration-reasonings :iterations} (set (keys writes))))
          (is (= [reasoning] (:iteration-reasonings writes)))
          (is (= [code] (mapv :code (:iterations writes))))
          (is (= 2 (count @(:requests fixture))))
          (is (= 2 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))
          (is (= 1 @prefix-effects))
          (is (= 1 (count after-physical)))
          (is (= (:request-id @saved) (get-in (first after-physical) [:request :request-id])))
          (is (= 1 (count effects)))
          (when (= :physical-response cutpoint)
            (is (= 2 (:ownership-epoch (first effects))))
            (is (some #(and (= :rlm/researcher-effect-indeterminate (:event/type %))
                             (= (:logical @saved) (:logical-action-identity %))) events)))
          ;; Original asynchronous trace completion is independent of replay.
          ;; Let it land before measuring whether another recovery mutates facts.
          (is (h/settle-until! #(some (fn [event] (= :sheet/execution-traced (:event/type event)))
                                     (orc/value-log-read-tick-events ctx tenant tick)) :timeout-ms 5000))
          (let [count-before (count (orc/value-log-read-tick-events ctx tenant tick))]
            (is (not-any? :resumed? (orc/resume-in-progress! ctx)))
            (is (= count-before (count (orc/value-log-read-tick-events ctx tenant tick)))))
          (swap! proofs conj {:kind cutpoint :saved @saved :resumed resumed :events events
                              :result result :writes writes :requests @(:requests fixture)
                              :prefix-effects @prefix-effects})))
      (finally
        (when-let [ctx @first-context] (h/stop-async-context ctx))
        (when-let [ctx @second-context] (h/stop-async-context ctx))
        (stop-fixture! fixture)))))

(deftest completed-prime-effect-replays-before-registry-lookup
  (checkpoint-cutpoint-case! :effect-completed))

(deftest completed-prime-physical-response-reconciles-original-effect-under-new-owner
  (checkpoint-cutpoint-case! :physical-response))

(defn historical-prime-case! [mode]
  (let [tenant (random-uuid) tick (random-uuid) prefix-effects (atom 0)
        primary (keyword (str "prime-legacy-primary-" (random-uuid)))
        backup (keyword (str "prime-legacy-backup-" (random-uuid)))
        candidates [{:provider primary :model "fixture/prime" :options {:max-retries 0}}
                    {:provider backup :model "fixture/prime-backup" :options {:max-retries 0}}]
        config-id (journal/configuration-id candidates :temporary-transport)
        store {:type :sqlite :database-file (str (fresh-directory) "/events.sqlite") :maximum-pool-size 2}
        common {:tenant-id tenant :auth-claims {} :llm-provider primary :probe-effects prefix-effects}
        staged (atom []) saved (atom nil) original-handler processors/execute-repl-researcher-node
        preflight-count (atom 0)]
    ;; Historical facts are deliberately constructed through public commands.
    ;; They represent old journal formats and are not a live-provider claim.
    (with-redefs [processors/execute-repl-researcher-node #(swap! staged conj %)]
      (h/with-async-test-context [ctx {:context common :event-store-conn store}]
        (let [sheet (orc/build-workflow! ctx (workflow backup {}))
              node (:id (first (filter #(= "research" (:name %)) (orc/get-nodes-for-sheet ctx sheet))))]
          (h/run-and-apply! ctx (command :sheet/tick-tree
                                {:sheet-id sheet :tick-id tick :inputs {}
                                 :options {:timeout-ms 120000 :llm-call-budget 2}}))
          (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
          (let [owner {:sheet-id sheet :tick-id tick :node-id node
                       :node-start-id (get-in (first @staged) [:event :event/id])
                       :exec-context {} :role :prime :ownership-epoch 1}
                root (select-keys owner [:sheet-id :tick-id :node-id])
                logical (effects/provider-logical-action-identity
                         {:tick-id tick :node-id node :iteration-index 0
                          :provider primary :model "fixture/prime"
                          :module {:historical-module "original"} :inputs {:source "original-source"}
                          :options {:role :prime :name "research" :configuration-id config-id}})
                claim! (fn [id]
                         (h/run-and-apply! ctx
                           (command :sheet/claim-researcher-effect
                             (merge root {:iteration-index 0 :ownership-epoch 1 :attempt-ordinal 0
                                          :logical-action-identity id
                                          :attempt-identity (effects/attempt-identity id 1 0)
                                          :kind :provider :claimed-at (str (java.time.Instant/now))}))))
                _ (is (nil? (:cognitect.anomalies/category
                              (h/run-and-apply! ctx (command :sheet/claim-researcher-frontier
                                                    (merge root {:ownership-epoch 1 :claimed-at (str (java.time.Instant/now))}))))))
                bound (journal/callbacks ctx (assoc owner :logical-action-id logical :configuration-id config-id)
                                         candidates :temporary-transport)
                outcome {:status :success :result {:outputs {:code code :reasoning reasoning}
                                                          :model "fixture/prime-backup"}
                         :outputs {:code code :reasoning reasoning}
                         :model "fixture/prime-backup"}]
            (is (nil? (:cognitect.anomalies/category (claim! logical))))
            ;; An inline provider effect in the same iteration cannot become
            ;; the prime simply because it shares :kind :provider.
            (is (nil? (:cognitect.anomalies/category (claim! (str logical "/inline")))))
            (is (nil? (:cognitect.anomalies/category ((:claim! bound) 0 (first candidates)))))
            (when (#{:terminal :ambiguous} mode)
              (is (nil? (:cognitect.anomalies/category
                         ((:record! bound) {:candidate-index 0 :provider primary :requested-model "fixture/prime"
                                           :status :success :advanced? false :outcome outcome})))))
            (when (= :backup-physical mode)
              (is (nil? (:cognitect.anomalies/category
                         ((:record! bound) {:candidate-index 0 :provider primary :requested-model "fixture/prime"
                                           :status :failure :advanced? true
                                           :outcome {:status :failure :failure-kind :transport-failure
                                                     :model-fallback {:policy :temporary-transport :attempts []}}}))))
              (is (nil? (:cognitect.anomalies/category ((:claim! bound) 1 (second candidates)))))
              (let [request {:request-id (random-uuid) :candidate-index 1 :attempt-ordinal 0
                             :provider backup :requested-model "fixture/prime-backup"}]
                (is (nil? (:cognitect.anomalies/category ((:record-request! bound) (assoc request :phase :started)))))
                (is (nil? (:cognitect.anomalies/category ((:record-request! bound) (assoc request :phase :completed :outcome outcome)))))))
            (when (= :ambiguous mode)
              (let [other-id (str logical "/another-prime")
                    other (journal/callbacks ctx (assoc owner :logical-action-id other-id :configuration-id config-id)
                                             candidates :temporary-transport)]
                (is (nil? (:cognitect.anomalies/category (claim! other-id))))
                (is (nil? (:cognitect.anomalies/category ((:claim! other) 0 (first candidates)))))
                (is (nil? (:cognitect.anomalies/category
                           ((:record! other) {:candidate-index 0 :provider primary :requested-model "fixture/prime"
                                             :status :success :advanced? false :outcome outcome}))))))
            (reset! saved {:sheet sheet :node node :logical logical :configuration-id config-id
                           :before (orc/value-log-read-tick-events ctx tenant tick)})))))
    (with-redefs [llm/preflight-provider! (fn [& _] (swap! preflight-count inc)
                                           (throw (ex-info "Historical recovery must precede registry lookup" {})))]
      (h/with-async-test-context
        [ctx {:context (assoc common :orc/instance-id (random-uuid)
                                    :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600))
              :event-store-conn store}]
        (let [resumed (orc/resume-in-progress! ctx)
              _ (is (some :resumed? resumed))
              _ (is (h/settle-until! #(some? (runtime/durable-terminal-result ctx tick)) :timeout-ms 10000))
              result (runtime/durable-terminal-result ctx tick)
              events (orc/value-log-read-tick-events ctx tenant tick)
              completion (last (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                             (= (:node @saved) (:node-id %))) events))
              writes (when completion (orc/value-log-resolve-writes ctx tenant tick completion))
              successful? (#{:terminal :backup-physical} mode)
              history (filter #(contains? journal/event-types (:event/type %)) events)]
          (is (zero? @preflight-count))
          (is (= 1 @prefix-effects))
          (is (not-any? #(= :sheet/provider-call-reserved (:event/type %)) events))
          (is (not-any? #(= :sheet/model-plan-retained (:event/type %)) events))
          (is (= #{config-id} (set (map :configuration-id history))))
          (if successful?
            (do
              (is (= :success (:status result)) (pr-str result))
              (is (= {:summary "original-source"} (select-keys writes [:summary])))
              (is (= #{:summary :iteration-reasonings :iterations} (set (keys writes))))
              (is (= [reasoning] (:iteration-reasonings writes)))
              (is (= [code] (mapv :code (:iterations writes))))
              (is (some #(and (= :rlm/researcher-effect-completed (:event/type %))
                               (= (:logical @saved) (:logical-action-identity %))
                               (= 2 (:ownership-epoch %))) events)))
            (do
              (is (= :failure (:status result)))
              (is (= :model-plan-incompatible (:failure-kind completion)))
              (is (= (str "Model plan cannot be resumed ["
                          (if (= :ambiguous mode) "historical-operation-ambiguous" "legacy-effective-profile-unretained") "]")
                     (:error completion)))
              (is (empty? writes))))
          (swap! proofs conj {:kind mode :saved @saved :resumed resumed :events events
                              :result result :writes writes :preflight-calls @preflight-count}))))))

(deftest incomplete-unretained-prime-refuses-typed-before-registry-or-spend
  (historical-prime-case! :incomplete))

(deftest ambiguous-historical-prime-associations-refuse-before-registry-or-spend
  (historical-prime-case! :ambiguous))

(deftest identifiable-historical-prime-terminal-outcome-replays-original-identity
  (historical-prime-case! :terminal))

(deftest identifiable-historical-backup-physical-receipt-replays-original-identity
  (historical-prime-case! :backup-physical))

(defn profile-plan [provider model]
  {:version 1 :policy :temporary-transport
   :candidates [{:provider provider :model model :protocol :chat
                 :options {:output-mode :function-calling :force-tool-choice? false :max-retries 0}
                 :profile {:version "paired-function-v1-strict" :output-mode :function-calling
                           :force-tool-choice? false :strict-arguments? true}}]})

(defn staged-prime! [f]
  (let [staged (atom []) primary (keyword (str "prime-owned-" (random-uuid)))
        backup (keyword (str "prime-owned-backup-" (random-uuid)))
        store {:type :sqlite :database-file (str (fresh-directory) "/events.sqlite") :maximum-pool-size 2}]
    (with-redefs [processors/execute-repl-researcher-node #(swap! staged conj %)]
      (h/with-async-test-context [ctx {:event-store-conn store
                                      :context {:tenant-id (random-uuid) :auth-claims {} :llm-provider primary :probe-effects (atom 0)}}]
        (let [sheet (orc/build-workflow! ctx (workflow backup {})) tick (random-uuid)
              node (:id (first (filter #(= "research" (:name %)) (orc/get-nodes-for-sheet ctx sheet))))]
          (h/run-and-apply! ctx (command :sheet/tick-tree {:sheet-id sheet :tick-id tick :inputs {}}))
          (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
          (f ctx {:sheet-id sheet :tick-id tick :node-id node :role :prime :exec-context {}
                  :node-start-id (get-in (first @staged) [:event :event/id]) :ownership-epoch 1}
             primary backup staged))))))

(defn frontier! [ctx owner epoch]
  (h/run-and-apply! ctx (command :sheet/claim-researcher-frontier
                              (merge (select-keys owner [:sheet-id :tick-id :node-id])
                                     {:ownership-epoch epoch :claimed-at (str (java.time.Instant/now))}))))

(defn turn-id [owner iteration]
  (fn [_ config]
    (effects/provider-logical-action-identity
     {:tick-id (:tick-id owner) :node-id (:node-id owner) :iteration-index iteration
      :module {:name "prime-test-module"} :inputs {:turn iteration}
      :options {:role :prime :configuration-id config :exec-context (:exec-context owner)}})))

(deftest competing-prime-preparations-and-current-request-ownership-are-fenced
  (staged-prime!
   (fn [ctx owner primary backup staged]
     (let [authored {:fallback-models [{:provider backup :model "fixture/backup"}]}
           plan-a (profile-plan primary "fixture/a") plan-b (profile-plan primary "fixture/b")
           root (select-keys owner [:sheet-id :tick-id :node-id])
           initial (assoc owner :logical-action-id "unowned" :configuration-id (journal/retained-configuration-id plan-a)
                                :plan-scope-id (journal/prime-plan-scope-id owner authored) :plan plan-a)]
       (is (= :cognitect.anomalies/conflict
              (:cognitect.anomalies/category (h/run-and-apply! ctx (command :sheet/retain-model-plan (dissoc initial :ownership-epoch))))))
       (is (nil? (:cognitect.anomalies/category (frontier! ctx owner 1))))
       (let [ready (java.util.concurrent.CountDownLatch. 2) release (promise)
             workers (mapv (fn [plan]
                             (future (journal/prepare-prime-call
                                      ctx owner authored 0 []
                                      #(do (.countDown ready) @release plan) (turn-id owner 0)))) [plan-a plan-b])]
         (is (.await ready 5 TimeUnit/SECONDS))
         (deliver release true)
         (let [prepared (mapv #(deref % 10000 ::timeout) workers)
               winner (first prepared)
               next-turn (journal/prepare-prime-call ctx owner authored 1 []
                           #(throw (ex-info "Retained plan must precede registry resolution" {})) (turn-id owner 1))
               before (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))
               callbacks (:callbacks winner)
               request {:request-id (random-uuid) :phase :started :candidate-index 0 :attempt-ordinal 0
                        :provider primary :requested-model (get-in winner [:candidates 0 :model])}]
           (is (not-any? #{::timeout} prepared))
           (is (= 1 (count (filter #(= :sheet/model-plan-retained (:event/type %)) before))))
           (is (= 1 (count (set (map :configuration-id prepared)))))
           (is (every? #(= (:plan winner) (:plan %)) prepared))
           (is (= (:plan winner) (:plan next-turn)))
           (is (= (:configuration-id winner) (:configuration-id next-turn)))
           (is (not= (:logical-action-id winner) (:logical-action-id next-turn)))
           (is (empty? (get-in next-turn [:callbacks :request-receipts])))
           (is (nil? (:cognitect.anomalies/category ((:claim! callbacks) 0 (first (:candidates winner))))))
           (is (nil? (:cognitect.anomalies/category ((:record-request! callbacks) request))))
           (let [resumed (h/run-and-apply! ctx (command :sheet/resume-node-execution
                                                    (assoc root :original-start-event-id (:node-start-id owner) :inputs {})))]
             (is (nil? (:cognitect.anomalies/category resumed))))
           (is (h/settle-until! #(= 2 (count @staged)) :timeout-ms 5000))
           (is (nil? (:cognitect.anomalies/category (frontier! ctx owner 2))))
           (let [completion (assoc request :phase :completed :outcome {:status :success :result {:outputs {:code code :reasoning reasoning}}})
                 entry {:candidate-index 0 :provider primary :requested-model (:requested-model request)
                        :status :success :advanced? false :outcome (:outcome completion)}
                 stale [((:record-request! callbacks) completion) ((:record! callbacks) entry)
                        ((:select! callbacks) 0 (first (:candidates winner)))
                        (h/run-and-apply! ctx (command :sheet/retain-model-plan initial))]
                 current-owner (assoc owner :node-start-id (get-in (last @staged) [:event :event/id]) :ownership-epoch 2)
                 current (journal/prepare-prime-call ctx current-owner authored 0 [] (constantly plan-b) (turn-id owner 0))
                 current-callbacks (:callbacks current)
                 other-tenant (try
                                (journal/prepare-prime-call (assoc ctx :tenant-id (random-uuid)) current-owner authored 0 []
                                  (constantly plan-a) (turn-id owner 0))
                                :unexpected-success
                                (catch clojure.lang.ExceptionInfo e (ex-data e)))
                 mirror-ambiguity (try
                                    (journal/prepare-prime-call
                                     ctx (assoc current-owner :legacy-completed-action-id "legacy-current-turn")
                                     authored 1 [] (constantly plan-a) (turn-id owner 1))
                                    :unexpected-success
                                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
             (is (every? #(= :cognitect.anomalies/conflict (:cognitect.anomalies/category %)) stale))
             (is (= :model-plan-incompatible (:failure-kind other-tenant)))
             (is (= :model-plan-ownership-lost (:reason other-tenant)))
             (is (= :model-plan-incompatible (:failure-kind mirror-ambiguity)))
             (is (= :historical-operation-ambiguous (:reason mirror-ambiguity)))
             (is (= (:configuration-id winner) (:configuration-id current)))
             (is (= (:logical-action-id winner) (:logical-action-id current)))
             (is (nil? (:cognitect.anomalies/category ((:claim! current-callbacks) 0 (first (:candidates current))))))
             (is (nil? (:cognitect.anomalies/category ((:record-request! current-callbacks) completion))))
             (is (nil? (:cognitect.anomalies/category ((:record! current-callbacks) entry))))
             (let [after (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))
                   receipt (last (filter #(= :sheet/model-request-recorded (:event/type %)) after))]
               (is (= (:request-id request) (get-in receipt [:request :request-id])))
               (is (= 2 (:ownership-epoch receipt)))
               (is (= (:node-start-id current-owner) (:node-start-id receipt)))
               (is (= 1 (count (filter #(= :sheet/model-plan-retained (:event/type %)) after))))
               (swap! proofs conj {:kind :prime-owned-preparation :events after
                                   :prepared (mapv #(dissoc % :callbacks) prepared)
                                   :stale stale :other-tenant other-tenant :mirror-ambiguity mirror-ambiguity})))))))))

(deftest unfamiliar-prime-profile-refuses-fresh-work-but-replays-exact-completed-request
  (staged-prime!
   (fn [ctx owner primary backup staged]
     (let [authored {:fallback-models [{:provider backup :model "fixture/backup"}]}
           plan (assoc-in (profile-plan primary "fixture/future") [:candidates 0 :profile :version] "unknown-paired-v9")
           config (journal/retained-configuration-id plan)
           logical ((turn-id owner 0) plan config)
           call-owner (assoc owner :logical-action-id logical :configuration-id config)
           _ (is (nil? (:cognitect.anomalies/category (frontier! ctx owner 1))))
           retained (h/run-and-apply! ctx (command :sheet/retain-model-plan
                                          (assoc call-owner :plan-scope-id (journal/prime-plan-scope-id owner authored) :plan plan)))
           unsupported (try (journal/prepare-prime-call ctx owner authored 0 [] (constantly plan) (turn-id owner 0))
                            :unexpected-success
                            (catch clojure.lang.ExceptionInfo e (ex-data e)))
           bound (journal/callbacks ctx call-owner (:candidates plan) :temporary-transport)
           prediction {:outputs {:code code :reasoning reasoning}
                       :usage {:prompt-tokens 8 :completion-tokens 5 :total-tokens 13}
                       :model "fixture/future"
                       :provider-observations {:provider (name primary) :model "fixture/future"}}
           request {:request-id (random-uuid) :phase :started :candidate-index 0 :attempt-ordinal 0
                    :provider primary :requested-model "fixture/future"}
           calls (atom 0) reservations (atom 0)]
       (is (nil? (:cognitect.anomalies/category retained)))
       (is (= :model-plan-incompatible (:failure-kind unsupported)))
       (is (= :retained-profile-version-unsupported (:reason unsupported)))
       (is (nil? (:cognitect.anomalies/category ((:claim! bound) 0 (first (:candidates plan))))))
       (is (nil? (:cognitect.anomalies/category ((:record-request! bound) request))))
       (is (nil? (:cognitect.anomalies/category
                  ((:record-request! bound) (assoc request :phase :completed
                                                    :outcome {:status :success :result prediction
                                                              :outputs (:outputs prediction) :usage (:usage prediction)
                                                              :model (:model prediction)})))))
       (h/run-and-apply! ctx (command :sheet/resume-node-execution
                              (assoc (select-keys owner [:sheet-id :tick-id :node-id])
                                     :original-start-event-id (:node-start-id owner) :inputs {})))
       (is (h/settle-until! #(= 2 (count @staged)) :timeout-ms 5000))
       (is (nil? (:cognitect.anomalies/category (frontier! ctx owner 2))))
       (let [current-owner (assoc owner :node-start-id (get-in (last @staged) [:event :event/id]) :ownership-epoch 2)
             prepared (journal/prepare-prime-call ctx current-owner authored 0 [] (constantly plan) (turn-id owner 0))
             call-context (merge ctx {:prepared-prime-model-call prepared :researcher-iteration 0
                                      :reserve-provider-call! (fn [_] (swap! reservations inc) nil)})]
         (is (true? (:replay-only? prepared)))
         (with-redefs [llm/preflight-provider! (fn [& _] (swap! calls inc) (throw (ex-info "No registry during exact replay" {})))
                       llm/predict (fn [& _] (swap! calls inc) (throw (ex-info "No transport during exact replay" {})))]
           (let [result (models/predict {:fallback-models []} primary {} {} {:with-metadata? true}
                                       call-context :prime logical)]
             (is (= prediction (dissoc result :model-fallback)))
             (is (zero? @calls))
             (is (zero? @reservations))))
         ;; The inline surface must not consume an inherited prime preparation.
         (with-redefs [llm/predict (fn [& _] (swap! calls inc) {:outputs {:summary "inline-own-result"}})]
           (let [inline (models/predict {} primary {} {} {:with-metadata? true}
                                       call-context :subcall "different-inline-request")]
             (is (= {:summary "inline-own-result"} (:outputs inline)))
             (is (= 1 @calls))
             (is (= 1 @reservations))))
         (swap! proofs conj {:kind :unfamiliar-prime-profile :unsupported unsupported
                             :prepared (dissoc prepared :callbacks)
                             :events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))}))))))

(deftest frontier-advance-after-owner-validation-cannot-enter-late-cas-snapshot
  (staged-prime!
   (fn [ctx owner primary _ _]
     (is (nil? (:cognitect.anomalies/category (frontier! ctx owner 1))))
     (let [candidates [{:provider primary :model "fixture/original" :options {}}]
           call-owner (assoc owner :logical-action-id "guarded-original-prime")
           bound (journal/callbacks ctx call-owner candidates :temporary-transport)
           request {:request-id (random-uuid) :candidate-index 0 :attempt-ordinal 0
                    :provider primary :requested-model "fixture/original" :phase :started}
           _ (is (nil? (:cognitect.anomalies/category ((:claim! bound) 0 (first candidates)))))
           _ (is (nil? (:cognitect.anomalies/category ((:record-request! bound) request))))
           read-real es/read gate (atom false) advanced (atom nil)
           result (with-redefs [es/read (fn [store options]
                                         (when (and (contains? (:types options) :sheet/node-execution-started)
                                                    (contains? (:types options) :sheet/model-request-recorded)
                                                    (compare-and-set! gate false true))
                                           ;; This read belongs to prime-owned-cas,
                                           ;; after the handler has validated owns?.
                                           (reset! advanced (frontier! ctx owner 2)))
                                         (read-real store options))]
                    ((:record-request! bound)
                     (assoc request :phase :completed :outcome {:status :success :result {:outputs {:code code :reasoning reasoning}}})))
           events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))]
       (is (true? @gate))
       (is (nil? (:cognitect.anomalies/category @advanced)))
       (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
       (is (not-any? #(and (= :sheet/model-request-recorded (:event/type %))
                           (= :completed (get-in % [:request :phase]))) events))
       (is (= [1 2] (mapv :ownership-epoch (filter #(= :rlm/researcher-frontier-claimed (:event/type %)) events))))
       (swap! proofs conj {:kind :frontier-between-validation-and-cas :events events :result result :advanced @advanced})))))

(deftest retained-plan-between-legacy-prime-validation-and-append-rejects-old-bucket
  (staged-prime!
   (fn [ctx owner primary _ _]
     (is (nil? (:cognitect.anomalies/category (frontier! ctx owner 1))))
     (let [legacy-owner (dissoc (assoc owner :logical-action-id "legacy-prime") :ownership-epoch)
           legacy-candidates [{:provider primary :model "fixture/old" :options {}}]
           bound (journal/callbacks ctx legacy-owner legacy-candidates :temporary-transport)
           plan (profile-plan primary "fixture/current")
           authored {:fallback-models []}
           read-real es/read gate (atom false) retained (atom nil)
           result (with-redefs [es/read (fn [store options]
                                         (when (and (= #{:sheet/tree-tick-started} (:types options))
                                                    (compare-and-set! gate false true))
                                           ;; append obtains selection scope only
                                           ;; after claim-model-candidate owns?.
                                           (reset! retained
                                             (h/run-and-apply! ctx (command :sheet/retain-model-plan
                                                                      (assoc owner :logical-action-id "new-prime-plan"
                                                                                   :configuration-id (journal/retained-configuration-id plan)
                                                                                   :plan-scope-id (journal/prime-plan-scope-id owner authored)
                                                                                   :plan plan)))))
                                         (read-real store options))]
                    ((:claim! bound) 0 (first legacy-candidates)))
           events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:tick-id owner))]
       (is (true? @gate))
       (is (nil? (:cognitect.anomalies/category @retained)))
       (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result)))
       (is (= 1 (count (filter #(= :sheet/model-plan-retained (:event/type %)) events))))
       (is (not-any? #(= :sheet/model-candidate-claimed (:event/type %)) events))
       (swap! proofs conj {:kind :retained-plan-between-legacy-validation-and-append :events events
                           :result result :retained @retained})))))
