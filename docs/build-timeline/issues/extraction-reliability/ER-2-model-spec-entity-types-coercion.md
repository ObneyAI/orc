# ER-2 — Coerce/guard model-spec `:entity-types` unparsed-string at the extract boundary

## Parent
[Extraction reliability index](README.md)

## The bug (observed, not assumed)

Instrumenting `orchestrate-extract-containers` to dump `(get-in context [:inputs :model-spec])` per source showed: for **crosswalk** and **O\*NET**, `:entity-types` reached the extractor as an **unparsed EDN STRING** — my `(count …)` returned 342 / 332 (the string length) and `(doseq [et …] (:type et))` yielded per-character `nil`s. **ipeds** parsed cleanly (4 real entity-type maps). So the C1 `[:vector [:map …]]` parse of `:entity-types` is INTERMITTENT per source (the same fragility GC-6/GC-10 defend), and on the crosswalk/O\*NET path the string form reached the extractor un-coerced.

`normalize-model-spec` (`extract_subbehavior.clj` ~824) EXISTS to coerce exactly this (GC-10 Fix A) and IS called at `central_evolver.clj:458` — but the model-spec that reached `orchestrate-extract-containers` was still string-form, so either the coercion doesn't run on the path the per-container extractor reads, or it didn't recognize that particular string shape.

Extraction still recovered here (the AUTHOR path produced drafts), so it was non-fatal THIS run — but an un-coerced `:entity-types` starves GC-1 canonicalization of `:uri-keying-fields` (it can't look up a type's keying), a plausible contributor to entity fragmentation (`institution`/`inst` under different type names), and it may worsen ER-1 (a garbage model-spec pushes the AUTHOR toward generic-key guessing).

## What to build

Guarantee the extractor always sees a PARSED `:entity-types` (vector of maps):
- Ensure `normalize-model-spec` runs on the model-spec BEFORE it reaches `orchestrate-extract-containers` (the per-container path), not only in `delegate-model-extract!`, OR harden the coercion to recognize the string shape that slipped through.
- Make `normalize-model-spec` robust to the observed string form (parse the EDN string → vector of entity-type maps; MIRROR GC-6's `normalize-vocabulary`). If it genuinely can't parse, surface it HONESTLY (a diagnostic), never leave a 342-nil vector that silently degrades canonicalization.
- DOMAIN-AGNOSTIC — purely structural coercion; names no field.

## /prototype
Light — instrument to catch WHERE the string slips past (is normalize called on this path? does it handle the string?), then a targeted deterministic coercion. Reproduce with a crosswalk/O\*NET 2/2 run dumping the pre- and post-normalize `:entity-types`.

## Acceptance criteria
- [ ] The model-spec reaching `orchestrate-extract-containers` has `:entity-types` as a vector of maps (each with `:type` + `:uri-keying-fields`) for ALL sources, incl. crosswalk + O\*NET — verified LIVE.
- [ ] A genuinely-unparseable `:entity-types` is surfaced honestly (diagnostic), never a nil-filled vector (#4/#5).
- [ ] GC-1 canonicalization degraded-count on a clean 3-source run is ~0 (the coercion feeds it real keying).
- [ ] TDD (red→green) on `normalize-model-spec` for the observed string form (pure); behavior through public fn. Ontology brick gate green; 0 orphan this-repo JVMs.

## Blocked by
None — self-contained.

## Handoff focus
Read-first: `extract_subbehavior.clj` `normalize-model-spec` (~824) + its call site `central_evolver.clj:458` + how the model-spec threads to `orchestrate-extract-containers` (the pipeline blackboard `:model-spec`); `synthesize_vocab_subbehavior.clj` `normalize-vocabulary` (the mirror pattern). Re-orchestrate, don't fork. One bounded build at a time; `pgrep -f` hygiene.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — extend `normalize-model-spec` + its wiring; do NOT fork.
9. Adversarial qualitative verdict — hunt for a silently-degraded model-spec masked as success; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — the coercion is deterministic; verify it against the real LLM-authored string form.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Domain/format-agnostic — purely structural coercion; names no field.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
