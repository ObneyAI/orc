(ns ai.obney.orc.orc-service.core.periodic-tasks
  "Tenant-scoped durable triggers for ORC engine maintenance."
  (:require [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.periodic-task.interface :refer [defperiodic]]
            [ai.obney.orc.orc-service.core.recovery-scan :as recovery-scan]))

(defperiodic :sheet recover-active-executions
  {:schedule {:every 30 :duration :seconds}}
  "Prompt each tenant's ordinary todo processors to rediscover durable work.

   Grain interval schedules fire first at startup, then at the configured
   interval. The trigger itself contains no execution identity; recovery reads
   the tenant's authoritative in-progress projection when the event is handled."
  [tenant-id time]
  (when (recovery-scan/needed? tenant-id)
    {:result/events
     [(->event
       {:type :sheet/recovery-scan-triggered
        :body {:triggered-at (str time)}})]}))
