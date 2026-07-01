# GM-2 — Mention event + re-derived role/identity view (the CALM substrate)

## Parent
[PRD](../../prd/graph-grounded-modeling-and-reformation.md) · [ADR 0001](../../../adr/0001-mention-log-rederived-view.md)

## What to build
Introduce the substrate that makes reformation order-independent (per the ADR): store every extraction as an immutable append-only **mention**, and treat concepts/relationships + the entity-vs-attribute ROLE + canonical identity as a **re-derived VIEW** over the mention log — not state mutated in place.

- A `mention` event (append-only) capturing one extracted assertion + its provenance (source, row/container, the raw draft, the dimension/keying VALUES).
- A **derivation projection** that produces the concept/relationship/role view from the mention log (this slice: derive-from-mentions must reproduce TODAY's graph).
- Concepts become a derived view; "role" (entity vs attribute vs observation) is a function of the mentions, re-derivable.

This slice does NOT yet re-derive on new evidence (that's GM-3) — it establishes the log + derivation with BYTE-FOR-BASELINE PARITY so nothing depends on an unproven substrate (the ADR's reversibility mitigation).

## /prototype (REQUIRED before TDD)
Prototype the mention→derive projection on a small real build: capture mentions, derive the graph, and diff it against the current direct-build graph on the clean sources. Prove the derivation reproduces the same concepts/edges (byte-for-baseline) BEFORE formalizing the event/projection via TDD. Surface any gap honestly.

## Acceptance criteria
- [ ] A `mention` event + schema exists (Grain: command → schema-validated event → projection; assert it LANDED by reading the projection).
- [ ] The derivation projection produces concepts/relationships from mentions.
- [ ] **LIVE parity:** the clean 3-source graph DERIVED-from-mentions equals today's directly-built graph (same concepts/rels within LLM variance; the 447/338/0 baseline holds).
- [ ] The mention log is append-only (no mutation/retraction of a landed mention).
- [ ] TDD (red→green) on the pure derivation function (mentions → concepts/roles) with fixtures; behavior through public projection/query fns.
- [ ] Recursive-only RLM untouched; ontology brick gate green; 0 orphan this-repo JVMs.

## Blocked by
None — foundational. /handoff can be written now. (Sequenced after GM-1 for the quick win; not a hard dependency.)

## Handoff focus
Read-first: `commands.clj` (existing `:ontology/` create-concept/relationship + the equivalence non-destructive pattern), `read_models.clj` (projections, `get-concepts`/`get-relationships`), `central_evolver.clj` (land/reconcile path where mentions originate), `reconcile_subbehavior.clj` (LAND reuses `compile-discovery-source!`). The change adds a mention event + a derivation projection ALONGSIDE the current path, proving parity — re-orchestrate, do not fork the whole builder.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse the existing land/compile + projection machinery; do NOT fork the builder.
9. Adversarial qualitative verdict — hunt for where the derived view DIVERGES from the baseline; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — the derivation is deterministic; verify the contract AND that the upstream LLM extraction still lands as mentions.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Domain/format-agnostic — the mention/derivation names NO domain column; purely structural + provenance.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
