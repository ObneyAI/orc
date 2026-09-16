# RS-2 handoff — The classifier mints a domain child from an uncovered leaf match

Issue: `docs/issues/r-inject-specialisation/RS-2-the-classifier-mints-a-domain-child-from-an-uncovered-leaf-match.md`
(read it whole). Work in `/Users/darylroberts/Desktop/Code/orc-r-inject-specialisation` (branch
`feature/r-inject-specialisation`). Runner: the same command RS-1's brief gives (a `clojure -M:dev:test -e` run from
this worktree, one JVM at a time, 0 orphans after every run; never kill a JVM you did not start). Do not run `poly test`
or any whole-brick build. Do not commit, push or stash. Never edit `specs/*.allium`.

## Goal

After the existing match / bundle / walk-down / deferral logic has produced an outcome, the classifier applies the
assigned candidate's domain verdict and label (RS-1's `:domain-coverage` / `:domain-label` / `:domain-reasoning`,
which today stop at the rerank return value) and produces the domain-child assignment the spec's three rules describe.
Pure classifier work: no event is recorded here (RS-3 does that); the result map carries everything RS-3 needs.

## Read first

1. Spec excerpt, verbatim (`specs/ontology.allium`):

       rule MintDomainChild {
           when: TaskClassified(occurrence, outcome, assigned_class, coverage, domain_label)
           requires: outcome = matched
           requires: assigned_class.children.count = 0
           requires: coverage in {partial, uncovered}
           let child_identity = stable_domain_child_identity(assigned_class, domain_label)
           ensures: DomainChildMinted(parent: assigned_class, label: domain_label, identity: child_identity, minted_by: occurrence)
           ensures: ConceptRelationship.created(source: child_identity, target: assigned_class, predicate: "skos:broader", ...)
       }
       rule LandOnDomainChild {
           when: TaskClassified(occurrence, outcome, assigned_class, coverage, domain_label)
           requires: outcome = matched
           requires: assigned_class.children.count > 0
           requires: assigned_class.children.any(child => child.domain_label = domain_label)
           let child = assigned_class.children.find(child => child.domain_label = domain_label)
           ensures: TaskAssignedToDomainChild(child: child, minted_by: occurrence)
       }
       rule MintSiblingDomainChild {
           when: TaskClassified(occurrence, outcome, assigned_class, coverage, domain_label)
           requires: outcome = matched
           requires: assigned_class.children.count > 0
           requires: assigned_class.children.none(child => child.domain_label = domain_label)
           let child_identity = stable_domain_child_identity(assigned_class, domain_label)
           ensures: DomainChildMinted(...)  ensures: ConceptRelationship.created(...)
       }
       @invariant DomainChildIdentityIsStable  -- identity derived from parent + the canonical (chosen) label
       @invariant DomainChildrenAreAlwaysConsidered  -- a matched class with domain children is not a leaf
       @invariant DomainCoverageIsJudgedNotInferred  -- unknown/missing/malformed defers on the domain axis

2. `components/ontology/src/ai/obney/orc/ontology/interface.clj` `apply-rerank` (~line 857; the join at ~952–954
   `assoc`s only `:reasoning`, `:fitness-score`, `:rerank-source` from each reranked entry onto the candidate). Widen it
   to also carry `:domain-coverage`, `:domain-label`, `:domain-reasoning` (present since RS-1; `:unknown`/nil when the
   model omitted them). Candidates passed INTO the rerank may carry `:existing-domain-children` (RS-1 accepts it) — the
   classifier supplies it (below).
3. `components/ontology/src/ai/obney/orc/ontology/core/task_classifier.clj`:
   - `classify-task` (line 612) and its result contract (docstring ~618–660); the `:assigned-via` stamps at ~763
     (`:bundle`), ~775 (`:mint`), ~792/808 (`:match`), ~834 (walk-down). The new work applies AFTER a `:match` result
     for a candidate on the `:tree-class` axis; `:bundle`, walk-down's own `:mint`, and `:uncertain` are untouched.
   - `get-tree-class-children` (line 294): reads the concept graph's narrower concepts of a parent and drops children
     without a description at `:tree-fingerprint` scope. RS-P2 found runtime children describe themselves at
     `:tree-class` scope, so today a domain child would be invisible here — RS-3 owns that fix; for RS-2 the parent's
     children and their labels come through an INJECTED capability on the context (`:domain-children-fn`, `(fn [ctx
     parent-id] -> [{:target-id .. :domain-label ..}])`), defaulting to a real implementation that reads the narrower
     concepts and each child's `:domain-label` (a concept-projected field RS-3 will populate; absent → no children).
     Tests fake it. This is the injected-capability seam pattern; do not read the store directly in the new logic.
   - `walk-down-from` (~line 346): unchanged; but per `DomainChildrenAreAlwaysConsidered`, when the matched
     `:tree-class` leaf HAS domain children (per the seam), the sibling logic below runs regardless of the specificity
     gate.
   - Identity: `stable-uuid-from` in `core/commands.clj` (~line 825, `nameUUIDFromBytes`) is the established
     derivation; derive the child identity from the string `"domain-child:" + parent-id + ":" + canonical-label` with
     the same helper (copy the one-liner into the classifier or expose it — do not import commands into the pure ns).
   - Label canonicalisation is the JUDGED choice (D7/D7b): pass the parent's existing children labels to the rerank as
     `:existing-domain-children` on that candidate so the model reuses one when it fits (RS-1's instruction demands
     it); the classifier then trusts `:domain-label` verbatim. The only normalisation you may apply is trim + lower-case
     + spaces→hyphens (a formatting rule, not a matching rule; document it).
4. RS-1's evidence and shapes: `rs1_domain_verdict_test.clj`; the probes' findings
   (`development/bench/ood-stress-results/rs-p1b2-sibling-reuse-separated-probe/FINDINGS.md`) — in particular: once a
   class has children the reranker calls it `covered` regardless, so the verdict is consulted ONLY when the parent has
   no children (D7b).
5. Existing tests to mirror: `el3_three_state_outcome_test.clj`, `walk_down_classifier_test.clj`,
   `el1b_convergence_capture_test.clj`, `cc23_ranked_candidates_test.clj` (how `search-descriptions`/`rerank!` are
   stubbed with typed payloads).

## The exact change (result shape RS-3 consumes)

On a `:match` on the `:tree-class` axis, with `parent` = the assigned class and `verdict` = the assigned candidate's
`{:domain-coverage :domain-label :domain-reasoning}`:

- children = `(domain-children-fn ctx parent-id)`.
- **No children:** `coverage` ∈ `#{:partial :uncovered}` → assign the derived child identity; `:assigned-via
  :mint-domain-child`; `:parent-tree-id parent-id`; `:domain-label label`; `:was-fresh-mint? true`.
  `:covered` → unchanged `:match` result, plus `:domain-verdict verdict` carried.
  `:unknown` (or missing/malformed, which RS-1 already folds to `:unknown`) → unchanged `:match` result plus
  `:domain-deferral {:axis :domain :reason ...}` and `:domain-verdict`; NO child.
- **Children present:** a child whose `:domain-label` equals the (normalised) label → assign THAT child's identity;
  `:assigned-via :land-on-domain-child`; `:parent-tree-id parent-id`; `:was-fresh-mint? false`. Otherwise → the derived
  sibling identity; `:assigned-via :mint-sibling-domain-child`; `:was-fresh-mint? true`. The verdict is NOT consulted.
- A nil/blank label when a child would be minted → treat as `:unknown` (defer; no child from an empty label).
- Every result carries `:domain-verdict` and `:domain-children-considered` (the labels seen) so RS-3 can record them.
- Walk-down's own `:mint` (fresh leaf under a matched ancestor) and `:bundle` results pass through untouched.

## TDD cycle list

1. **RED** `rs2_domain_child_classifier_test.clj`: `apply-rerank`'s join carries the three domain keys onto the
   candidate (stub `rerank!` with an RS-1-shaped payload; assert the joined candidate). RED today.
2. GREEN.
3. **RED** no children, `:partial` → derived identity, `:mint-domain-child`, parent, label; same task again → identical
   identity (stability). RED today. GREEN.
4. **RED** no children, `:uncovered` → same as 3; `:covered` → byte-identical to today's `:match` result plus
   `:domain-verdict`. GREEN.
5. **RED** `:unknown` and a missing verdict → `:match` plus `:domain-deferral {:axis :domain}`, no child, no
   identity change. GREEN.
6. **RED** children present, label equals a child's → `:land-on-domain-child` with that child's identity, verdict
   ignored even when `:covered`. GREEN.
7. **RED** children present, new label → `:mint-sibling-domain-child` with the derived identity, verdict ignored.
   GREEN.
8. **RED** a matched leaf with children is considered even when top-1 fitness ≥ the specificity threshold (0.95): the
   sibling logic runs. GREEN.
9. **Guard**: `:bundle`, walk-down's `:mint`, `:uncertain` and a `:tree-fingerprint`-axis match are byte-identical to
   today; blank label → deferral. Regression: `el3-three-state-outcome-test`, `walk-down-classifier-test`,
   `el1b-convergence-capture-test`, `cc23-ranked-candidates-test`, `sio4-classifier-honesty-test`,
   `rr1-reranker-timeout-resilience-test`, `rs1-domain-verdict-test`, `r05b-classify-behaviors-test`.

Propagate note (orchestrator): the obligations this slice owns are `rule-success.MintDomainChild`,
`rule-failure.MintDomainChild.1/.2/.3`, `rule-success.LandOnDomainChild`, `rule-failure.LandOnDomainChild.1/.2/.3`,
`rule-success.MintSiblingDomainChild`, `rule-failure.MintSiblingDomainChild.1/.2/.3` — twelve, all uncovered today
(RED by absence); `rule-entity-creation.*.1` (the graph edge) belongs to RS-3. Expected coverage line for this slice:
`12 obligations, 12 covered, 0 uncovered`.

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

- `specs/*.allium`; the reranker instruction and parse (RS-1); the wedge (orc-service), any command, event, read model
  or projector (RS-3); the prepend renderer (RS-4); harvest, the consolidator, the retrieval gate, the bundle band,
  walk-down's own leaf mint.

## Report back

Files changed; each cycle's RED and GREEN `RESULT` lines verbatim; one result map per branch (`:mint-domain-child`,
`:land-on-domain-child`, `:mint-sibling-domain-child`, deferral); the derived-identity rule stated; the coverage line;
what you could NOT verify; the orphan-JVM check.
