# PR05 — Comparison runner under `development/bench/predict-rlm-comparison/`

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

A new benchmark runner mirroring `development/bench/runner.clj` but enriched with the capture surface needed for hand-authored comparison reports. Lives under `development/bench/predict-rlm-comparison/runner.clj`. The existing 5-benchmark runner is untouched.

Differences from the existing runner:

1. **Preserves `:iterations`** in the result EDN. Currently dropped at `runner.clj:272-285` (the runner's `cond->` record-builder only includes a small subset of the executor's returned map). This is the Phase 1 researcher's per-iteration code+stdout+vars-created log.

2. **Preserves `:by-node`** in the result EDN. Per-leaf-node usage breakdown produced by the Phase 2 tree executor.

3. **Adds `:node-trace`**. After the run completes (before `(runner/stop!)`), query the event store for all `:sheet/node-execution-completed` events on the run's sheet, sort by event timestamp, and persist as a vector of `{:node-id :status :inputs :writes :usage :duration-ms}` entries. The event schema already carries `:inputs` and `:writes` per leaf (verified at `interface/schemas.clj:874-888`).

4. **Mulog JSONL trace file**. Attach `com.brunobonacci.mulog/start-publisher!` writing JSON-lines to `<results-dir>/<task>_<timestamp>.trace.jsonl` for the duration of each run; stop the publisher when the run ends. Captures raw prompt/response data from the existing `u/trace ::rlm-llm-primitive` and similar tracing blocks already present in the codebase.

5. **Single-task-lock**. An atom on the runner ns refuses to start a new task if a prior one is in flight. Concurrent runs against the same in-memory sheet would corrupt state.

Output filename convention is `<task-slug>_<timestamp>.edn` for the result and `<task-slug>_<timestamp>.trace.jsonl` for the trace, both under a new `development/bench/predict-rlm-comparison/results/` directory.

## Acceptance criteria

- [ ] New file `development/bench/predict-rlm-comparison/runner.clj` exists; mirrors the public API of the existing runner (`start!`, `run!`, `stop!`, `generate-summary!`)
- [ ] Results directory `development/bench/predict-rlm-comparison/results/` is created on first run
- [ ] Running the existing `contract_comparison_validated` task definition through the new runner produces an EDN containing **all four** new fields: `:iterations`, `:by-node`, `:node-trace`, plus all the existing summary fields
- [ ] `:iterations` contains at least one entry with `{:code ... :result ... :stdout ... :vars-created ...}` shape
- [ ] `:by-node` is a non-empty map keyed by node path (the tree executor already aggregates this post-P01)
- [ ] `:node-trace` is a non-empty vector; each entry has `:node-id`, `:inputs`, `:writes`, `:status`, `:usage`
- [ ] A `.trace.jsonl` file is produced alongside the `.edn` with the same `<task>_<timestamp>` slug
- [ ] Each line in the JSONL is parseable JSON
- [ ] Calling `(runner/run! ...)` twice in immediate succession (before the first completes) fails the second call with `{:status :failure :error "task in flight"}` (or similar)
- [ ] After `(runner/stop!)`, the lock clears
- [ ] The existing 5-benchmark runner (`development/bench/runner.clj`) is byte-for-byte unchanged
- [ ] No regression: run the existing `contract_comparison_validated` through the new runner; verify success status, plausible token counts (close to historical baseline), same family of emitted tree

## Blocked by

PR01
