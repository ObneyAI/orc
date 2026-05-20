# RLM (Research Language Model) Mode

RLM is a two-phase execution pattern for the `:repl-researcher` node type. The LLM iteratively generates Clojure code in a sandboxed REPL (Phase 1) and can optionally emit behavior trees that ORC executes as child ticks (Phase 2). When recursive mode is enabled, the model can inspect Phase 2 outputs and continue reasoning rather than treating `emit-tree!` as a terminator.

## When to use RLM

RLM fits problems where the *right tree shape isn't known up front*. The model designs the workflow as it learns about the input. Examples:

- **Analytical tasks on large documents** — model decides whether to chunk, how many parallel iterations, how to synthesize
- **Multi-step extraction** — emit a tree to get summary, then inspect, then call follow-up LLM/code for derived metrics
- **Adaptive recovery** — when a tree returns `:partial` with some chunks failed, the model can decide to retry, fall back, or accept what it has

If your tree shape is fixed and known, use the regular ORC DSL directly — don't pay the Phase 1 code-gen overhead.

## Composition: `repl-researcher` as a node inside a larger workflow

`orc/repl-researcher` is a leaf-style node like `orc/llm` or `orc/code` — it sits anywhere in your behavior tree, not just at the root. Upstream nodes can write to blackboard keys the researcher reads; downstream nodes can consume what the researcher wrote.

A common pattern is **pre-process → research → post-process**, using the high-level DSL:

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc])

(def my-workflow
  (orc/workflow "research-pipeline"
    (orc/blackboard
      {:raw-input          :string
       :cleaned-input      :string
       :researched-summary :string
       :final-report       :string})

    (orc/sequence "main"
      (orc/llm "pre-process"
        :model "google/gemini-2.5-flash"
        :instruction "Clean the raw input. Remove extra whitespace, normalize punctuation."
        :reads  [:raw-input]
        :writes [:cleaned-input])

      (orc/repl-researcher "research"
        :model "google/gemini-2.5-flash"
        :instruction "Produce a one-paragraph summary highlighting key facts."
        :reads  [:cleaned-input]
        :writes [:researched-summary]
        :max-iterations 3
        :rlm true)                       ; or {:recursive? true} / {:debug? true}

      (orc/llm "post-process"
        :model "google/gemini-2.5-flash"
        :instruction "Wrap the summary as a brief executive report."
        :reads  [:researched-summary]
        :writes [:final-report]))))

;; Build (idempotent — no-op if definition hasn't changed) and execute.
;; build-workflow! returns the deterministic sheet-id derived from the
;; workflow name, so you can pipe it straight into execute.
(let [sheet-id (orc/build-workflow! ctx my-workflow)]
  (orc/execute ctx sheet-id {:raw-input some-text}))
```

Execution flow:

1. The `sequence` node runs its children in order.
2. `pre-process` writes `:cleaned-input` to the blackboard.
3. The researcher reads `:cleaned-input`, runs its iterative Phase 1 (and Phase 2 if it calls `emit-tree!`), writes `:researched-summary`.
4. `post-process` reads `:researched-summary` and writes `:final-report`.
5. The execute call's `:outputs` includes everything written to the blackboard.

There's nothing special about being a child — the researcher emits the same `:sheet/node-execution-completed` event as any other leaf, and sequence/fallback/parallel parents react to it the same way.

### Things to be aware of when composing

- **Blackboard keys must be declared up front** in `orc/blackboard`. The researcher's `:reads` only see declared keys that prior nodes have written.
- **The researcher reports `:status :success` / `:failure` / `:partial` / `:timeout` like any other node.** Sequence parents continue on `:success` and `:partial`, halt on `:failure`. Fallback continues on `:failure` to the next sibling. So you can place the researcher anywhere in those compositions.
- **Budget composition.** If you set `:timeout-ms` on the researcher, it bounds total Phase 1 + cumulative tree-execution wall-time *for that researcher only* — it doesn't account for sibling nodes. If your overall workflow has a deadline, the researcher's `:timeout-ms` should be set lower than the remaining time you want to allow.
- **Multiple researchers in one workflow** are supported — each gets its own Phase 1 loop and (if it calls `emit-tree!`) its own Phase 2 child tick. Their iteration counts and budgets are independent.

## Basic usage (terminal mode — default)

The simplest case: a workflow whose root is a single `repl-researcher` node.

```clojure
(require '[ai.obney.orc.orc-service.interface :as orc])

(def risk-analysis
  (orc/workflow "risk-analysis"
    (orc/blackboard
      {:document          :string
       :risk-matrix       :string
       :executive-summary :string})

    (orc/repl-researcher "researcher"
      :model "google/gemini-2.5-flash"
      :instruction "Analyze the document for risks and obligations. Produce
                    a :risk-matrix mapping each obligation to HIGH/MEDIUM/LOW
                    with justification, and an :executive-summary."
      :reads  [:document]
      :writes [:risk-matrix :executive-summary]
      :max-iterations 5
      :rlm true)))                       ; or {:debug? true} for verbose logging

;; Build (idempotent — no-op if definition hasn't changed) and execute.
;; build-workflow! returns the deterministic sheet-id derived from the
;; workflow name.
(let [sheet-id (orc/build-workflow! ctx risk-analysis)]
  (orc/execute ctx sheet-id {:document doc-text}))
;; => {:status :success
;;     :outputs {:risk-matrix "..." :executive-summary "..."}
;;     :duration-ms ...}
```

The model:

1. Iterates up to `:max-iterations` times, generating Clojure code each iteration.
2. Code can call `(llm ...)`, `(code ...)`, `(store! ...)`, `(get-var ...)`, etc.
3. Optionally calls `(emit-tree! [...])` to design a behavior tree for ORC to execute.
4. Terminal mode: when the model calls `emit-tree!`, Phase 2 runs and the result returns to the caller — the loop ends immediately.

## Recursive mode (`:rlm {:recursive? true}`)

When the `:recursive?` flag is set, `emit-tree!` is no longer terminal:

```clojure
{:rlm {:recursive? true :debug? true}}
```

After Phase 2 completes:
- Tree's `:writes`-declared outputs merge into sandbox-vars (model calls `(get-var :summary)` naturally)
- A lightweight summary entry appends to `:tree-results` (chronological history)
- Control returns to Phase 1 loop
- Model gets a fresh iteration to reason about the result, run follow-up `(llm ...)` / `(code ...)`, emit another tree, or call `(final! ...)` to terminate

The loop ends ONLY when:
- Model calls `(final! {...})` with the declared `:writes` keys
- `:max-iterations` is exhausted (returns `:failure :error "Max iterations reached without final!"`)
- The total `:timeout-ms` budget is exhausted

### What the model sees after a tree

Each entry in `:tree-results` (visible via `(get-var :tree-results)`):

```clojure
{:tick-id <uuid>
 :tree-raw [:sequence [...]]                   ; the tree the model designed
 :status :success | :partial | :failure | :timeout
 :elapsed-ms 4523
 :outputs-keys [:summary :chunk-summaries]     ; what's now in sandbox-vars
 :nodes-succeeded 22
 :nodes-failed 2
 :nodes-total 24
 :usage {:prompt-tokens N :completion-tokens N :total-tokens N}

 ;; ONLY when :status is :partial or :failure
 ;; (verbatim from the underlying map-each's :partial-summary):
 :failure-indices [7 17]
 :failure-reasons {7 "Rate limit exhausted" 17 "Schema validation failed"}

 ;; ONLY when :status is :timeout (Phase 2 budget cancellation):
 :phase2-elapsed-ms 4500
 :budget-remaining-ms 0
 :nodes-completed-before-cancel 4}
```

The summary is **descriptive, not prescriptive** — raw enums, factual counts, verbatim error strings. The model interprets for its own task. No `:severity :high`, no `:recommended-action`. The executor doesn't editorialize.

### Drill-down primitives (when the summary isn't enough)

In recursive mode the SCI sandbox also exposes five primitives that read directly from the event store for any tree the model has run. These let the model pull structural detail on demand — per-node `:status`, `:input-profile`, full trajectories, per-failure errors — without us baking everything into the lightweight `:tree-results` summary.

| Primitive | Returns |
|---|---|
| `(tree-detail)` / `(tree-detail tick-id)` | `{:tick-id :status :tree-raw :outputs :nodes [...]}` for the most-recent / specific tree. Each node entry has `:node-id :status :duration-ms :writes :usage :input-profile` and (for map-each parents) a verbatim `:partial-summary`. |
| `(tree-trajectory)` / `(tree-trajectory tick-id)` | Chronological per-event log from the tree's `:sheet/rlm-tree-execution-completed` bookend `:trajectory` field. |
| `(tree-failures)` | Vector of failure entries for the most-recent tree — joins direct leaf failures and map-each `:partial-summary` failure indices into one list, each with `:error` and (where available) `:input-profile`. |
| `(node-output node-id)` | `:writes` map of the named node's `:sheet/node-execution-completed` event in the most-recent tree. |
| `(node-input-profile node-id)` | `:input-profile` (chunk shape, byte/word counts, etc.) of the named node's `:sheet/rlm-tree-node-completed` event in the most-recent tree. |

Usage guidance baked into the system prompt: **prefer the `:tree-results` summary; drill down only when the summary doesn't give you enough to decide your next step.** A `(tree-trajectory)` call can return multi-KB data — pulling it into every iteration's history bloats the next prompt.

These primitives are bound in the sandbox **only when `:recursive? true`** — non-recursive callers (`:rlm true`, `:rlm {:debug? true}`) get unresolved-symbol if they try to call them.

### Example: two-step task

```clojure
;; Task: summarize document, then count distinct entities, return both.

;; The model writes (in iteration 1):
(emit-tree!
  [:sequence
   [:llm {:instruction "Produce a one-paragraph summary"
          :reads [:document]
          :writes [:summary_text]}]
   [:final {:keys [:summary_text]}]])
;; → Phase 2 runs, recur fires

;; The model writes (in iteration 2 — :tree-results now visible):
(let [summary (get-var :summary_text)
      entities (llm "extract-entities"
                    :instruction "List all distinct named entities"
                    :reads [:document]
                    :writes [:entities_list])]
  (final! {:summary summary
           :entity-count (count (clojure.string/split (:entities_list entities) #","))}))
;; → final! reached, loop terminates with both outputs
```

Real LLM verification: `:status :success`, `:entity-count` reflects actual entity count from the doc, `:cumulative-thinking-ms` + `:cumulative-tree-ms` surface where wall-time was spent.

## Sandbox primitives

When the model's Phase 1 code executes in the SCI sandbox, the following primitives are available:

| Primitive | Purpose |
|---|---|
| `(llm "name" :instruction ... :reads [...] :writes [...])` | Sub-LLM call (synchronous; result merged into sandbox-vars) |
| `(code "name" :writes [...] :body ...)` | Pure Clojure computation (no LLM call) |
| `(sequence ...)` | Run primitives in order |
| `(parallel ...)` | Run primitives concurrently, merge results |
| `(map-each ...)` | Iterate over a collection with a child primitive |
| `(fallback ...)` | Behavioral combinator — first success wins |
| `(condition ...)` | Conditional execution |
| `(store! :key value)` | Store a value in sandbox-vars |
| `(get-var :key)` | Retrieve a value from sandbox-vars |
| `(list-vars)` | List all sandbox-vars with previews |
| `(get-input :key)` | Read a declared input |
| `(final! {...})` | **Terminate** with validated output |
| `(emit-tree! [...])` | Emit a behavior tree for Phase 2 execution |

All primitives execute IMMEDIATELY and return results to sandbox-vars. The model can compose them freely, reason about results across iterations (the history is rebuilt into each subsequent prompt), and decide its next move.

## Phase 2 tree DSL node types

When the model calls `(emit-tree! [...])`, the literal S-expression it writes is the **Phase 2 tree DSL** — a separate language from the Phase 1 SCI sandbox above. The supported node types are:

| Node | Shape | Purpose |
|---|---|---|
| `:sequence` | `[:sequence child1 child2 ...]` | Run children in order, stop on first failure |
| `:parallel` | `[:parallel child1 child2 ...]` | Run children concurrently (must be independent) |
| `:fallback` | `[:fallback child1 child2 ...]` | Run children in order, return first success |
| `:map-each` | `[:map-each {:from :coll :as :item :into :results :max-concurrency N} child]` | Apply child to each item; `:max-concurrency` defaults to 1 |
| `:llm` | `[:llm {:instruction "..." :reads [...] :writes [...]}]` | Sub-LLM call with declared I/O |
| `:code` | `[:code {:reads [...] :writes [...] :fn (fn [{:keys [inputs]}] ...)}]` | Pure-Clojure transform inside the tree. `:fn` receives `{:inputs <map-of-read-keys>}` and must return a map keyed by the declared `:writes`. Use for deterministic transforms (counts, joins, reductions) instead of paying a sub-LLM. `:fn` may also be a `"qualified.symbol/string"` resolved at execution time. |
| `:chunk-document` | `[:chunk-document {:from :doc :size 8000 :into :chunks}]` | Helper: split a string into chunks |
| `:aggregate` | `[:aggregate {:from :coll :writes [...]}]` | Helper: merge map-each results into per-key vectors |
| `:condition` | `[:condition pred then-child else-child]` | Conditional execution |
| `:final` | `[:final {:keys [...]}]` | Marker — declares which sandbox keys form the tree's outputs |

Note that `:code` here is a **tree-DSL node** (Phase 2, runs inside a child sheet), distinct from the Phase 1 sandbox `(code ...)` primitive listed in the table above. The Phase 1 primitive runs immediately in the model's SCI context; a Phase 2 `:code` node compiles down to a `sheet/code` leaf, registered via the ephemeral-fn registry so the inline function value survives the child-sheet boundary.

## Output Contract

The model is told:

```
You MUST call (final! {...}) with keys: [<the :writes-declared keys>]
```

And, for the prompt format DSCloj's parser expects:

```
Your response MUST start with `[[ ## code ## ]]` on its own line, followed by
RAW Clojure code (NO markdown code fences, NO ```clojure tags), and end with
`[[ ## completed ## ]]`.
```

Some models (notably gemini-2.5-flash) default to markdown fences which DSCloj's marker-based parser silently drops. The "CRITICAL OUTPUT FORMAT" prompt section is what keeps them aligned.

## Safety surface

| Knob | Purpose |
|---|---|
| `:max-iterations` (default 10) | Caps the number of Phase 1 code-gen iterations |
| `:timeout-ms` (default 900_000) | Total wall-time budget for Phase 1 + cumulative tree execution |
| `:llm-call-budget` (opt-in, no default) | Hard cap on total LLM call count per tick |

Trees are "free" from the iteration counter — `:max-iterations` only counts code-gen calls. Budget exhaustion in Phase 1 skips Phase 2 entirely with `:status :failure :error "Budget exhausted in Phase 1"`. Phase 2 timeouts trigger `:sheet cancel-tick` on the child tick with ~500 ms drain.

## How partial outcomes propagate

When a `map-each` inside a Phase 2 tree completes with some children succeeded and some failed:

- **All succeeded** → `:status :success`, output is the full vector
- **Some succeeded** (`0 < failed < total`) → `:status :partial`, output is **successes-only** vector
- **All failed** → `:status :failure`, output is `[]`
- **Empty source list** → `:status :success`, output is `[]`

Sequence and fallback parents treat `:partial` as continuation (downstream synthesis runs on successes). Sequences propagate `:partial` *stickily* — if any child was partial, the overall sequence reports `:partial`.

The map-each's `:sheet/node-execution-completed` event body carries a `:partial-summary`:

```clojure
{:total N :succeeded N :failed M
 :failure-indices [...]
 :failure-reasons {idx error-string ...}}
```

This is the data your judge layer subscribes to. The model in recursive mode sees the same data via `:tree-results`'s `:failure-indices` / `:failure-reasons` fields.

## Observability events emitted

Per the Grain methodology — every event is monitorable.

| Event | When emitted | Body |
|---|---|---|
| `:sheet/node-execution-completed` | Every node finishes | `:status`, `:writes`, `:duration-ms`, `:usage`, optional `:partial-summary` |
| `:sheet/rlm-tree-node-completed` | Per-node inside RLM Phase 2 trees | Structured `:node-path`, `:usage`, `:input-profile` |
| `:sheet/rlm-tree-execution-completed` | Bookend per Phase 2 tree | `:trajectory` (full per-event log), `:total-usage`, `:task-fingerprint` placeholder |
| `:sheet/tick-cancelled` | When Phase 2 budget cancellation fires mid-flight | `:sheet-id`, `:tick-id` |

Judges in future work can subscribe to any of these for granular signal.

## Result shape

When you call `orc/execute`, the result delivered to your caller has:

```clojure
{:status :success | :partial | :failure | :timeout
 :outputs {<your declared :writes keys> ...}
 :usage {:prompt-tokens N :completion-tokens N :total-tokens N
         :by-node {<structured-path> {tokens per node}}}      ; per-node breakdown
 :duration-ms N                                                ; total wall-time
 :trace-id <uuid>                                              ; the tick-id, for drill-down via event store
 :error <string?>                                              ; present when :status is :failure / :timeout
 :generated-tree-raw [...]}                                    ; the raw S-expr tree when the model emitted one
```

This is what `runtime/deliver-completion!` forwards to the synchronous caller — same shape regardless of whether the researcher ran in terminal or recursive mode.

**Phase-level timing breakdown** (`:phase1-elapsed-ms`, `:phase2-elapsed-ms`, `:phase2-tick-id`, `:cumulative-thinking-ms`, `:cumulative-tree-ms`) is **not currently surfaced through the `sheet/execute` envelope**. It exists in the inner researcher response (visible if you invoke `execute-repl-researcher-rlm` directly), and the underlying events (`:sheet/node-execution-completed`, `:sheet/rlm-tree-execution-completed`) carry the per-node and per-tick timing data that observability consumers can reconstruct from the event store. Surfacing the breakdown on the top-level execute result is a planned enhancement.

## Generalization benchmark

The repo ships a 5-task benchmark that demonstrates RLM generalizes — the model designs **structurally different trees** for **structurally different tasks** given only goal-only instructions (no example trees, no algorithm hints). See [`development/bench/RESULTS.md`](../development/bench/RESULTS.md) for the headline report and [`development/bench/README.md`](../development/bench/README.md) for run instructions.

The bench tasks use the *terminal* `emit-tree!` mode (`:rlm {:debug? true}`). They demonstrate the model's ability to design one good tree for a task — not the recursive flow. A separate slice will migrate the bench to recursive mode and validate no-regression there.

## Quick reference

```clojure
;; Terminal mode (model designs ONE tree, Phase 2 runs, done)
{:rlm true}
{:rlm {:debug? true}}

;; Recursive mode (emit-tree! returns to Phase 1; model decides next move)
{:rlm {:recursive? true}}
{:rlm {:recursive? true :debug? true}}
```

Same node config either way. The `:recursive?` flag controls one thing: whether Phase 2's result is returned to the caller (terminal) or merged back into sandbox-vars + recurred (recursive).

### Recursive-mode extras

When `:recursive? true` is set, the sandbox also exposes the drill-down primitives `tree-detail`, `tree-trajectory`, `tree-failures`, `node-output`, and `node-input-profile` for reading the full event-store record of any tree the model has already run. The lightweight `:tree-results` summary is the primary signal; drill-downs are there for when the summary isn't enough. See [Drill-down primitives](#drill-down-primitives-when-the-summary-isnt-enough) above.

### Phase 2 tree DSL

The DSL the model writes inside `(emit-tree! [...])` supports `:sequence`, `:parallel`, `:fallback`, `:map-each`, `:llm`, `:code`, `:chunk-document`, `:aggregate`, `:condition`, and `:final`. See [Phase 2 tree DSL node types](#phase-2-tree-dsl-node-types) above for the full shape of each.

## Related guides

- [`docs/ORC-SERVICE-GUIDE.md`](ORC-SERVICE-GUIDE.md) — Core execution engine and DSL reference
- [`docs/dsl-tutorial.md`](dsl-tutorial.md) — Step-by-step DSL tutorial
- [`docs/EVENT-STORE-PATTERNS.md`](EVENT-STORE-PATTERNS.md) — Grain event-sourcing patterns
- [`development/bench/RESULTS.md`](../development/bench/RESULTS.md) — Generalization benchmark headline report
