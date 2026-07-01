# Graph-grounded modeling + order-independent reformation — issue index

**PRD:** [`../../prd/graph-grounded-modeling-and-reformation.md`](../../prd/graph-grounded-modeling-and-reformation.md) · **Design:** [`../../grill-sessions/wide-stats-observation-modeling.md`](../../grill-sessions/wide-stats-observation-modeling.md) · **ADR:** [`../../../adr/0001-mention-log-rederived-view.md`](../../../adr/0001-mention-log-rederived-view.md)

Supersedes the GC-16 measure-explosion guardrail (reverted). Thin tracer-bullet slices, each live-verified, never big-bang. Local issue files only (never remote), per project policy.

## Slice table + /prototype + /handoff cadence

| Slice | What | Type | Blocked by | /prototype | /handoff timing |
|-------|------|------|-----------|-----------|-----------------|
| **GM-1** | Graph-context preview into the Model + grain/reify prompt (Layer A, standalone value) | HITL | — | **YES** — live-capture the model-spec; tune the context+prompt so the Model actually emits Observations (vs the variance we saw) BEFORE TDD | **now** (self-contained) |
| **GM-2** | `mention` event + re-derived role/identity VIEW (the CALM substrate) | HITL | — | **YES** — the mention→derive projection is novel + foundational; prototype the derivation + byte-parity BEFORE TDD | **now** (self-contained) |
| **GM-3** | Incremental re-derive of the affected region on source-land → order-independence | HITL | GM-2 | **YES** — the affected-region re-derive + demote-via-re-derivation is the convergence mechanism | **AFTER GM-2 lands + is inspected** — craft from GM-2's REAL mention/derive signatures (dependency rule) |
| **GM-4** | CQ-loop re-derive route (a role-dependent CQ triggers re-derivation) | AFK | GM-3 | light/none | **AFTER GM-3 lands + is inspected** — craft from GM-3's real re-derive API |
| **GM-5** | Non-mutating minting-rate / ontology-conformance measurement | AFK | — | none | **now** (independent; most useful once GM-1 lands to measure the delta) |

## The build loop per slice (global disciplines)

Each slice runs: **/handoff → /prototype (where flagged) → /tdd (tests FIRST, red→green) → /inspect-orc** (the orchestrator adversarially re-verifies on REAL data before commit). The 13 Core Disciplines are embedded VERBATIM in each issue's Disciplines section so a subagent can't skip them.

## Dependency rule (do NOT pre-write blocked handoffs)

GM-3 and GM-4 depend on an EARLIER slice's real produced API (GM-2's mention/derive projection; GM-3's re-derive step). Per the build-loop discipline: do NOT pre-write those handoffs. Craft each one AFTER its blocker lands and is `/inspect-orc`'d, from the real signatures — otherwise the handoff bakes in a guessed API that won't match.

## Sequencing recommendation

1. **GM-1** first — standalone value, de-risks the Model+context decision, likely fixes pseo when sources are entity-first (unblocks the head-to-head early).
2. **GM-2** — the mention-log substrate, byte-for-baseline parity on clean sources before anything depends on it (the ADR's reversibility mitigation).
3. **GM-3** — the order-independence acceptance (two source orders → same graph). Handoff crafted from GM-2's real API.
4. **GM-4** — CQ-driven re-derivation. Handoff from GM-3's real API.
5. **GM-5** — the honest minting-rate metric (parallelizable; best after GM-1).

## The two load-bearing LIVE gates (every slice re-checks the relevant one)

- **Clean-source regression gate:** 3-source (IPEDS/crosswalk/O\*NET) 2/2 in-memory stays at the GC-13 baseline (447 concepts / 338 rels / 0 dangling). Prior attempts inflated this — any inflation is a FAIL.
- **Order-independence acceptance (GM-3+):** `[pseo,…]` vs `[…,pseo]` source orders → the same final graph within LLM variance.
