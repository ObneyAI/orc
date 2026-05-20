# R-1: Core recursive loop (recursive `emit-tree!` opt-in)

## Parent

PRD: `docs/prd/rlm-recursive-emit-tree.md` (local, on feature branch)

## What to build

Make `emit-tree!` non-terminal so the RLM is genuinely recursive. Once a Phase 2 tree completes, its `:writes`-declared outputs merge into sandbox-vars (the model can call `(get-var :summary)` naturally) and a lightweight summary entry appends to a `:tree-results` history vector. Control returns to the Phase 1 loop, the model gets a new iteration to reason about the result, and the loop ends only when the model calls `(final! {...})` (or `:max-iterations` is exhausted).

This is the architectural foundation for true REPL recursion. Drill-down primitives are a follow-up (R-2); R-1 ships with the summary alone, which is sufficient for the model to reason about partial outcomes and decide its next move.

The behavior is **opt-in via `:rlm {:recursive? true}`** on the repl-researcher node config. Existing trees (with `:rlm true` or `:rlm {:debug? true}`) preserve the current terminal `emit-tree!` behavior so the canonical bench suite doesn't regress.

### Summary shape (each entry in `:tree-results`)

The summary is FACTUAL ONLY — no prose, no severity scoring, no retry hints. The model interprets for its own task; the executor doesn't editorialize.

```clojure
{;; Identity
 :tick-id <uuid>
 :tree-raw [:sequence [...]]              ; the tree the model designed

 ;; Outcome — raw enum, no editorial
 :status :success | :partial | :failure | :timeout
 :elapsed-ms 4523

 ;; What's now available in sandbox-vars
 :outputs-keys [:summary :chunk-summaries]

 ;; Factual counts
 :nodes-succeeded 22
 :nodes-failed 2
 :nodes-total 24

 ;; Failure detail (only when :partial or :failure) — verbatim from D-008's :partial-summary
 :failure-indices [7 17]
 :failure-reasons {7 "Rate limit exhausted" 17 "Schema validation failed"}

 ;; Timeout detail (only when :status :timeout) — from D-003
 :phase2-elapsed-ms 4500
 :budget-remaining-ms 0
 :nodes-completed-before-cancel 4

 ;; Cost awareness
 :usage {:prompt-tokens N :completion-tokens N :total-tokens N}}
```

### Prompt framing (descriptive, not prescriptive)

The recursive-mode prompt section must:
- Describe `:status` values without editorializing (no "URGENT" or "WARNING")
- Tell the model "interpretation depends on your task" (e.g., for some tasks `:partial` is acceptable; for others it requires follow-up)
- List the model's options after a tree completes: call `(emit-tree! ...)` again, run `(llm ...)` or `(code ...)` inline, or call `(final! {...})` to terminate
- NOT prescribe a specific reaction to any status

### Deep modules to extract

- `compute-tree-result-summary` (pure fn) — takes Phase 2 result + tick events (queried from event store), produces the lightweight `:tree-results` entry. Pure data transformation; testable in isolation.
- `merge-tree-result-into-sandbox` (pure fn) — takes Phase 2 result + current sandbox-vars + the repl-researcher node's `:writes` keys; returns new sandbox-vars with outputs merged (only `:writes`-declared keys, NOT input blackboard keys), `:tree-results` appended, `:generated-tree` and `:generated-tree-raw` cleared.

### What does NOT change

- Existing `:rlm true` / `:rlm {:debug? true}` callers — behavior identical to current main (terminal `emit-tree!`)
- Bench infrastructure or canonical bench tasks
- Event types / commands / read models (everything reuses existing schemas)
- `:max-iterations` semantics — still counts code-gen iterations only
- `:timeout-ms` budget machinery (D-003 unchanged)

## Acceptance criteria

### Opt-in flag

- [ ] `:rlm {:recursive? true}` flag accepted on the repl-researcher node config
- [ ] `:sheet/set-repl-researcher-config` command schema accepts the flag (already accepts `{:debug?}` map shape, so this is an additive extension to the `:rlm` map's documented keys)
- [ ] `:sheet/repl-researcher-config-set` event projects the flag onto node state via the read model
- [ ] When `:recursive?` is absent or false, behavior is UNCHANGED from current main (terminal `emit-tree!`)

### Recursion path

- [ ] When `:recursive?` is true and `(emit-tree! ...)` fires, the loop recurs after Phase 2 (no longer terminates)
- [ ] After Phase 2 completes (any status — `:success`, `:partial`, `:failure`, `:timeout`), sandbox-vars contains the tree's `:writes`-declared outputs (e.g., `:summary` accessible via `(get-var :summary)`)
- [ ] Input blackboard keys (e.g., the full `:document` text) are NOT re-merged back into sandbox-vars after Phase 2
- [ ] `:generated-tree` and `:generated-tree-raw` are cleared from sandbox-vars after Phase 2 so the dispatch doesn't re-fire on the same tree
- [ ] `:tree-results` vector accumulates one entry per tree execution, ordered chronologically across iterations
- [ ] On naming collisions in sandbox-vars (two trees both write `:summary`), last-write-wins. The older value is preserved in `:tree-results` (older entries don't get overwritten).

### Summary content

- [ ] Each `:tree-results` entry contains `:tick-id`, `:tree-raw`, `:status` (raw enum), `:elapsed-ms`, `:outputs-keys`, `:nodes-succeeded`, `:nodes-failed`, `:nodes-total`, `:usage`
- [ ] Failure detail (`:failure-indices`, `:failure-reasons`) appears ONLY when status is `:partial` or `:failure` — verbatim from D-008's `:partial-summary`
- [ ] Timeout detail (`:phase2-elapsed-ms`, `:budget-remaining-ms`, `:nodes-completed-before-cancel`) appears ONLY when status is `:timeout` — from D-003's response shape
- [ ] Summary contains NO prose interpretation, NO severity scoring, NO retry hints, NO full trajectory inline, NO raw `:event/*` keys

### Prompt update

- [ ] System prompt has a recursive-mode section (only included when `:recursive?` is true) describing:
  - `:tree-results` history and how to read it via `(list-vars)` / `(get-var :tree-results)`
  - `:status` enum description (raw, no editorial)
  - "Interpretation depends on your task" framing with concrete examples
  - Available follow-up actions: `(emit-tree! ...)`, `(llm ...)`, `(code ...)`, `(final! {...})`
- [ ] No urgent/warning/alarm language anywhere in the recursive-mode prompt

### Observability

- [ ] Response carries `:cumulative-thinking-ms` (sum of Phase 1 code-gen wall-time) + `:cumulative-tree-ms` (sum of tree-execution wall-time) separately
- [ ] These fields are present on every response path (success, failure, timeout, exhausted) in recursive mode

### Safety surface

- [ ] `:max-iterations` continues to count code-gen iterations only (trees are "free" from the counter)
- [ ] `:max-iterations` exhaustion still yields `{:status :failure :error "Max iterations reached without final!"}` if the model keeps emitting trees and never calls `final!`
- [ ] D-003's `:timeout-ms` budget machinery still bounds total wall-time; cumulative tree execution + Phase 1 thinking shares the budget

### Tests (mock executor — for dev iteration)

- [ ] Deep-module unit tests for `compute-tree-result-summary` covering every status case (`:success`, `:partial`, `:failure`, `:timeout`) and the conditional appearance of failure/timeout-specific fields
- [ ] Deep-module unit tests for `merge-tree-result-into-sandbox` covering: input blackboard keys NOT merged back, only `:writes` keys merged, `:tree-results` appended (not replaced), `:generated-tree` cleared, last-write-wins across two sequential merges
- [ ] Integration test: recur happens after Phase 2 — mock 1st predict returns `(emit-tree! ...)`, mock 2nd asserts the prompt contains `:tree-results`, mock 2nd returns `(final! {...})`, verify final outputs reach the caller
- [ ] Integration test: `:tree-results` accumulates — mock 3 sequential `emit-tree!` then `final!`, verify the vector has 3 entries in order
- [ ] Integration test: outputs merge — tree writes `:summary`, next iteration's `(get-var :summary)` returns the tree's value
- [ ] Integration test: `:generated-tree` cleared after recur (assert it's absent in sandbox-vars on the iteration after Phase 2)
- [ ] Integration test: `:max-iterations` exhausts gracefully — mock predict keeps emitting trees, verify the loop ends with `:failure :error "Max iterations reached without final!"` after N iterations
- [ ] Integration test: `:recursive?` flag gating — with `:rlm true` (no `:recursive?`), behavior identical to current main (the existing `emit-tree-triggers-phase2-execution` test passes unchanged)

### Live verification (MANDATORY — per project rule "mocks are dev-only, live runs are mandatory before done")

- [ ] **Two-step task with real LLM.** Task: "Summarize the document, then count distinct entities, return both `:summary` and `:entity-count`." The model must:
  - Emit a tree to summarize/extract
  - Read `:summary` from `(get-var ...)` after recur
  - Run `(llm "count entities" ...)` inline OR design a follow-up tree
  - Call `(final! {:summary ... :entity-count ...})`
- [ ] Live run with `:rlm {:recursive? true}` produces final outputs with BOTH keys
- [ ] Live run shows multiple iterations of LLM code-gen (at least 2 — one for emit-tree, one for follow-up + final)
- [ ] `:tree-results` in the saved EDN result reflects the tree execution(s)
- [ ] `:cumulative-thinking-ms` + `:cumulative-tree-ms` populated separately

### No-regression on canonical bench

- [ ] All 5 canonical bench tasks (`document-analysis`, `risk-analysis`, `contract-comparison`, `contract-comparison-validated`, `legal-issue-detection`) run unmodified with `:rlm true` (terminal path) and produce results identical to current main
- [ ] Framework test suite still green: 142 tests / 507 assertions / 0 failures across map-each, classifier, RLM, parallel, e2e, async, dsl, runtime, versioning, judges, retry, etc.

### Single commit on feature branch

- [ ] One coherent feat commit on `feature/core-orc-upgrades` describing the schema additions, deep modules, dispatch change, prompt update, observability metrics — plus live verification numbers (real LLM calls made, iterations used, final output shape) and a no-regression statement from the canonical bench.

## Blocked by

None — can start immediately. Builds on the already-shipped O02/O03 (per-node events + bookend with trajectory + input-profile) and D-008/D-003 (`:partial-summary` + budget machinery) — all on `main`.

## User stories covered

PRD user stories: 1–5 (core recursion), 6–10 (summary content), 11–13 (prompt framing), 19–21 (budget + observability), 22–24 (back-compat + migration), 26 (judge-implementer prerequisite).
