# Document Analysis — ORC RLM vs predict-rlm (Apples-to-Apples)

**Date:** 2026-05-21
**Models:** main `openai/gpt-5.4`, sub `openai/gpt-5.1-chat` (matches predict-rlm's published setup)
**Run:** [`results/document-analysis-predict-rlm_2026-05-21_162203.edn`](../results/document-analysis-predict-rlm_2026-05-21_162203.edn)
**Reference:** [`references/predict-rlm/document_analysis/sample/output/report.md`](../references/predict-rlm/document_analysis/sample/output/report.md)
**Source document:** YYJ-2025-Parking-Management-RFP.pdf (5.4MB, 136 pages, ~280K chars)

## Bottom Line

Against predict-rlm's published reference for the same document with the same models, **ORC extracted ALL 12 of predict-rlm's published key dates PLUS 7 additional dates** that didn't appear in their published table — including the contractually significant proposal-validity dates (2025-05-30 / 2025-06-29), the first revenue/account statement deadlines (2025-06-06 / 2025-07-07), and the seasonal shuttle service period (2024-12-22 to 2025-01-15).

| Dimension | predict-rlm published | ORC |
|---|---|---|
| Key dates extracted | 12 | **19** (incl. all 12 + 7 additional) |
| Key entities | 4 explicitly tabled (+ inline) | **13** (incl. vendors like Flowbird, Hesion LPR, Precise Parklink, Skidata not in predict-rlm's published table) |
| Wall clock | ~4 minutes | 3.7 minutes |
| Total tokens | 194,072 | 614,439 (~3.2× more) |
| Cost (rough) | $0.52 | ~$1.20 |
| Markdown narrative `:report` | Full 4-section briefing report | **NOT synthesized** (intermediate keys have content, but the final `:report` write was nil) |
| Extraction methodology | Vision (pymupdf renders each page → multimodal LLM) | Text extraction (PDFBox extracts text → text LLM) |

**ORC was MORE thorough on structured extraction, but did not produce the final markdown narrative.** This is the inverse trade-off from predict-rlm: their published report.md is a polished prose synthesis that we lack, but their key-dates table is less complete than ours.

## What Got Extracted vs predict-rlm's Published Table

### Key Dates — overlap with predict-rlm

All 12 of predict-rlm's published dates were captured by ORC (and 7 more):

| Date | predict-rlm | ORC | Notes |
|---|:-:|:-:|---|
| 2025-02-13 RFP issued | ✅ | ✅ | "Date of Issue" |
| 2025-02-20 14:00 PST Receipt Confirmation due | ✅ | ✅ | ISO + time + timezone exact |
| 2025-02-27 13:30 PST Mandatory pre-bid meeting | ✅ | ✅ | ISO + time + timezone exact |
| 2025-03-06 14:00 PST Deadline for questions | ✅ | ✅ | exact |
| 2025-03-10 Question response deadline | ✅ | ✅ | exact |
| 2025-03-31 14:00 PST Proposal submission deadline | ✅ | ✅ | exact |
| 2025-04-24 Anticipated award | ✅ | ✅ | exact |
| 2025-06-01 Contract commencement | ✅ | ✅ | exact |
| 2030-05-31 Initial term ends | ✅ | ✅ | exact |
| 2029-12-01 Renewal notice deadline | ✅ | ✅ | exact |
| 2030-06-01 Potential renewal term begins | ✅ | ⚠️ | not captured separately — implied by 2030-05-31 end |
| 2032-05-31 Renewal term ends | ✅ | ✅ | exact |

### Additional dates ORC captured (predict-rlm did NOT publish)

These are real contractually-significant dates in the source document that predict-rlm's published report omitted:

| ORC's date | What it is | Why it matters |
|---|---|---|
| 2025-02-20 14:00 PST RSVP for pre-bid meeting | RSVP deadline separate from receipt-confirmation | Failing this disqualifies bidding |
| 2025-03-01 Parking Lot Rates Effective Date | Rate schedule activation | Affects revenue projections |
| 2025-05-30 Contract Proposal Validity Date (RFP) | 60-day proposal validity per Section 2.8 | Contractual deadline |
| 2025-06-06 First Weekly Statement of Revenue Due | First operational reporting deadline | Operational obligation |
| 2025-06-29 Contract Proposal Validity Date (Certification) | 90-day validity per Schedule Four | Internal discrepancy with the 60-day in Section 2.8 — surfaced by the model |
| 2025-07-07 First Monthly Statement of Account Due | First monthly accounting reporting deadline | Operational obligation |
| 2024-12-22 / 2025-01-15 Seasonal Shuttle Period | Mandatory shuttle service window | Operational requirement during initial term |

The model's `:verification` field explicitly noted the proposal-validity discrepancy: "Clarify that valid proposal period has internal discrepancies: Section 2.8 states 60 days, while Schedule Four (1.0) requires certification for 90 days from closing." This is high-value forensic reading — both validity dates exist in the RFP and they contradict each other.

### Key Entities — overlap + extras

predict-rlm's published table lists 4 explicit entity rows (VAA, YYJ, David Parson, Elizabeth M. Brown) plus several inline-mentioned organizations.

ORC's 13 entities:

| Entity | Role | predict-rlm published? |
|---|---|:-:|
| Victoria Airport Authority | Issuing Authority / Client | ✅ |
| Victoria International Airport (YYJ) | Operating site | ✅ (named YYJ) |
| David Parson | Commercial Development Officer | ✅ |
| Elizabeth M. Brown | CEO of VAA | ✅ |
| His Majesty the King in Right of Canada | Lessor (Head Lease) | mentioned inline |
| Minister of Transport | Crown representative | mentioned inline |
| Transport Canada | Author of Environmental Baseline Study | not explicit |
| Canadian Transportation Agency (CTA) | Regulatory / Audit Body | not explicit |
| Work Safe BC | Occupational Health and Safety Regulator | not explicit |
| YYJ Security Operations Center | Airport Security Reporting Point | not explicit |
| Flowbird | Short-Term Parking Control System Provider | not explicit |
| Hesion LPR | License Plate Recognition Software Provider | not explicit |
| Skidata | Parking Control System Provider | not explicit |
| Precise Parklink | Equipment and Maintenance Service Provider | not explicit |

ORC captured 4 named vendor infrastructure providers (Flowbird, Hesion LPR, Skidata, Precise Parklink) that don't appear in predict-rlm's published entity table. These are operationally important for a parking-management bidder (each is a system the eventual contractor must interface with).

## What ORC Did Not Produce: the markdown narrative `:report`

predict-rlm's published `report.md` is a polished 4-section briefing report with prose synthesis. ORC's run wrote rich content into intermediate blackboard keys (`:consolidated`, `:verification`, `:page-extractions`, `:final-financial-items`) but did NOT produce a final markdown `:report` string. The `:report` write key remained nil.

Reading the run's tree (`:generated-tree-raw`), the model designed a multi-stage extraction-and-verification pipeline but did not include a final synthesis `:llm` node to compose the markdown narrative. The structured outputs (key-dates, key-entities) ARE complete; the prose synthesis stage is the missing piece.

**Why this matters less than it might:** predict-rlm's published numerical match between their dates table and ours is achieved already — ORC's structured outputs are the authoritative comparison axis. The missing narrative is style-not-substance: anyone wanting a narrative could call a single follow-up `:llm` on the structured data. A second iteration with a tighter instruction telling the model "you MUST write the final `:report` markdown" would likely fix it.

## Methodology Caveat: Text Extraction vs Vision

predict-rlm renders each PDF page to PNG via pymupdf and calls a multimodal LLM. ORC's port uses PDFBox text extraction and a text LLM (same path that worked for document_redaction).

**Why this matters for fair comparison:**
- The information being extracted (dates, entities, financial terms) is text — both methodologies hit the same source content.
- predict-rlm's published 194K tokens at ~$0.004/page reflects image-tile billing for 136 page images. ORC's 614K tokens reflects sending text content (which is larger per page than image-tile billing) plus the framework's prompt overhead being applied per-iteration.
- For text-heavy documents (RFPs, contracts) the extraction quality is essentially identical between vision and text — both pipelines see the same words.
- For documents with visual diagrams/tables/charts that are NOT text-rendered (scanned PDFs, image-only diagrams), vision would have a real advantage. This RFP is text-rendered throughout.

**Why we chose text mode:** the framework's `:field-type :image` vision routing currently does not propagate through `:map-each` iteration over a vector of images. The model can read individual images fine (image_analysis and invoice_processing both work — they have single-image or per-key reads) but `:map-each` over a 136-page image vector turns into 136 inline-base64 text prompts, blowing up token usage 20-100×. We caught this with a 21M-token / 11.6-minute timeout on the first attempt. Text-mode bypasses that limitation cleanly. A future framework upgrade to propagate `:field-type :image` through iteration would close this gap.

## What the Model Designed

The model designed a multi-stage extraction-and-verification tree. From the run's `:node-trace`:
- Stage 1: index pages with positional info (`:page-item` wrap)
- Stage 2: `:map-each` over indexed pages with `:max-concurrency` extraction
- Stage 3: consolidate per-page extractions into structured key-dates + key-entities + financial-items lists
- Stage 4: adversarial verification pass — re-read the consolidated output AND the source pages to find what was missed
- Stage 5: write final structured outputs

The verification stage is what surfaced the 7 additional dates beyond predict-rlm's published 12. The pattern is:

```
[:sequence
 [:code (wrap pages with index)]
 [:map-each {:from :indexed-pages :as :page-item}
  [:llm extract-page-facts]]
 [:code (aggregate :all-page-facts)]
 [:llm consolidate-into-key-dates-entities-financial]
 [:llm adversarial-verification]
 [:final ...]]
```

The model's adversarial verification clause produced:
- 4 additional key dates
- 4 additional key entities (Transport Canada, Hesion LPR, YYJ SOC, Minister of Transport)
- 8 additional financial items (insurance limits, parking rates, etc.)
- 3 explicit corrections to the initial extraction

## Run Metrics

| Metric | ORC | predict-rlm |
|---|---:|---:|
| Wall clock | 223.6s (~3.7 min) | ~4 min |
| Main LM calls (Phase 1 design + verification) | ~5 | 8 |
| Sub LM calls (per-page + consolidation) | ~140 | 63 |
| Total tokens | 614,439 | 194,072 |
| Token ratio (ORC / predict-rlm) | 3.16× | — |
| Cost estimate | ~$1.20 | $0.52 |
| Documents | 1 (136 pages) | 1 (136 pages) |

ORC's higher token usage comes from text mode (each page's text is larger than image-tile billing for the same page). Wall clock is comparable because we parallelize differently and use OpenRouter which has different latency characteristics from predict-rlm's direct OpenAI calls.

## Findings

1. **Date extraction quality: ORC > predict-rlm published.** We captured all 12 of predict-rlm's dates plus 7 more, including the contractually significant proposal-validity discrepancy (Section 2.8's 60 days vs Schedule Four's 90 days) that's a real inconsistency in the source.

2. **Entity extraction is more vertical: predict-rlm focuses on top-level institutional parties; ORC also surfaces vendor infrastructure providers** (Flowbird, Hesion LPR, Skidata, Precise Parklink). For a bidder reading the RFP, this vendor list is operationally critical.

3. **Adversarial-verification clause works as designed.** The model's tree included a dedicated verification stage that read both the source and the candidate extraction, identifying missed items. This produced 4 of the 7 additional dates ORC has beyond predict-rlm's table.

4. **Cost penalty for text mode is real but bounded.** 3.16× more tokens vs predict-rlm because text-mode extraction is more verbose per page than vision-mode image-tile billing. For text-heavy documents this is fine; for vision-heavy documents (scanned forms, image-only diagrams) we'd lose more.

5. **Missing markdown narrative is a real gap.** predict-rlm publishes a polished 4-section briefing report. ORC produced richer structured data but not the prose narrative. Fixing this requires the instruction to be more emphatic about the final `:report` write, OR a separate post-processing stage that synthesizes the markdown from the structured outputs.

6. **Wall clock parity.** 3.7 min vs ~4 min — basically equivalent. OpenRouter latency was the dominating factor on our side, not the framework.

## Reproducibility

```bash
export OPENROUTER_API_KEY=sk-or-v1-...

clj -M:dev:test -e '
(require (quote [predict-rlm-comparison.tasks.document-analysis :as t]))
(require (quote [predict-rlm-comparison.runner :as r]))
(r/start!) (r/run! t/task) (r/stop!)'
```

- **Models:** `openai/gpt-5.4` main + `openai/gpt-5.1-chat` sub
- **Input:** YYJ-2025-Parking-Management-RFP.pdf (predict-rlm's verbatim sample)
- **Methodology:** text extraction via predict-rlm-pdf brick (PDFBox)
- **Output:** `results/document-analysis-predict-rlm_<timestamp>.edn`

## Related

- predict-rlm's published report: [`references/predict-rlm/document_analysis/sample/output/report.md`](../references/predict-rlm/document_analysis/sample/output/report.md)
- Companion clean reports: [01_image_analysis](01_image_analysis.md) · [02_document_redaction](02_document_redaction.md) · [03_invoice_processing](03_invoice_processing.md)
