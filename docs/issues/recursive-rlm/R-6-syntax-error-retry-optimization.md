# R-6: Phase-1 syntax-error retry optimization

## Parent

`docs/prd/rlm-recursive-emit-tree.md`. Follow-up surfaced by the 5-benchmark recursive sweep (post R-3 + R-4 + R-5 fixes). All 5 benchmarks pass cleanly but 2 of them (`contract_comparison`, `document_analysis`) waste 2 iterations on syntax errors before self-recovering.

## What to build

When the SCI sandbox returns a parse error like `Unmatched delimiter: ], expected: } to match { at [line col]`, the model frequently emits the same or a similar parse-broken code on the next iteration — wasting an iteration and ~5-10K tokens per retry.

Two cooperating mitigations:

1. **Enhance iteration-history error display** — when an iteration's `:error` is a SCI parse error with `[line col]` markers, surface the offending line + a caret pointer in the iteration history so the model sees exactly which character to fix:

   ```
   ### Iteration 1
   Code:
   ...
   Error: Unmatched delimiter: ], expected: } to match { at [16 11]
   At that line:
     {:from :a :as :b]
               ^
   ```

2. **Prompt guidance for recursive mode** — when prior iterations show parse errors, the prompt should explicitly say: "if a prior iteration's `:error` was a parse error with `[line col]`, carefully count the parens/brackets at that exact position before writing the next iteration. The error message is structurally exact — fix the indicated character, don't retry similar code."

End-to-end behavior:
- After R-6, the model's average iterations-until-success on `contract_comparison` and `document_analysis` recursive drops from 4 → ≤ 2.
- Token cost on those benchmarks improves by ~5-10K per benchmark.

## Acceptance criteria

- [ ] Unit test: `build-iteration-history` for an entry with SCI parse error includes the line/caret context.
- [ ] Unit test: recursive-mode prompt mentions "fix the indicated character" + the structural-exact framing.
- [ ] Live verify: re-run `contract_comparison` and `document_analysis` recursive — average iteration count ≤ 2.
- [ ] No regression on the 3 benchmarks that already converge in 2 iterations (`image_analysis`, `document_redaction`, `invoice_processing`).

## Blocked by

- None (R-3 + R-4 + R-5 already shipped to this branch).

## Notes

- This is an OPTIMIZATION, not a correctness fix. All 5 recursive benchmarks already pass (per the R-Bench sweep).
- Lower priority than making recursive the default mode (R-Default).
