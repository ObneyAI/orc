# RR-14: Sandbox delta with periodic full snapshots

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

Even after the history split, sandbox state is written in full on every checkpoint, so cost grows with sandbox size
times iterations. The delta is already known — created and updated variables are computed every iteration — so carry only
what changed and fold on resume.

A fold is more fragile than a snapshot: one missing event and the sandbox is wrong. Persist value-bearing deltas plus
periodic full snapshots, and verify an explicit predecessor/revision/hash chain before exposing reconstructed state.

Snapshot cadence is counted in **durable resume revisions**, not logical iterations. Retry and timeout checkpoints advance
the durable chain without advancing the logical-iteration frontier, so an iteration-based cadence cannot bound replay.
The interval is a configuration seam in this slice. Its compatibility default is `1` (a full snapshot every revision)
until RR-26 measures representative real campaigns and writes the calibrated value back into the spec; RR-14 must not
turn synthetic prototype numbers into a production constant.

Only `:sandbox-vars` is delta-encoded here. The remaining resume-state fields, including variable-creation metadata, stay
complete in each resume fact. The fold continues to expose a hydrated full V2-shaped resume state to the executor, while
the durable event encoding advances to a new version and remains able to read the existing full-state formats.

## Acceptance criteria

- [x] A checkpoint carries only changed sandbox values between snapshots
- [x] Resume reconstructs sandbox state identically to a full-snapshot resume, proven by comparison
- [x] A missing or corrupt delta fails loudly rather than silently producing a wrong sandbox
- [x] Snapshot cadence is configurable by durable resume revision; the compatibility default is one until RR-26 calibrates it
- [x] Existing V1/V2 full checkpoints remain readable while the new writer emits the versioned full/delta encoding
- [x] Durable bytes for a large-working-set campaign are measured before and after

## Spec obligations covered

- `entity-fields.Campaign`
- `enum-comparable.CampaignResumeFactKind`
- `when-presence.CampaignResumeFact.predecessor_revision`
- `when-presence.CampaignResumeFact.predecessor_state_hash`
- `when-presence.CampaignResumeFact.sandbox_snapshot`
- `when-presence.CampaignResumeFact.sandbox_puts`
- `when-presence.CampaignResumeFact.sandbox_deletes`
- `entity-fields.CampaignResumeFact`
- `entity-optional.CampaignResumeFact.predecessor_revision`
- `entity-optional.CampaignResumeFact.predecessor_state_hash`
- `entity-optional.CampaignResumeFact.sandbox_snapshot`
- `entity-optional.CampaignResumeFact.sandbox_puts`
- `entity-optional.CampaignResumeFact.sandbox_deletes`
- `rule-success.CampaignSavesInitialSandboxSnapshot`
- `rule-failure.CampaignSavesInitialSandboxSnapshot.1`
- `rule-failure.CampaignSavesInitialSandboxSnapshot.2`
- `rule-failure.CampaignSavesInitialSandboxSnapshot.3`
- `rule-entity-creation.CampaignSavesInitialSandboxSnapshot.1`
- `rule-success.CampaignSavesPeriodicSandboxSnapshot`
- `rule-failure.CampaignSavesPeriodicSandboxSnapshot.1`
- `rule-failure.CampaignSavesPeriodicSandboxSnapshot.2`
- `rule-failure.CampaignSavesPeriodicSandboxSnapshot.3`
- `rule-entity-creation.CampaignSavesPeriodicSandboxSnapshot.1`
- `rule-success.CampaignSavesSandboxDelta`
- `rule-failure.CampaignSavesSandboxDelta.1`
- `rule-failure.CampaignSavesSandboxDelta.2`
- `rule-failure.CampaignSavesSandboxDelta.3`
- `rule-entity-creation.CampaignSavesSandboxDelta.1`
- `invariant.SandboxResumeFactsFormOneVerifiedChain`
- `invariant.SandboxResumeReconstructionIsExact`
- `invariant.SandboxSnapshotCadenceBoundsReplay`
- `invariant.ResumeStateEncodingRemainsReadCompatible`

## Test seams

Seam-2 (restart: stop processors, reopen store, restart processors) for fold-correctness across a real restart, plus a byte measurement.

## Calibration prototype finding

The throwaway terminal prototype compared full snapshots with value-bearing `puts`/`deletes` deltas using actual
canonical EDN byte counts. It proved exact reconstruction for intact chains and distinct loud rejection for a missing
record, payload corruption, and a semantically invalid predecessor revision whose hashes had been recomputed. With retry
checkpoints present, durable-resume-revision cadence retained the `K - 1` replay bound while logical-iteration cadence did
not. The byte results were synthetic feasibility evidence only and selected no production K.

## Deferred calibration

RR-26 owns representative live-campaign measurement and the resulting nontrivial default interval. That is the locked
G18 decision: RR-14 builds and proves the mechanism; the whole-spec integration pass chooses the number from real data.

## Blocked by

RR-4.

## Handoff plan

**Handoff is crafted AFTER RR-4 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the resume-state write API and the boundary between it and the iteration record
  - how resume currently reads state, so the fold slots into one read rather than adding another

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

## Implementation and verification report

The checkpoint command continues to accept a complete hydrated V2 resume state,
while the durable writer now emits V3 resume facts. Non-sandbox fields remain
complete. Sandbox state is represented by an initial or cadence-selected full
snapshot and value-bearing `puts`/`deletes` between snapshots. Each delta names
its exact predecessor revision and state hash, and every V3 fact records a
SHA-256 hash of a typed, traversal-order-independent canonical sandbox value.
The public read boundary reconstructs the existing complete V2 shape from the
latest full snapshot and its suffix rather than exposing the storage encoding.

The TDD loop covered initial full encoding, durable-revision cadence, deletes,
corrupt chains, legacy transition, public workflow configuration, positive
interval validation, real SQLite restart, and bounded replay. Independent weed
inspection then found a code bug: malformed V3 facts could mix or omit
full/delta fields because hydration stripped extra keys and treated missing
collections like empty ones. DET-E2E-276 first reproduced four invalid shapes,
then a shared predicate made both the registered event schema and defensive
hydration reject them. No test was weakened to admit the implementation.

The focused namespace passed 11 tests and 41 assertions. Its SQLite case closes
the first context, reopens the same store with a fresh cache, and reads the
exact hydrated V2 state from a persisted full-plus-delta chain. The affected
RR14, checkpointed-researcher, blocked-recovery, and effect-claim set passed 84
tests and 728 assertions. For 128 sandbox values of 256 bytes with one changed
value, canonical EDN measured 38,923 bytes for one full snapshot plus one delta
versus 73,089 bytes for two full snapshots, a 46.7% reduction. A five-revision
chain with `K = 3` proved that the public cold read hydrates the latest snapshot
and at most `K - 1` following deltas.

`32 obligations, 32 covered, 0 uncovered`. `/weed` found no remaining RR-14
spec/code divergence after the conditional-shape fix. No generated mock, stub,
TODO, or skeleton replaces a durable boundary. The compatibility default
remains `K = 1`; choosing a nontrivial production cadence from representative
campaigns is the ratified RR-26 calibration responsibility, not an untracked
gap in this slice.

The final `clojure -M:poly test brick:orc-service` inspection gate exited zero
after 61 minutes 57 seconds. The first broad attempt exposed a harness
dependency mismatch rather than an RR-14 failure: Poly's `orc` project used the
locally recovered Grain lease/drain APIs, but its standalone `orc-service`
project still selected Grain SHA `47073a2820f571b31fe784311886613a9ed3297e`.
Aligning those project graphs made the previously failing standalone
bounded-campaign namespace pass 32 tests / 244 assertions; the subsequent
map-each recovery namespace passed 7 / 77 in the same canonical run.
