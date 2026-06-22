# EB7 Handoff — Embed+Index subbehavior (GUARANTEED auto-embed + ColBERT, closes P2)

Fresh-context brief for EB7 (`docs/build-timeline/issues/evolutionary-builder/EB7-embed-index-subbehavior.md`).
Crafted post-merge from EB3's REAL `embed-fields` signal + the REAL embed/ColBERT
signatures. Work DIRECTLY on `feature/ontology-architecture` (NOT a worktree). DO NOT
COMMIT/PUSH — leave staged; the orchestrator `/inspect-orc`s then commits locally.
Implement via `/tdd`.

## The goal + the drift it closes
The **Embed+Index** subbehavior as a delegatable sheet (mirror EB4/EB5/EB6 registry
pattern via `orc-service.interface`). It is a **GUARANTEED step**: on a built graph,
it auto-detects embed-worthy fields (informed by EB2/EB3's `embed-fields` signal),
**embeds** the concepts, and **ColBERT-indexes** them — BY DEFAULT, with no caller
wiring. This closes the **P2 drift**: `deterministic_skeleton.clj` L34–37 — `build!`'s
`:embed` and `:index` stages **DEFAULT TO SKIP** (`:skipped? true`) unless a caller
supplies the embed/reindex fns. So semantic retrieval silently doesn't happen. EB7
makes embed+index first-class + automatic, driven by the Model's `embed-fields`.

## Read first
1. `components/ontology/src/ai/obney/orc/ontology/core/model_subbehavior.clj` (~L79–86, L148) — EB3's `:embed-fields` write (`embed-fields-key`, `[:vector :string]`) — the EB7 INPUT signal (the embed-worthy fields the Model committed; EB2's profile surfaced the candidates). Plus the sheet/registry + C1 pattern (and `extract_subbehavior.clj`/`reconcile_subbehavior.clj` for the `:code`-orchestration pattern).
2. `components/ontology/src/ai/obney/orc/ontology/core/embedding.clj`:`embed-concepts-batch!` (~L453) — REUSE (no fork): the production batch-embed (DJL). Note F1 (embed batching at scale — `docs/build-timeline/issues/discovery-tree/DT-followups.md`).
3. `components/ontology/src/ai/obney/orc/ontology/interface.clj`: `detect-embeddable-fields` (~L1247) / `detect-embeddable-fields-heuristic` (~L1588) / `analyze-fields-for-embedding` (~L1539, LLM-based) — the detectors (prefer the heuristic + the EB3 signal; only use the LLM analyzer if no signal). `bootstrap-reindex!` (~L548 → `todo-processors/maybe-rebuild!`, the RF4-fixed coalescing-latch reindex — builds the ColBERT `ontology-descriptions` index, idempotent). `hybrid-search` (~L1398) — to VERIFY semantic retrieval works.
4. `deterministic_skeleton.clj` (~L34–37) — the `:embed`/`:index` skip-default this closes.

## What EB7 must build
A sheet `ontology-embed-index/...@v1`. Reads `[:ontology-id :embed-fields]` (+ the
graph via `:ontology-id`) → writes an embed+index report `[:embedded-count
:index-id :embed-fields-used …]`. Deterministic-leaning (EB3 already committed the
`embed-fields`, so NO `:llm` is needed — prefer `:code` nodes):
- **`:code` DETECT** — resolve the embed-worthy fields: use EB3's `:embed-fields` signal as the primary source; fall back to `detect-embeddable-fields-heuristic` on the graph schema when the signal is absent. Domain-agnostic (the fields come from the model/graph, no hardcoded vocab).
- **`:code` EMBED** — `embed-concepts-batch!` over the in-scope concepts on the resolved fields (reuse, no fork). Embed events LAND (concept embeddings on the projection — read back).
- **`:code` INDEX** — `bootstrap-reindex!` / `maybe-rebuild!` so the ColBERT `ontology-descriptions` index is built BY DEFAULT (not skipped). Confirm the index exists.

## Do NOT
- Fork `embed-concepts-batch!` / the detectors / the reindex (reuse). Hardcode embed-field vocab (driven by the model/graph). Leave embed/index optional (the WHOLE point is GUARANTEED-by-default). Touch EB1–EB6 or unrelated files. Commit/push. Create a worktree.

## Prototype (WORTH — do first)
On a real built graph, prove: the embed-fields resolve from the Model's `:embed-fields`, `embed-concepts-batch!` fires, the ColBERT index builds, and `hybrid-search` returns a LABELED, semantically-correct hit — all WITHOUT any caller wiring the embed/index fns. Capture it.

## Verify
- `/tdd` red→green; sheet built + registered; delegated `[:ontology-id :embed-fields]`; embed events LAND (read the projection back — discipline 7); the ColBERT index is built (confirm via the index read-model / `list-indexes`).
- **LIVE verify (REAL Grain + REAL DJL embeddings + REAL ColBERT bridge):** real graph → auto-embed + ColBERT-index fire BY DEFAULT → `hybrid-search` returns labeled, semantically-correct hits. Capture verbatim (`docs/build-timeline/live-verify/EB7-embed-index.md`): the embed count, the index id, a sample hybrid-search query + its semantic hits.
- **Gate hygiene (load-bearing):** EB7's core behavior IS embed + ColBERT (needs DJL + the bridge), so the LIVE verify is an INTEGRATION test → place it in `development/ontology-integration/ai/obney/orc/ontology/` (on-demand `:dev:test`), NOT the fast brick gate. A fast hermetic test (structure/contract/wiring + the embed-fields-resolution logic, with embedding/ColBERT stubbed or `:signals` limited) stays in `components/ontology/test`. Decide per measured runtime; report which.
- Green under BOTH `clj -M:poly test brick:ontology` AND `:dev:test` of the EB7 ns.
- JVM hygiene: bounded runs; 0 orphan THIS-repo JVMs after (exclude sibling worktrees — kill only your own by PID; if you spawn a ColBERT bridge, kill only the PID your run spawned).

## Report back (raw data)
Sheet + node design; the prototype result (embed+index fired by default, hybrid-search semantic hit); the LIVE capture (embed count + index id + a real hybrid-search query→hits, verbatim); which lane the test went in + why; dual-runner totals; "0 orphan THIS-repo JVMs"; F1 batching note; every file changed by path; honest negatives. DO NOT COMMIT/PUSH. Binding Core Disciplines block 1–13 (in the EB7 issue) in force verbatim.
