# 01 — Legal Issue Detection

The smallest input in the suite — a 7,331-character employment agreement — paired with a task that demands traceability: every flagged issue must cite the specific clause text. The headline question for this report: what does R-Inject's prepend cost on a task small enough that the baseline finished in 5,706 tokens, and what does the model build differently when the corpus discipline is in front of it?

**Run summary**

- **Status:** `:success`
- **Wall clock:** 58.4 s
- **Phase 1 + Phase 2 tokens:** 43,198 (38,386 prompt + 4,812 completion)
- **Iterations:** 2 (design → final)
- **Saved EDN:** [`generalization-results/legal-issue-detection_2026-06-02_120945.edn`](../generalization-results/legal-issue-detection_2026-06-02_120945.edn)
- **Source document:** [`documents/employment_agreement.txt`](../documents/employment_agreement.txt) — Pacific Northwest Financial Services Inc. employment agreement, BC jurisdiction (7,331 chars)
- **Models:** main + sub `google/gemini-3-flash-preview`

## Comparison to the un-prepended baseline

The original `bench/tasks/05-legal-issue-detection.md` reports the baseline (no R-Inject) ran in **8.8 seconds** using **5,706 tokens**. R-Inject's overhead on this task:

| Metric | Baseline | R-Inject | Δ |
|---|---:|---:|---:|
| Total tokens | 5,706 | 43,198 | **+37,492 (+657%)** |
| Wall clock | 8.8 s | 58.4 s | **+49.6 s (+563%)** |
| Tree stages | direct-answer (no tree) | 4 stages: chunk → map-each + per-chunk extraction → :code aggregator → synthesis | + 4 stages |

This is the steepest relative overhead in the suite. The baseline took the cheapest possible path on a 7K document: a single LLM call producing free-form prose. R-Inject's prepend (15,156 chars) is itself ~6× larger than the baseline's entire token budget. On a small document, the prepend cost dwarfs the document cost.

What the +37K bought: typed schemas with mandatory citation fields, per-chunk processing that survives token-pressure, a deterministic `:code` aggregation step, and final synthesis grounded in the structured extraction. Whether that's worth +563% wall-clock for a 6-page contract is a real engineering question — the answer depends on whether the user wants verifiable issues-with-citations vs a quick narrative read.

## What the classifier retrieved

`classify-task` retrieved 4 structural candidates; the 0.6 display floor admits only the top two into the rendered prepend. The reranker correctly recognizes the third and fourth as poor structural fits (one-document review vs the two-document or pure-scheduling shapes).

| # | Seed | Fitness | Why |
|---:|---|---:|---|
| 1 | `Legal-issue-detection` | **1.00** | "Perfectly matches the task's requirement for traceable legal review. It specifies an :output-schema with a :citation field, which directly supports the quality requirement that 'all issues must be traceable to specific clause text.' Its focus on flag detection and recommendations aligns with the requested :issues and :recommendations outputs." |
| 2 | `Risk-analysis` | **0.75** | "Pattern's focus on identifying risks/obligations and synthesizing an executive summary matches the intent's goal of identifying employee concerns. The use of :map-each and structured schemas is appropriate for reviewing a 7K character document, though it lacks the specific legal citation emphasis of the top candidate." |
| 3 | `Contract-comparison` | 0.30 | *Filtered (below 0.6 floor)* — "Specifically targets comparison/diffing between two documents or versions. The current task is a solo review of one agreement, making the parallel extraction and diff-synthesis pattern overly complex and semantically mismatched." |
| 4 | `Scheduling` | 0.00 | *Filtered* — "For scheduling and constraint-validation via code. No relevance to the semantic analysis or legal review of an employment contract's text." |

The behavioral classifier returned 3 candidates, all above 0.6:

| # | Behavior | Confidence | Why |
|---:|---|---:|---|
| 1 | Analysis | **0.95** | "Best fit as it specifically targets producing findings with evidence and recommends tying findings to specific items (the contract clauses) for auditability." |
| 2 | Critique | **0.92** | "Adversarial review of an artifact against quality criteria (Employee's interests). Critique's use of adversarial framing and located issues/suggested fixes aligns perfectly with the requirement for traceable issues and recommendations." |
| 3 | Extraction | **0.65** | "The 'extraction' pattern is relevant for the initial identification of terms before the analysis of 'issues' or 'missing' components begins." |

The pairing — Analysis as the reasoning frame, Critique as the adversarial frame, Extraction as the per-chunk discipline — is the suite-wide pattern: same task draws on multiple behavioral primitives, with the rich prepend surfacing each one's `:strengths` and `:weaknesses` bodies in full.

## The verbatim prepend the model received

The block below — 15,156 characters total — was prepended to the model's Phase 1 task input *exactly as shown*, before the original task instruction. Headers include the seed name (`#### Top match — Legal-issue-detection (confidence: 1.00)`) so the model can reference candidates by name in its reasoning:

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
#### Top match — Legal-issue-detection (confidence: 1.00)
Why this fits: This candidate perfectly matches the task's requirement for traceable legal review. It specifies an :output-schema with a :citation field, which directly supports the quality requirement that 'all issues must be traceable to specific clause text.' Its focus on flag detection and recommendations aligns with the requested :issues and :recommendations outputs.

Pattern guidance (seed `:summary`):
Legal-issue-detection trees flag legal concerns in a document with per-issue severity, area-of-law, source citation, and recommendation. The proven shape mirrors risk-analysis but with citation as a first-class output: per-section :map-each with :output-schemas requiring :citation field, then :code (NOT :llm) for the severity-summary rollup. Always require citation in the schema so users can verify the model's flags against the source.


#### Alternative #1 — Risk-analysis (confidence: 0.75)
Why this fits: This pattern's focus on identifying risks/obligations and synthesizing an executive summary matches the intent's goal of identifying employee concerns. The use of :map-each and structured schemas is appropriate for reviewing a 7K character document, though it lacks the specific legal citation emphasis of the top candidate.

Pattern guidance (seed `:summary`):
Risk-analysis trees identify and categorize risks/obligations from a document into a per-item :risk-matrix (HIGH/MEDIUM/LOW + justification) plus an :executive-summary. The proven shape is per-section :map-each with structured :output-schemas: split by section delimiter, classify each section under bounded concurrency, aggregate the matrix, then synthesize the executive summary. Always use :output-schemas to constrain severity to an enum — free-form strings drift across iterations and break aggregation.


### Behavioral competencies (top 3 from corpus retrieval)
1. Behavioral: Analysis (confidence: 0.95)
   Why this fits: The task requires reasoning over a document to produce structured findings (issues, ambiguities, missing elements). Analysis is the best fit as it specifically targets producing findings with evidence and recommends tying findings to specific items (the contract clauses) for auditability, which matches the task's quality requirement.

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

2. Behavioral: Critique (confidence: 0.92)
   Why this fits: The task is essentially an adversarial review of an artifact (the contract) against quality criteria (Employee's interests). Critique's use of adversarial framing and located issues/suggested fixes aligns perfectly with the requirement for traceable issues and recommendations.

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

3. Behavioral: Extraction (confidence: 0.65)
   Why this fits: While the query asks for review/reasoning, the structure involves identifying specific clauses (extraction) into the four required outputs. The 'extraction' pattern is relevant for the initial identification of terms before the analysis of 'issues' or 'missing' components begins.

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

---

````

After this block, the user's task instruction asking for `:issues`, `:ambiguities`, `:missing`, and `:recommendations` appears verbatim.

## What the model designed

```clojure
[:sequence
 [:chunk-document {:from :employment_agreement :size 3500 :into :chunks}]
 [:map-each
  {:from :chunks :as :chunk :into :chunk_findings :max-concurrency 2}
  [:llm
   {:instruction
    "Review this employment agreement chunk from the EMPLOYEE's perspective.
     Identify:
     1. Issues: Broad or unfavorable terms (include specific clause citation).
     2. Ambiguities: Vague language that could be risky (include citation).
     3. Missing: Standard protections missing from this specific section.
     All citations must be verbatim text from the document."
    :reads [:chunk]
    :writes [:issues :ambiguities :missing]
    :output-schemas
    {:issues [:vector [:map [:text :string] [:citation :string] [:reason :string]]]
     :ambiguities [:vector [:map [:text :string] [:citation :string] [:explanation :string]]]
     :missing [:vector [:string]]}}]]

 ;; The :code aggregator that flattens per-chunk findings — full fn source below
 [:code
  {:fn (fn [{:keys [inputs]}]
         (let [results (:chunk_findings inputs)]
           {:raw_issues (mapcat :issues results)
            :raw_ambiguities (mapcat :ambiguities results)
            :raw_missing (distinct (mapcat :missing results))}))
   :reads [:chunk_findings]
   :writes [:raw_issues :raw_ambiguities :raw_missing]}]

 [:llm
  {:instruction
   "Synthesize the final review for the employee.
    - Consolidate raw_issues and raw_ambiguities, removing duplicates.
    - Review raw_missing and identify standard employment protections absent
      from the WHOLE agreement.
    - Create actionable recommendations for negotiation based on these findings.
    Format the final output strictly into the required keys."
   :reads [:raw_issues :raw_ambiguities :raw_missing :employment_agreement]
   :writes [:issues :ambiguities :missing :recommendations]}]

 [:final {:keys [:issues :ambiguities :missing :recommendations]}]]
```

Four observations on what changed from the baseline single-call shape:

**The model designed a chunked tree for a 7K document.** Chunk size 3,500 chars produces 2-3 chunks; `:max-concurrency 2` matches. This is overkill on cost grounds — the document fits a single context easily — but the model's stated reasoning (below) makes it explicit: "the quality requirements mandate specific clause traceability, which is more reliable when the model processes smaller contexts." The Extraction seed's `:strengths` body says "for documents large enough to risk context truncation, use :map-each over chunks" — the model chose to apply that discipline even when truncation isn't imminent.

**Typed schemas with three fields per finding.** Each issue is `[:map [:text :string] [:citation :string] [:reason :string]]`. The `:citation` field is the load-bearing one — it's what the task instruction demanded and what the Legal-issue-detection seed body specifically prescribes. The `:reason` field is the model's addition, giving downstream synthesis a per-finding justification.

**The `:code` aggregator is correctly wired.** The inline fn `(fn [{:keys [inputs]}] ...)` destructures the input map, pulls `:chunk_findings`, and flattens with `mapcat`. The aggregation produces `:raw_issues`, `:raw_ambiguities`, `:raw_missing` — distinct keys from the final declared writes, so the synthesis stage operates on intermediate names and the final stage produces the declared shape. (Compare to task 04's risk-analysis run, where an analogous `:code` aggregator misunderstood the `:map-each` output shape and silently produced empty vectors.)

**Final synthesis reads the original document too.** `:reads [:raw_issues :raw_ambiguities :raw_missing :employment_agreement]`. The synthesis stage needs the structured findings (its primary input) AND the raw agreement (so it can spot whole-document gaps like "no termination clause" that aren't visible from per-chunk extractions alone). The Analysis seed's `:strengths` body recommends multi-input synthesis for this reason.

## What the model reasoned about its choices

> **Iteration 1 (the design call):** *"The task requires a traceable legal review of a 7,300-character employment agreement from the employee's perspective. I am choosing a sequential pipeline that first chunks the document to ensure high recall for specific clauses (as per the Extraction strength), then performs a per-chunk analysis for issues, ambiguities, and missing elements. This approach follows the Top Match (Legal-issue-detection) and Analysis patterns by using `:output-schemas` to enforce structured findings with mandatory citations/traceability. I considered a single-pass LLM call but rejected it because the quality requirements mandate specific clause traceability, which is more reliable when the model processes smaller contexts. The final step aggregates these findings into the four required output keys."*

Three corpus references in one paragraph: "the Extraction strength," "the Top Match (Legal-issue-detection)," "Analysis patterns." The model names the seeds by name and ties each one to a specific design decision (chunking → Extraction, typed schemas → Legal-issue-detection, multi-stage → Analysis).

The "I considered a single-pass LLM call but rejected it" sentence is worth noting — it's the model verbalizing the choice not to take the baseline's path even though the document size would permit it. This is the prepend's discipline overriding the default cheap-path heuristic.

> **Iteration 2 (the termination call):** *"The previous iteration successfully executed a comprehensive pipeline: chunking the agreement, performing parallel extraction of issues/ambiguities/missing clauses with structured schemas for traceability, and synthesizing the final results. I have verified that the variables `:issues`, `:ambiguities`, `:missing`, and `:recommendations` are now populated in the sandbox as per the iteration history. ... The task is complete. I am now returning the final results."*

Clean reuse-from-sandbox finalization. The model checks that the required vars are populated and calls `(final! ...)` without re-running anything.

## Output quality

**`:recommendations`** — the synthesized negotiation guidance:

> 1. **Define Bonus Metrics:** Request an addendum or revised Schedule A that specifies the KPIs (Individual vs. Company) and changes "discretionary" to "formulaic" based on those targets.
> 2. **Limit Restrictive Covenants:** Negotiate the 18-month non-solicitation down to 12 months and seek to strike the Non-Compete clause entirely, as it is notoriously difficult to enforce in BC for this role.
> 3. **Data Privacy Protection:** Request that sensitive identification (Passport/Driver's License/Health Cards) be redacted from the final contract and held in a secured HR file instead.
> 4. **Clarify Termination Procedures:** Insist on the inclusion of a termination clause that explicitly guarantees at least the minimum notice or pay-in-lieu required by the BC Employment Standards Act.
> 5. **Mitigate Injunction Risks:** Request the removal of the "monetary damages may be inadequate" admission to preserve your right to defend yourself in court should a dispute arise.
> 6. **Limit Background Search:** Narrow the background check authorization to "relevant professional and criminal history," explicitly excluding or limiting credit checks unless strictly required by FINTRAC/regulatory compliance/bonding.

Recommendation #2 cites the **18-month non-solicitation** and Non-Compete — verifiable against the source (Schedule E sections 2-3). Recommendation #3 mentions Passport/Driver's License/Health Cards — all of which are present in the schedules and represent a real privacy concern. Recommendation #4 references the **BC Employment Standards Act** — jurisdictionally accurate. Recommendation #6 mentions FINTRAC — a real Canadian financial regulatory body whose requirements would actually justify narrower background checks.

The model is synthesizing findings into actionable advice grounded in:
- Specific contract clauses (verifiable against source)
- The right jurisdiction's legal frameworks (BC ESA, FINTRAC)
- Realistic negotiation scope (e.g., narrowing rather than striking the non-solicit)

This is the kind of output the prepend's `:citation`-required typed schema enables. Without traceability through the pipeline, recommendations like #4 could easily reference the wrong jurisdiction.

## What this run tells us about R-Inject

For a small (~7K char) document, R-Inject's costs are dominated by the prepend itself, not by Phase 2 sub-LLM work. The baseline produces a serviceable narrative in 5.7K tokens; R-Inject produces typed, citation-grounded outputs in 43K tokens — roughly 7.5× the cost. The trade is real and the right answer is task-dependent:

- If the consumer needs to *verify* each issue against the source (compliance review, due diligence, legal-team handoff), R-Inject's `:citation`-typed schema is load-bearing — the verification can be mechanical rather than re-reading the agreement.
- If the consumer just wants a quick "is this a reasonable employment agreement" read, the baseline is fine.

The prepend's specific contribution: the model designed a 4-stage tree with mandatory citations even though the document size permitted a simpler answer. Iteration 1's reasoning makes this explicit — it's not the model defaulting to overkill, it's the model choosing the corpus-discipline path because the prepend made that discipline salient.

Cost transparency note: the 43,198 tokens reported are the executor's `:usage` (Phase 1 + Phase 2 sub-LLM aggregation). The classifier reranker calls themselves contribute additional tokens not visible in this field; on a single-task confirmation run with the new `:node-trace` capture, the full system cost for legal-issue-detection was 53,525 tokens (`:node-trace-usage-total`). The classifier overhead — ~10K tokens — is the cost of corpus retrieval + reranking and should be expected on every R-Inject task.

## Reproducing this report's data

- Verbatim prepend (15,156 chars): `:r-inject-trace.:prepend`
- All 4 structural candidates with fitness scores + reasoning: `:r-inject-trace.:classifier-payload.:structural.:top-candidates`
- All 3 behavioral candidates with confidence + reasoning: `:r-inject-trace.:classifier-payload.:behavioral.:behaviors`
- Per-iteration full code (including inline `:code` fn source): `:iterations`
- Per-iteration reasoning verbatim: `:iteration-reasonings`
- Final tree (with `:code` fns sanitized to `<inline-fn>` — sources in `:iterations[].code`): `:generated-tree-raw`
- Outputs by key: `:outputs`
- Token usage (Phase 1 + Phase 2 aggregated): `:usage`
