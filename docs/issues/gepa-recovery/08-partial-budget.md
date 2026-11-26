# Slice 8: budget exhaustion at an incomplete comparison

## Read first

AGENTS.md, ORC-PRINCIPLES, specs/gepa.allium CompleteComparisonsOnly and AttemptAccountingSurvivesRecovery; task_execution.clj; todo_processors.clj evaluation helpers/catches; commands.clj complete-optimization; gepa_metric_recovery_test.clj and gepa_periodic_recovery_test.clj.

## Actual verified dependencies

Root independently verified ledger14/94, public accounting4/46, real duplicate-delivery1/17, real scheduler1/12, actual safe/unsafe/budget/attempt-limit metric recovery1/45. Task claim returns :budget-exhausted before work when capacity is absent. Evaluation helper throws ex-info with :gepa/evaluation-status :budget-exhausted. Current candidate catch suppresses this and subsample catch logs/returns nil. This can leave an optimization running forever when capacity cannot complete a full comparison.

## Required behavior and TDD

First prove through actual code-node trees and registered commands (no invented tree results): with two validation examples and budget1, the first task succeeds and the second cannot be admitted; no aggregate may be invented, but the run must durably fail with the budget reason because no fully evaluated candidate exists. Then prove budget3 permits one complete two-example candidate and one partial successor; exhaustion must complete with the original fully evaluated candidate, preserving the successor's completed evidence, without inventing its aggregate. Keep exact tree/metric counts and all public spending correct.

Add narrowly scoped handling of claim-budget exhaustion in candidate and subsample catches: terminate with a prior fully evaluated candidate if one exists, otherwise explicit failure. Do not treat pending owners, missing metric registrations, unsafe unknown outcomes, or exhausted retry attempts as successful completion. Do not discard admitted unfinished work; complete-optimization's managed-task guard is owned by the slice6 agent.

## Ownership

Own one new orc-service test namespace and only a shared budget-exhaustion helper plus its calls in the two evaluation catches in todo_processors.clj. Do not edit any other handler/helper, commands, schemas, interface, ledger, specs, or existing tests. Root is not editing those catch sections while you own them. No commits. JVMs run only through coordinated short noninteractive checkpoints.

## Acceptance

Root reruns tests/source and the existing race/recovery cases. Every test uses real Grain and actual code leaves. No LLM calls necessary for this deterministic admission behavior. Report command conflicts or ambiguous competing work instead of inventing a fallback score. Existing checklist GEPA-RECOVERY-3/6/10 tracks this integration; keep unchecked until root inspection.

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
