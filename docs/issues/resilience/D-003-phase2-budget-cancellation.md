# D-003: Phase 2 budget-aware timeout with cancellation

## Parent

PRD: `docs/prd/category-d-resilience.md` (local, on feature branch)

## What to build

The two-phase RLM execution currently hardcodes a 900-second ceiling on Phase 2 regardless of how long Phase 1 took or what the caller's actual deadline is. A caller who sets a 120-second deadline can end up waiting 1020 seconds. The hardcode also leaks compute — when the parent gives up, the child tick keeps executing in the background.

This slice makes Phase 2 respect a budget end-to-end, with clean cancellation when the budget is exhausted.

**Budget lookup (in order):**
1. `(:timeout-ms node)` — repl-researcher node-level config (primary)
2. `(get-in tick-ctx [:options :timeout-ms])` — parent tick deadline (fallback)
3. 900,000 ms hardcode (preserves current behavior when nothing specified)

After Phase 1 completes, the engine computes `remaining = original-budget - phase1-elapsed` and passes that to `tree-executor/execute-tree`.

**Three edge cases handled:**

- **Budget already exhausted in Phase 1** (`remaining <= 0`): emit `{:status :failure :error "Budget exhausted in Phase 1" :phase1-elapsed-ms N}` without invoking Phase 2 at all. No wasted child tick.

- **Phase 2 exceeds remaining budget**: dispatch the existing `:sheet cancel-tick` command on the child tick, wait ~500 ms for in-flight nodes to drain their writes into the event store, then return `{:status :timeout :error "..." :phase1-elapsed-ms N :phase2-elapsed-ms M}` with no `:outputs`. The bookend `:sheet/rlm-tree-execution-completed` event from O03 fires during the drain and reflects whatever partial trajectory was reached — judges and observability still see what completed.

- **Happy path (Phase 2 finishes within budget)**: existing behavior, but the response now ALSO carries `:phase1-elapsed-ms` + `:phase2-elapsed-ms`. Cheap to add; useful for observability.

No new event types. No new commands. The `:sheet cancel-tick` command and `:sheet/tick-cancelled` event already exist. The O03 bookend event already fires from `execute-tree`'s completion path; cancellation simply triggers the same path earlier.

Pure deep module extracted: `resolve-phase2-budget` takes the repl-researcher node, the tick-context, and `phase1-elapsed-ms`, and returns `{:total-budget-ms N :remaining-ms N :source [:node | :tick | :hardcoded] :exhausted? boolean}`. Encapsulates the entire lookup chain; unit-testable in isolation; reusable later if Phase 1 ever grows its own budget check.

This slice does NOT return partial outputs in the timeout response (Q9 Option C — explicitly out of scope). Callers that want partial state read events directly. The `:status :timeout` response intentionally has no `:outputs` field.

## Acceptance criteria

### Schema

- [ ] `:timeout-ms :int` is a valid optional field on the repl-researcher node config schema
- [ ] RLM result schema documents `:phase1-elapsed-ms` and `:phase2-elapsed-ms` as fields that may appear on success, failure, or timeout responses
- [ ] RLM result schema documents `:status :timeout` as a valid value with no `:outputs`

### Deep module — `resolve-phase2-budget`

- [ ] Pure function, takes node + tick-ctx + phase1-elapsed-ms
- [ ] Returns `{:total-budget-ms :remaining-ms :source :exhausted?}`
- [ ] Node-level `:timeout-ms` takes precedence → returns `{:source :node ...}`
- [ ] Tick `:options :timeout-ms` used when node has none → `{:source :tick ...}`
- [ ] Neither set → `{:source :hardcoded :total-budget-ms 900000 ...}`
- [ ] `phase1-elapsed-ms >= total-budget-ms` → `{:exhausted? true :remaining-ms 0}`
- [ ] Unit tests cover every branch of the lookup chain plus the exhausted case

### RLM handler integration

- [ ] `execute-repl-researcher-rlm` captures `phase1-elapsed-ms` right before invoking Phase 2
- [ ] Calls `resolve-phase2-budget` to compute remaining
- [ ] If `:exhausted?`: returns `{:status :failure :error "Budget exhausted in Phase 1" :phase1-elapsed-ms N}` without invoking `tree-executor/execute-tree`
- [ ] Otherwise passes `:remaining-ms` as `:timeout-ms` to `tree-executor/execute-tree`
- [ ] On `:status :timeout` from `execute-tree`: dispatches `:sheet cancel-tick` on the child tick-id
- [ ] After cancellation, blocks ~500 ms drain interval so in-flight nodes settle their writes
- [ ] Returns `{:status :timeout :error "..." :phase1-elapsed-ms N :phase2-elapsed-ms M}` with no `:outputs`
- [ ] On `:status :success` and `:status :failure` from `execute-tree`: existing behavior PLUS includes `:phase1-elapsed-ms` + `:phase2-elapsed-ms` in the response

### Tests (mock executor — for dev iteration)

- [ ] Node-level `:timeout-ms` takes precedence over tick fallback (verified via `resolve-phase2-budget` unit + integration)
- [ ] Tick `:options :timeout-ms` used when node has none
- [ ] 900s hardcode used when neither set
- [ ] `remaining <= 0` skips Phase 2 entirely; response is `{:status :failure :error "Budget exhausted in Phase 1"}`
- [ ] Phase 2 timeout dispatches `:sheet cancel-tick` (verify event-store has `:sheet/tick-cancelled` tagged with the child tick-id)
- [ ] 500ms drain allows bookend `:sheet/rlm-tree-execution-completed` event to fire with partial trajectory
- [ ] Timeout response shape: `{:status :timeout :error :phase1-elapsed-ms :phase2-elapsed-ms}` with no `:outputs` field
- [ ] Happy path response now includes `:phase1-elapsed-ms` + `:phase2-elapsed-ms`

### Live verification (MANDATORY — per project rule "mocks are dev-only, live runs are mandatory before done")

- [ ] G07 risk-analysis with `:timeout-ms 30000` on the repl-researcher node (canonical run takes 64.8s — must time out):
  - [ ] Phase 2 cancels cleanly partway through the 24-chunk map-each
  - [ ] Saved EDN shows `:status :timeout` with both elapsed-ms fields
  - [ ] Event store has `:sheet/tick-cancelled` for the child tick-id
  - [ ] Bookend `:sheet/rlm-tree-execution-completed` event reflects partial trajectory (some chunks complete, others not)
  - [ ] No orphan events flowing after cancellation (check timestamp gap — events stop within the 500ms drain window, not continuing for tens of seconds)
- [ ] G07 risk-analysis unmodified with generous default timeout — completes in ~64.8s with full output, identical to current main (no regression)

### No regression on happy path

- [ ] Framework tests pass: 73 tests, 277 assertions, 0 failures on RLM-related namespaces
- [ ] All 5 canonical benchmark tasks re-run unmodified produce identical results to current main (no timeouts hit in the canonical suite)

### Single commit on feature branch

- [ ] One coherent feat commit on `feature/core-orc-upgrades` describing budget lookup + skip-if-exhausted + cancellation + drain + response shape additions, plus live verification numbers (cancel timing, drain effectiveness, timestamp-gap evidence of no orphan events)

## Blocked by

D-008 (map-each partial results) — primarily because both touch the same module surface and D-008 ships first. Also D-003's cancellation test exercises the bookend event firing on cancellation, which is cleanest to verify after `:partial` semantics are stable.

## User stories covered

13, 14, 15, 16, 17, 18, 19, 20, 21, 22
