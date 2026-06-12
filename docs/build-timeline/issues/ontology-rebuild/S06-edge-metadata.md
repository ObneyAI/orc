# S06 — Edge metadata: schema'd + serialized

**Type:** AFK · **Phase:** 1 · **PRD module:** M2 (part 5) · **Stories:** 17 (partial)

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

Relationship events gain named optional fields for the three edge-level
metadata classes — promoted from the existing open `:properties` bag,
which is currently NEVER exported (verified gap):

- `:confidence-class` — `:extracted | :inferred | :ambiguous`
  (provenance class of the assertion)
- `:evidence` — source quotes/statements that produced the edge
- `:valid-from` / `:valid-to` — temporal validity bounds + a
  supersession marker

End-to-end: a relationship written with metadata projects into the graph
carrying it, retrieval results expose it, and TTL export emits it
(RDF-star-style or reified-on-demand — our event-sourced edges carry
metadata natively; reification is an EXPORT concern only). The
`:properties` open bag remains for arbitrary extras and is now also
serialized.

## Acceptance criteria

- [ ] Relationship events accept and validate the named metadata fields;
      all optional; existing fixtures regress clean
- [ ] Projection exposes metadata on edges; retrieval results include it
- [ ] Export emits confidence-class/evidence/validity AND the residual
      `:properties` content — adversarial: a metadata-rich edge exported
      then inspected must show every field (the silent-drop failure mode
      is the thing under test)
- [ ] Ambiguous-class edges are queryable as a set (the future review
      queue's read path — read-model filter by confidence-class)
- [ ] Live verify: real extraction run producing at least one edge with
      evidence quotes, captured and inspected

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
