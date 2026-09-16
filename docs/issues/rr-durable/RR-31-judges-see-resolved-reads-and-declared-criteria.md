# RR-31 — Judges see the node's resolved reads and its declared criteria

## Parent

RR-29's live run of the rewritten end-to-end judging phase (`.rr-durable-notes/RR29-live-run-judge-input-findings.md`,
findings 1 and 2). Spec: `TraceEvidence.inputs` and invariant `JudgesSeeTheWorkNotOnlyItsResult` (evaluation.allium);
documented contract: "supply your own `:criteria` string in the `sheet/judges` config" (`docs/EVALUATION-COMPONENT.md`).

## What to build

On the live processor path every judge attached to an ordinary workflow node is handed empty inputs: the node's
lifecycle events carry only execution context in `:inputs` since the value-log merge, and the judge-input builder
still reads that field instead of resolving the node's recorded reads from the value log the way it already resolves
the node's writes. A grounding judge therefore reports "the source is empty" for a node that read the whole ticket.
Separately, the `:criteria` a workflow declares on a built-in judge is validated, stored and documented as the common
way to make that judge domain-specific, yet the live invocation never passes it to the judge; the four LLM judges run
on their built-in criteria only.

Make the judge-input builder resolve `:inputs` from the completion's recorded reads (`value-log-resolve-reads` on the
event store, tenant, tick and completion), keeping the existing direct-inputs and started-event reach-back only for
completions that record no reads (the direct-tick and researcher paths). Make the live invocation compose each LLM
judge's instruction with the declared `:criteria` when the judge configuration carries one, falling back to the
built-in criteria when it does not. Neither change alters scores, feedback, dimensions or provenance shape; both change
only what the judge is shown.

## Acceptance criteria

- [x] A judge attached to a workflow node that declares reads receives those reads' resolved values as its inputs,
      verified through a real executed workflow on the live processor path (not a hand-built event)
- [x] A judge attached to a direct-tick node or a researcher terminal completion still receives the inputs it receives
      today (no regression on the reach-back path)
- [x] An LLM judge whose configuration carries `:criteria` is invoked with an instruction containing that criteria
      verbatim; one without `:criteria` is invoked exactly as today
- [x] The reach-back for started events is scoped to the tick (no tenant-wide scan per judged completion)
- [x] Every existing evaluation and consolidator suite is green unchanged (no assertion weakened)

## Spec obligations covered

- `entity-fields.TraceEvidence` / `contract-signature.TraceJudge.evaluate` (must stay green); the invariant
  `JudgesSeeTheWorkNotOnlyItsResult` is contract prose (`allium plan` emits no obligation for it) and is covered by the
  slice's own executed-workflow test.

## Verification

A judge attached to an ordinary workflow node now sees the values the node actually read. The judge-input builder
resolves `:inputs` from the completion's recorded read keys and read sources through the value log — the same way it
already resolved the node's writes — with any execution-context inputs the event carries layered over them; completions
that record no reads (direct ticks, researcher terminals) keep the direct-inputs and started-event reach-back, and that
reach-back now reads only the tick's events instead of every started event the tenant ever emitted. A judge
configuration's declared `:criteria` now reaches all four LLM judges: it replaces the rubric's built-in "what to
evaluate" before the instruction is composed, and an absent criteria composes an instruction byte-identical to before.

Every behaviour was driven red-first: the executed-workflow test (a real sequence with an LLM node that reads the
ticket, the provider stubbed, the judge invocation captured) was RED with empty inputs and GREEN with the resolved
read; the tick-scoped reach-back was RED on a query without the tick tag; each judge's criteria test was RED with the
rubric's built-in criteria in the instruction. The implementer ran the orchestrator's probe before changing anything
and again after: all three default-judge nodes of the triage workflow now see their resolved reads.

Independent inspection re-read every diff, confirmed the two Gap-7 reach-back tests are untouched and green, no
`.allium` file changed and no assertion was weakened, and re-ran the RR-31 suite with the runtime, async, RR-30,
RR-17, tier-1, grounding, orc-service judges and consolidator suites: 147 tests / 500 assertions, 0 failures.
The gated real-model end-to-end test was run live once after the change (1 test / 64 assertions, exit 0): the grounding judge now cites the ticket's own content ("The ticket mentions a billing error; … a password reset issue") where it previously reported an empty source. Coverage `2 obligations, 2 covered, 0 uncovered` (`entity-fields.TraceEvidence`,
`contract-signature.TraceJudge.evaluate`; the invariant `JudgesSeeTheWorkNotOnlyItsResult` is contract prose, now
evidenced for ordinary nodes by the executed-workflow test). On the final tree the complete two-project `orc-service` brick passes with exit 0 in 71 minutes 34 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (130 namespaces, 1060 tests / 5924 assertions per graph, 0 failures, 0 errors), and the evaluation brick passes in both of its projects (135 tests / 549 assertions per project, 0 failures). Allium holds at 114 information diagnostics, 35
warnings, 0 errors, 0 analyse findings. Registered as DET-E2E-293. The response framing and the empty-instruction
sentinel remain grill Q7.

## Test seams

One red-first test through a real executed workflow with a stubbed provider and a captured judge invocation (the
orchestrator's probe `.rr-durable-notes/probe_judge_inputs.clj` is the shape); one red-first test per criteria case
asserting the composed instruction; the existing async judge and consolidator suites.

## Blocked by

RR-30 — both slices edit the live judge invocation in the same file; the brief is crafted from RR-30's landed code.

## Handoff plan

`docs/build-timeline/handoff-plan/RR31-judges-see-resolved-reads-and-criteria-HANDOFF.md` (written after RR-30 lands)

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
- Rubric stances and scales, score gating, weights and aggregation, the heuristic-structural and custom judges.
- What the judge is told the producer's response is (the write-map framing) and the instruction-less sentinel — both are
  grill Q7, decided separately.

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
