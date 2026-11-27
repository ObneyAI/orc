(ns ai.obney.orc.evaluation.interface.schemas
  "Schema definitions for evaluation commands, events, and queries.

   Defines the contracts for evaluation-related data structures used
   throughout the Grain event sourcing system."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))


(def DimensionScore
  "Schema for a single evaluation dimension result."
  [:map
   [:name :string]
   [:weight :double]
   [:score :double]
   [:feedback :string]])

(def AssessmentDimension
  "A dimension of a scored assessment. Feedback is optional: a score-only rubric
   (feedback :none) has none, and none is ever invented."
  [:map
   [:name :string]
   [:weight :double]
   [:score :double]
   [:feedback {:optional true} :string]])

(def PerformanceSignal
  "The body of every opt-in performance signal: where it happened (the node
   version under a judge revision), the assessment whose outcome tipped it, and
   the window it judged: the last `window` outcomes, `scored` of which were
   scored, `trailing-score` their mean, `coverage-ratio` scored over outcomes."
  [:map
   [:sheet-id :uuid]
   [:node-id :uuid]
   [:node-version {:optional true} :string]
   [:judge-name :string]
   [:judge-revision-number :int]
   [:assessment-id :uuid]
   [:alert :map]
   [:window :int]
   [:scored :int]
   [:trailing-score {:optional true} number?]
   [:coverage-ratio number?]
   [:signalled-at :string]])

;; =============================================================================
;; Events
;; =============================================================================

(defschemas events
  {
   :judge/score-emitted
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:judge-name :string]
    [:judge-config :map]
    [:score [:and number? [:>= 0.0] [:<= 1.0]]]
    [:feedback :string]
    [:dimensions [:vector DimensionScore]]
    [:model-provenance {:optional true} [:maybe :map]]
    [:emitted-at :string]
    [:assessment-id {:optional true} :uuid]
    [:band {:optional true} :int]
    [:revision-number {:optional true} :int]]

   :evaluation/assessment-requested
   [:map
    [:assessment-id :uuid]
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:run-node-id {:optional true} :uuid]
    [:tick-id :uuid]
    [:subject-completion-id :uuid]
    [:exec-context {:optional true} :map]
    [:judge-name :string]
    [:judge-revision-number :int]
    [:judge-type :keyword]
    [:purposes [:set :keyword]]
    [:requested-at :string]
    [:node-version {:optional true} :string]
    [:version-number {:optional true} :int]
    [:depends-on {:optional true} [:vector :uuid]]]

   :evaluation/assessment-scored
   [:map
    [:assessment-id :uuid]
    [:sheet-id {:optional true} :uuid]
    [:node-id {:optional true} :uuid]
    [:node-version {:optional true} :string]
    [:judge-name {:optional true} :string]
    [:judge-revision-number {:optional true} :int]
    [:alert {:optional true} :map]
    [:band {:optional true} :int]
    [:score [:and number? [:>= 0.0] [:<= 1.0]]]
    [:feedback {:optional true} :string]
    [:dimensions [:vector AssessmentDimension]]
    [:band-distribution {:optional true} :map]
    [:model-provenance {:optional true} [:vector :map]]
    [:judge-tick-id {:optional true} :uuid]]

   :evaluation/assessment-failed
   [:map
    [:assessment-id :uuid]
    [:sheet-id {:optional true} :uuid]
    [:node-id {:optional true} :uuid]
    [:node-version {:optional true} :string]
    [:judge-name {:optional true} :string]
    [:judge-revision-number {:optional true} :int]
    [:alert {:optional true} :map]
    [:reason :keyword]
    [:message :string]
    [:model-provenance {:optional true} [:vector :map]]
    [:judge-tick-id {:optional true} :uuid]]

   :evaluation/assessment-ungradable
   [:map
    [:assessment-id :uuid]
    [:sheet-id {:optional true} :uuid]
    [:node-id {:optional true} :uuid]
    [:node-version {:optional true} :string]
    [:judge-name {:optional true} :string]
    [:judge-revision-number {:optional true} :int]
    [:alert {:optional true} :map]
    [:reason :keyword]
    [:message :string]
    [:band-distribution {:optional true} :map]
    [:model-provenance {:optional true} [:vector :map]]
    [:judge-tick-id {:optional true} :uuid]]

   :evaluation/performance-threshold-crossed PerformanceSignal
   :evaluation/performance-threshold-recovered PerformanceSignal
   :evaluation/performance-coverage-degraded PerformanceSignal
   :evaluation/performance-coverage-restored PerformanceSignal

   :judge/composite-score-computed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:composite-score {:optional true} [:and number? [:>= 0.0] [:<= 1.0]]]
    [:subject-completion-id {:optional true} :uuid]
    [:contributing-judges [:vector [:map
                                    [:judge-name :string]
                                    [:score number?]
                                    [:weight number?]]]]
    [:coverage {:optional true} [:map
                                 [:expected :int] [:scored :int]
                                 [:failed :int] [:ungradable :int]]]
    [:partial {:optional true} :boolean]
    [:purpose {:optional true} :keyword]
    [:emitted-at :string]]})

;; =============================================================================
;; Commands
;; =============================================================================

(defschemas commands
  {
   :evaluation/record-judge-score
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:judge-name :string]
    [:judge-config :map]
    [:score [:and number? [:>= 0.0] [:<= 1.0]]]
    [:feedback :string]
    [:dimensions [:vector DimensionScore]]
    [:model-provenance {:optional true} [:maybe :map]]
    [:emitted-at {:optional true} :string]]

   :evaluation/record-assessment-outcome
   [:map
    [:assessment-id :uuid]
    [:status [:enum :scored :failed :ungradable]]
    [:band {:optional true} :int]
    [:score {:optional true} [:and number? [:>= 0.0] [:<= 1.0]]]
    [:feedback {:optional true} :string]
    [:dimensions {:optional true} [:vector AssessmentDimension]]
    [:band-distribution {:optional true} :map]
    [:reason {:optional true} :keyword]
    [:message {:optional true} :string]
    [:model-provenance {:optional true} [:vector :map]]
    [:judge-tick-id {:optional true} :uuid]
    [:judge-config {:optional true} :map]]

   :evaluation/record-composite-score
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:composite-score {:optional true} [:and number? [:>= 0.0] [:<= 1.0]]]
    [:subject-completion-id {:optional true} :uuid]
    [:contributing-judges [:vector [:map
                                    [:judge-name :string]
                                    [:score number?]
                                    [:weight number?]]]]
    [:coverage {:optional true} [:map
                                 [:expected :int] [:scored :int]
                                 [:failed :int] [:ungradable :int]]]
    [:partial {:optional true} :boolean]
    [:purpose {:optional true} :keyword]
    [:emitted-at {:optional true} :string]]})

;; =============================================================================
;; Read models and queries
;; =============================================================================
