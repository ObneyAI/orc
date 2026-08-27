# RR-10: Indeterminate effects resolved by callee participation

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

On resume, a claim with no recorded outcome means the effect may or may not have happened. Resolve it by who owns
the effect, because that is what determines what is possible.

A generated child is **rejoined** by its stable identity and never repeated. A checkpoint-safe tool and a behavior mint are
**re-attempted under the same identity**, which their owner deduplicates. A provider call is **re-attempted as a further
attempt of the same logical action** — and the indeterminacy is recorded for the operator but **not** surfaced to the
model. The model did not fail; our process did, and feeding that into its reasoning history teaches it a false lesson
about its own work.

## Acceptance criteria

- [ ] A child with an unresolved claim is rejoined, not re-run
- [ ] A tool or mint is re-attempted under the same identity and its owner deduplicates
- [ ] A provider call is re-attempted as a new attempt under a newer epoch, with the prior claim left indeterminate
- [ ] The model's prompt history shows one iteration; the durable record shows both attempts
- [ ] Every indeterminate call is queryable and attributable to the epoch that made it

## Spec obligations covered

- `rule-success.ClaimedEffectBecomesIndeterminate`
- `rule-failure.ClaimedEffectBecomesIndeterminate.1`
- `transition-edge.EffectClaim.claimed.indeterminate`
- `transition-terminal.EffectClaim.status`
- `invariant.ResolvedClaimsRecordWhenTheyResolved`

## Test seams

Seam-2 (restart: stop processors, reopen store, restart processors) for the crash window, Seam-3 (durable evidence: event-store reads) for attributability.

## Blocked by

None — can start immediately.

## Handoff plan

**Handoff is crafted AFTER RR-7 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the claim record's status transitions and how an unresolved claim is found on resume
  - the content-derived identity, so a re-attempt is recognisably the same logical action

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
