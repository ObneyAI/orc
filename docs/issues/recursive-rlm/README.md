# Recursive RLM — Issue Breakdown

**Parent PRD:** [`docs/prd/rlm-recursive-emit-tree.md`](../../prd/rlm-recursive-emit-tree.md) (local, on feature branch)

**Status:** Local issue tracking — these files will NOT be committed to main. They are reference documentation for work happening on `feature/core-orc-upgrades`.

## Issue List

| # | Title | Type | Blocked by | Status |
|---|-------|------|------------|--------|
| R-1 | [Core recursive loop](R-1-core-recursive-loop.md) | AFK | None | ✅ merged to main |
| R-2 | [Drill-down primitives](R-2-drill-down-primitives.md) | AFK | R-1 | ✅ merged to main |
| R-3 | [Inline-fn Fressian sanitization](R-3-inline-fn-fressian-sanitization.md) | AFK | R-1 | ✅ committed (905s → 131s, 6.9× faster, 0 Fressian errors) |
| R-4 | [Sub-model injection in recursive mode](R-4-sub-model-injection-recursive.md) | AFK | R-3 | ✅ committed (Phase-2 :llm leaves now hit gpt-5.1-chat, not gemini-3-flash) |
| R-5 | [History reuse hint](R-5-history-reuse-hint.md) | AFK | R-3 | ✅ committed (document_redaction 197s → 60s, 3.3× faster) |
| R-Bench | [5-benchmark recursive verification sweep](R-Bench-recursive-5-benchmark-sweep.md) | HITL | R-3 + R-4 | ✅ **5/5 SUCCESS** — recursive matches/beats terminal mode |
| R-6 | [Syntax-error retry optimization](R-6-syntax-error-retry-optimization.md) | AFK | R-Default | open — optional follow-up |
| R-Default | [Make recursive the default RLM mode](R-Default-make-recursive-default.md) | AFK | R-Bench | **open** — flip the default |

## Dependency Graph

```
R-1 ─→ R-2  (drill-down primitives)
 │
 ├─→ R-3  (Fressian sanitization)
 │    │
 │    ├─→ R-4  (sub-model injection)
 │    │    │
 │    │    └─→ R-Bench  (5-benchmark sweep, HITL)
 │    │
 │    └─→ R-5  (history reuse, optional)
```

## R-3..R-Bench context

The recursive-mode benchmark experiment (`development/bench/predict_rlm_comparison/reports/recursive-mode-experiment.md` + `recursive-mode-plan.md`) ran all 5 predict-rlm benchmarks in recursive mode and found systematic regressions. Two framework fixes were made up-front and committed on `feature/rlm-recursive-mode-fixes`:

- ✅ `validate-final!` rejects all-empty outputs (was: model could call `(final! {:k1 nil :k2 []})` and succeed silently)
- ✅ Recursive-mode prompt leads with "`emit-tree!` is how you do work" (was: model treated emit-tree! as one option among many, never called it, called final! with empty data instead)

After those, recursive-mode runs DO emit trees and produce real output, but surface three further framework bugs (R-3, R-4, R-5 above).

End-goal: with R-3 + R-4 fixed (and ideally R-5), recursive mode passes the 5-benchmark sweep (R-Bench) and can become the always-on default.

## Priority — these land BEFORE Category C

User's explicit instruction: **prioritize these issues BEFORE the self-improvement loop (Category C — judges, fingerprints, ontology pattern matching).** Category C is **deferred-not-abandoned**. The `:tree-results` data layer built in R-1 feeds DIRECTLY into Category C's judges — each tree-results entry becomes a candidate scoring input for the judge layer. This work is foundational plumbing, not a re-prioritization.

When Category C issues eventually get created on this branch, R-1 and R-2 must be sequence-before-them.

## Sequence Rules

- Each slice is end-to-end testable on its own and leaves the feature branch in a runnable state.
- Each slice ends with a **live real run** (real LLM, real event store, real Grain pipeline) confirming production-truth — per the project's verification rule.
- All event emissions flow through proper Grain commands (commands emit events; processors react). No direct event-store writes.
- Both slices are opt-in via `:rlm {:recursive? true}` on the repl-researcher node config. Existing trees (`:rlm true` or `:rlm {:debug? true}`) keep current terminal behavior — no regression to the canonical bench suite.
- Framework tests must remain green on the RLM-related namespaces (baseline 142 tests / 507 assertions / 0 failures as of the D-003 merge).
- After both slices verify live, framework changes get ported to `main` via selective-checkout (same pattern as O01+O02+O03, D-008, D-003).

## Why this order

**R-1 first** because:
- It ships the architectural foundation — the dispatch change, sandbox-vars merge, `:tree-results` summary, and prompt update. Without R-1 the model has no recursion at all.
- The summary alone (no drill-down primitives) is sufficient for the model to reason about partial outcomes and decide its next move. R-1 is end-to-end shippable.
- R-1's live verification (two-step task) proves the recursive flow works with a real LLM. That's the gating signal for R-2.

**R-2 second** because:
- It enriches the model's inspection capability with five new SCI sandbox primitives (`tree-detail`, `tree-trajectory`, `tree-failures`, `node-output`, `node-input-profile`).
- All primitives read existing event-store data from O02/O03 — no new events captured.
- R-2's live verification (adaptive task with sentinel failure) proves the model uses the new primitives to reason about failures and react appropriately.

## Cross-references to prior work

This work builds on:

- [`docs/prd/phase2-observability-layer.md`](../../prd/phase2-observability-layer.md) — O02 per-node usage events with structured paths + O03 bookend event with full trajectory + `:input-profile`. These events ARE the data source for R-2's drill-down primitives. DONE on `main`.
- [`docs/prd/category-d-resilience.md`](../../prd/category-d-resilience.md) — D-008's `:partial-summary` provides failure detail for R-1's tree-results summary; D-003's `:timeout-ms` + cancellation provides the budget machinery that bounds recursive tree execution. DONE on `main`.

## Out of scope (deferred to future work)

- **Removing the terminal `emit-tree!` path.** Deprecation plan exists in the PRD; the actual removal is a follow-up slice gated by all bench tasks being migrated to `:recursive?` and verified.
- **Full bench reconvert.** Migrating all 5 canonical bench tasks (`document-analysis`, `risk-analysis`, etc.) to `:rlm {:recursive? true}` — separate slice. Not blocking R-1 or R-2.
- **Category C (self-improving loop).** Deferred-not-abandoned; will pick up after R-1 + R-2 ship.
- **Tree-in-tree recursion.** A Phase 2 tree containing nested repl-researcher nodes — tangential concept, not in scope here.
