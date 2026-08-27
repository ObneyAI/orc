# RR-23: Tag-scope the hot evidence queries

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

Several of the loop's hottest reads scan every event of a type for the tenant. The promotion path does two such
scans before its cheap gate and six past it, per occurrence event; the reflection path does eight; and the duplicate-score
check does one **per score dispatched**, over the fastest-growing event type in the system.

Three of the four already have the tag they need and can be scoped with no producer change. The fourth cannot: the tree
bookend is tagged by the throwaway execution it ran in rather than by the campaign that produced it, so it cannot be found
by source at all. Add that tag at the emit site.

The missing tag is the same class of defect as the dead join already found there — the bookend is identified by its
ephemeral execution rather than by what it belongs to. Fixing it makes the bookend consistently addressable by source,
which is what every consumer actually wants.

## Acceptance criteria

- [ ] The promotion and reflection scans are scoped by tag rather than by type alone
- [ ] The duplicate-score check is scoped to its execution rather than scanning all scores
- [ ] The tree bookend carries a tag identifying the campaign that produced it
- [ ] Query cost no longer grows with total store size, demonstrated by measurement at two store sizes
- [ ] Results are identical to the unscoped versions — proven by comparison, not assumed

## Spec obligations covered

None. This slice repairs behaviour that predates the campaign model; its proof is the existing suite plus the acceptance criteria above.

## Test seams

Seam-4 (ontology consumers over a synthesized event stream) primary, plus a cost measurement at two store sizes.

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
