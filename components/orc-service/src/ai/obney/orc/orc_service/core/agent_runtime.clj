(ns ai.obney.orc.orc-service.core.agent-runtime
  "Agent runtime for executing cell formulas.

   This namespace provides:
   - AgentRuntime protocol for pluggable agent implementations
   - MockAgentRuntime for testing (generates mock outputs)
   - Helper functions for creating runtimes"
  (:require [ai.obney.orc.orc-service.core.logic :as logic]))

;; =============================================================================
;; Agent Runtime Protocol
;; =============================================================================

(defprotocol AgentRuntime
  "Protocol for agent execution implementations."
  (execute [this signature inputs]
    "Execute a signature with inputs, return outputs map.
     signature: {:instruction string, :inputs [...], :outputs [...]}
     inputs: {input-name {:type field-type, :value any}}
     returns: {output-name {:type field-type, :value any}}"))

;; =============================================================================
;; Mock Agent Runtime
;; =============================================================================

(defrecord MockAgentRuntime [delay-ms responses]
  AgentRuntime
  (execute [_ signature inputs]
    (when delay-ms
      (Thread/sleep delay-ms))

    (if-let [response (get responses (:instruction signature))]
      (if (fn? response)
        (response signature inputs)
        response)
      (logic/generate-mock-outputs (:outputs signature)))))

;; =============================================================================
;; Configurable Mock Runtime
;; =============================================================================

(defrecord ConfigurableMockRuntime [delay-ms error-rate responses]
  AgentRuntime
  (execute [_ signature inputs]
    (when delay-ms
      (Thread/sleep delay-ms))

    (when (and error-rate (< (rand) error-rate))
      (throw (ex-info "Simulated agent failure" {:type :agent-error})))

    (if-let [response (get responses (:instruction signature))]
      (if (fn? response)
        (response signature inputs)
        response)
      (logic/generate-mock-outputs (:outputs signature)))))

(defrecord EchoRuntime []
  AgentRuntime
  (execute [_ signature inputs]
    (into {}
          (map (fn [output-def]
                 [(:name output-def)
                  {:type (:type output-def)
                   :value (str "Echo: instruction='" (:instruction signature)
                               "' inputs=" (pr-str inputs))}])
               (:outputs signature)))))
