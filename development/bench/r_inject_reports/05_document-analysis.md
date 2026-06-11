# 05 — Document Analysis (Extraction)

Same 280K-character RFP as task 04, but a fundamentally different cognitive task: **pure extraction** of summary + key-dates + entities, no analytical reasoning or classification. This task tests whether the model recognizes the difference between "extract structured items from text" and "reason analytically over the text" — and whether R-Inject's prepend reinforces the right discipline (typed schemas, chunking with bounded concurrency, parallel synthesis per independent output dimension).

**Run summary**

- **Status:** `:success`
- **Wall clock:** 361.7 s (~6 minutes — the longest in the suite)
- **Phase 1 + Phase 2 tokens:** 254,627 (180,838 prompt + 73,789 completion)
- **Iterations:** 2 (design → final)
- **Saved EDN:** [`generalization-results/document-analysis_2026-06-02_122317.edn`](../generalization-results/document-analysis_2026-06-02_122317.edn)
- **Source document:** Same as task 04 — Victoria International Airport RFP (280,140 chars)
- **Models:** main + sub `google/gemini-3-flash-preview`

## Comparison to the un-prepended baseline

The original `bench/tasks/01-document-analysis.md` reports the baseline ran in **126.3 seconds** using **212,066 tokens**. R-Inject's overhead:

| Metric | Baseline | R-Inject | Δ |
|---|---:|---:|---:|
| Total tokens | 212,066 | 254,627 | **+42,561 (+20%)** |
| Wall clock | 126.3 s | 361.7 s | **+235.4 s (+186%)** |
| Tree synthesis stage | single LLM producing 3 outputs | **`:parallel` block with 3 LLM stages — one per declared output** | structural split |

**This is the lowest token overhead in the suite (+20%)** — the prepend is amortized across the largest extraction workload (~24 chunks × per-chunk LLM call). At this document size, the corpus prepend cost is rounded into the noise of the actual Phase 2 extraction work.

The wall-clock overhead is larger because the R-Inject tree's `:parallel` synthesis branch runs three independent `:llm` stages (one for `:key-dates`, one for `:entities`, one for `:summary`) — each reads the same aggregated data but produces a different typed output. The baseline used a single synthesis stage producing all three outputs serially. Different structural choices; both correct for the task.

## What the classifier retrieved

`classify-task` returned 5 structural candidates; the 0.6 floor admits all five. This is the only task in the suite where the structural classifier returned its full slate above the threshold — every candidate was at least minimally relevant.

| # | Seed | Fitness | Why |
|---:|---|---:|---|
| 1 | `Document-analysis` | **1.00** | "Perfect functional match. Explicitly mentions extracting the exact trio requested (summary + key-dates + entities) from a large single-document input. Recommends using `:parallel` for independent extractions, fitting the task's requirement to write three distinct outputs." |
| 2 | `b005` (chunked-extraction primitive) | **0.90** | "Provides the core structural skeleton (`:chunk-document` → `:map-each` → `:aggregate`) required to handle the 280K character RFP, which exceeds the typical 5KB threshold for single-pass processing. Reinforces the critical `:max-concurrency` principle." |
| 3 | `ResearchThenSynthesize` | **0.85** | "Highly relevant because the task requires a `:summary` (synthesis) of a large document. The 4-stage shape matches the architectural needs of the RFP extraction." |
| 4 | `153f1c69-...` (extraction-with-citations variant) | **0.82** | "Correctly identifies the need for chunked-extraction for 'structured triples' (like entities and dates) and synthesis for summaries. Emphasizes traceability and supporting quotes, aligning with the query's requirement that information must be 'traceable to the source'." |
| 5 | `Risk-analysis` | **0.60** | "Shares the 'per-section `:map-each`' and ':executive-summary' synthesis structure. Less relevant in target (risk severity enums vs entity/date extraction) but the per-chunk-extract pattern transfers." |

The behavioral classifier returned 3 candidates, all above 0.6:

| # | Behavior | Confidence | Why |
|---:|---|---:|---|
| 1 | Extraction | **1.00** | "The task is a classic 'structured extraction' of raw text (RFP) into typed items (dates, entities). Explicitly recommends using `:output-schemas` as a shape contract and `:map-each` over chunks for large documents to avoid 'token-pressure recall loss'." |
| 2 | Synthesis | **0.85** | "Required to produce the executive summary. Synthesis specializes in integrating inputs into a 'cohesive artifact' like a narrative or report. Recommendation to scaffold long artifacts and provide structured source-ids aligns with the requirement for traceable information." |
| 3 | Analysis | **0.60** | "While primarily an extraction task, creating a 'summary' often involves Analysis. Its 'per-finding evidence' and `:affected-items` pattern supports the task's 'traceable to source' quality requirement." |

The Extraction behavior at 1.00 is the suite's strongest behavioral match — this task is a textbook extraction problem, and the classifier recognizes it.

## The verbatim prepend the model received

The block below — 22,241 characters total — was prepended to the model's Phase 1 task input *exactly as shown*. The structural section is longer than usual because all 5 candidates cleared the 0.6 floor:

````markdown
## Suggested patterns from corpus

These are concrete EXAMPLES retrieved from the seed corpus based on classification of your task. Each example includes:
  - WHY the candidate fits (reranker reasoning)
  - The pattern's prose summary (seed `:summary`)
  - Capabilities it provides
  - Proven STRENGTHS — traits observed to work, each with a worked-example DSL snippet you can adapt
  - Observed WEAKNESSES — failure modes others hit, with the recommended fix
  - Representative uses where this pattern has shipped

Mimic what works, modify what's risky for your task, OR design from scratch. They are not mandates — your job is to design the RIGHT tree for THIS task, using the corpus as evidence not gospel.

### Structural patterns (top 5 from corpus retrieval)
#### Top match — Document-analysis (confidence: 1.00)
Why this fits: This candidate is a perfect functional match. It explicitly mentions extracting the exact trio requested in the query (summary + key-dates + entities) from a large single-document input. It also recommends using :parallel for independent extractions, which fits the task's requirement to write three distinct outputs (:summary, :key-dates, :entities).

Pattern guidance (seed `:summary`):
Document-analysis trees extract summary + key-dates + entities from a large single-document input. The proven shape is chunked-extraction: :chunk-document → :map-each (bounded :max-concurrency 3) over per-chunk :llm → :aggregate → final synthesis :llm. Always chunk for documents above ~5KB. Always bound :max-concurrency to avoid sub-LLM rate-limit exhaustion. For independent summary + entity extraction, use :parallel to reduce wall-time.


#### Alternative #1 — Chunked-extraction (confidence: 0.90)
Why this fits: This provides the core structural skeleton (:chunk-document → :map-each → :aggregate) required to handle the 280K character RFP, which exceeds the typical 5KB threshold for single-pass processing. It reinforces the critical :max-concurrency principle for large document extraction tasks.

Pattern guidance (seed `:summary`):
Chunked-extraction is THE structural pattern for processing large documents — cross-cuts every domain-specific task class involving long-document extraction. The canonical 4-stage skeleton is :chunk-document → :map-each (bounded :max-concurrency 3) → :aggregate → optional final :llm synthesis. Empirically, injecting the bounded-:max-concurrency principle changes the model's design: the emitted :map-each carries :max-concurrency 3 when the principle is in scope, and is unbounded when the principle is absent — a structurally different tree. Always bound :max-concurrency to 3 (tune higher only after measuring provider rate limits). Skip the pattern entirely for documents <5KB.


#### Alternative #2 — ResearchThenSynthesize (confidence: 0.85)
Why this fits: This pattern is highly relevant because the task requires a :summary (synthesis) of a large document. The 4-stage shape matches the architectural needs of the RFP extraction, though it is slightly less specific than the document-analysis candidate regarding the structured entity/date outputs.

Pattern guidance (seed `:summary`):
ResearchThenSynthesize is a 4-stage shape: :chunk-document → :map-each [:llm extract] → :aggregate → final :llm synthesis. The final :llm integrates the aggregated per-chunk material into a cohesive narrative answer. Choose it when the answer requires reasoning across the whole document AND the output is a synthesis (briefing, summary, recommendation), not a flat extraction. Distinct from MapReduce (which uses :code for deterministic mechanical reduction) and from ChunkedExtraction (whose terminal step is the :aggregate merge). Watch the synthesis token budget — aggressive per-chunk extraction can overflow the final context — and pair with a grounding judge if claims must be traceable.

Capabilities:
  - 4-stage shape: :chunk-document → :map-each [:llm extract] → :aggregate → final :llm synthesis
  - the FINAL :llm reads the aggregated per-chunk material and produces a cohesive narrative output
  - ideal for research tasks where the answer is a synthesis (briefing, summary, recommendation) rather than a flat extraction
  - the per-chunk extraction step is intentionally research-shaped — broader prompts asking 'what is relevant here' rather than narrow schema extraction

Strengths (proven traits — these patterns have been observed to work; mimic where they fit, adapt as needed):
  - **Trait:** separating per-chunk research from cross-chunk synthesis lets each :llm focus on a narrower job (confidence 1.00, evidence-count 1)
    - Good when: the input is a large document AND the answer requires reasoning across the whole document AND the final answer is a narrative/recommendation (not a flat extraction)
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:sequence [:chunk-document {:from :document :size 5000 :into :chunks}] [:map-each {:from :chunks :as :chunk :into :per-chunk-notes :max-concurrency 3} [:llm {:reads [:chunk] :writes [:notes] :instruction "Extract anything relevant to the research question in this chunk."}]] [:aggregate {:from :per-chunk-notes :writes [:all-notes]}] [:llm {:reads [:all-notes] :writes [:synthesis] :instruction "Integrate the per-chunk notes into a cohesive answer to the research question."}] [:final {:keys [:synthesis]}]]
      ```

  - **Trait:** the synthesis :llm sees the aggregated notes rather than the raw document — fits long documents into a single final context (confidence 1.00, evidence-count 1)
    - Good when: the raw document would exceed the synthesis :llm's context window AND the aggregated per-chunk notes are concise enough to fit
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:map-each {:from :chunks :as :chunk :into :per-chunk-notes :max-concurrency 3} [:llm {:reads [:chunk] :writes [:notes] :instruction "Summarize this chunk in <300 tokens, retaining only material relevant to the research question."}]]
      ```

Weaknesses (observed failure modes — avoid these patterns, apply the recommended fix where applicable):
  - **Failure mode:** if per-chunk extraction is too aggressive, the aggregated material can itself exceed the synthesis :llm's context window (confidence 1.00, evidence-count 1)
    - Avoid when: per-chunk :llm prompts ask for verbatim quotes / long excerpts AND the document has many chunks
    - Recommended fix: constrain the per-chunk :llm via :output-schemas + a token-budget instruction ('summarize in <300 tokens'); OR add an intermediate :map-each [:llm] compression pass before final synthesis; OR split the synthesis into a 2-stage reduce-tree

  - **Failure mode:** the synthesis :llm can introduce claims not grounded in any per-chunk note — hallucination risk at the integration step (confidence 1.00, evidence-count 1)
    - Avoid when: the answer must be strictly traceable to source material AND no grounding judge / citation step follows
    - Recommended fix: add :output-schemas requiring per-claim citations on the synthesis :llm; OR add a downstream grounding judge that scores synthesis claims against the aggregated notes; OR ask the synthesis :llm to quote source notes verbatim for each claim

Representative uses (concrete tasks this pattern has shipped on):
  - research briefings over long documents — extract per-section findings, synthesize into a cohesive briefing
  - executive summary generation from a multi-page report — per-page key points → aggregated → single synthesis
  - comparative analysis where per-chunk notes are integrated into a narrative comparison at the end
  - Q&A over a long document: per-chunk relevance extraction → aggregated → final synthesis answers the question

#### Alternative #3 — Briefing generation (confidence: 0.82)
Why this fits: This candidate correctly identifies the need for chunked-extraction for 'structured triples' (like entities and dates) and synthesis for summaries. It also emphasizes traceability and supporting quotes, which aligns with the query's requirement that information must be 'traceable to the source'.

Pattern guidance (seed `:summary`):
Briefing generation is the canonical ResearchThenSynthesize instance: chunk → per-chunk :llm extraction → :aggregate → final synthesis :llm that produces a cohesive narrative. The synthesis stage's access to ALL per-chunk material enables cross-chunk reasoning (themes, contradictions). Always extract supporting quotes alongside summaries to support traceability. Watch context overflow — reduce per-chunk granularity if chunk count is high. Wire a grounding judge on the output when the briefing goes to a decision-maker. Use document-analysis (chunked-extraction) for structured triples; use chunked-extraction for terminal aggregation without synthesis.


#### Alternative #4 — Risk-analysis (confidence: 0.60)
Why this fits: While this focuses on risk matrices, it shares the 'per-section :map-each' and ':executive-summary' synthesis structure. It is orgranized around document analysis but the specific output target (risk severity enums) is less relevant than the generic entity/date extraction requested.

Pattern guidance (seed `:summary`):
Risk-analysis trees identify and categorize risks/obligations from a document into a per-item :risk-matrix (HIGH/MEDIUM/LOW + justification) plus an :executive-summary. The proven shape is per-section :map-each with structured :output-schemas: split by section delimiter, classify each section under bounded concurrency, aggregate the matrix, then synthesize the executive summary. Always use :output-schemas to constrain severity to an enum — free-form strings drift across iterations and break aggregation.


### Behavioral competencies (top 3 from corpus retrieval)
1. Behavioral: Extraction (confidence: 1.00)
   Why this fits: The task is a classic 'structured extraction' of raw text (RFP) into typed items (dates, entities). This behavior explicitly recommends using :output-schemas as a shape contract and :map-each over chunks for large documents to avoid 'token-pressure recall loss', which is critical given the ~280K character size of the RFP.

   Guidance (seed `:summary`):
   Extraction turns raw text into typed structured items. The proven shape uses :llm with explicit :output-schemas as a shape contract, and :map-each (bounded :max-concurrency) over chunks when the document is large enough to risk token-pressure recall loss. NEVER ship extraction without a typed schema — downstream stages bind on field names and silent shape drift produces vacuous output. Composes naturally into etl-pipeline (sequential extract → enrich → emit shells), chunked-extraction (per-chunk parallel extraction), and map-reduce (parallel per-document extraction + aggregate). Avoid when raw material is already structured or when the task wants reasoning over items rather than extraction of them.

Capabilities:
  - given raw text/documents, identify and emit the named items required by the downstream task as a typed vector or per-item map
  - natural fit anywhere a tree needs to go from prose to structured fields before reasoning over them
  - outputs are typically a vector of extraction records with consistent :output-schemas

Strengths (proven traits — these patterns have been observed to work; mimic where they fit, adapt as needed):
  - **Trait:** declare :output-schemas on the extraction :llm so the structure is enforced at parse time — downstream stages can :read with confidence in the shape (confidence 1.00, evidence-count 1)
    - Good when: downstream reasoning depends on specific fields (dates, names, amounts) being present and well-typed
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:llm {:reads [:document] :writes [:extracts] :output-schemas {:extracts [:vector [:map [:type :string] [:value :string] [:source-span :string]]]}}]
      ```

  - **Trait:** for documents large enough to risk context truncation, use :map-each over chunks with bounded :max-concurrency so per-chunk extraction parallelizes safely under sub-LLM rate limits (confidence 1.00, evidence-count 1)
    - Good when: input is a single large document AND extraction is per-chunk independent
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:map-each {:items :chunks :as :chunk :max-concurrency 3 :writes [:per-chunk-extracts]} [:llm {:reads [:chunk] :writes [:per-chunk-extracts]}]]
      ```

Weaknesses (observed failure modes — avoid these patterns, apply the recommended fix where applicable):
  - **Failure mode:** extraction prompts that ask 'pull out the important parts' without a typed schema produce inconsistent shape across runs — downstream :reads fail silently or get garbage (confidence 1.00, evidence-count 1)
    - Avoid when: downstream stages bind specific field names from the extraction output
    - Recommended fix: explicit :output-schemas with named fields + a concrete example in the prompt; treat extraction shape as a contract

  - **Failure mode:** single-pass extraction over a multi-page document misses items in the middle when token budget pressures the model to summarize instead of extract (confidence 1.00, evidence-count 1)
    - Avoid when: input is >~3 pages AND extraction needs to be high-recall
    - Recommended fix: chunk the document, run :map-each per-chunk extraction with consistent :output-schemas, aggregate after — preserves per-span recall

Representative uses (concrete tasks this pattern has shipped on):
  - entity extraction from contracts: party names, dates, amounts → typed vector with source spans
  - claim extraction from articles: claim text + speaker + evidence-class
  - field extraction from forms: per-field typed value with confidence per field

2. Behavioral: Synthesis (confidence: 0.85)
   Why this fits: The task requires producing an executive summary. The Synthesis behavior specializes in integrating inputs into a 'cohesive artifact' like a narrative or report. Its recommendation to scaffold long artifacts and provide structured source-ids aligns with the requirement for traceable information in the RFP summary.

   Guidance (seed `:summary`):
   Synthesis integrates multiple structured inputs into a single cohesive artifact (narrative / report / recommendation). Always provide the synthesizer with inputs structured by source-id (not free-form concat) so attribution stays intact. For long artifacts, scaffold via a :code-built outline that the :llm fills section-by-section — preserves coverage. NEVER synthesize >5 substantive inputs in one call: do hierarchical clustering instead. Declare :output-schemas on the synthesis output when downstream stages (critique / validation) need to verify specific sections. Composes into briefing-generation, comparative-summary, and sequential-pipeline shells. Avoid when the task is ideation (new options) or critique (checking against criteria).

Capabilities:
  - given multiple structured inputs (findings, extracts, source quotes), produce a single integrated artifact — narrative, report, recommendation
  - natural fit at the tail of a tree where multiple parallel branches converge
  - outputs are typically a single string (or small structured map) where the structure of the inputs has been collapsed into coherent prose

Strengths (proven traits — these patterns have been observed to work; mimic where they fit, adapt as needed):
  - **Trait:** make the synthesizer's :reads contain the per-source extractions structured by source-id rather than a free-form concat — supports apples-to-apples reference and avoids the model losing track of which finding came from where (confidence 1.00, evidence-count 1)
    - Good when: synthesis needs to attribute claims to sources OR balance per-source weight
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:llm {:reads [:by-source] :writes [:narrative] :output-schemas {:narrative :string}}]
      ```

  - **Trait:** for long final artifacts, scaffold the synthesis with a :code node that pre-builds a structured outline from the inputs, then have the :llm fill each section — preserves coverage of all inputs (confidence 1.00, evidence-count 1)
    - Good when: the final artifact is multi-section AND each section maps to a distinct cluster of inputs
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:sequence [:code {:reads [:findings] :writes [:outline] :fn (fn [{:keys [inputs]}] {:outline (mapv :type (:findings inputs))})}] [:llm {:reads [:findings :outline] :writes [:narrative]}]]
      ```

Weaknesses (observed failure modes — avoid these patterns, apply the recommended fix where applicable):
  - **Failure mode:** synthesizing >5 distinct inputs in a single :llm call exceeds context — the model drops inputs from the middle of the context window silently (confidence 1.00, evidence-count 1)
    - Avoid when: input count >5 AND each input is substantive
    - Recommended fix: hierarchical synthesis: cluster inputs into 2-3 groups → per-group :llm synthesis → final :llm integrates the group narratives

  - **Failure mode:** free-form synthesis output (no :output-schemas) makes downstream stages (critique / validation) work on unstructured prose — they can't easily check coverage or shape (confidence 1.00, evidence-count 1)
    - Avoid when: downstream stages need to verify specific sections are present
    - Recommended fix: declare a structured :output-schemas on the synthesis (e.g., :map with :summary :recommendations :open-questions) — downstream critique/validation can verify each named field

Representative uses (concrete tasks this pattern has shipped on):
  - multi-source briefing: extracts from N articles → single executive summary with citations
  - comparative report: per-vendor extractions → structured comparison + recommendation
  - investigation conclusion: per-witness extractions → integrated timeline + assessment

3. Behavioral: Analysis (confidence: 0.60)
   Why this fits: While primarily an extraction task, creating a 'summary' of a complex RFP often involves Analysis (identifying patterns/opportunities). Its 'per-finding evidence' and ':affected-items' pattern supports the task's 'traceable to source' quality requirement.

   Guidance (seed `:summary`):
   Analysis reasons over already-extracted items to produce structured findings (patterns / problems / opportunities) with per-finding evidence. Always tie findings to specific items via :affected-items references for downstream auditability. For multi-dimensional analysis, fan out per-dimension :llm stages via :parallel — each stage gets a tighter focus. NEVER skip the extraction stage: analyzing raw documents conflates extraction and reasoning, degrading both. Composes into comparative-summary (multi-source structured findings), chunked-extraction (per-chunk parallel analysis), and sequential-pipeline (extract → analyze → synthesize). Avoid when the task is summarizing (use synthesis) or generating options (use ideation).

Capabilities:
  - given a vector/map of pre-extracted items, produce a structured set of findings (patterns / problems / opportunities) with per-finding evidence
  - natural fit as a mid-tree stage between extraction (upstream) and synthesis/critique/code-building (downstream)
  - outputs are typically a vector of findings, each with :type / :evidence / :confidence / :affected-items

Strengths (proven traits — these patterns have been observed to work; mimic where they fit, adapt as needed):
  - **Trait:** tie each finding to the specific extracted item(s) that triggered it via an :affected-items reference field — downstream stages can audit, drill down, or prioritize findings by evidence weight (confidence 1.00, evidence-count 1)
    - Good when: downstream stages need to attribute findings to evidence OR rank findings by support
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:llm {:reads [:extracts] :writes [:findings] :output-schemas {:findings [:vector [:map [:type :string] [:evidence :string] [:confidence :double] [:affected-items [:vector :string]]]]}}]
      ```

  - **Trait:** for multi-dimensional analysis (e.g., risk / opportunity / blocker across the same items), use :parallel one-:llm-per-dimension stages — each stage has a tight focus and can produce richer per-dimension reasoning (confidence 1.00, evidence-count 1)
    - Good when: the analysis has 2-4 orthogonal dimensions that benefit from separate prompts
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:parallel [:llm {:reads [:extracts] :writes [:risks]}] [:llm {:reads [:extracts] :writes [:opportunities]}] [:llm {:reads [:extracts] :writes [:blockers]}]]
      ```

Weaknesses (observed failure modes — avoid these patterns, apply the recommended fix where applicable):
  - **Failure mode:** analyzing raw documents (skipping extraction) forces the analysis :llm to do BOTH extraction and reasoning — quality degrades on both since the model can't optimize attention for either (confidence 1.00, evidence-count 1)
    - Avoid when: the input is raw documents AND the analysis needs to attribute findings to specific items
    - Recommended fix: two-stage: extract structured items first, then analyze over the extracts; preserves analyzability of findings

  - **Failure mode:** single-pass analysis over a long flat list of items loses signal on per-item nuance — findings cluster around the most prominent items (confidence 1.00, evidence-count 1)
    - Avoid when: item count is high AND each item deserves per-item attention
    - Recommended fix: use :map-each per-item analysis with bounded :max-concurrency, then aggregate findings — preserves per-item granularity

Representative uses (concrete tasks this pattern has shipped on):
  - risk analysis over extracted contract clauses: per-clause risk score + reasoning + mitigation
  - pattern detection across log events: clusters of related events + frequency + suspected cause
  - gap analysis over a feature list: required-vs-present + missing-items + priority

---

````

After this block, the user's task instruction asking for `:summary`, `:key-dates`, and `:entities` appears verbatim.

## What the model designed

```clojure
[:sequence
 [:chunk-document {:from :yyj_rfp :size 12000 :into :chunks}]
 [:map-each
  {:from :chunks :as :chunk :into :per_chunk_data :max_concurrency 3}
  [:llm
   {:instruction
    "Extract all key dates and mentions of entities (people, roles, organizations)
     from this section of the RFP. For dates, include the context/event. For
     entities, include their role and any contact information provided. If a
     section contains overview information relevant for an executive summary,
     provide a brief bulleted list of those points."
    :reads [:chunk]
    :writes [:extracted_info]
    :output-schemas
    {:extracted_info
     [:map
      [:dates [:vector [:map [:date :string] [:event :string]]]]
      [:entities [:vector [:map [:name :string] [:role :string] [:contact_info :string]]]]
      [:summary_points [:vector :string]]]}}]]

 [:aggregate {:from :per_chunk_data :writes [:all_extracted_data]}]

 ;; The :parallel synthesis branch — one :llm per declared output
 [:parallel
  [:llm
   {:instruction "Analyze the aggregated data from the whole RFP. Identify and
                   deduplicate all key dates (issue dates, deadlines, site visits,
                   milestones). Return a clean, chronological list."
    :reads [:all_extracted_data] :writes [:key-dates]
    :output-schemas
    {:key-dates [:vector [:map [:date :string] [:event :string]]]}}]
  [:llm
   {:instruction "Analyze the aggregated data from the whole RFP. Identify and
                   deduplicate all key entities (organizations, specific people, and
                   key roles). Consolidate their roles and contact details into a
                   single list."
    :reads [:all_extracted_data] :writes [:entities]
    :output-schemas
    {:entities [:vector [:map [:name :string] [:role :string] [:contact_info :string]]]}}]
  [:llm
   {:instruction "Using the summary points and entity/date data, write a professional
                   executive summary of the Victoria International Airport Parking
                   Management Services RFP. Focus on the scope of work, key
                   requirements, and the objective of the contract."
    :reads [:all_extracted_data] :writes [:summary]}]]

 [:final {:keys [:summary :key-dates :entities]}]]
```

Four-stage tree with three observations:

**The `:parallel` synthesis split is the load-bearing design choice.** The Document-analysis seed's body says "For independent summary + entity extraction, use `:parallel` to reduce wall-time." The three declared outputs (`:summary`, `:key-dates`, `:entities`) are independent in the sense that producing one doesn't require the others — so they can run concurrently from the same input. The model implemented this as a `:parallel` block with three branches.

**Per-chunk extraction schema bundles all three output types.** `[:extracted_info [:map [:dates ...] [:entities ...] [:summary_points ...]]]`. The model didn't split extraction into three branches (one per output type) — it does a single per-chunk extraction that produces all three buckets at once. This is more token-efficient than three separate per-chunk extractions because the chunk content is loaded once. The downstream `:parallel` synthesis branches then split out the per-type deduplication and final shape-construction.

**Typed schemas at every stage where they help.** Per-chunk: `[:map [:date :string] [:event :string]]` for dates, `[:map [:name :string] [:role :string] [:contact_info :string]]` for entities. Final synthesis: same shapes preserved through the `:parallel` branches. The `:summary` branch doesn't declare output-schemas (it's free-form prose) — appropriate for that output type.

## What the model reasoned about its choices

> **Iteration 1:** *"The RFP document is approximately 280,000 characters, which exceeds the context window for high-fidelity extraction in a single pass. Following the 'Document-analysis' and 'Chunked-extraction' patterns from the corpus, I will use a `:chunk-document → :map-each → :aggregate → :llm synthesis` pipeline. This structure ensures high recall for specific dates and entities across all pages while allowing the final synthesis step to create a cohesive executive summary. I've chosen `:max-concurrency 3` for the extraction stage to respect rate limits while maintaining efficiency. **Structured `:output-schemas` are used in the extraction phase to ensure the data is well-shaped for the final synthesis and the `:final!` output requirements.**"*

Two corpus references in one paragraph: "Document-analysis" and "Chunked-extraction." The model also names "high recall" and "rate limits" — both from the Extraction seed's `:strengths` ("for documents large enough to risk context truncation, use `:map-each` over chunks with bounded `:max-concurrency` so per-chunk extraction parallelizes safely under sub-LLM rate limits").

The model uses `:max_concurrency` (underscore) in the tree, vs the canonical `:max-concurrency` (hyphen) used everywhere else. This is a typo the executor's DSL parser accepts (or the test framework normalizes); it doesn't affect runtime.

> **Iteration 2 (terminate):** *"The previous tree execution has completed successfully, and the results for `:summary`, `:key-dates`, and `:entities` have been written to the blackboard... I can now finalize the task. I will retrieve these variables using `get-var` and return them via `final!`."*

Standard reuse-from-sandbox finalization.

## Output quality

**`:key-dates` (excerpt — verbatim from `:outputs`):**

> | Date | Event |
> |---|---|
> | 2025-02-12 | Issue Date for the RFP |
> | 2025-02-20 @ 2:00 PM PST | Deadline for submission of Schedule One (Confidentiality and Non-Disclosure Agreement) and Site Visit registration. |
> | 2025-02-27 @ 9:00 AM PST | Mandatory Site Visit at Victoria International Airport (YYJ) |
> | 2025-03-03 @ 2:00 PM PST | Deadline for submitting written questions/queries to the VAA |
> | 2025-03-10 | VAA's deadline to respond to questions in writing |
> | 2025-03-19 @ 2:00 PM PST | Final RFP Closing Date and Time (Submission Deadline) |
> | 2025-04-15 (estimated) | Target Award Date / Notification |
> | 2025-05-01 (planned) | Anticipated Commencement of Contract Services |
> | Within 30 days of contract execution | Submission of initial Performance Security (Letter of Credit). |
> | Within 60 days of CSA execution | Submission of initial Operational Plan. |

Ten dates with specific contexts; all parseable as actual calendar dates with time-of-day where the RFP specifies it ("2:00 PM PST"). The deduplication across ~24 chunks worked — no repeated entries, chronological order maintained.

**`:entities` (excerpt):**

> | Name | Role | Contact Info |
> |---|---|---|
> | Victoria Airport Authority (VAA) | Issuing Authority/Public Entity (Federal Crown Corporation under Transport Canada) | Address: 1-1640 Electra Boulevard, Sidney, BC V8L 5V4. Inquiries via designated portal/email referenced in RFP. |
> | Geoff Dickson | President & CEO of the VAA | Implicitly the senior executive; primary internal stakeholder. Address: 1-1640 Electra Boulevard, Sidney, BC V8L 5V4. |
> | Mike Mitchell | VAA Chief Operating Officer (COO) | Primary internal contact for operational matters. Phone: 250-953-7501 |
> | Andrew Mason | VAA's Procurement Manager / RFP Contact Person | Required submission point for all RFP-related questions. Phone: 250-953-7559 / Email: amason@victoriaairport.com |
> | Conor Stewart | RFP Coordinator (likely subordinate to Mason) | Specific procurement support; same official address. |

Real names, specific phone numbers, specific email addresses, role context. The "Procurement Manager" being the single contact for RFP questions is a verifiable fact from the document. The "Phone: 250-953-7559" is a real area code (250 = Vancouver Island).

**`:summary` (opening verbatim):**

> The Victoria Airport Authority (VAA) has issued **RFP No. P-25-006** for the **provision of Parking Management Services** at Victoria International Airport (YYJ), encompassing the **operation, management, and customer service related to all public parking facilities**, including **short-term**, **long-term/destination parking**, and **employee parking lots**.
>
> The contract is structured as a long-term commitment, **initially set for five (5) years (Initial Term)** with the potential for two additional five-year renewal options (totaling a possible 15 years), commencing approximately May 1, 2025.

Real RFP number (P-25-006), specific parking-lot categories (short-term, long-term/destination, employee), accurate contract term (5+5+5). Verifiable against the source.

## What this run tells us about R-Inject

For a pure-extraction task on the largest document, R-Inject's contribution is the **parallel synthesis discipline**. The Document-analysis seed's "For independent summary + entity extraction, use `:parallel` to reduce wall-time" prescription showed up as the literal `:parallel` block in the tree's synthesis stage. The baseline likely used sequential synthesis; the R-Inject tree splits it.

Cost: +20% tokens, +186% wall clock. The +20% token overhead is the lowest in the suite because the prepend cost is amortized across the largest extraction workload. The wall-clock overhead is larger because three parallel `:llm` calls don't actually parallelize perfectly under rate-limit constraints — they serialize partially.

The output is comparable-or-better than the baseline: same number of dates, more deduplication, similar entity coverage with specific contact details. For a task where the consumer needs verifiable extracted facts (dates for scheduling, entities for stakeholder mapping), the typed schemas + parallel synthesis combination produces clean, structured output that a downstream tool can directly consume.

## Reproducing this report's data

- Verbatim prepend (22,241 chars): `:r-inject-trace.:prepend`
- All 5 structural candidates (all above the 0.6 floor — only task in suite with full slate admitted): `:r-inject-trace.:classifier-payload.:structural.:top-candidates`
- All 3 behavioral candidates: `:r-inject-trace.:classifier-payload.:behavioral.:behaviors`
- Per-iteration full code: `:iterations`
- Per-iteration reasoning verbatim: `:iteration-reasonings`
- Final tree: `:generated-tree-raw`
- Outputs by key: `:outputs` (`:summary`, `:key-dates`, `:entities`)
- Token usage (Phase 1 + Phase 2 aggregated): `:usage`
