(ns dev
  "ORC development REPL.

   Quick start:
     1. Connect to nREPL on port 7888
     2. Eval the (start!) form in the comment block
     3. Build and execute workflows against `ctx`"
  (:require [ai.obney.orc.orc-dev.core :as orc-dev]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

(defonce service (atom nil))

(declare stop!)

(defn start! []
  (when @service (stop!))
  (reset! service (orc-dev/start))
  (println "ORC started.")
  :started)

(defn stop! []
  (when-let [s @service]
    (orc-dev/stop s)
    (reset! service nil)
    (println "ORC stopped.")
    :stopped))

(defn ctx []
  (or (::orc-dev/context @service)
      (throw (ex-info "Not started. Run (dev/start!) first." {}))))

(defn cmd! [command-data]
  (cp/process-command
   (assoc (ctx) :command
     (merge {:command/id (random-uuid)
             :command/timestamp (time/now)}
            command-data))))

(comment
  ;; === Quick Start ===

  (start!)

  ;; Define a function for the code node to resolve
  (defn my-upper [{:keys [inputs]}]
    {:output (.toUpperCase (str (:input inputs)))})

  ;; Build a workflow using the DSL
  ;; See docs/dsl-tutorial.md for the full node reference
  (def my-workflow
    (orc/workflow "hello"
      (orc/blackboard {:input :string :output :string})
      (orc/code "upper"
        :fn "dev/my-upper"
        :reads [:input]
        :writes [:output])))

  (def sheet-id (orc/build-workflow! (ctx) my-workflow))

  ;; Execute it
  (orc/execute (ctx) sheet-id {:input "hello from orc"})
  ;; => {:status :success, :outputs {:input "hello from orc", :output "HELLO FROM ORC"}, ...}

  ;; LLM node example (requires :llm-provider configured)
  (def llm-workflow
    (orc/workflow "greeting"
      (orc/blackboard {:name :string :greeting :string})
      (orc/llm "greet"
        :instruction "Generate a friendly one-sentence greeting for the given name."
        :reads [:name]
        :writes [:greeting])))

  (stop!)

  ,)
