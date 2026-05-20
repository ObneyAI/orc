# US-FW-Open — Push `feature/rlm-framework-upgrades` + open PR

## Parent

`docs/prd/upstream-pr-plan.md` — Step 2 final action.

## What to build

Push `feature/rlm-framework-upgrades` to `origin` and open a pull request against `ObneyAI/orc` `main` via `gh pr create`.

PR title: `RLM framework upgrades — correctness fixes + schema-driven structured output + prompt/observability`

PR body structure:
- **Summary** — one-paragraph: 9 RLM framework upgrades grouped into 3 commits; correctness fixes + capability enablers + prompt/observability; ~300 LOC framework + ~60 LOC tests + ~80 LOC doc updates. (U1 and U2/U3 already shipped via main's R-1 + R-2 — dropped from this PR.)
- **What's in this PR** — bulleted list of the 9 upgrades (U4, U5, U6, U7, U8, U9-delta, U10, U11, U12) by U-number with a one-line description of each.
- **Relationship to R-1 / R-2** — explicit note that R-1 shipped U1 + multi-tree iteration and R-2 shipped U2/U3 + drill-down primitives. Our PR is the remaining delta.
- **Commits** — list of the 3 commit messages so reviewers can navigate per-commit.
- **Files touched** — file-by-file summary of which upgrades land in which file.
- **Tests added** — 4 new unit tests with their names + what they assert.
- **Empirical proof points** — link to the local image_analysis dream-scenario report + document_redaction report under `feature/predict-rlm-benchmarks` worktree (note these reports will be upstreamed in the follow-up PR-Bench).
- **Related** — link to `docs/prd/orc-rlm-upgrades.md` (the technical upgrade spec) and the PR-Bench dependency note.
- **Test plan** — bulleted checklist of how reviewers can verify (run `clj -M:poly test brick:orc-service`; optional live run via the predict-rlm-comparison runner once PR-Bench lands).
- **Notes** — call out the relationship to R-1 (which already shipped U1 + multi-tree iteration); note our work is opt-in additions that don't change behavior for non-RLM consumers.

This slice is HITL because the user wants to review the PR body before it goes public on `ObneyAI/orc`.

## Acceptance criteria

- [ ] Branch `feature/rlm-framework-upgrades` pushed to `origin`.
- [ ] PR open against `ObneyAI/orc` `main` with the structured body above.
- [ ] PR title is descriptive and scoped (no vague "fix stuff").
- [ ] PR description references `docs/prd/orc-rlm-upgrades.md` and notes PR-Bench dependency.
- [ ] All 3 commits visible in the PR's commit history (NOT squash-mode push).
- [ ] CI checks (if any wired to the repo) pass — at minimum `clj -M:poly test brick:orc-service`.

## Blocked by

- US-FW-C (the branch must have all 3 commits + tests + docs before opening the PR)
