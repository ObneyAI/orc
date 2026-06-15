# V03 — CSV source-access tools

**Type:** AFK · **Milestone:** M2 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

Per-format SOURCE-ACCESS tools for CSV, granted to the discovery RLM so it can
EXPLORE a CSV by its shape WITHOUT loading the whole file into context. This is
the CSV leg of the format-aware-ingestion ADR (mirrors the S19 graph-tools
pattern, now for sources). Port the relevant `csv_ontology` extraction knowledge
(columns → properties/classes, row shape → individuals, adjacent FK-like columns
→ relationships) into the tools' internals.

Tools (decision-encoding shape): `peek-columns` (header + inferred types),
`sample-rows` (N rows, capped), `profile-column` (distinct count / cardinality /
example values). All sample/profile — none dump the whole file.

## Acceptance criteria

- [ ] Each tool callable by an RLM sandbox; returns correct shape against a CSV
      fixture (e.g. a CIP-SOC crosswalk: peek-columns → the 4 columns;
      sample-rows → N rows; profile-column → cardinality).
- [ ] Tools SAMPLE, never dump — adversarial: a tool over a large CSV reads a
      bounded slice, asserted (no full-file load).
- [ ] Docstrings are self-contained (PURPOSE / EXAMPLE / RETURNS) — the model can
      use each from the docstring alone (S19 docstring-quality pattern).
- [ ] Read-side only: no events emitted by the tools.
- [ ] Empty/edge CSV (no rows, weird header) returns honest empty, not an error
      or fabricated data.
- [ ] All predecessor slice suites stay green.
- [ ] Live verify: an RLM session uses the CSV tools to explore a real crosswalk
      and describe its structure correctly (captured).

## Blocked by

None.

## Cross-references

- PRD module M-P1 (ADR); the S19 sandbox-tools pattern (tool isolation +
  docstring-quality tests transfer directly); the old `csv_ontology` sheet.

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
