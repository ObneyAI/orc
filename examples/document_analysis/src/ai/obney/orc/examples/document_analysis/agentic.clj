(ns ai.obney.orc.examples.document-analysis.agentic
  "Style B — RLM-faithful port of document_analysis.

   Single repl-researcher with :rlm true. The LLM sees only metadata for
   :documents (count, paths preview) and writes Clojure that calls
   pdf/page-count + pdf/page-image-data-uri + (predict-all …) over pages,
   then synthesizes a report and writes it via docx/write-markdown-as-docx,
   ending with (final! …)."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.document-analysis.schemas :as schemas]))

(def signature-strategy
  (str
"Analyze documents and produce a structured DocumentAnalysis report.

You receive:
  documents — vector of PDF paths (full value bound; root prompt sees only metadata)
  criteria  — string describing what to extract and in what format

Strategy (translated from predict-rlm's DSPy signature docstring):

1. Survey the documents using `pdf/page-count` for each path. Print a brief
   summary of file names and page counts.

2. Gather information by extracting page text (or rendering page images for
   vision-heavy material) and calling `predict-all` over the items in
   bounded parallel:

       (let [pages (for [doc documents
                         n (range (pdf/page-count doc))]
                     {:doc doc :n n})
             findings (predict-all
                        {:name        \"extract-page\"
                         :items       (mapv (fn [p] (pdf/page-text (:doc p) (:n p))) pages)
                         :as          :page-text
                         :inputs      {:criteria criteria}
                         :instructions \"Extract content relevant to the criteria.\"
                         :schema      :string
                         :max-concurrency 6})]
         ...)

3. Synthesize a markdown report and a structured analysis:

       (let [analysis
             (predict
               {:name \"synthesize\"
                :inputs {:findings findings :criteria criteria}
                :instructions \"Produce a markdown report plus key-dates and key-entities.\"
                :schema [:map
                         [:report :string]
                         [:key-dates [:vector [:map [:name :string] [:date :string]]]]
                         [:key-entities [:vector [:map [:name :string]]]]]})]
         ...)

4. Render the markdown into a DOCX:

       (let [docx-path (docx/write-markdown-as-docx
                          \"/sandbox/output/report.docx\"
                          (:report analysis))]
         (final! {:analysis analysis :docx-report docx-path}))

Stay grounded — do NOT invent dates or entities not present in the source text."))

(def workflow
  (dsl/workflow "document-analysis-agentic"
    (dsl/blackboard schemas/blackboard)
    (dsl/repl-researcher "orchestrate"
      :model "google/gemini-2.5-flash"
      :instruction
      (str (ds/compose-instructions :pdf :docx) "\n\n" signature-strategy)
      :reads [:documents :criteria]
      :writes [:analysis :docx-report]
      :mcp-tools (vec ds/all-tool-names)
      :max-iterations 15
      :rlm {:enabled? true
            :context-key :documents
            :max-predict-calls 100
            :max-predict-concurrency 6
            :max-predict-input-chars 200000
            :history-preview-chars 6000})))
