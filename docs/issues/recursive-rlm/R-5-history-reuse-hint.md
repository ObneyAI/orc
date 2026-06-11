# R-5: Iteration history reminds the model to reuse prior-tree variables

## Parent

`docs/prd/rlm-recursive-emit-tree.md`. Optimization follow-up surfaced by the recursive-mode benchmark experiment in `development/bench/predict_rlm_comparison/reports/recursive-mode-experiment.md`.

## What to build

In the recursive-mode experiment, tree 2 (iter 2) RE-RAN pass1 (per-page PII extraction) and pass2 (per-page adversarial review) from scratch instead of reusing the `:pass1_targets` / `:pass2_results` variables that tree 1 had already populated. Tree 2 only needed to do its NEW work (pass3 corrective review reading prior `:targets-applied` + `:targets-missing`), not redo passes 1+2.

The recursive-mode prompt section explains that tree outputs merge into sandbox-vars (`(get-var :key)` is available), but the iteration history doesn't actively remind the model that VARIABLES FROM PRIOR TREES ARE NOW IN SCOPE. So the model defaults to re-computing.

End-to-end behavior:
- The iteration history (`build-iteration-history` in `executor.clj`) gains a per-iteration prefix block listing the variable names that previous trees wrote to sandbox-vars, e.g.:
  > Iteration 2 added these variables (reuse via `(get-var :key)` rather than recomputing): :pass1_targets (vector of 84 maps), :pass2_results (vector of 6 maps), :all_targets (vector of 95 maps), :targets-applied (vector of 95 maps), :targets-missing (vector of 1 map).
- The model sees this in subsequent iterations and references existing variables rather than redoing per-page LLM calls.

This is purely additive UX guidance — the variables ARE already accessible; the prompt just makes the option salient.

## Acceptance criteria

- [ ] Unit test: `build-iteration-history` called with a history entry containing `:vars-created [:pass1_targets :all_targets]` includes those variable names + previews in the formatted output.
- [ ] Integration: document_redaction recursive (after R-3 + R-4) shows tree 2 (when emitted) referencing `(get-var :pass1_targets)` or similar in its `:reads`, not re-doing per-page pass1 extraction.
- [ ] No regression on existing iteration-history tests.

## Blocked by

- R-3 (sanitization — without it, the recursive run can't complete tree 2 cleanly to evaluate whether reuse is happening).
- Optional but desirable: R-4 (sub-model — to keep evidence apples-to-apples).

## Notes

- This is the LOWEST priority of R-3/R-4/R-5. The ~30s of wasted work from tree 2's redundancy is small compared to the 14-minute Fressian crash silence (R-3) and the model-attribution issue (R-4).
- If the model ALREADY uses prior-tree variables once R-3 is in (the redundancy was an artifact of crashed state, not a real prompt issue), R-5 may not be needed.
