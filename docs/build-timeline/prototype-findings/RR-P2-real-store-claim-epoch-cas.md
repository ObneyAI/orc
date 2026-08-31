# RR-P2 finding — real-store claim-epoch compare-and-swap

## Question

Can every shipped Grain v3 event-store backend atomically enforce the whole
campaign fence: only the current ownership epoch may write, and only one claim
for a logical action may exist in that epoch?

## Finding

Yes, on the in-memory, SQLite, and Postgres implementations pinned by this
repository. The working predicate is tenant-local and campaign-tag-scoped.
Frontier-claim and effect-claim events share the campaign tag; the CAS query
uses that tag and a union of event types so one single-pass reducer can verify
both the authoritative epoch and same-action uniqueness:

```clojure
{:tags #{[:campaign campaign-id]}
 :types #{:rr/frontier-claimed :rr/effect-claimed}
 :predicate-fn
 (fn [events]
   (let [{:keys [frontier-epoch duplicate?]}
         (reduce
          (fn [state event]
            (case (:event/type event)
              :rr/frontier-claimed
              (update state :frontier-epoch max (:ownership-epoch event))

              :rr/effect-claimed
              (if (and (= logical-action-id (:logical-action-identity event))
                       (= candidate-epoch (:ownership-epoch event)))
                (assoc state :duplicate? true)
                state)

              state))
          {:frontier-epoch -1 :duplicate? false}
          events)]
     (and (= candidate-epoch frontier-epoch)
          (not duplicate?))))}
```

`events` is an `IReduce` stream, not necessarily a sequence. The first probe
incorrectly used `not-any?` and failed before evaluating store behavior; using
the protocol's reducible contract corrected the harness. Predicates must be
pure because the in-memory STM may evaluate one more than once.

The first recorded finding was still incomplete after that harness repair. It
queried only the logical-action tag and rejected only epochs greater than the
candidate. That allowed both a duplicate claim in the same epoch and a stale
worker claiming a previously unseen action after the frontier advanced. An
independent RR-7 dependency audit exposed both counterexamples. The
campaign-scoped predicate above is the corrected result; the old
action-scoped predicate must not be implemented.

## Atomicity evidence

The corrected throwaway program ran against the public
`event-store-v3/append` API on each backend. For each store it:

1. claimed a campaign frontier at epoch 1 and claimed one effect;
2. advanced the same frontier to epoch 2;
3. proved the stale epoch-1 owner could neither complete its old effect nor
   claim a previously unseen action;
4. proved the epoch-2 owner could claim and complete that action;
5. proved a duplicate epoch-2 claim conflicted before append; and
6. ran 50 simultaneous same-action, same-epoch races on distinct campaigns,
   requiring one durable claim and one rejected writer in every race.

Observed results:

| Backend | Stale new action rejected | Stale outcome rejected | Same-epoch duplicate rejected | Same-epoch races with exactly one conflict |
|---|---:|---:|---:|---:|
| In-memory | yes | yes | yes | 50 / 50 |
| SQLite | yes | yes | yes | 50 / 50 |
| Postgres 16 | yes | yes | yes | 50 / 50 |

These results match the implementation boundaries at Grain commit
`5de0735d04916c63055a76637fa9bdef36345533`:

- in-memory evaluates the predicate and appends inside one `dosync`;
- SQLite evaluates it and appends inside one `BEGIN IMMEDIATE` transaction;
- Postgres takes `pg_advisory_xact_lock(tenant)` before both the predicate read
  and append in the same transaction.

The Postgres proof used an isolated `postgres:16-alpine` container on a
temporary port. The container was stopped and auto-removed after capture.

## Latency measurement

Each corrected figure is based on 100 plain effect appends and 100
campaign-scoped CAS effect claims after ten warmups. The CAS campaign's stream
grows during the run, matching the corrected predicate rather than the invalid
action-local measurement. “Added” is CAS median minus plain median on this
development machine; it is feasibility evidence, not a capacity SLO.

| Backend | Plain p50 | CAS p50 | Added p50 | CAS p95 |
|---|---:|---:|---:|---:|
| In-memory | 0.085 ms | 0.133 ms | 0.048 ms | 0.540 ms |
| SQLite | 0.330 ms | 0.961 ms | 0.631 ms | 1.327 ms |
| Postgres 16 | 1.400 ms | 2.246 ms | 0.845 ms | 2.693 ms |

## Runnable demonstration

The throwaway script accepted `in-memory`, `sqlite`, or `postgres` as its first
argument and used the predicate above. It was run as:

```bash
clojure -M:dev:test /private/tmp/rr_p2_same_epoch_cas.clj in-memory
clojure -M:dev:test /private/tmp/rr_p2_same_epoch_cas.clj sqlite

# From Grain's projects/grain-event-store-postgres-v3 directory, with the
# isolated database listening on 127.0.0.1:55432:
clojure -M /private/tmp/rr_p2_same_epoch_cas.clj postgres
```

The demonstration constructed frontier, claim and outcome events with
`event-store-v3/->event`, supplied the `:cas` map above to `append`, checked
`::cognitect.anomalies/conflict`, and read the campaign-tagged stream after
every proof. The temporary script and databases were discarded after the
result was recorded; no prototype code was kept in production or test paths.

## Decision handed to RR-7

RR-7 may implement the ratified layer-2 fence with a same-tenant,
campaign-tagged CAS predicate and atomic frontier/claim/outcome appends. Both
frontier and effect events carry the campaign tag; action identity may also be
tagged for read-back but cannot be the fence's only scope. Claim acquisition
requires the candidate epoch to equal the latest frontier epoch and rejects an
existing same-action claim in that epoch. Outcome/state writes allow the
current owner's equal epoch but reject a higher frontier epoch and duplicate
resolution. Predicates consume the reducible in one pure pass. A CAS conflict
occurs before dispatch; the effect never runs inside the command handler,
because command handling precedes append.

Coverage: `0 obligations, 0 covered, 0 uncovered`. This HITL prototype resolved
a design uncertainty; it did not implement a behavioral obligation.
