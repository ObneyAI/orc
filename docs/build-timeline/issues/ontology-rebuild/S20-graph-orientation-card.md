# S20 — Graph orientation card (deterministic skeleton)

**Type:** AFK · **Phase:** 4 · **PRD module:** M9 · **Stories:** 20

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

The graph's equivalent of a large-document preview: an orientation card
injected into a repl-researcher's context whenever S19's ontology tools
are granted, so the model uses them intelligently instead of exploring
blind. Four layers, all generated deterministically from projections
(the optional LLM prose layer is a separate NEXT-tail slice):

1. **Identity** — the ORSD spec if present (purpose/scope/CQs — what
   this graph is FOR), ontology-level metadata, and the section +
   alignment-section registry (what the model's queries may auto-widen
   into)
2. **T-Box digest** — scopes, top classes with instance counts,
   predicates with usage counts + characteristics
   (transitive/functional/inverse pairs), axiom summary (disjoint sets,
   chain definitions) — from S07's axiom events + the concepts
   projection
3. **Content sample** — top-N concepts by degree/evidence-count with
   one-line descriptions + 2-3 representative neighborhoods
4. **Tool affordances** — the granted tools with worked one-liners
   against THIS graph's actual content

The card is cached and refreshed on reindex (riding the existing
reindex-trigger machinery); generation is cheap enough to never block a
session start.

## Acceptance criteria

- [ ] Card generated for a seeded graph contains all four layers with
      values verifiably matching the projections (counts cross-checked
      against statistics fns — adversarial: a stale or wrong count is
      the failure mode)
- [ ] Graph WITHOUT an ORSD spec: identity layer degrades gracefully
      (sections + metadata only), card remains useful
- [ ] Card refreshes after reindex: grow the graph, trigger reindex,
      assert the card reflects new counts/samples (and NOT before —
      cache behavior is part of the contract)
- [ ] Injection: a sandbox session granted ontology tools receives the
      card in context (integration test through the sandbox wiring)
- [ ] Self-containedness review: the card alone (no other context)
      orients a reader on what the graph is, what's in it, and how to
      query it — adversarially reviewed by reading the card cold
- [ ] Live verify: real recursive-RLM session where the captured
      transcript shows the model USING card information (e.g., choosing
      a predicate it learned from the digest) — quality of orientation,
      not just presence

## Blocked by

- S19 (tools + grant wiring), S07 (axiom digest), S14 (identity layer's
  ORSD read — soft: card works without a spec)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
