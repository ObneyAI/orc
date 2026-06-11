# PRD: Category D — Resilience for ORC RLM Phase 2 Tree Execution

**Status:** Ready for vertical slices
**Branch:** `feature/core-orc-upgrades` (local, untracked — same pattern as `phase2-observability-layer.md`)
**Source:** Grill-me session Q1–Q10 (all options "i agree")
**Slices:** Two, sequenced — D-008 first, D-003 second

## Problem Statement

The ORC RLM behavior-tree engine has two known resilience gaps that bite tree consumers in production at scale:

**Problem 1 — One bad chunk silently corrupts a whole map-each.**
The canonical risk-analysis run does 24 map-each iterations on a 280K RFP with 4.1× per-chunk token variance. When a single LLM call fails (rate limit, timeout, schema mismatch), map-each currently reports `:status :success` to its parent with a results vector that contains failure markers (`:__status :failure`) inline. Downstream synthesis nodes are given a mixed-status collection they're not designed to handle — at best, they produce a degraded result that looks fine; at worst, they hallucinate to fill gaps. There is no first-class signal that the map-each was actually partial.

**Problem 2 — Phase 2 ignores time budgets.**
The two-phase RLM execution (Phase 1: LLM generates the tree; Phase 2: tree executes via child tick) hardcodes a 900 s ceiling on Phase 2 regardless of how long Phase 1 took or what the caller's actual deadline is. A caller who sets a 120 s deadline can end up waiting 1020 s. The hardcode also leaks compute — when the parent gives up, the child tick keeps executing in the background, burning rate-limited LLM budget.

Both issues are observable today: the per-node usage data from the O02/O03 observability layer makes failure patterns visible, but the engine doesn't yet react to them. Resilience is the next layer on top of the now-complete observability.

## Solution

Two narrow, vertical slices.

**D-008 — First-class partial-results for map-each.**
Map-each emits `:partial` when some children succeed and some fail, `:failure` when all fail, `:success` when all succeed (or when the source list is empty). The output blackboard key contains only the successful results. A `:partial-summary` block on the map-each's completion event captures `{:total :succeeded :failed :failure-indices :failure-reasons :failure-input-profiles}` so judges and ontology pattern-matchers can reason about failures at a glance without scanning per-child events. Sequence and fallback parents treat `:partial` as continuation — existing trees with map-each-then-synthesis keep working, but now synthesis runs on a clean successes-only collection.

**D-003 — Budget-aware Phase 2 timeout with cancellation.**
The repl-researcher node accepts a `:timeout-ms` option that bounds the total Phase-1-plus-Phase-2 budget. After Phase 1 completes, the engine computes `remaining = original - phase1-elapsed` and passes that to Phase 2. If Phase 1 has already exhausted the budget, Phase 2 is skipped with a clean `:failure`. If Phase 2 exceeds its remaining budget, the engine dispatches the existing `:sheet cancel-tick` command on the child tick, waits ~500 ms for in-flight nodes to drain their writes, and returns `:status :timeout` with elapsed-ms fields. No orphan compute keeps running after the parent gives up.

Both slices are entirely self-contained inside `orc-service` and depend on nothing outside the ORC repo. They explicitly lay schema hooks for the future Category C work (judges, fingerprints, ontology pattern matching) without depending on it — so D can ship even if C's ontology work needs significant rework.

## User Stories

1. As an ORC consumer running multi-chunk analysis on a large document, I want one failing chunk not to invalidate the entire run, so that my user receives a useful (partial) result instead of nothing.
2. As an ORC consumer, I want the engine to tell me explicitly when a map-each was partial (vs fully successful or fully failed), so that I can decide whether to retry, surface the partial result, or fail loudly.
3. As an ORC consumer, I want the successful results of a partial map-each delivered as a clean flat vector (no inline failure markers), so that my downstream synthesis prompts don't need to know about ORC's failure-marker convention.
4. As an ORC consumer, I want per-chunk failure details (which index failed, why, with what input profile) available on the map-each's own completion event, so that I can debug or display failure summaries without scanning per-child events.
5. As a tree designer (human), I want the `:partial` status to be a first-class node-status value, so that I can express "this branch is fine even when some children failed" in tree logic.
6. As a tree designer (LLM in Phase 1), I want sequence and fallback parents to treat `:partial` as continuation, so that the trees I generate keep flowing through synthesis steps after a tolerable partial map-each.
7. As an ORC consumer, I want existing trees that pre-date the `:partial` status to keep working without code changes, so that this slice is non-breaking.
8. As an ORC consumer, I want zero map-each-level retry layered on top of per-child retry, so that a single bad chunk fails fast (≤4 s × 3 attempts) rather than blocking the run for minutes.
9. As an ORC consumer, I want an empty source list to remain a `:success` with output `[]`, so that early-stage trees with no work to do don't surface as anomalies.
10. As an ORC consumer, I want a "no items succeeded" outcome to surface as `:failure` (not `:partial`), so that downstream logic can distinguish "we got something" from "we got nothing."
11. As a future C-phase judge implementer, I want `:partial` to be a queryable event-store status (not buried in result data), so that I can subscribe to partial outcomes for pattern-matching and feedback generation.
12. As a future C-phase ontology pattern-matcher, I want `:partial-summary :failure-input-profiles` to correlate input characteristics with failure modes, so that I can learn patterns like "chunks > 11K tokens consistently fail with rate-limit errors."

13. As an ORC consumer setting a deadline on a request, I want the total Phase-1-plus-Phase-2 wall time to honor my budget, so that downstream timeouts and SLA promises work predictably.
14. As an ORC consumer, I want the repl-researcher node itself to carry an optional `:timeout-ms`, so that tree designers can express timing intent at the tree level rather than depending on an out-of-band caller.
15. As an ORC consumer who doesn't specify a timeout, I want the engine to fall back to the parent tick's deadline, so that I get sensible default behavior without configuration churn.
16. As an ORC consumer who doesn't specify a timeout anywhere, I want the engine to fall back to the current 900 s hardcode, so that existing behavior is preserved.
17. As an ORC consumer whose Phase 1 has already eaten the entire budget, I want Phase 2 to be skipped entirely with a clean `:failure :error "Budget exhausted in Phase 1"`, so that I don't waste time on a doomed Phase 2 invocation.
18. As an ORC consumer whose Phase 2 exceeds its budget, I want the child tick cancelled (not just abandoned), so that no LLM calls keep firing in the background after the parent gives up.
19. As an ORC consumer whose Phase 2 was cancelled, I want a brief drain interval so that in-flight nodes have a chance to settle their writes into the event store, so that the bookend event reflects an accurate partial trajectory.
20. As an ORC consumer whose Phase 2 timed out, I want the response to carry `:phase1-elapsed-ms` and `:phase2-elapsed-ms`, so that I can debug whether my time budget is being eaten by code generation or by tree execution.
21. As an observability consumer, I want the existing bookend `:sheet/rlm-tree-execution-completed` event from O03 to fire even on cancellation, so that the trajectory of partial execution is preserved for analysis.
22. As an ORC consumer, I want the canonical 5-task benchmark suite to produce identical results to current main after D ships, so that I can be confident the resilience changes don't regress the happy path.

## Implementation Decisions

### Architecture

This is a Polylith library on top of Grain v2. All changes are inside `components/orc-service`. The top namespace is `ai.obney.orc`. Two slices, sequenced — D-008 lands first because (a) higher real-world urgency and (b) cleaner data layer for the future C-phase judges.

D-008 builds entirely on top of the existing per-child-retry infrastructure from Issue 007 (already DONE). D-003 builds entirely on top of the existing `:sheet cancel-tick` command in `commands.clj`. No new infrastructure invented.

### Modules — D-008

**Modify: `interface.schemas`**
- Add `:partial` to the `node-status` enum.
- Add `:partial` to every event-body status enum that currently lists `:success`/`:failure`/`:running` (node-execution-completed, parallel-completion, etc.).
- Define a Malli schema for `:partial-summary` as a value shape: `{:total :int :succeeded :int :failed :int :failure-indices [:vector :int] :failure-reasons [:map-of :int :string] :failure-input-profiles {:optional true} [:map-of :int :map]}`.
- Make `:partial-summary` an optional field on the `:sheet/node-execution-completed` event body.

**New deep module: `classify-map-each-outcome`** (pure function, no event-store coupling)
- Input: results vector (with successes as raw values and failures as `{:__status :failure :__error ... :__original ...}` per current todo_processors convention), the count of items started.
- Output: `[status :- node-status, partial-summary :- partial-summary-shape-or-nil, output-vector :- vector]`.
- Encapsulates the entire Q6 rule table:
  - 0 items → `[:success nil []]`
  - 0 failed → `[:success nil <full vector>]`
  - 0 < failed < total → `[:partial <summary> <successes-only-vector>]`
  - All failed → `[:failure <summary> []]`
- Deep because: the rule is small but central; future judges may call this directly to classify externally-emitted results.

**Modify: `core.todo-processors / handle-map-each-child-completion`**
- When the `:complete` action fires (all items have terminated), call `classify-map-each-outcome` to produce `[status summary output]`.
- The blackboard write event uses the new `output` vector (successes-only).
- The map-each's own `:sheet/node-execution-completed` event body carries `:status status` and (when `summary` is non-nil) `:partial-summary summary`.

**Modify: `core.todo-processors / handle-child-completion`**
- Sequence parents: continue execution on `:partial` (same path as `:success`).
- Fallback parents: also stop on `:partial` (same path as `:success` — `:partial` means "we got something usable").
- Map-each items themselves do not surface `:partial` (a single LLM call is either `:success` or `:failure`).

**Modify: `core.commands / complete-node-execution`**
- Accept optional `:partial-summary` field on input; pass through to event body.

### Modules — D-003

**New deep module: `resolve-phase2-budget`** (pure function)
- Input: repl-researcher node map, tick-context (with `:options`), `phase1-elapsed-ms`.
- Output: `{:total-budget-ms N :remaining-ms N :source [:node | :tick | :hardcoded] :exhausted? boolean}`.
- Lookup order: `(:timeout-ms node)` → `(get-in tick-ctx [:options :timeout-ms])` → 900000.
- `:exhausted?` is true iff `remaining-ms <= 0`.
- Deep because: same lookup pattern can be reused later for Phase-1 budget checks; the function has a single well-typed contract that all callers depend on.

**Modify: `core.executor / execute-repl-researcher-rlm`**
- Capture `start-time` (already done) and record `phase1-elapsed = now - start-time` right before Phase 2.
- Call `resolve-phase2-budget`. If `:exhausted?`, emit `{:status :failure :error "Budget exhausted in Phase 1" :phase1-elapsed-ms ...}` without invoking `execute-tree`.
- Otherwise pass `:remaining-ms` as `:timeout-ms` to `tree-executor/execute-tree`.
- On `:status :timeout` from `execute-tree`:
  - Dispatch the existing `:sheet cancel-tick` command on the child tick-id.
  - Block on a small drain interval (≈500 ms) so in-flight nodes settle their writes.
  - Return `{:status :timeout :error "..." :phase1-elapsed-ms ... :phase2-elapsed-ms ...}` — no `:outputs`.
- On `:status :success` or `:status :failure`: existing behavior, but also include `:phase1-elapsed-ms` and `:phase2-elapsed-ms` in the response (cheap to add; useful for observability).

**Modify: `interface.schemas`**
- Add optional `:timeout-ms :int` to the repl-researcher node config schema.
- Document `:phase1-elapsed-ms`, `:phase2-elapsed-ms`, and `:status :timeout` shape in the RLM result schema.

### What does NOT change

- No new event types. We reuse `:sheet/node-execution-completed` (with the new `:partial-summary` field), `:sheet/tick-cancelled` (existing), and `:sheet/rlm-tree-execution-completed` (from O03).
- No new commands. Reuse `:sheet/complete-node-execution` (now accepts `:partial-summary`) and `:sheet cancel-tick` (existing).
- No map-each-level retry machinery. Per-child retry from Issue 007 already runs; D-008 just classifies the outcome.
- No partial-outputs-in-timeout-response. Caller receives `:status :timeout` with elapsed-ms; if they want partial state they read events directly.
- No Phase 1 iteration-level budget check (Phase 1 still runs to its own max-iterations limit; D-003 only bounds Phase 2).
- No threshold configurability for `:partial` vs `:failure` (the rule from Q6 is universal).

## Testing Decisions

### What makes a good test for this work

Tests target the external contract — emitted event shapes, blackboard write contents, response shape, status transitions — not internal data structures. The deep modules (`classify-map-each-outcome`, `resolve-phase2-budget`) are pure functions and get isolated unit tests; everything else is integration-style and walks the real handler code paths.

A repeated principle from the user, saved as feedback memory: **mocks are acceptable for fast unit-test iteration, but every slice MUST be verified with a live real run** (real LLM, real data, real event store) before being marked done. Production-truth is mandatory.

### Test plan per slice

**D-008 deep-module unit (`classify-map-each-outcome`):**
- Each row of the Q6 rule table as a separate test
- Including the empty-list edge case and the all-fail edge case
- Confirms output vector shape (succeeds-only) and `:partial-summary` shape including `:failure-input-profiles`

**D-008 integration (existing `map_each_node_test.clj` + new tests in `rlm_tree_executor_test.clj`):**
- Happy path unchanged (24/24 succeed → `:success`, no `:partial-summary`, output is full vector)
- 22 succeed + 2 forced failures → `:partial` event, output vector has 22 items, `:partial-summary` carries indices 7 + 17 with errors and input-profiles
- All fail → `:failure` event, output `[]`, `:partial-summary` shows `:succeeded 0`
- Empty source list → `:success`, output `[]`, no `:partial-summary`
- Sequence parent treats `:partial` as continuation — a sequence with map-each-then-synthesis still fires the synthesis node's start event when map-each is `:partial`
- Per-child events still emit individually with structured paths (canonical record preserved)

**D-008 schema:**
- `:partial` validates as `node-status` in all relevant enums
- `:partial-summary` shape validates including the optional `:failure-input-profiles`

**D-008 live verification (NON-OPTIONAL):**
- Modify `rlm-gen-bench` to support injecting a "sentinel failure" instruction in a single chunk of risk-analysis
- Run G07-style risk-analysis with the failure injected on one chunk; confirm:
  - Saved EDN's map-each `:status` = `:partial`
  - Bookend `:sheet/rlm-tree-execution-completed` event reflects `:partial` somewhere in the trajectory
  - Synthesis runs on the 23 successful chunks
  - O02 `:by-node` shows 23 succeeded + 1 failed
- Re-run all 5 canonical benchmark tasks unmodified; confirm output is identical to current main (no regression on the happy path)

**D-003 deep-module unit (`resolve-phase2-budget`):**
- Node-level `:timeout-ms` takes precedence → returns `{:source :node ...}`
- Tick `:options :timeout-ms` used when node has none → `:source :tick`
- Neither set → `:source :hardcoded`, `:total-budget-ms 900000`
- `phase1-elapsed > total-budget` → `:exhausted? true`, `:remaining-ms 0`

**D-003 integration (new tests, likely in `rlm_tree_executor_test.clj`):**
- `:exhausted?` skips Phase 2 entirely; response is `:failure` with "Budget exhausted in Phase 1"
- Phase 2 timeout dispatches `:sheet cancel-tick` (verify event-store has `:sheet/tick-cancelled` for the child tick-id)
- 500 ms drain allows bookend event to fire with partial trajectory
- Response shape: `{:status :timeout :error :phase1-elapsed-ms :phase2-elapsed-ms}` with no `:outputs`
- Happy path: `:status :success` response now also includes `:phase1-elapsed-ms` + `:phase2-elapsed-ms`

**D-003 live verification (NON-OPTIONAL):**
- G07 risk-analysis with `:timeout-ms 30000` on the repl-researcher node (canonical run takes 64.8 s, so this MUST time out). Confirm:
  - Phase 2 cancels cleanly partway through the 24-chunk map-each
  - Saved EDN shows `:status :timeout` with elapsed-ms fields
  - Event store has `:sheet/tick-cancelled` for the child tick
  - Bookend event shows partial trajectory (some chunks complete, others not)
  - No orphan events flowing after cancellation (verify by checking the timestamp gap — events should stop within the 500 ms drain window, not continue for tens of seconds)
- G07 unmodified with generous timeout — completes in ~64.8 s with full output, identical to current main

### Prior art for tests

- `rlm_tree_executor_test.clj` (added during O02/O03) — same fixture pattern with mock DSCloj provider and in-memory event store. New D-008/D-003 integration tests fit cleanly here.
- `map_each_node_test.clj` — existing integration tests for map-each happy path. New partial/failure tests extend this file.
- O02/O03 verification approach — run live G07 with the new feature exercised, then spot-check the saved EDN. Same model applies.

## Out of Scope

- Map-each-level retry layered on top of per-child retry (Q5).
- Partial-outputs-on-timeout in the response shape (Q9 Option C — partial outputs are recoverable from events).
- Phase 1 iteration-level budget check (Phase 1 still runs to its max-iterations limit even when the budget is mostly spent).
- Per-leaf timeout inheritance (LLM calls inside Phase 2 don't get a cascaded slice of the remaining budget — they use their own retry config).
- Configurable threshold for `:partial` vs `:failure` (Q6 is a fixed rule).
- Sibling blackboard key for failures (e.g. `<output-key>-failures`) — failures live in events; revisit if a real use case demands the data on the blackboard plane.
- All of Category C (judges, fingerprints, ontology pattern matching).
- All of Category E (conversational debugging, skills→trees, personality layer).

## Further Notes

### Sequencing relative to Category C

User constraint, in their own words: *"the ontology system may end up needing some extra work and i dont want that to force us to put off resilience."*

D is engineered to be entirely self-contained. It needs no judges, no fingerprints, no ontology. The schema hooks for C are passive — they sit in the event-store waiting for C consumers, but no D code reads them.

After D ships:
- C judges can subscribe to `:sheet/node-execution-completed` events filtered by `:status :partial` or `:status :failure` for granular per-node signal.
- C judges can subscribe to `:sheet/rlm-tree-execution-completed` (the O03 bookend) for tree-level signal.
- C ontology pattern-matching can join `:partial-summary :failure-input-profiles` with task-fingerprints to learn input-correlated failure patterns.
- The `:task-fingerprint` placeholder is already in the bookend event from O03; future Issue 012 fills it.

If ontology rework runs long, D is safe — resilience ships independently and the C work picks up whenever ontology is ready.

### Branch / commit conventions

- PRD stays local on `feature/core-orc-upgrades` (untracked, like the prior PRDs).
- Issues will be saved under `docs/issues/resilience/D-008-*.md` and `D-003-*.md`, also local-only.
- Each slice commits to `feature/core-orc-upgrades`; when both slices ship and verify live, port the framework changes to `main` via the same selective-checkout pattern used for O01+O02+O03.
- Commit messages match the project style (feat / fix / refactor with brief why, Co-Authored-By trailer).

### Risk register

- **R1 — Sequence/fallback parent semantics change might surprise existing trees.** Mitigated by Q22 verification (re-run all 5 canonical benchmarks unmodified; results must be identical).
- **R2 — Cancellation drain timing.** If 500 ms is too short, the bookend event may fire with a less complete trajectory. Mitigated by checking the timestamp gap in live verification; if needed, the drain interval becomes a tunable.
- **R3 — Per-child retry already runs to completion before map-each classifies.** This means a `:partial` result can take up to 12 s longer (3 attempts × 4 s max backoff) than a "fail fast" approach. Acceptable for now; retry-fast option could be added later if telemetry warrants.
