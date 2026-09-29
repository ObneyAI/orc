# CV-D handoff — every landing on an existing domain family is judged (decision C3')

Work in `/Users/darylroberts/Desktop/Code/orc-convergence-arc` (branch feature/domain-child-convergence). No commits;
never edit `specs/*.allium`. Read first: decision C3' at the end of
`docs/build-timeline/grill-sessions/domain-child-convergence-decisions.md`; the spec rules `MergeIntoDomainFamily`,
`MintDomainFamily`, `JudgeReachedDomainFamily` and the invariants `DomainFamilyMergeIsJudged` and
`DomainFamilyIsALeafOnTheDomainAxis` in `specs/ontology.allium`; the CV-A and CV-C ledgers in
`docs/issues/domain-child-convergence/README.md`; and `components/ontology/src/ai/obney/orc/ontology/core/task_classifier.clj`
(`maybe-assign-domain-child`, `run-domain-axis-for-match`, `assign-domain-child`, `mint-domain-family-via-merge`,
`default-domain-merge-fn`, `nearest-families`).

## Why

Live evidence: with the current code, tasks from different groups land in one family through three paths that never
reach the merge judge: (1) the tenant-wide landing by a label a family already carries, (2) the per-parent landing on a
shape's existing child label, (3) a match or walk-down that reaches a family (landing, verdict not consulted). The
judge (about 1 false merge in 23 in the RS-P3 probe) runs only on would-be mints.

## The change

1. **Proposed family.** Each of the three paths now only PROPOSES a family: the family carrying the task's canonical
   label (tenant-wide), a shape's child whose label matches, or the family a match or walk-down reached.
2. **One judged question for every proposal and every would-be mint.** Route all of them through the merge seam
   (`mint-domain-family-via-merge` or a generalisation of it) with the proposed family in the query. `nearest-families`
   must always include the proposed family among the candidates shown to the judge (fetch its concept and description
   if the hybrid search did not return it; the count bound still applies, the proposed family is never the one dropped).
   Verdicts: `:same` naming a SHOWN family lands on that family (it may differ from the proposed one); `:new` mints;
   `:unknown` or a malformed answer defers `:merge-unresolved`.
3. **Reached family.** A match or walk-down that reaches a family is judged as a match on the family's own parent shape
   (its skos:broader): the shape for a mint is that parent, the task's own reranker label and coverage for the family
   candidate carry through. Never mint under a family.
4. **Identity collision.** A `:new` verdict whose derived identity (`stable-domain-child-identity` of shape and label)
   is an existing family's identity cannot mint: defer with a new reason `:label-taken` (add it to the domain-deferral
   reason enum in `components/ontology/src/ai/obney/orc/ontology/interface/schemas.clj`, and wherever orc-service mirrors
   that enum).
5. **Unchanged:** covered-seed protection runs first and is judge-free; a covered match with no proposed family is a
   plain match with no judge call; the bundle never lands on a family.

## Tests (bundle: one RED run of the new tests, then GREEN over new tests and guards)

Pure classifier tests (seams on ctx; no store-less shortcuts): label proposal + `:same` lands; label proposal + `:new`
mints a family with a different identity; label proposal + `:new` with the same identity defers `:label-taken`; child
label proposal is judged; reached family is judged as its parent shape (`:same` lands, `:new` mints under the parent,
never under the family); the proposed family is always among the judge's candidates even when `nearest-families` omits
it; `:unknown` defers; zero judge calls on a covered match and on a covered-seed protection. Update existing tests that
assumed an unjudged landing by declaring the judge's verdict explicitly (never delete an assertion; say which changed
and why). Store-backed (rs3 durable, real in-memory store): a label landing records the merge verdict on the classified
event and creates no concept. Guards: rs1, rs2, rs3 birth, rs5, rs6-weed, rs7 newborn reach, rs7 domain-family merge,
el1b, walk-down, seeds, cc23, reranker, and orc-service rs3 durable and rs4 render.

Focused runs only: `clojure -J-Djava.awt.headless=true -J-Xmx3g -M:dev:test <script.clj>` requiring the namespaces and
exiting on the result; never `-M:poly test` or the repository-wide suite. One JVM at a time; never kill a JVM you did not
start.

## Live proof (you run it)

Before running, delete `.orc-colbert-indexes/ontology-descriptions`. Then run the 42-task confusable check:
`clojure -J-Djava.awt.headless=true -J-Xmx3g -M:dev:test /private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad/confusable-check.clj`
(it writes `development/bench/ood-stress-results/rs7-confusable-check`; delete that directory first). Report its CHECK
lines verbatim: statuses, assigned-via, families, families shared across groups, and each group's assignments. The
target: no family shared across groups, and in-domain tasks (legal-issue-detection, contract-comparison) matching the
legal seeds rather than a family.

## Report back

Files changed; RED and GREEN RESULT lines; the live CHECK lines verbatim; any judgment calls; what you could not verify;
orphan-JVM check. Never write or print an API key.
