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

- [x] Full `/weed` clean across all three specs, every divergence classified
- [x] Two racing workers produce one claim; the loser fires no effect
- [x] A real process kill mid-campaign resumes automatically and completes without repeating a succeeded child
- [x] Duplicate provider spend across the kill is attributable to a specific epoch transition
- [ ] All five measurements recorded and written into the specs as config — three measured deterministically (sweep recorded in the ledger); evidence density and the coherence distribution need live campaigns; `orc-service.allium` has no config block yet (user decision)
- [ ] The convergence gate's threshold is chosen from observed data, and only then allowed to block — stays report-only until the live distribution is observed (user decision)
- [ ] No blocking open questions remain in the touched area — five design questions raised for the user, none blocking the landed behaviour

## Spec obligations covered

- `invariant.OneClaimPerLogicalActionIdentityPerEpoch`
- `invariant.ClaimsNeverExceedTheirCampaignsEpoch`
- `transition-terminal.Campaign.status`
- `transition-terminal.EffectClaim.status`
- `contract-signature.CheckpointedResearcherExecution.inspect_iterations`

## Verification
The whole-spec integration slice closed the arc against the three specs and the live system rather
than against the slices' own claims. `/weed` ran in check mode over `orc-service`, `ontology` and
`evaluation`; every divergence was re-verified by the orchestrator against the spec line and the code
line and classified — five spec bugs tended (the two landed evidence bases, the claim's verdict
corroboration count with the rule that increments it, the mint operation the mutation contract's own
invariant governs, the trace-evidence field names the engine actually threads, and the two
provider-failure fields a transport failure cannot know), four code bugs reproduced RED and fixed, six
intentional gaps recorded, and five design questions raised for the user rather than patched. Allium
re-derived after the tends stays at the characterized baseline of 115 information diagnostics, 35
warnings, 0 errors and 0 analyse findings.

The five obligations map to covering tests already green in the gate — `OneClaimPerLogicalActionIdentityPerEpoch`
(the same-frontier race and the new two-worker race), `ClaimsNeverExceedTheirCampaignsEpoch` (the
frontier fence, the v2-checkpoint fence, the non-cooperative stale owner and the two-worker late write),
`transition-terminal.Campaign.status` (abandon, cancel, terminal-parent, terminal fences, the first-terminal
verdict), `transition-terminal.EffectClaim.status` (indeterminate resolution and the mint callee's retry
rejection) and `inspect_iterations` (the verbatim trace history and the public two-iteration reconstruction);
coverage `5 obligations, 5 covered, 0 uncovered`.

Seam-7 is a new deterministic test: a second execution worker subscribed to the same store and pubsub
receives the one recovery start, both workers reach the frontier claim under a barrier, exactly one epoch
is claimed, the loser dispatches no provider call, checkpoints nothing and completes nothing, and a late
write carrying the epoch it failed to claim is fenced. Seam-8 ran live against the pinned real model with
a SIGKILL rather than a graceful stop; automatic recovery in a clean process finished the campaign from the
same SQLite store with no resubmission (DET-E2E-255). A constrained live deadline (DET-E2E-256) found a real
defect — two deadlines derived from one remainder classified a provider timeout two different ways, and the
transport's exception reaches the engine with its cause flattened to a string — fixed by making the engine's
own deadline fire strictly first while the transport keeps the exact outer bound, proven red→green→live.
The gated live journeys `det-e2e-105/106/252` pass; `det-e2e-105` had read Phase-1 code from an event the
default-on flip stopped emitting and now reads the durable records.

Measurements: iteration records are constant-size (linear volume); full resume snapshots grow with the
working set so total resume bytes grow quadratically (1.55 MB at 100 iterations); a snapshot interval of ten
cuts that 3.8× but raises quantum p95 from 28 ms to 162 ms — recorded with the fixture, not yet written as
config: `specs/orc-service.allium` has no config block, and the coherence threshold and the evidence-density
constant still need live campaigns, so the config decisions are recorded as open for the user.

Gates on the final tree: the complete two-project `orc-service` brick passes with exit 0 in 59 minutes 29 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (129 namespaces, 1060 tests / 5925 assertions per graph, 0 failures, 0 errors); the ontology brick passes in both owning graphs (77 namespaces, 668 tests / 3803 assertions each); the evaluation brick passes (9 namespaces, 116 tests / 480 assertions); Grain's control-plane and todo-processor-v2 brick tests and the SQLite project's tests pass on the recovered Grain tree. Zero orphan JVMs after every run.

Incident: between Sept 11 and Sept 14 macOS's /tmp cleaner deleted the worktree's git link, 38 tracked
files, the pinned Grain checkout and 13 untracked arc files; all were recovered (tracked files from HEAD,
three source files exactly from two agreeing transcripts, ten test namespaces reconstructed from Codex
patch transcripts and validated at exactly the Sept 11 counts, 104 tests / 731 assertions, and the pinned
Grain checkout's unpushed control-plane work replayed onto PR #22's head). The arc's real Grain dependency
is that recovered working tree, which must be committed to the PR #22 branch.

## Test seams

Seam-7 (concurrency — NEW) and Seam-8 (gated live provider — NEW, orchestrator SOLO) — both built here. Plus a full pass over Seam-1 (public execution via `with-async-test-context`)–Seam-5 (judge runtime).

## Blocked by

All preceding RR-Durable slices and both prototypes.

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
