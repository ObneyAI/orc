# V05 — Excel source-access tools

**Type:** AFK · **Milestone:** M2 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

Per-format SOURCE-ACCESS tools for Excel (.xlsx), granted to the discovery RLM so
it explores a workbook by sheet/column WITHOUT loading the whole file. The Excel
leg of the format-aware-ingestion ADR — the one format the OLD builder never
supported (it required hand conversion to CSV). Targets: O*NET v30.1
`db_30_1_excel` (multi-file/sheet) and PSEO `pseo_la.xlsx` (2 large sheets:
institution+CIP+degree → Y1/Y5/Y10 earnings percentiles).

Tools (decision-encoding shape): `list-sheets`, `sheet-columns` (header + types
for a sheet), `sample-rows` (per sheet, capped). Internally may stream the xlsx
(it is a zip of worksheet XML) so a 119 MB sheet is sampled, not loaded.

## Acceptance criteria

- [ ] Each tool callable by an RLM sandbox against an .xlsx fixture: list-sheets →
      sheet names; sheet-columns → header/types; sample-rows → bounded rows.
- [ ] Handles a LARGE multi-sheet workbook (PSEO-shaped) by sampling — adversarial:
      assert no full-workbook load (bounded read of the worksheet stream).
- [ ] Multi-file Excel directories (O*NET-shaped) are enumerable.
- [ ] Self-contained docstrings (PURPOSE / EXAMPLE / RETURNS).
- [ ] Read-side only: no events emitted by the tools.
- [ ] All predecessor slice suites stay green.
- [ ] Live verify: an RLM session uses the Excel tools to explore a real
      PSEO/O*NET workbook and describe its sheets/columns correctly (captured).

## Blocked by

None.

## Cross-references

- PRD module M-P1 (ADR); note the OLD builder has NO Excel support — this is
  net-new capability that also removes a manual pre-conversion step.

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
