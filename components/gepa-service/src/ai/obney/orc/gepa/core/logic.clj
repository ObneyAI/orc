(ns ai.obney.orc.gepa.core.logic
  (:require [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u])
  (:import java.time.Instant
           java.util.concurrent.Callable
           java.util.concurrent.ExecutionException
           java.util.concurrent.FutureTask
           java.util.concurrent.TimeUnit
           java.util.concurrent.TimeoutException))

(defn sha256 [value]
  (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str value)
                                  java.nio.charset.StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))))

(defn snapshot-instructions
  [version]
  (letfn [(walk [node]
            (cons (select-keys node [:name :instruction])
                  (mapcat walk (:children node))))]
    (->> (walk (get-in version [:snapshot :nodes]))
         (keep (fn [{:keys [name instruction]}]
                 (when (and name instruction) [name instruction])))
         (into (sorted-map)))))

(defonce ^:private completion-registry (atom {}))

(defn unregister-completion! [optimization-id]
  (swap! completion-registry dissoc optimization-id))

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
    (unregister-completion! optimization-id)))

;; =============================================================================
;; Event Types
;; =============================================================================

(def gepa-event-types
  #{:gepa/optimization-started
    :gepa/datasets-stored
    :gepa/candidate-created
    :gepa/candidate-evaluation-started
    :gepa/candidate-evaluated
    :gepa/example-evaluated
    :gepa/frontier-updated
    :gepa/reflective-dataset-built
    :gepa/proposer-call-completed
    :gepa/task-call-completed
    :gepa/parent-minibatch-evaluation-requested
    :gepa/proposal-ready
    :gepa/winner-applied
    :gepa/mutation-proposed
    :gepa/candidate-acceptance-decision
    :gepa/subsample-evaluation-started
    :gepa/subsample-evaluated
    :gepa/merge-attempted
    :gepa/crossover-performed
    :gepa/optimization-completed
    :gepa/optimization-failed})

;; =============================================================================
;; EXACT Python GEPA Prompt Template
;; =============================================================================

(def python-gepa-prompt-template
  "EXACT copy of Python GEPA's InstructionProposalSignature.default_prompt_template.

   Placeholders:
   - <curr_param> → current instruction
   - <side_info> → formatted reflective examples"
  "I provided an assistant with the following instructions to perform a task for me:
```
<curr_param>
```

The following are examples of different task inputs provided to the assistant along with the assistant's response for each of them, and some feedback on how the assistant's response could be better:
```
<side_info>
```

Your task is to write a new instruction for the assistant.

Read the inputs carefully and identify the input format and infer detailed task description about the task I wish to solve with the assistant.

Read all the assistant responses and the corresponding feedback. Identify all niche and domain specific factual information about the task and include it in the instruction, as a lot of it may not be available to the assistant in the future. The assistant may have utilized a generalizable strategy to solve the task, if so, include that in the instruction as well.

Provide the new instructions within ``` blocks.")

(def general-rules-prompt-template
  "Optional reflection prompt for callers to pass as `:reflection-prompt-template`.

   GEPA's default template with three sentences inserted before its last line.
   GEPA's default asks the rewrite to include the niche and domain facts the
   examples show; this template asks for general rules instead of copied
   example texts, and leaves out values that each input already carries.
   Nothing selects it unless a caller passes it."
  (str/replace
    python-gepa-prompt-template
    "\n\nProvide the new instructions within ``` blocks."
    (str "\n\nThe new instruction will be used on many inputs you have not seen. "
         "Turn what the feedback shows into general rules: describe the kind of input or "
         "mistake, and do not copy these examples' exact texts, names or answers into the "
         "instruction. Include domain knowledge the inputs do not provide; leave out "
         "values that each input already provides, since they can differ from one input "
         "to the next."
         "\n\nProvide the new instructions within ``` blocks.")))

(defn field-value
  "Read an exact field first, then its string/keyword equivalent. Presence,
   including explicit nil or false, wins over the equivalent spelling."
  ([fields k] (field-value fields k nil))
  ([fields k not-found]
   (let [equivalent (cond (string? k) (keyword k)
                         (keyword? k) (subs (str k) 1))]
     (cond (contains? fields k) (get fields k)
           (and equivalent (contains? fields equivalent)) (get fields equivalent)
           :else not-found))))

(defn make-exact-match-metric
  "Create a metric function that checks for exact match on a key.

   Usage:
   (make-exact-match-metric \"answer\")
   ;; Returns 1.0 if output[\"answer\"] == expected[\"answer\"], else 0.0"
  [output-key]
  (with-meta (fn [expected actual]
    (let [exp-val (field-value expected output-key)
          act-val (field-value actual output-key)]
      (if (and exp-val act-val (= (str exp-val) (str act-val)))
        1.0
        0.0)))
    {:ai.obney.orc.gepa.core.metrics/descriptor {:kind :exact-match :configuration {:output-key output-key}}}))

(defn make-contains-metric
  "Create a metric function that checks if output contains expected.

   Usage:
   (make-contains-metric \"answer\")
   ;; Returns 1.0 if expected is substring of output, else 0.0"
  [output-key]
  (with-meta (fn [expected actual]
    (let [exp-val (str (field-value expected output-key ""))
          act-val (str (field-value actual output-key ""))]
      (if (and (seq exp-val)
               (seq act-val)
               (.contains (.toLowerCase act-val) (.toLowerCase exp-val)))
        1.0
        0.0)))
    {:ai.obney.orc.gepa.core.metrics/descriptor {:kind :contains :configuration {:output-key output-key}}}))

(defn weighted-combine
  "Combine per-dimension judge results into a single [0,1] score.

   `dimension-results` is a seq of {:dim :score :weight}. Returns the
   weight-normalized weighted mean of the scores. Returns 0.0 (never NaN) when
   there are no dimensions or the total weight is zero."
  [dimension-results]
  (let [total-weight (reduce + 0.0 (map :weight dimension-results))]
    (if (pos? total-weight)
      (/ (reduce + 0.0 (map (fn [{:keys [score weight]}]
                              (* (double weight) (double score)))
                            dimension-results))
         total-weight)
      0.0)))

(defn format-judge-feedback
  "Render the per-dimension judge results into one actionable feedback string,
   WEAKEST dimensions first (so the proposer attacks the biggest problems
   first). `dimension-results` is a seq of {:dim :score :feedback :reasoning}.

   Each line: 'dimension (0.NN): <feedback> [reasoning: <reasoning>]'.

   When EVERY dimension's feedback is blank (a score-only judge, or a judge
   that wrote none) there is no feedback to give: the result is \"\", never a
   placeholder, so GEPA's own Expected-output fallback
   (`reflective-feedback-for-instance`) fires instead of being masked."
  [dimension-results]
  (let [blank? #(str/blank? %)]
    (if (every? (comp blank? :feedback) dimension-results)
      ""
      (->> dimension-results
           (sort-by :score)
           (map (fn [{:keys [dim score feedback reasoning]}]
                  (let [fb (or (not-empty (some-> feedback str/trim)) "(no feedback)")
                        rs (not-empty (some-> reasoning str/trim))]
                    (str (name dim) " (" (format "%.2f" (double score)) "): " fb
                         (when rs (str " [reasoning: " rs "]"))))))
           (str/join "\n")))))

(def ^:private dimension->judge
  {:grounding             {:fn evaluation/grounding-judge             :result-key :grounding-result}
   :instruction-following {:fn evaluation/instruction-following-judge :result-key :instruction-result}
   :reasoning             {:fn evaluation/reasoning-judge             :result-key :reasoning-result}
   :completeness          {:fn evaluation/completeness-judge          :result-key :completeness-result}})

(defn- run-judge-dimension
  "Run a single tier-1 judge on the trace-data and extract its score + feedback.
   Returns {:dim :score :feedback :reasoning :weight}. A failed judge is an
   evaluation failure, never a fabricated zero grade."
  [dim weight trace-data]
  (let [{:keys [fn result-key]} (dimension->judge dim)]
    (try
      (let [result (-> (fn {:inputs {:trace-data trace-data}})
                       (get result-key))]
        {:dim dim
         :weight weight
         :score (double (:score result))
         :feedback (str (or (:feedback result) ""))
         :reasoning (str (or (:reasoning result) ""))})
      (catch Exception t
        (u/log :ai.obney.orc.gepa.core.metrics/judge-dimension-error :dim dim :error (.getMessage t))
        (throw (ex-info "Judge dimension did not produce a grade"
                        {:reason :judge-dimension-failed :dimension dim} t))))))

(def default-judge-task
  "Default STABLE grading task the judges score the response against.

   CRITICAL: the judges must grade against the optimization GOAL, NOT the
   candidate's own instruction. If we fed the candidate instruction in as the
   judges' :instruction, instruction-following would reward Goodharting — a
   deliberately bad instruction like 'reply with banana' that the producer
   faithfully obeys would score HIGH. Grading against a stable task means a
   'banana' answer scores low (it doesn't answer the question), which is what
   lets GEPA climb from a bad seed to a good instruction.

   Override per-optimization via :judges {:weights {..} :task \"...\"}."
  "Answer the user's question helpfully, accurately, and completely.")

(defn- input-key-set
  "The set of input keys (both string and keyword forms) so we can tell the
   producer's RESPONSE apart from input fields the workflow echoes back into
   the output blackboard."
  [input]
  (if (map? input)
    (into #{} (mapcat (fn [k] [k (if (keyword? k) (name k) (keyword k))])
                      (keys input)))
    #{}))

(defn extract-response
  "Extract the producer's actual RESPONSE string from a GEPA (input, output)
   pair.

   The workflow's output map is the full blackboard — it echoes the INPUT
   fields alongside the written output(s). Picking the first string value
   blindly grabs an echoed input (e.g. the user's question), which makes every
   judge see the question as the answer and score 0. So: prefer the canonical
   answer keys, then fall back to a string output value whose key is NOT an
   input key (the genuinely-written output)."
  [input output]
  (cond
    (string? output) output
    (map? output)
    (let [in-keys (input-key-set input)]
      (or (get output "answer")     (get output :answer)
          (get output "assistant-response") (get output :assistant-response)
          (get output "response")   (get output :response)
          (get output "output")     (get output :output)
          (get output "result")     (get output :result)
          (->> output
               (filter (fn [[k v]] (and (string? v) (not (contains? in-keys k)))))
               first
               val)
          (first (filter string? (vals output)))
          (pr-str output)))
    :else (str output)))

(defn- ->trace-data
  "Build the judges' trace-data map from a GEPA (input, output) pair plus the
   STABLE grading task. The four tier-1 judges read :response, :inputs, and
   :instruction (the grading task — NOT the candidate instruction)."
  [input output task]
  {:response (str (extract-response input output))
   :inputs (if (map? input) (dissoc input "answer" "expected" "label") input)
   :instruction (or task default-judge-task)})

(def default-judge-weights
  "Default tier-1 weights when :judges is passed as a bare set/`true` rather
   than an explicit weight map."
  {:grounding 0.30
   :instruction-following 0.25
   :reasoning 0.20
   :completeness 0.25})

(defn normalize-judge-config
  "Normalize the :judges value into {:weights {dim -> w} :task string}.

   Accepts:
   - a weight map {:grounding 0.3 ..}                  (task defaults)
   - a set / seq of dims #{:grounding :reasoning}      (default weights for those)
   - {:weights {..} :task \"...\"}                       (explicit, with grading task)
   - true / nil                                        (all default)"
  [judges]
  (let [explicit-task (when (and (map? judges) (contains? judges :task)) (:task judges))
        raw-weights (cond
                      (and (map? judges) (contains? judges :weights)) (:weights judges)
                      (map? judges) judges
                      (or (set? judges) (sequential? judges))
                      (select-keys default-judge-weights judges)
                      :else default-judge-weights)
        weights (if (seq raw-weights) raw-weights default-judge-weights)]
    {:weights weights
     :task (or explicit-task default-judge-task)}))

(defn make-judge-metric
  "Create a real GEPA metric backed by the orc tier-1 evaluation judges.

   `judges` is the optimization's :judges config — see normalize-judge-config:
   a weight map {:grounding w :instruction-following w :reasoning w
   :completeness w} (any subset; weights normalized), or
   {:weights {..} :task \"...\"} to also set the STABLE grading task. The judges
   grade the response against that task — NOT the candidate instruction (see
   default-judge-task for why).

   The returned fn has signature (fn [input output] -> {:score :feedback}):
   - runs each weighted judge dimension IN PARALLEL (futures — sequential judge
     calls timed out GEPA budgets), then
   - returns the weight-normalized [0,1] :score AND a weakest-first :feedback
     string aggregating every dimension's :feedback/:reasoning.

   GEPA's execute-workflow path accepts either a bare number or this
   {:score :feedback} map (see todo-processors/score+feedback-of), and threads
   the :feedback into the reflective dataset."
  [judges]
  (let [{:keys [weights task]} (normalize-judge-config judges)]
    (with-meta (fn judge-metric
      [input output]
      (let [trace-data (->trace-data input output task)
            futs (mapv (fn [[dim weight]]
                         [dim (future (run-judge-dimension dim weight trace-data))])
                       weights)]
        (try
          (let [dimension-results (mapv (fn [[_dim fut]] @fut) futs)]
            {:score (weighted-combine dimension-results)
             :feedback (format-judge-feedback dimension-results)})
          (finally
            (doseq [[_dim fut] futs]
              (when-not (realized? fut) (future-cancel fut)))))))
      {:ai.obney.orc.gepa.core.metrics/descriptor {:kind :judges :configuration {:weights weights :task task}}})))

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
        factory (:ai.obney.orc.gepa.core.metrics/descriptor (meta metric-fn))
        kind (cond factory (:kind factory) metric-fn :custom judges :judges :else :default)
        descriptor {:kind kind
                    :metric-id (if (= kind :custom)
                                 (str (or metric-id (str "anonymous/" optimization-id)))
                                 (str "orc/" (name kind)))
                    :revision (str (or metric-revision "1"))
                    :configuration (if (= kind :judges)
                                     (assoc (or (:configuration factory)
                                                (normalize-judge-config judges))
                                            :judge-config (evaluation/current-judge-config))
                                     (or (:configuration factory) {}))
                    :retry-safe? (if (= kind :custom) (true? metric-retry-safe?) true)
                    :max-attempts max-attempts
                    :timeout-ms timeout-ms}]
    (when (= kind :custom) (register! context descriptor metric-fn))
    descriptor))

(defonce ^:private metric-fn-registry (atom {}))

(defn register-metric-fn!
  "Register a metric function for an optimization-id.
   Called by optimize! so async todo processors can look it up."
  [optimization-id metric-fn]
  (swap! metric-fn-registry assoc optimization-id metric-fn))

(defn unregister-metric-fn!
  "Remove a metric function from the registry (cleanup after optimization)."
  [optimization-id]
  (swap! metric-fn-registry dissoc optimization-id))

(defn accounting [state]
  (let [reserved (count (filter #(zero? (:attempt-count % 0)) (vals (:tasks state))))
        spent (:spent state 0) budget (:budget state 0)]
    {:spent spent :reserved reserved :budget budget :available (- budget spent reserved)}))

(defn default-metric-fn
  "Default metric: exact string match on 'answer' or 'expected' field.

   Returns 1.0 if output matches expected, 0.0 otherwise."
  [input output]
  (let [expected (or (field-value input "answer")
                     (field-value input "expected")
                     (field-value input "label"))
        missing (Object.)
        named (reduce (fn [_ k]
                        (let [v (field-value output k missing)]
                          (if (identical? missing v) missing (reduced v))))
                      missing ["answer" "output" "result"])
        actual (if (identical? missing named)
                 (when (= 1 (count output)) (first (vals output)))
                 named)]
    (if (and expected actual)
      (if (= (str expected) (str actual))
        1.0
        (if (and (string? actual)
                 (string? expected)
                 (.contains (.toLowerCase (str actual))
                            (.toLowerCase (str expected))))
          0.7
          0.0))
      0.0)))

(defn get-metric-fn
  "Get the registered metric function for an optimization-id.
   Returns nil if not registered (processors fall back to default-metric-fn)."
  [optimization-id]
  (get @metric-fn-registry optimization-id))

(defn stable-call-id
  [& parts]
  (java.util.UUID/nameUUIDFromBytes
    (.getBytes (pr-str (vec parts)) java.nio.charset.StandardCharsets/UTF_8)))

(defn resolve-metric
  "Resolve the original metric; returned functions retain the two-argument API."
  [context descriptor]
  (case (:kind descriptor)
    :exact-match
    {:status :ready
     :metric-fn (make-exact-match-metric
                  (get-in descriptor [:configuration :output-key]))}
    :contains
    {:status :ready
     :metric-fn (make-contains-metric
                  (get-in descriptor [:configuration :output-key]))}
    :judges
    (let [configuration (:configuration descriptor)
          metric (make-judge-metric configuration)]
      {:status :ready
       :metric-fn (fn [input output]
                    (evaluation/call-with-judge-config
                      (:judge-config configuration)
                      #(metric input output)))})
    :default
    {:status :ready
     :metric-fn default-metric-fn}
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

(defn normalize-usage
  "Token usage as recorded in durable evidence. A cost the provider reported is
   kept (as a double); an absent cost is never invented."
  [usage]
  (let [cost (:cost usage)]
    (cond-> {:prompt-tokens (int (or (:prompt-tokens usage) (:prompt_tokens usage) 0))
             :completion-tokens (int (or (:completion-tokens usage) (:completion_tokens usage) 0))
             :total-tokens (int (or (:total-tokens usage) (:total_tokens usage) 0))}
      (number? cost) (assoc :cost (double cost)))))

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

;; =============================================================================
;; ReflectiveExample Structure - Matches Python TypedDict
;; =============================================================================

(defn make-reflective-example
  "Create a ReflectiveExample matching Python's TypedDict structure.

   Arguments:
   - inputs: Map of input field names to values
   - generated-outputs: Map of output field names to values, OR error string
   - feedback: Feedback text for the LLM

   Returns:
   {:Inputs {...}
    :Generated-Outputs {...} or \"error message\"
    :Feedback \"...\"}"
  [inputs generated-outputs feedback]
  {"Inputs" inputs
   "Generated Outputs" generated-outputs
   "Feedback" feedback})

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
    (if (and rich (not (str/blank? rich)))
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
                            expected (or (field-value input "answer")
                                         (field-value input "expected")
                                         (field-value input "label")
                                         "")
                            perfect? (>= score 1.0)
                            feedback (if (and perfect? (str/blank? (get feedbacks index)))
                                       (str "The response scored "
                                            (format "%.2f" (double score))
                                            " on this example: it met the expectation.")
                                       (reflective-feedback-for-instance
                                         feedbacks index score expected))]]
                  {:perfect? perfect?
                   :example (make-reflective-example
                              input (get outputs index {}) feedback)})]
    (u/log :ai.obney.orc.gepa.core.todo-processors/building-reflective-dataset
           :num-examples (count entries)
           :num-rich-feedbacks (count feedbacks)
           :num-outputs (count outputs))
    (->> (concat (remove :perfect? entries) (filter :perfect? entries))
         (mapv :example))))

(defn extract-instruction-from-response
  "Extract instruction from ``` blocks, matching Python GEPA's logic.

   Python implementation:
   1. Find first and last ``` positions
   2. If they're the same or overlap, handle incomplete blocks
   3. Skip optional language specifier after opening ```
   4. Return stripped content between blocks"
  [lm-out]
  (let [start (str/index-of lm-out "```")
        end (str/last-index-of lm-out "```")]
    (cond
      (nil? start)
      (str/trim lm-out)

      (or (nil? end) (>= (+ start 3) end))
      (let [stripped (str/trim lm-out)]
        (cond
          (str/starts-with? stripped "```")
          (let [after-opening (subs stripped 3)]
            (if-let [newline-idx (str/index-of after-opening "\n")]
              (str/trim (subs after-opening (inc newline-idx)))
              (str/trim after-opening)))

          (str/ends-with? stripped "```")
          (str/trim (subs stripped 0 (- (count stripped) 3)))

          :else
          stripped))

      :else
      (let [content (subs lm-out (+ start 3) end)]
        (if-let [newline-idx (str/index-of content "\n")]
          (let [first-line (subs content 0 newline-idx)]
            (if (and (seq first-line)
                     (not (str/includes? first-line " ")))
              (str/trim (subs content (inc newline-idx)))
              (str/trim content)))
          (str/trim content))))))

(defn validate-prompt-template
  "Validate that prompt template contains required placeholders.
   Matches Python's InstructionProposalSignature.validate_prompt_template"
  [template]
  (when template
    (let [required-placeholders ["<curr_param>" "<side_info>"]
          missing (remove #(str/includes? template %) required-placeholders)]
      (when (seq missing)
        (throw (ex-info (str "Missing placeholder(s) in prompt template: "
                             (str/join ", " missing))
                        {:missing missing}))))))

(defn render-proposal-prompt
  "Render the proposal prompt with placeholders filled in.

   Arguments:
   - current-instruction: The current instruction text
   - formatted-examples: Pre-formatted reflective examples string
   - prompt-template: Optional custom template (uses default if nil)"
  ([current-instruction formatted-examples]
   (render-proposal-prompt current-instruction formatted-examples nil))
  ([current-instruction formatted-examples prompt-template]
   (let [template (or prompt-template python-gepa-prompt-template)]
     (validate-prompt-template template)
     (-> template
         (str/replace "<curr_param>" current-instruction)
         (str/replace "<side_info>" formatted-examples)))))

(defn propose-new-instruction
  "Propose a new instruction using the EXACT Python GEPA method.

   This function can be called directly without needing the ORC workflow,
   which is useful for integration into the todo processors.

   Arguments:
   - llm-fn: Function that takes a prompt and returns LLM response
   - current-instruction: The instruction to improve
   - formatted-examples: Pre-formatted reflective examples string
   - prompt-template: Optional custom template for this component

   Returns:
   {:proposed-instruction \"extracted instruction\"
    :raw-response \"full LLM response\"}"
  [llm-fn current-instruction formatted-examples & {:keys [prompt-template]}]
  (u/log :ai.obney.orc.gepa.core.proposer/proposing-new-instruction
         :instruction-length (count current-instruction)
         :examples-length (count formatted-examples))

  (let [prompt (render-proposal-prompt current-instruction
                                       formatted-examples
                                       prompt-template)
        response (llm-fn prompt)
        extracted (extract-instruction-from-response response)]

    (u/log :ai.obney.orc.gepa.core.proposer/instruction-proposed
           :extracted-length (count extracted))

    {:proposed-instruction extracted
     :raw-response response}))

(defn render-value
  "Render a value as markdown, matching Python's render_value logic.

   - Images are replaced with [IMAGE-N] placeholders (not applicable in Clojure)
   - Dicts become nested headers
   - Lists become numbered items
   - Other values are stringified

   Arguments:
   - value: The value to render
   - level: Markdown header depth (default 3)

   Returns:
   Formatted string"
  ([value] (render-value value 3))
  ([value level]
   (cond
     (map? value)
     (if (empty? value)
       "\n"
       (str/join
         (for [[k v] value]
           (str (apply str (repeat level "#")) " " (name k) "\n"
                (render-value v (min (inc level) 6))))))

     (sequential? value)
     (if (empty? value)
       "\n"
       (str/join
         (map-indexed
           (fn [i item]
             (str (apply str (repeat level "#")) " Item " (inc i) "\n"
                  (render-value item (min (inc level) 6))))
           value)))

     :else
     (str (str/trim (str value)) "\n\n"))))

(defn convert-sample-to-markdown
  "Convert a single ReflectiveExample to markdown.

   Matches Python's convert_sample_to_markdown function.

   Arguments:
   - sample: Map with 'Inputs', 'Generated Outputs', 'Feedback' keys
   - example-num: 1-based example number

   Returns:
   Formatted markdown string"
  [sample example-num]
  (let [sb (StringBuilder.)]
    (.append sb (str "# Example " example-num "\n"))
    (doseq [[k v] sample]
      (.append sb (str "## " k "\n"))
      (.append sb (render-value v 3)))
    (.toString sb)))

(defn format-reflective-examples
  "Format a list of ReflectiveExamples as markdown.

   Matches Python's format_samples function from instruction_proposal.py.

   Arguments:
   - examples: Sequence of ReflectiveExample maps

   Returns:
   Formatted markdown string with all examples"
  [examples]
  (->> examples
       (map-indexed (fn [idx sample]
                      (convert-sample-to-markdown sample (inc idx))))
       (str/join "\n\n")))

(defn sha256-string
  [s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (str s) java.nio.charset.StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

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
      (u/log :ai.obney.orc.gepa.core.todo-processors/calling-llm :model model-name :prompt-length (count prompt))
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
                       :prompt-sha256 (sha256-string prompt)
                       :response-sha256 (sha256-string response)}))
        (u/log :ai.obney.orc.gepa.core.todo-processors/llm-response-received :response-length (count response))
        response)))))

(defn- format-parse-failure
  "Format a parse failure message matching Python's FailedPrediction handling.

   Python format:
   \"Couldn't parse the output as per the expected output format. The model's raw response was:
   ```
   {raw_response}
   ```\""
  [raw-response]
  (str "Couldn't parse the output as per the expected output format. "
       "The model's raw response was:\n"
       "```\n"
       raw-response "\n"
       "```\n\n"))

(defn build-reflective-example
  "Build a single ReflectiveExample from evaluation data.

   Arguments:
   - inputs: Map of input field names to values
   - outputs: Map of output field names to values, or nil for failures
   - raw-response: Raw LLM response (for parse failures)
   - feedback-fn: Function that generates feedback from (inputs, outputs, expected)
   - expected: Expected outputs for comparison

   Returns:
   ReflectiveExample map"
  [{:keys [inputs outputs raw-response feedback-fn expected parse-failed?]}]
  (let [
        formatted-inputs (into {}
                           (map (fn [[k v]]
                                  [(if (keyword? k) (name k) (str k))
                                   (if (string? v) v (json/generate-string v))])
                                inputs))

        formatted-outputs (if parse-failed?
                            (format-parse-failure raw-response)
                            (into {}
                              (map (fn [[k v]]
                                     [(if (keyword? k) (name k) (str k))
                                      (if (string? v) v (str v))])
                                   outputs)))

        feedback (if parse-failed?
                   "Your output failed to parse. Please follow the expected output format."
                   (if feedback-fn
                     (feedback-fn {:inputs inputs
                                   :outputs outputs
                                   :expected expected})
                     "No feedback provided."))]

    (make-reflective-example formatted-inputs formatted-outputs feedback)))

;; =============================================================================
;; ORC-Specific Helpers
;; =============================================================================

(defn orc-trace->reflective-data
  "Convert an ORC execution trace to reflective dataset format.

   ORC traces have structure:
   {:nodes {\"node-name\" {:inputs {...} :outputs {...}}
            ...}
    :blackboard {...}
    :status :success/:failure}

   Arguments:
   - trace: ORC execution trace
   - component-name: Name of the LLM node
   - expected: Expected outputs
   - feedback-fn: Function to generate feedback

   Returns:
   ReflectiveExample or nil if component not in trace"
  [trace component-name expected feedback-fn]
  (when-let [node-data (get-in trace [:nodes component-name])]
    (let [{:keys [inputs outputs status raw-response]} node-data
          parse-failed? (= status :parse-failure)]
      (build-reflective-example
        {:inputs inputs
         :outputs outputs
         :raw-response raw-response
         :parse-failed? parse-failed?
         :expected expected
         :feedback-fn feedback-fn}))))

(defn build-orc-reflective-dataset
  "Build reflective dataset from ORC evaluation results.

   Arguments:
   - evaluation-results: Sequence of {:trace, :inputs, :expected, :score} maps
   - components: List of LLM node names
   - feedback-fns: Map of component-name → feedback-fn

   Returns:
   Map of component-name → formatted-examples-string (ready for proposer)"
  [evaluation-results components feedback-fns]
  (let [raw-dataset
        (reduce
          (fn [dataset {:keys [trace inputs expected]}]
            (reduce
              (fn [d component]
                (if-let [example (orc-trace->reflective-data
                                   trace
                                   component
                                   expected
                                   (get feedback-fns component))]
                  (update d component (fnil conj []) example)
                  d))
              dataset
              components))
          {}
          evaluation-results)]

    (into {}
      (map (fn [[component examples]]
             [component (format-reflective-examples examples)])
           raw-dataset))))

(defn is-dominated?
  "Check if program y is dominated by other programs.

   EXACT match to Python's is_dominated function.

   A program y is dominated if for EVERY front that y appears in,
   at least one program from the 'programs' set also appears in that front.

   This means y has no 'unique' contribution - all instances where y is best
   are also covered by other programs.

   Arguments:
   - y: The program index to check
   - programs: Set of other program indices to compare against
   - pareto-front-mapping: Map of {instance-id -> #{program-ids that are best}}

   Returns true if y is dominated."
  [y programs pareto-front-mapping]
  (let [
        y-fronts (->> (vals pareto-front-mapping)
                      (filter #(contains? % y)))]
    (if (empty? y-fronts)
      true
      (every?
        (fn [front]
          (some #(contains? programs %) front))
        y-fronts))))

(defn remove-dominated-programs
  "Remove dominated programs from the Pareto front.

   EXACT match to Python's remove_dominated_programs function.

   Iteratively removes programs whose best-at instances are all
   also best-at by other programs. The order of removal is determined
   by scores (lowest first) to preserve higher-scoring programs.

   Arguments:
   - pareto-front-mapping: Map of {instance-id -> #{program-ids that are best}}
   - scores: Optional sequence of scores indexed by program-id (nil uses uniform)

   Returns:
   New pareto-front-mapping with dominated programs removed."
  ([pareto-front-mapping]
   (remove-dominated-programs pareto-front-mapping nil))
  ([pareto-front-mapping scores]
   (u/log :ai.obney.orc.gepa.core.pareto/remove-dominated-programs-start
          :num-instances (count pareto-front-mapping))

   (let [
         freq (reduce
                (fn [f front]
                  (reduce
                    (fn [f' p]
                      (update f' p (fnil inc 0)))
                    f
                    front))
                {}
                (vals pareto-front-mapping))

         programs (keys freq)
         score-map (if scores
                     (into {} (map-indexed (fn [i s] [i s]) scores))
                     (zipmap programs (repeat 1)))
         sorted-programs (sort-by #(get score-map % 0) programs)]

     (loop [dominated #{}
            remaining (vec sorted-programs)
            found-to-remove true]
       (if-not found-to-remove
         (let [dominators (set/difference (set sorted-programs) dominated)]
           (u/log :ai.obney.orc.gepa.core.pareto/remove-dominated-programs-done
                  :num-dominators (count dominators)
                  :num-dominated (count dominated))
           (into {}
             (map (fn [[instance-id front]]
                    [instance-id (set/intersection front dominators)])
                  pareto-front-mapping)))

         (let [result
               (reduce
                 (fn [_ y]
                   (when-not (dominated y)
                     (let [other-progs (set/difference
                                         (set remaining)
                                         #{y}
                                         dominated)]
                       (when (is-dominated? y other-progs pareto-front-mapping)
                         (reduced {:found y})))))
                 nil
                 remaining)]
           (if result
             (recur (conj dominated (:found result))
                    remaining
                    true)
             (recur dominated remaining false))))))))

(defn find-dominator-programs
  "Find the non-dominated programs in the Pareto front.

   EXACT match to Python's find_dominator_programs function.

   Arguments:
   - pareto-front-mapping: Map of {instance-id -> #{program-ids}}
   - agg-scores: Sequence of aggregate scores indexed by program-id

   Returns:
   Vector of program-ids that are dominators (non-dominated)."
  [pareto-front-mapping agg-scores]
  (let [filtered-mapping (remove-dominated-programs pareto-front-mapping agg-scores)
        uniq-progs (->> (vals filtered-mapping)
                        (mapcat identity)
                        set)]
    (vec uniq-progs)))

(defn select-program-candidate-from-pareto-front
  "Select a candidate from the Pareto front for mutation.

   EXACT match to Python's select_program_candidate_from_pareto_front function.

   1. Remove dominated programs from the front
   2. Count frequency of each program in the filtered front
   3. Sample weighted by frequency (more instances = more likely)

   Arguments:
   - pareto-front-mapping: Map of {instance-id -> #{program-ids}}
   - agg-scores: Sequence of aggregate scores indexed by program-id
   - rng: Random number generator (java.util.Random or function)

   Returns:
   Selected program index."
  [pareto-front-mapping agg-scores rng]
  (let [
        filtered-mapping (remove-dominated-programs pareto-front-mapping agg-scores)

        freq (reduce
               (fn [f front]
                 (reduce
                   (fn [f' prog-idx]
                     (update f' prog-idx (fnil inc 0)))
                   f
                   front))
               {}
               (vals filtered-mapping))

        sampling-list (mapcat
                        (fn [[prog-idx frequency]]
                          (repeat frequency prog-idx))
                        freq)]

    (u/log :ai.obney.orc.gepa.core.pareto/select-from-pareto-front
           :num-candidates (count freq)
           :sampling-list-size (count sampling-list))

    (when (empty? sampling-list)
      (throw (ex-info "No Pareto programs survived filtering"
                      {:filtered-mapping filtered-mapping})))

    (let [idx (if (fn? rng)
                (rng (count sampling-list))
                (.nextInt ^java.util.Random rng (count sampling-list)))]
      (nth (vec sampling-list) idx))))

(defn select-component-round-robin
  "Select which component to mutate using round-robin.

   EXACT match to Python's RoundRobinReflectionComponentSelector.

   Each candidate maintains its own 'next predictor to update' index.
   This ensures all components get mutated over time.

   Arguments:
   - candidate-idx: Index of the candidate being mutated
   - component-names: Vector of component names
   - round-robin-state: Map of {candidate-idx -> next-predictor-idx}

   Returns:
   {:component selected-component-name
    :new-state updated-round-robin-state}"
  [candidate-idx component-names round-robin-state]
  (let [pid (get round-robin-state candidate-idx 0)
        num-components (count component-names)
        next-pid (mod (inc pid) num-components)
        selected (nth component-names pid)]
    {:component selected
     :new-state (assoc round-robin-state candidate-idx next-pid)}))

(defn idxmax
  "Return the index of the maximum value in a sequence.

   EXACT match to Python's idxmax function."
  [scores]
  (let [indexed (map-indexed vector scores)
        max-val (apply max scores)]
    (first (first (filter #(= (second %) max-val) indexed)))))

(defn does-triplet-have-desirable-predictors?
  "Check if a triplet (ancestor, id1, id2) has predictors worth merging.

   EXACT match to Python's does_triplet_have_desirable_predictors.

   Returns true if there exists at least one predictor where:
   - One descendant has the same instruction as ancestor
   - The other descendant has a different instruction
   - This indicates independent improvements on different predictors

   Arguments:
   - program-candidates: Sequence of candidate maps {predictor-name -> instruction}
   - ancestor: Index of the ancestor candidate
   - id1: Index of first descendant
   - id2: Index of second descendant

   Returns true if the triplet is desirable for merging."
  [program-candidates ancestor id1 id2]
  (let [pred-names (keys (nth program-candidates ancestor))
        found-predictors
        (for [pred-name pred-names
              :let [pred-anc (get (nth program-candidates ancestor) pred-name)
                    pred-id1 (get (nth program-candidates id1) pred-name)
                    pred-id2 (get (nth program-candidates id2) pred-name)]
              :when (and (or (= pred-anc pred-id1)
                             (= pred-anc pred-id2))
                         (not= pred-id1 pred-id2))]
          pred-name)]
    (pos? (count found-predictors))))

(defn filter-ancestors
  "Filter common ancestors to find valid merge candidates.

   EXACT match to Python's filter_ancestors.

   Filters out ancestors that:
   - Have already been used in a merge with this pair
   - Have higher score than either descendant (ancestor should be worse)
   - Don't have desirable predictor patterns

   Arguments:
   - i, j: The descendant candidate indices
   - common-ancestors: Set of potential ancestor indices
   - merges-performed: {:ancestor-logs #{[i j ancestor] ...}}
   - agg-scores: Sequence of aggregate scores
   - program-candidates: Sequence of candidate instruction maps

   Returns sequence of valid ancestor indices."
  [i j common-ancestors merges-performed agg-scores program-candidates]
  (->> common-ancestors
       (filter
         (fn [ancestor]
           (and
             (not (contains? (:ancestor-logs merges-performed) [i j ancestor]))

             (<= (nth agg-scores ancestor) (nth agg-scores i))
             (<= (nth agg-scores ancestor) (nth agg-scores j))

             (does-triplet-have-desirable-predictors?
               program-candidates ancestor i j))))
       vec))

(defn get-ancestors
  "Get all ancestors of a node by traversing parent links.

   Arguments:
   - node: Index of the node
   - parent-list: Sequence of [parent-idx ...] for each node
   - ancestors-found: Accumulated set of ancestors

   Returns set of all ancestor indices."
  [node parent-list ancestors-found]
  (reduce
    (fn [found parent]
      (if (or (nil? parent)
              (contains? found parent))
        found
        (get-ancestors parent parent-list (conj found parent))))
    ancestors-found
    (nth parent-list node [])))

(defn find-common-ancestor-pair
  "Find a pair of candidates with a common ancestor suitable for merging.

   EXACT match to Python's find_common_ancestor_pair.

   Arguments:
   - rng: Random number generator
   - parent-list: Sequence of parent indices for each candidate
   - program-indexes: Candidates to consider
   - merges-performed: Previously performed merges
   - agg-scores: Aggregate scores for weighting
   - program-candidates: Candidate instruction maps
   - max-attempts: Maximum sampling attempts

   Returns [id1 id2 ancestor] or nil."
  [rng parent-list program-indexes merges-performed agg-scores program-candidates
   & {:keys [max-attempts] :or {max-attempts 10}}]

  (loop [attempt 0]
    (when (< attempt max-attempts)
      (if (< (count program-indexes) 2)
        nil

        (let [
              indexes (vec program-indexes)
              sample-idx (fn [] (nth indexes (rng (count indexes))))
              i (sample-idx)
              j (loop [j' (sample-idx)]
                  (if (= j' i)
                    (recur (sample-idx))
                    j'))

              [i j] (if (< j i) [j i] [i j])

              ancestors-i (get-ancestors i parent-list #{})
              ancestors-j (get-ancestors j parent-list #{})]

          (if (or (contains? ancestors-i j)
                  (contains? ancestors-j i))
            (recur (inc attempt))

            (let [common-ancestors (set/intersection ancestors-i ancestors-j)
                  filtered (filter-ancestors i j common-ancestors
                                             merges-performed agg-scores
                                             program-candidates)]
              (if (empty? filtered)
                (recur (inc attempt))

                (let [weights (map #(nth agg-scores %) filtered)
                      total-weight (reduce + weights)
                      r (* (rng) total-weight)]
                  (loop [remaining (map vector filtered weights)
                         cumulative 0.0]
                    (if-let [[anc w] (first remaining)]
                      (let [new-cum (+ cumulative w)]
                        (if (< r new-cum)
                          [i j anc]
                          (recur (rest remaining) new-cum)))
                      [i j (first filtered)])))))))))))

(defn sample-and-attempt-merge
  "Sample candidates and attempt to merge them using common ancestor logic.

   EXACT match to Python's sample_and_attempt_merge_programs_by_common_predictors.

   Arguments:
   - agg-scores: Aggregate scores for all candidates
   - rng: Random number generator (fn that returns double in [0,1) or int in [0,n))
   - merge-candidates: Indices of candidates to consider
   - merges-performed: {:ancestor-logs #{...} :merge-descs #{...}}
   - program-candidates: Sequence of candidate instruction maps
   - parent-list: Parent indices for each candidate
   - has-val-support-overlap?: Optional fn (id1 id2) -> bool
   - max-attempts: Max attempts to find mergeable pair

   Returns {:candidate new-instructions, :id1 i, :id2 j, :ancestor anc} or nil."
  [agg-scores rng merge-candidates merges-performed program-candidates parent-list
   & {:keys [has-val-support-overlap? max-attempts]
      :or {max-attempts 10}}]

  (when (and (>= (count merge-candidates) 2)
             (>= (count parent-list) 3))

    (loop [attempt 0]
      (when (< attempt max-attempts)
        (if-let [[id1 id2 ancestor]
                 (find-common-ancestor-pair
                   rng parent-list merge-candidates
                   merges-performed agg-scores program-candidates
                   :max-attempts max-attempts)]

          (if (contains? (:ancestor-logs merges-performed) [id1 id2 ancestor])
            (recur (inc attempt))

            (do
              (assert (<= (nth agg-scores ancestor) (nth agg-scores id1))
                      "Ancestor should not be better than descendant 1")
              (assert (<= (nth agg-scores ancestor) (nth agg-scores id2))
                      "Ancestor should not be better than descendant 2")
              (assert (not= id1 id2) "Cannot merge same program")

              (let [pred-names (keys (nth program-candidates ancestor))
                    {:keys [new-program merge-desc]}
                    (reduce
                      (fn [{:keys [new-program merge-desc]} pred-name]
                        (let [pred-anc (get (nth program-candidates ancestor) pred-name)
                              pred-id1 (get (nth program-candidates id1) pred-name)
                              pred-id2 (get (nth program-candidates id2) pred-name)]
                          (cond
                            (and (or (= pred-anc pred-id1)
                                     (= pred-anc pred-id2))
                                 (not= pred-id1 pred-id2))
                            (let [same-as-ancestor? (= pred-anc pred-id1)
                                  new-value-idx (if same-as-ancestor? id2 id1)]
                              {:new-program (assoc new-program pred-name
                                              (get (nth program-candidates new-value-idx) pred-name))
                               :merge-desc (conj merge-desc new-value-idx)})

                            (and (not= pred-anc pred-id1)
                                 (not= pred-anc pred-id2))
                            (let [
                                  source (cond
                                           (> (nth agg-scores id1) (nth agg-scores id2)) id1
                                           (> (nth agg-scores id2) (nth agg-scores id1)) id2
                                           :else (if (< (rng) 0.5) id1 id2))]
                              {:new-program (assoc new-program pred-name
                                              (get (nth program-candidates source) pred-name))
                               :merge-desc (conj merge-desc source)})

                            :else
                            {:new-program (assoc new-program pred-name
                                            (get (nth program-candidates id1) pred-name))
                             :merge-desc (conj merge-desc id1)})))
                      {:new-program {}
                       :merge-desc []}
                      pred-names)]

                (if (contains? (:merge-descs merges-performed) [id1 id2 merge-desc])
                  (recur (inc attempt))

                  (if (and has-val-support-overlap?
                           (not (has-val-support-overlap? id1 id2)))
                    (recur (inc attempt))

                    {:candidate new-program
                     :id1 id1
                     :id2 id2
                     :ancestor ancestor
                     :merge-desc merge-desc})))))

          (recur (inc attempt)))))))

;; =============================================================================
;; Merge Acceptance Check
;; =============================================================================

(defn should-accept-merge?
  "Check if merged candidate should be accepted.

   EXACT match to Python's acceptance criterion: >= max(parents).

   Arguments:
   - new-scores: Vector of scores for merged candidate
   - id1-scores: Vector of scores for parent 1
   - id2-scores: Vector of scores for parent 2

   Returns true if merge should be accepted."
  [new-scores id1-scores id2-scores]
  (let [new-sum (reduce + new-scores)
        parent-max (max (reduce + id1-scores)
                        (reduce + id2-scores))]
    (>= new-sum parent-max)))

;; =============================================================================
;; Merge State Management
;; =============================================================================

(defn empty-merge-state
  "Create empty merge tracking state."
  []
  {:ancestor-logs #{}
   :merge-descs #{}})

;; =============================================================================
;; ORC Workflow Definition - Single LLM Node
;; =============================================================================

(def instruction-proposer-workflow
  "ORC workflow for GEPA instruction proposal.

   This is a single-LLM-node workflow that matches Python GEPA exactly.
   The prompt template is injected at execution time via the instruction.

   Inputs:
   - current-instruction: The instruction being optimized
   - reflective-examples: Markdown-formatted examples with feedback

   Outputs:
   - proposed-instruction: New instruction within ``` blocks"
  (sheet/workflow "gepa-instruction-proposer"
    (sheet/blackboard
      {:current-instruction [:string {:description "Current instruction to improve"}]
       :reflective-examples [:string {:description "Markdown-formatted examples with Inputs, Generated Outputs, and Feedback"}]
       :proposed-instruction [:string {:description "New instruction within ``` blocks"}]})

    (sheet/llm "propose"
      :model "anthropic/claude-sonnet-4"
      :instruction python-gepa-prompt-template
      :reads [:current-instruction :reflective-examples]
      :writes [:proposed-instruction])))

;; =============================================================================
;; Workflow Builder
;; =============================================================================

(defn build-proposer-workflow!
  "Build the instruction proposer workflow.

   Returns the sheet-id of the built workflow."
  [context]
  (u/log :ai.obney.orc.gepa.core.proposer/building-proposer-workflow)
  (sheet/build-workflow! context instruction-proposer-workflow))

(defn due? [state now]
  (and (not (:terminal? state))
       (some (fn [[_ {:keys [boundary tasks]}]]
               (when-not (contains? (:completed-boundaries state) boundary)
                 (let [current (keep #(get-in state [:tasks (:call-id %)]) tasks)
                       healthy? (some #(and (not= :completed (:status %))
                                            (:lease-expires-at %)
                                            (.isAfter (Instant/parse (:lease-expires-at %)) now))
                                      current)]
                   (not healthy?))))
             (:boundaries state))))

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

(defn invalid-reflection-template
  "The reason a reflection prompt template is unusable, or nil. Every template,
   whether given for all components or for one, must carry both placeholders; a
   bad one is refused before any evaluation."
  [template]
  (let [templates (cond (nil? template) []
                        (string? template) [template]
                        (map? template) (vals template)
                        :else [:ai.obney.orc.gepa.core.commands/invalid])]
    (some (fn [t]
            (try (if (string? t)
                   (do (validate-prompt-template t) nil)
                   "Reflection prompt template must be a string or a map of component name to string")
                 (catch Exception e (.getMessage e))))
          templates)))

(defn same-admitted-boundaries? [events boundary expected]
  (= expected
     (into #{} (keep (fn [event]
                       (when (and (= :gepa/task-boundary-opened (:event/type event))
                                  (= boundary (:boundary event)))
                         (:boundary-id event)))) events)))

(defn managed-call? [events call-id]
  (some #(and (= :gepa/task-boundary-opened (:event/type %))
              (some (fn [task] (= call-id (:call-id task))) (:tasks %)))
        events))

;; =============================================================================
;; Complete Optimization
;; =============================================================================

(defn ready-unpublished-comparison? [state config]
  (some (fn [[boundary entries]]
          (let [manifest (mapcat :tasks entries)
                tasks (map #(get-in state [:tasks (:call-id %)]) manifest)
                roles (group-by :role tasks)
                parents (:subsample-parent roles)
                proposals (:subsample-proposed roles)
                ready? (case (:kind boundary)
                         :candidate true
                         :subsample
                         (or (and (seq parents) (seq proposals)
                                  (= (frequencies (map :example-index parents))
                                     (frequencies (map :example-index proposals))))
                             (and (seq parents) (empty? proposals)
                                  (get config :skip-perfect-score true)
                                  (every? #(= 1.0 (get-in % [:result :score])) parents)))
                         false)]
            (and ready?
                 (not (contains? (:completed-boundaries state) boundary))
                 (seq manifest)
                 (every? #(= :completed (:status %)) tasks))))
        (group-by :boundary (vals (:boundaries state)))))

(def event-types
  #{:gepa/optimization-started :gepa/optimization-completed :gepa/optimization-failed
    :gepa/candidate-evaluated :gepa/subsample-evaluated
    :gepa/task-boundary-opened :gepa/task-claimed :gepa/task-lease-renewed
    :gepa/task-attempt-dispatched :gepa/task-workflow-completed :gepa/task-metric-started
    :gepa/task-finished :gepa/task-call-completed :gepa/task-recovery-required
    :gepa/task-adopted :gepa/task-adoption-blocked})

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

(defn record-merge-attempt
  "Record a merge attempt to prevent duplicates."
  [state id1 id2 ancestor merge-desc]
  (-> state
      (update :ancestor-logs conj [id1 id2 ancestor])
      (update :merge-descs conj [id1 id2 merge-desc])))

;; =============================================================================
;; Subsample Selection for Merged Candidates
;; =============================================================================

(defn select-merge-eval-subsample
  "Select evaluation subsample that tests both parent's strengths.

   EXACT match to Python's select_eval_subsample_for_merged_program.

   Selects instances where:
   - id1 beats id2 (p1)
   - id2 beats id1 (p2)
   - They tie (p3)

   Arguments:
   - scores1: Map of {instance-id -> score} for candidate 1
   - scores2: Map of {instance-id -> score} for candidate 2
   - num-samples: Target number of samples (default 5)
   - rng: Random function

   Returns vector of instance-ids."
  [scores1 scores2 rng & {:keys [num-samples] :or {num-samples 5}}]
  (let [common-ids (set/intersection (set (keys scores1)) (set (keys scores2)))
        common-ids-vec (vec common-ids)

        p1 (filterv #(> (get scores1 %) (get scores2 %)) common-ids-vec)
        p2 (filterv #(> (get scores2 %) (get scores1 %)) common-ids-vec)
        p3 (filterv #(and (not (contains? (set p1) %))
                          (not (contains? (set p2) %)))
                    common-ids-vec)

        n-each (max 1 (int (Math/ceil (/ num-samples 3))))

        sample-from-bucket
        (fn [bucket n already-selected]
          (let [available (remove #(contains? already-selected %) bucket)
                take-n (min (count available) n)]
            (if (zero? take-n)
              []
              (loop [selected []
                     remaining (vec available)]
                (if (or (>= (count selected) take-n)
                        (empty? remaining))
                  selected
                  (let [idx (rng (count remaining))
                        item (nth remaining idx)]
                    (recur (conj selected item)
                           (into (subvec remaining 0 idx)
                                 (subvec remaining (inc idx))))))))))

        selected1 (sample-from-bucket p1 n-each #{})
        selected2 (sample-from-bucket p2 n-each (set selected1))
        selected3 (sample-from-bucket p3 n-each (set/union (set selected1) (set selected2)))
        selected (into [] cat [selected1 selected2 selected3])

        remaining-count (- num-samples (count selected))
        final-selected
        (if (pos? remaining-count)
          (let [unused (remove #(contains? (set selected) %) common-ids-vec)]
            (if (>= (count unused) remaining-count)
              (into selected (take remaining-count (shuffle unused)))
              (into selected (take remaining-count (cycle common-ids-vec)))))
          selected)]

    (vec (take num-samples final-selected))))

;; =============================================================================
;; MergeProposer-style Interface
;; =============================================================================

(defn propose-merge
  "Propose a merge between candidates.

   This is the main entry point matching Python's MergeProposer.propose.

   Arguments:
   - state: GEPA optimization state with:
     - :program-candidates - Sequence of candidate instruction maps
     - :agg-scores - Aggregate scores
     - :parent-list - Parent indices for each candidate
     - :pareto-front-mapping - {instance-id -> #{candidate-ids}}
     - :val-subscores - {candidate-id -> {instance-id -> score}}
   - merge-state: Current merge tracking state
   - rng: Random function
   - config: {:val-overlap-floor min-overlap-instances}

   Returns:
   {:candidate merged-instructions
    :parent-ids [id1 id2]
    :ancestor ancestor
    :merge-state updated-merge-state
    :subsample-ids ids-to-evaluate}
   or nil if no valid merge found."
  [state merge-state rng config]
  (u/log :ai.obney.orc.gepa.core.merge/propose-merge
         :num-candidates (count (:program-candidates state)))

  (let [{:keys [program-candidates agg-scores parent-list
                pareto-front-mapping val-subscores]} state
        val-overlap-floor (get config :val-overlap-floor 5)

        dominators (find-dominator-programs pareto-front-mapping agg-scores)

        has-val-support-overlap?
        (fn [id1 id2]
          (let [common (set/intersection
                         (set (keys (get val-subscores id1 {})))
                         (set (keys (get val-subscores id2 {}))))]
            (>= (count common) val-overlap-floor)))]

    (when-let [result (sample-and-attempt-merge
                        agg-scores rng dominators
                        merge-state program-candidates parent-list
                        :has-val-support-overlap? has-val-support-overlap?)]
      (let [{:keys [candidate id1 id2 ancestor merge-desc]} result

            scores1 (get val-subscores id1 {})
            scores2 (get val-subscores id2 {})

            subsample-ids (when (and (seq scores1) (seq scores2))
                            (select-merge-eval-subsample scores1 scores2 rng))]

        (when (seq subsample-ids)
          {:candidate candidate
           :parent-ids [id1 id2]
           :ancestor ancestor
           :merge-state (record-merge-attempt merge-state id1 id2 ancestor merge-desc)
           :subsample-ids subsample-ids})))))

(defn uniform-crossover
  "Perform simple uniform crossover (not the Python GEPA algorithm)."
  [instructions1 instructions2]
  (let [all-components (set (concat (keys instructions1) (keys instructions2)))]
    (reduce
      (fn [merged component]
        (let [inst1 (get instructions1 component)
              inst2 (get instructions2 component)
              selected (cond
                         (and inst1 inst2) (if (< (rand) 0.5) inst1 inst2)
                         inst1 inst1
                         inst2 inst2
                         :else "")]
          (assoc merged component selected)))
      {}
      all-components)))

(defn weighted-crossover
  "Perform weighted crossover based on scores (not the Python GEPA algorithm)."
  [instructions1 score1 instructions2 score2]
  (let [total-score (+ score1 score2)
        prob1 (if (pos? total-score) (/ score1 total-score) 0.5)
        all-components (set (concat (keys instructions1) (keys instructions2)))]
    (reduce
      (fn [merged component]
        (let [inst1 (get instructions1 component)
              inst2 (get instructions2 component)
              selected (cond
                         (and inst1 inst2) (if (< (rand) prob1) inst1 inst2)
                         inst1 inst1
                         inst2 inst2
                         :else "")]
          (assoc merged component selected)))
      {}
      all-components)))

(defn unregister!
  "Remove a process-local binding without changing its durable descriptor."
  [context descriptor]
  (swap! registrations dissoc (registration-key context descriptor))
  nil)

(defn compute-pareto-front-mapping
  "Compute the Pareto front mapping from candidates.

   This creates the data structure used by selection:
   {instance-idx -> #{candidate-ids that are best at this instance}}

   Arguments:
   - candidates: Sequence of {:candidate-id id, :scores [s1 s2 ...]}

   Returns:
   Map of {instance-idx -> #{candidate-ids}}"
  [candidates]
  (when (seq candidates)
    (let [num-instances (count (:scores (first candidates)))]
      (reduce
        (fn [mapping idx]
          (let [best-score (->> candidates
                                (map #(nth (:scores %) idx nil))
                                (remove nil?)
                                (apply max Double/NEGATIVE_INFINITY))
                best-ids (->> candidates
                              (filter #(= (nth (:scores %) idx nil) best-score))
                              (map :candidate-id)
                              set)]
            (assoc mapping idx best-ids)))
        {}
        (range num-instances)))))

(defn update-pareto-front-mapping
  "Update the Pareto front mapping with a new candidate's scores.

   Arguments:
   - mapping: Current {instance-idx -> #{candidate-ids}}
   - max-scores: Current {instance-idx -> max-score}
   - candidate-id: ID of new candidate
   - scores: Vector of scores for new candidate

   Returns:
   {:mapping updated-mapping, :max-scores updated-max-scores}"
  [mapping max-scores candidate-id scores]
  (reduce
    (fn [{:keys [mapping max-scores]} idx]
      (let [score (nth scores idx)
            current-max (get max-scores idx Double/NEGATIVE_INFINITY)]
        (cond
          (> score current-max)
          {:mapping (assoc mapping idx #{candidate-id})
           :max-scores (assoc max-scores idx score)}

          (= score current-max)
          {:mapping (update mapping idx (fnil conj #{}) candidate-id)
           :max-scores max-scores}

          :else
          {:mapping mapping :max-scores max-scores})))
    {:mapping mapping :max-scores max-scores}
    (range (count scores))))

(defn select-all-components
  "Select all components for mutation.

   EXACT match to Python's AllReflectionComponentSelector."
  [candidate]
  (vec (keys candidate)))

(defn select-candidate-pareto
  "Select a candidate using Pareto-based selection.

   EXACT match to Python's ParetoCandidateSelector."
  [pareto-front-mapping agg-scores rng]
  (select-program-candidate-from-pareto-front
    pareto-front-mapping agg-scores rng))

(defn select-candidate-best
  "Select the current best candidate.

   EXACT match to Python's CurrentBestCandidateSelector."
  [agg-scores]
  (idxmax agg-scores))

(defn select-candidate-epsilon-greedy
  "Select candidate using epsilon-greedy strategy.

   EXACT match to Python's EpsilonGreedyCandidateSelector.

   With probability epsilon, select randomly.
   Otherwise, select the best."
  [agg-scores epsilon rng]
  (let [r (if (fn? rng)
            (rng)
            (.nextDouble ^java.util.Random rng))]
    (if (< r epsilon)
      (let [n (count agg-scores)
            idx (if (fn? rng)
                  (rng n)
                  (.nextInt ^java.util.Random rng n))]
        idx)
      (idxmax agg-scores))))

;; =============================================================================
;; Legacy Compatibility Functions
;; =============================================================================

(defn dominates?
  "Check if candidate A dominates candidate B.

   A dominates B if:
   - A is at least as good as B on ALL instances
   - A is strictly better than B on AT LEAST ONE instance

   Returns true if A dominates B."
  [scores-a scores-b]
  (let [pairs (map vector scores-a scores-b)
        at-least-as-good? (every? (fn [[a b]] (>= a b)) pairs)
        strictly-better? (some (fn [[a b]] (> a b)) pairs)]
    (and at-least-as-good? strictly-better?)))

(defn instances-best-at
  "Get which instances a candidate is best at.

   Returns vector of instance indices where this candidate
   achieves the maximum score."
  [candidate-id pareto-front-mapping]
  (->> pareto-front-mapping
       (filter (fn [[_idx best-ids]]
                 (contains? best-ids candidate-id)))
       (map first)
       vec))

;; =============================================================================
;; Diversity Metrics
;; =============================================================================

(defn frontier-diversity
  "Compute diversity metric for the Pareto frontier.

   Returns the number of unique instance winners - higher is better."
  [pareto-front-mapping]
  (->> (vals pareto-front-mapping)
       (mapcat identity)
       set
       count))

(defn instruction-similarity
  "Compute similarity between two instruction sets using Jaccard similarity."
  [instructions-a instructions-b]
  (let [tokens-a (set (mapcat #(clojure.string/split % #"\s+")
                              (vals instructions-a)))
        tokens-b (set (mapcat #(clojure.string/split % #"\s+")
                              (vals instructions-b)))
        intersection (count (set/intersection tokens-a tokens-b))
        union (count (set/union tokens-a tokens-b))]
    (if (zero? union)
      1.0
      (/ intersection union))))

;; =============================================================================
;; Selection Statistics
;; =============================================================================

(defn selection-statistics
  "Compute statistics about the current selection state."
  [candidates pareto-front-mapping agg-scores]
  (let [dominator-ids (set (find-dominator-programs pareto-front-mapping agg-scores))
        dominator-scores (map #(nth agg-scores %) dominator-ids)]
    {:frontier-size (count dominator-ids)
     :diversity-score (frontier-diversity pareto-front-mapping)
     :num-instances (count pareto-front-mapping)
     :best-aggregate-score (when (seq dominator-scores) (apply max dominator-scores))
     :avg-aggregate-score (when (seq dominator-scores)
                            (/ (reduce + dominator-scores) (count dominator-scores)))}))

;; =============================================================================
;; Batch Proposal for Multiple Components
;; =============================================================================

(defn propose-for-components
  "Propose new instructions for multiple components.

   This matches Python GEPA's behavior where each component (predictor)
   can have its own custom prompt template.

   Arguments:
   - llm-fn: Function that takes a prompt and returns LLM response
   - candidate: Map of component-name → current-instruction
   - reflective-dataset: Map of component-name → formatted-examples
   - components-to-update: Vector of component names to mutate
   - component-templates: Optional map of component-name → prompt-template

   Returns:
   Map of component-name → proposed-instruction"
  [llm-fn candidate reflective-dataset components-to-update
   & {:keys [component-templates]}]
  (reduce
    (fn [new-texts component-name]
      (let [current-instruction (get candidate component-name)
            formatted-examples (get reflective-dataset component-name)]
        (if (or (nil? formatted-examples)
                (str/blank? formatted-examples))
          (do
            (u/log :ai.obney.orc.gepa.core.proposer/skipping-component
                   :component component-name
                   :reason "no-reflective-data")
            new-texts)
          (let [template (get component-templates component-name)
                result (propose-new-instruction llm-fn
                                                current-instruction
                                                formatted-examples
                                                :prompt-template template)]
            (assoc new-texts component-name (:proposed-instruction result))))))
    {}
    components-to-update))

(defn propose-mutation
  "Execute the instruction proposer via ORC workflow.

   Arguments:
   - context: Grain context with event-store, etc.
   - sheet-id: The proposer workflow sheet ID
   - current-instruction: The instruction to improve
   - formatted-examples: Pre-formatted reflective examples

   Returns:
   {:status :success/:failure
    :proposed-instruction \"extracted instruction\"
    :raw-response \"full response\"}"
  [context sheet-id current-instruction formatted-examples]
  (u/log :ai.obney.orc.gepa.core.proposer/proposing-mutation-via-orc
         :sheet-id sheet-id)

  (let [result (sheet/execute context sheet-id
                 {:current-instruction current-instruction
                  :reflective-examples formatted-examples})]

    (if (= :success (:status result))
      (let [raw-response (get-in result [:outputs :proposed-instruction])
            extracted (extract-instruction-from-response raw-response)]
        {:status :success
         :proposed-instruction extracted
         :raw-response raw-response})

      {:status :failure
       :error (:error result)})))

(def population-event-types
  "Events that affect the population read model."
  #{:gepa/optimization-started
    :gepa/candidate-created
    :gepa/candidate-evaluation-started
    :gepa/candidate-evaluated})

(def pareto-frontier-event-types
  "Events that affect the Pareto frontier read model."
  #{:gepa/optimization-started
    :gepa/candidate-evaluated
    :gepa/frontier-updated})

(def optimization-state-event-types
  "Events that affect the optimization state read model."
  #{:gepa/optimization-started
    :gepa/metric-selected
    :gepa/candidate-created
    :gepa/candidate-evaluated
    :gepa/subsample-evaluated
    :gepa/optimization-completed
    :gepa/optimization-failed})

(def datasets-event-types
  "Events that affect the datasets read model."
  #{:gepa/datasets-stored})

(def optimization-list-event-types
  "Events that affect the optimization list read model."
  #{:gepa/optimization-started
    :gepa/optimization-completed
    :gepa/optimization-failed})

(defn gepa-state
  "Projects events into full GEPA state matching Python's GEPAState structure.

   Python GEPAState fields mapped:
   - program_candidates -> :program-candidates (vec of instruction maps)
   - program_full_scores_val_set -> :agg-scores (vec of doubles)
   - prog_candidate_val_subscores -> :val-subscores ({cand-id -> {instance-id -> score}})
   - parent_program_for_candidate -> :parent-list (vec of [parent-ids])
   - named_predictor_id_to_update_next_for_program_candidate -> :round-robin-state
   - list_of_named_predictors -> :predictor-names (vec of strings)

   Usage:
   (let [events (into [] (es/read event-store {:tags #{[:optimization opt-id]}}))
         state (gepa-state {} events)]
     (:program-candidates state))"
  [state events]
  (reduce
    (fn [s event]
      (let [type (:event/type event)]
        (case type
          :gepa/optimization-started
          (-> s
              (assoc :optimization-id (:optimization-id event))
              (assoc :sheet-id (:sheet-id event))
              (assoc :config (:config event))
              (assoc :trainset-size (:trainset-size event))
              (assoc :valset-size (:valset-size event))
              (assoc :status :running)
              (assoc :started-at (str (:started-at event)))
              (assoc :program-candidates [])
              (assoc :agg-scores [])
              (assoc :val-subscores {})
              (assoc :parent-list [])
              (assoc :round-robin-state {})
              (assoc :predictor-names nil)
              (assoc :candidate-id->idx {})
              (assoc :pareto-front-mapping {})
              (assoc :max-scores {})
              (assoc :total-metric-calls 0)
              (assoc :iteration 0)
              (assoc :evaluation-cache {})
              (assoc :merge-state {:ancestor-logs #{} :merge-descs #{}}))

          :gepa/datasets-stored
          (-> s
              (assoc :trainset (:trainset event))
              (assoc :valset (:valset event)))

          :gepa/candidate-created
          (let [{:keys [candidate-id instructions parent-ids generation]} event
              idx (count (:program-candidates s))
              pred-names (or (:predictor-names s) (vec (sort (keys instructions))))
              parent-indices (mapv (fn [pid]
                                     (get (:candidate-id->idx s) pid))
                                   parent-ids)
              inherited-pointer (reduce max 0 (keep #(get (:round-robin-state s) %)
                                                    parent-indices))]
          (-> s
              (update :program-candidates conj instructions)
              (update :agg-scores conj nil)
              (update :parent-list conj parent-indices)
              (update :round-robin-state assoc idx inherited-pointer)
              (assoc :predictor-names pred-names)
              (assoc-in [:candidate-id->idx candidate-id] idx)
              (assoc-in [:candidate-details idx]
                        {:candidate-id candidate-id
                         :generation generation
                         :mutation-reason (:mutation-reason event)
                         :source-optimization-id (:source-optimization-id event)})))

          :gepa/candidate-evaluated
          (let [{:keys [candidate-id scores aggregate-score metric-calls]} event
              idx (get (:candidate-id->idx s) candidate-id)]
          (when idx
            (-> s
                (assoc-in [:agg-scores idx] aggregate-score)
                (update :total-metric-calls + (or metric-calls 0))
                (assoc-in [:val-subscores idx]
                          (into {} (map-indexed (fn [i score] [i score]) scores)))
                (as-> s'
                      (reduce-kv
                        (fn [acc instance-idx score]
                          (let [current-max (get-in acc [:max-scores instance-idx]
                                                    Double/NEGATIVE_INFINITY)]
                            (cond
                              (> score current-max)
                              (-> acc
                                  (assoc-in [:max-scores instance-idx] score)
                                  (assoc-in [:pareto-front-mapping instance-idx] #{idx}))

                              (== score current-max)
                              (update-in acc [:pareto-front-mapping instance-idx]
                                         (fnil conj #{}) idx)

                              :else acc)))
                        s'
                        (zipmap (range) scores))))))

        :gepa/proposal-ready
          (let [idx (get (:candidate-id->idx s) (:parent-id event))
                n (count (:predictor-names s))]
            (if (and idx (pos? n))
              (update-in s [:round-robin-state idx] #(mod (inc (or % 0)) n))
              s))

        :gepa/example-evaluated
          (let [{:keys [candidate-id example-id score]} event
                idx (get (:candidate-id->idx s) candidate-id)]
            (when idx
              (assoc-in s [:val-subscores idx example-id] score)))

          :gepa/reflective-dataset-built
          (let [{:keys [candidate-id components dataset]} event
                idx (get (:candidate-id->idx s) candidate-id)]
            (when idx
              (assoc-in s [:reflective-datasets idx] {:components components
                                                      :dataset dataset})))

          :gepa/candidate-acceptance-decision
          (let [{:keys [candidate-id accepted? subsample-scores-before subsample-scores-after]} event
                idx (get (:candidate-id->idx s) candidate-id)]
            (when idx
              (assoc-in s [:acceptance-decisions idx]
                        {:accepted? accepted?
                         :before subsample-scores-before
                         :after subsample-scores-after})))

          :gepa/subsample-evaluation-started
          (update s :iteration inc)

          :gepa/subsample-evaluated
          (let [{:keys [parent-id proposed-instructions accepted? metric-calls]} event]
            (-> s
                (update :total-metric-calls + (or metric-calls 0))
                (update :subsample-decisions (fnil conj [])
                        {:parent-id parent-id
                         :accepted? accepted?
                         :proposed-instructions proposed-instructions})))

          :gepa/merge-attempted
          (let [{:keys [id1 id2 ancestor merge-desc]} event]
            (-> s
                (update-in [:merge-state :ancestor-logs] conj [id1 id2 ancestor])
                (cond-> merge-desc
                  (update-in [:merge-state :merge-descs] conj [id1 id2 merge-desc]))))

          :gepa/optimization-completed
          (assoc s
                 :status :completed
                 :completed-at (str (:completed-at event))
                 :best-candidate-id (:best-candidate-id event)
                 :final-score (:final-score event)
                 :improvement (:improvement event))

          :gepa/optimization-failed
          (assoc s
                 :status :failed
                 :completed-at (str (:failed-at event))
                 :error-message (:error-message event))

          s)))
    state
    events))

(defn population
  "Projects candidate events into population state.
   This is a simpler view focused on candidate data."
  [state events]
  (reduce
    (fn [pop event]
      (let [type (:event/type event)]
        (case type
          :gepa/optimization-started
          (-> pop
              (assoc :optimization-id (:optimization-id event))
              (assoc :sheet-id (:sheet-id event))
              (assoc :config (:config event))
              (assoc :candidates {})
              (assoc :evaluated #{})
              (assoc :total-metric-calls 0))

          :gepa/candidate-created
          (assoc-in pop [:candidates (:candidate-id event)]
                    {:candidate-id (:candidate-id event)
                     :instructions (:instructions event)
                     :parent-ids (:parent-ids event)
                     :generation (:generation event)
                     :mutation-reason (:mutation-reason event)
                     :source-optimization-id (:source-optimization-id event)
                     :historical-val-subscores (:historical-val-subscores event)
                     :created-at (str (:created-at event))
                     :status :pending})

          :gepa/candidate-evaluation-started
          (update-in pop [:candidates (:candidate-id event)]
                     assoc :status :evaluating)

          :gepa/candidate-evaluated
          (let [{:keys [candidate-id scores aggregate-score feedbacks outputs metric-calls]} event]
            (-> pop
                (update-in [:candidates candidate-id] merge
                           {:scores scores
                            :aggregate-score aggregate-score
                            :feedbacks (or feedbacks {})
                            :outputs (or outputs {})
                            :status :evaluated})
                (update :evaluated (fnil conj #{}) candidate-id)
                (update :total-metric-calls + (or metric-calls 0))))

          pop)))
    state
    events))

;; =============================================================================
;; Pareto Frontier Read Model
;; =============================================================================

(defn pareto-frontier
  "Projects evaluated candidates into Pareto frontier state.
   Returns structure matching Python's pareto_front_mapping."
  [state events]
  (reduce
    (fn [frontier event]
      (let [type (:event/type event)]
        (case type
          :gepa/optimization-started
          (-> frontier
              (assoc :optimization-id (:optimization-id event))
              (assoc :max-scores {})
              (assoc :best-at {})
              (assoc :scores-by-candidate {})
              (assoc :frontier-members #{}))

          :gepa/candidate-evaluated
          (let [{:keys [candidate-id scores]} event]
            (let [all-scores (assoc (:scores-by-candidate frontier)
                                    candidate-id (vec scores))
                  dominates? (fn [a b]
                               (and (every? true? (map >= a b))
                                    (some true? (map > a b))))
                  members (into #{}
                                (keep (fn [[id candidate-scores]]
                                        (when-not
                                          (some (fn [[other-id other-scores]]
                                                  (and (not= id other-id)
                                                       (dominates? other-scores
                                                                   candidate-scores)))
                                                all-scores)
                                          id)))
                                all-scores)
                  max-scores (reduce (fn [maxima candidate-scores]
                                       (mapv max maxima candidate-scores))
                                     (repeat (count scores)
                                             Double/NEGATIVE_INFINITY)
                                     (vals all-scores))
                  best-at (into {}
                                (map-indexed
                                  (fn [idx maximum]
                                    [idx (into #{}
                                               (keep (fn [id]
                                                       (when (== maximum
                                                                  (nth (get all-scores id)
                                                                       idx))
                                                         id)))
                                               members)]))
                                max-scores)]
              (assoc frontier
                     :scores-by-candidate all-scores
                     :max-scores (into {} (map-indexed vector max-scores))
                     :best-at best-at
                     :frontier-members members)))

          :gepa/frontier-updated
          frontier

          frontier)))
    state
    events))

;; =============================================================================
;; Optimization State Read Model
;; =============================================================================

(defn optimization-state
  "Projects events into overall optimization state summary."
  [state events]
  (reduce
    (fn [opt event]
      (let [type (:event/type event)]
        (case type
          :gepa/optimization-started
          {:optimization-id (:optimization-id event)
           :sheet-id (:sheet-id event)
           :metric-descriptor (:metric-descriptor event)
           :config (:config event)
           :status :running
           :started-at (str (:started-at event))
           :completed-at nil
           :best-score Double/NEGATIVE_INFINITY
           :best-candidate-id nil
           :total-candidates 0
           :total-metric-calls 0}

          :gepa/metric-selected
          (assoc opt :metric-descriptor (:metric-descriptor event))

          :gepa/candidate-created
          (update opt :total-candidates inc)

          :gepa/candidate-evaluated
          (let [{:keys [candidate-id aggregate-score metric-calls]} event
                new-best? (> aggregate-score (:best-score opt Double/NEGATIVE_INFINITY))]
            (-> opt
                (update :total-metric-calls + (or metric-calls 0))
                (cond->
                  new-best?
                  (assoc :best-score aggregate-score
                         :best-candidate-id candidate-id))))

          :gepa/subsample-evaluated
          (update opt :total-metric-calls (fnil + 0) (or (:metric-calls event) 0))

          :gepa/optimization-completed
          (assoc opt
                 :status :completed
                 :completed-at (str (:completed-at event))
                 :best-candidate-id (:best-candidate-id event)
                 :best-score (:final-score event))

          :gepa/optimization-failed
          (assoc opt
                 :status :failed
                 :completed-at (str (:failed-at event))
                 :error-message (:error-message event))

          opt)))
    state
    events))

;; =============================================================================
;; Datasets Read Model
;; =============================================================================

(defn datasets
  "Projects datasets-stored events into dataset state."
  [state events]
  (reduce
    (fn [s event]
      (let [type (:event/type event)]
        (case type
          :gepa/datasets-stored
          (assoc s
                 :trainset (:trainset event)
                 :valset (:valset event))
          s)))
    state
    events))

;; =============================================================================
;; Optimization List Read Model
;; =============================================================================

(defn optimization-list-reducer
  "Reduces optimization lifecycle events into a map of
   {optimization-id -> {:optimization-id :sheet-id :status :started-at ...}}."
  [state event]
  (case (:event/type event)
    :gepa/optimization-started
    (assoc state (:optimization-id event)
           {:optimization-id (:optimization-id event)
            :sheet-id (:sheet-id event)
            :status :running
            :started-at (str (:started-at event))})

    :gepa/optimization-completed
    (-> state
        (assoc-in [(:optimization-id event) :status] :completed)
        (assoc-in [(:optimization-id event) :completed-at] (str (:completed-at event))))

    :gepa/optimization-failed
    (-> state
        (assoc-in [(:optimization-id event) :status] :failed)
        (assoc-in [(:optimization-id event) :failed-at] (str (:failed-at event))))

    state))

;; =============================================================================
;; Round-Robin State Helpers
;; =============================================================================

(defn get-next-component-to-update
  "Get the next component to update for a candidate using round-robin.
   The rotation is derived from recorded events (see `gepa-state`): it advances
   once for each of the candidate's rounds that reached reflection, and a child
   starts at the furthest rotation among its parents."
  [state candidate-idx]
  (let [predictor-names (:predictor-names state)
        n (count predictor-names)
        round-robin-idx (get (:round-robin-state state) candidate-idx 0)]
    (when (seq predictor-names)
      (nth predictor-names (mod round-robin-idx n)))))

;; =============================================================================
;; Evaluation Cache Helpers
;; =============================================================================

(defn cache-key
  "Generate cache key for evaluation result."
  [candidate-hash example-id]
  [candidate-hash example-id])

(defn get-cached-evaluation
  "Get cached evaluation result if available."
  [state candidate example-id]
  (let [cand-hash (hash candidate)]
    (get-in state [:evaluation-cache (cache-key cand-hash example-id)])))

(defn cache-evaluation
  "Cache an evaluation result."
  [state candidate example-id result]
  (let [cand-hash (hash candidate)]
    (assoc-in state [:evaluation-cache (cache-key cand-hash example-id)] result)))

;; =============================================================================
;; Combined Event Application
;; =============================================================================

(defn apply-events
  "Apply a sequence of events to build combined read model state."
  [events]
  {:gepa-state (gepa-state {} events)
   :population (population {} events)
   :frontier (pareto-frontier {} events)
   :optimization (optimization-state {} events)})

(defn- extract-predictor-inputs
  "Extract inputs from a trace for a specific predictor.

   In ORC, this maps to extracting the inputs that were
   passed to a specific LLM node."
  [trace predictor-name]
  (get-in trace [:nodes predictor-name :inputs] {}))

(defn- extract-predictor-outputs
  "Extract outputs from a trace for a specific predictor."
  [trace predictor-name]
  (get-in trace [:nodes predictor-name :outputs] {}))

(defn make-reflective-dataset
  "Build a reflective dataset from evaluation traces.

   This matches Python's DspyAdapter.make_reflective_dataset method.

   Arguments:
   - candidate: Map of component-name → instruction
   - evaluation-traces: Sequence of trace data from evaluation
   - components-to-update: List of component names to include
   - feedback-map: Map of component-name → feedback-fn

   Returns:
   Map of component-name → list of ReflectiveExample maps"
  [candidate evaluation-traces components-to-update feedback-map]
  (u/log :ai.obney.orc.gepa.core.reflection/building-reflective-dataset
         :components components-to-update
         :num-traces (count evaluation-traces))

  (reduce
    (fn [dataset component-name]
      (let [examples
            (->> evaluation-traces
                 (keep (fn [trace-data]
                         (let [{:keys [inputs outputs expected score raw-response parse-failed?]}
                               (get-in trace-data [:components component-name])]
                           (when inputs
                             (build-reflective-example
                               {:inputs inputs
                                :outputs outputs
                                :raw-response raw-response
                                :parse-failed? parse-failed?
                                :expected expected
                                :feedback-fn (get feedback-map component-name)})))))
                 (vec))]
        (if (seq examples)
          (assoc dataset component-name examples)
          (do
            (u/log :ai.obney.orc.gepa.core.reflection/no-reflective-examples
                   :component component-name)
            dataset))))
    {}
    components-to-update))

;; =============================================================================
;; Default Feedback Functions
;; =============================================================================

(defn simple-comparison-feedback
  "Generate feedback by comparing outputs to expected values.

   This is a simple default feedback function that can be used
   when no custom feedback logic is needed."
  [{:keys [inputs outputs expected]}]
  (cond
    (nil? expected)
    "No expected output provided for comparison."

    (= outputs expected)
    "Output matches expected result."

    :else
    (let [diffs (for [[k v] expected
                      :let [actual (get outputs k)]
                      :when (not= v actual)]
                  (str "- " k ": expected '" v "', got '" actual "'"))]
      (str "Output differs from expected:\n"
           (str/join "\n" diffs)))))

(defn score-based-feedback
  "Generate feedback based on a numeric score.

   Arguments:
   - score: Numeric score (0.0 to 1.0)
   - threshold: Score threshold for success (default 0.8)

   Returns:
   Feedback string"
  [score & {:keys [threshold] :or {threshold 0.8}}]
  (cond
    (>= score threshold)
    (str "Good result with score " (format "%.2f" score) ".")

    (>= score 0.5)
    (str "Partial success with score " (format "%.2f" score)
         ". Consider improving accuracy and completeness.")

    :else
    (str "Poor result with score " (format "%.2f" score)
         ". The output significantly differs from expectations.")))

(defn epoch-shuffled-batch-sampler
  "Epoch-shuffled batch sampling (Python GEPA parity).

   Like k-fold CV / SGD batching:
   - Shuffle training indices at start of each epoch
   - Return sequential minibatches of size `batch-size`
   - Once all examples used, reshuffle for next epoch
   - Ensures ALL training examples get used for reflection

   Args:
     trainset-size: Number of training examples (e.g., 18)
     batch-size: Minibatch size (default 3)
     iteration: Current optimization iteration (0-indexed)
     seed: Random seed for deterministic shuffling (default 42)

   Returns:
     Vector of indices for this iteration's minibatch

   Example with 18 training examples, batch-size 3:
     Epoch 0: [5,2,17,0,8,12,3,15,1,6,14,9,4,11,7,10,16,13]
       Iteration 0: [5, 2, 17]   <- batch 0
       Iteration 1: [0, 8, 12]   <- batch 1
       Iteration 2: [3, 15, 1]   <- batch 2
       Iteration 3: [6, 14, 9]   <- batch 3
       Iteration 4: [4, 11, 7]   <- batch 4
       Iteration 5: [10, 16, 13] <- batch 5 (all 18 examples used!)
     Epoch 1: [reshuffle] -> new random order
       Iteration 6: starts fresh with reshuffled indices"
  ([trainset-size batch-size iteration]
   (epoch-shuffled-batch-sampler trainset-size batch-size iteration 42))

  ([trainset-size batch-size iteration seed]
   {:pre [(pos? trainset-size) (pos? batch-size) (>= iteration 0)]}

   (let [
         batches-per-epoch (int (Math/ceil (/ trainset-size batch-size)))

         epoch (quot iteration batches-per-epoch)

         batch-in-epoch (mod iteration batches-per-epoch)

         rng (java.util.Random. (+ seed (* epoch 1000)))

         indices (vec (range trainset-size))
         shuffled (->> indices
                       (map (fn [idx] [idx (.nextDouble rng)]))
                       (sort-by second)
                       (mapv first))

         start (* batch-in-epoch batch-size)
         end (min (+ start batch-size) trainset-size)
         slice (subvec shuffled start end)]

     (if (< (count slice) batch-size)
       (let [needed (- batch-size (count slice))
             extra (take needed shuffled)]
         (u/log :ai.obney.orc.gepa.core.sampler/padding-last-batch
                :iteration iteration
                :batch-in-epoch batch-in-epoch
                :slice-size (count slice)
                :padding-from-start needed)
         (vec (concat slice extra)))
       slice))))

(defn get-minibatch-for-iteration
  "Get actual training examples for a given iteration.

   Convenience function that applies epoch-shuffled indices to trainset.

   Args:
     trainset: Vector of training examples
     batch-size: Minibatch size
     iteration: Current iteration
     seed: Optional random seed

   Returns:
     Vector of training examples for this minibatch"
  ([trainset batch-size iteration]
   (get-minibatch-for-iteration trainset batch-size iteration 42))

  ([trainset batch-size iteration seed]
   (let [indices (epoch-shuffled-batch-sampler
                   (count trainset) batch-size iteration seed)]
     (mapv #(nth trainset %) indices))))

(defn batches-per-epoch
  "Calculate how many batches fit in one epoch.

   Useful for understanding when examples will start repeating."
  [trainset-size batch-size]
  (int (Math/ceil (/ trainset-size batch-size))))

(defn current-epoch
  "Get which epoch the current iteration is in (0-indexed)."
  [trainset-size batch-size iteration]
  (quot iteration (batches-per-epoch trainset-size batch-size)))

(defn now [context]
  ((or (:gepa/clock-fn context) #(Instant/now))))

(defn healthy? [task instant]
  (and (:lease-expires-at task)
       (.isAfter (Instant/parse (:lease-expires-at task)) instant)))

(defn owner? [task command instant]
  (and task (= (:owner-id task) (:owner-id command))
       (= (:boundary-id task) (:boundary-id command))
       (= (:epoch task) (:epoch command)) (healthy? task instant)))

(defn reply [status & [data]]
  {:command-result/events [] :command/result (assoc (or data {}) :status status)})

(defn event [type optimization-id body]
  (->event {:type type :tags (cond-> #{[:optimization optimization-id]}
                             (:call-id body) (conj [:call (:call-id body)]))
            :body (assoc body :optimization-id optimization-id)}))

(defn append-result [optimization-id previous events result]
  {:command-result/events events
   :command-result/cas
   {:types event-types :tags #{[:optimization optimization-id]}
    :predicate-fn (fn [current]
                    (= (mapv :event/id previous) (mapv :event/id current)))}
   :command/result result})

(defn ambiguous-legacy-task [previous tasks]
  (some (fn [entry]
          (let [matches (filter #(and (= :gepa/task-call-completed (:event/type %))
                                      (= (:call-id entry) (:call-id %))) previous)
                evidence (set (map #(select-keys % [:output :score :feedback :model :usage]) matches))]
            (when (> (count evidence) 1)
              {:call-id (:call-id entry) :reason :ambiguous-legacy-task
               :source-event-ids (mapv :event/id matches)})))
        tasks))

(defn extract-workflow-instructions
  "Extract current instructions from a sheet/workflow.

   Walks the workflow tree and collects LLM node instructions.
   Returns map of {node-name -> instruction-text}.

   Finds every model-instructed node — leaves with an :ai/:llm executor,
   model decisions (:decision executor) and :llm-condition nodes — and
   extracts their instructions for optimization."
  [context sheet-id]
  (u/log :ai.obney.orc.gepa.core.todo-processors/extracting-instructions :sheet-id sheet-id)
  (let [nodes (sheet/get-nodes-for-sheet context sheet-id)
        llm-nodes (filter (fn [node]
                            (or (and (= :leaf (:type node))
                                     (#{:ai :llm :decision} (:executor node)))
                                (= :llm-condition (:type node))))
                          nodes)
        instructions (into {}
                           (for [node llm-nodes
                                 :let [name (or (:name node) (str (:id node)))
                                       instruction (:instruction node)]
                                 :when instruction]
                             [name instruction]))]
    (if (seq instructions)
      instructions
      {"default" "You are a helpful assistant."})))

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
  (u/log :ai.obney.orc.gepa.core.todo-processors/on-mutation-proposed
         :parent-id (:parent-candidate-id event)
         :component (:component-updated event)
         :new-instruction-preview (subs (or (:new-instruction event) "") 0
                                        (min 100 (count (or (:new-instruction event) "")))))
  )

;; =============================================================================
;; On Frontier Updated
;; =============================================================================

(defn on-frontier-updated
  "Handle frontier-updated event.

   Log the frontier update for observability.
   No action needed - the evaluated handler already checked budget."
  [{:keys [event] :as context}]
  (u/log :ai.obney.orc.gepa.core.todo-processors/on-frontier-updated
         :candidate-id (:candidate-id event)
         :instances-best-at (:instances-best-at event)))

;; =============================================================================
;; On Optimization Failed
;; =============================================================================

(defn on-optimization-failed
  "Handle optimization-failed event.

   Log the failure and deliver result to waiting callers."
  [{:keys [event] :as context}]
  (let [{:keys [optimization-id error-message]} event]
    (u/log :ai.obney.orc.gepa.core.todo-processors/on-optimization-failed
           :optimization-id optimization-id
           :error-message error-message)

    (deliver-completion!
      optimization-id
      {:optimization-id optimization-id
       :status :failed
       :error error-message})))
