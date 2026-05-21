# US-Recomb — Final re-comb of doc claims against live behavior

## Parent

`docs/prd/upstream-pr-plan.md` — Step 4 (final).

## What to build

<<<<<<< Updated upstream
Final pre-submission verification pass to catch any drift between documentation claims and observed live behavior.
=======
Final pre-submission verification pass to catch (a) drift between documentation claims and observed live behavior AND (b) coverage gaps where significant capabilities are undocumented or under-documented.
>>>>>>> Stashed changes

End-to-end behavior:
1. Fresh checkout of `feature/rlm-framework-upgrades` into a temporary worktree.
2. Live re-run of `image_analysis` benchmark via the PR-Bench runner (which lives on `feature/predict-rlm-comparison-bench`, so cross-branch verification).
3. Live re-run of `document_redaction` benchmark.
4. For EACH claim in `docs/RLM-GUIDE.md` (added in PR-Framework), verify the live behavior matches.
5. For EACH claim in `development/bench/predict-rlm-comparison/reports/01_image_analysis.md` (e.g. "ORC 2.8× cheaper", "22 of 24 letters match predict-rlm exactly"), verify the live run produces the same outcomes within expected variance.
6. For EACH claim in `development/bench/predict-rlm-comparison/reports/02_document_redaction.md` (e.g. "92 redactions", "100% strict recall", "28.9s"), verify.
7. For EACH command-line in `development/bench/predict-rlm-comparison/README.md`, run it from a clean shell and confirm it works as advertised.
8. If ANY claim drifts from observed behavior, fix the doc (or the code) and push the correction to the appropriate branch BEFORE the PRs are reviewed.

<<<<<<< Updated upstream
This slice is HITL because it's the human-eyeball final verification checkpoint. The user (or whoever does final review) decides whether each claim is sufficiently grounded.
=======
### Doc-coverage audit (added after US-FW-Open feedback)

In addition to verifying existing claims, audit `docs/RLM-GUIDE.md` for known coverage gaps that surfaced during US-FW-Open review. Each item below should land as a new sub-section or expanded explanation:

- **Single "all `:rlm` config options" reference table** — currently `:recursive?`, `:debug?`, `:available-code-nodes`, `:sub-model` are scattered across separate sections. Need one canonical table at the start of the configuration discussion.
- **`:timeout-ms` semantics** — both at the `:repl-researcher` node level AND as the Phase-2 budget control (D-003 budget machinery). When the node sets it, how does it interact with the parent tick's `:timeout-ms`? Document the precedence rules.
- **`:max-iterations` behavior at the limit** — currently shown in example without explanation. What happens when reached: terminate? Return partial? Status `:failure`? Document the actual behavior.
- **Per-`:llm`-node `:model` override** — mentioned in the Phase-2 tree DSL table cell, but should have its own example showing "use a vision-specific model for one node while sub-model handles the rest" (e.g. `[:llm {:model "openai/gpt-4o" ...}]` overrides both main and sub-model).
- **Per-`:llm`-node `:retry` config** — default is `{:max-attempts 3 :backoff-ms [1000 2000 4000]}` per `rlm_dsl.clj`; nowhere documented. Show default + how to override.
- **`:reads` / `:writes` validation** — what happens when a node declares writes it doesn't produce? When `:reads` keys don't exist in the blackboard? Output contract section touches this; expand.
- **Tree-DSL → canonical translation** — passing mention only; show a worked example of `[:llm {...}]` → `(sheet/llm ...)` for users debugging emit-tree! output.
- **U13 `:parallel` node** — newly fixed in PR-Framework; ensure the Phase-2 tree DSL table's `:parallel` row is accurate and includes an example.

This slice is HITL because it's the human-eyeball final verification checkpoint. The user (or whoever does final review) decides whether each claim is sufficiently grounded AND whether the coverage is sufficient.
>>>>>>> Stashed changes

## Acceptance criteria

- [ ] image_analysis fresh-checkout run produces `:status :success` and letter-extraction within 2-3 letters of the 1,345 cited in the headline report.
- [ ] document_redaction fresh-checkout run produces `:status :success`, total-redactions in the 90-95 range, strict-PII recall verifiably ≥85% against the manual ground-truth inventory.
- [ ] Every command in the bench README runs successfully from a fresh shell.
- [ ] Every numerical claim in the clean reports matches observed behavior (within model run-to-run variance — ±5% typical).
- [ ] Every code example in `docs/RLM-GUIDE.md` compiles / matches the behavior described.
- [ ] Any doc drift identified is fixed and pushed to the appropriate PR branch.
<<<<<<< Updated upstream
=======
- [ ] **Coverage audit complete**: each item in the "Doc-coverage audit" list above has a corresponding section or expanded explanation in RLM-GUIDE.md.
>>>>>>> Stashed changes
- [ ] Final sign-off: user reviews and approves the verification report.

## Blocked by

- US-Bench-Open (both PRs must be open before final re-comb, since re-comb spans both)
