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

- [x] Every score emitted by a default LLM judge on the live path carries at least one dimension named in that judge's
      own vocabulary, with its evidence and the judge's score
- [x] The heuristic-structural and custom judges' dimensions are unchanged
- [x] The consolidator's reading of judge scores keeps working (its suites green)
- [x] Score, feedback and model provenance on every emitted event are unchanged
- [x] The emitted event still validates against the score schema; one score per completion still holds

## Spec obligations covered

- `entity-fields.JudgeScore`, `invariant.JudgeScoresAreBounded`, `invariant.OneJudgeScorePerCompletion` (must stay
  green); the `ActionableFeedback` guarantee is prose (`allium plan` emits no obligation for it) and is covered by the
  slice's own per-judge tests.

## Verification

Every score a default LLM judge emits on the live processor path now carries one named dimension built from the
judge's own evidence lists (grounded against ungrounded claims, requirements met against missed, reasoning strengths
against weaknesses, aspects covered against missing), carrying the judge's score and a weight of 1.0; empty lists still
yield the dimension with feedback saying nothing was cited. Score, feedback and model provenance pass through
unchanged, the heuristic-structural and custom judges are byte-identical, and every emitted event validates against
the score schema. The implementer drove each judge red-first through the live path (sheet, declared judge, node,
completion, stubbed provider, read-back of the emitted event): four RED/GREEN cycles plus a guard.

Independent inspection re-read the diff, verified the projected evidence keys against the judges' real output schemas,
spot-checked the RED and GREEN logs, and re-ran the suite with every evaluation namespace and the ontology consumers.
Live QA then found a defect the unit tests could not: the brief's example dimension names ("Grounding", "Instruction
following") were not the repo's existing vocabulary, and the ontology classifier's dictionary is case-sensitive, so the
first live run of the real-model end-to-end test produced a failure record with no URI and failed in its
classification phase. The orchestrator fixed it red-first: one vocabulary map derived from the tier-1 rubric names
("Source Grounding", "Instruction Following", "Reasoning Quality", "Completeness") now feeds both the synchronous
aggregate and the live projection, and a cross-component guard classifies each default judge's dimension through the
ontology classifier. The second live run passed (1 test / 64 assertions, exit 0) with three named dimensions
classifying to real failure concepts. The classifier's own hole — a URI-less failure for any unknown dimension name —
is recorded and folded into grill Q6 rather than patched here.

Coverage `3 obligations, 3 covered, 0 uncovered` (`entity-fields.JudgeScore`, `invariant.JudgeScoresAreBounded`,
`invariant.OneJudgeScorePerCompletion`, all still green); the `ActionableFeedback` guarantee is prose and is covered by
the per-judge tests. Focused re-run 183 tests / 740 assertions, 0 failures. On the final tree the complete two-project `orc-service` brick passes with exit 0 in 57 minutes 49 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (130 namespaces, 1060 tests / 5924 assertions per graph, 0 failures, 0 errors), and the evaluation brick passes in both of its projects, `orc` and `orc-evaluation` (11 namespaces, 126 tests / 531 assertions per project, 5 minutes 45 seconds) — the first time the `orc-evaluation` project has resolved and run, after its `cat/schema-util` pin was aligned with the other projects. Allium holds at 114 information
diagnostics, 35 warnings, 0 errors, 0 analyse findings; no `.allium` change; no weakened test. Five stale Grain pins
(the evaluation, GEPA, ColBERT and MCP projects and the GEPA component, still at the pre-recovery SHA) surfaced when
the evaluation brick's project failed to resolve after its tests had passed, and were moved to the arc's pinned SHA.
Registered as DET-E2E-292.

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
