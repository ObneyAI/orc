# ORC DSL Reference

> **If you're new to ORC**, start at [GETTING-STARTED.md](GETTING-STARTED.md). This is the complete DSL reference. The [Core Concepts section](#core-concepts) is the newcomer entry point; everything after that is advanced reference material.

A comprehensive reference for building AI workflows with the ORC behavior tree DSL.

## Table of Contents

1. [Introduction](#introduction)
2. [Quick Start](#quick-start)
3. [Core Concepts](#core-concepts)
4. [Node Reference](#node-reference)
5. [Complete Tutorial: CRM Lead Qualification System](#complete-tutorial-crm-lead-qualification-system)
6. [Advanced Patterns](#advanced-patterns)
7. [Tracing & GEPA Primitives](#tracing--gepa-primitives)
8. [API Reference](#api-reference)
9. [Best Practices](#best-practices)

---

## Introduction

### What is ORC?

ORC is a behavior tree framework for building AI-powered workflows in Clojure. It provides:

- **Declarative workflow definition** - Define complex AI pipelines as data structures
- **Composable nodes** - Build complex behaviors from simple building blocks
- **Observable execution** - Every step is logged, traceable, and auditable
- **Event sourcing** - Full history of workflow changes and executions
- **Deterministic identity** - Workflow and node IDs are stable across rebuilds

### Why Use a DSL?

The ORC DSL offers several advantages over imperative code:

1. **Readability** - Workflow structure is immediately visible
2. **Composability** - Nodes can be nested and reused
3. **Testability** - Each node can be tested in isolation
4. **Observability** - Built-in tracing and debugging
5. **Version control** - Workflows can be exported, diffed, and versioned
6. **Hot reloading** - Rebuild workflows without restarting the system

---

## Quick Start

### Minimal Workflow Example

```clojure
(ns my-app.workflows
  (:require [ai.obney.orc.orc-service.interface :as sheet]))

;; Define a simple workflow
(def hello-workflow
  (sheet/workflow "hello-world"
    (sheet/blackboard
      {:input-name :string
       :greeting :string})

    (sheet/llm "generate-greeting"
      :model "google/gemini-2.0-flash-001"
      :instruction "Generate a friendly greeting for the given name."
      :reads [:input-name]
      :writes [:greeting])))

;; Build and execute
(defn run-hello [ctx name]
  (let [sheet-id (sheet/build-workflow! ctx hello-workflow)]
    (sheet/execute ctx sheet-id {:input-name name})))
```

### Running a Workflow

```clojure
;; 1. Start your REPL and load the context (see repl_stuff.clj)
;; 2. Build the workflow
(def sheet-id (sheet/build-workflow! ctx my-workflow))

;; 3. Execute with inputs
(def result (sheet/execute ctx sheet-id {:input-key "value"}))

;; 4. Check the outputs
(:outputs result)  ;; => {:output-key "result value"}
(:status result)   ;; => :success or :failure

;; Optional: Set LLM call budget (safety net for runaway workflows)
(def result (sheet/execute ctx sheet-id {:input-key "value"}
                           :llm-call-budget 50))
```

---

## Core Concepts

> **Newcomer entry point — start here.** Lines 90–135 cover the essential mental model (blackboard, nodes, execution). Everything from [Node Reference](#node-reference) onward is advanced reference material.

### Blackboard

The **blackboard** is shared state that nodes read from and write to. It's defined as a map of key names to [Malli](https://github.com/metosin/malli) schemas:

```clojure
(sheet/blackboard
  {:input-text :string                          ;; Simple type
   :word-count :int                             ;; Integer
   :items [:vector :string]                     ;; Vector of strings
   :scores [:vector [:map [:name :string]       ;; Vector of maps
                          [:score :double]]]
   :config [:map-of :keyword :any]})            ;; Open map
```

**Key rules:**
- All node inputs/outputs must be declared in the blackboard
- Keys are keywords at runtime (matching schema definition)
- Schema validation is optional but recommended

### Nodes

Nodes are the building blocks of workflows. There are two categories:

**Control Nodes** (orchestrate execution):
- `sequence` - Run children in order
- `parallel` - Run children concurrently
- `fallback` - Try alternatives until one succeeds
- `map-each` - Iterate over a collection

**Leaf Nodes** (perform work):
- `llm` - Call an LLM with a prompt
- `code` - Execute a Clojure function
- `condition` - Check a boolean expression
- `llm-condition` - Use LLM for yes/no decisions

### Execution Model

1. **Build phase** - Workflow definition is stored in the event store
2. **Execute phase** - Engine traverses the tree, running nodes
3. **Success/Failure** - Each node returns success or failure
4. **Blackboard updates** - Nodes write results to the blackboard

---

## Node Reference

### sequence

Execute children in order. Fails immediately if any child fails.

```clojure
(sheet/sequence "main-flow"
  (sheet/code "step-1" ...)
  (sheet/llm "step-2" ...)
  (sheet/code "step-3" ...))
```

**Behavior:**
- Executes children left-to-right
- If a child fails, stops and returns failure
- If all children succeed, returns success

### parallel

Execute children concurrently with configurable success/failure policies.

```clojure
;; Default: all must succeed, any can fail
(sheet/parallel "concurrent-tasks"
  (sheet/llm "task-a" ...)
  (sheet/llm "task-b" ...)
  (sheet/llm "task-c" ...))

;; Custom policies
(sheet/parallel "scoring"
  {:success-policy :all      ;; :all, :any, or :majority
   :failure-policy :any}     ;; :any or :all
  (sheet/llm "score-1" ...)
  (sheet/llm "score-2" ...)
  (sheet/llm "score-3" ...))
```

**Success policies:**
- `:all` (default) - All children must succeed
- `:any` - At least one child must succeed
- `:majority` - More than half must succeed

**Failure policies:**
- `:any` (default) - Fail if any child fails
- `:all` - Only fail if all children fail

### fallback

Try children in order until one succeeds. Also known as "selector" in behavior tree terminology.

```clojure
(sheet/fallback "with-fallback"
  ;; Try the preferred approach first
  (sheet/sequence "preferred-path"
    (sheet/condition "check-available"
      :check {:key :premium-api :op :equals :value true})
    (sheet/llm "use-premium" ...))

  ;; Fall back to alternative
  (sheet/llm "use-standard" ...))
```

**Behavior:**
- Tries children left-to-right
- If a child succeeds, stops and returns success
- If a child fails, tries the next one
- If all children fail, returns failure

**Common pattern: condition + action**
```clojure
(sheet/fallback "conditional-branch"
  ;; Branch 1: If condition is true, do action
  (sheet/sequence "if-branch"
    (sheet/condition "check-condition" ...)
    (sheet/llm "then-action" ...))

  ;; Branch 2: Else (always runs if condition failed)
  (sheet/llm "else-action" ...))
```

### map-each

Iterate over a collection, executing children for each item.

```clojure
(sheet/map-each "process-items"
  :from :items               ;; Source collection key
  :as :current-item          ;; Key for current item
  :into :processed-items     ;; Output collection key
  :parallel 5                ;; Max concurrent (default 1)

  ;; Children run for each item
  (sheet/sequence "item-pipeline"
    (sheet/llm "analyze" ...)
    (sheet/code "transform" ...)))
```

**Parameters:**
- `:from` - Blackboard key containing the input collection
- `:as` - Blackboard key where current item is placed
- `:into` - Blackboard key where results are collected
- `:parallel` - Max concurrent executions (1 = sequential)

**Output aggregation:**
Results are collected into a vector in the same order as inputs.

### llm

Execute an LLM call with structured input/output.

```clojure
(sheet/llm "generate-summary"
  :model "google/gemini-2.5-flash"
  :instruction "Analyze this data and provide:
- summary: Brief overview (2-3 sentences)
- keyPoints: List of 3-5 key takeaways
- sentiment: Overall tone (positive/negative/neutral)"
  :reads [:input-data :context]
  :writes [:analysis]
  :retry {:max-attempts 3 :backoff-ms [100 500 1000]})
```

**Parameters:**
- `:model` - OpenRouter model ID (e.g., "google/gemini-2.5-flash", "openai/gpt-4o")
- `:instruction` - Prompt sent to the LLM
- `:reads` - Blackboard keys to include as context
- `:writes` - Blackboard keys to write results to
- `:retry` - Optional retry configuration

**Structured Output:**

Simply describe the fields you want in your instruction. The framework automatically handles structured output extraction (via function calling or fallback mechanisms):

```clojure
:instruction "Analyze the lead and provide a qualification assessment.

Provide these fields:
- score: Number 0-100
- qualified: Boolean
- reason: Brief explanation"
:writes [:lead-assessment]
```

The response is automatically parsed and stored to the specified blackboard key.

### code

Execute a Clojure function.

```clojure
(sheet/code "calculate-score"
  :fn "my-app.scoring/compute-weighted-score"
  :reads [:raw-scores :weights]
  :writes [:final-score])
```

**Function signature:**

```clojure
(defn compute-weighted-score
  "Code executor functions receive a map with :inputs and return a map of outputs."
  [{:keys [inputs]}]
  (let [raw-scores (:raw-scores inputs)
        weights (:weights inputs)
        score (calculate raw-scores weights)]
    ;; Return a map of output-key -> value
    {:final-score score}))
```

**Key points:**
- `:fn` is the fully-qualified function name as a string
- Function receives `{:inputs {:key value ...}}`
- Function returns `{:output-key value ...}`
- Multiple outputs can be written from a single function
- Inputs and outputs use keyword keys

### condition

Static boolean check on blackboard values.

```clojure
(sheet/condition "check-premium-user"
  :check {:key :user-tier :op :equals :value "premium"})

(sheet/condition "check-high-score"
  :check {:key :score :op :gt :value 80})

(sheet/condition "check-has-data"
  :check {:key :items :op :not-empty})
```

**Supported operators:**
- `:equals` - Exact match
- `:not-equals` - Not equal
- `:gt` - Greater than
- `:gte` - Greater than or equal
- `:lt` - Less than
- `:lte` - Less than or equal
- `:contains` - Collection/string contains value
- `:not-empty` - Collection is not empty
- `:empty` - Collection is empty
- `:in` - Value is in the given set
- `:matches` - Regex match (value is regex pattern)

**Behavior:**
- Returns SUCCESS if condition is true
- Returns FAILURE if condition is false
- Use with `fallback` for conditional branching

### llm-condition

Use LLM to evaluate a yes/no question.

```clojure
(sheet/llm-condition "is-urgent?"
  :model "google/gemini-2.0-flash-001"
  :instruction "Is this message urgent, time-sensitive, or requiring immediate attention? Answer yes or no."
  :reads [:message-content])
```

**Parameters:**
- `:model` - OpenRouter model ID
- `:instruction` - Yes/no question for the LLM
- `:reads` - Context to evaluate

**Behavior:**
- LLM responds with yes/no (or true/false, 1/0)
- Returns SUCCESS if yes, FAILURE if no
- Use with `fallback` for LLM-driven branching

### delegate

Execute another workflow (sheet) with isolated blackboard. Useful for composing reusable subworkflows.

```clojure
(sheet/delegate "process-enrichment"
  :target-sheet-id enrichment-sheet-id  ;; UUID of target workflow
  :reads [:lead-data :context]           ;; Keys to pass as inputs
  :writes [:enriched-data :metadata]     ;; Keys to receive as outputs
  :timeout-ms 60000                       ;; Optional timeout (default 5 min)
  :inherit-ontology? true)                ;; Share learned patterns
```

**Parameters:**
- `:target-sheet-id` - UUID of the workflow to execute (required)
- `:reads` - Blackboard keys to pass as inputs to target workflow
- `:writes` - Blackboard keys to receive back from target workflow outputs
- `:timeout-ms` - Execution timeout in milliseconds (default: 300000)
- `:inherit-ontology?` - Whether child inherits ontology context (default: true)

**Behavior:**
- Creates isolated blackboard for target workflow (no shared state)
- Maps `:reads` keys from parent blackboard to target inputs
- Maps target outputs back to parent `:writes` keys
- Target failure causes delegate node to fail
- Full execution tracing with lifecycle events

**Use cases:**
- Decompose complex workflows into reusable components
- A/B test different sub-workflow implementations
- Isolate failure domains (child failure won't corrupt parent state)
- Parallel execution of independent subworkflows via `parallel` + `delegate`

**Example: Parent with reusable child**
```clojure
;; Build the child workflow first
(def enrichment-sheet-id
  (sheet/build-workflow! ctx
    (sheet/workflow "lead-enrichment"
      (sheet/blackboard
        {:lead-data :string
         :enriched-data [:map [:company-info :string]
                              [:funding-stage :string]]})
      (sheet/llm "enrich"
        :model "google/gemini-2.5-flash"
        :instruction "Research and enrich this lead data..."
        :reads [:lead-data]
        :writes [:enriched-data]))))

;; Parent workflow delegates to child
(def parent-workflow
  (sheet/workflow "lead-processor"
    (sheet/blackboard
      {:raw-lead :string
         :processed-lead [:map [:original :string]
                               [:enriched [:map-of :keyword :any]]]})
    (sheet/sequence "main"
      ;; Pass raw-lead, get back enriched-data into processed-lead
      (sheet/delegate "enrich-step"
        :target-sheet-id enrichment-sheet-id
        :reads [:raw-lead]
        :writes [:processed-lead]))))
```

---

## Complete Tutorial: CRM Lead Qualification System

This tutorial builds a complete lead qualification and nurturing workflow, demonstrating all DSL features.

```mermaid
flowchart TB
  root["<b>lead-processor</b><br/>SEQUENCE"]:::seq
  root --> intake["<b>intake</b><br/>LLM · leaf<hr/>▸ reads&nbsp;&nbsp;raw-lead<br/>◂ writes&nbsp;&nbsp;lead"]:::llm
  root --> enrichRef[["<b>delegate → lead-enrichment</b><br/>reusable subbehavior · isolated blackboard"]]:::sub
  root --> score["<b>score</b><br/>CODE · leaf<hr/>▸ reads&nbsp;&nbsp;enriched<br/>◂ writes&nbsp;&nbsp;grade"]:::code
  enrichRef -. "reads lead → writes enriched" .-> ENR
  subgraph ENR["lead-enrichment · its own tree, own blackboard"]
    direction TB
    er["<b>lead-enrichment</b><br/>SEQUENCE"]:::seq
    er --> e1["<b>company lookup</b><br/>LLM · leaf"]:::llm
    er --> e2["<b>fit analysis</b><br/>LLM · leaf"]:::llm
  end
  classDef seq fill:#1e3a8a,stroke:#60a5fa,color:#fff,stroke-width:2px;
  classDef llm fill:#4c1d95,stroke:#c4b5fd,color:#fff;
  classDef code fill:#0f766e,stroke:#5eead4,color:#fff;
  classDef sub fill:#1f2937,stroke:#94a3b8,color:#e5e7eb,stroke-dasharray:4 3;
```

### Overview

The system processes incoming sales leads through two parallel tracks:
1. **Qualification Track** - Analyze, score, and prioritize leads
2. **Outreach Track** - Generate personalized communications

### Sample Data Structures

```clojure
;; Lead record
{:lead-id "lead-001"
 :company-name "Acme Corp"
 :company-size "mid-market"
 :industry "Manufacturing"
 :contact-name "Jane Smith"
 :contact-title "VP Operations"
 :contact-email "jane@acme.com"
 :source "Website Demo Request"
 :pain-points ["manual processes" "scaling issues"]
 :budget-range "$50k-100k"
 :timeline "Q2 2025"}

;; Product catalog
[{:product-id "prod-001"
  :name "Workflow Automation Suite"
  :ideal-for ["Operations" "IT"]
  :min-company-size "mid-market"
  :price-range "$30k-80k"}]

;; Sales rep
{:rep-id "rep-001"
 :name "John Doe"
 :territories ["West Coast" "Mountain"]
 :expertise ["Manufacturing" "Logistics"]}
```

### The Complete Workflow

See `development/src/lead_qualification_demo.clj` for the full working implementation.

```clojure
(def lead-qualification-workflow
  (sheet/workflow "lead-qualification"

    ;; === BLACKBOARD SCHEMA ===
    (sheet/blackboard
      ;; Inputs
      {:leads [:vector [:map-of :keyword :any]]
       :products [:vector [:map-of :keyword :any]]
       :sales-reps [:vector [:map-of :keyword :any]]

       ;; Qualification track outputs
       :current-lead [:map-of :keyword :any]
       :lead-analysis [:map-of :keyword :any]
       :icp-match [:map-of :keyword :any]
       :budget-score :double
       :need-score :double
       :authority-score :double
       :timeline-score :double
       :composite-score [:map-of :keyword :any]
       :product-recommendations [:vector [:map-of :keyword :any]]
       :assigned-rep [:map-of :keyword :any]
       :qualified-leads [:vector [:map-of :keyword :any]]

       ;; Outreach track outputs
       :comm-preferences [:map-of :keyword :any]
       :filtered-leads [:vector [:map-of :keyword :any]]
       :current-content [:map-of :keyword :any]
       :personalized-content [:vector [:map-of :keyword :any]]
       :email-variant-a :string
       :email-variant-b :string
       :campaign-assignment [:map-of :keyword :any]
       :ready-for-outreach [:vector [:map-of :keyword :any]]

       ;; Final outputs
       :final-results [:map-of :keyword :any]})

    ;; === MAIN SEQUENCE ===
    (sheet/sequence "main"

      ;; === PARALLEL TRACKS ===
      (sheet/parallel "tracks"

        ;; =====================================
        ;; QUALIFICATION TRACK
        ;; =====================================
        (sheet/sequence "qualification-track"

          (sheet/map-each "qualify-leads"
            :from :leads
            :as :current-lead
            :into :qualified-leads
            :parallel 5

            (sheet/sequence "lead-qualification-pipeline"

              ;; Phase 1: LLM Analysis
              (sheet/llm "analyze-lead"
                :model "google/gemini-2.5-flash"
                :instruction "Analyze this lead and extract:
- companyProfile: Industry, size, and market position
- painPoints: List of identified challenges
- budgetSignals: Any indicators of budget (explicit or implicit)
- decisionMakers: Identified stakeholders and their roles
- urgencyIndicators: Signals about timeline pressure"
                :reads [:current-lead]
                :writes [:lead-analysis])

              ;; Phase 2: Code - ICP Matching
              (sheet/code "match-icp"
                :fn "lead-qualification-demo/match-ideal-customer-profile"
                :reads [:current-lead :lead-analysis]
                :writes [:icp-match])

              ;; Phase 3: Parallel BANT Scoring
              (sheet/parallel "bant-scoring"
                {:success-policy :all}

                (sheet/llm "score-budget"
                  :model "google/gemini-2.5-flash"
                  :instruction "Score this lead's budget fit from 0.0 to 1.0.
Consider: explicit budget mentions, company size, typical spend for their industry.
Return just the score as a decimal number."
                  :reads [:current-lead :lead-analysis]
                  :writes [:budget-score])

                (sheet/llm "score-need"
                  :model "google/gemini-2.5-flash"
                  :instruction "Score the urgency of this lead's need from 0.0 to 1.0.
Consider: pain point severity, current workarounds, cost of inaction.
Return just the score as a decimal number."
                  :reads [:current-lead :lead-analysis]
                  :writes [:need-score])

                (sheet/llm "score-authority"
                  :model "google/gemini-2.5-flash"
                  :instruction "Score this contact's decision-making authority from 0.0 to 1.0.
Consider: job title, role in evaluation process, organizational level.
Return just the score as a decimal number."
                  :reads [:current-lead :lead-analysis]
                  :writes [:authority-score])

                (sheet/llm "score-timeline"
                  :model "google/gemini-2.5-flash"
                  :instruction "Score timeline alignment from 0.0 to 1.0.
Consider: stated timeline, fiscal year cycles, competing priorities.
Return just the score as a decimal number."
                  :reads [:current-lead :lead-analysis]
                  :writes [:timeline-score]))

              ;; Phase 4: Code - Composite Scoring
              (sheet/code "compute-composite-score"
                :fn "lead-qualification-demo/compute-bant-composite"
                :reads [:budget-score :need-score :authority-score :timeline-score :icp-match]
                :writes [:composite-score :is-qualified])

              ;; Phase 5: Conditional - Only recommend products for qualified leads
              (sheet/fallback "product-recommendation-gate"
                (sheet/sequence "if-qualified"
                  (sheet/condition "check-qualified"
                    :check {:key :is-qualified :op :equals :value true})
                  (sheet/llm "recommend-products"
                    :model "google/gemini-2.5-flash"
                    :instruction "Based on this lead's profile and needs, recommend 1-3 products.
For each recommendation provide:
- productId: ID from the product catalog
- fitScore: 0.0-1.0 how well it matches their needs
- rationale: Why this product fits"
                    :reads [:current-lead :lead-analysis :products]
                    :writes [:product-recommendations]))

                ;; Else: Not qualified, skip recommendations
                (sheet/code "skip-products"
                  :fn "lead-qualification-demo/empty-recommendations"
                  :reads []
                  :writes [:product-recommendations]))

              ;; Phase 6: Code - Sales Rep Assignment
              (sheet/code "assign-rep"
                :fn "lead-qualification-demo/assign-sales-rep"
                :reads [:current-lead :composite-score :sales-reps]
                :writes [:assigned-rep]))))

        ;; =====================================
        ;; OUTREACH TRACK
        ;; =====================================
        (sheet/sequence "outreach-track"

          ;; Phase 1: LLM - Communication Preferences
          (sheet/llm "analyze-comm-preferences"
            :model "google/gemini-2.5-flash"
            :instruction "Analyze these leads and determine communication preferences:
- preferredChannel: email, phone, or linkedin
- bestTiming: morning, afternoon, or evening
- tone: formal, conversational, or technical
- frequency: high, medium, or low touch"
            :reads [:leads]
            :writes [:comm-preferences])

          ;; Phase 2: Code - Hard Filtering
          (sheet/code "filter-leads"
            :fn "lead-qualification-demo/filter-contactable-leads"
            :reads [:leads]
            :writes [:filtered-leads])

          ;; Phase 3: Map-Each - Content Personalization
          (sheet/map-each "personalize-content"
            :from :filtered-leads
            :as :current-lead
            :into :personalized-content
            :parallel 5

            (sheet/sequence "content-pipeline"

              ;; Generate personalized email
              (sheet/llm "generate-email"
                :model "google/gemini-2.5-flash"
                :instruction "Write a personalized outreach email for this lead.
Use their industry context, pain points, and company details.
Keep it under 200 words, professional but warm.
Include a clear call-to-action for a demo.

Return: subject, opening, body, cta, fullEmail"
                :reads [:current-lead :comm-preferences]
                :writes [:current-content])

              ;; Phase 4: Parallel A/B Variants
              (sheet/parallel "ab-variants"
                (sheet/llm "variant-a"
                  :model "google/gemini-2.5-flash"
                  :instruction "Rewrite this email with emphasis on ROI and cost savings.
Keep the same structure but focus on financial benefits."
                  :reads [:current-content]
                  :writes [:email-variant-a])

                (sheet/llm "variant-b"
                  :model "google/gemini-2.5-flash"
                  :instruction "Rewrite this email with emphasis on efficiency and time savings.
Keep the same structure but focus on productivity benefits."
                  :reads [:current-content]
                  :writes [:email-variant-b]))))

          ;; Phase 5: Code - Campaign Assignment
          (sheet/code "assign-campaigns"
            :fn "lead-qualification-demo/assign-to-campaigns"
            :reads [:personalized-content :comm-preferences]
            :writes [:ready-for-outreach])))

      ;; === COMBINE RESULTS ===
      (sheet/code "combine-results"
        :fn "lead-qualification-demo/combine-final-results"
        :reads [:qualified-leads :ready-for-outreach]
        :writes [:final-results]))))
```

### DSL Features Demonstrated

| Feature | Where Used | Purpose |
|---------|------------|---------|
| `sequence` | Main flow, each track | Orchestrate sequential steps |
| `parallel` | Two tracks, BANT scoring | Run independent work concurrently |
| `fallback` | Product recommendation gate | Conditional branching |
| `map-each` | Lead processing, content generation | Iterate with optional parallelism |
| `llm` | Analysis, scoring, content | AI-powered processing |
| `code` | Filtering, scoring, assignment | Deterministic logic |
| `condition` | Qualification check | Boolean decision |
| `blackboard` | Throughout | Typed data flow |
| `:success-policy :all` | BANT scoring | Require all scores |
| `:parallel 5` | map-each nodes | Concurrent processing |

---

## Advanced Patterns

### Nested Parallelism

Combine parallel tracks with parallel processing within each track:

```clojure
(sheet/parallel "outer"
  (sheet/sequence "track-1"
    (sheet/map-each "process"
      :from :items-1
      :as :item
      :into :results-1
      :parallel 10  ;; 10 concurrent workers
      (sheet/parallel "multi-score"
        (sheet/llm "score-a" ...)
        (sheet/llm "score-b" ...)
        (sheet/llm "score-c" ...))))

  (sheet/sequence "track-2"
    ...))
```

### Error Handling with Fallback

```mermaid
flowchart TB
  fb["<b>resolve address</b><br/>FALLBACK · first child that succeeds wins"]:::fb
  fb --> primary["<b>1 · geocode API</b><br/>CODE · leaf<br/><i>fast path</i>"]:::code
  fb --> secondary["<b>2 · LLM extract</b><br/>LLM · leaf<br/><i>tried only if #1 fails</i>"]:::llm
  fb --> default["<b>3 · mark unknown</b><br/>CODE · leaf<br/><i>deterministic default — always succeeds</i>"]:::code
  classDef fb fill:#7c2d12,stroke:#fb923c,color:#fff,stroke-width:2px;
  classDef code fill:#0f766e,stroke:#5eead4,color:#fff;
  classDef llm fill:#4c1d95,stroke:#c4b5fd,color:#fff;
```

Gracefully handle failures with fallback alternatives:

```clojure
(sheet/fallback "api-with-fallback"
  ;; Try primary API
  (sheet/sequence "primary"
    (sheet/code "call-primary-api"
      :fn "myapp/call-premium-api"
      :reads [:data]
      :writes [:result]))

  ;; Fallback to secondary
  (sheet/sequence "secondary"
    (sheet/code "call-fallback-api"
      :fn "myapp/call-basic-api"
      :reads [:data]
      :writes [:result]))

  ;; Last resort: use cached data
  (sheet/code "use-cache"
    :fn "myapp/get-cached-result"
    :reads [:data]
    :writes [:result]))
```

### LLM-Driven Routing

```mermaid
flowchart TB
  route["<b>route lead</b><br/>FALLBACK · first success wins"]:::fb
  route --> hot["<b>sales-ready path</b><br/>SEQUENCE"]:::seq
  hot --> q{{"<b>is it sales-ready?</b><br/>LLM-CONDITION · model judges yes/no<hr/>▸ reads&nbsp;&nbsp;lead, grade"}}:::llmc
  hot --> book["<b>book a call</b><br/>LLM · leaf<hr/>◂ writes&nbsp;&nbsp;meeting"]:::llm
  route --> nurture["<b>nurture</b><br/>LLM · leaf<br/><i>default branch when not sales-ready</i><hr/>◂ writes&nbsp;&nbsp;drip-campaign"]:::llm
  classDef fb fill:#7c2d12,stroke:#fb923c,color:#fff,stroke-width:2px;
  classDef seq fill:#1e3a8a,stroke:#60a5fa,color:#fff,stroke-width:2px;
  classDef llmc fill:#5b21b6,stroke:#ddd6fe,color:#fff;
  classDef llm fill:#4c1d95,stroke:#c4b5fd,color:#fff;
```

Use `llm-condition` for intelligent routing:

```clojure
(sheet/fallback "route-by-intent"
  (sheet/sequence "handle-complaint"
    (sheet/llm-condition "is-complaint?"
      :model "google/gemini-2.0-flash-001"
      :instruction "Is this message a complaint or expressing frustration?"
      :reads [:message])
    (sheet/llm "complaint-response" ...))

  (sheet/sequence "handle-question"
    (sheet/llm-condition "is-question?"
      :model "google/gemini-2.0-flash-001"
      :instruction "Is this message asking a question?"
      :reads [:message])
    (sheet/llm "answer-question" ...))

  ;; Default: general response
  (sheet/llm "general-response" ...))
```

### Aggregating Map-Each Results

Pattern for reducing results after map-each:

```clojure
(sheet/sequence "process-and-aggregate"
  (sheet/map-each "score-items"
    :from :items
    :as :current-item
    :into :scored-items
    :parallel 10
    (sheet/llm "score" ...))

  ;; Aggregate the results
  (sheet/code "aggregate-scores"
    :fn "myapp/compute-aggregate-stats"
    :reads [:scored-items]
    :writes [:stats :top-items :summary]))
```

### Retry Configuration

Configure retries for unreliable operations:

```clojure
(sheet/llm "external-api-call"
  :model "google/gemini-2.5-flash"
  :instruction "..."
  :reads [:input]
  :writes [:output]
  :retry {:max-attempts 3
          :backoff-ms [100 500 2000]})  ;; Exponential backoff
```

### Evaluation Judges

Define evaluation criteria at the workflow level and reference them from LLM nodes. This enables retrospective evaluation of LLM outputs for quality assurance.

#### Defining Judges

Use `sheet/judges` parallel to `sheet/blackboard`:

```clojure
(sheet/workflow "lead-qualification"
  (sheet/blackboard
    {:lead-data :string
     :analysis [:map
                [:score :double]
                [:reasoning :string]
                [:keyFactors [:vector :string]]]})

  ;; Define evaluation criteria at workflow level
  (sheet/judges
    {:analysis-completeness
     {:type :completeness
      :criteria "Must include: score (0.0-1.0), reasoning (2+ sentences), and at least 3 key factors"
      :weight 0.35}

     :analysis-grounding
     {:type :grounding
      :criteria "All key factors must cite specific data from lead-data. Score must be justified by stated reasoning."
      :weight 0.35}

     :analysis-reasoning
     {:type :reasoning
      :criteria "Reasoning must connect lead characteristics to scoring decision. Logic must be clear and non-contradictory."
      :weight 0.30}})

  (sheet/llm "analyze-lead"
    :model "google/gemini-2.5-flash"
    :instruction "Analyze this lead for sales qualification..."
    :reads [:lead-data]
    :writes [:analysis]
    :judges ["analysis-completeness" "analysis-grounding" "analysis-reasoning"]))
```

#### Judge Types

**Grounding (35% default weight)**

Detects hallucinations by checking if claims are supported by input context.

| Score | Meaning |
|-------|---------|
| 1.0 | Every claim traceable, no hallucinations |
| 0.8 | Almost all claims grounded, minor extrapolation |
| 0.6 | Some claims ungrounded but no serious hallucinations |
| 0.4 | Multiple ungrounded claims |
| 0.2 | Majority of claims not supported by context |

```clojure
{:type :grounding
 :criteria "All claims about budget must trace to explicit budget fields.
            Do not infer company size from name alone.
            Timeline claims must reference stated deadlines."}
```

**Instruction Following (25% default weight)**

Evaluates whether the LLM followed its given instruction.

| Score | Meaning |
|-------|---------|
| 1.0 | All requirements met, format correct |
| 0.8 | Most requirements met, minor format issues |
| 0.6 | Core requirements met, some missed |
| 0.4 | Several requirements missed |
| 0.2 | Instruction largely ignored |

```clojure
{:type :instruction-following
 :criteria "Response must be valid JSON.
            All requested fields must be present.
            Score must be numeric, not text."}
```

**Reasoning Quality (20% default weight)**

Evaluates coherence and logical flow of reasoning.

| Score | Meaning |
|-------|---------|
| 1.0 | Clear logical flow, well-structured argument |
| 0.8 | Sound reasoning with minor gaps |
| 0.6 | Generally logical but some unclear steps |
| 0.4 | Weak reasoning, logical gaps |
| 0.2 | Incoherent or contradictory reasoning |

```clojure
{:type :reasoning
 :criteria "Each conclusion must cite supporting evidence.
            Conflicting factors must be addressed.
            Final score must follow from stated reasoning."}
```

**Completeness (20% default weight)**

Evaluates whether all aspects of the task were addressed.

| Score | Meaning |
|-------|---------|
| 1.0 | All aspects thoroughly covered |
| 0.8 | Most aspects covered, minor omissions |
| 0.6 | Core aspects covered, some gaps |
| 0.4 | Multiple aspects missing |
| 0.2 | Majority of required aspects missing |

```clojure
{:type :completeness
 :criteria "Must address: company profile, pain points, budget signals,
            decision makers, urgency indicators.
            Each aspect needs at least one specific data point."}
```

#### Sharing Judges Across Nodes

Multiple nodes can reference the same judge definition:

```clojure
(sheet/workflow "multi-step-analysis"
  (sheet/judges
    {:common-grounding
     {:type :grounding
      :criteria "All claims must cite specific input data"}})

  (sheet/sequence "main"
    (sheet/llm "step-1"
      :reads [:input-1]
      :writes [:output-1]
      :judges ["common-grounding"])
    (sheet/llm "step-2"
      :reads [:output-1]
      :writes [:output-2]
      :judges ["common-grounding"])))
```

#### Custom Judge Type

Reference a custom evaluation sheet for domain-specific validation:

```clojure
{:type :custom
 :sheet-id #uuid "abc123..."  ;; ID of custom judge workflow
 :weight 0.25}
```

#### Evaluation is Retrospective

Judges are evaluated **after** execution, not during. This keeps production latency low while enabling comprehensive batch evaluation:

```clojure
;; After workflow executions...
(eval/evaluate-node-traces event-store
  {:sheet-id sheet-id
   :node-id "analyze-lead"
   :limit 50})
;; => Evaluates historical traces using defined judge criteria
```

For inline validation during execution, use `condition` or `llm-condition` nodes instead.

---

## Tracing & GEPA Primitives

ORC includes a comprehensive tracing system that enables full observability of workflow execution. This powers **GEPA** (Goal-directed, Evaluative, Planning Agent) capabilities by providing the primitives needed to analyze, debug, and optimize AI workflows.

### Why Tracing Matters

Every workflow execution is automatically traced, capturing:
- **Timing data** - Start/end times and duration for every node
- **Input/output snapshots** - Blackboard state before and after each node
- **Execution path** - Which branches were taken, which fallbacks triggered
- **Error details** - Where failures occurred and why
- **Token usage** - LLM token consumption for cost analysis

This enables:
1. **Debugging** - Understand exactly what happened in any execution
2. **Performance analysis** - Find bottlenecks and optimize
3. **A/B testing** - Compare different workflow versions
4. **Reproducibility** - Re-run exact versions with same inputs
5. **Cost tracking** - Monitor LLM token usage

### Trace Architecture

The tracing system has three layers:

```
┌─────────────────────────────────────────────────────────────┐
│                    Workflow Execution                        │
├─────────────────────────────────────────────────────────────┤
│  Layer 1: Internal Trace (Always-On)                        │
│  - Stores in event store                                     │
│  - Full node execution details                               │
│  - Blackboard snapshots                                      │
├─────────────────────────────────────────────────────────────┤
│  Layer 2: Langfuse Integration (Optional)                    │
│  - External observability                                    │
│  - Real-time monitoring                                      │
│  - Token usage tracking                                      │
├─────────────────────────────────────────────────────────────┤
│  Layer 3: Local Collector (Development)                      │
│  - In-memory for REPL testing                               │
│  - No external dependencies                                  │
└─────────────────────────────────────────────────────────────┘
```

### Execution Result with Trace

Every `execute` call returns trace metadata:

```clojure
(def result (sheet/execute ctx sheet-id inputs))

result
;; => {:status :success              ; or :failure, :timeout
;;     :outputs {:key value ...}     ; Final blackboard state
;;     :duration-ms 1234             ; Total execution time
;;     :trace-id #uuid "..."         ; Unique trace identifier
;;     :executed-version 1}          ; If running published version
```

### Trace Data Structure

Each trace contains comprehensive execution data:

```clojure
{:trace-id #uuid "abc123..."
 :sheet-id #uuid "def456..."
 :version-number 1                    ; nil for draft
 :started-at #inst "2025-01-18T..."
 :completed-at #inst "2025-01-18T..."
 :duration-ms 2500
 :status :success                     ; :success, :failure, :timeout
 :input-snapshot {:leads [...]}       ; Initial blackboard
 :output-snapshot {:results [...]}    ; Final blackboard
 :error nil                           ; Error message if failed
 :node-traces [...]                   ; Per-node execution details
}
```

### Node Trace Structure

Each node execution is captured with full context:

```clojure
{:trace-instance-id #uuid "..."      ; Unique per execution
 :node-id #uuid "..."                ; References node definition
 :node-name "analyze-lead"           ; User-friendly name
 :node-type :leaf                    ; :leaf, :sequence, :parallel, etc.
 :parent-id #uuid "..."              ; Parent node (for hierarchy)
 :path ["main" "qualification-track" "analyze-lead"]  ; Full path from root
 :child-index 0                      ; Position among siblings
 :status :success
 :started-at #inst "..."
 :completed-at #inst "..."
 :duration-ms 450
 :inputs {:current-lead {...}}       ; Node inputs
 :outputs {:lead-analysis {...}}     ; Node outputs
 :error nil}
```

### Querying Traces

#### Get a Single Trace

```clojure
;; Retrieve full trace by ID
(sheet/get-trace ctx trace-id)
;; => {:trace-id ... :node-traces [...] ...}
```

#### Get Traces for a Sheet

```clojure
;; All recent traces
(sheet/get-traces ctx {:sheet-id sheet-id})

;; Filter by status
(sheet/get-traces ctx {:sheet-id sheet-id
                       :status :failure})

;; Filter by version
(sheet/get-traces ctx {:sheet-id sheet-id
                       :version-number 2})

;; Filter by time range
(sheet/get-traces ctx {:sheet-id sheet-id
                       :since #inst "2025-01-18T00:00:00Z"
                       :limit 50})

;; Filter to traces that executed a specific node
(sheet/get-traces ctx {:sheet-id sheet-id
                       :node-id problematic-node-id})
```

### Node Statistics

Aggregate performance data across executions:

```clojure
(sheet/node-stats ctx {:sheet-id sheet-id})
;; => [{:node-id #uuid "..."
;;      :node-name "analyze-lead"
;;      :node-type :leaf
;;      :execution-count 150
;;      :success-count 145
;;      :failure-count 5
;;      :success-rate 0.967
;;      :avg-duration-ms 423.5
;;      :p50-duration-ms 380.0      ; Median
;;      :p95-duration-ms 890.0      ; 95th percentile
;;      :common-errors [{:message "Rate limit exceeded" :count 3}]}
;;     ...]

;; Filter to specific nodes
(sheet/node-stats ctx {:sheet-id sheet-id
                       :node-ids [node-1 node-2]})

;; Filter by time range
(sheet/node-stats ctx {:sheet-id sheet-id
                       :since #inst "2025-01-15T00:00:00Z"})
```

### Version Management

#### Publishing Versions

Lock in a workflow state for reproducible execution:

```clojure
;; Publish current draft as version 1
(sheet/publish-version! ctx sheet-id "Initial release")

;; Make changes to draft...

;; Publish as version 2
(sheet/publish-version! ctx sheet-id "Added error handling")
```

#### Executing Specific Versions

```clojure
;; Execute the current draft
(sheet/execute ctx sheet-id inputs)

;; Execute a specific published version
(sheet/execute ctx sheet-id inputs :use-version 1)

;; Or use the command directly
(sheet/execute-version ctx {:sheet-id sheet-id
                            :version-number 1
                            :inputs inputs})
```

#### Comparing Versions

```clojure
(sheet/diff-versions ctx {:sheet-id sheet-id
                          :from-version 1
                          :to-version 2})
;; => {:node-diff {:added-nodes [...]
;;                 :removed-nodes [...]
;;                 :modified-nodes [...]}
;;     :blackboard-diff {:added [...]
;;                       :removed [...]
;;                       :modified [...]}}
```

### Batch Execution

Run multiple inputs through the same workflow:

```clojure
(sheet/batch-execute ctx {:sheet-id sheet-id
                          :inputs-list [input-1 input-2 input-3 ...]})
;; => {:results [{:trace-id ... :status :success}
;;               {:trace-id ... :status :success}
;;               {:trace-id ... :status :failure}]
;;     :total-executions 3
;;     :successful-count 2
;;     :failed-count 1
;;     :duration-ms 5430}

;; With specific version
(sheet/batch-execute ctx {:sheet-id sheet-id
                          :version-number 2
                          :inputs-list inputs})
```

### Langfuse Integration

For external observability, configure Langfuse:

```clojure
;; In config.edn
{:langfuse/enabled true
 :langfuse/public-key "pk-..."
 :langfuse/secret-key "sk-..."
 :langfuse/host "https://cloud.langfuse.com"}
```

When enabled, traces are automatically sent to Langfuse with:
- Trace events for overall workflow execution
- Span events for each node
- Generation events for LLM nodes (with token usage)

### Visualization

The ORC UI provides rich visualization tools:

#### Gantt Chart
- Timeline view of node execution
- Shows parallel execution clearly
- Color-coded by status (green/red/yellow)
- Click for node details

#### Flame Graph
- Hierarchical view of execution time
- Width = duration
- Color = node type
- Identify bottlenecks visually

#### Timeline View
- Blackboard state evolution
- Shows when each key was written
- Old → New value transitions
- Trace data flow through workflow

### Tracing Best Practices

1. **Use meaningful node names** - They appear in all trace views
2. **Keep blackboard keys organized** - Makes trace inspection easier
3. **Version before production** - Always publish before deploying
4. **Monitor node stats** - Watch for degrading success rates
5. **Set up alerts** - Use Langfuse for failure notifications

### Example: Debugging a Failed Execution

```clojure
;; 1. Get recent failures
(def failures (sheet/get-traces ctx {:sheet-id sheet-id
                                     :status :failure
                                     :limit 10}))

;; 2. Examine a specific failure
(def trace (sheet/get-trace ctx (:trace-id (first failures))))

;; 3. Find the failing node
(def failed-nodes
  (filter #(= :failure (:status %)) (:node-traces trace)))

(first failed-nodes)
;; => {:node-name "score-budget"
;;     :error "Rate limit exceeded"
;;     :inputs {:current-lead {...}}
;;     ...}

;; 4. Check if it's a pattern
(sheet/node-stats ctx {:sheet-id sheet-id
                       :node-ids [(:node-id (first failed-nodes))]})
;; => Shows failure rate, common errors
```

---

## API Reference

### Building Workflows

#### `sheet/workflow`
```clojure
(sheet/workflow "workflow-name"
  (sheet/blackboard {...})
  (sheet/sequence "root" ...))
```

Creates a workflow definition. The name is the identity - same name = same sheet-id.

#### `sheet/build-workflow!`
```clojure
(sheet/build-workflow! ctx workflow-def)
;; => sheet-id (UUID)
```

Builds or updates a workflow. **Idempotent** - a SHA-256 content hash of the workflow definition is stored on the sheet. If the definition hasn't changed since the last build, this is a true no-op (zero events emitted). If the definition has changed, the sheet is cleared and rebuilt with the new content. Safe to call on every application startup.

### Executing Workflows

#### `sheet/execute`
```clojure
(sheet/execute ctx sheet-id {:input-key value})
;; => {:status :success/:failure
;;     :outputs {:output-key value}
;;     :error "..." (if failed)}
```

Executes a workflow with the given inputs.

Options:
- `:use-version n` - Execute a specific published version

### Export/Import

#### `sheet/export-sheet`
```clojure
(sheet/export-sheet ctx sheet-id)
;; => {:version 1
;;     :sheet {:name "..." :id uuid}
;;     :blackboard-schema {...}
;;     :nodes {...}}
```

Exports a sheet as an EDN structure.

#### `sheet/import-sheet`
```clojure
(sheet/import-sheet ctx exported-data)
;; => new-sheet-id
```

Imports an exported sheet, creating a new sheet.

#### `sheet/export-to-dsl`
```clojure
(sheet/export-to-dsl exported-data)
(sheet/export-to-dsl exported-data :ns "sheet")
(sheet/export-to-dsl exported-data :pretty? false)
;; => "(workflow \"name\" ...)"
```

Generates Clojure DSL code from exported data.

#### `sheet/save-sheet-as-dsl!`
```clojure
(sheet/save-sheet-as-dsl! ctx sheet-id "path/to/workflow.clj")
```

Saves a sheet directly to a .clj file.

---

## Best Practices

### Naming Conventions

```clojure
;; Workflows: noun-phrase describing the purpose
"lead-qualification"
"document-processing"
"customer-onboarding"

;; Sequences: noun-phrase describing the flow
"main"
"qualification-track"
"scoring-pipeline"

;; LLM nodes: verb-phrase describing the action
"analyze-lead"
"generate-summary"
"score-relevance"

;; Code nodes: verb-phrase describing the computation
"compute-score"
"filter-items"
"merge-results"

;; Conditions: question form
"check-qualified"
"is-premium?"
"has-budget"
```

### Blackboard Design

1. **Use descriptive names** - `lead-analysis` not `analysis1`
2. **Group related keys** - Use consistent prefixes
3. **Specify schemas** - Catch errors early with Malli types
4. **Document complex schemas** - Add comments for nested structures

```clojure
(sheet/blackboard
  ;; Input data
  {:input-leads [:vector [:map-of :keyword :any]]
   :product-catalog [:vector [:map-of :keyword :any]]

   ;; Intermediate state
   :current-lead [:map-of :keyword :any]
   :lead-scores [:map [:budget :double]
                      [:need :double]
                      [:authority :double]]

   ;; Output data
   :qualified-leads [:vector [:map-of :keyword :any]]
   :final-report [:map-of :keyword :any]})
```

### LLM Prompt Engineering

1. **Be specific about output format**
```clojure
:instruction "Analyze X and provide:
- fieldA: Description of what goes here
- fieldB: Description of what goes here"
```

2. **Provide context in reads**
```clojure
:reads [:current-item :user-preferences :historical-data]
```

3. **Use appropriate models**
```clojure
;; Complex reasoning
:model "google/gemini-2.5-flash"

;; Simple tasks
:model "google/gemini-2.0-flash-001"

;; High quality generation
:model "openai/gpt-4o"
```

### Code Executor Patterns

```clojure
;; Standard pattern
(defn my-function
  [{:keys [inputs]}]
  (let [input-a (:input-a inputs)
        input-b (:input-b inputs)
        result (process input-a input-b)]
    {:output-key result}))

;; Multiple outputs
(defn compute-stats
  [{:keys [inputs]}]
  (let [items (:items inputs)
        stats (calculate-stats items)]
    {:mean (:mean stats)
     :median (:median stats)
     :count (count items)}))

;; Filtering pattern
(defn filter-items
  [{:keys [inputs]}]
  (let [items (:items inputs)
        criteria (:criteria inputs)
        filtered (filter #(matches? % criteria) items)]
    {:filtered-items (vec filtered)
     :removed-count (- (count items) (count filtered))}))
```

### Testing Workflows

```clojure
;; Build once, test with different inputs
(def sheet-id (sheet/build-workflow! ctx my-workflow))

;; Test happy path
(let [result (sheet/execute ctx sheet-id sample-input)]
  (assert (= :success (:status result)))
  (assert (some? (get-in result [:outputs :expected-key]))))

;; Test edge cases
(let [result (sheet/execute ctx sheet-id edge-case-input)]
  (assert (= :success (:status result)))
  (assert (= expected-value (get-in result [:outputs :key]))))

;; Test error handling
(let [result (sheet/execute ctx sheet-id invalid-input)]
  (assert (= :failure (:status result))))
```

---

## Next Steps

1. **Start from Phase 1 of [GETTING-STARTED.md](GETTING-STARTED.md)** — wire a context and run your first tree
2. **Compose subbehaviors** — once a single tree works, factor reusable pieces into their own sheets and `:delegate` to them (see [ORC-PRINCIPLES.md](ORC-PRINCIPLES.md) Principles 2–3)
3. **Reach for `:repl-researcher`** when a step needs open-ended exploration whose shape you can't draw up front (see [RLM-GUIDE.md](RLM-GUIDE.md))
4. **Build your own** — start with a simple workflow and iterate

Happy workflow building!
