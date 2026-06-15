# V04 — SQL / SQLite source-access tools

**Type:** AFK · **Milestone:** M2 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

Per-format SOURCE-ACCESS tools for SQL/SQLite, granted to the discovery RLM so it
explores a relational source by its schema WITHOUT loading the whole DB. The SQL
leg of the format-aware-ingestion ADR. Port the `sql_ontology` extraction
knowledge (tables → classes, columns → properties, foreign keys → relationships,
row samples → individuals) into the tools' internals. Target: a 60+-table SQLite
like IPEDS `output.db` must be explorable without dumping it.

Tools (decision-encoding shape): `list-tables`, `table-schema` (columns + types),
`foreign-keys`, `sample-rows` (per table, capped), `query` (read-only SELECT;
bounded result). All sample/query — never dump.

## Acceptance criteria

- [ ] Each tool callable by an RLM sandbox against a SQLite fixture: list-tables →
      the tables; table-schema → columns/types; foreign-keys → the FK edges;
      sample-rows → bounded rows; query → bounded SELECT result.
- [ ] Tools SAMPLE/bound — adversarial: a query/sample over a large table returns
      a bounded slice (no full-table/DB load), asserted. `query` is read-only
      (a write/DDL attempt is rejected).
- [ ] FK discovery surfaces relationships the RLM can turn into edges
      (table→table), demonstrated on the fixture.
- [ ] Self-contained docstrings (PURPOSE / EXAMPLE / RETURNS).
- [ ] Read-side only: no events emitted by the tools.
- [ ] All predecessor slice suites stay green.
- [ ] Live verify: an RLM session uses the SQL tools to explore a real
      multi-table SQLite (IPEDS-shaped) and describe its schema + key links.

## Blocked by

None.

## Cross-references

- PRD module M-P1 (ADR); the old `sql_ontology` sheet (already reads SQLite via
  PRAGMA: tables/columns/FKs); S19 tool pattern.

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
