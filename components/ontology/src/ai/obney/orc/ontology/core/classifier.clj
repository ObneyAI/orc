(ns ai.obney.orc.ontology.core.classifier
  "Evaluation classifier - maps evaluation dimensions to failure ontology URIs.

   The evaluation component produces scores across 4 dimensions:
   - Grounding: Is output supported by input context?
   - Instruction Following: Did it follow given instructions?
   - Reasoning: Is the logic sound?
   - Completeness: Is anything missing?

   This classifier maps low scores to specific failure URIs and attempts
   to identify more specific subtypes based on feedback text analysis."
  (:require [clojure.string :as str]
            [ai.obney.orc.ontology.core.static-ontology :as static]))

;; =============================================================================
;; Dimension to Failure Mapping
;; =============================================================================

(def dimension->failure-uri
  "Maps evaluation dimension names to root failure concept URIs."
  {"Grounding" "failure:Grounding"
   "Source Grounding" "failure:Grounding"
   "Instruction Following" "failure:InstructionFollowing"
   "Reasoning" "failure:Reasoning"
   "Reasoning Quality" "failure:Reasoning"
   "Completeness" "failure:Completeness"})

;; =============================================================================
;; Subtype Detection via Indicator Matching
;; =============================================================================

(def subtype-indicators
  "Maps failure subtypes to their indicator patterns.
   Indicators are phrases that suggest this specific failure type."
  {;; Grounding subtypes
   "failure:Hallucination" ["hallucinated" "made up" "invented" "fabricated" "not in source"
                            "no evidence" "unsupported claim" "imagined"]
   "failure:FactHallucination" ["wrong number" "incorrect date" "false claim" "wrong fact"
                                "incorrect value" "made up statistic"]
   "failure:RelationshipHallucination" ["no relationship" "invented connection" "false link"
                                        "wrong relationship" "doesn't connect"]
   "failure:Contradiction" ["contradicts" "opposite" "inconsistent" "conflicts with"
                            "disagrees with" "contrary to"]
   "failure:Misattribution" ["wrong source" "misattributed" "credited to wrong"
                             "incorrect attribution" "wrong author"]

   ;; Instruction Following subtypes
   "failure:FormatViolation" ["wrong format" "invalid JSON" "not markdown" "malformed"
                              "invalid syntax" "format error" "parse error"]
   "failure:ConstraintViolation" ["too long" "too short" "exceeded limit" "out of range"
                                  "over limit" "under limit" "word count"]
   "failure:RequirementMissed" ["missing required" "did not include" "omitted required"
                                "forgot to include" "missing mandatory"]
   "failure:ScopeViolation" ["out of scope" "beyond request" "unasked for" "off topic"
                             "irrelevant" "not requested"]

   ;; Reasoning subtypes
   "failure:LogicalGap" ["missing step" "doesn't follow" "unexplained leap" "gap in logic"
                         "skipped reasoning" "unclear connection"]
   "failure:UnjustifiedLeap" ["unjustified" "no support" "leap in logic" "unsupported conclusion"
                              "baseless claim" "unsubstantiated"]
   "failure:CircularReasoning" ["circular" "tautology" "assumes conclusion" "begging the question"
                                "self-referential" "proves itself"]
   "failure:FalseEquivalence" ["false equivalence" "not comparable" "unlike comparison"
                               "apples and oranges" "incomparable"]

   ;; Completeness subtypes
   "failure:MissingEntity" ["missing" "omitted" "not included" "left out" "forgot"
                            "skipped" "absent"]
   "failure:InsufficientDetail" ["too brief" "lacks detail" "superficial" "shallow"
                                 "not enough detail" "needs more"]
   "failure:TruncatedOutput" ["truncated" "cut off" "incomplete" "ended abruptly"
                              "stopped early" "unfinished"]
   "failure:PartialCoverage" ["partial" "only covered" "missed aspects" "incomplete coverage"
                              "didn't address"]})

(defn- score-indicator-match
  "Score how well feedback text matches a failure subtype's indicators.
   Returns a score between 0 and 1."
  [feedback indicators]
  (when (and (seq feedback) (seq indicators))
    (let [feedback-lower (str/lower-case feedback)
          matches (filter #(str/includes? feedback-lower (str/lower-case %)) indicators)
          match-count (count matches)]
      (if (pos? match-count)
        (min 1.0 (* match-count 0.3))  ; Each match adds 0.3, capped at 1.0
        0.0))))

(defn find-specific-subtype
  "Find the most specific failure subtype based on feedback text.
   Returns the subtype URI or nil if no specific subtype matches."
  [base-failure-uri feedback]
  (when (and base-failure-uri (seq feedback))
    ;; Get all subtypes that are narrower than the base failure
    (let [narrower-uris (map :uri (static/get-narrower-concepts base-failure-uri))
          ;; Also check grandchildren (e.g., FactHallucination under Hallucination)
          all-subtypes (concat narrower-uris
                               (mapcat #(map :uri (static/get-narrower-concepts %)) narrower-uris))
          ;; Score each subtype against the feedback
          scored (->> all-subtypes
                      (map (fn [uri]
                             (when-let [indicators (get subtype-indicators uri)]
                               {:uri uri
                                :score (score-indicator-match feedback indicators)})))
                      (filter some?)
                      (filter #(pos? (:score %)))
                      (sort-by :score >))]
      ;; Return the best match if score is above threshold
      (when-let [best (first scored)]
        (when (>= (:score best) 0.3)
          (:uri best))))))

;; =============================================================================
;; Main Classification Function
;; =============================================================================

(defn- dimension->confidence
  "Convert dimension score to failure confidence.
   Lower scores = higher confidence in the failure."
  [score threshold]
  (if (< score threshold)
    (- 1.0 (/ score threshold))  ; 0 score = 1.0 confidence, threshold score = 0 confidence
    0.0))

(defn classify-evaluation
  "Classify evaluation results into failure ontology URIs.

   Arguments:
   - evaluation-result: Map with :score and :dimensions
     - :score - Overall score (0.0-1.0)
     - :dimensions - Vector of {:name :score :feedback}

   Options:
   - :threshold - Score below which to flag as failure (default 0.7)
   - :min-confidence - Minimum confidence to include (default 0.1)

   Returns:
   {:failures [{:uri \"failure:Hallucination\"
                :confidence 0.8
                :evidence \"feedback text\"
                :dimension \"Grounding\"}]
    :primary-failure-uri \"failure:Hallucination\"
    :overall-score 0.5}"
  [evaluation-result & [{:keys [threshold min-confidence]
                         :or {threshold 0.7
                              min-confidence 0.1}}]]
  (let [{:keys [score dimensions]} evaluation-result
        failures (->> dimensions
                      (filter #(< (:score %) threshold))
                      (map (fn [{:keys [name score feedback]}]
                             (let [base-uri (get dimension->failure-uri name)
                                   confidence (dimension->confidence score threshold)
                                   subtype (find-specific-subtype base-uri feedback)]
                               {:uri (or subtype base-uri)
                                :base-uri base-uri
                                :subtype-uri subtype
                                :confidence confidence
                                :evidence feedback
                                :dimension name
                                :dimension-score score})))
                      (filter #(>= (:confidence %) min-confidence))
                      (sort-by :confidence >)
                      vec)]
    {:failures failures
     :primary-failure-uri (some-> failures first :uri)
     :overall-score score
     :threshold threshold}))

;; =============================================================================
;; Batch Classification
;; =============================================================================

(defn classify-trace-evaluations
  "Classify multiple trace evaluations, grouping failures by type.
   Useful for aggregating patterns across many executions.

   Arguments:
   - evaluations: Seq of {:trace-id :evaluation-result}

   Returns:
   {:by-failure {\"failure:Hallucination\" [{:trace-id ... :confidence ...}]}
    :summary {:total-traces N :failed-traces N :by-dimension {...}}}"
  [evaluations & [opts]]
  (let [classified (map (fn [{:keys [trace-id evaluation-result]}]
                          {:trace-id trace-id
                           :classification (classify-evaluation evaluation-result opts)})
                        evaluations)
        ;; Group by failure URI
        by-failure (->> classified
                        (mapcat (fn [{:keys [trace-id classification]}]
                                  (map (fn [f]
                                         (assoc f :trace-id trace-id))
                                       (:failures classification))))
                        (group-by :uri))
        ;; Summary statistics
        total (count classified)
        failed (count (filter #(seq (-> % :classification :failures)) classified))
        by-dimension (->> classified
                          (mapcat #(-> % :classification :failures))
                          (group-by :dimension)
                          (map (fn [[dim fs]]
                                 [dim {:count (count fs)
                                       :avg-confidence (/ (reduce + (map :confidence fs)) (count fs))}]))
                          (into {}))]
    {:by-failure by-failure
     :summary {:total-traces total
               :failed-traces failed
               :failure-rate (when (pos? total) (double (/ failed total)))
               :by-dimension by-dimension}}))

;; =============================================================================
;; Severity Estimation
;; =============================================================================

(def failure-severity-weights
  "Base severity weights for failure categories.
   Grounding and Reasoning failures are typically more severe."
  {"failure:Grounding" :high
   "failure:InstructionFollowing" :medium
   "failure:Reasoning" :high
   "failure:Completeness" :medium
   ;; Specific subtypes
   "failure:Hallucination" :critical
   "failure:Contradiction" :high
   "failure:CircularReasoning" :high
   "failure:TruncatedOutput" :low})

(defn estimate-severity
  "Estimate severity level for a classified failure.
   Returns :low, :medium, :high, or :critical"
  [{:keys [uri confidence dimension-score]}]
  (let [base-severity (get failure-severity-weights uri
                           (get failure-severity-weights
                                (get dimension->failure-uri (:dimension uri))
                                :medium))]
    ;; Adjust based on confidence and score
    (cond
      (and (= base-severity :critical) (>= confidence 0.8)) :critical
      (and (#{:high :critical} base-severity) (>= confidence 0.7)) :high
      (and (>= confidence 0.5) (<= dimension-score 0.3)) :high
      (>= confidence 0.3) :medium
      :else :low)))

;; =============================================================================
;; Trigger Extraction
;; =============================================================================

(defn extract-triggers
  "Extract trigger phrases from failure evidence.
   Triggers are short phrases that describe what caused the failure."
  [failures]
  (->> failures
       (mapcat (fn [{:keys [evidence]}]
                 (when (seq evidence)
                   ;; Extract phrases that describe the issue
                   ;; Simple heuristic: sentences containing failure indicators
                   (let [sentences (str/split evidence #"[.!?]")]
                     (->> sentences
                          (filter #(> (count %) 10))  ; Skip very short fragments
                          (filter #(< (count %) 200)) ; Skip very long fragments
                          (map str/trim)
                          (take 3))))))
       distinct
       vec))
