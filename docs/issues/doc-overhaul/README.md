# ORC Documentation Overhaul — Issue Breakdown

**Parent PRD:** `docs/prd/orc-documentation-overhaul.md`

## Overview

Every user-facing ORC doc needs to be approachable by newcomers, accurate by code verification, and navigable by dependency layer. This is a systematic two-wave restructuring. No big spikes — one doc per issue.

## Wave 1 — Build the mental model (new docs)

| # | Title | Type | Blocked by |
|---|-------|------|------------|
| DOC-01 | COMPONENT-MAP.md — opt-in hierarchy + dep graph | AFK | None |
| DOC-02 | README.md — layer table + cross-references | AFK | DOC-01 |
| DOC-03 | JUDGE-ARCHITECTURE.md — north star for perfect judges | AFK | None |
| DOC-04 | GETTING-STARTED.md Phases 1–3 | AFK | DOC-01, DOC-03 |
| DOC-05 | GETTING-STARTED.md Phases 4–6 | AFK | DOC-04 |
| DOC-06 | Visual representation integration | HITL | Prototype review |

## Wave 2 — Systematic overhauls (all blocked by Wave 1 complete)

| # | Title | Type |
|---|-------|------|
| DOC-07 | ORC-PRINCIPLES.md — review + cross-references | AFK |
| DOC-08 | ORC-SERVICE-GUIDE.md — restructure by opt-in tier | AFK |
| DOC-09 | DSL-REFERENCE.md — rename + restructure | AFK |
| DOC-10 | EVALUATION-COMPONENT.md — rubric-first structure | AFK |
| DOC-11 | RLM-GUIDE.md — recursive-default framing | AFK |
| DOC-12 | GEPA-GUIDE.md — independence framing | AFK |
| DOC-13 | ONTOLOGY.md — substrate/application reframing | AFK |
| DOC-14 | SELF-IMPROVING-LOOP.md — absorb FEEDBACK-LOOP content | AFK |
| DOC-15 | LIVING-DESCRIPTIONS.md — tighten all three sections | AFK |
| DOC-16 | STREAMING.md — orientation-first | AFK |
| DOC-17 | EVENT-STORE-PATTERNS.md — ORC-consumer framing | AFK |
| DOC-18 | FEEDBACK-LOOP.md — archive + migrate content to DOC-14 | AFK |
| DOC-19 | CONTRIBUTOR-GRAIN-PATTERNS.md — renamed + relocated | Done |
| DOC-20 | ARCHITECTURE.md — add component-boundary section | AFK |
| DOC-21 | PATTERN-RECORDING.md (done) | AFK |
| DOC-22 | COLBERT-INTEGRATION.md — optional-upgrade framing | AFK |
| DOC-23 | MCP-SHEET-BUILDER-GUIDE.md — Layer 8 standalone | AFK |
| DOC-24 | FUTURE-VISION.md — review + archive stale sections | AFK |

## Dependency graph

```
DOC-01 → DOC-02
DOC-03 → DOC-04 (independent of DOC-01)
DOC-04 → DOC-05

Wave 1 complete → DOC-07 through DOC-24 (all parallel)
DOC-14 (SELF-IMPROVING-LOOP) → DOC-15 (LIVING-DESCRIPTIONS)
DOC-14 (SELF-IMPROVING-LOOP) → DOC-18 (archive FEEDBACK-LOOP)
DOC-14 (SELF-IMPROVING-LOOP) → DOC-21 (PATTERN-RECORDING)
DOC-06 (visual) → parallel with any, HITL gated
```

## Shared disciplines (apply verbatim to every issue)

### Core
1. Never assume. Chase every claim to its source code. No band-aids.
2. TDD: red → green → refactor, one acceptance criterion at a time.
3. Verify adversarially — "this looks right" is not verification. Run the code.
4. Dispatch to fresh agents, then independently re-verify their work.
5. Report faithfully — include what you couldn't verify, any missteps.

### ORC/Grain (all 12, adapted for doc work)
1. Never accept "claim looks right" — verify by running against `orc-template`.
2. Every code example follows commands → events → projections; no bare state assertions.
3. Real `orc-template` execution — no invented outputs; actual REPL output embedded.
4. No false green: code that runs but produces different output than stated is a FAIL.
5. Deterministic claims (component deps, event schemas, scale mappings) verified from source.
6. Right node type: examples use the node that fits the work; don't force `:repl-researcher` for single-turn reasoning.
7. Re-orchestrate, don't rewrite: reference existing verified patterns from codebase.
8. Domain-agnostic examples: contract-analysis examples must work with substitution for any domain.
9. `:reasoning`-first: every `:llm` example node writes `:reasoning` first in `:writes`.
10. JVM hygiene: stop `orc-template` after every code verification run; 0 orphan JVMs.
11. No truncation: embed full REPL output verbatim; never truncated for convenience.
12. Parallel `map-each` leaf must be primitive: if any example uses `:map-each`, leaf is `:llm`/`:code` with explicit `:writes`.

## Sequence rules

- Every slice leaves the docs directory in a coherent, cross-linkable state.
- No issue touches content owned by another issue — coordinate via `docs/issues/doc-overhaul/`.
- Wave 2 agents must read the Wave 1 output before writing.
- After each slice: orchestrator runs `/inspect-orc` — re-runs all code examples, re-reads the doc, tries to break claims. Never mark done until inspected.