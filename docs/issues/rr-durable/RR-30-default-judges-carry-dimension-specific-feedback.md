# RR-30 — Default judges carry dimension-specific feedback

## Parent

Grill decision D4 (`docs/build-timeline/grill-sessions/rr-weed-followup-decisions.md`): `ActionableFeedback` stays as
written; the code must honour it.

## What to build

On the live processor path the four default LLM judges — grounding, reasoning, completeness and instruction-following
— emit their score with an empty dimensions list, although each already produces the named evidence the guarantee
asks for. Project each judge's own evidence lists into named dimensions carrying that judge's score, so every emitted
judge score carries dimension-specific feedback, the two judges that already comply are no longer exceptions, and the
consolidator receives dimension-level feedback instead of a number plus prose. The projection is pure: no new model
call, no change to what the judges are asked, no change to score or feedback values.

## Acceptance criteria

- [ ] Every score emitted by a default LLM judge on the live path carries at least one dimension named in that judge's
      own vocabulary, with its evidence and the judge's score
- [ ] The heuristic-structural and custom judges' dimensions are unchanged
- [ ] The consolidator's reading of judge scores keeps working (its suites green)
- [ ] Score, feedback and model provenance on every emitted event are unchanged
- [ ] The emitted event still validates against the score schema; one score per completion still holds

## Spec obligations covered

- `entity-fields.JudgeScore`, `invariant.JudgeScoresAreBounded`, `invariant.OneJudgeScorePerCompletion` (must stay
  green); the `ActionableFeedback` guarantee is prose (`allium plan` emits no obligation for it) and is covered by the
  slice's own per-judge tests.

## Test seams

One red-first test per default judge on the live processor path asserting the dimensions of the emitted score event;
the existing async judge suites; the consolidator suites.

## Blocked by

None — can start immediately (independent of RR-28 and RR-29).

## Handoff plan

`docs/build-timeline/handoff-plan/RR30-default-judges-dimension-feedback-HANDOFF.md`

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
- Rubrics, prompts, the discrete scale gating, judge weights and aggregation, the heuristic-structural and custom
  judge dimension logic.

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
