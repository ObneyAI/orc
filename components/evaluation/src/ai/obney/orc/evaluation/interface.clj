(ns ai.obney.orc.evaluation.interface
  "Public interface for the evaluation component.

   This component provides LLM-as-judge evaluation capabilities for
   ORC sheet service executions, with GEPA-compatible feedback generation.

   ## Key Concepts

   - **ScoreWithFeedback**: A score (0.0-1.0) paired with actionable feedback
   - **MetricDimension**: A weighted evaluation dimension (e.g., grounding, completeness)
   - **Judge**: A function that evaluates trace data and returns scores with feedback
   - **Rubric**: A prompt template that defines evaluation criteria

   ## Usage

   ```clojure
   ;; Create a score with feedback
   (require '[ai.obney.orc.evaluation.interface :as eval])

   (eval/->score-with-feedback 0.75 \"Good but missing key entity\")

   ;; Combine dimension scores
   (eval/combine-dimension-scores
     [{:name \"Grounding\" :weight 0.6 :score 0.9 :feedback \"Well grounded\"}
      {:name \"Completeness\" :weight 0.4 :score 0.5 :feedback \"Missing aspects\"}])

   ;; Get a judge executor for use in sheets
   (eval/get-judge :grounding)
   ```"
  (:require [ai.obney.orc.evaluation.core.feedback :as feedback]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.evaluation.core.rubrics :as rubrics]
            [ai.obney.orc.evaluation.core.trace-extraction :as traces]
            [ai.obney.orc.evaluation.core.sheets :as sheets]
            ;; Gap-1: per-event evaluator runtime + judge-scores read-model
            [ai.obney.orc.evaluation.core.judge-runtime :as judge-runtime]
            ;; Load schemas to register them
            [ai.obney.orc.evaluation.interface.schemas]))

;; =============================================================================
;; Re-exports: Feedback
;; =============================================================================

(def ->score-with-feedback
  "Create a ScoreWithFeedback with validation.
   See feedback/->score-with-feedback for full documentation."
  feedback/->score-with-feedback)

(def ->metric-dimension
  "Create a MetricDimension with validation.
   See feedback/->metric-dimension for full documentation."
  feedback/->metric-dimension)

(def combine-dimension-scores
  "Combine multiple weighted dimensions into a single ScoreWithFeedback.
   See feedback/combine-dimension-scores for full documentation."
  feedback/combine-dimension-scores)

(def render-feedback
  "Render a feedback template with arguments.
   See feedback/render-feedback for full documentation."
  feedback/render-feedback)

(def aggregate-feedback-summary
  "Create a concise summary of multiple dimension feedbacks.
   See feedback/aggregate-feedback-summary for full documentation."
  feedback/aggregate-feedback-summary)

(def FEEDBACK_TEMPLATES
  "Common feedback templates for evaluation patterns."
  feedback/FEEDBACK_TEMPLATES)

;; =============================================================================
;; Re-exports: Judges
;; =============================================================================

(def get-judge
  "Get a judge executor by key.
   Available keys: :grounding, :instruction-following, :reasoning, :completeness, :aggregate"
  judges/get-judge)

(def grounding-judge
  "Judge executor for evaluating source grounding/hallucination."
  judges/grounding-judge)

(def instruction-following-judge
  "Judge executor for evaluating instruction following."
  judges/instruction-following-judge)

(def reasoning-judge
  "Judge executor for evaluating reasoning quality."
  judges/reasoning-judge)

(def completeness-judge
  "Judge executor for evaluating response completeness."
  judges/completeness-judge)

(def aggregate-dimensions
  "Executor for aggregating multiple dimension results."
  judges/aggregate-dimensions)

(def evaluate-single
  "Evaluate a trace with a single judge.
   See judges/evaluate-single for full documentation."
  judges/evaluate-single)

(def evaluate-all
  "Evaluate a trace with all judges and aggregate.
   See judges/evaluate-all for full documentation."
  judges/evaluate-all)

;; Note: with-mock-llm and with-judge-config are macros.
;; Use them directly from the core namespace:
;;   (require '[ai.obney.orc.evaluation.core.judges :as judges])
;;   (judges/with-mock-llm ...)

;; =============================================================================
;; Re-exports: Rubrics
;; =============================================================================

(def get-rubric
  "Get a rubric by key.
   Available keys: :grounding, :instruction-following, :reasoning, :completeness"
  rubrics/get-rubric)

(def get-rubrics
  "Get multiple rubrics by keys. Returns all if keys is nil."
  rubrics/get-rubrics)

(def DEFAULT_RUBRICS
  "Default set of rubrics for reference-free evaluation."
  rubrics/DEFAULT_RUBRICS)

;; =============================================================================
;; Re-exports: Per-event evaluator runtime (Gap-1)
;; =============================================================================

(def get-judge-scores
  "Gap-1: return the vector of judge result entries emitted for the given
   (sheet-id, node-id, tick-id) tuple. Empty vector when no judges fired
   for that tick. Each entry has :judge-name :judge-config :score
   :feedback :dimensions :emitted-at. Consolidator (Gap-3) consumes this
   read-model to enrich its LLM reflection input."
  judge-runtime/get-judge-scores)

(def get-effective-judges-for-node
  "Gap-5: return the effective judge list for a node — a vec of
   {:judge-name :judge-config} entries. Resolution order:
     1. Explicit consumer attachment (even empty) wins
     2. Default attachment for :repl-researcher nodes (5 judges) when
        the Living Description opt-in is on
     3. Empty otherwise
   Used by judge-runtime at dispatch time; also useful for operators
   to introspect what's wired."
  judge-runtime/get-effective-judges-for-node)

;; =============================================================================
;; Re-exports: Trace Extraction
;; =============================================================================

(def get-llm-traces
  "Extract LLM node traces for evaluation.
   See traces/get-llm-traces for full documentation."
  traces/get-llm-traces)

(def get-traces-raw
  "Get raw sheet execution traces.
   See traces/get-traces-raw for full documentation."
  traces/get-traces-raw)

(def get-node-stats
  "Get basic statistics for LLM node executions.
   See traces/get-node-stats for full documentation."
  traces/get-node-stats)

(def format-trace-for-evaluation
  "Format a trace for input to evaluation judges.
   See traces/format-trace-for-evaluation for full documentation."
  traces/format-trace-for-evaluation)

;; =============================================================================
;; Re-exports: Evaluation Sheets (ORC Workflows)
;; =============================================================================

(def evaluation-suite
  "Complete evaluation suite workflow definition.
   Runs all judges in parallel and aggregates results.
   Use with sheet/build-workflow! and sheet/execute."
  sheets/evaluation-suite)

(def batch-evaluation-suite
  "Batch evaluation suite for processing multiple traces.
   Uses map-each for parallel trace processing."
  sheets/batch-evaluation-suite)

(def selective-judge-suite
  "Create an evaluation suite with only specified judges.
   Args: judge-keys - vector of :grounding, :instruction-following, :reasoning, :completeness"
  sheets/selective-judge-suite)

(def grounding-judge-sheet
  "Single grounding/hallucination judge sheet."
  sheets/grounding-judge-sheet)

(def instruction-judge-sheet
  "Single instruction following judge sheet."
  sheets/instruction-judge-sheet)

(def reasoning-judge-sheet
  "Single reasoning quality judge sheet."
  sheets/reasoning-judge-sheet)

(def completeness-judge-sheet
  "Single completeness judge sheet."
  sheets/completeness-judge-sheet)

;; =============================================================================
;; High-Level API
;; =============================================================================

(defn evaluate-trace
  "Evaluate a single trace using the specified judges.

   Args:
     trace-data: Map with :inputs, :outputs, :instruction
     options: Map with optional keys:
       :judges - Vector of judge keys to run (default: all)
       :weights - Map of judge-key to weight (default: from rubrics)

   Returns:
     ScoreWithFeedback with aggregate score and combined feedback

   Example:
     (evaluate-trace
       {:inputs {:question \"What is 2+2?\"}
        :outputs {:answer \"4\"}
        :instruction \"Answer math questions\"}
       {:judges [:grounding :reasoning]})"
  ([trace-data]
   (evaluate-trace trace-data {}))
  ([trace-data {:keys [judges] :or {judges [:grounding :instruction-following
                                            :reasoning :completeness]}}]
   (let [executor-context {:inputs {:trace-data trace-data}}
         results (reduce
                  (fn [acc judge-key]
                    (let [judge-fn (get-judge judge-key)
                          result (judge-fn executor-context)]
                      (merge acc result)))
                  {}
                  judges)
         agg-result (aggregate-dimensions {:inputs results})]
     (->score-with-feedback
      (:aggregate-score agg-result)
      (:feedback-summary agg-result)
      (:dimensions agg-result)))))

(defn evaluate-traces
  "Evaluate multiple traces and return aggregated statistics.

   Args:
     traces: Sequence of trace-data maps
     options: Same as evaluate-trace options

   Returns:
     Map with:
       :results - Vector of individual ScoreWithFeedback results
       :avg-score - Average score across all traces
       :min-score - Minimum score
       :max-score - Maximum score
       :low-scoring - Traces with score below 0.7"
  ([traces]
   (evaluate-traces traces {}))
  ([traces options]
   (let [results (mapv #(evaluate-trace % options) traces)
         scores (mapv :score results)]
     {:results results
      :avg-score (if (seq scores)
                   (/ (reduce + scores) (count scores))
                   0.0)
      :min-score (if (seq scores) (apply min scores) 0.0)
      :max-score (if (seq scores) (apply max scores) 0.0)
      :low-scoring (filterv #(< (:score %) 0.7) results)})))
