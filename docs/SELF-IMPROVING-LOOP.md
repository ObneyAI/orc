# Self-Improving Workflows in ORC

> **For developers building LLM workflows on top of ORC.** This guide
> explains how to write workflows that get better as they run — without
> writing any extra learning code yourself.

> **⚠ Alpha — read this before adopting in production.**
> The self-improving loop ships as alpha-stage capability. It works
> well for in-distribution workflows that align with the shipped seed
> corpus, and it's worth using today for those. Workflows that fall
> far outside the corpus's covered domains will see honest-but-thin
> classifications and rare behavioral mints. See
> [Current capabilities and known limitations](#current-capabilities-and-known-limitations)
> below for what's solid, what's rough, and what's actively being
> investigated.

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

That's it. With these two flags:

- **`:auto-classify? true`** — before the model starts designing, ORC
  classifies your task against the seed corpus and prepends the top-fitting
  pattern's full body (capabilities + worked-example DSL snippets +
  observed strengths and weaknesses) to the model's instruction.

- **`:recursive? true`** — the model can iterate: design a tree, run it,
  inspect the results via drill-down primitives, refine, and call
  `(final! ...)` when ready. If a leaf in its emitted tree fails, the
  model can emit a smaller tree to recover rather than abandoning the
  partial progress.

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

## Current capabilities and known limitations

The self-improving loop is **alpha-stage** as of this writing. The
components that compose the loop work as documented — but the
*aggregate behavior on workflows far outside the shipped seed corpus
is honest-but-thin*, not what a "self-improving" label would imply
at maturity. The framing in this section comes from a real 21-task
OOD evidence-gathering sweep, not from marketing.

### Solid (use without hesitation)

- **In-distribution classification.** Tasks that resemble shipped seed
  patterns (legal-issue-detection, contract-comparison, risk-analysis,
  chunked-extraction, etc.) match at confidence 1.00 and the prepend
  carries the seed's full worked-example DSL.
- **Recursive RLM with drill-down primitives.** `(tree-detail)`,
  `(tree-failures)`, `(node-output ...)` all work; the model uses
  them to recover from mid-tree failures via smaller resume trees.
- **Consolidator-driven body evolution for stable patterns.** When
  the same pattern gets repeated traffic, the body version increments
  with new strengths grounded in observed execution.
- **`mint-behavior!` mechanics.** The defcommand path, persistence,
  ColBERT re-index, and same-iteration lookup all work as documented.

### Rough today

- **Out-of-distribution classification.** Tasks whose semantics fall
  outside the shipped 23 tree-class + 12 behavioral seeds tend to
  force-fit to the structurally-closest pattern at confidence 0.85-0.95,
  with mechanically-plausible reranker reasoning that ignores domain
  specialization. The classifier isn't broken — it's matching on
  structural shape because that's what the corpus exposes — but the
  prepend the model receives doesn't tell it that the pattern is a
  shape-approximation, not a domain match.

- **Mint-behavior! firing rate in practice.** In the OOD evidence
  sweep, the agent's mint-behavior! affordance fired on 1 of 21
  deliberately-OOD tasks, and that 1 was triggered by a reranker
  failure rather than a quality judgment. Today's classifier does
  not surface "no candidate is a good semantic match" as a strong
  signal to the model — it surfaces top-N matches with reasoning,
  and the model treats those as coverage.

- **Hierarchical specialization is missing from the behavioral seed
  layer.** The 12 abstract behaviors (Research / Extraction / Analysis
  / Synthesis / Ideation / Design / Critique / Validation /
  Code-building / Transformation / Classification / Investigation)
  describe shape but not domain. There's no "Analysis-of-source-code"
  or "Validation-of-schedule-against-hard-constraints" child entries
  yet. Whether adding them would solve the OOD force-fit pattern is
  an open question being investigated (see HANDOFF below).

### Active investigation

The R04 OOD verification work in
`development/bench/ood-stress-results/` is an evidence gathering
arc that surfaced the above limits. Two paths forward are being
considered:

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
- [`LIVING-DESCRIPTIONS.md`](LIVING-DESCRIPTIONS.md) — how the corpus
  builds and protects itself; safeguards against over-reacting to recent
  runs; description granularities (tree-class, node-instance, node-type)
- [`FEEDBACK-LOOP.md`](FEEDBACK-LOOP.md) — the larger continuous-
  improvement cycle ORC fits into
- [`ORC-SERVICE-GUIDE.md`](ORC-SERVICE-GUIDE.md) — the foundation:
  building behavior trees, blackboard schemas, node types
