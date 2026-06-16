# V18 — Referential integrity as an always-on structural invariant

**Type:** AFK · **Milestone:** M3 (builder hardening, gates V21 re-run) · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`
**Surfaced by:** V17 — 119/249 edges dangled (referenced concepts never minted) because `validate-stage` is skipped when no shapes are supplied.

## What to build

A general, domain-agnostic guarantee: after discovery/compile, every
relationship's endpoints resolve to a concept that exists in the graph. This must
hold REGARDLESS of whether optional validation shapes were supplied (V17 supplied
none, so `validate-stage` short-circuited and 119 dangling edges survived into
the artifact).

Behavior (auto-mint the implied entity — a referenced node IS a discovered
entity, which fits the evolver's job to "discover entities + edges + ambiguities"):

1. In the deterministic compile/normalize path (not behind optional shapes), scan
   every relationship draft. For any `:source-uri`/`:target-uri` that is NOT in
   the minted concept set, **auto-mint a minimal concept** for that URI — a
   low-confidence / "implied" concept (label derivable from the URI's own id
   segment generically, NOT a domain-specific label) flagged for later enrichment.
2. **Ambiguity surfacing:** if the dangling URI is a near-variant of an existing
   concept URI (e.g. a different identifier encoding), record it as an ambiguity
   for the dedup/alignment layer rather than silently minting a twin. The
   near-variant detection must be GENERAL (structural URI similarity / the
   existing dedup-cascade), NOT a hardcoded code-format rule.
3. **No silent loss / no false green:** surface counts — implied concepts minted,
   ambiguities flagged, edges that still cannot resolve. An artifact must never
   report success while carrying dangling edges.

This is domain-agnostic engine behavior; it works for any source/medium and any
domain. It does NOT encode anything about CIP/SOC/education.

## Acceptance criteria

- [ ] After compile, `every-edge-endpoint-resolves` is true for a graph built
      WITHOUT supplied shapes (the V17 condition) — dangling count 0.
- [ ] Referenced-but-unminted endpoints become minimal implied concepts, flagged
      low-confidence / for-enrichment (not full-confidence twins).
- [ ] Near-variant dangling URIs are surfaced as ambiguities via the general
      dedup/alignment path, not silently duplicated, and NOT via a hardcoded
      format rule.
- [ ] Counts surfaced (implied minted / ambiguities / unresolved); no false green.
- [ ] Pre-existing graphs that already supplied shapes still validate as before
      (no regression); the S17 skeleton + S12 dedup suites stay green.
- [ ] Live verify: a real discovery→compile run that previously dangled now
      resolves all endpoints; captured.

## Blocked by

None — parallel-safe with V19 (`source_tools_*`). Owns `rlm_discovery.clj`
(compile path) + `deterministic_skeleton.clj`.

## Cross-references

- V17 capture (the 119 dangling edges + the validate-skip root cause):
  `docs/build-timeline/live-verify/V17-graph-b-full-scale.md`.

## Core Disciplines (binding on every implementer, human or subagent — no exceptions, no reinterpretation)

1. NEVER make assumptions and NEVER presume "model variance" / "transient" / "flaky" as an explanation. Every unexpected behavior is diagnosed to its root cause before proceeding.
2. All behaviors are not just verified for COMPLETION — they are adversarially reviewed for QUALITY. Ask "how could this pass while still being wrong?" and test that.
3. Always deeply debug: chase every issue all the way to its root with explicit debug text/logging added during the investigation. Heavy instrumentation when a symptom resists hypothesis cycles.
4. Synthetic tests passing is the FLOOR, not the ceiling. Live verification (real Grain event store, real LLM calls, real ColBERT where the behavior involves them) is mandatory before declaring done. No invented fixtures — tree/model outputs are captured from real runs. No false green — a passing fallback or degenerate path is not proof.
5. Never bypass a bug with fallback logic; fix the root cause.
6. Implementation proceeds via /tdd: vertical tracer bullets, one test → one implementation; tests verify behavior through public interfaces, never implementation details.
7. Grain/ORC disciplines hold: all writes are commands → schema-validated events; read-models project; no bare event-store appends; recursive-only RLM; no hardcoded phrase matching as quality gates.

### Verification-phase additions (binding for this initiative)

8. No strawman / unbiased baseline. The old side (graph A1) always runs at its STRONGEST honest config (embeddings on, crosswalk CIP↔SOC extracted as edges, FK extraction). Never hobble or weaken the old system to make the new one look better — beating a weakened baseline proves nothing.
9. Adversarial qualitative verdict. The comparison is judged on the ACTUAL verbatim information returned, per vertical — actively hunt for where the NEW system is WORSE. "Both completed" is not a pass; no false-better.
10. "Deterministic skeleton" ≠ LLM-free. The ontology is DISCOVERED BY LLMs (recursive-RLM discovery + LLM dedup/CQ judges) inside the deterministic skeleton; verify BOTH the deterministic contracts AND the LLM-discovery quality.
11. Standing ops rules: the real OpenRouter key is passed as a shell env var ONLY, never committed; never truncate model-authored output when capturing/comparing (pass verbatim); retrieval-facing descriptions are self-contained (no file paths / SHAs / slice names); HITL audit every changed/added file by path before any commit; branch = feature/ontology-architecture; one commit per slice; co-author `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
12. Domain/industry-agnostic. These fixes improve the GENERAL evolutionary builder — NO tuning toward the education/crosswalk example or any vertical (no baked-in CIP/SOC/OPEID knowledge or industry schema). The evolver's focus comes from the runtime goal/docs. Format/medium SPECIALISTS (CSV/SQL/Excel/text, each with their own tools + instructions + trees) ARE encouraged — improving a specialist's ergonomics is in scope; encoding a domain answer is not.
