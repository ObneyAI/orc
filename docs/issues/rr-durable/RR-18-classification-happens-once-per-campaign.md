# RR-18: Classification happens once per campaign

**Type:** AFK

## Parent

PRD: [`docs/prd/rr-durable-self-learning.md`](../../prd/rr-durable-self-learning.md)
Grill log: [`docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`](../../build-timeline/grill-sessions/rr-durable-self-learning-dossier.md)

## What to build

The classifier runs on every start event, resume starts included, so an N-quantum campaign classifies N times —
paying N reranker round-trips (one measured at 302 seconds) and emitting N occurrence events.

That inflates the counter recurrence is read from, lets a class clear the harvest threshold on a fraction of the real
evidence, lets the classifier's own retrieval gate clear inside a single run, and — if two quanta land on different
classes — attributes one occurrence to two classes at once, breaking the parity between the read model and the aggregate.

The signature a classification runs on is fixed by the work that was asked for, so asking again during the same campaign
asks an identical question. Skip on resume and carry the answer forward. Fix it at both ends: make the assignment command
idempotent on the occurrence key, so no future producer can re-inflate the counters.

## Acceptance criteria

- [x] A campaign classifies once regardless of how many quanta it takes
- [x] A resumed campaign reuses the carried classification rather than re-deriving it
- [x] The assignment command is idempotent on the occurrence key; a duplicate is a no-op
- [x] One campaign contributes exactly one occurrence to every counter that reads it
- [x] The run envelope reports the classification the model actually saw, not the latest

## Spec obligations covered

- `contract-signature.TaskClassification.classify`

The generated contract-signature obligation is governed by the ratified
`ClassificationIsOnePerCampaign` invariant in `specs/ontology.allium`: one
classification decision and its exact classifier payload belong to one
campaign, resumes reuse that decision, and repeated assignment for the same
occurrence does not create another occurrence. Allium currently reports the
contract signature as the machine-generated obligation; the named invariant is
the acceptance constraint applied when propagating its public integration
proof.

## Verification

The classification outcome and its exact structural/behavioral context now land
in the same atomic commit. Every checkpoint carries that context, and a resumed
quantum rebuilds the model-facing node from the durable fact without invoking
either classifier again. Recovery also reads the committed classification
outcome when a process dies after that commit but before the first checkpoint.

The ontology assignment command treats `[source-sheet-id source-tick-id]` as
the occurrence identity. Sequential duplicates are successful no-ops;
concurrent contenders are first-writer-wins under an event-store CAS; and a
different tick on the same sheet remains independent. Counter projections
therefore observe one assignment. Runtime reports the first durable assignment,
which is the classification used to prepare the campaign.

The public crash proof runs a real default-checkpointed researcher against
SQLite, blocks only after the atomic classification append, loses the live
lease before any provider call or checkpoint, closes the complete runtime and
store, then reopens with fresh runtime state. Across both processes, each
classifier is called once, the provider is called once after recovery, one
assignment exists, and the event and resume state contain the exact same
classifier context. The focused RR-18 public SQLite and different-tick set
passes 3 tests and 35 assertions; the full checkpointed researcher namespace
passes 38 tests and 342 assertions; and the bounded/checkpointed aggregate
passes 70 tests and 579 assertions. The complete two-project `orc-service`
brick passes with exit 0 in 58 minutes 42 seconds under
`-J-Djava.awt.headless=true`. Two preceding non-headless attempts aborted in
macOS AppKit registration rather than returning a Clojure test failure, so no
non-headless full-run success is claimed.

Independent inspection found `1 obligations, 1 covered, 0 uncovered`, no
generated mock, stub, TODO, or skeleton, and no unresolved spec/code
divergence. The final Allium gates match the characterized twelve-spec baseline:
115 information diagnostics, 35 warnings, 0 errors, and zero analyse findings.
RR-19 remains the explicitly separate outcome-time recurrence slice.

## Test seams

Seam-4 (ontology consumers over a synthesized event stream) primary, Seam-1 (public execution via `with-async-test-context`) for the resume path.

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
