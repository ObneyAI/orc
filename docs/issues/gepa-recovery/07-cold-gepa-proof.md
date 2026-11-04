# Slice 7: cold GEPA recovery proof

## Read first

AGENTS.md, docs/ORC-PRINCIPLES.md, specs/gepa.allium recovery contracts, task_execution.clj, recovery.clj, task_ledger.clj, gepa_periodic_recovery_test.clj and durable_execution_result_test.clj.

## Verified dependencies

Root independently passed ledger14/94, actual resume/subscriber1/17, real scheduled post-tree recovery1/12 and public durable result3/20 plus8 assertions in a separate JVM. This slice independently tests process loss, not a new speculative API.

Public interfaces: sheet/execute with caller tick-id; sheet/get-execution-result; gepa/recover-in-progress! emits requests consumed by normal processors; gepa/resume!; metric-runtime/prepare!, invoke-once!; ledger APIs described in slice1 final source. Build/start/claim/dispatch/finish through actual commands. Scheduled recovery is already exercised separately; this proof can invoke public recover-in-progress! explicitly after starting real processors.

## Change and ownership

Add one test/helper namespace under orc-service/test for a two-process SQLite recovery proof. Own only that new namespace and a short usage doc under docs/issues/gepa-recovery/. Do not edit production, specs, unrelated fixtures or runtime setup. No commits. Root owns JVM scheduling; prepare source and request short noninteractive proof commands.

Provide CLI prepare and recover entrypoints with a manifest path. Prepare real code-node tree(s), immutable datasets/candidate/manifests, and persist three supported interrupted states in one SQLite store: ownership before dispatch, actual tree completed before GEPA workflow-result/metric persistence, and scored task finished before aggregate publication. Original metric should be reconstructible read-only/default; call the actual metric for the finished fixture, do not invent a result.

Stop all processors/store and exit prepare process. Recover in a distinct JVM with new caches/processors against the same file and tenant. Assert original task/tick IDs, exact inputs/outputs, one intended tree per task, no repeated finished metric, complete aggregates and accounting across public views. Repeated recovery must append no duplicate task/tick/aggregate/effect. Include missing custom registration only if it can be represented as a concise additional fixture with explicit public restore; otherwise report it as separately covered by metric tests, not cold-proven.

Reuse the existing original-identity derivation from periodic test/production; don't invent an alternate harness tree engine. A test-only import command may recreate historical facts only if necessary and documented; no bare appends. Never bypass normal effects to claim live execution.

## Validation

This is verification of implemented behavior; an initially passing checkpoint is valid, no artificial RED. Any failure must be traced, minimized and reported to root before production repair. All helper commands bounded, clean shutdown. Do not hide failures in unconditional exit0; print full assertion totals and exit nonzero on any failure. Keep SQLite manifest for root's independent second-process inspection, no deletion of unrelated files.

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

