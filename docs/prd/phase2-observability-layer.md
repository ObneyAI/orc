# PRD — Phase 2 Observability Layer

**Status:** Draft (local, not committed)
**Date:** 2026-05-19
**Branch:** `feature/core-orc-upgrades` (do NOT merge to main yet)
**Source:** Audit + planning session on 2026-05-19. See `/Users/darylroberts/.claude/plans/optimized-kindling-flame.md` for the pre-grill audit and the 5-category outstanding-work framing (A: quick-wins, B: observability, C: self-improving loop, D: resilience, E: big-vision items). This PRD implements **Category B** with Slice 0 collapsing Category A1 into a test-alignment step.

## Problem Statement

As a developer working on ORC RLM, I have proven that the engine genuinely adapts behavior tree design to task type (5-task generalization benchmark on main, zero hallucinations across 37 spot-checks). But the next phase of work — making trees **self-improving** via judges that score per-node and per-tree quality, task fingerprints for similarity matching, and ontology pattern accumulation — cannot start until the system emits the events those downstream consumers need.

Today, when an RLM Phase 2 tree executes, the only telemetry surfaced to consumers is a single aggregate `:usage` map at the tree-execution level. There is no per-node token tracking, no `:rlm/tree-node-completed` event with structured fields, no `:rlm/tree-execution-completed` bookend, no trajectory, no input profile. The 50K+ LOC of already-built GEPA, ontology, and rolling-metrics infrastructure in the codebase is starved for signal from RLM runs because the signal isn't being emitted.

Also, the failing tests in `rlm_mode_test.clj` (3 assertions) assert behaviour that contradicts the actual implemented architecture. The architecture is intentional and correct (sub-LLM calls receive full values from `:reads`; only the code-generating parent LLM sees previews of sandbox-vars). The tests were written aspirationally and never reconciled. They need to be aligned before we add new tests on top of the same area.

## Solution

A two-slice observability layer that:

1. **Aligns the failing preview tests to the actual architecture** (small, decouples from rest of work).
2. **Slice 1**: extends node completion with per-node usage tracking, in the event store via proper Grain command/event flow. Emits both an enriched generic completion event (universal token tracking) and a new RLM-specific completion event (path-aware, room for later judge fields).
3. **Slice 2**: emits a tree-execution bookend event with trajectory, and captures input profile on each RLM node completion. Updates the bench reports to surface the new per-node data.

After these two slices, the event store contains every signal needed by future judge / fingerprint / pattern-matching work. Saved result EDNs gain a per-node usage breakdown immediately. Bench reports gain a "Token Breakdown" section showing where each tree actually spends its tokens.

## User Stories

1. As a benchmark author, I want the failing preview tests to assert the actual correct architecture, so that the test suite green-bars and gives accurate signal.
2. As a benchmark author, I want a code comment confirming "sub-LLM calls receive FULL values; previews are only for the code-generating LLM" to remain in place, so that future contributors don't reintroduce the test-vs-code mismatch.
3. As an ORC service consumer running ANY behavior tree (RLM or otherwise), I want per-node token usage tracked in the event store, so that I can answer "where did the tokens go" without manual instrumentation.
4. As an RLM workflow author, I want per-node telemetry emitted via `:rlm/tree-node-completed` events, so that downstream judges and pattern-matchers can subscribe to them.
5. As an RLM workflow author, I want each node-completed event to carry a `node-path` that incorporates map-each iteration context, so that I can distinguish "iteration 3 of chunk processing" from "iteration 7" or from the final synthesis stage.
6. As a benchmark report reader, I want the saved EDN result file to include a `:by-node` token breakdown under `:usage`, so that I can analyse where tokens went without re-running anything.
7. As a benchmark report reader, I want the `:by-node` totals to sum (within small rounding) to the aggregate `:total-tokens`, so that I can trust the breakdown is complete.
8. As a benchmark report reader, I want an "Token Breakdown" section in each task report (tasks/01-05.md), so that the bench documentation reflects the new visibility.
9. As an RLM workflow author, I want a `:rlm/tree-execution-completed` bookend event with timestamps and parent chain (trajectory), so that I can replay the full execution timeline.
10. As an RLM workflow author, I want each node-completed event to carry an `:input-profile` with at least chunk-length and content-density indicators, so that future judges can correlate quality to input shape.
11. As an RLM workflow author, I want a placeholder `:task-fingerprint` field on the tree-execution-completed event, so that task-fingerprint work later (issue 012) has a stable place to write.
12. As a Grain consumer, I want all event emissions to flow through proper command handlers (no direct event-store writes from processors), so that the event log remains the single source of truth and validation is centralised.
13. As a developer reading the codebase, I want event schemas updated in `interface/schemas.clj` rather than ad-hoc maps, so that the contract is discoverable.
14. As a benchmark runner, I want both G04 and G07 benchmarks to pass end-to-end with the new event fields populated, so that I can verify the change with the existing test corpus.
15. As a framework test reader, I want the 24-test framework suite to continue passing without modification, so that the observability work doesn't regress existing behaviour.
16. As a benchmark report reader, I want existing token-usage numbers in `tasks/01-05.md` and `RESULTS.md` to remain valid (no re-baselining required) because the preview "bug" is a test bug not a code bug, so that the published story stays consistent.
17. As a maintainer, I want this work to live on `feature/core-orc-upgrades` and NOT main, so that main continues to ship only verified, complete capability while research-shaped iteration happens on the branch.

## Implementation Decisions

### Architectural framing

This is Phase 2 observability work, the prerequisite for Phase 3 self-improving loop work (judges, fingerprints, ontology integration). The decision to start with observability rather than going straight to judges is explicit: judges need events to subscribe to and score; pattern-matchers need fingerprints and input profiles to compare against the ontology; today the events don't carry that signal.

### Slice 0 — Test alignment

A single small slice executed first, in isolation, with its own commit.

- Update the two failing preview tests in `rlm_mode_test.clj` to assert the correct architecture: sub-LLM calls receive full values for `:reads`; the parent code-generating LLM's `:inputs-info` summary uses previews.
- Add a brief comment in the test file referencing the load-bearing comment in `rlm_sandbox.clj` so future contributors understand the contract.
- No production code changes in this slice.
- Verification gate: framework tests go from "24/95 pass / 3 fail" to all-pass.

### Slice 1 — Per-node usage events

The minimum-viable observability emission. Goal: every node completion lands in the event store with its token usage attached, accessible via the standard Grain patterns.

**Modules touched:**
- `interface/schemas.clj` — add optional `:usage` field to the generic node-completed event schema; add a new `:rlm/tree-node-completed` event schema with at least `{:node-id, :node-path, :usage, :timestamp}` and explicit room for later fields (`:config`, `:input-profile`, `:scores`, `:feedback`).
- `core/commands.clj` — extend the existing complete-node-execution command to accept `:usage`; add a new command that emits the `:rlm/tree-node-completed` event when an RLM Phase 2 node finishes.
- `core/todo_processors.clj` — the leaf-execution processor that currently captures usage in an atom must also pass it into the complete-node-execution command. The handler that processes a node-completed event in RLM Phase 2 context must dispatch the new RLM-specific command.
- Runner / result-capture path — when a Phase 2 tree run completes, aggregate the per-node usage from the new events into a `:by-node` map and include it in the saved EDN under `:usage`.

**Schema shape (illustrative, not final):**

```clojure
;; existing extended:
:sheet/node-execution-completed
  {... :usage (optional)
       {:prompt-tokens int :completion-tokens int :total-tokens int}}

;; new:
:rlm/tree-node-completed
  {:node-id uuid
   :node-path string   ; derived from map-each context, e.g. "map-each-2[3]/llm-4"
   :usage {...}
   :timestamp inst
   ;; placeholders for future fields, omitted in slice 1:
   ;; :config, :input-profile, :scores, :feedback
   }
```

**Node-path derivation:** the existing `::map-each-parent` and `::map-each-index` keys travel in event inputs today. The new event captures these at emission time. Full ancestry resolution can be done later via the read model walking up the sheet's node tree.

**Grain pattern:** commands → events → (eventually) read models → queries. No bypassing.

### Slice 2 — Trajectory + input profile + report visibility

Builds on slice 1's events.

**Modules touched:**
- `interface/schemas.clj` — add `:rlm/tree-execution-completed` (bookend) schema with `:trajectory` (vec of `{:node-id :timestamp :event-type}`), placeholder `:task-fingerprint` (string or nil for now), and `:efficiency-analysis` placeholder. Extend `:rlm/tree-node-completed` schema with `:input-profile` field.
- `core/commands.clj` — add command that emits the bookend event when a Phase 2 tree-execution finishes.
- `core/todo_processors.clj` — when emitting a `:rlm/tree-node-completed`, also compute the input profile (length and content-density indicators per `:reads` key); when the parent Phase 2 tick completes, dispatch the new bookend command.
- Bench task reports (`development/bench/tasks/01-05.md`) — add a "Token Breakdown" section per task showing the per-node distribution from a canonical run.
- `RESULTS.md` — if a headline observation emerges from the breakdowns (e.g. "synthesis dominates token cost on G06"), surface it.

### Architectural notes carried from the grill

- The preview architecture is **deliberate and load-bearing**: parent code-generating LLM sees previews of sandbox-vars (token-space economy), sub-LLM calls receive full values from `:reads`. The existing comment at `rlm_sandbox.clj:125-127` is the canonical documentation; the failing tests were written against an aspirational alternative architecture and need to be aligned, not the code.
- Existing benchmark numbers on main ARE valid signal because the preview "bug" is a test bug. No re-baselining required.
- This work lives on `feature/core-orc-upgrades`. Main continues to ship only complete-and-verified capability.

## Testing Decisions

What makes a good test for this work: it exercises the event-emission pipeline end-to-end (command → event → read-model projection → optional query), not the internal helper functions. Specifically:

- For slice 0: the existing failing tests at `rlm_mode_test.clj:1059` and `:1232` get updated assertions. They become positive contracts for the correct architecture, not regressions to fix.
- For slice 1: the verification is integration-style — run G04 (smallest, fastest task) and G07 (largest, exercises Phase 2 emit-tree! path with parallel map-each), then inspect the saved result EDN. The `:usage :by-node` map should exist, contain entries for at least the sub-LLM nodes, and sum (within rounding) to `:usage :total-tokens`.
- For slice 2: the verification is the same integration shape plus inspection of the bookend event — it should be present in the event store with a non-empty trajectory and a populated (even if minimal) input-profile field on each per-node event.

Prior art for this style of test: the existing `rlm-tree-executor-test/map-each-max-concurrency-runs-iterations-in-parallel` is the model. It exercises the framework end-to-end with a deterministic mock LLM and asserts on observable behaviour (peak in-flight count). The new tests follow that shape: small mocked-LLM scenario, real event-store path, assert on the events / event-derived data the consumer would actually see.

We do NOT add unit tests for: the new schema map shape (it's data, deserialisation would catch malformation), individual helper functions in the processors (those are implementation), or the bench report markdown sections (manual review).

We DO add at least one test that verifies the `:usage :by-node` totals approximately equal the aggregate `:total-tokens` — this is the "we can trust this breakdown" contract.

## Out of Scope

The pre-grill audit (in the plan file) categorised all outstanding work into five buckets A–E. This PRD addresses **Category B** (and a sliver of Category A as slice 0). The remaining categories are **explicitly deferred** and **must not be forgotten** — each gets its own PRD when picked up.

### Category A — Quick wins / known bugs

- **A1 — Preview leakage / B01** is **absorbed into Slice 0** of this PRD. Audit revealed the "bug" was a test-vs-code mismatch (the code is intentionally correct; the tests assert an aspirational alternative architecture). The actual fix is small and lives in the test layer.

No other Category A items remain.

### Category C — Self-improving loop (issues 009, 010, 011, 012, 014)

The next major chunk after observability. The four-line summary of why we're deferring it: judges need events to subscribe to, fingerprints need input profiles to compute, and pattern-matching needs node-level usage data to detect efficiency wins. Slices 1+2 of THIS PRD emit those events. C work picks up from there.

Specific deferred items:
- **C1** — Node-level judge processor that scores per-node quality and emits `:rlm/node-evaluated` events with principle-based feedback (issue 009)
- **C2** — Tree-level judge processor that evaluates the holistic tree and emits efficiency-analysis events (issue 010)
- **C3** — HITL judge-quality verification loop — manual review of judge outputs to ensure principles are useful (issue 011)
- **C4** — Task fingerprint capture (issue 012). Slice 2 of this PRD adds the placeholder `:task-fingerprint` field; C4 fills it.
- **C5** — Pattern judge that correlates tree evaluations to the existing ontology already built in `components/ontology/` (issue 014)

**Critical context for C work**: GEPA, ontology, and rolling-metrics infrastructure are ALREADY BUILT in `components/`. The biggest leverage isn't writing new capabilities — it's wiring those existing systems to the RLM tree execution feedback loop. Slices 1+2 of this PRD are the prerequisite plumbing for that wiring.

### Category D — Resilience (issues 008, 003)

- **D1** — Collect partial results on map-each failures (issue 008). Currently the map-each processor returns `:success` or fails entirely; there's no `:partial` status with which items succeeded vs failed.
- **D2** — Budget-aware Phase 2 timeout (issue 003). Currently the Phase 2 timeout is hardcoded at 900s; should be `original-timeout - phase1-elapsed`.

### Category E — Big-vision items from `docs/FUTURE-VISION.md`

The FUTURE-VISION doc itself is outdated — GEPA, rolling-metrics, tree library, ontology, and tree self-description are ALL already built per the audit. The truly-greenfield items remaining are:

- **E1 — Conversational debugging**: an agent that lets a developer "talk to a trace" — query why a specific node failed, what its inputs looked like, what alternatives the model considered. Foundation exists (rolling-metrics, tree profiles, timestep state capture); the agent layer does not.
- **E2 — Skills → Trees pipeline**: turn user-defined skills into executable behavior trees. Partial foundation (MCP Sheet Builder, pattern library).
- **E3 — Personality layer**: customer-facing message filtering via a soul.md config + a personalize node type. Smallest foundation; integrates as a node type.

### Summary of deferred work

| Cat | Items | Why deferred |
|-----|-------|--------------|
| C   | C1–C5 (judges, fingerprint, pattern) | Needs observability events from Slice 1+2 first |
| D   | D1–D2 (partial results, budget timeout) | Independent; pick when resilience matters more than learning loop |
| E   | E1 conversational debugging, E2 skills→trees, E3 personality | Greenfield; pick when ready to expand product surface |

## Further Notes

- This PRD is local-only and intentionally not committed to git. It lives at `docs/prd/phase2-observability-layer.md` as planning context on the `feature/core-orc-upgrades` branch.
- The grill session that produced this PRD is captured in `/Users/darylroberts/.claude/plans/optimized-kindling-flame.md`. Refer to that file for the full audit + decision trail.
- The parent PRD `docs/prd/rlm-phase2-tree-execution.md` already exists on this branch and defines the broader Phase 2 architecture, including the judge-event field shapes. This PRD is consistent with that document's design and implements its slices 4-6 (per-node usage, completion events, input profile, trajectory).
- If slice 1 reveals deeper Grain-pattern issues (e.g. discovering the event-emission pipeline has implicit ordering constraints we haven't accounted for), pause and reassess scope rather than push through.
- The rollback path is trivial: each slice is its own commit; revert in reverse order.
