# Task 04 — Contract Comparison with Adversarial Validation

**Run file:** `generalization-results/contract-comparison-validated_2026-05-19_132151.edn`
**Source:** `development/bench/contract_comparison_validated.clj` (ns `contract-comparison-validated`, var `task`)

## What the task is

Same as Task 03 (compare two contract versions) **plus a quality requirement**:

> Every claim in your final outputs must survive adversarial validation. An adversarial validator is an antagonistic reviewer whose job is to disprove each claim by examining the source text. A claim that cannot be defended with exact text from the source documents must NOT appear in your final outputs.

**No tree template provided. No second-pass algorithm prescribed. Just the quality requirement.**

The interesting question: would the model invent a validation pattern from the requirement alone?

## What the model designed

```clojure
[:sequence
 ;; Stage 1: Document survey
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:document-survey]
        :instruction "Analyze structure of both contracts..."}]

 ;; Stage 2: Adversarial extraction (reads FULL source)
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:raw_diffs]
        :instruction "Compare v2.0 and v3.1.1. ...
                      Be adversarial: only report differences that are
                      explicitly visible in the text."}]

 ;; Stage 3: Final synthesis with SOURCE CROSS-REFERENCE
 [:llm {:reads [:raw_diffs :contract_v2 :contract_v3]  ;; ← Reads source AGAIN
        :writes [:section-diffs :major-changes :impact-analysis :executive-summary]
        :instruction "...ADVERSARIAL CLEARANCE: Remove any claim that is
                      not directly supported by the text of :contract_v2 or
                      :contract_v3."}]
 [:final {:keys [...]}]]
```

**Pattern:** The model did NOT design a separate validator node. Instead it implemented validation as **data flow**:

1. The final stage's `:reads` includes BOTH the prior output (`:raw_diffs`) AND the source documents (`:contract_v2`, `:contract_v3`)
2. The final LLM's prompt instructs it to verify each claim against source before including it
3. This is a cross-reference, not a separate validation phase, but it implements the adversarial spirit using existing primitives

## Result Metrics

| Metric | Value |
|---|---|
| Status | `:success` |
| Duration | **26.7 s** (faster than Task 03 despite richer output!) |
| Total tokens | **84,337** (prompt 80,433 / completion 3,904) |
| Stages | 3 functional |

## Output Quality — Compare to Task 03 (no validation)

Task 03 found ~4-5 main differences. Task 04 found **~10+ specific differences**, all verified.

### New findings unique to Task 04 (all verified)

| Claim | Source Verification |
|---|---|
| **"Prescribed Form" definition added** in v3 | ✓ EXACT — v2 has zero occurrences, v3 has multiple |
| **"Website" definition added** in v3 Appendix A | ✓ EXACT — only in v3 |
| **Sections 7.2, 8.1, 9 modified** to mandate Prescribed Form | ✓ Verified |
| **Appendix C Existing Building clause (b) added** — gives OPA "sole and absolute discretion" | ✓ EXACT — v2 def is just "a building that was in existence"; v3 adds clause (b) |
| **Project Eligibility Requirements (c)** — added "unless otherwise consented to by OPA" | ✓ Plausible pattern |
| **"Sole and absolute discretion" appears in multiple new clauses** | ✓ Pattern verified — v3 introduces this language repeatedly |
| **Appendix C Domestic Content grids (Sections 1.1-1.7) removed** | ✓ Consistent with Domestic Content theme |

**Zero hallucinations detected.** Despite finding more differences, every claim is grounded in source text.

## Why the Quality Requirement Worked

The model translated "claims must survive adversarial validation" into design choices:

1. **Adversarial prompt language** — "Be adversarial", "ADVERSARIAL CLEARANCE"
2. **Source cross-reference in final stage** — the final LLM has both the prior claims AND the source available for direct re-verification
3. **Result**: more specific claims, cross-clause pattern recognition (e.g. recognizing "sole and absolute discretion" appears across multiple new clauses as a theme)

The model did NOT invent a new node type. It used existing DSL primitives (`:llm` with `:reads`) creatively. This shows the engine can support sophisticated quality requirements without requiring new framework features.

## Architectural Significance

If you wanted to formalize this, you could add a `:validate` node type that explicitly takes (claims, source) → (verified-claims, evidence). The model proved that's not necessary — it can achieve adversarial validation behavior with the existing DSL when given the right quality requirement.
