# C-1: Principle storage + retrieval + injection (hand-authored seed)

> **Status: SHIPPED** — live-verified end-to-end on `feature/core-orc-upgrades`. The 3-way comparison (control / principle-injected / wrong-task-class-id) passed: the model demonstrably emits a different tree when the matching principle is injected, and behaves at baseline when the principle's task-class UUID is mismatched.

## Parent

PRD: [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md)

## What to build

Wire the existing ontology storage + retrieval infrastructure into the RLM tree-design loop so that **hand-authored principles** from prior task-class work surface in the model's system prompt at tree-design time. End-to-end, this slice ships:

1. **Two new node-config fields under `:rlm`** on the repl-researcher node — `:task-class-id` (UUID) and `:principles-fn` (function). Both optional; legacy callers with `:rlm true` are unaffected.
2. **A new injection function** in the executor — `build-rlm-principles-section` — pure, takes a node + retrieval result, returns either a markdown prompt block or nil. Runs parallel to the existing `build-ontology-examples-section`.
3. **A default `:principles-fn` implementation** the executor synthesizes when the user doesn't override — queries `ontology/get-tree-profile` for the configured `:task-class-id`, concats `:strengths` + `:weaknesses` + `:domain-knowledge`.
4. **Hand-authored seed principles** (5-10 entries) addressing real benchmark failures. Each one is a single `:ontology/record-tree-strength` or `:ontology/record-tree-weakness` command using existing schema. RLM-specifics live in the domain-agnostic fields:
   - `:domain-type` = `"rlm-tree-design"`
   - `:context-conditions` = when the principle applies (task class, input shape, observed symptom)
   - `:action-taken` = the good pattern (tree-DSL snippet + named pattern URI)
   - `:expected-outcome` = what success looks like
   - `:evidence-trace-ids` = tick-ids of supporting failure/success runs
5. **Stable task-class UUIDs** assigned by hand for the existing benchmark task classes (doc-extraction, risk-analysis, contract-comparison, legal-issue-detection, document-analysis). Stored as named constants in a Clojure namespace for reuse across seed + tests.
6. **Documentation** in RLM-GUIDE.md — new node-config fields, injection-section format, hand-authoring workflow.
7. **Live-verify 3-way comparison** proving the model demonstrably acts on injected principles (not just that the pipe works).

### Principle encoding (decision-rich shape — derives from PRD Q4)

```clojure
;; Strength principle — "do this":
{:command/name :ontology/record-tree-strength
 :tree-id <task-class-uuid>
 :pattern-uri "success:BoundedMapEach"
 :confidence 1.0                          ; hand-authored = full
 :domain-type "rlm-tree-design"
 :context-conditions {:task-class :doc-extraction
                      :input-shape :large-chunked-text
                      :symptom :rate-limit-exhausted}
 :action-taken {:tree-pattern :map-each-with-concurrency-3
                :snippet "[:map-each {... :max-concurrency 3} [:llm {...}]]"}
 :expected-outcome "Avoid sub-LLM rate-limit errors on chunked extraction"
 :evidence-trace-ids [<tick-id-of-failure>]}

;; Weakness principle — "avoid this":
;; Same shape but with :failure-context and :attempted-action instead
;; (per the existing record-tree-weakness command schema).
```

### Injection format (rendered in the system prompt)

```
## Principles for this task class

### When extracting entities from chunked documents (3 evidence runs)
**Good pattern**: bound :max-concurrency on :map-each
```clojure
[:map-each {... :max-concurrency 3} [:llm {...}]]
```
**Why**: Sub-LLM rate limits exhaust on unbounded concurrency

### Avoid: sequential summarization of long legal docs (2 evidence runs)
**Prefer**: parallel chunk-summarize → aggregate
```clojure
[:sequence [:chunk-document {...}] [:parallel ...] [:aggregate ...]]
```
**Why**: Sequential pipeline produces ~4x wall-time vs parallel for >10 chunks
```

Strengths use "When X" headers; weaknesses use "Avoid: X" headers. Section header (`## Principles for this task class`) is omitted entirely when no principles match.

### Retrieval hook contract

Symmetrical with the existing `:examples-fn`:

```clojure
;; User-supplied or default:
:principles-fn (fn [context] [...principle-maps])

;; Default (synthesized by executor when not overridden):
:principles-fn (fn [_]
                 (let [profile (ontology/get-tree-profile ctx task-class-id)]
                   (concat (:strengths profile)
                           (:weaknesses profile)
                           (:domain-knowledge profile))))
```

User can override with custom retrieval (top-N by confidence, semantic similarity, custom filtering).

## Acceptance criteria

### Schema + wiring

- [ ] Repl-researcher node config accepts `:task-class-id` (UUID) and `:principles-fn` (function) under `:rlm`. Schema validates.
- [ ] Both fields are optional — when both are absent, the prompt is identical to today (no regression for legacy callers).
- [ ] When `:task-class-id` is set but `:principles-fn` is not, the executor synthesizes a default `:principles-fn` querying `ontology/get-tree-profile`.
- [ ] When `:principles-fn` is set explicitly, the user's function is used as-is.

### Injection function

- [ ] `build-rlm-principles-section` is a pure function: takes (node, retrieval result) → returns markdown string OR nil.
- [ ] When the retrieval result is empty (or nil), the function returns nil (caller omits the section entirely; no empty `## Principles for this task class` header in the prompt).
- [ ] When the retrieval result has N entries, the rendered section contains N principle blocks.
- [ ] Strengths render with "When X" headers; weaknesses render with "Avoid: X" headers; both visually distinguishable.
- [ ] Each rendered block contains: header text from `:context-conditions`, "Good pattern" or "Prefer/Avoid" line, the `:action-taken.snippet` as a Clojure code block, a "Why" line from `:expected-outcome`, and an "(N evidence runs)" suffix from `:evidence-trace-ids` count.

### Storage / reuse

- [ ] Existing `:ontology/record-tree-strength` and `:ontology/record-tree-weakness` command surfaces unchanged. NO new command, NO new event type, NO new schema in the ontology component.
- [ ] At least 1 hand-authored principle exists in the seed (committed in a Clojure ns under `development/src/`), grounded in a real benchmark failure with `:evidence-trace-ids` populated.
- [ ] All hand-authored principles use the `:domain-type` value `"rlm-tree-design"`.
- [ ] Task-class UUIDs are defined as named constants in the seed namespace (one per benchmark task class — at minimum: doc-extraction, risk-analysis, contract-comparison, legal-issue-detection, document-analysis).

### Tests

- [ ] Pure-function unit tests for `build-rlm-principles-section` covering:
  - nil-when-empty: empty retrieval result → returns nil
  - single-strength render: one strength entry → expected markdown block
  - single-weakness render: one weakness entry → "Avoid:" header
  - multi-entry render: mixed strengths + weaknesses produce all blocks in order
  - count formatting: "(N evidence runs)" suffix reflects `:evidence-trace-ids` length
- [ ] Default `:principles-fn` impl tested with an in-memory event store seeded with known principle events; verifies the right principles come back for a given `:task-class-id`.
- [ ] Integration test: `execute-repl-researcher-rlm` with `:principles-fn` set produces a system prompt containing the rendered Principles section (asserted via prompt-string inspection, parallel to how `recursive_rlm_test.clj` asserts on prompt content).
- [ ] Regression: all existing RLM-related tests pass unchanged (recursive_rlm_*, recursive_rlm_drill_down_*, rlm_*, rlm_dsl_*, rlm_mode_*, rlm_tree_executor_*).

### HITL audit checkpoint (REQUIRED)

After the mechanical implementation lands, before the live-verify is declared successful, a human must:

- [ ] Capture the formatted prompt string from a real run (Run Y below).
- [ ] Manually inspect and confirm:
  - The principle reads like a *rule* the model can apply, not entity-specific advice.
  - The "Good pattern" snippet is valid tree-DSL.
  - The "When ..." guard is concrete enough to match-or-not-match new tasks.
  - The format doesn't overwhelm the prompt (compare token cost to baseline).
- [ ] If any inspection fails, iterate on the format (or principle text) BEFORE re-running the live-verify. This is the "infrastructure existence is not quality" gate.

### Live-verify 3-way comparison (MANDATORY)

Real LLM (gemini-2.5-flash for cost), real ontology event store, real benchmark task. All three runs must hold:

- [ ] **Run X (control, no `:principles-fn` set):** chosen benchmark failure reproduces deterministically — ≥2 of 2 attempts produce the same failure mode. (Confirms the baseline failure is real, not flaky.)
- [ ] **Run Y (principle injected via matching `:task-class-id`):** EITHER the failure mode is resolved, OR the model's iter-2+ generated tree visibly applies the principle pattern (verified by reading the iteration history in the saved EDN).
- [ ] **Run Z (principle injected, deliberately wrong `:task-class-id`):** behavior matches X, principle NOT applied. (Confirms task-class matching is load-bearing — the principle isn't silently always-on.)
- [ ] Live-verify output (3 EDN run-records, one per run) saved to `development/bench/generalization-results/` (gitignored), with timestamps for inspection.

If Run Y fails to differ from Run X, root-cause whether (a) the format is wrong, (b) the principle text is wrong, (c) retrieval isn't matching, (d) the principle was never actually relevant — iterate on the appropriate layer. Don't ship "almost works."

### Documentation

- [ ] RLM-GUIDE.md updated with:
  - New node-config fields (`:task-class-id`, `:principles-fn`) documented.
  - Default `:principles-fn` impl explained.
  - Example of the rendered "Principles for this task class" prompt section.
  - Hand-authoring workflow (which command to use, which fields carry RLM data).

## Critical project rules

1. **Trace bugs to root cause** — if a principle doesn't move model behavior in Run Y, root-cause through the layers; don't bypass with a tweaked test.
2. **Mocks for dev iteration only; live runs required before declaring done** — the 3-way comparison is non-negotiable.
3. **Infrastructure existence is not quality** — the HITL audit checkpoint exists precisely because writing events and getting them back is the easy part; producing principles that actually teach the model is the hard part.
4. **Never truncate model-authored output back to the model** — if a principle's "Good pattern" snippet is long, render it in full.

## Blocked by

None — can start immediately.

## Cross-references

- Parent PRD: [`docs/prd/category-c-self-improving-loop.md`](../../prd/category-c-self-improving-loop.md)
- Prior data layer (DONE): [`docs/prd/rlm-recursive-emit-tree.md`](../../prd/rlm-recursive-emit-tree.md) (R-1, R-2)
- Prior observability layer (DONE): [`docs/prd/phase2-observability-layer.md`](../../prd/phase2-observability-layer.md) (O01-O03)
- Prior resilience layer (DONE): [`docs/prd/category-d-resilience.md`](../../prd/category-d-resilience.md) (D-003, D-008)
- Existing storage surface: [`docs/PATTERN-RECORDING.md`](../../PATTERN-RECORDING.md)
- Feedback loop architecture: [`docs/FEEDBACK-LOOP.md`](../../FEEDBACK-LOOP.md)
- Future automation: [`docs/GEPA-INTEGRATION-PLAN.md`](../../GEPA-INTEGRATION-PLAN.md)
- Architectural target: [`docs/FUTURE-VISION.md`](../../FUTURE-VISION.md) Phase 4a + B.3
