# ORC Prior Art and Research Lessons

> A consolidated research note on systems related to ORC, the academic literature
> behind its components, and the architectural and experimental lessons that follow.
>
> Researched: 2026-08-06

## Executive summary

ORC is an event-sourced behavior-tree workflow engine for compound AI systems. It
combines fixed workflow control, LLM and code leaves, typed blackboard contracts,
runtime-generated subtrees, evaluation, prompt evolution, ontology-backed memory,
and hybrid retrieval.

There are systems resembling every major part of ORC, but no system identified in
this review combines all of them around the same architecture. ORC's defensible
distinction is therefore not the invention of its individual mechanisms. It is their
integration around an event-sourced behavior-tree runtime, together with distinct
actuators for runtime structural adaptation, offline prompt optimization, and reuse
of experience.

The best concise positioning is:

> ORC is an event-sourced adaptive behavior-tree runtime for compound AI systems,
> combining explicit workflow control with bounded runtime tree synthesis and
> experience-derived pattern reuse.

The literature broadly supports ORC's architectural direction. Its most important
lesson, however, is that ORC should become more empirical and less dependent on
LLM-authored interpretations of its own traces:

> Treat ORC as a learnable control system whose policy is the behavior tree, whose
> observations are event streams, whose reward is multidimensional evaluation, and
> whose memory contains provenance-bearing hypotheses rather than accepted truths.

The highest-priority improvements are:

1. Calibrate and benchmark the judges that feed every learning mechanism.
2. Separate structural, semantic, exact-definition, and runtime-policy identities.
3. Record adaptive interventions so improvement can be measured causally.
4. Represent learned claims atomically with supporting and contradicting episodes.
5. Add static validation and effect constraints for generated trees.
6. Exploit the event store for deterministic process and conformance analysis.
7. Benchmark retrieval signals and introduce calibrated abstention.
8. Add a durable topology optimizer alongside GEPA and runtime RLM generation.

## What ORC currently is

ORC's repository describes a layered system:

| Layer | Capability |
|---|---|
| Core | Behavior-tree DSL and execution, typed blackboard, event-sourced state, workflow versioning |
| Value storage | Canonical execution values externalized from events |
| Evaluation | Grounding, reasoning, completeness, instruction-following, and custom judges |
| Observability | Trace forwarding and execution correlation |
| GEPA | Pareto-frontier reflective prompt evolution |
| Ontology | General concept graph, embeddings, descriptions, and pattern knowledge |
| ColBERT | Token-level late-interaction retrieval |
| Self-improving loop | Classification, pattern injection, evidence consolidation, and behavior minting |
| MCP sheet builder | Workflow generation from tool schemas |
| RLM | Sandboxed code generation and recursive runtime subtree construction |

The authoritative repository descriptions are:

- [README](../README.md)
- [Architecture](ARCHITECTURE.md)
- [Component map](COMPONENT-MAP.md)
- [ORC principles](ORC-PRINCIPLES.md)
- [RLM guide](RLM-GUIDE.md)
- [Self-improving loop](SELF-IMPROVING-LOOP.md)
- [Ontology](ONTOLOGY.md)
- [Living descriptions](LIVING-DESCRIPTIONS.md)
- [Judge architecture](JUDGE-ARCHITECTURE.md)
- [GEPA guide](GEPA-GUIDE.md)
- [ColBERT integration](COLBERT-INTEGRATION.md)

## Prior-art landscape

### Closest systems

| System | Resemblance to ORC | Important difference |
|---|---|---|
| Dendron | Behavior trees combining LLMs, ordinary code, and classical AI | Closest conceptual ancestor, but not ORC's complete event-sourced, evaluative, adaptive, and ontology-backed stack |
| LangGraph | Stateful graphs, shared schemas, branching, loops, parallel work, persistence, and durable execution | General graph/state-machine semantics rather than reactive behavior-tree semantics; no equivalent integrated experiential ontology |
| GPTSwarm | Recursively composable agent graphs; optimization of node prompts and graph connectivity | Experimental graph optimizer rather than an event-sourced operational BT runtime |
| DSPy and GEPA | Evaluation-driven reflective prompt evolution and Pareto candidate selection | Optimize textual components rather than providing ORC's execution and memory architecture |
| Recursive Language Models | A model writes code in a REPL, decomposes inputs, invokes submodels, and inspects intermediate results | Primarily an inference strategy; ORC embeds it as a bounded node that emits behavior subtrees |
| ACE | Accumulates strategies and failures into evolving playbooks without updating model weights | Context/playbook-centric rather than ontology- and behavior-tree-centric |
| Voyager and ExpeL | Reusable skills and insights learned from experience and retrieved for later work | Research agents in narrower settings, with less emphasis on durable workflow operations |
| AutoGen GraphFlow | Sequential, parallel, conditional, and looping multi-agent workflows | Message/team-oriented rather than typed-blackboard BT execution; no inherent self-improving loop |
| Temporal and Dagster-like engines | Durable workflow operations, retries, observability, and recovery | Do not provide ORC's behavior-tree policy, LLM evaluation, RLM, or experience loop |

### Dendron: closest conceptual ancestor

Dendron explicitly argues that behavior trees unify language models with classical
AI and traditional programming. That is also ORC's foundational premise. It
demonstrates chat, robotic inspection, and safety-constrained agents.

This means ORC should not claim novelty for using behavior trees to orchestrate LLM
agents. ORC's distinction lies in what it adds: event sourcing, typed blackboard
contracts, workflow versions, recursive generated subtrees, judges, prompt evolution,
and ontology-backed reuse.

Sources:

- [Behavior Trees Enable Structured Programming of Language Model Agents](https://arxiv.org/abs/2404.07439)
- [Dendron repository](https://github.com/RichardKelley/dendron)

### LangGraph: closest general-purpose substitute

LangGraph supplies stateful graph execution, conditional edges, dynamic workers,
parallelism, persistence, streaming, human intervention, and durable checkpoints.
It is likely the closest practical general-purpose alternative to ORC.

The conceptual difference is important:

- A graph engine asks which node should execute next.
- A behavior tree asks which behavior succeeds, fails, remains applicable, or should
  be reconsidered on this tick.

ORC consequently offers a more opinionated hierarchical policy-selection model,
while LangGraph supplies more general state-machine and dataflow semantics.

Sources:

- [LangGraph Graph API](https://langchain-ai.github.io/langgraph/how-tos/state-reducers/)
- [LangGraph persistence](https://langchain-ai.github.io/langgraph/concepts/time-travel/)
- [LangGraph workflow patterns](https://langchain-ai.github.io/langgraph/agents/tools/)

### Runtime adaptation: Recursive Language Models

RLM treats long input as an external environment that a model explores
programmatically. The model writes code, selects relevant portions, recursively calls
language models, and inspects results. The original work reports handling inputs up to
two orders of magnitude beyond the base context window.

ORC extends this into a mixed fixed/adaptive architecture:

- RLM is a leaf inside an otherwise explicit workflow.
- It emits a constrained behavior-tree DSL.
- Generated work executes through the same event-producing runtime as handwritten
  workflows.
- Results return to the researcher, which can design a focused follow-up tree.
- Deterministic preprocessing, validation, and packaging can surround the adaptive
  region.

This fixed outer tree with a bounded adaptive inner subtree is one of ORC's strongest
architectural differentiators.

Sources:

- [Recursive Language Models](https://arxiv.org/abs/2512.24601)
- [RLM author explanation and code](https://alexzhang13.github.io/blog/2025/rlm/)

### Prompt and topology optimization

GPTSwarm represents language agents as computational graphs and optimizes both node
prompts and graph connectivity. This makes it the closest prior art to improving both
node behavior and orchestration.

GEPA uses natural-language reflection over trajectories and textual feedback, then
maintains a Pareto frontier of complementary candidates. It reports outperforming GRPO
by 6% on average and up to 20% in the evaluated tasks, with up to 35 times fewer
rollouts, and outperforming MIPROv2 by more than 10% in reported comparisons.

Automated Design of Agentic Systems goes further by allowing a meta-agent to invent
complete agent programs and retain an archive of prior discoveries.

These works expose ORC's missing durable actuator:

- GEPA improves static instruction text.
- RLM generates ephemeral runtime topology.
- Living Descriptions influence later runtime designs.
- No ORC subsystem yet systematically proposes, evaluates, and publishes improved
  durable behavior-tree topology.

Sources:

- [Language Agents as Optimizable Graphs](https://arxiv.org/abs/2402.16823)
- [GEPA](https://arxiv.org/abs/2507.19457)
- [Automated Design of Agentic Systems](https://arxiv.org/abs/2408.08435)

### Experiential learning and non-parametric adaptation

Several systems learn without changing model weights:

- Reflexion stores verbal feedback in episodic memory.
- Voyager stores successful executable skills and retrieves them for new tasks.
- ExpeL extracts natural-language insights from successful and failed experiences.
- ACE evolves structured playbooks using generation, reflection, and curation.

ORC's three-layer failure/success/problem ontology and injection of retrieved patterns
into a behavior-tree generator is unusual, but the underlying proposition—retaining
reusable experience without fine-tuning—is established.

Sources:

- [Reflexion](https://arxiv.org/abs/2303.11366)
- [Voyager](https://openreview.net/pdf?id=P8E4Br72j3)
- [ExpeL](https://arxiv.org/abs/2308.10144)
- [Agentic Context Engineering](https://arxiv.org/abs/2510.04618)

## Lessons by ORC subsystem

## 1. Core behavior-tree engine

The behavior-tree literature supports ORC's choice. Research repeatedly finds that
BTs improve modularity, reactivity, reuse, and maintainability as control complexity
grows. The benefit is architectural: an equivalent FSM can implement the same policy,
but is often harder to modify and analyze.

Sources:

- [A Survey of Behavior Trees in Robotics and AI](https://arxiv.org/abs/2005.05842)
- [Comparison between Behavior Trees and Finite State Machines](https://arxiv.org/abs/2405.16137)
- [Programming effort for BTs and FSMs](https://arxiv.org/abs/2209.07392)

### Recommended additions

- Explicit running, interruption, cancellation, and preemption semantics.
- Preconditions, postconditions, invariants, and declared effects.
- Static validation of reads, writes, and schema compatibility.
- Per-node estimated success probability, latency, token use, and monetary cost.
- Checks for unreachable children, impossible fallback paths, recursive nontermination,
  and unsafe parallel writes.
- A distinction between failure and current inapplicability.

Probabilistic LLM work warrants richer outcomes than only success and failure:

```clojure
:success
:failure
:partial
:inapplicable
:retryable
:timeout
:budget-exhausted
:cancelled
```

These can map to parent behavior-tree semantics while remaining distinct in events,
evaluation, and learning.

### Planning above behavior trees

Robotics research combines high-level Hierarchical Task Network planning with low-level
BT execution: the planner chooses a decomposition while the BT provides reactive
execution and recovery.

Source:

- [Hybrid HTN and Behavior Tree planning](https://ojs.aaai.org/index.php/AIIDE/article/view/13044)

This suggests the following ORC division of responsibility:

- Fixed BT: known operational policy.
- RLM: local adaptive planner for an unknown-shaped step.
- Topology optimizer: offline proposal of durable workflow changes.
- BT runtime: validated execution, interruption, and recovery.

RLM should not become the only planning mechanism.

## 2. Event sourcing and Grain

Event sourcing is one of ORC's most underexploited advantages. Process-mining research
uses event logs to discover actual process variants, compare observed and intended
behavior, identify deviations, measure process fitness, and locate bottlenecks.

Sources:

- [Conformance Checking Techniques of Process Mining](https://journals.sagepub.com/doi/10.3233/APC210213)
- [Multi-perspective conformance checking of uncertain traces](https://www.sciencedirect.com/science/article/pii/S0952197623010795)

### Add deterministic process analysis

Alongside Living Description reflection, ORC should:

1. Reconstruct each tick as a complete execution trajectory.
2. Compare the trace with the declared tree.
3. Detect skipped, repeated, unexpected, and reordered actions.
4. Discover frequent execution variants by workflow and task class.
5. Calculate:

   - path frequency;
   - fallback activation rate;
   - retry effectiveness;
   - partial-result survival;
   - latency and cost by path;
   - success probability conditional on node outcomes;
   - conformance fitness;
   - structural precision.

This supplies objective features to the learning system rather than asking an LLM to
infer everything from raw traces.

### Record causal interventions

Execution correlations do not prove that a pattern, prompt mutation, or generated tree
caused an improvement. Every adaptive intervention should record:

```clojure
{:intervention/type :pattern-injection
 :candidate-id ...
 :baseline-policy-id ...
 :selection-propensity ...
 :task-class ...
 :model ...
 :judge-version ...
 :retriever-version ...
 :tree-definition-hash ...
 :prompt-content-hash ...}
```

This enables randomized holdouts, matched comparisons, canaries, and eventually
off-policy evaluation.

## 3. Blackboard and contracts

ORC's typed blackboard and declared reads/writes are stronger than ordinary shared
agent state. They should evolve toward an effect and provenance system.

Add:

- required versus optional reads;
- immutable versus mutable keys;
- single-writer or explicit merge-policy constraints;
- preconditions and postconditions;
- sensitivity and provenance labels;
- runtime validation of outputs against postconditions;
- static detection of parallel write conflicts;
- lineage from derived values to source values, prompts, tools, and model calls.

Before executing an RLM-generated tree, ORC should prove that:

- every read has a possible producer;
- all outputs conform to the parent contract;
- no forbidden effect is present;
- recursion and fan-out stay within budget;
- parallel branches have valid merge semantics.

Research on neuro-symbolic BT verification demonstrates that formal verification can
coexist with learned or opaque leaves. ORC need not verify the LLM internally; it can
verify the envelope around it.

Source:

- [Neuro-Symbolic Behavior Trees and Their Verification](https://proceedings.mlr.press/v288/serbinowska25a.html)

## 4. Evaluation and LLM judges

ORC's explicit criteria, discrete bands, reason-before-score ordering, structured form
filling, and independent dimensions are consistent with G-Eval. G-Eval found improved
human alignment from chain-of-thought and form filling, while still reporting only
moderate correlation and identifying bias toward LLM-generated text.

Source:

- [G-Eval](https://arxiv.org/abs/2303.16634)

The broader literature identifies position bias, verbosity bias, self-preference,
correlated generator/judge errors, and prompt-specific calibration problems.

Sources:

- [Judging LLM-as-a-Judge](https://openreview.net/pdf?id=uccHPGDlao)
- [Mitigating the Bias of Large Language Model Evaluation](https://arxiv.org/abs/2409.16788)

### Build a judge benchmark

Every judge version should be evaluated against a human-labeled set containing:

- obvious positives and negatives;
- adjacent-band examples;
- concise and verbose equivalents;
- reordered responses;
- hedged hallucinations;
- fluent but invalid reasoning;
- adversarial distractors;
- outputs from the same and different model families.

Track:

- exact and adjacent-band agreement;
- weighted Cohen's kappa;
- false-accept and false-reject rates;
- repeated-evaluation stability;
- calibration by task class;
- bias sensitivity.

PaperBench provides a useful precedent: it built a separate benchmark for its LLM judge
rather than assuming the judge was trustworthy.

Source:

- [PaperBench](https://openai.com/index/paperbench/)

### Store uncertainty

A 1–5 band hides judge uncertainty. Important decisions should permit repeated samples
and store a distribution:

```clojure
{:mean 0.71
 :variance 0.036
 :samples 3
 :agreement 0.67
 :calibration-version "..."}
```

GEPA and Living Descriptions should weight evidence by judge reliability and
uncertainty.

### Pairwise evaluation is not automatically superior

ORC's decision to defer pairwise judging is defensible. Recent evidence reports that
pairwise judgments can be more vulnerable to distractor features than absolute scoring.

Source:

- [Pairwise or Pointwise?](https://arxiv.org/abs/2504.14716)

Pairwise evaluation should be adopted only where ORC-specific calibration shows that it
improves reliability.

### Evidence hierarchy

Evaluation should prefer:

1. Deterministic verifier.
2. Environment outcome.
3. Constraint or schema check.
4. Reference-based metric.
5. Calibrated LLM judge.
6. Uncalibrated LLM reflection.

LLM judges should handle genuinely semantic dimensions, not properties ORC can compute.

## 5. Composite scoring

A weighted mean can conceal catastrophic failure: high fluency must not compensate for
fabrication or unsafe behavior.

Support:

- weighted mean;
- weighted geometric mean;
- minimum dimension;
- veto thresholds;
- lexicographic objectives;
- Pareto dominance;
- task-specific utility.

Example:

```clojure
{:hard-gates {:grounding 0.75
              :safety 1.0}
 :optimize   [:completeness :reasoning]
 :tie-break  [:cost :latency]}
```

This better matches GEPA's Pareto framing than immediately collapsing all dimensions
into one scalar.

## 6. GEPA

GEPA's main lesson is that rich trajectory feedback can be substantially more useful
than sparse scalar reward.

ORC should supply GEPA with:

- exact failing leaves;
- deterministic verifier output;
- retrieved evidence;
- tool errors;
- budget consumption;
- judge reasoning and uncertainty;
- contrasts with successful runs in the same task class;
- process-conformance deviations.

### Guard against overfitting

Use separate datasets for:

- reflection and training;
- candidate selection;
- final untouched testing.

Also require:

- repeated stochastic trials per candidate;
- confidence intervals;
- baseline re-evaluation;
- cross-model transfer tests;
- cost and latency objectives;
- production canaries and rollback.

The judge, retrieval corpus, and task set should be frozen during an optimization
experiment. Otherwise the objective moves while it is being optimized.

## 7. RLM and `repl-researcher`

RLM is valuable for genuinely unknown-shaped work, but should be gated more strictly.

### Add an adaptive router

Predict whether a fixed strategy is sufficient using:

- input length;
- task class;
- historical fixed-tree performance;
- retrieval confidence;
- expected decomposition gain;
- cost and deadline;
- uncertainty after a cheap first pass.

Recommended escalation:

```text
fixed leaf
   -> low confidence or oversized input
fixed map/reduce template
   -> unresolved or structurally novel
RLM
   -> repeated failure
human review or durable topology search
```

### Validate generated trees

Require:

- structural schema checks;
- dataflow validation;
- budget analysis;
- tool allowlists;
- fan-out limits;
- termination constraints;
- side-effect checks;
- checks against known unsafe patterns.

### Preserve alternatives

Retain competing decompositions, rejected hypotheses, and failed subtrees rather than
only the selected final design. This creates the raw material for a future durable tree
candidate archive.

## 8. Durable topology optimization

ORC should add an offline topology optimizer that:

1. Selects a published workflow and task distribution.
2. Proposes constrained mutations, such as:

   - adding or removing a validator;
   - introducing bounded parallelism;
   - adding recovery or fallback;
   - replacing a leaf with a delegate;
   - inserting retrieval;
   - changing map/reduce decomposition;
   - promoting a recurring RLM subtree.

3. Rejects invalid candidates statically.
4. Evaluates candidates on held-out tasks.
5. Maintains a Pareto frontier for quality, cost, latency, and reliability.
6. Publishes only after canary validation.
7. Preserves the prior sheet version for immediate rollback.

ORC's event sourcing and versioning make it particularly well suited to implementing
this safely.

## 9. Ontology and Living Descriptions

ORC's structured, incremental descriptions align well with ACE, especially because ACE
identifies two failure modes of repeated rewriting:

- brevity bias, which removes useful detail;
- context collapse, in which iterative rewriting erodes prior knowledge.

### Separate memory types

Make these first-class:

- Episodic memory: the exact execution, context, output, failure, and evidence.
- Semantic memory: generalized claims such as "bounded concurrency reduces rate-limit
  failure."
- Procedural memory: executable or generatable behavior patterns.

Every semantic or procedural claim should link to supporting and contradicting episodes.

### Store hypotheses, not facts

A Living Description entry should resemble:

```clojure
{:claim ...
 :scope ...
 :supporting-episode-ids [...]
 :contradicting-episode-ids [...]
 :effective-sample-size 27
 :task-distribution ...
 :judge-reliability ...
 :confidence-interval ...
 :status :candidate | :validated | :deprecated}
```

An LLM-authored confidence number must not be treated as statistical confidence.

### Prevent context collapse

Instead of regenerating a complete description on every consolidation:

- extract atomic proposed changes;
- merge them deterministically;
- retain old claims and provenance;
- resolve contradictions explicitly;
- summarize only for retrieval and presentation;
- keep the canonical store lossless.

### Measure memory benefit

Randomly withhold pattern injection on a portion of executions and compare:

- success and quality;
- token and monetary cost;
- latency;
- structural complexity;
- error categories.

Also compare correct retrieval, random retrieval, and no retrieval. This distinguishes
the value of the retrieved pattern from the generic effect of adding more context.

## 10. Workflow fingerprinting

The current structural fingerprint erases instruction and function contents. This is
useful for topology grouping, but insufficient as the sole workflow identity.

Use multiple identities:

- `structure-hash`: normalized topology;
- `semantic-hash`: normalized instructions, schemas, tools, and effects;
- `definition-hash`: exact complete definition;
- `runtime-policy-id`: definition plus model, tool, and retriever configuration;
- `task-class`;
- `behavioral-embedding`.

This is high priority because current aggregation can attribute a successful semantic
variant to unrelated workflows sharing the same structure.

## 11. Ontology retrieval, embeddings, ColBERT, and RRF

ORC's hybrid retrieval architecture is plausible:

- ColBERT preserves token-level interactions while permitting document-side
  precomputation.
- Reciprocal Rank Fusion is a simple, historically robust fusion method.
- Hybrid graph/vector retrieval has outperformed either alone in some domain studies.

Sources:

- [ColBERT](https://people.eecs.berkeley.edu/~matei/papers/2020/sigir_colbert.pdf)
- [Reciprocal Rank Fusion](https://cormack.uwaterloo.ca/cormacksigir09-rrf.pdf)
- [HybridRAG](https://arxiv.org/abs/2408.04948)

None of these results proves that fixed `k=60`, current weights, or even all three
signals are optimal for ORC's small specialized corpus.

### Build retrieval evaluation

Create reviewed queries with:

- relevant patterns;
- explicitly harmful patterns;
- partial matches;
- out-of-distribution inputs;
- lexical matches with the wrong behavior;
- structural matches from the wrong domain.

Measure each signal and their fusion using:

- Recall@k;
- precision@k;
- MRR;
- nDCG;
- harmful-retrieval rate;
- abstention accuracy;
- downstream execution gain;
- latency and cost.

Include sparse lexical retrieval such as BM25 as a baseline. Exact domain terminology
may outperform semantic retrieval for some queries.

### Make fusion conditional

Different queries favor different signals:

- exact node or tool names: lexical;
- semantic task similarity: dense or ColBERT;
- related failure mechanisms: graph expansion;
- known workflow family: structural matching.

Tune fusion by task class or choose weights through held-out evaluation. The retriever
must also be able to abstain: an irrelevant injected pattern can be worse than none.

## 12. Evolutionary ontology builder

LLM-discovered concepts and relationships create a feedback-loop risk: generated
knowledge enters retrieval, retrieval affects executions, and those executions can then
reinforce the generated knowledge.

Add:

- source spans and IDs for every concept and triple;
- extraction confidence separate from empirical confidence;
- deterministic duplicate candidates before LLM reconciliation;
- contradiction records rather than destructive merging;
- schema and domain/range constraints;
- sampled human review;
- extraction precision/recall benchmarks;
- quarantine for automatically created concepts;
- ontology version pinning on every execution.

Automatically created knowledge should become eligible for injection only after crossing
an evidence threshold.

## 13. MCP Sheet Builder and tool use

Tool-learning research divides the task into planning, tool selection, tool calling, and
response generation. StableToolBench exists because unavailable and changing APIs make
tool evaluation unstable.

Sources:

- [Tool Learning with Large Language Models: A Survey](https://arxiv.org/abs/2405.17935)
- [StableToolBench](https://arxiv.org/abs/2403.07714)

Implications for ORC:

- Retrieve a small tool candidate set instead of exposing every schema.
- Contrast similar tools explicitly.
- Validate generated arguments before invocation.
- Model authentication, mutability, idempotency, cost, and risk.
- Generate compensation behavior for mutating operations.
- Pin tool and schema versions to generated sheets.
- Test generated workflows in simulated or read-only environments first.
- Treat tool descriptions and results as untrusted, injection-capable inputs.
- Separate semantic appropriateness from authorization.

The builder should produce both a workflow and a capability/threat manifest.

## 14. Observability and provenance

Agent-observability literature emphasizes complete structured trajectories for
security, accountability, monitoring, and trust.

Sources:

- [AgentTrace](https://arxiv.org/abs/2602.10133)
- [From Agent Traces to Trust](https://arxiv.org/abs/2606.04990)

ORC's canonical lineage should connect:

```text
task
-> sheet version
-> node execution
-> input value versions
-> prompt and model configuration
-> tool calls
-> retrieved patterns and evidence
-> emitted values
-> judge decisions
-> consolidation updates
-> future interventions
```

Langfuse should be a projection of canonical event history rather than a competing
source of truth.

Add queries such as:

- Why was this branch selected?
- Which value made this condition true?
- Which pattern was injected?
- Which episodes support that pattern?
- Which judge caused this description update?
- Which prompt or tree version introduced the regression?

## 15. File and value storage

Every canonical external value reference should bind:

- cryptographic content hash;
- media type and schema version;
- producer node execution;
- source value lineage;
- storage backend;
- access classification;
- retention policy;
- encryption and key version;
- canonical versus preview representation.

Judges and replay must consume canonical values rather than truncated event previews.
Replay should fail clearly when an artifact is missing or changed.

## 16. Evaluation strategy for ORC itself

AgentBoard argues that final success rate hides meaningful intermediate progress and
introduces a stepwise progress-rate metric. Agent-evaluation surveys likewise emphasize
behavior, reliability, safety, long-horizon interaction, and deployment realism.

Sources:

- [AgentBoard](https://arxiv.org/abs/2401.13178)
- [Evaluation and Benchmarking of LLM Agents: A Survey](https://arxiv.org/abs/2507.21504)

ORC should benchmark:

- fixed workflow versus RLM;
- RLM with and without retrieval;
- two-signal versus three-signal retrieval;
- no memory versus injected memory;
- baseline versus GEPA prompt;
- handwritten versus generated tree;
- static versus optimized topology;
- same-model versus cross-model judge;
- familiar versus out-of-distribution task;
- clean versus malicious tool output.

Measure:

- final success;
- partial progress;
- deterministic correctness;
- per-dimension quality;
- repeated-run reliability;
- cost and latency;
- LLM call count;
- recovery success;
- fallback frequency;
- harmful side effects;
- calibration error;
- memory benefit and harm;
- performance under model substitution.

Report distributions and confidence intervals, not isolated runs.

## Prioritized roadmap

### P0: Protect the learning signal

1. Create an ORC judge-calibration benchmark.
2. Attach uncertainty and judge-version metadata to scores.
3. Prefer deterministic verifiers over LLM judges.
4. Add hard gates to composite scoring.
5. Separate structure, semantic, exact-definition, and runtime-policy identities.

Until this is done, downstream learners can amplify measurement noise.

### P1: Make learning experimentally defensible

6. Add pattern-injection holdouts and randomized canaries.
7. Record interventions and selection propensities.
8. Represent Living Description claims atomically with episode provenance.
9. Retain contradicting evidence and effective sample size.
10. Pin ontology, retriever, judge, model, and workflow versions per run.

### P2: Exploit event sourcing

11. Add trace reconstruction and process-conformance analysis.
12. Calculate path-, node-, and intervention-level statistics.
13. Add output-to-source and learned-pattern provenance queries.
14. Detect execution variants and recurring recovery subtrees automatically.

### P3: Make adaptive execution safer

15. Add a fixed-tree/RLM router.
16. Validate generated trees statically.
17. Add formal cancellation, retry, timeout, and budget semantics.
18. Shadow-test novel mutating workflows before execution.

### P4: Close the structural-improvement loop

19. Build a durable topology optimizer.
20. Maintain a candidate-tree archive and Pareto frontier.
21. Promote repeatedly successful runtime subtrees into candidate published workflows.
22. Canary, version, and automatically roll back published adaptations.

### P5: Validate retrieval and memory

23. Build relevance judgments for the pattern corpus.
24. Benchmark graph, dense, ColBERT, lexical, and fused retrieval independently.
25. Introduce calibrated retrieval abstention.
26. Measure end-to-end benefit against no retrieval and random retrieval.

## Claims ORC can and cannot make

### Defensible claims

- ORC applies behavior-tree control to compound AI workflows.
- It integrates handwritten and runtime-generated behavior trees through one runtime.
- It provides an event-sourced basis for workflow versioning, replay, audit, and
  learning evidence.
- It separates static prompt optimization, runtime tree generation, and experiential
  pattern retrieval.
- It attempts a closed loop from execution through evaluation and memory into future
  tree design.

### Claims to avoid without further evidence

- That ORC invented behavior-tree orchestration for LLM agents.
- That the current self-improving loop reliably improves out-of-distribution tasks.
- That Living Description confidence is statistically calibrated.
- That three-signal retrieval is better than simpler retrieval on ORC's corpus.
- That judge composites constitute ground-truth quality.
- That structural fingerprints identify semantically equivalent workflows.
- That observed performance improvements were caused by injected patterns.
- That runtime-generated topology is superior to fixed templates without controlled
  comparison.

## Final assessment

The literature does not suggest replacing ORC's architecture. It suggests tightening
and validating it.

ORC's strongest foundations are:

- behavior trees for modular and reactive control;
- typed blackboard dataflow;
- event-sourced execution evidence;
- bounded adaptive RLM nodes inside explicit workflows;
- GEPA for textual optimization;
- evolving non-parametric memory;
- hybrid semantic and relational retrieval.

Its weakest scientific assumptions are:

- treating judge scores as reliable without a judge benchmark;
- treating LLM consolidation as evidence of learning;
- grouping semantically different workflows under one structural fingerprint;
- using fixed retrieval fusion without corpus-specific evaluation;
- inferring improvement from observational traces;
- lacking durable topology optimization;
- lacking formal validation of generated trees.

Addressing these gaps would make ORC more than another orchestration framework. It
could become a reproducible platform for adaptive compound-AI control in which every
change to prompts, topology, retrieval, and memory is attributable, measurable,
versioned, and reversible.

