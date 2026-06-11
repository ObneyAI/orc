# O02: Per-node usage events + EDN visibility

## Parent

PRD: `docs/prd/phase2-observability-layer.md` (local, on feature branch)

## What to build

Make every node completion in the event store carry its token usage, and surface a `:by-node` breakdown in saved EDN result files.

Two parallel emission paths:

1. **Universal token tracking**: extend the existing generic `:sheet/node-execution-completed` event with an optional `:usage` field. Update the existing complete-node-execution command to accept it. Update the leaf-execution processor to pass usage from the LLM call result into the command. This benefits ALL trees (RLM or not).

2. **RLM-specific completion signal**: add a new `:rlm/tree-node-completed` event schema carrying `{node-id, node-path, usage, timestamp}`. Emit this event in addition to the generic one when a node completes inside an RLM Phase 2 tree execution. Path is derived at emission time from the existing `::map-each-parent` / `::map-each-index` context (full ancestry resolution can come later via a read model). Schema explicitly leaves room for future fields (`:config`, `:input-profile`, `:scores`, `:feedback`) that O03 and downstream judge work will fill.

3. **EDN visibility**: when `runner/run!` finishes a task, aggregate the per-node usage from the new events into a `:by-node` map and include it in the saved result EDN under `:usage`. Existing aggregate `:total-tokens` etc. remain unchanged for backward compat.

Use proper Grain patterns throughout: commands emit events; processors react. No direct event-store writes.

## Acceptance criteria

- [ ] `:sheet/node-execution-completed` event schema in `interface/schemas.clj` has an optional `:usage` field (Malli schema)
- [ ] `:sheet/complete-node-execution` command accepts `:usage` and the handler emits it in the event body
- [ ] `:rlm/tree-node-completed` event schema added with `{:node-id, :node-path, :usage, :timestamp}` and explicit room for later fields
- [ ] A new command emits the `:rlm/tree-node-completed` event when an RLM Phase 2 node completes
- [ ] `execute-leaf-node` processor extracts usage from the LLM result and includes it in the complete-node-execution command
- [ ] Both events fire for RLM Phase 2 nodes (the generic one for universal observability, plus the RLM-specific one for the learning loop)
- [ ] `runner/run!` aggregates `:by-node` usage from the new events into the saved result EDN under `:usage :by-node`
- [ ] `:by-node` totals approximately equal the aggregate `:total-tokens` (within small rounding)
- [ ] Framework tests still pass: 24 tests, 95 assertions, 0 failures
- [ ] G04 benchmark passes end-to-end via NEW API with `:by-node` populated
- [ ] G07 benchmark passes end-to-end with `:by-node` populated, `:rlm/tree-node-completed` events observable in event store
- [ ] Single commit on feature branch

## Blocked by

- O01 (test alignment) — establishes green baseline before adding new event work

## User stories covered

3, 4, 5, 6, 7, 12, 13, 14, 15
