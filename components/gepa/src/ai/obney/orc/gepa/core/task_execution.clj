(ns ai.obney.orc.gepa.core.task-execution
  "Execute admitted GEPA tasks against their original durable ORC ticks."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.gepa.core.task-ledger :as ledger]
            [ai.obney.orc.gepa.core.metric-runtime :as metric]
            [ai.obney.orc.orc-service.interface :as sheet])
  (:import [java.util.concurrent Executors ThreadFactory TimeUnit]))

(defn- command! [ctx command]
  (cp/process-command (assoc ctx :command
                       (merge {:command/id (random-uuid) :command/timestamp (time/now)} command))))

(defn- attention! [ctx owner code message]
  (ledger/attention! ctx (assoc owner :reason {:code code :message message})))

(defn- with-lease [ctx owner f]
  (let [healthy (atom true)
        scheduler (Executors/newSingleThreadScheduledExecutor
                    (reify ThreadFactory
                      (newThread [_ runnable]
                        (doto (Thread. runnable "orc-gepa-task-lease") (.setDaemon true)))))
        period (long (or (:gepa/task-renew-ms ctx) 30000))]
    (try
      (.scheduleAtFixedRate scheduler
        ^Runnable (fn []
                    (try
                      (when-not (= :renewed (:status (ledger/renew! ctx owner)))
                        (reset! healthy false))
                      (catch Exception _ (reset! healthy false))))
        period period TimeUnit/MILLISECONDS)
      (f healthy)
      (finally (.shutdownNow scheduler)))))

(defn- workflow-result! [ctx owner task healthy]
  (or (:workflow-result task)
      (sheet/get-execution-result ctx (:trace-id task))
      (let [started? (seq (into [] (es/read (:event-store ctx)
                                    {:tenant-id (:tenant-id ctx)
                                     :types #{:sheet/tree-tick-started}
                                     :tags #{[:tick (:trace-id task)]}})))
            ;; Dispatch through the ordinary public Grain command. Its atomic
            ;; tick identity guard protects the crash window before tick start;
            ;; no process-local completion promise is replaced during recovery.
            result (when (and @healthy (not started?))
                     (command! (assoc ctx :gepa/patched-instructions (:instructions task))
                       {:command/name :sheet/tick-tree :sheet-id (:sheet-id task)
                        :tick-id (:trace-id task) :inputs (:input task)}))
            timeout-ms (long (or (:gepa/workflow-timeout-ms ctx) 300000))
            deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
        (if (:cognitect.anomalies/category result)
          {:status :dispatch-failed :error (:cognitect.anomalies/message result)}
          (do
            (when started? (sheet/resume-in-progress! ctx))
            (loop []
              (or (sheet/get-execution-result ctx (:trace-id task))
                  (when (and @healthy (< (System/nanoTime) deadline))
                    (Thread/sleep 20)
                    (recur)))))))))

(defn- trace-provenance [ctx trace-id]
  (let [calls (filter :model
                (into [] (es/read (:event-store ctx)
                           {:tenant-id (:tenant-id ctx)
                            :types #{:sheet/node-execution-completed}
                            :tags #{[:tick trace-id]}})))
        usage (fn [call]
                (let [u (:usage call)]
                  {:prompt-tokens (or (:prompt-tokens u) (:prompt_tokens u) 0)
                   :completion-tokens (or (:completion-tokens u) (:completion_tokens u) 0)
                   :total-tokens (or (:total-tokens u) (:total_tokens u) 0)}))]
    (when (seq calls)
      {:model (:model (first calls)) :usage (reduce #(merge-with + %1 %2) {} (map usage calls))})))

(defn- grade! [ctx owner descriptor task]
  (loop [task task]
    (let [retry? (:metric-started? task)
          outcome (when retry? {:status :unknown :reason :interrupted-metric})
          admitted (cond
                     (not retry?) {:status :dispatched}
                     (metric/retry-allowed? descriptor (:attempt-count task) outcome)
                     (ledger/dispatch! ctx (assoc owner :attempt-kind :metric :attempt-id (random-uuid)))
                     :else {:status :recovery-required :reason :metric-outcome-unknown})]
      (if-not (= :dispatched (:status admitted))
        (attention! ctx owner (or (:reason admitted) (:status admitted))
                    "Original metric outcome cannot be safely retried within the remaining budget")
        (let [current (ledger/get-task ctx (:optimization-id owner) (:call-id owner))
              attempt (assoc owner :attempt-id (:attempt-id current))
              started (ledger/begin-metric! ctx attempt)]
          (if-not (= :metric-started (:status started))
            started
            (let [result (metric/invoke-once!
                           (assoc ctx :gepa/task-attempt attempt)
                           descriptor (:input current)
                           (get-in current [:workflow-result :outputs]))]
              (if (= :completed (:status result))
                (ledger/finish! ctx
                  (assoc attempt :result (merge (:result result)
                                               (trace-provenance ctx (:trace-id current))
                                               {:output (get-in current [:workflow-result :outputs])})))
                (do
                  (attention! ctx owner (or (:reason result) :metric-failed)
                              (or (get-in result [:error :message]) "Metric did not return a valid grade"))
                  (if (metric/retry-allowed? descriptor (:attempt-count current) result)
                    (recur (ledger/get-task ctx (:optimization-id owner) (:call-id owner)))
                    {:status :recovery-required :reason (:reason result)}))))))))))

(defn execute-task!
  "Claim and finish one manifest task. Pending/recovery results carry no grade.
   Only the owner dispatches effects; every metric retry reuses original output."
  [ctx identity descriptor]
  (let [owner (assoc identity :owner-id (random-uuid))
        claimed (ledger/claim! ctx owner)]
    (case (:status claimed)
      :reused {:status :completed :task (:task claimed) :result (get-in claimed [:task :result])}
      :owned
      (let [task (:task claimed) owner (assoc owner :epoch (:epoch task))]
        (with-lease ctx owner
          (fn [healthy]
            (let [resolved (metric/resolve-metric ctx descriptor)]
              (if-not (= :ready (:status resolved))
                (attention! ctx owner (or (:reason resolved) :metric-unavailable)
                            "Restore the original metric registration before recovery")
                (let [dispatched (if (pos? (:attempt-count task 0))
                                   {:status :already-dispatched}
                                   (ledger/dispatch! ctx (assoc owner :attempt-kind :evaluation
                                                              :attempt-id (random-uuid))))]
                  (if-not (#{:dispatched :already-dispatched} (:status dispatched))
                    dispatched
                    (let [task (ledger/get-task ctx (:optimization-id identity) (:call-id identity))
                          result (workflow-result! ctx owner task healthy)]
                      (cond
                        (not @healthy) {:status :stale-owner}
                        (nil? result) {:status :pending}
                        (not= :success (:status result))
                        (attention! ctx owner :workflow-failed
                                    (or (:error result) "The original workflow did not complete successfully"))
                        :else
                        (let [recorded (ledger/record-workflow! ctx (assoc owner :result result))]
                          (if-not (= :workflow-recorded (:status recorded))
                            recorded
                            (let [graded (grade! ctx owner descriptor
                                          (ledger/get-task ctx (:optimization-id identity) (:call-id identity)))]
                              (if (#{:completed :reused} (:status graded))
                                (let [done (ledger/get-task ctx (:optimization-id identity) (:call-id identity))]
                                  {:status :completed :task done :result (:result done)})
                                graded)))))))))))))
      claimed)))
