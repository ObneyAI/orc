# RH4 — preserve researcher configuration across public round-trips

**Spec obligation covered**: `WorkflowDefinitionConstruction.ResearcherConfigurationSurvivesRoundTrip`

## Root cause

The public researcher constructor and durable command/event/read-model path retain
behavior-affecting configuration, but `export-sheet` reconstructs a smaller node map.
`export-to-dsl` and `import-sheet` then operate on that already-truncated shape, and
their own researcher mappings omit additional fields. A normal export/re-import can
therefore erase `:tool-caller-fn` and widen a gated workflow to its base caller; it can
also erase `:options :optional-writes` and change output semantics.

## Tracer bullet

1. DET-E2E-164 builds a researcher through the public DSL with tool contracts, a
   consumer tool gate, browser tools, ontology context, and execution options.
2. It proves the exact field map at public EDN export, rendered/evaluated DSL, and
   durable EDN import.
3. Repair the three authoritative researcher mappings in `core/dsl.clj`; do not add
   a parallel serializer or change runtime permission semantics.

## Verification

The public tracer was RED at all three assertions before implementation: EDN export,
rendered/evaluated DSL, and durable EDN import each lost part of the researcher
configuration. The three existing mappings were repaired one boundary at a time.
Independent inspection then found a second RED: generic empty-collection filtering
still erased explicit `:rlm {}` and `:context {}` choices from rendered DSL, changing
execution mode and context-classification behavior. A researcher-scoped formatter
exception preserves those exact choices without changing other node formats. The
complete `dsl-roundtrip-test` namespace passed 17 tests and 33 assertions, and
independent inspection reproduced both populated and explicit-empty public round-trips
with no remaining field loss or permission widening.

## Handoff dependency

Implement only after DET-E2E-164 is run RED against the real current public APIs.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.
