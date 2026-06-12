# S09 — TTL ingestion adapter + G1 round-trip gate

**Type:** AFK · **Phase:** 1 (gate) · **PRD module:** M3 · **Stories:** 6, 7, 8, 27

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

TTL ingestion as a first-class write adapter, plus the executable gate
that pins the entire representation bundle. The events-first invariant
(user-locked, round 3): *"when we are building the graph we do so with
event schemas which then can be projected and cached as a usable graph
or exported to ttl or if someone comes in with a ttl we should be able
to take it apart into the format grain/orc needs and could reproject
that same ttl from the ingestion events the same way as exporting a ttl
built from disparate data sources events."*

- Ingestion parses a TTL file and DECOMPOSES it into the standard event
  vocabulary — concepts (labels/datatypes/annotations per S04,
  quantities/sequences per S05), relationships (+ metadata per S06),
  axioms (S07), equivalences (S08) — via commands, never bare appends.
- Ingested events flow through the same projection / embedding /
  indexing as any other write; the ingested graph is immediately
  queryable and growable.
- **G1 gate:** `ingest(ttl) → events → export ≍ source` — semantic
  triple-set equivalence (serialization ordering varies; compare
  canonicalized triple sets). This defines the brownfield onboarding
  path precisely.

## Acceptance criteria

- [ ] A fixture TTL exercising EVERY bundle feature (multilingual
      labels, datatyped literals, quantities, an ordered sequence, edge
      metadata, every axiom type, all three equivalence kinds,
      annotations incl. ontology header metadata) ingests with zero
      dropped triples — the ingestion report enumerates produced events
      per feature with explicit debug logging
- [ ] **G1:** re-export of the ingested fixture is triple-set-equivalent
      to the source (canonicalized comparison implemented in the test
      harness; the diff on failure prints the exact missing/extra
      triples — root-cause-ready output, not a boolean)
- [ ] The same G1 harness run against a graph built from a non-TTL
      source (events → export → ingest → export) is a fixed point —
      proving symmetry, not just one direction
- [ ] Ingested concepts are retrievable via scoped hybrid-search and
      contribute to dedup/compare-to-existing in later builds
- [ ] Malformed TTL fails loudly with position-bearing errors — never a
      partial silent ingest (adversarial fixtures: truncated file,
      undefined prefix, datatype garbage)
- [ ] Live verify: ingest a real-world TTL (e.g., a published ontology
      excerpt), inspect events, re-export, run G1

## Blocked by

- S04, S05, S06, S07, S08 (the bundle this gate pins)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
