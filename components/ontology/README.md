# Ontology Component

Three-layer semantic knowledge system combining **static foundation** with **dynamic discovery** and **self-learning** for continuous improvement of AI workflows.

## Quick Links

- [Full Documentation](../../docs/ONTOLOGY.md) - Complete API reference and architecture
- [MCP Server Guide](../../docs/ONTOLOGY-MCP.md) - External tool access (13 tools)
- [Feedback Loop Architecture](../../docs/FEEDBACK-LOOP.md) - End-to-end improvement cycle
- [DSL Reference - Context Injection](../../docs/DSL-REFERENCE.md#ontology-context-injection)

## Overview

### Static Foundation

Predefined concepts that map to evaluation judges:
- **Failure Ontology** - 22 concepts (Hallucination, Grounding, InstructionFollowing, etc.)
- **Success Ontology** - 18 patterns (ValidationLoop, ExplicitSchema, ChainOfThought)
- **Problem Ontology** - 21 problem types (Classification, Summarization, DataExtraction)

### Discovery & Extension

The ontology grows over time through:
- **Failure subtype discovery** - New patterns proposed when evaluations reveal uncategorized failures
- **Tree profile learning** - Track which trees excel at which problems
- **Node pattern learning** - Learn what structures work for different node types

### Self-Learning Mode

Enable trees to learn from their own execution history:
- **Domain-agnostic context** - Record conditions and actions for any domain (drones, legal, sales, etc.)
- **Rich context recording** - Capture state conditions, actions taken, and expected outcomes
- **Rule extraction** - Extract explicit condition-action rules from successful episodes
- **Actionable context** - Format learned patterns for direct LLM prompt injection

## Public API

See `src/ai/obney/orc/ontology/interface.clj` for the complete public API.

### Static Concept Access

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

;; Get concepts by scope
(ontology/get-static-concepts {:scope :failure})  ;; 22 failure concepts
(ontology/get-static-concepts {:scope :success})  ;; 18 success patterns
(ontology/get-static-concepts {:scope :problem})  ;; 21 problem types

;; Look up specific concept
(ontology/get-static-concept-by-uri "failure:Hallucination")
;; => {:uri "failure:Hallucination" :label "Hallucination" :indicators [...]}

;; Map evaluation dimension to failure URI
(ontology/get-failure-concept-for-dimension "Grounding")
;; => "failure:Grounding"
```

### Context Building

```clojure
;; Build context for LLM prompts
(ontology/build-ontology-context event-store
  {:problem-type "problem:Classification"
   :include #{:hierarchy :patterns :failures}
   :max-items 5})

;; Format context as markdown
(ontology/format-context-for-llm context)
```

### Self-Learning

```clojure
;; Record success with rich domain-agnostic context
(cp/run-command! ctx :ontology/record-tree-strength
  {:tree-id tree-uuid
   :pattern-uri "success:PrecisionHover"
   :confidence 0.92
   :evidence-trace-ids [trace-id]
   :domain-type "drone-control"
   :context-conditions {:battery-level 0.25
                        :wind-speed 18.5
                        :altitude 150}
   :action-taken {:type "hover"
                  :target "reduce-altitude"
                  :reason "Low battery in windy conditions"}
   :expected-outcome "stable-hover"})

;; Get tree's own accumulated patterns
(ontology/find-self-patterns event-store tree-id)

;; Build actionable context for LLM injection
(ontology/build-actionable-context event-store tree-id
  {:domain-description "Drone flight control"
   :max-patterns 5})

;; Extract condition-action rules from episodes
(ontology/extract-rules ctx tree-id
  {:domain-type "drone-control"
   :domain-description "Autonomous drone operations"
   :min-episodes 5})

;; Query extracted rules
(ontology/get-tree-rules event-store tree-id)
(ontology/find-rules-by-problem event-store "precision-landing")
```

### Discovery & Learning

```clojure
(require '[ai.obney.grain.command-processor.interface :as cp])

;; Propose a new failure subtype
(cp/run-command! ctx :ontology/propose-failure-subtype
  {:parent-uri "failure:Hallucination"
   :proposed-uri "failure:Hallucination.NumericHallucination"
   :label "Numeric Hallucination"
   :description "Invented numbers or statistics"
   :evidence-count 5})

;; Record a tree's weakness
(cp/run-command! ctx :ontology/record-tree-weakness
  {:tree-id tree-uuid
   :failure-uri "failure:Hallucination"
   :severity :high
   :trigger-phrase "invented statistics"})

;; Classify evaluation results (with auto-recording)
(cp/run-command! ctx :ontology/classify-evaluation
  {:trace-id trace-uuid
   :evaluation-result eval-result
   :auto-record? true})
```

## Usage in ORC Workflows

Add the `:context` parameter to LLM nodes for automatic ontology injection:

```clojure
(sheet/llm "classify"
  :model "google/gemini-2.5-flash"
  :instruction "Classify the input..."
  :reads [:input]
  :writes [:classification]
  :context {:problem-type "problem:Classification"
            :include-patterns true
            :include-failures true})
```

The context is retrieved at execution time and prepended to the LLM prompt, providing:
- Success patterns relevant to the problem type
- Failure modes to avoid
- Few-shot examples from similar successful trees

## MCP Server

Start the MCP server for external tool access:

```bash
clj -M:dev:ontology-server
# Server on http://localhost:8765
```

13 tools available across 3 categories:
- **Knowledge tools** (6): search-concepts, get-concept, get-hierarchy, get-context, list-concepts, hybrid-search
- **Memory tools** (3): get-tree-profile, get-node-patterns, find-trees-for-problem
- **Execution tools** (4): sheets/list-available, get-description, execute, find-for-problem

See [ONTOLOGY-MCP.md](../../docs/ONTOLOGY-MCP.md) for full tool documentation.

## Component Structure

```
ontology/
├── src/ai/obney/orc/ontology/
│   ├── interface.clj          # Public API
│   ├── interface/schemas.clj  # Malli schemas (events, commands, queries)
│   └── core/
│       ├── commands.clj       # Command handlers (record, classify, discover)
│       ├── queries.clj        # Query handlers
│       ├── read_models.clj    # Event projections (concepts, tree-profiles, learned-rules)
│       ├── static_ontology.clj # Predefined concepts (failure/success/problem)
│       ├── classifier.clj     # Evaluation → failure URI mapping
│       ├── graph.clj          # BFS spreading activation, RRF fusion
│       ├── retrieval.clj      # Hybrid search, context building, self-learning
│       ├── rule_extraction.clj # Rule extraction workflow (self-learning)
│       ├── embedding.clj      # DJL vector operations
│       └── serialization.clj  # TTL/SKOS/OWL export
└── test/                      # Unit tests
```

## Tests

```bash
clj -M:dev:test -e "(require '[clojure.test :refer [run-tests]] '[ai.obney.orc.ontology.core-test]) (run-tests 'ai.obney.orc.ontology.core-test)"
```
