# ORC Sheet Service Vision & Roadmap

> **Last audited: 2026.** For what's shipped today, see [COMPONENT-MAP.md](COMPONENT-MAP.md) and [GETTING-STARTED.md](GETTING-STARTED.md). This doc describes the forward-looking vision and in-progress work.

> **Reference Document** - When stuck or wondering "What's next?", consult this roadmap.
>
> Created: 2026-03-01 | Last architectural decisions confirmed with Daryl

## Executive Summary

This document captures the comprehensive vision for evolving ORC (behavior tree engine) into a **self-improving AI behavior system** where:
- Behaviors (sheets/trees) are trainable and optimizable
- Each LLM node can be fine-tuned via GEPA
- Trees self-describe and inform future tree building
- Rolling metrics trigger automatic analysis and training
- Conversational debugging allows "talking to traces"

---

## Part 1: Shipping Status

> The original March 2025 "Current State Assessment" (what was and wasn't built at that time) is archived in [archived/FUTURE-VISION-2025.md](archived/FUTURE-VISION-2025.md). For the authoritative current shipping status see [COMPONENT-MAP.md](COMPONENT-MAP.md).

### "What's NOT Built Yet" — 2026 Audit

| Item | Was | Now |
|------|-----|-----|
| **GEPA optimization loop** — Reflection LLM, instruction proposals, Pareto frontier | ~~NOT built~~ | **Shipped** — see [GEPA-GUIDE.md](GEPA-GUIDE.md), `components/gepa/` |
| **Rolling average monitoring** — automatic metric tracking | ~~NOT built~~ | **Shipped** — `get-node-rolling-metrics`, `get-tree-rolling-metrics` in `orc-service`; auto-threshold alerts and training triggers remain forward-looking |
| **Tree self-description** — trees describe what they can solve | ~~NOT built~~ | **Shipped** — see [LIVING-DESCRIPTIONS.md](LIVING-DESCRIPTIONS.md), `components/ontology/` |
| **Tree library/ontology** — searchable library of proven trees | ~~NOT built~~ | **Shipped** — see [ONTOLOGY.md](ONTOLOGY.md), `components/ontology/` (tree profiles, semantic search, RRF + ColBERT hybrid retrieval) |
| **Conversational debugging** — "talk to traces" | NOT built | **Still not shipped** |
| **Personality layer** — customer-facing message filtering | NOT built | **Still not shipped** |

---

## Part 2: The Vision (Organized from Daryl's Notes)

### Theme 1: Skills → Trees Pipeline

**Original Note:** "In skills → trees. We can research with tavily/exa the skills and best use for MCPs if relevant. Use context to turn mcps into trees and other skills steps into other nodes that assembles a full skill tree."

**What This Means:**
- Skills (user-defined capabilities) should become executable behavior trees
- MCP tools can be researched (via Tavily/Exa) to understand best practices
- Skill steps map to ORC nodes (code, llm, etc.)
- Result: A "skill tree" that combines MCPs + custom logic

**Required Features:**
1. Skill → Tree compiler
2. MCP research integration (Tavily/Exa for documentation)
3. Skill step → node type mapper
4. **Dynamic executor generation** - Skills with custom code steps need runtime-generated executors (see MCP Sheet Builder pattern)

---

### Theme 2: Rolling Metric Monitoring & Auto-Training

**Original Note:** "We can monitor a rolling average in the event store on premade metrics, if it dips below some threshold then we analyze with failure ontology build dataset and train out issue."

**What This Means:**
- Event store tracks rolling averages of node/tree performance
- Premade metrics: success rate, grounding score, instruction compliance, etc.
- When metrics dip below threshold → trigger analysis
- Failure ontology classifies issues → builds training dataset
- Auto-training cycle fixes the issue

**Required Features:**
1. Rolling average read model (per node, per tree)
2. Threshold configuration system
3. Failure ontology (taxonomy of failure types)
4. Dataset builder from traces
5. Auto-training trigger mechanism

---

### Theme 3: Best-of-N Tree Generation

**Original Note:** "Thinking of new trees get best of n where we feed the worse responses chosen responses reasoning scores etc into an annual training loop where we build an initial synthetic dataset based on the objectives of the tree, best of n, refine initial instructions, new tree is born."

**What This Means:**
- When creating new trees, generate N candidates
- Score each candidate against objectives
- Feed worst/best responses + reasoning into training loop
- Build synthetic dataset from tree objectives
- Refine instructions iteratively
- Final "best" tree emerges

**Required Features:**
1. Multi-candidate tree generation
2. Tree objective schema
3. Candidate scoring/ranking
4. Synthetic dataset generator
5. Instruction refinement loop

---

### Theme 4: Tree Analysis & Optimization

**Original Note:** "Take sheets and their runs and analyze all metrics and chains per run for all nodes. Find where the tree could be more efficient or have additional steps added (ie validation, additional research steps, request more loop steps, common issues in certain nodes, spark instruction tuning (if enough new data since the last run) to 'optimize trees' perhaps even say if certain nodes would be more effective if they had access to x information being passed to the blackboard at x timestep (since all ticks we will see what info is available per timestamp) and can recreate the entire workflow."

**What This Means:**
- Comprehensive analysis across all runs
- Per-node metrics (success rate, duration, common errors)
- Efficiency analysis (could tree be shorter/faster?)
- Gap analysis (missing validation, research, loops?)
- Information availability analysis (what blackboard state at each tick?)
- Instruction tuning triggers (enough new data since last tune?)
- State recreation at any timestep

**Required Features:**
1. Per-node aggregate metrics read model
2. Efficiency analyzer (bottleneck detection)
3. Gap analyzer (missing steps suggestion)
4. Timestep state reconstruction
5. Information availability graph
6. Instruction tuning scheduler

---

### Theme 5: Conversational Tree Debugging

**Original Note:** "If we are looking at a trace we can talk to our root tree and say something like 'on trace x I see node n did this when it should have done something like this'. We can recreate the state of the entire bt at that timestep to inform the optimizers/judges. 'You're right let's take a look at that nodes performance' we already have the rolling average in the event store as described above but if there are ones in the queue which haven't been scored yet let's go ahead and score them. Check on the judges and see if we need to add a new judge to check for some new failure reason. Adjust given judge and if an adjustment is made we rerun (with permission) a troubleshooting test. Come back with some score and optimized instruction/data profile. Perhaps even hit another training phase. Fix the node (remember to check if this is a common failure pattern or a one off caused by something not common)"

**What This Means:**
- Conversational interface for debugging traces
- Point to specific trace + node → discuss issues
- System recreates full BT state at that timestep
- Check rolling averages, score unscored traces
- Analyze if new judge needed for new failure type
- Test adjustments, get scores, potentially retrain
- Distinguish one-off failures from common patterns

**Required Features:**
1. Conversational debug interface (agent/sheet)
2. Timestep state recreation
3. Unscored trace detection + batch scoring
4. Failure pattern classifier (one-off vs common)
5. Judge adequacy analyzer
6. Permission-gated retraining

---

### Theme 6: Tree Hierarchy & Self-Description

**Original Note:** "Don't forget, we can take established trees and find what the architecture is research → research Wikipedia → research marvels wiki → research Spider-Man wiki → etc. When we have any tree builder submit a new tree subtree pattern we make sure to self describe it, what types of objectives/problems it can solve and what it's capable of. Then as we go it's updating with the types of problems it has successfully solved along with avg metrics so we can further show what behaviors are good/bad. Then we can use that info in our ontology to inform us more about building future trees (for all tree builders, MCP, skills, etc) and in our folder structure we have a core sheets library organized in the waterfall like mentioned above. The model uses rrf with description/objective/problem embeddings and graph traversal to find the most optimal example successful trees and common failures from proven bad trees to then provide few shot to our tree builder steps"

#### Runtime Tree Delegation (Implemented)

In addition to organizational hierarchy, ORC supports **runtime delegation**
where one tree can execute another tree with its own isolated blackboard:

```clojure
(orc/delegate "process-subtask"
  :target-sheet-id child-sheet-uuid
  :reads [:input-data :context]      ;; Pass to child
  :writes [:result :metrics]          ;; Receive from child
  :timeout-ms 60000
  :inherit-ontology? true)            ;; Share learned patterns
```

**Key features:**
- **Isolated blackboard** - Child doesn't see parent state directly
- **Input/output mapping** - `:reads` passes data to child, `:writes` receives back
- **Timeout enforcement** - Per-delegation timeout (default 5 minutes)
- **Ontology inheritance** - Child can access parent's learned patterns
- **Full tracing** - Delegation generates lifecycle events for observability

**Use cases:**
- Decompose complex workflows into reusable subworkflows
- Enable A/B testing with different sub-tree implementations
- Parallelize work across multiple specialized trees
- Isolate failure domains (child failure doesn't corrupt parent state)

**What This Means:**
- Trees form hierarchies (parent → child specializations)
- Every tree/subtree has self-description metadata:
  - What objectives it can solve
  - What problems it handles
  - Capabilities list
- Descriptions update over time with:
  - Actual problems solved
  - Average metrics
  - Success/failure history
- Core sheets library organized by hierarchy
- Retrieval: RRF + embeddings + graph traversal
- Few-shot examples from proven trees
- Counter-examples from proven bad trees

**Required Features:**
1. Tree self-description schema
2. Tree hierarchy graph
3. Dynamic description updater
4. Core sheets library structure
5. Embedding-based tree retrieval
6. RRF + graph traversal search
7. Few-shot example selector
8. **Dynamic executor generation** - Every tree builder must generate and persist executors for code nodes (foundational infrastructure from MCP Sheet Builder)

---

### Theme 7: Personality & Customer-Facing Messages

**Original Note:** "There can be an output field for when we want customer facing messages to send (like thinking, nebulizing, etc) that takes our version of 'soul.md' and generates a forward message. In the tree we keep the context to purely analysis/data but for issues, next steps, forward facing reasoning we pass it through the personality. Then for any front facing messages inside pipelines we can always just have a personalize step (best of n or allow streaming?) that has a judge making sure all of the info from the report tree/synthesizer is conveyed to the user (but with the quirks of the personality being the mailman on that report)"

**What This Means:**
- Separate concerns: analysis/data vs customer-facing output
- "Soul.md" defines personality/voice
- Personalize step transforms data → friendly message
- Best-of-N generation for quality
- Streaming option for real-time
- Judge ensures information completeness despite personality filter

**Required Features:**
1. Soul.md personality configuration
2. Personalize node type (or code executor)
3. Best-of-N message generation
4. Streaming personalization
5. Information completeness judge

---

### Theme 8: Failure/Success Ontologies (Anterior-Inspired)

**Inspiration:** Christopher Lovejoy's talk on "Adaptive Domain Intelligence Engine" at Anterior - a system where failure modes are captured in an ontology, domain experts tag failures, and the system learns from categorized examples.

**What This Means:**
Trees should self-describe what they're good and bad at via ontological classification:

1. **Three-Layer Ontology System:**
   - **Failure Ontology:** Why things go wrong (Hallucination, InstructionViolation, ReasoningDefect, CompletenessFailure, ContextFailure)
   - **Success Ontology:** What makes things work (StructuralPatterns, InstructionPatterns, DataFlowPatterns)
   - **Problem Domain Ontology:** What types of problems exist (InformationRetrieval, ContentGeneration, Analysis, Workflow)

2. **Tree-Level Profiles:**
   - Strengths: What patterns this tree excels at (with confidence scores)
   - Weaknesses: What failure types this tree encounters (with frequency, severity, triggers)
   - Solves: What problem types this tree handles (with success rates)
   - Domain Knowledge: Learned insights that improved performance

3. **Node-Type Learning:**
   - Patterns shared across all nodes of the same type (:llm, :search, :code)
   - Effective vs ineffective query patterns for search nodes
   - Effective vs ineffective instruction patterns for LLM nodes
   - Common errors and fixes for code executor nodes

4. **Event-Sourced Ontologies:**
   - Ontology concepts stored as Grain events
   - Read models reconstruct ontology state
   - On-demand TTL/SKOS export for visualization/tooling
   - Tree profiles built from evaluation → classification → recording events

5. **Tree Builder Integration:**
   - Few-shot retrieval: Find similar successful trees by problem type
   - Failure patterns to avoid: Aggregate common failures for problem type
   - Context enhancement: Inject examples + anti-patterns into builder prompts

**Required Features:**
1. Static core ontology (failure types, success patterns, problem domains)
2. Ontology event schemas (concept-created, relationship-created, tree-weakness-recorded, etc.)
3. Ontology read models (concepts graph, tree profiles, node experiences)
4. Failure classifier (evaluation result → ontology concepts)
5. Tree profile recorder (evaluation → classification → event emission)
6. TTL serializer (read model state → SKOS/OWL Turtle)
7. Few-shot retriever (problem type → successful tree examples)
8. Context enhancer (inject ontology context into tree builder prompts)
9. LLM pattern discovery (discover new failure subtypes over time)

**Key Insight:**
This is Anterior's "Failure Mode Ontology" + "Ready-made Datasets" pattern applied to behavior trees. Every failure gets categorized, every success gets captured, and the system uses this structured knowledge to improve future tree building.

---

### Theme 9: Enhanced Retrieval with ColBERT (RAGatouille Integration)

**Status:** Planned (Phase 4a Enhancement)

**Original Note:** "The ontology mechanism (with rrf) is using embeddings for its search within memory etc already. So the likely integration for ragatouille is integration with the ontology based memory that will already be used internally for all retrieval whether it be custom documents or internal mechanisms for preexisting trees."

**What This Means:**
The existing RRF-based hybrid search (graph + embeddings) gains a third signal: ColBERT late-interaction retrieval for superior semantic matching. ColBERT provides token-level matching instead of single-vector embeddings, offering better relevance for complex queries.

**Architecture:**
```
                       Query
                         │
         ┌───────────────┼───────────────┐
         │               │               │
         ▼               ▼               ▼
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│ Graph BFS   │  │ MiniLM      │  │ ColBERT     │
│ (spreading  │  │ (384-dim    │  │ (token-level│
│ activation) │  │ sentence)   │  │ late-inter) │
└──────┬──────┘  └──────┬──────┘  └──────┬──────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
               ┌────────────────┐
               │ RRF Fusion     │
               │ (k=60, weights)│
               └────────────────┘
                        │
                        ▼
                 Ranked Results
```

**Why Python Bridge (Not Pure Clojure):**
ColBERT is fundamentally different from a callable API:
1. **Neural Network** - 110M parameter PyTorch transformer model
2. **PLAID Index** - Specialized compression algorithm (2000+ lines), not a data format
3. **Training** - Requires PyTorch autograd for gradient computation
4. **No JVM Equivalent** - ONNX/DJL can't replicate PLAID indexing or training

The Python bridge adds ~50ms latency per call (negligible vs model inference time) while providing full RAGatouille functionality.

**Key Features:**
1. **Three-Signal Hybrid Search** - Graph BFS + MiniLM + ColBERT via existing RRF
2. **Tree Profile Indexing** - Index tree self-descriptions for few-shot retrieval
3. **Domain Training** - Fine-tune ColBERT on tree success/failure data (pairs/triplets)
4. **Event-Sourced** - Full audit trail of index/search/training operations
5. **Reranking** - In-memory rerank without index for candidate selection

**Integration Points:**
- Extends `ontology/hybrid-search` with optional ColBERT signal
- Uses existing `compute-rrf-scores` for fusion (no changes to RRF logic)
- Training data extracted from successful trace evaluation pairs
- Index lifecycle tracked via Grain events

**Required Features:**
1. ColBERT component with Python bridge (`components/colbert/`)
2. Index lifecycle events (`:colbert/index-created`, `:colbert/search-performed`, etc.)
3. Hybrid search extension in ontology (add ColBERT batch to RRF)
4. Training data extraction from traces (pairs with min-score filter)
5. Hard negative mining (using existing DJL embeddings)
6. Document splitting (matching RAGatouille's LlamaIndex splitter logic)

**Component Structure:**
```
components/colbert/
├── interface.clj                 # Public API
├── interface/schemas.clj         # Events, commands, queries
└── core/
    ├── bridge.clj                # Python subprocess (JSON-RPC)
    ├── corpus_processor.clj      # Document splitting
    ├── training_data_processor.clj # Triplet generation
    ├── negative_miner.clj        # Hard negative mining (DJL-based)
    └── read_models.clj           # Event projections
```

**Use Cases:**
- Semantic search over internal documentation
- Tree profile retrieval for few-shot examples
- Reranking LLM-generated candidates by relevance
- Training domain-specific retrievers on tree success/failure data

**Dependency:** Phase 4a (Ontology) for tree profiles, existing RRF infrastructure

---

## Part 3: Feature Dependency Map

```
                    ┌─────────────────────────────────────────┐
                    │ FOUNDATION LAYER (Build First)          │
                    ├─────────────────────────────────────────┤
                    │ 1. GEPA Integration                     │
                    │    - Reflection LLM                     │
                    │    - Instruction proposals              │
                    │    - Pareto frontier                    │
                    │                                          │
                    │ 2. Rolling Metric Read Model            │
                    │    - Per-node aggregates                │
                    │    - Threshold configuration            │
                    │                                          │
                    │ 3. Tree Self-Description Schema         │
                    │    - Objectives, capabilities           │
                    │    - Problem types                       │
                    └──────────────┬──────────────────────────┘
                                   │
          ┌────────────────────────┼────────────────────────┐
          │                        │                         │
          ▼                        ▼                         ▼
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│ OPTIMIZATION    │    │ MONITORING      │    │ LIBRARY         │
│ LAYER           │    │ LAYER           │    │ LAYER           │
├─────────────────┤    ├─────────────────┤    ├─────────────────┤
│ 4. Failure      │    │ 7. Auto-trigger │    │ 10. Tree        │
│    Ontology     │◄───┤    System       │    │     Hierarchy   │
│                 │    │                 │    │     Graph       │
│ 5. Dataset      │    │ 8. Threshold    │    │                 │
│    Builder      │    │    Alerts       │    │ 11. Embedding   │
│                 │    │                 │    │     Index       │
│ 6. Best-of-N    │    │ 9. Unscored     │    │                 │
│    Generator    │    │    Scorer       │    │ 12. RRF Search  │
└────────┬────────┘    └────────┬────────┘    └────────┬────────┘
         │                      │                       │
         └──────────────────────┼───────────────────────┘
                                │
                                ▼
                    ┌─────────────────────────────────────────┐
                    │ ONTOLOGY LAYER (Anterior-Inspired)       │
                    ├─────────────────────────────────────────┤
                    │ O1. Static Core Ontology                │
                    │     - Failure types (SKOS hierarchy)    │
                    │     - Success patterns                  │
                    │     - Problem domains                   │
                    │                                          │
                    │ O2. Ontology Event Store                │
                    │     - concept-created events            │
                    │     - tree-weakness-recorded events     │
                    │     - node-pattern-learned events       │
                    │                                          │
                    │ O3. Tree Profile Read Model             │
                    │     - Strengths (what tree excels at)   │
                    │     - Weaknesses (failure patterns)     │
                    │     - Problem mappings (what it solves) │
                    │                                          │
                    │ O4. Node-Type Learning                  │
                    │     - Shared patterns per :llm/:search  │
                    │     - Effective vs ineffective patterns │
                    │                                          │
                    │ O5. Failure Classifier                  │
                    │     - Evaluation → ontology concepts    │
                    │     - Auto-tagging from judge feedback  │
                    │                                          │
                    │ O6. Few-Shot Retriever                  │
                    │     - Problem type → successful trees   │
                    │     - Patterns to avoid from failures   │
                    │     - Inject into tree builder prompts  │
                    │                                          │
                    │ O7. TTL/SKOS Exporter                   │
                    │     - On-demand serialization           │
                    │     - Graph visualization support       │
                    │                                          │
                    │ O8. LLM Pattern Discovery               │
                    │     - Discover new failure subtypes     │
                    │     - Extend ontology over time         │
                    └──────────────┬──────────────────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────────────────────┐
                    │ INTELLIGENCE LAYER                       │
                    ├─────────────────────────────────────────┤
                    │ 13. Tree Analyzer                       │
                    │     - Efficiency analysis               │
                    │     - Gap detection                     │
                    │     - Information availability          │
                    │                                          │
                    │ 14. Conversational Debugger             │
                    │     - Trace discussion                  │
                    │     - State reconstruction              │
                    │     - Pattern classification            │
                    │                                          │
                    │ 15. Judge Adequacy Analyzer             │
                    │     - Missing judge detection           │
                    │     - New failure types                 │
                    └──────────────┬──────────────────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────────────────────┐
                    │ EXPERIENCE LAYER                         │
                    ├─────────────────────────────────────────┤
                    │ 16. Skills → Trees Pipeline             │
                    │     - Skill compiler                    │
                    │     - MCP research integration          │
                    │                                          │
                    │ 17. Personality Layer                   │
                    │     - Soul.md configuration             │
                    │     - Personalize node                  │
                    │     - Best-of-N + Judge                 │
                    └─────────────────────────────────────────┘
```

---

## Part 4: Prioritized Feature Roadmap

> **Foundational Infrastructure (Complete):** Dynamic executor generation is now implemented in MCP Sheet Builder. This capability - generating Clojure code at runtime, storing it in the event store, and loading it into isolated namespaces - is **essential for all tree builders**. Without it, trees can only reference pre-existing functions, severely limiting dynamic capabilities. See `docs/MCP-SHEET-BUILDER-GUIDE.md` Part 7 for details.

### Phase 1: GEPA Foundation (Critical Path)
**Goal:** Connect evaluation to optimization

| Feature | Description | Dependencies | Effort |
|---------|-------------|--------------|--------|
| **1.1 Reflection Sheet** | LLM analyzes low-scoring traces, identifies patterns | Evaluation component | Medium |
| **1.2 Instruction Proposal** | Generate improved instructions from reflection | 1.1 | Medium |
| **1.3 Pareto Frontier** | Track multiple instruction candidates per node | 1.2 | Medium |
| **1.4 A/B Execute Command** | Run two versions side-by-side, compare | Sheet versioning | Low |
| **1.5 Optimization Read Model** | Track proposals, experiments, results | 1.3, 1.4 | Medium |

### Phase 2: Monitoring Infrastructure
**Goal:** Automatic metric tracking with alerts

| Feature | Description | Dependencies | Effort |
|---------|-------------|--------------|--------|
| **2.1 Rolling Metric Model** | Per-node rolling averages (success rate, scores, duration) | Event store | Medium |
| **2.2 Threshold Config** | Define thresholds per node/tree | 2.1 | Low |
| **2.3 Alert Events** | Emit events when thresholds crossed | 2.2 | Low |
| **2.4 Auto-Analysis Trigger** | On alert → queue for analysis | 2.3, 1.1 | Medium |

### Phase 3: Tree Library & Self-Description
**Goal:** Trees describe themselves and are searchable

| Feature | Description | Dependencies | Effort |
|---------|-------------|--------------|--------|
| **3.1 Self-Description Schema** | objectives, capabilities, problem_types, success_history | Schema design | Low |
| **3.2 Tree Registration** | On build, extract/generate description | 3.1 | Medium |
| **3.3 Description Updater** | After execution, update success/failure stats | 3.2, event store | Medium |
| **3.4 Tree Hierarchy Graph** | Parent/child relationships, specializations | 3.2 | Medium |
| **3.5 Embedding Index** | Embed descriptions for semantic search | 3.2 | Medium |
| **3.6 RRF + Graph Search** | Combined retrieval for few-shot examples | 3.4, 3.5 | High |

### Phase 4: Optimization Intelligence
**Goal:** Analyze trees and suggest improvements

| Feature | Description | Dependencies | Effort |
|---------|-------------|--------------|--------|
| **4.1 Basic Failure Taxonomy** | Core failure types: hallucination, instruction_fail, reasoning_gap, etc. | Evaluation judges | Medium |
| **4.2 Dataset Builder** | From failure traces → training examples | 4.1 | Medium |
| **4.3 Best-of-N Generator** | Generate N tree candidates, score, select | Tree builder | High |
| **4.4 Tree Analyzer** | Efficiency, gaps, information availability | Trace data | High |
| **4.5 Timestep Reconstructor** | Recreate full BT state at any point | Trace data | Medium |

### Phase 4a: Ontology Knowledge System (Anterior-Inspired)
**Goal:** Self-improving trees via structured failure/success ontologies

This phase builds on the basic failure taxonomy (4.1) to create a comprehensive ontology-based learning system.

| Feature | Description | Dependencies | Effort |
|---------|-------------|--------------|--------|
| **4a.1 Static Core Ontology** | SKOS hierarchy for failure types, success patterns, problem domains | 4.1 | Medium |
| **4a.2 Ontology Event Schemas** | concept-created, tree-weakness-recorded, node-pattern-learned events | Grain event store | Low |
| **4a.3 Ontology Read Models** | Concepts graph, tree profiles, node experiences from events | 4a.2 | Medium |
| **4a.4 Failure Classifier** | Map evaluation results (4 judges) to ontology concepts | 4a.1, Evaluation | Medium |
| **4a.5 Tree Profile Recorder** | Emit tree-strength/weakness events from evaluations | 4a.4, 4a.3 | Medium |
| **4a.6 Node-Type Learning** | Aggregate patterns across all :llm, :search, :code nodes | 4a.3 | Medium |
| **4a.7 TTL/SKOS Exporter** | On-demand serialization from read model → Turtle | 4a.3 | Low |
| **4a.8 Few-Shot Retriever** | Query tree profiles for similar successful trees | 4a.5, 3.5 | Medium |
| **4a.9 Context Enhancer** | Inject ontology examples into tree builder prompts | 4a.8, MCP builder | Medium |
| **4a.10 LLM Pattern Discovery** | Discover new failure subtypes, extend ontology over time | 4a.1, 4a.4 | High |

**Reuses Existing Infrastructure:**
- `development/src/ontology_exploration.clj` - SKOS serialization, taxonomy pipeline
- `development/src/graph_search.clj` - BFS spreading activation, RRF scoring
- `development/src/unified_ontology.clj` - Multi-source unification patterns

### Phase 5: Conversational Debugging
**Goal:** Natural language interface for trace analysis

| Feature | Description | Dependencies | Effort |
|---------|-------------|--------------|--------|
| **5.1 Debug Agent Sheet** | Conversation-driven trace investigation | 4.5 | High |
| **5.2 Pattern Classifier** | One-off vs common failure detection | 4.1, 2.1 | Medium |
| **5.3 Judge Adequacy Check** | Detect if new judge type needed | 5.2, evaluation | Medium |
| **5.4 Permission-Gated Actions** | Request permission before retraining | 5.3 | Low |

### Phase 6: Experience Layer
**Goal:** Skills compilation and personality

| Feature | Description | Dependencies | Effort |
|---------|-------------|--------------|--------|
| **6.1 Skill → Tree Compiler** | Transform skill definitions to ORC trees | MCP builder | High |
| **6.2 MCP Research Integration** | Use Tavily/Exa to research tool usage | 6.1 | Medium |
| **6.3 Soul.md Config** | Personality definition format | Schema design | Low |
| **6.4 Personalize Executor** | Transform data → friendly message | 6.3 | Medium |
| **6.5 Best-of-N Personalization** | Generate N messages, judge selects best | 6.4, evaluation | Medium |

---

## Part 5: Architectural Decisions (Confirmed)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **GEPA Training Target** | Both - Start with Prompts | Begin with prompt optimization, extend to fine-tuning as infrastructure matures |
| **Rolling Metric Granularity** | Per-node + Per-tree | Good balance of detail and actionability |
| **Conversational Debug Interface** | REPL-first | Quick to build, integrates with existing workflow, extend later |
| **Personality Layer Priority** | Medium | After core GEPA + monitoring works |
| **Tree Library Scope** | Internal Registry | Shareable across projects, build organizational knowledge |
| **Phase 1 Focus** | GEPA Optimization | Self-improving existing trees before new tree generation |
| **Ontology Seeding** | Static Core + LLM Discovery | Define core failure types statically, let LLM discover subtypes over time |
| **Node Learning Granularity** | By Node Type | Learn patterns for :llm, :search, :code as categories - shared learnings across instances |
| **TTL Export Timing** | On-Demand via Query | Events are source of truth, reconstruct TTL when explicitly requested |
| **Ontology Infrastructure** | Extend Existing | Build on existing ontology_exploration.clj SKOS/OWL and graph_search.clj infrastructure |
| **Tree Profile Storage** | Event-Sourced | Strengths/weaknesses as Grain events, reconstruct via read models |
| **Few-Shot Integration** | Context Enhancement | Inject ontology knowledge into tree builder prompts, not model weights |
| **Dynamic Executor Generation** | Event-Sourced + Runtime Loading | Tree builders can't function with static executors only - MCP tools, skills, and LLM-generated code all require runtime loading |

---

## Part 6: The Full Vision Realized

```
┌─────────────────────────────────────────────────────────────────────┐
│                        USER DEFINES SKILLS                           │
│        "I want a lead qualifier that researches and scores"         │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    SKILLS → TREES COMPILER                           │
│  - Research MCPs with Tavily/Exa                                    │
│  - Map skill steps to ORC nodes                                     │
│  - Use tree library for few-shot examples                           │
│  - Generate behavior tree                                           │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    BEST-OF-N GENERATION                              │
│  - Generate N candidate trees                                       │
│  - Score against objectives                                         │
│  - Synthetic dataset from objectives                                │
│  - Refine, select best                                              │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    PRODUCTION EXECUTION                              │
│  - Tree executes with full tracing                                  │
│  - Every node: inputs, outputs, timing                              │
│  - Stored in Grain event store                                      │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
         ┌───────────────────────┴───────────────────────┐
         ▼                                               ▼
┌─────────────────────────┐               ┌─────────────────────────┐
│   ROLLING METRICS       │               │   EVALUATION            │
│   - Per-node averages   │               │   - 4 dimension judges  │
│   - Per-tree aggregates │               │   - ScoreWithFeedback   │
│   - Threshold alerts    │               │   - Retrospective       │
└───────────┬─────────────┘               └───────────┬─────────────┘
            │                                         │
            └────────────────┬────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│              ONTOLOGY KNOWLEDGE SYSTEM (Anterior-Inspired)          │
│  - Failure classifier: evaluation → ontology concepts               │
│  - Tree profiles: strengths, weaknesses, problem mappings          │
│  - Node-type learning: shared patterns across :llm, :search, :code │
│  - Event-sourced: tree-weakness-recorded, node-pattern-learned      │
│  - TTL/SKOS export: on-demand serialization for visualization      │
│  - Few-shot retriever: find similar successful trees               │
│  - LLM discovery: auto-extend ontology with new failure subtypes   │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    GEPA OPTIMIZATION                                 │
│  - Reflection LLM analyzes failures                                 │
│  - Identifies patterns (hallucination, instruction gaps)           │
│  - Proposes improved instructions                                   │
│  - A/B tests proposals                                              │
│  - Updates Pareto frontier                                          │
│  - Publishes new versions                                           │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    TREE LIBRARY                                      │
│  - Self-describes objectives, capabilities                          │
│  - Updates with success/failure history                             │
│  - Hierarchy: research → research-wiki → research-spider-man       │
│  - RRF + embedding search for few-shot                              │
│  - Counter-examples from proven bad trees                           │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    CONVERSATIONAL DEBUG                              │
│  "On trace X, node N did this wrong..."                             │
│  - Recreate state at timestep                                       │
│  - Check rolling averages                                           │
│  - Score unscored traces                                            │
│  - Classify: one-off vs common pattern                              │
│  - Suggest new judges if needed                                     │
│  - Permission-gated retraining                                      │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    PERSONALITY LAYER                                 │
│  - Soul.md defines voice                                            │
│  - Analysis stays pure data                                         │
│  - Forward-facing messages personalized                             │
│  - Best-of-N + completeness judge                                   │
│  - "Quirks of personality as mailman"                               │
└─────────────────────────────────────────────────────────────────────┘
```

**The cycle is self-reinforcing:**
- Trees execute → metrics collected → issues detected → GEPA improves → better trees
- New trees built → learn from library → outperform old ones → update library
- Failures analyzed → ontology grows → better judges → better feedback → better GEPA
- **Ontology captures knowledge → tree builders get few-shot examples → better new trees → more knowledge**
- **Node-type learning → all :llm nodes share learnings → all :search nodes share learnings → faster convergence**

---

## Quick Reference: What To Build Next

When you're stuck, check which phase you're in and what's next:

1. **Phase 1 (GEPA):** Reflection sheet → Instruction proposals → Pareto frontier → A/B testing
2. **Phase 2 (Monitoring):** Rolling metrics → Thresholds → Alerts → Auto-triggers
3. **Phase 3 (Library):** Self-description → Registration → Hierarchy → Search
4. **Phase 4 (Intelligence):** Basic failure taxonomy → Dataset builder → Best-of-N → Tree analyzer
5. **Phase 4a (Ontology):** Static core → Event schemas → Read models → Classifier → Tree profiles → Few-shot → TTL export → Discovery
6. **Phase 4b (ColBERT):** Python bridge → Index management → Ontology integration → Training pipeline → ORC integration
7. **Phase 5 (Debug):** Debug agent → Pattern classifier → Judge adequacy
8. **Phase 6 (Experience):** Skills compiler → Personality layer

**Start with Phase 1** - everything else builds on GEPA working.

**Phase 4a (Ontology)** can begin after Phase 4.1 (basic failure taxonomy) is complete. It runs in parallel with the rest of Phase 4 and Phase 5, providing increasingly valuable context to tree builders as the ontology grows.

**Phase 4b (ColBERT)** can begin after Phase 4a establishes tree profiles. It adds ColBERT as a third retrieval signal to the existing RRF hybrid search, enabling superior semantic matching for few-shot retrieval and domain-specific training.

**Dynamic Executor Generation (Complete):** This foundational infrastructure is already implemented in MCP Sheet Builder. All tree builders (Skills→Trees, Ontology-enhanced, GEPA) will reuse this pattern for generating and persisting code node executors.

