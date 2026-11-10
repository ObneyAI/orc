(ns ai.obney.orc.orc-service.retained-emitted-model-plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str] [clojure.java.io :as io] [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.todo-processors :as processors]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.budget-facts :as budget-facts]
            [ai.obney.orc.orc-service.core.model-call-journal :as journal]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]
            [ai.obney.orc.orc-service.retained-prime-model-plan-test :as prime])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress] [java.util.concurrent TimeUnit]))
(def proofs (atom []))
(def packet-schema
  [:map {:closed true :description "Exact original evidence packet."}
   [:payload [:map {:closed true :description "Original nested evidence values."}
              [:label [:string {:description "Original source label."}]]
              [:enabled [:boolean {:description "False is an intentional source value."}]]
              [:count [:int {:description "Zero is an intentional source value."}]]
              [:items [:vector {:description "Opaque source records."}
                       [:map {:closed true} [:id :string] [:note [:maybe :string]]]]]]]])
(def packet {:payload {:label "original-source" :enabled false :count 0
                       :items [{:id "opaque/alpha" :note nil}]}})
(def emitted-tree
  [:sequence
   [:llm {:instruction "Return the exact nested source evidence packet."
          :reads [:source] :writes [:packet] :output-schemas {:packet packet-schema}
          :options {:max-retries 0 :max-tokens 4096}}]
   [:final {:keys [:packet]}]])
(def first-code (str "(do (touch {}) (emit-tree! " (pr-str emitted-tree) "))"))
(def last-code "(final! {:summary (get-var :packet)})")
(defn actual-message [body values]
  (cond
    (seq (:tools body))
    {:role "assistant" :content nil
     :tool_calls [{:id "controlled-emitted-call" :type "function"
                   :function {:name "submit_response" :arguments (json/generate-string values)}}]}
    (:response_format body)
    {:role "assistant" :content (json/generate-string values)}
    :else
    {:role "assistant"
     :content (str (str/join "\n" (for [[k v] values]
                                   (str "[[ ## " (name k) " ## ]]\n"
                                        (if (string? v) v (json/generate-string v)))))
                   "\n[[ ## completed ## ]]")}))
(defn server!
  ([] (server! nil))
  ([respond]
  (let [requests (atom []) responses (atom []) errors (atom []) counts (atom {})
        pool (java.util.concurrent.Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [raw (slurp (.getRequestBody exchange)) body (json/parse-string raw true)
                  model (:model body) n (get (swap! counts update model (fnil inc 0)) model)
                  _ (swap! requests conj {:raw raw :body body})
                  primary? (= "fixture/emitted-primary" model)
                  status (if primary? 503 200)
                  values (if respond (respond body n)
                           (if (= "fixture/outer-prime" model)
                           {:reasoning "Execute the controlled emitted protocol."
                            :code (if (= n 1) first-code last-code)}
                           packet))
                  response (if primary?
                             {:error {:message "controlled temporary unavailable" :code 503}}
                             {:id (str "controlled-" model "-" n) :model (str model "-resolved")
                              :choices [{:index 0 :finish_reason (if (seq (:tools body)) "tool_calls" "stop")
                                         :message (actual-message body values)}]
                              :usage {:prompt_tokens 8 :completion_tokens 5 :total_tokens 13}})
                  serialized (json/generate-string response) bytes (.getBytes serialized "UTF-8")]
              (swap! responses conj {:status status :raw serialized :body response})
              (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.sendResponseHeaders exchange status (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (catch Throwable e (swap! errors conj {:class (.getName (class e))}))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :requests requests :responses responses :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))})))
(defn run-fresh-case!
  ([capped?] (run-fresh-case! capped? true))
  ([capped? checkpointed?]
  (let [out (System/getProperty "orc.probe.out") db (io/file (or out (prime/fresh-directory)) (str "emitted-" capped? "-checkpoint-" checkpointed? ".sqlite"))
        _ (assert (not (.exists db)) "Fresh database required")
        fixture (server!) effects (atom 0) tools (atom 0)
        outer (keyword (str "emitted-outer-" (random-uuid)))
        primary (keyword (str "emitted-primary-" (random-uuid)))
        backup (keyword (str "emitted-backup-" (random-uuid)))
        tenant (random-uuid) tick (random-uuid)]
    (try
      (let [config {:provider :openrouter
                    :config {:api-key "CONTROLLED-NOT-A-REAL-KEY" :api-base (:url fixture) :timeout 10000}}]
        (llm/register-provider! outer (assoc config :model "fixture/outer-prime" :output-mode :json-schema))
        (llm/register-provider! primary (assoc config :model "fixture/emitted-primary" :output-mode :function-calling))
        (llm/register-provider! backup (assoc config :model "fixture/emitted-backup")))
      (h/with-async-test-context [ctx {:event-store-conn {:type :sqlite :database-file (str db) :maximum-pool-size 2}
                                     :context {:tenant-id tenant :llm-provider outer :probe-effects effects
                                               :call-tool-fn (fn ([_ _] (swap! tools inc) {:ok true}) ([_ _ _] (swap! tools inc) {:ok true}))}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow (str "fresh-emitted-retained-profile-" capped? "-" (random-uuid))
                        (orc/blackboard {:source [:string {:description "Original prefix source."}]
                                         :summary packet-schema})
                        (orc/sequence "task"
                          (orc/code "prefix" :fn "ai.obney.orc.orc-service.retained-prime-model-plan-test/prefix!"
                            :reads [] :writes [:source])
                          (orc/repl-researcher "research" :model "fixture/outer-prime"
                            :instruction "Execute the controlled emitted protocol, then finalize its packet."
                            :reads [:source] :writes [:summary] :mcp-tools ["touch"]
                            :tool-contracts {"touch" {:arguments [:map] :result [:map [:ok :boolean]] :checkpoint-safe? true}}
                            :max-iterations 2 :options {:output-mode :json-schema :max-retries 0 :max-tokens 65536}
                            :rlm {:recursive? true :checkpointed? checkpointed?
                                  :sub-provider primary :sub-model "fixture/emitted-primary"
                                  :sub-options {:max-retries 0 :max-tokens 4096}
                                  :sub-fallback-models [{:provider backup :model "fixture/emitted-backup"}]
                                  :timeouts {:provider-ms 20000 :iteration-ms 60000 :phase2-ms 45000 :campaign-ms 120000}}))))
              result (apply orc/execute ctx sheet {} (concat [:tick-id tick :timeout-ms 120000 :max-ticks 1] (when capped? [:llm-call-budget 4])))
              all-events (into [] (es/read (:event-store ctx) {:tenant-id tenant}))
              root-events (orc/value-log-read-tick-events ctx tenant tick)
              children (filter #(and (= :sheet/tree-tick-started (:event/type %)) (= tick (:parent-tick-id %))) all-events)
              child (first children) child-tick (:tick-id child)
              child-events (when child-tick (orc/value-log-read-tick-events ctx tenant child-tick))
              child-nodes (when child (orc/get-nodes-for-sheet ctx (:sheet-id child)))
              leaf (first (filter #(= :ai (:executor %)) child-nodes))
              leaf-completion (first (filter #(and (= :sheet/node-execution-completed (:event/type %)) (= (:id leaf) (:node-id %))) child-events))
              writes (when leaf-completion (orc/value-log-resolve-writes ctx tenant child-tick leaf-completion))
              bodies (mapv :body @(:requests fixture))
              emitted (filterv #(str/starts-with? (:model %) "fixture/emitted-") bodies)
              reservations (filterv #(= :sheet/provider-call-reserved (:event/type %)) all-events)]
          (swap! proofs conj {:result result :tenant tenant :root-sheet sheet :root-tick tick
                         :root-events root-events :all-events all-events :child-events child-events
                         :child-starts (vec children) :child-leaf leaf :child-completion leaf-completion
                         :canonical-child-writes writes :prefix-effects @effects :tool-effects @tools
                         :requests @(:requests fixture) :responses @(:responses fixture)
                         :fixture-errors @(:errors fixture) :reservations reservations
                         :authored-emitted emitted-tree :expected-packet packet
                         :primary-default :function-calling :backup-default nil :outer-explicit-mode :json-schema})
          (spit (io/file (.getParentFile db) (str "proof-" capped? "-checkpoint-" checkpointed? ".edn")) (pr-str (last @proofs)))
          (testing "Runtime success and exact native projection do not substitute for wire adherence"
            (is (empty? @(:errors fixture)))
            (is (= :success (:status result)) (pr-str result))
            (is (= packet (get-in result [:outputs :summary])))
            (is (= {:packet packet} writes))
            (is (= 1 @effects)) (is (= 1 @tools))
            (is (= 1 (count children)))
            (is (= tick (:parent-tick-id child)))
            (is (= (when checkpointed? tick) (get-in child [:options :researcher-campaign-tick-id])))
            (is (= :success (:status leaf-completion)))
            (is (= (if capped? 4 0) (count reservations)))
            (is (= ["fixture/outer-prime" "fixture/emitted-primary" "fixture/emitted-backup" "fixture/outer-prime"] (mapv :model bodies)))
            (is (every? #(true? (get-in % [:response_format :json_schema :strict]))
                        (filter #(= "fixture/outer-prime" (:model %)) bodies))))
          (when-not checkpointed?
            (is (nil? (get-in child [:options :checkpointed-campaign?])))
            (is (= tick (get-in child [:options :llm-budget-root-tick-id])))
            (is (every? #(= :ordinary (:admission-kind %)) reservations))
            (is (not-any? #(contains? % :ownership-epoch) reservations))
            (is (not-any? #(contains? #{:rlm/researcher-frontier-claimed :rlm/researcher-effect-claimed
                                        :rlm/researcher-effect-completed} (:event/type %)) all-events)))
          (testing "The effective specialist plan and actual decoded child predictions are durable"
            (let [plans (filter #(and (= :sheet/model-plan-retained (:event/type %)) (= (if checkpointed? :subcall :node) (:role %))) all-events)
                  physical (filter #(and (= :sheet/model-request-recorded (:event/type %)) (= child-tick (:tick-id %))) all-events)
                  saved (first (filter #(and (= :completed (get-in % [:request :phase])) (= 1 (get-in % [:request :candidate-index]))) physical))]
              (is (= 1 (count plans)))
              (is (= [:function-calling :function-calling] (mapv #(get-in % [:profile :output-mode]) (get-in (first plans) [:plan :candidates]))))
              (is (= 4 (count physical)))
              (is (= {:packet packet} (get-in saved [:request :outcome :outputs])))
              (is (= :success (get-in saved [:request :provider-outcome :status])))))
          (testing "Both actual emitted candidates must receive the inherited paired function contract"
            (is (= 2 (count emitted)))
            (doseq [body emitted]
              (is (= "auto" (:tool_choice body)))
              (is (= "submit_response" (get-in body [:tools 0 :function :name])))
              (is (true? (get-in body [:tools 0 :function :strict])))
              (is (= #{"payload"} (set (get-in body [:tools 0 :function :parameters :required]))))
              (is (= #{"label" "enabled" "count" "items"}
                     (set (get-in body [:tools 0 :function :parameters :properties :payload :required]))))
              (is (str/includes? (pr-str (get-in body [:tools 0 :function :parameters])) "False is an intentional source value."))
              (is (not (contains? body :response_format)))
              (is (not (str/includes? (pr-str (:messages body)) "[[ ##"))))
            (is (= (get-in (first emitted) [:tools 0 :function :parameters])
                   (get-in (second emitted) [:tools 0 :function :parameters]))))))
      (finally
        (.stop (:server fixture) 0) (.shutdownNow (:pool fixture))
        (assert (.awaitTermination (:pool fixture) 5 TimeUnit/SECONDS) "Owned HTTP executor did not terminate"))))))

(deftest fresh-capped-emitted-retains-effective-profile-and-physical-response (run-fresh-case! true))

(deftest fresh-uncapped-emitted-retains-effective-profile-and-physical-response (run-fresh-case! false))

(deftest fresh-optout-emitted-preserves-profile-without-campaign-checkpoints
  (run-fresh-case! true false))

(deftest canonical-specialist-start-read-failure-and-partial-metadata-refuse
  (prime/staged-prime!
    (fn [ctx original _ backup _]
      (let [owner (assoc original :role :subcall :logical-action-id "read-failure-request")
            resolve-count (atom 0) read-events es/read
            before (into [] (read-events (:event-store ctx) {:tenant-id (:tenant-id ctx)}))
            failed (with-redefs [es/read (fn [store query]
                                          (if (= #{:sheet/tree-tick-started} (:types query))
                                            {:cognitect.anomalies/category :cognitect.anomalies/unavailable
                                             :cognitect.anomalies/message "UNSAFE-NOT-FOR-OUTPUT"}
                                            (read-events store query)))]
                     (try (journal/prepare-inline-call ctx owner {:fallback-models [{:provider backup}]}
                            #(do (swap! resolve-count inc) (throw (ex-info "resolver must not run" {}))))
                          nil (catch clojure.lang.ExceptionInfo e e)))
            history-failed (with-redefs [es/read (fn [store query]
                                                  (if (contains? (:types query) :sheet/model-plan-retained)
                                                    {:cognitect.anomalies/category :cognitect.anomalies/unavailable}
                                                    (read-events store query)))]
                             (try (journal/prepare-inline-call ctx owner {:fallback-models [{:provider backup}]}
                                    #(do (swap! resolve-count inc) (throw (ex-info "resolver must not run" {}))))
                                  nil (catch clojure.lang.ExceptionInfo e e)))
            invalid-tick (random-uuid)
            invalid-command {:sheet-id (:sheet-id original) :tick-id invalid-tick :inputs {}
                             :options {:researcher-campaign-node-id (:node-id original)}}
            partial (try
                      (budget-facts/event ctx {:type :sheet/tree-tick-started :tags #{[:tick invalid-tick]}
                                               :body (dissoc invalid-command :inputs)})
                      nil (catch clojure.lang.ExceptionInfo e e))
            public-result (h/run-and-apply! ctx (prime/command :sheet/tick-tree invalid-command))]
        (is (= :model-plan-incompatible (:failure-kind (ex-data failed))))
        (is (= :canonical-start-unavailable (:reason (ex-data failed))))
        (is (not (str/includes? (str failed) "UNSAFE-NOT-FOR-OUTPUT")))
        (is (= :model-plan-incompatible (:failure-kind (ex-data history-failed))))
        (is (= :model-history-unavailable (:reason (ex-data history-failed))))
        (is (zero? @resolve-count))
        (is (= :model-plan-incompatible (:failure-kind (ex-data partial))))
        (is (= :emitted-campaign-lineage-unavailable (:reason (ex-data partial))))
        (is (= :cognitect.anomalies/fault (:cognitect.anomalies/category public-result)))
        (is (str/includes? (:cognitect.anomalies/message public-result) "Canonical model campaign metadata is incomplete"))
        (is (empty? (orc/value-log-read-tick-events ctx (:tenant-id ctx) invalid-tick)))
        (is (= before (into [] (read-events (:event-store ctx) {:tenant-id (:tenant-id ctx)}))))))))

(deftest distinct-emitted-and-inline-requests-share-role-plan-across-saved-quantum
  (let [tree (assoc-in emitted-tree [1 1 :reads] [:packet])
        first-source (str "(do (touch {}) (emit-tree! " (pr-str tree) "))")
        middle-source "(do (llm \"inline\" :instruction \"Return the next evidence observation\" :reads [:packet] :writes [:packet]) (store! :prior (get-var :packet)))"
        third-source (str "(emit-tree! " (pr-str tree) ")")
        codes [first-source middle-source third-source last-code]
        initial (assoc-in packet [:payload :label] "initial-source")
        second-value (-> packet (assoc-in [:payload :label] "second-source") (assoc-in [:payload :count] 2))
        third-value (-> packet (assoc-in [:payload :label] "third-source") (assoc-in [:payload :enabled] true))
        fixture (server! (fn [body n]
                           (cond
                             (= "fixture/outer-prime" (:model body))
                             {:reasoning "Execute the next controlled role protocol." :code (nth codes (dec n))}
                             (= "fixture/emitted-backup" (:model body))
                             (let [value (nth [packet second-value third-value] (dec n))]
                               ;; Inline's authored output is one packet field;
                               ;; emitted AI flattens its one map output to payload.
                               ;; The fixture answers the actual declared envelope.
                               (if (= ["packet"] (get-in body [:tools 0 :function :parameters :required]))
                                 {:packet value} value))
                             :else packet)))
        outer (keyword (str "emitted-quantum-outer-" (random-uuid)))
        primary (keyword (str "emitted-quantum-primary-" (random-uuid)))
        backup (keyword (str "emitted-quantum-backup-" (random-uuid)))
        config {:provider :openrouter :config {:api-key "CONTROLLED-NOT-A-REAL-KEY" :api-base (:url fixture) :timeout 10000}}
        _ (llm/register-provider! outer (assoc config :model "fixture/outer-prime" :output-mode :json-schema))
        _ (llm/register-provider! primary (assoc config :model "fixture/emitted-primary" :output-mode :function-calling))
        _ (llm/register-provider! backup (assoc config :model "fixture/emitted-backup"))
        tenant (random-uuid) tick (random-uuid) prefix-count (atom 0) tool-count (atom 0)
        directory (or (System/getProperty "orc.probe.out") (prime/fresh-directory))
        db (io/file directory "emitted-mixed-quantum.sqlite") _ (assert (not (.exists db)))
        store {:type :sqlite :database-file (str db) :maximum-pool-size 2}
        common {:tenant-id tenant :auth-claims {} :llm-provider outer :probe-effects prefix-count
                :call-tool-fn (fn [_ _ _] (swap! tool-count inc) {:ok true})}
        first-context (atom nil) second-context (atom nil) starts (atom 0) held (promise)
        handler processors/execute-repl-researcher-node]
    (try
      (let [ctx (h/create-async-test-context {:context common :event-store-conn store}) _ (reset! first-context ctx)
            definition (orc/workflow (str "emitted-mixed-quantum-" (random-uuid))
                         (orc/blackboard {:source [:string {:description "Original prefix source."}]
                                          :packet packet-schema :prior packet-schema :summary packet-schema})
                         (orc/sequence "task"
                           (orc/code "prefix" :fn "ai.obney.orc.orc-service.retained-prime-model-plan-test/prefix!" :reads [] :writes [:source])
                           (orc/repl-researcher "research" :model "fixture/outer-prime"
                             :instruction "Execute the controlled role protocol and finalize the latest packet."
                             :reads [:source :packet] :writes [:summary] :max-iterations 4
                             :mcp-tools ["touch"] :tool-contracts {"touch" {:arguments [:map] :result [:map [:ok :boolean]] :checkpoint-safe? true}}
                             :options {:output-mode :json-schema :max-retries 0 :max-tokens 65536}
                             :rlm {:recursive? true :checkpointed? true :quantum {:max-iterations 1}
                                   :sub-provider primary :sub-model "fixture/emitted-primary"
                                   :sub-options {:max-retries 0 :max-tokens 4096}
                                   :sub-fallback-models [{:provider backup :model "fixture/emitted-backup"}]
                                   :timeouts {:provider-ms 20000 :iteration-ms 60000 :phase2-ms 45000 :campaign-ms 120000}})))
            sheet (orc/build-workflow! ctx definition)]
        (with-redefs [processors/execute-repl-researcher-node
                      (fn [context] (if (= 2 (swap! starts inc)) (do (deliver held true) nil) (handler context)))]
          (h/run-and-apply! ctx (prime/command :sheet/tick-tree {:sheet-id sheet :tick-id tick :inputs {:packet initial}
                                                              :options {:timeout-ms 120000 :llm-call-budget 8}}))
          (is (= true (deref held 20000 :timeout)))
          (is (= 3 (count @(:requests fixture))))
          (is (= 1 @prefix-count)) (is (= 1 @tool-count))
          (h/stop-async-context ctx) (reset! first-context nil))
        (llm/register-provider! primary (assoc config :model "fixture/changed-primary" :output-mode :json-schema))
        (llm/register-provider! backup (assoc config :model "fixture/changed-backup" :output-mode :json-schema))
        (let [ctx (h/create-async-test-context {:context (assoc common :orc/instance-id (random-uuid)
                                                                      :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600))
                                              :event-store-conn store})
              _ (reset! second-context ctx) resumed (orc/resume-in-progress! ctx)
              _ (is (some :resumed? resumed))
              _ (is (h/settle-until! #(some? (runtime/durable-terminal-result ctx tick)) :timeout-ms 20000))
              result (runtime/durable-terminal-result ctx tick)
              events (into [] (es/read (:event-store ctx) {:tenant-id tenant}))
              plans (filter #(and (= :sheet/model-plan-retained (:event/type %)) (= :subcall (:role %))) events)
              physical (filter #(and (= :sheet/model-request-recorded (:event/type %)) (= :subcall (:role %))
                                     (= :completed (get-in % [:request :phase]))) events)
              successful (filter #(= 1 (get-in % [:request :candidate-index])) physical)
              bodies (mapv :body @(:requests fixture))
              specialist (filterv #(not= "fixture/outer-prime" (:model %)) bodies)
              actual-values (mapv #(or (get-in % [:request :outcome :outputs :packet])
                                      (get-in % [:request :outcome :result :outputs :packet])) successful)
              child-starts (filter #(and (= :sheet/tree-tick-started (:event/type %)) (= tick (:parent-tick-id %))) events)]
          (is (= :success (:status result)) (pr-str result))
          (is (= third-value (get-in result [:outputs :summary])))
          (is (= ["fixture/outer-prime" "fixture/emitted-primary" "fixture/emitted-backup"
                  "fixture/outer-prime" "fixture/emitted-backup" "fixture/outer-prime" "fixture/emitted-backup"
                  "fixture/outer-prime"] (mapv :model bodies)))
          (is (= 1 (count plans)))
          (is (= 1 (count (set (map :configuration-id physical)))))
          (is (= 3 (count (set (map :logical-action-id physical)))))
          (is (= [packet second-value third-value] actual-values))
          (is (= 2 (count child-starts)))
          (is (= 2 (count (set (map :tick-id child-starts)))))
          (is (some #(= :rlm/researcher-resume-state-saved (:event/type %)) events))
          (is (= [1] (mapv :candidate-index (filter #(= :sheet/model-candidate-selected (:event/type %)) events))))
          (is (every? #(and (= "auto" (:tool_choice %)) (true? (get-in % [:tools 0 :function :strict]))) specialist))
          (doseq [[body expected] (map vector (filter #(= "fixture/emitted-backup" (:model %)) specialist)
                                                   [initial packet second-value])]
            (let [content (get-in body [:messages 0 :content])
                  input-json (some-> (re-find #"(?m)^packet: (.+)$" content) second)]
              (is (= expected (when input-json (json/parse-string input-json true))))
              (is (str/includes? content "False is an intentional source value."))
              (is (str/includes? content "Zero is an intentional source value."))))
          (is (= 8 (count (filter #(= :sheet/provider-call-reserved (:event/type %)) events))))
          (is (= 1 @prefix-count)) (is (= 1 @tool-count))
          (let [proof {:kind :mixed-specialist-saved-quantum :result result :events events :plans (vec plans)
                       :requests @(:requests fixture) :responses @(:responses fixture)
                       :expected-values [packet second-value third-value] :canonical-values actual-values
                       :prefix-count @prefix-count :tool-count @tool-count}]
            (swap! proofs conj proof) (spit (io/file directory "mixed-quantum-proof.edn") (pr-str proof)))))
      (finally (when-let [ctx @first-context] (h/stop-async-context ctx))
               (when-let [ctx @second-context] (h/stop-async-context ctx))
               (prime/stop-fixture! fixture)))))

(deftest incomplete-emitted-epoch-refuses-before-provider-or-new-plan
  (prime/staged-prime!
    (fn [ctx parent primary backup _]
      (let [child (orc/build-workflow! ctx
                    (orc/workflow (str "missing-emitted-epoch-" (random-uuid))
                      (orc/blackboard {:value [:string {:description "Uncalled child output."}]})
                      (orc/llm "child" :instruction "This malformed owner must not dispatch."
                        :model "fixture/emitted-primary" :reads [] :writes [:value]
                        :fallback-models [{:provider backup :model "fixture/emitted-backup"}])))
            tick (random-uuid) preflights (atom 0) original-preflight llm/preflight-provider!
            options {:timeout-ms 10000 :researcher-campaign-sheet-id (:sheet-id parent)
                     :researcher-campaign-tick-id (:tick-id parent) :researcher-campaign-node-id (:node-id parent)
                     :researcher-campaign-exec-context {}}]
        (with-redefs [llm/preflight-provider! (fn [& args] (swap! preflights inc) (apply original-preflight args))]
          (h/run-and-apply! ctx (prime/command :sheet/tick-tree
                                 {:sheet-id child :tick-id tick :parent-tick-id (:tick-id parent) :inputs {} :options options}))
          (is (h/settle-until! #(some? (runtime/durable-terminal-result ctx tick)) :timeout-ms 5000)))
        (let [events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick)
              completion (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                               (= :failure (:status %))) events))]
          (is (= :model-plan-incompatible (:failure-kind completion)))
          (is (str/includes? (:error completion) "emitted-campaign-lineage-unavailable"))
          (is (zero? @preflights))
          (is (not-any? #(contains? #{:sheet/model-plan-retained :sheet/model-request-recorded
                                      :sheet/provider-call-reserved} (:event/type %)) events)))))))

(defn exact-historical-emitted-response! [missing-epoch?]
  (prime/staged-prime!
    (fn [ctx parent primary backup _]
      (let [staged (atom [])
            logical-child "original-generated-child-for-command-replay"
            tick (java.util.UUID/nameUUIDFromBytes (.getBytes logical-child "UTF-8"))
            _ (is (nil? (:cognitect.anomalies/category (prime/frontier! ctx parent 1))))
            _ (is (nil? (:cognitect.anomalies/category
                          (h/run-and-apply! ctx (prime/command :sheet/claim-researcher-effect
                            (merge (select-keys parent [:sheet-id :tick-id :node-id])
                                   {:ownership-epoch 1 :iteration-index 0 :kind :generated-child
                                    :logical-action-identity logical-child
                                    :attempt-identity (effects/attempt-identity logical-child 1 0)
                                    :attempt-ordinal 0 :claimed-at (str (java.time.Instant/now))}))))))
            child (orc/build-workflow! ctx
                    (orc/workflow (str "known-emitted-response-" (random-uuid))
                      (orc/blackboard {:value [:string {:description "Original known child response."}]})
                      (orc/llm "child" :instruction "Return the original child value."
                        :model "fixture/current-primary" :reads [] :writes [:value]
                        :fallback-models [{:provider backup :model "fixture/current-backup"}])))
            options (cond-> {:timeout-ms 120000 :researcher-campaign-sheet-id (:sheet-id parent)
                             :researcher-campaign-tick-id (:tick-id parent) :researcher-campaign-node-id (:node-id parent)
                             :researcher-campaign-exec-context {}}
                      (not missing-epoch?) (assoc :researcher-ownership-epoch 1 :researcher-iteration-index 0))
            candidates [{:provider primary :model "fixture/original-primary" :options {}}
                        {:provider backup :model "fixture/original-backup" :options {}}]
            prediction {:status :success :outputs {:value "known-original-value"}
                        :usage {:prompt-tokens 3 :completion-tokens 2 :total-tokens 5}
                        :model "original-resolved-model" :resolved-model "original-resolved-model"}
            request {:request-id (random-uuid) :candidate-index 0 :attempt-ordinal 0
                     :provider primary :requested-model "fixture/original-primary"}]
        (with-redefs [processors/execute-leaf-node #(swap! staged conj %)]
          (h/run-and-apply! ctx (prime/command :sheet/tick-tree
                                 {:sheet-id child :tick-id tick :parent-tick-id (:tick-id parent) :inputs {} :options options}))
          (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
          (let [node (:id (first (orc/get-nodes-for-sheet ctx child)))
                owner {:sheet-id child :tick-id tick :node-id node :node-start-id (get-in (first @staged) [:event :event/id])
                       :exec-context {} :role :subcall :logical-action-id (pr-str [tick node {}])}
                callbacks (journal/callbacks ctx owner candidates :temporary-transport)
                _ (is (nil? (:cognitect.anomalies/category ((:claim! callbacks) 0 (first candidates)))))
                _ (is (nil? (:cognitect.anomalies/category ((:record-request! callbacks) (assoc request :phase :started)))))
                _ (is (nil? (:cognitect.anomalies/category ((:record-request! callbacks)
                                          (assoc request :phase :completed :outcome prediction :provider-outcome {:status :success})))))
                resumed (orc/resume-in-progress! (assoc ctx :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600)))
                _ (is (some #(and (:resumed? %) (= tick (:tick-id %))) resumed))
                _ (is (h/settle-until! #(= 2 (count @staged)) :timeout-ms 5000))
                _ (is (nil? (:cognitect.anomalies/category (prime/frontier! ctx parent 2))))
                current (assoc owner :node-start-id (get-in (last @staged) [:event :event/id]))
                canonical (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick)
                started (first (filter #(= :sheet/tree-tick-started (:event/type %)) canonical))
                blackboard (get-in started [:execution-snapshot :blackboard-entries])
                leaf (first (filter #(= :ai (:executor %)) (orc/get-nodes-for-sheet ctx child)))
                preflights (atom 0) reservations (atom 0) actual-preflight llm/preflight-provider!
                result (with-redefs [llm/preflight-provider! (fn [& args] (swap! preflights inc) (apply actual-preflight args))]
                         (executor/execute-ai leaf blackboard primary
                           :options {:tick-id tick :exec-context {} :model-role-context {:context ctx :owner current}
                                     :reserve-llm-call! (fn [] (swap! reservations inc) nil)
                                     :execution-deadline-ms (+ (System/currentTimeMillis) 10000)}))
                after (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick)
                physical (filter #(= :sheet/model-request-recorded (:event/type %)) after)
                recorded (last (filter #(= :sheet/model-candidate-recorded (:event/type %)) after))]
            (is (= :success (:status result)) (pr-str result))
            (is (= {:value "known-original-value"} (:outputs result)))
            (is (= "original-resolved-model" (:resolved-model result)))
            (is (= (:usage prediction) (:usage result)))
            (is (zero? @preflights)) (is (zero? @reservations))
            (is (= 2 (count physical)))
            (is (= #{(:request-id request)} (set (map #(get-in % [:request :request-id]) physical))))
            (is (= (journal/configuration-id candidates :temporary-transport) (:configuration-id recorded)))
            (is (= (:logical-action-id owner) (:logical-action-id recorded)))
            (is (not-any? #(= :sheet/model-plan-retained (:event/type %)) after))
            (is (not= (:node-start-id owner) (:node-start-id current)))
            (swap! proofs conj {:kind :known-emitted-command-replay :missing-epoch? missing-epoch?
                                :before canonical :after after :result result :original-request request :original-owner owner :current-owner current
                                :preflights @preflights :reservations @reservations})))))))

(deftest known-emitted-response-replays-before-missing-epoch-fresh-admission
  (exact-historical-emitted-response! true))

(deftest known-emitted-response-replays-after-parent-epoch-changes
  (exact-historical-emitted-response! false))

(defn emitted-append-race! [advance]
  (prime/staged-prime!
    (fn [ctx parent primary _ _]
      (let [staged (atom []) logical "actual-emitted-append-race-child"
            child-tick (java.util.UUID/nameUUIDFromBytes (.getBytes logical "UTF-8"))
            _ (is (nil? (:cognitect.anomalies/category (prime/frontier! ctx parent 1))))
            _ (is (nil? (:cognitect.anomalies/category
                          (h/run-and-apply! ctx (prime/command :sheet/claim-researcher-effect
                            (merge (select-keys parent [:sheet-id :tick-id :node-id])
                                   {:ownership-epoch 1 :iteration-index 0 :kind :generated-child
                                    :logical-action-identity logical :attempt-identity (effects/attempt-identity logical 1 0)
                                    :attempt-ordinal 0 :claimed-at (str (java.time.Instant/now))}))))))
            child (orc/build-workflow! ctx
                    (orc/workflow (str "actual-emitted-append-race-" (random-uuid))
                      (orc/blackboard {:value :string})
                      (orc/llm "child" :instruction "Held for actual command ordering." :reads [] :writes [:value])))
            options {:researcher-campaign-sheet-id (:sheet-id parent) :researcher-campaign-tick-id (:tick-id parent)
                     :researcher-campaign-node-id (:node-id parent) :researcher-campaign-exec-context {}
                     :researcher-ownership-epoch 1 :researcher-iteration-index 0}]
        (with-redefs [processors/execute-leaf-node #(swap! staged conj %)]
          (h/run-and-apply! ctx (prime/command :sheet/tick-tree
                                 {:sheet-id child :tick-id child-tick :parent-tick-id (:tick-id parent) :inputs {} :options options}))
          (is (h/settle-until! #(= 1 (count @staged)) :timeout-ms 5000))
          (let [node-id (get-in (first @staged) [:event :node-id])
                owner {:sheet-id child :tick-id child-tick :node-id node-id
                       :node-start-id (get-in (first @staged) [:event :event/id]) :exec-context {} :role :subcall
                       :logical-action-id "same-original-child-request"}
                candidates [{:provider primary :model "fixture/emitted" :options {}}]
                callbacks (journal/callbacks ctx owner candidates :temporary-transport)
                request {:request-id (random-uuid) :candidate-index 0 :attempt-ordinal 0
                         :provider primary :requested-model "fixture/emitted"}
                _ (is (nil? (:cognitect.anomalies/category ((:claim! callbacks) 0 (first candidates)))))
                _ (when (= :child-start advance)
                    (is (nil? (:cognitect.anomalies/category ((:record-request! callbacks) (assoc request :phase :started))))))
                fired (atom false) original-append es/append
                pending-phase (if (= :child-start advance) :completed :started)
                outcome (with-redefs [es/append
                                      (fn [store append-options]
                                        (when (and (some #(and (= :sheet/model-request-recorded (:event/type %))
                                                              (= child-tick (:tick-id %))
                                                              (= pending-phase (get-in % [:request :phase])))
                                                        (:events append-options))
                                                   (compare-and-set! fired false true))
                                          (if (= :child-start advance)
                                            (do
                                              (is (some #(and (:resumed? %) (= child-tick (:tick-id %)))
                                                        (orc/resume-in-progress! (assoc ctx :orc/clock-fn #(.plusSeconds (java.time.Instant/now) 3600)))))
                                              (is (h/settle-until! #(= 2 (count @staged)) :timeout-ms 5000)))
                                            (is (nil? (:cognitect.anomalies/category (prime/frontier! ctx parent 2))))))
                                        (original-append store append-options))]
                          ((:record-request! callbacks)
                           (cond-> (assoc request :phase pending-phase)
                             (= :completed pending-phase)
                             (assoc :outcome {:status :success :outputs {:value "stale-result"}}
                                    :provider-outcome {:status :success}))))
                events (orc/value-log-read-tick-events ctx (:tenant-id ctx) child-tick)
                physical (filter #(= :sheet/model-request-recorded (:event/type %)) events)]
            (is @fired "the actual append window was reached")
            (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category outcome)))
            (is (= (if (= :child-start advance) [:started] []) (mapv #(get-in % [:request :phase]) physical)))
            (when (= :child-start advance)
              (is (not= (:node-start-id owner) (get-in (last @staged) [:event :event/id]))))
            (swap! proofs conj {:kind :actual-emitted-append-race :advance advance :owner owner :outcome outcome :events events})))))))

(deftest emitted-child-start-advance-fences-stale-physical-response
  (emitted-append-race! :child-start))

(deftest emitted-parent-frontier-advance-fences-fresh-physical-request
  (emitted-append-race! :parent-frontier))
