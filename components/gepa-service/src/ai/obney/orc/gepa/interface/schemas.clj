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


(defschemas types
  {:gepa/timestamp
   [:or :time/offset-date-time :time/instant
    [:fn (fn [value]
           (and (string? value)
                (try (java.time.Instant/parse value)
                     true
                     (catch java.time.format.DateTimeParseException _ false))))]]

   :gepa/example-value
   [:or :nil :boolean :string char? :keyword :symbol number? :uuid inst?
    :time/offset-date-time
    [:sequential [:ref :gepa/example-value]]
    [:set [:ref :gepa/example-value]]
    [:map-of [:ref :gepa/example-value] [:ref :gepa/example-value]]]

   :gepa/metric-descriptor
   [:map
    [:kind [:enum :default :judges :custom :exact-match :contains]]
    [:metric-id :string] [:revision :string] [:configuration :map]
    [:retry-safe? :boolean] [:max-attempts pos-int?] [:timeout-ms pos-int?]]

   :gepa/candidate
   [:map
    [:candidate-id :uuid]
    [:optimization-id :uuid]
    [:instructions [:map-of :string :string]]
    [:parent-ids [:vector [:maybe :uuid]]]
    [:generation :int]
    [:created-at :gepa/timestamp]
    [:source-optimization-id {:optional true} [:maybe :uuid]]]

   :gepa/evaluation-result
   [:map
    [:candidate-id :uuid]
    [:scores [:vector :double]]
    [:aggregate-score :double]
    [:trace-ids [:vector :uuid]]
    [:metric-calls :int]]

   :gepa/pareto-entry
   [:map
    [:instance-id :int]
    [:best-candidate-ids [:set :uuid]]
    [:best-score :double]]

   :gepa/reflective-example
   [:map
    ["Inputs" [:map-of :string :gepa/example-value]]
    ["Generated Outputs" [:or
                          [:map-of :string :gepa/example-value]
                          :string]]
    ["Feedback" :string]]

   :gepa/optimization-config
   [:map
    [:max-metric-calls {:default 50} :int]
    [:reflection-minibatch-size {:default 3} :int]
    [:reflection-lm {:default "anthropic/claude-sonnet-4"} :string]
    [:reflection-options {:optional true} :map]
    [:reflection-prompt-template {:optional true} [:or :string [:map-of :string :string]]]
    [:max-reflection-tokens {:optional true} [:maybe :int]]
    [:use-merge {:default true} :boolean]
    [:crossover-rate {:default 0.3} :double]
    [:val-overlap-floor {:default 5} :int]
    [:skip-perfect-score {:default true} :boolean]
    [:perfect-score {:optional true} [:maybe :double]]
    [:pause-after-proposal? {:optional true :default false} :boolean]
    [:frontier-type {:default :instance} [:enum :instance :objective :hybrid :cartesian]]]

   :gepa/optimization-state
   [:map
    [:optimization-id :uuid]
    [:sheet-id :uuid]
    [:config :gepa/optimization-config]
    [:status [:enum :running :completed :failed]]
    [:metric-calls :int]
    [:best-score :double]
    [:best-candidate-id [:maybe :uuid]]]})


(defschemas events
  {:gepa/recovery-scan-triggered [:map [:triggered-at :string]]
   :gepa/optimization-recovery-requested [:map [:optimization-id :uuid]]
   :gepa/metric-selected
   [:map [:optimization-id :uuid] [:metric-descriptor :gepa/metric-descriptor]]

   :gepa/optimization-started
   [:map
    [:metric-descriptor {:optional true} :gepa/metric-descriptor]
    [:optimization-id :uuid]
    [:sheet-id :uuid]
    [:config [:map
              [:max-metric-calls :int]
              [:reflection-minibatch-size :int]
              [:reflection-lm :string]
              [:reflection-options {:optional true} :map]
              [:reflection-prompt-template {:optional true} [:or :string [:map-of :string :string]]]
              [:max-reflection-tokens {:optional true} [:maybe :int]]
              [:use-merge :boolean]
              [:crossover-rate :double]
              [:val-overlap-floor {:optional true} :int]
              [:skip-perfect-score {:optional true} :boolean]
              [:perfect-score {:optional true} [:maybe :double]]
              [:pause-after-proposal? {:optional true} :boolean]]]
    [:trainset-size :int]
    [:valset-size :int]
    [:started-at :gepa/timestamp]]

   :gepa/datasets-stored
   [:map
    [:optimization-id :uuid]
    [:trainset [:vector [:map-of :string :gepa/example-value]]]
    [:valset [:vector [:map-of :string :gepa/example-value]]]
    [:stored-at :gepa/timestamp]]

   :gepa/candidate-created
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:instructions [:map-of :string :string]]
    [:parent-ids [:vector [:maybe :uuid]]]
    [:generation :int]
    [:mutation-reason [:maybe :string]]
    [:source-optimization-id {:optional true} [:maybe :uuid]]
    [:historical-val-subscores {:optional true} [:maybe [:map-of :int :double]]]
    [:created-at :gepa/timestamp]]

   :gepa/candidate-evaluation-started
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:started-at :gepa/timestamp]]

   :gepa/candidate-evaluated
   [:map
    [:accounting-mode {:optional true} [:enum :tasks]]
    [:task-call-ids {:optional true} [:vector :uuid]]
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:scores [:vector :double]]
    [:aggregate-score :double]
    [:trace-ids [:vector :uuid]]
    [:feedbacks {:optional true} [:map-of :int :string]]
    [:outputs {:optional true} [:map-of :int [:map-of [:or :string :keyword] :gepa/example-value]]]
    [:metric-calls :int]
    [:evaluated-at :gepa/timestamp]]

   :gepa/example-evaluated
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:example-id :uuid]
    [:example-inputs [:map-of :string :gepa/example-value]]
    [:generated-outputs [:or [:map-of :string :gepa/example-value] :string]]
    [:score :double]
    [:trace-id {:optional true} [:maybe :uuid]]
    [:feedback {:optional true} [:maybe :string]]
    [:evaluated-at :gepa/timestamp]]

   :gepa/frontier-updated
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:instances-best-at [:vector :int]]
    [:updated-at :gepa/timestamp]]

   :gepa/reflective-dataset-built
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:components [:vector :string]]
    [:num-examples :int]
    [:built-at :gepa/timestamp]]

   :gepa/mutation-proposed
   [:map
    [:optimization-id :uuid]
    [:parent-candidate-id :uuid]
    [:new-candidate-id :uuid]
    [:component-updated :string]
    [:old-instruction :string]
    [:new-instruction :string]
    [:mutation-reason :string]
    [:proposed-at :gepa/timestamp]]

   :gepa/proposer-call-completed
   [:map
    [:optimization-id :uuid]
    [:parent-candidate-id :uuid]
    [:component :string]
    [:call-id :uuid]
    [:provider :keyword]
    [:model :string]
    [:usage [:map
             [:prompt-tokens :int]
             [:completion-tokens :int]
             [:total-tokens :int]
             [:cost {:optional true} :double]]]
    [:prompt :string]
    [:response :string]
    [:prompt-sha256 :string]
    [:response-sha256 :string]
    [:completed-at :gepa/timestamp]]

   :gepa/optimization-resumed
   [:map
    [:optimization-id :uuid]
    [:resumed-at :gepa/timestamp]]

   :gepa/task-call-completed
   [:map
    [:optimization-id :uuid]
    [:call-id :uuid]
    [:role :keyword]
    [:example-index :int]
    [:trace-id :uuid]
    [:output :map]
    [:score :double]
    [:feedback {:optional true} [:maybe :string]]
    [:model {:optional true} [:maybe :string]]
    [:usage {:optional true} [:maybe :map]]
    [:completed-at :gepa/timestamp]]

   :gepa/parent-minibatch-evaluation-requested
   [:map
    [:optimization-id :uuid]
    [:request-id :uuid]
    [:parent-id :uuid]
    [:component :string]
    [:iteration :int]
    [:subsample-indices [:vector :int]]
    [:requested-at :gepa/timestamp]]

   :gepa/proposal-ready
   [:map
    [:optimization-id :uuid]
    [:proposal-id :uuid]
    [:parent-id :uuid]
    [:proposed-instructions [:map-of :string :string]]
    [:component-updated :string]
    [:subsample-indices [:vector :int]]
    [:iteration :int]
    [:ready-at :gepa/timestamp]]

   :gepa/winner-applied
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:sheet-id :uuid]
    [:source-version :int]
    [:target-version :int]
    [:source-fingerprint :string]
    [:target-fingerprint :string]
    [:applied-at :gepa/timestamp]]

   :gepa/subsample-evaluation-started
   [:map
    [:optimization-id :uuid]
    [:parent-id :uuid]
    [:proposed-instructions [:map-of :string :string]]
    [:component-updated :string]
    [:subsample-indices [:vector :int]]
    [:iteration :int]
    [:started-at :gepa/timestamp]]

   :gepa/subsample-evaluated
   [:map
    [:parent-perfect-skip? {:optional true} :boolean]
    [:accounting-mode {:optional true} [:enum :tasks]]
    [:task-call-ids {:optional true} [:vector :uuid]]
    [:optimization-id :uuid]
    [:parent-id :uuid]
    [:proposed-instructions [:map-of :string :string]]
    [:component-updated :string]
    [:subsample-indices [:vector :int]]
    [:iteration {:optional true} :int]
    [:parent-scores [:vector :double]]
    [:proposed-scores [:vector :double]]
    [:parent-sum :double]
    [:proposed-sum :double]
    [:accepted? :boolean]
    [:metric-calls :int]
    [:evaluated-at :gepa/timestamp]]

   :gepa/candidate-acceptance-decision
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:parent-ids [:vector :uuid]]
    [:accepted? :boolean]
    [:subsample-scores-before [:vector :double]]
    [:subsample-scores-after [:vector :double]]
    [:decision-reason :string]
    [:decided-at :gepa/timestamp]]

   :gepa/merge-attempted
   [:map
    [:optimization-id :uuid]
    [:id1 :int]
    [:id2 :int]
    [:ancestor :int]
    [:merge-desc {:optional true} [:vector :int]]
    [:attempted-at :gepa/timestamp]]

   :gepa/crossover-performed
   [:map
    [:optimization-id :uuid]
    [:parent1-id :uuid]
    [:parent2-id :uuid]
    [:ancestor-id {:optional true} :uuid]
    [:child-candidate-id :uuid]
    [:merged-instructions [:map-of :string :string]]
    [:performed-at :gepa/timestamp]]

   :gepa/optimization-completed
   [:map
    [:optimization-id :uuid]
    [:best-candidate-id :uuid]
    [:final-score :double]
    [:total-metric-calls :int]
    [:num-candidates :int]
    [:improvement :double]
    [:completed-at :gepa/timestamp]]

   :gepa/optimization-failed
   [:map
    [:optimization-id :uuid]
    [:error-message :string]
    [:failed-at :gepa/timestamp]]})


(defschemas commands
  {:gepa/request-recovery [:map [:optimization-id :uuid]]
   :gepa/select-metric
   [:map [:optimization-id :uuid] [:metric-descriptor :gepa/metric-descriptor]]

   :gepa/start-optimization
   [:map
    [:metric-descriptor {:optional true} :gepa/metric-descriptor]
    [:optimization-id {:optional true} :uuid]
    [:sheet-id :uuid]
    [:trainset [:vector [:map-of :string :gepa/example-value]]]
    [:valset [:vector [:map-of :string :gepa/example-value]]]
    [:config {:optional true} [:map
              [:max-metric-calls {:optional true :default 50} :int]
              [:reflection-minibatch-size {:optional true :default 3} :int]
              [:reflection-lm {:optional true :default "anthropic/claude-sonnet-4"} :string]
              [:reflection-options {:optional true} :map]
              [:reflection-prompt-template {:optional true} [:or :string [:map-of :string :string]]]
              [:max-reflection-tokens {:optional true} [:maybe :int]]
              [:use-merge {:optional true :default true} :boolean]
              [:crossover-rate {:optional true :default 0.3} :double]
              [:val-overlap-floor {:optional true :default 5} :int]
              [:skip-perfect-score {:optional true :default true} :boolean]
              [:perfect-score {:optional true} [:maybe :double]]
              [:pause-after-proposal? {:optional true :default false} :boolean]]]
    [:inherit-from-previous {:optional true :default true} :boolean]]

   :gepa/create-candidate
   [:map
    [:optimization-id :uuid]
    [:instructions [:map-of :string :string]]
    [:parent-ids [:vector [:maybe :uuid]]]
    [:mutation-reason [:maybe :string]]
    [:source-optimization-id {:optional true} [:maybe :uuid]]
    [:historical-val-subscores {:optional true} [:maybe [:map-of :int :double]]]]

   :gepa/evaluate-candidate
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:capture-traces {:optional true :default false} :boolean]]

   :gepa/evaluate-on-subsample
   [:map
    [:optimization-id :uuid]
    [:parent-id :uuid]
    [:proposed-instructions [:map-of :string :string]]
    [:component-updated :string]
    [:subsample-indices [:vector :int]]
    [:iteration :int]]

   :gepa/record-subsample-result
   [:map
    [:parent-perfect-skip? {:optional true} :boolean]
    [:accounting-mode {:optional true} [:enum :tasks]]
    [:task-call-ids {:optional true} [:vector :uuid]]
    [:optimization-id :uuid]
    [:parent-id :uuid]
    [:proposed-instructions [:map-of :string :string]]
    [:component-updated :string]
    [:subsample-indices [:vector :int]]
    [:iteration :int]
    [:parent-scores [:vector :double]]
    [:proposed-scores [:vector :double]]
    [:accepted? :boolean]
    [:metric-calls :int]]

   :gepa/record-evaluation-result
   [:map
    [:accounting-mode {:optional true} [:enum :tasks]]
    [:task-call-ids {:optional true} [:vector :uuid]]
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:scores [:vector :double]]
    [:trace-ids [:vector :uuid]]
    [:feedbacks {:optional true} [:map-of :int :string]]
    [:outputs {:optional true} [:map-of :int [:map-of [:or :string :keyword] :gepa/example-value]]]
    [:metric-calls :int]]

   :gepa/update-frontier
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:scores [:vector :double]]]

   :gepa/record-proposer-call
   [:map
    [:optimization-id :uuid]
    [:parent-candidate-id :uuid]
    [:component :string]
    [:call-id :uuid]
    [:provider :keyword]
    [:model :string]
    [:usage [:map
             [:prompt-tokens :int]
             [:completion-tokens :int]
             [:total-tokens :int]
             [:cost {:optional true} :double]]]
    [:prompt :string]
    [:response :string]
    [:prompt-sha256 :string]
    [:response-sha256 :string]]

   :gepa/mark-optimization-resumed
   [:map
    [:optimization-id :uuid]]

   :gepa/record-task-call
   [:map
    [:optimization-id :uuid]
    [:call-id :uuid]
    [:role :keyword]
    [:example-index :int]
    [:trace-id :uuid]
    [:output :map]
    [:score :double]
    [:feedback {:optional true} [:maybe :string]]
    [:model {:optional true} [:maybe :string]]
    [:usage {:optional true} [:maybe :map]]]

   :gepa/request-parent-minibatch-evaluation
   [:map
    [:optimization-id :uuid]
    [:parent-id :uuid]
    [:component :string]
    [:iteration :int]
    [:subsample-indices [:vector :int]]]

   :gepa/record-proposal-ready
   [:map
    [:optimization-id :uuid]
    [:proposal-id :uuid]
    [:parent-id :uuid]
    [:proposed-instructions [:map-of :string :string]]
    [:component-updated :string]
    [:subsample-indices [:vector :int]]
    [:iteration :int]]

   :gepa/record-winner-application
   [:map
    [:optimization-id :uuid]
    [:candidate-id :uuid]
    [:sheet-id :uuid]
    [:source-version :int]
    [:target-version :int]
    [:source-fingerprint :string]
    [:target-fingerprint :string]]

   :gepa/propose-mutation
   [:map
    [:optimization-id :uuid]
    [:parent-candidate-id :uuid]
    [:component-to-update :string]
    [:reflective-examples [:vector [:map
                                    ["Inputs" [:map-of :string :gepa/example-value]]
                                    ["Generated Outputs" [:or [:map-of :string :gepa/example-value] :string]]
                                    ["Feedback" :string]]]]
    [:prompt-template {:optional true} [:maybe :string]]]

   :gepa/perform-crossover
   [:map
    [:optimization-id :uuid]
    [:parent1-id :uuid]
    [:parent2-id :uuid]]

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


(defschemas queries
  {:gepa/get-optimization-progress
   [:map [:optimization-id :uuid]]

   :gepa/get-optimization-state
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
    [:best-at [:map-of :int [:set :uuid]]]
    [:frontier-members [:set :uuid]]]

   :gepa/optimization-summary
   [:map
    [:optimization-id :uuid]
    [:sheet-id :uuid]
    [:status [:enum :running :completed :failed]]
    [:config :gepa/optimization-config]
    [:started-at :gepa/timestamp]
    [:completed-at [:maybe :gepa/timestamp]]
    [:best-score :double]
    [:best-candidate-id [:maybe :uuid]]
    [:total-candidates :int]
    [:total-metric-calls :int]]

   :gepa/full-state
   [:map
    [:optimization-id :uuid]
    [:sheet-id :uuid]
    [:config :gepa/optimization-config]
    [:status [:enum :running :completed :failed]]
    [:program-candidates [:vector [:map-of :string :string]]]
    [:agg-scores [:vector [:maybe :double]]]
    [:val-subscores [:map-of :int [:map-of [:or :int :uuid] :double]]]
    [:parent-list [:vector [:vector [:maybe :int]]]]
    [:round-robin-state [:map-of :int :int]]
    [:predictor-names [:vector :string]]
    [:pareto-front-mapping [:map-of :int [:set :int]]]
    [:merge-state [:map
                   [:ancestor-logs [:set [:vector :int]]]
                   [:merge-descs [:set [:tuple :int :int [:vector :int]]]]]]
    [:total-metric-calls :int]
    [:iteration :int]]})

(defschemas task-types
  {:gepa/task-boundary
   [:map [:kind [:enum :candidate :subsample]]
    [:candidate-id {:optional true} :uuid]
    [:parent-id {:optional true} :uuid]
    [:iteration {:optional true} :int]]
   :gepa/task-manifest-entry
   [:map [:call-id :uuid] [:trace-id :uuid] [:role :keyword]
    [:example-index :int] [:input :map] [:instructions [:map-of :string :string]]
    [:metric-retry-safe? {:optional true} :boolean]
    [:max-metric-attempts {:optional true} pos-int?]
    [:sheet-id {:optional true} :uuid]]
   :gepa/task-result
   [:map [:output :map] [:score [:and :double [:>= 0.0] [:<= 1.0]]]
    [:feedback {:optional true} [:maybe :string]]
    [:model {:optional true} [:maybe :string]]
    [:usage {:optional true} [:maybe :map]]]})

(defschemas task-commands
  {:gepa/manage-task
   [:map [:operation [:enum :open-boundary :claim :renew :dispatch :workflow-completed :metric-started :finish :attention]]
    [:optimization-id :uuid] [:boundary-id :uuid]
    [:boundary {:optional true} :gepa/task-boundary]
    [:tasks {:optional true} [:vector :gepa/task-manifest-entry]]
    [:call-id {:optional true} :uuid] [:owner-id {:optional true} :uuid]
    [:epoch {:optional true} pos-int?]
    [:attempt-id {:optional true} :uuid]
    [:attempt-kind {:optional true} [:enum :evaluation :metric]]
    [:result {:optional true} :map]
    [:reason {:optional true} [:map [:code :keyword] [:message :string]]]]})

(defschemas task-events
  {:gepa/task-adoption-blocked
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:reason [:enum :ambiguous-legacy-task]] [:source-event-ids [:vector :uuid]]]
   :gepa/task-boundary-opened
   [:map [:optimization-id :uuid] [:boundary-id :uuid]
    [:boundary :gepa/task-boundary] [:tasks [:vector :gepa/task-manifest-entry]]]
   :gepa/task-claimed
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:trace-id :uuid] [:owner-id :uuid] [:epoch pos-int?]
    [:lease-expires-at :string]]
   :gepa/task-lease-renewed
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:lease-expires-at :string]]
   :gepa/task-attempt-dispatched
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:attempt-id :uuid]
    [:attempt-kind [:enum :evaluation :metric]] [:attempt-number pos-int?]]
   :gepa/task-workflow-completed
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:result :map]]
   :gepa/task-metric-started
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:attempt-id :uuid]]
   :gepa/task-finished
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:attempt-id :uuid] [:result :gepa/task-result]]
   :gepa/task-recovery-required
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:owner-id :uuid] [:epoch pos-int?] [:reason [:map [:code :keyword] [:message :string]]]]
   :gepa/task-adopted
   [:map [:optimization-id :uuid] [:boundary-id :uuid] [:call-id :uuid]
    [:trace-id :uuid] [:result :gepa/task-result]
    [:source-event-ids [:vector :uuid]] [:source-trace-ids [:vector :uuid]]
    [:observed-attempts pos-int?]]})
