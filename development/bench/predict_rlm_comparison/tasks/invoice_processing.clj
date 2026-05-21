(ns predict-rlm-comparison.tasks.invoice-processing
  "Invoice processing benchmark — predict-rlm port for apples-to-apples
   comparison. The model extracts structured invoice data (vendor, number,
   dates, line items, totals) from PDF invoices via vision, then references
   the pre-built `build-invoice-workbook` :code node to produce a real
   .xlsx output.

   Source task: predict-rlm/examples/invoice_processing/signature.py
   Reference data: development/bench/predict-rlm-comparison/references/
                    predict-rlm/invoice_processing/sample/

   Models: gpt-5.4 main + gpt-5.1-chat sub for apples-to-apples (matching
   predict-rlm's published setup).

   This file is also a complete worked example of how to compose an ORC RLM
   benchmark — see the :task map at the bottom for the full shape (task name,
   model + sub-model, instruction, input/output schemas, input-loader,
   writes, evaluation-criteria, predict-rlm-reported metadata).

   Run from REPL:
     (require '[predict-rlm-comparison.tasks.invoice-processing :as t])
     (require '[predict-rlm-comparison.runner :as r])
     (r/start!)
     (r/run! t/task)
     (r/stop!)

   Or via the standalone Clojure runner:
     clj -M:dev:test -m predict-rlm-comparison.run.invoice-processing"
  (:require [ai.obney.orc.predict-rlm-pdf.interface :as pdf]
            [ai.obney.orc.predict-rlm-invoice-tools.interface :as invoice-tools]
            [clojure.java.io :as io]))

(def ^:private references-dir
  "development/bench/predict-rlm-comparison/references/predict-rlm/invoice_processing/sample/input")

(def ^:private acme-pdf-path
  (str references-dir "/acme-invoice-2025-0042.pdf"))

(def ^:private globaltech-pdf-path
  (str references-dir "/globaltech-invoice-GT-10587.pdf"))

(def ^:private output-workbook-path
  ;; Each run writes a fresh workbook here. The path is computed at
  ;; task-load time so the model sees a real absolute path in its inputs.
  (.getAbsolutePath
    (io/file "development/bench/predict-rlm-comparison/results/invoice_extraction.xlsx")))

(def instruction
  "Goal-only instruction, port-cleaned from predict-rlm's signature.py docstring.

   Verbatim end-goal + output-schema description preserved. Python-specific
   methodology nouns (`pymupdf`, `asyncio.gather`, `predict()`, `openpyxl`)
   stripped. Adversarial-completeness clause added.

   The model is NOT told which tree shape to use, only what the output must
   look like and what code-node functions are available."
  "Extract structured data from PDF invoices into an Excel spreadsheet.

   Goal:
     For each invoice (provided as pre-rendered page images on the
     blackboard), extract the vendor name, invoice number, ISO-formatted
     date and due date, subtotal, tax, total, and individual line items
     (description, quantity, unit price, amount per line).

   Then produce an .xlsx workbook at the provided :output-path containing:
     - One 'Summary' sheet with columns: Vendor, Invoice #, Date, Due Date,
       Subtotal, Tax, Total (one row per invoice).
     - One sheet per invoice (named after the vendor) with columns:
       Description, Quantity, Unit Price, Amount (one row per line item).

   Output schema for :invoices is a vector of maps with these keys:
     :vendor-name (string) — vendor/supplier name
     :invoice-number (string) — invoice number/reference
     :date (string) — invoice date in ISO YYYY-MM-DD format
     :due-date (string) — payment due date in ISO YYYY-MM-DD format
     :subtotal (number) — subtotal before tax and discounts (in dollars)
     :tax (number) — tax amount in dollars
     :total (number) — total amount due in dollars
     :line-items (vector of maps) — each item has :description (string),
                  :quantity (number), :unit-price (number), :amount (number)

   :total-amount (number) — combined total across all invoices.
   :summary (string) — brief summary of the invoices processed and what
     the workbook contains.
   :workbook-path (string) — absolute path to the produced .xlsx file.

   QUALITY REQUIREMENTS:
     - Every numeric field must be a real number extracted from the
       invoice, not a placeholder.
     - Dates MUST be ISO YYYY-MM-DD (transform whatever format appears
       in the source).
     - Line-item amounts should be consistent with quantity * unit-price
       (within rounding tolerance); if they aren't on the invoice, use
       what the invoice says rather than recomputing.
     - subtotal + tax should equal total (within rounding tolerance);
       if the invoice itself doesn't reconcile, return what is printed.

   STRUCTURAL VERIFICATION REQUIREMENT: After identifying invoice contents,
   adversarially re-examine the rendered pages to verify completeness:
     - Did you capture every line item?
     - Did you correctly identify vendor name (often appears in the header
       or letterhead, not necessarily in the bill-to line)?
     - Are dates and amounts read precisely from the document, or did you
       paraphrase / round?
   If anything was missed or wrong, correct it before producing the final
   structured result.

   COMPOSITION CONSIDERATIONS — read carefully:

   - The blackboard provides image vectors keyed by invoice (:acme-pages,
     :globaltech-pages). Each is a vector of base64 data-URI strings, one
     per page of that invoice. The schema marks them as :field-type :image
     so the framework routes them as multimodal image content (not text).

   - If your design iterates pages within an invoice, each per-iteration
     sub-call sees one page in isolation. The sub-call cannot infer its own
     position in the source collection from page content alone. If you need
     positional context (e.g. \"this is page 2 of the acme invoice\"), thread
     that context into each iteration explicitly.

   - For multi-invoice tasks: structured outputs from per-invoice extraction
     must be combined into a single :invoices vector before the workbook-
     writing step. The :code node that writes the workbook expects a flat
     vector of invoice maps, not a nested structure.

   - The pre-built code-node function `build-invoice-workbook` (advertised
     below) does the deterministic xlsx-writing step. Reference it from
     your tree via `[:code {:fn \"ns/sym\" :reads [...] :writes [:workbook-path]}]`
     rather than re-implementing xlsx generation inline. The path string
     to write to is provided on the blackboard as :output-path.

   - Behavior-tree primitives (sequence, parallel, map-each, llm, code,
     final) are the durable, observable record of the work. Prefer emitting
     a tree over coordinating multiple sub-calls inline as imperative
     Clojure code in Phase 1.")

(defn- load-inputs []
  (let [acme-pages (pdf/render-pages-as-data-uris acme-pdf-path {:dpi 200})
        globaltech-pages (pdf/render-pages-as-data-uris globaltech-pdf-path {:dpi 200})]
    {:acme-pages (vec acme-pages)
     :globaltech-pages (vec globaltech-pages)
     :output-path output-workbook-path}))

(def task
  {:name "Invoice Processing (predict-rlm port)"
   :slug "invoice-processing"
   :pattern "Multi-invoice vision-extraction → structured data → xlsx output"
   ;; Apples-to-apples model setup matching predict-rlm:
   ;;   main LM:  openai/gpt-5.4
   ;;   sub LM:   openai/gpt-5.1-chat
   :model "openai/gpt-5.4"
   :sub-model "openai/gpt-5.1-chat"
   ;; Catalog content embedded directly in :instruction (avoiding the
   ;; framework's :available-code-nodes input-field path that has known
   ;; interaction issues with gpt-5.4 — see document_redaction.clj for
   ;; the same workaround).
   :instruction (str instruction
                     "\n\n## Available pre-built code-node functions\n\n"
                     invoice-tools/available-code-nodes)
   :input-schemas {:acme-pages [:vector {:field-type :image} :string]
                   :globaltech-pages [:vector {:field-type :image} :string]
                   :output-path :string}
   :output-schemas {:invoices [:vector [:map-of :any :any]]
                    :total-amount :double
                    :summary :string
                    :workbook-path :string}
   :input-loader load-inputs
   :writes [:invoices :total-amount :summary :workbook-path]
   :evaluation-criteria
   ["Both invoices' vendor names extracted correctly"
    "Both invoices' ISO dates correctly formatted (YYYY-MM-DD)"
    "Line items from each invoice captured (description + quantity + unit-price + amount)"
    "Subtotal + tax + total reconcile per invoice (within rounding)"
    ":total-amount equals sum of per-invoice totals"
    "Workbook file exists at :workbook-path"
    "Workbook has Summary sheet + per-invoice sheets with correct columns"]
   ;; Reference numbers from predict-rlm's sample/output (output.md is minimal —
   ;; no published token/cost stats for this benchmark, just the xlsx + screenshot).
   :predict-rlm-reported {:invoices-count 2
                          :workbook-file "invoice_extraction.xlsx"
                          :note "output.md does not publish token/cost stats; structural comparison is xlsx-based"}})
