# V10 — Graph-diff harness (A1 / A2 / B structure stats)

**Type:** AFK · **Milestone:** M3 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

A harness that computes + diffs structural statistics across the three graphs —
A1 (old builder, V08), A2 (real production graph), B (new builder, V09) — so the
GRAPH layer of the head-to-head is measured, not eyeballed. Output a structured
stats table per graph + a delta view.

## Acceptance criteria

- [ ] For each of A1, A2, B: total nodes; node counts by type
      (program / CIP / SOC / institution / earnings / wage / …); total edges;
      edge counts by predicate; cross-source link count; properties-per-concept
      distribution; coverage (e.g. % programs with a CIP, % CIPs with a SOC, %
      SOCs with wage + earnings).
- [ ] A delta table: B vs A1 and B vs A2 on each metric, with a regression flag
      where B < A.
- [ ] Cross-source connectivity is measured explicitly (the program↔CIP↔SOC↔
      earnings chains) — the dimension the rebuild targets.
- [ ] The harness loads each graph artifact from disk (A1/B built here; A2 the
      cached production graph) without re-running the builds.
- [ ] Output is a committed, reviewable stats doc feeding V13.
- [ ] Honest counting — no metric defined to flatter B; adversarial: pick metrics
      where the OLD graph might win and report them too.

## Blocked by

V08, V09.

## Cross-references

- PRD module M-Compare; the per-dimension rubric in `BRYC-COMPARISON-RUN-DESIGN.md`;
  A2 = `daryls-area51/.bryc-graph-cache-with-embeddings.json`.

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
