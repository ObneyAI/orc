# MT-7c — Acceptance: unfragmented comprehensive build, CQ-gate as the cross-run criterion

## Parent
[Meaningful multi-table extraction](README.md) · Design: [ADR-0001 canonical vocabulary binding](../../../../components/ontology/docs/adr/0001-canonical-vocabulary-binding.md) (settled — do not re-litigate).

## What to build
The end-to-end acceptance for the vocabulary-binding line — run the comprehensive O\*NET build through the full pipeline (≥2 clean solo runs) and verify:

1. **Within-run fragmentation is GONE** — every landed draft's entity-type resolves to the run's canonical vocabulary (declared + admitted proposals); one URI scheme per type; the same real entity is ONE node (the previous ~3971-node occupation split collapses to the vocabulary's own entity count). Freelancing count = 0 (any author failure is an HONEST per-container failure in the report, not a freelanced draft).
2. **Cross-run acceptance = the CQ-gate**, per ADR-0001 — two runs may legitimately discover different (both valid) vocabularies/grains; the criterion is that BOTH pass the CQ-gate and BOTH are internally unfragmented. Structural sameness across runs is explicitly NOT required (recording that in the acceptance verdict fn).
3. **A2-vs-B unblocked, reframed as capability** — the comparison measures what questions each graph answers + fact coverage (e.g. % of occupations whose skills are retrievable), not shape (node counts per type).

## Acceptance criteria
- [ ] Comprehensive O\*NET build ×2 (one bounded build at a time): each run completes; each run's landed graph has ZERO freelanced entity-types (every type ∈ that run's vocabulary ledger); the same-entity split observed pre-fix does not recur within a run. Verified by reading the projection.
- [ ] Both runs evaluated by the CQ-gate; the verdict fn treats CQ-pass + internally-unfragmented as PASS even when the two runs' vocabularies differ (a structural-sameness check exists only as a NON-gating diagnostic).
- [ ] The extraction/proposal ledgers are surfaced per run (declared, admitted, requires-review, honest failures) — observable, no false-green.
- [ ] A capability-based A2-vs-B measurement runs on graph B (facet retrievability vs the A2 baseline), acknowledging structural difference honestly.
- [ ] Domain-agnostic implementation confirmed (scan the impl, not the drivers, for domain names). Ontology brick gate green; 0 orphan JVMs.

## Type
HITL — the acceptance verdict + the A2-vs-B reframe deserve human review on the real results.

## Blocked by
MT-7a + MT-7b (the binding + proposal path must land first). Handoff written after both land, from real signatures.

## /prototype
Not required — this slice exercises the composed pipeline and measures.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that (an "unfragmented" run achieved by mass per-container failures is a hollow pass — check the honest-failure counts too).
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces (the verdict fn is pure + tested on fixtures).
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — the acceptance exercises the composed pipeline; do NOT fork.
9. Adversarial qualitative verdict — hunt for a freelanced draft, a hollow pass via mass failures, or fragmentation hiding in an unexamined type; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the deterministic binding/ledgers AND the LLM-discovered vocabulary quality on the real build.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — scan the IMPLEMENTATION (not the drivers) for O\*NET/CIP/SOC leakage; this is the general system TESTED with O\*NET, not built FOR it.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
