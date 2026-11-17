# Judges and feedback for GEPA

Use this readiness check when assessments become optimization evidence. It is not
a verified end-to-end optimization recipe. Native background judging, custom judge
trees and GEPA metric calls are distinct paths; verify the one actually consumed.

## Use the judge the job needs

Built-in dimensions are options. A task-specific judge can be a registered
subbehavior that gathers authoritative evidence, checks exact domain invariants,
delegates semantic criteria, and reconciles the results into a described band and
feedback. Use [judge composition](compose-judges.md) to select that structure.
Deterministic checks can establish arithmetic, identity or committed effects;
semantic checks assess meaning, sufficiency and source support. Preserve which
criterion produced each finding and any critical requirement that limits the
overall band. An average of unrelated passing checks must not erase it.

An explicit optimization metric can invoke that judge subbehavior with the selected
producer artifact and its evidence, then consume its validated score and feedback.
The judge's own goal, rubric and evidence contract stay fixed while the producer
instruction changes. Verify the whole delegated assessment and feedback path;
generic built-in response extraction is not a prerequisite. This customization
does not repair the optimizer's example-attribution or assessment-failure defects
described below. Do not substitute a producer's own success verdict for an
independent task assessment.

## Establish the assessment contract

- Identify the assessed execution, dataset example, candidate instruction version,
  exact output artifact, rubric and evidence versions. Keep these identities with
  the score and feedback through persistence, reflection and replay.
- Select output keys or an explicit projection. A leading rationale string is not
  interchangeable with a structured decision or specialist deliverable. Include
  relevant inputs, authoritative sources and actual tool effects. Use
  [evidence and runtime](evidence-and-runtime.md) and
  [blackboard contracts](../../orc-blackboard-contracts/SKILL.md) for context flow.
- Hold the task and rubric stable while varying candidate instructions. Judge
  against the intended goal, not whether the candidate obeyed its newly weakened
  instruction. Use described bands and [calibration](rubrics-and-calibration.md).
- Require feedback that names the unmet criterion, supporting evidence and useful
  correction. A score restatement, empty text or evaluator exception is not
  improvement feedback. Evidence can justify several valid routes; avoid labels
  that unnecessarily mandate one specialist itinerary.
- Preserve evaluator failure, pending work, insufficient evidence and genuine
  lowest-band performance separately. Report assessment coverage through
  [reporting](reporting.md); exclude failed assessments from ordinary fitness.

Score-only decision judges can support monitoring. They do not generate feedback.
If another evaluator supplies feedback, preserve its separate provenance and
verify that it concerns the same execution and rubric; do not present an invented
explanation as the score-only model's reasoning.

## Keep datasets and attribution distinct

Use execution history and reviewed synthetic cases to target an observed weakness.
Record label provenance: a successful tick and a producer's own approval are not
correctness labels. Include incomplete evidence, changed constraints, completed
effects and legitimate alternative solutions when relevant to the weakness.

Distinguish development/training examples, candidate-selection examples and an
untouched final evaluation set. Record which sets feed reflection. Any example
used to revise or select instructions is no longer an untouched final holdout.

Trace one deliberately distinct example through input → produced output → judge
evidence → grade/feedback → persisted record → reflection input. Repeat with
reordered examples and different training/selection lengths. Index equality across
two datasets does not establish example identity. Verify the optimized instruction
actually reaches its target provider. For native decisions, verify instruction
overrides and native question context through
[decision models](../../orc-decision-models/SKILL.md) and
[model/SIO setup](../../orc-model-sio/SKILL.md).

## Merged managed evaluation and recovery

At `250de6e6de2ad95647ef6da4c69220c2bd631279`, managed GEPA evaluation uses a durable task ledger, task leases, persisted metric descriptors and processor-owned recovery. Source inspection of `gepa/core/task_execution.clj` shows original tick/result reuse; `core/metric_runtime.clj` returns failed status for metric exceptions and unknown status for timeout, not zero fitness. Pending/recovery results carry no grade. Do not apply the historical outer-exception-to-zero finding below to this managed path. This review does not reverify every historical artifact-selection or dataset-attribution boundary.

Restore custom metrics before workers consume persisted work:

```clojure
(gepa/register-metric! context persisted-descriptor metric-fn)
(gepa/recover-in-progress! context)
```

The first public API restores the trusted two-argument metric under tenant, metric ID and revision. Missing registration yields `:recovery-required`; it does not silently select a default. Load GEPA's public interface to register processors and its periodic trigger, then start and own the Grain schedules explicitly. The second API requests recovery through those processors, leaving healthy ownership and completed boundaries alone. Restore providers and other trusted capabilities too. Metric retries require the persisted retry-safe policy, remaining attempts and budget; they reuse original workflow output. A timeout does not prove remote work stopped.

This managed path is distinct from attached judge assessments: those have durable requests/outcomes but still launch process-local background futures. GEPA recovery does not establish recovery or bounded admission for attached judges. The API/source inspection is not a fresh-process runtime proof or a demonstrated quality improvement.

## Verified adapter limits at a pinned revision

Focused deterministic probes at ORC
`9f7c27357b9f9bcf765fbd86baaedf5911261cd8` established:

| Boundary | Observed behavior | Consequence |
| --- | --- | --- |
| Reflection example construction | Validation outputs/feedback were joined to training inputs by index | Different datasets produce false input/output/feedback pairs |
| Metric exception | Successful workflow plus thrown evaluator became completed zero fitness with empty feedback | Evaluator outage can look like poor specialist performance |
| Default artifact extraction | A leading noninput rationale string was selected instead of a structured output under another key | Judge assessed the wrong artifact |
| Judge provider exception | Built-in judge metric converted failure into score zero and error-text feedback | Invalid assessment entered ordinary score aggregation |

The first two probes used public optimization with real Grain commands, SQLite
events and projections; reflection selection was inspected at its internal builder
boundary. The latter two used the public judge metric and real built-in judge
construction with only the model-provider boundary injected. Positive controls
verified canonical answer delivery, fixed task delivery and valid lowest-band
scoring. These prove adapter mechanics, not model judgment quality or successful
optimization. No full GEPA improvement run is established by these probes.

Inspect `gepa/core/todo_processors.clj` (validation evaluation, metric exception
handling, reflective dataset construction) and `gepa/core/metrics.clj` (response
extraction and judge-dimension failures) in the consuming revision. Keep repaired
and historical behavior separate; rerun the affected boundary before dropping a
qualification. Background judge repairs alone do not establish these fixes.

Until artifact selection is explicit, the convenience judge metric is unsuitable
for ambiguous multi-output evidence. An explicit metric can own artifact
selection and evidence assembly, but does not repair optimizer dataset joins or
outer exception handling. Verify those independently before optimization. Reproduce
and hand off framework defects rather than compensating with misleading scores,
duplicated training/validation sets, or error-string parsing.

After the boundaries pass, optimize one measured weakness with unrelated topology
and instructions fixed. Evaluate untouched cases and the assembled tree; retain
failed candidates and report the limits of observed transfer.
