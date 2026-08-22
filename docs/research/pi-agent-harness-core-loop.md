# Pi's agent harness core loop in ORC primitives

Status: research note, based on ORC commit `38e0319` and Pi commit
`c49906ec77788625aacbdc53ebca6fbe65bd20f5` (2026-08-22).

## Conclusion

The closest single ORC primitive to Pi's agent loop is a recursive
`:repl-researcher` node. It owns an iterative model-controlled cycle, retains
working state between iterations, can invoke tools and LLMs, can emit child
behavior trees, observes their results, and terminates explicitly with
`final!`.

That is not the whole analogue, however. Pi's `runLoop` combines several
responsibilities in one in-memory transcript loop. In ORC those responsibilities
are deliberately split across primitives:

```text
Pi AgentContext.messages                 ORC blackboard + sandbox-vars/history
Pi assistant turn                        repl-researcher Phase-1 code generation
Pi tool-call batch                       inline call-tool / emitted ORC subtree
Pi append ToolResultMessage              durable child outputs + tree-results
Pi repeat while tool calls remain        recursive repl-researcher iteration
Pi agent/turn/tool events                tick/node events + ephemeral stream
Pi AbortSignal                           tick budget + cancel-tick (best effort)
Pi follow-up / steering queues           no orc-service primitive; host concern
Pi final text response                    final! declared writes / tick result
```

The architectural answer is therefore:

> Pi's harness loop is analogous to an ORC-hosted recursive researcher workflow,
> not to ORC's tree-tick loop alone. `:repl-researcher` supplies the adaptive
> decision loop; `:sheet/tick-tree` and the event processors supply durable
> execution; the blackboard and execution-value events supply typed state;
> `call-tool-fn` / `:tool-caller-fn` supply the effect boundary; `:delegate` and
> generated child ticks supply composition; and a host/session layer must supply
> conversation transcripts, steering, and follow-up queues.

## What Pi's loop actually guarantees

Pi's core loop is transcript-driven:

1. Add prompt messages and emit agent/turn/message lifecycle events.
2. Transform the accumulated agent context and stream one assistant message.
3. Collect tool calls from that assistant message.
4. Validate and gate each call, execute the batch sequentially or in parallel,
   and append ordered tool-result messages to the transcript.
5. Ask the model again while the batch requests continuation.
6. Inject steering messages before the next assistant call.
7. When the model would stop, consume queued follow-up messages or terminate.

The current implementation is in Pi's
[`packages/agent/src/agent-loop.ts`](https://github.com/badlogic/pi-mono/blob/c49906ec77788625aacbdc53ebca6fbe65bd20f5/packages/agent/src/agent-loop.ts):

- `runAgentLoop` and `runAgentLoopContinue` establish the initial transcript and
  lifecycle (`95-143`).
- `runLoop` contains the nested tool/steering and follow-up loops (`155-275`).
- `streamAssistantResponse` transforms context at the model-call boundary and
  emits streaming message events (`281-372`).
- `executeToolCalls` selects sequential or parallel batch execution (`411-425`).
- tool preparation validates arguments and runs the pre-call gate; execution
  streams updates; and finalization runs the post-call hook (`600-780`).

This is a small operational kernel. Session persistence, UI behavior, compaction,
and coding-agent policy are layers around it rather than the loop itself.

## Responsibility-by-responsibility mapping

| Pi responsibility | Closest ORC primitive | Match | Important difference |
|---|---|---:|---|
| Accumulated model context | Typed blackboard for workflow state; `sandbox-vars`, iteration `history`, and `tree-results` inside a recursive researcher | Partial | ORC state is dataflow-oriented, not a canonical user/assistant/tool transcript. |
| One model decision | Phase-1 prediction in `execute-repl-researcher-rlm` | Strong | The model emits sandbox Clojure (or a structured code field), not an assistant message containing native tool calls. |
| Tool invocation | `call-tool` in the RLM sandbox, resolved through `call-tool-fn` or a node `:tool-caller-fn` | Strong | Calls happen from generated sandbox code or generated `:code` nodes, rather than directly from assistant content. |
| Tool schema and authorization | Bound MCP tool contracts plus the configured tool gate | Strong | ORC's declared input/output and consumer gate are more explicitly workflow-scoped. |
| Multiple tool calls | Multiple inline calls; generated `:sequence` / `:parallel` / `:map-each` trees | Strong structurally | Parallelism is expressed as tree topology; it is not an implicit default for one assistant tool-call batch. |
| Feed observations back to the model | Persistent sandbox variables and chronological `tree-results`, followed by another Phase-1 iteration | Strong | ORC summarizes child-tree outcomes and exposes declared outputs instead of appending `ToolResultMessage`s verbatim. |
| Continue until model stops | Recursive researcher loop until `final!`, max iterations, or budget exhaustion | Strong | Pi infers continuation from tool calls and stop reasons; ORC requires the model-generated program to call `final!`. |
| Durable execution | `tick-tree`, execution-value events, node lifecycle events, and read models | Stronger in ORC | Pi core mutates an in-memory context; persistence is supplied above it. |
| Sub-agent/subworkflow composition | `:delegate` to a versioned sheet; generated child ticks for emitted trees | Stronger in ORC | Delegation is a first-class execution lineage and typed data-transfer seam. |
| Streaming observability | `execute-stream` / `subscribe-execution`; tick, node, RLM, LLM-field, and child-link envelopes | Strong | ORC's live stream is explicitly lossy and reconstructs durable facts from the event store. |
| Cancellation | `cancel-tick`, execution deadlines, shared LLM-call budgets | Partial | ORC stops progression but currently cannot abort an in-flight provider HTTP call. |
| Mid-run steering | None in `orc-service` | Missing | A session host would need to persist queued input and expose it at a safe iteration boundary. |
| Follow-up messages after apparent completion | None in `orc-service` | Missing | A host can start/correlate another tick, but there is no atomic "check queue before closing this agent run" primitive. |
| Context transform / compaction | Prompt construction plus bounded RLM history/variable previews | Partial | There is no general transcript-level `transformContext` hook because ORC does not own the transcript. |
| Before/after every tool hook | Pre-call gating exists; result flows through tool output validation | Partial | There is no Pi-shaped generic post-tool result-rewrite hook at the harness level. |

## The two loops must not be confused

ORC contains two kinds of repetition relevant to this comparison.

The tree-tick loop is the durable behavior-tree scheduler. A
`:sheet/tree-tick-started` event starts root execution; node-start and
node-completion events advance composites; a root completion with `:running`
causes another tick iteration; otherwise the tick becomes terminal. This is
the analogue of a workflow runtime or scheduler, not of Pi's model/tool
conversation loop.

The recursive researcher loop is the adaptive agent loop. Each iteration asks
the model for sandbox code, executes that code with `llm`, `code`, `call-tool`,
`emit-tree!`, and `final!` capabilities, retains variables and history, and—when
the model emitted a child tree—merges declared child outputs and a factual
`tree-results` entry before returning to the model. This is the closest match
to Pi's reason/act/observe cycle.

Putting a `:repl-researcher` at the root of a tick composes the two:

```text
host/session
  -> tick-tree
       -> repl-researcher Phase 1: decide next action
            -> inline tool/LLM work, or emit-tree!
                 -> child tick: deterministic ORC topology
                      -> leaves / delegates / tools
                 <- declared outputs + factual tree-results
            -> next Phase-1 iteration
            -> final!
       <- durable tick result and trace
```

## Where the analogy breaks

### ORC does not own a conversation

`orc-service` executes typed workflows from input maps to output maps. It has
opaque `tool-context` plumbing for a host's turn identity, but it intentionally
does not interpret session or message semantics. Consequently, user/assistant
messages, branching transcripts, compaction, steering queues, and follow-up
queues belong in a host component (the code comments identify `orc-sessions` as
that owner, but that component is not present in this repository).

### A researcher iteration is program synthesis, not a native tool-use turn

Pi presents tools to the model and interprets tool-call content directly. ORC's
recursive researcher asks the model to produce a small Clojure program. That
program may call a tool inline or construct a behavior tree. This extra
indirection is intentional: ORC puts deterministic structure around model work
and allows one decision to produce a typed, observable, delegatable subtree.
It also means `:repl-researcher` is not a drop-in implementation of Pi's event
and transcript contract.

### ORC observations are projections, not just return values

Pi appends tool results to the current context. ORC writes execution values and
lifecycle facts durably, then projects tick state, blackboard values, traces,
and lineage. A faithful ORC design must verify those projections and events,
not merely the map returned by `execute`.

## Recommended ORC shape for a Pi-like coding harness

If the goal is behavioral equivalence to Pi rather than merely "an agent that
can use tools," keep the session loop outside `orc-service` and use ORC as the
durable action engine:

1. A session host owns the canonical transcript, queued steering/follow-up
   messages, context transformation/compaction, and user-facing lifecycle.
2. Each agent turn invokes a versioned root workflow through `:delegate` or a
   correlated tick.
3. The root workflow uses deterministic nodes for known policy and a recursive
   `:repl-researcher` only where the next action genuinely is not known.
4. Tool effects cross a configured `:tool-caller-fn` gate carrying the host's
   opaque turn/tool context.
5. Complex actions are emitted as child trees or delegated to versioned sheets;
   simple calls remain inline.
6. The host converts the durable result/trace into transcript observations and
   atomically checks steering/follow-up queues before declaring the session idle.

Do not model the entire Pi loop as a bare ORC `:sequence`, and do not equate
ORC's automatic `:running` re-tick with an agent turn. A fixed sequence cannot
express the model-selected number of action/observation rounds, while re-tick
is scheduler control without transcript or tool-result semantics.

## Concrete gaps before ORC could claim a first-class Pi-loop analogue

No engine change is required for a host to assemble a useful coding agent today.
A first-class reusable analogue would nevertheless need an explicit behavioral
contract for:

- canonical transcript ownership and conversion into model context;
- the safe boundary at which queued steering becomes visible;
- the atomic idle/follow-up decision;
- ordered tool-result representation, especially for parallel calls;
- turn-level stop, error, and abort semantics;
- provider-call cancellation rather than progression-only cancellation;
- replay rules distinguishing durable messages/effects from ephemeral deltas;
- the relationship between a host turn, a root tick, and descendant ticks.

Those belong primarily at the session/harness boundary. Adding them directly to
the generic tree scheduler would collapse the separation that currently lets ORC
remain a workflow library rather than an application or chat runtime.

## ORC evidence used

- [`docs/ORC-PRINCIPLES.md`](../ORC-PRINCIPLES.md) defines the behavior tree as
  the deterministic spine, reserves `:repl-researcher` for unknown topology,
  and makes `:delegate` the composition seam.
- [`docs/RLM-GUIDE.md`](../RLM-GUIDE.md) documents recursive researcher
  iterations, `tree-results`, follow-up actions, and explicit `final!`.
- [`components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj`](../../components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj)
  implements recursive Phase 1, persistent sandbox state, gated tool calls,
  emitted child trees, budgets, and finalization (`2705-3275`).
- [`components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj`](../../components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj)
  implements tick/node event progression, delegates, re-ticks, and processor
  registration (`1593-1665`, `2273-2406`, `3632-3727`, `4512-4559`).
- [`components/orc-service/src/ai/obney/orc/orc_service/core/runtime.clj`](../../components/orc-service/src/ai/obney/orc/orc_service/core/runtime.clj)
  dispatches `:sheet/tick-tree`, carries opaque tool context, waits for durable
  completion, and applies execution budgets (`540-640`).
- [`docs/STREAMING.md`](../STREAMING.md) defines the ephemeral/durable split,
  descendant-tick streaming, recovery, and current cancellation semantics.
- [`specs/orc-service.allium`](../../specs/orc-service.allium) is the behavioral
  source of truth for execution, tool gates, durability, delegation, streaming,
  cancellation, and researcher output contracts.
