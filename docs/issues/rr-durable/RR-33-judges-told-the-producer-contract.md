# RR-33 — Judges are told the producer's contract: declared output fields and a task that is never empty

## Parent

Grill decision D7 (`docs/build-timeline/grill-sessions/rr-weed-followup-decisions.md`). Spec: no change
(`TraceEvidence.instruction` / `outputs` in `specs/evaluation.allium` pin neither framing).

## What to build

The four default LLM judges are handed the node's whole write map as `response` and told it is "the producer's output",
and are handed an empty string as the task when the node has no instruction. Describe the response as what it is: the
producer's declared output fields, one value per field, whose field set the workflow fixed through its typed
blackboard — so a judge grades the values and not the object shape. When a node has no instruction, compose the task
from the judge's declared criteria when present, else from the node's declared writes, so the task is never empty and
the "No instruction provided" sentinel is reached only when nothing at all is known. No data reshaping, no scoring
change, no prompt stance or scale change.

## Acceptance criteria

- [ ] Every default LLM judge module describes `response` as the producer's declared output fields (one value per
      field, field set fixed by the workflow) and no longer as free-form output
- [ ] For a node with an instruction, the task the judge sees is that instruction, byte-identical to today
- [ ] For a node without an instruction and a judge with declared criteria, the task the judge sees is the criteria
- [ ] For a node without an instruction and no criteria, the task names the node's declared writes; the empty string is
      never sent
- [ ] Scores, feedback, dimensions and provenance shapes are unchanged; every existing evaluation suite is green
      unchanged (no assertion weakened)

## Spec obligations covered

- `contract-signature.TraceJudge.evaluate` and `entity-fields.TraceEvidence` must stay green; the framing is not a spec
  obligation and is covered by the slice's own tests plus the orchestrator's live run.

## Test seams

Red-first per judge: capture the module handed to the stubbed provider (the RR-31 criteria tests' pattern) and assert
the response description and the composed task for the three task cases; the RR-31 byte-identical guards must stay
green for the instruction-present case; the orchestrator runs the gated end-to-end test live and checks the
instruction-following judge no longer docks the sentiment node for its shape.

## Blocked by

RR-31 (landed). Independent of RR-32.

## Handoff plan

`docs/build-timeline/handoff-plan/RR33-judges-told-the-producer-contract-HANDOFF.md`

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

- `specs/*.allium`; rubric stances, scales and bands; score gating; weights and aggregation; RR-30's dimension
  projection; RR-31's read resolution and criteria threading (build on them, do not restructure them); the
  heuristic-structural and custom judges; the consolidator.

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
