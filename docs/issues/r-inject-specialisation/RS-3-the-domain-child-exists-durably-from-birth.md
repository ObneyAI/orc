# RS-3 — The domain child exists durably from birth

## Parent

PRD `docs/prd/r-inject-specialisation.md`; decisions D1–D6 in `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md`; ADR 0006. Spec: `specs/ontology.allium` `contract TaskClassification`, `enum DomainCoverage`, `value DomainVerdict`, `rule MintDomainChild`.

## What to build

The auto-classify wedge records the coverage verdict, the label, the reasoning and the parent on the classified event. When the classifier returns a domain-child assignment, the wedge records, at classification time and before the campaign runs, the signature claim it already records for a fresh mint and the self-description the child is born with — its label as summary and its parent — by the path RS-P2 proved, so that the concept graph carries the SKOS broader edge from the parent as soon as the projectors run, and walk-down can find the child under its parent. A partial verdict records the label as a representative-use claim on the parent class. An unknown verdict is recorded through the existing deferral command and event, which now name the domain axis alongside the structural and behavioral axes. No second writer of a body slot is introduced.

## Acceptance criteria

- [ ] Through the live processor path with the reranker redefined, an uncovered leaf match yields a classified event carrying the verdict, label, reasoning, parent and the child's provenance, a signature claim on the child, and a SKOS broader edge from the child to the parent after the projectors run
- [ ] The child is reachable as a child of its parent by the walk-down's own child lookup
- [ ] A partial verdict yields a representative-use claim carrying the label on the parent class and no child
- [ ] An unknown verdict yields a deferral event naming the domain axis and no assignment of a child
- [ ] The classification remains one per campaign occurrence; every existing wedge, claim-capture and observability suite is green unchanged

## Spec obligations covered

`rule-entity-creation.MintDomainChild.1` (the ConceptRelationship created), plus `invariant.DeferralIsVisible` and `invariant.ClassificationIsOnePerCampaign` (must stay green).

## Test seams

Seam 3 — the orc-service wedge through the test helpers (prior art: `r_inject_classifier_context_test`, `cc6_cv1_claim_capture_test`, `el3_wedge_skip_uncertain_test`, `cc23_classification_observability_test`) and the ontology projectors.

## Blocked by

RS-2 and RS-P2.

## Handoff plan

`docs/build-timeline/handoff-plan/RS3-domain-child-durable-from-birth-HANDOFF.md` (written after RS-2 lands).

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
- The prepend renderer, harvest, the consolidator, the retrieval gate; the description body slot (claims only — CC-6).

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
