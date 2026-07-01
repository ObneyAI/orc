# GM-1 — Graph-context preview into the Model + grain/reify prompt

## Parent
[PRD: Graph-grounded modeling + order-independent reformation](../../prd/graph-grounded-modeling-and-reformation.md) · [Design](../../grill-sessions/wide-stats-observation-modeling.md)

## What to build
Make the Model `:llm` decide entity-vs-attribute-vs-observation LOGICALLY against the graph so far, instead of blind. Two coordinated pieces (both DOMAIN-AGNOSTIC — name NO pseo/O\*NET column):

1. **Graph-context preview → Model.** A pre-Model step that snapshots the accumulating graph and threads it into the Model's `:reads`, mirroring the GC-6 vocabulary pattern. REUSE the S20 orientation-card renderer (TBox digest = existing entity types + their keying-fields + predicates; plus a bounded ABox sample). The Model receives "these entity types already exist with these keys/predicates."
2. **Grain/reify prompt block.** Add to the Model prompt (DT3 model-node-prompt / `model_subbehavior.clj`) the standards-backed principle: numeric/aggregate columns are MEASURES of a grain; **reify them as an Observation node per grain-tuple IFF they are qualified by MORE THAN ONE subject dimension** (attach entity-dimensions to existing entities, carry measures as attributes); if qualified by a SINGLE subject, they are plain data-property attributes of that entity. Model against the existing schema — attach to existing entities, do not mint duplicates.

Layer-A only: this runs on the CURRENT build path (no mention log yet). It fixes pseo when entity-defining sources are processed first; full order-independence is GM-2/GM-3.

## /prototype (REQUIRED before TDD)
Live-capture the pseo model-spec WITH the graph-context + grain prompt in place (wrap the Model/extract seam, dump the authored entity-types), on a pseo-AFTER-ipeds run so `program` exists in context. Confirm the Model emits Observations with dimensions resolved to the existing program/institution (not per-row entities, not duplicated), and that O\*NET is unaffected. Tune the context content + prompt until it reliably does so, THEN write tests. (The prior attempt's failure was model variance — prove it live before trusting it.)

## Acceptance criteria
- [ ] The Model's `:reads` include a graph-context snapshot built from the orientation-card (schema digest + bounded sample); when the graph is empty (first source) it degrades gracefully.
- [ ] The grain/reify principle is in the Model prompt; names NO domain column.
- [ ] **LIVE (pseo after ipeds):** earnings modeled as Observations, entity-dimensions resolved to the existing program/institution (not duplicated), measures as native-number attributes, no whole-row bloat; earnings-bearing > 0.
- [ ] **LIVE regression gate:** the 3-source (IPEDS/crosswalk/O\*NET) 2/2 in-memory build stays at the GC-13 baseline (447 concepts / 338 rels / 0 dangling) — O\*NET and the clean sources UNCHANGED.
- [ ] TDD (red→green) on the pure graph-context snapshot builder (deterministic from projections) + the prompt-assembly seam; behavior through public fns.
- [ ] Recursive-only RLM; ontology brick gate green; 0 orphan this-repo JVMs.

## Blocked by
None — self-contained. /handoff can be written now.

## Handoff focus
Read-first: the S20 orientation-card (`orientation_card.clj`), GC-6 `synthesize_vocab_subbehavior.clj` + how vocabulary threads into the Model, `model_subbehavior.clj` (`model-prompt`, blackboard, C1 structured schema), `central_evolver.clj` (`delegate-model-extract!`, the pre-Model step seam), DT3 model-node-prompt in `discovery_tree.clj`. The change is a new pre-Model graph-context step + a prompt block + threading it via `:reads` — re-orchestrate, do not fork.

## Core Disciplines (binding — verbatim, no reinterpretation, no exceptions)
1. NEVER make assumptions; NEVER explain an LLM-node result as "variance/transient/flaky" — root-cause every unexpected behavior before proceeding.
2. Verify QUALITY not just completion — ask "how could this pass while still being wrong?" and test that.
3. Deeply debug to root cause with explicit instrumentation when a symptom resists hypothesis cycles.
4. Synthetic / "it ran" is the FLOOR. Live REAL Grain + REAL LLM + REAL source files is mandatory; no false green.
5. Never bypass a bug with a silent fallback; fix the root cause.
6. TDD: vertical tracer bullets, tests FIRST (red→green→refactor); behavior through PUBLIC interfaces.
7. Grain/ORC: commands→schema-validated events; assert events LANDED by reading projections; no bare appends; recursive-only RLM; no hardcoded phrase matching.
8. Re-orchestrate, NOT rewrite — reuse the orientation-card + GC-6 threading + reconcile/spine; do NOT fork the pipeline.
9. Adversarial qualitative verdict — hunt for where the output is WRONG (earnings lost/duplicated/mis-grained; clean sources inflated); surface honest negatives.
10. "Deterministic skeleton" ≠ LLM-free — verify BOTH the snapshot contract AND the Model's reasoning quality.
11. Real key = shell env var ONLY, never committed; never truncate model output; JVM hygiene (bounded runs, kill orphans, confirm 0 this-repo orphan JVMs after).
12. Domain/format-agnostic — name NO pseo/O\*NET column; graph/profile/model-spec-driven at runtime; NO hardcoded column lists or phrase matching.
13. Every `:llm` node writes `:reasoning` FIRST (node-scoped in concurrent contexts).
