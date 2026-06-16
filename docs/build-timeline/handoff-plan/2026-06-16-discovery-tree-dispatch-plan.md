# Discovery-Tree Redesign — Slice Dispatch Plan

How the 10 discovery-tree slices get implemented: per-slice `/handoff` context,
the `/prototype` directive, the slice-by-slice sequencing, and the binding
after-each verification protocol. Parent:
`docs/build-timeline/issues/discovery-tree/` (the slices) +
`docs/build-timeline/prd/2026-06-16-discovery-behavior-tree-redesign.md`.

Each subagent receives: its slice file (acceptance criteria + the SHA-identical
disciplines block — BINDING), the curated handoff context below, the prototype
directive, and the standing instruction to implement via `/tdd` with real-LLM
live verify before declaring done. One commit per slice, audited by path.

---

## After-EACH verification protocol (binding — main thread, never skipped)

We never 100% trust a subagent. After every slice returns, BEFORE marking it done
or dispatching the next:

1. **Combined regression sweep** — the prior discovery-tree suites + the reused
   suites (V18/V20/V06/V19/S12/S17/S18) green via `:dev:test` (the `:poly test`
   ontology brick is known-unrunnable — pre-existing `description_events_test`/
   `seed-descriptions` classpath gap).
2. **Adversarial QUALITY review** (disciplines 2/4/9) — read the actual tests +
   implementation; ask "how could this pass while still being wrong?"; confirm a
   REAL-LLM live verify happened and INSPECT the captured output (not just "tests
   green"); confirm no fallback masks a bug; confirm root-causing.
3. **Inspect-what-we-expect** — verify each acceptance criterion by reading the
   real artifact (e.g. DT2: a real profile matching the contract; DT3: the grain+
   scope decision on a real profile; DT7: reconcile-not-duplicate against existing
   graph state; DT8: a failing CQ closed by focused re-extract + an unanswerable
   CQ surfaced honestly).
4. **Disciplines audit** — all 12 honored; especially 8 (reuse not fork —
   confirm V18/V20/S12/S03 are reused, not duplicated), 12 (domain-agnostic — scan
   the diff for CIP/SOC/OPEID/education leakage), 11 (co-author + file-scope +
   self-contained + no committed key).
5. Only then: mark done, update the combined sweep, dispatch the next slice.

---

## Per-slice handoff dossier + prototype directive

### DT1 — scaffold + orchestration · prototype: YES
- **Handoff:** RLM-GUIDE (tree DSL, `:repl-researcher` as a node in a larger
  tree, drill-down primitives node-output/node-input-profile/tree-failures);
  `run-discovery!` (what's replaced — read its loop + blackboard/sandbox-vars);
  `build!` return shape (`:status`/`:graph-health`/`:exit-criterion`); V20
  apply-step (the extract stage).
- **Prototype:** prove the tree composes, nodes pass the contract via the
  blackboard, `build!` is invoked, and the CQ verdict is readable — on ONE real
  source, thin nodes OK. Then TDD-harden the orchestration + contract.

### DT2 — Profile node · prototype: WORTH
- **Handoff:** the profile contract (PRD M2); V06/V19 specialist tools per medium;
  node-output primitive; the DT1 scaffold + blackboard contract.
- **Prototype:** settle the SMALL prompt + the profile contract shape against a
  real source (the S19 lesson: the node must work from the tool docstrings).

### DT3 — Model+grain+scope node · prototype: WORTH
- **Handoff:** the model-spec contract (PRD M2); the V17/V20 over-extraction
  lesson (raw-row dump, no scope); the DT2 profile as input.
- **Prototype:** prove grain + scope decisions on a real profile that previously
  over-extracted (e.g. a breakdown-heavy table → canonical-row grain + goal scope).

### DT4 — Transform-design node · prototype: SOFT
- **Handoff:** V20 apply-step + transform contract; the DT3 model-spec as input.
- **Prototype (soft):** light probe of the node that AUTHORS the transform from a
  model-spec; the apply-step itself is V20-proven.

### DT5 — Requirements/CQ node · prototype: WORTH · HITL
- **Handoff:** S14 ORSD store + S15 CQ runner; goal X profiles as input; the HITL
  review surface; default gate (pass-rate>=0.8, unknown-rate<=0.3).
- **Prototype:** derive CQs on the real BRYC profiles; confirm they're sane +
  goal-anchored + reviewable before TDD.

### DT6 — promotion seam · prototype: NO
- **Handoff:** classify-behaviors interface + seed corpus; the minting-rework
  caveat (clean boundary, no coupling to current internals); the DT2/DT3/DT4
  prompt-assembly sites.

### DT7 — cross-source linking/reconcile · prototype: WORTH
- **Handoff:** S03 alignment registry, S12 dedup cascade, S21 embeddings/ColBERT,
  V18 referential integrity; the against-current-graph-state seam; the maintain
  handoff.
- **Prototype:** prove cross-source merge + ambiguity surfacing against EXISTING
  graph state (run twice / pre-populated graph → reconcile not duplicate).

### DT8 — CQ-loop + recovery · prototype: YES
- **Handoff:** `build!` CQ verdict; drill-down (tree-failures, graph-health);
  the focused-failure-recovery pattern (self-improving loop); re-extract-vs-
  unanswerable logic; budget bound.
- **Prototype:** prove a failing CQ is closed by a FOCUSED re-extract, and a
  genuinely-unanswerable CQ terminates honestly (no spin, no false green).

### DT9 — greenfield/maintain branch · prototype: NO
- **Handoff:** the maintain handoff (branch-condition shape); greenfield is the
  built arm, maintain an explicit deferred stub.

### DT10 — end-to-end live verify · prototype: NO · HITL
- **Handoff:** the 5 BRYC sources + paths; the acceptance criteria (LA scale,
  NODES not edges, earnings attributes, 0 dangling, CQ verdict, honest
  unanswerables); the V09/V17/V20 capture pattern. The user signs off.

---

## Sequencing

Slice-by-slice (the directive): **DT1 → DT2 → DT3 → DT4 → DT7 → DT8 → DT10** on
the critical path, with **DT5** after DT2, **DT6** after DT2–DT4, **DT9** after
DT1 — slotted in where they don't block the critical path. Each slice runs its
`/handoff` → `/prototype` (where flagged) → `/tdd` → the after-each protocol
before the next. No big parallel waves by default — inspection discipline first;
a wave only where two slices are clearly file-disjoint and both verified after.

## Prototype summary
YES: **DT1** (orchestration), **DT8** (adaptive loop). WORTH: **DT2**, **DT3**,
**DT5**, **DT7**. SOFT: **DT4**. NO: DT6, DT9, DT10.
