# S13 — Evidence Tier-1 (deterministic, always-on)

**Type:** AFK · **Phase:** 2 · **PRD module:** M6 · **Stories:** 5, 17

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

Deterministic evidence tracking maintained by the compare-to-existing
path (S12's cascade) for free — no LLM, no flags, always on:

- Re-encountering an existing concept in a new source IS evidence: bump
  `evidence-count`, append the source ref, stamp `last-reinforced-at`.
- New information that CONFLICTS with a stored field emits a
  contradiction marker — visible, queryable, never a silent overwrite.
- Works identically for graphs built from sources and graphs ingested
  from TTL (S09) — growth is growth.

This is substrate-level metadata every consumer gets, including
"ontology as a database" consumers with all self-improvement dormant.
(Tier-2 — LLM-driven freshness trends and refine-not-overwrite
definition evolution — is a separate NEXT-tail slice with its own budget
knobs; explicitly OUT of this slice.)

## Acceptance criteria

- [ ] Second-source re-encounter of a concept bumps evidence-count,
      appends the new source ref, updates last-reinforced-at — asserted
      through the public query surface, not internals
- [ ] Conflicting field value emits a contradiction marker carrying both
      values + both sources; the stored value is NOT silently replaced
      (adversarial: the overwrite path is the failure mode under test)
- [ ] Contradiction markers are queryable as a set per ontology (the
      review surface's read path)
- [ ] No evidence mutation occurs without a corresponding event (audit:
      replay the event stream → identical evidence state)
- [ ] Live verify: real two-source build over overlapping domain
      content; captured evidence/contradiction output adversarially
      reviewed for correctness (every bump justified by an actual
      re-encounter)

## Blocked by

- S12 (lives in the cascade's compare-to-existing path)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
