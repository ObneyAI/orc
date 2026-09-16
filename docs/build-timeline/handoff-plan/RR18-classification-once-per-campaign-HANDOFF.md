# RR-18 implementation handoff: classification is one durable fact per campaign

## Read first

- `AGENTS.md`
- `docs/ORC-PRINCIPLES.md`
- `CONTEXT.md`, especially Campaign, Checkpoint, Occurrence, and Classification
- `specs/ontology.allium`, contract `TaskClassification` and invariant
  `ClassificationIsOnePerCampaign`
- `specs/orc-service.allium`, entities `Campaign` and `CampaignResumeFact`
- `specs/COVERAGE.md`
- `docs/issues/rr-durable/RR-18-classification-happens-once-per-campaign.md`
- `docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md`, G3
- `docs/prd/rr-durable-self-learning.md`, Evidence and the self-learning loop
- `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj`,
  classification wedge and checkpoint boundary
- `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj`, all
  checkpoint constructors
- `components/orc-service/src/ai/obney/orc/orc_service/core/researcher_resume_state.clj`
- `components/orc-service/src/ai/obney/orc/orc_service/core/runtime.clj`,
  `collect-tick-classification`
- `components/ontology/src/ai/obney/orc/ontology/core/commands.clj`, assignment CAS
- the corresponding classifier, bounded-campaign, resume-state, counter, and
  runtime tests

## Verified mechanism and root cause

Every initial, yielded, or recovered researcher quantum enters the same node-start
handler. That handler already reads the durable resume state, but decides whether
to classify solely from the immutable base node's `:auto-classify?` flag and nil
static `:context`. The first classification is stored only on an ephemeral copy of
the node, so every resumed ownership epoch asks the classifier again.

RR-9 atomically commits one classification per ownership epoch. Its CAS is
deliberately epoch-scoped, so it does not prevent a new epoch from classifying the
same campaign. The ontology assignment command likewise fences only active epoch
ownership. Runtime then reads all assignment events for a tick and explicitly
selects the latest, which can differ from the first payload shown to the model.

V2/V3 resume-state machinery already supplies the correct persistence seam:
`checkpoint->v2-researcher-facts` preserves arbitrary non-history fields, and V3
delta encoding changes only `:sandbox-vars`. No new store or parallel state path is
needed.

## Exact change

1. Capture the exact `:context` produced by the first classification as the
   campaign's carried classifier payload. Thread it into the executor and copy it
   into every checkpoint constructor: ordinary iteration, terminal, retry, timeout,
   and error checkpoints.
2. Admit that payload as an optional field in V2 and V3 resume schemas so old
   checkpoints remain readable. V3 full/delta encode and hydrate must preserve it
   byte-for-byte outside sandbox hashing.
3. When a durable carried payload exists, rebuild the current node from it and run
   the normal R-Inject render without calling either classifier or emitting another
   classification/convergence decision. A recovery start and a yielded start use
   the same durable criterion; do not trust only a process-local or undocumented
   resume marker.
4. Make `:ontology/assign-task-class` idempotent on
   `[source-sheet-id source-tick-id]`. A sequential duplicate is a successful
   empty-event no-op; concurrent duplicates contend on an occurrence-key CAS so
   only the first assignment lands. For checkpointed calls, compose this guard with
   the existing live-epoch/terminal fence. Different ticks on the same sheet remain
   independent occurrences.
5. Make the run envelope select the first durable assignment for the occurrence—the
   classification used to prepare the campaign—not the latest event. Avoid the
   current duplicate read while touching the seam.
6. Prove one stored assignment increments every current raw-event counter once and
   leaves the existing occurrence-deduped projections in agreement. Do not move
   recurrence to terminal outcome; RR-19 owns that change.

## Spec obligations

- `contract-signature.TaskClassification.classify`, constrained by
  `ClassificationIsOnePerCampaign`

Required propagate report: `1 obligations, M covered, K uncovered`.

## TDD cycle list

1. RED: dispatch the assignment command twice with different command ids but the
   same occurrence key and different class ids. Both calls are non-anomalous, only
   the first event exists, and consolidation delta/total plus occurrence ownership
   equal one/first. GREEN: pre-read for sequential no-op plus an occurrence CAS.
2. RED: release two assignment commands for the same occurrence concurrently after
   both have observed absence. Exactly one event must land; the loser may receive a
   Grain CAS conflict, but replaying it after the winner is visible is a successful
   no-op. GREEN: compose occurrence idempotency with active epoch fencing.
3. RED: a V2 payload survives V3 full snapshot, delta, and hydration unchanged, and
   old payload-free states still validate. GREEN: optional schema fields only; no
   sandbox hash coupling.
4. RED: a public two-quantum default-checkpointed campaign calls structural and
   behavioral classification once, persists the exact payload, and supplies that
   same payload to both provider turns. GREEN: thread the payload through all
   checkpoint constructors and resume from it.
5. RED: recovery from the persisted frontier in fresh runtime state reuses the
   payload without classifier or assignment dispatch. GREEN: durable-state gating,
   not a local resume flag.
6. RED: with a historical duplicate stream containing conflicting classes, the run
   envelope reports the first classification rather than the latest. GREEN: select
   the campaign's original fact and perform one read.
7. Refactor only after every prior cycle is green. Preserve RR-9 timeout,
   cancellation, atomic-batch, and epoch-race proofs.

## Live QA the orchestrator will run

- Execute a public default-checkpointed two-quantum campaign and inspect classifier
  call counts, raw assignment facts, V2/V3 resume payloads, both provider prompts,
  run envelope, occurrence projection, and consolidation counters.
- Repeat across real SQLite close/reopen with fresh runtime state and prove no
  classifier or assignment effect runs after recovery.
- Barrier-race same-occurrence assignments and verify first-writer-wins, one event,
  one counter increment, and replay-as-no-op.
- Run focused suites, affected classification/checkpoint regressions, changed
  bricks, Allium check/analyse, weed check mode, obligation audit, and independent
  inspect-orc.

## Do NOT touch

- `specs/*.allium`; the orchestrator is the only spec writer.
- RR-19 outcome-time recurrence, RR-20 worked-pattern selection, or later slices.
- Grain code or dependency pins.
- `/Users/darylroberts/Desktop/Code/orc`, the ontology branch, or any other worktree.
- Existing generated or handwritten tests merely to make a new test pass.

## Disciplines (verbatim)

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions.
  Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or
  the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can
  fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never
  horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive
  refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network,
  share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges
  React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing
  (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a
  durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's
  "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the
  `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

## Report back

- Exact RED and GREEN command/result for every cycle.
- Files changed and why.
- The line `1 obligations, M covered, K uncovered`, with a reason for every gap.
- Every generated mock, stub, TODO, or skeleton.
- Every divergence classified as spec bug, code bug, aspirational design, or
  intentional gap.
- Anything not verified.

The orchestrator independently runs `/inspect-orc`, including Allium severity
comparison, weed check mode, obligation audit, public resume/restart proof,
same-occurrence race, and broad tests before closing DET-E2E-280.

## Completion and independent inspection

RR-18 is implemented and independently inspected through the public campaign,
SQLite restart, ontology command, projection, and run-envelope boundaries.

- Assignment idempotency. Sequential duplicate commands are successful no-ops;
  concurrent same-occurrence commands use first-writer-wins CAS; replay is a
  no-op; and the same sheet with a different tick remains an independent
  occurrence. The raw assignment stream and all current counter projections
  retain one occurrence and its first class.
- Durable carry. The exact classification context is optional in V2/V3 resume
  facts, remains outside sandbox delta hashing, and is copied by ordinary,
  terminal, retry, timeout, and error checkpoint constructors. Yield and
  recovery rebuild the model-facing node from that fact and skip both
  classifiers.
- Pre-checkpoint crash closure. Classification outcome events carry the exact
  context in the same atomic commit. Recovery prefers checkpoint state and
  otherwise uses that committed outcome, closing the window between
  classification commit and the first checkpoint.
- Envelope identity. Runtime selects the first assignment for the occurrence,
  rather than a later conflicting historical duplicate.

The strongest public proof executes a real default-checkpointed researcher on
SQLite, intercepts the real atomic classification commit after append, removes
live lease ownership before provider or checkpoint work, closes the entire
runtime and store, then opens a fresh runtime and resumes. Across the crash,
both classifiers run exactly once total. The provider runs once after recovery,
exactly one assignment is stored, no pre-crash resume state exists, and the
outcome event and recovered resume state preserve the same context.

The pre-checkpoint RED was three failures: the restarted process entered the
classifier, classifier calls reached two, and the durable outcome context was
nil. The GREEN public SQLite and different-tick set passes 3 tests and 35
assertions. The full checkpointed researcher namespace passes 38 tests and 342
assertions, and the bounded campaign/checkpoint aggregate passes 70 tests and
579 assertions.

Independent inspection repeated the public SQLite crash proof and the
different-tick command proof, reviewed the production seams, and accepted the
slice with no blocking findings. Coverage is `1 obligations, 1 covered, 0
uncovered`. No generated mock, stub, TODO, or skeleton remains. The deterministic
classifiers and provider are injected capabilities used to observe call counts;
the store, commands, processors, runtime, and restart boundary are real.

Two discarded shell attempts did not count as verification: one appended an
accidental token after otherwise passing tests and exited while trying to read
it as a file; the next malformed the `-Sdeps` option and never ran the proof.
The subsequent clean command exited 0. While extending the assignment proof,
`other-tick-id` was initially bound in the wrong test and caused a compile
error; moving the binding to the intended test restored the exact proof.

One stale RR-1 test was a harness defect rather than a product regression: its
full pipeline consumed the node-start before the test classifier could observe
it. The repaired harness executes a direct root researcher and excludes only
the leaf executor, proving the slow classifier runs off-thread without changing
the product assertion.

Two non-headless broad runs aborted natively at the PDF/MCP namespace. Both
macOS crash reports show `SIGABRT` in AppKit registration through
`JRSAppKitAWT`, `NSApplicationAWT`, and `AWTStarter`, not a Clojure failure.
With `-Djava.awt.headless=true`, the same namespace passes all 10 tests and 74
assertions in each consuming project graph. The complete two-project
`orc-service` brick then passed with exit 0 in 58 minutes 42 seconds; the result
is recorded in DET-E2E-280. No non-headless full-run success is claimed.

Classifications: the missing pre-checkpoint recovery fact was a code bug and is
fixed; the RR-1 failure was a test-harness bug and is fixed; context carrier
placement is an intentional implementation detail consistent with the spec;
RR-19 outcome-time recurrence remains aspirational and out of scope. The local
Grain pin remains an explicit integration dependency until its upstream PR is
merged.
