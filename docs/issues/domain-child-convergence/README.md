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
