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

## Preserve accounting through learning provenance

Reflection completions carry integer token counts and an optional numeric cost.
Description and claim-delta provenance must accept that same accounting contract.
Treating every usage entry as an integer rejects valid fractional cost and can
prevent a successful reflection from producing its successor event. The shared
provenance usage schema validates known fields and retains additive provider
accounting details; malformed token counts remain rejected.

Completion accounting is distinct from full provider observations. The successor
inherits completion totals and cost. Detailed provider usage stays on the
execution-owned provider-attempt event. Direct provenance commands also preserve
any supplied compatible provider details, including explicit unavailable cost.

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

The reconciliation checklist tracks eleven focused obligations. Local verification
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

## Parallel responses and missing-output diagnosis

Each parallel child must supply its own declared outputs. A controlled predictor
must choose fields from the requested module, not from invocation order. The
regression exercises both arrival orders and verifies exact canonical values and
producer identity. Required-output validation remains enabled.

Ordinary missing-output failures now retain `:missing-write-keys` through the
completion event and public trace, matching the schema-error policy's existing
diagnostic contract. Retry behavior is unchanged. Present invalid values are not
classified as absent, and required nullable values must actually be supplied.
The response retained on a failed completion is diagnostic evidence; accepted
blackboard writes must be verified from the canonical value log.

A real-provider witness ran two parallel LLM leaves with disjoint described
inputs and strict output contracts, followed by a code join. Both calls succeeded.
The captured wire requests included the declared descriptions and schemas; each
raw response decoded to its own canonical writes and durable provider invocation.
The joined values matched the supplied records. A fresh process reconstructed the
closed snapshot exactly without adding events (153 events). This bounded witness
verifies parallel dataflow, not general-agent quality.
