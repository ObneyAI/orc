# Document Analysis — ORC RLM vs predict-rlm

**Date:** 2026-05-19
**Run:** [`results/document-analysis_2026-05-19_131441.edn`](../results/document-analysis_2026-05-19_131441.edn)
**Task source:** [`development/bench/document_analysis.clj`](../../document_analysis.clj) (ns `document-analysis`)
**Reference:** [`references/predict-rlm/document_analysis/sample/output/report.md`](../references/predict-rlm/document_analysis/sample/output/report.md)
**Source document:** YYJ-2025-Parking-Management-RFP (Victoria International Airport, ~280K chars / 136 pages — same RFP predict-rlm uses)

## Bottom Line

| Dimension | predict-rlm published | ORC |
|---|---|---|
| Wall clock | ~4 minutes | **2.1 minutes** |
| Total tokens | 194,072 | 212,066 (147K prompt / 65K completion) |
| Outputs | Markdown report + key-dates table + key-entities table | `:summary` (markdown), `:key-dates` (chronological), `:entities` (categorized catalog) — all populated |
| Chunks | not published | 24 × 12K chars, processed at concurrency 5 |
| Hallucinations | not published | **Zero across 8 spot-checked facts** |
| Date precision | 12 dates | Full chronological list including dates predict-rlm did not publish (April 1 1997 Head Lease date; November 25 2024 Ice Control Map; December 31 2024 Net Book Value; spring/summer 2025 milestones) |
| Entity catalog | 4 explicitly tabled | Organizations, First Nations, regulatory bodies, banks, carriers (Air Canada, WestJet, Alaska, Pacific Coastal, Porter, Air North, Harbour Air), equipment/software providers (SKIDATA, FLOWBIRD, Hesion, Precise Parklink) |

ORC's run completed in roughly half predict-rlm's wall clock with comparable token usage and produced complete `:summary` + `:key-dates` + `:entities` outputs. Eight facts spot-checked against the source RFP came out exact (3,243 parking spaces; 1.87M passengers in 2024; Curb Management Program terminology; First Nations names) — zero hallucinations.

## What the Model Designed

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

**Pattern:** Parallel chunked extraction + three specialized synthesis LLMs (one per output).

**Why this pattern works for this task:**
- 280K-char document is too large for one LLM call → `:chunk-document` splits into 12K-char chunks
- Chunks are independent → `:map-each` with `:max-concurrency 5` processes them in parallel
- The three outputs (executive prose, chronological dates, entity catalog) have fundamentally different structure → specialized synthesis per output keeps each call's input bounded and tightly focused

## Sample Output (verbatim from the headline EDN)

### `:summary` (excerpt)

```markdown
### Executive Summary: 2025 Parking Management Services at Victoria International Airport (YYJ)

**Project Overview**
The Victoria Airport Authority (VAA) is soliciting proposals for a professional
parking management firm to oversee the operation of its parking facilities and
curbside traffic at Victoria International Airport (YYJ). This project encompasses
3,243 spaces across four main lots, seasonal overflow areas, and the implementation
of a Curb Management Program to optimize terminal frontage traffic flow. With
passenger traffic recovering post-COVID and growing (estimated at 1.87M in 2024),
VAA seeks a partner that combines operational excellence with innovation and
advanced parking technology.

**Scope of Services**
The successful Operator will be responsible for day-to-day operations, including:
*   **Facility Management:** Oversight of short-term, long-term, and employee lots,
    as well as seasonal shuttle services during peak periods (e.g., late December
    to mid-January).
*   ...
```

### `:key-dates` (excerpt)

```markdown
*   **April 1, 1997:** Reference date of the "Head Lease" between the Crown and the VAA.
*   **November 25, 2024:** Date of the Ice Control and Snow Clearing Map (Appendix C).
*   **December 31, 2024:** Reference date for the Net Book Value calculation of parking equipment.
*   **January 20, 2025:** Date of the Parking Capacity Map (Appendix A).
*   **Thursday, February 13, 2025:** Date of RFP Issue.
*   **Thursday, February 20, 2025 (2:00 pm PST):** Deadline for Receipt Confirmation Form (Schedule 1) and RSVP for the Mandatory Pre-Bid Meeting.
*   **Thursday, February 27, 2025 (1:30 pm PST):** Mandatory Pre-Bid Meeting and Site Tour at Victoria International Airport.
*   **Saturday, March 1, 2025:** Effective date for new parking lot rates (Appendix B).
*   **Thursday, March 6, 2025 (2:00 pm PST):** Deadline for questions from Proponents.
*   **Monday, March 10, 2025:** Deadline for VAA to provide responses to questions.
*   **Monday, March 31, 2025 (2:00 pm PST):** Closing date for submission of proposals.
*   **Thursday, April 24, 2025:** Target date for award to the successful Proponent.
*   **Spring 2025:** Expected installation of the digital signage.
*   ...
```

### `:entities` (excerpt)

```markdown
### Organizations
*   **Victoria Airport Authority (VAA / The Authority):** The issuing entity, airport operator, and contracting body.
*   **Victoria International Airport (YYJ / The Airport):** The site of operations.
*   **The Crown:** His Majesty the King in Right of Canada, represented by the Minister of Transport.
*   **W̱SÁNEĆ People:** Traditional territory holders.
*   **First Nations Partners:** Tseycum First Nation, Tsartlip First Nation, Tsawout First Nation, and Pauquachin First Nation.
*   **Canadian Transportation Agency (CTA):** Federal regulatory body for accessibility and transport audits.
*   **WorkSafeBC / Workers' Compensation Board of BC:** Regulatory body for occupational health and safety.
*   ...

### Equipment / Software Providers
*   SKIDATA, FLOWBIRD, Hesion (LPR software), Precise Parklink (maintenance/parts)

### Major Carriers
*   Air Canada, WestJet, Alaska Airlines, Pacific Coastal, Porter, Air North, Harbour Air
```

## Verified Facts (spot-check against the source RFP)

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

## Run Metrics

| Metric | Value |
|---|---|
| Status | `:success` |
| Duration | **126.3 s** (~2.1 minutes) |
| Total tokens | **212,066** (prompt 147,180 / completion 64,886) |
| Chunks | 24 × 12K chars |
| Parallel concurrency | 5 |
| Synthesis style | 3 specialized LLMs in sequence (executive summary, key dates, entities) |
| Hallucinations on spot-checked facts | 0 of 8 |

## Reproducibility

```bash
export OPENROUTER_API_KEY=sk-or-v1-...

clj -M:dev:test -e '
(require (quote [document-analysis :as t]))
(require (quote [runner]))
(runner/start!) (runner/run! t/task) (runner/stop!)'
```

- **Task source:** `development/bench/document_analysis.clj`
- **Input:** `development/bench/documents/yyj_rfp.txt` (~280K chars)
- **Output:** `development/bench/generalization-results/document-analysis_<timestamp>.edn`

## Related

- predict-rlm's published report: [`references/predict-rlm/document_analysis/sample/output/report.md`](../references/predict-rlm/document_analysis/sample/output/report.md)
- Companion clean reports: [01_image_analysis](01_image_analysis.md) · [02_document_redaction](02_document_redaction.md) · [03_invoice_processing](03_invoice_processing.md) · [05_contract_comparison](05_contract_comparison.md)
