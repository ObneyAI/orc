# S05 — Quantity+unit values + ordered sequences

**Type:** AFK · **Phase:** 1 · **PRD module:** M2 (parts 3, 4) · **Stories:** 3, 30

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

Two representation needs, end-to-end (schema → projection → export):

1. **Quantity + unit structured values** (QUDT-shaped) — numeric
   attributes with units carry `{:value 75 :unit "kg"}` instead of a
   bare number with implicit units. Export renders the QUDT-style
   pattern. Bare numerics remain legal where genuinely unitless.
2. **Ordered sequences** — the graph-native convention for
   order-dependent knowledge: a direct `immediately-follows` predicate
   plus a `follows` predicate marked transitive (consumed by traversal —
   transitive predicates are followed in BFS closure per the round-3
   derived-edge decision). NO RDF list vocabulary (rdf:first/rest
   blank-node chains are explicitly rejected). End-to-end: a sequence of
   concepts (steps) written with the convention is traversable in order,
   and "X follows Y" queries answer across multiple hops.

## Acceptance criteria

- [ ] Quantity values round through events → projection → export with
      value and unit both preserved (adversarial: two attributes with the
      same number but different units must remain distinguishable)
- [ ] Sequence convention: immediately-follows chain of ≥4 concepts is
      traversable in order via neighborhood expansion; multi-hop
      "follows" reachability works through the transitive marker
- [ ] Order survives export and is structurally evident in the TTL
- [ ] Schema additions optional; all existing fixtures still validate
- [ ] Live verify: a real source containing both quantities-with-units
      and an ordered structure produces correct events (captured from a
      real run, not hand-invented)

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
