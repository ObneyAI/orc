# Pi core loop on ORC

This demo is a small, runnable agent harness modeled on Pi's core agent loop,
with its supplied effectful tools crossing a real ORC workflow boundary. It is
meant for exploring the mechanics of a durable model/tool loop: accumulated
conversation context, streamed assistant messages, tool batches, steering,
follow-ups, cancellation, and the Grain events and ORC traces produced along
the way.

The behavioral authority is
[`packages/agent/src/agent-loop.ts` lines 95–780](https://github.com/badlogic/pi-mono/blob/c49906ec77788625aacbdc53ebca6fbe65bd20f5/packages/agent/src/agent-loop.ts#L95-L780)
at Pi commit `c49906ec77788625aacbdc53ebca6fbe65bd20f5`.
This is a transcription of that loop's observable behavior, not a reimagining
based only on Pi's user interface.

## What you can do

The fastest way to explore the demo is as a long-lived nREPL process. From any
nREPL client you can:

- create multiple isolated, named conversations;
- send repeated prompts while retaining each conversation's transcript;
- let a real model choose and invoke typed ORC workflow tools;
- inspect Pi lifecycle events, provider evidence, ORC tick IDs, and correlated
  Grain events after each prompt;
- queue steering or follow-up input from a second REPL connection;
- stop after the current turn or abort active ORC work; and
- define a new ORC workflow at the REPL and immediately expose it as a model
  tool.

The demo also includes deterministic conformance scenarios and bounded
real-provider evidence scenarios for repeatable verification.

## What this demo is not

This is development code, not a production agent service. In particular, it
does not provide authentication, durable chat-session storage, compaction,
prompt/skill discovery, a terminal UI, or Pi's filesystem and shell tools. The
nREPL server can evaluate arbitrary Clojure and therefore binds only to
`127.0.0.1`; do not expose its port through an untrusted tunnel or network.

Conversation state lives in memory. Stopping the process discards named
sessions, although ORC execution evidence remains observable for the lifetime
of the disposable Grain system.

## Prerequisites

- Run commands from the ORC repository root.
- Use the repository's supported JDK and Clojure CLI setup.
- Set `OPENROUTER_API_KEY` for real-model sessions and `--live` scenarios.
- Use CIDER, Calva, vim-fireplace, Conjure, or another nREPL client for the
  interactive workflow.

The real-model examples default to the pinned model
`google/gemini-3.6-flash` through OpenRouter. Starting nREPL does **not** contact
a provider; a provider request begins only when you prompt a live session.

## Five-minute REPL walkthrough

### 1. Start the harness

Choose a fixed port so your editor can reconnect predictably:

```bash
export OPENROUTER_API_KEY="..."

clojure -M:dev \
  -m ai.obney.orc.demos.pi-agent-loop.main --nrepl 7888
```

The process prints:

```text
Pi/ORC nREPL listening on 127.0.0.1:7888
Evaluate functions in ai.obney.orc.demos.pi-agent-loop.repl
```

Passing `0` instead of `7888` asks the operating system to choose an available
port; use the printed port when connecting.

### 2. Connect and create a session

Connect your nREPL client to `127.0.0.1:7888`, then evaluate:

```bash
# Optional built-in terminal client; editors can connect directly instead.
clojure -M:dev -m nrepl.cmdline \
  --connect --host 127.0.0.1 --port 7888
```

At the connected REPL:

```clojure
(require '[ai.obney.orc.demos.pi-agent-loop.repl :as pi])

(pi/server-info)
;; => {:running? true, :bind "127.0.0.1", :port 7888, :sessions []}

(pi/create-demo-session! :play)
;; => :play
```

`create-demo-session!` builds a typed ORC lookup workflow and presents it to a
real model as `lookup_secret`. Creating the session initializes provider
configuration but does not make a model request.

### 3. Hold a conversation

```clojure
(def first-turn
  (pi/prompt! :play
              "Use the available tool to retrieve the secret for customer 42."))

(select-keys first-turn [:status :new-messages])

(def second-turn
  (pi/prompt! :play
              "Was that value obtained from a tool? Answer briefly."))

(pi/history :play)
```

The first call normally contains four new transcript messages:

```text
user → assistant(tool call) → tool-result → assistant(final answer)
```

The second model request sees that entire accumulated transcript plus the new
user message. `prompt!` is synchronous: it returns after the model/tool loop
reaches a terminal boundary for that prompt.

### 4. Inspect what happened

```clojure
;; Exact result maps returned by every prompt/continuation
(pi/results :play)
(pi/last-result :play)

;; Pi lifecycle events
(pi/last-events :play)
(pi/event-history :play)

;; Provider, model, response ID, token usage, and finish reason
(pi/provider-evidence :play)

;; ORC tick events and correlation lineage for one or every result
(pi/durable-summary :play)
(pi/durable-history :play)
```

Useful fields in a prompt result include:

| Field | Meaning |
|---|---|
| `:status` | Terminal loop status such as `:completed`, `:error`, or `:aborted` |
| `:new-messages` | Messages added by this prompt only |
| `:events` | Ordered Pi lifecycle events with monotonic sequence numbers |

Tool-result messages additionally retain `:tool-call-id`, `:tool-name`, error
and termination flags, plus `:details` containing ORC status, outputs, duration,
trace ID, and correlation ID.

### 5. Close the session and server

```clojure
(pi/close-session! :play) ; discard this conversation
(pi/shutdown!)           ; stop nREPL and the disposable Grain/ORC system
```

`Ctrl-C` in the server terminal also runs the shutdown hook.

## Session and control API

All operations take an explicit session name; unknown names fail instead of
silently creating state. Reusing a name fails until the old session is closed.

| Function | Purpose |
|---|---|
| `(pi/running?)` | Report whether this process owns a running demo nREPL server |
| `(pi/start! port)` | Programmatically start the loopback server and Grain/ORC system; the CLI normally does this |
| `(pi/server-info)` | Show bind address, selected port, status, and session names |
| `(pi/context)` | Return the active tenant-scoped ORC context for building tools |
| `(pi/sessions)` | List active session names |
| `(pi/create-demo-session! name)` | Create a real-model session with the sample ORC lookup tool |
| `(pi/create-live-session! name options)` | Create a real-model session with caller-supplied tools and provider options |
| `(pi/create-session! name options)` | Create a low-level session with an explicit `:model-turn`, useful for deterministic experiments |
| `(pi/prompt! name text)` | Add a user message and synchronously run the loop |
| `(pi/continue! name)` | Continue from a valid non-assistant transcript boundary without adding a prompt |
| `(pi/history name)` | Return the accumulated transcript |
| `(pi/results name)` | Return all prompt and continuation result maps |
| `(pi/last-result name)` | Return the most recent result map |
| `(pi/last-events name)` | Return lifecycle events from the most recent result |
| `(pi/event-history name)` | Return lifecycle events accumulated across results |
| `(pi/provider-evidence name)` | Return all retained provider response evidence |
| `(pi/durable-summary name)` | Read ORC/Grain evidence associated with the latest result |
| `(pi/durable-history name)` | Read ORC/Grain evidence associated with every result |
| `(pi/steer! name text)` | Queue steering for the next safe post-tool-batch boundary |
| `(pi/follow-up! name text)` | Queue input consumed only when the loop would otherwise end |
| `(pi/stop-after-turn! name)` | End after the current model turn |
| `(pi/abort! name)` | Mark the loop aborted and cancel tracked active ORC ticks |
| `(pi/close-session! name)` | Abort if necessary and remove the named session |
| `(pi/shutdown!)` | Stop nREPL and release every session and Grain/ORC resource |

### Steering a running prompt

Because `prompt!` blocks its nREPL evaluation until the run completes, use a
second cloned nREPL session or connection for controls:

```clojure
;; Connection A
(pi/prompt! :play "Perform the requested tool work and then summarize it.")

;; Connection B, while A is running
(pi/steer! :play "In the summary, lead with the ORC trace evidence.")
```

Steering never splits an active tool batch. It is appended after the complete
batch and before the next model request, matching the pinned Pi loop. A session
rejects overlapping `prompt!`/`continue!` runs.

`abort!` stops loop progression and cancels active ORC ticks. It cannot
currently interrupt an HTTP request already dispatched to the model provider;
that request may still finish and consume tokens before the loop observes the
abort.

## Bring your own ORC tool

The following can be evaluated directly in the connected REPL. It defines a
deterministic ORC workflow, turns it into a Pi-shaped model tool, and starts a
new live conversation around it.

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc]
         '[ai.obney.orc.demos.pi-agent-loop.tools :as pi-tools])

(defn lookup-order [{:keys [inputs]}]
  {:answer (str "Order " (:order-id inputs) " is ready")})

(def order-sheet-id
  (orc/build-workflow!
   (pi/context)
   (orc/workflow "pi-repl-order-lookup"
     (orc/blackboard {:order-id :string :answer :string})
     (orc/code "lookup-order"
               :fn (str (ns-name *ns*) "/lookup-order")
               :reads [:order-id]
               :writes [:answer]))))

(def order-correlation-id (random-uuid))

(def order-tool
  (assoc
   (pi-tools/orc-workflow-tool
    {:name "lookup_order"
     :description "Look up the authoritative status of an order."
     :context (pi/context)
     :sheet-id order-sheet-id
     :correlation-id order-correlation-id
     :input-fn #(select-keys % [:order-id])
     :content-fn #(get-in % [:outputs :answer])})
   :parameters
   {:type "object"
    :properties {:order-id {:type "string"}}
    :required ["order-id"]
    :additionalProperties false}))

(pi/create-live-session!
 :orders
 {:tools {"lookup_order" order-tool}
  :system-prompt
  "Use lookup_order for order-status questions. Never invent order status."})

(pi/prompt! :orders "What is the status of order A-104?")
```

The JSON-schema-shaped `:parameters` map is what the provider sees. The ORC
adapter maps validated arguments into workflow inputs and maps successful ORC
outputs into transcript content. Every execution gets a fresh tick ID while
the supplied correlation ID groups related executions.

`create-live-session!` accepts these options:

| Option | Default | Purpose |
|---|---|---|
| `:tools` | none | Map from provider-visible tool name to Pi tool definition |
| `:provider` | `:openrouter` | LiteLLM provider key |
| `:model-name` | `google/gemini-3.6-flash` | Provider model identifier |
| `:system-prompt` | tool-use safety prompt | Instruction prepended to provider messages |
| `:request-options` | `{}` | Additional options merged into each provider request |

For deterministic experiments, use `create-session!` with a function of
`[messages turn-config]` as `:model-turn`. Deterministic model functions are
appropriate for testing loop mechanics; they are never accepted as evidence of
real model selection, recovery, or adaptation.

## Three ways to run the demo

### Interactive nREPL harness

```bash
clojure -M:dev \
  -m ai.obney.orc.demos.pi-agent-loop.main --nrepl 7888
```

This starts the server and waits until shutdown. It runs no scenario and makes
no provider request by itself.

### Deterministic scenario

```bash
clojure -M:dev -m ai.obney.orc.demos.pi-agent-loop.main
```

This uses scripted model turns to verify loop structure while executing the
tool through a real ORC workflow. It requires no API key and prints the
transcript beside ORC lineage.

### Bounded live evidence scenarios

```bash
OPENROUTER_API_KEY=... \
clojure -M:dev -m ai.obney.orc.demos.pi-agent-loop.main --live
```

This runs the deterministic scenario plus five real-provider scenarios:

1. model-selected tool use grounded in an ORC-only nonce;
2. recovery from an attributable ORC tool failure;
3. adaptation to steering delivered after a held tool batch;
4. a selected parent ORC workflow delegating to a child sheet; and
5. provider-native streamed tool-call reconstruction and exact execution.

The live run fails instead of falling back to scripted responses when required
selection, grounding, recovery, steering, delegation, streaming, identity,
usage, or durable evidence is absent.

## Architecture

```text
nREPL client
    │ prompt / steer / inspect
    ▼
named stateful harness
    │ accumulated transcript
    ▼
Pi-shaped core loop ───────────────► model provider
    │ assistant tool calls                │ streamed response/evidence
    ▼                                     │
Pi tool adapter ◄─────────────────────────┘
    │ typed inputs, tick + correlation IDs
    ▼
ORC workflow / delegated child tree
    │ commands and events
    ▼
Grain event store ──► projections, trace, replay, lineage
```

The loop is intentionally a small orchestration layer. It uses ORC's public
workflow boundary rather than defining a parallel command or projection model.
The disposable runtime uses a tenant-scoped in-memory Grain v3 event store,
pub/sub, a tenant processor, and an LMDB projection cache in a temporary
directory.

## Pi behavior covered

The implementation covers the core behaviors exercised by the pinned Pi loop:

- transcript-driven start and continuation;
- transform-before-convert model context preparation;
- message and turn lifecycle ordering;
- streamed assistant text and fragmented tool-call reconstruction;
- argument preparation, validation, before/after hooks, and blocking;
- parallel completion order versus source-ordered transcript results;
- global and per-tool sequential execution;
- partial tool updates;
- missing, failed, blocked, and length-truncated tool calls;
- unanimous tool termination;
- steering, follow-ups, next-turn snapshots, graceful stop, and abort; and
- no later ORC work after cancellation.

This does not claim conformance with Pi features outside the referenced loop,
such as its complete application/session layer or bundled tool collection.

## Verification

Run the focused deterministic and nREPL integration suite:

```bash
clojure -M:dev:test -e \
"(require 'ai.obney.orc.demos.pi-agent-loop.core-test
          'ai.obney.orc.demos.pi-agent-loop.repl-test)
 (let [r (clojure.test/run-tests
          'ai.obney.orc.demos.pi-agent-loop.core-test
          'ai.obney.orc.demos.pi-agent-loop.repl-test)]
   (shutdown-agents)
   (when (pos? (+ (:fail r) (:error r)))
     (System/exit 1)))"
```

The current suite contains 33 tests and 135 assertions. It covers the pure loop
and real ORC/Grain integration, including a real loopback nREPL socket. The
live-model obligations remain separate and require the `--live` command above;
they are never satisfied by deterministic model doubles.

Validate the behavioral specification:

```bash
allium check specs/pi-agent-loop-demo.allium
allium analyse specs/pi-agent-loop-demo.allium
```

## Source map

| File | Responsibility |
|---|---|
| [`core.clj`](core.clj) | Pi-compatible transcript, lifecycle, and tool-batch loop |
| [`runtime.clj`](runtime.clj) | Stateful prompts, continuation, queues, overlap rejection, stop, and abort |
| [`repl.clj`](repl.clj) | Loopback nREPL lifecycle and named session API |
| [`model.clj`](model.clj) | OpenRouter model calls, provenance, streaming, and tool-call reconstruction |
| [`tools.clj`](tools.clj) | Adapter from Pi-shaped tools to public ORC workflow execution |
| [`system.clj`](system.clj) | Disposable tenant-scoped Grain and ORC runtime |
| [`events.clj`](events.clj) | Tick/correlation event reads and durable summaries |
| [`scenarios.clj`](scenarios.clj) | Deterministic and mandatory live evidence scenarios |
| [`main.clj`](main.clj) | Scenario runner and nREPL entry point |
| [`core_test.clj`](core_test.clj) | Pi lifecycle and ORC integration conformance tests |
| [`repl_test.clj`](repl_test.clj) | Network, session isolation, controls, and shutdown tests |

Deeper design and evidence:

- [Pi source analysis](../../../../../../../docs/research/pi-agent-harness-core-loop.md)
- [Behavioral design](../../../../../../../docs/design/pi-agent-loop-demo.md)
- [Allium specification](../../../../../../../specs/pi-agent-loop-demo.allium)
- [Deterministic E2E evidence ledger](../../../../../../../docs/DETERMINISTIC-E2E-TEST-CHECKLIST.md)

## Troubleshooting

### `OPENROUTER_API_KEY` is missing

The server can still start and deterministic sessions still work. A
`create-demo-session!` or other live session fails when prompted. Export the key
in the shell that launches the nREPL process, then restart it.

### The port is already in use

Choose another fixed port or pass `0` and connect to the port printed at
startup.

### `Pi harness session already exists`

Session names are unique. Inspect `(pi/sessions)`, close the existing session,
or choose another name.

### `Unknown Pi harness session`

The name was never created, was closed, or belonged to a previous server
process. Sessions are deliberately not created implicitly.

### `Harness is already running`

Only one prompt or continuation may execute on a named session at a time. Use a
second nREPL connection only for steering, follow-up, stop, abort, or inspection;
do not submit a second prompt until the first returns.

### The server stopped but a provider request consumed tokens

The harness prevents further turns and cancels tracked ORC ticks, but the
current provider transport does not interrupt an already-dispatched HTTP
request. This limitation is surfaced explicitly rather than hidden behind a
successful cancellation claim.
