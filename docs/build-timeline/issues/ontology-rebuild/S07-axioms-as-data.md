# S07 — Axioms-as-data events + OWL export

**Type:** AFK · **Phase:** 1 · **PRD module:** M2 (part 6) · **Stories:** 18 (partial)

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

Axioms become first-class DATA in the event vocabulary — stored,
projected, consumed by lints and traversal, exported as proper OWL —
with NO inference engine (the locked formality ceiling: axioms are data
+ lint inputs + traversal hints, never an embedded reasoner):

1. **Disjointness sets** — "these sibling classes are mutually disjoint"
2. **Property characteristics** — functional / transitive / symmetric /
   inverse-of pairings
3. **Property hierarchies** — sub-property-of
4. **Chain definitions** — P∘Q→R rules (stored now; query-time synthesis
   consumes them in a later NEXT-tail slice)

End-to-end: axiom commands emit events; the concepts projection carries
an axiom view; a predicate marked transitive is honored by BFS closure;
TTL export emits owl:disjointWith / owl:FunctionalProperty /
owl:TransitiveProperty / owl:inverseOf / rdfs:subPropertyOf /
owl:propertyChainAxiom correctly.

## Acceptance criteria

- [ ] Each axiom type: command → event → projection round-trips with
      Malli-validated schemas
- [ ] Transitive-marked predicates are followed in BFS closure exactly
      like broader/narrower today; non-marked predicates are NOT
      (adversarial pair test)
- [ ] Inverse pairings traverse both directions without duplicate edges
- [ ] Export emits each axiom type as the correct OWL construct —
      verified against an external parse (load exported TTL into a
      standard RDF lib in a test and assert the axiom triples)
- [ ] Explicit NON-goal asserted in tests: no class-membership inference
      occurs from axioms (a fixture that WOULD reclassify under OWL
      domain/range semantics must remain unchanged — guarding the
      no-reasoner ceiling)
- [ ] Schema additions optional; existing fixtures regress clean

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
