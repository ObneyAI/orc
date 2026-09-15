# RS-2 — The classifier mints a domain child from an uncovered leaf match

## Parent

PRD `docs/prd/r-inject-specialisation.md`; decisions D1–D6 in `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md`; ADR 0006. Spec: `specs/ontology.allium` `contract TaskClassification`, `enum DomainCoverage`, `value DomainVerdict`, `rule MintDomainChild`.

## What to build

After the existing match, bundle, walk-down and deferral logic has produced an outcome, the classifier applies the assigned candidate's coverage verdict. A matched outcome whose assigned class has no children and whose verdict is uncovered becomes a domain-child assignment: the child identity derived deterministically from the parent class and the canonical label, the parent, the label, the reasoning, and a provenance of its own, distinct from walk-down's mint. Partial keeps the leaf assignment and carries the label. Unknown returns the shape assignment with a domain-axis deferral to be recorded. Covered changes nothing. Walk-down's own leaf mint and the bundle band are untouched, and the same task twice derives the same child identity.

## Acceptance criteria

- [ ] An uncovered leaf match yields the derived child identity, parent, label and its own provenance, and the same task again yields the identical identity
- [ ] A partial verdict assigns the leaf and carries the label; a covered verdict is byte-identical to today's result
- [ ] An unknown, missing or malformed verdict yields the shape assignment plus a domain-axis deferral, and no child identity
- [ ] A match on a class that has children, and walk-down's own mint, behave exactly as before (the existing walk-down and three-state suites are green unchanged)
- [ ] Label canonicalisation is deterministic and documented in the function's contract

## Spec obligations covered

`rule-success.MintDomainChild`, `rule-failure.MintDomainChild.1/.2/.3`, `enum-comparable.DomainCoverage` (already covered by RS-1, stays green).

## Test seams

Seam 1 — `classify-task` with the reranker stubbed to a typed payload (prior art: `el3_three_state_outcome_test`, `walk_down_classifier_test`, `el1b_convergence_capture_test`, `cc23_ranked_candidates_test`).

## Blocked by

RS-1.

## Handoff plan

`docs/build-timeline/handoff-plan/RS2-classifier-mints-domain-child-HANDOFF.md` (written after RS-1 lands).

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
- The reranker instruction, the wedge, the prepend renderer, harvest, the consolidator, the retrieval gate, the bundle band.

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
