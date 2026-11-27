(ns ai.obney.orc.gepa.core.periodic-tasks
  "Periodic discovery triggers; applications own Grain scheduler startup."
  (:require [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.periodic-task.interface :refer [defperiodic]]))

(defperiodic :gepa recover-evaluations
  {:schedule {:every 30 :duration :seconds}}
  "Ask the tenant's processors to recover abandoned evaluation work."
  [_tenant-id instant]
  {:result/events [(->event {:type :gepa/recovery-scan-triggered
                            :body {:triggered-at (str instant)}})]})
