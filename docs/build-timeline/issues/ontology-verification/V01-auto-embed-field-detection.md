# V01 — Auto-embed / ColBERT field detection in the skeleton

**Type:** AFK · **Milestone:** M1 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

The deterministic skeleton's embed + index stages must, BY DEFAULT, detect which
concept fields are worth embedding and embed + ColBERT-index them — instead of
the current skip-when-no-fn behavior. The detection capability already exists in
the codebase (a heuristic schema scan, an LLM-data-sample analyzer, and the old
builder's auto-detect-colbert-fields path); this slice wires it into the
skeleton's embed/index stages so a graph built through the NEW path is
semantically searchable by default. Caller-supplied embed/index fns still
override; the DEFAULT changes from skip to detect-then-embed.

This closes Pillar 2 (auto-find fields that need embedding via embedding model or
ColBERT) and gates Mode A (V02), which needs embeddings to explore the ingested
graph.

## Acceptance criteria

- [ ] A skeleton build over a fixture with embeddable text fields produces
      concept embeddings AND a ColBERT index WITHOUT the caller supplying an
      embed/index fn (default = detect-then-embed, not skip).
- [ ] Field detection is exercised through the public build entry — the detected
      fields are the ones embedded; a field with no semantic content is not.
- [ ] The embedded/indexed fields are subsequently RETRIEVABLE via hybrid-search
      (embedding + ColBERT signals return them) — verified through the public
      retrieval surface, not internals.
- [ ] Caller override still works: an explicit embed/index fn is honored.
- [ ] Adversarial: a build with NO embeddable fields does not error and does not
      fabricate embeddings (honest empty, not false green).
- [ ] All predecessor slice suites stay green.
- [ ] Live verify: a real build (real embeddings + real ColBERT) end-to-end,
      captured, showing detected fields → retrievable.

## Blocked by

None.

## Cross-references

- PRD module M-P2; gap P2 in `intent-alignment-verdict.md`.
- Existing capability to wire in: the embeddable-field detectors + the old
  builder's auto-detect-colbert-fields path; the skeleton embed/index stages.

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
