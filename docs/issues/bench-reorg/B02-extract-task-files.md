# B02: Extract 5 per-task definition files

## Parent

PRD: `docs/prd/bench-reorganization.md` (local)

## What to build

Extract each of the 5 task definitions from `rlm_gen_bench.clj`'s `:tasks` map into its own file, each containing only a `def task` map (no runner code, no system setup). Each task file becomes a focused, readable example.

The 5 files (with their namespaces):

- `document_analysis.clj` → `(ns document-analysis)`
- `risk_analysis.clj` → `(ns risk-analysis)`
- `contract_comparison.clj` → `(ns contract-comparison)`
- `contract_comparison_validated.clj` → `(ns contract-comparison-validated)`
- `legal_issue_detection.clj` → `(ns legal-issue-detection)`

The `rlm_gen_bench.clj` wrapper's `:tasks` map is reorganized to reference the task vars from these new files: `{:document-analysis document-analysis/task, :risk-analysis risk-analysis/task, ...}`. The public API `(run-task! :risk-analysis)` continues to work because the lookup path is unchanged.

Each task file's ns docstring includes a copy-pasteable REPL invocation as a teaching aid.

## Acceptance criteria

- [ ] 5 new files exist with the namespaces listed above; each contains a `def task` map matching the existing task definition exactly (instruction, documents, writes, evaluation-criteria, etc.)
- [ ] Each task file has a docstring with a copy-pasteable REPL example like:
  ```clojure
  (require '[risk-analysis :as t])
  (require '[runner])
  (runner/start!)
  (runner/run! t/task)
  (runner/stop!)
  ```
- [ ] `rlm_gen_bench.clj`'s `:tasks` map is rebuilt by referencing the new task vars; public API unchanged
- [ ] Benchmark test verification before commit (all 5 tasks via the wrapper API):
  - `(rlm-gen-bench/run-task! :legal-issue-detection)` → `:success`, ~9s, ~5-6K tokens
  - `(rlm-gen-bench/run-task! :risk-analysis)` → `:success`, ~60-85s, ~170K tokens, emit-tree! used
  - At minimum the 2 above; ideally all 5 spot-checked for shape
- [ ] Framework tests still pass
- [ ] Each task file is independently loadable: `(require '[risk-analysis])` succeeds without errors

## Blocked by

- B01 (Extract runner.clj) — the task files' docstrings reference `runner` namespace and `(runner/run! task)` API

## User stories covered

1, 2, 4, 8, 15
