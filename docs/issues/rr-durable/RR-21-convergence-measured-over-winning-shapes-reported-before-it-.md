# RR-21: Convergence measured over winning shapes, reported before it gates

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

The convergence measure counts distinct shapes across **every** tree a class ever emitted, against a denominator of
campaigns — and a campaign emits one tree per repair round. Ten typical campaigns therefore produce a ratio far above the
threshold, and the gate can never pass. It is invisible today only because the numerator has always been zero: the measure
has never once been witnessed on real data, so it passes vacuously.

It is also perverse in the wrong direction — a model that recovers well from a bad first tree scores as *less* convergent
than one that fails outright.

Measure the shapes that **won**: one per successful campaign, the terminal shape that carried it to success. The ratio is
distinct successful terminal shapes divided by successful campaigns. Failed and timed-out campaigns remain recurrence,
quality and weakness evidence, but they enter neither side of the coherence ratio and cannot make it easier to pass.

And roll it out **report-only** first. A threshold that has never fired cannot be distinguished from one that never will:
harvesting nothing looks identical to nothing qualifying. Compute it, report it, and only gate on it once its real
distribution has been observed.

## Acceptance criteria

- [ ] Convergence counts one winning shape per successful campaign
- [ ] The reported ratio is distinct successful terminal shapes divided by successful campaigns
- [ ] Failed, timed-out, cancelled and abandoned campaigns do not enter either side of the ratio
- [ ] A campaign that repairs is not penalised for the shapes it abandoned
- [ ] The measure is computed and reported without blocking promotion
- [ ] The gate report distinguishes 'did not qualify' from 'not yet measurable'
- [ ] The observed distribution is recorded, so a calibrated threshold can be chosen from data

## Spec obligations covered

- `entity-optional.CampaignIteration.emitted_shape`
- `rule-success.ReportSuccessfulShapeCoherence`

## Test seams

Seam-4 (ontology consumers over a synthesized event stream) primary.

## Blocked by

RR-20.

## Handoff plan

**Handoff is crafted AFTER RR-20 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - how a winning shape is identified from the outcome-keyed claim RR-20 produces
  - the per-shape claim keying, which the distinct count reads

The orchestrator then runs `/propagate` scoped to the obligations above, confirms RED, and seeds the TDD cycle list.

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

- `specs/*.allium` — report divergences with a proposed classification (spec bug / code bug / aspirational design /
  intentional gap); never edit. The orchestrator is the only spec writer.
- Any slice not named in this brief. If you find a defect outside your slice, report it; do not widen the diff.
- `docs/prd/`, `docs/adr/`, the grill log — read-only inputs.
- Other worktrees under `~/Desktop/Code/orc*` — other work is live in them.

## Report back

- The `/propagate` coverage line verbatim: `N obligations, M covered, K uncovered`, with a reason for every
  uncovered one (infrastructure gap / unmappable / out of slice). No silent caps.
- Any propagate-emitted mock, stub or TODO skeleton, declared explicitly as a tracked gap.
- Proposed divergence classifications for anything where spec and code disagree.
- What you could NOT verify, stated plainly.

## Orchestrator gates (run after you report)

`/inspect-orc` against this slice: re-run the proof independently, re-read the code, try to break the claims, plus the
three ORC-specific gates — spec-conformance (`allium check` / `analyse` error-free by **severity count** on touched
specs), `/weed` check-mode with classified divergences, and the obligation audit (the coverage line above must survive
into the slice report). Allium's internal verify is the CLAIM; `/inspect-orc` is the falsification.
