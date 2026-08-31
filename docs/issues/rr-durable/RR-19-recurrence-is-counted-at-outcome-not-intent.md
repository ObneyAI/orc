# RR-19: Recurrence is counted at outcome, not intent

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

The occurrence event is emitted before the model does any work, and the counter promotion reads is bumped on it — so
a campaign that classifies and then crashes counts toward promotion while contributing no quality signal at all.
Recurrence is counted at intent; quality is counted at outcome.

Count recurrence when a campaign reaches a verdict. A cancelled or abandoned campaign says something about our
infrastructure and nothing about the behaviour: its iteration records remain available for describing what was tried,
but they move no gate. A failed campaign is neither cancelled nor abandoned — failure is a verdict, and learning from
failure is the point.

## Acceptance criteria

- [ ] Recurrence advances only when a campaign reaches success, failure or timeout
- [ ] An abandoned campaign advances no gate
- [ ] An abandoned campaign's iteration records still reach the reflection as descriptive evidence
- [ ] A cancelled campaign advances neither recurrence nor judging, while its completed iteration evidence remains inspectable
- [ ] A failed campaign counts for recurrence and against the quality axes
- [ ] The gate report's occurrence figure matches the number of campaigns that actually reached a verdict

## Spec obligations covered

- `invariant.AbandonedCampaignsRecordNoVerdict`
- `invariant.CancelledCampaignsRecordCause`
- `rule-success.CampaignIsAbandoned`
- `transition-terminal.Campaign.status`

## Test seams

Seam-4 (ontology consumers over a synthesized event stream) primary.

## Blocked by

None — can start immediately.

## Handoff plan

**Pre-writable now.** No dependency on an upstream slice's produced API, so the handoff brief can be written before the arc starts. The orchestrator runs `/propagate` scoped to the obligations above, confirms the generated tests are RED, and seeds the TDD cycle list with them.

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
