# predict-rlm Comparison Benchmark Suite

**This is ORC's workbench, built on behavior trees.** When we apply Recursive Language Model strategies into ORC's decomposition space — behavior trees the model emits via `emit-tree!`, with `:llm`, `:code`, `:map-each`, `:parallel`, `:sequence`, and `:final` as the building blocks — these are the results on a published, real-world benchmark suite.

[predict-rlm](https://github.com/Trampoline-AI/predict-rlm) is cutting-edge research from Trampoline-AI demonstrating the Recursive Language Model concept end-to-end on real documents, with fully open source code, sample inputs, and published outputs we can read directly. Their decomposition primitive is Python + DSPy `predict()` + `asyncio.gather()`. ORC's is the behavior tree. Same RLM idea, different toolchain — and that toolchain difference is what these reports surface.

The framing here isn't competition. predict-rlm's published examples gave us a concrete, high-quality reference point to ask "how does this RLM strategy look when expressed as a behavior tree, on the same input documents, with the same verbatim instructions, with the same models?" The reports describe what ORC's tree-emitting researcher does on each task and what the resulting outputs look like. predict-rlm's published numbers appear alongside ORC's as the reference baseline.

If you're exploring ORC for the first time, the reports are useful concrete examples of what RLM-on-behavior-trees looks like. If you're coming from predict-rlm, this is a parallel implementation showing the same RLM idea expressed in a different decomposition space.

## What's compared

All 5 of predict-rlm's example benchmarks ported apples-to-apples (same models, same verbatim instructions, same source documents).

| Benchmark | Source task | Clean report | Reference EDN |
|---|---|---|---|
| `image_analysis` | [predict-rlm/examples/image_analysis](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples/image_analysis) | [`reports/01_image_analysis.md`](reports/01_image_analysis.md) | [`results/image-analysis_2026-05-20_150618.edn`](results/image-analysis_2026-05-20_150618.edn) |
| `document_redaction` | [predict-rlm/examples/document_redaction](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples/document_redaction) | [`reports/02_document_redaction.md`](reports/02_document_redaction.md) | [`results/document-redaction_2026-05-20_165215.edn`](results/document-redaction_2026-05-20_165215.edn) |
| `invoice_processing` | [predict-rlm/examples/invoice_processing](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples/invoice_processing) | [`reports/03_invoice_processing.md`](reports/03_invoice_processing.md) | [`results/invoice-processing_2026-05-21_145054.edn`](results/invoice-processing_2026-05-21_145054.edn) |
| `document_analysis` | [predict-rlm/examples/document_analysis](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples/document_analysis) | [`reports/04_document_analysis.md`](reports/04_document_analysis.md) | [`results/document-analysis-predict-rlm_2026-05-21_162203.edn`](results/document-analysis-predict-rlm_2026-05-21_162203.edn) |
| `contract_comparison` | [predict-rlm/examples/contract_comparison](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples/contract_comparison) | [`reports/05_contract_comparison.md`](reports/05_contract_comparison.md) | [`results/contract-comparison-predict-rlm_2026-05-21_164244.edn`](results/contract-comparison-predict-rlm_2026-05-21_164244.edn) |

**Cross-benchmark synthesis report:** [`reports/00_index.md`](reports/00_index.md) — the aggregate story across all 5.

## Headline results (from committed reference EDNs)

| Benchmark | ORC | predict-rlm published (verbatim from their `sample/output/output.md`) |
|---|---|---|
| image_analysis | 9,560 tokens / 26.9s / 22-of-24 letters match predict-rlm exactly | 26,547 tokens / ~60s / $0.08 |
| document_redaction | 92 redactions / 28.9s / 52,120 tokens / 100% strict-PII recall (84/84) | 89 redactions / 87s / 65,847 tokens / $0.18 / 89% strict-PII recall (75/84) |
| invoice_processing | 16,573 tokens / 28.1s / 2 invoices extracted, $34,804.30 total, 11/12 line items match reference xlsx | output.md doesn't publish token/cost stats for this benchmark |
| document_analysis | 212,066 tokens / 2.1 min / full `:summary` + `:key-dates` + `:entities` populated / zero hallucinations on 8 spot-checks | 194,072 tokens / ~4 min / $0.52 |
| contract_comparison | 94,258 tokens / 50s / same headline change identified + 2 additional material findings | 173,057 tokens / ~5.5 min / $0.71 |

These are two real implementations of the same tasks, on the same documents, with the same model setup. The per-benchmark clean reports describe what ORC's tree-emitting researcher did on each task. predict-rlm's published numbers appear as the reference point for comparison, not as a target. See [`reports/00_index.md`](reports/00_index.md) for the cross-benchmark write-up.

## Prerequisites

- **`OPENROUTER_API_KEY`** environment variable. Each benchmark bills ~$0.05-1.50 per run via OpenRouter (gpt-5.4 + gpt-5.1-chat).
- Clojure CLI tools installed (`clj`).
- See [`docs/RLM-GUIDE.md`](../../../docs/RLM-GUIDE.md) for the framework feature set the benchmarks exercise.

## How to run

### Quick start — one Clojure -main per benchmark

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."

# From the repo root, run any individual benchmark:
clj -M:dev:test -m predict-rlm-comparison.run.image-analysis
clj -M:dev:test -m predict-rlm-comparison.run.document-redaction
clj -M:dev:test -m predict-rlm-comparison.run.invoice-processing
clj -M:dev:test -m predict-rlm-comparison.run.contract-comparison
clj -M:dev:test -m predict-rlm-comparison.run.document-analysis

# Or run all 5 in sequence (fastest-first, ~6-10 min total):
clj -M:dev:test -m predict-rlm-comparison.run.all
```

Each `run.*` namespace has a `(defn -main [& args] ...)` that:
- Verifies `OPENROUTER_API_KEY` is set; fails fast with helpful error if not
- Boots the runner, executes the task, tears down cleanly
- Prints a structured status block (STATUS / DURATION / TOTAL TOKENS)
- Writes the result EDN under `results/<benchmark>_<timestamp>.edn` + `.trace.edn`
- Exits non-zero on failure (composes cleanly with CI / shell pipelines)

The shared bootstrap (env-check + status formatting) lives in
[`run/_common.clj`](../predict_rlm_comparison/run/_common.clj). The full suite
runner is [`run/all.clj`](../predict_rlm_comparison/run/all.clj).

### REPL invocation (equivalent, for interactive use)

If you prefer to drive from the REPL or want to inspect intermediate state
without re-loading the JVM:

```clojure
;; Single benchmark
(require '[predict-rlm-comparison.tasks.image-analysis :as t])
(require '[predict-rlm-comparison.runner :as r])
(r/start!) (r/run! t/task) (r/stop!)

;; Or via the aggregator for the full suite + summary table
(require '[predict-rlm-comparison.all :as bench])
(bench/start!) (bench/run-all!) (bench/summary!) (bench/stop!)
```

Substitute the task namespace for any of the other 4: `predict-rlm-comparison.tasks.document-redaction`, `...invoice-processing`, `...contract-comparison`, `...document-analysis`.

### Models

All 5 tasks declare `:model "openai/gpt-5.4"` + `:sub-model "openai/gpt-5.1-chat"`. The framework injects the sub-model into every `:llm` node that doesn't specify its own model, so Phase-2 sub-LLM calls hit gpt-5.1-chat while Phase-1 (tree design) runs through gpt-5.4. This matches predict-rlm's published config.

## Expected runtime + cost

| Benchmark | Wall clock | Cost (rough) |
|---|---|---|
| image_analysis | 25-35s | ~$0.05 |
| document_redaction | 30-60s | ~$0.10-0.15 |
| invoice_processing | 25-40s | ~$0.05-0.10 |
| contract_comparison | 50-90s | ~$0.15-0.25 |
| document_analysis | 3-5 min | ~$1.00-1.50 (most expensive — 136-page RFP) |
| **All 5 (`run.all`)** | **6-10 min total** | **~$1.30-2.10 total** |

These vary with model latency and how quickly the model converges on a workable tree.

## Where outputs go

Each `(r/run! ...)` call writes two files under `results/`:

```
results/<benchmark>_<timestamp>.edn        # full result map (status, outputs, usage, node-trace, ...)
results/<benchmark>_<timestamp>.trace.edn  # mulog events emitted during the run
```

The `.gitignore` in `results/` excludes all `.edn` files EXCEPT the 4 headline files cited in the reports. Your fresh runs land as untracked files for local inspection.

## Inspecting your output

Each run writes a result EDN under `results/<benchmark>_<timestamp>.edn` containing `:status`, `:outputs`, `:duration-ms`, `:usage`, and the full `:node-trace`. The output shape per benchmark:

| Benchmark | Key output fields | What to compare against |
|---|---|---|
| image_analysis | `:outputs :answer` (markdown string with letter counts) | predict-rlm's published table in `references/predict-rlm/image_analysis/sample/output/output.md` |
| document_redaction | `:outputs :total-redactions`, `:outputs :targets-applied`, `:outputs :redacted-text-per-page` | predict-rlm's published 89 redactions in `references/predict-rlm/document_redaction/sample/output/output.md` |
| invoice_processing | `:outputs :invoices`, `:outputs :total-amount`, `:outputs :workbook-path` | predict-rlm's reference workbook at `references/predict-rlm/invoice_processing/sample/output/invoice_extraction.xlsx` (open both side-by-side) |
| document_analysis | `:outputs :key-dates`, `:outputs :key-entities`, `:outputs :report` | predict-rlm's published table in `references/predict-rlm/document_analysis/sample/output/report.md` |
| contract_comparison | `:outputs :summary`, `:outputs :section-diffs`, `:outputs :key-differences`, `:outputs :report` | predict-rlm's published `references/predict-rlm/contract_comparison/sample/output/comparison-report.md` |

The 5 clean reports under `reports/` walk through the headline-run output field-by-field with side-by-side comparisons against the predict-rlm reference for each benchmark — read those for the detailed comparison rubric.

## Each task file is a worked example

The 5 task files under `tasks/` are deliberately structured to be standalone learning examples of how to compose an ORC RLM benchmark with the framework's full feature set. Read any one of them top-to-bottom:

| File | Demonstrates |
|---|---|
| [`tasks/image_analysis.clj`](../../bench/predict_rlm_comparison/tasks/image_analysis.clj) | Vision input via `[:string {:field-type :image}]` schema; single-output `:answer` writes; minimal task shape |
| [`tasks/document_redaction.clj`](../../bench/predict_rlm_comparison/tasks/document_redaction.clj) | Multi-page text + image inputs; `:available-code-nodes` advertised via instruction; deterministic `:code` reference; constraint-based composition guidance |
| [`tasks/invoice_processing.clj`](../../bench/predict_rlm_comparison/tasks/invoice_processing.clj) | Multi-document parallel vision extraction; `:output-schemas` for structured Invoice maps; pre-built `:code` for xlsx generation; combined `:invoices :total-amount :summary :workbook-path` writes |
| [`tasks/contract_comparison.clj`](../../bench/predict_rlm_comparison/tasks/contract_comparison.clj) | Cross-document comparison; structured `ComparisonResult` schema; `:section-diffs` with `:significance` enum; deterministic-code final synthesis pattern |
| [`tasks/document_analysis.clj`](../../bench/predict_rlm_comparison/tasks/document_analysis.clj) | Large-document text extraction; nested `:key-dates` + `:key-entities` schemas; adversarial-completeness verification clause |

Each file's top-of-file docstring shows:
- What predict-rlm task it ports (verbatim instruction sourcing)
- Fidelity caveats specific to that task
- The exact REPL incantation to run it
- The standalone Clojure `-main` runner namespace under `run/`

The `:task` map at the bottom of each file is the complete shape: name, slug, model + sub-model, instruction, input-schemas, output-schemas, input-loader, writes, evaluation-criteria, and `:predict-rlm-reported` metadata. **Copy any of these as a template for building your own ORC RLM benchmark.**

For broader framework reference, see [`docs/RLM-GUIDE.md`](../../../docs/RLM-GUIDE.md) — covers the full `repl-researcher` node config, `:rlm` mode options, Phase-2 tree DSL primitives, observability events, and recursive-mode opt-in.

## Methodology + fidelity caveats

For full methodology, fidelity caveats, and the per-benchmark deep analysis, read the clean reports:

- [`reports/00_index.md`](reports/00_index.md) — cross-benchmark synthesis (start here)
- [`reports/01_image_analysis.md`](reports/01_image_analysis.md)
- [`reports/02_document_redaction.md`](reports/02_document_redaction.md)
- [`reports/03_invoice_processing.md`](reports/03_invoice_processing.md)
- [`reports/04_document_analysis.md`](reports/04_document_analysis.md)
- [`reports/05_contract_comparison.md`](reports/05_contract_comparison.md)

Key choices documented across those reports:
- All predict-rlm numbers sourced from their committed `sample/output/output.md` and `sample/output/report.md` files (authoritative reference; their READMEs may have different numbers from separate runs)
- Instruction port-cleaning: verbatim goal + adversarial-completeness clause; methodology-specific Python tool nouns stripped per the "verbatim goal, not verbatim methodology" principle
- document_redaction uses text-mode substring replacement (predict-rlm uses pymupdf's PDF-native `apply_redactions()`). The RLM decision-making is what's compared, not PDF-rewriting mechanics.
- document_analysis + contract_comparison use text extraction (PDFBox); predict-rlm uses vision. For text-heavy documents both pipelines see the same content; framework support for vision-mode `:field-type :image` propagation through `:map-each` is a future upgrade.

## References

- **predict-rlm**: https://github.com/Trampoline-AI/predict-rlm (MIT license — `references/predict-rlm/LICENSE`)
- **ORC RLM Guide**: [`docs/RLM-GUIDE.md`](../../../docs/RLM-GUIDE.md) — full reference for the `repl-researcher` node, `:rlm` mode options, Phase-2 tree DSL primitives, observability events

## Adding a new benchmark

Pattern:

1. New brick under `components/predict-rlm-<task>-tools/` with `:available-code-nodes` catalog if the task needs pre-built `:code` fns.
2. New task file under `development/bench/predict_rlm_comparison/tasks/<task>.clj` with verbatim instruction from predict-rlm's `signature.py` + adversarial-completeness clause + `:model :sub-model` for apples-to-apples.
3. Reference assets under `references/predict-rlm/<task>/` — at minimum `signature.py.txt`, `sample/input/*`, `sample/output/output.md`.
4. Live run → headline result EDN → clean report at `reports/0X_<task>.md` mirroring 01/02 structure.
5. Update this README's "What's compared" + "Headline results" tables.
