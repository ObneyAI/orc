# B03: Add `all.clj` aggregator

## Parent

PRD: `docs/prd/bench-reorganization.md` (local)

## What to build

Add a new `all.clj` aggregator module that requires all 5 task files plus the runner, and exposes two convenience entry points:

- `(all/run-all!)` — runs all 5 tasks sequentially via `(runner/run-all! [...all task vars...])`
- `(all/summary!)` — generates a markdown summary table from saved EDNs via `(runner/generate-summary! "development/bench/generalization-results")`

This module is optional convenience — external developers copying a single task file ignore it entirely. Internal benchmark workflows use it for "run everything + summarize."

## Acceptance criteria

- [ ] New file `development/bench/all.clj` exists with ns `all`
- [ ] Requires all 5 task namespaces (`document-analysis`, `risk-analysis`, `contract-comparison`, `contract-comparison-validated`, `legal-issue-detection`) and `runner`
- [ ] Exposes `(all/run-all!)` and `(all/summary!)` functions
- [ ] `all/run-all!` calls `(runner/run-all! [doc/task risk/task cc/task ccv/task lid/task])`
- [ ] `all/summary!` generates the same markdown summary as the existing `rlm-gen-bench/generate-summary-table!`
- [ ] Benchmark test verification before commit:
  - `(require '[all :as bench])` succeeds without errors (transitively loads all 5 task files)
  - **Skip full run-all in this slice** (15+ minute runtime); instead verify the require + 2 individual task runs via the same aggregator path. Full run-all is verified in B04.
- [ ] Framework tests still pass

## Blocked by

- B02 (Extract task files) — `all.clj` requires all 5 task namespaces to exist

## User stories covered

6, 7, 9
