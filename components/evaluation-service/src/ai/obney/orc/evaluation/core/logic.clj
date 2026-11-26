(ns ai.obney.orc.evaluation.core.logic
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.evaluation.core.feedback :as feedback]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [cheshire.core :as json]
            [clj-uuid :as uuid]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u])
  (:import java.security.MessageDigest))

;; --- Scale ---

(defn discrete-scale
  "Build a first-class discrete `Scale` artifact.

   Args (map):
     :min   — lowest level (integer, e.g. 1)
     :max   — highest level (integer, e.g. 5)
     :bands — map of {level -> description-string}; one entry PER level in
              [min, max]. The description is what level N *means* — the
              anchor the judge reasons toward.

   Returns:
     {:kind :discrete :min <min> :max <max> :bands {...}}

   Throws ExceptionInfo when bounds are invalid or a level is missing a
   band description. The Scale intentionally carries NO :criteria,
   :instruction, or :prompt — those are supplied by the judge, decoupled
   from the scale."
  [{:keys [min max bands]}]
  (when-not (and (integer? min) (integer? max))
    (throw (ex-info "discrete-scale: :min and :max must be integers"
                    {:min min :max max})))
  (when-not (< min max)
    (throw (ex-info "discrete-scale: :min must be < :max"
                    {:min min :max max})))
  (let [levels (range min (inc max))
        missing (remove #(contains? bands %) levels)]
    (when (seq missing)
      (throw (ex-info "discrete-scale: every level needs a band description"
                      {:missing-levels (vec missing)
                       :levels (vec levels)
                       :provided (vec (keys bands))})))
    (let [blank (filterv #(let [d (get bands %)]
                            (not (and (string? d) (not (str/blank? d)))))
                         levels)]
      (when (seq blank)
        (throw (ex-info "discrete-scale: every band needs a non-blank description"
                        {:blank-levels blank}))))
    {:kind :discrete
     :min min
     :max max
     :bands (into (sorted-map) (select-keys bands levels))}))

(defn levels
  "The ordered seq of integer levels in this scale, low → high."
  [{:keys [min max]}]
  (range min (inc max)))

(defn coerce-level
  "Coerce a level to an integer. Accepts integers and numeric strings
   (some judge models return the level as a JSON string, or as a float like
   4.0). Returns nil for anything non-coercible."
  [level]
  (cond
    (integer? level) level
    (and (number? level) (== level (long level))) (long level)
    (and (string? level) (re-matches #"\s*-?\d+\s*" level)) (Long/parseLong (str/trim level))
    :else nil))

(defn level->unit-score
  "Map a discrete `level` to a continuous score in [0,1], deterministically:

       (level - min) / (max - min)

   For a 1-5 scale: 1→0.0, 2→0.25, 3→0.5, 4→0.75, 5→1.0.

   Coerces numeric-string levels (\"4\" → 4). Throws ExceptionInfo when the
   level is nil / non-numeric / out of [min, max] — this is part of the
   no-run-through guarantee: a garbage level is an error, never a silent 0."
  [{:keys [min max] :as _scale} level]
  (let [n (coerce-level level)]
    (when (nil? n)
      (throw (ex-info "level->unit-score: level is nil or non-numeric"
                      {:level level})))
    (when-not (<= min n max)
      (throw (ex-info "level->unit-score: level out of range"
                      {:level n :min min :max max})))
    (double (/ (- n min) (- max min)))))

(defn render-bands
  "Render the scale's per-level bands as plain human-readable text for the
   judge's instruction. This is band ANCHORING text, not an output format:
   per the structured-output rule we never put a JSON example or
   'return only JSON' in the prompt — the output shape is carried by the
   typed blackboard / ORC LLM output fields, not the prompt."
  [{:keys [bands] :as scale}]
  (->> (levels scale)
       (map (fn [lvl] (str "- " lvl ": " (get bands lvl))))
       (str/join "\n")))

(defn gate-banded-output
  "The 'no run-through' gate (structured-output rule, obney-ops craft).

   Takes the judge's structured output map (expected to carry at least a
   numeric `:level`, plus `:reasoning` / `:feedback`) and either:
     - throws ExceptionInfo when the output is nil/empty or missing a usable
       `:level` (a silent empty structured output is the documented
       failure mode of a prompt that grew past a permissive schema), or
     - returns the output enriched with a deterministic `:score` in [0,1]
       mapped from the level via `level->unit-score`.

   This guarantees a judge never silently scores 0 on an empty model
   response — it errors loudly so a structured-output regression is caught."
  [scale output]
  (when (or (nil? output) (and (map? output) (empty? output)))
    (throw (ex-info "gate-banded-output: empty judge output — no-run-through gate tripped (suspect a structured-output regression)"
                    {:output output})))
  (let [raw-level (:level output)
        level (coerce-level raw-level)]
    (when (nil? level)
      (throw (ex-info "gate-banded-output: judge output missing a usable :level/score — no-run-through gate tripped"
                      {:output output})))
    (assoc output
           :level level
           :score (level->unit-score scale level))))

;; --- Feedback ---

(defn ->score-with-feedback
  "Create a ScoreWithFeedback with validation.

   Args:
     score: Float between 0.0 and 1.0
     feedback: Actionable text explaining the evaluation
     dimensions: Optional vector of MetricDimension records

   Example:
     (->score-with-feedback
       0.75
       \"Good grounding but missing one key entity\"
       [{:name \"grounding\" :weight 0.6 :score 0.8 :feedback \"...\"}])"
  ([score feedback]
   (->score-with-feedback score feedback nil))
  ([score feedback dimensions]
   {:pre [(number? score)
          (<= 0.0 score 1.0)
          (string? feedback)]}
   (feedback/->ScoreWithFeedback (double score) feedback dimensions)))

(defn ->metric-dimension
  "Create a MetricDimension with validation.

   Args:
     name: Human-readable name of this dimension
     weight: Float between 0.0 and 1.0 (weights should sum to 1.0 across dimensions)
     score: Float between 0.0 and 1.0
     feedback: Feedback specific to this dimension

   Example:
     (->metric-dimension \"Source Grounding\" 0.35 0.8 \"Well grounded in sources\")"
  [name weight score feedback]
  {:pre [(string? name)
         (number? weight)
         (<= 0.0 weight 1.0)
         (number? score)
         (<= 0.0 score 1.0)
         (string? feedback)]}
  (feedback/->MetricDimension name (double weight) (double score) feedback))

(defn- score-indicator
  "Return a textual indicator for a score."
  [score]
  (cond
    (>= score 0.8) "GOOD"
    (>= score 0.5) "NEEDS IMPROVEMENT"
    :else "POOR"))

(defn combine-dimension-scores
  "Combine multiple weighted dimensions into a single ScoreWithFeedback.

   Calculates weighted average score and combines feedback, prioritizing
   low-scoring dimensions (they appear first in feedback).

   Args:
     dimensions: Vector of MetricDimension records or maps with
                 :name, :weight, :score, :feedback keys

   Returns:
     ScoreWithFeedback with weighted score and combined feedback

   Example:
     (combine-dimension-scores
       [{:name \"Grounding\" :weight 0.6 :score 0.9 :feedback \"Well grounded\"}
        {:name \"Completeness\" :weight 0.4 :score 0.5 :feedback \"Missing aspects\"}])
     ;; => ScoreWithFeedback(score=0.74, feedback='[Completeness - NEEDS IMPROVEMENT]...')"
  [dimensions]
  (if (empty? dimensions)
    (->score-with-feedback 0.0 "No dimensions to evaluate." [])
    (let [total-weight (reduce + (map :weight dimensions))
          _ (when (zero? total-weight)
              (throw (ex-info "Total weight of dimensions cannot be zero" {})))
          weighted-score (/ (reduce + (map #(* (:weight %) (:score %)) dimensions))
                           total-weight)
          sorted-dims (sort-by :score dimensions)
          feedback-parts (map (fn [{:keys [name score feedback]}]
                               (let [indicator (score-indicator score)]
                                 (format "[%s - %s]: %s" name indicator feedback)))
                             sorted-dims)
          combined-feedback (str/join "\n" feedback-parts)]
      (->score-with-feedback weighted-score combined-feedback dimensions))))

(def ^:const FEEDBACK_TEMPLATES
  "Common feedback templates for evaluation patterns.

   Use with format or string interpolation to generate actionable feedback."
  {:missing-entity
   "Missing key entity: %s. This information is critical for %s. Consider: %s"

   :hallucination
   "Response contains claim '%s' which is NOT found in any source. This is hallucination. Consider: only include facts from %s."

   :incomplete-coverage
   "Query has %d aspects but only %d were addressed. Missing: %s. Consider: ensure all parts of user query are covered."

   :wrong-action
   "Recommended %s but outcome indicates %s was needed. Signal missed: %s. Consider: %s"

   :instruction-not-followed
   "Instruction specified: '%s'. Response did not follow this. Specifically: %s. Consider: %s"

   :reasoning-unclear
   "Reasoning is unclear or inconsistent. Issue: %s. Consider: provide step-by-step reasoning that clearly connects premises to conclusions."

   :sarcasm-missed
   "Sarcasm detection failure. The phrase '%s' uses positive words ironically. Sarcasm patterns present: %s. Consider: Add specific sarcasm detection to the instruction."

   :score-miscalibrated
   "Score of %s is %s for the actual quality. Evidence: %s. Consider: %s"})

(defn render-feedback
  "Render a feedback template with the given arguments.

   Args:
     template-key: Keyword key from FEEDBACK_TEMPLATES
     & args: Arguments to format into the template

   Example:
     (render-feedback :hallucination
                      \"classes are free\"
                      \"the provided FAQ documents\")"
  [template-key & args]
  (if-let [template (get FEEDBACK_TEMPLATES template-key)]
    (apply format template args)
    (str "Unknown feedback template: " template-key)))

(defn aggregate-feedback-summary
  "Create a concise summary of multiple dimension feedbacks.

   Args:
     score-with-feedback: A ScoreWithFeedback record

   Returns:
     A condensed summary string suitable for quick review"
  [{:keys [score feedback dimensions]}]
  (let [poor-dims (filter #(< (:score %) 0.5) dimensions)
        needs-work-dims (filter #(and (>= (:score %) 0.5) (< (:score %) 0.8)) dimensions)]
    (cond
      (>= score 0.9)
      (format "Excellent (%.0f%%): All dimensions performing well." (* 100 score))

      (>= score 0.7)
      (format "Good (%.0f%%): %d dimension(s) need improvement: %s"
              (* 100 score)
              (count needs-work-dims)
              (str/join ", " (map :name needs-work-dims)))

      :else
      (format "Poor (%.0f%%): %d dimension(s) failing: %s. Action needed."
              (* 100 score)
              (count poor-dims)
              (str/join ", " (map :name poor-dims))))))

;; --- Rubrics ---

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
  (discrete-scale
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

(def GROUNDING_TIER1
  "The redesigned grounding judge's decoupled pieces, bundled for the judge fn
   to compose at call time. The Scale is first-class and separable."
  {:name "Source Grounding"
   :weight 0.35
   :criteria GROUNDING_CRITERIA
   :stance GROUNDING_STANCE
   :scale GROUNDING_SCALE})

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
  (discrete-scale
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
  (discrete-scale
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
  (discrete-scale
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

;; --- Heuristic structural ---

(defn- contains-node?
  "Walk the tree and check whether any node has `tag` as its first element."
  [tree tag]
  (boolean
    (some (fn [node]
            (and (vector? node) (= tag (first node))))
          (tree-seq #(and (sequential? %) (not (map? %))) seq tree))))

(defn- has-sequence-root? [tree]
  (and (vector? tree) (= :sequence (first tree))))

(defn- has-llm? [tree] (contains-node? tree :llm))

(defn- has-final? [tree] (contains-node? tree :final))

(defn- has-map-each? [tree] (contains-node? tree :map-each))

(defn evaluate-tree-structure
  "Evaluate the structural shape of an RLM-generated behavior tree DSL.

   `raw-dsl` is the verbatim S-expression form (vector-of-vectors) the
   model produced via emit-tree!. Returns {:score :feedback :dimensions}
   shaped consistent with the unified judge protocol.

   Pure — no LLM call, no event store access, no global state."
  [raw-dsl]
  (let [tree raw-dsl
        seq? (has-sequence-root? tree)
        llm? (has-llm? tree)
        final? (has-final? tree)
        map-each? (has-map-each? tree)
        structure-score (cond
                          (and seq? final?) 1.0
                          seq? 0.7
                          :else 0.3)
        decomposition-score (cond
                              map-each? 1.0
                              llm? 0.6
                              :else 0.2)
        overall-score (* 0.5 (+ (* 0.6 structure-score)
                                (* 0.4 decomposition-score)))
        feedback (cond
                   (and seq? map-each? final?)
                   "Excellent tree structure with proper decomposition pattern."
                   (and seq? llm? final?)
                   "Good tree structure but could use map-each for better decomposition."
                   seq?
                   "Basic tree structure. Consider adding sub-LLM calls for analysis."
                   :else
                   "Tree structure needs improvement. Use :sequence as root.")]
    {:score overall-score
     :feedback feedback
     :dimensions [{:name "Structure"
                   :weight 0.6
                   :score structure-score
                   :feedback (if seq? "Valid sequence root" "Missing sequence root")}
                  {:name "Decomposition"
                   :weight 0.4
                   :score decomposition-score
                   :feedback (if map-each?
                               "Uses map-each for parallelism"
                               "Could benefit from map-each")}]}))

;; --- Judges ---

(def judges-GroundingResultSchema
  "Malli schema for grounding judge output. PA-3: :score is now derived from
   the discrete :level via the Scale (1-5 → [0,1]); :level and :reasoning are
   the richer tier-1 fields (reason-before-score)."
  [:map
   [:score :double]
   [:level {:optional true} [:enum 1 2 3 4 5]]
   [:reasoning {:optional true} :string]
   [:grounded-claims [:vector :string]]
   [:ungrounded-claims [:vector :string]]
   [:feedback :string]])

(def InstructionFollowingResultSchema
  "Malli schema for instruction following judge output. PA-4: :score is now
   derived from the discrete :level via the Scale (1-5 -> [0,1]); :level and
   :reasoning are the richer tier-1 fields (reason-before-score)."
  [:map
   [:score :double]
   [:level {:optional true} [:enum 1 2 3 4 5]]
   [:reasoning {:optional true} :string]
   [:requirements-met [:vector :string]]
   [:requirements-missed [:vector :string]]
   [:feedback :string]])

(def judges-ReasoningResultSchema
  "Malli schema for reasoning quality judge output. PA-4: tier-1 shape (see
   InstructionFollowingResultSchema)."
  [:map
   [:score :double]
   [:level {:optional true} [:enum 1 2 3 4 5]]
   [:reasoning {:optional true} :string]
   [:reasoning-strengths [:vector :string]]
   [:reasoning-weaknesses [:vector :string]]
   [:feedback :string]])

(def judges-CompletenessResultSchema
  "Malli schema for completeness judge output. PA-4: tier-1 shape (see
   InstructionFollowingResultSchema)."
  [:map
   [:score :double]
   [:level {:optional true} [:enum 1 2 3 4 5]]
   [:reasoning {:optional true} :string]
   [:aspects-covered [:vector :string]]
   [:aspects-missing [:vector :string]]
   [:feedback :string]])

(defn render-rubric-prompt
  "Render a rubric prompt with trace data.

   Args:
     rubric: A rubric map with :prompt template
     trace-data: Map with :inputs, :outputs (or :response), :instruction

   Returns:
     Rendered prompt string"
  [rubric trace-data]
  (let [inputs-json (json/generate-string (or (:inputs trace-data) {}) {:pretty true})
        response (cond
                   (:response trace-data) (:response trace-data)
                   (string? (:outputs trace-data)) (:outputs trace-data)
                   (:outputs trace-data) (json/generate-string (:outputs trace-data) {:pretty true})
                   :else "{}")
        instruction (or (:instruction trace-data) "No instruction provided")]
    (-> (:prompt rubric)
        (str/replace "{inputs}" inputs-json)
        (str/replace "{response}" (str response))
        (str/replace "{instruction}" instruction))))

(defn- build-judge-module
  "Build a ORC LLM module for a judge evaluation.

   Args:
     prompt: The rendered rubric prompt
     output-fields: Vector of output field definitions

   Returns:
     ORC LLM module map"
  [prompt output-fields]
  {:inputs [{:name :evaluation_request
             :spec :string
             :description "The evaluation request with context and scoring criteria"}]
   :outputs output-fields
   :instructions prompt})

(defn grounding-output-fields
  "PA-3 tier-1 grounding output fields, ordered to FORCE reason-before-score:
   the model fills :reasoning and the claim lists BEFORE it commits to a
   :level band. Field order in the ORC LLM tool schema is the generation
   order. There is NO soft :score field — the score is derived
   deterministically from the discrete :level via the Scale (1-5 → [0,1]),
   never self-reported by the model. Output shape is carried entirely by
   these typed fields (the typed blackboard) — never by json-in-prompt and
   never by a permissive :output-schemas override."
  []
  [{:name :reasoning
    :spec :string
    :description (str "Your adversarial, source-grounded analysis. Hunt for "
                     "claims the source does NOT support. Write this BEFORE "
                     "choosing a level.")}
   {:name :grounded-claims
    :spec [:vector :string]
    :description "Substantive claims in the response that ARE supported by the source."}
   {:name :ungrounded-claims
    :spec [:vector :string]
    :description (str "Substantive claims NOT supported by the source "
                     "(fabrications, unsupported numbers/names/dates, or "
                     "inferences stated as fact). Empty if none.")}
   {:name :level
    :spec [:and :int [:>= 1] [:<= 5]]
    :description "The grounding band (1-5) chosen AFTER the reasoning, per the bands defined in the instruction."}
   {:name :feedback
    :spec :string
    :description "Specific, actionable feedback the producer can act on to improve grounding."}])

(defn build-grounding-instruction
  "Compose the grounding judge's instruction from the DECOUPLED pieces:
   stance (how to behave) + criteria (what to evaluate) + the Scale's bands
   (how to score). Kept short and field-name-oriented per the structured-
   output rule: it tells the model WHAT to do and names the output fields,
   but contains NO JSON example and NO 'return only JSON' directive — the
   output shape is the typed blackboard's job, not the prompt's.

   The actual source + response + producer-instruction are passed as typed
   INPUT fields (see build-grounding-module), not interpolated here, so the
   instruction stays a stable, decoupled template."
  [{:keys [criteria stance scale]}]
  (str stance "\n\n"
       "WHAT TO EVALUATE:\n" criteria "\n\n"
       "You are given three inputs: `source` (the context the producer had), "
       "`response` (the producer's declared output fields, one value per "
       "field, as a JSON object — the field set is fixed by the workflow's "
       "typed blackboard, not chosen by the producer; judge the values, not "
       "the object shape), and `producer_instruction` (the task the producer "
       "was given). Compare the response against the source ONLY.\n\n"
       "SCORING BANDS (choose exactly one level for the `level` field):\n"
       (render-bands scale) "\n\n"
       "Fill `reasoning` first (adversarial analysis grounded in the source), "
       "then `grounded-claims` and `ungrounded-claims`, then choose `level`, "
       "then write `feedback`."))

(defn coerce-source-string
  "Render the producer's inputs/source into a single string for the judge's
   typed `source` input field. Maps/collections are pretty-printed so the
   judge sees structured context; strings pass through."
  [v]
  (cond
    (nil? v) ""
    (string? v) v
    :else (json/generate-string v {:pretty true})))

(defn- build-grounding-module
  "ORC LLM module for the tier-1 grounding judge. Typed INPUT fields carry the
   trace data; typed OUTPUT fields carry the verdict. No json-in-prompt, no
   permissive output schema."
  [instruction]
  {:inputs [{:name :source
             :spec :string
             :description "The source context the producer was given (ground truth to check against)."}
            {:name :response
             :spec :string
             :description (str "The producer's declared output fields, one value per "
                               "field, as a JSON object. The field set is fixed by the "
                               "workflow's typed blackboard, not chosen by the producer. "
                               "Judge the values, not the object shape.")}
            {:name :producer_instruction
             :spec :string
             :description "The instruction the producer was given (for context only; do not grade against it)."}]
   :outputs (grounding-output-fields)
   :instructions instruction})

(defn instruction-following-output-fields []
  [{:name :reasoning
    :spec :string
    :description (str "Your adversarial compliance audit: enumerate the "
                      "instruction's explicit requirements and prohibitions, "
                      "then check each against the response. Write this BEFORE "
                      "choosing a level.")}
   {:name :requirements-met
    :spec [:vector :string]
    :description "Explicit instruction requirements that WERE satisfied."}
   {:name :requirements-missed
    :spec [:vector :string]
    :description (str "Explicit instruction requirements that were NOT "
                      "satisfied, or prohibitions that were violated. Empty if "
                      "none.")}
   {:name :level
    :spec [:and :int [:>= 1] [:<= 5]]
    :description "The instruction-following band (1-5) chosen AFTER the reasoning, per the bands defined in the instruction."}
   {:name :feedback
    :spec :string
    :description "Specific, actionable feedback the producer can act on to better follow the instruction."}])

(defn reasoning-output-fields []
  [{:name :reasoning
    :spec :string
    :description (str "Your adversarial logical analysis: trace the inference "
                      "chain from premises to conclusion and attack the "
                      "weakest link. Write this BEFORE choosing a level.")}
   {:name :reasoning-strengths
    :spec [:vector :string]
    :description "Aspects of the inference chain that are sound."}
   {:name :reasoning-weaknesses
    :spec [:vector :string]
    :description (str "Logical gaps, unstated assumptions, non-sequiturs, or "
                      "overreaching conclusions. Empty if none.")}
   {:name :level
    :spec [:and :int [:>= 1] [:<= 5]]
    :description "The reasoning-quality band (1-5) chosen AFTER the reasoning, per the bands defined in the instruction."}
   {:name :feedback
    :spec :string
    :description "Specific, actionable feedback the producer can act on to improve reasoning rigor."}])

(defn completeness-output-fields []
  [{:name :reasoning
    :spec :string
    :description (str "Your adversarial coverage audit: enumerate the distinct "
                      "aspects the task required, then check each against the "
                      "response for presence and sufficient detail. Write this "
                      "BEFORE choosing a level.")}
   {:name :aspects-covered
    :spec [:vector :string]
    :description "Required aspects of the task that WERE addressed with sufficient detail."}
   {:name :aspects-missing
    :spec [:vector :string]
    :description (str "Required aspects that are missing or answered only as "
                      "thin stubs. Empty if none.")}
   {:name :level
    :spec [:and :int [:>= 1] [:<= 5]]
    :description "The completeness band (1-5) chosen AFTER the reasoning, per the bands defined in the instruction."}
   {:name :feedback
    :spec :string
    :description "Specific, actionable feedback the producer can act on to improve completeness."}])

(defn build-tier1-instruction
  "Compose a tier-1 judge's instruction from the DECOUPLED pieces:
   stance (how to behave) + criteria (what to evaluate) + the Scale's bands
   (how to score). Generic over the instruction-following / reasoning /
   completeness dimensions (grounding keeps its own build-grounding-instruction
   because it grades against the source ONLY, not the producer instruction).

   Kept short and field-name-oriented per the structured-output rule: it tells
   the model WHAT to do and names the output fields, but contains NO JSON
   example and NO 'return only JSON' directive — the output shape is the typed
   blackboard's job, not the prompt's. The trace data is passed as typed INPUT
   fields (see build-tier1-module), not interpolated here."
  ([rubric]
   (build-tier1-instruction rubric false))
  ([{:keys [criteria stance scale]} iteration-evidence?]
   (str stance "\n\n"
        "WHAT TO EVALUATE:\n" criteria "\n\n"
        "You are given three inputs: `instruction` (the task the producer was "
        "given), `response` (the producer's declared output fields, one value "
        "per field, as a JSON object — the field set is fixed by the "
        "workflow's typed blackboard, not chosen by the producer; judge the "
        "values, not the object shape), and `inputs` (the context/material "
        "the producer had). Evaluate the response against the instruction "
        "(and the inputs where relevant)."
        (when iteration-evidence?
          (str " You are also given `iteration_evidence`, a bounded durable "
               "record of the research attempts. Use it to explain why the "
               "outcome occurred."))
        "\n\n"
        "SCORING BANDS (choose exactly one level for the `level` field):\n"
        (render-bands scale) "\n\n"
        "Fill `reasoning` first (adversarial analysis), then the evidence "
        "lists, then choose `level`, then write `feedback`.")))

(defn- build-tier1-module
  "ORC LLM module for a tier-1 (instruction/reasoning/completeness) judge. Typed
   INPUT fields carry the trace data; typed OUTPUT fields carry the verdict. No
   json-in-prompt, no permissive output schema."
  [instruction output-fields iteration-evidence?]
  {:inputs (cond-> [{:name :instruction
                     :spec :string
                     :description "The instruction the producer was given (the task to evaluate compliance/coverage against)."}
                    {:name :response
                     :spec :string
                     :description (str "The producer's declared output fields, one value per "
                                       "field, as a JSON object. The field set is fixed by the "
                                       "workflow's typed blackboard, not chosen by the producer. "
                                       "Judge the values, not the object shape.")}
                    {:name :inputs
                     :spec :string
                     :description "The context/material the producer had (for relevance checks)."}]
             iteration-evidence?
             (conj {:name :iteration_evidence
                    :spec :string
                    :description "Bounded durable evidence for the research attempts that produced the outcome."}))
   :outputs output-fields
   :instructions instruction})

(defn call-llm-judge
  "Call an LLM to evaluate using the given prompt and parse the response.

   Args:
     prompt: The rendered rubric prompt
     output-fields: Vector of ORC LLM output field definitions
     options: Optional map with :provider, :model

   Returns:
     Map with parsed output fields (e.g., {:score 0.8 :feedback \"...\" ...})"
  [prompt output-fields & {:keys [provider model] :or {provider judges/*judge-provider*
                                                        model judges/*judge-model*}}]
  (let [module (build-judge-module prompt output-fields)
        inputs {:evaluation_request "Please evaluate according to the rubric above."}
        result (llm/predict provider module inputs
                               {:with-metadata? true
                                :validate? false
                                :model model})]
    (assoc (or (:outputs result) result)
           :model-provenance {:provider provider
                              :model (or (:model result) model)
                              :usage (:usage result)})))

(defn compose-task
  "RR-33: compose the task string a judge is told, in priority order: the
   node's own instruction when non-blank; else the judge's declared
   :criteria when non-blank; else a synthesized task naming the node's
   declared write keys (never blank when writes are known — a code node's
   task is never the empty string just because the DSL's `code` takes no
   instruction); else the existing 'No instruction provided' sentinel,
   reached only when nothing at all is known about the task. Same rule for
   both call-grounding-judge-llm and call-tier1-judge-llm."
  [trace-data criteria]
  (let [instruction (:instruction trace-data)
        write-keys (:write-keys trace-data)]
    (cond
      (and (string? instruction) (not (str/blank? instruction)))
      instruction

      (and (string? criteria) (not (str/blank? criteria)))
      criteria

      (seq write-keys)
      (str "Produce the declared output fields: "
           (str/join ", " (map name write-keys)))

      :else
      "No instruction provided")))

(defn call-grounding-judge-llm
  "PA-3 tier-1 grounding LLM call. Sends the trace data as typed INPUT fields
   (source / response / producer_instruction) and the decoupled instruction.
   Returns the raw parsed output map (with :reasoning, :grounded-claims,
   :ungrounded-claims, :level, :feedback) — gating + level→score mapping is
   the caller's job (grounding-judge), so the no-run-through gate sees the
   raw model output.

   RR-31: `:criteria`, when a non-blank string, OVERRIDES the rubric's
   built-in criteria before the instruction is composed — the workflow's
   declared \"what to evaluate\" for this judge. Absent :criteria leaves the
   rubric untouched: the instruction is byte-identical to today's."
  [trace-data & {:keys [provider model criteria] :or {provider judges/*judge-provider*
                                                       model judges/*judge-model*}}]
  (let [rubric (cond-> (get-tier1-rubric :grounding)
                 (and (string? criteria) (not (str/blank? criteria)))
                 (assoc :criteria criteria))
        instruction (build-grounding-instruction rubric)
        module (build-grounding-module instruction)
        response (cond
                   (:response trace-data) (:response trace-data)
                   (string? (:outputs trace-data)) (:outputs trace-data)
                   (:outputs trace-data) (json/generate-string (:outputs trace-data) {:pretty true})
                   :else "")
        inputs {:source (coerce-source-string (:inputs trace-data))
                :response (str response)
                :producer_instruction (compose-task trace-data criteria)}
        result (llm/predict provider module inputs
                               {:with-metadata? true
                                :validate? false
                                :model model})]
    (assoc (or (:outputs result) result)
           :model-provenance {:provider provider
                              :model (or (:model result) model)
                              :usage (:usage result)})))

(defn call-tier1-judge-llm
  "PA-4 tier-1 LLM call for the instruction-following / reasoning / completeness
   judges. Mirrors call-grounding-judge-llm: sends the trace data as typed
   INPUT fields (instruction / response / inputs) and the decoupled instruction
   composed from the rubric-key's criteria + stance + Scale bands. Returns the
   raw parsed output map (with :reasoning, the dimension's evidence lists,
   :level, :feedback) — gating + level->score mapping is the caller's job, so
   the no-run-through gate sees the raw model output.

   `output-fields` is the dimension's reason-before-score field vector.

   RR-31: `:criteria`, when a non-blank string, OVERRIDES the rubric's
   built-in criteria before the instruction is composed — the workflow's
   declared \"what to evaluate\" for this judge. Absent :criteria leaves the
   rubric untouched: the instruction is byte-identical to today's."
  [rubric-key output-fields trace-data & {:keys [provider model criteria]
                                          :or {provider judges/*judge-provider*
                                               model judges/*judge-model*}}]
  (let [rubric (cond-> (get-tier1-rubric rubric-key)
                 (and (string? criteria) (not (str/blank? criteria)))
                 (assoc :criteria criteria))
        iteration-evidence (not-empty (:researcher-iterations trace-data))
        instruction (build-tier1-instruction rubric (boolean iteration-evidence))
        module (build-tier1-module instruction output-fields
                                   (boolean iteration-evidence))
        response (cond
                   (:response trace-data) (:response trace-data)
                   (string? (:outputs trace-data)) (:outputs trace-data)
                   (:outputs trace-data) (json/generate-string (:outputs trace-data) {:pretty true})
                   :else "")
        inputs (cond->
                {:instruction (compose-task trace-data criteria)
                 :response (str response)
                 :inputs (coerce-source-string (:inputs trace-data))}
                 iteration-evidence
                 (assoc :iteration_evidence
                        (coerce-source-string iteration-evidence)))
        result (llm/predict provider module inputs
                               {:with-metadata? true
                                :validate? false
                                :model model})]
    (assoc (or (:outputs result) result)
           :model-provenance {:provider provider
                              :model (or (:model result) model)
                              :usage (:usage result)})))

(defn- mock-grounding-result []
  "Mock for the tier-1 grounding shape: a discrete :level + :reasoning, NOT a
   self-reported :score. The judge fn maps :level → :score via the Scale, so
   the mock deliberately omits :score to exercise that path."
  {:level 4
   :reasoning "Mock adversarial review: claims appear traceable to the source; minor extrapolation."
   :grounded-claims ["Mock: Response uses information from provided context"]
   :ungrounded-claims []
   :feedback "Mock evaluation. In production, this analyzes actual grounding against the source."})

(defn- mock-instruction-following-result []
  {:level 4
   :reasoning "Mock compliance audit: main task and required components satisfied; one minor imprecision."
   :requirements-met ["Mock: main task addressed"]
   :requirements-missed []
   :feedback "Mock evaluation. In production, this audits compliance with each instruction directive."})

(defn- mock-reasoning-result []
  {:level 4
   :reasoning "Mock logical analysis: chain is sound with one minor unstated-but-obvious assumption."
   :reasoning-strengths ["Mock: clear logical structure"]
   :reasoning-weaknesses []
   :feedback "Mock evaluation. In production, this attacks the weakest link in the inference chain."})

(defn- mock-completeness-result []
  {:level 4
   :reasoning "Mock coverage audit: every required aspect addressed; one non-essential elaboration omitted."
   :aspects-covered ["Mock: main aspects addressed"]
   :aspects-missing []
   :feedback "Mock evaluation. In production, this audits coverage of every required aspect."})

(defn grounding-judge
  "PA-3 tier-1 grounding judge: adversarial, source-grounded, reason-before-
   score, on a decoupled discrete 1-5 Scale mapped deterministically to
   [0,1]. A judge is one capability with two deployment modes (event-
   subscribed and in-pipeline gate); this fn is the shared internal scoring.

   This is a code executor function for use in sheet/code nodes.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     grounding-result: Map carrying the BACK-COMPATIBLE shape
       {:score (double 0-1) :grounded-claims :ungrounded-claims :feedback}
       PLUS the richer tier-1 fields {:level :reasoning}. :score is derived
       from the discrete :level via the Scale, never self-reported.

   The no-run-through gate (scale/gate-banded-output) THROWS on empty/garbage
   model output — a judge never silently scores 0 on a structured-output
   regression."
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        criteria (:criteria inputs)
        the-scale (:scale (get-tier1-rubric :grounding))
        raw (if judges/*use-mock-llm*
              (mock-grounding-result)
              (call-grounding-judge-llm trace-data :criteria criteria))
        gated (gate-banded-output the-scale raw)]
    {:grounding-result
     {:score (:score gated)
      :level (:level gated)
      :grounded-claims (vec (:grounded-claims gated))
      :ungrounded-claims (vec (:ungrounded-claims gated))
      :reasoning (or (:reasoning gated) "")
      :feedback (or (:feedback gated) "")
      :model-provenance (:model-provenance raw)}}))

(defn instruction-following-judge
  "PA-4 tier-1 instruction-following judge: adversarial compliance auditor,
   reason-before-score, on a decoupled discrete 1-5 Scale mapped
   deterministically to [0,1]. Mirrors grounding-judge exactly.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     instruction-result: BACK-COMPATIBLE shape
       {:score (double 0-1) :requirements-met :requirements-missed :feedback}
       PLUS the richer tier-1 fields {:level :reasoning}. :score is derived
       from the discrete :level via the Scale, never self-reported.

   The no-run-through gate (scale/gate-banded-output) THROWS on empty/garbage
   model output."
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        criteria (:criteria inputs)
        the-scale (:scale (get-tier1-rubric :instruction-following))
        raw (if judges/*use-mock-llm*
              (mock-instruction-following-result)
              (call-tier1-judge-llm :instruction-following
                                    (instruction-following-output-fields)
                                    trace-data
                                    :criteria criteria))
        gated (gate-banded-output the-scale raw)]
    {:instruction-result
     {:score (:score gated)
      :level (:level gated)
      :requirements-met (vec (:requirements-met gated))
      :requirements-missed (vec (:requirements-missed gated))
      :reasoning (or (:reasoning gated) "")
      :feedback (or (:feedback gated) "")
      :model-provenance (:model-provenance raw)}}))

(defn reasoning-judge
  "PA-4 tier-1 reasoning-quality judge: adversarial logician, reason-before-
   score, on a decoupled discrete 1-5 Scale mapped deterministically to [0,1].
   Mirrors grounding-judge exactly.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     reasoning-result: BACK-COMPATIBLE shape {:score :reasoning-strengths
       :reasoning-weaknesses :feedback} PLUS {:level :reasoning}."
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        criteria (:criteria inputs)
        the-scale (:scale (get-tier1-rubric :reasoning))
        raw (if judges/*use-mock-llm*
              (mock-reasoning-result)
              (call-tier1-judge-llm :reasoning
                                    (reasoning-output-fields)
                                    trace-data
                                    :criteria criteria))
        gated (gate-banded-output the-scale raw)]
    {:reasoning-result
     {:score (:score gated)
      :level (:level gated)
      :reasoning-strengths (vec (:reasoning-strengths gated))
      :reasoning-weaknesses (vec (:reasoning-weaknesses gated))
      :reasoning (or (:reasoning gated) "")
      :feedback (or (:feedback gated) "")
      :model-provenance (:model-provenance raw)}}))

(defn completeness-judge
  "PA-4 tier-1 completeness judge: adversarial coverage auditor, reason-before-
   score, on a decoupled discrete 1-5 Scale mapped deterministically to [0,1].
   Mirrors grounding-judge exactly.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     completeness-result: BACK-COMPATIBLE shape {:score :aspects-covered
       :aspects-missing :feedback} PLUS {:level :reasoning}."
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        criteria (:criteria inputs)
        the-scale (:scale (get-tier1-rubric :completeness))
        raw (if judges/*use-mock-llm*
              (mock-completeness-result)
              (call-tier1-judge-llm :completeness
                                    (completeness-output-fields)
                                    trace-data
                                    :criteria criteria))
        gated (gate-banded-output the-scale raw)]
    {:completeness-result
     {:score (:score gated)
      :level (:level gated)
      :aspects-covered (vec (:aspects-covered gated))
      :aspects-missing (vec (:aspects-missing gated))
      :reasoning (or (:reasoning gated) "")
      :feedback (or (:feedback gated) "")
      :model-provenance (:model-provenance raw)}}))

(def default-judge-dimension-names
  "The one vocabulary for the four default LLM judges' dimension names. Each
   is the judge's tier-1 rubric name, and each is a name the ontology
   classifier maps to a failure concept (its dictionary is case-sensitive).
   Used by the synchronous aggregate below and by the live runtime's
   per-judge dimension projection, so the two paths cannot drift apart."
  {:grounding             (:name GROUNDING_TIER1)
   :instruction-following (:name INSTRUCTION_FOLLOWING_TIER1)
   :reasoning             (:name REASONING_TIER1)
   :completeness          (:name COMPLETENESS_TIER1)})

(defn aggregate-dimensions
  "Aggregate multiple dimension results into a single score.

   Input keys:
     grounding-result: Result from grounding judge
     instruction-result: Result from instruction following judge
     reasoning-result: Result from reasoning judge
     completeness-result: Result from completeness judge

   Output keys:
     aggregate-score: Float between 0.0 and 1.0
     feedback-summary: Combined feedback string
     dimensions: Vector of dimension details"
  [{:keys [inputs] :as _executor-context}]
  (let [
        candidates [[:grounding :grounding-result 0.35]
                    [:instruction-following :instruction-result 0.25]
                    [:reasoning :reasoning-result 0.20]
                    [:completeness :completeness-result 0.20]]
        dimensions (vec
                    (for [[judge-key result-key weight] candidates
                          :let [result (get inputs result-key)]
                          :when (some? result)]
                      (do
                        (when-not (number? (:score result))
                          (throw (ex-info "aggregate-dimensions: a judge result without a numeric :score cannot be aggregated"
                                          {:judge judge-key :result result})))
                        (->metric-dimension
                         (default-judge-dimension-names judge-key) weight
                         (:score result)
                         (or (:feedback result) "")))))
        _ (when (empty? dimensions)
            (throw (ex-info "aggregate-dimensions: no judge result was supplied; nothing to aggregate"
                            {:supplied-keys (vec (keys inputs))})))
        result (combine-dimension-scores dimensions)]

    {:aggregate-score (:score result)
     :feedback-summary (aggregate-feedback-summary result)
     :dimensions (mapv #(select-keys % [:name :weight :score :feedback])
                       dimensions)}))

(defn evaluate-trace
  "Evaluate ONE trace on all four dimensions and aggregate: the per-item body
   of the batch evaluation suite, as a single primitive code leaf with explicit
   writes (a parallel map-each collects each iteration's own writes, so a
   composite whose writes the collection depended on could be misattributed).

   Input keys:
     trace-data: Map with :inputs, :response, :instruction

   Output keys:
     current-aggregate: the aggregate result of this trace's four judges"
  [{:keys [inputs] :as _executor-context}]
  (let [ctx {:inputs {:trace-data (:trace-data inputs)}}
        run (fn [judge-fn result-key] (future (get (judge-fn ctx) result-key)))
        grounding (run grounding-judge :grounding-result)
        instruction (run instruction-following-judge :instruction-result)
        reasoning (run reasoning-judge :reasoning-result)
        completeness (run completeness-judge :completeness-result)]
    {:current-aggregate
     (aggregate-dimensions
      {:inputs {:grounding-result @grounding
                :instruction-result @instruction
                :reasoning-result @reasoning
                :completeness-result @completeness}})}))

(defn evaluate-single
  "Evaluate a trace with a single judge.

   Convenience wrapper that formats inputs correctly.

   Args:
     judge-key: One of :grounding, :instruction-following, :reasoning, :completeness
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Returns:
     The judge result map"
  [judge-key trace-data]
  (let [judge-fn (case judge-key
                   :grounding grounding-judge
                   :instruction-following instruction-following-judge
                   :reasoning reasoning-judge
                   :completeness completeness-judge)]
    (judge-fn {:inputs {:trace-data trace-data}})))

(def judge-registry
  "Registry of available judge executors."
  {:grounding #'grounding-judge
   :instruction-following #'instruction-following-judge
   :reasoning #'reasoning-judge
   :completeness #'completeness-judge
   :aggregate #'aggregate-dimensions})

(defn get-judge
  "Get a judge executor by key."
  [key]
  (get judge-registry key))

;; --- Sheets ---

(def ^:private trace-input-value-schema
  [:schema
   {:registry
    {:ai.obney.orc.evaluation.core.sheets/trace-input
     [:or :nil :string :int :double :boolean :keyword
      [:vector [:ref :ai.obney.orc.evaluation.core.sheets/trace-input]]
      [:map-of [:or :keyword :string] [:ref :ai.obney.orc.evaluation.core.sheets/trace-input]]]}}
   [:ref :ai.obney.orc.evaluation.core.sheets/trace-input]])

(def TraceDataSchema
  "Schema for input trace data"
  [:map
   [:inputs [:map-of :string trace-input-value-schema]]
   [:response :string]
   [:instruction :string]])

(def JudgeResultSchema
  "Schema for a single judge result"
  [:map
   [:score :double]
   [:feedback :string]])

(def sheets-GroundingResultSchema
  "Schema for grounding judge result"
  [:map
   [:score :double]
   [:grounded-claims [:vector :string]]
   [:ungrounded-claims [:vector :string]]
   [:feedback :string]])

(def InstructionResultSchema
  "Schema for instruction following judge result"
  [:map
   [:score :double]
   [:requirements-met [:vector :string]]
   [:requirements-missed [:vector :string]]
   [:feedback :string]])

(def sheets-ReasoningResultSchema
  "Schema for reasoning quality judge result"
  [:map
   [:score :double]
   [:reasoning-strengths [:vector :string]]
   [:reasoning-weaknesses [:vector :string]]
   [:feedback :string]])

(def sheets-CompletenessResultSchema
  "Schema for completeness judge result"
  [:map
   [:score :double]
   [:aspects-covered [:vector :string]]
   [:aspects-missing [:vector :string]]
   [:feedback :string]])

(def DimensionSchema
  "Schema for a scored dimension"
  [:map
   [:name :string]
   [:weight :double]
   [:score :double]
   [:feedback :string]])

(def AggregateResultSchema
  "Schema for aggregated evaluation result"
  [:map
   [:aggregate-score :double]
   [:feedback-summary :string]
   [:dimensions [:vector DimensionSchema]]])

(defn grounding-judge-sheet
  "Sheet that evaluates source grounding / hallucination detection.

   Input: trace-data (TraceDataSchema)
   Output: grounding-result (GroundingResultSchema)"
  []
  (orc/workflow "evaluation-grounding-judge"
    (orc/blackboard
     {:trace-data TraceDataSchema
      :grounding-result sheets-GroundingResultSchema})

    (orc/code "evaluate-grounding"
      :fn "ai.obney.orc.evaluation.core.judges/grounding-judge"
      :reads [:trace-data]
      :writes [:grounding-result])))

(defn instruction-judge-sheet
  "Sheet that evaluates instruction following.

   Input: trace-data (TraceDataSchema)
   Output: instruction-result (InstructionResultSchema)"
  []
  (orc/workflow "evaluation-instruction-judge"
    (orc/blackboard
     {:trace-data TraceDataSchema
      :instruction-result InstructionResultSchema})

    (orc/code "evaluate-instruction"
      :fn "ai.obney.orc.evaluation.core.judges/instruction-following-judge"
      :reads [:trace-data]
      :writes [:instruction-result])))

(defn reasoning-judge-sheet
  "Sheet that evaluates reasoning quality.

   Input: trace-data (TraceDataSchema)
   Output: reasoning-result (ReasoningResultSchema)"
  []
  (orc/workflow "evaluation-reasoning-judge"
    (orc/blackboard
     {:trace-data TraceDataSchema
      :reasoning-result sheets-ReasoningResultSchema})

    (orc/code "evaluate-reasoning"
      :fn "ai.obney.orc.evaluation.core.judges/reasoning-judge"
      :reads [:trace-data]
      :writes [:reasoning-result])))

(defn completeness-judge-sheet
  "Sheet that evaluates response completeness.

   Input: trace-data (TraceDataSchema)
   Output: completeness-result (CompletenessResultSchema)"
  []
  (orc/workflow "evaluation-completeness-judge"
    (orc/blackboard
     {:trace-data TraceDataSchema
      :completeness-result sheets-CompletenessResultSchema})

    (orc/code "evaluate-completeness"
      :fn "ai.obney.orc.evaluation.core.judges/completeness-judge"
      :reads [:trace-data]
      :writes [:completeness-result])))

(defn evaluation-suite
  "Complete evaluation suite that runs all judges in parallel and aggregates.

   This is the main evaluation workflow. It takes trace data, runs all four
   dimension judges in parallel for efficiency, then aggregates into a single
   score with combined feedback.

   Input:
     trace-data: Map with :inputs, :response, :instruction

   Output:
     aggregate-result: Map with :aggregate-score, :feedback-summary, :dimensions

   Example:
     (def suite-id (sheet/build-workflow! ctx (evaluation-suite)))
     (sheet/execute ctx suite-id
       {:trace-data {:inputs {:question \"What is 2+2?\"}
                     :response \"4\"
                     :instruction \"Answer math questions\"}})"
  []
  (orc/workflow "evaluation-suite"
    (orc/blackboard
     {:trace-data TraceDataSchema
      :grounding-result sheets-GroundingResultSchema
      :instruction-result InstructionResultSchema
      :reasoning-result sheets-ReasoningResultSchema
      :completeness-result sheets-CompletenessResultSchema
      :aggregate-result AggregateResultSchema})

    (orc/sequence "main"
      (orc/parallel "run-judges"
        (orc/code "grounding-judge"
          :fn "ai.obney.orc.evaluation.core.judges/grounding-judge"
          :reads [:trace-data]
          :writes [:grounding-result])

        (orc/code "instruction-judge"
          :fn "ai.obney.orc.evaluation.core.judges/instruction-following-judge"
          :reads [:trace-data]
          :writes [:instruction-result])

        (orc/code "reasoning-judge"
          :fn "ai.obney.orc.evaluation.core.judges/reasoning-judge"
          :reads [:trace-data]
          :writes [:reasoning-result])

        (orc/code "completeness-judge"
          :fn "ai.obney.orc.evaluation.core.judges/completeness-judge"
          :reads [:trace-data]
          :writes [:completeness-result]))

      (orc/code "aggregate"
        :fn "ai.obney.orc.evaluation.core.judges/aggregate-dimensions"
        :reads [:grounding-result :instruction-result
                :reasoning-result :completeness-result]
        :writes [:aggregate-result]))))

(defn batch-evaluation-suite
  "Evaluation suite for processing multiple traces.

   Uses map-each to iterate over a list of traces and evaluate each one. The
   current trace is the `:trace-data` key the judges read; the per-item body is
   ONE code leaf with explicit writes, so a parallel iteration collects its own
   result and never another's.

   Input:
     traces: Vector of trace-data maps

   Output:
     results: Vector, one entry per trace in input order: the trace itself
              with `:current-aggregate`, the aggregate result of its four judges

   Example:
     (def batch-id (sheet/build-workflow! ctx (batch-evaluation-suite)))
     (sheet/execute ctx batch-id
       {:traces [{:inputs {...} :response \"...\" :instruction \"...\"}
                 {:inputs {...} :response \"...\" :instruction \"...\"}]})"
  []
  (orc/workflow "evaluation-batch-suite"
    (orc/blackboard
     {:traces [:vector TraceDataSchema]
      :trace-data TraceDataSchema
      :current-aggregate AggregateResultSchema
      :results [:vector [:map [:current-aggregate AggregateResultSchema]]]})

    (orc/map-each "evaluate-all"
      :from :traces
      :as :trace-data
      :into :results
      :parallel 3

      (orc/code "evaluate-trace"
        :fn "ai.obney.orc.evaluation.core.judges/evaluate-trace"
        :reads [:trace-data]
        :writes [:current-aggregate]))))

(defn selective-judge-suite
  "Evaluation suite that only runs specified judges.

   This is useful when you only need specific dimensions evaluated.

   Args:
     judge-keys: Vector of judge keys to include
                 Options: :grounding, :instruction-following, :reasoning, :completeness

   Example:
     ;; Only grounding and reasoning
     (def suite-id
       (sheet/build-workflow! ctx
         (selective-judge-suite [:grounding :reasoning])))"
  [judge-keys]
  (let [include-grounding? (some #{:grounding} judge-keys)
        include-instruction? (some #{:instruction-following} judge-keys)
        include-reasoning? (some #{:reasoning} judge-keys)
        include-completeness? (some #{:completeness} judge-keys)

        judge-nodes (cond-> []
                      include-grounding?
                      (conj (orc/code "grounding-judge"
                              :fn "ai.obney.orc.evaluation.core.judges/grounding-judge"
                              :reads [:trace-data]
                              :writes [:grounding-result]))

                      include-instruction?
                      (conj (orc/code "instruction-judge"
                              :fn "ai.obney.orc.evaluation.core.judges/instruction-following-judge"
                              :reads [:trace-data]
                              :writes [:instruction-result]))

                      include-reasoning?
                      (conj (orc/code "reasoning-judge"
                              :fn "ai.obney.orc.evaluation.core.judges/reasoning-judge"
                              :reads [:trace-data]
                              :writes [:reasoning-result]))

                      include-completeness?
                      (conj (orc/code "completeness-judge"
                              :fn "ai.obney.orc.evaluation.core.judges/completeness-judge"
                              :reads [:trace-data]
                              :writes [:completeness-result])))

        bb-schema (cond-> {:trace-data TraceDataSchema
                           :aggregate-result AggregateResultSchema}
                    include-grounding?
                    (assoc :grounding-result sheets-GroundingResultSchema)

                    include-instruction?
                    (assoc :instruction-result InstructionResultSchema)

                    include-reasoning?
                    (assoc :reasoning-result sheets-ReasoningResultSchema)

                    include-completeness?
                    (assoc :completeness-result sheets-CompletenessResultSchema))

        agg-reads (cond-> []
                    include-grounding? (conj :grounding-result)
                    include-instruction? (conj :instruction-result)
                    include-reasoning? (conj :reasoning-result)
                    include-completeness? (conj :completeness-result))]

    (orc/workflow (str "evaluation-selective-" (clojure.string/join "-" (map name judge-keys)))
      (orc/blackboard bb-schema)

      (orc/sequence "main"
        (apply orc/parallel "run-judges" judge-nodes)

        (orc/code "aggregate"
          :fn "ai.obney.orc.evaluation.core.judges/aggregate-dimensions"
          :reads agg-reads
          :writes [:aggregate-result])))))

;; --- Judge behaviours ---

(def builtin-types
  "The judge types that ship as behaviours."
  #{:grounding :instruction-following :reasoning :completeness})

(defn default-rubric
  "The default rubric of a built-in judge type, as the data a judge declaration
   carries: {:criterion :stance :bands {level description} :feedback :required}.
   Built from the same criteria/stance/scale constants the legacy judge
   functions compose their prompts from."
  [judge-type]
  (let [{:keys [criteria stance scale]} (get-tier1-rubric judge-type)]
    {:criterion criteria
     :stance stance
     :bands (into (sorted-map) (:bands scale))
     :feedback :required}))

(defn- non-blank-string? [v] (and (string? v) (not (str/blank? v))))

(defn effective-rubric
  "The rubric a built-in judge grades by: its declared :rubric, else the type's
   default rubric. A judge declared with only :criteria (no rubric) uses the
   default rubric with that criterion."
  [judge-config]
  (or (:rubric judge-config)
      (cond-> (default-rubric (:type judge-config))
        (non-blank-string? (:criteria judge-config))
        (assoc :criterion (:criteria judge-config)))))

(defn grading-form
  "`:feedback` when the rubric requires written feedback, `:score-only` when it
   declares none."
  [rubric]
  (if (= :none (:feedback rubric)) :score-only :feedback))

(defn rubric-value
  "The rubric as the blackboard value the behaviour reads: the grading contract
   (criterion, stance, described bands) without the feedback mode, which the
   behaviour's form already encodes."
  [rubric]
  (cond-> {:bands (into (sorted-map) (:bands rubric))}
    (:criterion rubric) (assoc :criterion (:criterion rubric))
    (:stance rubric) (assoc :stance (:stance rubric))))

(def ^:private host-value-registry
  {:ai.obney.orc.evaluation.core.judge-behaviours/value
   [:or :nil :string :int :double :boolean :keyword :uuid
    [:vector [:ref :ai.obney.orc.evaluation.core.judge-behaviours/value]]
    [:map-of [:or :keyword :string] [:ref :ai.obney.orc.evaluation.core.judge-behaviours/value]]]})

(defn- host-values
  "A recursive map of host values, described for the model."
  [description]
  [:schema {:registry host-value-registry}
   [:map-of {:description description} [:or :keyword :string] [:ref :ai.obney.orc.evaluation.core.judge-behaviours/value]]])

(def ^:private rubric-schema
  [:map {:description (str "The grading contract. `criterion` is WHAT to evaluate, "
                           "`stance` is HOW to behave as the reviewer, and `bands` maps "
                           "each band number to what that band means. You must choose "
                           "exactly one of the band numbers.")}
   [:bands [:map-of :int [:maybe :string]]]
   [:criterion {:optional true} :string]
   [:stance {:optional true} :string]])

(def ^:private host-instruction-description
  "The instruction the assessed node was given (the task it was asked to do).")

(def ^:private host-inputs-description
  "The values the assessed node read (the material it had to work from).")

(def ^:private host-outputs-description
  (str "The values the assessed node wrote: its declared output fields, one value per "
       "field. The field set is fixed by the workflow's typed blackboard, not chosen by "
       "the producer. Judge the values, not the object shape."))

(def ^:private original-task-description
  (str "The original task of the whole run: the inputs the run was started with. "
       "Context only, separate from the assessed node's own instruction; it shows "
       "what the run as a whole was asked to do."))

(def ^:private host-iterations-description
  "Bounded durable evidence for the research attempts that produced the outcome.")

(defn- with-description
  "Add a :description to a Malli schema form's properties."
  [schema description]
  (cond
    (keyword? schema) [schema {:description description}]
    (map? (second schema)) (update schema 1 assoc :description description)
    :else (into [(first schema) {:description description}] (rest schema))))

(def ^:private band-description
  (str "The band number you chose from the rubric's bands, chosen AFTER your "
       "reasoning. It must be one of the rubric's band numbers."))

(defn- band-schema
  "The chosen band: a positive integer. Never an enum (integer enums broke one
   Gemini route). The bound is a `:min` property on a plain `:int`, NOT an
   `[:and :int [:>= 1]]` form: the structured-output parser only reads a plain
   integer schema as a number, so the `:and` form leaves the model's `5` as the
   text \"5\" and the band fails schema validation (found live on
   z-ai/glm-5.3-flash). A banded decision requires a plain integer answer key
   too; the model is offered only the rubric's own bands."
  []
  [:int {:min 1 :description band-description}])

(defn- blackboard-for [judge-type form iterations? feedback-fields]
  (cond-> {:host-instruction [:string {:description host-instruction-description}]
           :host-inputs (host-values host-inputs-description)
           :host-outputs (host-values host-outputs-description)
           :original-task (host-values original-task-description)
           :rubric rubric-schema
           :band (band-schema)}
    iterations? (assoc :host-iterations [:string {:description host-iterations-description}])
    (= :feedback form) (merge feedback-fields)))

(defn- type-fields [judge-type]
  (case judge-type
    :grounding (grounding-output-fields)
    :instruction-following (instruction-following-output-fields)
    :reasoning (reasoning-output-fields)
    :completeness (completeness-output-fields)))

(defn- as-band-language [text]
  (str/replace text "choosing a level" "choosing a band"))

(defn- non-blank-feedback
  "The feedback the rubric requires must contain a non-whitespace character. A
   blank answer then violates the node's declared output, so it is a malformed
   provider answer and takes the executor's ordinary retry path. The pattern is
   a STRING so the schema survives persistence in the workflow snapshot."
  [string-schema]
  [:re (second string-schema) "\\S"])

(defn- feedback-form-fields
  "Blackboard entries for the feedback form's writes other than :band, keyed by
   field name, in generation order: :reasoning first, then the type's evidence
   fields, (the band sits between them and the feedback), then :feedback."
  [judge-type]
  (let [fields (type-fields judge-type)
        field (fn [n] (first (filter #(= n (:name %)) fields)))
        evidence (remove #(#{:reasoning :level :feedback} (:name %)) fields)
        entry (fn [{:keys [spec description]}]
                (with-description spec (as-band-language description)))]
    {:ordered-writes (vec (concat [:reasoning]
                                  (map :name evidence)
                                  [:band :feedback]))
     :fields (into {:reasoning (entry (field :reasoning))
                    :feedback (non-blank-feedback (entry (field :feedback)))}
                   (map (fn [f] [(:name f) (entry f)]) evidence))}))

(defn- evidence-guidance [judge-type iterations?]
  (str
   (case judge-type
     :grounding
     (str "You are given `host-inputs` (the source: the values the producer read, the "
          "ground truth to check against), `host-outputs` (the values the producer "
          "wrote), and `host-instruction` (the task the producer was given, for context "
          "only: do not grade against it). Compare the host outputs against the host "
          "inputs ONLY.")
     (str "You are given `host-instruction` (the task the producer was given), "
          "`host-outputs` (the values the producer wrote), and `host-inputs` (the "
          "context and material the producer had). Evaluate the host outputs against "
          "the host instruction, and against the host inputs where relevant."))
   " `original-task` is the task the whole run was started with: context only, "
   "separate from the host instruction."
   " Judge the values, not the object shape."
   (when iterations?
     (str " You are also given `host-iterations`, a bounded durable record of the "
          "research attempts. Use it to explain why the outcome occurred."))))

(def ^:private rubric-guidance
  (str "`rubric` is your grading contract: apply its criterion, behave as its stance "
       "says, and choose exactly one band number from its bands."))

(defn- feedback-instruction [judge-type iterations?]
  (str (evidence-guidance judge-type iterations?) "\n\n"
       rubric-guidance "\n\n"
       "Fill `reasoning` first (adversarial analysis), then the evidence lists, then "
       "choose `band`, then write `feedback`: specific, actionable feedback the "
       "producer can act on."))

(defn- score-only-instruction [judge-type iterations?]
  (str (evidence-guidance judge-type iterations?) "\n\n"
       "Grade the host outputs against the rubric: apply its criterion, behave as its "
       "stance says, and choose the band that fits."))

(defn workflow-name
  "The workflow's name: one per type, form, evidence shape and model, so a
   declared model gets its own concrete workflow (a content-addressed build is
   idempotent, so reuse is free)."
  [judge-type form iterations? model]
  (str "judge-" (name judge-type) "-" (name form)
       (when iterations? "-iterations")
       (when (non-blank-string? model) (str "@" model))))

(defn behaviour
  "The workflow definition of a built-in judge.

   `form` is :feedback or :score-only; `model` is the judge's declared model
   (nil: the node declares none and resolves like any node, from the runtime's
   configured provider); `iterations?` adds the host-iterations evidence read
   for repl-researcher hosts."
  [judge-type form {:keys [model iterations?]}]
  {:pre [(contains? builtin-types judge-type) (#{:feedback :score-only} form)]}
  (let [model (when (non-blank-string? model) model)
        reads (cond-> [:host-instruction :host-inputs :host-outputs :original-task :rubric]
                iterations? (conj :host-iterations))
        node-name "grade"
        wf-name (workflow-name judge-type form iterations? model)]
    (if (= :score-only form)
      (orc/workflow wf-name
        (orc/blackboard (blackboard-for judge-type form iterations? nil))
        (orc/llm-decision node-name
          :model model
          :instruction (score-only-instruction judge-type iterations?)
          :reads reads
          :bands-from :rubric
          :writes [:band]))
      (let [{:keys [ordered-writes fields]} (feedback-form-fields judge-type)]
        (orc/workflow wf-name
          (orc/blackboard (blackboard-for judge-type form iterations? fields))
          (orc/llm node-name
            :model model
            :instruction (feedback-instruction judge-type iterations?)
            :reads reads
            :writes ordered-writes
            :options {:unparseable-is-schema-error? true}))))))

;; --- Node version ---

(def ^:private not-definition
  "Keys of a node entry that are bookkeeping or the judging setup, not what the
   node does when it runs."
  #{:id :sheet-id :parent-id :children-ids :status :last-error :judges :name
    :source-node-id})

(defn- canonical
  "A deterministic, order-independent form of `x`: maps and sets become sorted
   vectors (by printed form), so equal values always print equal."
  [x]
  (cond
    (map? x) (->> x
                  (map (fn [[k v]] [(canonical k) (canonical v)]))
                  (sort-by (comp pr-str first))
                  (into [:map]))
    (set? x) (into [:set] (sort-by pr-str (map canonical x)))
    (sequential? x) (mapv canonical x)
    :else x))

(defn- sha-256-hex [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn effective-definition
  "The definition of `node` as it ran: the node entry less bookkeeping and absent
   fields, with `overrides` (instruction overrides keyed by node name) applied."
  [node overrides]
  (let [overridden (if-let [instruction (and (:name node) (get overrides (:name node)))]
                     (assoc node :instruction instruction)
                     node)]
    (into {} (remove (fn [[k v]] (or (contains? not-definition k) (nil? v)))) overridden)))

(defn version-of
  "The version identifier (a SHA-256 hex string) of `definition` under the
   published sheet `version-number` (nil for a draft run)."
  [definition version-number]
  (sha-256-hex (pr-str (canonical {:definition definition :sheet-version version-number}))))

(defn node-version
  "The version of the node a completion event is for, read from the run it
   belongs to: the tick's own snapshot of the sheet (its nodes, overrides and
   published version) when it has one, else the sheet's live node. nil when the
   node cannot be found."
  [ctx {:keys [sheet-id tick-id node-id]}]
  (let [tick-ctx (orc/get-tick-execution-context ctx tick-id)
        node (if tick-ctx
               (get (:nodes-by-id tick-ctx) node-id)
               (orc/get-node ctx sheet-id node-id))]
    (when node
      (version-of (effective-definition node (:instruction-overrides tick-ctx))
                  (:version-number tick-ctx)))))

;; --- Judge run ---

(def default-timeout-ms
  "How long a judge's workflow may run unless the judge declares :timeout-ms."
  60000)

(defn failed [reason message & {:as extra}]
  (merge {:status :failed :reason reason :message message} extra))

(defn- ungradable [reason message & {:as extra}]
  (merge {:status :ungradable :reason reason :message message} extra))

(def model-unresolved-message
  (str "The judge's model could not be resolved. Declare a :model on the judge "
       "(a model id or a registered provider name), or configure the runtime's "
       "LLM provider so the judge can run on it."))

(defn host-value
  "Coerce an evidence value into the judge blackboard's value domain
   (nil, string, integer, double, boolean, keyword, uuid, vectors, maps)."
  [x]
  (cond
    (or (nil? x) (string? x) (boolean? x) (keyword? x) (uuid? x)) x
    (instance? Long x) x
    (integer? x) (str x)
    (ratio? x) (double x)
    (number? x) (double x)
    (map? x) (into {}
                   (map (fn [[k v]] [(if (or (keyword? k) (string? k)) k (str k))
                                     (host-value v)]))
                   x)
    (coll? x) (mapv host-value x)
    :else (str x)))

(defn model-provenance
  "One entry per model call of the judge's tick, from its completion events
   (requested model, resolved model, usage) - never invented."
  [completions provider]
  (->> completions
       (filter #(or (:model %) (:requested-model %) (:resolved-model %)))
       (mapv (fn [e]
               (cond-> {:model (or (:resolved-model e) (:requested-model e) (:model e))}
                 (:requested-model e) (assoc :requested-model (:requested-model e))
                 (:resolved-model e) (assoc :resolved-model (:resolved-model e))
                 (:usage e) (assoc :usage (:usage e))
                 (keyword? provider) (assoc :provider provider))))))

(defn- rejected-keys
  "The writes the executor rejected on the judge's failed completions."
  [completions]
  (into #{} (comp (filter #(= :failure (:status %))) (mapcat #(concat (:rejected-write-keys %) (:missing-write-keys %)))) completions))

(defn- failure-kind [completions]
  (some #(when (and (= :failure (:status %)) (:failure-kind %)) (:failure-kind %))
        completions))

(defn- band-score
  "Deterministic mapping of a valid band to [0,1]: (level - min) / (max - min)."
  [bands band]
  (let [levels (sort (keys bands))
        lo (first levels)
        hi (last levels)]
    (double (/ (- band lo) (- hi lo)))))

(defn- non-blank? [v] (and (string? v) (not (str/blank? v))))

(defn- validate-dimensions
  "Each supporting dimension needs a non-blank name and a numeric score in
   [0,1] - and feedback when the rubric requires it. A missing WEIGHT defaults
   to 1.0 (declared equal weighting); a missing score is never defaulted
   (J09). Returns {:dimensions [...]} or {:error message}."
  [raw rubric]
  (cond
    (nil? raw) {:dimensions []}
    (not (sequential? raw)) {:error (str "the judge wrote :dimensions that is not a vector: " (pr-str raw))}
    :else
    (let [feedback-required? (= :required (:feedback rubric))
          checked
          (map-indexed
           (fn [i d]
             (let [problem
                   (cond
                     (not (map? d)) "is not a map"
                     (not (non-blank? (:name d))) "has no non-blank :name"
                     (not (and (number? (:score d)) (<= 0.0 (double (:score d)) 1.0)))
                     (str "has no numeric :score in [0,1] (got " (pr-str (:score d)) ")")
                     (and (some? (:weight d)) (not (and (number? (:weight d)) (>= (double (:weight d)) 0.0))))
                     (str "has an invalid :weight " (pr-str (:weight d)))
                     (and feedback-required? (not (non-blank? (:feedback d))))
                     "has no :feedback, which the rubric requires")]
               (if problem
                 {:error (str "dimension " i " " problem)}
                 {:dimension (cond-> {:name (:name d)
                                      :weight (double (or (:weight d) 1.0))
                                      :score (double (:score d))}
                               (some? (:feedback d)) (assoc :feedback (:feedback d))
                               (and (nil? (:feedback d)) (nil? rubric)) (assoc :feedback ""))})))
           raw)]
      (if-let [e (some :error checked)]
        {:error e}
        {:dimensions (mapv :dimension checked)}))))

(defn- summarize-evidence
  "Render one dimension's feedback from a pair of evidence lists (cited vs
   omitted), in the judge's own vocabulary. Empty lists still yield a
   dimension - the feedback says nothing was cited/omitted rather than
   silently dropping the dimension (RR-30)."
  [cited-label cited-items omitted-label omitted-items]
  (str cited-label ": "
       (if (seq cited-items) (str/join "; " cited-items) "nothing cited")
       ". " omitted-label ": "
       (if (seq omitted-items) (str/join "; " omitted-items) "none")
       "."))

(defn judge-run-project-dimensions
  "RR-30: project a built-in judge's own evidence lists (on its outputs) into
   its one named DimensionScore, carrying the judge's own score and a weight
   of 1.0, named with the judge's rubric name from
   `judges/default-judge-dimension-names` (the ontology classifier's dictionary
   is case-sensitive). Pure: no new model call."
  [judge-type inner score]
  (let [dimension (fn [cited-label cited omitted-label omitted]
                    [{:name (default-judge-dimension-names judge-type)
                      :weight 1.0
                      :score score
                      :feedback (summarize-evidence cited-label cited omitted-label omitted)}])]
    (case judge-type
      :grounding (dimension "Grounded claims" (:grounded-claims inner)
                            "Ungrounded claims" (:ungrounded-claims inner))
      :reasoning (dimension "Strengths" (:reasoning-strengths inner)
                            "Weaknesses" (:reasoning-weaknesses inner))
      :completeness (dimension "Aspects covered" (:aspects-covered inner)
                               "Aspects missing" (:aspects-missing inner))
      :instruction-following (dimension "Requirements met" (:requirements-met inner)
                                        "Requirements missed" (:requirements-missed inner))
      [])))

(defn rubric-outcome
  "The outcome of a judge that grades by a rubric: the workflow wrote a `:band`."
  [judge-type rubric outputs]
  (let [bands (:bands rubric)
        band (:band outputs)
        valid? (and (integer? band) (contains? bands band))]
    (cond
      (not valid?)
      (failed :invalid-result
              (if (nil? band)
                "The judge wrote no band, so there is nothing to score."
                (str "The judge wrote band " (pr-str band) ", which is not one of the rubric's bands "
                     (pr-str (vec (sort (keys bands))))
                     ".")))

      (and (= :required (:feedback rubric)) (not (non-blank? (:feedback outputs))))
      (failed :missing-feedback
              "The rubric requires written feedback but the judge wrote none; no score is recorded.")

      :else
      (let [score (band-score bands band)
            dims (if (contains? builtin-types judge-type)
                   {:dimensions (judge-run-project-dimensions judge-type outputs score)}
                   (validate-dimensions (:dimensions outputs) rubric))]
        (if (:error dims)
          (failed :invalid-dimensions (:error dims))
          (cond-> {:status :scored
                   :band band
                   :score score
                   :dimensions (:dimensions dims)}
            (= :required (:feedback rubric)) (assoc :feedback (:feedback outputs))))))))

(defn legacy-score-outcome
  "A legacy custom judge (no rubric) that writes a numeric :score in [0,1]."
  [outputs]
  (let [score (:score outputs)]
    (if-not (and (number? score) (<= 0.0 (double score) 1.0))
      (failed :invalid-result
              (if (nil? score)
                "The custom judge wrote no :score, so there is nothing to score."
                (str "The custom judge wrote :score " (pr-str score) ", which is not a number in [0,1].")))
      (let [dims (validate-dimensions (:dimensions outputs) nil)]
        (if (:error dims)
          (failed :invalid-dimensions (:error dims))
          (cond-> {:status :scored
                   :score (double score)
                   :dimensions (:dimensions dims)}
            (non-blank? (:feedback outputs)) (assoc :feedback (:feedback outputs))))))))

(defn execution-failure-outcome [result completions timeout-ms]
  (let [kind (failure-kind completions)]
    (cond
      (= :timeout (:status result))
      (failed :deadline (str "The judge did not finish within " timeout-ms " ms."))

      (= :undecided kind)
      (ungradable :tied-bands
                  (str "The decision model returned an exact tie for the most probable band, "
                       "so no band is selected; the expected position is never rounded into a band."))

      (and (= :schema-validation-failed kind) (contains? (rejected-keys completions) :feedback))
      (failed :missing-feedback
              "The rubric requires written feedback but the judge wrote none after its retries; no score is recorded.")

      (= :provider-not-configured kind)
      (failed :judge-model-unresolved model-unresolved-message)

      :else
      (failed (or kind :judge-execution-failed)
              (str "The judge's workflow did not succeed"
                   (when-let [e (:error result)] (str ": " e)))))))

(defn execute-workflow
  "Run a judge's workflow marked as assessment work (`origin`): the mark is
   recorded durably on the run and inherited by everything it delegates to, so
   nothing inside a judge's tree is ever auto-assessed."
  [ctx sheet-id inputs timeout-ms origin]
  (try
    (orc/execute ctx sheet-id inputs :timeout-ms timeout-ms :assessment-origin origin)
    (catch Throwable t
      (u/log :ai.obney.orc.evaluation.core.judge-run/judge-execution-threw
             :sheet-id sheet-id
             :error (ex-message t)
             :exception-class (.getName (class t)))
      {:status :failure :error (ex-message t)})))

(def ^:private build-lock (Object.))

(defn build-behaviour!
  "Build (idempotently, by content) the concrete behaviour of a built-in judge.
   Serialised so concurrent judges of one type never race the same build."
  [ctx judge-type form opts]
  (locking build-lock
    (orc/build-workflow! ctx (behaviour judge-type form opts))))

(defn execution-family
  "The complete execution family beneath the assessed node: every node execution
   under it, following delegates and generated child runs, verbatim and
   untruncated (`orc/get-execution-family` scoped to the node). A node that ran
   as one iteration of a map-each is shown only its own iteration's executions
   in its own run."
  [ctx {:keys [tick-id node-id exec-context]}]
  (let [family (orc/get-execution-family ctx tick-id {:node-id node-id})]
    (if (seq exec-context)
      (filterv (fn [entry]
                 (or (not= tick-id (:tick-id entry))
                     (= exec-context (select-keys (:exec-context entry) (keys exec-context)))))
               family)
      family)))

(defn declares-key?
  "True when `judge-config` is a custom judge whose workflow declares the
   blackboard key `k`: the judge's way of asking the runtime for optional
   evidence (:host-family, :child-assessments). Built-in judges declare none."
  [ctx judge-config k]
  (let [eval-sheet-id (or (:eval-sheet-id judge-config) (:sheet-id judge-config))]
    (boolean (and (= :custom (:type judge-config))
                  eval-sheet-id
                  (contains? (orc/get-blackboard-by-key ctx eval-sheet-id) k)))))

;; --- Assessments ---

(def ^:private assessment-namespace
  "Fixed namespace of the name-based (v5) assessment ids."
  #uuid "5c1ae3a0-6f5d-5d59-9b49-3f2a1c7e8d10")

(defn assessment-id
  "The deterministic id of the assessment of `subject-completion-id` by the judge
   `judge-name` at `revision-number`. The same triple always yields the same id,
   so a replayed completion maps onto the assessment it already requested."
  [subject-completion-id judge-name revision-number]
  (uuid/v5 assessment-namespace
           (str subject-completion-id "|" judge-name "|" revision-number)))

(def default-purposes
  "The purposes of a judge that declares none (every declared judge has fed the
   learning loops): monitoring + learning."
  #{:monitoring :learning})

(def request-event-type :evaluation/assessment-requested)

(def terminal-event-types
  #{:evaluation/assessment-scored
    :evaluation/assessment-failed
    :evaluation/assessment-ungradable})

(def lifecycle-event-types
  (conj terminal-event-types request-event-type))

(def terminal-status
  {:evaluation/assessment-scored :scored
   :evaluation/assessment-failed :failed
   :evaluation/assessment-ungradable :ungradable})

(def status->terminal-event-type
  (into {} (map (fn [[t s]] [s t])) terminal-status))

(defmulti assessments*
  "Apply a lifecycle event to the assessments state: `{assessment-id entry}`."
  (fn [_state event] (:event/type event)))

(defmethod assessments* :default [state _] state)

(defmethod assessments* :evaluation/assessment-requested
  [state event]
  (assoc state (:assessment-id event)
         (cond-> (merge {:status :pending :late false}
                        (select-keys event [:assessment-id :sheet-id :node-id :tick-id
                                            :subject-completion-id :judge-name
                                            :judge-revision-number :judge-type :purposes
                                            :requested-at :exec-context :node-version :run-node-id :version-number
                                            :depends-on])))))

(defn- assessments-settle
  "A terminal event settles a PENDING assessment once; a later one never rewrites it."
  [state event status fields]
  (let [id (:assessment-id event)
        entry (get state id {:assessment-id id :status :pending :late false})]
    (if (= :pending (:status entry))
      (assoc state id (merge entry (select-keys event fields) {:status status}))
      state)))

(defmethod assessments* :evaluation/assessment-scored
  [state event]
  (assessments-settle state event :scored [:band :score :feedback :dimensions :band-distribution
                               :model-provenance :judge-tick-id]))

(defmethod assessments* :evaluation/assessment-failed
  [state event]
  (assessments-settle state event :failed [:reason :message :model-provenance :judge-tick-id]))

(defmethod assessments* :evaluation/assessment-ungradable
  [state event]
  (assessments-settle state event :ungradable [:reason :message :band-distribution
                                   :model-provenance :judge-tick-id]))

;; --- Performance ---

(def default-window "Outcomes in a trailing window unless a query says otherwise." 20)

(def max-retained
  "Most recent outcomes a cell keeps in order; the largest window that can be asked of
   it, and so the largest an alert may declare."
  orc/max-alert-window)

(defn cell-key [{:keys [sheet-id node-id node-version judge-name judge-revision-number]}]
  [sheet-id node-id node-version judge-name judge-revision-number])

(defn cell-id
  "A stable UUID naming a cell (used to tag its alert signals)."
  [cell-key]
  (uuid/v5 #uuid "a41f9d52-6b0e-5b0a-9c8e-0d2b7f7a4c11" (pr-str cell-key)))

(defn- empty-cell [k first-seen]
  (let [[sheet-id node-id node-version judge-name revision] k]
    {:sheet-id sheet-id :node-id node-id :node-version node-version
     :judge-name judge-name :judge-revision-number revision
     :pending 0 :scored 0 :failed 0 :ungradable 0 :subject-failed 0
     :score-sum 0.0 :bands {} :series []
     :first-seen first-seen :last-seen first-seen}))

(defmulti performance*
  "Fold one assessment lifecycle event into `{:index {assessment-id {:cell :settled?}}
   :cells {cell-key cell}}`."
  (fn [_state event] (:event/type event)))

(defmethod performance* :default [state _] state)

(defmethod performance* :evaluation/assessment-requested
  [state event]
  (let [id (:assessment-id event)
        k (cell-key event)]
    (if (contains? (:index state) id)
      state
      (-> state
          (assoc-in [:index id] {:cell k :settled? false})
          (update-in [:cells k] #(or % (cond-> (empty-cell k (:requested-at event))
                                    (:version-number event) (assoc :version-number (:version-number event)))))
          (update-in [:cells k :pending] inc)))))

(defn- settle-cell [cell status event]
  (let [score (:score event)]
    (-> cell
        (update :pending dec)
        (update status inc)
        (cond-> (= :subject-failed (:reason event)) (update :subject-failed (fnil inc 0)))
        (assoc :last-seen (str (:event/timestamp event)))
        (cond->
         (= :scored status) (update :score-sum + score)
         (and (= :scored status) (some? (:band event))) (update-in [:bands (:band event)] (fnil inc 0)))
        (update :series (fn [series]
                          (let [series (conj series (cond-> {:assessment-id (:assessment-id event)
                                                             :status status
                                                             :at (str (:event/timestamp event))}
                                                      (= :scored status) (assoc :score score)
                                                      (= :subject-failed (:reason event))
                                                      (assoc :reason :subject-failed)
                                                      (some? (:band event)) (assoc :band (:band event))))]
                            (if (> (count series) max-retained)
                              (subvec series (- (count series) max-retained))
                              series)))))))

(defn- performance-settle [state event]
  (let [id (:assessment-id event)
        {:keys [cell settled?]} (get-in state [:index id])]
    (if (or (nil? cell) settled?)
      state
      (-> state
          (assoc-in [:index id :settled?] true)
          (update-in [:cells cell] settle-cell (terminal-status (:event/type event)) event)))))

(doseq [t terminal-event-types]
  (defmethod performance* t [state event] (performance-settle state event)))

(defn mean [xs] (when (seq xs) (/ (reduce + 0.0 xs) (count xs))))

(defn trailing-scores
  "The scores of the last `window` scored outcomes of `series`, oldest first."
  [series window]
  (->> series (filter #(= :scored (:status %))) (keep :score) (take-last window)))

(defn stats
  "The reported statistics of a cell-shaped map (a cell or a merge of cells)."
  [{:keys [scored failed ungradable pending score-sum bands series] :as cell} window]
  (let [total (+ scored failed ungradable pending)
        subject-failed (or (:subject-failed cell) 0)]
    (-> (select-keys cell [:sheet-id :node-id :node-version :judge-name :judge-revision-number
                           :first-seen :last-seen])
        (assoc :scored scored :failed failed :ungradable ungradable :pending pending
               :subject-failed subject-failed
               :total total
               :coverage (if (pos? total) (/ (double scored) total) 0.0)
               :mean-score (when (pos? scored) (/ score-sum scored))
               :trailing-mean (mean (trailing-scores series window))
               :band-distribution bands))))

(defn merge-cells
  "One cell standing for several (the same judge and revision across versions)."
  [cells]
  (let [base (-> (first cells) (dissoc :node-version))]
    (reduce (fn [acc c]
              (-> acc
                  (update :pending + (:pending c))
                  (update :scored + (:scored c))
                  (update :failed + (:failed c))
                  (update :ungradable + (:ungradable c))
                  (update :subject-failed (fnil + 0) (or (:subject-failed c) 0))
                  (update :score-sum + (:score-sum c))
                  (update :bands #(merge-with + % (:bands c)))
                  (update :series into (:series c))
                  (update :first-seen #(first (sort [% (:first-seen c)])))
                  (update :last-seen #(last (sort [% (:last-seen c)])))))
            (update base :series vec)
            (rest cells))))

(defn chronological [cell] (update cell :series #(vec (sort-by :at %))))

(defn matching [{:keys [sheet-id node-id node-version judge-name]} cells]
  (filter (fn [c] (and (or (nil? sheet-id) (= sheet-id (:sheet-id c)))
                       (or (nil? node-id) (= node-id (:node-id c)))
                       (or (nil? node-version) (= node-version (:node-version c)))
                       (or (nil? judge-name) (= judge-name (:judge-name c)))))
          cells))

;; --- Trace extraction ---

(def trace-events
  "Events that contain execution traces"
  #{:sheet/execution-traced})

(def node-events
  "Events that contain node definitions"
  #{:sheet/node-created
    :sheet/node-executor-set
    :sheet/node-instruction-set
    :sheet/node-io-set})

(defn build-nodes-map
  "Build a map of node-id -> node metadata from events"
  [events]
  (reduce
   (fn [state event]
     (case (:event/type event)
       :sheet/node-created
       (assoc state (:node-id event)
              {:node-id (:node-id event)
               :sheet-id (:sheet-id event)
               :type (:type event)
               :name nil
               :instruction nil
               :executor nil
               :model nil
               :reads []
               :writes []})

       :sheet/node-executor-set
       (-> state
           (assoc-in [(:node-id event) :executor] (:executor event))
           (assoc-in [(:node-id event) :model] (:model event)))

       :sheet/node-instruction-set
       (assoc-in state [(:node-id event) :instruction] (:instruction event))

       :sheet/node-io-set
       (-> state
           (assoc-in [(:node-id event) :reads] (:reads event))
           (assoc-in [(:node-id event) :writes] (:writes event)))

       state))
   {}
   events))

(def ^:private llm-node-types
  "Node TYPES that call an LLM by definition, whatever their executor."
  #{:llm-condition :repl-researcher})

(defn- is-llm-node?
  "Does this node trace represent an LLM call?

   Two independent axes decide it, and conflating them is why this returned
   false for every node in the log:

     :node-type — what kind of node (:leaf, :map-each, :repl-researcher …).
                  :llm-condition and :repl-researcher call an LLM by
                  definition.
     :executor  — how a LEAF runs (:ai, :decision, :code, :tool). A leaf is :leaf
                  whether it calls a model or runs Clojure; only :executor
                  distinguishes them.

   The previous version tested `:executor` against node-TYPE values
   (:llm, :llm-condition, :repl-researcher). :llm is not a member of either
   enum, and the executor enum is :ai/:code/:tool — so the set could never
   match, and `llm-only?` emptied every result.

   `node-metadata` is the entry from build-nodes-map, used as a fallback for
   traces written before :executor was carried on the node trace."
  ([node-trace] (is-llm-node? node-trace nil))
  ([node-trace node-metadata]
   (let [executor (or (:executor node-trace) (:executor node-metadata))
         node-type (or (:node-type node-trace) (:type node-metadata))]
     (boolean (or (contains? #{:ai :decision} executor)
                  (contains? llm-node-types node-type))))))

(defn extract-node-trace-data
  "Transform a raw node trace into evaluation format.

   Returns:
     {:trace-id UUID
      :sheet-id UUID
      :node-id UUID
      :node-name string
      :inputs map - the inputs provided to the node
      :outputs map - the outputs produced by the node
      :instruction string - the instruction/prompt used
      :model string - the model used
      :duration-ms int
      :status keyword
      :researcher-iterations vector - ordered durable records, when present
      :researcher-events vector - bounded campaign evidence, when present}"
  [sheet-trace node-trace node-metadata io]
  (cond->
   {:trace-id (:trace-id sheet-trace)
    :sheet-id (:sheet-id sheet-trace)
    :node-id (:node-id node-trace)
    :node-name (or (:node-name node-trace) (:name node-metadata) "unknown")
    :inputs (or (:inputs io) {})
    :outputs (or (:outputs io) {})
    :instruction (or (:instruction node-trace) (:instruction node-metadata) "")
    :model (or (:model node-trace) (:model node-metadata))
    :duration-ms (:duration-ms node-trace)
    :status (:status node-trace)
    :executed-at (:started-at sheet-trace)}
    (seq (:researcher-iterations sheet-trace))
    (assoc :researcher-iterations (:researcher-iterations sheet-trace))
    (seq (:researcher-events sheet-trace))
    (assoc :researcher-events (:researcher-events sheet-trace))))

(defn filter-node-traces
  "Filter node traces from a sheet trace based on criteria.

   Options:
     :node-id - Filter to specific node ID
     :node-name - Filter by node name (substring match)
     :executor - Filter by executor type (:ai, :code, :tool)
     :llm-only? - Only include LLM nodes
     :nodes-map - Optional {node-id -> node metadata}, used to resolve
                  :executor for traces that predate it being carried on the
                  node trace itself"
  [node-traces {:keys [node-id node-name executor llm-only? nodes-map]}]
  (cond->> node-traces
    llm-only? (filter #(is-llm-node? % (get nodes-map (:node-id %))))
    node-id (filter #(= node-id (:node-id %)))
    node-name (filter #(and (:node-name %)
                            (.contains (str (:node-name %)) node-name)))
    executor (filter #(= executor (:executor %)))))

(defn format-trace-for-evaluation
  "Format a trace for input to evaluation judges.

   Transforms extracted trace data into the format expected by judges:
   - Converts inputs/outputs to strings if needed
   - Extracts response text from common output patterns
   - Ensures instruction is present

   Args:
     trace: A trace map from get-llm-traces

   Returns:
     Map with:
       :inputs - JSON string or map of inputs
       :response - The LLM response text
       :instruction - The instruction/prompt used"
  [{:keys [inputs outputs instruction]}]
  (let [
        response (or (:response outputs)
                     (:answer outputs)
                     (:output outputs)
                     outputs)]
    {:inputs inputs
     :response response
     :instruction (or instruction "No instruction provided")}))

;; --- Commands ---

(defn within-process?
  "Authorization predicate for the judge score-recording commands.

   These commands are dispatched in-process by the judge runtime's
   background future (never from an external/HTTP boundary), so a
   default-allow is correct here — matching how `execute-leaf-node`'s
   completion commands flow within the process. Grain's command-request
   handler is the boundary that enforces auth for externally-submitted
   commands; these are never submitted that way."
  [_ctx]
  true)

(defn same-judge-score?
  [sheet-id node-id tick-id judge-name event]
  (and (= sheet-id (:sheet-id event))
       (= node-id (:node-id event))
       (= tick-id (:tick-id event))
       (= judge-name (:judge-name event))))

(defn same-composite-score?
  "Composite identity: the subject completion when the composite names one, else
   the legacy (sheet, node, tick) tuple."
  [sheet-id node-id tick-id subject event]
  (if subject
    (= subject (:subject-completion-id event))
    (and (= sheet-id (:sheet-id event))
         (= node-id (:node-id event))
         (= tick-id (:tick-id event)))))

(defn composite-scope-tags [tick-id subject]
  (if subject #{[:subject subject]} #{[:tick tick-id]}))

(defn open-assessment?
  "True when `history` holds the request and no terminal outcome yet."
  [history]
  (and (some #(= request-event-type (:event/type %)) history)
       (not-any? #(contains? terminal-event-types (:event/type %)) history)))

(defn outcome-error
  "nil when the command body is a well-formed outcome, else a message. A scored
   outcome has a score within [0,1], and - when its judge's rubric requires
   feedback - non-blank feedback; a failed or ungradable one has its reason and
   message - nothing is recorded without them."
  [{:keys [status score feedback reason message judge-config]}]
  (case status
    :scored (cond
              (not (number? score))
              "a scored outcome needs a :score"

              (not (<= 0.0 (double score) 1.0))
              (str "a scored outcome's :score must be within [0,1], got " (pr-str score))

              (and (= :required (get-in judge-config [:rubric :feedback]))
                   (not (and (string? feedback) (not (str/blank? feedback)))))
              "a scored outcome of a judge whose rubric requires feedback needs non-blank :feedback")
    (:failed :ungradable) (when-not (and reason message)
                            (str "a " (name status) " outcome needs a :reason and a :message"))
    nil))

(defn terminal-event
  [request-body {:keys [assessment-id status band score feedback dimensions band-distribution
                        reason message model-provenance judge-tick-id judge-config]}]
  (es/->event
   {:type (status->terminal-event-type status)
    :tags #{[:sheet (:sheet-id request-body)] [:node (:node-id request-body)]
            [:tick (:tick-id request-body)] [:assessment assessment-id]
            [:subject (:subject-completion-id request-body)]}
    :body (cond-> (merge {:assessment-id assessment-id}
                         (select-keys request-body [:sheet-id :node-id :node-version
                                                    :judge-name :judge-revision-number]))
            (:alert judge-config) (assoc :alert (:alert judge-config))
            (= :scored status) (assoc :score score :dimensions (vec dimensions))
            (and (= :scored status) (some? band)) (assoc :band band)
            (and (= :scored status) (some? feedback)) (assoc :feedback feedback)
            (not= :scored status) (assoc :reason reason :message message)
            (and (= :ungradable status) band-distribution) (assoc :band-distribution band-distribution)
            (and (= :scored status) band-distribution) (assoc :band-distribution band-distribution)
            (seq model-provenance) (assoc :model-provenance (vec model-provenance))
            judge-tick-id (assoc :judge-tick-id judge-tick-id))}))

(defn legacy-score-event
  "The `:judge/score-emitted` record of a scored outcome of a LEARNING judge that
   has feedback - the one record the learning loops read. Carries the assessment
   it records, so distinct executions of one node keep distinct scores."
  [request-body {:keys [assessment-id band score feedback dimensions model-provenance judge-config]}]
  (es/->event
   {:type :judge/score-emitted
    :tags #{[:sheet (:sheet-id request-body)] [:node (:node-id request-body)]
            [:tick (:tick-id request-body)] [:assessment assessment-id]}
    :body (cond-> {:sheet-id (:sheet-id request-body)
                   :tick-id (:tick-id request-body)
                   :node-id (:node-id request-body)
                   :judge-name (:judge-name request-body)
                   :judge-config (or judge-config {:type (:judge-type request-body)})
                   :score score
                   :feedback feedback
                   :dimensions (vec dimensions)
                   :emitted-at (str (java.time.Instant/now))
                   :assessment-id assessment-id
                   :revision-number (:judge-revision-number request-body)}
            (some? band) (assoc :band band)
            (seq model-provenance)
            (assoc :model-provenance (select-keys (first model-provenance)
                                                  [:provider :model :usage])))}))

;; --- Judge runtime ---

(defn strip-engine-keys
  "Drop the engine's own bookkeeping keys (map-each markers, tick iteration,
   durable order) from a map of values: they are not data any node declared,
   and a strict judge schema rejects them."
  [m]
  (into {} (remove (comp orc/value-log-engine-key? key)) m))

(defn resolved-reads-inputs
  "RR-31: the node's :inputs, resolved from its recorded reads via the value
   log. `event` is the `:sheet/node-execution-completed` body — it carries
   :read-keys (what the node declared it reads) and :read-sources (where
   each key's value came from); `orc/value-log-resolve-reads` walks those
   pointers back to the actual values the node read, the same way
   build-trace-data's :outputs already resolves the node's writes.

   Any non-empty direct :inputs on the event (execution context / map-each
   item overrides — the only thing `:inputs` carries since the value-log
   merge, per todo_processors.clj's root-start emit) is layered OVER the
   resolved reads: those are overrides the caller has already computed, not
   a substitute for the reads. When there is no direct :inputs, the resolved
   reads stand alone."
  [ctx tick-id event]
  (let [resolved (or (orc/value-log-resolve-reads (:event-store ctx) (:tenant-id ctx)
                                                  tick-id event)
                     {})
        direct-inputs (not-empty (:inputs event))]
    (merge resolved direct-inputs)))

(defn- descendant-ids
  "The ids of every node beneath `node-id` in `nodes-by-id`."
  [nodes-by-id node-id]
  (loop [acc #{} frontier [node-id]]
    (if (empty? frontier)
      acc
      (let [kids (into [] (mapcat #(:children-ids (get nodes-by-id %))) frontier)]
        (recur (into acc kids) kids)))))

(defn composite-reads
  "What a composite (a node with children and no read declaration of its own)
   read from OUTSIDE itself: the values its descendants in this tick read that
   no earlier descendant had written, resolved from the value log. Values a
   composite's children pass to each other are internal to it, not its reads."
  [ctx tick-id event node]
  (let [nodes-by-id (orc/get-nodes-by-id ctx (:sheet-id event))
        beneath (descendant-ids nodes-by-id (:id node))
        tick-events (orc/value-log-read-tick-events (:event-store ctx) (:tenant-id ctx) tick-id)
        completions (->> tick-events
                         (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                       (contains? beneath (:node-id %))))
                         (sort-by #(str (:event/id %))))
        external (loop [cs completions, written #{}, found {}]
                   (if-let [c (first cs)]
                     (let [fresh (into [] (remove #(or (contains? written %) (contains? found %)))
                                       (:read-keys c))]
                       (recur (rest cs)
                              (into written (:write-keys c))
                              (into found (map (fn [k] [k c])) fresh)))
                     found))]
    (into {}
          (mapcat (fn [[completion ks]]
                    (select-keys (orc/value-log-resolve-reads (:event-store ctx) (:tenant-id ctx)
                                                              tick-id completion)
                                 (map first ks))))
          (group-by val external))))

(defn- root-tick-id
  "The root of the run `tick-id` belongs to: follow :parent-tick-id up the
   durable tick-started events (delegates and generated trees start child
   ticks)."
  [ctx tick-id]
  (loop [id tick-id, depth 0]
    (let [parent (:parent-tick-id
                  (orc/value-log-tick-started-event ctx (:tenant-id ctx) id))]
      (if (and parent (< depth 64)) (recur parent (inc depth)) id))))

(defn original-task
  "The original task of the run: the root run's inputs, resolved values, kept
   separate from any node's own instruction."
  [ctx tick-id]
  (strip-engine-keys
   (or (orc/value-log-tick-seeds ctx (:tenant-id ctx) (root-tick-id ctx tick-id)) {})))

(defn run-node
  "The node a completion is for, as it ran: its entry in the run's durable tick
   execution context (the definition the run actually executed - for a published
   run, a node of the run's own that the live sheet does not have). Falls back
   to the sheet's live node only for a run with no snapshot."
  [ctx {:keys [sheet-id tick-id node-id]}]
  (or (some-> (orc/get-tick-execution-context ctx tick-id)
              (get-in [:nodes-by-id node-id]))
      (when (and sheet-id node-id) (orc/get-node ctx sheet-id node-id))))

(defn- judge-runtime-project-dimensions
  "RR-30: a default LLM judge's own evidence lists projected into its one named
   DimensionScore. The projection lives with the judge run path
   (`judge-run/project-dimensions`); kept here under its original name."
  [judge-type inner score]
  (judge-run-project-dimensions judge-type inner score))

(defn outcome->legacy
  "Map a judge OUTCOME (`judge-run/run-judge`) to the canonical
   `{:score :feedback :dimensions :model-provenance}` shape the
   `:evaluation/record-judge-score` command records, or nil.

   Only a :scored outcome carrying feedback is recorded: the legacy score event
   requires feedback, and a score-only outcome (rubric :feedback :none) has
   none and must not be given invented text - it is logged and skipped until
   assessments are recorded durably. A :failed or :ungradable outcome never
   becomes a score; it is logged with its reason."
  [judge-type outcome]
  (case (:status outcome)
    :scored
    (if (contains? outcome :feedback)
      {:score (:score outcome)
       :feedback (:feedback outcome)
       :dimensions (:dimensions outcome)
       :model-provenance (some-> (first (:model-provenance outcome))
                                 (select-keys [:provider :model :usage]))}
      (do (u/log :ai.obney.orc.evaluation.core.judge-runtime/score-only-result-not-recorded
                 :judge-type judge-type
                 :score (:score outcome)
                 :band (:band outcome))
          nil))

    (do (u/log :ai.obney.orc.evaluation.core.judge-runtime/judge-not-scored
               :judge-type judge-type
               :status (:status outcome)
               :reason (:reason outcome)
               :message (:message outcome))
        nil)))

(def llm-judge-types
  "The built-in judge types: each runs as a shipped behaviour (judge-behaviours)."
  #{:grounding :reasoning :completeness :instruction-following})

(defn invoke-heuristic-structural
  "Gap-2: heuristic structural evaluator. Scores the shape of the tree
   the host node emitted (via Phase 1 emit-tree! → :generated-tree-raw
   in :writes). Returns nil when the node didn't emit a tree, signaling
   the judge to gracefully no-op rather than score nothing."
  [trace-data]
  (when-let [tree (get-in trace-data [:outputs :generated-tree-raw])]
    (evaluate-tree-structure tree)))

(defn ->record-judge-score-command
  "Pure builder: the body for the `:evaluation/record-judge-score`
   command. The command handler is the sole writer of
   `:judge/score-emitted`; this builds the body it validates + emits.
   `source` is any map carrying :sheet-id / :node-id / :tick-id."
  [source judge-name judge-config result]
  {:command/id (random-uuid)
   :command/timestamp (time/now)
   :command/name :evaluation/record-judge-score
   :sheet-id (:sheet-id source)
   :tick-id (:tick-id source)
   :node-id (:node-id source)
   :judge-name judge-name
   :judge-config judge-config
   :score (:score result)
   :feedback (:feedback result)
   :dimensions (:dimensions result)
   :model-provenance (:model-provenance result)
   :emitted-at (str (java.time.Instant/now))})

(defn- normalize-judge-weights
  "Pure: given a seq of `{:judge-name :score :weight}` entries (where
   :score is numeric and :weight is optional), return a vector with
   :weight set to the EFFECTIVE normalized weight each entry contributes
   to the composite. Result weights always sum to ≤ 1.0 (= 1.0 except
   when all entries have :weight 0).

   Policy (Gap-8 RED#4 — share remaining mass):
   - **No :weight on any entry** → each entry gets 1/N (even distribution).
   - **All entries have :weight** → weights are re-normalized to sum to
     1.0 (relative weighting; consumer's input is a ratio, not absolute).
   - **Mixed (some :weight, some not)**:
     - When the explicit weights sum to < 1.0: explicit values are kept
       verbatim; un-weighted entries share the remaining mass evenly.
     - When the explicit weights sum to ≥ 1.0: un-weighted entries get
       0.0 (the explicit budget is already exhausted); explicit weights
       are re-normalized to sum to 1.0.

   Rationale: adding `:weight` to one judge must NOT silently zero the
   others. Consumers say 'weight THIS judge specifically; defaults for
   the rest'. The share-remaining policy preserves that intuition.

   Returns nil if no entries are scorable or total mass is zero."
  [entries]
  (let [scorable (filter #(number? (:score %)) entries)
        round4 (fn [w] (-> w (* 10000) Math/round (/ 10000.0)))]
    (when (seq scorable)
      (let [weighted   (filter #(number? (:weight %)) scorable)
            unweighted (remove #(number? (:weight %)) scorable)
            explicit-sum (reduce + 0.0 (map :weight weighted))]
        (cond
          (empty? weighted)
          (let [w (round4 (/ 1.0 (count scorable)))]
            (mapv #(assoc % :weight w) scorable))

          (empty? unweighted)
          (when (pos? explicit-sum)
            (mapv #(assoc % :weight (round4 (/ (:weight %) explicit-sum))) weighted))

          (>= explicit-sum 1.0)
          (when (pos? explicit-sum)
            (into []
                  (concat
                    (mapv #(assoc % :weight (round4 (/ (:weight %) explicit-sum))) weighted)
                    (mapv #(assoc % :weight 0.0) unweighted))))

          :else
          (let [remaining (- 1.0 explicit-sum)
                share (round4 (/ remaining (count unweighted)))]
            (into []
                  (concat
                    weighted
                    (mapv #(assoc % :weight share) unweighted)))))))))

(defn- compute-composite-score
  "Pure: takes a seq of `{:judge-name :score :weight}` entries and
   returns the weighted composite score, or nil if the entries are
   empty. See `normalize-judge-weights` for the weight policy.

   Returns a double rounded to 4 decimal places to keep schema-level
   equality predictable (avoids floating-point noise in test diffs).

   Entries with non-numeric :score are skipped; if NO entries have a
   numeric score, returns nil."
  [entries]
  (when-let [normalized (normalize-judge-weights entries)]
    (let [composite (reduce + 0.0 (map (fn [{:keys [score weight]}]
                                          (* score weight))
                                        normalized))]
      (-> composite
          (* 10000)
          Math/round
          (/ 10000.0)))))

(defn ->record-composite-score-command
  "Gap-8: build the body for the `:evaluation/record-composite-score`
   command from the judge results collected during a single processor
   cycle. Returns nil when fewer than 2 judges have valid scores —
   single-judge ticks don't need a composite distinct from the score
   itself, and zero-judge ticks have nothing to composite.

   `judge-results` is a seq of `{:judge-name :judge-config :result}`
   where :result is the canonical `{:score :feedback :dimensions}`
   shape (or nil for failed judges).

   The `:contributing-judges` field shows the EFFECTIVE normalized
   weight each judge contributed (e.g., `1/N` when consumers didn't set
   explicit weights), not the raw :judge-config value. This is what
   consumers want for debugging the composite: 'how did this number get
   computed?'

   The command handler is the sole writer of
   `:judge/composite-score-computed`; this builds the body it validates
   + emits. `source` carries :sheet-id / :node-id / :tick-id."
  ([source judge-results]
   (->record-composite-score-command
    source judge-results
    (let [scored (count (filter #(number? (:score (:result %))) judge-results))]
      {:expected (if (>= scored 2) (count judge-results) 0) :scored scored
       :failed (- (count judge-results) scored) :ungradable 0})))
  ([source judge-results coverage]
   (let [raw-entries (keep (fn [{:keys [judge-name judge-config result]}]
                             (when (and result (number? (:score result)))
                               {:judge-name judge-name
                                :score (:score result)
                                :weight (:weight judge-config)}))
                           judge-results)
         normalized (when (seq raw-entries) (normalize-judge-weights raw-entries))
         composite (when (seq raw-entries) (compute-composite-score raw-entries))]
     (when (>= (:expected coverage) 2)
       (cond-> {:command/id (random-uuid)
                :command/timestamp (time/now)
                :command/name :evaluation/record-composite-score
                :sheet-id (:sheet-id source)
                :tick-id (:tick-id source)
                :node-id (:node-id source)
                :contributing-judges (mapv (fn [{:keys [judge-name score weight]}]
                                             {:judge-name judge-name
                                              :score (double score)
                                              :weight (double weight)})
                                           normalized)
                :coverage coverage
                :partial (< (:scored coverage) (:expected coverage))
                :purpose :learning
                :emitted-at (str (java.time.Instant/now))}
         composite (assoc :composite-score composite)
         (:subject-completion-id source) (assoc :subject-completion-id
                                                (:subject-completion-id source)))))))

(def default-judges
  "Default judge entries auto-attached to repl-researcher nodes when
   the opt-in flag is on. Each entry is {:judge-name :judge-config}.
   Judge names are bare (e.g., \"grounding\") so downstream consumers
   querying judge-scores see them under the canonical name.

   ---
   IMPORTANT HISTORICAL CONTEXT — read before adding
   :applies-to-completion-kinds back to these defaults
   ---

   Gap-7 (2026-06-04) attempted to route these 5 judges by
   completion kind: heuristic-structural to intermediate ticks (which
   were assumed to carry :generated-tree-raw), the 4 LLM judges to
   terminal ticks (which were assumed to carry final task outputs).

   The LIVE verify of Gap-7 on legal-issue-detection revealed that
   premise was WRONG for the recursive RLM bench path:

     1. Recursive RLM only dispatches ONE :sheet/node-execution-completed
        per run — at terminal time when (final!) fires. There are no
        intermediate :sheet/node-execution-completed events; the
        Phase 1 ↔ Phase 2 loop is internal to the executor.

     2. The single terminal event carries EVERYTHING the executor
        accumulated: :issues + :ambiguities + ... (the final
        synthesized outputs) AND :generated-tree-raw + :iterations
        (the Phase 1 work products). Both heuristic-structural AND
        the LLM judges have valid inputs to grade.

   Net effect of applying :applies-to-completion-kinds #{:tree-iteration}
   to heuristic-structural: it never fires because no event of that
   kind ever arrives. The bench LOST a judge that previously fired
   usefully. That's a regression.

   Resolution chosen (2026-06-05): leave the defaults WITHOUT
   :applies-to-completion-kinds so all 5 judges attempt every
   repl-researcher terminal completion. The 4-arg resolver arity,
   the :completion-kind event field, and the command-handler
   derivation logic ALL REMAIN as infrastructure. A user can still
   attach a CUSTOM judge with :applies-to-completion-kinds on their
   own node — the filter only fires when the field is present.

   The tree-event grading use case (where we'd want
   heuristic-structural to fire on the campaign's :rlm/tree-generated
   event rather than only on the terminal sum-up) is filed as
   Gap-7b — `docs/issues/c2d-followups/Gap-7b-heuristic-structural-
   subscribes-to-rlm-tree-generated.md`. Gap-7b adds a SEPARATE
   processor subscribed to :rlm/tree-generated; it doesn't change
   this defaults list.

   What you SHOULD NOT do without Gap-7b shipping first:
     - Set :applies-to-completion-kinds #{:tree-iteration} on
       heuristic-structural here. There are no :tree-iteration
       events to fire on; this would silently regress.
     - Set :applies-to-completion-kinds #{:terminal} on the 4 LLM
       judges. That LOOKS correct, but it would only help once
       Gap-7b is shipped AND the executor stops carrying
       :generated-tree-raw on terminal events (otherwise nothing
       changes — both kinds of evidence are on terminal anyway).

   What you CAN do safely on top of this list:
     - Add new judge types via consumer code with their own
       :applies-to-completion-kinds.
     - Add a new judge type to this defaults list WITHOUT a
       kind filter (so it follows the same all-completions
       attach behavior as the existing 5).

   See also `Gap-7-judge-routing-by-completion-kind.md` for the
   full retrospective of what we tried and why we reverted."
  [{:judge-name "heuristic-structural"
    :judge-config {:type :heuristic-structural}}
   {:judge-name "grounding"
    :judge-config {:type :grounding}}
   {:judge-name "reasoning"
    :judge-config {:type :reasoning}}
   {:judge-name "completeness"
    :judge-config {:type :completeness}}
   {:judge-name "instruction-following"
    :judge-config {:type :instruction-following}}])

(defn applies-to?
  "Predicate: does this judge config apply to the given completion-kind?

   A judge with NO :applies-to-completion-kinds set applies to EVERY
   completion (backwards-compat — the default 5 judges are unfiltered and
   must keep firing on all completions, including nil-kind events).

   A judge that DOES specify :applies-to-completion-kinds is opting into
   kind-scoping: it applies ONLY when the event carries a matching kind. A
   nil-completion-kind event does NOT match — the judge asked for a
   specific kind and the event doesn't declare one.

   CJ-5b (live root cause): the coding-outcome judge is declared
   :applies-to-completion-kinds #{:terminal} so it fires EXACTLY ONCE, on
   the repl-researcher's terminal completion. A :blocked completion (a
   tool-permission pause, BEFORE any edit) carries :completion-kind nil —
   orc derives :terminal only for #{:success :failure :timeout}, not
   :blocked. The previous `(nil? completion-kind) true` branch let the
   kind-filtered judge fire on that premature :blocked completion and score
   a false band-1 'no file changes'. Checking the judge's filter FIRST
   fixes this without changing the unfiltered-judge default."
  [judge-config completion-kind]
  (let [applies (:applies-to-completion-kinds judge-config)]
    (cond
      (nil? applies) true
      (nil? completion-kind) false
      :else (contains? applies completion-kind))))

(def per-judge-timeout-ms
  "How long a single judge invocation may run before we give up on it.
   A slow/stuck judge can't block the others (each judge owns its own
   future) and now can't block the pubsub thread either (the whole
   resolve+run loop lives in the effect's background future)."
  60000)

(defn judge-purposes [judge-config]
  (if-let [purposes (not-empty (set (:purposes judge-config)))]
    purposes
    default-purposes))

(defn distinct-by-first
  "Transducer: drop entries whose first element was already seen."
  []
  (fn [rf]
    (let [seen (volatile! #{})]
      (fn
        ([] (rf))
        ([acc] (rf acc))
        ([acc [k :as entry]]
         (if (contains? @seen k)
           acc
           (do (vswap! seen conj k) (rf acc entry))))))))

(defn judge-revision-number
  "The revision of the definition in force: the judges read model's, else 1 (the
   built-in defaults are never declared, so they have a single definition)."
  [judge-config]
  (or (:revision-number judge-config) 1))

(defn judged-node-id
  "The node a completion is judged and measured as. A run of a published version
   has nodes of its own, each recording the draft node it was published from
   (read from the run's durable tick execution context): that draft node is the
   one whose judges monitor it. Any other completion is the node it names."
  [ctx {:keys [tick-id node-id]}]
  (or (some-> (orc/get-tick-execution-context ctx tick-id)
              (get-in [:nodes-by-id node-id :source-node-id]))
      node-id))

(defn ->assessment-requested-event
  "Pure builder of the `:evaluation/assessment-requested` event for `judge` (an
   effective-judges entry) assessing the completion `completion`. `node-id` is
   the node judged (the source node of a published run's node);
   `version-number` the published sheet version the run executed, nil for a
   draft. `depends-on` (the assessment ids the judging must wait for) is
   recorded on the request and indexed by a [:depends-on id] tag, so an outcome
   can find the requests waiting on it."
  [completion assessment-id {:keys [judge-name judge-config]} node-version node-id version-number depends-on]
  (let [subject (:event/id completion)
        sheet-id (:sheet-id completion)
        run-node-id (:node-id completion)
        tick-id (:tick-id completion)
        exec-context (second (orc/value-log-execution-key completion))]
    (es/->event
     {:type :evaluation/assessment-requested
      :tags (into #{[:sheet sheet-id] [:node node-id] [:tick tick-id]
                    [:assessment assessment-id] [:subject subject]}
                  (map (fn [dependency] [:depends-on dependency]))
                  depends-on)
      :body (cond-> {:assessment-id assessment-id
                     :sheet-id sheet-id
                     :node-id node-id
                     :tick-id tick-id
                     :subject-completion-id subject
                     :judge-name judge-name
                     :judge-revision-number (judge-revision-number judge-config)
                     :judge-type (:type judge-config)
                     :purposes (judge-purposes judge-config)
                     :requested-at (str (time/now))}
              (seq exec-context) (assoc :exec-context exec-context)
              (not= node-id run-node-id) (assoc :run-node-id run-node-id)
              node-version (assoc :node-version node-version)
              version-number (assoc :version-number version-number)
              (seq depends-on) (assoc :depends-on (vec depends-on)))})))

(defn terminal-event? [event]
  (contains? terminal-event-types (:event/type event)))

(defn resolve-judge-config
  "The definition in force for the revision a request names: the judges read
   model's current entry when it is that revision, else the entry rebuilt from
   that revision's recorded config. A built-in default (never declared) has its
   one definition. nil when the judge or revision is unknown."
  [context {:keys [sheet-id judge-name judge-revision-number purposes]}]
  (if-let [declared (get (orc/get-judges context sheet-id) judge-name)]
    (if (= judge-revision-number (:revision-number declared))
      declared
      (when-let [revision (some #(when (= judge-revision-number (:revision-number %)) %)
                                (:revisions declared))]
        (let [config (:judge-config revision)]
          (merge (cond-> config
                   (:sheet-id config) (assoc :eval-sheet-id (:sheet-id config)))
                 {:sheet-id sheet-id
                  :judge-name judge-name
                  :revision-number judge-revision-number
                  :purposes purposes}))))
    (some #(when (= judge-name (:judge-name %)) (:judge-config %)) default-judges)))

(defn failed-outcome [reason message]
  {:status :failed :reason reason :message message})

(def failed-statuses
  "Completion statuses of an execution that did not produce what it was asked
   to: recorded, never graded (unless the judge opts in with :assess-failures?)."
  #{:failure :timeout :blocked})

(defn subject-failed-outcome
  "The outcome of an assessment whose subject execution failed: ungradable, with
   the node's own failure kind and error in the message. No judge runs."
  [{:keys [status failure-kind error]}]
  {:status :ungradable
   :reason :subject-failed
   :message (str "The assessed execution did not succeed (status " (name status)
                 (when failure-kind (str ", failure kind " (name failure-kind)))
                 (when error (str ", error: " error))
                 "), so there is no output to grade.")})

(defn heuristic-outcome
  "The deterministic structural judge as an outcome: it grades the tree the node
   emitted, and a node that emitted none is ungradable - never scored."
  [trace-data]
  (if-let [result (invoke-heuristic-structural trace-data)]
    (merge {:status :scored} (select-keys result [:score :feedback :dimensions]))
    {:status :ungradable
     :reason :no-tree-emitted
     :message (str "The assessed node wrote no :generated-tree-raw, so there is no "
                   "tree structure to grade.")}))

(defn ->record-assessment-outcome-command
  [request {:keys [outcome judge-config]}]
  (merge {:command/id (random-uuid)
          :command/timestamp (time/now)
          :command/name :evaluation/record-assessment-outcome
          :assessment-id (:assessment-id request)}
         (into {} (remove (comp nil? val))
               (assoc (select-keys outcome [:status :band :score :feedback :dimensions
                                            :band-distribution :reason :message
                                            :model-provenance :judge-tick-id])
                      :judge-config judge-config))))

(def judging-in-flight
  "The assessments this process is judging right now. Judging starts from two
   places - a request, and the outcome of the last assessment a request waited
   on - and an assessment is judged once however the two interleave."
  (atom #{}))

(defn claim-judging!
  "Atomically claim `assessment-id` for judging: true for exactly one caller."
  [assessment-id]
  (let [[before _] (swap-vals! judging-in-flight conj assessment-id)]
    (not (contains? before assessment-id))))

(def tree-shape-judge-types
  "Set of judge types that grade tree SHAPE from a raw-dsl. The
   :rlm/tree-generated processor only runs judges whose :type belongs
   to this set. Other judges (grounding, reasoning, completeness,
   instruction-following) grade OUTPUT content and run on terminal
   completions where the synthesized output is available."
  #{:heuristic-structural})

(defmulti judge-scores*
  (fn [_state event] (:event/type event)))

(defmethod judge-scores* :default [state _] state)

(defmethod judge-scores* :judge/score-emitted
  [state event]
  (let [key [(:sheet-id event) (:tick-id event) (:node-id event)]
        entry (select-keys event [:sheet-id :tick-id :node-id
                                  :judge-name :judge-config
                                  :score :feedback :dimensions :emitted-at
                                  :model-provenance])]
    (update state key (fnil conj []) entry)))

(defn judge-scores
  "Build the judge-scores state from a seq of events."
  [initial-state events]
  (reduce judge-scores* initial-state events))

;; --- Alerts ---

(def signal-types
  #{:evaluation/performance-threshold-crossed
    :evaluation/performance-threshold-recovered
    :evaluation/performance-coverage-degraded
    :evaluation/performance-coverage-restored})

(def signal-state
  "What the last signal of a cell says about it now."
  {:evaluation/performance-threshold-crossed :crossed
   :evaluation/performance-coverage-degraded :degraded
   :evaluation/performance-threshold-recovered :healthy
   :evaluation/performance-coverage-restored :healthy})

(defn window-stats
  "The statistics of the last `window` outcomes of `series`, or nil while there are
   fewer than `window`."
  [series window]
  (when (>= (count series) window)
    (let [in-window (take-last window series)
          scores (keep #(when (= :scored (:status %)) (:score %)) in-window)]
      {:window window
       :scored (count scores)
       :trailing-score (when (seq scores) (/ (reduce + 0.0 scores) (count scores)))
       :coverage-ratio (/ (double (count scores)) window)})))

(defn next-signal
  "The signal (an event type) the window calls for given the cell's `state`
   (:crossed, :degraded or :healthy), or nil when nothing changed."
  [{:keys [below min-coverage]} {:keys [trailing-score coverage-ratio]} state]
  (cond
    (< coverage-ratio min-coverage)
    (when (not= :degraded state) :evaluation/performance-coverage-degraded)

    (< trailing-score below)
    (when (not= :crossed state) :evaluation/performance-threshold-crossed)

    :else
    (case state
      :crossed :evaluation/performance-threshold-recovered
      :degraded :evaluation/performance-coverage-restored
      nil)))

(defn series-through
  "The outcomes of the cell up to and including the one of `assessment-id`, or nil
   when it is not among them."
  [series assessment-id]
  (let [idx (first (keep-indexed #(when (= assessment-id (:assessment-id %2)) %1) series))]
    (when idx (subvec (vec series) 0 (inc idx)))))

;; --- Interface ---

(defn current-judge-config
  "Return the resolved provider/model used by retained synchronous judges.
   Consumers may persist this configuration to preserve judging across restart."
  []
  {:provider judges/*judge-provider* :model judges/*judge-model*})

(defn call-with-judge-config
  "Invoke f with the supplied provider/model. Other judge bindings, including
   mock mode, remain unchanged; futures created by f inherit these bindings."
  [config f]
  (binding [judges/*judge-provider* (or (:provider config) judges/*judge-provider*)
            judges/*judge-model* (or (:model config) judges/*judge-model*)]
    (f)))
