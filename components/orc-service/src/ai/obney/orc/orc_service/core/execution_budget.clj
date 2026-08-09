(ns ai.obney.orc.orc-service.core.execution-budget
  "Shared wall-clock deadline and active-attempt state for workflow execution.")

(defonce ^:private active-attempts (atom {}))
(defonce ^:private active-work (atom {}))

(defn remaining-ms [deadline-ms]
  (when deadline-ms
    (- (long deadline-ms) (System/currentTimeMillis))))

(defn record-attempt! [tick-id node-id state]
  (when (and tick-id node-id)
    (swap! active-attempts assoc [tick-id node-id (or (:exec-context state) {})] state))
  state)

(defn attempts-for-tick [tick-id]
  (into {} (for [[[candidate-tick-id node-id exec-context] state] @active-attempts
                 :when (= tick-id candidate-tick-id)]
             [[node-id exec-context] state])))

(defn clear-node! [tick-id node-id]
  (when (and tick-id node-id)
    (swap! active-attempts
           (fn [attempts]
             (into {} (remove (fn [[[candidate-tick-id candidate-node-id _] _]]
                                (and (= tick-id candidate-tick-id)
                                     (= node-id candidate-node-id)))
                              attempts))))))

(defn clear-tick! [tick-id]
  (swap! active-attempts
         (fn [attempts]
           (into {} (remove (fn [[[candidate-tick-id _ _] _]]
                              (= tick-id candidate-tick-id))
                            attempts))))
  (swap! active-work dissoc tick-id))

(defn register-work! [tick-id node-id work]
  (swap! active-work assoc-in [tick-id node-id] work)
  work)

(defn cancel-active-work! [tick-id]
  (doseq [[_ work] (get @active-work tick-id)]
    (future-cancel work))
  (swap! active-work dissoc tick-id))
