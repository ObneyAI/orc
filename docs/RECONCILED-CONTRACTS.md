# Reconciled model contracts and judge integration

## What is combined

The judge-assessment branch is the integration base. Input-field guides and the
structured-output/provider-observation repairs are reconciled on top of it.
The ontology architecture worktree is separate and contributes no changes here.
SIO and litellm-clj are pinned to immutable commits reachable from their main
branches, following SIO PR 14 and litellm-clj PR 2. No local dependency path is
required by consumers.

## Build model contracts from the blackboard

Use typed, described blackboard entries and explicit node reads/writes. Input
guides explain the fields actually supplied, including nested fields. Native
decision requests omit absent inputs while preserving explicit null, false and
empty values. Marker prompts retain their established ordering; conversational
structured requests and native decision questions carry the guide with the
instruction in their respective formats.

Ordinary LLM nodes can select `:options {:output-mode :json-schema}` or inherit
that mode from provider configuration. The compiled output schema and paired
decoder share the same contract; meanings also appear in model-visible text.
Strict JSON mode is currently implemented for blocking OpenRouter calls; strict
streaming is rejected explicitly. The compiler supports bounded scalar/enum/vector/closed-map structures.
Unsupported or ambiguous contracts fail before provider dispatch. It does not
silently downgrade a strict request to markers or repair model field names.
An ordinary node's `:model` is an actual provider model ID, not a registered
provider alias. Native decision provider names have their own resolution path.

Absent outputs do not become generated nulls. A required nullable output must
actually be present. Explicit null survives value-log reads and references;
declared string map keys stay strings. Missing required writes are recorded as
`:missing-write-keys`, separately from rejected supplied values. This preserves
judge missing-feedback classification without inventing a rejected answer.

## Observe attempts without mixing them with answers

Provider reasoning/refusal/usage observations are optional provider evidence.
They are not blackboard outputs. Command-owned provider-attempt events preserve
the canonical node-start, execution context, logical/node/provider attempt and
invocation identities. Public `provider-attempts` reads can select the exact
node start and invocation. Failed parsing may retain observations without
accepting generated domain writes. An observation persistence failure does not
justify repeating the provider operation.

Returned provider evidence is distinct from the exact outgoing HTTP body. The
latter still requires a controlled transport capture. Never claim a reconstructed
prompt or normalized response is a verbatim wire capture.

## Judge the intended boundary

Score-only native judges and conversational band-plus-feedback judges can assess
the same specialist independently. Custom judges are workflows with their own
typed inputs and outputs. Preserve exact subject completion identity when a
specialist runs in parallel or is called again later.

A composite's `:host-outputs` contains its forwarded outputs, not an automatic
union of every intermediate result. Explicitly publish a joined result when that
is the behavior's output. Declare `:host-family` when the judge needs the full
execution-family view. Keep transport and enclosing workflow deadlines aligned;
a test capture layer with a shorter hard maximum can reject a valid inherited
ORC timeout before any provider request is sent.

## Verification and limits

The reconciliation checklist tracks nine focused obligations. Local verification
includes real command/event/projection paths, controlled HTTP contract tests,
real strict generation and native model calls, and cold reconstruction.
The live compatibility run completed nine model calls and seven assessments:
five ordinary generations, four native decisions, three score-only assessments,
four feedback assessments and one guarded code-tool invocation. It checked
parallel/repeated child ownership and every judge's actual evidence inputs.
Fresh-process reconstruction matched events, execution families, assessments,
assessment reports and provider-attempt projections exactly.

CI lifecycle fixes use matching checkpoint-poller/pubsub cleanup APIs and wait
for the complete expected trace revision. They do not remove output assertions.
The deterministic restart proof does not itself establish live GEPA training.

Known separate work remains: durable judge capacity/claims/restart recovery,
some execution-family summary/explicit-null limitations, and SIO's independent
required-input validation behavior. Canonical tick-local value-log evidence is
the verified source used here. This reconciliation does not finish the broader
complex-agent research or establish superiority over another agent framework.
