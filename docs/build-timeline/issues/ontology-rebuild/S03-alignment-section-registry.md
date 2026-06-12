# S03 — Alignment-section registry + auto-widening queries

**Type:** AFK · **Phase:** 1 · **PRD module:** M1 · **Stories:** 11, 12

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

A registry (event-sourced: command → event → read-model) recording which
alignment sections serve which primary ontology sections. Queries against
a primary section auto-widen through the registry — all three retrieval
signals include the registered alignment section(s) — so consumers
benefit from cross-ontology equivalence links without knowing alignment
IDs. Auto-widening is on by default and can be disabled per query.
End-to-end behavior: register an alignment section linking concepts in
two primary sections (hand-authored relationship events suffice — the
dedicated equivalence event type arrives in S08); a query against one
primary section surfaces the linked concept from the other, with fusion
intact, without the caller naming the alignment section.

## Acceptance criteria

- [ ] Register/deregister commands emit events; the registry read-model
      projects current registrations per primary section
- [ ] A scoped query against a primary section with a registered
      alignment auto-widens and returns the cross-section linked concept
      (assert fusion fields present per S02's invariant)
- [ ] Disabling auto-widen per query restores strict single-section
      behavior (adversarial: verify NO alignment leakage when disabled)
- [ ] Unregistered sections are never auto-included (isolation regression
      from S02 still passes with the registry active)
- [ ] Alignment sections are independently droppable: deregistering
      removes their influence on subsequent queries with no residue
- [ ] Live verify: real two-section corpus + alignment section, logged
      widened-id sets per query

## Blocked by

- S02 (uniform scoping is the substrate this widens)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
