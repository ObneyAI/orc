# RH3 — disclose the authoritative mint contract independently of classifier injection

**Blocked by:** RH2 implementation and independent inspection

**Spec obligation covered:** `LeafExecutor.ResearcherSeesMintBehaviorContract`

## Verified mechanism

The real DET-E2E-102 journey configures its first recursive researcher with
`:auto-classify? false`. The exact `mint-behavior!` description-body shape currently
exists only inside `apply-r05-classifier-context`'s optional corpus prepend. The
always-present Phase-1 module names no mint primitive and carries no mint schema, even
though the sandbox binds the function. The consumer instruction therefore says only
"principle-shaped strengths and weaknesses"; the live model produced the predictable
stale guesses (vectors of strings, then maps with non-schema keys), and Grain correctly
rejected both.

This is a production contract-disclosure gap, not provider variance and not an output
normalization defect. The ontology's public `description-body` Malli value is the
authority. Phase 1 must receive that exact data contract whenever the primitive is
available; classifier context may add examples but cannot own the contract.

## Tracer-bullet TDD cycle

1. RED: through a public asynchronous recursive researcher with
   `:auto-classify? false`, capture the actual model module/input and return a valid
   `mint-behavior!` call followed by `final!`. Prove the authoritative contract is
   absent before implementation.
2. GREEN: disclose the exact public ontology schema and call shape in the always-present
   Phase-1 contract. The workflow succeeds, emits the minted audit with sheet/tick
   provenance, and returns its declared output.
3. REFACTOR/guard: prove classifier-enabled and classifier-disabled paths share the same
   authoritative contract rather than maintaining duplicate schema prose.

## Verification

The public tracer bullet was RED with four contract-disclosure failures while the real
mint command, provenance audit, and final output already passed. Production now derives
the contract from the actually registered Grain command schema without an ontology
dependency or copied field list. Independent inspection then found two fail-open edges:
an unregistered optional command remained bound in SCI, and a malformed registered
schema advertised nil fields. Separate RED/GREEN cycles now omit the unavailable
primitive and reject malformed registered schemas. The focused namespace passed 3 tests
/ 11 assertions; a combined run of mint, classifier, sandbox, tool/output-contract, and
RH1/RH2 suites passed 46 tests / 219 assertions. The real OpenRouter adaptive-loop
journey then passed 2 tests / 43 assertions with classification, evolution, and
mint-capable Phase-1 execution.

## Do not touch

- The `description-body` schema or mint command validation.
- Classifier retrieval, ranking, harvest gates, or contextual examples.
- Provider retry/timeout policy or RH1/RH2 behavior.
- The live test's consumer instruction to restate implementation-specific schema keys.
- Other worktrees or branches.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.
