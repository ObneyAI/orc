# Phase 2 Observability Layer — Issue Breakdown

**Parent PRD:** [`docs/prd/phase2-observability-layer.md`](../../prd/phase2-observability-layer.md) (local, on feature branch)

**Status:** Local issue tracking — these files will NOT be committed to main. They are reference documentation for work happening on `feature/core-orc-upgrades`.

## Issue List

| # | Title | Type | Blocked by |
|---|-------|------|------------|
| O01 | [Align preview tests to actual architecture](O01-align-preview-tests.md) | AFK | None |
| O02 | [Per-node usage events + EDN visibility](O02-per-node-usage-events.md) | AFK | O01 |
| O03 | [Tree-execution bookend + input profile + reports](O03-trajectory-input-profile-reports.md) | AFK | O02 |

## Dependency Graph

```
O01 ─→ O02 ─→ O03
(test  (per-node    (bookend +
 align) usage)       input profile +
                     report viz)
```

## Sequence Rules

- Each slice ends with the feature branch in a runnable state
- Each slice runs the framework tests (24 tests / 95 assertions / 0 failures) + G04 + G07 benchmarks before commit
- Output spot-checked against source documents (zero hallucinations) — same standard as the bench reorg
- All event emissions flow through proper Grain commands (commands emit events; processors react)

## Verification Standards (apply to all slices)

Before any commit:
- Framework tests pass: `rlm-tree-executor-test`, `rlm-dsl-test`
- G04 (legal-issue-detection, ~9s) end-to-end via NEW API (`runner/run! legal-issue-detection/task`)
- G07 (risk-analysis, 60-200s, exercises emit-tree! parallel path) end-to-end via NEW API
- New event fields populated correctly in saved EDN
- For O02+: `:by-node` totals approximately equal aggregate `:total-tokens` (within small rounding)

## Deferred work (NOT part of this batch)

- Category C — Self-improving loop (judges, fingerprint, pattern ontology integration)
- Category D — Resilience (partial map-each results, budget-aware timeout)
- Category E — Big-vision items (conversational debugging, skills→trees, personality layer)

See the parent PRD's "Out of Scope" section for details.
