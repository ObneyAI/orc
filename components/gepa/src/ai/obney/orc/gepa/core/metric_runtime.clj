(ns ai.obney.orc.gepa.core.metric-runtime
  "Metric identity and bounded invocation. Durable task ownership, retry
   admission and expenditure belong to the GEPA task ledger."
  (:require [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.gepa.core.metrics :as metrics])
  (:import [java.util.concurrent Callable ExecutionException FutureTask
            TimeUnit TimeoutException]))

(def default-max-attempts 3)
(def default-timeout-ms 300000)

(defonce ^:private registrations (atom {}))

(defn- registration-key [context descriptor]
  (let [tenant-id (:tenant-id context)]
    (when-not (uuid? tenant-id)
      (throw (ex-info "Metric registration requires a tenant UUID"
                      {:reason :metric-tenant-required})))
    [tenant-id (:metric-id descriptor) (:revision descriptor)]))

(defn register!
  "Register a trusted two-argument custom metric for one tenant and revision.
   Call this again when rebuilding a process against a persisted descriptor."
  [context descriptor metric-fn]
  (when-not (and (= :custom (:kind descriptor))
                 (string? (:metric-id descriptor))
                 (string? (:revision descriptor))
                 (ifn? metric-fn))
    (throw (ex-info "Invalid custom metric registration"
                    {:reason :invalid-metric-registration})))
  (swap! registrations assoc (registration-key context descriptor) metric-fn)
  descriptor)

(defn unregister!
  "Remove a process-local binding without changing its durable descriptor."
  [context descriptor]
  (swap! registrations dissoc (registration-key context descriptor))
  nil)

(defn prepare!
  "Prepare the serializable metric identity saved with an optimization.
   An anonymous custom function remains usable in this process, but restart
   must explicitly restore its generated identity rather than choose a default."
  [context optimization-id {:keys [metric-fn metric-id metric-revision
                                  metric-retry-safe? judges config]}]
  (let [max-attempts (get config :metric-max-attempts default-max-attempts)
        timeout-ms (get config :metric-timeout-ms default-timeout-ms)
        _ (when-not (and (pos-int? max-attempts) (pos-int? timeout-ms))
            (throw (ex-info "Metric limits must be positive integers"
                            {:reason :invalid-metric-limits})))
        factory (::metrics/descriptor (meta metric-fn))
        kind (cond factory (:kind factory) metric-fn :custom judges :judges :else :default)
        descriptor {:kind kind
                    :metric-id (if (= kind :custom)
                                 (str (or metric-id (str "anonymous/" optimization-id)))
                                 (str "orc/" (name kind)))
                    :revision (str (or metric-revision "1"))
                    :configuration (if (= kind :judges)
                                     (assoc (or (:configuration factory)
                                                (metrics/normalize-judge-config judges))
                                            :judge-config (evaluation/current-judge-config))
                                     (or (:configuration factory) {}))
                    :retry-safe? (if (= kind :custom) (true? metric-retry-safe?) true)
                    :max-attempts max-attempts
                    :timeout-ms timeout-ms}]
    (when (= kind :custom) (register! context descriptor metric-fn))
    descriptor))

(defn resolve-metric
  "Resolve the original metric; returned functions retain the two-argument API."
  [context descriptor]
  (case (:kind descriptor)
    :exact-match
    {:status :ready
     :metric-fn (metrics/make-exact-match-metric
                  (get-in descriptor [:configuration :output-key]))}
    :contains
    {:status :ready
     :metric-fn (metrics/make-contains-metric
                  (get-in descriptor [:configuration :output-key]))}
    :judges
    (let [configuration (:configuration descriptor)
          metric (metrics/make-judge-metric configuration)]
      {:status :ready
       :metric-fn (fn [input output]
                    (evaluation/call-with-judge-config
                      (:judge-config configuration)
                      #(metric input output)))})
    :default
    {:status :ready
     :metric-fn (requiring-resolve
                  'ai.obney.orc.gepa.core.todo-processors/default-metric-fn)}
    :custom
    (if-let [f (get @registrations (registration-key context descriptor))]
      {:status :ready :metric-fn f}
      {:status :recovery-required
       :reason :metric-registration-missing
       :metric-id (:metric-id descriptor)
       :revision (:revision descriptor)})
    {:status :recovery-required
     :reason :metric-descriptor-missing-or-unsupported}))

(defn retry-allowed?
  "Whether the durable caller may admit another attempt. This does not reserve
   budget, claim ownership, invoke a metric, or authorize retrying a tree."
  [descriptor completed-attempts outcome]
  (boolean
    (and (true? (:retry-safe? descriptor))
         (pos-int? completed-attempts)
         (< completed-attempts (:max-attempts descriptor default-max-attempts))
         (#{:failed :unknown} (:status outcome)))))

(defn- run-bounded
  [thunk timeout-ms]
  (let [task (FutureTask. ^Callable (bound-fn [] (thunk)))
        worker (doto (Thread. task "orc-gepa-metric")
                 (.setDaemon true))]
    (.start worker)
    (try
      (.get task (long timeout-ms) TimeUnit/MILLISECONDS)
      (catch ExecutionException e
        (throw (or (.getCause e) e)))
      (finally
        ;; Interrupting the local body is a request, not proof that an external
        ;; effect stopped. A dispatched attempt remains spent on timeout.
        (when-not (.isDone task) (.cancel task true))))))

(defn invoke-once!
  "Invoke the identified metric once. The durable caller owns retry admission.
   :gepa/metric-runner can inject (fn [thunk timeout-ms] raw-result) for tests;
   its default executes on a bound daemon worker with a finite wait. A timeout
   is an unknown outcome, not a zero grade or evidence of remote cancellation."
  [context descriptor input output]
  (let [{:keys [status metric-fn] :as resolved} (resolve-metric context descriptor)]
    (if (= :ready status)
      (try
        (let [runner (or (:gepa/metric-runner context) run-bounded)
              raw (runner #(metric-fn input output) (:timeout-ms descriptor))
              result (if (number? raw) {:score (double raw) :feedback nil} raw)
              score (:score result)]
          (if (and (map? result) (number? score)
                   (Double/isFinite (double score)) (<= 0.0 score 1.0)
                   (or (nil? (:feedback result)) (string? (:feedback result))))
            {:status :completed :result (assoc result :score (double score))}
            {:status :failed :reason :invalid-metric-result}))
        (catch TimeoutException _
          {:status :unknown :reason :metric-timeout
           :timeout-ms (:timeout-ms descriptor)})
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          {:status :unknown :reason :metric-interrupted})
        (catch Exception e
          {:status :failed :reason :metric-error
           :error {:class (.getName (class e)) :message (ex-message e)}}))
      resolved)))
