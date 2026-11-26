(ns ai.obney.orc.gepa.core.optimization
  "Optimization orchestration for GEPA.

   Contains the main optimize! entry point, completion registry
   for sync callers, and command dispatch helpers."
  (:require [ai.obney.orc.gepa.core.metrics :as metrics]
            [ai.obney.orc.gepa.core.metric-runtime :as metric-runtime]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Completion Registry (for sync callers waiting on async optimization)
;; =============================================================================

(defonce ^:private completion-registry (atom {}))

(defn register-completion!
  "Register a promise for an optimization-id. Returns the promise.
   Used by optimize! with :block? true to wait for completion."
  [optimization-id]
  (let [p (promise)]
    (swap! completion-registry assoc optimization-id p)
    p))

(defn deliver-completion!
  "Deliver a result to any waiting promise for an optimization-id.
   Called by todo processors when optimization completes or fails."
  [optimization-id result]
  (when-let [p (get @completion-registry optimization-id)]
    (deliver p result)
    (swap! completion-registry dissoc optimization-id)))

;; =============================================================================
;; Metric Function Registry (for async processors)
;; =============================================================================

(defonce ^:private metric-fn-registry (atom {}))

(defn register-metric-fn!
  "Register a metric function for an optimization-id.
   Called by optimize! so async todo processors can look it up."
  [optimization-id metric-fn]
  (swap! metric-fn-registry assoc optimization-id metric-fn))

(defn get-metric-fn
  "Get the registered metric function for an optimization-id.
   Returns nil if not registered (processors fall back to default-metric-fn)."
  [optimization-id]
  (get @metric-fn-registry optimization-id))

(defn unregister-metric-fn!
  "Remove a metric function from the registry (cleanup after optimization)."
  [optimization-id]
  (swap! metric-fn-registry dissoc optimization-id))

;; =============================================================================
;; Command Processing Helper
;; =============================================================================

(defn- process-command!
  "Process a command and extract the result.
   The command processor stores events automatically.
   Returns :command/result on success, or anomaly on failure."
  [context]
  (let [result (command-processor/process-command context)]
    (if (::anom/category result)
      result
      (:command/result result))))

;; =============================================================================
;; Main Optimization Entry Point
;; =============================================================================

(defn optimize!
  "Start a GEPA optimization run.

   Arguments:
   - context: Grain context with :event-store, :command-processor, etc.
   - opts: Optimization options
     - :sheet-id - UUID of the workflow to optimize
     - :trainset - Vector of training examples (for reflection)
     - :valset - Vector of validation examples (for scoring)
     - :metric-fn - Scoring function (fn [expected actual] -> 0.0-1.0)
     - :judges - Alternative: use orc-service judges {:grounding 0.35 ...}
     - :config - Optional configuration overrides
     - :inherit-from-previous - Auto-inherit Pareto candidates (default true)
     - :inherit-from - Specific optimization ID to inherit from
     - :block? - If true, block until complete (default false)
     - :timeout-ms - Max wait time when blocking (default 300000 = 5 min)

   Returns (async, :block? false):
   {:optimization-id uuid
    :status :running
    :config merged-config}

   Returns (sync, :block? true):
   {:optimization-id uuid
    :status :completed | :failed | :timeout
    :best-candidate {:instructions {...} :score double}
    :best-score double
    :duration-ms int}

   Example:
   ```clojure
   ;; Async (returns immediately)
   (optimize! ctx {:sheet-id id :valset data})

   ;; Sync (blocks until done)
   (optimize! ctx {:sheet-id id
                   :valset data
                   :metric-fn (metrics/make-exact-match-metric \"answer\")
                   :block? true})
   ```"
  [context {:keys [sheet-id trainset valset metric-fn judges config
                   inherit-from-previous inherit-from block? timeout-ms]
            :or {inherit-from-previous true block? false timeout-ms 300000}
            :as opts}]
  (let [optimization-id (random-uuid)
        start-time (System/currentTimeMillis)
        metric-descriptor (metric-runtime/prepare! context optimization-id opts)

        ;; Build metric function from judges if provided
        effective-metric-fn (cond
                              metric-fn metric-fn
                              judges (metrics/make-judge-metric judges)
                              :else nil)

        ;; Add metric-fn to context for todo processors
        ctx-with-metric (if effective-metric-fn
                          (assoc context :gepa/metric-fn effective-metric-fn)
                          context)

        ;; Register metric function for async processors
        _ (when effective-metric-fn
            (register-metric-fn! optimization-id effective-metric-fn))

        ;; Register for completion if blocking
        completion-promise (when block?
                             (register-completion! optimization-id))

        ;; Start the optimization
        cmd-result (process-command!
                     (assoc ctx-with-metric
                            :command {:command/id (random-uuid)
                                      :command/timestamp (time/now)
                                      :command/name :gepa/start-optimization
                                      :optimization-id optimization-id
                                      :metric-descriptor metric-descriptor
                                      :sheet-id sheet-id
                                      :trainset trainset
                                      :valset valset
                                      :config (or config {})
                                      :inherit-from-previous inherit-from-previous
                                      :inherit-from inherit-from}))]

    (if (::anom/category cmd-result)
      ;; Command failed
      (do
        (unregister-metric-fn! optimization-id)
        (when block?
          (swap! completion-registry dissoc optimization-id))
        cmd-result)

      ;; Command succeeded
      (if block?
        ;; Wait for completion
        (let [result (deref completion-promise timeout-ms ::timeout)
              duration-ms (- (System/currentTimeMillis) start-time)]
          (swap! completion-registry dissoc optimization-id)
          (unregister-metric-fn! optimization-id)
          (if (= result ::timeout)
            {:optimization-id optimization-id
             :status :timeout
             :error "Optimization timed out"
             :duration-ms duration-ms}
            (assoc result :duration-ms duration-ms)))

        ;; Return immediately
        {:optimization-id optimization-id
         :status :running
         :config (merge {:max-metric-calls 50} config)}))))
