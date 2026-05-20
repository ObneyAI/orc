# US-Sync — Merge `origin/main` into our branch + regressions GREEN

## Parent

`docs/prd/upstream-pr-plan.md` — Step 1 of the execution sequence.

## ⚠️ Critical: merge expectations after main re-sweep (2026-05-20)

`origin/main` has advanced 9 commits since our last sync (`37cf07d` HEAD). Multiple of those commits CONVERGE with our local work — careful conflict resolution required:

### Converged commits (resolve carefully — DO NOT lose our content)

| Commit on main | What it ships | Overlap with our work |
|---|---|---|
| `ba71447` R-1 | Recursive `emit-tree!` + SCI safe-list fix | Already known. Covers our U1 (SCI safe-list). Our worktree has the same fix idempotently — merge cleanly resolves. |
| `6747759` R-2 | Drill-down primitives + **`:code` tree-node port** | **Converges with our U2 (DSL `:code` inline-fn) AND U3 (compile-tree-node `:code` discriminator).** Both implementations are semantically identical to ours; ONLY comment wording differs. |
| `4265090` | Fix iteration-history truncation | Orthogonal to our work. Likely clean merge. |
| `aa04483` | Planning + live-verify scripts | User's planning files now on main; some PRDs we have locally are now upstream (`docs/prd/predict-rlm-benchmark-ports.md`, etc.) |
| `c7ac6b3 → 0b257e5` (4 commits) | `docs/RLM-GUIDE.md` (363 lines) + composition pattern + result-shape fix | Our U9 changes ADD to this guide, not replace. |

### Doc-file conflicts to resolve

| File | Difference | Resolution strategy |
|---|---|---|
| `docs/issues/predict-rlm/PR07-image-analysis-report.md` | 9-line local diff (supersedes-by-PR06b note) | Keep our version (the note has tracking value). |
| `docs/issues/predict-rlm/README.md` | 160-line local diff (our work-in-progress status updates) | Resolve carefully — main's is the canonical reference; our status content belongs in the worktree-local issues README, not main's. |
| `docs/prd/predict-rlm-benchmark-ports.md` | Both have versions | Diff at merge time; consolidate the canonical content from main with our local iterations. |
| Our local `docs/prd/orc-rlm-upgrades.md` + `docs/prd/upstream-pr-plan.md` | NOT on main yet | These stay local in our worktree, NOT shipped upstream (they're our planning docs). |
| Our local `docs/issues/upstream-pr/*.md` | NOT on main yet | Stay local — these track our internal execution. |

### Source-code conflicts to expect

| File | Conflict zone | Resolution |
|---|---|---|
| `rlm_dsl.clj` `:code` branch | Both versions exist — different comments, same logic | Pick R-2's comment style (canonical-upstream). Verify our test `code-node-accepts-inline-fn` still passes; rebase against R-2's `rlm_dsl_test` `:code` tests if they exist. |
| `rlm_tree_executor.clj` `compile-tree-node :code` branch | Both versions exist — same logic | Pick R-2's version; verify our other rlm_tree_executor changes (extract-all-keys :fn-position, extract-key-schemas) merge cleanly. |
| `rlm_sandbox.clj` | Main has R-2 drill-down imports + new safe-core comment; we have `preview-vector` recursive + `schema-field-type` + image routing | Different sections; likely clean merge with minor adjacent-line conflicts. |
| `executor.clj` | Main has R-1 recursive + R-2 prompt updates; we have build-module schema-preservation + execute-code reconciliation + our U9 prompt updates | More involved merge. Verify all R-1/R-2 functionality preserved. |

## What to build

Sync the `feature/predict-rlm-benchmarks` worktree with the latest `origin/main` so subsequent PR branches are built against an accurate, conflict-free baseline.

End-to-end behavior:
1. Current uncommitted work on `feature/predict-rlm-benchmarks` is preserved (a WIP commit captures it on the branch — not the final commit, just a preservation point).
2. `origin/main` is fetched. Inspect the 9 new commits.
3. `git merge origin/main` is performed against `feature/predict-rlm-benchmarks`. Conflicts (in the files listed above) are resolved in our local environment — NOT punted to GitHub.
4. Regression check passes:
   - `rlm-mode-test` (45 tests / 138 assertions historically — verify count post-merge)
   - `rlm-dsl-test` (27 tests / 109 assertions historically — verify; R-2 added 3 more `:code` tests)
   - `recursive_rlm_test` (NEW from R-1/R-2, 508 lines — must still pass)
   - `recursive_rlm_drill_down_test` (NEW from R-2, 308 lines — must still pass)
5. Live regression: re-run image_analysis (target: 22-of-24 letter match against predict-rlm's published counts) and document_redaction (target: ~92 redactions / 100% strict recall per ground-truth inventory). Both end-to-end successful.

If any regression surfaces, fix and re-validate BEFORE building PR branches. The downstream slices (US-FW-A onward) cannot start until the baseline is clean.

## Acceptance criteria

- [ ] Pre-merge state preserved as a WIP commit on `feature/predict-rlm-benchmarks` (no work lost during merge).
- [ ] `git merge origin/main` succeeds with all conflicts resolved cleanly (NO unresolved markers in any file).
- [ ] All 4 unit-test namespaces GREEN running individually: `rlm-mode-test`, `rlm-dsl-test`, `recursive_rlm_test`, `recursive_rlm_drill_down_test`.
- [ ] image_analysis live run produces `:status :success`, emit-tree! is used, 22-of-24 letter exact match against predict-rlm.
- [ ] document_redaction live run produces `:status :success`, tree includes `apply-redactions` referenced twice, total-redactions in the 90-95 range, strict-PII recall 100%.
- [ ] R-1 recursive `emit-tree!` and R-2 drill-down primitives confirmed functional (not broken by our work).
- [ ] Our U4 (`extract-all-keys`) preserved through merge — the new `extract-key-schemas` helper sits alongside it cleanly.
- [ ] Our U5 (Phase-1 image routing) preserved — `schema-field-type` helper and `execute-llm-primitive` field-type propagation intact.
- [ ] Our U6 (Phase-2 schema preservation via `:blackboard-schemas`) preserved.
- [ ] Our U7 (Phase-2 `:code` output reconciliation) preserved.
- [ ] Our U8 (event sanitization) preserved.
- [ ] Our U10 (`:rlm/researcher-iterations` event) preserved.
- [ ] Our U11 (`extract-key-schemas`) preserved.
- [ ] Our U12 (`preview-vector` recursive) preserved.

## Blocked by

None — can start immediately.
