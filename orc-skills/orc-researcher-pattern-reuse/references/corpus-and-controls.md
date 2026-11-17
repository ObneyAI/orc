# Corpus setup and controls

## Version boundary

The controls below were inspected at ORC commit `1851212396cced2fc4533fda30b824bd4b691390`. Recheck the installed revision before relying on a processor gate or option. The live paired probe established retrieval, semantic reranking, injection and recursive execution on that revision. Reflection and harvest details are source-verified here, not demonstrated by that pair.

## Explicit catalogs and contexts

The researcher can receive a string catalog in `:rlm {:available-code-nodes "..."}`. This becomes a researcher input and can inform generated `[:code {:fn "registered-function-name" ...}]` choices. It describes host functions; it does not register or load them. Load/register the actual functions through the runtime's code-node mechanism before use, and declare compatible reads/writes/schemas. Keep the catalog's documented contract equal to the real function's `{:inputs ...}` contract. Bound tools use their separately described tool configuration and argument schemas. Describe purpose, valid arguments, outputs, effects and success/failure behavior; a useful description tells the model when the capability fits, not merely its name.

Researcher tree-detail/trajectory/failure and node-output/input-profile primitives inspect the current campaign's recorded tree results. They do not automatically search every past event in the application. Expose older material intentionally through a catalog, explicit context or ontology retrieval.

Automatic classification runs only when the node has a map RLM configuration with `:auto-classify? true` and no existing `:context`. A supplied context suppresses that automatic path. Leave the node context absent when testing R-Inject; do not accidentally provide an empty context map. Explicit context and automatic classification are different choices.

## Establish a searchable corpus

When extending the lean runtime with ontology/ColBERT, avoid competing transitive local roots from separately downloaded Git libraries. At the inspected pin, replace the lean `obneyai/orc-service` project entry with the canonical component coordinate and use these entries at the same SHA, retaining the Grain runtime/store and canonical schema registry dependencies:

```clojure
orc/orc-service
{:git/url "https://github.com/ObneyAI/orc.git"
 :git/sha "1851212396cced2fc4533fda30b824bd4b691390"
 :deps/root "components/orc-service"}
orc/ontology
{:git/url "https://github.com/ObneyAI/orc.git"
 :git/sha "1851212396cced2fc4533fda30b824bd4b691390"
 :deps/root "components/ontology"}
orc/colbert
{:git/url "https://github.com/ObneyAI/orc.git"
 :git/sha "1851212396cced2fc4533fda30b824bd4b691390"
 :deps/root "components/colbert"}
```

Run `clojure -Stree` and require the interfaces before starting the runtime. A public standalone probe reproduced `No known ancestor relationship between local versions for orc/orc-service` with separate noncanonical project/component coordinates; this canonical direct component graph resolves. The error concerns library identity and competing checkout roots, not behavior-tree execution. Inspect the installed manifests rather than replacing this with private local paths.

Use the ontology public interface and application-owned Grain runtime:

1. Load ontology and the current ColBERT component with their dependencies. Ensure the event store, tenant, cache and processors belong to the intended application or isolated experiment.
2. For a fresh store that needs shipped examples, inspect `ontology/baseline-seeds` and call `ontology/seed-baseline-corpus!`. For an existing corpus, inspect it first and reuse its lineage. Re-seeding emits more description events; it is not an event-free initialization.
3. Start the intended Grain processors with the full runtime context, then call `ontology/bootstrap-reindex!`.
4. Check command outcomes, index existence, document IDs/metadata and public `ontology/search-descriptions` hits in the relevant granularities before a measured researcher run.

Cold description search can return `[]` when the description index is absent. Search does not synchronously bootstrap the index. Missing optional ColBERT can make reindexing a no-op. Neither establishes a ready R-Inject corpus.

Current ColBERT uses DJL ONNX Runtime and a HuggingFace tokenizer in the JVM, with `answerdotai/answerai-colbert-small-v1`. Verify the actual loaded checkpoint and encoder; an event's legacy model-name label can disagree with the current encoder. `-Dcolbert.model.path` selects checkpoint files; `-Dcolbert.index.root` selects index storage. If other DJL engines are on the classpath, use the documented `-Dai.djl.default_engine=OnnxRuntime` choice when this deployment intends ONNX. Observe materialization/startup before debugging semantic retrieval.

Index refresh currently has description-event and elapsed-time thresholds. The elapsed-time condition is checked when the refresh path is invoked; do not assume an independent timer has run. Verify a changed body's version is actually retrievable after refreshing.

For a small frozen experiment, public description commands plus a public `:colbert/create-index` command can establish a known indexed corpus. Mark authored seeds as authored fixtures. Record index creation and document hashes. Deliberately exclude corpus writers/refreshers from that isolated runtime's processor topology if the corpus must remain fixed. Do not change processor registrations in a shared running application to obtain a control.

Distinguish index files from event-sourced readiness. The low-level `colbert/create-index!` artifact operation does not substitute for the Grain `:colbert/create-index` command: files can exist while the public index projection/search path has no registered candidates. Verify the command result, its projected index identity, and nonempty expected public searches before spending model calls on classification. Empty retrieval is a setup finding until that boundary is checked.

### Register the full body on the axis the renderer reads

For custom authored guidance at this pin, use a full ordinary Grain command envelope (`:command/name`, `:command/id`, `:command/timestamp`) with these public command bodies:

| Advice axis | Public description command | Body and public readback |
| --- | --- | --- |
| Structural tree class | `:ontology/record-tree-class-description` | `{:target-id <uuid> :body <description-body>}`; verify `ontology/get-description` with target type `:tree-class`. |
| Behavioral subtree | `:ontology/record-tree-description` | `{:target-id <uuid> :body (assoc description-body :scope :behavioral-subtree)}`; verify `ontology/get-description` with target type `:tree-fingerprint`. |

Use the installed description schema for the complete body. Preserve the authored source, stable target and body version in an experiment manifest; omit `:model-provenance` for human-authored material rather than inventing a model trace. When real model provenance exists, its event schema requires actual trace/model/usage metadata. Index document target IDs and granularities must match these bodies.

A real trial indexed and selected a behavioral summary but wrote its full body through the structural command. Behavioral injection then had a nil body version: retrieval and classification succeeded, but the full conditional advice was not available. A separate public command/readback correction verifies the proper axis; it cannot retroactively establish exposure in the old trial. Require non-nil expected body/version before model spend, then prove the actual serialized request contains the intended conditional text. A correct index summary alone does not prove full-body registration or exposure.

## Classification is semantic selection and can write

The automatic path invokes structural classification and behavioral classification. Structural shape, domain family and behavioral function are separate axes; shared shape or a shared model-produced label does not establish identical task meaning.

Source defaults are structural threshold `0.7`, behavioral threshold `0.6` and behavioral top-N `5`. Node overrides are `:auto-classify-threshold` and `:auto-classify-behavioral-threshold`. Observe the candidates, reranker reasoning, uncertainty/deferral and selected identities. Retrieval scores are not task accuracy scores.

Classification may record assignment, match or mint a domain family and record capture-related evidence or deferral. It is not a pure read. The current assignment path needs event-store, tenant, sheet and tick context for durable provenance. Inspect actual emitted commands/events rather than inferring assignment from a debug message.

Verify the model and budget for both reranking and design. A node's chat options do not necessarily apply to every library-generated role or emitted child node. The observed pair used an explicit application policy assigning omitted chat max-tokens and temperature before public client serialization. That is a consumer policy, not an ORC compiler repair. Use model-supported parameters, capture each role's actual options and count retries as real requests.

## Injection and visibility

R-Inject can supply ranked structural and behavioral patterns, relevant domain-family substance, guarded strengths/weaknesses, worked source and node-type guidance. It offers adopt/adapt/specialize/mint choices; the task contract still governs selection.

Set runtime context `:injection-capture-rendered-block? true` when exact content is needed. Rendering dispatches the injection command and records `:intervention/injection-recorded`; public projection `:sheet/injection-records` is partitioned by sheet. The row includes host sheet/tick/node identity, arm, candidate IDs/versions, model metadata, rendered size, hash and optional rendered block. Recording is best effort when command context is missing, so assert the row exists.

At this pin, that projection keeps the latest render for `[sheet-id tick-id node-id]`. A recursive researcher can render on multiple iterations of the same node: two canonical injection events can yield one projected row. Use canonical injection event history plus captured request/iteration attribution to audit every exposure. Do not equate projected row count with model call count or compare the latest-row projection directly to an undeduplicated event vector.

The hash covers the rendered block. It does not prove that the complete request contained it. Capture the serialized provider request after SIO/client assembly and inspect its decoded messages, instruction, input values/schema descriptions, output schema and tools. Compare the exact recorded block to the actual sent content, including how often it appears. The inspected live request placed the same block in both generated module instructions and the task input; rendered character count was not total placement cost. Preserve raw request and response/usage without credentials. Do not substitute a reconstructed prompt or a hand-prepended string for this proof.

RLM root prompts can contain environment summaries rather than full source values. Inspect the actual inputs-info, available inspection primitives and authoritative write contract. In the paired probe, the short goal value and described output contract were visible to the root, while the full passages and packet description reached the emitted extraction LLM child. Do not claim every blackboard value/description was placed in the root prompt merely because it was declared.

Per-run whole-block control:

```clojure
(assoc ctx :injection-capture-rendered-block? true
           :injection-holdout {:enabled? true
                               :fraction 1.0
                               :baseline-policy-id "frozen-corpus-no-injection"})
```

Use fraction `0.0` for the treatment's context. The control still performs selection, but omits guidance. Keep models, task, node instruction, corpus, catalog and limits equal. These endpoint fractions make deterministic arms; they are not random assignment. A probabilistic fraction additionally needs recorded propensity and allocation verification.

The separate `:claim-holdout` context control suppresses claim-derived weakness content while retaining other guidance. It tests a narrower intervention. Whole-block holdout takes precedence, so do not combine the controls when trying to isolate claim effects. Whole-block injection changes prompt length and several guidance components together.

Pass context controls through the actual Grain poller/execution runtime. A dynamic binding in the caller's REPL thread can miss the renderer's worker thread. Verify persisted arm and actual request on every run.

## Separate flag, registration and effects

At the source boundary above, `living-description-enabled?` defaults false but is not a universal switch for ontology activity.

| Path | Current gate |
| --- | --- |
| Classification and injection | Researcher config/context; no Living Description flag check at this seam. |
| Automatic attached judge execution | Completion handler requires the Living Description flag. |
| Explicit configured judge selection | Resolver can return attachments while the flag is false; this does not prove automatic execution. |
| Default researcher judges | Unconfigured researcher receives five defaults when enabled; an explicit empty projected judge attachment suppresses defaults. Use the consuming revision's attachment API, not an assumed researcher constructor option. |
| Automatic generated-tree structural judging | Handler requires the flag. |
| Classified terminal occurrence recording | Qualified researcher verdict and matching assignment; no flag gate. |
| Emitted-shape enrichment | Qualified bookend/source/fingerprint/claim context; no flag gate. |
| Consolidation request/check/handler | Counters, request CAS, registered consolidator, provider and budgets; no flag gate. |
| Harvest | Recurrence, score evidence, description, parent linkage and one-time guard; no flag gate. |

Ordinary ontology and harvest processors are loaded by the ontology public interface. Reflection additionally needs the consolidator namespace and its processor registered and running. Inspect the actual registry and started processor topology. To freeze an experiment or prevent paid reflection, use explicit topology and provider/budget controls; turning this flag off is insufficient proof.

Judge behavior and attribution are evolving interfaces. Verify the installed revision's handler behavior and correctly attributed scores before activating a learning loop. Do not implement judge repairs as a side effect of setting up researcher reuse.

## Public sources

These links support the source distinctions; they are references, not required private project context:

- [Authoritative checkpoint mode](https://github.com/ObneyAI/orc/blob/1851212396cced2fc4533fda30b824bd4b691390/components/orc-service/src/ai/obney/orc/orc_service/core/researcher_mode.clj).
- [Classification, injection and execution processors](https://github.com/ObneyAI/orc/blob/1851212396cced2fc4533fda30b824bd4b691390/components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj).
- [Ontology public bootstrap/search controls](https://github.com/ObneyAI/orc/blob/1851212396cced2fc4533fda30b824bd4b691390/components/ontology/src/ai/obney/orc/ontology/interface.clj).
- [Ontology occurrence and reindex processors](https://github.com/ObneyAI/orc/blob/1851212396cced2fc4533fda30b824bd4b691390/components/ontology/src/ai/obney/orc/ontology/core/todo_processors.clj).
- [DJL engine selection](https://docs.djl.ai/master/docs/engine.html).
