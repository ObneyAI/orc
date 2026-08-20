# RH2 handoff — recursive empty-code recovery

## Read first

- `AGENTS.md`
- `docs/ORC-PRINCIPLES.md`
- `specs/orc-service.allium` — `LeafExecutor.ResearcherBlankCodeIsRecoverable`
- `docs/build-timeline/issues/pr34-rlm-hardening/RH2-recursive-empty-code-recovery.md`
- `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` — `execute-repl-researcher-rlm`
- The propagated DET-E2E-162 test and its recorded RED output.

## Exact change

Change only the recursive RLM Phase-1 blank-code branch. When `llm/predict` returns
no executable code and no explicit provider error, append attributable error evidence
to iteration history and recur while the existing loop budgets permit. Do not create a
separate retry counter or sleep. Preserve explicit provider errors as terminal.

## TDD cycle

The orchestrator supplies one already-RED public-interface test. Make only that test
green with the smallest production change. Do not edit the test, spec, checklist, issue,
handoff, guide, or coverage ledger. Do not anticipate later cases.

## Do not touch

- `specs/*.allium` or any test file.
- RH1 output validation and optional-write code.
- Terminal-mode behavior or provider retry implementation.
- Any worktree except `/private/tmp/orc-pr34-output-contract`.
- Do not commit or push.

## Report back

Provide the exact production diff, RED and GREEN commands/totals, and any missteps or
unverified behavior. Classify any divergence from the brief before making it.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.
