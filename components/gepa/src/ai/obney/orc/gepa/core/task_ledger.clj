(ns ai.obney.orc.gepa.core.task-ledger
  "Durable GEPA evaluation identity, ownership and capacity admission."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.gepa.interface.task-schemas]
            [ai.obney.orc.gepa.interface.schemas]
            [malli.core :as m]
            [cognitect.anomalies :as anom])
  (:import [java.time Instant]))

(def event-types
  #{:gepa/optimization-started :gepa/optimization-completed :gepa/optimization-failed
    :gepa/candidate-evaluated :gepa/subsample-evaluated
    :gepa/task-boundary-opened :gepa/task-claimed :gepa/task-lease-renewed
    :gepa/task-attempt-dispatched :gepa/task-workflow-completed :gepa/task-metric-started
    :gepa/task-finished :gepa/task-call-completed :gepa/task-recovery-required
    :gepa/task-adopted :gepa/task-adoption-blocked})

(defn- records [context optimization-id]
  (into [] (es/read (:event-store context)
                   {:tenant-id (:tenant-id context) :types event-types
                    :tags #{[:optimization optimization-id]}})))

(defn reduce-event [state event]
  (let [call-id (:call-id event)]
    (case (:event/type event)
      :gepa/optimization-started
      {:optimization-id (:optimization-id event) :budget (get-in event [:config :max-metric-calls])
       :spent 0 :tasks {} :boundaries {}}
      (:gepa/optimization-completed :gepa/optimization-failed)
      (assoc state :terminal? true)
      :gepa/task-boundary-opened
      (assoc-in state [:boundaries (:boundary-id event)]
                (select-keys event [:boundary :tasks]))
      :gepa/task-adoption-blocked
      (assoc-in state [:adoption-issues (:boundary-id event)]
                (select-keys event [:boundary-id :call-id :reason :source-event-ids]))
      :gepa/task-claimed
      (let [manifest (get-in state [:boundaries (:boundary-id event) :tasks])
            entry (some #(when (= call-id (:call-id %)) %) manifest)]
        (assoc-in state [:tasks call-id]
                  (merge entry {:status :claimed :attempt-count 0}
                         (get-in state [:tasks call-id])
                         (select-keys event [:boundary-id :owner-id :epoch :lease-expires-at]))))
      :gepa/task-lease-renewed
      (assoc-in state [:tasks call-id :lease-expires-at] (:lease-expires-at event))
      :gepa/task-attempt-dispatched
      (-> state
          (update :spent inc)
          (assoc-in [:tasks call-id :status] (if (= :metric (:attempt-kind event)) :workflow-completed :running))
          (assoc-in [:tasks call-id :metric-started?] false)
          (update-in [:tasks call-id] dissoc :recovery-reason)
          (assoc-in [:tasks call-id :attempt-count] (:attempt-number event))
          (assoc-in [:tasks call-id :attempt-id] (:attempt-id event))
          (update-in [:tasks call-id :attempts] (fnil conj [])
                     (select-keys event [:attempt-id :attempt-kind :attempt-number :owner-id :epoch])))
      :gepa/task-workflow-completed
      (-> state (assoc-in [:tasks call-id :workflow-result] (:result event))
          (assoc-in [:tasks call-id :status] :workflow-completed))
      :gepa/task-metric-started
      (-> state (assoc-in [:tasks call-id :metric-started?] true)
          (assoc-in [:tasks call-id :status] :metric-running))
      :gepa/task-finished
      (-> state (assoc-in [:tasks call-id :result] (:result event))
          (assoc-in [:tasks call-id :status] :completed))
      :gepa/task-recovery-required
      (-> state (assoc-in [:tasks call-id :recovery-reason] (:reason event))
          (assoc-in [:tasks call-id :status] :recovery-required))
      :gepa/task-adopted
      (let [entry (some #(when (= call-id (:call-id %)) %)
                        (get-in state [:boundaries (:boundary-id event) :tasks]))]
        (-> state
            (update :spent + (:observed-attempts event))
            (assoc-in [:tasks call-id]
                      (merge entry
                             (select-keys event [:boundary-id :trace-id :result :source-event-ids :source-trace-ids])
                             {:status :completed :attempt-count (:observed-attempts event)
                              :adopted? true
                              :workflow-result {:status :success :trace-id (:trace-id event)
                                                :outputs (get-in event [:result :output])}}))))
      (:gepa/candidate-evaluated :gepa/subsample-evaluated)
      (-> state
          (update :spent (fnil + 0) (if (= :tasks (:accounting-mode event)) 0 (or (:metric-calls event) 0)))
          (update :completed-boundaries (fnil conj #{})
                  (if (= :gepa/candidate-evaluated (:event/type event))
                    {:kind :candidate :candidate-id (:candidate-id event)}
                    {:kind :subsample :parent-id (:parent-id event) :iteration (:iteration event)})))
      state)))

(defreadmodel :gepa task-ledger
  {:events event-types :version 1}
  [state event] (reduce-event state event))

(defn get-state [context optimization-id]
  (if (uuid? (:tenant-id context))
    (rmp/project context :gepa/task-ledger {:tags #{[:optimization optimization-id]}})
    {::anom/category ::anom/incorrect ::anom/message "A tenant UUID is required"}))

(defn get-task [context optimization-id call-id]
  (let [state (get-state context optimization-id)]
    (if (::anom/category state) state (get-in state [:tasks call-id]))))

(defn accounting [state]
  (let [reserved (count (filter #(zero? (:attempt-count % 0)) (vals (:tasks state))))
        spent (:spent state 0) budget (:budget state 0)]
    {:spent spent :reserved reserved :budget budget :available (- budget spent reserved)}))

(defn get-accounting [context optimization-id]
  (let [state (get-state context optimization-id)]
    (if (::anom/category state) state (accounting state))))

(defn- now [context]
  ((or (:gepa/clock-fn context) #(Instant/now))))

(defn- healthy? [task instant]
  (and (:lease-expires-at task)
       (.isAfter (Instant/parse (:lease-expires-at task)) instant)))

(defn- owner? [task command instant]
  (and task (= (:owner-id task) (:owner-id command))
       (= (:boundary-id task) (:boundary-id command))
       (= (:epoch task) (:epoch command)) (healthy? task instant)))

(defn- reply [status & [data]]
  {:command-result/events [] :command/result (assoc (or data {}) :status status)})

(defn- event [type optimization-id body]
  (->event {:type type :tags (cond-> #{[:optimization optimization-id]}
                             (:call-id body) (conj [:call (:call-id body)]))
            :body (assoc body :optimization-id optimization-id)}))

(defn- append-result [optimization-id previous events result]
  {:command-result/events events
   :command-result/cas
   {:types event-types :tags #{[:optimization optimization-id]}
    :predicate-fn (fn [current]
                    (= (mapv :event/id previous) (mapv :event/id current)))}
   :command/result result})

(defn- ambiguous-legacy-task [previous tasks]
  (some (fn [entry]
          (let [matches (filter #(and (= :gepa/task-call-completed (:event/type %))
                                      (= (:call-id entry) (:call-id %))) previous)
                evidence (set (map #(select-keys % [:output :score :feedback :model :usage]) matches))]
            (when (> (count evidence) 1)
              {:call-id (:call-id entry) :reason :ambiguous-legacy-task
               :source-event-ids (mapv :event/id matches)})))
        tasks))

(defcommand :gepa manage-task
  {:authorized? (fn [context] (some? (:auth-claims context)))}
  "Manage durable evaluation work under optimization-scoped atomic admission."
  [{:keys [command] :as context}]
  (let [{:keys [optimization-id boundary-id operation boundary tasks call-id owner-id]} command
        previous (when (uuid? (:tenant-id context)) (records context optimization-id))
        state (reduce reduce-event nil previous)
        instant (now context)]
    (cond
      (not (uuid? (:tenant-id context)))
      {::anom/category ::anom/incorrect ::anom/message "A tenant UUID is required"}
      (nil? state) {::anom/category ::anom/not-found ::anom/message "Optimization not found"}
      (:terminal? state) (reply :optimization-terminal)
      :else
      (case operation
        :open-boundary
        (let [ambiguity (ambiguous-legacy-task previous tasks)]
        (cond
          (contains? (:completed-boundaries state) boundary)
          (reply :boundary-completed {:boundary boundary})
          ambiguity
          (let [issue (assoc ambiguity :boundary-id boundary-id)
                result (assoc issue :status :recovery-required)]
            (if (= issue (get-in state [:adoption-issues boundary-id]))
              (reply :recovery-required issue)
              (append-result optimization-id previous
                             [(event :gepa/task-adoption-blocked optimization-id issue)] result)))
          (some (fn [[id manifest]]
                  (and (not= id boundary-id)
                       (some (set (map :call-id tasks)) (map :call-id (:tasks manifest)))))
                (:boundaries state))
          {::anom/category ::anom/conflict ::anom/message "Task identity already belongs to another boundary"}
          :else
        (if-let [existing (get-in state [:boundaries boundary-id])]
          (if (= existing {:boundary boundary :tasks tasks})
            (reply :opened {:boundary-id boundary-id})
            {::anom/category ::anom/conflict ::anom/message "Evaluation manifest changed"})
          (if (and boundary (seq tasks) (= (count tasks) (count (set (map :call-id tasks)))))
            (let [adoptions
                  (keep (fn [entry]
                          (let [matches (filter #(and (= :gepa/task-call-completed (:event/type %))
                                                      (= (:call-id entry) (:call-id %))) previous)
                                canonical (first matches)
                                traces (vec (distinct (map :trace-id matches)))]
                            (when canonical
                              (event :gepa/task-adopted optimization-id
                                     {:boundary-id boundary-id :call-id (:call-id entry)
                                      :trace-id (:trace-id canonical)
                                      :result (select-keys canonical [:output :score :feedback :model :usage])
                                      :source-event-ids (mapv :event/id matches)
                                      :source-trace-ids traces :observed-attempts (count traces)})))) tasks)]
              (append-result optimization-id previous
                             (into [(event :gepa/task-boundary-opened optimization-id
                                           {:boundary-id boundary-id :boundary boundary :tasks tasks})] adoptions)
                             {:status :opened :boundary-id boundary-id}))
            {::anom/category ::anom/incorrect ::anom/message "A unique nonempty task manifest is required"}))))
        :claim
        (let [entry (some #(when (= call-id (:call-id %)) %)
                          (get-in state [:boundaries boundary-id :tasks]))
              existing (get-in state [:tasks call-id])]
          (cond
            (or (nil? entry) (nil? owner-id))
            {::anom/category ::anom/incorrect ::anom/message "Claim requires a manifest task and owner"}
            (= :completed (:status existing)) (reply :reused {:task existing})
            (and existing (healthy? existing instant)) (reply :pending {:task existing})
            (and (nil? existing) (not (pos? (:available (accounting state))))) (reply :budget-exhausted)
            :else
            (let [body {:boundary-id boundary-id :call-id call-id :trace-id (:trace-id entry)
                        :owner-id owner-id :epoch (inc (:epoch existing 0))
                        :lease-expires-at (str (.plusMillis instant (long (or (:gepa/task-lease-ms context) 90000))))}]
              (append-result optimization-id previous [(event :gepa/task-claimed optimization-id body)]
                             {:status :owned :task (merge entry {:status :claimed :attempt-count 0} existing body)}))))
        :renew
        (let [task (get-in state [:tasks call-id])]
          (if (owner? task command instant)
            (let [body (assoc (select-keys command [:boundary-id :call-id :owner-id :epoch])
                              :lease-expires-at
                              (str (.plusMillis instant (long (or (:gepa/task-lease-ms context) 90000)))))]
              (append-result optimization-id previous [(event :gepa/task-lease-renewed optimization-id body)]
                             {:status :renewed}))
            (reply :stale-owner)))
        :dispatch
        (let [task (get-in state [:tasks call-id]) retry? (= :metric (:attempt-kind command))]
          (cond
            (= :completed (:status task)) (reply :reused {:task task})
            (not (owner? task command instant)) (reply :stale-owner)
            (or (and (not retry?) (pos? (:attempt-count task 0)))
                (some #(= (:attempt-id command) (:attempt-id %)) (:attempts task)))
            (reply :already-dispatched {:task task})
            (and retry? (not (:metric-retry-safe? task))) (reply :recovery-required {:reason :metric-not-retry-safe})
            (and retry? (>= (:attempt-count task 0) (:max-metric-attempts task 3))) (reply :attempt-limit-exceeded)
            (and retry? (not (pos? (:available (accounting state))))) (reply :budget-exhausted)
            (and retry? (nil? (:workflow-result task)))
            {::anom/category ::anom/incorrect ::anom/message "A metric retry requires original workflow outputs"}
            (or (nil? (:attempt-id command)) (not (#{:evaluation :metric} (:attempt-kind command))))
            {::anom/category ::anom/incorrect ::anom/message "Initial dispatch requires an evaluation attempt identity"}
            :else
            (let [body (assoc (select-keys command [:boundary-id :call-id :owner-id :epoch :attempt-id :attempt-kind])
                              :attempt-number (inc (:attempt-count task 0)))]
              (append-result optimization-id previous
                             [(event :gepa/task-attempt-dispatched optimization-id body)]
                             {:status :dispatched :attempt-number (:attempt-number body) :attempt-id (:attempt-id command)}))))
        :workflow-completed
        (let [task (get-in state [:tasks call-id]) result (:result command)]
          (cond
            (not (owner? task command instant)) (reply :stale-owner)
            (:workflow-result task)
            (if (= result (:workflow-result task)) (reply :workflow-recorded {:reused? true})
                {::anom/category ::anom/conflict ::anom/message "Workflow result is immutable"})
            (or (zero? (:attempt-count task 0)) (not= (:trace-id task) (:trace-id result)))
            {::anom/category ::anom/incorrect ::anom/message "Workflow result must reference the dispatched task trace"}
            :else
            (append-result optimization-id previous
                           [(event :gepa/task-workflow-completed optimization-id
                                   (select-keys command [:boundary-id :call-id :owner-id :epoch :result]))]
                           {:status :workflow-recorded})))
        :metric-started
        (let [task (get-in state [:tasks call-id])]
          (cond
            (= :completed (:status task)) (reply :reused {:task task})
            (not (owner? task command instant)) (reply :stale-owner)
            (not= (:attempt-id task) (:attempt-id command)) (reply :stale-attempt)
            (:metric-started? task) (reply :already-started)
            (nil? (:workflow-result task))
            {::anom/category ::anom/incorrect ::anom/message "Metric requires the original durable workflow result"}
            :else
            (append-result optimization-id previous
                           [(event :gepa/task-metric-started optimization-id
                                   (select-keys command [:boundary-id :call-id :owner-id :epoch :attempt-id]))]
                           {:status :metric-started})))
        :finish
        (let [task (get-in state [:tasks call-id]) result (:result command)]
          (cond
            (= :completed (:status task))
            (if (= result (:result task)) (reply :reused {:task task})
                {::anom/category ::anom/conflict ::anom/message "Task result is immutable"})
            (not (owner? task command instant)) (reply :stale-owner)
            (not= (:attempt-id task) (:attempt-id command)) (reply :stale-attempt)
            (some (fn [key] (and (contains? result key) (not= (get result key) (get task key))))
                  [:call-id :trace-id :role :example-index])
            {::anom/category ::anom/conflict ::anom/message "Metric result cannot replace task identity"}
            (not= (:output result) (get-in task [:workflow-result :outputs]))
            {::anom/category ::anom/conflict ::anom/message "Metric output must match the durable workflow result"}
            (or (not (:metric-started? task)) (not (m/validate :gepa/task-result result)))
            {::anom/category ::anom/incorrect ::anom/message "Completion requires a started metric and a valid scored result"}
            :else
            (append-result optimization-id previous
                           [(event :gepa/task-finished optimization-id
                                   (assoc (select-keys command [:boundary-id :call-id :owner-id :epoch :result])
                                          :attempt-id (:attempt-id task)))
                            (event :gepa/task-call-completed optimization-id
                                   (merge (select-keys task [:call-id :role :example-index :trace-id])
                                          (select-keys result [:output :score :feedback :model :usage])
                                          {:accounting-mode :tasks :completed-at (str instant)}))]
                           {:status :completed :result result})))
        :attention
        (let [task (get-in state [:tasks call-id])]
          (cond
            (not (owner? task command instant)) (reply :stale-owner)
            (= :completed (:status task)) (reply :reused {:task task})
            (nil? (:reason command)) {::anom/category ::anom/incorrect ::anom/message "Recovery reason is required"}
            (= (:reason command) (:recovery-reason task)) (reply :recovery-required)
            :else
            (append-result optimization-id previous
                           [(event :gepa/task-recovery-required optimization-id
                                   (select-keys command [:boundary-id :call-id :owner-id :epoch :reason]))]
                           {:status :recovery-required})))))))

(defn- command! [context operation data]
  (loop [remaining 32]
    (let [result (cp/process-command
                   (assoc context :command
                          (merge {:command/id (random-uuid) :command/timestamp (time/now)
                                  :command/name :gepa/manage-task :operation operation} data)))]
      (if (and (pos? remaining) (= ::anom/conflict (::anom/category result)) (:cas result))
        (recur (dec remaining))
        (or (:command/result result) result)))))

(defn open-boundary! [context data] (command! context :open-boundary data))
(defn claim! [context data] (command! context :claim data))
(defn renew! [context data] (command! context :renew data))
(defn dispatch! [context data] (command! context :dispatch data))
(defn record-workflow! [context data] (command! context :workflow-completed data))
(defn begin-metric! [context data] (command! context :metric-started data))
(defn finish! [context data] (command! context :finish data))
(defn attention! [context data] (command! context :attention data))
