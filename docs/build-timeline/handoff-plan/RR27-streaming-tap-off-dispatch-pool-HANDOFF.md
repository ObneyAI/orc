# RR-27 handoff — The streaming tap never occupies the engine's dispatch pool

Slice issue: `docs/issues/rr-durable/RR-27-streaming-tap-off-the-dispatch-pool.md` (read it whole).
Work in `/Users/darylroberts/Desktop/Code/orc-rr-durable-arc` (branch `feature/rr-durable-self-learning-arc`). Run
tests with the scratchpad runner `zsh /private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/run-focused.sh <log> <ns…>`
(plain `-M:dev:test`; prefix `JVM_XMX=1600m` — the machine is memory-starved by another team's benchmark). ONE JVM at a
time; never kill a JVM that is not yours (check `lsof -p <pid> -d cwd`); confirm 0 orphan JVMs after every run.

## Goal

Move `streaming/ensure-tap!`'s forwarding loop off the fixed core.async dispatch pool onto its own thread, exactly as
`start-router!` already does and for the reason its docstring states, without changing any observable streaming
behaviour; and harden the one test that blocks inside the tap so a wrong synchronisation fails in seconds instead of
wedging a full gate.

## Read first

1. `components/orc-service/src/ai/obney/orc/orc_service/core/streaming.clj` lines 377–395 (`start-router!` and its
   rationale: "Runs on a dedicated thread (async/thread), NOT a go block … blocking work that would starve the fixed
   core.async dispatch pool") and lines 455–484 (`ensure-tap!`: one sliding-buffered channel per pubsub instance,
   subscribed to every tapped event type, drained by an `async/go-loop` that calls `subs-covering` and `async/put!`
   per event, wrapped in try/catch, storing `[ch]` in `taps`), plus `shutdown-taps!` and `reset-all!` right after.
2. `components/orc-service/test/ai/obney/orc/orc_service/durable_iteration_stream_test.clj`: `take-until` (line 38),
   `drain!` (line 25), and `det-e2e-278-durable-order-precedes-root-stream-closure` (line 407) — note the
   `with-redefs-fn` on `subs-covering` that does `@release-child-tap` (line 427, an UNBOUNDED deref, on whatever
   thread runs the tap loop) and that `release-child-tap` is delivered only after `take-until` returns (line 461+).
3. `.rr-durable-notes/WEDGE-ROOT-CAUSE.md` §3 and §5 — the confirmed hazard and the proposed fixes. §1 explains why
   the thread dump shows two generations of core.async singletons (poly's per-brick classloader) — not your concern.
4. Spec excerpt, verbatim (`specs/orc-service.allium`, contract `ExecutionEventStream`):

       @invariant StreamingNeverOccupiesTheEngineDispatchPool
           -- Forwarding a durable event into subscriptions runs on its own thread,
           -- never on the shared fixed dispatch pool that workflow execution and
           -- pubsub distribution depend on. A subscription path that is slow or
           -- blocked therefore holds no engine capacity, so it cannot delay or
           -- wedge workflow execution — it can only be bounded, dropped or closed.

       @invariant StalledSubscriberRemainsBounded
           -- A subscriber that stops consuming cannot exceed its configured
           -- delivery capacity or delay workflow execution. …

## The exact change

- `ensure-tap!`: replace `(async/go-loop [] (when-let [event (async/<! ch)] … (recur)))` with an `async/thread`
  running the same loop over `async/<!!`. Keep the sliding buffer, the per-pubsub singleton guard, the try/catch
  (a throw must never kill the process-wide tap), the identical forwarding logic, and the `(swap! taps assoc ps [ch])`
  storage shape so `shutdown-taps!` is unchanged. Closing `ch` must still end the loop (`<!!` returns nil).
- Add a comment on the loop cross-referencing the router's rationale and the spec invariant by name.
- `durable_iteration_stream_test.clj` line 427: `(deref release-child-tap 5000 ::release-timed-out)`; after the
  existing assertions add `(is (not= ::release-timed-out …))` on the recorded result (capture it in an atom the
  redefinition writes to). Do not change what `det-e2e-278` proves.

## TDD cycle list (RED first, one at a time; the orchestrator confirmed no `/propagate` test applies — the invariant
is prose, so these are the contract)

1. **RED** `rr27_streaming_tap_thread_test.clj` / `forwarding-never-runs-on-a-dispatch-thread`: subscribe to a tick,
   redefine `subs-covering` (via `with-redefs-fn`, as the existing test does) to record `(.getName (Thread/currentThread))`
   into an atom and then call the real function; publish one tapped durable event; wait (bounded) for the envelope;
   assert the recorded thread name does NOT start with `async-dispatch-`. With the `go-loop` this is RED (the name is
   `async-dispatch-N`); with `async/thread` it is GREEN (`async-thread-macro-N`).
2. **RED** `a-blocked-tap-path-holds-no-dispatch-thread-and-delays-no-workflow`: redefine `subs-covering` to block on a
   promise the test controls (bounded 10 s); publish a tapped event so the tap is held; THEN (a) enumerate live threads
   via `Thread/getAllStackTraces` and assert no thread whose name starts with `async-dispatch-` has a frame whose class
   name contains `streaming$ensure_tap_BANG_`; (b) execute a small deterministic workflow with `sheet/execute` (the
   `:test` provider, as other tests do) and assert it completes `:success` within its timeout while the tap is still
   held; then release the promise and assert the held envelope arrives. With the `go-loop`, (a) fails (a dispatch
   thread carries the frame); with `async/thread` both hold.
3. Implement the `async/thread` change; 1 and 2 go GREEN.
4. Harden `det-e2e-278` as specified; run the whole `durable-iteration-stream-test` namespace and the other streaming
   suites (`grep -l "streaming" components/orc-service/test/**/*.clj`) green.
5. Regression sweep: `checkpointed-researcher-test`, `durable-iteration-stream-test`, `rr26-two-workers-one-frontier-test`,
   `bounded-campaign-operations-test` green.

## Live QA the orchestrator will run

The full `orc-service` brick, twice if timing matters, and a thread-dump spot check that no `async-dispatch-*` thread
ever holds an `ensure-tap!` frame during the streaming suites.

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
- `start-router!`, `subscribe-execution`, `execute-stream`, `emit!`, `link-child!`, envelope normalisation, the
  sliding-buffer size, `tapped-event-types`, `shutdown-taps!`/`reset-all!` shapes.
- Any test other than `durable_iteration_stream_test.clj` and the new RR-27 namespace.
- Do not weaken or delete any existing assertion.

## Report back

- Coverage line verbatim: `3 obligations, 3 covered, 0 uncovered` (the three `ExecutionEventStream` signatures, already
  covered by existing suites — confirm they are still green) plus the prose invariant covered by cycles 1–2.
- Every test file you created or changed, with RED and GREEN results (the actual `RESULT` lines from the runner logs).
- Any propagate-emitted mock, stub or TODO skeleton (there should be none) declared explicitly.
- Proposed divergence classifications for anything where spec and code disagree.
- What you could NOT verify, stated plainly, and the orphan-JVM check result after your last run.
