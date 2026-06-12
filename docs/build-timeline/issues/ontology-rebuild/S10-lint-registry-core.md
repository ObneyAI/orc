# S10 — Lint registry + EDN-SHACL interpreter core

**Type:** AFK · **Phase:** 2 · **PRD module:** M4 · **Stories:** 13, 15

## Parent

`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`

## What to build

The unified validation layer's foundation: a lint registry whose shapes
are SHACL-shaped EDN (the locked b′ decision), an in-JVM interpreter for
the phase-1 subset, violation events, and the first axiom-independent
built-in lints — end-to-end from shape definition to a queryable
validation report.

Shape source-of-truth (decision-rich sketch from the grill):

```clojure
{:shape/type :node-shape
 :target-class <uri-or-class>
 :severity :violation            ; :info | :warning | :violation
 :message "..."
 :deactivated false
 :property [{:path <predicate>
             :min-count 1 :max-count 1
             :not {...}}]
 :code <predicate-fn>}           ; escape hatch for shapes standard
                                 ; SHACL cannot express
```

- Interpreter phase-1 subset: target-class resolution, property path,
  min/max-count, severity, message, deactivated, not. (Qualified value
  shapes and further components arrive in S11.)
- Violations emit as events (`lint-violation`-style) carrying shape id,
  severity, message, offending URIs — projected into a per-ontology
  validation-report read-model.
- First built-in lints (axiom-independent): dangling relationship
  endpoints, naming-convention consistency (`:code`), language tags on
  non-linguistic literals (`:code`).
- Malli validates the EDN shape format itself; deactivated shapes are
  skipped and reported as skipped.

## Acceptance criteria

- [ ] Shapes register, validate (Malli), interpret against a projected
      graph; violations evented with all fields
- [ ] Each phase-1 component has a positive AND negative fixture (a graph
      that violates, a graph that passes) — adversarial coverage, not
      just happy-path
- [ ] The three built-in lints fire correctly on seeded-bad fixtures and
      stay silent on clean ones
- [ ] `:deactivated true` suppresses a shape and the report says so
      (adversarial: a violating graph + deactivated shape = no violation
      event, one skip record)
- [ ] Validation report read-model: per-ontology current report +
      violation history queryable
- [ ] `:code` escape-hatch shapes run in the same registry flow with
      identical reporting

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
