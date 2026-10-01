# CV-E handoff — a domain family is never a shape-ranking candidate (decision C5')

Work in `/Users/darylroberts/Desktop/Code/orc-convergence-arc` (branch feature/domain-child-convergence). No commits;
never edit `specs/*.allium`. Read first: decisions C3' and C5' at the end of
`docs/build-timeline/grill-sessions/domain-child-convergence-decisions.md`; the invariant
`DomainFamilyIsALeafOnTheDomainAxis` and the rules `MergeIntoDomainFamily` / `MintDomainFamily` in `specs/ontology.allium`;
the CV-D ledger in `docs/issues/domain-child-convergence/README.md`; in `components/ontology/src/ai/obney/orc/ontology/`:
`core/task_classifier.clj` (`classify-task`, `walk-down-from`, `pick-best-child`, `get-tree-class-children` callers,
`maybe-assign-domain-child`, `family-concept?`, `default-domain-family-parent-fn`, `nearest-families`) and
`interface.clj` (`search-descriptions`, `tree-class-representatives`, `apply-rerank`).

## Why

Pass 1 of the post-fix arm: only 2 of 24 in-domain tasks reached their curated seed. For the risk-analysis task all
five ranked candidates were other groups' domain families; the seed was not in the ranking. Families carry specific
birth descriptions and outrank generic seeds, and each new family adds to the crowding.

## The change

1. **The shape ranking holds no family.** Classification's retrieval (`classify-task`'s `search-descriptions` call over
   the two tree axes, with the rerank) must exclude domain families BEFORE the reranker's candidate set is taken, so the
   reranker still sees its full count of non-family candidates. Add an optional candidate predicate to
   `search-descriptions` (applied with the granularity filter, before `tree-class-representatives` and the take), keep
   the unfiltered behaviour byte-identical for every other caller, and pass a family predicate from `classify-task`.
   A candidate is a family when its tree-class concept is agent-authored with a label that is not its own id (the same
   discriminator `family-concept?` / `default-domain-family-parent-fn` already use). Read it through a seam on ctx
   (default: the real store read), batch or cache it per classification so one classification does at most one read per
   distinct candidate, and fail closed: a failed lookup excludes the candidate (never lets an unverified class compete).
   Over-fetch enough that excluding families still leaves the reranker its usual count where the index has them.
2. **Walk-down never descends into a family.** `walk-down-from` / `pick-best-child` consider only non-family children.
3. **No reach path remains.** With 1 and 2, `maybe-assign-domain-child`'s reached-family branch cannot fire from
   retrieval; keep it only if a remaining caller can still hand it a family, otherwise remove it with its tests' intent
   moved to the new tests below (say which).
4. Families are still found by the merge step's own `nearest-families` search and proposed by label or child label
   (CV-D); leave that path unchanged.

## Tests (bundle: one RED run of the new tests, then GREEN over new tests and guards)

- `search-descriptions` with the predicate: families never reach the reranker; the reranker still gets its full count
  when the index holds enough non-family candidates; the no-predicate path is unchanged (existing reranker tests).
- `classify-task`: a family that ColBERT ranks first never becomes top-1 or a ranked candidate; the in-domain seed that
  sat behind five families reaches the reranker; a failed family lookup excludes that candidate.
- Walk-down: a shape whose only child is a family returns the shape itself; a family child is never picked.
- Update tests that relied on a family being reached by retrieval or walk-down (rs5, rs7 newborn reach, rs8): the new
  expectation is that the family is not reached; say which assertions changed and why; never delete an assertion
  without saying so.
- Guards: rs1, rs2, rs3 birth, rs5, rs6-weed, rs7 newborn reach, rs7 domain-family merge, rs8, el1b, walk-down,
  seeds, cc23, reranker, rr3, classifier, and orc-service rs3 durable, rs4 render, researcher-resume-state.

Focused runs only: `clojure -J-Djava.awt.headless=true -J-Xmx3g -M:dev:test <script.clj>`; never `-M:poly test`. One JVM
at a time; never kill a JVM you did not start.

## Live proof

Do NOT delete directories. Move them aside with `mv` if needed. Before a live run, move
`.orc-colbert-indexes/ontology-descriptions` aside (`mv … .stale-<time>`) and move any existing
`development/bench/ood-stress-results/rs7-confusable-check` aside. Then run
`clojure -J-Djava.awt.headless=true -J-Xmx3g -M:dev:test /private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/confusable-check.clj`
and report its CHECK lines verbatim. Then add the four in-domain groups and their neighbours by running a copy of that
script with the group set `#{"legal-issue-detection" "contract-comparison" "document-analysis" "risk-analysis"
"compliance-checklist" "clause-drafting" "meeting-actions" "research-synthesis"}` and variants up to 3, writing to
`development/bench/ood-stress-results/rs7-in-domain-check`, and report its CHECK lines verbatim, plus for each in-domain
task whether it matched its expected seed (the manifest's `:expected-seed-id`).

## Report back

Files changed; RED and GREEN RESULT lines; both checks' CHECK lines verbatim and the in-domain seed-match list;
judgment calls; what you could not verify; orphan-JVM check. Never write or print an API key.
