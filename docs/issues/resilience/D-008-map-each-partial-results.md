# D-008: Map-each partial results

## Parent

PRD: `docs/prd/category-d-resilience.md` (local, on feature branch)

## What to build

When some children of a map-each succeed and some fail, surface that fact cleanly to the rest of the tree and to the event store. Today, one failing chunk silently corrupts the entire map-each — failures get inlined into the results vector with `:__status :failure` markers, and the map-each reports `:status :success` to its parent regardless. Downstream synthesis nodes are given a mixed-status collection they're not designed to handle.

This slice introduces `:partial` as a first-class node-status value:

- **Empty source list → `:success`** (output `[]`, unchanged)
- **All items succeed → `:success`** (output is full vector, unchanged)
- **0 < items failed < total → `:partial`** (output is successes-only flat vector; failure details surface via `:partial-summary`)
- **All items failed → `:failure`** (output `[]`)

No threshold knob, no configurable failure policy. Threshold reasoning lives in judges, not in the executor.

When status is `:partial` or `:failure`, the map-each's `:sheet/node-execution-completed` event carries a `:partial-summary` block:

```clojure
{:partial-summary
 {:total N
  :succeeded N
  :failed M
  :failure-indices [...]
  :failure-reasons {idx error-string ...}
  :failure-input-profiles {idx {...input-profile from O03...} ...}}}
```

This is a denormalized convenience layer. The canonical record stays the per-child `:sheet/node-execution-completed` events (each with their own status, error, structured path from O02, input-profile from O03) — rolling judges in the future Category C work will subscribe to those.

The output blackboard key contains ONLY the successful results as a flat vector. Failures are NOT inlined with `:__status :failure` markers (current behavior dropped). Downstream consumers — including LLM synthesis prompts — get a clean collection without needing to know about ORC's failure-marker convention.

Sequence and fallback parents treat `:partial` as continuation (same path as `:success`). Existing trees with map-each-then-synthesis keep working; synthesis now runs on a clean successes-only collection instead of a mixed-status one.

Map-each items themselves do not surface `:partial` — a single LLM call is either `:success` or `:failure`. Per-child retry from Issue 007 already runs to completion before the classifier sees the outcome; no map-each-level retry layer.

Pure deep module extracted: `classify-map-each-outcome` takes the results vector with the existing `:__status` failure markers plus the count of items started, and returns `[status partial-summary-or-nil output-vector]`. Encapsulates the entire Q6 rule table; unit-testable in isolation without any event-store dependency.

Use proper Grain patterns throughout: commands emit events; processors react. No direct event-store writes.

## Acceptance criteria

### Schema

- [ ] `:partial` is a valid value in the `node-status` enum
- [ ] `:partial` is a valid value in every event-body status enum that currently lists `:success`/`:failure` (node-execution-completed and any sibling enums in the schemas file)
- [ ] `:partial-summary` Malli schema defined with shape `{:total :int :succeeded :int :failed :int :failure-indices [:vector :int] :failure-reasons [:map-of :int :string] :failure-input-profiles {:optional true} [:map-of :int :map]}`
- [ ] `:sheet/node-execution-completed` event body accepts optional `:partial-summary`
- [ ] `:sheet/complete-node-execution` command accepts optional `:partial-summary` and the handler emits it in the event body

### Deep module — `classify-map-each-outcome`

- [ ] Pure function, no event-store coupling
- [ ] Returns `[:success nil []]` for empty input
- [ ] Returns `[:success nil <full vector>]` when 0 items failed
- [ ] Returns `[:partial <summary> <successes-only-vector>]` when 0 < failed < total
- [ ] Returns `[:failure <summary> []]` when all items failed
- [ ] `:partial-summary` includes `:failure-input-profiles` when provided
- [ ] Unit tests cover every row of the rule table including edge cases

### Map-each handler

- [ ] When all items complete, the handler calls `classify-map-each-outcome` to produce `[status summary output]`
- [ ] The blackboard write event for the output key uses the classifier's `output` vector (successes-only)
- [ ] The map-each's own `:sheet/node-execution-completed` event body carries `:status status` and (when non-nil) `:partial-summary summary`

### Parent semantics

- [ ] Sequence parent treats `:partial` as continuation (same path as `:success`)
- [ ] Fallback parent treats `:partial` as continuation (same path as `:success`)
- [ ] Map-each items themselves never report `:partial` (single LLM calls are `:success` or `:failure`)

### Tests (mock executor — for dev iteration)

- [ ] 24/24 succeed: status `:success`, no `:partial-summary`, output is full 24-item vector — unchanged from current behavior
- [ ] 22 succeed + 2 forced failures: status `:partial`, output is 22-item vector, `:partial-summary` carries the right indices, reasons, and input-profiles
- [ ] All fail: status `:failure`, output `[]`, `:partial-summary` shows `:succeeded 0`
- [ ] Empty source list: status `:success`, output `[]`, no `:partial-summary`
- [ ] Sequence parent + map-each-then-synthesis: synthesis fires its start event when map-each is `:partial`
- [ ] Per-child events still emit individually with structured paths from O02 (canonical record preserved)

### Live verification (MANDATORY — per project rule "mocks are dev-only, live runs are mandatory before done")

- [ ] `rlm-gen-bench` infrastructure extended to support injecting a sentinel failure on one chunk of risk-analysis
- [ ] Sentinel-failure G07 run on risk-analysis produces saved EDN with map-each `:status :partial`
- [ ] Bookend `:sheet/rlm-tree-execution-completed` event from O03 reflects `:partial` somewhere in the trajectory
- [ ] Synthesis runs on the 23 successful chunks and produces a coherent result
- [ ] O02 `:by-node` reflects 23 succeeded + 1 failed (not 24 succeeded)

### No regression on happy path

- [ ] Framework tests pass: 73 tests, 277 assertions, 0 failures on RLM-related namespaces
- [ ] All 5 canonical benchmark tasks re-run unmodified produce identical results to current main (none of them inject failures, so all should remain `:success` end-to-end)

### Single commit on feature branch

- [ ] One coherent feat commit on `feature/core-orc-upgrades` describing the three coordinated layers (classifier, handler wiring, parent semantics) plus live verification numbers

## Blocked by

None — can start immediately. Builds on the already-shipped O02 (per-node usage with structured paths) and O03 (bookend event + input-profile) infrastructure.

## User stories covered

1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 22
