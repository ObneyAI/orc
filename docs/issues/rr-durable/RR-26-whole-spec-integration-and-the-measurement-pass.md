# RR-26: Whole-spec integration and the measurement pass

**Type:** HITL

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

The standing final slice. Not a vertical slice — it exists because the cross-cutting proof is nobody's slice by
construction, and would silently never run.

**Convergence:** a full `/weed` over all three specs with every divergence classified; cross-entity propagate tests;
`allium check` and `analyse` error-free by severity count; no blocking open questions in the touched area.

**Seam-7 — concurrency.** Two workers racing one frontier. There is no prior art: the platform's own conformance test for
its fencing invariant calls the handler twice sequentially in one thread with a no-op body, which proves checkpoint dedup
and not mutual exclusion over effects. The fence claim is unfalsifiable without this.

**Seam-8 — live proof, orchestrator SOLO.** A real provider, a multi-quantum campaign, and a **real process kill**
mid-campaign. It must resume automatically, complete, and not repeat a child that already succeeded. A gated
single-process journey exists; this extends it to the restart the arc actually claims.

**Measurements written back as config:** sandbox growth, iteration-record volume, the evidence density constant for the
new observation kind, worst-case quantum duration, and the convergence ratio's real distribution. Guessing these is what
produced the 6.4 MB reflection failure; the existing constants are documented as measured, with the explicit rule that
under-prediction is fixed with new anchors and never a bigger constant.

## Acceptance criteria

- [ ] Full `/weed` clean across all three specs, every divergence classified
- [ ] Two racing workers produce one claim; the loser fires no effect
- [ ] A real process kill mid-campaign resumes automatically and completes without repeating a succeeded child
- [ ] Duplicate provider spend across the kill is attributable to a specific epoch transition
- [ ] All five measurements recorded and written into the specs as config
- [ ] The convergence gate's threshold is chosen from observed data, and only then allowed to block
- [ ] No blocking open questions remain in the touched area

## Spec obligations covered

- `invariant.OneClaimPerActionIdentityPerEpoch`
- `invariant.ClaimsNeverExceedTheirCampaignsEpoch`
- `transition-terminal.Campaign.status`
- `transition-terminal.EffectClaim.status`
- `contract-signature.CheckpointedResearcherExecution.inspect_iterations`

## Test seams

Seam-7 (concurrency — NEW) and Seam-8 (gated live provider — NEW, orchestrator SOLO) — both built here. Plus a full pass over Seam-1 (public execution via `with-async-test-context`)–Seam-5 (judge runtime).

## Blocked by

None — can start immediately.

## Handoff plan

**Orchestrator SOLO.** Not dispatched to a subagent: this is the falsification pass, and the person who built a thing is the wrong person to try to break it. Handoff is written last, from the whole landed arc.

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
