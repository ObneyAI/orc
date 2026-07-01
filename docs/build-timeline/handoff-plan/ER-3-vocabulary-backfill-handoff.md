# Handoff — ER-3: backfill empty model-spec `:entity-types` from the GC-6 canonical vocabulary

> **⚠ UPDATE (2026-07-01) — the SIMPLE backfill in this handoff was prototyped, found to REGRESS O\*NET (0 drafts — the vocabulary doesn't cover O\*NET's source-specific Ability/WorkActivity types), and REVERTED.** See the "Prototype finding" section in the [issue](../issues/extraction-reliability/ER-3-author-freelances-entity-type-fragmentation.md). The real fix must be ADDITIVE (canonical floor + AUTHOR may still add source-specific types), likely COUPLED with GM-1's grain/reify handling for O\*NET's rating tables. Steps (1)+(2) below still describe the (reverted) mechanism; do NOT ship them as-is — make them additive + verify O\*NET still extracts.

**Issue:** [`../issues/extraction-reliability/ER-3-author-freelances-entity-type-fragmentation.md`](../issues/extraction-reliability/ER-3-author-freelances-entity-type-fragmentation.md)
**Branch:** `feature/ontology-architecture` · commit-LOCAL only. `OPENROUTER_API_KEY` = shell env var. **One bounded build at a time; `pgrep -f` hygiene.**

## Root cause (established — instrumented)
When the model-spec `:entity-types` is EMPTY (a malformed C1 string → `normalize-model-spec` → `[]`, observed on ipeds/O\*NET), the per-container AUTHOR has no `:type` to anchor to (DT4 prompt: "tag `:entity-type` with the model-spec `:type`"), so it FREELANCES inconsistent names across containers (`"EducationalInstitution"` in one, `:academic_institution_library` in another, for the SAME institutions) → GC-1 keys the URI on the divergent type → fragmentation. When the model-spec HAS types (crosswalk), the AUTHOR is consistent → no fragmentation (the control). Form variants (`"Ability"`/`:ability`) already collapse; the real split is DIFFERENT NAMES.

## The fix
GC-6 synth-vocab already produces the CORRECT single canonical types (`:canonical-entity-types`, each `{:type :uri-keying-fields :aliases}`). Use it as the backfill:
1. **Thread `:vocabulary` to the Extract step** — add it to `model-extract-pipeline-def`'s `delegate-extract` `:reads` (central_evolver ~176) + forward it in inputs; add to `delegate-model-extract!`'s Extract inputs (~440). It's already in the pipeline blackboard (~142).
2. **Backfill in `orchestrate-extract-containers`** (where ER-1/ER-2 already normalizes the model-spec): if the normalized model-spec `:entity-types` is EMPTY, populate it from the vocabulary's `:canonical-entity-types` (map each to `{:type :uri-keying-fields}`), so the AUTHOR grounds in the canonical types across ALL containers. `normalize-vocabulary` (synthesize_vocab) coerces the vocabulary's own C1 string form first.
3. **(Backstop, verify first)** confirm GC-1 mints the canonical URI from the NORMALIZED `:entity-type` (so any residual string/keyword/casing variants collapse) — the form variants already collapse, so this may already hold; add only if a gap is found.

## /prototype (verify live — the fragmentation is INTERMITTENT)
Implement (1)+(2); run the 3-source diag (capture per-source model-spec `:type` names + AUTHOR `:entity-type` tags + final by-kind) ≥3 times. Confirm: institutions now appear under ONE kind (not `educational-institution` + `academic-institution-library`), across runs, even when the Model's own entity-types came back empty. If still fragmenting, root-cause WHY the AUTHOR ignores a non-empty model-spec (do not assume).

## TDD (tests FIRST, red→green, behavior through public fns)
1. **Backfill (pure):** given a model-spec with empty `:entity-types` + a vocabulary with canonical types, `orchestrate-extract-containers` (stubbed child-tick, capture forwarded model-spec) forwards a model-spec whose `:entity-types` = the vocabulary's canonical types. A NON-empty model-spec is left UNCHANGED (backfill only fills the gap).
2. **No-vocab / no-gap:** empty model-spec + no vocabulary → unchanged (honest, no crash).

## Do NOT touch
GM-1 (separate files). The pseo/wide-stats modeling. Reconcile/CQ-gate/embed. No baked entity names.

## Live-QA (`/inspect-orc`)
Reproduce the fragmentation diagnostic MYSELF ≥3 runs; confirm one institution kind (no name-split) even on empty-model-spec runs; the clean 3-source concept count is materially more stable; O\*NET junction sheets still extract (ER-1 preserved); ontology brick gate green; 0 orphan JVMs (`pgrep -f`).

## Core Disciplines (binding — verbatim)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse the GC-6 vocabulary + `normalize-vocabulary`; do NOT fork.
9. Adversarial qualitative verdict — hunt for the SAME entity under >1 type-name; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the deterministic backfill AND the AUTHOR's tagging under it.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Domain/format-agnostic — vocabulary/structural only; name NO entity.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
