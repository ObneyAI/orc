(ns ai.obney.orc.gepa.core.todo-processors
  "Todo processors for GEPA optimization loop.

   These event handlers implement the core optimization algorithm with
   subsample-first evaluation (Python GEPA parity):

   1. On optimization-started: Create seed candidate
   2. On candidate-created: Trigger full evaluation (for seed/inherited)
   3. On candidate-evaluated: Update frontier, check budget, then request a
      mutation round: sample the parent and component, draw the TRAINING
      minibatch, and record the request (a durable, resumable boundary).
   3b. On parent-minibatch-evaluation-requested: evaluate the PARENT on the
      recorded minibatch and build the reflective examples from that
      evaluation alone (the validation set never reaches reflection). A parent
      perfect on its minibatch ends the round before any proposer call, and
      the skipped comparison is published.
   4. On proposal-ready: Evaluate the proposal on the SAME minibatch; the
      parent's minibatch tasks are reused from the ledger, not run again
   5. On subsample-evaluated:
      - If improved (new_sum > old_sum): Create the candidate, FULL evaluation
      - If not improved: run another mutation round
   6. Loop until budget exhausted, then complete

   This subsample-first approach matches Python GEPA's efficiency:
   - Rejected candidates cost only 3 metric calls (vs 72 for full valset)
   - Accepted candidates cost 3 + 72 = 75 metric calls
   - Net savings: ~60% if 50% rejection rate

   Each processor is triggered by events and emits commands
   to drive the state machine forward."
  (:require [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.orc.gepa.core.commands :as commands]
            [ai.obney.orc.gepa.core.logic :as logic]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [com.brunobonacci.mulog :as u])
  (:import [java.util.concurrent Executors ThreadFactory TimeUnit]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn run-command!
  "Execute a command through the command processor."
  [context command]
  (command-processor/process-command
    (assoc context :command command)))

;; =============================================================================
;; On Candidate Created
;; =============================================================================

(defn on-candidate-created
  "Handle candidate-created event.

   Trigger evaluation of the new candidate."
  [{:keys [event] :as context}]
  (u/log ::on-candidate-created
         :candidate-id (:candidate-id event))

  (let [{:keys [optimization-id candidate-id]} event]
    (run-command! context
      {:command/id (random-uuid)
       :command/timestamp (time/now)
       :command/name :gepa/evaluate-candidate
       :optimization-id optimization-id
       :candidate-id candidate-id})))

(defn- metric-descriptor-for [context optimization-id]
  (or (:metric-descriptor (rm/get-optimization-summary context optimization-id))
      (when-let [binding (or (logic/get-metric-fn optimization-id)
                            (:gepa/metric-fn context))]
        (let [descriptor (logic/prepare! context optimization-id {:metric-fn binding})
              selected (run-command! context
                         {:command/id (random-uuid) :command/timestamp (time/now)
                          :command/name :gepa/select-metric :optimization-id optimization-id
                          :metric-descriptor descriptor})]
          (or (get-in selected [:command/result :metric-descriptor])
              (some :metric-descriptor
                    (into [] (event-store/read (:event-store context)
                               {:tenant-id (:tenant-id context)
                                :types #{:gepa/optimization-started :gepa/metric-selected}
                                :tags #{[:optimization optimization-id]}}))))))))

(defn- prepare-task-batch! [context sheet-id instructions indexed-examples call-context]
  (if-not (:optimization-id call-context)
    call-context
    (let [{:keys [optimization-id role call-scope]} call-context
          descriptor (metric-descriptor-for context optimization-id)
          boundary-id (logic/stable-call-id :gepa/boundary optimization-id role call-scope)
          boundary (if (= role :candidate)
                     {:kind :candidate :candidate-id call-scope}
                     {:kind :subsample :parent-id (first call-scope) :iteration (second call-scope)})
          tasks (mapv (fn [[index input]]
                        (let [call-id (logic/stable-call-id :gepa/task optimization-id call-scope role index)]
                          {:call-id call-id :trace-id (logic/stable-call-id :gepa/trace call-id)
                           :role role :example-index index :input input :instructions instructions
                           :sheet-id sheet-id :metric-retry-safe? (true? (:retry-safe? descriptor))
                           :max-metric-attempts (:max-attempts descriptor 3)}))
                      indexed-examples)
          opened (commands/open-boundary! context
                   {:optimization-id optimization-id :boundary-id boundary-id
                    :boundary boundary :tasks tasks})]
      (when-not (= :opened (:status opened))
        (throw (ex-info "Evaluation boundary cannot execute"
                        {:gepa/evaluation-status (or (:status opened) :recovery-required)
                         :boundary-id boundary-id :result opened})))
      (assoc call-context :boundary-id boundary-id :metric-descriptor descriptor))))

(defn- attention! [ctx owner code message]
  (commands/attention! ctx (assoc owner :reason {:code code :message message})))

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
                      (when-not (= :renewed (:status (commands/renew! ctx owner)))
                        (reset! healthy false))
                      (catch Exception _ (reset! healthy false))))
        period period TimeUnit/MILLISECONDS)
      (f healthy)
      (finally (.shutdownNow scheduler)))))

(defn- workflow-result! [ctx owner task healthy]
  (or (:workflow-result task)
      (sheet/get-execution-result ctx (:trace-id task))
      (let [started? (seq (into [] (event-store/read (:event-store ctx)
                                    {:tenant-id (:tenant-id ctx)
                                     :types #{:sheet/tree-tick-started}
                                     :tags #{[:tick (:trace-id task)]}})))
            result (when (and @healthy (not started?))
                     (commands/command! (assoc ctx :gepa/patched-instructions (:instructions task))
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
                (into [] (event-store/read (:event-store ctx)
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
                     (logic/retry-allowed? descriptor (:attempt-count task) outcome)
                     (commands/dispatch! ctx (assoc owner :attempt-kind :metric :attempt-id (random-uuid)))
                     :else {:status :recovery-required :reason :metric-outcome-unknown})]
      (if-not (= :dispatched (:status admitted))
        (attention! ctx owner (or (:reason admitted) (:status admitted))
                    "Original metric outcome cannot be safely retried within the remaining budget")
        (let [current (rm/get-task ctx (:optimization-id owner) (:call-id owner))
              attempt (assoc owner :attempt-id (:attempt-id current))
              started (commands/begin-metric! ctx attempt)]
          (if-not (= :metric-started (:status started))
            started
            (let [result (logic/invoke-once!
                           (assoc ctx :gepa/task-attempt attempt)
                           descriptor (:input current)
                           (get-in current [:workflow-result :outputs]))]
              (if (= :completed (:status result))
                (commands/finish! ctx
                  (assoc attempt :result (merge (:result result)
                                               (trace-provenance ctx (:trace-id current))
                                               {:output (get-in current [:workflow-result :outputs])})))
                (do
                  (attention! ctx owner (or (:reason result) :metric-failed)
                              (or (get-in result [:error :message]) "Metric did not return a valid grade"))
                  (if (logic/retry-allowed? descriptor (:attempt-count current) result)
                    (recur (rm/get-task ctx (:optimization-id owner) (:call-id owner)))
                    {:status :recovery-required :reason (:reason result)}))))))))))

(defn execute-task!
  "Claim and finish one manifest task. Pending/recovery results carry no grade.
   Only the owner dispatches effects; every metric retry reuses original output."
  [ctx identity descriptor]
  (let [owner (assoc identity :owner-id (random-uuid))
        claimed (commands/claim! ctx owner)]
    (case (:status claimed)
      :reused {:status :completed :task (:task claimed) :result (get-in claimed [:task :result])}
      :owned
      (let [task (:task claimed) owner (assoc owner :epoch (:epoch task))]
        (with-lease ctx owner
          (fn [healthy]
            (let [resolved (logic/resolve-metric ctx descriptor)]
              (if-not (= :ready (:status resolved))
                (attention! ctx owner (or (:reason resolved) :metric-unavailable)
                            "Restore the original metric registration before recovery")
                (let [dispatched (if (pos? (:attempt-count task 0))
                                   {:status :already-dispatched}
                                   (commands/dispatch! ctx (assoc owner :attempt-kind :evaluation
                                                              :attempt-id (random-uuid))))]
                  (if-not (#{:dispatched :already-dispatched} (:status dispatched))
                    dispatched
                    (let [task (rm/get-task ctx (:optimization-id identity) (:call-id identity))
                          result (workflow-result! ctx owner task healthy)]
                      (cond
                        (not @healthy) {:status :stale-owner}
                        (nil? result) {:status :pending}
                        (not= :success (:status result))
                        (attention! ctx owner :workflow-failed
                                    (or (:error result) "The original workflow did not complete successfully"))
                        :else
                        (let [recorded (commands/record-workflow! ctx (assoc owner :result result))]
                          (if-not (= :workflow-recorded (:status recorded))
                            recorded
                            (let [graded (grade! ctx owner descriptor
                                          (rm/get-task ctx (:optimization-id identity) (:call-id identity)))]
                              (if (#{:completed :reused} (:status graded))
                                (let [done (rm/get-task ctx (:optimization-id identity) (:call-id identity))]
                                  {:status :completed :task done :result (:result done)})
                                graded)))))))))))))
      claimed)))

(defn- completed-task-call
  [ctx call-id]
  (some #(when (= call-id (:call-id %)) %)
        (into [] (event-store/read (:event-store ctx)
                                   {:tenant-id (:tenant-id ctx)
                                    :types #{:gepa/task-call-completed}}))))

(defn- trace-model-provenance
  [ctx trace-id]
  (let [calls (into []
                    (filter #(and (= trace-id (:tick-id %)) (:model %)))
                    (event-store/read (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx)
                                       :types #{:sheet/node-execution-completed}}))]
    (when (seq calls)
      {:model (:model (first calls))
       :usage (reduce (fn [total call]
                        (merge-with + total (logic/normalize-usage (:usage call))))
                      {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}
                      calls)})))

(defn execute-unmanaged-workflow
  "Execute a workflow with specific instructions on a single input.

   Returns {:output map :trace-id uuid :score double :feedback string-or-nil
            :success? true}. The :feedback is the judges' rich per-example
   feedback when the metric is the judge metric, else nil (back-compat).

   A workflow that failed or a metric that threw yields no grade: the call
   throws ex-info with :gepa/evaluation-status :failed, as the managed task
   path does. A score is never invented."
  ([context sheet-id instructions input metric-fn]
   (execute-unmanaged-workflow context sheet-id instructions input metric-fn nil))
  ([context sheet-id instructions input metric-fn
    {:keys [optimization-id role example-index call-scope]}]
  (let [call-id (when optimization-id
                  (logic/stable-call-id :gepa/task optimization-id call-scope role example-index))
        completed (when call-id (completed-task-call context call-id))]
    (if completed
      {:output (:output completed)
       :trace-id (:trace-id completed)
       :score (:score completed)
       :feedback (:feedback completed)
       :success? true
       :charged? false
       :model (:model completed)
       :usage (:usage completed)}
  (let [
        input-map (if (map? input)
                    (into {} (map (fn [[k v]] [(keyword k) v]) input))
                    {:input input})
        trace-id (random-uuid)
        result (try
                 (assoc (sheet/execute
                          (logic/patch-workflow-instructions context sheet-id instructions)
                          sheet-id
                          input-map
                          :tick-id trace-id
                          :timeout-ms 300000)
                        :trace-id trace-id)
                 (catch Exception e
                   {:status :failure
                    :trace-id trace-id
                    :error (.getMessage e)
                    :outputs {}}))]

    (u/log ::workflow-executed
           :sheet-id sheet-id
           :status (:status result)
           :duration-ms (:duration-ms result))

    (when-not (= :success (:status result))
      (throw (ex-info "Evaluation task has not produced a complete grade"
                      {:gepa/evaluation-status :failed
                       :reason :workflow-failed
                       :trace-id (:trace-id result)
                       :error (:error result)})))

    (let [output (:outputs result)
          {:keys [score feedback]}
          (try
            (logic/score+feedback-of (metric-fn input output))
            (catch Exception e
              (u/log ::metric-error :error (.getMessage e))
              (throw (ex-info "Evaluation task has not produced a complete grade"
                              {:gepa/evaluation-status :failed
                               :reason :metric-failed
                               :trace-id (:trace-id result)
                               :error (.getMessage e)}))))]
      (let [provenance (trace-model-provenance context (:trace-id result))
            completed-result {:output output
       :trace-id (:trace-id result)
       :score score
       :feedback feedback
       :success? true
       :charged? true
       :model (:model provenance)
       :usage (:usage provenance)}]
        (when call-id
          (run-command! context
            {:command/id (random-uuid)
             :command/timestamp (time/now)
             :command/name :gepa/record-task-call
             :optimization-id optimization-id
             :call-id call-id
             :role role
             :example-index example-index
             :trace-id (:trace-id result)
             :output (or output {})
             :score score
             :feedback feedback
             :model (:model provenance)
             :usage (:usage provenance)}))
        completed-result)))))))

(defn execute-workflow-with-instructions
  "Evaluate an unmanaged example or execute a previously admitted durable task."
  ([context sheet-id instructions input metric-fn]
   (execute-unmanaged-workflow context sheet-id instructions input metric-fn))
  ([context sheet-id instructions input metric-fn
    {:keys [optimization-id role example-index call-scope boundary-id metric-descriptor] :as call-context}]
   (if-not boundary-id
     (execute-unmanaged-workflow context sheet-id instructions input metric-fn call-context)
     (let [call-id (logic/stable-call-id :gepa/task optimization-id call-scope role example-index)
           outcome (execute-task! context
                     {:optimization-id optimization-id :boundary-id boundary-id :call-id call-id}
                     metric-descriptor)]
       (if (= :completed (:status outcome))
         (merge (:result outcome)
                {:trace-id (get-in outcome [:task :trace-id]) :success? true
                 :task-call-id call-id :attempt-count (get-in outcome [:task :attempt-count])
                 :charged? false})
         (throw (ex-info "Evaluation task has not produced a complete grade"
                         {:gepa/evaluation-status (or (:status outcome) :recovery-required)
                          :task outcome})))))))

(defn evaluate-candidate-on-valset
  "Evaluate a candidate's instructions on the full validation set.

   Returns {:scores [double ...] :trace-ids [uuid ...]
            :feedbacks {instance-idx -> feedback-string}
            :outputs {instance-idx -> output-map} :metric-calls int}.
   :feedbacks only contains entries for examples whose metric supplied rich
   feedback (the judge metric); structural metrics leave it empty. :outputs
   carries the per-instance generated output blackboard so the reflective
   dataset can show the proposer the actual (bad) answer text."
  ([context sheet-id instructions valset metric-fn]
   (evaluate-candidate-on-valset context sheet-id instructions valset metric-fn nil))
  ([context sheet-id instructions valset metric-fn call-context]
  (let [call-context (prepare-task-batch! context sheet-id instructions
                       (map-indexed vector valset) call-context)
        results (doall
                  (map-indexed
                    (fn [idx example]
                      (u/log ::evaluating-example
                             :index idx
                             :total (count valset))
                      (execute-workflow-with-instructions
                        context sheet-id instructions example metric-fn
                        (assoc call-context :example-index idx)))
                    valset))]
    {:scores (mapv :score results)
     :trace-ids (mapv :trace-id results)
     :feedbacks (into {} (keep-indexed (fn [idx r]
                                         (when (:feedback r) [idx (:feedback r)]))
                                       results))
     :outputs (into {} (keep-indexed (fn [idx r]
                                       (when (some? (:output r)) [idx (:output r)]))
                                     results))
     :metric-calls (if (:boundary-id call-context)
                     (reduce + (map :attempt-count results))
                     (count (filter :charged? results)))
     :task-call-ids (when (:boundary-id call-context) (mapv :task-call-id results))
     :accounting-mode (when (:boundary-id call-context) :tasks)})))

;; =============================================================================
;; On Candidate Evaluation Started
;; =============================================================================

(defn- finish-at-admission-budget-exhaustion!
  "Finish the optimization because a budget ran out: complete with the best
   fully evaluated candidate, or fail naming the budget when none exists.
   `budget` is :metric-calls (default) or :reflection-tokens."
  ([context optimization-id] (finish-at-admission-budget-exhaustion! context optimization-id :metric-calls))
  ([context optimization-id budget]
  (let [tasks (vals (:tasks (rm/get-state context optimization-id)))]
    (if (some #(not= :completed (:status %)) tasks)
      (u/log ::budget-exhausted-with-unfinished-work :optimization-id optimization-id)
      (let [winner (rm/get-best-candidate context optimization-id)]
        (run-command! context
          (cond-> {:command/id (random-uuid)
                   :command/timestamp (time/now)
                   :command/name (if (some? (:aggregate-score winner))
                                   :gepa/complete-optimization
                                   :gepa/fail-optimization)
                   :optimization-id optimization-id}
            (nil? (:aggregate-score winner))
            (assoc :error-message (str (case budget
                                         :reflection-tokens "Reflection token budget"
                                         "Metric budget")
                                       " exhausted before a complete candidate evaluation")))))))))

(defn on-candidate-evaluation-started
  "Handle candidate-evaluation-started event.

   For inherited candidates with historical-val-subscores:
   - SKIP LLM evaluation entirely (0 metric calls)
   - Directly record historical scores as evaluation result
   - This enables true single-run parity across epochs

   For new candidates (no historical scores):
   1. Get the candidate's instructions
   2. Temporarily patch the workflow with these instructions
   3. Execute workflow on validation set
   4. Collect scores from traces
   5. Record evaluation result"
  [{:keys [event] :as context}]
  (u/log ::on-candidate-evaluation-started
         :candidate-id (:candidate-id event))

  (let [{:keys [optimization-id candidate-id]} event
        event-store (:event-store context)

        candidate (rm/get-candidate context optimization-id candidate-id)
        historical-scores (:historical-val-subscores candidate)]

    (if (and historical-scores (seq historical-scores))
      (let [
            max-instance-id (if (seq historical-scores)
                              (apply max (keys historical-scores))
                              -1)
            scores-vec (mapv (fn [idx]
                               (get historical-scores idx 0.0))
                             (range (inc max-instance-id)))]
        (u/log ::reusing-historical-scores
               :candidate-id candidate-id
               :num-instances (count historical-scores)
               :mean-score (when (seq scores-vec)
                             (/ (reduce + scores-vec) (count scores-vec))))
        (run-command! context
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :gepa/record-evaluation-result
           :optimization-id optimization-id
           :candidate-id candidate-id
           :scores scores-vec
           :trace-ids []
           :metric-calls 0}))

      (let [opt-state (rm/get-optimization-summary context optimization-id)
            sheet-id (:sheet-id opt-state)
            instructions (:instructions candidate)

            valset (rm/get-valset context optimization-id)

            metric-fn (or (logic/get-metric-fn optimization-id)
                          (:gepa/metric-fn context)
                          logic/default-metric-fn)

            eval-result
            (if (and valset (seq valset) sheet-id)
              (try
                (evaluate-candidate-on-valset
                  context sheet-id instructions valset metric-fn
                  {:optimization-id optimization-id
                   :role :candidate
                   :call-scope candidate-id})
                (catch Exception e
                  (u/log ::evaluation-error
                         :error (.getMessage e)
                         :candidate-id candidate-id)
                  (when (= :budget-exhausted (:gepa/evaluation-status (ex-data e)))
                    (finish-at-admission-budget-exhaustion! context optimization-id))
                  (when-not (:gepa/evaluation-status (ex-data e))
                  (run-command! context
                    {:command/id (random-uuid)
                     :command/timestamp (time/now)
                     :command/name :gepa/fail-optimization
                     :optimization-id optimization-id
                     :error-message (str "Candidate evaluation failed: " (.getMessage e))}))
                  nil))
              (do
                (run-command! context
                  {:command/id (random-uuid)
                   :command/timestamp (time/now)
                   :command/name :gepa/fail-optimization
                   :optimization-id optimization-id
                   :error-message "Candidate evaluation requires a persisted non-empty validation set and workflow"})
                nil))]

        (when eval-result
          (u/log ::evaluation-complete
                 :candidate-id candidate-id
                 :mean-score (when (seq (:scores eval-result))
                               (/ (reduce + (:scores eval-result))
                                  (count (:scores eval-result)))))

          (run-command! context
            (cond-> {:command/id (random-uuid)
                     :command/timestamp (time/now)
                     :command/name :gepa/record-evaluation-result
                     :optimization-id optimization-id
                     :candidate-id candidate-id
                     :scores (:scores eval-result)
                     :trace-ids (:trace-ids eval-result)
                     :metric-calls (:metric-calls eval-result)
                     :accounting-mode :tasks :task-call-ids (:task-call-ids eval-result)}
              (:feedbacks eval-result) (assoc :feedbacks (:feedbacks eval-result))
              (:outputs eval-result) (assoc :outputs (:outputs eval-result)))))))))

(defn- completed-proposer-call
  [ctx call-id]
  (some #(when (= call-id (:call-id %)) %)
        (into [] (event-store/read (:event-store ctx)
                                   {:tenant-id (:tenant-id ctx)
                                    :types #{:gepa/proposer-call-completed}}))))

(defn generate-mutated-instruction
  "Generate a mutated instruction using the proposer.

   Arguments:
   - context: Grain context
   - parent-instructions: Map of component-name → instruction
   - component: Component to mutate
   - reflective-examples: Vector of ReflectiveExample maps
   - model-name: LLM model to use for proposal

   Returns:
   New instruction string."
  ([context parent-instructions component reflective-examples model-name]
   (generate-mutated-instruction context parent-instructions component
                                 reflective-examples model-name nil nil nil))
  ([context parent-instructions component reflective-examples model-name
    optimization-id parent-candidate-id iteration]
  (let [config (when optimization-id
                 (:config (rm/get-optimization-summary context optimization-id)))
        context (if optimization-id
                  (assoc context :gepa/reflection-options (:reflection-options config))
                  context)
        template (:reflection-prompt-template config)
        prompt-template (cond (string? template) template
                              (map? template) (get template (str component)))
        current-instruction (get parent-instructions component)
        formatted-examples (logic/format-reflective-examples reflective-examples)
        call-id (when (and optimization-id parent-candidate-id (some? iteration))
                  (logic/stable-call-id :gepa/proposer optimization-id parent-candidate-id
                                  iteration component))
        completed (when call-id (completed-proposer-call context call-id))
        llm-fn (logic/make-llm-fn
                 context model-name
                 (when (and optimization-id parent-candidate-id)
                   (fn [provenance]
                     (run-command!
                       context
                       (assoc (merge {:command/id (random-uuid)
                               :command/timestamp (time/now)
                               :command/name :gepa/record-proposer-call
                               :optimization-id optimization-id
                               :parent-candidate-id parent-candidate-id
                               :component (str component)}
                              provenance)
                              :call-id call-id)))))]

    (u/log ::generating-mutation
           :component component
           :current-instruction-length (count current-instruction)
           :num-examples (count reflective-examples))

    (if (or (empty? reflective-examples)
            (nil? current-instruction))
      (str current-instruction " Be more precise and complete in your answers.")
      (let [effective-llm-fn (if completed
                               (constantly (:response completed))
                               llm-fn)
            result (logic/propose-new-instruction
                     effective-llm-fn
                     current-instruction
                     formatted-examples
                     :prompt-template prompt-template)]
        (:proposed-instruction result))))))

(defn- reflection-budget-exhausted?
  "True once the durable proposer-call evidence has reached the optimization's
   `:max-reflection-tokens` (a call may take the total past it by its own usage)."
  [context optimization-id]
  (let [limit (get-in (rm/get-optimization-summary context optimization-id)
                      [:config :max-reflection-tokens])]
    (and (some? limit)
         (>= (rm/get-reflection-tokens-used context optimization-id) limit))))

(defn- fail-optimization! [context optimization-id message]
  (run-command! context
    {:command/id (random-uuid)
     :command/timestamp (time/now)
     :command/name :gepa/fail-optimization
     :optimization-id optimization-id
     :error-message message}))

(defn evaluate-candidate-on-subsample
  "Evaluate a candidate's instructions on a subsample (minibatch) of trainset.

   This is the key efficiency optimization from Python GEPA:
   - Evaluate on small minibatch (3 examples) BEFORE full valset
   - Only proceed to full eval if subsample shows improvement
   - Rejected candidates cost only 3 metric calls instead of 72

   Args:
     context: Grain context
     sheet-id: Workflow sheet to execute
     instructions: Map of {predictor-name -> instruction}
     trainset: Full training set
     indices: Vector of indices into trainset to evaluate
     metric-fn: Scoring function

   Returns {:scores [double ...] :trace-ids [uuid ...] :metric-calls int
            :outputs {training-index -> output-map}
            :feedbacks {training-index -> feedback-string}}.
   :scores and :trace-ids follow the order of `indices`; :outputs and
   :feedbacks are keyed by the TRAINING index each result belongs to, so a
   reflective example is always one example's input, output and feedback."
  ([context sheet-id instructions trainset indices metric-fn]
   (evaluate-candidate-on-subsample context sheet-id instructions trainset indices metric-fn nil))
  ([context sheet-id instructions trainset indices metric-fn call-context]
  (let [subsample (mapv #(nth trainset %) indices)
        call-context (prepare-task-batch! context sheet-id instructions
                       (map vector indices subsample) call-context)
        results (doall
                  (map-indexed
                    (fn [idx example]
                      (u/log ::evaluating-subsample
                             :index idx
                             :trainset-index (nth indices idx)
                             :total (count indices))
                      (execute-workflow-with-instructions
                        context sheet-id instructions example metric-fn
                        (assoc call-context :example-index (nth indices idx))))
                    subsample))]
    {:scores (mapv :score results)
     :trace-ids (mapv :trace-id results)
     :outputs (into {} (keep (fn [[index r]]
                               (when (some? (:output r)) [index (:output r)]))
                             (map vector indices results)))
     :feedbacks (into {} (keep (fn [[index r]]
                                 (when (:feedback r) [index (:feedback r)]))
                               (map vector indices results)))
     :metric-calls (if (:boundary-id call-context)
                     (reduce + (map :attempt-count results))
                     (count (filter :charged? results)))
     :task-call-ids (when (:boundary-id call-context) (mapv :task-call-id results))
     :accounting-mode (when (:boundary-id call-context) :tasks)})))

(defn- evaluate-parent-minibatch!
  "Evaluate the parent on its minibatch. Returns the result, or nil when the
   evaluation could not complete (the reason is logged or the optimization is
   finished/failed here)."
  [context optimization-id sheet-id parent-id parent-instructions trainset indices
   iteration metric-fn]
  (try
    (evaluate-candidate-on-subsample
      context sheet-id parent-instructions trainset indices metric-fn
      {:optimization-id optimization-id
       :role :subsample-parent
       :call-scope [parent-id iteration]})
    (catch Exception e
      (u/log ::parent-minibatch-evaluation-error
             :error (.getMessage e)
             :parent-id parent-id)
      (let [status (:gepa/evaluation-status (ex-data e))]
        (cond
          (= :budget-exhausted status)
          (finish-at-admission-budget-exhaustion! context optimization-id)

          status nil

          :else
          (fail-optimization! context optimization-id
                              (str "Parent minibatch evaluation failed: " (.getMessage e)))))
      nil)))

(defn on-parent-minibatch-evaluation-requested
  "Handle parent-minibatch-evaluation-requested: run the mutation round from
   the recorded parent, component, iteration and minibatch.

   1. Evaluate the PARENT on that training minibatch (role :subsample-parent,
      scope [parent-id iteration]). The validation set never takes part in
      reflection. Completed tasks are reused from the ledger and unfinished
      ones recovered, so running this again for the same record is safe.
   2. A parent perfect on its minibatch (with :skip-perfect-score) ends the
      round before any proposer call; the skipped comparison is published so
      completion is not blocked.
   3. Otherwise build the reflective examples from that evaluation alone and
      persist the proposal. The acceptance gate later reuses the parent's
      tasks (same role and call-scope) instead of running them again."
  [{:keys [event] :as context}]
  (let [{:keys [optimization-id parent-id component iteration]} event
        indices (:subsample-indices event)
        parent-instructions (:instructions (rm/get-candidate context optimization-id parent-id))
        opt-summary (rm/get-optimization-summary context optimization-id)
        opt-config (:config opt-summary {})
        sheet-id (:sheet-id opt-summary)
        reflection-model (get opt-config :reflection-lm "anthropic/claude-sonnet-4")
        skip-perfect? (get opt-config :skip-perfect-score true)
        trainset (rm/get-trainset context optimization-id)
        metric-fn (or (logic/get-metric-fn optimization-id)
                      (:gepa/metric-fn context)
                      logic/default-metric-fn)]
    (u/log ::on-parent-minibatch-evaluation-requested
           :parent-id parent-id :component component :iteration iteration
           :subsample-indices indices)
    (if-not (and (seq trainset) sheet-id)
      (fail-optimization! context optimization-id
                          "Subsample evaluation requires a persisted non-empty training set and workflow")
      (when-let [parent-result (evaluate-parent-minibatch!
                                 context optimization-id sheet-id parent-id
                                 parent-instructions trainset indices iteration metric-fn)]
        (if (and skip-perfect? (every? #(>= % 1.0) (:scores parent-result)))
          (do
            (u/log ::skipping-perfect-parent
                   :parent-id parent-id
                   :parent-scores (:scores parent-result))
            (run-command! context
              {:command/id (random-uuid)
               :command/timestamp (time/now)
               :command/name :gepa/record-subsample-result
               :optimization-id optimization-id
               :parent-id parent-id
               :proposed-instructions parent-instructions
               :component-updated component
               :subsample-indices indices
               :iteration iteration
               :parent-scores (:scores parent-result)
               :proposed-scores (:scores parent-result)
               :accepted? false
               :metric-calls (:metric-calls parent-result)
               :accounting-mode :tasks
               :task-call-ids (:task-call-ids parent-result)
               :parent-perfect-skip? true}))
          (if (and (reflection-budget-exhausted? context optimization-id)
                   (not (completed-proposer-call
                          context (logic/stable-call-id :gepa/proposer optimization-id parent-id
                                                  iteration component))))
            (finish-at-admission-budget-exhaustion! context optimization-id :reflection-tokens)
          (try
            (let [examples (logic/build-reflective-dataset trainset indices parent-result)
                  new-instruction (generate-mutated-instruction
                                    context parent-instructions component examples
                                    reflection-model optimization-id parent-id iteration)]
              (u/log ::mutation-generated
                     :component component
                     :num-reflective-examples (count examples)
                     :new-instruction-length (count new-instruction))
              (run-command! context
                {:command/id (random-uuid)
                 :command/timestamp (time/now)
                 :command/name :gepa/record-proposal-ready
                 :optimization-id optimization-id
                 :proposal-id (logic/stable-call-id :gepa/proposal optimization-id parent-id iteration component)
                 :parent-id parent-id
                 :proposed-instructions (assoc parent-instructions component new-instruction)
                 :component-updated component
                 :subsample-indices indices
                 :iteration iteration}))
            (catch Exception e
              (fail-optimization! context optimization-id
                                  (str "Instruction proposal failed: " (.getMessage e)))))))))))

(defn on-subsample-evaluation-started
  "Handle subsample-evaluation-started event.

   Python GEPA parity: Evaluate BOTH parent and proposed instructions
   on the SAME subsample (minibatch), then compare scores.

   This is the key efficiency optimization:
   - Only 3 examples evaluated for parent (may be cached)
   - Only 3 examples evaluated for proposed
   - Total: 6 metric calls max (vs 72+ for full valset)
   - Candidate only created if proposed > parent"
  [{:keys [event] :as context}]
  (u/log ::on-subsample-evaluation-started
         :parent-id (:parent-id event)
         :subsample-indices (:subsample-indices event)
         :iteration (:iteration event))

  (let [{:keys [optimization-id parent-id proposed-instructions
                component-updated subsample-indices iteration]} event
        opt-summary (rm/get-optimization-summary context optimization-id)
        sheet-id (:sheet-id opt-summary)

        trainset (rm/get-trainset context optimization-id)

        metric-fn (or (logic/get-metric-fn optimization-id)
                      (:gepa/metric-fn context)
                      logic/default-metric-fn)

        parent-candidate (rm/get-candidate context optimization-id parent-id)
        parent-instructions (:instructions parent-candidate)]

    (let [opt-config (get opt-summary :config {})
          skip-perfect? (get opt-config :skip-perfect-score true)]

      (if (and trainset (seq trainset) sheet-id)
        (try
          (let [
                parent-result (evaluate-candidate-on-subsample
                                context sheet-id parent-instructions
                                trainset subsample-indices metric-fn
                                {:optimization-id optimization-id
                                 :role :subsample-parent
                                 :call-scope [parent-id iteration]})
                parent-scores (:scores parent-result)
                parent-sum (reduce + parent-scores)
                parent-perfect? (and skip-perfect?
                                     (every? #(>= % 1.0) parent-scores))]

            (if parent-perfect?
              (do
                (u/log ::skipping-perfect-parent
                       :parent-id parent-id
                       :parent-scores parent-scores
                       :metric-calls (:metric-calls parent-result))
                (run-command! context
                  {:command/id (random-uuid)
                   :command/timestamp (time/now)
                   :command/name :gepa/record-subsample-result
                   :optimization-id optimization-id
                   :parent-id parent-id
                   :proposed-instructions proposed-instructions
                   :component-updated component-updated
                   :subsample-indices subsample-indices
                   :iteration iteration
                   :parent-scores parent-scores
                   :proposed-scores parent-scores
                   :accepted? false
                   :metric-calls (:metric-calls parent-result)
                   :accounting-mode :tasks :task-call-ids (:task-call-ids parent-result)
                   :parent-perfect-skip? true}))

              (let [
                    proposed-result (evaluate-candidate-on-subsample
                                      context sheet-id proposed-instructions
                                      trainset subsample-indices metric-fn
                                      {:optimization-id optimization-id
                                       :role :subsample-proposed
                                       :call-scope [parent-id iteration
                                                    (logic/sha256-string proposed-instructions)]})
                    proposed-scores (:scores proposed-result)
                    proposed-sum (reduce + proposed-scores)

                    accepted? (> proposed-sum parent-sum)

                    metric-calls (+ (:metric-calls parent-result)
                                    (:metric-calls proposed-result))]

                (u/log ::subsample-evaluation-complete
                       :parent-sum parent-sum
                       :proposed-sum proposed-sum
                       :accepted? accepted?
                       :metric-calls metric-calls)

                (run-command! context
                  {:command/id (random-uuid)
                   :command/timestamp (time/now)
                   :command/name :gepa/record-subsample-result
                   :optimization-id optimization-id
                   :parent-id parent-id
                   :proposed-instructions proposed-instructions
                   :component-updated component-updated
                   :subsample-indices subsample-indices
                   :iteration iteration
                   :parent-scores parent-scores
                   :proposed-scores proposed-scores
                   :accepted? accepted?
                   :metric-calls metric-calls
                   :accounting-mode :tasks
                   :task-call-ids (into (:task-call-ids parent-result) (:task-call-ids proposed-result))}))))

        (catch Exception e
          (u/log ::subsample-evaluation-error
                 :error (.getMessage e)
                 :parent-id parent-id)
          (when (= :budget-exhausted (:gepa/evaluation-status (ex-data e)))
            (finish-at-admission-budget-exhaustion! context optimization-id))
          nil))

      (run-command! context
        {:command/id (random-uuid)
         :command/timestamp (time/now)
         :command/name :gepa/fail-optimization
         :optimization-id optimization-id
         :error-message "Subsample evaluation requires a persisted non-empty training set and workflow"})))))

(defn- resume-step!
  [ctx optimization-id]
  (let [summary (rm/get-optimization-summary ctx optimization-id)
        remaining-budget (- (get-in summary [:config :max-metric-calls] 0)
                            (or (:total-metric-calls summary) 0))
        events (into []
                     (filter #(= optimization-id (:optimization-id %)))
                     (event-store/read (:event-store ctx)
                                       {:tenant-id (:tenant-id ctx)}))
        by-type (group-by :event/type events)
        terminal (last (concat (get by-type :gepa/optimization-completed)
                               (get by-type :gepa/optimization-failed)))
        evaluated-ids (set (map :candidate-id
                                (get by-type :gepa/candidate-evaluated)))
        evaluation-started-ids (set (map :candidate-id
                                         (get by-type :gepa/candidate-evaluation-started)))
        subsample-results (set (map (juxt :parent-id :iteration)
                                    (get by-type :gepa/subsample-evaluated)))
        subsample-starts (set (map (juxt :parent-id :iteration)
                                   (get by-type :gepa/subsample-evaluation-started)))
        pending-proposal (last
                           (remove #(contains? subsample-starts
                                               [(:parent-id %) (:iteration %)])
                                   (get by-type :gepa/proposal-ready)))
        round-decided (set (concat (map (juxt :parent-id :iteration)
                                        (get by-type :gepa/proposal-ready))
                                   subsample-results))
        pending-round (last
                        (remove #(contains? round-decided [(:parent-id %) (:iteration %)])
                                (get by-type :gepa/parent-minibatch-evaluation-requested)))
        pending-subsample (last
                            (remove #(contains? subsample-results
                                                [(:parent-id %) (:iteration %)])
                                    (get by-type :gepa/subsample-evaluation-started)))
        pending-evaluation (last
                             (remove #(contains? evaluated-ids (:candidate-id %))
                                     (get by-type :gepa/candidate-evaluation-started)))
        pending-candidate (last
                            (remove #(or (contains? evaluated-ids (:candidate-id %))
                                         (contains? evaluation-started-ids (:candidate-id %)))
                                    (get by-type :gepa/candidate-created)))]
    (cond
      terminal
      {:optimization-id optimization-id :status :already-terminal
       :terminal-event (:event/type terminal)}

      pending-subsample
      (do (on-subsample-evaluation-started (assoc ctx :event pending-subsample))
          {:optimization-id optimization-id :status :resumed
           :boundary :subsample-evaluation
           :parent-id (:parent-id pending-subsample)})

      pending-proposal
      (do (commands/command! ctx {:command/name :gepa/mark-optimization-resumed
                         :optimization-id optimization-id})
          (let [started (commands/command! ctx
                                  {:command/name :gepa/evaluate-on-subsample
                                   :optimization-id optimization-id
                                   :parent-id (:parent-id pending-proposal)
                                   :proposed-instructions
                                   (:proposed-instructions pending-proposal)
                                   :component-updated
                                   (:component-updated pending-proposal)
                                   :subsample-indices
                                   (:subsample-indices pending-proposal)
                                   :iteration (:iteration pending-proposal)})
                start-event (some #(when (= :gepa/subsample-evaluation-started
                                                (:event/type %)) %)
                                  (:command-result/events started))]
            (when start-event
              (on-subsample-evaluation-started
                (assoc ctx :event start-event))))
          {:optimization-id optimization-id :status :resumed
           :boundary :proposal-ready
           :remaining-budget remaining-budget
           :parent-id (:parent-id pending-proposal)})

      pending-round
      (do (on-parent-minibatch-evaluation-requested (assoc ctx :event pending-round))
          {:optimization-id optimization-id :status :resumed
           :boundary :parent-minibatch-evaluation
           :parent-id (:parent-id pending-round)})

      pending-evaluation
      (do (on-candidate-evaluation-started (assoc ctx :event pending-evaluation))
          {:optimization-id optimization-id :status :resumed
           :boundary :candidate-evaluation
           :candidate-id (:candidate-id pending-evaluation)})

      pending-candidate
      (do (on-candidate-created (assoc ctx :event pending-candidate))
          {:optimization-id optimization-id :status :resumed
           :boundary :candidate-created
           :candidate-id (:candidate-id pending-candidate)})

      :else
      {:optimization-id optimization-id :status :no-recoverable-boundary})))

(defn resume!
  "Resume one incomplete GEPA transition from immutable state.

   The function deliberately advances only the first missing transition. It is
   safe to call repeatedly (or after process reconstruction): command guards
   and terminal events remain authoritative, and completed proposer calls are
   never invoked again merely to reconstruct their proposal.

   The result also reports `:reflection-tokens-used`, the tokens recorded by
   the optimization's proposer calls."
  [ctx optimization-id]
  (assoc (resume-step! ctx optimization-id)
         :reflection-tokens-used (rm/get-reflection-tokens-used ctx optimization-id)))

(defn- optimization-resumed?
  [ctx optimization-id]
  (seq (into []
             (event-store/read (:event-store ctx)
                               {:tenant-id (:tenant-id ctx)
                                :types #{:gepa/optimization-resumed}
                                :tags #{[:optimization optimization-id]}}))))

(defn select-component
  "Select which component of `parent-id` to mutate next, round-robin.

   Each candidate rotates through its component names (sorted, so the order is
   stable) and its pointer advances once for every round that reached
   reflection; a skipped round does not advance it, and a child starts at the
   furthest pointer among its parents. The pointer is derived from recorded
   events (`rm/gepa-state`), so a redelivery chooses the same component."
  [context optimization-id parent-id]
  (let [state (rm/get-gepa-state context optimization-id)
        idx (get-in state [:candidate-id->idx parent-id])]
    (or (when idx (logic/get-next-component-to-update state idx))
        (first (sort (keys (:instructions (rm/get-candidate context optimization-id parent-id))))))))

;; =============================================================================
;; On Optimization Started
;; =============================================================================

(defn on-optimization-started
  "Handle optimization-started event.

   1. Extract instructions from target workflow (seed candidate)
   2. Create seed candidate
   3. Query ALL unique candidates from previous runs (cumulative)
   4. Create inherited candidates for top performers

   This enables cumulative learning - 5 epochs × 20 runs = 100 epochs × 1 run."
  [{:keys [event] :as context}]
  (u/log ::on-optimization-started
         :optimization-id (:optimization-id event))

  (let [{:keys [optimization-id sheet-id config]} event
        inherit? (get config :inherit-from-previous true)
        inherit-limit (get config :inherit-limit 10)

        seed-instructions (logic/extract-workflow-instructions context sheet-id)

        _ (run-command! context
            {:command/id (random-uuid)
             :command/timestamp (time/now)
             :command/name :gepa/create-candidate
             :optimization-id optimization-id
             :instructions seed-instructions
             :parent-ids [nil]
             :mutation-reason nil
             :source-optimization-id nil})

        inherited-candidates (when inherit?
                               (rm/get-all-unique-candidates
                                 context sheet-id
                                 :limit inherit-limit))]

    (when (seq inherited-candidates)
      (u/log ::inheriting-candidates
             :count (count inherited-candidates)
             :optimization-id optimization-id)

      (doseq [cand inherited-candidates
              :let [inst (:instructions cand)
                    subscores (:val-subscores cand)]
              :when (not= inst seed-instructions)]
        (u/log ::inheriting-candidate
               :source-optimization-id (:source-optimization-id cand)
               :score (:aggregate-score cand)
               :num-subscores (count subscores))
        (run-command! context
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :gepa/create-candidate
           :optimization-id optimization-id
           :instructions inst
           :parent-ids [nil]
           :mutation-reason "inherited-from-previous-run"
           :source-optimization-id (:source-optimization-id cand)
           :historical-val-subscores subscores})))))

(defn- request-mutation-round-within-budget!
  [context optimization-id fallback-parent-id]
  (let [parent-id (or (rm/sample-pareto-frontier context optimization-id)
                      fallback-parent-id)
        component (select-component context optimization-id parent-id)
        opt-summary (rm/get-optimization-summary context optimization-id)
        minibatch-size (get-in opt-summary [:config :reflection-minibatch-size] 3)
        iteration (rm/get-iteration-count context optimization-id)
        trainset (rm/get-trainset context optimization-id)]
    (if-not (and (seq trainset) (:sheet-id opt-summary))
      (fail-optimization! context optimization-id
                          "Subsample evaluation requires a persisted non-empty training set and workflow")
      (run-command! context
        {:command/id (random-uuid)
         :command/timestamp (time/now)
         :command/name :gepa/request-parent-minibatch-evaluation
         :optimization-id optimization-id
         :parent-id parent-id
         :component component
         :iteration iteration
         :subsample-indices (vec (distinct (logic/epoch-shuffled-batch-sampler
                                             (count trainset) minibatch-size iteration)))}))))

(defn- request-mutation-round!
  "Open a mutation round: choose the parent, the component and the training
   minibatch, and record the request (`ParentMinibatchEvaluationRequested`) as
   its own durable boundary. Nothing is evaluated here; the processor of that
   event runs the round from the recorded values, and `resume!` re-runs it from
   the same record after an interruption.

   The minibatch is drawn by the deterministic epoch-shuffled sampler. The
   sampler pads a short last batch with a repeated index; one example is
   evaluated, and reflected on, once, so the recorded indices are distinct.

   `fallback-parent-id` is used when the frontier is empty."
  [context optimization-id fallback-parent-id]
  (if (reflection-budget-exhausted? context optimization-id)
    (finish-at-admission-budget-exhaustion! context optimization-id :reflection-tokens)
    (request-mutation-round-within-budget! context optimization-id fallback-parent-id)))

;; =============================================================================
;; On Candidate Evaluated
;; =============================================================================

(defn on-candidate-evaluated
  "Handle candidate-evaluated event.

   Python GEPA parity with subsample-first evaluation:
   1. Update Pareto frontier
   2. Check if budget exhausted
   3. If budget remains: run a mutation round (evaluate the sampled parent on
      its training minibatch, reflect on that evaluation, persist the proposal)
   4. If exhausted: complete optimization

   IMPORTANT: New mutated candidates go through subsample evaluation FIRST.
   The candidate is NOT created until it passes subsample evaluation.
   This matches Python GEPA's efficiency optimization."
  [{:keys [event] :as context}]
  (u/log ::on-candidate-evaluated
         :candidate-id (:candidate-id event)
         :aggregate-score (:aggregate-score event))

  (let [{:keys [optimization-id candidate-id scores aggregate-score]} event
        event-store (:event-store context)

        _ (run-command! context
            {:command/id (random-uuid)
             :command/timestamp (time/now)
             :command/name :gepa/update-frontier
             :optimization-id optimization-id
             :candidate-id candidate-id
             :scores scores})

        budget-exhausted? (rm/budget-exhausted? context optimization-id)]

    (if budget-exhausted?
      (do
        (u/log ::budget-exhausted :optimization-id optimization-id)
        (run-command! context
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :gepa/complete-optimization
           :optimization-id optimization-id}))

      (request-mutation-round! context optimization-id candidate-id))))

;; =============================================================================
;; On Subsample Evaluation Started
;; =============================================================================

(defn on-proposal-ready
  "Advance a durable proposal into evaluation unless an operator requested a
   pause at this exact recovery boundary. `gepa/resume!` advances paused work
   explicitly after reconstruction."
  [{:keys [event] :as context}]
  (let [{:keys [optimization-id parent-id proposed-instructions
                component-updated subsample-indices iteration]} event
        config (:config (rm/get-optimization-summary context optimization-id))]
    (when-not (and (:pause-after-proposal? config)
                   (not (optimization-resumed? context optimization-id)))
      (run-command! context
        {:command/id (random-uuid)
         :command/timestamp (time/now)
         :command/name :gepa/evaluate-on-subsample
         :optimization-id optimization-id
         :parent-id parent-id
         :proposed-instructions proposed-instructions
         :component-updated component-updated
         :subsample-indices subsample-indices
         :iteration iteration}))))

;; =============================================================================
;; On Subsample Evaluated
;; =============================================================================

(defn on-subsample-evaluated
  "Handle subsample-evaluated event.

   Python GEPA parity:
   - If accepted (proposed_sum > parent_sum): Create candidate and trigger FULL evaluation
   - If rejected: Propose another mutation (back to on-candidate-evaluated flow)

   This is where the efficiency gain happens:
   - Rejected mutations: only cost 6 metric calls (subsample only)
   - Accepted mutations: cost 6 + 72 = 78 metric calls (subsample + full valset)"
  [{:keys [event] :as context}]
  (u/log ::on-subsample-evaluated
         :accepted? (:accepted? event)
         :parent-sum (:parent-sum event)
         :proposed-sum (:proposed-sum event))

  (let [{:keys [optimization-id parent-id proposed-instructions
                component-updated accepted? metric-calls]} event
        event-store (:event-store context)]

    (if accepted?
      (do
        (u/log ::subsample-accepted
               :creating-candidate true
               :triggering-full-eval true)

        (run-command! context
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :gepa/create-candidate
           :optimization-id optimization-id
           :instructions proposed-instructions
           :parent-ids [parent-id]
           :mutation-reason (str "Mutated " component-updated " - passed subsample eval")}))

      (let [budget-exhausted? (rm/budget-exhausted? context optimization-id)]
        (if budget-exhausted?
          (do
            (u/log ::budget-exhausted-after-rejection :optimization-id optimization-id)
            (run-command! context
              {:command/id (random-uuid)
               :command/timestamp (time/now)
               :command/name :gepa/complete-optimization
               :optimization-id optimization-id}))

          (request-mutation-round! context optimization-id parent-id))))))

;; =============================================================================
;; On Optimization Completed
;; =============================================================================

(defn on-optimization-completed
  "Handle optimization-completed event.

   Log completion, deliver result to waiting callers, and apply best instructions."
  [{:keys [event] :as context}]
  (let [{:keys [optimization-id best-candidate-id final-score improvement]} event
        event-store (:event-store context)
        best-candidate (when best-candidate-id
                         (rm/get-candidate context optimization-id best-candidate-id))]

    (u/log ::on-optimization-completed
           :optimization-id optimization-id
           :best-candidate-id best-candidate-id
           :final-score final-score
           :improvement improvement)

    (logic/deliver-completion!
      optimization-id
      {:optimization-id optimization-id
       :status :completed
       :best-candidate (when best-candidate
                         {:instructions (:instructions best-candidate)
                          :score (:aggregate-score best-candidate)
                          :candidate-id best-candidate-id})
       :best-score final-score
       :improvement improvement})))

;; =============================================================================
;; Todo Processor Registry
;; =============================================================================

(defprocessor :gepa on-optimization-started
  {:topics #{:gepa/optimization-started}}
  "Handle optimization-started event: create seed candidate and inherit from previous runs."
  [context]
  (on-optimization-started context))

(defprocessor :gepa on-candidate-created
  {:topics #{:gepa/candidate-created}}
  "Handle candidate-created event: trigger evaluation."
  [context]
  (on-candidate-created context))

(defprocessor :gepa on-candidate-evaluation-started
  {:topics #{:gepa/candidate-evaluation-started}}
  "Handle candidate-evaluation-started event: evaluate on validation set."
  [context]
  (on-candidate-evaluation-started context))

(defprocessor :gepa on-candidate-evaluated
  {:topics #{:gepa/candidate-evaluated}}
  "Handle candidate-evaluated event: update frontier, propose mutation, subsample eval."
  [context]
  (on-candidate-evaluated context))

(defprocessor :gepa on-parent-minibatch-evaluation-requested
  {:topics #{:gepa/parent-minibatch-evaluation-requested}}
  "Run a recorded mutation round: evaluate the parent on its minibatch, then skip or propose."
  [context]
  (on-parent-minibatch-evaluation-requested context))

(defprocessor :gepa on-subsample-evaluation-started
  {:topics #{:gepa/subsample-evaluation-started}}
  "Handle subsample-evaluation-started: evaluate parent and proposed on minibatch."
  [context]
  (on-subsample-evaluation-started context))

(defprocessor :gepa on-proposal-ready
  {:topics #{:gepa/proposal-ready}}
  "Advance a persisted proposal to its subsample evaluation boundary."
  [context]
  (on-proposal-ready context))

(defprocessor :gepa on-subsample-evaluated
  {:topics #{:gepa/subsample-evaluated}}
  "Handle subsample-evaluated: accept or reject mutation."
  [context]
  (on-subsample-evaluated context))

(defprocessor :gepa on-mutation-proposed
  {:topics #{:gepa/mutation-proposed}}
  "Handle mutation-proposed event (logging, backwards compatibility)."
  [context]
  (logic/on-mutation-proposed context))

(defprocessor :gepa on-frontier-updated
  {:topics #{:gepa/frontier-updated}}
  "Handle frontier-updated event (logging)."
  [context]
  (logic/on-frontier-updated context))

(defprocessor :gepa on-optimization-completed
  {:topics #{:gepa/optimization-completed}}
  "Handle optimization-completed: deliver result, apply best instructions."
  [context]
  (on-optimization-completed context))

(defprocessor :gepa on-optimization-failed
  {:topics #{:gepa/optimization-failed}}
  "Handle optimization-failed: deliver failure to waiting callers."
  [context]
  (logic/on-optimization-failed context))

(defprocessor :gepa on-recovery-scan-triggered
  {:topics #{:gepa/recovery-scan-triggered}}
  "Discover abandoned evaluations using canonical task ownership."
  [context]
  (commands/recover-in-progress! context)
  {})

(defprocessor :gepa on-optimization-recovery-requested
  {:topics #{:gepa/optimization-recovery-requested}}
  "Resume the original durable boundary on the normal Grain processor pool."
  [context]
  (resume!
    context (get-in context [:event :optimization-id]))
  {})
