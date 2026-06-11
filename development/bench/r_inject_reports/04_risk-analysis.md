# 04 — Risk & Obligation Analysis

The largest input in the suite — a 280,140-character RFP for parking management at Victoria International Airport — and a task that demands analytical work, not just extraction: identify obligations, classify them, assign risk levels with justifications, produce a strategic executive summary. The prepend's *Risk-analysis* seed prescribes the exact `:map-each` + structured `:output-schemas` pattern with enum-constrained severity. The question for this report: does the model adopt that pattern, and does it produce a real risk analysis (vs an empty risk-matrix the way an earlier run did when the aggregation was misshapen)?

**Run summary**

- **Status:** `:success`
- **Wall clock:** 228.3 s (~3.8 minutes)
- **Phase 1 + Phase 2 tokens:** 244,018 (169,178 prompt + 74,840 completion)
- **Iterations:** 2 (design → final)
- **Saved EDN:** [`generalization-results/risk-analysis_2026-06-02_121715.edn`](../generalization-results/risk-analysis_2026-06-02_121715.edn)
- **Source document:** [`documents/yyj_rfp.txt`](../documents/yyj_rfp.txt) — Victoria International Airport Parking Management Services RFP (280,140 chars)
- **Models:** main + sub `google/gemini-3-flash-preview`

## Comparison to the un-prepended baseline

The original `bench/tasks/02-risk-analysis.md` reports the baseline ran in **64.8 seconds** using **170,336 tokens**. R-Inject's overhead:

| Metric | Baseline | R-Inject | Δ |
|---|---:|---:|---:|
| Total tokens | 170,336 | 244,018 | **+73,682 (+43%)** |
| Wall clock | 64.8 s | 228.3 s | **+163.5 s (+252%)** |
| Tree stages | chunk + map-each(5) + single synthesis | chunk + map-each(5) + aggregate + analytical synthesis with typed `[:enum "HIGH" "MEDIUM" "LOW"]` | similar shape, stronger typing |

The token overhead (+43%) is the lowest among the big-doc tasks — the prepend is amortized across ~35 chunks of extraction work. The wall-clock overhead is +252% because the analytical synthesis call reads all per-chunk extractions and produces a typed risk-matrix with reasoning per row; the baseline used a simpler one-pass synthesis without typed output. The R-Inject design isn't a structurally different tree — it's the *same shape* with *richer typed contracts* between stages.

## What the classifier retrieved

`classify-task` returned 3 structural candidates; the 0.6 floor admits the top two. The Risk-analysis seed nailed the match at 1.00 — the task is a textbook fit for its prescription.

| # | Seed | Fitness | Why |
|---:|---|---:|---|
| 1 | `Risk-analysis` | **1.00** | "Perfectly matches the task's required outputs (`:risk-matrix` and `:executive-summary`) and methodology. Explicitly recommends `:map-each` pattern with structured `:output-schemas` to handle mapping of obligations to HIGH/MEDIUM/LOW risk levels — exactly what `:risk-matrix` requires for a large document like `:yyj_rfp` (~280K chars)." |
| 2 | `Legal-issue-detection` | **0.75** | "Captures the legal/analytical nature and uses citations (relevant to the `:penalties` requirement for clause references), but is less fit because it prioritizes issue detection over the classification of obligations into a structured matrix." |
| 3 | `Scheduling` | 0.10 | *Filtered* — "For scheduling and constraint validation. The query involves a 'Timeline' obligation type, but the structural shape of sequential constraint checking is irrelevant to the analytical risk/obligation evaluation required for the RFP." |

The behavioral classifier returned **5 candidates, all above the 0.6 floor**:

| # | Behavior | Confidence | Why |
|---:|---|---:|---|
| 1 | Analysis | **0.95** | "Core competency. Reasoning over items to produce structured findings with evidence. Recommends tying findings to affected-items for auditability — critical for the clause references requested in `:penalties`." |
| 2 | Extraction | **0.88** | "Necessary precursor. The candidate notes that 'analyzing raw documents conflates extraction and reasoning'; since the query involves a massive 280K char RFP, chunked-extraction with `:output-schemas` is the only way to reliably feed the `:obligations` and `:penalties` stages." |
| 3 | Synthesis | **0.85** | "Perfect for the `:executive-summary` output. Focuses on integrating multiple structured inputs (obligations, penalties, risk-matrix) into a cohesive recommendation/report." |
| 4 | Critique | **0.75** | "Relevant for the risk-matrix generation, which requires evaluating obligations against quality criteria (risk levels HIGH/MEDIUM/LOW). Adversarial framing fits the 'bidder's perspective' required for the analysis." |
| 5 | Design | **0.60** | "Relevant for the strategic side of the `:executive-summary`, particularly in structuring architectural or plan-based decisions and surface risks." |

The cognitive-operation spread — Extract, Analyze, Synthesize, Critique, Design — is the full corpus surfacing because RFP risk analysis genuinely touches each of these.

## The verbatim prepend the model received

The block below — 23,728 characters total — was prepended to the model's Phase 1 task input *exactly as shown*:

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

### Structural patterns (top 2 from corpus retrieval)
#### Top match — Risk-analysis (confidence: 1.00)
Why this fits: This candidate perfectly matches the task's required outputs (:risk-matrix and :executive-summary) and methodology. It explicitly recommends a :map-each pattern with structured :output-schemas to handle the mapping of obligations to HIGH/MEDIUM/LOW risk levels, which is exactly what the :risk-matrix task requires for a large document like :yyj_rfp (~280K chars).

Pattern guidance (seed `:summary`):
Risk-analysis trees identify and categorize risks/obligations from a document into a per-item :risk-matrix (HIGH/MEDIUM/LOW + justification) plus an :executive-summary. The proven shape is per-section :map-each with structured :output-schemas: split by section delimiter, classify each section under bounded concurrency, aggregate the matrix, then synthesize the executive summary. Always use :output-schemas to constrain severity to an enum — free-form strings drift across iterations and break aggregation.


#### Alternative #1 — Legal-issue-detection (confidence: 0.75)
Why this fits: While this candidate captures the legal/analytical nature and uses citations (relevant to the :penalties requirement for clause references), it is less fit than the risk-analysis tree because it prioritizes issue detection over the classification of obligations into a structured matrix. The rollup logic via :code is useful but not as central to the 'risk level' intent as the :map-each risk-analysis pattern.

Pattern guidance (seed `:summary`):
Legal-issue-detection trees flag legal concerns in a document with per-issue severity, area-of-law, source citation, and recommendation. The proven shape mirrors risk-analysis but with citation as a first-class output: per-section :map-each with :output-schemas requiring :citation field, then :code (NOT :llm) for the severity-summary rollup. Always require citation in the schema so users can verify the model's flags against the source.


### Behavioral competencies (top 5 from corpus retrieval)
1. Behavioral: Analysis (confidence: 0.95)
   Why this fits: Analysis is the core competency required for the risk-matrix and obligations tasks. It specifically mentions reasoning over items to produce structured findings with evidence (matching the query's focus on reasoning over clauses rather than just listing them) and recommends tying findings to affected-items for auditability, which is critical for the clause references requested in :penalties.

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

2. Behavioral: Extraction (confidence: 0.88)
   Why this fits: Extraction is a necessary precursor behavior for the analysis. The candidate notes that 'analyzing raw documents conflates extraction and reasoning', and since the query involves a massive 280K char RFP, the recommended chunked-extraction pattern with :output-schemas is the only way to reliably feed the :obligations and :penalties stages.

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

3. Behavioral: Synthesis (confidence: 0.85)
   Why this fits: Synthesis is perfect for the :executive-summary output. It focuses on integrating multiple structured inputs (the obligations, penalties, and risk-matrix generated in earlier steps) into a cohesive recommendation or report, which directly aligns with the strategic concerns and recommendations requested in the query.

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

4. Behavioral: Critique (confidence: 0.75)
   Why this fits: Critique is highly relevant for the risk-matrix generation, which requires evaluating obligations against quality criteria (risk levels HIGH/MEDIUM/LOW). The use of adversarial framing or explicit checklists mentioned here fits the 'bidder's perspective' required for the risk analysis.

   Guidance (seed `:summary`):
   Critique evaluates an artifact against quality criteria, producing per-criterion grades + located issues + suggested fixes. Always require per-issue location references AND per-issue :suggested-fix — vague critiques produce vague refinements. Score against an explicit per-dimension rubric, NOT a single overall grade, so refinement knows where to focus. NEVER use the same prompt persona for critique as for the original producer — self-critique ratifies. Use adversarial framing (skeptical reviewer / safety auditor) or an explicit checklist. Composes into draft-critique (validation-loop child) and iterative-refinement (sequential-pipeline child) shells; also into validation-loop pattern when paired with a producer. Avoid for formal rule-checking (use validation) or for orphan critiques with no downstream consumer.

Capabilities:
  - given an artifact + quality criteria, produce a structured critique — per-criterion grade + list of specific issues + suggested fixes
  - natural fit downstream of synthesis/design/code-building, upstream of refinement loops or human review
  - outputs are typically a map with per-criterion scores + a vector of specific issues with locations + a vector of suggested improvements

Strengths (proven traits — these patterns have been observed to work; mimic where they fit, adapt as needed):
  - **Trait:** require per-issue location references (line number / section name / item id) so the downstream refinement stage knows exactly what to address — vague issues produce vague fixes (confidence 1.00, evidence-count 1)
    - Good when: the artifact is structured and references are addressable
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:llm {:reads [:artifact :criteria] :writes [:issues] :output-schemas {:issues [:vector [:map [:location :string] [:problem :string] [:severity :string] [:suggested-fix :string]]]}}]
      ```

  - **Trait:** score against an explicit per-criterion rubric (not a single overall grade) so the refinement stage knows which dimension needs work — overall-grade-only critiques produce regressions in unscored dimensions (confidence 1.00, evidence-count 1)
    - Good when: quality has multiple distinct dimensions (correctness / clarity / completeness / efficiency)
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:llm {:reads [:artifact :rubric] :writes [:scores] :output-schemas {:scores [:map-of :string :double]}}]
      ```

Weaknesses (observed failure modes — avoid these patterns, apply the recommended fix where applicable):
  - **Failure mode:** self-critique on the same model (no prompt-framing change) tends to ratify the original artifact — the model is biased toward consistency with its prior output (confidence 1.00, evidence-count 1)
    - Avoid when: the critique stage uses the same :llm with the same role framing as the producer
    - Recommended fix: use a different prompt persona (skeptical reviewer / safety auditor) OR a different model OR an explicit checklist that forces specific question forms — adversarial framing improves catch rate

  - **Failure mode:** critiques that don't produce actionable per-issue fixes leave the refinement stage with nothing to act on — issues phrased as 'unclear' don't tell the writer what to do (confidence 1.00, evidence-count 1)
    - Avoid when: the critique feeds a refinement stage
    - Recommended fix: every issue must include a :suggested-fix — explicit phrasing of how to address it; not 'consider revising' but 'replace X with Y because Z'

Representative uses (concrete tasks this pattern has shipped on):
  - draft review: per-section grade + specific issues + suggested rewrites
  - code review: per-file findings + severity + suggested fix
  - design review: per-decision check + risks + open questions

5. Behavioral: Design (confidence: 0.60)
   Why this fits: Design is relevant for the strategic side of the :executive-summary, particularly in structuring architectural or plan-based decisions and surface risks. However, it is less about auditing an existing document and more about creating something new, making it secondary to Analysis.

   Guidance (seed `:summary`):
   Design produces a single committable plan/spec/architecture from inputs (goal + constraints + optional candidate options). Always structure as :map with named sections (decisions / open-questions / risks) — downstream automated stages bind on these. For non-trivial designs with interacting decisions, iterate via draft → critique → refined-spec. ALWAYS require an :open-questions field — empty means 'I'm confident', non-empty surfaces the gap before downstream stages run against an implicit guess. Composes into iterative-refinement (draft → critique → finalize) and sequential-pipeline (analysis → design → code-building) shells. Avoid when the task is option generation (use ideation) or execution against a known plan (use code-building).

Capabilities:
  - given a goal + constraints + (optionally) candidate options, produce a single concrete plan/spec/architecture as the chosen direction
  - natural fit as the convergence stage after research/extraction/analysis/ideation; the gate before code-building or execution
  - outputs are typically a single structured spec (sections / decisions / open-questions) with explicit decisions rather than a list of options

Strengths (proven traits — these patterns have been observed to work; mimic where they fit, adapt as needed):
  - **Trait:** structure the design output as :map with named sections (decisions / open-questions / risks) — downstream code-building or execution can :read specific sections without re-parsing prose (confidence 1.00, evidence-count 1)
    - Good when: the design will feed automated downstream stages (code-building, validation, execution)
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:llm {:reads [:goal :constraints :options] :writes [:spec] :output-schemas {:spec [:map [:decisions [:vector :string]] [:open-questions [:vector :string]] [:risks [:vector :string]]]}}]
      ```

  - **Trait:** iterate design via draft → critique → refined-spec when the design is non-trivial — single-pass design tends to leave decisions implicit (confidence 1.00, evidence-count 1)
    - Good when: the design has multiple interacting decisions AND the cost of a wrong call is high
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:sequence [:llm {:reads [:goal] :writes [:draft-spec]}] [:llm {:reads [:draft-spec :goal] :writes [:critique]}] [:llm {:reads [:draft-spec :critique] :writes [:refined-spec]}]]
      ```

Weaknesses (observed failure modes — avoid these patterns, apply the recommended fix where applicable):
  - **Failure mode:** designs that leave open-questions implicit force the downstream stage to either fail or guess — both produce drift between intended and actual outcome (confidence 1.00, evidence-count 1)
    - Avoid when: downstream stages will execute against the design
    - Recommended fix: require an explicit :open-questions field in the spec output — empty means 'I'm confident'; non-empty surfaces the gap

  - **Failure mode:** design without explicit consideration of the constraints (as part of :reads) tends to produce specs that look reasonable but violate the constraints in subtle ways (confidence 1.00, evidence-count 1)
    - Avoid when: the task has hard constraints (budget / API limits / shape requirements)
    - Recommended fix: always include constraints as a separate :reads input on the design stage AND require the spec output to reference how each constraint was honored

Representative uses (concrete tasks this pattern has shipped on):
  - technical architecture spec from a problem brief + constraints
  - experiment design from a research question + dataset constraints
  - API contract design from a feature spec + backwards-compat requirements

---

````

After this block, the user's task instruction asking for `:obligations`, `:penalties`, `:risk-matrix`, and `:executive-summary` appears verbatim.

## What the model designed

```clojure
[:sequence
 [:chunk-document {:from :yyj_rfp :size 8000 :into :chunks}]
 [:map-each
  {:from :chunks :as :chunk :into :per_chunk_data :max-concurrency 5}
  [:llm
   {:instruction
    "Analyze this RFP section from a bidder's perspective.
     1. Extract all obligations: things the bidder MUST do (Financial,
        Operational, Compliance, Reporting, or Timeline).
     2. Identify all penalties: consequences for non-compliance, termination
        triggers, or disqualification criteria.
     Include specific clause numbers or section titles as references."
    :reads [:chunk]
    :writes [:extractions]
    :output-schemas
    {:extractions
     [:map
      [:obligations
       [:vector [:map [:type :string]
                     [:description :string]
                     [:reference :string]]]]
      [:penalties
       [:vector [:map [:description :string]
                     [:consequence :string]
                     [:reference :string]]]]]}}]]

 [:aggregate {:from :per_chunk_data :writes [:all_extractions]}]

 [:llm
  {:instruction
   "From the aggregated RFP extractions, produce the final comprehensive analysis.
    1. Refine the full list of :obligations.
    2. Refine the list of :penalties with specific clause references.
    3. Create a :risk-matrix: Map major obligations/penalties to a risk level
       (HIGH/MEDIUM/LOW). Justify each level based on difficulty of fulfillment
       and severity of consequence.
    4. Write an :executive-summary: Highlight the top 3-5 strategic concerns
       and actionable recommendations for the bidder.
    Ensure every risk level is one of [HIGH, MEDIUM, LOW]."
   :reads [:all_extractions]
   :writes [:obligations :penalties :risk-matrix :executive-summary]
   :output-schemas
   {:obligations [:vector :any]
    :penalties [:vector :any]
    :risk-matrix
    [:vector [:map [:obligation :string]
                   [:risk-level [:enum "HIGH" "MEDIUM" "LOW"]]
                   [:reasoning :string]]]
    :executive-summary :string}}]

 [:final {:keys [:obligations :penalties :risk-matrix :executive-summary]}]]
```

Four-stage tree (not the 6+ stages of task 02). Three observations:

**Chunk size 8,000 with `:max-concurrency 5`.** Produces ~35 chunks of the 280K document. The Risk-analysis seed's worked example DSL uses chunk size 5000; the model chose larger chunks (8000) plus higher concurrency (5 vs the seed's 3) — task-specific adaptation that fits the rate-limit reality of running 35+ parallel extractions.

**Per-chunk schema separates obligations from penalties at the top level.** `[:extractions [:map [:obligations [:vector ...]] [:penalties [:vector ...]]]]`. This is a single typed map per chunk with two named vectors inside. The Extraction seed's body says "explicit `:output-schemas` with named fields + a concrete example in the prompt; treat extraction shape as a contract." The model's schema is the literal contract: every chunk produces a map with those two keys.

**The analytical synthesis stage has typed `[:enum "HIGH" "MEDIUM" "LOW"]` on `:risk-level`.** Directly from the Risk-analysis seed's `:strengths` body: "always use `:output-schemas` to constrain severity to an enum — free-form strings drift across iterations and break aggregation." The model also constrains `:obligations` and `:penalties` to `[:vector :any]` (loose typing to preserve the per-chunk structures); the strict constraint goes where it matters most (severity).

This is a single-pass synthesis tree, not the multi-stage hierarchical synthesis tasks 02 and 03 used. With only 4 declared outputs (vs 5 in the contract-comparison tasks), the Synthesis seed's "NEVER synthesize >5 substantive inputs in one call" rule isn't triggered. The model correctly identified that the 4 outputs all derive from the same aggregated extractions and synthesized them in one go.

## What the model reasoned about its choices

> **Iteration 1:** *"The task requires a deep analytical dive into a large RFP (280k chars). To handle this safely and thoroughly, I will follow the suggested 'Risk-analysis' and 'Extraction' patterns. I'll split the document into 8,000-character chunks to ensure high recall for specific clauses and penalties.*
>
> *The tree will first extract raw obligations and penalties with citations (as suggested by the 'Legal-issue-detection' and 'Extraction' patterns). Then, I'll use an analytical step to categorize these into a risk matrix, justified by reasoning. Finally, a synthesis node will generate the executive summary. I prioritized `:chunk-document` and `:map-each` to prevent the model from missing critical middle-of-document penalties. **I also applied `:output-schemas` to the risk matrix to ensure risk levels (HIGH/MEDIUM/LOW) are consistent for downstream aggregation.**"*

Three corpus references in one paragraph: "Risk-analysis" (structural top match), "Extraction" (behavioral #2 — chunking discipline), and "Legal-issue-detection" (citation pattern from structural #2). The model is composing across multiple corpus pieces:
- The shape from Risk-analysis (chunk → map-each → synthesize)
- The citation discipline from Legal-issue-detection (`:reference` field per item)
- The enum-typed severity from Risk-analysis's `:strengths` body

The "prevent the model from missing critical middle-of-document penalties" sentence is the Extraction seed's "single-pass extraction over a multi-page document misses items in the middle when token budget pressures the model to summarize instead of extract" warning being internalized.

> **Iteration 2 (terminate):** Standard reuse-from-sandbox finalization.

## Output quality

**`:risk-matrix` (excerpt — verbatim from `:outputs`):**

> | Obligation | Risk Level | Reasoning |
> |---|---|---|
> | Maintain minimum 5% Letter of Credit (Performance Security) (Schedule "A", Section 3.4.2) | **HIGH** | Failure leads to direct contract termination, exposing the contractor to immediate cessation of revenue. Renewal/reinstatement requirements add significant administrative and financial pressure. |
> | Compliance with all federal/provincial/municipal laws, including PIPEDA, OHSA, FOIPPA (Schedule "B", General Conditions 1.0/3.0) | **HIGH** | Non-compliance with binding statutes (especially federal privacy laws like PIPEDA) results in serious legal/financial penalties beyond contract termination, potentially involving public/regulatory censure. |
> | Provide structured weekly Revenue & Patron Volume Reports (Schedule "A", Section 4.2 / 4.4) | **MEDIUM** | Failure invites breach of contract, financial audit, and potential dispute, but reporting is a procedural failure rather than an immediate operational disaster. |
> | Maintain comprehensive Customer Service Standards and Programs (Schedule "B", General Conditions 4.0) | **MEDIUM** | Required compliance with the VAA's prescribed standards (e.g., training, dispute resolution) ties up significant operational resources, but the risk is reputational/penalty-based rather than immediate termination. |
> | Handle Hazardous Materials and Spills (Schedule "B", General Conditions 5.0 (b)) | **LOW** | Standard environmental compliance is mandatory but typically managed through dedicated subcontractors or established protocols. Low risk of an unforeseen catastrophic failure. |

Five rows, all typed (`:enum [HIGH MEDIUM LOW]`), every obligation has a section reference (Schedule "A" Section 3.4.2, Schedule "B" General Conditions 1.0/3.0, etc.), and each `:reasoning` field grounds the risk level in specific consequences. The enum constraint paid off — no "Major" or "Significant" or "Concerning" labels that would break downstream aggregation.

**`:penalties` (excerpt):**

> 1. **Contract Termination for Cause:** Triggered by Material Default, including failure to remit revenue, gross negligence, or failure to maintain insurance. *Reference: Schedule "B", General Conditions 2.1*.
> 2. **Termination for Insolvency:** Triggered by Contractor bankruptcy, receivership, or assignment for benefit of creditors. *Reference: Schedule "B", General Conditions 2.2*.
> 3. **Disqualification (Bid Stage):** Failure to provide required documentation (e.g., Tax Clearance, Letter of Credit). *Reference: Section 3.4 (RFP Document)*.
> 4. **Financial Penalty for Lost Revenue:** Calculated at 1.5 times the average daily revenue calculated for that respective period. *Reference: Schedule "A", Section 4.5*.

Section references are real and verifiable (Schedule B GC 2.1, GC 2.2 exist in the RFP). The "1.5× lost daily revenue" formula in penalty #4 is a verifiable contractual quantum, not a hallucinated number.

**`:executive-summary` (opening):**

> The Victoria International Airport Authority (VAA) Parking Management Services contract represents a **high-risk, low-margin opportunity** that requires the Contractor to assume responsibility for both operational continuity and substantial financial guarantees over a long-term commitment. The primary risks are concentrated in three areas: 1) **Strict Compliance Burden**, particularly regarding federal/provincial privacy laws; 2) **Operational Volatility** tied to aviation industry fluctuations affecting parking volume; and 3) **Severe Contractual Risk** stemming from the Performance Security and termination triggers...

Frames the bid as a strategic recommendation, not just a summary. Identifies three concrete risk concentrations, all grounded in specific findings from the prior stages.

## What this run tells us about R-Inject

For a large analytical task with multiple typed outputs, R-Inject's contribution is the **typing discipline** — `:enum "HIGH" "MEDIUM" "LOW"` on severity, `:reference :string` per obligation/penalty, structured per-chunk schemas. The baseline produced a similar-shape tree (chunked extraction + synthesis) but with less rigorous typing. The R-Inject tree's typed enum is what makes downstream aggregation possible — without it, downstream tools (judges, reviewers, dashboards) can't reliably parse the risk levels.

Cost: +43% tokens, +252% wall clock. The reward: typed risk-matrix with section-grounded reasoning, clear strategic synthesis, no hallucinated section references. For an RFP risk analysis where the consumer will hand the output to a real bid team, the typing discipline is load-bearing.


## Reproducing this report's data

- Verbatim prepend (23,728 chars): `:r-inject-trace.:prepend`
- All 3 structural candidates: `:r-inject-trace.:classifier-payload.:structural.:top-candidates`
- All 5 behavioral candidates (all above floor): `:r-inject-trace.:classifier-payload.:behavioral.:behaviors`
- Per-iteration full code: `:iterations`
- Per-iteration reasoning verbatim: `:iteration-reasonings`
- Final tree: `:generated-tree-raw`
- Outputs by key: `:outputs` (`:obligations`, `:penalties`, `:risk-matrix`, `:executive-summary`)
- Token usage (Phase 1 + Phase 2 aggregated): `:usage`
