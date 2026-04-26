# Bench comparison: predict-rlm vs orc Style A vs orc Style B

**Date:** 2026-04-26
**Models:** root `openai/gpt-5`, sub `openai/gpt-5-mini` (via OpenRouter)
**N:** 3 runs per (task × stack)
**max-iterations:** 15
**Prompt source:** verbatim DEFAULT_QUERY / CRITERIA from each `predict-rlm/examples/<task>/run.py`
**Total spend:** ~$4.20 (predict-rlm $3.57, orc-A $0.26, orc-B $0.40); document_analysis Style B aborted on credit exhaustion after the rest landed.

## Bottom line (revised)

The first round of this report misled us — my **bench wrapper had wrong input keys** (`:document-paths` instead of `:documents`, etc.), so orc workflows were getting nil inputs and reporting `:status :success` while doing nothing. After fixing the keys, **orc Style A actually does the work on 4 of 5 tasks** and is dramatically cheaper than predict-rlm. orc Style B is mixed: solid on extractive tasks (invoices, contract diff), still shortcuts on document_redaction, and crashed on the heaviest task due to credit exhaustion.

## Headline matrix

| Task | predict-rlm | orc Style A | orc Style B | Quality verdict |
|---|---|---|---|---|
| **image_analysis** | 2/3 produce full A–Z counts (T:155, total 1343) — $0.05–$0.25 | 2/3 produce A–Z counts but for **header text only** (T:11, total ~80) — $0.013 | 0/3 produce counts (rigid signature) — $0.011 | predict-rlm correct; Style A scoped wrong; Style B refused |
| **invoice_processing** | 4 invoices, totals $4086+$30717+$34804, 5–6 line items each — $0.075 | 5 "invoices" (over-splits 2-page invoice), same totals — $0.006 | 2 invoices (correct grouping), same totals — $0.022 | All three find the same money; differ only in row grouping |
| **document_redaction** | 70–200 redactions, 9 categories — $0.114 | **261–301 redactions**, 7–9 categories — $0.020 | 0/3 redactions (model still shortcuts) — $0.06–$0.14 | Style A produces MORE than predict-rlm; Style B broken |
| **contract_comparison** | 49–135 sub-calls (real diffs) — $0.413 | 4–15 diffs with full impact reasoning — $0.013 | 0–17 diffs, varying — $0.041 | Style A actually competitive on quality |
| **document_analysis** | 137–215 key dates, 108–556 entities, 25–33 KB report — $0.402 | 6–8 dates, 12–15 entities, 0.4 KB report — $0.024 | **3/3 ERR (credit exhaustion)** — $0 | predict-rlm dominant; Style A under-scoped |

---

## What changed since round 1

The original report flagged orc as "shortcutting work" on 4 of 5 tasks. Most of that was actually a bench wrapper bug:

```diff
;; bench/run_orc.clj — broken
-{:document-paths paths :criteria criteria}     ; pipeline reads :documents
-{:invoice-paths  paths}                         ; pipeline reads :invoices
-{:contract-paths paths}                         ; pipeline reads :contracts
;; fixed
+{:documents paths :criteria criteria}
+{:invoices  paths}
+{:contracts paths}
```

orc treated the missing keys as nil values, fed nil to LLM nodes, and the LLMs dutifully reported "no documents provided" while orc still reported `:status :success`. **The fact that orc didn't surface the missing-key as an error is itself a real gap** — silent success on missing reads is a footgun. (Followup #2.)

After the fix, the same orc workflows produce real output. The cheap-cost numbers are now meaningful:

- orc Style A invoice_processing: $0.006 / run (vs predict-rlm $0.075) — 12× cheaper, finds same money
- orc Style A document_redaction: $0.020 / run (vs predict-rlm $0.114) — 6× cheaper, MORE redactions
- orc Style A contract_comparison: $0.013 / run (vs predict-rlm $0.413) — 32× cheaper, real diffs with reasoning
- orc Style A document_analysis: $0.024 / run (vs predict-rlm $0.402) — 17× cheaper, but **much shallower** report

## Per-task observations (revised)

### image_analysis — orc scopes wrong, predict-rlm gets it right

predict-rlm hit reference letter counts on 2 of 3 runs (T:155, E:144, I:120, total 1343). orc Style A produces letter counts on 2 of 3 runs, but for ~30 characters from the image header only (T:11, total ~80). The Style A pipeline (`load-images → analyze-each → synthesize`) extracts a "concise observation" per image, not the full text — so the per-letter count is taken over the observation string, not the visible image content. Architectural mismatch between the pipeline shape and the predict-rlm prompt's "count letters in the entire image" requirement.

orc Style B produces 0 letter counts across 3 runs. The signature-strategy still forces "load + predict-all + synthesize" with no slot for "extract N times for self-consistency, count programmatically." This is the rigidity finding from round 1, unchanged by the input-key fix.

### invoice_processing — three stacks agree on the money, disagree on row grouping

All three stacks correctly find Acme Corporation + GlobalTech Solutions Ltd. and the same dollar totals: $4086.40, $30717.90, $34804.30. They differ on how to group multi-page invoices into rows:

- predict-rlm: 4 invoice records (header + continuation handled correctly)
- orc Style A: 5 invoice records (over-splits — counts each page as an invoice)
- orc Style B: 2 invoice records (one per PDF, ignores page boundaries)

Cost-quality tradeoff is genuine: Style B at $0.022 produces the cleanest invoice grouping; Style A at $0.006 produces the most noise but lowest cost. Predict-rlm at $0.075 is the most accurate but 12×–4× more expensive depending which orc style you compare to.

### document_redaction — Style A overfinds, Style B refuses

predict-rlm: 70–200 redactions per run, 9 categories. Real PII surfaced.

orc Style A: 261–301 redactions per run, 7–9 categories. **More than predict-rlm.** Style A's pipeline is LLM-driven — the per-doc `find-targets-for-doc` LLM is being aggressive (or the prompt rewards over-flagging). 6× cheaper than predict-rlm but with output that needs human triage. The 1 redacted PDF was produced (Style A `apply-redactions` code node ran).

orc Style B: 0 redactions across 3 runs (1 ERR). The model called `final!` with `{:redacted-documents {:total-redactions 0 :targets []}}` — same shortcut behavior as round 1. The fix didn't help here because the input wasn't the issue; the prompt + model combination keeps producing empty `final!` calls.

### contract_comparison — Style A actually competitive

predict-rlm: 49–135 sub-calls per run, $0.41 average. Real diffing work, but my wrapper's structured-output capture is broken for this task (returns bare Pydantic), so we can't see what it actually produced.

orc Style A: 4–15 diffs per run with detailed `significance` strings:
> "Major — removes a material supplier obligation and a basis for OPA to collect/verify domestic content compliance under the contract. Affects eligibility/compliance enforcement and any supplier assurances tied to domestic content."

That's real legal-style impact reasoning, not a shortcut. **$0.013/run** — 32× cheaper than predict-rlm. With predict-rlm's structured output unrecoverable, we can't directly compare the diff sets, but the quality of orc Style A's individual diffs is high.

orc Style B: 0–17 diffs, varying quality (one run produced 17 with no significance details, another produced 0). Higher variance than Style A.

### document_analysis — predict-rlm wins by a lot

This is where predict-rlm's depth matters most.

predict-rlm: 137–215 key dates extracted, 108–556 named entities, 25–33 KB markdown report covering the 136-page RFP. $0.40 average — real money for real work.

orc Style A: 6–8 key dates, 12–15 entities, **0.4 KB report**. The pipeline (`survey → summarize-each → synthesize`) compresses each document to a single LLM-summarized paragraph and then synthesizes from those compressed summaries, so depth is bounded by the summarize step's quality. Cheap ($0.024) but shallow.

orc Style B: 3/3 ERR — credit exhaustion ("can only afford 2557 tokens"). The repl-researcher tried to budget for max_tokens larger than what the OpenRouter balance allowed, so `dscloj/predict` rejected the call. Could complete with more budget.

---

## Revised cost roll-up

| Stack | Total spend | Notes |
|---|---|---|
| predict-rlm | $3.57 | 15 runs, all real work, all completed |
| orc Style A (re-run with fixed keys) | $0.26 | 15 runs, real work on 4 of 5 tasks |
| orc Style B (re-run with fixed keys) | $0.40 | 12 ok + 3 ERR (document_analysis credit exhaustion) |
| **Total** | **$4.23** | |

Per-stack medians (across all 5 tasks):

| Stack | Median per-run cost | Total wall-clock for sweep |
|---|---|---|
| predict-rlm | $0.13 | ~90 min (sequential, JVM-free) |
| orc Style A | $0.013 | ~22 min (sequential, JVM startup × 5) |
| orc Style B | $0.022 | ~13 min (sequential, JVM startup × 5) |

orc is **5–32× cheaper per run** on tasks where it can actually do the work. On document_analysis (the heaviest task) Style A is shallow enough that the cost-quality comparison favors predict-rlm; on invoice_processing and contract_comparison Style A is genuinely competitive.

## Followups (revised)

1. ✅ **Fixed orc bench input keys.** Real comparison data now flows. (was followup #1)
2. **orc should error on missing reads.** Silent nil-substitution lets `:status :success` ship empty output. Either fail-fast at workflow build (validate `:reads` against blackboard schema) or fail at execute time when a read returns nil for a non-Optional field.
3. **Fix predict-rlm wrapper for bare-Pydantic returns** (contract_comparison output still missing). One-liner in `_structured_result` to handle `BaseModel` instances.
4. **Persist `:iterations` to node-completion event in orc.** `executor.clj:1283` builds `{:code :result :stdout}` history per iteration but only attaches to the in-memory result. Adding it to the event payload means trace.edn can show what code the model wrote. Tracked as task #27.
5. **orc Style B `final!`-with-empty-data shortcut on document_redaction.** Same model + prompt + everything else worked on invoice_processing. Either the redaction signature-strategy is mis-tuned for `gpt-5`, or the redaction sandbox tools (`pdf/search-text`, `pdf/redact-rects`) need better discoverability in the prompt.
6. **orc Style B max_tokens budgeting.** `dscloj/predict` requested up to 3349 max_tokens for the document_analysis root call — that's only enough for a few KB of output. Smaller `max_tokens` would either succeed within budget or surface earlier. Worth investigating regardless of OpenRouter balance.
7. **Parallel orc bench (task #28).** Single long-lived JVM, semaphore-bounded concurrent ticks. Drops orc sweep from 22+13 = 35 min to ~5–10 min and avoids the JVM-restart-per-task waste.
8. **LLM-as-judge for prose tasks** (document_analysis report quality, contract_comparison diff completeness). Structural metrics tell us "did work happen"; can't tell us "is the answer good."

## What this proved about the comparison framework

- **Cost-only metrics are dangerous when one stack might shortcut.** The only thing that saved the round-1 report from being completely wrong was building structural extractors per task (`compare.py`). Without them, "orc is 100× cheaper" would have been the headline.
- **Per-stack output extractors caught both bugs**: orc Style A doing nothing (round 1) and orc Style A doing the work but at insufficient depth (round 2). Two qualitatively different failure modes, both invisible to cost+duration.
- **Variance is high.** predict-rlm image_analysis ranged $0.05–$0.25 (5×); document_redaction ranged $0.09–$0.36 (4×). N=3 is the floor for variance estimation; N=5 would be cleaner but the per-task token cost makes it expensive.
