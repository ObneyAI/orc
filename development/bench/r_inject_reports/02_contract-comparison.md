# 02 — Contract Comparison

Two real Ontario microFIT contracts — version 2.0 (56K chars) and version 3.1.1 (49K chars) — and a task that demands cite-able section references for every claimed difference. This is the case where the prepend's *Contract-comparison* structural seed says explicitly: "parallel per-contract extraction THEN sequential synthesis; never feed both raw contracts into a single `:llm` because attention-budget exhaustion hallucinates provisions." The question for this report: does the model adopt that prescription, and does the resulting tree maintain section-level grounding through all five outputs?

**Run summary**

- **Status:** `:success`
- **Wall clock:** 152.5 s
- **Phase 1 + Phase 2 tokens:** 109,693 (77,498 prompt + 32,195 completion)
- **Iterations:** 2 (design → final)
- **Saved EDN:** [`generalization-results/contract-comparison_2026-06-02_121327.edn`](../generalization-results/contract-comparison_2026-06-02_121327.edn)
- **Source documents:** [`contract_v2.txt`](../documents/contract_v2.txt) (56,047 chars) and [`contract_v3.txt`](../documents/contract_v3.txt) (49,021 chars) — Ontario microFIT v2.0 and v3.1.1
- **Models:** main + sub `google/gemini-3-flash-preview`

## Comparison to the un-prepended baseline

The original `bench/tasks/03-contract-comparison.md` reports the baseline ran in **35.6 seconds** using **64,336 tokens**. R-Inject's overhead:

| Metric | Baseline | R-Inject | Δ |
|---|---:|---:|---:|
| Total tokens | 64,336 | 109,693 | **+45,357 (+71%)** |
| Wall clock | 35.6 s | 152.5 s | **+116.9 s (+329%)** |
| Tree stages | per-document survey + comparison + synthesis | 7-stage: parallel chunked extraction → 4× sequential synthesis stages → final | + 4 stages |

The token overhead (+71%) is the lowest among the small/medium-document tasks; the wall-clock overhead (+329%) is larger because the R-Inject tree splits synthesis into four distinct `:llm` stages instead of one. This is the prepend's Synthesis seed working: "NEVER synthesize >5 substantive inputs in one call — do hierarchical clustering instead." The model split a 5-output synthesis into 4 sequential `:llm` stages, each reading a tightly-scoped subset of upstream writes. Result: every synthesis call has a small prompt, large structured outputs, and produces section-cited findings.

## What the classifier retrieved

`classify-task` returned 5 structural candidates; the 0.6 floor admits the top three. The reranker correctly recognized the third (Risk-analysis at 0.60) as adjacent-but-incomplete — useful for the `:impact-analysis` output but missing the diff logic.

| # | Seed | Fitness | Why |
|---:|---|---:|---|
| 1 | `Contract-comparison` | **1.00** | "Perfect architectural match. Explicitly recommends a 'diff-two-versions' pattern that uses parallel structured extraction followed by synthesis, specifically warning against feeding raw text to avoid hallucinations." |
| 2 | `Comparative-summary` | **0.90** | "Strong alternative but slightly more general. Addresses multi-source research-then-synthesize and emphasizes identical output schemas for alignment, which is critical for `:section-diffs`. Explicitly mentions its relationship to the contract-comparison class." |
| 3 | `Risk-analysis` | **0.60** | "Relevant for `:impact-analysis` and `:major-changes` aspects, as it handles risk/obligation identification and classification (HIGH/MEDIUM/LOW). However, lacks the specific dual-document 'comparison' logic." |
| 4 | `Legal-issue-detection` | 0.50 | *Filtered* — "Fits the quality requirement for source citation, but is designed for single-document analysis rather than a comparative diff." |
| 5 | `ResearchThenSynthesize` | 0.40 | *Filtered* — "Useful pattern for `:executive-summary` and global reasoning, but the structural pattern is less optimized for pinpointing 'what changed' between two specific files." |

The behavioral classifier returned 4 candidates, all above 0.6:

| # | Behavior | Confidence | Why |
|---:|---|---:|---|
| 1 | Analysis | **0.95** | "Core competency. Reasoning over two documents to identify patterns (differences) and providing per-finding evidence (specific clause references). The recommended pattern of tying findings to `:affected-items` directly supports the quality requirement." |
| 2 | Synthesis | **0.85** | "Highly relevant for producing `:impact-analysis` and `:executive-summary`. Requirement to maintain source-id attribution and generate hierarchical artifacts (survey, diffs, summary) aligns with synthesis's pattern of integrating structured findings into a cohesive narrative." |
| 3 | Extraction | **0.75** | "Necessary precursor. Because the task requires identifying exact section/clause references in documents of ~50K characters, the `:map-each` and chunking patterns are critical to preventing recall loss over legal text before comparisons are made." |
| 4 | Classification | **0.60** | "Specifically relevant to `:major-changes`, which requires sorting differences into a closed set of categories (MAJOR vs MINOR vs COSMETIC). The recommended `:enum` constraint in `:output-schema` ensures consistent labeling across sections." |

The Classification behavior at #4 directly drove the model's `[:enum "MAJOR" "MINOR" "COSMETIC"]` on the `:major-changes` schema — visible in the tree below.

## The verbatim prepend the model received

The block below — 20,334 characters total — was prepended to the model's Phase 1 task input *exactly as shown*, before the original task instruction:

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

### Structural patterns (top 3 from corpus retrieval)
#### Top match — Contract-comparison (confidence: 1.00)
Why this fits: This candidate is a perfect architectural match for the task. It explicitly recommends a 'diff-two-versions' pattern (contract-comparison) that uses parallel structured extraction (matching the multi-output requirement for document-survey and section-diffs) followed by synthesis (matching major-changes and impact-analysis), specifically warning against feeding raw text to avoid hallucinations.

Pattern guidance (seed `:summary`):
Contract-comparison trees compare two contracts (or two versions) for key differences, similarities, and a recommendation. The proven shape: :parallel per-contract structured-extraction (:output-schemas guaranteeing typed provisions), then sequential synthesis :llm consuming the structured vectors (NOT raw text). Always extract first, then diff — feeding both raw contracts to a single synthesis :llm hits attention limits and induces hallucinated provisions.


#### Alternative #1 — Comparative summary (confidence: 0.90)
Why this fits: This is a strong alternative but slightly more general than the specific contract-comparison tree. It addresses the multi-source research-then-synthesize requirement and emphasizes identical output schemas for alignment, which is critical for the ':section-diffs' requirement. It explicitly mentions its relationship to the contract-comparison class.

Pattern guidance (seed `:summary`):
Comparative summary is the multi-source research-then-synthesize variant: parallel per-source extraction → :code aggregator structures extractions per source → final :llm synthesizes a structured comparison (common / differences / recommendation). Always use :parallel (not :sequence) for independent per-source stages — wall-time becomes max(per-source). CRITICAL: use IDENTICAL :output-schemas across per-source stages so comparable fields stay aligned. Limit to ~5 sources per synthesis; for more, do hierarchical comparison. Use contract-comparison (parallel-independent task-class) when the structure is exactly diff-two-versions; comparative-summary is for general N-source comparison.


#### Alternative #2 — Risk-analysis (confidence: 0.60)
Why this fits: Relevant for the ':impact-analysis' and ':major-changes' aspects of the task, as it handles risk/obligation identification and classification (HIGH/MEDIUM/LOW). However, it lacks the specific dual-document 'comparison' logic required to generate the ':section-diffs' between contract versions.

Pattern guidance (seed `:summary`):
Risk-analysis trees identify and categorize risks/obligations from a document into a per-item :risk-matrix (HIGH/MEDIUM/LOW + justification) plus an :executive-summary. The proven shape is per-section :map-each with structured :output-schemas: split by section delimiter, classify each section under bounded concurrency, aggregate the matrix, then synthesize the executive summary. Always use :output-schemas to constrain severity to an enum — free-form strings drift across iterations and break aggregation.


### Behavioral competencies (top 4 from corpus retrieval)
1. Behavioral: Analysis (confidence: 0.95)
   Why this fits: Analysis is the core competency required here. The task involves reasoning over two documents to identify patterns (differences) and providing per-finding evidence (specific clause references). The recommended pattern of tying findings to :affected-items directly supports the quality requirement to cite specific section numbers for every claimed difference.

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

2. Behavioral: Synthesis (confidence: 0.85)
   Why this fits: Synthesis is highly relevant for producing the final report outputs like the :impact-analysis and :executive-summary. The requirement to maintain source-id attribution and generate hierarchical artifacts (survey, diffs, summary) aligns with the synthesis behavioral pattern of integrating structured findings into a cohesive narrative while preserving source provenance.

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

3. Behavioral: Extraction (confidence: 0.75)
   Why this fits: Extraction is a necessary precursor to the analysis phase. Because the task requires identifying exact section/clause references in documents of ~50K characters, the :map-each and chunking patterns mentioned in extraction are critical to ensuring full coverage and preventing recall loss over legal text before the comparisons are made.

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

4. Behavioral suggestion (confidence: 0.60)
   Why this fits: Classification is specifically relevant to the :major-changes output, which requires sorting differences into a closed set of categories (MAJOR vs MINOR vs COSMETIC). Using the recommended :enum constraint in an :output-schema would ensure consistent labeling across the different contract sections.

   Guidance (seed `:summary`):
   Classification assigns each item from a set to one or more predefined categories. ALWAYS include the closed-set vocabulary in :reads AND constrain output via :output-schemas with [:enum ...] of allowed categories — open-ended output drifts across runs and breaks downstream aggregation. Prefer :map-each per-item (with bounded :max-concurrency) over batch classification for >20 items — preserves per-item attention. Composes into parallel-classify-aggregate (map-reduce variant), map-reduce shells, and parallel-independent shells (multi-axis classification with per-axis classifier). Avoid for open-ended labels (clustering), for rule-based assignments (use :code), or for category set generation (use ideation).

Capabilities:
  - given a set of items + a category vocabulary, emit per-item category assignments (optionally multi-label with confidence)
  - natural fit when downstream stages branch or aggregate by category
  - outputs are typically a vector of {:item-id :category :confidence} or a map from item-id to category

Strengths (proven traits — these patterns have been observed to work; mimic where they fit, adapt as needed):
  - **Trait:** fan out per-item classification via :map-each with bounded :max-concurrency — per-item LLM calls give richer per-item reasoning than a single batch classifier prompt (confidence 1.00, evidence-count 1)
    - Good when: items are independent AND per-item context matters for accuracy
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:map-each {:items :items :as :item :max-concurrency 3 :writes [:classifications]} [:llm {:reads [:item :vocabulary] :writes [:classification] :output-schemas {:classification [:map [:category :string] [:confidence :double]]}}]]
      ```

  - **Trait:** include the category vocabulary in :reads of each per-item classifier — anchors the model to the closed-set output and prevents drift to free-form labels (confidence 1.00, evidence-count 1)
    - Good when: the category set is closed and stable
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:llm {:reads [:item :vocabulary] :writes [:classification] :output-schemas {:classification [:enum :a :b :c]}}]
      ```

Weaknesses (observed failure modes — avoid these patterns, apply the recommended fix where applicable):
  - **Failure mode:** classifying without the vocabulary in the prompt produces free-form labels that drift across runs — downstream aggregation over categories breaks (confidence 1.00, evidence-count 1)
    - Avoid when: downstream stages aggregate / branch on category equality
    - Recommended fix: always include the closed-set vocabulary in :reads AND constrain output via :output-schemas with [:enum ...] of the allowed categories

  - **Failure mode:** single batch classification of N>20 items in one :llm call loses per-item attention — accuracy degrades at the tail of the batch (confidence 1.00, evidence-count 1)
    - Avoid when: item count >20 AND per-item accuracy matters
    - Recommended fix: :map-each per-item with bounded :max-concurrency — wall-time stays low and per-item attention is preserved

Representative uses (concrete tasks this pattern has shipped on):
  - per-document topic classification: each document → topic label + confidence
  - per-issue triage: each support ticket → category from a fixed taxonomy
  - per-clause risk-class assignment: each contract clause → risk level + reasoning

---

````

After this block, the user's task instruction asking for the contract diff, major-changes classification, impact analysis, and executive summary appears verbatim.

## What the model designed

```clojure
[:sequence
 [:parallel
  [:sequence
   [:chunk-document {:from :contract_v2 :size 12000 :into :v2_chunks}]
   [:map-each {:from :v2_chunks :as :chunk :into :v2_sections :max-concurrency 3}
    [:llm
     {:instruction "List all section headers and summarize the primary legal
                     obligations/provisions in this chunk of the microFIT
                     Contract V2.0. Maintain exact section numbering."
      :reads [:chunk] :writes [:section_list]
      :output-schemas
      {:section_list [:vector [:map [:section_id :string]
                                    [:content_summary :string]
                                    [:clause_text_representative :string]]]}}]]
   [:aggregate {:from :v2_sections :writes [:v2_map]}]]
  [:sequence
   [:chunk-document {:from :contract_v3 :size 12000 :into :v3_chunks}]
   [:map-each {:from :v3_chunks :as :chunk :into :v3_sections :max-concurrency 3}
    [:llm
     {:instruction "List all section headers and summarize the primary legal
                     obligations/provisions in this chunk of the microFIT
                     Contract V3.1.1. Maintain exact section numbering."
      :reads [:chunk] :writes [:section_list]
      :output-schemas
      {:section_list [:vector [:map [:section_id :string]
                                    [:content_summary :string]
                                    [:clause_text_representative :string]]]}}]]
   [:aggregate {:from :v3_sections :writes [:v3_map]}]]]

 [:llm {:instruction "Using the structured section maps from V2.0 and V3.1.1, create
                       a document survey comparing the structure."
        :reads [:v2_map :v3_map] :writes [:document-survey]}]

 [:llm {:instruction "Compare the v2_map and v3_map section by section. Identify exact
                       additions, removals, and modifications. If a section is
                       identical, state so. For every change, provide the section
                       number and a description."
        :reads [:v2_map :v3_map] :writes [:section-diffs]}]

 [:llm {:instruction "Classify the detected differences from section-diffs into MAJOR
                       (impacts legal rights or payments), MINOR (clarification), or
                       COSMETIC (formatting/grammar). Provide reasoning for each."
        :reads [:section-diffs] :writes [:major-changes]
        :output-schemas
        {:major-changes [:vector [:map [:section_id :string]
                                       [:change_type [:enum "MAJOR" "MINOR" "COSMETIC"]]
                                       [:description :string]
                                       [:reasoning :string]]]}}]

 [:llm {:instruction "Analyze the impact of the MAJOR and MINOR changes identified.
                       Explain who is affected (Supplier vs OPA) and how the risk
                       profile has shifted."
        :reads [:major-changes] :writes [:impact-analysis]}]

 [:llm {:instruction "Synthesize all previous findings into a high-level executive
                       summary detailing how the microFIT contract evolved from V2.0
                       to V3.1.1."
        :reads [:document-survey :section-diffs :major-changes :impact-analysis]
        :writes [:executive-summary]}]

 [:final {:keys [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]]
```

Three things to notice in the design:

**The parallel-extraction-then-sequential-synthesis shape is the corpus's exact recommendation.** The `:parallel` block contains two symmetric `:sequence` branches — one per contract — each with `[:chunk-document → :map-each + per-chunk :llm + :aggregate]`. The output schemas are identical across branches (`:section_id`, `:content_summary`, `:clause_text_representative`) per the Comparative-summary seed's "CRITICAL: use IDENTICAL :output-schemas across per-source stages so comparable fields stay aligned" rule. The downstream synthesis stages read the aggregated maps, never the raw contracts.

**Five sequential synthesis stages instead of one big one.** Document-survey → section-diffs → major-changes → impact-analysis → executive-summary. Each stage reads only what it needs (e.g., `:major-changes` reads only `:section-diffs`; the final `:executive-summary` synthesis reads all prior synthesized writes). This matches the Synthesis seed's prescription to keep each synthesis call under ~5 substantive inputs.

**Typed enum on `:major-changes`.** `[:enum "MAJOR" "MINOR" "COSMETIC"]` — directly from the Classification behavior's prescription. Without it, the labels would drift across sections (e.g., "Major", "major", "Significant") and break downstream aggregation. The schema enforces the closed vocabulary at output-parse time.

## What the model reasoned about its choices

> **Iteration 1:** *"The task requires a deep comparison between two legal documents (V2 and V3 of a microFIT contract) which are roughly 50-55k characters each. To ensure high recall and precision in identifying section-level changes without hallucinating, I've chosen the 'Contract-comparison' tree shape suggested by the corpus. This architecture uses parallel structured extraction (per version) to map out the sections and contents, followed by a synthesis phase. I chose to use :chunk-document for both contracts to respect token limits while ensuring full coverage. I rejected a single-pass comparison of the raw texts because the combined size exceeds the effective attention window for detailed clause-matching. The :output-schemas in the extraction phase will ensure standardized section names and content snippets, which makes the downstream :section-diffs and :impact-analysis more robust. **I specifically incorporated the suggestion to classify changes with an enum (MAJOR/MINOR/COSMETIC) to meet the requirement for :major-changes.**"*

Two corpus references in one paragraph: the "Contract-comparison" structural pattern by name, and the enum suggestion from the Classification behavior. The "I rejected a single-pass comparison" sentence is the prepend's "feeding both raw contracts to a single synthesis :llm hits attention limits and induces hallucinated provisions" warning being internalized as the model's own design rationale.

> **Iteration 2 (terminate):** *"The previous tree execution successfully populated all required output variables... the next step is to terminate the iteration by finalizing these results."*

Standard clean termination.

## Output quality

**`:major-changes` (verbatim from `:outputs`):** the model identified specific section-level changes with the typed enum:

> - **Section 6.5 (Disclosure of Information)** — `MAJOR` — v3 explicitly authorizes the OPA to disclose Supplier/Facility information to public officials, government ministries, and emergency services for public health, safety, and emergency response purposes. v2.0 was more restricted in disclosure scope.
> - **Section 5.1.2 / 8.1 / 9 (Prescribed Form)** — `MINOR` — v3 introduces a "Prescribed Form" requirement (defined in Appendix A) for notices, amendments, and termination notices, formalizing the format requirements.
> - **Appendix C Sections 1-3 (Domestic Content Requirements)** — `MAJOR` — v3 removes the detailed Domestic Content Calculation Tables and percentage requirements that were present in v2.0, suggesting a fundamental shift in the eligibility criteria for solar PV facilities.

The MAJOR/MINOR/COSMETIC labels are typed (enum-enforced); section IDs are real; the changes are verifiable against the source contracts.

**`:executive-summary`:**

> The transition from v2.0 to v3.1.1 of the microFIT contract represents a significant **administrative tightening and procedural standardization** by the OPA, while simultaneously **scaling back industrial policy goals**...
>
> Most notably, v3.1.1 **eliminates the explicit Domestic Content Calculation Tables and percentage thresholds for solar PV** found in v2.0 Appendix C, indicating a major shift away from mandatory local-sourcing requirements that were a hallmark of the early FIT program...

Frames the v2→v3 evolution as a specific policy direction ("scaling back industrial policy goals", "administrative tightening"), grounded in concrete changes. The Domestic Content removal is the headline change that all baseline and R-Inject runs identify; the R-Inject version frames it within a coherent policy narrative.

## What this run tells us about R-Inject

This is the cleanest demonstration in the suite of the prepend driving topology. The model adopted:
- The exact parallel-extraction shape from the Contract-comparison seed (visible in `:parallel` block)
- The identical-output-schemas discipline from the Comparative-summary seed (visible in the per-branch schema)
- The synthesis-split-by-output discipline from the Synthesis seed (visible in 5 sequential `:llm` stages)
- The enum-typed Classification from the Classification behavior (visible on `:major-changes`)

The model's iteration-1 reasoning names two of those four directly ("Contract-comparison tree shape suggested by the corpus", "enum (MAJOR/MINOR/COSMETIC) to meet the requirement"). The other two adoptions are visible in the tree structure but not name-checked in the reasoning — they showed up as a structural side-effect of choosing the top-candidate.

Net cost: +71% tokens, +329% wall clock vs the baseline. The reward: section-cited findings in every output, typed change-type classification, no hallucinated section numbers, and an executive summary that integrates the structural changes into a coherent narrative. For a contract-comparison task where the consumer needs to verify each claim against the source, that's a meaningful trade.

## Reproducing this report's data

- Verbatim prepend (20,334 chars): `:r-inject-trace.:prepend`
- All 5 structural candidates with fitness scores + reasoning: `:r-inject-trace.:classifier-payload.:structural.:top-candidates`
- All 4 behavioral candidates with confidence + reasoning: `:r-inject-trace.:classifier-payload.:behavioral.:behaviors`
- Per-iteration full code: `:iterations`
- Per-iteration reasoning verbatim: `:iteration-reasonings`
- Final tree: `:generated-tree-raw`
- Outputs by key: `:outputs` (`:document-survey`, `:section-diffs`, `:major-changes`, `:impact-analysis`, `:executive-summary`)
- Token usage (Phase 1 + Phase 2 aggregated): `:usage`
