# RH1 handoff — exact researcher declared-output contract

## Read first

1. `AGENTS.md`
2. `docs/ORC-PRINCIPLES.md`
3. `specs/orc-service.allium` — `LeafExecutor`
4. `docs/build-timeline/issues/pr34-rlm-hardening/RH1-exact-researcher-output-contract.md`
5. Researcher completion in `todo_processors.clj`, validation in `executor.clj`, and
   `repl_researcher_consumer_gate_e2e_test.clj`

## Exact change

Through the public asynchronous workflow interface, enforce required presence/non-null,
explicit top-level optional-write absence, schema validation, and preservation of Malli-
normalized declared values. Preserve existing internal observability fields without
allowing raw declared values to overwrite validated declared values.

## TDD cycle list

Execute RH1's seven red→green cycles in order, one test at a time. After each green,
re-run the whole focused namespace before starting the next cycle. Assert returned
results, durable events, and projection replay.

## Do not touch

Do not edit any other worktree, Survey/ontology behavior, benchmark traces, or generated
tests to make them pass. Do not weaken the PR's existing invalid-output test.

## Orchestrator live QA

Run the existing real-LLM researcher structured-output test, inspect durable provenance,
then run the full `orc-service` brick and Allium/weed/inspect-orc gates.

## Dependency rule

This slice depends only on PR #34 SHA `38e0319b`. No handoff may assume APIs from any
unmerged ontology or local feature worktree.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

Specs are orchestrator-owned and must not be edited by an implementation subagent.
