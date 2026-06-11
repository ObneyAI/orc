# Recursive Mode Root-Cause Plan (LOCAL ONLY)

**Status:** investigation + plan, no code changes yet
**Branch:** `experiment/predict-rlm-recursive-mode`
**Companion:** [`recursive-mode-experiment.md`](recursive-mode-experiment.md) — the 2-of-5 experiment that surfaced the regression

## What the experiment showed (recap)

Recursive mode (`:rlm {:recursive? true}`) produced **degraded or empty outputs** on both benchmarks tested:

- **image_analysis recursive**: 16,363 tokens / 29s — `:answer` field shows A-Z all zeros (terminal: 22-of-24 letters correct)
- **document_redaction recursive**: 70,732 tokens / 15min — `:total-redactions nil` / 0 redactions (terminal: 92 redactions in 29s)

In document_redaction, the model ran 5 Phase-1 iterations executing direct `(llm ...)` / `(code ...)` calls but **never emitted a single tree**, then returned `:status :success` with empty outputs.

## Root-cause findings (from reading the framework)

### Finding 1 — `final!` validates key PRESENCE, not value meaningfulness

**Where:** `components/orc-service/src/ai/obney/orc/orc_service/core/rlm_sandbox.clj` lines 237-260 (`validate-final!`)

`validate-final!` throws if:
- Output contains extra keys not in `:writes`
- Output is missing keys from `:writes`

It does NOT throw if the values are nil / empty-vector / empty-string. So a model can call:

```clojure
(final! {:total-redactions nil
         :targets-applied []
         :targets-missing []
         :redacted-text-per-page []
         :page-summaries []})
```

…and the framework accepts it as `:success`. This is what happened in the document_redaction recursive run — the model gave up and called `final!` with all-empty values to satisfy the schema, and the framework cheerfully returned `:success`.

**Hypothesis #2 from the experiment was PARTIALLY RIGHT** — validation isn't silent (it does throw), but the throw condition is too lenient. A real "have we produced meaningful work?" check is missing.

### Finding 2 — Recursive-mode prompt positions `emit-tree!` as one option among many

**Where:** `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` lines 1446-1490 (`build-rlm-code-generation-module`)

Current text injected into the prompt when `:recursive?` is true:

> ## Recursive emit-tree! (this mode)
>
> When you call `(emit-tree! ...)`, the tree executes and then control returns to you for another iteration. The tree's outputs are merged into your variables (use `(get-var :summary)` etc.), and a summary entry is appended to `:tree-results`. The loop ends only when you call `(final! {...})` or you exceed `:max-iterations`.

This phrasing — "**When** you call `(emit-tree! ...)`" — frames emit-tree! as one option the model **might** choose. The terminal-mode prompt is tighter because emitting the tree IS the answer-producing action.

Then later in the same prompt:

> ## Output Contract
> You MUST call `(final! {...})` with keys: \[`:total-redactions` `:targets-applied` ...]

So the model sees: "you MUST call final!" + "you MAY emit trees". It then short-circuits — calls `final!` with empty values to satisfy the contract without doing the harder work of designing and emitting a tree.

**Hypothesis #1 from the experiment was RIGHT** — recursive-mode prompt under-emphasizes `emit-tree!` as the work-doing primitive.

### Finding 3 — Iteration history IS exposed to the model (hypothesis #3 was WRONG)

**Where:** `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj` line 949 (`build-iteration-history`)

The model sees every prior iteration's code, result, stdout, error, and `vars-created` — verbatim, no truncation. So hypothesis #3 (no history visibility) was wrong.

What's MISSING from iteration history, though, is **summary information about emitted trees**. The model sees its own code but not (e.g.) "Iteration 2: you emitted a tree; result was :partial with 14 of 22 chunks succeeded". This signal lives in `sandbox-vars[:tree-results]` which the model has to manually inspect via `(get-var :tree-results)`. It might not occur to the model to look there.

(Lower priority than findings 1 + 2; this is a UX improvement, not a regression cause.)

## Proposed fixes (TDD, in dependency order)

### Fix 1 — Make recursive-mode prompt lead with `emit-tree!` as the primary work primitive

**File:** `executor.clj` `build-rlm-code-generation-module` around line 1446

Reframe the recursive-mode section to lead with intent ("emit-tree! is how you do work") and treat the loop semantics as a **consequence** of emit-tree!, not the headline. Something like:

> ## Recursive mode (this mode)
>
> In this mode, `emit-tree!` is how you do work — design a tree for one piece of the task, run it, see the result, decide what to do next. The pattern is repeated emit-tree! → inspect → decide, until you have everything you need to call `(final! ...)`.
>
> **Each iteration, prefer to either**:
> 1. Emit a tree via `(emit-tree! ...)` to make progress on the task, OR
> 2. Call `(final! {...})` when the work is done.
>
> Direct `(llm ...)` / `(code ...)` calls in Phase 1 are for narrow inspection/decision flows — they should not be your main work loop. If you find yourself iterating `(llm ...)` calls without emitting trees, switch to emit-tree!.
>
> **After each `(emit-tree! ...)`** the tree's outputs are merged into your variables (`(get-var :key)`), a summary entry is appended to `:tree-results`, and control returns to you. See `(get-var :tree-results)` for what happened.

This makes emit-tree! the strong default + flags the "iterating direct LLM calls" failure mode the model fell into.

**Test:** rerun document_redaction recursive after this fix. Expect at least 1 emit-tree! call across iterations.

### Fix 2 — `final!` rejects all-empty outputs

**File:** `rlm_sandbox.clj` `validate-final!` around line 237

Add a second validation pass: if EVERY declared write maps to a nil / empty-string / empty-collection value, throw with a clear error like:

```
(ex-info "final! called with all empty values. The model should produce meaningful work via emit-tree! before terminating."
         {:empty-keys [:total-redactions :targets-applied ...]
          :declared-writes ...})
```

This still allows legitimate partial outputs (e.g. a redaction task where the document genuinely has no PII — but that's vanishingly rare and worth the error message).

**Less aggressive alternative:** schema-driven — for output-schemas declaring `[:vector ...]` with non-zero min-count, require count ≥ 1; for `[:string]` with `:min` constraint, require length ≥ min. This is more nuanced but requires :output-schemas to be set (which the predict-rlm tasks do).

**Test:** unit test on `validate-final!` covering empty-vector, nil, empty-string cases.

### Fix 3 — Add iteration-result-summary to history

**File:** `executor.clj` `build-iteration-history` around line 949

For recursive iterations that emitted a tree, append a one-line summary at the top of that iteration's history entry:

> ### Iteration 2 (emitted 1 tree: status :partial, 14/22 chunks)
> Code: ...

This makes the "did I do useful work last iteration?" signal more salient. Currently the model sees its code but has to chase `(get-var :tree-results)` to see the outcome.

**Test:** rerun document_redaction recursive after this fix combined with #1 and #2. Expect the model to recognize "iteration N's tree got :success with 92 redactions" and call `(final! ...)` with the accumulated data.

## Verification protocol

After Fixes 1 + 2 are in, rerun the 5 benchmarks in recursive mode and compare against terminal-mode headlines:

| Benchmark | Terminal headline | Recursive target |
|---|---|---|
| image_analysis | 9,560 tokens / 27s / 22-of-24 letters | ≤ +50% tokens, same/better letter recall |
| document_redaction | 92 redactions / 29s / 52,120 tokens | ≥ 80 redactions, ≤ 90s, ≤ +50% tokens |
| invoice_processing | 16,573 tokens / 28s / 11-of-12 line items | matching extraction quality |
| contract_comparison | 94,258 tokens / 50s / Domestic Content + 2 findings | matching key-differences |
| document_analysis | 614K tokens / 3.7min / 19 dates + 13 entities | matching extraction at ≤ +50% tokens (vs terminal) |

**Pass condition:** all 5 produce meaningful outputs (non-empty key writes) and quality is within reasonable variance of terminal-mode. Token / wall-clock overhead is acceptable so long as outputs are correct.

**Fail condition:** any benchmark returns empty outputs OR significantly degraded quality (e.g. < half the expected redaction count). Diagnose before continuing.

Estimated re-run cost: ~$3-7 for the full 5-benchmark sweep (per the experiment recap).

## What this does NOT do

- Does not ship to main yet — the framework changes live on a fix branch + the experiment branch carries the recursive runners
- Does not modify the task instructions — the goal is the framework prompt change carries the load, not per-task prompt tweaks
- Does not change `:max-iterations` default — 5 is sufficient when emit-tree! is preferred (each emit-tree! does real work)

## Branch / merge plan

1. New branch `feature/rlm-recursive-mode-fixes` off latest `origin/main` (`7fbfb54`)
2. Commit fixes 1, 2, 3 separately so each can be reverted independently if regression surfaces
3. Re-run all 5 benchmarks via the `run/recursive/<task>.clj` namespaces (preserved on `experiment/predict-rlm-recursive-mode`)
4. Compare against terminal-mode headlines (committed reference EDNs already in `results/`)
5. If pass: open PR with `experiment/predict-rlm-recursive-mode` → `feature/rlm-recursive-mode-fixes` consolidation, ship to main
6. If fail: iterate on the prompt / validation tuning; document failures in this deep-dive

The runner.clj `:recursive?` plumbing change is generally useful regardless of recursive-mode fix success, so it ships as part of the fix PR.
