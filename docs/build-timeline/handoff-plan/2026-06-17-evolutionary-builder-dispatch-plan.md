# Evolutionary Builder (EB) — Slice Dispatch Plan

How the 12 EB slices get implemented: per-slice `/handoff` context, the
`/prototype` directive, slice-by-slice sequencing, and the binding after-each
**`/inspect-orc`** protocol. Parent:
`docs/build-timeline/issues/evolutionary-builder/` (the slices) +
`docs/build-timeline/prd/2026-06-17-general-purpose-evolutionary-ontology-builder.md`.

Each subagent receives its slice file (acceptance + the SHA-identical disciplines
block 1–13, BINDING), the curated handoff below, the prototype directive, and the
standing instruction to implement via `/tdd` with real-LLM/real-Grain live verify
before declaring done. One commit per slice, audited by path.

---

## After-EACH `/inspect-orc` protocol (binding — main thread, never skipped)

We never 100% trust a subagent. After every slice returns, BEFORE marking it done
or dispatching the next, run the general `/inspect` routine (re-run the proof
yourself; re-read the real code; try to BREAK it; root-cause; check what's-not-said;
report faithfully) PLUS these **ORC/Grain-focused disciplines** (the `/inspect-orc`
additions — to be baked into a dedicated `/inspect-orc` skill):

1. **Never accept "model variance / transient / flaky"** for an LLM-node result — diagnose to root cause.
2. **Real Grain:** commands → schema-validated events → projections; NO bare appends; assert events LANDED by reading the projection back, not a return value.
3. **Real LLM + real ColBERT + real embeddings** in the live verify; no mocks/stubs for a "done" claim; no invented fixtures.
4. **No false green:** a fallback/degenerate/empty path is not proof; a build that "completed" with 0 concepts / dangling edges / a raw-row dump is a FAIL.
5. **Deterministic-skeleton ≠ LLM-free:** verify BOTH the deterministic contracts AND the LLM-reasoning.
6. **Right node type / recursive-only RLM:** single-turn reasoning is `:llm`/`:code`, not a forced `:repl-researcher`; recursion is intentional (CQ-loop, Survey).
7. **Re-orchestration not rewrite:** confirm reuse of `build!`/S12/V18/V20/S03/S07/S15 + corpus behaviors, not forks.
8. **Domain-agnostic:** scan the IMPLEMENTATION (not the driver) for industry/domain leakage.
9. **`:reasoning`-first (#13):** confirm every `:llm` node writes `:reasoning` first, node-scoped in `:parallel`/`:map-each` (no blackboard trample).
10. **JVM hygiene:** confirm 0 orphan this-repo JVMs after any live run; a lingering hot-loop JVM is a finding.
11. **No truncation** of model-authored output when capturing/comparing.

Then: combined regression sweep (the EB suites + reused build!/S12/V18/V20 suites green via `:dev:test`); inspect-what-we-expect against each acceptance criterion; disciplines audit. Only then mark done + dispatch the next.

---

## Per-slice handoff dossier + prototype directive

- **EB1 (YES):** RLM-GUIDE + node palette (`:delegate` in todo_processors `execute-delegate-node`; `dsl/delegate`); build-workflow!/create-sheet; runtime child-tick; DT1 (replaced). Prototype: delegate-as-composition + the subbehavior-sheet registry round-trips on real Grain.
- **EB2 (WORTH):** DT2 profile-node + frozen contract; V06/V19 specialist tools; the embed-worthy-field signal; repl-researcher TERMINAL mode. Prototype: the focused Survey prompt + contract-incl-embed-signal on a real source.
- **EB3 (WORTH):** DT3 grain/scope; the model-spec contract; V17/V20 over-extraction lesson; the embed-fields + candidate-axioms additions; profile value-shape tolerance. Prototype: grain+scope+embed-fields+candidate-axioms on a real profile.
- **EB4 (SOFT):** DT4 + DT4-grounding (real-row-key surfacing); the V20 apply-step; the transform contract; the model-spec input. Prototype: light probe of the authoring node.
- **EB5 (WORTH):** DT7 reconcile-graph! + against-graph-state seam; S12/S03/S21/V18; the NEW attribute/feature-granularity + check-before-mint hybrid search. Prototype: entity+attribute cross-source merge + check-before-mint vs existing graph.
- **EB6 (WORTH):** S07 axiom commands; the V07 coercion precedent; EB3's candidate-axioms; the `:axioms-skipped` drop. Prototype: real axioms (disjointness + characteristic + subClass) land via S07.
- **EB7 (WORTH):** detect-embeddable-fields / analyze-fields-for-embedding; embed-concept + ColBERT; the EB2/EB3 embed-field signal; the P2 drift; F1 (DT-followups). Prototype: auto-detect → embed + ColBERT fires by default informed by Model.
- **EB8 (WORTH · HITL):** DT5 derive/persist/override; S14 ORSD + S15 CQ runner; semantic-validation; the HITL review surface. Prototype: derive grounded CQs on real profiles; confirm gate-spec persistence.
- **EB9 (WORTH):** `:fallback`/`:condition`/`:llm-condition`; Investigation + Validation behaviors; focused-failure-recovery; FallbackRecovery emit-ability caveat. Prototype: the reusable resilience sub-tree on one induced failure.
- **EB10 (YES):** DT8 CQ-loop + focused-recovery; build! CQ verdict; the routing (CQ+graph-health → subbehavior); the greenfield/maintain `:condition` (DT9); `:map-each`/`:parallel`. Prototype: composed central tree delegates subbehaviors, CQ-fail routes a focused re-invoke that closes it, unanswerable terminates honestly.
- **EB11 (WORTH):** DT9 branch; EB5 multi-granularity reconcile + EB6 TBox-evolution; the maintain handoff; attributes-as-first-class. Prototype: a new source introduces a new class + attribute connecting to an existing entity's attribute, vs an existing graph.
- **EB12 (NO · HITL):** the 5 sources + paths; the verify-not-assume acceptance; the V09/V17/V20 capture pattern. The user signs off; this graph becomes the new-side artifact for the head-to-head.

---

## Sequencing
Slice-by-slice: **EB1 → EB2 → EB3 → (EB4→EB5) ∥ (EB6, EB7) → EB8 → EB9 → EB10 → EB11 → EB12**. EB3's children (EB4/EB6/EB7) can run as a small wave once their shared EB3 contract is frozen + verified; otherwise serial for inspection discipline. Each slice: `/handoff` → `/prototype` (where flagged) → `/tdd` → the after-each `/inspect-orc` protocol.

## Prototype summary
YES: **EB1** (delegation/registry seam), **EB10** (central loop + routing). WORTH: EB2, EB3, EB5, EB6, EB7, EB8, EB9, EB11. SOFT: EB4. NO: EB12.
