# RR-29 handoff — Delete the dead synchronous evaluation path

Issue: `docs/issues/rr-durable/RR-29-delete-the-dead-synchronous-evaluation-path.md` (read it whole).
Work in `/Users/darylroberts/Desktop/Code/orc-rr-durable-arc` (branch `feature/rr-durable-self-learning-arc`). Run
tests with `JVM_XMX=1600m zsh /private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/run-focused.sh <log> <ns…>`
(plain `-M:dev:test`). ONE JVM at a time; never kill a JVM whose working directory is not this worktree (another team's
benchmark runs on this machine); confirm 0 orphan JVMs after every run. Do not run `poly test` or any whole-brick build
(the orchestrator runs the gates). Do not commit, push or stash. Never edit `specs/*.allium`.

## Goal

Delete the caller-less synchronous evaluation API from the evaluation component so one evaluation path remains — the
event-driven judge runtime — and no hardcoded quality threshold survives in the component.

## Read first

1. `specs/evaluation.allium` — `contract TraceJudge` now declares only `evaluate` (per-judge) and the gating/scale
   operations; `evaluate_all` and `evaluate_many` were retired (grill D3, `45043b26` and `b0df4461`).
2. `components/evaluation/src/ai/obney/orc/evaluation/interface.clj` lines ~225-286: `evaluate-trace` (single-trace
   synchronous aggregate) and `evaluate-traces` (batch; `(< (:score %) 0.7)` hardcoded). Confirm with a repo-wide
   search (components, bases, development; exclude tests) that nothing calls either — the orchestrator found no
   non-test caller and only schema references.
3. `components/evaluation/src/ai/obney/orc/evaluation/interface/schemas.clj` lines ~62 (`:evaluation/trace-evaluated`),
   ~73 (`:evaluation/batch-completed`), ~120 (`:evaluation/evaluate-trace`), ~130 (`:evaluation/evaluate-batch`) —
   command and event types no processor dispatches or emits. Verify each has no producer/consumer before deleting.
4. `components/evaluation/src/ai/obney/orc/evaluation/core/judges.clj` — `aggregate-dimensions` (fixed per-dimension
   weights, used only by the synchronous path? verify) — delete it only if its sole caller is the deleted path.
5. The live path that must remain untouched: `core/judge_runtime.clj`, `core/commands.clj`.

## The exact change

Delete `evaluate-trace`, `evaluate-traces`, the four schema declarations, and any helper whose only caller was one of
them. Delete tests that exist solely to exercise the deleted path (list them in the report). Nothing else changes.

## TDD cycle list

1. **RED** `rr29_no_synchronous_evaluation_path_test.clj`: assert the interface namespace resolves neither
   `evaluate-trace` nor `evaluate-traces` (`(nil? (ns-resolve 'ai.obney.orc.evaluation.interface 'evaluate-traces))`),
   and that the schema registry has no `:evaluation/evaluate-batch`, `:evaluation/batch-completed`,
   `:evaluation/evaluate-trace`, `:evaluation/trace-evaluated` entries. RED today.
2. GREEN: the deletions.
3. Regression: `judge-async-command-test`, `rr17-trace-judge-evidence-test`, `rr26-composite-score-race-test`,
   `judge-runtime-test`, and every other namespace under `components/evaluation/test` (list them; run them all).
4. Repo-wide search: no `0.7` score threshold and no reference to the deleted vars/types outside git history.

Propagate note (orchestrator): the planned obligations `contract-signature.TraceJudge.evaluate`,
`invariant.OneJudgeScorePerCompletion`, `invariant.JudgeScoresAreBounded` are already covered and must stay green.
Coverage line: `3 obligations, 3 covered, 0 uncovered`.

## Disciplines (verbatim — do not summarise, do not skip)

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions.
  Reproduce -> minimize -> fix the actual cause. Don't blame the network or the model — the cause is in the code or
  the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can
  fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red -> green -> refactor, one test at a time.** Vertical tracer-bullet slices, never
  horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive
  refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, network, storage)
  as capabilities that **default to the real impl and are faked in tests**.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing. Then
  turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's
  "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

Standing rules for this arc:

- **Never weaken a generated test to make it pass.** A `/propagate`-generated test is contract. If it is wrong, the
  spec is wrong: report it, the orchestrator `/tend`s and re-propagates.
- **A generated test green before you implement is a finding**, not success — already-covered or vacuous. Report it.
- **Default-path behaviour must not change** until RR-15 flips the default. Every slice before it keeps the
  non-checkpointed path byte-identical.

## Do NOT touch

- `specs/*.allium`; the judge runtime processors; judge implementations, rubrics, scale gating; the consolidator.

## Report back

Files changed and files deleted (with the tests removed and why each only exercised the dead path); each cycle's RED
and GREEN `RESULT` lines verbatim; the coverage line; the repo-wide search output; what you could NOT verify; the
orphan-JVM check.
