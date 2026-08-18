# 005 — Sheet execution checkpoint amplification dominates tick latency

## Summary

Behavior-tree execution pays ~600ms of fixed cost per node transition, and
almost none of it is computation. Seven todo processors subscribe to
`:sheet/node-execution-started` and four to `:sheet/node-execution-completed`;
for every node event, exactly one does work while the rest no-op on a type
check — but **every one of them durably advances its own checkpoint cursor,
each in its own fsync'd SQLite commit**. On a ~40-node judgment tree this
turns 7 seconds of LLM time into a 31-second tick.

## Measurements (Sormo production runtime, 2026-08-18)

All numbers from ORC trace projections plus direct event-store inspection of
one representative judgment tick (`5273172f-7b47-48b6-85a3-05ecd6cb625f`):

- Tick total: **30,729ms**; LLM node time: **7,390ms**.
- 39 node executions; median gap between one node completing and the next
  starting: **616ms** (max 1,682ms); sum of inter-node gaps: **14.7s**.
- Event-store writes during the 31s tick: **530 transactions, of which 505
  were `:grain/todo-processor-checkpoint`** and ~110 were actual sheet/domain
  events. 23.3s non-LLM time ÷ 530 commits ≈ **44ms per commit** (macOS
  fsync).
- Lifetime store composition: 229k of 548k events (**42%**) are checkpoints.

Ruled out by measurement:

- **Read-model projection**: warm tag-scoped projections are 4ms. The L1/L2
  cache (scope-keyed, watermark deltas) works as designed. Cold folds
  (0.7–1.8s over the sheet tag's 55,670 events) occur only on genuine
  cache-key misses, not in the steady-state hop path.
- **Poller latency**: 25ms interval; a few cycles per hop is noise.

## Mechanism

Grain's todo-processor-v2 already batches checkpoints for pure handlers (one
`:batch-range` checkpoint per poll batch). Two ORC-side characteristics defeat
this:

1. **Sequential causal chains produce batch-of-1.** Each hop's event is
   produced by processing the previous one, so the poll frontier is always one
   event deep; the batch optimization never engages.
2. **Topic fan-out multiplies cursors.** Per `node-execution-started` event,
   seven processors run (`execute-leaf-node`, `execute-condition-node`,
   `execute-composite-node`, `execute-parallel-node`, `execute-map-each-node`,
   `execute-repl-researcher-node`, `execute-delegate-node`); six no-op on the
   node-type guard but still commit a cursor advance. Per
   `node-execution-completed`: four more (`handle-child-completion`,
   `handle-map-each-child-completion`, `update-blackboard`,
   `complete-tree-tick`).

Net: ~11 fsync'd cursor commits per hop × ~44ms ≈ the observed 616ms.

## Proposed fixes, in ascending scope

### 1. Consolidate the seven `node-execution-started` executors (lowest risk)

All seven executor fns already guard internally (`(when (= :leaf (:type node))
…)` etc., returning nil on non-match; `execute-condition-node` ends in
`:else nil`), and node types are mutually exclusive, so a single dispatcher
processor delegating via an `or` chain over the existing fns is semantically
identical and cuts started-topic cursors 7 → 1 (~11 → ~5 commits per hop,
~600ms → ~280ms).

**Migration trap:** Grain checkpoints are keyed by processor name, and a
fresh name with no checkpoint catches up **from the beginning of the stream**
(`get-last-processed-id` → nil → no `:after` bound), which would replay all
15,635 historical `node-execution-started` events. Mitigation: register the
dispatcher under one of the existing names (e.g. `:sheet/execute-leaf-node`)
so it inherits a cursor already at head; the six retired names leave inert
checkpoints. Migrate while no tick is in flight.

**Deliberately excluded:** the `node-execution-completed` pair
(`handle-child-completion` / `handle-map-each-child-completion`) are guarded
by parent-shape logic, not node type — mutual exclusivity is not proven, and
an `or` chain would short-circuit a case where both should act. Consolidating
them needs its own analysis. `update-blackboard` and `complete-tree-tick` do
real per-event work and currently run concurrently; merging them serializes
ordering and should also be considered separately.

### 2. Checkpoint at async boundaries, not per node (design fix)

Deterministic nodes (conditions, code, composite bookkeeping) are idempotent
and cost microseconds to re-run; persisting per transition buys negligible
recovery value at full commit cost. Executing consecutive deterministic
segments in memory and checkpointing only at async boundaries (LLM calls,
external effects, tick edges) would reduce a ~40-hop tick to ~5–6 durable
steps while keeping crash-recovery where it matters.

### 3. Group-commit same-event cursors (Grain-side, general)

All processors handling the same event could advance their cursors in one
SQLite transaction. Helps any fan-out workload, not just sheets. Related
option: an `:initial-position :head` `defprocessor` option would remove the
fresh-name replay trap for future renames/consolidations.

## Impact

Fixed per-judgment overhead is ~23s today and grows with nothing — it is paid
identically by a trivial conversational reply and a complex deliberation.
Fix 1 alone roughly halves it; fix 2 reduces it by ~85%.
