# RS-5 — The chain reaches retrieval and harvest

## Parent

PRD `docs/prd/r-inject-specialisation.md`; decisions D1–D6 in `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md`; ADR 0006. Spec: `specs/ontology.allium` `contract TaskClassification`, `enum DomainCoverage`, `value DomainVerdict`, `rule MintDomainChild`.

## What to build

On synthesised durable events, a domain child that has recurred past the retrieval gate is a retrievable candidate as a sibling of its parent's other children, is reachable by walk-down from the parent, and, once it recurs and scores past the harvest gate, is promoted into a behavioral child under the nearest abstract behavior with its own body assembled from its consolidated description — without any change to harvest's gate, the consolidator, or the retrieval gate. Any gap found on that path (a reader that drops the child, a name the harvest cannot anchor, a body the consolidator cannot assemble for a child) is fixed in this slice, and the fix is reported as a finding.

## Acceptance criteria

- [ ] Synthesised occurrences past the retrieval gate make the child a retrievable candidate and a walk-down child of its parent
- [ ] Synthesised occurrences past the harvest gate promote the child under the nearest abstract behavior with a body carrying its label and worked pattern
- [ ] Harvest's gate, the consolidator's policy and the retrieval gate are byte-identical
- [ ] Every existing harvest, coherence, seeds and consolidator suite is green unchanged

## Spec obligations covered

`entity-fields.TreeProfile`-family obligations untouched; the harvest and promotion rules' existing obligations must stay green (report the coverage line for `PromoteWellScoredClass` and the harvest rules).

## Verification

No new production path. Two readers were fixed and one field carried. Walk-down's synthetic child candidate now
carries the scope its description was read under, tree-class for a runtime-emergent domain child and
tree-fingerprint for a seeded one, instead of a hardcoded seeded axis that hid a walk-down-selected domain child
from every tree-class consumer downstream. The harvested body carries the domain label as an optional field, read
from the class's tree-class concept at harvest time; the label lives on the concept, which is what distinguishes a
domain child from every other tree-class concept (seeded and lazily created concepts carry their own identifier as
their label, the same convention RS-2's children lookup relies on). Harvest's gate, the consolidator, the retrieval
gate and the reindex trigger are byte-identical; a domain mint does not force a reindex, by decision, and the child
becomes searchable at the next rebuild like any other claim write.

Seam 4 throughout: real store, real commands, real read-models, no retrieval index loaded. The index feed and the
retrieval-gate band were green on first write and are reported as findings, as the brief predicted: the feed already
reads assembled tree-class bodies (the child's document carries its birth signature as content, granularity
tree-class, confidence zero because it has no strengths yet), and the gate treats the child's identity like any
tree-class (surfaced at zero occurrences, filtered at one and two, surfaced again at three). The walk-down scope
and the harvested label were red-first: one failure each, for the stated reason, then green. The harvest proof
mirrors the good-class harvest with a minted child in place of a recorded description: twelve synthesised
occurrences on the child, the first carrying the parent behavior, promote exactly one behavioral child under that
parent whose body carries the injected signature and the domain label, through the direct call and through the
processor.

Independent inspection re-ran the RS-5 suite with the birth, walk-down, harvest, body-assembly, consolidator,
RS-2, seeds, convergence-capture, coherence, recurrence and hierarchy suites plus the RS-3 durable and RS-4 render
suites (179 tests, 954 assertions, 0 failures), re-read the three diffs and confirmed the gated forms untouched.
The implementer surfaced, root-caused and did not paper over a harness defect in a helper it had been told to reuse:
the birth suite's dispatch helper appended every command's events a second time, because the real command
processor already appends before returning; one mint left ten events in the store. Both suites now dispatch through
the command processor alone, and the birth suite's idempotency test now proves one minted event in the store after
two mints rather than reading only the second command's return value. One stale name in the brief (a recurrence
corroboration suite that does not exist as a file; its subject lives in the coherence suite) was reported rather
than silently substituted. Coverage `0 obligations, 0 covered, 0 uncovered` for new ones; the harvest and promotion
rules' existing obligations hold through their suites.

On the combined RS-4 and RS-5 tree the ontology brick passes in each owning project graph as its own JVM (82
namespaces, 706 tests / 3982 assertions each, 0 failures) and the complete two-project `orc-service` brick passes
with exit 0 in 54 minutes 46 seconds under a 3 GB heap cap (132 namespaces per graph, 2142 tests / 11970 assertions
across both, 0 failures, 0 errors) — this run is also RS-4's brick gate.

## Test seams

Seam 4 — synthesised-event tests (prior art: `el4_harvest_test`, `rr21_winning_shape_coherence_test`, `seeds_test`).

## Blocked by

RS-3.

## Handoff plan

`docs/build-timeline/handoff-plan/RS5-chain-reaches-harvest-HANDOFF.md` (written after RS-3 lands, alongside RS-4's).

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
- **No regex or phrase matching over model-authored prose — in tests or in production.** Assert on structured data the
  runtime emits: typed reranker fields, event bodies, identities, concept-graph edges, rendered values the test itself
  injected. The reranker is stubbed with a typed payload the test constructs; only the live sweep hears the real model.
- **Fitness keeps meaning shape-and-intent fit.** Coverage is a separate discrete verdict; nothing reads a domain
  judgement off the fitness number or off any similarity threshold.

## Do NOT touch

- `specs/*.allium` — the orchestrator is the only spec writer.
- Harvest thresholds, the coherence measure, the consolidator's policy, the retrieval gate value.

## Report back

- The `/propagate` coverage line verbatim: `N obligations, M covered, K uncovered`, with a reason for every
  uncovered one. No silent caps.
- Any propagate-emitted mock, stub or TODO skeleton, declared explicitly as a tracked gap.
- Proposed divergence classifications for anything where spec and code disagree.
- What you could NOT verify, stated plainly.

## Orchestrator gates (run after you report)

`/inspect-orc` against this slice: re-run the proof independently, re-read the code, try to break the claims, plus the
three ORC-specific gates — spec-conformance (`allium check` / `analyse` error-free by **severity count** on touched
specs), `/weed` check-mode with classified divergences, and the obligation audit (the coverage line above must survive
into the slice report). Allium's internal verify is the CLAIM; `/inspect-orc` is the falsification.
