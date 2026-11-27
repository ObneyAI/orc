(ns ai.obney.orc.orc-service.core.periodic-tasks
  "Tenant-scoped durable triggers for ORC engine maintenance."
  (:require [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.periodic-task.interface :refer [defperiodic]]
            [ai.obney.orc.orc-service.core.logic :as logic]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [com.brunobonacci.mulog :as u]))

(defn abandoned-work?
  "True when `resume-in-progress!` would resume at least one start now, by the
   same lease decision, or rebuild a map coordinator it has not yet resumed.
   Reads the cached in-progress projection first (idle is empty) and the durable
   events only of ticks that are still running."
  [context]
  (boolean
   (some
    (fn [{:keys [tick-id nodes-in-progress]}]
      (let [nodes-by-id (:nodes-by-id (rm/get-tick-execution-context context tick-id))
            tick-events (into [] (es/read (:event-store context)
                                          {:tenant-id (:tenant-id context)
                                           :tags #{[:tick tick-id]}}))
            {:keys [open-starts renewals-by-start]} (logic/tick-open-starts tick-events)]
        (or (logic/unresumed-map-coordinator? nodes-by-id nodes-in-progress tick-events)
            (some #(logic/recoverable-start? context nodes-by-id renewals-by-start %)
                  open-starts))))
    (rm/get-all-active-executions context))))

(defn needed?
  "True when the periodic scan should record its trigger for `tenant-id`."
  [tenant-id]
  (if-let [f @logic/context-fn]
    (try
      (if-let [context (f tenant-id)]
        (boolean (abandoned-work? context))
        true)
      (catch Throwable t
        (u/log :ai.obney.orc.orc-service.core.recovery-scan/recovery-scan-state-unreadable :tenant-id tenant-id
               :error (ex-message t))
        true))
    true))

(defperiodic :sheet recover-active-executions
  {:schedule {:every 30 :duration :seconds}}
  "Prompt each tenant's ordinary todo processors to rediscover durable work.

   Grain interval schedules fire first at startup, then at the configured
   interval. The trigger itself contains no execution identity; recovery reads
   the tenant's authoritative in-progress projection when the event is handled."
  [tenant-id time]
  (when (needed? tenant-id)
    {:result/events
     [(->event
       {:type :sheet/recovery-scan-triggered
        :body {:triggered-at (str time)}})]}))
