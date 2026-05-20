# Upstream PR — translating predict-rlm comparison work to ORC main

Local issue tracker for the 2-PR upstream effort: PR-Framework (9 RLM framework upgrades) + PR-Bench (predict-rlm comparison benchmark suite).

Parent PRD: [`docs/prd/upstream-pr-plan.md`](../../prd/upstream-pr-plan.md).
Technical upgrade spec: [`docs/prd/orc-rlm-upgrades.md`](../../prd/orc-rlm-upgrades.md).

---

## ⚠️ Where we left off (read this first if resuming)

**Status:** Planning complete; second clean sweep of main complete (2026-05-20). Execution starts at `US-01-Sync`. No PR branches built yet.

**Sequence:**
1. US-Sync — preserve current work + merge `origin/main` (9 new commits, includes R-1 + R-2 convergence) + regressions GREEN
2. US-FW-A → US-FW-B → US-FW-C → US-FW-Open (PR-Framework, **9 upgrades after R-2 convergence**)
3. US-Bench-Bricks → US-Bench-RunnerReports → US-Bench-Open (PR-Bench)
4. US-Recomb — final claim verification across both PRs

---

## ⚠️ Updated scope after main re-sweep (2026-05-20)

Main has converged on parts of our work. The updated unique-to-us upgrade list is **9 upgrades** (down from 11):

| # | Upgrade | Status |
|---|---|---|
| ~~U1~~ | SCI safe-clojure-core fix | ✅ on main via R-1 (`ba71447`) |
| ~~U2~~ | DSL `:code` inline-fn | ✅ on main via R-2 (`6747759`) |
| ~~U3~~ | Tree executor `:code` string/fn discriminator | ✅ on main via R-2 |
| **U4** | `extract-all-keys` `:fn`-position independence | ✗ ours |
| **U5** | Phase-1 sub-LLM image routing | ✗ ours |
| **U6** | Phase-2 child-sheet schema preservation | ✗ ours |
| **U7** | Phase-2 `:code` output reconciliation | ✗ ours |
| **U8** | Inline-fn event sanitization (Fressian) | ✗ ours |
| **U9** | Prompt updates (delta) | ✗ ours (PARTIAL — main has R-2's `:code` + drill-down doc; we add emit-tree-as-default + sequential-`(llm ...)` anti-pattern + `:output-schemas` advertising + `:available-code-nodes` + `:field-type :image`) |
| **U10** | `:rlm/researcher-iterations` event | ✗ ours |
| **U11** | `extract-key-schemas` + schema-driven declare-key | ✗ ours |
| **U12** | `preview-vector` recursive | ✗ ours |

Multi-tree iteration was shipped on main as `:rlm {:recursive? true}` (R-1) — dropped from our plan.

---

## Issues

| # | ID | Title | Type | Blocked by |
|---|---|---|---|---|
| 1 | [US-01-Sync](US-01-Sync.md) | Merge `origin/main` into our branch + regressions GREEN | AFK | none |
| 2 | [US-02-FW-A](US-02-FW-A.md) | PR-Framework commit 1: correctness fixes (U4 + U5 + U6 + U7) | AFK | US-01-Sync |
| 3 | [US-03-FW-B](US-03-FW-B.md) | PR-Framework commit 2: schema-driven structured output + event sanitization (U8 + U11) — **scope reduced after R-2 convergence** | AFK | US-02-FW-A |
| 4 | [US-04-FW-C](US-04-FW-C.md) | PR-Framework commit 3: prompt updates (delta) + observability (U9 + U10 + U12) + RLM-GUIDE docs | AFK | US-03-FW-B |
| 5 | [US-05-FW-Open](US-05-FW-Open.md) | Push `feature/rlm-framework-upgrades` + open PR | HITL | US-04-FW-C |
| 6 | [US-06-Bench-Bricks](US-06-Bench-Bricks.md) | PR-Bench commits 1-3: 3 Polylith bricks + 2 task definitions | AFK | US-04-FW-C |
| 7 | [US-07-Bench-RunnerReports](US-07-Bench-RunnerReports.md) | PR-Bench commit 4: runner + reports + references + README | AFK | US-06-Bench-Bricks |
| 8 | [US-08-Bench-Open](US-08-Bench-Open.md) | Push `feature/predict-rlm-comparison-bench` + open PR | HITL | US-07-Bench-RunnerReports, US-05-FW-Open |
| 9 | [US-09-Recomb](US-09-Recomb.md) | Final re-comb of doc claims against live behavior | HITL | US-08-Bench-Open |

## Dependency graph

```
US-01-Sync (merge main; resolve R-1 + R-2 + doc conflicts)
  └── US-02-FW-A (U4 + U5 + U6 + U7 — pure bug fixes)
        └── US-03-FW-B (U8 + U11 — capability delta after R-2 took U2/U3)
              └── US-04-FW-C (U9 delta + U10 + U12 + RLM-GUIDE additions)
                    ├── US-05-FW-Open (HITL)
                    │
                    └── US-06-Bench-Bricks (3 Polylith bricks + 2 tasks)
                          └── US-07-Bench-RunnerReports (runner + reports + refs + README)
                                └── US-08-Bench-Open (HITL; also depends on US-05-FW-Open)
                                      └── US-09-Recomb (HITL)
```

## Rigor commitments (carried through from PRD)

- Every claim in `docs/RLM-GUIDE.md` updates verified against a test or live run before submission. Pattern follows the existing `development/src/rlm_guide_examples_verify.clj` precedent.
- Every command-line in the bench `README.md` verified against fresh-checkout reproducibility.
- All predict-rlm comparison numbers sourced verbatim from their `sample/output/output.md` (NOT their README, which has different numbers).
- US-Recomb (HITL) is the final pre-submission verification checkpoint — not optional, not skippable.

## Sweep log

| Date | What changed | Action taken |
|---|---|---|
| 2026-05-20 (initial) | Original 9-slice plan based on `37cf07d` main HEAD | Slice files created with 11 upgrades planned |
| 2026-05-20 (re-sweep) | Main advanced 9 commits: R-1 + R-2 + 4 docs + 2 other | Updated US-01-Sync with merge expectations; reduced US-03-FW-B scope (drop U2/U3); adjusted US-04-FW-C U9 description; updated US-05-FW-Open PR description |
