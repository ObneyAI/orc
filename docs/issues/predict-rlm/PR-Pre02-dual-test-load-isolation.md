# PR-Pre02 — Fix dual-test-load isolation between `rlm_dsl_test` and `rlm_mode_test`

## Parent

`docs/prd/predict-rlm-benchmark-ports.md` (Further Notes → "Pre-existing bug tickets surfaced by this work").

## What to build

Surfaced during PR03 regression check: when both `rlm_dsl_test` AND `rlm_mode_test` are loaded into the same JVM (via two consecutive `(require ... :reload-all)` calls), then `rlm_mode_test` runs, the latter fails with **6 failures + 31 errors out of 45 tests**. Each test namespace passes cleanly when run **alone** in a fresh JVM:
- `rlm_dsl_test` alone: 18 tests, 84 assertions, 0 failures, 0 errors
- `rlm_mode_test` alone: 45 tests, 138 assertions, 0 failures, 0 errors

The errors during dual-load are all at `core.cljc:410` with the message:
```
clojure.lang.ExceptionInfo: Error on key :ai.obney.grain.todo-processor-v2.core/in-chan when building system
{:reason :integrant.core/build-threw-exception, ...}
```

This is an Integrant system build failure during a test fixture — something in the dual-loaded state is preventing the per-test grain system from being constructed cleanly. Verified pre-existing — same failure pattern on a clean checkout of `main` with PR03 changes stashed.

Possible root causes (need investigation):
- Stale `defonce` state across test namespaces (atoms not reset between loads)
- A processor or pubsub registration that leaks across `:reload-all` cycles
- A `with-redefs` block somewhere that doesn't properly unwind

Independent of the predict-rlm comparison work — `rlm_mode_test` is the broader RLM-mode test suite, not specific to our ports. Tracking here because PR03's regression check is what surfaced it.

## Acceptance criteria

- [ ] Diagnose root cause — identify which `defonce`/global state in `rlm_dsl_test`'s requires or its top-level forms is polluting the JVM such that `rlm_mode_test`'s Integrant fixture build fails.
- [ ] Fix the leak — either reset the offending state at namespace teardown OR refactor the test fixture to be resilient to it.
- [ ] **Verify:** require both namespaces in the same JVM, run both `run-tests` calls in sequence (either order), assert both report 0 failures and 0 errors.
- [ ] Document the root cause in the commit message so future contributors don't re-introduce it.
- [ ] No regression on either namespace running alone.

## Blocked by

None — can start immediately. Independent of all predict-rlm work.
