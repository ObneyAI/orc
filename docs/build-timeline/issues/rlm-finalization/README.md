# RLM Finalization — structured `final!` for the terminal repl-researcher (RF)

Local issue slices for hardening how the `:repl-researcher` node returns its answer.

## Why

The `:repl-researcher` executor has TWO finalization paths:

- **`:rlm` path** (recursive + `:rlm`-configured nodes; what EB2 Survey and all
  recursive workflows use) — the model calls **`(final! {…})`**, which
  `validate-final!`s the output against the node's declared `:writes` (rejects
  extra/missing/all-blank keys) and captures the **structured map** directly into
  an atom. Read straight off the writes — no string parsing, no syntax-trust.
- **Legacy terminal path** (a `:repl-researcher` node with NO `:rlm`) — finalizes
  ONLY by regex-scanning stdout/result for a `FINAL_ANSWER: <text>` marker. The
  model must emit a magic string with exact syntax; a bare/prose answer (or any
  syntax slip) is executed as code, errors, is never detected, and the loop spins
  to `:failure`.

The brittle marker path is the root cause of 12 long-stale `repl-researcher-test`
failures (proven failing identically pre- and post-merge — never a regression,
just never run by the slice sweeps). The fix is to make the terminal path finalize
the SAME structured way the rest of ORC already works: via `final!` → validated
`:writes` → read directly. This removes a whole class of syntax-trust brittleness
and unifies finalization on one model (aligned with the recursive-only direction).

## Slices

| # | Slice | Type | Blocked by |
|---|-------|------|-----------|
| RF1 | Structured `final!` finalization for the terminal `:repl-researcher` path (+ rewrite the 12 stale `repl-researcher-test` + fix the 1 stale `code-executor-test` string) | AFK | — |
| RF2 | `seed-descriptions` test-classpath gap — make the 5 ontology tests runnable under `clj -M:poly test` | AFK · deferred | — |
| RF3 | `build-atomicity-test` sqlite brick-dep gap — add `grain-event-store-sqlite-v3` to `orc-service/deps.edn` test scope so the brick gate exits 0 | AFK · **DONE** (`afac14dd`) | — |
| RF4 | de-flake `reindex-processor-fires-at-threshold` — root-caused to a **real production concurrency race** (multi-fire); fixed with a per-tenant lock + deterministic test wait | AFK · **DONE** (`56cb54b8`) | RF2 |
| RF5 | separate live/slow integration tests (s17 live-ColBERT, c2a 9-min) from the ontology fast gate — **needs a repo-owner decision** | AFK · open | RF2,RF4 |

**RF2 + RF3 are the same class** — "brick test-classpath gaps": a test depends on
something present only on the root/`:dev` classpath, so `clj -M:poly test` can't
load it (it then either skips silently or reds the whole gate). **RF4** is the
next layer the same way: each fix un-masks the next previously-unreachable failure
(RF1 → build-atomicity/RF3 → seed-load/RF2 → reindex-flaky/RF4). All were surfaced
by making the gate actually run. They are deferred + tracked so they aren't lost.

## Posture
- Each slice: `/handoff` → `/tdd` (red→green tracer bullets) → the binding
  **after-each `/inspect-orc` protocol** (re-run under BOTH `clj -M:poly test` AND
  direct `:dev:test`; re-read; try to break; root-cause; JVM hygiene; faithful
  report) before it's trusted or committed.
- Every slice carries the SAME binding **Core Disciplines** block (1–13).
- Dispatch detail for RF1: `docs/build-timeline/handoff-plan/2026-06-18-rlm-finalization-RF1-handoff.md`.

## Grounding
Root-cause dive (this conversation): the terminal path's marker-scraping vs the
`:rlm` path's `final!`; the 12 failures proven long-stale by a pre-merge run; the
`final!`/`validate-final!` machinery in `rlm_sandbox.clj`. See also the memory
`feedback_poly_test_vs_dev_test_false_green` (why `:dev:test`-only green hid these).
