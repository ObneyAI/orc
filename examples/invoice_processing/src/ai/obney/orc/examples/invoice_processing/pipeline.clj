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

   map-each :into yields the full iteration scope (item + writes), so each
   element looks like {:path … :n … :text … :page-extract {:vendor-name …}}.
   We pull :page-extract out of each iteration record before merging.

   Convention: a 'header' page populates vendor-name + invoice-number with
   real values; continuation pages set those to empty strings and only
   carry line-items. We split by header presence and attach continuation
   pages to the most-recent header."
  [{:keys [inputs]}]
  (let [iter-results (or (:page-extracts inputs) [])
        extracts (->> iter-results
                      (keep (fn [r] (cond
                                      ;; iteration-scope shape (preferred)
                                      (and (map? r) (map? (:page-extract r)))
                                      (:page-extract r)
                                      ;; fallback: the writes were the whole iteration
                                      (map? r) r
                                      :else nil))))
        header? (fn [e] (and (string? (:invoice-number e))
                             (not (clojure.string/blank? (:invoice-number e)))))
        ;; Walk in document order, attaching continuations to the prior header.
        invoices (loop [[e & more] extracts
                        current nil
                        acc []]
                   (cond
                     (nil? e)
                     (cond-> acc current (conj current))

                     (header? e)
                     (recur more
                            (assoc (select-keys e [:vendor-name :invoice-number :date
                                                   :due-date :subtotal :tax :total])
                                   :line-items (vec (or (:line-items e) [])))
                            (cond-> acc current (conj current)))

                     :else
                     (recur more
                            (if current
                              (update current :line-items (fnil into [])
                                      (or (:line-items e) []))
                              ;; Continuation with no prior header — treat as standalone
                              {:vendor-name "" :invoice-number "" :date "" :due-date ""
                               :subtotal 0.0 :tax 0.0 :total 0.0
                               :line-items (vec (or (:line-items e) []))})
                            acc)))
        total (reduce + 0.0 (keep :total invoices))]
    {:result {:invoices invoices
              :total-amount total
              :summary (str "Processed " (count invoices) " invoices across "
                            (count extracts) " pages.")}}))

(defn- unique-sheet-names
  "Dedupe sheet names by appending a counter to collisions. Excel/POI rejects
   duplicates, and real LLM output may produce identical vendor+invoice combos
   across multiple result entries."
  [base-names]
  (let [seen (volatile! {})]
    (mapv (fn [name]
            (let [n (clojure.string/trim (or name "Invoice"))
                  base (if (clojure.string/blank? n) "Invoice" n)
                  count-now ((vswap! seen update base (fnil inc 0)) base)]
              (if (= 1 count-now) base (str base " #" count-now))))
          base-names)))

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
        invoices    (or (:invoices result) [])
        summary-rows (mapv (fn [{:keys [vendor-name invoice-number date due-date
                                        subtotal tax total]}]
                             [vendor-name invoice-number date due-date
                              (or subtotal 0.0) (or tax 0.0) (or total 0.0)])
                           invoices)
        item-cols   [{:header "Description" :width 40}
                     {:header "Quantity"    :width 10}
                     {:header "Unit Price"  :width 12}
                     {:header "Amount"      :width 12}]
        sheet-names (unique-sheet-names
                      (mapv (fn [{:keys [vendor-name invoice-number]}]
                              (str (or vendor-name "Invoice") " "
                                   (or invoice-number "")))
                            invoices))
        per-invoice (mapv (fn [name {:keys [line-items]}]
                            {:name name
                             :columns item-cols
                             :rows (mapv (fn [{:keys [description quantity unit-price amount]}]
                                           [description quantity unit-price amount])
                                         line-items)})
                          sheet-names invoices)
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
          :model "openai/gpt-5.4-mini"
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
