(ns ai.obney.orc.orc-service.deadline-model-alternative-e2e-test
  "DET-E2E-325: distinguish deadline propagation from expired-call admission.
   Controlled wall-clock barriers; no fake epoch or paid provider calls."
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [litellm.router :as router]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.ordinary-transport-failure-e2e-test :as http-fixture]
            [ai.obney.orc.orc-service.scoped-model-alternative-e2e-test :as scoped])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors]))

(def last-proof (atom {}))
(def provider :deadline-local-alternative)

(defn no-effect-child [_] {:out true})

(deftest c-public-execute-preserves-spent-cap-and-allows-local-tightening
  ;; These effect-free runs inspect persisted cap arithmetic only. Test B
  ;; separately proves that an expired provider attempt is refused.
  (let [samples (atom [])
        clock #(let [now (System/currentTimeMillis)] (swap! samples conj now) now)]
    (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :execution-now-ms-fn clock}}]
      (let [sheet (orc/build-workflow! ctx
                    (orc/workflow "public-deadline-caps"
                      (orc/blackboard {:out [:boolean {:description "The effect-free code returned."}]})
                      (orc/code "effect-free" :fn "ai.obney.orc.orc-service.deadline-model-alternative-e2e-test/no-effect-child"
                        :writes [:out])))]
        (doseq [kind [:spent :local-tighter]]
          (let [cap (+ (System/currentTimeMillis) (if (= kind :spent) -1000 120000))
                tick-id (random-uuid)
                result (orc/execute ctx sheet {} :tick-id tick-id :timeout-ms 60000
                                    :execution-deadline-ms cap :max-ticks 1)
                frozen (orc/get-tick-execution-context ctx tick-id)
                expected (if (= kind :spent) cap (+ (last @samples) 60000))]
            (swap! last-proof update :public-caps (fnil conj [])
                   {:kind kind :supplied-cap cap :clock-sample (last @samples)
                    :result result :context frozen
                    :events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)})
            (is (= :success (:status result)))
            (is (true? (get-in result [:outputs :out])))
            (is (= expected (get-in frozen [:options :execution-deadline-ms])))))))))

(deftest a-child-deadline-does-not-grow-across-controlled-dispatch-delay
  (let [samples (atom []) count-samples (atom 0)
        child-entered (promise) release-child (promise)
        clock (fn []
                (let [n (swap! count-samples inc)
                      entered (System/currentTimeMillis)]
                  (when (= n 2)
                    (deliver child-entered entered)
                    (when (= ::not-released (deref release-child 5000 ::not-released))
                      (throw (ex-info "Owned child clock barrier was not released" {}))))
                  (let [now (System/currentTimeMillis)]
                    (swap! samples conj {:sample n :entered-ms entered :returned-ms now})
                    now)))]
    (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :execution-now-ms-fn clock}}]
      (let [child (orc/build-workflow! ctx
                    (orc/workflow "deadline-propagation-child"
                      (orc/blackboard {:out [:boolean {:description "The effect-free child returned."}]})
                      (orc/code "effect-free-child"
                        :fn "ai.obney.orc.orc-service.deadline-model-alternative-e2e-test/no-effect-child"
                        :writes [:out])))
            parent (orc/build-workflow! ctx
                     (orc/workflow "deadline-propagation-parent"
                       (orc/blackboard {:out [:boolean {:description "The effect-free delegated result."}]})
                       (orc/delegate "child" :target-sheet-id child :writes [:out] :timeout-ms 120000)))
            tick-id (random-uuid)
            pending (future (orc/execute ctx parent {} :tick-id tick-id :timeout-ms 60000 :max-ticks 1))]
        (try
          (let [entered (deref child-entered 10000 ::not-entered)]
            (is (integer? entered) "The child reached the existing real-clock capability")
            ;; This is the intentional scheduling gap, not a model timeout.
            (Thread/sleep 250)
            (deliver release-child true)
            (let [result (deref pending 15000 ::not-completed)
                  family (orc/get-execution-family ctx tick-id)
                  child-ids (distinct (map :tick-id (filter #(= tick-id (:parent-tick-id %)) family)))
                  child-id (when (= 1 (count child-ids)) (first child-ids))
                  root-context (orc/get-tick-execution-context ctx tick-id)
                  child-context (when child-id (orc/get-tick-execution-context ctx child-id))
                  root-deadline (get-in root-context [:options :execution-deadline-ms])
                  child-deadline (get-in child-context [:options :execution-deadline-ms])
                  child-sample (first (filter #(= 2 (:sample %)) @samples))]
              (swap! last-proof assoc :propagation
                     {:result result :samples @samples :root-tick-id tick-id :child-tick-id child-id
                      :root-context root-context :child-context child-context
                      :root-events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)
                      :child-events (when child-id (orc/value-log-read-tick-events ctx (:tenant-id ctx) child-id))
                      :http-requests 0})
              (is (= :success (:status result)))
              (is (= true (get-in result [:outputs :out])))
              (is (= 2 (count @samples)))
              (is (= 1 (count child-ids)))
              (is (and (integer? (:entered-ms child-sample))
                       (integer? (:returned-ms child-sample))
                       (>= (- (:returned-ms child-sample) (:entered-ms child-sample)) 250)))
              (is (and (integer? root-deadline) (pos? root-deadline)))
              (is (and (integer? child-deadline) (pos? child-deadline)))
              (is (and (integer? root-deadline) (integer? child-deadline)
                       (<= child-deadline root-deadline))
                  "A delayed child must not reconstruct a later absolute deadline")))
          (finally
            (deliver release-child true)
            (when-not (future-done? pending) (future-cancel pending))))))))

(defn wait-for-original-expiry [{:keys [tick-id fixture-expiry-notes] :as ctx}]
  (let [deadline (get-in (orc/get-tick-execution-context ctx tick-id) [:options :execution-deadline-ms])
        entered (System/currentTimeMillis)]
    (when-not (and (integer? deadline) (< entered deadline))
      (throw (ex-info "Expiry fixture did not reach its gate before the original deadline" {})))
    (loop []
      (let [remaining (- deadline (System/currentTimeMillis))]
        (when (not (neg? remaining))
          (Thread/sleep (min 100 (inc remaining)))
          (recur))))
    (let [released (System/currentTimeMillis)
          admission (scoped/read-exact-failure ctx)]
      (swap! fixture-expiry-notes conj {:tick-id tick-id :deadline-ms deadline
                                      :entered-ms entered :released-ms released
                                      :admission (:alternative-admission admission)})
      admission)))

(defn start-server! []
  (let [requests (atom []) errors (atom [])
        executor (Executors/newFixedThreadPool 2)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server executor)
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [body (json/parse-string (slurp (.getRequestBody exchange)) true)
                  model (:model body)]
              (swap! requests conj {:body body :model model :inputs (scoped/wire-inputs body)
                                    :response-issued-at-ms (System/currentTimeMillis)})
              (case model
                "fixture/primary" (http-fixture/reply! exchange 502 "text/html" "<html>fixture unavailable</html>")
                "fixture/alternative"
                (http-fixture/reply! exchange 200 "application/json"
                                    (json/generate-string
                                     {:id "unexpected-expired-alternative" :model model
                                      :choices [{:index 0 :finish_reason "stop"
                                                 :message {:role "assistant"
                                                           :content (json/generate-string
                                                                     {:answer-reasoning "The fixture received an expired request."
                                                                      :item-id "EXPIRED" :answer false})}}]}))
                (throw (ex-info "Unexpected deadline fixture model" {}))))
            (catch Throwable e
              (swap! errors conj {:class (.getName (class e))})
              (try (http-fixture/reply! exchange 500 "text/plain" "fixture failed") (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :executor executor :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn expiry-workflow! [ctx]
  (let [candidate (fn [name model]
                    (orc/llm name :model model :instruction scoped/instruction
                      :reads scoped/task-reads :writes scoped/task-writes
                      :retry {:max-attempts 1 :backoff-ms []}
                      :options (assoc scoped/generation-options :timeout-ms 30000)))]
    (orc/build-workflow! ctx
      (orc/workflow "original-deadline-alternative"
        (orc/blackboard scoped/board)
        (orc/sequence "same-task-with-one-completed-tool"
          (orc/code "completed-tool" :fn "ai.obney.orc.orc-service.scoped-model-alternative-e2e-test/completed-tool"
            :reads [:request] :writes [:tool-receipt])
          (orc/fallback "model-candidates"
            (candidate "primary" "fixture/primary")
            (orc/sequence "eligible-but-expired"
              (orc/code "read-own-primary-failure"
                :fn "ai.obney.orc.orc-service.deadline-model-alternative-e2e-test/wait-for-original-expiry"
                :reads scoped/task-reads :writes [:alternative-admission :alternative-allowed?])
              (orc/condition "eligible" :check {:key :alternative-allowed? :op :equals :value true})
              (candidate "alternative" "fixture/alternative"))))))))

(deftest b-expired-original-task-cannot-start-an-alternative-provider-attempt
  (let [server (start-server!) effects (atom {}) notes (atom [])]
    (try
      (llm/register-provider! provider {:provider :openrouter :model "fixture/primary"
                                       :config {:api-base (:url server) :api-key http-fixture/fixture-token}})
      (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider provider
                                                :fixture-effects effects :fixture-expiry-notes notes}}]
        (let [sheet (expiry-workflow! ctx) tick-id (random-uuid)
              deadline (+ (System/currentTimeMillis) 15000)
              command-result (h/run-command ctx
                               (assoc (h/make-tick-tree-command sheet :tick-id tick-id)
                                      :inputs {:request {:item-id "EXPIRED" :question "Does this item satisfy the check?"}}
                                      :options {:timeout-ms 15000 :execution-deadline-ms deadline
                                                :max-ticks 1 :llm-call-budget 2 :store-trace? true}))
              settled? (h/settle-until! #(#{:success :failure :timeout :cancelled}
                                          (:root-status (orc/get-tick ctx tick-id))) :timeout-ms 30000)
              events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick-id)
              frozen (orc/get-tick-execution-context ctx tick-id)
              nodes (:nodes-by-id frozen)
              alternative-id (:id (first (filter #(= "alternative" (:name %)) (vals nodes))))
              completions (filterv #(= :sheet/node-execution-completed (:event/type %)) events)
              primary (first (filter #(= "fixture/primary" (:requested-model %)) completions))
              alternative (first (filter #(= alternative-id (:node-id %)) completions))
              starts (filter #(and (= :sheet/node-execution-started (:event/type %))
                                    (= alternative-id (:node-id %))) events)
              provider-starts (filter #(= :sheet/provider-attempt-started (:event/type %)) events)
              gate (first @notes)]
          (swap! last-proof assoc :expiry
                 (http-fixture/readable-snapshot
                  {:command-result command-result :settled? settled? :events events :context frozen
                   :tick (orc/get-tick ctx tick-id) :primary primary :alternative alternative
                   :gate gate :requests @(:requests server) :server-errors @(:errors server)
                   :effects @effects :paid-provider-calls 0 :event-store :in-memory}))
          (is (not (contains? command-result :cognitect.anomalies/category)))
          (is settled?)
          (is (empty? @(:errors server)))
          (is (= ["fixture/primary"] (mapv :model @(:requests server))))
          (is (= {:type :litellm/server-error :http-status 502} (get-in primary [:provider-evidence :transport])))
          (is (= :failure (:status primary)))
          (is (= 1 (count @notes)))
          (is (and (integer? (:entered-ms gate)) (<= (+ (:entered-ms gate) 5000) deadline))
              "Primary and gate were ready with at least five seconds of setup headroom")
          (is (and (integer? (:released-ms gate)) (> (:released-ms gate) deadline)))
          (is (true? (get-in gate [:admission :allowed?])) "Ownership/temporary policy allowed; deadline independently refuses")
          (is (= 1 (count starts)))
          (is (= :timeout (:status alternative)))
          (is (= "Execution deadline exhausted before provider attempt" (:error alternative)))
          (is (nil? (:failure-kind alternative)) "Deadline expiry is not an outage classification")
          (is (= 1 (count provider-starts)))
          (is (not-any? #(= alternative-id (:node-id %)) provider-starts))
          (is (= {"EXPIRED" 1} @effects))
          (is (= deadline (get-in frozen [:options :execution-deadline-ms])))
          (is (= 2 (get-in frozen [:options :llm-call-budget])))))
      (finally
        (router/unregister! provider)
        (http-fixture/stop-server! server)))))
