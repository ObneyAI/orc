# PRD: predict-rlm Benchmark Ports — Apples-to-Apples RLM Comparison

**Status:** In progress — PR01-PR07 complete; PR06/PR07 surfaced two new core fixes (Phase-1 vision routing, prompt sequential-llm-chain guidance) folded back into this PRD before continuing.
**Author:** daryl@obney.ai (with Claude grill session + iterative refinement)
**Plan:** `/Users/darylroberts/.claude/plans/in-another-branch-we-glistening-kettle.md`
**Issues:** `docs/issues/predict-rlm/`

## Problem Statement

ORC's RLM (Research Language Model) tree-emitting researcher adapts behavior tree design to the task at hand — proven across five in-house generalization benchmarks (headline report `development/bench/RESULTS.md`, "37 spot-checked facts, 0 hallucinations").

To make that generalization claim defensible against a published external reference, we want apples-to-apples comparison against Trampoline-AI's predict-rlm (https://github.com/Trampoline-AI/predict-rlm), which exposes 5 reference benchmarks via a DSPy-based Python-in-WASM RLM. We've ported one (`contract_comparison_validated`). Three remain — `invoice_processing` (vision + Excel), `document_redaction` (vision + deterministic redaction), `image_analysis` (single-image VLM). Without them the comparison is incomplete and our generalization claim cannot be honestly defended at the field-suite level.

Three ORC capability gaps blocked the work at start:
1. `emit-tree!` DSL doesn't accept `:code` nodes — model-emitted trees can't reference custom Clojure tools. **Resolved in PR02.**
2. No mechanism to expose per-benchmark tool catalogs to the researcher; the existing `:mcp-tools` plumbing is wired for non-RLM iterative-REPL mode only. **Resolved in PR03.**
3. *Discovered during PR06/PR07*: **Phase-1 sub-LLM calls don't propagate image-typed blackboard schemas through dscloj.** When the model writes `(llm "..." :reads [:image] ...)` in direct-execution mode, the data URI is sent as text content (character-counted into tokens) instead of as `image_url` multimodal content. Wildly inflates reported token counts (480K vs ~1K real) and likely degrades model vision quality. **To be resolved as PR-Pre03.**

Two refinements emerged during execution:
4. **Verbatim signature docstrings are not the right port** — predict-rlm's signatures are Python procedural recipes ("use pathlib, asyncio.gather, dspy.Image"). Keeping them verbatim leaks tool nouns and step-by-step framing that don't apply to our runtime and confuse the model. *Locked: "verbatim goal, not verbatim methodology" — see Port-cleanup principle below.*
5. **The RLM prompt doesn't currently push the model toward `emit-tree!` when writing chained sequential `(llm ...)` calls in Phase 1.** This pattern often indicates a tree would be more appropriate (better observability, support for `:code` nodes, retry). The image_analysis run wrote two chained sub-LLM calls instead of emitting a tree — and missed the `:code`-node affordance for deterministic letter counting. *To be addressed by a small RLM-prompt addition.*

## Solution

1. **Core ORC extensions** (committed as we go on this branch):
   - **PR02:** `:code` case in `rlm-dsl->orc-dsl` so emit-tree! trees can include `[:code {:fn "ns/sym" :reads [...] :writes [...]}]`.
   - **PR03:** `:available-code-nodes` plumbed through `:rlm` map config to a dscloj input field in the researcher's prompt.
   - **PR06 follow-on:** new event type `:rlm/researcher-iterations` emitted always when researcher iterations exist (independent of tree emission). Schema registered. Runner queries it for uniform iteration capture across tree-emit and direct-execution runs.
   - **PR-Pre03 (new):** Phase-1 sub-LLM image routing — propagate `:field-type :image` from blackboard schema to dscloj module's input `:type` so the data URI is sent as `image_url`, not as text. ~5-10 LOC fix in `rlm_sandbox.clj`.
   - **PR-Prompt (new):** RLM prompt addition encouraging emit-tree! when the model is writing chained sequential `(llm ...)` calls in Phase 1 + reminder that `:code` nodes are available for deterministic transforms.

2. **Four new Polylith components**, each with its own opt-in dependencies:
   - **`predict-rlm-pdf`** (PR04 — done) — Apache PDFBox wrapper.
   - **`predict-rlm-image-tools`** (PR06 — done) — image base64 + MIME helpers; task definition.
   - **`predict-rlm-redaction-tools`** (PR08 — pending) — task definition + `apply-redactions` deterministic transformation.
   - **`predict-rlm-invoice-tools`** (PR10 — pending) — task definition + `build-invoice-workbook` (docjure).

3. **New benchmark runner** (PR05 — done) under `development/bench/predict-rlm-comparison/` mirroring the existing runner with capture extensions: `:iterations`, `:by-node`, `:node-trace` (from event store), per-run mulog `.trace.edn`, single-task-lock.

4. **Goal-instructions per the port-cleanup principle** (locked during PR07):
   - Goal-only — verbatim end-goal and quality requirements from predict-rlm.
   - **Strip** language-specific tool nouns (`pathlib`, `asyncio.gather`, `dspy.Image`, `predict()`) and "step 1/2/3/4" procedural framing.
   - **Add** an explicit thoroughness/care emphasis and an adversarial-completeness clause.
   - **No tree-shape hints, no methodology dictation, no answer hints.**
   - Documented per-benchmark in the comparison report's fidelity caveats section.

5. **Hand-authored side-by-side comparison reports** following `RESULTS.md` / `tasks/0N-*.md` style: verbatim emitted tree, per-leaf walkthrough citing the event trace, manual quality spot-check, explicit fidelity caveats, findings. Plus an aggregate `00_index.md`.

## User Stories

1. As a benchmark author, I want to run each predict-rlm comparison benchmark from a single REPL command, so that I can quickly iterate and re-run as needed.
2. As a benchmark author, I want pre-loaded image data URIs available to the model via blackboard keys with `:field-type :image`, so that the RLM researcher can do real vision sub-LLM calls without writing rendering code itself.
3. As a benchmark author, I want goal-only instructions (port-cleaned) — verbatim end-goal but no Python-specific methodology — so that the model can design from a clear goal without being misled by cross-runtime tool nouns.
4. As a benchmark author, I want a per-benchmark catalog of available code-node functions (with input/output shapes) injected into the researcher's prompt automatically, so that the model knows what tools it can invoke in its emitted tree.
5. As a benchmark author, I want every sub-LLM call's full inputs and outputs persisted to disk after the run, so that I can manually inspect each call when writing the comparison report.
6. As a benchmark author, I want the raw LLM prompts and responses captured via mulog alongside the result EDN, so that I can debug parser failures and analyze actual model behavior.
7. As a benchmark author, I want Phase 1 researcher iterations preserved in the result file even when no tree is emitted, so that direct-execution runs are reviewable too.
8. As a benchmark author, I want per-node token usage broken down in the result EDN, so that I can identify which sub-LLM calls were expensive and why.
9. As a benchmark author, I want a single-task-lock that prevents starting a second benchmark run while one is in flight, so that sheet state can't be corrupted by concurrent runs.
10. As a benchmark author, I want each per-benchmark component to declare its own opt-in dependencies (PDFBox, docjure), so that external consumers don't pay for benchmarks they don't use.
11. As a benchmark author, I want the model to design its own behavior tree from a goal-only instruction (never told what tree to emit), so that the comparison validates true adaptive generalization.
12. As a benchmark author, I want document_redaction to use vision LLM identification (matching predict-rlm methodology) plus a deterministic `apply-redactions` code node, so that we test adaptive RLM ability rather than redaction mechanics.
13. As a benchmark author, I want invoice_processing to produce both structured `InvoiceExtractionResult` and an `.xlsx` workbook via an opt-in docjure code node, so that we match predict-rlm's output schema and reported scope.
14. As a benchmark author, I want the image_analysis benchmark to send the image as proper `image_url` multimodal content (not as base64-text), so that tokens are billed correctly and vision quality matches the underlying model's actual capability.
15. As a benchmark author, I want each goal instruction to include an adversarial-completeness clause ("verify nothing was missed; if anything was, extract and include it"), so that the model has a quality bar to drive multi-pass or in-tree validation patterns without being told the methodology.
16. As a benchmark author, I want a Phase 0 re-run of `contract_comparison_validated` on the new branch baseline, so that I can confirm infrastructure works before adding new benchmarks.
17. As a benchmark author, I want each new benchmark built and validated in order of increasing complexity (image_analysis → document_redaction → invoice_processing), so that failures surface early on small-scope tasks.
18. As a benchmark author, I want a 4-step smoke protocol per benchmark (schema dry-run → real run at max-iter 5 → sanity inspection → reproducibility run).
19. As a reviewer, I want each comparison report to follow a consistent skeleton (task, inputs, methodology side-by-side, metrics table, emitted tree, sub-LLM walkthrough, hand-authored quality assessment, fidelity caveats, findings).
20. As a reviewer, I want methodology asymmetries between predict-rlm and our ports made explicit in each report (e.g., redaction text-output vs PDF-output, model-wrote-openpyxl vs deterministic-code-node, instruction-cleanup notes), so the comparison is honest.
21. As a reviewer, I want the aggregate index report to summarize token efficiency, quality, and tree-design observations across all 4 ports.
22. As a reviewer, I want predict-rlm's sample inputs (PDFs, images) used verbatim under MIT attribution, so our outputs can be compared directly against their published references.
23. As a reviewer, I want each report's "Sub-LLM call walkthrough" to cite specific entries from the event-trace and mulog trace files, so quality claims are reproducible.
24. As a reviewer, I want token-count claims sanity-checked against actual OpenRouter dashboard billing, because dscloj's reported `:usage` cannot be trusted for vision calls until the Phase-1 routing fix lands.
25. As an external ORC consumer, I want to pull only the per-benchmark components I care about, so my classpath stays lean.
26. As an external ORC consumer, I want components named with the `predict-rlm-` prefix, so they're clearly scoped to the comparison effort.
27. As a future ORC developer, I want the `:code` node type accepted by `emit-tree!`, so future benchmarks can ship custom pure-Clojure tools the model composes into trees.
28. As a future ORC developer, I want `:available-code-nodes` on the researcher node config to surface per-benchmark tool docs in the model's prompt without modifying core prompt code.
29. As a future ORC developer, I want unit tests for the deep modules (PDFBox wrapper, `apply-redactions`, `:code` DSL extension, `build-invoice-workbook`, Phase-1 vision routing), so regressions are caught before benchmark runs.
30. As a future ORC developer, I want this work to follow a pattern reusable for other bench suites (per-suite brick prefix, per-benchmark opt-in deps, runner under `development/bench/<suite>-comparison/`, the `:available-code-nodes` mechanism, the comparison report skeleton).
31. As the RLM model running a benchmark, I want my emitted tree to support `:code` nodes referencing pre-built Clojure functions, so I can compose deterministic tool calls into the tree alongside LLM sub-calls.
32. As the RLM model running a benchmark, I want a catalog of available tool functions visible in my prompt, so I know what code nodes I can emit and what their contracts are.
33. **(New, PR06/PR07 finding)** As the RLM model, I want the framework prompt to remind me that **chained sequential `(llm ...)` calls in Phase 1 should usually be `emit-tree!` instead** — and that `:code` nodes are available for deterministic transforms — so I don't accidentally bypass tree-level observability and the deterministic-transform affordance when the task would benefit from them.
34. **(New, PR06/PR07 finding)** As the RLM model, when an input is image-typed, I want the actual image content to be passed to my Phase-1 reasoning call so I can SEE the image when deciding whether to emit a tree and what shape it should take — not just see a base64 preview string. *Multimodal Phase-1 researcher; out of scope for this PRD; tracked as future work.*

## Implementation Decisions

### Core ORC extensions

**Done (PR02):** `:code` case in `rlm-dsl->orc-dsl` — accepts `[:code {:fn "ns/sym" :reads [...] :writes [...]}]`, translates to `(sheet/code :fn ... :reads ... :writes ...)`. Downstream Phase-2 executor's `:executor :code` with `:fn` symbol resolution via `ns-resolve` already supports this. Validates `:fn` is a non-empty string; throws clear ex-info on missing.

**Done (PR03):** `:available-code-nodes` plumbing — optional string field on the existing `:rlm` map config. Read via the same `(get rlm-config :available-code-nodes)` pattern as `:debug?`. Conditionally added to the dscloj module's `:inputs` and referenced in `:instructions`. No new schema/command surface required (uses the existing free-form `:rlm` map).

**Done (PR05/PR06):** `:rlm/researcher-iterations` event — new event type emitted from `execute-repl-researcher-node` in `todo_processors.clj` whenever iterations exist, independent of tree emission. Schema registered in `interface/schemas.clj` `events` map alongside the other `:rlm/*` types (`:rlm/tree-generated`, `:rlm/tree-executed`, `:rlm/tree-evaluated`). The runner queries this event for iterations, giving uniform capture across tree-emit and direct-execution runs.

**Pending (PR-Pre03):** Phase-1 sub-LLM image routing — `execute-llm-primitive` in `rlm_sandbox.clj` builds the dscloj module's `:inputs` without propagating `:field-type` from the blackboard schema. Result: image-typed inputs get sent as text content, character-counted into tokens (~480K vs ~1K real for a typical screenshot). Surfaced by PR07 token investigation; direct OpenRouter call shows the correct 1,126 tokens for the same image. Fix: look up the blackboard entry's schema for each `:reads` key and propagate `:type :image` (or other field-type) to the module input. ~5-10 LOC. Affects only direct-execution Phase-1; Phase-2 tree `:llm` nodes already route correctly via `executor.clj/build-module`.

**Pending (PR-Prompt):** RLM prompt policy update — `emit-tree!` is the **default** unless the task is trivially expressible. Added to `build-rlm-code-generation-module` in `executor.clj`. Specific guidance:

- **Default to `emit-tree!`.** For ANY non-trivial workflow — anything involving multiple steps, parallelism, deterministic transforms alongside LLM calls, large inputs, or quality/verification requirements — emit a tree.
- **The narrow exceptions** (when `emit-tree!` is overkill): when the preview shows the task is trivially small enough that a single `(llm ...)` call OR a single `:code` node would clearly suffice (e.g. a single short input + a single output with no intermediate processing).
- **If you find yourself writing 2+ sequential `(llm ...)` calls in Phase 1, that is a strong signal you should be using `emit-tree!`** with a `:sequence` of `:llm` nodes instead. The tree gives observability, per-node retry, deterministic-transform composition, and event-trace coverage that direct Phase-1 chaining does not.
- **For deterministic transforms** (counting, regex matching, deduplication, string replacement, file I/O), prefer `:code` with `{:fn "ns/sym"}` over `(llm ...)`. Letter-counting via LLM is hallucination-prone; a pure-Clojure function is definitively correct.

This is a policy shift, not just a hint — when the model emits direct-execution Phase-1 code for a complex task, it bypasses the tree-level observability and composition that ORC's RLM was designed for. The prompt should reflect that emit-tree! is the primary mode, not the optional escape hatch. ~20-25 lines of prompt text. No code structure change.

### Per-benchmark instruction principle (locked during PR07)

predict-rlm's signatures are Python procedural recipes. Keeping them verbatim leaks tool nouns and step-by-step framing into our model's prompt. The locked port principle:

- **Keep verbatim:** end-goal statement, output schema, quality requirements (e.g. "claims must cite source text")
- **Strip:** language-specific tool nouns and "step 1/2/3/4" procedural framing
- **Add:** explicit emphasis on thoroughness/care/completeness — **adversarial-completeness clause** (e.g. "verify nothing was missed; if anything was, extract and include it") — **no methodology or tree-shape dictation**
- **Do not add:** answer hints, tree-shape hints, methodology our system uses
- **Document every cleanup** in the comparison report's fidelity caveats so the comparison is honest

Per-benchmark adversarial-completeness clauses (concrete):
- **image_analysis:** "After producing your initial answer, adversarially verify completeness by re-examining the image to identify any visible text region your extraction may have missed. If significant text was missed, extract those regions and add to the count before producing the final answer."
- **document_redaction:** "After identifying targets, adversarially verify completeness by re-examining each page's text for any PII categories you may have missed. If any were missed, add them before applying redactions."
- **invoice_processing:** "After extracting invoice details, adversarially verify completeness — re-examine each invoice for any line items, totals, or vendor details your extraction may have missed."

The user query passed alongside the instruction stays verbatim from predict-rlm (their default query is user input, not framework instruction).

### New Polylith components (Pattern A naming: `predict-rlm-*`)

- **`predict-rlm-pdf`** (PR04 — done) — `render-pages-as-data-uris`, `extract-pages-as-text`. PDFBox-backed. 5 tests, 39 assertions GREEN.
- **`predict-rlm-image-tools`** (PR06 — done) — `image->data-uri` with MIME detection. 4 tests, 9 assertions GREEN.
- **`predict-rlm-redaction-tools`** (PR08 — pending) — task definition + `apply-redactions` deterministic transformation.
- **`predict-rlm-invoice-tools`** (PR10 — pending) — task definition + `build-invoice-workbook` (docjure).

### Runner & capture extensions (PR05 — done)

`development/bench/predict-rlm-comparison/runner.clj`:
- Public API mirrors `development/bench/runner.clj` (`start!`, `run!`, `stop!`, `generate-summary!`).
- Preserves `:iterations` (read from `:rlm/researcher-iterations` events), `:by-node` (from Phase 2 result), `:node-trace` (read from `:sheet/node-execution-completed` events filtered by run-start instant across all sheets — captures parent + ephemeral child sheets).
- Per-run mulog `.trace.edn` publisher (file format is line-delimited EDN; not JSON — the original PRD said `.jsonl` but mulog's `simple-file` publisher writes EDN).
- Single-task-lock atom; refuses concurrent runs.
- Supports `:input-schemas` (per-key Malli schema, defaults to `:string`) and `:input-loader` (0-arg fn returning `{key value}`) on the task definition for non-text inputs.

### Dual-model support for apples-to-apples (PR-Dual-Model — pending)

predict-rlm published numbers use `gpt-5.4` as main LM and `gpt-5.1` (= `openai/gpt-5.1-chat` on OpenRouter) as sub-LM. Both are available on OpenRouter; the gpt-5 family requires `max_tokens >= 16` (our defaults satisfy that).

To match predict-rlm's setup exactly without re-tuning per-benchmark, the comparison runner supports:
- `:model` — main LM used by the repl-researcher node (Phase-1 code-generation + any direct-execution sub-LLM calls).
- `:sub-model` (new, optional) — when set, after Phase-1 emits the canonical tree, the executor walks the tree and injects `:model sub-model` into each `:llm` node that lacks an explicit `:model`. The Phase-2 leaf executor's existing `get-provider-with-model` then routes those calls through the sub-model's litellm config.

Per-task tasks can override globally-configured `:model` and `:sub-model` (e.g. for vision benchmarks that need different models than text benchmarks).

**Tree injection mechanism:** after `(contains? @sandbox-vars :generated-tree)` triggers Phase 2 (executor.clj's emit-tree! handler), but before calling `tree-executor/execute-tree`, the executor walks the canonical-DSL form with `clojure.walk/postwalk`, looking for `(sheet/llm ...)` forms, and inserts `:model sub-model` into the keyword args when `:model` isn't already specified.

**Important:** this is a comparison-quality concern, not a framework limitation. The single-model setup is still the most common case and remains the default. Model-family choice is a comparison axis, not a controlled variable — `gemini-3-flash-preview` and `gpt-5.4/5.1-chat` are two data points on the cost/correctness frontier, neither is "the right answer."

See `docs/issues/predict-rlm/PR-Dual-Model-runner-support.md`.

### Branch mechanics (PR01 — done)

- Worktree from `main` at sibling path `../orc-predict-rlm`; branch `feature/predict-rlm-benchmarks`.
- **No cherry-pick.** Verified: `main` and local `feature/core-orc-upgrades` have **byte-identical** `executor.clj`, `rlm_sandbox.clj`, `rlm_dsl.clj`, `rlm_tree_executor.clj`, `todo_processors.clj`, `commands.clj`. Main has all required infra.

### Build & test order (revised after PR06/PR07 findings)

1. **Done:** PR01 Phase 0 baseline → PR02 `:code` DSL → PR03 `:available-code-nodes` → PR04 `predict-rlm-pdf` → PR05 comparison runner → PR06 image_analysis execution → PR07 image_analysis report.
2. **Now:** PR-Pre03 Phase-1 vision routing fix + PR-Prompt sequential-llm-chain guidance.
3. **Re-run** image_analysis with vision fix + adversarial-completeness clause; update PR07 report.
4. **Then:** PR08 document_redaction execution → PR09 report → PR10 invoice_processing execution → PR11 report → PR12 aggregate index.

### Per-benchmark smoke protocol (locked PR05)

1. Schema dry-run (no LLM): declare keys, load docs/images, verify schemas validate.
2. One real run with `:max-iterations 5`.
3. Sanity inspection: open EDN, eyeball outputs vs source.
4. One reproducibility run.

No artificial token cap. Single-task-lock prevents accidental concurrency. **Token-cost claims unreliable until PR-Pre03 ships.**

### Static assets (PR06 — partially done; PR08/PR10 will add the rest)

- `development/bench/predict-rlm-comparison/references/predict-rlm/LICENSE` — verbatim MIT license (done).
- `references/predict-rlm/<task>/signature.py.txt` — verbatim source for traceability (done for image_analysis).
- `references/predict-rlm/<task>/sample/input/*` — copied sample PDFs/images (done for image_analysis).
- `references/predict-rlm/<task>/sample/output/*` — copied for cross-reference (done for image_analysis).
- `ground-truth/<task>.edn` — parsed/structured ground truth (pending for document_redaction's 89 targets).

### Comparison report skeleton (locked PR07)

Each report under `development/bench/predict-rlm-comparison/reports/` includes:
- Headline + run-file references + test date
- Bottom-line summary (qualitative + quantitative)
- "What the task is" (verbatim instruction + verbatim query, with port-cleanup notes)
- "What the model designed" (verbatim emitted code OR direct execution decision)
- Side-by-side metrics table (LLM calls, wall clock, output/input tokens, letters/items extracted, cost)
- Per-leaf walkthrough citing `:node-trace`, `:iterations`, mulog trace
- Output quality verdict (verbatim model output + verbatim predict-rlm reference + manual spot-check)
- Fidelity caveats — explicit list of methodology asymmetries
- Findings — about ORC, about the comparison, about next steps
- Reproducibility — command, branch, git SHA, inputs

## Testing Decisions

**What makes a good test in this codebase:**
- Test external behavior, not implementation. Functions tested via input → output contracts.
- Prefer pure-data fixtures over LLM round-trips: tests should run fast and deterministically.
- Use `clj -M:poly test brick:<name> :dev` for opt-in bricks (the `:dev` flag is needed because per-benchmark bricks are only registered in the dev project).
- For DSL transformations, mirror `rlm_dsl_test.clj` — round-trip an S-expr, assert canonical-form output.

**Modules under test:**

1. **`:code` DSL extension** in `rlm-dsl->orc-dsl` — done. 3 deftests covering simple translation, composition inside sequence, missing-`:fn` error.
2. **`:available-code-nodes` plumbing** in `execute-repl-researcher-rlm` / `build-rlm-code-generation-module` — done. 2 deftests covering presence (catalog flows to dscloj) and absence (module shape unchanged).
3. **`predict-rlm-pdf`** — done. 5 deftests: fixtures loadable, page-count matches fixtures, render returns proper data-URI shape, decoded payload is valid PNG, DPI affects output size, extract-pages-as-text contains expected substrings.
4. **`predict-rlm-image-tools/image->data-uri`** — done. 4 deftests: PNG data URI prefix correct, decodes back to PNG signature, MIME detection (png/jpg/jpeg/webp/PNG case-insensitive), unsupported extension throws.
5. **Comparison runner's single-task-lock** — done. 4 deftests for acquire-when-free, reject-when-held, reusable-after-release, state-reports-holder.
6. **Phase-1 vision routing fix** (PR-Pre03 — pending) — unit test that builds a module via `execute-llm-primitive` with a `:reads` key whose blackboard schema carries `:field-type :image`, asserts the resulting dscloj-call's content payload uses the `image_url` content-block format (mock dscloj/predict and capture).
7. **`predict-rlm-redaction-tools/apply-redactions`** (PR08 — pending) — synthetic targets + per-page text, pure transformation. Idempotence, count consistency, no-target-leakage.
8. **`predict-rlm-invoice-tools/build-invoice-workbook`** (PR10 — pending) — synthetic invoice data → xlsx; re-open and verify structure.

**End-to-end validation (not unit tests):**
- Runner capture extensions — validated by live benchmark runs producing EDN+trace files with expected fields.
- `:available-code-nodes` plumbing — validated by benchmark runs that successfully invoke catalog'd code nodes by name.
- Goal-instruction discipline — checked by PR review against the cloned predict-rlm source.

## Out of Scope

- **Multimodal Phase-1 researcher** (the model sees the image when deciding whether/how to emit a tree). Documented as a future enhancement (user story #34). Out of scope here because it would require a deeper architectural change to `execute-repl-researcher-rlm`.
- **Cross-tree iteration** (model emits tree A, sees result, decides to emit tree B). Today the model can satisfy adversarial-validation requirements via in-tree validation (the `contract_comparison_validated` pattern). Cross-tree iteration is a deeper framework change; not needed for this work.
- **Path C — StaticMCPClient adapter for iterative-REPL mode comparison.** Deferred per Q2 outcome.
- **True PDF-native redaction parity.** We apply redactions to extracted text; predict-rlm modifies the PDF. Documented as a fidelity caveat.
- **LLM-as-judge.** Manual qualitative review is the judge for this work.
- **Porting `document_analysis` and `contract_comparison` to vision mode.** Existing 5-benchmark suite uses preparsed `.txt`.
- **New RLM primitives** beyond the surgical core extensions listed.
- **Multi-run statistical averaging.** Single-run snapshots match predict-rlm's published format.
- **Cross-branch coordination with `feature/core-orc-upgrades`.** Our work merges to `main` on its own timeline.
- **Vision model swap.** ~~`gemini-3-flash-preview` throughout.~~ **Reclassified as in-scope** as part of PR-Dual-Model (above). Predict-rlm's `gpt-5.4` (main) + `gpt-5.1-chat` (sub) are now first-class supported via the runner's `:model` + `:sub-model` config.
- **Multi-tree iteration** (model emits tree A, sees Phase-2 result, decides whether to emit tree B). Currently the executor returns after Phase 2 completes — the model only gets one shot at tree design. Multi-tree iteration would let the model adversarially review its own tree's output and emit a follow-up tree if completeness is insufficient. See `docs/issues/predict-rlm/PR-Multi-Tree-iteration.md` — captured for future, NOT in scope here.

## Further Notes

- **Sample-data licensing.** predict-rlm is MIT-licensed. Sample inputs + LICENSE copied verbatim into `references/predict-rlm/` with attribution.
- **Predict-rlm source location.** Cloned read-only at `/tmp/predict-rlm-read/predict-rlm/` for dev reference. Not committed.
- **Cost framing reset.** Pre-PR07 cost estimates were misleading by ~3 orders of magnitude due to the Phase-1 vision routing bug. Direct OpenRouter call with the same image: ~$0.0006. Reported cost in the EDN: undefined because litellm's normalized usage doesn't distinguish image_tokens. Action item: PR-Pre03 ships → re-run → re-verify cost via OpenRouter dashboard for the new timestamp.
- **Existing benchmark suite untouched.** Phase 0 re-run validates only that the baseline still works. The new runner lives alongside the existing one.
- **Manual-review tradition.** Quality assessment follows the depth of `tasks/04-contract-comparison-validated.md` — spot-check facts against source documents, inspect emitted tree shape, examine per-leaf-node IO from the event trace, capture qualitative observations metric tables can't.
- **Future-suite reusability.** This work's patterns (per-suite brick prefix, per-benchmark opt-in deps, runner under `development/bench/<suite>-comparison/`, the `:available-code-nodes` mechanism, the comparison report skeleton) are intentionally designed to support additional benchmark suites without re-litigating these decisions.
- **Implementation plan reference.** `/Users/darylroberts/.claude/plans/in-another-branch-we-glistening-kettle.md` captures the original Q1-Q13 grilling decision points.
- **Pre-existing bug tickets surfaced by this work:**
  - **PR-Pre01:** 12 failures in `repl_researcher_test.clj` on clean main (non-RLM iterative mode tests). Independent of this work; surfaced by PR02 brick test runs.
  - **PR-Pre02:** Dual-test-load isolation bug — `rlm_dsl_test` + `rlm_mode_test` both loaded into the same JVM causes the second's Integrant fixture build to fail. Each passes alone. Independent of this work; surfaced by PR03 regression check.
  - **PR-Pre03:** Phase-1 sub-LLM image routing bug (this PRD's #3). Surfaced by PR07 token investigation; will be fixed as part of this work.
- **RLM prompt parity verified.** `main`, local `feature/core-orc-upgrades`, and `remote/origin/feature/core-orc-upgrades` all have byte-identical RLM-related source files (`executor.clj`, `rlm_sandbox.clj`, `rlm_dsl.clj`, `rlm_tree_executor.clj`, `todo_processors.clj`, `commands.clj`). No prompt improvements have been made elsewhere that we need to backport. PR-Prompt's additions (sequential-llm-chain guidance + `:code`-node hint) will be the first such improvements.
