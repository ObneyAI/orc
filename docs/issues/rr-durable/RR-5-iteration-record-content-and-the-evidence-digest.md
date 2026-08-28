# RR-5: Iteration record content and the evidence digest

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

Give the iteration record the content the loop and the judges actually need: iteration index and attempt, status,
duration, the shape of the tree it emitted, the tree itself, the campaign's own code, error class with a bounded excerpt,
variable deltas as key lists, and bounded reasoning.

What is **reduced** is data payload values — they become keys and profiles, retrievable by drill-down. The reflection
window died once at 6.4 MB and the cause was blackboard payloads riding inputs into writes and growing within a session,
not code. Code is kilobytes and does not accumulate; it stays.

## Acceptance criteria

- [x] Each iteration record carries index, attempt, status, duration, shape, tree, code, bounded error and reasoning, and variable deltas as key lists
- [x] Data payload values are reduced to keys and profiles; no raw payload enters the record
- [x] A bounded excerpt is bounded by a named, measured limit — not an arbitrary constant
- [x] Resumed prompt history names the keys created, updated and removed by each prior iteration without embedding their payload values
- [x] The record is sufficient for a judge to say WHY an outcome happened, demonstrated by a judge reading one
- [x] Record size per iteration is measured and reported, not assumed

## Spec obligations covered

- `entity-fields.CampaignIteration`
- `entity-optional.CampaignIteration.duration`
- `entity-optional.CampaignIteration.emitted_shape`
- `entity-optional.CampaignIteration.error`
- `invariant.RecordedTreesCarryTheirShape`
- `invariant.SuccessfulIterationsRecordTheirCode`

## Test seams

Seam-3 (durable evidence: event-store reads) primary; Seam-1 (public execution via `with-async-test-context`) for the end-to-end shape.

## Blocked by

RR-4.

## Handoff plan

**Handoff is crafted AFTER RR-4 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the iteration-record write API and its field names
  - how a record is addressed for read-back (tick/node/iteration/attempt)
  - the resume-state boundary, so the digest does not duplicate it

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
