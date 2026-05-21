# ORC Sheet Service Vision & Roadmap

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

## Part 1: Current State Assessment

### What's Built & Working

| Component | Status | Key Capabilities |
|-----------|--------|------------------|
| **Sheet Service (ORC)** | ✅ Production | Behavior trees, all node types, versioning, full tracing |
| **Grain Event Store** | ✅ Production | Immutable events, read models, trace storage |
| **Evaluation Component** | ✅ Working | 4 judges (grounding, instruction, reasoning, completeness), ScoreWithFeedback |
| **MCP Sheet Builder** | ✅ Complete (5 phases) | Intent analysis, pattern selection, tool relevance, REPL Researcher |
| **DSCloj** | ✅ Integrated | Clojure-native DSPy, explicit schemas, output flattening |
| **Langfuse Integration** | ✅ Working | External observability, token tracking |

### Key Infrastructure Already Available

1. **Full execution tracing** - Every node execution captured with inputs/outputs/timing
2. **Sheet versioning** - Publish versions (v1, v2, v3...), execute specific versions, A/B ready
3. **Trace extraction** - Query historical LLM node executions from event store
4. **ScoreWithFeedback** - GEPA-compatible evaluation format with actionable feedback
5. **Intent analysis** - LLM-based understanding of user goals
6. **Pattern library** - 8 agent patterns with relationship-aware generation

### What's NOT Built Yet

1. **GEPA optimization loop** - Reflection LLM, instruction proposals, Pareto frontier
2. **Rolling average monitoring** - No automatic metric tracking with thresholds
3. **Tree self-description** - Trees don't describe what they can solve
4. **Tree library/ontology** - No searchable library of proven trees
5. **Conversational debugging** - Can't "talk to traces"
6. **Personality layer** - No customer-facing message filtering

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

---

## Appendix: Ontology Component Structure

When implementing Phase 4a, create the following component structure:

```
components/ontology/
├── deps.edn
└── src/ai/obney/workshop/ontology/
    ├── interface.clj                 ;; Public API
    ├── interface/schemas.clj         ;; Event schemas
    └── core/
        ├── static_ontology.clj       ;; Core failure/success concepts
        ├── read_models.clj           ;; Event → state reconstruction
        ├── serialization.clj         ;; TTL export (reuse from ontology_exploration)
        ├── classifier.clj            ;; Evaluation → failure type mapping
        ├── recorder.clj              ;; Emit ontology events
        ├── retrieval.clj             ;; Few-shot retrieval
        ├── discovery.clj             ;; LLM pattern discovery
        └── sheets.clj                ;; ORC workflows for ontology ops
```

**Reuse existing infrastructure:**
- `development/src/ontology_exploration.clj` - SKOS serialization functions
- `development/src/graph_search.clj` - BFS spreading activation, RRF scoring
- `development/src/unified_ontology.clj` - Multi-source unification patterns

---

## Appendix B: Ontology Implementation Reference

### B.1 The Anterior Pattern Applied to Trees

**Key Insights from Christopher Lovejoy's Talk:**
1. **Failure Mode Ontology** - Categorize all the ways AI can fail, not just "it failed"
2. **Domain Expert Dashboard** - Review failures and tag with failure mode
3. **Failure → Metric Correlation** - Which failure modes cause the most impact?
4. **Ready-made Datasets** - Each failure mode becomes an eval set for iteration
5. **Domain Knowledge Suggestions** - Experts add knowledge that fixes specific failure patterns

**Translation to Trees/Sheets:**

| Anterior Concept | Tree/Sheet Equivalent |
|------------------|----------------------|
| **False Approvals** (north star) | Low-scoring executions (aggregate < 0.7) |
| **Medical Record Extraction** (failure category) | Input Processing Failures |
| **Clinical Reasoning** (failure category) | LLM Reasoning Failures |
| **Rules Interpretation** (failure category) | Instruction Following Failures |
| **Conservative Therapy (ambiguity)** | Schema Ambiguity, Context Gaps |
| **Domain Knowledge Addition** | Tree/Node Instruction Refinement |

---

### B.2 Three-Layer Ontology Taxonomy

```
┌─────────────────────────────────────────────────────────────────────┐
│ LAYER 1: FAILURE ONTOLOGY                                           │
│ "Why things go wrong"                                                │
├─────────────────────────────────────────────────────────────────────┤
│ failure:Hallucination                                                │
│   ├── failure:FactHallucination                                     │
│   ├── failure:RelationshipHallucination                             │
│   └── failure:NumberHallucination                                   │
│ failure:InstructionViolation                                        │
│   ├── failure:FormatViolation                                       │
│   ├── failure:ConstraintViolation                                   │
│   └── failure:RequirementMissed                                     │
│ failure:ReasoningDefect                                             │
│   ├── failure:LogicalGap                                            │
│   ├── failure:UnjustifiedLeap                                       │
│   └── failure:CircularReasoning                                     │
│ failure:CompletenessFailure                                         │
│   ├── failure:MissingEntity                                         │
│   ├── failure:InsufficientDetail                                    │
│   └── failure:TruncatedOutput                                       │
│ failure:ContextFailure                                              │
│   ├── failure:MissingContext                                        │
│   ├── failure:IgnoredContext                                        │
│   └── failure:MisinterpretedContext                                 │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ LAYER 2: SUCCESS ONTOLOGY                                           │
│ "What makes things work"                                             │
├─────────────────────────────────────────────────────────────────────┤
│ success:PatternCategory                                             │
│   ├── success:ResearchPattern                                       │
│   │   ├── success:MultiSourceGathering                              │
│   │   ├── success:IterativeRefinement                               │
│   │   └── success:ValidationLoop                                    │
│   ├── success:AnalysisPattern                                       │
│   │   ├── success:StructuredDecomposition                           │
│   │   ├── success:ComparativeAnalysis                               │
│   │   └── success:SynthesisAggregation                              │
│   └── success:ExecutionPattern                                      │
│       ├── success:ParallelIndependent                               │
│       ├── success:SequentialPipeline                                │
│       └── success:FallbackRecovery                                  │
│                                                                      │
│ success:EffectiveTechnique                                          │
│   ├── success:ExplicitSchemaDefinition                              │
│   ├── success:FewShotExamples                                       │
│   ├── success:ChainOfThought                                        │
│   ├── success:ValidationStep                                        │
│   └── success:RetryWithFeedback                                     │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ LAYER 3: PROBLEM DOMAIN ONTOLOGY                                    │
│ "What types of problems exist"                                       │
├─────────────────────────────────────────────────────────────────────┤
│ problem:Category                                                     │
│   ├── problem:InformationRetrieval                                  │
│   │   ├── problem:DocumentSearch                                    │
│   │   ├── problem:DataExtraction                                    │
│   │   └── problem:KnowledgeQuery                                    │
│   ├── problem:ContentGeneration                                     │
│   │   ├── problem:Summarization                                     │
│   │   ├── problem:Translation                                       │
│   │   └── problem:CreativeWriting                                   │
│   ├── problem:Analysis                                              │
│   │   ├── problem:Classification                                    │
│   │   ├── problem:Scoring                                           │
│   │   └── problem:Comparison                                        │
│   └── problem:Workflow                                              │
│       ├── problem:MultiStepProcess                                  │
│       ├── problem:ConditionalBranching                              │
│       └── problem:IterativeRefinement                               │
└─────────────────────────────────────────────────────────────────────┘
```

---

### B.3 Tree-Level Ontology Profile Data Structure

Each tree/sheet gets an **ontology profile** that evolves over time:

```clojure
{:tree-id UUID
 :name "lead-qualification"
 :version 3

 ;; What this tree is good at (success ontology)
 :strengths
 [{:pattern :success/MultiSourceGathering
   :confidence 0.92
   :evidence-count 150
   :avg-score 0.88}
  {:pattern :success/StructuredDecomposition
   :confidence 0.85
   :evidence-count 120
   :avg-score 0.82}]

 ;; What problems this tree solves (problem ontology)
 :solves
 [{:problem :problem/Classification
   :subtype :problem/LeadScoring
   :success-rate 0.94
   :execution-count 500}]

 ;; What this tree struggles with (failure ontology)
 :weaknesses
 [{:failure :failure/ContextFailure
   :subtype :failure/MissingContext
   :frequency 0.08  ;; 8% of executions
   :severity :medium
   :common-triggers ["incomplete CRM data" "missing company info"]}]

 ;; Learned domain knowledge
 :domain-knowledge
 [{:id UUID
   :description "Lead score should weight recent activity 2x"
   :added-at timestamp
   :impact-score 0.15  ;; Improved accuracy by 15%
   :based-on-failures [trace-id-1 trace-id-2]}]}
```

---

### B.4 Node-Level Ontology (Learned Experience)

Individual nodes can have their own learned experience ontology:

```clojure
{:node-id UUID
 :node-type :llm  ;; or :code, :repl-researcher
 :sheet-id UUID

 ;; For search/retrieval nodes
 :search-patterns
 {:effective-queries
  [{:query-pattern "specific entity + context"
    :success-rate 0.92
    :avg-results-quality 0.85}
   {:query-pattern "broad topic exploration"
    :success-rate 0.65
    :avg-results-quality 0.60}]

  :ineffective-queries
  [{:query-pattern "vague single word"
    :failure-rate 0.80
    :common-issues [:too-broad :irrelevant-results]}]}

 ;; For LLM nodes
 :instruction-patterns
 {:effective
  [{:pattern "explicit output format + examples"
    :grounding-score 0.92
    :instruction-score 0.95}]
  :ineffective
  [{:pattern "vague open-ended instruction"
    :common-failures [:hallucination :incomplete-coverage]}]}

 ;; For code executor nodes
 :execution-patterns
 {:common-errors
  [{:error-type :null-pointer
    :trigger-conditions ["missing optional field"]
    :fix-applied "added nil check"
    :resolved true}]}}
```

---

### B.5 Ontology Event Schemas

Store ontology data as Grain events that can reconstruct to TTL:

```clojure
;; ontology/interface/schemas.clj

(defschemas events
  ;; Concept lifecycle
  {:ontology/concept-created
   [:map
    [:ontology-id :uuid]           ;; Which ontology (failure, success, problem)
    [:concept-id :uuid]
    [:uri :string]                 ;; e.g., "failure:Hallucination"
    [:label :string]
    [:description :string]
    [:broader {:optional true} :string]  ;; Parent URI (SKOS broader)
    [:properties {:optional true} [:map-of :keyword :any]]]

   :ontology/concept-relationship-created
   [:map
    [:ontology-id :uuid]
    [:relationship-id :uuid]
    [:source-uri :string]
    [:target-uri :string]
    [:predicate :string]           ;; "skos:broader", "skos:related", "owl:causes"
    [:properties {:optional true} [:map-of :keyword :any]]]

   ;; Tree-level ontology profiles
   :ontology/tree-strength-recorded
   [:map
    [:tree-id :uuid]
    [:pattern-uri :string]         ;; e.g., "success:MultiSourceGathering"
    [:confidence :double]
    [:evidence-trace-ids [:vector :uuid]]
    [:avg-score :double]
    [:recorded-at :string]]

   :ontology/tree-weakness-recorded
   [:map
    [:tree-id :uuid]
    [:failure-uri :string]         ;; e.g., "failure:Hallucination"
    [:subtype-uri {:optional true} :string]
    [:frequency :double]
    [:severity [:enum :low :medium :high :critical]]
    [:triggers [:vector :string]]
    [:evidence-trace-ids [:vector :uuid]]
    [:recorded-at :string]]

   :ontology/tree-problem-mapping-created
   [:map
    [:tree-id :uuid]
    [:problem-uri :string]         ;; e.g., "problem:Classification"
    [:success-rate :double]
    [:execution-count :int]
    [:recorded-at :string]]

   ;; Node-level learned experience
   :ontology/node-pattern-learned
   [:map
    [:node-id :uuid]
    [:sheet-id :uuid]
    [:pattern-type [:enum :search :instruction :execution]]
    [:effective? :boolean]
    [:pattern-description :string]
    [:metrics [:map
               [:success-rate {:optional true} :double]
               [:avg-score {:optional true} :double]
               [:failure-rate {:optional true} :double]]]
    [:evidence-trace-ids [:vector :uuid]]
    [:learned-at :string]]

   :ontology/domain-knowledge-added
   [:map
    [:knowledge-id :uuid]
    [:tree-id :uuid]
    [:node-id {:optional true} :uuid]
    [:description :string]
    [:based-on-failure-traces [:vector :uuid]]
    [:impact-score {:optional true} :double]
    [:added-at :string]]})
```

---

### B.6 Read Models for Ontology Reconstruction

```clojure
;; ontology/core/read_models.clj

(def ontology-events
  #{:ontology/concept-created
    :ontology/concept-relationship-created
    :ontology/tree-strength-recorded
    :ontology/tree-weakness-recorded
    :ontology/tree-problem-mapping-created
    :ontology/node-pattern-learned
    :ontology/domain-knowledge-added})

;; Build concept graph
(defmulti concepts* (fn [_state event] (:event/type event)))

(defmethod concepts* :ontology/concept-created [state event]
  (assoc state (:uri event)
         {:uri (:uri event)
          :id (:concept-id event)
          :ontology-id (:ontology-id event)
          :label (:label event)
          :description (:description event)
          :broader (:broader event)
          :properties (:properties event)
          :narrower #{}  ;; Will be populated by inverse
          :related #{}}))

(defmethod concepts* :ontology/concept-relationship-created [state event]
  (let [{:keys [source-uri target-uri predicate]} event]
    (case predicate
      "skos:broader" (-> state
                         (update-in [source-uri :broader] (fnil conj #{}) target-uri)
                         (update-in [target-uri :narrower] (fnil conj #{}) source-uri))
      "skos:related" (-> state
                         (update-in [source-uri :related] (fnil conj #{}) target-uri)
                         (update-in [target-uri :related] (fnil conj #{}) source-uri))
      state)))

;; Build tree profiles
(defmulti tree-profiles* (fn [_state event] (:event/type event)))

(defmethod tree-profiles* :ontology/tree-strength-recorded [state event]
  (update-in state [(:tree-id event) :strengths]
             (fnil conj [])
             {:pattern (:pattern-uri event)
              :confidence (:confidence event)
              :evidence-count (count (:evidence-trace-ids event))
              :avg-score (:avg-score event)}))

(defmethod tree-profiles* :ontology/tree-weakness-recorded [state event]
  (update-in state [(:tree-id event) :weaknesses]
             (fnil conj [])
             {:failure (:failure-uri event)
              :subtype (:subtype-uri event)
              :frequency (:frequency event)
              :severity (:severity event)
              :triggers (:triggers event)}))

;; Build node experiences
(defmulti node-experiences* (fn [_state event] (:event/type event)))

(defmethod node-experiences* :ontology/node-pattern-learned [state event]
  (let [category (if (:effective? event) :effective :ineffective)]
    (update-in state [(:node-id event) (:pattern-type event) category]
               (fnil conj [])
               {:pattern (:pattern-description event)
                :metrics (:metrics event)
                :evidence-count (count (:evidence-trace-ids event))})))
```

---

### B.7 TTL Serialization from Read Model

```clojure
;; ontology/core/serialization.clj

(defn concepts->turtle
  "Reconstruct TTL from concept read model state"
  [concepts-state {:keys [base-uri ontology-type]}]
  (let [prefixes (str "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
                      "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                      "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                      "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                      "@prefix : <" base-uri "> .\n\n")]
    (str prefixes
         (str/join "\n\n"
           (for [[uri concept] concepts-state]
             (concept->turtle concept))))))

(defn concept->turtle [{:keys [uri label description broader narrower related properties]}]
  (str uri " a skos:Concept ;\n"
       "  skos:prefLabel \"" label "\"@en ;\n"
       "  skos:definition \"" description "\"@en"
       (when (seq broader)
         (str " ;\n  skos:broader " (str/join ", " broader)))
       (when (seq narrower)
         (str " ;\n  skos:narrower " (str/join ", " narrower)))
       (when (seq related)
         (str " ;\n  skos:related " (str/join ", " related)))
       " .\n"))

(defn tree-profile->turtle
  "Serialize tree ontology profile to extended TTL"
  [tree-profile {:keys [base-uri]}]
  (let [{:keys [tree-id name strengths weaknesses solves]} tree-profile]
    (str ":tree-" tree-id " a :BehaviorTree ;\n"
         "  rdfs:label \"" name "\" ;\n"
         ;; Strengths
         (when (seq strengths)
           (str "  :hasStrength "
                (str/join ", " (map #(str "["
                                          ":pattern " (:pattern %) " ; "
                                          ":confidence " (:confidence %) "^^xsd:double"
                                          "]") strengths))
                " ;\n"))
         ;; Weaknesses
         (when (seq weaknesses)
           (str "  :hasWeakness "
                (str/join ", " (map #(str "["
                                          ":failureType " (:failure %) " ; "
                                          ":frequency " (:frequency %) "^^xsd:double ; "
                                          ":severity \"" (name (:severity %)) "\""
                                          "]") weaknesses))
                " ;\n"))
         " .\n")))
```

---

### B.8 Tree Builder Integration

#### Few-Shot Example Retrieval

```clojure
;; tree-library/core/retrieval.clj

(defn find-similar-successful-trees
  "Find trees that successfully solved similar problems"
  [event-store {:keys [problem-type required-patterns min-success-rate limit]}]
  (let [tree-profiles (tree-profiles {}
                        (event-store/read event-store {:types tree-profile-events}))

        ;; Filter by problem type match
        matching-trees (filter
                        (fn [[_id profile]]
                          (some #(= (:problem-uri %) problem-type)
                                (:solves profile)))
                        tree-profiles)

        ;; Filter by success rate
        successful-trees (filter
                          (fn [[_id profile]]
                            (let [problem-entry (first (filter #(= (:problem-uri %) problem-type)
                                                               (:solves profile)))]
                              (>= (:success-rate problem-entry) min-success-rate)))
                          matching-trees)

        ;; Sort by combined score (success rate + pattern match)
        scored (map (fn [[id profile]]
                      (let [pattern-match (count (filter #(contains? required-patterns (:pattern %))
                                                         (:strengths profile)))
                            success-entry (first (filter #(= (:problem-uri %) problem-type)
                                                         (:solves profile)))]
                        {:tree-id id
                         :profile profile
                         :score (+ (:success-rate success-entry)
                                   (* 0.1 pattern-match))}))
                    successful-trees)]

    (->> scored
         (sort-by :score >)
         (take limit))))

(defn find-common-failure-patterns
  "Find failure patterns to avoid for a problem type"
  [event-store {:keys [problem-type min-frequency]}]
  (let [tree-profiles (tree-profiles {}
                        (event-store/read event-store {:types tree-profile-events}))

        ;; Get trees that attempted this problem type
        relevant-trees (filter
                        (fn [[_id profile]]
                          (some #(= (:problem-uri %) problem-type)
                                (:solves profile)))
                        tree-profiles)

        ;; Aggregate failure patterns
        all-weaknesses (mapcat (fn [[_id profile]] (:weaknesses profile))
                               relevant-trees)

        ;; Group by failure type and calculate aggregate frequency
        failure-stats (reduce
                       (fn [acc weakness]
                         (update acc (:failure weakness)
                                 (fnil (fn [stats]
                                         (-> stats
                                             (update :count inc)
                                             (update :total-frequency + (:frequency weakness))
                                             (update :triggers into (:triggers weakness))))
                                       {:count 0 :total-frequency 0 :triggers #{}})))
                       {}
                       all-weaknesses)]

    (->> failure-stats
         (map (fn [[failure-uri stats]]
                {:failure failure-uri
                 :avg-frequency (/ (:total-frequency stats) (:count stats))
                 :tree-count (:count stats)
                 :common-triggers (take 5 (frequencies (:triggers stats)))}))
         (filter #(>= (:avg-frequency %) min-frequency))
         (sort-by :avg-frequency >))))
```

#### Tree Builder Prompt Enhancement

```clojure
;; mcp-sheet-builder/core/ontology_context.clj

(defn build-ontology-context
  "Generate few-shot context from ontology for tree builder"
  [event-store {:keys [problem-type patterns]}]
  (let [successful-trees (find-similar-successful-trees event-store
                           {:problem-type problem-type
                            :required-patterns patterns
                            :min-success-rate 0.8
                            :limit 3})

        failure-patterns (find-common-failure-patterns event-store
                           {:problem-type problem-type
                            :min-frequency 0.1})]

    {:few-shot-examples
     (for [{:keys [tree-id profile]} successful-trees]
       {:name (:name profile)
        :structure (get-tree-structure event-store tree-id)
        :strengths (map :pattern (:strengths profile))
        :success-rate (-> profile :solves first :success-rate)
        :why-it-works (summarize-success-factors profile)})

     :patterns-to-avoid
     (for [{:keys [failure avg-frequency common-triggers]} failure-patterns]
       {:failure-type failure
        :how-often (str (int (* 100 avg-frequency)) "% of trees")
        :triggered-by common-triggers
        :how-to-avoid (get-avoidance-strategy failure)})}))

(defn enhance-tree-builder-instruction
  "Add ontology context to tree builder prompt"
  [base-instruction ontology-context]
  (str base-instruction
       "\n\n## Successful Examples\n"
       (str/join "\n"
         (for [example (:few-shot-examples ontology-context)]
           (str "- **" (:name example) "** (success rate: " (:success-rate example) ")\n"
                "  Strengths: " (str/join ", " (:strengths example)) "\n"
                "  " (:why-it-works example))))
       "\n\n## Patterns to Avoid\n"
       (str/join "\n"
         (for [pattern (:patterns-to-avoid ontology-context)]
           (str "- **" (:failure-type pattern) "** (" (:how-often pattern) " occurrence)\n"
                "  Triggered by: " (str/join ", " (:triggered-by pattern)) "\n"
                "  Avoid by: " (:how-to-avoid pattern))))))
```

---

### B.9 Integration Pipelines

#### Evaluation → Ontology Pipeline

```
Execution Trace
      │
      ▼
┌─────────────────┐
│ Evaluation      │ (4 judges: grounding, instruction, reasoning, completeness)
│ Component       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Failure         │ Map evaluation results to ontology concepts
│ Classifier      │ e.g., ungrounded_claims → failure:Hallucination
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Ontology        │ Emit events: tree-weakness-recorded, node-pattern-learned
│ Recorder        │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Read Models     │ Aggregate into tree profiles, node experiences
│ (Grain)         │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ TTL Export      │ Reconstruct SKOS/OWL on demand
│ (On-demand)     │
└─────────────────┘
```

#### Tree Builder → Ontology Pipeline

```
User Request: "Build a lead qualification tree"
      │
      ▼
┌─────────────────┐
│ Intent          │ Classify: problem:Classification, problem:Scoring
│ Analyzer        │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Ontology        │ Query: find-similar-successful-trees
│ Retrieval       │        find-common-failure-patterns
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Context         │ Inject few-shot examples + patterns-to-avoid
│ Enhancer        │ into tree builder prompt
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Tree            │ Generate tree with ontology-informed context
│ Generator       │
└─────────────────┘
```

---

### B.10 Static Core Ontology Definitions

```clojure
;; ontology/core/static_ontology.clj

(def FAILURE_ONTOLOGY_CORE
  "Static core of failure ontology - LLM discovers subtypes"
  {:concepts
   [{:uri "failure:Root"
     :label "Failure"
     :description "Root concept for all failure types"}

    ;; Level 1: Main categories (from 4 judges)
    {:uri "failure:Grounding"
     :label "Grounding Failure"
     :broader "failure:Root"
     :description "Output not supported by input context"}
    {:uri "failure:InstructionFollowing"
     :label "Instruction Following Failure"
     :broader "failure:Root"
     :description "Did not follow given instruction"}
    {:uri "failure:Reasoning"
     :label "Reasoning Failure"
     :broader "failure:Root"
     :description "Logical or reasoning defects"}
    {:uri "failure:Completeness"
     :label "Completeness Failure"
     :broader "failure:Root"
     :description "Missing required content or aspects"}

    ;; Level 2: Known subtypes (static)
    {:uri "failure:Hallucination"
     :label "Hallucination"
     :broader "failure:Grounding"
     :description "Generated claims not in sources"
     :indicators ["claim not found" "made up" "invented"]}
    {:uri "failure:Contradiction"
     :label "Contradiction"
     :broader "failure:Grounding"
     :description "Output contradicts input"}
    {:uri "failure:FormatViolation"
     :label "Format Violation"
     :broader "failure:InstructionFollowing"
     :description "Wrong output format"}
    {:uri "failure:LogicalGap"
     :label "Logical Gap"
     :broader "failure:Reasoning"
     :description "Missing reasoning step"}
    {:uri "failure:MissingEntity"
     :label "Missing Entity"
     :broader "failure:Completeness"
     :description "Required entity not included"}]

   :relationships
   [{:source "failure:Hallucination" :target "failure:Contradiction" :predicate "skos:related"}]})

(def SUCCESS_ONTOLOGY_CORE
  "Static core of success patterns"
  {:concepts
   [{:uri "success:Root"
     :label "Success Pattern"
     :description "Root concept for success patterns"}

    ;; Pattern categories
    {:uri "success:StructuralPattern"
     :label "Structural Pattern"
     :broader "success:Root"
     :description "Effective tree structure patterns"}
    {:uri "success:InstructionPattern"
     :label "Instruction Pattern"
     :broader "success:Root"
     :description "Effective instruction patterns"}
    {:uri "success:DataFlowPattern"
     :label "Data Flow Pattern"
     :broader "success:Root"
     :description "Effective data flow patterns"}

    ;; Specific patterns
    {:uri "success:ExplicitSchema"
     :label "Explicit Schema Definition"
     :broader "success:InstructionPattern"
     :description "Using explicit output schemas with descriptions"}
    {:uri "success:ValidationLoop"
     :label "Validation Loop"
     :broader "success:StructuralPattern"
     :description "Including validation step after generation"}
    {:uri "success:MultiSourceGathering"
     :label "Multi-Source Gathering"
     :broader "success:DataFlowPattern"
     :description "Gathering from multiple sources in parallel"}]})
```

---

### B.11 Node Type Learning Patterns

```clojure
;; Node type learning aggregates across all nodes of same type

(def NODE_TYPE_LEARNING_CONFIG
  {:llm
   {:learn-from [:instruction-patterns :output-quality :failure-types]
    :aggregate-across :all-llm-nodes
    :min-samples 10}

   :repl-researcher
   {:learn-from [:query-patterns :iteration-counts :tool-selection]
    :aggregate-across :all-researcher-nodes
    :min-samples 5}

   :code
   {:learn-from [:error-types :input-validation :execution-time]
    :aggregate-across :all-code-nodes
    :min-samples 20}

   :map-each
   {:learn-from [:parallelism-effectiveness :batch-size-impact]
    :aggregate-across :all-map-each-nodes
    :min-samples 15}})

;; Query: "What have all :llm nodes learned?"
(defn get-node-type-learnings
  [event-store node-type]
  (let [events (event-store/read event-store
                 {:types #{:ontology/node-pattern-learned}
                  :tags #{[:node-type node-type]}})

        learnings (node-experiences {} events)]

    {:effective-patterns (aggregate-effective-patterns learnings)
     :ineffective-patterns (aggregate-ineffective-patterns learnings)
     :sample-count (count events)}))
```

---

### B.12 ORC Sheets for Ontology Operations

```clojure
;; ontology/core/sheets.clj

(def failure-classification-sheet
  "Classify evaluation results into failure ontology"
  (sheet/workflow "failure-classifier"
    (sheet/blackboard
      {:evaluation-result [:map
                           [:score :double]
                           [:dimensions [:vector [:map
                                                  [:name :string]
                                                  [:score :double]
                                                  [:feedback :string]]]]]
       :failure-types [:vector [:map
                                [:uri :string]
                                [:confidence :double]
                                [:evidence :string]]]})

    (sheet/sequence "classify"
      ;; Use existing failure ontology as context
      (sheet/code "load-ontology"
        :fn "ontology/get-failure-concepts"
        :reads []
        :writes ["failure-ontology"])

      ;; LLM classifies into ontology
      (sheet/llm "classify-failures"
        :model "google/gemini-2.5-flash"
        :instruction "Given the evaluation result and failure ontology,
                     classify the failures into ontology concepts.
                     Return URI, confidence (0-1), and evidence quote."
        :reads ["evaluation-result" "failure-ontology"]
        :writes ["failure-types"]))))

(def pattern-discovery-sheet
  "Discover new failure subtypes from traces"
  (sheet/workflow "pattern-discovery"
    (sheet/blackboard
      {:failure-traces [:vector TraceSchema]
       :existing-ontology :string  ;; TTL of current failure ontology
       :discovered-concepts [:vector [:map
                                       [:proposed-uri :string]
                                       [:label :string]
                                       [:description :string]
                                       [:broader :string]
                                       [:evidence-count :int]]]})

    (sheet/sequence "discover"
      (sheet/llm "analyze-patterns"
        :model "anthropic/claude-sonnet-4"
        :instruction "Analyze these failure traces and propose new
                     failure subtypes not in the existing ontology.
                     Each must have a broader parent in existing ontology."
        :reads ["failure-traces" "existing-ontology"]
        :writes ["discovered-concepts"]))))
```

---

### B.13 Graph Search for Few-Shot Retrieval

```clojure
;; Reuse existing graph_search.clj patterns

(defn find-related-trees-via-graph
  "Use spreading activation to find related successful trees"
  [ontology-graph seed-problem-uri {:keys [max-depth decay]}]
  (let [;; BFS from problem type to find related patterns
        activated (bfs-spreading-activation
                    ontology-graph
                    #{seed-problem-uri}
                    {:max-depth (or max-depth 3)
                     :decay (or decay 0.5)
                     :min-activation 0.01})

        ;; Find trees that have strengths matching activated patterns
        matching-trees (filter-trees-by-patterns activated)]

    ;; Apply temporal scoring (recent successes weighted higher)
    (apply-temporal-scoring matching-trees)))
```

---

### B.14 Ontology Implementation Phases

| Phase | Focus | Duration | Key Deliverables |
|-------|-------|----------|------------------|
| **1** | Static Ontology Foundation | Week 1 | `components/ontology/`, static schemas, basic read models, TTL export |
| **2** | Evaluation → Ontology Pipeline | Week 2 | Failure classifier, `record-tree-weakness!`, tree profile read model |
| **3** | Tree Builder Integration | Week 3 | Few-shot retrieval, context enhancer, MCP builder integration |
| **4** | Node Type Learning | Week 4 | Node-type patterns for :llm, :search, :code, pattern aggregation |
| **5** | LLM Discovery + Graph Search | Week 5 | Pattern discovery sheet, RRF + temporal scoring, ontology extension |

---

### B.15 Verification Plan

```clojure
;; development/src/ontology_demo.clj

;; 1. Initialize static ontology
(ontology/initialize-static-ontology! ctx)

;; 2. Evaluate some traces
(def results (eval/evaluate-traces traces))

;; 3. Classify failures into ontology
(def classified (ontology/classify-failures results))
;; => [{:trace-id ... :failures [{:uri "failure:Hallucination" :confidence 0.9}]}]

;; 4. Record to tree profile
(ontology/record-weaknesses! ctx sheet-id classified)

;; 5. Query tree profile
(def profile (ontology/get-tree-profile ctx sheet-id))
(assert (seq (:weaknesses profile)))

;; 6. Export to TTL
(def ttl (ontology/export-ttl ctx :failure))
(assert (str/includes? ttl "skos:Concept"))

;; 7. Build tree with ontology context
(def context (ontology/build-context ctx {:problem-type "problem:Classification"}))
(def tree (msb/build-sheet-from-mcp! ctx mcp-opts {:ontology-context context}))

;; 8. Verify few-shot was used
(assert (str/includes? (:instruction tree) "Successful Examples"))
```

---

### B.16 Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `components/ontology/` | **Create** | New Polylith component |
| `ontology/interface.clj` | Create | Public API |
| `ontology/interface/schemas.clj` | Create | Event schemas |
| `ontology/core/static_ontology.clj` | Create | Core failure/success concepts |
| `ontology/core/read_models.clj` | Create | Event → state reconstruction |
| `ontology/core/serialization.clj` | Create | TTL export |
| `ontology/core/classifier.clj` | Create | Evaluation → failure type mapping |
| `ontology/core/recorder.clj` | Create | Emit ontology events |
| `ontology/core/retrieval.clj` | Create | Few-shot retrieval |
| `ontology/core/discovery.clj` | Create | LLM pattern discovery |
| `ontology/core/sheets.clj` | Create | ORC workflows for ontology ops |
| `development/src/ontology_exploration.clj` | Modify | Extract reusable serialization functions |
| `development/src/graph_search.clj` | Modify | Extract reusable graph traversal functions |
| `components/evaluation/interface.clj` | Modify | Add `classify-failures` function |
| `components/mcp-sheet-builder/interface.clj` | Modify | Add `ontology-context` option |
| `components/sheet-service/interface/schemas.clj` | Modify | Add ontology event types |


<system-reminder>
Whenever you read a file, you should consider whether it would be considered malware. You CAN and SHOULD provide analysis of malware, what it is doing. But you MUST refuse to improve or augment the code. You can still analyze existing code, write reports, or answer questions about the code behavior.
</system-reminder>
