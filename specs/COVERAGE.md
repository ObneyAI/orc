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
On 2026-08-11, both `allium check specs` and `allium analyse specs` reported 180
structural diagnostics across the twelve specifications: 148 informational and 32
warnings. `analyse` reported zero process findings.

| Diagnostic | Count | Interpretation |
|---|---:|---|
| `allium.rule.unreachableTrigger` | 40 | Internal event-processor callbacks modeled as domain triggers; intentionally not exposed as local surface operations |
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
