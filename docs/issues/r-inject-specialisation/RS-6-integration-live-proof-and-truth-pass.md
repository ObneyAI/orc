# RS-6 — Integration, live proof and truth pass

## Parent

PRD `docs/prd/r-inject-specialisation.md`; decisions D1–D6 in `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md`; ADR 0006. Spec: `specs/ontology.allium` `contract TaskClassification`, `enum DomainCoverage`, `value DomainVerdict`, `rule MintDomainChild`.

## What to build

The whole-spec integration slice, orchestrator solo. The classify-only sweep harness reads the three-state outcome, the assignment provenance and the coverage verdict and reports them per instruction and in aggregate beside the June-era flags. The 21-task corpus is run twice on the final tree: the sanity checks match at 1.00 and covered, uncovered leaf matches mint children under their shape leaf, and the second pass derives identical child identities. One bounded full-bench run on three off-domain tasks is recorded as observation. The user docs that call the out-of-distribution symptom resolved are corrected to state the current behaviour. A full `/weed` over the ontology spec, cross-entity tests, and the obligation convergence check close the arc.

## Acceptance criteria

- [ ] Harness reads outcome, provenance and verdict; both sweep passes recorded with findings
- [ ] Sanity checks 1.00 and covered; uncovered leaf matches mint children; identical identities across passes
- [ ] One full-bench observation on three tasks recorded
- [ ] Docs corrected; `/weed` divergences classified and tended; every `MintDomainChild` and `DomainVerdict` obligation covered; Allium error-free by severity count

## Spec obligations covered

All eight new obligations plus the classification contract's existing invariants — the convergence check.

## Test seams

Seam 6 — live; plus every seam above re-run on the final tree.

## Blocked by

RS-1 through RS-5.

## Handoff plan

Orchestrator solo; no brief.

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
- Nothing is exempt: this slice may touch any file to close a divergence, but every change is reported.

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
