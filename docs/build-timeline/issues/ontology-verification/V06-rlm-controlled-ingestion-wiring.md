# V06 — RLM-controlled source ingestion wiring

**Type:** AFK · **Milestone:** M2 · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`

## What to build

The keystone of the format-aware-ingestion ADR: wire the discovery RLM so that,
given a RAW structured source, it (1) detects the source format, (2) EXPLORES it
via the per-format source-access tools (V03 CSV / V04 SQL / V05 Excel / existing
text), (3) designs the extraction, and (4) feeds the ONE new deterministic
skeleton through the existing `:inline-concepts` / `:inline-relationships` seam.
The skeleton's parse stage gains a route from "raw structured source" → this
RLM-controlled ingestion (today it handles only `:ttl` + `:inline-*`).

"Control its exploration": the RLM chooses a format-appropriate strategy — a CSV
is explored differently from a SQL DB differently from text — but all flow into
the same substrate (normalize → dedup S12 → evidence S13 → validate S10/11 →
auto-embed V01 → index → CQ S15).

## Acceptance criteria

- [ ] Given a raw CSV source descriptor, the discovery RLM explores it via the
      CSV tools and produces concept + relationship drafts that ingest into the
      skeleton (end-to-end to events).
- [ ] Same for a SQLite source (via SQL tools) and an Excel source (via Excel
      tools) — each produces a connected sub-graph from its structure.
- [ ] Cross-source linking works: a crosswalk CSV's CIP↔SOC becomes edges that
      connect to CIP/SOC concepts from other sources (entity resolution / shared
      URIs) — the result is CONNECTED, not per-source piles.
- [ ] Scale: the RLM never loads a whole source into context (tools sample) —
      asserted on a large fixture.
- [ ] Format dispatch is correct (CSV vs SQL vs Excel vs text routes to the right
      tools); an unknown format fails loudly with a clear error (no silent skip).
- [ ] Malformed/empty source → loud, root-caused failure, not a fabricated graph.
- [ ] All predecessor slice suites stay green.
- [ ] Live verify: real RLM session ingests at least one real structured source
      (e.g. the CIP-SOC crosswalk CSV + a small SQLite) end-to-end → skeleton
      build to a connected graph; transcript + drafts captured verbatim.

## Blocked by

V03, V04, V05.

## Cross-references

- PRD module M-P1 (ADR); S18 discovery + the `:inline-*` skeleton seam; S12/S13/
  S10-11/S15 downstream stages; the old `csv_ontology`/`sql_ontology` extraction
  knowledge ported into the tools.

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
