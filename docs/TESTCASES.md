# Bench test cases — what each task asks and how each stack approaches it

This is the **fairness audit** for the cross-stack benchmarks reported in `bench/REPORT.md` and Part 9 of `RLM-DEEP-ANALYSIS.md`. It documents, per task and per stack:

- the literal input
- the schema/constraint the output must satisfy
- the _prompt_ — what the user-facing query/criteria says
- the _signature/strategy_ — what the implementation tells the LLM about how to approach it
- the **pre-knowledge level** the implementation injects (skeleton code, tool list, anti-pattern warnings, etc.)
- the model assignments per node (root vs sub) and why

The point is that "predict-rlm vs orc Style A vs Style B vs Legacy vs Driver" only means something if all five face the _same task_ with _comparable freedom_. Where the freedoms differ — and they do, in important ways — this doc names the difference so the bench numbers are read in context.

---

## How to read the comparisons

Each task section below has the same structure:

1. **Input** — what file(s), how big, what page count
2. **Output schema** — Pydantic / Malli shape the answer must satisfy
3. **User prompt** — the verbatim DEFAULT_QUERY / CRITERIA from `predict-rlm/examples/<task>/run.py`. **All five stacks face this exact prompt** (orc bench substitutes it via `bench/predict_rlm_prompts.edn`).
4. **Per-stack signature/strategy** — the implementation-level instruction the LLM sees, with notes on:
   - **Pre-knowledge** — does the prompt show working code? Hand-extract examples? Tool docstrings?
   - **Constraint** — does the prompt forbid specific Clojure constructs? Java interop? Parser functions?
   - **Submit mechanism** — `final!` (RLM), FINAL_ANSWER text marker (Legacy), `:writes` declaration (Style A nodes), or DSL emission (Driver)
5. **Comparable axis** — what the bench actually measures across stacks for this task

---

## The five stacks under test

| Stack           | What it is                                                                                                                                                                                                                                    | Reliability ceiling                     | Cost per task                                                   | Token cost                        |
| --------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------- | --------------------------------------------------------------- | --------------------------------- |
| **predict-rlm** | Trampoline AI's Python RLM runtime — `dspy.Signature` + `dspy.RLM` running in Pyodide+Deno. Reference implementation.                                                                                                                         | 100% on N=3 across all 5 tasks          | Highest — $0.07–$0.53 per run                                   | Highest — 50K–800K tokens per run |
| **orc Style A** | Hand-coded behavior tree (`pipeline.clj`). Each phase is an explicit node: `(code …)`, `(map-each …)`, `(llm …)`, `(code …)`. Per-node instruction is GEPA-optimizable.                                                                       | 100% on N=3 across all 5 tasks          | Lowest — $0.005–$0.032 per run (8–33× cheaper than predict-rlm) | Lowest — 4K–25K tokens            |
| **orc Style B** | RLM-faithful (`agentic.clj`). Single `repl-researcher` node with `:rlm true`. LLM emits Clojure code in SCI sandbox, with `predict` / `predict-all` / `final!` host primitives.                                                               | 100% on N=3 across all 5 tasks          | Mid — $0.012–$0.057 per run (3–9× cheaper than predict-rlm)     | Mid — 7K–35K tokens               |
| **orc Legacy**  | Pre-RLM repl-researcher (`legacy.clj`, no `:rlm` config). Single LLM iterating in SCI sandbox with `:mcp-tools`, no sub-LLM calls, FINAL_ANSWER text-marker submit.                                                                           | 67% on N=3, only 2 of 5 tasks attempted | Low — $0.021–$0.055                                             | Mid — 10K–23K tokens              |
| **orc Driver**  | Cameron's workflow-driver agent. The LLM's per-turn output IS a complete `(workflow …)` DSL form. Multi-turn loop: observe → propose → submit → eval → decide. Operates on a target Sheet with an eval-set + judges. Brand new in this round. | TBD (round-6)                           | TBD                                                             | TBD                               |

---

## Methodology

### What the bench actually compares

For the four "execute the task" stacks (predict-rlm, Style A, Style B, Legacy), the bench measures:

- **Reliability** — `n_ok / n_runs`. A run is "ok" if the workflow completed and produced a non-empty structured result matching the output schema.
- **Cost** — sum of (root_tokens × root_price) + (sub_tokens × sub_price) per run. Token prices come from `bench/price_table.edn`, calibrated to LiteLLM's per-Mtok rates for OpenAI's gpt-5 family.
- **Tokens** — input + output across root + sub LLM calls. Captured from each stack's native telemetry (predict-rlm via `RunTrace`, orc via `:sheet/node-execution-completed` events with `:usage`).
- **Wall-clock** — duration from dispatch to result.
- **Per-task structural metrics** — task-specific. For invoice_processing: `invoices_found`, `vendor_names`, `line_item_counts`, `totals_seen`. For document_redaction: `redactions_total`, `redactions_by_category`, `produced_redacted_pdfs`. See `bench/compare.py :: EXTRACTORS`.

For the Driver stack the question is different — see the **Driver-recovery measurement** section at the end.

### Models

All stacks run with the same model pair in every round-5+ sweep:

- **Root** (orchestrator / single-model on Legacy / root-LM on Style B): `openai/gpt-5`
- **Sub** (per-page extraction / `predict-all` items / Style A `(llm …)` nodes): `openai/gpt-5-mini`

The bench's `bench/run_orc.clj :: assert-allowed-model!` walks every workflow before execution and refuses to run if any node references gpt-4 / gpt-4o / gpt-4o-mini / gpt-4-turbo / gpt-3.5-turbo, or if any node has a nil model (which would route to provider default — `litellm-router/setup-openai!` defaults to `gpt-4o-mini`). With `OPENAI_API_KEY` unset (only `OPENROUTER_API_KEY` set), `setup-openai!` doesn't fire. This is the GPT-4 audit.

predict-rlm passes `--model openrouter/openai/gpt-5 --sub-lm-model openrouter/openai/gpt-5-mini` via flags to its own `bench/run_predict.py` wrapper.

### `:max-iterations 15`

All stacks that have an iteration loop (Style B repl-researcher, Legacy repl-researcher, predict-rlm RLM, Driver loop) are capped at 15 iterations. This cap exists because earlier rounds saw a 19-minute pathological loop on image_analysis predict-rlm — without a cap, the bench is at the mercy of model-side convergence detection. 15 is enough for any task we've measured to reach `final!` / FINAL_ANSWER if the model is going to.

---

## Task 1 — image_analysis

### Input

| Property | Value                                                                                     |
| -------- | ----------------------------------------------------------------------------------------- |
| File     | `Screenshot 2026-04-02 at 12.16.21 PM.png`                                                |
| Size     | 510,990 bytes                                                                             |
| Pages    | n/a (single image)                                                                        |
| Subject  | First page of an Ontario microFIT contract (logo, headers, dense paragraphs, form blanks) |
| Format   | PNG, ~1100 chars of visible text after OCR                                                |

### Output schema

```python
class AnalyzeImages(dspy.Signature):
    images: list[File] = dspy.InputField(desc="Image files to analyze (PNG, JPG, WEBP)")
    query: str = dspy.InputField(desc="A question about the images")
    answer: str = dspy.OutputField(desc="The answer to the query")
```

Single string answer. No structured shape — quality is judged by content.

### User prompt (DEFAULT_QUERY, all stacks)

> What letters appear in each image, and how many times does each letter appear? Always include: logo text, header address/phone/fax, header email, header website URL, "Page N" footers, etc.
>
> For each image:
>
> 1. Extract the visible text multiple times (at least 2-3 extractions per image)
> 2. Compare the extractions - if they differ, extract again until you get consistent results
> 3. Only after you have consistent text extraction, count the letters programmatically (case insensitive)
>
> Use prompts like "Return ONLY the exact text visible, nothing else."
> Do all counting and comparison in Python, not via predict().
>
> Treat uppercase and lowercase as the same letter (case-insensitive).
> Output the letter statistics in alphabetical order (A-Z).

This query is engineered to require **N-extraction self-consistency** (vision is unreliable) followed by **programmatic counting** (LLMs miscount). It's the cleanest "is the framework actually doing the work" probe in the suite.

### Per-stack approach

| Stack           | Implementation file                        | Pre-knowledge given to LLM                                                                                                                                                                                                                                                                            | Sub-LLM calls allowed?                                               |
| --------------- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| **predict-rlm** | `examples/image_analysis/signature.py`     | 4-step open prompt: list files → load as base64 → use `predict()` with `dspy.Image` → synthesize. **No code skeleton.** Trusts the model to write Python that calls `predict()` itself.                                                                                                               | ✅ via `predict()` host fn                                           |
| **orc Style A** | `examples/image_analysis/.../pipeline.clj` | `(sequence (code "load-images") (map-each "analyze-each" (llm "analyze-one")) (llm "synthesize"))`. The "load images as base64 URI" step is pre-built as a `(code …)` node — no LLM involved. The per-image observation and final synthesis are LLM calls. **Static structure, two-LLM-call answer.** | ✅ via the `(map-each (llm))` and `(llm "synthesize")` nodes         |
| **orc Style B** | `examples/image_analysis/.../agentic.clj`  | Sandbox warning + `image/load-data-uri` MCP tool docstring + `predict` / `predict-all` / `final!` primitives + a **complete copy-paste skeleton** (130+ lines) showing exactly the load-uris → predict-all → synthesize → final! plan.                                                                | ✅ via `predict-all`                                                 |
| **orc Legacy**  | n/a                                        | Vision sub-call requires a multimodal LLM call from inside the iteration loop. Legacy mode has no `predict` host fn — single-model context only. **Architectural N/A**.                                                                                                                               | ❌ — no sub-LLM call available                                       |
| **orc Driver**  | (round-6 measurement)                      | Driver mutates the BT itself. For image_analysis it would emit a corrected pipeline.clj-shaped form.                                                                                                                                                                                                  | ✅ as a side effect of emitting nodes that include `(predict-all …)` |

### Pre-knowledge analysis

| Stack       | Knows the answer shape?                      | Knows specific tools?                        | Has worked code skeleton?       | Forbids specific patterns?                              |
| ----------- | -------------------------------------------- | -------------------------------------------- | ------------------------------- | ------------------------------------------------------- |
| predict-rlm | ✅ via Pydantic                              | ✅ `dspy.Image`, `predict`                   | ❌                              | ❌                                                      |
| Style A     | ✅ via Malli + node decomposition            | ✅ pre-wired in code nodes                   | n/a (humans wrote the BT)       | n/a                                                     |
| Style B     | ✅ via Malli                                 | ✅ explicit MCP tool list + sandbox bindings | ✅ ~70-line copy-paste skeleton | ✅ "no Java interop", "no `(ns …)`", "no nested `#(…)`" |
| Legacy      | n/a                                          | n/a                                          | n/a                             | n/a                                                     |
| Driver      | (varies — Driver edits an existing pipeline) | (varies)                                     | (varies)                        | (varies)                                                |

### Comparable axis for this task

- Did the answer include all 26 letter counts in alphabetical order?
- Do the counts match the ground-truth? Reference counts (verified against the source image): `A:110, B:14, C:77, D:55, E:144, F:33, G:12, H:45, I:120, J:0, K:0, L:50, M:23, N:106, O:96, P:52, Q:0, R:101, S:73, T:155, U:33, V:6, W:16, X:1, Y:20, Z:1`, total 1,343.
- Did the implementation actually do the self-consistency loop the query asks for, or did it skip to a single extraction?

This task isolates **whether the framework forces the LLM to follow user-specified rigor**. A purely permissive framework (orc Style B) lets the model honor the signature-strategy's implicit shape over the user query's explicit demands.

---

## Task 2 — invoice_processing

### Input

| Property                | Value                                                            |
| ----------------------- | ---------------------------------------------------------------- |
| Files                   | `acme-invoice-2025-0042.pdf` + `globaltech-invoice-GT-10587.pdf` |
| Sizes                   | 9,312 + 10,064 bytes                                             |
| Pages                   | 1 + 1 = 2 total                                                  |
| Vendors                 | Acme Corporation, GlobalTech Solutions Ltd.                      |
| Ground-truth totals     | $4,086.40 (Acme) + $30,717.90 (GlobalTech) = $34,804.30          |
| Ground-truth line items | 5 (Acme) + 6 (GlobalTech) = 11                                   |

### Output schema

```python
class InvoiceExtractionResult(BaseModel):
    invoices: list[Invoice]
    total_amount: float
    summary: str

class Invoice(BaseModel):
    vendor_name: str
    invoice_number: str
    date: str
    due_date: str
    subtotal: float
    tax: float
    total: float
    line_items: list[LineItem]

class LineItem(BaseModel):
    description: str
    quantity: float
    unit_price: float
    amount: float
```

Plus a **`File` output**: `workbook` — the resulting `invoice_extraction.xlsx`. predict-rlm collects this from `/sandbox/output/<field>/`; orc collects via the `xlsx/write-workbook` skill writing to `/tmp`.

### User prompt

`predict-rlm/examples/invoice_processing/run.py` doesn't have a `CRITERIA` constant — the schema and the signature docstring carry all the constraint. So all stacks face the **bare schema** as the constraint, no extra natural-language hints about field meaning.

### Per-stack approach

| Stack           | Implementation                                                                                                                                                                                                                                                                                                                                                                             | Pre-knowledge                                                                                                         | Sub-LLM calls                            |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| **predict-rlm** | `signature.py` 5-step plan: survey → render each page → predict() per page → assemble result → save .xlsx                                                                                                                                                                                                                                                                                  | Schema in Pydantic; signature docstring describes the plan in prose                                                   | ✅ via `predict()` per page              |
| **orc Style A** | `pipeline.clj`: `(code "explode-pages") → (map-each (llm "extract-invoice-page")) → (code "merge-invoices") → (code "build-workbook")`. Per-page extraction is one LLM call per page (2 pages = 2 LLM calls). Merge + workbook build are pure code.                                                                                                                                        | Page extraction is a tight single-purpose LLM call; merge + workbook are deterministic                                | ✅ 2 sub-LLM calls (one per page)        |
| **orc Style B** | `agentic.clj` ~220-line signature-strategy. **Full copy-paste skeleton** showing `predict-all` over pages with the `[:map …]` schema inline, header-vs-continuation logic for multi-page invoices, `xlsx/write-workbook` call, `(final! …)` shape.                                                                                                                                         | Sandbox warnings + MCP tool list + per-tool arg-key warnings ("⚠ keys MUST be :out-path and :sheets-spec") + skeleton | ✅ via `predict-all` over pages          |
| **orc Legacy**  | `legacy.clj`: same input/criteria but **no `predict-all`** — model must read PDF text via `pdf/document-text`, parse it in its own context across iterations, build invoices vector by hand, write workbook, emit `FINAL_ANSWER`. **"Hand-extract, don't write a parser"** is the load-bearing instruction; without it the model writes regex parsers with placeholder patterns and fails. | Sandbox warnings + tool list + 3-iteration plan: read → transcribe → write workbook & submit                          | ❌ — single model context only           |
| **orc Driver**  | (round-6) Would observe the existing Style A pipeline and emit a corrected version if it's degraded. Demoed by Cameron on document_analysis; same pattern would apply here.                                                                                                                                                                                                                | Driver sees the current Sheet's DSL form + recent ticks + judge scores                                                | (whatever the emitted DSL form contains) |

### Pre-knowledge analysis

| Stack       | Knows the answer shape?                                 | Knows specific tools?                                                    | Has worked code skeleton?                                                                                  | Forbids specific patterns?                                                      |
| ----------- | ------------------------------------------------------- | ------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| predict-rlm | ✅ Pydantic                                             | ✅ `predict`, `dspy.Image`, `openpyxl`, `pandas` (all from skill bundle) | ❌ — open prose plan                                                                                       | ❌                                                                              |
| Style A     | ✅ Malli + nodes                                        | ✅ pre-wired in code nodes                                               | n/a (humans wrote the BT)                                                                                  | n/a                                                                             |
| Style B     | ✅ Malli                                                | ✅ explicit `pdf/*` + `xlsx/*` MCP tool list                             | ✅ ~150-line skeleton including `(let [pages …] …)`, `predict-all`, `loop`/`recur` for header continuation | ✅ "NO Java interop", "NO unsafe fns", "DO NOT call (final! …) with empty data" |
| Legacy      | ✅ Malli                                                | ✅ tool list                                                             | ✅ "iteration plan" with read → transcribe → submit pseudo-code                                            | ✅ Java interop, ns/require, parser functions with placeholder regex            |
| Driver      | ✅ Malli (it sees the target Sheet's blackboard schema) | ✅ via the Sheet snapshot                                                | n/a (Driver reads the existing tree, not a skeleton)                                                       | n/a                                                                             |

### Comparable axis

All four "execute" stacks (round-5 result):

- **All find the correct vendors**: Acme + GlobalTech
- **All find the correct totals**: $4,086.40 + $30,717.90 + $34,804.30 aggregate
- **predict-rlm and Style B produce identical line-item counts**: [5, 6]
- **Style A occasionally produces [5, 7]** because it splits the 10% discount line
- **Legacy succeeds on 2/3 runs** (architectural ceiling) with the same [5, 6] counts when ok

This task is the **best apples-to-apples**: deterministic ground truth, all stacks reach it. The cost-quality picture (Style A 14× cheaper, Legacy ~2.5× cheaper, Style B 3.4× cheaper than predict-rlm) is the headline.

---

## Task 3 — document_redaction

### Input

| Property | Value                                                                                 |
| -------- | ------------------------------------------------------------------------------------- |
| File     | `PNFS-Employment-Agreement-2025.pdf`                                                  |
| Size     | 39,615 bytes                                                                          |
| Pages    | 6                                                                                     |
| Content  | Dense PII — names, SINs, addresses, emails, phone numbers, financial info, signatures |

### Output schema

```python
class RedactionResult(BaseModel):
    total_redactions: int
    page_summaries: list[PageSummary]
    targets: list[RedactionTarget]

class PageSummary(BaseModel):
    page: int
    redaction_count: int
    categories: list[str]

class RedactionTarget(BaseModel):
    page: int
    text: str        # exact substring to redact (must literally appear in page text)
    category: str
    reason: str
```

Plus `redacted_documents: list[File]` — the actual redacted PDFs.

### User prompt (CRITERIA, all stacks)

> Redact all personally identifiable information (PII), including:
>
> 1. **Names** — Full names of individuals (not company or organization names)
> 2. **Contact info** — Phone numbers, email addresses, fax numbers
> 3. **Addresses** — Street addresses, P.O. boxes (not city/state/country)
> 4. **Government IDs** — Social security numbers, tax IDs, passport numbers
> 5. **Financial info** — Bank account numbers, credit card numbers, routing numbers
> 6. **Signatures** — Handwritten signatures (redact the bounding area)
>
> Added to this, redact any dates found in the document, in any format.

### Per-stack approach

| Stack           | Implementation                                                                                                                                                                                                                                               | Pre-knowledge                                                                                                    | Sub-LLM calls                                             |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| **predict-rlm** | `signature.py` 6-step plan: read criteria → survey → inspect each page visually → apply redactions → verify by re-rendering → save                                                                                                                           | Skill bundle gives `pymupdf` (search_for, add_redact_annot, apply_redactions). Verification via vision sub-call. | ✅ many `predict()` calls per doc (vision + verification) |
| **orc Style A** | `pipeline.clj`: `(code "survey") → (map-each "find-targets" (llm "find-targets-for-doc")) → (code "apply-redactions")`. One LLM call per doc to identify targets; pure-code search-rect-apply afterward.                                                     | Same `pdf/*` skill (search-text, redact-rects). No vision verification step in the BT.                           | ✅ 1 sub-LLM call per doc                                 |
| **orc Style B** | `agentic.clj` (rewritten in round-3) — full copy-paste skeleton with `pdf/page-count → pdf/page-text → predict-all over docs → search-text → redact-rects → final!`. Same anti-shortcut warnings as invoice_processing's Style B.                            | Sandbox warnings + `pdf/*` + `redaction.md` skill instructions + skeleton                                        | ✅ via `predict-all` over docs                            |
| **orc Legacy**  | `legacy.clj` (rewritten in round-4 hill-climb) — multi-iteration plan: read all pages → hand-identify PII targets → search-text per target → redact-rects → FINAL_ANSWER. Critical anti-pattern: "DO NOT submit < 30 entries — partial coverage is failure." | Tool list + iteration plan + page-coverage warning                                                               | ❌ — single model identifies all PII in own context       |
| **orc Driver**  | (round-6)                                                                                                                                                                                                                                                    | (varies)                                                                                                         | (varies)                                                  |

### Pre-knowledge analysis

| Stack       | Knows the answer shape? | Knows specific tools?                                        | Has worked code skeleton?                  | Forbids specific patterns?                                          |
| ----------- | ----------------------- | ------------------------------------------------------------ | ------------------------------------------ | ------------------------------------------------------------------- |
| predict-rlm | ✅ Pydantic             | ✅ skill includes `pymupdf` + verification module            | ❌ — open prose plan with verify-loop hint | ❌                                                                  |
| Style A     | ✅ Malli + nodes        | ✅ pre-wired in `apply-redactions` code node                 | n/a                                        | n/a                                                                 |
| Style B     | ✅ Malli                | ✅ pdf + redaction MCP tools, "covers all pages" instruction | ✅ ~100-line skeleton                      | ✅ Java interop, parser-function templates                          |
| Legacy      | ✅ Malli                | ✅ pdf MCP tools                                             | ✅ iteration-plan pseudo-code              | ✅ Java interop, ns/require, nested `#(…)`, < 30-target submissions |
| Driver      | ✅ via Sheet snapshot   | ✅ via Sheet                                                 | n/a                                        | n/a                                                                 |

### Comparable axis

- **Total redactions found** (predict-rlm: 70–100; Style A: 65–75; Style B: 68–73 when ok; Legacy: 23–26 when ok with 4-of-6-page coverage)
- **Categories covered** (predict-rlm: 9; others: 6–10)
- **Did the implementation produce a redacted PDF?** All stacks except some Legacy runs do.
- **Reliability** (Style A/B/predict: 100%; Legacy: 67% — the architectural ceiling)

The Legacy depth-vs-coverage finding is the most diagnostic: predict-rlm/Style A/Style B can fan out their attention across 6 pages with sub-calls; Legacy's single context covers ~4 pages reliably and stops.

---

## Task 4 — contract_comparison

### Input

| Property | Value                                                                       |
| -------- | --------------------------------------------------------------------------- |
| Files    | `microFIT-Contract-Version-2-0.pdf` + `microFIT-Contract-Version-3-1-1.pdf` |
| Sizes    | 215,980 + 350,971 bytes                                                     |
| Pages    | 23 + 22 = 45 total                                                          |
| Subject  | Two versions of the Ontario microFIT contract                               |

### Output schema

```python
class ComparisonResult(BaseModel):
    report: str  # markdown
    section_diffs: list[SectionDiff]
    key_differences: list[KeyDifference]

class SectionDiff(BaseModel):
    section: str
    significance: Literal["major", "minor", "identical"]
    summary: str
```

### User prompt

No CRITERIA constant — the comparison protocol is baked into the signature docstring. All stacks face the same "compare these two contracts and produce a structured diff report" task.

### Per-stack approach

| Stack           | Implementation                                                                                                                                                                         | Pre-knowledge                                                    | Sub-LLM calls                                       |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- | --------------------------------------------------- |
| **predict-rlm** | `signature.py` open prompt: relate sections, classify significance, surface impact. The model invents the per-section comparison loop.                                                 | Pydantic schema + open prose                                     | ✅ many `predict()` calls (49–135 per run observed) |
| **orc Style A** | `pipeline.clj`: `(code "survey") → (map-each "summarize-each" (llm "summarize-doc")) → (llm "compare")`. Each doc is summarized once, then the synthesizer compares the two summaries. | Doc summaries are bounded; comparison is a single synthesis call | ✅ 2 doc summaries + 1 comparison = 3 LLM calls     |
| **orc Style B** | `agentic.clj`. Open prompt — no copy-paste skeleton (one of the two example agentic.clj files that doesn't have one).                                                                  | sandbox warnings + tools                                         | ✅ via `predict-all`                                |
| **orc Legacy**  | not implemented — large input (45 pages) infeasible for single-context coverage                                                                                                        | n/a                                                              | n/a                                                 |
| **orc Driver**  | (round-6)                                                                                                                                                                              | (varies)                                                         | (varies)                                            |

### Comparable axis

predict-rlm wrapper bug (returning bare Pydantic) means we can't recover predict-rlm's structured `report` field directly — only token usage and call counts. So this task's quality comparison is partial:

- **Style A** produces 4–15 detailed section-diffs per run with significance reasoning and impact analysis (~$0.013/run, 25K tokens)
- **Style B** produces 0–17 diffs with much higher variance in detail
- **predict-rlm** does the work (49–135 sub-calls) but the structured output is unrecoverable until the wrapper bug is fixed

The token cost spread is huge: predict-rlm uses 398K tokens median per run on this task; Style A uses 25K. **16× difference.**

---

## Task 5 — document_analysis

### Input

| Property | Value                                                          |
| -------- | -------------------------------------------------------------- |
| File     | `YYJ-2025-Parking-Management-RFP.pdf`                          |
| Size     | 5,379,910 bytes (5.1 MB)                                       |
| Pages    | 136                                                            |
| Subject  | Victoria Airport Authority RFP for Parking Management Services |

### Output schema

```python
class DocumentAnalysis(BaseModel):
    report: str         # markdown briefing report
    key_dates: list[KeyDate]
    key_entities: list[KeyEntity]
```

Plus `docx_report: File` — the report rendered to .docx via `docx/write-markdown-as-docx`.

### User prompt (CRITERIA, all stacks)

A 4-section briefing-report template covering Executive Summary, Key Dates and Timeline, Key Entities and Stakeholders, Financial Information, with formatting guidelines ("favor prose over bullets", "no page references", etc.). ~30 lines of structured criteria.

### Per-stack approach

| Stack           | Implementation                                                                                                                                                                                                                                                                                               | Pre-knowledge                                        | Sub-LLM calls                                       |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------- | --------------------------------------------------- |
| **predict-rlm** | `signature.py` open plan: survey → page-by-page extraction → synthesis → render docx. The criteria is appended to the signature's instruction at runtime (`AnalyzeDocuments.with_instructions(...)`).                                                                                                        | Pydantic + skill bundle + dynamic criteria injection | ✅ 60–124 sub-calls per run on the 136-page input   |
| **orc Style A** | `pipeline.clj`: `(code "survey") → (map-each "summarize-each" (llm "summarize-doc")) → (llm "synthesize") → (code "render-docx")`. Each document gets ONE summary call regardless of page count. The 136-page RFP becomes one ~2K-token summary input to the synthesizer. **Pipeline shape caps the depth.** | Per-doc summary + synthesis                          | ✅ 2 LLM calls total (summarize 1 doc + synthesize) |
| **orc Style B** | `agentic.clj` — same skeleton family as invoice_processing but with `pdf/page-image-data-uri` for vision-grade extraction                                                                                                                                                                                    | Skeleton + tools                                     | ✅ via `predict-all` over pages                     |
| **orc Legacy**  | not implemented — 136 pages exceed single-context attention                                                                                                                                                                                                                                                  | n/a                                                  | n/a                                                 |
| **orc Driver**  | **The validated case from Cameron's demo.** Driver observes a degraded Style A pipeline (synthesize node weakened), emits a corrected workflow form, eval recovers 0.85 → 0.90, commit-version! publishes v1.                                                                                                | Sees current Sheet + recent ticks + judge scores     | (whatever the emitted DSL contains)                 |

### Comparable axis

This task is the **most architecturally diagnostic** in the suite:

- **predict-rlm**: 25–33 KB report, 137–215 key dates, 108–556 entities. Cost: $0.46–$0.82/run; tokens 706K–1.2M.
- **Style A**: 0.4 KB report, 6–8 key dates, 12–15 entities. Cost: $0.016/run; tokens 15K. **60× shallower** — pipeline shape caps depth at 1 summary call per doc.
- **Style B**: ~$0.057/run, 35K tokens. Faster wall-clock (23s vs 166s).
- **Driver**: addresses the Style A depth ceiling by emitting workflow forms with deeper structure (e.g., per-page extraction node instead of per-doc summary).

This is the task where "structure beats freedom" (Style A < predict-rlm on quality even though much cheaper) AND where the Driver concept is most clearly motivated.

---

## Driver-recovery measurement (round-6)

The four execute stacks all run "task → answer." The Driver runs "degraded workflow → improved workflow." Different output shape, different metric.

### Bench cell shape for the Driver

For each task that has a Style A pipeline (all 5 except where Legacy was N/A):

1. **Take the pristine Style A pipeline.**
2. **Mechanically degrade one node** — defined per-task in `bench/degradations.edn`. Examples:
   - document_analysis: replace `synthesize` node's instruction with `"Output a brief summary."` AND drop `:criteria` from `:reads`.
   - invoice_processing: replace `extract-invoice-page` node's instruction with `"Read the page."`.
   - document_redaction: replace `find-targets-for-doc` node's instruction with `"Find PII."`.
3. **Run the eval-set** against the degraded pipeline to baseline the regression (judge scores drop).
4. **Hand the degraded sheet to `driver/run-driver-loop!`** with the same eval-set, judges `[:grounding :completeness]`, and an objective stating which node is weak.
5. **Measure**:
   - Did the driver publish? (`publish!` succeeded → `:status :ok`)
   - How many turns?
   - Total cost across all turns
   - Final eval score
   - Final published version's diff against original (which nodes were modified?)
6. **Stack tag in `run.json`**: `orc-driver-recovery`

This isn't comparable to "did the workflow produce the right answer" — it's "can the driver recover a regressed workflow." Both questions matter.

### Crossover analysis (deferred)

The headline "is driver mode worth its overhead" requires a separate experiment:

- Run driver once → publish v1 → measure `(driver upfront cost)`
- Run v1 N times → measure `N × (Style A cost)`
- Compare to `N × (predict-rlm cost)`
- Crossover N where the driver investment pays off

This is the **amortized Style A** measurement and is more complex to set up. Tracked as followup.

---

## Per-task ground truth (ergonomic reference)

| Task                | Reference output                                                                                                                                                            |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| image_analysis      | A:110 B:14 C:77 D:55 E:144 F:33 G:12 H:45 I:120 J:0 K:0 L:50 M:23 N:106 O:96 P:52 Q:0 R:101 S:73 T:155 U:33 V:6 W:16 X:1 Y:20 Z:1 (total 1,343)                             |
| invoice_processing  | 2 invoices: Acme INV-2025-0042 ($4,086.40, 5 LI) + GlobalTech GT-10587 ($30,717.90, 6 LI). Aggregate $34,804.30. The 10% discount on GlobalTech is a 6th line worth $3,100. |
| document_redaction  | ~70–100 PII items across 9 categories on 6 pages (predict-rlm range, treated as ground-truth lower bound).                                                                  |
| contract_comparison | (no fixed ground-truth; comparison quality must be judged via prose evaluation)                                                                                             |
| document_analysis   | ~150 key dates, ~200+ key entities, ~25 KB structured briefing report (predict-rlm range, treated as ground-truth lower bound).                                             |

---

## Followups for this doc

1. **LLM-as-judge integration** — currently we measure structural metrics (count of items, presence of keys). For prose tasks (document_analysis report quality, contract_comparison diff completeness) we need an LLM judge running the orc `evaluation` component against the bench outputs.
2. **Driver-recovery degradation library** — once the round-6 sweep runs, this doc should grow a `degradations.edn` reference appendix.
3. **Round-by-round numbers** stay in `bench/REPORT.md`; this doc captures the _invariant_ setup. Don't put round-specific numbers here.
