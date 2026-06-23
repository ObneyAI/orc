# EB10 Handoff — Central evolver loop (CQ-gate as the OBJECTIVE) — the keystone

Fresh-context brief for EB10 (`docs/build-timeline/issues/evolutionary-builder/EB10-central-evolver-loop.md`).
Crafted post-merge from the REAL EB2–EB9 subbehavior contracts + DT8/DT9's loop
logic. Work DIRECTLY on `feature/ontology-architecture` (NOT a worktree). DO NOT
COMMIT/PUSH — leave staged; the orchestrator `/inspect-orc`s then commits locally.
Implement via `/tdd`. This is the keystone — re-ORCHESTRATION (reuse DT8/DT9's loop +
build!), composing the SUBBEHAVIORS via `:delegate` instead of DT1–DT9's inline nodes.

## The goal — the central tree
A FIXED-COMPOSED central tree that `:delegate`s to the subbehaviors and pursues
**CQ-satisfaction as its OBJECTIVE** (not a terminal report):

```
:condition greenfield-vs-maintain (re-house DT9 decision)
  → Survey (per source, :delegate)
  → derive CQs (Validate+CQ derive, :delegate) + persist ORSD
  → bounded LOOP [
       :map-each sources (delegate Model → Extract; optionally :parallel)
       → :code land drafts + delegate Reconcile + delegate Axiom/TBox
       → delegate Embed+Index (guaranteed P2)
       → :code build! (dedup + the S15 exit-criterion)        ; deterministic skeleton
       → run the CQ gate (evaluate-cqs! in-process w/ the judge capability)
       → :condition on the verdict:
            pass        → done
            fail        → ROUTE (ONE adaptive :llm/decision node, :reasoning FIRST:
                          map the failing CQ + graph-health → the subbehavior that
                          closes the gap) → re-invoke FOCALLY → re-gate
            unanswerable→ terminate HONESTLY (no spin, no false-green; surfaced reason)
     ]   ; budget-bounded — ALWAYS terminates with a surfaced reason
```

## The REAL subbehavior contracts to wire across `:delegate` (look each up via its `-subbehavior-name`; reuse `dsl/sheet-id-for-name`)
- **Survey** `ontology-survey/...@v1` — `:reads [:goal :source-descriptor]` → `:writes [:profile]`
- **Model** `ontology-model/model@v1` — `:reads [:goal :profile]` → `:writes [:reasoning :model-spec :candidate-axioms]` (+ `:embed-fields` inside model-spec/sibling)
- **Extract** `ontology-extract/extract@v1` — `:reads [:model-spec :source]` → `:writes [:concept-drafts :relationship-drafts :extraction-report]`
- **Reconcile** `ontology-reconcile/reconcile@v1` — `:reads [:ontology-id :concept-drafts :relationship-drafts :source-uri-sets]` → `:writes [:reconcile-report]`
- **Axiom/TBox** `ontology-axiom-tbox/axiom-tbox@v1` — `:reads [:ontology-id :candidate-axioms :model-spec]` → `:writes [:axiom-report]`
- **Embed+Index** `ontology-embed-index/embed-index@v1` — `:reads [:ontology-id :embed-fields]` → `:writes [:embed-index-report]`
- **Validate+CQ** `ontology-validate-cq/validate-cq@v1` — `:reads [:ontology-id :goal :profile (:consumer-cqs :judge-fn)]` → `:writes [:competency-questions :cq-verdict :graph-health]`

The flow wires `:writes`→`:reads` across `:delegate`: Survey `:profile`→Model; Model `:model-spec`→Extract/Axiom, `:candidate-axioms`→Axiom, `:embed-fields`→Embed; Extract `:concept-drafts`/`:relationship-drafts`→Reconcile; the landed graph (`:ontology-id`)→Embed/Validate.

## Read first
1. The 7 subbehavior `*.clj` (names + contracts above; they're EB9-`:resilient?` capable). EB1's `delegate_composition_test` / `dsl/delegate` for the seam.
2. `components/ontology/src/ai/obney/orc/ontology/core/discovery_tree.clj`: `run-discovery-tree!` (the DT1–DT9 central spine), `greenfield-vs-maintain-branch` (~L1220, DT9), the `recovery-branch`/`cq-reextract-branch` decisions (~L1168/L1192, DT8 — the focused-recovery + CQ-driven re-extract DECISION logic to RE-HOUSE as the ROUTE). Re-house the loop+routing LOGIC; swap inline nodes for `:delegate`.
3. `components/ontology/src/ai/obney/orc/ontology/core/deterministic_skeleton.clj`:`build!` (~L812) — the dedup + S15 exit-criterion + graph-health verdict shape (REUSE, no fork).
4. EB8's `evaluate-cqs!` + the judge-as-IN-PROCESS-capability pattern (the judge fn CANNOT cross `:delegate` — run the gate in-process with the judge, per EB8/EB9).

## What EB10 must build
A `central-evolver` tree (built via `orc-service.interface`; a fixed `:sequence`/`:condition`/`:map-each` composition that `:delegate`s to the subbehaviors). The CQ-gate is the LOOP OBJECTIVE: a failing CQ is ROUTED (the `:llm`/decision node, `:reasoning` FIRST) to the closing subbehavior (missing entity→Extract, missing link→Reconcile, missing class/attr→Axiom/Model, absent-in-source→terminate), re-invoked FOCALLY (not a full rebuild), re-gated → pass; unanswerable→honest terminate; budget-bounded.

## Do NOT
- Rebuild DT8/DT9's loop or `build!` (RE-HOUSE/reuse). Fork the subbehaviors (compose via `:delegate`). Spin on an unanswerable CQ or emit a false-green pass (#4/#9 — honest terminate with reason). Route via hardcoded phrase matching (#7/#12 — the route reads the CQ + graph-health). Run an unbounded loop (always budget-terminate). Touch the subbehavior internals or unrelated files. Commit/push. Create a worktree.

## Prototype (YES — novel; do first)
Prove the composed central tree: (a) `:delegate`s to ≥2 real subbehaviors end-to-end on a real source; (b) on a STUBBED failing CQ verdict, the ROUTE node maps it to the right subbehavior, re-invokes it focally, and the re-gate PASSES (the route-and-close); (c) a STUBBED unanswerable CQ → honest terminate (no further routing, surfaced reason). Capture it.

## Verify
- `/tdd` red→green. **Loop LOGIC tested DETERMINISTICALLY** (stub the CQ verdict): fail→route→re-invoke→re-gate→pass; unanswerable→terminate-honestly; budget-exhausted→terminate-with-reason; greenfield-vs-maintain `:condition` selects correctly. These are the hermetic brick-gate tests (no LLM/ColBERT — stub the verdict + the subbehavior results).
- **LIVE verify (the keystone proof):** on a REAL source, the composed central tree runs the real subbehaviors via `:delegate` end-to-end to a CQ verdict — capture the per-iteration trace (which subbehaviors ran, the CQ verdict + graph-health, any route + focused re-invoke, the terminate reason). On-demand lane (`development/ontology-integration/...` + a `development/src` driver — drives all the subbehaviors' LLM + ColBERT). Capture verbatim (`docs/build-timeline/live-verify/EB10-central-loop.md`).
- Assert events LANDED + the graph state via the projection (discipline 7), not return values. Re-run EB2–EB9 suites — composing them MUST NOT regress them.
- Green under BOTH `clj -M:poly test brick:ontology` AND `:dev:test` of the EB10 ns.
- JVM hygiene: bounded runs; 0 orphan THIS-repo JVMs after (exclude sibling worktrees — kill only your own by PID; ColBERT bridge by its own PID).

## Report back (raw data)
The central-tree composition (the fixed structure + the `:delegate` wiring + the ROUTE node); the prototype result (the 3 proofs); the deterministic loop-logic tests (fail→route→pass, unanswerable→terminate, budget→terminate); the LIVE capture (the end-to-end per-iteration trace to a CQ verdict on a real source); which lane each test went in; dual-runner totals + EB2–EB9 no-regression confirmation; "0 orphan THIS-repo JVMs"; every file changed by path; honest negatives. DO NOT COMMIT/PUSH. Binding Core Disciplines block 1–13 (in the EB10 issue) in force verbatim.
