# Serving independent conversations concurrently

Source scope: generic execution verified against ORC `481523c2` with the accounting follow-up, using real Grain/Postgres and deterministic capabilities. Distinct-user nested parallel execution and durable success recording were independently verified. The broader recovery and effect guidance draws on separate application evidence; genuine successful replay, concurrent recording with convergent CAS retry, child/nonterminal rejection and obsolete-success publication fencing have also been observed at `250de6e6`. A two-request cold restart also retained original ticks/completed values and repeated only unfinished capability bodies. Immutable v1/v2 package coexistence also passed with actual sheet-family and delegate-target checks. Activation in that fixture was host configuration, not a durable registry. This is not a throughput guarantee or complete production application.

## Separate the three concurrency problems

Parallel specialists inside a tree, independent user requests inside one service, and multiple servers processing one tenant have different ownership requirements. Success at one does not prove the others.

One deployed agent can share behavior definitions, model configuration and infrastructure across many users. Each request still owns its tenant/user/conversation identity, attempt, root tick, child lineage, blackboard and durable outcome. Do not put all conversations into a shared mutable blackboard.

## Start without occupying the application processor

Persist the request and its stable execution identity through a domain command. A processor submits that execution through `orc/execute-stream` and returns without dereferencing the result promise. Handle dispatch anomalies explicitly. Dispose of the observation subscription when nobody needs it; the event store, not that subscription, owns durable execution facts.

Record the outcome in a separate processor observing `:sheet/tree-tick-completed`. The event type can also describe a running status or a child tick. Validate tenant, registered root identity, current attempt and terminal status before publishing anything. Reconstruct the result from durable evidence through `orc/get-execution-result` when the consuming revision supplies it. Readback does not run the behavior again.

The domain record command must be idempotent under repeated/concurrent completion delivery. Identical replay changes nothing; conflicting reuse of a completed attempt is refused. Define typed dispatch, execution and recording failures so one bad request cannot cause an endlessly failing processor batch that blocks unrelated users.

## Give concurrency guards the right scope

A guard must see all facts that can invalidate its decision, and exclude unrelated users' history. Tag events by stable domain identities and use event-store CAS over that scope. The appropriate scope may be an account, conversation, operation or attempt; it is determined by the invariant, not by whichever key is easiest to query. Check tag-query semantics before treating several tags as a union.

Prove two things together: unrelated requests can both succeed, and a true duplicate is still rejected. A global lock can hide an incorrect scope by serializing the application. Blind retries can conceal false conflicts while leaving throughput and liveness broken.

When a newer message supersedes an older attempt, request cancellation and also fence result publication and external actions against the current attempt. Cancellation is best effort; late transport completion must not become an accepted obsolete answer or action. Recheck current ownership atomically when the result is appended, not only when the completion is first read.

A completed tick may legitimately refuse cancellation. Read its canonical lifecycle before attempting cancellation; if cancellation is refused after that read, reread the lifecycle to account for a concurrent completion. A terminal tick can be acknowledged as already finished. Preserve other refusals rather than matching message text or suppressing every error. Cancellation acknowledgment does not prove active tool or model bodies stopped.

The generic supersession observation deliberately delayed cancellation: the newer and unrelated requests completed while old capability bodies remained held, then the old root genuinely succeeded and its recording command acknowledged it as obsolete without publishing. A separate concurrent-record observation forced two validated commands to reach the real Postgres append boundary together: one won, one received a CAS conflict, and an identical retry converged. These show result ownership and duplicate handling; they do not establish external action idempotence or cancellation of active work.

## Restore a runtime, not a caller's memory

Register schemas, commands, queries, read models, processors and providers before workers consume persisted events. Put trusted capabilities into the poller/runtime context and reconstruct them after restart. Functions present only in the original submission context may disappear across the asynchronous boundary.

Start and own recovery schedules explicitly; loading their namespace or starting a poller does not start timers. Recover the original tick against the same event store and tenant. Preserve original deadlines, identities and completed outputs. A model call interrupted before its result is durably recorded may be repeated and charged again. The generic cold-restart observation retained ten original root/child tick starts and four completed specialist source records; only two interrupted availability bodies ran again. Automatic recovery completed before a subsequent explicit recovery call returned no work. This is a bounded single-runtime proof, not distributed ownership or a provider exactly-once guarantee.

Align optional-work deadlines with the recovery contract. If an auxiliary summary is permitted to expire quickly, a crash may legitimately produce the main handoff without that summary. If the summary must survive the interruption, specify a separate durable completion policy rather than silently extending every deadline.

External mutations need their own replay contract. Prefer provider-supported idempotency. Otherwise persist original intent and provider guards, reconcile against authoritative state before retrying, retain the original guard, and expose ambiguous outcomes honestly. A stable local action ID does not make an external API idempotent by itself. See the tool and Grain application skills for these boundaries.

## Prepare immutable behavior packages outside admission

Build children before parents and publish a versioned or configuration-qualified package before admitting requests to it. A request retains its selected package identity for its whole lifetime. A new model configuration must not clear/rebuild shared sheets under older active ticks.

Content-hash idempotence makes unchanged registration safe, but repeatedly checking/building an entire package inside a serialized start processor still costs time. Measure package preparation separately from admission latency. Include shared auxiliary sheets when checking version isolation; isolating the main root alone is insufficient.

The generic package observation built five v2 sheets while a v1 request's bodies remained active. The v2 request finished first. Both requests executed their own five actual sheet IDs, and all four delegate targets in each package stayed inside that package. The old package acquired no definition/hash/deletion mutations, and forwarding instrumentation saw no builder calls during admission. A copied `package-id` output alone would not establish this. Host selection of a prepared package does not by itself provide a durable configuration registry or cross-server activation protocol.

## Measure the actual bottleneck

CPU, heap, resident memory, projection map capacity and local GPU capacity are separate quantities. Hosted-model work also depends on provider quotas/latency, transport resources, database pool and locks, downstream systems and queued work. A successful small burst does not establish unlimited capacity.

Measure admission latency, active roots and leaves, queue age, per-user completion latency, database waiters, retries, physical HTTP attempts and per-run cost. Associate every observation with tenant/request/tick ownership. Sum costs from owned calls rather than shared API-key balance changes.

When inspecting node context, resolve actual reads and writes through the consuming revision's public value-log or node-trace APIs. Raw node-start `:inputs` may carry only execution metadata. Require the expected number of resolved records before comparing ownership; an empty loop can otherwise look like successful context verification.

When saving a restart frontier, distinguish executor configuration from durable node type: the observed code executor completions carry `:node-type :leaf`, while delegates carry `:delegate`. Inspect actual events before writing a filter. Preserve typed event timestamps when serializing evidence; a manifest containing `#time/offset-date-time` needs the explicit trusted reader, such as `java.time.OffsetDateTime/parse`, when read with `clojure.edn/read-string`. Do not sanitize the event text or recreate identities to get past a manifest error.

Use distinct per-user inputs and outputs in load proofs. Identical synthetic answers can hide swapped context. Exercise the real message/command → processor → tree → durable completion → domain projection path, and separately test a fresh-process restart. Keep one deliberate negative control that exposes accidental serialization. Do not repeat broad benchmarks without a new uncertainty to resolve.

## Multiple instances are a separate deployment contract

One runtime serving many users is not permission to start duplicate tenant pollers on several servers. Use the consuming Grain deployment's ownership/control-plane design and prove tenant assignment, scheduler ownership, failover and duplicate handling before claiming horizontal scaling. Application-specific single-instance evidence cannot establish those guarantees.

Route tree-level parallel write ownership to the specialist-composition and blackboard skills; lifecycle/store setup to runtime bootstrap; commands/CAS to the Grain application skill; external effects to the tool skill; and trace attribution to observability. Keep project-specific measurements and source paths in the research evidence, not in portable setup instructions.

## Exact public seams and admission limits

Source review and the scoped generic observations at ORC `250de6e6de2ad95647ef6da4c69220c2bd631279` use these forms. The dispatch-rejection caveat below remains source-derived:

```clojure
(orc/execute-stream context sheet-id inputs
                    :tick-id root-tick-id
                    :correlation-id correlation-id
                    :timeout-ms 600000
                    :include-values? false)
;; => {:tick-id uuid :events-ch channel :close! zero-arity-fn :result promise}
;; or a top-level :cognitect.anomalies/category map on subscription failure

((:close! handle)) ; dispose observation; does not cancel the execution
(orc/get-tick context root-tick-id)
(orc/get-execution-result context root-tick-id)
```

These are API forms, not a complete admission handler. Keep the processor nonblocking: do not wait on `:result`. A normal handle does **not** prove dispatch succeeded. At this revision, command rejection closes the subscription and delivers a failure to the result promise but still returns a handle; it may produce no durable completion. The concurrent-serving fixture checks for a durable tick status after dispatch and throws if none exists. That catches this case, but a thrown effect with checkpoint-after is not a durable typed dispatch-failure policy. Provide explicit failure handling before treating this as production admission. Do not infer a rejection category from error prose or mistake a fast execution failure for dispatch rejection.

`get-execution-result` takes context and tick ID, with tenant ownership supplied by context. It returns nil without a terminal tree completion and otherwise reconstructs canonical outputs without rerunning work. The tree-completion schema declares terminal root statuses `:success`, `:failure`, `:tree-generated`, `:partial`, `:timeout` and `:blocked`; `:running` is intermediate. A blocked execution is not a successful business outcome. The runtime result converter also recognizes `:cancelled`, but that value is absent from this event schema; verify cancellation reporting separately.

Filter completion by the application's registered root tick and sheet, then recheck current-attempt authority in the recording command. The fixture's narrower success path accepts only an admitted `:success` root, rereads its terminal event under the tenant/tick, checks no parent tick and lifecycle `:completed`, and reads the durable result. It validates artifact schema and request/user/package ownership before recording under a CAS that includes the conversation’s ownership events and outcome uniqueness. The domain projection retains superseded request history while its public outcomes query exposes current requests. It does not implement general failed/timeout/blocked outcomes.

Source anchors within ORC: `orc_service/interface.clj` (`execute-stream`, `get-execution-result`), `core/streaming.clj` (`execute-stream`), `core/runtime.clj` (`durable-terminal-result`, `terminal-root-status->result-status`) and `interface/schemas.clj` (`:sheet/tree-tick-completed`), beneath `components/orc-service/src/ai/obney/orc/`.

| Contract | Evidence boundary |
| --- | --- |
| Nonblocking handle, observation disposal, durable result signatures | Exercised in scoped generic observations at `250de6e6`; unusual dispatch-rejection behavior remains a separate source-derived caveat |
| Distinct-user nested parallel work and durable success recording | Independently verified at the opening source scope; success-only application fixture |
| Missing tick after a returned handle | Fixture detects it and throws; durable typed failure handling remains open |
| Replay, concurrent recording and completion scope | Genuine replay preserves identity; forced duplicate append admits one winner with convergent retry; real child and running events are rejected before any outcome exists |
| Supersession | Current ownership and late obsolete-success fencing observed; cancellation intentionally arrived after completion, so active-body interruption is unproved |
| Cold restart | Two original requests recovered automatically in a fresh JVM/cache, with completed values retained and only unfinished capability bodies repeated; not a recovery-latency benchmark |
| Package coexistence | v2 completed while v1 stayed active; actual graph/target isolation, unchanged v1 definitions and no admission builder calls observed; host-only activation |
| Temporary-only model failover | Requires reliable typed, sanitized predecessor-attempt evidence; see model/SIO failure boundary |
| Multi-instance ownership, shutdown joining and throughput | Need deployment-specific proof; concurrent success alone does not establish them |

## Recording conflicts are application work

At Grain dbf5b522, todo-processor-v2 `process-effect-after` catches an effect exception and appends its on-failure events plus a checkpoint. Throwing from an outcome recorder does not, by itself, schedule that command again. For a recoverable scope CAS conflict, rerun the actual command decision against fresh durable authority with a finite retry bound; do not reuse the stale decision or repeat tool/provider work. Other failures and exhausted conflicts need an explicit durable failure/recovery policy.

A reconstructed same-tenant SQLite application at ORC4966c8e independently passed controlled dispatch/execution-failure publication and an actual stale-authority append race. The latter reread the current revision before recording a late success as historical. Its latest pending query never substituted an older completed answer. These are scoped controlled-provider proofs, not general recording recovery, cancellation or throughput guarantees.

## Fresh-process recovery with independent effects

A later independently repeated two-boundary recovery experiment (historical local experiment; private archive not bundled) at ORC4966c8e kills the owned JVM mid-model and after an external peer credit commits but before its local receipt is saved. A fresh JVM/cache opens the same SQLite store, reconstructs capabilities and starts owned periodic recovery. It observes the original root before any explicit resume; completed model nodes stay completed, original600s deadline and12-call cap remain, and only abandoned work restarts after its actual90s lease expires.

For the committed credit, fresh execution reads status first using the saved original intent, records the actual peer receipt, and sends no second charge. Controlled HTTP and a separately persistent local peer establish these boundaries; this is not real-provider exactly-once or multi-server ownership evidence. Namespace registration alone had omitted scheduling in the initial consumer bootstrap. Starting and owning the actual Grain schedules fixed that setup; no ORC library change was needed. Exact root-run audit is saved beside the report.
