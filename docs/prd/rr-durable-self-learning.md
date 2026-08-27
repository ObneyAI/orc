# PRD: Durable, self-describing repl-researcher campaigns

**Source of truth:** ADR 0004 (campaign effects are at-least-once and attributable) + ADR 0005 (recursive campaigns are checkpointed by default) + `specs/orc-service.allium`, `specs/ontology.allium`, `specs/evaluation.allium` + grill log `docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md` (decisions G1–G19, research findings R1–R8).

**Branch:** `feature/rr-durable-self-learning`, based on `checkpointed-resumable-repl-researcher` @ `386cb400` (contains `main` f4d7e848). We own that branch; it is the vehicle, not a finished feature.

**Naming note:** this PRD uses `Seam-1`…`Seam-8` for test seams, to avoid collision with the grill log's `S1`–`S5` (self-learning defects).

## Problem Statement

A repl-researcher **campaign** — one recursive research effort from first iteration to `final!` — runs entirely inside one in-memory invocation. If the process crashes, restarts, or loses ownership partway, everything the model reasoned out is gone: completed iterations, sandbox state, the trees it designed and repaired. Retrying starts the campaign over and re-pays for provider calls, tool effects, and child trees that already succeeded.

Worse for the product's central thesis: **the campaign cannot learn from itself.** The self-learning loop is supposed to make the researcher unlike a stateless ReACT worker — it should carry forward what worked and what didn't, so it isn't starting cold every time. But every mechanism attached to it observes **one datum per campaign**: one judge verdict, one terminal outcome, one occurrence counter. Everything that actually determines quality — which of five designed trees worked, which failed and why, how the model recovered — happens *inside* the campaign and leaves no durable trace.

The consequences compound:

- The corpus records **whichever tree was emitted last**, with no knowledge of whether it succeeded. A campaign that emits a broken tree, sees it fail, and repairs it can crystallize the *failed* pattern as the class's proven one.
- The pattern it ships often can't be used at all: code the model writes inside a generated tree is replaced by a placeholder before storage, so the corpus tells the model to ADOPT a pattern whose logic is missing.
- Judges grade a black box — they receive the task's inputs, the final outputs, and the instruction, and nothing about the work.
- The gate that checks whether a class has *converged on a repeatable shape* has never once fired on real data; it passes vacuously today and would block everything the moment it starts working.
- Recurrence is counted when a task is *classified*, before any work happens, so a campaign that classifies and crashes still counts toward promotion.

An opt-in durability mode was built (PR #36) but is not enabled by the loop's own configuration, has two blocking defects, and its ownership guarantee is stated in wording that is false in the handoff window.

## Solution

Make a campaign **durable, resumable, and self-describing**, and feed what it durably records back into the loop that is supposed to learn from it.

Three shifts, from the user's perspective:

1. **A campaign survives.** It advances in bounded quanta, records what it needs to continue, and resumes automatically after a crash or a restart — without anyone resubmitting the workflow, and without repeating work that already succeeded.
2. **A campaign describes itself.** Every iteration leaves an immutable record of what was attempted, what it produced or failed with, the tree it emitted and that tree's shape and outcome, and the code it wrote — real source, not a placeholder. That record is the single account of the campaign: what an observer watches live and what the system reads back afterwards are the same thing.
3. **The loop learns from the work, not just the outcome.** Judges and reflection read the iteration record. A pattern is recorded as proven only when a campaign succeeded with it. Recurrence counts campaigns that reached a verdict. Convergence is measured over the shapes that won.

The guarantee is stated honestly rather than aspirationally: **exactly-once campaign state and exactly-once child, tool and mint effects; provider calls at-least-once, bounded by ownership epochs crossed, with every indeterminate call durably attributable.** Research established that exactly-once for a provider call is not obtainable — no LLM vendor participates in deduplication — so the strong mechanism is relocated to where the callee is us.

## User Stories

1. As an ORC operator, I want a campaign to resume after a process restart, so that a crash costs me one iteration instead of an entire research effort.
2. As an ORC operator, I want recovery to happen without my intervention, so that durability is a property of the system rather than a runbook step I must remember.
3. As an ORC operator, I want a campaign that was interrupted to be visibly interrupted, so that I can tell "still working" from "silently wedged".
4. As an ORC operator, I want duplicate provider spend to appear as an attributable line item, so that I can answer "did we double-pay, and where" instead of guessing.
5. As an ORC operator, I want a campaign's worst-case uninterrupted duration to be a known number, so that ownership handover can be reasoned about rather than hoped about.
6. As an ORC operator, I want the LLM-call budget to survive a restart, so that a resumed campaign cannot quietly spend a second full budget.
7. As an ORC operator, I want cost figures to count each iteration once, so that reported usage is not inflated by how many times a campaign yielded.
8. As a workflow author, I want durability to be on by default for recursive researchers, so that I get the evidence and the resilience without knowing a flag exists.
9. As a workflow author, I want an explicit opt-out, so that a campaign that never yields need not pay for machinery it does not use.
10. As a workflow author, I want my existing non-durable researchers to behave exactly as they do today when I opt out, so that adopting this is not a rewrite.
11. As a workflow author, I want a campaign that hits an unsupported sandbox value to tell me which value, so that I can fix it rather than guess.
12. As a workflow author, I want an unsupported value to cost me one checkpoint rather than the whole campaign, so that a durability failure does not destroy the work already done.
13. As a workflow author, I want a tool I bind to a durable campaign to be rejected up front if it cannot be safely retried, so that I learn at configuration time rather than mid-run.
14. As a workflow author, I want map-each work that already finished to be rejoined after a restart, so that 27 of 30 completed chunks are not thrown away and re-paid for.
15. As a workflow author, I want the failure indices and reasons from a partial map to survive a restart, so that the model's repair logic still has something to act on.
16. As a workflow author, I want a campaign that blocks on something external to record what it was blocked on, so that a re-run does not ask a person the same question twice.
17. As a researcher model, I want the corpus pattern I am shown to contain real code, so that ADOPT is a move I can actually make.
18. As a researcher model, I want a pattern to declare what it reads and writes, so that binding it to my task is mechanical rather than a re-derivation.
19. As a researcher model, I want to be shown patterns that succeeded, so that I am not handed a tree that failed as though it were proven.
20. As a researcher model, I want to see what a class is *bad* at, so that I can choose SPECIALIZE or MINT rather than adopting something ill-suited.
21. As a researcher model, I want my prompt history to reflect what I did, so that an infrastructure failure is not recorded as my failure.
22. As a researcher model, I want variables I updated to be visible in my history, not only ones I created, so that I can reason about what changed.
23. As a researcher model, I want my earlier iterations preserved when a later one times out, so that a slow final step does not erase the work that led to it.
24. As a self-learning loop, I want per-iteration evidence, so that I can describe *why* a class is strong or weak rather than only *whether* it passed.
25. As a self-learning loop, I want one campaign to count as one occurrence, so that a campaign that yielded ten times does not look like ten pieces of evidence.
26. As a self-learning loop, I want recurrence counted when a campaign reaches a verdict, so that abandoned work does not push a class toward promotion.
27. As a self-learning loop, I want a failed campaign to count, so that I learn from failure while ignoring power cuts.
28. As a self-learning loop, I want the pattern I record to be the one that worked, so that what I promote is proven rather than merely most recent.
29. As a self-learning loop, I want a class that genuinely succeeds with two shapes to keep both, so that a second success does not erase the first.
30. As a self-learning loop, I want convergence measured over the shapes that won, so that a model that recovers well is not scored as less coherent than one that fails outright.
31. As a self-learning loop, I want a never-exercised gate to report before it blocks, so that "harvested nothing" is distinguishable from "nothing qualified".
32. As a self-learning loop, I want the classifier to run once per campaign, so that resuming does not inflate every counter I depend on.
33. As a self-learning loop, I want an occurrence to be attributed to one class, so that judge scores are not counted for two classes at once.
34. As a self-learning loop, I want my evidence window to carry code and errors without carrying data payloads, so that richer evidence does not reproduce the failure that once made a reflection call impossible.
35. As a judge, I want the iterations of the work I am scoring, so that my feedback names what went wrong rather than restating the output.
36. As a judge, I want to score a whole campaign once, so that a campaign that took many attempts does not outvote one that took few.
37. As an evaluation consumer, I want the iteration record to reach the trace API, so that the evidence exists somewhere I can actually read it.
38. As an observer, I want to watch a running campaign iteration by iteration, so that a long campaign is not an opaque wait.
39. As an observer, I want what I watched to match what is read back later, so that live and durable views cannot disagree.
40. As an observer, I want a rejoined child to appear in the lineage, so that a restart does not leave a hole in the picture.
41. As a platform engineer, I want a superseded worker's result to be rejected, so that a zombie cannot corrupt a campaign's record.
42. As a platform engineer, I want an effect claimed before it happens, so that a duplicate is prevented rather than detected afterwards.
43. As a platform engineer, I want action identity derived from content, so that replay cannot return a recorded result for a different call.
44. As a platform engineer, I want the lease check the platform demands to actually be supplied, so that the declared contract is not inert.
45. As a platform engineer, I want an in-flight campaign to be cancellable on lease loss, so that drain is possible at all.
46. As a platform engineer, I want superseded resume state to be discardable, so that storage does not grow without bound.
47. As a platform engineer, I want the hot evidence queries scoped by tag, so that the loop's cost does not grow with total store size.
48. As a maintainer, I want the specification to state what the system actually guarantees, so that a future reader is not misled into building on a promise that was never met.

## Implementation Decisions

Every decision below is ratified in the grill log and encoded in the specs; this section names them, it does not restate the formal statements.

### Execution and durability

- **Checkpointing becomes the default** for recursive campaigns, with explicit opt-out (ADR 0005, G1). Gated on the write-amplification fix — a default must not ship a storage regression.
- **Resume state and the iteration record become separate durable facts** (G8). Resume state is small, superseded per quantum, and carries current sandbox values, usage, budgets and frontier position. An iteration record is written once per iteration and never rewritten. Storage becomes proportional to iterations rather than to their square, and the trace no longer needs to reconcile two sources.
- **The sandbox is delta-encoded with periodic full snapshots** (G18). `:vars-created` / `:vars-updated` are already computed, so the delta is known; snapshot interval is a measured value, not a guess.
- **Superseded resume state is compactable**; iteration records are retained on evidence terms (G18).

### Foundation repairs

Defects in the inherited branch that are not themselves design decisions, but must land before anything is built on top. Each is a default-path defect once checkpointing is the default.

- **Root `:running` reticks are silently dropped.** The tick-completion processor returns the trace-refresh's nil, discarding the retick events, so any workflow whose root completes `:running` stops advancing. This affects **every workflow**, not only campaigns, and is reproduced: the existing retick suite passes on `main`, fails 18 assertions on the inherited branch, and passes again with the fix.
- **The Phase-1 budget branch is unreachable.** A campaign-deadline check was inserted above it at an identical threshold, so budget exhaustion reports as a timeout and loses its diagnostics (elapsed, budget, cumulative tree and thinking time).
- **A Phase-2 timeout no longer gets a repair turn.** The timeout intercept runs before the recursive-mode branch, orphaning the handling that let the model see a child timeout in its results and try a smaller tree — which is precisely the adaptive recovery the researcher exists for.
- **The durable tool caller ignores the arity guard** that the non-durable path applies, so a host supplying a two-argument tool caller fails on first use under checkpointing.
- **A still-running child tick is reported to its parent as a failure.** The child-result reconstruction has no running case, unlike the equivalent reconstruction elsewhere in the runtime, and a rejoined child contributes zero to cumulative child time — so timing drifts on every replay.
- **A rejoined child skips lineage linking**, leaving a hole in the observable picture exactly where a restart happened (G12 rider).
- **Behavior minting carries no iteration provenance**, so a minted behavior cannot be distinguished as a confident first-attempt novelty from a fourth-attempt fallback — materially different evidentiary weight for something every future task may retrieve. Minting also forces a full corpus reindex unconditionally on every mint, which a replayed iteration re-pays.

### Ownership and effects

- **A per-frontier claim epoch is minted into ORC's own event stream** (G7). Grain exposes no fencing token and its compare-and-swap cannot read lease state, so the token must be ours; ORC's store becomes the resource that checks it.
- **Effects are claimed before they happen, and the claim is the same record as the durable intent** (G7). One append serves as both the fence and the after-the-fact evidence, so the claim is per-effect rather than per-quantum and a superseded worker's spend is bounded to one in-flight effect.
- **Action identity becomes content-derived** — tick, node, iteration, attempt, code hash, kind, tool name, canonical arguments — generated inside the durable step (G7, R8). Execution-order ordinals change on replay and can return a recorded result for a different call.
- **Every effect boundary gets an identity**, including the inline provider primitive and behavior minting, which have none today (G7 layer 3, D7).
- **The platform's lease check is wired** (G7 layer 1) — the contract Grain declares it demands is currently never supplied.
- **The campaign's in-flight work is registered and cancellable**, and reassignment is delayed beyond worst-case quantum duration (G7 layer 5). Labelled probability reduction, not guarantee.
- **Indeterminacy resolves by callee participation** (G16): a child is rejoined by stable identity; a tool or mint is re-attempted under the same identity and deduplicated by its owner; a provider call is re-attempted as a further attempt of the same logical action, recorded for the operator and **not** surfaced to the model.
- **Recovery is automatic and recognises campaign frontiers** (G2), split into two obligations so the startup half cannot hide behind the node-type half.

### Deadlines and accounting

- **Every campaign operation falls inside a deadline**, including the classification work done before the first iteration, which today runs outside the campaign clock (G15).
- **Transport-level timeouts** exist so cancellation is not purely advisory (G15).
- **Worst-case quantum duration becomes a recorded quantity** so the reassignment delay has something to be sized against (G15).
- **The LLM-call budget is derived from durable claims** with an in-memory cache, because the budget spans a trace family that no single checkpoint covers (G17).
- **Usage accrues once per completed iteration**, not once per yield (G17, D4).

### Evidence and the self-learning loop

- **The iteration record carries** iteration index, status, duration, tree fingerprint, the emitted tree including its code, the campaign's own code, error class and bounded excerpt, variable deltas as key lists, and bounded reasoning (G5). Data payload **values** are reduced to keys and profiles — the 6.4 MB reflection failure was payloads, not code.
- **Generated code is persisted as source** (G6), which simultaneously makes patterns adoptable, dissolves the checkpoint-crash defect at its root, and lets a resumed campaign reconstruct inline functions. **Capture mechanism is prototyped before the design is committed.**
- **Classification is one per campaign** (G3), skipped on resume and carried forward, with the assignment command made idempotent on the occurrence key.
- **Recurrence counts at outcome, not intent** (G10). Abandonment is infrastructure noise; failure is a verdict.
- **The worked pattern is keyed on outcome and shape** (G4) — a failed tree never displaces a successful one, and a class keeps every shape it genuinely succeeds with.
- **Coherence measures winning shapes**, one per occurrence, and rolls out report-only until its real distribution is known (G11).
- **Behaviors remain advice**, and their patterns must be genuinely adoptable — real code plus declared key bindings (G13).
- **Judges receive the iteration material**; verdict identity is unchanged, one per completion (G5, evaluation spec). The tree-scoped judge identity proposed mid-grill was **withdrawn**.
- **The live stream becomes a projection of the durable record** (G12); the terminal-only iterations event is retired.
- **The evaluation trace API surfaces the iteration record** (S3) — it currently projects a fixed field list that drops it.
- **Blocked outcomes are recorded and rejoinable** (G14); blocking stays terminal.
- **Map-each survivors are rejoined from durable child completions** (G9).
- **Hot evidence queries are tag-scoped** (G19); three tags already exist, the fourth is added at an emit site already being modified.

### Corrections carried forward

- The idempotency key supplied to tools **must stop being described as provider deduplication** anywhere in code, docs, or spec (R1). No vendor honours one; the SDK code that appears to support it is dead.
- Stale documentation is corrected as encountered: harvest is described as unshipped when it is live; the tree-generated event is documented as firing per emit when it fires once per campaign.

## Testing Decisions

**What makes a good test here.** Test behaviour through the public execution surface. The unit under test is a *campaign* — what it durably records, what it resumes to, what it does not repeat — never an internal function's return shape. A test that asserts on an event's presence and fields is testing behaviour; a test that asserts on how a loop is structured is not. Crash tests must actually restart processors against the same store, because an in-process thrown exception does not prove what a restart proves.

**Seams**, preferring existing ones, highest first:

- **Seam-1 — public execution.** `sheet/execute` via `with-async-test-context` (real processors, real pubsub). The primary seam. Prior art: `running_retick_test.clj`, the public retick test in `checkpointed_researcher_test.clj`.
- **Seam-2 — restart.** `stop-test-processors!` → close/reopen store → `start-test-processors`. Proves automatic recovery without caller resubmission. Prior art: the SQLite restart test in `checkpointed_researcher_test.clj`, currently hand-driven; this promotes it to the processor-rebuilt form.
- **Seam-3 — durable evidence.** `read-tick-events` / event-store reads asserting iteration records, claims, epochs, shape and outcome. Prior art: existing checkpoint-event assertions.
- **Seam-4 — self-learning consumers.** Ontology tests over a synthesized event stream. Prior art: `el4_harvest_test.clj`, `consolidator_test.clj`.
- **Seam-5 — judge runtime.** Prior art: `judge_runtime_test.clj`.
- **Seam-6 — executor unit.** `recursive_rlm_test.clj` in-process with a redefined provider. **Last resort**, used only where Seam-1 genuinely cannot reach: deadline arithmetic, codec round-trips, quantum bounds.
- **Seam-7 — concurrency (NEW).** Two workers racing one frontier: two processor sets against one store, asserting one claim wins and the loser fires no effect. No prior art exists — Grain's own conformance test for its fencing invariant calls the handler twice sequentially in one thread with a no-op body, which proves checkpoint dedup, not mutual exclusion over effects. G7 is unfalsifiable without this seam, so it is built.
- **Seam-8 — gated live provider (NEW).** Real provider, multi-quantum, real process kill. Extends the existing gated single-process journey to the restart the arc actually claims. Orchestrator SOLO proof, never an AFK slice.

**Measurement is a test obligation, not a follow-up.** Sandbox growth, iteration-record volume, evidence density for the new observation kind, worst-case quantum duration, and the coherence ratio's real distribution are all *measured and recorded*, then written into the specs as config. Guessing these is what produced the 6.4 MB reflection failure.

**Generated tests are contract.** Tests emitted by `/propagate` from the specs are never weakened to pass; a mismatch is fixed in the spec and re-propagated.

## Out of Scope

- **A durable human-wait frontier** — pausing a campaign for approval and resuming the same child. Recorded as an `open question` in `specs/orc-service.allium` with its blocking prerequisites and its direct collision with the absolute campaign deadline. Blocking stays terminal (G14).
- **Executable behavior invocation.** Behaviors remain advice; a primitive that runs a harvested behavior would first have to solve slot rebinding, which a proven tree does not declare (G13).
- **Per-iteration LLM judging.** Withdrawn during the grill: with per-tree status available as fact, per-tree judgment is cost for little value (G4).
- **A read-model rework for the ontology consumers.** Tag-scoping plus the reduced invocation rate is expected to suffice; revisit only if measurement says otherwise (G19).
- **GEPA integration.** No call site consumes researcher traces today.
- **Pattern recording** (`docs/PATTERN-RECORDING.md`) — not attached to the researcher; its writer has no producer on this path.
- **A general worst-case-duration predictor.** We lack the data, and an over-aggressive bound manufactures the failures it aims to prevent (G15).
- **Retention numbers.** The *structure* is decided; the numbers are measured (G18).

## Further Notes

**The grill corrected two confident errors, both of which would otherwise have shipped as silent assumptions.** First, the platform tenant fence was asserted to exist and does not: `lease-check-fn` is never wired into ORC's processors, so the guard short-circuits and the declared contract is inert. Second, the idempotency plumbing visible in the OpenAI and Anthropic SDKs is dead code — the header name is never set — so a reasonable code read produces a false conclusion about provider support. Both are recorded in the research findings.

**Two specification invariants had been weakened to match the code.** One (`ActiveCampaignsRecoverAutomatically`) is restored and split, because the weakening concealed an obligation the code simply had not met. The other (`BlockedChildPausesTheCampaign`) is ratified as weakened, because it reflects a genuine architectural boundary ORC holds consistently. The distinction matters: not every weakening is a retreat, and not every restoration is progress.

**One decision remains prototype-gated.** Capturing generated code as source requires the emitted form to reach the emit primitive as something other than a compiled closure. If neither a quoted form nor interpreter source metadata is available, the fallback changes the surface the model is taught, which requires re-grilling rather than an in-flight decision.

**The defect that motivates the whole arc, stated plainly:** a campaign that designs a broken tree, watches it fail, and repairs it can crystallize the *broken* tree as the class's proven pattern — and ship it with its code replaced by a placeholder. That is the ReACT cold-start failure reappearing at precisely the point the corpus exists to prevent it.
