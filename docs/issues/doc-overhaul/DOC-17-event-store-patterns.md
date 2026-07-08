## What to build

Restructure `docs/EVENT-STORE-PATTERNS.md` to be useful for ORC consumers (people building workflows with `sheet/execute`), not just Grain developers. Add ORC-centric query examples: reading traces for a specific tick, querying judge scores, querying node rolling metrics. Frame the Grain-internal patterns as "for contributors building new ORC features."

## Read first

1. `docs/EVENT-STORE-PATTERNS.md` — full file
2. `docs/GETTING-STARTED.md` — event-store queries shown there (avoid duplication)
3. `components/orc-service/src/ai/obney/orc/orc_service/interface.clj` — query functions
4. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

Yes — run each ORC-specific event query against orc-template: `(es/read event-store {:types #{:sheet/node-execution-completed} :tags #{[:sheet sheet-id]}})`, `(eval/get-judge-scores ...)`, `(orc/get-node-rolling-metrics ...)`. Capture output.

## TDD cycle

- **Red:** Doc is written for a Grain developer (bare `es/read` calls, framework internals). ORC consumer use cases (traces, judge scores, rolling metrics) are absent.
- **Green:** Add an ORC-consumer section at the top. Verify all queries run. Reframe existing Grain patterns as contribution-level reference.
- **Refactor:** Confirm `(into [] (es/read ...))` materialization rule is explained with a "why" (reducible collection, not realized).

## Acceptance criteria

- [ ] Opening: "If you're building ORC workflows, this section has the queries you'll actually use." followed by ORC-consumer section
- [ ] ORC-consumer section: traces by sheet/tick, node events, judge scores, rolling metrics — all with captured output
- [ ] `(into [] (es/read ...))` materialization rule explained: reducible collection must be realized before counting
- [ ] Tag-based filtering shown for `[:sheet sheet-id]` and `[:tick tick-id]`
- [ ] Existing Grain-internal patterns labeled as "For contributors building new ORC features" (not removed)
- [ ] All new query examples verified with captured REPL output

## Do NOT touch

Any component source. `docs/GETTING-STARTED.md`.

## Live QA the orchestrator runs

Run every new query example against orc-template. Confirm output matches doc. Confirm `(count (es/read ...))` fails (without `into []`) as the doc warns.

## Blocked by

Wave 1 complete.

## Handoff note

The existing doc's Grain patterns section is accurate for contributors. Do not delete it — label it clearly and put it after the consumer section.