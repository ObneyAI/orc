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
  (:require [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.evaluation.core.logic :as logic]
            [ai.obney.orc.evaluation.core.queries :as queries]
            [ai.obney.orc.evaluation.core.read-models]
            [ai.obney.orc.evaluation.core.todo-processors]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.integrations.interface :as integrations]))

(def ->score-with-feedback
  "Create a ScoreWithFeedback with validation.
   See logic/->score-with-feedback for full documentation."
  logic/->score-with-feedback)

(def ->metric-dimension
  "Create a MetricDimension with validation.
   See logic/->metric-dimension for full documentation."
  logic/->metric-dimension)

(def combine-dimension-scores
  "Combine multiple weighted dimensions into a single ScoreWithFeedback.
   See logic/combine-dimension-scores for full documentation."
  logic/combine-dimension-scores)

(def render-feedback
  "Render a feedback template with arguments.
   See logic/render-feedback for full documentation."
  logic/render-feedback)

(def aggregate-feedback-summary
  "Create a concise summary of multiple dimension feedbacks.
   See logic/aggregate-feedback-summary for full documentation."
  logic/aggregate-feedback-summary)

(def FEEDBACK_TEMPLATES
  "Common feedback templates for evaluation patterns."
  logic/FEEDBACK_TEMPLATES)

(defn current-judge-config
  "Return the resolved provider/model used by retained synchronous judges.
   Consumers may persist this configuration to preserve judging across restart."
  []
  (logic/current-judge-config))

(defn call-with-judge-config
  "Invoke f with the supplied provider/model. Other judge bindings, including
   mock mode, remain unchanged; futures created by f inherit these bindings."
  [config f]
  (logic/call-with-judge-config config f))

(def get-judge
  "Get a retained synchronous judge function by key (not an assessment).
   Available keys: :grounding, :instruction-following, :reasoning, :completeness, :aggregate"
  logic/get-judge)

(def grounding-judge
  "Judge executor for evaluating source grounding/hallucination."
  logic/grounding-judge)

(def instruction-following-judge
  "Judge executor for evaluating instruction following."
  logic/instruction-following-judge)

(def reasoning-judge
  "Judge executor for evaluating reasoning quality."
  logic/reasoning-judge)

(def completeness-judge
  "Judge executor for evaluating response completeness."
  logic/completeness-judge)

(def aggregate-dimensions
  "Executor for aggregating multiple dimension results."
  logic/aggregate-dimensions)

(def evaluate-single
  "Evaluate a trace with a single judge.
   See logic/evaluate-single for full documentation."
  logic/evaluate-single)

(def get-rubric
  "Get a legacy single-string rubric by key (retained for legacy paths; judges
   attached to nodes declare their own rubric).
   Available keys: :grounding, :instruction-following, :reasoning, :completeness"
  logic/get-rubric)

(def get-rubrics
  "Get multiple rubrics by keys. Returns all if keys is nil."
  logic/get-rubrics)

(def DEFAULT_RUBRICS
  "Default set of rubrics for reference-free evaluation."
  logic/DEFAULT_RUBRICS)

(def get-judge-scores
  "Return the score entries the learning loops read for the given
   (sheet-id, node-id, tick-id) tuple: one per scored outcome of a learning judge
   that carried feedback. Empty vector otherwise (monitoring judges, failed or
   ungradable assessments leave no entry). Each entry has :judge-name
   :judge-config :score :feedback :dimensions :emitted-at. The consolidator reads
   this read-model to enrich its LLM reflection input. For every assessment
   outcome use `get-assessments`."
  queries/get-judge-scores)

(def get-assessments
  "S7: the tenant's assessments, oldest request first, optionally narrowed by
   `{:sheet-id :node-id :tick-id :judge-name :status}`. An assessment is the
   durable unit of judging: identified by the completion it assesses, the judge
   and the judge's revision, and ending :scored, :failed or :ungradable (:pending
   until then). See ADR 0008."
  queries/get-assessments)

(def get-node-performance
  "S11: performance of a node, version by version (never blended), with a rollup
   across versions. `{:sheet-id :node-id [:node-version] [:judge-name] [:window]}`.
   Every outcome of every assessment contributes, always; see core/queries.clj."
  queries/get-node-performance)

(def get-low-performing
  "S11: the node versions whose trailing mean is below `:below`, worst first.
   `{:below x [:sheet-id] [:min-coverage] [:window]}`."
  queries/get-low-performing)

(def get-performance-trend
  "S11: a node judge's most recent outcomes in order.
   `{:sheet-id :node-id :judge-name [:node-version] [:window]}`."
  queries/get-performance-trend)

(def get-assessment-report
  "S11: coverage and grades over an explicit set of assessments, by judge and
   revision. `{:assessment-ids [...]}` or `{:subject-ids [...]}`."
  queries/get-assessment-report)

(def get-effective-judges-for-node
  "Return the effective judge list for a node — a vec of
   {:judge-name :judge-config} entries. Resolution order:
     1. An explicit attachment (even empty) wins; attaching a judge enables it
     2. The 5 default judges for :repl-researcher nodes, only when the Living
        Description flag is on (the flag gates only these defaults)
     3. Empty otherwise
   Used by the judge runtime when a node completes; also useful for operators
   to introspect what is wired."
  queries/get-effective-judges-for-node)

(def get-llm-traces
  "Extract LLM node traces for evaluation.
   See queries/get-llm-traces for full documentation."
  queries/get-llm-traces)

(def get-traces-raw
  "Get raw sheet execution traces.
   See queries/get-traces-raw for full documentation."
  queries/get-traces-raw)

(def get-node-stats
  "Get basic statistics for LLM node executions.
   See queries/get-node-stats for full documentation."
  queries/get-node-stats)

(def format-trace-for-evaluation
  "Format a trace for input to evaluation judges.
   See logic/format-trace-for-evaluation for full documentation."
  logic/format-trace-for-evaluation)

(def evaluation-suite
  "Complete evaluation suite workflow definition.
   Runs all judges in parallel and aggregates results.
   Use with sheet/build-workflow! and sheet/execute."
  logic/evaluation-suite)

(def batch-evaluation-suite
  "Batch evaluation suite for processing multiple traces.
   Uses map-each for parallel trace processing."
  logic/batch-evaluation-suite)

(def selective-judge-suite
  "Create an evaluation suite with only specified judges.
   Args: judge-keys - vector of :grounding, :instruction-following, :reasoning, :completeness"
  logic/selective-judge-suite)

(def grounding-judge-sheet
  "Single grounding/hallucination judge sheet."
  logic/grounding-judge-sheet)

(def instruction-judge-sheet
  "Single instruction following judge sheet."
  logic/instruction-judge-sheet)

(def reasoning-judge-sheet
  "Single reasoning quality judge sheet."
  logic/reasoning-judge-sheet)

(def completeness-judge-sheet
  "Single completeness judge sheet."
  logic/completeness-judge-sheet)

(integrations/register-code-functions!
  :evaluation
  {'ai.obney.orc.evaluation.core.judges/grounding-judge #'logic/grounding-judge
    'ai.obney.orc.evaluation.core.judges/instruction-following-judge #'logic/instruction-following-judge
    'ai.obney.orc.evaluation.core.judges/reasoning-judge #'logic/reasoning-judge
    'ai.obney.orc.evaluation.core.judges/completeness-judge #'logic/completeness-judge
    'ai.obney.orc.evaluation.core.judges/aggregate-dimensions #'logic/aggregate-dimensions
    'ai.obney.orc.evaluation.core.judges/evaluate-trace #'logic/evaluate-trace})
