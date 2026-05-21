# predict-rlm Comparison Benchmark Suite

Apples-to-apples comparison of ORC's RLM (Repl Researcher with `emit-tree!`) against the published [predict-rlm](https://github.com/Trampoline-AI/predict-rlm) reference implementation. Same models (`openai/gpt-5.4` main + `openai/gpt-5.1-chat` sub), same verbatim instructions, same source documents.

## What's compared

| Benchmark | Source task | Clean report | Reference EDN |
|---|---|---|---|
| `image_analysis` | [predict-rlm/examples/image_analysis](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples/image_analysis) | [`reports/01_image_analysis.md`](reports/01_image_analysis.md) | [`results/image-analysis_2026-05-20_150618.edn`](results/image-analysis_2026-05-20_150618.edn) |
| `document_redaction` | [predict-rlm/examples/document_redaction](https://github.com/Trampoline-AI/predict-rlm/tree/main/examples/document_redaction) | [`reports/02_document_redaction.md`](reports/02_document_redaction.md) | [`results/document-redaction_2026-05-20_165215.edn`](results/document-redaction_2026-05-20_165215.edn) |

## Headline results (from committed reference EDNs)

| Benchmark | ORC outcome | predict-rlm (verbatim from their `sample/output/output.md`) |
|---|---|---|
| image_analysis | 9,560 tokens / 26.9s / 22-of-24 letters match predict-rlm EXACTLY | 26,547 tokens / ~60s / $0.08 |
| document_redaction | 92 redactions / 28.9s / 52,120 tokens / **100% strict-PII recall** (84/84) | 89 redactions / 87s (1m 27s) / 65,847 tokens / $0.18 / **89% strict-PII recall** (75/84) |

ORC ~2.8× cheaper on tokens for image_analysis; ~1.26× cheaper for document_redaction; ~2-4× faster on wall clock. Higher strict-recall on document_redaction (caught the transit number, Business Number, and date-range PII predict-rlm missed).

## Prerequisites

- **`OPENROUTER_API_KEY`** environment variable. Both benchmarks bill ~$0.05-0.15 per run via OpenRouter (gpt-5.4 + gpt-5.1-chat).
- Clojure CLI tools installed (`clj`).
- The PR-Framework upgrades (U4-U13) merged on `main`. See [`docs/RLM-GUIDE.md`](../../../docs/RLM-GUIDE.md) for the framework feature set.

## How to run

From the repo root:

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."

# image_analysis
clj -M:dev:test -e '
(require (quote [predict-rlm-comparison.tasks.image-analysis :as t]))
(require (quote [predict-rlm-comparison.runner :as r]))
(r/start!)
(r/run! t/task)
(r/stop!)'

# document_redaction
clj -M:dev:test -e '
(require (quote [predict-rlm-comparison.tasks.document-redaction :as t]))
(require (quote [predict-rlm-comparison.runner :as r]))
(r/start!)
(r/run! t/task)
(r/stop!)'
```

Both tasks declare `:model "openai/gpt-5.4"` + `:sub-model "openai/gpt-5.1-chat"`. The framework's PR-Dual-Model machinery walks the emit-tree! tree and injects the sub-model into every `:llm` node that doesn't specify its own model, so all Phase-2 sub-LLM calls hit gpt-5.1-chat while Phase-1 (tree design) runs through gpt-5.4.

## Expected runtime + cost

| Benchmark | Wall clock | Cost (rough) |
|---|---|---|
| image_analysis | 25-35s | ~$0.05 |
| document_redaction | 60-90s | ~$0.10-0.15 |

These vary with model latency and how quickly the model converges on a workable tree. Both should complete in under 2 minutes.

## Where outputs go

Each `(r/run! ...)` call writes two files under `results/`:

```
results/<benchmark>_<timestamp>.edn        # full result map (status, outputs, usage, node-trace, ...)
results/<benchmark>_<timestamp>.trace.edn  # mulog events emitted during the run
```

The `.gitignore` in `results/` excludes all `.edn` files EXCEPT the 4 headline files cited in the reports. Your fresh runs land as untracked files for local inspection.

## Comparing your run to the committed reference

After running a benchmark:

1. **Check `:status :success`** — the runner prints this at the end. Anything else is a real failure to investigate.
2. **For image_analysis**: open the result EDN's `:outputs :answer` field. Compare letter counts against `references/predict-rlm/image_analysis/sample/output/output.md`. Expect ≥18-of-26 letters to match exactly (model run-to-run variance accounts for the rest).
3. **For document_redaction**: check `:outputs :total-redactions` is in the 80-95 range and `:outputs :targets-applied` is a vector of real PII items (names, SINs, addresses, dates).

If your numbers fall well outside these ranges (e.g. 0 redactions, status `:failure`), that's a **bug**, not run-to-run variance. The framework has been verified to reliably produce results in these ranges; investigate root cause rather than re-running.

## Methodology + fidelity caveats

For full methodology, fidelity caveats, and the per-benchmark deep analysis, read the clean reports:

- [`reports/01_image_analysis.md`](reports/01_image_analysis.md)
- [`reports/02_document_redaction.md`](reports/02_document_redaction.md)

Key choices documented in those reports:
- All predict-rlm numbers sourced from their committed `sample/output/output.md` (authoritative reference; their README has different numbers from a separate run)
- Instruction port-cleaning: verbatim goal + adversarial-completeness clause; methodology-specific Python tool nouns stripped per the "verbatim goal, not verbatim methodology" principle
- document_redaction uses text-mode substring replacement (predict-rlm uses pymupdf's PDF-native `apply_redactions()`). The RLM decision-making is what's compared, not PDF-rewriting mechanics.

## References

- **predict-rlm**: https://github.com/Trampoline-AI/predict-rlm (MIT license — `references/predict-rlm/LICENSE`)
- **ORC RLM Guide**: [`docs/RLM-GUIDE.md`](../../../docs/RLM-GUIDE.md)
- **PR-Framework**: 10 framework upgrades enabling these benchmarks (U4-U13, see PR description)

## Adding a new benchmark

Pattern:

1. New brick under `components/predict-rlm-<task>-tools/` with `:available-code-nodes` catalog if the task needs pre-built `:code` fns.
2. New task file under `development/bench/predict_rlm_comparison/tasks/<task>.clj` with verbatim instruction from predict-rlm's `signature.py` + adversarial-completeness clause + `:model :sub-model` for apples-to-apples.
3. Reference assets under `references/predict-rlm/<task>/` — at minimum `signature.py.txt`, `sample/input/*`, `sample/output/output.md`.
4. Live run → headline result EDN → clean report at `reports/0X_<task>.md` mirroring 01/02 structure.
5. Update this README's "What's compared" + "Headline results" tables.
