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
