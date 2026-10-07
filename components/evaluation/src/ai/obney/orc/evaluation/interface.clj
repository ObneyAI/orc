(ns ai.obney.orc.evaluation.interface
  "Public interface for the evaluation component.

   This component grades what ORC nodes did and records every judgment durably.

   ## Key Concepts

   - **Judge**: a behaviour (an ordinary workflow, built-in or custom) that grades
     one assessment subject, a completed node execution, against a rubric.
     Declared with `sheet/judges` and attached to any node with `:judges`;
     attaching it enables it.
   - **Rubric**: the criterion, the reviewer's stance, a described band per level
     and whether feedback is required. The model picks a band; the score is
     derived from it.
   - **Assessment**: one judge's judgment of one subject under one judge revision.
     It ends scored, failed or ungradable (`get-assessments`).
   - **Purposes**: a monitoring judge feeds performance only; a learning judge
     (which must require feedback) also feeds Living Descriptions and harvest.
   - **Performance**: per node version, always collected (`get-node-performance`,
     `get-low-performing`, `get-performance-trend`, `get-assessment-report`).
   - **ScoreWithFeedback / MetricDimension**: value types used by the retained
     synchronous judge functions and by GEPA's judge metric.

   See docs/EVALUATION-COMPONENT.md for the reference and runnable examples."
  (:require [ai.obney.orc.evaluation.core.feedback :as feedback]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.evaluation.core.rubrics :as rubrics]
            [ai.obney.orc.evaluation.core.trace-extraction :as traces]
            [ai.obney.orc.evaluation.core.sheets :as sheets]
            ;; per-event evaluator runtime + judge-scores read-model
            [ai.obney.orc.evaluation.core.judge-runtime :as judge-runtime]
            [ai.obney.orc.evaluation.core.assessments :as assessments]
            [ai.obney.orc.evaluation.core.performance :as performance]
            [ai.obney.orc.evaluation.core.alerts]
            ;; Load the score-recording command handlers so they register
            ;; in the global command registry (the judge runtime dispatches
            ;; them via cp/process-command from its background future).
            [ai.obney.orc.evaluation.core.commands]
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
  "Get a retained synchronous judge function by key (not an assessment).
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

;; Note: with-mock-llm and with-judge-config are macros.
;; Use them directly from the core namespace:
;;   (require '[ai.obney.orc.evaluation.core.judges :as judges])
;;   (judges/with-mock-llm ...)

;; =============================================================================
;; Re-exports: Rubrics
;; =============================================================================

(def get-rubric
  "Get a legacy single-string rubric by key (retained for legacy paths; judges
   attached to nodes declare their own rubric).
   Available keys: :grounding, :instruction-following, :reasoning, :completeness"
  rubrics/get-rubric)

(def get-rubrics
  "Get multiple rubrics by keys. Returns all if keys is nil."
  rubrics/get-rubrics)

(def DEFAULT_RUBRICS
  "Default set of rubrics for reference-free evaluation."
  rubrics/DEFAULT_RUBRICS)

;; =============================================================================
;; Re-exports: Per-event evaluator runtime and assessments
;; =============================================================================

(def get-judge-scores
  "Return the score entries the learning loops read for the given
   (sheet-id, node-id, tick-id) tuple: one per scored outcome of a learning judge
   that carried feedback. Empty vector otherwise (monitoring judges, failed or
   ungradable assessments leave no entry). Each entry has :judge-name
   :judge-config :score :feedback :dimensions :emitted-at. The consolidator reads
   this read-model to enrich its LLM reflection input. For every assessment
   outcome use `get-assessments`."
  judge-runtime/get-judge-scores)

(def get-assessments
  "S7: the tenant's assessments, oldest request first, optionally narrowed by
   `{:sheet-id :node-id :tick-id :judge-name :status}`. An assessment is the
   durable unit of judging: identified by the completion it assesses, the judge
   and the judge's revision, and ending :scored, :failed or :ungradable (:pending
   until then). See ADR 0008."
  assessments/get-assessments)

(def get-node-performance
  "S11: performance of a node, version by version (never blended), with a rollup
   across versions. `{:sheet-id :node-id [:node-version] [:judge-name] [:window]}`.
   Every outcome of every assessment contributes, always; see core/performance.clj."
  performance/get-node-performance)

(def get-low-performing
  "S11: the node versions whose trailing mean is below `:below`, worst first.
   `{:below x [:sheet-id] [:min-coverage] [:window]}`."
  performance/get-low-performing)

(def get-performance-trend
  "S11: a node judge's most recent outcomes in order.
   `{:sheet-id :node-id :judge-name [:node-version] [:window]}`."
  performance/get-performance-trend)

(def get-assessment-report
  "S11: coverage and grades over an explicit set of assessments, by judge and
   revision. `{:assessment-ids [...]}` or `{:subject-ids [...]}`."
  performance/get-assessment-report)

(def get-effective-judges-for-node
  "Return the effective judge list for a node — a vec of
   {:judge-name :judge-config} entries. Resolution order:
     1. An explicit attachment (even empty) wins; attaching a judge enables it
     2. The 5 default judges for :repl-researcher nodes, only when the Living
        Description flag is on (the flag gates only these defaults)
     3. Empty otherwise
   Used by the judge runtime when a node completes; also useful for operators
   to introspect what is wired."
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
