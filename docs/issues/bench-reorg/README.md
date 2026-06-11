# Bench Reorganization — Issue Breakdown

**Parent PRD:** `docs/prd/bench-reorganization.md` (local-only, uncommitted)

**Status:** Local issue tracking — these files will NOT be committed to main.

## Issue List

| # | Title | Type | Blocked by |
|---|-------|------|------------|
| B01 | [Extract runner.clj (compatibility-preserving)](B01-extract-runner.md) | AFK | None |
| B02 | [Extract 5 per-task definition files](B02-extract-task-files.md) | AFK | B01 |
| B03 | [Add all.clj aggregator](B03-add-all-aggregator.md) | AFK | B02 |
| B04 | [Migrate docs, delete old files, full smoke-test](B04-cutover-and-cleanup.md) | AFK | B03 |

## Dependency Graph

```
B01 ─→ B02 ─→ B03 ─→ B04
(runner) (task files) (aggregator) (cutover)
```

## Sequence Rules

- Each slice ends with main in a runnable state
- Each slice runs the relevant benchmark tests before commit and verifies results match expectations (no hallucinations, expected token counts, expected durations)
- B01-B03 are additive (old API still works in parallel)
- B04 is the cutover that removes the old API

## Verification Standards (apply to all slices)

Before any commit:
- Framework tests pass: `rlm-tree-executor-test`, `rlm-dsl-test` (24 tests, 95 assertions)
- At least G04 + G07 smoke-tested end-to-end
- Output spot-checked against source documents (zero hallucinations)
