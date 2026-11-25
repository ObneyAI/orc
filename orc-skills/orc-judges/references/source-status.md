# Source status and verified limits

The inspected framework revision is ORC
`490816267b76b0428c53437052980a25f5905209`, with Grain
`dbf5b5229aad09785e40f71e96adf65c90f971d5`. Recheck consuming versions before
assuming these limitations remain. Controlled public-interface runtime proofs use
real Grain commands/processors, SQLite events and LMDB projections; deterministic
judge capabilities establish mechanics, not live model quality.

## Merged source correction

At `250de6e6de2ad95647ef6da4c69220c2bd631279`, source inspection establishes explicit attachment support for any existing sheet node and enablement independent of Living Description. The flag controls default researcher judges only. Durable assessment identity is subject completion ID + judge name + judge revision, with requested and scored/failed/ungradable outcomes. These replace the historical table's attachment, enablement and missing assessment-identity limitations. Attached assessment execution still uses process-local futures; pending durability alone does not prove restart recovery or capacity admission. Managed GEPA has its own persisted task recovery and metric-restoration contract; see [GEPA readiness](gepa-readiness.md).

This correction is source review, not a rerun of the historical experiments. Keep unrelated historical findings qualified until their exact current paths are verified.

## Existing capabilities and reproduced findings

| Area | Verified behavior or limit |
| --- | --- |
| Built-ins | Grounding, instruction following, reasoning and completeness select described bands and derive scores |
| Band validation | Missing/out-of-range/fractional levels rejected; blank/nil band descriptions accepted by constructor |
| Attachments | Leaves supported; composites/delegates rejected through public command |
| Enablement | Standalone attachment and direct custom execution succeed, but automatic judging requires the Living Description flag |
| Custom composition | A native attached custom judge can delegate to a parallel specialist with controlled capabilities |
| Evidence | Resolved host values supplied; custom host trace remains a node-ID summary |
| Score-only | Custom score-only result becomes a native score event with empty-string feedback |
| Repeated nodes | Three independently captured per-item judgments can yield one persisted native score |
| Dimensions | Incomplete custom dimension normalized to invented zero score, weight one, empty name/feedback |
| Selected aggregation | Unselected default dimensions inserted with score 0.5 |
| Exported sheets | Public build fails when recursive trace-schema registry is lost by DSL flattening |
| Provenance | Score event can retain model provenance while native score projection drops it |
| Assessment recursion | Bounded asynchronous host→judge A→judge B shows private depth does not confine that processor path |

These findings are characterization, not repaired behavior. Do not hide failed
native obligations by calling an application-owned alternative native support.

## Verified operational behavior and remaining limits

Three held deterministic judge bodies were observed active concurrently, while the
completion processor checkpoint advanced before any score existed. This proves
asynchronous execution, not hardware-aware capacity scheduling or durable jobs.
A controlled 200 ms replacement for the internal 60-second collector deadline
finished collection while its held body remained active and uncancelled; releasing
that body later produced no score. The original deadline was not waited out in a
paid test. Direct public completion-handler reentry invoked work three times while
score recording retained one score. This is an invocation-deduplication probe,
not an ordinary Grain poller replay or crash/restart demonstration.

Durable bounded scheduling, machine-capacity admission, crash/restart recovery,
general shutdown safety, native poller replay and richer scope ownership remain
unproven or absent at this revision. Native composites report successful judges
without a complete-panel coverage contract; persist expected assessment coverage
through an explicitly owned application seam.

Native score identity lacks execution-instance/completion-event and rubric revision.
The projection does not preserve selected band, detailed evidence or custom judge
child identity. A richer application assessment must preserve these deliberately.
Named query schemas are not evidence of registered reporting query handlers.

## Intended contracts

The desired production behavior supports individual executions, subbehaviors,
trees and explicit groups; durable pending work; bounded capacity; restart recovery;
distinct replay/execution identity; observable failures and optional feedback.
These are requirements for implementation or repair where current native support
does not satisfy them. An arbitrary float score without described bands does not
satisfy the grading contract.

## Public source anchors

- [Scale and gate](https://github.com/ObneyAI/orc/blob/490816267b76b0428c53437052980a25f5905209/components/evaluation/src/ai/obney/orc/evaluation/core/scale.clj)
- [Judges and aggregation](https://github.com/ObneyAI/orc/blob/490816267b76b0428c53437052980a25f5905209/components/evaluation/src/ai/obney/orc/evaluation/core/judges.clj)
- [Completion handler, custom execution and score projection](https://github.com/ObneyAI/orc/blob/490816267b76b0428c53437052980a25f5905209/components/evaluation/src/ai/obney/orc/evaluation/core/judge_runtime.clj)
- [Score recording commands](https://github.com/ObneyAI/orc/blob/490816267b76b0428c53437052980a25f5905209/components/evaluation/src/ai/obney/orc/evaluation/core/commands.clj)
- [Attachment command](https://github.com/ObneyAI/orc/blob/490816267b76b0428c53437052980a25f5905209/components/orc-service/src/ai/obney/orc/orc_service/core/commands.clj)
- [Evaluation behavioral specification](https://github.com/ObneyAI/orc/blob/490816267b76b0428c53437052980a25f5905209/specs/evaluation.allium)

## Live evidence and its limits

Real provider requests verified built-in/custom attached judges, delegated and
parallel judge workflows, native Jev Score calls inside code nodes, and durable
application reporting. A small 25-case construction suite per model exercised
band selection, omissions, unsupported assertions and adversarial artifact text.
Those manually reviewed construction ranges are not human-gold labels or a
held-out accuracy benchmark. They establish observed behavior on those examples,
not general judge accuracy, production reliability or safe confidence thresholds.

Fresh author trials retain failed provider-contract responses and their exact
public-boundary replays. A valid schema cannot guarantee a provider follows it;
validate every nested output and record failed/ungradable work without a score.
An HTTP 200 with a malformed-function-call finish is also an evaluator failure.
Inspect provider evidence as well as the returned tree status and report coverage.

## Revision context

The cited `4908162` observations are historical. Decision/tool APIs were inspected at ORC `1851212396cced2fc4533fda30b824bd4b691390`; see [current source status](../../SOURCE-STATUS.md) for the public integration checkpoint. Preserve those original experiment claims at their original pin; use current owning skills for supported integration.

Current GEPA reflection behavior and adapter limits are described in [GEPA readiness](gepa-readiness.md); the convenience judge metric's output extraction is the remaining limit. Focused probes at `9f7c27357b9f9bcf765fbd86baaedf5911261cd8` do not re-establish
every historical background-runtime limitation above and do not demonstrate a
full optimization run. Judge scheduling, assessment persistence and optimizer
metric consumption require separate revision-specific verification.
