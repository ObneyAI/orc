(ns ai.obney.orc.evaluation.core.sheets
  "ORC Sheet definitions for running evaluation workflows.

   This namespace provides pre-built evaluation sheets that leverage
   the full ORC/Grain infrastructure for tracing and observability.

   ## Usage

   ```clojure
   (require '[ai.obney.orc.evaluation.core.sheets :as eval-sheets])
   (require '[ai.obney.orc.orc-service.interface :as sheet])

   ;; Build the evaluation suite (idempotent)
   (def suite-id (sheet/build-workflow! ctx (eval-sheets/evaluation-suite)))

   ;; Execute on a trace
   (sheet/execute ctx suite-id {:trace-data {...}})
   ```

   ## Available Sheets

   - `grounding-judge-sheet` - Single judge for grounding/hallucination
   - `instruction-judge-sheet` - Single judge for instruction following
   - `reasoning-judge-sheet` - Single judge for reasoning quality
   - `completeness-judge-sheet` - Single judge for completeness
   - `evaluation-suite` - Full multi-dimension evaluation with aggregation"
  (:require [ai.obney.orc.orc-service.interface :as dsl]))

;; =============================================================================
;; Blackboard Schemas
;; =============================================================================

(def ^:private trace-input-value-schema
  [:schema
   {:registry
    {::trace-input
     [:or :nil :string :int :double :boolean :keyword
      [:vector [:ref ::trace-input]]
      [:map-of [:or :keyword :string] [:ref ::trace-input]]]}}
   [:ref ::trace-input]])

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

(def GroundingResultSchema
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

(def ReasoningResultSchema
  "Schema for reasoning quality judge result"
  [:map
   [:score :double]
   [:reasoning-strengths [:vector :string]]
   [:reasoning-weaknesses [:vector :string]]
   [:feedback :string]])

(def CompletenessResultSchema
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

;; =============================================================================
;; Individual Judge Sheets
;; =============================================================================

(defn grounding-judge-sheet
  "Sheet that evaluates source grounding / hallucination detection.

   Input: trace-data (TraceDataSchema)
   Output: grounding-result (GroundingResultSchema)"
  []
  (dsl/workflow "evaluation-grounding-judge"
    (dsl/blackboard
     {:trace-data TraceDataSchema
      :grounding-result GroundingResultSchema})

    (dsl/code "evaluate-grounding"
      :fn "ai.obney.orc.evaluation.core.judges/grounding-judge"
      :reads [:trace-data]
      :writes [:grounding-result])))

(defn instruction-judge-sheet
  "Sheet that evaluates instruction following.

   Input: trace-data (TraceDataSchema)
   Output: instruction-result (InstructionResultSchema)"
  []
  (dsl/workflow "evaluation-instruction-judge"
    (dsl/blackboard
     {:trace-data TraceDataSchema
      :instruction-result InstructionResultSchema})

    (dsl/code "evaluate-instruction"
      :fn "ai.obney.orc.evaluation.core.judges/instruction-following-judge"
      :reads [:trace-data]
      :writes [:instruction-result])))

(defn reasoning-judge-sheet
  "Sheet that evaluates reasoning quality.

   Input: trace-data (TraceDataSchema)
   Output: reasoning-result (ReasoningResultSchema)"
  []
  (dsl/workflow "evaluation-reasoning-judge"
    (dsl/blackboard
     {:trace-data TraceDataSchema
      :reasoning-result ReasoningResultSchema})

    (dsl/code "evaluate-reasoning"
      :fn "ai.obney.orc.evaluation.core.judges/reasoning-judge"
      :reads [:trace-data]
      :writes [:reasoning-result])))

(defn completeness-judge-sheet
  "Sheet that evaluates response completeness.

   Input: trace-data (TraceDataSchema)
   Output: completeness-result (CompletenessResultSchema)"
  []
  (dsl/workflow "evaluation-completeness-judge"
    (dsl/blackboard
     {:trace-data TraceDataSchema
      :completeness-result CompletenessResultSchema})

    (dsl/code "evaluate-completeness"
      :fn "ai.obney.orc.evaluation.core.judges/completeness-judge"
      :reads [:trace-data]
      :writes [:completeness-result])))

;; =============================================================================
;; Full Evaluation Suite
;; =============================================================================

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
  (dsl/workflow "evaluation-suite"
    (dsl/blackboard
     {:trace-data TraceDataSchema
      :grounding-result GroundingResultSchema
      :instruction-result InstructionResultSchema
      :reasoning-result ReasoningResultSchema
      :completeness-result CompletenessResultSchema
      :aggregate-result AggregateResultSchema})

    (dsl/sequence "main"
      ;; Run all judges in parallel for efficiency
      (dsl/parallel "run-judges"
        (dsl/code "grounding-judge"
          :fn "ai.obney.orc.evaluation.core.judges/grounding-judge"
          :reads [:trace-data]
          :writes [:grounding-result])

        (dsl/code "instruction-judge"
          :fn "ai.obney.orc.evaluation.core.judges/instruction-following-judge"
          :reads [:trace-data]
          :writes [:instruction-result])

        (dsl/code "reasoning-judge"
          :fn "ai.obney.orc.evaluation.core.judges/reasoning-judge"
          :reads [:trace-data]
          :writes [:reasoning-result])

        (dsl/code "completeness-judge"
          :fn "ai.obney.orc.evaluation.core.judges/completeness-judge"
          :reads [:trace-data]
          :writes [:completeness-result]))

      ;; Aggregate all results into final score
      (dsl/code "aggregate"
        :fn "ai.obney.orc.evaluation.core.judges/aggregate-dimensions"
        :reads [:grounding-result :instruction-result
                :reasoning-result :completeness-result]
        :writes [:aggregate-result]))))

;; =============================================================================
;; Batch Evaluation Suite
;; =============================================================================

(defn batch-evaluation-suite
  "Evaluation suite for processing multiple traces.

   Uses map-each to iterate over a list of traces and evaluate each one.

   Input:
     traces: Vector of trace-data maps

   Output:
     results: Vector of aggregate-result maps

   Example:
     (def batch-id (sheet/build-workflow! ctx (batch-evaluation-suite)))
     (sheet/execute ctx batch-id
       {:traces [{:inputs {...} :response \"...\" :instruction \"...\"}
                 {:inputs {...} :response \"...\" :instruction \"...\"}]})"
  []
  (dsl/workflow "evaluation-batch-suite"
    (dsl/blackboard
     {:traces [:vector TraceDataSchema]
      :current-trace TraceDataSchema
      :grounding-result GroundingResultSchema
      :instruction-result InstructionResultSchema
      :reasoning-result ReasoningResultSchema
      :completeness-result CompletenessResultSchema
      :current-aggregate AggregateResultSchema
      :results [:vector AggregateResultSchema]})

    (dsl/map-each "evaluate-all"
      :from :traces
      :as :current-trace
      :into :results
      :parallel 3  ;; Process 3 traces concurrently

      (dsl/sequence "evaluate-one"
        ;; Run all judges in parallel
        (dsl/parallel "run-judges"
          (dsl/code "grounding-judge"
            :fn "ai.obney.orc.evaluation.core.judges/grounding-judge"
            :reads [:current-trace]
            :writes [:grounding-result])

          (dsl/code "instruction-judge"
            :fn "ai.obney.orc.evaluation.core.judges/instruction-following-judge"
            :reads [:current-trace]
            :writes [:instruction-result])

          (dsl/code "reasoning-judge"
            :fn "ai.obney.orc.evaluation.core.judges/reasoning-judge"
            :reads [:current-trace]
            :writes [:reasoning-result])

          (dsl/code "completeness-judge"
            :fn "ai.obney.orc.evaluation.core.judges/completeness-judge"
            :reads [:current-trace]
            :writes [:completeness-result]))

        ;; Aggregate
        (dsl/code "aggregate"
          :fn "ai.obney.orc.evaluation.core.judges/aggregate-dimensions"
          :reads [:grounding-result :instruction-result
                  :reasoning-result :completeness-result]
          :writes [:current-aggregate])))))

;; =============================================================================
;; Selective Judge Suite
;; =============================================================================

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

        ;; Build judge nodes based on selection
        judge-nodes (cond-> []
                      include-grounding?
                      (conj (dsl/code "grounding-judge"
                              :fn "ai.obney.orc.evaluation.core.judges/grounding-judge"
                              :reads [:trace-data]
                              :writes [:grounding-result]))

                      include-instruction?
                      (conj (dsl/code "instruction-judge"
                              :fn "ai.obney.orc.evaluation.core.judges/instruction-following-judge"
                              :reads [:trace-data]
                              :writes [:instruction-result]))

                      include-reasoning?
                      (conj (dsl/code "reasoning-judge"
                              :fn "ai.obney.orc.evaluation.core.judges/reasoning-judge"
                              :reads [:trace-data]
                              :writes [:reasoning-result]))

                      include-completeness?
                      (conj (dsl/code "completeness-judge"
                              :fn "ai.obney.orc.evaluation.core.judges/completeness-judge"
                              :reads [:trace-data]
                              :writes [:completeness-result])))

        ;; Build blackboard schema based on selection
        bb-schema (cond-> {:trace-data TraceDataSchema
                           :aggregate-result AggregateResultSchema}
                    include-grounding?
                    (assoc :grounding-result GroundingResultSchema)

                    include-instruction?
                    (assoc :instruction-result InstructionResultSchema)

                    include-reasoning?
                    (assoc :reasoning-result ReasoningResultSchema)

                    include-completeness?
                    (assoc :completeness-result CompletenessResultSchema))

        ;; Build aggregator reads
        agg-reads (cond-> []
                    include-grounding? (conj :grounding-result)
                    include-instruction? (conj :instruction-result)
                    include-reasoning? (conj :reasoning-result)
                    include-completeness? (conj :completeness-result))]

    (dsl/workflow (str "evaluation-selective-" (clojure.string/join "-" (map name judge-keys)))
      (dsl/blackboard bb-schema)

      (dsl/sequence "main"
        (apply dsl/parallel "run-judges" judge-nodes)

        (dsl/code "aggregate"
          :fn "ai.obney.orc.evaluation.core.judges/aggregate-dimensions"
          :reads agg-reads
          :writes [:aggregate-result])))))
