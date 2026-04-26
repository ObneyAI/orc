# RLMs and orc — Deep Analysis

How we got from the _Recursive Language Models_ paper to predict-rlm to orc's `:rlm` mode, and where the design goes next.

**Reference:** Zhang, Kraska, Khattab — _Recursive Language Models_ (arXiv:2512.24601, Dec 2025) · [Zhang's blog](https://alexzhang13.github.io/blog/2025/rlm/)

**Source repos referenced:**

- `predict-rlm` — Trampoline AI's production runtime ([Trampoline-AI/predict-rlm](https://github.com/Trampoline-AI/predict-rlm))
- `dspy` — Stanford NLP's base RLM primitive ([stanfordnlp/dspy](https://github.com/stanfordnlp/dspy))
- `orc` — this repo (behavior-tree workflow engine)
- `grain` — event-sourcing foundation ([ObneyAI/grain](https://github.com/ObneyAI/grain))
- `noj` — JVM data-science substrate ([scicloj/noj](https://github.com/scicloj/noj))

---

## Part 1 — RLMs (the idea), per the paper and Zhang's blog

### The thesis

**Recursive Language Models (RLMs)** are an inference-time scaling technique. The core move:

> Don't feed the long context **into** the model. Feed it into a **REPL the model controls**, and let the model decompose, peek, grep, partition, and recursively call itself (or a sub-LM) over snippets.

A standard LM call has signature `q, C → y` (query + monolithic context → answer). An RLM has the same external signature but internally exposes `C` as a Python variable inside a REPL the root LM drives by writing code. Sub-calls invoke an LM (depth=1 in the published experiments) on programmatically chosen subsets.

### Why it works (the "bitter lesson" framing)

- **Context rot avoidance**: the root LM never reads the raw context — it only sees code, prints, and small summaries. It stays in its comfortable operating range no matter how big `C` is.
- **Substrate-symmetric scaling**: when base models get longer windows, faster reasoning, or cheaper tokens, the RLM gets all of those for free (each sub-call is a base-model call). Hand-built scaffolds cap the model; RLMs don't.
- **Symbolic compression**: a single line of code (`asyncio.gather(predict(...) for chunk in chunks)`) can express what an agentic loop would have to enumerate as N explicit tool calls.

### Emergent decomposition strategies the paper observed

- **Peeking** — print head/tail of the context to learn its structure
- **Grepping** — regex/keyword filtering using the model's prior knowledge of what's worth looking for
- **Partition + Map (+ Reduce)** — chunk, fan out sub-calls, aggregate
- **Sequential context-building** — pass running notes forward across chunks
- **Verification loops** — re-check answers via separate sub-calls

### Headline benchmark numbers (paper)

| Benchmark                         | Base              | RLM                        | Notes                          |
| --------------------------------- | ----------------- | -------------------------- | ------------------------------ |
| OOLONG @ 132k (GPT-5)             | 44.0%             | 56.5%                      | +28% relative; comparable cost |
| OOLONG @ 132k (GPT-5-mini)        | —                 | beats GPT-5 base by ~33pts | smaller model > bigger non-RLM |
| OOLONG-Pairs (quadratic agg.)     | 0.04%             | 58.0%                      | base model collapses           |
| BrowseComp+ 1k docs (~10M tok)    | 0% (over context) | 91.3%                      | scales 100× past window        |
| LongBench-v2 CodeQA (Qwen3-Coder) | 20%               | 56%                        |                                |

Median cost on BrowseComp+ 1k: RLM(GPT-5) ≈ **$0.99**, vs $1.50–$2.75 for direct ingestion / summarization baselines. Tail (p95) cost spikes when models over-verify.

### Limits called out

- Recursion depth fixed at 1 in experiments
- Sub-calls were sequential in the paper's reference impl (no async)
- Models aren't trained for RLM use → strategies are inefficient (Qwen3-Coder makes thousands of sub-calls for trivial tasks; GPT-5 is conservative ~10)
- Brittle answer-extraction (FINAL() tag detection)
- Underperforms base models on _short_ inputs

---

## Part 2 — `predict-rlm`: a production-focused RLM runtime

`predict-rlm` is Trampoline AI's productionization of the paper's idea, built on **DSPy's `RLM` primitive**. It's the same two-level architecture as the paper but turned into a typed, sandboxed, async, file-aware library.

### The shape of a user's program

A user defines a **DSPy Signature** (typed inputs/outputs + a strategy docstring) and hands it to `PredictRLM`:

```python
class ProcessInvoices(dspy.Signature):
    """Extract structured data from PDF invoices into an Excel spreadsheet.
       1. Survey the invoices ...
       2. Render each page and use predict() ...
       3. Build the workbook with openpyxl ...
       4. Save and produce the result.
    """
    invoices: list[File] = dspy.InputField(...)
    workbook: File = dspy.OutputField(...)
    result: InvoiceExtractionResult = dspy.OutputField(...)

rlm = PredictRLM(ProcessInvoices, lm="openai/gpt-5.4", sub_lm="openai/gpt-5.1",
                 skills=[pdf_skill, spreadsheet_skill])
```

The **docstring is the strategy** — it becomes part of the system prompt the root LM reads. The Pydantic output type is what the LM must SUBMIT.

### The two-level execution model

1. **Root LM** (`lm=`) — writes Python in a REPL, iterates code → output → next code, mirrors the paper's "outer model."
2. **Sub-LM** (`sub_lm=`) — invoked from inside the sandbox via `await predict(signature, **kwargs)`. Each call is a fresh context window. Replaces the paper's `llm_query()`.

This mapping matches the paper's `max_recursion_depth=1` exactly: root writes code, code calls a leaf LM, leaf returns typed data.

### The REPL system prompt

The `PREDICT_RLM_INSTRUCTIONS` constant (in `predict_rlm/predict_rlm.py`) is the heart of the runtime. It's a 200-line prompt that teaches the root LM:

- The REPL contract (variables persist, prints get truncated to 5 KB, async event loop is live, must `await` predict)
- `predict()` semantics: signature string with type hints, custom Pydantic types resolve by name, image fields auto-wrap base64/URLs into `dspy.Image`, **type contract is enforced** — VLM returning `None` for a non-Optional field raises rather than silently coercing
- Concurrency pattern: always `asyncio.gather()` independent sub-calls
- Three idiomatic shapes: **explore-before-extract**, **sequential context-building**, **parallel map → synthesize** (these are essentially the paper's emergent strategies, made explicit)
- `SUBMIT(...)` semantics: terminates the turn instantly, must be alone in its turn, accepts Pydantic instances or dicts, validated against output types

This is where productionization happens — the paper's "models invent inefficient strategies" problem gets addressed with a curated, opinionated playbook instead of training.

### The sandbox — `interpreter.py` + `sandbox/runner.js`

- **Pyodide running inside Deno**, driven over stdin/stdout via **JSON-RPC 2.0**
- **JSPI** (JavaScript Promise Integration) is the key trick: lets sync-looking Python in WASM make async JS tool calls. Required for tool calling from inside Pyodide.
- **Concurrent tool dispatch** via `asyncio.gather` on the host — multiple `predict()` calls in flight in parallel.
- **System-wide cap**: `MAX_CONCURRENT_SANDBOXES = 50` (~800 MB each, ~40 GB ceiling) via class-level semaphore — prevents OOM when many RLM rollouts run in parallel.
- **Network**: deny-by-default, with a default allowlist for PyPI/jsDelivr so `micropip` can install packages; user adds domains via `allowed_domains=`.
- **Non-blocking stdin/stdout** via raw fds + `loop.add_reader/add_writer` so concurrent rollouts don't serialize on pipe I/O.

### File I/O — the production-critical bit the paper doesn't address

`PredictRLM` adds **typed File support** the paper lacks:

- `File` input fields are mounted at `/sandbox/input/<field>/<basename>`
- `File` output fields: the RLM writes to `/sandbox/output/<field>/`, and `_sync_output_files` syncs files back to the host and wraps the path in a `File` object
- File-typed output fields are exposed to the LM as `str` (just submit the path), then reconstituted on the host — clean separation between sandbox and host filesystems

### Skills — `rlm_skills.py`

A `Skill` is a 4-tuple `(instructions, packages, modules, tools)` that gets composed into the RLM:

- **instructions** → appended to system prompt under `## Skill: <name>`
- **packages** → `micropip` installs in the sandbox
- **modules** → `.py` files mounted at `/sandbox/lib/<name>.py` and added to `sys.path`
- **tools** → host-side callables exposed alongside `predict()`

Built-in skills: `pdf` (pymupdf), `spreadsheet` (openpyxl + pandas + formulas + a `formula_eval` verifier module), `docx` (python-docx + md2docx). Skills compose with conflict-checking on tool/module names.

### Tracing — `trace.py`

Every run produces a `RunTrace` containing per-iteration `IterationStep`s with:

- reasoning, code, output (truncated + untruncated), error flag
- nested `tool_calls` and `predict_calls` with token usage and duration
- attached to both successful predictions and raised exceptions

This is what the README means by **"fully interpretable trajectories"** — concrete signal that downstream optimizers like GEPA can ingest.

### Synchronous vs async forward

- `forward()` builds a per-call event loop and uses `repl.execute()` (blocking)
- `aforward()` uses `repl.aexecute()` directly so parallel rollouts in `asyncio.gather` actually overlap
- The whole stack — DSPy LM calls, sandbox stdin/stdout, tool execution — is async-clean end-to-end

### What's genuinely novel beyond the paper

1. **Typed sub-calls with Pydantic schema reconstruction inside the sandbox** (`_models_from_schema`). The model defines a `BaseModel` in REPL globals, the predict tool extracts JSON-Schema from it, ships it to the host, and reconstructs the type so DSPy can do structured generation. This makes "sub-LM call" into a typed RPC, not just text-in-text-out as in the paper.
2. **Type contract on outputs**: `None` for a non-Optional declared type raises with a hint to mark it Optional or simplify. Closes the silent-failure mode.
3. **Multimodal**: `dspy.Image` type-hint detection auto-wraps strings/URLs, giving the RLM vision sub-calls.
4. **File-to-file workflows**: not just QA over text, but "PDFs in, .xlsx out" with proper sandbox/host boundary.
5. **Skills as a composition mechanism** for instructions + packages + tools — turns "what should the RLM know how to do" into a reusable unit.

### How it maps onto the paper's claims

| Paper claim                                                | predict-rlm realization                                                                               |
| ---------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| Root LM sees only query, accesses context programmatically | Inputs become REPL variables; `File` inputs become path strings; large blobs never enter root context |
| Sub-call ≈ `llm_query()` over chunks                       | `await predict(signature, **kwargs)` with typed I/O                                                   |
| Depth=1 recursion                                          | Same — `predict` calls a flat LM, no nested RLMs                                                      |
| Async sub-calls are a known limitation                     | **Solved** — JSPI + asyncio.gather concurrency on host                                                |
| Strategies are emergent and inefficient                    | **Curated** — explicit playbook in PREDICT_RLM_INSTRUCTIONS                                           |
| Variable stitching for unbounded outputs                   | REPL state persists across iterations; SUBMIT reads from variables                                    |
| Cost-comparable to direct ingestion                        | Trampoline claims `RLM(GPT-5-mini) > base GPT-5`, same direction as paper                             |

---

## Part 3 — Deep dive into the predict-rlm example set

Five examples, deliberately laid out along a difficulty/capability axis. Each picks a different combination of (input type, output type, skill mix, sub-LM intensity, state mutation) so the matrix covers the framework's full surface. **orc's `examples/` directory mirrors this set 1:1** — same five tasks, same input PDFs, same output schemas (Malli ports of Pydantic models).

### 1. `image_analysis` — multimodal QA with self-consistency

**Input**

- 1 image: `Screenshot 2026-04-02 at 12.16.21 PM.png` (510 KB) — first page of an Ontario microFIT contract (logo, headers, dense paragraphs, form blanks)
- A query that's deceptively simple but engineered to expose a known LLM weakness:
  > "Extract the visible text multiple times (at least 2-3 extractions per image), compare the extractions — if they differ, extract again until you get consistent results. Only then count the letters programmatically."

**Output**

- ~30-line verbatim text extraction
- Per-letter A–Z count table (T=155, E=144, I=120, …, total 1,343)
- Run stats: **4 main-LM calls, 5 sub-LM calls, $0.08, ~1 minute**

**What it's showing**
This is the **simplest possible RLM** — no skills, no `File` outputs, no Pydantic — and exists to teach the **division of labor** principle: _use `predict()` for perception, use Python for computation_. The query explicitly forbids `predict()`-based counting because LLMs miscount letters; the trace shows the RLM extracts text via VLM, **runs the same extraction 2–3× for self-consistency** (note `Verification pass consistent: False` in the output, which the RLM still surfaces honestly), then does case-insensitive counting in pure Python.

It's also the only example with **no `sub_lm` advantage** — both LMs do similar work — so it isolates the value of the REPL/predict split itself rather than skill composition.

### 2. `invoice_processing` — file-to-file transformation

**Input**

- `acme-invoice-2025-0042.pdf` and `globaltech-invoice-GT-10587.pdf` (2 PDFs)

**Output**

- `invoice_extraction.xlsx` — a real Excel file (the actual artifact, not a description of one)
- `InvoiceExtractionResult` Pydantic instance with nested `Invoice → LineItem` models

**What it's showing**
This is the **canonical "structured ETL" pattern**: heterogeneous PDFs in, single normalized spreadsheet out. The interesting parts:

- **Two `File` output fields** in the same signature (`workbook: File` + `result: InvoiceExtractionResult`) — proving the framework can return both a file artifact _and_ structured data in one call
- **Skill composition**: `skills=[pdf_skill, spreadsheet_skill]`. The RLM gets pymupdf to render pages, then openpyxl/pandas/formulas to build the workbook, plus the `formula_eval` verification module — all merged into one sandbox.
- **Strategy in the docstring** prescribes the playbook: survey → render → parallel `predict()` per page → build sheets → save. The output is essentially a "schema-conformant Excel" with a Summary sheet plus per-invoice sheets.

When the deliverable is a file, the RLM produces the file; the Pydantic result is just provenance.

### 3. `document_analysis` — long-form report generation

**Input**

- `YYJ-2025-Parking-Management-RFP.pdf` (136 pages — by far the largest input in any example)
- A separate `criteria` parameter that's a 4-section briefing template (Executive Summary / Key Dates / Entities / Financial Information)

**Output**

- 119-line markdown briefing report
- `key_dates: list[KeyDate]` and `key_entities: list[KeyEntity]` (typed extractions for downstream consumers)
- A DOCX of the same report (third output field, `docx_report: File`)
- **8 main-LM calls, 63 sub-LM calls, 136 pages → $0.52 ($0.004/page)**

**What it's showing**
Three things that none of the other examples demonstrate:

1. **Runtime instruction injection** — the criteria isn't hardcoded into the `Signature`; the service does `AnalyzeDocuments.with_instructions(AnalyzeDocuments.instructions + "\n\n# Task\n\n" + criteria)`. This is how you parameterize an RLM's behavior without rewriting the signature class.

2. **Long-context decomposition at scale** — 136 pages would saturate a normal LM's context window; the report covers everything from a Feb 13 RFP issue date through a 2032 renewal end, plus 7 distinct parking-rate items, plus 5 named entities with contact info. The cost-per-page of $0.004 is the headline "RLMs are cheap on long inputs" claim from the paper, made concrete.

3. **Multi-format output via skill composition** — `skills=[pdf_skill, docx_skill]` gives the RLM both PDF reading _and_ DOCX writing in one sandbox. The same in-memory report becomes both `result.report` (markdown string) and `result.docx_report` (synced `.docx` file).

The 8:63 main-to-sub call ratio is the most extreme in the example set — the orchestrator stays small while the perception layer fans out across 136 pages.

### 4. `document_redaction` — state-modifying workflow with verification

**Input**

- `PNFS-Employment-Agreement-2025.pdf` (6 pages, dense PII — names, SINs, addresses, emails, phone numbers, financial info, signatures)
- A criteria string listing 6 PII categories to redact

**Output**

- `PNFS-Employment-Agreement-2025-redacted.pdf` — the modified PDF
- `RedactionResult` with **89 redactions across 6 pages**, per-page breakdown by category, full audit trail of every redacted text+reason
- $0.18 total ($0.03/page — 7× more expensive than `document_analysis` per page)

**What it's showing**
This is the only example with **destructive output** (the original is mutated, not just summarized) and it introduces several new patterns:

1. **A purpose-built custom skill**. `redaction_skill` is 50 lines of pymupdf instructions teaching the RLM the `search_for → add_redact_annot → apply_redactions` cycle, how to handle text that splits across lines, how to redact bounding boxes for signatures/logos, and **how to verify** by re-rendering and asking a fresh `predict()` "what PII is still visible?" This is the framework's answer to "how do I codify a domain pattern": not a tool function but a skill bundle.

2. **The verification loop made explicit** — the skill teaches a self-check pattern. The audit trail shows the RLM caught variants: the same person's name in 5 different forms (`Margaret Elisabeth Thornbury-Watson`, `Margaret`, `Margaret E. Thornbury-Watson`, `Margaret Thornbury-Watson`) and the same SIN in two formats (`847-291-036` and `9847 291 036`). That's the recursive "check then fix" pattern the paper describes, surfaced as concrete rows.

3. **`list[File]` output** — proving the file-sync layer handles batch outputs, not just single files. The framework discovers files in `/sandbox/output/redacted_documents/` and wraps each as a `File`.

4. **Higher per-page cost is the tradeoff** — verification loops aren't free. 30 sub-LM calls for 6 pages = 5 calls/page (extract + verify + re-extract for misses). That's the cost of _being right_ on a state-modifying task.

### 5. `contract_comparison` — pairwise relational reasoning

**Input**

- `microFIT-Contract-Version-2-0.pdf` (23 pages) + `microFIT-Contract-Version-3-1-1.pdf` (22 pages)
- No criteria parameter — the comparison protocol is baked into the signature docstring

**Output**

- 770-line markdown report (the longest output in any example) organized as: Document Survey → Methodology → Section-by-section findings → Appendices → Impact Analysis → Recommendations → Summary
- `section_diffs` with `Literal["major", "minor", "identical"]` significance enum
- `key_differences` with impact analysis
- **80 sub-LM calls, $0.71, 5.5 minutes — most expensive run**

**What it's showing**
The most cognitively complex example. The other four are _extractive_ (text → fields); this one is _relational_ — every claim is "Doc A says X but Doc B says Y, here's the impact."

Notable behaviors visible in the output:

- **Honest documentation of gaps**: the report repeatedly flags "no text is provided for Section 8 in the materials" and explicitly distinguishes "documentation gap" from "true shift". The RLM doesn't hallucinate to fill silence — it surfaces uncertainty as a first-class output.
- **Internal contradiction handling**: §4.4 (Appendix D-1) — "the materials are inconsistent: one summary describes... another explicitly states..." It calls out conflicts in its own intermediate findings rather than picking one and moving on.
- **Heavy sub-LM usage** (80 calls) — pairwise comparison forces the RLM to extract section-by-section from _both_ documents and then run synthesis calls to compare. The 4:80 main:sub ratio is the most extreme decomposition in the set.
- **Schema-driven discipline**: every `SectionDiff` carries a `significance` literal that the report uses as section structure, so the Pydantic schema actually shapes the prose.

### Cross-cutting matrix — what the example set as a whole demonstrates

| Example             | Inputs            | Outputs               | Skills                 | Sub-LM calls |     Cost / page | Capability shown                                              |
| ------------------- | ----------------- | --------------------- | ---------------------- | -----------: | --------------: | ------------------------------------------------------------- |
| image_analysis      | 1 image + query   | str                   | none                   |            5 | — ($0.08 total) | Multimodal QA, predict-vs-Python split, self-consistency      |
| invoice_processing  | N PDFs            | File + Pydantic       | pdf + spreadsheet      |            — |               — | Two file outputs in one signature, ETL to Excel               |
| document_analysis   | N PDFs + criteria | str + lists + File    | pdf + docx             |           63 |          $0.004 | Runtime criteria injection, long context, multi-format export |
| document_redaction  | N PDFs + criteria | list[File] + Pydantic | pdf + custom redaction |           30 |           $0.03 | Custom skill design, state mutation, verification loops       |
| contract_comparison | ≥2 PDFs           | Pydantic              | pdf                    |           80 |          $0.016 | Relational/pairwise reasoning, gap-honest output              |

### Dimensions varied across the set

- **Input arity**: 1 → 2 → N (and runtime parameterization via `criteria`)
- **Output kinds**: pure text → typed Pydantic → File artifact → list[File] → multi-output
- **Skill composition**: none → built-in pair → built-in + custom
- **Mutation**: read-only → file-creating → file-modifying
- **Reasoning shape**: extractive → aggregative → comparative
- **Decomposition intensity**: 5 sub-calls → 80

### What you can learn just by reading the outputs

1. **The `Run Stats` blocks are the proof of the pitch.** The README claims "RLMs scale with base model improvements without context rot." The output stats make it concrete: 136-page RFP processed end-to-end for 52 cents, and the main LM only made 8 calls — meaning it never read the raw PDF text directly, just orchestrated.

2. **Sub-LM count is a proxy for decomposition depth.** Document analysis (extractive) needs 63 sub-calls; contract comparison (relational) needs 80; redaction (with verification) needs 30 over 6 pages — that ratio tells you "this task type costs ~5 sub-calls/page when you require correctness checks."

3. **Per-page cost varies 7×** (document*analysis $0.004 vs document_redaction $0.03). Same skill (pdf), same models, same input size class. The delta is \_what the task demands of the model* — passive read vs verified mutation. Useful prior when budgeting a new RLM.

4. **The audit trails are receipts.** The redaction example lists every redacted string with a reason; the comparison report flags every "documentation gap." This is the "fully interpretable trajectories" claim from the README operationalized — when the RLM is wrong, you can see _exactly where_ it was wrong, not just that the answer is bad.

5. **The strategy docstring is load-bearing.** Compare the prose at the top of each `signature.py`: every example has a numbered 4–6 step playbook. That docstring goes straight into the system prompt. The examples teach by example that the docstring is where you encode methodology, not the calling code.

### The unifying message of the example set

Each example is a different **shape** of work the same primitive (`PredictRLM`) can take on, and the matrix is constructed to show that **adding capability = composing skills + adjusting the strategy docstring**, not changing the runtime. The framework's pitch — _"define inputs, outputs, and tools; the model handles control flow"_ — is demonstrated by five different (input, output, tool) tuples producing five very different artifact types under the same 30-iteration budget and same 50 max LLM calls.

### Bottom line on predict-rlm

The paper is a research finding: _give the model a REPL over its own context and it learns to decompose_. `predict-rlm` is the engineering: typed signatures, Pydantic-aware sub-calls, JSPI-enabled async tool dispatch, file mounting/sync, composable skills, full trajectory tracing, all built on top of DSPy's RLM primitive. The example set is the proof: five differently-shaped tasks (text QA → ETL → long-form report → state mutation → pairwise comparison) running on the same primitive, with cost/quality numbers that match the paper's claims at the page level.

---

## Part 4 — `dspy.RLM`: the upstream primitive predict-rlm extends

Reading the upstream primitive at `dspy/predict/rlm.py` clarifies what's "the paper" vs "predict-rlm's productionization." There are actually **three layers** here, each adding distinct value.

### The architectural skeleton is dspy's

`dspy.RLM` is itself a near-literal implementation of Zhang/Kraska/Khattab. The pieces:

#### The system prompt — the paper made executable

```
You have access to a Python REPL environment. Write Python code...
Available:
- llm_query(prompt) - query a sub-LLM (~500K char capacity) for semantic analysis
- llm_query_batched(prompts) - query multiple prompts concurrently
- SUBMIT(...) - submit final output when done
...
1. EXPLORE FIRST  2. ITERATE  3. VERIFY BEFORE SUBMITTING
2. USE llm_query FOR SEMANTICS  5. MINIMIZE RETYPING  6. SUBMIT ONLY AFTER SEEING OUTPUTS
```

The "~500K char capacity" line and the `llm_query` / `llm_query_batched` names are basically transcribed from the paper. Note the comment in dspy: `# TODO: Optimize this prompt across a diverse benchmark` — it's a starting prompt, not a tuned one.

#### Two-signature pattern — action + extract

- `generate_action` — the iterative driver, takes `variables_info`, `repl_history`, `iteration` and outputs `reasoning` + `code`
- `extract` — a **fallback predictor** that runs only if `max_iterations` is hit without SUBMIT (`_extract_fallback`). It reads the trajectory and produces typed outputs.

This is a critical idea: the RLM has a graceful failure mode. If the model never calls SUBMIT in 20 iterations, dspy doesn't crash — it asks a fresh LM call to look at the trajectory and produce the best possible structured output anyway. predict-rlm inherits this.

#### `REPLVariable` — the "metadata not content" pattern as code

At `dspy/primitives/repl_types.py`, `REPLVariable.from_value()` is what makes the paper's "context as variable" work. For each input:

- Records `type_name`, `total_length`
- Builds a **head+tail preview** (`preview[:500] + "..." + preview[-500:]`) capped at 1000 chars by default
- `format()` produces what the LM literally sees:
  ````
  Variable: `documents` (access it in your code)
  Type: list
  Total length: 4,238,991 characters
  Preview:
  \```
  [{"name": "report.pdf", "path": "/sandbox/..."}, ...
  \```
  ````
  The LM sees ~1KB of metadata about a 4MB input. That's the paper's mechanism for keeping the root LM out of context rot, expressed in 60 lines of pydantic.

#### `REPLHistory` — immutable, head+tail truncated

`append()` returns a new frozen instance. `format_output` truncates each entry's stdout to 10,000 chars with the first/last halves kept and an "(N characters omitted)" marker in between. Same head/tail trick as REPLVariable.

So _both_ directions of the LM↔REPL channel are compressed:

- Inputs come in as ~1KB previews of N-MB variables
- Outputs come back as ~10KB extracts of arbitrarily large prints

This is what makes the root LM's context grow slowly even when the underlying data is huge.

#### SUBMIT is exception-driven control flow

`runner.js` defines `FinalOutput(BaseException)` in the sandbox. SUBMIT raises it. The Deno runner catches it specifically and converts to `jsonrpcResult({ final: answer })`, which the Python side wraps in `FinalOutput` and the RLM loop recognizes. SUBMIT terminating mid-turn isn't a quirk — it's a Python exception escaping the user code.

The runner also **dynamically generates SUBMIT** with the output field signature (`makeSubmitWrapper`). If your signature is `-> items: list, total: int`, the sandbox literally defines:

```python
def SUBMIT(items: list, total: int):
    raise FinalOutput({"items": items, "total": total})
```

So Python's own argument validation enforces the output schema — you get a TypeError before the RLM loop even sees the call.

#### Tool wrappers are typed too

`makeToolWrapper` generates Python functions inside the sandbox with proper signatures. When you register `add_tool(a: int, b: int)`, the sandbox gets a Python wrapper that calls `_js_tool_call` over JSON-RPC and returns the parsed result. Tool calls cross the JS↔Python boundary via `run_sync`, with `pyodide.ffi.JsProxy` unwrapping for the return value.

#### Large variable trick — `LARGE_VAR_THRESHOLD = 100MB`

Variables under 100MB get inlined as Python literals via `_serialize_value`. Variables over 100MB get written to `/tmp/dspy_vars/<name>.json` in the sandbox MEMFS and loaded inside the code with `json.loads(open(...).read())`. The reason is in the comment: **Pyodide's FFI crashes at exactly 128MB.** The 100MB threshold is a safety margin.

This is the same idea as predict-rlm's `File` mounting — keep huge data out of the FFI marshalling path — but generalized to any large Python value.

#### `CodeInterpreter` is a Protocol

`code_interpreter.py` defines a runtime-checkable Protocol with `tools`, `start()`, `execute()`, `shutdown()`. The default is `PythonInterpreter` (Deno+Pyodide). Tests use `MockInterpreter` for deterministic responses. Production users could plug in E2B or Modal. predict-rlm's `JspiInterpreter` is _exactly this kind of swap-in_ — it inherits from `PythonInterpreter` and adds JSPI + async dispatch.

### Where predict-rlm actually diverges from dspy.RLM

| Concern                     | `dspy.RLM` (upstream)                                                      | `PredictRLM` (predict-rlm)                                                                                          |
| --------------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Sub-LM call shape           | `llm_query(str) -> str` — raw text in, raw text out                        | `await predict(signature, **kwargs) -> typed dict` with Pydantic schema reconstruction                              |
| Concurrency                 | `ThreadPoolExecutor(max_workers=8)` for `llm_query_batched` — sync threads | JSPI + `asyncio.gather` end-to-end; class-level semaphore caps parallel sandboxes at 50                             |
| Multimodal                  | none                                                                       | `dspy.Image` auto-wrap for URL/base64 strings                                                                       |
| Code fence parsing          | strict — rejects ` ```ts ` etc.                                            | permissive — accepts `python`/`py`/`repl`                                                                           |
| Output truncation in prompt | 10K chars (head+tail)                                                      | 5K chars — tighter, forces variable persistence                                                                     |
| File I/O                    | none — sandbox is hermetic                                                 | `File` input mounting at `/sandbox/input/<field>/`, output sync from `/sandbox/output/<field>/`                     |
| Skills                      | none                                                                       | composable `(instructions, packages, modules, tools)` bundles                                                       |
| Tracing                     | `Prediction.trajectory` (list of dicts)                                    | full `RunTrace` with per-iteration `tool_calls` and `predict_calls`, token usage, durations, attached to errors too |
| Custom Pydantic outputs     | `parse_value` via DSPy adapters                                            | sandbox-side schema extraction → host reconstruction → typed sub-LM responses                                       |
| Type contract enforcement   | none — None silently passes                                                | raises if VLM returns None for non-Optional output                                                                  |
| Action+Extract fallback     | yes, in `_extract_fallback`                                                | inherited, plus async variant                                                                                       |

### The three-layer mental model

**dspy.RLM is the paper's "RLM as a sandboxed REPL with `llm_query` and SUBMIT" almost line-for-line.** All the genuinely novel architectural ideas are upstream:

1. The action+extract two-signature pattern with graceful fallback
2. `REPLVariable` and `REPLHistory` as the head+tail-truncated bridge that keeps context small in both directions
3. SUBMIT as a Python `BaseException` trapped by the sandbox runner
4. Dynamically-generated typed SUBMIT and tool wrappers in Pyodide
5. JSON-RPC 2.0 over Deno stdin/stdout
6. The 100MB → MEMFS escape hatch for large variables
7. `CodeInterpreter` as a swappable Protocol

**predict-rlm's value-add is everything that turns this into "I can ship it":**

- Replacing `llm_query(str) -> str` with typed `predict(signature, **kw) -> Pydantic` is the single biggest delta. The paper's RLM passes raw strings around; predict-rlm makes the sub-LM call into a typed RPC, which is what lets the parent RLM trust outputs structurally.
- Async (JSPI + asyncio.gather) breaks the paper's sequential limitation and lets one RLM saturate parallel sub-calls without threads.
- The semaphore-bounded sandbox pool is what makes _concurrent rollouts_ possible (you can run 50 RLM instances in parallel without OOMing).
- File I/O is the bridge from "context is a string in a variable" (paper) to "context is a 50-page PDF on disk" (production).
- Skills convert the paper's "the model invents inefficient strategies" problem into "we ship pre-tuned strategy bundles."

### Reframing the whole stack

Reading dspy first reframes the predict-rlm analysis: **the paper's contribution is small (the REPL+sub-LM pattern), dspy productionized it (Protocol + truncation + extract fallback + dynamic typed SUBMIT), and predict-rlm took the productionized version into production-production (async, types, files, skills, tracing).** Three layers of "make it real," each adding distinct value:

| Layer                    | What it contributes                                                                                                               | What you'd have without it                                                                   |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| **Paper (Zhang et al.)** | The core insight: model writes code that recursively calls a sub-LM over a context variable                                       | Just an idea — no implementation pattern                                                     |
| **dspy.RLM**             | The execution skeleton: REPL truncation, action+extract, SUBMIT-as-exception, typed sandbox tools, swappable interpreter Protocol | A research prototype with a `requests.post` to OpenAI                                        |
| **predict-rlm**          | The production layer: typed Pydantic sub-calls, async via JSPI, file I/O, composable skills, tracing, semaphore pooling           | A working agent you can't trust outputs from, can't pass files to, and can't run in parallel |

The chain is what makes the paper actually useful — and what would make any new RLM-style runtime difficult to build from scratch without leaning on at least the dspy layer.

---

## Part 5 — How orc/grain attacks the same problem

The orc `examples/` directory **mirrors predict-rlm's example set 1:1** — same 5 tasks (image_analysis, document_analysis, invoice_processing, contract_comparison, document_redaction), same input PDFs, same output schemas (Malli ports of Pydantic models). This is deliberate — orc's example set exists as a head-to-head benchmark against predict-rlm. And it ships **two implementations of each**: Style A (explicit pipeline) and Style B (RLM-faithful). That's the architectural thesis in code form.

### Substrate inversion — the central move

predict-rlm and orc solve the same problem with inverted assumptions about the substrate:

|                        | predict-rlm                                                                     | orc                                                                                  |
| ---------------------- | ------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| Language for tool code | Python                                                                          | Clojure                                                                              |
| Tool runtime           | Pyodide compiled to WASM                                                        | SCI (Small Clojure Interpreter) running in JVM                                       |
| Process boundary       | Deno subprocess + JSON-RPC over stdin/stdout                                    | In-process function calls                                                            |
| Sandboxing mechanism   | OS-level (Deno permissions, WASM memory)                                        | Language-level (SCI is a _whitelist_ interpreter; no eval-of-jvm-class)              |
| FFI layer              | JSPI for async tool calls from WASM Python                                      | None — `call-tool-fn` is just a Clojure function                                     |
| Library substrate      | micropip-installed Pyodide-compatible packages (pymupdf, openpyxl, python-docx) | Native JVM libraries (PDFBox, Apache POI, docx4j) wrapped as `doc-skills`            |
| Data-science fallback  | Python pandas/numpy/scipy in WASM                                               | The whole **noj** stack (tech.ml.dataset, tablecloth, fastmath) — already JVM-native |

This single inversion eliminates an entire layer of plumbing. **Where predict-rlm has 1500 lines of `interpreter.py` + `runner.js` to build a Python REPL inside Deno inside a subprocess inside JSPI, orc has SCI** — a 600-line `sci_sandbox.clj` that builds a sandboxed Clojure interpreter by selectively whitelisting `clojure.core` symbols and merging in tool bindings. The "sandbox" is the language itself.

The trade is real: you don't get the entire PyPI universe, you get the JVM universe. For the example task class (PDF/Excel/DOCX manipulation, multimodal extraction, structured output), the JVM has equivalents for everything — and noj fills the data-science gap that's traditionally Python's stronghold.

### `doc-skills` is structurally identical to predict-rlm's skills

Reading `components/doc-skills/src/ai/obney/orc/doc_skills/interface.clj` against predict-rlm's `Skill` dataclass:

| predict-rlm `Skill`                                           | orc `doc-skills`                                                                                                       |
| ------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `instructions: str` (markdown injected into system prompt)    | `(instructions :pdf)` — markdown loaded from `resources/.../instructions/pdf.md`, composed with `compose-instructions` |
| `packages: list[str]` (micropip installs in sandbox)          | implicit — JVM deps already on classpath                                                                               |
| `modules: dict[str, str]` (Python files mounted into sandbox) | `sci-flat-bindings` / `sci-bindings` — symbol maps merged into SCI namespaces                                          |
| `tools: dict[str, Callable]` (host-side functions)            | `call-tool-fn` — MCP-style `(tool-name args-map) -> result` dispatcher                                                 |

The interface.clj exposes both shapes simultaneously:

- **`sci-bindings`** for RLM mode — the LLM writes `(pdf/page-count "…")` directly, just like predict-rlm's `pymupdf.open(documents[0])` inside the REPL
- **`call-tool-fn`** for MCP-style tool-calling mode — same function dispatched by name string `"pdf/page-count"`

Same skill, two front-ends. predict-rlm only has the first; orc supports both.

The dispatcher does what predict-rlm leaves to Pydantic: explicit `require-args!` validation with precise "expected … got …" errors. predict-rlm trusts the sandbox to surface `TypeError` from Python's argument validation; orc throws ex-info upfront.

### The RLM mode — `:rlm true` on `repl-researcher`

`components/orc-service/src/ai/obney/orc/orc_service/core/dsl.clj` defines `repl-researcher`. With `:rlm {:enabled? true ...}` it becomes the direct analog of `PredictRLM`:

```clojure
:rlm {:enabled? true
      :context-key :documents              ;; full value bound in SCI
      :predict-model "openai/gpt-5-mini"
      :max-predict-calls 100
      :max-predict-concurrency 8           ;; bounded parallel sub-calls
      :max-predict-input-chars 100000      ;; truncate inputs to predict()
      :history-preview-chars 4000}         ;; truncate REPL history shown to root LM
```

**Every knob has a predict-rlm/dspy.RLM analog:**

| orc                                  | predict-rlm / dspy                                                                                                                           |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `:context-key :documents`            | The "metadata-not-content" pattern — `REPLVariable` shows the root LM only count + path preview while binding the full value as a SCI symbol |
| `:max-predict-calls 100`             | dspy.RLM's `max_llm_calls=50`                                                                                                                |
| `:max-predict-concurrency 8`         | predict-rlm's `JspiInterpreter.MAX_CONCURRENT_SANDBOXES = 50` (system-wide); dspy's `ThreadPoolExecutor(max_workers=8)` (per-batch)          |
| `:max-predict-input-chars 100000`    | predict-rlm's `~400K tokens per call` capacity guidance                                                                                      |
| `:history-preview-chars 4000`        | dspy's `max_output_chars=10_000` head+tail truncation in `REPLHistory.format_output`                                                         |
| `(predict {…})`, `(predict-all {…})` | `await predict(signature, **kw)` / `asyncio.gather`                                                                                          |
| `(final! {…})`                       | `SUBMIT(…)` — same exception-driven control flow                                                                                             |

The `predict-all` shape from `agentic.clj` is **explicit map-style**, more declarative than predict-rlm's positional async pattern:

```clojure
(predict-all
  {:name "extract-page"
   :items (mapv (fn [p] (pdf/page-text (:doc p) (:n p))) pages)
   :as :page-text
   :inputs {:criteria criteria}
   :instructions "Extract content relevant to the criteria."
   :schema :string
   :max-concurrency 6})
```

vs predict-rlm:

```python
results = await asyncio.gather(*[
    predict("page: dspy.Image -> dates: list[str]", page=img)
    for img in images
])
```

orc names the call (`:name "extract-page"` — for tracing), declares concurrency upfront, and uses Malli for `:schema` instead of DSPy signature strings. The trace is queryable per-name.

### Style A vs Style B — the dimension predict-rlm doesn't have

Every orc example ships **both**:

- **`pipeline.clj`** (Style A) — explicit behavior tree: `(sequence ...)` of `(code "survey")` → `(map-each "summarize-each" :parallel 3 (llm ...))` → `(llm "synthesize" :judges ["grounding"])` → `(code "render-docx")`. Each node has its own instruction, its own retry policy, its own GEPA optimization handle, its own LLM-as-judge.
- **`agentic.clj`** (Style B) — single `(repl-researcher "orchestrate" :rlm {...})`. The LLM writes Clojure that does survey + parallel extract + synthesize + render in one trajectory.

predict-rlm only has the equivalent of Style B. The orc example set is making an argument: **most production tasks should be Style A, with Style B reserved for genuinely exploratory work where the structure isn't known in advance.** Style A gives you:

- Per-node tracing/judging — you can swap `summarize-doc`'s instruction without touching `synthesize`
- GEPA-optimizable instructions per node, independently
- Deterministic retry semantics per node
- The full set of behavior-tree combinators (`fallback`, `parallel`, `map-each`)

The README at `examples/README.md` makes this explicit: "Style A — Pipeline: each phase is an explicit node. Per-node tracing, judges, retries; every instruction is GEPA-optimizable independently." The trace claim is concrete: predict-rlm's `RunTrace` is in-memory and per-run; orc's traces are events written to grain's event store, queryable across runs (`(es/read event-store {:tags #{[:sheet sheet-id]}})`).

### What grain provides that the paper/dspy/predict-rlm don't

Grain is event-sourced CQRS. That gives orc properties no Python RLM stack has:

1. **Trace as data, not artifact.** Every iteration of every `repl-researcher` writes events. You can:
   - Re-derive new read models from old runs
   - Project across thousands of runs to find drift
   - Tag-query: "show me every iteration where the RLM called pdf/redact-rects on documents tagged `:contract`"
2. **Multi-tenancy by construction.** Tenant-scoped event reads with Postgres RLS — predict-rlm has nothing like this; concurrent rollouts are just "more sandboxes."
3. **Versioning + draft/published modes** for workflows. You can `stash` a workflow, edit it, A/B test, restore.
4. **Distributed coordination** without external services (event-sourced leases). predict-rlm runs in one process.
5. **GEPA built-in** as a first-class optimizer over node instructions. predict-rlm mentions GEPA as a future hook for trajectories; orc ships it.
6. **The behavior tree itself is composable infrastructure.** A `repl-researcher` is just one node alongside `sequence`, `fallback`, `parallel`, `map-each`, `delegate` (sub-workflows), `code`, `llm`. predict-rlm is the _whole_ program; orc's RLM is _one node type_ in a richer DSL.

### The synthesis

predict-rlm and orc are answering the same question with deeply different premises:

- **predict-rlm**: "The model writes Python, decomposes its own context, calls itself recursively. We need a Python sandbox, file I/O, typed sub-calls, and skills." → builds a Python WASM REPL with rich tooling.
- **orc**: "The model writes _some_ code (Clojure SCI) inside a behavior-tree node, sometimes recursively. The substrate is JVM-native libraries, the trace is event-sourced, and an RLM is one of several node types you can compose." → builds a behavior-tree DSL where one node type happens to be an RLM.

The shared insight from the paper survives in both: **don't put the whole context in the model's prompt; expose it programmatically and let the model write code to interrogate it.** Both stacks honor the metadata-only-to-root-LM rule (predict-rlm's REPLVariable with previews; orc's `:context-key` binding the full value but showing previews to the root LM).

The differences are about what you build _around_ the RLM:

- predict-rlm builds inside the RLM — typed sub-calls, multimodal, skills, file I/O
- orc builds _outside_ — behavior trees, judges, GEPA, event-sourced trace, multi-tenancy, distributed coordination

The orc examples are the empirical claim: **for real production tasks, the explicit Style A pipeline is usually a better answer than the implicit Style B RLM**, and grain's event-sourcing makes both modes durable, queryable, and optimizable in ways an in-memory `RunTrace` can't be.

The mapping `pymupdf/openpyxl/python-docx → PDFBox/POI/docx4j` and `pandas → noj` is the "we don't need Python" claim. The mapping `Pyodide-in-Deno → SCI-in-JVM` is the "we don't need WASM sandboxing" claim. Together they collapse three layers of predict-rlm's plumbing into a single library that runs in the same process as your application.

### Four-layer stack comparison

| Layer                  | predict-rlm side                                                                                | orc/grain side                                                                                |
| ---------------------- | ----------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| **Insight**            | "model writes code that recursively calls a sub-LM" (paper)                                     | same                                                                                          |
| **Execution skeleton** | dspy.RLM — REPL truncation, action+extract, SUBMIT-as-exception, swappable interpreter Protocol | grain — event-sourced CQRS, defcommand/defquery/defprocessor, behavior-tree node Protocol     |
| **Production runtime** | predict-rlm — JSPI async, typed Pydantic sub-calls, file I/O, skills, tracing                   | orc — `repl-researcher :rlm true`, SCI sandbox, doc-skills, event-sourced trace, GEPA, judges |
| **Tool substrate**     | Pyodide micropip ecosystem                                                                      | JVM ecosystem (PDFBox/POI/docx4j) + noj                                                       |

Each row picks one premise. The full vertical comparison is the picture.

---

## Part 6 — Preview strategies: how each stack exposes context to the root LM

Three different answers to the same question: **the model needs enough metadata to decide what code to write, but not the actual content.**

### What the root LM literally sees per variable

#### dspy.RLM — `REPLVariable.from_value` (`dspy/primitives/repl_types.py`)

For each input variable, the LM sees a formatted text block:

````
Variable: `documents` (access it in your code)
Type: list
Description: PDF documents to analyze
Constraints:
Total length: 4,238,991 characters
Preview:
\```
[{"path": "/sandbox/input/documents/report.pdf"}, {"path": "/sandbox/...
...
"path": "/sandbox/input/documents/appendix.pdf"}]
\```
````

Construction:

```python
value_str = json.dumps(jsonable, indent=2) if dict-or-list else str(jsonable)
preview = value_str[:500] + "..." + value_str[-500:]   # head+tail at 1KB
```

**Fields shown:** name, type_name (Python type), desc (from FieldInfo), constraints (from FieldInfo), total_length (chars), preview (head+tail of `str(value)`).

**No hash.** The model has no way to detect "this variable is identical to one in a previous iteration."

#### predict-rlm — same as dspy

predict-rlm inherits `REPLVariable` unchanged. Its variable presentation is identical to dspy.RLM's. The added type contracts and Pydantic schema reconstruction live in the `predict()` tool, not in the variable preview surface.

#### orc — `value-preview` + `build-rlm-blackboard-metadata` (`components/orc-service/src/ai/obney/orc/orc_service/core/executor.clj`)

For each input variable the LM sees:

```
- documents: vector (string)  — PDF documents to analyze [context — large; metadata only here]
  meta: {:type :vector :count 2 :size 4238991 :hash 1684205321 :preview "[\"/sandbox/input/documents/report.pdf\" \"/sandbox/input/...\"]"}
```

Construction:

```clojure
(case type
  string  {:type :string :size (count v)         :hash (hash v) :preview (head-tail v 600)}
  map     {:type :map    :keys (vec (keys v)) :size (count s) :hash (hash v) :preview (head-tail s 600)}
  vector  {:type :vector :count (count v)     :size (count s) :hash (hash v) :preview (head-tail s 600)}
  coll    {:type :coll   :count (count v)     :size (count s) :hash (hash v) :preview (head-tail s 600)}
  scalar  {:type :scalar :size (count s)         :hash (hash v) :preview s})
```

`head-tail` keeps the head + ` … (N chars omitted) … ` + tail (matching dspy/predict-rlm); values shorter than the budget are returned verbatim with no marker. For very small budgets it degrades to head-only.

**Fields shown:** Malli schema description, custom desc, role marker (e.g. `[context — large; metadata only here]`), and a structured meta map: `:type` (Clojure-level kind), `:size` (chars), `:keys` (for maps) or `:count` (for vectors/colls), `:hash`, `:preview` (head + tail string with omitted-count marker).

### Cross-cutting differences

| Dimension                     | dspy.RLM / predict-rlm                                      | orc                                                                                                                              |
| ----------------------------- | ----------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| **Output shape**              | Multi-line formatted prose block                            | Single line + structured EDN map (LM-readable as data)                                                                           |
| **Truncation**                | head + "…" + tail (split at 500/500 = 1000 chars)           | head + " … (N chars omitted) … " + tail (default 600-char total budget; degrades to head-only for very small budgets)            |
| **Identity hash**             | none — LM can't detect "same value"                         | `(hash v)` — Clojure's structural hash on every preview                                                                          |
| **Type info**                 | Python `type(v).__name__` (e.g. "list", "dict")             | Clojure kind (`:vector`, `:map`, `:coll`, `:scalar`) **plus** Malli schema description from blackboard                           |
| **Size signal**               | `total_length` (string length of `str(value)`)              | `:size` (chars of `pr-str`) **plus** `:count` (collection length) **plus** `:keys` (for maps)                                    |
| **Schema info**               | Pydantic field `desc` and `constraints` (free-form strings) | Malli schema rendered into a description, plus optional `Field(:description …)` extracted separately                             |
| **Role markers**              | none                                                        | `[context — large; metadata only here]` flags the `:context-key` so the LM knows which variable is the "big one"                 |
| **Output fields shown to LM** | Only inputs are previewed                                   | Inputs _and_ outputs are listed: "Output variables (call `(final! {...})` to commit values):" with each `:writes` field's schema |

### Iteration history — how previous turns are shown

This is the _other_ preview channel — the LM doesn't just need to know what variables exist, it needs to know what it just tried.

#### dspy.RLM — `REPLEntry.format_output`

````
=== Step 3 ===
Reasoning: I need to count line items.
Code:
\```python
print(len(invoices))
\```
Output (524,891 chars):
{first 5000 chars of stdout}

... (514,891 characters omitted) ...

{last 5000 chars of stdout}
````

Default truncation: **10K chars** with `head + middle marker + tail`. Each REPLEntry is frozen (immutable); `REPLHistory.append` returns a new instance.

#### predict-rlm — same approach, tighter cap

Inherits the same `REPLHistory` object but PREDICT*RLM_INSTRUCTIONS documents the cap as **5K chars** and explicitly tells the model: *"What persists fully: Python variables. Everything you store in a variable survives intact. What gets truncated: printed output. Never rely on seeing full print output — if you need the data, it should be in a variable."\_

That explicit teaching is the predict-rlm contribution: dspy implements the truncation; predict-rlm tells the LM how to write code that survives it.

#### orc — `compress-history`

```
### Iteration 1
Code:
{first 400 chars of code}
Output:
{first 600 chars of stdout}
Result: {first 300 chars of result}

### Iteration 2
Code:
...
Error: {error message}
```

Per-section, head-only truncation: **code ≤ 400 chars, stdout ≤ 600 chars, result ≤ 300 chars**. No tail. The whole history is then bounded by `:history-preview-chars` (default 4000 in agentic.clj — capped _after_ per-section bullets are concatenated).

### What the design choices reveal

#### dspy/predict-rlm: "treat the LM like it can read text"

Variables are formatted as natural-language prose blocks. Truncation is **head+tail** because the assumption is "the start tells you the schema, the end tells you the recent state — the middle is filler." History is also head+tail. The model parses everything as English with embedded code fences.

The bet: LMs are good at reading semi-structured prose. Don't over-engineer the format.

#### orc: "treat the LM like it can read data"

Variables come back as **EDN maps** with named keys (`:type`, `:size`, `:hash`, `:keys`, `:preview`). The LM sees `{:type :vector :count 2 :hash 1684205321 :preview "..."}` and can reason about structure: "this is a 2-element vector, here's its hash for identity tracking." The preview itself uses **head + tail** truncation matching dspy/predict-rlm — so the LM sees both the start (schema/structure) and the end (recent state) — but the structured fields (`:keys`, `:count`, `:hash`) carry the bulk of the "what's the shape" signal regardless of how the preview was clipped.

Three orc-only signals worth highlighting:

1. **Hash on every variable.** This is the real differentiator. The LM can write code like:

   ```clojure
   (when (= (:hash (describe documents)) prior-hash)
     ;; same input as last iteration — skip re-extraction
     ...)
   ```

   dspy/predict-rlm have nothing equivalent — their LM has no way to assert "the variable I'm looking at is the same one I saw two turns ago."

2. **Structural keys/count, not just length.** `(:keys v)` for maps and `(:count v)` for vectors give the LM exact collection shape without it having to parse the preview string. dspy gives `total_length` (chars of `str(value)`), which conflates "how many items" with "how big each item is."

3. **The role marker `[context — large; metadata only here]`.** Tells the LM directly which variable is "the big one it's supposed to interrogate via predict()." dspy implicitly relies on the LM noticing that one variable's `total_length` is much larger than others.

#### History truncation — still head-only

Variable previews use head + tail (matching dspy/predict-rlm), but **iteration history** in `compress-history` is still per-section head-only — code (400 chars), stdout (600), result (300). dspy's head+tail history would catch "the answer is in the middle of a long stdout" cases that orc misses.

This is a deliberate tradeoff: the variable preview is what the LM uses to plan; the history is what it uses to remember what just happened. For history, the most relevant signal is usually "what was the most recent print" (head of the recent iteration's stdout), and the per-section caps stay tight so the prompt doesn't bloat across many iterations.

orc compensates by telling the LM (via the strategy docstring) to use `(println …)` sparingly and put real results in `(final! …)` — same teaching as predict-rlm's "store in variables, print summaries" guidance.

### Bottom line on previews

Three philosophies for the same surface:

|                 | "What is this variable?"                                                                            | "What just happened?"                                                    | Identity-tracking                                                |
| --------------- | --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ | ---------------------------------------------------------------- |
| **dspy.RLM**    | Prose block: type + length + head/tail string                                                       | Prose block: code + head/tail of stdout (10K)                            | None                                                             |
| **predict-rlm** | Same as dspy                                                                                        | Same as dspy with tighter cap (5K) and explicit teaching                 | None                                                             |
| **orc**         | Structured EDN map: type + size + count/keys + **hash** + head+tail preview + Malli schema + role marker | Per-section bullets: code (400) + stdout (600) + result (300), head-only | **Hash per variable** — LM can detect identity across iterations |

The shared thread: **never put the raw value in the prompt.** The differences are about whether the LM is consuming prose (dspy/predict-rlm) or data (orc), and whether identity is implicit (you'll notice it's the same string) or explicit (`:hash 1684205321`).

---

## Part 7 — Exploration: RLM-as-synthesizer (a third mode)

### The question that cracked this open

Should the RLM generate Clojure (current Style B), or should it generate orc behavior trees?

The current orc example set offers two modes per task:

- **Style A — hand-crafted BT**: humans wrote the workflow; LLM only fills in node-level instructions
- **Style B — agentic RLM**: LLM writes Clojure that runs once and produces a trajectory

There's a missing third mode that's the most strategically interesting:

- **Style C — RLM-as-synthesizer**: LLM uses RLM mode to _discover_ a workflow once on a representative input, then emits a durable BT. Future runs execute the BT (cheap, optimizable, traceable); the RLM is the workflow _designer_, not the per-run executor.

### Why C matters more than A or B alone

| Mode                       | LLM produces                       | Cost per run                        | Reusable | GEPA-optimizable                       | Best when                               |
| -------------------------- | ---------------------------------- | ----------------------------------- | -------- | -------------------------------------- | --------------------------------------- |
| **A — Hand-crafted BT**    | nothing (humans wrote it)          | low (just LLM nodes)                | yes      | yes per-node                           | structure known a priori                |
| **B — Agentic RLM**        | Clojure trajectory (ephemeral)     | high (full re-derivation every run) | no       | no — trajectories aren't reoptimizable | structure unknown / one-off             |
| **C — RLM-as-synthesizer** | a behavior tree (durable artifact) | high once, low forever              | yes      | yes on the synthesized BT              | structure discoverable, runs repeatedly |

Style B in production is "pay the LLM to re-derive the plan every time." That's the _opposite_ of what grain's event-sourced trace was built to enable. You get a beautiful trace of what happened but not what to do next time. The trajectory rots — even if you replay events, you can't `gepa/optimize` against a one-shot LLM-authored Clojure blob, because there's no per-node instruction to mutate.

A BT, by contrast, is structurally GEPA-optimizable per node, judge-instrumentable per node, versionable, A/B-testable. That's the entire grain bet.

### What "RLM generates BTs" would actually look like

orc already has every primitive needed:

1. **`mcp-sheet-builder`** is literally already "dynamic workflow generation from tool schemas" — but driven by MCP discovery, not by an exploratory RLM trajectory.
2. **DSL round-trip via uuidv5 stable node IDs** means a synthesized BT is a first-class data object — read-modeled, queryable, exportable, re-importable.
3. **`repl-researcher` :rlm true** already exists as the synthesizer's "thinking" mode.
4. **GEPA** can optimize the resulting BT's per-node instructions once it exists.

The synthesis loop:

```
1. User invokes (synthesize-workflow ctx {:goal …, :sample-inputs […]})
2. Internal repl-researcher runs Style B on a representative input —
   it gets to write Clojure, call (predict-all …), explore freely
3. After (final! …), an *additional* extract step asks the same LLM:
   "Given this trajectory, emit an orc DSL form that would replay this
    workflow on similar inputs, with stable node names."
4. That DSL form is built via build-workflow! → durable Sheet
5. Future runs use (orc/execute …) on the new sheet — no more LLM
   plan-derivation; only the per-node LLM calls
6. GEPA can now optimize the synthesized BT's instructions
```

This is essentially **trajectory distillation into a workflow**. The expensive RLM run is amortized across every subsequent BT execution.

### Decision criterion (mechanical)

- **Will this task class run more than ~5 times on structurally-similar inputs?** → Synthesize a BT (mode C). The amortized cost wins fast.
- **One-off exploration?** → Style B. Don't bother synthesizing.
- **Structure varies wildly per input?** → Style B. The synthesized BT would be wrong for the next input.
- **Structure consistent, content varies?** → Mode C. This is the sweet spot.

Most production document-processing workloads (the orc example set) are structure-consistent / content-varying. `invoice_processing` should be a synthesized BT, not a re-running RLM.

### Why predict-rlm/dspy can't do this

Style B (predict-rlm style) is what you do when you don't have orc's substrate. It's the _only_ answer Trampoline can give because they don't have behavior trees, event-sourced workflows, GEPA, or a DSL to emit into. **Their stack forces every task to be exploratory.**

orc's stack lets you **use the RLM where it's actually best** (discovering structure once) and then _graduate_ to the artifact production wants (a BT). That's a strict superset of what predict-rlm can express. The synthesizer mode is the move that makes orc's RLM coherent with the rest of the orc value prop, instead of an oddly-shaped escape hatch.

### Open design questions

1. **The extract prompt.** What exactly do we ask the LLM to emit? Raw DSL forms, or a structured intermediate that we render to DSL?
2. **Generalization signal.** A trajectory that worked on one PDF doesn't necessarily generalize. Run synthesis on N representative inputs and ask the LLM to find common structure? Let it emit branches (`fallback`) where uncertain?
3. **Code-node fidelity.** When the trajectory contains complex Clojure (e.g., the per-document loop in `invoice_processing/agentic.clj`), do we lift it verbatim into a `code` node, or refactor it into smaller nodes? Trade: verbatim is faithful but opaque; refactored is granular but the LLM might mis-decompose.
4. **Round-tripping the output schema.** The synthesized BT's outputs must match the `:writes` shape Style B produced. Easiest: pass the original Pydantic/Malli schema through unchanged.
5. **Cost accounting.** Synthesis is expensive (full Style B run + extract step). A `(synthesized-from sheet-id …)` tag on the Sheet plus a `synthesis-cost` field on the read model would let us measure when amortization kicks in.
6. **Re-synthesis trigger.** When inputs drift (new vendor PDF format), the BT will fail. Auto-trigger re-synthesis on N consecutive failures? Manual? Periodic?

### Status

- Concept stage — not implemented
- All required primitives exist (DSL round-trip, mcp-sheet-builder, repl-researcher :rlm, GEPA, judges)
- Next step: design the `synthesize-workflow` API and the trajectory→DSL extract prompt
- Pick one example (probably `invoice_processing`) to run as a synthesis spike
- Compare: cost of synthesized BT × N runs vs Style B × N runs at break-even N
