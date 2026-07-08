(ns ai.obney.orc.gepa.core.commands
  "Command handlers for GEPA optimization.

   Commands trigger state changes by emitting events.
   All state is derived from events, not stored directly.

   Command flow:
   1. Validate input
   2. Query current state via read models
   3. Apply business rules
   4. Emit events or return anomaly"
  (:require [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.core.pareto :as pareto]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :as command-processor :refer [defcommand]]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Default Configuration
;; =============================================================================

(def default-config
  {:max-metric-calls 50
   :reflection-minibatch-size 3
   :reflection-lm "anthropic/claude-sonnet-4"
   :use-merge true
   :crossover-rate 0.3})

(defn merge-config
  "Merge user config with defaults."
  [user-config]
  (merge default-config user-config))

;; =============================================================================
;; Start Optimization
;; =============================================================================

(defcommand :gepa start-optimization
  "Start a new GEPA optimization run.

   Creates the optimization context and prepares for
   seed candidate creation and evaluation loop.

   Emits two events:
   1. :gepa/optimization-started - Metadata about the optimization
   2. :gepa/datasets-stored - Full trainset and valset for evaluation"
  [{{:keys [sheet-id trainset valset config optimization-id]} :command
    :keys [event-store] :as ctx}]
  (let [optimization-id (or optimization-id (random-uuid))
        merged-config (merge-config (or config {}))
        now (time/now)
        ;; Normalize trainset/valset to use string keys for JSON compatibility
        normalize-keys (fn [m] (into {} (map (fn [[k v]] [(name k) v]) m)))
        normalized-trainset (mapv normalize-keys trainset)
        normalized-valset (mapv normalize-keys valset)]
    {:command-result/events
     [;; Event 1: Optimization started with metadata
      (->event {:type :gepa/optimization-started
                :tags #{[:optimization optimization-id]
                        [:sheet sheet-id]}
                :body {:optimization-id optimization-id
                       :sheet-id sheet-id
                       :config merged-config
                       :trainset-size (count trainset)
                       :valset-size (count valset)
                       :started-at now}})
      ;; Event 2: Store full datasets for evaluation and cross-run persistence
      (->event {:type :gepa/datasets-stored
                :tags #{[:optimization optimization-id]}
                :body {:optimization-id optimization-id
                       :trainset normalized-trainset
                       :valset normalized-valset
                       :stored-at now}})]
     :command/result {:optimization-id optimization-id
                      :config merged-config}}))

;; =============================================================================
;; Create Candidate
;; =============================================================================

(defcommand :gepa create-candidate
  "Create a new candidate in the population.

   Candidates are instruction sets to be evaluated.
   Parent IDs track lineage for mutation history.
   Source-optimization-id tracks cross-run inheritance.
   Historical-val-subscores enables skipping re-evaluation for inherited candidates."
  [{{:keys [optimization-id instructions parent-ids mutation-reason
            source-optimization-id historical-val-subscores]} :command
    :keys [event-store] :as ctx}]
  (let [opt-state (rm/get-optimization-summary ctx optimization-id)]
    (cond
      ;; Optimization not found
      (nil? (:optimization-id opt-state))
      {::anom/category ::anom/not-found
       ::anom/message "Optimization not found"}

      ;; Optimization already completed
      (= :completed (:status opt-state))
      {::anom/category ::anom/conflict
       ::anom/message "Optimization already completed"}

      ;; Create the candidate
      :else
      (let [candidate-id (random-uuid)
            pop-state (rm/get-population-state ctx optimization-id)
            ;; Determine generation from parents
            generation (if (some some? parent-ids)
                         (let [parent-gens (->> parent-ids
                                                (filter some?)
                                                (map #(get-in pop-state [:candidates % :generation] 0)))]
                           (if (seq parent-gens)
                             (inc (apply max parent-gens))
                             0))
                         0)]
        {:command-result/events
         [(->event {:type :gepa/candidate-created
                    :tags #{[:optimization optimization-id]
                            [:candidate candidate-id]}
                    :body {:optimization-id optimization-id
                           :candidate-id candidate-id
                           :instructions instructions
                           :parent-ids parent-ids
                           :generation generation
                           :mutation-reason mutation-reason
                           :source-optimization-id source-optimization-id  ;; Track cross-run inheritance
                           :historical-val-subscores historical-val-subscores  ;; Skip eval if present
                           :created-at (time/now)}})]
         :command/result {:candidate-id candidate-id
                          :generation generation}}))))

;; =============================================================================
;; Evaluate Candidate
;; =============================================================================

(defcommand :gepa evaluate-candidate
  "Evaluate a candidate on the validation set.

   This command triggers the evaluation process.
   The actual evaluation happens in the todo processor
   which executes the workflow and records traces."
  [{{:keys [optimization-id candidate-id]} :command
    :keys [event-store] :as ctx}]
  (let [candidate (rm/get-candidate ctx optimization-id candidate-id)]
    (cond
      ;; Candidate not found
      (nil? candidate)
      {::anom/category ::anom/not-found
       ::anom/message "Candidate not found"}

      ;; Already evaluated
      (= :evaluated (:status candidate))
      {::anom/category ::anom/conflict
       ::anom/message "Candidate already evaluated"}

      ;; Start evaluation
      :else
      {:command-result/events
       [(->event {:type :gepa/candidate-evaluation-started
                  :tags #{[:optimization optimization-id]
                          [:candidate candidate-id]}
                  :body {:optimization-id optimization-id
                         :candidate-id candidate-id
                         :started-at (time/now)}})]
       :command/result {:status :evaluation-started}})))

;; =============================================================================
;; Record Evaluation Result
;; =============================================================================

(defcommand :gepa record-evaluation-result
  "Record the results of a candidate evaluation.

   Called after workflow execution and trace collection.
   Updates the population with scores and triggers frontier update."
  [{{:keys [optimization-id candidate-id scores trace-ids feedbacks outputs metric-calls]} :command
    :keys [event-store] :as ctx}]
  (let [candidate (rm/get-candidate ctx optimization-id candidate-id)]
    (cond
      ;; Candidate not found
      (nil? candidate)
      {::anom/category ::anom/not-found
       ::anom/message "Candidate not found"}

      ;; Already has scores
      (:scores candidate)
      {::anom/category ::anom/conflict
       ::anom/message "Candidate already has evaluation results"}

      ;; Record results
      :else
      (let [aggregate-score (if (seq scores)
                              (/ (reduce + scores) (count scores))
                              0.0)]
        {:command-result/events
         [(->event {:type :gepa/candidate-evaluated
                    :tags #{[:optimization optimization-id]
                            [:candidate candidate-id]}
                    :body {:optimization-id optimization-id
                           :candidate-id candidate-id
                           :scores (vec scores)
                           :aggregate-score aggregate-score
                           :trace-ids (vec trace-ids)
                           ;; Per-instance RICH judge feedback (instance-idx ->
                           ;; weakest-first feedback string). Empty/absent for
                           ;; structural metrics. The reflective dataset uses
                           ;; this instead of the score-only string.
                           :feedbacks (or feedbacks {})
                           ;; Per-instance generated OUTPUT (instance-idx ->
                           ;; output blackboard map). Empty/absent when the
                           ;; workflow produced no output. The reflective dataset
                           ;; puts this real output (the actual bad answer) into
                           ;; the proposer's "Generated Outputs" slot.
                           :outputs (or outputs {})
                           :metric-calls metric-calls
                           :evaluated-at (time/now)}})]
         :command/result {:aggregate-score aggregate-score
                          :num-scores (count scores)}}))))

;; =============================================================================
;; Update Frontier
;; =============================================================================

(defcommand :gepa update-frontier
  "Update the Pareto frontier with a newly evaluated candidate.

   Determines which instances (if any) this candidate is best at
   and emits a frontier update event if it's Pareto-optimal."
  [{{:keys [optimization-id candidate-id scores]} :command
    :keys [event-store] :as ctx}]
  (let [frontier (rm/get-pareto-frontier-state ctx optimization-id)
        ;; Check which instances this candidate is now best at
        instances-best-at
        (reduce-kv
          (fn [best-at idx score]
            (let [current-best (get-in frontier [:max-scores idx] Double/NEGATIVE_INFINITY)]
              (if (>= score current-best)
                (conj best-at idx)
                best-at)))
          []
          (zipmap (range) scores))]

    (if (seq instances-best-at)
      ;; Candidate is Pareto-optimal
      {:command-result/events
       [(->event {:type :gepa/frontier-updated
                  :tags #{[:optimization optimization-id]
                          [:candidate candidate-id]}
                  :body {:optimization-id optimization-id
                         :candidate-id candidate-id
                         :instances-best-at instances-best-at
                         :updated-at (time/now)}})]
       :command/result {:pareto-optimal? true
                        :instances-best-at instances-best-at}}

      ;; Not Pareto-optimal
      {:command/result {:pareto-optimal? false
                        :instances-best-at []}})))

;; =============================================================================
;; Evaluate on Subsample (Python GEPA Parity)
;; =============================================================================

(defcommand :gepa evaluate-on-subsample
  "Trigger subsample evaluation for proposed instructions.

   Python GEPA parity: Before committing to full valset evaluation,
   first evaluate BOTH parent and proposed instructions on a small
   minibatch (3 examples). Only proceed to full eval if the proposed
   instructions show strict improvement (proposed_sum > parent_sum).

   This is the key efficiency optimization:
   - Rejected mutations cost only ~6 metric calls (3 parent + 3 proposed)
   - Accepted mutations cost ~6 + 72 = ~78 calls (subsample + full valset)
   - Net savings: ~60% if 50% rejection rate"
  [{{:keys [optimization-id parent-id proposed-instructions
            component-updated subsample-indices iteration]} :command
    :keys [event-store] :as ctx}]
  (let [opt-state (rm/get-optimization-summary ctx optimization-id)
        parent (rm/get-candidate ctx optimization-id parent-id)]
    (cond
      ;; Optimization not found
      (nil? (:optimization-id opt-state))
      {::anom/category ::anom/not-found
       ::anom/message "Optimization not found"}

      ;; Parent not found
      (nil? parent)
      {::anom/category ::anom/not-found
       ::anom/message "Parent candidate not found"}

      ;; Budget exhausted
      (rm/budget-exhausted? ctx optimization-id)
      {::anom/category ::anom/conflict
       ::anom/message "Metric budget exhausted"}

      ;; Trigger subsample evaluation
      :else
      {:command-result/events
       [(->event {:type :gepa/subsample-evaluation-started
                  :tags #{[:optimization optimization-id]
                          [:parent parent-id]}
                  :body {:optimization-id optimization-id
                         :parent-id parent-id
                         :proposed-instructions proposed-instructions
                         :component-updated component-updated
                         :subsample-indices (vec subsample-indices)
                         :iteration iteration
                         :started-at (time/now)}})]
       :command/result {:status :subsample-evaluation-started
                        :iteration iteration
                        :subsample-size (count subsample-indices)}})))

;; =============================================================================
;; Record Subsample Result (Python GEPA Parity)
;; =============================================================================

(defcommand :gepa record-subsample-result
  "Record the result of subsample evaluation.

   Emits subsample-evaluated event which triggers either:
   - Candidate creation + full eval (if accepted)
   - Another mutation attempt (if rejected)

   The acceptance criterion matches Python GEPA:
   proposed_sum > parent_sum (strict improvement, not >=)"
  [{{:keys [optimization-id parent-id proposed-instructions component-updated
            subsample-indices parent-scores proposed-scores accepted? metric-calls]} :command
    :keys [event-store] :as ctx}]
  (let [opt-state (rm/get-optimization-summary ctx optimization-id)
        parent-sum (reduce + parent-scores)
        proposed-sum (reduce + proposed-scores)]
    (cond
      ;; Optimization not found
      (nil? (:optimization-id opt-state))
      {::anom/category ::anom/not-found
       ::anom/message "Optimization not found"}

      ;; Record the subsample result
      :else
      {:command-result/events
       [(->event {:type :gepa/subsample-evaluated
                  :tags #{[:optimization optimization-id]
                          [:parent parent-id]}
                  :body {:optimization-id optimization-id
                         :parent-id parent-id
                         :proposed-instructions proposed-instructions
                         :component-updated component-updated
                         :subsample-indices (vec subsample-indices)
                         :parent-scores (vec parent-scores)
                         :proposed-scores (vec proposed-scores)
                         :parent-sum parent-sum
                         :proposed-sum proposed-sum
                         :accepted? accepted?
                         :metric-calls metric-calls
                         :evaluated-at (time/now)}})]
       :command/result {:accepted? accepted?
                        :parent-sum parent-sum
                        :proposed-sum proposed-sum
                        :metric-calls metric-calls}})))

;; =============================================================================
;; Propose Mutation
;; =============================================================================

(defcommand :gepa propose-mutation
  "Propose a mutation for a parent candidate.

   Uses reflective examples (failures with feedback) to guide
   the instruction improvement. The actual mutation happens
   via an ORC workflow in the todo processor."
  [{{:keys [optimization-id parent-candidate-id component-to-update
            reflective-examples]} :command
    :keys [event-store] :as ctx}]
  (let [opt-state (rm/get-optimization-summary ctx optimization-id)
        parent (rm/get-candidate ctx optimization-id parent-candidate-id)]
    (cond
      ;; Parent not found
      (nil? parent)
      {::anom/category ::anom/not-found
       ::anom/message "Parent candidate not found"}

      ;; Budget exhausted
      (rm/budget-exhausted? ctx optimization-id)
      {::anom/category ::anom/conflict
       ::anom/message "Metric budget exhausted"}

      ;; Valid mutation request - pass to todo processor
      :else
      {:command/result {:status :mutation-queued
                        :parent-candidate-id parent-candidate-id
                        :component-to-update component-to-update
                        :num-examples (count reflective-examples)}})))

;; =============================================================================
;; Record Mutation Result
;; =============================================================================

(defcommand :gepa record-mutation-result
  "Record the result of a mutation proposal.

   Called after the instruction proposer workflow completes.
   Creates the new mutated candidate."
  [{{:keys [optimization-id parent-candidate-id component-updated
            new-instruction mutation-reason]} :command
    :keys [event-store] :as ctx}]
  (let [parent (rm/get-candidate ctx optimization-id parent-candidate-id)
        new-candidate-id (random-uuid)
        new-instructions (assoc (:instructions parent) component-updated new-instruction)]
    {:command-result/events
     [(->event {:type :gepa/mutation-proposed
                :tags #{[:optimization optimization-id]
                        [:candidate new-candidate-id]
                        [:parent parent-candidate-id]}
                :body {:optimization-id optimization-id
                       :parent-candidate-id parent-candidate-id
                       :new-candidate-id new-candidate-id
                       :component-updated component-updated
                       :new-instruction new-instruction
                       :mutation-reason mutation-reason
                       :proposed-at (time/now)}})]
     :command/result {:new-candidate-id new-candidate-id
                      :new-instructions new-instructions}}))

;; =============================================================================
;; Perform Crossover
;; =============================================================================

(defcommand :gepa perform-crossover
  "Perform crossover between two parent candidates.

   Merges instructions from two Pareto-optimal parents.
   Uses the merge engine ORC workflow."
  [{{:keys [optimization-id parent1-id parent2-id]} :command
    :keys [event-store] :as ctx}]
  (let [parent1 (rm/get-candidate ctx optimization-id parent1-id)
        parent2 (rm/get-candidate ctx optimization-id parent2-id)]
    (cond
      (nil? parent1)
      {::anom/category ::anom/not-found
       ::anom/message "Parent 1 not found"}

      (nil? parent2)
      {::anom/category ::anom/not-found
       ::anom/message "Parent 2 not found"}

      :else
      {:command/result {:status :crossover-queued
                        :parent1-id parent1-id
                        :parent2-id parent2-id}})))

;; =============================================================================
;; Record Crossover Result
;; =============================================================================

(defcommand :gepa record-crossover-result
  "Record the result of a crossover operation."
  [{{:keys [optimization-id parent1-id parent2-id merged-instructions]} :command
    :keys [event-store] :as ctx}]
  (let [child-id (random-uuid)]
    {:command-result/events
     [(->event {:type :gepa/crossover-performed
                :tags #{[:optimization optimization-id]
                        [:candidate child-id]
                        [:parent parent1-id]
                        [:parent parent2-id]}
                :body {:optimization-id optimization-id
                       :parent1-id parent1-id
                       :parent2-id parent2-id
                       :child-candidate-id child-id
                       :merged-instructions merged-instructions
                       :performed-at (time/now)}})]
     :command/result {:child-candidate-id child-id}}))

;; =============================================================================
;; Complete Optimization
;; =============================================================================

(defcommand :gepa complete-optimization
  "Mark an optimization run as completed.

   Calculates final statistics and identifies the best candidate."
  [{{:keys [optimization-id]} :command
    :keys [event-store] :as ctx}]
  (let [opt-state (rm/get-optimization-summary ctx optimization-id)
        pop-state (rm/get-population-state ctx optimization-id)
        ;; Find best candidate
        evaluated-candidates (->> (vals (:candidates pop-state))
                                  (filter :aggregate-score))
        best-candidate (when (seq evaluated-candidates)
                         (apply max-key :aggregate-score evaluated-candidates))
        ;; Calculate improvement
        first-candidate (first (sort-by :created-at (vals (:candidates pop-state))))
        initial-score (or (:aggregate-score first-candidate) 0.0)
        final-score (or (:aggregate-score best-candidate) 0.0)
        improvement (- final-score initial-score)]
    (cond
      (= :completed (:status opt-state))
      {::anom/category ::anom/conflict
       ::anom/message "Optimization already completed"}

      (nil? best-candidate)
      {::anom/category ::anom/incorrect
       ::anom/message "No candidates evaluated"}

      :else
      {:command-result/events
       [(->event {:type :gepa/optimization-completed
                  ;; Include sheet tag for cross-run inheritance queries
                  :tags #{[:optimization optimization-id]
                          [:sheet (:sheet-id opt-state)]}
                  :body {:optimization-id optimization-id
                         :sheet-id (:sheet-id opt-state)
                         :best-candidate-id (:candidate-id best-candidate)
                         :final-score final-score
                         :total-metric-calls (:total-metric-calls pop-state)
                         :num-candidates (count (:candidates pop-state))
                         :improvement improvement
                         :completed-at (time/now)}})]
       :command/result {:best-candidate-id (:candidate-id best-candidate)
                        :final-score final-score
                        :improvement improvement}})))

;; =============================================================================
;; Fail Optimization
;; =============================================================================

(defcommand :gepa fail-optimization
  "Mark an optimization run as failed."
  [{{:keys [optimization-id error-message]} :command
    :keys [event-store] :as ctx}]
  (let [opt-state (rm/get-optimization-summary ctx optimization-id)]
    (cond
      (= :completed (:status opt-state))
      {::anom/category ::anom/conflict
       ::anom/message "Cannot fail a completed optimization"}

      (= :failed (:status opt-state))
      {::anom/category ::anom/conflict
       ::anom/message "Optimization already failed"}

      :else
      {:command-result/events
       [(->event {:type :gepa/optimization-failed
                  :tags #{[:optimization optimization-id]}
                  :body {:optimization-id optimization-id
                         :error-message error-message
                         :failed-at (time/now)}})]
       :command/result {:status :failed}})))

;; =============================================================================
;; Command Dispatch Helpers (used by interface delegation)
;; =============================================================================

(defn process-command!
  "Process a command and extract the result.
   The command processor stores events automatically.
   Returns :command/result on success, or anomaly on failure."
  [context]
  (let [result (command-processor/process-command context)]
    (if (::anom/category result)
      result
      (:command/result result))))

(defn create-candidate!
  "Create a candidate via command dispatch."
  [context optimization-id instructions parent-ids mutation-reason]
  (process-command!
    (assoc context
           :command {:command/id (random-uuid)
                     :command/timestamp (time/now)
                     :command/name :gepa/create-candidate
                     :optimization-id optimization-id
                     :instructions instructions
                     :parent-ids parent-ids
                     :mutation-reason mutation-reason})))

(defn record-evaluation!
  "Record evaluation results via command dispatch."
  [context optimization-id candidate-id scores trace-ids metric-calls]
  (process-command!
    (assoc context
           :command {:command/id (random-uuid)
                     :command/timestamp (time/now)
                     :command/name :gepa/record-evaluation-result
                     :optimization-id optimization-id
                     :candidate-id candidate-id
                     :scores scores
                     :trace-ids trace-ids
                     :metric-calls metric-calls})))

(defn complete-optimization!
  "Complete an optimization via command dispatch."
  [context optimization-id]
  (process-command!
    (assoc context
           :command {:command/id (random-uuid)
                     :command/timestamp (time/now)
                     :command/name :gepa/complete-optimization
                     :optimization-id optimization-id})))
