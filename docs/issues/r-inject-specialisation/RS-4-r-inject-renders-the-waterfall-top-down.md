# RS-4 — R-Inject renders the waterfall top down

## Parent

PRD `docs/prd/r-inject-specialisation.md`; decisions D1–D6 in `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md`; ADR 0006. Spec: `specs/ontology.allium` `contract TaskClassification`, `enum DomainCoverage`, `value DomainVerdict`, `rule MintDomainChild`.

## What to build

When a task is assigned to a domain child that has no consolidated body, the prepend's structural section renders the parent's full entry as the shape the task matched — its summary, worked pattern, strengths, guards and representative uses, exactly as a match renders today — followed by one line naming the child the task is assigned to and stating that this campaign's outcome is the child's first evidence. The four-move menu's SPECIALIZE wording points at that assignment rather than inviting a structural mint the runtime already made. When the child has a consolidated body, the child renders as the primary entry and the parent as a shape-context line. The fresh-mint text is unchanged for walk-down's own mint. Holdout arms and injection recording are unchanged.

## Acceptance criteria

- [ ] A newborn domain child renders the parent's full entry plus the child line, and the rendered values (parent identity, child identity, label) are the ones the test injected
- [ ] A domain child with a consolidated body renders as the primary entry with the parent as shape context
- [ ] A walk-down mint and a plain match render exactly as before; the holdout arms and injection record are unchanged
- [ ] Every existing R-Inject and injection-record suite is green unchanged

## Spec obligations covered

None new (a consumer surface); the prepend suites are the evidence.

## Test seams

Seam 5 — the deterministic prepend suite (prior art: `r_inject_classifier_context_test`, `cc13_injection_record_test`).

## Blocked by

RS-3.

## Handoff plan

`docs/build-timeline/handoff-plan/RS4-r-inject-waterfall-render-HANDOFF.md` (written after RS-3 lands).

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
- The classifier, the wedge's recording, harvest, the consolidator.

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
