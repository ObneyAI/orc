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
| `llm` | — | — | Excluded: empty placeholder directory; no production source or dependency manifest |
| `mcp-sheet-builder` | Workflow generation from MCP tools | `mcp-sheet-builder.allium` | Distilled and checked |
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
`src/` tree. The ten specifications cover 130 product namespaces across the 15
non-empty product components. The one additional namespace belongs to
`grain-test-utils`, whose public purpose is test-fixture construction and is
explicitly excluded above. `llm` contains no source namespace.

| Specification | Source namespaces represented |
|---|---:|
| `agent-browser.allium` | 2 |
| `colbert.allium` | 11 |
| `evaluation.allium` | 11 |
| `file-storage.allium` | 6 across contract, local, and S3 components |
| `gepa.allium` | 13 |
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
On 2026-08-06, both `allium check specs` and `allium analyse specs` reported 176
structural diagnostics across the ten specifications: 147 informational and 29
warnings. `analyse` reported zero process findings.

| Diagnostic | Count | Interpretation |
|---|---:|---|
| `allium.rule.unreachableTrigger` | 38 | Internal event-processor callbacks modeled as domain triggers; intentionally not exposed as local surface operations |
| `allium.field.unused` | 109 | Distilled public/domain state not yet referenced by a modeled rule or surface; retained as coverage, but should be reduced when the model can express its use |
| `allium.externalEntity.missingSourceHint` | 14 | External system or consumer boundaries without an imported governing specification; accepted pending stable cross-repository coordinates |
| `allium.definition.unused` | 13 | Distilled boundary value shapes not yet referenced by a modeled surface or rule; candidates for connection or removal during tending |
| `allium.entity.unused` | 2 | Distilled entities not yet connected to the process model; candidates for connection or removal during tending |

This is a characterized baseline, not an allowlist for future warnings. Agents
must review every newly introduced or changed diagnostic, update this table when
the accepted baseline deliberately changes, and avoid claiming a clean Allium
gate while either command exits non-zero.
