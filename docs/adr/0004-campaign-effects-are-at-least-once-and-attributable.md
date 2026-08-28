# Campaign effects are at-least-once and attributable, not exactly-once

A checkpointed repl-researcher campaign advances in bounded quanta and can be
resumed on another node after a crash or an ownership handoff. A worker that
loses ownership mid-quantum may still have external effects in flight — a
provider call, a tool call, a generated-child dispatch, a behavior mint — so an
effect can happen twice even when the durable record cannot.

The question is what guarantee ORC offers about those effects. This decision is
hard to reverse because it fixes the identity and claim shape of every durable
effect record, and it is easy to re-litigate because "exactly-once" is what
every competing product's marketing appears to promise.

## What the evidence establishes

Exactly-once for an external effect is available exactly when the callee
participates in the deduplication protocol. Kafka achieves it because the effect
target is Kafka itself and the broker checks producer epochs; Confluent's
[delivery-semantics documentation](https://docs.confluent.io/kafka/design/delivery-semantics.html)
separately warns about coordinating effects written to external systems.

No completion provider in ORC's reviewed provider path exposes a request key on
which this design can rely for completion deduplication. The clearest code-level
example is OpenAI's official Python client: it creates a key for non-GET retries,
but only emits it when the client's provider-specific idempotency-header name is
set; the base client leaves that hook unset. Its
[official README](https://github.com/openai/openai-python#retries) also documents
two automatic retries for connection failures, timeouts, conflicts, rate limits
and server errors. That establishes the relevant lost-result window without
claiming that client-side retry plumbing is provider-side deduplication.

The durable-execution systems sampled land in the same place for step bodies.
Azure's [Durable Task programming model](https://learn.microsoft.com/en-us/azure/azure-functions/durable/programming-model-overview)
says an activity may rerun if it completes before its result is recorded. DBOS
[documents the same execute-before-checkpoint window](https://docs.dbos.dev/golang/tutorials/step-tutorial).
AWS states that its default is at-least-once per retry and that neither execution
semantic guarantees exactly once across an entire workflow; at-most-once plus
no retry buys a possible non-execution instead
([AWS durable-execution guidance](https://docs.aws.amazon.com/durable-execution/patterns/best-practices/idempotency/)).

Fencing does not close it either. A token only fences when the resource itself
persists the highest token seen and rejects lower ones; a check immediately
before the effect is a time-of-check-to-time-of-use race that an arbitrary
process pause defeats. Grain exposes no such token, and its compare-and-swap
predicates cannot read lease state at all, because they are scoped to the
appending tenant and cross-tenant reads are forbidden.

## Considered options

- **Promise exactly-once effects** — rejected because it is not buildable from
  our side for the provider call, and the current specification already promises
  it in wording that is false in the handoff window.
- **Rely on the tenant lease alone** — rejected twice over: the lease is not
  wired into ORC's processors at all, so the declared fencing contract is never
  supplied; and even wired, a lease without resource-side token checking cannot
  survive a process pause.
- **Wait for the losing worker to quiesce before reassigning** — rejected as a
  guarantee. No mature system waits for proof of quiescence; cooperative
  cancellation is skipped entirely by a partitioned or paused worker. Retained
  only as probability reduction.
- **Relocate the strong guarantee to where the callee participates** — chosen.
  ORC mints a per-frontier claim epoch in its own event stream and makes every
  durable write conditional on it, so ORC's own store becomes the resource that
  checks the token. Effects whose callee is ORC itself — generated children,
  checkpoint-safe tools, behavior mints — become exactly-once through
  content-derived action identities claimed before dispatch. The provider call
  becomes bounded, attributed waste rather than a correctness risk.

## Consequences

- Campaign state is exactly-once. A superseded worker's result cannot land,
  because its epoch is rejected by the store.
- Generated children, checkpoint-safe tool calls, and behavior mints are
  exactly-once, because ORC is the callee and the claim precedes the dispatch.
- Provider calls are at-least-once, bounded by the number of ownership epochs a
  quantum crosses — in practice two for a single handoff.
- Every indeterminate provider call is durably recorded and attributable to the
  epoch that produced it, so duplicate spend is a measurable line item rather
  than an unexplained cost.
- An indeterminate call is re-called on resume as a second attempt of the same
  logical action, and is not surfaced to the model. The model did not fail; the
  process did, and feeding infrastructure noise into its reasoning history
  teaches it a false lesson about its own work.
- The idempotency key ORC supplies to tools remains meaningful at ORC's own
  boundary and must not be described anywhere as provider deduplication.
- Logical action identity must be derived from content inside the durable step
  and exclude the attempt. Every physical dispatch has a separate attempt
  identity derived from the logical identity, ownership epoch and attempt
  ordinal. Execution-order-only identities change on replay and can return a
  recorded result for a different call.
