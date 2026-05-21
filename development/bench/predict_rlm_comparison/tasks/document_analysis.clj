(ns predict-rlm-comparison.tasks.document-analysis
  "Document analysis benchmark — predict-rlm port for apples-to-apples
   comparison. The model analyzes a multi-page PDF (the YYJ Parking
   Management RFP) and produces a structured DocumentAnalysis with a
   markdown report, key dates, and key entities.

   Source task: predict-rlm/examples/document_analysis/signature.py
   Reference data: development/bench/predict_rlm_comparison/references/
                    predict-rlm/document_analysis/sample/

   Note: ORC has its own development/bench/document_analysis.clj that
   uses the SAME source PDF but with a different (string-only) output
   schema. This port mirrors predict-rlm's structured DocumentAnalysis
   schema (KeyDate + KeyEntity + report) so we can compare extraction
   directly against predict-rlm's published report.md.

   Models: gpt-5.4 main + gpt-5.1-chat sub for apples-to-apples.

   This file is also a complete worked example of how to compose an ORC
   RLM benchmark — see the :task map at the bottom for the full shape
   (name, slug, model + sub-model, instruction, input/output schemas,
   input-loader, writes, evaluation-criteria, predict-rlm-reported
   metadata).

   Run from REPL:
     (require '[predict-rlm-comparison.tasks.document-analysis :as t])
     (require '[predict-rlm-comparison.runner :as r])
     (r/start!)
     (r/run! t/task)
     (r/stop!)

   Or via the standalone Clojure runner:
     clj -M:dev:test -m predict-rlm-comparison.run.document-analysis"
  (:require [ai.obney.orc.predict-rlm-pdf.interface :as pdf]))

(def ^:private pdf-path
  "development/bench/predict_rlm_comparison/references/predict-rlm/document_analysis/sample/input/YYJ-2025-Parking-Management-RFP.pdf")

(def criteria
  "Verbatim from predict-rlm's run.py CRITERIA constant — defines what
   sections the briefing report must contain and how to format them."
  "Analyze the document(s) and produce a comprehensive briefing report
structured as follows.

---

**Formatting guidelines:**

The report should be professional, easy to read, and visually elegant.
Mix prose, tables, bullets, and numbered items to draw the topology of
the information and help the reader quickly parse the report. Do not
include page references — present information directly. Use bold
sparingly. Favor prose over bullets; use bullets very sparingly.

---

**Report sections:**

1.  **Executive Summary**
    What is this document about? Who are the key parties involved?
    What are the most important facts, decisions, or actions described?

2.  **Key Dates and Timeline**
    All significant dates mentioned in the document: deadlines,
    effective dates, milestones, meetings, expiration dates. Present
    in chronological order. Flag any unusually tight timelines.

3.  **Key Entities and Stakeholders**
    People, organizations, and roles mentioned in the document.
    For each, note their role and relevance. Include contact
    information where available.

4.  **Financial Information**
    Any monetary amounts, fees, budgets, pricing structures,
    payment terms, or financial obligations mentioned. Summarize
    in a table if multiple items exist.")

(def instruction
  "Port-cleaned from predict-rlm's signature.py docstring (verbatim end-goal
   + output schema; Python tool nouns stripped; adversarial-completeness
   added)."
  (str "Analyze documents and produce a structured report.

   Goal:
     Read the provided document pages, then produce a comprehensive
     briefing report (in markdown) PLUS structured lists of key dates
     and key entities. Both the human-readable markdown report and the
     structured lists must accurately reflect content actually present
     in the source documents.

   Output schema:
     :report — markdown string, the full briefing report following the
       criteria's section structure (Executive Summary, Key Dates and
       Timeline, Key Entities and Stakeholders, Financial Information).
     :key-dates — vector of maps with keys :name (e.g. \"Submission Deadline\",
       \"Effective Date\"), :date (ISO YYYY-MM-DD), :time (24-hour HH:MM
       optional), :timezone (e.g. \"PST\", \"EST\", optional).
     :key-entities — vector of maps with keys :name (person, organization,
       or role), :role (optional), :contact (contact info if available,
       optional).

   QUALITY REQUIREMENTS:
     - Information must be accurate and traceable to the source documents.
     - Do NOT invent dates, people, organizations, or facts not present.
     - Dates in :key-dates MUST be ISO YYYY-MM-DD format (transform from
       whatever format appears in the source).
     - The markdown :report should be professional, mix prose with tables
       where useful, follow the report-criteria structure below.

   STRUCTURAL VERIFICATION REQUIREMENT: After producing an initial
   extraction, adversarially re-examine the document pages to verify
   completeness:
     - Did you capture every important date (deadlines, meetings,
       milestones, term dates)?
     - Did you correctly identify all named people, organizations, and
       roles — including those mentioned only briefly?
     - Did the markdown report cover all four required sections?
   If anything was missed or wrong, correct it before producing the final
   structured result.

   COMPOSITION CONSIDERATIONS — read carefully:

   - The blackboard provides page texts as a vector keyed :document-page-texts.
     Each element is the plain text extracted from one PDF page. The
     document has roughly 136 pages so the vector is large.

   - If your design iterates pages, each per-iteration sub-call sees one
     page in isolation. The sub-call cannot infer its own position in the
     source collection from text content alone. If you need positional
     context (e.g. \"this is page 12 of the RFP\"), thread that context
     into each iteration explicitly.

   - For multi-section extraction tasks: producing a structured report
     usually involves (a) gathering raw content per page, (b) classifying
     content into report sections, and (c) synthesizing the final markdown.
     The framework supports doing each step as a separate tree node, with
     intermediate writes flowing through the blackboard.

   - Behavior-tree primitives (sequence, parallel, map-each, llm, code,
     final) are the durable, observable record of the work. Prefer emitting
     a tree over coordinating multiple sub-calls inline as imperative
     Clojure code in Phase 1.\n\n"

       "---\n\n## REPORT CRITERIA (from predict-rlm verbatim)\n\n"
       criteria))

(defn- load-inputs []
  ;; Use per-page TEXT extraction (not vision) to keep the run efficient.
  ;; predict-rlm renders all 136 pages as images and uses vision (their
  ;; published cost: 194K tokens / ~4min / $0.52). Our path: extract text
  ;; per page via PDFBox, iterate pages as plain strings. This is the same
  ;; text-mode approach that worked for document_redaction (89 redactions
  ;; in 27s). The fidelity caveat is documented in the report: ORC's
  ;; comparison is text-extraction-based; predict-rlm is vision-based.
  {:document-page-texts (vec (pdf/extract-pages-as-text pdf-path))})

(def task
  {:name "Document Analysis (predict-rlm port)"
   :slug "document-analysis-predict-rlm"
   :pattern "Multi-page document analysis (vision LLM per page) → structured DocumentAnalysis"
   :model "openai/gpt-5.4"
   :sub-model "openai/gpt-5.1-chat"
   :instruction instruction
   :input-schemas {:document-page-texts [:vector :string]}
   :output-schemas {:report :string
                    :key-dates [:vector [:map-of :any :any]]
                    :key-entities [:vector [:map-of :any :any]]}
   :input-loader load-inputs
   :writes [:report :key-dates :key-entities]
   :evaluation-criteria
   ["RFP issue date (2025-02-13) captured"
    "Proposal submission deadline (2025-03-31) captured"
    "Mandatory pre-bid meeting (2025-02-27) captured"
    "Contract commencement date (2025-06-01) captured"
    "Key personnel identified: David Parson (Commercial Development Officer)"
    "Key personnel identified: Elizabeth M. Brown (CEO of VAA)"
    "Key organizations: Victoria Airport Authority (VAA), YYJ"
    "Financial info: 5% LOC, fixed management fee structure, revenue handling"
    "Report markdown has all 4 required sections"]
   ;; predict-rlm publishes Run Stats for this benchmark in their sample/output/report.md
   :predict-rlm-reported {:main-lm-calls 8 :sub-lm-calls 63
                          :input-tokens 163557 :output-tokens 30515
                          :total-tokens 194072
                          :cost-usd 0.52
                          :duration-approx "~4 minutes"
                          :pages 136
                          :cost-per-page 0.004
                          :note "From their sample/output/report.md Run Stats table"}})
