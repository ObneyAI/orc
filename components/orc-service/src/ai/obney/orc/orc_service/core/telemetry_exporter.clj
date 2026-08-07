(ns ai.obney.orc.orc-service.core.telemetry-exporter
  "Bounded, failure-isolated export of durable ORC events.

   Event publication must never participate in workflow backpressure. Events
   enter a bounded local queue with `offer`; one worker exports them in source
   order and retries the current event before advancing. Every non-accepted
   event is therefore either retried or explicitly counted as dropped."
  (:require [ai.obney.grain.pubsub.interface :as pubsub]
            [clojure.core.async :as async]))

(defn- event-id [event]
  (:event/id event))

(defn- accepted? [response id]
  (contains? (set (:accepted-ids response)) id))

(defn start!
  "Start an exporter subscribed to `event-types`.

   `export-fn` receives a one-element vector and returns
   `{:accepted-ids #{event-id ...}}`. Throws and missing acknowledgements are
   retried up to `max-attempts`. Queue overflow and exhausted retries are
   visible in `stats`. Pub/sub callbacks only use non-blocking queue offer."
  [event-pubsub event-types export-fn
   & {:keys [capacity max-attempts]
      :or {capacity 256 max-attempts 3}}]
  {:pre [(pos? capacity) (pos? max-attempts) (seq event-types)]}
  (let [queue (java.util.concurrent.ArrayBlockingQueue. capacity)
        running? (atom true)
        stats (atom {:offered 0 :accepted 0 :retried 0 :dropped 0
                     :failures 0 :max-occupancy 0 :accepted-ids []
                     :dropped-ids []})
        chans (mapv (fn [event-type]
                      (let [ch (async/chan (async/sliding-buffer capacity))]
                        (pubsub/sub event-pubsub {:topic event-type :sub-chan ch})
                        (async/go-loop []
                          (when-let [event (async/<! ch)]
                            (let [offered? (.offer queue event)]
                              (swap! stats
                                     (fn [s]
                                       (cond-> (-> s
                                                   (update :offered inc)
                                                   (update :max-occupancy max (.size queue)))
                                         (not offered?)
                                         (-> (update :dropped inc)
                                             (update :dropped-ids conj (event-id event)))))))
                            (recur)))
                        ch))
                    event-types)
        worker (Thread.
                (fn []
                  (while (or @running? (not (.isEmpty queue)))
                    (when-let [event (.poll queue 50 java.util.concurrent.TimeUnit/MILLISECONDS)]
                      (loop [attempt 1]
                        (let [response (try
                                         (export-fn [event])
                                         (catch Throwable _
                                           (swap! stats update :failures inc)
                                           nil))
                              id (event-id event)]
                          (if (accepted? response id)
                            (swap! stats (fn [s]
                                           (-> s
                                               (update :accepted inc)
                                               (update :accepted-ids conj id))))
                            (if (< attempt max-attempts)
                              (do (swap! stats update :retried inc)
                                  (recur (inc attempt)))
                              (swap! stats (fn [s]
                                             (-> s
                                                 (update :dropped inc)
                                                 (update :dropped-ids conj id))))))))))))]
    (.setName worker "orc-telemetry-exporter")
    (.setDaemon worker true)
    (.start worker)
    {:queue queue :running? running? :stats stats :channels chans :worker worker
     :capacity capacity}))

(defn stats
  "Return an immutable exporter status snapshot."
  [exporter]
  (assoc @(:stats exporter)
         :occupancy (.size ^java.util.concurrent.ArrayBlockingQueue (:queue exporter))
         :capacity (:capacity exporter)
         :running? @(:running? exporter)))

(defn stop!
  "Stop accepting events and drain queued work within `timeout-ms`.
   Returns the final status with `:stopped?`."
  [exporter & {:keys [timeout-ms] :or {timeout-ms 2000}}]
  (doseq [ch (:channels exporter)] (async/close! ch))
  (reset! (:running? exporter) false)
  (.join ^Thread (:worker exporter) timeout-ms)
  (let [stopped? (not (.isAlive ^Thread (:worker exporter)))]
    (when-not stopped? (.interrupt ^Thread (:worker exporter)))
    (assoc (stats exporter) :stopped? stopped?)))
