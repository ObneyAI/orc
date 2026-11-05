# 4. Atomic candidate and task result publication

## Read first

AGENTS.md; docs/ORC-PRINCIPLES.md; specs/gepa.allium DurableOptimizationLifecycle; core/commands.clj record-evaluation-result and record-task-call; existing budget_test.clj concurrent result proof; TDD and inspect skills.

## Exact change

Own only those two existing command implementations in components/gepa/src/ai/obney/orc/gepa/core/commands.clj and a new GEPA test namespace. Preserve the existing local record-subsample-result CAS unchanged. Both candidate-result and legacy task-call completion currently read before append without atomic admission. Add tenant-scoped CAS under their existing durable identities and idempotent duplicate handling. A real competing different result must not overwrite a committed result. Do not modify task-ledger, metric-runtime, schemas, interface, or .allium files.

## TDD cycles

First force two real record-evaluation-result commands to overlap after reading the same unevaluated candidate. Use a barrier around a forwarded real read if needed, not invented state. Assert one durable candidate-evaluated event, one budget charge and no worker fault. Confirm RED then repair only that command and GREEN. Next exercise concurrent record-task-call commands with same call-id and assert one completion record; confirm RED then repair and GREEN. Preserve sequential duplicate behavior compatibility where explicitly documented, and test conflict vs exact replay.

No JVM without root lease; ledger currently owns it. Prepare first RED test while waiting. Bounded JVMs only, no provider calls. No commits/push. Root will independently inspect/rerun and verify live resume/model evidence before merge. No dependency on the new ledger or metric APIs; do not pre-write dependent integration.

## Disciplines

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
