(ns ai.obney.orc.evaluation.core.alerts
  "Opt-in performance alerts (ThresholdWatchingIsOptIn).

   Only a judge that DECLARED an alert is watched. Its terminal outcomes carry the
   alert, so the stage that reacts to outcomes knows whether to watch without
   reading anything: a judge with no alert costs this stage nothing and emits
   nothing.

   For a watched judge, each outcome evaluates the trailing WINDOW of the node
   version's outcomes under that judge revision: the last `:window` outcomes, as
   they stood at that outcome (never later ones, so the verdict is the same on
   replay and however fast outcomes arrive). A window not yet full says nothing.
   A full window is then judged:

     coverage < min-coverage          -> coverage DEGRADED (not poor performance)
     coverage ok, mean < below        -> threshold CROSSED
     coverage ok, mean >= below       -> RECOVERED after a crossing, coverage
                                         RESTORED after degraded coverage

   Each signal is recorded once per episode: the next is decided against the last
   signal of the cell, and the append is fenced by a CAS on that last signal, so
   concurrent or repeated delivery cannot record a second one. A signal never
   starts training by itself; nothing subscribes to it here."
  (:require [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.evaluation.core.assessments :as assessments]
            [ai.obney.orc.evaluation.core.performance :as performance]))

(def signal-types
  #{:evaluation/performance-threshold-crossed
    :evaluation/performance-threshold-recovered
    :evaluation/performance-coverage-degraded
    :evaluation/performance-coverage-restored})

(def ^:private signal-state
  "What the last signal of a cell says about it now."
  {:evaluation/performance-threshold-crossed :crossed
   :evaluation/performance-coverage-degraded :degraded
   :evaluation/performance-threshold-recovered :healthy
   :evaluation/performance-coverage-restored :healthy})

(defn window-stats
  "The statistics of the last `window` outcomes of `series`, or nil while there are
   fewer than `window`."
  [series window]
  (when (>= (count series) window)
    (let [in-window (take-last window series)
          scores (keep #(when (= :scored (:status %)) (:score %)) in-window)]
      {:window window
       :scored (count scores)
       :trailing-score (when (seq scores) (/ (reduce + 0.0 scores) (count scores)))
       :coverage-ratio (/ (double (count scores)) window)})))

(defn next-signal
  "The signal (an event type) the window calls for given the cell's `state`
   (:crossed, :degraded or :healthy), or nil when nothing changed."
  [{:keys [below min-coverage]} {:keys [trailing-score coverage-ratio]} state]
  (cond
    (< coverage-ratio min-coverage)
    (when (not= :degraded state) :evaluation/performance-coverage-degraded)

    (< trailing-score below)
    (when (not= :crossed state) :evaluation/performance-threshold-crossed)

    :else
    (case state
      :crossed :evaluation/performance-threshold-recovered
      :degraded :evaluation/performance-coverage-restored
      nil)))

(defn- series-through
  "The outcomes of the cell up to and including the one of `assessment-id`, or nil
   when it is not among them."
  [series assessment-id]
  (let [idx (first (keep-indexed #(when (= assessment-id (:assessment-id %2)) %1) series))]
    (when idx (subvec (vec series) 0 (inc idx)))))

(defn- last-signal [{:keys [event-store tenant-id]} cell-id]
  (last (into [] (es/read event-store {:types signal-types
                                       :tags #{[:performance-cell cell-id]}
                                       :tenant-id tenant-id}))))

(defn on-assessment-outcome
  "Handler for a terminal assessment event: the pure result (event + CAS) of
   watching its judge's alert, or nil. nil at once, with no read, when the
   outcome carries no alert."
  [{:keys [event] :as context}]
  (when-let [alert (:alert event)]
    (let [k (performance/cell-key event)
          cell-id (performance/cell-id k)
          series (some-> (performance/cell context k) :series
                         (series-through (:assessment-id event)))
          stats (when series (window-stats series (:window alert)))]
      (when stats
        (let [previous (last-signal context cell-id)
              state (get signal-state (:event/type previous) :healthy)
              signal (next-signal alert stats state)]
          (when signal
            (let [previous-id (:event/id previous)]
              {:result/events
               [(es/->event
                 {:type signal
                  :tags #{[:sheet (:sheet-id event)] [:node (:node-id event)]
                          [:performance-cell cell-id] [:assessment (:assessment-id event)]}
                  :body (cond-> (merge {:sheet-id (:sheet-id event)
                                        :node-id (:node-id event)
                                        :judge-name (:judge-name event)
                                        :judge-revision-number (:judge-revision-number event)
                                        :assessment-id (:assessment-id event)
                                        :alert alert
                                        :signalled-at (str (time/now))}
                                       (select-keys stats [:window :scored :coverage-ratio]))
                          (:node-version event) (assoc :node-version (:node-version event))
                          (some? (:trailing-score stats)) (assoc :trailing-score (:trailing-score stats)))})]
               :result/cas
               {:types signal-types
                :tags #{[:performance-cell cell-id]}
                :predicate-fn (fn [existing]
                                (= previous-id (:event/id (last (into [] existing)))))}})))))))

(defprocessor :evaluation on-assessment-outcome-recorded
  {:topics assessments/terminal-event-types}
  "S11: watch the alert of a judge that declared one. A judge with no alert is
   never read for and never signalled."
  [context]
  (on-assessment-outcome context))
