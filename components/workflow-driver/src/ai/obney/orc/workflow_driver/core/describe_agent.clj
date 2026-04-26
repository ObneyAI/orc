(ns ai.obney.orc.workflow-driver.core.describe-agent
  "Milestone 1 validation agent: a thin LLM caller that takes a Sheet
   snapshot rendered by `workflow-driver/describe-sheet` and asks the
   model to summarize the workflow's purpose, surface trouble signs,
   and propose a first thing to optimize.

   Purpose: confirm the read-only ops give an LLM enough signal to
   reason about a target Sheet — the cheapest test of whether the
   read-model surface is rich enough to drive against."
  (:require [dscloj.core :as dscloj]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.workflow-driver.core.observe :as observe]
            [ai.obney.orc.workflow-driver.core.format :as fmt]))

(def ^:private describe-module
  {:inputs [{:name :snapshot
             :spec :string
             :description "Structured snapshot of an ORC behavior-tree Sheet — its DSL form, blackboard schema, per-node rolling metrics, recent ticks, and cost/duration frontier."}]
   :outputs [{:name :purpose
              :spec :string
              :description "2-3 sentences describing what this workflow appears to do, inferred from node names and instructions. Cite specific node names."}
             {:name :trouble-signs
              :spec :string
              :description "Concrete trouble signs observable in the snapshot: failing nodes, never-run nodes, missing instructions, suspicious metric trends. If none, say so explicitly. Cite node names."}
             {:name :first-optimization
              :spec :string
              :description "The most likely first thing to optimize next, with a one-sentence rationale tied to the snapshot. If there is no execution history yet, say so and propose a smoke run."}]
   :instructions
   (str "You are an ORC workflow engineer reviewing a behavior-tree Sheet's "
        "current structure and recent performance. The snapshot you receive "
        "is the only information you have — do not invent facts. Be specific: "
        "always cite node names and metrics where applicable. If the snapshot "
        "indicates no execution history, say so plainly rather than speculating "
        "about performance.\n\n"
        "ORC vocabulary you may use: 'sequence', 'parallel', 'map-each', "
        "'fallback' (composite nodes); 'llm', 'code', 'repl-researcher', "
        "'delegate' (leaf nodes); 'blackboard' (the keyed data store nodes "
        "read from / write to); 'tick' (one execution of the tree).")})

(defn build-describe-input
  "Compose the full text the LLM sees as `:snapshot`. Pulled out for
   testability — callable without an LLM key."
  [ctx sheet-id tick-limit]
  (let [snap (observe/sheet-snapshot ctx sheet-id)]
    (if-not snap
      (str "No Sheet found for id " sheet-id)
      (let [dsl (observe/sheet-as-dsl ctx sheet-id)
            nodes (observe/node-summary ctx sheet-id)
            ticks (observe/recent-ticks ctx sheet-id {:limit tick-limit})
            front (observe/pareto ctx sheet-id {:limit tick-limit})]
        (str
          (fmt/render-sheet-snapshot snap)
          "\n\nCurrent tree (DSL form):\n"
          (or dsl "<unable to render>")
          "\n\n"
          (fmt/render-node-summary nodes)
          "\n\n"
          (fmt/render-recent-ticks ticks)
          "\n\n"
          (fmt/render-pareto front))))))

(defn describe-via-llm!
  "Run the describe-Sheet agent against `sheet-id`. Composes the
   read-only observation pipeline, hands the rendered snapshot to a
   single LLM call, and returns the model's structured report.

   Options:
     :model       — DSCloj model id (default \"google/gemini-2.5-flash\")
     :provider    — DSCloj provider keyword (default :openrouter)
     :tick-limit  — how many recent ticks to include (default 10)

   Returns:
     {:snapshot  <the text shown to the LLM>
      :outputs   {:purpose … :trouble-signs … :first-optimization …}
      :usage     {…}
      :model     <resolved model id>}"
  [ctx sheet-id & [{:keys [model provider tick-limit]
                    :or {model "google/gemini-2.5-flash"
                         provider :openrouter
                         tick-limit 10}}]]
  (let [snapshot-text (build-describe-input ctx sheet-id tick-limit)
        effective-provider (orc/get-provider-with-model provider model)
        result (dscloj/predict
                 effective-provider
                 describe-module
                 {:snapshot snapshot-text}
                 {:validate? false
                  :with-metadata? true})]
    {:snapshot snapshot-text
     :outputs (or (:outputs result) result)
     :usage (:usage result)
     :model (or (:model result) model)}))
