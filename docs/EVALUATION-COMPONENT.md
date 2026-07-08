# Evaluation Component

LLM-as-judge evaluation for ORC sheet service executions, with GEPA-compatible feedback generation.

> **For the judge design north star** — rubric architecture (criteria × stance × scale), the design decisions behind discrete bands and adversarial stance, and the perfect-judge checklist — see [JUDGE-ARCHITECTURE.md](JUDGE-ARCHITECTURE.md). This doc is the complete API reference and implementation detail.

## Start here: make the tree you already have better

You have a behavior tree. Maybe it's a `:sequence` of `:llm` nodes — survey a
document, diff it, classify the changes, summarize. It runs. The tree completes.
Each `:llm` node produces output.

But **is that output any good?** Is it *grounded* — does every claim trace back
to the input the node was given, or did the model invent a number? Is it
*complete* — did it cover everything the task asked for? Did it actually *follow
the instruction* it was handed? Today you either eyeball it or you don't check
at all. At any real volume, neither scales.

A **judge** answers those questions automatically. It's a function that reads a
node's **trace** — the `:inputs` it received, the `:response` it produced, and
the `:instruction` it was given — and returns a **score** in `[0,1]` plus
actionable feedback. Crucially, **a judge does not change your tree's execution
path.** In the default mode it fires *out of band*, after the node has already
finished, as a side effect of the event log. Your `:execute` call returns
exactly what it returned before; the score lands in the event store a moment
later.

The rest of this section walks you from the tree you already have, in the
smallest possible steps, to a tree whose every interesting node is being graded
— and whose scores feed ORC's automatic-improvement machinery.

### Step 1 — attach a built-in judge to ONE node you already have

The smallest possible step: pick one `:llm` node and attach a built-in judge.
ORC ships four LLM judges — `:grounding`, `:instruction-following`,
`:reasoning`, `:completeness` — and you attach them in two moves: declare the
judge on the workflow with `sheet/judges`, then reference it by name on the node
with `:judges`.

```clojure
(sheet/workflow "doc-analysis"
  (sheet/blackboard {:contract :string :survey :string})

  ;; 1. Declare the judge on the workflow (name → config).
  (sheet/judges
    {:survey-grounding {:type :grounding :weight 0.5}})

  (sheet/sequence "main"
    ;; 2. Reference it by name on the node you already have.
    (sheet/llm "survey"
      :model "google/gemini-2.5-flash"
      :instruction "Survey the structure and key provisions of the contract."
      :reads  [:contract]
      :writes [:survey]
      :judges ["survey-grounding"])))   ; <— the only line you added
```

The `survey` node still reads `:contract`, still writes `:survey`, still runs
exactly when and how it did. After it completes, the `survey-grounding` judge
fires asynchronously and emits a `:judge/score-emitted` event. **No latency is
added to the execution path.**

> **One-time enable.** The async judge path is gated on the Living Description
> opt-in flag (default off, so consumers pay zero overhead until they ask for
> it). Turn it on once for your context:
>
> ```clojure
> (cp/process-command
>   (assoc ctx :command {:command/name :ontology/set-living-description-enabled
>                        :command/id (random-uuid)
>                        :command/timestamp (java.time.Instant/now)
>                        :enabled? true}))
> ```
>
> Without the flag on, attached judges simply don't fire (the processor returns
> immediately). See [SELF-IMPROVING-LOOP.md](SELF-IMPROVING-LOOP.md).

For the full worked example on a real five-node contract-analysis tree, see
[GETTING-STARTED.md § Phase 2 — LLM judges](GETTING-STARTED.md#phase-2--llm-judges).

### Step 2 — read the score back

Each judge writes a `:judge/score-emitted` event, accumulated in the
`:evaluation/judge-scores` read-model keyed by `[sheet-id tick-id node-id]`.
Read it back with `get-judge-scores`:

```clojure
(require '[ai.obney.orc.evaluation.interface :as eval])

;; signature: (get-judge-scores ctx sheet-id node-id tick-id)
;; tick-id comes from the execution (e.g. via execute-stream — see STREAMING.md)
(eval/get-judge-scores ctx sheet-id survey-node-id tick-id)
;; => [{:judge-name "survey-grounding"
;;      :judge-config {:type :grounding :weight 0.5}
;;      :score      0.75
;;      :feedback   "Well grounded. Every substantive claim traces to the source..."
;;      :dimensions []
;;      :emitted-at "..."}]
```

That `:score` is a `[0,1]` value derived deterministically from a discrete 1–5
**band** the judge chose (the model never self-reports a float — see [The
Default Judges](#the-default-judges)). The `:feedback` is the actionable "here's
what to fix" text.

### Step 3 — build a judge for YOUR domain

The built-in judges grade general qualities. They don't know that *your*
completeness means "company name, budget range, timeline, and decision-maker
must all be present." When you need domain-specific standards, you have two
escalating options:

1. **Custom criteria on a built-in type** — keep the built-in `:grounding` /
   `:completeness` / etc. machinery, but supply your own `:criteria` string in
   the `sheet/judges` config. This is the common case.
2. **A full custom judge function** — when you need your own scoring `Scale`
   (different bands, a different range) or domain logic, write a judge function
   following the tier-1 shape.

Both are covered in detail under [Custom Rubrics and Workflow
Judges](#custom-rubrics-and-workflow-judges) below. For a complete, runnable
walkthrough of building a domain `Scale` and wiring a custom judge, see
[GETTING-STARTED.md § Phase 3 — Custom judges](GETTING-STARTED.md#phase-3--custom-judges).

### Step 4 — grade nodes inside a SUBBEHAVIOR (a delegated child sheet)

Real trees compose: a parent tree calls a child sheet through a `:delegate`
node, which runs that child workflow with its own isolated blackboard. The
question is — **can you grade the LLM nodes buried inside that child?** Yes.

Judges attach to a node, not to a position in the top-level tree. The async
runtime fires on *every* `:sheet/node-execution-completed` event, no matter how
deeply the node is nested — including nodes inside a delegated child sheet.
Concretely, a judge attaches to any `:leaf` node (`sheet/llm`, `sheet/code`) or
a `:repl-researcher` node that carries a `:judges` field.

The key move: **you don't attach judges to the `:delegate` node** — `delegate`
isn't a leaf and has no `:judges` slot. Instead you declare and attach the
judges *inside the child sheet's own definition*, exactly as in Step 1. The
child sheet is itself a full workflow with its own `sheet/judges` block.

```clojure
;; The CHILD sheet — defined and built on its own, then delegated to.
(def child-sheet
  (sheet/workflow "extract-provisions"
    (sheet/blackboard {:contract :string :provisions :string})

    ;; Judges declared on the CHILD workflow…
    (sheet/judges
      {:extraction-grounding {:type :grounding :weight 1.0}})

    (sheet/sequence "main"
      ;; …and attached to the child's own leaf node.
      (sheet/llm "extract"
        :model "google/gemini-2.5-flash"
        :instruction "Extract every provision verbatim from the contract."
        :reads  [:contract]
        :writes [:provisions]
        :judges ["extraction-grounding"]))))

(def child-id (sheet/build-workflow! ctx child-sheet))

;; The PARENT tree delegates to the child. No :judges on the delegate node;
;; the child's own attached judges fire when the child's "extract" node completes.
(def parent-sheet
  (sheet/workflow "review"
    (sheet/blackboard {:contract :string :provisions :string})
    (sheet/sequence "main"
      (sheet/delegate "extract-step"
        :target-sheet-id child-id
        :reads  [:contract]
        :writes [:provisions]))))
```

When the parent runs and the child's `extract` node completes, its
`extraction-grounding` judge emits a `:judge/score-emitted` event just like a
top-level node. (`:repl-researcher` nodes can also carry judges — and when the
opt-in flag is on, they auto-attach the default judge set even with no explicit
`:judges`. See [RLM-GUIDE.md § Judges on repl-researcher nodes](RLM-GUIDE.md#judges-on-repl-researcher-nodes-rlm-specific-defaults--living-description-loop).)

### Step 5 — how scores feed the bigger improvement loop

A judge score isn't the destination; it's the seed of automatic improvement. The
same `:judge/score-emitted` events you read in Step 2 are consumed by ORC's
learning machinery:

- **GEPA** uses judge scores (and their feedback) as the fitness signal that
  drives prompt-instruction optimization and Pareto-frontier selection. See
  [GEPA-GUIDE.md](GEPA-GUIDE.md).
- **The self-improving loop** consolidates judge scores into Living Description
  bodies (a pattern's observed strengths, weaknesses, and evidence-counts),
  which then prime future tree designs. See
  [SELF-IMPROVING-LOOP.md](SELF-IMPROVING-LOOP.md).

The judge-grounded `:avoid-when` evidence is **dual-use**, not merely recorded.
It feeds two consumers (ADRs
0015 /
0016): (1) the consolidator
folds it into Living Description bodies (the C-3 weakness-self-correction loop),
and (2) classification's reranker READS each candidate's `:avoid-when` and a
deterministic contrastive **domain penalty** ENFORCES it after the rerank, so a
strong shape match no longer overrides a firing domain guard. A `refactor→rename`
run that scores badly teaches the consolidator to add an "avoid when extract /
refactor" guard, after which the penalty bites precisely on the next similar task —
the same evidence both learns and enforces. See
[LIVING-DESCRIPTIONS.md](LIVING-DESCRIPTIONS.md) and
[SELF-IMPROVING-LOOP.md § How novelty is handled](SELF-IMPROVING-LOOP.md#2-how-novelty-is-handled--detect-and-defer--the-emergence-loop).

The full signal path is detailed in [§ Why judges matter](#why-judges-matter)
just below — the short version: **every automatic improvement ORC makes starts
with a judge score**, which is why judge calibration matters so much.

---

## Why judges matter

A judge score is the seed of every downstream learning mechanism in ORC. The signal path runs: judge score → `:judge/score-emitted` event → consolidator → Living Description body (strengths, weaknesses, evidence-counts) → GEPA reflective dataset → proposer LLM → new instruction candidate → Pareto frontier selection. Each time ORC updates its behaviour — through Living Descriptions, through GEPA picking a better instruction, through the self-improving loop mint-and-retrieve cycle — it is acting on a signal that started with a judge score. **Judge noise propagates into every learning mechanism**: a miscalibrated judge does not just produce a bad number; it feeds bad signal into the ontology, into the reflective dataset, and into every future GEPA candidate. This is why so much engineering investment went into the score-derivation pipeline — discrete bands, adversarial stance, reason-before-score field ordering, typed blackboard output, and the no-run-through gate. Each property exists to prevent a specific documented failure mode. For the full signal-path diagram, design lineage, and the 8-property "perfect judge" checklist, see [JUDGE-ARCHITECTURE.md § 1 — Why judges matter](JUDGE-ARCHITECTURE.md#1-why-judges-matter).

## Two deployment modes

A judge is **one evaluation capability** — not inherently a pass/fail gate. The same built-in or custom judge is first-class in either of two modes:

**Mode A — Event-subscribed async (the learning path)**
The judge fires out-of-band, after node execution, as a side effect of the event log. The `:evaluation/on-node-execution-completed` processor subscribes to `:sheet/node-execution-completed` events, fires attached judges in parallel via futures, and emits one `:judge/score-emitted` event per judge. Scores feed the consolidator → Living Descriptions body → GEPA reflective dataset. This is the self-improving loop path. Zero overhead when the Living Description opt-in flag is off.

**Mode B — In-pipeline behavior-tree gate**
The judge runs inline inside the workflow execution and its verdict directly gates flow. Wire `evaluate-trace` as the fn body of a `sheet/code` node that writes `:quality-score` to the blackboard, then gate with a `sheet/condition` node on the returned score. This adds one LLM call's worth of latency per qualifying node. The judge function itself does not change — only its deployment site differs.

Improving the built-in judge benefits both modes simultaneously. For the full event-flow diagram, processor wiring detail, and code patterns for each mode, see [JUDGE-ARCHITECTURE.md § 6 — Two deployment modes](JUDGE-ARCHITECTURE.md#6-two-deployment-modes).

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
[`RLM-GUIDE.md` § Judges on repl-researcher nodes](RLM-GUIDE.md#judges-on-repl-researcher-nodes-rlm-specific-defaults--living-description-loop)
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
├── deps.edn                              # Dependencies (orc's LLM layer, Cheshire)
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

### Rubric (tier-1: decoupled criteria × stance × Scale)

A tier-1 rubric is **not** a single bundled prompt string. It keeps the three concerns separate and is resolved via `rubrics/get-tier1-rubric`:

```clojure
(rubrics/get-tier1-rubric :grounding)
;; => {:name "Source Grounding"
;;     :weight 0.35
;;     :criteria GROUNDING_CRITERIA   ;; WHAT to evaluate (decoupled)
;;     :stance   GROUNDING_STANCE     ;; HOW to behave — adversarial (decoupled)
;;     :scale    GROUNDING_SCALE}     ;; HOW to score — a first-class discrete 1–5 Scale
```

The judge fn composes `stance + criteria + (scale/render-bands scale)` into a short, field-name-oriented instruction at call time. The output shape is carried by the **typed blackboard** (orc's LLM-layer output fields with `{:description …}`), so there is **no `## Required Output (JSON)` block** and **no "1.0 Excellent / 0.8 Good" soft anchor** in the prompt — the per-level band descriptions ARE the scoring anchors.

> The old single-string `*_RUBRIC` defs (with embedded `## Scoring Rubric` soft-0–1 anchors and a `## Required Output (JSON)` example) survive in `core/rubrics.clj` **only for legacy retrospective code paths**. The live judges use the tier-1 rubrics above.

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

> **All four LLM judges below run the tier-1 shape** (decoupled discrete 1–5 Scale, adversarial reason-before-score, typed-blackboard output, no-run-through gate — see the [tier-1 section](#tier-1-judge-model-2026-06-decoupled-discrete-scale--reason-before-score--all-four-llm-judges) above). In every judge result, `:score` is a `[0,1]` value **derived deterministically from the discrete `:level` band** (the model never self-reports a float). Each result also carries the richer tier-1 fields `:level` (the 1–5 band) and `:reasoning` (the adversarial analysis written before the band was chosen).

### 1. Grounding Judge (advisory 35% weight)

Adversarial, source-grounded reviewer: detects hallucinations by checking whether every substantive claim traces to the source (the inputs), never the producer's self-report.

**Output fields:**
- `reasoning`: Adversarial source-grounded analysis (written BEFORE the band)
- `grounded-claims`: List of substantive claims supported by the source
- `ungrounded-claims`: List of unsupported claims (fabrications, unsupported numbers/names/dates, inferences stated as fact)
- `level`: The chosen 1–5 grounding band
- `feedback`: Actionable improvement suggestions
- `score`: `[0,1]`, derived deterministically from `:level` via the Scale

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

Adversarial compliance auditor: enumerates the instruction's explicit requirements AND prohibitions, then checks each against the response.

**Output fields:**
- `reasoning`: Adversarial compliance audit (written BEFORE the band)
- `requirements-met`: Explicit instruction requirements that WERE satisfied
- `requirements-missed`: Requirements not satisfied, or prohibitions violated
- `level`: The chosen 1–5 instruction-following band
- `feedback`: Suggestions for better compliance
- `score`: `[0,1]`, derived deterministically from `:level` via the Scale

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

Adversarial logician: traces the inference chain from premises to conclusion and attacks the weakest link.

**Output fields:**
- `reasoning`: Adversarial logical analysis (written BEFORE the band)
- `reasoning-strengths`: Aspects of the inference chain that are sound
- `reasoning-weaknesses`: Logical gaps, unstated assumptions, non-sequiturs, overreaching conclusions
- `level`: The chosen 1–5 reasoning-quality band
- `feedback`: Suggestions for clearer, more rigorous reasoning
- `score`: `[0,1]`, derived deterministically from `:level` via the Scale

### 4. Completeness Judge (advisory 20% weight)

Adversarial coverage auditor: enumerates the distinct aspects the task required, then checks each against the response for presence AND sufficient detail.

**Output fields:**
- `reasoning`: Adversarial coverage audit (written BEFORE the band)
- `aspects-covered`: Required aspects addressed with sufficient detail
- `aspects-missing`: Required aspects missing or answered only as thin stubs
- `level`: The chosen 1–5 completeness band
- `feedback`: Suggestions for better coverage
- `score`: `[0,1]`, derived deterministically from `:level` via the Scale

### 5. Heuristic Structural Judge (added 2026-06; deterministic, no LLM)

Pure-Clojure heuristic that grades the SHAPE of a tree the model produced (via `:generated-tree-raw` in the host's writes). Looks for patterns like declared `:output-schemas`, presence of `:aggregate` for deterministic merges, `:max-concurrency` on `:map-each`, etc. Returns a score reflecting "how well-formed is this tree as a behavior tree?"

This judge is **deterministic** — it keeps its `[0,1]` shape directly (no discrete `Scale`, no LLM call, so `get-tier1-rubric` returns `nil` for it). Fires alongside the 4 LLM judges when Living Description is on. No LLM cost. Also fires per `:rlm/tree-generated` event (each intermediate Phase-1 emit-tree iteration), not just on terminal completion.

**Source:** `components/evaluation/src/.../core/heuristic_structural.clj`

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

Access the **tier-1** rubrics (criteria × stance × Scale) the live judges use:

```clojure
;; Get a tier-1 rubric (the live judge shape)
(rubrics/get-tier1-rubric :grounding)
;; => {:name "Source Grounding" :weight 0.35
;;     :criteria "..." :stance "..." :scale {:kind :discrete :min 1 :max 5 :bands {...}}}

;; Available: :grounding :instruction-following :reasoning :completeness
;; (returns nil for keys without a tier-1 rubric, e.g. :heuristic-structural)
```

> `eval/get-rubric` / `eval/get-rubrics` / `eval/DEFAULT_RUBRICS` return the **legacy** soft-0–1 single-string rubrics. Those are retained for legacy retrospective code paths only — do not build new live judges on them.

#### 3. Custom Judge Functions

For complex domain-specific validation, create a custom judge function. Follow the tier-1 shape: a decoupled discrete `Scale`, reason-before-score field ordering, typed-blackboard output (no JSON-in-prompt), and the no-run-through gate.

```clojure
(require '[ai.obney.orc.evaluation.core.scale :as scale])

;; A custom domain Scale with explicit per-level bands (decoupled from criteria)
(def domain-completeness-scale
  (scale/discrete-scale
    {:min 1 :max 5
     :bands {1 "None of company name / budget / timeline / decision-maker present."
             2 "Only one of the four required fields present."
             3 "Two of the four present, or all four but most as thin stubs."
             4 "Three of the four present with adequate detail."
             5 "All four present with sufficient detail."}}))

(defn my-completeness-judge
  [{:keys [inputs]}]
  (let [trace-data (get inputs :trace-data)
        ;; Output fields are ordered :reasoning first, :level last
        ;; (field order = generation order → reason-before-score). Each field
        ;; is a typed LLM-layer field with {:description ...} — never JSON-in-prompt.
        raw (judges/call-tier1-judge-llm :completeness
                                         (custom-output-fields)
                                         trace-data)
        ;; no-run-through gate: throws on empty/garbage output; maps :level → :score
        gated (scale/gate-banded-output domain-completeness-scale raw)]
    {:completeness-result gated}))
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

## Implementation notes

### Tier-1 judge model (2026-06): decoupled discrete Scale + reason-before-score — ALL FOUR LLM judges

> **Status: all four built-in LLM judges run the tier-1 shape (ADR 0011). Grounding migrated in PA-3; instruction-following, reasoning, and completeness migrated in PA-4 — they mirror grounding exactly.** Grounding's 1–5 **band wording is FINALIZED (keep-strict, human-reviewed 2026-06-16)** — see `GROUNDING_SCALE` in `core/rubrics.clj`; the PA-4 dimensions carry the same keep-strict band philosophy.
>
> This is the canonical, live judge shape. The old soft `0.0–1.0` bundled rubrics (`GROUNDING_RUBRIC`, `INSTRUCTION_FOLLOWING_RUBRIC`, `REASONING_QUALITY_RUBRIC`, `COMPLETENESS_RUBRIC` with their "1.0 Excellent / 0.8 Good" anchors and JSON-in-prompt) are **retained in `core/rubrics.clj` only for legacy retrospective code paths** — they are NOT what the live judges use.

A judge is **one evaluation capability** producing a score + feedback. It is **not inherently a pass/fail gate**; the *same* judge is deployable two ways (both first-class): event-subscribed/out-of-band (`:judge/score-emitted` → consolidator/GEPA — the learning path) or in-pipeline as behavior-tree gate logic (its verdict gates flow; adds per-turn inference). Improving the built-in judge benefits both modes.

What the tier-1 shape is (identical across grounding, instruction-following, reasoning, completeness):

1. **Decoupled `Scale`** (`core/scale.clj`) — a first-class artifact separate from the criteria/stance. `discrete-scale` builds a discrete **1–5 with an explicit per-level band description**; `level->unit-score` maps it **deterministically** to `[0,1]` (1→0.0, 2→0.25, 3→0.5, 4→0.75, 5→1.0) so storage/aggregation shape is unchanged. Each judge keeps three pieces separate, bundled in `*_TIER1` and resolved via `rubrics/get-tier1-rubric`:
   - **`*_CRITERIA`** — *what* to evaluate (the dimension's definition);
   - **`*_STANCE`** — *how to behave* (an adversarial reviewer persona);
   - **`*_SCALE`** — *how to score* (the first-class discrete 1–5 `Scale`).
   None of the three embeds the others; the judge fn composes them at call time.
2. **Adversarial, reason-before-score** — each judge takes a skeptical reviewer stance (grounding defends "not grounded"; instruction-following is a compliance auditor; reasoning is an adversarial logician; completeness is a coverage auditor). Grounding grades against the *source* only (never the producer's self-report). The output fields are **ordered so `:reasoning` + the dimension's evidence lists come BEFORE the discrete `:level`** (field order = generation order in the LLM layer's tool schema). There is **no self-reported float score**; `:score` is derived deterministically from the band.
3. **Structured output via the typed blackboard** — orc's LLM-layer input/output fields with `{:description …}`. **No `:output-schemas`, no JSON-in-the-prompt.** The instruction names the fields and the bands; it carries no JSON example and no "return only JSON" directive. The trace data is passed as typed INPUT fields, not interpolated into the instruction.
4. **No-run-through gate** (`scale/gate-banded-output`) — empty/garbage model output (or a missing/unusable `:level`) **throws** (never a silent 0). This catches structured-output regressions. In-pipeline this fails the node loudly; event-subscribed the runtime isolates + mulog-logs it.

Each judge's result now carries the **back-compatible** dimension shape (e.g. `grounding-result` → `{:score :grounded-claims :ungrounded-claims :feedback}`, `instruction-result` → `{:score :requirements-met :requirements-missed :feedback}`, `reasoning-result` → `{:score :reasoning-strengths :reasoning-weaknesses :feedback}`, `completeness-result` → `{:score :aspects-covered :aspects-missing :feedback}`) PLUS richer `{:level :reasoning}`. Consumers reading `:score` (incl. the per-event runtime → `:judge/score-emitted`) are unaffected.

#### Keep-strict band philosophy (ADR 0011)

Every dimension's band-4 wording caps a response at band 3 the moment it crosses the dimension's red line — and only a fully-clean response reaches band 5:

- **Grounding:** ANY inference presented as fact — even hedged ("likely", "probably") — caps at band 3.
- **Instruction-following:** ANY missed required component or violated prohibition caps at band 3.
- **Reasoning:** ANY logical leap presented as established caps at band 3.
- **Completeness:** ANY required aspect missing, or answered as a thin stub, caps at band 3.

Strictness is deliberate (grounding/coverage/compliance/soundness are the failure modes the flywheel must catch) and is balanced by the other dimension judges, the turn-level satisfaction judge, and human-gated GEPA acceptance (watch for Goodhart toward terse, inference-free answers).

#### Calibration evidence (real traces, live OpenRouter)

Grounding bands were scored against real responses built from the real bench doc `employment_agreement.txt` (model `google/gemini-2.5-flash`), via `development/prototype_grounding_calibration.clj`; the PA-4 dimensions were calibrated the same way (`development/src/prototype_tier1_calibration.clj`):

| Crafted response (degrading grounding) | judge band | score |
|---|---|---|
| Faithful extraction | 5 | 1.00 |
| Mostly grounded, one hedged inference | 3 | 0.50 |
| Gist right, unverifiable inferences as fact | 2 | 0.25 |
| Fabricated specifics (wrong title/salary/date) | 1 | 0.00 |
| Contradicts source / different subject | 1 | 0.00 |

Bands are **strictly monotonic** with degrading quality and **stable across repeats** (faithful→5 ×3, fabricated→1 ×3) — the discrete-scale anti-mode-collapse thesis confirmed on real data. The adversarial stance grades ~1 band **stricter** in the middle than lenient hand-labels (a single explicit inference drops a response below band 4). **Human-reviewed decision (2026-06-16): keep strict** (see the band philosophy above); band-4 wording was aligned to this.

---

## Coming soon

### Pluggable scales on built-in judges

Currently the four built-in LLM judges have **sealed 1–5 bands** defined in `rubrics.clj` (`GROUNDING_SCALE`, `INSTRUCTION_FOLLOWING_SCALE`, `REASONING_SCALE`, `COMPLETENESS_SCALE`). These bands are not configurable at the judge-attachment site; a consumer wanting different band wording or a different range must write a complete custom judge function.

Coming: the ability to supply a custom `Scale` artifact to an existing built-in judge type directly — e.g., pass `{:type :grounding :scale my-domain-scale}` in `sheet/judges` without rewriting the stance, the output fields, or the instruction composition. This closes the gap between the decoupled `Scale` design (the `Scale` is already a first-class artifact separate from criteria and stance — see [JUDGE-ARCHITECTURE.md § Pluggable scales on built-in LLM judges](JUDGE-ARCHITECTURE.md#pluggable-scales-on-built-in-llm-judges)) and the current `sheet/judges` API, which does not yet expose the Scale slot for overriding.

---

## Source Files Reference

| File | Purpose |
|------|---------|
| `interface.clj` | Public API - all exports |
| `core/feedback.clj` | ScoreWithFeedback, MetricDimension, feedback templates |
| `core/scale.clj` | First-class decoupled discrete `Scale` (1–5 bands → `[0,1]`), band rendering, no-run-through gate |
| `core/judges.clj` | Judge implementations (tier-1 reason-before-score), LLM calling, configuration |
| `core/rubrics.clj` | Tier-1 rubrics (`*_CRITERIA`/`*_STANCE`/`*_SCALE`/`*_TIER1`, `get-tier1-rubric`); legacy soft-0–1 rubrics for retrospective paths |
| `core/trace_extraction.clj` | Query traces from event store |
| `core/sheets.clj` | ORC workflow definitions |
| `development/src/evaluation_demo.clj` | Usage examples and demos |

---

## Dependencies

```clojure
;; components/evaluation/deps.edn
{:deps {cheshire/cheshire {:mvn/version "5.13.0"}
        ;; orc's LLM layer (git dep) — the structured-output predictor that
        ;; backs LLM judge calls; same one orc-service uses for :llm nodes
        orc/llm-layer {:git/url "..." :git/sha "..."}
        orc/orc-service {:local/root "../orc-service"}}}
```

- **Cheshire**: JSON encoding for trace data
- **orc's LLM layer**: the structured-output LLM predictor used for judge calls
- **orc-service**: ORC behavior tree execution
