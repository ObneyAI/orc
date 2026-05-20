# US-Bench-Open — Push `feature/predict-rlm-comparison-bench` + open PR

## Parent

`docs/prd/upstream-pr-plan.md` — Step 3 final action.

## What to build

Push `feature/predict-rlm-comparison-bench` to `origin` and open a pull request against `ObneyAI/orc` `main` via `gh pr create`.

PR title: `predict-rlm comparison benchmark suite — 2 ports + runner + reports`

PR body structure:
- **Summary** — one-paragraph: 3 new Polylith bricks + comparison runner + 2 benchmark ports (image_analysis, document_redaction) + 2 clean reports + verbatim predict-rlm reference assets under MIT attribution.
- **Headline results** — bottom-line table from each clean report (matches predict-rlm at parity or higher recall, 2-4× faster, 1.3-2.8× cheaper on tokens).
- **What's in this PR** — bulleted file-by-file summary of what each commit adds.
- **Dependency** — explicit "Depends on `<URL-of-PR-Framework>`". PR-Framework MUST land first; this PR's runner reads `:rlm/researcher-iterations` events (U10) and the `:llm` nodes use `:output-schemas` (U11) — both shipped in PR-Framework.
- **How to run the benchmarks** — paste the relevant section of the bench README so reviewers can verify reproducibility without leaving the PR page.
- **Reference data licensing** — note predict-rlm is MIT-licensed; `LICENSE` file is copied verbatim under `references/predict-rlm/LICENSE`.
- **What's NOT in this PR** — deep-dive reports (stay in our local worktree), all intermediate/failed run EDNs (only 4 headline files committed), invoice_processing port (future work).
- **Test plan** — bulleted checklist: brick-level unit tests pass without API keys; live e2e run requires `OPENROUTER_API_KEY` + ~$0.15 budget; expected outcomes documented in clean reports.
- **Related** — link to `docs/prd/predict-rlm-benchmark-ports.md` (the original benchmark-port spec).

This slice is HITL because:
1. The user wants to review the PR body before it goes live on `ObneyAI/orc`.
2. The dependency note on PR-Framework's PR-URL requires PR-Framework to be open first.

## Acceptance criteria

- [ ] Branch `feature/predict-rlm-comparison-bench` pushed to `origin`.
- [ ] PR open against `ObneyAI/orc` `main` with the structured body above.
- [ ] PR title is descriptive and scoped.
- [ ] PR description explicitly references PR-Framework's URL/number as a dependency.
- [ ] PR description embeds the reproducibility section from the bench README so reviewers can run benchmarks without leaving the page.
- [ ] All 4 PR-Bench commits visible in the PR's commit history (NOT squash-mode push).
- [ ] CI checks (if any wired to the repo) pass.

## Blocked by

- US-Bench-RunnerReports (the branch must have all 4 commits + README + reports + headline EDNs before opening the PR)
- US-FW-Open (PR-Framework must be open first so we can reference it as a dependency)
