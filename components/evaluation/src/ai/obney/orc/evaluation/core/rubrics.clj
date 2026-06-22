(ns ai.obney.orc.evaluation.core.rubrics
  "Evaluation rubrics for LLM-as-judge evaluation.

   Each rubric defines:
   - What dimension of quality is being evaluated
   - Scoring criteria (0.0 to 1.0)
   - The prompt template for the judge LLM
   - Weight for aggregation with other dimensions

   These rubrics are designed for REFERENCE-FREE evaluation,
   meaning they don't require ground truth labels.

   ---------------------------------------------------------------------------
   PA-3 (2026-06): the GROUNDING judge has been redesigned to the tier-1
   shape (ADR 0011 / doc/judge-framework-verdict-notes.md):
     - a first-class DISCRETE 1-5 `Scale` with explicit per-level bands,
       DECOUPLED from the criteria/instruction (see core/scale.clj), mapped
       deterministically to [0,1] for storage;
     - an ADVERSARIAL, SOURCE-GROUNDED, reason-BEFORE-score stance;
     - structured output via the typed blackboard (DSCloj output fields with
       {:description ...}) — NO json-in-prompt, NO permissive output schema.
   The old soft-0.0-1.0 `GROUNDING_RUBRIC` below is retained only for the
   legacy retrospective code paths; the live grounding judge uses
   GROUNDING_CRITERIA + GROUNDING_SCALE.

   PA-4 (2026-06): the remaining three judges — INSTRUCTION-FOLLOWING,
   REASONING, and COMPLETENESS — are migrated to the SAME tier-1 shape,
   mirroring grounding exactly: decoupled *_CRITERIA / *_STANCE / *_SCALE,
   bundled as *_TIER1, resolved via get-tier1-rubric. Their old soft-0.0-1.0
   rubrics below are likewise retained only for legacy retrospective paths.
   ---------------------------------------------------------------------------"
  (:require [ai.obney.orc.evaluation.core.scale :as scale]))

;; =============================================================================
;; Grounding/Hallucination Detection
;; =============================================================================

(def GROUNDING_RUBRIC
  {:name "Source Grounding"
   :weight 0.35
   :description "Evaluates whether the response is grounded in the provided context/inputs"
   :prompt
   "You are evaluating whether an LLM response is grounded in the provided context.

## Task
Check if every factual claim in the response can be traced to the input context.
Identify any hallucinations (claims not supported by the inputs).

## Input Context
The LLM was given these inputs:
```json
{inputs}
```

## LLM Response
```
{response}
```

## Instruction Given to LLM
```
{instruction}
```

## Scoring Rubric
- 1.0 (Excellent): Every claim is traceable to inputs, no hallucinations
- 0.8 (Good): Almost all claims grounded, very minor extrapolations only
- 0.6 (Adequate): Most claims grounded, some unsupported but plausible inferences
- 0.4 (Poor): Significant ungrounded claims that could mislead
- 0.2 (Very Poor): Most information not from inputs
- 0.0 (Failed): Contains clear hallucinations or contradicts inputs

## Required Output (JSON)
{
  \"score\": <float 0.0-1.0>,
  \"grounded-claims\": [\"list of claims that ARE supported by inputs\"],
  \"ungrounded-claims\": [\"list of claims that are NOT supported\"],
  \"feedback\": \"Specific actionable feedback explaining the score. Include suggestions for improvement.\"
}"})

;; =============================================================================
;; PA-3 — Grounding judge: decoupled criteria + discrete-band Scale
;; =============================================================================
;;
;; These three artifacts are kept SEPARATE on purpose (the tier-1 decoupling):
;;   GROUNDING_CRITERIA — WHAT to evaluate (the dimension's definition)
;;   GROUNDING_STANCE   — HOW to behave (adversarial, source-grounded persona)
;;   GROUNDING_SCALE    — HOW to score (a first-class discrete 1-5 Scale)
;; The judge composes them; none of them embeds the others.

(def GROUNDING_CRITERIA
  "WHAT the grounding judge evaluates. Decoupled from the scale + stance."
  (str "Grounding measures whether every factual claim in the RESPONSE can be "
       "traced to the SOURCE (the inputs/context the producer was given). "
       "An ungrounded claim is any statement of fact, number, name, date, or "
       "entity that is NOT present in or directly entailed by the source — "
       "even if it sounds plausible or is generally true in the world. "
       "Contradicting the source is worse than merely adding unsupported "
       "detail. Generic framing, hedging, and restating the task are neither "
       "grounded nor ungrounded claims — judge the substantive factual "
       "content."))

(def GROUNDING_STANCE
  "HOW the grounding judge behaves: an adversarial, source-grounded reviewer.
   This is the cheap, high-value calibration move (ADR 0011): the judge
   defends the null that the output is NOT grounded and hunts for flaws."
  (str "You are a skeptical, adversarial reviewer. Your default assumption is "
       "that the response is NOT fully grounded until the source proves "
       "otherwise. Actively hunt for claims the source does not support. Check "
       "every specific fact, number, name, date, and entity against the "
       "source — not against your own world knowledge, and never against the "
       "producer's self-report. A confident, fluent response that invents one "
       "specific fact is a grounding failure, not a success. First reason "
       "through the evidence, THEN choose a band."))

(def GROUNDING_SCALE
  "PA-3 grounding Scale — REVIEWED & FINALIZED 2026-06-16 (keep-strict).

   A first-class discrete 1-5 scale with explicit per-level bands, decoupled
   from the criteria/stance. Mapped deterministically to [0,1] via
   scale/level->unit-score (1→0.0, 2→0.25, 3→0.5, 4→0.75, 5→1.0).

   These band descriptions were calibrated against REAL bench-document
   traces (employment_agreement.txt, yyj_rfp-derived) scored live on
   OpenRouter — see development/prototype_grounding_calibration.clj and the
   PA-3 report. Strictness policy (human-reviewed): ANY inference presented
   as fact — even hedged ('likely', 'probably') — caps at band 3. Grounding
   is deliberately unforgiving; balanced by the other dimension judges, the
   satisfaction judge, and human-gated GEPA acceptance."
  (scale/discrete-scale
    {:min 1 :max 5
     :bands
     {1 (str "Ungrounded / fabricated. The response contradicts the source on "
             "central facts, or is about a different subject entirely, or "
             "nearly all of its specific claims are inventions. A reader "
             "relying on it would be badly misled.")
      2 (str "Largely ungrounded. Multiple specific claims (facts, numbers, "
             "names, dates, entities) are unsupported or wrong, OR one central "
             "fact is fabricated/contradicted, even if some surrounding detail "
             "is correct. More wrong than right on substance.")
      3 (str "Mixed grounding. Most of the substance is supported, but there "
             "are one or more unsupported claims or inferences presented as "
             "fact — INCLUDING hedged ones ('likely', 'probably'). A reader "
             "should verify before trusting it.")
      4 (str "Well grounded. Every substantive claim traces to the source; "
             "the only deviation is a minor imprecise phrasing or omission "
             "that does not mislead about a fact. NO inference or extrapolation "
             "presented as fact, even hedged — any such claim caps at band 3.")
      5 (str "Fully grounded. Every substantive factual claim is directly "
             "supported by the source. No fabrication, no contradiction, and "
             "no inference presented as fact — hedged or otherwise. Strictly "
             "faithful to what the source says.")}}))

;; =============================================================================
;; Instruction Following
;; =============================================================================

(def INSTRUCTION_FOLLOWING_RUBRIC
  {:name "Instruction Following"
   :weight 0.25
   :description "Evaluates whether the LLM followed the given instruction"
   :prompt
   "You are evaluating whether an LLM correctly followed its instruction.

## Instruction Given
```
{instruction}
```

## LLM Response
```
{response}
```

## LLM Inputs
```json
{inputs}
```

## Task
Evaluate how well the response follows the instruction:
1. Did it address the main task described in the instruction?
2. Did it follow any specific formatting requirements?
3. Did it include all required output components?
4. Did it avoid doing things the instruction forbade?

## Scoring Rubric
- 1.0 (Excellent): Instruction followed completely, all requirements met
- 0.8 (Good): Minor deviations but main task accomplished correctly
- 0.6 (Adequate): Main task done but missing some requirements
- 0.4 (Poor): Partial compliance, significant deviations
- 0.2 (Very Poor): Barely followed the instruction
- 0.0 (Failed): Did not follow the instruction at all

## Required Output (JSON)
{
  \"score\": <float 0.0-1.0>,
  \"requirements-met\": [\"list of instruction requirements that were satisfied\"],
  \"requirements-missed\": [\"list of instruction requirements that were NOT satisfied\"],
  \"feedback\": \"Specific actionable feedback explaining the score and how to better follow the instruction.\"
}"})

;; =============================================================================
;; Reasoning Quality
;; =============================================================================

(def REASONING_QUALITY_RUBRIC
  {:name "Reasoning Quality"
   :weight 0.20
   :description "Evaluates the coherence and quality of reasoning"
   :prompt
   "You are evaluating the quality of reasoning in an LLM response.

## Instruction Given
```
{instruction}
```

## LLM Response
```
{response}
```

## LLM Inputs
```json
{inputs}
```

## Task
Evaluate the reasoning quality:
1. Is the reasoning clear and easy to follow?
2. Are the logical steps connected properly?
3. Does the reasoning lead naturally to the conclusion?
4. Are there any logical fallacies or gaps?

## Scoring Rubric
- 1.0 (Excellent): Crystal clear reasoning, logical flow, well-justified conclusions
- 0.8 (Good): Clear reasoning with minor gaps or unstated assumptions
- 0.6 (Adequate): Understandable but could be clearer or more rigorous
- 0.4 (Poor): Confusing or contains logical gaps
- 0.2 (Very Poor): Hard to follow, unclear how conclusions were reached
- 0.0 (Failed): No apparent reasoning or completely incoherent

## Required Output (JSON)
{
  \"score\": <float 0.0-1.0>,
  \"reasoning-strengths\": [\"aspects of reasoning that were good\"],
  \"reasoning-weaknesses\": [\"logical gaps or unclear elements\"],
  \"feedback\": \"Specific actionable feedback for improving reasoning clarity and rigor.\"
}"})

;; =============================================================================
;; Completeness
;; =============================================================================

(def COMPLETENESS_RUBRIC
  {:name "Completeness"
   :weight 0.20
   :description "Evaluates whether all aspects of the task were addressed"
   :prompt
   "You are evaluating whether an LLM response is complete.

## Instruction Given
```
{instruction}
```

## LLM Response
```
{response}
```

## LLM Inputs
```json
{inputs}
```

## Task
Evaluate completeness:
1. Does the response address all parts of the instruction?
2. Does it utilize all relevant information from the inputs?
3. Is anything missing that should have been included?
4. Does it provide sufficient detail?

## Scoring Rubric
- 1.0 (Excellent): Complete response, all aspects addressed with appropriate detail
- 0.8 (Good): Nearly complete, only minor details missing
- 0.6 (Adequate): Mostly complete but missing some useful information
- 0.4 (Poor): Incomplete, missing significant aspects
- 0.2 (Very Poor): Very incomplete, barely addresses the task
- 0.0 (Failed): Response is a stub or doesn't address the task

## Required Output (JSON)
{
  \"score\": <float 0.0-1.0>,
  \"aspects-covered\": [\"aspects of the task that were addressed\"],
  \"aspects-missing\": [\"aspects that should have been included but weren't\"],
  \"feedback\": \"Specific actionable feedback for improving completeness.\"
}"})

;; =============================================================================
;; Default Rubric Set
;; =============================================================================

(def DEFAULT_RUBRICS
  "Default set of rubrics for reference-free evaluation.
   Weights sum to 1.0."
  {:grounding GROUNDING_RUBRIC
   :instruction-following INSTRUCTION_FOLLOWING_RUBRIC
   :reasoning REASONING_QUALITY_RUBRIC
   :completeness COMPLETENESS_RUBRIC})

(defn get-rubric
  "Get a rubric by key."
  [key]
  (get DEFAULT_RUBRICS key))

(defn get-rubrics
  "Get multiple rubrics by keys. Returns all if keys is nil."
  [keys]
  (if keys
    (select-keys DEFAULT_RUBRICS keys)
    DEFAULT_RUBRICS))

;; =============================================================================
;; PA-3 — tier-1 grounding rubric accessor (criteria × stance × scale)
;; =============================================================================

(def GROUNDING_TIER1
  "The redesigned grounding judge's decoupled pieces, bundled for the judge fn
   to compose at call time. The Scale is first-class and separable."
  {:name "Source Grounding"
   :weight 0.35
   :criteria GROUNDING_CRITERIA
   :stance GROUNDING_STANCE
   :scale GROUNDING_SCALE})

;; =============================================================================
;; PA-4 — Instruction-following judge: decoupled criteria + discrete-band Scale
;; =============================================================================

(def INSTRUCTION_FOLLOWING_CRITERIA
  "WHAT the instruction-following judge evaluates. Decoupled from scale + stance."
  (str "Instruction-following measures whether the RESPONSE does exactly what "
       "the INSTRUCTION asked — no more, no less. Check every explicit "
       "directive: the main task, any format/structure/length requirement, "
       "every required output component, and every PROHIBITION (things the "
       "instruction said NOT to do). A response that is fluent and useful but "
       "ignores a specific directive is a following failure on that directive. "
       "Doing extra, unrequested work counts against following only when the "
       "instruction implied a constraint it violates (e.g. 'answer in one "
       "sentence'). Judge compliance with the instruction's literal demands, "
       "not the general quality of the answer."))

(def INSTRUCTION_FOLLOWING_STANCE
  "HOW the instruction-following judge behaves: an adversarial compliance
   auditor. ADR 0011 keep-strict: defend the null that the response did NOT
   fully comply, and hunt for any directive it skipped or violated."
  (str "You are a skeptical, adversarial compliance auditor. Your default "
       "assumption is that the response did NOT fully follow the instruction "
       "until you have checked every directive. Enumerate the instruction's "
       "explicit requirements AND prohibitions first, then verify each one "
       "against the response. A single missed required component, a violated "
       "format/length constraint, or a forbidden action performed is a "
       "compliance failure — fluency does not excuse it. First reason through "
       "each requirement, THEN choose a band."))

(def INSTRUCTION_FOLLOWING_SCALE
  "PA-4 instruction-following Scale — calibrated live on real traces (see
   development/src/prototype_tier1_calibration.clj). Discrete 1-5 with explicit
   per-level bands, decoupled from criteria/stance, mapped deterministically to
   [0,1] (1->0.0 ... 5->1.0). Keep-strict (ADR 0011): ANY violated requirement
   or prohibition caps the score; only a fully-compliant response reaches 5."
  (scale/discrete-scale
    {:min 1 :max 5
     :bands
     {1 (str "Did not follow the instruction. The response addresses a "
             "different task, ignores the core directive, or does the opposite "
             "of what was asked. A reader would not recognize it as a response "
             "to this instruction.")
      2 (str "Barely followed. The main task is only partially attempted AND "
             "multiple explicit requirements are missed or violated, OR a "
             "central directive (the primary ask) was not satisfied even if "
             "minor ones were.")
      3 (str "Partially followed. The main task is addressed, but one or more "
             "explicit requirements are missed or a stated constraint/"
             "prohibition is violated (e.g. wrong format, exceeded length, did "
             "a forbidden thing). A reader gets the gist but not what was "
             "specified.")
      4 (str "Well followed. The main task and all required components are "
             "satisfied; the only deviation is a minor, non-substantive "
             "imprecision that does not violate any explicit directive. NO "
             "missed required component and NO violated prohibition — any such "
             "violation caps at band 3.")
      5 (str "Fully followed. Every explicit directive is satisfied: the main "
             "task, all format/structure/length requirements, all required "
             "components, and all prohibitions respected. Exactly what was "
             "asked, no violated constraint.")}}))

(def INSTRUCTION_FOLLOWING_TIER1
  "The redesigned instruction-following judge's decoupled pieces, bundled."
  {:name "Instruction Following"
   :weight 0.25
   :criteria INSTRUCTION_FOLLOWING_CRITERIA
   :stance INSTRUCTION_FOLLOWING_STANCE
   :scale INSTRUCTION_FOLLOWING_SCALE})

;; =============================================================================
;; PA-4 — Reasoning-quality judge: decoupled criteria + discrete-band Scale
;; =============================================================================

(def REASONING_CRITERIA
  "WHAT the reasoning judge evaluates. Decoupled from scale + stance."
  (str "Reasoning quality measures whether the RESPONSE's argument is sound: "
       "are the logical steps explicit and correctly connected, does each step "
       "follow from the prior ones and the inputs, and does the conclusion "
       "actually follow from the steps? Hunt for logical gaps, unstated "
       "assumptions presented as established, non-sequiturs, circular "
       "reasoning, and conclusions that overreach the evidence. A confident, "
       "well-written conclusion with no visible or valid derivation is a "
       "reasoning failure, not a success. Judge the soundness of the inference "
       "chain, not the prose style or the surface plausibility of the answer."))

(def REASONING_STANCE
  "HOW the reasoning judge behaves: an adversarial logician. ADR 0011
   keep-strict: defend the null that the reasoning is flawed and hunt for the
   weakest link."
  (str "You are a skeptical, adversarial logician. Your default assumption is "
       "that the reasoning is flawed until each step proves itself. Trace the "
       "inference chain from premises to conclusion and attack the weakest "
       "link: find unstated assumptions, gaps where a step is asserted not "
       "derived, and conclusions that claim more than the steps support. A "
       "fluent answer that hides a logical leap is a reasoning failure. First "
       "reason through the chain's soundness, THEN choose a band."))

(def REASONING_SCALE
  "PA-4 reasoning-quality Scale — calibrated live on real traces. Discrete 1-5
   with explicit per-level bands, mapped deterministically to [0,1]. Keep-strict
   (ADR 0011): any logical leap presented as established caps at band 3; only a
   fully sound, gap-free chain reaches 5."
  (scale/discrete-scale
    {:min 1 :max 5
     :bands
     {1 (str "Incoherent or absent reasoning. There is no discernible logical "
             "chain, or the steps contradict each other, or the conclusion is "
             "unrelated to anything argued. A reader cannot tell how (or "
             "whether) the answer was derived.")
      2 (str "Seriously flawed reasoning. The chain has a major break — a "
             "central non-sequitur, circular argument, or a conclusion that "
             "plainly does not follow from the steps — even if individual "
             "sentences read sensibly.")
      3 (str "Mixed reasoning. The overall direction is followable, but there "
             "are one or more logical gaps or unstated assumptions presented "
             "as established fact, OR the conclusion overreaches what the steps "
             "support. A reader should not fully trust the inference.")
      4 (str "Sound reasoning. Each step follows from the prior ones and the "
             "inputs, and the conclusion follows from the steps; the only "
             "weakness is a minor unstated-but-obvious assumption that does "
             "not threaten the conclusion. NO logical leap presented as "
             "established — any such leap caps at band 3.")
      5 (str "Rigorous reasoning. Every step is explicit and correctly "
             "connected, no gaps or unstated assumptions of substance, and the "
             "conclusion follows necessarily from the steps. The inference "
             "chain would survive an adversarial read.")}}))

(def REASONING_TIER1
  "The redesigned reasoning judge's decoupled pieces, bundled."
  {:name "Reasoning Quality"
   :weight 0.20
   :criteria REASONING_CRITERIA
   :stance REASONING_STANCE
   :scale REASONING_SCALE})

;; =============================================================================
;; PA-4 — Completeness judge: decoupled criteria + discrete-band Scale
;; =============================================================================

(def COMPLETENESS_CRITERIA
  "WHAT the completeness judge evaluates. Decoupled from scale + stance."
  (str "Completeness measures whether the RESPONSE covers everything the "
       "INSTRUCTION required and uses the relevant material in the INPUTS. "
       "Identify every distinct aspect the task asks for (each sub-question, "
       "each requested field, each part of a multi-part ask) and check that "
       "the response addresses each one with sufficient detail to be useful. "
       "An omitted required aspect, an under-detailed stub answer, or relevant "
       "input information left unused is an incompleteness. This is NOT about "
       "correctness or grounding (other judges cover those) — only about "
       "coverage: is anything that SHOULD be there missing?"))

(def COMPLETENESS_STANCE
  "HOW the completeness judge behaves: an adversarial coverage auditor. ADR 0011
   keep-strict: defend the null that something required is missing and hunt for
   the gap."
  (str "You are a skeptical, adversarial coverage auditor. Your default "
       "assumption is that the response is incomplete until you have accounted "
       "for every aspect the instruction required. Enumerate the distinct "
       "required aspects first, then check each against the response for "
       "presence AND sufficient detail. A response that covers most aspects "
       "but silently drops one, or answers a sub-question with a stub, is "
       "incomplete. First reason through the coverage, THEN choose a band."))

(def COMPLETENESS_SCALE
  "PA-4 completeness Scale — calibrated live on real traces. Discrete 1-5 with
   explicit per-level bands, mapped deterministically to [0,1]. Keep-strict
   (ADR 0011): any required aspect missing or answered as a stub caps the
   score; only full coverage with sufficient detail reaches 5."
  (scale/discrete-scale
    {:min 1 :max 5
     :bands
     {1 (str "Essentially empty. The response is a stub or does not address "
             "the task's aspects at all — nearly everything required is "
             "missing. A reader gets almost nothing they asked for.")
      2 (str "Largely incomplete. Most required aspects are missing or only "
             "gestured at; the response covers a minority of what the task "
             "asked, or answers the main aspect but omits most others.")
      3 (str "Partially complete. The main aspects are covered, but one or "
             "more required aspects are missing, OR several are answered as "
             "thin stubs lacking the detail to be useful, OR clearly relevant "
             "input information is left unused. Noticeable gaps remain.")
      4 (str "Nearly complete. Every required aspect is addressed with "
             "adequate detail; the only shortfall is a minor, non-essential "
             "elaboration that a reader would not miss. NO required aspect "
             "missing and NO stub answers — any such gap caps at band 3.")
      5 (str "Fully complete. Every required aspect is addressed with "
             "sufficient detail, and the relevant input information is used. "
             "Nothing that should be there is missing or under-developed.")}}))

(def COMPLETENESS_TIER1
  "The redesigned completeness judge's decoupled pieces, bundled."
  {:name "Completeness"
   :weight 0.20
   :criteria COMPLETENESS_CRITERIA
   :stance COMPLETENESS_STANCE
   :scale COMPLETENESS_SCALE})

(defn get-tier1-rubric
  "Get a redesigned (tier-1) rubric by key. PA-3 shipped :grounding; PA-4 adds
   :instruction-following, :reasoning, :completeness. Returns nil for unknown
   keys (e.g. :heuristic-structural, which keeps its deterministic shape)."
  [key]
  (case key
    :grounding GROUNDING_TIER1
    :instruction-following INSTRUCTION_FOLLOWING_TIER1
    :reasoning REASONING_TIER1
    :completeness COMPLETENESS_TIER1
    nil))
