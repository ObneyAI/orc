(ns ai.obney.orc.orc-service.interface.agent-runtime
  "Factories for the built-in AgentRuntime implementations."
  (:require [ai.obney.orc.orc-service.core.agent-runtime :as agent-runtime]
            [ai.obney.orc.orc-service.core.logic :as logic]))

(defn create-mock-runtime
  "Create a mock agent runtime.

   Options:
   - :delay-ms - Simulated processing delay in milliseconds (default: 100)
   - :responses - Map of instruction -> outputs (or instruction -> fn)
                  If a function, called with (signature inputs)

   Example:
   (create-mock-runtime
     :delay-ms 50
     :responses {\"Write a summary\" {\"summary\" {:type :text :value \"Custom summary\"}}
                 \"Evaluate quality\" (fn [sig inputs]
                                        {\"score\" {:type :number :value (rand-int 100)}})})"
  [& {:as options}]
  (logic/mock-runtime-from-options agent-runtime/->MockAgentRuntime options))

(defn create-configurable-runtime
  "Create a configurable mock runtime for testing error scenarios.

   Options:
   - :delay-ms - Simulated processing delay (default: 100)
   - :error-rate - Probability of failure, 0.0-1.0 (default: 0)
   - :responses - Map of instruction -> outputs or functions"
  [& {:as options}]
  (logic/configurable-runtime-from-options agent-runtime/->ConfigurableMockRuntime options))

(defn create-echo-runtime
  "Create an echo runtime that returns debug info in outputs."
  []
  (agent-runtime/->EchoRuntime))
