# RH2 — recover a recursive researcher after an empty Phase-1 code turn

**Blocked by:** RH1 inspected and pushed as `bc55e0f2`

**Spec obligation covered:** `LeafExecutor.ResearcherBlankCodeIsRecoverable`

## Verified mechanism

The live PR check produced two apparently different failures: one model first emitted
malformed SCI code and another successfully executed a generated tree. In both cases,
the following Phase-1 response contained no executable `:code`. The recursive executor
currently returns `:failure` immediately at that boundary even though `:max-iterations`
and the execution budget still permit another turn. Recoverable SCI errors already enter
history and recur; an empty successful response is the inconsistent path.

This is not an output-schema rejection and rerunning the nondeterministic check would
only hide the defect. The repair makes an empty, non-error response an attributable
iteration error and recurs. Explicit provider errors, security violations, cancellation,
and exhausted budgets remain terminal.

## TDD cycles

1. RED: through the public asynchronous recursive-researcher boundary, return an empty
   first Phase-1 response and a valid `final!` second response. GREEN: the tick succeeds,
   both calls' usage is accumulated, the empty-turn evidence reaches the second prompt,
   and the durable projection replays the final value.
2. RED/GREEN: return empty responses through `:max-iterations`. Verify exact call count
   and the ordinary max-iterations failure, not immediate blank-code failure.
3. RED/GREEN: return an explicit provider error with no code. Verify it remains terminal
   and is not converted into an application-level retry.

## Verification

The initial public tracer bullet was RED with seven assertion failures: execution
stopped after one empty response, usage included only that call, and neither the
answer nor success reached durable projection replay. The minimal recursive repair
made it green. Independent inspection then found and reproduced a terminal-mode scope
leak (three calls and max-iterations failure instead of one immediate failure); a
separate public RED/GREEN cycle corrected it. The completed focused namespace passed
16 tests and 75 assertions, covering recovery, durable iteration evidence, cumulative
usage, exact exhaustion, explicit provider-error precedence, and terminal compatibility.
The real OpenRouter adaptive-loop journey then passed 2 tests and 43 assertions after
recovering through generated Phase-2 work.

## Do not touch

- Output validation, optional-write, tool-contract, or tool-gate behavior repaired by RH1.
- Terminal-mode compatibility semantics.
- Provider retry internals; this is recursive Phase-1 self-correction, not transport retry.
- Other worktrees or branches.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.
