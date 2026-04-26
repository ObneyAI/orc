# Orc Examples — Predict-RLM Ports

Direct ports of every example from
[Trampoline-AI/predict-rlm](https://github.com/Trampoline-AI/predict-rlm)/examples/
into orc, in two styles each:

- **Style A — Pipeline** (`pipeline.clj`): each phase is an explicit node
  (`code` / `llm` / `map-each`). Per-node tracing, judges, retries; every
  instruction is GEPA-optimizable independently. Showcases orc's structural
  advantages (event-sourced trace, versioning, multi-tenancy).
- **Style B — RLM-faithful** (`agentic.clj`): a single `repl-researcher`
  with `:rlm true`. The LLM writes Clojure that calls SCI-exposed
  `pdf/`, `xlsx/`, `docx/` tools and host-backed `(predict …)` /
  `(predict-all …)` / `(final! …)`. The closest 1:1 analog to predict-rlm.

Both styles share the same `schemas.clj` (Malli ports of the original
Pydantic models) and the same `service.clj` execute helpers.

| Example | Original | Inputs | Outputs | Skills used |
|---|---|---|---|---|
| `image_analysis`     | predict-rlm/examples/image_analysis    | images + query | text answer | none |
| `document_analysis`  | predict-rlm/examples/document_analysis | PDFs + criteria | structured analysis + DOCX | pdf, docx |
| `invoice_processing` | predict-rlm/examples/invoice_processing | PDF invoices | structured invoices + XLSX | pdf, xlsx |
| `contract_comparison`| predict-rlm/examples/contract_comparison | 2 PDF contracts | structured diff | pdf |
| `document_redaction` | predict-rlm/examples/document_redaction | PDFs + criteria | redacted PDFs | pdf (+ redaction.md) |

## Running

Each example has a `run.clj` with REPL-runnable comment blocks. From the
repo root:

```bash
export DSCLOJ_PROVIDER=openrouter
export OPENROUTER_API_KEY=…
clj -M:dev -m nrepl.cmdline --port 7888
```

then in the REPL:

```clojure
(require '[ai.obney.orc.examples.image-analysis.run :as run])
(run/run-pipeline)   ;; Style A
(run/run-agentic)    ;; Style B
```

Sample inputs live in each example's `sample/input/` (copied from the
predict-rlm originals so each port runs self-contained). Outputs go to
`sample/output/{run-id}/` and are gitignored.

## Cross-style comparison story

Once Style A and Style B are run on the same input, both produce a full
event-sourced trace in the orc event store. This is the live demonstration
of the comparison-doc claim that orc treats trace as data, not as artifact:

```clojure
(require '[ai.obney.grain.event-store-v3.interface :as es])
(vec (es/read event-store {:tenant-id …
                           :tags #{[:sheet pipeline-sheet-id]}}))
;; Style A: one event per node + judge events
(vec (es/read event-store {:tenant-id …
                           :tags #{[:sheet agentic-sheet-id]}}))
;; Style B: one event per repl-researcher iteration + bounded :rlm metadata
;; (root usage, subcall usage, predict call count, hash/preview of context)
```

predict-rlm's `RunTrace` is in-memory and per-run; orc's traces are
durable, queryable, projectable, and survive across runs.
