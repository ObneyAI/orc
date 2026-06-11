# Evaluation Component

LLM-as-judge evaluation for ORC sheet service executions, with GEPA-compatible feedback generation.

## Quick Start

```clojure
;; 1. Require the evaluation interface
(require '[ai.obney.orc.evaluation.interface :as eval])
(require '[ai.obney.orc.evaluation.core.judges :as judges])

;; 2. Define trace data (what the LLM received and produced)
(def trace
  {:inputs {:context "FAQ: The gym is open Monday-Friday 6am-10pm."}
   :response "The gym is open Monday-Friday 6am-10pm."
   :instruction "Answer based only on the provided FAQ."})

;; 3. Evaluate with mock LLM (no API calls - for testing)
(judges/with-mock-llm
  (eval/evaluate-trace trace))
;; => {:score 0.82, :feedback "...", :dimensions [...]}

;; 4. Evaluate with real LLM
(eval/evaluate-trace trace)
;; => {:score 0.85, :feedback "Well grounded...", :dimensions [...]}
```

## Overview

The evaluation component provides **reference-free LLM-as-judge evaluation** for LLM outputs. It evaluates quality without requiring ground truth labels by using LLMs to judge four dimensions:

| Dimension | Default Weight | Purpose |
|-----------|----------------|---------|
| **Grounding** | 35% | Detect hallucinations - is the response supported by inputs? |
| **Instruction Following** | 25% | Did the LLM follow its instruction? |
| **Reasoning Quality** | 20% | Is the reasoning coherent and logical? |
| **Completeness** | 20% | Are all aspects of the task addressed? |

**Note**: Weights can be customized per-judge when defining them via `sheet/judges` at the workflow level.

### Key Value Propositions

1. **No labels required**: Evaluates process quality, not correctness against ground truth
2. **Actionable feedback**: Every score comes with specific improvement suggestions
3. **GEPA-compatible**: Feedback format designed for instruction optimization
4. **ORC-integrated**: Evaluation workflows run as sheets with full observability
5. **Flexible execution**: Mock mode for testing, real LLM for production

### Two evaluation modes

The component supports BOTH retrospective and inline evaluation:

**Retrospective (original design):**
Evaluation runs **after** sheet execution as a batch. You evaluate traces by:
1. Extracting historical execution data from the event store
2. Running evaluation judges on the extracted traces
3. Analyzing results to identify quality issues

**Inline (added 2026-06 via the per-event evaluator runtime):**
When the Living Description opt-in flag is on, the
`:evaluation/on-node-execution-completed` processor subscribes to
`:sheet/node-execution-completed` events and fires any attached judges
in parallel via futures. Score events land as `:judge/score-emitted`
in the event store, and the consolidator integrates them on the next
reflection cycle. See
[`RLM-GUIDE.md` § Attaching judges to your behavior trees](RLM-GUIDE.md#attaching-judges-to-your-behavior-trees)
for the full attach-and-fire flow.

Inline evaluation costs N extra LLM calls per qualifying node tick
(N = number of attached judges; 4 LLM judges by default for
`:repl-researcher`). Consumers opt out by leaving the flag off — when
off, the processor returns immediately with zero overhead.

For instant pass/fail validation INSIDE a workflow (different from
post-hoc scoring), see the sheet service's `condition` and
`llm-condition` nodes.

---

## Architecture

```
components/evaluation/
├── deps.edn                              # Dependencies (DSCloj, Cheshire)
└── src/ai/obney/workshop/evaluation/
    ├── interface.clj                     # PUBLIC API - all exports
    ├── interface/schemas.clj             # Malli schemas for events/commands
    └── core/
        ├── feedback.clj                  # ScoreWithFeedback, MetricDimension
        ├── judges.clj                    # LLM-as-judge implementations
        ├── rubrics.clj                   # Evaluation prompt templates
        ├── trace_extraction.clj          # Query traces from event store
        └── sheets.clj                    # ORC workflow definitions
```

### Data Flow

```
                    ┌─────────────────────────────────────────┐
                    │  Historical Sheet Executions            │
                    │  (stored in Grain event store)          │
                    └────────────────┬────────────────────────┘
                                     │
                                     ▼
                    ┌─────────────────────────────────────────┐
                    │  Trace Extraction                       │
                    │  get-llm-traces, format-trace-for-eval  │
                    └────────────────┬────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Evaluation Suite (parallel execution)                                   │
├─────────────────┬───────────────────┬────────────────┬─────────────────┤
│ Grounding Judge │ Instruction Judge │ Reasoning Judge│ Completeness    │
│     (35%)       │      (25%)        │     (20%)      │    (20%)        │
└────────┬────────┴─────────┬─────────┴────────┬───────┴────────┬────────┘
         │                  │                  │                │
         └──────────────────┴──────────────────┴────────────────┘
                                     │
                                     ▼
                    ┌─────────────────────────────────────────┐
                    │  Aggregation                            │
                    │  Weighted score + combined feedback     │
                    └────────────────┬────────────────────────┘
                                     │
                                     ▼
                    ┌─────────────────────────────────────────┐
                    │  ScoreWithFeedback                      │
                    │  {:score 0.78                           │
                    │   :feedback "..."                       │
                    │   :dimensions [...]}                    │
                    └─────────────────────────────────────────┘
```

---

## Workflow Integration

### Defining Judges at Workflow Level

Judges are defined at the workflow level using `sheet/judges`, paralleling how `sheet/blackboard` defines data schemas. Nodes then reference judges by name:

```clojure
(sheet/workflow "my-workflow"
  (sheet/blackboard
    {:input :string
     :output [:map [:score :double] [:reasoning :string]]})

  ;; Define judges at workflow level
  (sheet/judges
    {:my-grounding
     {:type :grounding
      :criteria "All claims must trace to specific fields in input. Do not assume data not present."
      :weight 0.4}
     :my-completeness
     {:type :completeness
      :criteria "Must include: score (0.0-1.0), reasoning (2+ sentences with specific evidence)"
      :weight 0.3}
     :my-reasoning
     {:type :reasoning
      :criteria "Conclusions must follow from stated evidence. No logical gaps or contradictions."
      :weight 0.3}})

  ;; Nodes reference judges by name
  (sheet/llm "analyze"
    :instruction "Analyze the input..."
    :reads [:input]
    :writes [:output]
    :judges ["my-grounding" "my-completeness" "my-reasoning"]))
```

### Benefits of Workflow-Level Judges

| Benefit | Explanation |
|---------|-------------|
| **Centralization** | All evaluation criteria defined in one place |
| **Reusability** | Multiple nodes can share the same judge |
| **Evolution** | Criteria updates apply to all nodes referencing the judge |
| **Discoverability** | Easy to see all evaluation standards for a workflow |

### Writing Effective Criteria

**Be specific and measurable:**
```clojure
;; Good - specific requirements
{:type :completeness
 :criteria "Must include: company name, employee count (number), budget range (min-max values), decision timeline (quarter/year)"}

;; Bad - vague requirements
{:type :completeness
 :criteria "Be complete"}
```

**Reference actual data fields for grounding:**
```clojure
{:type :grounding
 :criteria "All claims about the lead must trace to fields in lead-data input.
            Budget claims require explicit budget-range field data.
            Do not infer timeline from company size alone."}
```

**Define logical requirements for reasoning:**
```clojure
{:type :reasoning
 :criteria "Each conclusion must cite at least one supporting evidence point.
            When factors conflict, explain how they were weighted.
            Final score must be justified by the stated reasoning."}
```

### Sharing Judges Across Nodes

Multiple nodes can reference the same judge definition:

```clojure
(sheet/workflow "multi-step-analysis"
  (sheet/judges
    {:common-grounding
     {:type :grounding
      :criteria "All claims must cite specific input data"}})

  (sheet/sequence "main"
    (sheet/llm "step-1" :judges ["common-grounding"] ...)
    (sheet/llm "step-2" :judges ["common-grounding"] ...)))
```

---

## Core Concepts

### ScoreWithFeedback

The foundational return type. Every evaluation produces a score (0.0-1.0) paired with actionable feedback text.

```clojure
(require '[ai.obney.orc.evaluation.interface :as eval])

;; Create manually
(eval/->score-with-feedback 0.75 "Good but missing one key entity")

;; With dimension details
(eval/->score-with-feedback
  0.75
  "Good overall with room for improvement"
  [{:name "Grounding" :weight 0.35 :score 0.8 :feedback "..."}
   {:name "Completeness" :weight 0.20 :score 0.6 :feedback "..."}])
```

### MetricDimension

A weighted evaluation dimension with its own score and feedback.

```clojure
;; Create a dimension
(eval/->metric-dimension
  "Source Grounding"  ; name
  0.35                ; weight (should sum to 1.0 across dimensions)
  0.8                 ; score (0.0-1.0)
  "Well grounded in sources, minor extrapolation on timeline")

;; Combine multiple dimensions
(eval/combine-dimension-scores
  [(eval/->metric-dimension "Grounding" 0.6 0.9 "Well grounded")
   (eval/->metric-dimension "Completeness" 0.4 0.5 "Missing cost info")])
;; => ScoreWithFeedback with weighted average score of 0.74
```

### Judge

A function that evaluates trace data and returns a result map. Judges follow the ORC code executor pattern:

```clojure
(defn my-judge
  [{:keys [inputs]}]
  (let [trace-data (get inputs :trace-data)]
    ;; ... evaluate trace-data ...
    {:result-key {:score 0.8 :feedback "..."}}))
```

Built-in judges:
- `grounding-judge` - Hallucination detection
- `instruction-following-judge` - Task compliance
- `reasoning-judge` - Logical coherence
- `completeness-judge` - Coverage completeness

### Rubric

A prompt template that defines evaluation criteria. Each rubric includes:

```clojure
{:name "Source Grounding"
 :weight 0.35
 :description "Evaluates whether response is grounded in context"
 :prompt "You are evaluating whether an LLM response is grounded...

          ## Scoring Rubric
          - 1.0 (Excellent): Every claim traceable, no hallucinations
          - 0.8 (Good): Almost all claims grounded
          ...

          ## Required Output (JSON)
          {\"score\": <float>, \"grounded-claims\": [...], ...}"}
```

---

## The Default Judges

> **Note (2026-06):** The component originally shipped with four LLM
> judges (grounding, instruction-following, reasoning, completeness)
> and assigned weight percentages to each. A fifth judge,
> `heuristic-structural`, was added when the per-event evaluator
> runtime landed — it grades the shape of trees the model produces
> (deterministic, no LLM call). When the Living Description opt-in
> flag is on, all 5 auto-attach to `:repl-researcher` nodes.
>
> **The weight percentages below are advisory** (35/25/20/20 isn't
> applied as a code default). The runtime emits a
> `:judge/composite-score-computed` event per tick when 2+ judges
> fire on the same node, with a weighted composite. Default policy:
> even-weight (1/N) when consumers don't set explicit weights;
> consumer-set `:judge-config :weight` values normalize to sum to
> 1.0. The per-judge `:judge-averages` in the consolidator's
> reflection stays alongside the composite — consumers who want the
> independent per-judge signal aren't disrupted.

### 1. Grounding Judge (advisory 35% weight)

Detects hallucinations by checking if claims are supported by input context.

**Output fields:**
- `score`: 0.0-1.0
- `grounded-claims`: List of claims supported by inputs
- `ungrounded-claims`: List of unsupported claims (hallucinations)
- `feedback`: Actionable improvement suggestions

**Example:**
```clojure
(def trace
  {:inputs {:context "FAQ: Gym open Mon-Fri 6am-10pm"}
   :response "The gym is open 24/7 with free parking"
   :instruction "Answer from FAQ only"})

(judges/with-mock-llm
  (judges/evaluate-single :grounding trace))
;; => {:grounding-result
;;     {:score 0.4
;;      :grounded-claims ["gym hours mentioned"]
;;      :ungrounded-claims ["24/7 claim" "free parking claim"]
;;      :feedback "Contains hallucinations: 24/7 and parking not in FAQ"}}
```

### 2. Instruction Following Judge (advisory 25% weight)

Evaluates whether the LLM followed its given instruction.

**Output fields:**
- `score`: 0.0-1.0
- `requirements-met`: List of instruction requirements satisfied
- `requirements-missed`: List of requirements not satisfied
- `feedback`: Suggestions for better compliance

**Example:**
```clojure
(def trace
  {:inputs {:data {:name "John" :age 25}}
   :response "John is 25 years old."
   :instruction "Return JSON format only"})

(judges/evaluate-single :instruction-following trace)
;; => {:instruction-result
;;     {:score 0.4
;;      :requirements-met ["mentions correct data"]
;;      :requirements-missed ["JSON format requirement"]
;;      :feedback "Response is prose, not JSON. Format as {...}"}}
```

### 3. Reasoning Judge (advisory 20% weight)

Evaluates the coherence and quality of reasoning.

**Output fields:**
- `score`: 0.0-1.0
- `reasoning-strengths`: Aspects of reasoning that were good
- `reasoning-weaknesses`: Logical gaps or unclear elements
- `feedback`: Suggestions for clearer reasoning

### 4. Completeness Judge (advisory 20% weight)

### 5. Heuristic Structural Judge (added 2026-06; deterministic, no LLM)

Pure-Clojure heuristic that grades the SHAPE of a tree the model produced (via `:generated-tree-raw` in the host's writes). Looks for patterns like declared `:output-schemas`, presence of `:aggregate` for deterministic merges, `:max-concurrency` on `:map-each`, etc. Returns a score reflecting "how well-formed is this tree as a behavior tree?"

Fires alongside the 4 LLM judges when Living Description is on. No LLM cost. Also fires per `:rlm/tree-generated` event (each intermediate Phase-1 emit-tree iteration), not just on terminal completion.

**Source:** `components/evaluation/src/.../core/heuristic_structural.clj`

Evaluates whether all aspects of the task were addressed.

**Output fields:**
- `score`: 0.0-1.0
- `aspects-covered`: Aspects that were addressed
- `aspects-missing`: Aspects that should have been included
- `feedback`: Suggestions for better coverage

---

## API Reference

### High-Level Functions

#### `evaluate-trace`

Evaluate a single trace with all judges.

```clojure
(eval/evaluate-trace trace-data)
(eval/evaluate-trace trace-data {:judges [:grounding :reasoning]})
```

**Args:**
- `trace-data`: Map with `:inputs`, `:response`, `:instruction`
- `options` (optional):
  - `:judges` - Vector of judge keys to run (default: all four)

**Returns:** `ScoreWithFeedback` record

#### `evaluate-traces`

Evaluate multiple traces with statistics.

```clojure
(eval/evaluate-traces [trace1 trace2 trace3])
```

**Returns:**
```clojure
{:results [ScoreWithFeedback, ...]
 :avg-score 0.78
 :min-score 0.65
 :max-score 0.92
 :low-scoring [traces with score < 0.7]}
```

### Judge Functions

#### `evaluate-single`

Run a single judge on a trace.

```clojure
(judges/evaluate-single :grounding trace-data)
(judges/evaluate-single :instruction-following trace-data)
(judges/evaluate-single :reasoning trace-data)
(judges/evaluate-single :completeness trace-data)
```

#### `evaluate-all`

Run all judges and aggregate.

```clojure
(judges/evaluate-all trace-data)
;; => {:aggregate-score 0.78
;;     :feedback-summary "Good (78%): 1 dimension(s) need improvement..."
;;     :dimensions [{:name "Grounding" :score 0.8 ...} ...]
;;     :raw-results {:grounding {...} :reasoning {...} ...}}
```

### Trace Extraction

#### `get-llm-traces`

Extract LLM node execution traces from the event store.

```clojure
(eval/get-llm-traces event-store
  {:sheet-id my-sheet-id
   :node-name "analyze-lead"  ; substring match
   :limit 50})
```

**Returns:** Vector of trace maps with `:inputs`, `:outputs`, `:instruction`, `:model`, etc.

#### `format-trace-for-evaluation`

Transform a raw trace into evaluation format.

```clojure
(eval/format-trace-for-evaluation raw-trace)
;; => {:inputs {...} :response "..." :instruction "..."}
```

#### `get-node-stats`

Get statistics for LLM node executions.

```clojure
(eval/get-node-stats event-store {:sheet-id my-sheet-id})
;; => [{:node-id UUID
;;      :node-name "analyze-lead"
;;      :execution-count 150
;;      :success-rate 0.967
;;      :avg-duration-ms 423}]
```

### Feedback Utilities

**How Feedback Works**: The built-in judges call an LLM that returns feedback as part of its structured output. The rubric prompts ask the LLM to provide "Specific actionable feedback explaining the score." This feedback is dynamically generated by the judge LLM, not from templates.

**Utility Templates**: The `FEEDBACK_TEMPLATES` below are helper functions for building **custom judges** or manually constructing feedback. They are NOT used by the built-in judges.

#### `render-feedback`

Render a feedback template with arguments (for custom judge development).

```clojure
(eval/render-feedback :hallucination
                      "classes are free"
                      "the provided FAQ documents")
;; => "Response contains claim 'classes are free' which is NOT found..."
```

Available templates: `:missing-entity`, `:hallucination`, `:incomplete-coverage`, `:wrong-action`, `:instruction-not-followed`, `:reasoning-unclear`, `:sarcasm-missed`, `:score-miscalibrated`

#### `aggregate-feedback-summary`

Create a concise summary from a ScoreWithFeedback.

```clojure
(eval/aggregate-feedback-summary result)
;; => "Good (78%): 1 dimension(s) need improvement: Completeness"
```

---

## ORC Sheet Integration

The evaluation component provides pre-built ORC sheets for running evaluations with full observability.

### Single Judge Sheets

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])
(require '[ai.obney.orc.evaluation.interface :as eval])

;; Build the grounding judge sheet
(def judge-id (sheet/build-workflow! ctx (eval/grounding-judge-sheet)))

;; Execute on a trace
(sheet/execute ctx judge-id
  {:trace-data {:inputs {...}
                :response "..."
                :instruction "..."}})
```

Available: `grounding-judge-sheet`, `instruction-judge-sheet`, `reasoning-judge-sheet`, `completeness-judge-sheet`

### Full Evaluation Suite

Runs all four judges in parallel, then aggregates.

```clojure
;; Build the evaluation suite
(def suite-id (sheet/build-workflow! ctx (eval/evaluation-suite)))

;; Execute
(def result (sheet/execute ctx suite-id
              {:trace-data {:inputs {:context "..."}
                            :response "..."
                            :instruction "..."}}))

;; Access results
(get-in result [:outputs :aggregate-result])
;; => {:aggregate-score 0.78
;;     :feedback-summary "..."
;;     :dimensions [...]}
```

### Batch Evaluation Suite

Process multiple traces with parallel execution.

```clojure
(def batch-id (sheet/build-workflow! ctx (eval/batch-evaluation-suite)))

(sheet/execute ctx batch-id
  {:traces [trace1 trace2 trace3 ...]})
```

### Selective Judge Suite

Run only specific judges.

```clojure
;; Only grounding and reasoning
(def selective-id
  (sheet/build-workflow! ctx
    (eval/selective-judge-suite [:grounding :reasoning])))
```

---

## Configuration

### Mock vs Real LLM Mode

Use mock mode for testing without LLM API calls:

```clojure
;; Mock mode - no API calls
(judges/with-mock-llm
  (judges/evaluate-all trace-data))

;; Real mode (default) - makes LLM calls
(judges/evaluate-all trace-data)
```

### Provider and Model Configuration

```clojure
;; Default: OpenRouter with Gemini Flash
judges/*judge-provider*  ; => :openrouter
judges/*judge-model*     ; => "google/gemini-2.5-flash"

;; Override for a specific evaluation
(judges/with-judge-config
  {:provider :anthropic
   :model "claude-3-haiku-20240307"}
  (judges/evaluate-all trace-data))

;; Or override globally
(binding [judges/*judge-provider* :anthropic
          judges/*judge-model* "claude-3-haiku-20240307"]
  (judges/evaluate-all trace-data))
```

### Custom Rubrics and Workflow Judges

There are two ways to customize evaluation criteria:

#### 1. Workflow-Level Judges (Recommended)

Define judges with custom criteria at the workflow level using `sheet/judges`:

```clojure
(sheet/workflow "my-workflow"
  (sheet/judges
    {:my-completeness
     {:type :completeness
      :criteria "Must include: company name, budget range, timeline, decision maker"
      :weight 0.4}})

  (sheet/llm "analyze"
    :judges ["my-completeness"]
    ...))
```

When evaluating traces, the system automatically uses the criteria defined in the judge:

```clojure
;; Evaluates using criteria from sheet's judge definitions
(eval/evaluate-node-traces event-store
  {:sheet-id sheet-id
   :node-id "analyze"
   :limit 50})
```

#### 2. Programmatic Rubric Access

Access the built-in rubrics directly:

```clojure
;; Get a specific rubric
(eval/get-rubric :grounding)
;; => {:name "Source Grounding" :weight 0.35 :prompt "..."}

;; Get multiple rubrics
(eval/get-rubrics [:grounding :reasoning])

;; Get all default rubrics
eval/DEFAULT_RUBRICS
```

#### 3. Custom Judge Functions

For complex domain-specific validation, create a custom judge function:

```clojure
;; Custom judge with domain-specific criteria
(defn my-completeness-judge
  [{:keys [inputs]}]
  (let [trace-data (get inputs :trace-data)
        custom-rubric {:name "Domain Completeness"
                       :prompt "Evaluate if response includes: company name,
                                budget range, timeline, decision maker.
                                Score 0.25 for each present element.
                                ..."}
        prompt (judges/render-rubric-prompt custom-rubric trace-data)]
    {:completeness-result (judges/call-llm-judge prompt ...)}))
```

Or reference a custom evaluation sheet via the `:custom` judge type:

```clojure
{:type :custom
 :sheet-id #uuid "abc123..."  ;; ID of custom judge workflow
 :weight 0.25}
```

---

## Usage Examples

### Basic: Evaluate a Single Trace

```clojure
(require '[ai.obney.orc.evaluation.interface :as eval])
(require '[ai.obney.orc.evaluation.core.judges :as judges])

(def trace
  {:inputs {:question "What are the gym hours?"
            :context "FAQ: The gym is open Monday-Friday 6am-10pm."}
   :response "The gym is open Monday-Friday 6am-10pm."
   :instruction "Answer based only on the provided FAQ."})

;; Quick evaluation with mock LLM
(judges/with-mock-llm
  (judges/evaluate-all trace))
```

### Intermediate: Quick Grounding Check

```clojure
(defn quick-grounding-check
  "Check if a response is grounded in context."
  [response context]
  (let [trace {:inputs {:context context}
               :response response
               :instruction "Respond based only on the provided context."}]
    (judges/evaluate-single :grounding trace)))

;; Usage
(quick-grounding-check
  "The gym is open 24/7"
  "FAQ: The gym is open Monday-Friday 6am-10pm.")
;; => Low score due to hallucination
```

### Advanced: Extract and Evaluate Historical Traces

```clojure
(require '[ai.obney.orc.evaluation.interface :as eval])

;; Get execution context (from your REPL setup)
(def ctx (repl-stuff/get-context))
(def event-store (:event-store ctx))

;; Extract traces from a specific LLM node
(def traces
  (eval/get-llm-traces event-store
    {:sheet-id my-lead-qualifier-sheet
     :node-name "analyze-lead"
     :limit 20}))

;; Format and evaluate each trace
(def results
  (for [trace traces]
    (let [eval-data (eval/format-trace-for-evaluation trace)
          result (judges/evaluate-all eval-data)]
      {:trace-id (:trace-id trace)
       :score (:aggregate-score result)
       :feedback (:feedback-summary result)})))

;; Find low-scoring traces for analysis
(filter #(< (:score %) 0.7) results)
```

### Production: Batch Evaluation with Statistics

```clojure
(require '[ai.obney.orc.evaluation.interface :as eval])

;; Evaluate many traces
(def traces [...]) ; your trace data

(def batch-result (eval/evaluate-traces traces))

;; Summary statistics
(println "Average score:" (:avg-score batch-result))
(println "Score range:" (:min-score batch-result) "-" (:max-score batch-result))
(println "Low-scoring traces:" (count (:low-scoring batch-result)))

;; Analyze failure patterns
(doseq [trace (:low-scoring batch-result)]
  (println "---")
  (println "Score:" (:score trace))
  (println "Issues:" (:feedback trace)))
```

### ORC Sheet: Full Workflow Integration

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])
(require '[ai.obney.orc.evaluation.interface :as eval])

;; Build evaluation suite (idempotent)
(def suite-id (sheet/build-workflow! ctx (eval/evaluation-suite)))

;; Execute on a trace - fully traced in Grain event store
(def result
  (sheet/execute ctx suite-id
    {:trace-data {:inputs {:lead-data {:name "John" :company "Acme"}}
                  :response "Lead score: 85/100. Reason: ..."
                  :instruction "Analyze the lead and score 0-100"}}))

;; Result includes full execution trace
(get-in result [:outputs :aggregate-result])
;; => {:aggregate-score 0.82
;;     :feedback-summary "Good (82%): All dimensions performing well"
;;     :dimensions [{:name "Source Grounding" :score 0.9 ...} ...]}
```

---

## Future: GEPA Integration

The evaluation component is designed to feed into GEPA-style instruction optimization.

### How Feedback Flows to Optimization

```
1. Evaluation generates ScoreWithFeedback
   ↓
2. Low-scoring traces collected with their feedback
   ↓
3. Reflection LLM analyzes failure patterns
   ↓
4. New instruction proposed based on patterns
   ↓
5. A/B test via sheet versioning
```

### Planned Features

- **Reflection sheet**: LLM analyzes low-scoring traces to find patterns
- **Instruction proposal**: Automatic instruction improvements
- **Pareto frontier**: Track multiple instruction variants
- **A/B testing**: Version-controlled instruction experiments
- **Optimization commands**: `run-gepa-cycle` for automated improvement

### Event Types (Planned)

```clojure
;; After evaluation batch completes
{:type :evaluation/batch-completed
 :tags #{[:sheet sheet-id] [:node node-id]}
 :body {:traces-evaluated 50
        :avg-score 0.72
        :low-scoring-count 12}}

;; After GEPA reflection
{:type :optimization/instruction-proposal
 :tags #{[:sheet sheet-id] [:node node-id]}
 :body {:current-instruction "..."
        :proposed-instruction "..."
        :failure-patterns ["hallucination on dates" "missing entity X"]
        :based-on-traces [trace-ids...]}}
```

---

## Source Files Reference

| File | Purpose |
|------|---------|
| `interface.clj` | Public API - all exports |
| `core/feedback.clj` | ScoreWithFeedback, MetricDimension, feedback templates |
| `core/judges.clj` | Judge implementations, LLM calling, configuration |
| `core/rubrics.clj` | Evaluation prompt templates |
| `core/trace_extraction.clj` | Query traces from event store |
| `core/sheets.clj` | ORC workflow definitions |
| `development/src/evaluation_demo.clj` | Usage examples and demos |

---

## Dependencies

```clojure
;; components/evaluation/deps.edn
{:deps {cheshire/cheshire {:mvn/version "5.13.0"}
        io.github.ObneyAI/DSCloj {:git/url "..." :git/sha "..."}
        poly/orc-service {:local/root "../orc-service"}}}
```

- **Cheshire**: JSON encoding for trace data
- **DSCloj**: Clojure DSPy implementation for LLM calls
- **orc-service**: ORC behavior tree execution
