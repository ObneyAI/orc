# PRD: Upstream PR-Framework + PR-Bench — translating predict-rlm comparison work to ORC main

**Status:** Ready for execution
**Author:** daryl@obney.ai (with Claude grill session)
**Parent docs:**
- [`docs/prd/orc-rlm-upgrades.md`](orc-rlm-upgrades.md) (the technical upgrade specification — U1..U12)
- [`docs/prd/predict-rlm-benchmark-ports.md`](predict-rlm-benchmark-ports.md) (the benchmark port specification)
- [`development/bench/predict-rlm-comparison/reports/01_image_analysis.md`](../../development/bench/predict-rlm-comparison/reports/01_image_analysis.md) (proof point for U2/U3/U5/U6/U7/U8/U9/U12)
- [`development/bench/predict-rlm-comparison/reports/02_document_redaction.md`](../../development/bench/predict-rlm-comparison/reports/02_document_redaction.md) (proof point for U4/U11 + others)

## Problem Statement

The `feature/predict-rlm-benchmarks` worktree contains substantial framework correctness fixes, capability enablers, and a complete benchmark comparison suite — all uncommitted, sitting on top of main. Two reports demonstrate the value (image_analysis: ORC matches predict-rlm at 2.8× cheaper / 2.2× faster; document_redaction: 100% strict PII recall vs predict-rlm's 89%, 3.0× faster). However:

- **Framework bugs affect all ORC RLM consumers today, not just our comparison.** U5 (Phase-1 vision routing) silently sends 480K text-tokens instead of image_url blocks. U7 (Phase-2 :code reconciliation) causes 600s retry-storm timeouts on certain `:code` returns. U4 (extract-all-keys) breaks key declaration for any tree containing PR02-style `:code` nodes. These are real bugs leaking into every ORC RLM run that exercises the broken paths.
- **Capability enablers are stranded.** U2/U3/U8/U11 (model-authored inline `:code` + schema-driven `:llm` outputs) unlock the strongest expression of ORC's RLM design intent. Stranded in our branch they help nobody.
- **The benchmark suite proves the framework's claims** but is co-located with the framework work. External ORC consumers wanting to verify the comparison can't.
- **Main keeps shipping.** R-1 (recursive emit-tree!) and 4 doc commits landed since our last fetch. Continuing to develop unmerged compounds drift and conflict risk.

## Solution

Two upstream PRs against `ObneyAI/orc`, sequenced so the framework work lands first and the bench suite follows on top.

### PR-Framework — `feature/rlm-framework-upgrades`

11 RLM framework upgrades (U2..U12 minus U1 which already landed in R-1), bundled across 3 logical commits, with 4 new unit tests and `docs/RLM-GUIDE.md` updates. ~340 LOC across 6 framework files + ~80 LOC of tests + ~100 LOC of doc additions.

Commit groupings:
1. **`fix(orc-service): RLM correctness fixes (U4 + U5 + U6 + U7)`** — pure bug fixes; no behavior change for working consumers.
2. **`feat(orc-service): inline-fn :code + schema-driven structured output (U2 + U3 + U8 + U11)`** — new capability bundle; unlocks model-authored `:code` workflows.
3. **`feat(orc-service): RLM prompt updates + observability (U9 + U10 + U12)`** — model-side discoverability + observability surface.

### PR-Bench — `feature/predict-rlm-comparison-bench`

Depends on PR-Framework. 3 new Polylith bricks (`predict-rlm-pdf`, `predict-rlm-image-tools`, `predict-rlm-redaction-tools`), comparison runner with capture extensions, 2 task files (image_analysis, document_redaction), 2 clean external-facing reports (no deep-dives), a self-contained README with verified reproducibility steps, and verbatim predict-rlm reference assets under MIT attribution.

Commit groupings:
1. `feat(bench): predict-rlm-pdf brick`
2. `feat(bench): predict-rlm-image-tools brick + image_analysis benchmark`
3. `feat(bench): predict-rlm-redaction-tools brick + document_redaction benchmark`
4. `feat(bench): predict-rlm-comparison runner + reports + references`

## User Stories

1. As an ORC framework maintainer, I want the framework bugs in our worktree merged into main, so that all ORC RLM consumers benefit from the fixes immediately rather than depending on our unmerged branch.
2. As an ORC framework maintainer, I want each fix grouped into a logically-scoped commit, so that I can review and bisect at the level of intent (bug fix vs capability enabler vs prompt update).
3. As an ORC framework maintainer, I want each commit to be independently reviewable, so that PR review can proceed at the level of individual decisions rather than monolithic blobs.
4. As an ORC framework maintainer, I want the PR-Framework's tests to pass in CI without external dependencies (no OpenRouter key), so that the framework correctness can be validated without per-PR live LLM costs.
5. As an ORC framework maintainer, I want each new capability documented in `docs/RLM-GUIDE.md` with claims grounded in tests, so that the framework is self-documenting and accurate.
6. As an ORC framework maintainer, I want the bench suite kept separate from the framework PR, so that consumers who don't need predict-rlm comparison aren't forced to take the bench dependencies.
7. As an external ORC RLM consumer doing vision tasks, I want my image-typed blackboard values routed as multimodal content blocks (not inline text), so that vision tasks bill on image tiles instead of character-counted base64.
8. As an external ORC RLM consumer using emit-tree! with `:code` nodes, I want the `:code` node to accept either a qualified-symbol-string `:fn` reference OR an inline `(fn [{:keys [inputs]}] ...)` value, so that I can compose deterministic transforms inside trees without shipping every utility as a pre-built fn.
9. As an external ORC RLM consumer chaining LLM-structured-data → code-transform, I want `:llm` nodes to support `:output-schemas` declarations, so that the LLM's JSON output is parsed back into Clojure data before reaching downstream `:code` consumers.
10. As an external ORC RLM consumer running benchmarks that involve images, I want vector-of-data-URI inputs to be preview-truncated in prompts, so that my prompt budget isn't blown on inline base64.
11. As an external ORC RLM consumer running multi-page document tasks, I want the LMDB cache map-size to be configurable / larger by default, so that image-heavy tick state can be projected without `Environment mapsize reached` errors.
12. As an RLM-running model, I want the framework prompt to advertise inline-fn `:code`, `:output-schemas`, and the emit-tree-as-default policy, so that I know to reach for them when designing a workflow.
13. As an RLM-running model, I want `:available-code-nodes` on the repl-researcher node to surface per-benchmark tool documentation inside my prompt, so that I can reference pre-built deterministic transforms in my emitted trees.
14. As an external user evaluating ORC RLM against published benchmarks, I want a runnable comparison suite for the predict-rlm benchmarks, so that I can verify ORC's claims against an external reference without rebuilding the comparison from scratch.
15. As an external user running the predict-rlm comparison, I want the source PDFs, sample outputs, and predict-rlm LICENSE files committed under `references/`, so that my checkout is self-contained and doesn't require cloning predict-rlm separately.
16. As an external user running the predict-rlm comparison, I want a `README.md` under `development/bench/predict-rlm-comparison/` with prerequisites, exact REPL incantations, expected runtime and cost, and pointer to the clean reports, so that I can reproduce the comparison in under five minutes.
17. As an external user running the predict-rlm comparison, I want the headline result EDN + trace files committed alongside the reports, so that I can diff my fresh runs against the reference data.
18. As an external user reading the comparison reports, I want every predict-rlm number to be sourced verbatim from predict-rlm's committed sample/output/output.md files (with discrepancies between their README and their sample explicitly called out), so that I can trust the comparison is not making up numbers.
19. As a future ORC framework contributor, I want each framework upgrade documented with what it does, why it shipped this way originally, and the proof-point that validated the fix, so that future regressions can be diagnosed efficiently.
20. As a future ORC framework contributor, I want the unit tests covering each upgrade to be small, fast, and dependency-free, so that I can run them in seconds during development without per-test API costs.
21. As an upstream-PR reviewer, I want the PRs to be sized for human review (no >2000-LOC monoliths), so that I can read every line and reason about correctness.
22. As an upstream-PR reviewer, I want the PR description to surface the most important diff hunks (which file, which lines, what they do), so that I can navigate to them directly instead of scrolling through the entire diff.
23. As an upstream-PR reviewer, I want each capability enabler to have a concrete usage example in the doc updates, so that I can mentally simulate "how would I use this" before approving.
24. As the predict-rlm-benchmarks branch owner, I want the merge-to-main path resolved BEFORE opening the PRs (R-1 conflicts handled in our local environment, regressions validated against the merged state), so that the PRs are reviewing against the actual delta and not a stale baseline.
25. As the predict-rlm-benchmarks branch owner, I want the benchmarks to keep using terminal `:rlm {:debug? true}` emit-tree! mode (NOT R-1's recursive mode), so that the published numbers in the reports don't change due to mode switching that hasn't been characterized for these specific tasks.
26. As the predict-rlm-benchmarks branch owner, I want all numerical claims in the reports to be sourced verbatim from predict-rlm's `sample/output/output.md` (not from their `README.md`, which shows different numbers), so that the comparison is grounded in their committed authoritative reference.
27. As the predict-rlm-benchmarks branch owner, I want the `feature/predict-rlm-benchmarks` branch preserved with all uncommitted work as a WIP commit BEFORE merging main, so that no work is lost during the conflict-resolution step.
28. As the predict-rlm-benchmarks branch owner, I want a final re-comb step after PR-Framework and PR-Bench branches are built, so that any drift between docs and live behavior is caught and fixed before PR submission.
29. As an external ORC consumer who later wants to add a new benchmark to the comparison suite, I want the PR-Bench structure (per-benchmark brick + task definition + report, all under `predict-rlm-*` prefix) to be a clear template I can copy, so that adding `invoice_processing` or other future ports is mechanical.
30. As an upstream-PR reviewer, I want the dependency between PR-Framework and PR-Bench made explicit in the PR-Bench description (with a link to PR-Framework), so that I review PR-Framework first and don't waste cycles on PR-Bench until its dependency lands.

## Implementation Decisions

### PR ordering and dependency

PR-Framework lands first. PR-Bench branches off `feature/rlm-framework-upgrades`. Once PR-Framework merges to main, PR-Bench rebases onto main. PR-Bench's PR description references PR-Framework's PR number so reviewers know the order.

### PR-Framework upgrades — bundle and commit grouping

Three logical commits, grouped by intent rather than file:

| Commit | Upgrades | Rationale |
|---|---|---|
| 1. correctness fixes | U4, U5, U6, U7 | pure bug fixes; no behavior change for working consumers; lowest review-risk; bisectable |
| 2. capability bundle | U2, U3, U8, U11 | new capability enablers; introduces inline-fn `:code` + schema-driven structured output as a coherent feature set |
| 3. prompt + observability | U9, U10, U12 | model-side discoverability (prompt updates) + iteration-event emission + preview-vector fix |

U1 (SCI safe-clojure-core fix) is already shipped via R-1 on main — DROPPED from our PR list.

Multi-tree iteration (originally tracked as PR-Multi-Tree future work) is also shipped via R-1 as `:rlm {:recursive? true}` opt-in — DROPPED.

### PR-Framework — unit test additions

Four new unit tests added (one per untested upgrade in our worktree):

| U# | Test name (descriptive) | Asserts |
|---|---|---|
| U4 | `extract-all-keys-handles-fn-in-any-position` | synthetic tree with `:fn` first vs last; both yield identical key sets |
| U7 | `execute-code-reconciles-non-map-results` | non-map return with single `:write` → auto-wrap; multi-write mismatch → clear failure error |
| U8 | `sanitize-tree-replaces-fn-values-with-placeholder` | post-walk substitution; inline fn → `"<inline-fn>"` string; other values untouched |
| U12 | `preview-vector-truncates-large-string-elements` | vector with 10KB string element → preview is small; primitive elements untouched |

U3 (tree executor :code discriminator) and U6 (Phase-2 schema preservation) NOT unit-tested in isolation:
- U3: behavior covered by U2's existing tests + the tree-compile path's existing structure.
- U6: requires real Integrant fixture (slow, brittle); live benchmarks validate end-to-end; rlm-mode-test exercises indirectly.

### PR-Framework — RLM-GUIDE.md updates

Additions to `docs/RLM-GUIDE.md`:
- "Sandbox primitives" section: inline-fn `:code` syntax with example
- "Basic usage" or new sub-section: `:output-schemas` on `:llm` — when to declare, how it interacts with downstream `:code` consumers
- "Sandbox primitives" sub-note: `:field-type :image` blackboard schemas for vision inputs
- New optional section "Pre-built code-node catalog": `:available-code-nodes` on the repl-researcher node
- Cross-reference between terminal and recursive emit-tree! modes (R-1 already touched recursive; we add cross-refs to our new capabilities)

**Every claim in the doc updates is verified against a test or a live run before submission.** If a claim isn't verifiable, it's reworded or removed.

### PR-Framework — what's NOT included

- New event types beyond `:rlm/researcher-iterations`
- New commands
- New schema fields beyond `:output-schemas` (existing :rlm map already accepts arbitrary keys; `:output-schemas` is a new key on the `:llm` DSL node)
- New tests for U3, U6 (justified above)
- Any change to the existing 5-benchmark generalization suite (unchanged)
- Deep-dive reports (those stay in the predict-rlm-benchmarks worktree, NOT shipped upstream)

### PR-Bench — bricks and task definitions

| Brick | Exports | Dependencies |
|---|---|---|
| `predict-rlm-pdf` | `page-count`, `render-pages-as-data-uris`, `extract-pages-as-text` | Apache PDFBox 3.0.3 |
| `predict-rlm-image-tools` | `image->data-uri`, optional `count-letter-frequencies` / `format-letter-counts-answer` | none beyond Clojure |
| `predict-rlm-redaction-tools` | `apply-redactions` (shape-detect inputs by value type), `available-code-nodes` catalog | none beyond Clojure |

Each brick has its own `deps.edn` declaring its opt-in dependencies. External consumers pull only what their chosen benchmarks need.

Task definitions under `development/bench/predict_rlm_comparison/tasks/`:
- `image_analysis.clj` — single-image vision QA with structural-verification clause
- `document_redaction.clj` — multi-page PII identification + deterministic apply with adversarial second pass

Both use terminal `:rlm {:debug? true}` mode. Both declare `:model "openai/gpt-5.4"` + `:sub-model "openai/gpt-5.1-chat"` for apples-to-apples comparison.

### PR-Bench — comparison runner

`development/bench/predict_rlm_comparison/runner.clj`:
- Public API mirrors `development/bench/runner.clj` (`start!`, `run!`, `stop!`, `generate-summary!`)
- Preserves `:iterations` (read from `:rlm/researcher-iterations` events), `:by-node`, `:node-trace` (from `:sheet/node-execution-completed` events)
- Per-run mulog `.trace.edn` publisher
- Single-task-lock atom
- Supports `:input-schemas`, `:input-loader`, `:output-schemas`, `:sub-model`, `:available-code-nodes` on task definitions
- LMDB map-size bumped to 512MB for image-heavy tasks

### PR-Bench — reference assets

660KB of verbatim predict-rlm content committed under `development/bench/predict-rlm-comparison/references/predict-rlm/`:
- `LICENSE` (MIT, verbatim)
- `<task>/signature.py.txt` (verbatim source for traceability)
- `<task>/sample/input/*` (PDFs, screenshots used as benchmark inputs)
- `<task>/sample/output/output.md` (predict-rlm's published reference outputs — authoritative source for all comparison numbers in our reports)
- `document_redaction/README.md` (predict-rlm's README, for the README-vs-sample discrepancy note in the deep-dive)

### PR-Bench — result EDN policy

Commit only the 2 headline runs (4 files total):
- `image-analysis_2026-05-20_150618.edn` + `.trace.edn`
- `document-redaction_2026-05-20_165215.edn` + `.trace.edn`

Add `.gitignore` entry under `results/` to exclude future timestamped runs.

### PR-Bench — reports

Two clean external-facing reports committed:
- `reports/01_image_analysis.md`
- `reports/02_document_redaction.md`

Deep-dives (`*_deep_dive.md`) stay in the `feature/predict-rlm-benchmarks` worktree, NOT shipped upstream. They document the development journey (10 framework bugs surfaced and fixed in sequence) which is useful for our records but not external-facing.

### PR-Bench — README

Single `development/bench/predict-rlm-comparison/README.md`. Sections:
1. What this is (one paragraph)
2. What's compared (table of 2 benchmarks + EDN/report paths)
3. Headline results (compact summary)
4. Prerequisites (`OPENROUTER_API_KEY`, expected ~$0.15 total)
5. How to run (exact REPL incantations)
6. Expected runtime + cost per benchmark
7. Where outputs go
8. Comparing your run to the committed reference (expected variance)
9. Methodology + fidelity caveats (links to clean reports)
10. References (link to predict-rlm + MIT)

**Every command-line in the README is verified against fresh-checkout reproducibility before submission.**

### Sync-merge sequence (Step 1)

1. Commit current uncommitted work to `feature/predict-rlm-benchmarks` as a WIP preservation commit. No semantic message; just `wip: preserve pre-merge state`.
2. Fetch `origin/main`. Inspect the 5 new commits (R-1 + 4 doc commits).
3. `git merge origin/main` into `feature/predict-rlm-benchmarks`. Resolve conflicts (likely in `executor.clj` where R-1's recursive emit-tree! work and our changes overlap).
4. Run regression: `clj -M:dev:test` for `rlm-mode-test` (45 tests / 138 assertions GREEN) and `rlm-dsl-test` (27 tests / 109 assertions GREEN), separately to avoid the PR-Pre02 dual-load issue.
5. Live regression: re-run `image_analysis` and `document_redaction` against the merged state. Confirm 22-of-24 letter match and ~92 redactions / 100% strict recall still hold.

If any regression surfaces, fix and re-validate BEFORE building PR branches.

### Build-PR-Framework sequence (Step 2)

1. Branch `feature/rlm-framework-upgrades` off latest `origin/main`.
2. Apply framework changes as 3 logical commits (intent-grouped, not file-grouped).
3. Add 4 new unit tests as part of the appropriate commit.
4. Update `docs/RLM-GUIDE.md` with verified claims.
5. Run `clj -M:poly test brick:orc-service` GREEN.
6. Push branch.
7. Open PR via `gh pr create` with structured body (problem, solution, files, tests, proof point, related PRDs).

### Build-PR-Bench sequence (Step 3)

1. Branch `feature/predict-rlm-comparison-bench` off `feature/rlm-framework-upgrades` (or off main if PR-Framework already merged).
2. Apply bench changes as 4 logical commits (brick-grouped).
3. Add bench `README.md` with verified reproducibility steps.
4. Commit 4 headline EDN/trace files; add `.gitignore` for `results/*.edn` except the headlines.
5. Verify `deps.edn` changes are minimal + scoped.
6. Push branch.
7. Open PR via `gh pr create` with structured body referencing PR-Framework.

### Final re-comb sequence (Step 4)

1. Fresh checkout of each PR branch in a temporary worktree.
2. Live re-run both benchmarks.
3. Verify clean report numbers + README cost/runtime estimates match observed behavior.
4. If drift, fix and push.

## Testing Decisions

### What makes a good test in this codebase

- Tests verify external behavior, not implementation details. Tests survive refactors.
- Pure-data fixtures preferred over LLM round-trips. Unit tests should be fast (~seconds) and dependency-free (no OpenRouter key).
- Integration tests that exercise the full Phase-1 → Phase-2 → completion path are valuable but slower and live-API-dependent; they live under `predict-rlm-comparison/results/` as headline-run EDN artifacts, not under `test/`.
- Prior art for unit tests: `rlm_dsl_test.clj` (28 deftests of pure-data DSL round-trips and the existing PR-* test additions like `code-node-accepts-inline-fn`, `llm-primitive-propagates-image-field-type-to-module`, `extract-key-schemas-collects-from-llm-nodes`).

### Modules under unit-test

Framework (PR-Framework):
- `extract-all-keys` (rlm-tree-executor) — U4 case: position-independent `:fn`
- `execute-code` (executor) — U7 case: output reconciliation against declared writes
- Inline-fn sanitization (todo-processors) — U8 case: post-walk substitution
- `preview-vector` (rlm-sandbox) — U12 case: large-element truncation
- Existing tests for U2, U5, U11 remain unchanged

Brick deep modules (PR-Bench):
- `predict-rlm-pdf` — 5 existing deftests (fixtures-loadable, page-count, render-data-URI shape, decoded PNG validity, DPI affects size, text extraction)
- `predict-rlm-image-tools` — 4 existing deftests for `image->data-uri` MIME detection
- `predict-rlm-redaction-tools` — 6 existing deftests for `apply-redactions` (tracer, multi-page, missing-target graceful, page-summaries, empty-targets, idempotence)
- Comparison-runner single-task-lock — 4 existing deftests

### Modules NOT unit-tested (validated end-to-end via benchmark runs)

- Tree executor `:code` string/fn discriminator (U3) — covered by U2's tests + downstream live runs
- Phase-2 child-sheet schema preservation (U6) — requires real Integrant fixture; live benchmarks cover
- Per-benchmark image-data-URI round-trip — too dependency-heavy for unit; the benchmarks live-test
- Mulog trace capture — validated by reading committed `.trace.edn` artifacts
- Framework prompt content (U9) — string-presence assertions are brittle; live benchmark behavior is the real test
- `:rlm/researcher-iterations` event emission (U10) — validated by the comparison runner reading the events post-run

## Out of Scope

- **Multi-tree iteration via `:rlm {:recursive? true}`** for our benchmarks. R-1 introduced it as opt-in; converting our benchmarks to recursive would invalidate the published numbers and isn't justified for the headline comparison. Future work.
- **PR10 (invoice_processing)** and **PR11/12 (its report + aggregate index)**. Continuing the plan; tracked locally under `docs/issues/predict-rlm/PR10-*.md` and onward.
- **Deep-dive reports** (`*_deep_dive.md`). Stay in `feature/predict-rlm-benchmarks` worktree, NOT shipped upstream. They document the 10-bug development journey which is internal knowledge.
- **All ~30 failed/intermediate result EDN files** from the development journey. Headline-only commits upstream.
- **Pre-existing bug tickets** PR-Pre01 (12 failures in `repl_researcher_test.clj`) and PR-Pre02 (dual-test-load Integrant fixture conflict). Independent of this work; can be picked up anytime.
- **A new dscloj function-calling mode**. We chose schema-driven JSON-text parsing (which dscloj already supports via `complex-spec?`) over forcing function-calling. Function-calling at the provider API level remains disabled per the existing `:use-function-calling? false` comment.
- **LLM-as-judge for the comparison reports**. Manual qualitative review remains the judge.
- **`predict-rlm-shared` brick.** Originally planned but not needed — each per-benchmark brick is small enough to be self-contained.

## Further Notes

### Decisions adopted from the grill session

1. **Timing:** PR now, not after PR10/11/12. Framework bugs affect all RLM consumers today.
2. **Shape:** Two PRs, framework first, bench depends on framework.
3. **Test coverage:** Add 4 new unit tests (U4, U7, U8, U12) in addition to existing tests for U2/U5/U11.
4. **Merge:** Merge `origin/main` into `feature/predict-rlm-benchmarks` BEFORE building PR branches. Resolve R-1 conflicts in our environment, not on GitHub.
5. **U9 + U10 inclusion:** Both go in PR-Framework. U9 is essential for capability discoverability; U10 is a PR-Bench dependency (the runner reads `:rlm/researcher-iterations` events).
6. **Commit structure:** Logical groupings (3 commits PR-Framework, 4 commits PR-Bench) for reviewability + bisectability + revert isolation.
7. **Terminal mode:** Benchmarks keep using `:rlm {:debug? true}` terminal emit-tree!. Not converting to R-1's recursive mode for this iteration.
8. **Doc updates:** `docs/RLM-GUIDE.md` gets new sections for inline-fn `:code`, `:output-schemas`, `:field-type :image`, `:available-code-nodes`. Every claim verified against tests/live behavior.
9. **Reference assets:** Commit all 660KB of predict-rlm verbatim content under `references/`. Self-contained reproducibility.
10. **Result EDNs:** Commit ONLY the 2 headline runs (4 files: 2 EDN + 2 trace). Gitignore the rest.
11. **Bench README:** Single file, ~150 lines, user-friendly, accurate, every command verified.
12. **Branch names:** `feature/rlm-framework-upgrades` + `feature/predict-rlm-comparison-bench`.

### Validation against the predict-rlm published reference

All comparison numbers in the clean reports come from predict-rlm's committed `sample/output/output.md` files:

**image_analysis** (`/tmp/predict-rlm-read/predict-rlm/examples/image_analysis/sample/output/output.md` — copied verbatim to our `references/`):
- 4 main-LM calls (gpt-5.4) + 5 sub-LM calls (gpt-5.1) = 9 calls
- 17,048 main-in + 1,821 main-out + 5,723 sub-in + 1,955 sub-out = **26,547 tokens**
- $0.05 main + $0.03 sub = **$0.08 cost**
- Duration: **~1 minute**
- 1,343 letters reported in their letter-count table

**document_redaction** (`/tmp/predict-rlm-read/predict-rlm/examples/document_redaction/sample/output/output.md` — copied verbatim):
- 4 main-LM + 30 sub-LM = 34 calls
- 28,255 main-in + 3,144 main-out + 27,944 sub-in + 6,504 sub-out = **65,847 tokens**
- $0.08 main + $0.10 sub = **$0.18 cost**
- Duration: **1m 27s (87s)**
- **89 redactions** in the per-page summary

The predict-rlm README's document_redaction numbers (96 redactions, ~2 min, $0.24, 87,775 tokens) differ from their committed sample output. Our reports use the sample output as authoritative and call out this discrepancy explicitly so reviewers understand the source.

### Risks and mitigations

| Risk | Mitigation |
|---|---|
| R-1 merge conflicts disrupt our work | Merge in our local environment; resolve carefully; run full regression before building PR branches |
| Live benchmark runs cost real money | Estimated ~$0.15 per full re-run cycle; budget for ~$1-2 for the final re-comb |
| Reviewer asks for more unit tests | We have 4 new + 5 existing + 19 existing brick tests; if reviewer wants more, we can add a targeted few |
| `:output-schemas` doesn't work on some LLM provider | We've validated on gpt-5.4 + gpt-5.1-chat + gemini-3-flash-preview; dscloj's `complex-spec?` is provider-agnostic; risk is low |
| Doc claim drift from live behavior | Step 4 (final re-comb) addresses this; re-run benchmarks and verify every numerical claim |

### Implementation plan reference

The original `/Users/darylroberts/.claude/plans/in-another-branch-we-glistening-kettle.md` plan captured the Q1-Q13 grilling-session decision points for the broader benchmark port. This PRD is the synthesized form for the UPSTREAM PR-Framework + PR-Bench execution specifically, distilled from the grill session conducted 2026-05-20 covering questions 1-12.
