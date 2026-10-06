# Decision evaluation: native decision model vs conversational model

Runs the same `sheet/llm-decision` workflows on a native decision model (`typesafe/jev-1.13`
through OpenRouter decisions, selected per node with `:model "jev"`) and on the pinned
conversational model (`google/gemini-3.6-flash`), through the real ORC execution path.

- `cases.edn` — 60 labelled support-routing messages (36 clear, 24 hard: indirect, mixed-signal,
  sarcastic, reference-without-history, very short) over 6 described routes, and 20 labelled
  true/false claims. Labels are hand-written; dispute any of them before trusting a number.
- `run.clj` — executes every case on both models and writes `results.edn` (answer, confidence or
  probability, distribution, latency, usage).
- `analyse.clj` — accuracy (all / clear / hard / claims), misses, latency, tokens, cost, and the
  accuracy-vs-coverage effect of a confidence floor for the decision model.

```sh
ORC_OPENROUTER_E2E_TESTS=true clojure -J-Deval.dir=development/bench/decision-eval \
  -M:dev:test development/bench/decision-eval/run.clj
clojure -J-Deval.dir=development/bench/decision-eval -M -e \
  '(load-file "development/bench/decision-eval/analyse.clj")'
```

## Results (2026-10-06, two runs, identical outcomes)

| | jev-1.13 | gemini-3.6-flash |
|---|---|---|
| routing, all 60 | 95% (57/60) | 100% (60/60) |
| routing, clear 36 | 97% (35/36) | 100% |
| routing, hard 24 | 92% (22/24) | 100% |
| true/false claims, 20 | 100% | 100% |
| median latency per decision | ~0.29 s | ~2.1 s |
| reported cost, 60 routes | $0.0020 | not reported on the chat path |

The decision model's three misses (the same three cases in both runs) all carried confidence at or
below 0.68. With `:min-confidence 0.7` it answered 93% of cases (56/60) with 100% accuracy on
those, sending the rest to the abstention option. The conversational model reports no confidence,
so a floor on it always abstains.

Limits: one task family, hand-labelled, small n. The conversational model saturates this set, so
it does not separate the models on difficulty; harder or domain-specific sets are needed for that.
No calibration claim is made about the decision model's confidence beyond this set.
