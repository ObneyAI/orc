# RR-27 — The streaming tap never occupies the engine's dispatch pool

## Parent

RR-durable self-learning arc — follow-up from the RR-26 whole-spec integration inspection (the Sept 11 gate wedge).

## What to build

`streaming/ensure-tap!` forwards every durable event into covering subscriptions from an `async/go-loop`, i.e. on
the fixed eight-thread core.async dispatch pool that workflow execution and pubsub distribution share. The router
beside it (`start-router!`) deliberately runs on `async/thread` for exactly that reason, and its docstring says so;
the invariant was never applied to the tap. Anything slow or blocking reachable from the tap loop —
`subs-covering`, a subscription's `src-chan` put, a test double — therefore holds an engine dispatch thread. The
Sept 11 gate wedge sat on precisely that: a test redefining `subs-covering` to block on an unbounded promise
inside the tap loop, and a main thread parked on a one-second `alts!!` that never returned (thread dump and
root-cause report in `.rr-durable-notes/WEDGE-ROOT-CAUSE.md`).

Move the tap loop to its own thread (`async/thread` + `<!!`, matching the router), keep every existing
streaming guarantee byte-for-byte (ordering, sliding buffer, one tap per pubsub, shutdown shape), and harden the
one test that blocks inside the tap so a wrong synchronisation fails fast and loud instead of hanging a gate.

## Acceptance criteria

- [ ] No durable-event forwarding work runs on a dispatch-pool thread: while the tap is blocked inside its
      subscription path, every `async-dispatch-*` thread is free of `ensure-tap!` frames
- [ ] A blocked tap path delays no engine work: a workflow executes to completion while the tap is held blocked
- [ ] Ordering, sliding-buffer, one-tap-per-pubsub and `shutdown-taps!` behaviour are unchanged (existing
      streaming suites green, including `det-e2e-278-durable-order-precedes-root-stream-closure`)
- [ ] `durable_iteration_stream_test` never derefs an unbounded promise on a shared thread: the wait is bounded and
      a timeout is an assertion failure, not a hang

## Spec obligations covered

`allium plan` emits no obligation for a contract's prose `@invariant`, so the invariant this slice exists for —
`ExecutionEventStream.StreamingNeverOccupiesTheEngineDispatchPool` (tended by the orchestrator for this slice) — is
covered by the slice's own tests, not by a generated one. The contract's planned obligations
(`contract-signature.ExecutionEventStream.subscribe`, `.next_envelope`, `.close`) are already covered by the
existing streaming suites and must stay green. Coverage line to report: `3 obligations, 3 covered, 0 uncovered`,
plus the prose invariant named explicitly.

## Test seams

- Thread identity of the forwarding work (thread names / stack frames) — deterministic, no timing.
- A workflow executing while the tap is deliberately held blocked — deterministic via a promise the test controls.
- The existing ordering test, hardened.

## Blocked by

RR-26 (closed).

## Handoff plan

`docs/build-timeline/handoff-plan/RR27-streaming-tap-off-dispatch-pool-HANDOFF.md`

## Disciplines (verbatim — do not summarise, do not skip)

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions.
  Reproduce -> minimize -> fix the actual cause. Don't blame the network or the model — the cause is in the code or
  the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can
  fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red -> green -> refactor, one test at a time.** Vertical tracer-bullet slices, never
  horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive
  refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, network, storage)
  as capabilities that **default to the real impl and are faked in tests**.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing. Then
  turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's
  "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

Standing rules for this arc:

- **Never weaken a generated test to make it pass.** A `/propagate`-generated test is contract. If it is wrong, the
  spec is wrong: report it, the orchestrator `/tend`s and re-propagates.
- **A generated test green before you implement is a finding**, not success — already-covered or vacuous. Report it.
- **Default-path behaviour must not change** until RR-15 flips the default. Every slice before it keeps the
  non-checkpointed path byte-identical.

## Do NOT touch

- `specs/*.allium` — the orchestrator is the only spec writer; report proposed divergences instead.
- The router (`start-router!`), `subscribe-execution`, `execute-stream`, envelope normalisation, `emit!`,
  `link-child!` — this slice changes WHERE the tap loop runs, not what it does.
- Any test other than `durable_iteration_stream_test.clj` and the new RR-27 namespace.

## Report back

- The `/propagate` coverage line verbatim: `N obligations, M covered, K uncovered`, with a reason for every
  uncovered one. No silent caps.
- Any propagate-emitted mock, stub or TODO skeleton, declared explicitly as a tracked gap.
- Proposed divergence classifications for anything where spec and code disagree.
- What you could NOT verify, stated plainly.

## Orchestrator gates (run after you report)

`/inspect-orc` against this slice: re-run the proof independently, re-read the code, try to break the claims, plus the
three ORC-specific gates — spec-conformance (`allium check` / `analyse` error-free by **severity count** on touched
specs), `/weed` check-mode with classified divergences, and the obligation audit (the coverage line above must survive
into the slice report). Allium's internal verify is the CLAIM; `/inspect-orc` is the falsification.
