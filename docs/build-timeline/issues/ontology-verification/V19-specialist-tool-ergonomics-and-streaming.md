# V19 — Format-specialist tool ergonomics + count affordance + stream-all

**Type:** AFK · **Milestone:** M3 (builder hardening; foundation for V20 full-extraction) · **Parent:** `docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`
**Surfaced by:** V17 — the Excel specialist burned ~4 iterations on `sample-rows`/`sheet-columns` ARITY errors (passed a sheet-MAP instead of a name/index; passed a 4th arg), then ran out of budget → PSEO yielded `:no-output` (zero earnings). Coverage was also one-window-per-source.

## What to build

Harden the per-format source-access SPECIALIST tools so (a) a mis-call
self-corrects instead of costing the source its whole budget, and (b) the
specialist can both size a source and stream ALL of it (the capability V20's
deterministic full-extraction will apply a transform over). Format-specialist
improvement, fully domain-agnostic.

1. **Forgiving + consistent signatures across csv / sql / excel / text
   specialists:**
   - A sheet/table/selector argument accepts a name OR a 0-based index OR a
     descriptor MAP (extract its `:name`/`:index`) — passing back the map a prior
     call returned must not throw.
   - limit/offset accept either positional args OR a trailing options map; an
     extra or wrong-shape arg degrades gracefully (a clear teaching error) rather
     than throwing an arity exception.
   - Errors are instructive: they state the correct call form (the S19 lesson —
     usable from the docstring/error alone), so a specialist recovers in one turn.
   - Consistency is the goal: the same calling-convention shape across all format
     specialists, so a builder fluent in one isn't tripped by another.
2. **Count affordance (general, every format):** a tool to get the total
   row/entity count (or sheet/table cardinality) for a source WITHOUT loading it —
   so a specialist knows how much remains and can page to coverage, and so V20 can
   bound a full extraction.
3. **Stream-all capability (general, every format):** a way to iterate the FULL
   row set of a source in bounded windows (building on the `:offset` paging
   already added in V03/V04/V05/V17) — the substrate V20's deterministic
   full-extraction needs to apply a model-designed transform over every row. Keep
   the per-call ceiling; provide the iteration affordance above it.

None of this encodes anything domain-specific. It is medium ergonomics +
capability.

## Acceptance criteria

- [ ] Passing a descriptor MAP (as returned by list-sheets/list-tables) where a
      name/index is expected does NOT throw — it resolves.
- [ ] An extra-arg / wrong-shape sampling call returns a clear teaching error, not
      an arity exception; the convention is consistent across csv/sql/excel/text.
- [ ] A count affordance returns total cardinality per source without a full load,
      for every format.
- [ ] A stream-all affordance iterates the full row set in bounded windows for
      every format (per-call ceiling preserved).
- [ ] Live verify on REAL sources: the PSEO Excel file (the V17 failure) is
      sampled + counted + streamed without arity errors; one SQL + one CSV source
      likewise. Captured.
- [ ] Existing V03/V04/V05 + V06 ingestion suites stay green.

## Blocked by

None — parallel-safe with V18 (`rlm_discovery`/`skeleton`). Owns the
`source_tools_*` namespaces (csv/sql/excel/text) + their tests.

## Cross-references

- V17 capture (the Excel arity trace + one-window coverage):
  `docs/build-timeline/live-verify/V17-graph-b-full-scale.md`.
- Foundation for V20 (deterministic full-extraction) + V21 (re-run).

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
12. Domain/industry-agnostic. These fixes improve the GENERAL evolutionary builder — NO tuning toward the education/crosswalk example or any vertical (no baked-in CIP/SOC/OPEID knowledge or industry schema). The evolver's focus comes from the runtime goal/docs. Format/medium SPECIALISTS (CSV/SQL/Excel/text, each with their own tools + instructions + trees) ARE encouraged — improving a specialist's ergonomics is in scope; encoding a domain answer is not.
