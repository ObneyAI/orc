# Pi core-loop demo design

This demo reproduces the observable core-loop behavior of Pi commit
`c49906ec77788625aacbdc53ebca6fbe65bd20f5` using ORC primitives. Pi's source,
not this document's paraphrase, is the upstream conformance authority:

- [`runAgentLoop` / `runAgentLoopContinue`](https://github.com/badlogic/pi-mono/blob/c49906ec77788625aacbdc53ebca6fbe65bd20f5/packages/agent/src/agent-loop.ts#L95-L143)
- [`runLoop`](https://github.com/badlogic/pi-mono/blob/c49906ec77788625aacbdc53ebca6fbe65bd20f5/packages/agent/src/agent-loop.ts#L155-L275)
- [`streamAssistantResponse`](https://github.com/badlogic/pi-mono/blob/c49906ec77788625aacbdc53ebca6fbe65bd20f5/packages/agent/src/agent-loop.ts#L281-L372)
- [tool batching and execution](https://github.com/badlogic/pi-mono/blob/c49906ec77788625aacbdc53ebca6fbe65bd20f5/packages/agent/src/agent-loop.ts#L411-L780)

The behavioral contract is
[`specs/pi-agent-loop-demo.allium`](../../specs/pi-agent-loop-demo.allium).

## Boundary

The demo owns one in-process harness run: accumulated messages, model turns,
tool batches, steering/follow-up queues, lifecycle events, stop and abort. It
does not reproduce Pi's broader session repository, UI, skills, compaction,
filesystem tools or coding-agent policy.

Tools are versioned ORC workflows. The harness is the session-shaped host that
Pi's loop requires and `orc-service` intentionally does not provide.

## Proposed location

```text
development/src/ai/obney/orc/demos/pi_agent_loop/
  core.clj             pure loop transition decisions
  model.clj            model-stream protocol and live ORC LLM adapter
  tools.clj            Pi-shaped tool registry backed by ORC workflows
  events.clj           lifecycle event construction and subscription
  runtime.clj          run/continue, queues, cancellation, orchestration
  repl.clj             local nREPL lifecycle and named harness sessions
  scenarios.clj        deterministic and live scenario definitions
  main.clj             runnable demonstration entry point
  core_test.clj        Pi/ORC deterministic conformance tests
  repl_test.clj        nREPL and named-session integration tests
```

Tests belong under the matching `components/orc-service/test` tree if the
primitive becomes production behavior. While it remains a development demo,
conformance tests live beside a small demo test namespace under
`development/src`; complete integration obligations still use the public ORC
boundary and are recorded in the deterministic checklist.

## Runtime shape

```text
nREPL client -> named harness session
  -> prompt / inspect / steer / follow-up / stop / abort

prompt / continue
  -> append input messages
  -> model stream over transformed accumulated transcript
  -> append assistant message
  -> no tool calls? -------------------------------> turn end
  -> tool calls
       -> validate and gate in source order
       -> execute ORC workflows sequentially or concurrently
       -> emit completion events as calls finish
       -> append tool-result messages in source order
  -> turn end
  -> stop requested? ------------------------------> agent end
  -> inject queued steering before next model call
  -> non-terminating tool batch? ------------------> next turn
  -> otherwise poll follow-ups
       -> present ---------------------------------> next turn
       -> absent ----------------------------------> agent end
```

Every tool workflow receives the harness run's correlation identifier and a
parent/turn context. Its tick, descendant ticks, execution values and trace are
therefore inspectable as part of the same operation family.

The operator mode binds nREPL only to loopback. Starting it creates the
disposable Grain/ORC runtime but neither a conversation nor a provider request.
Named sessions are created explicitly, retain independent transcript and result
histories, and are discarded explicitly or during server shutdown.

## Evidence boundary

### Deterministic conformance

A scripted model stream is allowed only as a controlled input to the loop state
machine. It proves ordering and control behavior, never model capability.

Deterministic tests cover:

- exact lifecycle sequences for prompt, continuation and tool turns;
- transform-before-convert-before-model ordering;
- validation, missing tools, pre-call blocks and post-call transforms;
- parallel completion events versus source-ordered result messages;
- per-tool sequential override;
- length-truncated calls failing without execution;
- steering after the complete current tool batch;
- follow-ups before agent end;
- next-turn configuration snapshots and graceful stop;
- unanimous versus mixed tool termination;
- abort and no-new-work-after-cancel;
- real Grain command/event processing, projections, trace, replay and lineage.

### Mandatory live-model evidence

No scripted, captured or replayed model output can satisfy these scenarios:

1. **Tool selection and grounding.** The prompt omits information held by a
   tool. A pinned real model must call it with valid arguments, receive the
   result on a later turn, and state a result-only nonce in its final answer.
2. **Tool-error recovery.** The first real tool call returns an attributable
   error. The model must revise its next action and succeed via a permitted
   alternative, rather than receiving a prewritten recovery sequence.
3. **Steering adaptation.** Steering is queued while a gated tool is held by a
   deterministic latch. After release, the next real model request contains the
   steering message and the model's subsequent action observably changes.
4. **Structured ORC work.** When the demo claims the model can select or design
   structured execution, a real model must emit/delegate the subtree and use a
   child output in a later turn.
5. **Real transport streaming.** At least one run must observe provider-native
   streamed text/tool-call fragments and prove final reconstruction, exact tool
   invocation and durable provider/model/usage provenance.

Each live run is bounded, uses a pinned model, shuts down processors and stores,
and fails if it lacks model identity, token usage, exact tool invocations,
durable ORC outputs, transcript evidence, or terminal tick evidence.

## Intentional differences from Pi

- Tool effects cross typed, versioned ORC workflow boundaries rather than
  calling arbitrary functions directly.
- ORC persists effect and execution facts; ephemeral message deltas remain a
  live feed and are not treated as durable truth.
- Cancellation is honest about the current provider limitation: progression
  stops, but an in-flight provider HTTP request is not interrupted.
- Complex tool work may use `:delegate` or a generated child tree. The harness
  still presents its normalized outcome to the transcript as one tool result.

These differences strengthen durability and composition without changing the
Pi-visible turn, message and tool-batch contract.

## Delivery slices

1. Pure loop reducer and event sequences, checked against pinned Pi tests.
2. ORC-backed deterministic tools with correlation, trace and replay checks.
3. Steering, follow-up, stop and cancellation boundaries.
4. Live tool-selection/grounding and tool-error recovery.
5. Live steering and structured-subtree scenarios.
6. Runnable `main` that prints the transcript beside the ORC execution lineage.
7. Loopback nREPL mode with named, stateful live harness sessions and explicit
   resource lifecycle.
