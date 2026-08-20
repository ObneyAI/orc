# RH4 handoff — researcher configuration round-trip fidelity

## Read first

- `AGENTS.md`
- `docs/ORC-PRINCIPLES.md`
- `specs/orc-service.allium` — `ResearcherConfigurationSurvivesRoundTrip`
- `docs/build-timeline/issues/pr34-rlm-hardening/RH4-researcher-configuration-roundtrip.md`
- `components/orc-service/src/ai/obney/orc/orc_service/core/dsl.clj` —
  `build-node-tree`, `import-node!`, and `repl-researcher-node->form`
- `components/orc-service/test/ai/obney/orc/orc_service/dsl_roundtrip_test.clj` — DET-E2E-164

## Exact change

Preserve every field named by DET-E2E-164 through the three existing researcher
mappings. Keep one field vocabulary; do not introduce a second export format. Context
import continues through the existing `set-node-context` command. Runtime tool-gate,
tool-contract, and optional-write semantics are out of scope.

## TDD cycle

1. Run only DET-E2E-164 and record the current RED output.
2. Repair `build-node-tree`; rerun and observe the next public boundary failure.
3. Repair `repl-researcher-node->form`; rerun and observe the import boundary failure.
4. Repair `import-node!`; rerun DET-E2E-164 GREEN.
5. Run the complete `dsl-roundtrip-test` namespace and `git diff --check`.

## Do NOT touch

- Executor, sandbox, researcher completion, or tool-gate runtime code.
- Existing RH1/RH2/RH3 tests or specifications.
- Any other worktree, branch, commit, or remote.

## Orchestrator live QA

Re-export the real public definition in DET-E2E-164, evaluate its rendered DSL, import
its EDN, and inspect all three projected field maps. No network or device capability is
involved; this public event-store/projection journey is the live boundary for the slice.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

## Report back

Provide RED and GREEN commands/results, exact production files changed, all fields
preserved, broader namespace result, patch hygiene, and every harness mis-step.
