# V02 — Mode A early read (ingest existing TTL + per-vertical read)

**Type:** HITL · **Milestone:** M1 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

The brownfield proof + early quality read. Ingest the EXISTING production BRYC
graph (`louisiana_programs_full.ttl`) into the new substrate via the S09 TTL
round-trip ingest, auto-embed/index it (V01), and run a first per-vertical
exploration read (career / financial / outcome / academic / preference) using
the new system's RLM exploration. Produce a documented read for the user so we
get an early signal on substrate + retrieval + exploration quality with ZERO new
ingestion work, and prove "bring your own graph, we improve + extend it."

HITL: the user reviews the early read before M2 build work proceeds.

## Acceptance criteria

- [ ] `louisiana_programs_full.ttl` ingests into the new substrate; node/edge
      counts are reported and sane vs the source TTL (round-trip-faithful).
- [ ] Auto-embed/index (V01) runs over the ingested graph; concepts are
      retrievable via hybrid-search.
- [ ] A per-vertical exploration read is produced for at least one student
      profile (e.g. Trinity), capturing the ACTUAL recommendations + connections
      + reasoning VERBATIM — not a score, the real output.
- [ ] The read is written to a reviewable doc under
      `docs/build-timeline/live-verify/` for HITL sign-off.
- [ ] Adversarial: note anything that looks WORSE or missing vs the recorded old
      outputs (`BRYC-GRAPH-ANALYSIS.md`) — do not hide gaps.
- [ ] Live verify: real LLM exploration, real embeddings/ColBERT, captured.

## Blocked by

V01 (auto-embed).

## Cross-references

- PRD milestone M1; `BRYC-COMPARISON-RUN-DESIGN.md` (Mode A); the recorded
  Trinity/Aminata/Reagan outputs in daryls-area51 `docs/BRYC-GRAPH-ANALYSIS.md`.
- S09 TTL ingest; S19 RLM tools; S20 orientation card.

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
