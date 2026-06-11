# PR02 — `:code` node support in `emit-tree!` DSL

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

Extend `rlm-dsl->orc-dsl` in `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_dsl.clj` to translate `:code` nodes emitted by the RLM researcher. The DSL currently supports `:sequence`, `:llm`, `:map-each`, `:chunk-document`, `:aggregate`, `:parallel`, `:final` — `:code` is the missing piece that lets model-emitted trees reference custom pre-built Clojure functions by fully-qualified symbol.

Input shape (what the model writes via `emit-tree!`):

```
[:code {:fn "ai.obney.orc.example/some-fn" :reads [:input-key] :writes [:output-key]}]
```

Output (canonical ORC DSL passed to the executor):

```
(sheet/code :fn "ai.obney.orc.example/some-fn" :reads [:input-key] :writes [:output-key])
```

The downstream Phase-2 tree executor already resolves `:fn` symbols via `require` + `ns-resolve` (see `core/executor.clj` lines ~453-479), so no executor change is needed.

Also document the new node-type in `core/executor.clj` `build-rlm-code-generation-module`'s emit-tree! example block (lines ~1190-1196 in the available-node-types list) so the model is taught that `:code` exists.

Add unit tests to `rlm_dsl_test.clj` mirroring the existing test style for other node types.

## Acceptance criteria

- [ ] `(rlm-dsl->orc-dsl [:code {:fn "ns/sym" :reads [:a] :writes [:b]}])` returns `(sheet/code :fn "ns/sym" :reads [:a] :writes [:b])`
- [ ] Round-trip test inside an emit-tree! S-expr: a `[:sequence [:code {...}] [:final {...}]]` form translates to a canonical form executable by the existing tree executor
- [ ] Edge cases tested: missing `:fn` throws `ex-info` with a clear message; missing `:reads` and missing `:writes` produce sensible defaults; multiple writes preserved
- [ ] `build-rlm-code-generation-module`'s prompt lists `:code` in the available node-types section with a one-line example
- [ ] `clj -M:poly test brick:orc-service` passes including new tests
- [ ] Existing tests untouched and still passing
- [ ] No regression in the existing benchmark suite — re-run `contract_comparison_validated` after the change; tree shape should still be in the same family

## Blocked by

PR01
