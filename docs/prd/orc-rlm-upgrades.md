# PRD: Upstream ORC RLM upgrades — enable model-authored `:code` nodes inside emit-tree! trees

**Status:** Ready for upstream review/merge
**Author:** daryl@obney.ai (work performed during predict-rlm benchmark port)
**Parent:** `docs/prd/predict-rlm-benchmark-ports.md`
**Comparison report providing the proof point:** [`development/bench/predict-rlm-comparison/reports/01_image_analysis.md`](../../development/bench/predict-rlm-comparison/reports/01_image_analysis.md)

## Problem Statement

ORC's RLM (Repl Researcher) lets the model design behavior trees from a goal-only instruction. Today the model can emit `:llm`, `:sequence`, `:map-each`, `:chunk-document`, `:aggregate`, `:parallel`, and `:final` nodes. It **cannot** practically emit `:code` nodes inside its trees, despite the framework nominally supporting them.

Three classes of friction make `:code` nodes unusable in tree-emit mode:

1. **The SCI sandbox rejects model-written destructuring `(fn [{:keys [...]}] ...)` forms.** Errors with `Could not resolve symbol: clojure.lang.PersistentArrayMap/createAsIfByAssoc`. Root cause: the RLM `safe-clojure-core` whitelist wrongly includes the special forms `let if fn cond when do def`, which overrides SCI's native handling with `clojure.core` macro implementations whose expansion references unresolvable JVM internals.

2. **The DSL refuses inline-fn `:fn` values.** PR02-era `rlm-dsl->orc-dsl` only accepts `:fn` as a fully-qualified-symbol string. Inline functions (what the model naturally writes) throw at translation time.

3. **The event store can't serialize SCI fn values.** When the model's emitted tree contains an inline-fn `:fn`, Fressian serialization fails on the SCI fn object. The tick read-model can't project; downstream processors stall; the runner hits its 600s timeout.

In addition, three pre-existing latent issues surfaced during this work and should be fixed alongside:

4. Phase-1 sub-LLM image routing drops `:field-type :image` from the blackboard schema, sending vision content as base64 text (~480K tokens vs ~1K real image-tile billing).
5. Phase-2 child-sheet schema inference loses `:field-type` propagation across the parent→child sheet handoff.
6. `extract-all-keys` in `rlm_tree_executor.clj` uses `(take-while #(not= :fn %) ...)` which produces empty output when `:fn` is the first keyword arg (PR02's canonical form puts `:fn` first; existing `:chunk-document`/`:aggregate` paths put it last). Result: `:writes` keys aren't auto-declared in the child sheet → write-validation fails → 600s retry storm.

The net effect: the model can design beautiful tree-with-`:code` workflows (verified empirically — see image_analysis dream-scenario run) but executing them requires the upgrades documented below.

## Solution

Six framework changes in `components/orc-service/src/ai/obney/orc/orc_service/core/`. All are surgical, none break existing benchmarks (verified: existing 5-task generalization suite still passes; contract_comparison_validated still passes). Each is small enough to land as its own PR or all together. All but #4 and #5 are pure-framework changes (no behavior change visible to RLM consumers who don't use inline `:code`).

### Upgrade U1 — Strip core special forms from RLM `safe-clojure-core` whitelist (CRITICAL)

**File:** `rlm_sandbox.clj`, around the `safe-clojure-core` definition (~line 310-335).

**Change:** Remove `let if cond when do fn def` from the symbol whitelist. These are special forms / core macros that SCI handles natively. Selecting them from `(ns-publics 'clojure.core)` overrides SCI's evaluator with Clojure's macro implementations whose expansion emits JVM-internal references SCI cannot resolve.

**Impact:** Unblocks ANY model-written `(fn [{:keys [...]}] ...)` destructuring form — both in `:code` nodes inside trees AND in Phase-1 sandbox code. This was the original blocker preventing the dream scenario. No regression: SCI's native `let/if/fn/cond/when/do/def` are functionally equivalent for sandbox use, and the base sandbox (`sci_sandbox.clj`) has never had these in its whitelist.

**Why it shipped this way originally:** the whitelist was authored before someone tried to write a destructuring fn in Phase-1 sandbox code. The bug is silent until that pattern is exercised.

### Upgrade U2 — DSL accepts inline-fn `:code` `:fn` values (NEW CAPABILITY)

**File:** `rlm_dsl.clj`, the `:code` branch of `rlm-dsl->orc-dsl` (~line 133-152).

**Change:** Accept both forms for `:fn`:
- `(a)` non-empty string — qualified-symbol reference (pre-existing path, retained)
- `(b)` Clojure function value — inline `(fn [{:keys [inputs]}] ...)` written by the model

Both pass through unchanged to the downstream tree-executor.

**Impact:** Unlocks the model-authored `:code` pattern. Without this, the DSL throws `:code node missing required :fn (fully-qualified function symbol as string)` when the model emits an inline fn. With it, both pre-built fns AND model-authored fns work.

### Upgrade U3 — Tree executor's `:code` compilation discriminates string vs fn (CAPABILITY ENABLER)

**File:** `rlm_tree_executor.clj`, the `:code` branch of `compile-tree-node` (~line 345-385).

**Change:**
- When `:fn` is a string: pass through directly (the leaf executor's `resolve-fn` will resolve it via `ns-resolve` at run time)
- When `:fn` is a function value: register it in the ephemeral fn registry and store the ephemeral key on the leaf

Without this discrimination, the existing code path treats both as ephemeral fns — which works for inline-fn from `:chunk-document` / `:aggregate` (already inline fns) but turns string symbols into double-wrapped strings that fail with `String cannot be cast to IFn` at execution time.

**Impact:** Both string-symbol references (PR02 style) and inline-fn `:code` (model-authored) work cleanly.

### Upgrade U4 — `extract-all-keys` is position-independent for `:fn` (BUG FIX)

**File:** `rlm_tree_executor.clj`, the `:code` branch of `extract-all-keys` (~line 198-237).

**Change:** Replace `(take-while #(not= :fn %) ...)` with `(apply hash-map (rest tree))`. The existing form assumed `:fn` was always the LAST keyword arg (as `:chunk-document` / `:aggregate` canonical forms emit it). PR02's emit-tree! `:code` translation emits `:fn` first, so `take-while` stops immediately and `:writes` keys are never extracted to auto-declare in the child sheet. The auto-declaration is what enables blackboard-write validation; without it, the leaf's `:writes` aren't recognized and write-commit fails silently in a retry storm.

**Impact:** `:code` nodes are recognized by the child-sheet key-declaration loop regardless of `:fn` position. Eliminates 600s retry-storm timeouts for trees containing `:code` nodes the model emitted.

### Upgrade U5 — Phase-1 sub-LLM image routing (BUG FIX)

**File:** `rlm_sandbox.clj`, `execute-llm-primitive` (~line 115-160).

**Change:** When building the dscloj module from `:reads`, look up each key's blackboard schema and propagate `:field-type` (e.g. `:image`) to the module input field. Without this, image-typed blackboard values are sent to OpenRouter as inline text (character-counted ~480K tokens for a typical screenshot) rather than as multimodal `image_url` content blocks (proper ~1K-tile billing).

**Impact:** Vision works correctly through the Phase-1 sandbox `(llm ...)` primitive. Mirrors what Phase-2 leaf nodes already do via `executor.clj/build-field`. ~5 LOC.

### Upgrade U6 — Phase-2 child-sheet schema preservation (BUG FIX)

**File:** `executor.clj` (Phase-2 dispatch in `execute-repl-researcher-rlm`) + `rlm_tree_executor.clj` (`execute-tree`).

**Change:**
- `executor.clj`: pass `:blackboard-schemas {k schema}` alongside the `:blackboard {k value}` map when invoking `tree-executor/execute-tree`.
- `rlm_tree_executor.clj`: in `execute-tree`'s declare-keys loop, prefer the passed `:blackboard-schemas` schema over the value-type-inferred default.

Without this, the child sheet re-infers schema from value type (`(string? v)` → `:string`), losing `:field-type :image` from the parent's blackboard. Phase-2 `:llm` leaf nodes that read image keys would then send the data URI as text content (the same vision-routing bug as U5, but on the Phase-2 path).

**Impact:** Image-typed inputs route correctly through Phase-2 emit-tree! `:llm` nodes. Without this, vision benchmarks like image_analysis silently fail to actually use vision in tree mode.

### Upgrade U7 — Phase-2 `:code` output reconciliation (CAPABILITY ENABLER + BUG FIX)

**File:** `executor.clj`, `execute-code` (~line 481-560).

**Change:** Reconcile the function's return value with the node's declared `:writes`:
- Map result with at least one declared write key → `select-keys` to declared keys
- Non-map result with exactly one declared write → wrap as `{(first writes) result}` (auto-wrap)
- Map result with no matching keys + single declared write → wrap whole map under that key
- Otherwise fail with a clear error message (don't hang silently)

**Impact:** Fixes the previously-silent "function returned a map with unexpected keys" hang where `:sheet/complete-node-execution` was rejected without an error event, causing 600s retry storms (e.g. when a model uses `clojure.core/identity` as a placeholder and the runtime tries to write `:inputs`, `:event-store`, etc. as blackboard keys). Mirrors the auto-wrap behavior of the Phase-1 sandbox `(code ...)` primitive.

### Upgrade U8 — Sanitize SCI-fn values in stored events (CAPABILITY ENABLER)

**File:** `todo_processors.clj`, `execute-repl-researcher-node` (~line 460-500 — both the `:rlm/tree-generated` event body AND the repl-researcher's `:writes` in the `:sheet/complete-node-execution` command).

**Change:** Walk `generated-tree-raw` with `clojure.walk/postwalk` and replace `:fn` values that are functions with the placeholder string `"<inline-fn>"`. The actual function lives in the ephemeral fn registry during Phase-2 execution; the placeholder in events keeps the read-model projectable.

**Impact:** Event-store-serialized tree representations no longer contain unserializable SCI fn objects. Fixes the silent Fressian serialization failure that caused 600s timeouts in `complete_tree_tick` and `handle_child_completion` whenever a tree contained an inline `:code` fn.

### Upgrade U11 — Schema-driven structured output for `:llm` writes (CAPABILITY ENABLER)

**Files:** `rlm_tree_executor.clj` (`extract-key-schemas` helper + declare-keys integration), `executor.clj` (framework prompt teaches `:output-schemas`).

**Change:** Let model-emitted `:llm` nodes declare `:output-schemas {<write-key> <Malli-schema>}` on the node itself. The framework:
1. Walks the canonical tree at Phase-2 dispatch via `extract-key-schemas`
2. When auto-declaring child-sheet blackboard keys, uses the model-declared schema instead of `:any`
3. `build-module` looks up the blackboard schema for `:writes` and passes it as the dscloj output `:spec`
4. dscloj's existing `complex-spec?` mechanism kicks in: when the spec is a structured Malli type (`[:vector ...] [:map ...] [:map-of ...] [:tuple ...]` etc.), dscloj instructs the LLM to "respond with valid JSON" AND parses the response back into Clojure data via `parse-json-value`.

The end-to-end result: when the model declares `:output-schemas {:targets [:vector [:map ...]]}` on an `:llm` node, the downstream `:code` node receives `:targets` as a parsed Clojure vector of maps — NOT as a raw JSON-text string.

**Why this is critical:** without this, ANY tree that chains LLM → code-transform fails silently. The LLM produces structured-looking text; the framework stores it as a string; downstream code expects parsed data and either crashes (Character/Associative cast) or silently drops everything (empty after shape-check).

**Surfaced by:** document_redaction port. The model designed the right tree shape (multi-pass + reconcile + apply) but every run produced `:total-redactions = 0` because `:targets` were JSON strings, not vectors. After U11 landed, the same tree produced 92 redactions (vs predict-rlm's 89). See `development/bench/predict-rlm-comparison/reports/02_document_redaction.md` for the dream-scenario proof point.

**Why it's framework-native, not a hack:** ORC already declares Malli schemas on blackboard keys for type contracts. dscloj already detects complex schemas and handles JSON serialization. The gap was just the bridge — `extract-key-schemas` plumbing model-declared types from `:llm` nodes into the child-sheet's declare-key calls. The schema → JSON → parse cycle was all pre-existing dscloj capability, just unused because intermediate keys were always declared `:any`.

**Acceptance criteria:**
- [ ] `:llm` nodes can declare `:output-schemas {<key> <schema>}` and the declared schema propagates to the child-sheet's blackboard.
- [ ] Unit test: `extract-key-schemas` collects schemas from `:llm` nodes (including nested under `:sequence`, `:parallel`, `:map-each`).
- [ ] Live e2e: document_redaction with model-declared `:output-schemas` produces a non-empty `:total-redactions` matching predict-rlm within ~5%.
- [ ] No regression on text-output tasks (image_analysis still works; existing 5-benchmark suite still passes).
- [ ] Framework prompt advertises `:output-schemas` so the model knows to declare it.

### (Deferred for later) Upgrade U9 — Framework prompt update for `:code` inline-fn awareness

**File:** `executor.clj`, `build-rlm-code-generation-module` (~line 1240-1320 — the `## Available Primitives` and `### Use :code nodes for deterministic transforms` sections).

**Change:** Update the RLM system prompt to document the two ways to provide a `:code` node's function:
- (a) qualified-symbol string reference to a pre-built fn
- (b) inline `(fn [{:keys [inputs]}] {...write-key value...})` written by the model itself

Include a concrete example showing letter-counting with `re-seq` + `frequencies`.

**Impact:** Without this prompt update, the model defaults to using LLM calls for deterministic transforms even when the inline-fn affordance is available. The prompt change is what causes the model to reach for `:code` + Clojure rather than `:llm` + JSON parsing.

### (Deferred for later) Upgrade U10 — `:rlm/researcher-iterations` event for uniform iteration capture

**Files:** `todo_processors.clj` (emit the event always when iterations exist), `interface/schemas.clj` (register the event type).

**Change:** Emit a `:rlm/researcher-iterations` event after `execute-repl-researcher-rlm` returns, regardless of whether the model called `emit-tree!` or `final!` directly. Body: `{:execution-id :iterations :iteration-count :emitted-at}`.

**Impact:** Downstream observers (benchmark runners, evaluation tooling) get a uniform iteration-capture surface across both direct-execution and tree-emission modes. Without this, iterations are captured only when a tree is emitted (via the existing `:rlm/tree-generated` event), and direct-execution runs lose their iteration history at the observability layer.

## Priority and Sequencing

**Critical (must land — fixes broken framework):**

- **U1** — RLM `safe-clojure-core` whitelist correction. Any consumer's Phase-1 sandbox code using destructuring is broken today. Should be a same-day fix.
- **U5** — Phase-1 sub-LLM image routing. Any consumer's RLM doing vision in direct execution mode is broken today (silently sending base64 as text).
- **U6** — Phase-2 child-sheet schema preservation. Same vision-routing failure mode on the Phase-2 path.
- **U7** — Phase-2 `:code` output reconciliation. Eliminates a silent retry-storm failure mode for `:code` nodes returning unexpected map shapes.
- **U4** — `extract-all-keys` `:fn`-position independence. Required for `:code` nodes to be auto-declared in child sheets.

**Capability enablers (unlock model-authored `:code` nodes in trees):**

- **U2** — DSL accepts inline-fn `:fn`. Prerequisite for the dream scenario.
- **U3** — Tree executor discriminates string vs fn `:fn`. Prerequisite for both string-symbol and inline-fn paths to coexist.
- **U8** — SCI-fn sanitization in stored events. Required for inline-fn trees to actually execute end-to-end (events must be Fressian-serializable).
- **U11** — Schema-driven structured output for `:llm` writes (`:output-schemas` declaration). **Critical for any tree that chains LLM-structured-data → code-transform.** Without this, redaction-style workflows fail silently because LLM outputs arrive at downstream `:code` nodes as JSON strings instead of parsed data.

**Deferred for later (not blocking; revisit when ready):**

- **U9** — Framework prompt updates. Without this, the inline-fn capability is unreachable in practice because the model doesn't know it exists. Lower urgency upstream because consumers who need it can add their own prompt addition; the framework still SUPPORTS the pattern via U1-U8.
- **U10** — Researcher-iterations event. Observability improvement; non-breaking. Useful for benchmark/eval tooling but not framework-correctness.

**Suggested PR grouping for upstream:**

- PR-A "RLM framework correctness fixes": U1 + U4 + U5 + U6 + U7. All bug fixes; lands cleanly with no behavior change for working consumers and unblocks broken paths. Tests: existing test suite + a new unit test per fix.
- PR-B "Model-authored `:code` nodes in emit-tree!": U2 + U3 + U8 + U11. The new-capability bundle. Lands after PR-A. Tests: DSL inline-fn unit test, Phase-2 inline-fn integration test, schema-driven structured output unit test, image_analysis dream-scenario live run AND document_redaction dream-scenario live run as proof points.
- PR-C (deferred) "RLM prompt updates for inline-fn discoverability": U9.
- PR-D (deferred) "Uniform RLM iteration observability": U10.

## Acceptance criteria for "all upgrades landed"

- [ ] All 10 upgrades implemented and unit-tested where applicable.
- [ ] Existing 5-task generalization suite still passes with zero regression.
- [ ] image_analysis dream-scenario run reproduces from a clean checkout: model emits a tree with at least one inline `:code` node, the tree runs end-to-end, output matches predict-rlm within ±2 letters per character, total tokens ≤ 10K, wall clock ≤ 30s.
- [ ] No silent retry storms on any test; all 600s timeouts in the predict-rlm comparison work are eliminated.
- [ ] Vision routing works through both Phase-1 sandbox `(llm ...)` AND Phase-2 emit-tree! `:llm` paths.
- [ ] Documentation updated: `components/orc-service/README.md` (or equivalent) notes that `:code` nodes can take either a qualified-symbol-string `:fn` or an inline function value.

## What's NOT in this PRD

- **Multi-tree iteration** (model emits tree A, sees result, emits follow-up tree B). Captured as future work in `docs/issues/predict-rlm/PR-Multi-Tree-iteration.md`.
- **Dual-model support** (different LMs for Phase-1 vs Phase-2 leaves). Captured as comparison-quality enhancement in `docs/issues/predict-rlm/PR-Dual-Model-runner-support.md` and was implemented in our `feature/predict-rlm-benchmarks` branch. Could be ported upstream but is not strictly framework-correctness.
- **Per-task `:available-code-nodes` catalog mechanism**. PR03 from the predict-rlm comparison work; lets benchmarks advertise pre-built fns to the model. Useful but orthogonal to model-authored `:code`.

## Empirical proof point

The image_analysis dream-scenario run with all 10 upgrades in place ([`image-analysis_2026-05-20_150618.edn`](../../development/bench/predict-rlm-comparison/results/image-analysis_2026-05-20_150618.edn)):

- gpt-5.4 emitted a 4-stage tree (`[:sequence :llm :llm :llm :code :final]`) with an inline `(fn [{:keys [inputs]}] (let [letters (re-seq #"[A-Za-z]" ...)] {:answer (frequencies ...)}))` :code node
- Status `:success`, 26.9s, 9,560 tokens
- 22 of 24 letter counts match predict-rlm's published numbers EXACTLY
- predict-rlm uses 26,547 tokens for the same task with the same models
- See [`development/bench/predict-rlm-comparison/reports/01_image_analysis.md`](../../development/bench/predict-rlm-comparison/reports/01_image_analysis.md) for the headline analysis
- See [`development/bench/predict-rlm-comparison/reports/01_image_analysis_deep_dive.md`](../../development/bench/predict-rlm-comparison/reports/01_image_analysis_deep_dive.md) for the development journey, including all 10 framework issues surfaced, in chronological order with EDN-file references for each milestone.

## Files touched (reference)

| File | Upgrades |
|---|---|
| `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_sandbox.clj` | U1, U5 |
| `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_dsl.clj` | U2 |
| `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_tree_executor.clj` | U3, U4, U6 (partial) |
| `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` | U6 (partial), U7, U9 |
| `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj` | U8, U10 (partial) |
| `components/orc-service/src/ai/obney/orc/orc_service/interface/schemas.clj` | U10 (partial — register event) |
| `components/orc-service/test/ai/obney/orc/orc_service/rlm_dsl_test.clj` | Unit tests for U2, U3, U9, plus the inline-fn tests added during this work |
