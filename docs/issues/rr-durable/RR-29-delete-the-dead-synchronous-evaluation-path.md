# RR-29 — Delete the dead synchronous evaluation path

## Parent

Grill decision D3 (`docs/build-timeline/grill-sessions/rr-weed-followup-decisions.md`), extended in the same session to
the single-trace sibling: `evaluate_many` and `evaluate_all` were retired from the `TraceJudge` contract because
nothing calls their implementations and the batch path hardcoded a quality threshold.

## What to build

The evaluation component keeps a synchronous, caller-less evaluation API beside the live processor path: a
single-trace evaluator, a batch evaluator with a hardcoded low-score threshold, and the command and event schema
declarations for both that no processor dispatches or emits. Delete the whole limb so the component exposes one
evaluation path — the event-driven judge runtime — and no hardcoded quality threshold remains anywhere in it.

## Acceptance criteria

- [ ] The batch and single-trace synchronous evaluators and their command/event schema declarations are gone
- [ ] No hardcoded score threshold remains in the evaluation component (a repo-wide search finds none)
- [ ] `TraceJudge.evaluate` (the per-judge signature) and the live judge runtime are untouched and green
- [ ] Every existing evaluation suite is green unchanged; tests that only existed to exercise the deleted path are
      removed with it and listed in the report, never rewritten to keep a dead path alive

## Spec obligations covered

- `contract-signature.TraceJudge.evaluate` (must stay covered and green)
- `invariant.OneJudgeScorePerCompletion`, `invariant.JudgeScoresAreBounded` (untouched; must stay green)

## Test seams

Existence: the public interface no longer exposes the deleted vars; the schema registry no longer declares the
deleted command and event types. Regression: the async judge suites.

## Blocked by

None — can start immediately.

## Handoff plan

`docs/build-timeline/handoff-plan/RR29-delete-dead-synchronous-evaluation-HANDOFF.md`

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

- `specs/*.allium` — the orchestrator is the only spec writer.
- The judge runtime processors, judge implementations, rubrics, scale gating, the consolidator.

## Report back

- The `/propagate` coverage line verbatim: `N obligations, M covered, K uncovered`, with a reason for every
  uncovered one. No silent caps.
- Any propagate-emitted mock, stub or TODO skeleton, declared explicitly as a tracked gap.
- Proposed divergence classifications for anything where spec and code disagree.
- What you could NOT verify, stated plainly.

## Orchestrator gates (run after you report)

`/inspect-orc` against this slice: re-run the proof independently, re-read the code, try to break the claims, plus the
three ORC-specific gates — spec-conformance (`allium check` / `analyse` error-free by **severity count** on touched
specs), `/weed` check-mode with classified divergences, and the obligation audit (the coverage line above must survive
into the slice report). Allium's internal verify is the CLAIM; `/inspect-orc` is the falsification.
