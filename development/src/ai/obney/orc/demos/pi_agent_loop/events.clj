(ns ai.obney.orc.demos.pi-agent-loop.events
  "Rendering helpers for placing Pi lifecycle beside ORC tick lineage."
  (:require [ai.obney.grain.event-store-v3.interface :as event-store]))

(defn tick-events [context tick-id]
  (into [] (event-store/read (:event-store context)
                             {:tenant-id (:tenant-id context)
                              :tags #{[:tick tick-id]}})))

(defn correlation-events [context correlation-id]
  (into [] (event-store/read (:event-store context)
                             {:tenant-id (:tenant-id context)
                              :tags #{[:correlation correlation-id]}})))

(defn durable-summary [context loop-result]
  (let [tick-ids (->> (:new-messages loop-result)
                      (keep #(get-in % [:details :orc/trace-id]))
                      vec)]
    {:harness-events (:events loop-result)
     :transcript (:new-messages loop-result)
     :ticks (mapv (fn [tick-id]
                    {:tick-id tick-id
                     :events (tick-events context tick-id)})
                  tick-ids)}))
