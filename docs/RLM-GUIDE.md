# RLM (Recursive Language Model) Mode

**ORC is a workbench built on behavior trees.** This guide describes how Recursive Language Model strategies — a model that can spawn sub-computations, inspect their outputs, and continue reasoning — are applied in ORC's decomposition space: behavior trees the model emits via `emit-tree!`, with `:llm`, `:code`, `:map-each`, `:parallel`, `:sequence`, and `:final` as the building blocks.

In implementation terms, this is a two-phase execution pattern for the `:repl-researcher` node type. The LLM iteratively generates Clojure code in a sandboxed REPL (Phase 1) and can emit behavior trees that ORC executes as child ticks (Phase 2). **Recursive mode is the default** — each `emit-tree!` returns control to Phase 1 so the model can inspect outputs, accumulate sandbox-vars across iterations, and call `(final! ...)` when ready. Terminal mode (`:rlm {:recursive? false}`) is preserved as an explicit opt-out for the rare case a single tree emission is genuinely terminal. Phase 1 is the recursive code-generation loop; Phase 2 is the spawned sub-computation (the emitted tree); the recursive merge folds Phase 2 outputs back into Phase 1 sandbox-vars for further iteration.

Where other Recursive Language Model implementations might spawn sub-computations via `predict()` calls and `asyncio.gather()`, ORC's researcher emits a behavior tree. Same RLM strategies, expressed in ORC's behavior-tree decomposition space — and that's what this guide walks through.

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

## Basic usage (recursive mode — default)

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
4. Recursive mode (default): when the model calls `emit-tree!`, Phase 2 runs, its `:writes`-declared outputs are merged into the sandbox variables (accessible via `(get-var :key)`), a summary entry is appended to `:tree-results`, and control returns to Phase 1 for another iteration. The loop ends when the model calls `(final! {...})` or `:max-iterations` is exhausted.
5. Terminal mode (`:rlm {:recursive? false}` — explicit opt-out): when the model calls `emit-tree!`, Phase 2 runs and the result returns to the caller immediately — the loop ends without further Phase 1 iterations.

## Configuration reference

All options accepted by the `repl-researcher` node and the `:rlm` config map.

### Top-level options on `repl-researcher`

| Option | Type | Default | Purpose |
|---|---|---|---|
| `:model` | string | required | LLM identifier for the Phase-1 researcher (e.g. `"openai/gpt-5.4"`, `"google/gemini-2.5-flash"`). Also the default for Phase-2 `:llm` sub-calls when `:sub-model` is not set. |
| `:sub-model` | string | nil (uses `:model`) | LLM identifier injected into every Phase-2 `:llm` node that does NOT specify its own `:model`. Lets Phase-1 use a high-capability main LM while Phase-2 sub-calls go through a cheaper/faster sub LM. See [Main LM + Sub-LM](#main-lm--sub-lm-sub-model) below. |
| `:instruction` | string | required | The model's task. Verbatim goal-only is preferred; the framework adds the methodology framing. |
| `:reads` | vector of keywords | `[]` | Blackboard keys to load into Phase-1 sandbox + Phase-2 child sheet. |
| `:writes` | vector of keywords | `[]` | Blackboard keys the model must populate via `(final! ...)` or via the emit-tree! tree's `:final` node. |
| `:max-iterations` | int | 5 | Max Phase-1 iterations. If the model neither calls `(final! ...)` nor calls `(emit-tree! ...)` within this many iterations, the run returns `{:status :failure :error "Max iterations reached without final!"}`. |
| `:timeout-ms` | int | 900000 (15 min) | Hard wall-clock budget for Phase 2. Precedence: node's `:timeout-ms` > parent tick's `:timeout-ms` > hardcoded 15-minute default. When Phase 2 budget is exhausted mid-flight the child tick is cancelled. |
| `:rlm` | map or `true` | `false` | Enables RLM mode. `true` is equivalent to `{}`. See "`:rlm` config map" below. |

### `:rlm` config map options

| Option | Type | Default | Purpose |
|---|---|---|---|
| `:recursive?` | bool | `false` | Non-terminal `emit-tree!` — after each Phase-2 tree completes, control returns to Phase 1 for inspection / follow-up / `(final! ...)`. See [Recursive mode](#recursive-mode-rlm-recursive-true). |
| `:debug?` | bool | `false` | Verbose `[DEBUG RLM]` / `[DEBUG Tree]` stderr logging useful during development. Default off for production. |
| `:available-code-nodes` | string | nil | Markdown catalog of pre-built `:code` fns the model can reference via `[:code {:fn "ns/sym"}]`. Surfaced as an extra DSCloj module input field. See [Pre-built code-node catalog](#pre-built-code-node-catalog-available-code-nodes). |
| `:sub-model` | string | nil | Alternative location for `:sub-model` — `(:sub-model (:rlm node))` takes precedence over `(:sub-model node)`. Either works. |

### Per-`:llm`-node options inside `emit-tree!` trees

The `:llm` tree-DSL node accepts these options that are NOT part of the top-level `repl-researcher` config:

| Option | Type | Default | Purpose |
|---|---|---|---|
| `:instruction` | string | required | The sub-LLM's prompt. |
| `:reads` | vector of keywords | `[]` | Blackboard keys passed as inputs. |
| `:writes` | vector of keywords | `[]` | Blackboard keys this `:llm` produces. |
| `:model` | string | inherits from `:sub-model` then `:model` | Per-node model override. Useful when one specific node needs a vision-capable model while other nodes go through the cheaper sub-LM. Example: `[:llm {:model "openai/gpt-4o" :reads [:image] :writes [:caption] ...}]`. Per-node `:model` takes precedence over `:sub-model` injection. |
| `:output-schemas` | map | `nil` | Per-write Malli schemas. When the schema is structured (vector / map / etc.) the framework asks the LLM for JSON and parses the response back into Clojure data for downstream `:code` consumers. See [Structured LLM outputs](#structured-llm-outputs-output-schemas). |
| `:retry` | map | `{:max-attempts 3 :backoff-ms [1000 2000 4000]}` | Per-node retry policy. Default is 3 attempts with 1s/2s/4s exponential-ish backoff. Override per node like `[:llm {:retry {:max-attempts 5 :backoff-ms [500 1000 2000 4000 8000]} ...}]`. |

### `:reads` / `:writes` validation

- **`:reads`**: the framework declares each read key on the child sheet's blackboard. If a read key isn't already declared at the parent level OR populated as an input to `tick-tree`, the value is nil at execution time (the framework does not error). A nil read often manifests as the `:llm` or `:code` body crashing on `nil` arithmetic — verify your reads are seeded.
- **`:writes`**: the framework declares each write key on the child sheet and validates that the executor's result populates those keys. For `:llm` nodes, the model is told "you must produce all `:writes` keys"; missing writes leave the key nil. For `:code` nodes, see U7 reconciliation (single-write scalar auto-wraps; multi-write must return a map containing the declared keys, or fails clearly).

### Tree-DSL → canonical translation

When you write `[:sequence [:llm {...}] [:final {...}]]`, the `rlm-dsl/rlm-dsl->orc-dsl` translator produces a canonical S-expression with `sheet/` prefixes:

```clojure
;; You write (DSL form):
[:sequence
 [:llm {:instruction "summarize" :reads [:doc] :writes [:summary]}]
 [:final {:keys [:summary]}]]

;; Framework executes (canonical form):
(sheet/sequence
  (sheet/llm :instruction "summarize"
             :reads [:doc]
             :writes [:summary]
             :retry {:max-attempts 3 :backoff-ms [1000 2000 4000]})
  (final! {:keys [:summary]}))
```

Notable transformations:
- Vector → list with `sheet/<name>` head
- Map options → flat keyword-value pairs
- Default `:retry` config added to every `:llm`
- `:chunk-document` / `:aggregate` expand into `sheet/code` with inline helper fns

When debugging an `emit-tree!` run, examine `:generated-tree-raw` (S-expr DSL — what the model wrote) AND `:generated-tree` (canonical — what the framework executes) on the run result.

## Main LM + Sub-LM (`:sub-model`)

By default, both the Phase-1 researcher and any Phase-2 `:llm` sub-calls use the node's `:model`. For benchmark or cost-optimization workflows you can split them: run a higher-capability **main LM** in Phase 1 (which writes Clojure code and designs trees) and a cheaper/faster **sub-LM** for the per-leaf Phase-2 `:llm` calls.

```clojure
(orc/repl-researcher "researcher"
  :model     "openai/gpt-5.4"        ; Phase-1 main LM (tree design)
  :sub-model "openai/gpt-5.1-chat"   ; Phase-2 sub-LM injected into :llm nodes
  :instruction "Identify PII targets in the provided document pages..."
  :reads  [:page-texts :criteria]
  :writes [:total-redactions :targets-applied]
  :rlm true)
```

The framework walks the canonical emit-tree! tree and injects `:model :sub-model` into every `(sheet/llm ...)` form that does **not** already specify a `:model`. `:llm` nodes the model wrote with an explicit `:model "..."` are left untouched, so the model can still pin a specific model for a specific node (e.g. a vision-specific model for image reads).

Set on the `repl-researcher` node, or alternatively under the `:rlm` map as `:rlm {:sub-model "..."}`. When unset, all calls use `:model`.

This matches the predict-rlm bench's "apples-to-apples" pattern (gpt-5.4 main + gpt-5.1-chat sub). See [`development/bench/predict-rlm-comparison/`](../development/bench/predict-rlm-comparison/) for runnable examples.

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
| `:llm` | `[:llm {:instruction "..." :reads [...] :writes [...]}]` | Sub-LLM call with declared I/O. Optional `:output-schemas {<write-key> <Malli-schema>}` declares the shape of each write. When the schema is structured (vector/map/etc.), the framework asks the LLM for valid JSON and parses the response back to Clojure data — useful for chaining structured outputs into downstream `:code` consumers (see "Structured LLM outputs" below). Optional `:model "..."` pins a specific LLM for this node (otherwise inherits `:sub-model` if set, else `:model`). |
| `:code` | `[:code {:reads [...] :writes [...] :fn (fn [{:keys [inputs]}] ...)}]` | Pure-Clojure transform inside the tree. `:fn` receives `{:inputs <map-of-read-keys>}` and must return a map keyed by the declared `:writes`. Use for deterministic transforms (counts, joins, reductions) instead of paying a sub-LLM. `:fn` may also be a `"qualified.symbol/string"` resolved at execution time. |
| `:chunk-document` | `[:chunk-document {:from :doc :size 8000 :into :chunks}]` | Helper: split a string into chunks |
| `:aggregate` | `[:aggregate {:from :coll :writes [...]}]` | Helper: merge map-each results into per-key vectors |
| `:condition` | `[:condition pred then-child else-child]` | Conditional execution |
| `:final` | `[:final {:keys [...]}]` | Marker — declares which sandbox keys form the tree's outputs |

Note that `:code` here is a **tree-DSL node** (Phase 2, runs inside a child sheet), distinct from the Phase 1 sandbox `(code ...)` primitive listed in the table above. The Phase 1 primitive runs immediately in the model's SCI context; a Phase 2 `:code` node compiles down to a `sheet/code` leaf, registered via the ephemeral-fn registry so the inline function value survives the child-sheet boundary.

### Structured LLM outputs (`:output-schemas`)

When an `:llm` node's downstream consumer is a `:code` node, you typically want the LLM's `:writes` value as parsed Clojure data, not a raw JSON string. Declare `:output-schemas` on the `:llm` node:

```clojure
[:llm {:instruction "Identify PII targets on this page."
       :reads  [:page-text]
       :writes [:targets]
       :output-schemas
       {:targets [:vector [:map [:page :int]
                                [:text :string]
                                [:category :string]
                                [:reason :string]]]}}]
```

The framework propagates the declared schema to the child sheet's blackboard key. DSCloj's `complex-spec?` detector recognizes the structured shape, instructs the LLM to respond with JSON matching that schema, and parses the response back into Clojure data before the downstream `:code` node reads it.

`:output-schemas` is a map from write-key to Malli schema. Keys without a declared schema fall back to `:any` and arrive as text.

### Pre-built code-node catalog (`:available-code-nodes`)

For benchmarks/tasks that ship deterministic helper functions (e.g. `apply-redactions`, `count-letter-frequencies`), set `:available-code-nodes` on the repl-researcher to surface a markdown catalog as an extra DSCloj module input field. The model sees the catalog as `inputs.available-code-nodes` and can reference any function via `[:code {:fn "ns/sym" :reads [...] :writes [...]}]` instead of writing the transform inline.

```clojure
(orc/repl-researcher "redactor"
  :model "openai/gpt-5.4"
  :instruction "Redact PII targets from the document."
  :reads  [:page-texts :criteria]
  :writes [:redacted-text-per-page :total-redactions :targets-applied :targets-missing]
  :rlm {:available-code-nodes (slurp "resources/redaction_catalog.md")})
```

The catalog format is freeform markdown; describe each function's purpose, `:reads` shape, `:writes` shape, and any behavioral notes. The model is reminded at the end of the framework prompt to consult this input before designing `:code` nodes.

### Vision inputs (`:field-type :image`)

For Phase-1 sub-LLM calls AND Phase-2 `:llm` leaf nodes that read image-typed blackboard values, declare the blackboard key's schema with `:field-type :image`:

```clojure
(orc/blackboard
  {:page-images [:vector {:field-type :image} :string]   ; vector of data URIs
   :query       :string})
```

The framework propagates `:field-type :image` into the DSCloj module's input field, which routes the value as a multimodal `image_url` content block rather than as inline text. Without `:field-type :image`, vision tasks ship base64 data URIs as inline text — wrong content shape AND ~480K tokens per image vs ~1K for image-tile billing.

The same schema is preserved from the parent sheet to the Phase-2 child sheet via `:blackboard-schemas`, so leaf `:llm` nodes inside `emit-tree!` trees inherit the correct routing.

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
| `:rlm/tree-generated` | When the researcher emitted a tree (Phase-2 will execute) | `:tree-id`, `:execution-id`, `:raw-dsl` (inline-fns sanitized to `"<inline-fn>"`), `:generated-at` |
| `:rlm/researcher-iterations` | Whenever the researcher ran ≥1 Phase-1 iteration, regardless of mode | `:execution-id`, `:iterations` (vector of `{:code :result :stdout :error :vars-created}`), `:iteration-count`, `:emitted-at` |
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

The bench tasks now run in recursive mode by default — each `emit-tree!` returns to Phase 1, the model can inspect outputs via `(get-var ...)`, accumulate state across iterations, and call `(final! ...)` to terminate. The 5-benchmark verification sweep confirmed recursive matches or beats terminal-mode quality at equal or lower token cost.

## Quick reference

```clojure
;; Recursive mode (default — emit-tree! returns to Phase 1; model decides next move)
{:rlm true}
{:rlm {:debug? true}}
{:rlm {:recursive? true}}            ;; explicit
{:rlm {:recursive? true :debug? true}}

;; Terminal mode (explicit opt-out — model designs ONE tree, Phase 2 runs, done)
{:rlm {:recursive? false}}
{:rlm {:recursive? false :debug? true}}
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
