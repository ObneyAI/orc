# MT-5 — Acceptance: comprehensive O\*NET build + A2-vs-B readiness

## Parent
[Meaningful multi-table extraction](README.md)

## What to build
The end-to-end acceptance that the MT capability produces a CORRECT O\*NET graph, then unblocks the A2-vs-B head-to-head. Run a comprehensive (raised-cap) O\*NET build through the full pipeline (select → aggregate → merge → land → embed) and verify the result models O\*NET the way the reference does (only better-populated).

## Acceptance criteria
- [ ] The O\*NET portion of graph B is ONE occupation node type (per SOC), NO bridge/junction/observation nodes minted (the ability×activity tables are dropped, not modeled).
- [ ] Occupations carry populated `topSkills` + `topKnowledge` (top-N) + `jobZone` + `riasecCode` as flat attributes — i.e. graph B BEATS A2's cache (whose topSkills are empty on ~840/880 occupations). Quantify: % of occupations with non-empty topSkills in B vs A2.
- [ ] Occupation count is sane (~1000 O\*NET-SOC occupations, not per-row fragments; no fragmentation of one occupation across type-names).
- [ ] The build is reproducible across ≥2 clean solo runs (stable occupation count + populated attributes) — no false-green, no contention artifacts (`pgrep -f` hygiene, one bounded build at a time).
- [ ] Domain-agnostic implementation confirmed (scan the impl, not the driver, for baked O\*NET/CIP/SOC names — #12).
- [ ] Then: the A2-vs-B comparison can run on a graph B whose O\*NET is honest (this slice UNBLOCKS it; the comparison harness itself lives in the EB12/BRYC docs).

## Blocked by
MT-4 (needs occurrence-merge so occupations carry cross-container attributes).

## Handoff focus
This is the `/inspect-orc` acceptance for the whole MT line: re-run the comprehensive build MYSELF, read the projection (occupation nodes + their attributes) back, compare populated-skill coverage against the A2 cache, confirm no junction/observation nodes, confirm reproducibility. Read-first: the MT-1..MT-4 slices + the eb12 graph-B driver (`development/src/eb12_graph_b_central_evolver.clj`) + the A2 reference (`.bryc-graph-cache-with-embeddings.json`). One bounded build at a time; confirm 0 orphan this-repo JVMs after.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green (a completed build with 0 populated topSkills or junction nodes is a FAIL).
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — the acceptance exercises the composed pipeline; do NOT fork.
9. Adversarial qualitative verdict — hunt for junction nodes, empty attributes, or fragmentation masked as a "completed" build; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the deterministic selection/merge AND the LLM-authored aggregation on the real build.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — scan the IMPLEMENTATION (not the driver) for O\*NET/CIP/SOC leakage.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
