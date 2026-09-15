# RR-15: Checkpointing on by default

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

Flip the default: recursive campaigns are checkpointed unless a node explicitly opts out.

**This slice is deliberately last in the spine.** The moment checkpointing is the default, every defect that previously
reached only opt-in users becomes a default-path defect. It is the reward for the repairs, not the start of them — and it
must not land while write amplification, recovery, the fence, or budget survival are still open.

Opting out must preserve today's behaviour exactly: same single-invocation execution, same configuration meaning, same
result shape.

## Acceptance criteria

- [x] A recursive campaign is checkpointed with no configuration
- [x] An explicit opt-out reproduces today's behaviour exactly, verified against a recorded baseline
- [x] Durable cost per campaign is measured at the default and is within the budget set by RR-4 and RR-14
- [ ] Every spine slice is landed and inspected before this merges — verified, not assumed
- [x] Documentation describes the default and the opt-out, and no longer describes an opt-in flag

The unchecked merge gate is deliberate. The complete RR-9 through RR-15 stack
is implemented and independently verified against the local Grain checkout at
`/private/tmp/orc-rr8-grain-recover3`, but RR-8's live-lease dependency remains
unmerged in Grain PR #22. That PR is open at `47073a2` with all five checks
successful. RR-15 must not be represented as portable or merge-ready until the
dependency lands and the ORC pins are replaced with the landed revision.

## Spec obligations covered

- `contract-signature.CheckpointedResearcherExecution.execute_quantum`
- `contract-signature.CheckpointedResearcherExecution.resume_campaign`

## Test seams

Seam-1 (public execution via `with-async-test-context`) for both modes, Seam-2 (restart: stop processors, reopen store, restart processors) for the default path, plus a cost measurement.

## Blocked by

RR-4, RR-14, and the whole durable spine.

## Handoff plan

**Handoff is crafted AFTER RR-4 and RR-14 (and gated on the whole spine) lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the measured durable-cost figures from RR-4 and RR-14, which set the budget this flip must meet
  - the opt-out configuration key as actually implemented

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

## Implementation and verification record

One shared mode predicate now makes omitted `:checkpointed?` select bounded
checkpoint execution for recursive researchers while treating an explicit
`false` as authoritative. Terminal mode remains non-checkpointed when the key
is omitted. The executor, todo dispatch, descendant execution tags, recovery
scan, and runtime resume all consume that same decision.

The propagated public contract was RED before implementation: the initial
focused run reported 5 tests, 34 assertions, 2 failures because omission still
selected the single-invocation path and produced no resumable campaign. The
final focused namespace passes 5 tests and 42 assertions. It proves bounded
default execution, a real SQLite close/reopen of the same campaign, the exact
explicit-false compatibility result and event boundary, terminal omission,
and omitted-default versus explicit-true durable cost.

The paired cost fixture produced the same 22 events, identical event-type
frequencies, and 19,659 normalized canonical bytes in both checkpointed modes.
Raw serialization was 18,316 bytes for omission and 18,336 bytes for the
explicit authoring flag. The default selection therefore adds no durable event
or canonical byte beyond the already-qualified mechanism.

Independent verification passed:

- checkpoint/default/recovery/budget/handover/cancellation affected set: 106
  tests, 822 assertions;
- repaired consumer-gate namespace: 16 tests, 75 assertions;
- recursive RLM compatibility namespace: 54 tests, 234 assertions;
- combined final RR-15/stale-seam focused proof: 75 tests, 351 assertions;
- complete `clojure -M:poly test brick:orc-service` run across both consuming
  projects: exit 0 in 62 minutes 57 seconds;
- Allium: `12 specs`, `115 information`, `35 warnings`, `0 errors`; analyse:
  `0` findings;
- `/weed` check mode and obligation audit: `2 obligations, 2 covered, 0 uncovered`.

No generated mock, stub, TODO, or skeleton remains. The broad run exposed two
stale test seams rather than production defects. The consumer-gate test still
read the pre-RR-4 embedded iteration vector and pre-RR-5 `:error` field; it now
uses the public immutable iteration-record projection and its bounded
`:error-excerpt`. Six direct executor fixtures asserted legacy multi-turn
single-invocation behavior without declaring that mode; they now explicitly
set `:checkpointed? false`. Neither repair weakens an assertion or changes the
default-path contract.

Two harness mistakes were also ruled out and recorded rather than hidden. A
first broad attempt used stale root-project Grain coordinates instead of the
composed local checkout; both consuming project pins were corrected before the
green proof. One focused rerun mistakenly passed `-n` to an alias without a
test runner and failed before executing tests; the corrected `clojure.test`
invocation passed. DET-E2E-243 remains open for the separate production latency
qualification, live-lease portability remains outside this slice, and
RR-26 still owns representative snapshot-cadence calibration.

## Merge gate — closed 2026-09-15 (RR-34)

Grain PR #22 was dropped by its maintainer rather than merged. Every pin now points at Grain `main`; the gate this slice
kept open no longer waits on anything upstream. See RR-34 for what was retired with the PR and why ORC's correctness
never depended on it.
