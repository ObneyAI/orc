# Grain contracts and source anchors

Pinned to Grain `origin/main` `dbf5b5229aad09785e40f71e96adf65c90f971d5` and ORC `origin/main` `490816267b76b0428c53437052980a25f5905209`. Recheck the consuming application's actual revision.

Read Grain `docs/core-concepts.md`, `components/example-service/src/ai/obney/grain/example_service/{core,interface}/`, `components/event-store-v3/src/ai/obney/grain/event_store_v3/{core.clj,interface/schemas.clj}`, `components/event-model/src/ai/obney/grain/event_model/interface.clj`, and `docs/event-model.md`. The Grain core-concepts prose still says `->event` generates UUIDv7 IDs. At this pinned source, `->event` constructs an **appendable** event with `:event/type` and `:event/tags`; the v3 backend assigns UUIDv7 `:event/id` and `:event/timestamp` during append. Do not supply either field.

## Current public forms

Put these definitions in `src/my_app/proposals.clj` and load the namespace before starting processors. The example's claims contract is application-owned: the host authenticates a subject and supplies its allowed tenant and permissions. Neither the blackboard nor the request body may supply that authority.

```clojure
(ns my-app.proposals
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event defevent]]
            [ai.obney.grain.command-processor-v2.interface :as cp :refer [defcommand]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.grain.query-processor.interface :as qp :refer [defquery]]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(defn permitted? [ctx permission]
  (let [claims (:auth-claims ctx)]
    (and (some? (:subject-id claims))
         (uuid? (:tenant-id ctx))
         (= (:tenant-id ctx) (:tenant-id claims))
         (contains? (:permissions claims) permission))))

(defn proposal-writer? [ctx] (permitted? ctx :proposal/write))
(defn proposal-reader? [ctx] (permitted? ctx :proposal/read))
(defn forbidden []
  {::anom/category ::anom/forbidden ::anom/message "Unauthorized"})

(defschemas commands
  {:proposal/record [:map [:proposal-id :uuid] [:summary [:string {:min 1}]]]})
(defevent :proposal/recorded "A proposal was recorded."
  {:schema [:map [:proposal-id :uuid] [:summary :string]]})
(defschemas queries
  {:proposal/list [:map]})

(defcommand :proposal record
  {:authorized? proposal-writer? ; metadata used by an enforcing request adapter
   :grain.event-model/produces #{:proposal/recorded}}
  [ctx]
  ;; Explicit handler guard also covers direct cp/process-command callers.
  (if-not (proposal-writer? ctx)
    (forbidden)
    (let [{:keys [proposal-id summary]} (:command ctx)]
      (if (contains? (rmp/project ctx :proposal/proposals) proposal-id)
        {::anom/category ::anom/conflict
         ::anom/message "Proposal already recorded"}
        {:command-result/events
         [(->event {:type :proposal/recorded
                    :tags #{[:proposal proposal-id]}
                    :body {:proposal-id proposal-id :summary summary}})]}))))

(defreadmodel :proposal proposals
  {:events #{:proposal/recorded} :version 1}
  [state event]
  (case (:event/type event)
    :proposal/recorded
    (assoc state (:proposal-id event)
           {:proposal-id (:proposal-id event) :summary (:summary event)})
    state))

(defquery :proposal list
  {:authorized? proposal-reader?
   :grain.event-model/reads #{:proposal/proposals}}
  [ctx]
  (if (proposal-reader? ctx)
    {:query/result (rmp/project ctx :proposal/proposals)}
    (forbidden)))

(defn record-envelope [proposal-id summary]
  {:command/name :proposal/record
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :proposal-id proposal-id :summary summary})

(defn list-envelope []
  {:query/name :proposal/list
   :query/id (random-uuid)
   :query/timestamp (time/now)})

(defn record-proposal! [ctx proposal-id summary]
  (let [written (cp/process-command
                  (assoc ctx :command (record-envelope proposal-id summary)))]
    (when (::anom/category written)
      (throw (ex-info "Proposal command rejected"
                      {:category (::anom/category written)})))
    (let [queried (qp/process-query (assoc ctx :query (list-envelope)))
          expected {:proposal-id proposal-id :summary summary}]
      (when (::anom/category queried)
        (throw (ex-info "Proposal query rejected"
                        {:category (::anom/category queried)})))
      (when-not (= expected (get (:query/result queried) proposal-id))
        (throw (ex-info "Proposal projection did not establish the requested effect"
                        {:proposal-id proposal-id})))
      expected)))
```

At this Grain pin, direct `cp/process-command` and `qp/process-query` validate schemas/envelopes and call handlers; they do **not** execute registry `:authorized?` predicates. The request-handler adapters evaluate that metadata and deny missing/false predicates. The explicit guards above protect the direct path as well. Merely putting trusted `:auth-claims` in context does not enforce anything until a guard reads them. Apply the same rule to ORC diagnostic queries; internal read-model helpers also need a host access boundary when exposed to callers.

IDs are UUIDs; `time/now` returns an offset-date-time required by both envelope schemas. A Java `Instant`, string timestamp or map containing only `:command/name`/`:query/name` is not the complete direct-call envelope. The v3 store assigns the resulting event's ID and timestamp; the caller assigns the command/query envelope's ID and timestamp. Event/read/CAS tags are `[keyword UUID]` pairs at this pin, such as `[:proposal proposal-id]`; arbitrary strings are not entity tag IDs. Keep schedule timestamps in event bodies or derive a stable UUID for a tagged schedule identity.

An independent deterministic run at these pins compiled these definitions and exercised an ordinary ORC code capability through the real command/event/projection/query path. Unauthorized command/query calls returned forbidden; invalid arguments were rejected, a conflicting duplicate was rejected and a second tenant's projection/events were empty. Execute the consuming application's own path and add its actual Allium policy/effect constraints. The duplicate guard illustrates intent validation; read-then-append alone does not make concurrent writes atomic. Bind operation identity to payload and add a CAS/idempotency strategy for concurrent producers.

Keep generated handler vars distinct. `defcommand`, `defquery`, `defreadmodel`, `defprocessor` and `defperiodic` generate a `<service>-<name>` var in their declaring namespace. The read model/query example deliberately uses `:proposal/proposals` and `:proposal/list`. Likewise, name a periodic handler `refresh` and its processor `process-refresh`, or place them in different namespaces. Giving both `:app refresh` in one namespace overwrites the registered periodic Var with a one-argument processor; registration can look successful while scheduler invocation fails. Verify both registered handlers' signatures and their actual execution.

### Execute command → event → projection → query

With a started runtime and host-authenticated `ctx` carrying the example's write/read permissions:

```clojure
(def proposal-id (random-uuid))
(def expected {:proposal-id proposal-id :summary generated-summary})
(def command-result
  (cp/process-command
    (assoc ctx :command (record-envelope proposal-id generated-summary))))
(assert (nil? (::anom/category command-result)))
(def appended
  (into [] (es/read (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :types #{:proposal/recorded} :tags #{[:proposal proposal-id]}})))
(assert (= 1 (count appended)))
(assert (= generated-summary (:summary (first appended))))
(def projection (rmp/project ctx :proposal/proposals))
(assert (= expected (get projection proposal-id)))
(def query-result (qp/process-query (assoc ctx :query (list-envelope))))
(assert (nil? (::anom/category query-result)))
(assert (= expected (get (:query/result query-result) proposal-id)))

;; Adverse proof exercises the same direct handler path, with no authority.
(def denied-id (random-uuid))
(def denied-ctx (assoc ctx :auth-claims nil))
(assert (= ::anom/forbidden
           (::anom/category
             (cp/process-command
               (assoc denied-ctx :command (record-envelope denied-id generated-summary))))))
(assert (= ::anom/forbidden
           (::anom/category
             (qp/process-query (assoc denied-ctx :query (list-envelope))))))
(assert (empty? (into [] (es/read (:event-store ctx)
                          {:tenant-id (:tenant-id ctx)
                           :tags #{[:proposal denied-id]}}))))
```

Register `record-proposal!` as a trusted code-node capability in the [runtime's poller context](../../orc-runtime-bootstrap/references/runtime.md). It checks command rejection before read-back, so an old projection cannot disguise a failed command. ORC's tick/value-log APIs separately prove the tree execution that called it. This proof scenario does not establish concurrent exactly-once behavior or external-service integration.

## Processors and schedules

`defprocessor :area name {:topics #{:area/event}} [context]` receives `:event`, `:tenant-id`, and `:event-store`. Pure `{:result/events [...]}` is supported for infrastructure transitions, but domain state changes should go through a command with its validation policy. Effect mode supports `{:result/effect fn :result/checkpoint :before|:after}`; `:before` risks lost work after checkpoint, while `:after` can repeat an effect after crash. Choose deliberately, enforce an idempotency key for retryable external calls, and verify the checkpoint and resulting projection. Processors may execute concurrently on different events; CAS checkpointing does not make a read/modify/write business invariant atomic.

`defperiodic :area name {:schedule {:cron "0 0 * * *"}} [tenant-id time]` emits a trigger via `:result/events`; use `:result/cas` to deduplicate a schedule tick across nodes. A processor handles the trigger and issues the domain command. Registration does not start the scheduler. Load processor/periodic namespaces, then start `periodic/start-periodic-triggers!` with `:append-fn` and `:tenant-ids-fn`, retain its handles and stop them with `periodic/stop-periodic-triggers!` before closing the store. The [runtime example](../../orc-runtime-bootstrap/references/runtime.md) shows the public forms. Interval schedules fire immediately at startup at this pin; test that if first-fire behavior matters.

## Event Model and behavioral spec

`defeventmodel :area {...}` is a service-area map with `:commands`, `:events`, `:read-models`, `:queries`, `:todo-processors`, `:periodic-tasks`, `:screens`, and `:flows`. Blocks use `:area/name`; identity is `[kind name]` because a read model and query may share a name. Flow endpoints are kind-qualified vectors. Normal flow: command → event → read-model → query → screen; a query can feed a processor that issues a command; periodic tasks may lead to commands or events. `:subscribes` records the processor's event trigger, not a flow input. Commands and screens need `:grain/allium` references to behavioral rules/surfaces for the dev/CI composition gate. `def*` opts can declare `:grain.event-model/reads` and `:grain.event-model/produces` for runtime reconciliation. Validate topology against the live registry with the Event Model validator and validate Allium references in dev/CI. Keep acceptance criteria in `.allium`, not topology metadata.

## Proof matrix

| Scenario | Required observation |
| --- | --- |
| Valid command | Schema accepted; exact event body/tag appended for the intended tenant; projection and query show it. |
| Invalid or unauthorized command | Schema/invariant/adapter rejects it; no domain event appears. |
| Second tenant | Same query and event-read filters expose only that tenant's data. |
| Duplicate/replayed trigger | No duplicate domain effect; original event and checkpoint/idempotency evidence explain why. |
| Restart | Recreate cache/processors from durable store; query state matches pre-restart facts. |
| ORC code action | Invokes command via public interface; original tick trace and domain event/projection can be correlated. |

Source seams: Grain `command_processor_v2/core.clj` `process-command`/`execute-command`; `query_processor/core.clj` `process-query`; request-handler `core.clj` authorization checks; `command_processor_v2/interface/schemas.clj`, `query_schema/interface.clj`, and `time/interface.cljc` envelope types; `periodic_task/{core,interface}.clj` scheduler lifecycle. Resolve these under each component's `src/ai/obney/grain/` at the stated Git pin.

## Reducible event streams

At the inspected Grain/SQLite pin, v3 reads and CAS predicate inputs can be reducible event streams rather than seqable collections. Use `into []` when a small bounded stream needs sequence operations, or consume it with reduction/transduction. Calling `empty?`, `some` or sequence `filter` directly can fail before the intended domain check. Keep tenant scope on direct reads; do not realize an unbounded production history merely to copy a fixture's emptiness check. Verify the chosen backend's actual public read/CAS behavior.

## Revision context

The cited `4908162` observations are historical. Decision/tool APIs were inspected at ORC `1851212396cced2fc4533fda30b824bd4b691390`; see [current source status](../../SOURCE-STATUS.md) for the public integration checkpoint. Preserve those original experiment claims at their original pin; use current owning skills for supported integration.

## Ordered histories need an explicit collection contract

The current Grain reducer starts with `{}`, which is truthy. `(or state {:records []})` therefore leaves `:records` absent on the first event. `(update state :records conj entry)` then creates a list and later prepends, reversing the projected history. Initialize the collection explicitly, for example `(update state :records (fnil conj []) entry)`, or merge declared defaults before reduction. Bump the read-model version when changing a persisted projection's shape/order. Do not reverse a query merely to hide the wrong accumulator.

If a view selects the latest check, define latest by canonical observation order and verify that contract through actual query readback. One check can conceal a reversed-history bug; use distinct real receipts/revisions when that selection matters. Compare cold reconstructed records and their exact receipt bytes with canonical events, while preserving original evidence. A node can succeed and carry all expected fields while its history still has the wrong meaning.

A bounded actual-store workspace consumer exposed this defect before model execution. Its canonical source-read→change-intent→change-fact order was correct, but its projected list was reversed. A fresh v2 projection matched the original event order without replaying old work; one additional actual13-case acceptance established selection of the newest of two passing checks at the current source fingerprint. This is consumer/projection evidence with synthetic reviewer edits, not a model-generated repair or general production guarantee.
