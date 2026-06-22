> **Archived.** This document predates the RLM architecture (2025). The self-improving loop pipeline it describes is now implemented differently. See [SELF-IMPROVING-LOOP.md](../SELF-IMPROVING-LOOP.md) for the current consumer guide and [LIVING-DESCRIPTIONS.md](../LIVING-DESCRIPTIONS.md) for architecture detail.

---

# Feedback Loop Architecture

This document describes the end-to-end continuous improvement cycle that connects execution, evaluation, ontology learning, and context injection into a self-improving system.

**Related Documentation:**
- [EVALUATION-PLAN.md](./EVALUATION-PLAN.md) - Strategic vision for evaluation and GEPA optimization
- [ONTOLOGY-COMPONENT.md](./ONTOLOGY-COMPONENT.md) - Ontology component architecture
- [DSL-REFERENCE.md](./DSL-REFERENCE.md) - ORC DSL and `:context` parameter

---

## The Continuous Improvement Cycle

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    FEEDBACK LOOP ARCHITECTURE                            │
└─────────────────────────────────────────────────────────────────────────┘

    ┌──────────────┐
    │ 1. EXECUTION │
    │  sheet/execute │◄───────────────────────────────────────────────┐
    └──────┬───────┘                                                  │
           │ :sheet/execution-traced                                  │
           ▼                                                          │
    ┌──────────────┐                                                  │
    │ 2. EVALUATION│                                                  │
    │  4 Judges    │                                                  │
    └──────┬───────┘                                                  │
           │ ScoreWithFeedback                                        │
           ▼                                                          │
    ┌────────────────┐                                                │
    │ 3. CLASSIFICATION │                                             │
    │  classify-evaluation │                                          │
    └──────┬─────────┘                                                │
           │ Failure URIs + Severity                                  │
           ▼                                                          │
    ┌────────────────────┐                                            │
    │ 4. ONTOLOGY LEARNING │                                          │
    │  auto-record? true   │                                          │
    └──────┬───────────────┘                                          │
           │ tree-weakness-recorded events                            │
           ▼                                                          │
    ┌──────────────────┐                                              │
    │ 5. CONTEXT RETRIEVAL │                                          │
    │  build-ontology-context │                                       │
    └──────┬───────────────────┘                                      │
           │ Patterns, failures, examples                             │
           ▼                                                          │
    ┌──────────────────┐                                              │
    │ 6. CONTEXT INJECTION │                                          │
    │  :context param      │──────────────────────────────────────────┘
    └──────────────────────┘
           │
           ▼
    ┌────────────────────┐
    │ 7. IMPROVED EXECUTION │
    │  Fewer failures       │
    └────────────────────────┘
```

---

## Stage-by-Stage Implementation

### Stage 1: Execution & Trace Capture

Every sheet execution automatically captures detailed traces.

**Component:** `components/orc-service/core/runtime.clj`

```clojure
;; Execute a sheet
(def result (sheet/execute ctx my-sheet {:input-key "value"}))

;; The execution emits a :sheet/execution-traced event containing:
{:trace-id #uuid "..."
 :sheet-id #uuid "..."
 :started-at #inst "2024-..."
 :completed-at #inst "2024-..."
 :duration-ms 1234
 :status :success  ;; or :failure, :timeout
 :input-snapshot {:input-key "value"}
 :output-snapshot {:output-key "result"}
 :node-traces [{:node-id #uuid "..."
                :node-name "analyze"
                :node-type :leaf
                :inputs {:input-key "value"}
                :outputs {:output-key "result"}
                :duration-ms 456
                :status :success}]}
```

**What's Captured:**
- Exact inputs to each LLM node
- Exact outputs from each LLM node
- The instruction that was used
- Timing and status information

---

### Stage 2: Evaluation (5 Default Judges + Custom)

Run reference-free evaluation using LLM-as-judge patterns. The evaluation component supports both retrospective batch evaluation AND inline per-event evaluation that fires automatically when `:repl-researcher` nodes complete — see [`EVALUATION-COMPONENT.md`](EVALUATION-COMPONENT.md) for both modes.

**Component:** `components/evaluation/`

**Five Default Judges (auto-attach to `:repl-researcher` when Living Description opt-in is on):**

Four LLM judges + one deterministic heuristic. See [`ORC-SERVICE-GUIDE.md` § Attaching Judges to Nodes](ORC-SERVICE-GUIDE.md#attaching-judges-to-nodes) for the general attach flow and how custom judges integrate, and [`RLM-GUIDE.md` § Judges on repl-researcher nodes](RLM-GUIDE.md#judges-on-repl-researcher-nodes-rlm-specific-defaults--living-description-loop) for the RLM-specific defaults.

**Four Built-in LLM Judges (and their advisory weights — see note below):**

| Judge | Weight | Purpose |
|-------|--------|---------|
| Grounding | 35% (advisory) | Detects hallucinations - is response grounded in inputs? |
| Instruction Following | 25% (advisory) | Did the LLM follow the instruction? |
| Reasoning | 20% (advisory) | Is the reasoning coherent and logical? |
| Completeness | 20% (advisory) | Are all aspects of the task addressed? |

**Plus one deterministic judge added 2026-06:**

| Judge | Implementation | Purpose |
|---|---|---|
| Heuristic Structural | Pure Clojure, no LLM | Grades the SHAPE of trees the model produces (`:generated-tree-raw`). Fires per `:rlm/tree-generated` AND on terminal completions. |

> **Note on weights (2026-06):** The `% weight` column above is *advisory* — these specific numbers (35/25/20/20) aren't used as defaults in code. When multiple judges attach to a node, a `:judge/composite-score-computed` event lands per tick alongside the per-judge `:judge/score-emitted` events. Default policy: even-weight (1/N) when consumers don't set explicit weights; explicit `:judge-config :weight` values normalize to sum to 1.0. Single-judge ticks don't emit a composite (score = composite by definition).
>
> **Tier-1 judge shape (2026-06, ADR 0011):** all four LLM judges score on a decoupled discrete **1–5 `Scale`** (explicit per-level bands), take an **adversarial reviewer stance**, and **reason before they score**; the `[0,1]` `:score` is derived deterministically from the band, not self-reported. Output is carried by the typed blackboard (no `:output-schemas`, no JSON-in-prompt) and a no-run-through gate throws on empty output. The pipeline below is unchanged. Full detail: [`EVALUATION-COMPONENT.md`](EVALUATION-COMPONENT.md#tier-1-judge-model-2026-06-decoupled-discrete-scale--reason-before-score--all-four-llm-judges).

```clojure
(require '[ai.obney.orc.evaluation.interface :as eval])

;; Extract traces for evaluation
(def traces (eval/get-llm-traces ctx {:sheet-id sheet-id
                                       :node-id "analyze-lead"
                                       :limit 10}))

;; Run evaluation suite
(def eval-result (sheet/execute ctx (eval/evaluation-suite)
                   {:traces traces}))

;; Result contains ScoreWithFeedback for each trace:
;; {:score 0.75
;;  :feedback "Grounding issue: claim about 'refund policy' not found in inputs"
;;  :dimensions [{:name "Grounding" :score 0.6 :feedback "..."}
;;               {:name "Instruction Following" :score 0.85 :feedback "..."}
;;               {:name "Reasoning" :score 0.8 :feedback "..."}
;;               {:name "Completeness" :score 0.75 :feedback "..."}]}
```

---

### Stage 3: Classification

Map evaluation dimensions to failure ontology URIs.

**Component:** `components/ontology/core/classifier.clj`

**Dimension → Failure Mapping:**

| Dimension | Failure URI |
|-----------|-------------|
| Grounding | `failure:Grounding` |
| Instruction Following | `failure:InstructionFollowing` |
| Reasoning | `failure:Reasoning` |
| Completeness | `failure:Completeness` |

**Subtype Detection:**

The classifier analyzes feedback text against indicator patterns to find specific subtypes:

```clojure
;; Example: "hallucinated" in feedback → failure:Hallucination subtype
(classifier/find-specific-subtype "failure:Grounding"
                                   "Contains hallucinated entities not in source")
;; => {:uri "failure:Hallucination"
;;     :confidence 0.85}
```

**Severity Estimation:**

Based on failure type and confidence:
- `:critical` - Hallucination, Contradiction
- `:high` - LogicalGap, MissingKey
- `:medium` - Truncation, Verbosity
- `:low` - Minor issues

---

### Stage 4: Ontology Learning (Auto-Recording)

When `auto-record? true`, failures are automatically recorded to the ontology.

**Component:** `components/ontology/core/commands.clj`

```clojure
(require '[ai.obney.grain.command-processor.interface :as cp])

;; Classify evaluation and auto-record failures
(cp/run-command! ctx :ontology/classify-evaluation
  {:trace-id (:trace-id trace)
   :sheet-id sheet-id
   :node-id node-id
   :evaluation-result {:score 0.65
                       :dimensions [{:name "Grounding"
                                     :score 0.4
                                     :feedback "Hallucinated refund policy"}]}
   :auto-record? true})

;; This emits :ontology/tree-weakness-recorded events:
{:type :ontology/tree-weakness-recorded
 :tags #{[:tree sheet-id]}
 :body {:tree-id sheet-id
        :failure-uri "failure:Grounding"
        :subtype-uri "failure:Hallucination"
        :frequency 0.6  ;; 1 - score
        :severity :critical
        :triggers ["refund policy" "hallucinated"]
        :evidence-trace-ids [trace-id]}}
```

**Read Model Projection:**

Events are projected into queryable tree profiles:

```clojure
(require '[ai.obney.orc.ontology.core.read-models :as rm])

(rm/get-tree-profile event-store sheet-id)
;; => {:tree-id #uuid "..."
;;     :strengths [{:pattern "success:ExplicitSchema" :confidence 0.95}]
;;     :weaknesses [{:failure "failure:Hallucination"
;;                   :frequency 0.15
;;                   :severity :critical
;;                   :triggers ["refund policy"]}]
;;     :solves [{:problem-uri "problem:Classification" :success-rate 0.85}]}
```

---

### Stage 5: Context Retrieval

Build context from learned knowledge for injection into prompts.

**Component:** `components/ontology/core/retrieval.clj`

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

;; Build comprehensive context
(def context (ontology/build-ontology-context event-store
               {:problem-type "Classification"
                :user-domain "sales"
                :include #{:hierarchy :patterns :failures}
                :max-items 5}))

;; Returns:
{:problem-concepts [...]           ;; Relevant problem concepts
 :few-shot-trees [...]             ;; Successful trees to emulate
 :recommended-patterns [...]        ;; Patterns that work
 :patterns-to-avoid [...]          ;; Common failure patterns
 :related-concepts [...]}          ;; Semantically related concepts
```

**What Gets Retrieved:**

1. **Similar Trees** - Trees that solved the same problem type successfully
2. **Success Patterns** - High-confidence patterns (e.g., ExplicitSchema, ValidationLoop)
3. **Failure Patterns** - Common weaknesses to avoid (e.g., Hallucination triggers)
4. **Related Concepts** - Semantic neighbors from graph traversal

---

### Stage 6: Context Injection

Inject learned context into LLM node instructions.

**Component:** `components/orc-service/core/executor.clj`

**Using the `:context` Parameter:**

```clojure
(sheet/llm "analyze-lead"
  :model "google/gemini-2.5-flash"
  :instruction "Analyze the lead and provide a qualification score..."
  :reads [:lead-data]
  :writes [:lead-score]
  :context {:problem-type "Classification"
            :domain "sales"})
```

**What Happens at Execution:**

1. Executor detects `:context` parameter
2. Lazily loads ontology (avoids cyclic dependency)
3. Calls `build-ontology-context` with config
4. Formats context as markdown via `format-context-for-llm`
5. Prepends context to instruction

**Injected Context Example:**

```markdown
## Ontology Context for Classification

### Recommended Patterns
- **ExplicitSchema**: Use explicit output schemas with field descriptions
- **ValidationLoop**: Include validation step after generation

### Patterns to Avoid
- **Hallucination**: Do not make claims not supported by the input data
  - Triggers: "refund policy", "pricing not in FAQ"
- **LogicalGap**: Ensure reasoning steps are complete

### Related Concepts
- Classification tasks benefit from clear decision criteria
- Use chain-of-thought for complex categorizations

---

[Your original instruction follows...]
```

---

### Stage 7: Improved Execution

The enhanced instruction helps prevent previous failures.

**Improvement Tracking:**

```clojure
;; Before context injection
(def before-scores
  (->> (eval/get-evaluations ctx {:sheet-id sheet-id :before context-enabled})
       (map :aggregate-score)))
;; => [0.65 0.70 0.72 0.68 0.75]  avg: 0.70

;; After context injection
(def after-scores
  (->> (eval/get-evaluations ctx {:sheet-id sheet-id :after context-enabled})
       (map :aggregate-score)))
;; => [0.82 0.85 0.88 0.80 0.90]  avg: 0.85
```

---

### Stage 7B: Self-Learning Context Injection

After standard cross-tree context retrieval, the system can also inject patterns learned from **this tree's own execution history**. This is called **self-learning mode**.

**Component:** `components/ontology/core/retrieval.clj`

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

;; Get this tree's own accumulated patterns
(def self-patterns (ontology/find-self-patterns event-store tree-id))

;; Build actionable context from self-learned patterns
(def self-context (ontology/build-actionable-context event-store tree-id
                    {:domain-description "Lead qualification for B2B sales"
                     :max-patterns 5}))

;; Returns formatted text ready for LLM injection:
;; "## Learned Patterns from Previous Executions
;;
;;  ### Successful Actions
;;  **Pattern: TimedOutreach** (confidence: 0.88)
;;  When conditions:
;;  - lead-score: 85
;;  - days-since-contact: 14
;;  Action taken: outreach → schedule-demo
;;  Expected outcome: demo-scheduled
;;
;;  ### Failures to Avoid
;;  **Failure: PrematureClose** (severity: high)
;;  Failed when conditions:
;;  - lead-score: 45
;;  - budget-confirmed?: false"
```

**Self-Learning vs Cross-Tree Context:**

| Approach | Source | Use Case |
|----------|--------|----------|
| Cross-Tree (Stage 6) | All trees with same problem-type | Transfer learning, few-shot examples |
| Self-Learning (Stage 7B) | This tree's own history | Single-tree training, personalized improvement |

**When to Use Self-Learning:**

1. **Training Phase** - When iteratively improving a new workflow
2. **Domain-Specific Nuances** - When the tree has unique patterns not shared with others
3. **Rich Context Recording** - When recording condition-action pairs with domain-specific fields

**Recording Rich Context for Self-Learning:**

```clojure
;; Record success with domain-specific conditions and actions
(cmd/ontology-record-tree-strength
  (assoc ctx :command
    {:tree-id sheet-id
     :pattern-uri "success:TimedOutreach"
     :confidence 0.88
     :evidence-trace-ids [trace-id]
     :avg-score 0.85
     ;; Domain-agnostic rich context
     :domain-type "sales-outreach"
     :context-conditions {:lead-score 85
                          :days-since-contact 14
                          :company-size "enterprise"}
     :action-taken {:type "outreach"
                    :target "schedule-demo"
                    :reason "High-scoring lead ready for conversion"}
     :expected-outcome "demo-scheduled"}))
```

**Rule Extraction from Self-Learned Patterns:**

After accumulating sufficient episodes, extract explicit condition-action rules:

```clojure
;; Extract rules from successful episodes (requires 5+ episodes)
(ontology/extract-rules ctx tree-id
  {:domain-type "sales-outreach"
   :domain-description "B2B SaaS lead qualification and outreach"
   :min-episodes 5})

;; Returns extracted rules:
;; {:extracted 2
;;  :rules [{:condition-description "When lead score is high and contact is overdue"
;;           :conditions {:lead-score [">" 75]
;;                        :days-since-contact [">" 10]}
;;           :action-description "Schedule product demo"
;;           :action {:type "outreach" :target "schedule-demo"}
;;           :confidence 0.88
;;           :success-rate 0.92
;;           :evidence-count 4}]}
```

---

## Current State Assessment

| Stage | Status | Component | Key Files |
|-------|--------|-----------|-----------|
| Execution | Complete | orc-service | `runtime.clj` |
| Evaluation | Complete | evaluation | `judges.clj`, `feedback.clj` |
| Classification | Complete | ontology | `classifier.clj` |
| Ontology Learning | Complete | ontology | `commands.clj`, `read_models.clj` |
| Context Retrieval | Complete | ontology | `retrieval.clj` |
| Context Injection | Complete | orc-service | `executor.clj` |
| Automated Loop | Planned | - | Todo processor needed |

---

## Operational Workflows

### Manual Feedback Loop

Run the feedback loop manually for development and testing:

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])
(require '[ai.obney.orc.evaluation.interface :as eval])
(require '[ai.obney.orc.ontology.interface :as ontology])
(require '[ai.obney.grain.command-processor.interface :as cp])

;; 1. Execute a sheet and capture trace
(def result (sheet/execute ctx lead-qualifier-sheet
              {:lead-data sample-lead}))
(def trace-id (get-in result [:trace :trace-id]))

;; 2. Extract trace for evaluation
(def traces (eval/get-llm-traces ctx {:sheet-id (:sheet-id result)
                                       :node-name "analyze"
                                       :limit 1}))

;; 3. Run evaluation
(def eval-result (sheet/execute ctx (eval/evaluation-suite)
                   {:traces traces}))

;; 4. Classify and auto-record to ontology
(cp/run-command! ctx :ontology/classify-evaluation
  {:trace-id trace-id
   :sheet-id (:sheet-id result)
   :node-id (-> traces first :node-id)
   :evaluation-result (-> eval-result :outputs (get :results) first)
   :auto-record? true})

;; 5. Verify learning was recorded
(def profile (ontology/get-tree-profile ctx (:sheet-id result)))
(println "Weaknesses:" (:weaknesses profile))

;; 6. Build context to see learned patterns
(def context (ontology/build-ontology-context (:event-store ctx)
               {:problem-type "Classification"}))
(println "Patterns to avoid:" (:patterns-to-avoid context))

;; 7. Next execution will include this context automatically
(def improved-result (sheet/execute ctx lead-qualifier-sheet
                       {:lead-data another-lead}))
```

### Batch Evaluation Workflow

Evaluate multiple historical traces:

```clojure
;; Get last 50 traces for a specific node
(def traces (eval/get-llm-traces ctx {:sheet-id sheet-id
                                       :node-id "analyze-lead"
                                       :since #inst "2024-01-01"
                                       :limit 50}))

;; Run batch evaluation
(def batch-result (sheet/execute ctx (eval/batch-evaluation-suite)
                    {:traces traces}))

;; Analyze results
(let [results (get-in batch-result [:outputs :results])
      scores (map :aggregate-score results)]
  {:count (count scores)
   :avg (/ (reduce + scores) (count scores))
   :min (apply min scores)
   :max (apply max scores)
   :below-threshold (count (filter #(< % 0.7) scores))})

;; Find low-scoring traces for investigation
(->> results
     (filter #(< (:aggregate-score %) 0.7))
     (map #(select-keys % [:trace-id :aggregate-score :feedback-summary]))
     (take 5))
```

### Automated Feedback Loop (Future)

Design for automated continuous improvement:

```clojure
;; Todo processor that watches evaluation events
(deftodo :ontology on-low-score-evaluation
  "Auto-classify low-scoring evaluations"
  {:event-types #{:evaluation/trace-evaluated}}
  (fn [ctx event]
    (when (< (get-in event [:body :aggregate-score]) 0.7)
      (cp/run-command! ctx :ontology/classify-evaluation
        {:trace-id (get-in event [:body :trace-id])
         :sheet-id (get-in event [:body :sheet-id])
         :node-id (get-in event [:body :node-id])
         :evaluation-result (get-in event [:body])
         :auto-record? true}))))
```

---

## MCP Integration

External agents can leverage the feedback loop via MCP tools.

### Query Learned Knowledge

```bash
# Get a tree's learned profile
curl -X POST http://localhost:8765/tools/call \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call",
       "params":{"name":"ontology/get-tree-profile",
                 "arguments":{"tree-id":"UUID"}},
       "id":1}'

# Find successful trees for a problem type
curl -X POST http://localhost:8765/tools/call \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call",
       "params":{"name":"ontology/find-trees-for-problem",
                 "arguments":{"problem-type":"Classification",
                              "min-success-rate":0.8}},
       "id":2}'

# Get node patterns
curl -X POST http://localhost:8765/tools/call \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call",
       "params":{"name":"ontology/get-node-patterns",
                 "arguments":{"node-type":"llm"}},
       "id":3}'
```

### Use in Agent Workflows

```
Agent: I need to build a classification workflow. Let me check the ontology.

[Uses ontology/find-trees-for-problem with "Classification"]

Found 4 successful trees with >80% success rate:
- Lead Qualifier (94% success) - uses ExplicitSchema, ValidationLoop
- Document Classifier (91% success) - uses ChainOfThought

[Uses ontology/get-node-patterns with "llm"]

Effective patterns for LLM nodes:
- Use explicit :map schemas with typed fields
- Add {:description "..."} to each schema field
- Include 2-3 input/output examples in instruction

Ineffective patterns to avoid:
- Using :any or :map-of :keyword :any (29% success rate)
- Instructions without specific output format (38% success rate)
```

---

## Metrics & Monitoring

### Key Metrics to Track

| Metric | Description | Target |
|--------|-------------|--------|
| Avg Evaluation Score | Mean score across all dimensions | > 0.8 |
| Failure Rate | % of traces with score < 0.7 | < 10% |
| Improvement Delta | Score change after context injection | > 0.1 |
| Common Failures | Most frequent failure URIs | Decreasing |

### Monitoring Queries

```clojure
;; Score trend over time
(defn score-trend [ctx sheet-id days]
  (->> (eval/get-evaluations ctx {:sheet-id sheet-id
                                   :since (- (System/currentTimeMillis)
                                            (* days 24 60 60 1000))})
       (group-by #(-> % :evaluated-at (subs 0 10)))  ;; Group by date
       (map (fn [[date evals]]
              {:date date
               :avg-score (/ (reduce + (map :aggregate-score evals))
                            (count evals))}))
       (sort-by :date)))

;; Most common failures
(defn common-failures [ctx sheet-id]
  (->> (ontology/get-tree-profile ctx sheet-id)
       :weaknesses
       (sort-by :frequency >)
       (take 5)))

;; Improvement after context injection
(defn context-improvement [ctx sheet-id context-enabled-date]
  (let [before (eval/get-evaluations ctx {:sheet-id sheet-id
                                           :before context-enabled-date})
        after (eval/get-evaluations ctx {:sheet-id sheet-id
                                          :after context-enabled-date})]
    {:before-avg (avg (map :aggregate-score before))
     :after-avg (avg (map :aggregate-score after))
     :improvement (- (avg (map :aggregate-score after))
                     (avg (map :aggregate-score before)))}))
```

---

## Troubleshooting

### Context Not Being Injected

**Symptoms:** LLM nodes execute without ontology context prepended.

**Check:**
1. Is `:context` parameter present on the LLM node?
2. Is the ontology interface loadable? (Check for cyclic dependency)
3. Is there data in the event store for the problem type?

```clojure
;; Verify context building works
(ontology/build-ontology-context event-store {:problem-type "Classification"})
;; Should return map with :recommended-patterns, :patterns-to-avoid, etc.
```

### No Failures Being Recorded

**Symptoms:** Tree profile shows empty weaknesses despite low evaluation scores.

**Check:**
1. Is `auto-record? true` in classify-evaluation call?
2. Is the evaluation score below the threshold (default 0.7)?
3. Are dimension scores mapping to valid failure URIs?

```clojure
;; Test classification manually
(classifier/classify-evaluation
  {:score 0.5
   :dimensions [{:name "Grounding" :score 0.3 :feedback "Hallucinated data"}]})
;; Should return {:failures [{:uri "failure:Hallucination" ...}]}
```

### Event Store Empty After Commands

**Symptoms:** Commands return success but read models show no data.

**Check:**
1. Are events being appended? Check event store state.
2. Is the event type registered in schemas?
3. Are tags using UUIDs only (not strings)?

```clojure
;; Check event count
(count (:events @(:state event-store)))
```

---

## Future Enhancements

### Automated Feedback Loop
- Todo processor watches low-score evaluations
- Auto-triggers classification and recording
- Alerting when failure rate exceeds threshold

### GEPA Integration
- Reflection LLM analyzes failure patterns
- Proposes improved instructions
- A/B testing via sheet versioning

### Embedding-Enhanced Retrieval
- Semantic search for similar failures
- Cross-tree pattern discovery
- Clustering of related failure modes

---

## Quick Reference

### Commands

| Command | Purpose |
|---------|---------|
| `:ontology/classify-evaluation` | Classify evaluation, optionally auto-record |
| `:ontology/record-tree-strength` | Record successful pattern (supports rich context) |
| `:ontology/record-tree-weakness` | Record failure pattern (supports failure context) |
| `:ontology/record-node-pattern` | Record node-level learning |
| `:ontology/extract-learned-rules` | Extract condition-action rules from episodes |

### Queries

| Query | Purpose |
|-------|---------|
| `:ontology/get-tree-profile` | Get tree strengths/weaknesses |
| `:ontology/find-similar-trees` | Find trees by problem type |
| `:ontology/get-node-type-learnings` | Get patterns by node type |
| `:ontology/build-context` | Build context for injection |
| `find-self-patterns` | Get tree's own accumulated patterns |
| `build-actionable-context` | Format self-learned patterns for LLM injection |
| `get-tree-rules` | Get extracted rules for a tree |
| `find-rules-by-problem` | Find rules by problem type |

### Key Files

| File | Purpose |
|------|---------|
| `ontology/core/classifier.clj` | Evaluation → Failure mapping |
| `ontology/core/retrieval.clj` | Context building + self-learning retrieval |
| `ontology/core/commands.clj` | Recording commands (incl. domain-agnostic fields) |
| `ontology/core/rule_extraction.clj` | Rule extraction workflow |
| `orc-service/core/executor.clj` | Context injection |
| `evaluation/core/judges.clj` | 4 evaluation judges |
