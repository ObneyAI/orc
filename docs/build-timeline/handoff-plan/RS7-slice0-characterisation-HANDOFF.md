# Slice 0 handoff — characterise today's newborn-reach and grandchild behaviour (tests only)

Plan: `docs/issues/domain-child-convergence/PLAN.md` — read "Verified code seams" and Part C "Slice 0". Work in
`/Users/darylroberts/Desktop/Code/orc-convergence-arc` (branch `feature/domain-child-convergence`). Runner as in
RS-2's brief (`clojure -J-Djava.awt.headless=true -J-Xmx1600m -M:dev:test -e "…"`), one JVM at a time, 0 orphans.
Do not commit. Never edit `specs/*.allium`. **Tests only — no production code changes.** Another agent is editing
`development/` and `development/bench/runner.clj` on this worktree; do not touch those.

## Goal

Pin, deterministically and on structured data, exactly what the runtime does today in the four situations the
convergence arc will change, so the fix slices flip NAMED tests instead of guesses:

1. rs2 (`components/ontology/test/ai/obney/orc/ontology/rs2_domain_child_classifier_test.clj`, reuse
   `tree-class-candidate`, the `{:domain-children-fn …}` seam, `with-redefs [ontology/search-descriptions …
   tc/get-consolidation-total* …]`):
   - `newborn-as-top-1-match-with-partial-mints-a-grandchild-today`: top-1 is a childless domain child (its id as the
     candidate target, `:partial`, label given) → assert `:assigned-via :mint-domain-child` and `:parent-tree-id` =
     that child (the grandchild path).
   - `newborn-as-top-1-match-with-covered-is-a-plain-match-with-no-domain-label`: same but `:covered` → `:match`,
     no `:domain-label`, `:parent-tree-id` nil.
2. rs5 (`rs5_domain_child_chain_test.clj`, mint a real child with `mint-command`/`claim-command`; stub
   `search-descriptions` so the PARENT is top-1 at fitness 0.8 (below `specificity-threshold` 0.9, above match
   threshold 0.7) and stub `reranker/rerank!` for `pick-best-child` to prefer the child):
   `walk-down-into-a-newborn-returns-walk-down-provenance-not-a-landing` → `:assigned-via :walk-down`, assigned id =
   the child, no `:domain-label`, no `:domain-verdict`.
3. rs4 (`components/orc-service/test/ai/obney/orc/orc_service/rs4_waterfall_render_test.clj`, mirror
   `wedge-omits-domain-on-a-plain-match`): a stubbed `classify-task` returning `:match` whose assigned id is a domain
   child → the payload has no `:domain` key (so no child line renders today).
4. New `components/ontology/test/ai/obney/orc/ontology/rs7_newborn_reach_test.clj` pinning the three reach routes:
   (a) graph landing (parent top-1, existing child label → `:land-on-domain-child` with the child NOT among the
   candidates); (b) walk-down (as in 2); (c) index match (child itself top-1 → `:match` on the child; and with
   `:partial` → the grandchild, as in 1). Each asserts on `:assigned-via`, `:assigned-tree-id`, `:parent-tree-id`.
5. Note (do not change) the tests Slice 5 will flip: el1b `gate-passes-curated-seed-tree-class-at-total-zero`, rs5
   `cycle2-retrieval-gate-band-on-the-domain-child` (its total-0 assertion).

All new tests must be GREEN on today's code (they characterise it). A test that is red is a finding: report it with
the RESULT line and your reading; do not change production code to make it pass.

## Disciplines (verbatim)

Never assume; reproduce → minimise → root cause. Assert only on structured data the runtime emits (provenance,
identities, labels, verdict maps) — never regex or phrase matching over prose. Report faithfully, including what
you could not verify. Do not weaken any existing test.

## Report back

Files changed; the RESULT line of the combined run (`rs2-domain-child-classifier-test`,
`rs5-domain-child-chain-test`, `rs4-waterfall-render-test`, `rs7-newborn-reach-test`, `el1b-convergence-capture-test`)
verbatim; for each new test the exact result map it observed (pr-str) so the orchestrator can read today's
behaviour from data; anything red and why; the orphan-JVM check.
