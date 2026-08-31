# RR-7 handoff — claim epoch, claim-before-effect, and content-derived action identity

## Read first

1. `AGENTS.md`
2. `docs/ORC-PRINCIPLES.md`
3. `CONTEXT.md` — Campaign, Iteration, Frontier, Claim, Epoch, and Indeterminate effect
4. `docs/issues/rr-durable/RR-7-claim-epoch-claim-before-effect-and-content-derived-action-i.md`
5. `docs/build-timeline/grill-sessions/rr-durable-self-learning-dossier.md` — G7 and R1–R8
6. `docs/prd/rr-durable-self-learning.md` — Ownership and effects
7. `docs/issues/rr-durable/RR-P2-prototype-claim-epoch-compare-and-swap-against-the-real-stor.md`
8. `docs/build-timeline/prototype-findings/RR-P2-real-store-claim-epoch-cas.md`
9. `specs/orc-service.allium` — `Campaign`, `CampaignIteration`, `EffectClaim`,
   `CampaignResumesAtFrontier`, `EffectIsClaimedBeforeDispatch`,
   `ClaimedEffectCompletes`, and the seven RR-7 invariants
10. `components/orc-service/src/ai/obney/orc/orc_service/core/commands.clj` —
    `commit-researcher-iteration-v2`, `checkpoint-researcher-iteration`, and
    the legacy `record-researcher-action`
11. `components/orc-service/src/ai/obney/orc/orc_service/core/read_models.clj` —
    version-2 resume state, iteration records, and legacy researcher actions
12. `components/orc-service/src/ai/obney/orc/orc_service/interface/schemas.clj` —
    version-2 resume-state, iteration, command, and event schemas
13. `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` —
    `phase1-call-tool-fn`, `execute-repl-researcher-rlm`, the current
    `action-ordinal` identities, provider dispatch, and Phase-2 dispatch
14. `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_sandbox.clj` —
    tool bindings and `mint-behavior!`
15. `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_tree_executor.clj` —
    stable generated-child tick construction and rejoin behavior
16. `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj` —
    checkpointed researcher context construction and the current
    `:persist-researcher-action!` callback
17. `components/orc-service/test/ai/obney/orc/orc_service/checkpointed_researcher_test.clj`
18. `components/orc-service/test/ai/obney/orc/orc_service/async_execution_test.clj` —
    real Grain concurrency-barrier precedent
19. `docs/DETERMINISTIC-E2E-TEST-CHECKLIST.md`

## Relevant specification excerpt

> The claim precedes the effect. Nothing external happens until this record
> exists, so an effect that fired always has a claim naming the epoch that
> authorised it.

> A logical action has one claim per ownership epoch. Every physical attempt
> has its own attempt identity derived from the logical action identity, the
> ownership epoch, and a non-negative attempt ordinal.

> Resuming claims the frontier under an epoch at least as new as the one the
> campaign last recorded. A worker holding a superseded epoch cannot resume.

## Corrected RR-P2 result — the design gate

The first RR-P2 finding was falsified during the dependency audit. An
action-tag-only predicate that rejects only a higher epoch is insufficient:

- two writers can both claim the same logical action in the same epoch; and
- after the frontier advances, a stale worker can claim a previously unseen
  action because that action's stream is empty.

The corrected prototype passed on the repository's pinned Grain commit
`5de0735d04916c63055a76637fa9bdef36345533` against in-memory, SQLite, and
Postgres. Frontier, claim, and outcome events share one campaign tag. A CAS
query reads that campaign tag with a union of relevant event types and reduces
the provided `IReduce` stream once. Claim acquisition succeeds only when the
candidate epoch equals the authoritative frontier epoch and no claim for the
same logical action already exists in that epoch. Outcome writes additionally
require the matching claim and no prior resolution.

Each backend rejected a stale new-action claim, rejected a stale outcome,
rejected a same-epoch duplicate, and produced exactly one append plus one
conflict in 50/50 simultaneous same-action/same-epoch races. The prototype was
throwaway; the durable finding is the produced API. Do not restore its original
action-scoped predicate.

Grain facts that constrain the implementation:

- `:command-result/cas` has the shape
  `{:tags #{[:researcher-campaign campaign-tag-id]}
    :types #{...}
    :predicate-fn (fn [events] ...)}`.
- Tags are `[keyword UUID]`. Derive a stable UUID from the campaign identity;
  the human-readable logical action identity remains a string.
- Multiple tags have AND semantics. A campaign-plus-action query would omit
  the frontier event and is not the fence.
- `events` is reducible, not necessarily seqable. Do not call `seq`,
  `not-any?`, or `(into [] events)` inside the CAS predicate.
- Predicates are pure and may be re-evaluated by the in-memory STM.
- A rejected append returns `::cognitect.anomalies/conflict`; no proposed event
  or transaction is persisted.
- Command handlers run before append. No provider, tool, child, or mint effect
  may run in a command handler.

## Produced boundaries — use these, do not invent a second fence

RR-P2 produced a verified predicate shape, not retained production code. RR-7
must give that shape one deep module and one durable vocabulary:

- A campaign is the existing `[sheet-id tick-id node-id]` researcher
  occurrence. Its tag UUID is deterministically derived from that tuple and is
  carried as `[:researcher-campaign campaign-tag-id]` on every frontier, claim,
  and resolution event.
- `:rlm/researcher-frontier-claimed` is the authoritative epoch fact. The
  command that appends it receives the candidate epoch derived from the
  durable frontier being processed: 1 for the initial frontier, otherwise the
  prior frontier's recorded epoch plus 1. It must not derive `latest + 1`
  inside the handler. Two workers processing the same durable frontier must
  therefore propose the same epoch; one wins and the other receives a conflict
  without promotion.
- Version-2 resume state carries the epoch that owned the completed quantum.
  The next `:sheet/node-execution-started` frontier is already tied to that
  resume state. Do not use a process-local counter or wall clock as an epoch.
- `:rlm/researcher-effect-claimed` is appended before dispatch and carries all
  `EffectClaim` fields: campaign identity, iteration identity/index, logical
  action identity, attempt identity, non-negative attempt ordinal, ownership
  epoch, kind, status `:claimed`, and `claimed-at`.
- `:rlm/researcher-effect-completed` resolves that claim after an observed
  outcome, carries `resolved-at` and the existing result payload, and is
  guarded by the same campaign epoch. The claim projection presents one entity
  whose status moves `:claimed` to `:completed`; it does not manufacture a
  second claim.
- Keep `:rlm/researcher-action-completed` readable for pre-RR-7 evidence, but
  the checkpointed RR-7 path must stop treating post-effect recording as its
  safety mechanism. Do not silently rewrite old events.
- Put identity/canonicalization and CAS reducer construction behind a focused
  researcher-effect module. Commands, executor, sandbox, and projections call
  that boundary; none carries its own variant of the digest or predicate.

## Identity contract

Logical action identity is a stable SHA-256 digest over a canonical, typed EDN
representation whose shared preimage is:

`[tick-id node-id iteration-index kind target canonical-request-or-arguments]`

`target` is the tool name, provider/model target, stable generated-child
target, or mint target as applicable. Attempt number and execution order are
excluded. Map and set traversal order must not affect the digest; list, vector,
set, and map must remain distinguishable. An action originating in generated
or executing code adds that code's durable source hash to the preimage. The
outer Phase-1 provider call that produces code instead uses its canonical
request/module inputs and provider/model target; its not-yet-produced response
cannot key its own claim. Reuse a code/source digest already available at
generated-code call sites. Do not substitute the tree-shape fingerprint for a
generated-code hash.

Attempt identity is a second stable SHA-256 digest over
`[logical-action-identity ownership-epoch attempt-ordinal]`. The ordinal is
non-negative. A replay/reordered evaluation of the same content produces the
same logical identity; a physical retry keeps that logical identity but uses a
different epoch or ordinal and therefore a different attempt identity.

The stable logical identity is also the callee idempotency identity for
generated children, checkpoint-safe tools, and behavior mints. Provider calls
do not gain fictional provider deduplication; their claim makes each physical
attempt attributable. RR-10, not RR-7, resolves a claim left indeterminate by a
crash.

## Exact change

1. Add the pure campaign, logical-action, attempt-identity, and CAS-reducer
   boundary with stable canonicalization tests.
2. Add schemas, commands, events, and projections for frontier acquisition,
   pre-effect claims, and completed outcomes. Validate non-negative epochs and
   attempt ordinals at the public command boundary.
3. Acquire the frontier before a checkpointed campaign begins or resumes a
   quantum. A frontier conflict stops that worker before it enters the
   executor. Carry the winning epoch through the execution context and next
   version-2 resume state.
4. Replace checkpointed post-effect `record-researcher-action` dispatch with
   claim → successful append → effect → guarded completion. Only the successful
   claimant invokes the injected capability.
5. Apply the wrapper at all four effect boundaries: inline provider, inline
   Phase-1 tool, generated child, and `mint-behavior!`. Preserve the generated
   child's stable tick/rejoin semantics and each checkpoint-safe tool/mint's
   existing idempotent callee identity.
6. A stale owner may neither claim a new action nor complete an old action.
   A duplicate resolution is a conflict/no-op and never changes the projected
   completed result.
7. Preserve the non-checkpointed path exactly until RR-15. If no winning
   ownership epoch/capabilities are present, existing non-checkpointed dispatch
   and return shapes remain unchanged.
8. Leave indeterminate transition/recovery policy, durable call-budget
   derivation, lease wiring/drain, recovery scanning, and compaction to their
   named later slices.

## TDD cycle list

Run one RED → GREEN → refactor cycle at a time.

1. **Concurrency tracer bullet:** through schema-validated Grain commands in
   `with-async-test-context`, append one frontier, release two simultaneous
   same-epoch/same-logical-action claim attempts behind a barrier, and dispatch
   an injected effect only after a non-conflict claim result. RED because the
   frontier/claim API does not exist. GREEN only when the raw stream and
   projection contain one claim and the counter is exactly one.
2. Advance the frontier and prove the old owner can neither claim an unseen
   logical action nor complete its prior claim; prove the current owner can.
   Read raw events after each attempt so a projection race cannot fake it.
3. Add canonical identity tests: reorder map entries, set members, and action
   evaluation order and preserve logical identity; change each content member
   individually and require a different identity. Prove collection types do
   not collide.
4. Add attempt tests: same logical action plus same epoch/ordinal is stable;
   changing epoch or ordinal changes identity; a negative ordinal is rejected
   before append.
5. Add a public checkpointed provider workflow. Prove the claim event precedes
   the deterministic provider call's completion event, the projected claim is
   completed with timestamps, and replay does not return another call's
   recorded result. Keep provider semantics explicitly at-least-once across
   ownership epochs.
6. Add the inline Phase-1 tool path with reordered canonical arguments. Two
   workers at the same frontier produce one tool call; the callee receives the
   logical idempotency identity.
7. Add the generated-child path. The claim precedes child dispatch, the child
   tick identity is content-stable, and duplicate work rejoins instead of
   creating another child.
8. Add the `mint-behavior!` path. The claim precedes the ontology command and
   the mint receives a stable derived identity plus iteration provenance. Do
   not change ontology contracts outside the minimal existing idempotency seam.
9. Add a full public concurrency scenario with two checkpointed workers
   processing one durable frontier. The loser fires no effect, the winner's
   completion lands, and all frontier/claim/result evidence reads back through
   projections and the raw event store.
10. Re-run all 18 obligation mappings, the focused researcher suites, then the
    complete `orc-service` brick in both consuming project contexts. Refactor
    only after every current cycle is GREEN.

## Propagation status

`allium plan specs/orc-service.allium` yields exactly the 18 obligations named
in the RR-7 issue. Each has a concrete test mapping across the cycles above:

- claim/frontier success, failure, creation, transition, and entity fields →
  cycles 1, 2, 5, and 9;
- uniqueness, epoch, campaign/iteration membership, and resolution invariants
  → cycles 1, 2, 5, and 9;
- logical/attempt identity and ordinal invariants → cycles 3 and 4;
- provider/tool/child/mint failure branches → cycles 5–8.

Initial reconciliation before the first RR-7 RED:
`18 obligations, 18 covered, 0 uncovered`.

This line means every obligation has a planned executable witness. It does not
claim that RR-7 behavior exists. The first propagated concurrency tracer must
be witnessed RED before production code changes.

### Witnessed first RED

`det-e2e-261-same-frontier-race-claims-before-one-effect` ran through
`with-async-test-context` and schema-validated `process-command` with two
workers released by a deterministic barrier. The namespace ran 1 test / 9
assertions with 7 failures and 0 errors. Both RR-7 commands returned
`::cognitect.anomalies/not-found` with `"Unknown Command"`; no frontier or
effect claim was appended, the injected effect count remained zero, and the
claim projection did not exist. Both barrier assertions passed. This is the
expected missing-behavior RED, not a scheduler or harness failure.

The direct `clojure.test/run-tests` JVM retained the repository's asynchronous
test threads after printing its complete summary and was interrupted only
after capture. That shutdown behavior did not alter the RED result.

## Do NOT touch

- `specs/*.allium`; report any contradiction as spec bug, code bug,
  aspirational design, or intentional gap. The orchestrator is the only spec
  writer.
- RR-6 durable-source behavior or its tests except where the already-produced
  source/code hash must be consumed as identity input.
- RR-8 lease wiring, RR-9 drain/deadlines, RR-10 indeterminate recovery,
  RR-11 durable call budgeting, RR-12 automatic recovery, RR-14 sandbox
  deltas, RR-15 default-on, RR-16 trace publication, or later learning slices.
- Existing non-checkpointed behavior.
- The ontology worktree at `/Users/darylroberts/Desktop/Code/orc`.
- Generated or existing tests merely to obtain GREEN.

## Orchestrator live QA

The orchestrator independently runs the barrier race repeatedly, inspects the
campaign-tagged raw stream, compares it to the claim projection, forces a
frontier advance, and tries stale new-action and stale-completion writes. Then
the orchestrator drives one checkpointed workflow through deterministic
provider, tool, child, and mint capabilities and verifies claim-before-effect
ordering and identities at the actual capability boundary.

No real external provider is required: provider/network/tool effects are
injected deterministic capabilities. Grain commands, schemas, event append,
CAS, projections, checkpointed executor, generated-child execution, and the
ontology command boundary are real. Follow with focused tests, both Polylith
project contexts, `allium check`, `allium analyse`, `/weed` check mode, and
`/inspect-orc`.

## Dependency rule

RR-7 consumes the corrected RR-P2 predicate and the real RR-4/RR-5/RR-6
campaign evidence APIs. RR-10 and RR-11 handoffs must be written only after
RR-7 lands and is independently inspected, from its actual claim projection,
identity functions, and status transitions.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

## Report back

- Preserve the final line verbatim: `18 obligations, 18 covered, 0 uncovered`.
- List every RED and GREEN command/result, including the exact first RED.
- Show the raw campaign event order and projected claim for provider, tool,
  generated child, and mint.
- Report repeated race counts and every conflict/anomaly category separately;
  do not fold store failures into expected CAS conflicts.
- Show logical-action preimages/digests across reordered calls and attempt
  identities across retries without exposing provider secrets or payload data.
- Declare every mock, stub, TODO, or skeleton and classify every divergence.
- Do not edit specs, commit, push, or touch another worktree.

## Independent inspection report

### Verdict

The implemented RR-7 fence passes its public behavior, projection, focused, and
affected-namespace gates. DET-E2E-261 and every RR-7 acceptance criterion are
complete. The ratified closure requires a context-aware three-argument caller
for checkpointed effectful tools so the callee can receive and deduplicate the
stable idempotency key; two-argument compatibility remains outside that
boundary.

### RED and GREEN evidence

- First RED: the schema-validated
  `det-e2e-261-same-frontier-race-claims-before-one-effect` tracer ran 1 test / 9
  assertions with 7 failures and 0 errors. Both new commands were unknown, no
  claim existed, and neither effect fired. The deterministic barrier itself
  passed.
- Initial RR-7 cycles 1–5 then ran 5 tests / 48 assertions with zero failures or
  errors through the Grain command, event, projection, and public execution
  seams.
- Contract-closure RED: the public checkpointed workflow supplied a declared
  checkpoint-safe tool with a two-argument caller. All 5 assertions failed: the
  execution succeeded, provider and tool each ran once, and durable effect
  claims were present. GREEN: preflight now rejects the caller before model
  dispatch; the strengthened public witness passes with zero provider calls,
  tool calls, raw claims, and projected claims.
- Adversarial caller-absence RED: no configured caller bypassed that preflight,
  reported workflow success, ran the provider once, and left provider/tool
  claims in both raw and projected evidence. Removing the nil exemption makes
  the same public 5-assertion tracer GREEN before any model or effect work.
- Adversarial RED: a non-conflict frontier command anomaly was incorrectly
  treated as an ordinary lost race, leaving the node waiting until timeout. The
  focused test ran 1 test / 3 assertions with 2 failures. GREEN: only
  `:cognitect.anomalies/conflict` now represents race loss; other store/command
  anomalies fail the node. The same test passed 1 / 3.
- Adversarial RED: two identical tool calls in one generated program shared one
  logical identity, but the second same-epoch claim conflicted after the first
  completion and returned nil, producing `"one|"`. The focused test ran 1 test /
  5 assertions with 1 failure. GREEN: completed effects join an in-run cache
  seeded from durable projection, producing `"one|one"` with one claim and one
  tool dispatch. The test passed 1 / 5.
- The corresponding repeated inline-provider and behavior-mint paths reuse one
  completed logical action. The two focused provider/tool tests passed 2 tests /
  13 assertions; the strengthened mint test passed 1 / 11.
- The public same-frontier worker race passed 10 repeated runs / 120 assertions.
  The full RR-7 namespace passed 21 tests / 154 assertions.
- Eight affected namespaces passed 168 tests / 836 assertions. The earlier
  checkpointed namespace proof passed 29 / 245.
- The first final broad run exposed a load-sensitive pre-existing public-shape
  race in DET-E2E-030: `runtime/execute` could return its direct timeout map
  without `:outputs`, or accept a racing durable timeout reconstruction that
  always attached `:outputs {}`. The unchanged test failed once; 12 isolated
  repeats were green. A deterministic DET-E2E-029 replay assertion then failed
  RED against the reconstruction path. `durable-terminal-result` now omits
  outputs and output references for `:timeout`, matching the ratified resilience
  contract. DET-E2E-029/030 passed together and the full deterministic-failure
  namespace passed 17 / 111.
- The final `clojure -M:poly test brick:orc-service` rerun executed both the
  `orc` and `orc-service` consuming project contexts and exited 0 after 15
  minutes 38 seconds. No test failure or error appeared in either context.

### Durable order and identity evidence

- Provider raw order is
  `:rlm/researcher-frontier-claimed` →
  `:rlm/researcher-effect-claimed` →
  `:rlm/researcher-effect-completed`; the provider observes its raw claim before
  dispatch and the projection contains one completed claim with both timestamps.
- The three-argument checkpoint-safe tool observes its claim before invocation,
  receives the same logical identity as `:orc/idempotency-key`, and projects the
  completed result. Two public workers produce one frontier, one tool claim, one
  completion, and one tool invocation.
- The generated-child claim precedes the real child
  `:sheet/tree-tick-started`; the child tick is deterministically derived from
  the logical identity and the projected result links that same trace.
- The behavior-mint claim precedes
  `:ontology/behavioral-subtree-minted`; the ontology event carries the same
  logical and attempt identities, and two identical mint expressions produce one
  mint event and one completed projected claim.
- Logical preimages use the canonical typed value
  `[tick-id node-id iteration-index kind target request-or-arguments]`, adding
  the durable source hash for generated-code-origin effects. Map and set order do
  not alter the digest; list, vector, set, and map remain distinct. Attempt
  identity uses `[logical-action-identity ownership-epoch attempt-ordinal]`.
  Negative ordinals and caller-supplied identities from a different preimage are
  rejected before append.

### Independent corrections

Inspection found and repaired four defects beyond the initial implementation:

1. Public claim commands accepted arbitrary attempt identities. They now derive
   and compare the identity at the command boundary.
2. Version-2 checkpoint batches were not fenced by the active frontier epoch.
   Their CAS reducer now rejects superseded owners.
3. Effect canonicalization depended on JVM `pr-str` behavior and happened before
   registered checkpoint codecs. Identity now uses the focused canonicalizer
   after codec encoding.
4. A rejected provider claim could still write the legacy post-effect completion
   mirror, and checkpointed direct execution could proceed without claim hooks or
   a positive epoch. Both paths now fail closed.

The inspection also repaired the non-conflict anomaly handling and same-run
completed-action join described in the RED/GREEN evidence. Existing direct
executor fixtures were updated only to supply the new injected claim,
completion, and epoch capabilities; existing behavioral assertions were not
weakened. Old ordinal identity expectations were changed to the ratified
content-derived identity and stable-child-tick contract.

### Allium and weed reconciliation

`allium plan specs/orc-service.allium` contains all 18 named RR-7 obligation
IDs. `allium check specs` reports the characterized baseline of 35 warnings,
107 informational diagnostics, and zero errors; its non-zero exit is therefore
not described as clean. `allium analyse specs` exits 0 with the same structural
diagnostics and zero process findings.

Weed classifications:

- **Aspirational design:** a claimed effect's explicit indeterminate transition
  and recovery policy belong to RR-10.
- **Aspirational design:** recovery after a frontier append but before the next
  checkpoint needs a durable candidate-epoch source; deriving `latest + 1` in
  the claimant is intentionally prohibited and belongs to RR-10/RR-12.
- **Resolved code/spec divergence:** checkpointed effectful tools require the
  context-aware three-argument caller demanded by
  `CheckpointSafeToolsShareIdempotencyKeys`; incompatible callers are rejected
  before model dispatch, claim, or effect. Two-argument compatibility is an
  intentional non-checkpointed boundary, so RR-2 and RR-7 now agree.
- **Intentional gap:** the legacy `:rlm/researcher-action-completed` mirror
  remains readable because current trace assembly consumes it. RR-16 moves trace
  publication to the authoritative evidence.

No subagent edited `specs/*.allium`; the orchestrator's earlier tend pass is the
only RR-7 spec change. No generated or existing test was weakened after its RED
proof. Deterministic LLM, provider, and tool functions are injected test
capabilities. Direct-executor compatibility fixtures inject claim, completion,
and epoch seams. Grain commands, registered schemas, event append/CAS, raw event
reads, projections, checkpointed execution, generated-child execution, and the
ontology command boundary are real. There are no generated mocks, stubs, TODOs,
or skeletons.

`18 obligations, 18 covered, 0 uncovered`.
