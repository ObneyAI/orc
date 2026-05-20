# PR06b — Re-run image_analysis after fixes land + add adversarial-completeness clause

## Parent

`docs/prd/predict-rlm-benchmark-ports.md` (Build & test order item #3 in revised plan).

## What to build

Close the loop on the PR06/PR07 quality gap discovered during the comparison-report authoring. PR06 ran image_analysis end-to-end and PR07 wrote the comparison report. Two material issues surfaced that need resolving before the comparison can be trusted:

1. **PR-Pre03** — Phase-1 vision routing bug meant tokens were over-counted by ~3 orders of magnitude AND the model wasn't actually seeing the image (it saw a wall of base64). Once that lands, vision should work properly and token numbers should be honest.
2. **PR-Prompt** — RLM prompt policy update should push the model toward `emit-tree!` for chained workflows and toward `:code` nodes for deterministic transforms (letter-counting was an LLM call where a pure-Clojure function would have been definitively correct).

Plus a per-task addition:

3. **Adversarial-completeness clause** in the image_analysis goal instruction — explicit quality requirement: "After producing your initial answer, adversarially verify completeness by re-examining the image to identify any visible text region your extraction may have missed. If significant text was missed, extract those regions and add to the count before producing the final answer." Goal-only — no methodology or tree-shape dictation.

This issue:
1. Adds the adversarial-completeness clause to `tasks/image_analysis.clj`'s `:instruction`.
2. Re-runs the benchmark end-to-end with the new instruction + the PR-Pre03 vision fix + the PR-Prompt policy update all in effect.
3. Updates the PR07 comparison report at `development/bench/predict-rlm-comparison/reports/01_image_analysis.md` against the new run results, preserving both pre- and post-fix runs as artifacts in the report's fidelity caveats section.

## Acceptance criteria

- [ ] `tasks/image_analysis.clj` instruction includes the adversarial-completeness clause (verbatim text matches the wording in PR-Prompt issue).
- [ ] Live re-run of `(runner/run! t/task)` from a clean JVM after PR-Pre03 + PR-Prompt have landed.
- [ ] Re-run reports `:status :success` and a non-empty `:answer`.
- [ ] Re-run's `:usage` reports vision sub-LLM tokens on the order of 1K-10K (not 480K).
- [ ] Re-run's emitted code shows EITHER a proper `emit-tree!` tree OR a single-call direct execution — flagging clearly which the model chose and why (per the new prompt policy). If chained sequential `(llm ...)` appears again, the prompt policy needs further iteration.
- [ ] Re-run's `:answer` is materially MORE complete than the post-cleanup baseline (which extracted 211 letters vs predict-rlm's 1,343). Target: letter-count total within 2× of predict-rlm's 1,343 (or document why not).
- [ ] PR07 report (`reports/01_image_analysis.md`) is updated with:
  - The new run's EDN + trace file references
  - Side-by-side comparison column for "pre-PR-Pre03 + pre-PR-Prompt run" vs "post-fix run" vs "predict-rlm"
  - Updated findings section reflecting the new methodology
  - Updated token cost claims now grounded in honest billing
- [ ] No regression on the existing 5-benchmark suite or any other test namespace.

## Blocked by

- **PR-Pre03** (Phase-1 vision routing fix)
- **PR-Prompt** (emit-tree! default policy)

Both must land before this re-run is meaningful.

## Resumption note

When PR-Pre03 and PR-Prompt are GREEN, this issue is the immediate next step. Once it's done, the predict-rlm work resumes its original sequence at PR08 (document_redaction execution).
