# RR-P2: PROTOTYPE: claim-epoch compare-and-swap against the real store

**Type:** HITL

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

**Throwaway prototype. Produces a decision, not shippable code.**

The fence depends on a claim epoch that our own event store enforces — the platform exposes no fencing token, and its
compare-and-swap cannot read lease state because predicates are scoped to the appending tenant and cross-tenant reads are
forbidden.

Establish that a compare-and-swap predicate can express "commit only if no higher epoch has claimed this action
identity", atomically with the append, on the stores we actually run: in-memory, SQLite, and Postgres. The Postgres path
takes a per-tenant advisory lock for the whole append, which should make this genuinely linearizable — confirm rather
than assume.

**This can falsify the fence design.** If the predicate cannot express the condition atomically on any backend we ship,
the layer-2 guarantee is unavailable and the decision returns to the grill.

## Acceptance criteria

- [x] A demonstration that the epoch condition commits atomically with the append on each backend
- [x] A demonstration that a superseded epoch is REJECTED, not merely detected afterwards
- [x] A measurement of the added latency per claim
- [x] If any backend cannot express it: an explicit stop with the finding, for re-grilling
- [x] No production code is kept

Finding: [all three shipped backends provide the required atomic CAS boundary](../../build-timeline/prototype-findings/RR-P2-real-store-claim-epoch-cas.md).
No backend failed, so the conditional stop criterion did not fire.

## Spec obligations covered

None. This slice repairs behaviour that predates the campaign model; its proof is the existing suite plus the acceptance criteria above.

## Test seams

Prototype, verified by demonstration against each store implementation.

## Blocked by

None — can start immediately.

## Handoff plan

**Pre-writable now.** Its output gates RR-7.

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
