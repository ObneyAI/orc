# RS-3 handoff — The domain child exists durably from birth

Issue: `docs/issues/r-inject-specialisation/RS-3-the-domain-child-exists-durably-from-birth.md` (read it whole).
Work in `/Users/darylroberts/Desktop/Code/orc-r-inject-specialisation` (branch `feature/r-inject-specialisation`).
Runner: a `clojure -M:dev:test -e` run from THIS worktree (see RS-2's brief for the exact form), one JVM at a time,
0 orphans after every run; never kill a JVM you did not start. Do not run `poly test` or any whole-brick build. Do not
commit, push or stash. Never edit `specs/*.allium`.

## Goal

What RS-2 decides in memory becomes durable at classification time. The classified event records the verdict, the
label, the reasoning, the children considered, the parent and the child's provenance. A domain-child mint (first child
or sibling) records, before the campaign runs: the signature claim CV-1 already records for a fresh mint, and the
child's birth as a concept in the tree-class ontology carrying its domain label and a `skos:broader` edge to its
parent — by the COMMAND path RS-P2 proved, never by a description body write. A partial verdict on a childless class
that did NOT mint (blank label) and a covered verdict record nothing new. A domain-axis deferral is recorded through the
existing deferral command, extended to name the domain axis. And the two lookups that must see a domain child — RS-2's
default `domain-children-fn` and walk-down's own child lookup — read what this slice writes.

## Read first

1. Spec excerpt, verbatim (`specs/ontology.allium`): `rule MintDomainChild` and `rule MintSiblingDomainChild` each
   `ensures: DomainChildMinted(parent, label, identity, minted_by)` and
   `ensures: ConceptRelationship.created(source: child_identity, target: assigned_class, predicate: "skos:broader", ...)`;
   `rule LandOnDomainChild ensures: TaskAssignedToDomainChild(child, minted_by)`; invariants
   `DomainChildIdentityIsStable` ("its parent edge exists in the concept graph from the moment it is minted, and it
   describes itself from birth with its label and the signature that minted it"), `DeferralIsVisible` ("the recorded
   reasoning names the axis that deferred"), `ClassificationIsOnePerCampaign`.
2. RS-P2's verdict (`docs/issues/r-inject-specialisation/RS-P2-prototype-a-parent-edge-can-be-born-from-the-claim-path.md`,
   probe `development/src/rs_p2_parent_edge_probe.clj`): the claim path cannot carry a parent and the description
   projector ignores `:tree-class`-scoped bodies; ensuring both tree-class concepts and dispatching
   `:ontology/create-relationship` with `skos:broader` produced the edge with NO body written (Q-b); a child described
   only by a `:tree-class` claim is invisible to walk-down's `get-tree-class-children`, which reads `:tree-fingerprint`
   scope (Q-c).
3. `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj`, the auto-classify wedge
   (`maybe-auto-classify-and-set-context`, ~line 503–740): the `assignment-command` build (~640–680) forwards
   `:parent-tree-id`, `:assigned-via`, `:ranked-candidates` etc. from the classifier result; the CV-1 signature capture
   `capture-classification-signature!` runs only on `:was-fresh-mint? true` (~715) and records a `:representative-use`
   claim on the class; the deferral path (`:outcome :uncertain`, ~583–640) dispatches
   `:ontology/record-task-classification-deferral`.
4. RS-2's result shapes (`task_classifier.clj`, `assign-domain-child` / `maybe-assign-domain-child`; tests in
   `rs2_domain_child_classifier_test.clj`): `:assigned-via` ∈ `#{:mint-domain-child :land-on-domain-child
   :mint-sibling-domain-child}`, `:parent-tree-id`, `:domain-label` (canonical, on the two mint branches),
   `:was-fresh-mint?`, `:domain-verdict {:domain-coverage :domain-label :domain-reasoning}`,
   `:domain-children-considered [labels]`, and on a deferral `:domain-deferral {:axis :domain :reason
   :unknown-coverage | :children-lookup-failed}` with the match left untouched. RS-2's
   `default-domain-children-fn` reads each narrower concept's `:tree-class` description for `:domain-label` — change it
   in this slice to read the CONCEPT's label instead (below), since that is what this slice records.
5. `components/ontology/src/ai/obney/orc/ontology/core/commands.clj`: `assign-task-class` (~1440) and its schema
   (`interface/schemas.clj` ~687); `record-task-classification-deferral` (~1740) and its schema (~806,
   `:fallback-source [:enum :colbert-fallback :timeout-fallback]`); `create-concept` (schema ~1322: `:uri :label
   :description :scope :broader`) and `create-relationship`. `core/todo_processors.clj` (ontology):
   `ensure-tree-class-concept!` (~955, private; lazy-creates a tree-class concept with `:label (str target-id)`) and
   `tree-class-ontology-id`.
6. `components/ontology/src/ai/obney/orc/ontology/core/task_classifier.clj` `get-tree-class-children` (~294): reads
   narrower concepts and `get-description ctx :tree-fingerprint child-id`, dropping children without one.
7. Tests to mirror: `r_inject_classifier_context_test.clj`, `cc6_cv1_claim_capture_test.clj`,
   `el3_wedge_skip_uncertain_test.clj`, `cc23_classification_observability_test.clj` (orc-service), `seeds_test.clj`
   and `walk_down_classifier_test.clj` (ontology), and the RS-P2 probe for the store-level shape.

## The exact change

**Ontology: one command births a domain child.** `:ontology/mint-domain-child` with `{:parent-tree-id :child-tree-id
:domain-label :source-sheet-id :source-tick-id :source-node-id}`: ensures the parent's tree-class concept exists
(lazy-create as `ensure-tree-class-concept!` does), creates the child's tree-class concept with `:label` =
the domain label and `:description` naming the parent, `:broader [parent-uri]` (and, where `create-concept`'s
`:broader` does not project the reverse `:narrower` edge, dispatches `create-relationship skos:broader` exactly as the
projector does), and emits `:ontology/domain-child-minted {:parent-tree-id :child-tree-id :domain-label
:minted-by [sheet tick node]}`. Idempotent: minting the same child again (same identity) emits nothing new
(`DomainChildIdentityIsStable`). Move `ensure-tree-class-concept!` to a shared place if the command needs it.

**Ontology: the classified event and the deferral carry the domain facts.** `assign-task-class` accepts and records
(all optional, omit-not-nil): `:domain-verdict`, `:domain-label`, `:domain-children-considered`, `:domain-deferral`;
`:assigned-via` already forwards. `record-task-classification-deferral` accepts `:fallback-source :domain-coverage`
(schema enum extended) with `:reasoning` naming the axis and the reason; the classified event may ALSO carry
`:domain-deferral` (an assignment happened on the structural axis while the domain axis deferred — record both facts).

**Ontology: the two lookups read what is written.** RS-2's `default-domain-children-fn` returns each narrower
tree-class concept whose `:label` is a non-blank string other than its own id → `{:target-id :domain-label label}`.
`get-tree-class-children` reads the child's description at `:tree-class` scope first and `:tree-fingerprint` second
(the seeded instances live at the latter, runtime children at the former), so walk-down can rerank domain children once
they have a description.

**orc-service: the wedge records at classification time.** On `:assigned-via` ∈ `#{:mint-domain-child
:mint-sibling-domain-child}`: dispatch `:ontology/mint-domain-child` FIRST (so the concept and edge exist), then the
existing CV-1 signature capture on the child (it fires on `:was-fresh-mint? true` already), then `assign-task-class`
with the domain facts. On `:land-on-domain-child`: no mint, no capture (not a fresh mint), `assign-task-class` with the
facts. On a `:domain-deferral`: `assign-task-class` (the structural assignment stands) carrying `:domain-deferral`, plus
`record-task-classification-deferral` with `:fallback-source :domain-coverage`. All through the existing
`run-or-defer-classification-effect!` wrapper, in the same order as the existing effects.

## TDD cycle list

1. **RED** (ontology) `rs3_domain_child_birth_test.clj`: `:ontology/mint-domain-child` on a sync test context creates
   the child concept with `:label` = the domain label, the parent's narrower set contains the child (assert on
   `get-narrower-concepts`), and `:ontology/domain-child-minted` is emitted; minting again emits nothing new. RED (no
   such command). GREEN.
2. **RED**: `default-domain-children-fn` (via a `:tree-class` `:match` through `classify-task` with `search-descriptions`
   and `rerank!` stubbed, real store) returns the child with its label after cycle 1's mint; before the mint it returns
   `[]`. GREEN.
3. **RED**: `get-tree-class-children` returns a child whose only description is at `:tree-class` scope (record a
   `:tree-class` claim as CV-1 does). GREEN. Guard: a seeded child described at `:tree-fingerprint` scope is still
   returned (`walk_down_classifier_test` green unchanged).
4. **RED** (orc-service, live processor path with `rerank!` and `search-descriptions` redefined to a typed payload,
   `h/with-async-test-context`): a `:tree-class` match with no children and a `:partial` verdict yields, after the
   projectors settle, a classified event carrying `:assigned-via :mint-domain-child`, the parent, the label, the verdict
   and the considered labels; a `:representative-use` claim on the child; and the SKOS edge from child to parent. GREEN.
5. **RED**: the same task again yields `:land-on-domain-child` with the same child identity and no second mint event, no
   second claim. GREEN. (This is the durable proof of `DomainChildIdentityIsStable` and `LandOnDomainChild`.)
6. **RED**: an `:unknown` verdict yields the structural assignment plus a deferral event with `:fallback-source
   :domain-coverage` and reasoning naming the domain axis; no child, no edge, no claim. GREEN.
7. **Guard**: classification stays one per campaign occurrence (re-delivery emits nothing new); walk-down's own mint
   and the uncertain path are unchanged; every existing wedge, claim-capture, observability, seeds and walk-down suite
   is green unchanged. Regression: `r-inject-classifier-context-test`, `cc6-cv1-claim-capture-test`,
   `el3-wedge-skip-uncertain-test`, `cc23-classification-observability-test`, `rr1-classify-placement-test`
   (orc-service); `seeds-test`, `walk-down-classifier-test`, `rs2-domain-child-classifier-test`,
   `rs1-domain-verdict-test`, `el1b-convergence-capture-test`, `cc23-ranked-candidates-test` (ontology).

Propagate note (orchestrator): this slice owns `rule-entity-creation.MintDomainChild.1` and
`rule-entity-creation.MintSiblingDomainChild.1` (the graph edge), and must keep `invariant.DeferralIsVisible`,
`invariant.ClassificationIsOnePerCampaign` and `invariant.DecidedRankingIsRecorded` green. Expected coverage line:
`2 obligations, 2 covered, 0 uncovered` for the new ones; report the three invariants' suites as still green.

## Disciplines (verbatim — do not summarise, do not skip)

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions.
  Reproduce -> minimize -> fix the actual cause. Don't blame the network or the model — the cause is in the code or
  the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can
  fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red -> green -> refactor, one test at a time.** Vertical tracer-bullet slices, never
  horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive
  refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, network, storage)
  as capabilities that **default to the real impl and are faked in tests**.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing. Then
  turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's
  "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.

Standing rules for this arc:

- **Never weaken a generated test to make it pass.** A `/propagate`-generated test is contract. If it is wrong, the
  spec is wrong: report it, the orchestrator `/tend`s and re-propagates.
- **A generated test green before you implement is a finding**, not success — already-covered or vacuous. Report it.
- **No regex or phrase matching over model-authored prose — in tests or in production.** Assert on structured data the
  runtime emits: typed reranker fields, event bodies, identities, concept-graph edges, rendered values the test itself
  injected. The reranker is stubbed with a typed payload the test constructs; only the live sweep hears the real model.
- **Fitness keeps meaning shape-and-intent fit.** Coverage is a separate discrete verdict; nothing reads a domain
  judgement off the fitness number or off any similarity threshold.

## Do NOT touch

- `specs/*.allium`; the reranker (RS-1); RS-2's branch logic (only its default lookup's source of the label changes, as
  stated); the prepend renderer (RS-4); harvest, the consolidator, the retrieval gate; the description body slot (claims
  only — CC-6: no `record-tree-class-description` from the wedge).

## Report back

Files changed; each cycle's RED and GREEN `RESULT` lines verbatim; the classified event, the mint event, the claim and
the narrower set from cycle 4 as data; the coverage line; anything in `docs/*.md` describing classification that is now
stale (list, do not edit); what you could NOT verify; the orphan-JVM check.
