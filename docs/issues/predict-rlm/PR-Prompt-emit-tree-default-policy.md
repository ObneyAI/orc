# PR-Prompt — RLM prompt policy update: `emit-tree!` as default, narrow exceptions

## Parent

`docs/prd/predict-rlm-benchmark-ports.md` (Solution item #5; documented in detail under "Pending (PR-Prompt)" in Implementation Decisions).

## What to build

Observed during PR07: the model writing image_analysis chose direct Phase-1 execution with **two chained sequential `(llm ...)` calls** (`extract-text` → `calculate-letter-frequency`) when it should clearly have used `emit-tree!` — the chained pattern indicates a `[:sequence [:llm ...] [:llm ...]]` tree. Worse, the counting step was an LLM call where a deterministic `:code` node would have been definitively correct (the query literally asks for programmatic letter counting).

The existing RLM prompt's `## When to Use emit-tree!` section is framed around "Large Data Processing" — the model categorized image_analysis as "just an image + query, not large data" and didn't trigger the recommendation.

This issue strengthens the prompt's emit-tree! policy:

- **`emit-tree!` is the default.** For ANY non-trivial workflow — anything involving multiple steps, parallelism, deterministic transforms alongside LLM calls, large inputs, or quality/verification requirements — emit a tree.
- **The narrow exceptions** (when `emit-tree!` is overkill): when the input preview shows the task is trivially small enough that a single `(llm ...)` call OR a single `:code` node would clearly suffice.
- **If the model finds itself writing 2+ sequential `(llm ...)` calls in Phase 1, that is a strong signal to use `emit-tree!`** with a `:sequence` of `:llm` nodes instead. The tree provides observability, per-node retry, deterministic-transform composition, and event-trace coverage that direct Phase-1 chaining does not.
- **For deterministic transforms** (counting, regex matching, deduplication, string replacement, file I/O), prefer `:code` with `{:fn "ns/sym"}` over `(llm ...)`. Letter-counting via LLM is hallucination-prone; a pure-Clojure function is definitively correct.

The change is text-only in `build-rlm-code-generation-module` in `components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj`. Add a new "## Default Mode: emit-tree!" section near the top of the primitives documentation, and update the existing "## When to Use emit-tree!" to reflect the policy shift.

## Acceptance criteria

- [ ] New "## Default Mode: emit-tree!" section in the RLM prompt explicitly states emit-tree! is the default for non-trivial work.
- [ ] Existing "## When to Use emit-tree!" is updated to remove the "Large Data Processing" framing and align with the new default-first policy.
- [ ] Prompt explicitly calls out the "2+ sequential `(llm ...)` calls" anti-pattern with a recommendation to use a `[:sequence [:llm ...] [:llm ...]]` tree instead.
- [ ] Prompt explicitly calls out the "use `:code` for deterministic transforms" guidance, citing letter-counting and regex as examples.
- [ ] Unit test: capture the rendered prompt for a sample researcher node and assert it contains the policy strings (string-presence assertion).
- [ ] Re-run `contract_comparison_validated` and the existing 5-benchmark suite (at least one each) with the new prompt; verify model behavior on those is at least as good as before (no regression — same tree-shape family).
- [ ] No regression on `rlm_dsl_test`, `rlm_mode_test`, or comparison `runner-test`.

## Blocked by

None — can start immediately. Independent of PR-Pre03 (different file, different concern).

## Note on adversarial-completeness

Per-benchmark task instructions (PR06b, PR08, PR10) will ADDITIONALLY include an "adversarial-completeness clause" that says "verify nothing was missed; if anything was, extract and include it." That's separate from this prompt-level policy and lives in each task definition's `:instruction` string, not in the framework prompt.
