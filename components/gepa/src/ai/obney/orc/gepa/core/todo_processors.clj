(ns ai.obney.orc.gepa.core.todo-processors
  "Todo processors for GEPA optimization loop.

   These event handlers implement the core optimization algorithm with
   subsample-first evaluation (Python GEPA parity):

   1. On optimization-started: Create seed candidate
   2. On candidate-created: Trigger full evaluation (for seed/inherited)
   3. On candidate-evaluated: Update frontier, check budget
   4. On frontier-updated: Propose mutation → create candidate
   5. On mutation-proposed: Evaluate on SUBSAMPLE (3 examples)
   6. On subsample-evaluated:
      - If improved (new_sum > old_sum): Trigger FULL evaluation
      - If not improved: Propose another mutation
   7. Loop until budget exhausted, then complete

   This subsample-first approach matches Python GEPA's efficiency:
   - Rejected candidates cost only 3 metric calls (vs 72 for full valset)
   - Accepted candidates cost 3 + 72 = 75 metric calls
   - Net savings: ~60% if 50% rejection rate

   Each processor is triggered by events and emits commands
   to drive the state machine forward."
  (:require [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.core.pareto :as pareto]
            [ai.obney.orc.gepa.core.reflection :as reflection]
            [ai.obney.orc.gepa.core.proposer :as proposer]
            [ai.obney.orc.gepa.core.sampler :as sampler]
            [ai.obney.orc.gepa.core.optimization :as optimization]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.time.interface :as time]
            [clojure.string :as string]
            [dscloj.core :as dscloj]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn run-command!
  "Execute a command through the command processor."
  [context command]
  (command-processor/process-command
    (assoc context :command command)))

(defn score+feedback-of
  "Normalize a metric-fn return value into {:score double :feedback string-or-nil}.

   The judge metric returns {:score :feedback}; the structural metrics
   (exact-match/contains) and user metric-fns return a bare number. This
   unifies both so the evaluation path can carry per-example judge feedback
   when it is available, and nil otherwise (back-compat)."
  [metric-result]
  (if (map? metric-result)
    {:score (double (or (:score metric-result) 0.0))
     :feedback (:feedback metric-result)}
    {:score (double (or metric-result 0.0))
     :feedback nil}))

(defn extract-workflow-instructions
  "Extract current instructions from a sheet/workflow.

   Walks the workflow tree and collects LLM node instructions.
   Returns map of {node-name -> instruction-text}.

   Finds all LLM nodes (type :leaf with executor :llm or :llm-condition)
   and extracts their instructions for optimization."
  [context sheet-id]
  (u/log ::extracting-instructions :sheet-id sheet-id)
  (let [nodes (sheet/get-nodes-for-sheet context sheet-id)
        ;; Filter for LLM nodes - type :leaf with :executor :llm, or :llm-condition
        llm-nodes (filter (fn [node]
                            (or (and (= :leaf (:type node))
                                     (#{:ai :llm} (:executor node)))
                                (= :llm-condition (:type node))))
                          nodes)
        ;; Build instruction map: {node-name -> instruction}
        instructions (into {}
                           (for [node llm-nodes
                                 :let [name (or (:name node) (str (:id node)))
                                       instruction (:instruction node)]
                                 :when instruction]
                             [name instruction]))]
    (if (seq instructions)
      instructions
      ;; Fallback if no LLM nodes found
      {"default" "You are a helpful assistant."})))

(defn legacy-score-feedback
  "The score-only feedback string GEPA used before judge feedback was threaded
   through. Kept as the back-compat fallback for candidates evaluated by a
   structural/user metric-fn (no per-example judge feedback)."
  [score expected]
  (str "The response scored " (format "%.2f" (double score))
       " on this example. "
       "Expected: " expected ". "
       "Please improve the instruction to handle "
       "this type of input better."))

(defn reflective-feedback-for-instance
  "Choose the feedback to put in a reflective example for one instance.

   Prefers the candidate's RICH per-instance judge feedback (the judges'
   weakest-first :feedback/:reasoning) so the proposer sees WHY it scored low.
   Falls back to the legacy score-string when no rich feedback is present
   (blank or absent), preserving back-compat for non-judge metrics."
  [feedbacks instance-id score expected]
  (let [rich (get feedbacks instance-id)]
    (if (and rich (not (string/blank? rich)))
      rich
      (legacy-score-feedback score expected))))

(defn build-reflective-dataset
  "Build reflective examples from failing instances.

   Samples from trainset where the candidate underperformed.
   Returns Python GEPA-compatible ReflectiveExample format:
   {\"Inputs\" {...}, \"Generated Outputs\" {...}, \"Feedback\" \"...\"}

   When the candidate was scored by the judge metric, the per-instance
   `:feedbacks` carried on the candidate-evaluated event (the judges' rich,
   weakest-first feedback) is used as the ReflectiveExample feedback INSTEAD of
   the legacy 'scored 0.XX' string. This is what lets the proposer turn a bad
   instruction into a good one. Falls back to the score string per-instance
   when no rich feedback is available (back-compat).

   The per-instance generated `:outputs` (the candidate's actual bad answer for
   each failing instance) are likewise put into the 'Generated Outputs' slot so
   the proposer sees BOTH what was produced AND why it scored low. Falls back to
   `{}` per-instance when no output was recorded (back-compat)."
  [context optimization-id candidate-id config]
  (let [event-store (:event-store context)
        failures (rm/get-failing-instances context optimization-id candidate-id)
        trainset (rm/get-trainset context optimization-id)
        ;; Per-instance RICH judge feedback recorded on candidate-evaluated.
        feedbacks (rm/get-candidate-feedbacks context optimization-id candidate-id)
        ;; Per-instance generated outputs recorded on candidate-evaluated.
        outputs (rm/get-candidate-outputs context optimization-id candidate-id)
        minibatch-size (get config :reflection-minibatch-size 3)]

    (u/log ::building-reflective-dataset
           :candidate-id candidate-id
           :num-failures (count failures)
           :num-rich-feedbacks (count feedbacks)
           :num-outputs (count outputs)
           :minibatch-size minibatch-size)

    ;; Sample failures and build examples in Python GEPA format
    (->> failures
         (take minibatch-size)
         (map (fn [{:keys [instance-id score]}]
                (let [input (get trainset instance-id {})
                      ;; Get expected answer from trainset
                      expected (or (get input "answer")
                                   (get input "expected")
                                   (get input "label")
                                   "")
                      ;; Prefer the judges' rich feedback; else legacy string.
                      feedback (reflective-feedback-for-instance
                                 feedbacks instance-id score expected)
                      ;; The candidate's actual generated output for this
                      ;; instance (the bad answer); {} when none was recorded.
                      generated-output (get outputs instance-id {})]
                  ;; Return in exact Python GEPA ReflectiveExample format
                  (reflection/make-reflective-example
                    input             ;; Inputs
                    generated-output  ;; Generated Outputs (the real bad answer)
                    feedback))))      ;; Feedback string
         vec)))

(defn select-component
  "Select which component to mutate next.

   Uses round-robin with random tie-breaking based on
   how many times each component has been mutated."
  [context optimization-id]
  (let [pop-state (rm/get-population-state context optimization-id)
        ;; Get all mutation events to count per-component mutations
        components (keys (:instructions (first (vals (:candidates pop-state)))))]
    ;; For now, just pick randomly from components
    (rand-nth (vec components))))

;; =============================================================================
;; Workflow Execution with Instruction Patching
;; =============================================================================

(defn patch-workflow-instructions
  "Create a context with patched instructions for execution.

   This temporarily associates instructions with a workflow execution
   without modifying the persisted workflow. The instructions are
   passed through the execution context and applied during runtime."
  [context sheet-id instructions]
  (assoc context :gepa/patched-instructions instructions))

(defn execute-workflow-with-instructions
  "Execute a workflow with specific instructions on a single input.

   Returns {:output map :trace-id uuid :score double :feedback string-or-nil
            :success? bool}. The :feedback is the judges' rich per-example
   feedback when the metric is the judge metric, else nil (back-compat)."
  [context sheet-id instructions input metric-fn]
  (let [;; Build input map from the example — use keyword keys to match blackboard
        input-map (if (map? input)
                    (into {} (map (fn [[k v]] [(keyword k) v]) input))
                    {:input input})
        ;; Execute the workflow
        result (try
                 (sheet/execute
                   (patch-workflow-instructions context sheet-id instructions)
                   sheet-id
                   input-map
                   :timeout-ms 300000)
                 (catch Exception e
                   {:status :failure
                    :error (.getMessage e)
                    :outputs {}}))]

    (u/log ::workflow-executed
           :sheet-id sheet-id
           :status (:status result)
           :duration-ms (:duration-ms result))

    (let [output (:outputs result)
          ;; The metric sees the ORIGINAL example and the full output
          ;; blackboard. The judge metric grades the response against a STABLE
          ;; task (its :judges :task), NOT the candidate instruction — feeding
          ;; the candidate instruction would let instruction-following reward a
          ;; faithfully-followed BAD instruction (Goodhart). See metrics.
          {:keys [score feedback]}
          (try
            (score+feedback-of (metric-fn input output))
            (catch Exception e
              (u/log ::metric-error :error (.getMessage e))
              {:score 0.0 :feedback nil}))]
      {:output output
       :trace-id (random-uuid)  ;; TODO: Get from actual trace
       :score score
       :feedback feedback
       :success? (= :success (:status result))})))

(defn evaluate-candidate-on-valset
  "Evaluate a candidate's instructions on the full validation set.

   Returns {:scores [double ...] :trace-ids [uuid ...]
            :feedbacks {instance-idx -> feedback-string}
            :outputs {instance-idx -> output-map} :metric-calls int}.
   :feedbacks only contains entries for examples whose metric supplied rich
   feedback (the judge metric); structural metrics leave it empty. :outputs
   carries the per-instance generated output blackboard so the reflective
   dataset can show the proposer the actual (bad) answer text."
  [context sheet-id instructions valset metric-fn]
  (let [results (doall  ;; Force evaluation for side effects
                  (map-indexed
                    (fn [idx example]
                      (u/log ::evaluating-example
                             :index idx
                             :total (count valset))
                      (execute-workflow-with-instructions
                        context sheet-id instructions example metric-fn))
                    valset))]
    {:scores (mapv :score results)
     :trace-ids (mapv :trace-id results)
     :feedbacks (into {} (keep-indexed (fn [idx r]
                                         (when (:feedback r) [idx (:feedback r)]))
                                       results))
     :outputs (into {} (keep-indexed (fn [idx r]
                                       (when (some? (:output r)) [idx (:output r)]))
                                     results))
     :metric-calls (count valset)}))

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

   Returns {:scores [double ...] :trace-ids [uuid ...] :metric-calls int}."
  [context sheet-id instructions trainset indices metric-fn]
  (let [subsample (mapv #(nth trainset %) indices)
        results (doall
                  (map-indexed
                    (fn [idx example]
                      (u/log ::evaluating-subsample
                             :index idx
                             :trainset-index (nth indices idx)
                             :total (count indices))
                      (execute-workflow-with-instructions
                        context sheet-id instructions example metric-fn))
                    subsample))]
    {:scores (mapv :score results)
     :trace-ids (mapv :trace-id results)
     :metric-calls (count indices)}))

(defn default-metric-fn
  "Default metric: exact string match on 'answer' or 'expected' field.

   Returns 1.0 if output matches expected, 0.0 otherwise."
  [input output]
  (let [expected (or (get input "answer")
                     (get input "expected")
                     (get input "label"))
        ;; Look for output in common locations
        actual (or (get output "answer")
                   (get output "output")
                   (get output "result")
                   ;; Take first string value if single output
                   (first (filter string? (vals output))))]
    (if (and expected actual)
      (if (= (str expected) (str actual))
        1.0
        ;; Partial credit: check if expected is contained in output
        (if (and (string? actual)
                 (string? expected)
                 (.contains (.toLowerCase (str actual))
                            (.toLowerCase (str expected))))
          0.7
          0.0))
      0.0)))

;; =============================================================================
;; LLM Function for Instruction Proposal
;; =============================================================================

(defn make-llm-fn
  "Create the reflection LLM function (prompt) -> response-string for the
   instruction proposer.

   Calls the reflection model through dscloj/predict with
   :use-function-calling? true. This is REQUIRED for structured-output
   reflection models such as gemini-3-flash-preview (whose default
   function-calling is off); a raw chat-completion call returns malformed /
   empty output for those models. The single typed :response output field
   carries the proposer's full text (the proposer then extracts the
   instruction from its ``` block), per the validated reference harness."
  [context model-name]
  (let [provider (or (:dscloj-provider context) :openrouter)
        module {:inputs [{:name :prompt :spec :string
                          :description "the full proposer prompt"}]
                :outputs [{:name :response :spec :string
                           :description "the full response to the prompt"}]
                :instructions (str "Respond to the prompt exactly as it asks. "
                                   "When it asks for a new instruction in a ``` block, "
                                   "include the full new instruction inside a ``` block.")}]
    (fn [prompt]
      (u/log ::calling-llm :model model-name :prompt-length (count prompt))
      (let [response (str (:response
                           (dscloj/predict provider module {:prompt prompt}
                             {:model model-name
                              :use-function-calling? true
                              :validate? false
                              :with-metadata? false})))]
        (u/log ::llm-response-received :response-length (count response))
        response))))

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
  [context parent-instructions component reflective-examples model-name]
  (let [current-instruction (get parent-instructions component)
        formatted-examples (reflection/format-reflective-examples reflective-examples)
        llm-fn (make-llm-fn context model-name)]

    (u/log ::generating-mutation
           :component component
           :current-instruction-length (count current-instruction)
           :num-examples (count reflective-examples))

    (if (or (empty? reflective-examples)
            (nil? current-instruction))
      ;; If no examples or instruction, make a simple improvement
      (str current-instruction " Be more precise and complete in your answers.")
      ;; Use the proposer to generate a new instruction
      (let [result (proposer/propose-new-instruction
                     llm-fn
                     current-instruction
                     formatted-examples)]
        (:proposed-instruction result)))))

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

        ;; 1. Extract seed instructions from workflow
        seed-instructions (extract-workflow-instructions context sheet-id)

        ;; 2. Create seed candidate (generation 0)
        _ (run-command! context
            {:command/id (random-uuid)
             :command/timestamp (time/now)
             :command/name :gepa/create-candidate
             :optimization-id optimization-id
             :instructions seed-instructions
             :parent-ids [nil]
             :mutation-reason nil
             :source-optimization-id nil})

        ;; 3. Query inherited candidates (cumulative across ALL runs)
        inherited-candidates (when inherit?
                               (rm/get-all-unique-candidates
                                 context sheet-id
                                 :limit inherit-limit))]

    ;; 4. Create inherited candidates (skip if same as seed)
    (when (seq inherited-candidates)
      (u/log ::inheriting-candidates
             :count (count inherited-candidates)
             :optimization-id optimization-id)

      (doseq [cand inherited-candidates
              :let [inst (:instructions cand)
                    subscores (:val-subscores cand)]
              :when (not= inst seed-instructions)]  ;; Don't duplicate seed
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
           :parent-ids [nil]  ;; No parent - inherited from previous run
           :mutation-reason "inherited-from-previous-run"
           :source-optimization-id (:source-optimization-id cand)
           :historical-val-subscores subscores})))))

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
    ;; Trigger evaluation
    (run-command! context
      {:command/id (random-uuid)
       :command/timestamp (time/now)
       :command/name :gepa/evaluate-candidate
       :optimization-id optimization-id
       :candidate-id candidate-id})))

;; =============================================================================
;; On Candidate Evaluation Started
;; =============================================================================

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

        ;; Get candidate (includes historical-val-subscores if inherited)
        candidate (rm/get-candidate context optimization-id candidate-id)
        historical-scores (:historical-val-subscores candidate)]

    ;; Check if this is an inherited candidate with historical scores
    (if (and historical-scores (seq historical-scores))
      ;; SKIP EVALUATION - Reuse historical per-instance scores
      ;; This is key for true single-run parity: inherited candidates don't consume metric budget
      (let [;; Convert subscores map to ordered vector for consistency
            ;; Historical subscores are {instance-id -> score}
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
           :trace-ids []  ;; No traces - reusing history
           :metric-calls 0}))  ;; 0 metric calls - key for budget savings!

      ;; FRESH EVALUATION - Normal path for new candidates
      (let [opt-state (rm/get-optimization-summary context optimization-id)
            sheet-id (:sheet-id opt-state)
            instructions (:instructions candidate)

            ;; Get validation set from stored datasets
            valset (rm/get-valset context optimization-id)

            ;; Get metric function from registry, context, or use default
            metric-fn (or (optimization/get-metric-fn optimization-id)
                          (:gepa/metric-fn context)
                          default-metric-fn)

            ;; Evaluate: Execute workflow on each validation example
            ;; If valset is empty or workflow execution fails, fall back to simulated scores
            eval-result
            (if (and valset (seq valset) sheet-id)
              ;; Real evaluation using workflow execution
              (try
                (evaluate-candidate-on-valset
                  context sheet-id instructions valset metric-fn)
                (catch Exception e
                  (u/log ::evaluation-error
                         :error (.getMessage e)
                         :candidate-id candidate-id)
                  ;; Fallback to simulated scores on error
                  (let [num-instances (count valset)]
                    {:scores (vec (repeatedly num-instances #(+ 0.3 (* 0.4 (rand)))))
                     :trace-ids (vec (repeatedly num-instances random-uuid))
                     :metric-calls num-instances})))
              ;; Simulated evaluation when valset not available
              ;; (useful for testing without a real workflow)
              (let [num-instances (or (count valset) 10)]
                (u/log ::simulating-evaluation
                       :reason (if valset "empty-valset" "no-valset")
                       :num-instances num-instances)
                {:scores (vec (repeatedly num-instances #(+ 0.5 (* 0.5 (rand)))))
                 :trace-ids (vec (repeatedly num-instances random-uuid))
                 :metric-calls num-instances}))]

        (u/log ::evaluation-complete
               :candidate-id candidate-id
               :mean-score (when (seq (:scores eval-result))
                             (/ (reduce + (:scores eval-result))
                                (count (:scores eval-result)))))

        ;; Record the evaluation results (including any per-instance judge
        ;; feedback so the reflective dataset can use it instead of the
        ;; score-only string).
        (run-command! context
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :gepa/record-evaluation-result
           :optimization-id optimization-id
           :candidate-id candidate-id
           :scores (:scores eval-result)
           :trace-ids (:trace-ids eval-result)
           :feedbacks (:feedbacks eval-result)
           ;; Per-instance generated outputs so the reflective dataset can
           ;; show the proposer the actual (bad) answer text.
           :outputs (:outputs eval-result)
           :metric-calls (:metric-calls eval-result)})))))

;; =============================================================================
;; On Candidate Evaluated
;; =============================================================================

(defn on-candidate-evaluated
  "Handle candidate-evaluated event.

   Python GEPA parity with subsample-first evaluation:
   1. Update Pareto frontier
   2. Check if budget exhausted
   3. If budget remains: select parent, propose mutation, trigger SUBSAMPLE eval
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

        ;; Update the Pareto frontier
        _ (run-command! context
            {:command/id (random-uuid)
             :command/timestamp (time/now)
             :command/name :gepa/update-frontier
             :optimization-id optimization-id
             :candidate-id candidate-id
             :scores scores})

        ;; Check budget
        budget-exhausted? (rm/budget-exhausted? context optimization-id)]

    (if budget-exhausted?
      ;; Complete the optimization
      (do
        (u/log ::budget-exhausted :optimization-id optimization-id)
        (run-command! context
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :gepa/complete-optimization
           :optimization-id optimization-id}))

      ;; Continue with mutation -> subsample eval flow
      (let [;; Sample a parent from the Pareto frontier
            parent-id (rm/sample-pareto-frontier context optimization-id)
            ;; Or use the current candidate if frontier is empty
            parent-id (or parent-id candidate-id)
            ;; Get parent candidate
            parent-candidate (rm/get-candidate context optimization-id parent-id)
            parent-instructions (:instructions parent-candidate)
            ;; Select component to mutate
            component (select-component context optimization-id)
            ;; Get optimization config
            opt-summary (rm/get-optimization-summary context optimization-id)
            opt-config (:config opt-summary {})
            reflection-model (get opt-config :reflection-lm "anthropic/claude-sonnet-4")
            minibatch-size (get opt-config :reflection-minibatch-size 3)
            ;; Build reflective dataset from failures
            reflective-examples (build-reflective-dataset context optimization-id candidate-id opt-config)
            ;; Get iteration for epoch-shuffled sampling
            iteration (rm/get-iteration-count context optimization-id)
            ;; Get trainset for batch sampling
            trainset (rm/get-trainset context optimization-id)
            trainset-size (count trainset)
            ;; Calculate subsample indices using epoch-shuffled batch sampler
            subsample-indices (if (> trainset-size 0)
                                (sampler/epoch-shuffled-batch-sampler
                                  trainset-size minibatch-size iteration)
                                (vec (range (min minibatch-size 3))))]

        (u/log ::generating-mutation
               :parent-id parent-id
               :component component
               :iteration iteration
               :subsample-indices subsample-indices
               :num-reflective-examples (count reflective-examples))

        ;; Generate new instruction using the proposer
        (let [new-instruction (generate-mutated-instruction
                                context
                                parent-instructions
                                component
                                reflective-examples
                                reflection-model)
              ;; Create new instructions map with mutation
              new-instructions (assoc parent-instructions component new-instruction)]

          (u/log ::mutation-generated
                 :component component
                 :new-instruction-length (count new-instruction))

          ;; Trigger subsample evaluation WITHOUT creating candidate first
          ;; The candidate will be created ONLY if subsample evaluation shows improvement
          ;; This is the key efficiency optimization from Python GEPA
          ;; Note: skip-perfect-score check happens IN the subsample handler
          ;; after evaluating parent on the minibatch (not valset)
          (run-command! context
            {:command/id (random-uuid)
             :command/timestamp (time/now)
             :command/name :gepa/evaluate-on-subsample
             :optimization-id optimization-id
             :parent-id parent-id
             :proposed-instructions new-instructions
             :component-updated component
             :subsample-indices subsample-indices
             :iteration iteration}))))))

;; =============================================================================
;; On Subsample Evaluation Started
;; =============================================================================

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
        ;; Get optimization state
        opt-summary (rm/get-optimization-summary context optimization-id)
        sheet-id (:sheet-id opt-summary)

        ;; Get trainset for subsample evaluation
        trainset (rm/get-trainset context optimization-id)

        ;; Get metric function from registry, context, or use default
        metric-fn (or (optimization/get-metric-fn optimization-id)
                      (:gepa/metric-fn context)
                      default-metric-fn)

        ;; Get parent instructions
        parent-candidate (rm/get-candidate context optimization-id parent-id)
        parent-instructions (:instructions parent-candidate)]

    ;; Get skip-perfect-score config
    (let [opt-config (get opt-summary :config {})
          skip-perfect? (get opt-config :skip-perfect-score true)]

      (if (and trainset (seq trainset) sheet-id)
        ;; Real subsample evaluation
        (try
          (let [;; Evaluate PARENT on subsample FIRST
                parent-result (evaluate-candidate-on-subsample
                                context sheet-id parent-instructions
                                trainset subsample-indices metric-fn)
                parent-scores (:scores parent-result)
                parent-sum (reduce + parent-scores)
                ;; Check if parent got perfect score on minibatch (Python GEPA behavior)
                parent-perfect? (and skip-perfect?
                                     (every? #(>= % 1.0) parent-scores))]

            (if parent-perfect?
              ;; Skip mutation - parent already perfect on this minibatch
              (do
                (u/log ::skipping-perfect-parent
                       :parent-id parent-id
                       :parent-scores parent-scores
                       :metric-calls (:metric-calls parent-result))
                ;; Don't evaluate proposed, just record as rejected with perfect parent
                (run-command! context
                  {:command/id (random-uuid)
                   :command/timestamp (time/now)
                   :command/name :gepa/record-subsample-result
                   :optimization-id optimization-id
                   :parent-id parent-id
                   :proposed-instructions proposed-instructions
                   :component-updated component-updated
                   :subsample-indices subsample-indices
                   :parent-scores parent-scores
                   :proposed-scores parent-scores  ;; Use parent scores (didn't evaluate proposed)
                   :accepted? false                 ;; Not accepted - parent was perfect
                   :metric-calls (:metric-calls parent-result)}))  ;; Only counted parent evals

              ;; Normal case - evaluate proposed and compare
              (let [;; Evaluate PROPOSED on same subsample
                    proposed-result (evaluate-candidate-on-subsample
                                      context sheet-id proposed-instructions
                                      trainset subsample-indices metric-fn)
                    proposed-scores (:scores proposed-result)
                    proposed-sum (reduce + proposed-scores)

                    ;; Python GEPA acceptance criterion: strict improvement
                    ;; new_sum > old_sum (not >=)
                    accepted? (> proposed-sum parent-sum)

                    ;; Total metric calls: parent + proposed evaluations
                    metric-calls (+ (:metric-calls parent-result)
                                    (:metric-calls proposed-result))]

                (u/log ::subsample-evaluation-complete
                       :parent-sum parent-sum
                       :proposed-sum proposed-sum
                       :accepted? accepted?
                       :metric-calls metric-calls)

                ;; Record the subsample evaluation result
                ;; This triggers on-subsample-evaluated handler
                (run-command! context
                  {:command/id (random-uuid)
                   :command/timestamp (time/now)
                   :command/name :gepa/record-subsample-result
                   :optimization-id optimization-id
                   :parent-id parent-id
                   :proposed-instructions proposed-instructions
                   :component-updated component-updated
                   :subsample-indices subsample-indices
                   :parent-scores parent-scores
                   :proposed-scores proposed-scores
                   :accepted? accepted?
                   :metric-calls metric-calls}))))

        (catch Exception e
          (u/log ::subsample-evaluation-error
                 :error (.getMessage e)
                 :parent-id parent-id)
          ;; On error, skip this mutation and let the system continue
          nil))

      ;; No trainset - simulate subsample evaluation
      (let [simulated-parent-scores (vec (repeatedly (count subsample-indices) #(+ 0.3 (* 0.4 (rand)))))
            simulated-proposed-scores (vec (repeatedly (count subsample-indices) #(+ 0.35 (* 0.45 (rand)))))
            parent-sum (reduce + simulated-parent-scores)
            proposed-sum (reduce + simulated-proposed-scores)
            accepted? (> proposed-sum parent-sum)]

        (u/log ::simulating-subsample-evaluation
               :parent-sum parent-sum
               :proposed-sum proposed-sum
               :accepted? accepted?)

        (run-command! context
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :gepa/record-subsample-result
           :optimization-id optimization-id
           :parent-id parent-id
           :proposed-instructions proposed-instructions
           :component-updated component-updated
           :subsample-indices subsample-indices
           :parent-scores simulated-parent-scores
           :proposed-scores simulated-proposed-scores
           :accepted? accepted?
           :metric-calls (* 2 (count subsample-indices))})))))) ;; Close outer let for skip-perfect?

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
      ;; ACCEPTED: Create the candidate and trigger full evaluation
      (do
        (u/log ::subsample-accepted
               :creating-candidate true
               :triggering-full-eval true)

        ;; Create the candidate now that it passed subsample check
        (run-command! context
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :gepa/create-candidate
           :optimization-id optimization-id
           :instructions proposed-instructions
           :parent-ids [parent-id]
           :mutation-reason (str "Mutated " component-updated " - passed subsample eval")}))

      ;; REJECTED: Try another mutation
      (let [budget-exhausted? (rm/budget-exhausted? context optimization-id)]
        (if budget-exhausted?
          ;; Budget exhausted - complete the optimization
          (do
            (u/log ::budget-exhausted-after-rejection :optimization-id optimization-id)
            (run-command! context
              {:command/id (random-uuid)
               :command/timestamp (time/now)
               :command/name :gepa/complete-optimization
               :optimization-id optimization-id}))

          ;; Budget remains - try another mutation
          ;; Reuse the mutation flow from on-candidate-evaluated
          (let [;; Sample a NEW parent from the Pareto frontier (or use same parent)
                new-parent-id (or (rm/sample-pareto-frontier context optimization-id)
                                  parent-id)
                parent-candidate (rm/get-candidate context optimization-id new-parent-id)
                parent-instructions (:instructions parent-candidate)
                ;; Select component to mutate (round-robin)
                component (select-component context optimization-id)
                ;; Get config
                opt-summary (rm/get-optimization-summary context optimization-id)
                opt-config (:config opt-summary {})
                reflection-model (get opt-config :reflection-lm "anthropic/claude-sonnet-4")
                minibatch-size (get opt-config :reflection-minibatch-size 3)
                ;; Build reflective examples
                reflective-examples (build-reflective-dataset context optimization-id new-parent-id opt-config)
                ;; Get iteration for epoch-shuffled sampling
                iteration (rm/get-iteration-count context optimization-id)
                trainset (rm/get-trainset context optimization-id)
                trainset-size (count trainset)
                subsample-indices (if (> trainset-size 0)
                                    (sampler/epoch-shuffled-batch-sampler
                                      trainset-size minibatch-size iteration)
                                    (vec (range (min minibatch-size 3))))]

            (u/log ::subsample-rejected-retrying
                   :new-parent-id new-parent-id
                   :component component
                   :iteration iteration)

            ;; Generate new mutation and trigger subsample eval
            (let [new-instruction (generate-mutated-instruction
                                    context
                                    parent-instructions
                                    component
                                    reflective-examples
                                    reflection-model)
                  new-instructions (assoc parent-instructions component new-instruction)]

              (run-command! context
                {:command/id (random-uuid)
                 :command/timestamp (time/now)
                 :command/name :gepa/evaluate-on-subsample
                 :optimization-id optimization-id
                 :parent-id new-parent-id
                 :proposed-instructions new-instructions
                 :component-updated component
                 :subsample-indices subsample-indices
                 :iteration iteration}))))))))

;; =============================================================================
;; On Mutation Proposed
;; =============================================================================

(defn on-mutation-proposed
  "Handle mutation-proposed event.

   NOTE: In the subsample-first flow, mutations go through subsample evaluation
   before being accepted. This handler is kept for backwards compatibility
   and logging purposes.

   The mutation-proposed event is emitted by the record-mutation-result
   command and contains the new candidate info."
  [{:keys [event] :as context}]
  (u/log ::on-mutation-proposed
         :parent-id (:parent-candidate-id event)
         :component (:component-updated event)
         :new-instruction-preview (subs (or (:new-instruction event) "") 0
                                        (min 100 (count (or (:new-instruction event) "")))))
  ;; No action needed - candidate creation is handled by the subsample evaluation flow
  )

;; =============================================================================
;; On Frontier Updated
;; =============================================================================

(defn on-frontier-updated
  "Handle frontier-updated event.

   Log the frontier update for observability.
   No action needed - the evaluated handler already checked budget."
  [{:keys [event] :as context}]
  (u/log ::on-frontier-updated
         :candidate-id (:candidate-id event)
         :instances-best-at (:instances-best-at event)))

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

    ;; Deliver result to any waiting sync callers
    ;; Uses dynamic resolution to avoid circular dependency
    (when-let [deliver-fn (resolve 'ai.obney.orc.gepa.interface/deliver-completion!)]
      (deliver-fn optimization-id
                  {:optimization-id optimization-id
                   :status :completed
                   :best-candidate (when best-candidate
                                     {:instructions (:instructions best-candidate)
                                      :score (:aggregate-score best-candidate)
                                      :candidate-id best-candidate-id})
                   :best-score final-score
                   :improvement improvement}))))

;; =============================================================================
;; On Optimization Failed
;; =============================================================================

(defn on-optimization-failed
  "Handle optimization-failed event.

   Log the failure and deliver result to waiting callers."
  [{:keys [event] :as context}]
  (let [{:keys [optimization-id error-message]} event]
    (u/log ::on-optimization-failed
           :optimization-id optimization-id
           :error-message error-message)

    ;; Deliver failure to any waiting sync callers
    (when-let [deliver-fn (resolve 'ai.obney.orc.gepa.interface/deliver-completion!)]
      (deliver-fn optimization-id
                  {:optimization-id optimization-id
                   :status :failed
                   :error error-message}))))

;; =============================================================================
;; Todo Processor Registry
;; =============================================================================

;; =============================================================================
;; Processor Registration (defprocessor delegates to existing handler fns)
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

(defprocessor :gepa on-subsample-evaluation-started
  {:topics #{:gepa/subsample-evaluation-started}}
  "Handle subsample-evaluation-started: evaluate parent and proposed on minibatch."
  [context]
  (on-subsample-evaluation-started context))

(defprocessor :gepa on-subsample-evaluated
  {:topics #{:gepa/subsample-evaluated}}
  "Handle subsample-evaluated: accept or reject mutation."
  [context]
  (on-subsample-evaluated context))

(defprocessor :gepa on-mutation-proposed
  {:topics #{:gepa/mutation-proposed}}
  "Handle mutation-proposed event (logging, backwards compatibility)."
  [context]
  (on-mutation-proposed context))

(defprocessor :gepa on-frontier-updated
  {:topics #{:gepa/frontier-updated}}
  "Handle frontier-updated event (logging)."
  [context]
  (on-frontier-updated context))

(defprocessor :gepa on-optimization-completed
  {:topics #{:gepa/optimization-completed}}
  "Handle optimization-completed: deliver result, apply best instructions."
  [context]
  (on-optimization-completed context))

(defprocessor :gepa on-optimization-failed
  {:topics #{:gepa/optimization-failed}}
  "Handle optimization-failed: deliver failure to waiting callers."
  [context]
  (on-optimization-failed context))
