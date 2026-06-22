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
    [:emitted-at :string]]

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
    [:composite-score [:and number? [:>= 0.0] [:<= 1.0]]]
    [:contributing-judges [:vector [:map
                                    [:judge-name :string]
                                    [:score number?]
                                    [:weight number?]]]]
    [:emitted-at :string]]

   :evaluation/trace-evaluated
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:node-name :string]
    [:dimensions [:vector DimensionScore]]
    [:aggregate-score :double]
    [:feedback-summary :string]
    [:evaluated-at :string]]

   :evaluation/batch-completed
   [:map
    [:batch-id :uuid]
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:traces-evaluated :int]
    [:avg-score :double]
    [:min-score :double]
    [:max-score :double]
    [:score-distribution [:map-of :string :int]]
    [:completed-at :string]]})

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
    [:emitted-at {:optional true} :string]]

   :evaluation/record-composite-score
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:composite-score [:and number? [:>= 0.0] [:<= 1.0]]]
    [:contributing-judges [:vector [:map
                                    [:judge-name :string]
                                    [:score number?]
                                    [:weight number?]]]]
    [:emitted-at {:optional true} :string]]

   :evaluation/evaluate-trace
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:judge-config {:optional true}
     [:map
      [:dimensions {:optional true} [:vector :keyword]]
      [:model {:optional true} :string]]]]

   :evaluation/evaluate-batch
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:trace-ids {:optional true} [:vector :uuid]]
    [:since {:optional true} :string]
    [:limit {:optional true} :int]
    [:judge-config {:optional true}
     [:map
      [:dimensions {:optional true} [:vector :keyword]]
      [:model {:optional true} :string]]]]})

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
