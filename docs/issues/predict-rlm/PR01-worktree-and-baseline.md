# PR01 — Worktree from main + Phase 0 baseline re-run

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

Set up a fresh worktree of the ORC repository at a sibling path checked out from `main`, on a new branch `feature/predict-rlm-benchmarks`. From that worktree, re-run the existing `contract_comparison_validated` benchmark via the existing `development/bench/runner.clj` to confirm the bench infrastructure works on a clean `main` baseline. Required because the bench infrastructure and core RLM mode plumbing already live on `main` (per `036d6b9 feat(orc-service): Phase 2 observability layer (O01 + O02 + O03)`), and we want to validate before any new work touches it.

This slice introduces no new files in the repo itself — it produces a new EDN result and a baseline confirmation.

## Acceptance criteria

- [ ] Worktree exists at `../orc-predict-rlm` on branch `feature/predict-rlm-benchmarks`
- [ ] `clj -M:dev -m nrepl.cmdline --port 7888` starts cleanly from the worktree
- [ ] From REPL: `(require '[runner])`, then `(runner/start!)`, then `(runner/run! contract-comparison-validated/task)` completes with `:status :success`
- [ ] A new EDN file appears in `development/bench/generalization-results/` with timestamp matching the run
- [ ] `:generated-tree-raw` in the new EDN is non-empty and belongs to the same family as `..._2026-05-19_151058.edn` (sequence containing per-doc surveys + section-diff synthesis stages)
- [ ] `(runner/stop!)` cleans up without errors
- [ ] No core ORC files modified during this slice

## Blocked by

None — can start immediately.
