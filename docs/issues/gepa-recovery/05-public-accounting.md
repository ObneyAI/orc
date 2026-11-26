# Slice 5: consistent public GEPA accounting

## Read first

- AGENTS.md and docs/ORC-PRINCIPLES.md
- specs/gepa.allium: AttemptAccountingSurvivesRecovery and LegacyAccountingProvenance
- docs/issues/gepa-recovery/README.md
- core/task_ledger.clj and task_ledger_test.clj
- core/read_models.clj, core/queries.clj, existing budget tests

## Actual dependency and change

Root independently executed the ledger's first seven tests (65 assertions) successfully. Actual APIs are `task-ledger/reduce-event [state event]`, `accounting [state]`, `get-state [context optimization-id]`, and `get-accounting [context optimization-id]`. Its exported `event-types` names every accounting event. The ledger agent is finishing legacy coverage and terminal guards in that same API; coordinate before relying on its new accounting behavior.

Make GEPA state, population, optimization summary and public progress/population queries agree with authoritative expenditure, including dispatched tasks whose aggregate is absent. Task-accounted aggregate publication must not double-charge. Historical completed aggregate expenditure stays unchanged. Preserve reserved capacity separately from spent calls. Avoid duplicating a second accounting algorithm: reuse the ledger reducer/projection. Update read-model subscriptions and versions where required. Root just added metric-descriptor to optimization summary; preserve it.

## Ownership

Own core/read_models.clj and a new public_accounting_test.clj. Query code only if needed for actual public exposure. Do not edit commands, schemas, task_ledger, optimization, todo_processors, or any specs. Do not commit. One JVM lease shared across team; ledger owns it currently. Coordinate focused RED/GREEN there, root later reruns independently.

## Vertical TDD cycles

1. Through real Grain commands, admit and dispatch one task without publishing an aggregate. Assert every public budget view reports one spent call and no phantom completed evaluation. RED then implement.
2. Preserve recorded historical aggregate spending and reflect a new task attempt exactly once. Distinguish reservation from spend.
3. Once root's aggregate commands expose task accounting, exercise that actual boundary for no double-counting; keep this obligation explicitly pending until available, do not fabricate events to mark it complete.

## Root live QA and acceptance

Root reruns these tests and the actual resume/subscriber race, compares durable events to every budget view, and runs the full final live optimization. Checkpoint remains open until real integration, Allium and inspection agree. Document every unverified obligation. No narrowed tests or hidden mocks.

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
