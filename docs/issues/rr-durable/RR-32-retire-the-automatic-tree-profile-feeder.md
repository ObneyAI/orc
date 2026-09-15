# RR-32 — Retire the automatic tree-profile feeder and close the classifier's unknown-dimension hole

## Parent

Grill decision D6 (`docs/build-timeline/grill-sessions/rr-weed-followup-decisions.md`). Spec: the rules
`ClassifyEvaluationFailure` and `RecordSuccessfulPattern` are already removed from `specs/ontology.allium` with a
comment stating why; `TreeProfile`, its commands and `ExtractLearnedRules` remain.

## What to build

Delete the code that pretends `:evaluation/trace-evaluated` exists: the ontology `on-trace-evaluated` processor with
its two handlers and their private helper, the discovery reader that filters that event and the `run-pattern-discovery`
command and interface exports built on it. Make `classify-evaluation` skip a low-scoring dimension whose name is not in
its dictionary instead of building a failure record with a nil URI, and make the static ontology's dimension lookup
delegate to that one dictionary so the two cannot drift. Nothing the manual path offers changes: the tree-profile
entity, `record-tree-strength` / `record-tree-weakness`, the retrieval queries, learned-rule extraction and
PATTERN-RECORDING.md stay as they are.

## Acceptance criteria

- [ ] No processor subscribes to `:evaluation/trace-evaluated` and no code reads events of that type (a repo-wide search
      of `components/*/src` finds nothing)
- [ ] `classify-evaluation` given a low-scoring dimension with an unknown name returns no failure for it and still
      classifies the known ones; every failure it returns carries a `failure:` URI
- [ ] `get-failure-concept-for-dimension` and `classify-evaluation` answer from one dictionary (the four rubric names and
      their short aliases all resolve; an unknown name resolves to nil in both)
- [ ] The manual tree-profile path is byte-identical: its commands, queries and tests are untouched and green
- [ ] Every existing ontology, evaluation and orc-service suite is green unchanged; tests that only existed to exercise
      the deleted feeder or discovery path are deleted, not weakened

## Spec obligations covered

- `entity-fields.TreeProfile` and the `ExtractLearnedRules` obligations must stay green; the retired rules emit no
  obligations any more (a retirement finding, expected). The classifier fix is behind the `EvaluationClassified`
  concept the spec no longer states; it is covered by the slice's own tests.

## Test seams

Red-first: an existence test pinning the absence of the processor, the reader and the command; a classifier test with
an unknown dimension name (RED today: nil URI); a dictionary-parity test between the two lookups; the ontology core
suite, the consolidator suites, the evaluation RR-30 guard, and the orc-service ontology and end-to-end suites.

## Blocked by

None (RR-31 landed; independent of Q7).

## Handoff plan

`docs/build-timeline/handoff-plan/RR32-retire-tree-profile-feeder-HANDOFF.md`

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

- `specs/*.allium` — the orchestrator is the only spec writer (the tend is already done).
- `TreeProfile`, `record-tree-strength`, `record-tree-weakness`, the tree-profile read models, retrieval queries, rule
  extraction, embedding of profiles, and `docs/PATTERN-RECORDING.md`.
- The consolidator, living descriptions, the judge runtime.

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
