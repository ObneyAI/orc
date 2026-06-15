# V11 — Old exploration re-run (live, over A2)

**Type:** HITL · **Milestone:** M3 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

The OLD side of the exploration head-to-head. Stand up daryls-area51's 5 bespoke
explorers (career / financial / outcome / academic / preference; old ORC SHA)
over graph A2 (the real production graph) and RE-RUN them LIVE on the probe set —
fresh, same conditions, nothing stale or cherry-picked. Capture the full output
VERBATIM, organized per vertical, for the side-by-side.

HITL: needs the old-SHA daryls-area51 environment stood up (its deps) — the user
helps with setup/keys.

## Acceptance criteria

- [ ] daryls-area51's 5 explorers run live over A2 on the probe set: the 3 recorded
      student profiles (Trinity / Aminata / Reagan) + the adversarial probes
      (career differentiation, SOC→CIP→program multi-hop incl. crosswalk-only,
      financial/wage grounding, HBCU sensitivity, apprenticeship-vs-degree,
      hard-filter correctness).
- [ ] Full output captured VERBATIM per vertical per profile (recommendations,
      connections followed, reasoning, evidence) — not summarized, not truncated.
- [ ] The live re-run is sanity-checked against the recorded outputs in
      `BRYC-GRAPH-ANALYSIS.md` (do they still reproduce? note drift).
- [ ] Captured to a reviewable doc feeding V13.
- [ ] Live verify: real explorers, real LLM, real graph A2 — this IS the live run.

## Blocked by

Graph A2 available (the cached production graph) + the old-SHA environment.

## Cross-references

- PRD module M-Explore; daryls-area51 `bryc_graph_explorers.clj` +
  `bryc_explorer_workflows.clj` + `docs/BRYC-GRAPH-ANALYSIS.md`.

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
