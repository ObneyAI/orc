# GM-4 — CQ-loop re-derive route

## Parent
[PRD](../../prd/graph-grounded-modeling-and-reformation.md)

## What to build
Extend the CQ-objective loop so a failing ROLE-DEPENDENT competency question drives re-derivation. Today the loop routes {`:extract` `:reconcile` `:axiom` `:model` `:terminate`}; add a `:re-derive` (reformation) route so a CQ like "which program has these earnings?" that fails because earnings are stranded in the wrong role triggers a re-derivation of the relevant region (GM-3's mechanism), then re-gates. Extend the existing loop — do NOT fork it.

## /prototype
Light or none — reuses GM-3's re-derive. If the route decision needs tuning (when to re-derive vs extract), a small live prototype of the ROUTE reasoning may help.

## Acceptance criteria
- [ ] The CQ-objective loop has a re-derive/reformation route; the ROUTE `:llm` reasons over the failing CQ + graph state to choose it (NOT phrase matching, #7/#12).
- [ ] **LIVE:** a program→earnings CQ that FAILS pre-reform (earnings in the wrong role) PASSES post-reform, driven by the loop — proven on a real build.
- [ ] The loop still ALWAYS terminates honestly (re-derive is bounded; no spin; honest `:termination-reason`).
- [ ] TDD (red→green) on the route decision + the loop integration (stub the re-derive seam deterministically); behavior through public interfaces.
- [ ] Recursive-only RLM; ontology brick gate green; 0 orphan this-repo JVMs.

## Blocked by
**GM-3** (needs the re-derive mechanism).

## Handoff focus — DO NOT PRE-WRITE
Craft AFTER GM-3 lands + is `/inspect-orc`'d, from GM-3's real re-derive API + the existing `cq-objective-loop!` / `route-decision!` signatures.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — extend the existing CQ-objective loop; do NOT fork.
9. Adversarial qualitative verdict — hunt for a loop that spins or false-passes; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the loop machinery AND the ROUTE reasoning quality.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Domain/format-agnostic — the route reasons over CQ text + graph state, names NO domain column; no phrase matching.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
