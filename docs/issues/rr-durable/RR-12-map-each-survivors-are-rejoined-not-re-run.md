# RR-12: Map-each survivors are rejoined, not re-run

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

The fan-out coordinator is process-local, and its own comment records the assumption that made that acceptable: the
parent tick would time out and be restarted. Under checkpointing the parent **resumes**, so that assumption is void — a
restart mid-fan-out leaves a child with no coordinator, nothing delivers its completion, and the campaign waits out the
full timeout before retrying the entire map.

Concretely: 27 of 30 chunks done, process dies, and we burn the timeout and then re-pay for all 30. Advertising
resumability while the most expensive thing in the system is not resumable is the defect.

Rebuild the coordinator from durable child completions on resume — pending is declared items minus completed items. This is
the same pattern already established one level up for rejoining a completed child tree, applied one level down.

## Acceptance criteria

- [ ] A restart mid-fan-out rejoins completed items rather than re-running them
- [ ] Pending items are derived from what durably completed, not from process memory
- [ ] Reconstruction dedupes on the item's execution context, so a retried item does not create a second slot
- [ ] Failure indices and reasons survive the restart, so the next iteration's repair logic still has its input
- [ ] A partial outcome after a restart is indistinguishable from one without a restart

## Spec obligations covered

- `rule-success.CampaignIterationSucceeds`
- `invariant.OneIterationRecordPerAttempt`

## Test seams

Seam-2 (restart: stop processors, reopen store, restart processors) is the primary seam — restart mid-fan-out. Plus Seam-1 (public execution via `with-async-test-context`) for partial semantics.

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
