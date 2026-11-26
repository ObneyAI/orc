# Runtime forms and proof

Current setup source: ORC at `1851212396cced2fc4533fda30b824bd4b691390`; Grain `origin/main` at `dbf5b5229aad09785e40f71e96adf65c90f971d5`. Recheck these files at the consuming project's revisions:

- ORC `components/orc-service/src/ai/obney/orc/orc_service/interface.clj`, `core/dsl.clj`, `core/runtime.clj`, and `docs/GETTING-STARTED.md`.
- Grain `components/event-store-v3/src/ai/obney/grain/event_store_v3/interface.clj`; `components/event-store-sqlite-v3/.../interface.clj`; `components/event-store-postgres-v3/.../interface/datasource.clj`; `components/periodic-task/src/ai/obney/grain/periodic_task/{core,interface}.clj`; `docs/distributed-coordination.md`.

## Standalone dependencies

Create an application's own `deps.edn`; the ORC repository's development aliases are not installed application dependencies. This lean package supplies the engine, ordinary LLM integration and file-store protocol. It does not pull in ontology, ColBERT, evaluation or GEPA. Relative `:local/root` entries inside its Git manifest resolve within the downloaded public repository, not a developer's private checkout.

```clojure
{:paths ["src" "resources"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        obneyai/orc-service
        {:git/url "https://github.com/ObneyAI/orc.git"
         :git/sha "1851212396cced2fc4533fda30b824bd4b691390"
         :deps/root "projects/orc-service"}
        obneyai/grain-core-v2
        {:git/url "https://github.com/ObneyAI/grain.git"
         :git/sha "dbf5b5229aad09785e40f71e96adf65c90f971d5"
         :deps/root "projects/grain-core-v2"}
        ;; One canonical schema registry dependency across combined Git roots.
        cat/schema-util
        {:git/url "https://github.com/ObneyAI/grain.git"
         :git/sha "dbf5b5229aad09785e40f71e96adf65c90f971d5"
         :deps/root "components/schema-util"}
        ;; Include when using the SQLite mode below; omit for in-memory only.
        obneyai/grain-event-store-sqlite-v3
        {:git/url "https://github.com/ObneyAI/grain.git"
         :git/sha "dbf5b5229aad09785e40f71e96adf65c90f971d5"
         :deps/root "projects/grain-event-store-sqlite-v3"}}
 :aliases {:run {:jvm-opts
                 ["--add-opens=java.base/java.nio=ALL-UNNAMED"
                  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]}}}
```

Run the application's entry point with `clojure -M:run -m my-app.main`, or open a REPL with `clojure -M:run`. The alias applies the LMDB JVM options; a plain `clojure -M` does not apply them. Earlier independent runs at ORC `4908162` resolved the equivalent dependency graph, loaded the public DSL and started/stopped LMDB. The updated SHA above is source-inspected; execute it in the consuming application rather than treating that older run as proof at this revision. Recheck the pins and package manifests when upgrading and execute the consuming application's own setup.

Fresh standalone researcher applications have also executed this pinned lean runtime through public boundaries. When adding ontology/ColBERT, use the [canonical combined component graph](../../orc-researcher-pattern-reuse/references/corpus-and-controls.md#establish-a-searchable-corpus); separately downloaded project/component aliases can introduce conflicting transitive local roots. Resolve `clojure -Stree` and interface loading before any paid call. For owned JVM PID tracking use `(.pid (java.lang.ProcessHandle/current))`; the newer class is not automatically imported as `ProcessHandle` by Clojure.

## Single-process wiring

The mainline getting-started guide returns `{:ctx ctx ...}` but later reads `(::context sys)`. Use `(:ctx sys)`. Verify dependency aliases/package availability in the target application before running this form.

```clojure
(ns my-app.system
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface] ; omit for in-memory
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.pubsub.interface :as ps]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.periodic-task.interface :as periodic]
            [ai.obney.orc.orc-service.interface :as orc]))

(defn- owned-start! [owned label start! stop!]
  (let [resource (start!)]
    (swap! owned conj [label #(stop! resource)])
    resource))

(defn- rollback-start! [owned]
  (reduce (fn [failures [label stop!]]
            (try
              (stop!)
              failures
              (catch Throwable error
                ;; Retain safe diagnostic fields; never log a throwable/body.
                (conj failures {:resource label
                                :error-class (.getName (class error))}))))
          [] (reverse @owned)))

(defn start
  [{:keys [tenant-id database-file projection-dir provider auth-claims
           capabilities cache-start-fn start-schedules?
           projection-map-size-bytes projection-max-readers]
    :or {cache-start-fn kv/start start-schedules? false
         projection-map-size-bytes 1073741824 projection-max-readers 1024}}]
  (let [owned (atom [])]
    (try
      (let [pubsub (owned-start! owned :pubsub
                     #(ps/start {:type :core-async :topic-fn :event/type}) ps/stop)
            store (owned-start! owned :event-store
                    #(es/start {:conn (if database-file
                                        {:type :sqlite :database-file database-file}
                                        {:type :in-memory})
                                :event-pubsub pubsub}) es/stop)
            cache (owned-start! owned :cache
                    #(cache-start-fn (lmdb/->KV-Store-LMDB
                                      {:storage-dir projection-dir :db-name "projections"
                                       :map-size projection-map-size-bytes
                                       :max-readers projection-max-readers}))
                    kv/stop)
            ctx (merge {:tenant-id tenant-id :event-store store
                        :event-pubsub pubsub :cache cache :llm-provider provider
                        :auth-claims auth-claims}
                       capabilities) ; trusted host-supplied effect functions
            poller (owned-start! owned :poller
                     #(tp/start-tenant-poller
                        {:event-store store :tenant-ids #{tenant-id}
                         :context (dissoc ctx :tenant-id :event-store)
                         :poll-interval-ms 250}) tp/stop-tenant-poller)
            schedules (when start-schedules?
                        (owned-start! owned :schedules
                        #(periodic/start-periodic-triggers!
                           {:append-fn (fn [args] (es/append store args))
                            :tenant-ids-fn (fn [] #{tenant-id})})
                        periodic/stop-periodic-triggers!))]
        {:ctx ctx :event-store store :event-pubsub pubsub
         :cache cache :poller poller :schedules schedules})
      (catch Throwable startup-error
        (when-let [failures (seq (rollback-start! owned))]
          (.addSuppressed startup-error
            (ex-info "Startup cleanup incomplete" {:cleanup-failures (vec failures)})))
        (throw startup-error)))))

(defn stop [{:keys [ctx schedules poller cache event-store event-pubsub]}
           quiesce!]
  (periodic/stop-periodic-triggers! schedules)
  ;; Application gate: close new work admission, request cancellation as needed,
  ;; and await tracked tool/model bodies while services remain available.
  ;; If it throws, the following stops do not run: retain ownership and retry
  ;; cleanup after active work exits. A terminal result/cancel is not a join.
  (quiesce! ctx)
  (tp/stop-tenant-poller poller)
  (kv/stop cache)
  (es/stop event-store)
  (ps/stop event-pubsub))
```

Load application schema, command, query, processor and periodic namespaces before calling `start`. Requiring ORC registers its periodic recovery scan; a poller alone does not start it. At the merged revision, execution leases prevent healthy owned work from being treated as abandoned. Verify the public recovery contract after a real restart against the same store and preserve the original tick; repeated resume must be a no-op. Lease-based resumption does not make arbitrary external effects exactly-once.

The example leaves schedules an explicit opt-in so the application owns admission, scheduler topology and cleanup. The older healthy-worker duplication proof was at ORC `4908162` and is retained in its original campaign, not a current requirement to disable recovery. The Grain scheduler starts all registered triggers and offers no per-trigger filter in this API; choose ownership deliberately when business schedules and recovery run together.

Keep one scheduler owner and retain the returned schedule handles. The scheduler's append function is the infrastructure trigger seam; domain effects from those triggers still go through commands. For multiple domain tenants, replace the configured singleton with a function returning the current trusted tenant set, and coordinate scheduler ownership/CAS according to Grain's deployment topology. Do not start a second scheduler over the same registry accidentally. Grain v3 tags are `[keyword UUID]` pairs; put a scheduled time's string in the event body, and use a deterministic UUID for a tagged schedule identity when needed.

Every successful resource start registers its stop immediately. If a later start fails, rollback attempts all registered stops in reverse order and rethrows the original exception; cleanup failures add only resource labels and exception class names as suppressed evidence. The helper does not log exception messages, bodies or credentials. A constructor owns cleanup of internal resources it allocates before returning. The optional `:cache-start-fn` defaults to the real `kv/start`; a failure test can inject a throwing implementation after the real pub/sub and event store start, then prove both are closed. Normal shutdown still uses the application-supplied `quiesce!` callback.

Inject code-node capabilities into the processor/poller context. Adding arbitrary functions only to the caller's `execute` context does not make them survive ORC's asynchronous event boundary. For example, put `{:app/record-proposal! guarded-record-proposal!}` in `capabilities`; code actions receive that trusted function in their execution context. Keep authentication and capability configuration host-owned. Claims are data: direct `process-command` and `process-query` calls do not evaluate registry `:authorized?` predicates. Enforce authority in the host capability/handler or use an adapter that does, and include a UUID ID plus offset-date-time timestamp in each envelope. See the [complete guarded command-to-query example](../../orc-grain-application/references/contracts.md).

Choose a disposable `projection-dir` for in-memory experiments and a stable one for durable use. The event store remains authoritative if LMDB must rebuild. Register the provider before executing a model node; use a credential supplied by the application, never one hardcoded into a sheet or skill:

```clojure
(require '[litellm.router :as router])
(router/register! :my-provider
                  {:provider :openrouter
                   :model "google/gemini-2.5-flash"
                   :config {:api-base "https://openrouter.ai/api/v1"
                            :api-key (System/getenv "OPENROUTER_API_KEY")}})
```

That is a wiring example, not a provider availability guarantee. Do not claim a model call was tested without actually invoking it.

```clojure
(def system (start {:tenant-id tenant-id
                    :database-file "/path/to/events.sqlite"
                    :projection-dir "/path/to/projections"
                    :projection-map-size-bytes 1073741824
                    :projection-max-readers 1024
                    :provider :my-provider}))
(def ctx (:ctx system))
(def sheet-id (orc/build-workflow! ctx workflow-definition))
(def tick-id (random-uuid))
(def result (orc/execute ctx sheet-id inputs
                         :tick-id tick-id
                         :timeout-ms 120000 :llm-call-budget 30))
(def tick (orc/get-tick ctx tick-id))
(def events (orc/value-log-read-tick-events ctx tenant-id tick-id))
(def completions
  (filterv #(= :sheet/node-execution-completed (:event/type %)) events))
(def outputs-by-execution
  (into {} (map (fn [completion]
                  [(orc/value-log-execution-key completion)
                   (orc/value-log-resolve-writes
                     ctx tenant-id tick-id completion)]))
        completions))
;; When all work is proven terminal, this application's quiesce callback may
;; simply assert that fact. For an active service it must await/cancel its work.
(stop system application-quiesce!)
```

`orc/execute` accepts a caller-supplied `:tick-id`; its normal completed result carries `:trace-id`, though an early command rejection may not. The supplied ID makes projection read-back reliable. `outputs-by-execution` contains each completed node execution's writes, keyed by node ID and execution context; it is not a reconstructed entire blackboard. Check the expected producer/delegate's named result and domain postcondition against returned outputs and a public projection. Passing `ctx` lets `value-log-resolve-writes` follow delegate references and hydrate configured external values. `value-log-latest-values` only reduces written events; it does not follow delegate references or hydrate raw file-store events. `value-log-read-tick-events` can return `[]` on a read failure, so also check expected durable tick/events or an authorized query before accepting empty evidence. Test the projection through `orc/get-tick` or a relevant Grain read model. Do not infer completion from `:status` alone.

Historical independent deterministic runs at ORC `4908162` and the cited Grain pin exercised this SQLite resource/API graph with ORC's registered scheduler and an ordinary code node whose capability came from the poller context. The node issued the guarded proposal command, and durable tick/events, projected record and query agreed. The exact owned-start snippet also passed healthy startup → command tick → projected receipt → quiesce/stop. Its negative probe started real pub/sub and an in-memory event store, injected a cache-start failure, closed the store then pub/sub, and rethrew the identical original error. These establish the tested local runtime/action and partial-start cleanup paths; they do not establish model quality, a business periodic trigger, restart recovery or optional file backends.

### Canonical value storage

The default `:event-store` mode stores values inline in canonical `:sheet/execution-value-written` events. Explicit `:orc/value-storage {:type :file-store}` writes **every** value to a configured backend and records references. There is no automatic size threshold or inline fallback. The lean package above supplies only the file-store protocol: add and load a local or S3 implementation if choosing that mode, and carry its capability in the poller context as well. Referenced reads verify length/hash and can fail if the object/backend is unavailable. See the consuming revision's `docs/VALUE-STORAGE.md`; optional backends need their own proof.

## Durable and distributed modes

- SQLite: require `ai.obney.grain.event-store-sqlite-v3.interface`; `{:conn {:type :sqlite :database-file ...}}` is the registered form. Preserve the database file across restart; do not run independent process writers as the production topology.
- Postgres: require `ai.obney.grain.event-store-postgres-v3.interface`; `{:conn {:type :postgres :database-name ... :server-name ... :port-number ... :username ... :password ...}}` is adapted through HikariCP. Confirm actual deployment keys with the datasource implementation and pass secrets through the host's secret system. Start Grain `control-plane/start` after processor registration; it owns tenant pollers. For distributed SSE, use Grain's event tailer to republish shared-store events to local pub/sub.
- Recovery: after rebuilding the same sheets/processors against the same durable store, call `(orc/resume-in-progress! ctx)`. Verify original tick, sheet, and node identities. Repeat once; there should be no second work frontier. `execute-stream` is an observation channel, not a recovery source.

## Proof for a new application

1. Resolve the standalone Git dependencies with the LMDB alias, then start and stop cleanly. Verify backend/processor registrations and any explicitly selected scheduler handles/periodic trigger. Check the consuming revision's recovery eligibility before running automatic scans alongside healthy work. Stop schedules, quiesce/cancel active work, then stop poller, cache, event store and pub/sub; confirm no ORC futures or scheduler tasks remain. Unwind already-started services if startup fails midway.
2. Build a small sheet, run it, inspect a relevant public projection and tenant-tagged durable events. Use `{:tenant-id tenant-id :tags #{[:tick tick-id]}}` for direct v3 event reads when a tick ID is known.
3. For real LLM behavior, verify a real provider request and structured output separately; a fake provider only proves wiring.
4. With SQLite, interrupt a running leaf after its start is durable; restart and recover. Assert completion under the original tick and idempotence on a second resume.
5. Read with a second tenant context and show it cannot see the first tenant's events/projections. Test an unauthorized command and query through the actual guarded host/adapter path; trusted claims or metadata alone are not denial proof.


## Continue a completed business goal

An application can explicitly admit a new root for a terminal incomplete goal. Verify the predecessor’s canonical completion and delivered state, original goal revision, current workspace revision and continuation authority. Retain the original pass history, receipts and completed artifacts. Apply the approved additional budget within the same phase and record the predecessor on the new admission. Restore runtime capabilities and obtain fresh source-read authority for any further edit.

The inventory fixture retained 13,363 events and 210 domain records across this boundary. The continued root completed 15 additional passes and cited the current passing acceptance receipt. Its real SQLite admission regression rejects a closed tick as the current root. A running interrupted leaf uses the original-tick recovery path described above. Continuation proof (historical local experiment; private archive not bundled)

## Fresh-process projection readback

Drain owned execution and processors before taking a closed-store SQLite backup. Keep the original snapshot immutable, copy it to a fresh query directory, and open that copy with the public store backend and a fresh projection cache. Load domain/read-model/query registrations, preserve the original tenant, and start no poller, scheduler, provider or execution. Rebuild through authorized public queries and public value-log resolution; assert expected tick identities, native values and domain results, unchanged canonical events and original snapshot hash. Run this in a fresh JVM to exclude process-global projection cache state.

Preserve the execution-return capture separately from the final drained log. A trace processor can commit its own checkpoint after the root result is returned. An earlier event vector being a strict prefix of the closed-store log is a timing difference to inspect, not evidence that a query wrote events. Compare readback against the actual closed snapshot, account for each additional event, and verify the query itself leaves that event set unchanged.

## Projection capacity and timeouts

At the inspected Grain pin, `lmdb/->KV-Store-LMDB` accepts `:map-size` in bytes and `:max-readers`; omitting them selects a 10 MiB map and 1,024 reader slots. The wiring above makes both configurable and uses 1 GiB as a tested example, not a universal minimum or production sizing guarantee. A public KV experiment wrote sixteen distinct 1 MiB values: the default map raised `org.lmdbjava.Env$MapFullException` (`-30792`) on write ten, while the explicit 1 GiB map persisted all sixteen with exact byte readback. The same 20-second host deadline applied to both; measured bodies finished in under a second. More elapsed time or JVM heap does not change that configured database capacity.

Inspect actual LMDB `Env.info().mapSize`, page size and `(lastPageNumber + 1) * pageSize` as an allocated-page highwater estimate. Monitor growth/headroom and reader slots separately. This is not a supported Grain monitoring API: the LMDB environment is an implementation detail, so check the consuming cache representation before inspecting it. Map capacity, allocated file pages, Java used/maximum heap, and process resident memory are distinct. Rebuilding the cache still needs enough capacity. This experiment proves startup capacity configuration, not live resizing, crash recovery or a fix for an unrelated native fault.

Configure deadlines at their own boundaries:

| Boundary | Responsibility |
| --- | --- |
| Provider request (`:config {:timeout ...}` at this client pin) | Bound each HTTP operation; verify actual adapter units/options |
| Root `:timeout-ms` | Bound the intended overall tick |
| Delegate `:timeout-ms` | Bound child work within remaining parent time; inspect effective deadline |
| `:result-grace-ms` | Silence tolerance used to classify an expired completion wait; it does not extend `execute`'s wait. Zero can classify recent or untracked work as wedged failure |
| Application shutdown/drain | Wait for already admitted tool/model bodies before closing services |
| REPL/MCP client wait | Bound the observer's wait; expiration does not establish tick failure or cancel underlying evaluation |

Choose values from the expected work and deployment rather than silently inheriting defaults. Keep experiment limits explicit; larger defaults must not override a specified campaign cap. Inspect the existing tick, active bodies and transport before repeating a client-timed-out evaluation. Cancellation is a request, not proof that blocked/native work was joined.

## Physical attempt ownership and shutdown

When a campaign persists an HTTP-attempt counter, acquire its process lock before loading the counter. Loading first can reuse an old count after another owner has spent calls and released the lock. Native decision calls, delegated children, node retries and provider retries must share the actual outbound attempt gate when the budget applies to the entire operation. At the historical ORC `4908162` ordinary delegate path, a root `:llm-call-budget 30` alone permitted 31 child HTTP requests in a two-delegate deterministic boundary probe; an application-owned cap rejected the 31st before the fake HTTP implementation entered. These were real engine/store runs with fake responses, not provider-quality measurements.

A terminal root result or `orc/cancel!` is not a join of ordinary tool/model bodies. Independent gated probes reached the real root deadline while a delegated code body or provider transport remained active after cancellation. Close admission for new owned work, request cancellation where appropriate, and drain the actual tracked capability/transport bodies before stopping the poller and closing projections/store. Install the application quiescence gate inside the runtime's shutdown path, so an outer `finally` cannot close resources before a surrounding transport scope drains. If that gate fails, keep still-needed resources open, report the bounded failure and retain ownership for cleanup. This does not create a universal ORC join API or prove that every internal/background worker is joined.

## Merged execution policy

At ORC `1851212396cced2fc4533fda30b824bd4b691390`, ordinary execution leases guard recovery ownership, delegates inherit tool/MCP/caller context and effective deadlines/family call budgets, and `get-family-usage` aggregates descendant provider usage. Register native boolean/Choice providers using [Jev setup](../../orc-decision-models/references/jev.md). Keep provider retry and physical-attempt campaign caps distinct. Earlier observations in this reference about condition budget bypass, healthy-leaf resumption and delegate budget leakage are historical at `4908162`, not current required workarounds; verify the new boundaries with public tests and live readback.
