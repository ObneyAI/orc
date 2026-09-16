# RR-8 handoff — lease-wired automatic campaign recovery

## Read first

1. `AGENTS.md`
2. `docs/ORC-PRINCIPLES.md`
3. `CONTEXT.md` — Campaign, Quantum, Frontier, Claim, Epoch, and Abandoned campaign
4. `docs/issues/rr-durable/RR-8-lease-wired-recovery-recognises-campaigns-and-the-scan-runs-.md`
5. `docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md` — G2, G7, R6, and R7
6. `docs/prd/rr-durable-self-learning.md` — Ownership and effects; Testing Decisions
7. `specs/orc-service.allium` — `Campaign`, `CampaignResumesAtFrontier`,
   `CampaignIsAbandoned`, `AbandonedCampaignsRecordNoVerdict`,
   `RecoveryRecognisesCampaignFrontiers`, and `ActiveCampaignsRecoverAutomatically`
8. `docs/build-timeline/handoff-plan/RR7-claim-epoch-fence-HANDOFF.md`
9. `components/orc-service/src/ai/obney/orc/orc_service/core/researcher_effects.clj`
10. `components/orc-service/src/ai/obney/orc/orc_service/core/commands.clj` —
    `resume-node-execution`, `checkpoint-researcher-iteration`, and
    `claim-researcher-frontier`
11. `components/orc-service/src/ai/obney/orc/orc_service/core/read_models.clj` —
    in-progress executions, researcher resume state, and effect claims
12. `components/orc-service/src/ai/obney/orc/orc_service/core/runtime.clj` —
    `resume-in-progress!`
13. `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj` —
    the checkpointed researcher frontier acquisition boundary
14. `components/orc-service/src/ai/obney/orc/orc_service/test_helpers.clj` —
    processor stop/rebuild seams
15. `bases/orc-dev/src/ai/obney/orc/orc_dev/core.clj` — control-plane and periodic-trigger lifecycle
16. Grain commit `5de0735d04916c63055a76637fa9bdef36345533`:
    `todo_processor_v2/core.clj` `start-tenant-poller`,
    `control_plane/core.clj` `reconcile-tenants!`, and periodic-task registration/startup
17. `docs/DETERMINISTIC-E2E-TEST-CHECKLIST.md` — DET-E2E-235 and DET-E2E-240

## Relevant specification excerpt

> The recovery scan rediscovers a campaign frontier as it rediscovers any
> other unfinished work. A campaign is not skipped because of the kind of node
> it runs in. Repeated scans and explicit resume remain safe and idempotent.

> After process restart, every campaign whose parent execution remains active
> resumes without any caller resubmitting the workflow or invoking recovery.
> Recovery is something the system does, not something an operator must
> remember.

> Resuming claims the frontier under an epoch at least as new as the one the
> campaign last recorded. A worker holding a superseded epoch cannot resume.

> Abandonment is what happens when nobody will ever resume: the enclosing
> execution is over. It is a fact about the engine, not a verdict about the
> work, so it records no completion.

## Actual RR-7 boundaries — consume these

- A campaign is `[sheet-id tick-id node-id]`; its stable shared event-store tag
  is produced by `researcher-effects/campaign-tag`.
- `:rlm/researcher-frontier-claimed` is the authoritative ownership fact.
  `:sheet/claim-researcher-frontier` accepts a caller-derived candidate epoch
  and uses `researcher-effects/frontier-cas`. It deliberately does not derive
  `latest + 1` inside the handler.
- Version-2 resume state records the epoch that owned the last completed
  quantum. It is not necessarily the latest claimed epoch: the process may die
  after claiming the next frontier and before saving another resume state.
- `rm/get-researcher-effect-claims` is the durable claim read-back for one
  campaign. A `:claimed` row without a completion is evidence of an
  indeterminate effect, not permission to reuse the old epoch. RR-10 owns the
  per-callee resolution policy; RR-8 must preserve the evidence and advance
  ownership without presenting it as a model failure.
- The checkpointed researcher processor currently proposes
  `inc(resume-state.ownership-epoch)`. That is correct for an ordinary yielded
  continuation but wrong after process loss if a later frontier claim already
  landed. Recovery must read the latest durable frontier epoch, attach exactly
  `latest + 1` to the recovered start, and have every racing scanner propose
  that same candidate. One recovery start wins the existing
  `original-start-event-id` CAS; one frontier claim then wins RR-7's CAS.
- Keep the epoch and claim logic in the existing researcher-effects/command
  boundary. Do not introduce a lease-shaped second ORC fence.

## Confirmed Grain dependency

The pinned Grain commit exposes `:lease-check-fn` on `start-polling` and
`process-event`, but `start-tenant-poller` neither accepts nor checks it.
`control-plane/reconcile-tenants!` therefore cannot supply a lease predicate.
Its owned-tenant atom narrows normal dispatch, but a stale poller task can keep
processing after reassignment. RR-8 requires a small upstream Grain change:

1. `start-tenant-poller` accepts `:lease-check-fn`.
2. It checks ownership before invoking either pure or effect/CAS handlers.
3. It passes the same predicate into `process-event`, so the check is repeated
   at the effect boundary rather than only before handler construction.
4. The control plane supplies a predicate that projects current lease
   ownership and compares the tenant owner to its own node id.
5. Grain tests use two processor sets over one tenant and prove the non-owner
   neither invokes its handler/effect nor advances its processor checkpoint.

Do not edit the user's live Grain worktree. Develop and verify this dependency
in an isolated temporary Grain checkout. Do not pin ORC to an unpublished or
machine-local dependency. If the upstream commit cannot be published without
new authority, finish the independent ORC work and report that exact merge
blocker rather than weakening the lease test.

## Exact change

1. Add an auto-registered Grain periodic trigger for recovery. Use an
   interval schedule, whose first tick is `Instant/now`, so starting the
   standard periodic-trigger runtime emits a tenant-scoped recovery trigger
   immediately and continues scanning at a bounded interval.
2. Add a recovery todo processor whose effect invokes the tenant-scoped scan.
   It is discovered through the ordinary processor registry and is therefore
   governed by the wired Grain lease check. No production caller invokes
   `resume-in-progress!` manually.
3. Widen `resume-in-progress!` from `:leaf`/`:delegate` to include
   `:repl-researcher`. Keep ordinary leaf and delegate recovery unchanged.
4. For a recovered researcher start, read the latest campaign-tagged frontier
   epoch and effect-claim projection, attach the next candidate epoch to the
   durable recovery start, and make the researcher processor use that candidate.
   Claims remain evidence; RR-8 neither resolves nor deletes them.
5. Make campaign lifecycle read-back explicit enough to distinguish an active
   recoverable frontier from a campaign whose enclosing tick is terminal. A
   terminal parent may mark a still-running/yielded campaign `:abandoned`, with
   no completion/verdict timestamp, but must never enqueue it. A campaign whose
   researcher node already completed is not abandoned.
6. Preserve the existing `original-start-event-id` recovery CAS. Automatic
   scans, repeated periodic triggers, concurrent scanners, and an explicit
   `resume-in-progress!` call all converge on one recovered start and one newer
   frontier claim.
7. Preserve cancellation semantics and every non-checkpointed path. RR-9 owns
   cooperative drain/deadline work; RR-10 owns indeterminate-effect resolution.
8. Update the pinned Grain dependency only to a published, verified commit and
   keep every package SHA consistent.

## TDD cycle list

Run one RED -> GREEN -> refactor cycle at a time.

1. **Automatic restart tracer (DET-E2E-235):** execute a public checkpointed
   researcher until it has a yielded frontier, stop processors, rebuild the
   processor and periodic-trigger runtimes against the same event store, and
   do not call `resume-in-progress!`. RED until the registered trigger and
   processor autonomously rediscover `:repl-researcher`; GREEN only when the
   original tick/node/campaign completes and already-recorded provider work is
   not repeated.
2. Crash after a frontier claim but before the next checkpoint. Prove the
   recovered start proposes `latest durable frontier + 1`, the newer claim
   lands, the old epoch cannot write, and the campaign advances. This guards
   the deterministic restart wedge hidden by the old resume-state-derived
   candidate.
3. Run the periodic scan repeatedly and race it with explicit recovery. Prove
   one recovered start, one new frontier claim, one completion, and no duplicate
   provider/tool/child effect. Read raw events and projections.
4. End the enclosing execution while a campaign remains running/yielded. Prove
   the campaign read-back becomes `:abandoned`, keeps `completed-at` absent,
   retains iteration/claim evidence, and no automatic or explicit scan resumes it.
   Also prove a normally completed campaign is not relabelled abandoned.
5. Against the patched Grain dependency, run two processor sets over one
   tenant while only one owns its lease. The non-owner skips pure and
   effect/CAS handlers and does not advance its checkpoint; the owner processes
   normally. Reassign ownership and prove the result reverses without restart.
6. Re-run all seven obligation mappings, focused recovery/checkpoint/claim
   namespaces, the relevant Grain suites, and the full `orc-service` brick in
   both consuming project contexts. Refactor only while GREEN.

## Propagation status

`allium plan specs/orc-service.allium` emits all seven obligation IDs named by
the RR-8 issue:

- campaign resume success/failure and abandonment success/failure -> cycles 1,
  2, and 4;
- both abandonment transition edges -> cycle 4 from running and yielded;
- `AbandonedCampaignsRecordNoVerdict` -> cycle 4 via campaign read-back and raw
  lifecycle evidence.

The automatic startup-scan and node-kind requirements are ratified prose
invariants and produce no extra CLI obligation IDs. Cycles 1 and 3 are their
executable contract; this limitation is explicit, not silently counted as
machine-generated coverage.

Initial reconciliation before the first RR-8 RED:
`7 obligations, 7 covered, 0 uncovered`.

This says every emitted RR-8 obligation has a planned executable witness. It
does not claim the behavior exists. Cycle 1 must be witnessed RED before any
production implementation.

## Do NOT touch

- `specs/*.allium`; report divergences as spec bug, code bug, aspirational
  design, or intentional gap. The orchestrator is the only spec writer.
- The ontology worktree at `/Users/darylroberts/Desktop/Code/orc`.
- The user's live Grain worktree at `/Users/darylroberts/Desktop/Code/grain`.
- RR-9 drain/deadlines, RR-10 indeterminate resolution, RR-11 budgeting, RR-12
  map-each, RR-14 sandbox deltas, RR-15 default-on, or later consumer slices.
- Existing non-checkpointed behavior.
- Generated or existing tests merely to obtain GREEN.
- Commits, pushes, or dependency pins without the orchestrator's explicit
  authorization for that external state change.

## Orchestrator live QA

Independently rebuild the real periodic-trigger and todo-processor runtimes
against the same persistent SQLite store and observe autonomous recovery of the
same tick/node/campaign without a public execute or resume call. Inspect raw
frontier/start/claim/completion order and all relevant projections. Repeat the
scan, race explicit recovery, and force a terminal parent. Then run the
two-owner Grain probe and confirm ownership reversal. A real JVM kill/provider
journey remains the standing RR-26 Seam-8 close-out; RR-8 must nevertheless use
a real stop/reopen processor-store seam rather than an in-process throw.

## Dependency rule

RR-8 consumes RR-7's actual campaign tag, frontier command/CAS, resume-state
epoch, and effect-claim projection. RR-9's handoff must be written only after
RR-8 lands and is independently inspected, from the real recovery scheduler,
lease predicate, and in-flight registration boundary that then exists.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce -> minimize -> fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red -> green -> refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

## Report back

- Preserve the line verbatim: `7 obligations, 7 covered, 0 uncovered`.
- List each RED and GREEN command/result and the exact automatic-restart event
  order; state explicitly that no caller invoked execution or recovery after
  restart.
- Show the old and new frontier epochs, the recovery start's candidate, stale
  write rejection, and raw/projection agreement.
- Show repeated/concurrent scan counts and distinguish expected CAS conflicts
  from store or processor errors.
- Show the abandoned campaign projection, absent verdict timestamp, retained
  evidence, and absence of any later recovery start.
- Report Grain's owner/non-owner/reassignment results, the upstream commit, and
  every ORC dependency pin changed.
- Declare every mock, stub, TODO, skeleton, unverified external dependency, and
  classified spec/code divergence.
- Do not edit specs, commit, push, or touch another live worktree.
