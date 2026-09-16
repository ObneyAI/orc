# RS-1 — The reranker gives a domain verdict beside fitness

## Parent

PRD `docs/prd/r-inject-specialisation.md`; decisions D1–D6 in `docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md`; ADR 0006. Spec: `specs/ontology.allium` `contract TaskClassification`, `enum DomainCoverage`, `value DomainVerdict`, `rule MintDomainChild`.

## What to build

Every per-candidate result from the rerank call carries, beside its fitness and reasoning, a discrete domain-coverage verdict (covered, partial, uncovered, unknown) and a short canonical domain label, with the reasoning naming the representative use or guard the candidate matched or the gap it found. The parse step canonicalises the new keys as it does the existing ones and reads a missing or malformed verdict as unknown, never coercing it. The instruction is edited in place with the wording RS-P1 proved; the stance, the score definition and the reasoning discipline are unchanged. Every existing consumer of the reranked result keeps working unchanged.

## Acceptance criteria

- [x] A reranked entry validates with the two new fields present, and a missing or malformed verdict reads as unknown rather than failing the entry or being coerced to a value
- [x] The instruction asks for the verdict with reason-before-verdict and defines the four values; fitness's definition is unchanged
- [x] Every existing reranker suite is green unchanged; the enrichment contract and failure-surfacing behaviour are untouched
- [x] No consumer reads a domain judgement off fitness or off a similarity score

## Spec obligations covered

`entity-fields.DomainVerdict`, `value-equality.DomainVerdict`, `enum-comparable.DomainCoverage`; `contract-signature.TaskClassification.judge_domain_coverage` is realised as the reranker's per-candidate verdict (report the coverage line).

## Verification

Every per-candidate result of the rerank call now carries `:domain-coverage` (one of the four verdicts), `:domain-label`
and `:domain-reasoning` beside fitness and reasoning. The parse step canonicalises the three keys exactly as it does
the shipped two and reads a missing or malformed verdict as `:unknown` — never coercing it, and never dropping the
entry: the implementer's red run showed that a naive coercion would have made a legitimate ranked entry vanish from the
result when the enum validation rejected it, which is the second-order harm the no-coercion rule prevents. The
instruction gained the domain section the two prototypes proved, verbatim, in place of the shipped three-key output
contract; the orchestrator proved the text before and after that paragraph byte-identical to the shipped instruction.
Candidates may carry `:existing-domain-children`, rendered to the model for label reuse; the orchestrator renamed the
field in the instruction to the kebab form every other candidate field uses.

Red-first per cycle: the three fields dropped by `select-keys` (RED 3 failures); missing and malformed verdicts (RED 4);
the instruction section and the candidate key (RED at compile time, the constant did not exist). Independent
inspection re-read both diffs, proved the instruction prefix and suffix unchanged, confirmed no `.allium` edit and no
weakened assertion, and re-ran the RS-1 suite with eleven reranker and classifier suites: 0 failures. Coverage
`4 obligations, 4 covered, 0 uncovered` (`contract-signature.TaskClassification.judge_domain_coverage`,
`entity-fields.DomainVerdict`, `value-equality.DomainVerdict`, `enum-comparable.DomainCoverage` — all uncovered before
this slice). Finding carried to RS-2: `apply-rerank`'s join copies only reasoning, fitness and rerank source onto the
candidate, so the verdict stops at the rerank return value today; RS-2 widens that join. On the final tree the ontology brick passes in each owning project graph run as its own JVM (79 namespaces, 679 tests / 3847 assertions each, 0 failures) and the complete two-project `orc-service` brick passes with exit 0 in 72 minutes 35 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (130 namespaces, 1058 tests / 5885 assertions per graph, 0 failures, 0 errors); Allium on the ontology spec holds at 43 information diagnostics, 8 warnings, 0 errors, 0 analyse findings.

## Test seams

Seam 2 — the rerank workflow's parse step with constructed payloads (prior art: `reranker_test`, `cc15_reranker_enrichment_contract_test`, `rerank_failure_surfacing_test`).

## Blocked by

RS-P1 (its findings fix the field names and wording).

## Handoff plan

`docs/build-timeline/handoff-plan/RS1-reranker-domain-verdict-HANDOFF.md` (written after RS-P1).

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
- The classifier, the wedge, the prepend renderer, harvest, the consolidator.

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
