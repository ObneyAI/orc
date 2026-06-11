# PRD — Bench Reorganization: Per-Task Standalone Examples

**Status:** Draft (local, not committed)
**Date:** 2026-05-19

## Problem Statement

As an external developer who has pulled in `obneyai/orc` as a git dep, I want to understand how to use the ORC RLM workflow (`emit-tree!`, `:map-each`, behavior tree generation) by reading concrete, runnable examples. Today, the only example is `development/bench/rlm_gen_bench.clj`, which is a 570-line file containing five tasks intermingled with system setup, runners, and an aggregator. There is no way to read a single task end-to-end without scanning the whole file.

As an internal developer / researcher who wants to verify ORC RLM behavior, I want each task to be a separately repeatable benchmark — runnable, comparable, and easy to modify in isolation — without having to read or touch unrelated task definitions.

As a maintainer, I want to remove the old `rlm_benchmark.clj` from the main branch because it contains the anti-pattern we explicitly avoided in the generalization suite: a hardcoded behavior tree embedded in the task instruction. Keeping it on main sends mixed signals about "the right way to use the RLM."

## Solution

Reorganize `development/bench/` so that:

1. Each of the 5 generalization tasks lives in its own file at the top level of `development/bench/`
2. Shared infrastructure (event store / pubsub / processor setup, generic run!, save-result!, summary generation) lives in a single `runner.clj` that all task files share
3. A small `all.clj` aggregator provides the existing convenience of "run all tasks + generate summary"
4. The old `rlm_benchmark.clj` and its associated `runs/` baseline EDNs are deleted from main entirely (preserved in feature branch git history for recovery if ever needed)
5. All documentation references to old paths/namespaces are updated atomically with the file split

The resulting structure makes each task a clean, runnable, self-explanatory example of the RLM workflow. External developers can copy a single task file as a template; internal developers can iterate on a single task without touching the other four.

## User Stories

1. As an external developer, I want to open `development/bench/risk_analysis.clj` and immediately see the task instruction, expected outputs, and quality requirements, so that I can understand the task without reading the runner code.
2. As an external developer, I want each task file to be ~80 lines of focused content (not 150 lines of duplicated boilerplate), so that I can quickly read it end-to-end.
3. As an external developer, I want a one-line REPL command to run any single task, so that I can verify the example works before adapting it.
4. As an external developer, I want the task file's namespace declaration to include a docstring with a copy-pasteable REPL invocation, so that I can run it without consulting external documentation.
5. As an internal developer, I want to run a single task in isolation for benchmarking, so that I can iterate on one task without re-running the others.
6. As an internal developer, I want `(all/run-all!)` to still work as a convenience for full suite runs, so that I don't lose existing workflow.
7. As an internal developer, I want `(all/summary!)` to still produce a markdown summary table, so that I can compare runs at a glance.
8. As a contributor, I want to add a 6th task by creating a single new file (and adding it to `all.clj`), so that extending the suite is mechanical and low-friction.
9. As a reader of the README, I want clear examples of "run one task" vs "run all tasks," so that I pick the right pattern for my use case.
10. As a reader of the headline `RESULTS.md`, I want the per-task code examples to point to actual file names (not "see the tasks map in rlm_gen_bench.clj"), so that I can navigate from results to source.
11. As a security-minded reader, I want zero references to the deleted `rlm_benchmark.clj` to remain in the codebase or docs, so that there's no orphaned guidance pointing at the anti-pattern.
12. As a maintainer doing the migration, I want to verify nothing breaks by running G04 (the fastest task) end-to-end after the change, so that I have evidence the split worked.
13. As a maintainer doing the migration, I want to verify all framework tests still pass, so that the file move doesn't break any test discovery.
14. As a maintainer doing the migration, I want to grep the codebase for the old namespace `rlm-gen-bench` (and underscore variant `rlm_gen_bench`) and update every reference, so that no stale documentation links remain.
15. As a Clojure developer pulling this into another project, I want each task file to use standard Clojure naming conventions (hyphens in namespaces, underscores in filenames), so that nothing surprises me.
16. As a developer reading task reports, I want the existing per-task markdown files in `tasks/` (e.g., `tasks/02-risk-analysis.md`) to remain valid and updated to reference the new source file names, so that report-to-code navigation is intact.

## Implementation Decisions

### Modules to be built / modified

**`runner` module (new)** — Deep module containing all shared infrastructure for running an RLM benchmark task. Public interface is small (3 functions: `start!`, `stop!`, `run!`) but the implementation handles the entire grain-v2 system bringup (event store, pubsub, kv store, processors, command/query registries). This is the cleanest deep-module opportunity in the reorganization.

- `(runner/start!)` — initializes the system state (returns nothing useful, mutates internal atom). Same lifecycle semantics as the current `rlm-gen-bench/start!`.
- `(runner/run! task-map)` — given a task definition map, runs the task end-to-end and saves the result EDN. Takes the same task map shape as the current `tasks` map values.
- `(runner/stop!)` — tears down system state, cleans up kv-store cache directory.
- `(runner/run-all! task-maps)` — internal helper for the aggregator. Sequentially runs a list of task maps. Generic — no knowledge of specific tasks.
- `(runner/generate-summary! results-dir)` — reads saved EDN result files and produces a markdown summary table at `<results-dir>/SUMMARY.md`. Generic.

**5 task modules (new, one per task)** — Each is a thin file containing:
- An ns declaration with a docstring containing a copy-pasteable REPL invocation
- A `def task` map with the task definition (`:name`, `:pattern`, `:documents`, `:description`, `:instruction`, `:writes`, `:evaluation-criteria`)
- Nothing else. No runner code, no system setup.

Modules:
- `document-analysis` → file `document_analysis.clj`
- `risk-analysis` → file `risk_analysis.clj`
- `contract-comparison` → file `contract_comparison.clj`
- `contract-comparison-validated` → file `contract_comparison_validated.clj`
- `legal-issue-detection` → file `legal_issue_detection.clj`

**`all` aggregator module (new)** — Convenience aggregator that requires all 5 task modules and exposes:
- `(all/run-all!)` — calls `(runner/run-all! [document-analysis/task risk-analysis/task ...])`
- `(all/summary!)` — calls `(runner/generate-summary! "development/bench/generalization-results")`

External developers copying a single task file don't need `all.clj`. It's purely an internal convenience.

**Deleted files:**
- `development/bench/rlm_gen_bench.clj` (split into the 7 new files above)
- `development/bench/rlm_benchmark.clj` (anti-pattern; not preserved on main)
- `development/bench/runs/` directory and all 6 baseline EDNs inside (orphaned without the runner)

### Architectural decisions

1. **Flat namespaces, no nesting.** Each task file uses a top-level namespace name (`risk-analysis` not `orc-bench.tasks.risk-analysis`). Accepted trade-off: short names risk collision if an external dev copies into a project that already uses the same name. Mitigation: developers copying for their own use are expected to rename.

2. **Shared infrastructure, not full self-containment.** Each task file is a focused ~80-line definition; system setup boilerplate (event store, pubsub, processors) lives once in `runner.clj`. Rejected: full self-containment (150-line boilerplate duplicated 5× = noise that obscures the teaching value).

3. **Audience priority: external developers first.** Structure decisions optimize for "external dev learning the RLM workflow" over "internal benchmark convenience." Internal convenience preserved via the `all` aggregator.

4. **Naming convention: standard Clojure.** Files use underscores (`risk_analysis.clj`), namespaces use hyphens (`(ns risk-analysis)`). Reports in `tasks/` continue to use hyphenated markdown filenames (irrelevant to Clojure namespacing).

5. **`deps.edn` already correctly includes `development/bench` on `:dev :extra-paths`** — no further classpath changes needed.

6. **Task name strings unchanged.** The result EDN files in `generalization-results/` continue to work because task names (`"risk-analysis"`, etc.) are string-equal to what they were before. No EDN migration required.

7. **Old EDNs in `runs/` are deleted on main, preserved in feature branch.** Recovery path: `git checkout feature/core-orc-upgrades -- development/bench/runs/` if ever needed.

### Documentation update contract

Every reference to `rlm-gen-bench` or `rlm_gen_bench` is updated atomically with the file split, including:

- `CLAUDE.md` — the "RLM Generalization Benchmark" section, including the `clj -M:dev` REPL example
- `development/bench/README.md` — invocation examples, config location reference, file listing
- `development/bench/RESULTS.md` — any code examples in the headline story
- `development/bench/tasks/01-document-analysis.md` through `05-legal-issue-detection.md` — the "task key" reference at the top of each report becomes a "source file" reference (`development/bench/risk_analysis.clj`)
- `development/bench/appendix/tree-comparison.md` — any source references

Verification: `git grep "rlm-gen-bench\|rlm_gen_bench\|rlm-benchmark\|rlm_benchmark"` returns zero matches after the change.

### Execution plan (sequence-sensitive)

1. **Survey phase** — `git grep` for all references to the old namespaces. Enumerate every file that needs updating.
2. **Split phase** — Create the 7 new files (`runner.clj`, `all.clj`, 5 task files) by extracting from `rlm_gen_bench.clj`.
3. **Delete phase** — `git rm rlm_gen_bench.clj rlm_benchmark.clj`; `git rm -r runs/`.
4. **Doc update phase** — Update all documentation references in one pass.
5. **Smoke-test phase** — Run G04 (`legal-issue-detection`, fastest task ~9s) from the new namespace to verify the split works end-to-end. Run framework test suite (`rlm-tree-executor-test`, `rlm-dsl-test`).
6. **Commit + push phase** — Single commit with a clear message explaining the reorganization. Push to `main`.

## Testing Decisions

A good test for this reorganization is **integration-shaped**: verify the split modules work together via the runner. Unit-testing individual task definitions is low-value — they are pure data maps with no behavior.

**What to test:**

1. **G04 end-to-end smoke test** — After the split, running `(require '[legal-issue-detection :as t])` + `(runner/start!)` + `(runner/run! t/task)` + `(runner/stop!)` must succeed with the same output shape as before (status `:success`, `:outputs` map with `:issues`, `:ambiguities`, `:missing`, `:recommendations`). G04 is chosen because it's the fastest (~9s) and exercises the no-tree code path (direct answer rather than `emit-tree!`).

2. **`all` aggregator load test** — `(require '[all :as bench])` must succeed without errors, exercising all 5 task requires transitively.

3. **Framework tests unchanged** — `rlm-tree-executor-test` (24 tests, 95 assertions) must continue to pass. This regression-tests that none of the file moves broke the underlying RLM execution path.

**What NOT to test:**

- Per-task definition shape (the `task` map structure). These are data; any malformed task would fail at runner invocation, caught by the smoke test.
- Individual task `:instruction` text content. Validated against source documents already via the existing per-task reports (no automated assertion).
- `runner.clj` internals (system setup, process command, etc.). These are tested indirectly via the smoke test; their internal interfaces are well-established polylith patterns.

**Prior art:**

- The existing `rlm-tree-executor-test/map-each-max-concurrency-runs-iterations-in-parallel` is the model for integration-shaped tests in this area: it asserts observable behavior through public interfaces.
- The runner's `start!`/`stop!`/`run!` lifecycle is structurally identical to the current `rlm-gen-bench` API; the integration shape is unchanged.

## Out of Scope

- Adding new generalization tasks. The 5 existing tasks are kept as-is.
- Changing task definitions (instructions, outputs, evaluation criteria) beyond what's needed for the split.
- Modifying any framework code (`components/orc-service/*`). This is purely a bench-folder reorganization.
- Updating `feature/core-orc-upgrades` branch. The split happens directly on `main`. The feature branch retains the old layout as historical record.
- Adding test coverage beyond the smoke test described above. Unit-testing pure data maps adds maintenance cost without catching real bugs.
- Renaming any documents in `documents/` or result EDNs in `generalization-results/`.
- Adding an `archive/` folder for the old `rlm_benchmark.clj` — explicitly rejected per Q5 decision; preserved in git history instead.

## Further Notes

- The PRD captures decisions from a /grill-me session covering Q1 (audience), Q2 (self-containment level), Q3 (namespace layout), Q4 (aggregator placement), Q5 (anti-pattern removal), Q6 (naming), Q7 (execution sequencing).
- This PRD is local-only and intentionally not committed to git. It exists at `docs/prd/bench-reorganization.md` as planning context; the implementation will land directly on `main` without this PRD being shipped.
- After the reorganization, the existing per-task reports in `tasks/01-..05-.md` provide the human-readable narrative; the new per-task source files provide the runnable code; `runner.clj` provides the shared lifecycle. Each layer has a single clear purpose.
- If the smoke test reveals broken behavior, the rollback is trivial: `git revert` the reorganization commit. The previous structure is intact in commit `fe9eaf7`.
