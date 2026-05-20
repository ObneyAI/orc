# US-Recomb — Final re-comb of doc claims against live behavior

## Parent

`docs/prd/upstream-pr-plan.md` — Step 4 (final).

## What to build

Final pre-submission verification pass to catch any drift between documentation claims and observed live behavior.

End-to-end behavior:
1. Fresh checkout of `feature/rlm-framework-upgrades` into a temporary worktree.
2. Live re-run of `image_analysis` benchmark via the PR-Bench runner (which lives on `feature/predict-rlm-comparison-bench`, so cross-branch verification).
3. Live re-run of `document_redaction` benchmark.
4. For EACH claim in `docs/RLM-GUIDE.md` (added in PR-Framework), verify the live behavior matches.
5. For EACH claim in `development/bench/predict-rlm-comparison/reports/01_image_analysis.md` (e.g. "ORC 2.8× cheaper", "22 of 24 letters match predict-rlm exactly"), verify the live run produces the same outcomes within expected variance.
6. For EACH claim in `development/bench/predict-rlm-comparison/reports/02_document_redaction.md` (e.g. "92 redactions", "100% strict recall", "28.9s"), verify.
7. For EACH command-line in `development/bench/predict-rlm-comparison/README.md`, run it from a clean shell and confirm it works as advertised.
8. If ANY claim drifts from observed behavior, fix the doc (or the code) and push the correction to the appropriate branch BEFORE the PRs are reviewed.

This slice is HITL because it's the human-eyeball final verification checkpoint. The user (or whoever does final review) decides whether each claim is sufficiently grounded.

## Acceptance criteria

- [ ] image_analysis fresh-checkout run produces `:status :success` and letter-extraction within 2-3 letters of the 1,345 cited in the headline report.
- [ ] document_redaction fresh-checkout run produces `:status :success`, total-redactions in the 90-95 range, strict-PII recall verifiably ≥85% against the manual ground-truth inventory.
- [ ] Every command in the bench README runs successfully from a fresh shell.
- [ ] Every numerical claim in the clean reports matches observed behavior (within model run-to-run variance — ±5% typical).
- [ ] Every code example in `docs/RLM-GUIDE.md` compiles / matches the behavior described.
- [ ] Any doc drift identified is fixed and pushed to the appropriate PR branch.
- [ ] Final sign-off: user reviews and approves the verification report.

## Blocked by

- US-Bench-Open (both PRs must be open before final re-comb, since re-comb spans both)
