# Self-Improving Workflows in ORC

> **For developers who already have a behavior tree** — ideally one with a
> `:repl-researcher` node — and want it to get better the more it runs,
> without writing any learning code yourself.

> **⚠ Alpha — read this before adopting in production.**
> The self-improving loop ships as alpha-stage capability. It works
> well for in-distribution workflows that align with the shipped seed
> corpus, and it's worth using today for those. Workflows that fall
> far outside the corpus's covered domains will see honest-but-thin
> classifications and rare behavioral mints. See
> [Honest status today](#honest-status-today--solid-vs-rough)
> in this doc for the evidence summary.

---

## Start here: the same tree, now learning

You already have a behavior tree. Maybe it reviews contracts, triages
tickets, or extracts structured data. Right now it runs the same way
every single time — the model sees the same instruction, designs (or
follows) the same shape, and yesterday's thousand executions taught it
nothing.

**The self-improving loop changes that: turn on two flags, and the tree
learns from every execution — picking proven patterns when it designs
sub-trees, recording what worked, and getting better at recurring tasks
without you writing any learning code.**

Here's the journey this guide walks you through, starting from the tree
you already have:

1. **[The smallest opt-in](#the-smallest-opt-in)** — two flags on a
   `:repl-researcher` node you already have.
2. **[What fires when you flip them](#what-each-flag-does--and-the-exact-opt-in-boundary)** —
   the exact opt-in boundary, so there are no surprises.
3. **[Honest alpha-state framing](#honest-status-today--solid-vs-rough)** —
   what's solid, what's rough, kept near the top on purpose.
4. **[How it composes](#how-it-composes-delegated-child-sheets-join-the-loop)** —
   a delegated child sheet that uses `:repl-researcher` joins the loop too.
5. **[How it relates to GEPA](#two-orthogonal-improvement-actuators)** —
   a different actuator aimed at a different part of your tree.
6. **[Operations and debugging](#operations-and-debugging)** — how to
   watch, verify, and troubleshoot the loop in a running system.

The mechanism is event-sourced and runs in the background. You flip the
flags; you read the outputs. The rest of this section explains what those
outputs are.

---

## What ORC's self-improving loop gives you

When you build a behavior-tree workflow in ORC, you can opt into a loop
that:

1. **Picks proven patterns automatically.** When a task arrives, ORC
   classifies it against a corpus of known patterns and prepends the
   best-fitting examples to the model's prompt — including the actual
   tree-DSL snippets that have worked, not just summaries.

2. **Improves those patterns from real execution.** After tasks run,
   the system reads the execution events, extracts what worked and what
   didn't, and updates the pattern descriptions with new strengths,
   weaknesses, and recommended-patterns. The next task that hits the
   same pattern sees a richer prompt with the new content.

3. **Adds genuinely-new patterns to the corpus when needed.** If the
   model encounters work no existing pattern fits, it can contribute a
   new pattern that persists for future retrieval. New domains can grow
   the corpus organically.

4. **Recovers from mid-tree failures gracefully.** When a leaf in an
   emitted tree throws, the model sees the specific failure detail and
   typically emits a focused single-node tree to extract surviving data
   — rather than rebuilding the whole tree from scratch.

You get all four by setting a couple of flags on your workflow. The
loop runs in the background; you read the outputs.

---

## Two orthogonal improvement actuators

ORC has **two** independent ways to make a workflow better over time.
They aim at different parts of your tree, use different mechanisms, and
neither requires the other:

| | **`:auto-classify?`** (this guide) | **GEPA** ([GEPA-GUIDE.md](GEPA-GUIDE.md)) |
|---|---|---|
| **What it tunes** | The *tree the model designs* at runtime | The *instruction string* inside a node |
| **Where it applies** | `:repl-researcher` nodes (RLM tree design) | Static `orc/llm` nodes with a fixed shape |
| **Mechanism** | Prepends matched corpus patterns before the researcher designs a tree; the corpus body evolves from observed runs | Pareto selection + reflective mutation over a held-out trainset, offline |
| **When it improves** | Continuously, from production traffic | In a dedicated optimization run you trigger |

If your workflow is an RLM researcher that designs its own structure,
`:auto-classify?` is your actuator — and the rest of this guide is for
you. If your workflow is a fixed pipeline of `:llm` nodes whose *prompts*
you want tightened, reach for GEPA instead. Many real workflows use both:
GEPA-tuned `:llm` leaves inside a tree that an `:auto-classify?` researcher
designed. They plug into separate parts of the system and compose cleanly.

---

## The smallest opt-in

Add `:auto-classify? true` and `:recursive? true` to your repl-researcher
node:

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc])

(def my-workflow
  (orc/workflow "compliance-review"
    (orc/blackboard
      {:document [:string {:description "The contract or policy to review"}]
       :findings [:vector :any {:description "Issues with severity + citation"}]
       :recommendations [:string {:description "Actionable next steps"}]})
    (orc/repl-researcher "researcher"
      :model "google/gemini-3-flash-preview"
      :instruction "Review the provided document and produce structured findings."
      :reads [:document]
      :writes [:findings :recommendations]
      :rlm {:auto-classify? true
            :recursive? true})))

(let [sheet-id (orc/build-workflow! ctx my-workflow)]
  (orc/execute ctx sheet-id {:document "..."}))
```

That's it. The node already existed in your tree; you added a four-key
`:rlm` map. Everything below happens because of those two keys.

---

## What each flag does — and the exact opt-in boundary

- **`:auto-classify? true`** — before the model starts designing, ORC
  classifies your task against the seed corpus and prepends the top-fitting
  pattern's full body (capabilities + worked-example DSL snippets +
  observed strengths and weaknesses) to the model's instruction.

- **`:recursive? true`** — the model can iterate: design a tree, run it,
  inspect the results via drill-down primitives, refine, and call
  `(final! ...)` when ready. If a leaf in its emitted tree fails, the
  model can emit a smaller tree to recover rather than abandoning the
  partial progress.

**The exact opt-in boundary — what fires the moment you flip the flags,
and what does *not*:**

- `:auto-classify? true` on a `:repl-researcher` is what turns on the
  **read side** of the loop: classification (`classify-task` +
  `classify-behaviors`), the LLM reranker, and the corpus prepend before
  Phase 1. Without it, the researcher runs exactly as before — no
  classification, no prepend.
- `:recursive? true` is what turns on **iteration and mid-tree
  failure recovery** (Phase 1 ↔ Phase 2 looping). Without it the
  researcher emits one tree and returns the Phase-2 result directly.
- The **write side** — judges firing, strength/weakness counts
  incrementing, and the consolidator rewriting a pattern body — is gated
  *separately* by a system-level boolean opt-in,
  `get-living-description-enabled?` (set via the
  `:ontology/set-living-description-enabled` command; default `false`).
  Reading (`:auto-classify?`) and writing (Living Descriptions) are
  independent: you can prepend from the corpus without evolving it, or
  evolve it without a researcher reading from it. The full loop needs
  both turned on.
- Nothing fires for nodes **other than** `:repl-researcher`. A static
  `:llm` node in the same tree is untouched by `:auto-classify?` — that's
  GEPA's territory (see
  [Two orthogonal improvement actuators](#two-orthogonal-improvement-actuators)).
- `mint-behavior!` is **model-initiated**, not flag-initiated. Enabling
  the flags makes minting *possible*; the model only mints when no
  existing pattern credibly fits (see
  [New patterns get minted](#2-new-patterns-get-minted-when-the-model-encounters-genuinely-new-work)).

---

## How it composes: delegated child sheets join the loop

The loop is not limited to a top-level researcher. Because
`:auto-classify?` fires per `:repl-researcher` node — wherever that node
lives — a workflow that **delegates** to a child sheet containing a
`:repl-researcher` gets the same behavior inside the child.

`orc/delegate` runs another sheet with an isolated blackboard, mapping a
slice of the parent's keys in and the child's outputs back out:

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc])

;; A reusable child sheet: a self-improving researcher for one sub-task.
(def risk-subworkflow
  (orc/workflow "risk-assessment"
    (orc/blackboard
      {:clause     [:string {:description "A single contract clause"}]
       :risk-notes [:vector :any {:description "Risks with severity + rationale"}]})
    (orc/repl-researcher "risk-researcher"
      :model "google/gemini-3-flash-preview"
      :instruction "Assess the legal risk of the provided clause."
      :reads [:clause]
      :writes [:risk-notes]
      :rlm {:auto-classify? true     ; ← the child researcher joins the loop
            :recursive? true})))

(def parent-workflow
  (orc/workflow "contract-review"
    (orc/blackboard
      {:clause     [:string]
       :risk-notes [:vector :any]
       :summary    [:string]})
    (orc/sequence "review"
      ;; The delegate node runs risk-subworkflow as a sub-behavior.
      (orc/delegate "assess-risk"
        :target-sheet-id risk-sheet-id   ; built from risk-subworkflow
        :reads  [:clause]
        :writes [:risk-notes]
        :inherit-ontology? true)         ; default; shares corpus context downward
      (orc/llm "write-summary"
        :reads [:risk-notes]
        :writes [:summary]))))
```

When the parent hits the `assess-risk` delegate node, the child sheet
executes; its `risk-researcher` node fires its own classification +
corpus prepend exactly as a top-level researcher would, and its
execution events feed the same Living Description loop. The result:

- **Sub-behaviors improve independently.** A delegated researcher
  classifies to whatever pattern fits *its* sub-task — which may differ
  from the parent's — and evolves that pattern from its own traffic.
- **Reuse multiplies the learning.** If three different parent workflows
  all delegate to the same `risk-assessment` child sheet, every run from
  all three feeds the same pattern body. The child gets better faster
  than any single caller would drive it.
- **`:inherit-ontology? true`** (the `delegate` default) shares the
  parent's corpus context with the child so classification is consistent
  across the boundary.

So "turn on two flags" scales from a single node to a tree of delegated
sub-behaviors: put the flags on whichever `:repl-researcher` nodes you
want to learn, at whatever depth they live. For the composition mechanics
of `:repl-researcher` as a node inside a larger tree, see
[`RLM-GUIDE.md`](RLM-GUIDE.md).

---

## Honest status today — solid vs rough

The self-improving loop is **alpha-stage**. The components that compose
the loop work as documented — but the *aggregate behavior on workflows
far outside the shipped seed corpus is honest-but-thin*, not what a
"self-improving" label would imply at maturity. The framing below comes
from a real 21-task OOD evidence-gathering sweep, not from marketing.

| **Solid** (use without hesitation) | **Rough** (know before you commit) |
|---|---|
| In-distribution classification — tasks resembling shipped seed patterns (legal-issue-detection, contract-comparison, risk-analysis, chunked-extraction) match at confidence 1.00; prepend carries the full worked-example DSL | OOD force-fit — tasks outside the corpus force-fit to the structurally-closest pattern at confidence 0.85–0.95; reranker gives mechanically-plausible reasoning that misses domain specialization |
| Recursive RLM with drill-down — `(tree-detail)`, `(tree-failures)`, `(node-output node-id)` all work; model recovers mid-tree failures via focused single-node resume trees without rebuilding the whole pipeline | `mint-behavior!` firing rate — fired on 1 of 21 deliberately-OOD tasks; today's classifier does not surface "no good semantic match" as a strong signal; model treats top-N matches as coverage |
| Consolidator-driven body evolution — repeated traffic on a pattern increments the body version with new strengths grounded in observed execution; history is append-only | Hierarchical seed gaps — 12 abstract behavioral seeds describe shape but not domain (no "Analysis-of-legal-documents" or "Validation-of-schedule-constraints" specializations yet) |
| `mint-behavior!` mechanics — the defcommand path, persistence, ColBERT re-index, and same-iteration lookup all work as documented | |

### Active investigation

The R04 OOD verification work in
`development/bench/ood-stress-results/` is an evidence-gathering arc
that surfaced the above limits. Two paths forward are being considered:

- **Hierarchical/specialized seeds:** author N specializations per
  abstract behavior (Analysis-of-legal-documents,
  Analysis-of-source-code, etc.) so the classifier can discriminate
  on domain.
- **Judge-grounded rerank discrimination:** add a meta-judge that
  scores classification confidence against the matched seed's actual
  domain coverage (high shape-fit + low domain-fit → DOWN-weight).

The HANDOFF document at
`development/bench/ood-stress-results/HANDOFF.md` is the entry point
for a future agent picking up this investigation.

### What this means for your workflow

- If your workflow runs tasks that align with the shipped seed corpus,
  expect the loop to feel useful from day one.
- If your workflow runs tasks far outside the shipped corpus, expect
  to author your own corpus seeds via `:ontology/record-tree-description`
  (and possibly `:ontology/mint-behavioral-subtree` if your work needs
  new behavioral categories). The mint affordance is real and works;
  it just rarely fires from the agent side today without curator
  involvement.
- The loop is improving over time. New seeds, judge-grounded
  classification refinements, and consolidator improvements are
  expected to land additively without breaking the consumer API.

If you hit a specific OOD failure mode that matters to your workflow,
file it with a concrete reproduction case — your evidence is the
highest-leverage input to the corpus and classifier roadmap.

---

## What the model sees with `:auto-classify?` on

When the task arrives, the model's instruction is prepended with a block
like this (real example from a contract review task):

```
## Suggested patterns from corpus

These are concrete EXAMPLES retrieved from the seed corpus based on
classification of your task. Each example includes:
  - WHY the candidate fits (reranker reasoning)
  - The pattern's prose summary (seed `:summary`)
  - Capabilities it provides
  - Proven STRENGTHS — traits observed to work, each with a worked-example
    DSL snippet you can adapt
  - Observed WEAKNESSES — failure modes others hit, with the recommended fix
  - Representative uses where this pattern has shipped

Mimic what works, modify what's risky for your task, OR design from
scratch. They are not mandates — your job is to design the RIGHT tree
for THIS task, using the corpus as evidence not gospel.

### Structural patterns (top 1 from corpus retrieval)
#### Top match — Legal-issue-detection (confidence: 1.00)
Why this fits: This candidate perfectly matches the task's requirement
for legal review. It specifies an :output-schema with a :citation field,
which directly supports the quality requirement that all issues must be
traceable to specific clause text...

Pattern guidance (seed `:summary`):
Legal-issue-detection trees flag legal concerns in a document with
per-issue severity, area-of-law, source citation, and recommendation.
The proven shape mirrors risk-analysis but with citation as a first-class
output: per-section :map-each with :output-schemas requiring :citation
field, then :code (NOT :llm) for the severity-summary rollup.

Capabilities:
  - per-section issue extraction with structured outputs
  - severity classification across the whole document
  - citation linkage from finding back to source text

Strengths (proven traits — these patterns have been observed to work):
  - **Trait:** per-section :map-each with structured :output-schemas
    reliably surfaces issues with consistent severity + citation shape
    (confidence 1.00, evidence-count 7)
    - Good when: document has natural sections AND each section can be
      independently scanned for issues
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:sequence
        [:chunk-document {:from :document :delimiter "\n\n" :into :sections}]
        [:map-each {:from :sections :as :section :into :results
                     :max-concurrency 3}
          [:llm {:reads [:section]
                 :writes [:section-issues]
                 :output-schemas
                 {:section-issues
                  [:vector [:map [:concern :string]
                                 [:severity [:enum :high :medium :low]]
                                 [:citation :string]
                                 [:recommendation :string]]]}}]]
        [:aggregate {:from :results :writes [:issues]}]
        [:code {:reads [:issues]
                :writes [:severity-summary]
                :fn (fn [{:keys [issues]}]
                      {:severity-summary
                       (frequencies (map :severity issues))})}]
        [:final {:keys [:issues :severity-summary]}]]
      ```
```

The model reads this BEFORE designing. It can adopt the worked example
verbatim, adapt it for the task's quirks, or design from scratch — your
output schema, your task instruction, and the prepended context together
shape what gets emitted.

You don't have to author any of this content. The corpus ships with
hand-authored seeds covering common task families (document analysis,
risk identification, contract comparison, validation loops, etc.), and
the system extends the corpus from observation over time.

---

## What happens after the task runs

Two things, both in the background:

### 1. The corpus body updates

If your task hits the same pattern that other tasks have hit, the
system periodically reads the recent execution events and updates the
pattern's body. Updates come from observed evidence — not new
hand-authoring.

A pattern body that started simple:

```clojure
{:version 1
 :capabilities ["per-section issue extraction with structured outputs"]
 :strengths
 [{:trait "per-section :map-each with structured :output-schemas"
   :confidence 1.0
   :evidence-count 1
   :recommended-pattern "[:sequence [...]]"}]
 :weaknesses
 [{:trait "issues without citation fields become recommendations the user can't verify"
   :confidence 1.0
   :recommended-alternative "explicitly require :citation in the schema"}]
 :representative-uses
 ["compliance review of a draft regulation"
  "legal due diligence of a contract"]}
```

Becomes (after the corpus has observed real runs):

```clojure
{:version 3
 :consolidated-from-event-count 4
 :capabilities ["per-section issue extraction with structured outputs"
                 "severity classification across the whole document"
                 "adversarial framing for actionable critiques"]
 :strengths
 [{:trait "per-section :map-each with structured :output-schemas"
   :confidence 1.0
   :evidence-count 7   ; ← climbed from 1 to 7 as runs reinforced the pattern
   :recommended-pattern "[:sequence [...]]"}
  {:trait "Explicit :citation field in schemas enforces auditable traceability back to source text"
   :confidence 0.95   ; ← NEW trait, surfaced from observed runs
   :evidence-count 6
   :recommended-pattern "[:llm {:output-schemas {:findings [:vector [:map [:concern :string] [:citation :string]]]}}]"}
  {:trait "Adversarial review framing generates more actionable fixes than neutral summaries"
   :confidence 0.8    ; ← NEW trait, observed from task framing
   :evidence-count 4
   :recommended-pattern "..."}]
 :weaknesses [...]
 :representative-uses
 ["compliance review of a draft regulation"
  "legal due diligence of a contract"
  "adversarial employee-side review of employment agreements"   ; ← NEW use
  "audit of a corporate document for liability exposure"        ; ← NEW use
  "identifying ambiguities and missing mandatory clauses"]}     ; ← NEW use
```

The next task that classifies to this pattern sees the updated body in
its prepend. Workflows downstream of this corpus naturally benefit from
upstream task experience.

You can read the current body at any time:

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

(ontology/get-description ctx :tree-class some-pattern-id)
;; => {:summary "..." :strengths [...] :weaknesses [...] :version 3 ...}
```

> For the consolidation architecture, description granularities
> (tree-class, node-instance, node-type), and the four anti-recency
> safeguards that prevent single-bad-burst overcorrection, see
> [`LIVING-DESCRIPTIONS.md`](LIVING-DESCRIPTIONS.md).

### 2. New patterns get minted when the model encounters genuinely-new work

If a task arrives that doesn't fit any existing pattern at meaningful
confidence, the model can contribute a new pattern via the sandbox
primitive `(mint-behavior! ...)`. The minted behavior persists in the
corpus and is retrievable for future tasks that share its behavioral
shape — even when the future tasks are in different domains.

An example: a task in domain X (say, game balance playtesting) gets
solved with an iterative parameter-adjustment pattern. The model
explicitly mints the pattern. Months later, a different consumer's task
in domain Y (say, ML hyperparameter tuning) arrives — completely
different vocabulary, different metrics — but the same behavioral shape
(iterate parameter adjustments driven by measured feedback). The new
task's classify-behaviors call retrieves the minted pattern from
domain X at high confidence with the reranker explaining:

> _"This candidate perfectly matches the task's iterative tuning
> requirements. The pattern of pairing a trusted oracle (the 5-fold CV
> score/latency) with LLM-driven heuristics for parameter adjustment
> directly implements the core logic of the search loop described in
> the prompt."_

The model sees the minted pattern's prose summary plus its
recommended-pattern DSL and adapts it.

You don't decide when to mint. The model does — based on whether any
existing pattern in the corpus offers a credible match for the task. As
the corpus grows, the bar for minting rises (more existing patterns to
fit against), so mint events get rarer as the corpus matures.

---

## Recovering from mid-tree failures

The recursive loop has a useful property: when a leaf in an emitted
tree fails, the model can recover without rebuilding the whole tree.

Suppose the model designed a tree like:

```clojure
[:sequence
  [:llm {:writes [:proposed_schedule]}]
  [:code {:fn validator-fn
          :reads [:proposed_schedule]
          :writes [:schedule :violations :rationale]}]
  [:final {:keys [:schedule :violations :rationale]}]]
```

And the `:code` validator throws at runtime. The next iteration's
`:tree-results` summary shows:

```clojure
{:status :failure
 :nodes-succeeded 1
 :nodes-failed 1
 :failed-leaves [{:node-id #uuid "abc-..."
                  :error "Duplicate key: null"}]
 :nil-writes [:schedule :violations :rationale]
 :tree-raw [:sequence [:llm {...}] [:code {...}] [:final {...}]]
 :outputs-keys [:proposed_schedule]   ; ← survived from the :llm leaf
 :usage {:total-tokens 300}}
```

The model can read this and emit a focused recovery tree that reads the
surviving `:proposed_schedule` and re-runs JUST the validator with
different code:

```clojure
[:code {:fn (fn [{:keys [inputs]}]
              (let [sch (:proposed_schedule inputs)
                    ...   ; corrected validator logic
                    ]
                {:schedule ...
                 :violations ...
                 :rationale ...}))
        :reads [:proposed_schedule]
        :writes [:schedule :violations :rationale]}]
```

That single-node tree runs against the existing sandbox-vars, fills the
missing writes, and the model finalizes. Token cost is small because the
expensive `:llm` proposer didn't re-run.

The pattern works because:
- Successful leaves' writes ARE in sandbox-vars even when the overall
  tree returns `:failure`
- The next iteration's prompt includes `(get-var ...)` access to those
  surviving values
- The `:failed-leaves` field tells the model exactly which leaf to
  replace
- Drill-down primitives like `(node-output node-id)` and
  `(tree-failures)` are bound in the sandbox for explicit inspection
  when the summary isn't enough

For deeper detail on the recursive loop, see [`RLM-GUIDE.md`](RLM-GUIDE.md).

---

## Inspecting what the system is doing

### Where the classifier dropped its trace

Every R-Inject prepend leaves a sidecar file at
`/tmp/r-inject-trace-<sheet-id>.edn` containing:

```clojure
{:rendered-at "2026-06-09T..."
 :prepend "## Suggested patterns from corpus..."   ; the full block prepended
 :prepend-chars 4186
 :original-instruction-chars 800
 :classifier-payload
 {:structural {:assigned-tree-id #uuid "..."
               :confidence 1.00
               :top-candidates [{:document-metadata {:target-id "..."}
                                  :reasoning "..."
                                  :fitness-score 1.00} ...]
               :rerank-fallback? false}
  :behavioral {:behaviors [{:behavior-id #uuid "..."
                            :confidence 0.95
                            :reasoning "..."} ...]
               :rerank-fallback? false}}}
```

Read this to confirm:
- Which pattern was matched
- The reranker's reasoning verbatim
- What the model actually saw in its prompt

### Where the execution outputs are

Every workflow run writes a result EDN under
`development/bench/generalization-results/` (or wherever you've pointed
the runner). Contents include:

```clojure
{:task "<task-slug>"
 :status :success
 :iteration-reasonings ["..." "..."]   ; model's :reasoning per Phase-1 iteration
 :generated-tree-raw [:sequence [...]]  ; the tree the model actually emitted
 :outputs {...}                         ; the final values
 :usage {:total-tokens 28000 ...}
 :r-inject-trace {...}}                 ; classifier-payload + prepend
```

For debugging deeper, the event store has every node-execution-completed
event tagged with `[:sheet sheet-id]` and `[:tick tick-id]`. Use
`(es/read event-store {:tags #{[:tick tick-id]}})` to pull the full
chronological event log.

### How to spot-check the corpus body for a pattern

```clojure
(ontology/get-description ctx :tree-class pattern-target-id)
```

Returns the current body. To see the full history of every version:

```clojure
(ontology/get-description-history ctx :tree-class pattern-target-id)
;; => [{:body {...v1...} :recorded-at "..."}
;;     {:body {...v2...} :recorded-at "..."}
;;     {:body {...v3...} :recorded-at "..."}]
```

Each version-bump corresponds to a consolidation cycle — the system
read recent execution evidence, ran a reflection step, and emitted a
new description. The history is append-only; nothing is overwritten.

---

## Operations and debugging

### The self-improving pipeline — 7 stages

The diagram below shows the end-to-end loop for `:repl-researcher` nodes
with `:auto-classify? true`. This supersedes the pre-RLM version in
[`archived/FEEDBACK-LOOP.md`](archived/FEEDBACK-LOOP.md), which described a manual `:context`-parameter
injection flow rather than the current corpus-driven prepend loop.

```
┌─────────────────────────────────────────────────────────────────────────┐
│         SELF-IMPROVING LOOP PIPELINE (RLM / auto-classify aware)        │
└─────────────────────────────────────────────────────────────────────────┘

     ┌──────────────────────────────────────────────────────────────────┐
     │ OPT-IN GATE: :rlm {:auto-classify? true} on :repl-researcher    │
     │  ↳ activates Stage 2 (classification) and Stage 3 (corpus       │
     │    prepend). Without this flag task goes directly to Stage 4.   │
     └──────┬───────────────────────────────────────────────────────────┘
            │ on each task arrival
            ▼
    ┌───────────────────────────┐
    │ 1. TASK ARRIVAL           │◄──────────────────────────────────────────┐
    │  :repl-researcher fires;  │                                           │
    │  emit-tree! dispatched    │                                           │
    └──────┬────────────────────┘                                           │
           │ task signature (blackboard schema + instruction)               │
           ▼                                                                │
    ┌───────────────────────────┐                                           │
    │ 2. CLASSIFICATION         │                                           │
    │  classify-task            │                                           │
    │  classify-behaviors       │                                           │
    │  LLM reranker             │                                           │
    └──────┬────────────────────┘                                           │
           │ top-N matched patterns + fitness-scores + reasoning            │
           ▼                                                                │
    ┌───────────────────────────┐                                           │
    │ 3. R-INJECT PREPEND       │                                           │
    │  top-fitting corpus body  │                                           │
    │  prepended to instruction │                                           │
    └──────┬────────────────────┘                                           │
           │ enriched instruction (capabilities + DSL snippets +            │
           │   proven strengths + observed weaknesses)                      │
           ▼                                                                │
    ┌───────────────────────────┐                                           │
    │ 4. TREE DESIGN (Phase 1)  │                                           │
    │  model emits tree DSL     │                                           │
    │  informed by corpus match │                                           │
    └──────┬────────────────────┘                                           │
           │ emitted tree DSL                                               │
           ▼                                                                │
    ┌───────────────────────────┐                                           │
    │ 5. EXECUTION (Phase 2)    │                                           │
    │  emit-tree! runs the DSL  │                                           │
    │  in sandbox; model can    │                                           │
    │  iterate / recover        │                                           │
    └──────┬────────────────────┘                                           │
           │ :rlm/tree-execution-completed + node events                    │
           ▼                                                                │
    ┌───────────────────────────┐                                           │
    │ 6. EVALUATION             │                                           │
    │  5 auto-attached judges   │                                           │
    │  (heuristic-structural +  │                                           │
    │   grounding + reasoning + │                                           │
    │   completeness +          │                                           │
    │   instruction-following)  │                                           │
    └──────┬────────────────────┘                                           │
           │ :judge/score-emitted events                                    │
           ▼                                                                │
    ┌──────────────────────────────────────────────────────────────────┐    │
    │ 7. LIVING DESCRIPTION CONSOLIDATION                              │    │
    │  rolling aggregator (threshold: ~10 events) fires consolidator   │    │
    │  LLM → :ontology/*-description-updated → ColBERT async re-index │────┘
    │  Pattern body version increments; next matching task sees a      │
    │  richer prepend with updated strengths, weaknesses, and DSL      │
    └──────────────────────────────────────────────────────────────────┘
```

**What `:auto-classify? true` activates** (stages 2–3):

1. **Classification**: `classify-task` (structural tree-class match) and
   `classify-behaviors` (behavioral-subtree match) run against the seeded
   corpus.
2. **LLM reranker**: top-N matched patterns are scored against your task's
   intent — each candidate receives a fitness-score and a reasoning string.
3. **Corpus prepend**: the top-fitting pattern's full body is prepended to
   the model's instruction before Phase 1 starts.
4. **Post-execution body evolution** (stage 7): after the tree runs,
   execution events and judge scores feed the Living Description loop.
   Strength/weakness confidence counts increment; when the pattern hits
   the consolidation threshold a consolidator cycle updates the pattern
   body so the next matching task sees enriched examples.

**What `:recursive? true` activates** (stage 5):

The model can iterate: design a tree, run it, inspect results via
drill-down primitives (`tree-detail`, `tree-failures`, `node-output`),
refine, and call `(final! ...)` when satisfied. Mid-tree failures are
recoverable via focused single-node resume trees rather than a full
rebuild.

### Batch evaluation workflows

These workflows are useful for retrospective analysis — auditing judge
scores, investigating low-scoring traces, and verifying that the corpus
body is evolving as expected after a run.

**In the normal loop, judge firing and body evolution are automatic** (Stage
6 + Stage 7 above). You don't need to trigger them manually. The workflows
below are for investigation and debugging.

**Batch evaluation — analyze historical traces:**

```clojure
(require '[ai.obney.orc.evaluation.interface :as eval])
(require '[ai.obney.orc.orc-service.interface :as orc])

;; Get last 50 traces for a specific node
(def traces (eval/get-llm-traces ctx {:sheet-id sheet-id
                                       :node-id "analyze-lead"
                                       :since #inst "2024-01-01"
                                       :limit 50}))

;; Run batch evaluation
(def batch-result (orc/execute ctx (eval/batch-evaluation-suite)
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

**Consumer pattern — watch low-score evaluations for alerting:**

The automated judge firing (Stage 6) is shipped and runs without this.
If you want additional downstream handling — custom alerting, routing
low-score events to your own ontology workflows — you can add a todo
processor:

```clojure
(require '[ai.obney.grain.command-processor.interface :as cp])

;; Consumer-owned todo processor for custom low-score routing.
;; Not required — body evolution via Living Descriptions is automatic.
(deftodo :your-domain on-low-score-rlm-eval
  "Route low-scoring RLM evaluations to custom handling"
  {:event-types #{:judge/score-emitted}}
  (fn [ctx event]
    (when (< (get-in event [:body :score]) 0.7)
      ;; Your custom handling here — e.g., alert, record to a separate
      ;; ontology, or trigger a manual curator review workflow.
      (cp/run-command! ctx :your-domain/flag-for-review
        {:trace-id (get-in event [:body :trace-id])
         :score    (get-in event [:body :score])}))))
```

### Troubleshooting

#### Corpus prepend not appearing

**Symptoms:** Researcher's prompt does not include a
"## Suggested patterns from corpus" block; the R-Inject trace file is
missing or empty.

**Check:**

1. Is `:auto-classify? true` set on the `:repl-researcher`'s `:rlm` config?
2. Has `seed-baseline-corpus!` been called? Without seeds, the classifier
   has nothing to retrieve against.
3. Has the ColBERT index been built? Call `bootstrap-reindex!` after
   seeding. Without a built index, `search-descriptions` returns `[]` and
   the prepend is silently skipped — the researcher still runs, just
   without a corpus prepend.
4. Check the R-Inject trace file at
   `/tmp/r-inject-trace-<sheet-id>.edn`. It records which pattern was
   matched, the reranker's reasoning verbatim, and the full prepend block
   actually sent to the model.

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

;; Verify the corpus has been seeded and a known pattern is readable
(ontology/get-description ctx :tree-class some-known-pattern-id)
;; Should return a non-nil body; nil means corpus is empty or unseeded.

;; Trigger index rebuild if the index is stale or missing
(ontology/bootstrap-reindex! ctx)
```

#### Pattern body not evolving after runs

**Symptoms:** `ontology/get-description` returns the same `:version`
after multiple runs of the workflow; the body does not update with new
strengths or weaknesses.

**Check:**

1. Are judges firing? Check the event store for `:judge/score-emitted`
   events tagged with the sheet's tick. If no judge events exist,
   the Living Description opt-in may not be on, or the judges may not
   be auto-attached.
2. Has the consolidation threshold been hit? The default is ~10 events
   accumulated per pattern target before a consolidation cycle fires.
   Early runs may not trigger a cycle — check the event count.
3. Read the description history to see the last consolidation timestamp.

```clojure
;; Check version and history
(ontology/get-description-history ctx :tree-class pattern-target-id)
;; If history shows only 1 entry, consolidation hasn't fired yet.
;; The system needs ~10 relevant events before a cycle triggers.

;; Check judge events for a specific tick
(es/read event-store {:tags #{[:tick tick-id]}
                      :event-types #{:judge/score-emitted}})
;; Should show score-emitted events if judges are wired correctly.
```

#### Event store empty after commands

**Symptoms:** Commands return success but read models show no data.

**Check:**

1. Are events being appended? Check event store state.
2. Is the event type registered in schemas?
3. Are tags using UUIDs only (not strings)?

```clojure
;; Check total event count in the store
(count (:events @(:state event-store)))
```

---

## When NOT to use the self-improving loop

The loop assumes:
- Your workflows run frequently enough that observed evidence is
  meaningful (handful of runs to start producing usable corpus updates;
  dozens for confident principle extraction).
- Tasks within the same family share enough structure that pattern
  retrieval is useful. Wildly heterogeneous one-off tasks won't benefit.
- You're comfortable with the model designing its own behavior tree.
  If you need full structural control, build a non-recursive workflow
  with explicit `:llm` / `:map-each` / `:code` nodes and skip
  `:auto-classify?` / `:recursive?` entirely.

For workflows where the structure is genuinely fixed (e.g., a contract
ingestion pipeline that runs the same 4-step extraction every time),
the simpler non-RLM behavior tree is the right tool. ORC supports both
shapes side-by-side in the same codebase.

---

## Configuration reference

| Flag | What it does | When to enable |
|---|---|---|
| `:rlm {:auto-classify? true}` on a `repl-researcher` | Prepends top-fitting corpus pattern's body to the model's instruction | Whenever you want the model to draw on observed-good patterns |
| `:rlm {:recursive? true}` on a `repl-researcher` | Model can iterate: design → run → inspect → refine → final! | Default for any non-trivial task; pairs with `:auto-classify?` |
| `:rlm {:recursive? false}` (or `:rlm true`) | Single-shot tree emission; model returns Phase-2 result directly | Legacy / explicit non-iterative use |
| `:rlm {:max-iterations N}` | Caps Phase-1 iteration count (default 5) | When you want a strict budget on recovery attempts |
| `:rlm {:timeout-ms N}` | Wall-clock budget for the whole repl-researcher (default 900K ms = 15 min) | Right-size for task complexity |
| `:rlm {:sub-model "..."}` | Cheaper model for inner `:llm` nodes in emitted trees | Plan with strong model, execute with cheap one |
| Top-level `:model` | The model designing the tree (Phase 1) | Generally a capable model (gemini-3-flash-preview, gpt-5, claude-sonnet) |

---

## Related guides

- [`RLM-GUIDE.md`](RLM-GUIDE.md) — recursive RLM mode in depth: tree DSL,
  sandbox primitives, drill-down, sub-LLM cost control, vision inputs,
  output schemas
- [`LIVING-DESCRIPTIONS.md`](LIVING-DESCRIPTIONS.md) — consolidation
  architecture, description granularities (tree-class, node-instance,
  node-type), the four anti-recency safeguards, and the judge integration
  that feeds the consolidator
- [`GETTING-STARTED.md`](GETTING-STARTED.md) — Phase 4 (GEPA prompt
  optimization) and Phase 6 (self-improving loop setup with seed corpus
  and ColBERT index bootstrapping)
- [`GEPA-GUIDE.md`](GEPA-GUIDE.md) — the *other* improvement actuator:
  optimizing static `:llm` instruction strings via Pareto selection and
  reflective mutation (orthogonal to `:auto-classify?`; see
  [Two orthogonal improvement actuators](#two-orthogonal-improvement-actuators))
- [`archived/FEEDBACK-LOOP.md`](archived/FEEDBACK-LOOP.md) — the pre-RLM
  continuous-improvement framing; archived as of DOC-18 (content migrated here)
- [`ORC-SERVICE-GUIDE.md`](ORC-SERVICE-GUIDE.md) — the foundation:
  building behavior trees, blackboard schemas, node types
