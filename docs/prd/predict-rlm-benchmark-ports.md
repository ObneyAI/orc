# PRD: predict-rlm Benchmark Ports — Apples-to-Apples RLM Comparison

**Status:** Ready for implementation
**Author:** daryl@obney.ai (with Claude grill session)
**Plan:** `/Users/darylroberts/.claude/plans/in-another-branch-we-glistening-kettle.md`

## Problem Statement

ORC's RLM (Research Language Model) tree-emitting researcher adapts behavior tree design to the task at hand — proven across five in-house generalization benchmarks (headline report `development/bench/reports/07_final_generalization_report.md`, "37 spot-checked facts, 0 hallucinations").

To make that generalization claim defensible against a published external reference, we want apples-to-apples comparison against Trampoline-AI's predict-rlm (https://github.com/Trampoline-AI/predict-rlm), which exposes 5 reference benchmarks via a DSPy-based Python-in-WASM RLM. We've ported one (`contract_comparison_validated`). Three remain — `invoice_processing` (vision + Excel), `document_redaction` (vision + deterministic redaction), `image_analysis` (single-image VLM). Without them the comparison is incomplete and our generalization claim cannot be honestly defended at the field-suite level.

Two ORC capability gaps also block the work:
1. `emit-tree!` DSL doesn't accept `:code` nodes — so model-emitted trees can't reference custom Clojure tools.
2. There's no mechanism to expose per-benchmark tool catalogs to the researcher; the existing `:mcp-tools` plumbing is wired for non-RLM iterative-REPL mode only.

## Solution

1. **Two surgical core extensions** unlock everything else:
   - `:code` case added to `rlm-dsl->orc-dsl` so model-emitted trees can include `[:code {:fn "ns/sym" :reads [...] :writes [...]}]`.
   - `:available-code-nodes` field plumbed through `:rlm` map config to a new dscloj input field in the researcher's code-generation prompt, surfacing per-benchmark tool catalogs without polluting goal instructions.

2. **Four new Polylith components**, each with its own opt-in dependencies, so external consumers download only what their chosen benchmarks need:
   - `predict-rlm-pdf` — Apache PDFBox wrapper (render pages → data URIs; extract per-page text). Deep, pure, unit-testable.
   - `predict-rlm-invoice-tools` — invoice task definition + Excel workbook builder (docjure/POI, opt-in).
   - `predict-rlm-redaction-tools` — redaction task definition + deterministic `apply-redactions` transformation (pure function).
   - `predict-rlm-image-tools` — image task definition + base64 helpers.

3. **A new benchmark runner** under `development/bench/predict-rlm-comparison/` mirroring the existing `development/bench/runner.clj`, with capture extensions that preserve Phase 1 researcher iterations, per-leaf-node IO from event-store events, per-node usage breakdown, and a per-run mulog JSONL trace file. Single-task-lock prevents concurrent runs corrupting sheet state.

4. **Verbatim goal instructions** copied from predict-rlm's `signature.py` docstrings — no paraphrasing, no tree hints. The model designs its own tree from goal + tool catalog, exactly as the existing 5 benchmarks do.

5. **Hand-authored side-by-side comparison reports** following the existing `07_final_generalization_report.md` style: emitted tree S-expr, per-node usage table, sub-LLM call walkthrough citing the event trace, hand-authored quality assessment via manual spot-check, explicit fidelity caveats, and an aggregate `00_index.md` summarizing the cross-benchmark generalization claim.

## User Stories

1. As a benchmark author, I want to run each predict-rlm comparison benchmark from a single REPL command, so that I can quickly iterate and re-run as needed.
2. As a benchmark author, I want pre-loaded image data URIs available to the model via blackboard keys with `:field-type :image`, so that the RLM researcher can do real vision sub-LLM calls without writing rendering code itself.
3. As a benchmark author, I want the goal instruction passed to the researcher to be a verbatim copy of predict-rlm's signature docstring, so that the comparison is fair and not biased by prompt tuning.
4. As a benchmark author, I want a per-benchmark catalog of available code-node functions (with input/output shapes) injected into the researcher's prompt automatically, so that the model knows what tools it can invoke in its emitted tree.
5. As a benchmark author, I want every sub-LLM call's full inputs and outputs persisted to disk after the run, so that I can manually inspect each call when writing the comparison report.
6. As a benchmark author, I want the raw LLM prompts and responses captured via mulog JSONL alongside the result EDN, so that I can debug parser failures and analyze actual model behavior.
7. As a benchmark author, I want Phase 1 researcher iterations (the code the model generated each round) preserved in the result file, so that I can trace the model's thinking when designing a tree.
8. As a benchmark author, I want per-node token usage broken down in the result EDN, so that I can identify which sub-LLM calls were expensive and why.
9. As a benchmark author, I want a single-task-lock that prevents starting a second benchmark run while one is in flight, so that sheet state can't be corrupted by concurrent runs.
10. As a benchmark author, I want each per-benchmark component to declare its own opt-in dependencies (PDFBox, docjure), so that external consumers don't pay for benchmarks they don't use.
11. As a benchmark author, I want the model to design its own behavior tree from a goal-only instruction (never told what tree to emit), so that the comparison validates true adaptive generalization.
12. As a benchmark author, I want the document_redaction benchmark to use vision LLM identification (matching predict-rlm methodology) plus a deterministic `apply-redactions` code node, so that we test the model's adaptive ability rather than redaction mechanics.
13. As a benchmark author, I want the invoice_processing benchmark to produce both structured `InvoiceExtractionResult` and an `.xlsx` workbook (via opt-in docjure code node), so that we match predict-rlm's output schema and reported scope.
14. As a benchmark author, I want the image_analysis benchmark to send a base64-encoded image plus query to a vision LLM, so that we test single-image VLM tasks comparably.
15. As a benchmark author, I want a Phase 0 re-run of the existing `contract_comparison_validated` benchmark on the new branch baseline, so that I can confirm infrastructure works before adding new benchmarks.
16. As a benchmark author, I want each new benchmark built and validated in order of increasing complexity (image_analysis → document_redaction → invoice_processing), so that failures surface early on small-scope tasks.
17. As a benchmark author, I want a 4-step smoke protocol per benchmark (schema dry-run → real run at max-iter 5 → sanity inspection → reproducibility run), so that I can validate each benchmark deterministically without wasted runs.
18. As a reviewer, I want each comparison report to follow a consistent skeleton (task, inputs, methodology side-by-side, metrics table, emitted tree, sub-LLM walkthrough, hand-authored quality assessment, fidelity caveats, findings), so that I can compare benchmarks at a glance and cite findings consistently.
19. As a reviewer, I want methodology asymmetries between predict-rlm and our ports made explicit in each report (e.g., redaction text-output vs PDF-output, model-wrote-openpyxl vs deterministic-code-node), so that the comparison is honest and defensible.
20. As a reviewer, I want the aggregate index report to summarize token efficiency, quality, and tree-design observations across all 4 ports, so that I have a single document to share for the generalization claim.
21. As a reviewer, I want predict-rlm's sample inputs (PDFs, images) used verbatim from their MIT-licensed repository, so that our outputs can be compared directly against their published reference outputs.
22. As a reviewer, I want predict-rlm's MIT LICENSE included alongside copied sample inputs in our repo, so that attribution is preserved.
23. As a reviewer, I want each report's "Sub-LLM call walkthrough" section to cite specific entries from the event-trace and mulog JSONL files, so that any quality claim is reproducible by re-reading the trace data.
24. As an external ORC consumer, I want to pull only the predict-rlm benchmark components I care about (e.g., just `image_analysis` without PDFBox/docjure), so that my classpath stays lean.
25. As an external ORC consumer, I want the per-benchmark components named with the `predict-rlm-` prefix, so that they're clearly scoped to the comparison effort and not mistaken for general-purpose utilities.
26. As a future ORC developer, I want the `:code` node type accepted by `emit-tree!` and translated by `rlm-dsl->orc-dsl`, so that any future benchmark can ship custom pure-Clojure tools the model can compose into trees.
27. As a future ORC developer, I want the `:available-code-nodes` field on the researcher node config (via the `:rlm` map) to surface per-benchmark tool documentation in the model's prompt, so that future tool sets can be added without modifying core prompt code.
28. As a future ORC developer, I want a clean PR against `main` with only the predict-rlm-specific commits (not `feature/core-orc-upgrades` commits), so that the predict-rlm comparison work can merge independently.
29. As a future ORC developer, I want unit tests for the deep modules (PDFBox wrapper, `apply-redactions`, `:code` DSL extension, `build-invoice-workbook`), so that regressions in foundational tools are caught before benchmark runs.
30. As a future ORC developer, I want this work to follow a pattern reusable for other bench suites (per-suite `predict-X-*` brick prefix; runner under `development/bench/<suite>-comparison/`; per-benchmark opt-in deps), so that adding more comparisons later is mechanical.
31. As the RLM model running a benchmark, I want my emitted tree to support `:code` nodes referencing pre-built Clojure functions, so that I can compose deterministic tool calls into the tree alongside LLM sub-calls.
32. As the RLM model running a benchmark, I want a catalog of available tool functions (name, inputs, outputs, description) visible in my prompt, so that I know what code nodes I can emit and what their contracts are.

## Implementation Decisions

### Core ORC extensions (two surgical changes)

- **`:code` case in `rlm-dsl->orc-dsl`**: extends the existing DSL translator (which already handles `:sequence`, `:llm`, `:map-each`, `:chunk-document`, `:aggregate`, `:parallel`, `:final`). Accepts `[:code {:fn "ns/sym" :reads [...] :writes [...]}]` and translates to `(sheet/code :fn "ns/sym" :reads [...] :writes [...])`. The downstream Phase-2 tree executor already supports `:executor :code` with `:fn` symbol resolution via `ns-resolve`, so no executor changes required. ~10 LOC.

- **`:available-code-nodes` plumbing**: a new optional field on the existing `:rlm` map config (which is already `[:or :boolean :map]` — no schema changes needed). Read in `execute-repl-researcher-rlm` via the same `(get rlm-config :available-code-nodes)` pattern already used for `:debug?`. Passed to `build-rlm-code-generation-module` and added to the dscloj module as a new `:inputs` field with description "Available code-node functions for use in emit-tree! :code nodes." The runner constructs the markdown catalog for each benchmark and supplies it via `:rlm {:debug? true :available-code-nodes "<markdown>"}` on the researcher node. ~15-20 LOC.

### New Polylith components (Pattern A naming: `predict-rlm-*`)

- **`predict-rlm-pdf`** — exports:
  - `(render-pages-as-data-uris path & {:keys [dpi] :or {dpi 200}})` → vector of `"data:image/png;base64,..."` strings.
  - `(extract-pages-as-text path)` → vector of page strings.
  - Backed by `org.apache.pdfbox/pdfbox` (`PDFRenderer` + `PDFTextStripper`).
  - Deep module: pure I/O against PDF files, no LLM, deterministic.

- **`predict-rlm-invoice-tools`** — exports:
  - Task definition map (verbatim instruction from predict-rlm `examples/invoice_processing/signature.py`, output Malli schema mirroring their `InvoiceExtractionResult` Pydantic shape, `:available-code-nodes` markdown catalog string).
  - `(build-invoice-workbook {:keys [inputs context]})` code-node function — canonical `(fn [{:keys [inputs]}] {...})` signature. Takes `:invoices` (vector of Invoice maps) + `:output-path` (string), produces `.xlsx` with one sheet per invoice + a Summary sheet matching predict-rlm's schema, returns `{:workbook-path "..."}`. Uses `dk.ative/docjure`.

- **`predict-rlm-redaction-tools`** — exports:
  - Task definition map (verbatim instruction from `examples/document_redaction/signature.py`, output Malli schema mirroring their `RedactionResult`).
  - `(apply-redactions {:keys [inputs]})` code-node function — *deep module*. Takes `:page-texts` (vector of strings) + `:targets` (vector of vectors of `{:text :category :reason}` maps, outer index = page), produces `{:redacted-text-vector ... :total-redactions N :page-summaries [...]}`. Pure transformation: no I/O, no LLM, deterministic substring replacement.

- **`predict-rlm-image-tools`** — exports:
  - Task definition map (verbatim instruction from `examples/image_analysis/signature.py`).
  - `(image->data-uri path)` helper — base64 encoding + MIME detection. Thin.

### Runner & capture extensions

New runner under `development/bench/predict-rlm-comparison/` mirroring `development/bench/runner.clj`:

- **Document pre-loading**: for each task, load PDFs/images/text per task config. For images and rendered PDF pages, declare blackboard keys with Malli schema `[:vector {:field-type :image} :string]` (vector of data URIs) or `[:string {:field-type :image}]` (single data URI) — `executor.clj:330-357` `build-field` already extracts `:field-type :image` and dscloj passes them as OpenAI-format `{:type "image_url" :image_url {:url data-uri}}` content blocks.

- **Capture additions to `save-result!`** (compared to existing `runner.clj` which strips these):
  - `:iterations` — Phase 1 researcher iterations (already returned by `execute-repl-researcher-rlm`, currently dropped).
  - `:by-node` — per-leaf-node token usage (already returned, currently dropped).
  - `:node-trace` — read all `:sheet/node-execution-completed` events from the event store post-run, sort by event timestamp, include in result EDN. Each entry: `{:node-id :status :inputs :writes :usage :duration-ms}`. Surface already exists per schemas.clj:874-888.

- **Mulog JSONL trace**: attach `com.brunobonacci.mulog/start-publisher!` writing JSON-lines to `<results-dir>/<task>_<timestamp>.trace.jsonl` for the duration of each run; the existing `u/trace ::rlm-llm-primitive` blocks in `rlm_sandbox.clj` and elsewhere capture raw prompt/response data into these events.

- **Single-task-lock**: an atom in the runner that refuses to start a new task while a prior one is in flight. ~5 LOC.

### Vision-input mechanism

For `invoice_processing` and `image_analysis`: the runner pre-renders PDFs (via `predict-rlm-pdf`) and/or pre-encodes images, then loads the resulting data URIs into the blackboard with `:field-type :image` schemas before kicking off the researcher. The model sees the blackboard keys as previews, designs whatever tree it wants, and references them in `:llm` nodes' `:reads`. dscloj handles the multimodal content block construction end-to-end.

For `document_redaction`: the runner pre-loads **both** `:document-pages` (image data URIs) AND `:document-page-texts` (vector of strings). Vision LLM identifies targets per page via map-each; aggregate produces vector-of-vectors indexed by page; `apply-redactions` code node combines per-page text + per-page targets deterministically.

### Model and provider

`google/gemini-3-flash-preview` for all benchmarks (already used by existing 5-benchmark suite; supports vision). Configured in the runner's `config` map mirroring `development/bench/runner.clj:42`.

### Branch mechanics

- New worktree from `main`: `git worktree add ../orc-predict-rlm main`
- New branch: `feature/predict-rlm-benchmarks`
- **No cherry-pick** — `main` already contains all required infrastructure (RLM DSL, bench runner, P01 fix, O01-O03 observability — verified via `git log main`).
- Three new authored commits expected by end-of-work:
  1. `feat(rlm): support :code node in emit-tree! DSL`
  2. `feat(rlm): plumb :available-code-nodes through :rlm config`
  3. The predict-rlm-comparison work itself (components + runner + reports + references).

### Build & test order

1. Phase 0: re-run existing `contract_comparison_validated` on the new branch baseline.
2. Land core changes (`:code` DSL + `:available-code-nodes` plumbing).
3. Build `predict-rlm-pdf` component (unit tests against fixture PDF).
4. Build runner capture extensions; validate by running an existing benchmark with the new runner and confirming the new EDN/JSONL fields populate correctly.
5. **`image_analysis`** (smallest scope, validates vision plumbing).
6. **`document_redaction`** (exercises the `:code` DSL extension and vision-per-page + aggregate-by-page pattern).
7. **`invoice_processing`** (largest scope, exercises everything).
8. Write 3 comparison reports + aggregate index.

### Per-benchmark smoke protocol

1. Schema dry-run (no LLM): declare blackboard keys, load docs/images, verify schemas validate.
2. One real run with `:max-iterations 5` (default).
3. Sanity inspection: open EDN, eyeball outputs against source documents.
4. One reproducibility run; confirm tree shape variability and similar quality.

No artificial token cap. Single-task-lock prevents accidental concurrent runs.

### Static assets

- `references/predict-rlm/LICENSE` — verbatim MIT license from predict-rlm.
- `references/predict-rlm/<task>/signature.py.txt` — verbatim signature docstrings, for traceability.
- `references/predict-rlm/<task>/sample/input/*` — copied sample PDFs/images.
- `references/predict-rlm/<task>/sample/output/*` — copied sample outputs for cross-comparison.
- `ground-truth/<task>.edn` — parsed/structured ground truth (e.g., the 89 redaction targets from predict-rlm's `output.md` for `document_redaction`).

### Goal-instruction discipline

Every goal instruction is a verbatim copy of the predict-rlm `signature.py` docstring. No paraphrasing, no tree hints, no methodology suggestions. The per-benchmark tool catalog (markdown listing of available code-node functions with input/output shapes) is delivered via a separate channel — the `:rlm {:available-code-nodes "..."}` map — so it cannot be conflated with the goal.

### Comparison report skeleton

Each report under `development/bench/predict-rlm-comparison/reports/` follows the same skeleton with auto-populated metric/tree/methodology rows from the EDN and hand-authored quality assessment + findings sections. Aggregate index at `00_index.md` summarizes cross-benchmark generalization claims.

## Testing Decisions

**What makes a good test in this codebase:**
- Test the external behavior, not the implementation. Functions are tested via their input → output contracts.
- Prefer pure-data fixtures over LLM round-trips: tests should run fast and deterministically.
- Use the existing `clj -M:poly test brick:<name>` per-component pattern.
- For DSL transformations, mirror `rlm_dsl_test.clj` — round-trip an S-expr and assert canonical-form output.

**Modules to be tested (per user selection):**

1. **`:code` DSL extension** in `rlm-dsl->orc-dsl`:
   - Prior art: `rlm_dsl_test.clj` tests every other case (`:sequence`, `:llm`, `:map-each`, `:chunk-document`, `:aggregate`, `:parallel`, `:final`) by asserting canonical-form output for given S-expr input.
   - New cases: round-trip `[:code {:fn "ns/sym" :reads [:a] :writes [:b]}]` to `(sheet/code :fn "ns/sym" :reads [:a] :writes [:b])`. Single-write, multi-write, no-reads variants. Negative case: missing `:fn` field throws.

2. **`predict-rlm-pdf`**:
   - Fixture: a small, committed PDF (e.g., a 2-page slice of the predict-rlm employment_agreement sample, kept under `test/fixtures/`).
   - Tests:
     - `(render-pages-as-data-uris path)` returns a vector of length 2, each starting with `"data:image/png;base64,"` and decoding to a non-empty PNG.
     - `(extract-pages-as-text path)` returns a vector of length 2, each a non-empty string containing expected fixture substrings.
     - DPI parameter influences output size (higher DPI → larger byte counts).
   - Pure I/O, no LLM, fast.

3. **`predict-rlm-redaction-tools/apply-redactions`**:
   - Synthetic fixture: `[:page-texts ["John Smith works..." "Contact 555-1234..." "..."]]` + `[:targets [[{:text "John Smith" :category "person_name"}] [{:text "555-1234" :category "phone_number"}] []]]`.
   - Tests:
     - `:total-redactions` matches sum of per-page target counts.
     - Every target's `:text` is NOT a substring of the corresponding redacted page text.
     - `:page-summaries` reflects accurate counts and category lists per page.
     - Idempotence: applying redaction twice doesn't break.
     - Empty targets vector yields untouched text and zero count.
   - Pure transformation; no I/O, no LLM.

4. **`predict-rlm-invoice-tools/build-invoice-workbook`**:
   - Synthetic fixture: `[{:vendor-name "Acme" :invoice-number "001" :date "2026-01-15" :total 100.0 :line-items [{:description "Widget" :quantity 1 :unit-price 100.0 :amount 100.0}]} {...second invoice...}]`.
   - Tests:
     - Workbook file is created at the expected path.
     - Re-opening it (via docjure) shows N+1 sheets: one Summary sheet + one per invoice.
     - Summary sheet has one row per invoice with the expected vendor/total values.
     - Each invoice sheet contains its line-item rows.
   - Slower (file I/O + xlsx parse) but deterministic; acceptable trade-off for sanity verification of the workbook contract.

**Not unit-tested (validated end-to-end via benchmark runs instead):**
- Runner capture extensions (`:iterations`, `:by-node`, `:node-trace`, mulog JSONL) — validated by running an existing benchmark with the new runner and asserting the EDN/JSONL contain expected fields. Tighter feedback than mocking the event store.
- `:available-code-nodes` plumbing — validated end-to-end by the benchmark runs: if the model emits trees that successfully invoke our code nodes by name, the plumbing works.
- Goal-instruction verbatim copies — checked by PR review against the cloned predict-rlm source under `/tmp/predict-rlm-read/`.

## Out of Scope

- **Path C — StaticMCPClient adapter for iterative-REPL mode comparison.** Deferred per Q2 outcome. Documented for future work; would let benchmarks also run in non-RLM iterative-REPL mode for a second axis of comparison with predict-rlm.
- **True PDF-native redaction parity.** predict-rlm uses pymupdf's `apply_redactions()` to modify the underlying PDF object structure; we apply redactions to extracted text only. Documented as a fidelity caveat in the comparison report.
- **LLM-as-judge.** Manual qualitative review is the judge for this work. Adding an LLM judge would introduce variance that confounds the comparison.
- **Porting `document_analysis` and `contract_comparison` to vision mode.** These already exist in the in-house 5-benchmark suite using preparsed `.txt`; vision-mode ports would be a separate effort.
- **New RLM primitives or executor changes** beyond the two surgical core extensions.
- **Multi-run statistical averaging.** Predict-rlm doesn't publish averages; neither do we. We run each benchmark twice (smoke + reproducibility) and report both numbers.
- **Cross-branch coordination with `feature/core-orc-upgrades`.** Our work merges to `main` on its own timeline; that branch handles its own merge.
- **Vision model swap.** `gemini-3-flash-preview` is used throughout, matching the existing 5-benchmark suite. Other VLMs (Claude 3.5 Sonnet, GPT-4o family) are not evaluated.
- **Mock execution / sandbox-only testing of the runner.** All benchmark runs in this PRD hit real OpenRouter; no mock backends.

## Further Notes

- **Sample-data licensing.** predict-rlm is MIT-licensed (verified via their `LICENSE`). Sample inputs (PDFs, screenshot) and the LICENSE file are copied verbatim into `references/predict-rlm/` with attribution.
- **Predict-rlm source location.** Cloned read-only at `/tmp/predict-rlm-read/predict-rlm/` for development reference. Not committed to our repo; intended to be ephemeral.
- **Cost framing.** Per-benchmark API spend is in the $0.50-2 range based on existing bench numbers. Total under $20 across the whole effort. User has explicitly de-prioritized cost concerns ("I don't care about the cost of LLM runs… make sure the benchmark tests are thorough and complete").
- **Existing benchmark suite is untouched.** Phase 0 re-run validates only that the baseline still works; we don't modify any existing task definitions or runner code. The new runner under `development/bench/predict-rlm-comparison/` lives alongside the existing one.
- **Manual-review tradition.** Quality assessment follows the depth of `07_final_generalization_report.md` — spot-check facts against source documents, inspect emitted tree shape, examine per-leaf-node IO from the event trace, capture qualitative observations the metric tables can't. Token efficiency claims are sanity-checked against the captured `:by-node` breakdown.
- **Future-suite reusability.** This work's patterns (per-suite `predict-X-*` brick prefix, per-benchmark opt-in deps via component `deps.edn`, runner under `development/bench/<suite>-comparison/`, the `:available-code-nodes` mechanism, the comparison report skeleton) are intentionally designed to support additional benchmark suites without re-litigating these decisions.
- **Implementation plan reference.** `/Users/darylroberts/.claude/plans/in-another-branch-we-glistening-kettle.md` was approved during plan mode; this PRD is the synthesized form that captures the 13 grilling-session decision points (Q1-Q13) into a single shippable spec.
