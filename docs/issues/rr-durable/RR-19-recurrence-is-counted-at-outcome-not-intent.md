# RR-19: Recurrence is counted at outcome, not intent

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

The occurrence event is emitted before the model does any work, and the counter promotion reads is bumped on it — so
a campaign that classifies and then crashes counts toward promotion while contributing no quality signal at all.
Recurrence is counted at intent; quality is counted at outcome.

Count recurrence when a campaign reaches a verdict. A cancelled or abandoned campaign says something about our
infrastructure and nothing about the behaviour: its iteration records remain available for describing what was tried,
but they move no gate. A failed campaign is neither cancelled nor abandoned — failure is a verdict, and learning from
failure is the point.

## Acceptance criteria

- [x] Recurrence advances only when a campaign reaches success, failure or timeout
- [x] An abandoned campaign advances no gate
- [x] An abandoned campaign's iteration records still reach the reflection as descriptive evidence
- [x] A cancelled campaign advances neither recurrence nor judging, while its completed iteration evidence remains inspectable
- [x] A failed campaign counts for recurrence and against the quality axes
- [x] The gate report's occurrence figure matches the number of campaigns that actually reached a verdict

## Spec obligations covered

- `invariant.AbandonedCampaignsRecordNoVerdict`
- `invariant.CancelledCampaignsRecordCause`
- `rule-success.CampaignIsAbandoned`
- `transition-terminal.Campaign.status`

## Verification

Recurrence now advances on one explicit durable fact per classified researcher
campaign, `:ontology/tree-class-occurrence-recorded`, written by the
`:ontology/record-tree-class-occurrence` command when the campaign's node reaches
`:success`, `:failure` or `:timeout`. Classification (`:ontology/task-classified`)
remains the immutable attribution and reflection source and no longer moves any
counter, window, trigger or gate. The occurrence command admits only a matching
durable classification and the campaign's FIRST terminal researcher completion in
durable order — a blocked, cancelled or abandoned campaign, a yield, a
non-researcher node, a mismatched class or node, or a later stray completion for a
node that already settled, all record nothing. Sequential replays are successful
no-ops; concurrent contenders are first-writer-wins under an event-store CAS; a
different tick on the same sheet is an independent occurrence.

The consolidation delta counters and the class's recent-occurrence window (read
models bumped to version 3), harvest's occurrence pairs, ordered occurrence scores
and trigger, the threshold trigger and the gate report all read verdict
occurrences. A failed verdict counts for recurrence and carries its low judge
evidence into the quality window. Reflection joins the durable researcher
iteration records, so completed evidence from cancelled and abandoned campaigns
remains inspectable without advancing recurrence. A historical classification-only
stream replays as attribution and invents no verdict.

Independent inspection reran the subagent's proof and an adversarial probe set
(non-researcher completion, yield completion, cross-node classification, late
cancellation after a verdict, classification-only history, triple-delivered
timeout). One probe exposed a real code bug: a campaign whose node had already
completed `:blocked` could later gain a `:success` occurrence from a stray second
completion. It was fixed test-first — the RR-19 namespace's
`first-terminal-completion-is-the-campaigns-only-verdict` was RED at 4 assertions
and the admission predicate now treats the node's first terminal completion as the
only verdict, mirroring the campaign projection and
`transition-terminal.Campaign.status`; the forced-race fixture now races two
deliveries of the one completion. Two `orc-service` fixtures that had created "occurrences" from
classification alone were repaired to model what the spec now requires: the
CC-23 observability seed and the deterministic ontology E2E `occurrence!`
helper (DET-E2E-091) each record the classified campaign's terminal researcher
completion through `:sheet/complete-node-execution` and its verdict occurrence
through `:ontology/record-tree-class-occurrence`; under outcome-time recurrence
both had correctly counted zero, which is the semantic change working as
intended, not a product defect. The first full brick attempt also failed once in
`det-e2e-205-recovery-while-child-running` (recovery not observed within its
30-second settle window while three unrelated benchmark JVMs shared the host);
the namespace then passed solo twice, with the brick's full 35-processor registry
loaded, with its ten preceding namespaces in brick order (188 tests/1249
assertions), and inside the second full brick run. It touches no RR-19 code path
and is recorded as an unreproduced timing failure under host contention, not as
green by assumption.

Focused results: RR-19 namespace 13 tests / 53 assertions; repaired CC-23 namespace
7 / 67; probe 7 / 17; all 0 failures. The ontology brick passes in both owning
project graphs (653 tests/3717 assertions in each graph — the aggregate graph inside the brick invocation and the ontology-only graph in a fresh JVM in 9 minutes 25 seconds, after the combined single-JVM invocation hit the known DJL native-library double-classloader harness error in its second graph rather than a test failure). The complete two-project `orc-service` brick
passes with exit 0 in 76 minutes 42 seconds under `-J-Djava.awt.headless=true`
(116 namespaces and 1027 tests/5733 assertions in each project graph, 0 failures, 0 errors). Allium remains at the characterized twelve-spec baseline
of 115 information diagnostics, 35 warnings, 0 errors and zero analyse findings.
`allium plan specs/orc-service.allium` (335 obligations) resolves all four issue
obligations; coverage is `4 obligations, 4 covered, 0 uncovered`, with no weakened
generated test and no generated mock, stub, TODO or skeleton.

Weed check mode: no RR-19 behavioural divergence. Classified findings: the optional
`:source-completion-event-id` provenance is an implementation detail absent from
the behavioural spec (intentional gap); `harvest/distinct-tree-shapes` still counts
fingerprints over every tree execution while the spec's coherence clause counts
distinct successful terminal shapes over successful campaigns — pre-existing,
tracked, owned by RR-20/RR-21 (aspirational until those land); the occurrence
command answers a rejected false claim with the same silent no-op as a replay
(intentional for at-least-once delivery, noted for a later slice). The local Grain
pin remains an explicit integration dependency until its upstream PR is merged.

## Test seams

Seam-4 (ontology consumers over a synthesized event stream) primary.

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
