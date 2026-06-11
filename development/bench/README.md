# ORC RLM Generalization Benchmark Suite

A set of five end-to-end benchmarks that prove ORC RLM (the Repl Researcher + `emit-tree!` workflow) genuinely **adapts behavior tree design to task type** rather than pattern-matching a single template.

**TL;DR:** Across 5 fundamentally different tasks, the model designs 4 distinct tree patterns + 1 "no tree" decision, all from goal-only instructions, with **zero hallucinations** across 37 spot-checked facts.

📖 **Start here:** [`RESULTS.md`](RESULTS.md) — the headline story
📂 **Per-task details:** [`tasks/`](tasks/)
🌳 **Tree comparison:** [`appendix/tree-comparison.md`](appendix/tree-comparison.md)

---

## What the suite tests

Five task types, deliberately chosen to be structurally different:

| Task | Doc Size | Pattern | Why it's distinct |
|------|---------|---------|-------------------|
| `:document-analysis` | 280K (1 doc) | Extraction | Pull facts (dates, entities, summary) |
| `:risk-analysis` | 280K (1 doc) | Analytical reasoning | Classify, assess severity, recommend |
| `:contract-comparison` | 105K (2 docs) | Cross-document diff | Multi-doc reasoning |
| `:contract-comparison-validated` | 105K (2 docs) | Diff + adversarial validation | Quality requirement → cross-reference |
| `:legal-issue-detection` | 7K (1 doc) | Analytical reasoning (small) | Tests "no tree" decision |

## How to run

### 1. Set the API key

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
```

(Configured for `google/gemini-3-flash-preview` via OpenRouter. To change, edit `config` in `development/bench/runner.clj`.)

### 2. Run a single task from a Clojure REPL

```clojure
(require '[risk-analysis :as t])   ; or any per-task namespace
(require '[runner])
(runner/start!)
(runner/run! t/task)
(runner/stop!)
```

Or from the command line:

```bash
clj -M:dev -e '
(require (quote [risk-analysis :as t]))
(require (quote [runner]))
(runner/start!)
(runner/run! t/task)
(runner/stop!)'
```

### 3. Run all 5 tasks

```clojure
(require '[all :as bench])
(bench/start!)
(bench/run-all!)
(bench/summary!)
(bench/stop!)
```

This runs each task in sequence and saves results to `development/bench/generalization-results/`.

### 4. Generate a summary table from saved runs

```clojure
(require '[all :as bench])
(bench/summary!)
```

## Available tasks

Each lives in its own namespace + file:

| Namespace | File | Task var |
|-----------|------|----------|
| `document-analysis`            | `document_analysis.clj`            | `document-analysis/task` |
| `risk-analysis`                | `risk_analysis.clj`                | `risk-analysis/task` |
| `contract-comparison`          | `contract_comparison.clj`          | `contract-comparison/task` |
| `contract-comparison-validated`| `contract_comparison_validated.clj`| `contract-comparison-validated/task` |
| `legal-issue-detection`        | `legal_issue_detection.clj`        | `legal-issue-detection/task` |

## Output

Each run produces an EDN file with:
- `:task`, `:task-name`, `:pattern`
- `:duration-ms`, `:status`, `:usage` (tokens)
- `:documents` (sizes), `:total-chars`
- `:outputs` — the final results AND `:generated-tree-raw` (the tree the model designed)
- `:evaluation-criteria` — manual quality checklist

EDN files are saved to `development/bench/generalization-results/`.

## Documents used

Located in `development/bench/documents/`:

| File | Size | Content |
|------|------|---------|
| `yyj_rfp.txt` | 280K | Victoria Airport Authority Parking Management RFP (real) |
| `contract_v2.txt` | 56K | Ontario microFIT Contract Version 2.0 (real) |
| `contract_v3.txt` | 49K | Ontario microFIT Contract Version 3.1.1 (real) |
| `employment_agreement.txt` | 7K | Employment agreement (real, names redacted) |
| `invoice_acme.txt` | 800 | Sample invoice |
| `invoice_globaltech.txt` | 900 | Sample invoice |

## Reports

- **[`RESULTS.md`](RESULTS.md)** — the headline story, **start here**
- **[`tasks/`](tasks/)** — per-task deep dive (current best run only):
  - [`01-document-analysis.md`](tasks/01-document-analysis.md) — extraction on 280K
  - [`02-risk-analysis.md`](tasks/02-risk-analysis.md) — analytical reasoning on 280K
  - [`03-contract-comparison.md`](tasks/03-contract-comparison.md) — multi-doc diff
  - [`04-contract-comparison-validated.md`](tasks/04-contract-comparison-validated.md) — diff with adversarial validation requirement
  - [`05-legal-issue-detection.md`](tasks/05-legal-issue-detection.md) — small-doc analysis (model chose no tree)
- **[`appendix/tree-comparison.md`](appendix/tree-comparison.md)** — side-by-side trees

## Performance characteristics

| Task | Duration | Tokens |
|------|----------|--------|
| `:legal-issue-detection` | 8.8s | 5,706 |
| `:contract-comparison-validated` | 26.7s | 84,337 |
| `:contract-comparison` | 35.6s | 64,336 |
| `:risk-analysis` | 64.8s | 170,336 |
| `:document-analysis` | 126.3s | 212,066 |

## Configuration

`runner/config` lives at the top of `development/bench/runner.clj`:

```clojure
(def config
  {:model "google/gemini-3-flash-preview"
   :timeout-ms 600000  ;; 10 min wall clock
   :documents-dir "development/bench/documents"
   :results-dir "development/bench/generalization-results"})
```

## Caveats

- Requires a working `OPENROUTER_API_KEY` with credit on `google/gemini-3-flash-preview`.
- Model output is non-deterministic — exact tree structures may vary between runs (timings stay within 1.5x).
- Token usage varies more than wall-clock time; budget at least ~$0.10–$0.50 per full suite run depending on model selection.

