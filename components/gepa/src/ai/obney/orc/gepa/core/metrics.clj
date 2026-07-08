(ns ai.obney.orc.gepa.core.metrics
  "Metric functions for GEPA candidate evaluation.

   Two families:
   - Pure structural metrics (exact-match / contains) — compare expected vs
     actual and return a score in [0,1].
   - The judge metric — runs the orc tier-1 evaluation judges (grounding /
     instruction-following / reasoning / completeness) on the candidate's
     execution trace and returns BOTH a weighted [0,1] score AND an actionable,
     weakest-dimension-first feedback string. GEPA threads that feedback into
     the reflective dataset so the proposer sees WHY a candidate scored low —
     not just the number."
  (:require [clojure.string :as string]
            [ai.obney.orc.evaluation.interface :as judges]
            [com.brunobonacci.mulog :as u]))

(defn make-exact-match-metric
  "Create a metric function that checks for exact match on a key.

   Usage:
   (make-exact-match-metric \"answer\")
   ;; Returns 1.0 if output[\"answer\"] == expected[\"answer\"], else 0.0"
  [output-key]
  (fn [expected actual]
    (let [exp-val (get expected output-key)
          act-val (get actual output-key)]
      (if (and exp-val act-val (= (str exp-val) (str act-val)))
        1.0
        0.0))))

(defn make-contains-metric
  "Create a metric function that checks if output contains expected.

   Usage:
   (make-contains-metric \"answer\")
   ;; Returns 1.0 if expected is substring of output, else 0.0"
  [output-key]
  (fn [expected actual]
    (let [exp-val (str (get expected output-key ""))
          act-val (str (get actual output-key ""))]
      (if (and (seq exp-val)
               (seq act-val)
               (.contains (.toLowerCase act-val) (.toLowerCase exp-val)))
        1.0
        0.0))))

;; =============================================================================
;; Judge Metric — weighted tier-1 judges with rich feedback
;; =============================================================================

(def default-judge-weights
  "Default tier-1 weights when :judges is passed as a bare set/`true` rather
   than an explicit weight map."
  {:grounding 0.30
   :instruction-following 0.25
   :reasoning 0.20
   :completeness 0.25})

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

;; Maps each tier-1 dimension to its judge fn and the key its result is under.
;; (Each judge fn takes {:inputs {:trace-data ..}} and returns
;;  {:<result-key> {:score :feedback :reasoning ..}}.)
(def ^:private dimension->judge
  {:grounding             {:fn judges/grounding-judge             :result-key :grounding-result}
   :instruction-following {:fn judges/instruction-following-judge :result-key :instruction-result}
   :reasoning             {:fn judges/reasoning-judge             :result-key :reasoning-result}
   :completeness          {:fn judges/completeness-judge          :result-key :completeness-result}})

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

   Each line: 'dimension (0.NN): <feedback> [reasoning: <reasoning>]'."
  [dimension-results]
  (->> dimension-results
       (sort-by :score)
       (map (fn [{:keys [dim score feedback reasoning]}]
              (let [fb (or (not-empty (some-> feedback string/trim)) "(no feedback)")
                    rs (not-empty (some-> reasoning string/trim))]
                (str (name dim) " (" (format "%.2f" (double score)) "): " fb
                     (when rs (str " [reasoning: " rs "]"))))))
       (string/join "\n")))

(defn- run-judge-dimension
  "Run a single tier-1 judge on the trace-data and extract its score + feedback.
   Returns {:dim :score :feedback :reasoning :weight}. On any judge failure,
   degrades to score 0.0 with the error as feedback (so one flaky dimension
   can't sink the whole metric call)."
  [dim weight trace-data]
  (let [{:keys [fn result-key]} (dimension->judge dim)]
    (try
      (let [result (-> (fn {:inputs {:trace-data trace-data}})
                       (get result-key))]
        {:dim dim
         :weight weight
         :score (double (or (:score result) 0.0))
         :feedback (str (or (:feedback result) ""))
         :reasoning (str (or (:reasoning result) ""))})
      (catch Throwable t
        (u/log ::judge-dimension-error :dim dim :error (.getMessage t))
        {:dim dim
         :weight weight
         :score 0.0
         :feedback (str "Judge error: " (.getMessage t))
         :reasoning ""}))))

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
          ;; First string-valued output whose key is NOT an echoed input.
          (->> output
               (filter (fn [[k v]] (and (string? v) (not (contains? in-keys k)))))
               first
               val)
          ;; Last resort: any string value.
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
    (fn judge-metric
      [input output]
      (let [trace-data (->trace-data input output task)
            futs (mapv (fn [[dim weight]]
                         [dim (future (run-judge-dimension dim weight trace-data))])
                       weights)
            dimension-results (mapv (fn [[_dim fut]] @fut) futs)]
        {:score (weighted-combine dimension-results)
         :feedback (format-judge-feedback dimension-results)}))))
