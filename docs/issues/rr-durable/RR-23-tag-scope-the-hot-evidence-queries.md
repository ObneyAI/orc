# RR-23: Tag-scope the hot evidence queries

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

Several of the loop's hottest reads scan every event of a type for the tenant. The promotion path does two such
scans before its cheap gate and six past it, per occurrence event; the reflection path does eight; and the duplicate-score
check does one **per score dispatched**, over the fastest-growing event type in the system.

Three of the four already have the tag they need and can be scoped with no producer change. The fourth cannot: the tree
bookend is tagged by the throwaway execution it ran in rather than by the campaign that produced it, so it cannot be found
by source at all. Add that tag at the emit site.

The missing tag is the same class of defect as the dead join already found there — the bookend is identified by its
ephemeral execution rather than by what it belongs to. Fixing it makes the bookend consistently addressable by source,
which is what every consumer actually wants.

## Acceptance criteria

- [x] The promotion and reflection scans are scoped by tag rather than by type alone
- [x] The duplicate-score check is scoped to its execution rather than scanning all scores
- [x] The tree bookend carries a tag identifying the campaign that produced it
- [x] Query cost no longer grows with total store size, demonstrated by measurement at two store sizes
- [x] Results are identical to the unscoped versions — proven by comparison, not assumed

## Spec obligations covered

None. This slice repairs behaviour that predates the campaign model; its proof is the existing suite plus the acceptance criteria above.

## Verification

The loop's hot evidence reads are tag-scoped. The tree bookend now carries a
`[:source-tick source-tick-id]` tag at its emit site, so a campaign's Phase-2
executions are addressable by the campaign that produced them rather than only by
the ephemeral execution they ran in. Harvest's occurrence, score, winning-shape, classified-behaviour and harvested-check reads, the consolidator's reflection gather, aggregate metrics and model-completion lookups, the corroboration writer's bookend read, and the `:tree-class` branch of `get-consolidation-total` (the promotion path's pre-gate, which alone accounted for the measured growth) now read by occurrence pair (`[:tick …]`, `[:source-tick …]`, `[:tick …] [:node …]`) or by class (`[:description-target …]`, `[:harvested-tree-class …]`), through the read model's own registered reducer under the engine's documented query scope where a projection is involved (Grain keys its projection cache by that scope, so unscoped readers are unaffected). A `:legacy-replay?` opt-in, off by default, lets the winning-shape read fall back once per pair to the untagged scan for stores written before the tag existed. The duplicate-score check was
already scoped to its execution's tick; the composite duplicate check now is. No
read model was reworked (G19).

Equivalence is proven, not assumed: the contract namespace compares each scoped read
against an independent test-side reference computation over a multi-class store, and
the cost is measured through an injected counting read seam at two store sizes —
the promotion path, the reflection gather and the coherence measure materialise 6, 24 and 15 events at a 12-campaign store and exactly 6, 24 and 15 at a 30-campaign store (the unscoped tree measured 48, 48 and 36 growing to 120, 120 and 90).

Independent inspection reran the subagent's proof, reproduced the same flat cost table,
read every scoped call site, confirmed from the Grain source that a query-scoped
projection is cached under a key that includes its scope, and drove an adversarial probe:
the scoped `:tree-class` total equals the full unscoped fold and leaves node-type totals
untouched after scoped reads; an untagged pre-slice bookend is invisible to the scoped
winning-shape read unless legacy replay is opted in; a duplicate composite score is still
refused under the tick-scoped check; and two campaigns of one class keep their bookends
apart. Two fixtures that fabricated under-tagged events were corrected to model
production (DET-E2E-091's bookend now carries its source pair; the RR-19 helper tags
`[:node …]`), never by widening a production read. The scoping of `get-consolidation-total`
was not in the brief's enumerated map; the implementer flagged it and it is ratified as query
scoping, not a read-model rework.

The first full `orc-service` brick run on this tree failed one assertion outside the
slice: `public-execution-reticks-and-traces-checkpointed-iterations` saw a campaign's
trace list an effect claim after its own effect. Root cause (RR-16/RR-17 trace assembly,
not RR-23): the trace's researcher events were sorted by their rendered `:at` timestamp
strings, which different producers stamp at different precisions and which Java prints
without trailing zero groups, so `…49.64Z` sorted lexically after `…49.640123Z`; the
defect fires only when a timestamp happens to end in zeros, which is why four earlier
brick runs passed. Fixed test-first: entries now carry their source event's UUIDv7 id and
`order-researcher-events` sorts by that durable position (a yield placed right after its
checkpoint), with a unit test that pins the exact lexical-order trap; the checkpointed
researcher, trace, judge-evidence and iteration-stream suites then passed 57 tests / 516
assertions and the full gates were rerun on the fixed tree.

Focused results on the final tree: the contract namespace 5 tests / 14 assertions, the combined harvest/reflection/RR-19/RR-20/RR-21/judge-runtime/deterministic-ontology set 194 tests / 774 assertions, the adversarial probe 4 / 14, and after the trace-order fix the trace, checkpointed-researcher, judge-evidence and iteration-stream suites 57 / 516, all 0 failures. The ontology brick passes in
both owning project graphs (667 tests / 3800 assertions in each graph, run solo in fresh JVMs — 8 minutes 26 seconds and 8 minutes 25 seconds). The complete two-project `orc-service`
brick passes with exit 0 in 71 minutes 5 seconds under
`-J-Djava.awt.headless=true` (124 namespaces and 1045 tests / 5839 assertions in each project graph, 0 failures, 0 errors, on the second full run after the trace-order fix). Allium remains at the
characterized twelve-spec baseline of 115 information diagnostics, 35 warnings, 0
errors and zero analyse findings. The issue names no generated obligation: coverage is
`0 obligations, 0 covered, 0 uncovered`; the contract namespace ends at
5 tests / 14 assertions green from 5 failures at RED, with no weakened test and no generated mock, stub, TODO or
skeleton.

Weed check mode: no RR-23 divergence. Classified findings: the duplicate-score check was already tick-scoped before this slice (already covered — the issue's second criterion was half met); the legacy-replay opt-in is an implementation detail beneath the specs (intentional gap); the `:source-tick` tag is not named in any spec (intentional — tags are storage addressing, not behaviour).

## Test seams

Seam-4 (ontology consumers over a synthesized event stream) primary, plus a cost measurement at two store sizes.

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
