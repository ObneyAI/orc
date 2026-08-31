# RR-4: Resume state and the iteration record become separate facts

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

A campaign currently writes its entire history into every checkpoint, and the terminal checkpoint embeds a result
that repeats that history again — so durable cost grows with the square of iterations. It also forces the trace assembler
to reconcile two competing accounts of the same iteration, where the poorer one wins.

Split them, because they are different kinds of thing. **Resume state** is what a campaign needs to continue — current
sandbox values, accumulated usage and timing, remaining budgets, frontier position — and is superseded every quantum. An
**iteration record** is written once when an iteration completes and is never rewritten.

This is the slice that unblocks the default-on flip: a default must not ship quadratic writes.

## Acceptance criteria

- [x] Durable bytes for an N-iteration campaign grow linearly in N, demonstrated by measurement not assertion
- [x] An iteration record is written exactly once per completed iteration and never rewritten
- [x] Repeated attempts at one iteration are separately recorded, not collapsed last-wins
- [x] Resume reconstructs prompt history from iteration records rather than from a blob carried in state
- [x] The trace no longer merges two sources; the richer per-iteration content survives
- [x] An unsupported checkpoint value names the key and unsupported value kind without stringifying it
- [x] A later unsupported checkpoint value fails only that iteration and preserves every previously committed iteration record and resumable frontier

## Verified result

Version-2 campaign persistence now writes `:rlm/researcher-iteration-recorded`
and `:rlm/researcher-resume-state-saved` as separate facts in one guarded
append. Exact replay and stale frontier writes are atomic no-ops, while a
distinct retry at the same iteration retains its own attempt ordinal. Version-1
checkpoint reads remain available for migration, but new checkpointed execution
writes version 2 and reconstructs prompt history from ordered iteration records.

The serialized-event measurement used equal-sized campaigns at N=2, 4, and 8:
12,584 bytes / 14 events; 20,199 bytes / 22 events; and 35,639 bytes / 38 events.
Observed bytes per iteration decreased from 6,292.0 to 5,049.75 to 4,454.875,
which demonstrates bounded incremental growth rather than a repeated-history
series.

The focused checkpoint suite passes 15 tests / 109 assertions. The combined
checkpoint, recursive-researcher, and trace proof passes 78 tests / 409
assertions. `clojure -M:poly test brick:orc-service` completed with exit code 0
in both Polylith project contexts. Provider-facing integration tests that require
external credentials remained gated; the exercised provider was the injected
deterministic capability.

Obligation audit: `10 obligations, 9 covered, 1 uncovered`.

The uncovered obligation is `entity-fields.CampaignIteration`: RR-4 establishes
the immutable identity, status, and storage boundary, while RR-5 owns the full
record content (`started_at`, `completed_at`, duration, shape, tree/code,
bounded reasoning/error, and variable-key deltas). This is an aspirational
downstream gap, not a claim that RR-4 already supplies the living-description
evidence payload.

## Spec obligations covered

- `transition-edge.Campaign.running.yielded`
- `transition-edge.Campaign.yielded.running`
- `rule-success.CampaignYieldsQuantum`
- `rule-failure.CampaignYieldsQuantum.1`
- `rule-success.CampaignIterationSucceeds`
- `rule-success.CampaignIterationFails`
- `rule-success.CampaignIterationTimesOut`
- `invariant.OneIterationRecordPerAttempt`
- `transition-terminal.CampaignIteration.status`
- `entity-fields.CampaignIteration`

## Test seams

Seam-1 (public execution via `with-async-test-context`) for behaviour, Seam-3 (durable evidence: event-store reads) for the durable shape, and a byte measurement over a multi-iteration campaign.

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
