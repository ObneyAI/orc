# R-3: Inline-fn Fressian sanitization in read-model-processor tick state

## Parent

`docs/prd/rlm-recursive-emit-tree.md` (recursive emit-tree! PRD on feature branch). Follow-up to R-1 (core recursive loop) and R-2 (drill-down primitives), surfaced by the recursive-mode benchmark experiment in `development/bench/predict_rlm_comparison/reports/recursive-mode-experiment.md` + `recursive-mode-plan.md`.

## What to build

The U8 inline-fn sanitization (from the original framework upgrades PR) only sanitizes the `:rlm/tree-generated` event when stored. The READ-MODEL processor's tick-state persistence path (`ai.obney.grain.read_model_processor_v2.core/write_partitions!`) writes the SAME tree state — including SCI inline-fn objects from `[:code {:fn (fn [...] ...)}]` nodes — to LMDB via Fressian. Fressian has no write-handler for `sci.impl.fns$fun$arity_*` and throws an uncaught exception.

In recursive mode this is catastrophic: the model legitimately emits trees with inline `:code` fns, the read-model write fails mid-tick, the read-model processor enters a bad state, and the parent tick times out 14 minutes later despite having completed real work in the first minute.

End-to-end behavior:
- Apply the same inline-fn sanitization (replace SCI fn objects with the placeholder string `"<inline-fn>"`) to whatever tree-shaped data the read-model processor writes to LMDB.
- The actual SCI fn lives in the sandbox VM as a live object — only the SERIALIZED projection needs sanitization. In-flight execution still uses the real fn.
- Recursive-mode runs with inline `:code` fns complete in their natural wall clock (~30-90s per tree) instead of timing out at the parent-tick budget.

### Evidence pointer

Trace file: `development/bench/predict_rlm_comparison/results/document-redaction-recursive_2026-05-22_121601.trace.edn` — contains the uncaught exception at T+63s:

```
:exception "Cannot write sci.impl.fns$fun$arity_1__29795@542797e2 as tag null"
:trace [... read_model_processor_v2/write-partitions! ...]
```

## Acceptance criteria

- [ ] Unit test (or integration test) demonstrates recursive-mode execution of a tree containing `[:code {:fn (fn [...] {...}) :reads [...] :writes [...]}]` completes without an uncaught Fressian exception in `read_model_processor_v2/write_partitions!`.
- [ ] Document-redaction recursive run completes in ≤ 120s wall-clock (vs current 903s — the 14-minute mystery silence is gone).
- [ ] The persisted LMDB state contains the placeholder string `"<inline-fn>"` (or equivalent) in place of the SCI fn object, and downstream reads from that state don't crash.
- [ ] In-process tick execution still calls the real SCI fn (function semantics preserved end-to-end — sanitization is serialization-only).
- [ ] No regression on existing 5-benchmark terminal-mode test suite (all 66 brick + framework tests still GREEN).

## Blocked by

- R-1 + R-2 (already merged on main).

## What to do AFTER this lands

Re-run document_redaction recursive (cheapest evidence). Confirm:
- :status :success
- :total-redactions in 80-95 range (i.e. real output, not partial-due-to-crash)
- Duration ≤ 2 min
- AI events span the full duration (not bunched in first minute)

If verified, move to R-4 (sub-model injection in recursive mode) and R-5 (history reuse hint).
