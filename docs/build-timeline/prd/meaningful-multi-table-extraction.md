# PRD — Meaningful multi-table extraction

Parent for the MT issue line (`docs/build-timeline/issues/meaningful-multi-table-extraction/`). Grounded in the O\*NET due-diligence investigation (each finding verified in code / filesystem / the A2 reference artifacts). Local markdown, date-free, ordered by story.

## Problem Statement

When a source has MANY containers (a SQL database, a directory of Excel tables), the pipeline extracts the WRONG ones and models them shallowly:

- **Wrong containers.** `orchestrate-extract-containers` does `(take cap (list-source-containers source))`, and the excel-dir tool lists containers `(sort-by getName)` — alphabetical. For O\*NET (`db_30_1_excel`, 45 tables) the two alphabetically-first `.xlsx` are `Abilities to Work Activities.xlsx` and `Abilities to Work Context.xlsx` — **junction/bridge tables that carry NO occupation data.** So the dev-cap build extracts exactly the noise and never the occupation-centric tables (`Occupation Data`, `Skills`, `Knowledge`, …). This is the root cause of every O\*NET "junction sheet" symptom chased earlier (both the recovery of those tables and the suppression of them were chasing noise).
- **No aggregation.** The extraction transform is strictly PER-ROW, so a long-form table (`Skills`: occupation × skill-element × scale → value, ~91 skills/occupation) yields ~91 fragment drafts per occupation and never a rolled-up per-occupation attribute.
- **No occurrence-merge (suspected).** `create-concept` mints a fresh concept per draft and reconcile does attribute-LINKING, not attribute-UNION across same-key drafts — so an occupation named in `Occupation Data` and rated in `Skills` may not become one node carrying both.

Net effect on the A2-vs-B head-to-head: graph B looks artificially bad on O\*NET (a core source) for a FIXABLE pipeline reason, making the comparison unfair to B.

## Solution

A general "meaningful multi-table extraction" capability (domain/format-agnostic — no baked O\*NET/CIP/SOC names):

1. **Container selection** replaces blind take-first-N. A deterministic shape-classifier drops bridge/reference noise and tags each container's shape (entity / long-form / wide-stats); the Survey/Model then ranks the survivors by relevance to the goal, bounded by the cap.
2. **Long-form aggregation.** The Model can author an AGGREGATING transform (group-by entity key → rank by importance → top-N element array as a flat attribute), applied streamed/bounded.
3. **Occurrence-merge.** Per-entity drafts from different containers union their attributes into one node (verify reconcile/landing does this; build if absent).

The target model is A2's: occupations as ONE flat-attribute node type with `topSkills`/`topKnowledge`/`jobZone`/`riasecCode` — and because A2's own `topSkills` are mostly empty, graph B that populates them BEATS A2 on O\*NET depth. This aligns with GM-1's grain-reify (long-form element column = a layout artifact = the keyed subject's flat attributes; no Skill nodes, no reification).

## User Stories

1. As the pipeline, when I ingest a many-table source, I want to skip pure bridge/junction tables (two code columns, no measure), so that I don't mint cross-product noise A2 discards.
2. As the pipeline, I want to skip tiny reference/lookup tables (code→label dictionaries), so that I model real entities, not the source's internal vocabulary.
3. As the pipeline, I want to tag each surviving container's shape (entity / long-form / wide-stats), so that downstream modeling handles each correctly.
4. As the pipeline, I want to rank the surviving containers by relevance to the goal and extract the top ones within the cap, so that I spend the extraction budget on the containers that matter.
5. As the Model, I want to author an aggregating transform over a long-form container (group-by key, rank by importance, keep top-N element labels), so that `Skills` becomes one occupation node with a populated `topSkills` array, not 91 fragments.
6. As the pipeline, I want an aggregating transform applied streamed/bounded, so that a 92k-row container never materializes whole (no OOM).
7. As the pipeline, when the same real-world entity appears in multiple containers, I want to union its attributes into one node, so that an occupation carries its title (from one table) AND its top skills (from another).
8. As the evaluator, I want a comprehensive O\*NET build to produce occupations with populated `topSkills`/`topKnowledge`/`jobZone`/`riasecCode`, so that the A2-vs-B comparison is fair and B can beat A2's sparse O\*NET.
9. As a maintainer, I want all of this domain/format-agnostic (structural signals only), so that it works for any many-table source, not just O\*NET.

## Implementation Decisions

- **Container shape-classification is DETERMINISTIC** (structural: column count, key cardinality, presence of a measure/value column, an element/label column, row count). It drops bridge (2 key-columns, no measure) + reference (small, code→label) containers and tags entity / long-form / wide-stats. No LLM in the noise-drop — reliability over flexibility (the "deterministic skeleton" principle).
- **Relevance ranking is LLM/Model-driven** but scoped: it ranks only the containers that survived the structural pre-filter, against the goal — it decides RELEVANCE, never identity, so it cannot fragment the graph.
- **Selection is Survey-driven.** The Survey already profiles the source and holds the per-medium tool catalog (`list-containers`, `sheet-columns`, `count-rows`) — extend it to profile EACH container's cheap shape signal + produce a ranked, bounded container list the Extract step consumes (replacing `(take cap …)`).
- **Aggregation is a Model-authored aggregating transform** (group-by/top-N in code), not a rigid deterministic rollup — the aggregation is discovery, and unlike URIs it can't fragment identity. The extract path must accept a transform that emits one draft per GROUP, applied streamed/bounded.
- **Model depth is FLAT top-N array attributes** — matches A2 and GM-1's grain-reify. No Skill nodes, no reification of the skill/element label.
- **Occurrence-merge**: prefer reusing reconcile/landing to union same-key drafts' attributes; MT-4 first VERIFIES current behavior on real drafts, then builds the union only if absent.
- **Bounding preserved.** The cap (MC-0) and GC-13 parallelism stay; selection ranks-then-bounds rather than removing the cap. Comprehensive builds raise the cap deliberately.

## Testing Decisions

- Test behavior through public interfaces on REAL source data (the O\*NET `db_30_1_excel` directory), never synthetic-only. A build that "completed" with junction-noise concepts or 0 populated `topSkills` is a FAIL (no false-green).
- **MT-1** (shape-classifier): pure structural classification is unit-testable — feed real container headers/samples (a 2-code bridge, a long-form element table, an entity table, a tiny lookup) and assert the shape + keep/drop verdict. Prior art: the existing `mechanical-sample-rows` / `sheet-columns` container introspection.
- **MT-3** (aggregating transform): the group-by/top-N rollup is verifiable on a long-form sample (assert one draft per key with a top-N array); the streamed/bounded application is verified on the full 92k-row container (no OOM, correct top-N).
- **MT-4** (occurrence-merge): drive two containers' drafts for the same key through reconcile/landing and assert ONE node with unioned attributes (read the projection back — no bare append).
- Reused-capability suites (Survey, reconcile, extract, GC-13) must stay green — re-orchestrate, don't fork.

## Out of Scope

- The richer Skill-node model (shared skill nodes + weighted edges) — explicitly deferred; GM-1 + A2 both say flat.
- ER-3 institution fragmentation (a separate ipeds issue; independent of O\*NET).
- Wages/earnings (a separate la-oews source, not O\*NET).
- The A2-vs-B comparison harness itself (MT-5 triggers it; its design lives in the EB12/BRYC docs).

## Further Notes

- Grounding: A2 models O\*NET as ONE flat-attribute occupation node type (both `louisiana_programs_full.ttl` and `.bryc-graph-cache-with-embeddings.json` agree); no ability/activity/junction/observation nodes. `topSkills`/`topKnowledge`/`topTasks` exist but are mostly empty in the cache → B can beat A2 by populating them.
- The shape-classifier is the shared spine: MT-1 tags long-form; MT-2 selects it; MT-3 aggregates it; MT-4 merges it.
- The 13 Core Disciplines apply and are embedded verbatim in each issue.
