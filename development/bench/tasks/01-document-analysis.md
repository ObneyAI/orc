# Task 01 — Document Analysis (Extraction on 280K)

**Run file:** `generalization-results/document-analysis_2026-05-19_131441.edn`
**Source:** `development/bench/document_analysis.clj` (ns `document-analysis`, var `task`)

## What the task is

Extract a structured representation of a large RFP document.

- **Document:** Victoria International Airport RFP (`yyj_rfp.txt`, 280,140 chars, real document)
- **Outputs:** `:summary`, `:key-dates`, `:entities`

## What the model designed

```clojure
[:sequence
 [:chunk-document {:from :yyj_rfp, :size 12000, :into :chunks}]
 [:map-each
  {:from :chunks, :as :chunk, :into :extracted_data, :max-concurrency 5}
  [:llm
   {:instruction "Extract: 1) Key points for executive summary,
                  2) Important dates (with context),
                  3) Entities (people/roles, organizations, contact info).
                  If info is not present in this segment, leave it blank."
    :reads [:chunk]
    :writes [:segment_info]}]]
 [:aggregate {:from :extracted_data, :writes [:combined_info]}]
 [:sequence
  [:llm {:instruction "Synthesize Executive Summary..."
         :reads [:combined_info] :writes [:summary]}]
  [:llm {:instruction "List all Key Dates with context, chronologically..."
         :reads [:combined_info] :writes [:key-dates]}]
  [:llm {:instruction "List all unique Entities including organizations, roles, contacts..."
         :reads [:combined_info] :writes [:entities]}]]
 [:final {:keys [:summary :key-dates :entities]}]]
```

**Pattern:** Parallel chunked extraction + three SPECIALIZED synthesis LLMs (one per output).

**Why this pattern:**
- Document is too large for one LLM call → chunking
- Chunks are independent → parallel processing
- The three outputs (executive prose, chronological dates, entity catalog) have fundamentally different structure → specialized synthesis per output

## Result Metrics

| Metric | Value |
|---|---|
| Status | `:success` |
| Duration | **126.3 s** |
| Total tokens | **212,066** (prompt 147,180 / completion 64,886) |
| Chunks | 24 × 12K chars |
| Parallel concurrency | 5 |
| Synthesis style | 3 specialized LLMs in sequence |

## Output Quality (verified against source)

| Model Claim | Source Verification |
|---|---|
| 3,243 parking spaces, four main lots | ✓ Verified |
| 1.87M passengers in 2024 | ✓ Verified |
| April 1, 1997: Head Lease date (Crown / VAA) | ✓ EXACT |
| November 25, 2024: Ice Control & Snow Clearing Map (Appendix C) | ✓ EXACT |
| December 31, 2024: Net Book Value reference date | ✓ EXACT |
| February 13, 2025: RFP Issue Date | ✓ EXACT |
| Curb Management Program | ✓ EXACT terminology |
| Tseycum, Tsartlip, Tsawout, Pauquachin First Nations | ✓ EXACT names |

**Zero hallucinations detected.**

## Sample Output (excerpt)

```
### Executive Summary: 2025 Parking Management Services at YYJ

The Victoria Airport Authority (VAA) is soliciting proposals for a
professional parking management firm to oversee operations of its
parking facilities and curbside traffic at Victoria International
Airport. This project encompasses 3,243 spaces across four main lots,
seasonal overflow areas, and the implementation of a Curb Management
Program to optimize terminal frontage traffic flow.

### Key Dates
* April 1, 1997: Reference date of the "Head Lease" between the Crown and the VAA.
* November 25, 2024: Date of the Ice Control and Snow Clearing Map (Appendix C).
* December 31, 2024: Reference date for the Net Book Value calculation.
* February 13, 2025: Date of RFP Issue.
...
```
