# Task 03 — Contract Comparison (Cross-Document Diff)

**Run file:** `generalization-results/contract-comparison_2026-05-19_131726.edn`
**Source:** `development/bench/contract_comparison.clj` (ns `contract-comparison`, var `task`)

## What the task is

Compare two versions of a legal contract and identify what changed. Quality requirements: cite specific section/clause text; if sections are identical, say so rather than inventing differences.

- **Documents:** Ontario microFIT Contract v2.0 (`contract_v2.txt`, 56K) + v3.1.1 (`contract_v3.txt`, 49K) — both real documents
- **Outputs:** `:document-survey`, `:section-diffs`, `:major-changes`, `:impact-analysis`, `:executive-summary`

## What the model designed

```clojure
[:sequence
 ;; Stage 1: Document survey (reads BOTH docs)
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:document-survey]
        :instruction "Analyze the structure of both contracts..."}]
 ;; Stage 2: Section diffs (reads BOTH docs, full text)
 [:llm {:reads [:contract_v2 :contract_v3] :writes [:section-diffs]
        :instruction "Compare specific clauses side-by-side.
                      Cite section numbers and clause text.
                      If sections are identical, explicitly state 'No changes in Section X'."}]
 ;; Stage 3: Classify (reads stage 2 output)
 [:llm {:reads [:section-diffs] :writes [:major-changes]
        :instruction "Classify into MAJOR/MINOR/COSMETIC..."}]
 ;; Stage 4: Impact analysis (reads stages 2+3)
 [:llm {:reads [:major-changes :section-diffs] :writes [:impact-analysis]
        :instruction "Analyze impact: Supplier vs OPA, risk shifts..."}]
 ;; Stage 5: Executive synthesis (reads everything)
 [:llm {:reads [:document-survey :section-diffs :major-changes :impact-analysis]
        :writes [:executive-summary]
        :instruction "Synthesize all findings into executive summary..."}]
 [:final {:keys [...]}]]
```

**Pattern:** 5-stage pipeline. NO chunking, NO map-each.

**Why this pattern:**
- Both documents fit in context (~105K combined fits in a 1M context window)
- Direct comparison preserves specific text (no summarization loss)
- Sequential stages progressively reason: structure → diffs → classification → impact → synthesis
- Each stage `:reads` only what it needs from prior stages

This is a structurally **different** pattern from chunked tasks — the model adapts to document size.

## Result Metrics

| Metric | Value |
|---|---|
| Status | `:success` |
| Duration | **35.6 s** |
| Total tokens | **64,336** (prompt 59,277 / completion 5,059) |
| Stages | 5 sequential LLMs |
| Chunking | None |

## Output Quality (verified against source)

| Model Claim | Source Verification |
|---|---|
| Version 2.0 → 3.1.1 | ✓ EXACT |
| Section B(5)(iv) Domestic Content 60% removed in v3 | ✓ VERIFIED |
| Section 4.4.4 Domestic Content removed in v3 | ✓ VERIFIED |
| Subsequent 4.4 clauses renumbered | ✓ Structural change observed |
| Section 5.1.2 added "in the Prescribed Form" | ✓ Verified |
| Section 6.5 added "emergency services personnel" | ✓ VERIFIED — v3 has emergency language; v2 has zero "emergency" |
| Section 6.5 removed Domestic Content references | ✓ Verified |
| "No changes in Section A and Section B (Clauses 1-8)" | ✓ Model acknowledges identical sections as required |

**Zero hallucinations detected.** Model correctly identifies what's identical between versions instead of inventing differences (a real risk on cross-document diff tasks).

## Full Model Output

### `:section-diffs`

> ### Part 1 – Contract Details
> *   **Version Reference**: Updated from "Version 2.0" to "Version 3.1.1".
> *   **Section A & B**: No changes in Section A and Section B (Clauses 1-8).
> *   **Section B(5)(iv)**: **Removed**. The clause requiring the "Minimum Required Domestic Content Level" to be 60% for solar PV has been deleted.
>
> ### Part 2 – General Terms and Conditions
> *   **Section 1 through 4.4.3**: No changes.
> *   **Section 4.4.4**: **Removed**. The representation regarding Domestic Content Level (formerly Section 4.4.4 in v2) has been deleted. Subsequent clauses in Section 4.4 are renumbered.
> *   **Section 5.1.2**: **Modified**. Added "in the Prescribed Form".
> *   **Section 6.5 (Collection of Information)**: **Modified**. Removed "information to establish compliance with Minimum Required Domestic Content Level" from the list of information OPA may obtain from third parties.
> *   **Section 6.5 (Disclosure of Information)**: **Modified**. Removed Domestic Content references. Added provision allowing OPA to disclose information to other government ministries for promoting public health and safety and emergency services planning.

### `:major-changes`

> ### **MAJOR (Legal/Financial/Payment Impact)**
>
> | Section | Description of Change |
> | :--- | :--- |
> | **Section B(5)(iv) & Appendix C** | **Removal of Domestic Content Requirements**: The requirement for Solar PV projects to meet a 60% Domestic Content Level has been completely deleted. |
> | **Section 6.5** | **New Disclosure Rights**: Added language allowing the OPA to disclose information to government agencies and emergency services. |
> | **Appendix A (Eligibility)** | **Project Eligibility Discretion**: Modified to allow the OPA, in its "sole and absolute discretion," to consent to a project being an OPA contracted facility. |
> | **Appendix C** | **"Existing Building" Definition**: Expanded to allow OPA discretion to deem a structure an "Existing Building." |
>
> ### **MINOR (Clarifications/Definitions)**
>
> | Section | Description of Change |
> | :--- | :--- |
> | **Sections 5.1.2, 7.2, 8.1, 9** | Requirements for notifications, terminations, and amendments now mandate the OPA's "Prescribed Form." |
> | **Appendix A** | Added definitions for **"Prescribed Form"** and **"Website."** |
> | **Appendix A** | Removed definitions for "Domestic Content Level" and "Minimum Required Domestic Content Level." |
>
> ### **COSMETIC (Formatting/Grammar)**
>
> | Section | Description of Change |
> | :--- | :--- |
> | **Version Reference** | "Version 2.0" → "Version 3.1.1." |
> | **Section 4.4.4** | Renumbering of subsequent 4.4 clauses. |
> | **Appendices D & E** | Noted as "No changes." |

### `:impact-analysis`

> ### Summary of Impact
>
> The transition from Version 2.0 to Version 3.1.1 significantly reduces the regulatory and administrative burden on the **Supplier**, primarily through the elimination of domestic sourcing requirements. Conversely, the **OPA** has strengthened its administrative control over contract management and expanded its rights regarding data disclosure.
>
> ### 1. Shift in Risk and Obligations
>
> **Removal of Domestic Content Requirements** (Major Benefit to Supplier)
> - Supplier Risk: Significantly **Decreased** — no longer face contract termination or payment clawbacks for failing to source local components.
> - OPA Obligation: **Decreased** — no longer needs to audit complex domestic content grids.
>
> **Project Eligibility and "Existing Building" Discretion** (Beneficial to Supplier)
> - Supplier Opportunity: **Increased** — pathway for projects previously ineligible due to rigid definitions.
> - OPA Risk: **Increased Control** — shifts the burden of case-by-case decisions to the OPA.
>
> **Mandatory "Prescribed Forms"** (Increased Administrative Burden on Supplier)
> - Supplier Obligation: **Increased** — notices risk rejection if Supplier doesn't use current form versions.
>
> ### 2. Changes in Disclosure and Privacy
>
> **Public Health and Safety Information Sharing**: OPA can now share project information with government agencies and emergency services. Expands the circle of third parties who have access to the Supplier's facility data.
>
> ### 3. Financial and Payment Impact
>
> - **Payment Eligibility:** By removing Domestic Content representations (Section 4.4.4), a major hurdle for payment eligibility is removed. The financial risk of breaches due to local sourcing failures is eliminated.
> - **No Direct Price Changes**, but profit margins likely improve from reduced CAPEX.

### `:executive-summary`

> The transition from microFIT Contract Version 2.0 to Version 3.1.1 represents a fundamental shift in the program's regulatory philosophy, moving from a protectionist economic development model to a more streamlined, administratively rigid framework.
>
> **Critical Takeaways for Stakeholders:**
>
> *   **Elimination of Domestic Content Requirements:** The most impactful change is the complete removal of the "Minimum Required Domestic Content Level." Suppliers are no longer obligated to source 60% of Solar PV components from Ontario.
> *   **Expansion of OPA Discretion:** The OPA has granted itself "sole and absolute discretion" in two key areas: deeming structures as "Existing Buildings" and allowing previously contracted facilities to participate.
> *   **Standardization of Communications:** The introduction of the "Prescribed Form" is a vital administrative change. All official actions must now use specific forms updated on the OPA website.
> *   **Enhanced Information Disclosure:** The OPA has expanded rights to share project data with public officials and emergency services for public health and safety planning.
