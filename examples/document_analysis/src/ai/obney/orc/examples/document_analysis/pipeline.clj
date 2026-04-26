(ns ai.obney.orc.examples.document-analysis.pipeline
  "Style A — explicit pipeline for document_analysis.

     1. (code  survey)         — read page counts via PDFBox
     2. (map-each summarize)   — per-document llm summary using extracted text
     3. (llm    synthesize)    — produce DocumentAnalysis from summaries+criteria
     4. (code   render-docx)   — write the markdown report as DOCX"
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.examples.document-analysis.schemas :as schemas]
            [ai.obney.orc.doc-skills.core.pdf :as pdf]
            [ai.obney.orc.doc-skills.core.docx :as docx]))

(defn survey
  "code executor: produce per-document metadata + extracted text."
  [{:keys [inputs]}]
  (let [docs (:documents inputs)]
    {:document-meta
     (mapv (fn [path]
             (let [n-pages (pdf/page-count path)
                   ;; Cap text per doc to avoid bloating the LLM prompt
                   raw (pdf/document-text path)
                   text (subs raw 0 (min (count raw) 16000))]
               {:path path
                :pages n-pages
                :text text}))
           docs)}))

(defn render-docx
  "code executor: render the analysis :report markdown into a DOCX file."
  [{:keys [inputs] :as ctx-arg}]
  (let [{:keys [analysis]} inputs
        out-dir (or (:out-dir ctx-arg) (System/getProperty "java.io.tmpdir"))
        out-path (str out-dir "/document_analysis-report.docx")
        md (or (:report analysis) "")]
    {:docx-report (docx/write-markdown-as-docx out-path md)}))

(def workflow
  (dsl/workflow "document-analysis-pipeline"
    (dsl/blackboard schemas/blackboard)
    (dsl/sequence "main"
      (dsl/code "survey"
        :fn "ai.obney.orc.examples.document-analysis.pipeline/survey"
        :reads [:documents]
        :writes [:document-meta])

      (dsl/map-each "summarize-each"
        :from :document-meta
        :as :document
        :into :doc-summaries
        :parallel 3
        (dsl/llm "summarize-doc"
          :model "google/gemini-2.5-flash"
          :instruction
          (str "Summarize this document for an analyst. Be concise and grounded.\n\n"
               "Pull out: any deadlines or key dates, named entities (people / orgs / roles), "
               "and the main subject matter.\n\n"
               "Do NOT invent details that aren't in the text.")
          :reads [:document :criteria]
          :writes [:doc-summary]))

      (dsl/llm "synthesize"
        :model "google/gemini-2.5-flash"
        :instruction
        (str "Combine per-document summaries into a single structured analysis.\n\n"
             "Produce three outputs:\n"
             "1. report — a clean markdown report following the criteria.\n"
             "2. key-dates — a list of {:name :date [:time] [:timezone]}.\n"
             "3. key-entities — a list of {:name [:role] [:contact]}.\n\n"
             "Stay grounded in the per-document summaries; do not fabricate.")
        :reads [:doc-summaries :criteria]
        :writes [:analysis]
        :judges ["grounding"])

      (dsl/code "render-docx"
        :fn "ai.obney.orc.examples.document-analysis.pipeline/render-docx"
        :reads [:analysis]
        :writes [:docx-report]))))
