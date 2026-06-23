# EB11 Handoff — Maintain (evolutionary): new discoveries + new classes/attrs vs an existing graph

Fresh-context brief for EB11 (`docs/build-timeline/issues/evolutionary-builder/EB11-maintain-evolutionary.md`).
Crafted post-merge from EB10's REAL maintain arm + EB5/EB6's REAL contracts + the
deferred-maintain handoff design. Work DIRECTLY on `feature/ontology-architecture`
(NOT a worktree). DO NOT COMMIT/PUSH — leave staged; the orchestrator `/inspect-orc`s
then commits locally. Implement via `/tdd`. RE-ORCHESTRATION (flip a stub + reuse the
subbehaviors + EB10's loop), NOT a rewrite.

## The goal
Flip DT9/EB10's MAINTAIN arm from the explicit `maintain-deferred-stub` into a REAL
evolutionary-maintain run. When a graph already exists for the `:ontology-id` (the
front-of-tree `:condition` selects maintain), the new source:
1. runs **Survey → Model → Extract** on the NEW source (against the existing graph,
   not from scratch),
2. **evolves the TBox** (EB6 Axiom): introduce NEW taxonomy classes + NEW properties/
   attributes AND how they relate to existing classes/properties (S07 axioms land),
3. runs **MULTI-GRANULARITY Reconcile** (EB5): connect NEW entities → existing
   entities AND NEW attributes/features → existing entities' attributes/features,
4. **re-gates** CQs (a new source may ADD CQs), reusing EB10's CQ-objective loop.
**Idempotent:** re-running an UNCHANGED source reconciles, does NOT duplicate (EB5's
against-graph-state seam).

## Read first
1. `docs/build-timeline/handoff-plan/2026-06-16-DEFERRED-maintain-incremental-discovery-handoff.md` — the maintain design (§1 + §3: the flip is ADDITIVE — a per-source run + `reconcile-graph!` (DT7/EB5) against the existing graph, NO restructure of the spine).
2. `components/ontology/src/ai/obney/orc/ontology/core/central_evolver.clj` — `run-central-evolver!` (~L728) + the maintain arm (~L41–43, currently → `dt/maintain-deferred-stub`). EB11 flips THIS arm to a real maintain composition that reuses the loop's per-source subbehavior pipeline against the existing graph. `dt/greenfield-vs-maintain-branch` already DETECTS the existing graph (reuse its decision).
3. `components/ontology/src/ai/obney/orc/ontology/core/reconcile_subbehavior.clj` — EB5 ALREADY reconciles against the CURRENT graph at TWO granularities (entity + attribute) with check-before-mint. Maintain reuses it AS-IS (the maintain path is exactly "reconcile new drafts vs the existing graph"). Confirm reconcile-NOT-duplicate on a 2nd pass.
4. `components/ontology/src/ai/obney/orc/ontology/core/axiom_tbox_subbehavior.clj` — EB6 emits NEW class/property axioms (incl. the minted `assert-sub-class`) grounded against the (now-existing) graph → TBox evolution.
5. The Survey/Model/Extract subbehaviors + EB10's `cq-objective-loop!` (reuse for the re-gate).

## What EB11 must build
Flip the maintain arm: a maintain composition (likely a `run-central-evolver!` mode
or a `maintain-evolve!` fn) that, given a NEW source + an EXISTING `:ontology-id`,
runs the per-source subbehavior pipeline (Survey→Model→Extract→Reconcile→Axiom→Embed)
**in maintain mode against the existing graph** + re-gates. The KEY evolutionary
behaviors to prove (beyond greenfield):
- a NEW source introduces a NEW class (EB6) that did not exist in the graph;
- a NEW attribute on a new/old entity connects to an EXISTING entity's attribute
  (EB5 attribute-granularity);
- new entities reconcile-NOT-duplicate against existing ones;
- a CQ the first source couldn't answer can now PASS after the second source adds the
  missing data (the evolutionary payoff).

## Do NOT
- Rewrite EB10's loop or the subbehaviors (reuse; flip the stub + run maintain mode). Build greenfield-only logic (the existing graph is the input — read it). Duplicate on a re-run (idempotent via EB5). Hardcode vertical class/attr vocab (#12). Touch unrelated files. Commit/push. Create a worktree.

## Prototype (WORTH — do first)
Build a graph from source A, then feed source B (which shares an entity with A + adds
a NEW class whose attribute relates to an A entity's attribute) against the EXISTING
graph: prove (a) B's shared entity reconciles-not-duplicates, (b) B introduces a new
class (TBox grows — read back via `get-axioms` / the concepts), (c) a B attribute
connects to an existing A attribute (EB5 attribute link). Capture it.

## Verify
- `/tdd` red→green. Maintain-composition LOGIC tested deterministically where possible (the maintain branch selects + runs against an existing graph; idempotent re-run reconciles-not-duplicates) — hermetic brick gate.
- **LIVE verify (the evolutionary proof):** a real SECOND source against a real EXISTING graph introduces a NEW class whose attribute connects to an existing entity's attribute; new entities reconcile-not-duplicate; ideally a previously-unanswerable CQ now passes. Capture verbatim (`docs/build-timeline/live-verify/EB11-maintain.md`): the before/after graph (concepts/classes/axioms), the reconcile report (merges + attribute-links), the new TBox axioms. (Full multi-source-at-scale is staged with EB12.)
- Assert via the projection read-back (discipline 7). Re-run EB2–EB10 suites — no regression.
- Green under BOTH `clj -M:poly test brick:ontology` AND `:dev:test` of the EB11 ns. The real-subbehavior + LLM/ColBERT live test → on-demand lane.
- JVM hygiene: bounded runs; 0 orphan THIS-repo JVMs after (exclude sibling worktrees — kill only your own by PID; ColBERT bridge by its own PID).

## Report back (raw data)
The maintain composition (how you flipped the arm + reused the pipeline against the existing graph); the prototype (the 3–4 evolutionary proofs); the LIVE capture (before/after graph, the new class/attribute, the reconcile-not-duplicate, any newly-passing CQ); idempotency evidence; which lane each test went in; dual-runner totals + EB2–EB10 no-regression; "0 orphan THIS-repo JVMs"; every file changed by path; honest negatives. DO NOT COMMIT/PUSH. Binding Core Disciplines block 1–13 (in the EB11 issue) in force verbatim.
