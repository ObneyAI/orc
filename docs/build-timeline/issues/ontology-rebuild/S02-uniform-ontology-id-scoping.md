# S02 — Uniform ontology-id scoping across all signals + accessors

**Type:** AFK · **Phase:** 1 · **PRD module:** M1 · **Stories:** 10, 11

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

Ontology-id scoping becomes uniform across the entire query surface.
Today graph BFS traverses the merged concepts graph across ALL ontology
sections while the embedding and ColBERT signals filter by ontology-id —
producing (a) an isolation leak where one section's traversal surfaces
another section's URIs, and (b) silent ranking-fusion loss for
cross-section hits. After this slice:

- Graph BFS expansion is scoped to the query's ontology-id(s) by default,
  identically to the other two signals.
- Passing multiple ontology-ids widens ALL THREE signals together, so
  deliberate cross-section queries keep full RRF fusion.
- The remaining unscoped accessors (URI lookup, hierarchy
  narrower/broader accessors, statistics, profile finders) gain the same
  optional ontology-id / ontology-ids parameters, and public docstrings
  document the scoping parameters that already silently work.

## Acceptance criteria

- [ ] Adversarial leak test: two sections with overlapping URI
      neighborhoods; a single-section BFS query NEVER returns the other
      section's concepts (prove the leak exists before the fix, then that
      the fix closes it)
- [ ] Multi-section test: querying with both ids returns cross-section
      results WITH embedding/ColBERT ranks intact (assert fusion fields
      present, not nil — adversarial check on the silent-burial failure
      mode)
- [ ] Accessor fns accept and honor scoping params; unscoped calls behave
      as before within a single-section store (back-compat regression)
- [ ] URI-collision test: two sections each minting the same URI resolve
      to their own section's concept under scoped lookup
- [ ] Live verify: real corpus with two seeded sections, scoped +
      multi-section hybrid-search runs, per-signal candidate logging
      showing scope enforcement at each signal

## Blocked by

None — can start immediately. (Highest-priority correctness fix in the
initiative.)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
