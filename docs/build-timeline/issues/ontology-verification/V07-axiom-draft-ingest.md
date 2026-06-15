# V07 — Axiom-draft ingest

**Type:** AFK · **Milestone:** M2 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

Discovery already EXTRACTS axiom drafts, but the discovery→ingest adapter records
them as skipped — so discovered axioms (disjointness, characteristics,
sub-property, chain) never reach the graph. Wire axiom-drafts → the S07 axiom
commands, applying the SAME JSON-string→keyword coercion discipline already used
for concept `:scope` and relationship `:confidence-class` (the live-verify ingest
finding: the model returns enum values as JSON strings).

## Acceptance criteria

- [ ] A discovery output carrying axiom-drafts ingests them as real S07 axiom
      events (not skipped) — verified through the public ingest + axioms
      projection.
- [ ] String→keyword coercion: axiom-type / characteristic values arriving as
      JSON strings coerce to the correct keyword enum; unknown values are handled
      loudly (no silent drop, no fabricated axiom).
- [ ] Malformed axiom-draft → loud, root-caused failure, not a silent skip.
- [ ] The provenance count that previously said "skipped" now reflects ingested
      axioms.
- [ ] All predecessor slice suites stay green.
- [ ] Live verify: a real discovery run that extracts ≥1 axiom now lands it in
      the graph end-to-end (captured).

## Blocked by

None.

## Cross-references

- PRD module M-Axioms; S07 axiom commands; S18 discovery + the scope/
  confidence-class coercion precedent (same pattern); the `:axioms-skipped`
  gap noted in `intent-alignment-verdict.md` + the live-verify results doc.

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
