# B04: Migrate docs, delete old files, full smoke-test

## Parent

PRD: `docs/prd/bench-reorganization.md` (local)

## What to build

Final cutover step. Three coordinated changes:

1. **Documentation migration**: Update every reference to the old `rlm-gen-bench` namespace / `rlm_gen_bench.clj` filename to point to the new structure (per-task files + `runner` + `all` aggregator).
   - `CLAUDE.md` (the "RLM Generalization Benchmark" section)
   - `development/bench/README.md` (invocation examples, config location)
   - `development/bench/RESULTS.md` (the run command example at the bottom)
   - `development/bench/tasks/01-*.md` through `tasks/05-*.md` (each report's task-key/source-file reference)

2. **Delete old files**:
   - `development/bench/rlm_gen_bench.clj` (the wrapper from B01-B03 is no longer needed)
   - `development/bench/rlm_benchmark.clj` (anti-pattern with hardcoded tree template — explicit user decision)
   - `development/bench/runs/` directory and all 6 baseline EDN files inside (orphaned without the runner)

3. **Full smoke-test before commit**:
   - Run G04 (`legal-issue-detection`) end-to-end via the new namespace → `:success`, ~9s, ~5-6K tokens, real verified output
   - Run G07 (`risk-analysis`) end-to-end → `:success`, ~60-85s, ~170K tokens, emit-tree! triggered, real source-verified output
   - **Optional but recommended**: `(all/run-all!)` end-to-end (full 5-task run, ~6 min total) and `(all/summary!)` produces a valid markdown table
   - Framework tests pass: `rlm-tree-executor-test`, `rlm-dsl-test` (24 tests, 95 assertions, 0 failures)

After this slice, `git grep "rlm-gen-bench\|rlm_gen_bench\|rlm-benchmark\|rlm_benchmark"` returns zero matches.

## Acceptance criteria

- [ ] All documentation references updated to new namespaces / file paths
- [ ] `rlm_gen_bench.clj`, `rlm_benchmark.clj`, and `runs/` directory removed
- [ ] `git grep "rlm-gen-bench\|rlm_gen_bench\|rlm-benchmark\|rlm_benchmark"` returns zero results
- [ ] G04 smoke-test passes with expected metrics and verified output (no hallucinations)
- [ ] G07 smoke-test passes with expected metrics and verified output (no hallucinations, emit-tree! triggered)
- [ ] Framework tests pass (24 tests, 95 assertions, 0 failures)
- [ ] `deps.edn` still has `"development/bench"` on `:dev :extra-paths`
- [ ] Single clean commit on main with a descriptive message
- [ ] No tracked references to the deleted files remain (verified via `git status` clean for tracked items)

## Blocked by

- B03 (Add all.clj aggregator) — the docs in this slice reference `all` aggregator examples

## User stories covered

10, 11, 14, 16
