(ns ai.obney.orc.evaluation.interface.schemas
  "Schema definitions for evaluation commands, events, and queries.

   Defines the contracts for evaluation-related data structures used
   throughout the Grain event sourcing system."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Dimension Schema (shared)
;; =============================================================================

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

;; =============================================================================
;; Events
;; =============================================================================

(defschemas events
  {;; Gap-1: unified evaluator protocol — per-event evaluator output.
   ;; Emitted by the judge-runtime processor (subscribed to
   ;; :sheet/node-execution-completed) for each attached judge that
   ;; runs successfully. The consolidator (under Gap-3) consumes these
   ;; events alongside raw execution evidence to update Living
   ;; Description bodies.
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
    ;; S7 (HistoryIsAdditive): the assessment this score records, the rubric
    ;; band chosen, and the judge revision that graded. Absent on every record
    ;; written before assessments existed.
    [:assessment-id {:optional true} :uuid]
    [:band {:optional true} :int]
    [:revision-number {:optional true} :int]]

   ;; S7: a judgment is a durable ASSESSMENT. It is REQUESTED (durably, before any
   ;; judging) by the completion it assesses, then ends in exactly one of
   ;; scored / failed / ungradable. Identity = (subject completion, judge, judge
   ;; revision); the id is a name-based UUID of that triple, so replay yields the
   ;; same id.
   :evaluation/assessment-requested
   [:map
    [:assessment-id :uuid]
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:tick-id :uuid]
    [:subject-completion-id :uuid]
    [:exec-context {:optional true} :map]
    [:judge-name :string]
    [:judge-revision-number :int]
    [:judge-type :keyword]
    [:purposes [:set :keyword]]
    [:requested-at :string]
    ;; Room for later slices (node version pinning, composite dependencies).
    [:node-version {:optional true} :int]
    [:depends-on {:optional true} [:vector :uuid]]]

   :evaluation/assessment-scored
   [:map
    [:assessment-id :uuid]
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
    [:reason :keyword]
    [:message :string]
    [:model-provenance {:optional true} [:vector :map]]
    [:judge-tick-id {:optional true} :uuid]]

   :evaluation/assessment-ungradable
   [:map
    [:assessment-id :uuid]
    [:reason :keyword]
    [:message :string]
    [:band-distribution {:optional true} :map]
    [:model-provenance {:optional true} [:vector :map]]
    [:judge-tick-id {:optional true} :uuid]]

   ;; Gap-8: weighted composite score across all judges that fired
   ;; for a single (sheet, node, tick) tuple. Emitted in the same
   ;; processor cycle as the per-judge :judge/score-emitted events
   ;; (after parallel-future deref completes). Read-models can index
   ;; this for fast per-tick composite lookups; downstream optimization
   ;; (GEPA-style scalar fitness) reads from here.
   :judge/composite-score-computed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    ;; Absent when no judge scored: a coverage-only composite never invents a score.
    [:composite-score {:optional true} [:and number? [:>= 0.0] [:<= 1.0]]]
    ;; The completion the composite is of: composite identity is per subject.
    [:subject-completion-id {:optional true} :uuid]
    [:contributing-judges [:vector [:map
                                    [:judge-name :string]
                                    [:score number?]
                                    [:weight number?]]]]
    ;; HonestComposite: how many of the subject's judges the composite covers and
    ;; how many of them scored. :partial when any did not score. The composite is
    ;; of the LEARNING judges only: a monitoring-only judge is never mixed in.
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
  {;; The judge runtime's background future dispatches these two commands
   ;; once each judge result / the composite is ready. They are the ONLY
   ;; writers of the score events. Bodies mirror the corresponding event
   ;; shapes (minus the auto-filled :emitted-at, which is optional here so
   ;; the command handler can stamp it).
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

   ;; S7: the only writer of an assessment's terminal event (and, for a scored
   ;; outcome of a learning judge with feedback, of the legacy score event).
   ;; Fields mirror the judge-run OUTCOME.
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
    ;; The definition in force for the assessed revision; carried onto the
    ;; legacy score event.
    [:judge-config {:optional true} :map]]

   :evaluation/record-composite-score
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    ;; Absent when no judge scored: a coverage-only composite never invents a score.
    [:composite-score {:optional true} [:and number? [:>= 0.0] [:<= 1.0]]]
    ;; The completion the composite is of: composite identity is per subject.
    [:subject-completion-id {:optional true} :uuid]
    [:contributing-judges [:vector [:map
                                    [:judge-name :string]
                                    [:score number?]
                                    [:weight number?]]]]
    ;; HonestComposite: how many of the subject's judges the composite covers and
    ;; how many of them scored. :partial when any did not score. The composite is
    ;; of the LEARNING judges only: a monitoring-only judge is never mixed in.
    [:coverage {:optional true} [:map
                                 [:expected :int] [:scored :int]
                                 [:failed :int] [:ungradable :int]]]
    [:partial {:optional true} :boolean]
    [:purpose {:optional true} :keyword]
    [:emitted-at {:optional true} :string]]})

;; =============================================================================
;; Read Models
;; =============================================================================

(defschemas read-models
  {:evaluation/results-by-node
   [:map
    [:node-id :uuid]
    [:evaluations [:vector
                   [:map
                    [:trace-id :uuid]
                    [:aggregate-score :double]
                    [:evaluated-at :string]]]]]})

;; =============================================================================
;; Queries
;; =============================================================================

(defschemas queries
  {:evaluation/get-scores
   [:map
    [:sheet-id :uuid]
    [:node-id {:optional true} :uuid]
    [:since {:optional true} :string]
    [:min-score {:optional true} :double]
    [:max-score {:optional true} :double]
    [:limit {:optional true} :int]]

   :evaluation/get-low-scoring
   [:map
    [:sheet-id :uuid]
    [:node-id {:optional true} :uuid]
    [:threshold {:optional true} :double]
    [:limit {:optional true} :int]]

   :evaluation/get-trends
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:time-bucket {:optional true} [:enum :hour :day :week]]]})
