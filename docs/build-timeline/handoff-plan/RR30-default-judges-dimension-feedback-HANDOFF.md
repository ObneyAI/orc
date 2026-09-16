# RR-30 handoff — Default judges carry dimension-specific feedback

Issue: `docs/issues/rr-durable/RR-30-default-judges-carry-dimension-specific-feedback.md` (read it whole).
Work in `/Users/darylroberts/Desktop/Code/orc-rr-durable-arc` (branch `feature/rr-durable-self-learning-arc`). Run
tests with `JVM_XMX=1600m zsh /private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/run-focused.sh <log> <ns…>`
(plain `-M:dev:test`). ONE JVM at a time; never kill a JVM whose working directory is not this worktree (another team's
benchmark runs on this machine); confirm 0 orphan JVMs after every run. Do not run `poly test` or any whole-brick build
(the orchestrator runs the gates). Do not commit, push or stash. Never edit `specs/*.allium`.

## Goal

Make every score emitted by the four default LLM judges on the live processor path carry dimension-specific feedback,
by projecting each judge's own existing evidence lists into named dimensions. No new model call, no prompt change, no
change to score, feedback or model provenance.

## Read first

1. Spec excerpt, verbatim (`specs/evaluation.allium`, surface guarantee):

       @guarantee ActionableFeedback
           -- Every successful score carries dimension-specific feedback suitable
           -- for human diagnosis and downstream instruction optimization.

   and `value MetricDimension` / the `:dimensions [:vector DimensionScore]` shape in
   `components/evaluation/src/ai/obney/orc/evaluation/interface/schemas.clj` (~lines 38-42, 102-106): each dimension
   is `{:name :weight :score :feedback}` (read the exact `DimensionScore` schema before writing one).
2. `components/evaluation/src/ai/obney/orc/evaluation/core/judge_runtime.clj` lines ~144-170, `invoke-llm-judge`:
   dispatches to the four judges and returns `{:score :feedback :dimensions [] :model-provenance}` — the `[]` is the
   defect. Note `inner` is the judge's result map.
3. `components/evaluation/src/ai/obney/orc/evaluation/core/judges.clj` lines ~52-86 (each judge's output schema — the
   evidence lists: grounding `grounded-claims`/`ungrounded-claims`; instruction-following `requirements-met`/
   `requirements-missed`; reasoning `reasoning-strengths`/`reasoning-weaknesses`; completeness `aspects-covered`/
   `aspects-missing`) and the four `*-judge` functions (~489-616) — confirm which keys each returns in its result map.
4. `components/evaluation/src/ai/obney/orc/evaluation/core/heuristic_structural.clj` lines ~81-92 — the model to
   follow: dimensions named in the judge's own vocabulary with a weight, a score and a one-line feedback.
5. The existing async suites: `components/evaluation/test/ai/obney/orc/evaluation/judge_async_command_test.clj`
   (how a judge is exercised on the live processor path with the `:test` provider / stubbed LLM) and
   `rr17_trace_judge_evidence_test.clj`.
6. `docs/build-timeline/grill-sessions/rr-weed-followup-decisions.md` §D4.

## The exact change

In `invoke-llm-judge`, replace `:dimensions []` with a pure projection of `inner`'s evidence lists into dimensions:
one dimension per evidence pair, named in the judge's vocabulary (e.g. "Grounding" with feedback listing grounded vs
ungrounded claims; "Instruction following" with met vs missed; "Reasoning" with strengths vs weaknesses;
"Completeness" with covered vs missing), each carrying the judge's `:score` and a weight of 1.0 (a single-dimension
judge) — read `DimensionScore` and match it exactly. Empty evidence lists still yield the dimension (with feedback
saying nothing was cited). Score, feedback and model-provenance pass through unchanged.

## TDD cycle list

1. **RED** `rr30_default_judge_dimensions_test.clj`: for ONE judge (grounding), drive the live processor path the way
   `judge_async_command_test` does with a stubbed LLM returning a valid banded output including grounded/ungrounded
   claims; read back the emitted `:judge/score-emitted` event; assert `(seq (:dimensions event))`, that the dimension's
   name is the judge's vocabulary, its score equals the event's score, and its feedback mentions the cited claims.
   RED today (`[]`).
2. GREEN: the projection for that judge only.
3. Repeat RED→GREEN for reasoning, completeness, instruction-following (one at a time).
4. **RED then GREEN** guard: heuristic-structural's dimensions are byte-identical before/after (assert on its emitted
   event); a custom judge's dimensions untouched.
5. Regression: every namespace under `components/evaluation/test`, plus the consolidator suites in
   `components/ontology/test` that consume judge scores (`consolidator_test`, `consolidation_trigger_test`).

Propagate note (orchestrator): the guarantee is prose; `allium plan` emits no obligation for it. Planned obligations
that must stay green: `entity-fields.JudgeScore`, `invariant.JudgeScoresAreBounded`,
`invariant.OneJudgeScorePerCompletion`. Coverage line: `3 obligations, 3 covered, 0 uncovered` plus the guarantee
covered by cycles 1–4.

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

- `specs/*.allium`; rubrics, prompts, the discrete scale gating; judge weights and composite aggregation; the
  heuristic-structural and custom judge dimension logic; the consolidator.

## Report back

Files changed; each cycle's RED and GREEN `RESULT` lines verbatim; one example emitted event per judge (dimensions
shown); the coverage line; what you could NOT verify; the orphan-JVM check.
