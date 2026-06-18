# RF1 — Structured `final!` finalization for the terminal `:repl-researcher` (tracer bullet)

**Type:** AFK · **Parent:** `docs/build-timeline/issues/rlm-finalization/README.md`

## What to build
Make the **legacy terminal path** of `execute-repl-researcher` (a `:repl-researcher`
node with NO `:rlm` config) finalize via a **structured `final!` affordance** —
exactly the way the `:rlm` path already does — instead of regex-scanning stdout for
a `FINAL_ANSWER: <text>` marker. The model, when satisfied, calls
`(final! {:<write-key> value …})`; the executor `validate-final!`s that against the
node's declared `:writes` and returns it as `:outputs` read **directly off the
captured structured value** — no marker, no `pr-str`, no regex, no syntax-trust.

Reuse the EXISTING machinery (`final!-fn` + `validate-final!` in `rlm_sandbox.clj`)
— do NOT fork or reinvent it. The terminal path should bind the same structured
finalizer into its SCI sandbox.

**Step 0 (decision gate):** grep for production callers that build a
`:repl-researcher` node WITHOUT `:rlm` (i.e. that rely on the marker path).
- If NONE → **retire** the `FINAL_ANSWER`-marker scraping in the terminal path
  (cleanest; one finalization model).
- If ANY → keep marker scraping as a **deprecated fallback** AFTER the `final!`
  check, and note the callers for later migration.

Also fix the 1 stale `code-executor-test` assertion (rides along — same
test-correctness theme).

## Acceptance criteria
- [ ] A `:repl-researcher` node (no `:rlm`) whose generated code calls `(final! {:answer "42"})` returns `:status :success` with `:outputs {:answer "42"}` read directly off the captured structured value (no marker scraping).
- [ ] `final!` in the terminal path validates against the node's `:writes`: extra keys rejected, missing required keys rejected, all-blank rejected — each a clean `:status :failure` with a descriptive error (reuse `validate-final!`).
- [ ] The 12 `repl-researcher-test` assertions are rewritten to drive `final!` (the model's code calls it) and pass — covering: immediate finalize, tool-call-then-finalize, usage accumulation across iterations ending in `final!`, no-`call-tool-fn`, and namespaced MCP tools then `final!`.
- [ ] Existing terminal-path semantics preserved: blank code → `:failure "did not generate code"`; max-iterations without `final!` → `:failure`; convergence detection intact.
- [ ] Step-0 caller grep performed + recorded; marker scraping retired (no callers) OR kept as a documented deprecated fallback (callers listed).
- [ ] `code-executor-test` stale string updated to the current executor message.
- [ ] Green under BOTH `clj -M:poly test brick:orc-service` (boundary + canonical) AND direct `:dev:test` of `repl-researcher-test` + `code-executor-test`. 0 failures, 0 errors, nothing skipped.
- [ ] No regression in the `:rlm` / recursive path (its `final!`/`validate-final!` behavior unchanged) — re-run its suites.

## Blocked by
None.

## TDD cycle (red → green, one at a time)
1. RED: terminal node, code `(final! {:answer "42"})` → expect `:success` + `:outputs {:answer "42"}`. GREEN: bind a `final!` (reusing `validate-final!`) into the terminal SCI sandbox; capture to an atom; return on atom set, reading `:outputs` from the validated map.
2. RED: `final!` with an extra key not in `:writes` → `:failure` naming the extra key. GREEN: route through `validate-final!`.
3. RED: `final!` missing a required `:writes` key / all-blank → `:failure`. GREEN.
4. RED: two iterations — first calls a tool (println), second `(final! {:answer "pi is 3.14"})` → `:success`, exactly one tool call. GREEN.
5. RED: usage accumulates across iterations that end in `final!` (3 iters) → `:success` + summed usage. GREEN.
6. RED: no `:call-tool-fn` in context + code calls `(final! {:answer "42"})` → `:success`. GREEN.
7. RED: namespaced MCP tools (2 servers) then `(final! …)` → `:success`, both tools called. GREEN.
8. Confirm preserved-semantics tests still green (blank-code, max-iterations, final-answer-via-execution if kept).
9. Step-0 caller grep → retire or deprecate marker path accordingly (+ a test for whichever path remains).
10. `code-executor-test` stale string → update to the current message; GREEN.

## Handoff focus
`docs/build-timeline/handoff-plan/2026-06-18-rlm-finalization-RF1-handoff.md` — read-first list, the exact integration point (terminal loop vs `final!-fn`/`validate-final!`), do-NOT-touch, live-QA, the step-0 gate.

## Cross-references
Unifies terminal finalization with the `:rlm` path's `final!` model (recursive-only direction). Root-cause dive: the 12 failures are long-stale (proven pre-merge), not a regression.

## Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation)

1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation for an LLM-node result. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue to its root with explicit debug text/logging; heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (REAL Grain event store, REAL LLM calls where the path exercises one) is mandatory before declaring done. No invented fixtures — outputs are captured from real runs. No false green — a passing fallback / degenerate / empty path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces (the node's `:status`/`:outputs` contract), never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; NO bare event-store appends; assert events LANDED by reading the projection back (not by trusting a return value); recursive-only RLM; no hardcoded phrase matching as quality gates.
8. Re-orchestration, not rewrite. Reuse the EXISTING `final!` / `validate-final!` / sandbox-binding machinery (`rlm_sandbox.clj`) — the terminal path adopts the same structured finalizer the `:rlm` path already uses. Do not duplicate or fork it.
9. Adversarial qualitative verdict. Judge the ACTUAL output produced — actively hunt for where it is WRONG. "It ran" is not a pass; surface honest negatives rather than masking them.
10. "Deterministic skeleton" ≠ LLM-free. The deterministic contract (`final!` validation, `:writes` capture) AND the LLM-driven behavior are BOTH verified.
11. Standing ops rules: the real OpenRouter key is a shell env var ONLY, never committed; never truncate model-authored output when capturing/comparing (verbatim); retrieval-facing descriptions are self-contained (no file paths / SHAs / slice names); HITL audit every changed/added file by path before any commit; branch = feature/ontology-architecture; one commit per slice; co-author `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`; JVM hygiene — bounded live runs (future + deref timeout + System/exit), kill orphans by PID, confirm 0 this-repo orphan JVMs after.
12. Domain/industry-agnostic. NO baked-in domain knowledge or hardcoded phrase matching in the executor change; behavior comes from the node's declared contract, not hardcoded strings.
13. Every `:llm` node writes `:reasoning` FIRST in its `:writes` (chain-of-thought before structured output). In CONCURRENT contexts (`:parallel`, `:map-each`) use a NODE-SCOPED reasoning key. (This slice changes the repl-researcher executor, not an `:llm` node; any `:llm` node touched/added must still obey this.)
