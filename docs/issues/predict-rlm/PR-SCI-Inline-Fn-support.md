# PR-SCI-Inline-Fn — Make SCI-generated inline `(fn ...)` values executable as `:code` node fns

## Parent

`docs/prd/predict-rlm-benchmark-ports.md` (Out of Scope — captured for future) + report finding in `development/bench/predict-rlm-comparison/reports/01_image_analysis.md` under "Pushing toward emit-tree! with inline :code fns".

## What this would build

The framework now accepts inline `(fn ...)` values in `emit-tree!` `:code` nodes (PR-Image-Analysis follow-up). The DSL translation passes the fn through; the tree executor's `compile-tree-node :code` case registers it in the ephemeral fn registry; the leaf executor invokes it via `resolve-fn` + `execute-code`.

This architectural path works for **inline fns defined in regular Clojure code** (verified by unit test `code-node-accepts-inline-fn`). It DOES NOT work for **inline fns the model writes in its Phase-1 SCI sandbox code**, because the SCI evaluator's internal compilation of map literals in the fn body references `clojure.lang.PersistentArrayMap.createAsIfByAssoc` — a JVM static method that SCI's runtime can't resolve when the fn is later invoked.

Error encountered when running the model's tree:

```
ERROR: Could not resolve symbol: clojure.lang.PersistentArrayMap/createAsIfByAssoc
Available variables: :image, :query, :answer
```

Attempted fix that did NOT work: adding `:classes {'clojure.lang.PersistentArrayMap clojure.lang.PersistentArrayMap ...}` to `sci/init`. The symbol-lookup happens deeper than the class-whitelist layer addresses.

## Why this matters

This is the gap between "the model designs the right workflow" and "the model's workflow actually runs." The image_analysis experiments showed gpt-5.4 designed EXACTLY the predict-rlm methodology (multi-pass OCR + reconciliation) with model-authored inline `:code` fns for letter counting — but the SCI runtime blocked execution. This forces a fallback to either:

1. Direct-execution Phase-1 with inline Clojure (works but defeats the tree's observability advantage)
2. `emit-tree!` with `:available-code-nodes` pre-built fns (works but requires the framework to ship pre-built fns for every transform the model might want)

The inline-fn-in-tree path is the *ideal* developer experience and the strongest expression of ORC's claim ("model designs adaptive structural workflows including deterministic transforms"). Until this is resolved, the claim has an asterisk.

## Investigation directions

Pick the cheapest first.

### Option A: Find the right SCI configuration

SCI may have a non-obvious config option (e.g. `:allow`, `:disallow`, or `:reify-fn`) that exposes the static method. Check SCI's `sci.core/init` docs and known issues. Time: probably 1-2 hours of exploration.

### Option B: Eval the fn form outside SCI

The model's `(fn ...)` form is parseable Clojure. We could intercept earlier in the pipeline — before SCI compiles the fn into an SCI value — and instead use Clojure's regular `eval` to produce a real Clojure fn. Trade-offs:
- Pro: works around SCI entirely for this specific path
- Con: opens the sandbox a crack — the model could write arbitrary Clojure inside `:fn` forms. Mitigate by AST-validating the form before `eval` (only allow a safelist of forms: `let`, `re-seq`, `frequencies`, etc.).

### Option C: Auto-rewrite map literals

In the tree-executor's `:code` compilation step, if the `:fn` value is an SCI-created fn, walk its source and rewrite map literals into explicit `(hash-map ...)` calls (which SCI handles natively). This requires SCI to expose the fn's source form, which it sometimes does via `:meta` on the fn value.

### Option D: Use a different sandbox library

Replace SCI with a more permissive evaluator (e.g. babashka's `babashka.process` interop layer or a thin wrapper around `clojure.core/eval` with safelist gating). Bigger change but unblocks future work.

## Acceptance criteria

- [ ] An ORC live run where the model's emit-tree! tree contains an inline `:code` `:fn` (e.g. `(fn [{:keys [inputs]}] {:answer (count (-> inputs vals first))})`) succeeds end-to-end without `PersistentArrayMap` resolution errors.
- [ ] Unit test: a synthetic SCI-created inline fn that returns a map literal can be invoked by the leaf executor and produces the expected map.
- [ ] No regression on:
  - Existing `:available-code-nodes`-based pre-built-fn `:code` references
  - Direct-execution Phase-1 with inline `(code ...)` primitives
  - The contract_comparison_validated and existing 5-benchmark suite
- [ ] Re-run image_analysis without `:available-code-nodes` — model designs an inline-fn tree AND the tree runs to completion with deterministic Clojure counting.
- [ ] Document the chosen fix path in the issue resolution.

## Blocked by

None — can start when prioritized. Predict-rlm benchmark suite can continue without this; PR-Image-Analysis's headline result (apples-to-apples direct-execution at 1,345 letters / 12,786 tokens / 22.4s, parity with predict-rlm at 2× efficiency) is the existing primary claim, not contingent on inline-fn execution.

## Notes

- The framework prompt was updated to advertise inline-fn `:code` as a valid option. If this issue ships much later, consider rolling back that prompt addition to avoid the model designing trees the framework can't execute.
- Pre-built `:available-code-nodes` (e.g. `predict-rlm-image-tools/count-letter-frequencies`) remains the working alternative path for now and was tested live in the affordance-advertised run.
- The DSL test (`code-node-accepts-inline-fn`) and downstream executor changes ARE valuable and should stay — they unblock anyone calling the executor with hand-crafted inline fns from regular Clojure (not from SCI), e.g. internal benchmarks or tests.
