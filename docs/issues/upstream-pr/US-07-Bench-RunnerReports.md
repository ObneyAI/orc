# US-Bench-RunnerReports — PR-Bench commit 4: runner + reports + references + README

## Parent

`docs/prd/upstream-pr-plan.md` — Step 3 of the execution sequence, fourth (final) PR-Bench commit.

## What to build

**Commit 4:** `feat(bench): predict-rlm-comparison runner + reports + references + README`

Contents:

1. **Comparison runner** under `development/bench/predict_rlm_comparison/runner.clj`:
   - Public API mirrors `development/bench/runner.clj` (`start!`, `run!`, `stop!`, `generate-summary!`)
   - Preserves `:iterations` (read from `:rlm/researcher-iterations` events — U10 dependency), `:by-node`, `:node-trace` (from `:sheet/node-execution-completed` events, filtered by run-start instant across all sheets — captures parent + ephemeral child sheets)
   - Per-run mulog `.trace.edn` publisher (`simple-file` writes EDN, not JSON)
   - Single-task-lock atom prevents concurrent runs corrupting sheet state
   - Supports per-task `:model`, `:sub-model`, `:input-schemas`, `:output-schemas`, `:input-loader`, `:available-code-nodes`
   - LMDB map-size bumped to 512MB for image-heavy tasks

2. **Reference assets** under `development/bench/predict-rlm-comparison/references/predict-rlm/`:
   - `LICENSE` (MIT, verbatim from predict-rlm)
   - `<task>/signature.py.txt` (verbatim source for traceability)
   - `<task>/sample/input/*` (PDFs, screenshots used as benchmark inputs)
   - `<task>/sample/output/output.md` (predict-rlm's published reference outputs — authoritative source for all comparison numbers in our reports)
   - `document_redaction/README.md` (predict-rlm's README, useful for the README-vs-sample discrepancy reference)

3. **Two CLEAN external-facing reports** under `development/bench/predict-rlm-comparison/reports/`:
   - `01_image_analysis.md`
   - `02_document_redaction.md`
   - **NOT** the deep-dive companions (`*_deep_dive.md`) — those stay in the local `feature/predict-rlm-benchmarks` worktree

4. **Bench `README.md`** under `development/bench/predict-rlm-comparison/README.md`:
   - 1. What this is (one paragraph)
   - 2. What's compared (table of 2 benchmarks + EDN/report paths)
   - 3. Headline results (compact summary)
   - 4. Prerequisites (`OPENROUTER_API_KEY`, expected ~$0.15 total)
   - 5. How to run (exact REPL incantations for each benchmark)
   - 6. Expected runtime + cost per benchmark
   - 7. Where outputs go
   - 8. Comparing your run to the committed reference (expected variance, expected ~ranges)
   - 9. Methodology + fidelity caveats (links to clean reports)
   - 10. References (link to predict-rlm + MIT)
   - **Every command-line and number in the README is verified against fresh-checkout reproducibility before submission.**

5. **Result EDN policy:**
   - Commit ONLY 4 files: 2 headline `.edn` + 2 `.trace.edn`
     - `results/image-analysis_2026-05-20_150618.edn` + `.trace.edn`
     - `results/document-redaction_2026-05-20_165215.edn` + `.trace.edn`
   - Add `.gitignore` under `development/bench/predict-rlm-comparison/results/` that excludes all `.edn` and `.trace.edn` files EXCEPT those 4

## Acceptance criteria

- [ ] Fourth commit on `feature/predict-rlm-comparison-bench` with message starting `feat(bench): predict-rlm-comparison runner + reports + references + README` and Co-Authored-By trailer.
- [ ] Runner is invocable: `clj -M:dev -e '(require (quote [predict-rlm-comparison.runner :as r])) (r/start!) ...'` works without compilation errors.
- [ ] Reference assets fully present under `references/predict-rlm/` (total ~660KB).
- [ ] Both clean reports present (NO deep-dives).
- [ ] Bench README exists with all 10 sections.
- [ ] Every numerical claim in the README + reports is sourced from a verified data point (test result, run EDN, or predict-rlm's `sample/output/output.md` — NOT from their README which has different numbers).
- [ ] Every command-line in the README runs successfully on a fresh checkout.
- [ ] 4 headline EDN files committed (2 `.edn` + 2 `.trace.edn`).
- [ ] `.gitignore` correctly excludes additional `.edn` files in `results/` while preserving the 4 headlines.
- [ ] No regression on `rlm-mode-test`, `rlm-dsl-test`, existing 5-benchmark suite.

## Blocked by

- US-Bench-Bricks (the runner imports the 3 bricks and the task files depend on them)
