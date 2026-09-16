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

- [x] No durable-event forwarding work runs on a dispatch-pool thread: while the tap is blocked inside its
      subscription path, every `async-dispatch-*` thread is free of `ensure-tap!` frames
- [x] A blocked tap path delays no engine work: a workflow executes to completion while the tap is held blocked
- [x] Ordering, sliding-buffer, one-tap-per-pubsub and `shutdown-taps!` behaviour are unchanged (existing
      streaming suites green, including `det-e2e-278-durable-order-precedes-root-stream-closure`)
- [x] `durable_iteration_stream_test` never derefs an unbounded promise on a shared thread: the wait is bounded and
      a timeout is an assertion failure, not a hang

## Spec obligations covered

`allium plan` emits no obligation for a contract's prose `@invariant`, so the invariant this slice exists for —
`ExecutionEventStream.StreamingNeverOccupiesTheEngineDispatchPool` (tended by the orchestrator for this slice) — is
covered by the slice's own tests, not by a generated one. The contract's planned obligations
(`contract-signature.ExecutionEventStream.subscribe`, `.next_envelope`, `.close`) are already covered by the
existing streaming suites and must stay green. Coverage line to report: `3 obligations, 3 covered, 0 uncovered`,
plus the prose invariant named explicitly.

## Verification

The streaming tap now forwards every durable event from its own dedicated thread, never from the fixed
core.async dispatch pool that workflow execution and pubsub distribution share — the discipline the router
beside it already applied, and the one the Sept 11 gate wedge showed was missing. The change is confined to
where the loop runs: the sliding buffer, the one-tap-per-pubsub guard, the per-event try/catch, the forwarding
logic and the shutdown shape are byte-for-byte what they were, and closing the tap channel still ends the
loop. The one test that deliberately blocks inside the tap now bounds that wait and asserts it did not time
out, so a wrong synchronisation fails in seconds instead of parking a brick run for half an hour.

Two red-first tests pin the invariant. The first records the thread the forwarding work runs on and asserts
it is not a dispatch-pool thread — RED under the old loop (`async-dispatch-8`), GREEN after. The second holds
the tap blocked on a promise the test controls and, while it is held, enumerates every live thread and asserts
no dispatch-pool thread carries a tap frame — RED under the old loop, which was caught mid-frame on
`async-dispatch-2`, the wedge's mechanism reproduced on demand — then executes a workflow to completion while
the tap is still held, releases it, and receives the held envelope. The workflow-completes half of that test
was already true under the old loop: one held thread is not pool exhaustion, consistent with the root-cause
report's finding that the wedge was a specific hazard rather than steady-state starvation.

Independent inspection re-read the production diff against the brief line by line, confirmed no `.allium`
file was touched by the implementer, and re-ran the RR-27 namespace with the durable-iteration and both
streaming suites: 28 tests / 224 assertions, 0 failures. On the final tree the complete two-project `orc-service` brick passes with exit 0 in 88 minutes 34 seconds under `-J-Djava.awt.headless=true` with a 3 GB heap cap (130 namespaces, 1062 tests / 5933 assertions per graph, 0 failures, 0 errors). Allium holds at the
current baseline (114 information diagnostics, 35 warnings, 0 errors, 0 analyse findings) after the
orchestrator's tend of `ExecutionEventStream.StreamingNeverOccupiesTheEngineDispatchPool`. Coverage:
`3 obligations, 3 covered, 0 uncovered` — the contract's three planned signatures stay green in the existing
suites; the prose invariant, which `allium plan` does not emit, is covered by the two RR-27 tests. No generated
mock, stub, TODO or skeleton; no weakened test; no divergence.

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
