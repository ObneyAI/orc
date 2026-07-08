# GEPA Integration Guide

**GEPA** (Genetic-Pareto Prompt Optimizer) automatically improves LLM instructions through reflective mutation and Pareto selection. This guide documents how GEPA integrates with the ORC workflow service.

> **Provenance.** GEPA comes from the [DSPy](https://github.com/stanfordnlp/dspy) line of work on programmatic prompt optimization — a good reference if you want the research background. ORC ships a native-Clojure implementation (built on DSCloj, the Clojure structured-LLM library orc uses as its LLM base), so there's no Python in the loop.

---

## Start here: making your existing tree better

You already have a behavior tree with an `:llm` node, and you hand-wrote its instruction. It works. This guide is about what happens next.

> **Your `:llm` node works, but the instruction is your first guess. GEPA automatically searches for a better instruction — running your tree against examples, scoring with judges, and proposing improved instructions through reflective mutation.**

Here is the whole arc, from the tree you already have to an instruction you didn't have to write. Each step is a few lines at the REPL.

### Your starting point

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])

(def my-tree
  (sheet/workflow "support-reply"
    (sheet/blackboard
      {:ticket :string
       :reply  :string})
    (sheet/llm "draft-reply"
      :model "google/gemini-2.0-flash-001"
      :instruction "Write a reply to the support ticket."   ;; <- your first guess
      :reads  [:ticket]
      :writes [:reply])))

(def sheet-id (sheet/build-workflow! context my-tree))
```

### Step 1 — make one `:llm` node optimizable

You don't restructure anything. GEPA optimizes the instruction **in place**: it walks your tree, finds every `:llm` node, and reads each node's `:instruction` keyed by the node's `:name`. That set of `{node-name -> instruction}` strings becomes the **seed candidate** GEPA starts mutating from. At evaluation time GEPA patches its candidate instruction back onto the node *by name*, runs the tree, and never modifies your saved sheet.

> Verified in source: `gepa/core/todo_processors.clj` `extract-workflow-instructions` (collects `{node-name -> instruction}` from `:llm` / `:ai` / `:llm-condition` nodes) and `orc-service .../core/todo_processors.clj` `apply-instruction-override` (re-applies a candidate instruction when its key matches the node's `:name`).

So the only requirement to make a node optimizable is that it has a stable `:name` — which `"draft-reply"` already has. GEPA will vary *that node's* `"Write a reply to the support ticket."`. You do **not** move the instruction into the blackboard, and you do **not** touch `:reads`.

> If your tree has several `:llm` nodes, GEPA optimizes them all at once (round-robin per component), each keyed by its node name. To optimize exactly one node, point GEPA at a sheet that contains only that node — see [Optimizing inside a subbehavior](#optimizing-inside-a-subbehavior).

### Step 2 — build a trainset and valset from examples you already have

GEPA needs example inputs to run your tree against. An example is a flat map whose keys match your node's `:reads` (string keys). You almost certainly already have a handful of representative inputs — past tickets, sample questions, fixtures from a test.

- **`:trainset`** — examples GEPA samples *failures* from, to show the proposer LLM what is going wrong.
- **`:valset`** — examples GEPA *scores* candidates on (this is the number that has to go up).

```clojure
(def trainset
  [{"ticket" "I was charged twice for my subscription this month."}
   {"ticket" "How do I export my data to CSV?"}
   {"ticket" "The mobile app crashes when I open settings."}])

(def valset
  [{"ticket" "My password reset email never arrives."}
   {"ticket" "Can I downgrade my plan mid-cycle?"}])
```

With a **judge** metric you don't need a gold answer per example — the judges score the reply against the ticket. (Structural metrics like `make-exact-match-metric` *do* need an expected value; put it under an `"answer"`, `"expected"`, or `"label"` key.)

### Step 3 — run `gepa/optimize!` with a judge metric

```clojure
(require '[ai.obney.orc.gepa.interface :as gepa])

(def result
  (gepa/optimize! context
    {:sheet-id  sheet-id
     :trainset  trainset
     :valset    valset
     :metric-fn (gepa/make-judge-metric {:grounding 0.5 :completeness 0.5})
     :config    {:max-metric-calls 30}
     :block?    true}))      ;; block? true = wait and return the result (REPL-friendly)
```

`make-judge-metric` takes a **single weight map** — the keys are judge dimensions, the values are their relative weights (normalized internally). Each candidate's reply is scored by those weighted judges, and the judges' weakest-first feedback is threaded back to the proposer so the next instruction attacks the real weaknesses (see [How judge feedback drives mutation](#how-judge-feedback-drives-mutation)).

Equivalent shortcut — pass `:judges` instead of `:metric-fn` and `optimize!` builds the judge metric for you:

```clojure
(gepa/optimize! context
  {:sheet-id sheet-id :trainset trainset :valset valset
   :judges {:grounding 0.5 :completeness 0.5}
   :config {:max-metric-calls 30} :block? true})
```

### Step 4 — read the best instruction back and use it

With `:block? true`, `optimize!` returns when the run finishes:

```clojure
result
;; => {:optimization-id #uuid "..."
;;     :status :completed
;;     :best-candidate {:instructions {"draft-reply" "When replying to a support ticket, first restate..."}
;;                      :score 0.87
;;                      :candidate-id #uuid "..."}
;;     :best-score 0.87
;;     :duration-ms 48213}
```

The improved instruction is keyed by your node name:

```clojure
(get-in result [:best-candidate :instructions "draft-reply"])
;; => "When replying to a support ticket, first restate the customer's problem in one sentence,
;;     then give concrete next steps grounded only in the ticket's facts..."
```

Paste that string back into your `:llm` node's `:instruction` and rebuild — your tree now runs the optimized instruction by default. (You can also fetch it later with `(gepa/get-best-candidate context (:optimization-id result))`.)

### Step 5 — keep going, or move to a subbehavior

That is the full loop on a single node. Two natural next moves:

- **Optimize a node that lives inside a delegated child sheet** — each sub-sheet is its own independently-optimizable target. See [Optimizing inside a subbehavior](#optimizing-inside-a-subbehavior).
- **Understand what GEPA does *not* touch** — GEPA tunes static `:llm` instruction strings; it does not change how an RLM `:repl-researcher` designs its tree. See [GEPA vs. `:auto-classify?`](#gepa-is-independent-of-ontology-and-living-descriptions).

For a fuller hands-on session, see [GETTING-STARTED.md](GETTING-STARTED.md) Phase 4.

---

## Optimizing inside a subbehavior

ORC trees compose: a `:delegate` node runs another sheet (a child "subbehavior") with its own isolated blackboard. Because GEPA optimizes a **sheet** — and a delegated child *is* a sheet with its own `sheet-id` — **each sub-sheet is independently optimizable**. You point `gepa/optimize!` at whichever sheet-id owns the `:llm` node you want to improve.

```clojure
;; A child subbehavior: classify the ticket before the parent drafts a reply.
(def classify-tree
  (sheet/workflow "classify-ticket"
    (sheet/blackboard
      {:ticket   :string
       :category :string})
    (sheet/llm "classify"
      :model "google/gemini-2.0-flash-001"
      :instruction "Classify the ticket into one category."   ;; <- optimizable, independently
      :reads  [:ticket]
      :writes [:category])))

(def classify-sheet-id (sheet/build-workflow! context classify-tree))

;; The parent delegates to it.
(def parent-tree
  (sheet/workflow "support-reply"
    (sheet/blackboard
      {:ticket   :string
       :category :string
       :reply    :string})
    (sheet/sequence "pipeline"
      (sheet/delegate "classify-step"
        :target-sheet-id classify-sheet-id
        :reads  [:ticket]
        :writes [:category])
      (sheet/llm "draft-reply"
        :model "google/gemini-2.0-flash-001"
        :instruction "Write a reply to the support ticket."
        :reads  [:ticket :category]
        :writes [:reply]))))

(def parent-sheet-id (sheet/build-workflow! context parent-tree))
```

Now you have **two independent optimization targets**:

```clojure
;; Optimize the child's "classify" instruction in isolation.
(gepa/optimize! context
  {:sheet-id  classify-sheet-id
   :trainset  trainset :valset valset
   :metric-fn (gepa/make-judge-metric {:instruction-following 0.6 :grounding 0.4})
   :config    {:max-metric-calls 30} :block? true})

;; Separately, optimize the parent's "draft-reply" instruction.
(gepa/optimize! context
  {:sheet-id  parent-sheet-id
   :trainset  trainset :valset valset
   :metric-fn (gepa/make-judge-metric {:grounding 0.5 :completeness 0.5})
   :config    {:max-metric-calls 30} :block? true})
```

Each run only sees the `:llm` nodes in *its* sheet (`extract-workflow-instructions` walks one sheet), so the child's instruction and the parent's instruction evolve separately, against the judges that matter for each. This is the same composition pattern ORC uses everywhere — optimize the leaves of a subtree without disturbing the rest of the tree. (Optimizing the parent runs the real delegate at execution time, so the parent's scores reflect whatever instruction the child currently has.)

---

> **GEPA is independent of ontology and `:auto-classify?`.**
> - `components/gepa/deps.edn` lists only two ORC deps: `mulog` and `orc/evaluation`. No ontology, no ColBERT.
> - GEPA optimizes instruction strings inside static `orc/llm` nodes. `:auto-classify?` shapes RLM tree design (see [RLM-GUIDE.md](RLM-GUIDE.md)). They are orthogonal — neither requires the other.
>
> For a hands-on walkthrough, see [GETTING-STARTED.md](GETTING-STARTED.md) Phase 4.

```edn
{:paths ["src" "resources"]
 :deps {com.brunobonacci/mulog {:mvn/version "0.9.0"}
        ;; gepa's make-judge-metric runs the tier-1 evaluation judges as the
        ;; real GEPA metric (grounding/instruction-following/reasoning/
        ;; completeness), so it depends on the evaluation component.
        orc/evaluation {:local/root "../evaluation"}}
 :aliases {:test {:extra-paths ["test"]
                  :extra-deps {}}}}
```

---

## GEPA is independent of ontology and Living Descriptions

GEPA ships as its own Polylith component (`components/gepa`) with zero ontology and zero ColBERT dependencies. `components/gepa/deps.edn` verbatim:

```edn
{:paths ["src" "resources"]
 :deps {com.brunobonacci/mulog {:mvn/version "0.9.0"}
        ;; gepa's make-judge-metric runs the tier-1 evaluation judges as the
        ;; real GEPA metric (grounding/instruction-following/reasoning/
        ;; completeness), so it depends on the evaluation component.
        orc/evaluation {:local/root "../evaluation"}}
 :aliases {:test {:extra-paths ["test"]
                  :extra-deps {}}}}
```

Two deps: `mulog` (structured logging) and `orc/evaluation` (the tier-1 judges that score candidates). No model download, no ontology component, no ColBERT bridge. Pareto selection and reflective mutation run entirely in the JVM as native Clojure.

**Orthogonal to `:auto-classify?`.** GEPA and `:auto-classify?` are independent actuators that solve different problems:

| | GEPA (`gepa/optimize!`) | `:auto-classify?` (RLM) |
|---|---|---|
| **Operates on** | Static `:llm` instruction strings | How a `:repl-researcher` designs its tree |
| **When it runs** | Off-line optimization loop on a trainset | At Phase 1 of every `:repl-researcher` execution |
| **What it changes** | The `:instruction` string on a named `:llm` node | The pattern prepended to the researcher's context |
| **Requires the other?** | No — zero RLM dep | No — zero GEPA dep |

You can run GEPA on a static tree with zero RLM and zero ontology. You can use `:auto-classify?` on a researcher node without GEPA. They plug into separate parts of the execution stack.

---

## Table of Contents

1. [Start here: making your existing tree better](#start-here-making-your-existing-tree-better)
2. [Optimizing inside a subbehavior](#optimizing-inside-a-subbehavior)
3. [Overview](#overview)
4. [How judge feedback drives mutation](#how-judge-feedback-drives-mutation)
5. [Architecture](#architecture)
6. [Core Components](#core-components)
7. [Creating GEPA-Compatible Workflows](#creating-gepa-compatible-workflows)
8. [Running GEPA Optimization](#running-gepa-optimization)
9. [Evaluation Judges](#evaluation-judges)
10. [Event Store & Data Collection](#event-store--data-collection)
11. [Verification Test](#verification-test)
12. [Testing GEPA Workflows](#testing-gepa-workflows)
13. [Troubleshooting](#troubleshooting)

---

## Overview

### What is GEPA?

GEPA is a prompt optimization framework that:
- **Reflective Mutation**: Uses an LLM to analyze failures and propose improved instructions (not reinforcement learning)
- **Pareto Selection**: Maintains diversity by tracking per-example best candidates (not a single global best)
- **Iterative Improvement**: Runs multiple generations, each proposing better instruction variants

### Integration Approach

The shipped implementation is **native Clojure**. All algorithm logic (Pareto selection, reflective mutation, subsample-first evaluation) runs in the JVM, event-sourced through Grain:
- `gepa/optimize!` emits a `:gepa/optimization-started` event and returns a result channel
- Grain todo-processors drive the optimization state machine forward on each event
- `make-judge-metric` runs the tier-1 evaluation judges as the real scoring function
- Judge feedback threads into the reflective dataset so the proposer LLM sees *why* candidates scored low

---

## How judge feedback drives mutation

The core GEPA claim is that mutation quality improves when the proposer sees *why* a candidate scored low — not just the number. This is the chain, verified from source:

**Step 1 — `make-judge-metric` produces `ScoreWithFeedback`** (`metrics.clj:205-234`)

```clojure
(defn make-judge-metric
  "Create a real GEPA metric backed by the orc tier-1 evaluation judges.
   ...
   The returned fn has signature (fn [input output] -> {:score :feedback}):
   - runs each weighted judge dimension IN PARALLEL (futures — sequential judge
     calls timed out GEPA budgets), then
   - returns the weight-normalized [0,1] :score AND a weakest-first :feedback
     string aggregating every dimension's :feedback/:reasoning.

   GEPA's execute-workflow path accepts either a bare number or this
   {:score :feedback} map (see todo-processors/score+feedback-of), and threads
   the :feedback into the reflective dataset."  ; metrics.clj:206-223
  [judges]
  (let [{:keys [weights task]} (normalize-judge-config judges)]
    (fn judge-metric
      [input output]
      (let [trace-data (->trace-data input output task)
            futs (mapv (fn [[dim weight]]
                         [dim (future (run-judge-dimension dim weight trace-data))])
                       weights)
            dimension-results (mapv (fn [[_dim fut]] @fut) futs)]
        {:score (weighted-combine dimension-results)         ; metrics.clj:233
         :feedback (format-judge-feedback dimension-results)}))))  ; metrics.clj:234
```

Dimensions are sorted weakest-first by `format-judge-feedback` (`metrics.clj:97-111`) so the proposer attacks the biggest problems first. Example feedback string:

```
grounding (0.25): Claims not supported by input. [reasoning: The response asserts X but...]
completeness (0.50): Key sections missing. [reasoning: ...]
```

**Step 2 — `score+feedback-of` normalizes the metric result** (`todo_processors.clj:48-59`)

Both judge metrics (`{:score :feedback}` maps) and structural metrics (bare numbers from `make-exact-match-metric` / `make-contains-metric`) are unified into one shape. Judge feedback is carried; structural metrics yield `nil` feedback:

```clojure
(defn score+feedback-of
  "Normalize a metric-fn return value into {:score double :feedback string-or-nil}.
   The judge metric returns {:score :feedback}; the structural metrics
   (exact-match/contains) and user metric-fns return a bare number."
  [metric-result]
  (if (map? metric-result)
    {:score    (double (or (:score metric-result) 0.0))
     :feedback (:feedback metric-result)}   ; carries judge diagnosis verbatim
    {:score    (double (or metric-result 0.0))
     :feedback nil}))                       ; structural metrics: no feedback
```

**Step 3 — failing examples are wrapped into `ReflectiveExample` triplets** (`reflection.clj:28-43`)

Low-scoring examples (score below the Pareto frontier) are formatted as `ReflectiveExample` maps:

```clojure
{"Inputs"            {...candidate-inputs...}
 "Generated Outputs" {...node-outputs...}
 "Feedback"          "grounding (0.25): claims not supported...\ncompleteness (0.50): ..."}
```

The `:feedback` string from the judge is threaded in here verbatim — the proposer LLM sees the judge's adversarial diagnosis alongside each failing output.

**Step 4 — the proposer LLM generates a new instruction candidate** (`proposer.clj`)

The formatted reflective dataset is injected into the proposer prompt. The proposer (default: `claude-sonnet-4`) reads the triplets, diagnoses the root cause across all failing examples, and emits a new instruction string. This is the only LLM call in the mutation step.

**Full chain:**

```
make-judge-metric (metrics.clj:205-234)
  → {:score N :feedback "weakest-first diagnosis"}
    score+feedback-of (todo_processors.clj:48-59)
      → {:score N :feedback "..."}   (normalized, nil for structural)
        make-reflective-example (reflection.clj:28-43)
          → {"Inputs" ... "Generated Outputs" ... "Feedback" "..."}
            proposer LLM (proposer.clj)
              → new instruction candidate string
```

See also [`JUDGE-ARCHITECTURE.md`](JUDGE-ARCHITECTURE.md) — "How GEPA consumes judge scores."

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          gepa/optimize!                                   │
│  (gepa/interface.clj)                                                     │
│                                                                           │
│  Emits :gepa/optimization-started → event-sourced state machine begins   │
└───────────────────────────────┬──────────────────────────────────────────┘
                                │ Grain todo-processors drive the loop
                                ▼
┌──────────────────────────────────────────────────────────────────────────┐
│              todo-processors (optimization state machine)                 │
│  gepa/core/todo_processors.clj                                            │
│                                                                           │
│  optimization-started   → create seed candidate                          │
│  candidate-created      → evaluate on full trainset                      │
│  candidate-evaluated    → update Pareto frontier                         │
│  frontier-updated       → propose mutation                               │
│  mutation-proposed      → evaluate on subsample (3 examples) first       │
│  subsample-evaluated    → if improved: full eval; else: next mutation     │
└──────────┬────────────────────────────────────────┬───────────────────────┘
           │ metric-fn call                          │ proposer call
           ▼                                         ▼
┌────────────────────────────┐          ┌────────────────────────────────────┐
│  make-judge-metric          │          │  proposer.clj                       │
│  (metrics.clj:205-234)      │          │                                      │
│                             │          │  Builds reflective dataset:           │
│  Runs tier-1 judges         │          │  (Inputs / Generated Outputs /        │
│  IN PARALLEL (futures):     │          │   Feedback triplets)                  │
│   grounding                 │          │                                      │
│   instruction-following     │          │  Calls reflection-lm                 │
│   reasoning                 │          │  (default: claude-sonnet-4)          │
│   completeness              │          │  → proposes new instruction variant  │
│                             │          └────────────────────────────────────┘
│  Returns ScoreWithFeedback: │
│  {:score    0.42            │
│   :feedback "grounding      │
│   (0.25): claims not        │
│   supported by input..."}   │
└────────────────────────────┘

ORC Sheet execution + Evaluation Judges
  sheet/execute:        runs behavior tree with candidate instruction → outputs
  evaluation tier-1:   grades outputs with adversarial score + feedback per dimension
```

> **Note:** The diagram above describes the shipped **native Clojure** implementation. All algorithm logic (Pareto selection, reflective mutation, subsample-first evaluation) runs in the JVM, event-sourced through Grain.

---

## Core Components

### 1. Public API (`gepa.interface`)

**File:** `components/gepa/src/ai/obney/orc/gepa/interface.clj`

Require it as:

```clojure
(require '[ai.obney.orc.gepa.interface :as gepa])
```

#### `optimize!`
Main entry point. Takes the Grain `context` and an options map.

```clojure
(gepa/optimize! context
  {:sheet-id  sheet-id
   :trainset  trainset                                  ;; failures sampled for reflection
   :valset    valset                                    ;; examples candidates are scored on
   :metric-fn (gepa/make-judge-metric {:grounding 0.5 :completeness 0.5})
   :config    {:max-metric-calls 50}
   :block?    true})
```

**Options:**
| Option | Type | Description |
|--------|------|-------------|
| `:sheet-id` | uuid | Workflow whose `:llm` node instructions are optimized |
| `:trainset` | vector | Example input maps (string keys) sampled for reflective feedback |
| `:valset` | vector | Example input maps scored to drive selection |
| `:metric-fn` | fn | Scoring function — e.g. `make-judge-metric`, `make-exact-match-metric` |
| `:judges` | map | Shortcut: a weight map; `optimize!` builds the judge metric for you |
| `:config` | map | Overrides, e.g. `{:max-metric-calls 50 :reflection-lm "..."}` |
| `:inherit-from-previous` | bool | Auto-seed from prior runs on the same sheet (default `true`) |
| `:block?` | bool | If `true`, wait and return the result (default `false`, returns immediately) |
| `:timeout-ms` | int | Max wait when blocking (default `300000`) |

**Returns (`:block? true`):**
```clojure
{:optimization-id #uuid "..."
 :status :completed              ;; or :failed | :timeout
 :best-candidate {:instructions {"draft-reply" "When provided with a ticket, ..."}
                  :score 0.783
                  :candidate-id #uuid "..."}
 :best-score 0.783
 :duration-ms 48213}
```

The `:instructions` map is keyed by `:llm` node `:name` — pull the winning string with `(get-in result [:best-candidate :instructions "draft-reply"])`.

#### `make-judge-metric`
Builds a metric backed by the tier-1 evaluation judges. Takes a **single weight map** (NOT a context/sheet-id/judge-list signature):

```clojure
(gepa/make-judge-metric {:grounding 0.5 :completeness 0.5})
;; full set:
(gepa/make-judge-metric {:grounding 0.35 :instruction-following 0.25
                         :reasoning 0.20 :completeness 0.20})
```

#### Structural metrics
For exact / substring matching against an expected value in the example:

```clojure
(gepa/make-exact-match-metric "answer")   ;; 1.0 if output["answer"] == expected["answer"]
(gepa/make-contains-metric "answer")      ;; 1.0 if expected is a substring of output
```

#### Inspecting a run
```clojure
(gepa/get-progress context optimization-id)        ;; status, budget used, frontier size
(gepa/get-best-candidate context optimization-id)  ;; current best candidate map
(gepa/get-pareto-frontier context optimization-id) ;; frontier members + per-instance bests
(gepa/list-optimizations context :sheet-id sheet-id)
```

### 2. Algorithm Implementation

The shipped implementation is **native Clojure**. All optimization logic runs in the JVM, event-sourced through Grain.

| File | Role |
|------|------|
| `components/gepa/core/todo_processors.clj` | Optimization state machine (Pareto, mutation loop) |
| `components/gepa/core/metrics.clj` | `make-judge-metric` — judge-backed scoring with parallel futures |
| `components/gepa/core/proposer.clj` | Reflective mutation — the single proposer LLM call per generation |
| `components/gepa/core/reflection.clj` | `ReflectiveExample` triplet formatting |

> The full optimization loop — Pareto selection, reflective mutation, common-ancestor merge, and cross-run inheritance — is implemented natively in Clojure and stored as Grain events, so every step is observable on the event stream.

### 3. Evaluation Judges

**File:** `components/evaluation/src/ai/obney/workshop/evaluation/core/judges.clj`

Four judges evaluate LLM outputs:

| Judge | Weight | Evaluates |
|-------|--------|-----------|
| `:grounding` | 0.35 | Is response grounded in inputs? No hallucinations? |
| `:instruction-following` | 0.25 | Did LLM follow the instruction? |
| `:reasoning` | 0.20 | Is reasoning clear and logical? |
| `:completeness` | 0.20 | Are all aspects of the task addressed? |

Each tier-1 judge returns (ADR 0011 — adversarial, reason-before-score, discrete 1–5 band):
```clojure
{:score 0.75          ;; [0,1], derived deterministically from :level (NOT self-reported)
 :level 4             ;; the discrete 1–5 band the judge chose
 :reasoning "..."     ;; the adversarial analysis, written BEFORE the band
 :feedback "..."      ;; Actionable improvement suggestions
 ;; + dimension-specific evidence lists, e.g. :grounded-claims / :ungrounded-claims}
```

---

## Creating GEPA-Compatible Workflows

### What makes a node optimizable

GEPA optimizes the `:instruction` you already wrote — **in place**. Before each run it walks the target sheet, collects every `:llm` node's `:instruction` keyed by node `:name` (the seed candidate), mutates those strings, and patches a candidate's instruction back onto the matching node *by name* at execution time. Your saved sheet is never modified.

> Source: `extract-workflow-instructions` (`gepa/core/todo_processors.clj`) builds `{node-name -> instruction}` from `:llm` / `:ai` / `:llm-condition` nodes; `apply-instruction-override` (`orc-service .../core/todo_processors.clj`) re-applies a candidate's string when its key equals the node's `:name`.

The only requirements:

1. **The node has a stable `:name`.** That name is the optimization key, and it's how the winning instruction comes back to you (`:best-candidate :instructions "<node-name>"`). Without a name GEPA can't address the node.
2. **The node has an `:instruction`.** That string is the seed GEPA starts from.

That's it — no blackboard plumbing, no `:reads` changes.

```clojure
;; This node is already GEPA-optimizable. GEPA will vary the :instruction
;; string, keyed by the name "answer".
(sheet/llm "answer"
  :model "google/gemini-2.0-flash-001"
  :instruction "Answer the question."      ;; <- the seed GEPA improves
  :reads  [:question]
  :writes [:answer])
```

> **You do not put the instruction in the blackboard.** GEPA's candidate strings flow through the instruction-override path (by node name), not through example inputs — so adding `:instruction` to the blackboard or `:reads` does nothing for GEPA and just optimizes wrapper text instead of the real instruction.

### Complete Example Workflow

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])

(def qa-workflow
  (sheet/workflow "gepa-qa"
    (sheet/blackboard
      {:question [:string {:description "The user's question to answer"}]
       :answer   [:string {:description "The answer to the question"}]})

    (sheet/llm "answer"                      ;; <- node name = optimization key
      :model "google/gemini-2.0-flash-001"
      :instruction "Answer the question."    ;; <- seed instruction GEPA improves
      :reads  [:question]
      :writes [:answer])))

;; Build the workflow
(def sheet-id (sheet/build-workflow! context qa-workflow))
```

---

## Running GEPA Optimization

### Prerequisites

1. **API Keys (in environment):**
   ```bash
   export OPENROUTER_API_KEY="your-key"
   # Or ANTHROPIC_API_KEY, OPENAI_API_KEY depending on LLM choice
   ```

2. **Running REPL with system started:**
   ```clojure
   ;; In development/src/repl_stuff.clj
   (def service (start))
   (def context (:context service))
   ```

### Step-by-Step REPL Workflow

```clojure
;; 1. Require namespaces
(require '[ai.obney.orc.orc-service.interface :as sheet])
(require '[ai.obney.orc.gepa.interface :as gepa])

;; 2. Create a workflow whose :llm node has a stable name + seed instruction
(def qa-workflow
  (sheet/workflow "gepa-qa"
    (sheet/blackboard
      {:question :string
       :answer   :string})
    (sheet/llm "answer"                     ;; name "answer" is the optimization key
      :model "google/gemini-2.0-flash-001"
      :instruction "Answer the question."   ;; seed instruction GEPA improves
      :reads  [:question]
      :writes [:answer])))

(def sheet-id (sheet/build-workflow! context qa-workflow))

;; 3. Define examples — flat maps with string keys matching the node's :reads.
;;    With a structural metric, include the expected value under "answer".
(def examples
  [{"question" "What is 2 + 2?"                        "answer" "4"}
   {"question" "What is the capital of France?"        "answer" "Paris"}
   {"question" "Who wrote Romeo and Juliet?"           "answer" "William Shakespeare"}
   {"question" "What year did World War II end?"        "answer" "1945"}
   {"question" "What is the chemical symbol for water?" "answer" "H2O"}])

;; 4. Run GEPA optimization (blocking, REPL-friendly)
(def result
  (gepa/optimize! context
    {:sheet-id  sheet-id
     :trainset  (subvec examples 0 3)       ;; sampled for reflective feedback
     :valset    examples                    ;; scored to drive selection
     :metric-fn (gepa/make-judge-metric {:grounding 0.5 :completeness 0.5})
     :config    {:max-metric-calls 30}
     :block?    true}))

;; 5. Review results
(println "Status:" (:status result))
(println "Best score:" (:best-score result))
(println "Best instruction:"
         (get-in result [:best-candidate :instructions "answer"]))
```

For factual Q&A with exact expected answers, swap the metric for a structural one:

```clojure
:metric-fn (gepa/make-exact-match-metric "answer")
```

### Configuration Options

#### Choosing Judges

`make-judge-metric` (and the `:judges` shortcut) take a **weight map**. Pick the dimensions that matter and weight them; weights are normalized internally.

```clojure
;; Factual Q&A - focus on accuracy
(gepa/make-judge-metric {:grounding 0.6 :completeness 0.4})

;; Complex reasoning tasks
(gepa/make-judge-metric {:reasoning 0.5 :instruction-following 0.5})

;; All four dimensions
(gepa/make-judge-metric {:grounding 0.35 :instruction-following 0.25
                         :reasoning 0.20 :completeness 0.20})
```

#### LLM Configuration

GEPA involves two LLM roles:

| Role | Where it's set | Purpose |
|------|----------------|---------|
| **Task model** | the `:model` on each `:llm` node | Executes your workflow (produces the outputs judged each run) |
| **Reflection model** | `:reflection-lm` in `:config` | Reads failures + judge feedback and proposes improved instructions |

The task model is whatever you already put on your `:llm` node — GEPA runs your tree as-is. The reflection model is configured per optimization run:

```clojure
(gepa/optimize! context
  {:sheet-id  sheet-id
   :trainset  trainset :valset valset
   :metric-fn (gepa/make-judge-metric {:grounding 0.5 :completeness 0.5})
   :config    {:max-metric-calls 30
               :reflection-lm "openrouter/anthropic/claude-sonnet-4"}}) ;; smart proposer
```

**Reflection model options:**
```clojure
;; Via OpenRouter (single API key for all providers)
:reflection-lm "openrouter/anthropic/claude-sonnet-4"
:reflection-lm "openrouter/openai/gpt-4o"

;; Direct provider access
:reflection-lm "anthropic/claude-sonnet-4"
:reflection-lm "openai/gpt-4o"
```

To make task execution cheaper/faster, lower the model on the `:llm` node itself (e.g. `:model "google/gemini-2.0-flash-001"`).

**API Key Requirements:**

The LLM provider is determined by the model string prefix:
- `google/*` → Requires `GOOGLE_API_KEY`
- `openai/*` → Requires `OPENAI_API_KEY`
- `anthropic/*` → Requires `ANTHROPIC_API_KEY`
- `openrouter/*` → Requires `OPENROUTER_API_KEY` (can access all providers)

#### Budget Control

`:max-metric-calls` (in `:config`) caps the total number of candidate evaluations:

```clojure
:config {:max-metric-calls 20}    ;; quick test (fewer iterations)
:config {:max-metric-calls 100}   ;; thorough optimization
```

Other `:config` keys: `:reflection-minibatch-size` (failures sampled per mutation, default 3), `:use-merge` (crossover, default true), `:val-overlap-floor`, `:skip-perfect-score`, `:frontier-type`.

---

## Evaluation Judges

### How Judges Work

Each judge is an LLM-as-judge that evaluates trace data:

```clojure
;; Trace data structure passed to judges
{:inputs {:question "What is 2+2?"}    ;; What was sent to LLM
 :outputs {:answer "4"}                 ;; What LLM returned
 :instruction "Answer concisely."}       ;; The instruction being evaluated
```

### Judge Rubrics (tier-1: decoupled criteria × stance × discrete Scale)

Rubrics are defined in `evaluation/core/rubrics.clj` and resolved via `get-tier1-rubric`. Each tier-1 rubric keeps three concerns **decoupled** (ADR 0011):
- **criteria** — *what* to evaluate;
- **stance** — *how to behave* (an adversarial reviewer persona);
- **scale** — *how to score* (a first-class discrete **1–5 `Scale`** with explicit per-level bands, mapped deterministically to `[0,1]`; 1→0.0 … 5→1.0).

The judge **reasons before it scores** (field order forces `:reasoning` + evidence lists before the `:level` band), and output is carried by the **typed blackboard** — there is **no soft "1.0 Excellent / 0.8 Good" anchor and no JSON-in-the-prompt**. The per-level band descriptions are the scoring anchors.

**Example: Grounding bands (`GROUNDING_SCALE`, keep-strict)**
```
5: Fully grounded — every substantive claim directly supported; no inference-as-fact.
4: Well grounded — minor imprecision only; ANY inference-as-fact (even hedged) caps at 3.
3: Mixed — one or more unsupported claims or inferences presented as fact (incl. hedged).
2: Largely ungrounded — multiple unsupported specifics, or one central fact fabricated.
1: Ungrounded / fabricated — contradicts the source or nearly all claims are inventions.
```

> The old single-string soft-0–1 `*_RUBRIC` defs survive in `rubrics.clj` for legacy retrospective paths only. The live judges use the tier-1 rubrics above. Full detail: [`EVALUATION-COMPONENT.md`](EVALUATION-COMPONENT.md#tier-1-judge-model-2026-06-decoupled-discrete-scale--reason-before-score--all-four-llm-judges).

### Using Judges Directly

```clojure
(require '[ai.obney.orc.evaluation.interface :as eval])

;; Evaluate a single trace
(def trace-data
  {:inputs {:question "What is 2+2?"}
   :outputs {:answer "4"}
   :instruction "Answer math questions"})

;; Single judge
(eval/evaluate-single :grounding trace-data)
;; => {:score 0.95 :feedback "All claims grounded..." :grounded-claims [...]}

;; All judges with aggregation
(eval/evaluate-trace trace-data)
;; => {:score 0.87 :feedback "..." :dimensions [...]}

;; Custom judge selection
(eval/evaluate-trace trace-data {:judges [:grounding :reasoning]})
```

### Judge Weights

When aggregating scores, judges have different weights:

| Judge | Weight | Rationale |
|-------|--------|-----------|
| Grounding | 0.35 | Most critical - no hallucinations |
| Instruction Following | 0.25 | Task compliance |
| Reasoning | 0.20 | Quality of thought |
| Completeness | 0.20 | Thoroughness |

---

## Event Store & Data Collection

The event store captures rich data at both tree and node levels, which can be used for building trainsets and analyzing optimization results.

### Tree-Level Data

#### Execution Traces

Every workflow execution creates a `:sheet/execution-traced` event:

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])

;; Get all traces for a sheet
(def traces (sheet/get-traces-for-sheet event-store sheet-id))

;; Each trace contains:
{:trace-id #uuid "..."
 :sheet-id #uuid "..."
 :version-number 1
 :started-at #inst "..."
 :completed-at #inst "..."
 :duration-ms 1234
 :status :success
 :input-snapshot {:question "What is 2+2?" :instruction "..."}
 :output-snapshot {:answer "4"}
 :node-traces [{:node-id #uuid "..." :duration-ms 100 :status :success}]
 :error nil}
```

#### Tree Metadata

```clojure
;; Get metadata about a workflow
(sheet/get-tree-metadata event-store sheet-id)
;; => {:problem-types [:qa :factual] :avg-score 0.85 ...}

;; Find workflows by problem type
(sheet/find-trees-by-problem-type event-store :qa {:min-score 0.7 :limit 10})
```

### Node-Level Data

#### Rolling Metrics

Track per-node performance over recent executions:

```clojure
;; Get metrics for a specific node
(sheet/get-node-rolling-metrics event-store sheet-id node-id)
;; => {:execution-count 50
;;     :success-rate 0.92
;;     :failure-rate 0.08
;;     :avg-duration-ms 1500
;;     :recent-trend :improving}  ;; or :declining, :stable

;; Get metrics for all nodes in a tree
(sheet/get-tree-rolling-metrics event-store sheet-id)
;; => {:sheet-id #uuid "..."
;;     :nodes [{:node-id #uuid "..." :success-rate 0.95 ...}]
;;     :total-executions 100}
```

### Building Trainsets from Event Store

You can extract training data from past executions:

```clojure
;; Get recent traces
(def traces (sheet/get-traces-for-sheet event-store sheet-id))

;; Convert to GEPA example format: flat maps with string keys.
;; Each example merges the trace's inputs with its (successful) outputs,
;; so a structural metric can compare against the recorded answer.
(def examples
  (->> traces
       (filter #(= :success (:status %)))
       (map (fn [trace]
              (merge (:input-snapshot trace)
                     (:output-snapshot trace))))
       (take 20)
       vec))
```

### Extracting Low-Scoring Examples

For targeted improvement, find examples that scored poorly:

```clojure
;; Run evaluation on traces to identify weak spots
(require '[ai.obney.orc.evaluation.interface :as eval])

(def evaluated-traces
  (for [trace traces]
    (let [trace-data {:inputs (:input-snapshot trace)
                      :outputs (:output-snapshot trace)
                      :instruction (get (:input-snapshot trace) :instruction)}
          result (eval/evaluate-trace trace-data)]
      (assoc trace :eval-score (:score result)
                   :eval-feedback (:feedback result)))))

;; Find low-scoring examples
(def low-scoring
  (->> evaluated-traces
       (filter #(< (:eval-score %) 0.7))
       (sort-by :eval-score)))

;; These are prime candidates for GEPA's reflective improvement
```

---

## Verification Test

To confirm the loop runs end-to-end in your REPL, build a tiny workflow and run a short optimization. This is the same shape as the [Step-by-Step REPL Workflow](#step-by-step-repl-workflow), kept minimal for a smoke test:

```clojure
(require '[ai.obney.orc.orc-service.interface :as sheet])
(require '[ai.obney.orc.gepa.interface :as gepa])

;; 1. Minimal workflow with one named :llm node + seed instruction
(def sheet-id
  (sheet/build-workflow! context
    (sheet/workflow "gepa-smoke"
      (sheet/blackboard {:question :string :answer :string})
      (sheet/llm "answer"
        :model "google/gemini-2.0-flash-001"
        :instruction "Answer the question."
        :reads [:question] :writes [:answer]))))

;; 2. A few examples
(def examples
  [{"question" "What is 2 + 2?"                 "answer" "4"}
   {"question" "What is the capital of France?" "answer" "Paris"}
   {"question" "Who wrote Romeo and Juliet?"     "answer" "William Shakespeare"}])

;; 3. Run a short optimization (blocking)
(def result
  (gepa/optimize! context
    {:sheet-id  sheet-id
     :trainset  examples
     :valset    examples
     :metric-fn (gepa/make-exact-match-metric "answer")
     :config    {:max-metric-calls 12}
     :block?    true}))

;; 4. Inspect
result
;; => {:status :completed
;;     :best-score 0.85
;;     :best-candidate {:instructions {"answer" "When provided with a question..."} ...}
;;     ...}
```

A healthy run returns `:status :completed`, a numeric `:best-score`, and a `:best-candidate` whose `:instructions` map carries an improved string under your node name (`"answer"`). You can also watch progress mid-run from another REPL form with `(gepa/get-progress context (:optimization-id result))`.

---

## Testing GEPA Workflows

Unit tests for GEPA integration are available in:

**File:** `components/orc-service/test/ai/obney/workshop/orc_service/gepa_integration_test.clj`

### Test Context Setup

Use `with-async-test-context` for tests that need full event flow:

```clojure
(ns my-app.gepa-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.evaluation.interface :as eval]
            [ai.obney.orc.evaluation.core.judges :as judges]))

(deftest my-gepa-test
  (h/with-async-test-context [ctx]
    ;; Test code with full event store support
    ))
```

### Mock Executors

Create deterministic executors for testing without real LLM calls:

```clojure
(defn mock-qa-executor
  "Echoes instruction and question in the answer."
  [{:keys [inputs]}]
  (let [question (get inputs :question)
        instruction (get inputs :instruction "default")]
    {:answer (str "Instruction: " instruction " | Question: " question)}))
```

### Mock Judges

Bind `*use-mock-llm*` to avoid real LLM calls during evaluation:

```clojure
(binding [judges/*use-mock-llm* true]
  (let [trace-data {:inputs {:question "What is 2+2?"}
                    :outputs {:answer "4"}
                    :instruction "Answer accurately."}
        result (eval/evaluate-trace trace-data)]
    (is (number? (:score result)))
    (is (<= 0.0 (:score result) 1.0))))
```

### GEPA-Compatible Workflow Test Pattern

```clojure
(deftest gepa-workflow-test
  (testing "workflow with instruction in blackboard is GEPA-compatible"
    (h/with-async-test-context [ctx]
      (let [;; Create sheet
            sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "GEPA Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)

            ;; Create node
            node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf))
            node-id (-> node-result :command-result/events first :node-id)

            ;; Declare GEPA-compatible blackboard
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :question :string))
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :instruction :string))
            _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :answer :string))

            ;; Configure node with instruction in reads
            _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id node-id
                                      [:question :instruction]  ;; instruction in reads!
                                      [:answer]))
            _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id node-id :code
                                      :fn "my-app.test/mock-qa-executor"))]

        ;; Verify instruction is in reads
        (let [nodes (sheet/get-nodes-for-sheet (:event-store ctx) sheet-id)
              node (first (filter #(= :leaf (:type %)) nodes))]
          (is (some #{:instruction} (:reads node))))

        ;; Execute with dynamic instruction
        (let [result (sheet/execute ctx sheet-id
                       {:question "test?" :instruction "Be concise."})]
          (is (= :success (:status result)))
          (is (clojure.string/includes?
                (get (:outputs result) :answer)
                "Be concise.")))))))
```

### Running Tests

```bash
clojure -M:dev:test -e "(do
  (require '[clojure.test :refer [run-tests]]
           '[ai.obney.orc.orc-service.gepa-integration-test])
  (run-tests 'ai.obney.orc.orc-service.gepa-integration-test))"
```

Expected output:
```
Testing ai.obney.orc.orc-service.gepa-integration-test

Ran 13 tests containing 62 assertions.
0 failures, 0 errors.
```

### Test Coverage

The integration tests verify:

| Test | Validates |
|------|-----------|
| `gepa-workflow-structure-test` | Instruction in blackboard + reads |
| `gepa-workflow-executes-with-instruction-test` | Execution with dynamic instruction |
| `instruction-override-test` | Different instructions produce different outputs |
| `execution-events-test` | Correct events emitted during execution |
| `tick-events-emitted-test` | Tree tick lifecycle events |
| `judge-aggregation-test` | Weighted score aggregation |
| `judge-subset-test` | Subset judge selection |
| `read-model-queries-test` | Read model data accuracy |
| `manual-evaluation-pattern-test` | Manual loop with mock judges |
| `execution-duration-test` | Duration tracking |
| `execution-isolation-test` | Parallel execution isolation |
| `trace-storage-for-training-test` | Trace queryability for trainsets |
| `rolling-metrics-test` | Node performance metrics |

---

## Troubleshooting

### Common Issues

#### 1. "Instruction not being used"
Check that:
- `:instruction` is in the blackboard schema
- `:instruction` is in the `:reads` vector of the LLM node
- The node's static instruction tells the LLM to follow the dynamic instruction

#### 2. "All scores are 0"
Check:
- Workflow executes successfully without GEPA first
- API keys are set correctly
- Judge LLM can be reached

#### 3. "No improvement after optimization"
This can happen if:
- The seed instruction is already near-optimal
- The trainset is too small (try 5-10 examples minimum)
- The task is inherently variable (some randomness expected)

### Debug Mode

To see exactly what a judge metric scores (and the feedback it would feed the proposer), call the metric function directly. `make-judge-metric` returns `(fn [input output] -> {:score :feedback})`:

```clojure
(def judge-metric (gepa/make-judge-metric {:grounding 0.5 :instruction-following 0.5}))

(let [result (judge-metric {"question" "What is 2+2?"}      ;; input
                           {"answer" "4"})]                  ;; output
  (println "Score:" (:score result))
  (println "Feedback:" (:feedback result)))   ;; weakest-first judge diagnosis
```

Mid-run, inspect the live optimization state:

```clojure
(gepa/get-progress context optimization-id)         ;; status + budget used + frontier size
(gepa/get-pareto-frontier context optimization-id)  ;; frontier members
```

---

## Implementation Status

GEPA is a **fully shipped native Clojure implementation** — not a future roadmap item. All optimization state is stored as events, the Pareto frontier is a read model, and the instruction proposer runs as an ORC workflow (`proposer.clj`). Every optimization step is observable via the event stream.

> The complete optimization loop — Pareto selection, reflective mutation, common-ancestor merge, and cross-run inheritance — runs natively in the JVM as Clojure. There is no external runtime dependency in any release path.
