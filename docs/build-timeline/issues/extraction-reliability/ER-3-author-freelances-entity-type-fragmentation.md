# ER-3 — AUTHOR freelances `:entity-type` names (vocabulary fragmentation) when the model-spec is empty

## Parent
[Extraction reliability index](README.md)

## The bug (root-caused with instrumentation — the FULL chain, not assumed)

The remaining "concept-count variance" is intermittent **vocabulary fragmentation**: the SAME entity minted under 2–3 different type-names run-to-run (e.g. `institution` / `educational-institution` / `unitid`) → GC-1 keys the canonical URI on the type-name → the variants never merge → the count inflates.

A diagnostic capturing synth-vocab's output + each source's model-spec `:type` names + the AUTHOR's per-draft `:entity-type` tags nailed the chain:

- **GC-6 synth-vocab produced a CORRECT vocabulary** — ONE canonical `"Institution"` type (aliases: `ADM2022`, `School`, `Postsecondary Institution`), plus Educational Program, Occupation, Program-To-Occupation Mapping. So GC-6 is NOT the problem.
- **For ipeds + O\*NET the model-spec `:entity-types` was EMPTY (`[]`)** — the Model authored the entity-types as a MALFORMED EDN STRING, and `normalize-model-spec` (`edn/read-string`) couldn't parse it → `[]` (the same C1 parse fragility as ER-2, but here the string is un-parseable, not just un-coerced).
- **With an empty model-spec, the per-container AUTHOR FREELANCES `:entity-type` names** — the DT4 prompt says "tag `:entity-type` with the model-spec `:type` this concept IS" (`discovery_tree.clj` ~768), so with no `:type` available it invents one, INCONSISTENTLY:
  - ipeds: `{"EducationalInstitution" 200, :academic_institution_library 200}` — TWO names (one string, one KEYWORD) for the SAME 400 institutions, NEITHER matching the canonical `"Institution"`.
  - O\*NET: `{"Ability" 381, "WorkActivity" 381, :ability 139, :work-context-element 139}` — string-vs-keyword duplicates of the same types.
- **When the model-spec HAS types (crosswalk: `["Educational Program" "Occupation"]`), the AUTHOR tags CONSISTENTLY** (`{"Educational Program" 200, "Occupation" 200}`) → NO fragmentation. This is the control that proves the mechanism.

So: **empty/malformed model-spec `:entity-types` → the AUTHOR invents inconsistent `:entity-type` tags (string vs keyword, divergent names), ignoring the canonical GC-6 vocabulary → GC-1 fragments them.** Intermittent because the C1 parse of `:entity-types` is intermittent.

## Fix directions (to scope — MEASURE, don't assume which is sufficient)

1. **AUTHOR falls back to the GC-6 canonical vocabulary for `:entity-type`.** The canonical vocabulary is CORRECT and available; when the model-spec `:type` is absent/empty, the AUTHOR (or a deterministic post-step) should map each draft to the canonical `:type` (by the vocabulary's `:aliases`/`:description`) rather than invent a name. This is the robust fix — the vocabulary is the source of truth for type naming.
2. **Coerce `:entity-type` to ONE canonical FORM** before GC-1 canonicalization — a string `"Ability"` and a keyword `:ability` must not fragment (`normalize-key-name` already exists for type MATCHING; ensure the URI minting uses the normalized form, so string/keyword/casing variants collapse).
3. **Root the C1 parse** — reduce the intermittent malformed-`:entity-types` string (the deeper cause of the empty model-spec). Overlaps ER-2; may be the hardest and least reliable lever, so prefer (1)+(2) as the deterministic backstop.

## Acceptance criteria
- [ ] The SAME real entity gets ONE canonical `:entity-type` (hence ONE URI scheme) regardless of whether the model-spec carried types — proven LIVE across ≥3 runs (the fragmentation is intermittent, so multiple catches): `institution` appears under ONE kind, not 2–3.
- [ ] String-vs-keyword / casing variants of a type collapse (no `Ability` + `:ability` split).
- [ ] The clean 3-source concept count is STABLE across runs (the fragmentation-driven variance is gone).
- [ ] Domain-agnostic; TDD (red→green) on the deterministic type-normalization/vocabulary-mapping (pure); ontology brick gate green; 0 orphan this-repo JVMs.

## Blocked by
None. Builds on ER-1/ER-2 (committed) + GC-6 synth-vocab (exists).

## Handoff focus
Read-first: `discovery_tree.clj` DT4 `transform-node-prompt` (~768, the `:entity-type` tagging instruction), `model_subbehavior.clj` `vocabulary-constraint-block` (~292, the canonical vocabulary the Model reads), `synthesize_vocab_subbehavior.clj` (`:canonical-entity-types` + `:aliases`), `rlm_discovery.clj` / `extract_subbehavior.clj` GC-1 `canonicalize-drafts` + `normalize-key-name` (where the URI is minted from `:entity-type`). Re-orchestrate — reuse the vocabulary + `normalize-key-name`; do NOT fork. One bounded build at a time; `pgrep -f` hygiene.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse the GC-6 vocabulary + `normalize-key-name`; do NOT fork.
9. Adversarial qualitative verdict — hunt for the SAME entity under >1 type-name; surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the deterministic type-normalization AND the AUTHOR's tagging.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Domain/format-agnostic — name NO entity/vertical; vocabulary/structural only.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
