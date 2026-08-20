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
`src/` tree. The twelve specifications cover 132 product namespaces across the 16
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
| `ontology.allium` | 34 |
| `orc-service.allium` | 29 |
| `predict-rlm-tools.allium` | 4 across image, invoice, PDF, and redaction tools |

Cross-component dependencies are represented as named external entities and
contracts at each spec boundary. Internal event-processor callbacks are modeled
as domain triggers; they intentionally produce reachability diagnostics during
`check`, while `analyse` confirms that they introduce no process findings.

Audit result: every specification has zero check errors, zero analyse errors,
and zero process findings under Allium language version 3.

## Current diagnostic baseline

The Allium CLI treats warnings and informational diagnostics as a non-zero
result, so “zero errors” above does not mean `allium check specs` exits cleanly.
On 2026-08-19, both `allium check specs` and `allium analyse specs` reported 181
structural diagnostics across the twelve specifications: 149 informational and 32
warnings. `analyse` reported zero process findings.

| Diagnostic | Count | Interpretation |
|---|---:|---|
| `allium.rule.unreachableTrigger` | 41 | Internal event-processor callbacks modeled as domain triggers; intentionally not exposed as local surface operations |
| `allium.field.unused` | 108 | Distilled public/domain state not yet referenced by a modeled rule or surface; retained as coverage, but should be reduced when the model can express its use |
| `allium.externalEntity.missingSourceHint` | 16 | External system or consumer boundaries without an imported governing specification; accepted pending stable cross-repository coordinates |
| `allium.definition.unused` | 14 | Distilled boundary value shapes not yet referenced by a modeled surface or rule; candidates for connection or removal during tending |
| `allium.entity.unused` | 2 | Distilled entities not yet connected to the process model; candidates for connection or removal during tending |

This is a characterized baseline, not an allowlist for future warnings. Agents
must review every newly introduced or changed diagnostic, update this table when
the accepted baseline deliberately changes, and avoid claiming a clean Allium
gate while either command exits non-zero.

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

## Composed AI retry deadlines and timeout evidence (2026-08-09)

AI executor retries and node retries now share the root execution deadline and
LLM-call budget at each actual provider invocation. Provider request timeouts are
bounded by the execution time remaining, retry backoff is not started when it
cannot fit, and registered in-flight leaf work is interrupted on terminal caller
timeout. DET-E2E-131 verifies the provider-call cap through the public workflow
boundary. DET-E2E-132 verifies that a timeout trace preserves completed routing
nodes and identifies the unfinished AI node with provider attempt, node attempt,
configured limits, provider timeout, and remaining-budget evidence.

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
