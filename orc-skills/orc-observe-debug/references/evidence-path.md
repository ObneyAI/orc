# Evidence path (source snapshot)

For a checkout containing the **unreleased structured-contract repairs**, read [the verified repair path](../../orc-model-sio/references/structured-contract-repairs.md) before applying historical parser/metadata limitations below. It covers generated model-visible output meanings, paired strict parsing, null preservation and execution-owned provider observations. Verify the loaded revisions; these APIs are not assumed to exist on mainline.

Verified against ORC `origin/main` at `490816267b76b0428c53437052980a25f5905209`, SIO `d6d27f9bd59e2ac071396fc7942eabcdd4eabd12`, and ORC's pinned litellm-clj `0570e9459b8751d4a0c3dc1cd48595804426a84d`. Recheck current refs before relying on names or fields.

## Durable queries

Dispatch these through the application's authorized Grain query boundary; results are under `:query/result`:

```clojure
{:query/name :sheet/get-traces :sheet-id sheet-id :limit 20}
{:query/name :sheet/get-trace :trace-id trace-id}
{:query/name :sheet/node-trace-detail
 :trace-id trace-id :trace-instance-id trace-instance-id}
{:query/name :sheet/get-trace-family :trace-id trace-id}
{:query/name :sheet/get-correlated-traces :correlation-id operation-id}
{:query/name :sheet/node-stats :sheet-id sheet-id :node-ids [node-id]}
```

In a direct REPL call, the Grain query envelope needs a UUID `:query/id` and an offset-date-time `:query/timestamp`. At the pinned Grain revision, `qp/process-query` does not evaluate registry `:authorized?` metadata. ORC's metadata checks whether `:auth-claims` are present when an enforcing adapter evaluates it; claims supplied to a direct processor are not a guard. Choose an authenticated adapter or explicitly enforce the application's trace-reading permission and tenant before the direct call. A bare query map is also incomplete.

```clojure
(require '[ai.obney.grain.query-processor.interface :as qp]
         '[ai.obney.grain.time.interface :as time]
         '[cognitect.anomalies :as anom])

(def trace-query-names
  #{:sheet/get-traces :sheet/get-trace :sheet/node-trace-detail
    :sheet/get-trace-family :sheet/get-correlated-traces :sheet/node-stats})

(defn guarded-trace-query [ctx query]
  ;; Example application permission contract; claims come from the host.
  (let [claims (:auth-claims ctx)]
    (if (and (some? (:subject-id claims))
             (uuid? (:tenant-id ctx))
             (= (:tenant-id ctx) (:tenant-id claims))
             (contains? (:permissions claims) :orc/read)
             (contains? trace-query-names (:query/name query)))
      (qp/process-query
        (assoc ctx :query (assoc query
                           :query/id (random-uuid)
                           :query/timestamp (time/now))))
      {::anom/category ::anom/forbidden ::anom/message "Unauthorized"})))

(guarded-trace-query ctx {:query/name :sheet/get-trace :trace-id trace-id})
```

Preserve a query anomaly instead of interpreting it as an empty successful trace. An independent run executed this exact guard against a real ordinary-code ORC tick: an allowed query returned its durable terminal trace, while missing authority and a query outside the allowlist returned forbidden. Test the consuming application's actual access boundary too. The [Grain reference](../../orc-grain-application/references/contracts.md) also shows complete domain command → projection → query forms. Direct read-model/event-store helpers are privileged internal calls, not authorization adapters.

Read the canonical tick events with the v3 interface:

```clojure
(require '[ai.obney.grain.event-store-v3.interface :as es])
(into [] (es/read event-store
           {:tenant-id tenant-id :tags #{[:tick trace-id]}}))
```

`:sheet/execution-value-written` is the canonical write record. `:sheet/get-trace` is shape only. `:sheet/node-trace-detail` rehydrates values, including file-store references, and disambiguates repeated nodes with `:trace-instance-id` and execution context. Source: `components/orc-service/src/ai/obney/orc/orc_service/core/queries.clj` lines 308–322, 326–370, 484–535, 721–760; `docs/EVENT-STORE-PATTERNS.md` single-write section.

### Trace source revision

The direct `:sheet/get-trace` response nests the trace at `[:query/result :trace]`. Inspect that value rather than treating the whole query result as the trace. Count canonical tick facts after excluding exactly `:sheet/execution-traced` and `:sheet/execution-trace-refreshed`; those publication events must not inflate their own revision.

Earlier standalone runs produced correct generated-child values with trace source count7 against8 canonical facts: the late `:sheet/rlm-tree-execution-completed` had no refresh subscription. The later private repair forwards the original bookend context to the existing trace refresher. Event `:tick-id` identifies the child; `:source-tick-id` identifies its separate campaign. Independent public SQLite checks verify a single creation, monotonic revision, parent isolation and idempotent redelivery. A subsequent real emitted-researcher/local strict-HTTP fixture observes11/11 child facts and normal close. This is bounded mechanism evidence; verify the consuming revision rather than assuming every release includes it.

Result delivery and Future cancellation do not prove owned worker bodies have exited. An actual stubborn-body fixture reaches current traces but correctly refuses native-store and ledger normal close with `owned-body-exit-unproven`; process exit terminates that owned experiment, not graceful body join. Distinguish this intended refusal from a model failure.

Researcher iteration records and working history can bound explanation text (438 characters at the inspected private pin). Full raw provider responses and successful final `:iteration-reasonings` retain the complete explanation there; generated iteration code remains exact. Inspect the actual boundary instead of assuming working history, streamed previews and canonical output contain identical text.

Canonical values use inline `:event-store` storage by default. Explicit `:file-store` mode stores every value externally, with no size threshold or inline fallback. Rehydration verifies length/hash and requires the configured backend; missing or corrupt objects are failures. The lean engine package does not include local/S3 implementations. Check `docs/VALUE-STORAGE.md` and the [runtime reference](../../orc-runtime-bootstrap/references/runtime.md) before selecting an optional backend; a trace reference is not proof its bytes can be read.

## Resolve a completed node's outputs

Delegate outputs can be `:sheet/execution-value-referenced` events pointing into a child tick. `orc/value-log-latest-values` only reduces `:sheet/execution-value-written` events; it neither follows those delegate references nor hydrates raw file-store events. Use the public context-aware resolver for a completion:

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc])
(def events (orc/value-log-read-tick-events ctx tenant-id tick-id))
(def completions
  (filterv #(= :sheet/node-execution-completed (:event/type %)) events))
(def outputs-by-execution
  (into {} (map (fn [completion]
                  [(orc/value-log-execution-key completion)
                   (orc/value-log-resolve-writes
                     ctx tenant-id tick-id completion)]))
        completions))
```

This resolves writes for each node execution, preserving node ID and iteration context; it is not an entire reconstructed blackboard. Select the expected producer/delegate completion and assert its required named output plus the domain postcondition. Pass the full trusted `ctx`, which carries the event store and configured value-storage capability. Missing references or an empty event read are not successful output evidence. `:sheet/node-trace-detail` also resolves values with runtime context.

A live verification harness initially used only `value-log-latest-values` on root events and reported missing delegated outputs despite four successful model leaf calls. That readback omitted the child references. Reconcile such evidence through the resolver and the returned root outputs before diagnosing execution. This identifies the measurement error; it does not establish optional file-backend behavior.

Source: pinned ORC public `interface.clj` (`value-log-resolve-writes`) and `core/value_log.clj` (`execution-key`, `latest-values`, `read-tick-events`, `resolve-writes`, `resolve-source`).

## Primary execution failure and secondary missing artifacts

Check the actual phase execution before requiring a successful final-result projection. If a producer or binding node failed, the absent result that only a completed phase could emit is secondary evidence, not a replacement diagnosis. Preserve actual root/tick identity, full execution/trace, partial findings, prior draft and current pending state; keep persisted error fields allowlisted. Find the responsible failure through canonical ownership and node events rather than expected false routing conditions or parsed error prose. Never invent a final result or an extra execution to fill the gap.

Track raw schema compatibility, native/canonical preservation, domain currentness and source-grounded task success separately. A valid preserved answer can still cite a historical record outside the current invocation's allowed observations; that is a consumer-contract/binding issue, not evidence of decoder corruption. Compare the input description with the host guard before changing either. The [consumer checkpoint](../../orc-blackboard-contracts/references/context-evidence.md#investigation-consumer-contract-checkpoint) retains the original binding failure and its later missing-result symptom, alongside the separately audited corrected outcomes.

## Provider request boundary

Trace the same node and attempt through:

1. `orc_service/core/executor.clj`: `build-module` creates SIO fields; `gather-inputs` serializes complex values; `execute-ai` selects options and node `:model`.
2. `components/llm/.../core.clj`: `marker-request` or `function-request` creates one user message and optional `submit_response` tool; `predict` calls `router/completion`.
3. Pinned `litellm/router.clj`: request `:model` overrides the registered default.
4. Pinned provider adapter, for OpenRouter `litellm/providers/openrouter.clj`: `transform-request-impl` constructs the provider map; `make-request-impl` calls `http/post` with `:body (json/encode transformed-request)`.

Capture at step 4 when the question is **what was actually sent**. A probe at `router/completion` sees a pre-adapter Clojure map and is insufficient to establish the final HTTP body. Do not label reconstructed prompts or `node-trace-detail` values as verbatim provider requests.

## Live stream and telemetry

`orc/subscribe-execution` returns live envelopes. For a bounded run, subscribe before executing and wait for the stream's root terminal closure before explicitly closing; closing immediately after `execute` can truncate delivery. `:seq` gaps reveal some consumer drops, but upstream drops may have no gap. Ephemeral deltas vanish on restart; values over about 16 KB are previews. Rebuild from tenant-scoped durable events. `orc/start-telemetry-exporter!`, `orc/telemetry-exporter-stats`, and `orc/stop-telemetry-exporter!` expose bounded forwarding and its retry/drop statistics. Source: `docs/STREAMING.md` ordering/loss and payload sections; `components/orc-service/src/ai/obney/orc/orc_service/interface.clj` lines 49–50, 177–189.


## Verify safe persisted failures

ORC can turn upstream exception text or returned errors into durable node failure/completion data. Apply the [caller-boundary sanitation contract](../../orc-tools/references/catalogs-and-arguments.md#caller-boundary) before ORC receives them. Scrubbing a log or trace display afterwards does not remove canonical event contents. Inspect tenant-scoped events, resolved values and trace details, including configured external value storage when used.

In deterministic adverse tests, inject synthetic secret sentinels in messages, nested causes, ex-data and returned errors, including supported callback/failed-future paths. Run a real public tick through the faked capability; assert honest failure and absence of sentinels in durable events, values, traces and captures. Retain vetted class/status only where the runtime actually exposes them; do not invent missing telemetry. Logger-only assertions are insufficient. This is synthetic failure-path proof, separate from live provider evidence and the application's policy for deliberately retained model inputs/outputs.

## Structural HTTP-attempt ownership

Record actual tick ID, node ID and execution context (including map iteration) with each physical HTTP-attempt ID. Keep the node-attempt/provider-attempt distinction; one successful node may have several HTTP attempts. Identical outputs and unanswered requests make output-equality matching insufficient for exact ownership.

At the pinned ordinary AI path, `execute-ai` has tick/node/context information but removes internal tick and context options before calling the public model client. Final HTTP bodies therefore do not automatically supply those identities. A transport wrapper by itself proves the serialized request and physical call count; it must not claim exact ORC ownership without an independently verified correlation seam. A source-pinned read-only diagnostic hook around internal `execute-ai` can carry these identities through async bindings, but it is internal instrumentation with compatibility risk, not a supported public extension API. Use it only in an isolated owned JVM, preserve original execution behavior, and restore hooks after all tracked work exits. A stable public correlation hook remains a library integration gap where unavailable. Capture allowlisted exception class/status only; never persist internal call arguments, exception messages, ex-data or throwable dumps through the diagnostic hook.

### Verified bridges on the model-contract campaign stack

At ORC base `9116dc99` with the campaign's local repairs, two concurrent strict ordinary leaves sent byte-identical HTTP bodies. The fast workflow completed while the first response was deliberately held. Forty checks per independent run joined each response to its own canonical writes with no crosswrites. This proves controlled two-conversation overlap on one SQLite runtime/tenant, not many-user capacity, live latency or multi-server coordination.

The source-pinned bridge observes `provider-observations/start-provider-attempt!` only after the real command accepts the owner. A per-`execute-ai` dynamic cell carries tenant, sheet, tick, node, canonical node-start event, execution context, node/provider attempt and invocation UUID to final HTTP capture. Hato's supported asynchronous callback closes over that owner and a physical request UUID, returning the original response unchanged. Confirm the tuple against canonical start/completion events and the public attempt query. Do not add identity to the model body or infer it from request/response text.

Checkpointed researcher prime and inline calls can use `:sheet/reserve-provider-call` without ordinary provider-observation UUIDs when fallbacks are disabled. Observe the accepted command's `:invocation-identity`, iteration, campaign, budget root, ownership epoch and provider-attempt ordinal plus the actual runtime tenant/tick/node. Match retained fields to `:sheet/provider-call-reserved`. The command-only `:campaign-sheet-id` is checked separately against the campaign tick start; do not require a field the event does not store. Never fabricate an ordinary invocation UUID to complete a report. See [researcher ownership and recovery](../../orc-repl-researcher/references/model-contracts.md).

These wrappers are diagnostic hooks, not a new supported public transport API. Freeze loaded helpers during a run, preserve original arguments/results, drain tracked bodies, restore hooks, and retain terminal run-specific artifacts. A startup pointer that still says running is not stronger evidence than that run's terminal state. Harness setup/serialization errors must stay separate from product failures.

## Observer timeout versus execution timeout

A REPL/MCP client can stop waiting while its evaluation continues. Keep the original tick/evaluation identity and inspect durable events or a result artifact before starting another run. Separate provider timeout, tree deadline, result silence-grace threshold, client wait and shutdown-drain deadline. A terminal timeout result or `orc/cancel!` does not prove a tracked tool/model body has exited. Diagnose capacity exceptions distinctly too: LMDB `MapFullException` is a configured projection-map limit, not evidence of slow model work. See [runtime configuration and shutdown](../../orc-runtime-bootstrap/references/runtime.md).

## Revision context

The cited `4908162` observations are historical. Current decision/tool APIs at ORC `1851212396cced2fc4533fda30b824bd4b691390` are summarized in [model guidance](../../orc-model-sio/SKILL.md) and [tool guidance](../../orc-tools/SKILL.md). Preserve those original experiment claims at their original pin; use current owning skills for supported integration.

## Token totals versus complete monetary cost

At the current revision, `get-family-usage` correctly reconciled registered model token totals across delegated specialists. Its cost can still be incomplete: the pinned litellm OpenRouter conversational adapter keeps token fields but drops the provider's `usage.cost`, while native decisions preserve it. A real mixed-model family reported only the native cost although the captured conversational responses also contained costs. A public `llm/predict` probe confirmed tokens survived and reported cost did not.

Label family cost as the sum of costs available in normalized usage, not a complete bill. Preserve provider-reported costs at the actual response boundary for accounting, and distinguish missing cost from zero. Do not add raw provider cost to an already complete family total or guess a price from a requested model name. This adapter repair is separate from correct family token/call accounting.
## Reading exported event snapshots

Exported EDN can contain Grain timestamp tags such as `#time/offset-date-time`. Plain `clojure.edn/read-string` does not automatically load application data readers. A missing-reader exception in the audit harness is not a failed event-store replay. Register the tags actually present before comparing the snapshot with reconstructed native values, for example:

```clojure
(edn/read-string
  {:readers {'time/offset-date-time
             #(java.time.OffsetDateTime/parse %)}}
  (slurp snapshot-path))
```

Require `clojure.edn` as `edn`; preserve UUID and other native identities. For additional tags, use the application's actual registered readers. Opaque tagged literals can support text inspection, but do not compare them to reconstructed Java timestamp objects and call the resulting inequality data corruption. Keep the original database/export unchanged, rebuild with the original tenant and a fresh projection cache, and compare canonical values as well as returned summaries.

## Verify which source the REPL executed

A failed namespace reload can leave previously loaded Vars callable. A subsequent green test may therefore exercise the old function or test body. After a load/reload error, confirm the current source loads successfully before crediting later results; use a fresh bounded JVM when the loaded state is uncertain. Preserve the failed reload and distinguish the old-source result from the final current-source verification. This also matters when a test change adds evidence exports: a green old Var may never write the new proof artifacts.


## Completion event identity and correlation tokens

Read lifecycle identity fields according to their actual producer contract. Grain's v3 store assigns `:event/id` to the appended event. A parent delegate completion can also contain `:completion-id`: in the inspected ORC path this intentionally carries the child tick as the idempotency/correlation token, not the parent completion event ID. The public tick-event reader preserves both; value hydration does not alias them. Verify the consuming revision's delegate completion command and event writer before interpreting exported metadata.

When event identity is required, record explicit completion-event-ID and correlation-token fields. Compare the former with the matching canonical event's `:event/id` and the latter separately with its documented child tick/token. `(or (:completion-id completion) (:event/id completion))` is not an event-UUID normalizer: it can select different kinds of identity on delegate and ordinary leaf completions. A frozen calibration consumer mislabeled the delegate token this way; separate actual event IDs were subsequently derived from retained events without rewriting captures. This was a consumer metadata interpretation error, with no library defect shown. Presence-only assertions would not detect it. Preserve original records and document their token semantics alongside corrected audit attribution.
