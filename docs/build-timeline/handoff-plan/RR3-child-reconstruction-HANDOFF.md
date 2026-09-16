# RR-3 handoff — durable child reconstruction status and duration

## Read first

1. `AGENTS.md`
2. `docs/ORC-PRINCIPLES.md`
3. `docs/issues/rr-durable/RR-3-a-running-child-is-not-a-failure-and-a-replayed-child-keeps-.md`
4. `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_tree_executor.clj` — `reconstruct-completed-tick`, `await-existing-tick`, `execute-tree`
5. `components/orc-service/src/ai/obney/orc/orc_service/core/runtime.clj` — `durable-terminal-result`
6. `components/orc-service/test/ai/obney/orc/orc_service/rlm_tree_executor_test.clj` — the two RR-3 tests named below

## Relevant specification excerpt

From `specs/orc-service.allium`:

> Every terminal execution preserves the durable instant at which it began, records the terminal instant separately, and reports a duration equal to the elapsed time between those instants within the precision of the public duration representation. Unfinished durable nodes retain their start and use the workflow timeout instant only as their completion; summarized ephemeral steps do not claim independent durable timing.

This status-table repair predates the explicit campaign model, so `/propagate` reports: `0 obligations, 0 covered, 0 uncovered`. The two public `execute-tree` tests are the executable contract.

## Exact change

- A `:sheet/tree-tick-completed` bookend whose root is still `:running` is an intermediate retick signal, not a terminal record. Reconstruction must ignore it, register/reattach, close the read/register race, and await the existing child.
- When reconstructing a terminal stable child, return its durable elapsed duration from the persisted start and terminal instants. Do not use zero, replay wall time, or a new clock reading.
- Reconcile this status table with `runtime/durable-terminal-result` rather than letting two paths drift. Avoid an unrelated broad refactor unless a shared pure mapper is the smallest proven change.

## TDD cycle list

1. RED already witnessed: `stable-child-running-bookend-is-not-reconstructed-as-failure` returns replayed `:failure` and never registers. Make it rejoin and return the eventual completion.
2. RED already witnessed: `reconstructed-stable-child-retains-durable-duration` returns success without the expected 250 ms. Derive exactly 250 ms from fixture instants.
3. Add/identify the matching terminal-status table test for both reconstruction paths, including partial, timeout and blocked; running must remain excluded.
4. Run the complete `rlm-tree-executor-test` namespace, then the recursive researcher namespace.

## Do NOT touch

- `specs/*.allium`; the orchestrator is the only spec writer.
- RR-1, RR-2, duration schemas/events outside the child reconstruction seam, or other worktrees.
- The controlled-instant tests merely to obtain green.

## Orchestrator live QA

The orchestrator will independently re-run both controlled-event proofs, a real in-memory child execution/rejoin, the `orc-service` brick, and Allium/weed/inspect-orc gates.

## Dependency rule

RR-3 has no upstream API dependency. Work only against the landed PR #36 event shapes; if real duration cannot be reconstructed from those durable facts, report that as a blocking divergence instead of inventing an event mutation outside the slice.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

## Report back

- Preserve the coverage line verbatim: `0 obligations, 0 covered, 0 uncovered` with the reason above.
- List RED/GREEN commands, files changed and what could not be verified.
- Classify divergences; declare every mock, stub, TODO or skeleton.
