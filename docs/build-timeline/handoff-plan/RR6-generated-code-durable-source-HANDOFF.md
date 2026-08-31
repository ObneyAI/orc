# RR-6 handoff — generated code becomes durable source

## Read first

1. `AGENTS.md`
2. `docs/ORC-PRINCIPLES.md`
3. `CONTEXT.md` — Campaign, Iteration, Attempt, Iteration record, Living Description
4. `docs/issues/rr-durable/RR-6-generated-code-becomes-durable-source.md`
5. `docs/issues/rr-durable/RR-P1-prototype-capture-generated-code-as-durable-source.md`
6. `docs/build-timeline/prototype-findings/RR-P1-generated-code-source-capture.md`
7. `docs/build-timeline/handoff-plan/RR5-iteration-record-evidence-digest-HANDOFF.md`
8. `specs/orc-service.allium` — `GeneratedCodeIsDurableEvidence`,
   `CampaignIteration`, and `SuccessfulIterationsRecordTheirCode`
9. `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_sandbox.clj` —
   `build-rlm-context`, the `emit-tree!` binding, and `execute-rlm-code`
10. `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_dsl.clj` —
    the `:code` transformation branch
11. `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_tree_executor.clj` —
    the former sanitizer seam, `compile-tree-node`, the ephemeral registry,
    `execute-tree`, and the source-bearing completion bookend
12. `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` —
    `compute-tree-result-summary`, `iteration-record`, `checkpoint-result`,
    and recursive tree-history construction
13. `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj` —
    the terminal source-bearing tree-generated event path
14. `components/orc-service/src/ai/obney/orc/orc_service/core/read_models.clj` —
    `get-researcher-iteration-records`
15. `components/orc-service/test/ai/obney/orc/orc_service/checkpointed_researcher_test.clj`
16. `components/orc-service/test/ai/obney/orc/orc_service/rlm_dsl_test.clj`
17. `components/orc-service/test/ai/obney/orc/orc_service/rlm_tree_executor_test.clj`
18. `docs/RLM-GUIDE.md` — the persisted-tree event contract

## Relevant specification excerpt

> Code a campaign writes, including code inside a tree it emits, is durably
> recorded as source. A recorded tree can be read, compared across attempts,
> and emitted again. Code is never reduced to a placeholder that cannot be run
> or understood. A source-bearing quoted tree crosses the emit boundary before
> inline function forms are compiled for execution; source is not recovered
> from closure metadata and code nodes are not rewritten into a string-authored
> DSL.

> A successful iteration records its generated code.

## Ratified RR-P1 result

An already-evaluated SCI closure has no recoverable source metadata and is not a
durable-source mechanism. A quoted emitted tree reaches `emit-tree!` as exact
Clojure data, round-trips through `pr-str` / `read-string`, and its inline
`(fn ...)` forms compile with `sci/eval-form` in the same SCI context. The
string-authored fallback was not required and is not authorized.

## Produced APIs — use these signatures, do not invent replacements

- The sandbox builds one SCI context in `build-rlm-context` and binds
  `emit-tree!` inside that context. Compilation of quoted code forms therefore
  belongs at this boundary and must use that same context.
- `emit-tree!` currently stores `:generated-tree` (canonical executable form)
  and `:generated-tree-raw` (the authored tree) in sandbox vars. Preserve this
  two-representation boundary: authored source for durability, compiled values
  only for execution.
- `rlm-dsl/rlm-dsl->orc-dsl` transforms `[:code {...}]` to
  `(sheet/code ...)`. It currently accepts a qualified-symbol string or a live
  function; RR-6 adds quoted `(fn ...)` source at the emit boundary without
  changing code nodes to string-authored functions.
- `tree-executor/execute-tree` receives the canonical executable tree. Its
  `compile-tree-node` currently registers live inline functions in the
  process-local ephemeral registry for the child sheet execution.
- `:sheet/checkpoint-researcher-iteration` atomically records the immutable
  iteration fact and resume state. The record body stores authored tree source
  under `:emitted-tree`; reads use
  `(get-researcher-iteration-records ctx sheet-id tick-id node-id)` ordered by
  `[:iteration-index :attempt-ordinal]`.
- SQLite restart precedent is the explicit stop/reopen pattern in
  `checkpointed_researcher_test.clj`; use a real SQLite v3 event store and
  projection read-back rather than an in-memory serialization assertion.

## Exact change

Split authored source from executable values at `emit-tree!`:

1. When a quoted tree contains a code-node `(fn ...)` form, retain the exact
   source tree as data before compiling only those function forms for execution
   in the same SCI context.
2. Persist the authored source tree in the RR-5 iteration record and every
   durable tree representation. It must contain neither a live function object
   nor `"<inline-fn>"`.
3. Re-emitting a recorded source tree must compile fresh executable functions
   and produce equivalent outputs without consulting a prior JVM's ephemeral
   registry.
4. Remove the sanitize-before-encode stopgap from checkpoint state, iteration
   evidence, tree-result summaries, recursive history, and the completion
   bookend. Do not leave a second placeholder-producing path behind.
5. Update the model-facing emit-tree guidance only as needed to teach the
   ratified quoted-tree form. Do not introduce a string-authored code-node DSL.
6. Preserve the established non-checkpointed behavior for existing trees and
   the default-path compatibility contract until RR-15.

The initial audit found six placeholder-producing production call sites: four
in `executor.clj`, one in `rlm_tree_executor.clj`, and one in
`todo_processors.clj`. The function definition itself and every stale test/doc
expectation must also be reconciled; do not mistake removing only the first five
call sites for completion.

The source tree is authored evidence, not a payload value. Do not truncate it,
profile it, or replace it with a hash. The existing fingerprint remains the
shape identity and deliberately normalizes function bodies; it does not replace
source.

## TDD cycle list

1. Add one public checkpointed workflow test whose deterministic provider emits
   a quoted tree containing an inline code-node `(fn ...)`. Confirm RED because
   the current DSL rejects the source form. Make it GREEN with the smallest
   source-before-compilation path. Assert the workflow output, projected
   iteration record, and raw record event: exact function form present, no live
   function object, and no `"<inline-fn>"` anywhere.
2. Add exact round-trip and shape tests. Read the projected source, round-trip it
   through EDN, confirm equality, and confirm its fingerprint is the same before
   and after durable serialization. RED, then GREEN.
3. Add a public re-emission test. Feed the recorded source tree to a fresh
   researcher/SCI context, emit it again, and prove equivalent output. Clear or
   isolate the ephemeral registry before the second execution so registry reuse
   cannot fake the result. RED, then GREEN.
4. Add the real SQLite close/reopen tracer bullet. Persist the iteration and
   source tree, stop the store, reopen it, read the record projection, and emit
   the reopened source in a fresh execution context. Prove the inline transform
   executes after restart and the completed provider turn is not replayed. RED,
   then GREEN.
5. Remove each `sanitize-tree-for-events` production call site one at a time.
   Keep or replace each old regression assertion with the stronger durable-source
   behavior; never delete the checkpoint-crash guard without its replacement
   being GREEN.
6. Add a negative compatibility test for an already-evaluated closure at the
   durable boundary. It must never be represented as source or silently changed
   into a placeholder. Preserve any authorized non-checkpointed compatibility;
   if durable execution rejects the value, the error must name the quoted-source
   requirement before append rather than crash a projection.
7. Reconcile both formal plan obligations and the named guidance invariant, run
   the focused namespaces, then `clojure -M:poly test brick:orc-service`.
   Refactor only after GREEN.

## Propagation status

`allium plan specs/orc-service.allium` emits two formal RR-6 issue obligations:
`entity-fields.CampaignIteration` and
`invariant.SuccessfulIterationsRecordTheirCode`. RR-5 already covers their
general record shape. RR-6 strengthens their generated-code case through the
public scenario tests above.

`GeneratedCodeIsDurableEvidence` is a named guidance invariant in the same
specification but the current CLI does not emit it as a separate executable
obligation. Do not inflate the formal obligation count. Translate its quoted
source, round-trip, re-emission, and restart clauses into the scenario tests
above and preserve the final line exactly.

Initial reconciliation before the first RR-6 RED:
`2 obligations, 2 covered, 0 uncovered`.

This means tests can be written for every formal obligation; it does not mean
the missing RR-6 behavior is already implemented. The first public test must be
witnessed RED before any production change.

### Witnessed first RED

`quoted-inline-code-tree-is-durable-source-and-executes` exercises the public
workflow boundary with a checkpointed researcher whose provider emits a quoted
tree containing `(fn ...)` source. The existing implementation returned
`:failure`, no `:doubled` output, and no emitted-tree evidence. Its durable error
excerpt names the exact missing seam:
`:code node missing required :fn (qualified-symbol string or inline function)`.
The namespace ran 26 tests / 207 assertions with exactly five assertions failing
inside this new test and zero errors. No production code had changed.

The first attempted focused command used an unsupported `-n` argument and made
`clojure.main` look for a file named `-n`; that harness error is discarded. The
witnessed RED is from direct `clojure.test/run-tests` execution of the namespace.

## Do NOT touch

- `specs/*.allium`; report any contradiction as spec bug, code bug,
  aspirational design, or intentional gap. The orchestrator is the only spec
  writer.
- RR-7 effect claims/fencing, RR-14 sandbox deltas, RR-16 streaming, RR-17
  trace/judge wiring, or RR-22 key binding.
- The RR-4 immutable identity, append ordering, or resume-state separation.
- The RR-5 evidence caps, payload profiles, or allowlist except for replacing
  placeholder tree storage with exact source.
- The ontology worktree at `/Users/darylroberts/Desktop/Code/orc`.
- Generated or existing tests merely to obtain GREEN.

## Orchestrator live QA

The orchestrator independently reads raw iteration events and projections,
searches serialized values for `"<inline-fn>"`, round-trips source, clears the
ephemeral registry between executions, closes/reopens SQLite, re-emits the
reopened tree, and compares outputs/fingerprints. Then run focused tests, both
Polylith project contexts, `allium check`, `allium analyse`, `/weed` check mode,
and `/inspect-orc`.

External-provider code quality is not part of RR-6; the provider is an injected
deterministic capability. The real event store, command, projection, SCI
compiler, child sheet, and restart path are not mocked.

## Dependency rule

RR-6 consumes the verified RR-P1 decision and the real RR-4/RR-5 record API.
RR-20 and RR-22 implementation handoffs must be written only after RR-6 lands
and is independently inspected, using its actual durable-source representation.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

## Report back

- Preserve the final line verbatim: `2 obligations, 2 covered, 0 uncovered`.
- List every RED and GREEN command/result, including the exact first RED.
- Show the source representation before persistence, in the raw event, after
  projection, and after SQLite reopen.
- Enumerate every removed sanitizer call site and every remaining use with its
  justification.
- Declare every mock, stub, TODO, or skeleton and classify every divergence.
- Do not edit specs, commit, or push.

## Implementation report

### Produced representation

`emit-tree!` now keeps two representations at one boundary. The authored quoted
tree is captured before compilation as both exact Clojure data
(`:generated-tree-raw`) and exact EDN text (`:generated-tree-source`). Only
map-entry `:fn` values whose authored value is a list beginning with `fn` or
`fn*` are compiled with `sci/eval-form`, using the same SCI context that is
executing the provider's Phase-1 code. The resulting canonical tree is
execution-only.

The immutable iteration record carries the authored structure under
`:emitted-tree`, its exact text under `:emitted-tree-source`, and the existing
shape fingerprint. `:rlm/tree-generated` carries the same pair as `:raw-dsl`
and `:source-edn`; `:sheet/rlm-tree-execution-completed` carries it as
`:generated-tree` and `:generated-tree-source`. The model-facing iteration
history prefers the exact source text, so a later turn can emit it again.

The source text is authoritative after a persistent-store round trip. Grain's
SQLite Fressian decoder intentionally deep-converts every Java List to a Clojure
vector, so structured event data alone cannot distinguish code lists from DSL
vectors. `read-string` of the retained EDN text reconstructs that distinction;
fresh `emit-tree!` compilation then creates a new ephemeral executable function.
The persisted code-node value itself remains a list form, never a live closure,
placeholder, or string-authored code-node DSL.

Checkpointed execution rejects an already-evaluated inline closure at
`emit-tree!` before tree append and names the quoted-source requirement. The
explicit non-checkpointed compatibility path still executes such a closure, but
keeps its compiled tree execution-only and appends neither the live closure nor
a placeholder.

### TDD and root-cause evidence

- First RED: `checkpointed-researcher-test` ran 26 tests / 207 assertions with
  five failures, all in
  `quoted-inline-code-tree-is-durable-source-and-executes`. The current DSL
  rejected quoted `(fn ...)` source and produced no durable tree evidence.
- The first source-before-compilation cycle made the public workflow GREEN and
  preserved non-checkpointed execution. A separate legacy probe then exposed
  live SCI closures in generated history/events; keeping that representation
  execution-only made the probe GREEN without restoring placeholders.
- Fingerprint round-trip and fresh re-emission cycles cleared the private
  ephemeral registry before the second public workflow and proved equivalent
  output from newly compiled code.
- The durable-boundary negative cycle proved a checkpointed evaluated closure
  is rejected with attributable iteration evidence before any
  `:rlm/tree-generated` append.
- The first SQLite RED showed every authored function list reopened as a
  vector. Accepting `sequential?` was tested and rejected: rebuilding only the
  outer function form left nested calls vectorized and returned literal data,
  which failed the declared output schema. Reading Grain's pinned Fressian
  codec confirmed `deep-clojurize` deliberately maps every Java List to a
  vector. Exact EDN text alongside the structured observation fixed the real
  cause; the unsafe heuristic was reverted to `seq?`.
- A strengthened event assertion then found a second production omission: the
  tree executor placed `:generated-tree-source` on the bookend command, but the
  command handler neither destructured nor emitted it and the event schema did
  not declare it. The command-to-event seam was completed; the focused suite
  finished at 77 tests / 398 assertions, zero failures/errors.
- The adjacent sweep first ran 146 tests / 559 assertions with one failure in
  an old test that deliberately injected a live closure into
  `:generated-tree-raw` and expected the removed sanitizer to rescue it. The
  test was strengthened to retain its checkpoint/resume/no-replay contract
  using quoted source. The rerun passed 146 tests / 560 assertions.
- `clojure -M:poly test brick:orc-service` completed both consuming project
  contexts in 12 minutes 22 seconds with exit 0 and zero failures/errors in
  every namespace summary.

Discarded harness/mutation mistakes are part of the record: one initial test
command forced exit zero regardless of assertion results and was not counted;
one patch temporarily placed a binding in the preceding RR-5 test and produced
a compile error before correction; one focused rerun used the wrong namespace
prefix; and two patch invocations stalled after applying atomically and were
terminated only after the filesystem result was checked. None is cited as
verification evidence.

### Sanitizer removal audit

All six production calls and the function definition were removed:

- `executor.clj`: checkpoint state, iteration record, recursive tree-result
  summary, and recursive history.
- `rlm_tree_executor.clj`: Phase-2 completion bookend.
- `todo_processors.clj`: generated-tree outputs and event.
- `sanitize-tree-for-events` itself and its unused `clojure.walk` dependency in
  the tree executor.

The only remaining `"<inline-fn>"` mentions are negative assertions and the
fingerprint module's explicit compatibility normalization for replaying legacy
evidence. There is no placeholder-producing path.

### Persistent restart proof

The SQLite tracer uses the real Grain v3 store and command/event/projection
path. It writes v2 resume state plus the immutable iteration record, stops the
store, reopens the same file, reads the record projection, resumes with exactly
one new provider call (the completed source-producing turn is not replayed),
clears the old ephemeral registry, and emits the reopened source through a fresh
public workflow. Input `11` produces `22`; the fresh registry is empty after
cleanup. DET-E2E-260 records the complete public and restart contract.

### Weed and Allium inspection

`allium check specs` and `allium analyse specs` each report exactly 35 warnings,
107 informational diagnostics, and zero errors, matching `specs/COVERAGE.md`.
`analyse` reports zero process findings. The non-zero `check` exit is the
characterized warning/info baseline, not a clean check.

Check-mode weed found no spec bug, code bug, or aspirational divergence. Two
differences are intentional gaps:

1. `GeneratedCodeIsDurableEvidence` specifies exact-source behavior without
   prescribing storage keys. The implementation's `:emitted-tree-source`,
   `:source-edn`, and `:generated-tree-source` fields are representation details
   of that invariant, not omitted domain behavior.
2. Source fields remain optional in command/event schemas so historical records
   and bookends replay. The current checkpointed producer always supplies exact
   source for an emitted tree; legacy records may not. Tightening the shared
   unversioned schema would reject the history it exists to replay.

The explicit non-checkpointed live-closure behavior agrees with
`ExplicitOptOutPreservesCompatibility`; it does not claim durable campaign
source. No `specs/*.allium` file was edited by the subagent or orchestrator.

### Inspect-ORC discipline audit

The implementation was re-read and attacked at raw event, projection, fresh
SCI, real SQLite reopen, legacy compatibility, malformed durable closure, and
downstream fingerprint/bookend seams. Grain commands, schemas, events and
projections are exercised; the SQLite setup's direct `es/append` applies only
the real events returned by the registered command so restart can be isolated,
not hand-authored events. There is no domain-specific logic, new forced node
type, LLM-node reasoning contract, map-each leaf, or architecture fork in the
slice. Source is deliberately untruncated. External-provider reasoning quality,
ColBERT, embeddings, browser/device paths, and real-model variance are not RR-6
claims; tests inject the provider response while retaining the real Grain,
SCI, child-sheet, event, projection and restart mechanisms. No mock, stub, TODO,
or skeleton was emitted beyond that declared deterministic provider capability.

`2 obligations, 2 covered, 0 uncovered`
