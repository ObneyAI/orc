# Judges and feedback for GEPA

Use this readiness check when assessments become optimization evidence. Training in
rounds, reflection pairing, component rotation, reflection templates, the
reflection token budget and provider cost are described below. Optimization quality remains task-specific:
judge it on an untouched holdout. Native background judging, custom judge trees and
GEPA metric calls are distinct paths; verify the one actually consumed.

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
generic built-in response extraction is not a prerequisite. Do not substitute a producer's own success verdict for an
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
Distinct training and validation sets are supported: reflection draws only from
the parent's evaluation on a training minibatch, and the validation set never
feeds it.

Trace one deliberately distinct example through input → produced output → judge
evidence → grade/feedback → persisted record → reflection input. Repeat with
reordered examples and different training/selection lengths. Index equality across
two datasets does not establish example identity. Verify the optimized instruction
actually reaches its target provider. For native decisions, verify instruction
overrides and native question context through
[decision models](../../orc-decision-models/SKILL.md) and
[model/SIO setup](../../orc-model-sio/SKILL.md).

## Merged managed evaluation and recovery

At `250de6e6de2ad95647ef6da4c69220c2bd631279`, managed GEPA evaluation uses a durable task ledger, task leases, persisted metric descriptors and processor-owned recovery. Source inspection of `gepa/core/task_execution.clj` shows original tick/result reuse; `core/metric_runtime.clj` returns failed status for metric exceptions and unknown status for timeout, not zero fitness. Pending/recovery results carry no grade.

Restore custom metrics before workers consume persisted work:

```clojure
(gepa/register-metric! context persisted-descriptor metric-fn)
(gepa/recover-in-progress! context)
```

The first public API restores the trusted two-argument metric under tenant, metric ID and revision. Missing registration yields `:recovery-required`; it does not silently select a default. Load GEPA's public interface to register processors and its periodic trigger, then start and own the Grain schedules explicitly. The second API requests recovery through those processors, leaving healthy ownership and completed boundaries alone. Restore providers and other trusted capabilities too. Metric retries require the persisted retry-safe policy, remaining attempts and budget; they reuse original workflow output. A timeout does not prove remote work stopped.

This managed path is distinct from attached judge assessments: those have durable requests/outcomes but still launch process-local background futures. GEPA recovery does not establish recovery or bounded admission for attached judges. The API/source inspection is not a fresh-process runtime proof or a demonstrated quality improvement.

## Training in rounds

This section describes ORC with the GEPA continuation changes (branch
`fix/gepa-durable-continuation`, pending merge). Earlier
revisions do not continue training this way, so check that the consuming
revision includes them before relying on it.

A completed optimization is final: `resume!` continues only an interrupted run
and answers `:already-terminal` for a completed one. To keep training, start a
new `optimize!` on the same workflow. It inherits the best candidates of the
earlier runs and continues from where they stopped, so a budget split into
rounds adds up to one run of the same total.

What carries over to a new run:

- **The minibatch schedule.** Training minibatches follow one deterministic
  schedule. A run that inherits continues it from the furthest point reached by
  the runs it inherits from, so it never repeats their minibatches in order. A
  run that inherits nothing starts the schedule from its beginning.
- **The best candidates.** By default, the Pareto candidates of every completed
  earlier run of the workflow: one per distinct instruction set, best aggregate
  first, at most `:inherit-limit` in `:config` (default 10). With
  `:inherit-from <optimization-id>`, only that run's Pareto candidates, even
  when `:inherit-from-previous` is false. With `:inherit-from-previous false`,
  at the top level of `optimize!` or in `:config`, none. Only completed runs
  pass on candidates, so resume an interrupted round to completion first. Each
  inherited candidate carries the mutation reason
  `"inherited-from-previous-run"` and its `:source-optimization-id`.
- **Their recorded scores.** A score is recorded per example under a
  fingerprint of the example content, the workflow content hash and the metric
  identity (kind, id, revision, configuration). A starting candidate (each
  inherited one, and the seed when an identical instruction set was scored
  before) reuses every recorded score whose fingerprint matches, in any order,
  and is evaluated only on the examples without one. A new or edited example
  costs one metric call per starting candidate; an unchanged one costs nothing
  wherever it moved. An edited workflow, including its seed instructions, or a
  changed metric reuses nothing. When one instruction set appears in several
  runs, its scores merge by the maximum per fingerprint.

How a run spends its budget:

- One mutation round is undecided at a time. The seed and the inherited
  candidates are evaluated before the first round opens.
- A round opens only when the budget left can finish it: two minibatch
  evaluations plus one full validation pass.
- Parents come from the Pareto front with dominated programs removed.

Measured:

- Deterministic chain, each round in its own process on one SQLite file,
  repeated 5 times: four rounds of 30 metric calls reached the same best as one
  run of 120 in every repeat (0.33, 0.50, 0.83, 1.00).
- Live, with a small model through OpenRouter on a six-example labeling task,
  each round in its own process on one SQLite file: round 2 inherited round 1's
  candidates at 0 calls with equal scores and continued the schedule. Round 3,
  after one validation example was added at the front and one was edited,
  charged each starting candidate exactly 2 calls and scored only those two
  examples. Round 4 ended its own process mid-run; `resume!` in a new process
  finished it with only the 11 missing evaluations, and nothing was scored or
  charged twice. Every round's best was already 1.00 on this easy task, so the
  live check proves the mechanics, not score gains.

Every round needs:

- **One durable event store** (SQLite or Postgres) shared by all rounds. The
  in-memory store, which test contexts use, keeps nothing after its process ends.
- **The same workflow name.** `build-workflow!` derives the sheet id from the
  name, so rebuild under that name in each process.
- **A stable metric identity.** Pass `:metric-id` with a custom `:metric-fn`,
  and a new `:metric-revision` whenever the scoring changes. Without
  `:metric-id` each run gets a fresh identity, so no recorded score is reused.
- **A task step that always answers.** Give each model step enough output
  tokens for any reasoning the model does first. In the live check, a 64-token
  cap made both a small Anthropic model and a reasoning model return no answer
  text on some calls, and each such call stalled its round (see below).

Recovering an interrupted round:

- The run's state is in the durable store. In a new process, rebuild the
  workflow under the same name and restore the metric with
  `gepa/register-metric!`, passing the stored `:metric-descriptor` (in
  `:gepa/optimization-started`) and the function, or pass `:gepa/metric-fn` in
  the context. The registration `optimize!` made does not survive the process.
- Then call `gepa/resume!` until it answers `:already-terminal`. Each call
  advances the first missing step (a candidate evaluation, a parent minibatch,
  a proposal, a subsample), and the processors carry on from there. An
  application that runs GEPA's processors and periodic tasks can instead call
  `gepa/recover-in-progress!` (see "Merged managed evaluation and recovery").
- A task held by the process that died is taken over only after its lease
  lapses (`:gepa/task-lease-ms` in the context, default 90 seconds); until then
  `resume!` answers `:resumed` without progress.
- Nothing already scored is scored again, and no attempt is charged twice.
- A workflow call that fails, such as a model step that returns no usable
  output, is not retried. The task records `:gepa/task-recovery-required` with
  reason `workflow-failed`, the run stays `:running`, and `resume!` does not
  retry it. Inspect those events, fix the cause (often the token cap above) and
  run the round again.

On a durable store, concurrent evaluation tasks can conflict on a ledger write.
The ledger retries a conflict that the store reports under a key named `cas`,
with jittered backoff, up to 200 times; `:gepa/ledger-retry` in the context sets
`:max-attempts` and `:max-backoff-ms`. A refusal that the ledger decides
itself, such as a second result for a finished task, is returned at once.
When the retries run out, the evaluation stops at a recoverable boundary and
the run waits until the task's lease lapses (`:gepa/task-lease-ms` in the
context, default 90 seconds); a blocking `optimize!` returns `:timeout` in the
meantime. Then `resume!` takes the task over and continues the run; earlier calls
answer `:resumed` without progress. An application that runs GEPA's periodic
tasks recovers it through the 30-second recovery scan without a call. Without
either, as in a test context, the run stays `:running`, so a caller that drives
GEPA directly calls `resume!` until it answers `:already-terminal`.

Before relying on continuation, run two short rounds on the durable store and
check round 2: the inherited candidates and their source runs in
`:gepa/candidate-created`, that they were charged 0 metric calls in
`get-progress`, and that its first minibatch continues round 1's schedule.

## Configuring reflection

Reflection records, per round, the parent, component,
iteration and distinct minibatch indices (`:gepa/parent-minibatch-evaluation-requested`,
a durable boundary that `resume!` re-runs). It evaluates the parent on that
training minibatch and builds every reflective example from those results, so
each example's inputs, generated output and feedback belong to one training
example. A parent perfect on its minibatch is skipped before any proposer call;
the acceptance gate reuses the parent's evaluation, as the Python GEPA library does.

Components rotate per candidate, always: round-robin over sorted component
names. A round that records a proposal advances the pointer; a skipped perfect
parent does not. A child starts at the furthest pointer among its parents.

Options available from ORC `4025888bf`; each defaults to GEPA's behavior:

- `:reflection-prompt-template`: one template, or a map from component name
  (string keys) to template. Both `<curr_param>` and `<side_info>` are required;
  startup validation checks only that each template is a string with those
  placeholders. A map key that matches no
  component is ignored, and components without a template use the default.
- `gepa/general-rules-prompt-template`: GEPA's template plus three sentences
  asking for general rules instead of copied examples, and for leaving out
  values each input already carries. In one A/B on a
  decision step (one run per arm, distinct sets) the default template's three
  rewrites quoted seven full training sentences and copied example names and
  values; the general-rules template's quoted none in full. A later run with it
  still quoted one short line in some rewrites. The best held-out aggregate was
  equal (0.767), so the gain is in copying, not in score. Use it when task inputs carry their own facts. The default asks the
  rewrite to include domain facts, which suits tasks whose inputs do not.
- `:max-reflection-tokens`: summed from the proposer-call evidence, checked when a
  round opens and before each proposer call (one call may overshoot by its own
  usage). It finishes like the metric budget; progress and `resume!` report the tokens
  used.
- A provider-reported cost is kept in the proposer-call evidence. OpenRouter
  returns it when asked, for example
  `:reflection-options {:extra-body {:usage {:include true}}}`.

## Acceptance and noise

In GEPA and in ORC the minibatch check is a cheap filter that decides which
proposals receive a full validation evaluation; the best candidate is chosen by
validation scores. Task-model noise can let a weak proposal through the filter
(wasted calls) or filter out a good one. It does not choose the winner. To reduce
noise:

- use a larger validation set (task-model calls are usually cheap);
- use a larger `:reflection-minibatch-size`;
- remove workflow components that never run;
- repeat the check on the top candidates;
- judge the result once on an untouched holdout.

## Adapter limits

Checked against the GEPA source in ORC (`core/metrics.clj`, `core/todo_processors.clj`,
`core/metric_runtime.clj`, `core/task_execution.clj`):

- **Evaluator failure.** A thrown or timed-out metric, or a failed workflow, is not
  scored. Managed evaluation returns a failed or unknown status from
  `metric_runtime.clj`, and the task ledger and `task_execution.clj` record it with
  no grade. A metric result outside 0 to 1, non-numeric, or with non-string
  feedback is also failed (`:invalid-metric-result`). When the parent's minibatch
  evaluation ends with such a status, the round stays pending for ledger recovery
  and `resume!` (`todo_processors.clj`). The unmanaged arities throw with
  `:gepa/evaluation-status :failed`. A built-in judge dimension that throws
  raises instead of returning a score (`run-judge-dimension`, `metrics.clj`).
- **Default artifact selection.** The convenience judge metric's `extract-response`
  (`metrics.clj`) uses a string output as is, and otherwise takes the first present of the keys `answer`,
  `assistant-response`, `response`, `output` and `result` (a non-string value under one of these keys is taken as is); otherwise the first
  string value whose key is not an input key; otherwise any string value; a map
  with no string value is printed whole. A string rationale under a noninput key
  is therefore chosen over a structured output under another key. Unsuitable for
  ambiguous multi-output evidence; an explicit metric owns artifact selection and
  evidence assembly.
- **Not established.** Model grading quality and optimization improvement are task
  specific; these checks cover adapter mechanics only.

Inspect `gepa/core/todo_processors.clj` (validation evaluation, reflective dataset
construction) and `gepa/core/metrics.clj` (response extraction, judge dimensions)
in the consuming revision, and rerun the boundary you depend on. Background judge
behavior does not establish GEPA behavior.

After the boundaries pass, optimize one measured weakness with unrelated topology
and instructions fixed. Evaluate untouched cases and the assembled tree; retain
failed candidates and report the limits of observed transfer.
