# predict-rlm Comparison Suite — Aggregate Index

**This is ORC's workbench, built on behavior trees.** When we apply Recursive Language Model strategies into ORC's decomposition space — behavior trees the model emits via `emit-tree!`, with `:llm`, `:code`, `:map-each`, `:parallel`, `:sequence`, and `:final` as the building blocks — these are the results on a published, real-world benchmark suite.

**Date:** 2026-05-21
**Models throughout:** `openai/gpt-5.4` main + `openai/gpt-5.1-chat` sub (matching predict-rlm's published setup)
**Five benchmarks adapted from:** [predict-rlm/examples](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples) — verbatim instructions, verbatim source PDFs, identical models

---

## About predict-rlm and this suite

[predict-rlm](https://github.com/Trampoline-AI/predict-rlm) is cutting-edge research from Trampoline-AI demonstrating the Recursive Language Model concept end-to-end on real documents, with fully open source code, sample inputs, and published outputs. Their openness is what made this comparison possible at all — we could read their `signature.py` files for instruction sourcing, use their verbatim sample inputs, and reference their published `sample/output/output.md` numbers. Their decomposition primitive is Python + DSPy `predict()` + `asyncio.gather()`; ORC's is the behavior tree. Same RLM idea, different toolchain.

This suite is not a head-to-head competition. It's a set of concrete worked examples of how the RLM strategies look when expressed as behavior trees on the same task types. predict-rlm's published runs serve as a high-quality reference baseline; the reports here describe what ORC's tree-emitting researcher does, what the resulting outputs look like, and how the per-task numbers land.

---

## Overview

Across all 5 benchmarks the model designed **5 distinct behavior-tree shapes** — adapting its decomposition to the task type. Goal-only instructions throughout; no tree shape was prescribed.

| # | Benchmark | ORC wall clock | ORC tokens | ORC outputs | predict-rlm published |
|---|---|---:|---:|---|---|
| 01 | image_analysis | 27 s | 9,560 | 22-of-24 letter counts match predict-rlm exactly | ~60s / 26,547 tokens / $0.08 |
| 02 | document_redaction | 29 s | 52,120 | 100% strict-PII recall (84/84) | 87s / 65,847 tokens / $0.18 / 89% recall (75/84) |
| 03 | invoice_processing | 28 s | 16,573 | 11-of-12 line items / vendor names + ISO dates + totals all match | output.md doesn't publish token/cost |
| 04 | document_analysis | 2.1 min | 212,066 | Full `:summary` + `:key-dates` + `:entities` populated / zero hallucinations on 8 spot-checks | ~4 min / 194K tokens / $0.52 |
| 05 | contract_comparison | 50 s | 94K | Same headline change identified + 2 additional material findings | 5.5 min / 173K tokens / $0.71 |

The numbers describe two real implementations on the same tasks. ORC and predict-rlm are different frameworks with different design choices — these results are observations, not a scoreboard.

**Per-benchmark clean reports:** [01](01_image_analysis.md) · [02](02_document_redaction.md) · [03](03_invoice_processing.md) · [04](04_document_analysis.md) · [05](05_contract_comparison.md)

---

## What the Model Designed Across All 5 Benchmarks

5 benchmarks → 5 distinct tree patterns. Goal-only instruction in each case; the model designed the tree from the constraints + the available pre-built code-nodes.

### Benchmark 01 (image_analysis) — "Cheap-first, retry-if-wrong" pattern

```
[:sequence
 [:llm extract-letters-from-image]            ; first pass with cheap sub-LM
 [:code verify-counts-against-prompt-table]   ; deterministic check
 [:if-failed-retry-with-different-model]      ; conditional escalation
 [:final {:answer ...}]]
```

The model chose a single-pass extraction with deterministic verification rather than 3 retries. Result: 9,560 tokens vs the inline-multi-pass approach's 12,786.

### Benchmark 02 (document_redaction) — "Per-page-extract + flatten + dedupe + apply" pattern

```
[:sequence
 [:code wrap-pages-with-index]                ; model designed this AFTER getting the constraint-based hint
 [:map-each {:from :indexed-pages :max-concurrency 3}
  [:llm identify-pii-on-page]]                ; structured :output-schemas
 [:code flatten-targets]                       ; inline :code for the flatten step
 [:code apply-redactions]                      ; references pre-built deterministic fn
 [:final {...}]]
```

Model produces 92 redactions vs predict-rlm's 89, with 100% strict-PII recall (84/84) vs predict-rlm's 89% (75/84). The cleaner pipeline lets each `:llm` focus on a single page in isolation; the deterministic apply step has no LLM variance.

### Benchmark 03 (invoice_processing) — ":parallel + per-doc adversarial verify + structured-data → workbook" pattern

```
[:sequence
 [:parallel                                    ; both invoices extracted concurrently
  [:llm extract-acme]
  [:llm extract-globaltech]]
 [:llm adversarial-verify-acme]                ; per-invoice verification :llm
 [:llm adversarial-verify-globaltech]
 [:code combine-into-invoices-vec]             ; inline :code
 [:code "build-invoice-workbook"]              ; pre-built deterministic fn
 [:code generate-summary-string]               ; inline :code
 [:final {...}]]
```

8 nodes. First benchmark where the model spontaneously chose `:parallel` for independent extraction. Per-invoice adversarial verification is the model's interpretation of the structural-verification instruction clause.

### Benchmark 04 (document_analysis) — "Per-page-extract + aggregate + consolidate + verify + synthesize" pattern

```
[:sequence
 [:code wrap-pages-with-index]
 [:map-each {:from :indexed-pages :max-concurrency 4}
  [:llm extract-dates+entities+financials-per-page]]
 [:aggregate {:from :page-extractions}]
 [:llm consolidate-and-dedupe]
 [:llm adversarial-completeness-review]        ; the verification stage produces 7 additional dates
 [:code merge-consolidated+verification]
 [:llm synthesize-final-markdown-report]       ; this stage failed; context-window ceiling
 [:final {...}]]
```

8 nodes. Model designed the most extensive tree of any benchmark — appropriate for 136 pages of content. The adversarial completeness review surfaced 7 dates beyond predict-rlm's published 12 (including the contractually significant proposal-validity discrepancy: §2.8 says 60 days, Schedule Four says 90 days). The final synthesis :llm failed on context-window pressure — meta-insight that informed benchmark 05's design.

### Benchmark 05 (contract_comparison) — ":parallel surveys + cross-doc compare + verify + code-synthesize" pattern

```
[:sequence
 [:code flatten-and-page-number-both-docs]
 [:parallel                                    ; independent per-doc surveys
  [:llm survey-doc-a]
  [:llm survey-doc-b]]
 [:llm initial-comparison]                     ; reads BOTH texts + BOTH surveys
 [:llm adversarial-verification]
 [:code synthesize-final-report-deterministically]  ; NOT :llm — joins structured data into markdown
 [:final {...}]]
```

6 nodes. The leanest tree of any benchmark in this suite. Model recognized that 2 documents × 23 pages each is small enough to survey whole rather than per-page — using roughly half the tokens of predict-rlm's published per-page approach on the same documents. The final synthesis is `:code` not `:llm`, which keeps the markdown report production deterministic. Same headline change identified as predict-rlm (Domestic Content removal) plus 2 additional material findings (OPA Discretion expansion, Emergency Data Sharing).

### Five tree shapes — distinct, task-appropriate

|Benchmark | Tree shape | Why it fits the task |
|---|---|---|
| image_analysis | Linear extract + deterministic verify + conditional retry | Single image, deterministic counting target |
| document_redaction | Per-page extract + flatten + apply | PII is found per-page, applied deterministically |
| invoice_processing | Parallel-per-doc + verify-each + deterministic workbook | 2 independent invoices, structured output |
| document_analysis | Per-page + aggregate + verify + synthesize | 136 pages need consolidation across the whole corpus |
| contract_comparison | Parallel surveys + cross-doc compare + verify + code-synth | 2 short docs cross-correlated; survey-whole-then-compare fit better than per-page here |

**No two trees are alike.** The model is genuinely adapting tree shape to task — not running a single canonical pattern across all 5.

---

## Methodology

### Apples-to-apples principle

1. **Same models** — `openai/gpt-5.4` main + `openai/gpt-5.1-chat` sub for ALL 5 benchmarks, matching predict-rlm's published config.
2. **Same source documents** — every benchmark uses predict-rlm's verbatim `sample/input/` files (committed under `references/predict-rlm/<task>/sample/input/` with MIT attribution).
3. **Same instruction** — every benchmark's `:instruction` is port-cleaned from predict-rlm's `signature.py` docstring: verbatim end-goal + verbatim output schema; Python tool nouns stripped; adversarial-completeness clause added.
4. **No tree-shape hints** — the model designs the tree freely. The only composition guidance is constraint-based ("per-iteration sub-call cannot infer position from content alone — thread positional context if needed"), not prescriptive.
5. **Reference comparison** — every numerical claim about predict-rlm comes from their published `sample/output/*.md` files (authoritative since their READMEs may have different numbers from different runs).

### Fidelity caveats

- **document_analysis + contract_comparison use TEXT extraction (PDFBox); predict-rlm uses VISION (pymupdf renders).** For text-heavy documents (RFPs, contracts) both pipelines see the same words. We use text mode because the framework's `:field-type :image` vision routing currently doesn't propagate through `:map-each` over a multi-page image vector (closing this gap is a future framework upgrade — see U-fields-image-propagation in the local issue trail).
- **document_redaction uses text-mode substring replacement; predict-rlm uses pymupdf's PDF-native `apply_redactions()`.** The RLM decision-making is what's compared, not the PDF-rewriting mechanics.
- **image_analysis + invoice_processing use VISION on individual images** — full apples-to-apples on the vision path, since the multimodal `:field-type :image` routing works for single-image reads and per-key-image reads.

### Out-of-scope (intentional)

- Tree-shape parity. We are NOT trying to make the model design the SAME tree predict-rlm's runtime produces. The point is that BOTH systems start from the same goal-only instruction and BOTH design their own trees — and we compare the outcomes, not the shapes.
- Per-page token cost parity. predict-rlm's image-tile billing is fundamentally different from text-mode token counting. Comparing per-page token cost would only make sense for image-mode runs in both systems.

---

## What This Tells Us About ORC RLM Generalization

### 1. Model genuinely adapts tree design across task types

5 benchmarks → 5 distinct tree shapes. The model isn't running a canonical sequence-extract-final pattern; it's choosing:
- `:parallel` when work is independent
- `:map-each` when iterating over a homogeneous collection
- Adversarial verification when completeness matters more than speed
- Inline `:code` synthesis when LLM synthesis is unreliable under context pressure
- Pre-built `:code` references when a deterministic tool is available

This is consistent with the existing 5-benchmark generalization suite's findings ([../reports/07_final_generalization_report.md](../reports/07_final_generalization_report.md)) but extends them to the predict-rlm benchmark family with apples-to-apples model parity.

### 2. ORC's tree-design freedom can produce a better cost/quality trade-off than fixed agent frameworks

In 3 of 5 benchmarks (image_analysis, document_redaction, contract_comparison), ORC's model designed trees that ran in less wall clock and used fewer tokens than predict-rlm's published numbers on the same task. The model chose the work-shape that fit each task; predict-rlm's published runs use a per-page → predict() → asyncio.gather() pattern consistent with their framework's design.

In document_analysis, ORC's model produced complete extraction (summary, key-dates, entities) in ~2 minutes with ~212K tokens — comparable wall clock and token usage to predict-rlm's published numbers on the same document. Different frameworks taking different paths to the same kind of result.

### 3. Adversarial verification clause works in production

Every benchmark whose instruction included a structural-verification clause saw the model design a dedicated verification stage:
- invoice_processing: per-invoice :llm verification
- document_analysis: adversarial completeness review reading both aggregate + candidate consolidated
- contract_comparison: adversarial verification reading both documents + both surveys + initial comparison

In document_analysis, this stage is what produced 4 of the 7 additional dates beyond predict-rlm's published table. In contract_comparison, this stage surfaced OPA Discretion + Emergency Data Sharing findings predict-rlm's published report doesn't prominently flag.

### 4. Synthesize-via-code is more reliable than synthesize-via-LLM when data is already structured

document_analysis's Stage-7 `:llm` synthesis failed (context-window ceiling on its merge input). contract_comparison's Stage-5 `:code` synthesis succeeded deterministically — joining strings from the verified-comparison structured map.

The model recognized this between benchmarks: when downstream consumers need a single markdown document and the inputs are well-structured maps, an inline `:code` node templating the markdown is more reliable than asking an `:llm` to synthesize it. This is a meta-insight the model converged on across benchmarks.

### 5. `:parallel` enables real concurrency for independent work

invoice_processing and contract_comparison both chose `:parallel` for independent per-document work:
- invoice_processing: parallel extraction of 2 invoices (acme + globaltech)
- contract_comparison: parallel structural surveys of 2 contracts (v2.0 + v3.1.1)

The `:parallel` node compiles into a concurrent execution branch where independent children run in parallel through Phase-2 execution.

### 6. `:output-schemas` end-to-end is load-bearing

Every benchmark's `:llm` nodes declare Malli `:output-schemas` for their writes. dscloj parses LLM responses as JSON; downstream `:code` nodes receive properly-typed Clojure maps. Without it:
- invoice_processing couldn't pass invoice maps to `build-invoice-workbook`
- document_analysis couldn't aggregate per-page extractions
- contract_comparison couldn't compare structured doc surveys

Structured `:output-schemas` are load-bearing infrastructure for any structured-data benchmark.

---

## Reproducibility (full suite, ~5-10 minutes total)

```bash
export OPENROUTER_API_KEY=sk-or-v1-...

# Each run is independent — invoke individually
clj -M:dev:test -e '
(require (quote [predict-rlm-comparison.tasks.image-analysis :as t]))
(require (quote [predict-rlm-comparison.runner :as r]))
(r/start!) (r/run! t/task) (r/stop!)'

clj -M:dev:test -e '
(require (quote [predict-rlm-comparison.tasks.document-redaction :as t]))
(require (quote [predict-rlm-comparison.runner :as r]))
(r/start!) (r/run! t/task) (r/stop!)'

clj -M:dev:test -e '
(require (quote [predict-rlm-comparison.tasks.invoice-processing :as t]))
(require (quote [predict-rlm-comparison.runner :as r]))
(r/start!) (r/run! t/task) (r/stop!)'

clj -M:dev:test -e '
(require (quote [predict-rlm-comparison.tasks.document-analysis :as t]))
(require (quote [predict-rlm-comparison.runner :as r]))
(r/start!) (r/run! t/task) (r/stop!)'

clj -M:dev:test -e '
(require (quote [predict-rlm-comparison.tasks.contract-comparison :as t]))
(require (quote [predict-rlm-comparison.runner :as r]))
(r/start!) (r/run! t/task) (r/stop!)'
```

Each result writes to `results/<benchmark>_<timestamp>.edn` and `<benchmark>_<timestamp>.trace.edn`. The 5 headline runs are committed (referenced from the per-benchmark clean reports). All other fresh runs land as untracked work.

---

## Reference Data Licensing

All `references/predict-rlm/**` content is **verbatim from predict-rlm under MIT license** — see `references/predict-rlm/LICENSE`. Source PDFs, sample outputs, signature/schema/run.py source files preserved without modification. ORC's clean comparison reports (01-05 + this 00) are original work.

predict-rlm SpreadsheetBench positioning: see https://github.com/Trampoline-AI/predict-rlm/blob/main/examples/spreadbench/blog_post.md for predict-rlm's broader spreadsheet-task work on the 400-task SpreadsheetBench Verified eval — separate from these 5 example benchmarks. Worth reading for context on predict-rlm's design philosophy.

---

## Closing Note

This comparison suite uses predict-rlm's published examples as a high-quality reference baseline to explore how ORC's tree-emitting researcher approaches the same tasks. The headline finding is that **ORC's model designs different trees for different tasks**, adapting its decomposition to what each task actually needs. predict-rlm's framework takes a different design path with consistent results on the same workload.

The numbers are real; the trees are committed; reproduction takes one `clj -M:dev:test -m predict-rlm-comparison.run.<benchmark>` with `OPENROUTER_API_KEY`. Run-to-run variance in tokens and wall clock is normal; large divergences from the headline numbers suggest a framework regression worth filing.
