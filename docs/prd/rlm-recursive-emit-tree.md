# PRD: Recursive `emit-tree!` — True RLM Recursion

**Status:** Ready for `/to-issues`
**Branch:** `feature/core-orc-upgrades` (local, untracked — same pattern as prior PRDs)
**Source:** Grill-me session Q1–Q8 (all options "i agree") + review/refine pass
**Slices:** R-1 (core recursive loop) → R-2 (drill-down primitives) — see Proposed Slicing
**Priority:** Foundational detour before Category C (self-improving loop)

## Cross-references to prior work

This PRD builds directly on two foundations already on `main`:

- [`phase2-observability-layer.md`](phase2-observability-layer.md) (DONE) — O02 per-node usage events with structured paths and O03 bookend `:sheet/rlm-tree-execution-completed` event with full trajectory + `:input-profile`. These events ARE the data source for the new drill-down primitives in this PRD. Without O02/O03 we couldn't build this.
- [`category-d-resilience.md`](category-d-resilience.md) (DONE) — D-008's `:partial-summary` provides failure details for the new tree-results summary; D-003's `:timeout-ms` + cancellation provides the budget machinery that bounds recursive tree execution.

Sequenced AFTER this PRD (deferred but not abandoned):

- **Category C — the self-improving loop** (judges → fingerprints → ontology pattern matching). The `:tree-results` accumulating vector from this PRD's work feeds directly into Category C's judge layer; each tree-results entry is a candidate scoring input. This PRD is the data layer; Category C is the learning layer. User explicitly framed this PRD as "a bit of a detour" before C — necessary plumbing, not a re-prioritization.

## Problem Statement

Today `emit-tree!` is a one-shot terminator. Code-grounded verification at `executor.clj:1474-1592` confirms the dispatch:

```
(cond
  error              → recur with error history
  @final-output      → return :success
  emit-tree! called  → execute Phase 2 + return phase2 result      ; ← EXIT, no recur
  FINAL_ANSWER text  → return :success
  :else              → recur)
```

When the model calls `(emit-tree! ...)`, the loop executes Phase 2 and returns its result directly to the caller. The model:

- Never sees Phase 2's actual outputs (`:summary` or whatever the tree produced)
- Cannot reason about a tree's partial outcomes ("23 of 24 chunks succeeded — should I retry or accept?")
- Cannot run a follow-up tree after seeing the first one's results
- Cannot mix tree execution with subsequent inline reasoning
- Cannot inspect what failed and decide a targeted recovery

The system prompt (`executor.clj:1218-1240`) treats `emit-tree!` like any other tool — alongside `llm`, `code`, `sequence`, etc. It does NOT warn the model that `emit-tree!` is terminal. So the prompt-vs-reality contract is broken: the model thinks it's getting a tool, but it's actually getting an escape hatch.

The result: an RLM that's only "iterative" until the first `emit-tree!`. After that, it's a one-shot. We lose all the reasoning, debugging, and adaptive capabilities the REPL is supposed to enable.

## Solution

Make `emit-tree!` non-terminal. Every tool call returns to the REPL. Only `final!` (or `:max-iterations` exhaustion) terminates the loop. The model has full agency to inspect intermediate results and decide what to do next.

Concretely the loop after Phase 2 will:

1. Execute the tree (existing path — unchanged)
2. **Merge Phase 2's `:writes`-declared outputs into `sandbox-vars`** so the model can call `(get-var :summary)` naturally
3. **Append a lightweight summary entry to `:tree-results`** so the model has a history of tree executions across iterations
4. **Clear `:generated-tree` and `:generated-tree-raw`** so the dispatch doesn't re-fire on the same tree
5. **Recur** to the Phase 1 loop top
6. Next LLM call's prompt includes the new `:tree-results` and merged outputs in the history
7. Model decides: call `(final! ...)` to wrap up, call `(llm ...)` for follow-up reasoning, call `(emit-tree! ...)` for another tree, call drill-down primitives like `(tree-failures)` to inspect, or anything else
8. Only `(final! ...)` or `:max-iterations` exhausted terminates

The behavior is **opt-in via `:rlm {:recursive? true}`** on the repl-researcher node. Existing trees (with `:rlm true` or `:rlm {:debug? true}`) preserve the current terminal behavior so the canonical bench suite doesn't regress. We plan to deprecate the terminal path over time and make recursive the default + only behavior, after all bench tasks have been migrated and verified.

The model gets new drill-down primitives — only wired when `:recursive?` is true — for inspecting tree-execution details on demand: `(tree-detail)`, `(tree-trajectory)`, `(tree-failures)`, `(node-output ...)`, `(node-input-profile ...)`.

## User Stories

### Core recursion (the loop change)

1. As a tree designer (LLM in Phase 1), I want to call `(emit-tree! ...)` to design a complex pipeline, then continue reasoning about the result, so that I can do follow-up work without exiting the REPL.
2. As an ORC consumer using `:rlm {:recursive? true}`, I want my tree's outputs merged directly into the REPL's variable space, so that the model can write `(get-var :summary)` naturally — same as if `:summary` had been produced by a sub-LLM call.
3. As an LLM in Phase 1, I want last-write-wins semantics on sandbox-vars output keys when two trees both write `:summary`, so that the most recent value is naturally accessible — while the older value is preserved in `:tree-results` for historical reference.
4. As an LLM in Phase 1, I want my `:tree-results` history to accumulate across iterations (vector, not single value), so that I can reflect across multiple tree runs in the same session.
5. As an LLM in Phase 1, I want `:generated-tree` to be cleared from sandbox-vars after Phase 2 completes, so that the dispatch doesn't infinitely re-execute the same tree.

### Summary content (trustable, factual, not dramatic)

6. As an LLM in Phase 1, I want to see a `:status` enum on each tree result (`:success`, `:partial`, `:failure`, `:timeout`), so that I can decide whether the tree's output is acceptable for my task.
7. As an LLM in Phase 1, I want factual counts (`:nodes-succeeded`, `:nodes-failed`, `:nodes-total`) on each tree result, so that I can quantify completion without prose interpretation telling me what to feel about it.
8. As an LLM in Phase 1, I want `:failure-indices` and `:failure-reasons` (verbatim from D-008's `:partial-summary`) on each tree-results entry when a tree was partial or failed, so that I know exactly which sub-nodes failed and why.
9. As an LLM in Phase 1, I want elapsed-time stats per tree (`:elapsed-ms`) and total usage (`:usage`), so that I can self-regulate against budget.
10. As an LLM in Phase 1, I want timeout-specific fields (`:phase2-elapsed-ms`, `:budget-remaining-ms`, `:nodes-completed-before-cancel`) on entries with `:status :timeout`, so that I understand exactly how the tree was cut short.

### Prompt framing (descriptive, not prescriptive)

11. As an LLM in Phase 1, I want the prompt to describe `:status` values without editorializing (no "URGENT" or "WARNING"), so that my interpretation depends on my task rather than reacting to alarm signals.
12. As an LLM in Phase 1, I want to be told that `:partial` is sometimes acceptable depending on the task (with concrete examples), so that I can use task-specific judgment instead of blanket "partial = bad."
13. As an LLM in Phase 1, I want to be told that I can call `(emit-tree! ...)` again, `(llm ...)` or `(code ...)` inline, or `(final! {...})` to terminate, so that I understand all my options after a tree completes.

### Drill-down primitives (pull on demand)

14. As an LLM in Phase 1, I want to call `(tree-detail)` / `(tree-detail tick-id)` to get the full structured execution detail for a tree, so that I can drill deeper than the lightweight summary without forcing every iteration to carry the full payload.
15. As an LLM in Phase 1, I want to call `(tree-trajectory)` / `(tree-trajectory tick-id)` to inspect the chronological event log of a tree's execution, so that I can understand the exact sequence of node firings.
16. As an LLM in Phase 1, I want to call `(tree-failures)` to get just the failure list (with errors and input profiles), so that I can target a retry without sifting through successful nodes.
17. As an LLM in Phase 1, I want to call `(node-output node-id)` and `(node-input-profile node-id)` to retrieve specific node outputs and input profiles, so that I can reason about individual sub-steps in a tree.
18. As a tree designer, I want the drill-down primitives to ONLY exist when `:recursive?` is true, so that the simpler non-recursive namespace doesn't get polluted.

### Budget and observability

19. As an ORC consumer, I want `:max-iterations` to keep counting LLM code-gen iterations (not tree executions), and `:timeout-ms` (from D-003) to bound total Phase 1 + cumulative tree wall-time, so that runaway recursive loops can't burn unbounded wall-time while the semantic of "how many times the model thinks" stays clear and unchanged.
20. As an LLM in Phase 1, I want `:max-iterations` exhausted with `:failure :error "Max iterations reached without final!"` if I keep emitting trees and never call `final!`, so that there's a hard ceiling on runaway loops.
21. As an ORC consumer, I want the response to surface `:cumulative-thinking-ms` and `:cumulative-tree-ms` separately, so that I can debug whether time was spent on the model's reasoning or on tree executions.

### Backward compatibility and migration

22. As an ORC consumer with an existing `:rlm true` repl-researcher node, I want my tree to behave exactly as it does today (terminal `emit-tree!`), so that none of my current production code or benchmarks regress.
23. As an ORC consumer adopting the new recursive behavior, I want a clear migration path — toggle `:rlm {:recursive? true}`, update the instruction if needed, re-test — so that I can opt in incrementally rather than all-at-once.
24. As an ORC consumer, I want all the bench tasks (`document-analysis`, `risk-analysis`, `contract-comparison`, etc.) to continue producing identical results with `:rlm true`, so that the canonical generalization story stays intact.
25. As an ORC consumer, I want to be able to convert each bench task to `:rlm {:recursive? true}` in a follow-up slice (not this one) and confirm no regression, so that the eventual deprecation of terminal behavior is gated by real verification.

### Future-facing

26. As a future judge-implementer (Category C), I want each `:tree-results` entry to carry enough metadata (`:tick-id`, `:status`, `:partial-summary`, `:tree-raw` form) to be a scoring input, so that I can subscribe to per-iteration data without forcing additional event-store queries.

## Proposed Slicing

This PRD breaks into **two vertical tracer-bullet slices**. Each is end-to-end testable on its own (including live LLM verification) and leaves the feature branch in a runnable state. Both opt-in via `:rlm {:recursive? true}`.

### Slice R-1: Core recursive loop (no drill-down primitives yet)

End-to-end shippable feature:

- `:rlm {:recursive? true}` opt-in flag (schema additions + command + read model)
- Loop dispatch change: on Phase 2 completion (any status), merge `:writes`-declared outputs into sandbox-vars, append summary entry to `:tree-results`, clear `:generated-tree` / `:generated-tree-raw`, recur to loop top
- Lightweight `:tree-results` summary shape (per Q5) — factual fields only, no editorial
- Prompt update for recursive mode (descriptive framing per Q5, lists `(emit-tree! ...)`, `(llm ...)`, `(code ...)`, `(final! {...})` as the model's options)
- Observability metrics: `:cumulative-thinking-ms` + `:cumulative-tree-ms` tracked + surfaced in response
- Deep modules extracted: `compute-tree-result-summary` + `merge-tree-result-into-sandbox` (pure fns)

Why this is end-to-end shippable WITHOUT drill-down primitives: the summary alone carries enough information (status, failure-indices + reasons, counts, elapsed) for the model to reason about partial outcomes and decide its next move. Drill-down enriches but isn't required for the basic recursion + reasoning loop.

Live verification gate:

- Two-step task (Q8 test #8): tree generates summary → recur → inline `(llm "count entities")` → `final!` with both outputs
- No-regression: existing `:rlm true` benchmarks identical to current main

### Slice R-2: Drill-down primitives

Adds the model's ability to inspect tree-execution details on demand:

- `(tree-detail)` / `(tree-detail tick-id)`
- `(tree-trajectory)` / `(tree-trajectory tick-id)`
- `(tree-failures)`
- `(node-output node-id)`
- `(node-input-profile node-id)`

All read from the existing event store; no new data captured. Gated by `:recursive?` flag.

Live verification gate:

- Failure-recovery task (Q8 test #9): inject sentinel failure → model uses `(tree-failures)` to inspect → reacts (emits follow-up tree OR runs inline OR calls `final!` with what it has)

### Slice ordering rationale

R-1 ships the architectural change and proves the recursion works with real LLM reasoning. R-2 enriches the model's inspection capability. R-1 is sequence-blocking for R-2 (no point adding drill-down if recursion doesn't work).

Both slices are AFK (no architectural decisions remain open). Both follow the project's TDD methodology (one test → one impl, live verification mandatory).

## Implementation Decisions

### Architecture

The work is entirely inside `components/orc-service`. No new event types; we reuse `:sheet/node-execution-completed`, `:sheet/rlm-tree-node-completed`, and `:sheet/rlm-tree-execution-completed` (all already shipping from O02/O03/D-008). No new commands. The change is in the Phase 1 loop's dispatch and in the SCI sandbox bindings.

### Opt-in flag

`:rlm {:recursive? true}` on the repl-researcher node config. The `:rlm` value already accepts `{:debug? true}` shape, so adding `:recursive?` is a non-breaking extension. The flag is documented in the node config schema (Malli).

### Loop dispatch change (gated)

Current dispatch at the "emit-tree! was called" branch returns Phase 2's result directly. The new code path:

```
;; (sketch of decision-rich shape, not literal code)
(case-of [:recursive? flag, phase2-result]
  not-recursive?     → return phase2-result (current behavior, unchanged)
  recursive? success → merge :writes outputs into sandbox-vars,
                       append summary entry to :tree-results,
                       clear :generated-tree + :generated-tree-raw,
                       recur to loop top
  recursive? partial → same merge + append + clear + recur
                       (model sees :partial in summary, decides)
  recursive? failure → same merge (empty outputs) + append + clear + recur
                       (model sees :failure in summary, decides)
  recursive? timeout → same merge (empty outputs) + append + clear + recur
                       (model sees :timeout in summary, decides — may have one chance to call final!))
```

The model sees the outcome regardless of status. The budget gate (D-003's `:timeout-ms`) is the final hard stop — if Phase 1 + cumulative trees exceed the budget, the existing D-003 exhausted-budget path kicks in cleanly.

### Deep modules to extract

**`compute-tree-result-summary`** — pure function. Takes Phase 2 result + tick events (queried from event store), produces the lightweight `:tree-results` entry shape per Q5. Pure data transformation; testable in isolation. Reused by both the main loop integration AND the drill-down primitives that surface a tree's identity.

**`merge-tree-result-into-sandbox`** — pure function. Takes Phase 2 result + current sandbox-vars + the repl-researcher node's `:writes` keys, returns the new sandbox-vars with outputs merged and the summary appended to `:tree-results`. Encapsulates the "what gets merged" rule (only `:writes`-declared keys, not input blackboard keys) and the last-write-wins behavior. Testable in isolation.

The drill-down primitives (`tree-detail`, `tree-trajectory`, `tree-failures`, `node-output`, `node-input-profile`) are wrappers over event-store reads. The logic is small (filter events by tick-id + type + node-id; project to user-facing shape) and can be tested independently of SCI by passing in a fixture context.

### Summary shape (each `:tree-results` entry)

```clojure
{;; Identity
 :tick-id <uuid>
 :tree-raw [:sequence [...]]              ; the tree the model designed

 ;; Outcome — raw enum, no editorial
 :status :success | :partial | :failure | :timeout
 :elapsed-ms 4523

 ;; What's now available
 :outputs-keys [:summary :chunk-summaries]

 ;; Factual counts
 :nodes-succeeded 22
 :nodes-failed 2
 :nodes-total 24

 ;; Failure detail (only when :partial or :failure) — verbatim from D-008's :partial-summary
 :failure-indices [7 17]
 :failure-reasons {7 "Rate limit exhausted"
                   17 "Schema validation failed"}

 ;; Timeout detail (only when :status :timeout) — from D-003
 :phase2-elapsed-ms 4500
 :budget-remaining-ms 0
 :nodes-completed-before-cancel 4

 ;; Cost awareness
 :usage {:prompt-tokens N :completion-tokens N :total-tokens N}}
```

Explicitly **NOT** in the summary:

- No prose interpretation (no `:summary-message "Tree partially completed"`)
- No severity scoring (no `:severity :high` or `:reliability 0.92`)
- No "retry hint" (no `:recommended-action :retry-failures`)
- No full trajectory inline (available via `(tree-trajectory)`)
- No per-node usage breakdown inline (available via `(tree-detail)`)
- No raw `:event/*` event-store keys

### New drill-down primitives (only when `:recursive?` true)

```
(tree-detail)             ; full structured info for most recent tree
(tree-detail tick-id)     ; for a specific past tree

(tree-trajectory)         ; chronological per-event log of most recent tree
(tree-trajectory tick-id) ; for a specific past tree

(tree-failures)           ; failure entries for most recent tree (errors + input profiles)

(node-output node-id)     ; outputs from a specific node in the most recent tree
(node-input-profile node-id) ; input profile from O03 for that node
```

All read from the existing event store. No new data captured.

### Prompt framing additions (descriptive, not prescriptive)

When `:recursive?` is true, the system prompt adds a section describing the loop semantics. Wording is:

- "After `(emit-tree! ...)` runs, the tree's outputs are merged into your variables (use `(get-var :summary)` etc.)."
- "A summary entry is appended to `:tree-results`."
- `:status` enum description (raw, no editorial)
- "Interpretation depends on your task. For some tasks `:partial` is acceptable; for others it requires follow-up. You decide."
- List of drill-down primitives
- "You can call `(emit-tree! ...)` again, run `(llm ...)` or `(code ...)`, or call `(final! {...})` to terminate."

Critically: no urgent/warning/alarm language. The summary is factual; the model's reaction is task-dependent.

### Iteration budget (no changes to safety surface)

`:max-iterations` continues to count LLM code-gen iterations only. A tree execution doesn't increment the iteration counter — only the next code-gen call does. The wall-time gate is D-003's `:timeout-ms`, already shared across Phase 1 + cumulative tree executions. The model self-regulates by reading `:elapsed-ms` + `:usage` in `:tree-results`.

Observability addition: track `:cumulative-thinking-ms` (sum of Phase 1 code-gen wall-time) + `:cumulative-tree-ms` (sum of tree-execution wall-time) separately and surface both in the final response. This is read-only debugging; it doesn't change the safety model.

### What does NOT change

- No new event types
- No new commands
- No new ORC executor logic outside the recursive loop
- No changes to non-recursive callers (`:rlm true` or `:rlm {:debug? true}` keep current behavior)
- No changes to the bench infrastructure or canonical bench tasks
- No deprecation of `emit-tree!` terminal behavior in this PRD (deprecation is a follow-up slice)

## Testing Decisions

### What makes a good test for this work

Tests target the external contract — what the model sees in sandbox-vars after a tree, what shape the summary takes, what the drill-down primitives return, what the loop does on recur. NOT internal control flow inside the dispatch cond.

A repeated user constraint: **mocks are dev-only iteration aids; live real-LLM runs are mandatory before declaring done.** Production-truth, not just theory. Saved as feedback memory [[verification-live-runs]].

A second repeated constraint: **trace bugs to root cause; no fallback logic.** Saved as feedback memory [[debug-to-root-cause]]. If a test fails or a live run produces unexpected output, dump the events tagged with the tick-id and trace the actual sequence; don't paper over with workarounds.

### Test plan

**Unit tests for the deep modules:**

- `compute-tree-result-summary` — fixture inputs (a Phase 2 result + a vector of tick events), assert the returned summary has the right shape for every status (`:success`, `:partial`, `:failure`, `:timeout`). Covers the optional failure/timeout fields conditionally appearing.
- `merge-tree-result-into-sandbox` — assert input blackboard keys NOT merged back; only `:writes` keys; `:tree-results` appended (not replaced); `:generated-tree` cleared; last-write-wins on key collisions across two sequential merges.

**Integration tests (mock predict, real event store, real SCI):**

1. Recur happens after Phase 2: mock 1st predict returns `(emit-tree! ...)`; mock 2nd asserts prompt contains `:tree-results`; mock 2nd returns `(final! {...})`. Verify final outputs reach the caller.
2. `:tree-results` accumulates: mock 3 sequential `emit-tree!` then `final!`. Verify the vector has 3 entries in order.
3. Outputs merge: tree writes `:summary`; next iteration's `(get-var :summary)` returns the tree's value.
4. Drill-down primitives: mock a tree run; from the next iteration's code, call `(tree-detail)`, `(tree-trajectory)`, `(tree-failures)`, `(node-output ...)`. Verify each returns the expected shape from the event store.
5. `:generated-tree` cleared post-Phase-2: assert after recur it's no longer in sandbox-vars.
6. `:max-iterations` exhausts: mock predict to keep emitting trees forever; verify the loop ends with `:status :failure :error "Max iterations reached without final!"` after N iterations.
7. `:recursive?` flag gates everything: with `:rlm true` (no `:recursive?`), behavior is identical to current main. Same fixture as the existing `emit-tree-triggers-phase2-execution` test should still pass.

**Live LLM tests (MANDATORY):**

8. **Two-step task requiring reasoning between trees.** Task: "Summarize a document, then count distinct entities, return both `:summary` and `:entity-count`." The model must emit a tree, see `:summary` after recur, run `(llm "count entities" ...)` inline, then call `(final! {:summary ... :entity-count ...})`. Proves recursion as designed: model uses both tree and inline LLM in sequence, terminates with `final!`.
9. **Adaptive task with failure recovery.** Inject a sentinel failure (D-008 style — a chunk that throws via a `:code` sibling), confirm the model:
   - Sees `:partial` in `:tree-results`
   - Reacts: emits a follow-up tree, runs `(llm ...)` to handle the failures inline, OR calls `(final! ...)` with what it has
   - Any of the three reactions is acceptable; the test proves the model has the *option*, not a specific recovery shape
10. **Simplest possible live: termination.** Real LLM, real tree, model gets result, calls `final!`. "Does the loop terminate?" The smoke test before anything else.

**No-regression gate:**

11. Re-run all 5 canonical bench tasks with `:rlm true` (terminal path). Results identical to current main. This is the safety net that the gating works.

**Deferred (separate follow-up slice):**

- Convert all 5 canonical bench tasks to `:rlm {:recursive? true}`, run them, verify outputs are still correct (may require per-task prompt tuning). This is the long-term deprecation gate — only after this is green do we consider flipping the default.

### Prior art for the tests

- `rlm_tree_executor_test.clj` — same fixture pattern with mock DSCloj provider, in-memory event store, real Grain processors. The D-003 integration tests follow this pattern; the new recursive tests fit cleanly alongside.
- `map_each_node_test.clj` — pattern for integration tests with real `sheet/execute` and a `:code` executor for the failure-injection sentinel.
- `d008_live_verify.clj` and `d003_live_verify.clj` — pattern for standalone live verify scripts that drive the new feature with a real LLM. The new `recursive_rlm_live_verify.clj` will follow the same pattern.

## Out of Scope

- **Removing the terminal `emit-tree!` path.** The deprecation plan exists but is a follow-up slice gated by all bench tasks being migrated to `:recursive?` and verified.
- **Tree-in-tree recursion** (a Phase 2 tree containing nested repl-researcher nodes). Tangential — the repl-researcher node type already supports being a child of a tree, but the focus here is REPL-level recursion at Phase 1.
- **Auto-retry of failed tree executions.** The model decides via reasoning (looking at `:partial-summary` + `:failure-indices`) whether to retry. The executor does not retry trees automatically.
- **Judge implementation** (Category C). The `:tree-results` accumulating vector is built here as the data layer that future judges will consume; the judges themselves are a separate PRD.
- **Pattern matching across `:tree-results` history** (Category C — ontology integration). Separate work.
- **Configuration knobs beyond `:recursive? true`.** E.g., `:max-trees` (we explicitly punted on this in Q6), or a `:partial-policy` for what to do on a `:partial` status (we leave this to the model's task-specific judgment per Q5).
- **Cross-tick tree-results history.** The `:tree-results` vector is per-RLM-session; it resets between top-level sheet/execute calls. Cross-session learning is Category C's ontology layer.

## Further Notes

### Why "summary trustability" was the critical user constraint

User's exact words: *"we just need to make sure the summary is trustable to paint the right picture of the run and does not cause something that would force the model into some dramatic action."*

Every decision around the summary content (Q5) reflects this:

- Raw enums (`:success` / `:partial` / `:failure` / `:timeout`) — no rewording
- Factual counts (`:nodes-succeeded 22 :nodes-failed 2`) — no editorialization
- Verbatim failure reasons (`{7 "Rate limit exhausted"}`) — model reads the actual error
- No "concerning" or "issue" or "needs attention" language anywhere in the summary or the prompt
- Prompt explicitly says "interpretation depends on your task" — model uses task semantics, not the summary's emotional framing

The alternative — a summary that prescribes (e.g., `:retry-recommended true`) — would force the model into reactions it shouldn't make on its own. The model knows its task; the executor doesn't. Keep them separate.

### Branch / commit conventions

- PRD stays local on `feature/core-orc-upgrades` (untracked, like the prior PRDs).
- Issues will be saved under `docs/issues/recursive-rlm/*.md`, also local-only.
- Each slice commits to `feature/core-orc-upgrades`; when verified live, port the framework changes to `main` via the same selective-checkout pattern used for O01+O02+O03, D-008, D-003.
- Commit messages match the project style.

### Risk register

- **R1 — Model doesn't realize it needs to call `final!` after a tree.** The prompt change should prevent this, but if the model's instinct is "I emitted a tree, I'm done," `:max-iterations` will exhaust. Mitigation: live verification gate 8 (the two-step task) catches this directly.
- **R2 — `:tree-results` vector grows unbounded in long-running sessions.** Mitigation: `:max-iterations` is the hard cap on iterations, and each iteration adds at most one entry. With default 10, the vector has at most 10 entries. Vector size is bounded by the existing safety surface.
- **R3 — Drill-down primitives return event-store data that's out of date.** Events are read after the tick completes, so they reflect the final state. If a tree is still running (shouldn't happen — `tree-executor/execute-tree` blocks until completion), drill-down would see partial state. Mitigation: trees are always synchronous from the RLM's perspective; if this ever changes (e.g., async trees), drill-down would need a "tree-complete?" check.
- **R4 — Model loops emit-tree → final! → emit-tree → final! by mistake.** Once `final!` is called, the loop terminates. There's no way for the model to "call final! then continue." Mitigation: this isn't actually a risk — `final!` is documented as terminal, and the loop guards it.
- **R5 — Token cost increase per session.** Recursive RLM sessions emit N trees and accumulate N summaries (~500 tokens each) in the prompt. With `:max-iterations 10` this is bounded to ~5K extra prompt tokens worst case. Significant but bounded. Mitigation: summary content is deliberately lightweight (per Q5); drill-down primitives are pull-only so the model pays full-trajectory cost ONLY when it explicitly asks. Compare to the alternative (one-shot trees that fail without recovery) — the recursion enables the model to AVOID rerunning expensive trees by reasoning over the summary first.
- **R6 — Drill-down primitive misuse bloating context.** If the model calls `(tree-trajectory)` it could receive a multi-KB structured response that lands in the next iteration's history. Mitigation: the prompt should describe drill-down primitives as "use only when summary is insufficient." A future enhancement could cap response size or paginate; out of scope for slice R-2.
- **R7 — Schema validation for `:recursive?` boolean field.** The `:rlm` field in the repl-researcher node schema currently accepts `[:or :boolean :map]`. Adding `:recursive?` as a map field requires no schema change since `:rlm {...}` already accepts arbitrary maps. But the `:sheet/set-repl-researcher-config` command and `:sheet/repl-researcher-config-set` event need a corresponding field accept-and-project path. Mitigation: confirmed in implementation surface — covered in Slice R-1.

### Open questions deferred to implementation

- **Exact wording of the recursive-mode prompt section.** Per Q5 we have the structure; the prose will be refined in implementation.
- **Whether `(tree-detail)` returns nested structured data or pretty-formatted string.** Probably nested map (model can navigate); string formatting is a presentation concern.
- **Whether `tree-results` history is trimmed to the last N entries on very long sessions.** Defer — current iteration cap (10) makes this irrelevant.
