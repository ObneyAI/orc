# PRD — Domain-child convergence

Source of truth: `specs/ontology.allium` (contract `TaskClassification`: invariants `DomainChildIdentityIsStable`,
`DomainFamilyDescribesItselfFromBirth`, `DomainFamilyMergeIsJudged`, `CoveredSeedProtection`,
`DomainFamilyIsALeafOnTheDomainAxis`, `DomainChildrenAreAlwaysConsidered`, `NewbornFamilyIsMatchableNotSurfaced`;
rules `CoveredSeedWins`, `LandOnDomainFamily`, `LandOnReachedDomainFamily`, `MergeIntoDomainFamily`,
`MintDomainFamily`; config `merge_candidate_count`, `family_label_list_bound`). Decisions C0–C8 in
`docs/build-timeline/grill-sessions/domain-child-convergence-decisions.md`; ADR 0007. Plan and dossier in
`docs/issues/domain-child-convergence/PLAN.md`.

## Problem statement

The specialisation arc lets a task that matches a shape but not that shape's domain get its own class, so the loop
learns per domain. It works on exact repeats (identity stable on 18 of 21) and fails on realistic traffic: 24
paraphrases of 8 domains produced zero landings and two or three children per domain, because the top shape moves
between paraphrases while identity is scoped to the parent, and because the reranker coins labels at instance
granularity. A newborn child can also become a parent (grandchildren), an in-domain task can be pulled to a
neighbouring shape and mint a mis-parented child, a child reached inside the recurrence band gets no child line,
and what a child has learned is invisible to the model until consolidation. Under real traffic the loop would grow
many thin near-duplicate classes and harvest would never fire.

## Solution

One domain family per domain per tenant. Identity keeps its birth-shape derivation, but a later occurrence finds its
family by canonical label across the tenant, through the concept graph, whatever shape retrieved top-1. A family is
born with a rich self-description and an embedding so the hybrid search finds it by meaning. A classification that
would mint asks one more discrete question against the nearest families' full descriptions: same family, new, or
unknown. A covering class with no families of its own keeps its own traffic. A family is a leaf on the domain axis:
reaching one is a landing, never a mint beneath it. A newborn is matchable but not shown as a pattern until it has
evidence; a family's own accrued body is shown as soon as it has substance. The proof is deterministic tests on
structured data; a 400-task realistic-traffic sweep, run before and after on a frozen corpus, reports convergence
against expectations stated beforehand.

## User stories

1. As the loop, I want every paraphrase of one domain to accrue on one family, so that recurrence, consolidation and harvest see one class.
2. As a task author, I want my task to land on the family already minted for its domain even when retrieval scores a different shape top-1, so that my campaign's evidence joins its siblings.
3. As a curator, I want an authored class that covers a task's domain to win over a neighbouring shape that would mint, so that seeds keep their own traffic and no mis-parented family is born.
4. As the model, I want to be shown the proven shape and the family I am working on, with what that family has already learned, so that my tree benefits from prior successes and avoids recorded failures.
5. As the model, I want a family's description to carry purpose, shape, successes and failures rather than a label, so that retrieval and merging compare substance.
6. As the loop, I want a would-be mint judged against the nearest existing families before it creates anything, so that a variant label does not scatter a domain.
7. As an operator, I want that judgement to cost one model call only when a mint would otherwise happen, so that landings and covered matches stay cheap.
8. As the loop, I want an unresolved merge to defer and record why, so that nothing is inferred from an unanswerable question.
9. As the model, I never want a family used as a shape class for another family, so that there are no grandchildren under one-line parents.
10. As the model, I want a newborn family kept out of the surfaced patterns until it has evidence, while still being landed on, so that I see substance, not a signature line.
11. As the loop, I want every existing family identity to stay valid across this change, so that stores minted before it lose nothing.
12. As a maintainer, I want every new rule branch proven deterministically on structured data, so that the gate does not depend on a live model.
13. As the team, I want a frozen 400-task realistic corpus with ground truth from reviewed briefs, run before and after the change on the same order and policy, so that convergence is measured, not claimed.
14. As the team, I want expectations for that measurement written before the post-fix run, so that a miss is a documented reason and not a moved goalpost.
15. As the model, when the executor rejects an evaluated closure in an emitted tree, I want a hint that names the fix, so that I do not burn iterations on the same rejection.

## Implementation decisions

- C0 convergence = one family per domain per tenant, under its birth shape. C1 identity derivation unchanged; landing by canonical label tenant-wide via the concept graph (agent-authored tree-class concepts). C2 task-family label instruction, bounded tenant-wide label list to the reranker (new blackboard slot), rich birth description through the claim path, concept embedded at birth. C3 merge judge on would-be mints over the hybrid search's ranked neighbourhood (`merge_candidate_count`), reason before verdict, unknown defers. C4 covered-seed protection before landing; selection recorded on the event. C5 family is a leaf on the domain axis; reaching one is a landing on all three routes (graph, walk-down, index). C6 newborn matchable but not surfaced at total 0 (provenance discriminator). C7 family body under the child line whenever it has substance; closure hint preventive and reactive, keyed on ex-data. C8 deterministic gate; sweeps report against stated expectations.
- Ordering inside the classifier: covered-seed protection → tenant-wide landing → merge judge → mint.
- Seams (injected, real defaults, faked in tests): family lookup by label; family parent lookup; merge judge; newborn discriminator; family neighbourhood retrieval.

## Testing decisions

Behaviour through public interfaces on structured data; never regex or phrase matching over prose. Seams: the pure classifier with stubbed reranker and merge payloads (prior art `rs2_domain_child_classifier_test`, `rs7_newborn_reach_test`); the reranker contract (`rs1_domain_verdict_test`, byte-pinned instruction); the wedge through the live processor path and the checkpointed commit (`rs3_domain_child_durable_test`); Seam-4 synthesised events for birth description, embedding and harvest (`rs5_domain_child_chain_test`); the render (`rs4_waterfall_render_test`); the executor hint (constructed ex-info); the RS-7 sweeps and end-to-end subset (evidence).

## Out of scope

Changing harvest thresholds, the consolidator or the bundle band; behavioral-axis classification; authoring domain seeds; the hosted-runner recovery flake (`det-e2e-205`); the GEPA budget flake.
