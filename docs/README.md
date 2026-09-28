# ORC documentation

ORC is a **laboratory and production line for accountable agentic software**. These guides follow the lab loop: you **build** a workflow, **run** it, keep every step as **evidence**, **judge** it, **improve** it, **harvest** what works, and **operate** it in production.

New to ORC? Read **[Getting Started](GETTING-STARTED.md)**. It is one contract-analysis workflow that grows phase by phase through the whole loop.

---

## 1 · Build: design a workflow

<img src="media/bt-games.gif" alt="A behavior tree ticking in a game" width="360" align="right">

Workflows are behavior trees written as plain Clojure data. Nodes declare what they read and write on a typed blackboard, and stacked sub-behaviors are delegates.

- [Getting Started](GETTING-STARTED.md): the progressive walkthrough, phases 1–6.
- [DSL Reference](DSL-REFERENCE.md): every node and option. Core Concepts is the entry point.
- [ORC Principles](ORC-PRINCIPLES.md): how to compose well, including the node palette, `:delegate`, and events-first.
- [RLM Guide](RLM-GUIDE.md): `repl-researcher`, where a model designs and runs its own subtree.
- [MCP Sheet Builder](MCP-SHEET-BUILDER-GUIDE.md): generating workflows from MCP tool schemas.
- [Pattern Recording](PATTERN-RECORDING.md): recording and reusing patterns by hand.

<br clear="right">

## 2 · Run: execute, stream, recover

<img src="media/run-knowledge-work.gif" alt="An ORC workflow ticking with a live blackboard" width="360" align="right">

The engine ticks the tree. The model thinks inside nodes, and the tree owns order, failure handling and hand-offs.

- [ORC Service Guide](ORC-SERVICE-GUIDE.md): the execution engine, tracing and correlation.
- [Streaming](STREAMING.md): live node and token streams.
- [Value Storage](VALUE-STORAGE.md): production persistence for blackboard values.

<br clear="right">

## 3 · Evidence: every step is an event

<img src="media/bt-robot-events.gif" alt="Decisions streaming into an event store" width="360" align="right">

ORC is built on Grain's event sourcing. Every input, decision and value written is an immutable event. Read models turn that history into search indexes, graphs and datasets.

- [Architecture](ARCHITECTURE.md): ORC and Grain, and how sheets live as event streams.
- [Event Store Patterns](EVENT-STORE-PATTERNS.md): the events and the queries builders use.
- [Contributor Grain Patterns](contributors/CONTRIBUTOR-GRAIN-PATTERNS.md): the complete pattern reference, for contributors.

<br clear="right">

## 4 · Judge: turn a human standard into instrumentation

<img src="media/judges.gif" alt="Judges scoring a node's output" width="360" align="right">

Judges watch the nodes that matter. They write evidence first, then score. They run as event processors, so the work never waits on them.

- [Evaluation](EVALUATION-COMPONENT.md): attaching judges and reading scores back.
- [Judge Architecture](JUDGE-ARCHITECTURE.md): rubric design, custom judges, scales and composite scoring.

<br clear="right">

## 5 · Improve: better instructions, better designs

<img src="media/gepa-loop.gif" alt="GEPA optimizing one node's instruction" width="360" align="right">

GEPA tunes one node's instruction against that node's own judged history. Living Descriptions turn judged evidence into knowledge that shapes future trees. *(Living Descriptions are alpha.)*

- [GEPA Guide](GEPA-GUIDE.md): reflective prompt optimization with a Pareto frontier.
- [Living Descriptions](LIVING-DESCRIPTIONS.md): how nodes and trees come to describe themselves.
- [Ontology](ONTOLOGY.md): the concept graph as memory.
- [Ontology MCP](ONTOLOGY-MCP.md): the concept graph over MCP.
- [ColBERT Integration](COLBERT-INTEGRATION.md): the pure-JVM retrieval signal.

<br clear="right">

## 6 · Harvest: proven patterns become reusable behaviors

<img src="media/harvest.gif" alt="A recurring pattern promoted into a reusable behavior" width="360" align="right">

When a pattern keeps recurring and keeps scoring well, ORC promotes it into a named behavior that other workflows can delegate to. No model is retrained. *(Alpha: the thresholds are still being calibrated.)*

- [Self-Improving Loop](SELF-IMPROVING-LOOP.md): auto-classify, pattern evolution and harvest, with an honest account of current state.

<br clear="right">

## 7 · Operate: packages and roadmap

- [Packages](PACKAGES.md): pull in only the layer you need.
- [Component Map](COMPONENT-MAP.md): the opt-in layers, the dependency graph and known issues.
- [dscloj Migration](DSCLOJ-MIGRATION.md): upgrade notes.
- [Deterministic E2E Checklist](DETERMINISTIC-E2E-TEST-CHECKLIST.md): what is verified end to end.
- [Future Vision](FUTURE-VISION.md): where the lab is heading.

---

<sub>The animations explain real ORC mechanisms using illustrative data; the workbench is an analogy. The Harvest animation shows older gate values. The current defaults are in the [Self-Improving Loop](SELF-IMPROVING-LOOP.md).</sub>
