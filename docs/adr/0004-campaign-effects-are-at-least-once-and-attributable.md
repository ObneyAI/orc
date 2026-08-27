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
target is Kafka itself and the broker checks producer epochs; Confluent states
the boundary plainly — if processing has side effects on other storage systems,
those APIs are not sufficient.

No LLM provider participates. There is no idempotency key for completions at
OpenAI, Anthropic, OpenRouter, Azure OpenAI, or Vertex; Bedrock offers one only
on its asynchronous job APIs. The OpenAI and Anthropic Python SDKs both contain
complete idempotency plumbing with the header name left unset, so the generated
key is never sent — code that reads like support and is not. Both SDKs retry
5xx twice by default with no deduplication key, so a generation whose response
was lost is re-run and re-billed.

Every durable-execution engine lands in the same place. Temporal, Restate, DBOS,
Azure Durable Functions and Step Functions are all at-least-once for side
effects; each "exactly-once" claim scopes to state transitions or workflow
starts, never to the body of a step. Azure documents the identical hazard with a
number attached: roughly ten seconds to detect lease loss, during which
duplicate activity execution may be observed.

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
- Action identity must be derived from content inside the durable step —
  execution-order ordinals change on replay and can return a recorded result for
  a different call.
