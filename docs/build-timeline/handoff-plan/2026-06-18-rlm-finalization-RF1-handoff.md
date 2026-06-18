# RF1 Handoff — Structured `final!` finalization for the terminal `:repl-researcher`

Fresh-context implementation brief for the RF1 slice
(`docs/build-timeline/issues/rlm-finalization/RF1-terminal-final-bang.md`).
Implement via `/tdd`; the orchestrator runs the after-each `/inspect-orc` protocol
before trusting or committing. One commit. Branch `feature/ontology-architecture`.

## The goal in one sentence
A `:repl-researcher` node with NO `:rlm` config should finalize by the model calling
**`(final! {…})`** (validated against the node's `:writes`, captured structured,
read directly) — the SAME way the `:rlm` path already works — NOT by regex-scanning
stdout for a `FINAL_ANSWER: <text>` marker.

## Read first (in this order)
1. `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj`
   - `execute-repl-researcher` (defn ~L1400) — the DISPATCHER: `(if (:rlm node) …)` (~L1432). The `:rlm` branch → `execute-repl-researcher-rlm` (~L2159, binds `final!`). The else branch → the LEGACY TERMINAL loop (~L1517–1571) that scans `FINAL_ANSWER` via `sci-sandbox/contains-final-answer?` on `result-for-extraction`/`:stdout`. **This terminal loop is what RF1 changes.**
   - The `final!` return path in the rlm branch (~L2527–2545) — for how a captured structured output becomes `:outputs`.
2. `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_sandbox.clj`
   - `final!-fn` (~L374–378): `(validate-final! output declared-writes)` → `(reset! final-output validated)` → returns a (vestigial) marker string. **Reuse this pattern.**
   - `validate-final!` (~L239–272): the structured `:writes` contract (extra/missing/all-blank rejection). **Reuse this fn.**
   - `:final-output` atom wiring (~L327, L341) — how the captured value is surfaced.
3. `components/orc-service/src/ai/obney/orc/orc_service/core/sci_sandbox.clj`
   - `extract-final-answer` / `contains-final-answer?` / `final-answer-patterns` (~L264–317) — the marker path RF1 retires-or-deprecates.
4. `components/orc-service/test/ai/obney/orc/orc_service/repl_researcher_test.clj` — the 12 stale tests (`test-node` has NO `:rlm`).
5. `components/orc-service/test/ai/obney/orc/orc_service/code_executor_test.clj` — L105 stale string `#"must return a map"` (current message: `"could not be reconciled with declared :writes"`, executor.clj ~L919).
6. Memory `feedback_poly_test_vs_dev_test_false_green` — WHY this must be verified under BOTH runners.

## Step 0 — decision gate (do this FIRST, record the result)
Grep for production callers building a `:repl-researcher` node WITHOUT `:rlm`:
```
grep -rn --include='*.clj' "repl-researcher\|:repl-researcher" components development | grep -v _test
```
Inspect each construction site for an `:rlm` key.
- **Zero non-`:rlm` callers →** retire the `FINAL_ANSWER`-marker scraping in the terminal loop (delete the marker branch; finalize only via `final!`).
- **Any non-`:rlm` caller →** keep marker scraping as a **deprecated fallback AFTER** the `final!` check; list the callers in the live-verify note for later migration.
Record the finding in the live-verify capture either way.

## The exact change
In `executor.clj`'s terminal (non-`:rlm`) branch of `execute-repl-researcher`:
- Build the terminal SCI sandbox with a **`final!` binding** that mirrors
  `rlm_sandbox/final!-fn`: `(validate-final! output (:writes node))` → reset a
  local `final-output` atom. (Reuse `validate-final!`; do not reimplement its
  rules.)
- After executing each iteration's code, **check the `final-output` atom first**:
  if set, return `{:status :success :outputs <validated-map> :iterations … :duration-ms … :usage @total-usage}` — `:outputs` read directly off the validated map (NO marker parse, NO `pr-str` round-trip).
- Per Step 0: either remove the `contains-final-answer?`/`extract-final-answer`
  marker branch, or leave it as a clearly-commented deprecated fallback after the
  atom check.
- Preserve the rest of the loop verbatim: blank-code → `:failure "did not generate
  code"`; `repeated-output?` convergence; max-iterations → `:failure`; usage
  accumulation.

Then rewrite the 12 `repl-researcher-test` assertions so the mocked `dscloj/predict`
returns code that **calls `final!`** (e.g. `{:outputs {:code "(final! {:answer \"42\"})"}}`),
and assert `:status :success` + the value off `:outputs`. For multi-iteration tests,
earlier iterations do their work (println/tool calls) and the LAST iteration calls
`final!`. Update `code-executor-test` L105 to the current message.

## Do NOT touch
- `execute-repl-researcher-rlm` / the `:rlm` / recursive path's BEHAVIOR (only
  reuse its `final!`/`validate-final!` helpers).
- `validate-final!`'s rules (reuse as-is).
- The ontology bricks, the EB subbehaviors, `build!`, anything outside the
  repl-researcher terminal path + the two named test files.
- The `:reasoning`-first convention on `:llm` nodes (unrelated; leave intact).

## Live QA (the orchestrator will independently re-run all of this)
- `clj -M:poly test brick:orc-service` → no Error 101, brick executes, 0 fail.
- Direct: `clj -M:dev:test -e "(require 'clojure.test 'ai.obney.orc.orc-service.repl-researcher-test 'ai.obney.orc.orc-service.code-executor-test) (let [r (clojure.test/run-tests 'ai.obney.orc.orc-service.repl-researcher-test 'ai.obney.orc.orc-service.code-executor-test)] (println :SUMMARY r) (shutdown-agents) (System/exit (+ (:fail r) (:error r))))"` → 0 fail / 0 error.
- Re-run a recursive/`:rlm` suite to confirm no regression in the `final!` path.
- JVM hygiene: bounded runs; confirm 0 orphan this-repo JVMs (exclude sibling worktrees) after; kill by PID if any.
- Capture a short live-verify note (`docs/build-timeline/live-verify/RF1-final-bang.md`): the Step-0 finding, the before/after of one rewritten test, both runners green.

## Dependency rule
RF1 is self-contained — it depends only on the EXISTING `final!`/`validate-final!`
API (already in `rlm_sandbox.clj`), so no pre-blocked APIs. RF2 (`seed-descriptions`
poly gap) is independent and deferred; do not bundle it.

## Disciplines
The binding Core Disciplines block 1–13 in the RF1 issue file is in force VERBATIM.
Most load-bearing here: #5 (no band-aid — this is a real root-cause fix, not greening
stale tests), #6 (TDD red→green, one at a time), #8 (REUSE `final!`/`validate-final!`,
don't fork), #4 + #9 (verify under BOTH runners; no false green; honest negatives),
#11 (one commit, co-author trailer, JVM hygiene, HITL audit by path).
