# PRD — Discovery Behavior-Tree Redesign

**Date:** 2026-06-16 · **Branch:** `feature/ontology-architecture` · **Local PRD** (not an external tracker issue).
**Parent context:** the ontology substrate/builder rebuild (`docs/build-timeline/prd/2026-06-12-ontology-substrate-and-builder-rebuild.md`) and the verification phase (`docs/build-timeline/prd/2026-06-15-ontology-verification-and-bryc-comparison.md`). Design source: the design grill recorded in the working session (decisions Q1–Q7).

---

## Problem Statement

Ontology discovery currently runs as a single open-ended recursive-RLM loop
(`run-discovery!`) per source, driven by one enormous instruction. Every failure
mode we hit while building real graphs got answered by appending another
paragraph to that instruction — model the program as a node not an edge; cover
the whole source not one window; mint at the right grain; honor the goal's scope.
The instruction is now ~9 distinct guidance blocks / ~400 lines of prompt string,
and a single free-form loop still keeps *dropping* one of them: it dumps raw rows
instead of entities, or forgets to scope to the region the goal named, or models
recommendation targets as edges with no nodes to recommend.

This is a prompt-bloat treadmill: more prose does not *structurally* guarantee a
step happens. The recent real builds proved it — the autonomous builder produced
a coverage-incomplete, partly-dangling graph (V17), and when full-extraction was
wired in, a naïve single transform over-extracted an entire national table
(V20 live verify). The capability is there; the *orchestration* is the problem.

ORC is a behavior-tree execution engine. We are fighting its grain by cramming a
multi-step methodology into one prompt instead of expressing it as a tree of
focused steps.

## Solution

Replace the monolithic open-ended discovery loop with a **cohesive discovery
behavior tree**: a small set of *focused* repl-researcher reasoning nodes — each
with a small, single-purpose prompt — sequenced by the tree, feeding the existing
deterministic skeleton, with an adaptive competency-question loop wrapped around
it.

Each step a node, so the step is *structurally guaranteed* (you cannot skip the
"decide grain + scope" step when it is its own node with the profile as explicit
input). The mega-prompt decomposes into per-node prompts an order of magnitude
smaller. The deterministic spine (`build!`) and every capability we already
built (source tools, full-extraction apply-step, referential integrity, dedup,
alignment, evidence, hybrid retrieval, CQ runner) are **reused unchanged** — this
is re-orchestration, not a rewrite.

The shape, two levels:

```
GRAPH-LEVEL ORCHESTRATION  (one tree; per-medium tool-leaves; hybrid)
  goal/intent ───────────────── orients every node (input, not a node)
  profile-all sources         ← per-source PROFILE node ×N
  derive competency questions  (grounded in the profiles, goal-anchored, HITL-reviewable)
  PER-SOURCE SUB-TREE ×N:  Model+grain+scope → Transform-design+validate → [deterministic full-extract]
  → build!  (normalize → dedup S12 → evidence S13 → validate S10/11 → embed P2 → index)
  cross-source link / reconcile   (against CURRENT graph state)
  CQ-gate (S15) ──fail──→ RLM inspects (drill-down) → FOCUSED re-extract/re-link
                 └─pass──→ done
```

The user gets a discovery process that reliably models entities as nodes, covers
sources comprehensively, scopes to the goal, links across sources, and pursues a
*goal* (the competency questions) rather than running once and stopping — with
each behavior improvable in isolation, and an honest "this question is
unanswerable from these sources" instead of an infinite loop or a false green.

## User Stories

1. As a greenfield ontology builder, I want discovery decomposed into discrete steps, so that each step (profile, model, transform) is guaranteed to run instead of being one bullet a single prompt can drop.
2. As a greenfield builder, I want the model to mint real-world entities as concept NODES (not only as edges), so that the graph has things to retrieve, describe, and recommend.
3. As a greenfield builder, I want extraction to cover the whole source, not the first sampled window, so that the graph is comprehensive.
4. As a greenfield builder, I want the model to honor the scope the goal states (a region, subset, or time window), so that I get the graph the goal asked for rather than a raw-table dump.
5. As a greenfield builder, I want the model to mint at the right entity grain (one concept per real entity, breakdown rows collapsed or modeled as their own entities), so that the graph is knowledge, not raw rows.
6. As a structured-source consumer (CSV), I want a CSV-specialist set of tools and a CSV-targeted leaf, so that discovery reads my source idiomatically.
7. As a structured-source consumer (SQL/SQLite), I want a SQL-specialist leaf with schema/sample/query/stream tools, so that discovery explores my database by shape without loading it.
8. As a structured-source consumer (Excel), I want an Excel-specialist leaf with sheet/columns/sample/stream tools, so that discovery handles workbooks and folders-of-workbooks.
9. As a structured-source consumer (text), I want a text-specialist leaf, so that prose sources participate in the same tree shape.
10. As any structured-source consumer, I want the SAME discovery steps regardless of medium, so that quality is consistent and improvements apply everywhere ("separate tool users, same goal").
11. As the author of the Profile node, I want a small focused prompt that only characterizes the source (entity candidates, identifying keys, scope fields, linking keys, breakdown/grain signals), so that it does one job well and is independently testable.
12. As the author of the Model node, I want a small focused prompt that only decides entity types, URI-keying, grain strategy, and the scope filter from the goal + profile, so that the grain/scope decision is a first-class guaranteed step.
13. As the author of the Transform node, I want a small focused prompt that only designs and sample-validates the per-row extraction transform, so that the transform is correct before it runs at scale.
14. As an RLM node author, I want each node's prompt assembled through a single seam, so that nodes can later be promoted from static prompts to living, self-improving behaviors without rewriting the node.
15. As a maintainer of the self-improving loop, I want the promotion seam to be a clean interface boundary not coupled to current minting internals, so that the in-flight minting rework can plug in later without breaking discovery.
16. As a builder pursuing a goal, I want competency questions derived AFTER profiling, so that the questions fit what the sources actually contain rather than being authored blind.
17. As a builder, I want the goal/intent to orient every node from the start, so that profiling and modeling look for the right kinds of things even before CQs are formalized.
18. As a reviewer, I want the derived competency questions surfaced for HITL review/override, so that the load-bearing exit gate is inspectable, not silently trusted.
19. As a builder, I want a CQ-driven loop that re-extracts to close a gap when a competency question fails, so that discovery seeks the goal instead of running once.
20. As a builder, I want the loop to distinguish "re-extract to close the gap" from "honestly unanswerable from these sources" and surface the latter, so that it never spins on an impossible question and I learn what the sources genuinely cannot answer.
21. As a builder, I want recovery to be a focused single-node re-run (reading surviving sandbox-vars + the failure), so that one failed step doesn't force a full rebuild.
22. As a builder of a connected graph, I want cross-source linking to run as a reconciliation pass over the current graph state, so that entities from different sources merge and ambiguities are surfaced.
23. As an ontology maintainer, I want the reconciliation stage written against current graph state (not an empty graph), so that the deferred maintain/incremental branch drops in cleanly later (see the maintain handoff).
24. As ORC itself, I want the discovery tree to be a behavior tree I can compose as a node inside a larger tree, so that discovery is a first-class workflow primitive.
25. As a verification reviewer, I want to read each node's output and the CQ verdict, so that I can judge discovery quality step-by-step rather than only at the end.
26. As a verification reviewer, I want the deterministic skeleton (`build!`) left intact, so that the proven spine isn't a regression risk in this redesign.
27. As a consumer in any domain, I want discovery to carry no industry-specific tuning, so that the same builder works for education, healthcare, finance, or anything — its focus coming from the runtime goal/docs.
28. As an operator, I want each node and the whole tree verified on real sources with a real LLM before "done", so that synthetic green is the floor, not the ceiling.

## Implementation Decisions

Organized by module. Existing component: `ai.obney.orc.ontology` (discovery,
skeleton) + `ai.obney.orc.orc-service` (repl-researcher node, sandbox, source
tools). No file paths/snippets except the prototype-derived contract shapes.

### M1 — Tree orchestration (hybrid: fixed core + RLM-chosen branches)
- A fixed core node-sequence whose steps are structurally guaranteed; RLM-chosen
  branches only at the edges: recovery; CQ-driven re-extract; greenfield-vs-
  maintain (maintain deferred); full-extract-vs-inline for sources small enough
  that the sample already covers them.
- Two-level structure: a graph-level orchestration (profile-all → derive CQs →
  per-source extract → link/reconcile → CQ-gate → loop) wrapping a per-source
  sub-tree (model → transform → extract). Profiling is the early per-source step
  whose results feed graph-level CQ derivation.
- Built on the existing `:repl-researcher` node + tree DSL; the discovery tree is
  itself composable as a node in a larger behavior tree.

### M2 — The three focused reasoning nodes (per source)
- **Profile** — characterize the source via the medium's specialist tools; emit a
  profile.
- **Model + grain + scope** — read goal + profile; decide entity types, URI-keying
  fields, grain strategy, scope filter; emit a model-spec.
- **Transform-design + sample-validate** — read model-spec + a sample; design the
  per-row extraction transform and validate it on the sample; emit the transform.
- One tree shape; the medium's specialist tools are bound at the leaves
  (csv/sql/excel/text). Same steps, different tools.
- **Inter-node contract** (passed via the blackboard; read with the existing
  `node-output` / `node-input-profile` drill-down primitives). Shapes (these
  encode the decision precisely):
  - Profile → `{:entity-candidates [...] :identifying-keys {...} :scope-fields [...] :linking-keys [...] :grain-signals [...] :sample [...]}`
  - Model → `{:entity-types [{:type ... :uri-keying-fields [...] :grain-strategy (:canonical-row-filter | :breakdown-as-entity)}] :scope-filter ... :edges [...]}`
  - Transform → `{:transform-source "(fn [row] {:concept-drafts [...] :relationship-drafts [...]})" :selector "<table-or-sheet>"}` (the V20 extraction-transform shape, sample-validated)

### M3 — Requirements / competency-question node
- A graph-level node that, AFTER profiling all sources, derives competency
  questions from goal ⨯ the profiles (grounded + goal-anchored), HITL-reviewable
  and consumer-overridable. The CQs persist as the S14 ORSD spec that S15 judges.
- Consumer-supplied CQs seed/override the derived set. For builds with CQs already
  in hand, the derivation is skipped/seeded.

### M4 — Deterministic full-extraction integration
- Reuse the V20 apply-step verbatim as the bridge from a Model/Transform output to
  the full source: stream the whole source (V19 `stream-all`), apply the per-row
  transform, count + surface per-row errors, no abort, no false green. The
  Transform node produces the transform; this stage applies it.

### M5 — Cross-source linking / reconciliation (against current graph state)
- A graph-level reconciliation pass AFTER per-source extraction, using S03
  alignment registry + S12 dedup cascade + S21 embeddings/ColBERT + V18
  referential integrity. This is where "discover ambiguities" lives.
- **Load-bearing seam:** the stage reads EXISTING concepts/relationships from the
  projection and reconciles against current graph state — it does NOT assume an
  empty graph. This is the single thing that makes the deferred maintain branch a
  clean later addition (per the maintain handoff).

### M6 — The promotion seam (static now, living later)
- Each node's prompt assembly goes through ONE seam returning a static focused
  prompt today, shaped so it can later source from `classify-behaviors`/the seed
  corpus and participate in minting. The seam is a clean interface boundary; it
  does NOT couple to current minting internals (the minting process is being
  reworked separately). Promotion is a flip behind the seam, not a node rewrite.

### M7 — The build!-CQ-loop boundary
- The tree owns the adaptive loop; `build!` stays an intact deterministic
  sub-call. The tree calls `build!`, reads its CQ verdict (`:status :complete |
  :failed-cq` with `:graph-health` + `:exit-criterion`), and branches: pass →
  done; fail → RLM inspects (drill-down) the failing CQ + graph-health → FOCUSED
  re-extract/re-link of the node whose output the gap traces to → re-gate; or, if
  unanswerable from the available sources, surface honestly + terminate the loop.
- Default gate (S15): pass-rate ≥ 0.8, unknown-rate ≤ 0.3; overridable per build.
- The loop is budget-bounded (max iterations) so it always terminates.

## Testing Decisions

- **Good tests verify behavior through public interfaces, not node internals.** A
  test reads like "given this real profile, the Model node decides one concept per
  entity at the canonical-row grain and the goal's scope filter" — not "the
  prompt contains string X."
- **Per-node tests on CAPTURED REAL inputs.** Each focused node is independently
  tested: feed it a real captured profile/model-spec (captured from a real run,
  never invented) and assert the shape + key decisions of its output. This is the
  payoff of decomposition — each node is testable in isolation.
- **Deterministic stages already covered** — V18 (referential integrity), V20
  (apply-step), S12 (dedup), S03/S13/S15 suites stay green; this redesign must not
  regress them.
- **End-to-end live verify on the real BRYC 5 sources** (IPEDS `output.db`,
  `cip_soc_crosswalk.csv`, O*NET `db_30_1_excel`, `louisiana_occupation_wages.csv`,
  `pseo_la.xlsx`) with the S15 CQ gate as the acceptance criterion. Real LLM
  (gemini-3-flash-preview), real embeddings/ColBERT, real Grain. Mandatory before
  "done"; no false green.
- **The V17/V20 lessons are explicit acceptance checks:** the resulting graph is
  at Louisiana scale (not a national raw-row dump), has program/entity NODES (not
  edge-only), carries earnings as queryable attributes, and resolves endpoints
  (V18). A naïve over-extraction is a FAIL.
- **Prior art:** the s18/s19/s20 live-verify drivers; the V09/V17/V20 capture
  docs; the per-slice live-verify pattern used throughout the verification phase.

## Out of Scope

- **The maintain / incremental-discovery branch (P4)** — DEFERRED; context
  captured in `docs/build-timeline/handoff-plan/2026-06-16-DEFERRED-maintain-incremental-discovery-handoff.md`. We build greenfield maintain-AWARE (M5 reconciles against current graph state) but do not build the maintain branch.
- **Minting-process internals** — the user is reworking minting separately; this
  PRD builds only the clean promotion seam (M6), not minting.
- **Turning nodes ON as living behaviors** — promotable seam only; nodes stay
  static-prompt in this PRD.
- **The BRYC product flow** — the comparison uses discovery output; it is not the
  product being built here.
- **Non-structured/streaming sources beyond csv/sql/excel/text.**

## Further Notes

- **The prompt-bloat treadmill** is the core diagnosis: ~400 lines of guidance in
  one loop, growing per failure, still dropping concerns. The tree is the
  structural fix; the grain/scope guidance moves out of a frozen mega-prompt into
  a focused Model node (and later, an evolving behavior body).
- **"Deterministic skeleton" ≠ LLM-free.** The skeleton (`build!`) is the
  deterministic spine; the knowledge work (profile, model, transform, CQ judging,
  dedup judging) is done by LLMs at the nodes/stages. Verify BOTH.
- **This is the structural response to the V17 honest-negative.** V17 proved the
  open-ended autonomous builder is partial (no nodes, dangling edges, no scope, no
  coverage); the tree makes each of those a guaranteed step.
- **The V20 live verify** proved the full-extraction MECHANISM at 1.66M rows / 0
  errors, but a single naïve transform over-extracted (national, raw-row grain) —
  exactly why grain + scope become their own focused Model-node decision rather
  than another mega-prompt paragraph.
- **Domain/industry-agnostic invariant** (discipline 12): no education/crosswalk/
  CIP/SOC tuning anywhere; format specialists are encouraged; the builder's focus
  comes from the runtime goal/docs.
- **Recursive-only RLM:** the focused leaves ARE recursive-RLM nodes; the
  recursive-only direction holds. `gemini-3-flash-preview` default. Events-first
  Grain discipline throughout (commands → schema-validated events → projections;
  no bare appends).
- An **ADR** should record the hard-to-reverse calls (tree-owns-loop /
  build!-stays-sub-call; static-now-promotable seam; reconcile-against-graph-state
  seam) once this PRD is approved.
