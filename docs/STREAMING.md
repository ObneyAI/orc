# ORC Live Streaming

## Watch your tree run

Your tree runs and returns a result — but while it's running, you're blind.
Streaming lets you watch each node start and finish, see incremental output,
and follow a `:repl-researcher`'s exploration live — without changing your
durable event model. Subscribe, and you get a live feed of everything
happening inside your tree.

If you already have a tree, you call `orc/execute` and block until it's done:

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc])

(orc/execute ctx sheet-id {:question "..."})
;; => {:status :success :outputs {...} :duration-ms 1234}
;;    ...but nothing until the whole tree finishes.
```

Swap that one call for `orc/execute-stream` and you get the same result map
*plus* a live channel of everything that happens along the way:

```clojure
(require '[clojure.core.async :as async])

(let [{:keys [events-ch result]}
      (orc/execute-stream ctx sheet-id {:question "..."})]
  (async/go-loop []
    (when-let [e (async/<! events-ch)]
      (println (:orc.stream/type e) (:node-name e))   ;; live: each node start/finish
      (recur)))
  @result)                                            ;; same map execute returns
```

That's the whole jump: `execute` → `execute-stream`, blocking → live. The rest
of this doc explains the event taxonomy you'll see on that channel, what the
stream guarantees (and what it doesn't), and how to bridge it to a UI.

ORC executions can be observed live: node lifecycle, progress, incremental
node results, RLM phase activity, and (Stage 2) LLM token output — delivered
in-process over a core.async channel that your application bridges to
SSE/WebSocket however it likes. ORC stays a library; there is no HTTP layer
here.

Streaming is an **ephemeral observation layer**. The durable event-sourced
model is unchanged: every event that was persisted before is persisted
exactly the same way, whether or not anyone is streaming. If nobody
subscribes, the stream machinery is a no-op.

## What streaming is for

Streaming is an **ephemeral observation layer** — node lifecycle, progress, incremental node results, RLM phase activity. The durable event-sourced model is unchanged: every event that was persisted before is still persisted. If nobody subscribes, the stream machinery is a no-op.

**Use it for:** real-time UI progress bars, live debugging, streaming LLM token output to a frontend, monitoring execution from a separate process.
**Don't use it as persistence** — the event store is the durable record. Streaming is ephemeral.

## Quick start

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc]
         '[clojure.core.async :as async])

;; One call: subscribe + dispatch
(let [{:keys [tick-id events-ch result close!]}
      (orc/execute-stream ctx sheet-id {:question "..."} :timeout-ms 120000)]
  (async/go-loop []
    (when-let [e (async/<! events-ch)]
      (handle-envelope! e)      ;; push to UI, log, etc.
      (recur)))
  @result)                      ;; exact same map blocking execute returns
```

Or subscribe separately — useful when you dispatch ticks yourself
(`tick-id` is caller-suppliable on `execute` and `:sheet/tick-tree`).
**Subscribe before dispatching** so no events are missed:

```clojure
(let [tick-id (random-uuid)
      {:keys [events-ch close!]} (orc/subscribe-execution ctx tick-id)]
  ;; ... dispatch :sheet/tick-tree with :tick-id tick-id ...
  )
```

The context needs a reachable Grain pubsub: either `:event-pubsub` directly
or (the usual case) an `:event-store` started with one — the hub finds it at
`[:config :event-pubsub]`.

## The envelope

Every event on the channel is a flat map:

```clojure
{:orc.stream/type :node-completed   ;; dispatch on this
 :seq        42                     ;; strictly monotonic per subscription
 :ts         #object[OffsetDateTime ...]
 :tick-id    #uuid "..."            ;; tick the event belongs to
 :root-tick-id #uuid "..."          ;; the subscribed root
 :sheet-id   #uuid "..."
 :node-id    #uuid "..."            ;; when node-scoped
 :node-name  "Draft Answer"         ;; best-effort, when resolvable
 :node-type  :ai                    ;; :ai/:code/:sequence/:map-each/...
 :map-each   {:parent #uuid "..." :index 2}  ;; when inside a map-each item
 ...}                               ;; type-specific keys below
```

Malli schemas for every type:
`ai.obney.orc.orc-service.interface/stream-envelope-schema`.

### Event taxonomy

| `:orc.stream/type` | When | Type-specific keys |
|---|---|---|
| `:tick-started` | tick (or re-tick) begins | `:iteration` `:parent-tick-id` `:input-keys` |
| `:node-started` | any node begins | — |
| `:node-completed` | any node finishes — **this is the incremental-results event** | `:status` `:writes` `:usage` `:duration-ms` `:error` `:completion-kind` |
| `:progress` | sequence/map-each advances | `:kind` `:index` `:total` |
| `:child-tick-linked` | a child tick spawned (RLM Phase 2, delegate) | `:parent-tick-id` `:child-tick-id` |
| `:rlm-iteration-started` | RLM Phase 1 iteration begins (**recursive mode only**) | `:iteration` `:max-iterations` |
| `:rlm-code-generated` | model wrote sandbox code (**recursive mode only**) | `:iteration` `:code` `:reasoning` (capped) |
| `:rlm-sandbox-completed` | sandbox execution finished (**recursive mode only**) | `:iteration` `:result` `:stdout` `:error` `:vars-created` `:final?` |
| `:rlm-tree-generated` | emit-tree! produced a tree | `:raw-dsl` (capped) |
| `:rlm-phase2-started` | Phase 2 child tick dispatched (**recursive mode only**) | `:child-tick-id` |
| `:rlm-phase2-completed` | Phase 2 child tick finished (**recursive mode only**) | `:child-tick-id` `:status` `:duration-ms` `:error` |
| `:llm-fields` | *(Stage 2)* progressive per-field text from a streaming `:ai` leaf | `:fields` `:final?` |
| `:llm-raw-delta` | *(Stage 2, opt-in)* raw text delta | `:text` |
| `:tick-completed` | a tick reached a terminal status | `:status` `:outputs` `:error` |
| `:tick-cancelled` | a tick was cancelled | — |
| `:error` | hub-level error (e.g. dispatch anomaly) | `:error` `:source` |
| `:stream-closed` | always the last event | `:reason` (`:completed` `:cancelled` `:timeout` `:ttl` `:closed-by-consumer`) |

Notes:
- The subscription covers the root tick **and every child tick** linked under
  it. Child events carry the child's `:tick-id`; group by it (after
  `:child-tick-linked`) to render sub-trees.
- Re-ticks re-emit `:tick-started` with `:iteration`. Intermediate
  `:running` tick completions are internal re-tick signals and are not
  forwarded.
- Map-each runs the same child `node-id` once per item; `:map-each
  {:parent :index}` disambiguates.

## Ordering, loss, and reconnection

**Plain summary:** `:seq` is a strictly monotonic integer per subscription. A gap in `:seq` means your consumer fell behind the sliding buffer and lost events. For lost events, reconstruct from the event store using `(es/read event-store {:tags #{[:tick tick-id]}})`. This is why streaming is not a replacement for event store queries — it is a live feed, not a guarantee.

- `:seq` is strictly monotonic per subscription, assigned by a single
  router in arrival order. In practice a node's `:node-started` precedes
  its deltas and its `:node-completed` (the forwarding path is
  microseconds; the gap between the underlying durable events spans a full
  append/publish/processor round-trip), but durable event types are
  forwarded by independent tap loops, so cross-type ordering is an
  expectation, not a structural guarantee — consumers needing strict
  lifecycle ordering should reconcile against the durable event store.
- The consumer channel is a **sliding buffer** (default 4096,
  `:buffer` option). A consumer that falls behind loses the **oldest**
  events; the newest (including the terminal `:stream-closed`) always
  survive. This is deliberate: a stalled UI can never stall the engine.
- **Detect consumer-side loss** via a gap in `:seq`. Caveat: under
  sustained burst, events can also be dropped *upstream* of the router
  (engine-wide per-event-type tap buffers, sliding 1024; per-subscription
  merge buffer, sliding 4096) — that loss produces **no** `:seq` gap.
  Consumers needing a complete durable record must reconcile from the
  event store regardless of gaps — every durable event is tagged
  `[:tick tick-id]`:

```clojure
(es/read event-store {:tenant-id tenant-id :tags #{[:tick tick-id]}})
```

- **Reconnection**: deltas and RLM ephemeral events are gone by design
  (never persisted), but the full durable record (node lifecycle, writes,
  usage, completion) is in the event store. A reconnecting UI should
  re-read durable events for the tick, rebuild its view, then continue
  from the live stream of a fresh subscription (child ticks must be
  re-discovered from `:parent-tick-id` on their `tree-tick-started`
  events).
- Streams self-close on the root's terminal event. A TTL backstop
  (`:ttl-ms`, default 1h) closes streams whose tick never completes.
  `close!` is idempotent.

## Payload capping

`:writes` / `:outputs` values bigger than ~16KB (printed) are replaced with:

```clojure
{:orc.stream/truncated true :preview "first 16KB..." :full-size 1048576}
```

The raw value is always in the durable event. Disable value payloads
entirely with `:include-values? false`.

## Cancellation

```clojure
(orc/cancel! ctx tick-id)
;; => {:cancelled [tick-id child-tick-id ...]} | anomaly map
```

Best-effort: the engine stops progressing; in-flight LLM HTTP calls run to completion.

Semantics (best-effort, documented honestly):
- The engine stops progressing: no new nodes start (a guard fails queued
  leaf executions fast), the re-tick loop halts, and known child ticks are
  cancelled too.
- **In-flight LLM HTTP calls run to completion** — there is no abort hook
  at the provider layer yet. A cancelled tick can still consume provider
  tokens for calls already dispatched.
- Callers blocked on `execute` unblock immediately with
  `{:status :failure :error "tick cancelled" :cancelled? true}`.
- Live streams receive `:tick-cancelled` then
  `:stream-closed {:reason :cancelled}`.

## Bridging to a UI

Envelopes contain UUIDs, keywords, `OffsetDateTime`s, and arbitrary
blackboard values. Recommendations:

- **Transit** if your frontend speaks it — round-trips everything.
- **JSON**: stringify UUIDs/timestamps, convert keywords with `name`
  (drop namespaces deliberately), and run values through `pr-str` when not
  JSON-native. Don't `cheshire`-encode raw envelopes blindly — namespaced
  keys like `:orc.stream/type` become awkward (`"orc.stream/type"`) and
  non-JSON values throw.

### Plain SSE (http-kit / ring async)

```clojure
(defn stream-handler [ctx sheet-id inputs]
  (fn [request]
    (http-kit/as-channel request
      {:on-open
       (fn [ch]
         (http-kit/send! ch {:status 200
                             :headers {"Content-Type" "text/event-stream"}} false)
         (let [{:keys [events-ch]} (orc/execute-stream ctx sheet-id inputs)]
           (async/go-loop []
             (if-let [e (async/<! events-ch)]
               (do (http-kit/send! ch (str "data: " (envelope->json e) "\n\n") false)
                   (recur))
               (http-kit/close ch)))))})))
```

### Grain datastar

Grain's `datastar` component (`stream-view`) re-renders a query over SSE
whenever subscribed event types fire on the pubsub. Durable execution
events (`:sheet/node-execution-completed` etc.) work with it directly via
`:event-types` + `:event-tags` — no hub needed — but token deltas and RLM
ephemeral events never touch the pubsub, so for full-fidelity UIs hold a
`subscribe-execution` channel in your view state and patch elements from
it.

## RLM example sequence

A recursive repl-researcher run streams like:

```
:tick-started → :node-started (repl-researcher)
→ :rlm-iteration-started {:iteration 1} → :rlm-code-generated → :rlm-sandbox-completed
→ :child-tick-linked → :rlm-phase2-started {:child-tick-id C}
→ :tick-started (C) → :node-started/:node-completed... (C, live)
→ :tick-completed (C) → :rlm-phase2-completed
→ :rlm-iteration-started {:iteration 2} → ... → :rlm-sandbox-completed {:final? true}
→ :rlm-tree-generated   (durable; emitted once after the node finishes, with the last tree)
→ :node-completed (repl-researcher, full :writes + :usage)
→ :tick-completed → :stream-closed {:reason :completed}
```

Try it live (no UI needed): `development/src/streaming_live_verify.clj`
prints the stream for real OpenRouter-backed runs (an `:llm` leaf with token
deltas, and a recursive RLM node).

## Stage 2: LLM token streaming

Opt in per subscription:

```clojure
(orc/execute-stream ctx sheet-id inputs
                    :llm-deltas? true     ;; :llm-fields progressive field snapshots
                    :raw-deltas? true)    ;; + :llm-raw-delta raw chunks (debug)
```

`:llm-fields` events carry the **text-so-far per output field** (idempotent
for UIs — render the latest snapshot); the last one has `:final? true`.
Active only when ALL hold: a covering subscription set `:llm-deltas? true`,
orc's LLM layer supports streaming (capability detection — older
pins fall back to blocking execution with no delta events), and the node
isn't using function-calling. The cross-repo chain delivering usage-correct
token streams: litellm-clj `stream_options`/usage-in-chunks support →
orc's streaming LLM layer (typed-event channel with a final
`{:outputs :usage :model}` emission) → SHA bumps orc's LLM layer.

Streamed and non-streamed executions always produce **byte-identical
persisted events** — streaming never changes
`:sheet/node-execution-completed` (covered by the equivalence test in
`streaming_test.clj`).

## Engine-safety guarantees (tested)

`ai.obney.orc.orc-service.streaming-test` covers: envelope ordering with
monotonic `:seq`, map-each correlation, concurrent-tick isolation, a
deliberately stalled consumer (engine completes, event store intact),
subscription/lineage cleanup after every scenario, cancellation, and the
delegate child-tick cascade.
