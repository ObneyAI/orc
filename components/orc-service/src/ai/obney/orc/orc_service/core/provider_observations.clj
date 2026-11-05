(ns ai.obney.orc.orc-service.core.provider-observations
  "Command-owned provider attempt observations, separate from blackboard/accounting."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp :refer [defcommand]]
            [ai.obney.grain.query-processor.interface :as qp :refer [defquery]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [cognitect.anomalies :as anom]))

(def owner-keys [:sheet-id :tick-id :node-id :node-start-id :exec-context
                 :node-attempt :provider-attempt :invocation-id])
(def owner-fields [[:sheet-id :uuid] [:tick-id :uuid] [:node-id :uuid] [:node-start-id :uuid]
                   [:exec-context [:fn map?]] [:node-attempt [:int {:min 1}]]
                   [:provider-attempt [:int {:min 1}]] [:invocation-id :uuid]])
(def observation-schema
  [:map {:closed true}
   [:provider :string]
   [:response-id {:optional true} [:maybe :string]]
   [:model {:optional true} [:maybe :string]]
   [:usage {:optional true} [:maybe [:fn map?]]]
   [:choices {:optional true}
    [:vector [:map {:closed true}
              [:index {:optional true} [:maybe :int]]
              [:finish-reason {:optional true} [:maybe [:or :keyword :string]]]
              [:reasoning-content {:optional true} [:maybe :string]]
              [:reasoning-details {:optional true} [:maybe [:vector [:fn map?]]]]
              [:refusal {:optional true} [:maybe :string]]]]]])
(def outcome-schema
  [:map {:closed true}
   [:status [:enum :success :failure :timeout :cancelled]]
   [:failure-kind {:optional true} :keyword]
   [:provider-observations {:optional true} observation-schema]])
(defschemas provider-observation-commands
  {:sheet/start-provider-attempt (into [:map] owner-fields)
   :sheet/complete-provider-attempt (into [:map] (conj owner-fields [:outcome outcome-schema]))})
(defschemas provider-observation-events
  {:sheet/provider-attempt-started (into [:map] owner-fields)
   :sheet/provider-attempt-completed (into [:map] (conj owner-fields [:outcome outcome-schema]))})
(defschemas provider-observation-queries
  {:sheet/provider-attempts [:map [:tick-id :uuid] [:node-start-id :uuid]
                            [:invocation-id {:optional true} :uuid]]
   :sheet/provider-attempts-result [:vector [:fn map?]]})

(def event-types #{:sheet/provider-attempt-started :sheet/provider-attempt-completed})
(defn authorized? [ctx] (and (some? (:tenant-id ctx)) (some? (:auth-claims ctx))))
(defn- rejected [category message] {::anom/category category ::anom/message message})
(defn- invocation-events [ctx invocation-id]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                      :tags #{[:provider-invocation invocation-id]}
                                      :types event-types})))
(defn- canonical-start [ctx tick-id node-start-id]
  (some #(when (= node-start-id (:event/id %)) %)
        (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                            :tags #{[:tick tick-id]}
                                            :types #{:sheet/node-execution-started}}))))
(defn- owns-start? [ctx owner]
  (when-let [event (canonical-start ctx (:tick-id owner) (:node-start-id owner))]
    (and (= (select-keys owner [:sheet-id :tick-id :node-id])
            (select-keys event [:sheet-id :tick-id :node-id]))
         (= (:exec-context owner) (value-log/exec-context (:inputs event))))))
(defn- append-transition [type owner outcome predicate]
  {:command-result/events
   [(->event {:type type
              :tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)]
                      [:provider-node-start (:node-start-id owner)]
                      [:provider-invocation (:invocation-id owner)]}
              :body (cond-> owner outcome (assoc :outcome outcome))})]
   :command-result/cas {:tags #{[:provider-invocation (:invocation-id owner)]}
                        :types event-types :predicate-fn #(predicate (into [] %))}})

(defcommand :sheet start-provider-attempt
  {:authorized? authorized?}
  [ctx]
  (if-not (some? (:tenant-id ctx))
    (rejected ::anom/forbidden "Provider observation tenant is required")
    (let [owner (select-keys (:command ctx) owner-keys)
          events (invocation-events ctx (:invocation-id owner))
          started (first events)]
      (cond
        (not (owns-start? ctx owner)) (rejected ::anom/incorrect "Provider attempt does not own the canonical node start")
        started (if (= owner (select-keys started owner-keys))
                  {:command-result/events []}
                  (rejected ::anom/conflict "Provider invocation identity already belongs to another attempt"))
        :else (append-transition :sheet/provider-attempt-started owner nil empty?)))))

(defcommand :sheet complete-provider-attempt
  {:authorized? authorized?}
  [ctx]
  (if-not (some? (:tenant-id ctx))
    (rejected ::anom/forbidden "Provider observation tenant is required")
    (let [owner (select-keys (:command ctx) owner-keys)
          outcome (get-in ctx [:command :outcome])
          events (invocation-events ctx (:invocation-id owner))
          started (first events)
          completed (some #(when (= :sheet/provider-attempt-completed (:event/type %)) %) events)
          owns? (and started (= owner (select-keys started owner-keys)))]
      (cond
        (not owns?) (rejected ::anom/incorrect "Provider outcome requires its exact started attempt")
        completed (if (= outcome (:outcome completed))
                    {:command-result/events []}
                    (rejected ::anom/conflict "Provider attempt already has a different outcome"))
        :else (append-transition :sheet/provider-attempt-completed owner outcome
                                 #(and (= 1 (count %))
                                       (= owner (select-keys (first %) owner-keys))))))))

(defreadmodel :sheet provider-attempt-ledger
  {:events event-types :version 1}
  [state event]
  (let [id (:invocation-id event)]
    (case (:event/type event)
      :sheet/provider-attempt-started
      (-> state
          (update :order (fnil conj []) id)
          (assoc-in [:by-id id] (assoc (select-keys event owner-keys) :status :started)))
      :sheet/provider-attempt-completed
      (update-in state [:by-id id] assoc :status :completed :outcome (:outcome event))
      state)))

(defquery :sheet provider-attempts
  {:authorized? authorized?}
  [ctx]
  (if-not (some? (:tenant-id ctx))
    (rejected ::anom/forbidden "Provider observation tenant is required")
    (let [{:keys [tick-id node-start-id invocation-id]} (:query ctx)]
      (if-not (canonical-start ctx tick-id node-start-id)
        (rejected ::anom/not-found "Canonical node start is not present for this tenant and tick")
        (let [{:keys [order by-id]} (rmp/project ctx :sheet/provider-attempt-ledger
                                               {:tags #{[:provider-node-start node-start-id]}})]
          {:query/result (into [] (comp (map by-id)
                                        (filter #(and (= tick-id (:tick-id %))
                                                      (or (nil? invocation-id)
                                                          (= invocation-id (:invocation-id %)))))) order)})))))

(defn- invoke-command! [ctx command-name data]
  (let [call #(cp/process-command
               (assoc ctx :command (merge data {:command/name command-name :command/id (random-uuid)
                                                :command/timestamp (time/now)})))
        result (call)]
    ;; A competing identical transition may win CAS after our read. Re-enter the
    ;; registered command once: exact replay is idempotent; conflicting reuse fails.
    (if (= ::anom/conflict (::anom/category result)) (call) result)))
(defn start-provider-attempt! [ctx attempt] (invoke-command! ctx :sheet/start-provider-attempt attempt))
(defn complete-provider-attempt! [ctx attempt] (invoke-command! ctx :sheet/complete-provider-attempt attempt))
(defn provider-attempts [ctx execution]
  (qp/process-query (assoc ctx :query (merge execution {:query/name :sheet/provider-attempts
                                                       :query/id (random-uuid) :query/timestamp (time/now)}))))
