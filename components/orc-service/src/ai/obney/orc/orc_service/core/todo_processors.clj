(ns ai.obney.orc.orc-service.core.todo-processors
  "Behavior Tree Sheet todo processors (policies).

   These are event-driven side effects that respond to domain events:
   - Execute leaf nodes when tree tick starts
   - Handle sequence/fallback/parallel composite node logic
   - Handle map-each iteration
   - Update blackboard with node outputs"
  (:require [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.time.interface :as time]
            [clojure.string :as str]))

;; =============================================================================
;; Output Key Normalization
;; =============================================================================
;;
;; Code executors may return string keys ({"output" "value"}) while the
;; :sheet/complete-node-execution command schema expects keyword keys.
;; This helper ensures consistent keyword keys.

(defn- normalize-output-keys
  "Convert output map keys to keywords for command schema compatibility.
   Handles both string and keyword keys, preserving values."
  [outputs]
  (when outputs
    (reduce-kv (fn [acc k v]
                 (assoc acc (if (keyword? k) k (keyword k)) v))
               {}
               outputs)))

;; =============================================================================
;; LLM Call Budget Tracking (Opt-in Only)
;; =============================================================================
;;
;; Budget is opt-in only - if :llm-call-budget is not specified, NO limit is
;; enforced. This prevents unexpected workflow cancellations.

;; Tracks LLM call counts per tick-id for budget enforcement.
(defonce ^:private tick-llm-counts (atom {}))

(defn- increment-llm-count!
  "Increment LLM call count for a tick."
  [tick-id]
  (swap! tick-llm-counts update tick-id (fnil inc 0)))

(defn- get-llm-count
  "Get current LLM call count for a tick."
  [tick-id]
  (get @tick-llm-counts tick-id 0))

(defn clear-llm-count!
  "Clear LLM count for a tick (called on tick completion)."
  [tick-id]
  (swap! tick-llm-counts dissoc tick-id))

;; =============================================================================
;; Tick-Scoped Usage Tracking
;; =============================================================================
;; Tracks token usage per tick-id for aggregation across sub-LLM calls.

(defonce ^:private tick-usage (atom {}))

(defn- add-usage!
  "Add usage from a single LLM call to the tick's total."
  [tick-id usage]
  (when (and tick-id usage)
    (swap! tick-usage update tick-id
           (fn [u]
             (let [current (or u {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})]
               {:prompt-tokens (+ (:prompt-tokens current 0)
                                  (or (:prompt-tokens usage) (:prompt_tokens usage) 0))
                :completion-tokens (+ (:completion-tokens current 0)
                                      (or (:completion-tokens usage) (:completion_tokens usage) 0))
                :total-tokens (+ (:total-tokens current 0)
                                 (or (:total-tokens usage) (:total_tokens usage) 0))})))))

(defn get-tick-usage
  "Get aggregated usage for a tick."
  [tick-id]
  (get @tick-usage tick-id {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}))

(defn clear-tick-usage!
  "Clear usage for a tick (called on tick completion)."
  [tick-id]
  (swap! tick-usage dissoc tick-id))

(defn- check-llm-budget
  "Check if LLM budget exceeded. Returns nil if no budget set (unlimited).
   Only checks if :llm-call-budget was explicitly set in tick options."
  [context tick-id]
  (let [tick-ctx (rm/get-tick-execution-context context tick-id)
        budget (get-in tick-ctx [:options :llm-call-budget])]
    ;; CRITICAL: Only check if budget was explicitly set (not nil)
    ;; nil means unlimited - no cap enforced
    (when budget
      (let [current (get-llm-count tick-id)]
        (when (>= current budget)
          {:exceeded true :current current :budget budget})))))

;; =============================================================================
;; Tick-Scoped Resolution Helpers
;; =============================================================================
;;
(defn- resolve-nodes-by-id
  "Get nodes-by-id for a tick from tick-scoped execution context."
  [ctx _sheet-id tick-id]
  (rm/get-tick-nodes-by-id ctx tick-id))

(defn- resolve-blackboard
  "Get blackboard for a tick from tick-scoped execution context."
  [ctx _sheet-id tick-id]
  (rm/get-tick-blackboard ctx tick-id))

(defn- resolve-instruction-overrides
  "Get GEPA instruction overrides for a tick, if any."
  [ctx tick-id]
  (:instruction-overrides (rm/get-tick-execution-context ctx tick-id)))

(defn- apply-instruction-override
  "If instruction overrides exist for this node's name, apply them."
  [node overrides]
  (if-let [override (and overrides (:name node) (get overrides (:name node)))]
    (assoc node :instruction override)
    node))

(defn- apply-ontology-context
  "If node has :context parameter, inject ontology context into instruction.

   The :context parameter can include:
   - :problem-type - Problem URI for context lookup
   - :include-patterns - Include success patterns
   - :include-failures - Include failure patterns
   - :tree-id - Enable self-learning mode
   - :self-learning? - Enable self-learning context

   This requires the ontology component to be available in the context."
  [node context]
  (let [ctx-config (:context node)]
    (if (and ctx-config (:instruction node))
      ;; Try to build ontology context - gracefully handle missing ontology component
      (try
        (let [ontology-ns (requiring-resolve 'ai.obney.orc.ontology.interface/build-ontology-context)
              format-fn (requiring-resolve 'ai.obney.orc.ontology.interface/format-context-for-llm)]
          (if (and ontology-ns format-fn)
            (let [;; Resolve tree-id: explicit > sheet-id (auto for self-learning)
                  tree-id (or (:tree-id ctx-config)
                              (when (:self-learning? ctx-config) (:sheet-id context)))
                  ;; Build ontology context - pass full context (needs :event-store and :cache)
                  ontology-ctx (ontology-ns context
                                            (cond-> {:problem-type (:problem-type ctx-config)}
                                              tree-id (assoc :tree-id tree-id)
                                              (:self-learning? ctx-config) (assoc :self-learning? true)))
                  ;; Format for LLM injection
                  formatted (format-fn ontology-ctx
                                       {:include (cond-> #{}
                                                   (:include-patterns ctx-config) (conj :patterns)
                                                   (:include-failures ctx-config) (conj :failures))
                                        :max-items 5})]
              (if (and formatted (not (str/blank? formatted)))
                ;; Prepend ontology context to instruction
                (update node :instruction
                        (fn [inst]
                          (str "## Ontology Context\n\n"
                               formatted
                               "\n\n---\n\n"
                               inst)))
                node))
            node))
        (catch Exception _e
          ;; Ontology component not available or error - proceed without context
          node))
      node)))

(defn- make-bb-write-event
  "Create a tick-scoped blackboard write event (isolated per execution)."
  [_event-store sheet-id tick-id key value _blackboard]
  (->event
   {:type :sheet/execution-value-written
    :tags #{[:sheet sheet-id]
            [:tick tick-id]}
    :body {:tick-id tick-id
           :sheet-id sheet-id
           :key key
           :value value}}))

;; =============================================================================
;; Tick Execution Processor
;; =============================================================================

(defn execute-tree-tick
  "When a tree tick starts, begin executing from the root node.
   Reads from tick-scoped execution context (snapshot-based execution)."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        tick-ctx (rm/get-tick-execution-context context tick-id)
        root-id (:root-node-id tick-ctx)
        nodes-by-id (:nodes-by-id tick-ctx)
        root-node (when root-id (get nodes-by-id root-id))]
    (when root-node
      (let [blackboard (:blackboard tick-ctx)
            inputs (if (= :leaf (:type root-node))
                     (reduce (fn [acc k]
                               (if-let [entry (get blackboard k)]
                                 (assoc acc k (:value entry))
                                 acc))
                             {}
                             (:reads root-node))
                     {})]
        {:result/events
         [(->event
           {:type :sheet/node-execution-started
            :tags #{[:sheet sheet-id]
                    [:node root-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :node-id root-id
                   :inputs inputs}})]}))))

;; =============================================================================
;; Node Execution Processor
;; =============================================================================

;; Default provider - set to nil to use mock, or :openrouter etc for real execution
(def ^:dynamic *default-dscloj-provider* :openrouter)

(defn- extract-execution-context
  "Extract map-each execution context from inputs.
   Returns a map with only the context keys needed for correlation."
  [inputs]
  (select-keys inputs [::map-each-index ::map-each-parent]))

;; =============================================================================
;; Input Profile Computation (deep module — pure function, testable in isolation)
;; =============================================================================

(defn- profile-value
  "Profile a single value's shape. Returns a map of size/density indicators.
   Pure function — no side effects, deterministic."
  [v]
  (cond
    (string? v)
    {:type :string
     :length (count v)
     :word-count (count (clojure.string/split v #"\s+"))
     :line-count (count (clojure.string/split-lines v))}

    (sequential? v)
    {:type :vector
     :length (count v)}

    (map? v)
    {:type :map
     :length (count v)}

    :else
    {:type :other
     :length (count (str v))}))

(defn compute-input-profile
  "Given a node's :reads list and a blackboard, build an input-profile map
   keyed by read key. Each value is a map of size/density indicators
   (see profile-value).

   Pure function — testable in isolation."
  [reads blackboard]
  (reduce
    (fn [acc k]
      (let [v (get-in blackboard [k :value])]
        (if (nil? v)
          acc
          (assoc acc k (profile-value v)))))
    {}
    (or reads [])))

(defn execute-leaf-node
  "Execute a leaf node when node-execution-started is emitted.
   Supports multiple executor types:
   - :ai - DSCloj AI execution (default)
   - :code - Clojure function execution
   - :tool - Direct tool invocation

   Runs execution in a future to avoid blocking the pubsub thread.
   Uses cp/process-command to emit completion events."
  [{:keys [event event-store dscloj-provider] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        overrides (resolve-instruction-overrides context tick-id)
        node (-> (get nodes-by-id node-id)
                 (apply-instruction-override overrides)
                 (apply-ontology-context context))]
    (when (= :leaf (:type node))
      (let [raw-blackboard (resolve-blackboard context sheet-id tick-id)
            ;; Merge event inputs into blackboard (e.g., map-each item values)
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k) (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            ;; Use provider from context, fall back to default, or use mock if nil
            provider (or dscloj-provider *default-dscloj-provider*)
            executor-type (or (:executor node) :ai)
            ;; Extract execution context for correlation
            exec-context (extract-execution-context event-inputs)
            ;; Check LLM budget ONLY for AI executor types (not code)
            is-llm-call? (and (= :ai executor-type) provider)]
        ;; Check budget before execution (only if budget set and this is an LLM call)
        (if-let [exceeded (and is-llm-call? (check-llm-budget context tick-id))]
          ;; Budget exceeded - fail immediately
          (cp/process-command
            (assoc context :command
                   {:command/id (random-uuid)
                    :command/timestamp (time/now)
                    :command/name :sheet/fail-node-execution
                    :sheet-id sheet-id
                    :tick-id tick-id
                    :node-id node-id
                    :error (str "LLM call budget exceeded: "
                               (:current exceeded) "/" (:budget exceeded))}))
          ;; Run execution in a future to avoid blocking
          (do
            (when is-llm-call?
              (let [map-idx (::map-each-index exec-context)]
                (println (format "[EVENT RECEIVED] map-idx=%s thread=%s"
                                (or map-idx "n/a")
                                (.getName (Thread/currentThread))))))
            (future
            (try
              ;; Increment LLM count before making the call (if applicable)
              (when is-llm-call? (increment-llm-count! tick-id))
              (let [start-ms (System/currentTimeMillis)
                    _ (when is-llm-call?
                        (let [instr (or (:instruction node) "")
                              instr-preview (if (> (count instr) 80) (str (subs instr 0 80) "...") instr)
                              map-idx (::map-each-index exec-context)]
                          (println (format "[SUBCALL START] node=%s%s thread=%s instr=%s"
                                          (subs (str node-id) 0 (min 8 (count (str node-id))))
                                          (if map-idx (str " map-idx=" map-idx) "")
                                          (.getName (Thread/currentThread))
                                          instr-preview))))
                    result (cond
                             ;; Code executor doesn't need provider
                             (= :code executor-type)
                             (executor/execute-leaf node blackboard nil
                                                    :context context)
                             ;; AI executor with provider
                             provider
                             (executor/execute-leaf node blackboard provider
                                                    :context context)
                             ;; No provider - use mock
                             :else
                             (executor/execute-leaf-mock node blackboard))
                  {:keys [status outputs error duration-ms usage]} result
                  _ (when is-llm-call?
                      (println (format "[SUBCALL DONE]  node=%s status=%s duration=%dms tokens=%s"
                                      (subs (str node-id) 0 (min 8 (count (str node-id))))
                                      (name status)
                                      (or duration-ms (- (System/currentTimeMillis) start-ms))
                                      (or (:total-tokens usage) "?"))))]
              ;; Track usage for this tick (aggregates across all LLM calls)
              (when usage (add-usage! tick-id usage))
              ;; Use process-command to emit completion event
              (cp/process-command
                (assoc context :command
                       (cond-> {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/complete-node-execution
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :node-id node-id
                                :status status
                                :writes (normalize-output-keys (or outputs {}))}
                         duration-ms (assoc :duration-ms duration-ms)
                         error (assoc :error error)
                         (seq exec-context) (assoc :inputs exec-context)
                         (seq usage) (assoc :usage usage))))
              ;; ALSO emit the RLM-specific learning-signal event when an LLM
              ;; call has usage. Carries a precomputed structured node-path
              ;; and an :input-profile derived from the node's :reads so
              ;; downstream judges/aggregators don't need to recompute.
              (when (and is-llm-call? (seq usage))
                (let [node-path (cond-> [{:type :leaf :node-id node-id}]
                                  (::map-each-parent exec-context)
                                  (#(into [{:type :map-each
                                            :parent (::map-each-parent exec-context)
                                            :index (::map-each-index exec-context)}]
                                          %)))
                      input-profile (compute-input-profile (:reads node) blackboard)]
                  (cp/process-command
                    (assoc context :command
                           (cond-> {:command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :command/name :sheet/record-rlm-tree-node-completion
                                    :sheet-id sheet-id
                                    :tick-id tick-id
                                    :node-id node-id
                                    :node-path node-path
                                    :usage usage}
                             (seq input-profile)
                             (assoc :input-profile input-profile)))))))
            (catch Exception e
              ;; Use process-command to emit failure event
              (cp/process-command
                (assoc context :command
                       {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :sheet/fail-node-execution
                        :sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :error (.getMessage e)})))))))
        ;; Return nil - completion will be handled by the future via process-command
        nil))))

;; =============================================================================
;; REPL Researcher Node Execution Processor
;; =============================================================================

(defn execute-repl-researcher-node
  "Execute a repl-researcher node when node-execution-started is emitted.
   Runs in a future like leaf/llm-condition nodes to avoid blocking pubsub."
  [{:keys [event event-store dscloj-provider] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        overrides (resolve-instruction-overrides context tick-id)
        node (-> (get nodes-by-id node-id)
                 (apply-instruction-override overrides)
                 (apply-ontology-context context))]
    (when (= :repl-researcher (:type node))
      (let [raw-blackboard (resolve-blackboard context sheet-id tick-id)
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k) (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            provider (or dscloj-provider *default-dscloj-provider*)
            exec-context (extract-execution-context event-inputs)]
        (future
          (try
            (let [result (if provider
                           (executor/execute-repl-researcher node blackboard provider context)
                           {:status :failure :error "No DSCloj provider configured"})
                  {:keys [status outputs error duration-ms generated-tree-raw usage]} result
                  ;; Track usage for this tick (RLM mode aggregates all LLM calls)
                  _ (when usage (add-usage! tick-id usage))
                  ;; Handle :tree-generated status - only propagate raw tree (canonical contains fns)
                  ;; The raw S-expr DSL is pure data and can be serialized to event store
                  effective-status (if (= :tree-generated status) :tree-generated status)
                  ;; U8: Sanitize the raw tree before putting it on the blackboard.
                  ;; Inline (fn ...) values on :code nodes are SCI fn objects that
                  ;; Fressian cannot serialize. Without sanitization, the read-model
                  ;; can't project the resulting events and the tick stays pending
                  ;; forever. The actual function continues to live in the
                  ;; ephemeral-fn-registry for Phase-2 execution; only the event
                  ;; representation needs sanitization.
                  sanitized-tree-raw (when generated-tree-raw
                                       (tree-executor/sanitize-tree-for-events generated-tree-raw))
                  ;; Include sanitized generated-tree-raw in outputs when present
                  ;; (for Phase 2 auto-execution observability)
                  effective-outputs (cond-> (or outputs {})
                                      sanitized-tree-raw (assoc :generated-tree-raw sanitized-tree-raw))]
              ;; Emit :rlm/tree-generated event when tree is generated
              ;; Check for generated-tree-raw presence (Phase 2 auto-execution returns :success with this field)
              (when (some? generated-tree-raw)
                (es/append event-store
                           {:tenant-id (:tenant-id context)
                            :events [(->event
                                      {:type :rlm/tree-generated
                                       :tags #{[:sheet sheet-id]
                                               [:tick tick-id]}
                                       :body {:tree-id (random-uuid)
                                              :execution-id tick-id
                                              :raw-dsl sanitized-tree-raw
                                              :generated-at (str (java.time.Instant/now))}})]}))
              (cp/process-command
                (assoc context :command
                       (cond-> {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/complete-node-execution
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :node-id node-id
                                :status effective-status
                                :writes (normalize-output-keys (or effective-outputs {}))}
                         duration-ms (assoc :duration-ms duration-ms)
                         error (assoc :error error)
                         (seq exec-context) (assoc :inputs exec-context)
                         ;; Propagate :usage (including :by-node from Phase 2)
                         ;; so per-node detail bubbles up to the parent tick.
                         (seq usage) (assoc :usage usage)))))
            (catch Exception e
              (cp/process-command
                (assoc context :command
                       {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :sheet/fail-node-execution
                        :sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :error (.getMessage e)})))))
        nil))))

;; =============================================================================
;; Delegate Node Execution Processor
;; =============================================================================

(defn execute-delegate-node
  "Execute delegate node by dispatching to target sheet.
   Maps parent blackboard inputs to target, executes, maps outputs back.

   Delegate nodes execute another sheet (workflow) with isolated blackboard:
   - :reads keys are passed from parent blackboard to target inputs
   - :writes keys are received from target outputs back to parent blackboard
   - Execution is async with timeout enforcement

   Follows ORC async pattern: future + cp/process-command for completion."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (= :delegate (:type node))
      (let [raw-blackboard (resolve-blackboard context sheet-id tick-id)
            ;; Merge event inputs into blackboard (ORC pattern)
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k)
                                          (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            exec-context (extract-execution-context event-inputs)

            target-sheet-id (:target-sheet-id node)
            read-keys (:reads node)
            write-keys (:writes node)
            timeout-ms (or (:delegate-timeout-ms node) 300000)

            ;; Map parent blackboard to target inputs (string keys for execute)
            target-inputs (reduce
                           (fn [acc k]
                             (if-let [entry (get blackboard k)]
                               (assoc acc (name k) (:value entry))
                               acc))
                           {}
                           read-keys)]

        ;; Async execution pattern (ORC standard)
        (future
          (try
            (let [start-time (System/currentTimeMillis)
                  result (runtime/execute context target-sheet-id
                                          target-inputs
                                          :timeout-ms timeout-ms)
                  duration-ms (- (System/currentTimeMillis) start-time)
                  status (:status result)

                  ;; Map target outputs to parent blackboard
                  ;; Note: target outputs may have keyword or string keys depending on execution path
                  outputs (reduce
                           (fn [acc k]
                             (let [kw-key (if (keyword? k) k (keyword k))
                                   str-key (name k)
                                   ;; Try keyword key first, then string key
                                   v (or (get (:outputs result) kw-key)
                                         (get (:outputs result) str-key))]
                               (if v
                                 (assoc acc k v)
                                 acc)))
                           {}
                           write-keys)]

              ;; Complete node execution (ORC pattern)
              (cp/process-command
                (assoc context :command
                       (cond-> {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/complete-node-execution
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :node-id node-id
                                :status status
                                :writes (normalize-output-keys outputs)
                                :duration-ms duration-ms}
                         (seq exec-context) (assoc :inputs exec-context)
                         (:error result) (assoc :error (:error result))))))

            (catch Exception e
              ;; Fail node execution (ORC pattern)
              (cp/process-command
                (assoc context :command
                       {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :sheet/fail-node-execution
                        :sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :error (.getMessage e)})))))
        ;; Return nil - completion handled async via process-command
        nil))))

;; =============================================================================
;; Condition Node Execution Processor
;; =============================================================================

(defn- normalize-for-comparison
  "Normalize values for comparison, handling yesno/boolean cases.
   Converts 'yes'/'true'/true to true, 'no'/'false'/false to false."
  [v]
  (cond
    (boolean? v) v
    (string? v) (let [lower (clojure.string/lower-case v)]
                  (cond
                    (#{"yes" "true" "1"} lower) true
                    (#{"no" "false" "0"} lower) false
                    ;; Try parsing as number
                    :else (try (Double/parseDouble v) (catch Exception _ v))))
    :else v))

(defn evaluate-condition-check
  "Evaluate a condition check against a blackboard value.
   Returns true if check passes, false otherwise.
   Handles yesno fields by normalizing 'yes'/'no' strings to booleans."
  [check blackboard]
  (let [{:keys [key op value]} check
        entry (get blackboard key)
        bb-value (:value entry)
        ;; Normalize both values for comparison
        norm-bb (normalize-for-comparison bb-value)
        norm-val (normalize-for-comparison value)]
    (case op
      :equals (= norm-bb norm-val)
      :not-equals (not= norm-bb norm-val)
      :gt (and (number? norm-bb) (number? norm-val) (> norm-bb norm-val))
      :lt (and (number? norm-bb) (number? norm-val) (< norm-bb norm-val))
      :gte (and (number? norm-bb) (number? norm-val) (>= norm-bb norm-val))
      :lte (and (number? norm-bb) (number? norm-val) (<= norm-bb norm-val))
      :contains (and (string? bb-value) (string? value) (.contains bb-value value))
      :exists (some? bb-value)
      :truthy (boolean norm-bb)  ;; Use normalized value so "no"/"false" → false
      ;; Default to false for unknown ops
      false)))

(defn execute-condition-node
  "Execute a condition node when node-execution-started is emitted.
   Evaluates the check against blackboard and returns success/failure/running.
   Also handles llm-condition nodes via async LLM execution."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        exec-context (extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)
        node-type (:type node)]
    (cond
      ;; Static condition - immediate evaluation
      (= :condition node-type)
      (let [blackboard (resolve-blackboard context sheet-id tick-id)
            check (:check node)
            on-fail (get check :on-fail :failure)
            passed? (if check
                      (evaluate-condition-check check blackboard)
                      false)
            status (if passed?
                     :success
                     on-fail)]
        {:result/events
         [(->event
           {:type :sheet/node-execution-completed
            :tags #{[:sheet sheet-id]
                    [:node node-id]
                    [:tick tick-id]}
            :body (cond-> {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id node-id
                           :status status}
                    (seq exec-context) (assoc :inputs exec-context))})]})

      ;; LLM condition - async execution via future
      (= :llm-condition node-type)
      (let [blackboard (resolve-blackboard context sheet-id tick-id)
            provider (:dscloj-provider context)]
        (future
          (try
            (let [result (if provider
                           (executor/execute-llm-condition node blackboard provider
                                                          :context {:event-store event-store})
                           ;; No provider - fail with error
                           {:status :failure
                            :error "No dscloj-provider configured for LLM condition"})
                  {:keys [status result error duration-ms]} result
                  ;; LLM condition: true = success, false = failure
                  final-status (if (= :success status)
                                 (if result :success :failure)
                                 :failure)]
              (cp/process-command
               (assoc context :command
                      (cond-> {:command/id (random-uuid)
                               :command/timestamp (time/now)
                               :command/name :sheet/complete-node-execution
                               :sheet-id sheet-id
                               :tick-id tick-id
                               :node-id node-id
                               :status final-status
                               :writes {}}
                        duration-ms (assoc :duration-ms duration-ms)
                        error (assoc :error error)
                        (seq exec-context) (assoc :inputs exec-context)))))
            (catch Exception e
              (cp/process-command
               (assoc context :command
                      {:command/id (random-uuid)
                       :command/timestamp (time/now)
                       :command/name :sheet/fail-node-execution
                       :sheet-id sheet-id
                       :tick-id tick-id
                       :node-id node-id
                       :error (.getMessage e)})))))
        ;; Return nil - completion handled by future
        nil)

      ;; Not a condition node
      :else nil)))

;; =============================================================================
;; Composite Node Execution Processor
;; =============================================================================

(defn execute-composite-node
  "Handle execution of sequence/fallback nodes.
   When started, begin executing the first child.
   When a child completes, decide whether to continue or finish."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        exec-context (extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (#{:sequence :fallback} (:type node))
      (let [children-ids (:children-ids node)]
        (if (empty? children-ids)
          ;; No children - sequence succeeds, fallback fails
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body (cond-> {:sheet-id sheet-id
                             :tick-id tick-id
                             :node-id node-id
                             :status (if (= :sequence (:type node)) :success :failure)}
                      (seq exec-context) (assoc :inputs exec-context))})]}
          ;; Start first child
          (let [first-child-id (first children-ids)
                first-child (get nodes-by-id first-child-id)
                raw-blackboard (resolve-blackboard context sheet-id tick-id)
                ;; Merge event inputs into blackboard (e.g., map-each item values)
                ;; This ensures items passed from map-each are available to children
                blackboard (reduce (fn [bb [k v]]
                                     (if (and (keyword? k)
                                              (not (= (namespace k) (namespace ::_))))
                                       (assoc-in bb [k :value] v)
                                       bb))
                                   raw-blackboard
                                   event-inputs)
                bb-inputs (if (= :leaf (:type first-child))
                            (reduce (fn [acc k]
                                      (if-let [entry (get blackboard k)]
                                        (assoc acc k (:value entry))
                                        acc))
                                    {}
                                    (:reads first-child))
                            {})
                ;; Merge execution context with blackboard inputs AND event-inputs
                ;; Pass event-inputs to children so they can also access map-each items
                inputs (merge exec-context event-inputs bb-inputs)]
            {:result/events
             [(->event
               {:type :sheet/node-execution-started
                :tags #{[:sheet sheet-id]
                        [:node first-child-id]
                        [:tick tick-id]}
                :body {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id first-child-id
                       :inputs inputs}})]}))))))

;; =============================================================================
;; Parallel Node Execution Processor
;; =============================================================================

(defn execute-parallel-node
  "Handle execution of parallel nodes.
   When started, execute ALL children concurrently.
   Completion is handled by handle-parallel-child-completion."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        exec-context (extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (= :parallel (:type node))
      (let [children-ids (:children-ids node)]
        (if (empty? children-ids)
          ;; No children - parallel succeeds
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body (cond-> {:sheet-id sheet-id
                             :tick-id tick-id
                             :node-id node-id
                             :status :success}
                      (seq exec-context) (assoc :inputs exec-context))})]}
          ;; Start ALL children concurrently
          (let [blackboard (resolve-blackboard context sheet-id tick-id)]
            {:result/events
             (vec
              (for [child-id children-ids]
                (let [child (get nodes-by-id child-id)
                      bb-inputs (if (= :leaf (:type child))
                                  (reduce (fn [acc k]
                                            (if-let [entry (get blackboard k)]
                                              (assoc acc k (:value entry))
                                              acc))
                                          {}
                                          (:reads child))
                                  {})
                      ;; Merge execution context with blackboard inputs
                      inputs (merge exec-context bb-inputs)]
                  (->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id]
                            [:node child-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id child-id
                           :inputs inputs}}))))}))))))

;; =============================================================================
;; Child Completion Handler
;; =============================================================================

(defn- matches-execution-context?
  "Check if an event's inputs match the given execution context.
   Empty context matches everything (for non-map-each executions)."
  [event exec-context]
  (if (empty? exec-context)
    true  ;; No context = match all (normal execution, not inside map-each)
    (let [event-inputs (or (:inputs event) {})]
      (every? (fn [[k v]]
                (= (get event-inputs k) v))
              exec-context))))

(defn- count-child-statuses
  "Count how many children of a node have completed with each status.
   Filters by execution context to distinguish between map-each iterations.
   Returns {:success n :failure n :total-children n :completed n}"
  [{:keys [event-store tenant-id] :as ctx} sheet-id tick-id parent-node exec-context]
  (let [children-ids (:children-ids parent-node)
        total (count children-ids)
        ;; Read all execution completed events for this tick and these children
        events (vec (es/read event-store
                                      {:types #{:sheet/node-execution-completed}
                                       :tags #{[:tick tick-id]}
                                       :tenant-id tenant-id}))
        ;; Filter to only children of this parent AND matching execution context
        child-set (set children-ids)
        child-completions (filter (fn [e]
                                    (and (child-set (:node-id e))
                                         (matches-execution-context? e exec-context)))
                                  events)
        ;; Count by status
        success-count (count (filter #(= :success (:status %)) child-completions))
        failure-count (count (filter #(= :failure (:status %)) child-completions))]
    {:success success-count
     :failure failure-count
     :total-children total
     :completed (count child-completions)}))

(defn- evaluate-parallel-completion
  "Evaluate if a parallel node should complete based on its policies.
   Returns nil if not ready, or {:status :success/:failure} if ready."
  [child-counts success-policy failure-policy]
  (let [{:keys [success failure total-children completed]} child-counts
        ;; Default policies
        success-policy (or success-policy :all)
        failure-policy (or failure-policy :any)]
    (cond
      ;; Check failure policy first
      (and (= failure-policy :any) (> failure 0))
      {:status :failure}

      (and (= failure-policy :all) (= failure total-children))
      {:status :failure}

      ;; Check success policy
      (and (= success-policy :all) (= success total-children))
      {:status :success}

      (and (= success-policy :any) (> success 0))
      {:status :success}

      (and (= success-policy :majority) (> success (/ total-children 2)))
      {:status :success}

      ;; All children completed but didn't meet success criteria
      (= completed total-children)
      {:status :failure}

      ;; Not all children completed yet
      :else nil)))

(defn handle-child-completion
  "When a child node completes, handle the parent's logic.
   For sequences: continue on success, fail on failure.
   For fallbacks: succeed on success, continue on failure.
   For parallel: check policies to determine completion.
   Propagates execution context from child to parent/siblings."
  [{:keys [event event-store tenant-id] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        child-id (:node-id event)
        child-status (:status event)
        child-writes (:writes event)  ;; Capture writes from completed child
        event-inputs (:inputs event)
        exec-context (extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        child (get nodes-by-id child-id)
        parent-id (:parent-id child)]
    (when parent-id
      (let [parent (get nodes-by-id parent-id)
            siblings (:children-ids parent)
            child-index (.indexOf (vec siblings) child-id)
            next-child-id (get (vec siblings) (inc child-index))
            blackboard (resolve-blackboard context sheet-id tick-id)
            ;; Merge child's writes into blackboard - handles race condition where
            ;; read model hasn't yet processed the execution-value-written events
            blackboard-with-writes (reduce (fn [bb [k v]]
                                             (assoc-in bb [k :value] v))
                                           blackboard
                                           child-writes)]
        (case (:type parent)
          :sequence
          (cond
            ;; :success and :tree-generated both mean the child completed successfully.
            ;; :tree-generated is from RLM two-phase execution (tree was generated).
            ;; D-008: :partial from map-each is treated as continuation — downstream
            ;; synthesis nodes still run against the successes-only output.
            (#{:success :tree-generated :partial} child-status)
            (if next-child-id
              ;; Continue to next child
              (let [next-child (get nodes-by-id next-child-id)
                    next-index (inc child-index)
                    total-children (count siblings)
                    bb-inputs (if (= :leaf (:type next-child))
                                (reduce (fn [acc k]
                                          (if-let [entry (get blackboard-with-writes k)]
                                            (assoc acc k (:value entry))
                                            acc))
                                        {}
                                        (:reads next-child))
                                {})
                    ;; Merge execution context with blackboard inputs AND child writes
                    ;; Child writes are needed for non-leaf nodes (map-each, etc.) that
                    ;; read from the blackboard - ensures they see the previous child's outputs
                    inputs (merge exec-context bb-inputs child-writes)]
                {:result/events
                 [;; Emit sequence progress event
                  (->event
                   {:type :sheet/sequence-progress-updated
                    :tags #{[:sheet sheet-id]
                            [:node parent-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id parent-id
                           :child-index next-index
                           :total-children total-children}})
                  (->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id]
                            [:node next-child-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id next-child-id
                           :inputs inputs}})]})
              ;; All children succeeded — sequence completes.
              ;; D-008 sticky :partial: if ANY prior sibling completed with :partial,
              ;; propagate :partial up so the at-a-glance status truthfully reflects
              ;; that partial behavior occurred somewhere in the tree. Otherwise use
              ;; the last child's status (preserves :tree-generated for RLM Phase 1).
              (let [sibling-set (set siblings)
                    child-completions (->> (es/read event-store
                                             {:types #{:sheet/node-execution-completed}
                                              :tags #{[:tick tick-id]}
                                              :tenant-id tenant-id})
                                           (into [])
                                           (filter (fn [e]
                                                     (and (sibling-set (:node-id e))
                                                          (matches-execution-context? e exec-context)))))
                    any-partial? (some #(= :partial (:status %)) child-completions)
                    final-status (if any-partial? :partial child-status)]
                (cp/process-command
                  (assoc context :command
                         (cond-> {:command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :command/name :sheet/complete-node-execution
                                  :sheet-id sheet-id
                                  :tick-id tick-id
                                  :node-id parent-id
                                  :status final-status
                                  :writes (or (:writes event) {})}  ;; Propagate writes, default to empty map
                           (seq exec-context) (assoc :inputs exec-context))))
                nil))

            (= child-status :failure)
            ;; Child failed - sequence fails, propagate error
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :failure}
                        (:error event) (assoc :error (:error event))
                        (seq exec-context) (assoc :inputs exec-context))})]}

            (= child-status :timeout)
            ;; D-003: Child timed out (e.g. RLM Phase 2 budget exceeded) -
            ;; propagate :timeout up so callers can distinguish budget exhaustion
            ;; from a generic failure. Like :failure, sequence does not continue.
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :timeout}
                        (:error event) (assoc :error (:error event))
                        (seq exec-context) (assoc :inputs exec-context))})]}

            (= child-status :running)
            ;; Child returned running - propagate up
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :running}
                        (seq exec-context) (assoc :inputs exec-context))})]}

            ;; Unknown status - do nothing
            :else nil)

          :fallback
          (case child-status
            (:success :partial)
            ;; Child succeeded (or partially succeeded — D-008) - fallback succeeds.
            ;; :partial means "we got something usable" so fallback stops here.
            ;; The :status surfaced matches the child's so downstream sees the truth.
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status child-status}
                        (seq exec-context) (assoc :inputs exec-context))})]}
            ;; D-003: :timeout from a child is like :failure for fallback purposes —
            ;; we didn't get useful output, try the next sibling. If no next sibling,
            ;; fallback completes with :timeout (truthful propagation).
            (:failure :timeout)
            (if next-child-id
              ;; Continue to next child
              (let [next-child (get nodes-by-id next-child-id)
                    bb-inputs (if (= :leaf (:type next-child))
                                (reduce (fn [acc k]
                                          (if-let [entry (get blackboard k)]
                                            (assoc acc k (:value entry))
                                            acc))
                                        {}
                                        (:reads next-child))
                                {})
                    ;; Merge execution context with blackboard inputs
                    inputs (merge exec-context bb-inputs)]
                {:result/events
                 [(->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id]
                            [:node next-child-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id next-child-id
                           :inputs inputs}})]})
              ;; All children failed - fallback fails
              {:result/events
               [(->event
                 {:type :sheet/node-execution-completed
                  :tags #{[:sheet sheet-id]
                          [:node parent-id]
                          [:tick tick-id]}
                  :body (cond-> {:sheet-id sheet-id
                                 :tick-id tick-id
                                 :node-id parent-id
                                 :status :failure}
                          (seq exec-context) (assoc :inputs exec-context))})]})
            :running
            ;; Child returned running - propagate up
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :running}
                        (seq exec-context) (assoc :inputs exec-context))})]}
            ;; Unknown status - do nothing
            nil)

          :parallel
          ;; For parallel nodes, check if all children completed based on policies.
          ;; Uses event-store CAS to prevent duplicate completions when
          ;; multiple children complete rapidly and all see the same state.
          (let [child-counts (count-child-statuses context sheet-id tick-id parent exec-context)
                completion (evaluate-parallel-completion
                            child-counts
                            (:success-policy parent)
                            (:failure-policy parent))]
            (when completion
              (let [event (->event
                            {:type :sheet/node-execution-completed
                             :tags #{[:sheet sheet-id]
                                     [:node parent-id]
                                     [:tick tick-id]}
                             :body (cond-> {:sheet-id sheet-id
                                            :tick-id tick-id
                                            :node-id parent-id
                                            :status (:status completion)}
                                     (seq exec-context) (assoc :inputs exec-context))})]
                ;; CAS: only append if no completion for this node+tick exists yet.
                ;; Event-store handles publishing, so no need to return events.
                (es/append event-store
                  {:events [event]
                   :tenant-id tenant-id
                   :cas {:types #{:sheet/node-execution-completed}
                         :tags #{[:tick tick-id] [:node parent-id]}
                         :predicate-fn (fn [existing]
                                         (empty? (into [] existing)))}})
                {})))

          ;; Map-each has special handling via execute-map-each-node
          :map-each
          nil

          ;; Unknown parent type
          nil)))))

;; =============================================================================
;; Map-Each Node Execution Processor
;; =============================================================================

;; Process Manager State for Map-Each Iterations
;;
;; This atom acts as a process manager (saga coordinator) for map-each node
;; execution. It tracks in-flight iteration state across multiple async event
;; cycles. This is intentionally NOT event-sourced because:
;; - State is transient (only exists during active map-each execution)
;; - Concurrent child completions require atomic updates (race condition risk with event sourcing)
;; - Loss on restart is acceptable (the parent tick will timeout and can be restarted)
(defonce ^:private map-each-state (atom {}))

(defn- map-each-key [tick-id node-id]
  (str tick-id "-" node-id))

;; =============================================================================
;; D-008: classify-map-each-outcome — pure deep module
;; =============================================================================

(defn classify-map-each-outcome
  "Classify the outcome of a map-each based on its results vector.

   Input:
     {:results    [...]   ;; raw value for success, {:__status :failure ...} for failure
      :item-count N}      ;; total items started

   Returns a 3-tuple: [status partial-summary-or-nil output-vector]
     status  = :success | :partial | :failure
     summary = nil OR {:total :succeeded :failed :failure-indices :failure-reasons}
     output  = successes-only vector

   Rule table (Q6 from D-008 PRD):
     - Empty input          -> [:success nil []]
     - All items succeeded  -> [:success nil <full vector>]
     - 0 < failed < N       -> [:partial <summary> <successes-only>]
     - All items failed     -> [:failure <summary> []]"
  [{:keys [results item-count]}]
  (if (zero? item-count)
    [:success nil []]
    (let [failure? (fn [r] (and (map? r) (= :failure (:__status r))))
          indexed (map-indexed vector results)
          failed-pairs (filter (fn [[_ r]] (failure? r)) indexed)
          successful-pairs (remove (fn [[_ r]] (failure? r)) indexed)
          failed-count (count failed-pairs)]
      (cond
        (zero? failed-count)
        [:success nil (vec results)]

        :else
        (let [summary {:total item-count
                       :succeeded (- item-count failed-count)
                       :failed failed-count
                       :failure-indices (mapv first failed-pairs)
                       :failure-reasons (into {} (map (fn [[i r]] [i (:__error r)]) failed-pairs))}
              status (if (= failed-count item-count) :failure :partial)
              output (mapv second successful-pairs)]
          [status summary output])))))

(defn execute-map-each-node
  "Handle execution of map-each nodes.
   Iterates over a list in the blackboard, executing the child subtree for each item.
   Supports optional concurrency via :max-concurrency."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)  ;; May contain writes from previous sequence child
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (= :map-each (:type node))
      (let [source-key (:source-key node)
            item-key (:item-key node)
            output-key (:output-key node)
            max-concurrency (or (:max-concurrency node) 1)
            children-ids (:children-ids node)
            child-id (first children-ids) ;; map-each has exactly one child subtree
            blackboard (resolve-blackboard context sheet-id tick-id)
            ;; Check event inputs first (may contain writes from previous sequence child),
            ;; then fall back to blackboard. This handles race condition where read model
            ;; hasn't yet processed the execution-value-written events.
            source-list (or (get event-inputs source-key)
                            (get-in blackboard [source-key :value]))]
        (cond
          (not (sequential? source-list))
          ;; Source is not a list - fail
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status :failure
                     :error (str "Source key '" source-key "' is not a list")}})]}

          (empty? source-list)
          ;; Empty list - succeed with empty results
          {:result/events
           [(make-bb-write-event event-store sheet-id tick-id output-key [] blackboard)
            (->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status :success}})]}

          (not child-id)
          ;; No child subtree - succeed with original list
          {:result/events
           [(make-bb-write-event event-store sheet-id tick-id output-key (vec source-list) blackboard)
            (->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status :success}})]}

          :else
          ;; Initialize iteration state and start first batch
          (let [state-key (map-each-key tick-id node-id)
                items (vec source-list)
                total-items (count items)
                ;; Pre-allocate results with nils to handle out-of-order completions
                ;; Determine how many to start
                batch-size (min max-concurrency total-items)
                batch-indices (vec (range batch-size))
                initial-state {:items items
                               :current-index 0
                               :results (vec (repeat total-items nil))
                               ;; Track items that have been started but not yet completed
                               :in-flight (set batch-indices)
                               :max-concurrency max-concurrency
                               :child-id child-id
                               :item-key item-key
                               :output-key output-key
                               :source-key source-key}
                ]
            ;; Store state
            (swap! map-each-state assoc state-key initial-state)
            ;; Start first batch - set item value and start child for each
            ;; We emit blackboard writes + start events so children can read item from blackboard
            {:result/events
             (into
              ;; Emit initial progress event
              [(->event
                {:type :sheet/map-each-progress-updated
                 :tags #{[:sheet sheet-id]
                         [:node node-id]
                         [:tick tick-id]}
                 :body {:sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :item-index 0
                        :total-items total-items}})]
              (mapcat
               (fn [idx]
                 (let [item (nth items idx)
                       child (get nodes-by-id child-id)]
                   ;; Emit blackboard write for item BEFORE starting child
                   ;; This ensures all children in a sequence can read the item
                   [(make-bb-write-event event-store sheet-id tick-id item-key item blackboard)
                    (->event
                     {:type :sheet/node-execution-started
                      :tags #{[:sheet sheet-id]
                              [:node child-id]
                              [:tick tick-id]}
                      :body {:sheet-id sheet-id
                             :tick-id tick-id
                             :node-id child-id
                             ;; Pass item as an input override
                             :inputs {item-key item
                                      ::map-each-index idx
                                      ::map-each-parent node-id}}})]))
               batch-indices))}))))))

(defn handle-map-each-child-completion
  "Handle completion of a map-each child iteration.
   Collects results and starts next items or completes the map-each node.
   Only processes completions from the DIRECT child of the map-each node."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        completing-node-id (:node-id event)
        child-status (:status event)
        inputs (:inputs event)
        writes (:writes event)
        ;; Check if this is a map-each child
        map-each-parent-id (get inputs ::map-each-parent)
        item-index (get inputs ::map-each-index)]
    (when (and map-each-parent-id item-index)
      (let [state-key (map-each-key tick-id map-each-parent-id)
            state (get @map-each-state state-key)]
        ;; Only process if:
        ;; 1. State exists for this map-each
        ;; 2. The completing node is the DIRECT child of the map-each (not a descendant)
        (when (and state (= completing-node-id (:child-id state)))
          (let [{:keys [items child-id item-key output-key]} state
                ;; Get the original item
                item (nth items item-index)
                ;; When child is a composite (sequence/fallback), writes may be empty.
                ;; In that case, read all non-special keys from the blackboard.
                effective-writes (if (seq writes)
                                   writes
                                   ;; Composite child - read from blackboard
                                   (let [blackboard (resolve-blackboard context sheet-id tick-id)
                                         source-key (:source-key state)]
                                     (reduce-kv
                                      (fn [acc k v]
                                        (if (and (keyword? k)
                                                 (not (#{item-key source-key output-key} k))
                                                 (not (= (namespace k) (namespace ::_))))
                                          (assoc acc k (:value v))
                                          acc))
                                      {}
                                      blackboard)))
                ;; Create result from writes
                computed-result (if (= :success child-status)
                                  (let [updated-item (get effective-writes item-key item)
                                        source-key (:source-key state)
                                        other-writes (reduce-kv
                                                       (fn [acc k v]
                                                         (if (and (not= k item-key)
                                                                  (not= k source-key)
                                                                  (not= k output-key))
                                                           (assoc acc (keyword k) v)
                                                           acc))
                                                       {}
                                                       effective-writes)]
                                    (if (map? updated-item)
                                      (merge updated-item other-writes)
                                      (if (seq other-writes)
                                        other-writes
                                        updated-item)))
                                  (assoc (if (map? item) item {:__original item})
                                         :__status :failure
                                         :__error (:error event)))
                ;; Atomically update state and determine action.
                ;; This prevents race conditions when multiple children complete concurrently.
                ;; Track :in-flight so concurrent completions don't all pick the same next-to-start.
                action (atom nil)
                _ (swap! map-each-state
                         (fn [all-state]
                           (let [s (get all-state state-key)]
                             (if-not s
                               ;; State already cleaned up (shouldn't happen)
                               (do (reset! action :noop) all-state)
                               (let [new-results (assoc (:results s) item-index computed-result)
                                     completed-count (count (filter some? new-results))
                                     total (count (:items s))
                                     ;; Remove the just-completed index from in-flight
                                     in-flight-after-removal (disj (:in-flight s) item-index)]
                                 (if (= completed-count total)
                                   ;; All done — remove state, action = :complete
                                   (do (reset! action {:type :complete
                                                       :results new-results
                                                       :completed-count completed-count})
                                       (dissoc all-state state-key))
                                   ;; Find next item to start: must be nil in results AND not in flight
                                   (let [next-to-start (first
                                                        (filter #(and (nil? (get new-results %))
                                                                      (not (contains? in-flight-after-removal %)))
                                                                (range total)))
                                         in-flight-after-start (if next-to-start
                                                                 (conj in-flight-after-removal next-to-start)
                                                                 in-flight-after-removal)]
                                     (reset! action (if (and next-to-start (< next-to-start total))
                                                      {:type :start-next
                                                       :next-index next-to-start
                                                       :completed-count completed-count}
                                                      {:type :wait
                                                       :completed-count completed-count}))
                                     (assoc all-state state-key
                                            (assoc s
                                                   :results new-results
                                                   :in-flight in-flight-after-start)))))))))
                act @action
                total-items (count items)
                nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
                blackboard (resolve-blackboard context sheet-id tick-id)]
            (case (:type act)
              :complete
              ;; D-008: classify the map-each outcome using the pure deep module.
              ;; Returns [status partial-summary-or-nil output-vector] where output
              ;; is the successes-only vector (failure markers stripped).
              (let [[status summary output]
                    (classify-map-each-outcome
                      {:results (:results act) :item-count total-items})
                    completion-body (cond-> {:sheet-id sheet-id
                                             :tick-id tick-id
                                             :node-id map-each-parent-id
                                             :status status}
                                      summary (assoc :partial-summary summary))]
                {:result/events
                 [(->event
                   {:type :sheet/map-each-progress-updated
                    :tags #{[:sheet sheet-id] [:node map-each-parent-id] [:tick tick-id]}
                    :body {:sheet-id sheet-id :tick-id tick-id :node-id map-each-parent-id
                           :item-index (:completed-count act) :total-items total-items}})
                  (make-bb-write-event event-store sheet-id tick-id output-key output blackboard)
                  (->event
                   {:type :sheet/node-execution-completed
                    :tags #{[:sheet sheet-id] [:node map-each-parent-id] [:tick tick-id]}
                    :body completion-body})]})

              :start-next
              (let [next-item (nth items (:next-index act))
                    child (get nodes-by-id child-id)]
                {:result/events
                 [(->event
                   {:type :sheet/map-each-progress-updated
                    :tags #{[:sheet sheet-id] [:node map-each-parent-id] [:tick tick-id]}
                    :body {:sheet-id sheet-id :tick-id tick-id :node-id map-each-parent-id
                           :item-index (:completed-count act) :total-items total-items}})
                  ;; Write item to blackboard so children in sequence can read it
                  (make-bb-write-event event-store sheet-id tick-id item-key next-item blackboard)
                  (->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id] [:node child-id] [:tick tick-id]}
                    :body {:sheet-id sheet-id :tick-id tick-id :node-id child-id
                           :inputs {item-key next-item
                                    ::map-each-index (:next-index act)
                                    ::map-each-parent map-each-parent-id}}})]})

              ;; :wait or :noop — just emit progress
              {:result/events
               [(->event
                 {:type :sheet/map-each-progress-updated
                  :tags #{[:sheet sheet-id] [:node map-each-parent-id] [:tick tick-id]}
                  :body {:sheet-id sheet-id :tick-id tick-id :node-id map-each-parent-id
                         :item-index (or (:completed-count act) 0) :total-items total-items}})]})))))))

;; =============================================================================
;; Blackboard Update Processor
;; =============================================================================

(defn update-blackboard-on-completion
  "No-op — blackboard writes are handled atomically by complete-node-execution.
   Kept as a registered processor for event flow compatibility."
  [_context]
  nil)


;; =============================================================================
;; Tree Tick Completion
;; =============================================================================

;; Maximum iterations to prevent infinite loops
(def ^:dynamic *max-tick-iterations* 10)

(defn complete-tree-tick
  "When the root node completes, complete the tree tick.
   If status is :running, automatically re-tick (up to max iterations).
   If tick has been cancelled, stop immediately.
   For tick-scoped executions, includes outputs in the completion event."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        status (:status event)
        error (:error event)  ;; Extract error from node completion
        tick-ctx (rm/get-tick-execution-context context tick-id)
        root-node-id (:root-node-id tick-ctx)
        ;; Per-execution override from options, else global dynamic default
        max-ticks (or (get-in tick-ctx [:options :max-ticks]) *max-tick-iterations*)
        ;; Get current tick to check iteration count and cancellation
        tick (rm/get-tick context tick-id)
        current-iteration (or (:iteration tick) 1)
        cancelled? (= :cancelled (:status tick))]
    (when (= node-id root-node-id)
      (cond
        ;; Tick was cancelled - don't re-tick
        cancelled?
        {:result/events
         [(->event
           {:type :sheet/tree-tick-completed
            :tags #{[:sheet sheet-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :iteration current-iteration
                   :root-status :failure}})]}

        ;; Status is running and we haven't hit max iterations - re-tick
        (and (= status :running)
             (< current-iteration max-ticks))
        {:result/events
         [(->event
           {:type :sheet/tree-tick-completed
            :tags #{[:sheet sheet-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :iteration current-iteration
                   :root-status :running}})
          (->event
           {:type :sheet/tree-tick-started
            :tags #{[:sheet sheet-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :iteration (inc current-iteration)}})]}

        ;; Either success/failure, or hit max iterations
        :else
        (let [final-status (if (and (= status :running)
                                     (>= current-iteration max-ticks))
                             :failure
                             status)
              ;; For tick-scoped executions, gather outputs from isolated blackboard
              outputs (when tick-ctx
                        (let [bb (:blackboard (rm/get-tick-execution-context context tick-id))]
                          (reduce-kv (fn [acc k entry]
                                       (assoc acc k (:value entry)))
                                     {}
                                     bb)))]
          {:result/events
           [(->event
             {:type :sheet/tree-tick-completed
              :tags #{[:sheet sheet-id]
                      [:tick tick-id]}
              :body (cond-> {:sheet-id sheet-id
                             :tick-id tick-id
                             :iteration current-iteration
                             :root-status final-status}
                      outputs (assoc :outputs outputs)
                      error (assoc :error error))})]})))))


;; =============================================================================
;; Execution Completion Delivery
;; =============================================================================

(defn- aggregate-tick-by-node
  "Read all :sheet/node-execution-completed events for the given tick-id
   from the event store, and aggregate per-node usage with a structured
   node-path key.

   When a node's :usage already contains a :by-node breakdown (i.e. it
   was a parent node like repl-researcher that aggregated child-tick
   per-node usage), we PASS THROUGH those structured entries instead of
   reducing to a single entry — this preserves the full path detail from
   Phase 2 child ticks back into the parent tick's saved result."
  [event-store tenant-id tick-id]
  (when event-store
    (let [tick-events (into [] (es/read event-store
                                (cond-> {:tags #{[:tick tick-id]}}
                                  tenant-id (assoc :tenant-id tenant-id))))
          completions (filter #(= :sheet/node-execution-completed (:event/type %))
                              tick-events)]
      (reduce
        (fn [acc event]
          (let [node-id (:node-id event)
                inputs (:inputs event)
                usage (:usage event)
                child-by-node (:by-node usage)]
            (cond
              ;; Pass-through: this node already has a per-child breakdown
              ;; (it was a parent that aggregated Phase 2 sub-LLM calls).
              ;; Inherit those entries directly so the structured paths
              ;; are preserved.
              (and node-id (seq child-by-node))
              (merge-with (fn [a b]
                            (merge-with + a b))
                          acc child-by-node)

              ;; Simple case: this node had its own usage but no sub-breakdown.
              ;; Build a path from map-each context (if present) and add as
              ;; a single entry.
              (and node-id usage)
              (let [map-each-parent (some (fn [k]
                                            (when (and (keyword? k)
                                                       (= (name k) "map-each-parent"))
                                              (get inputs k)))
                                          (keys (or inputs {})))
                    map-each-index (some (fn [k]
                                           (when (and (keyword? k)
                                                      (= (name k) "map-each-index"))
                                             (get inputs k)))
                                         (keys (or inputs {})))
                    path (if (and (some? map-each-parent) (some? map-each-index))
                           [{:type :map-each :parent map-each-parent :index map-each-index}
                            {:type :leaf :node-id node-id}]
                           [{:type :leaf :node-id node-id}])]
                (update acc path
                        (fn [existing]
                          (merge-with +
                                      (or existing {:prompt-tokens 0
                                                    :completion-tokens 0
                                                    :total-tokens 0})
                                      (select-keys usage
                                                   [:prompt-tokens
                                                    :completion-tokens
                                                    :total-tokens])))))

              :else acc)))
        {}
        completions))))

(defn deliver-execution-result
  "When a tick completes, deliver the result to any waiting promise.
   This bridges the async todo processor pipeline back to sync callers
   who are blocking on runtime/execute.

   Skips delivery for intermediate :running completions — those are
   re-tick signals, not final results. The final delivery happens when
   the tree completes with :success/:failure, or when max iterations
   is hit (complete-tree-tick converts :running to :failure)."
  [{:keys [event event-store tenant-id]}]
  (let [tick-id (:tick-id event)
        root-status (:root-status event)
        outputs (:outputs event)
        error (:error event)]
    ;; Skip intermediate :running completions — those are re-tick signals,
    ;; not final results. complete-tree-tick converts :running to :failure
    ;; when max iterations are exhausted.
    (when (not= root-status :running)
      ;; Get aggregated usage before clearing
      (let [usage (get-tick-usage tick-id)
            by-node (aggregate-tick-by-node event-store tenant-id tick-id)
            usage-with-breakdown (cond-> usage
                                   (seq by-node) (assoc :by-node by-node))]
        ;; Clean up budget and usage tracking for this tick
        (clear-llm-count! tick-id)
        (clear-tick-usage! tick-id)
        (runtime/deliver-completion! tick-id
          (cond-> {:status (case root-status
                             :success :success
                             :failure :failure
                             :tree-generated :tree-generated
                             ;; D-008: surface :partial to callers so they
                             ;; can distinguish "we got some output" from
                             ;; "we got nothing".
                             :partial :partial
                             ;; D-003: surface :timeout truthfully so callers
                             ;; can distinguish "budget exceeded mid-execution"
                             ;; from "we failed for some other reason".
                             :timeout :timeout
                             :failure)
                   :outputs (or outputs {})
                   ;; Include raw tree for :tree-generated status (canonical form generated at execution time)
                   :generated-tree-raw (get outputs :generated-tree-raw)
                   :trace-id tick-id
                   :error error}
            ;; Include usage if any LLM calls were made
            (pos? (:total-tokens usage 0)) (assoc :usage usage-with-breakdown)))))
    ;; No events to emit
    nil))

;; =============================================================================
;; Trace Assembly (Post-hoc from events)
;; =============================================================================

(defn assemble-execution-trace
  "After a tick completes, assemble an execution trace from events and store it.
   Only runs for tick-scoped executions (snapshot-based).
   Reads node-execution-started/completed events, correlates them with node
   metadata from the execution snapshot, and stores via store-execution-trace."
  [{:keys [event event-store] :as context}]
  (let [tick-id (:tick-id event)
        sheet-id (:sheet-id event)
        root-status (:root-status event)
        outputs (:outputs event)
        tick-ctx (rm/get-tick-execution-context context tick-id)]
    ;; Only assemble traces for tick-scoped executions
    (when tick-ctx
      (let [nodes-by-id (:nodes-by-id tick-ctx)
            version-number (:version-number tick-ctx)
            ;; Read all events for this tick (into [] to realize reducible)
            tick-events (into [] (es/read event-store {:tags #{[:tick tick-id]} :tenant-id (:tenant-id context)}))
            ;; Find tick-started event for timing
            started-event (first (filter #(= :sheet/tree-tick-started (:event/type %)) tick-events))
            started-at (when started-event (:event/timestamp started-event))
            completed-at (:event/timestamp event)
            ;; Build input snapshot from tick blackboard seed
            input-snapshot (let [bb (:blackboard tick-ctx)]
                             (reduce-kv (fn [acc k entry]
                                          (if (some? (:value entry))
                                            (assoc acc k (:value entry))
                                            acc))
                                        {} bb))
            ;; Build output snapshot
            output-snapshot (or outputs {})
            ;; Correlate node-execution-started and completed events
            started-events (filter #(= :sheet/node-execution-started (:event/type %)) tick-events)
            completed-events (filter #(= :sheet/node-execution-completed (:event/type %)) tick-events)
            ;; Build completed map: node-id -> completion event
            completed-by-node (reduce (fn [acc e] (assoc acc (:node-id e) e)) {} completed-events)
            ;; Build node traces
            node-traces (vec
                          (for [started started-events
                                :let [node-id (:node-id started)
                                      node (get nodes-by-id node-id)
                                      completed (get completed-by-node node-id)]
                                :when node]
                            (cond-> {:node-id node-id
                                     :node-name (:name node)
                                     :node-type (:type node)
                                     :parent-id (:parent-id node)
                                     :status (or (:status completed) :unknown)
                                     :started-at (str (:event/timestamp started))
                                     :completed-at (when completed (str (:event/timestamp completed)))}
                              (:duration-ms completed) (assoc :duration-ms (:duration-ms completed))
                              (:writes completed) (assoc :outputs (:writes completed))
                              (:error completed) (assoc :error (:error completed))
                              ;; Inputs from event (non-context keys)
                              (:inputs started) (assoc :inputs
                                                       (into {} (filter (fn [[k _]] (and (keyword? k) (not (= (namespace k) (namespace ::_)))))
                                                                        (:inputs started)))))))
            ;; Calculate duration
            duration-ms (if (and started-at completed-at)
                          (- (.toEpochMilli (java.time.Instant/parse (str completed-at)))
                             (.toEpochMilli (java.time.Instant/parse (str started-at))))
                          0)
            trace-id tick-id
            final-status (case root-status :success :success :failure :failure :failure)]
        ;; Store trace via command in a future (best-effort, non-blocking).
        ;; Must be async because cp/process-command -> es/append -> pubsub/pub
        ;; can deadlock if called synchronously within a todo processor thread.
        (future
          (try
            (cp/process-command
              (assoc context :command
                     (cond-> {:command/id (random-uuid)
                              :command/timestamp (time/now)
                              :command/name :sheet/store-execution-trace
                              :trace-id trace-id
                              :sheet-id sheet-id
                              :started-at (str (or started-at (time/now)))
                              :completed-at (str (or completed-at (time/now)))
                              :duration-ms duration-ms
                              :status final-status
                              :input-snapshot input-snapshot
                              :output-snapshot output-snapshot
                              :node-traces node-traces}
                       version-number (assoc :version-number version-number)
                       (:error event) (assoc :error (:error event)))))
            (catch Exception _e
              ;; Log but don't fail — trace storage is best-effort
              nil)))
        ;; No events to emit directly
        nil))))

;; =============================================================================
;; Todo Processor Registry
;; =============================================================================

;; =============================================================================
;; Snapshot Restore Processor
;; =============================================================================

(defn- flatten-snapshot-nodes
  "Flatten a nested snapshot tree into a list of [node parent-id] pairs,
   in creation order (parent before children)."
  [snapshot-node parent-id]
  (when snapshot-node
    (let [node-id (random-uuid)
          node-record [node-id parent-id snapshot-node]
          children (or (:children snapshot-node) [])]
      (cons node-record
            (mapcat #(flatten-snapshot-nodes % node-id) children)))))

(defn- collect-deletion-order
  "Collect nodes in deletion order (children before parents, leaf-first).
   Returns vector of node-ids to delete."
  [nodes-by-id root-id]
  (letfn [(collect [node-id]
            (let [node (get nodes-by-id node-id)
                  children-ids (:children-ids node)]
              (concat (mapcat collect children-ids)
                      [node-id])))]
    (when root-id
      (vec (collect root-id)))))

(defn restore-from-snapshot
  "Restore sheet state from a version or stash snapshot.
   Handles both :sheet/draft-reverted and :sheet/stash-restored events.

   This generates all events needed to:
   1. Delete all existing nodes (leaf-first)
   2. Delete all existing blackboard keys
   3. Recreate nodes from snapshot
   4. Recreate blackboard schema from snapshot"
  [{:keys [event event-store] :as context}]
  (let [event-type (:event/type event)
        sheet-id (:sheet-id event)
        snapshot (:snapshot event)]
    (when (and (#{:sheet/draft-reverted :sheet/stash-restored} event-type)
               snapshot)
      (let [;; Get current state to delete
            current-nodes-by-id (rm/get-nodes-by-id context sheet-id)
            current-sheet (rm/get-sheet context sheet-id)
            current-root-id (:root-node-id current-sheet)
            current-blackboard (rm/get-blackboard-by-key context sheet-id)

            ;; Generate deletion events for nodes (leaf-first order)
            node-deletion-ids (collect-deletion-order current-nodes-by-id current-root-id)
            node-deletion-events (mapv (fn [node-id]
                                         (->event
                                          {:type :sheet/node-deleted
                                           :tags #{[:sheet sheet-id]
                                                   [:node node-id]}
                                           :body {:sheet-id sheet-id
                                                  :node-id node-id}}))
                                       node-deletion-ids)

            ;; Generate deletion events for blackboard keys
            key-deletion-events (mapv (fn [[k _]]
                                        (->event
                                         {:type :sheet/key-deleted
                                          :tags #{[:sheet sheet-id]}
                                          :body {:sheet-id sheet-id
                                                 :key k}}))
                                      current-blackboard)

            ;; Extract snapshot data
            snapshot-nodes (:nodes snapshot)
            blackboard-schema (:blackboard-schema snapshot)

            ;; Generate key declaration events
            key-declaration-events (mapv (fn [[k schema]]
                                           (->event
                                            {:type :sheet/key-declared
                                             :tags #{[:sheet sheet-id]}
                                             :body {:sheet-id sheet-id
                                                    :key k
                                                    :schema schema}}))
                                         blackboard-schema)

            ;; Flatten snapshot tree to get node creation order
            node-records (flatten-snapshot-nodes snapshot-nodes nil)

            ;; Generate node creation and configuration events
            node-events (mapcat
                         (fn [[node-id parent-id snapshot-node]]
                           (let [node-type (:type snapshot-node)
                                 create-event (->event
                                               {:type :sheet/node-created
                                                :tags #{[:sheet sheet-id]
                                                        [:node node-id]}
                                                :body (cond-> {:sheet-id sheet-id
                                                               :node-id node-id
                                                               :type node-type}
                                                        parent-id (assoc :parent-id parent-id))})
                                 ;; Name event
                                 name-event (when (:name snapshot-node)
                                              (->event
                                               {:type :sheet/node-name-set
                                                :tags #{[:sheet sheet-id]
                                                        [:node node-id]}
                                                :body {:sheet-id sheet-id
                                                       :node-id node-id
                                                       :name (:name snapshot-node)}}))
                                 ;; Configuration events based on node type
                                 config-events
                                 (case node-type
                                   :leaf
                                   (filterv some?
                                            [(when (:instruction snapshot-node)
                                               (->event
                                                {:type :sheet/node-instruction-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :instruction (:instruction snapshot-node)}}))
                                             (when (or (seq (:reads snapshot-node))
                                                       (seq (:writes snapshot-node)))
                                               (->event
                                                {:type :sheet/node-io-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :reads (or (:reads snapshot-node) [])
                                                        :writes (or (:writes snapshot-node) [])}}))
                                             (when (:executor snapshot-node)
                                               (->event
                                                {:type :sheet/node-executor-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :executor (:executor snapshot-node)}
                                                         (:model snapshot-node) (assoc :model (:model snapshot-node))
                                                         (:fn snapshot-node) (assoc :fn (:fn snapshot-node))
                                                         (:tools snapshot-node) (assoc :tools (:tools snapshot-node)))}))
                                             (when (:retry snapshot-node)
                                               (->event
                                                {:type :sheet/node-retry-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :retry (:retry snapshot-node)}}))])

                                   :condition
                                   (filterv some?
                                            [(when (:check snapshot-node)
                                               (->event
                                                {:type :sheet/node-check-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :check (:check snapshot-node)}}))])

                                   :llm-condition
                                   (filterv some?
                                            [(when (or (:instruction snapshot-node)
                                                       (seq (:reads snapshot-node)))
                                               (->event
                                                {:type :sheet/llm-condition-config-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :instruction (or (:instruction snapshot-node) "")
                                                                :reads (or (:reads snapshot-node) [])}
                                                         (:model snapshot-node) (assoc :model (:model snapshot-node)))}))])

                                   :parallel
                                   [(->event
                                     {:type :sheet/parallel-config-set
                                      :tags #{[:sheet sheet-id] [:node node-id]}
                                      :body {:sheet-id sheet-id
                                             :node-id node-id
                                             :success-policy (or (:success-policy snapshot-node) :all)
                                             :failure-policy (or (:failure-policy snapshot-node) :any)}})]

                                   :map-each
                                   (filterv some?
                                            [(when (and (:source-key snapshot-node)
                                                        (:item-key snapshot-node)
                                                        (:output-key snapshot-node))
                                               (->event
                                                {:type :sheet/map-each-config-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :source-key (:source-key snapshot-node)
                                                                :item-key (:item-key snapshot-node)
                                                                :output-key (:output-key snapshot-node)}
                                                         (:max-concurrency snapshot-node)
                                                         (assoc :max-concurrency (:max-concurrency snapshot-node)))}))])

                                   :delegate
                                   (filterv some?
                                            [(when (:target-sheet-id snapshot-node)
                                               (->event
                                                {:type :sheet/delegate-config-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :target-sheet-id (:target-sheet-id snapshot-node)
                                                                :reads (or (:reads snapshot-node) [])
                                                                :writes (or (:writes snapshot-node) [])}
                                                         (:delegate-timeout-ms snapshot-node)
                                                         (assoc :timeout-ms (:delegate-timeout-ms snapshot-node))
                                                         (some? (:inherit-ontology? snapshot-node))
                                                         (assoc :inherit-ontology? (:inherit-ontology? snapshot-node)))}))])

                                   ;; sequence, fallback, repl-researcher - no extra config needed
                                   [])]
                             (filterv some? (into [create-event name-event] config-events))))
                         node-records)]

        {:result/events
         (vec (concat node-deletion-events
                      key-deletion-events
                      key-declaration-events
                      node-events))}))))

;; =============================================================================
;; Processor Registration (defprocessor delegates to existing handler fns)
;; =============================================================================

(defprocessor :sheet start-tree-tick
  {:topics #{:sheet/tree-tick-started}}
  "Start tick execution when tree tick begins."
  [context]
  (execute-tree-tick context))

(defprocessor :sheet execute-leaf-node
  {:topics #{:sheet/node-execution-started}}
  "Execute leaf nodes when node execution starts."
  [context]
  (execute-leaf-node context))

(defprocessor :sheet execute-condition-node
  {:topics #{:sheet/node-execution-started}}
  "Execute condition nodes when node execution starts."
  [context]
  (execute-condition-node context))

(defprocessor :sheet execute-composite-node
  {:topics #{:sheet/node-execution-started}}
  "Execute composite nodes (sequence/fallback) when node execution starts."
  [context]
  (execute-composite-node context))

(defprocessor :sheet execute-parallel-node
  {:topics #{:sheet/node-execution-started}}
  "Execute parallel nodes when node execution starts."
  [context]
  (execute-parallel-node context))

(defprocessor :sheet execute-map-each-node
  {:topics #{:sheet/node-execution-started}}
  "Execute map-each nodes when node execution starts."
  [context]
  (execute-map-each-node context))

(defprocessor :sheet execute-repl-researcher-node
  {:topics #{:sheet/node-execution-started}}
  "Execute repl-researcher nodes when node execution starts."
  [context]
  (execute-repl-researcher-node context))

(defprocessor :sheet execute-delegate-node
  {:topics #{:sheet/node-execution-started}}
  "Execute delegate nodes when node execution starts."
  [context]
  (execute-delegate-node context))

(defprocessor :sheet handle-child-completion
  {:topics #{:sheet/node-execution-completed}}
  "Handle child completion for composite nodes."
  [context]
  (handle-child-completion context))

(defprocessor :sheet handle-map-each-child-completion
  {:topics #{:sheet/node-execution-completed}}
  "Handle map-each child iteration completion."
  [context]
  (handle-map-each-child-completion context))

(defprocessor :sheet update-blackboard
  {:topics #{:sheet/node-execution-completed}}
  "Update blackboard on node completion."
  [context]
  (update-blackboard-on-completion context))

(defprocessor :sheet complete-tree-tick
  {:topics #{:sheet/node-execution-completed}}
  "Complete tree tick when root node completes."
  [context]
  (complete-tree-tick context))

(defprocessor :sheet restore-from-snapshot
  {:topics #{:sheet/draft-reverted :sheet/stash-restored}}
  "Restore sheet state from a version or stash snapshot."
  [context]
  (restore-from-snapshot context))

(defprocessor :sheet deliver-execution-result
  {:topics #{:sheet/tree-tick-completed}}
  "Deliver execution result to waiting promises (bridges async to sync)."
  [context]
  (deliver-execution-result context))

(defprocessor :sheet assemble-execution-trace
  {:topics #{:sheet/tree-tick-completed}}
  "Assemble and store execution trace from events."
  [context]
  (assemble-execution-trace context))

;; =============================================================================
;; RLM Rolling Judge Processor
;; =============================================================================

(defn- evaluate-tree-structure
  "Evaluate an RLM-generated tree structure heuristically.
   Returns a map with :score (0.0-1.0), :feedback, and :dimensions."
  [raw-dsl]
  (let [;; Check for key structural elements
        has-sequence? (and (vector? raw-dsl) (= :sequence (first raw-dsl)))
        has-llm-node? (some (fn find-llm [node]
                              (cond
                                (not (vector? node)) false
                                (= :llm (first node)) true
                                :else (some find-llm (rest node))))
                            (if (vector? raw-dsl) raw-dsl []))
        has-final? (some (fn find-final [node]
                           (cond
                             (not (vector? node)) false
                             (= :final (first node)) true
                             :else (some find-final (rest node))))
                         (if (vector? raw-dsl) raw-dsl []))
        has-map-each? (some (fn find-map-each [node]
                              (cond
                                (not (vector? node)) false
                                (= :map-each (first node)) true
                                :else (some find-map-each (rest node))))
                            (if (vector? raw-dsl) raw-dsl []))
        ;; Calculate dimension scores
        structure-score (cond
                          (and has-sequence? has-final?) 1.0
                          has-sequence? 0.7
                          :else 0.3)
        decomposition-score (cond
                              has-map-each? 1.0
                              has-llm-node? 0.6
                              :else 0.2)
        ;; Weighted average
        overall-score (* 0.5 (+ (* 0.6 structure-score)
                                 (* 0.4 decomposition-score)))
        feedback (cond
                   (and has-sequence? has-map-each? has-final?)
                   "Excellent tree structure with proper decomposition pattern."
                   (and has-sequence? has-llm-node? has-final?)
                   "Good tree structure but could use map-each for better decomposition."
                   has-sequence?
                   "Basic tree structure. Consider adding sub-LLM calls for analysis."
                   :else
                   "Tree structure needs improvement. Use :sequence as root.")]
    {:score overall-score
     :feedback feedback
     :dimensions [{:name "Structure" :weight 0.6 :score structure-score
                   :feedback (if has-sequence? "Valid sequence root" "Missing sequence root")}
                  {:name "Decomposition" :weight 0.4 :score decomposition-score
                   :feedback (if has-map-each? "Uses map-each for parallelism" "Could benefit from map-each")}]}))

(defn evaluate-rlm-tree
  "Rolling judge processor for :rlm/tree-generated events.
   Evaluates the tree structure and emits :rlm/tree-evaluated event."
  [{:keys [event event-store] :as context}]
  (let [tree-id (:tree-id event)
        execution-id (:execution-id event)
        raw-dsl (:raw-dsl event)
        {:keys [score feedback dimensions]} (evaluate-tree-structure raw-dsl)]
    {:result/events
     [(->event
       {:type :rlm/tree-evaluated
        :tags #{[:tree tree-id]}
        :body {:tree-id tree-id
               :execution-id execution-id
               :score score
               :feedback feedback
               :dimensions dimensions
               :evaluated-at (str (java.time.Instant/now))}})]}))

(defprocessor :rlm evaluate-rlm-tree
  {:topics #{:rlm/tree-generated}}
  "Rolling judge: evaluate RLM-generated tree structure."
  [context]
  (evaluate-rlm-tree context))
