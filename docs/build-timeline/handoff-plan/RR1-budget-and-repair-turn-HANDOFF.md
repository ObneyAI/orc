# RR-1 handoff — budget evidence and recursive repair turn

## Read first

1. `AGENTS.md`
2. `docs/ORC-PRINCIPLES.md`
3. `docs/issues/rr-durable/RR-1-phase-1-budget-branch-and-phase-2-repair-turn-restored.md`
4. `docs/prd/rr-durable-self-learning.md` — Foundation repairs
5. `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` — `resolve-phase2-budget` and `execute-repl-researcher-rlm`
6. `components/orc-service/test/ai/obney/orc/orc_service/recursive_rlm_test.clj` — the two RR-1 tests named below

## Relevant specification excerpt

From `specs/orc-service.allium`:

> A checkpointed campaign records one absolute campaign deadline when it starts. Yield, retick and process restart do not reset the deadline, the accumulated usage and timing budget, or the count of provider calls its trace family has already spent. Usage accrues once per completed iteration, never once per yield.

> An iteration timeout preserves every preceding completed iteration and yields a retry of the same iteration from the last checkpoint, retaining its stable logical action identities. Exhausting the configured iteration-attempt limit terminates the campaign as timeout.

This repair predates the explicit campaign model, so `/propagate` reports: `0 obligations, 0 covered, 0 uncovered`. The executable contract is the strengthened public-behavior acceptance test; do not manufacture an Allium ID.

## Exact change

Fix the two ordering defects in `execute-repl-researcher-rlm`:

- When the stored campaign deadline is exactly the deadline derived from the shared node/tick Phase-1/Phase-2 budget, budget exhaustion must win and return the complete budget/timing evidence. A genuinely distinct absolute campaign deadline must still return `:timeout` with `:timeout-kind :campaign`.
- Route recursive-mode Phase-2 timeout results through the existing summary/merge/history path so the next Phase-1 call receives `:child-outcome {:status :timeout ...}` and can repair. Preserve the current terminal timeout result for explicit non-recursive mode. Checkpoint retry semantics remain distinct and must not be silently changed.

## TDD cycle list

1. RED already witnessed: `derived-campaign-deadline-does-not-shadow-phase-one-budget-evidence` returns campaign timeout and omits budget/cumulative evidence. Make only this test green; re-run `resumed-campaign-keeps-original-absolute-deadline` unchanged.
2. RED already witnessed: `recursive-phase-two-timeout-becomes-repair-turn-evidence` stops after one provider call with `:timeout`. Make it reach the second turn, success, and timeout child evidence.
3. Add or identify the explicit non-recursive timeout regression and prove it remains terminal.
4. Run the complete `recursive-rlm-test` namespace and refactor only after green.

## Do NOT touch

- `specs/*.allium`; the orchestrator is the only spec writer.
- RR-2, RR-3, either prototype, ontology/harvest behavior, or other worktrees.
- Existing deadline tests or generated/strengthened tests merely to obtain green.

## Orchestrator live QA

The orchestrator will independently run the focused namespace, the public checkpointed researcher path, the `orc-service` brick, Allium check/analyse, `/weed` check-mode and `/inspect-orc`. No external provider spend is required for this deterministic repair.

## Dependency rule

RR-1 has no upstream API dependency and may implement only against the rebased PR #36 runtime at this branch head. Any newly discovered dependency is reported; no guessed downstream API may be introduced.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

## Report back

- Preserve the coverage line verbatim: `0 obligations, 0 covered, 0 uncovered` with the reason above.
- List each RED and GREEN command/result, exact files changed, and any unverified behavior.
- Classify every discovered divergence as spec bug, code bug, aspirational design, or intentional gap.
- Declare any mock, stub, TODO or skeleton; do not leave one implicit.
