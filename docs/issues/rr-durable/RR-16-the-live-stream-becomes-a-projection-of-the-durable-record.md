# RR-16: The live stream becomes a projection of the durable record

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

Two parallel accounts of a campaign exist and neither survives what we are building: ephemeral emits that store
nothing and no-op without a subscriber, and durable events emitted only at the very end. Mid-campaign there is nothing
durable to look at, and after a restart the live view is gone entirely.

They have already drifted — one event's documentation claims it fires per emitted tree when it fires once per campaign.

Once the iteration record exists, the durable store already contains an ordered account of a running campaign. Make the
live stream a **projection** of it, so what an observer watches and what the system reads back are the same thing. Retire
the terminal-only iterations event; it has no production consumer. Link lineage on the replay path too, or the projection
has a hole exactly where a restart happened.

Progress finer than one iteration stays ephemeral — a preview nothing is entitled to rely on.

## Acceptance criteria

- [ ] A subscriber watching a campaign sees iterations derived from durable records
- [ ] What was watched live matches what is read back afterwards, asserted by comparison
- [ ] Watching survives a restart
- [ ] A rejoined child appears in the lineage
- [ ] The terminal-only iterations event is retired and its documentation corrected
- [ ] Sub-iteration progress remains ephemeral and is documented as non-authoritative

## Spec obligations covered

- `entity-fields.CampaignIteration`
- `invariant.OneIterationRecordPerAttempt`

## Test seams

Seam-1 (public execution via `with-async-test-context`) with a live subscriber, Seam-2 (restart: stop processors, reopen store, restart processors) for survival across restart, Seam-3 (durable evidence: event-store reads) for equivalence.

## Blocked by

RR-5.

## Handoff plan

**Handoff is crafted AFTER RR-5 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the iteration record's read API and ordering guarantees
  - the record's field names, which the projection maps onto stream envelopes

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
