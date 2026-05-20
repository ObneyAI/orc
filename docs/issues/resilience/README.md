# Category D — Resilience: Issue Breakdown

**Parent PRD:** [`docs/prd/category-d-resilience.md`](../../prd/category-d-resilience.md) (local, on feature branch)

**Status:** Local issue tracking — these files will NOT be committed to main. They are reference documentation for work happening on `feature/core-orc-upgrades`.

## Issue List

| # | Title | Type | Blocked by |
|---|-------|------|------------|
| D-008 | [Map-each partial results](D-008-map-each-partial-results.md) | AFK | None |
| D-003 | [Phase 2 budget-aware timeout with cancellation](D-003-phase2-budget-cancellation.md) | AFK | D-008 |

## Dependency Graph

```
D-008 ─→ D-003
(partial   (timeout +
 results)   cancellation)
```

## Sequence Rules

- Each slice is end-to-end testable on its own and leaves the feature branch in a runnable state
- Each slice ends with a **live real run** (not just mocks) confirming the feature behaves as expected in production — per the project's verification rule
- All event emissions flow through proper Grain commands (commands emit events; processors react). No direct event-store writes.
- Framework tests must remain green: 73 tests / 277 assertions / 0 failures on the RLM-related namespaces (the 12 pre-existing failures in `repl_researcher_test.clj` are unrelated and out of scope).
- After both slices verify live, framework changes get ported to `main` via selective-checkout (same pattern used for O01+O02+O03).

## Why this order

**D-008 first** because:
- Higher real-world urgency — the canonical risk-analysis run has 4.1× per-chunk variance, so one bad chunk can silently corrupt a 24-chunk run today. Unblocks the immediate production-relevance problem.
- Lays cleaner data layer for the future Category C judges (which will subscribe to `:partial` events).
- No dependency on D-003.

**D-003 second** because:
- Smaller scope and lower urgency — current 900s hardcode isn't biting in canonical runs (max 126s).
- The timeout cancellation verifies that D-008's `:partial` semantics behave correctly under mid-execution cancellation (i.e., a map-each cancelled mid-flight should still emit `:partial` cleanly).
- Cancellation drain (~500ms) relies on the bookend event from O03 firing — already shipped, but D-003's live verification is the first test that exercises bookend-on-cancellation.

## Out of Scope (deferred to future PRDs)

- Map-each-level retry layered on per-child retry
- Partial-outputs-on-timeout response shape
- Phase 1 iteration-level budget check
- All of Category C (judges, fingerprints, ontology pattern matching) — see PRD's C-prerequisite-hooks section for handoff details
- All of Category E (conversational debugging, skills→trees, personality)
