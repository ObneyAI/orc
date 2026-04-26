(ns ai.obney.orc.examples.invoice-processing.agentic
  "Style B — the canonical RLM-mode demo.

   The LLM writes Clojure that:
     1. enumerates pages with `pdf/page-count` + `pdf/page-text`
     2. fans out per-page extraction with `(predict-all …)` — this is the
        Clojure analog of predict-rlm's
            await asyncio.gather(*[predict(p) for p in pages])
     3. merges page extracts into a result map
     4. writes the workbook with `xlsx/write-workbook`
     5. captures the result via `(final! …)`."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.invoice-processing.schemas :as schemas]))

(def signature-strategy
  (str
"Extract structured invoice data from PDF invoices and write an Excel workbook.

You receive:
  invoices — vector of PDF paths (full value bound; root prompt sees only metadata)

Strategy:

1. Enumerate every (path, page-number) across the invoices and grab text:

   (let [pages (vec (for [path invoices
                          n (range (pdf/page-count path))]
                      {:path path :n n :text (pdf/page-text path n)}))]
     ...)

2. Fan out per-page extraction with predict-all (this is the canonical RLM
   primitive — one bounded call producing many sub-LM responses in parallel):

   (let [extracts
         (predict-all
           {:name        \"extract-invoice-page\"
            :items       pages
            :as          :page
            :inputs      {}
            :instructions \"Extract vendor, invoice number, date, due-date,
                            subtotal, tax, total, and line-items from this
                            page. Continuation pages should populate
                            line-items only.\"
            :schema      [:map
                          [:vendor-name :string]
                          [:invoice-number :string]
                          [:date :string]
                          [:due-date :string]
                          [:subtotal :double]
                          [:tax :double]
                          [:total :double]
                          [:line-items [:vector [:map
                                                  [:description :string]
                                                  [:quantity :double]
                                                  [:unit-price :double]
                                                  [:amount :double]]]]]
            :max-concurrency 8})]
     ...)

3. Merge page extracts into one InvoiceExtractionResult — group by
   vendor-name + invoice-number, concatenate line-items, sum totals:

   (let [grouped (group-by (juxt :vendor-name :invoice-number) extracts)
         merged-invoices (mapv ...)
         result {:invoices merged-invoices
                 :total-amount (reduce + 0.0 (keep :total merged-invoices))
                 :summary (str \"Processed \" (count merged-invoices) \" invoices.\")}]
     ...)

4. Build the workbook (Summary sheet + per-invoice sheets) and write it:

   (let [path (xlsx/write-workbook
                \"/sandbox/output/invoice-extraction.xlsx\"
                (into [{:name \"Summary\"
                        :columns [...]
                        :rows    [...]}]
                      (for [inv merged-invoices]
                        {:name (str (:vendor-name inv) \" \" (:invoice-number inv))
                         :columns [...]
                         :rows    (mapv ... (:line-items inv))})))]
     (final! {:result result :workbook path}))

Stay grounded — only extract values that appear in the page text."))

(def workflow
  (dsl/workflow "invoice-processing-agentic"
    (dsl/blackboard schemas/blackboard)
    (dsl/repl-researcher "orchestrate"
      :model "google/gemini-2.5-flash"
      :instruction
      (str (ds/compose-instructions :pdf :xlsx) "\n\n" signature-strategy)
      :reads [:invoices]
      :writes [:result :workbook]
      :mcp-tools (vec ds/all-tool-names)
      :max-iterations 20
      :rlm {:enabled? true
            :context-key :invoices
            :max-predict-calls 200
            :max-predict-concurrency 8
            :max-predict-input-chars 200000
            :history-preview-chars 6000})))
