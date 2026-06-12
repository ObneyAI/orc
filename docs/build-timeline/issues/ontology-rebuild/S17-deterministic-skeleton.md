# S17 — Builder deterministic skeleton

**Type:** AFK · **Phase:** 3 · **PRD module:** M8 · **Stories:** 1, 4, 8

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

The hand-authored half of the hybrid builder — the pipeline stages that
own SUBSTRATE CONTRACTS and therefore are never delegated to model
discretion:

1. **Source parsing/normalization** with content-hashing — unchanged
   sources are skipped on grow cycles (hash recorded; re-supplying an
   identical source is a no-op with an explicit skip report)
2. **Dedup cascade integration** (S12) — every candidate concept/
   relationship flows through compare-to-existing + check-before-mint
3. **Validation pass** (S10/S11) — the lint registry runs over the
   build's output graph; the build report carries the validation report
4. **Event emission** — all writes through commands with schema'd
   events; nothing bypasses
5. **Embedding + ColBERT indexing** — extracted content embeds and
   indexes exactly as today's pipeline does
6. **Shared code library** — the per-format duplicated transforms
   (T-Box/A-Box assembly, serialization helpers, dedup utilities)
   consolidate into shared functions the skeleton calls

The skeleton exposes a discovery-phase SEAM: discovery is invoked as a
pluggable stage (the old sheets' discovery logic plugs in NOW as the
default; S18's recursive-RLM discovery plugs into the same seam later).
This keeps the old path fully functional as the regression baseline
while the skeleton ships.

## Acceptance criteria

- [ ] End-to-end build through the skeleton with the existing discovery
      logic produces a graph scoring ≥ the old-sheet baseline on the S16
      bench (no regression from re-plumbing — verified on the real
      bench, not assumed)
- [ ] Grow cycle with one changed + one unchanged source: unchanged
      source skipped (hash hit, explicit log), changed source
      re-extracted, dedup correctly merges against the existing graph
- [ ] Validation report attached to every build result; a seeded-bad
      source yields the expected lint violations in the report
- [ ] Event-discipline audit: replaying the build's event stream
      reproduces the projection byte-for-byte; zero writes outside
      commands (instrumented assertion)
- [ ] Shared library consolidation: the duplicated per-format transforms
      are gone (one implementation, all formats call it) with behavior
      verified equivalent via the bench
- [ ] Live verify: full real build (live LLM where the legacy discovery
      uses it, real embedding + ColBERT) captured end-to-end

## Blocked by

- S12 (cascade), S10 (validation core). S16 should be available for the
  no-regression check.

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
