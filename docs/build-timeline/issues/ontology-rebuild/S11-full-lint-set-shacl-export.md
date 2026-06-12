# S11 — Full built-in lint set + SHACL export + consumer-authored shapes

**Type:** AFK · **Phase:** 2 · **PRD module:** M4 · **Stories:** 14, 18, 26

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

Complete the validation layer: the axiom-dependent and modeling-
discipline lints, the qualified-value-shape interpreter component, SHACL
TTL export of the registry, and proof that consumer-authored shapes are
first-class. Built-in lint set to complete (each traces to a
course-verified LLM-extraction failure mode):

- **Disjointness violation** — an individual typed under two disjoint
  classes (axioms from S07)
- **Missing-disjointness warning** — sibling class sets with no
  disjointness asserted (classification correctness depends on it)
- **Universal-without-existential** — vacuously-satisfiable restriction
  patterns
- **Closure-axiom absence** — complete enumerations in extracted
  structure without a closing assertion
- **Roles-vs-classes** (`:code`) — class whose members plausibly change
  membership over time modeled as a class rather than a role
- **Name-implied semantics** (`:code`) — class names whose surface form
  implies relationships not asserted in the model
- **Functional-property double values** — two values on a
  functional-marked property (flagged as extraction ERROR — never the
  OWA sameAs inference)
- **Single-parent discipline** — multi-parent assertions in the asserted
  hierarchy (warning; ontology-normalisation rule)

Export: every registry shape (built-in and consumer) emits as standard
SHACL TTL alongside the ontology's TTL; `:code` shapes export with a
description marking them ORC-extended (never silently dropped).
Consumer shapes: an externally-authored EDN shape registers and runs
identically to built-ins.

## Acceptance criteria

- [ ] Every lint above: positive + negative fixtures; severity levels per
      the PRD; messages human-actionable
- [ ] Qualified-value-shape component interpreted correctly (nested
      shape + qualified-min-count fixture)
- [ ] SHACL TTL export of the registry validates a violating graph
      identically under pySHACL/GraphDB semantics for the expressible
      subset — verified by loading exported shapes + graph into an
      external SHACL validator in a test and comparing verdicts
      (adversarial: a graph our interpreter flags must be flagged by the
      external validator too, for the standard-expressible shapes)
- [ ] A consumer-authored shape (business rule not in the built-in set)
      registers, fires, reports, and exports — no special-casing
- [ ] Functional-double fixture explicitly asserts the verdict is a lint
      VIOLATION and that no sameAs/merge occurred (guarding against OWA
      semantics leaking in)

## Blocked by

- S10 (registry + interpreter core)
- S07 (axioms the disjointness/characteristics lints consume)

---

**Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation):**
1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.
