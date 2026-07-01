# GM-3 — Incremental re-derive of the affected region → order-independence

## Parent
[PRD](../../prd/graph-grounded-modeling-and-reformation.md) · [ADR 0001](../../../adr/0001-mention-log-rederived-view.md)

## What to build
Make the graph CONVERGE regardless of source order. On each source-land, RE-DERIVE the AFFECTED REGION of the graph (the mentions sharing a dimension/linking-key VALUE with the newly-landed source), so an earlier mis-modeling self-corrects with the new evidence. Concretely: earnings minted as entities when pseo ran first become Observations of a program once IPEDS lands, because re-deriving the shared-CIP neighborhood now sees the program. "Demote entity→observation" is the view re-deriving — NOT a retraction (per the ADR/CALM).

Incremental (affected region only), not a global re-derive (rejected for cost). Document the affected-region boundary + the bounded order-sensitivity it admits.

## /prototype (REQUIRED before TDD)
Prototype the affected-region computation + the re-derive on a 2-source pair in both orders. Prove the demote-via-re-derivation works (earnings become observations of the program) and that the two orders converge, BEFORE TDD.

## Acceptance criteria
- [ ] On source-land, the affected region (shared dimension/linking-key neighborhood) is re-derived; unaffected regions are untouched (tractable).
- [ ] An earlier entity-mis-model self-corrects to the correct role after later evidence arrives — NO data dropped in the re-derivation (#4/#5).
- [ ] **THE ORDER-INDEPENDENCE ACCEPTANCE (LIVE):** building `[pseo, ipeds, onet, …]` vs `[ipeds, onet, pseo, …]` yields the SAME final graph (same concepts/edges/roles within LLM variance).
- [ ] **Regression gate:** clean sources unaffected when no cross-source evidence changes a role (447/338/0 baseline holds).
- [ ] TDD (red→green) on the affected-region + re-derive functions; behavior through public interfaces.
- [ ] Recursive-only RLM; ontology brick gate green; 0 orphan this-repo JVMs.

## Blocked by
**GM-2** (needs the mention log + derivation projection).

## Handoff focus — DO NOT PRE-WRITE
Per the dependency rule: this handoff MUST be crafted AFTER GM-2 lands and is `/inspect-orc`'d, from GM-2's REAL mention/derivation signatures — the affected-region + re-derive build directly on GM-2's produced API, and pre-writing it would bake in a guessed interface. When GM-2 is inspected, write this handoff from the real fns.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green (a re-derivation that drops data is a FAIL).
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — build on GM-2's derivation + reconcile/spine; do NOT fork.
9. Adversarial qualitative verdict — hunt for non-convergence (two orders differ) + dropped/misattributed data; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — the re-derive is deterministic; verify the convergence contract AND that no LLM re-authoring is silently required.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Domain/format-agnostic — affected-region + re-derive name NO domain column; structural (shared dimension/linking-key values) only.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
