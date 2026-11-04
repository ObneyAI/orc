# 2. Preserve metric identity and bound safe metric invocation

## Exact change

Own new core/metric_runtime.clj plus its GEPA tests and targeted core/metrics.clj changes. Do not edit optimization.clj, todo_processors.clj, interface.clj or schemas; root wires these after inspecting actual API. Build helpers that prepare a serializable metric descriptor from existing optimize options, register/resolve functions by explicit identity, reconstruct built-in judge/default/factory metrics, and invoke with bounded attempts handled by caller. Keep existing two-argument metric functions. Preserve rich score/feedback exactly.

Built-in read-only judges and explicitly declared-safe custom functions may retry; arbitrary custom functions default unsafe. A missing custom binding produces explicit recovery-required evidence and never selects the default metric. Store kind, identity/revision, configuration and retry-safe declaration in descriptor. Defaults: three total attempts, five-minute timeout, configurable. A single bounded invocation returns structured success/error/unknown information; root's durable ledger owns retry admission and charges. A timeout does not prove remote cancellation. Record no secrets.

Existing metric exception-to-zero behavior must stop hiding infrastructure failures: judge dimension errors cannot fabricate a legitimate zero grade. Keep successfully produced feedback/score paths compatible. Metric-factory metadata can identify reconstructible built-ins. No changes to the evaluation component.

## TDD cycles

Descriptor serialization/built-in reconstruction; explicit custom identity registration and missing-binding behavior; preservation of score/feedback; thrown metric errors vs real zero grades; bounded timeout with fake/injected execution seam and safe-retry policy. One RED/GREEN slice at a time. No production provider calls here.

## Root live QA

Cold restart must use the same judge configuration and metric identity. An interrupted declared-safe judgment retries on original tree output with new durable attempt/charge; an unsafe or missing metric produces a visible handoff.

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

## Required configuration seam

Root expanded ownership narrowly to evaluation/interface.clj for current-judge-config [] and call-with-judge-config [config f], using existing dynamic judge configuration. Persist resolved provider/model in the descriptor and restore at invocation. Do not rewrite judging or mock defaults.
