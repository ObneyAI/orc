# PR-Multi-Tree — Multi-tree iteration in the RLM researcher (FUTURE)

## Parent

`docs/prd/predict-rlm-benchmark-ports.md` (Out of Scope — captured for future).

**Status:** Future enhancement, NOT in scope for the current predict-rlm comparison work. Captured here so the idea isn't lost.

## What this would build

Today the ORC RLM researcher loop is:

```
Phase 1 (loop):
  - Model writes Clojure code
  - Code runs in SCI sandbox
  - If `final!` was called → return result
  - If `emit-tree!` was called → Phase 2 spawns child sheet, executes tree → return result
  - Else (no terminal call) → recur with new history
```

Once `emit-tree!` fires and Phase-2 completes, **`execute-repl-researcher-rlm` returns immediately with the Phase-2 result.** The model only gets one shot at tree design — it cannot inspect Phase-2's output, decide "this is incomplete, let me design a follow-up tree," and emit a second tree.

This is a meaningful architectural limit. Predict-rlm's REPL-iterative model does the equivalent by running multiple `predict()` calls in the same Python session and progressively refining. ORC's RLM forces the model to predict all decomposition needs upfront, including any quality-validation stages, in a single tree.

The contract_comparison_validated benchmark works around this by encouraging in-tree adversarial validation (the final synthesis node re-reads sources). That pattern works for some tasks. For others — e.g. when the validation result might require a structurally different follow-up extraction — a multi-tree iteration would be more natural.

### Proposed shape

After Phase-2 completes, instead of returning, fold the result back into Phase-1's sandbox-vars:

```
Phase 1 (loop continues):
  - Model wrote `emit-tree!` → Phase 2 ran → result returned to sandbox-vars
  - Available now in sandbox-vars: all Phase-2 outputs + a marker like :previous-tree-result
  - Model gets another iteration: can inspect outputs, decide to call `final!` (commit) OR `emit-tree!` again (refine)
```

The previous-tree-result might be summarized (preview only) to keep token cost bounded — the actual values stay in sandbox-vars accessible via `get-var`.

### Why this matters for predict-rlm comparison

predict-rlm's `image_analysis` query baked in multi-pass extraction. Their model literally calls `predict()` 3 times and reconciles. Our model emits a tree, can do multi-pass IN the tree if it chooses, but cannot adaptively decide "extract more after seeing what was missed."

The PR06b run showed the limit: when given an adversarial-completeness instruction, the model absorbed it into a single prompt rather than restructuring as a follow-up tree. The model COULD have designed a multi-stage in-tree validation, but for harder tasks (e.g. iteratively refining a redaction set), a multi-tree iteration would map more cleanly to how predict-rlm operates.

## Out of scope (why this isn't being built now)

- The current predict-rlm comparison work delivers a meaningful apples-to-apples test even without multi-tree iteration — see PR07's correctness analysis.
- Multi-tree iteration requires non-trivial architectural change to `execute-repl-researcher-rlm` (the loop currently exits after Phase 2). It needs careful design to avoid runaway iteration costs.
- The in-tree adversarial pattern (contract_comparison_validated) shows the model can achieve a lot without multi-tree iteration.

## Acceptance criteria (when this gets picked up)

- [ ] After Phase-2 completion, the model can choose to emit another tree (model decision via `(emit-tree! ...)`) or commit (`final!`).
- [ ] Phase-2 results are folded into Phase-1 sandbox-vars so subsequent iterations can read them via `get-var` / `:reads`.
- [ ] `:max-iterations` bounds total iteration count to prevent runaway.
- [ ] Each iteration's emitted tree is captured separately in `:iterations` for observability.
- [ ] A new event type `:rlm/iteration-completed` (or similar) signals each iteration's outcome to downstream observability.
- [ ] Test: model emits tree A, Phase-2 runs, model emits tree B referencing tree A's output, second Phase-2 runs, final! commits — full e2e in a unit test with mocked dscloj.

## Blocked by

None — but explicitly NOT in this comparison work. Capture only.
