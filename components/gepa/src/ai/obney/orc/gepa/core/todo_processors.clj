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
  (:require [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.core.pareto :as pareto]
            [ai.obney.orc.gepa.core.reflection :as reflection]
            [ai.obney.orc.gepa.core.proposer :as proposer]
            [ai.obney.orc.gepa.core.sampler :as sampler]
            [ai.obney.orc.gepa.core.optimization :as optimization]
            [ai.obney.orc.gepa.core.task-ledger :as task-ledger]
            [ai.obney.orc.gepa.core.task-execution :as task-execution]
            [ai.obney.orc.gepa.core.recovery :as recovery]
            [ai.obney.orc.gepa.core.metric-runtime :as metric-runtime]
            [ai.obney.orc.gepa.core.metrics :as metrics]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.time.interface :as time]
            [clojure.string :as string]
            [ai.obney.orc.llm.interface :as llm]
            [com.brunobonacci.mulog :as u]))

(defn- sha256
  [s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (str s) java.nio.charset.StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- normalize-usage
  "Token usage as recorded in durable evidence. A cost the provider reported is
   kept (as a double); an absent cost is never invented."
  [usage]
  (let [cost (:cost usage)]
    (cond-> {:prompt-tokens (int (or (:prompt-tokens usage) (:prompt_tokens usage) 0))
             :completion-tokens (int (or (:completion-tokens usage) (:completion_tokens usage) 0))
             :total-tokens (int (or (:total-tokens usage) (:total_tokens usage) 0))}
      (number? cost) (assoc :cost (double cost)))))

(defn- stable-call-id
  [& parts]
  (java.util.UUID/nameUUIDFromBytes
    (.getBytes (pr-str (vec parts)) java.nio.charset.StandardCharsets/UTF_8)))

(defn- completed-proposer-call
  [ctx call-id]
  (some #(when (= call-id (:call-id %)) %)
        (into [] (event-store/read (:event-store ctx)
                                   {:tenant-id (:tenant-id ctx)
                                    :types #{:gepa/proposer-call-completed}}))))

(defn- completed-task-call
  [ctx call-id]
  (some #(when (= call-id (:call-id %)) %)
        (into [] (event-store/read (:event-store ctx)
                                   {:tenant-id (:tenant-id ctx)
                                    :types #{:gepa/task-call-completed}}))))

(defn- optimization-resumed?
  [ctx optimization-id]
  (seq (into []
             (event-store/read (:event-store ctx)
                               {:tenant-id (:tenant-id ctx)
                                :types #{:gepa/optimization-resumed}
                                :tags #{[:optimization optimization-id]}}))))

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
                        (merge-with + total (normalize-usage (:usage call))))
                      {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}
                      calls)})))

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

   Finds every model-instructed node — leaves with an :ai/:llm executor,
   model decisions (:decision executor) and :llm-condition nodes — and
   extracts their instructions for optimization."
  [context sheet-id]
  (u/log ::extracting-instructions :sheet-id sheet-id)
  (let [nodes (sheet/get-nodes-for-sheet context sheet-id)
        ;; Filter for LLM nodes - type :leaf with :executor :llm, or :llm-condition
        llm-nodes (filter (fn [node]
                            (or (and (= :leaf (:type node))
                                     (#{:ai :llm :decision} (:executor node)))
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
  "Build reflective examples from the parent's evaluation on its TRAINING
   minibatch. Pure: it reads nothing but its arguments.

   `trainset` is the training examples, `indices` the training indices of the
   minibatch, and `parent-result` the parent's evaluation on them:
   {:scores [score ...]            ;; positional, one per index
    :outputs {index -> output-map} ;; keyed by TRAINING index
    :feedbacks {index -> string}}  ;; keyed by TRAINING index
   Each example's inputs, generated output and feedback therefore belong to
   one and the same training example. Examples scoring below perfect come
   first; an example without a numeric score (failed or unknown evaluation)
   is never included.

   Returns Python GEPA-compatible ReflectiveExample format:
   {\"Inputs\" {...}, \"Generated Outputs\" {...}, \"Feedback\" \"...\"}

   The feedback is the judges' rich, weakest-first feedback when the metric
   supplied it, else the legacy score string. The generated output is the
   parent's actual answer for that example, `{}` when none was recorded."
  [trainset indices {:keys [scores outputs feedbacks]}]
  (let [entries (for [[index score] (map vector indices scores)
                      :when (number? score)
                      :let [input (nth trainset index)
                            expected (or (metrics/field-value input "answer")
                                         (metrics/field-value input "expected")
                                         (metrics/field-value input "label")
                                         "")
                            perfect? (>= score 1.0)
                            feedback (if (and perfect? (string/blank? (get feedbacks index)))
                                       (str "The response scored "
                                            (format "%.2f" (double score))
                                            " on this example: it met the expectation.")
                                       (reflective-feedback-for-instance
                                         feedbacks index score expected))]]
                  {:perfect? perfect?
                   :example (reflection/make-reflective-example
                              input (get outputs index {}) feedback)})]
    (u/log ::building-reflective-dataset
           :num-examples (count entries)
           :num-rich-feedbacks (count feedbacks)
           :num-outputs (count outputs))
    (->> (concat (remove :perfect? entries) (filter :perfect? entries))
         (mapv :example))))

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
    (or (when idx (rm/get-next-component-to-update state idx))
        (first (sort (keys (:instructions (rm/get-candidate context optimization-id parent-id))))))))

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
                  (stable-call-id :gepa/task optimization-id call-scope role example-index))
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
  (let [;; Build input map from the example — use keyword keys to match blackboard
        input-map (if (map? input)
                    (into {} (map (fn [[k v]] [(keyword k) v]) input))
                    {:input input})
        ;; Supply the durable execution identity ourselves. Successful
        ;; synchronous results do not otherwise promise to echo it, but GEPA's
        ;; call ledger must always point at the actual ORC trace.
        trace-id (random-uuid)
        result (try
                 (assoc (sheet/execute
                          (patch-workflow-instructions context sheet-id instructions)
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

    ;; A workflow that did not succeed has no output to grade, and a metric
    ;; that failed has produced no grade. Neither becomes a score: this path
    ;; refuses exactly as the managed task path does.
    (when-not (= :success (:status result))
      (throw (ex-info "Evaluation task has not produced a complete grade"
                      {:gepa/evaluation-status :failed
                       :reason :workflow-failed
                       :trace-id (:trace-id result)
                       :error (:error result)})))

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
              (throw (ex-info "Evaluation task has not produced a complete grade"
                              {:gepa/evaluation-status :failed
                               :reason :metric-failed
                               :trace-id (:trace-id result)
                               :error (.getMessage e)}))))]
      (let [provenance (trace-model-provenance context (:trace-id result))
            completed-result {:output output
       ;; This must be the durable ORC trace identity. A fabricated UUID makes
       ;; candidate evaluations impossible to audit or replay cross-component.
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

(defn- metric-descriptor-for [context optimization-id]
  (or (:metric-descriptor (rm/get-optimization-summary context optimization-id))
      (when-let [binding (or (optimization/get-metric-fn optimization-id)
                            (:gepa/metric-fn context))]
        (let [descriptor (metric-runtime/prepare! context optimization-id {:metric-fn binding})
              selected (run-command! context
                         {:command/id (random-uuid) :command/timestamp (time/now)
                          :command/name :gepa/select-metric :optimization-id optimization-id
                          :metric-descriptor descriptor})]
          (or (get-in selected [:command/result :metric-descriptor])
              ;; A concurrent delivery may have adopted the same identity first.
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
          boundary-id (stable-call-id :gepa/boundary optimization-id role call-scope)
          boundary (if (= role :candidate)
                     {:kind :candidate :candidate-id call-scope}
                     {:kind :subsample :parent-id (first call-scope) :iteration (second call-scope)})
          tasks (mapv (fn [[index input]]
                        (let [call-id (stable-call-id :gepa/task optimization-id call-scope role index)]
                          {:call-id call-id :trace-id (stable-call-id :gepa/trace call-id)
                           :role role :example-index index :input input :instructions instructions
                           :sheet-id sheet-id :metric-retry-safe? (true? (:retry-safe? descriptor))
                           :max-metric-attempts (:max-attempts descriptor 3)}))
                      indexed-examples)
          opened (task-ledger/open-boundary! context
                   {:optimization-id optimization-id :boundary-id boundary-id
                    :boundary boundary :tasks tasks})]
      (when-not (= :opened (:status opened))
        (throw (ex-info "Evaluation boundary cannot execute"
                        {:gepa/evaluation-status (or (:status opened) :recovery-required)
                         :boundary-id boundary-id :result opened})))
      (assoc call-context :boundary-id boundary-id :metric-descriptor descriptor))))

(defn execute-workflow-with-instructions
  "Evaluate an unmanaged example or execute a previously admitted durable task."
  ([context sheet-id instructions input metric-fn]
   (execute-unmanaged-workflow context sheet-id instructions input metric-fn))
  ([context sheet-id instructions input metric-fn
    {:keys [optimization-id role example-index call-scope boundary-id metric-descriptor] :as call-context}]
   (if-not boundary-id
     (execute-unmanaged-workflow context sheet-id instructions input metric-fn call-context)
     (let [call-id (stable-call-id :gepa/task optimization-id call-scope role example-index)
           outcome (task-execution/execute-task! context
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
        results (doall  ;; Force evaluation for side effects
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

(defn default-metric-fn
  "Default metric: exact string match on 'answer' or 'expected' field.

   Returns 1.0 if output matches expected, 0.0 otherwise."
  [input output]
  (let [expected (or (metrics/field-value input "answer")
                     (metrics/field-value input "expected")
                     (metrics/field-value input "label"))
        ;; A named output, even nil/false, takes precedence over other values.
        missing (Object.)
        named (reduce (fn [_ k]
                        (let [v (metrics/field-value output k missing)]
                          (if (identical? missing v) missing (reduced v))))
                      missing ["answer" "output" "result"])
        actual (if (identical? missing named)
                 (when (= 1 (count output)) (first (vals output)))
                 named)]
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

   Calls the reflection model through llm/predict with
   :use-function-calling? true. This is REQUIRED for structured-output
   reflection models such as gemini-3-flash-preview (whose default
   function-calling is off); a raw chat-completion call returns malformed /
   empty output for those models. The single typed :response output field
   carries the proposer's full text (the proposer then extracts the
   instruction from its ``` block), per the validated reference harness."
  ([context model-name]
   (make-llm-fn context model-name nil))
  ([context model-name completed!]
  (let [provider (or (:llm-provider context) :openrouter)
        module {:inputs [{:name :prompt :spec :string
                          :description "the full proposer prompt"}]
                :outputs [{:name :response :spec :string
                           :description "the full response to the prompt"}]
                :instructions (str "Respond to the prompt exactly as it asks. "
                                   "When it asks for a new instruction in a ``` block, "
                                   "include the full new instruction inside a ``` block.")}]
    (fn [prompt]
      (u/log ::calling-llm :model model-name :prompt-length (count prompt))
      (let [result (llm/predict provider module {:prompt prompt}
                     (merge (:gepa/reflection-options context)
                            {:model model-name
                             :use-function-calling? true
                             :validate? false
                             :with-metadata? true}))
            outputs (or (:outputs result) result)
            response (str (:response outputs))]
        (when completed!
          (completed! {:call-id (random-uuid)
                       :provider provider
                       :model (or (:model result) model-name)
                       :usage (normalize-usage (:usage result))
                       :prompt prompt
                       :response response
                       :prompt-sha256 (sha256 prompt)
                       :response-sha256 (sha256 response)}))
        (u/log ::llm-response-received :response-length (count response))
        response)))))

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
        ;; One template for every component, or one per component; a component
        ;; without one uses the default. Kept apart from :reflection-options,
        ;; which merge into the model request.
        template (:reflection-prompt-template config)
        prompt-template (cond (string? template) template
                              (map? template) (get template (str component)))
        current-instruction (get parent-instructions component)
        formatted-examples (reflection/format-reflective-examples reflective-examples)
        call-id (when (and optimization-id parent-candidate-id (some? iteration))
                  (stable-call-id :gepa/proposer optimization-id parent-candidate-id
                                  iteration component))
        completed (when call-id (completed-proposer-call context call-id))
        llm-fn (make-llm-fn
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
      ;; If no examples or instruction, make a simple improvement
      (str current-instruction " Be more precise and complete in your answers.")
      ;; Use the proposer to generate a new instruction
      (let [effective-llm-fn (if completed
                               (constantly (:response completed))
                               llm-fn)
            result (proposer/propose-new-instruction
                     effective-llm-fn
                     current-instruction
                     formatted-examples
                     :prompt-template prompt-template)]
        (:proposed-instruction result))))))

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
        inherit-from (:inherit-from config)
        inherit? (or (some? inherit-from) (get config :inherit-from-previous true))
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
                               (if inherit-from
                                 (rm/get-candidates-from-optimization
                                   context sheet-id inherit-from
                                   :limit inherit-limit)
                                 (rm/get-all-unique-candidates
                                   context sheet-id
                                   :limit inherit-limit)))]

    ;; 4. Create inherited candidates (skip if same as seed)
    (when (seq inherited-candidates)
      (u/log ::inheriting-candidates
             :count (count inherited-candidates)
             :optimization-id optimization-id)

      (doseq [cand inherited-candidates
              :let [inst (:instructions cand)
                    subscores (:scores-by-fingerprint cand)]
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
           :historical-scores subscores})))))

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

(defn- finish-at-admission-budget-exhaustion!
  "Finish the optimization because a budget ran out: complete with the best
   fully evaluated candidate, or fail naming the budget when none exists.
   `budget` is :metric-calls (default) or :reflection-tokens."
  ([context optimization-id] (finish-at-admission-budget-exhaustion! context optimization-id :metric-calls))
  ([context optimization-id budget]
  (let [tasks (vals (:tasks (task-ledger/get-state context optimization-id)))]
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

(defn- reflection-budget-exhausted?
  "True once the durable proposer-call evidence has reached the optimization's
   `:max-reflection-tokens` (a call may take the total past it by its own usage)."
  [context optimization-id]
  (let [limit (get-in (rm/get-optimization-summary context optimization-id)
                      [:config :max-reflection-tokens])]
    (and (some? limit)
         (>= (rm/get-reflection-tokens-used context optimization-id) limit))))

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
        historical-scores (:historical-scores candidate)
        ;; A recorded score is reused only for an example identical in content,
        ;; on the same workflow content and metric: look each example of this
        ;; run's valset up by fingerprint. Any example without a record means
        ;; the candidate is evaluated in this run and charged to its budget.
        fingerprints (when (seq historical-scores)
                       (rm/valset-fingerprints context optimization-id))
        reusable? (and (seq fingerprints)
                       (every? #(and % (contains? historical-scores %)) fingerprints))]

    (if reusable?
      ;; Reuse the recorded per-example scores: 0 metric calls.
      (let [scores-vec (mapv historical-scores fingerprints)]
        (u/log ::reusing-historical-scores
               :candidate-id candidate-id
               :num-instances (count scores-vec)
               :mean-score (/ (reduce + scores-vec) (count scores-vec)))
        (run-command! context
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :gepa/record-evaluation-result
           :optimization-id optimization-id
           :candidate-id candidate-id
           :scores scores-vec
           :trace-ids []  ;; No traces - reusing history
           :metric-calls 0}))

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

            ;; Evaluate the actual workflow. Fabricated scores would make a
            ;; provider/runtime failure look like optimization evidence.
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

          ;; Record the evaluation results (including any per-instance judge
          ;; feedback so the reflective dataset can use it instead of the
          ;; score-only string). Omit absent optional values rather than
          ;; submitting schema-invalid nils.
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

;; =============================================================================
;; Mutation round: reflect on the parent's training minibatch
;; =============================================================================

(defn- fail-optimization! [context optimization-id message]
  (run-command! context
    {:command/id (random-uuid)
     :command/timestamp (time/now)
     :command/name :gepa/fail-optimization
     :optimization-id optimization-id
     :error-message message}))

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

          ;; Pending or recovery-required work resumes through the ledger.
          status nil

          :else
          (fail-optimization! context optimization-id
                              (str "Parent minibatch evaluation failed: " (.getMessage e)))))
      nil)))

(declare request-mutation-round-within-budget!)

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
    ;; No round may open once the recorded reflection tokens reach the budget:
    ;; no metric call is spent on a round that cannot propose.
    (finish-at-admission-budget-exhaustion! context optimization-id :reflection-tokens)
    (request-mutation-round-within-budget! context optimization-id fallback-parent-id)))

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
         :subsample-indices (vec (distinct (sampler/epoch-shuffled-batch-sampler
                                             (count trainset) minibatch-size iteration)))}))))

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
        metric-fn (or (optimization/get-metric-fn optimization-id)
                      (:gepa/metric-fn context)
                      default-metric-fn)]
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
            ;; No proposal exists for this round; the parent's own
            ;; instructions fill the event's proposal slot. The comparison is
            ;; recorded as rejected, so no candidate is created.
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
          ;; The reflection budget is checked again just before the proposer
          ;; call, since the round may have been opened earlier. A call already
          ;; completed for this round is reused on resume and costs nothing, so
          ;; it never stops the round.
          (if (and (reflection-budget-exhausted? context optimization-id)
                   (not (completed-proposer-call
                          context (stable-call-id :gepa/proposer optimization-id parent-id
                                                  iteration component))))
            (finish-at-admission-budget-exhaustion! context optimization-id :reflection-tokens)
          ;; Provider failures are terminal and visible; they must not cause
          ;; event retries to create repeated frontier updates or fabricated
          ;; candidates.
          (try
            (let [examples (build-reflective-dataset trainset indices parent-result)
                  new-instruction (generate-mutated-instruction
                                    context parent-instructions component examples
                                    reflection-model optimization-id parent-id iteration)]
              (u/log ::mutation-generated
                     :component component
                     :num-reflective-examples (count examples)
                     :new-instruction-length (count new-instruction))
              ;; Persist the complete proposal as its own crash boundary.
              ;; A separate processor advances it to the acceptance gate.
              (run-command! context
                {:command/id (random-uuid)
                 :command/timestamp (time/now)
                 :command/name :gepa/record-proposal-ready
                 :optimization-id optimization-id
                 :proposal-id (stable-call-id :gepa/proposal optimization-id parent-id iteration component)
                 :parent-id parent-id
                 :proposed-instructions (assoc parent-instructions component new-instruction)
                 :component-updated component
                 :subsample-indices indices
                 :iteration iteration}))
            (catch Exception e
              (fail-optimization! context optimization-id
                                  (str "Instruction proposal failed: " (.getMessage e)))))))))))

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

      ;; Continue: reflect on the parent's training minibatch
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
                                trainset subsample-indices metric-fn
                                {:optimization-id optimization-id
                                 :role :subsample-parent
                                 :call-scope [parent-id iteration]})
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
                   :iteration iteration
                   :parent-scores parent-scores
                   :proposed-scores parent-scores  ;; Use parent scores (didn't evaluate proposed)
                   :accepted? false                 ;; Not accepted - parent was perfect
                   :metric-calls (:metric-calls parent-result)
                   :accounting-mode :tasks :task-call-ids (:task-call-ids parent-result)
                   :parent-perfect-skip? true}))  ;; Only counted parent evals

              ;; Normal case - evaluate proposed and compare
              (let [;; Evaluate PROPOSED on same subsample
                    proposed-result (evaluate-candidate-on-subsample
                                      context sheet-id proposed-instructions
                                      trainset subsample-indices metric-fn
                                      {:optimization-id optimization-id
                                       :role :subsample-proposed
                                       :call-scope [parent-id iteration
                                                    (sha256 proposed-instructions)]})
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
          ;; On error, skip this mutation and let the system continue
          nil))

      ;; Missing observations cannot justify a comparison or consume metric
      ;; budget. Surface the unavailable prerequisite as durable failure.
      (run-command! context
        {:command/id (random-uuid)
         :command/timestamp (time/now)
         :command/name :gepa/fail-optimization
         :optimization-id optimization-id
         :error-message "Subsample evaluation requires a persisted non-empty training set and workflow"})))))

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

          ;; Budget remains - the next mutation round
          (request-mutation-round! context optimization-id parent-id))))))

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

(defprocessor :gepa on-recovery-scan-triggered
  {:topics #{:gepa/recovery-scan-triggered}}
  "Discover abandoned evaluations using canonical task ownership."
  [context]
  (recovery/recover-in-progress! context)
  {})

(defprocessor :gepa on-optimization-recovery-requested
  {:topics #{:gepa/optimization-recovery-requested}}
  "Resume the original durable boundary on the normal Grain processor pool."
  [context]
  ((requiring-resolve 'ai.obney.orc.gepa.interface/resume!)
    context (get-in context [:event :optimization-id]))
  {})
