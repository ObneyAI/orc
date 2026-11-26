(ns ai.obney.orc.orc-service.scoped-model-alternative-e2e-test
  "DET-E2E-323 consumer example, not a generic ORC admission policy.
   Ordinary blocking calls only; real Grain with an in-memory store and local HTTP."
  (:require [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.ordinary-transport-failure-e2e-test :as http-fixture]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [litellm.router :as router])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch Executors TimeUnit]))

(def provider :scoped-model-alternative)
(def last-proof (atom nil))
(def instruction "Assess the exact requested item. Preserve its identifier and the completed tool receipt; return the boolean answer and its concise basis.")
(def generation-options {:output-mode :json-schema :max-retries 0 :timeout-ms 15000 :max-tokens 65536})
(def task-reads [:request :tool-receipt])
(def task-writes [:answer-reasoning :item-id :answer])
(def board
  {:request [:map [:item-id [:string {:description "Exact item owned by this task."}]]
                   [:question [:string {:description "The same question for each candidate."}]]]
   :tool-receipt [:map [:operation-id :string] [:item-id :string] [:ordinal :int]]
   :answer-reasoning [:string {:description "Concise basis for the declared answer."}]
   :item-id [:string {:description "Exact requested item identity, unchanged."}]
   :answer [:boolean {:description "Whether this item meets the requested check; false is a valid answer."}]
   :alternative-admission
   [:map {:description "Consumer admission based on the exact persisted predecessor and original execution limits."}
    [:allowed? :boolean] [:task-id :string]
    [:predecessor-event-id [:maybe :uuid]] [:gate-start-id [:maybe :uuid]]
    [:transport [:maybe
                 [:map
                  [:type {:optional true}
                   [:enum :litellm/invalid-request :litellm/invalid-config
                    :litellm/authentication-error :litellm/authorization-error
                    :litellm/provider-not-found :litellm/model-not-found
                    :litellm/unsupported-feature :litellm/quota-exceeded
                    :litellm/rate-limit :litellm/timeout :litellm/connection-error
                    :litellm/server-error :litellm/provider-error
                    :litellm/invalid-response :litellm/streaming-error :litellm/content-filter
                    :litellm/internal-error :litellm/resource-exhausted]]
                  [:http-status {:optional true} [:int {:min 100 :max 599}]]]]]
    [:owned? :boolean] [:tick-id :uuid] [:budget-root-tick-id [:maybe :uuid]]
    [:root-deadline-ms :int] [:candidate-deadline-ms :int]
    [:root-call-budget :int] [:candidate-call-budget :int]]
   :alternative-allowed? :boolean})

(defn temporary-transport? [{:keys [type http-status]}]
  ;; This example's explicit policy. No message parsing and no inference from 429 alone.
  (boolean
   (case type
     :litellm/rate-limit (or (nil? http-status) (= 429 http-status))
     :litellm/timeout (or (nil? http-status) (#{408 504} http-status))
     :litellm/connection-error (nil? http-status)
     (:litellm/server-error :litellm/provider-error)
     (and (integer? http-status) (<= 500 http-status 599))
     false)))

(defn own-predecessor?
  "All records are loaded by the gate from its own tenant/tick; callers cannot supply failure evidence."
  [{:keys [sheet-id tick-id primary-id gate-id start previous previous-index start-index
           expected-inputs previous-inputs]}]
  (boolean
   (and (uuid? primary-id) (uuid? gate-id)
        (= :sheet/node-execution-started (:event/type start))
        (= :sheet/node-execution-completed (:event/type previous))
        (= sheet-id (:sheet-id start) (:sheet-id previous))
        (= tick-id (:tick-id start) (:tick-id previous))
        (= gate-id (:node-id start)) (= primary-id (:node-id previous))
        (integer? previous-index) (integer? start-index) (< previous-index start-index)
        (= (orc/value-log-exec-context (:inputs start))
           (orc/value-log-exec-context (:inputs previous)))
        (= (set task-reads) (set (:read-keys previous)) (set (keys previous-inputs)))
        (= expected-inputs previous-inputs)
        (= :failure (:status previous))
        (= :transport-failure (:failure-kind previous)))))

(defn completed-tool [{:keys [inputs fixture-effects]}]
  (let [id (get-in inputs [:request :item-id])
        ordinal (get (swap! fixture-effects update id (fnil inc 0)) id)]
    {:tool-receipt {:operation-id (str "completed-tool/" id) :item-id id :ordinal ordinal}}))

(defn read-exact-failure [{:keys [inputs event tick-id node-id tenant-id] :as ctx}]
  (let [tick (orc/get-tick ctx tick-id)
        sheet-id (:sheet-id tick)
        frozen (orc/get-tick-execution-context ctx tick-id)
        primaries (filter #(= "primary" (:name %)) (vals (:nodes-by-id frozen)))
        primary (when (= 1 (count primaries)) (first primaries))
        events (orc/value-log-read-tick-events ctx tenant-id tick-id)
        indexed (mapv vector (range) events)
        starts (filter #(= (:event/id event) (:event/id (second %))) indexed)
        [start-index start] (when (= 1 (count starts)) (first starts))
        completions (filter (fn [[i e]]
                              (and (integer? start-index) (< i start-index)
                                   (= :sheet/node-execution-completed (:event/type e))
                                   (= (:id primary) (:node-id e))
                                   (= (orc/value-log-exec-context (:inputs start))
                                      (orc/value-log-exec-context (:inputs e))))) indexed)
        [previous-index previous] (when (= 1 (count completions)) (first completions))
        previous-inputs (when previous (orc/value-log-resolve-reads ctx tenant-id tick-id previous))
        ownership {:sheet-id sheet-id :tick-id tick-id :primary-id (:id primary) :gate-id node-id
                   :start start :previous previous :previous-index previous-index :start-index start-index
                   :expected-inputs (select-keys inputs task-reads) :previous-inputs previous-inputs}
        owned? (own-predecessor? ownership)
        transport (get-in previous [:provider-evidence :transport])
        allowed? (and owned? (temporary-transport? transport))
        root-id (get-in frozen [:options :llm-budget-root-tick-id])
        root-options (:options (orc/get-tick-execution-context ctx (or root-id tick-id)))
        admission {:allowed? allowed? :task-id (get-in inputs [:request :item-id])
                   :predecessor-event-id (:event/id previous) :gate-start-id (:event/id start)
                   :transport transport :owned? owned?
                   :tick-id tick-id :budget-root-tick-id root-id
                   :root-deadline-ms (:execution-deadline-ms root-options)
                   :candidate-deadline-ms (get-in frozen [:options :execution-deadline-ms])
                   :root-call-budget (:llm-call-budget root-options)
                   :candidate-call-budget (get-in frozen [:options :llm-call-budget])}]
    ;; Test-only observation capability; authorization comes from the store above.
    (when-let [captures (:fixture-admissions ctx)]
      (swap! captures conj {:admission admission :ownership ownership}))
    {:alternative-admission admission :alternative-allowed? allowed?}))

(defn build-package! [ctx]
  (let [candidate (fn [name model]
                    (orc/llm name :model model :instruction instruction
                      :reads task-reads :writes task-writes
                      :retry {:max-attempts 1 :backoff-ms []} :options generation-options))
        task (orc/build-workflow! ctx
               (orc/workflow "scoped-same-task-alternative" (orc/blackboard board)
                 (orc/fallback "configured-model-candidates"
                   (candidate "primary" "fixture/primary")
                   (orc/sequence "gated-alternative"
                     (orc/code "read-own-primary-failure" :fn "ai.obney.orc.orc-service.scoped-model-alternative-e2e-test/read-exact-failure"
                       :reads task-reads :writes [:alternative-admission :alternative-allowed?])
                     (orc/condition "eligible-recorded-failure" :check {:key :alternative-allowed? :op :equals :value true})
                     (candidate "alternative" "fixture/alternative")))))
        parent (orc/build-workflow! ctx
                 (orc/workflow "scoped-task-parent" (orc/blackboard board)
                   (orc/sequence "one-task-with-completed-tool"
                     (orc/code "complete-tool-once" :fn "ai.obney.orc.orc-service.scoped-model-alternative-e2e-test/completed-tool"
                       :reads [:request] :writes [:tool-receipt])
                     (orc/delegate "same-task" :target-sheet-id task :reads task-reads :writes task-writes :timeout-ms 60000))))]
    {:parent parent :task task}))

(defn wire-inputs [body]
  (let [message (get-in body [:messages 0 :content])
        input-section (second (str/split message #"Given the following inputs:\n" 2))
        input-section (first (str/split (or input-section "") #"\n\nOutput contract" 2))]
    (into {} (for [part (str/split input-section #"\n\n(?=[A-Za-z0-9_-]+:)")
                  :let [[k v] (str/split part #": " 2)] :when v]
               [(keyword k) (json/parse-string v true)]))))

(defn start-server! []
  (let [requests (atom []) errors (atom [])
        barrier (CountDownLatch. 2)
        executor (Executors/newFixedThreadPool 3)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server executor)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  inputs (wire-inputs body) id (get-in inputs [:request :item-id])
                  model (:model body) entered (System/nanoTime)
                  pair? (and (= model "fixture/primary") (#{"TASK-A" "TASK-B"} id))
                  _ (when pair? (.countDown barrier))
                  overlapped? (when pair? (.await barrier 10 TimeUnit/SECONDS))
                  row {:model model :inputs inputs :body body :entered-ns entered
                       :released-ns (System/nanoTime) :both-primary-requests-present? overlapped?}
                  _ (swap! requests conj row)
                  success! (fn [answer]
                             (http-fixture/reply! exchange 200 "application/json"
                               (json/generate-string
                                 {:id (str "fixture/" id "/" model) :model model
                                  :choices [{:index 0 :finish_reason "stop"
                                             :message {:role "assistant"
                                                       :content (json/generate-string
                                                                  {:answer-reasoning "The controlled provider evaluated this exact fixture item."
                                                                   :item-id id :answer answer})}}]
                                  :usage {:prompt_tokens 12 :completion_tokens 12 :total_tokens 24}})))]
              (cond
                (= model "fixture/alternative") (success! false)
                (= id "TASK-B") (success! false)
                (= id "SCHEMA") (success! "invalid-boolean")
                (= id "QUOTA") (http-fixture/reply! exchange 429 "application/json" "{\"error\":{\"message\":\"fixture quota exceeded\"}}")
                (= id "AUTH") (http-fixture/reply! exchange 401 "application/json" "{\"error\":{\"message\":\"fixture authentication rejected\"}}")
                (#{"TASK-A" "BUDGET"} id) (http-fixture/reply! exchange 502 "text/html" "<html>fixture gateway unavailable</html>")
                :else (throw (ex-info "Unknown fixture item" {}))))
            (catch Throwable e
              (swap! errors conj {:class (.getName (class e))})
              (try (http-fixture/reply! exchange 500 "text/plain" "fixture handler failed") (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :executor executor :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn execute-task [ctx package item-id budget]
  (let [tick-id (random-uuid)
        result (orc/execute ctx (:parent package) {:request {:item-id item-id :question "Does the item satisfy the check?"}}
                            :tick-id tick-id :timeout-ms 60000 :max-ticks 1 :llm-call-budget budget)
        child-ids (distinct (map :tick-id (filter #(= tick-id (:parent-tick-id %))
                                                (orc/get-execution-family ctx tick-id))))
        children (mapv #(orc/get-tick ctx %) child-ids)
        ticks (cons (orc/get-tick ctx tick-id) children)
        families (mapv (fn [tick]
                         (let [id (:id tick)
                               events (orc/value-log-read-tick-events ctx (:tenant-id ctx) id)
                               _ (h/settle-until! #(some? (get-in (h/run-query ctx (h/make-get-trace-query id)) [:query/result :trace])))
                               trace (get-in (h/run-query ctx (h/make-get-trace-query id)) [:query/result :trace])
                               completions (filterv #(= :sheet/node-execution-completed (:event/type %)) events)]
                           {:tick tick :context (orc/get-tick-execution-context ctx id) :events events :trace trace
                            :io (mapv (fn [e] {:completion e
                                               :reads (orc/value-log-resolve-reads ctx (:tenant-id ctx) id e)
                                               :writes (orc/value-log-resolve-writes ctx (:tenant-id ctx) id e)}) completions)})) ticks)]
    {:item-id item-id :root-tick-id tick-id :result result :families families}))

(defn model-rows [run]
  (filter #(contains? #{"fixture/primary" "fixture/alternative"} (get-in % [:completion :requested-model]))
          (mapcat :io (:families run))))

(deftest det-e2e-323-only-the-owning-task-admits-its-temporary-failure
  (let [server (start-server!) effects (atom {}) admissions (atom [])]
    (try
      (llm/register-provider! provider {:provider :openrouter :model "fixture/primary"
                                      :config {:api-base (:url server) :api-key http-fixture/fixture-token :timeout 15000}})
      (let [runs
            (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider provider
                                                       :fixture-effects effects :fixture-admissions admissions}}]
              (let [package (build-package! ctx)
                    a (future (execute-task ctx package "TASK-A" 2))
                    b (future (execute-task ctx package "TASK-B" 2))
                    pair [@a @b]
                    controls (mapv #(execute-task ctx package % (if (= % "BUDGET") 1 2)) ["QUOTA" "AUTH" "SCHEMA" "BUDGET"])]
                (vec (concat pair controls))))
            by-id (into {} (map (juxt :item-id identity) runs))
            requests @(:requests server)
            models-for #(mapv :model (filter (fn [r] (= % (get-in r [:inputs :request :item-id]))) requests))
            a (get by-id "TASK-A") b (get by-id "TASK-B")
            paired (filter :both-primary-requests-present? requests)
            ownership (:ownership (first (filter #(= "TASK-A" (get-in % [:admission :task-id])) @admissions)))]
        (reset! last-proof (http-fixture/readable-snapshot {:runs runs :requests requests :effects @effects
                                                         :admissions @admissions :server-errors @(:errors server)
                                                         :paid-provider-calls 0 :event-store :in-memory}))
        (is (empty? @(:errors server)))
        (is (= 7 (count requests)))
        (is (= 2 (count paired)) "Both primary HTTP handlers were present before either response")
        (is (= ["fixture/primary" "fixture/alternative"] (models-for "TASK-A")))
        (is (= ["fixture/primary"] (models-for "TASK-B")))
        (doseq [request requests
                :let [id (get-in request [:inputs :request :item-id])
                      rows (filter #(= (:model request) (get-in % [:completion :requested-model]))
                                   (model-rows (get by-id id)))]]
          (is (= 1 (count rows)) "Each unique typed item/model pair has one canonical invocation")
          (is (= (:inputs request) (:reads (first rows))) "The exact HTTP task and receipt equal canonical inputs"))
        (doseq [run [a b]]
          (is (= :success (get-in run [:result :status])))
          (is (= (:item-id run) (get-in run [:result :outputs :item-id])))
          (is (false? (get-in run [:result :outputs :answer]))))
        (doseq [id ["QUOTA" "AUTH" "SCHEMA" "BUDGET"]]
          (is (= ["fixture/primary"] (models-for id)))
          (is (= :failure (get-in by-id [id :result :status]))))
        (doseq [[id type status] [["QUOTA" :litellm/quota-exceeded 429]
                                 ["AUTH" :litellm/authentication-error 401]]
                :let [completion (:completion (first (model-rows (get by-id id))))]]
          (is (= :transport-failure (:failure-kind completion)))
          (is (= {:type type :http-status status} (get-in completion [:provider-evidence :transport]))))
        (is (= :schema-validation-failed
               (get-in (first (model-rows (get by-id "SCHEMA"))) [:completion :failure-kind])))
        (is (= "LLM call budget exceeded: 1/1" (get-in by-id ["BUDGET" :result :error])))
        (is (= (zipmap ["TASK-A" "TASK-B" "QUOTA" "AUTH" "SCHEMA" "BUDGET"] (repeat 1)) @effects))
        (doseq [run runs
                :let [rows (vec (model-rows run)) root (first (:families run)) child (second (:families run))
                      root-options (get-in root [:context :options]) child-options (get-in child [:context :options])
                      starts (filter #(= :sheet/tree-tick-started (:event/type %)) (get child :events))]]
          (is (= 2 (count (:families run))))
          (is (= 1 (count starts)) "Alternative stays in the original delegated task tick")
          (is (= (:root-tick-id run) (:llm-budget-root-tick-id child-options)))
          (is (= (:llm-call-budget root-options) (:llm-call-budget child-options)))
          (is (= (:execution-deadline-ms root-options)
                 (get-in (first (filter #(= :sheet/tree-tick-started (:event/type %)) (:events root))) [:options :execution-deadline-ms])))
          (doseq [row rows]
            (is (= (:item-id run) (get-in row [:reads :request :item-id])))
            (is (= {:operation-id (str "completed-tool/" (:item-id run)) :item-id (:item-id run) :ordinal 1}
                   (get-in row [:reads :tool-receipt])))))
        (let [rows (vec (model-rows a)) root (first (:families a)) child (second (:families a))
              admission (:admission (first (filter #(= "TASK-A" (get-in % [:admission :task-id])) @admissions)))
              child-start (first (filter #(= :sheet/tree-tick-started (:event/type %)) (:events child)))]
          (is (= 2 (count rows)))
          (is (apply = (map :reads rows)))
          ;; Deadlines live in frozen tick options and the executor's ephemeral
          ;; attempt state; durable node traces do not project that field.
          (is (and (integer? (:candidate-deadline-ms admission)) (pos? (:candidate-deadline-ms admission))))
          (is (and (integer? (:root-deadline-ms admission)) (pos? (:root-deadline-ms admission))))
          (is (= (:candidate-deadline-ms admission)
                 (get-in child-start [:options :execution-deadline-ms])
                 (get-in child [:context :options :execution-deadline-ms])))
          (is (= (:root-deadline-ms admission) (get-in root [:context :options :execution-deadline-ms])))
          (is (= 2 (:root-call-budget admission) (:candidate-call-budget admission))))
        (is (own-predecessor? ownership))
        (is (not= (orc/value-log-exec-context (get-in ownership [:previous :inputs]))
                  (orc/value-log-exec-context
                   (assoc (get-in ownership [:previous :inputs])
                          :ai.obney.orc.orc-service.core.todo-processors/map-each-index 1))))
        (doseq [bad [(assoc ownership :previous nil)
                     (assoc-in ownership [:previous :tick-id] (random-uuid))
                     (assoc-in ownership [:previous :inputs :ai.obney.orc.orc-service.core.todo-processors/map-each-index] 1)
                     (assoc ownership :previous-index (:start-index ownership))
                     (assoc-in ownership [:previous-inputs :request :item-id] "UNRELATED")]]
          (is (false? (own-predecessor? bad))))
        (doseq [transport [{:http-status 429} {:type :litellm/quota-exceeded :http-status 429}
                           {:type :litellm/authentication-error :http-status 503}
                           {:type :litellm/server-error} {:type :unknown/error :http-status 503}
                           {:type :litellm/rate-limit :http-status 401}]]
          (is (false? (temporary-transport? transport))))
        (doseq [transport [{:type :litellm/rate-limit :http-status 429} {:type :litellm/timeout}
                           {:type :litellm/connection-error} {:type :litellm/server-error :http-status 502}]]
          (is (true? (temporary-transport? transport)))))
      (finally
        (router/unregister! provider)
        (http-fixture/stop-server! server)))))
