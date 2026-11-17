# Durable quality reporting

## Choose the measurement unit

Record an assessment's exact scope: execution, specialist/subbehavior, tree or
selected group. A group has explicit immutable members and an inclusion policy.
A human-friendly session label is metadata; it does not establish query scoping.
Filter by actual tenant and subject identities, lineage, rubric/model version and
time boundaries where requested.

Keep execution attempts separate from the delivered task. Retried actions and
extra intermediate steps should not silently gain more weight in the final task
score. Group an execution's attempts deliberately when the rubric asks about its
final outcome, retaining the attempt evidence and policy.

## Report coverage next to grades

Use application-owned lifecycle states with explicit meanings:

| State/count | Meaning |
| --- | --- |
| Eligible | Completions satisfying the declared assessment eligibility policy |
| Expected/selected | Eligible units chosen for assessment, before judging effects |
| Pending | Selected assessment waiting for evidence or worker capacity |
| Scored | Valid selected-band judgment with adequate evidence |
| Failed | Provider, extraction, validation or execution failure |
| Ungradable | Available evidence or ambiguity prevents a defensible band |
| Skipped by policy | Eligible unit deliberately excluded, for example by sampling |

For a mutually exclusive final/current-state projection, selected assessments equal
scored + pending + failed + ungradable. Retries add invocation attempts, not new
selected subjects unless explicitly requesting another assessment. Failed and
ungradable records are not zero grades. A score-only assessment can be fully scored
with feedback absent.

Report band distribution, counts and coverage before or alongside a mean. A rising
mean among survivors can hide collapsing judge coverage. Keep optional feedback
presence visible and record sampling rates/strata. Sampling selects work; worker
admission controls when selected work executes.

## Preserve metric meaning

The primary grade is the selected band's deterministic unit score. Keep Jev's
probability-weighted score as a separate metric. Aggregate like measurements under
the same rubric or explicitly document cross-rubric meaning and weights.

Show each expected criterion and whether it scored. A partial panel is incomplete;
do not silently renormalize surviving weights into a fully assessed result. You
may publish a clearly labeled partial statistic with coverage and included weights.

Averages across child executions are operational performance statistics. They do
not replace a separate holistic assessment of the assembled artifact or root goal.
Likewise claim-support precision is not completeness, and a tool success count is
not proof that the task was satisfied.

## Build reports through Grain

Use commands to validate intent and emit durable assessment/lifecycle events.
Build read models from those events and expose authorized tenant-scoped queries.
Preserve subject/completion identity, artifact/evidence revision, rubric revision,
sampling policy, selected band, derived score, optional feedback, model provenance,
provider attempts and evaluation lineage.

Keep raw model assessments immutable. Human adjudication, rejudging or a new rubric
creates a separate record linked to the earlier assessment. A single mutable grade
destroys evidence needed to explain changes.

The native score event/projection at the inspected ORC pin does not preserve all
these fields. Its public score reader addresses a sheet/node/tick tuple. Rich
tree/group reports and lifecycle coverage require an explicit application model or
an engine extension. Query schemas without registered handlers are not working APIs.

Verify command results, durable events and public projected readback. Rebuild a
fresh projection after restart and compare identities and outcomes. A returned
report map does not prove that the data survives restart.

For feedback consolidation, include only actual feedback, retain source assessment
IDs and disclose missing feedback. A model summary can organize observations; it
cannot create explanations for score-only judgments. Do not feed score-only records
to consumers whose contract requires actionable feedback.

Use [orc-grain-application](../../orc-grain-application/SKILL.md) for schemas,
commands, processors, read models, authorization and durable query contracts.
