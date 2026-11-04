# 1. Durable GEPA task ownership and budget ledger

## Exact change

Implement a self-contained task lifecycle via new core/task_ledger.clj and interface/task_schemas.clj, with tests in the GEPA component. Use registered Grain commands/schemas, tenant-scoped durable reads/CAS and injectable clock. Root will integrate existing orchestration after reviewing the produced API. Do not edit existing todo_processors.clj, optimization.clj, metrics.clj, interface.clj, read_models.clj, or commands.clj.

Stable task IDs already derive from optimization, call scope, role, example index. Persist immutable boundary/task membership, trace identity, input/instruction identity, owner token/epoch and renewable lease before effects. Separate handler tokens within one process. Offer command-backed helpers to admit/claim, renew, begin initial evaluation/metric retry, finish, record attention, and observe status/accounting. Ownership expiry permits atomic takeover; stale owners cannot finalize. Scope budget CAS to the optimization, not only a call. Defaults:90s lease; configurable clock and lease.

An admitted initial evaluation reserves one slot; dispatch consumes it once. Additional metric attempts reserve/consume additional slots. Never erase an unknown dispatched attempt's charge. Completion/reuse/aggregate add no charge. Expose spent/reserved/available and task status. Task manifests belong to candidate or subsample evaluation boundaries.

Historical completed aggregate expenditure stays unchanged. For an unfinished boundary, adopt completed task records via existing stable IDs with explicit source event/trace references; avoid double-charging aggregate-covered work. Preserve ambiguity/provenance instead of guessing. New aggregates will be task-accounted. Design smallest API supporting this; send real signatures and example event shapes to root.

## TDD cycles

One behavior at a time: concurrent admission yields one owner and one reservation; last-slot competition; current-owner renew/complete and stale-owner rejection; expiry takeover keeps original trace; dispatch/metric retry accounting survives replay; legacy completed vs unfinished adoption. Root requires integration-shaped command tests and real projected readback. Keep uncovered obligations explicit.

## Root live QA

Real resume plus subscriber and real-provider task must show one tree per task, matching physical requests/metric attempts, ownership, ledger and public totals. Root adds orchestration/SQLite recovery after API inspection.

## Read first

- AGENTS.md; docs/ORC-PRINCIPLES.md; specs/gepa.allium, especially DurableOptimizationLifecycle.
- /Users/darylroberts/.codex/memory/orc-grain-onboarding.md.
- /Users/darylroberts/.agents/skills/tdd/SKILL.md and inspect-orc/SKILL.md.
- Existing real Grain async and SQLite test fixtures.

## Ownership and verification

Do not edit .allium files, other agents' files, dependency pins, or the separate ontology worktree. Do not commit/push. Preserve existing local edits. Work only in this reconciled runtime. Ask root for the single JVM lease before tests; no live provider calls in subagent work. Keep test JVMs bounded and exited. Use public interfaces and real command/event/projection boundaries; never weaken a test after its RED proof. Record exact commands and evidence. Root independently reruns proof and live QA before acceptance.

Dependent integration briefs are written only after the producing API is implemented and independently inspected. These initial slices are independent.

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
