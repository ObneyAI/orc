# RR-5 handoff — iteration record content and evidence digest

## Read first

1. `AGENTS.md`
2. `docs/ORC-PRINCIPLES.md`
3. `CONTEXT.md` — Campaign, Iteration, Attempt, Iteration record, Living Description
4. `docs/issues/rr-durable/RR-5-iteration-record-content-and-the-evidence-digest.md`
5. `docs/issues/rr-durable/RR-4-resume-state-and-the-iteration-record-become-separate-facts.md`
6. `docs/prd/rr-durable-self-learning.md` — G5 and stories 9–13, 37, and 46
7. `docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md` — G5 and the 6.4 MB payload finding
8. `specs/orc-service.allium` — `CampaignIteration`, the three `CampaignIteration*` rules, `RecordedTreesCarryTheirShape`, and `SuccessfulIterationsRecordTheirCode`
9. `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` — `iteration-record`, `checkpoint-result`, `persist-terminal-result`, `retry-result`, and the four history-entry construction sites
10. `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj` — `checkpoint->v2-researcher-facts`
11. `components/orc-service/src/ai/obney/orc/orc_service/core/read_models.clj` — `get-researcher-iteration-records`
12. `components/orc-service/src/ai/obney/orc/orc_service/core/profile.clj` — the existing data-shape projection
13. `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_fingerprint.clj` — the canonical tree fingerprint
14. `components/orc-service/test/ai/obney/orc/orc_service/checkpointed_researcher_test.clj`

## Relevant specification excerpt

> A settled CampaignIteration records its campaign identity, iteration index,
> attempt ordinal, terminal status, start and completion times, duration,
> generated-code and emitted-tree facts, emitted shape, and bounded error.

> A successful iteration records its generated code. A failed iteration
> truthfully records whether failure occurred before or after code generation.
> An iteration that records a tree also records that tree's shape.

## Produced RR-4 API — use these signatures, do not invent replacements

- Command: `:sheet/checkpoint-researcher-iteration` with `:resume-state` and
  `:iteration-record`. The command atomically emits
  `:rlm/researcher-iteration-recorded` and
  `:rlm/researcher-resume-state-saved`.
- Immutable identity: `[tick-id node-id iteration-index attempt-ordinal]`.
- Read: `(get-researcher-iteration-records ctx sheet-id tick-id node-id)` returns
  records ordered by `[:iteration-index :attempt-ordinal]`.
- Resume state is separately read by
  `(get-researcher-resume-state ctx sheet-id tick-id node-id)` and must not gain
  history or evidence fields.
- Public execution passes `:researcher-iteration-records` into the executor;
  version-2 prompt history is reconstructed from those records.

## Exact change

Replace the compatibility history entry currently persisted verbatim as an
iteration record with a deliberately constructed evidence digest. Keep these
top-level fields and names:

- identity/outcome: `:iteration-index`, `:attempt-ordinal`, `:status`
- lifecycle: `:started-at`, `:completed-at`, `:duration-ms`
- authored work: `:code`, bounded `:reasoning`
- emitted work: `:emitted-tree`, `:tree-fingerprint`,
  `:generated-code-recorded?`, `:emitted-tree-recorded?`
- failure evidence: `:error-class`, bounded `:error-excerpt`
- variable shape: `:variable-delta` containing `:created-keys`,
  `:updated-keys`, and `:removed-keys`
- data shape: `:result-profile` and `:stdout-profile` when those values exist

Do not persist raw `:result`, raw `:stdout`, blackboard values, or value-bearing
input/output maps in the iteration record. Use the existing pure profile
functions for value shape. Code and emitted-tree structure are authored evidence,
not data payload, and remain present. RR-6 owns exact source-text preservation
for inline generated code; RR-5 must not widen into compilation or source
reconstruction.

Start/completion/duration are attempt-scoped, not campaign-scoped. Obtain time
through an injected/defaulted clock seam so tests never sleep or race wall time.
Every success, failure, and timeout record has one start and one completion;
duration is non-negative and is derived from that pair.

The error and reasoning caps must be named public-to-the-component constants and
must be selected from a checked-in measurement over representative existing
iteration fixtures. Record the measured distribution and rationale in a
prototype finding before choosing values. Do not enlarge an unrelated evidence
budget or select a round number by intuition. Truncation applies only to the
durable digest; model-authored code and tree source are never truncated.

Update version-2 prompt-history rendering so it names created, updated, and
removed keys and never embeds their values. Keep the version-1 compatibility
renderer intact for already-written PR-36 checkpoints.

## TDD cycle list

1. Add one public successful-iteration test that fixes the clock and proves a
   projected record has attempt-scoped start, completion, duration, code,
   reasoning, and all three variable-key delta lists while excluding raw result,
   stdout, and sandbox values. Confirm RED, then make only this tracer bullet
   green.
2. Add an emitted-tree test. Prove the record carries the event-safe tree and
   canonical fingerprint together, and that `:emitted-tree-recorded?` cannot be
   true without both. RED, then green.
3. Add failure and timeout tests. Prove error class/excerpt and lifecycle fields
   belong to the failing attempt, while a provider timeout does not inherit code,
   tree, reasoning, or variable deltas from the prior attempt. RED, then green.
4. Add a payload projection test with a large string, nested map, and collection.
   Prove only key lists and profiles enter the raw durable event; search the
   serialized event bytes for unique payload sentinels. RED, then green.
5. Measure representative existing reasoning and error fixtures, check in the
   observed distribution/rationale, name the two limits, then test below, at,
   and above each boundary. RED, then green.
6. Add a close/reopen two-iteration prompt-history test. Prove created, updated,
   and removed key names appear, their values do not, and no completed provider
   turn is replayed. RED, then green.
7. Add the narrow judge-consumer proof using the existing evaluation seam: one
   judge receives the digest and can explain why the outcome occurred. Do not
   change judge count or classification timing; RR-17 owns the full trace/judge
   wiring. RED, then green.
8. Measure serialized record size across representative success, failure,
   timeout, and tree-emitting records. Report the observed distribution and
   assert structural bounds (payload-size independence and configured cap
   enforcement), not a guessed total-byte ceiling.
9. Reconcile all six obligations, run the focused namespaces, then
   `clojure -M:poly test brick:orc-service`. Refactor only after green.

## Propagation status

After the first public RED tracer bullet: `6 obligations, 3 covered, 3 uncovered`.

The first public tracer bullet covers
`entity-fields.CampaignIteration`,
`entity-optional.CampaignIteration.duration`, and
`invariant.SuccessfulIterationsRecordTheirCode`. Subsequent cycles cover the
optional shape/error fields and `RecordedTreesCarryTheirShape`. Preserve the
final reconciliation line exactly; do not count an assertion against a
hand-built command map as proof of production-created record content.

The witnessed RED was not an execution failure: the public workflow succeeded
and exactly one record projected. The record lacked attempt timestamps,
duration, profiles, and the three-list variable delta; it still carried raw
`:result` and `:stdout`, and both payload sentinels appeared in serialized
record text. That distinguishes missing RR-5 behavior from a broken harness.

## Do NOT touch

- `specs/*.allium`; the orchestrator is the only spec writer.
- The RR-4 event names, immutable identity, append ordering, stale-frontier CAS,
  or version-1 read compatibility.
- RR-6 source capture/compilation, RR-7 effect claims, RR-14 sandbox delta
  persistence, RR-16 live-stream projection, or RR-17 full judge/trace wiring.
- The non-checkpointed execution path.
- The ontology worktree at `/Users/darylroberts/Desktop/Code/orc`.
- Existing generated or strengthened tests merely to obtain green.

## Orchestrator live QA

The orchestrator independently inspects raw iteration events, verifies the
absence of payload sentinels, restarts the SQLite store, checks reconstructed
prompt text, recomputes fingerprint/profile values, reruns the measurement,
executes both Polylith project contexts, then runs `allium check`, `analyse`,
`/weed` check mode, and `/inspect-orc`. External-provider quality is not claimed;
the provider remains an injected deterministic capability for this slice.

## Dependency rule

RR-5 consumes RR-4's verified API above. RR-17's implementation handoff must be
written only after RR-5 lands and is inspected, using the actual digest fields
and measured limits produced here.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

## Report back

- Preserve the final `/propagate` line verbatim: `N obligations, M covered, K uncovered`.
- List every RED and GREEN command/result, the exact record shape, measured
  limits and distributions, payload sentinel proof, and record-size results.
- Classify every spec/code divergence and declare every mock, stub, TODO, or
  skeleton.
- Do not commit.

## Implementation report

### Produced record

The v2 command now accepts a closed, allowlisted digest. Its nested
`variable-delta`, `result-profile`, and `stdout-profile` maps are closed too,
and the measured reasoning/error maxima are enforced at both producer and
command-schema boundaries. Production-created records carry attempt identity,
terminal status, lifecycle, generated-code/tree facts, authored code and
event-safe tree structure when present, canonical fingerprint, bounded
reasoning/error evidence, variable-key deltas, and result/stdout profiles.
They never copy a compatibility history map wholesale.

A provider failure before code generation is still a settled iteration. It
records lifecycle and bounded failure evidence with
`generated-code-recorded? false`; it does not invent empty code. The Allium
failure rule was tended to carry this truthful boolean, and the success-only
code invariant is now `SuccessfulIterationsRecordTheirCode`.

### RED/GREEN evidence

- The first public workflow RED projected one record but exposed raw
  `:result`/`:stdout` and lacked lifecycle, profiles, and three-list deltas.
  The production allowlist and fixed-clock lifecycle made it GREEN.
- Emitted-tree, failure, timeout, raw-event sentinel, measured-cap,
  close/reopen SQLite history, judge-consumer, and serialized-size cycles were
  each exercised through their public boundaries. The payload-size test was
  already GREEN after the profile cycle; this was classified as existing
  coverage rather than missing behavior.
- Adversarial schema inspection first found a top-level raw-field bypass, then
  nested-map bypasses, then over-limit text bypasses. The corrected nested RED
  used otherwise-valid nested maps; an earlier incomplete-map probe was
  vacuous and was discarded. The final schema rejects all three classes.
- The provider-exception RED returned failure after one start-clock read, zero
  completion reads, and zero projected records. The checkpointed terminal path
  now persists the record. A nil-message throwable then exposed an incorrect
  checkpointed `no-code` classification; the bounded fallback now records it
  as `provider-failure`. An initial fallback affected the non-checkpointed
  error result, so a public compatibility RED caught the scope leak and the fix
  was gated to checkpointed execution. The final focused namespace is GREEN at
  25 tests and 199 assertions; the non-checkpointed result is byte-for-byte
  unchanged at its observable public boundary.
- The deterministic judge proof uses the public evaluation interface and one
  injected `llm/predict` capability. With explicit iteration evidence it can
  explain the exact failing iteration/status/error; without that option the
  existing module, instruction, and inputs remain unchanged. Automatic
  trace-to-judge wiring remains RR-17.

### Measurements

- Named caps: reasoning **438** characters; error excerpt **175** characters.
  The source populations, quantiles, and small error-sample limitation are in
  `docs/build-timeline/prototype-findings/RR5-iteration-evidence-bounds.md`.
- Representative record EDN bytes: timeout 372, tree success 630, direct
  success 714, capped failure 1,406. Complete Fressian event bytes: 778, 1,039,
  1,132, and 1,819 respectively.
- A one-byte versus 65,536-byte payload changes serialized digest size only by
  the decimal digits in `result-profile.length`; the payload itself is absent.

### Reconciliation and declared gaps

`6 obligations, 6 covered, 0 uncovered`.

- `CampaignIteration.campaign` maps to the event/read address
  `[sheet-id tick-id node-id]` rather than duplicating identity in the body.
- Lifecycle/evidence fields remain schema-optional only for unversioned RR-4
  migration compatibility; every production v2 path supplies them.
- Allium `emitted_shape` maps to `:tree-fingerprint`; optional `error` maps to
  `:error-class` plus `:error-excerpt`.
- The current sandbox has no delete primitive, so production
  `removed-keys` is empty. SQLite reconstruction proves persisted removed-key
  rendering; RR-14 owns real sandbox-delta deletion/persistence.
- Provider accounting and claim links are composed outside the digest. RR-17
  owns automatic trace/judge delivery, multi-iteration campaign judgment, one
  verdict, and tag deduplication.
- External-provider explanation quality is not claimed. Tests inject a
  deterministic provider and fixed clock; no generated mock, stub, TODO, or
  skeleton remains. The real-provider E2E namespace remains environment-gated.

### Final verification

- Focused checkpoint namespace: 25 tests, 199 assertions, zero failures and
  zero errors.
- Tier-1 evaluation namespace: 19 tests, 103 assertions, zero failures and
  zero errors. The full evaluation brick passed in both Polylith project
  contexts.
- Full `brick:orc-service` verification passed both Polylith project contexts
  with exit 0 in 17 minutes 48 seconds. The recursive RLM namespace contributed
  54 tests and 229 assertions, all green.
- Allium collection baseline remained exactly 35 warnings, 107 info, zero
  errors, and zero analysis findings. This is the recorded warning baseline,
  not a clean structural check.
- Independent `/inspect-orc` sign-off preserved the obligation audit:
  `6 obligations, 6 covered, 0 uncovered`.
