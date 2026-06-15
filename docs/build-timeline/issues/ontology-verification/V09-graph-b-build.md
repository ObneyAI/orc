# V09 — Graph B build (new builder, 5 sources)

**Type:** AFK · **Milestone:** M3 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

Build graph B: run the NEW builder (format-aware RLM-controlled ingestion V06 +
auto-embed V01 + axiom ingest V07, through the deterministic skeleton) on the
SAME 5 official sources used for A1. Each builder assembles its own program set +
its own embeddings (the hand-made embeddings CSV is excluded). The output is the
new-system graph the head-to-head measures.

## Acceptance criteria

- [ ] The new builder ingests all 5 official sources (IPEDS SQLite, CIP-SOC CSV,
      O*NET Excel, LA-OEWS CSV, PSEO Excel) via V06 and produces a connected
      graph (program↔CIP↔SOC↔earnings links present).
- [ ] Auto-embed (V01) produced embeddings + ColBERT index over the new graph;
      concepts retrievable via hybrid-search.
- [ ] Discovered axioms (V07) landed (not skipped).
- [ ] The build runs the full skeleton contract (parse→normalize→dedup→validate→
      embed→index→CQ exit-criterion) and reaches a terminal status; failures are
      loud + root-caused, never masked.
- [ ] Graph-structure stats captured (same schema as V08) for the V10 diff.
- [ ] Same embedding model + ColBERT config as A1 (fairness control).
- [ ] All predecessor slice suites stay green.
- [ ] Live verify: full real build end-to-end (real LLM discovery, real
      embeddings/ColBERT) over the 5 sources; captured.

## Blocked by

V01, V06, V07.

## Cross-references

- PRD modules M-P1/M-P2/M-Axioms + M-Compare; S17 skeleton; the
  `discover-and-build!` entry.

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
