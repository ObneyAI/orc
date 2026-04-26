(ns ai.obney.orc.workflow-driver.core.bt-node
  "Compose the driver loop as a behavior-tree code node.

   Any workflow can include a node that runs `run-driver-loop!`
   against a target Sheet — the synthesizer/optimizer pattern from
   RLM-DEEP-ANALYSIS.md Part 7 becomes first-class composable. The
   parent workflow supplies the driver's config via blackboard keys
   the code node reads from.

   This is implemented as a `(orc/code …)` node pointing at
   `driver-loop-code-fn` rather than a new node type — keeps the
   orc DSL surface unchanged and the integration shallow."
  (:require [ai.obney.orc.workflow-driver.core.loop :as drv-loop]
            [ai.obney.orc.orc-service.interface :as orc]))

(def ^:private required-config-keys
  [:sheet-id :objective :eval-set])

(defn- assert-config!
  [cfg]
  (let [missing (remove #(contains? cfg %) required-config-keys)]
    (when (seq missing)
      (throw (ex-info "driver-loop-code-fn: missing required config keys"
               {:missing missing :got (vec (keys cfg))})))))

(defn driver-loop-code-fn
  "Code-node entry point: reads `:driver-config` from inputs, runs
   the driver loop, returns `:driver-result`.

   The driver config map shape:
     {:sheet-id        <uuid>     ; required — target sheet
      :objective       <string>   ; required
      :eval-set        [...]      ; required
      :max-turns       <int>
      :min-pass-rate   <0..1>
      :model           <string>
      :provider        <keyword>
      :tick-timeout-ms <int>
      :description     <string>}

   The full execution context (event-store, command/query registries,
   pubsub) is destructured from the surrounding ctx so the driver loop
   reaches the same grain backend the parent workflow runs on."
  [{:as ctx :keys [inputs]}]
  (let [cfg (or (:driver-config inputs) inputs)]
    (assert-config! cfg)
    (let [;; Drop :inputs from ctx so the driver call gets a fresh ctx
          driver-ctx (dissoc ctx :inputs :command :query)
          result (drv-loop/run-driver-loop! driver-ctx cfg)]
      {:driver-result result})))

(defn driver-node
  "DSL helper: build a code-node that runs the driver loop against a
   target Sheet. Reads `:driver-config` from the parent workflow's
   blackboard (or whichever key you pass via `:reads`). Writes the
   loop's full result map to `:writes` (default `[:driver-result]`).

   Usage:
     (orc/workflow \"meta-workflow\"
       (orc/blackboard {:driver-config :map :driver-result :map})
       (orc/sequence \"main\"
         (driver/driver-node \"improve-extraction\")))

   The parent caller is responsible for populating `:driver-config` —
   typically by injecting it as an input on `orc/execute`."
  [name & {:keys [reads writes]
           :or {reads [:driver-config]
                writes [:driver-result]}}]
  (orc/code name
    :fn "ai.obney.orc.workflow-driver.core.bt-node/driver-loop-code-fn"
    :reads reads
    :writes writes))
