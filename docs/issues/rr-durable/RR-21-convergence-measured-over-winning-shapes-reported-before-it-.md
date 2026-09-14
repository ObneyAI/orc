# RR-21: Convergence measured over winning shapes, reported before it gates

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

The convergence measure counts distinct shapes across **every** tree a class ever emitted, against a denominator of
campaigns — and a campaign emits one tree per repair round. Ten typical campaigns therefore produce a ratio far above the
threshold, and the gate can never pass. It is invisible today only because the numerator has always been zero: the measure
has never once been witnessed on real data, so it passes vacuously.

It is also perverse in the wrong direction — a model that recovers well from a bad first tree scores as *less* convergent
than one that fails outright.

Measure the shapes that **won**: one per successful campaign, the terminal shape that carried it to success. The ratio is
distinct successful terminal shapes divided by successful campaigns. Failed and timed-out campaigns remain recurrence,
quality and weakness evidence, but they enter neither side of the coherence ratio and cannot make it easier to pass.

And roll it out **report-only** first. A threshold that has never fired cannot be distinguished from one that never will:
harvesting nothing looks identical to nothing qualifying. Compute it, report it, and only gate on it once its real
distribution has been observed.

## Acceptance criteria

- [x] Convergence counts one winning shape per successful campaign
- [x] The reported ratio is distinct successful terminal shapes divided by successful campaigns
- [x] Failed, timed-out, cancelled and abandoned campaigns do not enter either side of the ratio
- [x] A campaign that repairs is not penalised for the shapes it abandoned
- [x] The measure is computed and reported without blocking promotion
- [x] The gate report distinguishes 'did not qualify' from 'not yet measurable'
- [x] The observed distribution is recorded, so a calibrated threshold can be chosen from data

## Spec obligations covered

- `entity-optional.CampaignIteration.emitted_shape`
- `rule-success.ReportSuccessfulShapeCoherence`

## Verification

Convergence is now measured over winning shapes. `harvest/winning-shape-coherence`
joins a class's RR-19 `:success` verdict occurrences to their RR-20 bookends by the
`[source-sheet-id source-tick-id]` pair and takes, per successful campaign, the
fingerprint of its last successful Phase-2 execution in durable order — the terminal
shape that carried it to success. Shapes a campaign abandoned during repair, and every
bookend of a failed, timed-out, cancelled or abandoned campaign, enter neither side of
the ratio. The measure reports `successful-campaigns`, `successful-shape-observations`,
`distinct-successful-shapes`, the ratio (absent when no successful campaign carries a
shape) and a status that distinguishes `:not-measurable` from `:rejected` and
`:qualified` against `maximum_shape_ratio`.

The rollout is report-only: `harvest-candidate?` no longer includes a coherence
clause, `harvest-gate-report` carries the winning-shape measure under `:coherence`
with its verdict and counts, and the retired all-trees `distinct-tree-shapes`
measure is gone from harvest (the consolidator's descriptive aggregate is untouched).
Every verdict occurrence yields one durable `:ontology/shape-coherence-reported`
fact — the spec's `ShapeCoherenceReported` — carrying the class, the
verdict-occurrence total, the four counts, the ratio, the status, the configured
maximum and a timestamp, recorded through the `:ontology/report-shape-coherence`
command under an event-store CAS so re-delivery of one occurrence records nothing
twice; the observed distribution can therefore be read back before any threshold
is made load-bearing.

Independent inspection reran the subagent's proof and drove an adversarial probe
(a success superseded by a later success, a success followed by a final failed
bookend, a fingerprintless success, report idempotency under direct re-delivery of
one occurrence, a cancelled campaign carrying a success bookend, and a twelve-campaign
class through the gate report). One real defect was found and returned: the durable
report was computed from the store's state at processing time, so a backlog of
verdicts made every report carry the final counts (`[3 3 3]` instead of `[1 2 3]`);
it was fixed test-first by bounding both `verdict-occurrences` and the measure to the
triggering occurrence's durable position (`:source-occurrence-event-id`, UUIDv7
order), with a deterministic backlog test that drives the registered handler once
per occurrence. The propagated namespace's own context fixture also lacked the
LMDB cache the real completion command needs (the orchestrator's `/propagate`
mis-step, fixed as fixture plumbing only; no assertion changed).

Focused results on the final tree: the propagated namespace 6 tests / 37 assertions, the combined RR-21/RR-19/RR-20/harvest/consolidator set 126 tests / 576 assertions, the adversarial probe 5 / 12, all 0 failures. The ontology brick passes
in both owning project graphs (660 tests / 3770 assertions in each graph, run solo in fresh JVMs — 8 minutes 48 seconds and 7 minutes 33 seconds). The complete two-project
`orc-service` brick passes with exit 0 in 54 minutes 45 seconds under
`-J-Djava.awt.headless=true` (121 namespaces and 1041 tests / 5816 assertions in each project graph, 0 failures, 0 errors). Allium remains at the
characterized twelve-spec baseline of 115 information diagnostics, 35 warnings, 0
errors and zero analyse findings. `allium plan` resolves both issue obligations —
`rule-success.ReportSuccessfulShapeCoherence` by the durable-report proof and
`entity-optional.CampaignIteration.emitted_shape` already at the RR-5/RR-6 schema seam
(a finding, kept as the durable guard); coverage is `2 obligations, 2 covered, 0
uncovered`, the propagated namespace ends at 6 tests / 37 assertions green from 35 failures at RED, with no weakened
generated test and no generated mock, stub, TODO or skeleton.

Weed check mode: no RR-21 divergence. Classified findings: `ShapeCoherenceReported`,
`shape_coherence_report_status` and the three count functions are named in the rule
but not declared elsewhere in the spec (spec commentary rather than declared
constructs — intentional gap, the implementation is the declaration); the spec has
no idempotency clause for the report and the implementation adds one per occurrence
(intentional implementation detail); the descriptive consolidator aggregate still
counts every emitted tree for reflection (intentional — description evidence, not the
gate); a calibrated blocking threshold remains a later, data-driven decision
(aspirational).

## Test seams

Seam-4 (ontology consumers over a synthesized event stream) primary.

## Blocked by

RR-20.

## Handoff plan

**Handoff is crafted AFTER RR-20 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - how a winning shape is identified from the outcome-keyed claim RR-20 produces
  - the per-shape claim keying, which the distinct count reads

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
