# S04 — Labels, datatypes, annotations: schema + projection + TTL export

**Type:** AFK · **Phase:** 1 · **PRD module:** M2 (parts 1, 2, 8) · **Stories:** 28, 31

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

Additive schema extensions on concept events + faithful export, covering
three representation needs end-to-end (command → event → projection →
TTL):

1. **Language-tagged multiple labels** — `:labels [{:value "Director"
   :lang "en"} {:value "Regisseur" :lang "de"}]`; same structure
   available for comments. The existing single `:label :string` stays
   valid (back-compat). Export emits each with its language tag — the
   current hardcoded `@en` is removed.
2. **Datatyped attribute values** — concept attributes may carry
   `{:value 148 :datatype :xsd/integer}`; bare values remain legal;
   export emits `^^xsd:` types where present.
3. **Annotations** — `:comment` (rdfs:comment, distinct from
   `:description`/skos:definition), `:see-also`, `:is-defined-by`,
   `:model-guidance` (LLM-facing usage hints surfaced in retrieval
   payloads), and ontology-level metadata (title, version, license,
   creator) stored via an ontology-metadata event and emitted on the
   exported ontology header.

## Acceptance criteria

- [ ] Multi-language labels round through command → event → projection;
      export emits every label with its tag; NO hardcoded language
      remains in the serializer (adversarial grep + a de/en fixture)
- [ ] Single-label legacy events still project and export correctly
      (back-compat regression)
- [ ] Datatyped attributes export with correct xsd types; untyped values
      unchanged
- [ ] `:comment` and `:description` export as DISTINCT predicates
      (rdfs:comment vs skos:definition) — adversarial: a concept with
      both must show both, not one overwriting the other
- [ ] `:model-guidance` appears in retrieval result payloads for concepts
      that carry it
- [ ] Ontology-level metadata exports on the ontology header
- [ ] All schema additions are optional — every existing event fixture in
      the test suite still validates (Malli regression)

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
