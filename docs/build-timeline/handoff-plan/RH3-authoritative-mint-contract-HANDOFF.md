# RH3 handoff — authoritative mint contract disclosure

## Read first

- `AGENTS.md`
- `docs/ORC-PRINCIPLES.md`
- `specs/orc-service.allium` — `LeafExecutor.ResearcherSeesMintBehaviorContract`
- `docs/build-timeline/issues/pr34-rlm-hardening/RH3-authoritative-mint-contract.md`
- `components/ontology/src/ai/obney/orc/ontology/interface/schemas.clj` — `description-body`
- `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` — `build-rlm-code-generation-module` and Phase-1 runtime inputs
- `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_sandbox.clj` — `mint-behavior!-fn`
- The orchestrator-propagated DET-E2E-163 test and recorded RED proof.

## Exact change

Make the always-present Phase-1 researcher contract disclose the exact public ontology
`description-body` Malli schema and the `mint-behavior!` name/body/optional-parent call
shape. Use the authoritative schema value; do not copy its fields into a second constant.
The disclosure must not depend on `:auto-classify?`, retrieved candidates, or consumer
instruction prose.

## TDD cycle

The orchestrator supplies one already-RED public asynchronous test. Make only that test
green with the smallest production change. Do not edit the test, spec, checklist, issue,
handoff, guide, or coverage ledger. Do not anticipate later cases.

## Do not touch

- `specs/*.allium`, tests, or documentation.
- Ontology schema/command semantics, classifier/harvest behavior, provider retries, RH1,
  or RH2.
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
