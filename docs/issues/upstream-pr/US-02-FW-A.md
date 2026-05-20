# US-FW-A — PR-Framework commit 1: RLM correctness fixes (U4 + U5 + U6 + U7)

## Parent

`docs/prd/upstream-pr-plan.md` — Step 2 of the execution sequence, first of three PR-Framework commits.

## What to build

Create branch `feature/rlm-framework-upgrades` off latest `origin/main`. Apply the first PR-Framework commit:

**Commit:** `fix(orc-service): RLM correctness fixes (U4 + U5 + U6 + U7)`

Contents:
- **U4** — `extract-all-keys` in `rlm_tree_executor.clj` becomes `:fn`-position-independent. Replace the `take-while` filter with a uniform `(apply hash-map (rest tree))` that correctly extracts `:reads` and `:writes` regardless of where `:fn` appears in the args list.
- **U5** — `execute-llm-primitive` in `rlm_sandbox.clj` propagates `:field-type` from the blackboard schema to the dscloj module's input field. Image-typed reads route as multimodal `image_url` content blocks instead of inline base64 text.
- **U6** — `execute-tree` in `rlm_tree_executor.clj` accepts a new `:blackboard-schemas` option. `execute-repl-researcher-rlm` in `executor.clj` passes the parent's blackboard schemas through to the child sheet so `:field-type :image` (and other field-types) survive the parent→child handoff.
- **U7** — `execute-code` in `executor.clj` reconciles the function's return value against declared `:writes`: map-with-matching-keys → `select-keys`; non-map with single write → wrap; multi-write mismatch → clear failure error instead of silent retry storm.

Two new unit tests (one for U4, one for U7) demonstrate the behavior with pure-data fixtures. No live LLM dependency. Tests live in `rlm_dsl_test.clj` alongside the existing PR02/U2 + PR-Pre03/U5 + U11 tests, OR in a new `rlm_tree_executor_test.clj` and `executor_test.clj` if structurally cleaner.

This is a pure bug-fix commit — no behavior change for working consumers. Existing 5-benchmark suite continues to pass.

## Acceptance criteria

- [ ] Branch `feature/rlm-framework-upgrades` exists off `origin/main`'s HEAD.
- [ ] One commit on the branch with message starting `fix(orc-service): RLM correctness fixes (U4 + U5 + U6 + U7)` and Co-Authored-By trailer.
- [ ] `extract-all-keys` test (`extract-all-keys-handles-fn-in-any-position` or equivalent) GREEN: synthetic tree with `:fn` first AND `:fn` last yield identical key sets.
- [ ] `execute-code` reconciliation test (`execute-code-reconciles-non-map-results` or equivalent) GREEN: covers single-write non-map auto-wrap, map with overlapping keys, and the failure mode (multi-write + mismatched keys) returning `:status :failure` with a clear error message.
- [ ] No regression on `rlm-mode-test` (45 / 138 GREEN).
- [ ] No regression on `rlm-dsl-test` (27 / 109 GREEN + new tests).
- [ ] No regression on the existing 5-benchmark generalization suite (sanity-check at least one task end-to-end).
- [ ] Commit is mergeable and bisectable (revert leaves a working tree).

## Blocked by

- US-Sync (must merge main first so the branch is built off the correct baseline)
