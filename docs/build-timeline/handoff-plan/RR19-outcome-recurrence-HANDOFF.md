# RR-19 implementation handoff: recurrence is counted at outcome

## Goal

Move tree-class recurrence from classification intent to one durable campaign
verdict. Classification remains the immutable attribution/audit fact. A
successful, failed, or timed-out researcher campaign contributes exactly one
occurrence; blocked, cancelled, and abandoned campaigns contribute none.
Completed iteration evidence from cancelled or abandoned work remains
inspectable and available to a later class reflection.

## Read first

1. `AGENTS.md` and `docs/ORC-PRINCIPLES.md`.
2. `docs/issues/rr-durable/RR-19-recurrence-is-counted-at-outcome-not-intent.md`.
3. `docs/prd/rr-durable-self-learning.md`, especially “Evidence and the
   self-learning loop”.
4. `specs/orc-service.allium`: `Campaign`, its terminal transitions,
   `CampaignIsAbandoned`, `AbandonedCampaignsRecordNoVerdict`, and
   `CancelledCampaignsRecordCause`.
5. `specs/ontology.allium`: the harvest commentary and
   `TreeClassOccurrenceRecorded` consumers.
6. RR-18’s completed handoff, because classification identity and its
   first-writer-wins occurrence key are already real:
   `docs/build-timeline/handoff-plan/RR18-classification-once-per-campaign-HANDOFF.md`.
7. Production seams:
   - `components/ontology/src/ai/obney/orc/ontology/core/commands.clj`
   - `components/ontology/src/ai/obney/orc/ontology/core/read_models.clj`
   - `components/ontology/src/ai/obney/orc/ontology/core/todo_processors.clj`
   - `components/ontology/src/ai/obney/orc/ontology/core/consolidator.clj`
   - `components/ontology/src/ai/obney/orc/ontology/core/harvest.clj`
   - `components/ontology/src/ai/obney/orc/ontology/interface/schemas.clj`
   - `components/orc-service/src/ai/obney/orc/orc_service/core/read_models.clj`
   - `components/evaluation/src/ai/obney/orc/evaluation/core/judge_runtime.clj`
8. Existing tests around classification idempotency, campaign terminal states,
   harvest occurrence scores, consolidation thresholds, and cancellation.

## Verified mechanism map

- `:ontology/task-classified` is currently both the attribution fact and the
  recurrence source. It increments `:tree-class` consolidation counters,
  appends the class's recent occurrence order, supplies harvest occurrence
  pairs, and triggers consolidation/harvest processors.
- RR-18 makes that classification fact one-per-`[source-sheet-id
  source-tick-id]`, but it is still written before model work. It must remain
  available to runtime and score attribution.
- `:sheet/node-execution-completed` terminalizes the durable researcher
  campaign projection. Researcher statuses map to `:success`, `:failure`,
  `:timeout`, or `:blocked`.
- A parent `:sheet/tree-tick-completed` marks still-active campaigns
  `:abandoned`; `:sheet/tick-cancelled` marks them `:cancelled`. Neither is a
  behavior verdict.
- Reflection currently starts from all `:ontology/task-classified` facts and
  joins execution/judge evidence. Preserve that descriptive visibility for
  cancelled and abandoned campaigns even though they no longer move a gate.

## Exact behavioral change

Introduce one explicit durable tree-class occurrence fact for a classified
researcher campaign that reaches `:success`, `:failure`, or `:timeout`. Its
identity is `[source-sheet-id source-tick-id]`; it names the assigned class,
source node, and verdict. Make its command sequentially idempotent and
concurrently first-writer-wins with an event-store CAS. Never infer an
occurrence from the absence of cancellation, and never emit it for `:blocked`,
`:cancelled`, or `:abandoned`.

Retarget only recurrence consumers to that verdict fact:

- `:tree-class` consolidation delta/total counters;
- recent occurrence ordering used by judge windows;
- harvest's occurrence count and occurrence-score ordering;
- threshold and harvest trigger processors;
- the gate report's occurrence value.

Keep classification consumers that answer attribution, classifier audit,
runtime envelopes, behavioral-subtree routing, and reflection evidence on
`:ontology/task-classified`. A failed verdict counts toward recurrence and can
join low judge scores; cancellation and abandonment do not count and do not
fabricate judge evidence. Historical classification-only streams remain
replayable as classifications but do not retroactively invent verdicts.

Use the repository's existing schema-validated Grain command/event/processor
boundaries. Do not use a process-local deduplication guard or a bare production
event append.

## TDD cycle list

1. **RED — separate attribution from recurrence.** Through public projection
   functions, prove `:ontology/task-classified` records class attribution but
   does not advance a tree-class occurrence counter or recent-occurrence
   window. A success occurrence advances both exactly once.
2. **GREEN — durable occurrence command.** Add command/event schemas and a
   handler that records success/failure/timeout only. Prove sequential replay
   is a no-op, a forced concurrent race retains one first fact, conflicting
   replays cannot move the occurrence to another class/verdict, and another
   tick is independent.
3. **RED/GREEN — terminal producer chain.** Exercise a real processor-full
   classified researcher node completion. Success, failure, and timeout each
   produce one occurrence. Blocked completion, tick cancellation, and parent
   abandonment produce none. Repeated delivery/recovery stays one.
4. **RED/GREEN — learning consumers.** Prove counters, recent judge windows,
   harvest occurrence count, threshold crossing, and gate reporting all use
   verdict occurrences rather than classifications. A failed occurrence with
   low judge evidence counts and harms the quality inputs.
5. **RED/GREEN — descriptive evidence survives infrastructure endings.** Show
   completed immutable iteration evidence for an abandoned and a cancelled
   classified campaign remains inspectable and can be included when a later
   legitimate verdict triggers reflection, without either campaign advancing
   recurrence.
6. **RED/GREEN — public lifecycle proof.** Through public default-checkpointed
   execution, cover at least one verdict and the cancellation/abandonment
   exclusions. Assert raw events and public projections, not only return
   values.
7. Run the focused namespaces, affected ontology/campaign/evaluation suites,
   changed bricks, Allium check/analyse, weed check mode, obligation audit, and
   independent `/inspect-orc`.

## Allium obligation reconciliation

The scoped `allium plan` output names four issue obligations:

- `invariant.AbandonedCampaignsRecordNoVerdict`
- `invariant.CancelledCampaignsRecordCause`
- `rule-success.CampaignIsAbandoned`
- `transition-terminal.Campaign.status`

The verdict-occurrence tests are the implementation bridge joining those
campaign lifecycle obligations to ontology recurrence. Preserve the final
coverage line exactly: `4 obligations, M covered, K uncovered`, with a reason
for every uncovered obligation and every generated mock, stub, TODO, or
skeleton.

## Do NOT touch

- `specs/*.allium`; the orchestrator is the only spec writer. Report every
  divergence as spec bug, code bug, aspirational design, or intentional gap.
- RR-20 and later worked-pattern/coherence behavior.
- The local Grain checkout at `/private/tmp/orc-rr8-grain-recover3`.
- Other worktrees, especially the primary ontology branch at
  `/Users/darylroberts/Desktop/Code/orc`.
- Existing generated tests: do not weaken, delete, skip, narrow, or over-mock
  them to obtain green.
- Dependency pins except when required to run the already-composed local
  stack; report the need instead of changing it.

## Live QA owned by the orchestrator

The orchestrator will independently run public campaign verdict,
cancellation/abandonment, projection, and raw-event proofs; force the
same-occurrence CAS race; verify reflection evidence without recurrence; run
the affected and broad suites; compare Allium severity counts with
`specs/COVERAGE.md`; run weed check mode; and audit all four obligations before
closing the deterministic checklist item.

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

## Report back

- RED command/result before each production change and GREEN command/result
  after it.
- Files changed and exact public boundaries exercised.
- `4 obligations, M covered, K uncovered`.
- Every generated mock, stub, TODO, or skeleton.
- Every divergence classification and anything not verified.

