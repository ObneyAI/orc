# PRD — General-Purpose Evolutionary Ontology Builder (composed subbehaviors)

**Date:** 2026-06-17 · **Branch:** `feature/ontology-architecture` · **Local PRD** (not an external tracker issue).
**Supersedes/deepens:** `docs/build-timeline/prd/2026-06-16-discovery-behavior-tree-redesign.md` — that PRD correctly diagnosed the open-ended-loop problem and proposed a composed tree, but **under-grounded the shape** (a flat per-source Profile→Model→Transform sequence). This PRD re-grounds the design in the three-pipeline + behavior-corpus + four-pillar research and recasts it as *composed, delegatable subbehaviors* pursuing CQ-satisfaction.
**Grounding sources:** the three research syntheses in the design grill (pipelines / behavior-corpus / pillars-intent); the prior PRDs `2026-06-12-ontology-substrate-and-builder-rebuild.md`, `2026-06-15-ontology-verification-and-bryc-comparison.md`; the live-verify intent-alignment docs (`intent-alignment-verdict.md`, `intent-alignment-checklist.md`, `ontology-workflow-before-after.md`, `SURFACE-AREA-AND-PROOF.md`, `replacement-readiness-audit.md`); the DT1–DT9 + DTscale-1 slices + `docs/build-timeline/issues/discovery-tree/DT-followups.md`.

---

## Problem Statement

We are building a **general-purpose** evolutionary ontology/taxonomy builder: take *any* source (CSV, SQL, Excel, text, RDF), discover a general ontology usable for *any* retrieval, auto-embed the fields worth embedding, search the graph by fused signals, and **discover / learn / maintain / access** knowledge over time. The substrate IS the product; the self-improving loop and BRYC are *applications* of it.

Three things block that today:

1. **We keep re-discovering the old shape.** All three prior builders — the Python DSPy `CSVOntologyBuilder` that built BRYC, ORC main's `evolutionary_builder` + per-format sheets + `entity_resolver` + `graph_evolver`, and our current deterministic-skeleton + recursive-RLM substrate — **converge on one skeleton**: ingest/profile → extract entities → extract relationships → resolve/dedup → align → validate → embed/index → exit. A flat new "pipeline of stages" is that same skeleton re-drawn. Rebuilding it adds nothing.
2. **We were force-fitting one node type.** The DT1–DT9 discovery tree is a hand-rolled Clojure orchestration that runs *every* step — even single-turn reasoning like grain/scope or CQ-derivation — as a forced `:recursive? true` `:repl-researcher` session whose prompts literally plead "do NOT call emit-tree!". That over-powered node is the root of the per-node timeout (F3) and squanders ORC's real palette.
3. **The intent-alignment review already caught drifts we must not re-orphan.** P2 auto-embed was dropped in the rebuild; discovered axioms are recorded `:axioms-skipped`; cross-source identity leans on label-similarity; maintenance is shallow ("new URI = new class"). A rushed shape would re-orphan these.

The user's directive: do **not** rush to a shape and steps — *truly compose behaviors and subbehaviors* into a general-purpose evolving system, grounded in the research that got us here.

## Solution

Express the builder as a **central ORC behavior tree (the "evolver") that composes and `:delegate`s to durable, independently-evolvable SUBBEHAVIOR trees**, pursuing **competency-question (CQ) satisfaction as its optimization objective**, **maintain-native over the event-sourced graph**, and **reusing the proven deterministic skeleton (`build!`) unchanged**.

- The *behaviors and how they compose* are the design; the tree shape *falls out* of composing them — not the reverse.
- Each subbehavior is a first-class, reusable, separately-versioned tree (a sheet) the self-improving loop can evolve and any consumer can `:delegate` to. Inside each subbehavior, the right ORC nodes are used (`:llm`/`:code`/`:condition`/`:fallback`), with `:repl-researcher` reserved for genuinely freeform exploration (Survey). Each subbehavior carries its *own* resilience (fallback/condition/troubleshoot) so it self-corrects or fails *with a diagnosis* before continuing.
- Where all three prior builders are shallow, we **deepen**: CQ-gate as the loop's objective; connecting-concept minting as a *learned* behavior; evidence-grounded cross-source identity; real TBox/axiom evolution; true incremental maintenance; semantic validation.
- The previously-proven DT1–DT9 step *logic* (grain/scope decision, field-grounding, the V20 apply-step, reconcile-against-graph-state, the CQ-loop) is **reused, re-housed** into the correct composition. This is re-orchestration + deepening — not a re-skin, not a rewrite.

The user gets a domain-agnostic builder that, on any source, composes proven behaviors to produce a connected, axiom-bearing, auto-embedded, CQ-validated ontology; that *grows* when fed a new source (new classes, new attributes, connected to existing entities and their attributes); and whose every behavior is an inspectable, reusable, evolvable tree.

## User Stories

1. As a greenfield builder, I want the system to compose proven subbehaviors (not re-author steps) so that I get a connected ontology without re-discovering the old extract→resolve→validate skeleton.
2. As a greenfield builder, I want each discovery step to be the *right* ORC node (a single `:llm` turn for reasoning, `:code` for deterministic work, `:repl-researcher` only for freeform source exploration) so that the builder is fast and not a pile of recursive agents.
3. As a structured-source consumer (CSV/SQL/Excel/text), I want a format-appropriate Survey that explores my source by shape (never loading it) so that extraction is grounded in what the source actually contains.
4. As a builder, I want the Model subbehavior to decide entity types, URI-keying, grain, scope, the embed-worthy fields, AND candidate TBox/axioms so that grain/scope/representation are guaranteed decisions, not dropped concerns.
5. As a builder, I want Extract to author a per-row transform and apply it over the FULL source (the V20 apply-step) so that I get comprehensive, scoped coverage — not a sampled or raw-row dump.
6. As a builder, I want Reconcile to merge entities AND connect their attributes/features across sources, checking the existing graph before minting, so that the graph is connected and identity is evidence-grounded, not label-similarity guesswork.
7. As a builder, I want an Axiom/TBox subbehavior that emits disjointness, property characteristics, closure, subClassOf/subPropertyOf/domain/range so that the ontology has real representation depth (closing the `:axioms-skipped` drop).
8. As a builder, I want Embed+Index to be a GUARANTEED step that auto-detects embed-worthy fields (informed by Survey/Model) and embeds + ColBERT-indexes them so that the graph is semantically searchable by default (P2).
9. As a builder, I want a Validate+CQ subbehavior that derives competency questions from the goal × the profiles and gates the graph on them so that "done" means fit-for-purpose, not "ran".
10. As a builder, I want the central evolver to pursue CQ-satisfaction as its objective — re-discovering focally where a CQ fails — so that discovery seeks the goal instead of running once and stopping.
11. As a builder, I want a failing CQ routed (by a reasoning step) to the subbehavior that can close it (missing entity→Extract, missing link→Reconcile, missing class/attr→Axiom/Model) so that re-work is focused, not a full rebuild.
12. As a builder, I want a genuinely-unanswerable CQ to terminate the loop honestly (surfaced, not spun, not false-green) so that I learn what the sources cannot answer.
13. As a maintainer, I want to feed a NEW source and have the system make new discoveries against the existing graph, introduce new taxonomy classes + properties, and connect both the new entities AND their attributes/features to existing entities and their attributes — so that the ontology evolves rather than being rebuilt.
14. As a maintainer, I want a re-run over an unchanged source to be idempotent (reconcile, not duplicate) so that maintenance is safe.
15. As a brownfield consumer, I want to bring an existing graph (e.g. a TTL ingested to events) and have maintain improve + extend it the same way so that onboarding and growth are one motion.
16. As the author of a subbehavior, I want it to be its own composable tree with a clear `:reads`/`:writes` contract so that I can build, test, and improve it in isolation.
17. As a consumer building my OWN workflow, I want to `:delegate` to a published subbehavior (e.g. Extract, Validate) so that I can reuse a proven behavior without re-implementing it.
18. As the self-improving-loop maintainer, I want each subbehavior's prompt assembled through one seam so that subbehaviors can later be promoted to living, evolving bodies (sourced from classify-behaviors + minting) without rewriting them.
19. As a builder, I want each subbehavior to carry its own resilience — try a primary path, fall back to a robust one, gate on intermediate state, and troubleshoot (root-cause) before continuing — so that one bad step self-corrects or fails cleanly with a diagnosis instead of poisoning downstream.
20. As a consumer using the graph "as a database", I want self-improvement strictly opt-in so that I pay no classification/judge/reranker overhead when I just want a built graph.
21. As any retrieval consumer, I want fused BFS + embedding + ColBERT search, uniformly scoped, so that I can access the knowledge by any signal subset (P3).
22. As a verification reviewer, I want to read each subbehavior's output + the CQ verdict + the routing decisions so that I can judge quality step-by-step and adversarially.
23. As ORC itself, I want the evolver to be a composable behavior-tree node so that ontology-building is a first-class workflow primitive other trees can invoke.
24. As an operator, I want the whole thing verified on the real BRYC 5 sources end-to-end with the CQ gate, with no false green and the V17/V20 honest-negative lessons enforced (no raw-row dump; nodes not edges; grounded fields), before it's called done.
25. As an operator, I want any consumer in any domain to get correct behavior with NO industry-specific tuning — the focus comes from the runtime goal/docs.

## Implementation Decisions

Existing component `ai.obney.orc.ontology` (builder/discovery, skeleton) + `ai.obney.orc.orc-service` (behavior-tree executor, node palette, `:delegate`, source tools). The design composes ORC's real node palette: `:leaf` (executor `:ai`=LLM or `:code`), `:sequence`, `:fallback`, `:parallel`, `:map-each`, `:condition`, `:llm-condition`, `:delegate`, `:repl-researcher`.

### M-A — The 8 subbehaviors (the decomposition)
The builder composes **eight** subbehaviors — six compose existing corpus behaviors, three are minted gaps:
- **Survey/Profile** (compose Research/Investigation + the discovery-pattern survey) — explore a source by shape via the medium's specialist tools; emit the profile contract INCLUDING the embed-worthy-field signal. The ONE subbehavior that warrants a `:repl-researcher` (terminal mode — no recursion/emit-tree).
- **Model the ontology** (compose Design + Classification) — from goal × profile decide: entity types, URI-keying, grain (canonical-row vs breakdown-as-entity), scope filter, **embed-worthy fields** (feeds P2), and **candidate TBox/axioms**. A single `:llm` reasoning turn.
- **Extract** (compose Transformation + Extraction) — author a per-row transform grounded in the real row-key shape, validate on a sample, apply over the FULL source via the V20 apply-step. `:code` sample → `:llm` author → `:code` apply.
- **Reconcile/Resolve** (MINT; reuse S12 dedup + S03 alignment + S21 retrieval + V18 integrity) — merge across sources AND against the current graph, at **entity AND attribute/feature granularity**; **check the existing graph via P3 hybrid search BEFORE minting**; identity is evidence-grounded (S13), not label-cosine.
- **Emit Axioms/TBox** (MINT) — disjointness, property characteristics, closure axioms, subClassOf/subPropertyOf/domain/range. Closes the `:axioms-skipped` drop; makes representation depth a first-class step.
- **Embed+Index** (compose deterministic + Survey/Model-informed) — a GUARANTEED step that auto-detects embed-worthy fields (using the Model's signal) and embeds + ColBERT-indexes them (P2).
- **Validate + derive/check CQs** (compose Validation + Critique + the S15 CQ runner) — semantic validation; derive CQs from goal × profiles; run the CQ gate.
- **Maintain (evolutionary)** — see M-E.

### M-B — Composition + delegation (`:delegate` to subbehavior sheets)
The central evolver composes by **`:delegate`-ing to durable, reusable, independently-evolvable subbehavior SHEETS** (child tick, isolated blackboard, mapped `:reads`/`:writes`, lineage + streaming). Each subbehavior is a first-class artifact the self-improving loop can evolve and any consumer can reuse. Inside each subbehavior tree the right nodes are composed (`:llm`/`:code`/`:condition`/`:fallback`); `run-node-session!` is reserved for genuinely ephemeral single-turn work inside a subbehavior. **Verify-not-assume:** the `:delegate`-per-subbehavior child-tick overhead at real scale is an explicit live-verify acceptance criterion, not an assumption.

### M-C — The central evolver loop (CQ-gate as the objective)
A **fixed-composed** central tree (steps structurally guaranteed) with **CQ-satisfaction as the loop objective**:
- `:condition` greenfield-vs-maintain branch at the front.
- Greenfield: `:delegate` Survey (per source) → `:delegate` Derive-CQs (after Survey, grounded) → bounded LOOP[ `:map-each` over sources (`:delegate` Model → Extract, optionally `:parallel`) → `:delegate` Reconcile + Axiom/TBox → `:code` `build!` (dedup/embed/index; P2 informed by Model) → `:delegate` Validate+CQ-gate → `:condition` on the CQ verdict ].
- CQ verdict → **pass** = done; **fail** = the one adaptive **ROUTE** step (an `:llm`/decision node) maps the failing CQ + graph-health to the subbehavior that closes the gap → re-invoke focally → re-loop; **unanswerable-from-sources** = honest terminate.
- Bounded iterations; the routing is CQ-evidence-driven (and that same evidence is the natural future feed for P4-LEARN).

### M-D — Subbehavior-internal resilience
Each subbehavior sheet carries its OWN `:fallback` (primary → robust path), `:condition`/`:llm-condition` gates on intermediate state, and a reasoning/troubleshoot node (compose **Investigation** = root-cause + **Validation** = check) to self-correct OR fail cleanly with a diagnosis BEFORE continuing down its tree or returning. `:fallback` works in hand-composed top-level subbehavior sheets today; the RLM-*emitted* `:fallback` (Phase-2) is a tracked emit-tree extension (`emit_tree_extensions_pending`), needed only if Survey's `:repl-researcher` must author a fallback — not a blocker.

### M-E — Maintain (evolutionary)
Maintain is BUILT (a locked subbehavior), composing Survey/Model/Extract **against current graph state** → **TBox-evolution** (introduce new classes + new properties/attributes + how they relate to existing classes/properties) → **multi-granularity Reconcile** (connect new entities AND new attributes/features to existing entities + attributes/features) → re-gate CQs (a new source can ADD CQs). This implies **attributes/features are first-class typed properties** that can themselves relate — the TBox depth the old `detect-schema-extensions` ("new URI = new class") never reached. Its full multi-source incremental *verification* is staged after greenfield.

### M-F — The 5 under-served commitments (carried, not assumed)
- **P2** — Embed+Index is a guaranteed step informed by Survey/Model's embed-field signal (not implicit in `build!`).
- **P3** — check-before-mint hybrid search lives inside Reconcile/Model (informs the mint, not only downstream reconcile).
- **Axioms/TBox** — its own subbehavior (M-A) — closes `:axioms-skipped`.
- **P1 live-LLM-transform** — no longer F3-gated (Model/Transform are `:llm`/`:code`, not recursive sessions); its autonomous end-to-end live run (sane scoped count, no hand-correction) is a verify-not-assume acceptance criterion.
- **P4-LEARN** — CQ-evidence → evolve subbehavior bodies via classify-behaviors/minting — EXPLICITLY DEFERRED behind the M6 promotion seam, recorded as deferred.

### M-G — DT1–DT9 reuse
Re-house the proven step logic into the new subbehavior sheets. **F3 dissolves** (single-turn reasoning becomes `:llm`/`:code`, not forced `:recursive?` repl-researcher). **F1** (embed batching) + **F2** (SQL scope-set paging) survive as concerns inside the relevant `:code` stages (see `DT-followups.md`). The intact deterministic `build!` (parse→normalize→dedup S12→evidence S13→validate S10/11→embed P2→index→CQ S15) stays a `:code`/sub-call and is not regressed.

## Testing Decisions

- **Good tests verify behavior through the subbehavior's public `:reads`/`:writes` contract**, not its internal nodes. A test reads like "given this real profile + scoped goal, Model decides canonical-row grain + a goal scope filter + the embed-worthy fields."
- **Each subbehavior sheet independently testable** on CAPTURED REAL inputs (delegated with real `:reads`); no invented fixtures.
- **Deterministic stages already covered** — reuse the `build!` / S12 / V18 / V20 suites; this design must not regress them.
- **The central loop's CQ-objective behavior tested both ways**: deterministically (stub the CQ verdict sequence → fail routes to the right subbehavior → re-gate → pass; unanswerable → honest terminate; budget exhaustion → terminate-with-reason) AND live.
- **End-to-end live-verify acceptance on the real BRYC 5 sources** with the S15 CQ gate; real LLM (gemini-3-flash-preview), real embeddings/ColBERT, real Grain.
- **Verify-not-assume items are explicit acceptance criteria**: `:delegate` overhead at scale; P1 autonomous live transform yields a sane scoped count (no hand-correction); P2 auto-embed actually fires; attribute-level reconciliation connects new attributes to existing ones.
- **The V17/V20 honest-negative lessons are checks**: no raw-row dump, entities are NODES not edges-only, fields are grounded; no false green.
- **Prior art**: the DT1–DT9 + DTscale-1 + s18/s19/s20 live-verify drivers; the DT7/DT8 deterministic-loop + real-infra tests; the `/inspect` adversarial re-verification routine.

## Out of Scope / Deferred (sequenced)

- **P4-LEARN / living-evolving subbehavior bodies** — the M6 promotion seam is built; turning subbehaviors ON as living bodies (CQ-evidence → classify-behaviors/minting feedback) is deferred.
- **Minting-process internals** — the user is reworking minting separately; we build only the clean promotion seam.
- **RLM-emitted `:fallback`** (the Phase-2 emit-tree extension) — tracked, only needed if Survey must author a fallback.
- **The BRYC product flow** — BRYC is the verification vehicle, not the target.
- **Maintain's full multi-source incremental verification** — Maintain is built; its full at-scale incremental proof is staged after greenfield lands.

## Further Notes

- **Anti-rediscovery framing:** the flat phase-list is the old shape across all three prior builders; the leverage is the six deepenings (CQ-as-objective, learned connecting-concept minting, evidence-grounded identity, real TBox/axiom evolution, incremental maintain, semantic validation).
- **Deterministic-skeleton-wraps-LLM-discovery** holds: the skeleton owns the contracts; the subbehaviors do the knowledge work; verify BOTH.
- **F1/F2/F3:** F3 dissolves under this re-architecture; F1/F2 survive inside `:code` stages (`docs/build-timeline/issues/discovery-tree/DT-followups.md`).
- **Verify-not-assume caveats** are recorded in M-B/M-F so we do not declare done on assumptions.
- **Cross-cutting invariants** (the corpus Disciplines #1–12 bind every slice): domain/industry-agnostic (no education/CIP/SOC tuning); events-first (commands→schema-validated events→projections, no bare appends); recursive-only RLM (only Survey is a `:repl-researcher`; the *evolver's* recursion is the CQ-objective loop); descriptions self-contained; no hardcoded phrase matching; gemini-3-flash-preview default; live runs mandatory before done; JVM hygiene (bounded runs, kill-by-PID, 0 orphans).
- An **ADR** should record the hard-to-reverse calls (subbehaviors-as-delegatable-sheets; CQ-gate-as-loop-objective; attributes-as-first-class) once this PRD is approved.
