# ORC Allium distillation coverage

This ledger defines the scope of “every part of ORC” against the production
component directories in `components/`. A component is complete only after its
public boundary, domain state, significant rules, and cross-component contracts
are represented in a checked Allium specification.

| Component | Domain | Spec | Status |
|---|---|---|---|
| `agent-browser` | Agent-oriented browser interaction | `agent-browser.allium` | Distilled and checked |
| `colbert` | Late-interaction indexing and retrieval | `colbert.allium` | Distilled and checked |
| `evaluation` | Rubrics, judges, scoring, and feedback | `evaluation.allium` | Distilled and checked |
| `file-store` | Storage contract | `file-storage.allium` | Distilled and checked |
| `file-store-local` | Local implementation of storage contract | `file-storage.allium` | Distilled and checked as adapter |
| `file-store-s3` | S3 implementation of storage contract | `file-storage.allium` | Distilled and checked as adapter |
| `gepa` | Pareto-based prompt optimization | `gepa.allium` | Distilled and checked |
| `grain-test-utils` | Test-only Grain helpers | — | Excluded: test infrastructure |
| `langfuse` | Trace destination adapter | `observability.allium` | Distilled and checked with the managed exporter lifecycle exposed by `orc-service` |
| `llm` | Structured provider prediction and streaming | `llm.allium` | Distilled and checked |
| `mcp-sheet-builder` | Workflow generation and portable MCP transport lifecycle | `mcp-sheet-builder.allium`, `mcp-client.allium` | Distilled, specified, and verified through DET-E2E-145 |
| `ontology` | Ontology discovery, evolution, and retrieval | `ontology.allium` | Distilled and checked |
| `orc-service` | Behavior-tree execution and RLM runtime | `orc-service.allium` | Distilled and checked |
| `predict-rlm-image-tools` | Image benchmark deterministic transforms | `predict-rlm-tools.allium` | Distilled and checked |
| `predict-rlm-invoice-tools` | Invoice workbook transform | `predict-rlm-tools.allium` | Distilled and checked |
| `predict-rlm-pdf` | PDF page rendering and text extraction | `predict-rlm-tools.allium` | Distilled and checked |
| `predict-rlm-redaction-tools` | Exact document redaction transform | `predict-rlm-tools.allium` | Distilled and checked |

Bases, projects, development scripts, deployment configuration, fixtures, and
tests are evidence for these domains rather than separately governed product
domains. Any behavior found there that is not represented by a component is
added to this ledger rather than silently excluded.

## Source audit

The final audit enumerated every Clojure namespace under each component's
`src/` tree. The twelve specifications cover 145 product namespaces across the 16
non-empty product components. The one additional namespace belongs to
`grain-test-utils`, whose public purpose is test-fixture construction and is
explicitly excluded above.

| Specification | Source namespaces represented |
|---|---:|
| `agent-browser.allium` | 2 |
| `colbert.allium` | 11 |
| `evaluation.allium` | 11 |
| `file-storage.allium` | 6 across contract, local, and S3 components |
| `gepa.allium` | 13 |
| `llm.allium` | 2 |
| `mcp-sheet-builder.allium` | 18 |
| `observability.allium` | 2 |
| `ontology.allium` | 37 |
| `orc-service.allium` | 39 |
| `predict-rlm-tools.allium` | 4 across image, invoice, PDF, and redaction tools |

Cross-component dependencies are represented as named external entities and
contracts at each spec boundary. Internal event-processor callbacks are modeled
as domain triggers; they intentionally produce reachability diagnostics during
`check`, while `analyse` confirms that they introduce no process findings.

Audit result: every specification has zero check errors, zero analyse errors,
and zero process findings under Allium language version 3.

## Current diagnostic baseline

`allium check` exits non-zero when warnings or informational diagnostics are
present, while `allium analyse` returns success when those diagnostics produce
no process findings. Thus “zero errors” above does not mean
`allium check specs` exits cleanly. Under the current checked-in specifications
and Allium CLI, both
`allium check specs` and `allium analyse specs` report 150 structural diagnostics
across the twelve specifications: 115 informational and 35 warnings. `analyse`
reports zero process findings.

| Diagnostic | Count | Interpretation |
|---|---:|---|
| `allium.rule.unreachableTrigger` | 77 | Internal event-processor callbacks modeled as domain triggers; intentionally not exposed as local surface operations. The RR-12 map-each recovery, RR-13 blocked-child callback, and RR-14 resume-persistence triggers are such startup/internal callbacks. |
| `allium.field.unused` | 38 | Distilled public/domain state not yet referenced by a modeled rule or surface; retained as coverage, but should be reduced when the model can express its use |
| `allium.externalEntity.missingSourceHint` | 16 | External system or consumer boundaries without an imported governing specification; accepted pending stable cross-repository coordinates |
| `allium.definition.unused` | 17 | Distilled boundary value shapes not yet referenced by a modeled surface or rule; candidates for connection or removal during tending |
| `allium.entity.unused` | 2 | Distilled entities not yet connected to the process model; candidates for connection or removal during tending |

This is a characterized baseline, not an allowlist for future warnings. Agents
must review every newly introduced or changed diagnostic, update this table when
the accepted baseline deliberately changes, and avoid claiming a clean Allium
check while its characterized diagnostics remain.

## Durable map-each recovery evidence

RR-12 reconstructs a one-level map fan-out from the event log rather than its
process-local coordinator cache. Direct-child terminal evidence is keyed by the
full map execution context, results are aligned to declared item indices, and
pending work is declared contexts minus terminal and active contexts. The source
is replayed as of the original map-parent start, so a later child write cannot
change fan-out membership. Overlapping recovery scans install state
monotonically under the coordinator's atomic update; an older snapshot cannot
erase a newer completion or reservation.

DET-E2E-274 covers all six RR-12 obligations with seven public/durable tests and
77 assertions, including SQLite close/reopen and a controlled stale-snapshot
race. The post-fix affected set passed 62 tests and 446 assertions. A complete
two-project `orc-service` run passed with exit 0 in 77 minutes 49 seconds before
the final monotonic-install hardening; focused and affected tests were rerun
after that hardening. Nested map-each remains an intentional gap because the
current flat execution context cannot identify nested occurrences.

## Durable blocked-outcome evidence

RR-13 makes a generated child's blocked result an immutable terminal campaign
fact without introducing a waiting or unblock lifecycle. The completed effect
claim and compatibility action retain the full child result; the iteration
record carries `:status :blocked` and a present opaque `:block-reason`; and the
campaign projection carries the same reason and completion time. Propagation is
status-based rather than truthiness-based, so map, `false`, and present `nil`
payloads survive command, event, projection, and replay boundaries.

DET-E2E-275 covers all eleven RR-13 obligations with four public/durable tests
and 57 assertions. Its controlled crash window pauses the old owner after the
child completion claim but before the iteration commit, lets a newer epoch
recover the original start, and proves one provider call, one child execution,
one iteration, and one terminal outcome. The old checkpoint is rejected or
canonicalized to an empty-event no-op. The affected blocked/Phase-2,
checkpointed-researcher, and effect-claim suites passed 108 tests and 856
assertions in total. A durable human-wait frontier remains an intentional open
question rather than a hidden waiting state in this slice.

## Sandbox resume delta evidence

RR-14 changes the durable sandbox representation without changing the public
resume shape. V3 resume facts retain complete non-sandbox fields and encode the
sandbox as periodic full snapshots with value-bearing `puts`/`deletes` deltas.
Each delta verifies its predecessor revision and hash; each resulting sandbox
has a typed, traversal-order-independent SHA-256 hash. Full and delta fact
shapes are enforced both by the registered event schema and defensive
hydration. Legacy V1 and V2 facts remain readable, and the first V3 fact after a
legacy predecessor reanchors the chain with a full snapshot.

DET-E2E-276 covers all 32 RR-14 obligations with 11 tests and 41 assertions.
It includes public checkpoint/write/read behavior, malformed and corrupt-chain
rejection, exact V2 reconstruction, real SQLite close/reopen, and a public cold
read that hydrates only the latest snapshot plus at most `K - 1` deltas. The
affected RR14/checkpoint/recovery/effect-claim set passed 84 tests and 728
assertions. No mock, stub, TODO, or skeleton replaces a durable boundary.

For 128 sandbox values of 256 bytes with one changed value, canonical EDN
measured 38,923 bytes for one full snapshot plus one delta versus 73,089 bytes
for two full snapshots, a 46.7% reduction. This is mechanism qualification, not
production calibration: the compatibility default remains `K = 1`, and RR-26
owns representative campaign measurement and selection of a nontrivial value.

`32 obligations, 32 covered, 0 uncovered`. Independent weed inspection found
and drove the conditional-shape code bug red-to-green, then found no remaining
RR-14 divergence. The repository Allium result is 12 specs and 150 structural
diagnostics: 115 information, 35 warnings, 0 errors; analyse reports 0 process
findings.

The canonical `clojure -M:poly test brick:orc-service` gate completed in 61
minutes 57 seconds with exit status zero. An earlier broad run failed only when
Poly evaluated the brick through the standalone `orc-service` project: that
project still resolved Grain at `47073a2820f571b31fe784311886613a9ed3297e`,
while the `orc` project used the locally recovered RR-9 lease/drain APIs. After
aligning both project dependency graphs to the same recovered Grain checkout,
the formerly failing standalone bounded-campaign namespace passed 32 tests and
244 assertions, and the following map-each recovery namespace passed 7 tests
and 77 assertions in the canonical run. This classpath mismatch, rather than an
RR-14 timing defect, was the root cause of the broad-gate red.

## Concurrent execution and trace boundary evidence

The resumable-execution path now fences four timing-sensitive boundaries at
their durable event-store seams. Every execution publishes exactly one trace
creation fact; later assemblies compare their source event counts and publish a
distinct revision fact only when they advance the canonical trace
(DET-E2E-258). Concurrent public
cancellations share the same terminal append decision as tick completion, so
twelve synchronized attempts produce one cancellation event and late work stays
fenced (DET-E2E-259). Concurrent commands carrying one stable tick identity use
one append-time start claim; delegate delivery re-reads the child after acquiring
its process-local observer claim, preserving recovery while preventing a stale
pre-claim read from dispatching the child twice. The concurrent tick-start test
proved the defect with 32 starts before the append fence and one afterward, and
DET-E2E-197 retains exact child identity and lineage coverage under the broad
suite.

Ephemeral routing summaries now exclude facts for the same node, status and
iteration that were already committed at a preceding durable boundary. This
preserves the `BatchedTracePreservesObservability` contract without suppressing
later tick iterations; DET-E2E-007 settles on and verifies the exact four-node
trace. A summary delivered after terminal trace assembly also triggers a
monotonic refresh, so the final composite evidence is not dependent on
cross-topic processor timing; the exact-count contracts in DET-E2E-005 and
DET-E2E-008 exercise that boundary. Streaming verification separately reflects
the documented transport contract: independently tapped event types need not
arrive in durable lifecycle order, so DET-E2E-065 proves root-start presence and
monotonic stream sequence while checking strict tick-before-node ordering in
durable history.

The exact CI aggregate command, `clojure -M:poly test project:orc :all-bricks`,
completed in 16 minutes 25 seconds with zero failures or errors. This broad pass
included the deterministic failure, control-flow, streaming and delegate suites
and the async command tests that exercise these races.

## Provider output normalization and rejection evidence (2026-08-07)

The ORC service now schema-decodes provider-originated JSON values before its
authoritative blackboard validation. Schema-equivalent JSON numeric forms are
canonicalized without treating numeric strings as numbers. Keyword-valued enum
choices are presented to providers in canonical JSON spelling without EDN's
leading colon, and canonical strings decode to their declared keywords;
colon-prefixed strings remain invalid. Invalid outputs do not become execution
values; they are retained as trace-only rejected-value events under the
configured inline or file-store placement policy and rehydrate through exact
node trace detail. DET-E2E-124 verifies the successful and rejected paths through
the public workflow boundary.

The provider's default internal retry also covers decoded structured outputs
that fail their declared schemas. DET-E2E-125 verifies invalid-to-valid recovery
and invalid-to-invalid exhaustion, including exact call count, final-only
rejection evidence, and usage accumulated across both attempts.

DET-E2E-150 extends that obligation through nested maps, vectors, unions,
intersections, and keyword-dispatched variants across marker and function-call
leaf transports. The provider decoder is shared by the LLM validation boundary
and the executor's authoritative blackboard validation; validated direct
predictions return canonical values, while validation-disabled predictions
retain their parsed provider representation. String enums remain strings and
noncanonical nested values remain exact rejected-output trace evidence.

## Consumer-gated researcher tools and typed finalization

The ORC service contract requires an explicitly configured consumer tool gate
to remain authoritative across inline researcher calls and generated subtree
calls. Gate resolution or construction failures fail closed rather than using
the base caller. Researcher `final!` values cross the same declared blackboard
schema boundary as other successful leaves; rejected values remain evidence and
never become execution values. DET-E2E-155 and DET-E2E-156 track the complete
public DSL, durable projection, asynchronous execution, and replay obligations.
Both obligations are implemented by the deterministic
`repl-researcher-consumer-gate-e2e-test`; focused tests pass, and both tests
passed during the broad `orc-service` brick run. The final headless canonical
brick run completed both consuming projects with zero failures or errors.

DET-E2E-161 closes the researcher's output-contract bypasses across terminal
`final!` and asynchronous completion: required nil or missing writes fail before
any sibling write becomes canonical, Malli-normalized declared values cannot be
replaced by the raw snapshot retained for observability, and top-level writes
explicitly named by `:options :optional-writes` may be omitted or normalized
from literal nil to absence. Present structured values still cross their nested
Malli schema, including all-nil maps, while explicitly nullable nested fields
preserve nil as data. Optional-write configuration is scoped to each node's
declared writes and cannot suppress unrelated completion keys. The public
deterministic namespace passed 12 tests and 53 assertions, including durable
rejection, canonical event, and projection read-back; the pinned live structured
finalization test passed 8 assertions with durable provider provenance.

## Recursive researcher empty-turn recovery

The recursive Phase-1 loop treats a successful provider response with no
executable code as recoverable iteration evidence while ordinary iteration and
execution budgets remain. The evidence is included in the next model turn and
in the durable researcher-iterations event; usage from the empty turn remains
accounted. Repeated empty turns terminate through the existing max-iterations
boundary. Explicit provider errors remain terminal, and terminal compatibility
mode retains its immediate `LLM did not generate code` failure. DET-E2E-162
proves all four boundaries through the public asynchronous workflow path,
including command/event processing, projection replay, and value-log read-back.
The focused namespace passed 16 tests and 75 assertions. The real OpenRouter
adaptive-loop journey passed 2 tests and 43 assertions after exercising generated
Phase-2 recovery.

## Researcher behavioral-mint contract disclosure

When the ontology mint command is registered, the researcher receives the exact
registered Grain/Malli command contract for `mint-behavior!` before Phase-1 code
generation. The disclosure includes the authoritative description-body schema and the
optional parent shape, does not require corpus auto-classification, and is absent when
the optional command is not registered. DET-E2E-163 proves the classifier-disabled
public asynchronous path through the real sandbox command, durable provenance audit,
and successful final output. It also guards unavailable-command and malformed-schema
boundaries. Its focused run passed 3 tests and 11 assertions; the combined
mint/classifier/RH1/RH2 contract run passed 46 tests and 219 assertions. The real
OpenRouter adaptive-loop journey passed 2 tests and 43 assertions with
classification, evolution, and mint-capable Phase-1 execution.

## Researcher configuration round-trip fidelity

The public export, DSL rendering/evaluation, and EDN import boundaries must preserve
every behavior-affecting researcher field. DET-E2E-164 tracks tool contracts, the
consumer tool gate, browser tools, ontology context, and execution options through
all three boundaries through the real command/event/projection path. It also preserves
explicit empty `:rlm {}` and `:context {}` choices, which respectively select recursive
mode and suppress automatic context classification. The complete DSL round-trip
namespace passed 17 tests and 33 assertions.

## Researcher Phase-1 output contract disclosure

The researcher receives the exact authoritative blackboard schema for every
declared write before it designs Phase-1 code. Schema disclosure is guidance,
not a replacement for enforcement: successful `final!` values still cross the
same blackboard validation boundary, and invalid values still fail without
becoming canonical. DET-E2E-157 covers exact prompt preservation for structured
and scalar schemas; DET-E2E-158 covers successful live structured finalization
from a semantic-only consumer instruction. DET-E2E-157 passed 6 deterministic
assertions. DET-E2E-158 passed 8 assertions against pinned
`google/gemini-3.6-flash`, including durable model and token-usage evidence.
The complete `clojure -M:poly test brick:orc-service` run passed across both
projects in 12 minutes 18 seconds with no failures or errors.

## Researcher Phase-1 bound tool contracts

The ORC service contract requires Phase 1 to receive authoritative argument and
result schemas for its bound tools, preserving their complete structural form.
Missing schema declarations remain backward compatible but are visibly untyped,
and declarations for unbound tools are not disclosed. DET-E2E-159 tracks the
durable public boundary and deterministic model-input contract; DET-E2E-160
tracks live schema-guided chaining from a search result into a subsequent tool
call. DET-E2E-159 passed 12 assertions through the public asynchronous path and
the complete first-project `orc-service` brick run. DET-E2E-160 passed 10 live
assertions against pinned `google/gemini-3.6-flash`: the model used the disclosed
`:candidates` field, transferred `paper-160` into the retrieval call, finalized
with retrieval-only evidence, and retained durable model/usage provenance.

## Optional structured output presence (2026-08-13)

The structured prediction and leaf execution contracts distinguish an optional
field that may be absent from a nullable field whose present value may be null.
Flattening a structured output for provider reliability must preserve that
presence contract through provider schema generation, marker and tool-call
parsing, reassembly, authoritative validation, and projection. DET-E2E-148
verifies required and mutually exclusive optional fields across both provider
transports and durable projection read-back. DET-E2E-149 additionally verifies
that provider-supplied null is normalized to absence only for optional entries
whose schemas reject null, while required and explicitly nullable entries retain
their values through final validation and durable projection read-back.

## Structured provider failure evidence (2026-08-11)

Structured provider failures now carry an additive, sanitized evidence map
captured before tool-call decoding. Stable failure kinds distinguish transport
failure, a missing forced tool call, malformed tool arguments, schema-invalid
decoded arguments, and an empty provider response. Evidence is allowlisted to
provider/model identity, response ID, finish reason, tool-call presence/name,
usage, and output-truncation status; arbitrary provider payloads and tool
arguments are excluded. Existing successful output and status behavior is
unchanged. DET-E2E-147 verifies valid, missing, malformed, schema-invalid,
empty, and truncated responses through workflow execution, durable completion
events, trace assembly, and node-detail projection.

## Provider request control preservation (2026-08-09)

The LLM boundary now specifies that accepted provider request controls must
reach the selected provider with equivalent meaning or fail explicitly. ORC's
focused boundary test verifies that `:reasoning-effort` and `:max-tokens` reach
litellm.router, and the pinned litellm-clj OpenRouter provider verifies their
canonical HTTP transformation plus explicit rejection of unsupported or
ambiguous controls. DET-E2E-130 remains open until the complete persisted-node
through HTTP-body contract is exercised in one integration-shaped test.

The boundary regression also verifies that ORC's public `:timeout-ms` request
control is translated to LiteLLM's provider-facing `:timeout` key and that the
ORC-only spelling is not leaked. This closes the adapter gap that caused a
180-second live execution budget to be silently replaced by OpenRouter's
30-second default before the request reached the provider.

## Composed AI retry deadlines and timeout evidence (2026-08-09)

AI executor retries and node retries now share the root execution deadline and
LLM-call budget at each actual provider invocation. Provider request timeouts are
bounded by the execution time remaining, retry backoff is not started when it
cannot fit, and registered in-flight leaf work is interrupted on terminal caller
timeout. DET-E2E-131 verifies the provider-call cap through the public workflow
boundary. DET-E2E-132 verifies that a timeout trace preserves completed routing
nodes and identifies the unfinished AI node with provider attempt, node attempt,
configured limits, provider timeout, and remaining-budget evidence.

The recursive-researcher regression proves that its computed per-provider
budget reaches the LLM boundary; the focused LLM-boundary regression proves
that the same value reaches the provider configuration spelling consumed by
LiteLLM. Together they close the final adapter path for
`SingleExecutionDeadline` instead of falling back to an unrelated transport
default.

## Timeout trace chronology and canonical timestamps (2026-08-11)

The workflow execution contract now requires terminal traces to preserve the
durable execution start, record completion separately, derive duration from
those instants, and expose one canonical UTC representation. Trace lists and
time filters compare instants rather than timestamp spellings and apply limits
after chronological ordering. DET-E2E-146 remains open until mixed UTC/offset
events, timeout filtering and lookup, projection replay, partial-node timing,
and the SQLite-backed boundary. The deterministic contract is verified by
DET-E2E-146 together with the public runtime timeout assertions.

## Blackboard schema specificity (2026-08-07)

The ORC service now rejects semantically unconstrained blackboard schemas at
every nesting depth. This includes `:any`, `:some`, standalone or fieldless
maps, and collections without specific item/value schemas. DSL construction,
direct declaration and schema-update commands, version/stash restoration,
generated RLM trees, and MCP workflow conversion use the same constraint or
produce equally actionable feedback. DET-E2E-126 and DET-E2E-127 verify atomic rejection, exact key/schema
paths, the specificity nudge, and acceptance of explicit structured schemas.

Workflow definition identity includes the effective recursively resolved
blackboard schemas, rather than only their symbolic registry references. A
successful build captures those schemas in its persisted authoring state, so a
referenced schema change rebuilds the same sheet while unrelated registry
changes remain a no-op. DET-E2E-128 tracks the complete public-boundary
verification through a rebuilt workflow execution and its persisted blackboard
projection.

# Custom ontology lifecycle alignment (2026-08-07)

The ontology domain now specifies an empty custom-ontology lifecycle, typed
concept provenance and updates, explicit concept/edge uniqueness, supported
relationship predicates, tenant-scoped lifecycle reads, and interoperability
between manual and evolutionary graph mutations. Deterministic end-to-end
obligations DET-E2E-121 and DET-E2E-122 are verified through the public command
path, including retry semantics, tenant collision handling, graph validation,
and projection replay. DET-E2E-123 exercises a registered deterministic
N-Triples source through the public evolution boundary, including manual URI
canonicalization, extracted provenance, unified graph relationships, and
projection replay.

## Durable continuation and repeated delegates (2026-08-23)

A logical delegate invocation remains one stable child execution across
duplicate delivery and recovery. After that child terminates, reaching the
delegate in a later durable parent iteration creates a distinct invocation with
the current inputs. Completion append atomically rejects duplicate wakes within
an invocation without suppressing later invocations. Node write attribution and
trace correlation retain the parent iteration so each child result advances only
its originating parent visit. DET-E2E-168 verifies the complete code → delegate
→ running condition → next-tick work continuation. DET-E2E-169 additionally
verifies three invocations with inputs A, B and C create distinct child ticks,
execute once each, retain rehydratable per-invocation trace inputs and outputs,
reject duplicate completion delivery, and resume from durable child state after
an interruption before parent continuation.

## Checkpointed researcher execution

Recursive researcher campaigns now use checkpointed execution by default after
the durable producer spine has been implemented and inspected. Explicit
`:checkpointed? false` retains the non-checkpointed single-invocation
compatibility path; omitted terminal mode remains non-checkpointed. Together
the landed foundation and target contract cover
per-iteration checkpoints, durable external-action frontiers, stable
provider/tool/child identities, checkpoint-safe tool idempotency, durable sandbox
value constraints, independently composed deadlines, in-place continuation,
automatic restart recovery, fenced resume ownership, blocking and cancellation,
and ordered iteration trace reconstruction. Explicitly opted-out researcher
behavior remains the compatibility path.

The checkpointed runtime path is implemented with checkpoint/action events and
projections, per-iteration `:running` continuation, cold checkpoint rehydration,
stable generated-child attempt ticks, checkpoint-safe tool keys, independent
deadline scopes, durable iteration traces, and compatibility coverage for the
legacy path. Focused deterministic tests exercise the executor, public async
workflow boundary, SQLite close/reopen replay, child-completion crash window,
codec rehydration, CAS fencing, timeout boundaries, and paired latency sampling.
A gated pinned-model journey was verified live against OpenRouter:
the checkpointed researcher crossed a durable yield, resumed with its sandbox
and history, completed successfully, and satisfied all 11 output, checkpoint,
provider-latency, usage, and trace assertions. The complete live recursive-
researcher namespace also passed 3 tests and 32 assertions, including generated-
child recovery and nested call-budget enforcement.

DET-E2E-234 through DET-E2E-251 remain open until each complete
integration-shaped obligation is executed and recorded. DET-E2E-234 through DET-E2E-241 cover
yield/retick, real restart, idempotency windows, child incorporation, timeout
boundaries, sandbox replay, blocking/cancellation/concurrency, and unfinished-
campaign tracing. DET-E2E-242 through DET-E2E-251 add the complete crash-window
matrix plus checkpoint, continuation, restart, replay, storage, concurrency,
large-value, deadline, compatibility-regression, and soak measurements with
recorded percentile distributions and raw benchmark evidence. Until those
obligations pass through the public command/event/projection boundary, the
focused coverage above must not be represented as exhaustive recovery or
performance qualification.
DET-E2E-252 is closed by the live multi-quantum state-reuse journey above.
DET-E2E-253 through DET-E2E-257 remain open for generated-child recovery,
checkpoint-safe tool deduplication, clean-JVM automatic recovery, live provider
timeout evidence, and checkpointed-versus-compatibility benchmark comparison.
These journeys complement the deterministic suite and are not substitutes for
crash-boundary correctness proofs.

DET-E2E-277 verifies the default flip through the public workflow API. An
omitted checkpoint flag executes two recursive turns across separate bounded
quanta, and a real SQLite close/reopen resumes the same campaign without
repeating its completed iteration. Explicit `:checkpointed? false` retains one
researcher invocation, no campaign projection, and no checkpoint event family;
omitted terminal mode retains that same non-checkpointed boundary. The focused
namespace passed 5 tests and 42 assertions, and the affected checkpoint,
recovery, budget, handover, and cancellation set passed 106 tests and 822
assertions. The complete `orc-service` brick then passed both consuming project
contexts with exit 0 in 62 minutes 57 seconds.

The paired durable-cost fixture produced the same 22 events, identical event
type frequencies, and 19,659 normalized canonical bytes for omitted-default
and explicit-true execution. Raw serialization was 18,316 bytes with omission
and 18,336 bytes with the explicit authoring flag. The flip therefore adds no
runtime event or durable byte beyond the already-qualified checkpoint
mechanism; selecting a representative nontrivial snapshot interval remains the
separate RR-26 calibration obligation.

This verification composes ORC with the local Grain checkout at
`/private/tmp/orc-rr8-grain-recover3`. Grain PR #22 remains open at `47073a2`
with all five checks successful, so RR-15's behavior is verified while its
whole-spine-landed merge gate remains open. The local pin is not evidence that
the dependency has landed upstream.

## Durable iteration streaming

DET-E2E-278 verifies that the live researcher iteration stream is a projection
of immutable durable records. The public envelope carries the exact durable
record and identity, and one shared durable-topic tap preserves publish order
across event types so a later root terminal cannot overtake an earlier child
iteration and close the stream. A real SQLite close/reopen uses fresh runtime
and subscription state, completed-child replay restores root lineage, and
checkpointed/default completion omits the legacy terminal iteration aggregate.
Explicit `:checkpointed? false` retains that aggregate as its documented trace
compatibility source. Finer code, sandbox, Phase-2, and token previews remain
ephemeral and non-authoritative.

The RR-16 namespace passed 5 tests and 31 assertions, the streaming aggregate
passed 17/144, and the checkpoint/recovery/effect-claim/trace aggregate passed
95/845. The complete two-project `orc-service` brick passed with exit 0 in 72
minutes 52 seconds under `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`. Two
non-headless macOS attempts aborted before Clojure completion in AppKit AWT
registration; the deterministic MCP-tools namespace passed 10/74 directly and
inside the headless full run. This is recorded as a local harness requirement,
not as a green non-headless run.

Allium remains at the characterized 12-spec baseline of 115 information
diagnostics, 35 warnings, and 0 errors; `analyse` reports no findings in all 12
specs. Weed check mode found no remaining RR-16 divergence. Coverage is `2
obligations, 2 covered, 0 uncovered`, with no generated mock, stub, TODO, or
skeleton. Combined-load inspection initially appeared to reopen RR-7, but
minimisation proved a test-harness context mismatch: the original execution
included a command registry whose mint contract changed the canonical provider
request, while manual replay omitted it. Faithful replay is green without a
production or Allium change.

## Trace and judge campaign evidence

DET-E2E-279 verifies RR-17 through the public workflow and evaluation APIs. A
default-checkpointed researcher fails one iteration, repairs it on the next,
and exposes the same ordered immutable records through raw and extracted trace
reads. Trace assembly now includes bounded effect claim/completion identity,
status, ownership, and timing alongside checkpoint/yield/resume activity; it
does not copy effect result payloads. Terminal judge construction reads the
iteration projection directly, so judge correctness does not depend on the
asynchronous execution trace settling. Built-in reasoning evaluation receives
typed iteration evidence and names the failed first iteration.

Independent race inspection found that the prior duplicate-score pre-read was
only sequentially idempotent. Two commands forced past that read appended two
scores, violating `OneJudgeScorePerCompletion`. The repaired command retains
the tick-tagged bounded read and uses an event-store CAS over the complete
`[sheet-id node-id tick-id judge-name]` identity. The focused RR-17 namespace
passes 7 tests/21 assertions, the broadened trace/evaluation aggregate passes
129/679, the checkpointed researcher namespace passes 36/315, and the
standalone `orc-evaluation`-compatible subset passes 37/208 with the root
alias's required LMDB module opens supplied explicitly.

Allium remains at the characterized 12-spec baseline of 115 information
diagnostics, 35 warnings, and 0 errors; `analyse` reports no findings. Weed
check mode found no RR-17 divergence. Coverage is `5 obligations, 5 covered, 0
uncovered`, with no generated mock, stub, TODO, or skeleton. The provider used
by the deterministic judge/campaign tests is the repository's normal injected
capability seam. The still-open Grain PR #22 remains a stack portability gate,
not an unverified RR-17 behavior.

`clojure -M:poly test brick:evaluation` ran every evaluation namespace in the
`orc` consuming project with zero test failures, then stopped before the
`orc-evaluation` context because the composed worktree resolves
`cat/schema-util` from both the local Grain checkout and the remote PR-22 SHA.
The standalone project also contains legacy judge tests that import ontology
despite that project deliberately excluding ontology. Neither harness defect is
reported as a green full-brick run, and RR-17 does not alter dependency pins.

## One classification fact per campaign

DET-E2E-280 verifies RR-18 through the public default-checkpointed campaign,
ontology command and projection, durable outcome, resume-state, and runtime
envelope boundaries. The exact structural and behavioral classifier context is
committed atomically with the classification outcome, copied through every V2
and V3 checkpoint shape, and reused by later quanta without calling either
classifier again. Recovery prefers the checkpoint fact and falls back to the
same atomic outcome fact when a process stops before its first checkpoint.

The public crash proof stops a real SQLite-backed run after the classification
commit but before provider or checkpoint work, removes live lease ownership,
closes the runtime and store, and resumes from a fresh process boundary. Both
classifiers run once across the two runtimes, the provider runs once after
recovery, one assignment remains, and the recovered model receives the exact
durable context. Sequential and concurrent assignment attempts for the same
`[source-sheet-id source-tick-id]` are first-writer-wins and increment current
projections once; another tick remains independent. Runtime reads the first
assignment when replaying a historical conflicting duplicate stream.

The focused proof passes 3 tests/35 assertions, the checkpointed researcher
namespace passes 38/342, and the bounded/checkpointed aggregate passes 70/579.
The complete two-project `orc-service` brick passes with exit 0 in 58 minutes
42 seconds under `-J-Djava.awt.headless=true`; its PDF/MCP namespace passes
10/74 in each project graph. Two non-headless attempts aborted in macOS AppKit
registration rather than returning a Clojure test failure, so no non-headless
full-run success is claimed.

Allium remains at the characterized 12-spec baseline of 115 information
diagnostics, 35 warnings, and 0 errors; `analyse` reports no findings. Weed
check mode found no unresolved RR-18 divergence. Coverage is `1 obligations, 1
covered, 0 uncovered`, with no weakened generated test and no generated mock,
stub, TODO, or skeleton. RR-19 owns outcome-time recurrence and remains outside
this slice. The local Grain pin remains an explicit integration dependency
until its upstream PR is merged.

## Recurrence is counted at outcome

DET-E2E-281 verifies RR-19 through the public default-checkpointed campaign, the
ontology occurrence command and its processor, the consolidation, judge-window and
harvest projections, and raw event reads. One explicit durable occurrence fact is
recorded for a classified researcher campaign when its node's first terminal
completion is success, failure or timeout; classification stays the attribution and
reflection fact and advances nothing. Blocked completions, cancellation, parent
abandonment, yields, non-researcher nodes, mismatched attribution and stray later
completions record no occurrence, while completed iteration evidence from
cancelled and abandoned campaigns remains inspectable to later reflection. Sequential
replays are no-ops, concurrent contenders are first-writer-wins under an append-time
CAS, and another tick on the same sheet is independent. Every recurrence consumer —
delta counters, the ordered judge window, harvest occurrence pairs and scores, the
threshold and harvest triggers, and the gate report — reads verdict occurrences, so
a failed campaign counts and can lower the quality axes and a classification-only
history invents no verdict.

Independent inspection reran the proof, enumerated every remaining reader of the
classification event as attribution-only, and drove an adversarial probe set; one
probe exposed a real admission defect (a stray success after a blocked terminal),
fixed test-first so the node's first terminal completion is the only verdict. The
RR-19 namespace passes 13 tests/53 assertions, the repaired CC-23 observability
namespace 7/67, the ontology brick passes in both owning project graphs, and the
complete two-project `orc-service` brick passes with exit 0 in
76 minutes 42 seconds under `-J-Djava.awt.headless=true`.

Allium remains at the characterized 12-spec baseline of 115 information
diagnostics, 35 warnings, and 0 errors; `analyse` reports no findings. Weed check
mode found no RR-19 divergence; the completion-event provenance is an intentional
implementation detail, and the coherence shape ratio's success-only numerator is a
tracked gap owned by RR-20/RR-21. Coverage is `4 obligations, 4 covered, 0
uncovered`, with no weakened generated test and no generated mock, stub, TODO, or
skeleton. The local Grain pin remains an explicit integration dependency until its
upstream PR is merged.

## The worked pattern is keyed on outcome and shape

DET-E2E-282 verifies RR-20 through the public default-checkpointed campaign,
the bookend and verdict commands, the registered enrichment and corroboration
processors, the claim set, the assembled description and `harvest-body`. A
class's pattern claims are keyed per tree shape and by outcome: a successful
bookend records or reinforces a `:strength` carrying the exact recorded source,
a failed bookend records a `:weakness` with the same exact source, neither
displaces the other, a class keeps every shape it succeeds with, and a class
resolves its bookends by occurrence rather than by shared host sheet. Proof is
graded: the campaign's durable success verdict corroborates its success-bookend
shapes through a `:campaign-verdict` support delta counted as
`:verdict-corroborations`, which the selector ranks ahead of earned support, so
a corroborated shape beats a bare emitted artefact and a failed-only class
offers nothing.

Independent inspection found and returned two defects before closure (a
swallowed reducible error in the corroboration writer and support-only ranking
that let a re-emitted bare artefact win); both were fixed test-first with
durable processor-level and ranking tests. The public proof runs a default-checkpointed researcher whose scripted provider emits a failing quoted tree and then a structurally repaired one through `sheet/execute`, and reads back both bookends, both shaped iteration records, the single success verdict, the two shape claims, the assembled body and `harvest-body` (1 test / 34 assertions). Focused results on the final tree:
the propagated namespace 6 tests / 23 assertions, the four sibling RR-20 namespaces, the CV-2/CC-6 seams and the ontology claim/harvest/reranker seams 108 tests / 451 assertions, the public lifecycle proof 1 / 34, the checkpointed researcher namespace 39 / 352, all 0 failures; the ontology brick passes in both owning graphs; the
complete two-project `orc-service` brick passes with exit 0 in
69 minutes 28 seconds under `-J-Djava.awt.headless=true`.

Allium remains at the characterized 12-spec baseline of 115 information
diagnostics, 35 warnings, and 0 errors; `analyse` reports no findings. Weed
check mode found no RR-20 divergence; the claim-accounting fields are an
intentional implementation detail, the terminal-winning-shape narrowing is
RR-21's, and the R-Inject truncation is RR-22's. Coverage is `2 obligations, 2
covered, 0 uncovered` with the propagated behavioural bridge green, no weakened
generated test and no generated mock, stub, TODO, or skeleton.

## Convergence is measured over winning shapes and reported before it gates

DET-E2E-283 verifies RR-21 through the RR-19 occurrence and RR-20 bookend commands,
the registered harvest check processor, the gate report and the durable report events.
The coherence measure counts one winning shape per successful campaign — the terminal
successful shape in durable order — and reports distinct successful terminal shapes
over successful campaigns; failed, timed-out, cancelled and abandoned campaigns and the
shapes a repairing campaign abandoned enter neither side. `not-measurable` is reported
as such, never as coherent or rejected; the harvest gate no longer blocks on the clause;
and one `shape-coherence-reported` fact is recorded per verdict occurrence, idempotent
under re-delivery, so the distribution is observable before a calibrated threshold is
chosen. Independent inspection found and returned one defect (report values computed at processing time rather than as of the occurrence), fixed test-first with a deterministic backlog test. the propagated namespace 6 tests / 37 assertions, the combined RR-21/RR-19/RR-20/harvest/consolidator set 126 tests / 576 assertions, the adversarial probe 5 / 12, all 0 failures; the ontology brick passes in both
owning graphs; the complete two-project `orc-service` brick passes with exit 0 in
54 minutes 45 seconds under `-J-Djava.awt.headless=true`.

Allium remains at the characterized 12-spec baseline of 115 information diagnostics,
35 warnings, and 0 errors; `analyse` reports no findings. Weed check mode found no RR-21 divergence; the rule's helper names are spec commentary the implementation declares, and the blocking threshold remains a later data-driven decision. Coverage is
`2 obligations, 2 covered, 0 uncovered` with the propagated namespace green, no weakened
generated test and no generated mock, stub, TODO, or skeleton.

## Offered patterns are whole and declare their key bindings

DET-E2E-284 verifies RR-22 through the claim command and assembled description, the
public R-Inject prompt seam, and a scripted-provider campaign through
`sheet/execute`. A worked pattern reaches the model as its exact, complete source
with no truncation of any length; its bindings — external reads, writes and outputs —
are derived from that source and carried additively on the strength entry, absent
when the source does not parse or its code was elided; the rendering names them as
advice to rebind and never as a mandate; and a scripted-provider researcher shown the pattern copies it whole (identical shape fingerprint) and bridges its output to the task's own key. Independent inspection found and fixed two binding-derivation gaps (`:from` reads, duplicate keys) test-first.
the two contract namespaces plus the live adopt proof and the assembly properties seam 10 tests / 55 assertions, the R-Inject, formatting, seed-validation, reranker-contract and RR-20 seams 50 tests / 565 assertions, the checkpointed researcher namespace 39 / 352, the adversarial probe 2 / 21, all 0 failures; the ontology brick passes in both owning graphs; the complete
two-project `orc-service` brick passes with exit 0 in 71 minutes 9 seconds under
`-J-Djava.awt.headless=true`.

Allium remains at the characterized 12-spec baseline of 115 information diagnostics,
35 warnings, and 0 errors; `analyse` reports no findings. Weed check mode found no RR-22 divergence; the binding fields and derivation rules are implementation detail beneath the invariant. The slice
carries no generated obligation (`0 obligations, 0 covered, 0 uncovered`); the
contract namespaces are green with no weakened test and no generated mock, stub,
TODO, or skeleton.

## The hot evidence queries are tag-scoped

DET-E2E-285 verifies RR-23 through the bookend emit command, the scoped harvest,
reflection and evaluation reads, and an injected counting read seam. The tree bookend
carries a `source-tick` tag naming its campaign; the promotion path, the reflection
gather, the coherence measure and the composite duplicate check read by occurrence pair
or class tag rather than by type alone; scoped results equal an independent unscoped
reference over a multi-class store; and the events materialised by those reads do not
grow when the store gains unrelated campaigns (6/24/15 events at both 12 and 30 campaigns, against 48/48/36 growing to 120/120/90 before).
Independent inspection confirmed Grain keys query-scoped projections by scope, probed cache isolation, legacy replay and duplicate refusal, and ratified the promotion pre-gate's query scoping. the contract namespace 5 tests / 14 assertions, the combined harvest/reflection/RR-19/RR-20/RR-21/judge-runtime/deterministic-ontology set 194 tests / 774 assertions, the adversarial probe 4 / 14, and after the trace-order fix the trace, checkpointed-researcher, judge-evidence and iteration-stream suites 57 / 516, all 0 failures; the ontology brick passes in both owning
graphs; the complete two-project `orc-service` brick passes with exit 0 in
71 minutes 5 seconds under `-J-Djava.awt.headless=true`.

Allium remains at the characterized 12-spec baseline of 115 information diagnostics,
35 warnings, and 0 errors; `analyse` reports no findings. Weed check mode found no RR-23 divergence; the duplicate-score check was already tick-scoped and the tag and replay opt-in are implementation detail. The slice
carries no generated obligation (`0 obligations, 0 covered, 0 uncovered`); the
contract namespace is green with no weakened test and no generated mock, stub, TODO,
or skeleton.

## Behaviour mints carry iteration provenance

DET-E2E-286 verifies RR-24 through the real sandbox `mint-behavior!` under the landed
durable-effect options, the mint command, the registered forced-reindex processor and
the minted-event provenance accessor. A researcher mint records the iteration, the
attempt ordinal and the ownership epoch that minted it; provenance distinguishes a
first-attempt mint from a late fallback; a replayed iteration neither re-mints nor forces
a second reindex; and the derived concept id is stable across attempts and replays.
Independent inspection probed pre-slice mints, replay-vs-winner provenance and handler re-delivery, and ratified the at-most-once forced-reindex marker with the threshold reindex as its backstop. the contract namespace 5 tests / 30 assertions, the sandbox-mint, effect-claim, reindex, deterministic-ontology and bounded-campaign seams 107 tests / 779 assertions, the adversarial probe 3 / 6, all 0 failures; the ontology brick passes in both owning
graphs; the complete two-project `orc-service` brick passes with exit 0 in
69 minutes 25 seconds under `-J-Djava.awt.headless=true`.

Allium remains at the characterized 12-spec baseline of 115 information diagnostics,
35 warnings, and 0 errors; `analyse` reports no findings. Weed check mode found no RR-24 divergence; the first-attempt definition and the at-most-once reindex marker are recorded implementation decisions. Coverage is
`2 obligations, 2 covered, 0 uncovered` (both at their schema seams), with the contract
namespace green, no weakened test and no generated mock, stub, TODO, or skeleton.
## The documentation tells the truth about the landed loop

DET-E2E-287 verifies RR-25 through a contract namespace that greps the live tree
(`components/*/src` and the top-level docs, historical records exempt) for the statements the
arc made false and asserts the true ones where they belong: the idempotency key recognises an
already-dispatched call at our own boundary and cites ADR 0004; harvest is live; recurrence
is counted at the verdict occurrence; coherence is over winning shapes and report-only;
patterns are offered whole with declared bindings and no cap is described; and
`:rlm/tree-generated` fires once per campaign carrying the last tree. Independent inspection
re-read every changed sentence against the RR-19 … RR-24 Verification sections and the code,
strengthened the contract to catch the per-emit phrasing that had survived in the evaluation
component and its guide, and corrected the verdict enumeration, the executor wording and a
dangling ADR citation. The contract namespace 4 tests / 15 assertions, 0 failures; the
ontology brick passes in both owning graphs (668 / 3803 each); the complete two-project
`orc-service` brick passes with exit 0 in 88 minutes 46 seconds under
`-J-Djava.awt.headless=true` (127 namespaces, 1055 tests / 5897 assertions per graph).

Allium remains at the characterized 12-spec baseline of 115 information diagnostics,
35 warnings, and 0 errors; `analyse` reports no findings. Weed check mode found no RR-25
divergence. The slice carries no spec obligation (`0 obligations, 0 covered, 0 uncovered`);
the contract namespace is green, with no weakened test and no generated mock, stub, TODO,
or skeleton.
## The whole spec holds together

RR-26 closed the arc against the three specs and the live system. Weed check mode ran over
`orc-service`, `ontology` and `evaluation`; every divergence was orchestrator-verified against the
spec line and the code line — five spec bugs tended, four code bugs reproduced RED and fixed (a
provider-deadline race found by the live proof, a composite-score command with no compare-and-swap
that reproduced as a real double-write, a node-trace schema that rejected `:blocked`, and a gated
live journey reading an event the default-on flip stopped emitting), six intentional gaps recorded,
five design questions raised. The five obligations map to covering tests already green in the gate
(`5 obligations, 5 covered, 0 uncovered`). DET-E2E-288 (two workers, one frontier), DET-E2E-255 (a
real SIGKILL mid-campaign, automatic recovery from the same SQLite store with no resubmission) and
DET-E2E-256 (a constrained live deadline, which found the race) are ticked with their evidence.
Allium after the tends: 115 information diagnostics, 35 warnings, 0 errors, 0 analyse findings.
The complete two-project `orc-service` brick passes with exit 0 in 59 minutes 29 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (129 namespaces, 1060 tests / 5925 assertions per graph, 0 failures, 0 errors); the ontology brick passes in both owning graphs (77 namespaces, 668 tests / 3803 assertions each); the evaluation brick passes (9 namespaces, 116 tests / 480 assertions); Grain's control-plane and todo-processor-v2 brick tests and the SQLite project's tests pass on the recovered Grain tree.

## Streaming forwarding stays off the engine's dispatch pool

RR-27 moved the streaming tap's forwarding loop onto its own thread, matching the router's stated rationale
and the tended invariant `ExecutionEventStream.StreamingNeverOccupiesTheEngineDispatchPool`. DET-E2E-289 pins
it with two red-first tests (forwarding-thread identity; no dispatch thread carries a tap frame while the
tap is held blocked and a workflow still completes) and hardens the ordering test that once wedged a full
gate. Coverage `3 obligations, 3 covered, 0 uncovered` plus the prose invariant; the complete two-project `orc-service` brick passes with exit 0 in 88 minutes 34 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (130 namespaces, 1062 tests / 5933 assertions per graph, 0 failures, 0 errors).

## Retired stages and dead paths are gone from the code

RR-28 removed the retired `skipped` node status from the node-trace schema and the trace-summary query
(red-first: a skipped record is rejected; the node-stats result carries no skip count), so the code declares
exactly the lifecycle the spec declares. RR-29 deleted the caller-less synchronous evaluation path — the
single-trace and batch evaluators, the all-judges aggregate behind the retired `evaluate_all`, and their
never-dispatched schema declarations — with its hardcoded low-score gate; the per-judge `evaluate-single`
(`TraceJudge.evaluate`) and the event-driven runtime are the evaluation surface. Coverage `2 obligations,
2 covered, 0 uncovered` and `3 obligations, 3 covered, 0 uncovered` respectively. On the final tree the complete two-project `orc-service` brick passes with exit 0 in 58 minutes 15 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (130 namespaces, 1060 tests / 5924 assertions per graph, 0 failures, 0 errors), and the evaluation brick passes (10 namespaces, 119 tests / 487 assertions). Allium holds at 114 information diagnostics, 35 warnings, 0 errors, 0 analyse findings.

## Default judges carry dimension-specific feedback

RR-30 honours the `ActionableFeedback` guarantee on the live path: each default LLM judge's evidence lists are projected
into one named dimension (the tier-1 rubric name, shared with the synchronous aggregate and known to the ontology
classifier) carrying the judge's score; scores, feedback and provenance are unchanged. Coverage `3 obligations, 3 covered,
0 uncovered`. On the final tree the complete two-project `orc-service` brick passes with exit 0 in 57 minutes 49 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (130 namespaces, 1060 tests / 5924 assertions per graph, 0 failures, 0 errors), and the evaluation brick passes in both of its projects, `orc` and `orc-evaluation` (11 namespaces, 126 tests / 531 assertions per project, 5 minutes 45 seconds) — the first time the `orc-evaluation` project has resolved and run, after its `cat/schema-util` pin was aligned with the other projects.

## Judges see the node's resolved reads and its declared criteria

RR-31 makes `TraceEvidence.inputs` true for ordinary workflow nodes: the judge-input builder resolves the completion's
recorded reads through the value log (as it already resolved writes), keeps the direct-inputs and tick-scoped
started-event reach-back for completions without reads, and threads a judge configuration's declared `:criteria` into
the four LLM judges' instructions. Coverage `2 obligations, 2 covered, 0 uncovered`. On the final tree the complete two-project `orc-service` brick passes with exit 0 in 71 minutes 34 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (130 namespaces, 1060 tests / 5924 assertions per graph, 0 failures, 0 errors), and the evaluation brick passes in both of its projects (135 tests / 549 assertions per project, 0 failures).

## The automatic tree-profile feeder is retired

Grill D6 retired `ClassifyEvaluationFailure` and `RecordSuccessfulPattern`, whose trigger event no producer ever
emitted; RR-32 deleted the feeder processor, the discovery reader and its command, made the classifier skip unknown
dimension names instead of emitting a URI-less failure, and unified the two dimension dictionaries. Tree profiles remain
fed by the consumer-facing recording commands; judge feedback reaches the loop through living descriptions. Coverage
`4 obligations, 4 covered, 0 uncovered`. On the final tree (RR-32 and RR-33 together, nothing else in flight) the complete two-project `orc-service` brick passes with exit 0 in 67 minutes 28 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (130 namespaces, 1060 tests / 5924 assertions per graph, 0 failures, 0 errors); the evaluation brick passes in both of its projects (143 tests / 573 assertions per project); and the ontology brick passes in each owning project graph run as its own JVM (78 namespaces, 675 tests / 3823 assertions each, 0 failures).

## Automatic campaign recovery

RR-8's production path is present end to end. The periodic recovery trigger
runs at processor startup and every 30 seconds; its processor invokes
`resume-in-progress!`, whose unfinished-node scan includes `:repl-researcher`
frontiers and claims a newer ownership epoch before resuming. The campaign
projection derives abandonment when a parent ends and does not fabricate a
verdict or completion time. Repeated and concurrent recovery converge through
the durable frontier compare-and-swap rather than a process-local guard.

The checkpointed researcher namespace passes 36 tests and 315 assertions,
including startup recovery without resubmission, real SQLite close/reopen,
post-checkpoint frontier recovery, repeated/concurrent convergence, running and
yielded abandonment, and an abandoned-frontier CAS fence. The separately
checked Grain checkout at PR #22 SHA `47073a2820f571b31fe784311886613a9ed3297e`
passes the complete `todo-processor-v2` brick and includes the two-processor-set
ownership/reassignment proof. This composes against a local pin; it does not
claim that the still-open Grain PR has landed upstream.

Weed check mode found no RR-8 spec/code divergence. `7 obligations, 7 covered,
0 uncovered`; no generated mock, stub, TODO, or skeleton remains. The
repository-wide Allium gates remain at 12 specs, 115 information diagnostics,
35 warnings, 0 errors, and zero analyse findings.

## Claim-before-effect campaign fence

DET-E2E-261 closes the same-frontier claim race through schema-validated Grain
commands and the public checkpointed researcher boundary. Frontier, claim, and
completion events share the campaign tag; the store admits one same-epoch claim,
rejects the racing claimant before its injected effect, and rejects stale claims,
stale completions, and stale version-2 checkpoint commits after the frontier
advances. The claim projection moves the admitted claim from `:claimed` to
`:completed` without manufacturing a second claim.

Content-derived logical identities are canonical across reordered maps and sets,
distinguish EDN collection types, incorporate durable generated-code source when
applicable, and exclude attempt order. Attempt identities are derived from the
logical identity, ownership epoch, and non-negative attempt ordinal. Public
provider, inline-provider, context-aware three-argument checkpoint-safe tool,
generated-child, and behavior-mint paths all append the claim before dispatch;
repeated identical content rejoins the completed logical action. A missing or
two-argument effectful caller is rejected before model dispatch, effect claim,
or tool invocation, leaving both raw and projected claims empty. The focused
namespace passed 21 tests and 154 assertions, the public same-frontier race
passed 10 repeated runs and 120 assertions, and eight affected namespaces passed
168 tests and 836 assertions. The final
`clojure -M:poly test brick:orc-service` proof passed both consuming project
contexts with exit 0 in 15 minutes 38 seconds. Its first attempt exposed a
timeout-result reconstruction race outside RR-7; the strengthened DET-E2E-029
replay proof and repair are recorded in the RR-7 inspection report rather than
hidden behind the green rerun.

All 18 RR-7 obligations have executable witnesses and are represented in the
current `allium plan` output. At RR-7 closure, the repository-wide Allium result
was the characterized 35-warning, 107-information, zero-error baseline with zero
process findings. The former RR-2/RR-7 divergence is resolved: checkpointed effectful
tools require the context-aware three-argument caller demanded by
`CheckpointSafeToolsShareIdempotencyKeys`; two-argument compatibility remains
outside that boundary.

## Durable provider-call budget

DET-E2E-273 closes the restartable shared-call-budget gap with one immutable
provider-call reservation per physical attempt. A root-tag CAS validates the
durable budget, invoking execution and node, campaign iteration and live epoch,
global root-scoped invocation identity, and remaining capacity before provider
entry. Outer researcher turns, inline provider primitives, same-epoch retries,
generated descendants, and nested checkpointed campaigns share one root ledger.
The public projection replays that ledger; a process-local cache hydrates lazily
and cannot authorize a call. Read or append anomalies fail closed, while an
already-written reservation remains spent after an unknown provider outcome.

The deterministic proof includes parallel final-slot and duplicate-delivery
races, wrong-root/node/campaign/iteration and stale-epoch rejection, a real
SQLite close/reopen with automatic recovery after a pre-return crash, cache
miss/stale/concurrent-hydration boundaries, exact one- versus multi-quantum
usage equality, streaming ordering, and the unchanged non-checkpointed event and
result contract. The focused namespace passed 17 tests and 117 assertions; the
affected aggregate passed 185 tests and 1,177 assertions; and the full
`orc-service` brick passed both consuming project contexts with exit 0.

The independent SQLite read benchmark used 10 warmups and 30 samples per shape.
At 2, 20, 110, 200, 1,010, and 2,000 returned events, p50 latency was 429.292,
633.584, 442.208, 2,465.709, 516.041, and 10,654.333 microseconds,
respectively. Density dominated family width, supporting lazy first-call
hydration. All 18 RR-11 obligations are covered with no unresolved divergence.
The repository-wide Allium result is now the characterized 109-information,
35-warning, zero-error baseline with zero process findings.

## Monotonic terminal-trace refresh

Terminal trace assembly carries a revision derived from the durable, non-trace
events visible for the execution. Competing publishers first contend for one
atomic creation claim. A loser retries through the revision command, whose CAS
rejects stale and duplicate source counts while allowing a newer source snapshot
to add evidence. Creation uses `:sheet/execution-traced`; later advances use
`:sheet/execution-trace-refreshed`, and both replay into the same versioned
canonical projection. Neither publication event can advance the source revision.
The synchronous timeout writer uses the same publication boundary, so its
partial active-attempt evidence cannot race an equal or older asynchronous
assembly into a second creation fact. DET-E2E-258 verifies creation, stale,
duplicate and advancing publications through command, event and projection
read-back. Its named `TraceRefreshNeverRegresses` obligation is covered (one
obligation, one covered, zero uncovered).
