# predict-rlm Benchmark Ports — Issue Tracker

Local issues tracking the work described in `docs/prd/predict-rlm-benchmark-ports.md`.

---

## ⚠️ Where we left off (read this first if resuming)

**Last completed:** PR08 + PR09 ✅ — **DOCUMENT REDACTION PORT COMPLETE.** With apples-to-apples models (`gpt-5.4` + `gpt-5.1-chat`) the model designed an 8-node behavior tree using `apply-redactions` (path A — pre-built `:code` node) TWICE plus model-authored inline `:code` fns for flatten/combine transforms, with `:output-schemas` declared on all `:llm` nodes for structured-output parsing. **Against a manual ground-truth inventory built page-by-page from the source: ORC has 100% recall (84/84 strict PII items); predict-rlm has 89% (75/84) — 9 strict PII items missed including bank transit number, corporate Business Number (tax ID), 3 employment date ranges, and 4 same-text-different-page recurrences.** Both systems have ~4 over-redactions beyond strict criteria (ORC: asset tags / AD username; predict-rlm: Canada / city-state portions). **Headline: ORC 92 redactions / 28.9s / 52,120 tokens / 100% strict recall; predict-rlm 89 redactions / 87s (1m 27s, per their sample/output/output.md) / 65,847 tokens / $0.18 / 89% strict recall.** ORC: 3.0× faster, 1.26× cheaper on tokens, 100% strict recall vs predict-rlm's 89%. Required ONE new framework upgrade: **U11 — schema-driven structured output for `:llm` writes** (now in `docs/prd/orc-rlm-upgrades.md`). Reports in `development/bench/predict-rlm-comparison/reports/02_document_redaction.md` + `02_document_redaction_deep_dive.md`.

**Previously completed:** PR-SCI-Inline-Fn ✅ — image_analysis dream scenario realized with `[:sequence :llm :llm :llm :code :final]` and model's OWN inline `:code` fn for letter counting. 22 of 24 letters match predict-rlm exactly. 2.8× cheaper, 2.2× faster. See `01_image_analysis.md` + `01_image_analysis_deep_dive.md`.

**What we landed during this iteration** (all on top of PR01-PR07):
- ✅ **PR-Pre03** — Phase-1 sub-LLM image routing fix. Tokens dropped from 480K to ~4K. Vision now actually works.
- ✅ **PR-Prompt** — RLM prompt now defaults to `emit-tree!` for non-trivial work, warns against chained sequential `(llm ...)`, recommends `:code` for deterministic transforms.
- ✅ **PR06b** — image_analysis re-run + correctness comparison vs ground truth (1,754 letters source-of-truth from `contract_v2.txt` page 1).
- ✅ Five additional framework bugs found and fixed during PR06b: Phase-2 child-sheet schema preservation; `:code` string-fn double-wrap; Phase-2 `:code` output filtering to declared `:writes`; `extract-all-keys` `:fn`-position independence; Phase-2 `:code` non-map auto-wrap.

**🎯 Headline result for image_analysis (DREAM SCENARIO — apples-to-apples with predict-rlm's exact models, full behavior tree with model's OWN inline `:code` fn):**
- ORC dream tree: **1,345 letters / 9,560 tokens / 26.9s** with `[:sequence :llm :llm :llm :code :final]`
- predict-rlm reference (gpt-5.4 + gpt-5.1): 1,343 letters / 26,547 tokens / ~60s
- Ground truth: 1,754 letters total — both systems capture 77% of source letters
- ORC absolute-error sum: **409**; predict-rlm: **411** → essentially identical accuracy
- ORC tokens / wall clock: **2.8× cheaper, 2.2× faster** at par accuracy
- 22 of 24 letters match predict-rlm's counts EXACTLY
- The model designed multi-pass OCR + reconciliation + a deterministic Clojure counter, all from a goal-only instruction

**Secondary result (apples-to-apples single-pass, direct execution):** 1,345 letters / 12,786 tokens / 22.4s. Same accuracy as dream tree but more tokens (no tree-level efficiency).

**Earlier result (gemini same-model baseline):** ORC 1,250 letters / 5,787 tokens / 11.75s. Less accurate than gpt-5.4 (absolute error 510 vs 409) — gap is model-family, not methodology.

**Resume sequence:** continue to **PR08** (document_redaction execution). The framework is solid now. The port-cleanup principle is locked. All known blockers resolved.

Two pre-existing-bug tickets remain non-blocking: **PR-Pre01** (12 failures in `repl_researcher_test.clj`) and **PR-Pre02** (dual-test-load isolation). Both independent of predict-rlm work.

---

## Issues

### Comparison-quality enhancements

| ID | Title | Type | Blocked by | Status |
|---|---|---|---|---|
| [PR-Dual-Model](PR-Dual-Model-runner-support.md) | Runner support for `:model` + `:sub-model` (gpt-5.4 main + gpt-5.1-chat sub for true apples-to-apples) | AFK | none | ✅ done — apples-to-apples result: 22 of 24 letters match predict-rlm exactly, 2.1× cheaper, 2.7× faster |
| [PR-SCI-Inline-Fn](PR-SCI-Inline-Fn-support.md) | Make SCI-generated inline `(fn ...)` values executable as `:code` node fns | AFK | none | ✅ done — **DREAM REALIZED**. gpt-5.4 designed and executed `[:sequence :llm :llm :llm :code :final]` with its own inline letter-counter fn. Run: `image-analysis_2026-05-20_150618.edn` (1,345 letters, 9,560 tokens, 26.9s). Root cause: RLM safe-clojure-core wrongly included `let if fn cond when do def`. Fressian sanitization needed for stored events. |
| [PR-Multi-Tree](PR-Multi-Tree-iteration.md) | Multi-tree iteration in RLM researcher (model emits follow-up trees after Phase-2) | AFK (future) | none | ⏳ captured for future, NOT this work |

### Critical fixes surfaced during PR06/PR07 (resolved this iteration)

| ID | Title | Type | Blocked by | Status |
|---|---|---|---|---|
| [PR-Pre03](PR-Pre03-phase1-vision-routing.md) | Fix Phase-1 sub-LLM image routing (`:field-type :image` not propagated) | AFK | none | ✅ done |
| [PR-Prompt](PR-Prompt-emit-tree-default-policy.md) | RLM prompt policy update — `emit-tree!` as default | AFK | none | ✅ done |
| [PR06b](PR06b-image-analysis-rerun.md) | Re-run image_analysis after fixes + update PR07 report | AFK exec + HITL report | PR-Pre03, PR-Prompt | ✅ done |

### Original plan (PR01–PR12)

| ID | Title | Type | Blocked by | Status |
|---|---|---|---|---|
| [PR01](PR01-worktree-and-baseline.md) | Worktree from main + Phase 0 baseline re-run | AFK | none | ✅ done |
| [PR02](PR02-code-node-dsl-extension.md) | `:code` node support in `emit-tree!` DSL + tests | AFK | PR01 | ✅ done |
| [PR03](PR03-available-code-nodes-plumbing.md) | `:available-code-nodes` plumbing through `:rlm` config | AFK | PR01 | ✅ done |
| [PR04](PR04-predict-rlm-pdf-component.md) | `predict-rlm-pdf` component (PDFBox) + tests | AFK | PR01 | ✅ done |
| [PR05](PR05-comparison-runner-with-capture.md) | New comparison runner with capture extensions | AFK | PR01 | ✅ done |
| [PR06](PR06-image-analysis-execution.md) | `predict-rlm-image-tools` + image_analysis execution | AFK | PR03, PR05 | ✅ done (quality issues to be fixed by PR06b) |
| [PR07](PR07-image-analysis-report.md) | image_analysis comparison report (initial draft) | HITL | PR06 | ⚠️ superseded by PR06b for next iteration |
| [PR08](PR08-document-redaction-execution.md) | `predict-rlm-redaction-tools` + document_redaction execution | AFK | PR02, PR03, PR04, PR05, PR-Pre03, PR-Prompt | ✅ done — 92 redactions vs predict-rlm's 89, 28.9s vs 87s, 8-node tree with `apply-redactions` used 2× via PATH A |
| [PR09](PR09-document-redaction-report.md) | document_redaction comparison report (clean + deep-dive) | HITL | PR08 | ✅ done — `02_document_redaction.md` + `02_document_redaction_deep_dive.md` |
| [PR10](PR10-invoice-processing-execution.md) | `predict-rlm-invoice-tools` + invoice_processing execution | AFK | PR02, PR03, PR04, PR05, PR-Pre03, PR-Prompt | ⏳ pending |
| [PR11](PR11-invoice-processing-report.md) | invoice_processing comparison report | HITL | PR10 | ⏳ pending |
| [PR12](PR12-aggregate-index-report.md) | Aggregate index report `00_index.md` | HITL | PR07 (updated via PR06b), PR09, PR11 | ⏳ pending |

### Pre-existing bugs surfaced (independent, non-blocking)

| ID | Title | Type | Blocked by |
|---|---|---|---|
| [PR-Pre01](PR-Pre01-repl-researcher-test-failures.md) | Fix 12 failures in `repl_researcher_test.clj` (non-RLM iterative mode) | AFK | none |
| [PR-Pre02](PR-Pre02-dual-test-load-isolation.md) | Fix `rlm_dsl_test` + `rlm_mode_test` dual-load Integrant fixture failure | AFK | none |

---

## Methodology

- **AFK** issues can be implemented and merged without human interaction (subject to TDD verification).
- **HITL** issues require hand-authored content based on inspection of real benchmark run data.
- Per the PRD, all benchmark executions must run live against real LLMs before any slice is considered complete. No assumptions, no fallbacks: bugs are traced to root cause in the proper grain/orc manner.
- **Port-cleanup principle locked during PR07** (verbatim goal, not verbatim methodology) applies to PR08 and PR10 going forward. Each task's `:instruction` includes an adversarial-completeness clause; the framework prompt (after PR-Prompt) defaults to `emit-tree!` for non-trivial work.

## Dependency graph

```
PR01 (worktree + baseline) ── done
 ├─ PR02 (:code DSL) ── done
 │   ├─ PR08 (document_redaction execution) ⏳
 │   │   └─ PR09 (report) ⏳
 │   └─ PR10 (invoice_processing execution) ⏳
 │       └─ PR11 (report) ⏳
 ├─ PR03 (:available-code-nodes) ── done
 │   ├─ PR06 (image_analysis execution) ── done (PR06b pending)
 │   │   └─ PR07 (report) ── done (superseded by PR06b)
 │   ├─ PR08 ─┐
 │   └─ PR10 ─┤
 ├─ PR04 (predict-rlm-pdf) ── done
 │   ├─ PR08 ─┤
 │   └─ PR10 ─┤
 └─ PR05 (comparison runner) ── done
     ├─ PR06 ── done
     ├─ PR08 ─┤
     └─ PR10 ─┘

NEW critical-path fixes (must land before PR06b/PR08/PR10):
 PR-Pre03 (vision routing) ──┬── PR06b ── then unblocks PR07 refresh + PR12
 PR-Prompt (emit-tree default) ┘                          │
                                                          ├── PR08 → PR09 ──┐
                                                          └── PR10 → PR11 ──┴── PR12 (index)

Independent pre-existing bugs:
 PR-Pre01 (repl_researcher tests)  ── non-blocking
 PR-Pre02 (dual-test-load isolation) ── non-blocking
```

## Diff currently in worktree (uncommitted as of resumption note)

```
M components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj    (PR02 :code prompt doc + PR03 :available-code-nodes plumbing)
M components/orc-service/src/ai/obney/orc/orc_service/core/rlm_dsl.clj     (PR02 :code translation case)
M components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj  (PR05/PR06 :rlm/researcher-iterations event emission)
M components/orc-service/src/ai/obney/orc/orc_service/interface/schemas.clj     (PR06 :rlm/researcher-iterations event schema registration)
M components/orc-service/test/ai/obney/orc/orc_service/rlm_dsl_test.clj    (PR02 + PR03 tests)
M deps.edn                                                                 (PR04 + PR06 brick registrations)
A components/predict-rlm-pdf/...                                           (PR04)
A components/predict-rlm-image-tools/...                                   (PR06)
A development/bench/predict_rlm_comparison/runner.clj                      (PR05)
A development/bench/predict_rlm_comparison/runner_test.clj                 (PR05 lock helpers)
A development/bench/predict_rlm_comparison/tasks/image_analysis.clj        (PR06)
A development/bench/predict-rlm-comparison/references/predict-rlm/...      (PR06 sample data + LICENSE)
A development/bench/predict-rlm-comparison/results/*.edn + *.trace.edn     (PR01/PR05/PR06 live run artifacts)
A development/bench/predict-rlm-comparison/reports/01_image_analysis.md    (PR07 — will be refreshed by PR06b)
A docs/prd/predict-rlm-benchmark-ports.md                                  (this PRD, updated through PR07)
A docs/issues/predict-rlm/*.md                                             (these issue files)
```

Nothing has been committed yet. The user's policy is "minimal commit set at end of work" — we'll decide what goes in after PR12 completes.
