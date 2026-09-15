# RR-28 — Remove the retired `skipped` status from code

## Parent

Grill decision D1 (`docs/build-timeline/grill-sessions/rr-weed-followup-decisions.md`): the `skipped` node status
was retired from `specs/orc-service.allium` because the engine never starts an untaken branch, so no node execution
exists to mark.

## What to build

The code still carries the retired stage: the node-trace record schema admits a `skipped` status, a query result
schema declares a skip count, and the trace-summary query tallies a status nothing produces, so the count can only
ever read zero. Remove all three so the code declares exactly the lifecycle the spec declares, and any consumer that
read the tally stops receiving a field that could never move.

## Acceptance criteria

- [x] The node-trace status enum admits exactly the statuses the spec's `NodeExecutionStatus` declares
- [x] No query result carries a skip count and no read model computes one
- [x] Every existing node-trace, query and streaming suite is green unchanged (no assertion weakened)
- [x] A repo-wide search for the retired status in `components/*/src` finds nothing

## Spec obligations covered

- `enum-comparable.NodeExecutionStatus`
- `transition-terminal.NodeExecution.status`
Both are already covered by existing suites; a generated test green before the change is the expected finding, not
success — report it as such.

## Verification

The code now declares exactly the node lifecycle the spec declares. The node-trace record schema no
longer admits a `skipped` status, the trace-summary query result no longer carries a skip count, and the
tally that could only ever read zero is gone. Both changes were driven red-first: the RR-26 node-trace
test now asserts a `skipped` record is rejected (RED while the enum still admitted it), and a new query
test drives the real node-stats query through a real workflow execution and asserts the result carries
no skip count (RED while the tally still wrote one). The implementer also found and replaced a stale
"pending Phase 3" placeholder comment in the GEPA primitives suite with that real test.

Independent inspection re-read the diff (three removals, two test updates, nothing else), confirmed no
`.allium` file was touched, and re-ran the node-trace, GEPA-primitives, RR-27, durable-iteration and
streaming suites: 34 tests / 225 assertions, 0 failures. The remaining `:skipped` occurrences in the
ontology component are a different concept (an insufficient-traces result flag and embedding batch
counts) and were left alone. Coverage `2 obligations, 2 covered, 0 uncovered`
(`enum-comparable.NodeExecutionStatus`, `transition-terminal.NodeExecution.status`, both already covered
— the expected finding for a retirement). On the final tree the complete two-project `orc-service` brick passes with exit 0 in 58 minutes 15 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (130 namespaces, 1060 tests / 5924 assertions per graph, 0 failures, 0 errors), and the evaluation brick passes (10 namespaces, 119 tests / 487 assertions). Allium holds at 114 information diagnostics, 35 warnings, 0 errors, 0 analyse findings.

## Test seams

Schema validation of a node-trace record per status; the trace-summary query's result shape.

## Blocked by

None — can start immediately.

## Handoff plan

`docs/build-timeline/handoff-plan/RR28-remove-retired-skipped-status-HANDOFF.md`

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

- `specs/*.allium` — the orchestrator is the only spec writer.
- Any status other than the retired one; the executor; the streaming component.

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
