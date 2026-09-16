# RR-28 handoff — Remove the retired `skipped` status from code

Issue: `docs/issues/rr-durable/RR-28-remove-the-retired-skipped-status-from-code.md` (read it whole).
Work in `/Users/darylroberts/Desktop/Code/orc-rr-durable-arc` (branch `feature/rr-durable-self-learning-arc`). Run
tests with `JVM_XMX=1600m zsh /private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/run-focused.sh <log> <ns…>`
(plain `-M:dev:test`). ONE JVM at a time; never kill a JVM whose working directory is not this worktree (another team's
benchmark runs on this machine); confirm 0 orphan JVMs after every run. Do not run `poly test` or any whole-brick build
(the orchestrator runs the gates). Do not commit, push or stash. Never edit `specs/*.allium`.

## Goal

The spec no longer declares a `skipped` node status (grill decision D1, tended in `45043b26`). Make the code declare
exactly the lifecycle the spec declares: remove the retired enum value from the node-trace record schema, the skip
count from the query result schema, and the tally that computed it.

## Read first

1. `specs/orc-service.allium` — `enum NodeExecutionStatus { running | success | failure | partial | timeout | blocked }`
   and the `NodeExecution` `transitions status` block (terminal: success, failure, partial, timeout, blocked). This is
   the contract; the code must match it.
2. `components/orc-service/src/ai/obney/orc/orc_service/interface/schemas.clj` line ~410 (`::node-trace`'s `:status`
   enum still lists `:skipped`) and line ~2402 (`[:skip-count :int]` in a query result schema — find which query it
   belongs to and every consumer of that key).
3. `components/orc-service/src/ai/obney/orc/orc_service/core/queries.clj` line ~714 (`:skip-count (get statuses :skipped 0)`).
4. `components/orc-service/test/ai/obney/orc/orc_service/rr26_node_trace_status_test.clj` — an orchestrator-written
   test whose "statuses that already validated" list includes `:skipped`; that entry is now WRONG by decision and you
   may (must) update that one list — nothing else in that test.
5. `docs/build-timeline/grill-sessions/rr-weed-followup-decisions.md` §D1 for the reasoning.

## The exact change

- Remove `:skipped` from the `::node-trace` status enum.
- Remove `:skip-count` from the query result schema and from the query that computes it (and any read of it).
- Update the RR-26 node-trace test's status list; add an assertion that a `:skipped` record is now REJECTED by the
  schema.

## TDD cycle list

1. **RED** in `rr26_node_trace_status_test.clj`: assert `(not (m/validate node-trace (trace-record :skipped)))` and
   remove `:skipped` from the "already validated" list. RED because the enum still admits it.
2. GREEN: remove the enum value.
3. **RED** a small test that the trace-summary query result (whichever query owns `:skip-count`) carries no
   `:skip-count` key — drive it through the real query path used by an existing query test; RED because the key is
   still emitted. GREEN: remove the tally and the schema field.
4. Regression: the node-trace, query, streaming and RR-26/RR-27 suites green (`rr26-node-trace-status-test`,
   `rr27-streaming-tap-thread-test`, `durable-iteration-stream-test`, `streaming-test`, and the query test
   namespace(s) you touched).
5. Repo-wide search: `:skipped` and `skip-count` absent from `components/*/src`.

Propagate note (orchestrator): `enum-comparable.NodeExecutionStatus` and `transition-terminal.NodeExecution.status`
are already covered by existing suites — a generated test green before the change is the expected finding. Report the
coverage line as `2 obligations, 2 covered, 0 uncovered`.

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

- `specs/*.allium`; the executor; the streaming component; any status other than the retired one.

## Report back

Files changed; each cycle's RED and GREEN `RESULT` lines verbatim; the coverage line; the repo-wide search output;
what you could NOT verify; the orphan-JVM check.
