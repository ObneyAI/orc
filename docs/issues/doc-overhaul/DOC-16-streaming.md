## What to build

Restructure `docs/STREAMING.md` to lead with a 30-second orientation ("what is streaming for and when do you need it") before the envelope taxonomy. The current doc opens directly into the envelope and event table without framing what streaming is.

## Read first

1. `docs/STREAMING.md` — full file
2. `components/orc-service/src/ai/obney/orc/orc_service/interface.clj` — `execute-stream`, `subscribe-execution`, `cancel!`
3. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

Yes — run `execute-stream` on a simple 2-node workflow. Print the first 5 events from the channel. Confirm they match the doc's envelope shape. Capture output.

## TDD cycle

- **Red:** Doc opens directly into the envelope and event taxonomy. No "what is this for" context. RLM-specific events not clearly labeled as recursive-only.
- **Green:** Add 30-second orientation. Label RLM events as recursive-only. Verify quick-start example.
- **Refactor:** Confirm all event types in the table match what the implementation actually emits.

## Acceptance criteria

- [ ] 30-second opening: "Streaming is an ephemeral observation layer. The durable event-sourced model is unchanged — if nobody subscribes, the streaming machinery is a no-op."
- [ ] When to use it: real-time UI, progress reporting, live debugging — examples given
- [ ] Quick-start code example verified against orc-template — captured output embedded
- [ ] RLM-specific events (`:rlm-iteration-started`, `:rlm-code-generated`, `:rlm-sandbox-completed`, `:rlm-phase2-*`) labeled "recursive mode only"
- [ ] `cancel!` behavior accurately described: best-effort; in-flight LLM HTTP calls complete
- [ ] Loss model (sliding buffer, `:seq` monotonic, event-store reconciliation on gap) explained in plain terms
- [ ] Child tick cascade (subscribe covers root + all child ticks from delegate/RLM) explained

## Do NOT touch

Any component source.

## Live QA the orchestrator runs

Run the quick-start example. Confirm `:stream-closed` is the last event. Confirm `:seq` is monotonic. Confirm subscribing before dispatching catches all events.

## Blocked by

Wave 1 complete.

## Handoff note

The streaming doc is relatively short (256 lines) and structurally sound. This is a restructure-the-opening + add-labels pass, not a ground-up rewrite.