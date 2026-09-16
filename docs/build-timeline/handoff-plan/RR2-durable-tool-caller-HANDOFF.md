# RR-2 handoff — durable tool caller contract

## Read first

1. `AGENTS.md`
2. `docs/ORC-PRINCIPLES.md`
3. `docs/issues/rr-durable/RR-2-durable-tool-caller-honours-the-arity-guard.md`
4. `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` — `node-call-tool-fn`, `phase1-call-tool-fn`, and checkpointed `call-tool-fn`
5. `components/orc-service/src/ai/obney/orc/orc_service/core/sci_sandbox.clj` — `create-mcp-tool-fn`
6. `components/orc-service/test/ai/obney/orc/orc_service/recursive_rlm_test.clj` — the RR-2 tests named below

## Relevant specification excerpt

From `specs/orc-service.allium`:

> A checkpointed researcher may invoke an effectful inline tool only when its bound contract explicitly declares checkpoint safety. ORC supplies that logical action's stable idempotency key through the tool execution context on every attempt; the tool is responsible for deduplicating the external effect. A tool that does not declare this capability is rejected before its effect begins.

This arity repair itself predates the explicit campaign model, so `/propagate` reports: `0 obligations, 0 covered, 0 uncovered`. Its public behavioral tests are the contract.

## Exact change

- Validate every configured MCP tool contract before the first provider call when checkpointing is enabled; an undeclared or false `:checkpoint-safe?` fails closed before model or tool work.
- Preserve the host's existing two- versus three-argument caller contract. A two-argument caller must be invoked as `(tool-name args)`. A caller/context seam that supports the third argument must continue to receive the ORC idempotency/tool context. Do not catch an arity exception and present it as a successful tool result.
- Keep the non-checkpointed path byte-compatible.

## TDD cycle list

1. RED already witnessed: `configured-unsafe-tool-is-rejected-before-provider-or-effect` currently succeeds after one provider call. Make it fail before provider/effect invocation.
2. RED already witnessed: `checkpointed-safe-tool-preserves-two-argument-host-contract` never reaches the two-argument host because the durable wrapper calls three arguments. Make the host observe the exact tool name/args.
3. Re-run `checkpointed-tools-require-and-receive-idempotency-contract` and preserve its three-argument context/idempotency-key proof.
4. Add or identify an incompatible-arity diagnostic assertion that names the expected contract rather than leaking a raw Clojure arity exception.
5. Run the complete `recursive-rlm-test` and `repl-researcher-tool-caller-test` namespaces.

## Do NOT touch

- `specs/*.allium`; the orchestrator is the only spec writer.
- RR-1, RR-3, claim-epoch identity, either prototype, or other worktrees.
- The strengthened unsafe-tool test or any existing tool-context test merely to obtain green.

## Orchestrator live QA

The orchestrator will independently exercise both host arities through the researcher public boundary, inspect that the unsafe path emitted no effect, then run the `orc-service` brick and Allium/weed/inspect-orc gates.

## Dependency rule

RR-2 has no upstream API dependency. Do not invent the future RR-7 claim API; preserve the landed PR #36 context shape and report any seam that actually requires a later slice.

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
