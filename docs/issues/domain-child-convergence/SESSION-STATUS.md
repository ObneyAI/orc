# Convergence arc — session status (paused 2026-09-22 at the user's request; resume from here)

## Where everything is
- Arc worktree `/Users/darylroberts/Desktop/Code/orc-convergence-arc`, branch `feature/domain-child-convergence`,
  base commit `3fbba3a0` (grill C0–C8, ADR 0007, spec tend, Slice 0 tests, RS-7 tooling, 40 briefs, PRD, issues,
  briefs for CV-A and CV-B). Pinned baseline worktree `/Users/darylroberts/Desktop/Code/orc-convergence-baseline`
  at `51caa798` (the PR #38 head) for the PRE-FIX sweeps.
- PR #38 (`feature/r-inject-specialisation` → main): first CI run green; a second run failed only on the hosted-runner
  recovery flake `det-e2e-205` ("Last scan: []" within 30 s, never locally); the failed job was re-run and was still
  in progress at pause. Merge when green (`gh pr merge 38 --merge`), then fast-forward the main checkout.
- Decisions: `docs/build-timeline/grill-sessions/domain-child-convergence-decisions.md` (C0–C8). Spec:
  `specs/ontology.allium` tended (rules CoveredSeedWins / LandOnDomainFamily / LandOnReachedDomainFamily /
  MergeIntoDomainFamily / MintDomainFamily; seven invariants; config merge_candidate_count 5,
  family_label_list_bound 50) — 0 errors, 45 info, 8 warnings, 0 analyse findings. Glossary terms added: Domain
  family, Merge verdict, Covered-seed protection, Newborn family. ADR 0007.
- Plan: `docs/issues/domain-child-convergence/PLAN.md` (also `~/.claude/plans/i-have-a-codex-jiggly-quiche.md`).

## What was in flight when paused (all stopped; no JVMs left)
1. Sonnet bundle CV-A (classifier core) — brief `docs/build-timeline/handoff-plan/CV-A-classifier-core-HANDOFF.md`;
   stopped during its read-first phase (no edits observed). Re-dispatch with the same prompt.
2. Sonnet bundle CV-B (render + hint) — brief `CV-B-render-hint-HANDOFF.md`; same, re-dispatch.
   Both may edit `orc-service todo_processors.clj` in disjoint regions with exact-string Edit calls only.
3. Corpus generation — `$S/run-corpus-gen.sh` (S = the session scratchpad
   `/private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc/0d49c256-e217-4038-9059-e748ddab1ba5/scratchpad`;
   if the scratchpad is gone, the launcher form is in `development/src/traffic_corpus_gen.clj`'s comment block:
   `runner/start!` → sleep 45 s → `traffic-corpus-gen/generate! briefs.edn corpus-dir {:ctx …}` →
   `flag-near-dups-live!`). It was killed at ~16 of 240 tasks (6 style cards × 40 briefs; the plan said ~10 per
   brief — 6 is what the generator supports today; extend `style-cards` if 400 is wanted). `generate!` restarts from
   scratch. After generation: review flags in `manifest.edn`, spot-check per `REVIEW.md` (to write), then
   `traffic-corpus-gen/freeze!` and commit the corpus.
4. RS-P3 merge-step probe — `development/src/rs_p3_family_merge_probe.clj` (`run!`), launcher `$S/run-rs-p3.sh`
   (`runner/register-models!` only, no runner start); killed before any result. It replays the 24 convergence
   paraphrases against a growing family set in two arms (rich descriptions vs labels). Its verdict gates CV-C.
5. RS-7 baseline arm — NOT started. Run from the baseline worktree once the corpus is frozen:
   `rs7-traffic-sweep/run-pass!` pass 1 and 2 with `:reindex-policy {:every-k 25}`, then `run-e2e!` on 15 groups
   (see PLAN.md Part A); results under `development/bench/ood-stress-results/rs7-traffic/<stamp>-pre-fix/`.

## Pick-up sequence
1. `git status` in the arc worktree; discard or inspect any stray agent edits.
2. Check PR #38 CI; merge when green.
3. Re-dispatch CV-A and CV-B (parallel), re-run corpus generation, re-run RS-P3 (light JVMs may overlap; never two
   brick gates at once).
4. Inspect CV-A/CV-B with `/inspect-orc`; write ledgers in the issue files; gates (ontology per project, orc-service);
   commit. Then CV-C (merge judge) from RS-P3's verdict, same loop.
5. Baseline sweep on the pinned worktree (can run while CV-* build); post-fix sweep on the final tree; comparison;
   findings; integration (weed, obligation audit, docs); PR.
