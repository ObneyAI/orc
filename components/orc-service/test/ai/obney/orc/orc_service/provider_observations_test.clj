(ns ai.obney.orc.orc-service.provider-observations-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]
            [cognitect.transit :as transit]
            [ai.obney.grain.command-request-handler-v2.interface :as command-adapter]
            [ai.obney.grain.query-request-handler.interface :as query-adapter]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(defn finish [_] {:done true})
(defn command [ctx name data]
  (cp/process-command (assoc ctx :command (merge data {:command/name name :command/id (random-uuid)
                                                     :command/timestamp (time/now)}))))
(defn query [ctx data]
  (qp/process-query (assoc ctx :query (merge data {:query/name :sheet/provider-attempts
                                                 :query/id (random-uuid) :query/timestamp (time/now)}))))
(defn execution [ctx]
  (let [sheet (bt/build-workflow! ctx
                (bt/workflow (str "observation-owner-" (random-uuid))
                  (bt/blackboard {:done [:boolean {:description "Completion marker."}]})
                  (bt/code "finish" :fn "ai.obney.orc.orc-service.provider-observations-test/finish" :writes [:done])))
        result (bt/execute ctx sheet {} :timeout-ms 30000 :max-ticks 1)
        started (first (into [] (es/read (:event-store ctx)
                                        {:tenant-id (:tenant-id ctx) :tags #{[:tick (:trace-id result)]}
                                         :types #{:sheet/node-execution-started}})))]
    (assert (= :success (:status result)))
    {:sheet-id (:sheet-id started) :tick-id (:tick-id started) :node-id (:node-id started)
     :node-start-id (:event/id started) :exec-context (select-keys (:inputs started)
       [:ai.obney.orc.orc-service.core.todo-processors/tick-iteration
        :ai.obney.orc.orc-service.core.todo-processors/map-each-index
        :ai.obney.orc.orc-service.core.todo-processors/map-each-parent])
     :node-attempt 1 :provider-attempt 1 :invocation-id (random-uuid)}))
(def observed {:provider "test" :response-id "response-1" :model "actual/model"
               :choices [{:index 0 :finish-reason :stop :reasoning-content nil
                          :reasoning-details [{:type "reasoning.encrypted" :data "opaque" :index 0}]
                          :refusal nil}]
               :usage {:completion-tokens 9 :completion-tokens-details {:reasoning_tokens 8} :cost 0.01}})

(deftest command-events-projection-query-retain-exact-observations
  (let [ctx (h/create-async-test-context {:context {:auth-claims {:sub "trusted-test"}}})]
    (try
      (let [owner (execution ctx)
            started (command ctx :sheet/start-provider-attempt owner)
            completed (command ctx :sheet/complete-provider-attempt
                               (assoc owner :outcome {:status :success :provider-observations observed}))
            rows (:query/result (query ctx (select-keys owner [:tick-id :node-start-id])))]
        (is (nil? (::anom/category started)))
        (is (nil? (::anom/category completed)))
        (is (= 1 (count rows)))
        (is (= observed (get-in rows [0 :outcome :provider-observations]))))
      (finally (h/stop-async-context ctx)))))

(defn invocation-events [ctx owner]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                      :tags #{[:provider-invocation (:invocation-id owner)]}
                                      :types #{:sheet/provider-attempt-started :sheet/provider-attempt-completed}})))

(deftest replay-conflict-ownership-and-authority-through-public-capabilities
  (let [ctx (h/create-async-test-context {:context {:auth-claims {:sub "trusted-test"}}})]
    (try
      (let [owner (execution ctx)
            selection (select-keys owner [:tick-id :node-start-id])
            outcome (assoc owner :outcome {:status :failure :failure-kind :schema-validation-failed
                                           :provider-observations observed})
            denied (dissoc ctx :tenant-id)]
        (is (= ::anom/incorrect (::anom/category (bt/complete-provider-attempt! ctx outcome))))
        (is (= ::anom/forbidden (::anom/category (bt/start-provider-attempt! denied owner))))
        (is (= ::anom/forbidden (::anom/category (bt/provider-attempts denied selection))))
        (is (= ::anom/incorrect (::anom/category (bt/start-provider-attempt! ctx (assoc owner :node-id (random-uuid))))))
        (is (= ::anom/incorrect (::anom/category (bt/start-provider-attempt! ctx (assoc owner :exec-context {:wrong 1})))))
        (is (= ::anom/incorrect (::anom/category (bt/start-provider-attempt! (assoc ctx :tenant-id (random-uuid)) owner))))
        (is (empty? (invocation-events ctx owner)))
        (is (nil? (::anom/category (bt/start-provider-attempt! ctx owner))))
        (is (nil? (::anom/category (bt/start-provider-attempt! ctx owner))))
        (is (= 1 (count (invocation-events ctx owner))))
        (is (= :started (get-in (bt/provider-attempts ctx selection) [:query/result 0 :status])))
        (is (not (contains? (first (:query/result (bt/provider-attempts ctx selection))) :outcome)))
        (is (= ::anom/conflict (::anom/category (bt/start-provider-attempt! ctx (assoc owner :provider-attempt 2)))))
        (is (nil? (::anom/category (bt/complete-provider-attempt! ctx outcome))))
        (is (nil? (::anom/category (bt/complete-provider-attempt! ctx outcome))))
        (is (= 2 (count (invocation-events ctx owner))))
        (is (= ::anom/conflict (::anom/category (bt/complete-provider-attempt! ctx (assoc-in outcome [:outcome :status] :success)))))
        (is (= observed (get-in (bt/provider-attempts ctx selection) [:query/result 0 :outcome :provider-observations])))
        (is (= ::anom/forbidden (::anom/category (bt/complete-provider-attempt! denied outcome))))
        (is (= ::anom/not-found (::anom/category (bt/provider-attempts (assoc ctx :tenant-id (random-uuid)) selection))))
        (let [second-owner (assoc owner :invocation-id (random-uuid))]
          (is (nil? (::anom/category (bt/start-provider-attempt! ctx second-owner))))
          (is (nil? (::anom/category (bt/complete-provider-attempt! ctx (assoc second-owner :outcome {:status :success})))))
          (let [rows (:query/result (bt/provider-attempts ctx selection))]
            (is (= [(:invocation-id owner) (:invocation-id second-owner)] (mapv :invocation-id rows)))
            (is (= [:failure :success] (mapv #(get-in % [:outcome :status]) rows)))
            (is (= [1 1] (mapv :provider-attempt rows)) "Invocation UUID, not ordinal alone, owns the physical call")
            (is (= [(:invocation-id second-owner)]
                   (mapv :invocation-id (:query/result (bt/provider-attempts ctx (assoc selection :invocation-id (:invocation-id second-owner)))))))
            (let [fresh (h/create-test-context)]
              (try
                (is (= rows (:query/result (bt/provider-attempts
                                            (assoc fresh :event-store (:event-store ctx)
                                                         :tenant-id (:tenant-id ctx)
                                                         :auth-claims (:auth-claims ctx)) selection))))
                (finally (h/stop-context fresh))))
            (is (not (contains? (:outcome (second rows)) :provider-observations))))))
      (finally (h/stop-async-context ctx)))))

(deftest concurrent-identical-transition-is-idempotent
  (let [ctx (h/create-async-test-context {:context {:auth-claims {:sub "trusted-test"}}})]
    (try
      (let [owner (execution ctx)
            release (promise)
            jobs (doall (repeatedly 8 #(future @release (bt/start-provider-attempt! ctx owner))))]
        (deliver release true)
        (is (every? #(nil? (::anom/category @%)) jobs))
        (is (= 1 (count (invocation-events ctx owner))))
        (let [release (promise)
              data (assoc owner :outcome {:status :success :provider-observations observed})
              jobs (doall (repeatedly 8 #(future @release (bt/complete-provider-attempt! ctx data))))]
          (deliver release true)
          (is (every? #(nil? (::anom/category @%)) jobs))
          (is (= 2 (count (invocation-events ctx owner))))))
      (finally (h/stop-async-context ctx)))))

(deftest rejected-envelope-cannot-store-request-dumps
  (let [ctx (h/create-async-test-context {:context {:auth-claims {:sub "trusted-test"}}})]
    (try
      (let [owner (execution ctx)]
        (bt/start-provider-attempt! ctx owner)
        (is (some? (::anom/category
                     (bt/complete-provider-attempt! ctx
                       (assoc owner :outcome {:status :success
                                              :provider-observations (assoc observed :request {:headers {}})})))))
        (is (= 1 (count (invocation-events ctx owner))))
        (is (= :started (get-in (bt/provider-attempts ctx (select-keys owner [:tick-id :node-start-id]))
                                [:query/result 0 :status]))))
      (finally (h/stop-async-context ctx)))))

(deftest embedded-ledger-uses-tenant-without-synthetic-auth-claims
  (h/with-async-test-context [ctx]
    (let [owner (execution ctx)
          selection (select-keys owner [:tick-id :node-start-id])
          outcome (assoc owner :outcome {:status :success :provider-observations observed})]
      (is (not (contains? ctx :auth-claims)))
      (is (nil? (::anom/category (bt/start-provider-attempt! ctx owner))))
      (is (nil? (::anom/category (bt/complete-provider-attempt! ctx outcome))))
      (is (= observed (get-in (bt/provider-attempts ctx selection)
                             [:query/result 0 :outcome :provider-observations])))
      (doseq [operation [#(bt/start-provider-attempt! % owner)
                         #(bt/complete-provider-attempt! % outcome)
                         #(bt/provider-attempts % selection)]]
        (is (= ::anom/forbidden (::anom/category (operation (dissoc ctx :tenant-id)))))))))

(defn- adapter-result [channel]
  (let [[result selected] (async/alts!! [channel (async/timeout 10000)])]
    (assert (= channel selected) "Request adapter exceeded its bounded response deadline")
    result))

(defn- command-request [ctx name data]
  (adapter-result
    (command-adapter/handle-command ctx
      {:request {:transit-params
                 {:command (merge data {:command/name name :command/id (random-uuid)
                                        :command/timestamp (time/now)})}}})))

(defn- query-request [ctx data]
  (adapter-result
    (query-adapter/handle-query ctx
      {:request {:transit-params
                 {:query (merge data {:query/name :sheet/provider-attempts
                                      :query/id (random-uuid) :query/timestamp (time/now)})}}})))

(deftest request-adapters-enforce-registered-observation-authority
  (h/with-async-test-context [ctx]
    (let [owner (execution ctx)
          selection (select-keys owner [:tick-id :node-start-id])
          outcome (assoc owner :outcome {:status :success :provider-observations observed})
          trusted (assoc ctx :auth-claims {:sub "request-adapter-test"})]
      (is (= 403 (get-in (command-request ctx :sheet/start-provider-attempt owner) [:response :status])))
      (is (= 403 (get-in (query-request ctx selection) [:response :status])))
      (is (empty? (invocation-events ctx owner)))
      (is (= 403 (get-in (command-request (dissoc trusted :tenant-id) :sheet/start-provider-attempt owner)
                        [:response :status])))
      (is (= 403 (get-in (query-request (dissoc trusted :tenant-id) selection) [:response :status])))
      (is (= 200 (get-in (command-request trusted :sheet/start-provider-attempt owner) [:response :status])))
      (is (= 403 (get-in (command-request ctx :sheet/complete-provider-attempt outcome) [:response :status])))
      (is (= 1 (count (invocation-events ctx owner))))
      (is (= 200 (get-in (command-request trusted :sheet/complete-provider-attempt outcome) [:response :status])))
      (let [response (:response (query-request trusted selection))
            body (transit/read (transit/reader
                                (java.io.ByteArrayInputStream. (.getBytes ^String (:body response) "UTF-8")) :json))]
        (is (= 200 (:status response)))
        (is (= observed (get-in body [0 :outcome :provider-observations])))))))
