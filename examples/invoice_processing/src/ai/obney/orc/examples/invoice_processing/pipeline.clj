(ns ai.obney.orc.examples.invoice-processing.pipeline
  "Style A — explicit invoice pipeline.

     1. (code  explode-pages)   — fan invoices into page-text records
     2. (map-each extract-page) — per-page LLM extraction (parallel 8)
     3. (code  merge-invoices)  — combine page extracts → InvoiceExtractionResult
     4. (code  build-workbook)  — render summary + per-invoice sheets to .xlsx"
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.examples.invoice-processing.schemas :as schemas]
            [ai.obney.orc.doc-skills.core.pdf :as pdf]
            [ai.obney.orc.doc-skills.core.xlsx :as xlsx]))

(defn explode-pages
  "Produce a flat seq of {:path :n :text} for every page across all invoices."
  [{:keys [inputs]}]
  (let [paths (:invoices inputs)]
    {:pages
     (vec (for [path paths
                n (range (pdf/page-count path))]
            {:path path :n n :text (pdf/page-text path n)}))}))

(defn merge-invoices
  "Aggregate per-page extractions into an InvoiceExtractionResult.

   Pages that share a vendor-name+invoice-number are merged; line-items are
   concatenated across pages of the same invoice."
  [{:keys [inputs]}]
  (let [extracts (or (:page-extracts inputs) [])
        by-key (group-by (fn [e] [(get e :vendor-name)
                                  (get e :invoice-number)])
                         (filter map? extracts))
        invoices (mapv
                   (fn [[_ pages]]
                     (let [head (first pages)
                           items (vec (mapcat #(get % :line-items []) pages))]
                       (assoc (select-keys head [:vendor-name :invoice-number :date
                                                 :due-date :subtotal :tax :total])
                              :line-items items)))
                   by-key)
        total (reduce + 0.0 (keep :total invoices))]
    {:result {:invoices invoices
              :total-amount total
              :summary (str "Processed " (count invoices) " invoices across "
                            (count extracts) " pages.")}}))

(defn build-workbook
  "Write the InvoiceExtractionResult to an .xlsx workbook with a Summary
   sheet plus one sheet per invoice."
  [{:keys [inputs]}]
  (let [{:keys [result]} inputs
        out-path (str (System/getProperty "java.io.tmpdir")
                      "/invoice-extraction-" (System/currentTimeMillis) ".xlsx")
        summary-cols [{:header "Vendor"     :width 24}
                      {:header "Invoice #"  :width 14}
                      {:header "Date"       :width 12}
                      {:header "Due Date"   :width 12}
                      {:header "Subtotal"   :width 12}
                      {:header "Tax"        :width 10}
                      {:header "Total"      :width 12}]
        summary-rows (mapv (fn [{:keys [vendor-name invoice-number date due-date
                                        subtotal tax total]}]
                             [vendor-name invoice-number date due-date
                              (or subtotal 0.0) (or tax 0.0) (or total 0.0)])
                           (:invoices result))
        item-cols   [{:header "Description" :width 40}
                     {:header "Quantity"    :width 10}
                     {:header "Unit Price"  :width 12}
                     {:header "Amount"      :width 12}]
        per-invoice (mapv (fn [{:keys [vendor-name invoice-number line-items]}]
                            {:name (str (or vendor-name "Invoice") " "
                                        (or invoice-number ""))
                             :columns item-cols
                             :rows (mapv (fn [{:keys [description quantity unit-price amount]}]
                                           [description quantity unit-price amount])
                                         line-items)})
                          (:invoices result))
        spec (into [{:name "Summary" :columns summary-cols :rows summary-rows}]
                   per-invoice)]
    {:workbook (xlsx/write-workbook out-path spec)}))

(def workflow
  (dsl/workflow "invoice-processing-pipeline"
    (dsl/blackboard schemas/blackboard)
    (dsl/sequence "main"
      (dsl/code "explode-pages"
        :fn "ai.obney.orc.examples.invoice-processing.pipeline/explode-pages"
        :reads [:invoices]
        :writes [:pages])

      (dsl/map-each "extract-page"
        :from :pages
        :as :page
        :into :page-extracts
        :parallel 8
        (dsl/llm "extract-invoice-page"
          :model "google/gemini-2.5-flash"
          :instruction
          (str "Extract invoice fields from this single PDF page.\n\n"
               "If this page is the start of an invoice, populate vendor-name, "
               "invoice-number, date, due-date, subtotal, tax, total, and line-items.\n"
               "If this page is a continuation, populate line-items only and copy the "
               "vendor-name + invoice-number you can identify from the page header.\n\n"
               "Stay grounded — only extract what's actually visible in the text.")
          :reads [:page]
          :writes [:page-extract]
          :judges ["grounding"]))

      (dsl/code "merge-invoices"
        :fn "ai.obney.orc.examples.invoice-processing.pipeline/merge-invoices"
        :reads [:page-extracts]
        :writes [:result])

      (dsl/code "build-workbook"
        :fn "ai.obney.orc.examples.invoice-processing.pipeline/build-workbook"
        :reads [:result]
        :writes [:workbook]))))
