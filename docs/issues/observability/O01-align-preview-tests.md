# O01: Align preview tests to actual architecture

## Parent

PRD: `docs/prd/phase2-observability-layer.md` (local, on feature branch)

## What to build

Update the failing preview tests in `rlm_mode_test.clj` to assert the correct architecture that's already implemented:

- **Parent code-generating LLM** sees previews of sandbox-vars (token-space economy — the parent doesn't need to see 60K of raw document content in its prompt)
- **Sub-LLM calls** receive FULL values from their `:reads` because they need to actually process content
- This is documented in `rlm_sandbox.clj:125-127` and matches the behavior verified by every benchmark currently passing on main

The three failing assertions are around lines 1059, 1232, 1234, 1236 of `rlm_mode_test.clj`. They were written aspirationally for an alternative architecture that was never built. The fix is to update the assertions to match what the code actually does (and should do).

Also add a brief comment in the test file referencing the load-bearing comment in `rlm_sandbox.clj`, so future contributors don't reintroduce the test-vs-code mismatch.

**No production code changes in this slice.** This is a test-layer alignment.

## Acceptance criteria

- [ ] `rlm_mode_test.clj` failing assertions updated to assert sub-LLMs receive full values, not previews
- [ ] A comment in the test file references the canonical architecture doc at `rlm_sandbox.clj:125-127`
- [ ] Framework tests: 24/95 → all-pass (no fails, no errors)
- [ ] No changes to production code (rlm_sandbox.clj, executor.clj, etc.)
- [ ] Single commit on feature branch with a clear message explaining "test alignment, not code fix"

## Blocked by

None — can start immediately.

## User stories covered

1, 2 (test alignment + comment preservation)
