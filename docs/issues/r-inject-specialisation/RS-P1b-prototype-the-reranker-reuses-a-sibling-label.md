# RS-P1b — *Prototype:* the reranker reuses a sibling's domain label when one fits

## Parent

Grill decision D7 (`docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md`). Follows RS-P1, whose
findings showed labels are semantically stable but lexically loose (5 of 21 identical across passes).

## What to build

A feasibility gate, not a slice. D7 rests on one number: how often the reranker, shown the parent's existing domain
children labels, reuses one for a task in that domain instead of coining a variant. Run pass one as RS-P1 did to mint
labels per top candidate; run pass two with each candidate carrying the labels minted under it in pass one and the
instruction requiring reuse when one fits; measure, over the tasks whose top candidate carries siblings, the share that
reused a sibling label exactly. Also apply D7's tightened `covered` definition (subject matter, material, output kind)
and report the verdict distribution beside RS-P1's. Record the findings beside RS-P1's.

## Acceptance criteria

- [ ] Pass one and pass two persisted, pass two with sibling labels supplied per candidate
- [ ] Sibling-reuse rate over eligible tasks, verdict distribution under the tightened definition, and any remaining
      flips recorded in a findings file
- [ ] A written verdict on whether a judged choice converges labels well enough for D3's derived identity, and what
      RS-1 / RS-2 must carry if not

## Spec obligations covered

None (a prototype; informs RS-1 and RS-2).

## Test seams

The live probe only.

## Blocked by

None — can start immediately.

## Handoff plan

Orchestrator-run (HITL prototype).

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
- **No regex or phrase matching over model-authored prose — in tests or in production.** Assert on structured data the
  runtime emits: typed reranker fields, event bodies, identities, concept-graph edges, rendered values the test itself
  injected. The reranker is stubbed with a typed payload the test constructs; only the live sweep hears the real model.
- **Fitness keeps meaning shape-and-intent fit.** Coverage is a separate discrete verdict; nothing reads a domain
  judgement off the fitness number or off any similarity threshold.

## Do NOT touch

- Nothing in `components/`: the probe lives under `development/`.

## Report back

The two passes, the reuse rate, the verdict distribution, the verdict.
