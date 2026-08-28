# RR-P2 finding — real-store claim-epoch compare-and-swap

## Question

Can every shipped Grain v3 event-store backend atomically append an effect
claim only when no higher ownership epoch has already claimed the same logical
action?

## Finding

Yes, on the in-memory, SQLite, and Postgres implementations pinned by this
repository. The working predicate is tenant-local and action-tag-scoped:

```clojure
{:tags #{[:logical-action logical-action-id]}
 :types #{:rr/effect-claimed}
 :predicate-fn
 (fn [events]
   (not
    (reduce (fn [_ event]
              (if (> (:ownership-epoch event) candidate-epoch)
                (reduced true)
                false))
            false
            events)))}
```

`events` is an `IReduce` stream, not necessarily a sequence. The first probe
incorrectly used `not-any?` and failed before evaluating store behavior; using
the protocol's reducible contract corrected the harness.

## Atomicity evidence

The same throwaway program ran against the public `event-store-v3/append` API
on each backend. For each store it:

1. committed epoch 1;
2. committed epoch 2;
3. attempted epoch 1 again;
4. asserted the third append returned a CAS conflict and that the matching
   event count remained two; and
5. ran 50 simultaneous epoch-1/epoch-2 races on distinct action identities and
   verified the durable epoch order was never decreasing.

Observed results:

| Backend | Stale epoch rejected before append | Count before/after stale | 50 race orders nondecreasing | Races with a rejected writer |
|---|---:|---:|---:|---:|
| In-memory | yes | 2 / 2 | yes | 21 |
| SQLite | yes | 2 / 2 | yes | 18 |
| Postgres 16 | yes | 2 / 2 | yes | 25 |

These results match the implementation boundaries at Grain commit
`5de0735d04916c63055a76637fa9bdef36345533`:

- in-memory evaluates the predicate and appends inside one `dosync`;
- SQLite evaluates it and appends inside one `BEGIN IMMEDIATE` transaction;
- Postgres takes `pg_advisory_xact_lock(tenant)` before both the predicate read
  and append in the same transaction.

The Postgres proof used an isolated `postgres:16-alpine` container on a
temporary port. The container was stopped and auto-removed after capture.

## Latency measurement

Each figure is based on 100 unique-action plain appends followed by 100
unique-action CAS appends after ten warmups. “Added” is CAS median minus plain
median on this development machine; it is feasibility evidence, not a capacity
SLO.

| Backend | Plain p50 | CAS p50 | Added p50 | CAS p95 |
|---|---:|---:|---:|---:|
| In-memory | 0.115 ms | 0.104 ms | -0.010 ms (noise) | 0.138 ms |
| SQLite | 0.291 ms | 0.362 ms | 0.070 ms | 0.610 ms |
| Postgres 16 | 1.271 ms | 1.581 ms | 0.310 ms | 1.962 ms |

## Runnable demonstration

The throwaway script accepted `in-memory`, `sqlite`, or `postgres` as its first
argument and used the predicate above. It was run as:

```bash
clojure -M:dev:test /private/tmp/rr_p2_claim_epoch_cas.clj in-memory
clojure -M:dev:test /private/tmp/rr_p2_claim_epoch_cas.clj sqlite

# From Grain's projects/grain-event-store-postgres-v3 directory, with the
# isolated database listening on 127.0.0.1:55432:
clojure -M /private/tmp/rr_p2_claim_epoch_cas.clj postgres
```

The demonstration constructed claims with `event-store-v3/->event`, supplied
the `:cas` map above to `append`, checked `::cognitect.anomalies/conflict`, and
read the action-tagged stream after every proof. The temporary script and
database were discarded after the result was recorded; no prototype code was
kept in production or test paths.

## Decision handed to RR-7

RR-7 may implement the ratified layer-2 fence with a same-tenant, logical-action
tagged CAS predicate and an atomic claim append. The predicate must consume the
provided reducible without coercing it to a sequence. A superseded epoch is a
conflict before dispatch, never a result detected after the effect.

Coverage: `0 obligations, 0 covered, 0 uncovered`. This HITL prototype resolved
a design uncertainty; it did not implement a behavioral obligation.
