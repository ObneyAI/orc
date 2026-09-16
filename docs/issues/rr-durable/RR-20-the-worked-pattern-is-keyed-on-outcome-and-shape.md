# RR-20: The worked pattern is keyed on outcome and shape

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

The pattern recorded for a class is whichever tree was emitted **last**, with no knowledge of whether it worked — the
recording branches only on whether the tree differs from the one already stored, and never reads the outcome. The
selector that ships it then ranks by confidence with no filter on what the evidence rests on.

So a campaign that emits a broken tree, watches it fail, and repairs it can crystallize the **failed** tree as the class's
proven pattern. That is the whole defect this arc exists to fix.

A successful tree records and reinforces the worked pattern. A failed one records that it failed and never displaces a
success. Key the claim per shape rather than one per class, so a class that genuinely succeeds with two shapes keeps both
— which is also what makes the convergence measure mean something.

## Acceptance criteria

- [x] A pattern is recorded as proven only when a campaign succeeded with it
- [x] A failed shape is recorded as failed and never displaces a successful one
- [x] A class that succeeds with two shapes retains both
- [x] The selector prefers success-backed, occurrence-corroborated claims over bare emitted artefacts
- [x] A campaign that fails then repairs records the repair as proven and the first attempt as failed

## Spec obligations covered

- `entity-optional.CampaignIteration.emitted_shape`
- `invariant.RecordedTreesCarryTheirShape`

## Verification

The worked pattern is now recorded per shape and by outcome. The post-emit
enrichment writer (`on-emit-enrich-tree-class`) resolves the class by the
`[source-sheet-id source-tick-id]` occurrence rather than the shared host
sheet, reads the bookend's `:status`, and writes ONE claim operation keyed on
the tree's `:tree-fingerprint`: a `:success` bookend records or reinforces the
shape's `:strength` claim carrying the exact `:generated-tree-source` text; any
other outcome records the shape as a `:weakness` with the same exact source and
never touches a success-backed claim. A class that succeeds with two shapes
keeps two claims; a re-success reinforces only its own shape; a later failure
never displaces an earlier proven shape; an `:edit` can only reword the same
shape. Both writers declare what they rest on through two new mechanical
evidence bases, `:emitted-artifact-outcome` (the bookend's deterministic
outcome) and `:campaign-verdict` (the RR-19 verdict), admitted without judge
episodes and therefore never able to validate or enforce on their own.

Proof is graded. When a campaign's durable RR-19 verdict is `:success`, the
new `on-campaign-success-corroborate-worked-pattern` processor reinforces the
success-bookend shapes of that occurrence with a `:campaign-verdict` support
delta, which the claim fold counts durably as `:verdict-corroborations`; the
assembled strength entry carries that count additively when positive, and
`best-recommended-pattern` ranks it first, then earned confidence, then
recency, so a verdict-corroborated shape outranks a bare emitted artefact
however often the latter was re-emitted, and a failed-only class offers
nothing and cannot harvest. Concurrent claim writes lost at the event store's
CAS boundary are retried against a fresh read and logged when exhausted.

Independent inspection reran the subagent's proof and drove an adversarial
probe through the registered processors. Two defects were found and sent back:
the corroboration writer applied `filter` to the event store's reducible and
threw silently inside the processor thread (code bug, fixed by materialising
the read, with two durable processor-level tests added), and support-only
ranking let a bare artefact re-emitted three times outrank a shape corroborated
once (acceptance criterion unmet; fixed with the durable
`:verdict-corroborations` count and the explicit primary sort key, with a
durable ranking test for that exact scenario). The public lifecycle proof then drove the whole mechanism through `sheet/execute`: a default-checkpointed researcher with a scripted provider emitted a quoted tree whose Phase-2 execution genuinely failed, then a structurally repaired tree that succeeded and finalized the campaign; the two bookends, both iteration records with their shapes, the single success verdict occurrence, the repaired shape's `:strength` claim (exact source, `:verdict-corroborations` 1), the first shape's `:weakness` claim, `harvest-body` and the assembled strength entry were all read back from the store (1 test / 34 assertions).

Focused results on the final tree: the propagated namespace 6 tests / 23 assertions, the four sibling RR-20 namespaces, the CV-2/CC-6 seams and the ontology claim/harvest/reranker seams 108 tests / 451 assertions, the public lifecycle proof 1 / 34, the checkpointed researcher namespace 39 / 352, all 0 failures. The ontology brick
passes in both owning project graphs (653 tests / 3717 assertions in each graph, run solo in fresh JVMs — 8 minutes 15 seconds and 6 minutes 14 seconds — because the combined single-JVM invocation hits the known DJL native-library double-classloader harness error in its second graph). The complete two-project
`orc-service` brick passes with exit 0 in 69 minutes 28 seconds under
`-J-Djava.awt.headless=true` (121 namespaces and 1041 tests / 5816 assertions in each project graph, 0 failures, 0 errors). Allium remains at the
characterized twelve-spec baseline of 115 information diagnostics, 35 warnings,
0 errors and zero analyse findings after tending the graded-proof preference
into `WorkedPatternsAreProvenNotMerelyRecent`. `allium plan
specs/orc-service.allium` resolves both issue obligations at the RR-5/RR-6
iteration-record schema seam, already green before implementation (a finding,
kept as the durable guard); coverage is `2 obligations, 2 covered, 0
uncovered`, the propagated behavioural bridge ends at
6 tests / 23 assertions green from 15 failures at RED, with no weakened generated test and no generated mock,
stub, TODO or skeleton.

Weed check mode: no RR-20 divergence. Classified findings: `:campaign-verdict`
and `:verdict-corroborations` are implementation-level claim accounting absent
from the behavioural spec (intentional gap; the spec names the preference, not
the counter); a verdict corroborates every success bookend of its campaign
rather than only the terminal winning shape — RR-21 defines the winning shape
and owns that narrowing (aspirational until RR-21); R-Inject still clips an
offered pattern to 1,200 characters (code bug, ratified for RR-22, untouched
here). The pre-existing CV-2/CC-6 assertions that encoded one-slot,
last-emit-wins were changed deliberately and are listed in the handoff report.

## Test seams

Seam-4 (ontology consumers over a synthesized event stream) primary, Seam-3 (durable evidence: event-store reads) for the outcome join.

## Blocked by

RR-5 and RR-6.

## Handoff plan

**Handoff is crafted AFTER RR-5 and RR-6 lands and is inspected — not before.** This slice consumes that slice's real produced API; a brief written against a guessed signature sends a subagent down a path that does not exist.

Signatures to be read from the landed code rather than assumed:
  - the iteration record's shape and outcome fields
  - the durable source form from RR-6, since a recorded pattern must be runnable

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
