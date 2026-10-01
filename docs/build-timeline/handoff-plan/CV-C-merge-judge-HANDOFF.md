# CV-C handoff — the judged merge step (bundle)

Issue index `docs/issues/domain-child-convergence/README.md`; decision C3 (and C2, C4) in
`docs/build-timeline/grill-sessions/domain-child-convergence-decisions.md`; ADR 0007; prototype verdict
`development/bench/ood-stress-results/rs-p3-family-merge-probe/FINDINGS.md` (read it whole — it decides the
instruction's wording). Work in `/Users/darylroberts/Desktop/Code/orc-convergence-arc`; runner and rules as in
CV-A's brief; no commits; never edit `specs/*.allium`. Blocked by CV-A (it leaves the `:domain-merge-fn` seam with a
default of `{:kind :new}` at the two mint points; you replace the default).

## Already landed with CV-A (read the code, do not redo)

- Every would-be mint, including a second family under a shape that already has one, calls the `:domain-merge-fn`
  seam through `mint-domain-family-via-merge` in `task_classifier.clj`.
- `:same` is already resolved through the `:domain-family-parent-fn` seam, and a `:same` naming something with no
  family parent already defers `:merge-unresolved`. You still add the check that the named family was among the
  candidates shown to the judge.
- The birth description is the family's label, purpose, the parent shape's own summary and the birth task (no
  ids); the family is embedded at birth or the birth fails. Use that description as the family's text in the
  neighbourhood.

## Goal

When a classification would mint a domain family, the nearest existing families are retrieved by rank and one
discrete question decides: same family (named), new, or unknown. Same lands on that family; new mints; unknown
defers on the domain axis (`:merge-unresolved`) and mints nothing. No call on landings or covered matches; an empty
neighbourhood is "new" without a call.

## Spec (verbatim) — `specs/ontology.allium`

```
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
```
Rules `MergeIntoDomainFamily` (requires `verdict.kind = same`, ensures `TaskAssignedToDomainChild` with the merge
verdict) and `MintDomainFamily` (requires `verdict.kind = new`) — see the file, lines ≈1000–1090. You own
`rule-success`/`rule-failure` of `MergeIntoDomainFamily` (6) and the merge-verdict failure obligations of
`MintDomainFamily`. Config `merge_candidate_count: Integer = 5`.

## The exact change

1. `domain-family-merge-workflow` in `components/ontology/src/ai/obney/orc/ontology/core/reranker.clj` (beside
   `reranker-workflow`): blackboard `{:task :string :reasoning :string :candidates [:vector family-schema]
   :merge-json :string}`; one `orc/llm "merge"` node; its own byte-pinned instruction constant
   `family-merge-instruction` (a test pins it). The instruction, from RS-P3's verdict: a family shares SUBJECT
   MATTER AND OUTPUT KIND; the concrete instance (the dish, the database engine, the runner) never makes a new family;
   a DIFFERENT subject matter is a new family even when the output kind matches, and a different output kind is a
   new family even when the subject matter matches; write merge_reasoning FIRST naming the candidate compared and
   what is shared and what differs on both axes; then verdict `same` (with the family id) / `new` / `unknown`.
   Output keys EXACTLY `merge_reasoning`, `verdict`, `family`. Parse both a map and a JSON string (function calling
   returns a map — the RS-P3 run-1 defect); a malformed or out-of-set verdict reads as `unknown`, never coerced.
2. `merge-family! [ctx {:keys [signature reasoning candidates]}]` in reranker.clj returning
   `{:kind :same|:new|:unknown :family <id-or-nil> :reasoning <string> :usage …}`; model resolution like `rerank!`.
3. Neighbourhood retrieval `nearest-families [ctx {:keys [signature reasoning ranking]}]` (task_classifier or
   interface): `ontology/hybrid-search` over the tree-class ontology with `:query-text` = signature + reasoning,
   `:seed-uris` = the ranking's tree-class candidate URIs, `:scope :tree-class`, `:limit merge_candidate_count`,
   filtered to agent-authored concepts (families), returning `{:id :label :description}` where description is the
   family's assembled body summary (CV-A's rich birth description) — rank order preserved, no score used for any
   decision. Fail closed: an exception → the merge fn returns `{:kind :unknown :reason :neighbourhood-failed}`.
4. The default `:domain-merge-fn` becomes the real one: (a) empty neighbourhood → `{:kind :new}` without a call;
   (b) otherwise call `merge-family!`; `:same` with a family id that is in the shown set → land on it (the result
   carries `:merge-verdict`); `:same` naming an unshown family → treat as `unknown`; `:new` → mint; `:unknown` →
   `:domain-deferral {:axis :domain :reason :merge-unresolved}`. The classified event and the assign command carry
   `:merge-verdict {:kind :family :reasoning}` (optional keys; CV-A added the schema slot).
5. Config: `merge-candidate-count` default 5 read from `task_classifier` config alongside the other defaults.

## Tests (bundle; one RED run, then GREEN)

New `components/ontology/test/ai/obney/orc/ontology/rs7_domain_family_merge_test.clj` through `classify-task` with
stubbed `search-descriptions`, `get-consolidation-total*`, `:domain-families-fn` and a stubbed `:domain-merge-fn`
capturing its calls: same → landing on the named family (identity = that family, `:assigned-via
:land-on-domain-child`, `:merge-verdict` on the result); new → mint; unknown → deferral `:merge-unresolved`, no
mint; `:same` naming an unshown family → deferral; zero merge calls on a landing by label, on a covered match, and
on a covered-seed protection outcome; empty neighbourhood → mint with zero calls. Reranker: the instruction
byte-pin; `parse-merge-answer` accepts map and string, malformed → unknown. Store-backed (rs3 durable): a merge
landing records no concept and carries the verdict on the classified event. Guards: rs2, rs1, rs3, rs5, rs6_weed,
rs7_newborn_reach, el1b, walk-down, seeds, cc23.

Live QA (you): a small live run of `merge-family!` on three hand-built families (recipe scaling, marathon plan,
nutrition labels) with a sourdough-bakery task → `:same` recipe; a "compute nutrition labels" task → `:same`
nutrition; a "chess opening study plan" task → `:new`. Record the three answers verbatim (reasoning included).

## Disciplines (verbatim)

As CV-A's brief: root cause, bundle discipline, structured-data assertions only, fail-closed seams, never weaken a
test, report faithfully, never write the API key.

## Do NOT touch

`specs/*.allium`; the render section; `development/**`; harvest and the consolidator; CV-A's protection and
family-landing logic beyond replacing the merge seam's default.

## Report back

Files; RED/GREEN RESULT lines; the coverage line; the three live answers verbatim; what you could not verify; orphan
check.
