# 03 — Contract Comparison with Adversarial Validation

Same two contracts as task 02, but the task adds a quality requirement: an antagonistic reviewer must be able to disprove any claimed change against the source text, and only claims that survive that scrutiny appear in the final outputs. This is the suite's cleanest test of whether the prepend's *Critique* behavioral seed makes it into the tree topology as a real structural element (a separate validator node with a distinct persona) versus disappearing into prose disclaimers inside a single producer call.

**Run summary**

- **Status:** `:success`
- **Wall clock:** 69.7 s
- **Phase 1 + Phase 2 tokens:** 129,582 (103,978 prompt + 25,604 completion)
- **Iterations:** 2 (design → final)
- **Saved EDN:** [`generalization-results/contract-comparison-validated_2026-06-02_121054.edn`](../generalization-results/contract-comparison-validated_2026-06-02_121054.edn)
- **Source documents:** same as task 02 (56K v2.0, 49K v3.1.1)
- **Models:** main + sub `google/gemini-3-flash-preview`

## Comparison to the un-prepended baseline

The original `bench/tasks/04-contract-comparison-validated.md` reports the baseline ran in **26.7 seconds** using **84,337 tokens**. R-Inject's overhead:

| Metric | Baseline | R-Inject | Δ |
|---|---:|---:|---:|
| Total tokens | 84,337 | 129,582 | **+45,245 (+54%)** |
| Wall clock | 26.7 s | 69.7 s | **+43.0 s (+161%)** |
| Adversarial-validation form | inline "ADVERSARIAL VALIDATION" instruction language in one of the synthesis calls | **dedicated `:llm` node with distinct "antagonistic reviewer" persona** reading both source contracts AND drafts | structural separation |

The token overhead is the lowest of the three big tasks; the wall-clock overhead is also bounded. The interesting jump is structural: the baseline embedded the adversarial-validation requirement into a producer `:llm`'s instruction prose; the R-Inject tree spawns a separate validator node with a distinct persona and reads-from-source contract. Different *implementation* of the same quality requirement.

## What the classifier retrieved

`classify-task` returned 5 structural candidates; the 0.6 floor admits the top three. Note the **Critique seed appearing at the structural level** (#5 at 0.40, filtered) — the reranker correctly recognizes Critique as a behavioral pattern, not a structural shape, but its appearance in the structural list signals the adversarial-validation requirement was salient at retrieval time. The behavioral surfacing of Critique is where it pays off.

| # | Seed | Fitness | Why |
|---:|---|---:|---|
| 1 | `Contract-comparison` | **1.00** | "Exact structural match. Explicitly handles 'comparing two versions' of a contract and recommends a parallel extraction-then-synthesis pattern to avoid hallucinations, aligning perfectly with the requirement for adversarial validation and exact section references." |
| 2 | `Comparative-summary` | **0.85** | "Generalized version of the contract-comparison pattern. Supports multi-source parallel extraction and structured diffing. Slightly less specific than the dedicated contract-comparison tree-class." |
| 3 | `ResearchThenSynthesize` | **0.70** | "Underlying logic for the task: extracting information across length documents and synthesizing it into complex outputs. Lacks the explicit 'parallel comparison' optimization necessary for two-version contract diffing." |
| 4 | `Legal-issue-detection` | 0.50 | *Filtered* — "Useful for `:impact-analysis` and `:major-changes` aspects, but doesn't solve the core task of identifying changes between two versions." |
| 5 | `Critique` seed | 0.40 | *Filtered* — "Relevant only for the 'Adversarial Validation' quality requirement. The draft+critique pattern could be used to implement the 'antagonistic reviewer' role, but it does not address the primary architectural need of contract comparison." |

The behavioral classifier returned **5 candidates, ALL above the 0.6 floor** — the richest behavioral retrieval in the suite. Every cognitive operation the task touches lit up:

| # | Behavior | Confidence | Why |
|---:|---|---:|---|
| 1 | Analysis | **1.00** | "Core behavioral need. Reasoning over extracted items to produce structured findings with per-finding evidence. Aligns perfectly with the requirement to identify differences between contracts and classify them while citing evidence." |
| 2 | Critique | **0.95** | "Provides the behavioral pattern for 'Adversarial Validation' required by the prompt. Adversarial framing (antagonistic reviewer) to ensure every claim is defensible. Critical for the task's instruction to exclude any claim that cannot survive scrutiny." |
| 3 | Synthesis | **0.85** | "Required to generate `:executive-summary` and `:document-survey`. Recommendation for parallelizing long artifacts and maintaining attribution across multiple structured inputs fits the multi-part output requirement." |
| 4 | Extraction | **0.80** | "Necessary first step. Recommended pattern of using `:map-each` and `:output-schemas` is essential given the document-size constraints (56K and 49K chars)." |
| 5 | Validation | **0.60** | "Behaviors needed to fulfill the 'Quality Requirement' regarding exact section citations. While the prompt asks for adversarial judgment (Critique), the more mechanical checking of 'cite a specific section number' fits the Validation pattern." |

Critique at 0.95 — the second-highest behavioral confidence in the whole suite — is the load-bearing signal. The reranker correctly identifies Critique as the pattern that should drive the validator's design: distinct persona, source-of-truth access, explicit rejection criteria.

## The verbatim prepend the model received

The block below — 28,905 characters total — was prepended to the model's Phase 1 task input *exactly as shown*. This is the longest prepend in the suite because all 5 behavioral candidates cleared the 0.6 floor, each with its full body (capabilities + strengths + weaknesses + representative-uses):

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
Why this fits: This candidate is an exact structural match for the task. It explicitly handles 'comparing two versions' of a contract and recommends a parallel extraction-then-synthesis pattern to avoid hallucinations, which aligns perfectly with the requirement for adversarial validation and exact section references.

Pattern guidance (seed `:summary`):
Contract-comparison trees compare two contracts (or two versions) for key differences, similarities, and a recommendation. The proven shape: :parallel per-contract structured-extraction (:output-schemas guaranteeing typed provisions), then sequential synthesis :llm consuming the structured vectors (NOT raw text). Always extract first, then diff — feeding both raw contracts to a single synthesis :llm hits attention limits and induces hallucinated provisions.


#### Alternative #1 — Comparative summary (confidence: 0.85)
Why this fits: This is the generalized version of the contract-comparison pattern. It supports multi-source parallel extraction and structured diffing. While it fits the multi-output requirement (survey, diffs, impact), it is slightly less specific than the dedicated contract-comparison tree-class.

Pattern guidance (seed `:summary`):
Comparative summary is the multi-source research-then-synthesize variant: parallel per-source extraction → :code aggregator structures extractions per source → final :llm synthesizes a structured comparison (common / differences / recommendation). Always use :parallel (not :sequence) for independent per-source stages — wall-time becomes max(per-source). CRITICAL: use IDENTICAL :output-schemas across per-source stages so comparable fields stay aligned. Limit to ~5 sources per synthesis; for more, do hierarchical comparison. Use contract-comparison (parallel-independent task-class) when the structure is exactly diff-two-versions; comparative-summary is for general N-source comparison.


#### Alternative #2 — ResearchThenSynthesize (confidence: 0.70)
Why this fits: This represents the underlying logic for the task: extracting information across length documents and synthesizing it into complex outputs like the ':executive-summary' and ':impact-analysis'. However, it lacks the explicit 'parallel comparison' optimization necessary for two-version contract diffing.

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

### Behavioral competencies (top 5 from corpus retrieval)
1. Behavioral: Analysis (confidence: 1.00)
   Why this fits: Analysis is the core behavioral need for this task. It involves reasoning over extracted items to produce structured findings (patterns/problems) with per-finding evidence. This aligns perfectly with the requirement to identify differences between contracts and classify them (MAJOR/MINOR) while citing evidence. The task's need for :impact-analysis and :section-diffs mirrors this candidate's recommendation to tie findings to affected items via references.

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

2. Behavioral: Critique (confidence: 0.95)
   Why this fits: Critique provides the behavioral pattern for 'Adversarial Validation' required by the prompt. It focuses on evaluating an artifact against quality criteria (the validation requirements) using an adversarial framing (antagonistic reviewer) to ensure every claim is defensible. This is critical for the task's instruction to exclude any claim that cannot survive scrutiny.

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

3. Behavioral: Synthesis (confidence: 0.85)
   Why this fits: Synthesis is required to generate the final deliverables like the :executive-summary and the :document-survey. It takes the multi-dimensional findings from the analysis stage and integrates them into a cohesive narrative. The candidate's recommendation for parallelizing long artifacts and maintaining attribution across multiple structured inputs fits the multi-part output requirement.

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

4. Behavioral: Extraction (confidence: 0.80)
   Why this fits: Extraction is the necessary first step because the task involves processing raw text (the contracts) into structured items for comparison. The candidate's recommended pattern of using :map-each and :output-schemas is essential given the 'document-size' constraints (56K and 49K chars) which risk token-pressure loss if not handled systematically.

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

5. Behavioral: Validation (confidence: 0.60)
   Why this fits: Validation behaviors are needed to fulfill the 'Quality Requirement' regarding exact section citations. While the prompt asks for adversarial judgment (Critique), the more mechanical checking of 'cite a specific section number' fits the Validation pattern of Pass/Fail against formal rules.

   Guidance (seed `:summary`):
   Validation checks an artifact against formal rules, producing pass/fail + per-rule reasons. ALWAYS implement mechanical rules (schema / regex / field presence) in :code — deterministic and cheap. Only use :llm for judgment-requiring rules (semantic-fit / policy-intent). Structure failures as {:rule :reason :location} entries so the upstream producer in a validation-loop gets actionable retry signal. Wrap external rule sources in :fallback when the gate is on the critical path — silent failure of validation blocks the tree. Composes into producer-validator (validation-loop child), validation-loop pattern (with producer), and fallback-recovery shells (primary + backup validators). Avoid for judgment-based quality assessment (use critique) or for option selection (use convergent stage).

Capabilities:
  - given an artifact + a formal rule set (schema / spec / policy), produce a pass/fail decision per rule + reasons for any failures
  - natural fit as a gate node before downstream commitment (publish / merge / execute)
  - outputs are typically {:passed? boolean :violations [...]} or a per-rule pass-fail map with reasons

Strengths (proven traits — these patterns have been observed to work; mimic where they fit, adapt as needed):
  - **Trait:** implement the rule check itself in :code when the rule is mechanical (schema validation / regex match / field presence) — :code is deterministic and cheap; only fall back to :llm for rules that need judgment (confidence 1.00, evidence-count 1)
    - Good when: the rules are objective and machine-checkable
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:code {:reads [:artifact :schema] :writes [:result] :fn (fn [{:keys [inputs]}] {:result {:passed? true :violations []}})}]
      ```

  - **Trait:** structure the failure output as a vector of {:rule :reason :affected-location} entries — gives the upstream producer (validation-loop) actionable retry signal (confidence 1.00, evidence-count 1)
    - Good when: validation feeds a retry/refinement loop where the producer needs to know what to fix
    - Worked example DSL (corpus reference — adapt to your task):
      ```clojure
      [:llm {:reads [:artifact :rules] :writes [:result] :output-schemas {:result [:map [:passed? :boolean] [:violations [:vector [:map [:rule :string] [:reason :string] [:location :string]]]]]}}]
      ```

Weaknesses (observed failure modes — avoid these patterns, apply the recommended fix where applicable):
  - **Failure mode:** single :llm-based validation on a mechanical rule (schema match / field presence) is non-deterministic AND wastes tokens — the same artifact gets different pass/fail across runs (confidence 1.00, evidence-count 1)
    - Avoid when: the rule is fully expressible as code
    - Recommended fix: implement mechanical rules in :code; use :llm only for judgment-requiring rules (semantic-fit / policy-intent / tone)

  - **Failure mode:** validation without a fallback path silently blocks the tree when external rule sources fail — the gate never opens (confidence 1.00, evidence-count 1)
    - Avoid when: validation depends on an external service (policy API, schema registry) AND the rest of the tree can't degrade
    - Recommended fix: wrap external validation in :fallback with a local cached rule set OR a 'human review queue' branch — gate fails gracefully

Representative uses (concrete tasks this pattern has shipped on):
  - schema validation against a JSON Schema / Malli spec
  - policy compliance check against an org's content rules
  - completeness check: required sections present, required fields populated

---

````

After this block, the user's task instruction asking for the contract diff WITH adversarial validation appears verbatim.

## What the model designed

```clojure
[:sequence
 [:parallel
  [:sequence
   [:chunk-document {:from :contract_v2 :size 8000 :into :v2_chunks}]
   [:map-each {:from :v2_chunks :as :chunk :into :v2_sections :max-concurrency 3}
    [:llm {:instruction "Extract section headers and key provisions from this contract chunk.
                          Include section numbers and exact snippet text for references."
           :reads [:chunk] :writes [:extracted_v2]}]]
   [:aggregate {:from :v2_sections :writes [:all_v2_extracts]}]]
  [:sequence
   [:chunk-document {:from :contract_v3 :size 8000 :into :v3_chunks}]
   [:map-each {:from :v3_chunks :as :chunk :into :v3_sections :max-concurrency 3}
    [:llm {:instruction "Extract section headers and key provisions from this contract chunk.
                          Include section numbers and exact snippet text for references."
           :reads [:chunk] :writes [:extracted_v3]}]]
   [:aggregate {:from :v3_sections :writes [:all_v3_extracts]}]]]

 ;; Draft diff stage — first-pass comparison, writes to :draft_diffs
 [:llm {:instruction "Compare the extracted structures and provisions of v2.0 and v3.1.1.
                       Identify every addition, removal, and modification.
                       Classify changes as MAJOR, MINOR, or COSMETIC.
                       Produce a draft of :document-survey, :section-diffs, and :major-changes."
        :reads [:all_v2_extracts :all_v3_extracts] :writes [:draft_diffs]}]

 ;; THE ADVERSARIAL VALIDATOR — distinct persona, reads source contracts + drafts
 [:llm {:instruction "ADVERSARIAL VALIDATION: You are an antagonistic reviewer.
                       Review the draft differences against the source documents.
                       REMOVE any claim that cannot be verified with exact text from
                       :contract_v2 and :contract_v3.
                       If a claimed difference is actually consistent text across both,
                       delete the claim.
                       Ensure every remaining point cites a specific section number."
        :reads [:draft_diffs :contract_v2 :contract_v3]
        :writes [:validated_diff_data]}]

 [:llm {:instruction "Using only the validated diff data, produce the final report:
                       1. :document-survey (Structure overview)
                       2. :section-diffs (Detailed changes with references)
                       3. :major-changes (Typed classification)
                       4. :impact-analysis (Risk shift for Supplier vs OPA)
                       5. :executive-summary (Evolution overview)"
        :reads [:validated_diff_data]
        :writes [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]

 [:final {:keys [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]}]]
```

Four observations:

**Producer / validator / publisher separation.** The first synthesis `:llm` writes `:draft_diffs` (NOT one of the declared output keys). The validator `:llm` reads `:draft_diffs` + the source contracts, writes `:validated_diff_data`. The final `:llm` reads ONLY the validated data and writes the five declared outputs. The declared output keys are populated **only after the validator has rejected unsupported claims**. The Critique seed's "NEVER use the same prompt persona for critique as for the original producer — self-critique ratifies" principle is enforced structurally: different nodes, different `:reads`, different personae.

**The validator's `:reads` is the load-bearing element.** `:reads [:draft_diffs :contract_v2 :contract_v3]` — the validator has access to the *source of truth* (the raw contracts) plus the *artifact under review* (the drafts). The Critique seed's body warns: "self-critique on the same model (no prompt-framing change) tends to ratify the original artifact — the model is biased toward consistency with its prior output." This validator avoids that trap by reading the source-of-truth directly.

**Explicit rejection criteria.** "REMOVE any claim that cannot be verified with exact text" and "If a claimed difference is actually consistent text across both, delete the claim." The Critique seed prescribes "Always require per-issue location references AND per-issue :suggested-fix"; the model translated that into concrete validator rules.

**The "antagonistic reviewer" persona is verbatim from the seed.** "You are an antagonistic reviewer" — the Critique seed's `:strengths` body says "Use adversarial framing (skeptical reviewer / safety auditor)." The model's word choice ("antagonistic") is from the same register the seed prescribes.

## What the model reasoned about its choices

> **Iteration 1:** *"The task requires a high-fidelity comparison between two contract versions with a strict adversarial validation requirement. I will use the `emit-tree!` primitive to design a robust processing pipeline based on the 'Contract-comparison' and 'ResearchThenSynthesize' patterns.*
>
> *The tree begins by chunking both documents in parallel to handle the ~50-55k character length without losing detail (addressing the Extraction pattern's weakness). I will use a `:parallel` branch for each contract version to perform per-chunk extraction of section titles and key clauses; this ensures the `:document-survey` is grounded accurately.*
>
> *Next, I will use a `:llm` node to perform a 'Diff Reasoning' pass. Instead of asking one LLM to read both raw documents (which risks hallucination), I will feed it the aggregated structured extracts. This stage generates the `:section-diffs` and `:major-changes`.*
>
> ***Finally, I implement the 'Adversarial Validation' (Critique pattern) by passing the draft results to a skeptical reviewer persona. This reviewer is tasked with cross-referencing claims against the raw source text to strike out any unsupported assertions.*** *The final synthesized results will then be used to populate the five required output keys."*

Three corpus references in one paragraph: "Contract-comparison" and "ResearchThenSynthesize" patterns (structural) plus the "Critique pattern" (behavioral). The "skeptical reviewer persona" phrase is from the Critique seed's `:strengths` body. The "cross-referencing claims against the raw source text" sentence is the model verbalizing the Critique seed's "use a different prompt persona OR a different model OR an explicit checklist that forces specific question forms — adversarial framing improves catch rate" recommendation.

> **Iteration 2 (terminate):** Standard reuse-from-sandbox finalization.

## Output quality

Three claims that survived the validator stage:

**Section 5.1.2 / 8.1 / 9 (Prescribed Forms):**

> v3 mandates use of OPA-defined "Prescribed Forms" for all formal interactions: notice contact updates (5.1.2), termination notices (8.1), and amendments (9). v2.0 had a more open process for these procedural matters, allowing standard correspondence to suffice.

Verifiable: Appendix A in v3 contains the new "Prescribed Form" definition; sections 5.1.2 / 8.1 / 9 reference it. v2.0 has no such definition.

**Appendix C (Domestic Content Tables removed):**

> v3 removes the previously detailed Domestic Content Tables (Tables 1-3) from v2's Appendix C. v3's Appendix C focuses solely on Existing Building definitions, suggesting a major shift away from previous mandates regarding Domestic Content percentages.

Verifiable: v2.0's Appendix C contains explicit DC tables with percentage requirements (60% for solar PV). v3's Appendix C omits these tables entirely. This is the same headline change the predict-rlm comparison ([predict_rlm_comparison/reports/05_contract_comparison.md](../predict_rlm_comparison/reports/05_contract_comparison.md)) identified — corroborated through validated analysis.

**`:impact-analysis` (verbatim):**

> The transition from v2.0 to v3.1.1 fundamentally shifts the contractual landscape, favoring the OPA by consolidating power, increasing formalism, and introducing new restrictive criteria. **Supplier risk has increased** due to greater administrative overhead (mandatory Prescribed Forms) and a redefined, potentially narrower, definition of "Existing Building," which directly impacts project eligibility...

Frames the v2→v3 evolution as a power-redistribution: Supplier risk increases, OPA control increases. Grounded in specific section changes that survived the adversarial validator.

## What this run tells us about R-Inject

This is the cleanest demonstration in the suite of a prepend's behavioral seed driving structural design. The Critique seed's body — adversarial framing, source-of-truth reads, distinct producer/validator nodes, explicit rejection criteria — is implemented as a real node in the tree. The model could have collapsed all of that into prose disclaimers inside a single `:llm` call; it didn't. The structural separation is observable, recoverable, and load-bearing for the citation-grounded outputs that resulted.

Cost: +54% tokens, +161% wall clock vs the baseline's prose-validation approach. The reward: validated claims, structural producer/validator separation, source-cited findings. For an adversarial-validation-required task, this is the right tradeoff — the structural validator catches what prose disclaimers don't.

## Reproducing this report's data

- Verbatim prepend (28,905 chars — the longest in the suite): `:r-inject-trace.:prepend`
- All 5 structural candidates: `:r-inject-trace.:classifier-payload.:structural.:top-candidates`
- All 5 behavioral candidates (all above floor — richest behavioral retrieval): `:r-inject-trace.:classifier-payload.:behavioral.:behaviors`
- Per-iteration full code: `:iterations`
- Per-iteration reasoning verbatim: `:iteration-reasonings`
- Final tree: `:generated-tree-raw`
- Outputs by key: `:outputs`
- Token usage (Phase 1 + Phase 2 aggregated): `:usage`
