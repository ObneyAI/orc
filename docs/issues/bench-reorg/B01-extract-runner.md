# B01: Extract `runner.clj` (compatibility-preserving)

## Parent

PRD: `docs/prd/bench-reorganization.md` (local)

## What to build

Extract the shared benchmark infrastructure (system bringup, run!, run-all!, save-result!, generate-summary!) from `rlm_gen_bench.clj` into a new `runner.clj` module. Keep `rlm_gen_bench.clj` working as a thin wrapper that delegates to the new runner — the existing public API (`start!`, `stop!`, `run-task!`, `run-all-tasks!`, `generate-summary-table!`) continues to work exactly as before.

This is a non-breaking refactor: same external behavior, same outputs, same EDN result files. The split is internal.

## Acceptance criteria

- [ ] New file `development/bench/runner.clj` exists with ns `runner` containing: `start!`, `stop!`, `run!`, `run-all!`, `generate-summary!`
- [ ] `runner/run!` accepts a task map directly (not a keyword lookup) — generic, no task knowledge
- [ ] `rlm_gen_bench.clj` becomes a thin wrapper requiring `runner` and exposing the same public API as before
- [ ] Benchmark test verification before commit:
  - Run G04 (`legal-issue-detection`) end-to-end → `:success`, ~9s, ~5-6K tokens, real output (Schedule E references, specific durations)
  - Run G07 (`risk-analysis`) end-to-end → `:success`, ~60-85s, ~170K tokens, emit-tree! triggered, real source-verified output
- [ ] Framework tests still pass (`rlm-tree-executor-test`, `rlm-dsl-test` — 24 tests, 95 assertions)
- [ ] Generated tree captured in result EDNs (no regression in shape)
- [ ] No new untracked files except expected EDN result files in `generalization-results/`

## Blocked by

None — can start immediately.
