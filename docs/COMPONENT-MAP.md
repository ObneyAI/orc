# ORC Component Map

Use `projects/orc-service` for workflow execution. The other packages add judges, optimization, ontology, retrieval, or MCP sheet generation. Package identifiers appear under `projects/`. Physical brick names such as `evaluation-service` appear under `components/` and in Polylith test selectors. Consumers continue to require public namespaces such as `ai.obney.orc.evaluation.interface`. [Installation and package coordinates](PACKAGES.md)

## Opt-in layer table

| Layer | Capability | Physical bricks | Model dependencies |
|---|---|---|---|
| 0 | Behavior-tree DSL, workflow execution, restart recovery, event-sourced state and bounded durable-event export | `orc-service` | Engine includes the SIO-backed `llm` adapter. No DJL. |
| 0a | Store canonical execution values outside events | `file-store` plus `file-store-local` or `file-store-s3` | None. The umbrella includes both backends. |
| 1 | LLM judges for grounding, reasoning, completeness and instruction-following | `evaluation-service` | Judges run with the engine and a configured model provider. Explicitly attached judges also work when ontology is absent. |
| 2 | Forward traces to Langfuse | `langfuse` | None. The engine owns bounded queuing, retries, acknowledgements and shutdown. The adapter uses clj-http and data.json. |
| 3 | GEPA Pareto-frontier instruction optimization | `gepa-service`, `evaluation-service` | Uses the LLM adapter and judges. No DJL. |
| 4 | Dense embeddings and semantic concept search | `ontology-service` | DJL PyTorch and Hugging Face tokenizers. |
| 5 | ColBERT late-interaction retrieval and reranking | `colbert-service` | DJL OnnxRuntime and Hugging Face tokenizers run the `answerai-colbert-small-v1` checkpoint on the JVM. |
| 6 | Event-sourced concept graph, manual graph lifecycle and evolutionary ingestion from CSV, JSON, SQL, text or N-Triples | `ontology-service` | The ontology package includes DJL. Manual graph operations and deterministic N-Triples ingestion can run without loading an embedding model. |
| 7 | Self-improving loop, including execution classification, pattern evolution and behavior minting | `orc-service`, `evaluation-service`, `ontology-service` and `colbert-service` for the full retrieval stack | Ontology and ColBERT supply the model-backed retrieval signals. [Self-improving loop](SELF-IMPROVING-LOOP.md) |
| 8 | Generate behavior trees from MCP tool schemas | `mcp-sheet-builder` | Uses the engine and LLM adapter. No DJL. |

## Pull in only what you need

| Package root | Included capabilities |
|---|---|
| `projects/orc-service` | Engine, LLM adapter, Langfuse adapter, the file-store protocol and integration registry |
| `projects/orc-evaluation` | Engine and judges |
| `projects/orc-gepa` | Engine, judges and GEPA optimization |
| `projects/orc-ontology` | Engine, concept graph, embeddings and evolutionary builder |
| `projects/orc-colbert` | ColBERT and the integration registry. This package runs independently and can add a third retrieval signal to ontology. |
| `projects/orc-mcp-sheet-builder` | Engine and MCP sheet generation |
| `projects/orc` | All of the standalone packages above, including local and S3 file-store backends |

For example, to add judges to an application, pin the evaluation package in its `deps.edn`.

```clojure
obneyai/orc-evaluation {:git/url "https://github.com/ObneyAI/orc.git"
                        :git/sha "<sha>"
                        :deps/root "projects/orc-evaluation"}
```

## Component dependency graph

The table lists the direct, runtime ORC dependencies of the package bricks. The package manifests also supply Grain. Test aliases can bring in additional bricks for integration tests. [Package manifests](../projects)

| Brick | Direct ORC dependencies |
|---|---|
| `integrations`, `llm`, `langfuse`, `file-store` | None. The LLM and Langfuse adapters declare their external libraries in their own manifests. |
| `file-store-local`, `file-store-s3` | `file-store`. The S3 adapter also declares the Cognitect AWS API, endpoints and S3 libraries. |
| `orc-service` | `file-store`, `integrations`, `langfuse`, `llm` |
| `evaluation-service` | `integrations`, `llm`, `orc-service` |
| `gepa-service` | `evaluation-service`, `integrations`, `llm`, `orc-service` |
| `ontology-service` | `integrations`, `orc-service` |
| `colbert-service` | `integrations` |
| `mcp-sheet-builder` | `llm`, `orc-service` |

### Optional integrations

The integration registry loads a known provider interface on first use if it is on the classpath. That interface publishes named Vars. For example, the engine can obtain ontology classification and description operations, while ontology can obtain ColBERT indexing, search, batch search, reranking and normalization operations. A separately installed `agent-browser` brick can register browser tools for the researcher sandbox. The registry also lets providers bind historical qualified function names to implementations so persisted sheets can replay across internal code moves. [Integration registry](../components/integrations/src/ai/obney/orc/integrations/interface.clj)

Evaluation’s default-judge gate checks for the registered `:ontology/living-description-enabled` read model and projects its value for the caller. The evaluation processor can load the ontology provider when present. Applications using only explicitly attached judges can install the standalone evaluation package. [Evaluation processors](../components/evaluation-service/src/ai/obney/orc/evaluation/core/todo_processors.clj)

Both retrieval encoders run on the JVM. Ontology uses DJL PyTorch for dense embeddings. ColBERT uses DJL OnnxRuntime for token-level MaxSim scoring. The first use of ColBERT resolves the model from its local cache or downloads it. Set `-Dcolbert.model.path` to use a pre-provisioned checkpoint. [ColBERT public interface](../components/colbert-service/src/ai/obney/orc/colbert/interface.clj)

## Implementation notes

### Ontology scope in hybrid retrieval

The embedding signal receives `:ontology-id` and `:ontology-ids`. The graph signal expands seed URIs over the default static graph, and the ColBERT signal searches the selected index. For retrieval confined to one ontology by ID, use `semantic-search-concepts` or select only the embedding signal and pass the ID. If adding ColBERT, select an index built for the same scope. [Ontology retrieval](ONTOLOGY.md#known-issues)

### Judge rubrics are data

Each judge grades against a rubric value that defines the criterion, reviewer stance, score bands and feedback requirement. Built-in judges supply defaults. Set `:rubric` when declaring a judge, or use `:sheet/revise-judge` to create a new judge revision. [Evaluation component](EVALUATION-COMPONENT.md)

### Tree fingerprints group structural variants

Fingerprinting replaces instruction strings and function bodies with stable placeholders before hashing the tree. Instruction variants with the same structure therefore share a rolling-metrics bucket. The ontology `:tree-class` axis carries instruction-aware classifications. [Fingerprint implementation](../components/orc-service/src/ai/obney/orc/orc_service/core/logic.clj)

## Further reading

- [Getting started](GETTING-STARTED.md) covers installation and a first workflow.
- [Judge architecture](JUDGE-ARCHITECTURE.md) describes judge types, custom judges and composite scoring.
- [ORC principles](ORC-PRINCIPLES.md) describes event sourcing and workflow composition.
