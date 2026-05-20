# PR-Pre01 — Fix pre-existing failures in `repl_researcher_test.clj`

## Parent

`docs/prd/predict-rlm-benchmark-ports.md` (Further Notes → "Pre-existing bug tickets surfaced by this work").

## What to build

Surfaced during PR02 brick-test runs: `clj -M:poly test brick:orc-service` reports **12 failures** in `components/orc-service/test/ai/obney/orc/orc_service/repl_researcher_test.clj`. Verified pre-existing — same 12 failures appear on a clean checkout of `main` with no PR02 changes applied (confirmed by stashing PR02 work and re-running).

Affected deftests (all in non-RLM iterative-REPL mode, NOT in `:rlm true` mode):
- `immediate-final-answer-in-code-text-test` — expects `:status :success`, gets `:failure`
- `tool-call-then-answer-test` — same status mismatch
- `namespaced-tools-test` — same
- `nil-call-tool-fn-no-crash-test` — same
- `usage-tracking-test` — expects 300 prompt tokens, gets 400; multiple count mismatches

Independent of the predict-rlm comparison work — these tests target the non-RLM `execute-repl-researcher` iterative loop, not the RLM mode that the predict-rlm benchmarks use. Tracking here because PR02's regression check is what surfaced them; this issue should be re-homed to a separate folder if a non-predict-rlm contributor picks it up.

## Acceptance criteria

- [ ] Diagnose root cause for each failing test — identify whether it's a test-fixture stale-expectation issue or a regression in `execute-repl-researcher` non-RLM mode behavior.
- [ ] Either fix the underlying behavior OR update the test expectations to match current correct behavior. Document the choice per-test in the commit message.
- [ ] `clj -M:poly test brick:orc-service` reports 0 failures and 0 errors (currently 12 failures).
- [ ] No regression on RLM-mode tests (`rlm_dsl_test`, `rlm_mode_test`) or on the existing 5-benchmark suite live runs.

## Blocked by

None — can start immediately. Independent of all predict-rlm work.
