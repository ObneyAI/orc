# R-2: Drill-down primitives for tree introspection

## Parent

PRD: `docs/prd/rlm-recursive-emit-tree.md` (local, on feature branch)

## What to build

Enrich the recursive RLM mode (from R-1) with five new SCI sandbox primitives that let the model pull detailed tree-execution information on demand. The summary in `:tree-results` (built in R-1) is sufficient for most reasoning; these primitives are for cases where the model wants to drill into specifics — which exact node failed and why, what trajectory the tree took, what specific sub-node produced what output.

All five primitives are read-only queries over the existing event store (O02 per-node events + O03 bookend trajectory + input profiles). NO new events captured — we're exposing what's already collected.

The primitives are wired ONLY when `:rlm {:recursive? true}` is set. In non-recursive mode they're not in the SCI bindings (avoiding namespace pollution for simpler use cases).

### The five primitives

```
(tree-detail)             ; full structured info for most recent tree
(tree-detail tick-id)     ; full structured info for a specific past tree

(tree-trajectory)         ; chronological per-event log of most recent tree
(tree-trajectory tick-id) ; for a specific past tree

(tree-failures)           ; failure entries for most recent tree (errors + input profiles)

(node-output node-id)     ; outputs from a specific node in the most recent tree
(node-input-profile node-id) ; input profile from O03 for a specific node
```

### Behavior contract

- All primitives query the event store via filters on `[:tick tick-id]` tag + event type
- Returned data is cleaned (no raw `:event/*` keys; user-facing projections only)
- `(tree-detail)` / `(tree-trajectory)` / `(tree-failures)` operate on the most-recent tree by default (latest entry in `:tree-results`)
- `(tree-detail tick-id)` / `(tree-trajectory tick-id)` operate on a specific past tree by tick-id
- Invalid tick-id (no matching events) returns `nil` rather than crashing or throwing
- `(node-output node-id)` / `(node-input-profile node-id)` use the most-recent tree's tick-id implicitly

### Deep module to extract

A namespace of pure query functions takes `{:event-store ... :tenant-id ... :tick-id ...}` and returns the projection for each primitive. SCI bindings are thin wrappers over these queries. The query functions are testable independent of SCI with fixture event data.

### Prompt update

The recursive-mode prompt section (from R-1) is extended to list the new primitives with one-line descriptions. The framing emphasizes "use only when summary is insufficient" — mitigating risk R6 (drill-down primitives bloating prompt context with multi-KB responses).

## Acceptance criteria

### Primitive availability

- [ ] All 5 primitives are callable from generated code in iterations AFTER an `emit-tree!` recur, when `:rlm {:recursive? true}`
- [ ] Primitives are NOT in the SCI bindings when `:recursive?` is false — verify by trying to call them in non-recursive mode (they should be undefined / unresolved)

### `(tree-detail ...)`

- [ ] `(tree-detail)` returns a structured map with the most recent tree's full execution detail: `:tick-id`, `:tree-raw`, `:status`, `:elapsed-ms`, `:outputs`, per-node entries (status, node-type, structured-path, inputs/outputs/usage if applicable), `:partial-summary` if present, `:trajectory` (count or excerpt)
- [ ] `(tree-detail tick-id)` returns the same shape for a specific past tree
- [ ] `(tree-detail <invalid-tick-id>)` returns `nil` (no crash, no exception bubbling)
- [ ] Per-node entries derived from `:sheet/node-execution-completed` events tagged with the tick-id

### `(tree-trajectory ...)`

- [ ] `(tree-trajectory)` / `(tree-trajectory tick-id)` returns a chronologically-ordered vector of cleaned event entries: `[{:timestamp ... :event-type ... :node-id ... :status ...} ...]`
- [ ] Trajectory comes from the bookend `:sheet/rlm-tree-execution-completed` event's `:trajectory` field (already populated by O03)
- [ ] Invalid tick-id returns `nil`

### `(tree-failures)`

- [ ] Returns the failure entries for the most recent tree as a vector: `[{:index ... :node-id ... :error ... :input-profile {...}} ...]`
- [ ] When the tree was fully `:success`, returns `[]` (empty vector, not nil)
- [ ] Failure data comes from D-008's `:partial-summary` (failure-indices + failure-reasons) joined with O03's `:input-profile` per node

### `(node-output ...)` and `(node-input-profile ...)`

- [ ] `(node-output node-id)` returns the outputs map for that node from the most recent tree's `:sheet/node-execution-completed` event (the `:writes` field)
- [ ] `(node-input-profile node-id)` returns the input-profile map from O03's `:sheet/rlm-tree-node-completed` event for that node
- [ ] Both return `nil` for invalid node-ids

### Tests (mock executor — for dev iteration)

- [ ] Unit tests for each query function with fixture event data covering: success, partial, failure, timeout cases
- [ ] Integration test: from inside a recursive RLM iteration after a mock-emitted tree, call each of the 5 primitives and verify the returned shape matches expectations
- [ ] Integration test: invalid-input handling — `(tree-detail <bad-uuid>)` and `(node-output <bad-uuid>)` return nil cleanly
- [ ] Integration test: namespace pollution check — with `:rlm true` (no `:recursive?`), the primitives don't exist in the SCI sandbox; verifying generated code that calls `(tree-detail)` would get an unresolved-symbol error rather than executing

### Prompt update

- [ ] System prompt (recursive-mode section from R-1) is extended to list the five primitives with one-line descriptions
- [ ] Framing: "use only when summary is insufficient" (mitigating risk R6)
- [ ] No urgent/warning language; descriptive only

### Live verification (MANDATORY — per project rule "mocks are dev-only, live runs are mandatory before done")

- [ ] **Adaptive task with sentinel failure injection.** Task: a tree run with a forced sentinel failure on one map-each iteration (D-008 style — using a `:code` sibling that throws on a specific item). Confirm the model:
  - Sees `:partial` in `:tree-results` after the tree completes
  - Uses at least one drill-down primitive in its next iteration (e.g., `(tree-failures)` or `(tree-detail)`) — captured via the iteration history saved in the result EDN
  - Reacts: emits a follow-up tree, runs `(llm ...)` inline on the failures, OR calls `(final! ...)` with what it has. ANY of these reactions is acceptable — what we're proving is the OPTION exists and the model exercises it.
- [ ] Live verification saves an EDN file showing the iteration history with the drill-down primitive call visible in the generated code

### No-regression

- [ ] R-1's live verification (two-step task) still passes with R-2's primitives wired in (drill-down primitives don't break the core loop)
- [ ] Existing `:rlm true` (non-recursive) callers behave identically to current main — drill-down primitives don't exist in their SCI namespace
- [ ] Framework test suite stays green (R-1 baseline + R-2's new tests)

### Single commit on feature branch

- [ ] One coherent feat commit on `feature/core-orc-upgrades` describing the new primitives, the query deep module, the prompt extension, plus live verification numbers (real LLM calls, drill-down primitive call observed in iteration history, final output shape).

## Blocked by

R-1 (core recursive loop). R-1 establishes the recursive mode infrastructure (opt-in flag, dispatch change, sandbox-vars merge, `:tree-results` history). R-2 hangs new sandbox primitives off the same `:recursive?` flag and queries the events that R-1's loop emits.

## User stories covered

PRD user stories: 14–18 (drill-down primitives), plus the live-verification cross-cut with the adaptive-failure-recovery scenario from R-1's broader story set.
