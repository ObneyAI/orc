# S01 — Per-source caps before RRF fusion

**Type:** AFK · **Phase:** 1 · **PRD module:** M10 · **Stories:** 32

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

Fused hybrid retrieval gains a per-signal contribution cap applied BEFORE
RRF fusion, so that one over-expanding signal (typically graph BFS) cannot
crowd the fused candidate pool and drown the other signals. The cap is a
query option with a sensible default; setting it has no effect on
single-signal queries. End-to-end behavior: a query where one signal
returns 10× the candidates of the others produces a fused result list in
which the other signals' top hits still surface.

## Acceptance criteria

- [ ] A per-signal cap option exists on hybrid search (single and batch),
      default applied, override-able per query
- [ ] With a deliberately over-expanding graph signal in a fixture, the
      top fused results still contain the embedding/ColBERT top hits
      (adversarial test: prove the failure occurs WITHOUT the cap, then
      that the cap fixes it)
- [ ] No behavior change for single-signal or already-balanced queries
      (regression assertions on existing fixtures)
- [ ] Live verify: one real hybrid-search run confirming caps applied in
      the wild, logged with explicit debug output of per-signal pool sizes

## Blocked by

None — can start immediately.

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
