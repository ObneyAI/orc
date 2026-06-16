(ns ai.obney.orc.orc-service.core.observability
  "Observability infrastructure for behavior tree execution.
   Provides structured logging, error categorization, and slow execution detection."
  (:require [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def slow-thresholds-ms
  "Thresholds for slow execution warnings by node type (in milliseconds)."
  {:condition 100
   :leaf 30000
   :llm-condition 30000
   :sequence 60000
   :fallback 60000
   :parallel 120000
   :map-each 300000
   :execution 300000})

;; =============================================================================
;; Error Categorization
;; =============================================================================

(def error-categories
  "Error category definitions for classification."
  {:ai-provider-error "AI provider returned error"
   :ai-timeout "AI call timed out"
   :ai-rate-limit "Rate limited by AI provider"
   :ai-invalid-response "AI returned invalid/nil outputs"
   :code-execution-error "Code executor threw exception"
   :code-resolution-error "Could not resolve code function"
   :validation-error "Input/output validation failed"
   :timeout "Overall execution timed out"
   :tree-structure-error "BT structure issue"
   :blackboard-error "Blackboard key issue"
   :unknown "Uncategorized error"})

(defn categorize-error
  "Categorize an error based on exception or error message.
   Returns a keyword from error-categories."
  [error-or-exception]
  (let [msg (str (if (instance? Exception error-or-exception)
                   (.getMessage ^Exception error-or-exception)
                   error-or-exception))]
    (cond
      (re-find #"(?i)rate.?limit|429|too many requests" msg) :ai-rate-limit
      (re-find #"(?i)timeout|timed out" msg) :ai-timeout
      (re-find #"(?i)could not resolve|function not found|unable to resolve" msg) :code-resolution-error
      (re-find #"(?i)node not found|missing node" msg) :tree-structure-error
      (re-find #"(?i)blackboard key|not declared|key not found" msg) :blackboard-error
      (re-find #"(?i)validation|invalid|schema" msg) :validation-error
      (re-find #"(?i)nil output|empty output" msg) :ai-invalid-response
      :else :unknown)))

;; =============================================================================
;; Active Executions Gauge
;; =============================================================================

(defonce ^:private active-executions (atom 0))

(defn inc-active-executions!
  "Increment the active executions counter. Returns new count."
  []
  (swap! active-executions inc))

(defn dec-active-executions!
  "Decrement the active executions counter. Returns new count."
  []
  (swap! active-executions dec))

(defn get-active-executions
  "Get the current count of active executions."
  []
  @active-executions)

;; =============================================================================
;; Slow Execution Detection
;; =============================================================================

(defn slow?
  "Check if a duration exceeds the slow threshold for a node type."
  [node-type duration-ms]
  (let [threshold (get slow-thresholds-ms node-type (:execution slow-thresholds-ms))]
    (> duration-ms threshold)))

(defn get-threshold
  "Get the slow threshold for a node type."
  [node-type]
  (get slow-thresholds-ms node-type (:execution slow-thresholds-ms)))

;; =============================================================================
;; Logging Helpers - Execution Level
;; =============================================================================

(defn log-execution-started!
  "Log the start of a behavior tree execution."
  [{:keys [sheet-id trace-id input-keys root-node-id]}]
  (inc-active-executions!)
  (u/log ::execution-started
         :sheet-id sheet-id
         :trace-id trace-id
         :root-node-id root-node-id
         :input-keys input-keys
         :active-executions (get-active-executions)))

(defn log-execution-completed!
  "Log the completion of a behavior tree execution."
  [{:keys [sheet-id trace-id status duration-ms output-keys error]}]
  (dec-active-executions!)
  (u/log ::execution-completed
         :sheet-id sheet-id
         :trace-id trace-id
         :status status
         :duration-ms duration-ms
         :output-keys output-keys
         :active-executions (get-active-executions)
         :error (when error (str error))))

(defn log-execution-timeout!
  "Log a timeout of a behavior tree execution."
  [{:keys [sheet-id trace-id duration-ms timeout-ms]}]
  (dec-active-executions!)
  (u/log ::execution-timeout
         :level :warn
         :sheet-id sheet-id
         :trace-id trace-id
         :duration-ms duration-ms
         :timeout-ms timeout-ms))

;; =============================================================================
;; Logging Helpers - Node Level
;; =============================================================================

(defn log-node-started!
  "Log the start of a node execution."
  [{:keys [node-id node-name node-type trace-id]}]
  (u/log ::node-started
         :level :debug
         :node-id node-id
         :node-name node-name
         :node-type node-type
         :trace-id trace-id))

(defn log-node-completed!
  "Log the completion of a node execution. Includes slow detection."
  [{:keys [node-id node-name node-type status duration-ms trace-id error]}]
  (let [is-slow (slow? node-type duration-ms)]
    ;; Log completion
    (u/log ::node-completed
           :level (cond
                    (= status :failure) :warn
                    is-slow :warn
                    :else :debug)
           :node-id node-id
           :node-name node-name
           :node-type node-type
           :status status
           :duration-ms duration-ms
           :trace-id trace-id
           :error (when error (str error))
           :slow is-slow)
    ;; Log slow warning separately for easier alerting
    (when is-slow
      (u/log ::node-slow
             :level :warn
             :node-id node-id
             :node-name node-name
             :node-type node-type
             :duration-ms duration-ms
             :threshold-ms (get-threshold node-type)
             :exceeded-by-ms (- duration-ms (get-threshold node-type))
             :trace-id trace-id))))

;; =============================================================================
;; Logging Helpers - Error Level
;; =============================================================================

(defn log-error!
  "Log an error with categorization."
  [{:keys [node-id node-name node-type error trace-id]}]
  (let [category (categorize-error error)]
    (u/log ::error
           :level :error
           :node-id node-id
           :node-name node-name
           :node-type node-type
           :error-category category
           :error-message (str error)
           :trace-id trace-id)))

;; =============================================================================
;; Logging Helpers - AI Execution
;; =============================================================================

(defn log-ai-execution!
  "Log an AI node execution with token usage."
  [{:keys [node-id node-name model executor duration-ms status usage trace-id error]}]
  (u/log ::ai-execution
         :level (if (= status :failure) :warn :debug)
         :node-id node-id
         :node-name node-name
         :model model
         :executor executor
         :duration-ms duration-ms
         :status status
         :prompt-tokens (:prompt-tokens usage)
         :completion-tokens (:completion-tokens usage)
         :total-tokens (:total-tokens usage)
         :trace-id trace-id
         :error (when error (str error))))

(defn log-retry!
  "Log a retry attempt."
  [{:keys [node-id node-name attempt max-attempts reason trace-id]}]
  (u/log ::retry-attempt
         :level :info
         :node-id node-id
         :node-name node-name
         :attempt attempt
         :max-attempts max-attempts
         :reason reason
         :trace-id trace-id))

(defn log-unparseable-output!
  "Log an LLM response that parsed to nil for declared writes.

   Carries the FULL raw response (verbatim, no truncation) so the parse
   failure is diagnosable from the trace alone — without this the model's
   actual output is unrecoverable once the node completes."
  [{:keys [node-id node-name model nil-keys raw-length raw-response trace-id]}]
  (u/log ::unparseable-output
         :level :warn
         :node-id node-id
         :node-name node-name
         :model model
         :nil-keys nil-keys
         :raw-length raw-length
         :raw-response raw-response
         :trace-id trace-id))

;; =============================================================================
;; Logging Helpers - Composite Nodes
;; =============================================================================

(defn log-parallel-started!
  "Log the start of a parallel node execution."
  [{:keys [node-id node-name child-count trace-id]}]
  (u/log ::parallel-started
         :level :info
         :node-id node-id
         :node-name node-name
         :child-count child-count
         :trace-id trace-id))

(defn log-map-each-started!
  "Log the start of a map-each node execution."
  [{:keys [node-id node-name item-count concurrency trace-id]}]
  (u/log ::map-each-started
         :level :info
         :node-id node-id
         :node-name node-name
         :item-count item-count
         :concurrency concurrency
         :trace-id trace-id))

(defn log-sequence-progress!
  "Log progress through a sequence node."
  [{:keys [node-id node-name child-index total-children trace-id]}]
  (u/log ::sequence-progress
         :level :debug
         :node-id node-id
         :node-name node-name
         :child-index child-index
         :total-children total-children
         :trace-id trace-id))

(defn log-map-each-progress!
  "Log progress through a map-each node."
  [{:keys [node-id node-name item-index total-items trace-id]}]
  (u/log ::map-each-progress
         :level :debug
         :node-id node-id
         :node-name node-name
         :item-index item-index
         :total-items total-items
         :trace-id trace-id))
