# US-Bench-Bricks — PR-Bench commits 1-3: 3 Polylith bricks + 2 task definitions

## Parent

`docs/prd/upstream-pr-plan.md` — Step 3 of the execution sequence, first three commits of PR-Bench.

## What to build

Branch `feature/predict-rlm-comparison-bench` off `feature/rlm-framework-upgrades` (or off main if PR-Framework has already merged by the time this slice begins).

Three commits:

**Commit 1:** `feat(bench): predict-rlm-pdf brick`
- New Polylith component under `components/predict-rlm-pdf/`
- `interface.clj` exports:
  - `page-count` — count pages in a PDF
  - `render-pages-as-data-uris` — render each page as a base64 PNG data URI, configurable DPI
  - `extract-pages-as-text` — extract text per page
- `deps.edn` declares the Apache PDFBox 3.0.3 dependency (opt-in for benchmarks that need it)
- 5 existing deftests for the brick (fixture-loadable, page-count matches fixtures, render shape, decoded PNG validity, DPI affects size, text extraction)
- Registered in root `deps.edn` `:dev` `:extra-deps` AND `:test` `:extra-paths`

**Commit 2:** `feat(bench): predict-rlm-image-tools brick + image_analysis benchmark task`
- New Polylith component under `components/predict-rlm-image-tools/`
- `interface.clj` exports:
  - `image->data-uri` — base64 encoding + MIME detection
  - `count-letter-frequencies` — deterministic A-Z letter counter (pre-built `:code` node fn)
  - `format-letter-counts-answer` — formats frequency map into the answer string format
  - `available-code-nodes` — markdown catalog string surfaced via the task's `:available-code-nodes`
- 4 existing deftests for MIME detection + data URI shape
- New benchmark task file `development/bench/predict_rlm_comparison/tasks/image_analysis.clj`:
  - Verbatim default query from predict-rlm `run.py` DEFAULT_QUERY
  - Port-cleaned goal instruction (per the port-cleanup principle documented in the parent PRD): verbatim end-goal + adversarial-completeness clause + structural-verification clause; Python-specific tool nouns stripped
  - Model config: `:model "openai/gpt-5.4"` + `:sub-model "openai/gpt-5.1-chat"`
  - `:input-schemas {:image [:string {:field-type :image}] :query :string}`
  - `:input-loader` returns the encoded image + default query
  - `:writes [:answer]`
  - `:predict-rlm-reported` metadata block with the reference numbers (sourced from predict-rlm's `sample/output/output.md`)

**Commit 3:** `feat(bench): predict-rlm-redaction-tools brick + document_redaction benchmark task`
- New Polylith component under `components/predict-rlm-redaction-tools/`
- `interface.clj` exports:
  - `apply-redactions` — pure deterministic substring-replacement transform with shape-detect inputs (so the model can name `:reads` keys freely; the fn detects vector-of-strings = page-texts and vector-of-maps = targets by shape)
  - `available-code-nodes` — minimal plain-text catalog string (NOT containing embedded escaped-quote `[:code {:fn \"...\"}]` examples — those caused parse failures on gpt-5.4)
- 6 existing deftests for `apply-redactions` (tracer one-target, multi-page multi-target, missing-target graceful, page-summaries deduped categories, empty-targets, idempotence)
- New benchmark task file `development/bench/predict_rlm_comparison/tasks/document_redaction.clj`:
  - Verbatim criteria string from predict-rlm `run.py` CRITERIA
  - Port-cleaned goal instruction with structural-verification clause + adversarial-completeness clause
  - Model config: `:model "openai/gpt-5.4"` + `:sub-model "openai/gpt-5.1-chat"`
  - `:input-schemas {:page-texts [:vector :string] :page-images [:vector {:field-type :image} :string] :criteria :string}`
  - `:output-schemas` per write key (redaction result is structured data, not strings)
  - `:input-loader` extracts PDF text + renders pages via `predict-rlm-pdf`
  - `:writes [:redacted-text-per-page :total-redactions :page-summaries :targets-applied :targets-missing]`
  - The `:available-code-nodes` catalog content is embedded directly in the `:instruction` string (workaround for the dscloj-input-field issue documented in the deep-dive — present here as a note in the task file's commentary)
  - `:predict-rlm-reported` metadata block with 89 redactions / 87s / $0.18 / 65,847 tokens (sourced verbatim from predict-rlm's `sample/output/output.md`)

Each brick has its own opt-in `deps.edn` — external consumers pull only what their chosen benchmarks need.

## Acceptance criteria

- [ ] Branch `feature/predict-rlm-comparison-bench` exists off `feature/rlm-framework-upgrades` or off main (whichever is current).
- [ ] 3 separate commits in dependency order: `predict-rlm-pdf`, then `predict-rlm-image-tools + image_analysis task`, then `predict-rlm-redaction-tools + document_redaction task`.
- [ ] Each new component has its own `deps.edn` with scoped opt-in dependencies.
- [ ] Each component has its existing brick tests (5 + 4 + 6 = 15 deftests) passing via `clj -M:poly test brick:<name> :dev` or `clj -M:dev:test`.
- [ ] Root `deps.edn` registers all 3 bricks under `:dev` `:extra-deps` AND `:test` `:extra-paths`.
- [ ] Each task file references its brick(s) correctly and produces a syntactically-valid `task` map.
- [ ] No regression on `rlm-mode-test`, `rlm-dsl-test`, existing 5-benchmark suite (the new bricks should be additive only).
- [ ] Each commit is mergeable and bisectable.

## Blocked by

- US-FW-C (the bench depends on PR-Framework's framework upgrades; specifically `:output-schemas` schema-driven structured output U11, inline-fn `:code` U2/U3/U8, etc. The branch can be built on top of `feature/rlm-framework-upgrades` even before PR-Framework lands upstream — they share a local base.)
