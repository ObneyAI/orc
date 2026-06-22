# Pattern Recording: making your existing tree better over time

> Your tree handles the same kind of task again and again. You've noticed it does
> well in some conditions and poorly in others — but that knowledge lives in your
> head. Pattern recording lets you write that knowledge down as data the tree can
> read back: "when conditions look like X, approach Y worked." You record successes
> and failures explicitly, then inject the accumulated patterns into a node's prompt.

You already have a working tree. Nothing here asks you to rebuild it. You add a thin
loop *around* it: after a run, you record what happened; before the next run, the tree
reads back what it learned. The store underneath is Grain's event-sourced log, so every
recorded pattern is durable and auditable — but you never touch that machinery directly.
You call two commands to record, and one function to read back.

The walk below goes from "I have a tree" to "my tree carries its own experience forward,"
one step at a time.

## Step 1 — Record one strength, retrieve it, inject it

Start as small as possible: a single good run, a single recorded strength.

Say your tree just produced an answer you're happy with. Record *that this tree*
demonstrated a success pattern — through the command processor:

```clojure
(require '[ai.obney.grain.command-processor-v2.interface :as cp])
(require '[ai.obney.orc.ontology.interface :as ontology])

;; After a run you judged good, record one strength for THIS tree.
(cp/process-command
  (assoc ctx :command
    {:command/name       :ontology/record-tree-strength
     :tree-id            my-sheet-id
     :pattern-uri        "success:ClearStructuredAnswer"
     :confidence         0.85
     :evidence-trace-ids [trace-id]
     :avg-score          0.90}))
```

That's the whole "write it down" step. Now read it back — `find-self-patterns` returns
the patterns *this* tree has accumulated (not a cross-tree aggregate):

```clojure
(ontology/find-self-patterns ctx my-sheet-id {})
;; => {:strengths [{:uri "success:ClearStructuredAnswer" :confidence 0.85 ...}]
;;     :weaknesses []}
```

Finally, turn the accumulated patterns into a prompt-ready string and inject it.
`build-actionable-context` returns a **map**; the piece you inject is `(:formatted-context …)`:

```clojure
(let [result (ontology/build-actionable-context ctx my-sheet-id "problem:Classification" {})]
  (:formatted-context result))
;; => "## Learned Patterns from Previous Executions\n..."
```

Drop that string into your `:llm` node's `:instruction`. The next run is now primed with
what the last good run looked like. Record after each good run, and the injected context
grows richer over time.

## Step 2 — Record failures, with the context that triggered them

Successes alone teach the tree what to repeat; failures teach it what to avoid. When a run
goes wrong, record a weakness — and crucially, *the conditions that surrounded it* so the
tree can recognize the situation next time:

```clojure
(cp/process-command
  (assoc ctx :command
    {:command/name       :ontology/record-tree-weakness
     :tree-id            my-sheet-id
     :failure-uri        "failure:Hallucination"
     :frequency          0.15
     :severity           :high
     :triggers           ["missing-context" "vague-input"]
     :evidence-trace-ids [trace-id]
     ;; The situation when it went wrong — any keys you like:
     :failure-context    {:input-length 12
                          :missing-fields ["company-size" "budget"]}}))
```

`:failure-context` is free-form: record whatever you observed about the inputs or state
when the tree underperformed. `build-actionable-context` folds these weaknesses into the
same injected string as "patterns to avoid," so a single injection now carries *both* what
worked and what to steer clear of.

## Step 3 — Let your tree read its own patterns at runtime

Steps 1–2 had you inject patterns by hand in the REPL. The natural next move is to make the
tree do it itself on every run: add one `:code` node to your **existing** tree that loads the
patterns and writes them to the blackboard, and have the downstream `:llm` node you already
have read that key. Nothing else in your tree changes.

The `:code` node executor receives `{:keys [inputs ctx]}` and returns a map keyed by its
`:writes`. It calls `build-actionable-context` and hands the formatted string to the board:

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

(defn load-learned-patterns
  [{:keys [inputs ctx]}]
  (let [result (ontology/build-actionable-context
                 ctx (:tree-id inputs) "problem:Classification" {})]
    {:learned-context (:formatted-context result)}))
```

Wire it in front of your existing LLM node — one new node, then your LLM node reads what it
produced via `{{learned-context}}`:

```clojure
(sheet/sequence "answer-with-experience"
  ;; NEW: pull this tree's accumulated patterns onto the blackboard
  (sheet/code "load-patterns"
    :fn     "my.ns/load-learned-patterns"
    :reads  [:tree-id]
    :writes [:learned-context])

  ;; YOUR existing LLM node — now primed with what past runs learned
  (sheet/llm "answer"
    :instruction "Apply what previous runs learned, then answer.

{{learned-context}}

Question: {{question}}"
    :reads  [:learned-context :question]
    :writes [:answer]))
```

Now every execution reads back the patterns you've recorded, with zero manual injection.
You keep full control over *what* gets written down (Steps 1–2 are still explicit, deliberate
calls you make), while the *reading-back* happens automatically inside the tree.

> ORC also ships a built-in `:context` parameter on `:llm` nodes that automates the
> read-back without writing a `:code` node — see [Step 4 of the Quick Start](#step-4-enable-automatic-context-injection)
> below. The `:code` node shown here is the explicit version: reach for it when you want
> to shape, filter, or combine the patterns before they hit the prompt.

## When to use this — and when to let the loop do it

**Use manual pattern recording (this doc) when *you* decide what's worth remembering.** You
judge each run, you choose the `:pattern-uri` and confidence, you record only the lessons you
trust. It's deliberate, inspectable, and you're never surprised by what's in the prompt.

**Reach for the automated [self-improving loop](SELF-IMPROVING-LOOP.md) when you want the
system to observe executions and learn without you in the loop** — it classifies each run
against a pattern corpus, evolves pattern bodies from accumulated evidence, and can mint new
behaviors. That's the same recording machinery underneath, driven automatically instead of by
hand. Start manual to build intuition and a trustworthy corpus; graduate to the loop when the
volume outpaces your willingness to review every run.

## How this relates to the ontology as memory

The patterns you record here live in the same event-sourced ontology that backs
[ORC's "ontology as memory"](ONTOLOGY.md). The difference is *what* you're remembering:
[ONTOLOGY.md](ONTOLOGY.md) is about giving a tree memory of **domain knowledge** (facts,
concepts, prior documents it can retrieve and ground answers in); this doc is about giving a
tree memory of **its own performance** (which approaches worked under which conditions). Both
use a `:code` node to pull memory onto the blackboard for a downstream `:llm` node — Step 3
above is the performance-memory twin of [ONTOLOGY.md Step 3](ONTOLOGY.md). A mature workflow
often uses both: domain memory to know the subject, performance memory to know how it
handles it.

---

> **When to use this:** You want to explicitly record what your workflow has learned and inject it into future prompts, with direct control over what gets recorded.
>
> **If you want automatic learning** (the system observes executions and learns without manual calls), see [SELF-IMPROVING-LOOP.md](SELF-IMPROVING-LOOP.md) instead.

---

## Quick path: record → retrieve → inject

```clojure
(require '[ai.obney.grain.command-processor-v2.interface :as cp])
(require '[ai.obney.orc.ontology.interface :as ontology])

;; 1. Record a pattern after a successful execution
(cp/process-command
  (assoc ctx :command
    {:command/name       :ontology/record-tree-strength
     :tree-id            my-sheet-id
     :pattern-uri        "success:MyPattern"
     :confidence         0.85
     :evidence-trace-ids [trace-id]
     :avg-score          0.90}))

;; 2. Retrieve patterns recorded for this tree
(ontology/find-self-patterns ctx my-sheet-id {})
;; => {:strengths [{:uri ... :confidence 0.85 :context-conditions {...} ...}]
;;     :weaknesses [...]}

;; 3. Build a formatted context string and inject it
(let [result (ontology/build-actionable-context ctx my-sheet-id "problem:Classification" {})]
  (:formatted-context result))
;; => "## Learned Patterns from Previous Executions\n..."
```

Inject `(:formatted-context result)` directly into an `:instruction` string or `:reads` key.
No ontology infrastructure beyond `components/ontology` required.

---

# Manual Self-Learning with Ontology Context

This guide explains how to use ORC's self-learning capabilities to improve your workflows over time. The system uses Grain's event sourcing infrastructure, providing full audit trails and reliable pattern storage.

## Overview

ORC's self-learning system is built on Grain's event sourcing:

| Layer | Component | Purpose |
|-------|-----------|---------|
| **Commands** | `defcommand` | Record successes and failures from any domain |
| **Events** | Event Store | Store all learning decisions with full audit trail |
| **Read Models** | Projections | Aggregate events into queryable tree profiles |
| **Context Injection** | Executor | Prepend learned patterns to LLM instructions |

## Grain Event Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        YOUR CODE                                 │
│  (ontology/record-tree-strength ctx {...})                       │
│  (ontology/record-tree-weakness ctx {...})                       │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                     GRAIN COMMAND                                │
│  :ontology/record-tree-strength                                  │
│  :ontology/record-tree-weakness                                  │
│  → Validates against Malli schema                                │
│  → Emits :ontology/tree-strength-recorded event                  │
│  → Emits :ontology/tree-weakness-recorded event                  │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                     GRAIN EVENT STORE                            │
│  Event persisted with:                                           │
│  - :type :ontology/tree-strength-recorded                        │
│  - :tags #{[:tree tree-id]}  (for efficient filtering)           │
│  - :body {...pattern-data...}                                    │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                     GRAIN READ MODEL                             │
│  :ontology/tree-profiles projection                              │
│  → Reduces events into per-tree state                            │
│  → Cached in L2 for fast access                                  │
│  → Query via (ontology/get-tree-profile ctx tree-id)            │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                     CONTEXT INJECTION                            │
│  (orc/execute ctx sheet-id inputs)                               │
│  → Executor detects :context parameter on LLM node               │
│  → Queries tree-profiles read model                              │
│  → Formats patterns as markdown via format-context-for-llm       │
│  → Prepends to instruction before LLM call                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## Quick Start

### Step 1: Record a Success

When your workflow performs well, record the successful pattern:

```clojure
(require '[ai.obney.grain.command-processor-v2.interface :as cp])

(cp/process-command
  (assoc ctx :command
    {:command/name :ontology/record-tree-strength
     :tree-id sheet-id
     :pattern-uri "success:HighQualityOutput"
     :confidence 0.92
     :evidence-trace-ids [trace-id]
     :avg-score 0.88

     ;; Domain-agnostic fields (works for any domain)
     :domain-type "sales-outreach"
     :context-conditions {:lead-score 85
                          :days-since-contact 3
                          :decision-maker? true}
     :action-taken {:type "personalized-email"
                    :template "value-prop"
                    :tone "professional"}
     :expected-outcome "meeting-scheduled"}))
```

### Step 2: Record a Failure

When your workflow fails, record what went wrong:

```clojure
(cp/process-command
  (assoc ctx :command
    {:command/name :ontology/record-tree-weakness
     :tree-id sheet-id
     :failure-uri "failure:Hallucination"
     :frequency 0.15
     :severity :high
     :triggers ["missing-context" "vague-input"]
     :evidence-trace-ids [trace-id]

     ;; Domain-agnostic fields
     :domain-type "sales-outreach"
     :failure-context {:lead-score 30
                       :missing-fields ["company-size" "budget"]}}))
```

### Step 3: Query Learned Patterns

Check what a tree has learned:

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

;; Get full tree profile
(ontology/get-tree-profile ctx sheet-id)
;; => {:strengths [{:pattern-uri "success:..." :confidence 0.92 ...}]
;;     :weaknesses [{:failure-uri "failure:..." :severity :high ...}]
;;     :solves [...]}

;; Get only self-patterns (this tree's history)
(ontology/find-self-patterns ctx sheet-id {})
;; => {:strengths [...] :weaknesses [...]}

;; Build formatted context for injection
(ontology/build-actionable-context ctx sheet-id "problem:Classification" {})
;; => "## Learned Patterns\n\n### What Works Well\n..."
```

### Step 4: Enable Automatic Context Injection

Add `:context` parameter to LLM nodes:

```clojure
(orc/llm "analyze"
  :instruction "Analyze the input data..."
  :reads [:input-data]
  :writes [:analysis]
  ;; This enables automatic context injection
  :context {:problem-type "problem:Classification"
            :self-learning? true
            :tree-id sheet-id
            :include-patterns true
            :include-failures true})
```

At execution time, the executor will:
1. Query the tree's learned patterns
2. Format them as markdown
3. Prepend to your instruction

---

## Grain Events Reference

### Events Emitted

| Event | When | Key Fields |
|-------|------|------------|
| `:ontology/tree-strength-recorded` | Recording success | `tree-id`, `pattern-uri`, `confidence`, `context-conditions`, `action-taken` |
| `:ontology/tree-weakness-recorded` | Recording failure | `tree-id`, `failure-uri`, `severity`, `triggers`, `failure-context` |
| `:ontology/learned-rule-extracted` | Rule extraction | `tree-id`, `condition`, `action`, `success-rate` |

### Event Tagging

All events use Grain's tagging for efficient filtering:

```clojure
{:type :ontology/tree-strength-recorded
 :tags #{[:tree tree-id]}  ;; Filter by tree
 :body {...}}
```

Query events by tag:
```clojure
(es/read event-store {:types #{:ontology/tree-strength-recorded}
                      :tags #{[:tree tree-id]}})
```

---

## Read Model Queries

### Tree Profile Functions

| Function | Returns | Use Case |
|----------|---------|----------|
| `get-tree-profile` | Single tree's full profile | Inspect what a tree has learned |
| `get-all-tree-profiles` | All tree profiles | Cross-tree analysis |
| `find-trees-by-problem` | Trees solving a problem | Find similar workflows |
| `find-trees-with-weakness` | Trees with specific failure | Pattern analysis |

### Self-Learning Functions

| Function | Returns | Use Case |
|----------|---------|----------|
| `find-self-patterns` | This tree's strengths + weaknesses | Self-reinforcing learning |
| `build-actionable-context` | Formatted markdown string | Inject into LLM prompts |
| `extract-rules` | Condition-action rules | Explicit pattern mining |

---

## Domain-Agnostic Fields

The self-learning system works for **any domain** - sales, legal, construction, drone control, etc. Use these flexible fields:

### For Strengths

```clojure
{:domain-type "your-domain"           ;; e.g., "legal-review", "sales-outreach"
 :context-conditions {:any-key "value"  ;; Conditions when success happened
                      :another 123}
 :action-taken {:type "action-type"     ;; What led to success
                :target "target"
                :reason "why"}
 :expected-outcome "description"}       ;; What success looks like
```

### For Weaknesses

```clojure
{:domain-type "your-domain"
 :failure-context {:any-key "value"    ;; Conditions when failure happened
                   :another 123}
 :attempted-action {:type "action-type" ;; What was attempted
                    :target "target"}}
```

---

## Complete Example

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc]
         '[ai.obney.orc.ontology.interface :as ontology]
         '[ai.obney.grain.command-processor-v2.interface :as cp])

;; 1. Define workflow with self-learning enabled
(def my-workflow
  (orc/workflow "lead-qualifier"
    (orc/blackboard
      {:lead-data :map
       :qualification [:map
                       [:score :double]
                       [:reasoning :string]
                       [:qualified? :boolean]]})

    (orc/llm "qualify"
      :instruction "Qualify the lead based on BANT criteria."
      :reads [:lead-data]
      :writes [:qualification]
      ;; Enable self-learning
      :context {:problem-type "problem:Classification"
                :self-learning? true})))

;; 2. Build the workflow
(def sheet-id (orc/build-workflow! ctx my-workflow))

;; 3. Execute
(def result (orc/execute ctx sheet-id
              {:lead-data {:company "Acme" :revenue 5000000}}))

;; 4. Record outcome (after human review or automated evaluation)
(when (good-result? result)
  (cp/process-command
    (assoc ctx :command
      {:command/name :ontology/record-tree-strength
       :tree-id sheet-id
       :pattern-uri "success:AccurateQualification"
       :confidence 0.9
       :evidence-trace-ids [(:trace-id result)]
       :avg-score 0.88
       :domain-type "sales"
       :context-conditions {:company-size "enterprise"
                            :has-budget? true}
       :action-taken {:type "bant-analysis"}
       :expected-outcome "accurate qualification"})))

;; 5. Future executions automatically include learned patterns
;; The :context parameter causes injection of patterns into the prompt
```

---

## Testing Self-Learning

Run the integration test to verify everything works:

```clojure
;; 1. Start dev environment
(dev/start!)

;; 2. Load integration test
(load-file "development/src/self_learning_integration_test.clj")

;; 3. Run all tests
(run-all-tests)
```

The tests verify:
- Recording strengths flows through Grain events → read models
- Recording weaknesses works the same way
- `find-self-patterns` returns correct tree's patterns
- Context injection prepends patterns to LLM instructions

---

## Related Documentation

| File | Description |
|------|-------------|
| `docs/ONTOLOGY.md` | Full ontology component reference |
| `docs/SELF-IMPROVING-LOOP.md` | 7-stage continuous improvement cycle (current) |
| `docs/DSL-REFERENCE.md` | DSL reference including `:context` parameter |
| `development/src/self_learning_integration_test.clj` | Integration tests |
