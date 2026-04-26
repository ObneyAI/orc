(ns ai.obney.orc.examples.document-redaction.agentic
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.document-redaction.schemas :as schemas]))

(def signature-strategy
  (str
"Redact sensitive content from PDFs based on the criteria.

You receive:
  documents — vector of PDF paths
  criteria  — string describing what to redact (PII categories, etc.)

Strategy (translated from predict-rlm/examples/document_redaction):

1. Survey: get page-count and text per page for each document.

2. Use predict-all to identify redaction targets per document — the LLM
   returns {:page :text :category :reason} entries that LITERALLY appear
   in the document text:

   (let [targets-per-doc
         (predict-all
           {:name        \"find-targets\"
            :items       documents
            :as          :path
            :inputs      {:criteria criteria}
            :instructions \"Return [{:page :text :category :reason}] for matches.\"
            :schema      [:vector [:map [:page :int]
                                        [:text :string]
                                        [:category :string]
                                        [:reason :string]]]
            :max-concurrency 4})]
     ...)

3. For each (path, targets) pair, use pdf/search-text to find rectangles
   for each target text, then pdf/redact-rects to write a redacted PDF.

4. (Optional) Verify by re-rendering each redacted page and asking a vision
   sub-LM to check for remaining PII.

5. End with (final! {:redacted-documents [...] :result {...}}).

Stay grounded — only emit targets that appear in the actual page text."))

(def workflow
  (dsl/workflow "document-redaction-agentic"
    (dsl/blackboard schemas/blackboard)
    (dsl/repl-researcher "orchestrate"
      :model "google/gemini-2.5-flash"
      :instruction
      (str (ds/compose-instructions :pdf :redaction) "\n\n" signature-strategy)
      :reads [:documents :criteria]
      :writes [:redacted-documents :result]
      :mcp-tools (vec ds/all-tool-names)
      :max-iterations 20
      :rlm {:enabled? true
            :context-key :documents
            :max-predict-calls 100
            :max-predict-concurrency 4
            :max-predict-input-chars 200000
            :history-preview-chars 6000})))
