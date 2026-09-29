# Domain-child convergence — issues (bundled per the user's direction)

PRD `docs/prd/domain-child-convergence.md`; decisions C0–C8; ADR 0007; plan `PLAN.md` here. Implementers are Sonnet
5 subagents; the orchestrator inspects every bundle with `/inspect-orc` before its ledger and commit. Bundles are
written whole and tested together (one red run before implementing the pure parts, then green, then live QA), not
one thin slice at a time.

| Issue | Type | Content | Blocked by |
|---|---|---|---|
| CV-0 characterisation + RS-7 tooling | landed | Slice 0 tests; corpus generator, resumable sweep harness, metrics, comparison, e2e subset | — |
| CV-P3 merge-step probe | HITL, orchestrator | arm A discrete merge question over the hybrid neighbourhood with rich descriptions; arm B label list | CV-0 |
| CV-A classifier core (bundle) | AFK | family lookup tenant-wide + landing; covered-seed protection; family is a leaf (three reach routes); newborn hidden at total 0; rich birth description + embedding at birth; reranker label slot + task-family text | CV-0 |
| CV-B render + hint (bundle) | AFK | family body under the child line when it has substance; child line on every reach route; closure hint (preventive + reactive) | CV-0 (independent of CV-A's files except the payload keys) |
| CV-C merge judge (bundle) | AFK | merge workflow + seam at the two mint arms; unknown defers | CV-A, CV-P3 |
| CV-7 corpus + baseline sweep | orchestrator | generate, review, freeze the 400-task corpus; baseline arm on the pinned pre-fix worktree | CV-0 |
| CV-8 integration + post-fix sweep + truth pass | orchestrator | post-fix arm, comparison, weed, obligation audit, docs, PR | all |

Spec obligations (from `allium plan` after the tend): `CoveredSeedWins` (success + 3 failure), `LandOnDomainFamily`
(success + 2), `LandOnReachedDomainFamily` (success), `MergeIntoDomainFamily` (success + 5), `MintDomainFamily`
(success + 5 + 2 entity-creation), plus `DomainVerdict`/`DomainCoverage` (unchanged, covered). CV-A owns
CoveredSeedWins, LandOnDomainFamily, LandOnReachedDomainFamily and MintDomainFamily's non-merge obligations; CV-C
owns MergeIntoDomainFamily and the merge-dependent failure obligations of MintDomainFamily.

## Ledger

- **CV-P3 — DONE.** Verdict in `development/bench/ood-stress-results/rs-p3-family-merge-probe/FINDINGS.md`: both
  arms converge 8 of 8 groups onto one family; rich arm 1 false merge in 23 (single candidate shown, shared output
  kind only); labels arm 0 in the corrected run but 3 in run 1 (live variance). CV-C proceeds on the rich arm with
  the instruction stating the converse (different subject matter OR different output kind = new family) and with
  covered-seed protection ahead of the judge. Run 1 carried a parser defect (map vs JSON string), reported there.
- **CV-C brief** written: `docs/build-timeline/handoff-plan/CV-C-merge-judge-HANDOFF.md`; dispatch after CV-A lands
  and is inspected.

## Gate scope (user direction, from the agent-console AGENTS.md rule)

Bundle inspections run focused tests that load the working tree: the bundle's new tests and its named guard
namespaces, with the exit status checked, plus live QA where the bundle has a live path. Allium work stays targeted
to the behavior the bundle changed. Neither subagents nor bundle inspections run the repository-wide suite. After
CV-8's final edit, the integration runs once: a whole `/weed`, `clojure -M:poly test :all-bricks :dev` (per-project
poly runs are commit-sensitive and do not count), then `allium check` and `allium analyse` over every spec,
unfiltered. Any later edit means one more full run.

## CV-B verification ledger (orchestrator `/inspect-orc`)

- **Scope:** family body beneath the child line, the family recorded as an injection candidate, a preventive
  pitfalls bullet in checkpointed campaigns, and a reactive closure-rejection hint keyed on the sandbox's ex-data
  keyword with a fallback on the sandbox's own exported message constant.
- **RED, re-run by the orchestrator at HEAD** in a throwaway worktree: the family-substance render test fails; the
  no-substance and consolidated guards pass, as they should; the hint suite cannot load without the new constant.
  The implementer reported, faithfully, that it had skipped its own pre-implementation RED run.
- **Defect found in inspection and fixed:** a later task that reaches the newborn family by match carries the
  family in its top candidates. The injection record then listed the family twice, and its substance never
  rendered, because the render's lookup found the plain candidate first. The newborn branch now removes the family
  from the numbered candidates before appending its substance candidate. The new test
  `reached-family-renders-once-and-is-recorded-once` failed without the fix, on both symptoms, and passes with it.
- **Coverage added in inspection:** the hint still fires after a checkpoint resume, from the durable record's
  175-character error excerpt of the enhanced error. The durable record is built from an allowlist, so the ex-data
  never reaches an event.
- **GREEN, re-run by the orchestrator:** 216 tests over 15 namespaces (the new suites, every named guard, and the
  implementer's breadth set) with one failure, the orchestrator's own excerpt test using the wrong record shape;
  corrected and the hint suite re-run green (8 tests, 14 assertions).
- **Allium, scoped:** `allium check` on the orc-service spec shows 0 errors, 2 warnings, 43 info; `analyse` shows 0
  findings. No spec file changed. Obligation audit: 0 obligations, because the render text and the repair hint
  carry none (classified as an intentional gap: presentation, not domain behavior). Weed over the seam: the
  orc-service spec's closure-source statement is unchanged by this bundle; no divergence.
- **Commit content verified in isolation:** the staged bundle alone, on a clean worktree at the parent commit, runs 216 tests and 1,076 assertions green.
- **Deferred by rule:** the full gate runs once, in CV-8. No live QA for this bundle; the end-to-end subset will show the hint firing.

## CV-A verification ledger (orchestrator `/inspect-orc`)

- **Scope:** tenant-wide family lookup and landing by label, covered-seed protection, a family as a leaf on the
  domain axis, newborns matchable but not surfaced at zero, a merge seam at every would-be mint (default `new`),
  the family born with a rich description and an embedding, the reranker told the task-family granularity and
  shown the tenant's label list.
- **RED:** the implementer ran the unmodified guard suite after implementing, not before, and reported it. Exactly
  the intended flips failed; each later test went red then green on its own.
- **Defects found in inspection and fixed:**
  1. **The second family under a shape skipped the judge.** A new label under a shape that already had families
     took the old unjudged sibling mint: no merge judge, no birth description, no embedding. The spec has had no
     sibling rule since ADR 0007. That arm now goes through the merge-judged mint. New test: the judge is called
     for that case and its `same` verdict lands on the named family.
  2. **Store-less fail-open.** The family and family-parent lookups answered "no families" when the context had no
     event store, so a missing store would have minted instead of deferring. Removed; the pure suites now declare
     their world through the seams. Real-store coverage of both lookups exists (the cross-parent landing and the
     walk-down-into-family tests).
  3. **Unembedded births were silent.** A failed embedding skipped the embedding event and minted anyway. The
     birth now fails loudly, matching the existing embed command. New test: nothing lands.
  4. **The birth text named the parent shape by id,** in the concept description and in a guard claim. The guard
     claim also landed in the body's avoid-when list, which the domain penalty scores against. The guard claim
     is removed (two birth claims: capability and representative use). The description now carries the family
     label, its purpose, the parent shape's own summary and the birth task, with no ids.
  5. **A `same` verdict naming a non-family** landed with no parent and bypassed the parent seam. It now defers
     `merge-unresolved`. New test.
  6. **Render gap across CV-A and CV-B.** A family reached through the index has no parent among the candidates,
     so the newborn render showed no parent entry. The render now builds the parent's entry from its body with
     the top match's score and reasoning, first, and records it. Test extended.
- **Spec tended (orchestrator):** `CoveredSeedWins` no longer requires the absence of a family, and
  `LandOnDomainFamily` now yields to protection, matching decision C4's order. The outcome enum comment now says
  the sibling outcome is history only. `allium check` on the ontology spec: 0 errors, 8 warnings, 45 info;
  `analyse`: 0 findings.
- **Obligation audit (scoped to this bundle): 15 obligations, 15 covered, 0 uncovered.**
  - CoveredSeedWins success: `covered-leaf-neighbour-at-threshold-wins-over-a-partial-top-1`. Failure 1, no
    neighbour: `no-neighbour-is-byte-identical`, `covered-neighbour-with-families-does-not-protect`. Failure 2,
    top-1 covered: `covered-top-1-is-never-overridden-by-a-covered-neighbour` (added in inspection).
  - LandOnDomainFamily success: `label-existing-under-another-parent-lands-on-that-family` and the store-backed
    `cross-parent-landing-records-no-concept-and-carries-the-familys-own-parent`. Failure 1, no label:
    `blank-label-defers-instead-of-minting-no-children`. Failure 2, no family:
    `no-children-partial-verdict-mints-domain-child`. Failure 3, protection applies:
    `protection-runs-before-family-landing`.
  - LandOnReachedDomainFamily success: the two index-match tests, the walk-down tests, and the store-backed
    `walk-down-into-a-family-lands-on-it-no-walk-down-provenance`.
  - MintDomainFamily success: `no-children-partial-verdict-mints-domain-child`,
    `partial-verdict-no-children-mints-domain-child-durably`. Failures 1–4: blank label, covered neighbour, existing
    family, covered or unknown coverage (the tests named above plus `no-children-unknown-verdict-defers`). Entity
    creation 1–2: `mint-domain-child-births-the-child-concept-and-parent-edge` and
    `checkpointed-commit-publishes-a-domain-child-mint-atomically`.
  - Owned by CV-C: `MintDomainFamily` failure 5 (verdict not `new`) and all six `MergeIntoDomainFamily`
    obligations.
- **Weed over the seam:** `:domain-children-considered` is populated only on the per-parent arms, not on the
  tenant-wide landing or protection paths. It is schema-optional; classified as an intentional gap, since the
  tenant-wide label list is the evidence those paths use.
- **GREEN, re-run by the orchestrator:** 42 namespaces, 369 tests, 2,241 assertions, then the rs2 suite again
  after the last test (26 tests, 136 assertions).
- **Not yet run:** live QA of the rewritten reranker instruction. The RS-7 smoke on the post-fix tree exercises it
  before the post-fix arm. The full integrated gate runs once, in CV-8.

## RS7-PS verification ledger (persistent Postgres store for the sweeps)

- **What landed:** a dedicated local Postgres container, `orc-rs7-postgres`, on port 5435, bound to localhost with trust
  auth and a named volume. Grain's Postgres v3 event store is added at the pinned Grain revision. The runner takes an
  optional store connection, tenant and cache directory; its cache moved out of `/tmp`. Resuming on a store that
  already holds the tenant's events projects them and rebuilds the index once instead of re-seeding.
  `run.edn` is written before the first task, so a crash leaves the tenant recoverable.
- **Implementer's live proof:** a fresh session wrote 539 events, and a resumed session on the same tenant showed
  identical counts for every seed-producing event type, plus one index rebuild. A three-task smoke was killed after
  task two and resumed to finish all three on the recovered tenant.
- **Defect found in inspection and fixed:** the launcher notes said one database per arm and pass. Pass 2 measures
  stability against the tree pass 1 grew, so a fresh pass-2 database would have seeded a new corpus and reported
  stability against nothing. The notes now say one database per arm. `run-pass!` refuses a pass 2 unless pass-1
  records exist and the tenant matches pass 1's. New test `pass-2-refuses-a-tenant-other-than-pass-1s`.
- **Known limit, reported:** a task killed mid-flight may have appended part of its effects before the crash. On
  resume it re-runs against its own partial effects. Crashes are rare, and any occurrence shows in the record
  timestamps; the comparison reports it rather than hiding it.
- **GREEN, re-run by the orchestrator:** 21 tests and 109 assertions across the harness and model-registry suites.

## CV-C verification ledger (orchestrator `/inspect-orc`)

- **Scope:** the judged merge step. A would-be family birth retrieves the nearest existing families by rank, at
  most five, with no similarity cutoff. One judge call returns same, new or unknown, with the reasoning written
  first. Same lands on a family that was shown to the judge, new mints, and unknown defers with no mint. An empty
  neighbourhood mints with no call.
- **RED:** the implementer could not run the new tests against the old code and substituted a read-only proof
  that none of the new symbols existed at HEAD. Reported by the implementer; accepted, since those tests cannot
  compile against HEAD.
- **Live QA (implementer, verbatim in its report):** three live judge calls answered as expected. A sourdough
  task was judged the same as the recipe family, a nutrition-label task the same as the nutrition family, and a
  chess study plan new. The chess reasoning named the shared output kind and still ruled "new" on subject matter,
  which is the converse the probe findings asked the instruction to state.
- **Defect found in inspection and fixed: every scoped embedding read returned nothing.** Embedding events carry
  only UUID tags (`[:concept id]`), but the scoped read selected events by a `[:scope …]` tag and the per-concept
  read by a `[:uri …]` tag. Measured on a real store before the fix: the unscoped read found 1 of 1 embeddings,
  the scoped read 0 of 1, and the per-concept lookup failed. The embeddings read model now keeps each event's scope
  and filters in memory (read model version 2), and the per-concept lookup reads by key. Consequence had it
  shipped: the neighbourhood would always have been empty, the judge never asked, and every paraphrase a new
  family. It went unseen because every scoped-search test stubbed the search, and no production caller outside
  the ontology interface used a scoped read before this arc.
- **Tests added in inspection:** `nearest-families-finds-real-born-families-by-meaning` (two families born through
  the real command; both found, the recipe family first for a recipe task, the ordinary shape class excluded;
  failed before the fix). The durable second-family test now declares the judge's verdict and asserts that the
  real neighbourhood showed the judge the first family.
- **Implementer findings carried forward (follow-ups, not blocking):** the hybrid search's graph leg and its
  label enrichment read only the static seed ontology, so for families only the embedding leg ranks. The
  neighbourhood re-reads labels and descriptions from the live concept. The durable proof covers `same` but not
  `unknown` (the pure tests cover `unknown`).
- **Obligation audit (scoped to this bundle): 7 obligations, 7 covered, 0 uncovered.** MergeIntoDomainFamily
  success: `same-verdict-lands-on-the-named-family-and-carries-merge-verdict` and the store-backed
  `merge-landing-records-no-concept-and-carries-the-verdict`. Failure 1, no label:
  `blank-label-defers-instead-of-minting-no-children`. Failure 2, protection applies:
  `zero-merge-calls-on-a-covered-seed-protection-outcome`. Failure 3, a family carries the label:
  `zero-merge-calls-on-a-landing-by-label`. Failure 4, covered: `zero-merge-calls-on-a-covered-match`. Failure 5,
  verdict not same: `new-verdict-mints-a-family-and-carries-merge-verdict`, `unknown-verdict-defers-and-mints-nothing`.
  MintDomainFamily failure 5, verdict not new: `unknown-verdict-defers-and-mints-nothing`,
  `same-verdict-naming-an-unshown-family-defers`.
- **Allium, scoped:** no spec change in this bundle; the ontology spec's last check stands (0 errors, 0 analyse
  findings). Weed over the seam: the retrieval scope bug was a code bug with no spec statement; no divergence.
- **GREEN, re-run by the orchestrator:** 47 namespaces, 424 tests, 2,423 assertions, with the one durable test
  failing on the stale judge assumption; that test was corrected and its suite re-run green (9 tests, 99
  assertions).

## Retrieval fix found by the post-fix live smoke (orchestrator)

- **Symptom:** in a six-task live smoke of the post-fix tree, the first legal-issue task (in-domain) still minted a
  family under briefing generation. The second legal task then landed on that family, so convergence worked,
  on the wrong parent. The pre-fix smoke and the pre-fix convergence sweep showed the same in-domain miss.
- **Root cause, measured live:** the classifier's description search, with a rerank, fetched ColBERT's top 10 over
  the whole index and only then filtered to the two tree axes. Behavior descriptions held most of the ten slots, so
  4 entries (2 shapes) reached the reranker. The legal seed was 7th and never shown to it. The no-rerank path already
  over-fetched 3x for a filter; the rerank path did not.
- **Fix:** a filtered rerank over-fetches 3x and hands the reranker its usual count of allowed candidates. The
  unfiltered path is unchanged. Test `search-with-rerank-and-a-filter-gives-the-reranker-a-full-allowed-set` failed
  first with exactly the live symptom (4 candidates) and passes after the fix, with the reranker suites green (28 tests).
- **Live re-check:** the reranker now receives 10 tree candidates including the legal seed. It rates the seed 0.95
  `covered` and ranks it first; briefing generation drops to 0.70.
- **Effect on the comparison:** the pre-fix baseline arm runs without this fix, so the before-and-after includes it.
  The findings will attribute in-domain changes to this fix, not to the convergence decisions.

## Bundle fix found by the second post-fix live smoke (orchestrator)

- **Symptom:** with the retrieval fix in, the six-task smoke matched both legal tasks to the legal seed, minted a
  recipe family and landed the second recipe task on it. But the second marathon task was bundled into the recipe
  family: the reranker scored that family in the bundle band for "adjusting quantities across a distribution".
- **Root cause:** the shape-axis bundle considered every tree-class candidate, families included. Decision C5 made a
  reached family a landing on the match and walk-down paths; the bundle path was missed.
- **Fix:** the bundle considers shape classes only. A candidate the family-parent lookup identifies as a family is
  dropped, and so is one whose lookup fails, so a bundle never lands on an unverified class. Spec tended:
  `DomainFamilyIsALeafOnTheDomainAxis` now names the bundle. Check: 0 errors, 0 analyse findings.
- **Tests:** `bundle-never-lands-on-a-domain-family` (family only: mint; family and shape: bundle onto the shape;
  failed lookup: no bundle) failed on all three before the fix. Pure suites declare their world explicitly
  (el1b fixture, the cc23 bundle test). The rr3 richness test's ColBERT stub now honours the requested count, like
  the real index; it had relied on the stub returning more candidates than a real search hands the reranker.
- **GREEN:** 47 namespaces, 426 tests, with the 14 failures traced to those three setups; the two affected suites
  re-run green (18 tests, 97 assertions) after the corrections.

## One row per tree class for the reranker (orchestrator)

- **Symptom:** after the filter fix, a third smoke run still minted a family for the second in-domain legal task.
  ColBERT ranked the legal seed 9th and 10th on the tree axes, and new families in the index pushed it past the
  reranker's ten rows.
- **Root cause:** every tree class is indexed on both tree axes with the same description, so the reranker's ten rows
  held five classes, and ColBERT's raw scores for these shapes differ by less than one point in about 447. Which
  five classes the reranker saw was close to noise, and growing traffic adds families that compete for the five.
- **Fix:** the rerank path gives the reranker one row per tree class (the tree-class row preferred), ten classes in
  the same prompt size, and applies each returned judgement to both axis rows of that class. The output is still
  bounded by the caller's `k`, so the classifier's input shape is unchanged. Test
  `search-with-rerank-shows-the-reranker-distinct-tree-classes` failed first (five classes, each twice).
- **GREEN:** 47 namespaces, 427 tests, 2,447 assertions, all passing.
- **Live smoke, all six right:** marathon and recipe each mint one family and the second task lands on it; both
  legal tasks match the legal seed. Pre-fix, the same six produced two deferrals, a mint, a wrong-parent family
  for an in-domain task, and a second in-domain task captured by it.
