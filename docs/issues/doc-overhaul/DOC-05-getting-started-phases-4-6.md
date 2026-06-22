## What to build

Complete `docs/GETTING-STARTED.md` with Phases 4–6: (4) GEPA instruction optimization, (5) ontology as general memory, (6) self-improving RLM. Appended to the file created by DOC-04. Same verification discipline: every code example runs, output is captured.

## Read first

1. `docs/GETTING-STARTED.md` current state (DOC-04 output — must exist)
2. `docs/COMPONENT-MAP.md` (DOC-01 output)
3. `components/gepa/src/ai/obney/orc/gepa/interface.clj`
4. `components/gepa/src/ai/obney/orc/gepa/core/metrics.clj`
5. `components/ontology/src/ai/obney/orc/ontology/interface.clj` — `seed-baseline-corpus!`
6. `components/ontology/src/ai/obney/orc/ontology/core/embedding.clj`
7. `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` line 2176
8. `docs/RLM-GUIDE.md` — recursive-default evidence
9. `docs/SELF-IMPROVING-LOOP.md` — alpha-state framing to mirror
10. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

**Yes — before writing each phase.**

Phase 4 (GEPA): Set up a trainset of 3 contract examples with expected output. Run `gepa/optimize!` with `make-judge-metric` as the metric-fn. Capture `{:status :completed :best-score N :best-candidate {...}}`. Confirm the instruction key is in `:reads`, not inline.

Phase 5 (Ontology): Run `ontology/seed-baseline-corpus!` to confirm it works. Run `ontology/embed-text` — confirm returns a vector of doubles. Run a simple `ontology/semantic-search-concepts` query. Confirm ColBERT absent does not break anything.

Phase 6 (Self-improving RLM): Build a `:repl-researcher` node with `:rlm {:auto-classify? true :recursive? true}`. Execute with a contract document. Capture the Phase 1 iteration outputs and the tree design. DO NOT run if ontology seed corpus + ColBERT index not available — document the graceful degradation instead.

## TDD cycle

- **Red:** Phases 4–6 don't exist in GETTING-STARTED.md yet.
- **Green:** Write each phase section with verified examples.
- **Refactor:** Verify GEPA independence (zero ontology dep from `gepa/deps.edn`). Verify RLM recursive-default from `executor.clj:2176`. Verify ontology BFS scoping issue is noted in Phase 5.

## Acceptance criteria

**Phase 4 — GEPA:**
- [ ] Clear framing: GEPA is for static `orc/llm` nodes with instructions in data; `:auto-classify?` is for RLM tree designers — these are orthogonal actuators
- [ ] Pattern shown: instruction as a blackboard key (in `:reads`), not inline string
- [ ] `gepa/optimize!` call with `make-judge-metric` as the metric-fn
- [ ] Pareto selection explained in one sentence; reflective mutation in one sentence
- [ ] Real captured output from prototype: `{:status :completed :best-score 0.8 ...}`
- [ ] "What you just added" callout: `gepa` + `evaluation` deps, no Python, no ontology
- [ ] Cross-reference to `docs/JUDGE-ARCHITECTURE.md` for how feedback → mutation works

**Phase 5 — Ontology:**
- [ ] Opening framing: the substrate is general-purpose (not "three-layer taxonomy"); use cases include document knowledge, user memory, any domain graph
- [ ] `build-from-sources` call with a small CSV of contract clauses
- [ ] `semantic-search-concepts` query shown — verified against real prototype run
- [ ] `ontology-id` isolation explained: multiple separate graphs coexist
- [ ] DJL embedding framing: no Python, default `all-MiniLM-L6-v2`, configurable via `:model-id` for any HuggingFace sentence-transformer
- [ ] ColBERT as optional upgrade: shows `hybrid-search` with `:signals #{:graph :embedding}` (no ColBERT) vs `#{:graph :embedding :colbert}` (with ColBERT)
- [ ] **Known issue callout**: BFS scoping (`expand-concept-neighborhood` walks all ontology-ids regardless of filter) — use `hybrid-search` with explicit signals instead of raw BFS until fixed. Verified from `retrieval.clj`.
- [ ] "What you just added" callout: `ontology` component + DJL Java libs, no Python

**Phase 6 — Self-improving RLM:**
- [ ] Opening framing: `:auto-classify?` exactly tunes this into the self-improving corpus loop — but recursive RLM works WITHOUT it
- [ ] `:repl-researcher` node shown inside the larger tree (not just as root node): survey `:llm` → research `:repl-researcher` → summarize `:llm`
- [ ] Recursive-is-default explained from code: `(not= false (:recursive? (:rlm node)))`
- [ ] Terminal mode noted as deprecated: requires `:rlm {:recursive? false}`
- [ ] `:auto-classify?` opt-in boundary: what turns on (R-Inject, classification, reranker, corpus prepend, judges, Living Description evolution)
- [ ] Honest alpha-state framing: what's solid today (in-distribution, drill-down, consolidation for stable patterns, `mint-behavior!` mechanics); what's rough (OOD force-fit, mint firing rate, hierarchical seed gaps)
- [ ] "What you just added" callout: `ontology` + `colbert` (Python required for self-improving loop), seed corpus via `seed-baseline-corpus!`

**All phases:**
- [ ] Every `:llm` node writes `:reasoning` first
- [ ] Progressive dep table kept current at the top of each phase
- [ ] "Coming soon" sidebar at the end: pluggable judge scales, subbehavior-specialist evolutionary builder, terminal RLM full removal after bench migration

## Do NOT touch

Phases 1–3 written by DOC-04. Any component source files.

## Live QA the orchestrator runs

Run Phase 4 GEPA example — confirm `optimize!` completes. Read `components/gepa/deps.edn` — confirm zero `ontology` dep. Read `executor.clj:2176` (`recursive-mode?` computation) — confirm matches Phase 6 description verbatim. Confirm ColBERT is absent and Phase 5 still works. Confirm 0 orphan JVMs.

## Blocked by

DOC-04 (Phases 1–3 must be complete and verified).

## Handoff note

Phase 6 is the hardest to verify because the full self-improving loop requires ontology seed corpus + ColBERT index + enough executions to trigger consolidation. If these aren't available, document the graceful degradation path as the verified behavior, and note what additional setup is needed for the full loop. DO NOT invent output from a run you didn't watch.