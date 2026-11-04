# 3. Public durable ORC execution-result lookup

## Exact change

Own only orc-service/interface.clj, minimal core/runtime.clj changes if required, and a new focused ORC-service test namespace. Expose get-execution-result [context tick-id] as a read-only public boundary over the existing durable-terminal-result reconstruction. Return nil while no terminal result exists, otherwise preserve original status, trace identity, outputs, source references and executed version. Do not launch or join execute, replace a completion promise, retick, cancel, or mutate events. Require tenant context as existing store reads do.

## TDD cycles

Public lookup exists and reconstructs an actual completed code-node execution; pending/unknown tick returns nil; tenant isolation; durable SQLite/cold reconstruction preserves original output and execution ID without another start. Reuse existing fixtures and command paths. One test at a time. No edits to .allium; report any needed root spec addition.

## Root live QA

GEPA recovers completed original tree output after process loss and never creates another task tick merely to obtain its result. Root verifies model trace identity and canonical output sources in the live witness.

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
