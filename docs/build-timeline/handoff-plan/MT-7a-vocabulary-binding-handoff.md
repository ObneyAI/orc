# Handoff — MT-7a: vocabulary binding + enforcement seam

**Issue:** [`../issues/meaningful-multi-table-extraction/MT-7a-vocabulary-binding-enforcement.md`](../issues/meaningful-multi-table-extraction/MT-7a-vocabulary-binding-enforcement.md)
**Design (settled — do NOT re-litigate):** [`components/ontology/docs/adr/0001-canonical-vocabulary-binding.md`](../../../components/ontology/docs/adr/0001-canonical-vocabulary-binding.md) + `components/ontology/CONTEXT.md` §Vocabulary & identity.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `OPENROUTER_API_KEY` = shell env var only. **ONE bounded build at a time; `pgrep -f 'clojure|cpcache'` hygiene; confirm 0 this-repo orphan JVMs after live runs.**

## The problem (evidence-grounded — don't re-derive)
Per-container AUTHORs freelance entity-type names (`Occupation` vs `job-zones/occupation`; `Workplace Element` vs `Workplace`; case variants `job-element` vs `Job Element`) → the same real entity mints distinct canonical URIs → never merges → ~3971 nodes instead of ~1016, coverage diluted to ~23%. Also: `normalize-model-spec` silently coerces an unparseable `:entity-types` to `[]` and extraction PROCEEDS → guaranteed 100% freelancing (a silent-fallback violation on its own).

## The change (four pieces — re-orchestrate, no new machinery)

### 1. A pure vocabulary-binding ns (`ontology/core/vocabulary_binding.clj`, NEW)
- `canonical-types [model-spec]` → the declared vocabulary: `[{:type … :uri-keying-fields … :aliases …}]` (aliases tolerated when present — the model-spec schema is `{:closed false}`; absent → match on `:type` only).
- `resolve-entity-type [vocab entity-type]` → the canonical `:type` spelling, or nil. **Normalized-EXACT match** against `:type` + `:aliases` using the same case/separator normalization GC-1 uses (`normalize-key-name` in `extract_subbehavior.clj` ~806 — it's private; mirror it or make it public via a shared helper, do NOT fork a different normalization). **NO substring/fuzzy** — a test MUST assert `"job-zones/occupation"` does NOT resolve to `"Occupation"`.
- `bind-draft-types [vocab drafts]` → `{:drafts <matching drafts with :entity-type SNAPPED to the canonical spelling> :excluded [{:entity-type <as-emitted> :count n}]}` — non-matching drafts are EXCLUDED (they would only land as unfixable fragments) + counted honestly. Pure + total.
- `empty-vocabulary? [model-spec]` → true when `:entity-types` is nil/empty post-normalize.

### 2. The AUTHOR contract (static prompt, runtime enumeration)
The AUTHOR `:llm` node's instruction is STATIC (baked at sheet build); the model-spec arrives at RUNTIME as the `:model-spec` read the node already receives. So the binding block is static text: "your `entity-type` (in the aggregation-spec AND in every draft your transform mints) MUST be one of the `:type` values in the `model-spec` input, copied VERBATIM — never invent, rename, prefix, or re-case a type." Append it to BOTH author prompts (the resilient primary + fallback via `transform-author-prompt`/`robust-author-prompt`, and the flat path) exactly the way `cagg/aggregation-author-guidance` is appended today (`extract_subbehavior.clj` ~648, ~741). Reasoning-first (#13) unchanged.

### 3. The deterministic validation at the APPLY seam (`apply-transform-for-container-code`, ~508)
- **Aggregating path:** after `parse-aggregation-spec`, resolve the spec's `:entity-type` against the vocabulary — resolvable → snap to canonical spelling BEFORE `apply-aggregation-transform!`; unresolvable → do NOT run the aggregation with a freelanced type: treat as no-valid-spec (falls to the per-row branch only if a transform-source exists, else the container fails honestly).
- **Per-row path:** after `apply-extraction-transform!` returns, run `bind-draft-types` over the concept-drafts — snapped drafts proceed; excluded drafts are dropped with `:freelanced-drafts {:count n :types […]}` surfaced in the extraction report (never silently landed).
- **The re-ask comes FREE:** if binding excludes ALL drafts, `:concept-count` is 0 → the EXISTING EB9 resilience 0-draft gate (`extract-per-container-def` sanity `:condition` → troubleshoot → `:fallback` re-author) fires the fallback author — whose prompt now carries the binding block — then re-validates. Flat (non-resilient) mode: no re-ask, straight to the honest failure — consistent with flat mode having no resilience anywhere. Do NOT build a separate retry loop.

### 4. The empty-vocabulary hard stop (`orchestrate-extract-containers`, ~1471)
After `model-spec (normalize-model-spec …)`: `empty-vocabulary?` → FAIL LOUDLY (throw ex-info with an honest message) so the tree fails and the pipeline surfaces `:failed-at-model-extract` — extraction must NOT proceed container-by-container against no vocabulary. (This intentionally converts today's silent `[]` degrade into a loud stop; the Model subbehavior's own empty-entity-types re-ask gate remains the recovery path upstream.)

## Read-first (in order)
1. The ADR + CONTEXT.md §Vocabulary & identity (the settled design).
2. `extract_subbehavior.clj` — `apply-transform-for-container-code` (~508, the seam), the AUTHOR node defs both paths (~623-745), the EB9 resilience wrap (the 0-draft gate + `:fallback`), `normalize-model-spec` (~872), `normalize-key-name` (~806), `orchestrate-extract-containers` (~1455+).
3. `container_aggregate.clj` — `parse-aggregation-spec` / `aggregating-apply?` / `aggregation-author-guidance` (the MT-6 seam this extends).
4. `model_subbehavior.clj` — `model-spec-contract-schema` (the vocabulary's shape) + the existing empty-entity-types re-ask gate (~504).
5. The MT-7 supersede note + `development/src/mt7_resolution_prototype.clj` (why post-hoc resolution was rejected — context, not code to reuse).

## TDD cycle list (tests FIRST, red→green, behavior through PUBLIC fns)
1. **`resolve-entity-type` (pure):** exact match; case/separator variants (`job-element` → `Job Element`'s canonical spelling) snap; alias match when aliases present; `"job-zones/occupation"` does NOT resolve to `"Occupation"` (the no-fuzzy assertion); unknown → nil.
2. **`bind-draft-types` (pure):** matching-variant drafts snapped to canonical spelling; non-matching drafts EXCLUDED + counted per type; all-match → passthrough; empty drafts → honest zero.
3. **The APPLY seam (public `apply-transform-for-container-code`, real csv fixture like the MT-6 tests):** an aggregation-spec with a variant type → drafts land under the canonical spelling; a freelanced-type per-row transform → its drafts excluded + `:freelanced-drafts` in the report + `:concept-count` reflects the exclusion (so the EB9 gate can fire); a canonical-type author → behavior-preserving (byte-identical drafts).
4. **Empty-vocabulary hard stop (public orchestrator seam):** empty/unparseable `:entity-types` → loud failure, zero child ticks driven; a non-empty spec → unchanged behavior (existing orchestrator tests stay green).

(The LIVE proof — a real O\*NET multi-container run with ZERO freelanced schemes among landed drafts, the occupation split collapsing, and an honest failure surfacing when the model misbehaves — is the reviewer's `/inspect-orc`.)

## Do NOT touch
- MT-1/2/6 classifiers + gates, the chunk pager, MT-4/4b merge, GC-1 canonicalize (it now receives canonical types by construction — unchanged).
- The Model subbehavior's own re-ask gate (upstream recovery — separate).
- NO fuzzy/substring matching anywhere; NO domain names in code (#7/#12).

## Dependency rule
MT-7b (the proposal path) is handed off AFTER this lands + is inspected, from the REAL seam signatures produced here. Do not pre-build proposal handling; the binding must simply not preclude it (the exclusion + report shape is the natural extension point).

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that (a fuzzy snap that over-merges reads as "fixed" but is WRONG; exclusion that hides data loss without surfacing it is a false green).
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause (empty vocabulary = loud stop; freelanced drafts = excluded + surfaced, never silently landed).
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; NO hardcoded phrase matching / NO fuzzy or substring entity-type matching — normalized-EXACT against the discovered vocabulary only.
8. Re-orchestrate, NOT rewrite — reuse the EB9 0-draft resilience gate as the re-ask + the existing normalization; do NOT fork the author/apply paths or build a retry loop.
9. Adversarial qualitative verdict — hunt for a freelanced draft that slipped through, an over-merge via matching, or a silently-proceeding empty vocabulary; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — the vocabulary is LLM-discovered; the binding is deterministic; verify BOTH on real runs.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after; `pgrep -f`).
12. Domain/format-agnostic — the code enforces referential consistency against the model's OWN discovered vocabulary; name NO O\*NET/CIP/SOC column or entity; this is the general system TESTED with O\*NET, not built FOR it.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
