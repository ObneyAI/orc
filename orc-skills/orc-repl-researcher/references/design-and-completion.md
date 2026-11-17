# Design an adaptive researcher stage and prove completion

Read this when deciding what a researcher should own, designing the trees it may generate, or investigating a run that finished without satisfying its goal. For authored tree structure, use [orc-design-tree](../../orc-design-tree/SKILL.md); for reusable specialist boundaries, use [orc-compose-specialists](../../orc-compose-specialists/SKILL.md).

## 1. Give the researcher one goal inside a composed behavior

Choose a researcher when useful work depends on discoveries made at runtime: which sources exist, how much evidence needs inspection, which tool arguments can be derived, whether a first attempt worked, or what missing work should run next. A variable collection size alone often needs an authored `:map-each`; it does not require a researcher if the per-item method and surrounding stages are already known.

Let a reusable specialist own a recognizable capability and a described input/output contract. Its authored tree can prepare evidence, run an adaptive researcher stage, validate the resulting artifact, and hand that artifact to another specialist. Keep known mandatory steps structural: a completion gate or required persistence operation belongs in the authored tree when it must run regardless of the generated strategy. Use `:delegate` to compose registered specialist trees with isolated child blackboards and explicit boundary mappings.

The researcher owns the goal of its stage and the strategy needed to satisfy it. It may inspect, generate a tree, observe execution, and generate further work. Its outer contract stays stable while its internal strategy changes. Give it enough room to choose a useful method; specify required outcomes, evidence and limits instead of prescribing a large fixed script disguised as an adaptive task.

Before implementation, write these concrete decisions:

| Decision | What to specify |
|---|---|
| Goal | The useful artifact or decision the stage must deliver |
| Inputs | Described, typed evidence and configuration; stable source identities |
| Outputs | Required parsed fields, optional fields and the exact downstream consumer |
| Choice | What the researcher may discover or change: source selection, decomposition, tools, argument values, repair strategy |
| Invariants | Coverage, provenance, allowed effects, dependency order and source preservation |
| Completion | Structural checks plus semantic checks appropriate to the task |
| Limits | Iterations, physical model calls, deadlines, fan-out, source access and generated-code capabilities |

For example, an evidence-review specialist can require one grounded assessment per supplied packet, an exact quantity sum, and a synthesis identifying unresolved checks. The researcher chooses decomposition and follow-up inspection. The authored consumer requires complete packet identity coverage before using the assessment.

## 2. Keep the three programming palettes distinct

The public workflow DSL, the researcher's SCI program and its emitted tree are different interfaces. A constructor available in one is not automatically valid in another.

| Palette | What it controls | Design rule |
|---|---|---|
| Authored ORC workflow | Registered nodes, described blackboard, specialists, conditions/decisions, fallback, delegation and researcher placement | Use the public node contracts to guarantee known orchestration and compose reusable behaviors. |
| Researcher SCI program | Immediate inspection and variable operations, bound tools, `emit-tree!`, and `final!` | Use the exposed sandbox primitives and granted capabilities. A sandbox variable is not automatically an emitted leaf's local binding. |
| Emitted tree DSL | A generated child tree executed by ORC | Use the emitted language's supported forms and explicit reads/writes/schemas. Verify the compiled node configuration, not only the model's source. |

The verified emitted palette includes `:sequence`, `:parallel`, `:map-each`, `:llm`, `:code`, `:chunk-document`, `:aggregate`, and the `:final` output marker. Do not assume authored `:delegate`, `:repl-researcher`, `:llm-decision`, `:condition` or `:fallback` forms can be emitted. Confirm both transformation and compilation support before introducing an additional generated node kind; a documentation example alone is insufficient.

Two completion surfaces also differ:

- `[:final {:keys [...]}]` marks which child-tree values should be returned. It is not a semantic quality judge and does not itself terminate the outer research campaign.
- `(final! {...})` finalizes the researcher's declared writes. Call it after inspecting and validating the actual deliverable.

Generated inline functions must be durable source, usually quoted function forms, and receive their data through declared `:reads` under `:inputs`. A function closing over a previous SCI local or undeclared namespace is not a portable leaf. Registered code functions need a complete catalog entry describing their name, purpose, read/write contract and capabilities. Generated code can calculate, join, validate and invoke granted tools; it does not acquire unrestricted application access by being generated.

## 3. Draw dependencies before adding parallelism

Treat every proposed branch as a producer/consumer contract. Ask which values must exist before a node starts. Put a consumer after its producers' join. Siblings in `:parallel` must be independent, use disjoint writes and avoid shared mutable tool state.

A sound source investigation can be:

```text
sequence
  discover source reference
  parallel
    retrieve source using that reference
    retrieve independent review status using that reference
  compute exact totals from retrieved values
  model assessment using source, status and totals
  select child outputs
```

A sound collection review can be:

```text
sequence
  parallel
    map-each: model review of every input item
    code: arithmetic over the original collection
  code: extract the collected named review fields
  model: integrate all reviews and the total
  check identity coverage and required deliverables
```

Do not put review extraction or final synthesis in the arithmetic branch: both depend on the review branch. A real experiment with that layout completed successfully at engine level while returning zero reviews for a nonempty collection. Moving dependent work after the join and checking exact input/output identities produced complete reviews for different collection sizes.

Use runtime collection cardinality, not a fixed number of handwritten calls. Set `:max-concurrency` intentionally and inspect effective configuration and overlapping execution intervals. More concurrent calls increase resource pressure and do not establish independent verification.

### Declare the actual map-each collection shape

Declare specific `:output-schemas` for both the map's `:as` item binding and its `:into` collection, plus every child write. For map-valued input items, collection can include the original item fields merged with the child's named writes. If a child writes `:review`, the successful collection may contain augmented items such as:

```clojure
{:id "item-a" :content "..." :quantity 7
 :review {:id "item-a" :readiness "ready" :reason "..."}}
```

Declare that augmented shape and explicitly extract `:review` for the next consumer. Do not assume `:into` is a vector of bare review values. Check this boundary with real projected values. Use [orc-blackboard-contracts](../../orc-blackboard-contracts/SKILL.md) to define specific described contracts.

Failures need item attribution. Preserve original source identity and inspect node path, item index and attempt. A compacted partial collection can omit failed items; equality with input coverage is a stronger completion check than output length alone. Nested maps need a distinct recovery proof that identifies both outer and inner items.

## 4. Inspect long inputs without losing source identity

Use input profiles to discover type, size and structure, then selectively read the evidence required for the goal. Measure characters, bytes or tokens explicitly; they are different units. Choose chunking based on useful evidence boundaries and the consuming model's capabilities. A small source with important cross-references may need a whole-source view; large independent records may be better reviewed separately.

Carry document/reference identity and span or record identity through extraction, verification and synthesis. Preserve original evidence separately from normalized views. An opaque reference, UUID, workbook path or tool-returned identifier must come from the real source/tool result and be passed unchanged where required.

Chunking creates knowledge boundaries:

- A finding about one section can be supported by that section's exact evidence.
- A claim that something is absent from the entire source needs global confirmation. A clause absent from one chunk may appear elsewhere.
- A cross-record comparison requires both relevant sources, not a lossy summary that removed the detail being compared.
- A synthesis needs actual completed findings and their provenance, not a status string saying extraction succeeded.

Use source-grounded model work for interpretation. Use code for exact arithmetic, record joins, offsets and literal evidence checks. Do not use regex or keyword lists to decide what human or AI messages mean, which specialist fits, whether instructions were followed, or whether a task is semantically complete. Literal parsing or exact character counting can use deterministic code when the requirement itself is literal. Code-generated canned prose does not replace a model's task-specific synthesis.

## 5. Describe tools and choose arguments at the right boundary

Give each available tool a purpose description, typed/described arguments, typed/described result, effect classification and replay participation contract. Explain when it is useful and which prior output supplies its arguments. Inspect the actual catalog and schemas shown to the model: an allowlist of names alone gives weak selection guidance.

Use [orc-tools](../../orc-tools/SKILL.md) for authoritative catalog and invocation setup. In generated tool-calling code leaves, the granted invocation seam receives the tool name, argument map and opaque tool context. Thread that context through the leaf; do not invent a separate caller or bypass its validation to reach the same service.

Choose the smallest sufficient argument mechanism:

| Needed argument | Suitable producer |
|---|---|
| A choice from a supplied finite catalog | A decision model or LLM choosing a typed enum, mapped to a trusted catalog entry |
| A bounded numeric/boolean/enum combination | A decision model when its actual client/schema supports those choices |
| A new natural-language search query or a free-form task-specific field | An LLM or researcher producing the required structured arguments |
| An opaque source reference returned by discovery | Preserve the real result; a model may select among supplied candidates, never fabricate a reference |
| Arithmetic or a value already deterministically derivable | Code using validated source values |

Use [orc-decision-models](../../orc-decision-models/SKILL.md) for bounded semantic choice design. In an authored specialist, a bounded decision can route to a tool behavior or another specialist. In the generated palette, do not invent support for the authored decision node; use verified emitted capabilities or return a typed decision for the authored tree to handle.

Tools can form dependent sequences, independent parallel branches and repeated per-item behaviors. One tool result can reveal the next required action, which the researcher then assembles as another tree. This is task recursion through observed outcomes, not a promise that every authored node constructor supports recursive emission.

## 6. Make completion stricter than successful execution

Inspect the real artifact before finalization and again at the authored consumer boundary. Require:

1. Every required outer write is present with the intended parsed type and specific schema.
2. Required nested fields, item identity coverage, uniqueness and computed invariants hold.
3. Semantic findings answer the goal, preserve evidence and explicitly identify unresolved work.
4. Tool artifacts exist and can be consumed where an artifact is part of the goal.
5. Partial or failed child work is accounted for, repaired or honestly surfaced according to the contract.

Engine success, created variable names, a schema-looking tree and a plausible final narrative are separate observations. None proves the complete goal. A nonempty result map containing an empty required review vector can still be incomplete. A model saying it inspected or verified an artifact is not proof it read the actual values.

Define legitimate emptiness before running. Zero qualifying findings may be correct; zero reviews for supplied items is generally missing work. Zero numbers and `false` booleans can be valid results. Optional absence and an explicitly present nested nil need different schemas and consumer behavior.

The current `final!` checks declared keys and rejects all-empty outer values. That guard is not a full semantic completeness check. If a legitimate empty result conflicts with that boundary, surface the incompatibility. Use a meaningful evidence-bearing result envelope only when the downstream contract calls for it; do not add a dummy status or invented item merely to evade the guard. Keep schemas strict through recovery; replacing required structures with `:any`, converting unknown numeric values to zero, or accepting unrelated prose changes the promised outcome.

## 7. Observe failure, then repair the smallest missing seam

After emitting a tree, use the recorded outcome and preserved values to decide the next step. Separate these situations: a child is still running; a child timed out; a tool failed; a schema rejected a value; a semantic check found incomplete work; or your observation query returned no evidence. They require different responses.

When execution detail is needed, actually invoke `tree-detail`, `tree-trajectory`, `tree-failures`, `node-output` or `node-input-profile` as appropriate. Inspect their returned shapes and identities. Counts such as nodes-succeeded are not node-id vectors. Preserve UUID objects when the primitive expects node identity.

Verify invocation and result separately. A source mention in reasoning, an unexecuted branch or a code string containing `node-output` does not establish a call. A call returning nil does not establish that the producer failed or had no surviving output. Check the selected child tick, available tree-result binding, actual event scope and value resolution; continue diagnosis through the host trace if needed. Do not invent evidence to keep a repair moving.

Choose retained work only from attributable successful outputs. If discovery and retrieval succeeded while a status tool failed, rerun the missing status check and dependent assessment; preserve the discovered reference and retrieved source. If extraction succeeded and synthesis failed, rebuild synthesis against the surviving strict records. If one item failed, target that item and rejoin the result while keeping other items unchanged. A mechanical parsing defect needs a mechanical repair; a semantic defect needs new model work or evidence.

Check the repaired artifact against the original completion contract. Record which prior results were reused and which actions reran. Process restart, unknown external effects and durable resume have additional runtime obligations; consult the skill's recovery guidance rather than treating another emission as proof of restart recovery.

## 8. Spend exploration where it changes the answer

Budget root planning, child model calls, retries, tool work and final verification together. Set bounds from task scale and the actual provider/runtime contract. A cheaper child model can handle narrow extraction or bounded assessments while a stronger model plans or integrates; use per-node overrides where supported and verify the actual provider request. Do not depend on generated `:llm :options` to enforce provider limits: inspect the current compiler and actual request, and keep execution bounds at the verified host/runtime boundary. An option appearing in emitted source is not a budget guarantee.

Choose breadth for a reason: independent sources, different hypotheses or intentionally different review perspectives. Two identical model passes are not strong independent evidence. A verification node should return located defects and drive follow-up work, not merely praise the first result. More calls can cost more while preserving the same blind spot.

When comparing strategies, hold task evidence, modality, available tools and acceptance checks constant. Separate classification/retrieval overhead from execution cost. Report latency, physical call count, tokens, useful coverage, factual defects and effects replayed. A larger tree or reused pattern identity is not itself improvement.

Promote a repeated successful method manually into a described specialist or reusable code tool after inspecting its source, contracts, execution and failure behavior. Retain the conditions under which it worked and the evidence that supports it. Move stable orchestration out of repeated exploratory code generation; keep a researcher where strategy still depends on new evidence. This promotion is a reviewed design decision, not proof that automatic harvesting or training improved future runs.
