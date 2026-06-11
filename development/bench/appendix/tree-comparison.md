# Tree Comparison — Side by Side

The five tasks produced five structurally different decompositions. Each tree is shown verbatim as the model generated it.

## Task 05 — Legal Issue Detection (7K doc)

**Generated tree:** None. The model bypassed `emit-tree!` entirely and answered directly.

The 7K document fits comfortably in one LLM call. Decomposition would only add overhead. The model recognized this and skipped tree generation.

## Task 04 — Contract Comparison + Adversarial Validation (105K, 2 docs)

```clojure
[:sequence
 [:llm
  {:instruction
   "Analyze the structure of both contracts (v2.0 and v3.1.1). Map out all
    Parts, Sections, and Appendices. Identify the high-level roadmap of each document.",
   :reads [:contract_v2 :contract_v3],
   :writes [:document-survey]}]
 [:chunk-document {:from :contract_v2, :size 12000, :into :v2_chunks}]
 [:chunk-document {:from :contract_v3, :size 12000, :into :v3_chunks}]
 [:llm
  {:instruction
   "Compare v2.0 and v3.1.1. Identify every addition, removal, or modification.
    For each change, you MUST cite the specific Section number and the nature of
    the text change. Flag if a section is identical.
    Be adversarial: only report differences that are explicitly visible in the text.",
   :reads [:contract_v2 :contract_v3],
   :writes [:raw_diffs]}]
 [:llm
  {:instruction
   "Based on the identified differences, produce the final project deliverables.
    1. section-diffs: A structured list of changes with precise citations.
    2. major-changes: Categorize into MAJOR (legal/financial), MINOR, or COSMETIC.
    3. impact-analysis: Analyze risk shifts (Supplier vs OPA) for each change.
    4. executive-summary: Provide a high-level narrative.

    ADVERSARIAL CLEARANCE: Remove any claim that is not directly supported by
    the text of :contract_v2 or :contract_v3.",
   :reads [:raw_diffs :contract_v2 :contract_v3],
   :writes [:section-diffs :major-changes :impact-analysis :executive-summary]}]
 [:final
  {:keys [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]]
```

**Key observation:** The final stage's `:reads` includes both `:raw_diffs` AND the source documents — implementing adversarial validation as data flow.

Also note the dead code: the model speculatively allocated chunks (`:v2_chunks`, `:v3_chunks`) but never used them — choosing instead to read the full docs directly. An artifact of the model's reasoning process.

## Task 03 — Contract Comparison baseline (105K, 2 docs)

```clojure
[:sequence
 [:llm
  {:instruction
   "Analyze the structure of both :contract_v2 and :contract_v3. Create a
    document survey that outlines the sections, appendices, and parts of each.
    Note any immediate differences in layout or high-level organization.",
   :reads [:contract_v2 :contract_v3],
   :writes [:document-survey]}]
 [:llm
  {:instruction
   "Compare the specific clauses of :contract_v2 and :contract_v3 side-by-side.
    List every specific addition, removal, and modification. Ensure you cite
    section numbers and specific clause text. If sections are identical,
    explicitly state 'No changes in Section X'.",
   :reads [:contract_v2 :contract_v3],
   :writes [:section-diffs]}]
 [:llm
  {:instruction
   "Based on the :section-diffs, classify the changes into three categories:
    MAJOR (legal/financial/payment impact), MINOR (clarifications/definitions),
    and COSMETIC (formatting/grammar). Provide a table or list for each category.",
   :reads [:section-diffs],
   :writes [:major-changes]}]
 [:llm
  {:instruction
   "Analyze the impact of the identified changes in :major-changes. Determine
    who is affected (Supplier vs OPA) and how the risk or obligation has shifted
    between the versions. Look for changes in payment terms, liability, and
    required actions.",
   :reads [:major-changes :section-diffs],
   :writes [:impact-analysis]}]
 [:llm
  {:instruction
   "Synthesize all previous findings into a final executive summary. Explain
    how the microFIT contract evolved from Version 2.0 to 3.1.1, highlighting
    the most critical takeaways for a stakeholder.",
   :reads [:document-survey :section-diffs :major-changes :impact-analysis],
   :writes [:executive-summary]}]
 [:final
  {:keys [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]]
```

**Key observation:** 5-stage pipeline. No chunking — both docs fit in context. Each stage builds on previous outputs.

## Task 02 — Risk Analysis (280K, 1 doc)

```clojure
[:sequence
 [:chunk-document {:from :yyj_rfp, :size 12000, :into :chunks}]
 [:map-each
  {:from :chunks,
   :as :chunk,
   :into :extracted_data,
   :max-concurrency 5}
  [:llm
   {:instruction
    "Analyze this RFP section for a bidder. Identify:
     1) Specific Obligations (Financial, Operational, Compliance, Reporting, or Timeline).
     2) Penalties or consequences for non-compliance (include clause citations).
     3) Significant risks or liabilities.
     Focus on finding concrete details rather than generic terms.",
    :reads [:chunk],
    :writes [:analysis]}]]
 [:aggregate {:from :extracted_data, :writes [:combined_analysis]}]
 [:llm
  {:instruction
   "Based on the comprehensive analysis of all RFP sections, produce the
    final structured report.
    1. :obligations - Structured classification by category
    2. :penalties - Detailed list with clause references
    3. :risk-matrix - Map to HIGH/MEDIUM/LOW, justify via 'Difficulty vs Severity'
    4. :executive-summary - Strategic overview with recommendations
    REASON about the risks. Provide analytical depth, not just a list of clauses.",
   :reads [:combined_analysis],
   :writes [:obligations :penalties :risk-matrix :executive-summary]}]
 [:final
  {:keys [:obligations :penalties :risk-matrix :executive-summary]}]]
```

**Key observation:** Single unified synthesis. The four outputs are interrelated analytical artifacts — best reasoned about together.

## Task 01 — Document Analysis (280K, 1 doc)

```clojure
[:sequence
 [:chunk-document {:from :yyj_rfp, :size 12000, :into :chunks}]
 [:map-each
  {:from :chunks,
   :as :chunk,
   :into :extracted_data,
   :max-concurrency 5}
  [:llm
   {:instruction
    "Extract the following from this RFP segment:
     1) Key points for an executive summary,
     2) Important dates (with context),
     3) Entities (people/roles, organizations, contact info).
     If info is not present in this segment, leave it blank.",
    :reads [:chunk],
    :writes [:segment_info]}]]
 [:aggregate {:from :extracted_data, :writes [:combined_info]}]
 [:sequence
  [:llm
   {:instruction
    "Based on the provided extractions from a large RFP, synthesize a concise
     and professional Executive Summary.",
    :reads [:combined_info],
    :writes [:summary]}]
  [:llm
   {:instruction
    "Based on the provided extractions, list all specifically mentioned Key Dates
     with their associated context/milestones in chronological order where possible.",
    :reads [:combined_info],
    :writes [:key-dates]}]
  [:llm
   {:instruction
    "Based on the provided extractions, list all unique Entities including
     organizations, specific personnel roles, and contact information mentioned
     in the document.",
    :reads [:combined_info],
    :writes [:entities]}]]
 [:final {:keys [:summary :key-dates :entities]}]]
```

**Key observation:** Three SPECIALIZED synthesis LLMs. The three outputs have structurally different shapes (executive prose vs chronological list vs entity catalog) — separate synthesis per output.

## Common primitives, different topologies

All trees use the same DSL primitives (`:sequence`, `:llm`, `:map-each`, `:chunk-document`, `:aggregate`, `:final`). What differs is **how the model composes them**:

| Aspect | Task 01 | Task 02 | Task 03 | Task 04 | Task 05 |
|---|---|---|---|---|---|
| Tree at all? | ✓ | ✓ | ✓ | ✓ | **✗** |
| Chunking? | ✓ | ✓ | ✗ | ✗ (allocated, unused) | n/a |
| Parallel map-each? | ✓ (5) | ✓ (5) | ✗ | ✗ | n/a |
| # of synthesis LLMs | 3 specialized | 1 unified | 5-stage chain | 3-stage with cross-ref | 0 |
| Source re-read after extraction? | ✗ | ✗ | ✗ | **✓** | n/a |

The model's choices are task-appropriate, not template-driven.
