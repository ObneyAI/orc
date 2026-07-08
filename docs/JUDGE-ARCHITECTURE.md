# Judge Architecture

> **Role of this doc:** North-star reference for building an ORC judge — the design
> lineage, the `criteria × stance × scale` architecture, the 8-property "perfect
> judge" checklist, and what's coming. For the full API reference, built-in judge
> catalog, and workflow integration patterns, see
> [`EVALUATION-COMPONENT.md`](EVALUATION-COMPONENT.md).

---

## 1. Why judges matter

Judges are the crux of every downstream learning mechanism in ORC. The signal path is:

```
judge score
    └─► :judge/score-emitted event
            └─► consolidator
                    └─► Living Description body (strengths / weaknesses)
                            └─► GEPA reflective dataset
                                    └─► proposer LLM → new instruction candidate
                                            └─► Pareto frontier selection
```

Every time ORC updates its behaviour — through Living Descriptions, through GEPA picking a
better instruction, through the self-improving loop mint-and-retrieve cycle — it is acting
on a signal that started with a judge score. **Judge noise propagates into every learning
mechanism.** A miscalibrated judge does not just produce a bad number; it feeds bad signal
into the ontology, into the reflective dataset, and into every future candidate GEPA
proposes.

This is why so much engineering investment went into the score-derivation pipeline:
discrete bands, adversarial stance, reason-before-score field ordering, typed blackboard
output, and the no-run-through gate. Each property exists to prevent a specific documented
failure mode. They are all non-negotiable.

---

## 2. Design decisions

### The three-part rubric: `criteria × stance × scale`

Every ORC judge is composed of three independent, decoupled parts:

- **`CRITERIA`** — *what* to evaluate. The dimension's definition: grounding, instruction
  compliance, logical soundness, or coverage. A plain string describing what the judge measures.
- **`STANCE`** — *how* to behave. The adversarial reviewer persona. The judge defends the
  null that the output is NOT good, hunting for flaws rather than confirming quality.
- **`SCALE`** — *how* to score. A first-class `Scale` artifact from `core/scale.clj`,
  completely decoupled from the criteria and stance. It carries only band descriptions and
  the min/max range — no prompt, no criteria, no output fields.

None of the three embeds the others. The judge function composes them at call time.

**Why keep them separate?** The ORC ns docstring in `scale.clj` states the rationale:

> _"bundling criteria + scale + output-format into one prompt string with a soft 0.0–1.0
> anchor is the documented source of judge mode-collapse and miscalibration."_
> — `core/scale.clj`, lines 7–9

When criteria and scoring scale live in the same prompt string, changing the evaluation
criteria requires re-calibrating the scale, and vice versa. Keeping them separate lets you
tune each independently and swap in a custom scale without rewriting the criteria.

### Why discrete 1–5 bands instead of continuous 0.0–1.0

A continuous float score requires the model to self-report a number on an unbounded scale.
In practice, models cluster near 0.8 regardless of actual quality — the continuous range
gives no structural reason to use the full spectrum. A discrete 1–5 scale with explicit
per-level band descriptions forces the model to anchor to named quality descriptions rather
than reporting a vague float. The score `[0,1]` is then computed deterministically from the
integer band — the model never reports a float directly.

### Why adversarial stance

Lenient judges systematically overestimate quality. An adversarial stance — "defend the null
that this output is NOT good, hunt for what's wrong" — shifts calibration roughly one band
stricter in the middle quality range where most interesting signal lives. It's a cheap
framing change (one sentence in the `STANCE` var) with measurable calibration benefit.

### Why reason before score

Requiring the model to write `:reasoning` and the evidence lists *before* committing to a
`:level` forces chain-of-thought before the verdict. This is enforced structurally: field
order in the tool schema is the generation order. The model cannot reverse it at
inference time.

### Patterns evaluated and deferred

Three patterns were evaluated and deferred pending measured need:

| Pattern | Cost/value rationale for deferral |
|---|---|
| **Ensemble + hierarchical verify** | Running a second model to verify the judge's verdict roughly doubles inference cost per evaluated node tick. At tier-1, variance from a well-calibrated adversarial stance is already low enough for GEPA's signal-to-noise needs. Deferred until empirical evidence shows remaining judge variance is limiting GEPA convergence. |
| **Logprob token extraction** | Calibration via predicted token log-probabilities sidesteps generation variance but requires provider-level logprob access (not uniformly available on OpenRouter) and only works for token-space scores, not structured multi-field output. Incompatible with the typed-blackboard reason-before-score shape. |
| **Pairwise GEPA metrics** | Pairwise comparison ("is candidate A better than candidate B?") is more reliable for some quality dimensions. Deferred because current GEPA's reflective dataset format is score-based; wiring a pairwise judge in requires a new proposer input format. Earmarked for a future GEPA v2 iteration. |

The `GROUNDING_TIER1` entry in `rubrics.clj` (line 329) shows the decoupled structure:

```clojure
(def GROUNDING_TIER1
  {:name "Source Grounding"
   :weight 0.35
   :criteria GROUNDING_CRITERIA   ; separate var — WHAT to evaluate
   :stance   GROUNDING_STANCE     ; separate var — HOW to behave
   :scale    GROUNDING_SCALE})    ; separate Scale artifact — HOW to score
```

None of the three embeds the others. The judge function composes them at call time.

---

## 3. The perfect judge checklist

Eight properties, all non-optional. Each is verified against source.

---

### Property 1 — Scale decoupled from criteria

The `Scale` is a first-class artifact carrying only `{:kind :discrete :min :max :bands}`.
It has no `:criteria`, no `:instruction`, no `:prompt`. The `discrete-scale` constructor
enforces this by construction (`scale.clj`, line 46 docstring: _"The Scale intentionally
carries NO :criteria, :instruction, or :prompt"_).

Evidence: `GROUNDING_TIER1` in `rubrics.clj` (line 329–336) has separate `:criteria`,
`:stance`, `:scale` keys. None embed the others.

---

### Property 2 — Discrete 1–5 bands with explicit per-level descriptions

The built-in judges use a `discrete-scale` from 1 to 5. Every integer level has an
explicit, human-reviewed, calibration-tested description. This is the anti-mode-collapse
mechanism: the model anchors to band text, not to a continuous number.

The grounding scale (`GROUNDING_SCALE` in `rubrics.clj`, lines 116–152) illustrates the
full band wording — quoted verbatim:

| Band | Score | Verbatim description from `rubrics.clj` |
|------|-------|------------------------------------------|
| 1 | 0.00 | "Ungrounded / fabricated. The response contradicts the source on central facts, or is about a different subject entirely, or nearly all of its specific claims are inventions. A reader relying on it would be badly misled." |
| 2 | 0.25 | "Largely ungrounded. Multiple specific claims (facts, numbers, names, dates, entities) are unsupported or wrong, OR one central fact is fabricated/contradicted, even if some surrounding detail is correct. More wrong than right on substance." |
| 3 | 0.50 | "Mixed grounding. Most of the substance is supported, but there are one or more unsupported claims or inferences presented as fact — INCLUDING hedged ones ('likely', 'probably'). A reader should verify before trusting it." |
| 4 | 0.75 | "Well grounded. Every substantive claim traces to the source; the only deviation is a minor imprecise phrasing or omission that does not mislead about a fact. NO inference or extrapolation presented as fact, even hedged — any such claim caps at band 3." |
| 5 | 1.00 | "Fully grounded. Every substantive factual claim is directly supported by the source. No fabrication, no contradiction, and no inference presented as fact — hedged or otherwise. Strictly faithful to what the source says." |

Band 3 is found at `rubrics.clj` lines 141–144.

The score is the deterministic linear mapping `(level - min) / (max - min)`, verified from
`scale.clj` line 91 comment and REPL-confirmed: level 3 → 0.5, level 5 → 1.0.

---

### Property 3 — Adversarial stance

Every built-in judge takes a skeptical reviewer persona that defends the null that the
output is NOT good. From `GROUNDING_STANCE` (`rubrics.clj`, lines 103–114, verbatim
excerpt):

> _"You are a skeptical, adversarial reviewer. Your default assumption is that the
> response is NOT fully grounded until the source proves otherwise. Actively hunt for
> claims the source does not support."_

The same keep-strict principle applies to all four dimensions:
- Grounding: defends "not grounded"
- Instruction-following: a "compliance auditor" defending "did NOT fully comply"
- Reasoning: an "adversarial logician" defending "the reasoning is flawed"
- Completeness: a "coverage auditor" defending "something required is missing"

---

### Property 4 — Reasoning before score (field order enforced in schema)

The typed output fields are ordered so the model fills `:reasoning` and the dimension's
evidence lists **before** it commits to a `:level`. Field order in the tool schema
is the generation order — the model cannot reverse it.

From `grounding-output-fields` (`judges.clj`, lines 136–164), the field order is:

1. `:reasoning` — adversarial analysis (fill BEFORE choosing a level)
2. `:grounded-claims` — evidence list
3. `:ungrounded-claims` — evidence list
4. `:level` — the band chosen AFTER the reasoning
5. `:feedback` — actionable text

The `judges.clj` comment at line 137 states explicitly: _"ordered to FORCE
reason-before-score: the model fills :reasoning and the claim lists BEFORE it commits to a
:level band."_

There is **no self-reported `:score` field** in the output. `:score` is computed
deterministically after the LLM returns, by `gate-banded-output`. The model never reports
a float.

---

### Property 5 — Evidence lists per dimension

Each judge dimension carries concrete evidence that makes the reasoning auditable and gives
GEPA specific text to work from. The evidence keys by dimension:

| Dimension | Evidence fields |
|---|---|
| Grounding | `:grounded-claims`, `:ungrounded-claims` |
| Instruction-following | `:requirements-met`, `:requirements-missed` |
| Reasoning | `:reasoning-strengths`, `:reasoning-weaknesses` |
| Completeness | `:aspects-covered`, `:aspects-missing` |

Source: `judges.clj` output-field definitions at lines 136–289.

---

### Property 6 — Typed blackboard output (no `:output-schemas`, no JSON-in-prompt)

The judge's output shape is carried entirely by the typed output fields with
`{:description ...}`. There is no JSON example in the prompt and no `"return only JSON"`
directive. There is no permissive `:output-schemas` override.

From `grounding-output-fields` docstring (`judges.clj`, lines 142–145):

> _"Output shape is carried entirely by these typed fields (the typed blackboard) — never
> by json-in-prompt and never by a permissive :output-schemas override."_

The `render-bands` function in `scale.clj` (line 110) renders band descriptions as plain
human-readable text for the instruction — it is band anchoring text, not a JSON schema.
Its docstring states: _"per the structured-output rule we never put a JSON example or
'return only JSON' in the prompt — the output shape is carried by the typed blackboard /
typed output fields, not the prompt."_

---

### Property 7 — No-run-through gate

`scale/gate-banded-output` (`scale.clj`, line 125) throws `ExceptionInfo` on:
- `nil` output
- empty map output
- output missing a usable `:level`

This catches structured-output regressions before they silently score 0. From the
`gate-banded-output` docstring:

> _"This guarantees a judge never silently scores 0 on an empty model response — it errors
> loudly so a structured-output regression is caught."_

REPL-verified: `(scale/gate-banded-output s nil)` throws with message
`"gate-banded-output: empty judge output — no-run-through gate tripped (suspect a
structured-output regression)"`.

---

### Property 8 — Score derived deterministically from level, never self-reported

The model selects a band (`[:enum 1 2 3 4 5]`). ORC computes the score:

```
score = (level - min) / (max - min)
```

For a 1–5 scale (from `scale.clj`, line 91 comment):

```
1→0.0   2→0.25   3→0.5   4→0.75   5→1.0
```

REPL-verified:
```
"level 3 ->" 0.5
"level 5 ->" 1.0
```

There is no `:score` field in the typed output fields. `:score` is injected by
`gate-banded-output` via `level->unit-score` after LLM output is received:

```clojure
;; scale.clj, lines 149-150
(assoc output
       :level level
       :score (level->unit-score scale level))
```

---

## 4. Structured feedback envelope

### `evaluate-trace` result: `ScoreWithFeedback`

`eval/evaluate-trace` returns a `ScoreWithFeedback` record — the aggregate result across
all four dimension judges.

**Real prototype output** (run with `judges/with-mock-llm` against a contract-analysis
trace; mock assigns level 4 to each dimension → score 0.75):

```
clj -M:dev -e '
(require
  (quote [ai.obney.orc.evaluation.interface :as eval])
  (quote [ai.obney.orc.evaluation.core.judges :as judges]))
(let [trace {:inputs {:contract-v2 "This Agreement shall be governed by California law."}
             :outputs {:document-survey "Both contracts are governed by California law. Version 3 adds arbitration clause."}
             :instruction "Survey the structure and key differences between contract versions."}]
  (judges/with-mock-llm
    (prn (eval/evaluate-trace trace))))
```

```
#ai.obney.orc.evaluation.core.feedback.ScoreWithFeedback{
  :score 0.75,
  :feedback "Good (75%): 4 dimension(s) need improvement: Source Grounding, Instruction Following, Reasoning Quality, Completeness",
  :dimensions [
    {:name "Source Grounding",    :weight 0.35, :score 0.75,
     :feedback "Mock evaluation. In production, this analyzes actual grounding against the source."}
    {:name "Instruction Following", :weight 0.25, :score 0.75,
     :feedback "Mock evaluation. In production, this audits compliance with each instruction directive."}
    {:name "Reasoning Quality",   :weight 0.2,  :score 0.75,
     :feedback "Mock evaluation. In production, this attacks the weakest link in the inference chain."}
    {:name "Completeness",        :weight 0.2,  :score 0.75,
     :feedback "Mock evaluation. In production, this audits coverage of every required aspect."}]}
```

| Field | Type | What it is |
|-------|------|------------|
| `:score` | `double [0,1]` | Weighted aggregate across all dimension judges |
| `:feedback` | `string` | Human-readable summary with dimension-level breakdowns (weakest first in GEPA path) |
| `:dimensions` | `vector` | Per-dimension `{:name :weight :score :feedback}` |

### Per-judge result shape (grounding example)

When a judge runs individually (`evaluate-single` or inside `evaluate-all`), it returns:

```clojure
;; Field order shown as generated (reasoning before level — enforced by output-field order)
{:grounding-result
 {:reasoning       "Adversarial analysis: which claims are source-supported?"
  :grounded-claims ["claim A supported by source", ...]
  :ungrounded-claims []          ; empty when all claims are grounded
  :level   4                     ; discrete band the model chose
  :score   0.75                  ; derived from level via level->unit-score — never self-reported
  :feedback "Specific, actionable text the producer can act on to improve grounding."}}
```

The `:score` field is NOT present in the model's output — it is injected by
`gate-banded-output` after `:level` is validated. The model only reports a band.

---

## 5. How to build a custom judge

### Option A — Custom `Scale` with a custom function

1. Define your scale with `scale/discrete-scale`. Every level needs a description.
2. Build your typed output fields with `:reasoning` first, `:level` last.
3. Wire `gate-banded-output` — it validates and injects `:score`.

```clojure
(require '[ai.obney.orc.evaluation.core.scale :as scale])

;; 1. Define a first-class Scale with domain-specific bands (decoupled from criteria)
(def contract-completeness-scale
  (scale/discrete-scale
    {:min 1 :max 5
     :bands {1 "None of company name / budget / timeline / decision-maker present."
             2 "Only one of the four required fields present."
             3 "Two of the four present, or all four but most as thin stubs."
             4 "Three of the four present with adequate detail."
             5 "All four present with sufficient detail."}}))

;; 2. Your judge function follows the same code-executor pattern as the built-ins
(defn contract-completeness-judge
  [{:keys [inputs]}]
  (let [trace-data (:trace-data inputs)
        ;; Call the LLM with reason-before-score output fields
        ;; (field order: :reasoning first, :level last)
        raw  (call-my-judge-llm contract-completeness-scale trace-data)
        ;; 3. Wire the no-run-through gate — throws on empty/bad output;
        ;;    returns output enriched with deterministic :score
        gated (scale/gate-banded-output contract-completeness-scale raw)]
    {:completeness-result gated}))
```

See `EVALUATION-COMPONENT.md` § Custom Judge Functions for a complete worked example using
`judges/call-tier1-judge-llm` as the LLM call.

### Option B — Criteria override on built-in judges (workflow-level `sheet/judges`)

To reuse the built-in judge infrastructure with domain-specific criteria (while keeping
the built-in Scale, stance, and no-run-through gate), override the `:criteria` field at
the workflow level:

```clojure
(sheet/judges
  {:my-grounding
   {:type :grounding
    :criteria "All claims must trace to specific fields in the contract input.
               Budget claims require explicit budget-range field data.
               Do not infer timeline from company size alone."
    :weight 0.4}})
```

`sheet/judges` is where criteria overrides, custom weights, and judge names live for a
workflow. See `EVALUATION-COMPONENT.md` § Defining Judges at Workflow Level.

### Option C — Custom evaluation workflow via `:type :custom` + `:sheet-id`

For domain-specific judges that require their own workflow (multi-step evaluation,
external tool calls, deterministic rule engines):

```clojure
;; Build your evaluation workflow — its blackboard MUST declare :score, :feedback
(def my-judge-sheet-id (sheet/build-workflow! ctx my-eval-workflow))

;; Attach it to a host node
{:type     :custom
 :sheet-id my-judge-sheet-id
 :weight   0.25}
```

See [`ORC-SERVICE-GUIDE.md` § Building a custom judge](ORC-SERVICE-GUIDE.md#building-a-custom-judge)
for the full custom-workflow blackboard contract.

---

## 6. Two deployment modes

A judge is **one evaluation capability**. It is not inherently a pass/fail gate. The same
judge — built-in or custom — is deployable in two modes.

### Mode A — Event-subscribed async learning path

The judge fires out-of-band, after node execution, as a side effect of the event log.

```
:sheet/node-execution-completed  →  judge_runtime processor
                                          │
                                          ├─ fires attached judges in parallel (futures)
                                          │
                                          └─► :judge/score-emitted  (one per judge)
                                                    │
                                                    ▼
                                          consolidator (Gap-3)
                                                    │
                                                    ▼
                                          Living Description body
                                          (strengths / weaknesses / evidence-count)
                                                    │
                                                    ▼
                                          GEPA reflective dataset
                                          → proposer LLM → new instruction candidate
```

The processor (`judge_runtime.clj`, lines 1–19) subscribes to
`:sheet/node-execution-completed` events, reads the attached `:judges` from the host node,
and emits `:judge/score-emitted` events. It is protected by the Living Description opt-in
flag — when the flag is off it returns immediately with zero overhead.

**This is the learning path.** Scores here feed the self-improving loop.

### Mode B — In-pipeline behavior-tree gate

The judge runs inline, inside the workflow execution, and its verdict directly gates flow.
The pattern is: run the judge as a sub-execution (via `:delegate` to the judge sheet), then
use a `condition` node to gate on the returned `:score`. This adds one LLM call's worth of
latency per use.

```clojure
;; 1. Execute the judge as a delegate sub-tree — it writes :score and :feedback
(sheet/delegate "run-quality-gate"
  :target-sheet-id my-grounding-judge-sheet-id
  :reads [:host-inputs :host-outputs :host-instruction]
  :writes [:quality-score :quality-feedback])

;; 2. Gate on the returned score using a standard condition node
;;    (sheet/condition accepts :check {:key :op :value} — not :judge/:threshold)
(sheet/condition "quality-pass"
  :check {:key :quality-score :op :gte :value 0.75})
```

For a built-in LLM judge used in-pipeline, wire `evaluate-trace` as the fn body of a
`sheet/code` node that writes `:quality-score` to the blackboard, then gate with
`sheet/condition` as above.

This is a different deployment of the same judge scoring logic. The judge function itself
does not change.

**Both modes are first-class.** The same judge definition works in both. Improving the
judge improves both paths simultaneously.

---

## 7. How GEPA consumes judge output

GEPA's judge metric is in `components/gepa/src/ai/obney/orc/gepa/core/metrics.clj`.

The `make-judge-metric` function (`metrics.clj`, line 205) returns a function with
signature `(fn [input output] -> {:score :feedback})`:

1. It runs each requested dimension judge in parallel (futures — `metrics.clj` line 229).
2. It calls `weighted-combine` over the per-dimension results to produce a `[0,1]` score.
3. It calls `format-judge-feedback` on the per-dimension results sorted **weakest
   dimension first** — so the proposer sees the biggest problems at the top.
4. It returns `{:score <weighted-mean> :feedback <weakest-first string>}`.

From `metrics.clj`, line 233–234:
```clojure
{:score    (weighted-combine dimension-results)
 :feedback (format-judge-feedback dimension-results)}
```

GEPA's `execute-workflow` path accepts this `{:score :feedback}` map and:
- Uses `:score` as the **fitness signal** — the Pareto frontier selector uses this to rank
  instruction candidates.
- Threads `:feedback` into the **reflective dataset** — this is the mutation input for the
  proposer LLM. The feedback text (including per-dimension reasoning and actionable
  weaknesses, weakest-first) is presented to the proposer as context for why the previous
  instruction scored low.

From `metrics.clj` docstring (line 221–222):

> _"GEPA's execute-workflow path accepts either a bare number or this {:score :feedback}
> map (see todo-processors/score+feedback-of), and threads the :feedback into the
> reflective dataset."_

**Critical note from `default-judge-task` (`metrics.clj`, lines 59–71):** Judges in the
GEPA metric grade the response against a **stable grading task** — NOT the candidate
instruction. If the candidate instruction were used, instruction-following would reward
Goodharting: a deliberately bad instruction that the producer faithfully obeys would score
high. Grading against a stable task means a nonsense answer scores low, which is what
lets GEPA climb from a bad seed to a good instruction.

---

## 8. Coming soon

### Pluggable scales on built-in LLM judges

Currently, the four built-in LLM judges have **sealed 1–5 bands** defined in `rubrics.clj`
(`GROUNDING_SCALE`, `INSTRUCTION_FOLLOWING_SCALE`, `REASONING_SCALE`,
`COMPLETENESS_SCALE`). These bands are not configurable at the judge-attachment site; a
consumer wanting different band wording or a different range must write a complete custom
judge function.

Coming: the ability to supply a custom `Scale` artifact to an existing built-in judge type
directly — e.g., pass `{:type :grounding :scale my-domain-scale}` in `sheet/judges`
without rewriting the stance, the output fields, or the instruction composition. This
closes the gap between property-1 (scale is decoupled) and the current `sheet/judges` API,
which does not yet expose the Scale slot for overriding.

### Tier-2 patterns (deferred, documented)

Three patterns were evaluated and deferred (see [Section 2](#2-design-decisions)):

1. **Hierarchical verify** — a second model grades the judge's verdict. The high-value
   case is catching systematic judge miscalibration on a specific domain. Implementation
   note: the verifier should be a different model family from the original judge to avoid
   correlated failures. Cost: doubles LLM calls per evaluated tick.

2. **Ensemble + variance** — run N judges (same or different models) and use both the mean
   score and the variance as signals. High variance = uncertain region; low variance + low
   score = real quality failure. Informs exploration vs exploitation in GEPA candidate
   selection.

3. **Pairwise GEPA metrics** — instead of absolute scoring, present the proposer with two
   candidates and ask "which is better and why?" Requires a new proposer input format;
   earmarked for GEPA v2.

These are tracked in the backlog. None are in the current codebase.
