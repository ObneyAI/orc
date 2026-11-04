# Slice 6: managed comparison and terminal boundaries

## Read first

AGENTS.md, docs/ORC-PRINCIPLES.md, specs/gepa.allium (CompleteComparisonsOnly, AttemptAccountingSurvivesRecovery), core/task_ledger.clj and tests, core/commands.clj, interface/schemas.clj, docs/issues/gepa-recovery/README.md.

## Real APIs and existing implementation

Root independently verified ledger11tests79assertions, metric8tests63assertions, atomic publication3tests17assertions. `ledger/get-state ctx opt-id` has :tasks keyed by call-id, :boundaries keyed by boundary-id (each :boundary identity and :tasks frozen manifest), :completed-boundaries, and :terminal?. `ledger/get-task ctx opt-id call-id` returns :status, :result, :attempt-count, :boundary-id and manifest role/index. Completed task results are immutable. `ledger/finish!` atomically emits its finished fact and compatible task-call-completed fact.

Root has connected the task runner and added :accounting-mode :tasks and :task-call-ids to candidate/subsample commands and aggregate events. Their schemas are already optional. Current handlers still trust incoming managed aggregate payloads; your task is to close that exact integrity gap. Legacy aggregate input must remain supported and retain its historical accounting.

## Change

For task-accounted candidate and subsample aggregates, validate complete unique membership, original optimization/boundary identity, actual completed task scores (with original example indices), and total attempts. Pending/failed/unrelated/incomplete tasks must not publish a comparison. Preserve the existing parent-perfect skip: it may omit proposal tasks only when the actual parent grades and configuration justify that skip; expose that fact explicitly if needed, coordinate the additive producer change with root.

Reject standalone legacy record-task-call writes for calls already owned by a managed manifest; ledger finish is the only managed completion seam. Preserve identical legacy replay and legacy import before adoption.

Guard complete-optimization against currently admitted unfinished managed tasks. It must not freeze active work merely because reservations/dispatches reached the budget. Do not fabricate winners or scores. Root will handle orchestration's explicit budget/handoff behavior.

## Ownership

Own only record-evaluation-result, record-subsample-result, record-task-call and complete-optimization in core/commands.clj plus narrowly shared helper(s), and a new managed_result_boundary_test.clj. Do NOT edit other commands, read_models, task_ledger, metrics, optimization, todo_processors, interface, schemas or specs. Ask root for needed schema/producer additions. No commits. Root is sole JVM coordinator; use short noninteractive one-shot checkpoints.

## Vertical TDD cycles

1. Through real Grain commands, open/claim/dispatch a task, attempt premature candidate aggregate, prove rejection and no event. RED then implement; complete original task and prove valid aggregate.
2. Prove wrong membership/score cannot create a managed comparison. Subsample uses actual parent/proposed role/index evidence; include legitimate parent-perfect skip.
3. Prove legacy completion cannot bypass managed ownership and terminal optimization cannot discard admitted unfinished work. Keep existing atomic publication tests green.

Record named test obligations under existing GEPA-RECOVERY checklist entries; do not mark them complete. No bare event append, mocked ledger, or weakened existing tests.

## Parent acceptance

Root rereads patch, reruns focused tests and actual resume/subscriber trace, checks aggregate task IDs/scores/spend against event evidence, and applies Allium/weed/inspect plus final live CI gates. Report remaining integration gaps explicitly.

## Disciplines (verbatim)

## Core (always)


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

