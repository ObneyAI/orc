# MT-1 — Deterministic container shape-classification + structural noise pre-filter

## Parent
[Meaningful multi-table extraction](README.md) · PRD: `../../prd/meaningful-multi-table-extraction.md`

## What to build
A deterministic, domain/format-agnostic classifier that, given a container's cheap structural facts (column names, a small real sample, key cardinality, row count), assigns a SHAPE and a keep/drop verdict:
- **bridge** — ~2 columns, both high-cardinality identifiers/codes, NO measure/free-text column (a pairing of two non-primary entities). → DROP (it's an edge, not an extractable entity table; O\*NET's `Abilities to Work Activities` is this).
- **reference/lookup** — small row count, a code column + a label/description, no measures. → DROP for entity extraction.
- **entity** — one row per entity: an identifying key + attribute columns. → KEEP, tag `:entity`.
- **long-form** — a key column + an element/label column + a value column; the key repeats across many rows (one per element). → KEEP, tag `:long-form`.
- **wide-stats** — one subject per row with many measure columns. → KEEP, tag `:wide-stats`.

Reads only structure (via the existing container tools — `sheet-columns`, `mechanical-sample-rows`, `count-rows`); names NO domain column. Emits, per container, `{:name :shape :keep? :roles}` where `:roles` (for long-form) marks candidate key/element/value columns for MT-3.

## Acceptance criteria
- [ ] On the REAL O\*NET `db_30_1_excel`: `Abilities to Work Activities` + `Abilities to Work Context` → `bridge`, DROP; `Occupation Data` → `entity`, KEEP; `Skills`/`Knowledge`/`Abilities` → `long-form`, KEEP; a scales/reference lookup → `reference`, DROP.
- [ ] Verified on a SQL source + a CSV source too (domain/format-agnostic — the same classifier, no per-format branch).
- [ ] Pure/deterministic: same container facts → same verdict, no LLM, no `Math/random`.
- [ ] TDD red→green on the classifier through its public fn (feed real container facts; assert shape + keep? + roles). Ontology brick gate green; 0 orphan this-repo JVMs.

## /prototype
YES — pull the REAL structural facts for ~8 representative O\*NET tables (a bridge, a long-form, an entity, a lookup) via `sheet-columns` + `mechanical-sample-rows`, and confirm the structural signals (column count, key cardinality, measure-column presence, row count) actually separate the classes BEFORE codifying thresholds. Do not assume the signal separates — measure it.

## Blocked by
None — can start immediately.

## Handoff focus
Read-first: `discovery_tree.clj` `mechanical-sample-rows` + `profile-node-prompt` (the Survey's per-container introspection), `source_tools_excel.clj` `sheet-columns`/`do-excel-dir-sheets`, `extract_subbehavior.clj` `list-source-containers`. The classifier is a new PURE fn (structural); it does NOT yet change selection (that's MT-2) — MT-1 only produces the shape verdict + a test proving it on real O\*NET tables. Re-orchestrate (reuse the container tools), don't fork. One bounded build; `pgrep -f` hygiene.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse the container tools; do NOT fork.
9. Adversarial qualitative verdict — hunt for a mis-classified container masked as success; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — MT-1 IS the deterministic part; verify it against the real LLM-authored downstream in later slices.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — structural signals only; name NO O\*NET/CIP/SOC column or entity; no hardcoded table names.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
