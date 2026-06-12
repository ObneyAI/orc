# S14 — ORSD spec storage

**Type:** AFK · **Phase:** 2 · **PRD module:** M7 · **Stories:** 2

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

The ontology's requirements specification as a persistent, event-sourced
contract — mirroring the proven descriptions pattern (command →
ontology-id-tagged event → current+history projection), zero new
machinery. ORSD shape (from the grill):

```clojure
{:purpose "..." :scope "..."
 :intended-uses [...]
 :competency-questions ["..."]
 :natural-language-statements ["..."]
 :non-functional {...}}
```

- A `record-ontology-spec` command stores/updates the spec; history is
  append-only (every revision queryable).
- All fields optional — a spec can start as just CQs and grow.
- The build entry point accepts the ORSD map and records it; discovery
  context assembly (S18) and CQ evaluation (S15) read it from the
  projection — the spec stored WITH the ontology is the source of truth,
  not a per-call parameter.
- The current spec surfaces in the ontology-level metadata (S04) and
  later in the orientation card's identity layer (S20).

## Acceptance criteria

- [ ] Command → event → projection round-trip; current spec + full
      revision history queryable per ontology-id
- [ ] Spec revisions never destroy history (adversarial: record three
      revisions, assert all three retrievable in order)
- [ ] Malli schema validates the ORSD shape; unknown extra keys rejected
      loudly (contract discipline — no silent garbage in the contract)
- [ ] Build entry point records a provided spec; a build WITHOUT a spec
      proceeds unchanged (optionality regression)
- [ ] A second build (grow cycle) against an ontology with a stored spec
      can read it from the projection without re-passing it

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
