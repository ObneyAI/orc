# ORC RLM Generalization — Results

**Test date:** 2026-05-19
**Model:** google/gemini-3-flash-preview (via OpenRouter)
**Engine:** ORC RLM (Repl Researcher with `emit-tree!`)

## Bottom Line

**ORC RLM generalizes.** Given goal-only instructions (no example trees, no algorithm hints), the model designs **structurally different behavior trees** for **structurally different tasks**:

- 4 distinct tree patterns across 4 tree-using tasks
- 1 task where the model correctly chose **no tree at all** (direct answer)
- **Zero hallucinations** across 37+ source-document spot-checks

The engine is not pattern-matching. It is designing trees from first principles based on task semantics.

## Headline Table

| Task | Doc Size | Tree Pattern (model's design) | Duration | Tokens | Quality |
|------|---------|-------------------------------|----------|--------|---------|
| [Legal Issue Detection](tasks/05-legal-issue-detection.md) | 7K (1 doc) | **No tree** — direct answer | 8.8s | 5,706 | ✓ verified |
| [Contract Comparison (+ adversarial validation)](tasks/04-contract-comparison-validated.md) | 105K (2 docs) | 4-stage with source cross-reference in final | 26.7s | 84,337 | ✓ verified, **found 2× more diffs than baseline** |
| [Contract Comparison](tasks/03-contract-comparison.md) | 105K (2 docs) | 5-stage pipeline, no chunking | 35.6s | 64,336 | ✓ verified |
| [Risk Analysis](tasks/02-risk-analysis.md) | 280K (1 doc) | chunk + map-each(5) + **single** synthesis | 64.8s | 170,336 | ✓ verified |
| [Document Analysis](tasks/01-document-analysis.md) | 280K (1 doc) | chunk + map-each(5) + **three** specialized synthesis | 126.3s | 212,066 | ✓ verified |

## The Five Distinct Tree Patterns (Actual Generated Code)

### Pattern A — Direct Answer (no tree)

Used for the 7K employment agreement. The model recognized decomposition would add overhead with no benefit. **The model bypassed `emit-tree!` entirely** and answered directly.

### Pattern B — Pipeline (no chunking)

Used for the 105K combined contract comparison ([Task 03](tasks/03-contract-comparison.md)). Both documents fit in context so no chunking; sequential stages build progressive analysis.

```clojure
[:sequence
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:document-survey]
        :instruction "Analyze the structure of both contracts..."}]
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:section-diffs]
        :instruction "Compare specific clauses side-by-side..."}]
 [:llm {:reads [:section-diffs] :writes [:major-changes]
        :instruction "Classify into MAJOR/MINOR/COSMETIC..."}]
 [:llm {:reads [:major-changes :section-diffs] :writes [:impact-analysis]
        :instruction "Analyze impact: Supplier vs OPA, risk shifts..."}]
 [:llm {:reads [:document-survey :section-diffs :major-changes :impact-analysis]
        :writes [:executive-summary]
        :instruction "Synthesize all findings into executive summary..."}]
 [:final {:keys [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]]
```

### Pattern C — Pipeline with Source Cross-Reference

Used for contract comparison + adversarial validation ([Task 04](tasks/04-contract-comparison-validated.md)). The final stage's `:reads` includes BOTH the prior output AND the source documents — implementing validation as data flow rather than as a separate validator node.

```clojure
[:sequence
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:document-survey]
        :instruction "Analyze structure of both contracts..."}]
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:raw_diffs]
        :instruction "Compare v2.0 and v3.1.1.
                      Be adversarial: only report differences explicitly visible in the text."}]
 [:llm {:reads [:raw_diffs :contract_v2 :contract_v3]  ;; ← Reads source AGAIN
        :writes [:section-diffs :major-changes :impact-analysis :executive-summary]
        :instruction "...ADVERSARIAL CLEARANCE: Remove any claim that is not
                      directly supported by the text of :contract_v2 or :contract_v3."}]
 [:final {:keys [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]]
```

### Pattern D — Parallel Chunked + Unified Synthesis

Used for risk analysis on 280K ([Task 02](tasks/02-risk-analysis.md)). One unified synthesis produces all four interrelated analytical outputs together.

```clojure
[:sequence
 [:chunk-document {:from :yyj_rfp, :size 12000, :into :chunks}]
 [:map-each {:from :chunks, :as :chunk, :into :extracted_data, :max-concurrency 5}
  [:llm {:reads [:chunk] :writes [:analysis]
         :instruction "Analyze this RFP section for: Obligations, Penalties, Risks
                       (HIGH/MEDIUM/LOW). Focus on concrete details."}]]
 [:aggregate {:from :extracted_data, :writes [:combined_analysis]}]
 [:llm {:reads [:combined_analysis]
        :writes [:obligations :penalties :risk-matrix :executive-summary]
        :instruction "Produce final structured report:
                      :obligations classified by category
                      :penalties with clause references
                      :risk-matrix HIGH/MEDIUM/LOW via 'Difficulty vs Severity'
                      :executive-summary strategic recommendations"}]
 [:final {:keys [:obligations :penalties :risk-matrix :executive-summary]}]]
```

### Pattern E — Parallel Chunked + Specialized Synthesis

Used for document analysis on 280K ([Task 01](tasks/01-document-analysis.md)). The model split synthesis into three specialized LLMs because the three outputs (summary, dates, entities) have structurally different shapes.

```clojure
[:sequence
 [:chunk-document {:from :yyj_rfp, :size 12000, :into :chunks}]
 [:map-each {:from :chunks, :as :chunk, :into :extracted_data, :max-concurrency 5}
  [:llm {:reads [:chunk] :writes [:segment_info]
         :instruction "Extract: summary points, key dates, entities"}]]
 [:aggregate {:from :extracted_data, :writes [:combined_info]}]
 [:sequence
  [:llm {:reads [:combined_info] :writes [:summary]
         :instruction "Synthesize Executive Summary..."}]
  [:llm {:reads [:combined_info] :writes [:key-dates]
         :instruction "List all Key Dates chronologically..."}]
  [:llm {:reads [:combined_info] :writes [:entities]
         :instruction "List all unique Entities..."}]]
 [:final {:keys [:summary :key-dates :entities]}]]
```

## What the Model Demonstrably Does

1. **Adapts decomposition to input size**: 7K → no tree; 105K → pipeline; 280K → chunked-parallel.
2. **Adapts decomposition to task type**: extraction vs analytical vs cross-doc vs validated.
3. **Adapts decomposition to quality requirements**: When told "every claim must survive adversarial validation," it designed cross-reference data flow without being shown that pattern.
4. **Knows when NOT to use a tree**: G04 skipped `emit-tree!` entirely.
5. **Converges on canonical patterns when appropriate**: chunk + map + aggregate + synthesize for large data.

## Quality Verification

Across 5 tasks, ~37 specific facts were spot-checked against source documents (exact percentages, dates, clause references, named entities).

**Zero hallucinations.**

The verification approach was: take a specific quantitative or named claim from each output, search the source document for the supporting text, confirm match. Examples:

- "2% per month interest (26.824% annualized)" → EXACT match in source
- "Section 4.4.4 Domestic Content removed in v3" → confirmed by side-by-side
- "Tseycum, Tsartlip, Tsawout, Pauquachin First Nations" → exact name match
- "1% audit understatement threshold" → exact match

## How to Run

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
# Run a single task
clj -M:dev -e '(require (quote [risk-analysis :as t])) (require (quote [runner])) (runner/start!) (runner/run! t/task) (runner/stop!)'
# Or run the full 5-task suite
clj -M:dev -e '(require (quote [all :as bench])) (bench/start!) (bench/run-all!) (bench/summary!) (bench/stop!)'
```

See [README.md](README.md) for full instructions and task list.

## Observability Layer (O02 + O03)

After the generalization story landed, a per-node observability layer was added
so future judges and pattern-matchers can see what each tree actually did. The
saved EDN now carries `:by-node` under `:usage` with structured paths (e.g.
`[{:type :map-each :parent <uuid> :index 7} {:type :leaf :node-id <uuid>}]`).
Each Phase 2 leaf emits a `:sheet/rlm-tree-node-completed` event carrying its
usage, structured path, and `:input-profile` (length/word-count/line-count for
each `:reads` key). A bookend `:sheet/rlm-tree-execution-completed` event closes
each tree with the full trajectory and a placeholder `:task-fingerprint`.

### First headline finding (risk-analysis canonical run)

| Phase | Share of tokens |
|---|---:|
| Per-chunk analysis (24 map-each iterations) | **86.4%** |
| Final synthesis | 13.6% |

Per-chunk analysis dominates. The model spends most of its budget reasoning
over each section, not on the consolidation step. This is *actionable signal*
for future judges: optimization wins are most likely to come from reducing
per-chunk waste (smaller chunks, terser prompts) rather than synthesis tweaks.

See [`tasks/02-risk-analysis.md`](tasks/02-risk-analysis.md) for the full Token
Breakdown section with min/max per-chunk distribution.

## Where to Read More

- **Per-task deep dives**: [`tasks/`](tasks/) — clean, current-state-only analysis for each of the 5 tasks
- **Tree comparison**: [`appendix/tree-comparison.md`](appendix/tree-comparison.md) — side-by-side trees for visual diff
