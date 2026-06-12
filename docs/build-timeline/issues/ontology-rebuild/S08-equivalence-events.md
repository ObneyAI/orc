# S08 — Equivalence events with `:kind`

**Type:** AFK · **Phase:** 1 · **PRD module:** M2 (part 7) · **Stories:** 12

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

A dedicated equivalence event carrying a kind discriminator —
`:same-as | :equivalent-class | :equivalent-property` — tagged to an
alignment section (S03). The distinction is semantically load-bearing
(course-verified): individual-level identity (sameAs) merges property
assertions; class-level identity (equivalentClass) must NEVER be
expressed as sameAs (unintentional inheritance merging). End-to-end: an
equivalence command emits the event into an alignment section; the
projection links the URIs with the correct semantics; auto-widened
queries (S03) surface equivalents; export emits owl:sameAs /
owl:equivalentClass / owl:equivalentProperty respectively.

## Acceptance criteria

- [ ] Command + event + projection for all three kinds; kind is required
      (no default — forcing the assertion to be deliberate)
- [ ] Events tag to an alignment section; primary sections stay clean
      (adversarial: assert NO equivalence statements land in either
      primary section's event stream)
- [ ] Auto-widened query surfaces the equivalent concept from the other
      section with the equivalence visible in the result
- [ ] Export from the alignment section emits the correct OWL predicate
      per kind — adversarial: an equivalent-class pair must NOT export as
      sameAs (the inheritance-merge hazard is the failure mode under
      test)
- [ ] Existing fixtures regress clean

## Blocked by

- S03 (alignment-section registry — the tagging target)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
