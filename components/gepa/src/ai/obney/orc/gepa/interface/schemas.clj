(ns ai.obney.orc.gepa.interface.schemas
  "Schema definitions for GEPA - EXACT 1:1 Python GEPA Parity.

   This native ORC/Grain implementation provides EXACT feature parity with
   the Python GEPA library, using event sourcing for state management
   and ORC workflows for instruction mutation.

   Key concepts:
   - Candidate: A set of instructions being evaluated
   - Population: All candidates in an optimization run
   - Pareto Frontier: Per-instance best candidates for diversity
   - Reflective Mutation: LLM-driven instruction improvement
   - Cross-Run Persistence: Events enable training data mining across runs

   Python GEPA Reference Types Mapped:
   - Candidate -> :gepa/candidate
   - ReflectiveExample -> :gepa/reflective-example
   - GEPAState -> :gepa/state (via read models)"
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; =============================================================================
;; Module 1: Data Structures (Matches Python Types)
;; =============================================================================

(defschemas types
  {;; Matches Python's Candidate dataclass
   :gepa/candidate
   [:map
    [:candidate-id :uuid]
    [:optimization-id :uuid]
    [:instructions [:map-of :string :string]]  ;; predictor-name -> instruction
    [:parent-ids [:vector [:maybe :uuid]]]
    [:generation :int]
    [:created-at :any]
    [:source-optimization-id {:optional true} [:maybe :uuid]]]  ;; For cross-run inheritance

   ;; Evaluation result with all fields needed for reflective mutation
   :gepa/evaluation-result
   [:map
    [:candidate-id :uuid]
    [:scores [:vector :double]]                 ;; per-instance scores
    [:aggregate-score :double]
    [:trace-ids [:vector :uuid]]
    [:metric-calls :int]]

   ;; Pareto entry for frontier tracking
   :gepa/pareto-entry
   [:map
    [:instance-id :int]
    [:best-candidate-ids [:set :uuid]]
    [:best-score :double]]

   ;; Matches Python's ReflectiveExample TypedDict EXACTLY
   ;; Keys: "Inputs", "Generated Outputs", "Feedback"
   :gepa/reflective-example
   [:map
    ["Inputs" [:map-of :string :any]]
    ["Generated Outputs" [:or
                          [:map-of :string :any]  ;; Normal case
                          :string]]                ;; Parse failure case
    ["Feedback" :string]]

   ;; Configuration matching Python GEPA parameters
   :gepa/optimization-config
   [:map
    [:max-metric-calls {:default 50} :int]
    [:reflection-minibatch-size {:default 3} :int]
    [:reflection-lm {:default "anthropic/claude-sonnet-4"} :string]
    [:use-merge {:default true} :boolean]
    [:crossover-rate {:default 0.3} :double]
    [:val-overlap-floor {:default 5} :int]
    [:skip-perfect-score {:default true} :boolean]
    [:perfect-score {:optional true} [:maybe :double]]
    [:frontier-type {:default :instance} [:enum :instance :objective :hybrid :cartesian]]]

   ;; Python-compatible state structure
   :gepa/optimization-state
   [:map
    [:optimization-id :uuid]
    [:sheet-id :uuid]
    [:config :gepa/optimization-config]
    [:status [:enum :running :completed :failed]]
    [:metric-calls :int]
    [:best-score :double]
    [:best-candidate-id [:maybe :uuid]]]})

;; =============================================================================
;; Module 2: Events (Including Cross-Run Persistence)
;; =============================================================================

(defschemas events
  {;; Optimization lifecycle
   :gepa/optimization-started
   [:map
    [:optimization-id :uuid]
    [:sheet-id :uuid]
    [:config [:map
              [:max-metric-calls :int]
              [:reflection-minibatch-size :int]
              [:reflection-lm :string]
              [:use-merge :boolean]
              [:crossover-rate :double]
              [:val-overlap-floor {:optional true} :int]
              [:skip-perfect-score {:optional true} :boolean]
              [:perfect-score {:optional true} [:maybe :double]]]]
    [:trainset-size :int]
    [:valset-size :int]
    [:started-at :any]]

   ;; NEW: Store trainset and valset for cross-run persistence and evaluation
   :gepa/datasets-stored
   [:map
    [:optimization-id :uuid]
    [:trainset [:vector [:map-of :string :any]]]
    [:valset [:vector [:map-of :string :any]]]
    [:stored-at :any]]

   ;; Candidate created with cross-run lineage support
   :gepa/candidate-created
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:instructions [:map-of :string :string]]
    [:parent-ids [:vector [:maybe :uuid]]]
    [:generation :int]
    [:mutation-reason [:maybe :string]]
    [:source-optimization-id {:optional true} [:maybe :uuid]]  ;; If inherited from previous run
    [:historical-val-subscores {:optional true} [:maybe [:map-of :int :double]]]  ;; For skip-eval
    [:created-at :any]]

   :gepa/candidate-evaluation-started
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:started-at :any]]

   :gepa/candidate-evaluated
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:scores [:vector :double]]
    [:aggregate-score :double]
    [:trace-ids [:vector :uuid]]
    ;; Per-instance RICH judge feedback (instance-idx -> feedback string).
    [:feedbacks {:optional true} [:map-of :int :string]]
    ;; Per-instance generated OUTPUT (instance-idx -> output map).
    [:outputs {:optional true} [:map-of :int [:map-of :any :any]]]
    [:metric-calls :int]
    [:evaluated-at :any]]

   ;; NEW: Per-example evaluation for training data mining
   :gepa/example-evaluated
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:example-id :uuid]
    [:example-inputs [:map-of :string :any]]
    [:generated-outputs [:or [:map-of :string :any] :string]]
    [:score :double]
    [:trace-id {:optional true} [:maybe :uuid]]
    [:feedback {:optional true} [:maybe :string]]
    [:evaluated-at :any]]

   :gepa/frontier-updated
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:instances-best-at [:vector :int]]
    [:updated-at :any]]

   ;; NEW: Track reflective dataset building
   :gepa/reflective-dataset-built
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:components [:vector :string]]
    [:num-examples :int]
    [:built-at :any]]

   :gepa/mutation-proposed
   [:map
    [:optimization-id :uuid]
    [:parent-candidate-id :uuid]
    [:new-candidate-id :uuid]
    [:component-updated :string]
    [:old-instruction :string]
    [:new-instruction :string]
    [:mutation-reason :string]
    [:proposed-at :any]]

   ;; NEW: Subsample evaluation events (Python GEPA parity - subsample-first optimization)
   ;; These track the evaluation of PROPOSED instructions before creating a candidate
   :gepa/subsample-evaluation-started
   [:map
    [:optimization-id :uuid]
    [:parent-id :uuid]
    [:proposed-instructions [:map-of :string :string]]  ;; Instructions being tested
    [:component-updated :string]
    [:subsample-indices [:vector :int]]  ;; Which trainset indices to evaluate
    [:iteration :int]                     ;; For epoch-shuffled sampling
    [:started-at :any]]

   :gepa/subsample-evaluated
   [:map
    [:optimization-id :uuid]
    [:parent-id :uuid]
    [:proposed-instructions [:map-of :string :string]]
    [:component-updated :string]
    [:subsample-indices [:vector :int]]
    [:parent-scores [:vector :double]]    ;; Parent's scores on same subsample
    [:proposed-scores [:vector :double]]  ;; New instructions' scores
    [:parent-sum :double]
    [:proposed-sum :double]
    [:accepted? :boolean]                 ;; proposed_sum > parent_sum (strict improvement)
    [:metric-calls :int]                  ;; Total metric calls (parent + proposed evaluations)
    [:evaluated-at :any]]

   ;; NEW: Track acceptance decision (matches Python's strict improvement check)
   :gepa/candidate-acceptance-decision
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:parent-ids [:vector :uuid]]
    [:accepted? :boolean]
    [:subsample-scores-before [:vector :double]]
    [:subsample-scores-after [:vector :double]]
    [:decision-reason :string]
    [:decided-at :any]]

   ;; NEW: Track merge attempts for deduplication
   :gepa/merge-attempted
   [:map
    [:optimization-id :uuid]
    [:id1 :int]  ;; candidate index
    [:id2 :int]
    [:ancestor :int]
    [:merge-desc {:optional true} [:vector :int]]  ;; source per predictor
    [:attempted-at :any]]

   :gepa/crossover-performed
   [:map
    [:optimization-id :uuid]
    [:parent1-id :uuid]
    [:parent2-id :uuid]
    [:ancestor-id {:optional true} :uuid]
    [:child-candidate-id :uuid]
    [:merged-instructions [:map-of :string :string]]
    [:performed-at :any]]

   :gepa/optimization-completed
   [:map
    [:optimization-id :uuid]
    [:best-candidate-id :uuid]
    [:final-score :double]
    [:total-metric-calls :int]
    [:num-candidates :int]
    [:improvement :double]
    [:completed-at :any]]

   :gepa/optimization-failed
   [:map
    [:optimization-id :uuid]
    [:error-message :string]
    [:failed-at :any]]})

;; =============================================================================
;; Module 3: Commands
;; =============================================================================

(defschemas commands
  {:gepa/start-optimization
   [:map
    [:optimization-id {:optional true} :uuid]
    [:sheet-id :uuid]
    [:trainset [:vector [:map-of :string :any]]]
    [:valset [:vector [:map-of :string :any]]]
    [:config {:optional true} [:map
              [:max-metric-calls {:optional true :default 50} :int]
              [:reflection-minibatch-size {:optional true :default 3} :int]
              [:reflection-lm {:optional true :default "anthropic/claude-sonnet-4"} :string]
              [:use-merge {:optional true :default true} :boolean]
              [:crossover-rate {:optional true :default 0.3} :double]
              [:val-overlap-floor {:optional true :default 5} :int]
              [:skip-perfect-score {:optional true :default true} :boolean]
              [:perfect-score {:optional true} [:maybe :double]]]]
    [:inherit-from-previous {:optional true :default true} :boolean]]  ;; Auto-inherit toggle

   :gepa/create-candidate
   [:map
    [:optimization-id :uuid]
    [:instructions [:map-of :string :string]]
    [:parent-ids [:vector [:maybe :uuid]]]
    [:mutation-reason [:maybe :string]]
    [:source-optimization-id {:optional true} [:maybe :uuid]]
    [:historical-val-subscores {:optional true} [:maybe [:map-of :int :double]]]]  ;; Skip eval if present

   :gepa/evaluate-candidate
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:capture-traces {:optional true :default false} :boolean]]

   ;; NEW: Subsample evaluation command (Python GEPA parity)
   ;; Note: This evaluates PROPOSED instructions before creating a candidate
   ;; The candidate is only created if subsample shows improvement
   :gepa/evaluate-on-subsample
   [:map
    [:optimization-id :uuid]
    [:parent-id :uuid]
    [:proposed-instructions [:map-of :string :string]]  ;; Instructions to test
    [:component-updated :string]                         ;; Which component was mutated
    [:subsample-indices [:vector :int]]
    [:iteration :int]]

   ;; Record the result of subsample evaluation
   :gepa/record-subsample-result
   [:map
    [:optimization-id :uuid]
    [:parent-id :uuid]
    [:proposed-instructions [:map-of :string :string]]
    [:component-updated :string]
    [:subsample-indices [:vector :int]]
    [:parent-scores [:vector :double]]
    [:proposed-scores [:vector :double]]
    [:accepted? :boolean]
    [:metric-calls :int]]

   :gepa/record-evaluation-result
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:scores [:vector :double]]
    [:trace-ids [:vector :uuid]]
    ;; Per-instance RICH judge feedback (instance-idx -> feedback string).
    [:feedbacks {:optional true} [:map-of :int :string]]
    ;; Per-instance generated OUTPUT (instance-idx -> output map).
    [:outputs {:optional true} [:map-of :int [:map-of :any :any]]]
    [:metric-calls :int]]

   :gepa/update-frontier
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:scores [:vector :double]]]

   :gepa/propose-mutation
   [:map
    [:optimization-id :uuid]
    [:parent-candidate-id :uuid]
    [:component-to-update :string]
    ;; Uses Python GEPA ReflectiveExample format
    [:reflective-examples [:vector [:map
                                    ["Inputs" [:map-of :string :any]]
                                    ["Generated Outputs" [:or [:map-of :string :any] :string]]
                                    ["Feedback" :string]]]]
    [:prompt-template {:optional true} [:maybe :string]]]

   :gepa/perform-crossover
   [:map
    [:optimization-id :uuid]
    [:parent1-id :uuid]
    [:parent2-id :uuid]]

   ;; NEW: Explicit merge with common ancestor
   :gepa/attempt-merge
   [:map
    [:optimization-id :uuid]]

   :gepa/complete-optimization
   [:map
    [:optimization-id :uuid]]

   :gepa/fail-optimization
   [:map
    [:optimization-id :uuid]
    [:error-message :string]]})

;; =============================================================================
;; Module 4: Queries
;; =============================================================================

(defschemas queries
  {:gepa/get-optimization-state
   [:map
    [:optimization-id :uuid]]

   :gepa/get-candidate
   [:map
    [:candidate-id :uuid]]

   :gepa/get-population
   [:map
    [:optimization-id :uuid]]

   :gepa/get-pareto-frontier
   [:map
    [:optimization-id :uuid]]

   :gepa/get-best-candidate
   [:map
    [:optimization-id :uuid]]

   :gepa/list-optimizations
   [:map
    [:sheet-id {:optional true} :uuid]
    [:status {:optional true} [:enum :running :completed :failed]]
    [:limit {:optional true :default 20} :int]]

   ;; NEW: Cross-run queries for training data mining
   :gepa/get-previous-optimizations
   [:map
    [:sheet-id :uuid]
    [:limit {:optional true :default 5} :int]]

   :gepa/get-pareto-candidates-from-run
   [:map
    [:optimization-id :uuid]]

   :gepa/get-all-evaluations-for-sheet
   [:map
    [:sheet-id :uuid]
    [:min-score {:optional true} :double]
    [:limit {:optional true :default 1000} :int]]

   :gepa/get-failure-patterns
   [:map
    [:optimization-id :uuid]
    [:component {:optional true} :string]]})

;; =============================================================================
;; Read Model Schemas (Python-Compatible)
;; =============================================================================

(defschemas read-models
  {:gepa/population-state
   [:map
    [:optimization-id :uuid]
    [:candidates [:map-of :uuid :gepa/candidate]]
    [:evaluated [:set :uuid]]
    [:total-metric-calls :int]]

   :gepa/pareto-frontier-state
   [:map
    [:optimization-id :uuid]
    [:max-scores [:map-of :int :double]]
    [:best-at [:map-of :int [:set :uuid]]]   ;; Python: pareto_front_mapping
    [:frontier-members [:set :uuid]]]

   :gepa/optimization-summary
   [:map
    [:optimization-id :uuid]
    [:sheet-id :uuid]
    [:status [:enum :running :completed :failed]]
    [:config :gepa/optimization-config]
    [:started-at :any]
    [:completed-at [:maybe :any]]
    [:best-score :double]
    [:best-candidate-id [:maybe :uuid]]
    [:total-candidates :int]
    [:total-metric-calls :int]]

   ;; Full Python-compatible state (see read_models.clj gepa-state fn)
   :gepa/full-state
   [:map
    [:optimization-id :uuid]
    [:sheet-id :uuid]
    [:config :gepa/optimization-config]
    [:status [:enum :running :completed :failed]]
    ;; Python: program_candidates
    [:program-candidates [:vector [:map-of :string :string]]]
    ;; Python: program_full_scores_val_set
    [:agg-scores [:vector [:maybe :double]]]
    ;; Python: prog_candidate_val_subscores
    [:val-subscores [:map-of :int [:map-of :any :double]]]
    ;; Python: parent_program_for_candidate
    [:parent-list [:vector [:vector [:maybe :int]]]]
    ;; Python: named_predictor_id_to_update_next_for_program_candidate
    [:round-robin-state [:map-of :int :int]]
    ;; Python: list_of_named_predictors
    [:predictor-names [:vector :string]]
    ;; Pareto mapping (instance-id -> #{candidate-idx})
    [:pareto-front-mapping [:map-of :int [:set :int]]]
    ;; Merge tracking
    [:merge-state [:map
                   [:ancestor-logs [:set [:vector :int]]]
                   [:merge-descs [:set [:vector :any]]]]]
    [:total-metric-calls :int]
    [:iteration :int]]})
