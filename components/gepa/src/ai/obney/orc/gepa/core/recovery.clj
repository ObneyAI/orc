(ns ai.obney.orc.gepa.core.recovery
  "Tenant-scoped discovery of abandoned GEPA evaluation boundaries."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.gepa.core.task-ledger :as ledger])
  (:import [java.time Instant]))

(defn- due? [state now]
  (and (not (:terminal? state))
       (some (fn [[_ {:keys [boundary tasks]}]]
               (when-not (contains? (:completed-boundaries state) boundary)
                 (let [current (keep #(get-in state [:tasks (:call-id %)]) tasks)
                       healthy? (some #(and (not= :completed (:status %))
                                            (:lease-expires-at %)
                                            (.isAfter (Instant/parse (:lease-expires-at %)) now))
                                      current)]
                   (not healthy?))))
             (:boundaries state))))

(defcommand :gepa request-recovery
  {:authorized? (fn [ctx] (uuid? (:tenant-id ctx)))}
  "Request recovery through the ordinary processor pool, not an unmanaged future."
  [{{:keys [optimization-id]} :command :as ctx}]
  (let [state (ledger/get-state ctx optimization-id)]
    (if (and (:optimization-id state) (not (:terminal? state)))
      {:command-result/events
       [(->event {:type :gepa/optimization-recovery-requested
                  :tags #{[:optimization optimization-id]}
                  :body {:optimization-id optimization-id}})]
       :command/result {:status :requested :optimization-id optimization-id}}
      {:command-result/events [] :command/result {:status :not-active}})))

(defn recover-in-progress!
  "Discover abandoned task boundaries and request processor-owned recovery.
   Healthy task ownership and completed aggregates are left untouched."
  [ctx]
  (when-not (uuid? (:tenant-id ctx))
    (throw (ex-info "GEPA recovery requires a tenant UUID" {})))
  (let [now ((or (:gepa/clock-fn ctx) #(Instant/now)))
        ids (into #{} (map :optimization-id)
                  (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx) :types #{:gepa/optimization-started}}))]
    (into []
      (comp
        (filter #(due? (ledger/get-state ctx %) now))
        (map (fn [id]
               (cp/process-command
                 (assoc ctx :command {:command/id (random-uuid) :command/timestamp (time/now)
                                      :command/name :gepa/request-recovery :optimization-id id})))))
      ids)))
