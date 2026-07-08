> **Archived.** This was a Phase-0 planning document. GEPA is now shipped as a native-Clojure implementation inside orc. See [GEPA-GUIDE.md](../GEPA-GUIDE.md) for the current consumer guide.

---

# Plan: GEPA Training Loop Integration

## Current Status: PHASE 0 COMPLETE ✓ | READY FOR PHASE 1

### Phase 0 Verification Results (2024)

GEPA optimization verified with real Python GEPA + Clojure judges:

| Metric | Value |
|--------|-------|
| Initial Score | 0.765 |
| Final Score | 0.783 |
| Improvement | +0.018 |
| Metric Calls | 30 |
| Candidates Explored | 3 |

**Best Instruction Found:**
> When provided with a question, answer it directly and completely using your knowledge. Do not ask for additional instructions or wait for more input. Provide accurate, factual responses to the questions asked.

### Documentation

**Primary Reference:** [`docs/GEPA-GUIDE.md`](./GEPA-GUIDE.md) - Comprehensive GEPA integration guide

### Completed Work ✓
| Feature | Status | Validation |
|---------|--------|------------|
| GEPA Integration | ✓ | Verified with real optimization run |
| Python Adapter | ✓ | `development/python/clojure_adapter.py` |
| Clojure Bridge | ✓ | `orc-service/core/gepa.clj` |
| Evaluation Judges | ✓ | 4 judges with weighted aggregation |
| Pattern Discovery | ✓ | 10/10 stress tests, 100% precision/recall |
| Tree Metadata & Search | ✓ | 16 unit tests pass |
| Rolling Metrics | ✓ | 22 unit tests pass |

---

## Tracking

- [x] Step 0: Save plan to repository
- [x] Step 0.1: Add GEPA API routes (HTTP fallback)
- [x] Step 0.2: Create Python adapter (`development/python/clojure_adapter.py`)
- [x] Step 0.2b: Create direct bridge integration (`orc-service/core/gepa.clj`) - PREFERRED
- [x] Step 0.3: Run verification test ✓ (score improved 0.765 → 0.783)
- [x] Documentation: Create `docs/GEPA-GUIDE.md`
- [ ] Phase 1: Port to native Clojure (future)

---

## PHASE 0: VERIFY THEORY WITH REAL PYTHON GEPA (IMMEDIATE PRIORITY)

**Goal:** Before building GEPA as a behavior tree, verify the thesis works by connecting the real Python GEPA library to our Clojure event store and evaluation judges.

### Integration Approach: Direct Python-Clojure Bridge (PREFERRED)

Using `libpython-clj2`, we call GEPA directly from Clojure with Clojure functions as callbacks:

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])

;; Run GEPA optimization directly from Clojure
(sheet/optimize-instruction context sheet-id
  [{:inputs {:question "What is 2+2?"} :expected {:answer "4"}}
   {:inputs {:question "Capital of France?"} :expected {:answer "Paris"}}]
  :judges [:grounding :instruction-following :reasoning]
  :max-metric-calls 50)

;; Returns:
;; {:initial-score 0.65
;;  :final-score 0.89
;;  :best-instruction "Answer factual questions precisely..."
;;  :improvement 0.24
;;  :iterations 12}
```

**Key file:** `components/orc-service/src/.../core/gepa.clj`

### Alternative: HTTP API (for external Python processes)

The real GEPA library (https://github.com/gepa-ai/gepa.git) uses an **adapter interface**:

```python
class GEPAAdapter:
    def evaluate(self, candidates, dataset) -> List[ScoredResult]:
        """Execute candidates and return scored results"""

    def make_reflective_dataset(self, traces) -> ReflectiveDataset:
        """Generate diagnostic feedback from execution traces"""
```

### Integration Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Python GEPA Library                          │
│  gepa.optimize(adapter=ClojureEventStoreAdapter(...))           │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              ClojureEventStoreAdapter (Python)                   │
│                                                                  │
│  evaluate():                                                     │
│    1. POST /api/sheet/execute with candidate instruction         │
│    2. Receive trace-id from :sheet/trace-assembled event         │
│    3. POST /api/evaluation/evaluate with trace-id                │
│    4. Return score from :evaluation/completed event              │
│                                                                  │
│  make_reflective_dataset():                                      │
│    1. GET /api/evaluation/low-scoring?threshold=0.7              │
│    2. Query :evaluation/completed events with score < threshold  │
│    3. Format traces + feedback for GEPA reflection               │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTP
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Clojure API (HTTP or programmatic)              │
│                                                                  │
│  POST /api/sheet/execute                                         │
│  POST /api/evaluation/evaluate                                   │
│  GET  /api/evaluation/low-scoring                                │
│  GET  /api/events/by-tag?tag=[:trace trace-id]                   │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Event Store (PostgreSQL)                      │
│                                                                  │
│  Events stored:                                                  │
│  - :sheet/trace-assembled (execution traces)                     │
│  - :evaluation/completed (scores + feedback)                     │
│  - :gepa/candidate-proposed (new candidates)                     │
│  - :gepa/optimization-step-completed                             │
└─────────────────────────────────────────────────────────────────┘
```

### Step-by-Step Implementation

#### Step 0.1: Add GEPA API Endpoints

Expose these operations programmatically (or via HTTP routes in a host application):

```clojure
;; Execute a workflow and return trace-id
(sheet/execute ctx sheet-id inputs)

;; Evaluate a trace using judges
(evaluation/evaluate ctx trace-id judges)

;; Query low-scoring evaluations for reflective dataset
(evaluation/low-scoring ctx {:threshold 0.7 :limit 50})

;; Query events by tag (for trace retrieval)
(es/read event-store {:tenant-id tid :tags #{[:trace trace-id]}})
```

#### Step 0.2: Create Python Adapter
**File:** `development/python/clojure_adapter.py`

```python
from gepa.adapters import GEPAAdapter
import requests

class ClojureEventStoreAdapter(GEPAAdapter):
    def __init__(self, base_url: str, sheet_id: str, judges: list[str]):
        self.base_url = base_url
        self.sheet_id = sheet_id
        self.judges = judges  # ["grounding", "instruction-following", etc.]

    def evaluate(self, candidate: dict, example: dict) -> ScoredResult:
        # 1. Execute workflow with candidate instruction
        trace_resp = requests.post(
            f"{self.base_url}/api/sheet/{self.sheet_id}/execute",
            json={"inputs": example["inputs"],
                  "instruction_override": candidate["instruction"]}
        )
        trace_id = trace_resp.json()["trace-id"]

        # 2. Evaluate using our judges
        eval_resp = requests.post(
            f"{self.base_url}/api/evaluation/evaluate",
            json={"trace-id": trace_id, "judges": self.judges}
        )
        result = eval_resp.json()

        return ScoredResult(
            score=result["score"],
            feedback=result["feedback"],
            trace_id=trace_id
        )

    def make_reflective_dataset(self, threshold: float = 0.7):
        # Get low-scoring evaluations from event store
        resp = requests.get(
            f"{self.base_url}/api/evaluation/low-scoring",
            params={"threshold": threshold, "limit": 50}
        )
        return resp.json()
```

#### Step 0.3: Run Verification Test
**File:** `development/python/gepa_verification.py`

```python
import gepa
from clojure_adapter import ClojureEventStoreAdapter

# Trainset: examples with inputs + expected outputs
trainset = [
    {"inputs": {"question": "What is 2+2?"}, "expected": {"answer": "4"}},
    {"inputs": {"question": "Capital of France?"}, "expected": {"answer": "Paris"}},
    # ... more examples
]

# Seed candidate: initial instruction from the workflow
seed_instruction = "Answer the user's question accurately and concisely."

# Create adapter connected to our event store
adapter = ClojureEventStoreAdapter(
    base_url="http://localhost:8080",
    sheet_id="uuid-of-qa-workflow",
    judges=["grounding", "instruction-following", "reasoning"]
)

# Run GEPA optimization
result = gepa.optimize(
    seed_candidate={"instruction": seed_instruction},
    adapter=adapter,
    trainset=trainset,
    valset=trainset[:5],  # Use subset for validation
    max_metric_calls=50,
    task_lm="openai/gpt-4o-mini",
    reflection_lm="anthropic/claude-sonnet-4"
)

# Verify: check events were stored
print(f"Best instruction: {result.best_candidate['instruction']}")
print(f"Improvement: {result.initial_score} → {result.final_score}")
```

### Success Criteria for Phase 0

- [ ] API routes respond correctly
- [ ] Python adapter can execute workflows via HTTP
- [ ] Evaluation scores come from our Clojure judges
- [ ] Events are stored in PostgreSQL event store
- [ ] GEPA loop completes at least 3 iterations
- [ ] Final score improves OR instruction meaningfully changes
- [ ] Can query optimization history from event store

### Files to Create/Modify

| File | Purpose |
|------|---------|
| `components/orc-service/core/gepa.clj` | GEPA bridge (direct integration) |
| `development/python/clojure_adapter.py` | Python GEPA adapter |
| `development/python/gepa_verification.py` | Verification test script |
| `development/python/requirements.txt` | Add gepa dependency |

---

## PHASE 1: GEPA AS BEHAVIOR TREE (AFTER VERIFICATION)

**Goal:** Once theory is verified, port GEPA to native Clojure/ORC/Grain for full event-sourced optimization.

**Reference Materials:**
- **Real GEPA Library:** https://github.com/gepa-ai/gepa.git
- `/Users/darylroberts/Desktop/Code/area_51/madman_ramblings/Gepa/notes/README_GEPA.md`
- `/Users/darylroberts/Desktop/Code/area_51/madman_ramblings/Gepa/notes/gepa_01_architecture_overview.md`
- `/Users/darylroberts/Desktop/Code/area_51/madman_ramblings/Gepa/notes/gepa_06_modular_implementation_guide.md`

**Note:** Phase 1 (below) is only implemented AFTER Phase 0 verification succeeds.

### Core GEPA Concepts

| Concept | Description |
|---------|-------------|
| **Pareto Selection** | Maintains diversity via per-instance best candidate tracking (not single global best) |
| **Reflective Mutation** | LLM analyzes failures and proposes improved instructions (not RL) |
| **GEPAFeedbackMetric** | Protocol with two modes: module-level scoring AND predictor-level feedback |
| **Two-Dataset Architecture** | trainset for reflection, valset for Pareto tracking |

### Module-to-Clojure Mapping

| # | Python Module | Clojure Equivalent | Event Store Integration |
|---|--------------|-------------------|------------------------|
| 1 | **Data Structures** | `components/gepa/core/types.clj` | Malli schemas in `interface/schemas.clj` |
| 2 | **Candidate Builder** | `components/gepa/core/candidates.clj` | Emits `:gepa/candidate-created` events |
| 3 | **Trace Capture** | ✓ EXISTS: `trace-assembled` events | Query via evaluation read model |
| 4 | **Feedback Extractor** | `components/evaluation/interface.clj` | Existing judges become feedback extractors |
| 5 | **Instruction Proposer** | `components/gepa/core/proposer.clj` (ORC workflow) | LLM node for reflective mutation |
| 6 | **Pareto Selector** | `components/gepa/core/pareto.clj` (read model) | Projects `:gepa/candidate-evaluated` → frontier |
| 7 | **Merge Engine** | `components/gepa/core/merge.clj` | Combines best instructions from frontier |
| 8 | **Main Loop** | `components/gepa/core/optimizer.clj` | Commands + todo processor for loop orchestration |

---

## Module 1: Data Structures

**Purpose:** Core types matching Python GEPA exactly

```clojure
;; Candidate = A proposed instruction for a specific predictor
{:candidate/id uuid
 :candidate/predictor-id uuid      ; Which LLM node this instruction is for
 :candidate/instruction string     ; The proposed instruction text
 :candidate/parent-id uuid         ; nil for seed, else parent candidate
 :candidate/generation int         ; 0 for seed, increments
 :candidate/created-at inst}

;; Score = One evaluation result for one example
{:score/candidate-id uuid
 :score/example-id uuid            ; Which trainset/valset example
 :score/value double               ; 0.0-1.0 score
 :score/feedback string            ; Optional predictor-level feedback
 :score/trace-id uuid}             ; Link to execution trace

;; ParetoEntry = Per-example best candidate tracking
{:pareto/example-id uuid
 :pareto/best-candidate-id uuid
 :pareto/best-score double}

;; Population = All candidates + their scores (read model state)
;; ParetoFrontier = Map of example-id → ParetoEntry (read model state)
```

**Files to create:**
- `components/gepa/interface/schemas.clj` - Malli schemas for above types
- Events: `:gepa/candidate-created`, `:gepa/candidate-evaluated`, `:gepa/frontier-updated`

---

## Module 2: Candidate Builder

**Purpose:** Create new candidate variants

```clojure
;; Command: Create seed candidate from existing instruction
:gepa/create-seed-candidate
{:predictor-id uuid
 :instruction string}
→ emits :gepa/candidate-created

;; Command: Create mutated candidate from parent
:gepa/create-mutated-candidate
{:parent-id uuid
 :new-instruction string
 :mutation-reason string}
→ emits :gepa/candidate-created
```

**Files to create:**
- `components/gepa/core/commands.clj` - Command handlers
- `components/gepa/core/candidates.clj` - Builder logic

---

## Module 3: Trace Capture

**Status: ✓ ALREADY EXISTS**

Current infrastructure:
- `:sheet/trace-assembled` events store execution traces
- `:evaluation/evaluation-completed` events store scores
- Query via `evaluation` component read models

**No new code needed** - just query existing events.

---

## Module 4: Feedback Extractor (Integration with Evaluation Component)

**Purpose:** Connect existing judges to GEPA feedback protocol

**Key insight:** GEPAFeedbackMetric has TWO modes:
1. **Module-level scoring** (existing judges): Returns 0-1 score
2. **Predictor-level feedback** (new): Returns per-LLM-node feedback strings

**Existing judges to wrap:**
```clojure
;; From components/evaluation - these become feedback extractors
(defprotocol GEPAFeedbackMetric
  (score [this trace expected])           ; Returns {:score 0.85}
  (feedback [this trace expected]))       ; Returns {:node-id "feedback string"}
```

**Files to create:**
- `components/gepa/core/feedback.clj` - Protocol + wrappers for existing judges
- New judges can implement predictor-level feedback directly

---

## Module 5: Instruction Proposer (ORC Workflow)

**Purpose:** LLM-based reflective mutation

**ORC workflow structure:**
```clojure
(sheet/workflow "gepa-instruction-proposer"
  (sheet/blackboard
    {:current-instruction :string
     :failure-examples [:vector [:map
                                 [:input :string]
                                 [:expected :string]
                                 [:actual :string]
                                 [:feedback :string]]]
     :proposed-instruction :string
     :mutation-reasoning :string})

  (sheet/sequence "propose"
    (sheet/llm "analyze-failures"
      :model "anthropic/claude-sonnet-4"
      :instruction "Analyze why the current instruction failed on these examples..."
      :reads [:current-instruction :failure-examples]
      :writes [:mutation-reasoning])

    (sheet/llm "propose-improvement"
      :instruction "Based on the analysis, propose an improved instruction..."
      :reads [:current-instruction :mutation-reasoning]
      :writes [:proposed-instruction])))
```

**Files to create:**
- `components/gepa/core/proposer.clj` - ORC workflow definition

---

## Module 6: Pareto Selector (Read Model)

**Purpose:** Maintain per-example best candidates

**Read model projection:**
```clojure
(defn pareto-frontier
  "Reduces candidate-evaluated events into per-example best tracking."
  [state events]
  (reduce
    (fn [frontier {:keys [body]}]
      (let [{:keys [example-id candidate-id score]} body
            current-best (get frontier example-id)]
        (if (or (nil? current-best)
                (> score (:best-score current-best)))
          (assoc frontier example-id
                 {:best-candidate-id candidate-id
                  :best-score score})
          frontier)))
    state
    (filter #(= (:event/type %) :gepa/candidate-evaluated) events)))
```

**Key Pareto logic:**
- A candidate is **dominated** if another candidate beats it on ALL examples
- Frontier = set of non-dominated candidates
- Selection probability weighted by frontier contribution

**Files to create:**
- `components/gepa/core/read_models.clj` - Pareto frontier read model
- `components/gepa/core/pareto.clj` - Selection logic

---

## Module 7: Merge Engine

**Purpose:** Combine best instructions from frontier

```clojure
;; After N generations, merge best performing instructions
;; Uses LLM to synthesize common patterns from top candidates

(sheet/workflow "gepa-instruction-merger"
  (sheet/blackboard
    {:top-instructions [:vector :string]
     :performance-stats [:vector [:map [:instruction :string] [:avg-score :double]]]
     :merged-instruction :string})

  (sheet/llm "merge-instructions"
    :instruction "Analyze these top-performing instructions and synthesize a combined instruction that captures the best patterns..."
    :reads [:top-instructions :performance-stats]
    :writes [:merged-instruction]))
```

**Files to create:**
- `components/gepa/core/merge.clj` - Merge workflow + selection of top candidates

---

## Module 8: Main Loop (Optimizer)

**Purpose:** Orchestrate the training loop

**Event-driven loop via todo processor:**
```clojure
;; Todo processor: React to evaluation-completed events
(deftodo on-candidate-evaluated
  :gepa/candidate-evaluated
  [ctx event]
  ;; 1. Update Pareto frontier (via command)
  ;; 2. Check if generation complete
  ;; 3. If complete: select parents, propose mutations, create new candidates
  ;; 4. If budget exhausted: emit :gepa/optimization-completed
  )
```

**Budget configuration (from Python):**
```clojure
{:max-bootstrapped-demos 4
 :max-labeled-demos 16
 :num-candidates 10          ; Candidates per generation
 :max-generations 5
 :early-stop-threshold 0.95
 :min-improvement 0.01}
```

**Files to create:**
- `components/gepa/core/optimizer.clj` - Main loop orchestration
- `components/gepa/core/todo_processors.clj` - Event-driven reactions

---

## Event Flow Diagram

```
┌─────────────────┐
│ Start Training  │
│ :gepa/start     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│ Create Seeds    │────▶│ :gepa/candidate │
│ (from workflow) │     │    -created     │
└─────────────────┘     └────────┬────────┘
                                 │
         ┌───────────────────────┘
         ▼
┌─────────────────┐     ┌─────────────────┐
│ Execute on      │────▶│ :sheet/trace    │
│ trainset        │     │   -assembled    │
└─────────────────┘     └────────┬────────┘
                                 │
         ┌───────────────────────┘
         ▼
┌─────────────────┐     ┌─────────────────┐
│ Evaluate via    │────▶│ :evaluation/    │
│ judges          │     │   completed     │
└─────────────────┘     └────────┬────────┘
                                 │
         ┌───────────────────────┘
         ▼
┌─────────────────┐     ┌─────────────────┐
│ Score + Extract │────▶│ :gepa/candidate │
│ Feedback        │     │   -evaluated    │
└─────────────────┘     └────────┬────────┘
                                 │
    ┌────────────────────────────┴─────────────────────┐
    │                                                  │
    ▼                                                  ▼
┌─────────────────┐                          ┌─────────────────┐
│ Update Pareto   │                          │ Budget          │
│ Frontier        │                          │ Exhausted?      │
└────────┬────────┘                          └────────┬────────┘
         │                                            │
         ▼                                            ▼ YES
┌─────────────────┐                          ┌─────────────────┐
│ Select Parents  │                          │ Merge & Emit    │
│ (Pareto-based)  │                          │ :gepa/completed │
└────────┬────────┘                          └─────────────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│ Propose         │────▶│ :gepa/candidate │
│ Mutations       │     │    -created     │
└─────────────────┘     └────────┬────────┘
         │                       │
         └───────────────────────┘ (loop)
```

---

## Implementation Order

### Step 1: Create GEPA Component Structure
```bash
bb scripts/create_component.bb gepa
```

Files to create:
- `components/gepa/deps.edn`
- `components/gepa/src/ai/obney/workshop/gepa/interface.clj`
- `components/gepa/src/ai/obney/workshop/gepa/interface/schemas.clj`
- `components/gepa/src/ai/obney/workshop/gepa/core/types.clj`

### Step 2: Data Structures + Schemas (Module 1)
- Define Malli schemas for Candidate, Score, ParetoEntry
- Define events: `:gepa/candidate-created`, `:gepa/candidate-evaluated`

### Step 3: Candidate Builder (Module 2)
- Commands for creating seed and mutated candidates
- Unit tests for candidate creation

### Step 4: Feedback Extractor Integration (Module 4)
- Protocol wrapping existing evaluation judges
- Test with a simple judge → feedback extraction

### Step 5: Pareto Selector Read Model (Module 6)
- Read model projecting evaluated candidates to frontier
- Unit tests for Pareto dominance logic

### Step 6: Instruction Proposer Workflow (Module 5)
- ORC workflow for reflective mutation
- Test with sample failure cases

### Step 7: Main Loop + Todo Processor (Module 8)
- Orchestration command `:gepa/start-optimization`
- Todo processor for event-driven loop
- Integration test with small trainset

### Step 8: Merge Engine (Module 7)
- ORC workflow for instruction merging
- Test with multiple successful candidates

---

## Verification Plan

### Unit Tests
```bash
clojure -M:dev:test -n ai.obney.orc.gepa.types-test
clojure -M:dev:test -n ai.obney.orc.gepa.candidates-test
clojure -M:dev:test -n ai.obney.orc.gepa.pareto-test
```

### Integration Test
Create `development/src/gepa_integration_test.clj`:
1. Build simple classification workflow with one LLM node
2. Create trainset of 10 examples
3. Run GEPA optimization for 3 generations
4. Verify score improves OR instructions change meaningfully
5. Check all events emitted correctly

### Success Criteria
- [ ] All 8 modules implemented
- [ ] Events flow correctly through todo processors
- [ ] Pareto frontier updates correctly
- [ ] At least one workflow shows measurable improvement
- [ ] Existing evaluation judges work as feedback extractors

---

## Completed Foundation Work

### Tests Passing (68 tests, 220 assertions)
- `ontology/core_test.clj` - 17 tests, 86 assertions
- `orc-service/gepa_primitives_test.clj` - 13 tests, 59 assertions
- `orc-service/metadata_test.clj` - 16 tests, 35 assertions
- `orc-service/rolling_metrics_test.clj` - 22 tests, 40 assertions

### Features Complete

| Feature | Files | Tests |
|---------|-------|-------|
| Pattern Discovery | `ontology/core/discovery.clj` | 10/10 stress tests |
| Tree Metadata | `orc-service/core/metadata.clj`, `read_models.clj` | 16 unit tests |
| Rolling Metrics | `orc-service/core/read_models.clj` | 22 unit tests |
| Async LLM Fix | `orc-service/test_helpers.clj` | Verified via stress tests |

---

## Reference: Critical Files

### GEPA Reference Materials (User's Notes)
| File | Purpose |
|------|---------|
| `area_51/madman_ramblings/Gepa/notes/README_GEPA.md` | Overview and reading paths |
| `area_51/madman_ramblings/Gepa/notes/gepa_01_architecture_overview.md` | Architecture, adapter pattern |
| `area_51/madman_ramblings/Gepa/notes/gepa_06_modular_implementation_guide.md` | 8-module implementation guide |

### Existing Workshop Components
| File | Purpose |
|------|---------|
| `components/evaluation/` | Existing judges (become GEPA feedback extractors) |
| `components/orc-service/core/executor.clj` | ORC workflow execution |
| `components/orc-service/core/read_models.clj` | Rolling metrics + metadata |
| `components/ontology/core/discovery.clj` | Pattern discovery workflow |

### Files to Create (GEPA Component)
| File | Purpose |
|------|---------|
| `components/gepa/interface.clj` | Public API |
| `components/gepa/interface/schemas.clj` | Malli schemas for Candidate, Score, etc. |
| `components/gepa/core/types.clj` | Data structure definitions |
| `components/gepa/core/candidates.clj` | Candidate builder (Module 2) |
| `components/gepa/core/feedback.clj` | Judge → feedback extraction (Module 4) |
| `components/gepa/core/proposer.clj` | ORC workflow for mutation (Module 5) |
| `components/gepa/core/pareto.clj` | Pareto selection logic (Module 6) |
| `components/gepa/core/read_models.clj` | Pareto frontier read model (Module 6) |
| `components/gepa/core/merge.clj` | Instruction merger (Module 7) |
| `components/gepa/core/optimizer.clj` | Main loop orchestration (Module 8) |
| `components/gepa/core/commands.clj` | Command handlers |
| `components/gepa/core/todo_processors.clj` | Event-driven reactions |
