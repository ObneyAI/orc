# GM-5 — Non-mutating minting-rate / ontology-conformance measurement

## Parent
[PRD](../../prd/graph-grounded-modeling-and-reformation.md)

## What to build
A deterministic, NON-MUTATING measurement surfaced in the build report so "did we reduce mis-minting?" is a NUMBER, not a vibe: the rate at which the builder MINTS a new entity type vs REUSES an existing one, and an ontology-conformance signal (how much of a source's output maps onto the existing schema vs invents new classes). Read-only over the graph/mentions — it NEVER mutates the model-spec or gates by rewriting (the exact anti-pattern that sank the prior approach). It may inform an honest report / an optional abstain signal, never a silent repair.

## /prototype
None.

## Acceptance criteria
- [ ] A minting-rate + conformance metric is computed read-only from the graph/mentions and surfaced in the build report (honest, observable).
- [ ] It NEVER mutates the model-spec or the graph; it does not gate the build by rewriting.
- [ ] Applied to the GC-13 baseline vs a GM-1 pseo build, the metric MEASURABLY reflects the reduced mis-minting (a real before/after number).
- [ ] TDD (red→green) on the pure metric function; behavior through public fns.
- [ ] Domain-agnostic; ontology brick gate green; 0 orphan this-repo JVMs.

## Blocked by
None — independent (most useful once GM-1 lands, to measure the delta). /handoff can be written now.

## Handoff focus
Read-first: `read_models.clj` (concept/relationship projections, `concept-statistics`), the graph-health pattern in `eb12_graph_b_central_evolver.clj`, Text2KGBench-style conformance framing (see the PRD grounding). Pure read-only metric — no mutation, no gate.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse existing read-models/stats; do NOT fork.
9. Adversarial qualitative verdict — a metric that looks good while the graph is wrong is a FAIL; verify it tracks reality.
10. "Deterministic skeleton" ≠ LLM-free — the metric is deterministic; verify it reflects the real LLM-authored output.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Domain/format-agnostic — the metric names NO domain column; structural/schema-conformance only.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
