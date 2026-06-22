# PRD: ORC Documentation Overhaul

## Problem Statement

ORC has a feature-complete, layer-rich system but its documentation fails at every entry point. The README lists eight components flatly with no indication of which depend on what, which require Python, or which add cost overhead. The `ORC-SERVICE-GUIDE.md` mixes judges, GEPA, streaming, RLM, and ontology context with no dependency map — a newcomer cannot tell whether they need ontology to use judges. Every existing doc was written as an internal reference or design diary, not as consumer onboarding material. There is no progressive path from "hello world behavior tree" to "self-improving agent."

Three failure modes compound this:

1. **No opt-in hierarchy visible.** Newcomers can't see which components need which — judges, GEPA, ontology, ColBERT, Living Descriptions each have different deps and Python requirements.
2. **No progressive path.** There is no document that takes someone from a first workflow through judges, optimization, and eventually self-improvement.
3. **Docs written for insiders.** The judge design lineage, the substrate/application distinction in ontology, the recursive-default in RLM, the `tree-fingerprint` structural semantics — none of these are explained for someone encountering them for the first time.

## Solution

A two-wave restructuring. Wave 1 creates the new docs that establish the mental model. Wave 2 systematically overhauls every existing consumer-facing doc. No big spikes — one doc per issue, each verified against running code.

### Wave 1 — New docs

| Doc | Purpose |
|-----|---------|
| `docs/COMPONENT-MAP.md` | Opt-in hierarchy (Layers 0–8), dependency graph, known issues |
| `docs/JUDGE-ARCHITECTURE.md` | North star for building perfect judges — rubric design, lineage, tier-2 coming |
| `docs/GETTING-STARTED.md` | Progressive 6-phase contract-analysis narrative: core → judges → custom judge → GEPA → ontology → self-improving |
| README.md | Add layer table up front, reference new docs, keep Quick Start |

### Wave 2 — Per-doc overhauls

Every existing doc gets its own focused issue. Each agent reads the existing doc, the PRD intent notes, and the Wave 1 output before writing. Overlap-consolidation decisions:

- `FEEDBACK-LOOP.md` → archived (content migrated to `SELF-IMPROVING-LOOP.md`)
- `docs/contributors/CONTRIBUTOR-GRAIN-PATTERNS.md` — renamed and relocated to contributor zone
- `PATTERN-RECORDING.md` (renamed, standalone manual-control path guide), retained as standalone
- `DSL-REFERENCE.md` (the former DSL overview, renamed), restructured

## User Stories

1. As a **newcomer evaluating ORC**, I want a one-screen view of what each layer provides and what it costs in deps/Python.
2. As a **newcomer building a first workflow**, I want a worked example that starts simple and adds capability one layer at a time.
3. As a **newcomer wanting verifiable examples**, I want REPL output embedded in every code snippet.
4. As a **behavior tree builder**, I want visual diagrams alongside DSL code.
5. As a **judge user**, I want to understand the rubric architecture, why discrete 1-5 bands, and how to build custom judges.
6. As a **judge user**, I want to know what's coming (pluggable scales) so I don't build workarounds.
7. As a **GEPA user**, I want to understand that GEPA is for static LLM nodes and `:auto-classify?` is a separate orthogonal actuator for RLM tree design.
8. As an **ontology user**, I want to understand the substrate/applications distinction and how `ontology-id` enables separate memory graphs.
9. As an **ontology user**, I want to know about the BFS scoping bug.
10. As a **self-improving loop user**, I want the exact opt-in boundary for `:auto-classify?`.
11. As a **RLM user**, I want recursive-as-default up front and terminal deprecation noted.
12. As a **maintainer**, I want a clear component-boundary map.
13. As a **reader of any ORC doc**, I want code claims verified against running code.

## Implementation Decisions

### Central organizing principle: the opt-in hierarchy

| Layer | Capability | Components needed | Python? |
|-------|-----------|-------------------|---------|
| 0 | Behavior tree DSL + runtime + event sourcing + streaming | `orc-service` | No |
| 1 | LLM-as-judge scoring | `evaluation` | No |
| 2 | Custom judges (your rubric, your bands) | `evaluation` | No |
| 3 | GEPA — instruction optimization | `gepa` + `evaluation` | No |
| 4 | Base embeddings + semantic search | `ontology` (DJL) | No |
| 5 | ColBERT — late-interaction retrieval upgrade | `colbert` | Yes (`.venv-colbert`) |
| 6 | Evolutionary ontology builder — general memory | `ontology` + ORC sheets | No |
| 7 | Self-improving loop — Living Descriptions | `ontology` + `colbert` + seed corpus | Yes |
| 8 | MCP Sheet Builder — dynamic workflow generation | `mcp-sheet-builder` | No |

### Getting-started domain and structure

Contract analysis (comparing two contract versions). Six phases:

1. Core tree: `:sequence` of `:llm` nodes (survey → diff → classify → impact → summarize)
2. Judges: attach `:grounding` + `:completeness` — introduce rubric architecture
3. Custom judge: build with `scale/discrete-scale` — "coming soon: pluggable scales"
4. GEPA: optimize the extraction instruction — instructions as data, not inline
5. Ontology: ingest reference contracts → semantic search — substrate, `ontology-id`, BFS bug note
6. Self-improving: `:repl-researcher` with `:auto-classify?` + `:recursive?` — honest alpha-state

### Known issues to surface

1. **BFS scoping**: `expand-concept-neighborhood` walks all ontology-ids regardless of filter. Use `hybrid-search` with explicit `:signals` instead.
2. **Pluggable judge scales**: built-in LLM judges sealed; custom via `discrete-scale`. Coming.
3. **Tree-fingerprint is structural**: collapses `:instruction`. Domain-specific specialists need `:tree-class` (not yet auto-assigned).
4. **RLM terminal deprecation**: `:rlm {:recursive? false}` is the escape hatch; will be removed.

### Verification discipline

Every code example in Wave 1 docs must be run end-to-end against `orc-template` and the REPL output embedded. No untested claims. If a claim cannot be verified, label it as design intent or "coming soon."

### Visual representation

Mermaid + enhanced `print-tree` + raw DSL. All three present for every workflow: visual for orientation, `print-tree` for text-mode hierarchy, raw DSL as copy-pasteable source of truth.

## Wave 2 per-doc intent notes

Each Wave 2 issue agent receives:

| Doc | Intent |
|-----|--------|
| ORC-PRINCIPLES.md | Keep mostly; add navigation header + update cross-references |
| ORC-SERVICE-GUIDE.md | Restructure by opt-in tier — label each section with its Layer |
| DSL-REFERENCE.md | Renamed; first 135 lines are newcomer path |
| EVALUATION-COMPONENT.md | Lead with rubric north star, not tier-1 caveat block |
| RLM-GUIDE.md | Recursive-is-default first; composition pattern before root-only examples |
| GEPA-GUIDE.md | Lead with independence from ontology; show feedback→mutation chain |
| ONTOLOGY.md | Substrate/application reframing first; three-layer is one application |
| SELF-IMPROVING-LOOP.md | Absorb FEEDBACK-LOOP.md; move alpha-state table to top |
| LIVING-DESCRIPTIONS.md | Tighten; update judge integration for ADR 0011 |
| STREAMING.md | Orientation-first (30s); RLM events labeled |
| EVENT-STORE-PATTERNS.md | ORC consumer examples (not bare Grain) |
| FEEDBACK-LOOP.md | Archive; migrate 7-stage diagram + batch workflows + troubleshooting |
| CONTRIBUTOR-GRAIN-PATTERNS.md | Rename + relocate to contributor zone |
| ARCHITECTURE.md | Add component-boundary section |
| PATTERN-RECORDING.md | Renamed; "when to use this vs automated" |
| COLBERT-INTEGRATION.md | Frame as optional upgrade from the start |
| MCP-SHEET-BUILDER-GUIDE.md | Layer 8 standalone — no self-improving loop dep |
| FUTURE-VISION.md | Audit "Not Built Yet" vs shipped; archive stale |

## Out of Scope

- Code changes (print-tree IO mode, pluggable scales, BFS fix, tree-class auto-assignment)
- Merging `feature/ontology-architecture` branch
- orc-sessions documentation (internal consumer)
- Writing tests or code for any ORC feature

## Further Notes

- All code examples use `orc-template` as the consumer starting point. Pin to the same SHA.
- Visual prototype handoff dispatched; integrate chosen approach once reviewed.
- ADR 0008 (orc-sessions) is load-bearing: `:auto-classify?` is for RLM tree designers. GEPA is for static LLM nodes. Orthogonal actuators.
- ADR 0011 + `judge-framework-verdict-notes.md` (orc-sessions) are the source of truth for judge architecture.