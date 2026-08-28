# RR-9: Drain, and every campaign operation bounded

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

Two halves of making ownership handover reasonable rather than hopeful.

**Drain:** the campaign's in-flight work is not registered with the one cancellation mechanism that exists, so nothing can
stop it on lease loss. Register it, cancel on loss, and delay reassignment beyond worst-case quantum duration. Label this
honestly in the code as probability reduction, not a guarantee — a partitioned or paused worker skips cooperative
cancellation entirely, which is precisely why the epoch fence exists.

**Bounded:** the work done to prepare a campaign's first iteration runs outside the campaign clock entirely and has no
deadline of its own — one measurement recorded 302 seconds. Cancellation is also purely advisory, because it interrupts
rather than closing anything, and no transport-level timeout is set. Bring the preparation inside the clock, add real
transport timeouts, and make worst-case quantum duration a recorded quantity so the reassignment delay has something to be
sized against.

## Acceptance criteria

- [ ] Campaign work is registered and is cancelled on lease loss
- [ ] Parent or operator cancellation records one terminal cancelled campaign with a cause; completed iterations and claims remain inspectable and no recovery path resumes it
- [ ] Reassignment is delayed by a configured interval sized against measured worst-case quantum duration
- [ ] Classification work falls inside the campaign clock and has its own deadline
- [ ] A provider or tool call is bounded at the transport, not only by an advisory interrupt
- [ ] Worst-case quantum duration is measured and recorded, not estimated
- [ ] The drain path is documented in-code as probability reduction, never as a guarantee

## Spec obligations covered

- `invariant.SettledCampaignsRecordWhenTheySettled`
- `invariant.CancelledCampaignsRecordCause`
- `rule-success.CampaignTimesOut`
- `rule-failure.CampaignTimesOut.1`
- `rule-success.CampaignIsCancelled`
- `rule-failure.CampaignIsCancelled.1`
- `transition-edge.Campaign.running.timeout`
- `transition-edge.Campaign.running.cancelled`
- `transition-edge.Campaign.yielded.cancelled`

## Test seams

Seam-1 (public execution via `with-async-test-context`), Seam-2 (restart: stop processors, reopen store, restart processors) for lease-loss cancellation, and a measurement pass for the duration figure.

## Blocked by

RR-8.

## Handoff plan

**Handoff is crafted AFTER RR-8 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the lease-check wiring, so lease loss is observable at all
  - the recovery scan's abandonment decision, which the reassignment delay must not race

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
