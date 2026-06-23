# General-Purpose Evolutionary Ontology Builder — Issue Slices (EB)

Local issue slices for the composed-subbehaviors re-architecture. Parent PRD:
`docs/build-timeline/prd/2026-06-17-general-purpose-evolutionary-ontology-builder.md`.

Goal: a CENTRAL ORC evolver tree that composes/`:delegate`s to durable, evolvable
SUBBEHAVIOR sheets, pursuing CQ-satisfaction as its objective, maintain-native,
reusing the proven deterministic skeleton. Re-orchestration + deepening — composes
6 corpus behaviors, mints 3 gaps (Reconcile, Axiom/TBox, Maintain-evolutionary),
carries the 5 under-served pillar commitments first-class, re-houses DT1–DT9 logic.

Local-only. Every slice carries the SAME binding **Core Disciplines** block (1–13,
incl. #13 `:reasoning`-first on every `:llm` node, node-scoped in concurrent
contexts).

## Slices

| # | Slice | Type | Prototype | Blocked by |
|---|-------|------|-----------|------------|
| EB1 | Subbehavior-sheet harness + `:delegate` composition seam | AFK | YES | — |
| EB2 | Survey subbehavior (`:repl-researcher`, terminal) | AFK | WORTH | EB1 |
| EB3 | Model subbehavior (`:llm`: grain/scope + embed-fields + candidate axioms) | AFK | WORTH | EB1,EB2 |
| EB4 | Extract subbehavior (`:code`→`:llm`→`:code` V20 apply) — **DONE** (autonomous transform live-verified: 572 scoped concepts / 286 rels on real CSV, no hand-correction) | AFK | SOFT | EB1,EB3 |
| EB5 | Reconcile subbehavior (entity+attribute, check-before-mint) — **DONE** (deterministic, no fork; live-verified: check-before-mint fired pre-mint w/ real ColBERT, reconcile-not-duplicate 6→7 on 2nd pass, attribute-links, 0 dangling) | AFK | WORTH | EB1,EB4 |
| EB6 | Axiom/TBox subbehavior (mint) — **DONE** (emits real TBox axioms via S07 + MINTED assert-sub-class; live-verified axioms LAND via get-axioms; closes :axioms-skipped; honest-gap surfaces domain/range/closure + ungrounded) | AFK | WORTH | EB1,EB3 |
| EB7 | Embed+Index subbehavior (guaranteed P2) — **DONE** (embed+ColBERT-index fire BY DEFAULT, no caller wiring; live-verified: 10 embedded + read back, index built, hybrid-search returns labeled semantic hits) | AFK | WORTH | EB1,EB3 |
| EB8 | Validate+CQ subbehavior (semantic + derive/check CQs) — **DONE** (live-verified: derives 5 grounded goal-anchored CQs, persists as ORSD, S15 semantic gate verdict pass/unknown/fail + graph-health; consumer-override; HITL CQs surfaced) | HITL | WORTH | EB1,EB2 |
| EB9 | Subbehavior-internal resilience (fallback/condition/troubleshoot) — **DONE** (reusable `with-resilience`; composed into the 3 :llm subbehaviors; live-verified: recoverable→fallback recovers 572 concepts, unrecoverable→clean failure + real-LLM diagnosis, downstream not poisoned; + scoped orc-service delegate-failure-write fix) | AFK | WORTH | EB2–EB8 |
| EB10 | Central evolver loop (CQ-gate-as-objective + routing) — **DONE** (composes all subbehaviors via :delegate; CQ-gate is the loop objective: fail→route→focal-close→re-gate, unanswerable/budget→honest :failed-cq NEVER false-green; greenfield/maintain branch; live-verified end-to-end on a real source) | AFK | YES | EB2–EB8,EB9 |
| EB11 | Maintain (evolutionary: new classes/attrs vs existing graph) — **DONE** (flipped the maintain arm from the deferred stub to the REAL composition via a shared `run-evolver-pipeline!` BOTH arms run — re-orchestration, no fork; the subbehaviors are against-graph-state so maintain = the pipeline against a populated graph; live-verified: a real 2nd source auto-selected `:maintain`, grew the graph 8→11, introduced a NEW class kind connected to existing entities, reconciled-not-duplicated [`:exact-uri-hits 3`], and a previously-unanswerable certification-CQ now PASSES; idempotent + attribute-granularity link proven deterministically in the prototype + hermetic test) | AFK | WORTH | EB5,EB6,EB10 |
| EB12 | End-to-end acceptance on the BRYC 5 (verify-not-assume) | HITL | NO | EB1–EB11 |

## Dependency graph

```
EB1 ─┬─> EB2 ─┬─> EB3 ─┬─> EB4 ─> EB5 ─┐
     │        │        ├─> EB6        │
     │        │        └─> EB7        ├─> EB9 ─> EB10 ─> EB11 ─┐
     │        └─> EB8 ──────────────  ┘                       ├─> EB12 (HITL)
     └────────────────────────────────────────────────────────┘
```

## Posture
- Slice-by-slice: each `/handoff` → `/prototype` (where flagged) → `/tdd` → the
  **after-each `/inspect-orc` protocol** before the next.
- DT1–DT9 logic is REUSED (re-housed), not rebuilt; F3 dissolves (single-turn
  reasoning is `:llm`/`:code`, not forced `:repl-researcher`). F1/F2 survive in
  `:code` stages (`docs/build-timeline/issues/discovery-tree/DT-followups.md`).
- Deferred (sequenced): P4-LEARN (M6 seam only), minting internals, RLM-emitted
  `:fallback`, Maintain's full multi-source at-scale verification (with EB12).
- Dispatch detail (per-slice handoff + prototype + the `/inspect-orc` protocol +
  its ORC/Grain disciplines): `docs/build-timeline/handoff-plan/2026-06-17-evolutionary-builder-dispatch-plan.md`.
