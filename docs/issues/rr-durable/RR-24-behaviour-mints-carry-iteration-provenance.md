# RR-24: Behaviour mints carry iteration provenance

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

A minted behaviour records which execution minted it but not **which iteration**, so a behaviour minted confidently on
a first attempt is indistinguishable from one minted as a fourth-attempt fallback. Those carry materially different
evidentiary weight for something every future task may retrieve.

Minting also forces a full corpus reindex unconditionally on every mint, which a replayed iteration re-pays.

## Acceptance criteria

- [x] A minted behaviour records the iteration and attempt that minted it
- [x] Provenance distinguishes a first-attempt mint from a late fallback
- [x] A replayed iteration does not force a second reindex
- [x] Mint identity remains stable — a repeated mint still resolves to one concept

## Spec obligations covered

- `entity-fields.EffectClaim`
- `entity-fields.CampaignIteration`

## Verification

A behaviour minted from a researcher campaign now carries its iteration provenance
explicitly. The sandbox's `mint-behavior!` already claimed the effect before acting
(RR-7) and passed the logical action identity, the attempt identity and the iteration
index to `:ontology/mint-behavioral-subtree`; the minted event now also records
`:attempt-ordinal` and `:ownership-epoch`, and a public accessor
(`ontology/behavior-mint-provenance`, nil for hand-authored, harvested or pre-slice mints) answers the iteration, the attempt, the epoch and whether the mint was a
first attempt — the evidentiary distinction between a confident first-attempt mint and
a late fallback. A replayed iteration re-mints nothing: the logical-action CAS and the recovery of the winning mint (RR-7) already yield one minted event, one description and one forced reindex, which the contract test found already green; the one genuine gap — a processor-level re-delivery of the same minted event re-paying the forced ColBERT rebuild — was reproduced RED and closed with a durable at-most-once marker (`:ontology/mint-reindex-forced`, CAS-fenced on the minted event id) that the forced-rebuild processor claims before rebuilding. Mint identity is unchanged: the concept id is
derived from the name and parent, so every attempt and every replay resolves to the one
concept.

Independent inspection reran the subagent's seams and drove an adversarial probe: a
plain sandbox mint without a logical action answers no provenance and an unknown concept
answers nil; when a later replay with a different attempt and epoch conflicts on the
logical action, the provenance stays the attempt that actually minted; and two direct
deliveries of one minted event to the registered forced-rebuild handler produce exactly
one rebuild. The implementer's mid-cycle regression — attaching the new fields as nil for
pre-slice callers and failing the optional schema — was root-caused and fixed omit-not-nil
before any test was touched.

Focused results on the final tree: the contract namespace 5 tests / 30 assertions, the sandbox-mint, effect-claim, reindex, deterministic-ontology and bounded-campaign seams 107 tests / 779 assertions, the adversarial probe 3 / 6, all 0 failures. The ontology brick passes in
both owning project graphs (668 tests / 3803 assertions in each graph, run solo in fresh JVMs — 6 minutes 20 seconds and 8 minutes 23 seconds). The complete two-project `orc-service`
brick passes with exit 0 in 69 minutes 25 seconds under
`-J-Djava.awt.headless=true` (125 namespaces and 1050 tests / 5869 assertions in each project graph, 0 failures, 0 errors). Allium remains at the
characterized twelve-spec baseline of 115 information diagnostics, 35 warnings, 0
errors and zero analyse findings. `allium plan specs/orc-service.allium` resolves both
issue obligations — `entity-fields.EffectClaim` and `entity-fields.CampaignIteration` —
at the RR-7 claim-event and RR-5 iteration-record schema seams, already green before
implementation (a finding, kept as guards); coverage is `2 obligations, 2 covered, 0
uncovered`, the contract namespace ends at 5 tests / 30 assertions green from 4 failures at RED, with no weakened test
and no generated mock, stub, TODO or skeleton.

Weed check mode: no RR-24 divergence against `EffectClaim`, `CampaignIteration` or `MintNovelBehavior`.
Classified findings: `:first-attempt?` is defined as attempt ordinal 0 on ownership epoch 1
(a first try by a new owner after a lease handoff is not a first attempt) — an
implementation-level reading of "confident first attempt" beneath the issue's wording
(intentional gap, recorded here); the forced-reindex marker is claimed BEFORE the rebuild,
so a crash between the marker and the rebuild loses that forced rebuild and the
threshold-gated description-updated reindex path is the backstop (intentional at-most-once
trade-off: never re-pay a rebuild on replay, at the cost of a delayed one after a crash);
`ConceptProvenance` in the spec carries kind, source, creator and trace identity, not the
iteration (intentional — iteration provenance lives on the minted event, beneath the spec).

## Test seams

Seam-4 (ontology consumers over a synthesized event stream) primary, Seam-3 (durable evidence: event-store reads) for provenance.

## Blocked by

RR-7.

## Handoff plan

**Handoff is crafted AFTER RR-7 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the action identity assigned to a mint, which carries the iteration
  - the claim record, which the reindex guard reads to detect a replay

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
