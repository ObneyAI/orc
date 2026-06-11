# Task 02 — Risk & Obligation Analysis (Analytical reasoning on 280K)

**Run file:** `generalization-results/risk-analysis_2026-05-19_131031.edn`
**Source:** `development/bench/risk_analysis.clj` (ns `risk-analysis`, var `task`)

## What the task is

Perform analytical risk and obligation analysis on a large RFP from the bidder's perspective. NOT extraction — analytical reasoning with classification and assessment.

- **Document:** Victoria International Airport RFP (`yyj_rfp.txt`, 280,140 chars, real document)
- **Outputs:** `:obligations` (classified by type), `:penalties`, `:risk-matrix` (HIGH/MEDIUM/LOW), `:executive-summary`

## What the model designed

```clojure
[:sequence
 [:chunk-document {:from :yyj_rfp, :size 12000, :into :chunks}]
 [:map-each
  {:from :chunks, :as :chunk, :into :extracted_data, :max-concurrency 5}
  [:llm
   {:instruction "Analyze this RFP section for a bidder. Identify:
                  1) Specific Obligations (Financial, Operational, Compliance, Reporting, Timeline).
                  2) Penalties or consequences for non-compliance (include clause citations).
                  3) Significant risks or liabilities.
                  Focus on finding concrete details rather than generic terms."
    :reads [:chunk]
    :writes [:analysis]}]]
 [:aggregate {:from :extracted_data, :writes [:combined_analysis]}]
 [:llm
  {:instruction "Based on the comprehensive analysis of all RFP sections,
                 produce the final structured report.
                 1. :obligations - Structured classification by category
                 2. :penalties - Detailed list with clause references
                 3. :risk-matrix - Map to HIGH/MEDIUM/LOW, justify via 'Difficulty vs Severity'
                 4. :executive-summary - Strategic overview with recommendations
                 REASON about the risks. Provide analytical depth, not just a list of clauses."
   :reads [:combined_analysis]
   :writes [:obligations :penalties :risk-matrix :executive-summary]}]
 [:final {:keys [:obligations :penalties :risk-matrix :executive-summary]}]]
```

**Pattern:** Parallel chunked analysis + SINGLE unified synthesis with multiple output keys.

**Why this pattern:**
- Same chunking-and-parallel as document analysis (same doc size)
- BUT the four outputs (obligations, penalties, risk-matrix, exec-summary) are interrelated and analytical — best reasoned about together in one synthesis
- Contrast with document analysis which used 3 separate synthesis LLMs for structurally different outputs

## Result Metrics

| Metric | Value |
|---|---|
| Status | `:success` |
| Duration | **64.8 s** |
| Total tokens | **170,336** (prompt 133,824 / completion 36,512) |
| Chunks | 24 × 12K chars |
| Parallel concurrency | 5 |
| Synthesis style | 1 unified LLM with 4 outputs |

## Output Quality (verified against source)

| Model Claim | Source Verification |
|---|---|
| 2% per month interest (~27% annualized) | ✓ EXACT: `"Interest" means... 2% per month, compounded monthly (26.824% per annum)` |
| 1% audit understatement threshold | ✓ EXACT |
| 7 consecutive day strike termination | ✓ EXACT |
| 5% Letter of Credit | ✓ EXACT |
| 10km radius non-competition | ✓ EXACT: `within a radius of ten (10) kilometers` |
| 0.5% marketing spend obligation | ✓ EXACT |
| WorldHost training requirement | ✓ EXACT |
| Bilingual Official Languages Act compliance | ✓ Verified |

**Zero hallucinations detected.**

## Full Model Output

### `:obligations` (classified by type)

> ### 1. Financial Obligations
> *   **Revenue Remittance:** Must remit all Gross Revenue (less the Management Fee) and sales taxes to the VAA by the **5th business day** of the following month (GC 3.1.2).
> *   **Trust Responsibility:** All collected monies (cash/electronic) are the exclusive property of the VAA and must be held in trust (GC 3.1.1).
> *   **Fixed Fee Risk Management:** The "Fixed Management Fee" must cover all costs including snow removal, seasonal shuttles ($0 cost to VAA), and labor cost spikes/unionization (Sec 2.1.1, 2.1.2).
> *   **Security & Insurance:** Provide an Irrevocable Letter of Credit (ILOC) for **5% of the total management fee** (Sec 4) and maintain $5M-$10M in General Liability insurance (Sec 2.1.1).
> *   **Marketing Allocation:** Obligation to budget **0.5% of public parking revenues** toward marketing initiatives (Clause 2.1).
>
> ### 2. Operational Obligations
> *   **Availability:** Provide continuous service 24/7, 365 days a year, covering all flight arrivals/departures, including delays.
> *   **Staffing Minima:** Lot 1 booth (20 hrs/day), Curb Management Officer (15.5 hrs/day), and On-Site Manager (8 hrs/day) (Sec 2.6.3).
> *   **Curbside Management:** Develop and execute a mandatory "Curb Management Program".
> *   **Facility Maintenance:** Responsible for snow/ice control across 95,000m², daily litter pickup.
> *   **Equipment Monitoring:** Must daily monitor and immediately report deficiencies in VAA-owned SKIDATA, FLOWBIRD, and Hesion LPR systems.
>
> ### 3. Compliance & Reporting Obligations
> *   **Official Languages:** Must provide all printed materials and maintain on-shift staff capable of oral response in both **English and French**.
> *   **Regulatory Compliance:** Adhere to the *Aeronautics Act*, *Official Languages Act*, and *Workers' Compensation Act* (BC).
> *   **Reporting Cadence:** Weekly signed Revenue Statements (every Friday) and Monthly Statements of Account (5th business day).
> *   **Environmental:** Remediate any "Adverse Environmental Condition" caused by operations at the Contractor's cost.
>
> ### 4. Timeline Obligations
> *   **Contract Term:** Five-year term starting June 1, 2025, to May 31, 2030.
> *   **Office Setup:** Establish a local office within **two months** of award.

### `:penalties` (with clause refs)

> ### 1. Financial Penalties
> *   **Usurious Interest on Arrears:** Late revenue payments accrue interest at **2% per month (26.824% per year)**, compounded monthly.
> *   **Audit Shortfalls:** If an audit reveals revenue is understated by ≥ 1%, the Contractor must pay for the entire audit cost plus the deficiency and interest (Clause 3.4.4).
> *   **Collection Liability:** If the Contractor fails to follow the 3-step notification process for unpaid fees, they assume **personal financial liability** for those unpaid amounts.
> *   **Theft/Defalcation:** The Contractor is financially responsible for "all amounts lost due to theft or defalcation," regardless of source.
>
> ### 2. Termination & Disqualification
> *   **Immediate Termination (No Notice):** Triggered by insolvency, failure to make payments, abandonment of work, or a "fundamental breach" as determined by VAA.
> *   **Labor Dispute Interruption:** VAA may terminate if a strike or labor dispute causes a service interruption exceeding **7 consecutive days**.
> *   **Non-Compliance Rejection:** Failure to meet "Must" or "Mandatory" criteria in the RFP leads to immediate disqualification.
> *   **Security Removal:** VAA reserves the right to demand immediate removal of any personnel based on "national security" reasons.
>
> ### 3. Contractual Sanctions
> *   **LOC Forfeiture:** VAA may draw on the 5% LOC if a renewal is not provided **3 weeks prior** to expiry or if any default occurs.
> *   **VAA Step-in Rights:** If the Contractor fails to perform maintenance or environmental remediation, VAA can perform the work and charge the Contractor for all costs plus administrative overhead.

### `:risk-matrix` (HIGH/MEDIUM/LOW with reasoning)

| Obligation Category | Risk Level | Justification (Difficulty vs Severity) |
| :--- | :---: | :--- |
| **Fixed Management Fee** | **HIGH** | Difficulty: High. Must cover unpredictable snow/ice costs, seasonal shuttle spikes (Dec-Jan), and labor fuel. Severity: Extreme. No fee escalators for 5-7 years; unionization costs cannot be passed to VAA. |
| **Revenue Liability (Theft)** | **HIGH** | Difficulty: Medium. Requires perfect internal controls across cash/electronic. Severity: High. Contractor is the "insurer"; stolen funds must be replaced out-of-pocket as "Gross Revenue." |
| **Bilingual Service** | **MEDIUM** | Difficulty: High. Recruiting/retaining bilingual staff for every shift 24/7 is a localized labor market challenge in BC. Severity: Medium. Non-compliance is a breach of the Official Languages Act. |
| **Late Payment Interest** | **HIGH** | Difficulty: Low (administrative). Severity: Extreme. At ~27% annualized, even small delays in remitting large parking revenues create massive financial erosion. |
| **Environmental Remediation** | **MEDIUM** | Difficulty: Low (standard ops). Severity: High. Broad indemnity remains the Contractor's property/responsibility even if pollutants "affix to land." |
| **Non-Competition (10km)** | **LOW** | Difficulty: Low. Severity: Medium. Restricts regional expansion but is geographically contained to the Saanich Peninsula. |

### `:executive-summary`

> ### Strategic Overview
>
> The Victoria Airport Authority (VAA) RFP for Parking Management Services is structured as a **low-risk/high-reward model for the Authority** and a **high-liability/fixed-upside model for the Bidder**. By utilizing a "Fixed Management Fee" structure, the VAA offloads systemic risks—including inflation, labor disputes (unionization), and environmental events (snow/ice)—entirely onto the Contractor.
>
> ### Top Concerns for Leadership
> 1. **Revenue Accountability:** The definition of "Gross Revenue" includes funds lost to theft or employee defalcation. You are effectively acting as an insurance entity for the VAA's parking cash flow.
> 2. **Labor Cost Rigidity:** Section 2.1.2 explicitly forbids fee increases due to collective bargaining. If the local labor market forces wages up or a union is formed, your profit margin will be the only source of funding for these increases.
> 3. **The "27% Trap":** The 2% per month compounded interest on late revenue remittance is aggressive. Administrative errors in the "5th Business Day" reporting window carry a disproportionate financial penalty.
> 4. **Forecast Disclaimer:** The RFP provides growth forecasts but explicitly disclaims accuracy.

### Notable analytical inventions

The model invented the **"Difficulty vs Severity"** framework as a risk-matrix methodology — not present in the source documents or task instructions. It also coined the **"27% Trap"** as strategic framing for the high-interest penalty clause. Both are accurate distillations grounded in actual source text.

## Token Breakdown (O03 — observability slice 2)

After landing the Phase 2 observability layer (O02 + O03), the saved EDN now
contains a `:by-node` map under `:usage` keyed by structured node-path. Each
entry shows that node's per-call token cost.

**Run sampled:** `generalization-results/risk-analysis_2026-05-19_182902.edn` (162,614 total tokens)

### Distribution

| Phase | Entries | Tokens | Share |
|---|---:|---:|---:|
| Per-chunk analysis (map-each iterations) | 24 | 138,112 | **86.4%** |
| Final synthesis | 1 | 21,703 | 13.6% |

### Per-chunk statistics

| Metric | Value |
|---|---:|
| Avg tokens per chunk | 5,754 |
| Min tokens (smallest chunk's analysis) | 3,026 |
| Max tokens (largest chunk's analysis) | 12,405 |
| Spread (max ÷ min) | 4.1× |

### Headline observation

**Per-chunk analysis dominates token cost (86.4%)** — the model spends most of
its budget reasoning over each section, not on the final synthesis. This
matches the analytical pattern: the heavy lifting is per-section risk
assessment; synthesis is a relatively cheap consolidation step.

The 4.1× spread between min and max per-chunk tokens indicates the model
spends real effort proportional to section complexity — denser RFP sections
(operational requirements, indemnification clauses) generate more analysis
than thinner sections (header pages, table of contents).

### How to inspect for any run

```clojure
(require '[clojure.edn :as edn])
(def r (edn/read-string (slurp "<path-to-result.edn>")))
(get-in r [:usage :by-node])
;; => {[{:type :map-each :parent <uuid> :index 0} {:type :leaf :node-id <uuid>}]
;;     {:prompt-tokens N :completion-tokens N :total-tokens N}
;;     ...}
```

The same data is available in the event store as `:sheet/rlm-tree-node-completed`
events, each tagged with the tick-id. A bookend `:sheet/rlm-tree-execution-completed`
event closes the trace with the full trajectory.
