(ns ai.obney.orc.orc-service.interface.schemas
  "Behavior Tree Sheet schemas.

   This is a behavior tree visualization system where:
   - The grid represents a tree (rows = depth levels, cells subdivide horizontally)
   - Nodes are leaf (execute), sequence (run all until failure), or fallback (run until success)
   - Data flows through a shared blackboard
   - Nodes return status: success, failure, or running"
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.orc.orc-service.core.logic :as logic]))

;; =============================================================================
;; Domain Enums
;; =============================================================================

(def node-type
  "Behavior tree node types"
  [:enum :leaf :sequence :fallback :condition :llm-condition :parallel :map-each :repl-researcher :delegate])

(def parallel-success-policy [:enum :all :any :majority])

(def parallel-failure-policy [:enum :all :any])

(def emitted-dsl
  [:and [:fn #(or (vector? %) (list? %))]
   [:cat [:or :keyword :symbol] [:* logic/durable-value]]])

(def trace-timestamp
  "Live store timestamps and the serialized strings in historical trace projections."
  [:or :string inst? :time/instant :time/offset-date-time :time/zoned-date-time])

(def malli-definition
  [:fn {:error/message "expected a Malli schema"} logic/valid-malli-schema?])

(def exported-node
  [:schema {:registry {::exported-node
                       [:map [:id :uuid] [:name :string] [:type node-type]
                        [:children {:optional true} [:vector [:ref ::exported-node]]]]}}
   [:ref ::exported-node]])

(def diff-node
  [:map [:id :uuid] [:name :string] [:type node-type] [:path [:vector :string]]])

(def value-profile
  [:map [:type [:enum :string :map :vector :other]]
   [:length [:int {:min 0}]]
   [:word-count {:optional true} [:int {:min 0}]]
   [:line-count {:optional true} [:int {:min 0}]]])

(def stored-node-trace
  "Early execution traces identified nodes before per-instance trace IDs existed."
  [:or ::node-trace
   [:and logic/durable-value
    [:map [:node-id :uuid] [:node-type node-type]
     [:status [:enum :success :failure :running :partial :timeout :blocked]]
     [:started-at trace-timestamp] [:completed-at {:optional true} trace-timestamp]]
    [:fn #(not (contains? % :trace-instance-id))]]])

(def historical-iteration
  [:and logic/durable-value
   [:map [:code [:maybe :string]]
    [:stdout {:optional true} [:maybe :string]]
    [:reasoning {:optional true} [:maybe :string]]
    [:result {:optional true} logic/durable-value]]])

(def traced-iteration
  [:and logic/durable-value
   [:or [:map [:iteration [:int {:min 1}]]]
    [:map [:iteration-index [:int {:min 0}]] [:attempt-ordinal [:int {:min 0}]]]]
   [:map [:iteration {:optional true} [:int {:min 1}]]
    [:iteration-index {:optional true} [:int {:min 0}]]
    [:attempt-ordinal {:optional true} [:int {:min 0}]]
    [:status {:optional true} [:enum :success :failure :timeout :blocked]]
    [:code {:optional true} [:maybe :string]]]])

(def researcher-event
  [:map
   [:type [:enum :checkpoint :yield :resume :action-completed :effect-claimed :effect-completed :effect-indeterminate]]
   [:at trace-timestamp]
   [:iteration {:optional true} [:maybe [:int {:min 0}]]]
   [:kind {:optional true} [:enum :provider :tool :generated-child :behavior-mint]]
   [:action-id {:optional true} :string]
   [:action-kind {:optional true} :keyword]
   [:logical-action-id {:optional true} :string]
   [:attempt-id {:optional true} :string]
   [:logical-action-identity {:optional true} :string]
   [:attempt-identity {:optional true} :string]])

(def modified-node
  [:map [:id :uuid] [:name :string] [:path [:vector :string]]
   [:changes [:vector [:map [:field :keyword] [:old logic/durable-value] [:new logic/durable-value]]]]])

(def judge-rubric-schema
  "A judge's declared rubric: what it grades (criterion, optional stance), the
   integer-keyed level descriptions (contiguous, >= 2, validated by
   `judge-definition`), and whether grading must carry written feedback."
  [:map
   [:criterion :string]
   [:stance {:optional true} :string]
   [:bands [:map-of :int :string]]
   [:feedback [:enum :required :none]]])

(def judge-config-schema
  "A judge definition. Models resolve like any ORC model node: a declared model
   id or a registered provider name, else the runtime provider."
  [:map
   [:type :keyword]
   [:criteria {:optional true} :string]
   [:weight {:optional true} [:and number? [:>= 0.0]]]
   [:provider {:optional true} :keyword]
   [:model {:optional true} :string]
   [:timeout-ms {:optional true} :int]
   [:rubric {:optional true} judge-rubric-schema]
   [:purposes {:optional true} [:set [:enum :monitoring :learning]]]
   [:assess-failures? {:optional true} :boolean]
   [:alert {:optional true} [:map
                             [:below number?]
                             [:window :int]
                             [:min-coverage number?]]]
   [:sheet-id {:optional true} :uuid]])

(def executor-type
  "Executor types for leaf nodes"
  [:enum :ai :code :tool :decision])

(def decision-record
  "Durable record of one model decision: what was offered, what the provider
   answered, and whether the answer was set aside for the abstention option.
   A native decision model's own reported distribution, confidence (choice) or
   probability (yes/no) is carried exactly as reported; none is ever
   synthesised from a bare answer, so these keys are absent for providers that
   report none."
  [:map {:closed true
         :description "Durable record of one model decision"}
   [:offered {:description "Every option id the model was offered, verbatim"}
    [:vector logic/durable-value]]
   [:answer {:optional true
             :description "The answer the provider returned (present when one was obtained)"}
    logic/durable-value]
   [:abstained? {:description "True when the answer was set aside for the abstention option"}
    :boolean]
   [:set-aside {:optional true
                :description "The answer that was set aside when abstained? is true"}
    logic/durable-value]
   [:probabilities {:optional true
                    :description "Provider-reported probability per offered option id"}
    [:map-of :string number?]]
   [:confidence {:optional true
                 :description "Provider-reported confidence of a choice, as reported"}
    number?]
   [:probability {:optional true
                  :description "Provider-reported probability that a yes/no proposition holds"}
    number?]
   [:band-distribution {:optional true
                        :description "A banded decision's reported distribution, exactly as reported: probabilities keyed by 0-indexed level position, the expected position, and confidence when reported"}
    [:map {:closed true}
     [:probabilities [:map-of :string number?]]
     [:expected-position number?]
     [:confidence {:optional true} number?]]]])

(def node-status
  "Node execution status.

   :partial is emitted by map-each when 0 < failed < total items (D-008).
   Sequence/fallback parents treat :partial as continuation (same as :success).

   :timeout is emitted by RLM repl-researcher nodes when Phase 2 exceeds the
   budget (D-003). Surfaces to the parent so callers can distinguish 'no
   useful output' from a deliberate failure."
  [:enum :idle :running :success :failure :partial :timeout])

(def partial-summary
  "Denormalized at-a-glance synopsis of partial/failure outcome on a map-each.
   The canonical record stays in per-child :sheet/node-execution-completed
   events; this summary is a convenience for judges and dashboards.

   D-008: :failure-input-profiles is reserved for future enrichment when
   the map-each handler has access to per-child input profiles."
  [:map
   [:total :int]
   [:succeeded :int]
   [:failed :int]
   [:failure-indices [:vector :int]]
   [:failure-reasons [:map-of :int :string]]
   [:failure-input-profiles {:optional true} [:map-of :int :map]]])

(def structured-failure-kind
  [:enum :transport-failure :missing-forced-tool-call
   :tool-call-parsing-failed :schema-validation-failed
   :empty-provider-response :provider-finish-error
   :provider-not-configured
   :budget-history-unavailable
   :model-plan-incompatible
   :undecided])

(def provider-transport-evidence
  [:and
   [:map {:closed true}
    [:type {:optional true}
     [:enum :litellm/invalid-request :litellm/invalid-config
      :litellm/authentication-error :litellm/authorization-error
      :litellm/provider-not-found :litellm/model-not-found
      :litellm/unsupported-feature :litellm/quota-exceeded
      :litellm/rate-limit :litellm/timeout :litellm/connection-error
      :litellm/server-error :litellm/provider-error
      :litellm/invalid-response :litellm/streaming-error :litellm/content-filter
      :litellm/internal-error :litellm/resource-exhausted]]
    [:http-status {:optional true} [:int {:min 100 :max 599}]]]
   [:fn seq]])

(def provider-failure-evidence
  [:map
   [:provider :string]
   [:transport {:optional true} provider-transport-evidence]
   [:model {:optional true} [:maybe :string]]
   [:response-id {:optional true} [:maybe :string]]
   [:finish-reason {:optional true} [:maybe :string]]
   [:native-finish-reason {:optional true} [:maybe :string]]
   [:tool-call-present? {:optional true} :boolean]
   [:tool-call-name {:optional true} [:maybe :string]]
   [:usage {:optional true} [:maybe [:map
                                     [:prompt-tokens {:optional true} :int]
                                     [:completion-tokens {:optional true} :int]
                                     [:total-tokens {:optional true} :int]]]]
   [:output-truncated? {:optional true} :boolean]])

(def researcher-resume-state-v2
  [:and
   [:map
    [:version [:= 2]]
    [:revision [:and :int [:>= 0]]]
    [:ownership-epoch {:optional true} [:and :int [:>= 0]]]
    [:next-iteration [:and :int [:>= 0]]]
    [:classification-context {:optional true} :map]
    [:sandbox-vars :map]
    [:var-creation-times
     [:map-of :keyword [:and :int [:>= 0]]]]
    [:usage
     [:map
      [:prompt-tokens [:and :int [:>= 0]]]
      [:completion-tokens [:and :int [:>= 0]]]
      [:total-tokens [:and :int [:>= 0]]]]]
    [:cumulative-tree-ms [:and :int [:>= 0]]]
    [:iteration-attempts
     [:map-of [:and :int [:>= 0]] [:and :int [:>= 0]]]]
    [:campaign-started-at-ms [:and :int [:>= 0]]]
    [:campaign-deadline-ms [:and :int [:>= 0]]]
    [:observed-quantum-duration-ms {:optional true}
     [:and :int [:>= 0]]]
    [:max-observed-quantum-duration-ms {:optional true}
     [:and :int [:>= 0]]]]
   [:fn {:error/message
         "version-2 researcher resume state must be compact and its observed quantum duration cannot exceed its running maximum"}
    (fn [state]
      (let [observed (:observed-quantum-duration-ms state)
            running-max (:max-observed-quantum-duration-ms state)]
        (and (not (contains? state :history))
             (not (contains? state :terminal-result))
             (or (nil? observed)
                 (and (some? running-max)
                      (<= observed running-max))))))]])

(def researcher-resume-state-v3
  [:and
   [:map
   [:version [:= 3]]
   [:revision [:and :int [:>= 0]]]
   [:ownership-epoch {:optional true} [:and :int [:>= 0]]]
   [:next-iteration [:and :int [:>= 0]]]
   [:classification-context {:optional true} :map]
   [:sandbox-fact-kind [:enum :full-snapshot :sandbox-delta]]
   [:resulting-state-hash :string]
   [:sandbox-snapshot {:optional true} :map]
   [:predecessor-revision {:optional true} [:and :int [:>= 0]]]
   [:predecessor-state-hash {:optional true} :string]
   [:sandbox-puts {:optional true} :map]
   [:sandbox-deletes {:optional true} [:set :keyword]]
   [:var-creation-times [:map-of :keyword [:and :int [:>= 0]]]]
   [:usage [:map
            [:prompt-tokens [:and :int [:>= 0]]]
            [:completion-tokens [:and :int [:>= 0]]]
            [:total-tokens [:and :int [:>= 0]]]]]
   [:cumulative-tree-ms [:and :int [:>= 0]]]
   [:iteration-attempts [:map-of [:and :int [:>= 0]]
                         [:and :int [:>= 0]]]]
   [:campaign-started-at-ms [:and :int [:>= 0]]]
   [:campaign-deadline-ms [:and :int [:>= 0]]]
   [:observed-quantum-duration-ms {:optional true} [:and :int [:>= 0]]]
   [:max-observed-quantum-duration-ms {:optional true} [:and :int [:>= 0]]]]
   [:fn {:error/message
         "version-3 researcher resume state must match its full-snapshot or sandbox-delta shape"}
    logic/valid-v3-shape?]])

(def researcher-iteration-record
  [:and
   [:map {:closed true}
   [:iteration-index [:and :int [:>= 0]]]
   [:attempt-ordinal [:and :int [:>= 0]]]
   [:status [:enum :success :failure :timeout :blocked]]
   [:block-reason {:optional true} logic/durable-value]
   [:started-at {:optional true} :string]
   [:completed-at {:optional true} :string]
   [:duration-ms {:optional true} [:and :int [:>= 0]]]
   [:code {:optional true} [:maybe :string]]
   [:reasoning
    {:optional true}
    [:maybe [:string {:max logic/reasoning-max-chars}]]]
   [:generated-code-recorded? {:optional true} :boolean]
   [:emitted-tree-recorded? {:optional true} :boolean]
   [:emitted-tree {:optional true} emitted-dsl]
   [:emitted-tree-source {:optional true} :string]
   [:tree-fingerprint {:optional true} :string]
   [:error-class {:optional true} :string]
   [:error-excerpt
    {:optional true}
    [:string {:max logic/error-excerpt-max-chars}]]
   [:variable-delta
    {:optional true}
    [:map {:closed true}
     [:created-keys [:vector :keyword]]
     [:updated-keys [:vector :keyword]]
     [:removed-keys [:vector :keyword]]]]
   [:result-profile
    {:optional true}
    [:map {:closed true}
     [:type [:enum :string :vector :map :other]]
     [:length [:and :int [:>= 0]]]
     [:word-count {:optional true} [:and :int [:>= 0]]]
     [:line-count {:optional true} [:and :int [:>= 0]]]]]
   [:stdout-profile
    {:optional true}
    [:map {:closed true}
     [:type [:enum :string :vector :map :other]]
     [:length [:and :int [:>= 0]]]
     [:word-count {:optional true} [:and :int [:>= 0]]]
     [:line-count {:optional true} [:and :int [:>= 0]]]]]]
   [:fn {:error/message
         "a recorded emitted tree requires both its durable tree and fingerprint"}
    (fn [record]
      (or (not (:emitted-tree-recorded? record))
          (and (some? (:emitted-tree record))
               (seq (:tree-fingerprint record)))))]
   [:fn {:error/message
         "a blocked researcher iteration must carry its opaque block reason"}
    (fn [record]
      (= (= :blocked (:status record))
         (contains? record :block-reason)))]])

(def field-type
  "Supported field types for blackboard values (legacy)"
  [:enum :text :number :yesno :list :document :image :table])

(def legacy-type->malli
  "Maps legacy field types to Malli schemas"
  {:text :string
   :number :double
   :yesno :boolean
   :list [:vector :string]
   :document :string
   :image :string
   :table [:vector [:map-of :string ::durable-value]]})

(def decorator-type
  "Decorator types that modify node behavior"
  [:enum :retry :timeout :repeat])

(def condition-op
  "Operators for condition node checks"
  [:enum :equals :not-equals :gt :lt :gte :lte :contains :exists :truthy])

(def on-fail-behavior
  "What to return when a condition check fails"
  [:enum :failure :running])

(def execution-mode
  "Which version to use for execution"
  [:enum :draft :published])

;; =============================================================================
;; Domain Value Objects
;; =============================================================================

(def decorator
  "Decorator that modifies node execution behavior"
  [:map
   [:type decorator-type]
   [:config :map]])

(def field-value
  "A typed field value for blackboard entries.
   Schema can be any valid Malli schema (keyword like :string, or vector like [:map ...])"
  [:map
   [:schema malli-definition]
   [:value logic/durable-value]])

(def condition-check
  "A condition check definition for condition nodes"
  [:map
   [:key :keyword]
   [:op condition-op]
   [:value {:optional true} logic/durable-value]
   [:on-fail {:optional true} on-fail-behavior]])

(def tool-contracts
  "Authoritative Malli contracts keyed by the exact bound tool name."
  [:map-of :string
   [:map {:closed true}
    [:arguments {:optional true} malli-definition]
    [:result {:optional true} malli-definition]
    [:checkpoint-safe? {:optional true} :boolean]]])

(def execution-context
  [:map {:closed true}
      [:ai.obney.orc.orc-service.core.todo-processors/map-each-index {:optional true} [:and :int [:>= 0]]]
      [:ai.obney.orc.orc-service.core.todo-processors/map-each-parent {:optional true} :uuid]
      [:ai.obney.orc.orc-service.core.todo-processors/tick-iteration {:optional true} [:and :int [:>= 1]]]])

(def ordinary-provider-reservation
  [:map
    [:admission-kind [:= :ordinary]]
    [:budget-sheet-id :uuid] [:budget-tick-id :uuid]
    [:sheet-id :uuid] [:tick-id :uuid] [:node-id :uuid] [:node-start-id :uuid]
    [:exec-context execution-context]
    [:candidate-index [:and :int [:>= 0]]] [:node-attempt [:and :int [:>= 1]]]
    [:logical-action-identity :string] [:invocation-identity :string]
    [:provider-attempt-ordinal [:and :int [:>= 0]]] [:reserved-at :string]])

(def legacy-provider-reservation-command
  [:map
    [:budget-sheet-id :uuid]
    [:budget-tick-id :uuid]
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:campaign-sheet-id :uuid]
    [:campaign-tick-id :uuid]
    [:campaign-node-id :uuid]
    [:iteration-index [:and :int [:>= 0]]]
    [:logical-action-identity :string]
    [:invocation-identity :string]
    [:provider-attempt-ordinal [:and :int [:>= 0]]]
    [:ownership-epoch [:and :int [:>= 1]]]
    [:reserved-at :string]])

(def legacy-provider-reservation-event
  [:map
    [:budget-sheet-id :uuid]
    [:budget-tick-id :uuid]
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:campaign-tick-id :uuid]
    [:campaign-node-id :uuid]
    [:iteration-index [:and :int [:>= 0]]]
    [:logical-action-identity :string]
    [:invocation-identity :string]
    [:provider-attempt-ordinal [:and :int [:>= 0]]]
    [:ownership-epoch [:and :int [:>= 1]]]
    [:reserved-at :string]])

(def campaign-provider-reservation
  [:map
    [:admission-kind [:= :campaign]]
    [:budget-sheet-id :uuid]
    [:budget-tick-id :uuid]
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:campaign-sheet-id :uuid]
    [:campaign-tick-id :uuid]
    [:campaign-node-id :uuid]
    [:iteration-index [:and :int [:>= 0]]]
    [:logical-action-identity :string]
    [:invocation-identity :string]
    [:provider-attempt-ordinal [:and :int [:>= 0]]]
    [:ownership-epoch [:and :int [:>= 1]]]
    [:reserved-at :string]
    [:model-configuration-id {:optional true} :string]
    [:model-logical-action-id {:optional true} :string]])

(def recoverable-fn
  [:map {:closed true}
   [:version [:= 1]] [:context-version [:= 1]]
   [:source :string] [:sha256 [:re #"[0-9a-f]{64}"]]
   [:bindings [:map {:closed true}]]])

(defschemas domain
  {::durable-value logic/durable-value

   ::sheet
   [:map
    [:id :uuid]
    [:name :string]
    [:root-node-id {:optional true} :uuid]
    [:created-at {:optional true} :string]
    [:draft-dirty? {:optional true} :boolean]
    [:published-version {:optional true} :int]
    [:execution-mode {:optional true} execution-mode]
    [:has-stash? {:optional true} :boolean]]

   ::node
   [:map
    [:id :uuid]
    [:sheet-id :uuid]
    [:type node-type]
    [:name :string]
    [:parent-id {:optional true} :uuid]
    [:children-ids [:vector :uuid]]
    [:status node-status]
    [:provider {:optional true} :keyword]
    [:fallback-models {:optional true}
     [:vector [:map {:closed true}
               [:model [:string {:min 1}]]
               [:provider {:optional true} :keyword]
               [:options {:optional true} [:map]]]]]
    [:fallback-policy {:optional true} [:enum :temporary-transport :all-transport]]
    [:model-fallback-policy {:optional true} [:enum :temporary-transport :all-transport]]
    [:instruction {:optional true} :string]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]
    [:decorators [:vector decorator]]
    [:executor {:optional true} executor-type]
    [:model {:optional true} :string]
    [:options-from {:optional true} :keyword]
    [:bands-from {:optional true} :keyword]
    [:min-confidence {:optional true} :double]
    [:abstain {:optional true} logic/durable-value]
    [:fn {:optional true} :string]
    [:recoverable-fn {:optional true} recoverable-fn]
    [:tool {:optional true} :string]
    [:tools {:optional true} [:vector :keyword]]
    [:options {:optional true} :map]
    [:retry {:optional true} [:map
                              [:max-attempts :int]
                              [:backoff-ms [:vector :int]]]]
    [:check {:optional true} condition-check]
    [:success-policy {:optional true} parallel-success-policy]
    [:failure-policy {:optional true} parallel-failure-policy]
    [:source-key {:optional true} :keyword]
    [:item-key {:optional true} :keyword]
    [:output-key {:optional true} :keyword]
    [:max-concurrency {:optional true} :int]
    [:preserve-failures? {:optional true} :boolean]
    [:mcp-tools {:optional true} [:vector :string]]
    [:tool-contracts {:optional true} tool-contracts]
    [:tool-caller-fn {:optional true} :string]
    [:max-iterations {:optional true} :int]
    [:timeout-ms {:optional true} :int]
    [:target-sheet-id {:optional true} :uuid]
    [:delegate-timeout-ms {:optional true} [:and :int [:> 0]]]
    [:delegate-max-ticks {:optional true} [:and :int [:> 0]]]
    [:inherit-ontology? {:optional true} :boolean]
    [:last-error {:optional true} :string]]

   ::blackboard-entry
   [:map
    [:key :keyword]
    [:schema malli-definition]
    [:value {:optional true} logic/durable-value]
    [:version :int]]

   ::node-layout
   [:map
   [:node-id :uuid]
    [:row :int]
    [:start-col :double]
    [:end-col :double]]

   ::version-snapshot
   [:map
    [:snapshot-id :uuid]
    [:sheet-id :uuid]
    [:version-number :int]
    [:published-at trace-timestamp]
    [:description {:optional true} :string]
    [:snapshot :map]]

   ::stash
   [:map
    [:stash-id :uuid]
    [:sheet-id :uuid]
    [:stashed-at trace-timestamp]
    [:snapshot :map]]

   ::injected-candidate
   [:map
    [:axis [:enum :structural :behavioral]]
    [:candidate-id :string]
    [:version {:optional true} [:maybe :int]]
    [:score {:optional true} [:maybe :double]]]

   ;; -------------------------------------------------------------------------
   ;; Execution Trace Schemas
   ;; -------------------------------------------------------------------------

   ::node-trace
   [:map
    [:model-fallback {:optional true}
     [:map [:policy [:enum :temporary-transport :all-transport]]
      [:reason [:enum :selected :blocked :timeout :candidates-exhausted :policy-refused :output-exposed]]
      [:candidate-index :int] [:candidate-node-id {:optional true} :uuid]
      [:requested-model [:maybe :string]] [:provider {:optional true} :keyword]
      [:attempts {:optional true} [:vector :map]]
      [:recovery-accounting {:optional true}
       [:map [:source [:= :runtime-receipts]] [:coverage [:enum :complete :partial]]
        [:completed-receipts [:and :int [:>= 0]]]
        [:missing-earlier-receipts [:and :int [:>= 0]]]
        [:incomplete-receipts {:optional true} [:and :int [:>= 0]]]]]
      [:predecessor-event-id {:optional true} :uuid]]]
    [:node-id :uuid]
    [:delegate-child-tick-id {:optional true} :uuid]
    [:delegate-child-status {:optional true} :keyword]
    [:completion-delivered? {:optional true} :boolean]
    [:trace-instance-id :uuid]
    [:parent-trace-instance-id {:optional true} :uuid]
    [:node-name :string]
    [:node-type node-type]
    [:executor {:optional true} executor-type]
    [:parent-id {:optional true} :uuid]
    [:path [:vector :string]]
    [:child-index {:optional true} :int]
    [:exec-context {:optional true} :map]
    [:status [:enum :success :failure :running :partial :timeout :blocked]]
    [:started-at trace-timestamp]
    [:completed-at {:optional true} trace-timestamp]
    [:duration-ms {:optional true} :int]
    [:provider-attempt {:optional true} :int]
    [:max-provider-attempts {:optional true} :int]
    [:node-attempt {:optional true} :int]
    [:max-node-attempts {:optional true} :int]
    [:provider-timeout-ms {:optional true} [:maybe :int]]
    [:execution-deadline-ms {:optional true} [:maybe :int]]
    [:execution-budget-remaining-ms {:optional true} [:maybe :int]]
    [:read-keys {:optional true} [:vector :keyword]]
    [:write-keys {:optional true} [:vector :keyword]]
    [:missing-write-keys {:optional true} [:vector :keyword]]
    [:rejected-write-keys {:optional true} [:vector :keyword]]
    [:input-profile {:optional true} [:map-of :keyword :map]]
    [:output-profile {:optional true} [:map-of :keyword :map]]
    [:rejected-output-profile {:optional true} [:map-of :keyword :map]]
    [:failure-kind {:optional true} structured-failure-kind]
    [:provider-evidence {:optional true} provider-failure-evidence]
    [:error {:optional true} :string]]

   ::execution-trace
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:parent-trace-id {:optional true} :uuid]
    [:correlation-id {:optional true} :uuid]
    [:root-trace-id :uuid]
    [:child-trace-ids [:vector :uuid]]
    [:version-number {:optional true} :int]
    [:started-at trace-timestamp]
    [:completed-at trace-timestamp]
    [:duration-ms :int]
    [:status [:enum :success :failure :timeout :partial :blocked]]
    [:configured-max-ticks {:optional true} [:and :int [:> 0]]]
    [:consumed-ticks {:optional true} :int]
    [:terminal-reason {:optional true} :keyword]
    [:input-snapshot [:map-of :keyword :map]]
    [:output-snapshot [:map-of :keyword :map]]
    [:source-event-count {:optional true} [:and :int [:>= 0]]]
    [:node-traces [:vector stored-node-trace]]
    [:researcher-iterations {:optional true} [:vector traced-iteration]]
    [:researcher-events {:optional true} [:vector researcher-event]]
    [:error {:optional true} :string]]

   ;; -------------------------------------------------------------------------
   ;; Tree Metadata Schemas
   ;; -------------------------------------------------------------------------

   ::tree-metadata
   [:map
    [:sheet-id :uuid]
    [:name :string]
    [:description {:optional true} :string]
    [:problem-types [:vector :string]]
    [:capabilities [:vector :string]]
    [:patterns [:vector :string]]
    [:node-types [:vector :keyword]]
    [:node-count :int]
    [:has-validation? :boolean]
    [:has-retry? :boolean]
    [:has-parallel? :boolean]
    [:has-map-each? :boolean]
    [:execution-count {:optional true} :int]
    [:avg-score {:optional true} :double]
    [:last-executed {:optional true} :string]
    [:extracted-at :string]]})

(defschemas commands
  {

   :sheet/create-sheet
   [:map
    [:name :string]]

   :sheet/rename-sheet
   [:map
    [:sheet-id :uuid]
    [:name :string]]

   :sheet/delete-sheet
   [:map
    [:sheet-id :uuid]]

   ;; -------------------------------------------------------------------------
   ;; Node Commands
   ;; -------------------------------------------------------------------------

   :sheet/create-node
   [:map
    [:sheet-id :uuid]
    [:node-id {:optional true} :uuid]
    [:type node-type]
    [:parent-id {:optional true} :uuid]
    [:index {:optional true} :int]]

   :sheet/move-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:new-parent-id :uuid]
    [:index :int]]

   :sheet/reorder-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:index :int]]

   :sheet/delete-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]]

   :sheet/set-node-name
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:name :string]]

   :sheet/set-node-instruction
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]]

   :sheet/set-node-context
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:context logic/workflow-values]]

   :sheet/set-node-io
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]]

   :sheet/set-node-decorators
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:decorators [:vector decorator]]]

   :sheet/set-node-check
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:check condition-check]]

   :sheet/set-node-executor
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:executor executor-type]
    [:model {:optional true} :string]
    [:fn {:optional true} :string]
    [:recoverable-fn {:optional true} recoverable-fn]
    [:tool {:optional true} :string]
    [:tools {:optional true} [:vector :keyword]]
    [:options {:optional true} :map]
    [:options-from {:optional true} :keyword]
    [:bands-from {:optional true} :keyword]
    [:min-confidence {:optional true} :double]
    [:abstain {:optional true} logic/durable-value]
    [:tool-caller-fn {:optional true} :string]
    [:tool-contracts {:optional true} tool-contracts]]

   :sheet/set-node-model-policy
   [:map [:sheet-id :uuid] [:node-id :uuid]
    [:options {:optional true} [:map]]
    [:provider {:optional true} :keyword]
    [:fallback-models {:optional true}
     [:vector [:map {:closed true}
               [:model [:string {:min 1}]]
               [:provider {:optional true} :keyword]
               [:options {:optional true} [:map]]]]]
    [:fallback-policy {:optional true} [:enum :temporary-transport :all-transport]]]

   :sheet/set-node-retry
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:retry [:map
             [:max-attempts :int]
             [:backoff-ms [:vector :int]]]]]

   :sheet/set-parallel-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:success-policy {:optional true} parallel-success-policy]
    [:failure-policy {:optional true} parallel-failure-policy]]

   :sheet/set-map-each-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:source-key :keyword]
    [:item-key :keyword]
    [:output-key :keyword]
    [:max-concurrency {:optional true} :int]
    [:preserve-failures? {:optional true} :boolean]]

   :sheet/set-llm-condition-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]
    [:reads [:vector :keyword]]
    [:model {:optional true} :string]]

   :sheet/set-repl-researcher-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]
    [:mcp-tools [:vector :string]]
    [:tool-contracts {:optional true} tool-contracts]
    [:tool-caller-fn {:optional true} :string]
    [:model {:optional true} :string]
    [:max-iterations {:optional true} :int]
    [:rlm {:optional true}
     [:and
      [:or :boolean :map]
      [:fn {:error/message
            "sandbox snapshot interval must be a positive integer"}
       (fn [rlm]
         (or (boolean? rlm)
             (not (contains? rlm :sandbox-snapshot-interval))
             (let [interval (:sandbox-snapshot-interval rlm)]
               (and (integer? interval) (pos? interval)))))]]]
    [:options {:optional true} :map]
    [:timeout-ms {:optional true} :int]]

   :sheet/set-delegate-config
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:target-sheet-id :uuid]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]
    [:timeout-ms {:optional true} [:and :int [:> 0]]]
    [:max-ticks {:optional true} [:and :int [:> 0]]]
    [:inherit-ontology? {:optional true} :boolean]]

   ;; -------------------------------------------------------------------------
   ;; Blackboard Commands
   ;; -------------------------------------------------------------------------

   :sheet/declare-key
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:schema malli-definition]]

   :sheet/update-key-schema
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:schema malli-definition]]

   :sheet/set-key-value
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:value logic/durable-value]]

   :sheet/delete-key
   [:map
    [:sheet-id :uuid]
    [:key :keyword]]

   :sheet/set-content-hash
   [:map
    [:sheet-id :uuid]
    [:content-hash :string]]

   ;; -------------------------------------------------------------------------
   ;; Judge Commands
   ;; -------------------------------------------------------------------------

   :sheet/declare-judge
   [:map
    [:sheet-id :uuid]
    [:judge-name :string]
    [:judge-config judge-config-schema]]

   :sheet/revise-judge
   [:map
    [:sheet-id :uuid]
    [:judge-name :string]
    [:judge-config judge-config-schema]]

   :sheet/set-node-judges
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:judges [:vector :string]]]

   ;; -------------------------------------------------------------------------
   ;; Execution Commands
   ;; -------------------------------------------------------------------------

   :sheet/tick-tree
   [:map
    [:sheet-id :uuid]
    [:tick-id {:optional true} :uuid]
    [:parent-tick-id {:optional true} :uuid]
    [:correlation-id {:optional true} :uuid]
    [:inputs {:optional true} :map]
    [:input-sources {:optional true} [:map-of :keyword
                                      [:map [:tick-id :uuid] [:event-id :uuid]]]]
    [:use-version {:optional true} :int]
    [:force-draft {:optional true} :boolean]
    [:options {:optional true} :map]
    [:tool-context {:optional true} :map]]

   :sheet/tick-node
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:tick-id {:optional true} :uuid]
    [:overrides {:optional true} logic/workflow-values]]

   :sheet/cancel-tick
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:reason {:optional true} [:maybe :string]]]

   :sheet/resume-node-execution
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
   [:node-id :uuid]
   [:original-start-event-id :uuid]
    [:researcher-ownership-epoch {:optional true} [:and :int [:> 0]]]
    [:inputs logic/workflow-values]]

   :sheet/renew-node-execution-lease
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:exec-context {:optional true} execution-context]
    [:start-event-id :uuid]
    [:lease-owner :uuid]
    [:lease-expires-at :string]]

   :sheet/checkpoint-researcher-iteration
   [:or
    [:map
     [:sheet-id :uuid]
     [:tick-id :uuid]
     [:node-id :uuid]
     [:checkpoint :map]
     [:resume? {:optional true} :boolean]
     [:inputs logic/workflow-values]]
    [:map
     [:sheet-id :uuid]
     [:tick-id :uuid]
     [:node-id :uuid]
     [:resume-state researcher-resume-state-v2]
     [:iteration-record researcher-iteration-record]
     [:sandbox-snapshot-interval {:optional true} [:and :int [:> 0]]]
     [:resume? {:optional true} :boolean]
     [:inputs logic/workflow-values]]]

   :sheet/record-researcher-action
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:action-id :string]
    [:action-kind :keyword]
    [:iteration :int]
    [:result logic/durable-value]
    [:completed-at :string]]

   :sheet/claim-researcher-frontier
   [:map
   [:sheet-id :uuid]
   [:tick-id :uuid]
   [:node-id :uuid]
   [:budget-root-tick-id {:optional true} :uuid]
   [:ownership-epoch [:and :int [:>= 1]]]
    [:campaign-started-at-ms {:optional true} [:and :int [:>= 0]]]
   [:campaign-deadline-ms {:optional true} [:and :int [:>= 0]]]
    [:claimed-at :string]]

   :sheet/expire-researcher-classification
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:ownership-epoch [:and :int [:>= 1]]]
    [:expired-at :string]]

   :sheet/claim-researcher-effect
   [:map
   [:sheet-id :uuid]
   [:tick-id :uuid]
   [:node-id :uuid]
    [:budget-root-tick-id {:optional true} :uuid]
    [:iteration-index [:and :int [:>= 0]]]
    [:logical-action-identity :string]
    [:attempt-identity :string]
    [:attempt-ordinal [:and :int [:>= 0]]]
    [:ownership-epoch [:and :int [:>= 1]]]
    [:kind [:enum :provider :tool :generated-child :behavior-mint]]
    [:claimed-at :string]]

   :sheet/reserve-provider-call
   [:or ordinary-provider-reservation campaign-provider-reservation legacy-provider-reservation-command]

   :sheet/complete-researcher-effect
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:logical-action-identity :string]
    [:attempt-identity :string]
    [:ownership-epoch [:and :int [:>= 1]]]
    [:result logic/durable-value]
    [:resolved-at :string]]

   :sheet/mark-researcher-effect-indeterminate
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:logical-action-identity :string]
    [:attempt-identity :string]
    [:ownership-epoch [:and :int [:>= 1]]]
    [:resolved-at :string]]

   :sheet/complete-node-execution
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:completion-id {:optional true} :uuid]
    [:researcher-ownership-epoch {:optional true} [:and :int [:>= 1]]]
    [:status [:enum :success :failure :tree-generated :partial :timeout :blocked]]
    [:writes {:optional true} logic/workflow-values]
    [:missing-write-keys {:optional true} [:vector :keyword]]
    [:rejected-writes {:optional true} logic/workflow-values]
    [:write-sources {:optional true} [:map-of :keyword
                                      [:map [:tick-id :uuid] [:event-id :uuid]]]]
    [:write-references? {:optional true} :boolean]
    [:block-payload {:optional true} logic/durable-value]
    [:duration-ms {:optional true} :int]
    [:observed-quantum-duration-ms {:optional true}
     [:and :int [:>= 0]]]
    [:max-observed-quantum-duration-ms {:optional true}
     [:and :int [:>= 0]]]
    [:inputs {:optional true} logic/workflow-values]
    [:read-sources {:optional true} [:map-of :keyword
                                     [:map [:tick-id :uuid] [:event-id :uuid]]]]
    [:raw-response {:optional true} :string]
    [:condition-answer {:optional true} :boolean]
    [:decision {:optional true} decision-record]
    [:failure-kind {:optional true} structured-failure-kind]
    [:provider-evidence {:optional true} provider-failure-evidence]
    [:completion-kind {:optional true} [:enum :tree-iteration :terminal]]
    [:usage {:optional true}
     [:map
      [:prompt-tokens {:optional true} :int]
      [:completion-tokens {:optional true} :int]
      [:total-tokens {:optional true} :int]
      [:cost {:optional true :description "Provider-reported cost of the calls, when reported"} number?]]]
    [:own-usage {:optional true}
     [:map
      [:prompt-tokens {:optional true} :int]
      [:completion-tokens {:optional true} :int]
      [:total-tokens {:optional true} :int]
      [:cost {:optional true :description "Provider-reported cost of the calls, when reported"} number?]]]
    [:model {:optional true} :string]
    [:requested-model {:optional true} :string]
    [:resolved-model {:optional true} :string]
    [:partial-summary {:optional true} partial-summary]
    [:node-type {:optional true} :keyword]]

   :sheet/fail-node-execution
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:error :string]
    [:researcher-expected-frontier-epoch {:optional true} [:and :int [:>= 0]]]
    [:duration-ms {:optional true} :int]]

   :sheet/record-rlm-tree-node-completion
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:node-path [:vector :map]]
    [:usage
     [:map
      [:prompt-tokens {:optional true} :int]
      [:completion-tokens {:optional true} :int]
      [:total-tokens {:optional true} :int]
      [:cost {:optional true :description "Provider-reported cost of the calls, when reported"} number?]]]
    [:input-profile {:optional true} [:map-of :keyword :map]]]

   :sheet/record-rlm-tree-execution-completion
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:trajectory [:vector :map]]
    [:total-usage [:map]]
    [:task-fingerprint {:optional true} [:maybe :string]]
    [:tree-fingerprint {:optional true} [:maybe :string]]
    [:status {:optional true} [:enum :success :failure :partial :timeout]]
    [:duration-ms {:optional true} :int]
    [:generated-tree {:optional true} [:maybe emitted-dsl]]
    [:generated-tree-source {:optional true} [:maybe :string]]
    [:source-sheet-id {:optional true} [:maybe :uuid]]
    [:source-tick-id {:optional true} [:maybe :uuid]]]

   ;; -------------------------------------------------------------------------
   ;; Versioning Commands
   ;; -------------------------------------------------------------------------

   :sheet/publish-version
   [:map
    [:sheet-id :uuid]
    [:description {:optional true} :string]]

   :sheet/revert-to-version
   [:map
    [:sheet-id :uuid]
    [:version-number :int]]

   :sheet/restore-stash
   [:map
    [:sheet-id :uuid]]

   :sheet/set-execution-mode
   [:map
    [:sheet-id :uuid]
    [:mode execution-mode]]

   ;; -------------------------------------------------------------------------
   ;; Version-Targeted Execution Commands
   ;; -------------------------------------------------------------------------

   :sheet/execute-version
   [:map
    [:sheet-id :uuid]
    [:version-number :int]
    [:inputs {:optional true} :map]]

   :sheet/batch-execute
   [:map
    [:sheet-id :uuid]
    [:version-number {:optional true} :int]
    [:inputs [:vector :map]]]


   :sheet/emit-tick-started
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]]

   :sheet/emit-tick-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:correlation-id {:optional true} :uuid]
    [:root-status :keyword]]

   :sheet/store-execution-trace
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:parent-trace-id {:optional true} :uuid]
    [:correlation-id {:optional true} :uuid]
    [:root-trace-id :uuid]
    [:child-trace-ids [:vector :uuid]]
    [:version-number {:optional true} :int]
    [:started-at trace-timestamp]
    [:completed-at trace-timestamp]
    [:duration-ms :int]
    [:status :keyword]
    [:configured-max-ticks {:optional true} [:and :int [:> 0]]]
    [:consumed-ticks {:optional true} :int]
    [:terminal-reason {:optional true} :keyword]
    [:input-snapshot [:map-of :keyword :map]]
    [:output-snapshot [:map-of :keyword :map]]
    [:source-event-count {:optional true} [:and :int [:>= 0]]]
    [:node-traces [:vector stored-node-trace]]
    [:researcher-iterations {:optional true} [:vector traced-iteration]]
    [:researcher-events {:optional true} [:vector researcher-event]]
    [:error {:optional true} :string]]

   :sheet/refresh-execution-trace
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:parent-trace-id {:optional true} :uuid]
    [:correlation-id {:optional true} :uuid]
    [:root-trace-id :uuid]
    [:child-trace-ids [:vector :uuid]]
    [:version-number {:optional true} :int]
    [:started-at trace-timestamp]
    [:completed-at trace-timestamp]
    [:duration-ms :int]
    [:status :keyword]
    [:configured-max-ticks {:optional true} [:and :int [:> 0]]]
    [:consumed-ticks {:optional true} :int]
    [:terminal-reason {:optional true} :keyword]
    [:input-snapshot [:map-of :keyword :map]]
    [:output-snapshot [:map-of :keyword :map]]
    [:source-event-count [:and :int [:>= 0]]]
    [:node-traces [:vector stored-node-trace]]
    [:researcher-iterations {:optional true} [:vector traced-iteration]]
    [:researcher-events {:optional true} [:vector researcher-event]]
    [:error {:optional true} :string]]

   ;; -------------------------------------------------------------------------
   ;; Tree Metadata Commands
   ;; -------------------------------------------------------------------------

   :sheet/extract-tree-metadata
   [:map
    [:sheet-id :uuid]
    [:problem-types {:optional true} [:vector :string]]
    [:description {:optional true} :string]]


   :sheet/record-injection
   [:map
    [:intervention/type [:enum :pattern-injection]]
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:turn-id {:optional true} [:maybe [:or :uuid :string]]]
    [:candidate-id {:optional true} [:maybe :string]]
    [:candidates [:vector ::injected-candidate]]
    [:task-class {:optional true} [:maybe :uuid]]
    [:model {:optional true} [:maybe :string]]
    [:rendered-chars :int]
    [:prompt-content-hash {:optional true} [:maybe :string]]
    [:root-trace-id {:optional true} [:maybe :uuid]]
    [:correlation-id {:optional true} [:maybe :uuid]]
    [:arm [:enum :treatment :holdout :claim-holdout]]
    [:baseline-policy-id :string]
    [:selection-propensity :double]
    [:rendered-block {:optional true} :string]
    [:recorded-at {:optional true} :string]]

   :sheet/commit-researcher-classification
   [:and
    [:map
     [:sheet-id :uuid]
     [:tick-id :uuid]
     [:node-id :uuid]
     [:ownership-epoch [:and :int [:>= 1]]]
     [:effects [:vector [:map [:command/name :keyword]]]]]
    [:fn
     {:error/message
      "classification commit requires one outcome bound to its sheet, tick, node, and epoch"}
     logic/valid-researcher-classification-commit?]]})

;; =============================================================================
;; Event Schemas
;; =============================================================================

(defschemas events
  {

   :sheet/sheet-created
   [:map
    [:sheet-id :uuid]
    [:name :string]]

   :sheet/sheet-renamed
   [:map
    [:sheet-id :uuid]
    [:old-name :string]
    [:new-name :string]]

   :sheet/sheet-deleted
   [:map
    [:sheet-id :uuid]]

   :sheet/content-hash-set
   [:map
    [:sheet-id :uuid]
    [:content-hash :string]]

   ;; -------------------------------------------------------------------------
   ;; Node Events
   ;; -------------------------------------------------------------------------

   :sheet/node-created
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:type node-type]
    [:parent-id {:optional true} :uuid]
    [:index {:optional true} :int]]

   :sheet/node-moved
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:old-parent-id {:optional true} :uuid]
    [:new-parent-id :uuid]
    [:index :int]]

   :sheet/node-reordered
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:old-index :int]
    [:new-index :int]]

   :sheet/node-deleted
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]]

   :sheet/node-name-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:name :string]
    [:previous-name {:optional true} :string]]

   :sheet/node-instruction-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]
    [:previous-instruction {:optional true} :string]]

   :sheet/node-context-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:context logic/workflow-values]
    [:previous-context {:optional true} logic/workflow-values]]

   :sheet/node-io-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]
    [:previous-reads {:optional true} [:vector :keyword]]
    [:previous-writes {:optional true} [:vector :keyword]]]

   :sheet/node-decorators-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:decorators [:vector decorator]]
    [:previous-decorators {:optional true} [:vector decorator]]]

   :sheet/node-check-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:check condition-check]
    [:previous-check {:optional true} condition-check]]

   :sheet/node-executor-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:executor executor-type]
    [:model {:optional true} :string]
    [:fn {:optional true} :string]
    [:recoverable-fn {:optional true} recoverable-fn]
    [:tool {:optional true} :string]
    [:tools {:optional true} [:vector :keyword]]
    [:options {:optional true} :map]
    [:options-from {:optional true} :keyword]
    [:bands-from {:optional true} :keyword]
    [:min-confidence {:optional true} :double]
    [:abstain {:optional true} logic/durable-value]
    [:tool-caller-fn {:optional true} :string]
    [:tool-contracts {:optional true} tool-contracts]
    [:previous-executor {:optional true} executor-type]
    [:previous-model {:optional true} :string]
    [:previous-fn {:optional true} :string]
    [:previous-tools {:optional true} [:vector :keyword]]
    [:previous-options {:optional true} :map]]

   :sheet/node-model-policy-set
   [:map [:sheet-id :uuid] [:node-id :uuid]
    [:options {:optional true} [:map]]
    [:provider {:optional true} :keyword]
    [:fallback-models {:optional true}
     [:vector [:map {:closed true}
               [:model [:string {:min 1}]]
               [:provider {:optional true} :keyword]
               [:options {:optional true} [:map]]]]]
    [:fallback-policy {:optional true} [:enum :temporary-transport :all-transport]]]

   :sheet/model-fallback-policy-set
   [:map [:sheet-id :uuid] [:node-id :uuid]
    [:model-fallback-policy [:enum :temporary-transport :all-transport]]]

   :sheet/node-retry-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:retry [:map
             [:max-attempts :int]
             [:backoff-ms [:vector :int]]]]
    [:previous-retry {:optional true} [:map
                                       [:max-attempts :int]
                                       [:backoff-ms [:vector :int]]]]]

   :sheet/parallel-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:success-policy {:optional true} parallel-success-policy]
    [:failure-policy {:optional true} parallel-failure-policy]
    [:previous-success-policy {:optional true} parallel-success-policy]
    [:previous-failure-policy {:optional true} parallel-failure-policy]]

   :sheet/map-each-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:source-key :keyword]
    [:item-key :keyword]
    [:output-key :keyword]
    [:max-concurrency {:optional true} :int]
    [:preserve-failures? {:optional true} :boolean]
    [:previous-source-key {:optional true} :keyword]
    [:previous-item-key {:optional true} :keyword]
    [:previous-output-key {:optional true} :keyword]
    [:previous-max-concurrency {:optional true} :int]]

   :sheet/llm-condition-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]
    [:reads [:vector :keyword]]
    [:model {:optional true} :string]
    [:previous-instruction {:optional true} :string]
    [:previous-reads {:optional true} [:vector :keyword]]
    [:previous-model {:optional true} :string]]

   :sheet/repl-researcher-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:instruction :string]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]
    [:mcp-tools [:vector :string]]
    [:tool-contracts {:optional true} tool-contracts]
    [:tool-caller-fn {:optional true} :string]
    [:model {:optional true} :string]
    [:max-iterations {:optional true} :int]
    [:rlm {:optional true} [:or :boolean :map]]
    [:options {:optional true} :map]
    [:timeout-ms {:optional true} :int]
    [:previous-instruction {:optional true} :string]
    [:previous-reads {:optional true} [:vector :keyword]]
    [:previous-writes {:optional true} [:vector :keyword]]
    [:previous-mcp-tools {:optional true} [:vector :string]]
    [:previous-tool-contracts {:optional true} tool-contracts]
    [:previous-tool-caller-fn {:optional true} :string]
    [:previous-model {:optional true} :string]
    [:previous-max-iterations {:optional true} :int]
    [:previous-rlm {:optional true} [:or :boolean :map]]
    [:previous-options {:optional true} :map]
    [:previous-timeout-ms {:optional true} :int]]

   :sheet/delegate-config-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:target-sheet-id :uuid]
    [:reads [:vector :keyword]]
    [:writes [:vector :keyword]]
    [:timeout-ms {:optional true} :int]
    [:max-ticks {:optional true} [:and :int [:> 0]]]
    [:inherit-ontology? {:optional true} :boolean]
    [:previous-target-sheet-id {:optional true} :uuid]
    [:previous-reads {:optional true} [:vector :keyword]]
    [:previous-writes {:optional true} [:vector :keyword]]
    [:previous-timeout-ms {:optional true} :int]
    [:previous-max-ticks {:optional true} [:and :int [:> 0]]]
    [:previous-inherit-ontology? {:optional true} :boolean]]

   ;; -------------------------------------------------------------------------
   ;; Blackboard Events
   ;; -------------------------------------------------------------------------

   :sheet/key-declared
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:schema malli-definition]]

   :sheet/key-schema-updated
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:schema malli-definition]
    [:previous-schema {:optional true} malli-definition]]

   :sheet/key-value-set
   [:map
    [:sheet-id :uuid]
    [:key :keyword]
    [:value logic/durable-value]
    [:previous-value {:optional true} logic/durable-value]
    [:version :int]]

   :sheet/key-deleted
   [:map
    [:sheet-id :uuid]
    [:key :keyword]]

   ;; -------------------------------------------------------------------------
   ;; Judge Events
   ;; -------------------------------------------------------------------------

   :sheet/judge-declared
   [:map
    [:sheet-id :uuid]
    [:judge-name :string]
    [:judge-config judge-config-schema]
    [:revision-number {:optional true} :int]
    [:criteria-version {:optional true} :int]]

   :sheet/judge-revised
   [:map
    [:sheet-id :uuid]
    [:judge-name :string]
    [:judge-config judge-config-schema]
    [:revision-number :int]]

   :sheet/node-judges-set
   [:map
    [:sheet-id :uuid]
    [:node-id :uuid]
    [:judges [:vector :string]]
    [:previous-judges {:optional true} [:vector :string]]]

   ;; -------------------------------------------------------------------------
   ;; Execution Events
   ;; -------------------------------------------------------------------------

   :sheet/recovery-scan-triggered
   [:map
    [:triggered-at :string]]

   :sheet/tree-tick-started
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:parent-tick-id {:optional true} :uuid]
    [:correlation-id {:optional true} :uuid]
    [:iteration {:optional true} :int]
    [:seed-sources {:optional true} [:map-of :keyword
                                     [:map [:tick-id :uuid] [:event-id :uuid]]]]
    [:execution-snapshot {:optional true} :map]
    [:version-number {:optional true} :int]
    [:options {:optional true} :map]
    [:tool-context {:optional true} :map]]

   :sheet/node-execution-started
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:inputs logic/workflow-values]
    [:resumed-from-event-id {:optional true} :uuid]
    [:researcher-ownership-epoch {:optional true} [:and :int [:> 0]]]
    [:lease-owner {:optional true} :uuid]
    [:lease-expires-at {:optional true} :string]]

   :sheet/node-execution-lease-renewed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:exec-context {:optional true} execution-context]
    [:start-event-id :uuid]
    [:lease-owner :uuid]
    [:lease-expires-at :string]]

   :sheet/ephemeral-evaluations-recorded
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:iteration :int]
    [:steps [:vector
             [:map
              [:node-id :uuid]
              [:trace-instance-id :uuid]
              [:status [:enum :success :failure :running :partial :timeout :blocked]]
              [:node-type [:enum :condition :sequence :fallback]]]]]]

   :sheet/model-fallback-transition-recorded
   [:map [:sheet-id :uuid] [:tick-id :uuid] [:node-id :uuid]
    [:candidate-node-id :uuid] [:candidate-index :int] [:predecessor-event-id :uuid]
    [:next-node-id {:optional true} :uuid]
    [:policy [:enum :temporary-transport :all-transport]]
    [:reason [:enum :selected :blocked :timeout :candidates-exhausted :policy-refused :eligible-transport]]
    [:advanced? :boolean] [:inputs {:optional true} :map]]

   :sheet/node-execution-completed
   [:map
    [:model-fallback {:optional true}
     [:map [:policy [:enum :temporary-transport :all-transport]]
      [:reason [:enum :selected :blocked :timeout :candidates-exhausted :policy-refused :output-exposed]]
      [:candidate-index :int] [:candidate-node-id {:optional true} :uuid]
      [:requested-model [:maybe :string]] [:provider {:optional true} :keyword]
      [:attempts {:optional true} [:vector :map]]
      [:recovery-accounting {:optional true}
       [:map [:source [:= :runtime-receipts]] [:coverage [:enum :complete :partial]]
        [:completed-receipts [:and :int [:>= 0]]]
        [:missing-earlier-receipts [:and :int [:>= 0]]]
        [:incomplete-receipts {:optional true} [:and :int [:>= 0]]]]]
      [:predecessor-event-id {:optional true} :uuid]]]
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:completion-id {:optional true} :uuid]
    [:researcher-ownership-epoch {:optional true} [:and :int [:>= 1]]]
    [:status [:enum :success :failure :running :tree-generated :partial :timeout :blocked]]
    [:write-keys {:optional true} [:vector :keyword]]
    [:write-profile {:optional true} [:map-of :keyword :map]]
    [:missing-write-keys {:optional true} [:vector :keyword]]
    [:rejected-write-keys {:optional true} [:vector :keyword]]
    [:rejected-write-profile {:optional true} [:map-of :keyword :map]]
    [:write-sources {:optional true} [:map-of :keyword
                                      [:map [:tick-id :uuid] [:event-id :uuid]]]]
    [:read-keys {:optional true} [:vector :keyword]]
    [:input-profile {:optional true} [:map-of :keyword :map]]
    [:read-sources {:optional true} [:map-of :keyword
                                     [:map [:tick-id :uuid] [:event-id :uuid]]]]
    [:writes {:optional true} logic/workflow-values]
    [:block-payload {:optional true} logic/durable-value]
    [:duration-ms {:optional true} :int]
    [:observed-quantum-duration-ms {:optional true}
     [:and :int [:>= 0]]]
    [:max-observed-quantum-duration-ms {:optional true}
     [:and :int [:>= 0]]]
    [:inputs {:optional true} logic/workflow-values]
    [:raw-response {:optional true} :string]
    [:condition-answer {:optional true} :boolean]
    [:decision {:optional true} decision-record]
    [:failure-kind {:optional true} structured-failure-kind]
    [:provider-evidence {:optional true} provider-failure-evidence]
    [:usage {:optional true}
     [:map
      [:prompt-tokens {:optional true} :int]
      [:completion-tokens {:optional true} :int]
      [:total-tokens {:optional true} :int]
      [:cost {:optional true :description "Provider-reported cost of the calls, when reported"} number?]]]
    [:own-usage {:optional true}
     [:map
      [:prompt-tokens {:optional true} :int]
      [:completion-tokens {:optional true} :int]
      [:total-tokens {:optional true} :int]
      [:cost {:optional true :description "Provider-reported cost of the calls, when reported"} number?]]]
    [:model {:optional true} :string]
    [:requested-model {:optional true} :string]
    [:resolved-model {:optional true} :string]
    [:partial-summary {:optional true} partial-summary]
    [:node-type {:optional true} :keyword]
    [:completion-kind {:optional true} [:enum :tree-iteration :terminal]]]

   :sheet/rlm-tree-node-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:node-path [:vector :map]]
    [:usage
     [:map
      [:prompt-tokens {:optional true} :int]
      [:completion-tokens {:optional true} :int]
      [:total-tokens {:optional true} :int]
      [:cost {:optional true :description "Provider-reported cost of the calls, when reported"} number?]]]
    [:input-profile {:optional true} [:map-of :keyword :map]]
    [:scores {:optional true} :map]
    [:feedback {:optional true} :string]]

   :sheet/rlm-tree-execution-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:trajectory [:vector :map]]
    [:total-usage [:map]]
    [:task-fingerprint {:optional true} [:maybe :string]]
    [:tree-fingerprint {:optional true} [:maybe :string]]
    [:status {:optional true} [:enum :success :failure :partial :timeout]]
    [:duration-ms {:optional true} :int]
    [:generated-tree {:optional true} [:maybe emitted-dsl]]
    [:generated-tree-source {:optional true} [:maybe :string]]
    [:source-sheet-id {:optional true} [:maybe :uuid]]
    [:source-tick-id {:optional true} [:maybe :uuid]]
    [:timestamp [:fn inst?]]]

   :sheet/tree-tick-completed
   [:map
    [:sheet-id :uuid]
   [:tick-id :uuid]
    [:correlation-id {:optional true} :uuid]
    [:iteration {:optional true} :int]
    [:configured-max-ticks {:optional true} [:and :int [:> 0]]]
    [:consumed-ticks {:optional true} :int]
    [:terminal-reason {:optional true} :keyword]
    [:root-status [:enum :success :failure :running :tree-generated :partial :timeout :blocked]]
    [:output-keys {:optional true} [:vector :keyword]]
    [:block-payload {:optional true} logic/durable-value]
    [:error {:optional true} :string]]

   :sheet/execution-value-written
   [:map
    [:tick-id :uuid]
    [:sheet-id :uuid]
    [:key :keyword]
    [:value {:optional true} logic/durable-value]
    [:value-reference {:optional true}
     [:map
      [:file-id :string]
      [:byte-size :int]
      [:content-hash :string]
      [:format [:enum :nippy]]]]
    [:value-profile {:optional true} value-profile]
    [:value-id {:optional true} :uuid]
    [:node-id {:optional true} :uuid]
    [:exec-context {:optional true} :map]
    [:tick-seed? {:optional true} :boolean]
    [:input-seed? {:optional true} :boolean]]

   :sheet/execution-value-rejected
   [:map
    [:tick-id :uuid]
    [:sheet-id :uuid]
    [:key :keyword]
    [:value {:optional true} logic/durable-value]
    [:value-reference {:optional true}
     [:map
      [:file-id :string]
      [:byte-size :int]
      [:content-hash :string]
      [:format [:enum :nippy]]]]
    [:value-profile {:optional true} value-profile]
    [:value-id {:optional true} :uuid]
    [:node-id :uuid]
    [:exec-context {:optional true} :map]]

   :sheet/execution-value-referenced
   [:map
    [:tick-id :uuid]
    [:sheet-id :uuid]
    [:key :keyword]
    [:source [:map [:tick-id :uuid] [:event-id :uuid]]]
    [:value-id {:optional true} :uuid]
    [:node-id {:optional true} :uuid]
    [:exec-context {:optional true} :map]
    [:input-seed? {:optional true} :boolean]]

   :sheet/tick-cancelled
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:reason {:optional true} [:maybe :string]]]

   :sheet/sequence-progress-updated
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:child-index :int]
    [:total-children :int]
    [:exec-context {:optional true} :map]]

   :sheet/map-each-progress-updated
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:item-index :int]
    [:total-items :int]]

   ;; -------------------------------------------------------------------------
   ;; Versioning Events
   ;; -------------------------------------------------------------------------

   :sheet/version-published
   [:map
    [:sheet-id :uuid]
    [:snapshot-id :uuid]
    [:version-number :int]
    [:description {:optional true} :string]
    [:snapshot :map]]

   :sheet/draft-stashed
   [:map
    [:sheet-id :uuid]
    [:stash-id :uuid]
    [:snapshot :map]]

   :sheet/draft-reverted
   [:map
    [:sheet-id :uuid]
    [:target-version :int]
    [:snapshot-id :uuid]
    [:snapshot :map]]

   :sheet/stash-restored
   [:map
    [:sheet-id :uuid]
    [:stash-id :uuid]
    [:snapshot :map]]

   :sheet/execution-mode-set
   [:map
    [:sheet-id :uuid]
    [:mode execution-mode]
    [:previous-mode {:optional true} execution-mode]]

   ;; -------------------------------------------------------------------------
   ;; Trace Events
   ;; -------------------------------------------------------------------------

   :sheet/execution-traced
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:parent-trace-id {:optional true} :uuid]
    [:correlation-id {:optional true} :uuid]
    [:root-trace-id :uuid]
    [:child-trace-ids [:vector :uuid]]
    [:version-number {:optional true} :int]
    [:started-at trace-timestamp]
    [:completed-at trace-timestamp]
    [:duration-ms :int]
    [:status [:enum :success :failure :timeout :partial :blocked]]
    [:configured-max-ticks {:optional true} [:and :int [:> 0]]]
    [:consumed-ticks {:optional true} :int]
    [:terminal-reason {:optional true} :keyword]
    [:input-snapshot [:map-of :keyword :map]]
    [:output-snapshot [:map-of :keyword :map]]
    [:source-event-count {:optional true} [:and :int [:>= 0]]]
    [:node-traces [:vector stored-node-trace]]
    [:researcher-iterations {:optional true} [:vector traced-iteration]]
    [:researcher-events {:optional true} [:vector researcher-event]]
    [:error {:optional true} :string]]

   :sheet/execution-trace-refreshed
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:parent-trace-id {:optional true} :uuid]
    [:correlation-id {:optional true} :uuid]
    [:root-trace-id :uuid]
    [:child-trace-ids [:vector :uuid]]
    [:version-number {:optional true} :int]
    [:started-at trace-timestamp]
    [:completed-at trace-timestamp]
    [:duration-ms :int]
    [:status [:enum :success :failure :timeout :partial :blocked]]
    [:configured-max-ticks {:optional true} [:and :int [:> 0]]]
    [:consumed-ticks {:optional true} :int]
    [:terminal-reason {:optional true} :keyword]
    [:input-snapshot [:map-of :keyword :map]]
    [:output-snapshot [:map-of :keyword :map]]
    [:source-event-count [:and :int [:>= 0]]]
    [:node-traces [:vector stored-node-trace]]
    [:researcher-iterations {:optional true} [:vector traced-iteration]]
    [:researcher-events {:optional true} [:vector researcher-event]]
    [:error {:optional true} :string]]

   ;; -------------------------------------------------------------------------
   ;; Execution Audit Events
   ;; -------------------------------------------------------------------------

   :sheet/version-executed
   [:map
    [:sheet-id :uuid]
    [:version-number :int]
    [:trace-id :uuid]
    [:status :keyword]
    [:duration-ms :int]]

   :sheet/batch-executed
   [:map
    [:sheet-id :uuid]
    [:version-number {:optional true} :int]
    [:total-executions :int]
    [:successful-count :int]
    [:failed-count :int]
    [:duration-ms :int]]

   ;; -------------------------------------------------------------------------
   ;; Tree Metadata Events
   ;; -------------------------------------------------------------------------

   :sheet/tree-metadata-extracted
   [:map
    [:sheet-id :uuid]
    [:metadata ::tree-metadata]]


   :rlm/tree-generated
   [:map
    [:tree-id :uuid]
    [:execution-id :uuid]
    [:raw-dsl emitted-dsl]
    [:source-edn {:optional true} :string]
    [:iteration-count {:optional true} :int]
    [:input-metadata {:optional true} [:map
                                       [:size :int]
                                       [:type :keyword]]]
    [:generated-at :string]
    [:node-id {:optional true} :uuid]
    [:sheet-id {:optional true} :uuid]]

   :rlm/tree-executed
   [:map
    [:tree-id :uuid]
    [:execution-id :uuid]
    [:status [:enum :success :failure]]
    [:outputs {:optional true} :map]
    [:duration-ms :int]
    [:node-traces {:optional true} [:vector [:map [:node-id :uuid] [:status {:optional true} node-status]]]]
    [:error {:optional true} :string]]

   :rlm/tree-evaluated
   [:map
    [:tree-id :uuid]
    [:execution-id :uuid]
    [:score :double]
    [:feedback :string]
    [:dimensions {:optional true} [:vector
                                   [:map
                                    [:name :string]
                                    [:weight :double]
                                    [:score :double]
                                    [:feedback :string]]]]
    [:evaluated-at :string]]

   :rlm/researcher-iterations
   [:map
    [:execution-id :uuid]
    [:iterations [:vector historical-iteration]]
    [:iteration-count :int]
    [:emitted-at :string]]

   :rlm/researcher-checkpointed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:checkpoint :map]
    [:yielded? {:optional true} :boolean]
    [:checkpointed-at :string]]

   :rlm/researcher-iteration-recorded
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:iteration-index :int]
    [:attempt-ordinal :int]
    [:iteration-record researcher-iteration-record]
    [:recorded-at :string]]

   :rlm/researcher-resume-state-saved
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:revision :int]
    [:next-iteration :int]
    [:resume-state [:or researcher-resume-state-v2 researcher-resume-state-v3]]
    [:yielded? {:optional true} :boolean]
    [:saved-at :string]]

   :rlm/researcher-action-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:action-id :string]
    [:action-kind :keyword]
    [:iteration :int]
    [:result logic/durable-value]
    [:completed-at :string]]

   :rlm/researcher-frontier-claimed
   [:map
    [:sheet-id :uuid]
   [:tick-id :uuid]
   [:node-id :uuid]
   [:ownership-epoch [:and :int [:>= 1]]]
    [:campaign-started-at-ms {:optional true} [:and :int [:>= 0]]]
   [:campaign-deadline-ms {:optional true} [:and :int [:>= 0]]]
    [:claimed-at :string]]

   :rlm/researcher-classification-expired
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:ownership-epoch [:and :int [:>= 1]]]
    [:expired-at :string]]

   :rlm/researcher-effect-claimed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:iteration-index [:and :int [:>= 0]]]
    [:logical-action-identity :string]
    [:attempt-identity :string]
    [:attempt-ordinal [:and :int [:>= 0]]]
    [:ownership-epoch [:and :int [:>= 1]]]
    [:kind [:enum :provider :tool :generated-child :behavior-mint]]
    [:status [:= :claimed]]
    [:claimed-at :string]
    [:resolved-at [:maybe :string]]]

   :sheet/provider-call-reserved
   [:or ordinary-provider-reservation campaign-provider-reservation legacy-provider-reservation-event]

   :rlm/researcher-effect-completed
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:logical-action-identity :string]
    [:attempt-identity :string]
    [:ownership-epoch [:and :int [:>= 1]]]
    [:status [:= :completed]]
    [:result logic/durable-value]
    [:resolved-at :string]]

   :rlm/researcher-effect-indeterminate
   [:map
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:logical-action-identity :string]
    [:attempt-identity :string]
    [:resolved-by-ownership-epoch [:and :int [:>= 1]]]
    [:status [:= :indeterminate]]
    [:resolved-at :string]]

   :intervention/injection-recorded
   [:map
    [:intervention/type [:enum :pattern-injection]]
    [:sheet-id :uuid]
    [:tick-id :uuid]
    [:node-id :uuid]
    [:turn-id {:optional true} [:maybe [:or :uuid :string]]]
    ;; --- verbatim from the deferred ledger schema ---
    [:candidate-id {:optional true} [:maybe :string]]
    [:task-class {:optional true} [:maybe :uuid]]
    [:model {:optional true} [:maybe :string]]
    [:prompt-content-hash {:optional true} [:maybe :string]]
    [:candidates [:vector ::injected-candidate]]
    [:rendered-chars :int]
    [:root-trace-id {:optional true} [:maybe :uuid]]
    [:correlation-id {:optional true} [:maybe :uuid]]
    [:arm [:enum :treatment :holdout :claim-holdout]]
    [:baseline-policy-id :string]
    [:selection-propensity :double]
    [:rendered-block {:optional true} :string]
    [:recorded-at :string]]})

(defschemas queries
  {

   :sheet/sheets-list-screen
   [:map]

   :sheet/sheets-list-screen-result
   [:map
    [:sheets [:vector ::sheet]]
    [:total :int]]


   :sheet/sheet-view-screen
   [:map
    [:sheet-id :uuid]]

   :sheet/sheet-view-screen-result
   [:map
    [:sheet ::sheet]
    [:nodes [:vector ::node]]
    [:blackboard [:vector ::blackboard-entry]]
    [:layout [:vector ::node-layout]]
    [:version-info {:optional true}
     [:map
      [:published-version {:optional true} :int]
      [:draft-dirty? :boolean]
      [:execution-mode execution-mode]
      [:has-stash? :boolean]]]]


   :sheet/export-sheet
   [:map
    [:sheet-id :uuid]]

   :sheet/export-sheet-result
   [:map
    [:version :int]
    [:exported-at :string]
    [:sheet [:map
             [:name :string]
             [:id :uuid]]]
    [:blackboard-schema [:map-of :keyword malli-definition]]
    [:nodes {:optional true} [:maybe exported-node]]]

   ;; -------------------------------------------------------------------------
   ;; Versioning Queries
   ;; -------------------------------------------------------------------------

   :sheet/version-history
   [:map
    [:sheet-id :uuid]]

   :sheet/version-history-result
   [:map
    [:versions [:vector ::version-snapshot]]
    [:current-published-version {:optional true} :int]
    [:draft-dirty? :boolean]
    [:has-stash? :boolean]]

   :sheet/get-version
   [:map
    [:sheet-id :uuid]
    [:version-number :int]]

   :sheet/get-version-result
   [:map
    [:version ::version-snapshot]]

   :sheet/get-stash
   [:map
    [:sheet-id :uuid]]

   :sheet/get-stash-result
   [:map
    [:stash {:optional true} ::stash]]

   ;; -------------------------------------------------------------------------
   ;; Trace Queries
   ;; -------------------------------------------------------------------------

   :sheet/get-trace
   [:map
    [:trace-id :uuid]]

   :sheet/get-trace-result
   [:map
    [:trace ::execution-trace]]

   :sheet/get-trace-family
   [:map
    [:trace-id :uuid]]

   ::trace-family-summary
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:correlation-id {:optional true} :uuid]
    [:parent-trace-id {:optional true} :uuid]
    [:root-trace-id :uuid]
    [:child-trace-ids [:vector :uuid]]
    [:status [:enum :success :failure :timeout :partial :blocked]]
    [:started-at trace-timestamp]
    [:duration-ms :int]
    [:node-count :int]]

   :sheet/get-trace-family-result
   [:map
    [:root-trace-id :uuid]
    [:traces [:vector ::trace-family-summary]]]

   :sheet/get-correlated-traces
   [:map
    [:correlation-id :uuid]]

   ::correlated-trace-family
   [:map
    [:root-trace-id :uuid]
    [:traces [:vector ::trace-family-summary]]]

   :sheet/get-correlated-traces-result
   [:map
    [:correlation-id :uuid]
    [:traces [:vector ::trace-family-summary]]
    [:families [:vector ::correlated-trace-family]]]

   :sheet/get-traces
   [:map
    [:sheet-id :uuid]
    [:version-number {:optional true} :int]
    [:status {:optional true} [:enum :success :failure :timeout :partial :blocked]]
    [:node-id {:optional true} :uuid]
    [:since {:optional true} trace-timestamp]
    [:limit {:optional true} :int]]

   :sheet/get-traces-result
   [:map
    [:traces [:vector ::execution-trace]]
    [:total :int]]


   :sheet/runs-screen
   [:map
    [:trace-id {:optional true} :uuid]
    [:status {:optional true} [:enum :success :failure :timeout :partial :blocked]]
    [:limit {:optional true} :int]]

   :sheet/runs-screen-result
   [:map
    [:traces [:vector ::execution-trace-summary]]
    [:total :int]
    [:selected-trace {:optional true} ::execution-trace]]

   ::execution-trace-summary
   [:map
    [:trace-id :uuid]
    [:sheet-id :uuid]
    [:parent-trace-id {:optional true} :uuid]
    [:root-trace-id :uuid]
    [:child-trace-ids [:vector :uuid]]
    [:sheet-name :string]
    [:status [:enum :success :failure :timeout :partial :blocked]]
    [:started-at trace-timestamp]
    [:duration-ms :int]
    [:node-count :int]
    [:version-number {:optional true} :int]]

   :sheet/node-trace-detail
   [:map
    [:trace-id :uuid]
    [:trace-instance-id :uuid]]

   :sheet/node-trace-detail-result
   [:map
    [:node-id :uuid]
    [:trace-instance-id :uuid]
    [:exec-context {:optional true} :map]
    [:inputs {:optional true} [:maybe :map]]
    [:outputs {:optional true} [:maybe :map]]
    [:rejected-outputs {:optional true} [:maybe :map]]
    [:failure-kind {:optional true} [:maybe structured-failure-kind]]
    [:condition-answer {:optional true} [:maybe :boolean]]
    [:decision {:optional true} [:maybe decision-record]]
    [:provider-evidence {:optional true} [:maybe provider-failure-evidence]]
    [:model {:optional true} [:maybe :string]]
    [:requested-model {:optional true} [:maybe :string]]
    [:resolved-model {:optional true} [:maybe :string]]]

   :sheet/run-detail-screen
   [:map
    [:trace-id :uuid]]

   :sheet/run-detail-screen-result
   [:map
    [:trace ::execution-trace]]

   ;; -------------------------------------------------------------------------
   ;; Structural Diff Query
   ;; -------------------------------------------------------------------------

   :sheet/diff-versions
   [:map
    [:sheet-id :uuid]
    [:from-version :int]
    [:to-version :int]]

   :sheet/diff-versions-result
   [:map
    [:node-diff [:map
                 [:added-nodes [:vector diff-node]]
                 [:removed-nodes [:vector diff-node]]
                 [:modified-nodes [:vector modified-node]]]]
    [:blackboard-diff [:map
                       [:added [:vector :keyword]]
                       [:removed [:vector :keyword]]
                       [:modified [:vector [:map [:key :keyword] [:old-schema malli-definition] [:new-schema malli-definition]]]]]]]

   ;; -------------------------------------------------------------------------
   ;; Node Statistics Query
   ;; -------------------------------------------------------------------------

   :sheet/node-stats
   [:map
    [:sheet-id :uuid]
    [:version-number {:optional true} :int]
    [:since {:optional true} trace-timestamp]
    [:node-ids {:optional true} [:vector :uuid]]]

   :sheet/node-stats-result
   [:map
    [:stats [:vector [:map
                      [:node-id :uuid]
                      [:node-name :string]
                      [:node-type node-type]
                      [:execution-count :int]
                      [:success-count :int]
                      [:failure-count :int]
                      [:success-rate :double]
                      [:avg-duration-ms {:optional true} :double]
                      [:p50-duration-ms {:optional true} :int]
                      [:p95-duration-ms {:optional true} :int]
                      [:common-errors {:optional true} [:vector [:map [:message :string] [:count [:int {:min 1}]]]]]]]]
    [:trace-count :int]]

   ;; -------------------------------------------------------------------------
   ;; Active Executions Dashboard Query
   ;; -------------------------------------------------------------------------

   :sheet/active-executions-screen
   [:map]

   :sheet/active-executions-screen-result
   [:map
    [:executions [:vector [:map
                           [:tick-id :uuid]
                           [:sheet-id :uuid]
                           [:sheet-name :string]
                           [:status [:enum :running :completed]]
                           [:started-at trace-timestamp]
                           [:current-node-id {:optional true} :uuid]
                           [:current-node-name {:optional true} :string]
                           [:current-node-type {:optional true} node-type]
                           [:nodes-in-progress-count :int]
                           [:sequence-progress :map]
                           [:map-each-progress :map]]]]
    [:total :int]]})

;; =============================================================================
;; Read Model Schemas
;; =============================================================================

(defschemas read-models
  {:sheet/sheets       [:map-of :uuid ::sheet]
   :sheet/nodes        [:map-of :uuid ::node]
   :sheet/blackboard   [:map-of [:tuple :uuid :keyword] ::blackboard-entry]
   :sheet/judges       [:map-of [:tuple :uuid :string] :map]
   :sheet/ticks        [:map-of :uuid :map]
   :sheet/versions     [:map-of :uuid [:map-of :int ::version-snapshot]]
   :sheet/stashes      [:map-of :uuid :map]
   :sheet/traces       [:map-of :uuid ::execution-trace]})

(def model-selection-scope
  [:tuple :uuid :uuid :uuid [:enum :node :prime :subcall] logic/workflow-values :string])

(def model-outcome
  [:and logic/durable-value
   [:map [:status [:enum :success :failure :timeout :blocked :running :partial]]
    [:usage {:optional true} [:maybe [:map-of :keyword number?]]]
    [:outputs {:optional true} [:maybe logic/workflow-values]]
    [:failure-kind {:optional true} [:maybe :keyword]]
    [:error {:optional true} [:maybe :string]]]])

(def model-request
  [:and logic/durable-value
   [:map [:phase [:enum :started :completed]] [:request-id :uuid]
    [:candidate-index [:int {:min 0}]] [:attempt-ordinal [:int {:min 0}]]
    [:provider {:optional true} [:maybe :keyword]] [:requested-model {:optional true} [:maybe :string]]
    [:outcome {:optional true} model-outcome]]])

(def model-candidate-entry
  [:and logic/durable-value
   [:map [:candidate-index [:int {:min 0}]]
    [:status [:enum :success :failure :timeout :blocked :running :partial]]
    [:advanced? :boolean]
    [:outcome {:optional true} model-outcome]]])

(defschemas model-call-commands
  {:sheet/retain-model-plan (into [:map] (conj logic/model-call-owner-fields [:plan-scope-id :string] [:plan logic/schemas-retained-plan-schema]))
   :sheet/claim-model-candidate (into [:map] (conj logic/model-call-owner-fields [:candidate-index [:int {:min 0}]]))
   :sheet/record-model-request (into [:map] (conj logic/model-call-owner-fields [:request model-request]))
   :sheet/record-model-candidate (into [:map] (conj logic/model-call-owner-fields [:entry model-candidate-entry]))
   :sheet/expose-model-candidate-output (into [:map] (conj logic/model-call-owner-fields [:candidate-index [:int {:min 0}]]))
   :sheet/select-model-candidate (into [:map] (conj logic/model-call-owner-fields [:candidate-index [:int {:min 0}]]))})

(defschemas model-call-events
  {:sheet/model-plan-retained (into [:map] (conj logic/model-call-owner-fields [:plan-scope-id :string] [:plan logic/schemas-retained-plan-schema]))
   :sheet/model-candidate-claimed (into [:map] (conj logic/model-call-owner-fields [:candidate-index [:int {:min 0}]] [:selection-scope model-selection-scope]))
   :sheet/model-request-recorded (into [:map] (conj logic/model-call-owner-fields [:request model-request] [:selection-scope model-selection-scope]))
   :sheet/model-candidate-recorded (into [:map] (conj logic/model-call-owner-fields [:entry model-candidate-entry] [:selection-scope model-selection-scope]))
   :sheet/model-candidate-output-exposed (into [:map] (conj logic/model-call-owner-fields [:candidate-index [:int {:min 0}]] [:selection-scope model-selection-scope]))
   :sheet/model-candidate-selected (into [:map] (conj logic/model-call-owner-fields [:candidate-index [:int {:min 0}]] [:selection-scope model-selection-scope]))})

(defschemas model-call-queries
  {:sheet/model-candidate-history [:map [:tick-id :uuid]]
   :sheet/model-candidate-history-result
   [:vector (into [:map] (conj logic/model-call-owner-fields
                              [:event/id :uuid]
                              [:event/type [:enum :sheet/model-plan-retained :sheet/model-candidate-claimed
                                            :sheet/model-request-recorded :sheet/model-candidate-recorded
                                            :sheet/model-candidate-output-exposed :sheet/model-candidate-selected]]
                              [:candidate-index {:optional true} [:int {:min 0}]]
                              [:selection-scope {:optional true} model-selection-scope]
                              [:plan {:optional true} logic/schemas-retained-plan-schema]
                              [:entry {:optional true} model-candidate-entry]
                              [:request {:optional true} model-request]))]})

(defschemas authority-commands {:sheet/authorize-emitted-model-work (into [:map] logic/emitted-model-owner-fields)})

(defschemas authority-events {:sheet/emitted-model-work-authorized (into [:map] logic/emitted-model-binding-fields)})

(def provider-attempt-owner-fields [[:sheet-id :uuid] [:tick-id :uuid] [:node-id :uuid] [:node-start-id :uuid]
                   [:exec-context logic/workflow-values] [:node-attempt [:int {:min 1}]]
                   [:provider-attempt [:int {:min 1}]] [:invocation-id :uuid]
                   [:candidate-index {:optional true} [:int {:min 0}]]
                   [:requested-provider {:optional true} :keyword]
                   [:requested-model {:optional true} [:maybe :string]]])

(def provider-attempt-observation
  [:map {:closed true}
   [:provider :string]
   [:response-id {:optional true} [:maybe :string]]
   [:model {:optional true} [:maybe :string]]
   [:usage {:optional true} [:maybe [:map-of [:or :keyword :string] logic/durable-value]]]
   [:choices {:optional true}
    [:vector [:map {:closed true}
              [:index {:optional true} [:maybe :int]]
              [:finish-reason {:optional true} [:maybe [:or :keyword :string]]]
              [:reasoning-content {:optional true} [:maybe :string]]
              [:reasoning-details {:optional true} [:maybe [:vector [:map-of [:or :keyword :string] logic/durable-value]]]]
              [:refusal {:optional true} [:maybe :string]]]]]])

(def provider-attempt-outcome
  [:map {:closed true}
   [:status [:enum :success :failure :timeout :cancelled]]
   [:failure-kind {:optional true} :keyword]
   [:provider-evidence {:optional true} provider-failure-evidence]
   [:provider-observations {:optional true} provider-attempt-observation]])

(defschemas provider-observation-commands
  {:sheet/start-provider-attempt (into [:map] provider-attempt-owner-fields)
   :sheet/complete-provider-attempt (into [:map] (conj provider-attempt-owner-fields [:outcome provider-attempt-outcome]))})

(defschemas provider-observation-events
  {:sheet/provider-attempt-started (into [:map] provider-attempt-owner-fields)
   :sheet/provider-attempt-completed (into [:map] (conj provider-attempt-owner-fields [:outcome provider-attempt-outcome]))})

(defschemas provider-observation-queries
  {:sheet/provider-attempts [:map [:tick-id :uuid] [:node-start-id :uuid]
                            [:invocation-id {:optional true} :uuid]]
   :sheet/provider-attempts-result
   [:vector (into [:map] (conj provider-attempt-owner-fields
                              [:status [:enum :started :completed]]
                              [:outcome {:optional true} provider-attempt-outcome]))]})

(def retained-plan-schema logic/schemas-retained-plan-schema)

(def retained-option-keys logic/schemas-retained-option-keys)

(def model-call-owner-fields logic/model-call-owner-fields)

(def emitted-model-owner-fields logic/emitted-model-owner-fields)

(def durable-value logic/durable-value)

(def workflow-values logic/workflow-values)

(def emitted-model-binding-fields logic/emitted-model-binding-fields)
