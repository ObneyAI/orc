# V12 — New exploration (5 vertical framings over B)

**Type:** AFK · **Milestone:** M3 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

The NEW side of the exploration head-to-head. Run the new system's GENERAL RLM
exploration (S19 graph tools + recursive-RLM over the new substrate) on graph B,
framed in the SAME 5 verticals (career / financial / outcome / academic /
preference) on the SAME probe set as V11. Capture the full output VERBATIM, per
vertical, for the side-by-side. This is a deliberately HIGH bar: general new
exploration vs the OLD hand-tuned bespoke explorers.

## Acceptance criteria

- [ ] The new RLM exploration answers each of the 5 vertical framings on the 3
      profiles (Trinity / Aminata / Reagan) + the adversarial probes, over graph B.
- [ ] Output captured VERBATIM per vertical per profile (recommendations,
      connections followed, reasoning, evidence) — same shape as V11 so V13 can
      diff directly; not summarized, not truncated.
- [ ] Exploration uses fused retrieval (BFS + embedding + ColBERT + lexical,
      scoped) — verified it actually traverses cross-source links (program↔CIP↔
      SOC↔earnings), not just label lookups.
- [ ] Same probes + same vertical framings as V11 (fairness control).
- [ ] Adversarial: flag any vertical where the new output is thinner / less
      grounded than the old — do not hide it.
- [ ] Live verify: real RLM exploration over the real graph B; captured.

## Blocked by

V09 (graph B).

## Cross-references

- PRD module M-Explore; S19 RLM tools + S20 card; the 5 vertical definitions in
  daryls-area51 (career/financial/outcome/academic/preference).

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
