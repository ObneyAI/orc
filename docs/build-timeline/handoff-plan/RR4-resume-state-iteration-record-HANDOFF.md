# RR-4 handoff — resume state and iteration record are separate facts

## Read first

1. `AGENTS.md`
2. `docs/ORC-PRINCIPLES.md`
3. `CONTEXT.md` — Campaign, Iteration, Attempt, Quantum, Frontier, Resume state, and Iteration record
4. `docs/issues/rr-durable/RR-4-resume-state-and-the-iteration-record-become-separate-facts.md`
5. `docs/prd/rr-durable-self-learning.md` — G8 and stories 1–8, 22, 37, 46
6. `docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md` — G8, including the residual sandbox-growth caveat
7. `specs/orc-service.allium` — `Campaign`, `CampaignIteration`, `CampaignYieldsQuantum`, `CampaignResumesAtFrontier`, the three `CampaignIteration*` rules, and `OneIterationRecordPerAttempt`
8. `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` — checkpoint decode, `checkpoint-result`, `persist-terminal-result`, `retry-result`, and the recursive loop
9. `components/orc-service/src/ai/obney/orc/orc_service/core/commands.clj` — `checkpoint-researcher-iteration`
10. `components/orc-service/src/ai/obney/orc/orc_service/core/read_models.clj` — researcher checkpoint/action projections
11. `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj` — researcher context wiring, running-result retick, terminal researcher events, and trace assembly
12. `components/orc-service/test/ai/obney/orc/orc_service/checkpointed_researcher_test.clj` — begin with the RED test named below

## Relevant specification excerpt

> Resume state carries current sandbox values, accumulated usage and timing,
> remaining budgets and the frontier needed to continue, and is superseded on
> every quantum. An iteration record is written once when that attempt reaches
> success, failure or timeout and is never rewritten.

> A campaign advances in quanta. Yielding is not finishing: the campaign keeps
> its frontier, its budgets and its absolute deadline, and the work already
> completed stays completed.

> For two distinct iteration records, campaign, iteration index, and attempt
> ordinal may not all be equal.

## Exact change

Replace new writes of the version-1 checkpoint/history blob with two durable
facts committed in the same command batch:

- `:rlm/researcher-iteration-recorded` is immutable and uniquely identified by
  `[tick-id node-id iteration-index attempt-ordinal]`.
- `:rlm/researcher-resume-state-saved` is the supersedable continuation fact.
  Version 2 carries revision, next iteration, sandbox values, variable creation
  times, usage, cumulative tree time, iteration attempts, campaign start, and
  absolute deadline. It must not carry `:history` or `:terminal-result`.

Extend the internal `:sheet/checkpoint-researcher-iteration` command to accept
`:resume-state` plus `:iteration-record` and atomically append the iteration
fact, the resume-state fact, and (when requested) the resume start. Its CAS must
make an exact duplicate a no-op without collapsing a distinct attempt of the
same iteration. Keep reading version-1 `:rlm/researcher-checkpointed` events so
already-written PR-36 campaigns remain resumable; new execution writes use the
version-2 facts.

Add read projections and public functions named
`get-researcher-resume-state` and `get-researcher-iteration-records`. Thread
those values into the executor as `:researcher-resume-state` and
`:researcher-iteration-records`. Reconstruct prompt history from ordered
iteration records for version 2. A legacy version-1 checkpoint may still use
its embedded history during migration.

The executor must form and validate the iteration record and resume state before
advancing the frontier. Unsupported sandbox data reports the offending key path
and runtime kind without stringifying the value. If a later iteration cannot be
made durable, previously committed iteration records and the last committed
frontier remain unchanged and resumable.

Make trace assembly consume the immutable iteration events as its only source
of researcher history for version-2 campaigns. Do not merge checkpoint history
with terminal `:rlm/researcher-iterations`; richer fields from the immutable
record must survive unchanged.

## TDD cycle list

1. RED already witnessed:
   `iteration-record-and-resume-state-are-distinct-atomic-facts` produced no
   events or projections. Make the one command batch emit exactly one immutable
   iteration record, one resume-state fact, and one resume start; repeat the
   command and prove the batch is idempotent. Do not begin cycle 2 until green.
2. Add a public two-iteration campaign test. Prove each completed iteration has
   exactly one record, every version-2 resume state omits `:history` and
   `:terminal-result`, a resumed provider prompt contains the first record, and
   the public result is unchanged. Make it RED, then green.
3. Add a SQLite close/reopen test using version-2 events. Prove restart reads the
   resume state plus ordered records, continues at the frontier, and does not
   rerun the completed provider turn. RED, then green.
4. Add same-index attempt coverage: a timed-out attempt and its later successful
   attempt remain two records with attempt ordinals 0 and 1; replaying either
   exact command cannot create a duplicate. RED, then green.
5. Add the unsupported-value public test. Complete iteration 0, introduce an
   unsupported value in iteration 1, and prove the error names its key path and
   kind without containing `pr-str` of the value. The iteration-0 record and
   resumable frontier remain unchanged. RED, then green.
6. Add the trace-source test. Give a record a field that the old checkpoint/
   terminal merge dropped, settle on the durable trace publication, and prove
   the trace contains the record verbatim even if legacy terminal history is
   poorer. RED, then green.
7. Add a deterministic byte measurement for equal-sized synthetic iteration
   payloads at increasing N. Measure serialized event bytes after the trace has
   settled and demonstrate bounded bytes-per-added-iteration rather than an
   N-squared history series. Print the observed values in the assertion message;
   do not encode a guessed product limit. RED, then green.
8. Run the checkpointed-researcher, recursive-RLM, and trace-assembly namespaces,
   then `clojure -M:poly test brick:orc-service`. Refactor only after green.

## Propagation status

Final RR-4 line: `10 obligations, 9 covered, 1 uncovered`.

The public and durable tests cover atomic yield/resume persistence, successful,
failed, and timed-out attempts, terminal status, exact-replay idempotency,
distinct same-index retries, stale-frontier rejection, close/reopen recovery,
unsupported-value preservation, record-only trace assembly, and serialized-byte
growth.

The one uncovered obligation is `entity-fields.CampaignIteration`. RR-4 owns
the fact split, identity, immutability, ordering, and status needed to make the
record durable. RR-5 owns the complete evidence content, including timestamps,
duration, shape, emitted tree and code, bounded reasoning/error, and variable
key deltas. Classify that as an aspirational downstream gap; do not broaden
RR-4 or weaken the Allium entity to manufacture 10/10 coverage.

No propagation-generated mock, stub, TODO, or skeleton remains in this slice.

## Inspection result

- Focused checkpoint suite: 15 tests, 109 assertions, 0 failures, 0 errors.
- Checkpoint + recursive researcher + trace suites: 78 tests, 409 assertions,
  0 failures, 0 errors.
- Same-index retry was sampled five times without a duplicate or collapse.
- SQLite version-1 and version-2 close/reopen paths passed.
- Serialized bytes at N=2, 4, and 8 were 12,584; 20,199; and 35,639, with
  bytes per iteration decreasing from 6,292.0 to 5,049.75 to 4,454.875.
- Full `brick:orc-service` verification exited 0 after exercising both Polylith
  project contexts. Credential-gated external-provider cases remained skipped;
  deterministic provider injection supplied the live execution proof.
- `allium check specs`: 12 specs, 35 warnings, 107 info, 0 errors.
- `allium analyse specs`: 12 specs, 0 findings.
- `/weed` classifications: the complete CampaignIteration evidence payload is
  aspirational RR-5 work; version-1 read fallback is intentional migration
  compatibility; ownership epochs/concurrent recovery are aspirational RR-7
  work; retirement of the old terminal iteration event is intentionally owned
  by RR-16's durable-stream projection.

## Do NOT touch

- `specs/*.allium`; the orchestrator is the only spec writer. Report a proposed
  divergence classification instead.
- RR-5’s bounded evidence digest, RR-6 generated-source compilation, RR-7 effect
  claims, RR-14 sandbox delta encoding, RR-15 default-on behavior, RR-16 stream
  projection, ontology/evaluation consumers, or other worktrees.
- RR-1 deadline/repair semantics, RR-2 tool-caller arity/safety behavior, or
  RR-3 child reconstruction/status/duration behavior.
- Existing generated or strengthened tests merely to obtain green.
- The ontology worktree at `/Users/darylroberts/Desktop/Code/orc`.

## Orchestrator live QA

The orchestrator will independently run the command and public replay paths,
close/reopen the SQLite store, inspect raw event ordering and byte totals, wait
for the final trace publication, run both Polylith project contexts, rerun
Allium check/analyse, `/weed` check-mode, and `/inspect-orc`. No external model
spend is required; provider output is an injected deterministic capability.

## Dependency rule

RR-4 has no upstream produced-API dependency. RR-5, RR-6, RR-14, RR-16, and
RR-17 must not receive implementation handoffs until this slice lands and the
orchestrator writes their briefs from the real event names, projection
signatures, ordering, and compatibility behavior produced here.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

## Report back

- Preserve the final coverage line verbatim: `10 obligations, 9 covered, 1 uncovered`.
- List each RED and GREEN command/result, exact files changed, byte measurements,
  migration behavior, and anything unverified.
- Classify every discovered divergence as spec bug, code bug, aspirational
  design, or intentional gap.
- Declare every mock, stub, TODO or skeleton; do not leave one implicit.
- Do not commit.
