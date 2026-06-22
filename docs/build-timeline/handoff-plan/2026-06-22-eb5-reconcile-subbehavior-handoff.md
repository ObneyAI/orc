# EB5 Handoff — Reconcile subbehavior (entity + attribute granularity, check-before-mint)

Fresh-context brief for EB5 (`docs/build-timeline/issues/evolutionary-builder/EB5-reconcile-subbehavior.md`).
Crafted post-merge from EB4's REAL draft contract + the REAL reconcile signatures.
Work DIRECTLY on `feature/ontology-architecture` (NOT a worktree). DO NOT COMMIT —
leave staged; the orchestrator runs `/inspect-orc` then commits **locally (no push)**.
Implement via `/tdd`.

## The goal
The **Reconcile** subbehavior as a delegatable ORC sheet (mirror EB2/EB3/EB4's
registry pattern: `dsl/workflow` + `dsl/build-workflow!` + `dsl/sheet-id-for-name`,
via `orc-service.interface`). It links across sources AND against the CURRENT graph
state at TWO granularities — entities AND their attributes/features — with
**check-before-mint** (P3 hybrid search finds existing matches BEFORE minting, so
identity is evidence-grounded, not label-cosine). It REUSES the proven reconcile
machinery (no fork) and re-houses DT7's against-graph-state pass.

## Read first
1. `components/ontology/src/ai/obney/orc/ontology/core/extract_subbehavior.clj` (~L99–112) — EB5's INPUT contract: EB4's `:concept-drafts`/`:relationship-drafts` (vectors, the `compile-discovery-source!` draft shape) + `:extraction-report`.
2. `components/ontology/src/ai/obney/orc/ontology/core/discovery_tree.clj`:`reconcile-graph!` (~L1850) — the RE-HOUSE target. Reads the CURRENT graph for `:ontology-id` (NOT empty), links entities cross-source, REUSES S03 (alignment) + S12 (dedup cascade + LSH + project-once) + V18 (integrity); `:llm-budget` DEFAULT 0 (deterministic; ambiguity-band → `:requires-review`, never silently merged); optional `:source-uri-sets` for the shared-URI report. Returns `{:status :ok :concepts-in-scope :shared-uri-links :candidate-pairs …}`.
3. `components/ontology/src/ai/obney/orc/ontology/interface.clj`:`compile-discovery-source!` (~L477 — land the drafts as events) ; `hybrid-search` (~L1398) / `hybrid-retrieval` (~L1119) — the P3 BFS+embedding+ColBERT probe for **check-before-mint**.
4. `components/ontology/src/ai/obney/orc/ontology/core/deterministic_skeleton.clj` (~L26) — the check-before-mint hook "fires inside S12's command"; align with it (don't bypass).
5. `components/ontology/src/ai/obney/orc/ontology/core/rlm_discovery.clj`:`reconcile-current-graph-integrity!` (~L1473) — V18 integrity re-check.
6. `model_subbehavior.clj` / `extract_subbehavior.clj` — the sheet/registry + C1 patterns.

## What EB5 must BUILD (re-house + two deepenings)
- **Compose `reconcile-graph!` as the sheet's entity-reconcile node** (re-house; reuse, no fork) — reads CURRENT graph for `:ontology-id`, links entities cross-source, surfaces `:requires-review` ambiguities (V18), 0 dangling.
- **DEEPENING 1 — check-before-mint:** BEFORE minting a draft concept, probe the current graph via `hybrid-search` (P3) for an existing match; the probe INFORMS the mint/merge decision (evidence-grounded, S13), not only a downstream dedup. Acceptance: this probe is verified to fire pre-mint.
- **DEEPENING 2 — attribute/feature granularity:** beyond `reconcile-graph!`'s entity-level links, connect a new entity's ATTRIBUTES/FEATURES to existing entities' attributes/features (the NEW requirement DT7 never reached — attributes as first-class, relatable). This is the genuinely-new logic EB5 adds.
- **Against-graph-state (maintain seam):** a SECOND pass on a pre-populated graph reconciles-not-duplicates (DT7-proven). Verify reconcile reads CURRENT state (not empty).

## Node mix (implementer decides, but)
Reconcile is largely DETERMINISTIC (`reconcile-graph!`, dedup, hybrid-search are `:code`/evidence — NOT LLM label-cosine). Use `:code` nodes for the reconcile/probe/merge; an `:llm` node ONLY for genuinely-ambiguous adjudication (if you raise `:llm-budget`>0) — and then `:reasoning` FIRST (#13), node-scoped if concurrent. Do NOT mint on label-cosine; do NOT hardcode phrase matching (#7/#12).

## C1 / contract
The sheet's output is a reconcile REPORT map (merges, `:requires-review` ambiguities, integrity, attribute-links). A `:code` node returns it native → parsed across `:delegate`. If any `:llm` node emits a map, give it a STRUCTURED `[:map …]` schema (the EB3 C1 lesson). Assert events LANDED by reading the projection back (discipline 7), not a return value.

## Do NOT
- Fork `reconcile-graph!` / S12 dedup / S03 alignment / `hybrid-search` / V18 (reuse). Mint on label-cosine. Touch EB1–EB4 or unrelated files. Commit. Push. Create a worktree.

## Prototype (WORTH — do first)
Prove entity + attribute-level cross-source merge + check-before-mint against a
pre-populated graph: land source-A drafts, then reconcile source-B drafts and show
(a) a check-before-mint hybrid probe fired, (b) a B entity merged to an existing A
entity (reconcile-not-duplicate), (c) a B attribute linked to an existing A
attribute. Capture it.

## Verify (orchestrator independently re-runs)
- `/tdd` red→green: sheet built + registered; delegated per-source drafts + `:ontology-id`; reconcile runs against CURRENT graph; round-trips via the projection (discipline 7).
- **LIVE verify (REAL Grain + REAL ColBERT/embeddings for the P3 probe):** real cross-source reconcile (entity + attribute) on real sources; reconcile-NOT-duplicate on the 2nd pass; check-before-mint probe fired; 0 dangling; `:requires-review` ambiguities surfaced honestly (not silently merged). Capture verbatim (`docs/build-timeline/live-verify/EB5-reconcile.md`).
- **Gate hygiene (just established):** the live verify drives real ColBERT hybrid-search → it is an INTEGRATION test → place it in `development/ontology-integration/ai/obney/orc/ontology/` (on-demand `:dev:test`), NOT the brick gate. A fast hermetic contract test (structure/contract/reuse, no ColBERT/LLM) stays in `components/ontology/test`. Decide per measured runtime; report which + why.
- Green under BOTH `clj -M:poly test brick:ontology` AND `:dev:test` of the EB5 test ns.
- JVM hygiene: bounded runs; 0 orphan THIS-repo JVMs after (exclude sibling worktrees `orc-gepa-metric`/`orc-main`/etc. — kill only your own by PID; if you spawn a ColBERT bridge, kill only the PID your run spawned).

## Report back (raw data)
The sheet + node design; the prototype result (the 3 proofs above); the LIVE-verify capture (the reconcile report verbatim — merges, attribute-links, ambiguities, dangling=0, the check-before-mint probe evidence); which lane the test went in + why; dual-runner totals; "0 orphan THIS-repo JVMs"; every file changed by path; honest negatives. DO NOT COMMIT/PUSH. Binding Core Disciplines block 1–13 (in the EB5 issue) in force verbatim.
