# MT-7a — Vocabulary binding + enforcement seam (authors bound to the canonical entity-type vocabulary)

## Parent
[Meaningful multi-table extraction](README.md) · Design: [ADR-0001 canonical vocabulary binding](../../../../components/ontology/docs/adr/0001-canonical-vocabulary-binding.md) (settled — do not re-litigate) · Terms: [`components/ontology/CONTEXT.md`](../../../../components/ontology/CONTEXT.md) §Vocabulary & identity.

## What to build
Bind every per-container extraction AUTHOR (the per-row path AND the aggregating path) to the **canonical entity-type vocabulary** — the model-spec's discovered `:entity-types` — so vocabulary freelancing becomes impossible:

1. **Enumerated contract** — the AUTHOR's instruction enumerates the declared canonical types VERBATIM (name + keying fields) and requires the emitted entity-type to be one of them verbatim. (The explicit-proposal alternative is MT-7b; until it lands, non-matching = the re-ask/fail path below.)
2. **Deterministic validation seam** (between AUTHOR and APPLY): normalized-EXACT match of the emitted type against the vocabulary's `:type` + `:aliases` (the same case/separator normalization identity already uses — `job-element` ≡ `Job Element` collapses here). Match → snap to the canonical spelling. **NO fuzzy/substring matching** (a container-prefixed variant must NOT silently snap — over-merge risk).
3. **One re-ask on mismatch** (reuse the existing resilience/fallback seam — no new machinery), re-presenting the enumerated list. Still mismatched → **honest per-container failure**, surfaced in the extraction report. Never silent freelancing.
4. **Empty-vocabulary hard stop** — an empty/unparseable `:entity-types` means extraction CANNOT proceed for that source (guaranteed 100% freelancing); fail loudly at the extract boundary, never silently continue.

Result: every draft's `:entity-type` is canonical-by-construction, so the existing canonical-URI minting works unchanged and same-entity drafts merge.

## Acceptance criteria
- [ ] Variant-typed drafts whose type normalizes to a declared type (case/separator variants) land under the canonical spelling — one URI scheme per declared type. Verified LIVE on real O\*NET: the occupation split (`occupation/…` vs variant-scheme occupations) collapses for declared types.
- [ ] A non-matching emitted type triggers EXACTLY one re-ask with the enumerated list; a still-non-matching author yields an honest per-container `:failure` in the extraction report — never a silently-freelanced draft. TDD through public seams.
- [ ] Empty/unparseable `:entity-types` → extraction fails loudly (no drafts, honest report) — never proceeds.
- [ ] Behavior-preserving when the author already emits a canonical type verbatim (the common case: nothing changes).
- [ ] Domain/format-agnostic: the enumeration and matching operate on the runtime-discovered vocabulary; NO domain type/column named in code; NO fuzzy matching. Ontology brick gate green; 0 orphan JVMs.

## Type
AFK.

## Blocked by
None — can start immediately (MT-6 landed).

## /prototype
Not required — the mechanism reuses proven seams (the resilience re-ask, the normalization identity already in use), and the failed keying-resolution prototype + the grill already established the shape.

## Handoff focus
Read-first: the per-container extract sheet (SAMPLE→AUTHOR→APPLY, both resilient + flat paths) and where the AUTHOR receives the model-spec; the APPLY routing seam (where MT-6's sample-driven gate runs — the validation belongs alongside); the resilience/fallback re-ask seam; `normalize-model-spec` (the silent `[]` degrade this slice turns into a loud stop); the GC-6 vocabulary/aliases shape. Re-orchestrate — the validation is a deterministic step at the existing seam; do NOT fork the author or apply paths.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that (a fuzzy snap that over-merges reads as "fixed" but is WRONG; a re-ask loop that silently accepts freelancing is a false green).
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause (empty vocabulary = loud stop; unmatched type = honest failure, never a silent snap or proceed).
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; NO hardcoded phrase matching / NO fuzzy or substring entity-type matching — normalized-EXACT against the discovered vocabulary only.
8. Re-orchestrate, NOT rewrite — reuse the resilience re-ask seam + the existing normalization; do NOT fork the author/apply paths.
9. Adversarial qualitative verdict — hunt for a freelanced draft that slipped through, an over-merge via matching, or a silently-proceeding empty vocabulary; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — the vocabulary is LLM-discovered; the binding is deterministic; verify BOTH on real runs.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — the code enforces referential consistency against the model's OWN discovered vocabulary; name NO O\*NET/CIP/SOC column or entity; this is the general system TESTED with O\*NET, not built FOR it.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
