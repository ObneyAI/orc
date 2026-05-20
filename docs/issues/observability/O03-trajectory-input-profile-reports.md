# O03: Tree-execution bookend + input profile + report visibility

## Parent

PRD: `docs/prd/phase2-observability-layer.md` (local, on feature branch)

## What to build

Build on the per-node completion events from O02 to make every RLM tree execution leave a full audit trail.

1. **Input profile on each node-completed event**: extend the `:rlm/tree-node-completed` schema with an `:input-profile` field. For each key in the node's `:reads`, capture at minimum length (byte-count or char-count) and a small set of content-density indicators (e.g. fraction of tokens that look like structured markers, word count, etc.). Goal: future judges (deferred to Category C) can correlate quality outcomes to input shape.

2. **Tree-execution bookend event**: add `:rlm/tree-execution-completed` event schema with `{:tick-id, :trajectory, :task-fingerprint (placeholder), :total-usage, :timestamp}`. Trajectory is a vector of `{:node-id, :timestamp, :event-type}` entries spanning the whole Phase 2 run. Emit this event when Phase 2 finishes. The `:task-fingerprint` field stays as a placeholder (nil or empty string) — actual fingerprint computation is deferred to Category C4 / issue 012.

3. **Bench report visibility**: each of `development/bench/tasks/01-document-analysis.md` through `05-legal-issue-detection.md` gains a "Token Breakdown" section showing the per-node distribution from a canonical run (using `:by-node` from the O02 EDN). If a headline observation emerges (e.g. "synthesis dominates token cost on G06"), surface it in `RESULTS.md`.

All event emissions flow through proper Grain commands.

## Acceptance criteria

- [ ] `:rlm/tree-node-completed` event schema extended with `:input-profile` field (Malli schema)
- [ ] Per-node event emission captures input-profile from the node's `:reads` at completion time
- [ ] `:rlm/tree-execution-completed` event schema added with `:trajectory`, `:task-fingerprint` (placeholder), `:total-usage`, `:timestamp`
- [ ] Command emits the bookend event when an RLM Phase 2 tree-execution finishes
- [ ] Bench task reports (`tasks/01-..05-.md`) each have a new "Token Breakdown" section showing per-node distribution
- [ ] `RESULTS.md` updated if a headline observation emerges from the breakdowns
- [ ] Framework tests still pass: 24 tests, 95 assertions, 0 failures
- [ ] G04 benchmark passes; bookend event observable in event store; `:input-profile` populated on per-node events
- [ ] G07 benchmark passes; trajectory is non-empty and reflects the actual tree execution order
- [ ] No re-baselining of existing benchmark numbers required (per PRD: existing aggregates are valid signal)
- [ ] Single commit on feature branch

## Blocked by

- O02 (per-node usage events) — the bookend event aggregates per-node data and the input-profile lives on the per-node event schema added in O02

## User stories covered

8, 9, 10, 11, 16
