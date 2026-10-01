# CV-A handoff — classifier core bundle (families, protection, leaf, zero band, birth description)

Issue index: `docs/issues/domain-child-convergence/README.md`; PRD `docs/prd/domain-child-convergence.md`; decisions
`docs/build-timeline/grill-sessions/domain-child-convergence-decisions.md` (C0–C6); ADR 0007; plan `PLAN.md`
("Verified code seams"). Work in `/Users/darylroberts/Desktop/Code/orc-convergence-arc` (branch
`feature/domain-child-convergence`). Runner: `clojure -J-Djava.awt.headless=true -J-Xmx1600m -M:dev:test -e "…"` from
THIS worktree, one JVM at a time, 0 orphans; never kill a JVM you did not start. No `poly test`. No commits. Never
edit `specs/*.allium`. Another agent (CV-B) is editing `components/orc-service/src/.../core/todo_processors.clj`
(render section only), `executor.clj` and `rlm_sandbox.clj` on this worktree — do not touch those files; you own
`components/ontology/**` and the wedge's classification dispatch ONLY if a new command must be dispatched (coordinate
by keeping wedge edits to the `assignment-command`/mint dispatch block, lines ≈648–760, and say exactly what you
changed).

## Goal

After this bundle, on the pure classifier and the durable path: a later paraphrase of a domain lands on the family
minted first for it whatever shape scored top-1 (tenant-wide lookup by canonical label through the concept graph); an
in-domain task whose covering seed sits in the ranking is assigned to that seed, never minted under a neighbour; a
family is a leaf on the domain axis (reaching it by match, walk-down or index is a landing with the domain facts on
the event); a newborn family is matchable but not surfaced at total 0 while curated seeds still surface; every family
is born with a rich self-description through the claim path and an embedding on its concept; the reranker is asked
for task-family labels and shown a bounded tenant-wide label list. The merge judge (CV-C) is NOT in this bundle:
leave a clearly named seam `:domain-merge-fn` on the ctx whose DEFAULT returns `{:kind :new}` (so today's mint
behaviour is unchanged until CV-C lands) and thread its result into the two mint decisions.

## Spec excerpts (verbatim; the obligations you own)

```
    @invariant DomainChildIdentityIsStable
        -- A domain child's identity is derived from the shape it was born under
        -- and the canonical domain label, once, at its first mint — so every
        -- identity minted before this arc stays valid — and a later occurrence of
        -- the same domain FINDS it by that canonical label across all of the
        -- tenant's domain families through the concept graph, whatever shape
        -- retrieval scored top-1 this time (convergence grill C0/C1; ADR 0007).
        -- The family's parent edge is its birth shape, which the render shows as
        -- the shape the task matched. The label is canonical because it is
        -- CHOSEN: the reranker is shown the tenant's existing family labels (a
        -- bounded list) and reuses one when it fits, coining a new label only
        -- when none does — a judged choice, never a string rule.

    @invariant DomainFamilyDescribesItselfFromBirth
        -- A family is born with a rich, self-contained self-description through
        -- the claim path: its purpose (subject matter, material, output kind —
        -- drawn from the verdict's reasoning and the task), the shape it was born
        -- under and that shape's role, the signature that minted it, and the
        -- slots that fill as evidence lands (worked pattern, successes,
        -- failures, related families); its concept is embedded at birth so the
        -- hybrid search over the tree-class ontology (graph proximity and
        -- embedding similarity, rank-fused) finds it by meaning. Retrieval and
        -- the merge judge compare substance, never labels alone (grill C2).

    @invariant DomainFamilyMergeIsJudged
        -- A classification that would mint a domain family first retrieves the
        -- nearest existing families BY RANK (merge_candidate_count of them, no
        -- similarity cutoff; an empty neighbourhood is "new" without a call) and
        -- asks one discrete question with their full descriptions in view: same
        -- family as one of these (named), a new family, or unknown — reason
        -- before verdict. Same lands on that family, new mints, unknown defers on
        -- the domain axis (reason merge_unresolved) and mints nothing. The
        -- question is never asked on a landing or a covered match, and it is
        -- never replaced by a threshold over a similarity number (grill C3).

    @invariant CoveredSeedProtection
        -- Before the domain axis runs, a tree-class candidate in the ranking
        -- that is judged covered, has no domain families of its own, and is at
        -- or above the ordinary match threshold wins the assignment over a top
        -- match that would mint; the passed-over shape is recorded on the
        -- classified event (domain_selection). An authored class keeps its own
        -- traffic. A parent that already has families saying "covered" is not
        -- evidence (the D7b finding) and never protects (grill C4).

    @invariant DomainFamilyIsALeafOnTheDomainAxis
        -- A domain family is never a shape class for the domain axis: any match
        -- or walk-down that resolves to a family is a LANDING on it (its parent
        -- is its own broader edge, its label its concept label, the verdict not
        -- consulted), so there are no grandchildren and the classified event
        -- carries the domain facts however the family was reached. A task that
        -- reaches a family but is judged a different domain mints under the
        -- retrieved SHAPE, never under the family (grill C5).

    @invariant DomainChildrenAreAlwaysConsidered
        -- A matched shape class is never treated as a leaf on the domain axis
        -- merely because retrieval was confident: after EVERY tree-class match,
        -- whatever the top match's fitness (the specificity threshold that lets a
        -- confident match skip the walk-down does not skip this), the
        -- classification runs covered-seed protection, then the tenant-wide
        -- family lookup by canonical label, then — only when it would mint — the
        -- merge judge, so a repeat of a domain lands on its family even when the
        -- coverage verdict does not repeat. The one carve-out is the deferral
        -- above: a blank label, a failed family lookup or an unresolved merge
        -- leaves the task on the shape class with the deferral recorded, rather
        -- than guessing a family.

    @invariant NewbornFamilyIsMatchableNotSurfaced
        -- A family that has not yet reached the retrieval gate is kept among the
        -- candidates a classification can land on (matching stays ungated, else a
        -- hidden class could never recur and never be surfaced) but is not shown
        -- to the model as a pattern until it has evidence; a curated seed at zero
        -- occurrences is surfaced as before (grill C6).
}

```

```

    ensures: BaselineDescriptionsRecorded(
        scopes: { node_type, tree_class, behavioral_subtree },
        preserve_history: true
    )
}

-- R-Inject specialisation grill D2/D3/D4 (ADR 0006) and the convergence grill
-- C0–C8 (ADR 0007): a task that matches a SHAPE class on fitness but whose
-- domain that class does not cover gets its own identity — a domain family —
-- so recurrence, consolidation and harvest accrue per domain instead of
-- blurring the shape class. The trigger is the classify contract's
-- DECISION-TIME fact: a tree-class match, with the whole ranking and each
-- candidate's domain verdict, BEFORE the domain axis is applied and before the
-- classified event is recorded — the classified event then carries whichever
-- assignment these rules decide (a shape class, a covering class, or a family),
-- its provenance, the verdict, the families considered, the merge verdict and
-- any deferral (DecidedRankingIsRecorded).
-- shape_class is the matched tree-class concept and is never itself a family
-- (DomainFamilyIsALeafOnTheDomainAxis); ranking is the retrieval's top-k with
-- per-candidate verdicts. Black boxes: canonical_domain_label(label) is the
-- formatting rule (trim, lower-case, whitespace to hyphen; null when blank —
-- never a synonym list or a match over prose); domain_family(canonical_label)
-- is the tenant-wide lookup of the family carrying that label through the
-- concept graph (agent-authored tree-class concepts), null when none;
-- covered_leaf_neighbour(ranking) is a candidate judged covered, with no
-- families of its own, at or above the match threshold, or null;
-- nearest_families(signature, reasoning, ranking) is the hybrid search's ranked
-- neighbourhood bounded by merge_candidate_count; merge_verdict(...) is the
-- judge's discrete answer over those families' full descriptions (a named
-- family, new, or unknown); domain_child_identity(shape_class, canonical_label)
-- is the stable derivation (DomainChildIdentityIsStable); birth_description(...)
-- is the rich self-description of DomainFamilyDescribesItselfFromBirth.
-- Order (DomainChildrenAreAlwaysConsidered): CoveredSeedWins → LandOnDomainFamily
-- → MergeIntoDomainFamily → MintDomainFamily.
rule CoveredSeedWins {
    when: LeafShapeMatched(occurrence, shape_class, ranking, coverage, domain_label)
    let neighbour = covered_leaf_neighbour(ranking)
    requires: neighbour != null
    requires: coverage in {partial, uncovered}
    requires: domain_family(canonical_domain_label(domain_label)) = null

    ensures: TaskAssignedToShape(
        shape: neighbour,
        provenance: matched,
        domain_selection: neighbour,
        passed_over: shape_class,
        minted_by: occurrence
    )
}

rule LandOnDomainFamily {
    when: LeafShapeMatched(occurrence, shape_class, ranking, coverage, domain_label)
    let canonical_label = canonical_domain_label(domain_label)
    requires: canonical_label != null
    let family = domain_family(canonical_label)
    requires: family != null

    -- The classified event's assignment IS the landing: the family's identity,
    -- the landing provenance and the family's label; the family's parent stays
    -- its birth shape; no concept work, no second occurrence.
    ensures: TaskAssignedToDomainChild(
        child: family,
        provenance: landed_on_domain_child,
        minted_by: occurrence
    )
}

rule LandOnReachedDomainFamily {
    when: DomainFamilyReached(occurrence, family)

    -- A match or walk-down that resolves to a family is a landing on it, however
    -- it was reached (DomainFamilyIsALeafOnTheDomainAxis).
    ensures: TaskAssignedToDomainChild(
        child: family,
        provenance: landed_on_domain_child,
        minted_by: occurrence
    )
}

rule MergeIntoDomainFamily {
    when: LeafShapeMatched(occurrence, shape_class, ranking, coverage, domain_label)
    let canonical_label = canonical_domain_label(domain_label)
    requires: canonical_label != null
    requires: covered_leaf_neighbour(ranking) = null
    requires: domain_family(canonical_label) = null
    requires: coverage in {partial, uncovered}
    let candidates = nearest_families(occurrence.signature, occurrence.domain_reasoning, ranking)
    let verdict = merge_verdict(occurrence.signature, candidates)
    requires: verdict.kind = same

    ensures: TaskAssignedToDomainChild(
        child: verdict.family,
        provenance: landed_on_domain_child,
        merge_verdict: verdict,
        minted_by: occurrence
    )
}

rule MintDomainFamily {
    when: LeafShapeMatched(occurrence, shape_class, ranking, coverage, domain_label)
    let canonical_label = canonical_domain_label(domain_label)
    requires: canonical_label != null
    requires: covered_leaf_neighbour(ranking) = null
    requires: domain_family(canonical_label) = null
    requires: coverage in {partial, uncovered}
    let candidates = nearest_families(occurrence.signature, occurrence.domain_reasoning, ranking)
    let verdict = merge_verdict(occurrence.signature, candidates)
    requires: verdict.kind = new
    let child_identity = domain_child_identity(shape_class, canonical_label)
    let child_uri = tree_class_uri(child_identity)

    -- The family's concept, its parent edge, its rich birth description and its
    -- embedding are born together, before anything references it; minting the
    -- same identity again creates nothing. A shape that already has families
    -- mints a new one the same way (the verdict decided it is a new domain).
    ensures: Concept.created(
        ontology: shape_class.ontology,
        uri: child_uri,
        label: canonical_label,
        description: birth_description(occurrence, shape_class, canonical_label),
        scope: tree_class,
        provenance: agent_authored_provenance(occurrence),
        broader_uris: [shape_class.uri],
        indicators: [],
        embedding: concept_embedding(birth_description(occurrence, shape_class, canonical_label)),
        created_at: now,
        updated_at: null
    )
    ensures: ConceptRelationship.created(
        source: Concept with uri = child_uri,
        target: shape_class,
        predicate: "skos:broader",
        metadata: null,
        created_at: now
    )
    ensures: DomainChildMinted(
        parent: shape_class,
        label: canonical_label,
        identity: child_identity,
        minted_by: occurrence
    )
    ensures: TaskAssignedToDomainChild(
        child: child_identity,
        provenance: domain_child_minted,
        merge_verdict: verdict,
        minted_by: occurrence
    )
}


```

You own: `rule-success`/`rule-failure` of `CoveredSeedWins` (4), `LandOnDomainFamily` (3),
`LandOnReachedDomainFamily` (1), `MintDomainFamily`'s success, its failure obligations NOT about the merge verdict,
and its two entity-creation obligations (the concept with label/scope/provenance/broader/embedding; the edge);
invariants `DomainChildIdentityIsStable`, `DomainFamilyDescribesItselfFromBirth`, `CoveredSeedProtection`,
`DomainFamilyIsALeafOnTheDomainAxis`, `DomainChildrenAreAlwaysConsidered`, `NewbornFamilyIsMatchableNotSurfaced`.
Expected coverage line: `16 obligations, 16 covered, 0 uncovered` (the merge-verdict failure obligations and
`MergeIntoDomainFamily` belong to CV-C — list them as "owned by CV-C", not as uncovered).

## Read first

- `components/ontology/src/ai/obney/orc/ontology/core/task_classifier.clj`: `classify-task` (top-1 pick ≈909;
  `top-1-confident?` ≈1016; the walk-down provenance cond ≈1040–1056; the `maybe-assign-domain-child` wrapper
  ≈934/1056), `maybe-assign-domain-child` (≈806, receives only top-1 and widens only `:match`), `assign-domain-child`
  (≈732; arms 759/762–788/792/794–804), `default-domain-children-fn` (≈690), `canonicalize-domain-label` (≈662),
  `stable-domain-child-identity` (≈678), `gate-candidates` (≈524; band doc 534–547), `get-consolidation-total*` (55),
  `walk-down-from`/`pick-best-child`/`get-tree-class-children` (≈298–424), `domain-deferral` (≈723).
- `components/ontology/src/ai/obney/orc/ontology/interface.clj`: `existing-domain-child-labels` (≈741),
  `enrich-candidate-evidence` (≈767), `apply-rerank` JOIN (≈978–997 — every top-k candidate carries
  `:existing-domain-children`, `:domain-coverage`, `:domain-label`, `:fitness-score`), `search-descriptions` (≈1030),
  `get-concepts [ctx {:scope :tree-class}]` (≈178 → read_models ≈2381) + provenance `{:kind :agent-authored}`,
  `get-broader-concepts` (≈197), `hybrid-search` (≈1767), `embed-concept` command (`core/commands.clj` ≈717).
- `components/ontology/src/ai/obney/orc/ontology/core/reranker.clj`: `domain-coverage-section` (≈24–75; the label
  paragraph 47–60; the six-key contract byte-pinned by `rs1_domain_verdict_test`), `reranker-workflow` blackboard +
  `:reads` (≈285–294), `rerank!` inputs (≈479–481), `parse-reranked-json` whitelist (≈427).
- `components/ontology/src/ai/obney/orc/ontology/core/commands.clj`: `mint-domain-child` (≈588–667),
  `record-claim-deltas`, `assign-task-class` (domain keys), `record-task-classification-deferral`.
- Read-models: descriptions `assemble-body`/claim fold; concepts projection (`:label :broader :provenance`).
- orc-service wedge: `maybe-auto-classify-and-set-context` (≈503–850): mint → capture → assign → deferral dispatch;
  the checkpointed commit's effect rules (`orc_service/core/commands.clj` `commit-researcher-classification`,
  `interface/schemas.clj` `valid-researcher-classification-commit?`) — a new effect command MUST be added to both.
- Tests: `rs2_domain_child_classifier_test.clj` (fixtures `tree-class-candidate`, the `:domain-children-fn` seam;
  the two Slice 0 tests `newborn-as-top-1-match-with-partial-mints-a-grandchild-today` and
  `newborn-as-top-1-match-with-covered-is-a-plain-match-with-no-domain-label` are MEANT to flip — rename them to
  state the new behaviour), `rs7_newborn_reach_test.clj` (routes a/b/c1/c2 — c2 flips: no grandchild; b and c1
  flip: landing), `rs5_domain_child_chain_test.clj` (`walk-down-into-a-newborn-returns-walk-down-provenance-not-a-landing`
  flips; `cycle2-retrieval-gate-band-on-the-domain-child` total-0 assertion flips to matched-not-surfaced),
  `el1b_convergence_capture_test.clj` (`gate-passes-curated-seed-tree-class-at-total-zero` must STAY green),
  `rs3_domain_child_birth_test.clj`, `rs3_domain_child_durable_test.clj`, `rs1_domain_verdict_test.clj`,
  `rs6_weed_test.clj`, `walk_down_classifier_test.clj`, `seeds_test.clj`, `cc23_*`.

## The exact change

1. **Family lookup tenant-wide** — `existing-domain-families [ctx]` (interface or task_classifier): every tree-class
   concept with provenance `:agent-authored` and a non-blank label ≠ its id → `{:target-id :domain-label :parent-id}`
   (parent from `:broader`), most recently minted first, bounded by `family_label_list_bound` (50). Injected seam
   `:domain-families-fn` (default reads the concepts read-model; failure → domain deferral
   `:families-lookup-failed`, never fail open). `assign-domain-child` (or its replacement) lands by canonical label
   over this set BEFORE any per-parent logic; `:parent-tree-id` on the result = the family's own parent.
2. **Covered-seed protection** — pure `covered-leaf-neighbour [candidates families threshold]`: the first
   `:tree-class` candidate (rank order) with `:domain-coverage :covered`, no families under it, fitness ≥ the match
   threshold; applied before the domain axis when top-1 would mint (coverage partial/uncovered and no family for
   the label). Result: assigned = the neighbour, `:assigned-via :match`, plus
   `:domain-selection {:preferred <id> :over <top-1-id> :reason :covered-leaf-neighbour}`; forward it on the
   assign command and classified event (schema: optional map).
3. **Family is a leaf** — a `:match` or `:walk-down` whose assigned class is a family (seam `:domain-family-parent-fn`:
   reads the class's `:broader` + concept label; failure → deferral) becomes `:assigned-via :land-on-domain-child`,
   `:parent-tree-id` = its broader, `:domain-label` = its label, verdict not consulted; never runs the domain axis
   against a family. Apply on all three reach routes (graph landing already is a landing; walk-down; index match).
4. **Zero band** — `gate-candidates` gets seam `:newborn?-fn` (default: concept provenance `:agent-authored`); total 0
   ∧ newborn → excluded from the surfaced list, kept in matching candidates; seeds unchanged.
5. **Birth description + embedding** — on a mint, after the concept and edge, record through the claim path (never a
   body write) the family's rich self-description: a `:capability` claim carrying the purpose (subject matter,
   material, output kind — from the verdict's `:domain-reasoning` and the task signature), a `:representative-use`
   claim with the birth signature (exists today), and a `:guard`/`:context` claim naming the birth shape and its
   role; then dispatch `:ontology/embed-concept` for the child concept (or emit the embedding in the mint command if
   the embed path is synchronous). The wedge dispatches these inside the same bounded set (checkpointed commit rules
   updated: the embed and the extra claims are allowed effects tied to the mint).
6. **Reranker** — instruction paragraph (47–60) rewritten to task-family granularity ("the label names the FAMILY of
   task: subject matter and output kind, never the instance's material …; reuse a listed label unless subject matter
   AND output kind both differ"); new blackboard slot `:existing-domain-labels [:vector :string]` (reads, INPUTS
   DESCRIBED, `rerank!` inputs, `search-descriptions` rerank path passing the bounded list). Update
   `rs1_domain_verdict_test`'s byte-pin to the new text (the orchestrator authorises this one edit to that pin).
7. **Merge seam only** — `:domain-merge-fn` on ctx, default `(fn [_ _] {:kind :new})`, called at the two mint
   points with `(merge-fn ctx {:signature … :reasoning … :ranking … })`; `:kind :same` with `:family` lands,
   `:new` mints, anything else defers `:merge-unresolved`. CV-C replaces the default.
8. **Event/schema** — classified event and assign command gain optional `:domain-selection`, `:merge-verdict`;
   deferral reasons enum gains `:families-lookup-failed`, `:merge-unresolved`; new/changed commands validated in the
   checkpointed commit (both copies of the rule).

## Tests (the bundle's proof; write them all, then one RED run)

rs2: `label-existing-under-another-parent-lands-on-that-family` (top-1 = shape B, family under shape A with the
label → landing on A's family, `:parent-tree-id` A); `covered-leaf-neighbour-at-threshold-wins-over-a-partial-top-1`;
`covered-neighbour-with-families-does-not-protect`; `no-neighbour-is-byte-identical`; `protection-runs-before-family-landing`
(a mis-parented family exists for the label AND a covered seed sits in the ranking → the seed wins); the two Slice 0
flips renamed (`newborn-as-top-1-match-lands-on-the-family-no-grandchild`, `...-with-covered-lands-on-the-family`);
`families-lookup-failure-defers`. rs7_newborn_reach: b and c1 → landing, c2 → landing (no grandchild). rs5:
walk-down flip; cycle2 total-0 → matched-not-surfaced. el1b: `gate-hides-newborn-family-at-total-zero` beside the
seed test. rs3 durable: cross-parent landing records no concept and carries `:parent-tree-id` = the family's parent;
a mint records the birth claims (three kinds) and an embedded concept; the checkpointed commit accepts the extra
effects and rejects them beside a landing. rs1: byte-pin updated; `rerank-inputs-carry-the-bounded-label-list`.
Guards: everything else in the list above green unchanged.

Live QA (you): none live for this bundle (no LLM). The orchestrator runs the sweeps.

## Disciplines (verbatim — do not summarise, do not skip)

- **Never assume. Chase every bug to its ROOT CAUSE.** Reproduce → minimise → fix the actual cause; rule out the
  harness itself.
- **Bundle discipline (user direction):** write the whole bundle, run its new tests once RED before implementing the
  pure parts (capture the RESULT line), implement, GREEN, then the live QA named below. Do not run a JVM per tiny
  change; troubleshoot the bundle together.
- **Test behavior through public interfaces**, on structured data the runtime emits (provenance keywords, identities,
  labels, verdict maps, event bodies, graph edges, rendered values the test injected) — never regex or phrase matching
  over model-authored prose, in tests or in production.
- **Injected-capability seams** default to the real implementation and are faked in tests; a store failure in a
  lookup defers, never fails open.
- **Never weaken an existing test to make it pass**; if an existing assertion breaks, report it as a finding with the
  RESULT line and your reading (some Slice 0 characterisation tests are MEANT to flip — the brief names them).
- **Report faithfully** — including your own mis-steps and anything you couldn't verify. Never write the API key.

## Do NOT touch

`specs/*.allium`; the render section and `format-*` fns of orc-service `todo_processors.clj`, `executor.clj`,
`rlm_sandbox.clj` (CV-B); `development/**` (RS-7 tooling); harvest's gate and the consolidator; the existing result
directories.

## Report back

Files changed; RED and GREEN RESULT lines verbatim (one red run of the bundle's new tests, then green, then the full
guard list in one JVM); for every flipped Slice 0 test the new observed result map (pr-str); the coverage line with
the CV-C-owned obligations listed by name; the exact wedge/commit edits; anything you could NOT verify; the orphan
check (`pgrep -fl orc-convergence-arc`).
