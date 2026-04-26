(ns ai.obney.orc.examples.contract-comparison.agentic
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.contract-comparison.schemas :as schemas]))

(def signature-strategy
  (str
"Compare PDF contracts and produce a structured ComparisonResult.

You receive:
  contracts — vector of PDF paths (full value bound; root prompt sees only metadata)

Strategy:

1. Survey each contract:

   (let [docs (mapv (fn [p] {:path p :pages (pdf/page-count p) :text (pdf/document-text p)})
                    contracts)]
     ...)

2. Use predict-all to summarize each contract's section structure in parallel:

   (let [summaries (predict-all
                     {:name        \"summarize-contract\"
                      :items       docs
                      :as          :doc
                      :inputs      {}
                      :instructions \"List section headings and key terms.\"
                      :schema      [:map [:sections [:vector :map]]]
                      :max-concurrency 4})]
     ...)

3. Use predict to produce the comparison:

   (let [result (predict
                  {:name \"compare\"
                   :inputs {:summaries summaries :docs docs}
                   :instructions \"Produce a structured ComparisonResult.\"
                   :schema [:map
                            [:report :string]
                            [:section-diffs [:vector :map]]
                            [:key-differences [:vector :map]]
                            [:summary :string]]})]
     (final! {:result result}))

Stay grounded — do not invent sections or terms."))

(def workflow
  (dsl/workflow "contract-comparison-agentic"
    (dsl/blackboard schemas/blackboard)
    (dsl/repl-researcher "orchestrate"
      :model "google/gemini-2.5-flash"
      :instruction
      (str (ds/compose-instructions :pdf) "\n\n" signature-strategy)
      :reads [:contracts]
      :writes [:result]
      :mcp-tools (vec ds/all-tool-names)
      :max-iterations 12
      :rlm {:enabled? true
            :context-key :contracts
            :max-predict-calls 50
            :max-predict-concurrency 4
            :max-predict-input-chars 200000
            :history-preview-chars 6000})))
