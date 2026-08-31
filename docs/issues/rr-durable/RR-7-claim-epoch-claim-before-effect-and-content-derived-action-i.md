# RR-7: Claim epoch, claim-before-effect, and content-derived action identity

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

The fence. Deliberately one slice: the epoch, the claim, and the identity scheme are one mechanism, and shipping
any part alone is a half-built fence that reads as protection while providing none.

A campaign mints a monotonic ownership epoch per frontier into our own event stream, and every durable write is
conditional on it — our store becomes the resource that checks the token, which is the only role we can fill.

Every effect is **claimed before it happens**, under that epoch, and the claim is the same record that makes the effect's
outcome knowable afterwards — one append serving as both fence and evidence. Because the claim is per-effect rather than
per-quantum, a superseded worker's waste is bounded to one in-flight effect.

Logical action identity becomes **content-derived** from tick, node, iteration, effect kind, target and canonical
request or arguments, and is generated inside the durable step. Actions originating in generated code additionally
include that durable source hash; the outer provider call that produces code is keyed by its canonical request/module
inputs and provider/model target, never by its not-yet-produced response. Logical identity excludes attempt and
execution order. Each physical dispatch gets a distinct attempt identity derived from that logical identity, ownership
epoch and attempt ordinal. Execution-order-only identities change on replay and can return a recorded result for a
*different* call, which is a wrong answer rather than wasted spend.

Extends identity to the two effects that have none today: the inline provider primitive and behavior minting.

## Acceptance criteria

- [x] A superseded epoch's write is rejected by the store, not detected after the fact
- [x] Two workers racing one frontier produce exactly one claim per logical action identity per epoch; the loser fires no effect
- [x] Logical action identity is stable across replay and independent of execution order — proven by a test that reorders calls
- [x] Every physical dispatch has a unique attempt identity derived from logical identity, epoch and non-negative attempt ordinal
- [x] A retry retains the logical action identity but does not reuse another physical attempt's identity
- [x] Inline provider calls and behavior mints each carry a logical action identity and are claimed
- [x] Generated children, participating checkpoint-safe tools and mints are exactly-once; provider calls are at-least-once and every indeterminate one is attributable

The targeted contract decision is ratified and proven: a checkpoint-safe
effectful tool participates through the context-aware three-argument caller and
receives the stable logical idempotency key. A two-argument caller remains
compatible outside the checkpointed effect boundary, but a missing or
two-argument caller is rejected before model dispatch, effect claim, or tool
invocation inside it. The public RED proved the two-argument path dispatched
both model and tool and wrote claims. Adversarial inspection then proved an
absent caller could report success after model work and two durable claims. The
GREEN cases prove zero provider calls, tool calls, raw claims, and projected
claims.

## Spec obligations covered

- `invariant.OneClaimPerLogicalActionIdentityPerEpoch`
- `invariant.EffectAttemptIdentitiesAreUnique`
- `invariant.EffectAttemptOrdinalsAreNonNegative`
- `invariant.EffectAttemptIdentityMatchesLogicalAction`
- `invariant.ClaimsNeverExceedTheirCampaignsEpoch`
- `invariant.ClaimsBelongToTheirIterationsCampaign`
- `invariant.ResolvedClaimsRecordWhenTheyResolved`
- `rule-success.EffectIsClaimedBeforeDispatch`
- `rule-failure.EffectIsClaimedBeforeDispatch.1`
- `rule-failure.EffectIsClaimedBeforeDispatch.2`
- `rule-failure.EffectIsClaimedBeforeDispatch.3`
- `rule-failure.EffectIsClaimedBeforeDispatch.4`
- `rule-entity-creation.EffectIsClaimedBeforeDispatch.1`
- `rule-success.ClaimedEffectCompletes`
- `transition-edge.EffectClaim.claimed.completed`
- `entity-fields.EffectClaim`
- `rule-success.CampaignResumesAtFrontier`
- `rule-failure.CampaignResumesAtFrontier.2`

## Test seams

Seam-7 (concurrency — NEW) is mandatory here — this slice is unfalsifiable without it. Plus Seam-1 (public execution via `with-async-test-context`) and Seam-3 (durable evidence: event-store reads).

## Blocked by

RR-P2.

## Handoff plan

**Handoff is crafted AFTER RR-P2 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the compare-and-swap predicate shape the prototype proved viable
  - the per-backend atomicity guarantees the prototype measured
  - the claim record's field names and how a claim is addressed for read-back

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
